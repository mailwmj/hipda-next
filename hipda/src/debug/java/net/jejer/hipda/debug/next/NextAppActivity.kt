package net.jejer.hipda.debug.next

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
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
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.routing.AttachmentDownloader
import net.jejer.hipda.feature.routing.EXTRA_DEEP_LINK_URL
import net.jejer.hipda.utils.Constants

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
    lateinit var attachmentDownloader: AttachmentDownloader

    @Inject lateinit var loginOverlayRenderer: Optional<LoginOverlayRenderer>
    @Inject lateinit var messagesPaneRenderer: Optional<MessagesPaneRenderer>
    @Inject lateinit var searchMinePaneRenderer: Optional<SearchMinePaneRenderer>
    @Inject lateinit var settingsProfilePaneRenderer: Optional<SettingsProfilePaneRenderer>
    @Inject lateinit var imageViewerRenderer: Optional<ImageViewerRenderer>

    private val hostViewModel: NextAppHostViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this)[NextAppHostViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            hostViewModel.submitIncomingIntent(intent)
        }
        setContent {
            NextAppRoot(
                settingsFlow = settingsRepository.settings,
                currentAccountFlow = authRepository.currentAccount,
                smsUnreadCountFlow = notifyRepository.smsUnreadCount,
                notifyUnreadCountFlow = notifyRepository.notifyUnreadCount,
                imageLoader = imageLoader,
                loginOverlayRenderer = loginOverlayRenderer.orElse(null),
                messagesPaneRenderer = messagesPaneRenderer.orElse(null),
                searchMinePaneRenderer = searchMinePaneRenderer.orElse(null),
                settingsProfilePaneRenderer = settingsProfilePaneRenderer.orElse(null),
                imageViewerRenderer = imageViewerRenderer.orElse(null),
                attachmentDownloader = attachmentDownloader,
                hostViewModel = hostViewModel,
                forumViewModel = hiltViewModel<ForumViewModel>(),
                onFallbackLoginClick = {
                    startActivity(SampleExportActivity.createIntent(this))
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hostViewModel.submitIncomingIntent(intent)
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, NextAppActivity::class.java)

        fun createDeepLinkIntent(context: Context, url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url), context, NextAppActivity::class.java)
                .putExtra(EXTRA_DEEP_LINK_URL, url)

        fun createShortcutIntent(context: Context, action: String): Intent =
            Intent(action).setClass(context, NextAppActivity::class.java)

        fun createNotificationIntent(
            context: Context,
            smsCount: Int = 0,
            threadCount: Int = 0,
            uid: String? = null,
        ): Intent = Intent(Constants.INTENT_NOTIFICATION).setClass(context, NextAppActivity::class.java).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, smsCount)
            putExtra(Constants.EXTRA_THREAD_COUNT, threadCount)
            if (!uid.isNullOrBlank()) putExtra(Constants.EXTRA_UID, uid)
        }
    }
}
