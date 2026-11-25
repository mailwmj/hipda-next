package net.jejer.hipda.ui.designsystem.state

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

/**
 * 页面内容区的统一加载状态模型。
 */
@Stable
sealed interface HiLoadState<out T> {
    data object Loading : HiLoadState<Nothing>

    @Stable
    data class Content<out T>(val value: T) : HiLoadState<T>

    data object Empty : HiLoadState<Nothing>

    @Immutable
    data class Error(
        val message: String,
        val details: String? = null,
        val retry: (() -> Unit)? = null,
    ) : HiLoadState<Nothing>
}

/**
 * 记住并驱动加载指示器的防闪可见性状态（委托给纯协程 [LoadingVisibilityController]）。
 *
 * - 被动加载（`passive = true`）：150ms 后仍未结束才显示，显示后至少停留 250ms；不受系统动画缩放为 0 影响。
 * - 主动操作（`passive = false`）：0ms 立即显示，结束立即隐藏。
 */
@Composable
fun rememberLoadingIndicatorVisible(
    isLoading: Boolean,
    passive: Boolean = true,
): State<Boolean> {
    val scope = rememberCoroutineScope()
    val state = remember { mutableStateOf(isLoading && !passive) }
    val controller = remember(scope) {
        LoadingVisibilityController(
            scope = scope,
            initialVisible = isLoading && !passive,
            onVisibilityChange = { state.value = it },
        )
    }
    SideEffect {
        controller.update(isLoading = isLoading, passive = passive)
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return state
}

private enum class ContentVisualSlot {
    PENDING,
    SKELETON,
    EMPTY,
    ERROR,
    CONTENT,
}

private class ContentSlotCache<T> {
    var lastSettledSlot: ContentVisualSlot? = null
    var latestContent: HiLoadState.Content<T>? = null
    var latestError: HiLoadState.Error? = null
}

/**
 * 统一内容状态容器：
 * - 首次加载受 [rememberLoadingIndicatorVisible] 延迟规则约束（150ms 内完成直接显示内容，不闪骨架）；
 * - 各状态之间原地交叉淡入淡出（[HiMotion.effectsDefault] / `exit(effectsDefault)`，无位移、无尺寸跳动）；
 * - 仅在状态类别切换时触发过渡，`Content(value)` 内部数据增量更新不重建子树。
 */
@Composable
fun <T> HiContentState(
    state: HiLoadState<T>,
    modifier: Modifier = Modifier,
    passive: Boolean = true,
    skeleton: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    error: @Composable (HiLoadState.Error) -> Unit,
    content: @Composable (T) -> Unit,
) {
    val showSkeleton by rememberLoadingIndicatorVisible(
        isLoading = state is HiLoadState.Loading,
        passive = passive,
    )
    val cache = remember { ContentSlotCache<T>() }

    when (state) {
        is HiLoadState.Content -> {
            cache.latestContent = state
            cache.lastSettledSlot = ContentVisualSlot.CONTENT
        }
        HiLoadState.Empty -> {
            cache.lastSettledSlot = ContentVisualSlot.EMPTY
        }
        is HiLoadState.Error -> {
            cache.latestError = state
            cache.lastSettledSlot = ContentVisualSlot.ERROR
        }
        HiLoadState.Loading -> Unit
    }

    if (showSkeleton) {
        cache.lastSettledSlot = null
    }

    val targetSlot = when {
        showSkeleton -> ContentVisualSlot.SKELETON
        state is HiLoadState.Content -> ContentVisualSlot.CONTENT
        state is HiLoadState.Empty -> ContentVisualSlot.EMPTY
        state is HiLoadState.Error -> ContentVisualSlot.ERROR
        else -> cache.lastSettledSlot ?: ContentVisualSlot.PENDING
    }

    AnimatedContent(
        targetState = targetSlot,
        modifier = modifier,
        transitionSpec = {
            (hiFadeIn(animationSpec = HiMotion.effectsDefault()) togetherWith
                hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsDefault())))
                .using(null)
        },
        contentAlignment = Alignment.TopStart,
        label = "HiContentState",
    ) { slot ->
        when (slot) {
            ContentVisualSlot.PENDING -> Spacer(Modifier)
            ContentVisualSlot.SKELETON -> skeleton()
            ContentVisualSlot.EMPTY -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                empty()
            }
            ContentVisualSlot.ERROR -> {
                val errorState = (state as? HiLoadState.Error) ?: cache.latestError
                if (errorState != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        error(errorState)
                    }
                }
            }
            ContentVisualSlot.CONTENT -> {
                val contentState = (state as? HiLoadState.Content<T>) ?: cache.latestContent
                if (contentState != null) {
                    content(contentState.value)
                }
            }
        }
    }
}
