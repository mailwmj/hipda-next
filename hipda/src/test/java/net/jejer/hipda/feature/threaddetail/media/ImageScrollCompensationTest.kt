package net.jejer.hipda.feature.threaddetail.media

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.Image
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E2b 验收项 1（闭合 `R-BATCH5 B4-E2b`）三分支无跳动滚动补偿与比例缓存回收：
 * - 分支 ①：首楼上方（含首楼内已滚过视口顶边的图片、以及位于首楼上方 `contentPadding.top` 内的楼层图片）
 *   由 `4:3` 变为真实长图（`1:3`）时，当前首可见楼层距屏幕顶部偏差逐帧严格 `≤ 1dp`；
 * - 分支 ②：首楼自身未越顶或首楼下方图片变高时，首可见楼层距顶偏差严格 `== 0dp`（0 误滚），容器高度平滑过渡；
 * - 分支 ③：滚出视口回收后再滚回，首帧（在新请求尚未返回前）即按 `ImageAspectRatioCache` 真实比例布局，无回退 `4:3` 与二次跳动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
class ImageScrollCompensationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var savedNetworkState: SavedTestNetworkState

    private data class TestFloor(
        val postId: String,
        val header: String,
        val imageUrl: String? = null,
        val footer: String,
        val footerHeightDp: Int = 220,
    )

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        context = ApplicationProvider.getApplicationContext()
        savedNetworkState = captureTestNetworkState(context)
        configureTestNetworkState(
            context,
            metered = false,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
    }

    @After
    fun tearDown() {
        restoreTestNetworkState(context, savedNetworkState)
        ImageAspectRatioCache.Default.clear()
    }

    @Test
    fun allThreeBranches_aboveCompensatesWithin1dp_belowZeroScroll_recycleHitsCacheOnFirstFrame() {
        val aboveUrl = "https://img.example.invalid/branch1_tall.jpg"
        val inViewUrl = "https://img.example.invalid/branch2_inview_tall.jpg"
        val belowUrl = "https://img.example.invalid/branch2_below_tall.jpg"

        val gates = ConcurrentHashMap<String, CompletableDeferred<Image>>()
        gates[aboveUrl] = CompletableDeferred()
        gates[inViewUrl] = CompletableDeferred()
        gates[belowUrl] = CompletableDeferred()

        val loader = OfflineTestImageLoader { request ->
            val url = request.data.toString()
            val img = gates[url]?.await() ?: createSyntheticBitmap(400, 300).asImage()
            SuccessResult(image = img, request = request, dataSource = DataSource.NETWORK)
        }

        val floors = buildList {
            add(TestFloor("p1", "1楼头部", imageUrl = aboveUrl, footer = "1楼锚点正文", footerHeightDp = 260))
            add(TestFloor("p2", "2楼头部", imageUrl = inViewUrl, footer = "2楼锚点正文", footerHeightDp = 180))
            add(TestFloor("p3", "3楼头部", imageUrl = belowUrl, footer = "3楼锚点正文", footerHeightDp = 180))
            for (i in 4..30) {
                add(TestFloor("p$i", "${i}楼头部", imageUrl = null, footer = "${i}楼正文", footerHeightDp = 240))
            }
        }

        lateinit var listState: LazyListState
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    listState = rememberLazyListState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(items = floors, key = { "floor:${it.postId}" }) { floor ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(floor.header)
                                Spacer(Modifier.height(8.dp))
                                if (floor.imageUrl != null) {
                                    PostImageBlock(
                                        image = ContentBlock.Image(floor.imageUrl, null, 128_000L),
                                        floorPostId = floor.postId,
                                        listState = listState,
                                        onOpenImageViewer = {},
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(floor.footer, modifier = Modifier.height(floor.footerHeightDp.dp))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val oneDpPx = density

        // ========== 分支 ①：1楼图片底缘已滚过视口顶边，由 4:3 变为 1:3 长图 -> 锚点逐帧偏差 ≤ 1dp ==========
        // 1楼头部(~24dp) + 8dp + 4:3 图片(379dp * 3/4 ≈ 284dp) ≈ 316dp ≈ 632px；滚至 720px 确保图片底缘完全越过视口顶边，同时 1楼锚点正文与 2楼可见
        val scrollPastImagePx = (360f * density).toInt()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = scrollPastImagePx) }
        composeRule.waitForIdle()

        val floor1AnchorYBefore = composeRule.onNodeWithText("1楼锚点正文").fetchSemanticsNode().positionInRoot.y
        val floor2HeaderYBefore = composeRule.onNodeWithText("2楼头部").fetchSemanticsNode().positionInRoot.y
        val offsetBeforeBranch1 = listState.firstVisibleItemScrollOffset

        composeRule.mainClock.autoAdvance = false
        try {
            gates[aboveUrl]!!.complete(createSyntheticBitmap(width = 400, height = 1200).asImage())
            repeat(8) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                val anchorYNow = composeRule.onNodeWithText("1楼锚点正文").fetchSemanticsNode().positionInRoot.y
                val floor2YNow = composeRule.onNodeWithText("2楼头部").fetchSemanticsNode().positionInRoot.y
                assertTrue(
                    "分支① 帧 $frame 1楼锚点距顶偏差超 1dp：before=$floor1AnchorYBefore, now=$anchorYNow, diff=${abs(anchorYNow - floor1AnchorYBefore)}px",
                    abs(anchorYNow - floor1AnchorYBefore) <= oneDpPx + 0.5f,
                )
                assertTrue(
                    "分支① 帧 $frame 2楼距顶偏差超 1dp：before=$floor2HeaderYBefore, now=$floor2YNow, diff=${abs(floor2YNow - floor2HeaderYBefore)}px",
                    abs(floor2YNow - floor2HeaderYBefore) <= oneDpPx + 0.5f,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertTrue(
            "分支① 补偿后滚动偏移应显著增加（before=$offsetBeforeBranch1, after=${listState.firstVisibleItemScrollOffset}）",
            listState.firstVisibleItemScrollOffset > offsetBeforeBranch1 + (400f * density).toInt(),
        )
        assertEquals(1f / 3f, ImageAspectRatioCache.Default.get(aboveUrl)!!, 0.001f)

        // ========== 分支 ②：首楼自身未越顶 + 首楼下方图片由 4:3 变 1:3 -> 首楼距顶偏差严格 == 0dp（0 误滚） ==========
        composeRule.runOnIdle { listState.requestScrollToItem(index = 1, scrollOffset = 0) }
        composeRule.waitForIdle()

        val firstIdxBeforeBranch2 = listState.firstVisibleItemIndex
        val firstOffsetBeforeBranch2 = listState.firstVisibleItemScrollOffset
        val floor2TopBeforeBranch2 = composeRule.onNodeWithText("2楼头部").fetchSemanticsNode().positionInRoot.y

        gates[inViewUrl]!!.complete(createSyntheticBitmap(width = 400, height = 1200).asImage())
        gates[belowUrl]!!.complete(createSyntheticBitmap(width = 400, height = 1200).asImage())
        composeRule.waitForIdle()

        assertEquals("分支② 首可见楼层索引严禁变化", firstIdxBeforeBranch2, listState.firstVisibleItemIndex)
        assertEquals("分支② 首可见楼层滚动偏移必须严格为 0 变化（0 误滚）", firstOffsetBeforeBranch2, listState.firstVisibleItemScrollOffset)
        val floor2TopAfterBranch2 = composeRule.onNodeWithText("2楼头部").fetchSemanticsNode().positionInRoot.y
        assertEquals("分支② 首可见楼层距顶偏差必须严格为 0px", floor2TopBeforeBranch2, floor2TopAfterBranch2, 0.01f)

        // 验证 2楼图片确已按 1:3 变高（非空操作）
        val loadedFloor2Image = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )[0].fetchSemanticsNode()
        val expectedTallHeightPx = loadedFloor2Image.size.width * 3f
        assertTrue(
            "分支② 2楼图片高度应变为 1:3（约 $expectedTallHeightPx px），实际：${loadedFloor2Image.size.height}",
            abs(loadedFloor2Image.size.height - expectedTallHeightPx) <= 4f,
        )

        // ========== 分支 ③：滑出视口回收再滚回，首帧直接命中缓存真实比例，不回退 4:3、无二次跳动 ==========
        // 滚到 25 楼，彻底回收 1 楼
        composeRule.runOnIdle { listState.requestScrollToItem(index = 24, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertTrue(
            "1楼应已滑出可见列表并被回收",
            listState.layoutInfo.visibleItemsInfo.none { it.key == "floor:p1" },
        )

        // 滚回前用新的未完成 Deferred 拦截 aboveUrl：在图片尚未重新解码完成的“首帧”即测量 1楼高度！
        val reloadGate = CompletableDeferred<Image>()
        gates[aboveUrl] = reloadGate
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()

        val floor1ItemFirstFrame = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "floor:p1" }
        assertNotNull("滚回后 1楼应在可见项中", floor1ItemFirstFrame)
        val cachedFirstFrameFloor1Height = floor1ItemFirstFrame!!.size
        // 4:3 图片高 ~284dp，1:3 缓存图片高 ~1137dp，加上头部与正文后 1楼高度必须 >> 1000dp
        val minExpectedTallFloorPx = (1000f * density).toInt()
        assertTrue(
            "分支③ 回收再入首帧必须直接命中 1:3 缓存（> $minExpectedTallFloorPx px），不得回退 4:3，实际：$cachedFirstFrameFloor1Height px",
            cachedFirstFrameFloor1Height > minExpectedTallFloorPx,
        )

        // 放行重新解码，确认完成后高度 0 二次跳动
        reloadGate.complete(createSyntheticBitmap(width = 400, height = 1200).asImage())
        composeRule.waitForIdle()
        val floor1HeightAfterReload = listState.layoutInfo.visibleItemsInfo.first { it.key == "floor:p1" }.size
        assertEquals("分支③ 重解码完成后 1楼高度不得发生二次跳动", cachedFirstFrameFloor1Height, floor1HeightAfterReload)
    }

    @Test
    fun branch1_floorStrictlyAboveFirstVisibleInTopContentPadding_keepsFirstVisibleAnchoredWithin1dp() {
        val topBarFloorImgUrl = "https://img.example.invalid/top_padding_tall.jpg"
        val gate = CompletableDeferred<Image>()
        val loader = OfflineTestImageLoader { request ->
            SuccessResult(image = gate.await(), request = request, dataSource = DataSource.NETWORK)
        }
        val floors = (1..12).map { idx ->
            TestFloor(
                postId = "tp_$idx",
                header = "${idx}楼标题",
                imageUrl = if (idx == 1) topBarFloorImgUrl else null,
                footer = "${idx}楼正文内容",
                footerHeightDp = 240,
            )
        }

        lateinit var listState: LazyListState
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 80.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = floors, key = { "floor:${it.postId}" }) { floor ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(floor.header)
                                Spacer(Modifier.height(8.dp))
                                if (floor.imageUrl != null) {
                                    PostImageBlock(
                                        image = ContentBlock.Image(floor.imageUrl, null, 96_000L),
                                        floorPostId = floor.postId,
                                        listState = listState,
                                        onOpenImageViewer = {},
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(floor.footer, modifier = Modifier.height(floor.footerHeightDp.dp))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 滚动使 2楼成为首可见楼层（index = 1），1楼（index = 0）位于顶部 80dp contentPadding 区域内仍保持组合
        composeRule.runOnIdle { listState.requestScrollToItem(index = 1, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals(1, listState.firstVisibleItemIndex)
        assertTrue(
            "1楼应位于顶部 contentPadding 中且 index < firstVisibleItemIndex",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:tp_1" && it.index == 0 },
        )

        val floor2YBefore = composeRule.onNodeWithText("2楼标题").fetchSemanticsNode().positionInRoot.y
        val oneDpPx = composeRule.density.density

        gate.complete(createSyntheticBitmap(width = 400, height = 1200).asImage())
        composeRule.waitForIdle()

        val floor2YAfter = composeRule.onNodeWithText("2楼标题").fetchSemanticsNode().positionInRoot.y
        assertEquals(1, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertTrue(
            "顶部 contentPadding 内楼层变高时首可见楼层距顶偏差必须 ≤ 1dp：before=$floor2YBefore, after=$floor2YAfter",
            abs(floor2YAfter - floor2YBefore) <= oneDpPx + 0.5f,
        )
        composeRule.onNodeWithText("2楼标题").assertIsDisplayed()
    }
}
