package net.jejer.hipda.parser.sanitize

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * 脱敏与泄漏扫描共用的占位常量、旧解析器选择器（附出处）与基础工具。
 */
object SanitizeSelectors {
    const val PLACEHOLDER_UID = "10000"
    const val PLACEHOLDER_USERNAME = "HiUser"
    const val PLACEHOLDER_PEER_UID = "20000"
    const val PLACEHOLDER_PEER_USERNAME = "HiPeer"
    const val PLACEHOLDER_SMS_UNIT = "测试内容"
    const val PLACEHOLDER_TOKEN_CHAR = '0'
    const val PLACEHOLDER_SELF_AVATAR_PATH = "000/01/00/00"
    const val PLACEHOLDER_PEER_AVATAR_PATH = "000/02/00/00"

    // 出处：LoginHelper.java:145；HiParserThreadList.java:30；HiParser.java:460,754
    const val UMENU = "div#umenu"
    const val UMENU_USER_LINK = "#umenu cite a"
    const val UMENU_LINKS = "div#umenu a"

    // 出处：HiParser.java:790；LoginHelper.java:85；PrePostAsyncTask.java:89；HiParserThreadDetail.java:210；PostSmsAsyncTask.java:53
    const val FORMHASH_INPUT = "input[name=formhash], input#formhash, input[name=m_formhash]"

    // 出处：HiParser.java:217,223,229,231,238,242,247,266 (parseSMS) 与 468,479,483,490,505 (parseSmsDetail)；PostSmsAsyncTask.java:92
    const val SMS_LIST_ITEMS = "ul.pm_list li"
    const val SMS_DETAIL_ITEMS = "li.s_clear"
    const val SMS_ITEMS = "$SMS_LIST_ITEMS, $SMS_DETAIL_ITEMS"
    const val SMS_AUTHOR_CITE = "p.cite cite"
    const val SMS_AUTHOR_LINK = "p.cite cite a"
    const val SMS_AVATAR_LINK = "a.avatar"
    const val SMS_AVATAR_IMG = "a.avatar img"
    const val SMS_SUMMARY = "div.summary"
    const val SMS_SUMMARY_IN_DOCUMENT = "$SMS_LIST_ITEMS $SMS_SUMMARY, $SMS_DETAIL_ITEMS $SMS_SUMMARY"

    // 出处：HiUtils.java:142-143；PostSmsAsyncTask.java:73-75,83
    const val SMS_POST_FORM = "form[action*=pm.php]"
    const val SMS_MSGTO_INPUT = "input[name=msgto]"

    internal val DISCUZ_UID_REGEX = Regex("""(?i)(\bdiscuz_uid\s*=\s*)(['"]?)([^'",;\s<>)}]+)\2""")
    internal val COOKIE_ASSIGN_REGEX =
        Regex("""(?i)((?<![\w-])(?:cdb_auth|cdb_sid|cf_clearance)\s*=\s*)(['"]?)([^&;#'"\s<>),]+)\2""")
    internal val COOKIE_OBJECT_PROP_REGEX =
        Regex("""(?i)((?<![.\w-])(['"]?)(?:cdb_auth|cdb_sid|cf_clearance)\2\s*:\s*)(['"])([^'"]+)\3""")
    internal val UID_QUERY_PARAM_REGEX = Regex("""(?i)(^|[?&])(uid=)(\d+)""")
    internal val USERNAME_QUERY_PARAM_REGEX = Regex("""(?i)(^|[?&])(username=)([^&#]+)""")

    // 出处：HiUtils.java:208-221 (getAvatarUrlByUid，9 位补零分四段路径)
    internal val AVATAR_PATH_REGEX = Regex("""(\d{3}/\d{2}/\d{2}/\d{2})(?=_avatar_)""")

    internal fun buildSmsPlaceholder(length: Int): String = buildString(length.coerceAtLeast(0)) {
        while (this.length < length) {
            append(PLACEHOLDER_SMS_UNIT, 0, minOf(length - this.length, PLACEHOLDER_SMS_UNIT.length))
        }
    }

    internal fun isSmsPlaceholder(text: String): Boolean = text.isEmpty() || text == buildSmsPlaceholder(text.length)
    internal fun maskSameLength(value: String): String = PLACEHOLDER_TOKEN_CHAR.toString().repeat(value.length)
    internal fun isAllZeroMask(value: String): Boolean = value.isNotEmpty() && value.all { it == PLACEHOLDER_TOKEN_CHAR }

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
