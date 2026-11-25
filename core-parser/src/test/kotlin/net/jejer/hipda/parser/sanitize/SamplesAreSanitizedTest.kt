package net.jejer.hipda.parser.sanitize

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SamplesAreSanitizedTest {

    @Test
    fun repositorySampleDirectoriesAreSanitized() {
        val repoRoot = resolveRepositoryRoot()
        val sampleDirs = SampleLeakScanner.DEFAULT_SAMPLE_RELATIVE_PATHS.map { repoRoot.resolve(it) }
        assertDirectoriesSanitized(sampleDirs)
    }

    @Test
    fun emptyAndNonExistentSampleDirectoriesPass(@TempDir tempDir: Path) {
        val missingDir = tempDir.resolve("does_not_exist")
        assertDirectoriesSanitized(listOf(tempDir, missingDir))
    }

    @Test
    fun unsanitizedSampleInTempDirectoryFailsWithFileNameLeakTypeAndHint(@TempDir tempDir: Path) {
        val leakedThreadHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>
                var discuz_uid = '54321';
                document.cookie = "cdb_auth=realSecretCookie; cf_clearance=realCfToken";
              </script>
            </head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
              </div>
              <input type="hidden" name="formhash" value="a1b2c3d4" />
            </body>
            </html>
        """.trimIndent()

        val leakedSmsHtml = """
            <!DOCTYPE html>
            <html>
            <body>
              <ul class="pm_list">
                <li class="s_clear">
                  <p class="cite"><cite><a href="space.php?uid=67890">PeerAlice</a></cite> 2026-11-20</p>
                  <div class="summary">这是一条未脱敏的私人短消息</div>
                </li>
              </ul>
            </body>
            </html>
        """.trimIndent()

        val threadFile = tempDir.resolve("leaked_thread.html")
        val htmFile = tempDir.resolve("leaked_page.htm")
        val xmlFile = tempDir.resolve("login_rsp.xml")
        val smsDir = Files.createDirectories(tempDir.resolve("sms"))
        val smsFile = smsDir.resolve("leaked_sms.html")
        Files.writeString(threadFile, leakedThreadHtml, StandardCharsets.UTF_8)
        Files.writeString(htmFile, """<html><head><script>var discuz_uid = '77777';</script></head></html>""", StandardCharsets.UTF_8)
        Files.writeString(
            xmlFile,
            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来 <input type="hidden" name="formhash" value="a1b2c3d4" />]]></root>""",
            StandardCharsets.UTF_8,
        )
        Files.writeString(smsFile, leakedSmsHtml, StandardCharsets.UTF_8)

        val error = assertThrows(AssertionError::class.java) {
            assertDirectoriesSanitized(listOf(tempDir))
        }
        val message = error.message.orEmpty()

        assertTrue(message.contains("leaked_thread.html"), "Failure message should include thread filename: $message")
        assertTrue(message.contains("leaked_page.htm"), "Failure message should include .htm filename: $message")
        assertTrue(message.contains("login_rsp.xml"), "Failure message should include .xml filename: $message")
        assertTrue(message.contains("leaked_sms.html"), "Failure message should include sms filename: $message")
        assertTrue(message.contains(LeakType.FORMHASH.name), "Failure message should include FORMHASH: $message")
        assertTrue(message.contains(LeakType.DISCUZ_UID.name), "Failure message should include DISCUZ_UID: $message")
        assertTrue(message.contains(LeakType.COOKIE_VALUE.name), "Failure message should include COOKIE_VALUE: $message")
        assertTrue(message.contains(LeakType.SMS_CONTENT.name), "Failure message should include SMS_CONTENT: $message")
        assertTrue(message.contains("用 HtmlSanitizer 重新导出"), "Failure message should instruct re-export: $message")
    }

    @Test
    fun sanitizedSamplesInTempDirectoryPass(@TempDir tempDir: Path) {
        val identity = Identity(uid = "54321", username = "RealUser")
        val rawSms = """
            <!DOCTYPE html>
            <html>
            <head><script>var discuz_uid = '54321';</script></head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=54321" class="noborder">RealUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
              </div>
              <ul class="pm_list">
                <li class="s_clear">
                  <a class="avatar" href="space.php?uid=67890"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/06/78/90_avatar_middle.jpg" /></a>
                  <p class="cite"><cite><a href="space.php?uid=67890">PeerAlice</a></cite> 2026-11-20</p>
                  <div class="summary">这是一条私密的短消息</div>
                </li>
              </ul>
            </body>
            </html>
        """.trimIndent()

        val sanitized = HtmlSanitizer.sanitize(rawSms, PageType.SMS_DETAIL, identity)
        Files.writeString(tempDir.resolve("sanitized_sms.html"), sanitized, StandardCharsets.UTF_8)

        assertDirectoriesSanitized(listOf(tempDir))
    }

    @Test
    fun scanDirectoryDetectsLeakedSelfUidAndPassesAfterSanitization(@TempDir tempDir: Path) {
        val fakeUid = "424242"
        val rawProfile = """
            <!DOCTYPE html>
            <html>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <a href="my.php?item=buddylist&amp;newbuddyid=$fakeUid&amp;buddysubmit=yes" id="addbuddy">加为好友</a>
              <a href="viewthread.php?tid=9${fakeUid}0">无关长数字</a>
            </body>
            </html>
        """.trimIndent()
        val file = tempDir.resolve("user_profile.html")
        Files.writeString(file, rawProfile, StandardCharsets.UTF_8)

        val leakedReports = SampleLeakScanner.scanDirectory(tempDir, selfUid = fakeUid)
        assertTrue(leakedReports.size == 1)
        assertTrue(leakedReports.single().leaks.any { it.type == LeakType.SELF_UID })

        val scrubbed = HtmlSanitizer.sanitize(rawProfile, PageType.USER_INFO, Identity(uid = fakeUid, username = ""))
        Files.writeString(file, scrubbed, StandardCharsets.UTF_8)

        assertTrue(SampleLeakScanner.scanDirectory(tempDir, selfUid = fakeUid).isEmpty())
        assertDirectoriesSanitized(listOf(tempDir))
    }

    private fun assertDirectoriesSanitized(directories: List<Path>) {
        val reports = SampleLeakScanner.scanDirectories(directories)
        if (reports.isNotEmpty()) {
            fail<Unit>(SampleLeakScanner.formatLeakReport(reports))
        }
    }

    private fun resolveRepositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        return Path.of("").toAbsolutePath().normalize()
    }
}
