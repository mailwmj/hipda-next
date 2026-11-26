package net.jejer.hipda.feature.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.routing.AttachmentDownloader
import net.jejer.hipda.feature.routing.EXTRA_DEEP_LINK_URL
import net.jejer.hipda.ui.designsystem.applyHiEdgeToEdge
import net.jejer.hipda.ui.designsystem.isSystemNightModeActive
import net.jejer.hipda.utils.Constants

/**
 * 正式主入口 Activity：承载新版 Compose 外壳 [NextAppRoot]，统一接管桌面图标启动、
 * `.ng.IntentActivity` 外部深链、桌面快捷方式与系统通知点击，并在配置变更与历史重建时避免重放旧 Intent。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        applyHiEdgeToEdge(darkTheme = isSystemNightModeActive())
        if (savedInstanceState == null && !isLaunchedFromHistory(intent)) {
            sanitizeIncomingIntent(intent)?.let(hostViewModel::submitIncomingIntent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sanitizeIncomingIntent(intent)?.let(hostViewModel::submitIncomingIntent)
    }

    companion object {
        fun createNotificationIntent(
            context: Context,
            smsCount: Int = 0,
            threadCount: Int = 0,
            uid: String? = null,
        ): Intent = Intent(Constants.INTENT_NOTIFICATION).setClass(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_SMS_COUNT, smsCount)
            putExtra(Constants.EXTRA_THREAD_COUNT, threadCount)
            if (!uid.isNullOrBlank()) putExtra(Constants.EXTRA_UID, uid)
        }

        internal fun isLaunchedFromHistory(intent: Intent?): Boolean =
            intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

        /**
         * 导出入口安全边界：只提取已知字段构造干净 Intent，免疫不可反序列化 Extra 导致的 `BadParcelableException`，
         * 并显式丢弃 [Constants.EXTRA_USERNAME] 等不信任字段。
         */
        internal fun sanitizeIncomingIntent(raw: Intent?): Intent? {
            if (raw == null) return null
            val action = raw.action
            val data = raw.data
            val explicitUrl = runCatching { raw.getStringExtra(EXTRA_DEEP_LINK_URL) }.getOrNull()
            val smsCount = runCatching { raw.getIntExtra(Constants.EXTRA_SMS_COUNT, 0) }.getOrDefault(0)
            val threadCount = runCatching { raw.getIntExtra(Constants.EXTRA_THREAD_COUNT, 0) }.getOrDefault(0)
            val uid = runCatching { raw.getStringExtra(Constants.EXTRA_UID) }.getOrNull()
            return Intent(action).apply {
                if (data != null) this.data = data
                if (explicitUrl != null) putExtra(EXTRA_DEEP_LINK_URL, explicitUrl)
                if (action == Constants.INTENT_NOTIFICATION) {
                    putExtra(Constants.EXTRA_SMS_COUNT, smsCount)
                    putExtra(Constants.EXTRA_THREAD_COUNT, threadCount)
                    if (!uid.isNullOrBlank()) putExtra(Constants.EXTRA_UID, uid)
                }
            }
        }
    }
}
