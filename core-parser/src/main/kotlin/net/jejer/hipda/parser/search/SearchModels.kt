package net.jejer.hipda.parser.search

import net.jejer.hipda.parser.common.DiscuzPageMeta

data class SearchItem(
    val tid: String?,
    val pid: String?,
    val title: String,
    val snippet: String?,
    val author: String?,
    val authorUid: String?,
    val avatarUrl: String?,
    val time: String?,
    val forumName: String?,
)

data class SearchPage(
    val searchId: String?,
    val maxPage: Int,
    val items: List<SearchItem>,
    val meta: DiscuzPageMeta,
)
