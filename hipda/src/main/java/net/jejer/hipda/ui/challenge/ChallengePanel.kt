package net.jejer.hipda.ui.challenge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiScaleIn
import net.jejer.hipda.ui.designsystem.motion.hiScaleOut
import net.jejer.hipda.ui.designsystem.motion.hiSlideInVertically
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutVertically

/** 可见 Cloudflare 验证面板的尺寸与形态常量（product-design.md §4.1、§5、§7.12）。 */
@Immutable
object ChallengePanelDefaults {
    val DialogBreakpoint: Dp = 600.dp
    val NarrowHeaderBreakpoint: Dp = 360.dp
    const val NarrowHeaderFontScaleThreshold: Float = 1.5f
    val DialogMaxWidth: Dp = 480.dp
    val DialogMaxHeight: Dp = 560.dp
    const val SheetHeightFraction: Float = 0.78f
    const val DialogHeightFraction: Float = 0.80f
    const val ScrimAlpha: Float = 0.40f
}

private val SheetTopRoundedShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/**
 * 自适应可见验证面板（不使用毛玻璃，严格遵守 `HiMotion` 动效令牌）：
 * - 窗口宽度 `< 600dp`：底部面板（顶角 20dp，底边贴合导航栏安全区，`spatialDefault` 垂直升降）；
 * - 窗口宽度 `≥ 600dp`：居中对话框（最大宽 480dp，`HiShapeTokens.Large`，`spatialFast` + `effectsFast` 缩放淡入）。
 *
 * 将 WebView 区域抽象为 [webViewSlot]，在运行时承载同一运行中的 WebView 实例，在截图与动效测试中承载轻量占位。
 */
@Composable
fun ChallengePanelOverlay(
    visible: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissed: () -> Unit = {},
    webViewSlot: @Composable () -> Unit,
) {
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    val isDialog = windowWidth >= ChallengePanelDefaults.DialogBreakpoint
    val isNarrowLargeFont = !isDialog &&
        windowWidth < ChallengePanelDefaults.NarrowHeaderBreakpoint &&
        LocalDensity.current.fontScale >= ChallengePanelDefaults.NarrowHeaderFontScaleThreshold
    val transition = updateTransition(targetState = visible, label = "ChallengePanelOverlay")
    var everShown by remember { mutableStateOf(visible) }
    if (visible) {
        everShown = true
    }
    val currentOnDismissed by rememberUpdatedState(onDismissed)

    BackHandler(enabled = visible, onBack = onCancel)

    LaunchedEffect(transition.currentState, transition.targetState, transition.isRunning, everShown) {
        if (everShown && !transition.currentState && !transition.targetState && !transition.isRunning) {
            everShown = false
            currentOnDismissed()
        }
    }

    val panelEnter = if (isDialog) {
        hiScaleIn(animationSpec = HiMotion.spatialFast(), initialScale = 0.9f) +
            hiFadeIn(animationSpec = HiMotion.effectsFast())
    } else {
        hiSlideInVertically(animationSpec = HiMotion.spatialDefault()) { it }
    }
    val panelExit = if (isDialog) {
        hiScaleOut(animationSpec = HiMotion.exit(HiMotion.spatialFast()), targetScale = 0.9f) +
            hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsFast()))
    } else {
        hiSlideOutVertically(animationSpec = HiMotion.exit(HiMotion.spatialDefault())) { it }
    }

    Box(modifier = modifier.fillMaxSize()) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = hiFadeIn(animationSpec = HiMotion.effectsSlow()),
            exit = hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsSlow())),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = ChallengePanelDefaults.ScrimAlpha))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }

        val containerModifier = if (isDialog) {
            Modifier
                .align(Alignment.Center)
                .systemBarsPadding()
                .imePadding()
                .padding(24.dp)
                .widthIn(max = ChallengePanelDefaults.DialogMaxWidth)
                .fillMaxWidth()
                .heightIn(max = ChallengePanelDefaults.DialogMaxHeight)
                .fillMaxHeight(ChallengePanelDefaults.DialogHeightFraction)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(ChallengePanelDefaults.SheetHeightFraction)
        }

        transition.AnimatedVisibility(
            visible = { it },
            modifier = containerModifier,
            enter = panelEnter,
            exit = panelExit,
        ) {
            val panelTitle = stringResource(R.string.challenge_panel_title)
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        paneTitle = panelTitle
                        isTraversalGroup = true
                    },
                shape = if (isDialog) HiShapeTokens.Large else SheetTopRoundedShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = if (isDialog) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxSize().navigationBarsPadding().imePadding()
                    },
                ) {
                    ChallengePanelHeader(
                        title = panelTitle,
                        isNarrowLargeFont = isNarrowLargeFont,
                        onRefresh = onRefresh,
                        onCancel = onCancel,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        webViewSlot()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengePanelHeader(
    title: String,
    isNarrowLargeFont: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    if (isNarrowLargeFont) {
        // 极端窄屏 + 大字号（如 340dp + 2x）：标题与动作按钮置顶行，副行独占整宽避免截断且控制顶栏总高不挤占 WebView。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChallengePanelTitle(title = title, modifier = Modifier.weight(1f))
                ChallengePanelActions(onRefresh = onRefresh, onCancel = onCancel, horizontalPadding = 8.dp)
            }
            Text(
                text = stringResource(R.string.challenge_panel_subtitle),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChallengePanelTitle(title = title)
            Text(
                text = stringResource(R.string.challenge_panel_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChallengePanelActions(onRefresh = onRefresh, onCancel = onCancel)
    }
}

@Composable
private fun ChallengePanelTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChallengePanelActions(
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    horizontalPadding: Dp = 12.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChallengePanelActionButton(
            label = stringResource(R.string.challenge_action_refresh),
            onClick = onRefresh,
            horizontalPadding = horizontalPadding,
        )
        ChallengePanelActionButton(
            label = stringResource(R.string.challenge_action_cancel),
            onClick = onCancel,
            horizontalPadding = horizontalPadding,
        )
    }
}

@Composable
private fun ChallengePanelActionButton(
    label: String,
    onClick: () -> Unit,
    horizontalPadding: Dp = 12.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        shape = HiShapeTokens.Small,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .hiPressScale(interactionSource),
    ) {
        Text(text = label, maxLines = 1)
    }
}
