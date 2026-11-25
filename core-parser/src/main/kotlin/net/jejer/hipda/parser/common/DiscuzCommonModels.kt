package net.jejer.hipda.parser.common

data class DiscuzSessionInfo(
    val isLoggedIn: Boolean,
    val uid: String?,
    val username: String?,
)

data class PromptCounts(
    val pm: Int = 0,
    val announcePm: Int = 0,
    val systemPm: Int = 0,
    val friend: Int = 0,
    val thread: Int = 0,
) {
    /** 与旧 `NotiHelper` 的 `prompt_threads + prompt_systempm + prompt_friend` 统计等价。 */
    val notifyTotal: Int
        get() = thread + systemPm + friend
}

data class DiscuzPageMeta(
    val session: DiscuzSessionInfo,
    val formhash: String?,
    val errorMessage: String?,
    val promptCounts: PromptCounts,
)
