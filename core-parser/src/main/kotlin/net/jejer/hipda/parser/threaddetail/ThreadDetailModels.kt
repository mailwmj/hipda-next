package net.jejer.hipda.parser.threaddetail

import net.jejer.hipda.parser.common.DiscuzPageMeta

/**
 * 帖子详情单楼层不可变模型（对照旧 `DetailBean`）。
 *
 * 不执行黑名单过滤、不写全局签名缓存，所有过滤与缓存由上层 Repository 负责。
 */
data class PostFloor(
    val postId: String,
    val floor: Int,
    val page: Int,
    val uid: String,
    val author: String,
    val avatarUrl: String?,
    val timePost: String,
    val isWarned: Boolean,
    val postStatus: String?,
    val signature: String?,
    val poll: ThreadPoll?,
    val blocks: List<ContentBlock>,
) {
    /** 当前楼层内全部图片块（按出现顺序）。 */
    val images: List<ContentBlock.Image>
        get() = blocks.filterIsInstance<ContentBlock.Image>()
}

/**
 * 帖子详情页（`viewthread.php`）顶层不可变解析结果（对照旧 `DetailListBean`）。
 *
 * @property tid 主题 ID（优先使用调用方传入的纯数字 `tid`，缺省时尝试从页面链接/表单回退提取）。
 * @property fid 版块 ID，未解析到时为 `0`。
 * @property title 主题标题（优先 `div#nav`，为空时回退 `div#threadtitle`，经 `DiscuzTextDecoder` 解码双重转义 Emoji）。
 * @property page 当前页码（默认 `1`）。
 * @property lastPage 总页数（默认 `1`）。
 * @property floors 当前页解析出的有序楼层列表。
 * @property postsPerPageEstimate 估算的每页楼层数（第 1 页且多页时为本页楼层数；`page > 1` 时为 `(firstFloor - 1) / (page - 1)`；单页无法估算时为 `null`），仅计算返回，不写全局设置。
 * @property meta 公共页头元信息（会话、formhash、错误提示、提醒计数，复用 B9a `DiscuzCommonParser`）。
 * @property hasPostList 页面是否包含唯一的 `div#wrap div#postlist` 容器（缺失或异常页时为 `false`，对应旧解析器返回 `null`）。
 */
data class ThreadDetailPage(
    val tid: String?,
    val fid: Int,
    val title: String,
    val page: Int,
    val lastPage: Int,
    val floors: List<PostFloor>,
    val postsPerPageEstimate: Int?,
    val meta: DiscuzPageMeta,
    val hasPostList: Boolean,
) {
    /** 当前页全部楼层图片（按楼层与块出现顺序展开）。 */
    val allImages: List<ContentBlock.Image>
        get() = floors.flatMap { it.images }

    /** 按 `postId` 查找当前页内对应楼层。 */
    fun findFloorByPostId(postId: String): PostFloor? = floors.firstOrNull { it.postId == postId }
}
