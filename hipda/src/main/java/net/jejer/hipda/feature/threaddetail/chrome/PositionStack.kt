package net.jejer.hipda.feature.threaddetail.chrome

import androidx.compose.runtime.Immutable
import net.jejer.hipda.feature.threaddetail.ThreadDetailFilterUi

/**
 * 帖子详情阅读锚点快照，供 [PositionStack] 在跳页、引用跳楼或查看新回复前入栈，
 * 并在用户按系统返回时出栈经单一滚动权威 `PrependAnchorController` 精确回位。
 */
@Immutable
data class ReadingPositionAnchor(
    val page: Int,
    val postId: String?,
    val scrollOffsetPx: Int,
    val filter: ThreadDetailFilterUi = ThreadDetailFilterUi.All,
) {
    fun normalized(): ReadingPositionAnchor = copy(
        page = page.coerceAtLeast(1),
        postId = postId?.trim()?.takeIf { it.isNotEmpty() },
        scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0),
    )
}

/**
 * 帖子详情阅读位置返回栈（有界后进先出）：
 * - 执行跳页（含 `首页`/`末页`/滑块/输入）、点击引用/跳楼或查看新回复前压入跳前锚点；
 * - 用户按系统返回时优先于 `E3` 多栏返回栈逐级出栈回位，栈空后再交由外层离开帖子；
 * - 连续压入完全相同的栈顶锚点时自动去重，防止重复点击堆积无意义历史。
 */
class PositionStack(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    init {
        require(maxDepth > 0) { "maxDepth must be > 0, got $maxDepth" }
    }

    private val entries = ArrayDeque<ReadingPositionAnchor>()

    val size: Int
        get() = entries.size

    val isEmpty: Boolean
        get() = entries.isEmpty()

    val isNotEmpty: Boolean
        get() = entries.isNotEmpty()

    fun push(anchor: ReadingPositionAnchor) {
        val normalized = anchor.normalized()
        if (entries.lastOrNull() == normalized) return
        if (entries.size >= maxDepth) {
            entries.removeFirst()
        }
        entries.addLast(normalized)
    }

    fun pop(): ReadingPositionAnchor? = entries.removeLastOrNull()

    fun peek(): ReadingPositionAnchor? = entries.lastOrNull()

    fun clear() {
        entries.clear()
    }

    fun snapshot(): List<ReadingPositionAnchor> = entries.toList()

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 32
    }
}
