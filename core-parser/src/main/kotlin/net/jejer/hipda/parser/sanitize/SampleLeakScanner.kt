package net.jejer.hipda.parser.sanitize

import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.DISCUZ_UID_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.FORMHASH_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_SUMMARY_IN_DOCUMENT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.collectTextNodes
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.forEachNode
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isAllZeroMask
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSmsPlaceholder
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 扫描样本 HTML 是否残留敏感内容：
 * 非占位 formhash、非 10000 的 discuz_uid、Cookie 值、短消息页非占位正文。
 */
object SampleLeakScanner {

    val DEFAULT_SAMPLE_RELATIVE_PATHS = listOf(
        "core-parser/src/test/resources/samples",
        "hipda/src/test/resources/samples",
    )

    private val FORMHASH_URL_REGEX = Regex("""(?i)(?:^|[?&;'"])(m_formhash|formhash)=([^&#'"\s<>);,]+)""")
    private val FORMHASH_JS_ASSIGN_REGEX = Regex("""(?i)\b(m_formhash|formhash)\s*=\s*(['"])([^'"]+)\2""")
    private val FORMHASH_JS_OBJECT_REGEX = Regex("""(?i)(?<![.\w$])(['"]?)(m_formhash|formhash)\1\s*:\s*(['"])([^'"]+)\3""")

    fun scan(html: String): List<Leak> {
        val leaks = mutableListOf<Leak>()
        val doc = Jsoup.parse(html)

        for (input in doc.select(FORMHASH_INPUT)) {
            val value = input.attr("value").trim()
            if (value.isNotEmpty() && !isAllZeroMask(value)) {
                leaks.add(Leak(LeakType.FORMHASH, "input formhash 未脱敏 (length=${value.length})"))
            }
        }

        doc.forEachNode { node ->
            when (node) {
                is Element -> node.attributes().asList().forEach { attr ->
                    val key = attr.key.lowercase()
                    if (attr.value.isNotEmpty() && key != "class" && key != "id") {
                        scanTextForTokens(attr.value, "attr[${attr.key}]", leaks)
                    }
                }
                is DataNode -> scanTextForTokens(node.wholeData, "script", leaks)
                is Comment -> scanTextForTokens(node.data, "comment", leaks)
                is TextNode -> scanTextForTokens(node.wholeText, "text", leaks)
            }
        }

        for (summary in doc.select(SMS_SUMMARY_IN_DOCUMENT)) {
            if (summary.collectTextNodes().any { val t = it.wholeText.trim(); t.isNotEmpty() && !isSmsPlaceholder(t) }) {
                leaks.add(Leak(LeakType.SMS_CONTENT, "短消息 div.summary 包含非占位正文"))
            }
        }

        return leaks
    }

    fun scanDirectory(directory: Path): List<FileLeakReport> {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) return emptyList()
        val htmlFiles = Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html", ignoreCase = true) }
                .sorted()
                .toList()
        }
        return htmlFiles.mapNotNull { file ->
            scan(readHtmlFile(file)).takeIf { it.isNotEmpty() }?.let { FileLeakReport(file, it) }
        }
    }

    fun scanDirectories(directories: List<Path>): List<FileLeakReport> = directories.flatMap(::scanDirectory)

    fun formatLeakReport(reports: List<FileLeakReport>): String = buildString {
        appendLine("检测到未脱敏的样本文件，请用 HtmlSanitizer 重新导出：")
        for (report in reports) {
            val types = report.leaks.map { it.type }.distinct().joinToString(", ")
            appendLine("- 文件: ${report.file.fileName} (${report.file}) | 泄漏类型: [$types]")
            report.leaks.forEach { appendLine("    * ${it.type}: ${it.detail}") }
        }
    }

    private fun scanTextForTokens(text: String, source: String, leaks: MutableList<Leak>) {
        FORMHASH_URL_REGEX.findAll(text).forEach { m ->
            if (!isAllZeroMask(m.groupValues[2])) leaks.add(Leak(LeakType.FORMHASH, "$source 中 URL 参数 ${m.groupValues[1]} 未脱敏"))
        }
        FORMHASH_JS_ASSIGN_REGEX.findAll(text).forEach { m ->
            if (!isAllZeroMask(m.groupValues[3])) leaks.add(Leak(LeakType.FORMHASH, "$source 中脚本变量 ${m.groupValues[1]} 未脱敏"))
        }
        FORMHASH_JS_OBJECT_REGEX.findAll(text).forEach { m ->
            if (!isAllZeroMask(m.groupValues[4])) leaks.add(Leak(LeakType.FORMHASH, "$source 中对象字段 ${m.groupValues[2]} 未脱敏"))
        }
        DISCUZ_UID_REGEX.findAll(text).forEach { m ->
            val value = m.groupValues[3]
            if ((m.groupValues[2].isNotEmpty() || value.all(Char::isDigit)) && value != PLACEHOLDER_UID) {
                leaks.add(Leak(LeakType.DISCUZ_UID, "$source 中 discuz_uid 非 $PLACEHOLDER_UID"))
            }
        }
        COOKIE_ASSIGN_REGEX.findAll(text).forEach { m ->
            if (!isAllZeroMask(m.groupValues[3])) leaks.add(Leak(LeakType.COOKIE_VALUE, "$source 中发现未脱敏 Cookie 值"))
        }
        COOKIE_OBJECT_PROP_REGEX.findAll(text).forEach { m ->
            if (!isAllZeroMask(m.groupValues[4])) leaks.add(Leak(LeakType.COOKIE_VALUE, "$source 中发现未脱敏 Cookie 属性"))
        }
    }

    private fun readHtmlFile(path: Path): String {
        val bytes = Files.readAllBytes(path)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.UTF_8)) }
    }
}
