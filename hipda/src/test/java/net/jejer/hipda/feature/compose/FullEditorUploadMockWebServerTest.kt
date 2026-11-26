package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.DraftEntity
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
import net.jejer.hipda.data.post.ImageCompressor
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.TestImageFactory
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E6 验收项 4（完整 `MockWebServer` 离线集成测试，绝不访问真实站点）：
 * 使用真实 `FullEditorViewModel` + 真实 `DefaultImageUploadRepository` + 真实 `ImageCompressor` + 真实 `DefaultPostRepository`：
 * 1. 验证选图默认 `original = false` 自动压缩普通 PNG 照片为 JPEG（`Hi_*.jpg`），合规 GIF 直传；
 * 2. 验证多图并发上传分阶段进度、汇总 `正在上传 {current}/{total}`、单张失败重试不影响同伴、移除取消在途上传并清理临时文件；
 * 3. 验证点击缩略图光标处插入内联缩略图块 + 未插图按序自动附尾 + 最终 POST 表单精确包含 `attachnew[{id}][description]` 与 `[attachimg]`；
 * 4. 验证不安全 URI scheme（`file://`/`javascript:`/`intent:`/`data:`/`http://`）零网络请求直接拦截。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullEditorUploadMockWebServerTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var virtualClockMs: AtomicLong
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        workDir = tempFolder.newFolder("e6_upload_work")
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
        Dispatchers.resetMain()
    }

    private fun createRealViewModel(): FullEditorViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credentialsStore = PostTestCredentialsStore()
        val settingsRepo = PostTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/'), tailEnabled = false),
        )
        runBlocking {
            val acc = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
            credentialsStore.upsert(acc)
            credentialsStore.setActive(acc.uid)
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) }
        val primaryGw = SiteAccessGateway(primaryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val recoveryGw = SiteAccessGateway(recoveryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val authRepo = DefaultAuthRepository(credentialsStore, cookieStore, settingsRepo, primaryGw, recoveryGw, { virtualClockMs.get() }, scope)
        val forumHttp = ForumHttpClient(primaryGw, settingsRepo, authRepo)
        val postRepo = DefaultPostRepository(forumHttp, authRepo, settingsRepo, InMemoryDraftDao(), { virtualClockMs.get() }, scope)
        val compressor = ImageCompressor(context, { workDir }, Dispatchers.IO)
        val uploadRepo = DefaultImageUploadRepository(primaryGw, settingsRepo, authRepo, compressor, { virtualClockMs.get() })
        return FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo, { virtualClockMs.get() })
    }

    @Test
    fun multiImageUpload_compressDefault_retryFailure_cancelInFlight_inlineAndAutoAppendSubmit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowResolver = shadowOf(context.contentResolver)

        // 1) 普通 PNG 照片（走默认 original=false 自动压缩成 JPEG）、2) 合规 GIF（直传）、3) 慢上传用于取消测试
        val photo1Bytes = TestImageFactory.writeSolidPng(File(tempFolder.root, "photo1.png"), 160, 120).readBytes()
        val gif2Bytes = TestImageFactory.createValidGifBytes(width = 16, height = 16, paddingBytes = 96)
        val slow3Bytes = TestImageFactory.writeNoisyPng(File(tempFolder.root, "slow3.png"), 480, 360).readBytes()

        val uri1 = Uri.parse("content://net.jejer.hipda.test/media/photo1.png")
        val uri2 = Uri.parse("content://net.jejer.hipda.test/media/anim2.gif")
        val uri3 = Uri.parse("content://net.jejer.hipda.test/media/slow3.png")
        shadowResolver.registerInputStream(uri1, ByteArrayInputStream(photo1Bytes))
        shadowResolver.registerInputStream(uri2, ByteArrayInputStream(gif2Bytes))
        shadowResolver.registerInputStream(uri3, ByteArrayInputStream(slow3Bytes))

        val uploadBodies = CopyOnWriteArrayList<String>()
        val submittedPostBody = CopyOnWriteArrayList<String>()
        val gifAttempts = AtomicInteger(0)
        val slowArrived = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("post.php?action=newthread&fid=2") && request.method == "GET" -> {
                        gbkHtmlResponse(
                            PostTestFixtures.prePostHtml(
                                formhash = "fh_e6_01",
                                uid = "10000",
                                hash = "hash_e6_upload_001",
                                typeOptions = listOf("0" to "分类", "56" to "Discovery"),
                            ),
                        )
                    }

                    target.contains("misc.php?action=swfupload") -> {
                        val raw = request.body!!.utf8()
                        uploadBodies += raw
                        when {
                            raw.contains("Content-Type: image/gif") -> {
                                // 第 1 次失败模拟单图失败，重传第 2 次成功
                                if (gifAttempts.incrementAndGet() == 1) {
                                    MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|0|1").build()
                                } else {
                                    MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|700002|1").build()
                                }
                            }
                            raw.length > 20_000 -> {
                                // 较大噪点图（slow3）挂起等待被用户点 × 取消
                                slowArrived.countDown()
                                releaseSlow.await(5, TimeUnit.SECONDS)
                                MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|700003|1").build()
                            }
                            else -> {
                                // photo1（原为 PNG，因默认 original=false 自动压缩为 JPEG）
                                MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|700001|1").build()
                            }
                        }
                    }

                    target.contains("post.php?action=newthread") && request.method == "POST" -> {
                        submittedPostBody += request.body!!.utf8()
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", "viewthread.php?tid=8800123")
                            .build()
                    }

                    target.contains("viewthread.php?tid=8800123") -> {
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser", "<div id=\"postlist\"></div>"))
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val vm = createRealViewModel()
        withTimeout(15.seconds) {
            vm.open(FullEditorRequest.NewThread(initialFid = 2))
            awaitCondition { vm.uiState.value.prePostInfo != null }

            // 先测试不安全 URI scheme 拦截：0 次上传请求
            val reqCountBeforeUnsafe = server.requestCount
            vm.onPickImages(
                listOf(
                    Uri.parse("file:///etc/passwd"),
                    Uri.parse("javascript:alert(1)"),
                    Uri.parse("intent://evil"),
                    Uri.parse("data:image/png;base64,AAAA"),
                    Uri.parse("http://example.com/a.jpg"),
                ),
            )
            delay(100)
            assertEquals("不安全 URI 不得发出任何上传请求", reqCountBeforeUnsafe, server.requestCount)
            assertTrue(vm.uiState.value.uploads.isEmpty())

            // 同时选择 3 张图并发上传（photo1 + gif2 + slow3）
            vm.onPickImages(listOf(uri1, uri2, uri3))
            assertTrue("慢上传应已到达服务端", slowArrived.await(5, TimeUnit.SECONDS))
            awaitCondition {
                val ups = vm.uiState.value.uploads
                ups.any { it.imageIdOrNull == "700001" } && ups.any { it.isFailed }
            }

            // 验证上传期间汇总进度非空且允许继续编辑标题和正文
            assertTrue("仍有在途上传时应展示汇总进度", vm.uiState.value.uploadSummaryProgress != null)
            vm.selectCategory("56")
            vm.onSubjectChanged("多图上传实测完整标题")
            vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "前文内容后文说明", 4, 4)

            // 取消在途 slow3（点击 ×）
            val slowLocalId = vm.uiState.value.uploads.first { it.contentUri == uri3 }.localId
            vm.removeUpload(slowLocalId)
            releaseSlow.countDown()

            // 重试失败的 gif2（重新注册单次流供第二次读取）
            shadowResolver.registerInputStream(uri2, ByteArrayInputStream(gif2Bytes))
            val gifLocalId = vm.uiState.value.uploads.first { it.contentUri == uri2 }.localId
            vm.retryUpload(gifLocalId)
            awaitCondition { vm.uiState.value.uploadedImageIds == listOf("700001", "700002") }

            // 验证 photo1 默认 original=false 被压缩为 Hi_*.jpg（image/jpeg），gif2 直传 image/gif
            assertTrue("photo1 默认 original=false 必须走压缩 JPEG", uploadBodies.any { it.contains("filename=\"Hi_") && it.contains("Content-Type: image/jpeg") })
            assertTrue("合规 GIF 必须直传", uploadBodies.any { it.contains(".gif\"") && it.contains("Content-Type: image/gif") })
            awaitCondition { (workDir.listFiles()?.size ?: 0) == 0 }

            // 点击第一张已上传缩略图在光标 offset=4 处插入内联缩略图块；第二张不插入正文（保留未插图角标并在提交时自动附尾）
            val photo1LocalId = vm.uiState.value.uploads.first { it.imageIdOrNull == "700001" }.localId
            vm.onInsertUploadedImage(photo1LocalId)
            assertEquals(setOf("700001"), vm.uiState.value.insertedImageIds)
            assertFalse("700002 尚未插入正文", "700002" in vm.uiState.value.insertedImageIds)

            // 提交发新帖并校验最终 POST 表单字段
            vm.submit()
            awaitCondition { !vm.uiState.value.isOpen && submittedPostBody.isNotEmpty() }

            val formPairs = parseGbkFormFields(submittedPostBody.single())
            val message = formPairs.firstOrNull { it.first == "message" }?.second.orEmpty()
            val attachNewIds = formPairs.filter { it.first == "attachnew[][description]" }.map { it.second }
            assertTrue("正文应按光标位置内联包含 700001：$message", message.contains("前文内容\n[attachimg]700001[/attachimg]\n后文说明"))
            assertTrue("未插正文的 700002 必须自动附在正文末尾：$message", message.trimEnd().endsWith("[attachimg]700002[/attachimg]"))
            assertEquals("表单 attachnew[][description] 必须精确包含两张已上传图片 ID", listOf("700001", "700002"), attachNewIds)
            assertEquals("多图上传实测完整标题", formPairs.firstOrNull { it.first == "subject" }?.second)
            assertEquals(0, workDir.listFiles()?.size ?: 0)
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        while (!condition()) delay(25)
    }

    private fun parseGbkFormFields(rawBody: String): List<Pair<String, String>> {
        val gbk = Charset.forName("GBK")
        return rawBody.split('&').filter { it.isNotEmpty() }.map { pair ->
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            URLDecoder.decode(rawKey, gbk.name()) to URLDecoder.decode(rawVal, gbk.name())
        }
    }

    private class InMemoryDraftDao : DraftDao {
        private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
        override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
        override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
        override suspend fun delete(key: String) { store.value = store.value - key }
    }
}
