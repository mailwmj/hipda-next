package net.jejer.hipda.parser.sanitize

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlSanitizerTest {

    private val identity = Identity(uid = "54321", username = "RealUser")

    @Test
    fun rule1ReplacesCurrentUserUidAndUsernameAcrossTextAttributesUrlsAndScripts() {
        val chineseIdentity = Identity(uid = "54321", username = "张三")
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>张三 的个人空间 - HiPDA</title>
              <script>
                var currentUser = '张三';
                var currentUid = '54321';
                var encodedUser = '%D5%C5%C8%FD';
              </script>
            </head>
            <body>
              <div id="post_54321" class="postbox">
                <a href="space.php?uid=54321&amp;username=%D5%C5%C8%FD" title="访问 张三 的空间">张三</a>
                <a href="search.php?srchuid=54321&amp;srchuname=%E5%BC%A0%E4%B8%89">搜索</a>
                <a href="viewthread.php?tid=9543210">无关长数字帖子</a>
                <img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/05/43/21_avatar_middle.jpg" alt="张三" />
                <p>用户详情 (UID: 54321)</p>
                <input type="hidden" name="author" value="张三" />
              </div>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.USER_INFO, chineseIdentity)
        val doc = Jsoup.parse(sanitized)

        assertFalse(sanitized.contains("张三"))
        assertFalse(sanitized.contains("'54321'"))
        assertFalse(sanitized.contains("uid=54321"))
        assertFalse(sanitized.contains("UID: 54321"))
        assertFalse(sanitized.contains("000/05/43/21"))
        assertFalse(sanitized.contains("%D5%C5%C8%FD", ignoreCase = true))
        assertFalse(sanitized.contains("%E5%BC%A0%E4%B8%89", ignoreCase = true))

        assertTrue(doc.title().contains("HiUser"))
        assertEquals("post_54321", doc.selectFirst("div.postbox")?.id(), "DOM id must not be modified")
        assertEquals("space.php?uid=10000&username=HiUser", doc.selectFirst("a[title]")?.attr("href"))
        assertEquals("访问 HiUser 的空间", doc.selectFirst("a[title]")?.attr("title"))
        assertEquals("HiUser", doc.selectFirst("a[title]")?.text())
        assertEquals("search.php?srchuid=10000&srchuname=HiUser", doc.select("a")[1].attr("href"))
        assertEquals("viewthread.php?tid=9543210", doc.select("a")[2].attr("href"))
        assertTrue(doc.selectFirst("img")!!.attr("src").contains("000/01/00/00_avatar_middle.jpg"))
        assertEquals("HiUser", doc.selectFirst("img")?.attr("alt"))
        assertEquals("用户详情 (UID: 10000)", doc.selectFirst("p")?.text())
        assertEquals("HiUser", doc.selectFirst("input[name=author]")?.attr("value"))
        val script = doc.selectFirst("script")!!.data()
        assertTrue(script.contains("var currentUser = 'HiUser';"))
        assertTrue(script.contains("var currentUid = '10000';"))
        assertTrue(script.contains("var encodedUser = 'HiUser';"))
    }

    @Test
    fun rule2ReplacesSensitiveTokensWithSameLengthZerosPreservingFormat() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>
                var formhash = 'a1b2c3d4';
                var swfConfig = {
                  post_params: {"uid": "54321", "hash": "0123456789abcdef0123456789abcdef"},
                  sid: 'XyZ789',
                  auth: "secretAuthToken",
                  seccode: 'AB12'
                };
                location.hash = '#reply';
              </script>
            </head>
            <body>
              <form action="post.php?action=reply&amp;formhash=a1b2c3d4&amp;sid=XyZ789" method="post">
                <input type="hidden" name="formhash" id="formhash" value="a1b2c3d4" />
                <input type="hidden" name="m_formhash" value="deadbeef" />
                <input type="hidden" name="hash" value="0123456789abcdef0123456789abcdef" />
                <input type="hidden" name="sid" value="XyZ789" />
                <input type="hidden" name="auth" value="tok123456" />
                <input type="text" name="seccode" value="AB12" />
              </form>
              <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
              <a href="attachment.php?aid=99&amp;auth=tok123456&amp;seccode=AB12&amp;hash=11223344">附件</a>
              <!-- Cookie: cdb_auth=secretCookieAuth; cdb_sid=secretSid; cf_clearance=cf_tok_123 -->
            </body>
            </html>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.FORMHASH })
        assertTrue(rawLeaks.any { it.type == LeakType.COOKIE_VALUE })

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.POST_FORM, identity)
        val doc = Jsoup.parse(sanitized)

        assertEquals("00000000", doc.selectFirst("input[name=formhash]")?.attr("value"))
        assertEquals("00000000", doc.selectFirst("input[name=m_formhash]")?.attr("value"))
        assertEquals("0".repeat(32), doc.selectFirst("input[name=hash]")?.attr("value"))
        assertEquals("000000", doc.selectFirst("input[name=sid]")?.attr("value"))
        assertEquals("000000000", doc.selectFirst("input[name=auth]")?.attr("value"))
        assertEquals("0000", doc.selectFirst("input[name=seccode]")?.attr("value"))
        assertEquals(
            "post.php?action=reply&formhash=00000000&sid=000000",
            doc.selectFirst("form")?.attr("action"),
        )
        assertEquals(
            "logging.php?action=logout&formhash=00000000",
            doc.select("a")[0].attr("href"),
        )
        assertEquals(
            "attachment.php?aid=99&auth=000000000&seccode=0000&hash=00000000",
            doc.select("a")[1].attr("href"),
        )

        val script = doc.selectFirst("script")!!.data()
        assertTrue(script.contains("var formhash = '00000000';"))
        assertTrue(script.contains("\"hash\": \"${"0".repeat(32)}\""))
        assertTrue(script.contains("sid: '000000'"))
        assertTrue(script.contains("auth: \"${"0".repeat("secretAuthToken".length)}\""))
        assertTrue(script.contains("seccode: '0000'"))
        assertTrue(script.contains("location.hash = '#reply';"), "location.hash must stay intact")

        assertFalse(sanitized.contains("secretCookieAuth"))
        assertFalse(sanitized.contains("secretSid"))
        assertFalse(sanitized.contains("cf_tok_123"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty(), "Sanitized HTML must have no leaks")
    }

    @Test
    fun rule3ReplacesDiscuzUidInScriptsPreservingQuotesAndFormat() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <script type="text/javascript">
                var discuz_uid = '54321', IMGDIR = 'images/default';
              </script>
              <script type="text/javascript">
                var discuz_uid = 54321, styleid = '1';
                var other = "ok";
              </script>
              <script type="text/javascript">
                discuz_uid = "98765";
              </script>
            </head>
            <body><p>ok</p></body>
            </html>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.DISCUZ_UID })

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_LIST, identity)
        val scripts = Jsoup.parse(sanitized).select("script")

        assertTrue(scripts[0].data().contains("var discuz_uid = '10000', IMGDIR = 'images/default';"))
        assertTrue(scripts[1].data().contains("var discuz_uid = 10000, styleid = '1';"))
        assertTrue(scripts[2].data().contains("discuz_uid = \"10000\";"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun rule4SanitizesUmenuPersonalLinkAndUsernameWithoutDeletingNodes() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite>
                <span class="pipe">|</span>
                <a href="pm.php" id="pm_ntc">短消息</a>
                <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
              </div>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_LIST, identity)
        val doc = Jsoup.parse(sanitized)

        val umenu = doc.selectFirst("div#umenu")!!
        val userLink = umenu.selectFirst("cite a.noborder")!!
        assertEquals("space.php?uid=10000", userLink.attr("href"))
        assertEquals("HiUser", userLink.text())
        assertTrue(umenu.html().contains("space.php"), "LoginHelper.checkLoggedin requires space.php")
        assertEquals("logging.php?action=logout&formhash=00000000", umenu.select("a").last()!!.attr("href"))
        assertEquals("退出", umenu.select("a").last()!!.text())
        assertStructureUnchanged(raw, sanitized, listOf("div#umenu", "#umenu cite a", "#umenu cite a.noborder", "div#umenu a"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun rule5SanitizesSmsListBodiesAndPeerIdentitiesWithEqualLengthPlaceholder() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>var discuz_uid = '54321';</script>
            </head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=1a2b3c4d">退出</a>
              </div>
              <ul class="pm_list">
                <li id="pm_1">
                  <a class="avatar" href="space.php?uid=67890"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/06/78/90_avatar_middle.jpg" /></a>
                  <p class="cite"><cite><a href="space.php?uid=67890">PeerAlice</a></cite> 2026-11-20 10:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">今晚八点老地方见面！</div>
                </li>
                <li id="pm_2">
                  <a class="avatar" href="space.php?uid=78901"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/07/89/01_avatar_middle.jpg" /></a>
                  <p class="cite"><cite><a href="space.php?uid=78901">PeerBob</a></cite> 2026-11-20 11:30</p>
                  <div class="summary">好呀呀</div>
                </li>
              </ul>
            </body>
            </html>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.SMS_CONTENT })
        assertTrue(rawLeaks.any { it.type == LeakType.FORMHASH })
        assertTrue(rawLeaks.any { it.type == LeakType.DISCUZ_UID })

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.SMS_LIST, identity)
        val doc = Jsoup.parse(sanitized)
        val items = doc.select("ul.pm_list li")

        assertEquals(2, items.size)
        assertEquals("HiPeer", items[0].selectFirst("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[0].selectFirst("p.cite cite a")?.attr("href"))
        assertEquals("space.php?uid=20000", items[0].selectFirst("a.avatar")?.attr("href"))
        assertTrue(items[0].selectFirst("a.avatar img")!!.attr("src").contains("000/02/00/00_avatar_middle.jpg"))
        assertEquals("测试内容测试内容测试", items[0].selectFirst("div.summary")?.text())
        assertEquals("今晚八点老地方见面！".length, items[0].selectFirst("div.summary")!!.text().length)
        assertEquals("2026-11-20 10:00", items[0].selectFirst("p.cite")?.ownText())

        assertEquals("HiPeer", items[1].selectFirst("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[1].selectFirst("p.cite cite a")?.attr("href"))
        assertEquals("测试内", items[1].selectFirst("div.summary")?.text())
        assertEquals("好呀呀".length, items[1].selectFirst("div.summary")!!.text().length)

        assertFalse(sanitized.contains("PeerAlice"))
        assertFalse(sanitized.contains("PeerBob"))
        assertFalse(sanitized.contains("67890"))
        assertFalse(sanitized.contains("78901"))
        assertFalse(sanitized.contains("老地方"))

        assertStructureUnchanged(
            raw,
            sanitized,
            listOf("ul.pm_list", "ul.pm_list li", "p.cite", "p.cite cite", "p.cite cite a", "a.avatar", "a.avatar img", "div.summary"),
        )
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun rule5SanitizesSmsDetailConversationKeepingSelfPeerDistinctionAndRichHtmlStructure() {
        val prefixIdentity = Identity(uid = "54321", username = "User")
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>与 UserTwo 的短消息</title>
              <script>var discuz_uid = '54321';</script>
            </head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">User</a></cite>
                <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
              </div>
              <ul class="pm_list">
                <li id="pm_101" class="s_clear">
                  <a class="avatar" href="space.php?uid=67890"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/06/78/90_avatar_middle.jpg" /></a>
                  <p class="cite"><cite>UserTwo</cite> 2026-11-20 12:00</p>
                  <div class="summary">第一行私密<br /><a href="space.php?uid=67890">点击链接</a><img src="images/smilies/default/smile.gif" /></div>
                </li>
                <li id="pm_102" class="s_clear self">
                  <a class="avatar" href="space.php?uid=54321"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/05/43/21_avatar_middle.jpg" /></a>
                  <p class="cite"><cite>User</cite> 2026-11-20 12:01</p>
                  <div class="summary">收到 UserTwo 的消息了</div>
                </li>
              </ul>
              <form action="pm.php?action=send&amp;uid=67890" method="post">
                <input type="hidden" id="formhash" name="formhash" value="a1b2c3d4" />
                <input type="hidden" name="msgto" value="UserTwo" />
                <textarea name="message"></textarea>
              </form>
            </body>
            </html>
        """.trimIndent()

        assertTrue(SampleLeakScanner.scan(raw).isNotEmpty())

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.SMS_DETAIL, prefixIdentity)
        val doc = Jsoup.parse(sanitized)
        val items = doc.select("li.s_clear")

        assertEquals(2, items.size)
        assertEquals("与 HiPeer 的短消息", doc.title())

        // Peer message
        assertEquals("HiPeer", items[0].selectFirst("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[0].selectFirst("a.avatar")?.attr("href"))
        assertEquals("space.php?uid=20000", items[0].selectFirst("div.summary a")?.attr("href"))
        assertEquals("测试内容", items[0].selectFirst("div.summary a")?.text())
        assertEquals(1, items[0].select("div.summary br").size)
        assertEquals(1, items[0].select("div.summary img").size)

        // Self message
        assertEquals("HiUser", items[1].selectFirst("p.cite cite")?.text())
        assertEquals("space.php?uid=10000", items[1].selectFirst("a.avatar")?.attr("href"))
        assertEquals("收到 UserTwo 的消息了".length, items[1].selectFirst("div.summary")!!.text().length)
        assertEquals("测试内容测试内容测试内容测试内", items[1].selectFirst("div.summary")?.text())

        // Reply form
        assertEquals("pm.php?action=send&uid=20000", doc.selectFirst("form")?.attr("action"))
        assertEquals("00000000", doc.selectFirst("input#formhash")?.attr("value"))
        assertEquals("HiPeer", doc.selectFirst("input[name=msgto]")?.attr("value"))

        assertFalse(sanitized.contains("UserTwo"))
        assertFalse(sanitized.contains("67890"))
        assertFalse(sanitized.contains("54321"))
        assertFalse(sanitized.contains("第一行私密"))

        assertStructureUnchanged(
            raw,
            sanitized,
            listOf("#umenu cite a.noborder", "li.s_clear", "p.cite", "p.cite cite", "a.avatar", "div.summary", "div.summary br", "div.summary a", "div.summary img", "input#formhash"),
        )
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun threadDetailPreservesFullDomStructureAndIsIdempotent() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>var discuz_uid = '54321', formhash = '9f8e7d6c';</script>
            </head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=9f8e7d6c">退出</a>
              </div>
              <div id="wrap">
                <div id="nav"><a href="forumdisplay.php?fid=2">Discovery</a> &raquo; 合成测试主题</div>
                <div id="postlist">
                  <div id="post_1001">
                    <table><tbody><tr>
                      <td class="postauthor">
                        <div class="postinfo"><a href="space.php?uid=54321">RealUser</a></div>
                      </td>
                      <td class="postcontent">
                        <div class="postinfo"><strong><a><em>1</em></a></strong></div>
                        <div class="defaultpost">
                          <div class="postmessage">
                            <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">测试帖子正文内容</td></tr></tbody></table></div>
                          </div>
                        </div>
                      </td>
                    </tr></tbody></table>
                  </div>
                </div>
                <form id="poll" action="misc.php?action=votepoll&amp;tid=100">
                  <input type="hidden" name="formhash" value="9f8e7d6c" />
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()

        assertTrue(SampleLeakScanner.scan(raw).isNotEmpty())
        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_DETAIL, identity)
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())

        assertStructureUnchanged(
            raw,
            sanitized,
            listOf(
                "div#umenu",
                "#umenu cite a.noborder",
                "div#wrap div#postlist",
                "div#postlist > div",
                "td.postauthor div.postinfo a",
                "td.t_msgfont",
                "form#poll",
                "input[name=formhash]",
            ),
        )

        val sanitizedTwice = HtmlSanitizer.sanitize(sanitized, PageType.THREAD_DETAIL, identity)
        assertEquals(sanitized, sanitizedTwice, "Sanitizing twice must be idempotent")
    }

    @Test
    fun scannerDetectsAllFourLeakTypesAndDoesNotFalsePositiveOnNotifySummary() {
        val notifyHtml = """
            <!DOCTYPE html>
            <html>
            <head><script>var discuz_uid = '10000';</script></head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=00000000">退出</a>
              </div>
              <ul class="feed">
                <li>
                  <div class="f_quote">
                    <a href="space.php?uid=33333">OtherUser</a>
                    <em>2026-11-20</em>
                    <dl class="summary"><dd>原帖摘要内容</dd><dd>回复摘要内容</dd></dl>
                  </div>
                </li>
              </ul>
            </body>
            </html>
        """.trimIndent()

        assertTrue(SampleLeakScanner.scan(notifyHtml).isEmpty(), "Notice dl.summary must not trigger SMS leak")

        val leakyCookieHtml = """
            <html><body>
              <script>
                document.cookie = "cdb_auth=abc123; cdb_sid=xyz789; cf_clearance=cf-token-value";
              </script>
            </body></html>
        """.trimIndent()
        val cookieLeaks = SampleLeakScanner.scan(leakyCookieHtml)
        assertEquals(3, cookieLeaks.count { it.type == LeakType.COOKIE_VALUE })
    }

    private fun assertStructureUnchanged(raw: String, sanitized: String, selectors: List<String>) {
        val rawDoc: Document = Jsoup.parse(raw)
        val sanitizedDoc: Document = Jsoup.parse(sanitized)

        for (selector in selectors) {
            assertEquals(
                rawDoc.select(selector).size,
                sanitizedDoc.select(selector).size,
                "Node count changed for selector: $selector",
            )
        }

        val rawElements = rawDoc.allElements
        val sanitizedElements = sanitizedDoc.allElements
        assertEquals(rawElements.size, sanitizedElements.size, "Total element count changed")
        for (i in rawElements.indices) {
            assertEquals(rawElements[i].tagName(), sanitizedElements[i].tagName(), "Tag changed at index $i")
            assertEquals(rawElements[i].id(), sanitizedElements[i].id(), "id changed at index $i")
            assertEquals(rawElements[i].className(), sanitizedElements[i].className(), "class changed at index $i")
        }
    }
}
