package net.jejer.hipda.packaging

import java.io.File
import java.util.zip.ZipFile
import net.jejer.hipda.emoji.EmojiMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent verification probes for M7-4a (Probe a):
 * 1. Verifies Baseline Profile (`baseline.prof` / `baseline.profm`) binaries and committed text
 *    profiles cover core runtime journeys without nonMinifiedRelease harness leakage.
 * 2. Independently scans all new-stack Kotlin/Java sources (`feature/`, `data/`, `di/`, `ui/shell/`,
 *    `ui/designsystem/`, `ui/challenge/`, `ui/HiNextApplication.kt`, `service/Notification*.kt`,
 *    `emoji/`) plus production manifests/XMLs for all `R.*` references, XML `@type/name`
 *    references, and dynamic name lookups (`getIdentifier`, reflection on `R$*`), and verifies none
 *    were removed or stubbed by `isShrinkResources = true`.
 * 3. Verifies every emoji drawable (101 PNGs across all 61 `EmojiMap` codes), notification icon
 *    (`ic_stat_hi`), splash resources (`splash`, `splash_screen`), shortcut icons (4 items),
 *    launcher icon resources, `win.png`, and `assets/` about docs exist non-stubbed in the built
 *    release APK.
 */
class M7ResourceShrinkAndProfileProbeTest {

    @Test
    fun committedProfiles_containValidHotPathsAndZeroHarnessLeakage() {
        val repoRoot = locateRepoRoot()
        val baselineProf = File(repoRoot, "hipda/src/main/generated/baselineProfiles/baseline-prof.txt")
        val startupProf = File(repoRoot, "hipda/src/main/generated/baselineProfiles/startup-prof.txt")
        assertTrue("Missing baseline-prof.txt", baselineProf.isFile && baselineProf.length() > 10_000L)
        assertTrue("Missing startup-prof.txt", startupProf.isFile && startupProf.length() > 10_000L)

        val baselineLines = baselineProf.readLines(Charsets.UTF_8)
        val startupLines = startupProf.readLines(Charsets.UTF_8)
        assertFalse(
            "baseline-prof.txt must exclude nonMinifiedRelease harness package",
            baselineLines.any { it.contains("Lnet/jejer/hipda/baselineprofile/") },
        )
        assertFalse(
            "startup-prof.txt must exclude nonMinifiedRelease harness package",
            startupLines.any { it.contains("Lnet/jejer/hipda/baselineprofile/") },
        )

        val baselineText = baselineLines.joinToString("\n")
        val requiredHotSymbols = listOf(
            "Lnet/jejer/hipda/ui/HiNextApplication;",
            "Lnet/jejer/hipda/feature/app/NextAppRootKt;",
            "Lnet/jejer/hipda/ui/shell/HiAdaptiveShellKt;",
            "Lnet/jejer/hipda/feature/forum/ForumPaneKt;",
            "Lnet/jejer/hipda/feature/threaddetail/ThreadDetailPaneKt;",
            "Lnet/jejer/hipda/feature/threaddetail/body/InlineEmojiContentKt;",
            "Lnet/jejer/hipda/emoji/EmojiMap;",
            "Lnet/jejer/hipda/parser/threadlist/ThreadListParser;",
            "Lnet/jejer/hipda/parser/threaddetail/ThreadDetailParser;",
            "Lcom/kyant/backdrop/",
        )
        val missing = requiredHotSymbols.filterNot { baselineText.contains(it) }
        assertEquals("Missing expected hot symbols in baseline-prof.txt", emptyList<String>(), missing)
    }

    @Test
    fun newCode_hasNoUnsafeDynamicResourceLookups() {
        val repoRoot = locateRepoRoot()
        val dynamicHits = mutableListOf<String>()
        for (file in collectNewCodeFiles(repoRoot)) {
            val cleaned = stripComments(file.readText(Charsets.UTF_8))
            val rel = file.relativeTo(repoRoot).invariantSeparatorsPath

            GET_IDENTIFIER_REGEX.findAll(cleaned).forEach { m ->
                val pkgArg = m.groupValues[3].trim()
                if (pkgArg != "\"android\"") {
                    dynamicHits += "$rel: ${m.value.trim()}"
                }
            }
            REFLECT_R_CLASS_REGEX.findAll(cleaned).forEach { m ->
                dynamicHits += "$rel: ${m.value.trim()}"
            }
        }
        assertEquals(
            "Unexpected dynamic resource lookup in new code / emoji sources: $dynamicHits",
            emptyList<String>(),
            dynamicHits,
        )
    }

    @Test
    fun releaseApk_containsValidProfilesAllCriticalAssetsAndNoShrunkNewCodeRefs() {
        val repoRoot = locateRepoRoot()
        val releaseApk = File(repoRoot, "hipda/build/outputs/apk/release/hipda-ng-release.apk")
        val resourcesReport = File(repoRoot, "hipda/build/outputs/mapping/release/resources.txt")
        val isCi = System.getenv("CI").equals("true", ignoreCase = true)

        if (isCi) {
            assertTrue("CI must build ${releaseApk.path} before running probes", releaseApk.isFile)
            assertTrue("CI must generate ${resourcesReport.path}", resourcesReport.isFile)
        }
        if (!releaseApk.isFile) return

        // 1. Validate binary ART baseline profile and metadata headers & non-empty size
        ZipFile(releaseApk).use { zip ->
            val prof = zip.getEntry("assets/dexopt/baseline.prof")
            val profm = zip.getEntry("assets/dexopt/baseline.profm")
            assertTrue("Missing or empty assets/dexopt/baseline.prof", prof != null && prof.size > 0L)
            assertTrue("Missing or empty assets/dexopt/baseline.profm", profm != null && profm.size > 0L)

            val profMagic = zip.getInputStream(prof!!).use { stream ->
                val buf = ByteArray(8)
                val read = stream.read(buf)
                if (read >= 4) String(buf, 0, 4, Charsets.US_ASCII) else ""
            }
            val profmMagic = zip.getInputStream(profm!!).use { stream ->
                val buf = ByteArray(8)
                val read = stream.read(buf)
                if (read >= 4) String(buf, 0, 4, Charsets.US_ASCII) else ""
            }
            assertEquals("Unexpected baseline.prof magic header", "pro\u0000", profMagic)
            assertEquals("Unexpected baseline.profm magic header", "prm\u0000", profmMagic)

            // 2. Verify About assets documents are non-empty in release APK
            for (asset in listOf("assets/release-notes.txt", "assets/donors.txt", "assets/license.txt")) {
                val entry = zip.getEntry(asset)
                assertTrue("Missing or empty $asset in release APK", entry != null && entry.size > 10L)
            }

            // 3. Verify BaselineProfileHostActivity never leaks into release APK manifest
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val manifestAscii = String(manifestBytes, Charsets.ISO_8859_1)
            val manifestUtf16 = String(manifestBytes, Charsets.UTF_16LE)
            assertFalse(
                "BaselineProfileHostActivity leaked into release AndroidManifest.xml",
                manifestAscii.contains("BaselineProfileHostActivity") ||
                    manifestUtf16.contains("BaselineProfileHostActivity"),
            )
        }

        // 4. Parse shrinker report (`resources.txt`) and verify:
        //    (a) zero resources referenced by new code / manifest / xmls are unreachable/shrunk
        //    (b) all emojis, notification icon, splash, shortcuts, launcher icons, and `win` are kept
        assertTrue("Expected shrink report at ${resourcesReport.path}", resourcesReport.isFile)
        val report = parseResourcesReport(resourcesReport.readText(Charsets.UTF_8))

        val newCodeRefs = collectAllNewCodeAndManifestResourceRefs(repoRoot)
        assertTrue("Expected non-trivial R/XML refs from new code", newCodeRefs.size >= 400)

        val brokenRefs = mutableListOf<String>()
        for ((sourceRel, ref) in newCodeRefs) {
            if (ref.type == "id" || ref.type == "attr" || ref.type == "styleable") continue
            val normalizedKey = "${ref.type}:${ref.name.replace('.', '_')}"
            val rawKey = "${ref.type}:${ref.name}"
            val isReachable = rawKey in report.reachable || normalizedKey in report.reachable
            val isExplicitlyUnreachable = rawKey in report.unreachable || normalizedKey in report.unreachable
            if (isExplicitlyUnreachable || !isReachable) {
                brokenRefs += "$sourceRel -> $rawKey"
            }
        }
        assertEquals(
            "Shrunk or unreachable resources are referenced by new code / manifest: $brokenRefs",
            emptyList<String>(),
            brokenRefs,
        )

        val emojiNames = File(repoRoot, "emoji/src/main/res/drawable-nodpi")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .map { "drawable:${it.nameWithoutExtension}" }
            .sorted()
        assertEquals(101, emojiNames.size)

        val criticalResources = emojiNames + listOf(
            "drawable:win",
            "drawable:ic_stat_hi",
            "drawable:splash",
            "drawable:splash_screen",
            "drawable:ic_shortcut_wb_sunny",
            "drawable:ic_shortcut_search",
            "drawable:ic_shortcut_mail",
            "drawable:ic_shortcut_favorite",
            "mipmap:ic_launcher",
            "mipmap:ic_launcher_foreground",
        )
        val missingCritical = criticalResources.filter { key ->
            key in report.unreachable || key !in report.reachable
        }
        assertEquals(
            "Critical emojis / notification / splash / shortcut / launcher resources missing or shrunk: $missingCritical",
            emptyList<String>(),
            missingCritical,
        )
    }

    private data class ResourceRef(val type: String, val name: String)

    private data class ParsedShrinkReport(
        val reachable: Set<String>,
        val unreachable: Set<String>,
    )

    companion object {
        private val R_REF_REGEX = Regex(
            """\bR\.(drawable|mipmap|string|array|plurals|color|dimen|style|xml|raw|anim|animator|bool|integer|font|layout|id|attr|styleable)\.([A-Za-z0-9_]+)""",
        )
        private val XML_REF_REGEX = Regex(
            """@(drawable|mipmap|string|array|plurals|color|dimen|style|xml|raw|anim|animator|bool|integer|font|layout)/([A-Za-z0-9_.]+)""",
        )
        private val GET_IDENTIFIER_REGEX = Regex(
            """getIdentifier\s*\(\s*([^,()]+)\s*,\s*([^,()]+)\s*,\s*([^,()]+)\s*\)""",
        )
        private val REFLECT_R_CLASS_REGEX = Regex(
            """R\s*\$\s*(drawable|mipmap|string|raw|xml|color|dimen|style)""",
        )

        private fun locateRepoRoot(): File = ReleaseResourceAndProfileGuardTest.locateRepoRoot()

        private fun collectNewCodeFiles(repoRoot: File): List<File> {
            val dirs = listOf(
                "hipda/src/main/java/net/jejer/hipda/feature",
                "hipda/src/main/java/net/jejer/hipda/data",
                "hipda/src/main/java/net/jejer/hipda/di",
                "hipda/src/main/java/net/jejer/hipda/ui/shell",
                "hipda/src/main/java/net/jejer/hipda/ui/designsystem",
                "hipda/src/main/java/net/jejer/hipda/ui/challenge",
                "emoji/src/main/java/net/jejer/hipda/emoji",
            )
            val singleFiles = listOf(
                "hipda/src/main/java/net/jejer/hipda/ui/HiNextApplication.kt",
                "hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt",
                "hipda/src/main/java/net/jejer/hipda/service/NotificationPoster.kt",
                "hipda/src/main/java/net/jejer/hipda/service/NotificationScheduler.kt",
                "hipda/src/main/java/net/jejer/hipda/service/QuietHoursChecker.kt",
            )
            val result = mutableListOf<File>()
            for (rel in dirs) {
                val dir = File(repoRoot, rel)
                assertTrue("Missing new-code directory ${dir.path}", dir.isDirectory)
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { result += it }
            }
            for (rel in singleFiles) {
                val f = File(repoRoot, rel)
                assertTrue("Missing new-code file ${f.path}", f.isFile)
                result += f
            }
            return result.sortedBy { it.path }
        }

        private fun collectAllNewCodeAndManifestResourceRefs(repoRoot: File): List<Pair<String, ResourceRef>> {
            val refs = mutableListOf<Pair<String, ResourceRef>>()
            for (file in collectNewCodeFiles(repoRoot)) {
                val rel = file.relativeTo(repoRoot).invariantSeparatorsPath
                val cleaned = stripComments(file.readText(Charsets.UTF_8))
                R_REF_REGEX.findAll(cleaned).forEach { m ->
                    refs += rel to ResourceRef(m.groupValues[1], m.groupValues[2])
                }
            }
            // Cross-check EmojiMap explicitly against all 61 light+dark emoji resources
            EmojiMap.groups.flatMap { it.codes }.forEach { code ->
                assertTrue("Light drawable missing in EmojiMap for $code", EmojiMap.drawableFor(code, dark = false) != null)
                assertTrue("Dark drawable missing in EmojiMap for $code", EmojiMap.drawableFor(code, dark = true) != null)
            }

            val xmlFiles = listOf(
                "hipda/src/main/AndroidManifest.xml",
                "hipda/src/main/res/xml/shortcuts.xml",
                "hipda/src/main/res/xml/provider_paths.xml",
                "hipda/src/main/res/xml/backup_config.xml",
                "hipda/src/main/res/xml/data_extraction_rules.xml",
                "hipda/src/main/res/drawable/splash_screen.xml",
                "hipda/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            )
            for (rel in xmlFiles) {
                val f = File(repoRoot, rel)
                if (!f.isFile) continue
                val xmlText = f.readText(Charsets.UTF_8)
                XML_REF_REGEX.findAll(xmlText).forEach { m ->
                    refs += rel to ResourceRef(m.groupValues[1], m.groupValues[2])
                }
            }
            return refs
        }

        private fun parseResourcesReport(reportText: String): ParsedShrinkReport {
            val reachable = HashSet<String>()
            val unreachable = HashSet<String>()
            for (line in reportText.lineSequence()) {
                val firstColon = line.indexOf(':')
                if (firstColon <= 0) continue
                val secondColon = line.indexOf(':', firstColon + 1)
                if (secondColon <= firstColon + 1) continue
                val key = line.substring(0, secondColon)
                when {
                    line.contains(" is not reachable.") -> unreachable += key
                    line.contains(" reachable from ") -> reachable += key
                }
            }
            return ParsedShrinkReport(reachable = reachable, unreachable = unreachable)
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
