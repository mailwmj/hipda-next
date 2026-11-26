package net.jejer.hipda.verifysmoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained loopback-only (`127.0.0.1`) offline Discuz! 7.2 GBK HTTP fixture server
 * for V-M7-4b full-screen release APK walkthrough & WorkManager polling verification.
 * Never makes any external network call or contacts `4d4y.com`.
 */
internal class OfflineForumFixtureServer : Closeable {

    private val running = AtomicBoolean(false)
    private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "verify-smoke-http").apply { isDaemon = true }
    }

    val recordedRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val port: Int
        get() = serverSocket.localPort

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket.localPort}"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            while (running.get() && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (!running.get()) break else continue
                }
                executor.execute { handleClient(client) }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { conn ->
            conn.soTimeout = 5_000
            val input = BufferedInputStream(conn.getInputStream())
            val requestLine = readLineAscii(input) ?: return
            val headersMap = LinkedHashMap<String, String>()
            var contentLength = 0
            while (true) {
                val headerLine = readLineAscii(input) ?: break
                if (headerLine.isEmpty()) break
                val colon = headerLine.indexOf(':')
                if (colon > 0) {
                    val key = headerLine.substring(0, colon).trim().lowercase()
                    val value = headerLine.substring(colon + 1).trim()
                    headersMap[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    }
                }
            }
            val bodyBytesRead = if (contentLength > 0) {
                val out = ByteArrayOutputStream(contentLength)
                var remaining = contentLength
                val buf = ByteArray(1024)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(remaining, buf.size))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.toByteArray()
            } else {
                ByteArray(0)
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val rawTarget = parts.getOrNull(1).orEmpty()
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")
            val cookieHeader = headersMap["cookie"].orEmpty()
            val session = resolveSession(cookieHeader)
            recordedRequests += "$method $rawTarget"
            Log.i(TAG, "Fixture HTTP $method $rawTarget (session=${session?.second ?: "guest"})")

            var extraHeader = ""
            val (contentType, bodyBytes) = when {
                path.endsWith("logging.php") && method.equals("POST", ignoreCase = true) -> {
                    val formBody = String(bodyBytesRead, GBK)
                    val postedUser = queryParam(formBody, "username")
                        ?.let { runCatching { URLDecoder.decode(it, "GBK") }.getOrNull() }
                        ?.trim()
                        ?.ifEmpty { null }
                        ?: TEST_USERNAME
                    val tokenUser = if (postedUser == SECOND_USERNAME) SECOND_USERNAME else TEST_USERNAME
                    extraHeader = "Set-Cookie: $AUTH_COOKIE_NAME=${AUTH_COOKIE_PREFIX}$tokenUser; Path=/\r\n"
                    val xml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，$tokenUser]]></root>"""
                    "text/xml; charset=gbk" to xml.toByteArray(GBK)
                }

                path.endsWith("logging.php") -> {
                    val html = if (session != null) {
                        buildLoggedInVerifyHtml(session)
                    } else {
                        buildLoginFormHtml()
                    }
                    "text/html; charset=gbk" to html.toByteArray(GBK)
                }

                path.endsWith("forumdisplay.php") -> {
                    val fid = queryParam(query, "fid")?.toIntOrNull() ?: 2
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildForumListHtml(fid, page, session).toByteArray(GBK)
                }

                path.endsWith("viewthread.php") -> {
                    val tid = queryParam(query, "tid").orEmpty().ifBlank { FIRST_THREAD_ID }
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildThreadDetailHtml(tid, page, session, baseUrl).toByteArray(GBK)
                }

                path.endsWith("post.php") && method.equals("POST", ignoreCase = true) -> {
                    "text/html; charset=gbk" to buildThreadDetailHtml(FIRST_THREAD_ID, 1, session, baseUrl).toByteArray(GBK)
                }

                path.endsWith("post.php") -> {
                    "text/html; charset=gbk" to buildPostFormHtml(session).toByteArray(GBK)
                }

                path.endsWith("pm.php") && query.contains("daterange=5") -> {
                    val peerUid = queryParam(query, "uid").orEmpty().ifBlank { PEER_UID }
                    "text/html; charset=gbk" to buildSmsDetailHtml(session, peerUid, baseUrl).toByteArray(GBK)
                }

                path.endsWith("pm.php") -> {
                    val unreadOnly = query.contains("filter=newpm")
                    "text/html; charset=gbk" to buildSmsListHtml(session, baseUrl, includePromptCounts = unreadOnly).toByteArray(GBK)
                }

                path.endsWith("notice.php") -> {
                    "text/html; charset=gbk" to buildNotifyListHtml(session).toByteArray(GBK)
                }

                path.endsWith("search.php") -> {
                    "text/html; charset=gbk" to buildSearchHtml(session).toByteArray(GBK)
                }

                path.endsWith("my.php") -> {
                    val item = queryParam(query, "item").orEmpty()
                    val html = when (item) {
                        "attention" -> buildAttentionHtml(session)
                        "threads" -> buildMyThreadsHtml(session)
                        "posts" -> buildMyRepliesHtml(session)
                        else -> buildFavoritesHtml(session)
                    }
                    "text/html; charset=gbk" to html.toByteArray(GBK)
                }

                path.contains("post-photo") -> {
                    "image/png" to SAMPLE_POST_PHOTO_PNG
                }

                path.contains("avatar") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".gif") -> {
                    "image/png" to SAMPLE_AVATAR_PNG
                }

                else -> {
                    "text/html; charset=gbk" to buildForumListHtml(2, 1, session).toByteArray(GBK)
                }
            }

            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append(extraHeader)
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val output = conn.getOutputStream()
            output.write(headers)
            output.write(bodyBytes)
            output.flush()
        }
    }

    private fun resolveSession(cookieHeader: String): Pair<String, String>? {
        val rawCookie = cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$AUTH_COOKIE_NAME=") }
            ?.substringAfter("$AUTH_COOKIE_NAME=")
            ?: return null
        return when {
            rawCookie.contains(SECOND_USERNAME) -> SECOND_UID to SECOND_USERNAME
            rawCookie.contains(TEST_USERNAME) -> TEST_UID to TEST_USERNAME
            else -> TEST_UID to TEST_USERNAME
        }
    }

    private fun readLineAscii(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == key) return pair.substring(idx + 1)
        }
        return null
    }

    companion object {
        private const val TAG = "VerifySmoke"
        private const val AUTH_COOKIE_NAME = "cdb_auth"
        private const val AUTH_COOKIE_PREFIX = "probe_session_"
        private val GBK: Charset = Charset.forName("GBK")

        const val TEST_UID: String = "10001"
        const val TEST_USERNAME: String = "ProbeTester"
        const val SECOND_UID: String = "10002"
        const val SECOND_USERNAME: String = "ProbeTester2"
        const val PEER_UID: String = "20002"
        const val PEER_USERNAME: String = "FriendOne"

        const val FIRST_THREAD_ID: String = "910001"
        const val THREAD_TITLE_PREFIX: String = "Probe 主题 #"
        const val FIRST_THREAD_TITLE: String = "Probe 主题 #1 离线冒烟与看图表情测试"
        const val FIRST_FLOOR_MARKER: String = "Probe 楼层内容 #1"
        const val PAGE_2_FLOOR_MARKER: String = "第 2 页楼层 #16"
        const val PAGE_3_FLOOR_MARKER: String = "第 3 页楼层 #31"
        const val SMS_SUMMARY_TEXT: String = "离线测试短消息摘要"
        const val SMS_BODY_TEXT: String = "离线测试短消息对话正文"
        const val NOTIFY_TEXT: String = "离线测试回复提醒"
        const val SEARCH_RESULT_TITLE: String = "离线搜索结果主题 #1"
        const val FAVORITE_THREAD_TITLE: String = "离线收藏示例主题 #1"
        const val ATTENTION_THREAD_TITLE: String = "离线关注示例主题 #2"

        private val SAMPLE_POST_PHOTO_PNG: ByteArray by lazy { createSamplePng(240, 160, Color.rgb(42, 120, 210), Color.rgb(240, 180, 70)) }
        private val SAMPLE_AVATAR_PNG: ByteArray by lazy { createSamplePng(48, 48, Color.rgb(65, 160, 120), Color.rgb(245, 245, 245)) }

        private fun createSamplePng(width: Int, height: Int, bgColor: Int, accentColor: Int): ByteArray {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(bgColor)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
            canvas.drawRect(width * 0.15f, height * 0.2f, width * 0.85f, height * 0.8f, paint)
            return ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bmp.recycle()
                bos.toByteArray()
            }
        }

        private fun umenuHtml(session: Pair<String, String>?, includePromptCounts: Boolean = false): String = buildString {
            if (session != null) {
                append(
                    """
                    <div id="umenu">
                      <cite><a class="noborder" href="space.php?uid=${session.first}">${session.second}</a></cite>
                      <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
                    </div>
                    """.trimIndent(),
                )
            } else {
                append(
                    """
                    <div id="umenu">
                      <a href="logging.php?action=login">登录</a>
                    </div>
                    """.trimIndent(),
                )
            }
            if (includePromptCounts) {
                append(
                    """
                    <div class="promptcontent">
                      <a id="prompt_pm" href="pm.php?filter=newpm">私人消息 (1)</a>
                      <a id="prompt_threads" href="notice.php">帖子消息 (1)</a>
                    </div>
                    """.trimIndent(),
                )
            }
        }

        private fun buildLoginFormHtml(): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>登录</title></head><body>
            ${umenuHtml(null)}
            <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
              <input type="hidden" name="formhash" value="a1b2c3d4" />
            </form>
            </body></html>
        """.trimIndent()

        private fun buildLoggedInVerifyHtml(session: Pair<String, String>): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>登录成功</title></head><body>
            ${umenuHtml(session)}
            </body></html>
        """.trimIndent()

        fun buildForumListHtml(fid: Int, page: Int, session: Pair<String, String>?): String = buildString(16_384) {
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>Discovery</title></head><body>")
            append(umenuHtml(session))
            append("<div id=\"wrap\"><div class=\"pages\">")
            if (page == 1) {
                append("<strong>1</strong><a href=\"forumdisplay.php?fid=$fid&amp;page=2\">2</a>")
            } else {
                append("<a href=\"forumdisplay.php?fid=$fid&amp;page=1\">1</a><strong>$page</strong>")
            }
            append("</div><table class=\"datatable\">")
            val startIndex = (page - 1) * 25 + 1
            for (offset in 0 until 25) {
                val index = startIndex + offset
                val tid = (910_000 + index).toString()
                val title = if (index == 1 && fid == 2) FIRST_THREAD_TITLE else "Probe 主题 #$index 版块 $fid 离线列表"
                val uid = 20_000 + index
                val folderIcon = if (index == 2) "images/default/folder_new.gif" else "images/default/folder_common.gif"
                append(
                    """
                    <tbody id="normalthread_$tid">
                      <tr>
                        <td class="folder"><img src="$folderIcon" /></td>
                        <td class="icon"></td>
                        <th class="subject"><span id="thread_$tid"><a href="viewthread.php?tid=$tid">$title</a></span></th>
                        <td class="author"><cite><a href="space.php?uid=$uid">ProbeAuthor$index</a></cite><em>2026-11-26</em></td>
                        <td class="nums"><strong>${8 + index}</strong>/<em>${90 + index * 5}</em></td>
                        <td class="lastpost"><em><a href="redirect.php?tid=$tid&amp;goto=lastpost">2026-11-26 12:${(index % 59).toString().padStart(2, '0')}</a></em><cite>ReplyUser$index</cite></td>
                      </tr>
                    </tbody>
                    """.trimIndent(),
                )
            }
            append("</table></div></body></html>")
        }

        fun buildThreadDetailHtml(tid: String, page: Int, session: Pair<String, String>?, baseUrl: String): String = buildString(24_576) {
            val safePage = page.coerceIn(1, 3)
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>$FIRST_THREAD_TITLE</title></head><body>")
            append(umenuHtml(session))
            append("<div id=\"wrap\">")
            append("<input type=\"hidden\" name=\"formhash\" id=\"formhash\" value=\"a1b2c3d4\" />")
            append("<div id=\"nav\"><a href=\"index.php\">HiPDA</a> &raquo; <a href=\"forumdisplay.php?fid=2\">Discovery</a> &raquo; $FIRST_THREAD_TITLE</div>")
            append("<div class=\"forumcontrol\"><div class=\"pages\">")
            for (p in 1..3) {
                if (p == safePage) {
                    append("<strong>$p</strong>")
                } else {
                    append("<a href=\"viewthread.php?tid=$tid&amp;page=$p\">$p</a>")
                }
            }
            append("</div></div>")
            append("<div id=\"postlist\">")
            val startFloor = (safePage - 1) * 15 + 1
            for (offset in 0 until 15) {
                val floor = startFloor + offset
                val pid = (7_100_000 + floor).toString()
                val uid = if (floor == 1) "20001" else (30_000 + floor).toString()
                val author = if (floor == 1) "ProbeAuthor1" else "ProbeReplier$floor"
                val bodyHtml = when {
                    floor == 1 -> """
                        $FIRST_FLOOR_MARKER — 内联表情（默认/酷猴/呆呆）与客户端角标：
                        <img src="images/smilies/default/smile.gif" smilieid="1" />
                        <img src="images/smilies/coolmonkey/01.gif" smilieid="2" />
                        <img src="images/smilies/grapeman/01.gif" smilieid="3" />
                        <img src="attachments/day_140621/1406211752793e731a4fec8f7b.png" />
                        <br />
                        <img src="$baseUrl/forum/post-photo-1.png" />
                    """.trimIndent()
                    floor == 16 -> "$PAGE_2_FLOOR_MARKER — 跳页第二页首楼验证文本，含内联表情 <img src=\"images/smilies/default/lol.gif\" smilieid=\"16\" />"
                    floor == 31 -> "$PAGE_3_FLOOR_MARKER — 末页验证文本，含内联表情 <img src=\"images/smilies/coolmonkey/04.gif\" smilieid=\"31\" />"
                    else -> {
                        val quote = if (floor % 3 == 0) {
                            "<div class=\"quote\"><blockquote><font size=\"2\"><font color=\"#999999\">ProbeAuthor1 发表于 2026-11-26 10:00</font></font><br />引用内容用于滚动与排版测高。</blockquote></div>"
                        } else {
                            ""
                        }
                        "${quote}第 $safePage 页楼层 #$floor — 滚动与 R8 混淆安全渲染验证。"
                    }
                }
                append(
                    """
                    <div id="post_$pid">
                      <table><tbody><tr>
                        <td class="postauthor"><div class="postinfo"><a href="space.php?uid=$uid">$author</a></div></td>
                        <td class="postcontent">
                          <div class="postinfo">
                            <strong><a href="redirect.php?goto=findpost&amp;ptid=$tid&amp;pid=$pid"><em>$floor</em>#</a></strong>
                            <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 10:${(floor % 59).toString().padStart(2, '0')}</em></div></div>
                          </div>
                          <div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">
                            $bodyHtml
                          </td></tr></tbody></table></div></div></div>
                        </td>
                      </tr></tbody></table>
                    </div>
                    """.trimIndent(),
                )
            }
            append("</div></div></body></html>")
        }

        private fun buildPostFormHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>发帖</title></head><body>
            ${umenuHtml(session)}
            <form id="postform">
              <input type="hidden" name="formhash" value="a1b2c3d4" />
              <select name="typeid" id="typeid"><option value="0" selected="selected">默认</option></select>
              <textarea name="message"></textarea>
            </form>
            </body></html>
        """.trimIndent()

        private fun buildSmsListHtml(session: Pair<String, String>?, baseUrl: String, includePromptCounts: Boolean): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>短消息</title></head><body>
            ${umenuHtml(session, includePromptCounts)}
            <ul class="pm_list">
              <li class="s_clear">
                <a class="avatar" href="space.php?uid=$PEER_UID"><img src="$baseUrl/forum/avatar-$PEER_UID.png" /></a>
                <p class="cite"><cite><a href="space.php?uid=$PEER_UID">$PEER_USERNAME</a></cite> <img src="images/default/notice_newpm.gif" /> 2026-11-26 11:00</p>
                <div class="summary">$SMS_SUMMARY_TEXT</div>
              </li>
            </ul>
            </body></html>
        """.trimIndent()

        private fun buildSmsDetailHtml(session: Pair<String, String>?, peerUid: String, baseUrl: String): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>与 $PEER_USERNAME 对话</title></head><body>
            ${umenuHtml(session)}
            <input type="hidden" id="formhash" name="formhash" value="a1b2c3d4" />
            <ul class="pm_list">
              <li class="s_clear">
                <a class="avatar" href="space.php?uid=$peerUid"><img src="$baseUrl/forum/avatar-$peerUid.png" /></a>
                <p class="cite"><cite><a href="space.php?uid=$peerUid">$PEER_USERNAME</a></cite> 2026-11-26 11:00</p>
                <div class="summary">$SMS_BODY_TEXT</div>
              </li>
            </ul>
            </body></html>
        """.trimIndent()

        private fun buildNotifyListHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>提醒</title></head><body>
            ${umenuHtml(session)}
            <ul class="feed">
              <li>
                <div class="f_quote">
                  <a href="space.php?uid=$PEER_UID">$PEER_USERNAME</a> 回复了您的帖子 <a href="viewthread.php?tid=$FIRST_THREAD_ID">$FIRST_THREAD_TITLE</a> <a href="redirect.php?goto=findpost&amp;ptid=$FIRST_THREAD_ID&amp;pid=7100001">查看</a>
                  <em>2026-11-26 11:05</em>
                  <dl class="summary"><dd>原帖内容摘要</dd><dd>$NOTIFY_TEXT</dd></dl>
                </div>
              </li>
            </ul>
            </body></html>
        """.trimIndent()

        private fun buildSearchHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>搜索</title></head><body>
            ${umenuHtml(session)}
            <div id="wrap">
              <div class="pages"><strong>1</strong></div>
              <table class="datatable">
                <tbody id="normalthread_$FIRST_THREAD_ID">
                  <tr>
                    <th class="subject"><a href="viewthread.php?tid=$FIRST_THREAD_ID">$SEARCH_RESULT_TITLE</a></th>
                    <td class="author"><cite><a href="space.php?uid=20001">ProbeAuthor1</a></cite><em>2026-11-26</em></td>
                    <td class="forum">Discovery</td>
                  </tr>
                </tbody>
              </table>
            </div>
            </body></html>
        """.trimIndent()

        private fun buildFavoritesHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>收藏</title></head><body>
            ${umenuHtml(session)}
            <div id="wrap">
              <div class="pages"><strong>1</strong></div>
              <table class="datatable"><tbody>
                <tr>
                  <td><input type="checkbox" class="checkbox" name="delete[]" value="$FIRST_THREAD_ID" /></td>
                  <th><a href="viewthread.php?tid=$FIRST_THREAD_ID">$FAVORITE_THREAD_TITLE</a></th>
                  <td class="forum">Discovery</td>
                  <td class="lastpost">2026-11-26 10:00</td>
                </tr>
              </tbody></table>
            </div>
            </body></html>
        """.trimIndent()

        private fun buildAttentionHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>关注</title></head><body>
            ${umenuHtml(session)}
            <div id="wrap">
              <div class="pages"><strong>1</strong></div>
              <table class="datatable"><tbody>
                <tr>
                  <td><input type="checkbox" class="checkbox" name="delete[]" value="910002" /></td>
                  <th><a href="viewthread.php?tid=910002">$ATTENTION_THREAD_TITLE</a></th>
                  <td class="forum">Discovery</td>
                  <td class="lastpost">2026-11-26 10:05</td>
                </tr>
              </tbody></table>
            </div>
            </body></html>
        """.trimIndent()

        private fun buildMyThreadsHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>我的帖子</title></head><body>
            ${umenuHtml(session)}
            <div class="pages_btns"><div class="pages"><strong>1</strong></div></div>
            <table class="datatable">
              <tr><th>标题</th><td class="forum">版块</td><td class="lastpost">最后发表</td></tr>
              <tr><th><a href="viewthread.php?tid=$FIRST_THREAD_ID">$FIRST_THREAD_TITLE</a></th><td class="forum">Discovery</td><td class="lastpost">2026-11-26 10:00</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        private fun buildMyRepliesHtml(session: Pair<String, String>?): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>我的回复</title></head><body>
            ${umenuHtml(session)}
            <div class="pages_btns"><div class="pages"><strong>1</strong></div></div>
            <table class="datatable">
              <tr><th>标题</th><td class="forum">版块</td><td class="lastpost">最后发表</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
