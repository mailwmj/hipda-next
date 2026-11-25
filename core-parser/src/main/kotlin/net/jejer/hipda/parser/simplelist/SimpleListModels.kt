package net.jejer.hipda.parser.simplelist

import net.jejer.hipda.parser.common.DiscuzPageMeta

data class MyThreadItem(
    val tid: String,
    val title: String,
    val time: String,
    val forumName: String?,
)

data class MyThreadsPage(
    val maxPage: Int,
    val items: List<MyThreadItem>,
    val meta: DiscuzPageMeta,
    val hasTable: Boolean,
)

data class MyReplyItem(
    val tid: String,
    val pid: String,
    val title: String,
    val replyText: String?,
    val time: String,
    val forumName: String?,
)

data class MyRepliesPage(
    val maxPage: Int,
    val items: List<MyReplyItem>,
    val meta: DiscuzPageMeta,
    val hasTable: Boolean,
)

data class FavoriteThreadItem(
    val tid: String,
    val title: String,
    val time: String?,
    val forumName: String?,
)

data class FavoritesListPage(
    val maxPage: Int,
    val items: List<FavoriteThreadItem>,
    val meta: DiscuzPageMeta,
)
