package net.jejer.hipda.parser

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 纯离线脱敏样本夹具加载器（零旧代码依赖），读取 `hipda/src/test/resources/samples/` 下的真机样本。
 */
object SampleTestLoader {

    fun loadSample(relativePath: String): String {
        val resourceName = "samples/$relativePath"
        val stream = SampleTestLoader::class.java.classLoader?.getResourceAsStream(resourceName)
        val bytes = if (stream != null) {
            stream.use { it.readBytes() }
        } else {
            val filePath = resolveSamplePathFromRepo(relativePath)
            Files.readAllBytes(filePath)
        }
        return decodeBytes(bytes)
    }

    fun parseSampleDocument(relativePath: String): Document = Jsoup.parse(loadSample(relativePath))

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
