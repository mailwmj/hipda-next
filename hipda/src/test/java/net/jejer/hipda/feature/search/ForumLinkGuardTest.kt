package net.jejer.hipda.feature.search

import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.ThreadNavArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 站内链接识别与严格 `http`/`https` scheme 白名单安全反例测试。 */
class ForumLinkGuardTest {

    @Test
    fun resolveAndDispatch_acceptsValidHttpAndHttpsInSiteLinks() {
        val threads = mutableListOf<ThreadNavArgs>()
        val forums = mutableListOf<Int>()
        val users = mutableListOf<Pair<String, String>>()

        val threadLink = resolveAllowedForumLink("https://www.4d4y.com/forum/viewthread.php?tid=3478055")
        assertEquals(ResolvedForumLink.Thread("3478055", 1), threadLink)
        assertTrue(dispatchAllowedForumLink("https://www.4d4y.com/forum/viewthread.php?tid=3478055", AppSettings.DEFAULT_FORUM_SERVER, threads::add, forums::add) { u, n -> users += u to n })

        assertTrue(dispatchAllowedForumLink("HTTP://www.hi-pda.com/forum/forumdisplay.php?fid=6", AppSettings.DEFAULT_FORUM_SERVER, threads::add, forums::add) { u, n -> users += u to n })
        assertTrue(dispatchAllowedForumLink("HTTPS://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=3474137&pid=74880921", AppSettings.DEFAULT_FORUM_SERVER, threads::add, forums::add) { u, n -> users += u to n })
        assertTrue(dispatchAllowedForumLink("https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=3468951", AppSettings.DEFAULT_FORUM_SERVER, threads::add, forums::add) { u, n -> users += u to n })
        assertTrue(dispatchAllowedForumLink("https://www.4d4y.com/forum/space.php?uid=10000", AppSettings.DEFAULT_FORUM_SERVER, threads::add, forums::add) { u, n -> users += u to n })

        val customLink = resolveAllowedForumLink(
            rawInput = "http://127.0.0.1:8080/forum/viewthread.php?tid=9001",
            forumServer = "http://127.0.0.1:8080",
        )
        assertNotNull(customLink)
        assertEquals(ResolvedForumLink.Thread("9001", 1), customLink)

        assertEquals(
            listOf(
                ThreadNavArgs(tid = "3478055"),
                ThreadNavArgs(tid = "3474137", targetPid = "74880921"),
                ThreadNavArgs(tid = "3468951"),
            ),
            threads,
        )
        assertEquals(listOf(6), forums)
        assertEquals(listOf("10000" to ""), users)
    }

    @Test
    fun sanitizeAndDispatch_blocksDangerousSchemesWhitespaceControlCharsAndSpoofs() {
        val blockedInputs = listOf(
            null,
            "",
            "   ",
            // javascript:
            "javascript:alert(1)",
            "javascript://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "JAVASCRIPT://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "javascript:;",
            "JAVASCRIPT:history.back()",
            " JavaScript:alert(1)",
            "\tjavascript:void(0)",
            "java\nscript:alert(1)",
            "java\u0000script:alert(1)",
            // intent:
            "intent://scan/#Intent;scheme=zxing;end",
            "intent://www.4d4y.com/forum/viewthread.php?tid=3478055#Intent;scheme=https;end",
            "INTENT://evil/#Intent;end",
            " intent://www.4d4y.com/forum/viewthread.php?tid=1",
            // file:
            "file:///data/user/0/net.jejer.hipda/files/secret",
            "file://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "FILE:///etc/hosts",
            " file:///etc/passwd",
            // content:
            "content://media/external/images/media/1",
            "content://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "CONTENT://settings/system",
            " content://net.jejer.hipda/db",
            // data:
            "data:text/html,<script>alert(1)</script>",
            "data:text/html,https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "DATA:text/plain;base64,QQ==",
            " data:text/html,hi",
            // mailto: (搜索页站内地址仅白名单 http/https)
            "mailto:qa@example.com",
            "mailto:qa@4d4y.com?subject=https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "MAILTO:admin@4d4y.com",
            " mailto:qa@example.com",
            // other non-http(s) schemes
            "ftp://www.4d4y.com/forum/viewthread.php?tid=123",
            "tel:10086",
            "sms:10086",
            "blob:https://www.4d4y.com/forum/viewthread.php?tid=123",
            // whitespace / control / zero-width bypasses on http(s)
            " https://www.4d4y.com/forum/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/viewthread.php?tid=123 ",
            "\thttps://www.4d4y.com/forum/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/viewthread.php?tid=123\n",
            "https://www.4d4y.com/forum/\rviewthread.php?tid=123",
            "https://www.4d4y.com/forum/viewthread.php?tid=123\u0000",
            "\u200Bhttps://www.4d4y.com/forum/viewthread.php?tid=123",
            "\uFEFFhttps://www.4d4y.com/forum/viewthread.php?tid=123",
            "\u00A0https://www.4d4y.com/forum/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/viewthread.php?tid=123 extra",
            // scheme-less / protocol-relative / relative / host spoof / userinfo spoof
            "//www.4d4y.com/forum/viewthread.php?tid=123",
            "www.4d4y.com/forum/viewthread.php?tid=123",
            "viewthread.php?tid=123",
            "/forum/viewthread.php?tid=123",
            "https://www.4d4y.com@evil.com/forum/viewthread.php?tid=123",
            "https://evil-4d4y.com/forum/viewthread.php?tid=123",
            "https://4d4y.com.evil.com/forum/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/gotopost.php?pid=123456",
        )

        var callbackCount = 0
        for (raw in blockedInputs) {
            if (raw != "https://www.4d4y.com/forum/gotopost.php?pid=123456" &&
                raw != "https://evil-4d4y.com/forum/viewthread.php?tid=123" &&
                raw != "https://4d4y.com.evil.com/forum/viewthread.php?tid=123"
            ) {
                assertNull("sanitizeAllowedForumUrl 必须拦截 [$raw]", sanitizeAllowedForumUrl(raw))
            }
            assertNull("resolveAllowedForumLink 必须拦截 [$raw]", resolveAllowedForumLink(raw))
            assertFalse(
                "dispatchAllowedForumLink 必须拒绝 [$raw]",
                dispatchAllowedForumLink(
                    rawInput = raw,
                    forumServer = AppSettings.DEFAULT_FORUM_SERVER,
                    onOpenThread = { callbackCount++ },
                    onOpenForum = { callbackCount++ },
                    onOpenUserProfile = { _, _ -> callbackCount++ },
                ),
            )
        }
        assertEquals("拦截输入绝不能触发任何站内路由回调", 0, callbackCount)
    }
}
