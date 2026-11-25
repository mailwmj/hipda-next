package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.common.DiscuzPageMeta

enum class NotifyKind {
    THREAD,
    QUOTE,
    REPLY,
    SYSTEM,
    FRIEND,
}

data class NotifyItem(
    val kind: NotifyKind,
    val title: String,
    val info: String,
    val author: String?,
    val authorUid: String?,
    val avatarUrl: String?,
    val tid: String?,
    val pid: String?,
    val time: String?,
    val isNew: Boolean,
    val quoteOriginal: String? = null,
    val quoteReply: String? = null,
)

data class NotifyListPage(
    val items: List<NotifyItem>,
    val meta: DiscuzPageMeta,
    val hasFeedContainer: Boolean,
)
