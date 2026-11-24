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
    fun backupRules_useOnlyValidAndroidDomainsAndPathsWithoutWildcards() {
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
    }

    @Test
    fun allEncryptedStoresReferencedInMainSources_useHxPrefixAndAreExcludedFromBackup() {
        val mainJavaDir = File(moduleRoot, "src/main/java")
        assertTrue("Expected main source dir at ${mainJavaDir.path}", mainJavaDir.isDirectory)

        val discoveredDataStoreLogicalNames = linkedSetOf(
            DataStoreCredentialsStore.CREDENTIALS_LOGICAL_NAME,
            "site_cookies",
        )
        val encryptedCallRegex = Regex("""(?:encryptedFileName|wrapSerializer)\(\s*"([^"]+)"\s*[,)]""")
        val directHxDataStoreRegex = Regex("""dataStoreFile\(\s*"(hx_[^"]+)"\s*\)""")
        val directHxDbRegex = Regex("""databaseBuilder\([^)]*,\s*"(hx_[^"]+)"\s*\)""")

        val discoveredDirectDataStoreFiles = linkedSetOf<String>()
        val discoveredHxDatabases = linkedSetOf<String>()

        mainJavaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                encryptedCallRegex.findAll(text).forEach { match ->
                    discoveredDataStoreLogicalNames.add(match.groupValues[1])
                }
                directHxDataStoreRegex.findAll(text).forEach { match ->
                    discoveredDirectDataStoreFiles.add(match.groupValues[1])
                }
                directHxDbRegex.findAll(text).forEach { match ->
                    discoveredHxDatabases.add(match.groupValues[1])
                }
            }

        for (logicalName in discoveredDataStoreLogicalNames) {
            val encryptedName = aeadProvider.encryptedFileName(logicalName)
            assertTrue(
                "Encrypted store '$logicalName' must resolve to '${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix " +
                    "(got '$encryptedName'). All new encrypted files must use the " +
                    "'${AeadProvider.ENCRYPTED_FILE_PREFIX}' prefix via AeadProvider.encryptedFileName(...).",
                encryptedName.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX),
            )
            assertExcludedEverywhere("file", "datastore/$encryptedName")
        }

        for (fileName in discoveredDirectDataStoreFiles) {
            assertExcludedEverywhere("file", "datastore/$fileName")
        }
        for (dbName in discoveredHxDatabases) {
            assertExcludedEverywhere("database", dbName)
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
        return extractExcludeRules(root)
    }

    private fun parseDataExtractionRules(xmlFile: File): ExtractionSections {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val root = doc.documentElement
        assertTrue(
            "Expected root element <data-extraction-rules>, got <${root.tagName}>",
            root.tagName == "data-extraction-rules",
        )
        val cloudBackupNode = directChildElement(root, "cloud-backup")
        val deviceTransferNode = directChildElement(root, "device-transfer")
        assertTrue("Missing <cloud-backup> section in ${xmlFile.name}", cloudBackupNode != null)
        assertTrue("Missing <device-transfer> section in ${xmlFile.name}", deviceTransferNode != null)
        return ExtractionSections(
            cloudBackup = extractExcludeRules(cloudBackupNode!!),
            deviceTransfer = extractExcludeRules(deviceTransferNode!!),
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
    }
}
