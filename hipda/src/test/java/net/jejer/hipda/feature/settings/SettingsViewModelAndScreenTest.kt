package net.jejer.hipda.feature.settings

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.di.SettingsProfileUiModule
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class SettingsViewModelAndScreenTest {

    @Before
    fun grantNotificationPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun appearanceSettings_persistAndThemeSwitchPreservesCompositionScrollAndFocus() = withAnimatorDurationScale(0f) {
        val repo = InMemorySettingsRepository()
        val vm = SettingsViewModel(repo, RecordingNotificationScheduler(), FakeCacheManager(), SettingsErrorLogStore { "" })
        var disposeCount = 0
        var rememberedToken: Any? = null
        val focusRequester = FocusRequester()

        setContent {
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val cfg = HiThemeSettingsAdapter.toThemeConfig(uiState.settings)
            HiTheme(cfg.themeMode, cfg.pureBlack, cfg.seed, cfg.fontSizeStep, cfg.lineHeightPreset) {
                val token = remember { Any() }
                rememberedToken = token
                DisposableEffect(Unit) { onDispose { disposeCount++ } }
                SettingsDetailPane(
                    group = SettingsGroup.Appearance,
                    viewModel = vm,
                    onOpenSystemNotificationSettings = { true },
                    showBack = true,
                    onBack = {},
                    onOpenThread = {},
                    blacklistContent = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        val initialToken = requireNotNull(rememberedToken)

        onNodeWithText("深色").performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, repo.state.value.themeMode)
        assertSame("主题切换不得重建设置页组合树", initialToken, rememberedToken)
        assertEquals("主题切换不得触发 onDispose", 0, disposeCount)

        onNodeWithText("纯黑深色背景").performClick()
        waitForIdle()
        assertTrue(repo.state.value.pureBlack)

        onNodeWithContentDescription(HiColorPresets.Red.label).performClick()
        waitForIdle()
        assertEquals(HiColorPresets.Red.id, repo.state.value.themeSeed)

        onNodeWithText("跟随壁纸").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(AppSettings.THEME_SEED_WALLPAPER, repo.state.value.themeSeed)

        onNodeWithText("18").performClick()
        waitForIdle()
        assertEquals(4, repo.state.value.fontStep)

        onNodeWithText("宽松").performScrollTo().performClick()
        waitForIdle()
        assertEquals(LineSpacing.LOOSE, repo.state.value.lineSpacing)

        onNodeWithText("浅色").performScrollTo().performClick()
        waitForIdle()
        assertEquals(ThemeMode.LIGHT, repo.state.value.themeMode)
        assertSame(initialToken, rememberedToken)
        assertEquals(0, disposeCount)
    }

    @Test
    fun forumNotificationNetworkAndAboutGroups_interactValidateAndSanitizeViewCopyShare() = withAnimatorDurationScale(0f) {
        val repo = InMemorySettingsRepository()
        val scheduler = RecordingNotificationScheduler()
        val imageClearGate = CompletableDeferred<Long>()
        val cacheManager = FakeCacheManager(initialImageBytes = 4L * 1024L * 1024L, pendingImageClear = imageClearGate, webClearBytes = 2L * 1024L * 1024L)
        // 传入未经预脱敏的合成假敏感日志，确保查看/复制/分享三条路径均走 SettingsViewModel 统一脱敏入口
        val rawUnsanitizedLogs = """
            CrashTraceLine
            Cookie: cdb_auth=SYNTH_SHARE_CK; __cf_bm=SYNTH_SHARE_CFBM
            password="SYNTH_SHARE_QPW" m_formhash=SYNTH_SHARE_MFH
            url: space.php?uid=765432&srchuid=876543&authorid=654321
            avatar: uc_server/data/avatar/000/76/54/32_avatar_middle.jpg
            json: {"uid":"765432","formhash":"SYNTH_SHARE_JFH","password":"a\"SYNTH_SHARE_JPW"}
            encoded: space.php%3Fuid%3D246813%26cdb_auth%3DSYNTH_SHARE_ECA
            SavedAccount(uid=765432, username=synth_user_share, passwordMd5=***, securityAnswer=***)
            UID: 852963
        """.trimIndent()
        val secrets = listOf(
            "SYNTH_SHARE_CK", "SYNTH_SHARE_CFBM", "SYNTH_SHARE_QPW", "SYNTH_SHARE_MFH",
            "765432", "876543", "654321", "000/76/54/32", "SYNTH_SHARE_JFH", "SYNTH_SHARE_JPW",
            "246813", "SYNTH_SHARE_ECA", "synth_user_share", "852963",
        )
        val vm = SettingsViewModel(repo, scheduler, cacheManager, SettingsErrorLogStore { rawUnsanitizedLogs })

        var currentGroup by mutableStateOf(SettingsGroup.ForumAndPost)
        var openedNotificationSettings = 0
        val openedThreads = mutableListOf<ThreadNavArgs>()

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsDetailPane(
                            group = currentGroup,
                            viewModel = vm,
                            onOpenSystemNotificationSettings = { openedNotificationSettings++; true },
                            showBack = false,
                            onBack = null,
                            onOpenThread = { openedThreads += it },
                            blacklistContent = { _, _ -> },
                            contentPadding = PaddingValues(0.dp),
                        )
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)

        // 1. 论坛与发帖
        onNodeWithText("显示置顶帖").performClick()
        waitForIdle()
        assertFalse(repo.state.value.showStickyThreads)

        onNodeWithText("小尾巴").performClick()
        waitForIdle()
        assertTrue(repo.state.value.tailEnabled)

        onNodeWithText("尾巴链接").assertIsDisplayed().performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("javascript:alert(1)")
        onNodeWithText("确定").performClick()
        waitForIdle()
        onNodeWithText("请输入以 http:// 或 https:// 开头的有效网址").assertIsDisplayed()
        assertEquals("", repo.state.value.tailUrl)

        onNode(hasSetTextAction()).performTextReplacement("https://example.com/tail")
        onNodeWithText("确定").performClick()
        waitForIdle()
        assertEquals("https://example.com/tail", repo.state.value.tailUrl)

        onNodeWithText("管理版块").performClick()
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithContentDescription("上移 Buy & Sell").performClick()
        waitForIdle()
        assertEquals(listOf(6, 2, 7), repo.state.value.followedForums)
        onNodeWithContentDescription("移除 Geek Talks").performClick()
        waitForIdle()
        assertEquals(listOf(6, 2), repo.state.value.followedForums)
        onNodeWithContentDescription("添加 E-INK").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(6, 2, 59), repo.state.value.followedForums)
        onNodeWithContentDescription("返回").performClick()
        waitForIdle()

        // 2. 通知与免打扰
        currentGroup = SettingsGroup.Notification
        waitForIdle()
        onNodeWithText("接收提醒通知").performClick()
        waitForIdle()
        assertFalse(repo.state.value.notificationsEnabled)
        assertEquals(listOf(false), scheduler.syncedValues)

        onNodeWithText("通知声音与样式").performClick()
        waitForIdle()
        assertEquals(1, openedNotificationSettings)

        onNodeWithText("免打扰时段").performClick()
        waitForIdle()
        assertTrue(repo.state.value.silentModeEnabled)
        onNodeWithText("开始时间").assertIsDisplayed()
        onNodeWithText("结束时间").assertIsDisplayed()

        // 3. 网络与缓存（含 S1 校验拦截 ?query/#fragment/userinfo）
        currentGroup = SettingsGroup.NetworkAndCache
        waitForIdle()
        onNodeWithText("论坛服务器").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextReplacement("https://user:pass@bbs.example.com?q=1#h")
        onNodeWithText("确定").performClick()
        waitForIdle()
        onNodeWithText("请输入以 http:// 或 https:// 开头的有效网址").assertIsDisplayed()
        assertEquals(AppSettings.DEFAULT_FORUM_SERVER, repo.state.value.forumServer)

        onNode(hasSetTextAction()).performTextReplacement("https://bbs.example.com/")
        onNodeWithText("确定").performClick()
        waitForIdle()
        assertEquals("https://bbs.example.com", repo.state.value.forumServer)

        onNodeWithText("图片缓存上限").performClick()
        waitForIdle()
        onNodeWithText("1 GB").performClick()
        waitForIdle()
        assertEquals(1024, repo.state.value.imageCacheLimitMb)

        onNodeWithText("已占用 4 MB").assertIsDisplayed()
        mainClock.autoAdvance = false
        onNodeWithText("清除图片缓存").performClick()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("正在清除…").assertIsDisplayed()
        imageClearGate.complete(4L * 1024L * 1024L)
        mainClock.advanceTimeBy(200L)
        waitForIdle()
        onNodeWithText("已清除 4 MB").assertIsDisplayed()

        mainClock.advanceTimeBy(4_500L)
        waitForIdle()
        onNodeWithText("清除网页缓存").performClick()
        mainClock.advanceTimeBy(200L)
        waitForIdle()
        onNodeWithText("已清除 2 MB").assertIsDisplayed()
        mainClock.advanceTimeBy(4_500L)
        mainClock.autoAdvance = true
        waitForIdle()

        // 4. 关于：版本、发布帖回调、错误日志查看 / 复制 / 分享三条路径全脱敏断言
        currentGroup = SettingsGroup.About
        waitForIdle()
        onNodeWithText("客户端发布帖").performClick()
        waitForIdle()
        assertEquals(CLIENT_RELEASE_TID, openedThreads.single().tid)

        // 4a. 查看
        onNodeWithText("查看").performClick()
        waitForIdle()
        onNodeWithText("CrashTraceLine", substring = true).assertIsDisplayed()
        secrets.forEach { secret -> onNodeWithText(secret, substring = true).assertDoesNotExist() }
        onNodeWithText("关闭").performClick()
        waitForIdle()

        // 4b. 复制
        val app = ApplicationProvider.getApplicationContext<Application>()
        onNodeWithText("复制").performClick()
        waitForIdle()
        val clip = app.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("复制日志应保留诊断堆栈", clip.contains("CrashTraceLine"))
        secrets.forEach { secret -> assertFalse("复制日志不得包含 [$secret]:\n$clip", clip.contains(secret)) }

        // 4c. 分享（捕获 Intent.ACTION_CHOOSER -> EXTRA_INTENT 的 ACTION_SEND EXTRA_TEXT）
        shadowOf(app).clearNextStartedActivities()
        onNodeWithText("分享").performClick()
        waitForIdle()
        val chooserIntent = shadowOf(app).nextStartedActivity
        assertNotNull("点击分享应发出 Chooser Intent", chooserIntent)
        assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
        @Suppress("DEPRECATION")
        val sendIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser 内应包裹 ACTION_SEND Intent", sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        val sharedText = sendIntent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue("分享日志应保留诊断堆栈", sharedText.contains("CrashTraceLine"))
        secrets.forEach { secret -> assertFalse("分享日志不得包含 [$secret]:\n$sharedText", sharedText.contains(secret)) }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun settingsDetail_rotationPreservesScrollPositionAndUnsavedTextDialogDraft() = withAnimatorDurationScale(0f) {
        val repo = InMemorySettingsRepository()
        val vm = SettingsViewModel(repo, RecordingNotificationScheduler(), FakeCacheManager(), SettingsErrorLogStore { "" })
        var group by mutableStateOf(SettingsGroup.Appearance)
        var mounted by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })

        setContent {
            HiTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (mounted) {
                        SettingsDetailPane(
                            group = group,
                            viewModel = vm,
                            onOpenSystemNotificationSettings = { true },
                            showBack = false,
                            onBack = null,
                            onOpenThread = {},
                            blacklistContent = { _, _ -> },
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        waitForIdle()

        // 1. 矮宽屏下将外观与阅读滚动到底部使“宽松”可见，模拟旋转保存与恢复
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("宽松"))
        waitForIdle()
        onNodeWithText("宽松").assertIsDisplayed()
        val topBefore = onNodeWithText("宽松").fetchSemanticsNode().boundsInRoot.top

        val savedScroll = registry.performSave()
        mounted = false
        waitForIdle()
        registry = SaveableStateRegistry(savedScroll) { true }
        mounted = true
        waitForIdle()

        val topAfter = onNodeWithText("宽松").fetchSemanticsNode().boundsInRoot.top
        assertEquals("旋转恢复后设置详情页滚动位置应保持不变", topBefore, topAfter, 4f)

        // 2. 切至网络与缓存 -> 打开“论坛服务器”对话框输入未提交草稿 -> 模拟旋转保存与恢复
        group = SettingsGroup.NetworkAndCache
        waitForIdle()
        onNodeWithText("论坛服务器").performClick()
        waitForIdle()
        val draftUrl = "https://draft-rotated.example.com"
        onNode(hasSetTextAction()).performTextReplacement(draftUrl)
        waitForIdle()

        val savedDialog = registry.performSave()
        mounted = false
        waitForIdle()
        registry = SaveableStateRegistry(savedDialog) { true }
        mounted = true
        waitForIdle()

        onNodeWithText(draftUrl).assertIsDisplayed()
        assertEquals("未点确定前草稿不应提前写入仓库", AppSettings.DEFAULT_FORUM_SERVER, repo.state.value.forumServer)
    }

    @Test
    fun settingsGroupsPane_displaysAllFiveGroupsAndNotifiesSelection() = withAnimatorDurationScale(0f) {
        val clicked = mutableListOf<SettingsGroup>()
        setContent {
            HiTheme {
                SettingsGroupsPane(
                    selectedGroup = SettingsGroup.Appearance,
                    isSidebar = true,
                    showBack = false,
                    onBack = null,
                    onSelectGroup = { clicked += it },
                    contentPadding = PaddingValues(0.dp),
                    listState = LazyListState(),
                )
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        listOf("外观与阅读", "论坛与发帖", "通知与免打扰", "网络与缓存", "关于").forEach { label ->
            onNodeWithText(label).assertIsDisplayed()
        }
        onNodeWithText("关于").performClick()
        assertEquals(listOf(SettingsGroup.About), clicked)
    }

    @Test
    fun settingsProfileUiModule_bindsSettingsProfilePaneRendererImplAsSingleton() {
        assertNotNull(SettingsProfileUiModule::class.java.getAnnotation(Module::class.java))
        assertTrue(SettingsProfilePaneRendererImpl::class.java.isAnnotationPresent(Singleton::class.java))
        assertTrue(SettingsProfilePaneRendererImpl::class.java.declaredConstructors.any { it.isAnnotationPresent(Inject::class.java) })
        val bindMethod = SettingsProfileUiModule::class.java.getDeclaredMethod("bindSettingsProfilePaneRenderer", SettingsProfilePaneRendererImpl::class.java)
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(SettingsProfilePaneRenderer::class.java, bindMethod.returnType)
    }

    private class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
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

    private class RecordingNotificationScheduler : NotificationScheduler {
        val syncedValues = mutableListOf<Boolean>()
        override fun syncSchedule(notificationsEnabled: Boolean) { syncedValues += notificationsEnabled }
        override suspend fun syncFromSettings() = Unit
        override fun schedule() { syncedValues += true }
        override fun cancel() { syncedValues += false }
    }

    private class FakeCacheManager(
        private var initialImageBytes: Long = 0L,
        private val pendingImageClear: CompletableDeferred<Long>? = null,
        private val webClearBytes: Long = 0L,
    ) : SettingsCacheManager {
        override suspend fun getImageCacheSizeBytes(): Long = initialImageBytes
        override suspend fun clearImageCache(): Long {
            val freed = pendingImageClear?.await() ?: initialImageBytes
            initialImageBytes = 0L
            return freed
        }
        override suspend fun clearWebCache(): Long = webClearBytes
    }
}
