package net.jejer.hipda.feature.login

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dagger.Binds
import dagger.Module
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.di.LoginUiModule
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = FeatureScreenshotQualifiers.PHONE_411_891,
)
class LoginScreenTest {

    // region 验收 1：安全提问条件展开与切回“无安全提问”载荷净化（B4-E15 ①）

    @Test
    fun securityQuestion_expandInputThenRevertToNone_hidesFieldAndSubmitsZeroAndEmptyAnswer() =
        withAnimatorDurationScale(0f) {
            val repo = RecordingAuthRepository()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var successCount = 0
            try {
                val vm = LoginViewModel(repo, createIdleGateway(scope))
                setContent {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        LoginScreen(
                            onDismiss = {},
                            onLoginSuccess = { successCount++ },
                            viewModel = vm,
                        )
                    }
                }
                waitForIdle()

                // 默认不展示“提问回答”
                onNodeWithText("登录 HiPDA").assertIsDisplayed()
                onNodeWithText("提问回答").assertDoesNotExist()
                assertGlassSurfaceCountAtMost(0)

                onNode(hasSetTextAction() and hasText("用户名")).performTextInput("alice")
                onNode(hasSetTextAction() and hasText("密码")).performTextInput("pass1234")

                // 选安全提问 -> 展开“提问回答”并输入“王芳”
                onNodeWithText("安全提问").performClick()
                waitForIdle()
                onNodeWithText("母亲的名字").assertIsDisplayed().performClick()
                waitForIdle()

                onNode(hasSetTextAction() and hasText("提问回答")).assertIsDisplayed().performTextInput("王芳")
                waitForIdle()
                // 跨过双击超时阈值，避免测试虚拟时钟把两次点击下拉框合成双击选词触发 Robolectric Magnifier
                mainClock.advanceTimeBy(500L)

                // 再切回“无安全提问” -> “提问回答”收起
                onNodeWithText("安全提问").performClick()
                waitForIdle()
                onNodeWithText("无安全提问").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithText("提问回答").assertDoesNotExist()

                // 点击登录 -> 硬断言传给 AuthRepository.login 的参数满足 securityQuestionId == 0 && securityAnswer == ""
                onNodeWithText("登录").performClick()
                waitForIdle()

                assertEquals(1, repo.loginCalls.size)
                val call = repo.loginCalls.single()
                assertEquals("alice", call.username)
                assertEquals("pass1234", call.password)
                assertEquals(0, call.securityQuestionId)
                assertEquals("", call.securityAnswer)
                assertEquals(1, successCount)
            } finally {
                scope.cancel()
            }
        }

    // endregion

    // region 验收 2：0ms 进度圈防连点、失败保留输入、原地字段报错与精确焦点路由（B4-E15 ②）

    @Test
    fun submit_0msSpinnerAntiDoubleSubmit_preservesInputsRoutesFocusAndShowsCloudflareStatus() =
        withAnimatorDurationScale(0f) {
            val repo = RecordingAuthRepository()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = LoginViewModel(repo, createIdleGateway(scope))
                setContent {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        LoginScreen(
                            onDismiss = {},
                            onLoginSuccess = {},
                            viewModel = vm,
                        )
                    }
                }
                waitForIdle()

                // 密码显隐按钮触控区 >= 48dp 且可切换明/密文
                val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
                val toggleNode = onNodeWithContentDescription("显示密码").fetchSemanticsNode()
                assertTrue("密码显隐按钮宽 >= 48dp", toggleNode.touchBoundsInRoot.width >= minTouchPx)
                assertTrue("密码显隐按钮高 >= 48dp", toggleNode.touchBoundsInRoot.height >= minTouchPx)

                onNode(hasSetTextAction() and hasText("用户名")).performTextInput("alice")
                onNode(hasSetTextAction() and hasText("密码")).performTextInput("mySecret9")
                onNodeWithText("安全提问").performClick()
                waitForIdle()
                onNodeWithText("母亲的名字").performClick()
                waitForIdle()
                onNode(hasSetTextAction() and hasText("提问回答")).performTextInput("王芳")
                waitForIdle()

                // 1) 点击登录 -> 0ms 切换 HiLoadingSpinner（内容描述“正在登录…”）且禁用防连点
                val gate = CompletableDeferred<LoginResult>()
                repo.nextLoginDeferred = gate

                mainClock.autoAdvance = false
                onNodeWithText("登录").performClick()
                mainClock.advanceTimeByFrame()
                waitForIdle()

                onNodeWithContentDescription("正在登录…").assertIsDisplayed().assertIsNotEnabled()
                onNodeWithText("登录").assertDoesNotExist()
                assertEquals("挂起提交期间仅发起 1 次登录请求", 1, repo.loginCalls.size)

                // 2) 返回 InvalidCredentials（密码错）：保留全部输入、下方原地报错、焦点移至密码框
                mainClock.autoAdvance = true
                gate.complete(LoginResult.InvalidCredentials("登录失败，您还可以尝试 4 次", remainingAttempts = 4))
                waitForIdle()

                onNodeWithText("用户名或密码错误").assertIsDisplayed()
                onNodeWithText("登录失败", substring = true).assertDoesNotExist()
                onNode(hasSetTextAction() and hasText("密码")).assertIsFocused()

                val passwordBounds = onNode(hasSetTextAction() and hasText("密码")).fetchSemanticsNode().boundsInRoot
                val credErrBounds = onNodeWithText("用户名或密码错误").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "用户名或密码错误应位于密码输入框区域内/下方",
                    credErrBounds.top >= passwordBounds.top && credErrBounds.bottom <= passwordBounds.bottom + with(density) { 8.dp.toPx() },
                )

                // 所有输入原样保留
                assertEquals("alice", vm.uiState.value.username)
                assertEquals("mySecret9", vm.uiState.value.password)
                assertEquals("王芳", vm.uiState.value.securityAnswer)
                // 切显密码验证密码仍在界面
                onNodeWithContentDescription("显示密码").performClick()
                waitForIdle()
                onNodeWithContentDescription("隐藏密码").assertIsDisplayed()
                onNodeWithText("mySecret9").assertIsDisplayed()

                // 3) 返回安全提问回答错误：保留输入、挂在“提问回答”下方且精确聚焦“提问回答”框
                repo.nextLoginDeferred = null
                repo.nextLoginResult = LoginResult.InvalidCredentials("安全提问回答错误")
                onNodeWithText("登录").performClick()
                waitForIdle()

                onNodeWithText("安全提问回答错误").assertIsDisplayed()
                onNode(hasSetTextAction() and hasText("提问回答")).assertIsFocused()
                val answerBounds = onNode(hasSetTextAction() and hasText("提问回答")).fetchSemanticsNode().boundsInRoot
                val answerErrBounds = onNodeWithText("安全提问回答错误").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "安全提问回答错误应挂在提问回答输入框下方",
                    answerErrBounds.top >= answerBounds.top && answerErrBounds.bottom <= answerBounds.bottom + with(density) { 8.dp.toPx() },
                )
                assertEquals("alice", vm.uiState.value.username)
                assertEquals("mySecret9", vm.uiState.value.password)
                assertEquals("王芳", vm.uiState.value.securityAnswer)

                // 4) 网络错误：原地显示“网络连接失败，请重试”，不泄露异常名与状态码
                repo.nextLoginResult = LoginResult.NetworkError("SocketTimeoutException HTTP 503", IOException("boom"))
                onNodeWithText("登录").performClick()
                waitForIdle()

                onNodeWithText("网络连接失败，请重试").assertIsDisplayed()
                onNodeWithText("SocketTimeoutException", substring = true).assertDoesNotExist()
                onNodeWithText("503", substring = true).assertDoesNotExist()
                assertEquals("alice", vm.uiState.value.username)

                // 5) Cloudflare 验证：复用 ChallengeStatusView 并可点击“重新验证”重触发
                repo.nextLoginResult = LoginResult.ChallengeRequired(cooldownUntilMs = null, rejected = false)
                onNodeWithText("登录").performClick()
                waitForIdle()

                onNodeWithText("安全验证未完成").assertIsDisplayed()
                onNodeWithText("重新验证").assertIsDisplayed()

                val callsBeforeRetry = repo.loginCalls.size
                repo.nextLoginResult = LoginResult.ChallengeRequired(cooldownUntilMs = null, rejected = true)
                onNodeWithText("重新验证").performClick()
                waitForIdle()

                assertEquals(callsBeforeRetry + 1, repo.loginCalls.size)
                onNodeWithText("验证未通过").assertIsDisplayed()
            } finally {
                scope.cancel()
            }
        }

    // endregion

    // region 验收 3：宽屏居中模态（<= 480dp）、临时退出登录出口与 LoginUiModule 绑定

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun wideScreen_rendersCenteredModalCardMax480dpAndCloseDismisses() = withAnimatorDurationScale(0f) {
        val repo = RecordingAuthRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var dismissCount = 0
        try {
            val vm = LoginViewModel(repo, createIdleGateway(scope))
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    LoginScreen(
                        onDismiss = { dismissCount++ },
                        onLoginSuccess = {},
                        viewModel = vm,
                    )
                }
            }
            waitForIdle()

            assertGlassSurfaceCountAtMost(0)
            val cardNode = onNode(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.PaneTitle,
                    "登录 HiPDA",
                ),
            ).fetchSemanticsNode()
            val cardBounds = cardNode.boundsInRoot
            val maxCardWidthPx = with(density) { 480.dp.toPx() }
            assertTrue(
                "宽屏登录卡片宽应 <= 480dp，实际 ${with(density) { cardBounds.width.toDp() }}",
                cardBounds.width <= maxCardWidthPx + 1f,
            )
            val windowWidthPx = with(density) { 1280.dp.toPx() }
            assertTrue(
                "宽屏登录卡片应水平居中",
                abs((cardBounds.left) - (windowWidthPx - cardBounds.right)) <= with(density) { 2.dp.toPx() },
            )

            onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, dismissCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun accountMenu_temporaryLogoutCallsRepositoryDirectlyDismissesAndShowsSnackbar() = withAnimatorDurationScale(0f) {
        val signedInAccount = SavedAccount(
            uid = "2026",
            username = "hipda_veteran",
            passwordMd5 = "0123456789abcdef0123456789abcdef",
            lastLoginAt = 10_000L,
        )
        val repo = RecordingAuthRepository(initialAccount = signedInAccount)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val renderer = LoginOverlayRendererImpl(repo, createIdleGateway(scope))
        var overlayMode by mutableStateOf<LoginOverlayMode?>(LoginOverlayMode.AccountMenu)
        var dismissCount = 0

        try {
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            overlayMode?.let { mode ->
                                renderer.Content(
                                    mode = mode,
                                    onDismiss = {
                                        dismissCount++
                                        overlayMode = null
                                    },
                                    onLoginSuccess = { overlayMode = null },
                                )
                            }
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 24.dp)
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("账号").assertIsDisplayed()
            onNodeWithText("hipda_veteran").assertIsDisplayed()
            onNodeWithText("当前账号").assertIsDisplayed()
            onNodeWithText("退出登录").assertIsDisplayed()
            onNodeWithText("关闭").assertIsDisplayed()

            // 直接点击退出登录：无二次确认框，立即调 AuthRepository.logout()、关闭浮层并在宿主中展示“已退出登录”
            onNodeWithText("退出登录").performClick()
            waitForIdle()

            assertEquals(1, repo.logoutCalls)
            assertNull(repo.currentAccount.value)
            assertEquals(1, dismissCount)
            onNodeWithText("当前账号").assertDoesNotExist()
            onNodeWithText("确定").assertDoesNotExist()
            onNodeWithText("已退出登录").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loginUiModule_bindsLoginOverlayRendererImplAsSingleton() {
        assertNotNull(LoginUiModule::class.java.getAnnotation(Module::class.java))
        assertTrue(LoginOverlayRendererImpl::class.java.isAnnotationPresent(Singleton::class.java))
        val ctor = LoginOverlayRendererImpl::class.java.getDeclaredConstructor(
            AuthRepository::class.java,
            SiteAccessGateway::class.java,
        )
        assertTrue(ctor.isAnnotationPresent(Inject::class.java))

        val bindMethod = LoginUiModule::class.java.getDeclaredMethod(
            "bindLoginOverlayRenderer",
            LoginOverlayRendererImpl::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(LoginOverlayRenderer::class.java, bindMethod.returnType)
    }

    // endregion

    private fun createIdleGateway(scope: CoroutineScope): SiteAccessGateway = SiteAccessGateway(
        client = OkHttpClient(),
        detector = ChallengeDetector(),
        solver = ChallengeSolver { SolveResult.Cleared },
        clock = { 10_000L },
        gatewayScope = scope,
    )

    private data class RecordedLoginCall(
        val username: String,
        val password: String,
        val securityQuestionId: Int,
        val securityAnswer: String,
    )

    private class RecordingAuthRepository(
        initialAccount: SavedAccount? = null,
    ) : AuthRepository {
        private val _currentAccount = MutableStateFlow(initialAccount)
        override val currentAccount: StateFlow<SavedAccount?> = _currentAccount.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> =
            MutableStateFlow(listOfNotNull(initialAccount)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
        ).asStateFlow()
        override var sessionEpoch: Long = 1L

        val loginCalls = mutableListOf<RecordedLoginCall>()
        var logoutCalls = 0
        var nextLoginDeferred: CompletableDeferred<LoginResult>? = null
        var nextLoginResult: LoginResult = LoginResult.Success(
            SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1_000L),
        )

        override suspend fun login(
            username: String,
            passwordOrMd5: String,
            securityQuestionId: Int,
            securityAnswer: String,
        ): LoginResult {
            loginCalls += RecordedLoginCall(username, passwordOrMd5, securityQuestionId, securityAnswer)
            val result = nextLoginDeferred?.await() ?: nextLoginResult
            if (result is LoginResult.Success) {
                _currentAccount.value = result.account
                sessionEpoch++
            }
            return result
        }

        override suspend fun switchAccount(uid: String): LoginResult = error("unused")
        override suspend fun logout() {
            logoutCalls++
            _currentAccount.value = null
        }

        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
            SilentReloginResult.Suppressed
    }
}
