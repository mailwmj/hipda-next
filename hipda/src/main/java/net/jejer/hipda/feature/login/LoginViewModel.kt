package net.jejer.hipda.feature.login

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

/** Discuz! 标准安全提问选项（`questionid` 0..7）。 */
@Immutable
data class SecurityQuestionOption(val id: Int, @param:StringRes val labelRes: Int)

val DISCUZ_SECURITY_QUESTIONS: List<SecurityQuestionOption> = listOf(
    SecurityQuestionOption(SavedAccount.NO_SECURITY_QUESTION, R.string.login_question_none),
    SecurityQuestionOption(1, R.string.login_question_1),
    SecurityQuestionOption(2, R.string.login_question_2),
    SecurityQuestionOption(3, R.string.login_question_3),
    SecurityQuestionOption(4, R.string.login_question_4),
    SecurityQuestionOption(5, R.string.login_question_5),
    SecurityQuestionOption(6, R.string.login_question_6),
    SecurityQuestionOption(7, R.string.login_question_7),
)

enum class LoginField { Username, Password, SecurityAnswer }

@Immutable
data class LoginFieldError(val field: LoginField, @param:StringRes val messageRes: Int)

@Immutable
data class LoginFocusRequest(val field: LoginField, val id: Long)

@Immutable
data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val securityQuestionId: Int = SavedAccount.NO_SECURITY_QUESTION,
    val securityAnswer: String = "",
    val isSubmitting: Boolean = false,
    val fieldError: LoginFieldError? = null,
    @param:StringRes val generalErrorRes: Int? = null,
    val generalErrorArgs: List<Any> = emptyList(),
    val challengeState: ChallengeState = ChallengeState.Idle,
    val challengeFailure: ChallengeFailureKind? = null,
    val focusRequest: LoginFocusRequest? = null,
    val loginSuccessEventId: Long = 0L,
) {
    val showSecurityAnswer: Boolean get() = securityQuestionId != SavedAccount.NO_SECURITY_QUESTION

    override fun toString(): String =
        "LoginUiState(username=$username, password=***, securityQuestionId=$securityQuestionId, " +
            "securityAnswer=***, isSubmitting=$isSubmitting, " +
            "fieldError=$fieldError, generalErrorRes=$generalErrorRes, challengeState=$challengeState, " +
            "challengeFailure=$challengeFailure, focusRequest=$focusRequest, loginSuccessEventId=$loginSuccessEventId)"
}

/** 登录 ViewModel（净化安全提问载荷、保留失败输入、精确路由出错字段焦点）。 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val eventIdGen = AtomicLong(0L)
    private var submitJob: Job? = null
    private val _uiState = MutableStateFlow(LoginUiState(challengeState = siteAccessGateway.challengeState.value))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            siteAccessGateway.challengeState.collect { challenge ->
                _uiState.update { cur ->
                    cur.copy(
                        challengeState = challenge,
                        challengeFailure = if (challenge is ChallengeState.Solving) null else cur.challengeFailure,
                    )
                }
            }
        }
    }

    fun onUsernameChange(value: String) = updateCredentialInput { copy(username = value) }
    fun onPasswordChange(value: String) = updateCredentialInput { copy(password = value) }

    fun onSecurityQuestionSelected(questionId: Int) {
        val validId = if (DISCUZ_SECURITY_QUESTIONS.any { it.id == questionId }) questionId else SavedAccount.NO_SECURITY_QUESTION
        _uiState.update { cur ->
            cur.copy(
                securityQuestionId = validId,
                securityAnswer = if (validId == SavedAccount.NO_SECURITY_QUESTION) "" else cur.securityAnswer,
                fieldError = cur.fieldError?.takeUnless { it.field == LoginField.SecurityAnswer },
                generalErrorRes = null,
                generalErrorArgs = emptyList(),
            )
        }
    }

    fun onSecurityAnswerChange(value: String) = _uiState.update { cur ->
        cur.copy(
            securityAnswer = value,
            fieldError = cur.fieldError?.takeUnless { it.field == LoginField.SecurityAnswer },
            generalErrorRes = null,
            generalErrorArgs = emptyList(),
        )
    }

    fun consumeFocusRequest(id: Long) = _uiState.update { if (it.focusRequest?.id == id) it.copy(focusRequest = null) else it }
    fun consumeLoginSuccess(id: Long) = _uiState.update { if (it.loginSuccessEventId == id) it.copy(loginSuccessEventId = 0L) else it }

    fun resetForm() {
        submitJob?.cancel()
        submitJob = null
        _uiState.update { LoginUiState(challengeState = siteAccessGateway.challengeState.value) }
    }

    fun submit() {
        val s = _uiState.value
        if (s.isSubmitting) return
        val trimmedUser = s.username.trim()
        val sanitizedAnswer = if (s.securityQuestionId == SavedAccount.NO_SECURITY_QUESTION) "" else s.securityAnswer
        when {
            trimmedUser.isEmpty() -> return reportFieldError(LoginField.Username, R.string.common_error_login_credentials)
            s.password.isEmpty() -> return reportFieldError(LoginField.Password, R.string.common_error_login_credentials)
            s.securityQuestionId != SavedAccount.NO_SECURITY_QUESTION && sanitizedAnswer.isBlank() ->
                return reportFieldError(LoginField.SecurityAnswer, R.string.common_error_login_security_answer)
        }
        _uiState.update {
            it.copy(securityAnswer = sanitizedAnswer, isSubmitting = true, fieldError = null, generalErrorRes = null, generalErrorArgs = emptyList(), challengeFailure = null, focusRequest = null)
        }
        submitJob = viewModelScope.launch {
            val result = try {
                authRepository.login(trimmedUser, s.password, s.securityQuestionId, sanitizedAnswer)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LoginResult.NetworkError(t.message.orEmpty(), t)
            }
            handleLoginResult(result)
        }
    }

    private fun updateCredentialInput(transform: LoginUiState.() -> LoginUiState) = _uiState.update { cur ->
        val clearCred = cur.fieldError?.field == LoginField.Username || cur.fieldError?.field == LoginField.Password
        cur.transform().copy(fieldError = if (clearCred) null else cur.fieldError, generalErrorRes = null, generalErrorArgs = emptyList())
    }

    private fun handleLoginResult(result: LoginResult) = when (result) {
        is LoginResult.Success -> _uiState.update {
            it.copy(
                password = "",
                securityAnswer = "",
                isSubmitting = false,
                fieldError = null,
                generalErrorRes = null,
                generalErrorArgs = emptyList(),
                challengeFailure = null,
                loginSuccessEventId = eventIdGen.incrementAndGet(),
            )
        }
        is LoginResult.InvalidCredentials -> {
            val isSecErr = UiErrorMapper.mapLoginResult(result)?.messageRes == R.string.common_error_login_security_answer
            val field = when {
                isSecErr -> LoginField.SecurityAnswer
                USERNAME_ERROR_KEYWORDS.any { it in result.message } -> LoginField.Username
                else -> LoginField.Password
            }
            reportFieldError(field, if (isSecErr) R.string.common_error_login_security_answer else R.string.common_error_login_credentials)
        }
        is LoginResult.ChallengeRequired -> _uiState.update {
            it.copy(isSubmitting = false, challengeFailure = if (result.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete)
        }
        is LoginResult.NetworkError -> _uiState.update {
            it.copy(isSubmitting = false, generalErrorRes = R.string.login_error_network, generalErrorArgs = emptyList())
        }
        is LoginResult.Failure -> {
            val mapped = UiErrorMapper.mapLoginResult(result)
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    generalErrorRes = mapped?.messageRes ?: R.string.common_error_load_failed,
                    generalErrorArgs = mapped?.formatArgs.orEmpty(),
                )
            }
        }
    }

    private fun reportFieldError(field: LoginField, @StringRes messageRes: Int) = _uiState.update {
        it.copy(isSubmitting = false, fieldError = LoginFieldError(field, messageRes), generalErrorRes = null, generalErrorArgs = emptyList(), focusRequest = LoginFocusRequest(field, eventIdGen.incrementAndGet()))
    }

    private companion object {
        val USERNAME_ERROR_KEYWORDS = listOf("请输入用户名", "用户名不存在", "用户不存在")
    }
}
