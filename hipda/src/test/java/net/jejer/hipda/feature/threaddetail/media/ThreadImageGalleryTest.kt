package net.jejer.hipda.feature.threaddetail.media

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 M7-G2 #9 同帖看图多图横滑图集提取与 [PostImageBlock] 定位契约（撤回即红）：
 * - [extractThreadGalleryItems] 按楼层/块序提取合法 `http`/`https` 图集，跳过屏蔽楼层与全部危险 scheme；
 * - [PostImageBlock] 挂载 [LocalThreadImageGallery]（含跨楼层重复 URL）点第 3 张精确定位 `initialIndex = 2` 并透传楼层/作者；
 * - [LocalThreadImageGallery] 注入危险 scheme 时纵深过滤；未提供 Provider 时安全回退单图 `index = 0`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class ThreadImageGalleryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var savedNetworkState: SavedTestNetworkState

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
    fun extractThreadGalleryItems_preservesOrderSkipsBlockedFloorsAndBlocksAllDangerousSchemes() {
        val dupUrl = "https://img.example.invalid/dup.jpg"
        val midUrl = "http://img.example.invalid/mid.jpg"
        val floors = listOf(
            floorItem(
                postId = "p1",
                floor = 1,
                author = "alice",
                blocks = listOf(
                    ContentBlock.Text(listOf(InlineSpan.Plain("text"))),
                    ContentBlock.Image(fullUrl = dupUrl, thumbUrl = "https://img.example.invalid/dup_thumb.jpg", fileSizeBytes = 1024L),
                    ContentBlock.Image(fullUrl = "javascript:alert(1)", thumbUrl = "javascript:alert(1)", fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "file:///etc/hosts", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "content://media/external/images/1", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "intent://scan/#Intent;end", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "data:image/png;base64,AAA", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "mailto:a@b.com", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = "//evil.example.invalid/rel.jpg", thumbUrl = null, fileSizeBytes = 0L),
                    ContentBlock.Image(fullUrl = " https://img.example.invalid/space.jpg", thumbUrl = null, fileSizeBytes = 0L),
                ),
            ),
            floorItem(
                postId = "p_blocked",
                floor = 2,
                author = "blocked_user",
                isBlocked = true,
                blocks = listOf(
                    ContentBlock.Image(fullUrl = "https://img.example.invalid/blocked.jpg", thumbUrl = null, fileSizeBytes = 1024L),
                ),
            ),
            floorItem(
                postId = "p2",
                floor = 3,
                author = "bob",
                blocks = listOf(
                    ContentBlock.Image(fullUrl = "file:///etc/passwd", thumbUrl = midUrl, fileSizeBytes = 2048L),
                ),
            ),
            floorItem(
                postId = "p3",
                floor = 4,
                author = "charlie",
                blocks = listOf(
                    ContentBlock.Image(fullUrl = dupUrl, thumbUrl = null, fileSizeBytes = 1024L),
                ),
            ),
        )

        val gallery = extractThreadGalleryItems(floors)
        assertEquals(
            listOf(
                ThreadGalleryItem(url = dupUrl, floorPostId = "p1", floorNumber = 1, author = "alice"),
                ThreadGalleryItem(url = midUrl, floorPostId = "p2", floorNumber = 3, author = "bob"),
                ThreadGalleryItem(url = dupUrl, floorPostId = "p3", floorNumber = 4, author = "charlie"),
            ),
            gallery,
        )
    }

    @Test
    fun postImageBlock_withGalleryContainingDuplicateUrlsAcrossFloors_resolvesExactThirdIndexAndMetadata() {
        val dupUrl = "https://img.example.invalid/shared_meme.jpg"
        val midUrl = "https://img.example.invalid/middle_photo.jpg"
        val gallery = listOf(
            ThreadGalleryItem(url = dupUrl, floorPostId = "p1", floorNumber = 1, author = "alice"),
            ThreadGalleryItem(url = midUrl, floorPostId = "p2", floorNumber = 2, author = "bob"),
            ThreadGalleryItem(url = dupUrl, floorPostId = "p3", floorNumber = 3, author = "charlie"),
        )
        val loader = OfflineTestImageLoader.success()
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image(fullUrl = dupUrl, thumbUrl = null, fileSizeBytes = 1024L),
                            floorPostId = "p1",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                        PostImageBlock(
                            image = ContentBlock.Image(fullUrl = midUrl, thumbUrl = null, fileSizeBytes = 1024L),
                            floorPostId = "p2",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                        PostImageBlock(
                            image = ContentBlock.Image(fullUrl = dupUrl, thumbUrl = null, fileSizeBytes = 1024L),
                            floorPostId = "p3",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val clickableImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        clickableImages[2].performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened.size)
        val args = opened.single()
        assertEquals(listOf(dupUrl, midUrl, dupUrl), args.urls)
        assertEquals("跨楼层重复 URL 点击第 3 张必须精确定位到 initialIndex == 2", 2, args.initialIndex)
        assertEquals(3, args.floorNumber)
        assertEquals("charlie", args.author)
        assertNotNull(args.sourceBoundsInWindow)
    }

    @Test
    fun postImageBlock_withUnsafeUrlsInGalleryProvider_stripsDangerousSchemesAndFallsBackWhenEmpty() {
        val safe1 = "https://img.example.invalid/safe_1.jpg"
        val safe2 = "http://img.example.invalid/safe_2.jpg"
        val taintedGallery = listOf(
            ThreadGalleryItem(url = "javascript:alert(1)", floorPostId = "bad1", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = "file:///etc/hosts", floorPostId = "bad2", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = "content://media/external/images/1", floorPostId = "bad3", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = "intent://scan/#Intent;end", floorPostId = "bad4", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = "data:image/png;base64,AAA", floorPostId = "bad5", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = "mailto:test@example.com", floorPostId = "bad6", floorNumber = 1, author = "evil"),
            ThreadGalleryItem(url = safe1, floorPostId = "p1", floorNumber = 2, author = "alice"),
            ThreadGalleryItem(url = safe2, floorPostId = "p2", floorNumber = 3, author = "bob"),
        )
        val loader = OfflineTestImageLoader.success()
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompositionLocalProvider(LocalThreadImageGallery provides taintedGallery) {
                            PostImageBlock(
                                image = ContentBlock.Image(fullUrl = safe2, thumbUrl = null, fileSizeBytes = 1024L),
                                floorPostId = "p2",
                                listState = listState,
                                onOpenImageViewer = { opened += it },
                            )
                        }
                        PostImageBlock(
                            image = ContentBlock.Image(fullUrl = safe1, thumbUrl = null, fileSizeBytes = 1024L),
                            floorPostId = "p_standalone",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val clickableImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        clickableImages[0].performClick()
        clickableImages[1].performClick()
        composeRule.waitForIdle()

        assertEquals(2, opened.size)
        assertEquals(listOf(safe1, safe2), opened[0].urls)
        assertEquals(1, opened[0].initialIndex)
        assertEquals(3, opened[0].floorNumber)
        assertEquals("bob", opened[0].author)

        assertEquals(listOf(safe1), opened[1].urls)
        assertEquals(0, opened[1].initialIndex)
        assertNull(opened[1].floorNumber)
        assertNull(opened[1].author)
    }

    private fun floorItem(
        postId: String,
        floor: Int,
        author: String,
        blocks: List<ContentBlock>,
        isBlocked: Boolean = false,
    ): ThreadDetailFloorItem = ThreadDetailFloorItem(
        floor = PostFloor(
            postId = postId,
            floor = floor,
            page = 1,
            uid = floor.toString(),
            author = author,
            avatarUrl = null,
            timePost = "2026-11-26 12:00",
            isWarned = false,
            postStatus = null,
            signature = null,
            poll = null,
            blocks = blocks,
        ),
        isAuthorBlocked = isBlocked,
    )
}
