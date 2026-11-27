package net.jejer.hipda.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.NotifyFeed
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.SmsConversationItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M7-3 验收 2（后台轮询与通知投递矩阵）：覆盖 API 28 / 33 / 35 / 36 下
 * `POST_NOTIFICATIONS` 权限缺失、运行时被撤回与 `SecurityException` 优雅吞并行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class NotificationPermissionWorkerSafeguardTest {

    @Test
    @Config(sdk = [28])
    fun api28_workerAndPoster_deliverNotificationWithoutPostNotificationsPermission() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()

        assertFalse(requiresNotificationPermission())
        assertTrue(hasNotificationPermission(app))

        val pollCount = AtomicInteger(0)
        val settingsRepo = FakeSettingsRepo(AppSettings(notificationsEnabled = true))
        val worker = buildWorker(
            context = app,
            settingsRepo = settingsRepo,
            notifyRepo = FakeNotifyRepo(pollCount, sampleUnreadSnapshot()),
            poster = AndroidNotificationPoster(app),
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, pollCount.get())
        assertNotNull("API 28 应正常投递通知", shadowOf(nm).getNotification(AndroidNotificationPoster.NOTIFICATION_ID))
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_permissionRevoked_workerSkipsSilentlyWithZeroNetworkAndPosterDoesNotCrash() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()

        assertTrue(requiresNotificationPermission())
        assertFalse(hasNotificationPermission(app))

        val pollCount = AtomicInteger(0)
        val settingsRepo = FakeSettingsRepo(AppSettings(notificationsEnabled = true))
        val poster = AndroidNotificationPoster(app)

        // 1. 直接调用 AndroidNotificationPoster.postUnread -> 静默返回，0 崩溃、0 通知
        poster.postUnread(sampleUnreadSnapshot())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())

        // 2. 后台 Worker 执行 -> 静默返回 Result.success()、0 网络请求、保持 notificationsEnabled=true 不被篡改
        val worker = buildWorker(
            context = app,
            settingsRepo = settingsRepo,
            notifyRepo = FakeNotifyRepo(pollCount, sampleUnreadSnapshot()),
            poster = poster,
        )
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("无 POST_NOTIFICATIONS 权限时后台轮询应 0 网络请求", 0, pollCount.get())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue("无权限静默返回不应篡改用户持久化开关", settingsRepo.state.value.notificationsEnabled)
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_midFlightSecurityException_swallowedGracefullyWithSuccessAndConsistentSettings() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val settingsRepo = FakeSettingsRepo(AppSettings(notificationsEnabled = true))
        val throwingPoster = object : NotificationPoster {
            override fun postUnread(snapshot: UnreadSnapshot) {
                throw SecurityException("POST_NOTIFICATIONS revoked mid-flight")
            }

            override fun cancel() {
                throw SecurityException("NotificationManager cancel denied")
            }
        }

        // Case 1: 非空未读投递时抛出 SecurityException -> 优雅吞并返回 Result.success()
        val postWorker = buildWorker(
            context = app,
            settingsRepo = settingsRepo,
            notifyRepo = FakeNotifyRepo(AtomicInteger(0), sampleUnreadSnapshot()),
            poster = throwingPoster,
        )
        assertEquals(ListenableWorker.Result.success(), postWorker.doWork())
        assertTrue(settingsRepo.state.value.notificationsEnabled)

        // Case 2: 零未读取消时抛出 SecurityException -> 优雅吞并返回 Result.success()
        val cancelWorker = buildWorker(
            context = app,
            settingsRepo = settingsRepo,
            notifyRepo = FakeNotifyRepo(AtomicInteger(0), UnreadSnapshot(0, 0)),
            poster = throwingPoster,
        )
        assertEquals(ListenableWorker.Result.success(), cancelWorker.doWork())
        assertTrue(settingsRepo.state.value.notificationsEnabled)
    }

    private fun sampleUnreadSnapshot(): UnreadSnapshot = UnreadSnapshot(
        smsUnreadCount = 1,
        notifyUnreadCount = 0,
        latestUnreadSms = SmsConversationItem(
            uid = "20001",
            author = "Alice",
            avatarUrl = null,
            time = "刚刚",
            summary = "新消息测试",
            isNew = true,
        ),
    )

    private fun buildWorker(
        context: Context,
        settingsRepo: SettingsRepository,
        notifyRepo: NotifyRepository,
        poster: NotificationPoster,
    ): NotificationPollingWorker = TestListenableWorkerBuilder<NotificationPollingWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = NotificationPollingWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    settingsRepository = settingsRepo,
                    authRepository = SignedInAuthRepo,
                    credentialsStore = EmptyCredStore,
                    notifyRepository = notifyRepo,
                    notificationPoster = poster,
                    appVisibilityProvider = AppVisibilityProvider { false },
                    pollingClock = PollingClock { QuietHoursChecker.minutesOfDay(12, 0) },
                )
            },
        )
        .build()

    private class FakeNotifyRepo(
        private val pollCalls: AtomicInteger,
        private val snapshot: UnreadSnapshot,
    ) : NotifyRepository {
        override val smsUnreadCount: StateFlow<Int> = MutableStateFlow(snapshot.smsUnreadCount)
        override val notifyUnreadCount: StateFlow<Int> = MutableStateFlow(snapshot.notifyUnreadCount)
        override val notifications: StateFlow<List<NotifyEntry>> = MutableStateFlow(emptyList())
        override suspend fun getNotifications(priority: AccessPriority): MessageResult<NotifyFeed> = error("unused")
        override suspend fun markNotificationRead(key: String) = Unit
        override suspend fun markAllNotificationsRead() = Unit
        override suspend fun pollUnreadCounts(priority: AccessPriority): NotificationPollResult {
            pollCalls.incrementAndGet()
            return NotificationPollResult.Success(snapshot)
        }
        override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
        override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) = Unit
        override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) = Unit
    }

    private object SignedInAuthRepo : AuthRepository {
        private val account = SavedAccount(uid = "10000", username = "HiUser", passwordMd5 = "md5", lastLoginAt = 1L)
        override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(account).asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOf(account)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow<AuthState>(AuthState.SignedIn(account)).asStateFlow()
        override val sessionEpoch: Long = 1L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult = error("unused")
        override suspend fun switchAccount(uid: String): LoginResult = error("unused")
        override suspend fun logout() = Unit
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult = SilentReloginResult.Suppressed
    }

    private object EmptyCredStore : CredentialsStore {
        override val accounts: Flow<List<SavedAccount>> = MutableStateFlow(emptyList())
        override val activeUid: Flow<String?> = MutableStateFlow("10000")
        override val manuallyLoggedOut: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun upsert(account: SavedAccount) = Unit
        override suspend fun setActive(uid: String) = Unit
        override suspend fun markManualLogout() = Unit
        override suspend fun remove(uid: String) = Unit
    }

    private class FakeSettingsRepo(initial: AppSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = state.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = state.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = state.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = state.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = state.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = state.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = state.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
        override suspend fun setTailEnabled(enabled: Boolean) = state.update { it.copy(tailEnabled = enabled) }
        override suspend fun setTailText(text: String) = state.update { it.copy(tailText = text) }
        override suspend fun setTailUrl(url: String) = state.update { it.copy(tailUrl = url) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = state.update { it.copy(notificationsEnabled = enabled) }
        override suspend fun setSilentModeEnabled(enabled: Boolean) = state.update { it.copy(silentModeEnabled = enabled) }
        override suspend fun setSilentBeginMinutes(minutes: Int) = state.update { it.copy(silentBeginMinutes = minutes) }
        override suspend fun setSilentEndMinutes(minutes: Int) = state.update { it.copy(silentEndMinutes = minutes) }
        override suspend fun setForumServer(server: String) = state.update { it.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) = state.update { it.copy(imageCacheLimitMb = limitMb) }
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = state.update { it.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = state.update { it.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedPortrait = collapsed) }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedLandscape = collapsed) }
    }
}
