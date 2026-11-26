package net.jejer.hipda.baselineprofile

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
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only (`127.0.0.1`) HTTP fixture server for verifying the real `release` APK across
 * login, forum list, thread detail (inline emojis + post images + `win` badge), image viewer,
 * quick reply / full editor, and cold-start timing comparisons without contacting the live forum.
 */
internal class ProbeOfflineForumServer : Closeable {

    private val running = AtomicBoolean(false)
    private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "probe-offline-http").apply { isDaemon = true }
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
            if (contentLength > 0) {
                var remaining = contentLength
                val buf = ByteArray(1024)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(remaining, buf.size))
                    if (n <= 0) break
                    remaining -= n
                }
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val rawTarget = parts.getOrNull(1).orEmpty()
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")
            val cookieHeader = headersMap["cookie"].orEmpty()
            val hasSession = cookieHeader.contains(AUTH_COOKIE_NAME)
            recordedRequests += "$method $rawTarget"
            Log.i(TAG, "Probe HTTP $method $rawTarget (cookie=$hasSession)")

            var extraHeader = ""
            val (contentType, bodyBytes) = when {
                path.endsWith("logging.php") && method.equals("POST", ignoreCase = true) -> {
                    extraHeader = "Set-Cookie: $AUTH_COOKIE_NAME=probe_session; Path=/\r\n"
                    val xml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，$TEST_USERNAME]]></root>"""
                    "text/xml; charset=gbk" to xml.toByteArray(GBK)
                }
                path.endsWith("logging.php") -> {
                    val html = if (hasSession) {
                        buildLoggedInVerifyHtml()
                    } else {
                        buildLoginFormHtml()
                    }
                    "text/html; charset=gbk" to html.toByteArray(GBK)
                }
                path.endsWith("forumdisplay.php") -> {
                    val fid = queryParam(query, "fid")?.toIntOrNull() ?: 2
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildForumListHtml(fid, page, hasSession).toByteArray(GBK)
                }
                path.endsWith("viewthread.php") -> {
                    val tid = queryParam(query, "tid").orEmpty().ifBlank { FIRST_THREAD_ID }
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildThreadDetailHtml(tid, page, hasSession, baseUrl).toByteArray(GBK)
                }
                path.contains("post-photo") -> {
                    "image/png" to SAMPLE_POST_PHOTO_PNG
                }
                path.contains("avatar") || path.endsWith(".png") || path.endsWith(".jpg") -> {
                    "image/png" to SAMPLE_AVATAR_PNG
                }
                else -> {
                    "text/html; charset=gbk" to buildForumListHtml(2, 1, hasSession).toByteArray(GBK)
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
        private const val TAG = "M74aProbe"
        private const val AUTH_COOKIE_NAME = "cdb_auth"
        private val GBK: Charset = Charset.forName("GBK")

        const val TEST_USERNAME: String = "ProbeTester"
        const val FIRST_THREAD_ID: String = "910001"
        const val THREAD_TITLE_PREFIX: String = "Probe 主题 #"
        const val FIRST_THREAD_TITLE: String = "Probe 主题 #1 离线冒烟与看图表情测试"
        const val FIRST_FLOOR_MARKER: String = "Probe 楼层内容 #1"

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

        private fun umenuHtml(loggedIn: Boolean): String = if (loggedIn) {
            """
            <div id="umenu">
              <cite><a class="noborder" href="space.php?uid=10001">$TEST_USERNAME</a></cite>
              <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
            </div>
            """.trimIndent()
        } else {
            """
            <div id="umenu">
              <a href="logging.php?action=login">登录</a>
            </div>
            """.trimIndent()
        }

        private fun buildLoginFormHtml(): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>登录</title></head><body>
            ${umenuHtml(loggedIn = false)}
            <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
              <input type="hidden" name="formhash" value="a1b2c3d4" />
            </form>
            </body></html>
        """.trimIndent()

        private fun buildLoggedInVerifyHtml(): String = """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>登录成功</title></head><body>
            ${umenuHtml(loggedIn = true)}
            </body></html>
        """.trimIndent()

        fun buildForumListHtml(fid: Int, page: Int, loggedIn: Boolean): String = buildString(16_384) {
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>Discovery</title></head><body>")
            append(umenuHtml(loggedIn))
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
                val title = if (index == 1) FIRST_THREAD_TITLE else "Probe 主题 #$index 版块 $fid 离线列表"
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

        fun buildThreadDetailHtml(tid: String, page: Int, loggedIn: Boolean, baseUrl: String): String = buildString(24_576) {
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>$FIRST_THREAD_TITLE</title></head><body>")
            append(umenuHtml(loggedIn))
            append("<div id=\"wrap\">")
            append("<div id=\"nav\"><a href=\"index.php\">HiPDA</a> &raquo; <a href=\"forumdisplay.php?fid=2\">Discovery</a> &raquo; $FIRST_THREAD_TITLE</div>")
            append("<div class=\"forumcontrol\"><div class=\"pages\"><strong>1</strong></div></div>")
            append("<div id=\"postlist\">")
            for (floor in 1..12) {
                val pid = (7_100_000 + floor).toString()
                val uid = if (floor == 1) "20001" else (30_000 + floor).toString()
                val author = if (floor == 1) "ProbeAuthor1" else "ProbeReplier$floor"
                val bodyHtml = if (floor == 1) {
                    """
                    $FIRST_FLOOR_MARKER — 内联表情（默认/酷猴/呆呆）与客户端角标：
                    <img src="images/smilies/default/smile.gif" smilieid="1" />
                    <img src="images/smilies/coolmonkey/01.gif" smilieid="2" />
                    <img src="images/smilies/grapeman/01.gif" smilieid="3" />
                    <img src="attachments/day_140621/1406211752793e731a4fec8f7b.png" />
                    <br />
                    <img src="$baseUrl/forum/post-photo-1.png" />
                    """.trimIndent()
                } else {
                    val smilieSrc = when (floor % 3) {
                        0 -> "images/smilies/default/lol.gif"
                        1 -> "images/smilies/coolmonkey/04.gif"
                        else -> "images/smilies/grapeman/05.gif"
                    }
                    "Probe 楼层内容 #$floor — 滚动测试与表情渲染 <img src=\"$smilieSrc\" smilieid=\"$floor\" />"
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
    }
}
