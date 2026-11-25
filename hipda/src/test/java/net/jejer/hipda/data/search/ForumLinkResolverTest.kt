package net.jejer.hipda.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForumLinkResolverTest {

    @Test
    fun resolve_allSixLinkTypesOnCanonical4d4yHost() {
        assertEquals(
            ResolvedForumLink.Forum(fid = 2),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/forumdisplay.php?fid=2"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "3478055", page = 1),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/viewthread.php?tid=3478055"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "3478055", page = 4),
            ForumLinkResolver.resolve("http://4d4y.com/forum/viewthread.php?tid=3478055&page=4#pid99"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "3478055", page = 1),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/viewthread.php?tid=3478055&page=0"),
        )
        assertEquals(
            ResolvedForumLink.ThreadLastPage(tid = "3468951"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=3468951"),
        )
        assertEquals(
            ResolvedForumLink.ThreadLastPage(tid = "3468951"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/redirect.php?tid=3468951&from=favorites&goto=lastpost#lastpost"),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = "3474137", pid = "74880921"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=3474137&pid=74880921"),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = "3474137", pid = "74880921"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/redirect.php?pid=74880921&amp;ptid=3474137&amp;goto=findpost"),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = null, pid = "123456"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/gotopost.php?pid=123456"),
        )
        assertEquals(
            ResolvedForumLink.User(uid = "10000"),
            ForumLinkResolver.resolve("https://www.4d4y.com/forum/space.php?uid=10000"),
        )
    }

    @Test
    fun resolve_supportsLegacyHipdaCustomForumServerAndSchemeLessUrls() {
        assertEquals(
            ResolvedForumLink.Forum(fid = 6),
            ForumLinkResolver.resolve("http://www.hi-pda.com/forum/forumdisplay.php?fid=6"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "1579403", page = 2),
            ForumLinkResolver.resolve("https://hi-pda.com/forum/viewthread.php?tid=1579403&page=2"),
        )
        assertEquals(
            ResolvedForumLink.User(uid = "54321"),
            ForumLinkResolver.resolve("www.hi-pda.com/forum/space.php?uid=54321"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "8888", page = 1),
            ForumLinkResolver.resolve("4d4y.com/forum/viewthread.php?tid=8888"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "9999", page = 1),
            ForumLinkResolver.resolve("//www.4d4y.com/forum/viewthread.php?tid=9999"),
        )

        // Custom forumServer (both with and without /forum suffix, plus loopback with port)
        assertEquals(
            ResolvedForumLink.Thread(tid = "2026", page = 3),
            ForumLinkResolver.resolve(
                rawInput = "https://bbs.custom.example/forum/viewthread.php?tid=2026&page=3",
                forumServer = "https://bbs.custom.example",
            ),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = "2026", pid = "9001"),
            ForumLinkResolver.resolve(
                rawInput = "https://bbs.custom.example/forum/redirect.php?goto=findpost&ptid=2026&pid=9001",
                forumServer = "https://bbs.custom.example/forum",
            ),
        )
        assertEquals(
            ResolvedForumLink.Forum(fid = 7),
            ForumLinkResolver.resolve(
                rawInput = "http://127.0.0.1:8080/forum/forumdisplay.php?fid=7",
                forumServer = "http://127.0.0.1:8080",
            ),
        )
        assertEquals(
            ResolvedForumLink.User(uid = "777"),
            ForumLinkResolver.resolve(
                rawInput = "  bbs.custom.example/forum/space.php?uid=777  ",
                forumServer = "https://bbs.custom.example",
            ),
        )
    }

    @Test
    fun resolve_supportsRelativeForumScriptPaths() {
        assertEquals(
            ResolvedForumLink.Forum(fid = 2),
            ForumLinkResolver.resolve("forumdisplay.php?fid=2"),
        )
        assertEquals(
            ResolvedForumLink.Thread(tid = "3478055", page = 5),
            ForumLinkResolver.resolve("/forum/viewthread.php?tid=3478055&page=5"),
        )
        assertEquals(
            ResolvedForumLink.ThreadLastPage(tid = "3468951"),
            ForumLinkResolver.resolve("forum/redirect.php?goto=lastpost&tid=3468951"),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = "3474137", pid = "74880921"),
            ForumLinkResolver.resolve("./redirect.php?goto=findpost&ptid=3474137&pid=74880921"),
        )
        assertEquals(
            ResolvedForumLink.Post(tid = null, pid = "55555"),
            ForumLinkResolver.resolve("/gotopost.php?pid=55555"),
        )
        assertEquals(
            ResolvedForumLink.User(uid = "10000"),
            ForumLinkResolver.resolve("space.php?uid=10000"),
        )
    }

    @Test
    fun resolve_rejectsExternalSpoofedInvalidSchemeMalformedAndPlainTextInputs() {
        val rejectedInputs = listOf(
            "",
            "   ",
            "iPhone 16",
            "数码 交易",
            "3478055",
            "tid=3478055",
            "viewthread.php?tid=123 附带文字",
            "https://example.com/forum/viewthread.php?tid=123",
            "https://evil-4d4y.com/forum/viewthread.php?tid=123",
            "https://4d4y.com.evil.com/forum/viewthread.php?tid=123",
            "https://hi-pda.com.evil.org/forum/forumdisplay.php?fid=2",
            "https://www.4d4y.com/other/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/index.php",
            "https://www.4d4y.com/forum/search.php?srchtxt=abc",
            "https://www.4d4y.com/forum/logging.php?action=login",
            "javascript:alert(1)",
            "ftp://www.4d4y.com/forum/viewthread.php?tid=123",
            "file:///forum/viewthread.php?tid=123",
            "https://www.4d4y.com/forum/forumdisplay.php?fid=0",
            "https://www.4d4y.com/forum/forumdisplay.php?fid=-2",
            "https://www.4d4y.com/forum/forumdisplay.php?fid=abc",
            "https://www.4d4y.com/forum/viewthread.php?tid=0",
            "https://www.4d4y.com/forum/viewthread.php?tid=+123",
            "https://www.4d4y.com/forum/viewthread.php?tid=abc",
            "https://www.4d4y.com/forum/redirect.php?goto=lastpost",
            "https://www.4d4y.com/forum/redirect.php?goto=findpost&pid=12345",
            "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=12345",
            "https://www.4d4y.com/forum/redirect.php?goto=other&tid=12345",
            "https://www.4d4y.com/forum/gotopost.php?pid=0",
            "https://www.4d4y.com/forum/space.php?username=HiUser",
            "https://www.4d4y.com/forum/space.php?uid=0",
        )

        for (input in rejectedInputs) {
            assertNull("Expected null for '$input'", ForumLinkResolver.resolve(input))
        }
    }
}
