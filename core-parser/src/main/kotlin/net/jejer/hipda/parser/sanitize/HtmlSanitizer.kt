package net.jejer.hipda.parser.sanitize

import net.jejer.hipda.parser.sanitize.SanitizeSelectors.AVATAR_PATH_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.DISCUZ_UID_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_SELF_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_CITE
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_IMG
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_ITEMS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_MSGTO_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_POST_FORM
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_SUMMARY_IN_DOCUMENT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UID_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UMENU_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UMENU_USER_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.USERNAME_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.avatarPathToUid
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.buildSmsPlaceholder
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.collectTextNodes
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.firstMatch
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.forEachNode
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isAllZeroMask
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.maskSameLength
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.uidToAvatarPath
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 纯 JVM 的 Discuz!/HiPDA HTML 样本脱敏器。
 * 仅替换文本、属性值、脚本与注释中的敏感信息，不删除节点、不修改 class/id。
 */
object HtmlSanitizer {

    private val SENSITIVE_FIELDS = setOf(
        "formhash", "m_formhash", "hash", "sid", "auth",
        "seccode", "seccodeverify", "seccodehash",
        "cdb_auth", "cdb_sid", "cf_clearance",
    )

    private val URL_TOKEN_REGEX =
        Regex("""(?i)(^|[?&;'"])(formhash|m_formhash|hash|sid|auth|seccode(?:verify|hash)?)=([^&#'"\s<>);,]+)""")
    private val JS_TOKEN_ASSIGN_REGEXES = listOf(
        Regex("""(?i)(\b(?:m_formhash|formhash|seccode(?:verify|hash)?)\s*=\s*)(['"])([^'"]+)\2"""),
        Regex("""(?i)((?<![.\w$])(?:hash|sid|auth)\s*=\s*)(['"])([^'"]+)\2"""),
        COOKIE_ASSIGN_REGEX,
    )
    private val JS_OBJECT_PROP_REGEXES = listOf(
        Regex("""(?i)((?<![.\w$])(['"]?)(?:formhash|m_formhash|hash|sid|auth|seccode(?:verify|hash)?)\2\s*:\s*)(['"])([^'"]+)\3"""),
        COOKIE_OBJECT_PROP_REGEX,
    )

    fun sanitize(html: String, page: PageType, identity: Identity): String {
        val doc = Jsoup.parse(html).apply { outputSettings().prettyPrint(false) }

        val (selfUids, selfNames) = collectSelfIdentities(doc, identity)
        val (peerUids, peerNames) = if (page.isSms) collectPeerIdentities(doc, selfUids, selfNames) else emptySet<String>() to emptySet()
        val skippedTextNodes = if (page.isSms) sanitizeSmsSummaries(doc) else emptySet()

        val uidMap = buildMap {
            selfUids.forEach { put(it, PLACEHOLDER_UID) }
            peerUids.forEach { putIfAbsent(it, PLACEHOLDER_PEER_UID) }
        }
        val nameMap = buildMap {
            selfNames.forEach { put(it, PLACEHOLDER_USERNAME) }
            peerNames.forEach { putIfAbsent(it, PLACEHOLDER_PEER_USERNAME) }
        }

        applyTokenAndIdentityRules(doc, uidMap, nameMap, skippedTextNodes)
        normalizeUmenu(doc)
        if (page.isSms) normalizeSmsPeerNodes(doc)
        return doc.outerHtml()
    }

    private fun collectSelfIdentities(doc: Document, identity: Identity): Pair<Set<String>, Set<String>> {
        val uids = linkedSetOf<String>()
        val names = linkedSetOf<String>()
        identity.uid.trim().takeIf(::isReplaceableUid)?.let(uids::add)
        identity.username.trim().takeIf { it.isNotEmpty() && it != PLACEHOLDER_USERNAME }?.let(names::add)
        for (link in doc.select(UMENU_USER_LINK)) {
            extractUidParam(link.attr("href"))?.takeIf(::isReplaceableUid)?.let(uids::add)
            link.text().trim().takeIf { it.isNotEmpty() && it != PLACEHOLDER_USERNAME }?.let(names::add)
        }
        return uids to names
    }

    private fun collectPeerIdentities(doc: Document, selfUids: Set<String>, selfNames: Set<String>): Pair<Set<String>, Set<String>> {
        val peerUids = linkedSetOf<String>()
        val peerNames = linkedSetOf<String>()
        fun addUid(v: String?) = v?.trim()?.takeIf { isReplaceableUid(it) && it !in selfUids && it != PLACEHOLDER_PEER_UID }?.let(peerUids::add)
        fun addName(v: String?) = v?.trim()?.takeIf {
            it.isNotEmpty() && it !in selfNames && it != PLACEHOLDER_USERNAME && it != PLACEHOLDER_PEER_USERNAME
        }?.let(peerNames::add)

        for (item in doc.select(SMS_ITEMS)) {
            item.firstMatch(SMS_AUTHOR_CITE)?.let { addName(it.text()) }
            item.firstMatch(SMS_AUTHOR_LINK)?.let { addUid(extractUidParam(it.attr("href"))) }
            item.firstMatch(SMS_AVATAR_LINK)?.let { addUid(extractUidParam(it.attr("href"))) }
            item.firstMatch(SMS_AVATAR_IMG)?.let { img ->
                AVATAR_PATH_REGEX.find(img.attr("src"))?.let { addUid(avatarPathToUid(it.groupValues[1])) }
            }
        }
        doc.select(SMS_POST_FORM).forEach { addUid(extractUidParam(it.attr("action"))) }
        doc.select(SMS_MSGTO_INPUT).forEach { addName(it.attr("value")) }
        return peerUids to peerNames
    }

    private fun sanitizeSmsSummaries(doc: Document): Set<TextNode> {
        val replaced = Collections.newSetFromMap(IdentityHashMap<TextNode, Boolean>())
        for (summary in doc.select(SMS_SUMMARY_IN_DOCUMENT)) {
            for (textNode in summary.collectTextNodes()) {
                val whole = textNode.wholeText
                val core = whole.trim()
                if (core.isNotEmpty()) {
                    val start = whole.indexOfFirst { !it.isWhitespace() }
                    val end = whole.indexOfLast { !it.isWhitespace() } + 1
                    textNode.text(whole.substring(0, start) + buildSmsPlaceholder(core.length) + whole.substring(end))
                }
                replaced.add(textNode)
            }
        }
        return replaced
    }

    private fun applyTokenAndIdentityRules(
        doc: Document,
        uidMap: Map<String, String>,
        nameMap: Map<String, String>,
        skippedTextNodes: Set<TextNode>,
    ) {
        val avatarMap = buildMap {
            for ((uid, target) in uidMap) {
                val from = uidToAvatarPath(uid) ?: continue
                val to = uidToAvatarPath(target) ?: continue
                put(from, to)
            }
        }
        val uidRegex = uidMap.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            Regex("""(?<!\d)(${keys.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape)})(?!\d)""")
        }
        val rawNameRegex = nameMap.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            Regex(keys.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape))
        }
        val encodedNameMap = buildEncodedUsernameMap(nameMap)
        val encodedNameRegex = encodedNameMap.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            Regex(keys.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape), RegexOption.IGNORE_CASE)
        }

        fun transform(input: String, includeEncoded: Boolean): String {
            var out = sanitizeTokenString(input)
            if (avatarMap.isNotEmpty()) out = AVATAR_PATH_REGEX.replace(out) { avatarMap[it.groupValues[1]] ?: it.value }
            if (uidRegex != null) out = uidRegex.replace(out) { uidMap[it.value] ?: it.value }
            if (rawNameRegex != null) out = rawNameRegex.replace(out) { nameMap[it.value] ?: it.value }
            if (includeEncoded && encodedNameRegex != null) {
                out = encodedNameRegex.replace(out) { encodedNameMap[it.value.lowercase()] ?: it.value }
            }
            return out
        }

        doc.forEachNode { node ->
            when (node) {
                is Element -> sanitizeElementAttributes(node) { transform(it, includeEncoded = true) }
                is DataNode -> transform(node.wholeData, includeEncoded = true).takeIf { it != node.wholeData }?.let(node::setWholeData)
                is Comment -> transform(node.data, includeEncoded = true).takeIf { it != node.data }?.let(node::setData)
                is TextNode -> if (node !in skippedTextNodes) {
                    transform(node.wholeText, includeEncoded = false).takeIf { it != node.wholeText }?.let(node::text)
                }
            }
        }
    }

    private fun sanitizeElementAttributes(element: Element, transform: (String) -> String) {
        val tag = element.tagName().lowercase()
        val sensitiveInput = tag == "input" &&
            (element.attr("name").lowercase() in SENSITIVE_FIELDS || element.attr("id").lowercase() in SENSITIVE_FIELDS)
        val sensitiveMeta = tag == "meta" && element.attr("name").lowercase() in SENSITIVE_FIELDS

        for (attr in element.attributes().asList()) {
            val key = attr.key.lowercase()
            val value = attr.value
            if (value.isEmpty() || key == "class" || key == "id") continue
            val updated = when {
                (sensitiveInput && key == "value") || (sensitiveMeta && key == "content") -> maskSameLength(value)
                key in SENSITIVE_FIELDS || key.endsWith("-formhash") -> maskSameLength(value)
                else -> transform(value)
            }
            if (updated != value) attr.setValue(updated)
        }
    }

    private fun sanitizeTokenString(input: String): String {
        var out = DISCUZ_UID_REGEX.replace(input) { m ->
            val q = m.groupValues[2]
            if (q.isNotEmpty() || m.groupValues[3].all(Char::isDigit)) "${m.groupValues[1]}$q$PLACEHOLDER_UID$q" else m.value
        }
        out = URL_TOKEN_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=${maskSameLength(it.groupValues[3])}" }
        for (regex in JS_TOKEN_ASSIGN_REGEXES) {
            out = regex.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${maskSameLength(it.groupValues[3])}${it.groupValues[2]}" }
        }
        for (regex in JS_OBJECT_PROP_REGEXES) {
            out = regex.replace(out) { "${it.groupValues[1]}${it.groupValues[3]}${maskSameLength(it.groupValues[4])}${it.groupValues[3]}" }
        }
        return out
    }

    private fun normalizeUmenu(doc: Document) {
        for (link in doc.select(UMENU_USER_LINK)) {
            replaceLinkParams(link, PLACEHOLDER_UID, PLACEHOLDER_USERNAME)
            if (link.text().isNotBlank()) replaceElementTextPreservingChildren(link, PLACEHOLDER_USERNAME)
        }
        for (link in doc.select(UMENU_LINKS)) {
            if (link.attr("href").contains("space.php", ignoreCase = true)) {
                replaceLinkParams(link, PLACEHOLDER_UID, PLACEHOLDER_USERNAME)
            }
        }
    }

    private fun normalizeSmsPeerNodes(doc: Document) {
        for (item in doc.select(SMS_ITEMS)) {
            val cite = item.firstMatch(SMS_AUTHOR_CITE)
            val isSelf = cite?.text()?.trim() == PLACEHOLDER_USERNAME
            if (cite != null && !isSelf && cite.text().isNotBlank()) {
                val authorLink = cite.firstMatch("a")
                replaceElementTextPreservingChildren(authorLink ?: cite, PLACEHOLDER_PEER_USERNAME)
                authorLink?.let { replaceLinkParams(it, PLACEHOLDER_PEER_UID, preserveSelfUid = true) }
            }
            if (!isSelf) {
                item.firstMatch(SMS_AVATAR_LINK)?.let { replaceLinkParams(it, PLACEHOLDER_PEER_UID, preserveSelfUid = true) }
                item.firstMatch(SMS_AVATAR_IMG)?.let { img ->
                    val src = img.attr("src")
                    val updated = AVATAR_PATH_REGEX.replace(src) {
                        if (it.groupValues[1] == PLACEHOLDER_SELF_AVATAR_PATH) it.value else PLACEHOLDER_PEER_AVATAR_PATH
                    }
                    if (updated != src) img.attr("src", updated)
                }
            }
        }
    }

    private fun replaceLinkParams(link: Element, uid: String, username: String? = null, preserveSelfUid: Boolean = false) {
        val href = link.attr("href").ifEmpty { return }
        var updated = UID_QUERY_PARAM_REGEX.replace(href) { m ->
            if (preserveSelfUid && m.groupValues[3] == PLACEHOLDER_UID) m.value else "${m.groupValues[1]}${m.groupValues[2]}$uid"
        }
        if (username != null) {
            updated = USERNAME_QUERY_PARAM_REGEX.replace(updated) { "${it.groupValues[1]}${it.groupValues[2]}$username" }
        }
        if (updated != href) link.attr("href", updated)
    }

    private fun buildEncodedUsernameMap(nameMap: Map<String, String>): Map<String, String> {
        val charsets = listOfNotNull(StandardCharsets.UTF_8, runCatching { Charset.forName("GBK") }.getOrNull())
        return buildMap {
            for ((username, replacement) in nameMap) {
                for (charset in charsets) {
                    val encoded = URLEncoder.encode(username, charset)
                    for (variant in listOf(encoded, encoded.replace("+", "%20"))) {
                        if ('%' in variant && variant != username) putIfAbsent(variant.lowercase(), replacement)
                    }
                }
            }
        }
    }

    private fun extractUidParam(url: String): String? = UID_QUERY_PARAM_REGEX.find(url)?.groupValues?.get(3)
    private fun isReplaceableUid(uid: String): Boolean = uid.isNotEmpty() && uid != PLACEHOLDER_UID && !isAllZeroMask(uid)

    private fun replaceElementTextPreservingChildren(element: Element, replacement: String) {
        val targets = element.textNodes().filter { it.wholeText.isNotBlank() }
            .ifEmpty { element.collectTextNodes().filter { it.wholeText.isNotBlank() } }
        if (targets.isNotEmpty()) {
            targets.first().text(replacement)
            targets.drop(1).forEach { it.text("") }
        } else if (element.children().isEmpty()) {
            element.text(replacement)
        }
    }
}
