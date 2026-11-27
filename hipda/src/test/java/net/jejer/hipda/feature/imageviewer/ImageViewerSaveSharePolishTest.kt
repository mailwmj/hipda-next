package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M7-5b 验收 A-b & A-c 测试：
 * - A-b：假 `ImageViewerMediaActions` 返回 true/false 时各自出现 `已保存到相册` / `保存失败` Snackbar，
 *   且在独立看图与 `NextAppRoot` 真实链路下均位于底部工具栏之上不被遮挡；
 * - A-c：传入 `cachedFile` 或命中 Coil `diskCache` 时 `shareImage` 不调用网络/下载器；
 *   分享进行中按钮语义变为 disabled、阻止重复点击、展示按钮内进度圈，结束后恢复可点。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerSaveSharePolishTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("m7-5b-viewer-polish").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun aB_standaloneOverlay_saveTrueAndFalse_showsSnackbarStrictlyAboveBottomBar() {
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides ViewerTestImageLoader.solid(800, 600)) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/save_standalone.jpg")),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = mediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val saveBtnBounds = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInWindow

        // 1. 返回 true -> 显示「已保存到相册」，且底缘位于底栏按钮顶缘之上（不被工具栏挡住）
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        val okSnackBounds = composeRule.onNodeWithText("已保存到相册").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue(
            "成功 Snackbar 底缘(${okSnackBounds.bottom})必须严格位于底部工具栏按钮顶缘(${saveBtnBounds.top})之上",
            okSnackBounds.bottom <= saveBtnBounds.top,
        )

        // 等短时长 Snackbar 自动消失后再测失败分支
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.waitForIdle()

        // 2. 返回 false -> 显示「保存失败」，同样位于底部工具栏之上
        mediaActions.saveResult = false
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        val failSnackBounds = composeRule.onNodeWithText("保存失败").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue(
            "失败 Snackbar 底缘(${failSnackBounds.bottom})必须严格位于底部工具栏按钮顶缘(${saveBtnBounds.top})之上",
            failSnackBounds.bottom <= saveBtnBounds.top,
        )
    }

    @Test
    fun aB_realNextAppRoot_saveTrueAndFalse_showsSnackbarStrictlyAboveImageViewerToolbar() {
        val imgUrl = "https://img.example.invalid/save_root.jpg"
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
        val loader = ViewerTestImageLoader { req ->
            SuccessResult(ColorImage(0xFF336699.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        val harness = MultiPaneTestHarness()
        val repo = SingleImageRepo(imgUrl)
        val owner = ownerWithRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), mediaActions)
        harness.hostViewModel.openThread(ThreadNavArgs("5501", "看图保存反馈链路帖", 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val thumbMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            !hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))
        composeRule.onAllNodes(thumbMatcher, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

        val saveBtnBounds = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInWindow

        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        val okSnackBounds = composeRule.onNodeWithText("已保存到相册").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue(
            "NextAppRoot 中保存成功 Snackbar 底缘(${okSnackBounds.bottom})不得与看图底栏(${saveBtnBounds.top})重叠",
            okSnackBounds.bottom <= saveBtnBounds.top,
        )

        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.waitForIdle()

        mediaActions.saveResult = false
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        val failSnackBounds = composeRule.onNodeWithText("保存失败").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue(
            "NextAppRoot 中保存失败 Snackbar 底缘(${failSnackBounds.bottom})不得与看图底栏(${saveBtnBounds.top})重叠",
            failSnackBounds.bottom <= saveBtnBounds.top,
        )
    }

    @Test
    fun aC_shareImage_withCachedFile_neverCallsDownloaderOrNetwork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cachedPng = writeTempPngFile(tempDir, "cached_share.png")
        val downloader = FakeOriginalImageDownloader {
            throw AssertionError("已有 cachedFile 时严禁调用 OriginalImageDownloader")
        }
        val fakeUri = Uri.parse("content://net.jejer.hipda.next.provider/external_storage_root/share.png")
        val mediaActions = AndroidImageViewerMediaActions(
            context = app,
            originalDownloader = downloader,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            uriProvider = { _, _ -> fakeUri },
        )

        val shared = runBlocking {
            mediaActions.shareImage("https://img.example.invalid/cached.png", cachedFile = cachedPng)
        }
        assertTrue("传入 cachedFile 分享应成功", shared)
        assertTrue("传入 cachedFile 时不得调用下载器: ${downloader.requestedUrls}", downloader.requestedUrls.isEmpty())
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
    }

    @Test
    fun aC_shareImage_withCoilDiskCacheHit_reusesDiskCacheWithoutSecondNetworkRequest() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createPngBytes(width = 64, height = 64)
        val server = MockWebServer()
        server.start()
        val cacheDir = File(tempDir, "coil_disk_cache")
        val realCoil = ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() })) }
            .diskCache { DiskCache.Builder().directory(cacheDir).maxSizeBytes(10L * 1024L * 1024L).build() }
            .build()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Content-Length", pngBytes.size.toString())
                    .body(Buffer().write(pngBytes))
                    .build(),
            )
            val url = server.url("/coil_cached.png").toString()

            // 先由 Coil 加载一次图片并落盘磁盘缓存
            val loadResult = runBlocking {
                realCoil.execute(ImageRequest.Builder(app).data(url).build())
            }
            assertTrue("Coil 首次加载应成功: $loadResult", loadResult is SuccessResult)
            assertEquals(1, server.requestCount)

            val downloader = FakeOriginalImageDownloader {
                throw AssertionError("命中 Coil 磁盘缓存时严禁再触发 OriginalImageDownloader 网络下载")
            }
            val fakeUri = Uri.parse("content://net.jejer.hipda.next.provider/external_storage_root/share_coil.png")
            var copiedFile: File? = null
            val mediaActions = AndroidImageViewerMediaActions(
                context = app,
                originalDownloader = downloader,
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
                uriProvider = { _, file ->
                    copiedFile = file
                    fakeUri
                },
                diskCacheProvider = { realCoil.diskCache },
            )

            // cachedFile = null（未点“查看原图”直接点“分享”）：应直接命中 Coil 磁盘缓存，0 新增网络请求
            val shared = runBlocking { mediaActions.shareImage(url, cachedFile = null) }
            assertTrue("命中 Coil 磁盘缓存时分享应成功", shared)
            assertTrue("命中 Coil 磁盘缓存时下载器请求数必须为 0", downloader.requestedUrls.isEmpty())
            assertEquals("服务器总请求数仍必须保持为 1（无二次下载）", 1, server.requestCount)
            assertEquals(pngBytes.size.toLong(), copiedFile?.length())
        } finally {
            realCoil.shutdown()
            server.close()
        }
    }

    @Test
    fun aC_shareButton_isDisabledDuringShareBlocksDoubleClickAndRestoresAfterCompletion() {
        val firstShareGate = CompletableDeferred<Boolean>()
        val shareCalls = AtomicInteger(0)
        val suspendingActions = object : ImageViewerMediaActions {
            override suspend fun saveImage(url: String, cachedFile: File?): Boolean = true
            override suspend fun shareImage(url: String, cachedFile: File?): Boolean {
                shareCalls.incrementAndGet()
                return firstShareGate.await()
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides ViewerTestImageLoader.solid(800, 600)) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/share_busy.jpg")),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = suspendingActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 初始状态按钮可点
        composeRule.onNodeWithText("分享").assertIsDisplayed().assertIsEnabled()

        // 2. 点击分享后立即进入进行中：按钮语义 disabled，期间重复点击无效
        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()
        assertEquals("首次点击应触发 1 次 shareImage", 1, shareCalls.get())
        composeRule.onNodeWithText("分享").assertIsNotEnabled()

        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()
        assertEquals("进行中再次点击绝不能重复触发 shareImage", 1, shareCalls.get())

        // 3. 分享完成后按钮恢复可点
        firstShareGate.complete(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("分享").assertIsEnabled()
    }

    private fun ownerWithRepo(
        harness: MultiPaneTestHarness,
        repo: ThreadDetailRepository,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(repo, harness.gateway) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = factory
        }
    }

    private class SingleImageRepo(private val imageUrl: String) : ThreadDetailRepository {
        override suspend fun loadPage(
            tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
        ): ThreadDetailLoadResult {
            val floor = PostFloor(
                postId = "${tid}_1", floor = 1, page = page, uid = "1", author = "贴图作者", avatarUrl = null,
                timePost = "2026-11-27 08:00", isWarned = false, postStatus = null, signature = null, poll = null,
                blocks = listOf(
                    ContentBlock.Text(listOf(InlineSpan.Plain("看图保存反馈测试正文"))),
                    ContentBlock.Image(fullUrl = imageUrl, thumbUrl = imageUrl, fileSizeBytes = 1024L),
                ),
            )
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "看图保存测试帖", page, 1, listOf(floor), 1, offlinePageMeta(), true),
                filter = filter, forumName = forumName, threadAuthorId = "1",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "5501", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }
}
