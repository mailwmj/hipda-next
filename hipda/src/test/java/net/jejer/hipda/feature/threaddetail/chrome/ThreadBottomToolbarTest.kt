package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.favorite.DefaultFavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteCacheStore
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import net.jejer.hipda.feature.app.auditGlassModifiers
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LocalPostLinkDispatcher
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.routing.DefaultPostLinkDispatcher
import net.jejer.hipda.feature.routing.ExternalLinkOpener
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import androidx.compose.ui.test.onRoot
import net.jejer.hipda.ui.designsystem.glass.LocalHiGlassSuspended
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiCountBadge
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.GlassRenderTier
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertWcagContrastAtLeast
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.glass.resolveHiGlassStyleSpec
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 覆盖 E4 验收项 2、3、5（`B4-E4 ②`、`V-C4d` 对比度、外链白名单、MockWebServer 收藏/关注可撤销 Snackbar、
 * 窄容器自动缩为纯图标、`R-C5b` 两处修复）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ThreadBottomToolbarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun suppressBottomGlass_unmountsEntireHiGlassRegionAndReleasesSourceDemand() {
        var suppress by mutableStateOf(true)
        lateinit var glassState: HiGlassState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    glassState = rememberHiGlassState()
                    Box(Modifier.fillMaxSize()) {
                        ThreadBottomToolbar(
                            page = 2,
                            lastPage = 10,
                            isFavorited = false,
                            isAttention = false,
                            glassState = glassState,
                            suppressBottomGlass = suppress,
                            isJumpBarExpanded = false,
                            onExpandedChange = {},
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // B4-E4 ②：suppressBottomGlass = true 时外层 HiGlassRegion 整块卸载，0 标记且释放采样源需求
        assertEquals(0, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(0, countNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive)))
        assertFalse("suppressBottomGlass=true 且无其他消费者时 hasSourceDemand 必须为 false", glassState.hasSourceDemand)

        // 恢复显示：整体仅 1 个 HiGlassMarker、内部 3 枚 Toolbar 胶囊、激活采样源需求
        suppress = false
        composeRule.waitForIdle()
        assertEquals("正常态三段工具条仅记 1 个 HiGlassMarker", 1, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals("正常态三段工具条应有 3 枚 HiGlassSurface", 3, countNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive)))
        assertTrue("正常态应持有 glassState 采样源需求", glassState.hasSourceDemand)

        // 再次抑制：必须再次彻底卸载并归零采样源需求
        suppress = true
        composeRule.waitForIdle()
        assertEquals(0, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertFalse(glassState.hasSourceDemand)
    }

    /** R-E4 S2 ② 回归：`suppressBottomGlass` 切换时 `LazyColumn` 底部留白保持恒定（`ThreadBottomToolbarClearance`），帖尾零跳动。 */
    @Test
    fun suppressBottomGlass_keepsFixedBottomClearance_noScrollJumpAtThreadTail() {
        var suppress by mutableStateOf(false)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(SimpleDetailRepository(floorCount = 18), gateway)
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602999", title = "底部留白固定测试"),
                        chromeSpec = DetailChromeSpec(showBack = true, suppressBottomGlass = suppress),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 滚至帖尾最后一个条目（含末尾 footer），记录 afterContentPadding 与滚动位置
        composeRule.runOnIdle { listState.requestScrollToItem(index = listState.layoutInfo.totalItemsCount - 1, scrollOffset = 0) }
        composeRule.waitForIdle()
        val paddingBefore = listState.layoutInfo.afterContentPadding
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        assertTrue("正常态底部留白应至少为 ThreadBottomToolbarClearance", paddingBefore >= with(composeRule.density) { ThreadBottomToolbarClearance.roundToPx() })

        // 开启 suppressBottomGlass=true：卸载底部工具条但固定底部留白不得收缩，帖尾滚位严格 0 跳动
        suppress = true
        composeRule.waitForIdle()
        assertEquals("suppressBottomGlass=true 时 afterContentPadding 必须保持恒定", paddingBefore, listState.layoutInfo.afterContentPadding)
        assertEquals("suppressBottomGlass=true 时帖尾首可见项索引不得跳动", indexBefore, listState.firstVisibleItemIndex)
        assertEquals("suppressBottomGlass=true 时帖尾首可见项偏移不得跳动", offsetBefore, listState.firstVisibleItemScrollOffset)
    }

    /**
     * 对齐 `origin/main@1cffcd9` E14 看图浮层玻璃暂停契约（`LocalHiGlassSuspended`）：
     * 当 `LocalHiGlassSuspended = true`（如全屏看图覆盖底层帖子）时，底部三段式工具条（含收起态与展开跳页态）及详情页整体
     * 必须立即归零 `HiGlassMarker`、`HiGlassBlurActive(true)`、`DrawBackdrop` pass 与 `LayerBackdrop` / `hasSourceDemand` 采样，
     * 退出看图恢复 `false` 后完整恢复正常玻璃渲染。
     */
    @Test
    fun localHiGlassSuspended_stopsToolbarGlassMarkersBlurAndSourceDemand_thenRestores() {
        var suspended by mutableStateOf(false)
        var expanded by mutableStateOf(false)
        var showDetailPane by mutableStateOf(false)
        lateinit var toolbarGlassState: HiGlassState
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(SimpleDetailRepository(floorCount = 6), gateway)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiGlassSuspended provides suspended) {
                        if (!showDetailPane) {
                            toolbarGlassState = rememberHiGlassState()
                            Box(Modifier.fillMaxSize()) {
                                ThreadBottomToolbar(
                                    page = 2, lastPage = 10, isFavorited = false, isAttention = false,
                                    glassState = toolbarGlassState, suppressBottomGlass = false,
                                    isJumpBarExpanded = expanded, onExpandedChange = { expanded = it },
                                    onReplyClick = {}, onJumpToPage = {}, onToggleFavorite = {},
                                    onRefreshClick = {}, onToggleAttention = {}, onCopyLinkClick = {},
                                    onOpenInBrowserClick = {}, onShareClick = {},
                                )
                            }
                        } else {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = "2603001", title = "看图暂停底层玻璃测试"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                viewModel = vm,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 独立工具条收起态：正常态 1 Marker + 3 激活模糊 + 有采样需求；暂停后全部归零
        assertEquals(1, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(3, countNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true)))
        assertTrue(toolbarGlassState.hasSourceDemand)

        suspended = true
        composeRule.waitForIdle()
        assertEquals("LocalHiGlassSuspended=true 时工具条 HiGlassMarker 必须为 0", 0, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals("LocalHiGlassSuspended=true 时工具条激活模糊必须为 0", 0, countNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true)))
        assertFalse("LocalHiGlassSuspended=true 时必须停用 glassState 采样源需求", toolbarGlassState.hasSourceDemand)

        // 展开内联跳页条下依然保持 0 玻璃、停采样
        expanded = true
        composeRule.waitForIdle()
        assertEquals(0, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(0, countNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true)))
        assertFalse(toolbarGlassState.hasSourceDemand)

        // 恢复 false 后恢复正常
        expanded = false
        suspended = false
        composeRule.waitForIdle()
        assertEquals(1, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(3, countNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true)))
        assertTrue(toolbarGlassState.hasSourceDemand)

        // 2. 完整 ThreadDetailPane（顶栏 + 底部三段工具条 + LazyColumn.hiGlassSource）：暂停时全树 0 玻璃 / 0 LayerBackdrop / 0 DrawBackdrop
        showDetailPane = true
        composeRule.waitForIdle()
        val activeAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(2, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(1, activeAudit.layerBackdropCount)
        assertEquals(4, activeAudit.drawBackdropCount)

        suspended = true
        composeRule.waitForIdle()
        val suspendedAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals("看图覆盖期间帖子详情 HiGlassMarker 必须为 0", 0, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals("看图覆盖期间帖子详情激活模糊节点必须为 0", 0, countNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true)))
        assertEquals("看图覆盖期间必须停止 LazyColumn LayerBackdrop 离屏采样", 0, suspendedAudit.layerBackdropCount)
        assertEquals("看图覆盖期间必须停止全部 DrawBackdrop 渲染 pass", 0, suspendedAudit.drawBackdropCount)

        suspended = false
        composeRule.waitForIdle()
        val restoredAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(2, countNodes(SemanticsMatcher.expectValue(HiGlassMarker, true)))
        assertEquals(1, restoredAudit.layerBackdropCount)
        assertEquals(4, restoredAudit.drawBackdropCount)
    }

    @Test
    fun progressCapsuleColors_all20Presets_lightDarkOled_meetWcag45To1AndNeverUsePrimaryOnLightFill() {
        assertEquals(20, HiColorPresets.ALL.size)
        val modes = listOf(
            Triple("Light", false, false),
            Triple("Dark", true, false),
            Triple("OLED", true, true),
        )
        for (preset in HiColorPresets.ALL) {
            for ((modeName, darkTheme, pureBlack) in modes) {
                val cs = hiColorSchemeFromSeed(preset.color, darkTheme = darkTheme, pureBlack = pureBlack)
                val progress = resolveProgressCapsuleColors(cs, darkTheme)

                val maxAlpha = if (darkTheme) {
                    HiGlassTokens.ProgressFillContainerDarkMaxAlpha
                } else {
                    HiGlassTokens.ProgressFillContainerLightMaxAlpha
                }
                assertTrue(
                    "${preset.id}/$modeName 填充 alpha ${progress.fillColor.alpha} 超出上限 $maxAlpha",
                    progress.fillColor.alpha <= maxAlpha + 0.0001f,
                )
                assertEquals("${preset.id}/$modeName 前景文字必须使用 onSurface，严禁使用 cs.primary", cs.onSurface, progress.contentColor)

                // (a) 降级态（94% 纯色底，极端纯黑/纯白背板）+ 进度填充条
                val degradedSpec = resolveHiGlassStyleSpec(cs, darkTheme, GlassRenderTier.SOLID_DEGRADED, style = HiGlassStyle.Toolbar)
                for (backdrop in listOf(Color.Black, Color.White)) {
                    val glassSurface = degradedSpec.degradedSurfaceColor.compositeOver(backdrop)
                    val filledSurface = progress.fillColor.compositeOver(glassSurface)
                    assertWcagContrastAtLeast(
                        minRatio = 4.5f,
                        surface = filledSurface,
                        foreground = progress.contentColor,
                        label = "${preset.id}/$modeName/Degraded($backdrop)",
                    )
                }

                // (b) 模糊激活态（标准主题背板）+ 进度填充条
                val activeSpec = resolveHiGlassStyleSpec(cs, darkTheme, GlassRenderTier.REFRACTION_AND_BLUR, style = HiGlassStyle.Toolbar)
                for (backdrop in listOf(cs.background, cs.surface, cs.surfaceContainerLow, cs.surfaceContainer)) {
                    val glassSurface = activeSpec.activeTintColor.compositeOver(backdrop)
                    val filledSurface = progress.fillColor.compositeOver(glassSurface)
                    assertWcagContrastAtLeast(
                        minRatio = 4.5f,
                        surface = filledSurface,
                        foreground = progress.contentColor,
                        label = "${preset.id}/$modeName/Active",
                    )
                }
            }
        }
    }

    @Test
    fun externalUrlAllowlist_allowsHttpHttpsAndBlocksEveryDangerousScheme() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 合法 http/https 放行
        assertEquals(
            "https://www.4d4y.com/forum/viewthread.php?tid=2602293&page=3",
            buildSafeThreadUrl("https://www.4d4y.com", "2602293", 3),
        )
        assertEquals(
            "http://127.0.0.1:8080/forum/viewthread.php?tid=1001",
            buildSafeThreadUrl("http://127.0.0.1:8080/forum/", "1001", 1),
        )

        val shadowPm = Shadows.shadowOf(context.packageManager)
        val chromeComponent = ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")
        shadowPm.addActivityIfNotPresent(chromeComponent)
        shadowPm.addIntentFilterForActivity(
            chromeComponent,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("www.4d4y.com", null)
            },
        )
        shadowPm.getInternalMutablePackageInfo("com.android.chrome").activities?.forEach { it.exported = true }

        val launchedIntents = mutableListOf<Intent>()
        assertTrue(openExternalHttpUrl(context, "https://www.4d4y.com/forum/viewthread.php?tid=1001") { launchedIntents += it })
        assertEquals(1, launchedIntents.size)
        assertEquals(Intent.ACTION_VIEW, launchedIntents.single().action)
        assertEquals(chromeComponent, launchedIntents.single().component)

        launchedIntents.clear()
        assertTrue(shareExternalHttpUrl(context, "测试帖", "https://www.4d4y.com/forum/viewthread.php?tid=1001", "分享") { launchedIntents += it })
        assertEquals(1, launchedIntents.size)
        assertEquals(Intent.ACTION_CHOOSER, launchedIntents.single().action)

        // 危险 scheme 与畸形 URL 反例全拦截，绝不交给系统 Intent 或剪贴板
        val dangerousUrls = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:void(0)",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "file:///etc/passwd",
            "content://media/external/images/media/1",
            "data:text/html,<script>alert(1)</script>",
            "mailto:user@example.com",
            "tel:10086",
            "ftp://example.com/file.zip",
            "vbscript:msgbox(1)",
            "about:blank",
            "https://example.com/path with spaces",
            "https://example.com/line\nbreak",
            "https://example.com/ctrl\u0000char",
            "https:///missing-host",
            "https://user@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://user:pass@www.4d4y.com/forum/viewthread.php?tid=1",
            "",
            "   ",
        )
        launchedIntents.clear()
        for (bad in dangerousUrls) {
            assertNull("必须拦截危险或非法 URL: $bad", sanitizeExternalHttpUrl(bad))
            assertFalse("openExternalHttpUrl 必须拒绝: $bad", openExternalHttpUrl(context, bad) { launchedIntents += it })
            assertFalse("shareExternalHttpUrl 必须拒绝: $bad", shareExternalHttpUrl(context, "标题", bad, "分享") { launchedIntents += it })
            assertFalse("copyThreadLinkPlainText 必须拒绝: $bad", copyThreadLinkPlainText(context, "复制链接", bad))
        }
        assertEquals("危险 URL 严禁触发任何系统 Intent", 0, launchedIntents.size)
        assertNull("非法 tid 必须拦截", buildSafeThreadUrl("https://www.4d4y.com", "123;rm", 1))
        assertNull("危险 forumServer scheme 必须拦截", buildSafeThreadUrl("javascript:alert(1)//", "1001", 1))
    }

    @Test
    fun openInBrowser_delegatesToAndroidExternalLinkOpenerWithSelfExclusionAndInjectedOpener() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val selfPkg = context.packageName
        val threadUrl = "https://www.4d4y.com/forum/viewthread.php?tid=1001"
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            addDataAuthority("www.4d4y.com", null)
        }
        val selfComponent = ComponentName(selfPkg, "net.jejer.hipda.ng.IntentActivity")
        shadowPm.addActivityIfNotPresent(selfComponent)
        shadowPm.addIntentFilterForActivity(selfComponent, filter)

        // 1. 仅本应用匹配时，经由 AndroidExternalLinkOpener 拒绝自循环（0 startActivity）
        val launchedOnlySelf = mutableListOf<Intent>()
        assertFalse(
            "仅本应用匹配时 openExternalHttpUrl 必须经 AndroidExternalLinkOpener 拒绝自循环",
            openExternalHttpUrl(context, threadUrl) { launchedOnlySelf += it },
        )
        assertEquals(0, launchedOnlySelf.size)

        // 2. 注册外部浏览器后，经 AndroidExternalLinkOpener 显式拉起外部浏览器且排除本应用
        val chromeComponent = ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")
        shadowPm.addActivityIfNotPresent(chromeComponent)
        shadowPm.addIntentFilterForActivity(chromeComponent, filter)
        shadowPm.getInternalMutablePackageInfo("com.android.chrome").activities?.forEach { it.exported = true }

        val launchedWithChrome = mutableListOf<Intent>()
        assertTrue(openExternalHttpUrl(context, threadUrl) { launchedWithChrome += it })
        assertEquals(1, launchedWithChrome.size)
        assertEquals(chromeComponent, launchedWithChrome.single().component)
        assertTrue(launchedWithChrome.single().hasCategory(Intent.CATEGORY_BROWSABLE))

        // 3. 即使外部注入 opener，危险 scheme 仍先在 SafeLinkPolicy.sanitizeHttpUrl 拦截，0 次触发 opener
        val openedByInjected = mutableListOf<String>()
        var openerReturns = true
        val injectedOpener = ExternalLinkOpener { url ->
            openedByInjected += url
            openerReturns
        }
        val dangerousUrls = listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///etc/passwd",
            "content://media/external/images/media/1",
            "data:text/html,<script>alert(1)</script>",
            "mailto:user@example.com",
        )
        for (bad in dangerousUrls) {
            assertFalse("危险 scheme 必须先被 SafeLinkPolicy.sanitizeHttpUrl 拒绝: $bad", openExternalHttpUrl(context, bad, injectedOpener))
        }
        assertEquals("危险 scheme 绝不得调用 ExternalLinkOpener", 0, openedByInjected.size)

        // 4. 工具条「在浏览器中打开」经 LocalPostLinkDispatcher 复用同一 ExternalLinkOpener 实例，失败时展示无浏览器提示
        val snackMessages = mutableListOf<String>()
        val fakeSnackbar = object : PaneSnackbarController {
            override suspend fun show(
                message: String,
                target: PaneSnackbarTarget,
                actionLabel: String?,
                onAction: (() -> Unit)?,
            ) {
                snackMessages += message
            }
        }
        val dispatcher = DefaultPostLinkDispatcher(
            forumServerProvider = { AppSettings.DEFAULT_FORUM_SERVER },
            onOpenForum = {},
            onOpenThread = { _, _ -> },
            onOpenThreadLastPage = {},
            onOpenPost = { _, _ -> },
            onOpenUserProfile = {},
            externalLinkOpener = injectedOpener,
        )
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(
            SimpleDetailRepository(),
            gateway,
            SwitchableAuthRepository(null, AuthState.SignedOut),
            null,
            StaticSettingsRepository(AppSettings(forumServer = "https://www.4d4y.com")),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalPaneSnackbarController provides fakeSnackbar,
                LocalPostLinkDispatcher provides dispatcher,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "外链复用测试"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在浏览器中打开").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://www.4d4y.com/forum/viewthread.php?tid=2602293"), openedByInjected)
        assertTrue(snackMessages.isEmpty())

        openerReturns = false
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在浏览器中打开").performClick()
        composeRule.waitForIdle()
        assertEquals(2, openedByInjected.size)
        assertEquals(listOf("未找到可用的浏览器"), snackMessages)
    }

    @Test
    fun favoriteAttentionCopyLinkAndGuestPrompt_workEndToEndWithMockWebServer() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        val server = MockWebServer()
        server.start()
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val okHttpClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "HiPDA-Test-UA" },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        try {
            val baseServerUrl = server.url("").toString().trimEnd('/')
            val account = SavedAccount(uid = "1001", username = "测试账号", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
            val authRepo = SwitchableAuthRepository(account, AuthState.SignedIn(account))
            val settingsRepo = StaticSettingsRepository(AppSettings(forumServer = baseServerUrl))
            val gateway = SiteAccessGateway(okHttpClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val httpClient = ForumHttpClient(gateway, settingsRepo, authRepo)
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = repoScope,
                produceFile = { File(tempFolder.newFolder(), "fav_cache_test.preferences_pb") },
            )
            val favoriteRepo = DefaultFavoriteRepository(
                forumHttpClient = httpClient,
                authRepository = authRepo,
                favoriteCacheStore = FavoriteCacheStore(dataStore),
                clock = { 1_000L },
                scope = repoScope,
            )

            data class SnackRecord(
                val message: String,
                val target: PaneSnackbarTarget,
                val actionLabel: String?,
                val onAction: (() -> Unit)?,
            )
            val snackbars = mutableListOf<SnackRecord>()
            val fakeSnackbar = object : PaneSnackbarController {
                override suspend fun show(
                    message: String,
                    target: PaneSnackbarTarget,
                    actionLabel: String?,
                    onAction: (() -> Unit)?,
                ) {
                    snackbars += SnackRecord(message, target, actionLabel, onAction)
                }
            }

            val detailRepo = SimpleDetailRepository()
            val vm = ThreadDetailViewModel(detailRepo, gateway, authRepo, favoriteRepo, settingsRepo)
            var loginClicks = 0

            composeRule.setContent {
                CompositionLocalProvider(LocalPaneSnackbarController provides fakeSnackbar) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "2602293", title = "收藏关注与更多菜单测试"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = { loginClicks++ },
                            viewModel = vm,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            // 1. 收藏 -> 立即切为取消收藏 + DetailPane Snackbar「已收藏」「撤销」-> 点击撤销恢复「收藏」
            server.enqueue(ajaxXmlResponse("主题已成功添加到收藏夹中"))
            composeRule.onNodeWithContentDescription("收藏")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            awaitWallCondition(5_000L, { "snackbars=$snackbars req=${server.requestCount} state=${vm.uiState.value}" }) {
                snackbars.any { it.message == "已收藏" }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("取消收藏").assertIsDisplayed()
            assertEquals("已收藏", snackbars.last().message)
            assertEquals(PaneSnackbarTarget.DetailPane, snackbars.last().target)
            assertEquals("撤销", snackbars.last().actionLabel)
            val favAddReq = server.takeRequest()
            assertTrue(favAddReq.target.contains("item=favorites") && favAddReq.target.contains("action=add"))

            server.enqueue(ajaxXmlResponse("此主题已成功从您的收藏夹中移除。"))
            composeRule.runOnIdle { snackbars.last().onAction?.invoke() }
            awaitWallCondition(5_000L, { "req=${server.requestCount} fav=${vm.uiState.value.isFavorited}" }) {
                !vm.uiState.value.isFavorited && !favoriteRepo.isFavorite("2602293") && server.requestCount >= 2
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("收藏").assertIsDisplayed()
            val favUndoReq = server.takeRequest()
            assertTrue(favUndoReq.target.contains("item=favorites") && favUndoReq.target.contains("action=remove"))

            // 2. 更多菜单严格去重：含 刷新/关注/复制链接/在浏览器中打开/分享，不含 收藏/取消收藏/只看楼主/举报
            composeRule.onNodeWithContentDescription("更多")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeRule.waitForIdle()
            for (item in listOf("刷新", "关注", "复制链接", "在浏览器中打开", "分享")) {
                composeRule.onNodeWithText(item).assertIsDisplayed()
            }
            assertEquals("更多菜单不得重复出现收藏", 0, composeRule.onAllNodesWithText("收藏").fetchSemanticsNodes().size)
            assertEquals("更多菜单不得重复出现只看楼主", 0, composeRule.onAllNodesWithText("只看楼主").fetchSemanticsNodes().size)
            assertEquals("更多菜单不得出现举报", 0, composeRule.onAllNodesWithText("举报").fetchSemanticsNodes().size)

            // 点击 关注 -> DetailPane Snackbar「已关注」「撤销」
            server.enqueue(ajaxXmlResponse("此主题已成功添加到您的关注列表中。"))
            composeRule.onNodeWithText("关注").performClick()
            awaitWallCondition(5_000L, { "snackbars=$snackbars req=${server.requestCount}" }) {
                snackbars.any { it.message == "已关注" }
            }
            composeRule.waitForIdle()
            assertEquals("已关注", snackbars.last().message)
            assertEquals(PaneSnackbarTarget.DetailPane, snackbars.last().target)
            assertEquals("撤销", snackbars.last().actionLabel)
            assertTrue(vm.uiState.value.isAttention)
            val attAddReq = server.takeRequest()
            assertTrue(attAddReq.target.contains("item=attention") && attAddReq.target.contains("action=add"))

            server.enqueue(ajaxXmlResponse("此主题已成功从您的关注列表中移除。"))
            composeRule.runOnIdle { snackbars.last().onAction?.invoke() }
            awaitWallCondition(5_000L, { "req=${server.requestCount} att=${vm.uiState.value.isAttention}" }) {
                !vm.uiState.value.isAttention && !favoriteRepo.isAttention("2602293") && server.requestCount >= 4
            }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.isAttention)
            server.takeRequest()

            // 3. 复制链接：严格仅 ClipData.newPlainText + 链接已复制
            composeRule.onNodeWithContentDescription("更多").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("复制链接").performClick()
            composeRule.waitForIdle()

            val context = ApplicationProvider.getApplicationContext<Context>()
            val clip = context.getSystemService(ClipboardManager::class.java)!!.primaryClip
            assertNotNull(clip)
            assertTrue(clip!!.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            assertFalse(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            assertNull(clip.getItemAt(0).htmlText)
            assertNull(clip.getItemAt(0).uri)
            assertEquals("$baseServerUrl/forum/viewthread.php?tid=2602293", clip.getItemAt(0).text.toString())
            assertEquals("链接已复制", snackbars.last().message)
            assertEquals(PaneSnackbarTarget.DetailPane, snackbars.last().target)

            // 4. 游客态点击收藏：零网络请求，弹「登录后即可操作」+「登录」路由
            authRepo.signOut()
            composeRule.waitForIdle()
            val reqCountBefore = server.requestCount
            composeRule.onNodeWithContentDescription("收藏").performClick()
            composeRule.waitForIdle()
            assertEquals("游客态点击收藏不得发网", reqCountBefore, server.requestCount)
            assertEquals("登录后即可操作", snackbars.last().message)
            assertEquals("登录", snackbars.last().actionLabel)
            snackbars.last().onAction?.invoke()
            assertEquals(1, loginClicks)
        } finally {
            repoScope.cancel()
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            server.close()
        }
    }

    /**
     * 撤销令牌模型回归（负责人补充规则）：
     * - 每次可撤销操作生成独立令牌；发起新可撤销操作时立即关闭上一条 Snackbar 并确认上一次操作不可再撤销；
     * - 快速连续点两次收藏切换（收藏 -> 取消收藏）后再点撤销，最终 UI 状态与 `FavoriteRepository` 缓存严格一致（均已收藏）；
     * - 旧令牌对应的撤销调用不生效，且撤销只恢复当前令牌对应的那一次操作。
     */
    @Test
    fun undoTokenModel_rapidDoubleToggleFavoriteThenUndo_dismissesPreviousSnackbarAndMatchesCache() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        val server = MockWebServer()
        server.start()
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val okHttpClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "HiPDA-Test-UA" },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        try {
            val baseServerUrl = server.url("").toString().trimEnd('/')
            val account = SavedAccount(uid = "1001", username = "测试账号", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
            val authRepo = SwitchableAuthRepository(account, AuthState.SignedIn(account))
            val settingsRepo = StaticSettingsRepository(AppSettings(forumServer = baseServerUrl))
            val gateway = SiteAccessGateway(okHttpClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val httpClient = ForumHttpClient(gateway, settingsRepo, authRepo)
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = repoScope,
                produceFile = { File(tempFolder.newFolder(), "fav_double_toggle.preferences_pb") },
            )
            val favoriteRepo = DefaultFavoriteRepository(
                forumHttpClient = httpClient,
                authRepository = authRepo,
                favoriteCacheStore = FavoriteCacheStore(dataStore),
                clock = { 1_000L },
                scope = repoScope,
            )
            val detailRepo = SimpleDetailRepository()
            val vm = ThreadDetailViewModel(detailRepo, gateway, authRepo, favoriteRepo, settingsRepo)
            val snackbarHostState = SnackbarHostState()
            val realSnackbarController = SingleHostPaneSnackbarController(snackbarHostState)

            composeRule.setContent {
                CompositionLocalProvider(LocalPaneSnackbarController provides realSnackbarController) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        Box(Modifier.fillMaxSize()) {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = "2602293", title = "快速连续收藏撤销测试"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                viewModel = vm,
                            )
                            HiSnackbarHost(
                                hostState = snackbarHostState,
                                bottomClearance = 72.dp,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            assertFalse(favoriteRepo.isFavorite("2602293"))

            // 1. 快速连续点两次收藏切换（第 1 次：收藏；第 2 次：取消收藏）
            server.enqueue(ajaxXmlResponse("主题已成功添加到收藏夹中"))
            server.enqueue(ajaxXmlResponse("此主题已成功从您的收藏夹中移除。"))
            composeRule.runOnIdle {
                vm.toggleFavorite() // 操作 1（token 1）：false -> true
                vm.toggleFavorite() // 操作 2（token 2）：true -> false，立即确认 token 1 并取消旧 Snackbar
            }
            awaitWallCondition(5_000L, { "snack=${snackbarHostState.currentSnackbarData?.visuals?.message} req=${server.requestCount}" }) {
                snackbarHostState.currentSnackbarData?.visuals?.message == "已取消收藏" &&
                    server.requestCount >= 2
            }
            composeRule.waitForIdle()

            // 旧条「已收藏」不得存在，仅展示最新「已取消收藏」+「撤销」
            assertEquals(0, composeRule.onAllNodesWithText("已收藏").fetchSemanticsNodes().size)
            composeRule.onNodeWithText("已取消收藏").assertIsDisplayed()
            assertFalse("两次切换后 UI 状态应为未收藏", vm.uiState.value.isFavorited)
            assertFalse("两次切换后 FavoriteRepository 缓存应为未收藏", favoriteRepo.isFavorite("2602293"))

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertTrue("第 1 次请求应为 add：${req1.target}", req1.target.contains("action=add"))
            assertTrue("第 2 次请求应为 remove：${req2.target}", req2.target.contains("action=remove"))

            // 2. 尝试用旧令牌（tokenId = 1）撤销：必须被拒绝（零网络请求、状态不变）
            val countBeforeStaleUndo = server.requestCount
            composeRule.runOnIdle { vm.undoFavoriteMutation(tokenId = 1L) }
            composeRule.waitForIdle()
            assertEquals("已被新操作确认的旧令牌不得再次撤销", countBeforeStaleUndo, server.requestCount)
            assertFalse(vm.uiState.value.isFavorited)

            // 3. 点击当前 Snackbar「撤销」（对应 token 2：取消收藏 -> 恢复收藏）
            server.enqueue(ajaxXmlResponse("主题已成功添加到收藏夹中"))
            composeRule.onNodeWithText("撤销").performClick()
            awaitWallCondition(5_000L, { "fav=${vm.uiState.value.isFavorited} cache=${favoriteRepo.isFavorite("2602293")} req=${server.requestCount}" }) {
                vm.uiState.value.isFavorited && favoriteRepo.isFavorite("2602293") && server.requestCount >= 3
            }
            composeRule.waitForIdle()

            assertTrue("撤销第 2 次操作后 UI 应恢复为已收藏", vm.uiState.value.isFavorited)
            assertTrue("撤销后 FavoriteRepository.isFavorite 必须一致为 true", favoriteRepo.isFavorite("2602293"))
            assertTrue("撤销后 favoriteTids 缓存必须包含该 tid", "2602293" in favoriteRepo.favoriteTids.value)
            val req3 = server.takeRequest()
            assertTrue("撤销取消收藏应发起 add：${req3.target}", req3.target.contains("action=add"))

            // 4. 跨类型新操作（收藏后立即关注）：再发新操作时立即关闭上一条「已关注」Snackbar，且已确认操作不受影响
            server.enqueue(ajaxXmlResponse("此主题已成功添加到您的关注列表中。"))
            composeRule.onNodeWithContentDescription("更多").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("关注").performClick()
            awaitWallCondition(5_000L, { "snack=${snackbarHostState.currentSnackbarData?.visuals?.message} att=${vm.uiState.value.isAttention}" }) {
                snackbarHostState.currentSnackbarData?.visuals?.message == "已关注" &&
                    vm.uiState.value.isAttention &&
                    favoriteRepo.isAttention("2602293")
            }
            composeRule.waitForIdle()

            // 再点一次取消收藏时，发起瞬间即关掉上一条「已关注」Snackbar
            server.enqueue(ajaxXmlResponse("此主题已成功从您的收藏夹中移除。"))
            composeRule.runOnIdle { vm.toggleFavorite() }
            awaitWallCondition(5_000L, { "snack=${snackbarHostState.currentSnackbarData?.visuals?.message} fav=${favoriteRepo.isFavorite("2602293")}" }) {
                snackbarHostState.currentSnackbarData?.visuals?.message == "已取消收藏" &&
                    !favoriteRepo.isFavorite("2602293")
            }
            composeRule.waitForIdle()
            assertEquals("新操作发起后上一条「已关注」Snackbar 必须已关闭", 0, composeRule.onAllNodesWithText("已关注").fetchSemanticsNodes().size)
            assertTrue("取消收藏不得影响已确认的关注状态", favoriteRepo.isAttention("2602293"))
        } finally {
            repoScope.cancel()
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    @Config(qualifiers = "w340dp-h800dp-xhdpi")
    fun narrow340dpWith125FontScale_autoShrinksReplyTo48dpIconOnlyAndCompactPageIndicator() {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.25f)) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val glassState = rememberHiGlassState()
                    Box(Modifier.width(340.dp).fillMaxSize()) {
                        ThreadBottomToolbar(
                            page = 12,
                            lastPage = 45,
                            isFavorited = false,
                            isAttention = false,
                            glassState = glassState,
                            suppressBottomGlass = false,
                            isJumpBarExpanded = false,
                            onExpandedChange = {},
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 窄容器自动缩为纯图标 48×48dp（无“回复”文字，保留“回复”内容描述）
        assertEquals("窄容器紧凑态不应渲染“回复”文字", 0, composeRule.onAllNodesWithText("回复").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("回复")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("第 12 页，共 45 页，点按展开跳页")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    /** 闭合 R-C5b 两处遗留：HiPressFeedback detach 复位与 HiCountBadge/HiRollingNumber 无组合体内状态写。 */
    @Test
    fun rC5b_pressFeedbackDetachResetsStaleScale_andCountBadgeHasNoExtraSelfRecomposition() {
        // 1. HiPressFeedback：按下缩至 0.97 后未松开即 detach，再重新 attach 必须回到 1.0 静息态
        withAnimatorDurationScale(1f) {
            val interactionSource = MutableInteractionSource()
            var mounted by mutableStateOf(true)
            var scope: CoroutineScope? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    if (mounted) {
                        Box(
                            Modifier
                                .size(100.dp)
                                .hiPressScale(interactionSource)
                                .testTag("pressDetachTarget"),
                        )
                    }
                }
            }
            waitForIdle()
            val baseWidth = onNodeWithTag("pressDetachTarget").fetchSemanticsNode().boundsInRoot.width

            requireNotNull(scope).launch { interactionSource.emit(PressInteraction.Press(Offset.Zero)) }
            waitForIdle()
            val pressedScale = onNodeWithTag("pressDetachTarget").fetchSemanticsNode().boundsInRoot.width / baseWidth
            assertEquals("前提：按下后应缩至 0.97", 0.97f, pressedScale, 0.002f)

            // 模拟列表滚出/切页未触发 Release 即 detach，再重新 attach
            mounted = false
            waitForIdle()
            mounted = true
            waitForIdle()

            val reattachedScale = onNodeWithTag("pressDetachTarget").fetchSemanticsNode().boundsInRoot.width / baseWidth
            assertEquals("R-C5b：onDetach 复位 anim 后重新 attach 必须恢复静息缩放 1.0", 1.0f, reattachedScale, 0.002f)
        }

        // 2. HiCountBadge / HiRollingNumber：普通引用容器消除组合体内写 MutableState，状态变更不触发多余二次重组
        withAnimatorDurationScale(0f) {
            var count by mutableIntStateOf(3)
            var rolling by mutableIntStateOf(9)
            val badgeHostCounter = RecompositionCounter("BadgeHost")
            val rollingHostCounter = RecompositionCounter("RollingHost")

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box {
                        badgeHostCounter.Track()
                        HiCountBadge(count = count, modifier = Modifier.testTag("rC5bBadge"))
                    }
                    Box {
                        rollingHostCounter.Track()
                        HiRollingNumber(value = rolling, modifier = Modifier.testTag("rC5bRolling"))
                    }
                }
            }
            waitForIdle()
            badgeHostCounter.assertEquals(1)
            rollingHostCounter.assertEquals(1)

            count = 5
            rolling = 10
            waitForIdle()
            onNodeWithText("5").assertIsDisplayed()
            onNodeWithText("10").assertIsDisplayed()
            badgeHostCounter.assertEquals(2)
            rollingHostCounter.assertEquals(2)

            count = 0
            waitForIdle()
            onNodeWithTag("rC5bBadge").assertDoesNotExist()
            onNodeWithText("0").assertDoesNotExist()
        }
    }

    private fun countNodes(matcher: SemanticsMatcher): Int =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun awaitWallCondition(
        timeoutMs: Long,
        failureDetail: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(15L)
        }
        composeRule.waitForIdle()
        if (!predicate()) {
            throw AssertionError("Condition timed out after ${timeoutMs}ms: ${failureDetail()}")
        }
    }

    private fun ajaxXmlResponse(message: String): MockResponse {
        val xml = "<?xml version=\"1.0\" encoding=\"gbk\"?><root><![CDATA[$message]]></root>"
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/xml; charset=gbk")
            .body(Buffer().write(xml.toByteArray(Charset.forName("GB18030"))))
            .build()
    }

    private class SwitchableAuthRepository(initial: SavedAccount?, state: AuthState) : AuthRepository {
        private val _account = MutableStateFlow(initial)
        private val _state = MutableStateFlow(state)
        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initial)).asStateFlow()
        override val authState: StateFlow<AuthState> = _state.asStateFlow()
        override val sessionEpoch: Long = 1L
        fun signOut() {
            _account.value = null
            _state.value = AuthState.SignedOut
        }
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() = signOut()
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class StaticSettingsRepository(private val initial: AppSettings) : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(initial)
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

    private class SimpleDetailRepository(private val floorCount: Int = 1) : ThreadDetailRepository {
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
                title = "收藏关注与更多菜单测试",
                page = page,
                lastPage = 3,
                floors = (1..floorCount).map { idx ->
                    PostFloor(
                        "f$idx", idx, page, if (idx == 1) "1001" else "${1000 + idx}", if (idx == 1) "楼主" else "坛友$idx",
                        null, "2026-11-26 10:${idx.toString().padStart(2, '0')}", false, null, null, null,
                        listOf(ContentBlock.Text(listOf(InlineSpan.Plain("$idx 楼正文内容：用于验证底部工具条与固定留白在帖尾不跳动。")))),
                    )
                },
                postsPerPageEstimate = 12,
                meta = DiscuzPageMeta(DiscuzSessionInfo(true, "1001", "测试账号"), "fh1", null, PromptCounts()),
                hasPostList = true,
            ),
            filter = filter,
            threadAuthorId = "1001",
        )

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 3)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "2602293", 1)
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
}
