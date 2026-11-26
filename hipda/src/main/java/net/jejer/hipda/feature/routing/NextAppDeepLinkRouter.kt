package net.jejer.hipda.feature.routing

import android.content.Intent
import androidx.compose.runtime.Immutable
import net.jejer.hipda.data.search.ForumLinkResolver
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.utils.Constants

/**
 * 统一外部 Intent / 桌面快捷方式 / 系统通知点击解析目标（不可变值模型）。
 */
@Immutable
sealed interface DeepLinkTarget {
    data object TodayNew : DeepLinkTarget
    data object Search : DeepLinkTarget
    data object Sms : DeepLinkTarget
    data object Notify : DeepLinkTarget
    data object Favorites : DeepLinkTarget

    /** 单条未读短消息通知直达：严格只携带校验后的 `uid`，不信任也不持久化 Intent 中的用户名。 */
    @Immutable
    data class SmsConversation(val uid: String) : DeepLinkTarget

    @Immutable
    data class Forum(val fid: Int) : DeepLinkTarget

    @Immutable
    data class Thread(val tid: String, val page: Int = 1) : DeepLinkTarget

    @Immutable
    data class ThreadLastPage(val tid: String) : DeepLinkTarget

    @Immutable
    data class Post(val tid: String?, val pid: String) : DeepLinkTarget

    @Immutable
    data class User(val uid: String) : DeepLinkTarget

    /** 合法 `http`/`https` 外部链接但无法映射为已知站内目标：由宿主给出明确反馈而非静默丢弃。 */
    @Immutable
    data class UnsupportedUrl(val url: String) : DeepLinkTarget
}

/** Debug/预览入口显式 URL Extra 键名。 */
const val EXTRA_DEEP_LINK_URL: String = "net.jejer.hipda.extra.DEEP_LINK_URL"

/**
 * 纯解析外部 Intent、桌面快捷方式与通知点击目标。
 *
 * - 快捷方式 4 个动作与 `res/xml/shortcuts.xml` 一致：`new_posts`、`search`、`sms`、`favorite`；
 * - 通知点击（[Constants.INTENT_NOTIFICATION]）遵循 M6-polish 隐私约定，忽略 [Constants.EXTRA_USERNAME]；
 * - 外部链接强制先经 [SafeLinkPolicy.sanitizeHttpUrl] 严格白名单校验，任何危险或非 `http`/`https` scheme 一律返回 `null`。
 */
fun parseIncomingIntent(
    intent: Intent?,
    forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
): DeepLinkTarget? {
    if (intent == null) return null
    return when (intent.action) {
        Constants.INTENT_NEW_POSTS -> DeepLinkTarget.TodayNew
        Constants.INTENT_SEARCH -> DeepLinkTarget.Search
        Constants.INTENT_SMS -> DeepLinkTarget.Sms
        Constants.INTENT_FAVORITE -> DeepLinkTarget.Favorites
        Constants.INTENT_NOTIFICATION -> parseNotificationIntent(intent)
        Intent.ACTION_VIEW -> {
            val rawUrl = intent.dataString ?: intent.getStringExtra(EXTRA_DEEP_LINK_URL)
            parseDeepLinkUrl(rawUrl, forumServer)
        }
        else -> {
            val explicitUrl = intent.getStringExtra(EXTRA_DEEP_LINK_URL)
            if (explicitUrl != null) parseDeepLinkUrl(explicitUrl, forumServer) else null
        }
    }
}

/** 纯函数解析外部链接字符串：先走 [SafeLinkPolicy.sanitizeHttpUrl] 硬白名单，再由 [ForumLinkResolver] 映射站内目标。 */
fun parseDeepLinkUrl(
    rawUrl: String?,
    forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
): DeepLinkTarget? {
    val safeUrl = SafeLinkPolicy.sanitizeHttpUrl(rawUrl) ?: return null
    return when (val resolved = ForumLinkResolver.resolve(safeUrl, forumServer)) {
        is ResolvedForumLink.Forum -> DeepLinkTarget.Forum(resolved.fid)
        is ResolvedForumLink.Thread -> DeepLinkTarget.Thread(resolved.tid, resolved.page)
        is ResolvedForumLink.ThreadLastPage -> DeepLinkTarget.ThreadLastPage(resolved.tid)
        is ResolvedForumLink.Post -> DeepLinkTarget.Post(resolved.tid, resolved.pid)
        is ResolvedForumLink.User -> DeepLinkTarget.User(resolved.uid)
        null -> DeepLinkTarget.UnsupportedUrl(safeUrl)
    }
}

private fun parseNotificationIntent(intent: Intent): DeepLinkTarget? {
    val smsCount = intent.getIntExtra(Constants.EXTRA_SMS_COUNT, 0)
    val threadCount = intent.getIntExtra(Constants.EXTRA_THREAD_COUNT, 0)
    val uid = parsePositiveNumericId(intent.getStringExtra(Constants.EXTRA_UID))
    return when {
        smsCount == 1 && threadCount <= 0 && uid != null -> DeepLinkTarget.SmsConversation(uid)
        smsCount > 0 -> DeepLinkTarget.Sms
        threadCount > 0 -> DeepLinkTarget.Notify
        else -> null
    }
}

private fun parsePositiveNumericId(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
    val numeric = trimmed.toLongOrNull() ?: return null
    return if (numeric > 0L) trimmed else null
}
