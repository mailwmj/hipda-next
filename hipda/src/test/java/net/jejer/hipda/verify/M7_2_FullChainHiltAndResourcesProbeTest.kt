package net.jejer.hipda.verify

import android.app.Application
import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.feature.app.MainActivity
import net.jejer.hipda.feature.common.AppIntentConstants
import net.jejer.hipda.ui.HiNextApplication
import okhttp3.OkHttp
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V-M7-2 独立探针 3 与补充 (b)/(d) 全链路探针：
 * 经真实 `@HiltAndroidApp HiNextApplication` + `@AndroidEntryPoint MainActivity` + `NextAppRoot` + `MockWebServer`
 * 跑通：冷启动 → 论坛 → 帖子详情（含 3 组论坛表情与内置 `win` 图）→ 看图浮层 → 快速回复面板 → 完整编辑器 →
 * 消息（短消息/提醒）→ 搜索 → 我的 → 设置 → 关于文档 → 脱敏错误日志查看。
 * 全程离线（禁止任何请求离开 `127.0.0.1`），验证删库与删资源后零 `Resources.NotFoundException` 或 `ClassNotFoundException`。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M7_2_FullChainHiltAndResourcesProbeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var app: HiNextApplication
    private lateinit var mockServer: MockWebServer
    private val externalHostViolations = CopyOnWriteArrayList<String>()
    private val recordedPaths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as HiNextApplication
        OkHttp.initialize(app)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(app.workManagerConfiguration.workerFactory)
                .build(),
        )

        val pngBytes = createSolidPngBytes()
        mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val hostHeader = request.headers["Host"].orEmpty()
                if (hostHeader.contains("4d4y.com", ignoreCase = true) ||
                    hostHeader.contains("hi-pda.com", ignoreCase = true)
                ) {
                    externalHostViolations.add(hostHeader + " " + request.url.encodedPath)
                    return MockResponse(code = 403, body = "Forbidden external host in offline test")
                }
                val path = request.url.encodedPath
                val query = request.url.encodedQuery.orEmpty()
                recordedPaths.add("$path?$query")
                val baseUrl = mockServer.url("/forum/").toString()
                val imgUrl = mockServer.url("/img/probe_sample.png").toString()
                return when {
                    path.endsWith("/img/probe_sample.png") || path.endsWith(".jpg") -> {
                        MockResponse.Builder()
                            .code(200)
                            .headers(
                                okhttp3.Headers.headersOf(
                                    "Content-Type", "image/png",
                                    "Content-Length", pngBytes.size.toString(),
                                ),
                            )
                            .body(Buffer().write(pngBytes))
                            .build()
                    }
                    path.contains("forumdisplay.php") -> gbkHtmlResponse(buildForumListHtml())
                    path.contains("viewthread.php") -> gbkHtmlResponse(buildThreadDetailHtml(baseUrl, imgUrl))
                    path.contains("pm.php") -> gbkHtmlResponse(buildSmsListHtml())
                    path.contains("notice.php") -> gbkHtmlResponse(buildNotifyListHtml())
                    path.contains("my.php") -> gbkHtmlResponse(buildSimpleListHtml())
                    path.contains("search.php") -> gbkHtmlResponse(buildSearchHtml())
                    path.contains("post.php") && query.contains("action=reply") -> gbkHtmlResponse(buildPostFormHtml())
                    else -> gbkHtmlResponse("<html><body>ok</body></html>")
                }
            }
        }
        mockServer.start()

        // Bootstrap Hilt @Singleton SettingsRepository via a non-network Search-routed Activity before main launch
        val forumServerUrl = mockServer.url("/forum").toString().trimEnd('/')
        val bootstrap = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).setAction(AppIntentConstants.INTENT_SEARCH),
        ).create()
        val settingsRepo = bootstrap.get().settingsRepository
        runBlocking {
            settingsRepo.setForumServer(forumServerUrl)
            settingsRepo.setNotificationsEnabled(false)
            settingsRepo.settings.first { it.forumServer == forumServerUrl }
        }
        bootstrap.destroy()

        // Pre-seed crash log with sensitive tokens for Settings -> Error Logs verification
        val logsDir = File(app.filesDir, "logs").apply { mkdirs() }
        File(logsDir, "crash-20261126-190000-000.log").writeText(
            """
            设备名称 : ProbePhone
            java.lang.IllegalStateException: failure uid=887766 cookie=cdb_auth=TopSecretToken123 formhash=deadbeef ip=203.0.113.88
            SavedAccount(uid=887766, username=ProbeUser, password=PlainSecret999)
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    @After
    fun tearDown() {
        runCatching { mockServer.close() }
        runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    @Test
    fun probe3_realHiltAppColdStartThroughAllCoreScreensAndSanitizedErrorLogs() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Cold start -> ForumPane renders thread from MockWebServer
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("M7-2 独立全链路探针帖子", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("M7-2 独立全链路探针帖子", substring = true).assertIsDisplayed()

            // 2. Open ThreadDetailPane (renders 3 emoji groups + win badge + image block)
            composeRule.onNodeWithText("M7-2 独立全链路探针帖子", substring = true).performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("正文第一段", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("正文第一段", substring = true).assertIsDisplayed()

            // 3. Open ImageViewerOverlay by clicking loaded clickable Role.Image block (excludes inline emojis/win)
            val clickableImageMatcher =
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and hasClickAction()
            try {
                composeRule.waitUntil(timeoutMillis = 15_000) {
                    composeRule.onAllNodes(clickableImageMatcher).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (t: Throwable) {
                val tree = runCatching { composeRule.onRoot().printToString() }.getOrElse { it.toString() }
                throw AssertionError(
                    "Image failed to load. Recorded paths: $recordedPaths\nTree:\n$tree",
                    t,
                )
            }
            composeRule.onAllNodes(clickableImageMatcher).onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("保存图片").assertIsDisplayed()
            composeRule.onNodeWithText("分享").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("关闭").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isEmpty()
            }

            // 4. Open QuickReplySheet (guest or logged-in) and verify FullEditorScreen too
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("收起回复面板")
                    .fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("登录后即可操作").fetchSemanticsNodes().isNotEmpty()
            }
            if (composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            } else {
                // Dismiss sheet via Android back if handle is hidden in guest mode
                androidx.test.espresso.Espresso.pressBack()
            }

            // Return from ThreadDetailPane back to ForumPane and open FullEditorScreen via "发新帖"
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("返回")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("返回").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("发新帖")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("发新帖").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("发新帖").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("返回").performClick()

            // 5. Messages (SMS + Notify)
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("消息").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("hi_shell_nav_item_Messages").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("短消息").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("提醒").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("提醒").performClick()
            composeRule.waitForIdle()

            // 6. Search
            composeRule.onNodeWithTag("hi_shell_nav_item_Search").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("搜索标题、正文或作者").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("搜索标题、正文或作者").assertIsDisplayed()

            // 7. Mine -> Settings -> About -> License/ReleaseNotes/Donors & Error Logs View
            composeRule.onNodeWithTag("hi_shell_nav_item_Mine").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("收藏与关注").assertIsDisplayed()
            composeRule.onNodeWithText("我的帖子").assertIsDisplayed()
            composeRule.onNodeWithText("浏览历史").assertIsDisplayed()

            composeRule.onNodeWithText("设置").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("关于").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("外观与阅读").assertIsDisplayed()
            composeRule.onNodeWithText("论坛与发帖").assertIsDisplayed()
            composeRule.onNodeWithText("通知与免打扰").assertIsDisplayed()
            composeRule.onNodeWithText("网络与缓存").assertIsDisplayed()

            composeRule.onNodeWithText("关于").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("开源许可").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("更新记录").assertIsDisplayed()
            composeRule.onNodeWithText("捐助名单").assertIsDisplayed()

            // Open License dialog (verifies assets/license.txt renders cleanly)
            composeRule.onNodeWithText("开源许可").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("backdrop", substring = true, ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("关闭").performClick()

            // Open Release Notes dialog (verifies assets/release-notes.txt renders cleanly)
            composeRule.onNodeWithText("更新记录").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(
                    hasAnyAncestor(isDialog()) and SemanticsMatcher("HasReleaseNotesText") { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                            .any { it.text.isNotBlank() && it.text != "更新记录" && it.text != "关闭" }
                    },
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("关闭").performClick()

            // Open Error Logs View dialog (verifies crash log file read + sanitization)
            composeRule.onNodeWithText("查看").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(
                    SemanticsMatcher("ErrorLogText") { node ->
                        val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        texts.any { it.text.contains("crash-20261126-190000-000.log") }
                    },
                ).fetchSemanticsNodes().isNotEmpty()
            }
            val dialogLogsText = composeRule.onAllNodes(
                hasAnyAncestor(isDialog()) and SemanticsMatcher("HasSanitizedLog") { node ->
                    val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    texts.any { it.text.contains("crash-20261126-190000-000.log") }
                },
            ).onFirst().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }

            assertTrue("Sanitized marker [REDACTED] missing in error log view", dialogLogsText.contains("[REDACTED]"))
            assertFalse("Raw auth token leaked in error log view!", dialogLogsText.contains("TopSecretToken123"))
            assertFalse("Raw password leaked in error log view!", dialogLogsText.contains("PlainSecret999"))
            assertFalse("Raw formhash leaked in error log view!", dialogLogsText.contains("deadbeef"))
            assertFalse("Raw uid leaked in error log view!", dialogLogsText.contains("887766"))
            assertFalse("Raw IP leaked in error log view!", dialogLogsText.contains("203.0.113.88"))
        }

        assertTrue("Offline test attempted external host request: $externalHostViolations", externalHostViolations.isEmpty())
    }

    private fun gbkHtmlResponse(html: String): MockResponse {
        val gbk = Charset.forName("GB18030")
        return MockResponse.Builder()
            .code(200)
            .headers(okhttp3.Headers.headersOf("Content-Type", "text/html; charset=gbk"))
            .body(Buffer().write(html.toByteArray(gbk)))
            .build()
    }

    private fun createSolidPngBytes(): ByteArray {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.BLUE
        g.fillRect(0, 0, 64, 64)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun buildForumListHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk"><title>Discovery</title></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <div id="wrap">
          <div class="pages"><strong>1</strong><a href="forumdisplay.php?fid=2&page=2">2</a></div>
          <table id="threadlist">
            <tbody id="normalthread_90001">
              <tr>
                <td class="folder"><img src="images/default/folder_new.gif"/></td>
                <td class="icon"></td>
                <th class="subject"><span><a href="viewthread.php?tid=90001">M7-2 独立全链路探针帖子</a></span></th>
                <td class="author"><cite><a href="space.php?uid=20001">AuthorOne</a></cite><em>2026-11-26</em></td>
                <td class="nums"><strong>3</strong><em>120</em></td>
                <td class="lastpost"><cite><a href="space.php?uid=20001">AuthorOne</a></cite><em><a href="redirect.php?tid=90001">2026-11-26 10:00</a></em></td>
              </tr>
            </tbody>
          </table>
        </div>
        </body>
        </html>
    """.trimIndent()

    private fun buildThreadDetailHtml(forumBaseUrl: String, imgUrl: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk"><title>M7-2 独立全链路探针帖子</title></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <div id="nav"><a href="index.php">4D4Y</a> » <a href="forumdisplay.php?fid=2">Discovery</a> » M7-2 独立全链路探针帖子</div>
        <div id="wrap">
          <input type="hidden" name="formhash" id="formhash" value="1234abcd" />
          <div class="forumcontrol"><div class="pages"><strong>1</strong></div></div>
          <div id="postlist">
            <div id="post_70001">
              <table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=20001">AuthorOne</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a href="#"><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 10:00</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage firstpost"><div class="t_msgfontfix"><table><tbody><tr><td id="postmessage_70001" class="t_msgfont">
                    正文第一段 <img src="images/smilies/default/smile.gif"/> <img src="images/smilies/coolmonkey/01.gif"/> <img src="images/smilies/grapeman/01.gif"/> <img src="attachments/day_140621/1406211752793e731a4fec8f7b.png"/> <br/><img src="$imgUrl" width="120" height="80"/>
                  </td></tr></tbody></table></div></div></div>
                </td>
              </tr></tbody></table>
            </div>
          </div>
        </div>
        </body>
        </html>
    """.trimIndent()

    private fun buildSmsListHtml(): String = """
        <!DOCTYPE html>
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <ul class="pm_list"></ul>
        </body></html>
    """.trimIndent()

    private fun buildNotifyListHtml(): String = """
        <!DOCTYPE html>
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <ul class="feed"></ul>
        </body></html>
    """.trimIndent()

    private fun buildSimpleListHtml(): String = """
        <!DOCTYPE html>
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <table class="datatable"><tbody></tbody></table>
        </body></html>
    """.trimIndent()

    private fun buildSearchHtml(): String = """
        <!DOCTYPE html>
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <table class="datatable"><tbody></tbody></table>
        </body></html>
    """.trimIndent()

    private fun buildPostFormHtml(): String = """
        <!DOCTYPE html>
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=10001" class="noborder">ProbeUser</a></cite></div>
        <form id="postform"><input type="hidden" name="formhash" value="1234abcd" /></form>
        </body></html>
    """.trimIndent()
}
