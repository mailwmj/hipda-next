package net.jejer.hipda.parser.sanitize

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 脱敏与泄漏扫描共用的占位常量、旧解析器选择器（附出处）与基础正则/编解码工具。
 */
object SanitizeSelectors {
    const val PLACEHOLDER_UID = "10000"
    const val PLACEHOLDER_USERNAME = "HiUser"
    const val PLACEHOLDER_PEER_UID = "20000"
    const val PLACEHOLDER_PEER_USERNAME = "HiPeer"
    const val PLACEHOLDER_BLOCKED_PREFIX = "HiBlocked"
    const val PLACEHOLDER_SMS_UNIT = "测试内容"
    const val PLACEHOLDER_TOKEN_CHAR = '0'
    const val PLACEHOLDER_SELF_AVATAR_PATH = "000/01/00/00"
    const val PLACEHOLDER_PEER_AVATAR_PATH = "000/02/00/00"
    const val PLACEHOLDER_SMS_URL = "https://example.invalid/"
    const val PLACEHOLDER_SMS_IMAGE_URL = "https://example.invalid/placeholder.png"
    const val PLACEHOLDER_EMAIL = "user@example.com"
    const val PLACEHOLDER_IPV4 = "127.0.0.1"
    const val PLACEHOLDER_IPV6 = "::1"
    internal const val MIN_STANDALONE_SELF_UID_LENGTH = 4

    // 出处：LoginHelper.java:145；HiParserThreadList.java:30；HiParser.java:460,754
    const val UMENU = "div#umenu"
    const val UMENU_USER_LINK = "#umenu cite a"
    const val UMENU_LINKS = "div#umenu a"

    // 出处：HiParser.java:790；LoginHelper.java:85；PrePostAsyncTask.java:89,117；HiParserThreadDetail.java:210；PostSmsAsyncTask.java:53；UploadImgHelper.java:83
    const val FORMHASH_INPUT = "input[name=formhash], input#formhash, input[name=m_formhash]"
    const val SENSITIVE_TOKEN_INPUT =
        "input[name=hash], input#hash, input[name=sid], input#sid, input[name=auth], input#auth, " +
            "input[name=seccode], input#seccode, input[name=seccodeverify], input#seccodeverify, input[name=seccodehash], input#seccodehash"

    // 出处：HiParser.java:217,223,229,231,238,242,247,266 (parseSMS) 与 468,479,483,490,505 (parseSmsDetail)；PostSmsAsyncTask.java:92
    // 收窄 li.s_clear 至 ul.pm_list 内或含 p.cite，避免误伤非短消息页的 .s_clear .summary
    const val SMS_LIST_ITEMS = "ul.pm_list li"
    const val SMS_DETAIL_ITEMS = "ul.pm_list li.s_clear, li.s_clear:has(p.cite)"
    const val SMS_ITEMS = "ul.pm_list li, li.s_clear:has(p.cite)"
    const val SMS_AUTHOR_CITE = "p.cite cite"
    const val SMS_AUTHOR_LINK = "p.cite cite a"
    const val SMS_AVATAR_LINK = "a.avatar"
    const val SMS_AVATAR_IMG = "a.avatar img"
    const val SMS_SUMMARY = "div.summary"
    const val SMS_SUMMARY_IN_DOCUMENT = "ul.pm_list li div.summary, li.s_clear:has(p.cite) div.summary"

    // 出处：HiUtils.java:142-143；PostSmsAsyncTask.java:72-84
    const val SMS_POST_FORM = "form[action*=pm.php]"
    const val SMS_PM_LINKS = "a[href*=pm.php]"
    const val SMS_MSGTO_INPUT = "input[name=msgto]"
    const val SMS_PEER_HEADER_LINKS = "div.itemtitle a[href*=space.php]"

    // 出处：HiParser.java:564,649,734；HiParserThreadList.java:113,165；HiParserThreadDetail.java:153,182
    const val AUTHOR_IDENTITY_ELEMENTS =
        "div#profilecontent div.itemtitle h1, td.author cite a, td.lastpost cite, td.postauthor div.postinfo a, a[href*=space.php]"
    const val POST_CONTAINER = "div#postlist > div[id^=post_]"
    const val POST_AUTHOR_LINK = "td.postauthor div.postinfo a"
    const val POST_SIGNATURE = "td.postbottom > div.signatures"

    // 出处：HiParser.java:307-313,323-365 (parseNotify) 与 806-818 (parseBlacklist)
    const val NOTIFY_SENSITIVE_ITEMS = "ul.feed li div.f_buddy, ul.feed li div.f_manage"
    const val BLACKLIST_LINKS = "div.blacklist ul.commonlist a[href*=space.php]"

    val FORMHASH_FIELD_NAMES = setOf("formhash", "m_formhash")
    val OTHER_TOKEN_FIELD_NAMES = setOf("hash", "sid", "auth", "seccode", "seccodeverify", "seccodehash")
    private val COOKIE_NAME_REGEX = Regex("""(?i)^(?:cdb_[a-z0-9_]+|cf_clearance|__cf_bm|cf_chl_[a-z0-9_]*)$""")

    val DISCUZ_UID_REGEX =
        Regex("""(?i)((?<![.\w$])(['"]?)discuz_uid\2\s*(?:=|:)\s*(?:(?:parseInt|Number)\s*\(\s*)?)(['"]?)([^'",;\s<>)}]+)\3""")
    val COOKIE_ASSIGN_REGEX =
        Regex("""(?i)((?<![\w-])(?:cdb_[a-z0-9_]+|cf_clearance|__cf_bm|cf_chl_[a-z0-9_]*)\s*=\s*)(['"]?)([^&;#'"\s<>),]+)\2""")
    val COOKIE_OBJECT_PROP_REGEX =
        Regex("""(?i)((?<![.\w-])(['"]?)(?:cdb_[a-z0-9_]+|cf_clearance|__cf_bm|cf_chl_[a-z0-9_]*)\2\s*:\s*)(['"])([^'"]+)\3""")

    val URL_TOKEN_REGEX =
        Regex("""(?i)(^|[?&;'"])(formhash|m_formhash|hash|sid|auth|seccode(?:verify|hash)?)=([^&#'"\s<>);,]+)""")
    val JS_FORMHASH_ASSIGN_REGEX =
        Regex("""(?i)(\b(m_formhash|formhash|seccode(?:verify|hash)?)\s*=\s*)(['"])([^'"]+)\3""")
    val JS_OTHER_TOKEN_ASSIGN_REGEX =
        Regex("""(?i)((?<![.\w$])(hash|sid|auth)\s*=\s*)(['"])([^'"]+)\3""")
    val JS_OBJECT_PROP_REGEX =
        Regex("""(?i)((?<![.\w$])(['"]?)(formhash|m_formhash|hash|sid|auth|seccode(?:verify|hash)?)\2\s*:\s*)(['"])([^'"]+)\4""")

    val EMBEDDED_INPUT_TAG_REGEX = Regex("""(?i)<input\b[^>]*>""")
    val INPUT_NAME_OR_ID_ATTR_REGEX = Regex("""(?i)\b(?:name|id)\s*=\s*(['"]?)([^'">\s]+)\1""")
    val INPUT_VALUE_ATTR_REGEX = Regex("""(?i)(\bvalue\s*=\s*)(['"]?)([^'">\s]+)\2""")

    // 出处：HiParserThreadDetail.java:360 (attachment.php?aid=...)，Discuz 7.x aidencode 4/5 段 Base64
    val ATTACHMENT_AID_URL_REGEX =
        Regex("""(?i)(attachment\.php\?(?:[^&#'"\s<>)]*&(?:amp;)?)*aid=)([^&#'"\s<>);,]+)""")
    internal val ATTACHMENT_AID_PAYLOAD_REGEX = Regex("""^(\d+)\|([0-9a-fA-F]{8})\|(\d+)\|(\d+)(\|\d+)?$""")

    val UID_INPUT_NAMES = setOf("uid", "touid", "srchuid", "authorid", "userid", "buddyid", "newbuddyid")

    val UID_QUERY_PARAM_REGEX =
        Regex("""(?i)(^|[?&;])((?:[a-z_]*uid|author_?id|user_?id|(?:new_?)?buddy_?id)=)(\d+)(?![0-9A-Za-z_])""")
    val SCRIPT_UID_ASSIGN_REGEX =
        Regex("""(?i)((?<![.\w$])(?:[a-z_]*uid|author_?id|user_?id|(?:new_?)?buddy_?id)\s*=\s*)(['"]?)(\d+)\2(?![0-9A-Za-z_])""")
    val SCRIPT_UID_PROP_REGEX =
        Regex("""(?i)((?<![.\w$])(['"]?)(?:[a-z_]*uid|author_?id|user_?id|(?:new_?)?buddy_?id)\2\s*:\s*)(['"]?)(\d+)\3(?![0-9A-Za-z_])""")
    val SCRIPT_UID_CALL_REGEX =
        Regex("""(?i)(\b(?:deletepm|showuser|sendpm)\s*\(\s*)(['"]?)(\d+)\2(?![0-9A-Za-z_])""")
    val TEXT_UID_LABEL_REGEX = Regex("""(?i)(\bUID\s*[:：=]?\s*)(\d+)(?!\d)""")
    internal val URL_USERNAME_PARAM_REGEX =
        Regex("""(?i)(^|[?&;])((?:username|srchuname|msgto|user|author)=)([^&#'"\s<>);,\]]+)""")
    internal val USERNAME_QUERY_PARAM_REGEX = Regex("""(?i)(^|[?&;])(username=)([^&#'"\s<>);,\]]+)""")

    // 出处：HiUtils.java:208-221 (getAvatarUrlByUid，9 位补零分四段路径)
    val AVATAR_PATH_REGEX = Regex("""(\d{3}/\d{2}/\d{2}/\d{2})(?=_avatar_)""")

    val EMAIL_REGEX =
        Regex("""(?i)(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?![A-Za-z0-9.-])""")
    val IPV4_REGEX =
        Regex("""(?<![A-Za-z0-9._-])((?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))(?::(\d{1,5}))?(?![0-9.])""")
    val IPV6_REGEX =
        Regex("""(?i)(?<![0-9a-f:])((?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,5}|(?:[0-9a-f]{1,4}:){1,7}:|::(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})(?![0-9a-f:])""")

    internal val SMS_TITLE_PEER_REGEX = Regex("""与\s*(.+?)\s*的(?:短消息|交谈记录)""")
    internal val SAFE_SMS_SPACE_HREF_REGEX = Regex("""(?i)^space\.php\?uid=(?:10000|20000)$""")
    internal val SAFE_FORUM_ICON_SRC_REGEX =
        Regex("""(?i)^(?:https?://[^/]*\.?4d4y\.com/forum/)?images/(?:smilies|default|common)/[^?#\s]+$""")
    internal val BLOCKED_PLACEHOLDER_REGEX = Regex("""^HiBlocked\d+$""")
    internal val JUMP_FLOOR_REGEX = Regex("""^(回复\s+\d+#\s*)(.+)$""")
    internal val STATS_LABEL_REGEX = Regex("""^(?:查看|回复|主题|帖子|最后发表)\s*[:：]\s*\d.*$""")
    // 出处：HiParser.java:393-396 (parseNotifyThread)
    internal val NOTIFY_REPLY_PHRASE_REGEX = Regex("""回复了您(?:关注的主题|的帖子)""")
    private val DISCUZ_XML_ROOT_REGEX = Regex("""(?is)^\s*(?:<\?xml[^>]*\?>\s*)?<root\b""")

    fun isSensitiveFieldName(name: String): Boolean {
        val lower = name.lowercase()
        return lower in FORMHASH_FIELD_NAMES || lower in OTHER_TOKEN_FIELD_NAMES || COOKIE_NAME_REGEX.matches(lower)
    }

    internal fun isXmlDocument(content: String): Boolean = DISCUZ_XML_ROOT_REGEX.containsMatchIn(content)

    internal fun buildSmsPlaceholder(length: Int): String = buildString(length.coerceAtLeast(0)) {
        while (this.length < length) {
            append(PLACEHOLDER_SMS_UNIT, 0, minOf(length - this.length, PLACEHOLDER_SMS_UNIT.length))
        }
    }

    internal fun isSmsPlaceholder(text: String): Boolean = text.isEmpty() || text == buildSmsPlaceholder(text.length)
    internal fun maskSameLength(value: String): String = PLACEHOLDER_TOKEN_CHAR.toString().repeat(value.length)
    internal fun isAllZeroMask(value: String): Boolean = value.isNotEmpty() && value.all { it == PLACEHOLDER_TOKEN_CHAR }
    internal fun isReplaceableUid(uid: String): Boolean =
        uid.isNotEmpty() && uid.all(Char::isDigit) && uid != PLACEHOLDER_UID && !isAllZeroMask(uid)

    internal fun buildStandaloneUidRegex(uids: Collection<String>): Regex? {
        val eligible = uids
            .map { it.trim() }
            .filter { isReplaceableUid(it) && it.length >= MIN_STANDALONE_SELF_UID_LENGTH }
            .distinct()
            .sortedByDescending { it.length }
        if (eligible.isEmpty()) return null
        val alternation = eligible.joinToString("|", transform = Regex::escape)
        return Regex("""(?:(?<!\d)|(?<=%[0-9A-Fa-f]{2}))(?:$alternation)(?!\d)""")
    }

    internal fun isSafeSmsHref(href: String): Boolean =
        href == PLACEHOLDER_SMS_URL || href == "#" || href.equals("javascript:;", ignoreCase = true) ||
            SAFE_SMS_SPACE_HREF_REGEX.matches(href)

    internal fun isSafeSmsImgSrc(src: String): Boolean =
        src == PLACEHOLDER_SMS_IMAGE_URL || SAFE_FORUM_ICON_SRC_REGEX.matches(src)

    fun isSafeEmail(email: String): Boolean {
        val lower = email.lowercase()
        return lower == PLACEHOLDER_EMAIL || lower.endsWith("@example.invalid")
    }

    fun isSafeIpv4(ip: String): Boolean = ip == PLACEHOLDER_IPV4 || ip == "0.0.0.0"
    fun isSafeIpv6(ip: String): Boolean = ip == PLACEHOLDER_IPV6 || ip == "::"

    internal fun decodeAttachmentAid(rawAid: String): MatchResult? {
        val urlDecoded = runCatching {
            URLDecoder.decode(rawAid.replace("+", "%2B"), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val padded = when (urlDecoded.length % 4) {
            0 -> urlDecoded
            2 -> "$urlDecoded=="
            3 -> "$urlDecoded="
            else -> return null
        }
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        val payload = String(bytes, StandardCharsets.UTF_8)
        return ATTACHMENT_AID_PAYLOAD_REGEX.matchEntire(payload)
    }

    fun sanitizeAttachmentAid(rawAid: String): String {
        val match = decodeAttachmentAid(rawAid) ?: return rawAid
        val sanitizedPayload = "${match.groupValues[1]}|00000000|0|$PLACEHOLDER_UID${match.groupValues[5]}"
        val reencoded = Base64.getEncoder().encodeToString(sanitizedPayload.toByteArray(StandardCharsets.UTF_8))
        return if ('%' in rawAid) URLEncoder.encode(reencoded, StandardCharsets.UTF_8) else reencoded
    }

    internal fun urlDecodeCandidates(rawValue: String): Set<String> = buildSet {
        add(rawValue)
        val charsets = listOfNotNull(StandardCharsets.UTF_8, runCatching { Charset.forName("GB18030") }.getOrNull())
        for (charset in charsets) {
            runCatching { URLDecoder.decode(rawValue, charset) }.getOrNull()?.let(::add)
        }
    }

    internal fun uidToAvatarPath(uid: String): String? {
        if (uid.isEmpty() || uid.length > 9 || !uid.all(Char::isDigit)) return null
        val p = uid.padStart(9, '0')
        return "${p.substring(0, 3)}/${p.substring(3, 5)}/${p.substring(5, 7)}/${p.substring(7, 9)}"
    }

    internal fun avatarPathToUid(path: String): String? = path.replace("/", "").trimStart('0').ifEmpty { null }

    internal fun Node.forEachNode(action: (Node) -> Unit) {
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) = action(node)
                override fun tail(node: Node, depth: Int) = Unit
            },
            this,
        )
    }

    internal fun Node.collectTextNodes(): List<TextNode> = buildList { forEachNode { if (it is TextNode) add(it) } }

    // 避免 Kotlin 2.4 推断 Jsoup Element.selectFirst 的 @org.jspecify.annotations.Nullable 类型注解报错
    internal fun Element.firstMatch(selector: String): Element? = select(selector).firstOrNull()
}
