package net.jejer.hipda.baselineprofile

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained loopback-only (`127.0.0.1`) HTTP fixture server for Baseline Profile generation.
 *
 * Serves synthetic Discuz! 7.2 GBK HTML for forum thread lists (`/forum/forumdisplay.php`),
 * thread detail pages (`/forum/viewthread.php`), and a tiny 1x1 PNG for avatar URLs so profile
 * collection exercises the real OkHttp + `:core-parser` + Coil + Room + Compose pipeline without
 * ever contacting `4d4y.com`.
 */
internal class OfflineForumFixtureServer : Closeable {

    private val running = AtomicBoolean(false)
    private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "baseline-fixture-http").apply { isDaemon = true }
    }

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
            // Drain HTTP request headers until blank line.
            while (true) {
                val headerLine = readLineAscii(input) ?: break
                if (headerLine.isEmpty()) break
            }

            val rawTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")

            val (contentType, bodyBytes) = when {
                path.endsWith("forumdisplay.php") -> {
                    val fid = queryParam(query, "fid")?.toIntOrNull() ?: 2
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildForumListHtml(fid, page).toByteArray(GBK)
                }
                path.endsWith("viewthread.php") -> {
                    val tid = queryParam(query, "tid").orEmpty().ifBlank { FIRST_THREAD_ID }
                    val page = queryParam(query, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "text/html; charset=gbk" to buildThreadDetailHtml(tid, page).toByteArray(GBK)
                }
                path.contains("avatar") || path.endsWith(".png") || path.endsWith(".jpg") -> {
                    "image/png" to TRANSPARENT_1X1_PNG
                }
                else -> {
                    "text/html; charset=gbk" to buildForumListHtml(2, 1).toByteArray(GBK)
                }
            }

            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
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
        private val GBK: Charset = Charset.forName("GBK")

        const val FIRST_THREAD_ID: String = "900001"
        const val FIRST_THREAD_TITLE: String = "Baseline Profile 主题 #1 液态玻璃与阅读体验"
        const val FIRST_FLOOR_MARKER: String = "Baseline Profile 楼层内容 #1"

        // Valid 1x1 PNG (67 bytes) served for loopback avatar requests.
        private val TRANSPARENT_1X1_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )

        private const val UMENU_HTML = """
            <div id="umenu">
              <cite><a class="noborder" href="space.php?uid=10001">ProfileBenchUser</a></cite>
              <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
            </div>
        """

        fun buildForumListHtml(fid: Int, page: Int): String = buildString(16_384) {
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>Discovery</title></head><body>")
            append(UMENU_HTML)
            append("<div id=\"wrap\">")
            append("<div class=\"pages\">")
            if (page == 1) {
                append("<strong>1</strong><a href=\"forumdisplay.php?fid=$fid&amp;page=2\">2</a>")
            } else {
                append("<a href=\"forumdisplay.php?fid=$fid&amp;page=1\">1</a><strong>$page</strong>")
            }
            append("</div>")
            append("<table class=\"datatable\">")
            val startIndex = (page - 1) * 25 + 1
            for (offset in 0 until 25) {
                val index = startIndex + offset
                val tid = (900_000 + index).toString()
                val title = if (index == 1) {
                    FIRST_THREAD_TITLE
                } else {
                    "Baseline Profile 主题 #$index 版块 $fid 滚动与列表渲染样本"
                }
                val uid = 20_000 + index
                val folderIcon = if (index == 2) "images/default/folder_new.gif" else "images/default/folder_common.gif"
                val typeTag = if (index % 3 == 0) "<em>[<a href=\"forumdisplay.php?fid=$fid&amp;filter=type&amp;typeid=1\">讨论</a>]</em>" else ""
                append(
                    """
                    <tbody id="normalthread_$tid">
                      <tr>
                        <td class="folder"><img src="$folderIcon" /></td>
                        <td class="icon"></td>
                        <th class="subject">
                          $typeTag
                          <span id="thread_$tid"><a href="viewthread.php?tid=$tid">$title</a></span>
                        </th>
                        <td class="author"><cite><a href="space.php?uid=$uid">BenchAuthor$index</a></cite><em>2026-11-26</em></td>
                        <td class="nums"><strong>${12 + index}</strong>/<em>${120 + index * 7}</em></td>
                        <td class="lastpost"><em><a href="redirect.php?tid=$tid&amp;goto=lastpost">2026-11-26 12:${(index % 59).toString().padStart(2, '0')}</a></em><cite>ReplyUser$index</cite></td>
                      </tr>
                    </tbody>
                    """.trimIndent(),
                )
            }
            append("</table></div></body></html>")
        }

        fun buildThreadDetailHtml(tid: String, page: Int): String = buildString(24_576) {
            append("<!DOCTYPE html><html><head><meta charset=\"gbk\"><title>$FIRST_THREAD_TITLE</title></head><body>")
            append(UMENU_HTML)
            append("<div id=\"wrap\">")
            append("<div id=\"nav\"><a href=\"index.php\">HiPDA</a> &raquo; <a href=\"forumdisplay.php?fid=2\">Discovery</a> &raquo; $FIRST_THREAD_TITLE</div>")
            append("<div class=\"forumcontrol\"><div class=\"pages\">")
            if (page == 1) {
                append("<strong>1</strong><a href=\"viewthread.php?tid=$tid&amp;page=2\">2</a>")
            } else {
                append("<a href=\"viewthread.php?tid=$tid&amp;page=1\">1</a><strong>$page</strong>")
            }
            append("</div></div>")
            append("<div id=\"postlist\">")
            val startFloor = (page - 1) * 18 + 1
            for (offset in 0 until 18) {
                val floor = startFloor + offset
                val pid = (7_000_000 + floor).toString()
                val uid = if (floor == 1) "20001" else (30_000 + floor).toString()
                val author = if (floor == 1) "BenchAuthor1" else "BenchReplier$floor"
                val quoteHtml = if (floor > 1 && floor % 3 == 0) {
                    """
                    <div class="quote"><blockquote><font size="2"><font color="#999999">BenchAuthor1 发表于 2026-11-26 10:00</font></font><br />这是用于触发引用展开与排版测量的离线样本段落。</blockquote></div>
                    """.trimIndent()
                } else {
                    ""
                }
                val smilieSrc = when (floor % 3) {
                    0 -> "images/smilies/default/smile.gif"
                    1 -> "images/smilies/coolmonkey/01.gif"
                    else -> "images/smilies/grapeman/01.gif"
                }
                val bodyMarker = if (floor == 1) {
                    FIRST_FLOOR_MARKER
                } else {
                    "Baseline Profile 楼层内容 #$floor"
                }
                append(
                    """
                    <div id="post_$pid">
                      <table><tbody><tr>
                        <td class="postauthor">
                          <div class="postinfo"><a href="space.php?uid=$uid">$author</a></div>
                        </td>
                        <td class="postcontent">
                          <div class="postinfo">
                            <strong><a href="redirect.php?goto=findpost&amp;ptid=$tid&amp;pid=$pid"><em>$floor</em>#</a></strong>
                            <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 10:${(floor % 59).toString().padStart(2, '0')}</em></div></div>
                          </div>
                          <div class="defaultpost">
                            <div class="postmessage">
                              <div class="t_msgfontfix">
                                <table><tbody><tr><td class="t_msgfont">
                                  $quoteHtml
                                  $bodyMarker — 这是一段离线生成的 Discuz! 帖子正文，用于预热 Compose 文本排版、行内表情映射 <img src="$smilieSrc" smilieid="1" />、液态玻璃顶栏与底部三段工具条滚动路径。<br />
                                  第二段补充文本，确保楼层卡片具备真实多行高度，完整覆盖 LazyColumn 滚动回收、阅读位置记录与楼层渲染热点路径。
                                </td></tr></tbody></table>
                              </div>
                            </div>
                          </div>
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
