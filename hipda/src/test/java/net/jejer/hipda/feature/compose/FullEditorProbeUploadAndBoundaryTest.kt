package net.jejer.hipda.feature.compose

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import coil3.ColorImage
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.observe
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.DefaultImageUploadRepository
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.ImageCompressionResult
import net.jejer.hipda.data.post.ImageCompressor
import net.jejer.hipda.data.post.ImageUploadInput
import net.jejer.hipda.data.post.ImageUploadProgress
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V-E6 独立 `MockWebServer` 上传/发帖集成探针与边界验证（全程离线，严禁访问真实论坛）：
 * 1. 补充 (c) + 探针 3：真实 `ImageCompressor + DefaultImageUploadRepository + SiteAccessGateway + MockWebServer`
 *    5 张 `content://` 图片并发上传（默认 `original = false` 自动压缩）、中途取消 1 张、失败 1 张重试、
 *    汇总进度 `正在上传 {current}/{total}`、`MockWebServer` 计数校验及临时文件 0 残留；
 * 2. 真实 `DefaultPostRepository + Room DraftDao + MockWebServer`：点击缩略图插入块、UI 0 `[attachimg]` 文本、
 *    未插图发送自动附尾、草稿发送成功删库；
 * 3. `R-D6 S1`：3 个 `ImageUploadInput` 子类默认 `original == false`，普通大图自动压缩，合规 GIF 与超长图直传。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullEditorProbeUploadAndBoundaryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val composeRule = createComposeRule()

    class ProbeImageContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = backingFiles[uri] ?: throw java.io.FileNotFoundException("Missing $uri")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            val pfd = openFile(uri, mode)
            return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        companion object {
            const val AUTHORITY = "net.jejer.hipda.probe.upload.images"
            val backingFiles = LinkedHashMap<Uri, File>()
        }
    }

    private class ProbeCredentialsStore : CredentialsStore {
        private val _accounts = MutableStateFlow<List<SavedAccount>>(emptyList())
        private val _activeUid = MutableStateFlow<String?>(null)
        private val _manuallyLoggedOut = MutableStateFlow(false)
        override val accounts: Flow<List<SavedAccount>> = _accounts.asStateFlow()
        override val activeUid: Flow<String?> = _activeUid.asStateFlow()
        override val manuallyLoggedOut: Flow<Boolean> = _manuallyLoggedOut.asStateFlow()
        override suspend fun upsert(account: SavedAccount) {
            _accounts.update { cur -> cur.filterNot { it.uid == account.uid } + account }
        }
        override suspend fun setActive(uid: String) {
            _activeUid.value = uid
            _manuallyLoggedOut.value = false
        }
        override suspend fun markManualLogout() {
            _activeUid.value = null
            _manuallyLoggedOut.value = true
        }
        override suspend fun remove(uid: String) {
            _accounts.update { cur -> cur.filterNot { it.uid == uid } }
            if (_activeUid.value == uid) _activeUid.value = null
        }
    }

    private class ProbeSettingsRepo(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        override suspend fun setPureBlack(enabled: Boolean) = Unit
        override suspend fun setThemeSeed(themeSeed: String) = Unit
        override suspend fun setFontStep(step: Int) = Unit
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
        override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
        override suspend fun setShowStickyThreads(show: Boolean) = Unit
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
        override suspend fun setTailEnabled(enabled: Boolean) = Unit
        override suspend fun setTailText(text: String) = Unit
        override suspend fun setTailUrl(url: String) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
        override suspend fun setSilentEndMinutes(minutes: Int) = Unit
        override suspend fun setForumServer(server: String) = Unit
        override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
    }

    private lateinit var virtualClockMs: AtomicLong
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var database: HiDatabase
    private lateinit var workDir: File
    private lateinit var sourceDir: File
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(ctx)
        ProbeImageContentProvider.backingFiles.clear()
        Robolectric.setupContentProvider(ProbeImageContentProvider::class.java, ProbeImageContentProvider.AUTHORITY)

        virtualClockMs = AtomicLong(1_700_000_000_000L)
        workDir = tempFolder.newFolder("probe_upload_work")
        sourceDir = tempFolder.newFolder("probe_source_imgs")
        server = MockWebServer()
        server.start()
        database = Room.inMemoryDatabaseBuilder(ctx, HiDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        ProbeImageContentProvider.backingFiles.clear()
        scope.cancel()
        clientsToClose.forEach {
            it.dispatcher.cancelAll()
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        clientsToClose.clear()
        database.close()
        server.close()
    }

    private data class RealStack(
        val settingsRepo: ProbeSettingsRepo,
        val authRepo: DefaultAuthRepository,
        val postRepo: DefaultPostRepository,
        val uploadRepo: DefaultImageUploadRepository,
        val compressor: ImageCompressor,
    )

    private fun createRealStack(): RealStack {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val baseUrl = server.url("").toString().trimEnd('/')
        val settingsRepo = ProbeSettingsRepo(AppSettings(forumServer = baseUrl, tailEnabled = false))
        val creds = ProbeCredentialsStore().also {
            runBlocking {
                val acc = SavedAccount(uid = "10000", username = "HiUser", passwordMd5 = computeLegacyPasswordMd5("secret"), lastLoginAt = 1L)
                it.upsert(acc)
                it.setActive(acc.uid)
            }
        }
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "Mozilla/5.0" },
            transportInterceptor = TransportInterceptor({ null }, { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val gateway = SiteAccessGateway(primaryClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { virtualClockMs.get() }, scope, 5.seconds)
        val recoveryGateway = SiteAccessGateway(recoveryClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { virtualClockMs.get() }, scope, 5.seconds)
        val authRepo = DefaultAuthRepository(creds, cookieStore, settingsRepo, gateway, recoveryGateway, { virtualClockMs.get() }, scope)
        val forumHttp = ForumHttpClient(gateway, settingsRepo, authRepo)
        val postRepo = DefaultPostRepository(forumHttp, authRepo, settingsRepo, database.draftDao(), { virtualClockMs.get() }, scope)
        val compressor = ImageCompressor(ctx, { workDir }, Dispatchers.IO)
        val uploadRepo = DefaultImageUploadRepository(gateway, settingsRepo, authRepo, compressor, { virtualClockMs.get() })
        return RealStack(settingsRepo, authRepo, postRepo, uploadRepo, compressor)
    }

    private fun createContentUri(name: String, width: Int, height: Int, maxSeed: Int): Uri {
        val file = File(sourceDir, name)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rnd = Random(maxSeed.toLong())
        val px = IntArray(width * height) {
            Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        bmp.setPixels(px, 0, width, 0, 0, width, height)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = Uri.parse("content://${ProbeImageContentProvider.AUTHORITY}/$name").also {
            ProbeImageContentProvider.backingFiles[it] = file
        }
        val bytesRead = ApplicationProvider.getApplicationContext<Application>().contentResolver
            .openInputStream(uri)?.use { it.readBytes().size } ?: -1
        assertTrue("ContentResolver 必须可读 $uri，实际字节=$bytesRead", bytesRead > 0)
        return uri
    }

    private fun waitUntilWithLooper(
        timeoutMs: Long = 10_000L,
        diagnostic: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            runBlocking { kotlinx.coroutines.delay(15) }
            composeRule.waitForIdle()
        }
        assertTrue("等待超时(${timeoutMs}ms): ${diagnostic()}", predicate())
    }

    private fun prePostHtml(formhash: String = "fh_real_1", maxKb: Int = 256): String = """
        <html><head><script>var discuz_uid = 10000;</script></head><body>
        <div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite></div>
        <form id="postform" method="post" action="post.php?action=reply&tid=5566">
          <input type="hidden" name="formhash" value="$formhash" />
          <input type="hidden" name="hash" value="hash_real_999" />
          <div class="uploadinfo">文件尺寸: 小于 ${maxKb}KB</div>
          <textarea name="message"></textarea>
        </form></body></html>
    """.trimIndent()

    /** 补充 (c) + 探针 3：5 张真实 `content://` 图片并发压缩上传、中途取消第 3 张、第 5 张先失败后重试、`MockWebServer` 计数与临时文件全清。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe6_supplementC_mockWebServer_5Images_compressDefault_cancel1_fail1Retry_countsAndTempCleanup() {
        val stack = createRealStack()
        val vm = FullEditorViewModel(stack.postRepo, stack.uploadRepo, stack.authRepo, stack.settingsRepo, virtualClockMs::get)

        val uris = (1..5).map { idx -> createContentUri("img_$idx.png", 640, 480, idx * 31) }
        val originalSizes = uris.map { ProbeImageContentProvider.backingFiles[it]!!.length() }
        assertTrue("原始 PNG 应大于 256KB 以触发默认压缩", originalSizes.all { it > 256 * 1024 })

        val uploadRequestCount = AtomicInteger(0)
        val blockedUploadInFlight = CountDownLatch(1)
        val releaseBlockedUpload = CountDownLatch(1)
        val retryReachedServer = AtomicInteger(0)
        val uploadedBodyBytes = java.util.concurrent.CopyOnWriteArrayList<Long>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.contains("post.php?action=reply") && request.method == "GET" ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=gbk").body(prePostHtml()).build()

                    target.contains("misc.php?action=swfupload") -> {
                        val n = uploadRequestCount.incrementAndGet()
                        uploadedBodyBytes.add(request.body?.size?.toLong() ?: 0L)
                        when (n) {
                            1 -> MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|101|1").build()
                            2 -> MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|102|1").build()
                            3 -> {
                                blockedUploadInFlight.countDown()
                                releaseBlockedUpload.await(5, TimeUnit.SECONDS)
                                MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|103|1").build()
                            }
                            4 -> MockResponse.Builder().code(500).body("Server Error").build()
                            5 -> MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|104|1").build()
                            else -> {
                                retryReachedServer.incrementAndGet()
                                MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|105|1").build()
                            }
                        }
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val fakeImageLoader = object : ImageLoader {
            override val defaults = ImageRequest.Defaults.DEFAULT
            override val components = ComponentRegistry()
            override val memoryCache: MemoryCache? = null
            override val diskCache: DiskCache? = null
            override fun enqueue(request: ImageRequest): Disposable {
                val res = SuccessResult(ColorImage(Color.GRAY, 72, 72), request, DataSource.MEMORY)
                return object : Disposable {
                    override val job = CompletableDeferred(res)
                    override val isDisposed = true
                    override fun dispose() = Unit
                }
            }
            override suspend fun execute(request: ImageRequest): ImageResult =
                SuccessResult(ColorImage(Color.GRAY, 72, 72), request, DataSource.MEMORY)
            override fun newBuilder() = throw UnsupportedOperationException()
            override fun shutdown() = Unit
        }
        val snackbar = object : PaneSnackbarController {
            override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) = Unit
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPaneSnackbarController provides snackbar,
                LocalHiImageLoader provides fakeImageLoader,
            ) {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {})
                }
            }
        }

        // 先等待真实 DefaultAuthRepository 完成异步水合再打开编辑器，并直接诊断 prePost 状态
        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000L
            while (stack.authRepo.authState.value !is net.jejer.hipda.data.auth.AuthState.SignedIn &&
                System.currentTimeMillis() < deadline
            ) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue("authRepo should hydrate to SignedIn, was ${stack.authRepo.authState.value}", stack.authRepo.authState.value is net.jejer.hipda.data.auth.AuthState.SignedIn)
        composeRule.runOnIdle {
            vm.open(FullEditorRequest.Reply(FullEditorHandoff(tid = "5566", fid = 2, text = "多图探针正文", selectionStart = 6, selectionEnd = 6)))
        }
        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000L
            while (vm.uiState.value.prePostInfo == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(10)
            }
        }
        composeRule.waitForIdle()
        assertNotNull(
            "prePostInfo should be loaded; loggedIn=${vm.uiState.value.isLoggedIn}, prefetching=${vm.uiState.value.isPrefetchingPrePost}, inlineErr=${vm.uiState.value.inlineError}, reqCount=${server.requestCount}",
            vm.uiState.value.prePostInfo,
        )

        // 一次选取 5 张图并泵主循环等待第 3 张请求在 MockWebServer 挂起（绝不阻塞 Robolectric 主线程！）
        composeRule.runOnIdle { vm.onPickImages(uris) }
        waitUntilWithLooper(
            timeoutMs = 15_000L,
            diagnostic = { "reqCount=${uploadRequestCount.get()}, blocked=${blockedUploadInFlight.count}, uploads=${vm.uiState.value.uploads}" },
        ) {
            blockedUploadInFlight.count == 0L &&
                vm.uiState.value.uploads.size == 5 &&
                vm.uiState.value.uploads.count { it.status is UploadAttachmentStatus.Uploaded } == 3 &&
                vm.uiState.value.uploads.count { it.isFailed } == 1 &&
                vm.uiState.value.uploads.count { it.isInProgress } == 1
        }
        // 汇总文案「正在上传 5/5」必须可见（3 已完成 + 1 失败 = 4 个终态，current=(4+1)=5）
        val summary = vm.uiState.value.uploadSummaryProgress
        assertEquals(5 to 5, summary)
        composeRule.onNodeWithText("正在上传 5/5").assertIsDisplayed()

        // 中途取消仍在上传中的那张，放行服务端 latch 确认其不会复活为已上传
        val inProgressIdx = vm.uiState.value.uploads.indexOfFirst { it.isInProgress }
        assertTrue(inProgressIdx >= 0)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(inProgressIdx)
        composeRule.waitForIdle()
        composeRule.runOnIdle { vm.removeUpload(vm.uiState.value.uploads[inProgressIdx].localId) }
        releaseBlockedUpload.countDown()

        waitUntilWithLooper(
            timeoutMs = 10_000L,
            diagnostic = { "after cancel uploads=${vm.uiState.value.uploads}" },
        ) {
            val st = vm.uiState.value
            st.uploads.size == 4 && !st.hasActiveUploads && st.uploads.count { it.isFailed } == 1
        }
        assertEquals(3, vm.uiState.value.uploadedImageIds.size)
        assertFalse("被取消的 103 严禁进入已上传列表", "103" in vm.uiState.value.uploadedImageIds)
        assertNull("无在途上传后汇总进度应清零", vm.uiState.value.uploadSummaryProgress)
        assertEquals(0, composeRule.onAllNodes(hasText("正在上传", substring = true)).fetchSemanticsNodes().size)

        // 滚到失败图并点击重试图标（「上传失败，点击重试」）
        val failedIdx = vm.uiState.value.uploads.indexOfFirst { it.isFailed }
        assertTrue(failedIdx >= 0)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(failedIdx)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("上传失败，点击重试").assertIsDisplayed().performClick()
        waitUntilWithLooper(
            timeoutMs = 10_000L,
            diagnostic = { "after retry uploads=${vm.uiState.value.uploads}" },
        ) {
            val st = vm.uiState.value
            !st.hasActiveUploads && !st.hasFailedUploads && st.uploadedImageIds.size == 4
        }

        // 核对 MockWebServer 请求计数与压缩效果（5 次首轮请求 + 1 次重试请求 = 6 次；每次上传均经过默认压缩）
        assertEquals("MockWebServer 应精确收到 6 次 swfupload 请求（4 成/取消挂起 + 1 败 + 1 重试）", 6, uploadRequestCount.get())
        assertEquals(1, retryReachedServer.get())
        assertTrue("默认 original=false 下上传体字节应显著小于原图 PNG", uploadedBodyBytes.all { it < originalSizes.min() })

        // 所有临时目录（含被取消项与重试项）必须 0 残留文件
        waitUntilWithLooper(
            timeoutMs = 5_000L,
            diagnostic = { "leftover files=${workDir.listFiles()?.map { it.name }}" },
        ) { (workDir.listFiles()?.size ?: 0) == 0 }
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    /** 探针 3（续）：真实 `DefaultPostRepository + Room DraftDao + MockWebServer` 验证点击缩略图插入块（UI 0 `[attachimg]` 文本）与未插图自动附尾。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe7_inlineThumbnailBlock_noRawAttachimg_autoAppendUninsertedOnRealPostRepoSubmit() {
        val stack = createRealStack()
        val vm = FullEditorViewModel(stack.postRepo, stack.uploadRepo, stack.authRepo, stack.settingsRepo, virtualClockMs::get)

        val uri1 = createContentUri("inline_1.png", 400, 300, 11)
        val uri2 = createContentUri("tail_2.png", 400, 300, 22)
        val uploadSeq = AtomicInteger(200)
        var capturedPostBody = ""

        // ForumHttpClient 依赖最终 response.url 包含 viewthread.php?tid=... 判定成功；使用 302 重定向到 viewthread.php
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.contains("post.php?action=reply") && request.method == "GET" ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=gbk").body(prePostHtml()).build()

                    target.contains("misc.php?action=swfupload") ->
                        MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|${uploadSeq.incrementAndGet()}|1").build()

                    target.contains("post.php?action=reply") && request.method == "POST" -> {
                        capturedPostBody = request.body?.string(Charset.forName("GBK")).orEmpty()
                        MockResponse.Builder().code(302)
                            .addHeader("Location", "${server.url("/forum/viewthread.php?tid=7788&page=1")}")
                            .build()
                    }

                    target.contains("viewthread.php?tid=7788") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=gbk")
                            .body("<html><body><div id=\"umenu\"><cite><a href=\"space.php?uid=10000\">HiUser</a></cite></div><div id=\"postlist\"></div></body></html>").build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val fakeImageLoader = object : ImageLoader {
            override val defaults = ImageRequest.Defaults.DEFAULT
            override val components = ComponentRegistry()
            override val memoryCache: MemoryCache? = null
            override val diskCache: DiskCache? = null
            override fun enqueue(request: ImageRequest): Disposable {
                val res = SuccessResult(ColorImage(Color.GRAY, 72, 72), request, DataSource.MEMORY)
                return object : Disposable {
                    override val job = CompletableDeferred(res)
                    override val isDisposed = true
                    override fun dispose() = Unit
                }
            }
            override suspend fun execute(request: ImageRequest): ImageResult =
                SuccessResult(ColorImage(Color.GRAY, 72, 72), request, DataSource.MEMORY)
            override fun newBuilder() = throw UnsupportedOperationException()
            override fun shutdown() = Unit
        }
        val snackbar = object : PaneSnackbarController {
            override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) = Unit
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPaneSnackbarController provides snackbar,
                LocalHiImageLoader provides fakeImageLoader,
            ) {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {})
                }
            }
        }

        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000L
            while (stack.authRepo.authState.value !is net.jejer.hipda.data.auth.AuthState.SignedIn &&
                System.currentTimeMillis() < deadline
            ) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue("authRepo should hydrate to SignedIn, was ${stack.authRepo.authState.value}", stack.authRepo.authState.value is net.jejer.hipda.data.auth.AuthState.SignedIn)
        composeRule.runOnIdle {
            vm.open(FullEditorRequest.Reply(FullEditorHandoff(tid = "7788", fid = 2, text = "前后文本", selectionStart = 2, selectionEnd = 2)))
        }
        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000L
            while (vm.uiState.value.prePostInfo == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(10)
            }
        }
        composeRule.waitForIdle()
        assertNotNull(
            "prePostInfo should be loaded; loggedIn=${vm.uiState.value.isLoggedIn}, prefetching=${vm.uiState.value.isPrefetchingPrePost}, inlineErr=${vm.uiState.value.inlineError}, reqCount=${server.requestCount}",
            vm.uiState.value.prePostInfo,
        )

        composeRule.runOnIdle { vm.onPickImages(listOf(uri1, uri2)) }
        waitUntilWithLooper(
            timeoutMs = 10_000L,
            diagnostic = { "probe7 uploads=${vm.uiState.value.uploads}" },
        ) { vm.uiState.value.uploadedImageIds.size == 2 }
        composeRule.waitForIdle()

        // 两张上传完毕但均未插入正文时，均显示「未插入」角标
        assertEquals(2, composeRule.onAllNodesWithText("未插入").fetchSemanticsNodes().size)
        val firstCardAttachId = (vm.uiState.value.uploads[0].status as UploadAttachmentStatus.Uploaded).imageId
        val secondCardAttachId = (vm.uiState.value.uploads[1].status as UploadAttachmentStatus.Uploaded).imageId

        // 点击第一张缩略图在光标 (2) 处插入正文
        composeRule.onAllNodesWithContentDescription("插入图片")[0].performClick()
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithText("未插入").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()

        // 全语义树扫描：严禁向用户暴露 `[attachimg]` 原始标签文本
        val allNodes = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val leaks = allNodes.filter { node ->
            val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
            val editable = node.config.getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text
            val cds = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")
            listOf(texts, editable, cds).any { it.contains("[attachimg]", ignoreCase = true) }
        }
        assertTrue("编辑器 UI 严禁暴露 [attachimg] 源码文本，发现=$leaks", leaks.isEmpty())

        // 点发送：未插图的第二张应按顺序自动附在正文末尾，不弹询问框，并提交两个 attachnew
        composeRule.onNodeWithText("发送").performClick()
        waitUntilWithLooper(
            timeoutMs = 10_000L,
            diagnostic = { "probe7 submit isOpen=${vm.uiState.value.isOpen}, err=${vm.uiState.value.inlineError}" },
        ) { !vm.uiState.value.isOpen }

        val formParams = capturedPostBody.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) null else URLDecoder.decode(part.substring(0, idx), "GBK") to URLDecoder.decode(part.substring(idx + 1), "GBK")
        }
        val sentMessage = formParams.first { it.first == "message" }.second
        assertTrue(
            "已插入的 $firstCardAttachId 必须保留在正文插入处，实际=$sentMessage",
            sentMessage.contains("前后\n[attachimg]$firstCardAttachId[/attachimg]\n文本"),
        )
        assertTrue(
            "未插入的 $secondCardAttachId 必须自动附在正文末尾，实际=$sentMessage",
            sentMessage.endsWith("[attachimg]$secondCardAttachId[/attachimg]"),
        )
        val attachNewValues = formParams.filter { it.first == "attachnew[][description]" }.map { it.second }
        assertEquals(2, attachNewValues.size)
        assertEquals(setOf(firstCardAttachId, secondCardAttachId), attachNewValues.toSet())
        assertNull(runBlocking { database.draftDao().observe(DraftKey.reply("7788")).first() })
    }

    /** 探针 3 / R-D6 S1：断言 3 种 `ImageUploadInput` 默认 `original == false`，普通图自动压缩，合规 GIF 与超长图在 `original == false` 下直传。 */
    @Test
    fun probe8_rD6_s1_defaultOriginalFalse_andDirectPassThroughForGifAndUltraLong() = runBlocking {
        val dummyFile = File(sourceDir, "dummy.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(ImageUploadInput.ContentUri(Uri.parse("content://a/b")).original)
        assertFalse(ImageUploadInput.LocalFile(dummyFile).original)
        assertFalse(ImageUploadInput.Bytes(byteArrayOf(1, 2, 3)).original)

        val stack = createRealStack()
        // 普通大图（600x450 PNG > 32KB）默认 original = false 必须执行压缩
        val normalUri = createContentUri("normal_photo.png", 600, 450, 77)
        val normalFile = ProbeImageContentProvider.backingFiles[normalUri]!!
        val normalRes = stack.compressor.prepare(ImageUploadInput.LocalFile(normalFile), maxUploadBytes = 32 * 1024) as ImageCompressionResult.Success
        try {
            assertTrue("普通照片在默认 original=false 下必须执行压缩", normalRes.image.wasCompressed)
            assertEquals("image/jpeg", normalRes.image.mimeType)
        } finally {
            normalRes.image.cleanup()
        }

        // 超长图 (AR = 4.0 >= 3.0) 在 default original = false 下必须直传
        val longFile = File(sourceDir, "ultra_long.png")
        val longBmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888)
        FileOutputStream(longFile).use { longBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        longBmp.recycle()
        val longRes = stack.compressor.prepare(ImageUploadInput.LocalFile(longFile), maxUploadBytes = 512 * 1024) as ImageCompressionResult.Success
        try {
            assertFalse("超长图在 default original=false 下仍应直传", longRes.image.wasCompressed)
        } finally {
            longRes.image.cleanup()
        }

        // 合规 GIF 在 default original = false 下必须直传
        val gifBytes = ByteArrayOutputStream().apply {
            write("GIF89a".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(10, 0, 10, 0, 0, 0, 0))
            write(byteArrayOf(0x2C, 0, 0, 0, 0, 10, 0, 10, 0, 0))
            write(byteArrayOf(2, 2, 0x44, 1, 0, 0x3B))
        }.toByteArray()
        val gifRes = stack.compressor.prepare(ImageUploadInput.Bytes(gifBytes, mimeType = "image/gif"), maxUploadBytes = 512 * 1024) as ImageCompressionResult.Success
        try {
            assertFalse("合规 GIF 在 default original=false 下仍应直传", gifRes.image.wasCompressed)
            assertEquals("image/gif", gifRes.image.mimeType)
        } finally {
            gifRes.image.cleanup()
        }

        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }
}
