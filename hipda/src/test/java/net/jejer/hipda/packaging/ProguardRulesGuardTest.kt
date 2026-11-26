package net.jejer.hipda.packaging

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M7-4b R8 / ProGuard 规则与 Release DEX 守卫测试：
 * 1. 验证 `hipda/proguard-rules.txt`（及 `:emoji`/`:core-parser` 的 consumer-rules，如有）中引用的
 *    每个类与包均真实存在于项目主源码或运行时依赖中，杜绝死规则；
 * 2. 禁止残留已删旧依赖（Gson/CookieStore/Glide/Iconics/jobqueue/EventBus/Matisse 等）、已删旧类
 *    （`HiSettingsHelper`/`HiApplication`/`MainFrameActivity` 等）及无理由的全包通配 `-keep`；
 * 3. 验证液态玻璃 `com.kyant.backdrop` / `com.kyant.shapes` 最小定向规则与堆栈行号属性显式保留并写明理由；
 * 4. 附带负向自测证明规则解析器能真实检出死类、死 `-dontwarn`、单/多行无理由通配与禁留命名空间通配；
 * 5. 在 Release APK 与 `mapping.txt` 存在时（CI 环境下强制存在）直接解析二进制 DEX 与映射表，
 *    核验核心组件、Room、WorkManager/HiltWorker、@Serializable、JavascriptInterface、全部 8 个
 *    HiltViewModel、七大核心界面及 Kyant Backdrop/Shapes 存活，且零旧依赖类残留。
 */
class ProguardRulesGuardTest {

    data class ProguardRule(
        val directive: String,
        val baseDirective: String,
        val startLine: Int,
        val header: String,
        val body: String?,
        val leadingComment: String,
        val groupLeadingComment: String,
    )

    data class RuleTargetRef(
        val ruleLine: Int,
        val directive: String,
        val rawPattern: String,
        val isPackageNameDirective: Boolean,
    )

    data class ValidationReport(
        val rules: List<ProguardRule>,
        val deadReferences: List<String>,
        val forbiddenLegacyMatches: List<String>,
        val unreasonedOrBannedWildcardKeeps: List<String>,
        val allFullPackageWildcardKeeps: List<String>,
    )

    class RuntimeClassIndex(
        val classes: Set<String>,
        val packages: Set<String>,
    ) {
        private val platformClassLoader: ClassLoader? = ClassLoader.getSystemClassLoader()?.parent

        fun exists(pattern: String, allowPackageOnly: Boolean = false): Boolean {
            if (pattern.isEmpty() || pattern.startsWith(".") || pattern.endsWith(".")) {
                return false
            }
            if (!pattern.contains('*') && !pattern.contains('?')) {
                if (pattern in classes) return true
                if (allowPackageOnly && pattern in packages) return true
                val resourcePath = pattern.replace('.', '/') + ".class"
                if (platformClassLoader?.getResource(resourcePath) != null) return true
                if (pattern.startsWith("java.") && runCatching { Class.forName(pattern, false, null) }.isSuccess) {
                    return true
                }
                return false
            }
            val regex = proguardGlobToRegex(pattern)
            if (classes.any { regex.matches(it) }) return true
            if (allowPackageOnly && packages.any { regex.matches(it) }) return true
            return false
        }
    }

    @Test
    fun proguardRules_allReferencedClassesAndPackagesExistWithZeroDeadRules() {
        val repoRoot = resolveRepoRoot()
        val index = runtimeClassIndex
        val ruleFiles = collectProjectProguardFiles(repoRoot)
        assertTrue(
            "应至少扫描到 hipda/proguard-rules.txt，实际：${ruleFiles.map { it.relativeTo(repoRoot).path }}",
            ruleFiles.any { it.relativeTo(repoRoot).invariantSeparatorsPath == "hipda/proguard-rules.txt" },
        )

        // Confirm index itself successfully indexed project sources and core runtime dependencies.
        assertTrue("索引应包含应用主入口 MainActivity", index.exists("net.jejer.hipda.feature.app.MainActivity"))
        assertTrue("索引应包含核心解析器 ThreadDetailParser", index.exists("net.jejer.hipda.parser.threaddetail.ThreadDetailParser"))
        assertTrue("索引应包含 EmojiMap", index.exists("net.jejer.hipda.emoji.EmojiMap"))
        assertTrue("索引应包含 Kyant Backdrop", index.exists("com.kyant.backdrop.AndroidRuntimeShader"))
        assertTrue("索引应包含 Kyant Shapes", index.exists("com.kyant.shapes.RoundedRectangularShape"))
        assertFalse("索引不应包含已删旧类 HiSettingsHelper", index.exists("net.jejer.hipda.bean.HiSettingsHelper"))
        assertFalse("索引不应包含已删旧库 CookieStore", index.exists("net.gotev.cookiestore.**"))
        assertFalse("索引不应包含测试专属依赖 JUnit", index.exists("org.junit.Test"))

        for (file in ruleFiles) {
            val rel = file.relativeTo(repoRoot).invariantSeparatorsPath
            val report = validateRules(file.readText(Charsets.UTF_8), index)
            if (report.deadReferences.isNotEmpty()) {
                fail(
                    buildString {
                        appendLine("$rel 存在死规则（引用的类或包在源码与运行时依赖中均不存在）：")
                        report.deadReferences.forEach { appendLine("  - $it") }
                    },
                )
            }
        }
    }

    @Test
    fun proguardRules_forbidsRemovedLegacyLibrariesDeletedClassesAndWildcardKeeps() {
        val repoRoot = resolveRepoRoot()
        val ruleFiles = collectProjectProguardFiles(repoRoot)
        val index = runtimeClassIndex

        for (file in ruleFiles) {
            val rel = file.relativeTo(repoRoot).invariantSeparatorsPath
            val report = validateRules(file.readText(Charsets.UTF_8), index)

            if (report.forbiddenLegacyMatches.isNotEmpty()) {
                fail(
                    buildString {
                        appendLine("$rel 残留已删除旧依赖/旧类或过宽历史规则：")
                        report.forbiddenLegacyMatches.forEach { appendLine("  - $it") }
                    },
                )
            }
            if (report.unreasonedOrBannedWildcardKeeps.isNotEmpty()) {
                fail(
                    buildString {
                        appendLine("$rel 包含禁止或无理由的全包通配 keep：")
                        report.unreasonedOrBannedWildcardKeeps.forEach { appendLine("  - $it") }
                    },
                )
            }
            assertTrue(
                "$rel 应优先收窄到具体类/成员，当前不应保留任何全包通配 keep：${report.allFullPackageWildcardKeeps}",
                report.allFullPackageWildcardKeeps.isEmpty(),
            )
        }
    }

    @Test
    fun proguardRules_retainsTargetedKyantBackdropShapesAndLineNumbersWithRationale() {
        val repoRoot = resolveRepoRoot()
        val proguardFile = File(repoRoot, "hipda/proguard-rules.txt")
        assertTrue("缺少 hipda/proguard-rules.txt", proguardFile.isFile)

        val content = proguardFile.readText(Charsets.UTF_8)
        val rules = parseProguardRules(content)

        // 1. SourceFile,LineNumberTable must be retained for crash log retrace.
        val keepAttributesRule = rules.firstOrNull { it.baseDirective == "-keepattributes" }
        assertTrue("必须保留 -keepattributes SourceFile,LineNumberTable 以支持崩溃堆栈还原", keepAttributesRule != null)
        assertTrue(
            "-keepattributes 必须包含 SourceFile 与 LineNumberTable，实际：${keepAttributesRule!!.header}",
            keepAttributesRule.header.contains("SourceFile") && keepAttributesRule.header.contains("LineNumberTable"),
        )
        assertTrue(
            "-keepattributes 规则前必须写明注释理由",
            meaningfulCommentLength(keepAttributesRule.groupLeadingComment) >= MIN_RATIONALE_LENGTH,
        )

        // 2. Targeted Kyant Backdrop & Shapes rules must be explicitly present with rationale comments.
        val keptClasses = rules
            .filter { it.baseDirective in KEEP_DIRECTIVES }
            .flatMap { rule ->
                extractReferencedTargets(rule).map { target -> target.rawPattern to rule }
            }
            .toMap()

        val requiredBackdropAndShapesClasses = listOf(
            "com.kyant.backdrop.AndroidRuntimeShader",
            "com.kyant.backdrop.RuntimeShaderCacheImpl",
            "com.kyant.backdrop.DrawBackdropElement",
            "com.kyant.backdrop.DrawBackdropNode",
            "com.kyant.backdrop.backdrops.LayerBackdrop",
            "com.kyant.backdrop.backdrops.LayerBackdropElement",
            "com.kyant.backdrop.backdrops.LayerBackdropNode",
            "com.kyant.backdrop.effects.LensKt",
            "com.kyant.shapes.RoundedRectangularShape",
            "com.kyant.shapes.RoundedRectangularShape\$Corners",
        )
        for (className in requiredBackdropAndShapesClasses) {
            val rule = keptClasses[className]
            assertTrue("hipda/proguard-rules.txt 必须显式保留液态玻璃关键类 $className", rule != null)
            assertTrue(
                "$className 的保留规则上方必须写明保留理由注释",
                meaningfulCommentLength(rule!!.groupLeadingComment) >= MIN_RATIONALE_LENGTH,
            )
        }
    }

    @Test
    fun validator_negativeSelfTestsCatchDeadRulesLegacyClassesAndUnreasonedWildcards() {
        val index = runtimeClassIndex

        // 1. Dead removed-library rule & dead deleted-class rule must be flagged as BOTH dead and forbidden.
        val deadLegacySample = """
            -keep class net.gotev.cookiestore.** { *; }
            -keep class net.jejer.hipda.bean.HiSettingsHelper { *; }
            -dontwarn com.zhihu.matisse.**
            -dontwarn org.openjsse.**
            -keep class com.kyant.backdrop.NonExistentShader { *; }
            -keepclassmembers class ** {
                @org.greenrobot.eventbus.Subscribe <methods>;
            }
        """.trimIndent()
        val deadReport = validateRules(deadLegacySample, index)
        assertTrue(
            "必须检出 net.gotev.cookiestore.** 死规则，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("net.gotev.cookiestore.**") },
        )
        assertTrue(
            "必须检出已删类 HiSettingsHelper 死规则，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("net.jejer.hipda.bean.HiSettingsHelper") },
        )
        assertTrue(
            "必须检出死 -dontwarn com.zhihu.matisse.**，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("com.zhihu.matisse.**") },
        )
        assertTrue(
            "必须检出死 -dontwarn org.openjsse.**，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("org.openjsse.**") },
        )
        assertTrue(
            "必须检出同包下不存在的类 com.kyant.backdrop.NonExistentShader，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("com.kyant.backdrop.NonExistentShader") },
        )
        assertTrue(
            "必须检出规则体内的死注解 @org.greenrobot.eventbus.Subscribe，实际：${deadReport.deadReferences}",
            deadReport.deadReferences.any { it.contains("org.greenrobot.eventbus.Subscribe") },
        )
        assertTrue(
            "必须命中已删库/旧类禁用清单，实际：${deadReport.forbiddenLegacyMatches}",
            deadReport.forbiddenLegacyMatches.size >= 4,
        )

        // 2. Unreasoned full-package wildcard keep (single-line and multi-line) must fail even on existing packages.
        val unreasonedWildcardSample = """
            -keep class com.kyant.backdrop.** { *; }
            # short
            -keep public class com.kyant.shapes.* {
                public *;
            }
            # Blank line below breaks rationale attachment.

            -keep class com.kyant.shapes.** { *; }
        """.trimIndent()
        val wildcardReport = validateRules(unreasonedWildcardSample, index)
        assertEquals(
            "3 条无有效理由的全包通配 keep 必须全部被拦截，实际：${wildcardReport.unreasonedOrBannedWildcardKeeps}",
            3,
            wildcardReport.unreasonedOrBannedWildcardKeeps.size,
        )

        // 3. Banned broad namespaces fail EVEN WITH a long comment.
        val bannedNamespaceSample = """
            # Long rationale comment attempting to justify keeping the entire app package under R8 full mode.
            -keep class net.jejer.hipda.** { *; }
            # Long rationale comment attempting to justify keeping all AndroidX classes under R8 full mode.
            -keep class androidx.**
            # Long rationale comment attempting to justify keeping all Jsoup public APIs under R8 full mode.
            -keep public class org.jsoup.** {
                public *;
            }
        """.trimIndent()
        val bannedReport = validateRules(bannedNamespaceSample, index)
        assertEquals(
            "3 条禁留大包通配即使附注释也必须拦截，实际：${bannedReport.unreasonedOrBannedWildcardKeeps}",
            3,
            bannedReport.unreasonedOrBannedWildcardKeeps.size,
        )

        // 4. Targeted rule on an existing class with rationale passes cleanly.
        val validSample = """
            # Keep concrete AGSL shader wrapper because AAR ships no consumer ProGuard rules.
            -keep,allowobfuscation class com.kyant.backdrop.AndroidRuntimeShader {
                *;
            }
        """.trimIndent()
        val validReport = validateRules(validSample, index)
        assertTrue("合法定向规则不应报死规则：${validReport.deadReferences}", validReport.deadReferences.isEmpty())
        assertTrue("合法定向规则不应命中旧库黑名单：${validReport.forbiddenLegacyMatches}", validReport.forbiddenLegacyMatches.isEmpty())
        assertTrue("合法定向规则不应报通配违规：${validReport.unreasonedOrBannedWildcardKeeps}", validReport.unreasonedOrBannedWildcardKeeps.isEmpty())
    }

    @Test
    fun releaseApkAndMapping_verifyCriticalRuntimeContractsAndZeroLegacyClasses() {
        val repoRoot = resolveRepoRoot()
        val hipdaDir = File(repoRoot, "hipda")
        val releaseApk = File(hipdaDir, "build/outputs/apk/release/hipda-ng-release.apk")
        val mappingFile = File(hipdaDir, "build/outputs/mapping/release/mapping.txt")
        val seedsFile = File(hipdaDir, "build/outputs/mapping/release/seeds.txt")
        val missingRulesFile = File(hipdaDir, "build/outputs/mapping/release/missing_rules.txt")
        val isCi = System.getenv("CI").equals("true", ignoreCase = true)

        if (isCi) {
            assertTrue("CI 环境下必须已先构建 release APK：${releaseApk.path}", releaseApk.isFile)
            assertTrue("CI 环境下必须存在 R8 mapping.txt：${mappingFile.path}", mappingFile.isFile)
            assertTrue("CI 环境下必须存在 R8 seeds.txt：${seedsFile.path}", seedsFile.isFile)
        }
        if (!releaseApk.isFile || !mappingFile.isFile) {
            return
        }

        if (missingRulesFile.isFile) {
            val missingContent = missingRulesFile.readText(Charsets.UTF_8).trim()
            assertTrue("R8 不应产生 missing_rules.txt 缺失规则告警：$missingContent", missingContent.isEmpty())
        }

        val dexSummary = inspectReleaseApkDex(releaseApk)
        assertTrue("Release APK 应包含至少 1 个 DEX 文件", dexSummary.dexFileCount >= 1)
        assertTrue(
            "收紧 R8 后总 DEX 方法数应显著低于旧基线 69,956，实际：${dexSummary.totalMethodRefs}",
            dexSummary.totalMethodRefs < 60_000,
        )

        val parsedMapping = parseMappingFile(mappingFile)

        // 1. Components & reflection entry points must keep exact unobfuscated class names in DEX.
        val mustKeepExactInDex = listOf(
            "net.jejer.hipda.ui.HiNextApplication",
            "net.jejer.hipda.feature.app.MainActivity",
            "net.jejer.hipda.ui.challenge.ChallengeActivity",
            "net.jejer.hipda.ui.challenge.AndroidChallengeWebViewHandle\$FallbackChallengeJsBridge",
            "net.jejer.hipda.data.local.HiDatabase",
            "net.jejer.hipda.data.local.HiDatabase_Impl",
            "net.jejer.hipda.service.NotificationPollingWorker",
            "androidx.core.content.FileProvider",
            "androidx.startup.InitializationProvider",
        )
        for (className in mustKeepExactInDex) {
            val descriptor = toDexDescriptor(className)
            assertTrue(
                "Release DEX 必须保留未混淆类 $className ($descriptor)",
                descriptor in dexSummary.definedClassDescriptors,
            )
        }

        // Verify @JavascriptInterface method name is unobfuscated in mapping.
        val jsBridgeMembers = parsedMapping.membersByOriginalClass[
            "net.jejer.hipda.ui.challenge.AndroidChallengeWebViewHandle\$FallbackChallengeJsBridge",
        ].orEmpty()
        assertTrue(
            "FallbackChallengeJsBridge 必须保留未混淆的 onChallengeEvent(java.lang.String) 供 WebView JS 调用，实际：$jsBridgeMembers",
            jsBridgeMembers.any {
                it.contains("void onChallengeEvent(java.lang.String)") && it.endsWith(" -> onChallengeEvent")
            },
        )

        // 2. Critical runtime classes (may be obfuscated) must exist in mapping AND their target descriptor must exist in DEX.
        val mustSurviveInReleaseDex = listOf(
            // Kotlinx Serialization @Serializable + $$serializer
            "net.jejer.hipda.data.network.cookie.StoredCookieStore",
            "net.jejer.hipda.data.network.cookie.StoredCookieStore\$\$serializer",
            "net.jejer.hipda.data.network.cookie.StoredCookie",
            "net.jejer.hipda.data.network.cookie.StoredCookie\$\$serializer",
            "net.jejer.hipda.data.account.SavedAccount",
            "net.jejer.hipda.data.account.SavedAccount\$\$serializer",
            "net.jejer.hipda.data.account.CredentialsSnapshot",
            "net.jejer.hipda.data.account.CredentialsSnapshot\$\$serializer",
            "net.jejer.hipda.data.message.AccountNotifyReadState",
            "net.jejer.hipda.data.message.AccountNotifyReadState\$\$serializer",
            // All 8 HiltViewModels
            "net.jejer.hipda.feature.app.NextAppHostViewModel",
            "net.jejer.hipda.feature.forum.ForumViewModel",
            "net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel",
            "net.jejer.hipda.feature.compose.FullEditorViewModel",
            "net.jejer.hipda.feature.compose.QuickReplyViewModel",
            "net.jejer.hipda.feature.login.LoginViewModel",
            "net.jejer.hipda.feature.messages.MessagesListViewModel",
            "net.jejer.hipda.feature.messages.SmsConversationViewModel",
            // Core screen renderers, emoji & persisted enums
            "net.jejer.hipda.emoji.EmojiMap",
            "net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl",
            "net.jejer.hipda.feature.settings.SettingsProfilePaneRendererImpl",
            "net.jejer.hipda.feature.messages.MessagesPaneRendererImpl",
            "net.jejer.hipda.feature.mine.SearchMinePaneRendererImpl",
            "net.jejer.hipda.feature.login.LoginOverlayRendererImpl",
            "net.jejer.hipda.feature.compose.ComposeSlotsEntryPoint",
            "net.jejer.hipda.ui.shell.ShellDestination",
            "net.jejer.hipda.feature.common.SettingsGroup",
            "net.jejer.hipda.feature.compose.QuickReplyMode",
            "net.jejer.hipda.data.local.ReadingFilterMode",
            "net.jejer.hipda.data.settings.ThemeMode",
            "net.jejer.hipda.data.settings.LineSpacing",
            "net.jejer.hipda.data.settings.PaneSplit",
            // Kyant Backdrop & Shapes
            "com.kyant.backdrop.AndroidRuntimeShader",
            "com.kyant.backdrop.RuntimeShaderCacheImpl",
            "com.kyant.backdrop.DrawBackdropElement",
            "com.kyant.backdrop.DrawBackdropNode",
            "com.kyant.backdrop.backdrops.LayerBackdrop",
            "com.kyant.backdrop.backdrops.LayerBackdropElement",
            "com.kyant.backdrop.backdrops.LayerBackdropNode",
            "com.kyant.backdrop.effects.LensKt",
            "com.kyant.shapes.RoundedRectangularShape",
            "com.kyant.shapes.RoundedRectangularShape\$Corners",
        )
        for (originalClass in mustSurviveInReleaseDex) {
            val mappedName = parsedMapping.obfuscatedByOriginalClass[originalClass]
            assertTrue(
                "Release mapping.txt 必须保留关键运行类 $originalClass（不得被 R8 删除）",
                mappedName != null && !mappedName.startsWith("R8\$\$REMOVED"),
            )
            val descriptor = toDexDescriptor(mappedName!!)
            assertTrue(
                "Release DEX 必须包含 $originalClass 的映射类 $mappedName ($descriptor)",
                descriptor in dexSummary.definedClassDescriptors,
            )
        }

        // Verify all 7 core feature/runtime packages remain live in the release DEX.
        val requiredLivePackages = listOf(
            "net.jejer.hipda.feature.app.",
            "net.jejer.hipda.feature.forum.",
            "net.jejer.hipda.feature.threaddetail.",
            "net.jejer.hipda.feature.imageviewer.",
            "net.jejer.hipda.feature.compose.",
            "net.jejer.hipda.emoji.",
            "net.jejer.hipda.feature.settings.",
            "net.jejer.hipda.ui.designsystem.glass.",
            "net.jejer.hipda.parser.",
        )
        for (pkgPrefix in requiredLivePackages) {
            val liveCount = parsedMapping.obfuscatedByOriginalClass.entries.count { (orig, obf) ->
                orig.startsWith(pkgPrefix) &&
                    !obf.startsWith("R8\$\$REMOVED") &&
                    toDexDescriptor(obf) in dexSummary.definedClassDescriptors
            }
            assertTrue(
                "核心功能包 $pkgPrefix 在 Release DEX 中应存在存活类，实际：$liveCount",
                liveCount > 0,
            )
        }

        // 3. Zero deleted legacy classes or removed libraries in release DEX or mapping.
        val bannedRuntimePrefixes = listOf(
            "net.gotev.cookiestore.",
            "com.bumptech.glide.",
            "com.mikepenz.",
            "com.birbit.",
            "org.greenrobot.eventbus.",
            "com.zhihu.matisse.",
            "com.squareup.picasso.",
            "dev.chrisbanes.haze.",
            "net.jejer.hipda.bean.",
            "net.jejer.hipda.async.",
            "net.jejer.hipda.job.",
            "net.jejer.hipda.glide.",
        )
        val leakedClasses = parsedMapping.obfuscatedByOriginalClass.keys.filter { orig ->
            bannedRuntimePrefixes.any { prefix -> orig.startsWith(prefix) }
        }
        assertTrue(
            "Release 产物不应包含任何已删旧库或旧包类，实际：$leakedClasses",
            leakedClasses.isEmpty(),
        )

        // 4. Confirm R8 actually obfuscated/shrunk net.jejer.hipda and androidx (no accidental wildcard keep).
        if (seedsFile.isFile) {
            val seedClasses = seedsFile.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains(':') }
            val hipdaSeedCount = seedClasses.count { it.startsWith("net.jejer.hipda.") }
            val androidxSeedCount = seedClasses.count { it.startsWith("androidx.") }
            assertTrue(
                "收紧后 net.jejer.hipda.* 种子类应远少于旧全包 keep（2461），实际：$hipdaSeedCount",
                hipdaSeedCount < 200,
            )
            assertTrue(
                "收紧后 androidx.* 种子类应远少于旧全包 keep（9000+），实际：$androidxSeedCount",
                androidxSeedCount < 1_000,
            )
        }
    }

    companion object {
        private const val MIN_RATIONALE_LENGTH = 15

        private val KEEP_DIRECTIVES = setOf(
            "-keep",
            "-keepclassmembers",
            "-keepclasseswithmembers",
            "-keepnames",
            "-keepclassmembernames",
            "-keepclasseswithmembernames",
        )

        private val CLASS_SPEC_DIRECTIVES = KEEP_DIRECTIVES + setOf(
            "-if",
            "-assumenosideeffects",
            "-assumevalues",
            "-whyareyoukeeping",
        )

        private val CLASS_FILTER_DIRECTIVES = setOf(
            "-dontwarn",
            "-dontnote",
            "-keeppackagenames",
            "-adaptclassstrings",
        )

        private val PROGUARD_HEADER_KEYWORDS = setOf(
            "public", "private", "protected", "static", "final", "abstract", "synthetic",
            "open", "volatile", "transient", "native", "synchronized", "strictfp",
            "class", "interface", "@interface", "enum", "extends", "implements",
        )

        private val JAVA_PRIMITIVES_AND_KEYWORDS = setOf(
            "void", "boolean", "byte", "short", "char", "int", "long", "float", "double",
            "public", "private", "protected", "static", "final", "abstract", "native",
            "synchronized", "transient", "volatile", "strictfp", "synthetic", "bridge", "varargs",
            "return", "true", "false", "null",
        )

        private val FORBIDDEN_LEGACY_TOKENS = listOf(
            "com.google.gson",
            "net.gotev.cookiestore",
            "com.bumptech.glide",
            "com.mikepenz.iconics",
            "com.birbit.android.jobqueue",
            "org.greenrobot.eventbus",
            "com.squareup.picasso",
            "com.zhihu.matisse",
            "com.vanniktech.emoji",
            "io.github.inflationx",
            "com.davemorrissey",
            "id.zelory.compressor",
            "com.vdurmont.emoji",
            "androidx.preference",
            "com.google.android.material",
            "androidx.security.crypto",
            "dev.chrisbanes.haze",
            "HiSettingsHelper",
            "HiApplication",
            "MainFrameActivity",
            "NotiWorker",
            "NotiHelper",
            "HiParser",
            "HiUtils",
            "PostJob",
            "PostHelper",
            "LoginHelper",
        )

        private val UNCONDITIONALLY_BANNED_BROAD_KEEP_PREFIXES = setOf(
            "net.jejer.hipda",
            "androidx",
            "android",
            "java",
            "javax",
            "kotlin",
            "kotlinx",
            "org.jsoup",
            "okhttp3",
            "okio",
            "coil3",
            "com.google.gson",
            "com.google.dagger",
        )

        private val TEST_ONLY_CLASSPATH_MARKERS = listOf(
            "debugUnitTest",
            "UnitTest",
            "/test-classes/",
            "/classes/java/test",
            "/classes/kotlin/test",
            "junit",
            "robolectric",
            "roborazzi",
            "mockwebserver",
            "turbine",
            "hamcrest",
            "mockito",
            "androidx.test",
            "androidx-test",
            "espresso",
            "shadows-",
            "android-all-instrumented",
            "opentest4j",
            "assertj",
            "truth-",
            "byte-buddy",
            "room-testing",
            "work-testing",
            "kotlinx-coroutines-test",
        )

        private val PACKAGE_DECL_REGEX = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
        private val TYPE_DECL_REGEX = Regex("""\b(?:class|interface|object|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val BODY_ANNOTATION_REGEX = Regex("""@([A-Za-z_][A-Za-z0-9_.$*?]*)""")
        private val BODY_QUALIFIED_TYPE_REGEX = Regex("""\b([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_$*?]+)+)\b""")

        private val runtimeClassIndex: RuntimeClassIndex by lazy {
            buildRuntimeClassIndex(resolveRepoRoot())
        }

        internal fun resolveRepoRoot(): File {
            val candidates = listOf(File("."), File(".."))
            return candidates.firstOrNull { dir ->
                File(dir, "settings.gradle.kts").isFile &&
                    File(dir, "hipda/proguard-rules.txt").isFile
            } ?: error("无法定位仓库根目录（当前工作目录：${File(".").absolutePath}）")
        }

        internal fun collectProjectProguardFiles(repoRoot: File): List<File> {
            val result = mutableListOf<File>()
            val mainRules = File(repoRoot, "hipda/proguard-rules.txt")
            if (mainRules.isFile) result += mainRules
            for (moduleName in listOf("emoji", "core-parser")) {
                val moduleDir = File(repoRoot, moduleName)
                if (!moduleDir.isDirectory) continue
                moduleDir.walkTopDown()
                    .onEnter { it.name != "build" && it.name != ".gradle" }
                    .filter { file ->
                        file.isFile && (
                            file.name.contains("consumer", ignoreCase = true) ||
                                file.name.contains("proguard", ignoreCase = true) ||
                                file.extension == "pro"
                            )
                    }
                    .forEach { result += it }
            }
            return result.sortedBy { it.relativeTo(repoRoot).invariantSeparatorsPath }
        }

        internal fun parseProguardRules(content: String): List<ProguardRule> {
            val rawLines = content.lines()
            val rules = mutableListOf<ProguardRule>()
            var i = 0
            var pendingComments = mutableListOf<String>()
            var activeGroupComment = ""

            while (i < rawLines.size) {
                val trimmed = rawLines[i].trim()
                if (trimmed.isEmpty()) {
                    pendingComments.clear()
                    activeGroupComment = ""
                    i++
                    continue
                }
                if (trimmed.startsWith("#")) {
                    pendingComments += trimmed.removePrefix("#").trim()
                    i++
                    continue
                }
                if (trimmed.startsWith("-")) {
                    val startLine = i + 1
                    val directComment = pendingComments.joinToString("\n").trim()
                    if (directComment.isNotEmpty()) {
                        activeGroupComment = directComment
                    }
                    val groupComment = directComment.ifEmpty { activeGroupComment }
                    pendingComments = mutableListOf()

                    val ruleBuilder = StringBuilder()
                    var braceDepth = 0
                    var sawOpenBrace = false
                    while (i < rawLines.size) {
                        val lineNoComment = stripHashComment(rawLines[i]).trim()
                        if (i > startLine - 1 && braceDepth == 0 && !sawOpenBrace && lineNoComment.startsWith("-")) {
                            break
                        }
                        if (i > startLine - 1 && braceDepth == 0 && (lineNoComment.isEmpty() || rawLines[i].trim().startsWith("#"))) {
                            if (sawOpenBrace || ruleBuilder.isNotEmpty()) break
                        }
                        if (lineNoComment.isNotEmpty()) {
                            if (ruleBuilder.isNotEmpty()) ruleBuilder.append(' ')
                            ruleBuilder.append(lineNoComment)
                            for (ch in lineNoComment) {
                                if (ch == '{') {
                                    braceDepth++
                                    sawOpenBrace = true
                                } else if (ch == '}') {
                                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                                }
                            }
                        }
                        i++
                        if (sawOpenBrace && braceDepth == 0) {
                            break
                        }
                    }
                    val fullRule = ruleBuilder.toString().trim()
                    val firstSpace = fullRule.indexOfFirst { it.isWhitespace() }
                    val directive = if (firstSpace < 0) fullRule else fullRule.substring(0, firstSpace)
                    val baseDirective = directive.substringBefore(',')
                    val rest = if (firstSpace < 0) "" else fullRule.substring(firstSpace + 1).trim()
                    val openBraceIdx = rest.indexOf('{')
                    val closeBraceIdx = rest.lastIndexOf('}')
                    val header = if (openBraceIdx >= 0) rest.substring(0, openBraceIdx).trim() else rest
                    val body = if (openBraceIdx >= 0 && closeBraceIdx > openBraceIdx) {
                        rest.substring(openBraceIdx + 1, closeBraceIdx).trim()
                    } else {
                        null
                    }
                    rules += ProguardRule(
                        directive = directive,
                        baseDirective = baseDirective,
                        startLine = startLine,
                        header = header,
                        body = body,
                        leadingComment = directComment,
                        groupLeadingComment = groupComment,
                    )
                    continue
                }
                i++
            }
            return rules
        }

        internal fun extractReferencedTargets(rule: ProguardRule): List<RuleTargetRef> {
            val refs = mutableListOf<RuleTargetRef>()
            if (rule.baseDirective in CLASS_FILTER_DIRECTIVES) {
                val isPackageDirective = rule.baseDirective == "-keeppackagenames"
                for (raw in rule.header.split(Regex("""[\s,]+"""))) {
                    val token = raw.trim().removePrefix("!")
                    if (token.isEmpty() || token == "*" || token == "**") continue
                    refs += RuleTargetRef(rule.startLine, rule.directive, token, isPackageDirective)
                }
                return refs
            }
            if (rule.baseDirective !in CLASS_SPEC_DIRECTIVES) {
                return emptyList()
            }

            // Parse header tokens (annotations, class names, extends/implements supertypes)
            val headerTokens = rule.header.split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
            for (raw in headerTokens) {
                val cleaned = raw.removePrefix("!")
                if (cleaned.isEmpty()) continue
                if (cleaned.startsWith("@") && cleaned != "@interface") {
                    val ann = cleaned.removePrefix("@")
                    if (ann.isNotEmpty() && ann != "*" && ann != "**") {
                        refs += RuleTargetRef(rule.startLine, rule.directive, ann, isPackageNameDirective = false)
                    }
                    continue
                }
                if (cleaned in PROGUARD_HEADER_KEYWORDS) continue
                if (cleaned == "*" || cleaned == "**" || cleaned == "***") continue
                if (cleaned.startsWith("<") && cleaned.endsWith(">")) continue
                refs += RuleTargetRef(rule.startLine, rule.directive, cleaned, isPackageNameDirective = false)
            }

            // Parse body tokens (member annotations and fully-qualified parameter/field/return types)
            val body = rule.body ?: return refs
            for (match in BODY_ANNOTATION_REGEX.findAll(body)) {
                val ann = match.groupValues[1]
                if (ann.contains('.') && ann != "*" && ann != "**") {
                    refs += RuleTargetRef(rule.startLine, rule.directive, ann, isPackageNameDirective = false)
                }
            }
            for (match in BODY_QUALIFIED_TYPE_REGEX.findAll(body)) {
                val typeName = match.groupValues[1].trimEnd('[', ']')
                if (typeName in JAVA_PRIMITIVES_AND_KEYWORDS) continue
                if (typeName == "*" || typeName == "**" || typeName == "***") continue
                refs += RuleTargetRef(rule.startLine, rule.directive, typeName, isPackageNameDirective = false)
            }
            return refs
        }

        internal fun validateRules(content: String, index: RuntimeClassIndex): ValidationReport {
            val rules = parseProguardRules(content)
            val deadRefs = mutableListOf<String>()
            val forbiddenLegacy = mutableListOf<String>()
            val unreasonedOrBannedWildcards = mutableListOf<String>()
            val allFullPackageWildcards = mutableListOf<String>()

            for (rule in rules) {
                val ruleText = buildString {
                    append(rule.directive)
                    append(' ')
                    append(rule.header)
                    if (rule.body != null) {
                        append(" { ")
                        append(rule.body)
                        append(" }")
                    }
                }

                // 1. Forbidden legacy libraries / deleted classes / historical bad patterns
                for (token in FORBIDDEN_LEGACY_TOKENS) {
                    if (ruleText.contains(token)) {
                        forbiddenLegacy += "line ${rule.startLine}: 命中禁用旧依赖/旧类 '$token' -> $ruleText"
                    }
                }
                if (Regex("""\bclass\s+\.R\b""").containsMatchIn(rule.header) ||
                    Regex("""\*\*\.R\$\*""").containsMatchIn(rule.header)
                ) {
                    forbiddenLegacy += "line ${rule.startLine}: 禁止保留过宽 R 类通配规则 -> $ruleText"
                }

                // 2. Dead reference check (every referenced class/package must exist)
                val refs = extractReferencedTargets(rule)
                for (ref in refs) {
                    if (!index.exists(ref.rawPattern, allowPackageOnly = ref.isPackageNameDirective)) {
                        deadRefs += "line ${ref.ruleLine} [${ref.directive}]: '${ref.rawPattern}' 不存在于源码或运行时依赖中"
                    }
                }

                // 3. Full-package / overly broad wildcard keep check
                if (rule.baseDirective in KEEP_DIRECTIVES) {
                    val primaryTargets = extractPrimaryClassTargets(rule.header)
                    val hasAnnotationConstraint = rule.header.split(Regex("""\s+""")).any {
                        it.startsWith("@") && it != "@interface"
                    } || (rule.body != null && BODY_ANNOTATION_REGEX.containsMatchIn(rule.body))
                    val hasSupertypeConstraint = Regex("""\b(?:extends|implements)\s+\S+""").containsMatchIn(rule.header)

                    for (target in primaryTargets) {
                        val isPackageWildcard = target.endsWith(".**") || target.endsWith(".*")
                        val isUniversalWildcard = (target == "*" || target == "**") &&
                            !hasAnnotationConstraint && !hasSupertypeConstraint

                        if (isPackageWildcard || isUniversalWildcard) {
                            val summary = "line ${rule.startLine}: ${rule.directive} ${rule.header}"
                            allFullPackageWildcards += summary
                            val prefix = target.removeSuffix(".**").removeSuffix(".*")
                            val isBannedNamespace = isUniversalWildcard ||
                                UNCONDITIONALLY_BANNED_BROAD_KEEP_PREFIXES.any { banned ->
                                    prefix == banned || prefix.startsWith("$banned.")
                                }
                            val hasRationale = meaningfulCommentLength(rule.groupLeadingComment) >= MIN_RATIONALE_LENGTH
                            if (isBannedNamespace) {
                                unreasonedOrBannedWildcards += "$summary（禁止对宽泛命名空间 '$target' 做全包通配 keep）"
                            } else if (!hasRationale) {
                                unreasonedOrBannedWildcards += "$summary（全包通配 keep 缺少紧邻上方的有效保留理由注释）"
                            }
                        }
                    }
                }
            }

            return ValidationReport(
                rules = rules,
                deadReferences = deadRefs,
                forbiddenLegacyMatches = forbiddenLegacy,
                unreasonedOrBannedWildcardKeeps = unreasonedOrBannedWildcards,
                allFullPackageWildcardKeeps = allFullPackageWildcards,
            )
        }

        private fun extractPrimaryClassTargets(header: String): List<String> {
            val beforeSuper = header.split(Regex("""\b(?:extends|implements)\b"""), limit = 2).first()
            val afterClassKw = Regex("""\b(?:@interface|interface|class|enum)\s+(.+)$""").find(beforeSuper)
                ?.groupValues?.get(1) ?: return emptyList()
            return afterClassKw.split(Regex("""[\s,]+"""))
                .map { it.trim().removePrefix("!") }
                .filter { it.isNotEmpty() && it !in PROGUARD_HEADER_KEYWORDS && !it.startsWith("@") }
        }

        private fun meaningfulCommentLength(comment: String): Int =
            comment.count { !it.isWhitespace() && it != '#' && it != '-' && it != '=' }

        private fun stripHashComment(line: String): String {
            val hashIdx = line.indexOf('#')
            return if (hashIdx >= 0) line.substring(0, hashIdx) else line
        }

        private fun proguardGlobToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                when {
                    i + 1 < pattern.length && pattern[i] == '*' && pattern[i + 1] == '*' -> {
                        sb.append(".*")
                        i += 2
                    }
                    pattern[i] == '*' -> {
                        sb.append("[^.]*")
                        i++
                    }
                    pattern[i] == '?' -> {
                        sb.append("[^.]")
                        i++
                    }
                    pattern[i] in ".+$^()[]{}|\\" -> {
                        sb.append('\\').append(pattern[i])
                        i++
                    }
                    else -> {
                        sb.append(pattern[i])
                        i++
                    }
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }

        private fun buildRuntimeClassIndex(repoRoot: File): RuntimeClassIndex {
            val classes = HashSet<String>(65_536)
            val packages = HashSet<String>(8_192)

            fun addClass(dottedName: String) {
                if (dottedName.isEmpty()) return
                classes += dottedName
                var dot = dottedName.lastIndexOf('.')
                while (dot > 0) {
                    val pkg = dottedName.substring(0, dot)
                    if (!packages.add(pkg)) break
                    dot = pkg.lastIndexOf('.')
                }
            }

            // 1. Index main source trees across all modules.
            val mainSourceRoots = listOf(
                File(repoRoot, "hipda/src/main/java"),
                File(repoRoot, "core-parser/src/main/kotlin"),
                File(repoRoot, "emoji/src/main/java"),
            )
            for (srcRoot in mainSourceRoots) {
                if (!srcRoot.isDirectory) continue
                srcRoot.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        val text = file.readText(Charsets.UTF_8)
                        val pkg = PACKAGE_DECL_REGEX.find(text)?.groupValues?.get(1).orEmpty()
                        if (pkg.isNotEmpty()) {
                            addClass("$pkg.${file.nameWithoutExtension}Kt")
                            for (m in TYPE_DECL_REGEX.findAll(text)) {
                                addClass("$pkg.${m.groupValues[1]}")
                            }
                        }
                    }
            }
            addClass("net.jejer.hipda.R")
            addClass("net.jejer.hipda.BuildConfig")
            addClass("com.vanniktech.emoji.R")

            // 2. Index runtime classpath JARs & class directories (excluding test-only entries).
            val classpath = System.getProperty("java.class.path").orEmpty()
            for (entry in classpath.split(File.pathSeparatorChar)) {
                if (entry.isBlank()) continue
                val normalized = entry.replace('\\', '/')
                if (TEST_ONLY_CLASSPATH_MARKERS.any { marker -> normalized.contains(marker, ignoreCase = true) }) {
                    continue
                }
                val file = File(entry)
                if (!file.exists()) continue
                if (file.isDirectory) {
                    file.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach { clsFile ->
                            val rel = clsFile.relativeTo(file).invariantSeparatorsPath
                            classEntryToDottedName(rel)?.let(::addClass)
                        }
                } else if (file.isFile && (file.extension == "jar" || file.extension == "zip")) {
                    runCatching {
                        ZipFile(file).use { zf ->
                            val entries = zf.entries()
                            while (entries.hasMoreElements()) {
                                val name = entries.nextElement().name
                                if (name.endsWith(".class")) {
                                    classEntryToDottedName(name)?.let(::addClass)
                                }
                            }
                        }
                    }
                }
            }

            return RuntimeClassIndex(classes = classes, packages = packages)
        }

        private fun classEntryToDottedName(entryName: String): String? {
            if (!entryName.endsWith(".class")) return null
            var path = entryName
            if (path.startsWith("META-INF/versions/")) {
                val afterVersions = path.removePrefix("META-INF/versions/")
                val slash = afterVersions.indexOf('/')
                if (slash < 0) return null
                path = afterVersions.substring(slash + 1)
            }
            if (path.startsWith("META-INF/") || path.endsWith("module-info.class") || path.endsWith("package-info.class")) {
                return null
            }
            return path.removeSuffix(".class").replace('/', '.')
        }

        private data class ReleaseDexSummary(
            val dexFileCount: Int,
            val totalMethodRefs: Int,
            val definedClassDescriptors: Set<String>,
        )

        private data class ParsedMapping(
            val obfuscatedByOriginalClass: Map<String, String>,
            val membersByOriginalClass: Map<String, List<String>>,
        )

        private fun parseMappingFile(mappingFile: File): ParsedMapping {
            val classes = HashMap<String, String>(16_384)
            val members = HashMap<String, MutableList<String>>(4_096)
            var currentOriginal: String? = null
            mappingFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (!line[0].isWhitespace()) {
                        val arrow = line.indexOf(" -> ")
                        if (arrow > 0) {
                            val orig = line.substring(0, arrow).trim()
                            val obf = line.substring(arrow + 4).trim().removeSuffix(":")
                            classes[orig] = obf
                            currentOriginal = orig
                        }
                    } else if (line.contains(" -> ")) {
                        val cur = currentOriginal
                        if (cur != null && cur.startsWith("net.jejer.hipda.ui.challenge.")) {
                            members.getOrPut(cur) { mutableListOf() } += line.trim()
                        }
                    }
                }
            }
            return ParsedMapping(classes, members)
        }

        private fun toDexDescriptor(dottedClassName: String): String =
            "L${dottedClassName.replace('.', '/')};"

        private fun inspectReleaseApkDex(apkFile: File): ReleaseDexSummary {
            var dexCount = 0
            var totalMethods = 0
            val descriptors = HashSet<String>(16_384)
            ZipFile(apkFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.endsWith(".dex")) continue
                    val bytes = zf.getInputStream(entry).readBytes()
                    dexCount++
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val stringIdsSize = buf.getInt(56)
                    val stringIdsOff = buf.getInt(60)
                    val typeIdsSize = buf.getInt(64)
                    val typeIdsOff = buf.getInt(68)
                    val methodIdsSize = buf.getInt(88)
                    val classDefsSize = buf.getInt(96)
                    val classDefsOff = buf.getInt(100)
                    totalMethods += methodIdsSize

                    fun readString(stringIdx: Int): String {
                        val strDataOff = buf.getInt(stringIdsOff + stringIdx * 4)
                        var pos = strDataOff
                        // Skip ULEB128 utf16_size
                        while (true) {
                            val b = bytes[pos++].toInt() and 0xFF
                            if ((b and 0x80) == 0) break
                        }
                        val end = run {
                            var p = pos
                            while (p < bytes.size && bytes[p].toInt() != 0) p++
                            p
                        }
                        return String(bytes, pos, end - pos, Charsets.UTF_8)
                    }

                    for (cIdx in 0 until classDefsSize) {
                        val classTypeIdx = buf.getInt(classDefsOff + cIdx * 32)
                        if (classTypeIdx in 0 until typeIdsSize) {
                            val descStrIdx = buf.getInt(typeIdsOff + classTypeIdx * 4)
                            if (descStrIdx in 0 until stringIdsSize) {
                                descriptors += readString(descStrIdx)
                            }
                        }
                    }
                }
            }
            return ReleaseDexSummary(
                dexFileCount = dexCount,
                totalMethodRefs = totalMethods,
                definedClassDescriptors = descriptors,
            )
        }
    }
}
