package net.jejer.hipda.baselineprofile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.routing.AttachmentDownloader

/**
 * `nonMinifiedRelease`-only host Activity for generating the Compose Baseline Profile against
 * real production [NextAppRoot] using a loopback-only offline HTTP fixture server.
 *
 * Excluded from `release` and `debug` APKs, and filtered out from generated profile rules.
 */
@AndroidEntryPoint
class BaselineProfileHostActivity : ComponentActivity() {

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

        val loopbackServer = intent.getStringExtra(EXTRA_FORUM_SERVER)
            ?.trim()
            ?.takeIf { LOOPBACK_SERVER_REGEX.matches(it) }
        if (loopbackServer != null) {
            Log.i(TAG, "Configuring loopback forum server: $loopbackServer")
            runBlocking {
                settingsRepository.setForumServer(loopbackServer)
            }
        } else {
            Log.w(TAG, "No valid loopback forum server extra on intent: ${intent.extras}")
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
            )
        }
    }

    companion object {
        private const val TAG: String = "BaselineProfile"
        const val EXTRA_FORUM_SERVER: String = "net.jejer.hipda.baselineprofile.EXTRA_FORUM_SERVER"
        private val LOOPBACK_SERVER_REGEX = Regex("""^http://127\.0\.0\.1:\d{1,5}$""")
    }
}
