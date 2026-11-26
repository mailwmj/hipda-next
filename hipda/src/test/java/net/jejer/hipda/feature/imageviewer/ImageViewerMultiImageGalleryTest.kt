package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.ImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalImageViewerBoundsRegistry
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
import net.jejer.hipda.feature.threaddetail.media.LocalThreadImageGallery
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.feature.threaddetail.media.extractThreadGalleryItems
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 覆盖 M7-G2 #9 看图多图横滑与 `LocalThreadImageGallery -> PostImageBlock -> ImageViewerRendererImpl` 端到端链路（撤回即红）：
 * 1. `ImageViewerOverlay` 接收 3 图 `initialIndex = 1` 初始显示 `2 / 3`，向左滑切 `3 / 3`、向右滑切回 `2 / 3` 与 `1 / 3`；
 * 2. 只要外部通过 `LocalThreadImageGallery` 提供同帖图集，点击正文任意图片（含跨楼层重复 URL）即可精准落至对应 `initialIndex` 并左右横滑。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerMultiImageGalleryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(null)
    }

    @After
    fun tearDown() {
        ImageAspectRatioCache.Default.clear()
    }

    @Test
    fun imageViewerOverlay_startsAtInitialIndex1Of3_andSwipesLeftAndRightAcrossAllPages() {
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf(
                                "https://img.example.invalid/g1.jpg",
                                "https://img.example.invalid/g2.jpg",
                                "https://img.example.invalid/g3.jpg",
                            ),
                            initialIndex = 1,
                            floorNumber = 2,
                            author = "bob",
                        ),
                        onDismiss = { dismissed = true },
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第 2 楼 · bob").assertIsDisplayed()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // 左滑到第 3 张（3 / 3）
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("3 / 3").assertIsDisplayed()

        // 右滑回第 2 张（2 / 3），再右滑到第 1 张（1 / 3）
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
        assertFalse("左右横滑看图不得误触发关闭", dismissed)
    }

    @Test
    fun postImageBlockWithGalleryProvider_opensImageViewerAtExactIndexAndSupportsSwiping() {
        val dupUrl = "https://img.example.invalid/thread_dup.jpg"
        val midUrl = "https://img.example.invalid/thread_mid.jpg"
        val floors = listOf(
            ThreadDetailFloorItem(
                floor = PostFloor(
                    postId = "p1", floor = 1, page = 1, uid = "1", author = "alice", avatarUrl = null,
                    timePost = "2026-11-26 10:00", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Image(fullUrl = dupUrl, thumbUrl = null, fileSizeBytes = 1024L)),
                ),
                isAuthorBlocked = false,
            ),
            ThreadDetailFloorItem(
                floor = PostFloor(
                    postId = "p2", floor = 2, page = 1, uid = "2", author = "bob", avatarUrl = null,
                    timePost = "2026-11-26 10:05", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Image(fullUrl = midUrl, thumbUrl = null, fileSizeBytes = 1024L)),
                ),
                isAuthorBlocked = false,
            ),
            ThreadDetailFloorItem(
                floor = PostFloor(
                    postId = "p3", floor = 3, page = 1, uid = "3", author = "charlie", avatarUrl = null,
                    timePost = "2026-11-26 10:10", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Image(fullUrl = dupUrl, thumbUrl = null, fileSizeBytes = 1024L)),
                ),
                isAuthorBlocked = false,
            ),
        )
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var activeArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            val gallery = remember(floors) { extractThreadGalleryItems(floors) }
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            floors.forEach { floorItem ->
                                val img = floorItem.floor.blocks.filterIsInstance<ContentBlock.Image>().single()
                                PostImageBlock(
                                    image = img,
                                    floorPostId = floorItem.postId,
                                    listState = listState,
                                    onOpenImageViewer = { activeArgs = it },
                                    modifier = Modifier.fillMaxWidth(0.5f),
                                )
                            }
                        }
                        activeArgs?.let { args ->
                            renderer.Overlay(args = args, onDismiss = { activeArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 点击第 3 楼（与第 1 楼同 URL 的第 3 张图）
        val postImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        postImages[2].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第 3 楼 · charlie").assertIsDisplayed()
        composeRule.onNodeWithText("3 / 3").assertIsDisplayed()

        // 在看图浮层内向右滑依次查看第 2 张与第 1 张，再向左滑回第 2 张
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()
    }

    private fun swipeHorizontally(fromFraction: Float, toFraction: Float) {
        composeRule.onRoot().performTouchInput {
            val startX = width * fromFraction
            val stepX = width * (toFraction - fromFraction) / 6f
            down(Offset(startX, height * 0.5f))
            repeat(6) { moveBy(Offset(stepX, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
    }
}
