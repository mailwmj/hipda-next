package net.jejer.hipda.ui.challenge

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runEmptyComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411,
)
class ChallengeStatusAndPresenterTest {

    private val forumUrl: HttpUrl = "https://www.4d4y.com/forum/".toHttpUrl()
    private val unifiedUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    // region Step 2a: Solving 2000ms threshold, early Idle, and reduced-motion independence

    @Test
    fun statusView_solvingShowsCheckingAfter2000ms_andNeverFlashesWhenIdleEarly() =
        withAnimatorDurationScale(1f) {
            var nowMs = 10_000L
            var state by mutableStateOf<ChallengeState>(ChallengeState.Solving(startedAtMs = nowMs))

            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = {},
                            clock = { nowMs },
                        )
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            // Still hidden before 2000ms
            nowMs += 1_900L
            mainClock.advanceTimeBy(1_900L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            // Appears once 2000ms threshold is reached
            nowMs += 100L
            mainClock.advanceTimeBy(100L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertIsDisplayed()

            // Transition back to Idle hides it
            state = ChallengeState.Idle
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(500L)
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            // New Solving that returns to Idle before 2000ms must never flash
            nowMs = 20_000L
            state = ChallengeState.Solving(startedAtMs = nowMs)
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            nowMs += 1_500L
            mainClock.advanceTimeBy(1_500L)
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            state = ChallengeState.Idle
            Snapshot.sendApplyNotifications()
            nowMs += 1_500L
            mainClock.advanceTimeBy(1_500L)
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()
        }

    @Test
    fun statusView_solving2000msThresholdStillAppliesUnderReducedMotion() =
        withAnimatorDurationScale(0f) {
            var nowMs = 5_000L
            val state by mutableStateOf<ChallengeState>(ChallengeState.Solving(startedAtMs = nowMs))

            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = {},
                            clock = { nowMs },
                        )
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            nowMs += 1_800L
            mainClock.advanceTimeBy(1_800L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            nowMs += 200L
            mainClock.advanceTimeBy(200L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertIsDisplayed()
        }

    // endregion

    // region Step 2b: Incomplete and rejected states & AccessPriority.UserExplicit retry

    @Test
    fun statusView_incompleteAndRejectedStates_showCopyAndTriggerUserExplicitRetry() =
        withAnimatorDurationScale(0f) {
            val nowMs = 10_000L
            val retryPriorities = mutableListOf<AccessPriority>()
            var state by mutableStateOf<ChallengeState>(ChallengeState.Idle)
            var failure by mutableStateOf<ChallengeFailureKind?>(
                ChallengeIncompleteException().toChallengeFailureKind(),
            )

            assertEquals(ChallengeFailureKind.Incomplete, ChallengeIncompleteException().toChallengeFailureKind())
            assertEquals(ChallengeFailureKind.Rejected, ChallengeRejectedException(20_000L).toChallengeFailureKind())
            assertNull(IllegalStateException("other").toChallengeFailureKind())

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = { priority -> retryPriorities += priority },
                            failure = failure,
                            clock = { nowMs },
                        )
                    }
                }
            }
            waitForIdle()

            // Explicit single cancel / incomplete failure when state is Idle
            onNodeWithText("安全验证未完成").assertIsDisplayed()
            onNodeWithText("重新验证").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(listOf(AccessPriority.UserExplicit), retryPriorities)

            // Failure cooldown (rejected = false)
            failure = null
            state = ChallengeState.Cooldown(untilMs = nowMs + 30_000L, rejected = false)
            waitForIdle()
            onNodeWithText("安全验证未完成").assertIsDisplayed()
            onNodeWithText("重新验证").performClick()
            waitForIdle()
            assertEquals(listOf(AccessPriority.UserExplicit, AccessPriority.UserExplicit), retryPriorities)

            // Rejected cooldown (rejected = true)
            state = ChallengeState.Cooldown(untilMs = nowMs + 60_000L, rejected = true)
            waitForIdle()
            onNodeWithText("验证未通过").assertIsDisplayed()
            onNodeWithText("重新验证").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(
                listOf(AccessPriority.UserExplicit, AccessPriority.UserExplicit, AccessPriority.UserExplicit),
                retryPriorities,
            )
        }

    // endregion

    // region Step 2c: Cooldown lazy reset on expiration (including already-expired initial Cooldown)

    @Test
    fun statusView_cooldownAutoExpiresLazily_andAlreadyExpiredInitialCooldownIsIdle() =
        withAnimatorDurationScale(0f) {
            var nowMs = 50_000L
            // Initial state is already expired (untilMs <= nowMs)
            var state by mutableStateOf<ChallengeState>(
                ChallengeState.Cooldown(untilMs = 40_000L, rejected = true),
            )

            assertEquals(ChallengeState.Idle, state.effectiveAt(nowMs))

            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = {},
                            clock = { nowMs },
                        )
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("验证未通过").assertDoesNotExist()
            onNodeWithText("重新验证").assertDoesNotExist()

            // Transition into an active 5-second cooldown
            state = ChallengeState.Cooldown(untilMs = nowMs + 5_000L, rejected = true)
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("验证未通过").assertIsDisplayed()

            // Still shown just before expiration
            nowMs += 4_500L
            mainClock.advanceTimeBy(4_500L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("验证未通过").assertIsDisplayed()

            // Automatically disappears once untilMs is reached
            nowMs += 500L
            mainClock.advanceTimeBy(500L)
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("验证未通过").assertDoesNotExist()
            onNodeWithText("重新验证").assertDoesNotExist()
        }

    @Test
    fun statusView_expiredCooldownWithExplicitFailure_usesExplicitFailureNotStaleCooldown() =
        withAnimatorDurationScale(0f) {
            val nowMs = 50_000L
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = ChallengeState.Cooldown(untilMs = 40_000L, rejected = true),
                            onRetry = {},
                            failure = ChallengeFailureKind.Incomplete,
                            clock = { nowMs },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("安全验证未完成").assertIsDisplayed()
            onNodeWithText("验证未通过").assertDoesNotExist()
        }

    // endregion

    // region Step 2d: Real ComposeChallengeInteractionPresenter & Hilt binding

    @Test
    fun presenter_reusesSameWebViewWithoutLoadUrl_refreshReloads_cancelDismisses() = runEmptyComposeUiTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = scope.async { host.acquire(forumUrl, unifiedUa) }
            val intent = awaitStartedActivityIntent(application)
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val rawHandle = runBlocking { acquireDeferred.await() }
            val binding = requireNotNull(rawHandle.activityBinding)
            val originalWebView = binding.webView
            val loadUrlCalls = AtomicInteger(0)
            val reloadCalls = AtomicInteger(0)
            val trackingHandle = TrackingWebViewHandle(
                delegate = rawHandle,
                onLoadUrl = { loadUrlCalls.incrementAndGet() },
                onReload = { reloadCalls.incrementAndGet() },
            )
            val session = ChallengeInteractionSession(
                triggerUrl = forumUrl,
                reason = ChallengeInteractionReason.InteractiveBegin,
                webViewHandle = trackingHandle,
                activityBinding = binding,
                clearanceChecker = { false },
            )
            val presenter = ComposeChallengeInteractionPresenter(
                pollIntervalMs = 20L,
                dismissTimeoutMs = 500L,
            )

            val presentDeferred = scope.async { presenter.present(session) }
            waitForPanelShown()

            // Same WebView instance reused, visible/focusable/clickable, no extra loadUrl
            val mountedWebView = findWebView(binding.rootContainer)
            assertNotNull("Expected WebView to be mounted in panel", mountedWebView)
            assertSame("Panel must reuse the exact same WebView instance", originalWebView, mountedWebView)
            assertEquals("Presenting panel must not call loadUrl", 0, loadUrlCalls.get())
            assertNull("ShadowWebView must not have extra loadUrl calls", shadowOf(originalWebView).lastLoadedUrl)
            assertEquals(1f, originalWebView.alpha, 0.0001f)
            assertTrue(originalWebView.isFocusable)
            assertTrue(originalWebView.isClickable)
            val windowFlags = activity.window.attributes.flags
            assertEquals(0, windowFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(0, windowFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            // Refresh triggers WebView reload without extra loadUrl
            onNodeWithText("刷新").performClick()
            waitForIdle()
            assertEquals(1, reloadCalls.get())
            assertEquals(0, loadUrlCalls.get())

            // Cancel dismisses panel and returns Cancelled
            onNodeWithText("取消").performClick()
            val result = awaitDeferredWithMainIdle(presentDeferred)
            assertEquals(SolveResult.Cancelled, result)
            onNodeWithText("安全验证").assertDoesNotExist()
            assertEquals(0, binding.rootContainer.childCount)
            val restoredFlags = activity.window.attributes.flags
            assertTrue(
                "Window must restore FLAG_NOT_TOUCHABLE after dismiss",
                (restoredFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0,
            )
            assertTrue(
                "Window must restore FLAG_NOT_FOCUSABLE after dismiss",
                (restoredFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0,
            )

            rawHandle.close()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun presenter_hostOnDestroyCompletesCancelledAndCleansUpWithoutOuterCancellation() = runEmptyComposeUiTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = scope.async { host.acquire(forumUrl, unifiedUa) }
            val intent = awaitStartedActivityIntent(application)
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = runBlocking { acquireDeferred.await() }
            val binding = requireNotNull(handle.activityBinding)
            val pollCalls = AtomicInteger(0)
            val session = ChallengeInteractionSession(
                triggerUrl = forumUrl,
                reason = ChallengeInteractionReason.InteractiveBegin,
                webViewHandle = handle,
                activityBinding = binding,
                clearanceChecker = {
                    pollCalls.incrementAndGet()
                    false
                },
            )
            // Use a long dismissTimeoutMs so completion proves ON_DESTROY resolved immediately.
            val presenter = ComposeChallengeInteractionPresenter(
                pollIntervalMs = 20L,
                dismissTimeoutMs = 5_000L,
            )

            val presentDeferred = scope.async { presenter.present(session) }
            waitForPanelShown()
            assertFalse("Presenter must stay active before host destruction", presentDeferred.isCompleted)

            // Destroy host directly without cancelling presentDeferred externally.
            controller.pause().stop().destroy()
            val result = awaitDeferredWithMainIdle(presentDeferred)
            assertEquals(SolveResult.Cancelled, result)
            assertEquals(0, binding.rootContainer.childCount)

            val pollsAfterDestroy = pollCalls.get()
            Thread.sleep(60)
            assertEquals("Polling must stop after host ON_DESTROY cleanup", pollsAfterDestroy, pollCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun accessibilitySemantics_panelPaneTitleHeadingAndCheckingPoliteLiveRegion() =
        withAnimatorDurationScale(0f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        ChallengePanelOverlay(
                            visible = true,
                            onRefresh = {},
                            onCancel = {},
                            webViewSlot = {
                                ChallengeStatusView(
                                    state = ChallengeState.Solving(startedAtMs = 0L),
                                    onRetry = {},
                                    clock = { 2_500L },
                                )
                            },
                        )
                    }
                }
            }
            waitForIdle()

            onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "安全验证") and
                    SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
            ).assertIsDisplayed()

            onNodeWithText("安全验证")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

            onNodeWithText("正在检查安全环境…")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    @Test
    fun presenter_systemBackCancelsAndDismissesPanel() = runEmptyComposeUiTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = scope.async { host.acquire(forumUrl, unifiedUa) }
            val intent = awaitStartedActivityIntent(application)
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = runBlocking { acquireDeferred.await() }
            val binding = requireNotNull(handle.activityBinding)
            val session = ChallengeInteractionSession(
                triggerUrl = forumUrl,
                reason = ChallengeInteractionReason.BackgroundTimeout,
                webViewHandle = handle,
                activityBinding = binding,
                clearanceChecker = { false },
            )
            val presenter = ComposeChallengeInteractionPresenter(
                pollIntervalMs = 20L,
                dismissTimeoutMs = 500L,
            )

            val presentDeferred = scope.async { presenter.present(session) }
            waitForPanelShown()

            activity.onBackPressedDispatcher.onBackPressed()
            val result = awaitDeferredWithMainIdle(presentDeferred)
            assertEquals(SolveResult.Cancelled, result)
            onNodeWithText("安全验证").assertDoesNotExist()

            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun presenter_autoDismissesAndReturnsClearedWhenClearanceSucceeds() = runEmptyComposeUiTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = scope.async { host.acquire(forumUrl, unifiedUa) }
            val intent = awaitStartedActivityIntent(application)
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = runBlocking { acquireDeferred.await() }
            val binding = requireNotNull(handle.activityBinding)
            val cleared = AtomicBoolean(false)
            val session = ChallengeInteractionSession(
                triggerUrl = forumUrl,
                reason = ChallengeInteractionReason.InteractiveBegin,
                webViewHandle = handle,
                activityBinding = binding,
                clearanceChecker = { cleared.get() },
            )
            val presenter = ComposeChallengeInteractionPresenter(
                pollIntervalMs = 20L,
                dismissTimeoutMs = 500L,
            )

            val presentDeferred = scope.async { presenter.present(session) }
            waitForPanelShown()
            assertFalse("Presenter must remain open before clearance", presentDeferred.isCompleted)

            // Clearance detected in background -> panel auto-dismisses and returns Cleared
            cleared.set(true)
            val result = awaitDeferredWithMainIdle(presentDeferred)
            assertEquals(SolveResult.Cleared, result)
            onNodeWithText("安全验证").assertDoesNotExist()
            assertEquals(0, binding.rootContainer.childCount)

            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun hiltBinding_bindsComposeChallengeInteractionPresenterAsSingleton() {
        assertTrue(ComposeChallengeInteractionPresenter::class.java.isAnnotationPresent(Singleton::class.java))
        val ctor = ComposeChallengeInteractionPresenter::class.java.getDeclaredConstructor(
            SettingsRepository::class.java,
        )
        assertTrue(ctor.isAnnotationPresent(Inject::class.java))

        val bindMethod = ChallengeInteractionModule::class.java.getDeclaredMethod(
            "bindChallengeInteractionPresenter",
            ComposeChallengeInteractionPresenter::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(ChallengeInteractionPresenter::class.java, bindMethod.returnType)
    }

    // region E15 收口：R-A6 S3/S4 与 V-A6

    @Test
    fun statusView_pendingLoadingSlotShownBefore2000ms_thenSwitchesToChecking() =
        withAnimatorDurationScale(0f) {
            var nowMs = 10_000L
            var state by mutableStateOf<ChallengeState>(ChallengeState.Solving(startedAtMs = nowMs))

            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = {},
                            clock = { nowMs },
                            pendingLoadingSlot = { Text("原页面加载中") },
                        )
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("原页面加载中").assertIsDisplayed()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            // 1900ms 仍保留原加载指示（首帧 advanceTimeByFrame 已推进 16ms，16 + 1900 = 1916ms < 2000ms）
            nowMs += 1_900L
            mainClock.advanceTimeBy(1_900L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("原页面加载中").assertIsDisplayed()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()

            // 到达 2000ms 切换为“正在检查安全环境…”
            nowMs += 100L
            mainClock.advanceTimeBy(100L)
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertIsDisplayed()
            onNodeWithText("原页面加载中").assertDoesNotExist()

            // 切回 Idle 后两者均消失；新一轮 Solving 在未满 2s 结束绝不闪出“正在检查安全环境…”
            nowMs = 20_000L
            state = ChallengeState.Solving(startedAtMs = nowMs)
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("原页面加载中").assertIsDisplayed()

            nowMs += 1_200L
            mainClock.advanceTimeBy(1_200L)
            state = ChallengeState.Idle
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("原页面加载中").assertDoesNotExist()
            onNodeWithText("正在检查安全环境…").assertDoesNotExist()
        }

    @Test
    fun presenter_appliesSettingsRepositoryDarkPureBlackThemeLiveToChallengePanel() = runEmptyComposeUiTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = scope.async { host.acquire(forumUrl, unifiedUa) }
            val intent = awaitStartedActivityIntent(application)
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = runBlocking { acquireDeferred.await() }
            val binding = requireNotNull(handle.activityBinding)
            val session = ChallengeInteractionSession(
                triggerUrl = forumUrl,
                reason = ChallengeInteractionReason.InteractiveBegin,
                webViewHandle = handle,
                activityBinding = binding,
                clearanceChecker = { false },
            )
            val settingsFlow = MutableStateFlow(
                AppSettings(
                    themeMode = ThemeMode.DARK,
                    pureBlack = true,
                    themeSeed = HiColorPresets.Brand.id,
                    fontStep = 2,
                ),
            )
            val presenter = ComposeChallengeInteractionPresenter(
                settingsFlow = settingsFlow,
                pollIntervalMs = 20L,
                dismissTimeoutMs = 500L,
            )

            val presentDeferred = scope.async { presenter.present(session) }
            waitForPanelShown()

            // 深色+纯黑主题下，面板标题区域底层像素为暗色表面（亮度 < 0.25）
            val darkBitmap = onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "安全验证"),
            ).captureToImage().asAndroidBitmap()
            val darkSample = Color(darkBitmap.getPixel(darkBitmap.width / 2, 12))
            val darkLuma = 0.299f * darkSample.red + 0.587f * darkSample.green + 0.114f * darkSample.blue
            assertTrue("深色纯黑主题下面板背景应为深色，实际 luma=$darkLuma", darkLuma < 0.25f)

            // 实时切换为浅色主题：无需重启面板即响应变亮（亮度 > 0.75）
            settingsFlow.value = settingsFlow.value.copy(themeMode = ThemeMode.LIGHT, pureBlack = false)
            repeat(8) {
                shadowOf(Looper.getMainLooper()).idle()
                waitForIdle()
            }
            val lightBitmap = onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "安全验证"),
            ).captureToImage().asAndroidBitmap()
            val lightSample = Color(lightBitmap.getPixel(lightBitmap.width / 2, 12))
            val lightLuma = 0.299f * lightSample.red + 0.587f * lightSample.green + 0.114f * lightSample.blue
            assertTrue("实时切换浅色主题后面板背景应变亮，实际 luma=$lightLuma", lightLuma > 0.75f)

            onNodeWithText("取消").performClick()
            assertEquals(SolveResult.Cancelled, awaitDeferredWithMainIdle(presentDeferred))
            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun hiLoadingSpinner_isPublicReusableWithCustomStrokeWidth() = withAnimatorDurationScale(0f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiLoadingSpinner(
                    modifier = Modifier.size(20.dp).testTag("public_spinner"),
                    strokeWidth = 2.5.dp,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("public_spinner").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.NARROW_340)
    fun challengePanel_narrow340dpFontScale2x_noOverlapNoSubtitleTruncationMin48dpButtonsAndUsableWebViewHeight() =
        withAnimatorDurationScale(0f) {
            setContent {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 2.0f)) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ChallengePanelOverlay(
                            visible = true,
                            onRefresh = {},
                            onCancel = {},
                            webViewSlot = { Box(Modifier.fillMaxSize().testTag("narrow_webview_slot")) },
                        )
                    }
                }
            }
            waitForIdle()

            val titleNode = onNodeWithText("安全验证").assertIsDisplayed().fetchSemanticsNode()
            val subtitleNode = onNodeWithText("请完成页面上的验证以继续访问").assertIsDisplayed().fetchSemanticsNode()
            val refreshNode = onNodeWithText("刷新").assertIsDisplayed().fetchSemanticsNode()
            val cancelNode = onNodeWithText("取消").assertIsDisplayed().fetchSemanticsNode()
            val webViewNode = onNodeWithTag("narrow_webview_slot").assertIsDisplayed().fetchSemanticsNode()

            // 1) 按钮触控区 >= 48dp
            val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
            for ((name, node) in listOf("刷新" to refreshNode, "取消" to cancelNode)) {
                assertTrue("$name width >= 48dp", node.touchBoundsInRoot.width >= minTouchPx)
                assertTrue("$name height >= 48dp", node.touchBoundsInRoot.height >= minTouchPx)
            }

            // 2) 标题、副行、按钮、WebView 区域两两不重叠
            val rects = listOf(
                "title" to titleNode.boundsInRoot,
                "subtitle" to subtitleNode.boundsInRoot,
                "refresh" to refreshNode.boundsInRoot,
                "cancel" to cancelNode.boundsInRoot,
                "webview" to webViewNode.boundsInRoot,
            )
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val (n1, r1) = rects[i]
                    val (n2, r2) = rects[j]
                    val overlaps = r1.left < r2.right - 0.5f && r1.right > r2.left + 0.5f &&
                        r1.top < r2.bottom - 0.5f && r1.bottom > r2.top + 0.5f
                    assertFalse("340dp+2x 下 $n1($r1) 与 $n2($r2) 不得重叠", overlaps)
                }
            }

            // 3) 副行 14 字完整显示无省略截断
            val layouts = mutableListOf<TextLayoutResult>()
            val getLayout = subtitleNode.config[SemanticsActions.GetTextLayoutResult].action
            assertTrue("副行应暴露 TextLayoutResult", getLayout?.invoke(layouts) == true && layouts.isNotEmpty())
            val subtitleLayout = layouts.first()
            assertFalse("340dp+2x 副行不得超最大行截断", subtitleLayout.didOverflowHeight)
            for (line in 0 until subtitleLayout.lineCount) {
                assertFalse("340dp+2x 副行第 $line 行不得出现省略号", subtitleLayout.isLineEllipsized(line))
            }

            // 4) WebView 区域保有充足可用高度（>= 360dp）
            val webViewHeightDp = with(density) { webViewNode.boundsInRoot.height.toDp() }
            assertTrue("340dp+2x 下 WebView 区域应保有充足高度（实际 $webViewHeightDp）", webViewHeightDp >= 360.dp)
        }

    // endregion

    // endregion

    private fun awaitStartedActivityIntent(application: Application): Intent {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = shadowOf(application).nextStartedActivity
            if (next != null) return next
            Thread.sleep(10)
        }
        error("ChallengeActivity launch intent was not dispatched")
    }

    private fun ComposeUiTest.waitForPanelShown() {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            waitForIdle()
            val found = runCatching {
                onNodeWithText("安全验证").assertIsDisplayed()
                onNodeWithText("请完成页面上的验证以继续访问").assertIsDisplayed()
                true
            }.getOrDefault(false)
            if (found) return
            Thread.sleep(15)
        }
        onNodeWithText("安全验证").assertIsDisplayed()
    }

    private fun <T> ComposeUiTest.awaitDeferredWithMainIdle(deferred: kotlinx.coroutines.Deferred<T>): T {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            waitForIdle()
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
        waitForIdle()
        assertTrue("Expected presenter coroutine to complete before timeout", deferred.isCompleted)
        return runBlocking { deferred.await() }
    }

    private fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private class TrackingWebViewHandle(
        private val delegate: ChallengeWebViewHandle,
        private val onLoadUrl: () -> Unit,
        private val onReload: () -> Unit,
    ) : ChallengeWebViewHandle by delegate {
        override fun loadUrl(url: String) {
            onLoadUrl()
            delegate.loadUrl(url)
        }

        override fun reload() {
            onReload()
            delegate.reload()
        }
    }
}
