package net.jejer.hipda.parser.post

import net.jejer.hipda.parser.common.DiscuzPageMeta

enum class PrePostMode {
    NEW_THREAD,
    REPLY_THREAD,
    QUICK_REPLY,
    REPLY_POST,
    QUOTE_POST,
    EDIT_POST,
}

data class PrePostInfo(
    val formhash: String,
    val uid: String?,
    val hash: String?,
    val subject: String?,
    val text: String,
    val quoteText: String,
    val isDeletable: Boolean,
    val maxUploadFileSizeBytes: Int?,
    val noticeAuthor: String?,
    val noticeAuthorMsg: String?,
    val noticeTrimStr: String?,
    val existingImages: List<String>,
    val existingAttachments: List<String>,
    val typeId: String?,
    val typeOptions: Map<String, String>,
    val meta: DiscuzPageMeta,
)

sealed interface PrePostParseResult {
    data class Success(val info: PrePostInfo) : PrePostParseResult
    data class Error(val message: String) : PrePostParseResult
}
