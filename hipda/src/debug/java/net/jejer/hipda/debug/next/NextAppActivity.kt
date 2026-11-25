package net.jejer.hipda.debug.next

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.debug.sampleexport.SampleExportActivity
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.forum.ForumViewModel

/**
 * Debug 新版阅读宿主 Activity（仅注册在 `src/debug/AndroidManifest.xml`，绝不进入 release）。
 */
@AndroidEntryPoint
class NextAppActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var notifyRepository: NotifyRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var loginOverlayRenderer: Optional<LoginOverlayRenderer>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NextAppRoot(
                settingsFlow = settingsRepository.settings,
                currentAccountFlow = authRepository.currentAccount,
                smsUnreadCountFlow = notifyRepository.smsUnreadCount,
                notifyUnreadCountFlow = notifyRepository.notifyUnreadCount,
                imageLoader = imageLoader,
                loginOverlayRenderer = loginOverlayRenderer.orElse(null),
                hostViewModel = hiltViewModel<NextAppHostViewModel>(),
                forumViewModel = hiltViewModel<ForumViewModel>(),
                onFallbackLoginClick = {
                    startActivity(SampleExportActivity.createIntent(this))
                },
            )
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, NextAppActivity::class.java)
    }
}
