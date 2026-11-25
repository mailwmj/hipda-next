package net.jejer.hipda.parser.sanitize

import net.jejer.hipda.parser.sanitize.SanitizeSelectors.ATTACHMENT_AID_URL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.AUTHOR_IDENTITY_ELEMENTS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.AVATAR_PATH_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.BLACKLIST_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.COOKIE_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.DISCUZ_UID_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.EMAIL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.EMBEDDED_INPUT_TAG_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.INPUT_NAME_OR_ID_ATTR_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.INPUT_VALUE_ATTR_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.IPV4_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.IPV6_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_FORMHASH_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_OBJECT_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JS_OTHER_TOKEN_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.JUMP_FLOOR_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.NOTIFY_REPLY_PHRASE_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.NOTIFY_SENSITIVE_ITEMS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_BLOCKED_PREFIX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_EMAIL
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_IPV4
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_IPV6
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_PEER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_SELF_AVATAR_PATH
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_SMS_IMAGE_URL
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_SMS_URL
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_UID
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.PLACEHOLDER_USERNAME
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.POST_AUTHOR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.POST_CONTAINER
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.POST_SIGNATURE
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SCRIPT_UID_ASSIGN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SCRIPT_UID_CALL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SCRIPT_UID_PROP_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_CITE
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AUTHOR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_IMG
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_AVATAR_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_ITEMS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_MSGTO_INPUT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_PM_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_POST_FORM
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_SUMMARY_IN_DOCUMENT
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.SMS_TITLE_PEER_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.STATS_LABEL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.TEXT_UID_LABEL_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UID_INPUT_NAMES
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UID_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UMENU_LINKS
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.UMENU_USER_LINK
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.URL_TOKEN_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.URL_USERNAME_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.USERNAME_QUERY_PARAM_REGEX
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.avatarPathToUid
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.buildSmsPlaceholder
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.buildStandaloneUidRegex
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.collectTextNodes
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.firstMatch
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.forEachNode
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isAllZeroMask
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isReplaceableUid
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeEmail
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeIpv4
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeIpv6
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeSmsHref
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSafeSmsImgSrc
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isSensitiveFieldName
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.isXmlDocument
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.maskSameLength
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.sanitizeAttachmentAid
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.uidToAvatarPath
import net.jejer.hipda.parser.sanitize.SanitizeSelectors.urlDecodeCandidates
import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 纯 JVM 的 Discuz!/HiPDA HTML 与 AJAX XML 样本脱敏器。
 * 仅替换文本、属性值、脚本、注释与 XML CDATA 中的敏感信息，不删除节点、不修改 class/id。
 */
object HtmlSanitizer {

    private val URL_ATTRIBUTES = setOf("href", "src", "action", "file", "poster", "data-src", "background", "cite")
    private val USERNAME_INPUT_NAMES = setOf("author", "username", "srchuname", "msgto", "user", "noticeauthor")
    private val FORUM_ACTION_URL_KEYWORDS = listOf(
        "post.php", "logging.php", "forumdisplay.php", "viewthread.php",
        "memcp.php", "notice.php", "pm.php", "misc.php", "search.php", "my.php",
    )

    private val SCRIPT_USERNAME_ASSIGN_REGEX =
        Regex("""(?i)((?<![.\w$])(?:currentUser|discuz_user|username|user_?name|author)\s*=\s*)(['"])([^'"]+)\2""")
    private val SCRIPT_USERNAME_PROP_REGEX =
        Regex("""(?i)((?<![.\w$])(['"]?)(?:currentUser|discuz_user|username|user_?name|author)\2\s*:\s*)(['"])([^'"]+)\3""")
    private val JS_STRING_LITERAL_REGEX = Regex("""(['"])((?:\\.|(?!\1)[^\\])*)\1""")

    fun sanitize(html: String, page: PageType, identity: Identity): String {
        if (isXmlDocument(html)) {
            return sanitizeXml(html, page, identity)
        }
        val doc = Jsoup.parse(html).apply { outputSettings().prettyPrint(false) }
        sanitizeDocument(doc, page, identity)
        return doc.outerHtml()
    }

    private fun sanitizeXml(xml: String, page: PageType, identity: Identity): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser()).apply {
            outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml)
        }
        val cdataNodes = Collections.newSetFromMap(IdentityHashMap<TextNode, Boolean>())
        doc.forEachNode { node ->
            if (node is CDataNode) {
                val fragment = Jsoup.parseBodyFragment(node.text()).apply { outputSettings().prettyPrint(false) }
                sanitizeDocument(fragment, page, identity)
                node.text(fragment.body().html())
                cdataNodes.add(node)
            }
        }
        sanitizeDocument(doc, page, identity, preSkippedTextNodes = cdataNodes)
        return doc.outerHtml()
    }

    private fun sanitizeDocument(
        doc: Document,
        page: PageType,
        identity: Identity,
        preSkippedTextNodes: Set<TextNode> = emptySet(),
    ) {
        val (selfUids, selfNames) = collectSelfIdentities(doc, identity)
        val (peerUids, peerNames) = if (page.isSms) collectPeerIdentities(doc, selfUids, selfNames) else emptySet<String>() to emptySet()

        val uidMap = buildMap {
            selfUids.forEach { put(it, PLACEHOLDER_UID) }
            peerUids.forEach { putIfAbsent(it, PLACEHOLDER_PEER_UID) }
        }
        val nameMap = buildMap {
            selfNames.forEach { put(it.lowercase(), PLACEHOLDER_USERNAME) }
            peerNames.forEach { putIfAbsent(it.lowercase(), PLACEHOLDER_PEER_USERNAME) }
        }
        val ctx = buildReplaceContext(selfUids, uidMap, nameMap)

        // 先按上下文脱敏结构化私有区域，记录已替换的 TextNode 避免后续重复改写
        val skippedTextNodes = Collections.newSetFromMap(IdentityHashMap<TextNode, Boolean>())
        skippedTextNodes.addAll(preSkippedTextNodes)
        if (page.isSms) {
            sanitizeSmsSummaries(doc, uidMap, skippedTextNodes)
        }
        sanitizeSelfSignatures(doc, selfUids, selfNames, skippedTextNodes)
        if (page == PageType.NOTIFY) {
            sanitizeNotifySensitiveItems(doc, skippedTextNodes)
        }
        if (page == PageType.BLACKLIST) {
            sanitizeBlacklist(doc, skippedTextNodes)
        }

        // 先按作者/身份选择器精确替换节点，保护短/常见用户名不依赖自由文本匹配
        sanitizeKnownIdentityElements(doc, ctx, skippedTextNodes)

        // 遍历全树：令牌/Cookie/aid/IP/邮箱 + 语境限定 UID + 有边界用户名
        doc.forEachNode { node ->
            when (node) {
                is Element -> sanitizeElementAttributes(node, ctx)
                is DataNode -> transformText(node.wholeData, ctx, inScript = true)
                    .takeIf { it != node.wholeData }?.let(node::setWholeData)
                is Comment -> transformText(node.data, ctx, inScript = false)
                    .takeIf { it != node.data }?.let(node::setData)
                is TextNode -> if (node !in skippedTextNodes) {
                    sanitizeFreeTextNode(node, ctx)
                }
            }
        }

        normalizeUmenu(doc)
        if (page.isSms) normalizeSmsPeerNodes(doc)
    }

    private fun collectSelfIdentities(doc: Document, identity: Identity): Pair<Set<String>, Set<String>> {
        val uids = linkedSetOf<String>()
        val names = linkedSetOf<String>()
        identity.uid.trim().takeIf(::isReplaceableUid)?.let(uids::add)
        identity.username.trim().takeIf { it.isNotEmpty() && !it.equals(PLACEHOLDER_USERNAME, ignoreCase = true) }?.let(names::add)
        for (link in doc.select(UMENU_USER_LINK)) {
            extractUidParam(link.attr("href"))?.takeIf(::isReplaceableUid)?.let(uids::add)
            link.text().trim().takeIf { it.isNotEmpty() && !it.equals(PLACEHOLDER_USERNAME, ignoreCase = true) }?.let(names::add)
        }
        doc.forEachNode { node ->
            if (node is DataNode) {
                for (m in DISCUZ_UID_REGEX.findAll(node.wholeData)) {
                    m.groupValues[4].trim().takeIf(::isReplaceableUid)?.let(uids::add)
                }
            }
        }
        return uids to names
    }

    private fun collectPeerIdentities(doc: Document, selfUids: Set<String>, selfNames: Set<String>): Pair<Set<String>, Set<String>> {
        val selfLower = selfNames.map { it.lowercase() }.toSet()
        val peerUids = linkedSetOf<String>()
        val peerNames = linkedSetOf<String>()

        fun addUid(v: String?) = v?.trim()?.takeIf {
            isReplaceableUid(it) && it !in selfUids && it != PLACEHOLDER_PEER_UID
        }?.let(peerUids::add)

        fun addName(v: String?) = v?.trim()?.takeIf {
            val lower = it.lowercase()
            it.isNotEmpty() && lower !in selfLower &&
                !it.equals(PLACEHOLDER_USERNAME, ignoreCase = true) &&
                !it.equals(PLACEHOLDER_PEER_USERNAME, ignoreCase = true)
        }?.let(peerNames::add)

        // 第一轮：收集全部对方 uid（消息项、发送表单、pm.php 链接）
        for (item in doc.select(SMS_ITEMS)) {
            item.firstMatch(SMS_AUTHOR_LINK)?.let { addUid(extractUidParam(it.attr("href"))) }
            item.firstMatch(SMS_AVATAR_LINK)?.let { addUid(extractUidParam(it.attr("href"))) }
            item.firstMatch(SMS_AVATAR_IMG)?.let { img ->
                AVATAR_PATH_REGEX.find(img.attr("src"))?.let { addUid(avatarPathToUid(it.groupValues[1])) }
            }
        }
        doc.select(SMS_POST_FORM).forEach { addUid(extractUidParam(it.attr("action"))) }
        doc.select(SMS_PM_LINKS).forEach { addUid(extractUidParam(it.attr("href"))) }

        // 第二轮：收集对方用户名（消息项、msgto、所有 uid in peerUids 的 space.php 链接、页面标题）
        for (item in doc.select(SMS_ITEMS)) {
            item.firstMatch(SMS_AUTHOR_CITE)?.let { addName(it.text()) }
        }
        doc.select(SMS_MSGTO_INPUT).forEach { addName(it.attr("value")) }
        for (link in doc.select("a[href*=space.php]")) {
            val uid = extractUidParam(link.attr("href"))
            if (uid != null && uid in peerUids) addName(link.text())
        }
        SMS_TITLE_PEER_REGEX.find(doc.title())?.let { addName(it.groupValues[1]) }
        doc.select("div.itemtitle h1, div.itemtitle h2").forEach { heading ->
            SMS_TITLE_PEER_REGEX.find(heading.text())?.let { addName(it.groupValues[1]) }
        }
        return peerUids to peerNames
    }

    private fun sanitizeSmsSummaries(doc: Document, uidMap: Map<String, String>, skippedTextNodes: MutableSet<TextNode>) {
        for (summary in doc.select(SMS_SUMMARY_IN_DOCUMENT)) {
            for (textNode in summary.collectTextNodes()) {
                replaceTextNodeWithPlaceholder(textNode)
                skippedTextNodes.add(textNode)
            }
            for (el in summary.allElements) {
                if (el === summary) continue
                val tag = el.tagName().lowercase()
                if (tag == "a" && el.hasAttr("href")) {
                    val href = replaceUrlUidParams(el.attr("href"), uidMap)
                    el.attr("href", if (isSafeSmsHref(href)) href else PLACEHOLDER_SMS_URL)
                }
                if (tag == "img") {
                    for (imgAttr in listOf("src", "file", "data-src")) {
                        val src = el.attr(imgAttr).trim()
                        if (src.isNotEmpty() && !isSafeSmsImgSrc(src)) {
                            el.attr(imgAttr, PLACEHOLDER_SMS_IMAGE_URL)
                        }
                    }
                }
                for (attrName in listOf("title", "alt")) {
                    val raw = el.attr(attrName)
                    if (raw.isNotBlank()) el.attr(attrName, replacePreservingOuterWhitespace(raw))
                }
            }
        }
    }

    private fun sanitizeSelfSignatures(
        doc: Document,
        selfUids: Set<String>,
        selfNames: Set<String>,
        skippedTextNodes: MutableSet<TextNode>,
    ) {
        val selfLower = selfNames.map { it.lowercase() }.toSet()
        for (post in doc.select(POST_CONTAINER)) {
            val authorLink = post.firstMatch(POST_AUTHOR_LINK) ?: continue
            val uid = extractUidParam(authorLink.attr("href"))
            val author = authorLink.text().trim().lowercase()
            val isSelf = (uid != null && (uid in selfUids || uid == PLACEHOLDER_UID)) ||
                author in selfLower || author == PLACEHOLDER_USERNAME.lowercase()
            if (!isSelf) continue
            for (sig in post.select(POST_SIGNATURE)) {
                for (textNode in sig.collectTextNodes()) {
                    replaceTextNodeWithPlaceholder(textNode)
                    skippedTextNodes.add(textNode)
                }
            }
        }
    }

    private fun sanitizeNotifySensitiveItems(doc: Document, skippedTextNodes: MutableSet<TextNode>) {
        for (item in doc.select(NOTIFY_SENSITIVE_ITEMS)) {
            for (link in item.select("a[href*=space.php]")) {
                replaceLinkParams(link, PLACEHOLDER_PEER_UID, preserveSelfUid = true)
                if (link.text().isNotBlank() && !link.text().trim().equals(PLACEHOLDER_USERNAME, ignoreCase = true)) {
                    replaceElementTextPreservingChildren(link, PLACEHOLDER_PEER_USERNAME)
                }
                skippedTextNodes.addAll(link.collectTextNodes())
            }
            for (textNode in item.collectTextNodes()) {
                if (textNode in skippedTextNodes) continue
                val parentTag = textNode.parent()?.tagName()?.lowercase()
                if (parentTag == "em" || parentTag == "a") continue
                replaceTextNodeWithPlaceholder(textNode)
                skippedTextNodes.add(textNode)
            }
        }
    }

    private fun sanitizeBlacklist(doc: Document, skippedTextNodes: MutableSet<TextNode>) {
        val blockedMap = linkedMapOf<String, String>()
        var nextIndex = 1
        for (link in doc.select(BLACKLIST_LINKS)) {
            val href = link.attr("href")
            val rawParam = USERNAME_QUERY_PARAM_REGEX.find(href)?.groupValues?.get(3)
            val key = rawParam?.let { urlDecodeCandidates(it).lastOrNull() }?.ifBlank { null }
                ?: link.text().trim().ifBlank { null }
                ?: continue
            val placeholder = blockedMap.getOrPut(key.lowercase()) { "$PLACEHOLDER_BLOCKED_PREFIX${nextIndex++}" }
            var updatedHref = USERNAME_QUERY_PARAM_REGEX.replace(href) { "${it.groupValues[1]}${it.groupValues[2]}$placeholder" }
            updatedHref = UID_QUERY_PARAM_REGEX.replace(updatedHref) { "${it.groupValues[1]}${it.groupValues[2]}$PLACEHOLDER_PEER_UID" }
            if (updatedHref != href) link.attr("href", updatedHref)
            if (link.text().isNotBlank()) replaceElementTextPreservingChildren(link, placeholder)
            skippedTextNodes.addAll(link.collectTextNodes())
        }
    }

    private fun sanitizeKnownIdentityElements(doc: Document, ctx: ReplaceContext, skippedTextNodes: MutableSet<TextNode>) {
        if (ctx.nameMap.isEmpty()) return
        for (el in doc.select(AUTHOR_IDENTITY_ELEMENTS)) {
            val text = el.text().trim()
            val replacement = ctx.nameMap[text.lowercase()] ?: continue
            replaceElementTextPreservingChildren(el, replacement)
            skippedTextNodes.addAll(el.collectTextNodes())
        }
        // 跳楼链接 <strong><a href="redirect.php?...">回复 N# Author</a></strong>：只替换 # 后的作者
        for (link in doc.select("strong a[href*=redirect.php]")) {
            val match = JUMP_FLOOR_REGEX.matchEntire(link.text().trim()) ?: continue
            val author = match.groupValues[2].trim()
            val replacement = ctx.nameMap[author.lowercase()]
            if (replacement != null) {
                replaceElementTextPreservingChildren(link, "${replaceStandaloneSelfUids(match.groupValues[1], ctx)}$replacement")
            } else {
                val scrubbed = replaceStandaloneSelfUids(link.text(), ctx)
                if (scrubbed != link.text()) replaceElementTextPreservingChildren(link, scrubbed)
            }
            skippedTextNodes.addAll(link.collectTextNodes())
        }
    }

    private fun sanitizeFreeTextNode(node: TextNode, ctx: ReplaceContext) {
        val whole = node.wholeText
        if (whole.isBlank()) return
        val parent = node.parent()
        if (parent != null && isForumActionLink(parent)) {
            val updated = sanitizeProtectedText(whole, ctx)
            if (updated != whole) node.text(updated)
            return
        }
        if (STATS_LABEL_REGEX.matches(whole.trim())) {
            val updated = sanitizeProtectedText(whole, ctx)
            if (updated != whole) node.text(updated)
            return
        }
        val updated = if (NOTIFY_REPLY_PHRASE_REGEX.containsMatchIn(whole)) {
            transformPreservingMatches(whole, NOTIFY_REPLY_PHRASE_REGEX) { segment ->
                transformText(segment, ctx, inScript = false)
            }
        } else {
            transformText(whole, ctx, inScript = false)
        }
        if (updated != whole) node.text(updated)
    }

    private fun sanitizeProtectedText(input: String, ctx: ReplaceContext): String {
        var out = sanitizeTokenAndPersonalInfo(input)
        if (ctx.uidMap.isNotEmpty()) {
            out = TEXT_UID_LABEL_REGEX.replace(out) { m ->
                ctx.uidMap[m.groupValues[2]]?.let { "${m.groupValues[1]}$it" } ?: m.value
            }
        }
        return replaceStandaloneSelfUids(out, ctx)
    }

    private fun transformPreservingMatches(input: String, protectedRegex: Regex, transform: (String) -> String): String =
        buildString(input.length) {
            var cursor = 0
            for (match in protectedRegex.findAll(input)) {
                if (match.range.first > cursor) {
                    append(transform(input.substring(cursor, match.range.first)))
                }
                append(match.value)
                cursor = match.range.last + 1
            }
            if (cursor < input.length) {
                append(transform(input.substring(cursor)))
            }
        }

    private fun isForumActionLink(element: Element): Boolean {
        if (!element.tagName().equals("a", ignoreCase = true)) return false
        val href = element.attr("href").lowercase()
        if (href.contains("space.php")) return false
        return FORUM_ACTION_URL_KEYWORDS.any { href.contains(it) }
    }

    private fun sanitizeElementAttributes(element: Element, ctx: ReplaceContext) {
        val tag = element.tagName().lowercase()
        val fieldName = element.attr("name").lowercase()
        val fieldId = element.attr("id").lowercase()
        val sensitiveInput = tag == "input" && (isSensitiveFieldName(fieldName) || isSensitiveFieldName(fieldId))
        val sensitiveMeta = tag == "meta" && isSensitiveFieldName(fieldName)

        for (attr in element.attributes().asList()) {
            val key = attr.key.lowercase()
            val value = attr.value
            if (value.isEmpty() || key == "class" || key == "id") continue

            val updated = when {
                (sensitiveInput && key == "value") || (sensitiveMeta && key == "content") -> maskSameLength(value)
                isSensitiveFieldName(key) || key.endsWith("-formhash") -> maskSameLength(value)
                tag == "input" && key == "value" && fieldName in UID_INPUT_NAMES ->
                    ctx.uidMap[value.trim()] ?: replaceStandaloneSelfUids(value, ctx)
                tag == "input" && key == "value" && fieldName in USERNAME_INPUT_NAMES ->
                    matchUsernameValue(value, ctx) ?: replaceStandaloneSelfUids(value, ctx)
                key == "data-uid" -> ctx.uidMap[value.trim()] ?: replaceStandaloneSelfUids(value, ctx)
                key in URL_ATTRIBUTES -> transformUrlAttribute(value, ctx)
                key == "alt" -> ctx.nameMap[value.trim().lowercase()] ?: transformText(value, ctx, inScript = false)
                else -> transformText(value, ctx, inScript = key.startsWith("on"))
            }
            if (updated != value) attr.setValue(updated)
        }
    }

    private fun transformUrlAttribute(value: String, ctx: ReplaceContext): String {
        var out = sanitizeTokenAndPersonalInfo(value)
        if (ctx.avatarMap.isNotEmpty()) {
            out = AVATAR_PATH_REGEX.replace(out) { ctx.avatarMap[it.groupValues[1]] ?: it.value }
        }
        out = replaceUrlUidParams(out, ctx.uidMap)
        out = replaceUrlUsernameParams(out, ctx)
        out = replaceStandaloneSelfUids(out, ctx)
        return out
    }

    private fun transformText(input: String, ctx: ReplaceContext, inScript: Boolean): String {
        var out = sanitizeTokenAndPersonalInfo(input)
        if (ctx.avatarMap.isNotEmpty()) {
            out = AVATAR_PATH_REGEX.replace(out) { ctx.avatarMap[it.groupValues[1]] ?: it.value }
        }
        out = replaceUrlUidParams(out, ctx.uidMap)
        out = replaceUrlUsernameParams(out, ctx)
        if (ctx.uidMap.isNotEmpty()) {
            out = TEXT_UID_LABEL_REGEX.replace(out) { m ->
                ctx.uidMap[m.groupValues[2]]?.let { "${m.groupValues[1]}$it" } ?: m.value
            }
            if (inScript) {
                out = SCRIPT_UID_ASSIGN_REGEX.replace(out) { m ->
                    ctx.uidMap[m.groupValues[3]]?.let { "${m.groupValues[1]}${m.groupValues[2]}$it${m.groupValues[2]}" } ?: m.value
                }
                out = SCRIPT_UID_PROP_REGEX.replace(out) { m ->
                    ctx.uidMap[m.groupValues[4]]?.let { "${m.groupValues[1]}${m.groupValues[3]}$it${m.groupValues[3]}" } ?: m.value
                }
                out = SCRIPT_UID_CALL_REGEX.replace(out) { m ->
                    ctx.uidMap[m.groupValues[3]]?.let { "${m.groupValues[1]}${m.groupValues[2]}$it${m.groupValues[2]}" } ?: m.value
                }
            }
        }
        out = replaceStandaloneSelfUids(out, ctx)
        if (inScript && ctx.nameMap.isNotEmpty()) {
            out = SCRIPT_USERNAME_ASSIGN_REGEX.replace(out) { m ->
                matchUsernameValue(m.groupValues[3], ctx)?.let { "${m.groupValues[1]}${m.groupValues[2]}$it${m.groupValues[2]}" } ?: m.value
            }
            out = SCRIPT_USERNAME_PROP_REGEX.replace(out) { m ->
                matchUsernameValue(m.groupValues[4], ctx)?.let { "${m.groupValues[1]}${m.groupValues[3]}$it${m.groupValues[3]}" } ?: m.value
            }
        }
        if (inScript) {
            if (ctx.scriptSafeNameRegex != null || ctx.encodedNameRegex != null) {
                out = JS_STRING_LITERAL_REGEX.replace(out) { m ->
                    val quote = m.groupValues[1]
                    var inner = m.groupValues[2]
                    if (ctx.scriptSafeNameRegex != null) {
                        inner = ctx.scriptSafeNameRegex.replace(inner) { ctx.nameMap[it.value.lowercase()] ?: it.value }
                    }
                    if (ctx.encodedNameRegex != null) {
                        inner = ctx.encodedNameRegex.replace(inner) { ctx.encodedNameMap[it.value.lowercase()] ?: it.value }
                    }
                    "$quote$inner$quote"
                }
            }
        } else {
            if (ctx.boundedNameRegex != null) {
                out = ctx.boundedNameRegex.replace(out) { ctx.nameMap[it.value.lowercase()] ?: it.value }
            }
            if (ctx.encodedNameRegex != null) {
                out = ctx.encodedNameRegex.replace(out) { ctx.encodedNameMap[it.value.lowercase()] ?: it.value }
            }
        }
        return out
    }

    private fun sanitizeTokenAndPersonalInfo(input: String): String {
        var out = EMBEDDED_INPUT_TAG_REGEX.replace(input) { tagMatch -> sanitizeEmbeddedInputTag(tagMatch.value) }
        out = ATTACHMENT_AID_URL_REGEX.replace(out) { "${it.groupValues[1]}${sanitizeAttachmentAid(it.groupValues[2])}" }
        out = DISCUZ_UID_REGEX.replace(out) { m ->
            val q = m.groupValues[3]
            val v = m.groupValues[4]
            if (q.isNotEmpty() || v.all(Char::isDigit)) "${m.groupValues[1]}$q$PLACEHOLDER_UID$q" else m.value
        }
        out = URL_TOKEN_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=${maskSameLength(it.groupValues[3])}" }
        out = JS_FORMHASH_ASSIGN_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[3]}${maskSameLength(it.groupValues[4])}${it.groupValues[3]}" }
        out = JS_OTHER_TOKEN_ASSIGN_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[3]}${maskSameLength(it.groupValues[4])}${it.groupValues[3]}" }
        out = JS_OBJECT_PROP_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[4]}${maskSameLength(it.groupValues[5])}${it.groupValues[4]}" }
        out = COOKIE_ASSIGN_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${maskSameLength(it.groupValues[3])}${it.groupValues[2]}" }
        out = COOKIE_OBJECT_PROP_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[3]}${maskSameLength(it.groupValues[4])}${it.groupValues[3]}" }
        out = EMAIL_REGEX.replace(out) { if (isSafeEmail(it.groupValues[1])) it.value else PLACEHOLDER_EMAIL }
        out = IPV4_REGEX.replace(out) { m ->
            if (isSafeIpv4(m.groupValues[1])) m.value else if (m.groupValues[2].isNotEmpty()) "$PLACEHOLDER_IPV4:0" else PLACEHOLDER_IPV4
        }
        out = IPV6_REGEX.replace(out) { if (isSafeIpv6(it.groupValues[1])) it.value else PLACEHOLDER_IPV6 }
        return out
    }

    private fun sanitizeEmbeddedInputTag(tagHtml: String): String {
        val isSensitive = INPUT_NAME_OR_ID_ATTR_REGEX.findAll(tagHtml).any { isSensitiveFieldName(it.groupValues[2]) }
        if (!isSensitive) return tagHtml
        return INPUT_VALUE_ATTR_REGEX.replace(tagHtml) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}${maskSameLength(m.groupValues[3])}${m.groupValues[2]}"
        }
    }

    private fun replaceUrlUidParams(input: String, uidMap: Map<String, String>): String {
        if (uidMap.isEmpty()) return input
        return UID_QUERY_PARAM_REGEX.replace(input) { m ->
            uidMap[m.groupValues[3]]?.let { "${m.groupValues[1]}${m.groupValues[2]}$it" } ?: m.value
        }
    }

    private fun replaceUrlUsernameParams(input: String, ctx: ReplaceContext): String {
        if (ctx.nameMap.isEmpty()) return input
        return URL_USERNAME_PARAM_REGEX.replace(input) { m ->
            matchUsernameValue(m.groupValues[3], ctx)?.let { "${m.groupValues[1]}${m.groupValues[2]}$it" } ?: m.value
        }
    }

    private fun matchUsernameValue(rawValue: String, ctx: ReplaceContext): String? {
        for (candidate in urlDecodeCandidates(rawValue)) {
            ctx.nameMap[candidate.trim().lowercase()]?.let { return it }
        }
        return null
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
            val isSelf = cite?.text()?.trim()?.equals(PLACEHOLDER_USERNAME, ignoreCase = true) == true
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
        for (form in doc.select(SMS_POST_FORM)) {
            val action = form.attr("action")
            val updated = UID_QUERY_PARAM_REGEX.replace(action) { m ->
                if (m.groupValues[3] == PLACEHOLDER_UID) m.value else "${m.groupValues[1]}${m.groupValues[2]}$PLACEHOLDER_PEER_UID"
            }
            if (updated != action) form.attr("action", updated)
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

    private fun replaceStandaloneSelfUids(input: String, ctx: ReplaceContext): String =
        ctx.standaloneSelfUidRegex?.replace(input, PLACEHOLDER_UID) ?: input

    private fun buildReplaceContext(
        selfUids: Set<String>,
        uidMap: Map<String, String>,
        nameMap: Map<String, String>,
    ): ReplaceContext {
        val standaloneSelfUidRegex = buildStandaloneUidRegex(selfUids)
        val avatarMap = buildMap {
            for ((uid, target) in uidMap) {
                val from = uidToAvatarPath(uid) ?: continue
                val to = uidToAvatarPath(target) ?: continue
                put(from, to)
            }
        }
        val sortedNames = nameMap.keys.sortedByDescending { it.length }
        val boundedNameRegex = sortedNames.takeIf { it.isNotEmpty() }?.let { names ->
            Regex(names.joinToString("|", transform = ::buildBoundedNamePattern), RegexOption.IGNORE_CASE)
        }
        val scriptSafeNames = sortedNames.filterNot { isPlaceholderCollidingName(it) }
        val scriptSafeNameRegex = scriptSafeNames.takeIf { it.isNotEmpty() }?.let { names ->
            Regex(names.joinToString("|", transform = ::buildBoundedNamePattern), RegexOption.IGNORE_CASE)
        }
        val encodedNameMap = buildEncodedUsernameMap(nameMap)
        val encodedNameRegex = encodedNameMap.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            val pattern = keys.sortedByDescending { it.length }.joinToString("|") {
                """(?<![A-Za-z0-9_%])${Regex.escape(it)}(?![A-Za-z0-9_%])"""
            }
            Regex(pattern, RegexOption.IGNORE_CASE)
        }
        return ReplaceContext(
            uidMap,
            standaloneSelfUidRegex,
            avatarMap,
            nameMap,
            boundedNameRegex,
            scriptSafeNameRegex,
            encodedNameMap,
            encodedNameRegex,
        )
    }

    private fun isPlaceholderCollidingName(name: String): Boolean =
        isAllZeroMask(name) || name == PLACEHOLDER_UID || name == PLACEHOLDER_PEER_UID

    private fun buildBoundedNamePattern(name: String): String {
        val escaped = Regex.escape(name)
        val left = if (isAsciiWord(name.first())) """(?<![A-Za-z0-9_])""" else ""
        val right = if (isAsciiWord(name.last())) {
            """(?![A-Za-z0-9_]|\.(?:php|html?|js|css|gif|png|jpe?g|ico|xml|svg)\b)"""
        } else {
            ""
        }
        return "$left$escaped$right"
    }

    private fun buildEncodedUsernameMap(nameMap: Map<String, String>): Map<String, String> {
        val charsets = listOfNotNull(StandardCharsets.UTF_8, runCatching { Charset.forName("GB18030") }.getOrNull())
        return buildMap {
            for ((username, replacement) in nameMap) {
                for (charset in charsets) {
                    val encoded = URLEncoder.encode(username, charset)
                    val variants = linkedSetOf(
                        encoded,
                        encoded.replace("+", "%20"),
                        encoded.replace("%7E", "~"),
                        encoded.replace("%7E", "~").replace("+", "%20"),
                        encoded.replace("*", "%2A"),
                        encoded.replace("*", "%2A").replace("+", "%20"),
                        encoded.replace("%7E", "~").replace("*", "%2A"),
                        encoded.replace("%7E", "~").replace("*", "%2A").replace("+", "%20"),
                    )
                    for (variant in variants) {
                        if (!variant.equals(username, ignoreCase = true)) {
                            putIfAbsent(variant.lowercase(), replacement)
                        }
                    }
                }
            }
        }
    }

    private fun isAsciiWord(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun extractUidParam(url: String): String? = UID_QUERY_PARAM_REGEX.find(url)?.groupValues?.get(3)

    private fun replaceTextNodeWithPlaceholder(textNode: TextNode) {
        val whole = textNode.wholeText
        if (whole.isNotBlank()) textNode.text(replacePreservingOuterWhitespace(whole))
    }

    private fun replacePreservingOuterWhitespace(raw: String): String {
        val core = raw.trim()
        if (core.isEmpty()) return raw
        val start = raw.indexOfFirst { !it.isWhitespace() }
        val end = raw.indexOfLast { !it.isWhitespace() } + 1
        return raw.substring(0, start) + buildSmsPlaceholder(core.length) + raw.substring(end)
    }

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

    private data class ReplaceContext(
        val uidMap: Map<String, String>,
        val standaloneSelfUidRegex: Regex?,
        val avatarMap: Map<String, String>,
        val nameMap: Map<String, String>,
        val boundedNameRegex: Regex?,
        val scriptSafeNameRegex: Regex?,
        val encodedNameMap: Map<String, String>,
        val encodedNameRegex: Regex?,
    )
}
