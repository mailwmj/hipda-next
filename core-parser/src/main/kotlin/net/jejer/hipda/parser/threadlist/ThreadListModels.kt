package net.jejer.hipda.parser.threadlist

import net.jejer.hipda.parser.common.DiscuzPageMeta

/**
 * 帖子列表单条主题摘要（不可变模型，覆盖旧 `ThreadBean` 全部 17 个字段）。
 *
 * 置顶状态（[isSticky]）与作者（[author]）均原样保留，交由下游 `D4` 按设置与黑名单过滤；
 * [titleColor] 原样透传 CSS 颜色文本，深浅色可读性判断留至渲染期。
 */
data class ThreadSummary(
    val tid: String,
    val title: String,
    val titleColor: String?,
    val typeName: String?,
    val isSticky: Boolean,
    val isNew: Boolean,
    val isPoll: Boolean,
    val author: String,
    val authorId: String,
    val avatarUrl: String?,
    val timeCreate: String,
    val timeUpdate: String?,
    val lastPostAuthor: String,
    val replyCountText: String,
    val viewCountText: String,
    val withPic: Boolean,
    val maxPage: Int,
) {
    /** 容错整数回复数（如 `"-"` 返回 `null`）。 */
    val replyCount: Int?
        get() = replyCountText.trim().toIntOrNull()

    /** 容错整数查看数（如 `"-"` 返回 `null`）。 */
    val viewCount: Int?
        get() = viewCountText.trim().toIntOrNull()
}

/**
 * 帖子列表页解析结果（不可变模型）。
 *
 * - [hasThreadRows] 对齐旧 `ThreadListBean.isParsed`（页内是否存在 `tbody[id]`）；
 * - [page] / [lastPage] 供下游 `D4` 自动补页判断末页终止条件；
 * - [meta] 复用公共页头元信息（会话、formhash、错误提示、提醒计数）。
 */
data class ThreadListPage(
    val threads: List<ThreadSummary>,
    val hasThreadRows: Boolean,
    val page: Int,
    val lastPage: Int,
    val meta: DiscuzPageMeta,
)
