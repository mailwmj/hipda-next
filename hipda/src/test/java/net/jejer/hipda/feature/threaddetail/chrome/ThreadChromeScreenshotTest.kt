package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E4 底部三段式液态玻璃工具条与原地展开内联跳页条 10 张 Roborazzi 截图基线（前缀 `thread_chrome_`）：
 * 1. 标准三段态 `411`/`749`/`1280dp` × 浅/深（6 张）；
 * 2. 窄容器紧凑态 `340dp` + `1.25×` 浅色 与 `360dp` 右栏 + `1.25×` 深色（2 张）；
 * 3. 内联跳页展开态 `< 480dp` 双行（`411dp` 浅色）与 `≥ 480dp` 单行（`749dp` 深色）（2 张）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ThreadChromeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_chrome_toolbar_w411_light() = captureChrome(
        name = "thread_chrome_toolbar_w411_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_chrome_toolbar_w411_dark() = captureChrome(
        name = "thread_chrome_toolbar_w411_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = true),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun thread_chrome_toolbar_w749_light() = captureChrome(
        name = "thread_chrome_toolbar_w749_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun thread_chrome_toolbar_w749_dark() = captureChrome(
        name = "thread_chrome_toolbar_w749_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun thread_chrome_toolbar_w1280_light() = captureChrome(
        name = "thread_chrome_toolbar_w1280_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun thread_chrome_toolbar_w1280_dark() = captureChrome(
        name = "thread_chrome_toolbar_w1280_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.NARROW_340_800)
    fun thread_chrome_compact_w340_scale125_light() = captureChrome(
        name = "thread_chrome_compact_w340_scale125_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
        fontScale = 1.25f,
        initialPage = 12,
        lastPage = 45,
    )

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun thread_chrome_compact_rightpane_w360_scale125_dark() = captureChrome(
        name = "thread_chrome_compact_rightpane_w360_scale125_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
        fontScale = 1.25f,
        initialPage = 12,
        lastPage = 45,
        constrainedWidth = 360.dp,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_chrome_jumpbar_compact_w411_light() = captureChrome(
        name = "thread_chrome_jumpbar_compact_w411_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
        expandJumpBar = true,
        initialPage = 4,
        lastPage = 12,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun thread_chrome_jumpbar_wide_w749_dark() = captureChrome(
        name = "thread_chrome_jumpbar_wide_w749_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
        expandJumpBar = true,
        initialPage = 4,
        lastPage = 12,
    )

    private fun captureChrome(
        name: String,
        themeMode: HiThemeMode,
        chromeSpec: DetailChromeSpec,
        fontScale: Float = 1f,
        initialPage: Int = 3,
        lastPage: Int = 12,
        expandJumpBar: Boolean = false,
        constrainedWidth: Dp? = null,
    ) {
        val repo = ChromeSnapshotRepository(initialPage = initialPage, lastPage = lastPage)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        val staticScale = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = fontScale)) {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val listState = rememberLazyListState(initialFirstVisibleItemScrollOffset = 80)
                        Box(modifier = if (constrainedWidth != null) Modifier.width(constrainedWidth).fillMaxSize() else Modifier.fillMaxSize()) {
                            ThreadDetailPane(
                                args = ThreadNavArgs(
                                    tid = "97001",
                                    title = "底部三段液态玻璃工具条与内联跳页条实测",
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
        }
        composeRule.waitForIdle()
        if (initialPage > 1) {
            composeRule.runOnIdle { vm.loadPage(initialPage) }
            composeRule.waitForIdle()
        }
        if (expandJumpBar) {
            composeRule.onNodeWithContentDescription("第 $initialPage 页，共 $lastPage 页，点按展开跳页").performClick()
            composeRule.waitForIdle()
        }

        // 详情页同屏玻璃区域严格为 2（顶栏 1 + 底部工具条/内联跳页条 HiGlassRegion 1）
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private class ChromeSnapshotRepository(
        private val initialPage: Int,
        private val lastPage: Int,
    ) : ThreadDetailRepository {
        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            val resolved = page.coerceIn(1, lastPage)
            val floors = (1..8).map { idx ->
                val floorNum = (resolved - 1) * 10 + idx
                PostFloor(
                    postId = "cp_${resolved}_$idx",
                    floor = floorNum,
                    page = resolved,
                    uid = if (idx == 1) "1001" else "${1000 + idx}",
                    author = if (idx == 1) "linus_hi" else "geek_user_$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 11:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain(
                                    "第 $resolved 页第 $floorNum 楼：底部三段式液态玻璃工具条与楼层列表互为严格兄弟节点，" +
                                        "列表末尾预留固定高度留白，中段胶囊支持阅读进度填充与原地展开内联跳页。",
                                ),
                            ),
                        ),
                    ),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "底部三段液态玻璃工具条与内联跳页条实测",
                    page = resolved,
                    lastPage = lastPage,
                    floors = floors,
                    postsPerPageEstimate = 10,
                    meta = DiscuzPageMeta(
                        session = DiscuzSessionInfo(true, "1001", "linus_hi"),
                        formhash = "fh_chrome",
                        errorMessage = null,
                        promptCounts = PromptCounts(),
                    ),
                    hasPostList = true,
                ),
                filter = filter,
                threadAuthorId = "1001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, lastPage)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "97001", initialPage)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
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
    }
}
