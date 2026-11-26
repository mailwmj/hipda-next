package net.jejer.hipda.feature.common

/**
 * 新版统一 Intent Action 与 Extra 常量（脱离旧 `net.jejer.hipda.utils.Constants`）。
 *
 * - 快捷方式 4 个动作与 `res/xml/shortcuts.xml` 保持一致；
 * - 通知点击与 [net.jejer.hipda.feature.app.MainActivity] /
 *   [net.jejer.hipda.feature.routing.NextAppDeepLinkRouter] 共享。
 */
object AppIntentConstants {
    const val INTENT_NOTIFICATION: String = "notification"
    const val INTENT_SMS: String = "sms"
    const val INTENT_SEARCH: String = "search"
    const val INTENT_FAVORITE: String = "favorite"
    const val INTENT_NEW_POSTS: String = "new_posts"

    const val EXTRA_SMS_COUNT: String = "sms_count"
    const val EXTRA_THREAD_COUNT: String = "thread_count"
    const val EXTRA_UID: String = "uid"

    /**
     * 旧版遗留 Extra 键名：仅用于隐私边界剥离校验与测试，新代码严禁写入。
     */
    const val EXTRA_USERNAME: String = "username"
}
