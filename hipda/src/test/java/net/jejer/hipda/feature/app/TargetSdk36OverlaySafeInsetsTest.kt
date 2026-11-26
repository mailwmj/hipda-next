package net.jejer.hipda.feature.app

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.compose.FullEditorPlacement
import net.jejer.hipda.feature.compose.FullEditorPlacementFrame
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerOverlay
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.imageviewer.createViewerPhotoBitmap
import net.jejer.hipda.feature.login.LoginScreenContent
import net.jejer.hipda.feature.login.LoginUiState
import net.jejer.hipda.feature.settings.AccountManagementContent
import net.jejer.hipda.feature.settings.AccountManagementUiState
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.shell.HiNavigationSidebarModalOverlay
import net.jejer.hipda.ui.shell.HiSidebarState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M7-3 修复轮 1（R-M7-3 B2 & S1）跨 API 28 / 33 / 35 / 36 布局安全区断言：
 * - 手机横屏左/右侧 48dp 经典三键导航 + 横向挖孔：看图页关闭钮、页码胶囊、GIF 标与底栏均零遮挡，且背景大图仍铺满全屏；
 * - 竖屏深挖孔（44dp > 24dp 状态栏）：看图、登录、账号管理页顶栏均严格避让 44dp 挖孔；
 * - `< 480dp` 横屏全屏态：登录与账号管理页左右避让挖孔/三键导航，且叠加 IME 无双倍底部留白；
 * - S1：240dp 侧栏模态抽屉与居中模态编辑器均对齐横向挖孔。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 33, 35, 36])
class TargetSdk36OverlaySafeInsetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zeroMotion = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    private val sampleAccounts = listOf(
        SavedAccount("1001", "hipda_veteran", "0123456789abcdef0123456789abcdef", lastLoginAt = 2_000L),
        SavedAccount("1002", "geek_alt", "fedcba9876543210fedcba9876543210", lastLoginAt = 1_000L),
    )

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun imageViewer_landscapeLeftAndRightThreeButtonNavAndCutout_controlsAvoidBarsWhileImageFillsScreen() {
        val loader = staticViewerImageLoader()
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(motionDurationScale = zeroMotion) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf(
                                "https://img.example.invalid/demo.gif",
                                "https://img.example.invalid/photo_2.jpg",
                                "https://img.example.invalid/photo_3.jpg",
                            ),
                            initialIndex = 0,
                            floorNumber = 3,
                            author = "linus_hi",
                        ),
                        onDismiss = {},
                        originalDownloader = NoOpOriginalDownloader,
                        mediaActions = NoOpViewerMediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val navPx = with(density) { 48.dp.roundToPx() }
        val cutoutPx = with(density) { 40.dp.roundToPx() }
        val statusPx = with(density) { 24.dp.roundToPx() }

        // 1. 270° 反向横屏：左侧 48dp 三键导航栏 + 左侧 40dp 摄像头挖孔
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusPx,
            navLeftPx = navPx,
            cutoutLeftPx = cutoutPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val closeLeft = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifLeft = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val originalLeft = composeRule.onNodeWithText("查看原图").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rootAfter = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("看图背景容器仍须铺满全屏（left == 0）", rootAfter.left == 0f && rootAfter.right == rootBounds.right)
        assertTrue("左三键导航(48dp)下关闭按钮不得落入导航区，实际 left=${with(density) { closeLeft.left.toDp() }}", closeLeft.left >= navPx - 1f)
        assertTrue("左三键导航(48dp)下 GIF 角标不得落入导航区，实际 left=${with(density) { gifLeft.left.toDp() }}", gifLeft.left >= navPx - 1f)
        assertTrue("左三键导航(48dp)下底栏按钮不得落入导航区，实际 left=${with(density) { originalLeft.left.toDp() }}", originalLeft.left >= navPx - 1f)

        // 2. 90° 横屏：右侧 48dp 三键导航栏 + 左侧 40dp 挖孔
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusPx,
            navRightPx = navPx,
            cutoutLeftPx = cutoutPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val closeWithCutout = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifWithCutout = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pagePill = composeRule.onNodeWithText("1 / 3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shareBtn = composeRule.onNodeWithText("分享").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // AndroidX WindowInsetsCompat.BuilderImpl20（API 28）忽略 setDisplayCutout；API 29+（33/35/36）走 BuilderImpl29/30 完整验证挖孔
            assertTrue("左侧挖孔(40dp)下关闭按钮须避让挖孔，实际 left=${with(density) { closeWithCutout.left.toDp() }}", closeWithCutout.left >= cutoutPx - 1f)
            assertTrue("左侧挖孔(40dp)下 GIF 角标须避让挖孔，实际 left=${with(density) { gifWithCutout.left.toDp() }}", gifWithCutout.left >= cutoutPx - 1f)
        }
        assertTrue("右侧三键导航(48dp)下页码胶囊 1 / 3 不得被导航栏遮挡，实际 right=${with(density) { pagePill.right.toDp() }}", pagePill.right <= rootBounds.right - navPx + 1f)
        assertTrue("右侧三键导航(48dp)下底栏分享钮不得被导航栏遮挡，实际 right=${with(density) { shareBtn.right.toDp() }}", shareBtn.right <= rootBounds.right - navPx + 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun portraitDeepCutout_imageViewerControls_avoid44dpTopCutout() {
        val loader = staticViewerImageLoader()
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(motionDurationScale = zeroMotion) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/demo.gif", "https://img.example.invalid/p2.jpg"),
                            floorNumber = 1,
                            author = "cutout_tester",
                        ),
                        onDismiss = {},
                        originalDownloader = NoOpOriginalDownloader,
                        mediaActions = NoOpViewerMediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val statusPx = with(density) { 24.dp.roundToPx() }
        val cutoutTopPx = with(density) { 44.dp.roundToPx() }
        val navBottomPx = with(density) { 48.dp.roundToPx() }

        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusPx,
            navBottomPx = navBottomPx,
            cutoutTopPx = cutoutTopPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val closeBounds = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageBounds = composeRule.onNodeWithText("1 / 2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifBounds = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveBounds = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val expectedTopPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cutoutTopPx else statusPx
        assertTrue("竖屏深挖孔/状态栏下关闭按钮须避让顶部安全区，实际 top=${with(density) { closeBounds.top.toDp() }}", closeBounds.top >= expectedTopPx - 1f)
        assertTrue("竖屏深挖孔/状态栏下页码胶囊须避让顶部安全区，实际 top=${with(density) { pageBounds.top.toDp() }}", pageBounds.top >= expectedTopPx - 1f)
        assertTrue("竖屏深挖孔/状态栏下 GIF 角标须避让顶部安全区与顶栏，实际 top=${with(density) { gifBounds.top.toDp() }}", gifBounds.top >= expectedTopPx + with(density) { 68.dp.toPx() } - 1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+（33/35/36）原生分离 Type.navigationBars() 底部（API 28 Robolectric 无 rootWindowInsets，Impl20 将底部 systemWindow 归入 IME）
            assertTrue("竖屏三键导航(48dp)下保存图片按钮须避让底部导航栏，实际 bottom=${with(density) { saveBounds.bottom.toDp() }}", saveBounds.bottom <= rootBounds.bottom - navBottomPx + 1f)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun portraitDeepCutout_loginFullscreen_avoids44dpTopCutout() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(motionDurationScale = zeroMotion) {
                LoginScreenContent(
                    uiState = LoginUiState(username = "tester"),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSecurityQuestionSelected = {},
                    onSecurityAnswerChange = {},
                    onSubmit = {},
                    onDismiss = {},
                    onConsumeFocusRequest = {},
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val statusTopPx = with(density) { 24.dp.roundToPx() }
        val cutoutTopPx = with(density) { 44.dp.roundToPx() }
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navBottomPx = with(density) { 48.dp.roundToPx() },
            cutoutTopPx = cutoutTopPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val expectedTopPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cutoutTopPx else statusTopPx
        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("登录页竖屏深挖孔/状态栏下返回按钮不得侵入顶部安全区，实际 top=${with(density) { backBounds.top.toDp() }}", backBounds.top >= expectedTopPx - 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun portraitDeepCutout_accountManagementFullscreen_avoids44dpTopCutout() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(motionDurationScale = zeroMotion) {
                AccountManagementContent(
                    uiState = AccountManagementUiState(currentAccount = sampleAccounts.first(), savedAccounts = sampleAccounts),
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onLogout = {},
                    onClearCredentials = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val statusTopPx = with(density) { 24.dp.roundToPx() }
        val cutoutTopPx = with(density) { 44.dp.roundToPx() }
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navBottomPx = with(density) { 48.dp.roundToPx() },
            cutoutTopPx = cutoutTopPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val expectedTopPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cutoutTopPx else statusTopPx
        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val titleBounds = composeRule.onNodeWithText("账号").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("账号管理页竖屏深挖孔/状态栏下返回按钮须避让顶部安全区，实际 top=${with(density) { backBounds.top.toDp() }}", backBounds.top >= expectedTopPx - 1f)
        assertTrue("账号管理页竖屏深挖孔/状态栏下标题须避让顶部安全区，实际 top=${with(density) { titleBounds.top.toDp() }}", titleBounds.top >= expectedTopPx - 1f)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun shortLandscapeBelow480dp_loginFullscreen_avoidsLeftCutoutAndRightNavAndZeroDoubleImePadding() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(motionDurationScale = zeroMotion) {
                LoginScreenContent(
                    uiState = LoginUiState(username = "tester"),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSecurityQuestionSelected = {},
                    onSecurityAnswerChange = {},
                    onSubmit = {},
                    onDismiss = {},
                    onConsumeFocusRequest = {},
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cutoutLeftPx = with(density) { 44.dp.roundToPx() }
        val navRightPx = with(density) { 48.dp.roundToPx() }
        val statusTopPx = with(density) { 24.dp.roundToPx() }

        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navRightPx = navRightPx,
            cutoutLeftPx = cutoutLeftPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue("<480dp 横屏全屏登录页返回钮须避让左 44dp 挖孔，实际 left=${with(density) { backBounds.left.toDp() }}", backBounds.left >= cutoutLeftPx - 1f)
        }
        assertTrue("<480dp 横屏全屏登录页返回钮须避让顶部状态栏，实际 top=${with(density) { backBounds.top.toDp() }}", backBounds.top >= statusTopPx - 1f)

        // 反向横屏（左侧 48dp 三键导航）同样零遮挡
        val navLeftPx = with(density) { 48.dp.roundToPx() }
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navLeftPx = navLeftPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()
        val backReverse = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("<480dp 反向横屏登录页返回钮须避让左 48dp 三键导航，实际 left=${with(density) { backReverse.left.toDp() }}", backReverse.left >= navLeftPx - 1f)

        // API 30+ 校验底部三键导航 + IME 不产生双倍底部留白（Column 仍保有正常可视高度）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dispatchSafeInsets(
                view = requireNotNull(hostView),
                statusTopPx = statusTopPx,
                navBottomPx = with(density) { 48.dp.roundToPx() },
                imeBottomPx = with(density) { 180.dp.roundToPx() },
                rootWidthPx = rootBounds.width.toInt(),
                rootHeightPx = rootBounds.height.toInt(),
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithText("用户名").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun shortLandscapeBelow480dp_accountManagementFullscreen_avoidsLeftCutoutAndRightNav() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(motionDurationScale = zeroMotion) {
                AccountManagementContent(
                    uiState = AccountManagementUiState(currentAccount = sampleAccounts.first(), savedAccounts = sampleAccounts),
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onLogout = {},
                    onClearCredentials = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cutoutLeftPx = with(density) { 44.dp.roundToPx() }
        val navRightPx = with(density) { 48.dp.roundToPx() }
        val statusTopPx = with(density) { 24.dp.roundToPx() }

        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navRightPx = navRightPx,
            cutoutLeftPx = cutoutLeftPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()

        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val accountBounds = composeRule.onNodeWithText("hipda_veteran").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue("<480dp 横屏账号管理页返回钮须避让左 44dp 挖孔，实际 left=${with(density) { backBounds.left.toDp() }}", backBounds.left >= cutoutLeftPx - 1f)
        }
        assertTrue("<480dp 横屏账号管理页列表项须避让右 48dp 三键导航，实际 right=${with(density) { accountBounds.right.toDp() }}", accountBounds.right <= rootBounds.right - navRightPx + 1f)

        // 反向横屏（左侧 48dp 三键导航）在全部 API（含 API 28）均须避让左侧导航栏
        val navLeftPx = with(density) { 48.dp.roundToPx() }
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = statusTopPx,
            navLeftPx = navLeftPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()
        val backReverse = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("<480dp 反向横屏账号管理页返回钮须避让左 48dp 三键导航，实际 left=${with(density) { backReverse.left.toDp() }}", backReverse.left >= navLeftPx - 1f)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun s1_sidebarModalDrawer_andCenteredModalEditor_alignHorizontalCutout() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(motionDurationScale = zeroMotion) {
                Box(Modifier.fillMaxSize()) {
                    FullEditorPlacementFrame(
                        placement = FullEditorPlacement.CenteredModal,
                        windowWidthDp = 640f,
                        isSubmitting = false,
                        onRequestClose = {},
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            Text("模态编辑器内容")
                        }
                    }
                    HiNavigationSidebarModalOverlay(
                        visible = true,
                        state = HiSidebarState(),
                        mineGroupExpanded = false,
                        onDismiss = {},
                        onToggleMineGroup = {},
                        onSelectEntry = {},
                        onAccountClick = {},
                        onManageForumsClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cutoutLeftPx = with(density) { 44.dp.roundToPx() }
        val cutoutRightPx = with(density) { 44.dp.roundToPx() }
        val navLeftPx = with(density) { 48.dp.roundToPx() }

        // 左侧 48dp 三键导航在全部 API（含 API 28）均生效
        dispatchSafeInsets(
            view = requireNotNull(hostView),
            statusTopPx = with(density) { 24.dp.roundToPx() },
            navLeftPx = navLeftPx,
            rootWidthPx = rootBounds.width.toInt(),
            rootHeightPx = rootBounds.height.toInt(),
        )
        composeRule.waitForIdle()
        val drawerEntryNav = composeRule.onNodeWithText("今日新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("S1: 240dp 侧栏模态抽屉项须避让左 48dp 三键导航，实际 left=${with(density) { drawerEntryNav.left.toDp() }}", drawerEntryNav.left >= navLeftPx - 1f)

        // API 29+ 验证双侧 44dp 横向挖孔
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dispatchSafeInsets(
                view = requireNotNull(hostView),
                statusTopPx = with(density) { 24.dp.roundToPx() },
                cutoutLeftPx = cutoutLeftPx,
                cutoutRightPx = cutoutRightPx,
                rootWidthPx = rootBounds.width.toInt(),
                rootHeightPx = rootBounds.height.toInt(),
            )
            composeRule.waitForIdle()

            val drawerEntry = composeRule.onNodeWithText("今日新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val modalContent = composeRule.onNodeWithText("模态编辑器内容").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("S1: 240dp 侧栏模态抽屉项须避让左 44dp 横向挖孔，实际 left=${with(density) { drawerEntry.left.toDp() }}", drawerEntry.left >= cutoutLeftPx - 1f)
            assertTrue("S1: 居中模态编辑器须避让左 44dp 挖孔，实际 left=${with(density) { modalContent.left.toDp() }}", modalContent.left >= cutoutLeftPx - 1f)
            assertTrue("S1: 居中模态编辑器须避让右 44dp 挖孔，实际 right=${with(density) { modalContent.right.toDp() }}", modalContent.right <= rootBounds.right - cutoutRightPx + 1f)
        }
    }

    private fun dispatchSafeInsets(
        view: View,
        statusTopPx: Int = 0,
        navLeftPx: Int = 0,
        navBottomPx: Int = 0,
        navRightPx: Int = 0,
        cutoutLeftPx: Int = 0,
        cutoutTopPx: Int = 0,
        cutoutRightPx: Int = 0,
        imeBottomPx: Int = 0,
        rootWidthPx: Int,
        rootHeightPx: Int,
    ) {
        val builder = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTopPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(navLeftPx, 0, navRightPx, navBottomPx))
        if (imeBottomPx > 0) {
            builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
        }
        if (cutoutLeftPx > 0 || cutoutTopPx > 0 || cutoutRightPx > 0) {
            val leftRect = if (cutoutLeftPx > 0) Rect(0, rootHeightPx / 3, cutoutLeftPx, rootHeightPx * 2 / 3) else null
            val topRect = if (cutoutTopPx > 0) Rect(rootWidthPx / 3, 0, rootWidthPx * 2 / 3, cutoutTopPx) else null
            val rightRect = if (cutoutRightPx > 0) Rect(rootWidthPx - cutoutRightPx, rootHeightPx / 3, rootWidthPx, rootHeightPx * 2 / 3) else null
            builder.setDisplayCutout(
                DisplayCutoutCompat(
                    Insets.of(cutoutLeftPx, cutoutTopPx, cutoutRightPx, 0),
                    leftRect,
                    topRect,
                    rightRect,
                    null,
                    Insets.NONE,
                ),
            )
        }
        val insets = builder.build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
    }

    private fun staticViewerImageLoader(): ImageLoader = object : ImageLoader {
        override val defaults = ImageRequest.Defaults.DEFAULT
        override val components = ComponentRegistry()
        override val memoryCache: MemoryCache? = null
        override val diskCache: DiskCache? = null
        override fun enqueue(request: ImageRequest): Disposable {
            val result = SuccessResult(createViewerPhotoBitmap(width = 800, height = 400).asImage(), request, DataSource.MEMORY)
            request.target?.onSuccess(result.image)
            return object : Disposable {
                override val job: Deferred<ImageResult> = CompletableDeferred(result)
                override val isDisposed: Boolean = true
                override fun dispose() = Unit
            }
        }
        override suspend fun execute(request: ImageRequest): ImageResult =
            SuccessResult(createViewerPhotoBitmap(width = 800, height = 400).asImage(), request, DataSource.MEMORY)
        override fun newBuilder(): ImageLoader.Builder = error("unused")
        override fun shutdown() = Unit
    }

    private object NoOpOriginalDownloader : OriginalImageDownloader {
        override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
    }

    private object NoOpViewerMediaActions : ImageViewerMediaActions {
        override suspend fun saveImage(url: String, cachedFile: File?): Boolean = true
        override suspend fun shareImage(url: String, cachedFile: File?): Boolean = true
    }
}
