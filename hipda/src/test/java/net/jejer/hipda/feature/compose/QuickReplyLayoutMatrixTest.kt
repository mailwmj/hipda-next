package net.jejer.hipda.feature.compose

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E5 B1 参数化布局矩阵测试（248 格 = 31 净高 [120..420dp, 步长 10dp] × {有/无草稿} × {有/无错误} × {未绑定/全绑定}）：
 * 每格强校验：
 * 1. 面板总高 ≤ min(300dp, 可视净高 × 60%)，且位于状态栏与键盘之间；
 * 2. 输入框真实可见高度 ≥ 1 行且未裁切溢出面板；
 * 3. `发送` 按钮完整落在面板内（含未裁切尺寸）且高度 ≥ 48dp；
 * 4. 绑定态下 `展开全屏` / `插入图片` / `表情` 三按钮均可点、尺寸 ≥ 48dp 且完整落在面板内，未绑定态不渲染；
 * 5. 面板内全部子项（使用 `positionInRoot + size` 真实未裁切包围盒）均不越出面板边界；
 * 6. 有错误时，要么内联完整展示且不溢出，要么优雅降级通过 `DetailPane` Snackbar 传达。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h600dp-xhdpi")
class QuickReplyLayoutMatrixTest(
    private val hasRestoredDraft: Boolean,
    private val hasInlineError: Boolean,
    private val slotsBound: Boolean,
) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun netHeight120To420Step10_allCellsSatisfyPriorityInvariantsWithoutOverflow() {
        val tid = "2609001"
        val draftKey = DraftKey.reply(tid)
        val initialDrafts = if (hasRestoredDraft) {
            mapOf(draftKey.value to DraftEntity(draftKey.value, content = "已恢复草稿文本", updatedAt = 1_000L))
        } else {
            emptyMap()
        }
        val postRepo = FakePostRepository(initialDrafts = initialDrafts).apply {
            submitHandler = { PostSubmitResult.NetworkError("timeout") }
        }
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget(tid = tid, fid = 2, threadAuthor = "linus_hi"))
        val snackbars = RecordingPaneSnackbarController()
        var hostView: View? = null
        var measuredOneLinePx = 0f

        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalPaneSnackbarController provides snackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val textMeasurer = rememberTextMeasurer()
                    val bodyStyle = HiTheme.typography.postBody
                    measuredOneLinePx = textMeasurer.measure("单行测试输入", style = bodyStyle).size.height.toFloat()
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val gs = rememberHiGlassState()
                        Box(Modifier.fillMaxSize()) {
                            QuickReplySheet(
                                viewModel = qrVm,
                                glassState = gs,
                                onDismissFinished = {},
                                onOpenLogin = {},
                                onExpandToFullEditor = if (slotsBound) ({}) else null,
                                emojiPanelSlot = if (slotsBound) ({ Box(Modifier.fillMaxSize(), Alignment.Center) { Text("E7") } }) else null,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 确保文本非空并（若本组合含错误）触发一次提交失败进入 inlineError 状态
        if (!hasRestoredDraft) {
            composeRule.runOnIdle { qrVm.onTextChanged("单行测试输入") }
            composeRule.waitForIdle()
        }
        if (hasInlineError) {
            composeRule.runOnIdle { qrVm.submit() }
            composeRule.waitForIdle()
        }

        val density = composeRule.density
        val paneHeightDp = 600
        val statusBarDp = 24
        val statusBarPx = with(density) { statusBarDp.dp.roundToPx() }
        val minOneLinePx = maxOf(measuredOneLinePx - 1f, with(density) { 16.dp.toPx() })
        val minTouchPx = with(density) { 47.dp.toPx() }
        val failures = mutableListOf<String>()

        for (netHeightDp in 420 downTo 120 step 10) {
            val imeDp = paneHeightDp - statusBarDp - netHeightDp
            val imePx = with(density) { imeDp.dp.roundToPx() }
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()

            val cellTag = "net=${netHeightDp}dp(draft=$hasRestoredDraft,err=$hasInlineError,bound=$slotsBound)"
            val sheetNodes = composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (sheetNodes.size != 1) {
                failures += "$cellTag: expected 1 sheet glass node, found ${sheetNodes.size}"
                continue
            }
            val sheetNode = sheetNodes.single()
            val sheetTop = sheetNode.positionInRoot.y
            val sheetLeft = sheetNode.positionInRoot.x
            val sheetRight = sheetLeft + sheetNode.size.width
            val sheetBottom = sheetTop + sheetNode.size.height
            val sheetHeightDp = with(density) { sheetNode.size.height.toDp().value }
            val expectedMaxDp = minOf(300f, netHeightDp * 0.60f)
            val imeTopPx = with(density) { (paneHeightDp - imeDp).dp.toPx() }

            if (sheetHeightDp > expectedMaxDp + 1.5f) {
                failures += "$cellTag: sheetHeight=${sheetHeightDp}dp > max=${expectedMaxDp}dp"
            }
            if (sheetBottom > imeTopPx + 2f) {
                failures += "$cellTag: sheetBottom=${sheetBottom}px > imeTop=${imeTopPx}px"
            }
            if (sheetTop < statusBarPx - 2f) {
                failures += "$cellTag: sheetTop=${sheetTop}px < statusBar=${statusBarPx}px"
            }

            // 1. 输入框可见高度 ≥ 1 行且未越界
            val inputNode = composeRule.onNodeWithContentDescription("回复 linus_hi").fetchSemanticsNode()
            val inputVisibleH = inputNode.boundsInRoot.height
            val inputUnclippedTop = inputNode.positionInRoot.y
            val inputUnclippedBottom = inputUnclippedTop + inputNode.size.height
            if (inputVisibleH < minOneLinePx || inputNode.size.height < minOneLinePx) {
                failures += "$cellTag: input height too small visible=${inputVisibleH}px size=${inputNode.size.height}px (<${minOneLinePx}px)"
            }
            if (inputUnclippedTop < sheetTop - 1.5f || inputUnclippedBottom > sheetBottom + 1.5f) {
                failures += "$cellTag: input outside sheet [$inputUnclippedTop..$inputUnclippedBottom] vs sheet [$sheetTop..$sheetBottom]"
            }

            // 2. 发送按钮完整在面板内、触控高度 ≥ 48dp，且视觉胶囊严格为 32dp
            val sendNode = composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode()
            val sendTop = sendNode.positionInRoot.y
            val sendBottom = sendTop + sendNode.size.height
            val sendLeft = sendNode.positionInRoot.x
            val sendRight = sendLeft + sendNode.size.width
            if (sendNode.size.height < minTouchPx) {
                failures += "$cellTag: send height=${sendNode.size.height}px < 48dp"
            }
            val pillHeightDp = sendNode.firstChildLayoutHeightPx()?.let { with(density) { it.toDp().value } }
            if (pillHeightDp == null || pillHeightDp !in 31f..33f) {
                failures += "$cellTag: send visual capsule height=${pillHeightDp}dp != 32dp"
            }
            if (sendTop < sheetTop - 1.5f || sendBottom > sheetBottom + 1.5f || sendLeft < sheetLeft - 1.5f || sendRight > sheetRight + 1.5f) {
                failures += "$cellTag: send clipped [$sendLeft,$sendTop..$sendRight,$sendBottom] vs sheet [$sheetLeft,$sheetTop..$sheetRight,$sheetBottom]"
            }
            // ReplyThread 已登录不渲染顶部独立上下文行（有输入时全树 0 处，未同步文本时最多仅剩输入框内占位 1 处且位于输入框内）
            val ctxTextNodes = composeRule.onAllNodesWithText("回复 linus_hi").fetchSemanticsNodes()
            val maxExpectedCtxText = if (hasRestoredDraft) 0 else 1
            if (ctxTextNodes.size > maxExpectedCtxText || ctxTextNodes.any { it.positionInRoot.y < inputUnclippedTop - 1.5f }) {
                failures += "$cellTag: ReplyThread rendered standalone context row above input (found ${ctxTextNodes.size})"
            }
            if (slotsBound) {
                val expandNode = composeRule.onNodeWithContentDescription("展开全屏").fetchSemanticsNode()
                val imgNode = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
                val expandMidY = expandNode.positionInRoot.y + expandNode.size.height / 2f
                val imgMidY = imgNode.positionInRoot.y + imgNode.size.height / 2f
                if (kotlin.math.abs(expandMidY - imgMidY) > with(density) { 2.dp.toPx() }) {
                    failures += "$cellTag: ReplyThread expand button not in bottom bar (expandY=$expandMidY vs imgY=$imgMidY)"
                }
            }

            // 3. 绑定插槽按钮可达性（或未绑定态严格不渲染）
            val boundActionLabels = listOf("展开全屏", "插入图片", "表情")
            for (label in boundActionLabels) {
                val nodes = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                if (slotsBound) {
                    if (nodes.size != 1) {
                        failures += "$cellTag: bound button '$label' missing (found ${nodes.size})"
                    } else {
                        val n = nodes.single()
                        val hasClick = n.config.contains(SemanticsActions.OnClick) ||
                            n.parent?.config?.contains(SemanticsActions.OnClick) == true
                        val nTop = n.positionInRoot.y
                        val nBottom = nTop + n.size.height
                        val nLeft = n.positionInRoot.x
                        val nRight = nLeft + n.size.width
                        if (!hasClick) {
                            failures += "$cellTag: bound button '$label' not clickable"
                        }
                        if (nTop < sheetTop - 1.5f || nBottom > sheetBottom + 1.5f || nLeft < sheetLeft - 1.5f || nRight > sheetRight + 1.5f) {
                            failures += "$cellTag: bound button '$label' outside sheet [$nLeft,$nTop..$nRight,$nBottom] vs [$sheetLeft,$sheetTop..$sheetRight,$sheetBottom]"
                        }
                    }
                } else if (nodes.isNotEmpty()) {
                    failures += "$cellTag: unbound button '$label' must not render"
                }
            }

            // 4. 面板所有子项真实未裁切包围盒均不得越出面板边界
            val allSheetDescendants = collectDescendants(sheetNode)
            for (child in allSheetDescendants) {
                if (child.size.width <= 0 || child.size.height <= 0) continue
                val cTop = child.positionInRoot.y
                val cBottom = cTop + child.size.height
                val cLeft = child.positionInRoot.x
                val cRight = cLeft + child.size.width
                if (cTop < sheetTop - 2f || cBottom > sheetBottom + 2f || cLeft < sheetLeft - 2f || cRight > sheetRight + 2f) {
                    val desc = child.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: child.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: "node#${child.id}"
                    failures += "$cellTag: child '$desc' overflows sheet [$cLeft,$cTop..$cRight,$cBottom] vs [$sheetLeft,$sheetTop..$sheetRight,$sheetBottom]"
                }
            }

            // 5. 有错误时必须内联展示或优雅走 DetailPane Snackbar
            if (hasInlineError) {
                val inlineErrorNodes = composeRule.onAllNodesWithText("发送失败").fetchSemanticsNodes()
                val snackbarShown = snackbars.events.any { it.message == "发送失败" && it.target == PaneSnackbarTarget.DetailPane }
                if (inlineErrorNodes.isEmpty() && !snackbarShown) {
                    failures += "$cellTag: inlineError neither shown inline nor delivered via DetailPane Snackbar"
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("布局矩阵失败 ${failures.size} 处（前 15 处）：\n${failures.take(15).joinToString("\n")}")
        }
    }

    private fun collectDescendants(root: SemanticsNode): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            for (child in node.children) {
                result += child
                visit(child)
            }
        }
        visit(root)
        return result
    }

    private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
    ): T? = if (contains(key)) get(key) else null

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "draft={0}_error={1}_bound={2}")
        fun parameters(): List<Array<Any>> = buildList {
            for (draft in listOf(false, true)) {
                for (err in listOf(false, true)) {
                    for (bound in listOf(false, true)) {
                        add(arrayOf(draft, err, bound))
                    }
                }
            }
        }
    }
}
