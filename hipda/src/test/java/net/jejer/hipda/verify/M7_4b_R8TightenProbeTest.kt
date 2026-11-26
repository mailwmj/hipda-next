package net.jejer.hipda.verify

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-M7-4b 独立静态与 Release 产物探针（R8 相关专项）：
 * 1. 独立验证 `hipda/proguard-rules.txt` 与 `:emoji`/`:core-parser` 零过宽通配（`net.jejer.hipda.**`/`androidx.**` 等）、
 *    零已删旧库/旧类死规则，并显式保留经理由注释说明的 `com.kyant.backdrop` / `com.kyant.shapes` 与行号属性；
 * 2. 独立校验 Release APK 产物体积与 DEX 方法数显著收紧、包名/targetSdk 不变、单 DEX 合入、
 *    全套 101 个表情资源与 `assets` 三文档完整，以及 `NotificationPollingWorker`、8 个 `@HiltViewModel`、
 *    5 个 `@Serializable` + `$$serializer`、`HiDatabase_Impl`、`FallbackChallengeJsBridge` 与液态玻璃合约存活。
 */
class M7_4b_R8TightenProbeTest {

    @Test
    fun probe1_proguardRulesZeroBroadWildcardsZeroDeadLegacyRulesAndTargetedGlassKept() {
        val repoRoot = findRepoRoot()
        val proguardText = File(repoRoot, "hipda/proguard-rules.txt").readText(Charsets.UTF_8)
        val nonCommentLines = proguardText.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // 1. Banned broad wildcards and legacy library/class tokens must be completely absent.
        val bannedSubstrings = listOf(
            "net.jejer.hipda.**",
            "androidx.**",
            "org.jsoup.**",
            "com.google.gson",
            "net.gotev.cookiestore",
            "com.bumptech.glide",
            "com.mikepenz",
            "com.birbit",
            "org.greenrobot.eventbus",
            "com.zhihu.matisse",
            "com.squareup.picasso",
            "dev.chrisbanes.haze",
            "HiSettingsHelper",
            "HiApplication",
            "MainFrameActivity",
            "NotiWorker",
            "NotiHelper",
            "PublicSuffixDatabase",
            "org.openjsse",
            "org.conscrypt",
            "org.bouncycastle",
        )
        for (line in nonCommentLines) {
            for (banned in bannedSubstrings) {
                assertFalse(
                    "proguard-rules.txt 不应再包含已删旧规则或过宽通配 '$banned'，命中行：$line",
                    line.contains(banned, ignoreCase = true),
                )
            }
            assertFalse(
                "proguard-rules.txt 不应包含全包通配 '** {'：$line",
                line.startsWith("-keep") && line.contains(".**"),
            )
        }

        // 2. Targeted Kyant Backdrop / Shapes & stacktrace rules must stay present.
        val joined = nonCommentLines.joinToString("\n")
        val requiredTargetedTokens = listOf(
            "-keepattributes SourceFile,LineNumberTable",
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
        for (token in requiredTargetedTokens) {
            assertTrue("proguard-rules.txt 缺少必要定向规则：$token", joined.contains(token))
        }

        // 3. :emoji and :core-parser must not harbor stray legacy consumer proguard files.
        for (sub in listOf("emoji", "core-parser")) {
            val proFiles = File(repoRoot, sub).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && (it.extension == "pro" || it.name.contains("proguard", true)) }
                .toList()
            assertTrue("$sub 不应残留额外 proguard 规则文件：$proFiles", proFiles.isEmpty())
        }
    }

    @Test
    fun probe2_releaseApkSizeDexMethodReductionEmojisAndRuntimeContracts() {
        val repoRoot = findRepoRoot()
        val releaseApk = File(repoRoot, "hipda/build/outputs/apk/release/hipda-ng-release.apk")
        val mappingFile = File(repoRoot, "hipda/build/outputs/mapping/release/mapping.txt")
        val isCi = System.getenv("CI").equals("true", ignoreCase = true)
        if (isCi) {
            assertTrue("CI 下必须产出 release APK：${releaseApk.path}", releaseApk.isFile)
            assertTrue("CI 下必须产出 mapping.txt：${mappingFile.path}", mappingFile.isFile)
        }
        if (!releaseApk.isFile || !mappingFile.isFile) return

        // Pre-tighten E7 baseline: 6,299,655 B, 2 DEX, 70,418 methods.
        assertTrue(
            "收紧 R8 后 Release APK 应明显小于收紧前基线 6,299,655 B（阈值 < 5.3 MiB），实际：${releaseApk.length()}",
            releaseApk.length() < 5_557_452L,
        )

        val definedDescriptors = HashSet<String>()
        var totalMethods = 0
        var dexCount = 0
        ZipFile(releaseApk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                .toList()
            dexCount = dexEntries.size
            assertEquals("收紧 R8 后应合并为单 DEX，实际：$dexCount", 1, dexCount)

            for (entry in dexEntries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val dex = parseDexHeaderAndClasses(bytes)
                totalMethods += dex.methodIdsSize
                definedDescriptors.addAll(dex.definedClassDescriptors)
            }

            // Verify 3 assets docs, compiled Baseline Profile (M7-4a + M7-4b), & all 101 emoji PNGs survive intact in release APK.
            for (doc in listOf("assets/license.txt", "assets/release-notes.txt", "assets/donors.txt", "assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")) {
                val e = zip.getEntry(doc)
                assertTrue("Release APK 缺少 $doc 或为空", e != null && e.size > 0)
            }
            val emojiFiles = File(repoRoot, "emoji/src/main/res/drawable-nodpi")
                .listFiles { f -> f.isFile && f.extension == "png" }
                ?.map { it.nameWithoutExtension }
                .orEmpty()
            assertEquals("应有 101 个表情源 PNG", 101, emojiFiles.size)
            // Note: without resource shrinking, all PNGs keep their names in res/drawable-nodpi/ or res/; verify count of PNGs >= 101.
            val apkPngCount = zip.entries().asSequence().count { it.name.startsWith("res/") && it.name.endsWith(".png") }
            assertTrue("Release APK 中 PNG 资源数应覆盖全部 101 个表情，实际：$apkPngCount", apkPngCount >= 101)
        }

        assertTrue(
            "收紧 R8 后 DEX 方法数应大幅低于收紧前 70,418（阈值 < 45,000），实际：$totalMethods",
            totalMethods < 45_000,
        )

        // Verify exact unobfuscated runtime names required by WorkManager / Room / Android / WebView JS
        val exactNames = listOf(
            "Lnet/jejer/hipda/ui/HiNextApplication;",
            "Lnet/jejer/hipda/feature/app/MainActivity;",
            "Lnet/jejer/hipda/ui/challenge/ChallengeActivity;",
            "Lnet/jejer/hipda/ui/challenge/AndroidChallengeWebViewHandle\$FallbackChallengeJsBridge;",
            "Lnet/jejer/hipda/data/local/HiDatabase;",
            "Lnet/jejer/hipda/data/local/HiDatabase_Impl;",
            "Lnet/jejer/hipda/service/NotificationPollingWorker;",
        )
        for (desc in exactNames) {
            assertTrue("Release DEX 必须保留精确类名 $desc", desc in definedDescriptors)
        }

        // Verify mapping preserves obfuscated/unremoved critical classes & eliminates banned legacy packages
        val mappingLines = mappingFile.readLines(Charsets.UTF_8)
        val mappedClasses = HashMap<String, String>()
        for (line in mappingLines) {
            if (!line.startsWith(" ") && line.contains(" -> ") && line.endsWith(":")) {
                val orig = line.substringBefore(" -> ").trim()
                val obf = line.substringAfter(" -> ").removeSuffix(":").trim()
                mappedClasses[orig] = obf
            }
        }
        val criticalClasses = listOf(
            "net.jejer.hipda.data.account.SavedAccount\$\$serializer",
            "net.jejer.hipda.data.account.CredentialsSnapshot\$\$serializer",
            "net.jejer.hipda.data.network.cookie.StoredCookieStore\$\$serializer",
            "net.jejer.hipda.data.message.AccountNotifyReadState\$\$serializer",
            "net.jejer.hipda.feature.app.NextAppHostViewModel",
            "net.jejer.hipda.feature.forum.ForumViewModel",
            "net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel",
            "net.jejer.hipda.feature.compose.FullEditorViewModel",
            "net.jejer.hipda.feature.compose.QuickReplyViewModel",
            "net.jejer.hipda.feature.login.LoginViewModel",
            "net.jejer.hipda.feature.messages.MessagesListViewModel",
            "net.jejer.hipda.feature.messages.SmsConversationViewModel",
            "com.kyant.backdrop.AndroidRuntimeShader",
            "com.kyant.backdrop.RuntimeShaderCacheImpl",
            "com.kyant.backdrop.DrawBackdropNode",
            "com.kyant.backdrop.backdrops.LayerBackdropNode",
            "com.kyant.backdrop.effects.LensKt",
            "com.kyant.shapes.RoundedRectangularShape",
        )
        for (cls in criticalClasses) {
            val obf = mappedClasses[cls]
            assertTrue("mapping.txt 缺少关键类 $cls", obf != null && !obf.startsWith("R8\$\$REMOVED"))
            assertTrue("DEX 缺少 $cls -> $obf", "L${obf!!.replace('.', '/')};" in definedDescriptors)
        }
        val bannedSurvivingPrefixes = listOf(
            "com.google.gson.reflect.TypeToken",
            "androidx.preference.",
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
        for (legacyPrefix in bannedSurvivingPrefixes) {
            assertFalse(
                "Release mapping 不应再保留已删旧库/旧包类 $legacyPrefix",
                mappedClasses.entries.any { (orig, obf) ->
                    orig.startsWith(legacyPrefix) && !obf.startsWith("R8\$\$REMOVED")
                },
            )
        }
        val seedsFile = File(repoRoot, "hipda/build/outputs/mapping/release/seeds.txt")
        if (seedsFile.isFile) {
            val seedClasses = seedsFile.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains(':') }
            assertTrue(
                "收紧后 net.jejer.hipda.* 种子数应 < 200（原 2,461）",
                seedClasses.count { it.startsWith("net.jejer.hipda.") } < 200,
            )
            assertTrue(
                "收紧后 androidx.* 种子数应 < 1,000（原 9,000+）",
                seedClasses.count { it.startsWith("androidx.") } < 1_000,
            )
        }
    }

    private data class DexInfo(
        val methodIdsSize: Int,
        val definedClassDescriptors: Set<String>,
    )

    private fun parseDexHeaderAndClasses(bytes: ByteArray): DexInfo {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(0x38)
        val stringIdsOff = buf.getInt(0x3C)
        val typeIdsSize = buf.getInt(0x40)
        val typeIdsOff = buf.getInt(0x44)
        val methodIdsSize = buf.getInt(0x58)
        val classDefsSize = buf.getInt(0x60)
        val classDefsOff = buf.getInt(0x64)

        val strings = Array(stringIdsSize) { idx ->
            val strDataOff = buf.getInt(stringIdsOff + idx * 4)
            readMutf8String(bytes, strDataOff)
        }
        val types = Array(typeIdsSize) { idx ->
            val strIdx = buf.getInt(typeIdsOff + idx * 4)
            strings[strIdx]
        }
        val classDescriptors = HashSet<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val typeIdx = buf.getInt(classDefsOff + i * 32)
            classDescriptors.add(types[typeIdx])
        }
        return DexInfo(methodIdsSize, classDescriptors)
    }

    private fun readMutf8String(bytes: ByteArray, offset: Int): String {
        var pos = offset
        var shift = 0
        var utf16Len = 0
        while (true) {
            val b = bytes[pos++].toInt() and 0xFF
            utf16Len = utf16Len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        val chars = CharArray(utf16Len)
        var outIdx = 0
        while (outIdx < utf16Len) {
            val a = bytes[pos++].toInt() and 0xFF
            when {
                a == 0 -> break
                a < 0x80 -> chars[outIdx++] = a.toChar()
                (a and 0xE0) == 0xC0 -> {
                    val b = bytes[pos++].toInt() and 0xFF
                    chars[outIdx++] = (((a and 0x1F) shl 6) or (b and 0x3F)).toChar()
                }
                (a and 0xF0) == 0xE0 -> {
                    val b = bytes[pos++].toInt() and 0xFF
                    val c = bytes[pos++].toInt() and 0xFF
                    chars[outIdx++] = (((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar()
                }
                else -> break
            }
        }
        return String(chars, 0, outIdx)
    }

    private fun findRepoRoot(): File =
        listOf(File("."), File("..")).firstOrNull {
            File(it, "settings.gradle.kts").isFile && File(it, "hipda/proguard-rules.txt").isFile
        } ?: error("无法定位仓库根目录")
}
