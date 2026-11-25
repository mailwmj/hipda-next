package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.common.DiscuzPageMeta

data class SmsConversationItem(
    val uid: String,
    val author: String,
    val avatarUrl: String?,
    val time: String,
    val summary: String,
    val isNew: Boolean,
)

data class SmsListPage(
    val items: List<SmsConversationItem>,
    val meta: DiscuzPageMeta,
    val hasContainer: Boolean,
)

data class SmsMessageItem(
    val uid: String,
    val author: String,
    val avatarUrl: String?,
    val time: String,
    val contentHtml: String,
    val isNew: Boolean,
    val isMine: Boolean,
)

data class SmsDetailPage(
    val myUid: String?,
    val myUsername: String?,
    val formhash: String?,
    val messages: List<SmsMessageItem>,
    val meta: DiscuzPageMeta,
    val hasContainer: Boolean = true,
)

sealed interface SmsSendResult {
    data object Success : SmsSendResult
    data class Failed(val message: String) : SmsSendResult
}
