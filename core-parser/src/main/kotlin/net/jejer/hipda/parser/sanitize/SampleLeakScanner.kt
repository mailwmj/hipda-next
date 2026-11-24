package net.jejer.hipda.parser.sanitize

import net.jejer.hipda.parser.sanitize.SanitizeSelectors.ATTACHMENT_AID_URL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.AVATAR_PATH_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.BLACKLIST_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.BLOCKED_PLACEHOLDER_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.DISCUZ_UID_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.EMAIL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.EMBEDDED_INPUT_TAG_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.FORMHASH_FIELD_NAMES
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.FORMHASH_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.INPUT_NAME_OR_ID_ATTR_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.INPUT_VALUE_ATTR_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.IPV4_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.IPV6_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_FORMHASH_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_OTHER_TOKEN_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.NOTIFY_SENSITIVE_ITEMS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_SELF_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SENSITIVE_TOKEN_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_CITE
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_IMG
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_ITEMS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_MSGTO_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_PEER_HEADER_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_POST_FORM
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_SUMMARY_IN_DOCUMENT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_TITLE_PEER_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UID_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UMENU_USER_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.URL_TOKEN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.USERNAME_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.collectTextNodes
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.decodeAttachmentAid
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.firstMatch
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.forEachNode
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isAllZeroMask
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeEmail
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeIpv4
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeIpv6
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeSmsHref
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeSmsImgSrc
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSensitiveFieldName
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSmsPlaceholder
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isXmlDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 扫描样本 HTML / XML 是否残留未脱敏的敏感内容，与 HtmlSanitizer 脱敏规则对称。
 */
object SampleLeakScanner {

    val DEFAULT_SAMPLE_RELATIVE_PATHS = listOf(
        "core-parser/src/test/resources/samples",
        "hipda/src/test/resources/samples",
    )

    private val SCANNED_EXTENSIONS = setOf(".html", ".htm", ".xml")
    private val ALLOWED_SMS_UIDS = setOf(PLACEHOLDER_UID, PLACEHOLDER_PEER_UID)
    private val ALLOWED_SMS_NAMES = setOf(PLACEHOLDER_USERNAME, PLACEHOLDER_PEER_USERNAME)
    private val ALLOWED_SMS_AVATARS = setOf(PLACEHOLDER_SELF_AVATAR_PATH, PLACEHOLDER_PEER_AVATAR_PATH)

    fun scan(html: String): List<Leak> {
        val leaks = mutableListOf<Leak>()
        if (isXmlDocument(html)) {
            val xmlDoc = Jsoup.parse(html, "", Parser.xmlParser())
            xmlDoc.forEachNode { node ->
                when (node) {
                    is CDataNode -> scanDocument(Jsoup.parseBodyFragment(node.text()), leaks)
                    is Element -> node.attributes().asList().forEach { attr ->
                        val key = attr.key.lowercase()
                        if (attr.value.isNotEmpty() && key != "class" && key != "id") {
                            scanString(attr.value, "xml-attr[${attr.key}]", leaks)
                        }
                    }
                    is Comment -> scanString(node.data, "xml-comment", leaks)
                    is TextNode -> scanString(node.wholeText, "xml-text", leaks)
                }
            }
            return leaks
        }
        scanDocument(Jsoup.parse(html), leaks)
        return leaks
    }

    fun scanDirectory(directory: Path): List<FileLeakReport> {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) return emptyList()
        val files = Files.walk(directory).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString().lowercase()
                Files.isRegularFile(path) && SCANNED_EXTENSIONS.any(name::endsWith)
            }.sorted().toList()
        }
        return files.mapNotNull { file ->
            scan(readSampleFile(file)).takeIf { it.isNotEmpty() }?.let { FileLeakReport(file, it) }
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

    private fun scanDocument(doc: Document, leaks: MutableList<Leak>) {
        // 1. 表单中的 formhash 与其他敏感令牌（hash/sid/auth/seccode*）
        for (input in doc.select(FORMHASH_INPUT)) {
            val value = input.attr("value").trim()
            if (value.isNotEmpty() && !isAllZeroMask(value)) {
                leaks.add(Leak(LeakType.FORMHASH, "input formhash 未脱敏 (length=${value.length})"))
            }
        }
        for (input in doc.select(SENSITIVE_TOKEN_INPUT)) {
            val value = input.attr("value").trim()
            if (value.isNotEmpty() && !isAllZeroMask(value)) {
                val field = input.attr("name").ifEmpty { input.attr("id") }
                leaks.add(Leak(LeakType.SENSITIVE_TOKEN, "input $field 未脱敏 (length=${value.length})"))
            }
        }

        // 2. #umenu 当前用户身份对称校验
        for (link in doc.select(UMENU_USER_LINK)) {
            val uid = UID_QUERY_PARAM_REGEX.find(link.attr("href"))?.groupValues?.get(3)
            if (uid != null && uid != PLACEHOLDER_UID) {
                leaks.add(Leak(LeakType.UMENU_IDENTITY, "#umenu 个人链接 uid 非 $PLACEHOLDER_UID"))
            }
            val username = link.text().trim()
            if (username.isNotEmpty() && username != PLACEHOLDER_USERNAME) {
                leaks.add(Leak(LeakType.UMENU_IDENTITY, "#umenu 用户名非 $PLACEHOLDER_USERNAME"))
            }
        }

        // 3. 遍历节点属性、脚本、注释、文本
        doc.forEachNode { node ->
            when (node) {
                is Element -> node.attributes().asList().forEach { attr ->
                    val key = attr.key.lowercase()
                    if (attr.value.isNotEmpty() && key != "class" && key != "id") {
                        scanString(attr.value, "attr[${attr.key}]", leaks)
                    }
                }
                is DataNode -> scanString(node.wholeData, "script", leaks)
                is Comment -> scanString(node.data, "comment", leaks)
                is TextNode -> scanString(node.wholeText, "text", leaks)
            }
        }

        // 4. 短消息正文文本与内部元素属性（href/src/title/alt）
        for (summary in doc.select(SMS_SUMMARY_IN_DOCUMENT)) {
            if (summary.collectTextNodes().any { val t = it.wholeText.trim(); t.isNotEmpty() && !isSmsPlaceholder(t) }) {
                leaks.add(Leak(LeakType.SMS_CONTENT, "短消息 div.summary 包含非占位正文"))
            }
            for (el in summary.allElements) {
                if (el === summary) continue
                val tag = el.tagName().lowercase()
                if (tag == "a") {
                    val href = el.attr("href").trim()
                    if (href.isNotEmpty() && !isSafeSmsHref(href)) {
                        leaks.add(Leak(LeakType.SMS_CONTENT, "短消息 div.summary 包含未脱敏链接 href"))
                    }
                }
                if (tag == "img") {
                    for (imgAttr in listOf("src", "file", "data-src")) {
                        val src = el.attr(imgAttr).trim()
                        if (src.isNotEmpty() && !isSafeSmsImgSrc(src)) {
                            leaks.add(Leak(LeakType.SMS_CONTENT, "短消息 div.summary 包含未脱敏图片 $imgAttr"))
                        }
                    }
                }
                for (attrName in listOf("title", "alt")) {
                    val v = el.attr(attrName).trim()
                    if (v.isNotEmpty() && !isSmsPlaceholder(v)) {
                        leaks.add(Leak(LeakType.SMS_CONTENT, "短消息 div.summary 包含未脱敏属性 $attrName"))
                    }
                }
            }
        }

        // 5. 短消息对方身份（消息项、回复表单、单向会话页头/标题）
        val smsItems = doc.select(SMS_ITEMS)
        val smsForms = doc.select(SMS_POST_FORM)
        if (smsItems.isNotEmpty() || smsForms.isNotEmpty()) {
            scanSmsPeerIdentity(doc, smsItems, smsForms, leaks)
        }

        // 6. 提醒页 f_buddy/f_manage 与黑名单页
        scanNotifyAndBlacklist(doc, leaks)
    }

    private fun scanSmsPeerIdentity(doc: Document, smsItems: List<Element>, smsForms: List<Element>, leaks: MutableList<Leak>) {
        for (item in smsItems) {
            item.firstMatch(SMS_AUTHOR_CITE)?.text()?.trim()?.takeIf { it.isNotEmpty() && it !in ALLOWED_SMS_NAMES }?.let {
                leaks.add(Leak(LeakType.SMS_PEER, "短消息项作者未脱敏"))
            }
            listOfNotNull(item.firstMatch(SMS_AUTHOR_LINK), item.firstMatch(SMS_AVATAR_LINK)).forEach { link ->
                val uid = UID_QUERY_PARAM_REGEX.find(link.attr("href"))?.groupValues?.get(3)
                if (uid != null && uid !in ALLOWED_SMS_UIDS) {
                    leaks.add(Leak(LeakType.SMS_PEER, "短消息项链接 uid 非 10000/20000"))
                }
            }
            item.firstMatch(SMS_AVATAR_IMG)?.attr("src")?.let { src ->
                val path = AVATAR_PATH_REGEX.find(src)?.groupValues?.get(1)
                if (path != null && path !in ALLOWED_SMS_AVATARS) {
                    leaks.add(Leak(LeakType.SMS_PEER, "短消息项头像路径未脱敏"))
                }
            }
        }
        for (form in smsForms) {
            val uid = UID_QUERY_PARAM_REGEX.find(form.attr("action"))?.groupValues?.get(3)
            if (uid != null && uid !in ALLOWED_SMS_UIDS) {
                leaks.add(Leak(LeakType.SMS_PEER, "短消息表单 action uid 非 10000/20000"))
            }
        }
        for (input in doc.select(SMS_MSGTO_INPUT)) {
            val v = input.attr("value").trim()
            if (v.isNotEmpty() && v !in ALLOWED_SMS_NAMES) {
                leaks.add(Leak(LeakType.SMS_PEER, "短消息表单 msgto 未脱敏"))
            }
        }
        for (link in doc.select(SMS_PEER_HEADER_LINKS)) {
            val uid = UID_QUERY_PARAM_REGEX.find(link.attr("href"))?.groupValues?.get(3)
            val name = link.text().trim()
            if ((uid != null && uid !in ALLOWED_SMS_UIDS) || (name.isNotEmpty() && name !in ALLOWED_SMS_NAMES)) {
                leaks.add(Leak(LeakType.SMS_PEER, "短消息页头对方身份未脱敏"))
            }
        }
        SMS_TITLE_PEER_REGEX.find(doc.title())?.groupValues?.get(1)?.trim()?.takeIf { it !in ALLOWED_SMS_NAMES }?.let {
            leaks.add(Leak(LeakType.SMS_PEER, "短消息标题包含未脱敏对方用户名"))
        }
    }

    private fun scanNotifyAndBlacklist(doc: Document, leaks: MutableList<Leak>) {
        for (item in doc.select(NOTIFY_SENSITIVE_ITEMS)) {
            for (link in item.select("a[href*=space.php]")) {
                val uid = UID_QUERY_PARAM_REGEX.find(link.attr("href"))?.groupValues?.get(3)
                val name = link.text().trim()
                if ((uid != null && uid !in ALLOWED_SMS_UIDS) || (name.isNotEmpty() && name !in ALLOWED_SMS_NAMES)) {
                    leaks.add(Leak(LeakType.PERSONAL_INFO, "提醒项 div.f_buddy/f_manage 包含未脱敏用户"))
                }
            }
            val hasUnmaskedNote = item.collectTextNodes().any { textNode ->
                val parentTag = textNode.parent()?.tagName()?.lowercase()
                val trimmed = textNode.wholeText.trim()
                parentTag != "em" && parentTag != "a" && trimmed.isNotEmpty() && !isSmsPlaceholder(trimmed)
            }
            if (hasUnmaskedNote) {
                leaks.add(Leak(LeakType.PERSONAL_INFO, "提醒项 div.f_buddy/f_manage 包含未脱敏附言文本"))
            }
        }
        for (link in doc.select(BLACKLIST_LINKS)) {
            val param = USERNAME_QUERY_PARAM_REGEX.find(link.attr("href"))?.groupValues?.get(3)
            val text = link.text().trim()
            val paramBad = param != null && !BLOCKED_PLACEHOLDER_REGEX.matches(param)
            val textBad = text.isNotEmpty() && !BLOCKED_PLACEHOLDER_REGEX.matches(text)
            if (paramBad || textBad) {
                leaks.add(Leak(LeakType.PERSONAL_INFO, "黑名单列表包含未脱敏用户名"))
            }
        }
    }

    private fun scanString(text: String, source: String, leaks: MutableList<Leak>) {
        // 内嵌 <input> 标签（脚本模板等）
        for (tagMatch in EMBEDDED_INPUT_TAG_REGEX.findAll(text)) {
            val tagHtml = tagMatch.value
            val fieldNames = INPUT_NAME_OR_ID_ATTR_REGEX.findAll(tagHtml).map { it.groupValues[2].lowercase() }.toList()
            val matchedField = fieldNames.firstOrNull(::isSensitiveFieldName) ?: continue
            val value = INPUT_VALUE_ATTR_REGEX.find(tagHtml)?.groupValues?.get(3).orEmpty()
            if (value.isNotEmpty() && !isAllZeroMask(value)) {
                val type = if (matchedField in FORMHASH_FIELD_NAMES) LeakType.FORMHASH else LeakType.SENSITIVE_TOKEN
                leaks.add(Leak(type, "$source 中内嵌 input[$matchedField] 未脱敏"))
            }
        }

        // attachment.php?aid= Base64
        for (m in ATTACHMENT_AID_URL_REGEX.findAll(text)) {
            val payload = decodeAttachmentAid(m.groupValues[2]) ?: continue
            val hash = payload.groupValues[2]
            val uid = payload.groupValues[4]
            if (!isAllZeroMask(hash) || uid != PLACEHOLDER_UID) {
                leaks.add(Leak(LeakType.ATTACHMENT_AID, "$source 中 attachment.php aid Base64 载荷未脱敏"))
            }
        }

        // URL / JS 敏感令牌
        for (m in URL_TOKEN_REGEX.findAll(text)) {
            val name = m.groupValues[2].lowercase()
            if (!isAllZeroMask(m.groupValues[3])) {
                val type = if (name in FORMHASH_FIELD_NAMES) LeakType.FORMHASH else LeakType.SENSITIVE_TOKEN
                leaks.add(Leak(type, "$source 中 URL 参数 ${m.groupValues[2]} 未脱敏"))
            }
        }
        for (m in JS_FORMHASH_ASSIGN_REGEX.findAll(text)) {
            val name = m.groupValues[2].lowercase()
            if (!isAllZeroMask(m.groupValues[4])) {
                val type = if (name in FORMHASH_FIELD_NAMES) LeakType.FORMHASH else LeakType.SENSITIVE_TOKEN
                leaks.add(Leak(type, "$source 中脚本变量 ${m.groupValues[2]} 未脱敏"))
            }
        }
        for (m in JS_OTHER_TOKEN_ASSIGN_REGEX.findAll(text)) {
            if (!isAllZeroMask(m.groupValues[4])) {
                leaks.add(Leak(LeakType.SENSITIVE_TOKEN, "$source 中脚本变量 ${m.groupValues[2]} 未脱敏"))
            }
        }
        for (m in JS_OBJECT_PROP_REGEX.findAll(text)) {
            val name = m.groupValues[3].lowercase()
            if (!isAllZeroMask(m.groupValues[5])) {
                val type = if (name in FORMHASH_FIELD_NAMES) LeakType.FORMHASH else LeakType.SENSITIVE_TOKEN
                leaks.add(Leak(type, "$source 中对象字段 ${m.groupValues[3]} 未脱敏"))
            }
        }

        // discuz_uid（含 =、:、parseInt）
        for (m in DISCUZ_UID_REGEX.findAll(text)) {
            val value = m.groupValues[4]
            if ((m.groupValues[3].isNotEmpty() || value.all(Char::isDigit)) && value != PLACEHOLDER_UID) {
                leaks.add(Leak(LeakType.DISCUZ_UID, "$source 中 discuz_uid 非 $PLACEHOLDER_UID"))
            }
        }

        // Cookie 值（cdb_* / cf_clearance / __cf_bm / cf_chl_*）
        for (m in COOKIE_ASSIGN_REGEX.findAll(text)) {
            if (!isAllZeroMask(m.groupValues[3])) {
                leaks.add(Leak(LeakType.COOKIE_VALUE, "$source 中发现未脱敏 Cookie 值"))
            }
        }
        for (m in COOKIE_OBJECT_PROP_REGEX.findAll(text)) {
            if (!isAllZeroMask(m.groupValues[4])) {
                leaks.add(Leak(LeakType.COOKIE_VALUE, "$source 中发现未脱敏 Cookie 属性"))
            }
        }

        // 邮箱与 IPv4/IPv6
        for (m in EMAIL_REGEX.findAll(text)) {
            if (!isSafeEmail(m.groupValues[1])) {
                leaks.add(Leak(LeakType.PERSONAL_INFO, "$source 中发现未脱敏邮箱地址"))
            }
        }
        for (m in IPV4_REGEX.findAll(text)) {
            if (!isSafeIpv4(m.groupValues[1])) {
                leaks.add(Leak(LeakType.PERSONAL_INFO, "$source 中发现未脱敏 IPv4 地址"))
            }
        }
        for (m in IPV6_REGEX.findAll(text)) {
            if (!isSafeIpv6(m.groupValues[1])) {
                leaks.add(Leak(LeakType.PERSONAL_INFO, "$source 中发现未脱敏 IPv6 地址"))
            }
        }
    }

    private fun readSampleFile(path: Path): String {
        val bytes = Files.readAllBytes(path)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.UTF_8)) }
    }
}
