package net.jejer.hipda.ui.designsystem

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ComposeFoundationPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composePlaceholderRenders() {
        composeRule.setContent {
            ComposeFoundationPlaceholder(label = "HiPDA Compose Ready")
        }

        composeRule.onNodeWithText("HiPDA Compose Ready").assertIsDisplayed()
    }
}
