package net.jejer.hipda.packaging

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import net.jejer.hipda.emoji.EmojiMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard tests for M7-4a resource shrinking (`isShrinkResources = true`), dynamic resource keep
 * rules (`res/raw/keep.xml`), Baseline Profile wiring, and release APK output integrity.
 */
class ReleaseResourceAndProfileGuardTest {

    @Test
    fun buildGradle_enablesResourceShrinkingAndBaselineProfileWithoutTouchingTargetSdk() {
        val hipdaBuildFile = File(locateRepoRoot(), "hipda/build.gradle.kts")
        assertTrue("Missing hipda/build.gradle.kts", hipdaBuildFile.isFile)
        val content = hipdaBuildFile.readText(Charsets.UTF_8)

        assertTrue(
            "release buildType must enable isShrinkResources = true",
            Regex("""isShrinkResources\s*=\s*true""").containsMatchIn(content),
        )
        assertTrue(
            "hipda/build.gradle.kts must apply androidx.baselineprofile plugin",
            content.contains("alias(libs.plugins.androidx.baselineprofile)"),
        )
        assertTrue(
            "hipda/build.gradle.kts must depend on androidx.profileinstaller",
            content.contains("implementation(libs.androidx.profileinstaller)"),
        )
        assertTrue(
            "hipda/build.gradle.kts must connect baselineProfile(project(\":baselineprofile\"))",
            content.contains("""baselineProfile(project(":baselineprofile"))"""),
        )
        assertTrue(
            "hipda/build.gradle.kts must keep targetSdk sourced from libs.versions.targetSdk",
            content.contains("targetSdk = libs.versions.targetSdk.get().toInt()"),
        )
    }

    @Test
    fun keepXml_coversAllEmojiDrawablesAndDynamicResources_withNegativeSelfTest() {
        val repoRoot = locateRepoRoot()
        val keepFile = File(repoRoot, "hipda/src/main/res/raw/keep.xml")
        assertTrue("Missing keep.xml at ${keepFile.path}", keepFile.isFile)

        val patterns = parseKeepPatterns(keepFile.readText(Charsets.UTF_8))
        assertTrue("keep.xml must declare at least one tools:keep pattern", patterns.isNotEmpty())

        // 1. All 101 :emoji forum drawables on disk (21 default + 32 coolmonkey + 48 grapeman)
        val emojiResDir = File(repoRoot, "emoji/src/main/res/drawable-nodpi")
        assertTrue("Missing emoji drawable-nodpi dir at ${emojiResDir.path}", emojiResDir.isDirectory)
        val emojiDrawableNames = emojiResDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .sorted()
        assertEquals("Expected 101 forum emoji PNG drawables in :emoji", 101, emojiDrawableNames.size)

        // Cross-check EmojiMap codes (61) map cleanly to non-null light & dark drawables
        val allCodes = EmojiMap.groups.flatMap { it.codes }
        assertEquals("Expected 61 canonical emoji codes in EmojiMap", 61, allCodes.size)
        for (code in allCodes) {
            assertTrue("Missing light drawable for $code", EmojiMap.drawableFor(code, dark = false) != null)
            assertTrue("Missing dark drawable for $code", EmojiMap.drawableFor(code, dark = true) != null)
        }

        val requiredKeptResources = buildList {
            emojiDrawableNames.forEach { add("@drawable/$it") }
            add("@drawable/win")
            add("@drawable/ic_stat_hi")
            extractShortcutDrawableRefs(File(repoRoot, "hipda/src/main/res/xml/shortcuts.xml")).forEach { add(it) }
            add("@string/define_font_GoogleMaterial")
            add("@string/define_font_fontawesome")
        }

        val uncovered = requiredKeptResources.filterNot { res -> matchesAnyKeepPattern(res, patterns) }
        assertEquals(
            "All emoji and dynamic resources must be matched by res/raw/keep.xml tools:keep",
            emptyList<String>(),
            uncovered,
        )

        // Negative self-test: dropping coolmonkey_* must cause coolmonkey_01_dark to fail coverage
        val brokenPatterns = patterns.filterNot { it.contains("coolmonkey_") }
        assertFalse(
            "Negative self-test failed: coolmonkey_01_dark should not match without coolmonkey_* keep rule",
            matchesAnyKeepPattern("@drawable/coolmonkey_01_dark", brokenPatterns),
        )
    }

    @Test
    fun mainAndEmojiSources_haveNoUncoveredAppPackageGetIdentifierCalls_withNegativeSelfTest() {
        val repoRoot = locateRepoRoot()
        val sourceRoots = listOf(
            File(repoRoot, "hipda/src/main/java"),
            File(repoRoot, "emoji/src/main/java"),
        )
        val hits = mutableListOf<String>()
        for (root in sourceRoots) {
            assertTrue("Missing source root ${root.path}", root.isDirectory)
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val rel = file.relativeTo(repoRoot).invariantSeparatorsPath
                    val found = findAppPackageGetIdentifierCalls(file.readText(Charsets.UTF_8))
                    found.forEach { call -> hits += "$rel: $call" }
                }
        }
        assertEquals(
            "Found dynamic Resources.getIdentifier(...) calls targeting non-'android' package; " +
                "verify resource keep rules and document before allowing: $hits",
            emptyList<String>(),
            hits,
        )

        // Negative self-test: framework "android" package is allowed, app package call is flagged
        val sampleWithFrameworkOnly = """
            // resources.getIdentifier("commented_out", "drawable", context.packageName)
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        """.trimIndent()
        assertEquals(emptyList<String>(), findAppPackageGetIdentifierCalls(sampleWithFrameworkOnly))

        val sampleWithAppLookup = """
            val id = resources.getIdentifier("dynamic_badge", "drawable", context.packageName)
        """.trimIndent()
        val caught = findAppPackageGetIdentifierCalls(sampleWithAppLookup)
        assertEquals("Scanner must catch app-package Resources.getIdentifier calls", 1, caught.size)
    }

    @Test
    fun baselineProfileHarness_isIsolatedToNonMinifiedReleaseAndOfflineOnly() {
        val repoRoot = locateRepoRoot()
        val mainManifest = File(repoRoot, "hipda/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertFalse(
            "Production src/main/AndroidManifest.xml must never declare BaselineProfileHostActivity",
            mainManifest.contains("BaselineProfileHostActivity"),
        )

        val nonMinifiedManifest = File(repoRoot, "hipda/src/nonMinifiedRelease/AndroidManifest.xml")
        assertTrue("Missing nonMinifiedRelease manifest", nonMinifiedManifest.isFile)
        assertTrue(
            "nonMinifiedRelease manifest must declare BaselineProfileHostActivity",
            nonMinifiedManifest.readText(Charsets.UTF_8).contains("BaselineProfileHostActivity"),
        )

        val baselineSources = File(repoRoot, "baselineprofile/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("Expected Kotlin generator sources in :baselineprofile", baselineSources.isNotEmpty())
        for (file in baselineSources) {
            val codeWithoutComments = stripComments(file.readText(Charsets.UTF_8))
            assertFalse(
                "${file.name} must never reference live site domain 4d4y.com",
                codeWithoutComments.contains("4d4y.com", ignoreCase = true),
            )
            assertFalse(
                "${file.name} must never reference legacy domain hi-pda.com",
                codeWithoutComments.contains("hi-pda.com", ignoreCase = true),
            )
        }
    }

    @Test
    fun releaseApkAndResourceShrinkReport_preserveAllEmojiDrawablesAndBaselineProfile() {
        val repoRoot = locateRepoRoot()
        val releaseApk = File(repoRoot, "hipda/build/outputs/apk/release/hipda-ng-release.apk")
        val resourcesReport = File(repoRoot, "hipda/build/outputs/mapping/release/resources.txt")
        val isCi = System.getenv("CI").equals("true", ignoreCase = true)

        if (isCi) {
            assertTrue("CI build must produce ${releaseApk.path} before running unit tests", releaseApk.isFile)
            assertTrue("CI build must produce ${resourcesReport.path} when isShrinkResources = true", resourcesReport.isFile)
        }
        if (!releaseApk.isFile) return

        // 1. Release APK must contain non-empty compiled ART baseline profile + metadata
        ZipFile(releaseApk).use { zip ->
            val prof = zip.getEntry("assets/dexopt/baseline.prof")
            val profm = zip.getEntry("assets/dexopt/baseline.profm")
            assertTrue("Missing assets/dexopt/baseline.prof in release APK", prof != null && prof.size > 0L)
            assertTrue("Missing assets/dexopt/baseline.profm in release APK", profm != null && profm.size > 0L)
        }

        // 2. Resource shrinker report must keep all 101 emoji drawables + dynamic resources reachable
        if (resourcesReport.isFile) {
            val emojiNames = File(repoRoot, "emoji/src/main/res/drawable-nodpi")
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                .map { "drawable:${it.nameWithoutExtension}" }
                .sorted()
            val expectedResources = emojiNames + listOf(
                "drawable:win",
                "drawable:ic_stat_hi",
                "drawable:ic_shortcut_wb_sunny",
                "drawable:ic_shortcut_search",
                "drawable:ic_shortcut_mail",
                "drawable:ic_shortcut_favorite",
                "string:define_font_GoogleMaterial",
                "string:define_font_fontawesome",
            )
            val reportText = resourcesReport.readText(Charsets.UTF_8)
            val missingOrUnreachable = findUnreachableOrMissingInShrinkReport(reportText, expectedResources)
            assertEquals(
                "All emoji drawables and kept dynamic resources must remain reachable in release resources.txt",
                emptyList<String>(),
                missingOrUnreachable,
            )

            // Negative self-test on shrink report parser
            val simulatedBadReport = "drawable:coolmonkey_01:2131230873 is not reachable.\n"
            assertEquals(
                listOf("drawable:coolmonkey_01"),
                findUnreachableOrMissingInShrinkReport(simulatedBadReport, listOf("drawable:coolmonkey_01")),
            )
        }
    }

    companion object {
        private const val TOOLS_NS = "http://schemas.android.com/tools"

        private val GET_IDENTIFIER_3ARG_REGEX = Regex(
            """getIdentifier\s*\(\s*([^,()]+)\s*,\s*([^,()]+)\s*,\s*([^,()]+)\s*\)""",
        )

        internal fun locateRepoRoot(): File {
            val cwd = File(".").canonicalFile
            val candidates = listOf(cwd, cwd.parentFile)
            return candidates.firstOrNull { dir ->
                dir != null &&
                    File(dir, "settings.gradle.kts").isFile &&
                    File(dir, "hipda/build.gradle.kts").isFile &&
                    File(dir, "emoji/src/main/res/drawable-nodpi").isDirectory
            } ?: error("Cannot locate repository root from ${cwd.path}")
        }

        internal fun parseKeepPatterns(xmlContent: String): List<String> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xmlContent.byteInputStream(Charsets.UTF_8))
            val root = doc.documentElement
            assertEquals("Expected <resources> root element in keep.xml", "resources", root.tagName)
            val rawKeep = root.getAttributeNS(TOOLS_NS, "keep")
            return rawKeep.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        internal fun matchesAnyKeepPattern(resourceRef: String, patterns: List<String>): Boolean =
            patterns.any { pattern -> globToRegex(pattern).matches(resourceRef) }

        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            for (ch in glob) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> {
                        sb.append('\\').append(ch)
                    }
                    else -> sb.append(ch)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }

        private fun extractShortcutDrawableRefs(shortcutsFile: File): List<String> {
            assertTrue("Missing shortcuts.xml at ${shortcutsFile.path}", shortcutsFile.isFile)
            val content = shortcutsFile.readText(Charsets.UTF_8)
            return Regex("""android:icon="(@drawable/[^"]+)"""")
                .findAll(content)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }

        internal fun findAppPackageGetIdentifierCalls(source: String): List<String> {
            val cleaned = stripComments(source)
            return GET_IDENTIFIER_3ARG_REGEX.findAll(cleaned)
                .filter { match -> match.groupValues[3].trim() != "\"android\"" }
                .map { it.value.trim() }
                .toList()
        }

        internal fun findUnreachableOrMissingInShrinkReport(
            reportText: String,
            expectedTypeColonNames: List<String>,
        ): List<String> {
            val reachable = HashSet<String>(expectedTypeColonNames.size * 2)
            val unreachable = HashSet<String>()
            for (line in reportText.lineSequence()) {
                val firstColon = line.indexOf(':')
                if (firstColon <= 0) continue
                val secondColon = line.indexOf(':', firstColon + 1)
                if (secondColon <= firstColon + 1) continue
                val key = line.substring(0, secondColon)
                when {
                    line.contains(" is not reachable.") -> unreachable.add(key)
                    line.contains(" reachable from ") -> reachable.add(key)
                }
            }
            return expectedTypeColonNames.filter { key -> key in unreachable || key !in reachable }
        }

        private fun stripComments(src: String): String {
            val out = StringBuilder(src.length)
            var i = 0
            val n = src.length
            while (i < n) {
                if (src[i] == '"') {
                    out.append(src[i++])
                    while (i < n) {
                        val c = src[i++]
                        out.append(c)
                        if (c == '\\' && i < n) out.append(src[i++]) else if (c == '"') break
                    }
                    continue
                }
                if (i + 1 < n && src[i] == '/' && src[i + 1] == '/') {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                    continue
                }
                if (i + 1 < n && src[i] == '/' && src[i + 1] == '*') {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    continue
                }
                out.append(src[i++])
            }
            return out.toString()
        }
    }
}
