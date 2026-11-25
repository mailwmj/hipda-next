package net.jejer.hipda.parser.auth

import net.jejer.hipda.parser.common.DiscuzSessionInfo

sealed interface LoginFormParseResult {
    data class Ready(val formhash: String) : LoginFormParseResult

    data class AlreadyLoggedIn(
        val session: DiscuzSessionInfo,
        val logoutFormhash: String?,
    ) : LoginFormParseResult

    data class Error(val message: String) : LoginFormParseResult
}

sealed interface LoginSubmitResult {
    data class Success(val message: String) : LoginSubmitResult

    data class Failed(
        val message: String,
        val remainingAttempts: Int?,
    ) : LoginSubmitResult

    data class UnknownError(val rawSnippet: String) : LoginSubmitResult
}
