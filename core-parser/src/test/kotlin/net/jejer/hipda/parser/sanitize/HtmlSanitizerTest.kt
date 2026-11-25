package net.jejer.hipda.parser.sanitize

import net.jejer.hipda.parser.sanitize.SanitizeSelectors.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

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
        assertEquals("post_54321", doc.firstMatch("div.postbox")?.id(), "DOM id must not be modified")
        assertEquals("space.php?uid=10000&username=HiUser", doc.firstMatch("a[title]")?.attr("href"))
        assertEquals("访问 HiUser 的空间", doc.firstMatch("a[title]")?.attr("title"))
        assertEquals("HiUser", doc.firstMatch("a[title]")?.text())
        assertEquals("search.php?srchuid=10000&srchuname=HiUser", doc.select("a")[1].attr("href"))
        assertEquals("viewthread.php?tid=9543210", doc.select("a")[2].attr("href"))
        assertTrue(doc.firstMatch("img")!!.attr("src").contains("000/01/00/00_avatar_middle.jpg"))
        assertEquals("HiUser", doc.firstMatch("img")?.attr("alt"))
        assertEquals("用户详情 (UID: 10000)", doc.firstMatch("p")?.text())
        assertEquals("HiUser", doc.firstMatch("input[name=author]")?.attr("value"))
        val script = doc.firstMatch("script")!!.data()
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

        assertEquals("00000000", doc.firstMatch("input[name=formhash]")?.attr("value"))
        assertEquals("00000000", doc.firstMatch("input[name=m_formhash]")?.attr("value"))
        assertEquals("0".repeat(32), doc.firstMatch("input[name=hash]")?.attr("value"))
        assertEquals("000000", doc.firstMatch("input[name=sid]")?.attr("value"))
        assertEquals("000000000", doc.firstMatch("input[name=auth]")?.attr("value"))
        assertEquals("0000", doc.firstMatch("input[name=seccode]")?.attr("value"))
        assertEquals(
            "post.php?action=reply&formhash=00000000&sid=000000",
            doc.firstMatch("form")?.attr("action"),
        )
        assertEquals(
            "logging.php?action=logout&formhash=00000000",
            doc.select("a")[0].attr("href"),
        )
        assertEquals(
            "attachment.php?aid=99&auth=000000000&seccode=0000&hash=00000000",
            doc.select("a")[1].attr("href"),
        )

        val script = doc.firstMatch("script")!!.data()
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

        val umenu = doc.firstMatch("div#umenu")!!
        val userLink = umenu.firstMatch("cite a.noborder")!!
        assertEquals("space.php?uid=10000", userLink.attr("href"))
        assertEquals("HiUser", userLink.text())
        assertTrue(umenu.html().contains("space.php"), "LoginHelper.checkLoggedin requires space.php")
        assertEquals("logging.php?action=logout&formhash=00000000", umenu.select("a").lastOrNull()!!.attr("href"))
        assertEquals("退出", umenu.select("a").lastOrNull()!!.text())
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
        assertEquals("HiPeer", items[0].firstMatch("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[0].firstMatch("p.cite cite a")?.attr("href"))
        assertEquals("space.php?uid=20000", items[0].firstMatch("a.avatar")?.attr("href"))
        assertTrue(items[0].firstMatch("a.avatar img")!!.attr("src").contains("000/02/00/00_avatar_middle.jpg"))
        assertEquals("测试内容测试内容测试", items[0].firstMatch("div.summary")?.text())
        assertEquals("今晚八点老地方见面！".length, items[0].firstMatch("div.summary")!!.text().length)
        assertEquals("2026-11-20 10:00", items[0].firstMatch("p.cite")?.ownText())

        assertEquals("HiPeer", items[1].firstMatch("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[1].firstMatch("p.cite cite a")?.attr("href"))
        assertEquals("测试内", items[1].firstMatch("div.summary")?.text())
        assertEquals("好呀呀".length, items[1].firstMatch("div.summary")!!.text().length)

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
        assertEquals("HiPeer", items[0].firstMatch("p.cite cite")?.text())
        assertEquals("space.php?uid=20000", items[0].firstMatch("a.avatar")?.attr("href"))
        assertEquals("space.php?uid=20000", items[0].firstMatch("div.summary a")?.attr("href"))
        assertEquals("测试内容", items[0].firstMatch("div.summary a")?.text())
        assertEquals(1, items[0].select("div.summary br").size)
        assertEquals(1, items[0].select("div.summary img").size)

        // Self message
        assertEquals("HiUser", items[1].firstMatch("p.cite cite")?.text())
        assertEquals("space.php?uid=10000", items[1].firstMatch("a.avatar")?.attr("href"))
        assertEquals("收到 UserTwo 的消息了".length, items[1].firstMatch("div.summary")!!.text().length)
        assertEquals("测试内容测试内容测试内容测试内", items[1].firstMatch("div.summary")?.text())

        // Reply form
        assertEquals("pm.php?action=send&uid=20000", doc.firstMatch("form")?.attr("action"))
        assertEquals("00000000", doc.firstMatch("input#formhash")?.attr("value"))
        assertEquals("HiPeer", doc.firstMatch("input[name=msgto]")?.attr("value"))

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

    @Test
    fun blocker1SanitizesAndScansSmsSummaryLinksTitlesImagesAndAlts() {
        val raw = """
            <ul class="pm_list">
              <li class="s_clear">
                <p class="cite"><cite>PeerAlice</cite> 2026-11-20</p>
                <div class="summary">闲鱼：<a href="https://m.tb.cn/h.5SecretXianyu?tk=9988" title="私密闲鱼链接">https://m.tb.cn/h.5SecretXianyu?tk=9988</a> <a href="mailto:alice_real@gmail.com">alice_real@gmail.com</a> <img src="https://private.example.com/wechat_qr_999.png" alt="微信号 alice_wx" /><img src="images/smilies/default/smile.gif" /></div>
              </li>
            </ul>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.SMS_CONTENT })
        assertTrue(rawLeaks.any { it.type == LeakType.SMS_PEER })

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.SMS_DETAIL, identity)
        assertFalse(sanitized.contains("SecretXianyu"))
        assertFalse(sanitized.contains("m.tb.cn"))
        assertFalse(sanitized.contains("私密闲鱼链接"))
        assertFalse(sanitized.contains("alice_real@gmail.com"))
        assertFalse(sanitized.contains("wechat_qr_999"))
        assertFalse(sanitized.contains("alice_wx"))

        val doc = Jsoup.parse(sanitized)
        val links = doc.select("div.summary a")
        assertEquals(2, links.size)
        assertEquals(SanitizeSelectors.PLACEHOLDER_SMS_URL, links[0].attr("href"))
        assertEquals("测试内容测试", links[0].attr("title"))
        assertEquals(SanitizeSelectors.PLACEHOLDER_SMS_URL, links[1].attr("href"))

        val imgs = doc.select("div.summary img")
        assertEquals(2, imgs.size)
        assertEquals(SanitizeSelectors.PLACEHOLDER_SMS_IMAGE_URL, imgs[0].attr("src"))
        assertEquals("微信号 alice_wx".length, imgs[0].attr("alt").length)
        assertEquals("images/smilies/default/smile.gif", imgs[1].attr("src"))

        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty(), "Sanitized SMS links/images must pass scanner")

        // 即使正文已是占位词，未脱敏的 href/title/src/alt 也必须被扫描器捕获
        val attrOnlyLeak = """
            <ul class="pm_list"><li class="s_clear">
              <p class="cite"><cite>HiPeer</cite> 2026-11-20</p>
              <div class="summary">测试内容<a href="https://m.tb.cn/secret" title="私密标题">测试内容</a><img src="https://private.example.com/qr.png" alt="私密微信号" /></div>
            </li></ul>
        """.trimIndent()
        val attrLeaks = SampleLeakScanner.scan(attrOnlyLeak)
        assertTrue(attrLeaks.count { it.type == LeakType.SMS_CONTENT } >= 4)
    }

    @Test
    fun blocker2SanitizesAndScansBase64EncodedAttachmentAid() {
        val rawAid = "MTIzNDU2fGExYjJjM2Q0fDE3MDAwMDAwMDB8NTQzMjF8OTk5OTk5"
        val raw = """
            <dl class="t_attachlist">
              <p class="attachname">
                <a href="attachment.php?aid=$rawAid">doc.pdf</a>
              </p>
            </dl>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.ATTACHMENT_AID }, "Scanner must detect unsanitized attachment aid")

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_DETAIL, identity)
        assertFalse(sanitized.contains(rawAid))

        val href = Jsoup.parse(sanitized).firstMatch("a")!!.attr("href")
        assertTrue(href.startsWith("attachment.php?aid="))
        val encoded = href.substringAfter("aid=")
        val decoded = String(Base64.getDecoder().decode(encoded))
        val parts = decoded.split("|")
        assertEquals(5, parts.size)
        assertEquals("123456", parts[0])
        assertEquals("00000000", parts[1])
        assertEquals("10000", parts[3])
        assertEquals("999999", parts[4])
        assertFalse(decoded.contains("54321"))
        assertFalse(decoded.contains("a1b2c3d4"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())

        // Discuz 7.0 四段 URL 编码载荷（含 = -> %3D）
        val aid4Raw = Base64.getEncoder().encodeToString("9876|deadbeef|1700000000|54321".toByteArray())
            .replace("=", "%3D")
        val html4 = """<a href="attachment.php?nothumb=yes&amp;aid=$aid4Raw">img.jpg</a>"""
        assertTrue(SampleLeakScanner.scan(html4).any { it.type == LeakType.ATTACHMENT_AID })
        val sanitized4 = HtmlSanitizer.sanitize(html4, PageType.THREAD_DETAIL, identity)
        assertFalse(sanitized4.contains(aid4Raw))
        assertTrue(SampleLeakScanner.scan(sanitized4).isEmpty())
    }

    @Test
    fun blocker3SanitizesEncodedSpecialCharTextareaAndCaseInsensitiveUsernames() {
        // 1. 含空格 + 编码
        val spaceUser = Identity("54321", "Real User")
        val spaceRaw = """<a href="space.php?username=Real+User">link</a>"""
        val spaceSanitized = HtmlSanitizer.sanitize(spaceRaw, PageType.USER_INFO, spaceUser)
        assertFalse(spaceSanitized.contains("Real+User"))
        assertEquals("space.php?username=HiUser", Jsoup.parse(spaceSanitized).firstMatch("a")!!.attr("href"))

        // 2. PHP rawurlencode (~) 与 urlencode (*) 变体
        val tildeUser = Identity("54321", "~风~")
        val tildeRaw = """<a href="space.php?username=~%B7%E7~">~风~</a>"""
        val tildeSanitized = HtmlSanitizer.sanitize(tildeRaw, PageType.USER_INFO, tildeUser)
        assertFalse(tildeSanitized.contains("%B7%E7", ignoreCase = true))
        assertFalse(tildeSanitized.contains("~风~"))
        assertEquals("space.php?username=HiUser", Jsoup.parse(tildeSanitized).firstMatch("a")!!.attr("href"))

        val starUser = Identity("54321", "风*")
        val starRaw = """<a href="space.php?username=%B7%E7%2A">风*</a>"""
        val starSanitized = HtmlSanitizer.sanitize(starRaw, PageType.USER_INFO, starUser)
        assertFalse(starSanitized.contains("%B7%E7", ignoreCase = true))
        assertEquals("space.php?username=HiUser", Jsoup.parse(starSanitized).firstMatch("a")!!.attr("href"))

        // 3. textarea 内 GBK 编码用户名
        val gbkUser = Identity("54321", "张三")
        val textareaRaw = """<textarea name="message">[url=https://www.4d4y.com/forum/space.php?username=%D5%C5%C8%FD]profile[/url]</textarea>"""
        val textareaSanitized = HtmlSanitizer.sanitize(textareaRaw, PageType.POST_FORM, gbkUser)
        assertFalse(textareaSanitized.contains("%D5%C5%C8%FD", ignoreCase = true))
        assertTrue(textareaSanitized.contains("space.php?username=HiUser]profile[/url]"))

        // 4. 无 #umenu 页面的大小写不同用户名
        val caseRaw = """<html><head><title>realuser 的个人资料</title></head><body><p>欢迎您回来，realuser</p></body></html>"""
        val caseSanitized = HtmlSanitizer.sanitize(caseRaw, PageType.USER_INFO, identity)
        assertFalse(caseSanitized.contains("realuser", ignoreCase = true))
        assertEquals("HiUser 的个人资料", Jsoup.parse(caseSanitized).title())
        assertEquals("欢迎您回来，HiUser", Jsoup.parse(caseSanitized).firstMatch("p")!!.text())
    }

    @Test
    fun blocker4SanitizesOneWaySmsDetailPeerUsernameWithoutPeerMessageOrMsgto() {
        val raw = """
            <html>
            <head><title>与 PeerBob 的短消息 - Hi!PDA</title></head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite></div>
              <div class="itemtitle s_clear"><h2>共有 1 条与 <a href="space.php?uid=67890">PeerBob</a> 的交谈记录</h2></div>
              <ul class="pm_list">
                <li class="s_clear self">
                  <p class="cite"><cite>RealUser</cite> 2026-11-20</p>
                  <div class="summary">你好 PeerBob</div>
                </li>
              </ul>
              <form action="pm.php?action=send&amp;uid=67890" method="post">
                <input type="hidden" id="formhash" name="formhash" value="a1b2c3d4" />
              </form>
            </body>
            </html>
        """.trimIndent()

        val rawLeaks = SampleLeakScanner.scan(raw)
        assertTrue(rawLeaks.any { it.type == LeakType.SMS_PEER })
        assertTrue(rawLeaks.any { it.type == LeakType.SMS_CONTENT })
        assertTrue(rawLeaks.any { it.type == LeakType.FORMHASH })

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.SMS_DETAIL, identity)
        assertFalse(sanitized.contains("PeerBob"))
        assertFalse(sanitized.contains("67890"))
        assertFalse(sanitized.contains("54321"))
        val doc = Jsoup.parse(sanitized)
        assertEquals("与 HiPeer 的短消息 - Hi!PDA", doc.title())
        assertEquals("HiPeer", doc.firstMatch("div.itemtitle a")!!.text())
        assertEquals("space.php?uid=20000", doc.firstMatch("div.itemtitle a")!!.attr("href"))
        assertEquals("pm.php?action=send&uid=20000", doc.firstMatch("form")!!.attr("action"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun blocker5SanitizesAndScansIpsEmailsExpandedCookiesScriptInputsDiscuzUidVariantsAndXmlCdata() {
        // 1. USER_INFO IP 与邮箱
        val profileRaw = """
            <ul class="commonlist">
              <li>邮箱: real_personal_mail@163.com</li>
              <li>注册 IP: 114.242.19.88 - - 北京</li>
              <li>上次访问 IP: 223.104.3.155:51234</li>
              <li>IPv6: 2408:8207:192c:a100::1234</li>
            </ul>
        """.trimIndent()
        val profileLeaks = SampleLeakScanner.scan(profileRaw)
        assertTrue(profileLeaks.count { it.type == LeakType.PERSONAL_INFO } >= 4)

        val profileSanitized = HtmlSanitizer.sanitize(profileRaw, PageType.USER_INFO, identity)
        assertFalse(profileSanitized.contains("real_personal_mail@163.com"))
        assertFalse(profileSanitized.contains("114.242.19.88"))
        assertFalse(profileSanitized.contains("223.104.3.155"))
        assertFalse(profileSanitized.contains("2408:8207"))
        assertTrue(profileSanitized.contains("user@example.com"))
        assertTrue(profileSanitized.contains("127.0.0.1"))
        assertTrue(profileSanitized.contains("::1"))
        assertTrue(SampleLeakScanner.scan(profileSanitized).isEmpty())

        // 2. 脚本内嵌 input formhash、扩展 Cookie、parseInt/JSON discuz_uid
        val scriptRaw = """
            <html><body>
              <script>
                var tpl = '<input type="hidden" name="formhash" value="deadbeef" />';
                var discuz_uid = parseInt('99999');
                var meta = {"discuz_uid": "88888"};
                document.cookie = "cdb_loginuser=MyLoginAlias; cdb_activationauth=actSecret99; cdb_oldtopics=D776655D; __cf_bm=cfbmSecret";
              </script>
            </body></html>
        """.trimIndent()
        val scriptLeaks = SampleLeakScanner.scan(scriptRaw)
        assertTrue(scriptLeaks.any { it.type == LeakType.FORMHASH })
        assertEquals(2, scriptLeaks.count { it.type == LeakType.DISCUZ_UID })
        assertEquals(4, scriptLeaks.count { it.type == LeakType.COOKIE_VALUE })

        val scriptSanitized = HtmlSanitizer.sanitize(scriptRaw, PageType.THREAD_DETAIL, identity)
        assertFalse(scriptSanitized.contains("deadbeef"))
        assertFalse(scriptSanitized.contains("99999"))
        assertFalse(scriptSanitized.contains("88888"))
        assertFalse(scriptSanitized.contains("MyLoginAlias"))
        assertFalse(scriptSanitized.contains("actSecret99"))
        assertFalse(scriptSanitized.contains("D776655D"))
        assertFalse(scriptSanitized.contains("cfbmSecret"))
        assertTrue(scriptSanitized.contains("parseInt('10000')"))
        assertTrue(scriptSanitized.contains("\"discuz_uid\": \"10000\""))
        assertTrue(SampleLeakScanner.scan(scriptSanitized).isEmpty())

        // 3. Discuz AJAX XML CDATA 保持结构并脱敏
        val xmlRaw = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，RealUser <input type="hidden" name="formhash" value="a1b2c3d4" />]]></root>"""
        val xmlLeaks = SampleLeakScanner.scan(xmlRaw)
        assertTrue(xmlLeaks.any { it.type == LeakType.FORMHASH })

        val xmlSanitized = HtmlSanitizer.sanitize(xmlRaw, PageType.LOGIN, identity)
        assertFalse(xmlSanitized.contains("RealUser"))
        assertFalse(xmlSanitized.contains("a1b2c3d4"))
        assertTrue(xmlSanitized.contains("<![CDATA["))
        val xmlDoc = Jsoup.parse(xmlSanitized, "", Parser.xmlParser())
        val rootText = xmlDoc.select("root").text()
        assertTrue(rootText.contains("欢迎您回来，HiUser"))
        assertTrue(rootText.contains("00000000"))
        assertTrue(SampleLeakScanner.scan(xmlSanitized).isEmpty())
    }

    @Test
    fun blocker6ShortUidDoesNotCorruptFidPageFontSizeOrYears() {
        val shortUidIdentity = Identity(uid = "2", username = "RealUser")
        val raw = """
            <html>
            <head><script>var discuz_uid = '2';</script></head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=2" class="noborder">RealUser</a></cite></div>
              <a href="forumdisplay.php?fid=2">Discovery</a>
              <a href="viewthread.php?tid=54321&amp;page=2">2</a>
              <div class="quote"><font size="2">OtherUser 2026-11-20</font></div>
              <img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/00/00/02_avatar_middle.jpg" />
              <p>(UID: 2)</p>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_DETAIL, shortUidIdentity)
        val doc = Jsoup.parse(sanitized)

        assertEquals("space.php?uid=10000", doc.firstMatch("#umenu cite a")!!.attr("href"))
        assertEquals("forumdisplay.php?fid=2", doc.select("a")[1].attr("href"))
        assertEquals("viewthread.php?tid=54321&page=2", doc.select("a")[2].attr("href"))
        assertEquals("2", doc.select("a")[2].text())
        assertEquals("2", doc.firstMatch("div.quote font")!!.attr("size"))
        assertEquals("OtherUser 2026-11-20", doc.firstMatch("div.quote font")!!.text())
        assertTrue(doc.firstMatch("img")!!.attr("src").contains("000/01/00/00_avatar_middle.jpg"))
        assertEquals("(UID: 10000)", doc.firstMatch("p")!!.text())
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun blocker6UsernameSpaceDoesNotCorruptSpacePhpUrls() {
        val raw = """
            <html>
            <head><script>var space = 1; var url = 'space.php?uid=54321'; var discuz_uid = '54321';</script></head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">space</a></cite></div>
              <p>欢迎回来，space</p>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_LIST, Identity("54321", "space"))
        val doc = Jsoup.parse(sanitized)

        assertEquals("space.php?uid=10000", doc.firstMatch("#umenu cite a")!!.attr("href"))
        assertEquals("HiUser", doc.firstMatch("#umenu cite a")!!.text())
        assertTrue(doc.firstMatch("script")!!.data().contains("var space = 1;"))
        assertTrue(doc.firstMatch("script")!!.data().contains("space.php?uid=10000"))
        assertEquals("欢迎回来，HiUser", doc.firstMatch("p")!!.text())
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun blocker6UsernameReplyDoesNotCorruptJumpToFloorOrReplyUiLabels() {
        val raw = """
            <html>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">回复</a></cite></div>
              <strong><a href="redirect.php?goto=findpost&amp;pid=200&amp;ptid=100">回复 1# OtherUser</a></strong>
              <strong><a href="redirect.php?goto=findpost&amp;pid=201&amp;ptid=100">回复 2# 回复</a></strong>
              <a href="post.php?action=reply&amp;tid=100">回复</a>
              <span>回复: 56</span>
              <p>OtherUser 回复了您的帖子</p>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_DETAIL, Identity("54321", "回复"))
        val doc = Jsoup.parse(sanitized)
        val strongs = doc.select("strong a")

        assertEquals("HiUser", doc.firstMatch("#umenu cite a")!!.text())
        assertEquals("回复 1# OtherUser", strongs[0].text())
        assertEquals("回复 2# HiUser", strongs[1].text())
        assertEquals("回复", doc.firstMatch("a[href*=post.php]")!!.text())
        assertEquals("回复: 56", doc.firstMatch("span")!!.text())
        assertEquals("OtherUser 回复了您的帖子", doc.firstMatch("p")!!.text())
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun blocker6UsernameAdminDoesNotCorruptOtherUsernamesOrAssetPaths() {
        val raw = """
            <html>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">admin</a></cite></div>
              <td class="author"><cite><a href="space.php?uid=99999">admin123</a></cite></td>
              <p>administrator 发布了公告，欢迎 admin</p>
              <img src="images/default/admin_icon.gif" />
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_LIST, Identity("54321", "admin"))
        val doc = Jsoup.parse(sanitized)

        assertEquals("HiUser", doc.firstMatch("#umenu cite a")!!.text())
        assertTrue(sanitized.contains("admin123"))
        assertTrue(sanitized.contains("administrator 发布了公告，欢迎 HiUser"))
        assertEquals("images/default/admin_icon.gif", doc.firstMatch("img")!!.attr("src"))
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun blocker6UsernameZerosDoesNotCorruptSanitizedPlaceholders() {
        val raw = """
            <html>
            <head>
              <script>
                var discuz_uid = '54321';
                var formhash = 'a1b2c3d4';
                var sid = 'xyz';
                var currentUser = '000';
              </script>
            </head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">000</a></cite></div>
              <img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/05/43/21_avatar_middle.jpg" />
              <p>欢迎您回来，000</p>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(raw, PageType.THREAD_LIST, Identity("54321", "000"))
        val doc = Jsoup.parse(sanitized)
        val script = doc.firstMatch("script")!!.data()

        assertTrue(script.contains("var discuz_uid = '10000';"))
        assertTrue(script.contains("var formhash = '00000000';"))
        assertTrue(script.contains("var sid = '000';"))
        assertTrue(script.contains("var currentUser = 'HiUser';"))
        assertEquals("HiUser", doc.firstMatch("#umenu cite a")!!.text())
        assertTrue(doc.firstMatch("img")!!.attr("src").contains("000/01/00/00_avatar_middle.jpg"))
        assertEquals("欢迎您回来，HiUser", doc.firstMatch("p")!!.text())
        assertTrue(SampleLeakScanner.scan(sanitized).isEmpty())
    }

    @Test
    fun suggestion7ScannerSymmetryAndNoFalsePositiveOnNonSmsClearSummary() {
        val nonSmsFeed = """<html><body><ul class="feed"><li class="s_clear"><div class="summary">公开动态内容</div></li></ul></body></html>"""
        val sanitizedFeed = HtmlSanitizer.sanitize(nonSmsFeed, PageType.USER_INFO, identity)
        assertTrue(SampleLeakScanner.scan(sanitizedFeed).isEmpty(), "Non-SMS li.s_clear without p.cite must not trigger SMS_CONTENT")

        val leakedTokens = """
            <html><body>
              <div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite></div>
              <input type="hidden" name="hash" value="0123456789abcdef0123456789abcdef" />
              <input type="hidden" name="sid" value="XyZ789" />
              <ul class="pm_list">
                <li>
                  <p class="cite"><cite><a href="space.php?uid=67890">PeerAlice</a></cite> 2026-11-20</p>
                  <div class="summary">测试内容</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val leaks = SampleLeakScanner.scan(leakedTokens)
        assertTrue(leaks.any { it.type == LeakType.UMENU_IDENTITY })
        assertTrue(leaks.any { it.type == LeakType.SENSITIVE_TOKEN })
        assertTrue(leaks.any { it.type == LeakType.SMS_PEER })
    }

    @Test
    fun suggestion8SanitizesSelfSignatureNotifyBuddyAndBlacklist() {
        val threadWithSig = """
            <div id="postlist">
              <div id="post_1001">
                <table><tbody>
                  <tr><td class="postauthor"><div class="postinfo"><a href="space.php?uid=54321">RealUser</a></div></td></tr>
                  <tr><td class="postbottom"><div class="signatures">个人微信 my_wx_123</div></td></tr>
                </tbody></table>
              </div>
              <div id="post_1002">
                <table><tbody>
                  <tr><td class="postauthor"><div class="postinfo"><a href="space.php?uid=88888">OtherUser</a></div></td></tr>
                  <tr><td class="postbottom"><div class="signatures">别人的公开签名</div></td></tr>
                </tbody></table>
              </div>
            </div>
        """.trimIndent()
        val sigSanitized = HtmlSanitizer.sanitize(threadWithSig, PageType.THREAD_DETAIL, identity)
        assertFalse(sigSanitized.contains("my_wx_123"))
        assertTrue(sigSanitized.contains("别人的公开签名"))

        val notifyRaw = """
            <ul class="feed">
              <li><div class="f_buddy"><a href="space.php?uid=88888">OldWang</a> 请求加您为好友，附言：我是王五，电话 13912345678 <em>2026-11-20</em></div></li>
            </ul>
        """.trimIndent()
        assertTrue(SampleLeakScanner.scan(notifyRaw).any { it.type == LeakType.PERSONAL_INFO })
        val notifySanitized = HtmlSanitizer.sanitize(notifyRaw, PageType.NOTIFY, identity)
        assertFalse(notifySanitized.contains("OldWang"))
        assertFalse(notifySanitized.contains("88888"))
        assertFalse(notifySanitized.contains("13912345678"))
        assertTrue(notifySanitized.contains("2026-11-20"))
        assertTrue(SampleLeakScanner.scan(notifySanitized).isEmpty())

        val blacklistRaw = """
            <div class="blacklist">
              <ul class="commonlist">
                <li><a href="space.php?username=%C0%EE%CB%C4">李四</a></li>
                <li><a href="space.php?username=%CD%F5%CE%E5">王五</a></li>
              </ul>
            </div>
        """.trimIndent()
        assertTrue(SampleLeakScanner.scan(blacklistRaw).any { it.type == LeakType.PERSONAL_INFO })
        val blacklistSanitized = HtmlSanitizer.sanitize(blacklistRaw, PageType.BLACKLIST, identity)
        assertFalse(blacklistSanitized.contains("李四"))
        assertFalse(blacklistSanitized.contains("王五"))
        assertFalse(blacklistSanitized.contains("%C0%EE%CB%C4", ignoreCase = true))
        val blLinks = Jsoup.parse(blacklistSanitized).select("div.blacklist ul.commonlist a")
        assertEquals("space.php?username=HiBlocked1", blLinks[0].attr("href"))
        assertEquals("HiBlocked1", blLinks[0].text())
        assertEquals("space.php?username=HiBlocked2", blLinks[1].attr("href"))
        assertEquals("HiBlocked2", blLinks[1].text())
        assertTrue(SampleLeakScanner.scan(blacklistSanitized).isEmpty())
    }

    @Test
    fun r2Blocker1SanitizesChineseUsernamesWithoutSurroundingSpaces() {
        val chineseSelf = Identity(uid = "54321", username = "张三")

        // 1. 本人中文名在无空格的 title / attr / alt / 欢迎语中必须脱敏
        val profileRaw = """<html><head><title>张三的个人资料 - Hi!PDA</title></head><body><div id="umenu"><cite><a href="space.php?uid=54321" class="noborder" title="查看张三的个人资料">张三</a></cite></div><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/05/43/21_avatar_middle.jpg" alt="张三的头像"/><p>欢迎张三回来</p></body></html>"""
        assertTrue(SampleLeakScanner.scan(profileRaw).any { it.type == LeakType.UMENU_IDENTITY })

        val profileSanitized = HtmlSanitizer.sanitize(profileRaw, PageType.USER_INFO, chineseSelf)
        assertFalse(profileSanitized.contains("张三"), "Chinese username leaked: $profileSanitized")
        val profileDoc = Jsoup.parse(profileSanitized)
        assertEquals("HiUser的个人资料 - Hi!PDA", profileDoc.title())
        assertEquals("查看HiUser的个人资料", profileDoc.firstMatch("#umenu cite a")!!.attr("title"))
        assertEquals("HiUser", profileDoc.firstMatch("#umenu cite a")!!.text())
        assertEquals("HiUser的头像", profileDoc.firstMatch("img")!!.attr("alt"))
        assertEquals("欢迎HiUser回来", profileDoc.firstMatch("p")!!.text())
        assertTrue(SampleLeakScanner.scan(profileSanitized).isEmpty())

        // 2. 短消息中文对方名在无空格标题与交谈页头中必须脱敏
        val smsRaw = """<html><head><title>与李四的短消息 - Hi!PDA</title></head><body><div id="umenu"><cite><a href="space.php?uid=54321" class="noborder">张三</a></cite></div><div class="itemtitle s_clear"><h2>共有 1 条与李四的交谈记录</h2></div><ul class="pm_list"><li class="s_clear"><p class="cite"><cite><a href="space.php?uid=67890">李四</a></cite> 2026-11-20</p><div class="summary">你好</div></li></ul></body></html>"""
        val smsRawLeaks = SampleLeakScanner.scan(smsRaw)
        assertTrue(smsRawLeaks.any { it.type == LeakType.SMS_PEER })
        assertTrue(smsRawLeaks.any { it.type == LeakType.SMS_CONTENT })
        assertTrue(smsRawLeaks.any { it.type == LeakType.UMENU_IDENTITY })

        val smsSanitized = HtmlSanitizer.sanitize(smsRaw, PageType.SMS_DETAIL, chineseSelf)
        assertFalse(smsSanitized.contains("张三"))
        assertFalse(smsSanitized.contains("李四"), "Chinese peer username leaked: $smsSanitized")
        val smsDoc = Jsoup.parse(smsSanitized)
        assertEquals("与HiPeer的短消息 - Hi!PDA", smsDoc.title())
        assertEquals("共有 1 条与HiPeer的交谈记录", smsDoc.firstMatch("div.itemtitle h2")!!.text())
        assertTrue(SampleLeakScanner.scan(smsSanitized).isEmpty())

        // 3. 通知页固定短语保护：短名"回复"不误伤，正常中文名仍替换
        val replyNotify = """<html><body><p>OtherUser 回复了您关注的主题</p></body></html>"""
        assertEquals(
            "OtherUser 回复了您关注的主题",
            Jsoup.parse(HtmlSanitizer.sanitize(replyNotify, PageType.NOTIFY, Identity("54321", "回复"))).firstMatch("p")!!.text(),
        )
        val selfNotify = """<html><body><p>张三 回复了您关注的主题</p></body></html>"""
        assertEquals(
            "HiUser 回复了您关注的主题",
            Jsoup.parse(HtmlSanitizer.sanitize(selfNotify, PageType.NOTIFY, chineseSelf)).firstMatch("p")!!.text(),
        )
    }

    @Test
    fun r2Blocker2HandlesXhtmlWithXmlDeclarationAndNonCdataXmlElements() {
        // 1. 带 <?xml?> 声明的 XHTML 短消息页必须走 HTML 脱敏与扫描
        val xhtmlSmsRaw = """<?xml version="1.0" encoding="gbk"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"><html><body><ul class="pm_list"><li class="s_clear"><p class="cite"><cite>PeerAlice</cite></p><div class="summary">未脱敏短消息</div></li></ul></body></html>"""
        val xhtmlLeaks = SampleLeakScanner.scan(xhtmlSmsRaw)
        assertTrue(xhtmlLeaks.any { it.type == LeakType.SMS_CONTENT }, "XHTML SMS body must be scanned")
        assertTrue(xhtmlLeaks.any { it.type == LeakType.SMS_PEER }, "XHTML SMS peer must be scanned")

        val xhtmlSanitized = HtmlSanitizer.sanitize(xhtmlSmsRaw, PageType.SMS_DETAIL, identity)
        assertFalse(xhtmlSanitized.contains("PeerAlice"))
        assertFalse(xhtmlSanitized.contains("未脱敏短消息"))
        val xhtmlDoc = Jsoup.parse(xhtmlSanitized)
        assertEquals("HiPeer", xhtmlDoc.firstMatch("p.cite cite")!!.text())
        assertEquals("测试内容测试", xhtmlDoc.firstMatch("div.summary")!!.text())
        assertTrue(SampleLeakScanner.scan(xhtmlSanitized).isEmpty())

        // 2. 不含 CDATA 的 Discuz AJAX <root> XML 也必须脱敏并扫描非 CDATA 元素
        val nonCdataXmlRaw = """<?xml version="1.0" encoding="gbk"?><root><input type="hidden" name="formhash" value="a1b2c3d4"/></root>"""
        assertTrue(
            SampleLeakScanner.scan(nonCdataXmlRaw).any { it.type == LeakType.FORMHASH },
            "Non-CDATA XML input formhash must be detected by scanner",
        )

        val nonCdataXmlSanitized = HtmlSanitizer.sanitize(nonCdataXmlRaw, PageType.LOGIN, identity)
        assertFalse(nonCdataXmlSanitized.contains("a1b2c3d4"))
        val xmlDoc = Jsoup.parse(nonCdataXmlSanitized, "", Parser.xmlParser())
        assertEquals("00000000", xmlDoc.firstMatch("input[name=formhash]")!!.attr("value"))
        assertTrue(SampleLeakScanner.scan(nonCdataXmlSanitized).isEmpty())
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
