package net.jejer.hipda.feature.forum

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.*
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.ui.designsystem.motion.HiMotion

private val SWIPE_FLING_VELOCITY_THRESHOLD = 1000.dp
private val SWIPE_NEAR_ZERO_OFFSET = 4.dp
private const val SWIPE_DISTANCE_FRACTION = 0.25f
private const val NO_NEIGHBOR_DAMPING = 0.3f
private const val SWIPE_SNAP_EPSILON_PX = 0.5f

/** 按「今日新帖 + followedForums」顺序取相邻版块：`direction > 0` 下一项（左滑），`< 0` 上一项（右滑），首尾不循环，未关注返回 `null`。 */
fun adjacentForumSource(current: ForumListSource, followedForums: List<Int>, direction: Int): ForumListSource? {
    val step = direction.sign
    if (step == 0) return null
    val index = when (current) {
        ForumListSource.TodayNew -> 0
        is ForumListSource.Forum -> followedForums.indexOf(current.fid).takeIf { it >= 0 }?.plus(1) ?: return null
    }
    val target = index + step
    return when {
        target == 0 -> ForumListSource.TodayNew
        target in 1..followedForums.size -> ForumListSource.Forum(followedForums[target - 1])
        else -> null
    }
}

internal fun resolveForumSwipeStep(offsetPx: Float, velocityPxPerSec: Float, widthPx: Float, flingThresholdPx: Float, nearZeroPx: Float): Int = when {
    widthPx <= 0f -> 0
    abs(velocityPxPerSec) >= flingThresholdPx ->
        if (abs(offsetPx) <= nearZeroPx || offsetPx.sign == velocityPxPerSec.sign) -velocityPxPerSec.sign.toInt() else 0
    abs(offsetPx) > widthPx * SWIPE_DISTANCE_FRACTION -> -offsetPx.sign.toInt()
    else -> 0
}

private fun dampingFor(value: Float, canPrev: Boolean, canNext: Boolean): Float =
    if ((value < 0f && !canNext) || (value > 0f && !canPrev)) NO_NEIGHBOR_DAMPING else 1f

/** 论坛列表横向切换状态：拖拽期同步写 state，松手/入场交由标准 `Animatable`，`try/finally` 兜底归零。 */
@Stable
internal class ForumSwipeState(initialOffsetPx: Float, private val scope: CoroutineScope, private val reducedMotion: () -> Boolean) {
    private val offsetAnim = Animatable(initialOffsetPx)
    private var pendingEnter = initialOffsetPx != 0f
    private var isDragging by mutableStateOf(false)
    private var dragOffsetPx by mutableFloatStateOf(0f)
    private var rawDragPx = 0f
    private var isExiting = false
    private var activeAnimJob: Job? = null

    val offsetPx: Float get() = if (isDragging) dragOffsetPx else offsetAnim.value

    fun startPendingEnterIfNeeded() {
        if (!pendingEnter) return
        pendingEnter = false
        launchAnimation(null, 0f, HiMotion.spatialDefault(visibilityThreshold = SWIPE_SNAP_EPSILON_PX), 0f)
    }

    fun onDragDelta(deltaPx: Float, widthPx: Float, canPrev: Boolean, canNext: Boolean) {
        if (isExiting || widthPx <= 0f) return
        if (!isDragging) {
            val cur = offsetAnim.value
            rawDragPx = cur / dampingFor(cur, canPrev, canNext); dragOffsetPx = cur; isDragging = true
            activeAnimJob?.cancel(); activeAnimJob = null
            if (offsetAnim.isRunning && scope.isActive) scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetAnim.stop() }
        }
        rawDragPx = (rawDragPx + deltaPx).coerceIn(-widthPx, widthPx)
        dragOffsetPx = rawDragPx * dampingFor(rawDragPx, canPrev, canNext)
    }

    fun onDragStopped(
        velocityX: Float, widthPx: Float, flingThresholdPx: Float, nearZeroPx: Float,
        neighborForStep: (Int) -> ForumListSource?, onCommitSwitch: (ForumListSource, Float) -> Unit,
    ) {
        if (isExiting || !isDragging) return
        val cur = dragOffsetPx
        val step = resolveForumSwipeStep(cur, velocityX, widthPx, flingThresholdPx, nearZeroPx)
        val neighbor = if (step != 0) neighborForStep(step) else null
        if (neighbor != null && widthPx > 0f) {
            isExiting = true; val exitSign = -step.toFloat()
            val exitVel = if (exitSign < 0f) velocityX.coerceAtMost(0f) else velocityX.coerceAtLeast(0f)
            if (reducedMotion() || !scope.isActive) { resetImmediate(); onCommitSwitch(neighbor, 0f) }
            else launchAnimation(cur, exitSign * widthPx, HiMotion.exit(HiMotion.spatialFast(visibilityThreshold = SWIPE_SNAP_EPSILON_PX)), exitVel, keepOnComplete = true) { onCommitSwitch(neighbor, step.toFloat()) }
        } else launchAnimation(cur, 0f, HiMotion.spatialSnap(visibilityThreshold = SWIPE_SNAP_EPSILON_PX), velocityX)
    }

    fun onDragCancelled() { if (!isExiting && isDragging) launchAnimation(dragOffsetPx, 0f, HiMotion.spatialSnap(visibilityThreshold = SWIPE_SNAP_EPSILON_PX), 0f) }

    fun resetImmediate() {
        pendingEnter = false; isExiting = false; isDragging = false; dragOffsetPx = 0f; rawDragPx = 0f
        activeAnimJob?.cancel(); activeAnimJob = null
        if (scope.isActive && offsetAnim.value != 0f) scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { offsetAnim.snapTo(0f) } }
    }

    private fun launchAnimation(
        snapStartPx: Float?, targetPx: Float, spec: AnimationSpec<Float>, velocity: Float,
        keepOnComplete: Boolean = false, onComplete: (() -> Unit)? = null,
    ) {
        activeAnimJob?.cancel()
        val start = snapStartPx ?: offsetAnim.value
        if (!scope.isActive || reducedMotion() || abs(start - targetPx) < SWIPE_SNAP_EPSILON_PX) {
            isDragging = false; dragOffsetPx = 0f; rawDragPx = 0f
            if (scope.isActive) activeAnimJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetAnim.snapTo(targetPx) }
            onComplete?.invoke(); return
        }
        activeAnimJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var completed = false
            try {
                if (snapStartPx != null) offsetAnim.snapTo(snapStartPx)
                isDragging = false; dragOffsetPx = 0f; rawDragPx = 0f
                offsetAnim.animateTo(targetValue = targetPx, animationSpec = spec, initialVelocity = velocity)
                completed = true; onComplete?.invoke()
            } finally {
                if (!completed) {
                    if (keepOnComplete) isExiting = false
                    if (!isDragging && !isExiting) {
                        isDragging = false; dragOffsetPx = 0f; rawDragPx = 0f
                        withContext(NonCancellable) { runCatching { offsetAnim.snapTo(0f) } }
                    }
                }
            }
        }
    }
}

internal fun Modifier.forumHorizontalSwipe(
    swipeState: ForumSwipeState, getWidthPx: () -> Float, getTopBarBottomPx: () -> Float,
    getNeighbor: (Int) -> ForumListSource?, onCommitSwitch: (ForumListSource, Float) -> Unit,
): Modifier = pointerInput(swipeState) {
    val touchSlop = viewConfiguration.touchSlop
    val flingPx = SWIPE_FLING_VELOCITY_THRESHOLD.toPx(); val nearZeroPx = SWIPE_NEAR_ZERO_OFFSET.toPx()
    val maxVel = Velocity(viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.y < getTopBarBottomPx()) return@awaitEachGesture
        val tracker = VelocityTracker().apply { addPointerInputChange(down) }
        var dragging = false; var settled = false; var lastX = down.position.x
        try {
            while (true) {
                val change = awaitPointerEvent(if (dragging) PointerEventPass.Initial else PointerEventPass.Main)
                    .changes.firstOrNull { it.id == down.id } ?: break
                if (!dragging) {
                    if (change.isConsumed || !change.pressed) break
                    tracker.addPointerInputChange(change)
                    val dx = change.position.x - down.position.x; val dy = change.position.y - down.position.y
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) break
                    if (abs(dx) <= touchSlop || abs(dx) <= abs(dy)) continue
                    val w = getWidthPx(); if (w <= 0f) break
                    dragging = true; change.consume(); lastX = change.position.x
                    swipeState.onDragDelta(dx - dx.sign * touchSlop, w, getNeighbor(-1) != null, getNeighbor(+1) != null)
                } else {
                    if (change.isConsumed) break
                    tracker.addPointerInputChange(change); change.consume()
                    if (!change.pressed) {
                        settled = true
                        swipeState.onDragStopped(tracker.calculateVelocity(maxVel).x, getWidthPx(), flingPx, nearZeroPx, getNeighbor, onCommitSwitch)
                        break
                    }
                    val stepDx = change.position.x - lastX; lastX = change.position.x
                    if (stepDx != 0f) swipeState.onDragDelta(stepDx, getWidthPx(), getNeighbor(-1) != null, getNeighbor(+1) != null)
                }
            }
        } finally { if (dragging && !settled) swipeState.onDragCancelled() }
    }
}
