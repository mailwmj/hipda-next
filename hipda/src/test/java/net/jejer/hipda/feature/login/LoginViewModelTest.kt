package net.jejer.hipda.feature.login

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginFailureReason
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun securityQuestion_selectThenRevertToNone_clearsAndSanitizesPayloadToZeroAndEmpty() = runTest(testDispatcher) {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo, createIdleGateway(this))

        // 默认无安全提问，回答隐藏
        assertEquals(SavedAccount.NO_SECURITY_QUESTION, vm.uiState.value.securityQuestionId)
        assertFalse(vm.uiState.value.showSecurityAnswer)
        assertEquals("", vm.uiState.value.securityAnswer)

        vm.onUsernameChange("  alice  ")
        vm.onPasswordChange("p@ss123")

        // 选中安全提问并填写回答
        vm.onSecurityQuestionSelected(1)
        assertTrue(vm.uiState.value.showSecurityAnswer)
        vm.onSecurityAnswerChange("王芳")
        assertEquals("王芳", vm.uiState.value.securityAnswer)

        // 切回“无安全提问”：立即清空已填回答并收起
        vm.onSecurityQuestionSelected(SavedAccount.NO_SECURITY_QUESTION)
        assertFalse(vm.uiState.value.showSecurityAnswer)
        assertEquals("", vm.uiState.value.securityAnswer)

        // 提交：硬断言传给 AuthRepository.login 的载荷满足 securityQuestionId == 0 && securityAnswer == ""
        vm.submit()
        advanceUntilIdle()

        assertEquals(1, repo.loginCalls.size)
        val call = repo.loginCalls.single()
        assertEquals("alice", call.username)
        assertEquals("p@ss123", call.password)
        assertEquals(0, call.securityQuestionId)
        assertEquals("", call.securityAnswer)
        assertTrue(vm.uiState.value.loginSuccessEventId > 0L)
    }

    @Test
    fun securityQuestion_selectedWithAnswer_passesExactQuestionIdAndAnswer() = runTest(testDispatcher) {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo, createIdleGateway(this))

        vm.onUsernameChange("bob")
        vm.onPasswordChange("secret")
        vm.onSecurityQuestionSelected(3)
        vm.onSecurityAnswerChange("上海")
        vm.submit()
        advanceUntilIdle()

        val call = repo.loginCalls.single()
        assertEquals(3, call.securityQuestionId)
        assertEquals("上海", call.securityAnswer)
    }

    @Test
    fun submit_preservesAllInputsAndRoutesFocusForInvalidCredentialsSecurityAnswerAndNetworkError() =
        runTest(testDispatcher) {
            val repo = FakeAuthRepository()
            val vm = LoginViewModel(repo, createIdleGateway(this))

            vm.onUsernameChange("alice")
            vm.onPasswordChange("wrong_pw")
            vm.onSecurityQuestionSelected(1)
            vm.onSecurityAnswerChange("王芳")

            // 1) 用户名或密码错误：保留全部输入，挂在密码字段并聚焦密码
            repo.nextLoginResult = LoginResult.InvalidCredentials("登录失败，您还可以尝试 4 次", remainingAttempts = 4)
            vm.submit()
            advanceUntilIdle()

            val afterCredErr = vm.uiState.value
            assertFalse(afterCredErr.isSubmitting)
            assertEquals("alice", afterCredErr.username)
            assertEquals("wrong_pw", afterCredErr.password)
            assertEquals(1, afterCredErr.securityQuestionId)
            assertEquals("王芳", afterCredErr.securityAnswer)
            assertEquals(LoginFieldError(LoginField.Password, R.string.common_error_login_credentials), afterCredErr.fieldError)
            assertEquals(LoginField.Password, afterCredErr.focusRequest?.field)
            assertNull(afterCredErr.generalErrorRes)
            val firstFocusId = requireNotNull(afterCredErr.focusRequest?.id)
            vm.consumeFocusRequest(firstFocusId)
            assertNull(vm.uiState.value.focusRequest)

            // 2) 安全提问回答错误：保留全部输入，挂在提问回答字段并聚焦提问回答
            repo.nextLoginResult = LoginResult.InvalidCredentials("安全提问回答错误")
            vm.submit()
            advanceUntilIdle()

            val afterSecErr = vm.uiState.value
            assertEquals("alice", afterSecErr.username)
            assertEquals("wrong_pw", afterSecErr.password)
            assertEquals(1, afterSecErr.securityQuestionId)
            assertEquals("王芳", afterSecErr.securityAnswer)
            assertEquals(
                LoginFieldError(LoginField.SecurityAnswer, R.string.common_error_login_security_answer),
                afterSecErr.fieldError,
            )
            assertEquals(LoginField.SecurityAnswer, afterSecErr.focusRequest?.field)
            assertTrue("重复聚焦必须生成递增 ID", (afterSecErr.focusRequest?.id ?: 0L) > firstFocusId)

            // 3) 网络错误：保留全部输入，使用规范文案“网络连接失败，请重试”
            repo.nextLoginResult = LoginResult.NetworkError("SocketTimeoutException HTTP 504", IOException("boom"))
            vm.submit()
            advanceUntilIdle()

            val afterNetErr = vm.uiState.value
            assertEquals("alice", afterNetErr.username)
            assertEquals("wrong_pw", afterNetErr.password)
            assertEquals("王芳", afterNetErr.securityAnswer)
            assertNull(afterNetErr.fieldError)
            assertEquals(R.string.login_error_network, afterNetErr.generalErrorRes)

            // 4) Cloudflare 验证失败：保留全部输入，挂出对应 ChallengeFailureKind
            repo.nextLoginResult = LoginResult.ChallengeRequired(cooldownUntilMs = 120_000L, rejected = true)
            vm.submit()
            advanceUntilIdle()

            val afterChallenge = vm.uiState.value
            assertEquals("alice", afterChallenge.username)
            assertEquals(ChallengeFailureKind.Rejected, afterChallenge.challengeFailure)
        }

    @Test
    fun submit_setsSubmittingImmediatelyAndBlocksDuplicateSubmissionsWhileInFlight() = runTest(testDispatcher) {
        val gate = CompletableDeferred<LoginResult>()
        val repo = FakeAuthRepository(pendingLogin = gate)
        val vm = LoginViewModel(repo, createIdleGateway(this))

        vm.onUsernameChange("alice")
        vm.onPasswordChange("secret")

        vm.submit()
        // 0ms 同步进入提交态
        assertTrue(vm.uiState.value.isSubmitting)

        // 连点防重
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, repo.loginCalls.size)

        gate.complete(LoginResult.Success(sampleAccount("alice")))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSubmitting)
        assertTrue(vm.uiState.value.loginSuccessEventId > 0L)
    }

    @Test
    fun uiStateToString_redactsPasswordAndSecurityAnswer() {
        val state = LoginUiState(
            username = "alice",
            password = "SuperSecretPassword99",
            securityQuestionId = 1,
            securityAnswer = "SensitiveAnswer88",
        )
        val text = state.toString()
        assertFalse(text.contains("SuperSecretPassword99"))
        assertFalse(text.contains("SensitiveAnswer88"))
        assertTrue(text.contains("alice"))
    }

    @Test
    fun submit_success_immediatelyClearsInMemoryPasswordAndSecurityAnswer() = runTest(testDispatcher) {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo, createIdleGateway(this))

        vm.onUsernameChange("alice")
        vm.onPasswordChange("PlainSecret_99")
        vm.onSecurityQuestionSelected(1)
        vm.onSecurityAnswerChange("PlainAnswer_88")

        vm.submit()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loginSuccessEventId > 0L)
        assertFalse(state.isSubmitting)
        assertEquals("登录成功后必须立即清空内存中的明文密码", "", state.password)
        assertEquals("登录成功后必须立即清空内存中的安全提问回答", "", state.securityAnswer)
    }

    @Test
    fun submit_categorizedFailuresMapToDistinctGeneralErrorResAndNeverFallbackToLoadFailed() = runTest(testDispatcher) {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo, createIdleGateway(this))
        vm.onUsernameChange("alice")
        vm.onPasswordChange("secret")

        // 1) UnrecognizedSubmitResponse
        repo.nextLoginResult = LoginResult.Failure("unknown body", LoginFailureReason.UnrecognizedSubmitResponse)
        vm.submit()
        advanceUntilIdle()
        assertEquals(R.string.login_error_unrecognized_response, vm.uiState.value.generalErrorRes)
        assertTrue(vm.uiState.value.generalErrorArgs.isEmpty())
        assertTrue(vm.uiState.value.generalErrorRes != R.string.common_error_load_failed)

        // 2) SessionNotConfirmed
        repo.nextLoginResult = LoginResult.Failure("no umenu", LoginFailureReason.SessionNotConfirmed)
        vm.submit()
        advanceUntilIdle()
        assertEquals(R.string.login_error_session_not_confirmed, vm.uiState.value.generalErrorRes)
        assertTrue(vm.uiState.value.generalErrorArgs.isEmpty())
        assertTrue(vm.uiState.value.generalErrorRes != R.string.common_error_load_failed)

        // 3) HttpStatus(code)
        repo.nextLoginResult = LoginResult.Failure("HTTP 502", LoginFailureReason.HttpStatus(502))
        vm.submit()
        advanceUntilIdle()
        assertEquals(R.string.login_error_http_status, vm.uiState.value.generalErrorRes)
        assertEquals(listOf(502), vm.uiState.value.generalErrorArgs)
        assertTrue(vm.uiState.value.generalErrorRes != R.string.common_error_load_failed)

        // Editing input clears both generalErrorRes and generalErrorArgs
        vm.onPasswordChange("secret2")
        assertNull(vm.uiState.value.generalErrorRes)
        assertTrue(vm.uiState.value.generalErrorArgs.isEmpty())
    }

    private fun createIdleGateway(scope: TestScope): SiteAccessGateway = SiteAccessGateway(
        client = OkHttpClient(),
        detector = ChallengeDetector(),
        solver = ChallengeSolver { SolveResult.Cleared },
        clock = { 10_000L },
        gatewayScope = scope.backgroundScope,
    )

    private fun sampleAccount(username: String) = SavedAccount(
        uid = "1001",
        username = username,
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 1_000L,
    )

    private data class LoginCall(
        val username: String,
        val password: String,
        val securityQuestionId: Int,
        val securityAnswer: String,
    )

    private class FakeAuthRepository(
        initialAccount: SavedAccount? = null,
        private val pendingLogin: CompletableDeferred<LoginResult>? = null,
    ) : AuthRepository {
        private val _currentAccount = MutableStateFlow(initialAccount)
        override val currentAccount: StateFlow<SavedAccount?> = _currentAccount.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initialAccount)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
        ).asStateFlow()
        override var sessionEpoch: Long = 1L

        val loginCalls = mutableListOf<LoginCall>()
        var logoutCalls = 0
        var logoutFailure: Throwable? = null
        var nextLoginResult: LoginResult = LoginResult.Success(
            SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1_000L),
        )

        override suspend fun login(
            username: String,
            passwordOrMd5: String,
            securityQuestionId: Int,
            securityAnswer: String,
        ): LoginResult {
            loginCalls += LoginCall(username, passwordOrMd5, securityQuestionId, securityAnswer)
            val result = pendingLogin?.await() ?: nextLoginResult
            if (result is LoginResult.Success) {
                _currentAccount.value = result.account
                sessionEpoch++
            }
            return result
        }

        override suspend fun switchAccount(uid: String): LoginResult = error("unused")
        override suspend fun logout() {
            logoutFailure?.let { throw it }
            logoutCalls++
            _currentAccount.value = null
        }

        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
            SilentReloginResult.Suppressed
    }
}
