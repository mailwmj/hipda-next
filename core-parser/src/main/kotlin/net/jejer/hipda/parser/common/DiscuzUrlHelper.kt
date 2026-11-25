package net.jejer.hipda.parser.common

import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Discuz! URL、查询参数、头像路径与数字提取工具。
 * 纯 JVM、无副作用、不硬编码站点域名。
 */
object DiscuzUrlHelper {

    const val AVATAR_SUFFIX_MIDDLE = "_avatar_middle.jpg"
    const val NEW_PM_IMAGE_MARKER = "images/default/notice_newpm.gif"
    private const val AVATAR_UID_PAD_LENGTH = 9

    /**
     * 截取 `start` 与 `end` 之间的子串，语义对齐旧 `Utils.getMiddleString`：
     * - 未找到 `start` 返回空串；
     * - `end` 为空或未找到 `end` 时截取至末尾；
     * - 截取区间为空时返回空串。
     */
    fun middleString(source: String?, start: String, end: String = ""): String {
        if (source.isNullOrEmpty() || start.isEmpty()) return ""
        val startIndex = source.indexOf(start)
        if (startIndex == -1) return ""
        val contentStart = startIndex + start.length
        val endIndex = if (end.isEmpty()) {
            source.length
        } else {
            val found = source.indexOf(end, contentStart)
            if (found == -1) source.length else found
        }
        return if (endIndex <= contentStart) "" else source.substring(contentStart, endIndex)
    }

    /**
     * 从 URL 或查询串中按完整参数名提取非空值（忽略片段 `#...`，兼容 `&amp;` 与 `;` 分隔）。
     */
    fun extractQueryParam(url: String?, name: String): String? {
        if (url.isNullOrBlank() || name.isEmpty()) return null
        val withoutFragment = url.substringBefore('#')
        val query = if ('?' in withoutFragment) {
            withoutFragment.substringAfter('?')
        } else {
            withoutFragment
        }
        if (query.isEmpty()) return null
        val normalized = query.replace("&amp;", "&", ignoreCase = true)
        for (pair in normalized.split('&', ';')) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex <= 0) continue
            val key = pair.substring(0, eqIndex).trim()
            if (key.equals(name, ignoreCase = true)) {
                val value = pair.substring(eqIndex + 1).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /**
     * 按 Discuz! UCenter 9 位补零规则计算中等头像相对路径，如 `"10000"` -> `"000/01/00/00_avatar_middle.jpg"`。
     * 非法或超长 UID 返回 `null`。
     */
    fun avatarRelativePathForUid(uid: String?): String? {
        val trimmed = uid?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > AVATAR_UID_PAD_LENGTH || !trimmed.all { it in '0'..'9' }) {
            return null
        }
        val padded = trimmed.padStart(AVATAR_UID_PAD_LENGTH, '0')
        return "${padded.substring(0, 3)}/${padded.substring(3, 5)}/${padded.substring(5, 7)}/${padded.substring(7, 9)}$AVATAR_SUFFIX_MIDDLE"
    }

    /**
     * 计算 UID 对应的头像地址：
     * - [avatarBaseUrl] 为空或 `null` 时返回相对路径（不硬编码服务器域名）；
     * - 提供 [avatarBaseUrl] 时自动规范化末尾 `/` 后拼接。
     */
    fun avatarUrlForUid(uid: String?, avatarBaseUrl: String? = null): String? {
        val relativePath = avatarRelativePathForUid(uid) ?: return null
        val base = avatarBaseUrl?.trim().orEmpty()
        if (base.isEmpty()) return relativePath
        return if (base.endsWith("/")) "$base$relativePath" else "$base/$relativePath"
    }

    /**
     * 规范化页面中的头像 URL：空串或包含 `noavatar` 时返回 `null`，并将 `small` 替换为 `middle`。
     */
    fun normalizeAvatarUrl(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.contains("noavatar", ignoreCase = true)) {
            return null
        }
        return trimmed.replace("small", "middle")
    }

    fun isValidId(id: String?): Boolean {
        val trimmed = id?.trim() ?: return false
        val value = trimmed.toIntOrNull() ?: return false
        return value > 0
    }

    fun parseIntOrZero(raw: String?): Int = raw?.trim()?.toIntOrNull() ?: 0

    fun extractFirstIntOrZero(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 0
        val digits = buildString {
            for (ch in raw) {
                if (ch in '0'..'9') append(ch)
            }
        }
        return digits.toIntOrNull() ?: 0
    }
}

/**
 * 规避 Jsoup 1.24.1 `selectFirst`/`first`/`last` 的 `@org.jspecify.annotations.Nullable`
 * 在 Kotlin 2.4 下的泛型推断问题（见 `docs/agents/lesson.md`）。
 */
fun Element.firstMatch(selector: String): Element? = select(selector).firstOrNull()

fun Elements.firstElementOrNull(): Element? = firstOrNull()

fun Elements.lastElementOrNull(): Element? = lastOrNull()
