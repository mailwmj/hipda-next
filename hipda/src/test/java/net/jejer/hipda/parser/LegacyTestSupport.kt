package net.jejer.hipda.parser

import android.content.Context
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import net.jejer.hipda.bean.HiSettingsHelper
import net.jejer.hipda.glide.GlideHelper
import net.jejer.hipda.service.NotiHelper
import net.jejer.hipda.ui.HiApplication
import net.jejer.hipda.utils.HiParserThreadList
import net.jejer.hipda.utils.HiUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Robolectric 旧解析器离线测试基座，供 B7 / B8 / B9a / B9b 复用。
 *
 * 关键保护：
 * 1. 注入测试 Application Context 至旧 `HiApplication` / `HiSettingsHelper` 并刷新 `HiUtils.updateBaseUrls()`；
 * 2. 初始化 `GlideHelper.SYSTEM_AVATAR_FILE` 为临时路径，避免旧 `HiParser.parseNotify` 系统消息分支 NPE；
 * 3. 调用 `HiParserThreadList.holdFetchNotify()` 并刷新 `lastCheckSmsTime`，彻底阻断旧解析器内
 *    `ParseNotifyRunnable` 后台线程及 `NotiHelper.fetchNotification(doc)` 发起真实网络请求。
 */
object LegacyTestSupport {

    fun initLegacyEnvironment(context: Context = ApplicationProvider.getApplicationContext()): Context {
        val appContext = context.applicationContext ?: context
        injectHiApplicationContext(appContext)

        val settings = HiSettingsHelper.getInstance()
        rebindSettingsContext(settings, appContext)
        settings.reload()

        HiUtils.updateBaseUrls()
        ensureSystemAvatarFile(appContext)
        suppressBackgroundNotifyFetch()
        NotiHelper.clearNotification()
        return appContext
    }

    /**
     * 刷新旧解析器防抖窗口与短消息检查时间戳，保证调用 `HiParser.parseSimpleList` /
     * `HiParserThreadList.parse` / `NotiHelper.fetchNotification(doc)` 100% 离线。
     */
    fun suppressBackgroundNotifyFetch() {
        HiParserThreadList.holdFetchNotify()
        HiSettingsHelper.getInstance().setLastCheckSmsTime(System.currentTimeMillis())
    }

    fun loadSample(relativePath: String): String {
        val resourceName = "samples/$relativePath"
        val stream = LegacyTestSupport::class.java.classLoader?.getResourceAsStream(resourceName)
        val bytes = if (stream != null) {
            stream.use { it.readBytes() }
        } else {
            val filePath = resolveSamplePathFromRepo(relativePath)
            Files.readAllBytes(filePath)
        }
        return decodeBytes(bytes)
    }

    fun parseSampleDocument(relativePath: String): Document = Jsoup.parse(loadSample(relativePath))

    private fun injectHiApplicationContext(context: Context) {
        val field = HiApplication::class.java.getDeclaredField("context")
        field.isAccessible = true
        field.set(null, context)
    }

    @Suppress("DEPRECATION")
    private fun rebindSettingsContext(settings: HiSettingsHelper, context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        HiSettingsHelper::class.java.getDeclaredField("mCtx").apply {
            isAccessible = true
            set(settings, context)
        }
        HiSettingsHelper::class.java.getDeclaredField("mSharedPref").apply {
            isAccessible = true
            set(settings, prefs)
        }
        HiSettingsHelper::class.java.getDeclaredField("mEncrytedSharedPref").apply {
            isAccessible = true
            set(settings, prefs)
        }
    }

    private fun ensureSystemAvatarFile(context: Context) {
        if (GlideHelper.SYSTEM_AVATAR_FILE == null) {
            GlideHelper.SYSTEM_AVATAR_FILE = File(context.cacheDir, "system.png")
        }
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse {
                val gb18030 = runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.UTF_8)
                String(bytes, gb18030)
            }
    }

    private fun resolveSamplePathFromRepo(relativePath: String): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve("hipda/src/test/resources/samples/$relativePath").normalize()
            if (Files.isRegularFile(candidate)) {
                return candidate
            }
            current = current.parent
        }
        throw IllegalArgumentException("Sample file not found in classpath or repo: $relativePath")
    }
}
