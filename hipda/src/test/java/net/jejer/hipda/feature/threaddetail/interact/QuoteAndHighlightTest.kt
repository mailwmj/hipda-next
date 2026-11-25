package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E2c 验收项 1（含 `R-BATCH5 B4-E2c` 跨页缺号楼层视口可见性）及引用内链接 scheme 白名单：
 * 1. 跨页缺号第 15+ 楼跳楼：按 `key == "floor:$targetPid"` 落入视口（绝不用楼层号减 1），并经淡入 → `highlightHold(1000ms)` → 淡出；
 * 2. 本页 `GoToFloor` 跳楼零网络发包、准确滚入视口并高亮；
 * 3. 减少动态效果下为静态高亮，停留 `1000ms` 后直接消失；
 * 4. 长引用默认折叠、`HiMotion.spatialDefault()` 展开且展开过程上方楼层偏移 `≤ 1dp`；
 * 5. 引用正文链接双层 `sanitizePostUrl` 白名单反例全覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class QuoteAndHighlightTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun crossPageJumpToGappedFloor18_landsInViewportByStableKeyAndHighlightsThroughHoldToFadeOut() {
        val targetPid = "pid_p2_gap_18"
        val repo = JumpTestRepository(targetPid = targetPid)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88001", title = "跨页缺号跳楼测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 点击第 1 页第 2 楼的引用块，指向第 2 页第 18 项（前置存在大量删楼缺号，实际楼层号为 52 而非 18）
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithText("目标作者 发表于 2026-11-26 09:00").performClick()
            // 同步驱动 Main Looper（viewModelScope combine）与 Compose 帧时钟，让 loadByPostId 与单一滚动权威落位并进入高亮
            repeat(8) {
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }

            assertEquals("跨页跳楼必须经由 D5.loadByPostId 请求目标 pid", listOf(targetPid), repo.loadByPidCalls)
            assertEquals("跨页跳楼后连续区间应更新为第 2 页", 2, vm.uiState.value.firstLoadedPage)
            assertEquals(2, vm.uiState.value.lastLoadedPage)

            val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }
            assertTrue(
                "闭合 B4-E2c：跳楼后 visibleItemsInfo 必须实际包含 floor:$targetPid，实际：$visibleKeys",
                "floor:$targetPid" in visibleKeys,
            )
            assertEquals("目标楼层必须进入高亮状态", targetPid, vm.uiState.value.highlightedFloor?.postId)

            val highlightedNodes = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位到此楼层"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("目标楼层节点必须具备高亮无障碍语义", highlightedNodes.isNotEmpty())
            composeRule.onNodeWithText("52楼").assertIsDisplayed()
            composeRule.onNodeWithText("跨页缺号目标第 52 楼正文内容").assertIsDisplayed()

            // 停留 500ms（仍在 HiMotion.highlightHold = 1000ms 内）：高亮保持活跃
            composeRule.mainClock.advanceTimeBy(500L)
            ShadowLooper.idleMainLooper()
            assertNotNull("停留期内高亮不得提前消失", vm.uiState.value.highlightedFloor)

            // 推进越过 highlightHold + effectsSlow 淡出后：高亮结束并清空语义
            composeRule.mainClock.advanceTimeBy(HiMotion.highlightHold + 1_200L)
            repeat(5) {
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }
            assertNull("高亮淡出结束后应清空 highlightedFloor 状态", vm.uiState.value.highlightedFloor)
            val remainingHighlights = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位到此楼层"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("高亮淡出结束后不应残留高亮语义", 0, remainingHighlights.size)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun samePageGoToFloor_scrollsByStableKeyWithZeroNetworkRequestsAndHighlights() {
        val samePageTargetPid = "pid_p1_gap_17"
        val repo = JumpTestRepository(targetPid = "pid_p2_gap_18", samePageTargetPid = samePageTargetPid)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88001", title = "本页跳楼测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        assertFalse(
            "前提：本页第 17 项初始应在首屏外",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:$samePageTargetPid" },
        )

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithText("回复 39# 本页目标作者").performClick()
            repeat(6) {
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }

            assertEquals("本页跳楼不得调用 loadByPostId 发起网络请求", 0, repo.loadByPidCalls.size)
            assertTrue(
                "本页跳楼后 visibleItemsInfo 必须包含 floor:$samePageTargetPid",
                listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:$samePageTargetPid" },
            )
            assertEquals(samePageTargetPid, vm.uiState.value.highlightedFloor?.postId)
            composeRule.onNodeWithText("39楼").assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun reducedMotion_floorHighlightHoldsStatic1000msThenDisappearsImmediately() = withAnimatorDurationScale(0f) {
        val targetPid = "pid_p2_gap_18"
        val repo = JumpTestRepository(targetPid = targetPid)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88001", title = "减动效高亮测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        mainClock.autoAdvance = false
        try {
            onNodeWithText("目标作者 发表于 2026-11-26 09:00").performClick()
            repeat(6) {
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }

            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:$targetPid" })
            assertEquals("减动效下应立即进入静态高亮", targetPid, vm.uiState.value.highlightedFloor?.postId)

            // 800ms 时仍静态停留在高亮态
            mainClock.advanceTimeBy(800L)
            ShadowLooper.idleMainLooper()
            assertEquals("highlightHold 未满 1000ms 时静态高亮必须保持", targetPid, vm.uiState.value.highlightedFloor?.postId)

            // 越过 1000ms 后无需淡出直接消失
            mainClock.advanceTimeBy(250L)
            repeat(4) {
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }
            assertNull("减动效下停满 1000ms 必须直接消失", vm.uiState.value.highlightedFloor)
        } finally {
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun longQuote_collapsesByDefaultAndExpandsWithoutMovingFloorsAbove() {
        val longQuoteText = (1..10).joinToString("\n") { "第 $it 行长引用内容，用于验证默认超 4 行折叠并在点开展开后保持上方楼层位置不动。" }
        val repo = LongQuoteTestRepository(longQuoteText)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88002", title = "长引用折叠展开测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("展开").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("收起").fetchSemanticsNodes().size)

        val floor1TopBefore = composeRule.onNodeWithText("上方 1 楼基准正文").fetchSemanticsNode().boundsInRoot.top
        val floor2HeaderTopBefore = composeRule.onNodeWithText("2楼").fetchSemanticsNode().boundsInRoot.top
        val quoteBottomBefore = composeRule.onNodeWithText("展开").fetchSemanticsNode().boundsInRoot.bottom

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithText("展开").performClick()
            for (frame in 1..25) {
                composeRule.mainClock.advanceTimeByFrame()
                val floor1TopNow = composeRule.onNodeWithText("上方 1 楼基准正文").fetchSemanticsNode().boundsInRoot.top
                val floor2HeaderTopNow = composeRule.onNodeWithText("2楼").fetchSemanticsNode().boundsInRoot.top
                assertTrue(
                    "展开第 $frame 帧上方 1 楼不得跳动（drift=${abs(floor1TopNow - floor1TopBefore) / density}dp）",
                    abs(floor1TopNow - floor1TopBefore) / density <= 1.0f,
                )
                assertTrue(
                    "展开第 $frame 帧本楼头部不得跳动（drift=${abs(floor2HeaderTopNow - floor2HeaderTopBefore) / density}dp）",
                    abs(floor2HeaderTopNow - floor2HeaderTopBefore) / density <= 1.0f,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("收起").assertIsDisplayed()
        val quoteBottomAfter = composeRule.onNodeWithText("收起").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "展开后引用高度必须明显增长（before=$quoteBottomBefore, after=$quoteBottomAfter）",
            quoteBottomAfter > quoteBottomBefore + 40f * density,
        )

        // 点击收起恢复折叠态
        composeRule.onNodeWithText("收起").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("展开").assertIsDisplayed()
    }

    @Test
    fun quoteBlock_enforcesSchemeAllowlistForAllDangerousSchemesAndAllowsSafeLinks() {
        val dangerousUrls = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:history.back()",
            "intent://scan/#Intent;scheme=zxing;end",
            "INTENT://evil/#Intent;end",
            "file:///data/user/0/net.jejer.hipda.next/files/secret",
            "FILE:///etc/hosts",
            "content://media/external/images/media/1",
            "CONTENT://settings/system",
            "data:text/html,<script>alert(1)</script>",
            "DATA:text/plain;base64,QQ==",
            "mailto:bad@example.com?bcc=evil@x.com",
            "tel:10086",
            "ftp://example.com/archive.zip",
        )
        // 1. 渲染层断言：每种危险 scheme 均不生成任何 LinkAnnotation
        for (url in dangerousUrls) {
            val built = buildQuoteContentAnnotatedString(
                contentText = "引用包含 $url 结束",
                linkColor = Color.Blue,
                onLinkClick = null,
            )
            assertTrue(
                "引用中危险 scheme [$url] 不得生成 LinkAnnotation",
                built.getLinkAnnotations(0, built.length).isEmpty(),
            )
        }

        // 2. 点击层断言：生产默认 onLinkClick = null 路径下危险 scheme 0 触发，合法 http/https/mailto 正常触发
        val openedUris = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        for ((idx, url) in dangerousUrls.withIndex()) {
                            QuoteBlock(
                                quote = ContentBlock.Quote(
                                    authorAndTime = "",
                                    contentHtml = url,
                                    contentText = url,
                                    targetTid = null,
                                    targetPid = null,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("sep-$idx")
                        }
                        Box(Modifier.fillMaxWidth()) {
                            QuoteBlock(
                                quote = ContentBlock.Quote(
                                    authorAndTime = "",
                                    contentHtml = "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
                                    contentText = "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
                                    targetTid = null,
                                    targetPid = null,
                                ),
                            )
                        }
                        Box(Modifier.fillMaxWidth()) {
                            QuoteBlock(
                                quote = ContentBlock.Quote(
                                    authorAndTime = "",
                                    contentHtml = "http://example.com/ref",
                                    contentText = "http://example.com/ref",
                                    targetTid = null,
                                    targetPid = null,
                                ),
                            )
                        }
                        Box(Modifier.fillMaxWidth()) {
                            QuoteBlock(
                                quote = ContentBlock.Quote(
                                    authorAndTime = "",
                                    contentHtml = "mailto:qa@example.com",
                                    contentText = "mailto:qa@example.com",
                                    targetTid = null,
                                    targetPid = null,
                                ),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        for (url in dangerousUrls) {
            composeRule.onNodeWithText(url).performScrollTo().performTouchInput { click(Offset(12f, centerY)) }
            assertEquals("点击引用中危险 scheme [$url] 不得唤起 LocalUriHandler", 0, openedUris.size)
        }

        composeRule.onNodeWithText("https://www.4d4y.com/forum/viewthread.php?tid=2602293").performScrollTo().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("http://example.com/ref").performScrollTo().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("mailto:qa@example.com").performScrollTo().performTouchInput { click(Offset(12f, centerY)) }

        assertEquals(
            listOf(
                "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
                "http://example.com/ref",
                "mailto:qa@example.com",
            ),
            openedUris,
        )
    }

    private class JumpTestRepository(
        private val targetPid: String,
        private val samePageTargetPid: String = "pid_p1_gap_17",
    ) : ThreadDetailRepository {
        val loadByPidCalls = CopyOnWriteArrayList<String>()

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult = buildPage(tid, page)

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            buildPage(tid, 2)

        override suspend fun loadByPostId(
            pid: String,
            tid: String?,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadByPidCalls += pid
            return buildPage(tid ?: "88001", page = 2, targetPostId = pid)
        }

        private fun buildPage(tid: String, page: Int, targetPostId: String? = null): ThreadDetailLoadResult.Success {
            val floors = if (page == 1) {
                // 构造含删楼缺号的 20 条楼层（第 17 项楼层号跳到 39）
                val gappedNumbers = listOf(1, 2, 4, 5, 7, 9, 10, 12, 14, 16, 18, 20, 23, 26, 29, 34, 39, 41, 42, 45)
                gappedNumbers.mapIndexed { idx, floorNum ->
                    val pid = if (idx == 16) samePageTargetPid else "pid_p1_$idx"
                    val blocks: List<ContentBlock> = when (idx) {
                        1 -> listOf(
                            ContentBlock.Quote(
                                authorAndTime = "目标作者 发表于 2026-11-26 09:00",
                                contentHtml = "跨页引用目标内容",
                                contentText = "跨页引用目标内容",
                                targetTid = tid,
                                targetPid = targetPid,
                            ),
                            ContentBlock.GoToFloor(
                                label = "回复 39# 本页目标作者",
                                targetTid = tid,
                                targetPid = samePageTargetPid,
                                floor = 39,
                                author = "本页目标作者",
                            ),
                            ContentBlock.Text(listOf(InlineSpan.Plain("第 1 页第 2 楼正文"))),
                        )
                        16 -> listOf(ContentBlock.Text(listOf(InlineSpan.Plain("本页缺号目标第 39 楼正文内容"))))
                        else -> listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 1 页楼层 $floorNum 正文，占位确保列表超出首屏高度。"))))
                    }
                    PostFloor(pid, floorNum, 1, "${100 + idx}", if (idx == 16) "本页目标作者" else "作者$floorNum", null, "2026-11-26 08:$idx", false, null, null, null, blocks)
                }
            } else {
                // 第 2 页共 20 条，且前置楼层号严重缺号：index = 17（第 18 条）的真实楼层号为 52（52 - 1 = 51 远超列表长度 20）
                val gappedNumbers = listOf(21, 22, 24, 27, 28, 30, 31, 33, 35, 36, 38, 40, 41, 43, 44, 46, 48, 52, 55, 58)
                gappedNumbers.mapIndexed { idx, floorNum ->
                    val pid = if (idx == 17) targetPid else "pid_p2_$idx"
                    val text = if (idx == 17) "跨页缺号目标第 52 楼正文内容" else "第 2 页楼层 $floorNum 填充正文，用于保证目标楼层严格位于首屏之外。"
                    PostFloor(pid, floorNum, 2, "${200 + idx}", if (idx == 17) "目标作者" else "坛友$floorNum", null, "2026-11-26 09:$idx", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain(text)))))
                }
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "跳楼测试主题", page, 2, floors, 20, sampleMeta(), true),
                filter = ThreadDetailFilter.All,
                threadAuthorId = "100",
                targetPostId = targetPostId,
            )
        }

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

    private class LongQuoteTestRepository(private val longQuoteText: String) : ThreadDetailRepository {
        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(
                tid = tid,
                fid = 2,
                title = "长引用测试",
                page = 1,
                lastPage = 1,
                floors = listOf(
                    PostFloor("lq_1", 1, 1, "100", "楼主", null, "2026-11-26 08:00", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("上方 1 楼基准正文"))))),
                    PostFloor(
                        "lq_2", 2, 1, "101", "回复者", null, "2026-11-26 08:05", false, null, null, null,
                        listOf(
                            ContentBlock.Quote("楼主 发表于 2026-11-26 08:00", longQuoteText, longQuoteText, tid, "lq_1"),
                            ContentBlock.Text(listOf(InlineSpan.Plain("2 楼评论正文"))),
                        ),
                    ),
                ),
                postsPerPageEstimate = 2,
                meta = sampleMeta(),
                hasPostList = true,
            ),
            filter = filter,
            threadAuthorId = "100",
        )

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "88002", 1)
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
        private fun sampleMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "100", username = "楼主"),
            formhash = "abcd1234",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
