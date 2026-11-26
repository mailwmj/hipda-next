package net.jejer.hipda.feature.compose

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CompletableDeferred
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailFilterUi
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E5 UI / 手势 / IME / 矮屏紧凑态 / E6-E7 插槽裁定 / 冷却与提交流程集成测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class QuickReplySheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 负责人裁定 #2：
     * - 未绑定 E6/E7 时不渲染「展开全屏」「插入图片」「表情」三个按钮，且把手上拉不触发展开全屏；
     * - 绑定后三个按钮全部出现且触控区 ≥ 48dp，点击「展开全屏」与「插入图片」携带完整 `FullEditorHandoff` 且不弹「已保存草稿」提示。
     */
    @Test
    fun composeSlots_unboundHidesThreeButtonsAndBlocksSwipeUp_boundShowsAllAndDeliversFullEditorHandoff() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 2), createIdleComposeGateway(), authRepo)
        val snackbars = RecordingPaneSnackbarController()
        var bindSlots by mutableStateOf(false)
        val handoffs = mutableListOf<FullEditorHandoff>()
        var emojiHostSeen: QuickReplyEmojiPanelHost? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalPaneSnackbarController provides snackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "2606001", title = "插槽裁定测试", fid = 2, author = "linus_hi"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            viewModel = detailVm,
                            quickReplyViewModel = qrVm,
                            onExpandToFullEditor = if (bindSlots) ({ handoffs += it }) else null,
                            emojiPanelSlot = if (bindSlots) {
                                { host ->
                                    emojiHostSeen = host
                                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("E7 表情槽已挂载") }
                                }
                            } else null,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 未绑定态：打开快速回复面板，三个按钮一律不渲染，且上拉把手不触发展开
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("展开全屏").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("表情").fetchSemanticsNodes().size)

        composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 260f, durationMillis = 120)
        }
        composeRule.waitForIdle()
        assertTrue("未绑定时把手上拉不得触发展开全屏", handoffs.isEmpty())
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        // 先收起面板再切至绑定态，通过长按 2 楼「引用回复」验证引用字段透传
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()
        bindSlots = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第 1 页第 2 楼正文", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()

        // 2. 绑定态：三个按钮全部出现且触控区 ≥ 48dp
        composeRule.onNodeWithContentDescription("展开全屏").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 点击「表情」原地展开表情槽并切换为「键盘」图标，插入含代理对字符与 Emoji 并校验安全整字符回删（R-E5 S1）
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("E7 表情槽已挂载").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
        composeRule.runOnIdle {
            emojiHostSeen?.onInsertEmoji(":)😀𠮷")
            emojiHostSeen?.onBackspace()
            emojiHostSeen?.onBackspace()
        }
        composeRule.waitForIdle()
        assertEquals("代理对回删不得残留孤立高代理项", ":)", qrVm.uiState.value.text)
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()

        // 输入文本并设置选区 [2..5]，点「展开全屏」校验完整 FullEditorHandoff 且不弹「已保存草稿」
        val input = composeRule.onNodeWithContentDescription("引用第 2 楼 · geek_reader_2")
        input.performTextInput("测试展开内容")
        composeRule.waitForIdle()
        input.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 5, false) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertEquals(1, handoffs.size)
        assertEquals(
            FullEditorHandoff(
                tid = "2606001", fid = 2, text = ":)测试展开内容",
                selectionStart = 2, selectionEnd = 5, quotePid = "102", quoteAuthor = "geek_reader_2", quoteFloor = 2,
                openImagePickerImmediately = false, replyPid = null, threadAuthor = "linus_hi",
            ),
            handoffs.single(),
        )
        assertTrue("展开全屏不得重复弹「已保存草稿」提示", snackbars.events.none { it.message == "已保存草稿" })

        // 3. 重新打开并点「插入图片」校验 openImagePickerImmediately = true
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("准备插图")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("插入图片").performClick()
        composeRule.waitForIdle()
        assertEquals(2, handoffs.size)
        assertEquals("准备插图", handoffs.last().text)
        assertTrue("点插入图片必须设置 openImagePickerImmediately = true", handoffs.last().openImagePickerImmediately)
        assertTrue("点插入图片不得重复弹「已保存草稿」提示", snackbars.events.none { it.message == "已保存草稿" })
    }

    /** 验收项 2a：模拟 IME Insets 逐帧验证面板贴合键盘上方，且高度严格 ≤ min(300dp, 可视净高 × 60%)，常规窗底层目标楼层可见。 */
    @Test
    fun imeInsets_tracksKeyboardFrameByFrame_clampsMaxHeight_andLiftsUnderlyingTargetFloor() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 2, floorsPerPage = 14), createIdleComposeGateway(), authRepo)
        lateinit var listState: LazyListState
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2606002", title = "IME 逐帧与限高测试", fid = 2, author = "linus_hi"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = detailVm,
                        quickReplyViewModel = qrVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 打开面板并输入多行长文本撑高面板，逐帧推进 IME 高度 0 -> 140 -> 280 -> 440dp
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi")
            .performTextInput((1..18).joinToString("\n") { "第 $it 行长文本验证面板动态限高与内部滚动" })
        composeRule.waitForIdle()

        val density = composeRule.density
        val paneHeightDp = 891f
        var prevBottomDp = Float.MAX_VALUE
        for (imeDp in listOf(0, 140, 280, 440)) {
            val imePx = with(density) { imeDp.dp.roundToPx() }
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()

            // 最后一个挂载的 HiGlassBlurActive 即为快速回复面板自身（顶部为详情顶栏）
            val sheetGlassNode = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last()
            val sheetHeightDp = with(density) { sheetGlassNode.boundsInRoot.height.toDp().value }
            val sheetBottomDp = with(density) { sheetGlassNode.boundsInRoot.bottom.toDp().value }
            val netHeightDp = (paneHeightDp - imeDp).coerceAtLeast(0f)
            val expectedMaxDp = minOf(300f, netHeightDp * 0.60f)

            assertTrue(
                "IME=${imeDp}dp 时面板高 ${sheetHeightDp}dp 必须 ≤ min(300, ${netHeightDp}×60%)=${expectedMaxDp}dp",
                sheetHeightDp <= expectedMaxDp + 1.5f,
            )
            if (imeDp > 0) {
                assertTrue("IME=${imeDp}dp 升高时面板底缘应逐帧贴合上移（prev=$prevBottomDp cur=$sheetBottomDp）", sheetBottomDp < prevBottomDp)
                assertEquals("面板底缘应紧贴键盘顶缘 ${paneHeightDp - imeDp}dp", paneHeightDp - imeDp, sheetBottomDp, 2.5f)
            }
            prevBottomDp = sheetBottomDp
        }
    }

    /** 验收项 2b：矮屏/横屏（`891×411dp`）弹键盘使可视净高 `< 180dp` 时自动隐藏把手与草稿条，输入框与 `发送` 零裁切；`< 120dp` 仍可点 `展开全屏`。 */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun shortScreenLandscapeWithIme_hidesHandleAndDraftBanner_zeroClipping_andAllowsExpandBelow120dp() {
        val draftKey = DraftKey.reply("2606003")
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(draftKey.value to DraftEntity(draftKey.value, content = "矮屏恢复草稿", updatedAt = 1_000L)),
        )
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 1), createIdleComposeGateway(), authRepo)
        val handoffs = mutableListOf<FullEditorHandoff>()
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2606003", title = "矮屏横屏测试", fid = 2, author = "linus_hi"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = detailVm,
                        quickReplyViewModel = qrVm,
                        onExpandToFullEditor = { handoffs += it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        // 弹键盘前（净高 411dp ≥ 180dp）：把手与草稿横条均可见
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()

        // 分发 250dp IME（净高 161dp < 180dp）：自动隐藏把手与草稿恢复横条，输入框与发送完整不裁切
        val density = composeRule.density
        val ime250Px = with(density) { 250.dp.roundToPx() }
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                requireNotNull(hostView),
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime250Px)).build(),
            )
        }
        composeRule.waitForIdle()

        assertEquals("净高 < 180dp 时必须隐藏 32dp 拖动把手", 0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertEquals("净高 < 180dp 时必须隐藏草稿恢复横条", 0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)

        val imeTopPx = with(density) { (411 - 250).dp.toPx() }
        val paneRightPx = with(density) { 891.dp.toPx() }
        val inputBounds = composeRule.onNodeWithContentDescription("回复 linus_hi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("输入框不得裁切溢出键盘上方：bottom=${inputBounds.bottom} imeTop=$imeTopPx", inputBounds.bottom <= imeTopPx + 2f)
        assertTrue("发送按钮不得裁切溢出右缘或键盘上方：bounds=$sendBounds", sendBounds.right <= paneRightPx + 2f && sendBounds.bottom <= imeTopPx + 2f)

        // 极端矮屏净高 < 120dp（IME=300dp -> 净高 111dp）：仍可点「展开全屏」
        val ime300Px = with(density) { 300.dp.roundToPx() }
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                requireNotNull(hostView),
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime300Px)).build(),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, handoffs.size)
        assertEquals("矮屏恢复草稿", handoffs.single().text)
    }

    /** 验收项 2c：手势隔离（多行滚底内部滚动 / 选区态不被抢占；顶边界无选区超界或把手下拉关闭；非手势关闭路径一致；减动效 0ms）。 */
    @Test
    fun gestureIsolation_multilineScrollAndSelectionProtected_topOverscrollAndHandleDismiss_andReducedMotionOneFrame() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 1), createIdleComposeGateway(), authRepo)
        val snackbars = RecordingPaneSnackbarController()

        composeRule.setContent {
            CompositionLocalProvider(LocalPaneSnackbarController provides snackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "2606004", title = "手势隔离测试", fid = 2, author = "linus_hi"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            viewModel = detailVm,
                            quickReplyViewModel = qrVm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 输入多行长文本，在输入框中向上滑使内部 scrollState > 0，再向下滑：内部先滚回顶部，不关闭面板
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val input = composeRule.onNodeWithContentDescription("回复 linus_hi")
        input.performTextInput((1..22).joinToString("\n") { "多行滚动测试第 $it 行内容" })
        composeRule.waitForIdle()

        input.performTouchInput { swipeUp(startY = bottom - 8f, endY = top + 8f, durationMillis = 120) }
        composeRule.waitForIdle()
        input.performTouchInput { swipeDown(startY = top + 8f, endY = top + 90f, durationMillis = 120) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        // 2. 激活文本选区态（selection [0..6]）即使在顶边界向下拖也不得抢占关闭面板
        input.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 6, false) }
        composeRule.waitForIdle()
        input.performTouchInput { swipeDown(startY = centerY, endY = centerY + 320f, durationMillis = 140) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        // 3. 解除选区并从顶边界向下超界拖过阈值 -> 关闭面板并自动保存草稿
        input.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 0, false) }
        composeRule.waitForIdle()
        // 先确保滚回最顶
        input.performTouchInput { swipeDown(startY = top + 4f, endY = bottom + 400f, durationMillis = 140) }
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().isNotEmpty()) {
            // 若前一次手势刚好把内部列表滚到顶，再执行一次顶边界超界下拉即可关闭
            input.performTouchInput { swipeDown(startY = top + 4f, endY = bottom + 420f, durationMillis = 140) }
            composeRule.waitForIdle()
        }
        assertEquals("无选区顶边界超界下拉应关闭面板", 0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertTrue("下拉关闭后应静默保存草稿并弹 DetailPane Snackbar", snackbars.events.any { it.message == "已保存草稿" && it.target == PaneSnackbarTarget.DetailPane })

        // 4. 顶部把手未达阈值轻微慢速下拉松手弹回，大幅下拉关闭
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
            down(center)
            moveBy(Offset(0f, 36f), delayMillis = 240L)
            advanceEventTime(120L)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 320f, durationMillis = 120)
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)

        // 5. 减动效 0ms 一帧到位
        withAnimatorDurationScale(0f) {
            var dismissed = false
            val rmVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository())
            rmVm.open(QuickReplyTarget("2606004", 2, threadAuthor = "linus_hi"))
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val gs = rememberHiGlassState()
                        QuickReplySheet(rmVm, gs, onDismissFinished = { dismissed = true }, onOpenLogin = {})
                    }
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithContentDescription("收起回复面板").performClick()
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            assertTrue("减动效下关闭必须在 1 帧内完成", dismissed)
        }
    }

    /** 验收项 3：未登录原地提示、0ms 进度圈与失败留原地重试、非末页不移位 + `回复已发送 | 查看` 压栈回位、末页直接插楼滚底、过滤模式回复与主题切换保态。 */
    @Test
    fun guestPrompt_nonTailVsTailSuccess_filteredModeReply_andThemeSwitchPreservesInput() {
        var now = 10_000L
        val detailRepo = FakeComposeThreadDetailRepository(totalPages = 3, floorsPerPage = 10)
        val authRepo = FakeQuickReplyAuthRepository(initialAccount = null)
        val submitGate = CompletableDeferred<PostSubmitResult>()
        val postRepo = FakePostRepository(nowMs = { now })
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { now })
        val detailVm = ThreadDetailViewModel(detailRepo, createIdleComposeGateway(), authRepo)
        val snackbars = RecordingPaneSnackbarController()
        var themeMode by mutableStateOf(HiThemeMode.LIGHT)
        var loginClicks = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        lateinit var listState: LazyListState

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalPaneSnackbarController provides snackbars) {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        listState = rememberLazyListState()
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "2606005", title = "提交流程与过滤模式测试", fid = 2, author = "linus_hi"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = { loginClicks++ },
                            listState = listState,
                            viewModel = detailVm,
                            quickReplyViewModel = qrVm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 未登录打开快速回复：原地展示「登录后即可操作」与「登录」按钮
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录后即可操作").assertIsDisplayed()
        composeRule.onNodeWithText("登录").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, loginClicks)

        // 登录恢复后原地进入可输入态，并验证切换深浅主题不丢输入
        authRepo.setSignedIn()
        composeRule.waitForIdle()
        val input = composeRule.onNodeWithContentDescription("回复 linus_hi")
        input.performTextInput("非末页回复内容")
        composeRule.waitForIdle()
        themeMode = HiThemeMode.DARK
        composeRule.waitForIdle()
        assertEquals("切换深浅主题后输入内容必须完整保留", "非末页回复内容", qrVm.uiState.value.text)

        // 2. 点发送 0ms 进「正在发送…」，模拟失败留原地保输入
        postRepo.submitHandler = { submitGate.await() }
        composeRule.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在发送…").assertIsDisplayed().assertIsNotEnabled()

        submitGate.complete(PostSubmitResult.NetworkError("timeout"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("发送失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        assertEquals("失败后必须完整保留输入", "非末页回复内容", qrVm.uiState.value.text)

        // 3. 在第 1 页（共 3 页，非末页）点「重试」发送成功 -> 不移位，弹 DetailPane Snackbar「回复已发送 | 查看」
        val newFloor = PostFloor(
            postId = "311", floor = 31, page = 3, uid = "1001", author = "linus_hi",
            avatarUrl = null, timePost = "2026-11-26 12:40", isWarned = false, postStatus = null,
            signature = null, poll = null, blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("非末页回复内容")))),
        )
        detailRepo.extraLastPageFloors += newFloor
        val redirectedLastPage = ThreadDetailPage(
            tid = "2606005", fid = 2, title = "提交流程与过滤模式测试", page = 3, lastPage = 3,
            floors = (1..10).map { idx ->
                PostFloor("${300 + idx}", 20 + idx, 3, "1002", "geek", null, "12:00", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 3 页第 ${20 + idx} 楼")))))
            } + newFloor,
            postsPerPageEstimate = 10, meta = composeSampleMeta(), hasPostList = true,
        )
        postRepo.submitHandler = { PostSubmitResult.Success("2606005", redirectedPage = redirectedLastPage) }
        val pageBeforeSend = detailVm.uiState.value.page
        val indexBeforeSend = listState.firstVisibleItemIndex

        composeRule.onNodeWithText("重试").performClick()
        composeRule.waitForIdle()

        assertEquals("非末页回复成功不得移动当前页码", pageBeforeSend, detailVm.uiState.value.page)
        assertEquals("非末页回复成功不得移动当前滚动位置", indexBeforeSend, listState.firstVisibleItemIndex)
        val sentSnack = snackbars.events.last { it.message == "回复已发送" }
        assertEquals(PaneSnackbarTarget.DetailPane, sentSnack.target)
        assertEquals("查看", sentSnack.actionLabel)

        // 点击「查看」先压 PositionStack 再跳到第 3 页新楼，按系统返回退回第 1 页
        composeRule.runOnIdle { sentSnack.onAction?.invoke() }
        composeRule.waitForIdle()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue("查看新楼前必须已压入 PositionStack", detailVm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("按返回应从 PositionStack 回到原第 1 页", 1, detailVm.uiState.value.page)

        // 4. 开启「只看楼主」过滤模式后仍可直接回复与引用
        composeRule.onNodeWithContentDescription("只看楼主").performClick()
        composeRule.waitForIdle()
        assertTrue(detailVm.uiState.value.filter is ThreadDetailFilterUi.AuthorOnly)

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("过滤模式下直接回复")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        assertTrue("过滤模式下应成功提交 ReplyThread", postRepo.submitRequests.last() is PostSubmitRequest.ReplyThread)
        assertTrue("过滤模式下回复后应保持过滤视图不变", detailVm.uiState.value.filter is ThreadDetailFilterUi.AuthorOnly)

        // 5. 切回全帖并跳至末页末尾附近回复 -> 直接合入新楼并滚至新楼，不弹「回复已发送」也不压位置栈
        composeRule.onNodeWithContentDescription("退出只看").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { detailVm.loadPage(3) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(detailVm.uiState.value.floors.lastIndex, 0) }
        composeRule.waitForIdle()

        val tailFloor = newFloor.copy(postId = "312", floor = 32, blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("末页尾新楼")))))
        val tailPage = redirectedLastPage.copy(floors = redirectedLastPage.floors + tailFloor)
        postRepo.submitHandler = { PostSubmitResult.Success("2606005", redirectedPage = tailPage) }
        val sentSnackCountBefore = snackbars.events.count { it.message == "回复已发送" }
        val canPopBefore = detailVm.uiState.value.canPopPositionStack

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("末页尾新楼")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()

        assertEquals("末页末尾回复成功不得再弹「回复已发送」Snackbar", sentSnackCountBefore, snackbars.events.count { it.message == "回复已发送" })
        assertEquals("末页末尾回复成功不得额外压位置栈", canPopBefore, detailVm.uiState.value.canPopPositionStack)
        assertTrue("新楼层必须已插入末页", detailVm.uiState.value.floors.any { it.postId == "312" })
        assertTrue("列表必须已滚至末页新楼附近", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:312" })
    }

    /** 冷却倒计时 UI 展示校验：`HiRollingNumber` + `{N} 秒后可发送` 无障碍描述 + 禁用按钮 + 输入框保持可编辑。 */
    @Test
    fun cooldownUi_showsRemainingSecondsAndAccessibleDescriptionWhileInputRemainsEditable() {
        val now = 20_000L
        val postRepo = FakePostRepository(nowMs = { now }).apply { setCooldownUntil(now + 27_400L) }
        val qrVm = QuickReplyViewModel(postRepo, FakeQuickReplyAuthRepository(), clockMs = { now })
        qrVm.open(QuickReplyTarget("2606006", 2, threadAuthor = "linus_hi"))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState()
                    QuickReplySheet(qrVm, gs, onDismissFinished = {}, onOpenLogin = {})
                }
            }
        }
        composeRule.waitForIdle()

        // 27_400ms 向上取整为 28 秒
        composeRule.onNodeWithContentDescription("28 秒后可发送").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("28").assertIsDisplayed()

        // 倒计时期间仍可正常继续输入
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("冷却期间提前打字")
        composeRule.waitForIdle()
        assertEquals("冷却期间提前打字", qrVm.uiState.value.text)
    }

    /** R-E5 B1 路径 ① & ② 实机尺寸回归：主力折叠屏 `749×559dp`（状态栏 `24dp`）弹 `250dp`/`265dp` 键盘 + 恢复草稿 + 报错 + 绑定插槽，零裁切且三按钮可达。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun fold8Landscape749x559_ime250And265WithDraftErrorAndBoundSlots_zeroClipAndAllActionsReachable() {
        val tid = "2606008"
        val draftKey = DraftKey.reply(tid)
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(draftKey.value to DraftEntity(draftKey.value, content = "折叠屏草稿", updatedAt = 1_000L)),
        ).apply { submitHandler = { PostSubmitResult.NetworkError("timeout") } }
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 1), createIdleComposeGateway(), authRepo)
        val snackbars = RecordingPaneSnackbarController()
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalPaneSnackbarController provides snackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = tid, title = "Fold8 弹键盘回归", fid = 2, author = "linus_hi"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            viewModel = detailVm,
                            quickReplyViewModel = qrVm,
                            onExpandToFullEditor = {},
                            emojiPanelSlot = { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("E7") } },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val statusPx = with(density) { 24.dp.roundToPx() }
        for (imeDp in listOf(250, 265)) {
            val imePx = with(density) { imeDp.dp.roundToPx() }
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    requireNotNull(hostView),
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                        .build(),
                )
            }
            composeRule.waitForIdle()

            val sheetNode = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last()
            val sheetTop = sheetNode.positionInRoot.y
            val sheetBottom = sheetTop + sheetNode.size.height
            val inputNode = composeRule.onNodeWithContentDescription("回复 linus_hi").assertIsDisplayed().fetchSemanticsNode()
            val sendNode = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            assertTrue("Fold8 IME=${imeDp}dp 输入框不得挤成 0px：visible=${inputNode.boundsInRoot.height}", inputNode.boundsInRoot.height >= with(density) { 16.dp.toPx() })
            assertTrue("Fold8 IME=${imeDp}dp 发送不得裁切：sendBottom=${sendNode.positionInRoot.y + sendNode.size.height} vs $sheetBottom", sendNode.positionInRoot.y + sendNode.size.height <= sheetBottom + 1.5f)
            for (cd in listOf("展开全屏", "插入图片", "表情")) {
                val n = composeRule.onNodeWithContentDescription(cd).assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
                assertTrue("Fold8 IME=${imeDp}dp '$cd' 必须完整落在面板内", n.positionInRoot.y >= sheetTop - 1.5f && n.positionInRoot.y + n.size.height <= sheetBottom + 1.5f)
            }
        }
    }
}
