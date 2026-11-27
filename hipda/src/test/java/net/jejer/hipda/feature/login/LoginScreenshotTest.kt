package net.jejer.hipda.feature.login

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.challenge.ChallengePanelOverlay
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E15 登录页与 `V-A6` 340dp+2x 验证面板 Roborazzi 截图基线（前缀 `login_`，共 8 张）：
 * - 覆盖 `411`/`749`/`1280dp` × 浅/深（含宽屏居中 `≤ 480dp`、安全提问展开态、字段报错态与网络错误态）
 * - 覆盖 `340dp` 窄屏登录态
 * - 覆盖 `V-A6` `340dp` + `fontScale = 2.0f` 验证面板头防截断/防重叠快照（`login_challenge_narrow_2x.png`，不破坏既有 7 张 `challenge_*.png`）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class LoginScreenshotTest {

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun login_w411_light() = captureLogin(
        name = "login_w411_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = LoginUiState(username = "hipda_user"),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun login_w411_dark_expanded_error() = captureLogin(
        name = "login_w411_dark_expanded_error",
        themeMode = HiThemeMode.DARK,
        uiState = LoginUiState(
            username = "hipda_user",
            password = "secret_password",
            securityQuestionId = 1,
            securityAnswer = "错误回答",
            fieldError = LoginFieldError(LoginField.SecurityAnswer, R.string.common_error_login_security_answer),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun login_w749_light_modal_error() = captureLogin(
        name = "login_w749_light_modal_error",
        themeMode = HiThemeMode.LIGHT,
        uiState = LoginUiState(
            username = "hipda_user",
            password = "wrong_password",
            fieldError = LoginFieldError(LoginField.Password, R.string.common_error_login_credentials),
        ),
        withBackdrop = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun login_w749_dark_modal_expanded() = captureLogin(
        name = "login_w749_dark_modal_expanded",
        themeMode = HiThemeMode.DARK,
        uiState = LoginUiState(
            username = "hipda_user",
            password = "secret_password",
            securityQuestionId = 3,
            securityAnswer = "北京",
        ),
        withBackdrop = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun login_w1280_light_modal() = captureLogin(
        name = "login_w1280_light_modal",
        themeMode = HiThemeMode.LIGHT,
        uiState = LoginUiState(username = "hipda_user"),
        withBackdrop = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun login_w1280_dark_modal_network_error() = captureLogin(
        name = "login_w1280_dark_modal_network_error",
        themeMode = HiThemeMode.DARK,
        uiState = LoginUiState(
            username = "hipda_user",
            password = "secret_password",
            generalError = UiError(R.string.login_error_network),
        ),
        withBackdrop = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.NARROW_340_800)
    fun login_w340_light_narrow() = captureLogin(
        name = "login_w340_light_narrow",
        themeMode = HiThemeMode.LIGHT,
        uiState = LoginUiState(
            username = "hipda_user",
            password = "secret_password",
            securityQuestionId = 1,
            securityAnswer = "王芳",
            fieldError = LoginFieldError(LoginField.Password, R.string.common_error_login_credentials),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.NARROW_340_800)
    fun login_challenge_narrow_2x() = withAnimatorDurationScale(0f) {
        setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 2.0f)) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        MockBackdrop()
                        ChallengePanelOverlay(
                            visible = true,
                            onRefresh = {},
                            onCancel = {},
                            webViewSlot = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "www.4d4y.com",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText("安全验证").assertIsDisplayed()
        onNodeWithText("请完成页面上的验证以继续访问").assertIsDisplayed()
        onNodeWithText("刷新").assertIsDisplayed()
        onNodeWithText("取消").assertIsDisplayed()
        assertGlassSurfaceCountAtMost(0)
        onRoot().captureHiSnapshot("login_challenge_narrow_2x")
    }

    private fun captureLogin(
        name: String,
        themeMode: HiThemeMode,
        uiState: LoginUiState,
        withBackdrop: Boolean = false,
    ) = withAnimatorDurationScale(0f) {
        setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.fillMaxSize()) {
                    if (withBackdrop) MockBackdrop()
                    LoginScreenContent(
                        uiState = uiState,
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
        }
        waitForIdle()
        onNodeWithText("登录 HiPDA").assertIsDisplayed()
        assertGlassSurfaceCountAtMost(0)
        onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun MockBackdrop() {
    val cs = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Discovery", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
            repeat(5) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = HiShapeTokens.Medium,
                    color = cs.surfaceContainerLow,
                ) {}
            }
        }
    }
}
