package net.jejer.hipda.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.asString
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.hiFullscreenOverlaySafePadding
import net.jejer.hipda.ui.designsystem.feedback.HiIconSwitch
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private val LOGIN_MODAL_WIDTH_BREAKPOINT = 600.dp
private val LOGIN_MODAL_HEIGHT_BREAKPOINT = 480.dp
private val LOGIN_MAX_WIDTH = 480.dp
private const val LOGIN_SCRIM_ALPHA = 0.40f

/** M5 完整登录页：单栏全屏推入，`≥ 600dp` 覆盖全部栏居中模态卡片（最大宽 `480dp`，纯色不用毛玻璃）。 */
@Composable
fun LoginScreen(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.loginSuccessEventId) {
        val eventId = uiState.loginSuccessEventId
        if (eventId > 0L) {
            viewModel.consumeLoginSuccess(eventId)
            onLoginSuccess()
        }
    }
    LoginScreenContent(
        uiState = uiState,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSecurityQuestionSelected = viewModel::onSecurityQuestionSelected,
        onSecurityAnswerChange = viewModel::onSecurityAnswerChange,
        onSubmit = viewModel::submit,
        onDismiss = onDismiss,
        onConsumeFocusRequest = viewModel::consumeFocusRequest,
        modifier = modifier,
    )
}

@Composable
fun LoginScreenContent(
    uiState: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSecurityQuestionSelected: (Int) -> Unit,
    onSecurityAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onConsumeFocusRequest: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowInfo.current.containerDpSize
    val isModalCard = windowSize.width >= LOGIN_MODAL_WIDTH_BREAKPOINT && windowSize.height >= LOGIN_MODAL_HEIGHT_BREAKPOINT
    val title = stringResource(R.string.login_title)
    BackHandler(onBack = onDismiss)

    val formBody: @Composable (Modifier) -> Unit = { bodyModifier ->
        LoginFormBody(
            uiState = uiState,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onSecurityQuestionSelected = onSecurityQuestionSelected,
            onSecurityAnswerChange = onSecurityAnswerChange,
            onSubmit = onSubmit,
            onConsumeFocusRequest = onConsumeFocusRequest,
            modifier = bodyModifier,
        )
    }

    if (isModalCard) {
        Box(modifier = modifier.fillMaxSize()) {
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = LOGIN_SCRIM_ALPHA))
                    .clickable(scrimInteraction, indication = null, enabled = !uiState.isSubmitting, onClick = onDismiss),
            )
            Surface(
                modifier = Modifier.align(Alignment.Center).hiFullscreenOverlaySafePadding(includeTop = true, includeBottomNav = true, includeIme = true).padding(24.dp)
                    .widthIn(max = LOGIN_MAX_WIDTH).fillMaxWidth()
                    .semantics { paneTitle = title; isTraversalGroup = true },
                shape = HiShapeTokens.Large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    LoginTopBar(title, isModalCard = true, onDismiss = onDismiss)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    formBody(Modifier)
                }
            }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().hiFullscreenOverlaySafePadding(includeTop = true, includeBottomNav = true, includeIme = true)) {
                LoginTopBar(title, isModalCard = false, onDismiss = onDismiss)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    formBody(Modifier.widthIn(max = LOGIN_MAX_WIDTH))
                }
            }
        }
    }
}

@Composable
private fun LoginTopBar(title: String, isModalCard: Boolean, onDismiss: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val btnMod = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isModalCard) 20.dp else 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isModalCard) {
            IconButton(onClick = onDismiss, interactionSource = interaction, modifier = btnMod) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = if (isModalCard) 0.dp else 8.dp).semantics { heading() },
        )
        if (isModalCard) {
            IconButton(onClick = onDismiss, interactionSource = interaction, modifier = btnMod) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.login_action_close))
            }
        }
    }
}

@Composable
private fun LoginFormBody(
    uiState: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSecurityQuestionSelected: (Int) -> Unit,
    onSecurityAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onConsumeFocusRequest: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val questionFocus = remember { FocusRequester() }
    val answerFocus = remember { FocusRequester() }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.focusRequest?.id, uiState.isSubmitting, uiState.showSecurityAnswer) {
        val request = uiState.focusRequest ?: return@LaunchedEffect
        if (uiState.isSubmitting) return@LaunchedEffect
        val target = when (request.field) {
            LoginField.Username -> usernameFocus
            LoginField.Password -> passwordFocus
            LoginField.SecurityAnswer -> if (uiState.showSecurityAnswer) answerFocus else questionFocus
        }
        runCatching { target.requestFocus() }
        onConsumeFocusRequest(request.id)
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val usernameErr = uiState.fieldError?.takeIf { it.field == LoginField.Username }
        OutlinedTextField(
            value = uiState.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.login_field_username)) },
            singleLine = true,
            enabled = !uiState.isSubmitting,
            isError = usernameErr != null,
            supportingText = usernameErr?.let { err -> { FieldErrorText(err.messageRes) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
        )

        val passwordErr = uiState.fieldError?.takeIf { it.field == LoginField.Password }
        val toggleInteraction = remember { MutableInteractionSource() }
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_field_password)) },
            singleLine = true,
            enabled = !uiState.isSubmitting,
            isError = passwordErr != null,
            supportingText = passwordErr?.let { err -> { FieldErrorText(err.messageRes) } },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = !uiState.isSubmitting,
                    interactionSource = toggleInteraction,
                    modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(toggleInteraction),
                ) {
                    HiIconSwitch(
                        target = if (passwordVisible) LoginIcons.VisibilityOff else LoginIcons.Visibility,
                        contentDescription = stringResource(if (passwordVisible) R.string.login_cd_hide_password else R.string.login_cd_show_password),
                        iconSize = 22.dp,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (uiState.showSecurityAnswer) ImeAction.Next else ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { if (uiState.showSecurityAnswer) answerFocus.requestFocus() },
                onDone = { onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
        )

        val answerErr = uiState.fieldError?.takeIf { it.field == LoginField.SecurityAnswer }
        SecurityQuestionDropdown(
            selectedQuestionId = uiState.securityQuestionId,
            enabled = !uiState.isSubmitting,
            collapsedErrorRes = if (!uiState.showSecurityAnswer) answerErr?.messageRes else null,
            focusRequester = questionFocus,
            onSelect = onSecurityQuestionSelected,
        )

        AnimatedVisibility(
            visible = uiState.showSecurityAnswer,
            enter = hiExpandVertically(HiMotion.spatialDefault(), expandFrom = Alignment.Top) + hiFadeIn(),
            exit = hiShrinkVertically(HiMotion.exit(HiMotion.spatialDefault()), shrinkTowards = Alignment.Top) + hiFadeOut(),
        ) {
            OutlinedTextField(
                value = uiState.securityAnswer,
                onValueChange = onSecurityAnswerChange,
                label = { Text(stringResource(R.string.login_field_security_answer)) },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                isError = answerErr != null,
                supportingText = answerErr?.let { err -> { FieldErrorText(err.messageRes) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(answerFocus),
            )
        }

        uiState.generalError?.let { err ->
            Text(
                text = err.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.error,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        val submitInteraction = remember { MutableInteractionSource() }
        val submittingDesc = stringResource(R.string.login_status_submitting)
        Button(
            onClick = onSubmit,
            enabled = !uiState.isSubmitting,
            shape = HiShapeTokens.Small,
            interactionSource = submitInteraction,
            colors = ButtonDefaults.buttonColors(disabledContainerColor = cs.primary.copy(alpha = 0.72f), disabledContentColor = cs.onPrimary),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).defaultMinSize(minHeight = 48.dp).hiPressScale(submitInteraction)
                .then(if (uiState.isSubmitting) Modifier.semantics { contentDescription = submittingDesc } else Modifier),
        ) {
            if (uiState.isSubmitting) {
                HiLoadingSpinner(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary, trackColor = cs.onPrimary.copy(alpha = 0.28f))
            } else {
                Text(stringResource(R.string.login_action_submit))
            }
        }

        ChallengeStatusView(state = uiState.challengeState, onRetry = { onSubmit() }, failure = uiState.challengeFailure, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityQuestionDropdown(
    selectedQuestionId: Int,
    enabled: Boolean,
    collapsedErrorRes: Int?,
    focusRequester: FocusRequester,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = remember(selectedQuestionId) {
        DISCUZ_SECURITY_QUESTIONS.firstOrNull { it.id == selectedQuestionId } ?: DISCUZ_SECURITY_QUESTIONS.first()
    }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.login_field_security_question)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            isError = collapsedErrorRes != null,
            supportingText = collapsedErrorRes?.let { errRes -> { FieldErrorText(errRes) } },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DISCUZ_SECURITY_QUESTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = { expanded = false; onSelect(option.id) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldErrorText(messageRes: Int) = Text(
    text = stringResource(messageRes),
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
)

private object LoginIcons {
    val Visibility: ImageVector by lazy {
        ImageVector.Builder("LoginVisibility", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 4.5f); curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f); curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
                reflectiveCurveTo(21.27f, 16.39f, 23f, 12f); curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f); close()
                moveTo(12f, 17f); curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f); reflectiveCurveTo(9.24f, 7f, 12f, 7f)
                reflectiveCurveTo(17f, 9.24f, 17f, 12f); reflectiveCurveTo(14.76f, 17f, 12f, 17f); close()
                moveTo(12f, 9f); curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f); reflectiveCurveTo(10.34f, 15f, 12f, 15f)
                reflectiveCurveTo(15f, 13.66f, 15f, 12f); reflectiveCurveTo(13.66f, 9f, 12f, 9f); close()
            }
        }.build()
    }

    val VisibilityOff: ImageVector by lazy {
        ImageVector.Builder("LoginVisibilityOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 7f); curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f); curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
                lineTo(19.56f, 16.75f); curveTo(21.07f, 15.49f, 22.26f, 13.86f, 23f, 12f); curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
                curveTo(10.6f, 4.5f, 9.26f, 4.75f, 8.04f, 5.2f); lineTo(10.2f, 7.36f); curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f); close()
                moveTo(2f, 4.27f); lineTo(4.74f, 7.01f); curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
                curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f); curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
                lineTo(19.73f, 22f); lineTo(21f, 20.73f); lineTo(3.27f, 3f); lineTo(2f, 4.27f); close()
                moveTo(7.53f, 9.8f); lineTo(9.08f, 11.35f); curveTo(9.03f, 11.56f, 9f, 11.78f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f); curveTo(12.22f, 15f, 12.44f, 14.97f, 12.65f, 14.92f)
                lineTo(14.2f, 16.47f); curveTo(13.53f, 16.8f, 12.79f, 17f, 12f, 17f)
                curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f); curveTo(7f, 11.21f, 7.2f, 10.47f, 7.53f, 9.8f); close()
            }
        }.build()
    }
}
