package net.jejer.hipda.feature.threaddetail

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
import net.jejer.hipda.feature.threaddetail.media.OfflineTestImageLoader
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.TextFormat
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val QUALIFIER_DETAIL_PANE_411 = "w411dp-h559dp-xhdpi"
private const val QUALIFIER_DETAIL_PANE_448 = "w448dp-h696dp-xhdpi"
private const val QUALIFIER_DETAIL_PANE_680 = "w680dp-h800dp-xhdpi"

/**
 * 帖子详情核心 15 张 Roborazzi 截图基线（前缀 `thread_detail_`，不含连续加载头尾态，对齐 E2a 验收项 4）：
 * - 单栏 `411×891`（浅/深/OLED 纯黑）、`559×749`、`640×1024`；
 * - 大屏详情栏宽 `411`（含全屏圆钮与只看楼主胶囊）、`448`（深）、`680dp`（680dp 居中与显示列表圆钮）；
 * - 第 5 档字号 × 1.3 缩放；
 * - 过滤空态、错误态、骨架态 × `411` & `933dp`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ThreadDetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_light() = captureDetail(
        name = "thread_detail_w411_light",
        themeMode = HiThemeMode.LIGHT,
        scrolled = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_dark() = captureDetail(
        name = "thread_detail_w411_dark",
        themeMode = HiThemeMode.DARK,
        scrolled = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_oled_pure_black() = captureDetail(
        name = "thread_detail_w411_oled_pure_black",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        scrolled = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_PORTRAIT_559_749)
    fun thread_detail_w559_light() = captureDetail(
        name = "thread_detail_w559_light",
        themeMode = HiThemeMode.LIGHT,
        scrolled = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun thread_detail_w640_dark() = captureDetail(
        name = "thread_detail_w640_dark",
        themeMode = HiThemeMode.DARK,
        scrolled = true,
    )

    @Test
    @Config(qualifiers = QUALIFIER_DETAIL_PANE_411)
    fun thread_detail_pane411_filter_light() = captureDetail(
        name = "thread_detail_pane411_filter_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = false, onToggle = {})),
        startAuthorOnly = true,
    )

    @Test
    @Config(qualifiers = QUALIFIER_DETAIL_PANE_448)
    fun thread_detail_pane448_dark() = captureDetail(
        name = "thread_detail_pane448_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = false, onToggle = {})),
        scrolled = true,
    )

    @Test
    @Config(qualifiers = QUALIFIER_DETAIL_PANE_680)
    fun thread_detail_pane680_light() = captureDetail(
        name = "thread_detail_pane680_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = true, onToggle = {})),
        scrolled = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_step5_scale13_light() = captureDetail(
        name = "thread_detail_w411_step5_scale13_light",
        themeMode = HiThemeMode.LIGHT,
        fontSizeStep = HiFontSizeStep.STEP_5,
        fontScale = 1.3f,
        startAuthorOnly = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_empty_filter_light() = captureDetail(
        name = "thread_detail_w411_empty_filter_light",
        themeMode = HiThemeMode.LIGHT,
        startAuthorOnly = true,
        emptyOnFilter = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_detail_w933_empty_filter_dark() = captureDetail(
        name = "thread_detail_w933_empty_filter_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = false, onToggle = {})),
        startAuthorOnly = true,
        emptyOnFilter = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_error_light() = captureDetail(
        name = "thread_detail_w411_error_light",
        themeMode = HiThemeMode.LIGHT,
        errorResult = ThreadDetailError.NetworkError("网络连接超时"),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_detail_w933_error_dark() = captureDetail(
        name = "thread_detail_w933_error_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = false, onToggle = {})),
        errorResult = ThreadDetailError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_detail_w411_skeleton_light() = captureDetail(
        name = "thread_detail_w411_skeleton_light",
        themeMode = HiThemeMode.LIGHT,
        holdLoading = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_detail_w933_skeleton_dark() = captureDetail(
        name = "thread_detail_w933_skeleton_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(isFullScreen = false, onToggle = {})),
        holdLoading = true,
    )

    private fun captureDetail(
        name: String,
        themeMode: HiThemeMode,
        pureBlack: Boolean = false,
        fontSizeStep: HiFontSizeStep = HiFontSizeStep.STEP_3,
        fontScale: Float = 1.0f,
        chromeSpec: DetailChromeSpec = DetailChromeSpec(showBack = true),
        scrolled: Boolean = false,
        startAuthorOnly: Boolean = false,
        emptyOnFilter: Boolean = false,
        errorResult: ThreadDetailError? = null,
        holdLoading: Boolean = false,
    ) {
        val loadingGate = if (holdLoading) CompletableDeferred<Unit>() else null
        val repo = SnapshotThreadDetailRepository(
            loadingGate = loadingGate,
            emptyOnFilter = emptyOnFilter,
            errorResult = errorResult,
        )
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        val offlineImageLoader = OfflineTestImageLoader.success(
            width = 400,
            height = 300,
            dark = themeMode == HiThemeMode.DARK,
        )
        ImageAspectRatioCache.Default.clear()
        val staticScale = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalHiImageLoader provides offlineImageLoader,
            ) {
                HiTheme(
                    themeMode = themeMode,
                    pureBlack = pureBlack,
                    seed = HiColorPresets.Brand.toSeed(),
                    fontSizeStep = fontSizeStep,
                    motionDurationScale = staticScale,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = 0,
                            initialFirstVisibleItemScrollOffset = if (scrolled) 120 else 0,
                        )
                        ThreadDetailPane(
                            args = ThreadNavArgs(
                                tid = "2602293",
                                title = "折腾折叠屏客户端：Compose 原生正文、表情与动态调色实测",
                                fid = 2,
                                forumName = "Discovery",
                                author = "linus_hi",
                            ),
                            chromeSpec = chromeSpec,
                            onBack = {},
                            onOpenLogin = {},
                            listState = listState,
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        if (startAuthorOnly) {
            composeRule.runOnIdle { vm.toggleAuthorOnly() }
            composeRule.waitForIdle()
        }
        val expectsImageLoad = !holdLoading && errorResult == null
        if (expectsImageLoad) {
            assertTrue(
                "1楼示例图片必须仅经离线 LocalHiImageLoader 加载（零真实网络），实际：${offlineImageLoader.requestedUrls}",
                offlineImageLoader.requestedUrls.isNotEmpty() &&
                    offlineImageLoader.requestedUrls.all { it == "https://img.example.invalid/sample_photo.jpg" },
            )
        } else {
            assertEquals(emptyList<String>(), offlineImageLoader.requestedUrls)
        }
        assertEquals(0, offlineImageLoader.enqueueCount.get())
        composeRule.assertGlassSurfaceCountAtMost(1)
        composeRule.onRoot().captureHiSnapshot(name)
        ImageAspectRatioCache.Default.clear()
    }

    private class SnapshotThreadDetailRepository(
        private val loadingGate: CompletableDeferred<Unit>? = null,
        private val emptyOnFilter: Boolean = false,
        private val errorResult: ThreadDetailError? = null,
    ) : ThreadDetailRepository {

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadingGate?.await()
            errorResult?.let { return it }
            if (emptyOnFilter && filter !is ThreadDetailFilter.All) {
                return ThreadDetailLoadResult.Success(
                    page = ThreadDetailPage(tid, 2, "折腾折叠屏客户端：Compose 原生正文、表情与动态调色实测", 1, 1, emptyList(), null, sampleMeta(), true),
                    filter = filter,
                    threadAuthorId = "1001",
                )
            }
            val allFloors = sampleRichFloors()
            val filtered = if (filter is ThreadDetailFilter.AuthorOnly) allFloors.filter { it.uid == "1001" } else allFloors
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "折腾折叠屏客户端：Compose 原生正文、表情与动态调色实测",
                    page = page,
                    lastPage = if (filter is ThreadDetailFilter.All) 4 else 1,
                    floors = filtered,
                    postsPerPageEstimate = 50,
                    meta = sampleMeta(),
                    hasPostList = true,
                ),
                filter = if (filter is ThreadDetailFilter.AuthorOnly) ThreadDetailFilter.AuthorOnly("1001") else filter,
                threadAuthorId = "1001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 4, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "2602293", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = it.uid == "1003") } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = it.uid == "1003") }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }

    companion object {
        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )

        private fun sampleMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "1001", username = "linus_hi"),
            formhash = "abcd1234",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private fun sampleRichFloors(): List<PostFloor> = listOf(
            PostFloor(
                postId = "53529001",
                floor = 1,
                page = 1,
                uid = "1001",
                author = "linus_hi",
                avatarUrl = null,
                timePost = "2026-11-25 21:05",
                isWarned = false,
                postStatus = "本帖最后由 linus_hi 于 2026-11-25 21:20 编辑",
                signature = null,
                poll = null,
                blocks = listOf(
                    ContentBlock.Text(
                        listOf(
                            InlineSpan.Plain("原生 Compose 正文支持 "),
                            InlineSpan.Plain("粗体", TextFormat(bold = true)),
                            InlineSpan.Plain("、"),
                            InlineSpan.Plain("斜体", TextFormat(italic = true)),
                            InlineSpan.Plain("、"),
                            InlineSpan.Plain("下划线", TextFormat(underline = true)),
                            InlineSpan.Plain("、"),
                            InlineSpan.Plain("删除线", TextFormat(strikethrough = true)),
                            InlineSpan.Plain(" 与三套论坛表情 "),
                            InlineSpan.Emoji("default_lol", "images/smilies/default/lol.gif"),
                            InlineSpan.Emoji("coolmonkey_01", "images/smilies/coolmonkey/01.gif"),
                            InlineSpan.Emoji("grapeman_08", "images/smilies/grapeman/08.gif"),
                            InlineSpan.Plain(" 好的\uD83D\uDC4C"),
                            InlineSpan.LineBreak,
                            InlineSpan.Plain("极端色动态校准："),
                            InlineSpan.Plain("纯黑#000000 ", TextFormat(rawColor = "#000000")),
                            InlineSpan.Plain("深蓝#000080 ", TextFormat(rawColor = "#000080")),
                            InlineSpan.Plain("明黄#FFFF00 ", TextFormat(rawColor = "#FFFF00")),
                            InlineSpan.Plain("纯白#FFFFFF ", TextFormat(rawColor = "#FFFFFF")),
                            InlineSpan.Plain("正红#FF0000", TextFormat(rawColor = "#FF0000")),
                            InlineSpan.LineBreak,
                            InlineSpan.Link("查看项目说明", "https://www.4d4y.com/forum/viewthread.php?tid=2602293"),
                            InlineSpan.Plain(" · 联系 "),
                            InlineSpan.Email("dev@example.com"),
                        ),
                    ),
                    ContentBlock.Image(
                        fullUrl = "https://img.example.invalid/sample_photo.jpg",
                        thumbUrl = null,
                        fileSizeBytes = 128_000L,
                    ),
                    ContentBlock.Attachment(
                        url = "https://www.4d4y.com/forum/attachment.php?aid=4096",
                        title = "benchmark_report.pdf",
                        description = "248 KB",
                    ),
                ),
            ),
            PostFloor(
                postId = "53529002",
                floor = 2,
                page = 1,
                uid = "1002",
                author = "geek_reader",
                avatarUrl = null,
                timePost = "2026-11-25 21:14",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = listOf(
                    ContentBlock.Quote(
                        authorAndTime = "linus_hi 发表于 2026-11-25 21:05",
                        contentHtml = "原生 Compose 正文支持三套论坛表情与动态文字调色",
                        contentText = "原生 Compose 正文支持三套论坛表情与动态文字调色，在浅色、深色与 OLED 纯黑模式下都保持清晰可读。",
                        targetTid = "2602293",
                        targetPid = "53529001",
                    ),
                    ContentBlock.GoToFloor(
                        label = "回复 1# linus_hi",
                        targetTid = "2602293",
                        targetPid = "53529001",
                        floor = 1,
                        author = "linus_hi",
                    ),
                    ContentBlock.Text(
                        listOf(
                            InlineSpan.Plain("透明顶栏与轻底圆形按钮在滚动后过渡很自然 "),
                            InlineSpan.Emoji("coolmonkey_16", "images/smilies/coolmonkey/16.gif"),
                        ),
                    ),
                ),
            ),
            PostFloor(
                postId = "53529003",
                floor = 3,
                page = 1,
                uid = "1003",
                author = "blocked_user",
                avatarUrl = null,
                timePost = "2026-11-25 21:18",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("这条内容应被黑名单遮蔽")))),
            ),
            PostFloor(
                postId = "53529004",
                floor = 4,
                page = 1,
                uid = "1001",
                author = "linus_hi",
                avatarUrl = null,
                timePost = "2026-11-25 21:25",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = listOf(
                    ContentBlock.Notice("提示: 本楼层为楼主补充说明，开启只看楼主后仍可见。"),
                    ContentBlock.Text(
                        listOf(
                            InlineSpan.Plain("欢迎开启右上角「只看楼主」验证过滤胶囊与退出回原楼。"),
                        ),
                    ),
                ),
            ),
        )
    }
}
