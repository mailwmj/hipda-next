package net.jejer.hipda.ui.designsystem.feedback

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 反馈组件 Roborazzi 截图基线（411dp 宽，浅色与深色）：
 * - 带“撤销”动作的 Snackbar 浮在 64dp 模拟导航胶囊上方 8dp 处；
 * - 角标 `1`、`99`、`100` 三个值；
 * - 描线对勾画完的终态（及图标切换展示）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411,
)
class HiFeedbackScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun feedback_specimen_w411_light() = captureFeedbackSpecimen(
        name = "feedback_specimen_w411_light",
        themeMode = HiThemeMode.LIGHT,
    )

    @Test
    fun feedback_specimen_w411_dark() = captureFeedbackSpecimen(
        name = "feedback_specimen_w411_dark",
        themeMode = HiThemeMode.DARK,
    )

    private fun captureFeedbackSpecimen(
        name: String,
        themeMode: HiThemeMode,
    ) {
        composeRule.setContent {
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                FeedbackSpecimenScreen()
            }
        }
        composeRule.waitForIdle()

        // 校验 Snackbar 底边位于 64dp 模拟胶囊顶部上方 8dp 处（误差 <= 1dp）
        val capsuleBounds = composeRule.onNodeWithTag("mockCapsule").fetchSemanticsNode().boundsInRoot
        val snackbarBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .fetchSemanticsNode().boundsInRoot
        val gapDp = with(composeRule.density) { (capsuleBounds.top - snackbarBounds.bottom).toDp() }
        assertEquals(8f, gapDp.value, 1f)

        composeRule.onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun FeedbackSpecimenScreen() {
    val cs = MaterialTheme.colorScheme
    val hostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.feedback_action_undo)
    val capsuleBottomMargin = 8.dp
    val capsuleHeight = 64.dp
    val snackbarClearance = capsuleBottomMargin + capsuleHeight + 8.dp

    LaunchedEffect(hostState, undoLabel) {
        hostState.showSnackbar(
            message = "已收藏",
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "反馈组件样张",
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface,
                )

                // 角标 1 / 99 / 100
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HiShapeTokens.Medium)
                        .background(cs.surfaceContainerLow)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "未读角标（1 / 99 / 100）",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(1, 99, 100).forEach { count ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(HiShapeTokens.Medium)
                                        .background(cs.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Notifications,
                                        contentDescription = "消息",
                                        tint = cs.onSurfaceVariant,
                                    )
                                    HiCountBadge(
                                        count = count,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp),
                                    )
                                }
                                HiCountBadge(count = count)
                                Text(
                                    text = "count = $count",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 描线对勾终态 + 图标切换
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HiShapeTokens.Medium)
                        .background(cs.surfaceContainerLow)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "描线对勾终态与图标切换",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(HiShapeTokens.Full)
                                    .background(cs.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                HiStrokeCheck(
                                    visible = true,
                                    color = cs.onPrimaryContainer,
                                    contentDescription = "完成",
                                )
                            }
                            Text(
                                text = "对勾 24dp",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(HiShapeTokens.Medium)
                                    .background(cs.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                HiStrokeCheck(
                                    visible = true,
                                    color = cs.primary,
                                    strokeWidth = 3.dp,
                                    size = 36.dp,
                                    contentDescription = "完成放大预览",
                                )
                            }
                            Text(
                                text = "对勾 36dp",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(HiShapeTokens.Full)
                                    .background(cs.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                HiIconSwitch(
                                    target = Icons.Filled.Favorite,
                                    contentDescription = "已收藏",
                                    tint = cs.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = "图标切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 背景列表模拟，验证悬浮 Snackbar 与模拟胶囊不推挤内容
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HiShapeTokens.Medium)
                        .background(cs.surfaceContainerLow)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Discovery · 示例列表",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                    )
                    listOf(
                        "折叠屏展开后的阅读体验与排版细节记录",
                        "秋日随拍样张：城市建筑光影与色调控制",
                        "家庭局域网低功耗主机静音改造与测温",
                    ).forEachIndexed { idx, line ->
                        if (idx > 0) HorizontalDivider(color = cs.outlineVariant)
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            // 64dp 模拟导航胶囊（距底 8dp）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = capsuleBottomMargin)
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .height(capsuleHeight)
                    .shadow(
                        elevation = 4.dp,
                        shape = HiShapeTokens.Full,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.12f),
                    )
                    .clip(HiShapeTokens.Full)
                    .background(cs.surfaceContainerHigh)
                    .border(0.5.dp, cs.outlineVariant, HiShapeTokens.Full)
                    .testTag("mockCapsule"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("论坛", "消息", "搜索", "我的").forEachIndexed { idx, label ->
                        val bg = if (idx == 0) cs.secondaryContainer else Color.Transparent
                        val fg = if (idx == 0) cs.onSecondaryContainer else cs.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .clip(HiShapeTokens.Full)
                                .background(bg)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = fg,
                            )
                        }
                    }
                }
            }

            // 带“撤销”动作的 Snackbar，浮在 64dp 模拟胶囊上方 8dp 处
            HiSnackbarHost(
                hostState = hostState,
                bottomClearance = snackbarClearance,
            )
        }
    }
}
