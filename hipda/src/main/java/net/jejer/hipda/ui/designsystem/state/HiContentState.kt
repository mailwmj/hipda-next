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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

/** 页面内容区的统一加载状态模型。 */
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
 * 加载指示器防闪可见性状态（委托给 [LoadingVisibilityController]）：
 * - `passive = true`：150ms 后仍未结束才显示，至少停留 250ms，不受动画缩放 0 影响；
 *   通过 `rememberSaveable` 保存“本轮加载已显示”标记，宿主重建时若仍在加载立即保持显示。
 * - `passive = false`：同帧 0ms 同步返回 [isLoading]，无二次重组。
 */
@Composable
fun rememberLoadingIndicatorVisible(
    isLoading: Boolean,
    passive: Boolean = true,
): State<Boolean> {
    val scope = rememberCoroutineScope()
    val shownInCurrentLoad = rememberSaveable { BooleanArray(1) }
    val controller = remember(scope) {
        LoadingVisibilityController(
            scope = scope,
            initialVisible = isLoading && (!passive || shownInCurrentLoad[0]),
        )
    }
    val isLoadingState = rememberUpdatedState(isLoading)
    val passiveState = rememberUpdatedState(passive)
    val controllerVisibleState = produceState(initialValue = controller.visible.value, key1 = controller) {
        controller.visible.collect { visible ->
            value = visible
            shownInCurrentLoad[0] = isLoadingState.value && visible
        }
    }
    SideEffect {
        controller.update(isLoading = isLoading, passive = passive)
        shownInCurrentLoad[0] = isLoading && controller.visible.value
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    return remember(controllerVisibleState, isLoadingState, passiveState) {
        derivedStateOf { if (!passiveState.value) isLoadingState.value else controllerVisibleState.value }
    }
}

private enum class ContentVisualSlot { PENDING, SKELETON, EMPTY, ERROR, CONTENT }

private class ContentSlotCache<T> {
    var lastSettledSlot: ContentVisualSlot? = null
    var latestContent: HiLoadState.Content<T>? = null
    var latestError: HiLoadState.Error? = null
}

/**
 * 统一内容状态容器：首次加载受 [rememberLoadingIndicatorVisible] 延迟规则约束（150ms 内完成直接显示内容，
 * 不闪骨架；“重试”等用户主动触发的整页重载传 `passive = false`）；状态间以 `hiFadeIn()` / `hiFadeOut()`
 * 原地交叉淡入淡出，无位移、无尺寸跳动；`Content(value)` 数据增量更新不重建子树。
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
    val showSkeleton by rememberLoadingIndicatorVisible(state is HiLoadState.Loading, passive)
    val cache = remember { ContentSlotCache<T>() }
    when (state) {
        is HiLoadState.Content -> {
            cache.latestContent = state
            cache.lastSettledSlot = ContentVisualSlot.CONTENT
        }
        HiLoadState.Empty -> cache.lastSettledSlot = ContentVisualSlot.EMPTY
        is HiLoadState.Error -> {
            cache.latestError = state
            cache.lastSettledSlot = ContentVisualSlot.ERROR
        }
        HiLoadState.Loading -> Unit
    }
    if (showSkeleton) cache.lastSettledSlot = null

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
        transitionSpec = { (hiFadeIn() togetherWith hiFadeOut()).using(null) },
        contentAlignment = Alignment.TopStart,
        label = "HiContentState",
    ) { slot ->
        when (slot) {
            ContentVisualSlot.PENDING -> Spacer(Modifier)
            ContentVisualSlot.SKELETON -> skeleton()
            ContentVisualSlot.EMPTY, ContentVisualSlot.ERROR -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (slot == ContentVisualSlot.EMPTY) {
                    empty()
                } else {
                    ((state as? HiLoadState.Error) ?: cache.latestError)?.let { error(it) }
                }
            }
            ContentVisualSlot.CONTENT -> ((state as? HiLoadState.Content<T>) ?: cache.latestContent)?.let {
                content(it.value)
            }
        }
    }
}
