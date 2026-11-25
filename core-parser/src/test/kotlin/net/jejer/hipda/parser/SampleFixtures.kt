package net.jejer.hipda.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 纯 JVM 样本夹具加载器，供 `:core-parser` 各解析器测试（B7/B8/B9a/B9b）复用。
 * 自动定位仓库根目录下只读真机脱敏样本 `hipda/src/test/resources/samples/`。
 */
object SampleFixtures {

    const val LOGIN_PAGE = "login/login_page.html"
    const val SMS_LIST = "sms/sms_list.html"
    const val SMS_DETAIL = "sms/sms_detail.html"
    const val NOTIFY_LIST = "notify/notify_list.html"

    private const val SAMPLES_RELATIVE_DIR = "hipda/src/test/resources/samples"

    fun samplesDir(): Path {
        val dir = resolveRepositoryRoot().resolve(SAMPLES_RELATIVE_DIR).normalize()
        require(Files.isDirectory(dir)) {
            "Sample directory not found: $dir"
        }
        return dir
    }

    fun samplePath(relativePath: String): Path {
        val root = samplesDir()
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root) && Files.isRegularFile(target)) {
            "Sample file not found: $relativePath (resolved=$target)"
        }
        return target
    }

    fun loadSample(relativePath: String): String {
        val bytes = Files.readAllBytes(samplePath(relativePath))
        return decodeBytes(bytes)
    }

    fun parseSampleDocument(relativePath: String): Document = Jsoup.parse(loadSample(relativePath))

    fun parseXmlDocument(xml: String): Document = Jsoup.parse(xml, "", Parser.xmlParser())

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

    private fun resolveRepositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        return Path.of("").toAbsolutePath().normalize()
    }
}
