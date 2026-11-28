package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.FakeComposeSettingsRepository
import net.jejer.hipda.feature.compose.FakeImageUploadRepository
import net.jejer.hipda.feature.compose.FakePostRepository
import net.jejer.hipda.feature.compose.FakeQuickReplyAuthRepository
import net.jejer.hipda.feature.compose.FullEditorHandoff
import net.jejer.hipda.feature.compose.FullEditorRequest
import net.jejer.hipda.feature.compose.FullEditorScreen
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.forum.ForumTopBar
import net.jejer.hipda.feature.forum.rememberForumTopBarScrollState
import net.jejer.hipda.feature.messages.MessagesSolidTopBarFrame
import net.jejer.hipda.feature.settings.SettingsSolidTopBar
import net.jejer.hipda.feature.threaddetail.ThreadDetailTopBar
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.topbar.HiSolidTopBar
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.HiSidebarAccount
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U5a 验收 3：全应用 7 处顶栏标题行高度统一为 48dp、图标按钮触控区 ≥ 48dp，以及 933×696 三栏左/中/右顶栏底边水平对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class U5aTopBarHeightAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun allSixContentTopBars_have48dpTitleRowHeight_and48dpIconTouchTargets() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        editorVm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609001", 2, "", 0, 0, threadAuthor = "linus_hi")))
        var mode = mutableStateOf(0)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                when (mode.value) {
                    0 -> Box(Modifier.fillMaxWidth().testTag("top_bar_box")) {
                        ForumTopBar(
                            title = "Discovery",
                            source = ForumListSource.Forum(2),
                            followedForums = listOf(2, 6, 7),
                            sortByPostTime = false,
                            showForumTabs = false,
                            glassState = null,
                            isScrolled = { false },
                            scrollState = rememberForumTopBarScrollState(collapsible = false),
                            onSelectSource = {},
                            onNewThreadClick = {},
                            onSelectSortByPostTime = {},
                            onManageForumsClick = {},
                        )
                    }
                    1 -> Box(Modifier.fillMaxWidth().testTag("top_bar_box")) {
                        ThreadDetailTopBar(
                            title = "详情顶栏",
                            chromeSpec = DetailChromeSpec(showBack = true, fullScreenState = FullScreenToggle(false) {}),
                            isScrolledState = mutableStateOf(false),
                            glassState = rememberHiGlassState(),
                            onBack = {},
                        )
                    }
                    2 -> Box(Modifier.fillMaxWidth().testTag("top_bar_box")) {
                        HiSolidTopBar(
                            title = "我的",
                            isScrolled = false,
                            showBack = true,
                            onBack = {},
                        )
                    }
                    3 -> Box(Modifier.fillMaxWidth().testTag("top_bar_box")) {
                        MessagesSolidTopBarFrame(
                            title = "短消息",
                            showBack = true,
                            onBack = {},
                            isScrolled = false,
                            actions = {
                                IconButton(onClick = {}, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) { Icon(Icons.Rounded.Edit, "新短消息") }
                            },
                        )
                    }
                    4 -> Box(Modifier.fillMaxWidth().testTag("top_bar_box")) {
                        SettingsSolidTopBar(
                            title = "设置",
                            scrolled = false,
                            showBack = true,
                            onBack = {},
                        )
                    }
                    else -> FullEditorScreen(viewModel = editorVm, onOpenLogin = {}, isModalCard = false)
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        fun assertHostHeight48dp(label: String) {
            val h = with(density) { composeRule.onNodeWithTag("top_bar_box").fetchSemanticsNode().boundsInRoot.height.toDp().value }
            assertEquals("$label 标题行高必须为 48dp", 48f, h, 1.0f)
        }

        // 0. 论坛顶栏
        assertHostHeight48dp("ForumTopBar")
        composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("更多").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 1. 帖子详情顶栏
        mode.value = 1; composeRule.waitForIdle()
        assertHostHeight48dp("ThreadDetailTopBar")
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("全屏阅读").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 2. 通用纯色顶栏 HiSolidTopBar
        mode.value = 2; composeRule.waitForIdle()
        assertHostHeight48dp("HiSolidTopBar")
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 3. 短消息顶栏
        mode.value = 3; composeRule.waitForIdle()
        assertHostHeight48dp("MessagesSolidTopBarFrame")
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("新短消息").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 4. 设置顶栏
        mode.value = 4; composeRule.waitForIdle()
        assertHostHeight48dp("SettingsSolidTopBar")
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 5. 完整编辑器（非模态）顶栏：返回按钮与发送按钮垂直居中于 48dp 行内，且底边落于 48dp
        mode.value = 5; composeRule.waitForIdle()
        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertEquals("FullEditor 非模态顶栏返回按钮底缘应为 48dp", 48f, with(density) { backBounds.bottom.toDp().value }, 1.0f)
        assertEquals("FullEditor 非模态顶栏发送按钮应与返回按钮垂直居中对齐于 48dp 行", backBounds.center.y, sendBounds.center.y, 2f)
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun threePane933_leftMiddleRightTopBars_alignAt48dpHorizon() {
        val harness = MultiPaneTestHarness(richSampleContent = true)
        harness.hostViewModel.openThread(
            ThreadNavArgs(tid = "1001", title = "三栏顶栏 48dp 对齐测试", fid = 2, forumName = "Discovery", author = "linus_hi"),
        )
        composeRule.setContent {
            harness.RenderRoot(glassEnvironment = GlassEnvironmentState.RefractionAndBlur)
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        val leftBarBottomDp = with(density) {
            composeRule.onNodeWithTag(HiShellTestTags.SidebarTopBar).fetchSemanticsNode().boundsInRoot.bottom.toDp().value
        }
        val middleActionBottomDp = with(density) {
            composeRule.onNodeWithContentDescription("发新帖").fetchSemanticsNode().boundsInRoot.bottom.toDp().value
        }
        val rightActionBottomDp = with(density) {
            composeRule.onNodeWithContentDescription("全屏阅读").fetchSemanticsNode().boundsInRoot.bottom.toDp().value
        }
        assertEquals("左栏顶栏底边必须为 48dp", 48f, leftBarBottomDp, 1.0f)
        assertEquals("中栏论坛顶栏底边必须与左栏 48dp 水平对齐", leftBarBottomDp, middleActionBottomDp, 1.0f)
        assertEquals("右栏详情顶栏底边必须与左栏 48dp 水平对齐", leftBarBottomDp, rightActionBottomDp, 1.0f)
    }
}
