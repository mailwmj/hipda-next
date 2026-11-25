package net.jejer.hipda.parser.user

import net.jejer.hipda.parser.common.DiscuzPageMeta

data class UserProfile(
    val uid: String?,
    val username: String?,
    val isOnline: Boolean,
    val avatarUrl: String?,
    val formhash: String?,
    val detail: String,
    val meta: DiscuzPageMeta,
)

sealed interface BlacklistParseResult {
    data class Success(
        val usernames: List<String>,
        val isEmptyConfirmed: Boolean,
    ) : BlacklistParseResult

    data class InvalidPage(
        val reason: String,
    ) : BlacklistParseResult
}
