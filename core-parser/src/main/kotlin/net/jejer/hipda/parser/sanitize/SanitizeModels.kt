package net.jejer.hipda.parser.sanitize

import java.nio.file.Path

/**
 * 导出样本时已知的当前登录用户身份。
 */
data class Identity(
    val uid: String,
    val username: String,
)

/**
 * 待脱敏的页面类型；`SMS_*` 页面会额外脱敏短消息正文与对方身份。
 */
enum class PageType {
    THREAD_LIST,
    THREAD_DETAIL,
    SMS_LIST,
    SMS_DETAIL,
    NOTIFY,
    SEARCH,
    SIMPLE_LIST,
    USER_INFO,
    POST_FORM,
    LOGIN,
    BLACKLIST,
    OTHER,
    ;

    val isSms: Boolean
        get() = this == SMS_LIST || this == SMS_DETAIL
}

enum class LeakType {
    FORMHASH,
    DISCUZ_UID,
    COOKIE_VALUE,
    SMS_CONTENT,
}

data class Leak(
    val type: LeakType,
    val detail: String,
)

data class FileLeakReport(
    val file: Path,
    val leaks: List<Leak>,
)
