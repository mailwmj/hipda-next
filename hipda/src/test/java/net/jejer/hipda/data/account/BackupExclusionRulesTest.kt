package net.jejer.hipda.data.account

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.security.AeadProvider
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import net.jejer.hipda.di.SettingsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

class BackupExclusionRulesTest {

    private lateinit var aeadProvider: AeadProvider
    private lateinit var moduleRoot: File
    private lateinit var fullBackupRules: List<BackupRule>
    private lateinit var cloudBackupRules: List<BackupRule>
    private lateinit var deviceTransferRules: List<BackupRule>

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        aeadProvider = TinkAeadProvider(DummyKeysetBackend())
        moduleRoot = locateHipdaModuleRoot()

        val backupConfigFile = File(moduleRoot, "src/main/res/xml/backup_config.xml")
        val extractionRulesFile = File(moduleRoot, "src/main/res/xml/data_extraction_rules.xml")
        assertTrue("Missing backup_config.xml at ${backupConfigFile.path}", backupConfigFile.isFile)
        assertTrue("Missing data_extraction_rules.xml at ${extractionRulesFile.path}", extractionRulesFile.isFile)

        fullBackupRules = parseFullBackupContent(backupConfigFile)
        val extractionSections = parseDataExtractionRules(extractionRulesFile)
        cloudBackupRules = extractionSections.cloudBackup
        deviceTransferRules = extractionSections.deviceTransfer
    }

    @Test
    fun backupRules_useOnlyValidAndroidDomainsAndPathsWithoutWildcardsOrIncludeNodes() {
        val sections = mapOf(
            "backup_config.xml <full-backup-content>" to fullBackupRules,
            "data_extraction_rules.xml <cloud-backup>" to cloudBackupRules,
            "data_extraction_rules.xml <device-transfer>" to deviceTransferRules,
        )

        for ((sectionName, rules) in sections) {
            assertTrue("$sectionName must declare exclude rules", rules.isNotEmpty())
            for (rule in rules) {
                assertTrue(
                    "$sectionName contains invalid domain '${rule.domain}'",
                    rule.domain in VALID_DOMAINS,
                )
                assertFalse(
                    "$sectionName path '${rule.path}' uses unsupported wildcard characters; " +
                        "Android FullBackup.BackupScheme only matches exact file or directory paths.",
                    rule.path.contains('*') || rule.path.contains('?'),
                )
                assertFalse(
                    "$sectionName path '${rule.path}' contains illegal '..' or '//' sequence.",
                    rule.path.contains("..") || rule.path.contains("//"),
                )
            }
        }
    }

    @Test
    fun aospMatcher_doesNotFalselyTreatFilenamePrefixAsExclusion() {
        val brokenPrefixRules = listOf(
            BackupRule(domain = "sharedpref", path = "hx_"),
            BackupRule(domain = "file", path = "datastore/hx_"),
            BackupRule(domain = "database", path = "hx_"),
        )

        assertFalse(isExcludedByAosp(brokenPrefixRules, "sharedpref", "hx_tink_keysets.xml"))
        assertFalse(isExcludedByAosp(brokenPrefixRules, "file", "datastore/hx_credentials"))
        assertFalse(isExcludedByAosp(brokenPrefixRules, "database", "hx_secret.db"))
    }

    @Test
    fun allEncryptedStoresAndKeysets_areExcludedInCloudBackupDeviceTransferAndLegacyBackup() {
        val keysetPrefPath = "${AeadProvider.KEYSET_PREF_FILE}.xml"
        val credentialsPath = "datastore/${aeadProvider.encryptedFileName("credentials")}"
        val siteCookiesPath = "datastore/${aeadProvider.encryptedFileName("site_cookies")}"

        assertTrue(
            "Keyset pref file must keep '${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix",
            keysetPrefPath.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
        )
        assertTrue(
            "Credentials file must keep '${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix",
            aeadProvider.encryptedFileName("credentials").startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
        )
        assertTrue(
            "Site cookies file must keep '${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix",
            aeadProvider.encryptedFileName("site_cookies").startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
        )

        val requiredExclusions = listOf(
            "sharedpref" to keysetPrefPath,
            "file" to credentialsPath,
            "file" to siteCookiesPath,
        )

        for ((domain, targetPath) in requiredExclusions) {
            assertExcludedEverywhere(domain, targetPath)
        }

        // Also assert hx_tink_keysets.xml has an explicit file rule (not only directory ".") so
        // fresh-install restore checks block it even before shared_prefs/ exists on disk.
        val allSections = mapOf(
            "backup_config.xml <full-backup-content>" to fullBackupRules,
            "data_extraction_rules.xml <cloud-backup>" to cloudBackupRules,
            "data_extraction_rules.xml <device-transfer>" to deviceTransferRules,
        )
        for ((sectionName, rules) in allSections) {
            assertTrue(
                "$sectionName must explicitly exclude sharedpref '$keysetPrefPath' for restore safety.",
                rules.any {
                    it.domain == "sharedpref" &&
                        (normalizePath(it.path) == keysetPrefPath ||
                            normalizePath(it.path) == AeadProvider.KEYSET_PREF_FILE)
                },
            )
            assertTrue(
                "$sectionName should also exclude domain 'sharedpref' path '.' to cover any future hx_* keyset prefs.",
                rules.any { it.domain == "sharedpref" && normalizePath(it.path).isEmpty() },
            )
        }
    }

    @Test
    fun unencryptedUserSettingsAndRoomDatabase_remainEligibleForBackup() {
        val settingsPath = "datastore/${SettingsModule.SETTINGS_STORE_NAME}.preferences_pb"
        val roomDbPath = HiDatabase.DATABASE_NAME

        assertFalse(
            "backup_config.xml must not exclude unencrypted user settings ($settingsPath)",
            isExcludedByAosp(fullBackupRules, "file", settingsPath),
        )
        assertFalse(
            "data_extraction_rules.xml <cloud-backup> must not exclude unencrypted user settings ($settingsPath)",
            isExcludedByAosp(cloudBackupRules, "file", settingsPath),
        )
        assertFalse(
            "data_extraction_rules.xml <device-transfer> must not exclude unencrypted user settings ($settingsPath)",
            isExcludedByAosp(deviceTransferRules, "file", settingsPath),
        )
        assertFalse(
            "backup_config.xml must not exclude unencrypted Room database ($roomDbPath)",
            isExcludedByAosp(fullBackupRules, "database", roomDbPath),
        )
        assertFalse(
            "data_extraction_rules.xml <cloud-backup> must not exclude unencrypted Room database ($roomDbPath)",
            isExcludedByAosp(cloudBackupRules, "database", roomDbPath),
        )
        assertFalse(
            "data_extraction_rules.xml <device-transfer> must not exclude unencrypted Room database ($roomDbPath)",
            isExcludedByAosp(deviceTransferRules, "database", roomDbPath),
        )
    }

    @Test
    fun allEncryptedStoresReferencedInMainSources_areDiscoveredByScannerAndExcludedFromBackup() {
        val mainJavaDir = File(moduleRoot, "src/main/java")
        assertTrue("Expected main source dir at ${mainJavaDir.path}", mainJavaDir.isDirectory)

        val sources = mainJavaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file -> SourceFile(path = file.relativeTo(mainJavaDir).path, content = file.readText(Charsets.UTF_8)) }
            .toList()

        val scanResult = scanSourcesForEncryptedStores(sources)

        // Confirm the scanner genuinely discovers existing encrypted stores from source code
        // (via const val + named argument resolution) rather than relying on hardcoded entries.
        assertTrue(
            "Expected source scanner to discover 'hx_credentials' from CredentialsModule.kt, got ${scanResult.encryptedDataStoreFiles}",
            "hx_credentials" in scanResult.encryptedDataStoreFiles,
        )
        assertTrue(
            "Expected source scanner to discover 'hx_tink_keysets.xml' from TinkAeadProvider.kt, got ${scanResult.encryptedSharedPrefFiles}",
            "hx_tink_keysets.xml" in scanResult.encryptedSharedPrefFiles,
        )

        assertScanResultCoveredByBackupRules(scanResult)
    }

    @Test
    fun sourceScanner_catchesConstValNamedArgUnexcludedStoreAndUnprefixedDatastoreAndIgnoresKdoc() {
        // 1. KDoc, nested block comments, and line comments must not trigger false positives.
        val commentOnlySource = SourceFile(
            path = "ExampleDocs.kt",
            content = """
                package net.jejer.hipda.data.docs
                /**
                 * Example usage:
                 * const val DOC_ONLY = "doc_only"
                 * aeadProvider.wrapSerializer(logicalName = "doc_only", wrappedSerializer = Dummy)
                 * context.dataStoreFile("hx_doc_only")
                 */
                /* Outer comment /* nested block comment */
                 * aeadProvider.encryptedFileName("nested_comment_store")
                 */
                // aeadProvider.wrapSerializer("line_comment_store", Dummy)
                class ExampleDocs
            """.trimIndent(),
        )
        val commentScan = scanSourcesForEncryptedStores(listOf(commentOnlySource))
        assertEquals(emptySet<String>(), commentScan.encryptedDataStoreFiles)
        assertEquals(emptySet<String>(), commentScan.encryptedSharedPrefFiles)
        assertEquals(emptySet<String>(), commentScan.encryptedDatabaseFiles)
        assertEquals(emptyList<String>(), commentScan.prefixViolations)

        // 2. Synthetic source with const val FOO = "foo" + wrapSerializer(logicalName = FOO, ...)
        //    must be caught and fail against the backup XML rules because hx_foo is not excluded.
        val unexcludedSource = SourceFile(
            path = "UnexcludedModule.kt",
            content = """
                package net.jejer.hipda.data.sample
                object UnexcludedModule {
                    private const val FOO: String = "foo"
                    fun create(aeadProvider: AeadProvider) {
                        aeadProvider.wrapSerializer(
                            logicalName = FOO,
                            wrappedSerializer = DummySerializer,
                        )
                    }
                }
            """.trimIndent(),
        )
        val unexcludedScan = scanSourcesForEncryptedStores(listOf(unexcludedSource))
        assertTrue(
            "Scanner must resolve const val + named argument in wrapSerializer to 'hx_foo'",
            "hx_foo" in unexcludedScan.encryptedDataStoreFiles,
        )
        assertThrowsAssertionError("datastore/hx_foo") {
            assertScanResultCoveredByBackupRules(unexcludedScan)
        }

        // 3. Synthetic source using wrapSerializer alongside dataStoreFile("plain_secret") without
        //    encryptedFileName(...) must report a prefix violation instead of silently passing.
        val missingEncryptedFileNameSource = SourceFile(
            path = "MissingPrefixModule.kt",
            content = """
                package net.jejer.hipda.data.sample
                object MissingPrefixModule {
                    private const val SECRET_NAME = "plain_secret"
                    fun create(context: Context, aeadProvider: AeadProvider) {
                        val file = context.dataStoreFile(SECRET_NAME)
                        val serializer = aeadProvider.wrapSerializer(
                            logicalName = SECRET_NAME,
                            wrappedSerializer = DummySerializer,
                        )
                    }
                }
            """.trimIndent(),
        )
        val violationScan = scanSourcesForEncryptedStores(listOf(missingEncryptedFileNameSource))
        assertTrue(
            "Scanner must flag DataStore using wrapSerializer without encryptedFileName / hx_ prefix",
            violationScan.prefixViolations.isNotEmpty(),
        )
        assertThrowsAssertionError(AeadProvider.ENCRYPTED_FILE_PREFIX) {
            assertScanResultCoveredByBackupRules(violationScan)
        }
    }

    private fun assertScanResultCoveredByBackupRules(scanResult: SourceScanResult) {
        val guidance = "All new encrypted files must use the '${AeadProvider.ENCRYPTED_FILE_PREFIX}' " +
            "prefix (via AeadProvider.encryptedFileName) AND be explicitly excluded in both " +
            "hipda/src/main/res/xml/backup_config.xml and " +
            "hipda/src/main/res/xml/data_extraction_rules.xml (<cloud-backup> and <device-transfer>), " +
            "because Android Auto Backup does not support wildcards or filename prefixes."

        assertTrue(
            "Found encrypted DataStore(s) not generated via AeadProvider.encryptedFileName(...) / " +
                "'${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix: ${scanResult.prefixViolations}. $guidance",
            scanResult.prefixViolations.isEmpty(),
        )

        for (fileName in scanResult.encryptedDataStoreFiles) {
            assertTrue(
                "Encrypted DataStore file '$fileName' must start with '${AeadProvider.ENCRYPTED_FILE_PREFIX}'. $guidance",
                fileName.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
            )
            assertExcludedEverywhere("file", "datastore/$fileName")
        }
        for (prefName in scanResult.encryptedSharedPrefFiles) {
            assertTrue(
                "Encrypted SharedPreferences file '$prefName' must start with '${AeadProvider.ENCRYPTED_FILE_PREFIX}'. $guidance",
                prefName.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
            )
            assertExcludedEverywhere("sharedpref", prefName)
        }
        for (dbName in scanResult.encryptedDatabaseFiles) {
            assertTrue(
                "Encrypted database '$dbName' must start with '${AeadProvider.ENCRYPTED_FILE_PREFIX}'. $guidance",
                dbName.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
            )
            assertExcludedEverywhere("database", dbName)
        }
    }

    private fun scanSourcesForEncryptedStores(sources: List<SourceFile>): SourceScanResult {
        val strippedFiles = sources.map { SourceFile(it.path, stripKotlinComments(it.content)) }
        val constantTables = collectStringConstants(strippedFiles)

        val encryptedDataStoreFiles = linkedSetOf<String>()
        val encryptedSharedPrefFiles = linkedSetOf<String>()
        val encryptedDatabaseFiles = linkedSetOf<String>()
        val prefixViolations = mutableListOf<String>()

        for (file in strippedFiles) {
            val fileConstants = constantTables.perFile[file.path].orEmpty()
            val resolver = ConstantResolver(fileConstants, constantTables.global)

            val wrapCalls = findFunctionCalls(file.content, "wrapSerializer")
            val encryptedNameCalls = findFunctionCalls(file.content, "encryptedFileName")
            val dataStoreFileCalls = findFunctionCalls(file.content, "dataStoreFile") +
                findFunctionCalls(file.content, "preferencesDataStoreFile")
            val sharedPrefCalls = findFunctionCalls(file.content, "getSharedPreferences")
            val dbBuilderCalls = findFunctionCalls(file.content, "databaseBuilder")

            val fileUsesWrapSerializer = wrapCalls.isNotEmpty()
            for (call in wrapCalls) {
                val argExpr = call.namedOrPositional("logicalName", 0) ?: continue
                val resolved = resolver.resolveExpression(argExpr) ?: continue
                encryptedDataStoreFiles.add(aeadProvider.encryptedFileName(resolved))
            }

            for (call in encryptedNameCalls) {
                val argExpr = call.namedOrPositional("logicalName", 0) ?: continue
                val resolved = resolver.resolveExpression(argExpr) ?: continue
                encryptedDataStoreFiles.add(aeadProvider.encryptedFileName(resolved))
            }

            for (call in dataStoreFileCalls) {
                val argExpr = call.namedOrPositional("fileName", 0)
                    ?: call.namedOrPositional("name", 0)
                    ?: continue
                val nestedEncryptedCalls = findFunctionCalls(argExpr, "encryptedFileName")
                if (nestedEncryptedCalls.isNotEmpty()) {
                    val innerArg = nestedEncryptedCalls.first().namedOrPositional("logicalName", 0)
                    val resolvedInner = innerArg?.let(resolver::resolveExpression)
                    if (resolvedInner != null) {
                        val fileName = aeadProvider.encryptedFileName(resolvedInner)
                        val normalized = if (call.functionName == "preferencesDataStoreFile" &&
                            !fileName.endsWith(".preferences_pb")
                        ) {
                            "$fileName.preferences_pb"
                        } else {
                            fileName
                        }
                        encryptedDataStoreFiles.add(normalized)
                    }
                    continue
                }

                val resolved = resolver.resolveExpression(argExpr)
                if (fileUsesWrapSerializer) {
                    if (resolved == null || !resolved.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX)) {
                        prefixViolations.add(
                            "${file.path}: ${call.functionName}($argExpr) in a file using wrapSerializer " +
                                "must derive its file name via AeadProvider.encryptedFileName(...) " +
                                "with '${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix",
                        )
                    } else {
                        val fileName = if (call.functionName == "preferencesDataStoreFile" &&
                            !resolved.endsWith(".preferences_pb")
                        ) {
                            "$resolved.preferences_pb"
                        } else {
                            resolved
                        }
                        encryptedDataStoreFiles.add(fileName)
                    }
                } else if (resolved != null && resolved.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX)) {
                    val fileName = if (call.functionName == "preferencesDataStoreFile" &&
                        !resolved.endsWith(".preferences_pb")
                    ) {
                        "$resolved.preferences_pb"
                    } else {
                        resolved
                    }
                    encryptedDataStoreFiles.add(fileName)
                }
            }

            for (call in sharedPrefCalls) {
                val argExpr = call.namedOrPositional("name", 0) ?: continue
                val resolved = resolver.resolveExpression(argExpr) ?: continue
                if (resolved.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX)) {
                    val xmlName = if (resolved.endsWith(".xml")) resolved else "$resolved.xml"
                    encryptedSharedPrefFiles.add(xmlName)
                }
            }

            for (call in dbBuilderCalls) {
                val argExpr = call.namedOrPositional("name", 2) ?: continue
                val resolved = resolver.resolveExpression(argExpr) ?: continue
                if (resolved.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX)) {
                    encryptedDatabaseFiles.add(resolved)
                }
            }
        }

        return SourceScanResult(
            encryptedDataStoreFiles = encryptedDataStoreFiles,
            encryptedSharedPrefFiles = encryptedSharedPrefFiles,
            encryptedDatabaseFiles = encryptedDatabaseFiles,
            prefixViolations = prefixViolations,
        )
    }

    private fun stripKotlinComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            // Triple-quoted raw string literal
            if (i + 2 < n && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"') {
                out.append("\"\"\"")
                i += 3
                while (i < n) {
                    if (i + 2 < n && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"') {
                        out.append("\"\"\"")
                        i += 3
                        break
                    }
                    out.append(source[i++])
                }
                continue
            }
            // Regular string literal
            if (source[i] == '"') {
                out.append(source[i++])
                while (i < n) {
                    val ch = source[i++]
                    out.append(ch)
                    if (ch == '\\' && i < n) {
                        out.append(source[i++])
                    } else if (ch == '"') {
                        break
                    }
                }
                continue
            }
            // Char literal
            if (source[i] == '\'') {
                out.append(source[i++])
                while (i < n) {
                    val ch = source[i++]
                    out.append(ch)
                    if (ch == '\\' && i < n) {
                        out.append(source[i++])
                    } else if (ch == '\'') {
                        break
                    }
                }
                continue
            }
            // Line comment
            if (i + 1 < n && source[i] == '/' && source[i + 1] == '/') {
                i += 2
                while (i < n && source[i] != '\n') {
                    i++
                }
                continue
            }
            // Block / KDoc comment (Kotlin allows nesting)
            if (i + 1 < n && source[i] == '/' && source[i + 1] == '*') {
                var depth = 1
                i += 2
                out.append(' ')
                while (i < n && depth > 0) {
                    if (i + 1 < n && source[i] == '/' && source[i + 1] == '*') {
                        depth++
                        i += 2
                    } else if (i + 1 < n && source[i] == '*' && source[i + 1] == '/') {
                        depth--
                        i += 2
                    } else {
                        if (source[i] == '\n') out.append('\n')
                        i++
                    }
                }
                continue
            }
            out.append(source[i++])
        }
        return out.toString()
    }

    private fun collectStringConstants(strippedFiles: List<SourceFile>): ConstantTables {
        val rawPerFile = mutableMapOf<String, MutableMap<String, String>>()
        val rawGlobal = mutableMapOf<String, String>()
        val ambiguousSimpleNames = mutableSetOf<String>()

        for (file in strippedFiles) {
            val fileMap = rawPerFile.getOrPut(file.path) { mutableMapOf() }
            for (match in CONST_VAL_STRING_REGEX.findAll(file.content)) {
                val name = match.groupValues[1]
                val rawValue = match.groupValues[2]
                fileMap[name] = rawValue
                val existing = rawGlobal[name]
                if (existing != null && existing != rawValue) {
                    ambiguousSimpleNames.add(name)
                } else {
                    rawGlobal[name] = rawValue
                }
            }
        }
        ambiguousSimpleNames.forEach { rawGlobal.remove(it) }

        fun interpolate(value: String, local: Map<String, String>, depth: Int = 0): String? {
            if (depth > 4) return null
            if (!value.contains('$')) return value
            var unresolved = false
            val replaced = TEMPLATE_VAR_REGEX.replace(value) { match ->
                val varName = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val refRaw = local[varName] ?: rawGlobal[varName]
                val resolved = refRaw?.let { interpolate(it, local, depth + 1) }
                if (resolved == null) {
                    unresolved = true
                    match.value
                } else {
                    resolved
                }
            }
            return if (unresolved || replaced.contains('$')) null else replaced
        }

        val resolvedPerFile = rawPerFile.mapValues { (_, local) ->
            local.mapNotNull { (k, v) -> interpolate(v, local)?.let { k to it } }.toMap()
        }
        val resolvedGlobal = rawGlobal.mapNotNull { (k, v) ->
            interpolate(v, rawGlobal)?.let { k to it }
        }.toMap()
        return ConstantTables(perFile = resolvedPerFile, global = resolvedGlobal)
    }

    private fun findFunctionCalls(code: String, functionName: String): List<ParsedCall> {
        val pattern = Regex("""(?<!\w)${Regex.escape(functionName)}\s*\(""")
        val calls = mutableListOf<ParsedCall>()
        for (match in pattern.findAll(code)) {
            // Skip function declarations like `fun wrapSerializer(...)` or `fun <T> wrapSerializer(...)`.
            val prefixWindow = code.substring(maxOf(0, match.range.first - 40), match.range.first)
            if (DECLARATION_PREFIX_REGEX.containsMatchIn(prefixWindow)) continue

            val openParenIndex = match.range.last
            val closeParenIndex = findMatchingParen(code, openParenIndex) ?: continue
            val rawArgs = code.substring(openParenIndex + 1, closeParenIndex)
            calls.add(
                ParsedCall(
                    functionName = functionName,
                    arguments = splitTopLevelArguments(rawArgs),
                ),
            )
        }
        return calls
    }

    private fun findMatchingParen(code: String, openIndex: Int): Int? {
        var depth = 0
        var i = openIndex
        val n = code.length
        while (i < n) {
            val ch = code[i]
            if (ch == '"') {
                i++
                while (i < n) {
                    val c = code[i++]
                    if (c == '\\' && i < n) {
                        i++
                    } else if (c == '"') {
                        break
                    }
                }
                continue
            }
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return null
    }

    private fun splitTopLevelArguments(rawArgs: String): List<ParsedArg> {
        if (rawArgs.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var i = 0
        val n = rawArgs.length
        while (i < n) {
            val ch = rawArgs[i]
            if (ch == '"') {
                current.append(ch)
                i++
                while (i < n) {
                    val c = rawArgs[i++]
                    current.append(c)
                    if (c == '\\' && i < n) {
                        current.append(rawArgs[i++])
                    } else if (c == '"') {
                        break
                    }
                }
                continue
            }
            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    parts.add(current.toString().trim())
                    current.clear()
                    i++
                    continue
                }
            }
            current.append(ch)
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)

        return parts.map { part ->
            val namedMatch = NAMED_ARG_REGEX.matchEntire(part)
            if (namedMatch != null) {
                ParsedArg(name = namedMatch.groupValues[1], expression = namedMatch.groupValues[2].trim())
            } else {
                ParsedArg(name = null, expression = part)
            }
        }
    }

    private fun assertThrowsAssertionError(expectedSubstring: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected AssertionError containing '$expectedSubstring'")
        } catch (e: AssertionError) {
            assertTrue(
                "Expected AssertionError message to contain '$expectedSubstring', got: ${e.message}",
                e.message.orEmpty().contains(expectedSubstring),
            )
        }
    }

    private fun assertExcludedEverywhere(domain: String, targetPath: String) {
        val guidance = "All new encrypted files must use the '${AeadProvider.ENCRYPTED_FILE_PREFIX}' " +
            "prefix (via AeadProvider.encryptedFileName) AND be explicitly excluded in both " +
            "hipda/src/main/res/xml/backup_config.xml and " +
            "hipda/src/main/res/xml/data_extraction_rules.xml (<cloud-backup> and <device-transfer>), " +
            "because Android Auto Backup does not support wildcards or filename prefixes."

        assertTrue(
            "backup_config.xml <full-backup-content> does not exclude domain='$domain' path='$targetPath'. $guidance",
            isExcludedByAosp(fullBackupRules, domain, targetPath),
        )
        assertTrue(
            "data_extraction_rules.xml <cloud-backup> does not exclude domain='$domain' path='$targetPath'. $guidance",
            isExcludedByAosp(cloudBackupRules, domain, targetPath),
        )
        assertTrue(
            "data_extraction_rules.xml <device-transfer> does not exclude domain='$domain' path='$targetPath'. $guidance",
            isExcludedByAosp(deviceTransferRules, domain, targetPath),
        )
    }

    private fun isExcludedByAosp(
        rules: List<BackupRule>,
        targetDomain: String,
        targetRelativePath: String,
    ): Boolean {
        val normalizedTarget = normalizePath(targetRelativePath)
        val rootEquivalentTarget = domainToRootRelativePath(targetDomain, normalizedTarget)

        return rules.any { rule ->
            val normalizedRule = normalizePath(rule.path)
            when {
                rule.domain == targetDomain -> matchesPathInSameDomain(
                    domain = targetDomain,
                    normalizedRule = normalizedRule,
                    normalizedTarget = normalizedTarget,
                )
                rule.domain == "root" && rootEquivalentTarget != null -> matchesPathInSameDomain(
                    domain = "root",
                    normalizedRule = normalizedRule,
                    normalizedTarget = rootEquivalentTarget,
                )
                else -> false
            }
        }
    }

    private fun matchesPathInSameDomain(
        domain: String,
        normalizedRule: String,
        normalizedTarget: String,
    ): Boolean {
        if (normalizedRule.isEmpty()) return true // path="." excludes the entire domain directory.
        if (normalizedRule == normalizedTarget) return true
        if (normalizedTarget.startsWith("$normalizedRule/")) return true
        if (domain == "sharedpref" && !normalizedRule.endsWith(".xml") &&
            "$normalizedRule.xml" == normalizedTarget
        ) {
            return true
        }
        return false
    }

    private fun domainToRootRelativePath(domain: String, relativePath: String): String? =
        when (domain) {
            "file" -> "files/$relativePath"
            "sharedpref" -> "shared_prefs/$relativePath"
            "database" -> "databases/$relativePath"
            else -> null
        }

    private fun normalizePath(rawPath: String): String {
        var path = rawPath.trim()
        while (path.startsWith("./")) {
            path = path.removePrefix("./")
        }
        path = path.trimStart('/').trimEnd('/')
        return if (path == ".") "" else path
    }

    private fun parseFullBackupContent(xmlFile: File): List<BackupRule> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val root = doc.documentElement
        assertTrue(
            "Expected root element <full-backup-content>, got <${root.tagName}>",
            root.tagName == "full-backup-content",
        )
        assertNoIncludeElements(root, xmlFile.name)
        return extractExcludeRules(root)
    }

    private fun parseDataExtractionRules(xmlFile: File): ExtractionSections {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val root = doc.documentElement
        assertTrue(
            "Expected root element <data-extraction-rules>, got <${root.tagName}>",
            root.tagName == "data-extraction-rules",
        )
        assertNoIncludeElements(root, xmlFile.name)
        val cloudBackupNode = directChildElement(root, "cloud-backup")
        val deviceTransferNode = directChildElement(root, "device-transfer")
        assertTrue("Missing <cloud-backup> section in ${xmlFile.name}", cloudBackupNode != null)
        assertTrue("Missing <device-transfer> section in ${xmlFile.name}", deviceTransferNode != null)
        assertNoIncludeElements(cloudBackupNode!!, "${xmlFile.name} <cloud-backup>")
        assertNoIncludeElements(deviceTransferNode!!, "${xmlFile.name} <device-transfer>")
        return ExtractionSections(
            cloudBackup = extractExcludeRules(cloudBackupNode),
            deviceTransfer = extractExcludeRules(deviceTransferNode),
        )
    }

    private fun assertNoIncludeElements(container: Element, location: String) {
        val includeNodes = container.getElementsByTagName("include")
        assertEquals(
            "$location must not contain <include> elements: adding <include> switches Android " +
                "backup into allowlist mode and breaks implicit inclusion of unencrypted data.",
            0,
            includeNodes.length,
        )
    }

    private fun directChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tagName) {
                return node
            }
        }
        return null
    }

    private fun extractExcludeRules(container: Element): List<BackupRule> {
        val rules = mutableListOf<BackupRule>()
        val children = container.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == "exclude") {
                val domain = node.getAttribute("domain").trim()
                val path = node.getAttribute("path").trim()
                assertTrue("Found <exclude> with blank domain in <${container.tagName}>", domain.isNotEmpty())
                assertTrue("Found <exclude> with blank path in <${container.tagName}>", path.isNotEmpty())
                rules.add(BackupRule(domain = domain, path = path))
            }
        }
        return rules
    }

    private fun locateHipdaModuleRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(cwd, File(cwd, "hipda"))
        return candidates.firstOrNull {
            File(it, "src/main/res/xml/backup_config.xml").isFile
        } ?: error("Unable to locate :hipda module root from ${cwd.absolutePath}")
    }

    private data class SourceFile(
        val path: String,
        val content: String,
    )

    private data class ConstantTables(
        val perFile: Map<String, Map<String, String>>,
        val global: Map<String, String>,
    )

    private class ConstantResolver(
        private val local: Map<String, String>,
        private val global: Map<String, String>,
    ) {
        fun resolveExpression(rawExpr: String): String? {
            val expr = rawExpr.trim()
            val literalMatch = STRING_LITERAL_REGEX.matchEntire(expr)
            if (literalMatch != null) {
                val raw = literalMatch.groupValues[1]
                return if (raw.contains('$')) null else raw
            }
            if (QUALIFIED_IDENT_REGEX.matches(expr)) {
                val simpleName = expr.substringAfterLast('.')
                return local[simpleName] ?: global[simpleName]
            }
            return null
        }
    }

    private data class ParsedArg(
        val name: String?,
        val expression: String,
    )

    private data class ParsedCall(
        val functionName: String,
        val arguments: List<ParsedArg>,
    ) {
        fun namedOrPositional(paramName: String, index: Int): String? {
            arguments.firstOrNull { it.name == paramName }?.let { return it.expression }
            val positional = arguments.getOrNull(index)
            return if (positional != null && positional.name == null) positional.expression else null
        }
    }

    private data class SourceScanResult(
        val encryptedDataStoreFiles: Set<String>,
        val encryptedSharedPrefFiles: Set<String>,
        val encryptedDatabaseFiles: Set<String>,
        val prefixViolations: List<String>,
    )

    private data class BackupRule(
        val domain: String,
        val path: String,
    )

    private data class ExtractionSections(
        val cloudBackup: List<BackupRule>,
        val deviceTransfer: List<BackupRule>,
    )

    private class DummyKeysetBackend : KeysetBackend {
        override fun buildAead(normalizedKeysetName: String): Aead =
            KeysetHandle.generateNew(
                PredefinedAeadParameters.AES256_GCM,
                RegistryConfiguration.get(),
            ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        override fun clearKeysetEntry(normalizedKeysetName: String) {}
        override fun deleteMasterKeyAlias() {}
    }

    companion object {
        private val VALID_DOMAINS = setOf("root", "file", "database", "sharedpref", "external")
        private val CONST_VAL_STRING_REGEX =
            Regex("""const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"((?:[^"\\]|\\.)*)\x22""")
        private val TEMPLATE_VAR_REGEX =
            Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")
        private val DECLARATION_PREFIX_REGEX =
            Regex("""\bfun\s+(?:<[^>{}()]+>\s*)?(?:[A-Za-z0-9_.]+\.)?$""")
        private val NAMED_ARG_REGEX =
            Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)(.+)$""", RegexOption.DOT_MATCHES_ALL)
        private val STRING_LITERAL_REGEX = Regex("""^"((?:[^"\\]|\\.)*)"$""")
        private val QUALIFIED_IDENT_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$""")
    }
}
