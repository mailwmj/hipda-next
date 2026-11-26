package net.jejer.hipda.feature.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsValidatorsAndSupportTest {

    @Test
    fun forumServerAndTailUrl_allowHttpHttpsAndBlockAllDangerousSchemes() {
        assertEquals("https://www.4d4y.com", SettingsUrlValidator.validateForumServer("https://www.4d4y.com/"))
        assertEquals("http://127.0.0.1:8080", SettingsUrlValidator.validateForumServer("http://127.0.0.1:8080/"))
        assertEquals("", SettingsUrlValidator.validateTailUrl(""))
        assertEquals("", SettingsUrlValidator.validateTailUrl("   "))
        assertEquals("https://example.com/post?id=1", SettingsUrlValidator.validateTailUrl("https://example.com/post?id=1"))

        // S1：论坛服务器额外拒绝 ?query、#fragment 与 userinfo；小尾巴链接同样拒绝 userinfo
        listOf(
            "https://www.4d4y.com?x=1",
            "https://www.4d4y.com/?a=b",
            "https://www.4d4y.com#top",
            "https://www.4d4y.com/#section",
            "https://user:pass@www.4d4y.com",
            "https://user@www.4d4y.com",
        ).forEach { bad ->
            assertNull("论坛服务器必须拦截 query/fragment/userinfo: $bad", SettingsUrlValidator.validateForumServer(bad))
        }
        assertNull("小尾巴链接必须拦截 userinfo", SettingsUrlValidator.validateTailUrl("https://user:pass@example.com/p"))
        assertNull("小尾巴链接必须拦截纯用户名 userinfo", SettingsUrlValidator.validateTailUrl("https://user@example.com/p"))

        val dangerous = listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///etc/passwd",
            "FILE:///data/local/tmp/a",
            "content://com.android.contacts/contacts/1",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "mailto:alice@example.com",
            "ftp://files.example.com/a",
            "tel:10086",
            "www.4d4y.com",
            "https://",
            "http:///only-path",
            "http://\\evil.example",
            "https://example.com/a\nb",
            "https://example.com/a b",
            "https://example.com/\u0000x",
        )
        dangerous.forEach { bad ->
            assertNull("论坛服务器必须拦截非法地址: $bad", SettingsUrlValidator.validateForumServer(bad))
            assertNull("小尾巴链接必须拦截非法地址: $bad", SettingsUrlValidator.validateTailUrl(bad))
        }
        assertNull("论坛服务器不允许空白", SettingsUrlValidator.validateForumServer("   "))
    }

    @Test
    fun errorLogSanitizer_redactsAllUidAvatarQuotedJsonEncodedAndTokenVariants() = runBlocking {
        // 全量使用合成假值（绝不含真实 UID 或真实用户名）
        val raw = """
            java.io.IOException: request failed
            at net.jejer.hipda.feature.settings.SettingsViewModel.loadErrorLogs(SettingsViewModel.kt:117)
            Cookie: cdb_auth=SYNTH_CK_AUTH; cdb_oldtopics=SYNTH_OLDT; __cf_bm=SYNTH_CFBM; cf_clearance=SYNTH_CFCLR; PHPSESSID=SYNTH_SESS
            Set-Cookie: cdb_sid=SYNTH_CK_SID; HttpOnly
            Authorization: Bearer SYNTH_BEARER_TOK
            url1: https://example.invalid/forum/space.php?uid=765432&srchuid=876543&authorid=654321&touid=543210&ownerUid=432109&activeUid=321098
            url2: https://example.invalid/forum/space-uid-918273.html
            avatar1: https://example.invalid/uc_server/data/avatar/000/76/54/32_avatar_middle.jpg
            avatar2: https://example.invalid/uc_server/data/avatar/000/87/65/43
            json1: {"uid":"765432","ownerUid":876543,"formhash":"SYNTH_J_FH","m_formhash":"SYNTH_J_MFH","cdb_auth":"SYNTH_J_CA","cdb_sid":"SYNTH_J_CS","cdb_visitedfid":"SYNTH_J_VF","password":"a\"SYNTH_ESC_PW","passwordMd5":"0123456789abcdef0123456789abcdef","auth":"SYNTH_J_AUTH","cookie":"SYNTH_J_CK"}
            quoted: password="SYNTH_Q_PW1" passwd='SYNTH_Q_PW2\'TAIL' cdb_auth="SYNTH_Q_CA" formhash="SYNTH_Q_FH" m_formhash='SYNTH_Q_MFH'
            unquoted: formhash=SYNTH_U_FH&m_formhash=SYNTH_U_MFH&hash=SYNTH_U_HASH&sid=SYNTH_U_SID&auth=SYNTH_U_AUTH&cdb_cookietime=SYNTH_U_CT&__cf_bm=SYNTH_U_CFBM&cf_clearance=SYNTH_U_CFCLR&pwd=SYNTH_U_PWD
            html: <input type="hidden" name="formhash" value="SYNTH_IN_FH"><input name="m_formhash" value="SYNTH_IN_MFH"><input name="password" value="SYNTH_IN_PW">
            encoded: https%3A%2F%2Fexample.invalid%2Fspace.php%3Fuid%3D246813%26srchuid%3D135792%26authorid%3D975318%26formhash%3DSYNTH_E_FH%26m_formhash%3DSYNTH_E_MFH%26cdb_auth%3DSYNTH_E_CA%26password%3DSYNTH_E_PW%26__cf_bm%3DSYNTH_E_CFBM
            obj1: SavedAccount(uid=765432, username=synth_user_alpha, passwordMd5=***, securityAnswer=***)
            obj2: BlacklistSnapshot(ownerUid=642097, blocked=[])
            text1: UID: 852963
            text2: Cannot activate unknown account uid: 741852
            script: var discuz_uid = parseInt('963852');
        """.trimIndent()

        val sanitized = ErrorLogSanitizer.sanitize(raw)
        listOf(
            "SYNTH_CK_AUTH", "SYNTH_OLDT", "SYNTH_CFBM", "SYNTH_CFCLR", "SYNTH_SESS",
            "SYNTH_CK_SID", "SYNTH_BEARER_TOK",
            "765432", "876543", "654321", "543210", "432109", "321098", "918273",
            "000/76/54/32", "000/87/65/43",
            "SYNTH_J_FH", "SYNTH_J_MFH", "SYNTH_J_CA", "SYNTH_J_CS", "SYNTH_J_VF",
            "SYNTH_ESC_PW", "0123456789abcdef0123456789abcdef", "SYNTH_J_AUTH", "SYNTH_J_CK",
            "SYNTH_Q_PW1", "SYNTH_Q_PW2", "TAIL", "SYNTH_Q_CA", "SYNTH_Q_FH", "SYNTH_Q_MFH",
            "SYNTH_U_FH", "SYNTH_U_MFH", "SYNTH_U_HASH", "SYNTH_U_SID", "SYNTH_U_AUTH",
            "SYNTH_U_CT", "SYNTH_U_CFBM", "SYNTH_U_CFCLR", "SYNTH_U_PWD",
            "SYNTH_IN_FH", "SYNTH_IN_MFH", "SYNTH_IN_PW",
            "246813", "135792", "975318", "SYNTH_E_FH", "SYNTH_E_MFH", "SYNTH_E_CA", "SYNTH_E_PW", "SYNTH_E_CFBM",
            "synth_user_alpha", "642097", "852963", "741852", "963852",
        ).forEach { secret ->
            assertFalse("脱敏日志绝不能残留敏感信息 [$secret]:\n$sanitized", sanitized.contains(secret))
        }
        assertTrue("应保留正常异常堆栈行", sanitized.contains("java.io.IOException: request failed"))
        assertTrue("应保留正常调用栈行", sanitized.contains("SettingsViewModel.loadErrorLogs(SettingsViewModel.kt:117)"))

        val tempDir = createTempDirectory("e12-logs").toFile()
        try {
            File(tempDir, "crash-1.log").writeText("space.php?uid=765432\npassword=\"FILE_Q_SECRET\"\nm_formhash=FILE_MFH\nCrashLine")
            val fromStore = AndroidSettingsErrorLogStore(tempDir).readSanitizedLogs()
            assertFalse(fromStore.contains("765432"))
            assertFalse(fromStore.contains("FILE_Q_SECRET"))
            assertFalse(fromStore.contains("FILE_MFH"))
            assertTrue(fromStore.contains("CrashLine"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun notificationSettingsOpener_handlesPrimaryFallbackAndActivityNotFoundSafely() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val launched = mutableListOf<String>()

        val primaryOk = AndroidSystemNotificationSettingsOpener(context, sdkInt = Build.VERSION_CODES.O) { intent ->
            launched += intent.action.orEmpty()
        }
        assertTrue(primaryOk.open())
        assertEquals(listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS), launched)

        launched.clear()
        val fallbackOk = AndroidSystemNotificationSettingsOpener(context, sdkInt = Build.VERSION_CODES.O) { intent ->
            launched += intent.action.orEmpty()
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) throw ActivityNotFoundException("no primary")
        }
        assertTrue(fallbackOk.open())
        assertEquals(
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            launched,
        )

        val bothFail = AndroidSystemNotificationSettingsOpener(context, sdkInt = Build.VERSION_CODES.O) { _: Intent ->
            throw ActivityNotFoundException("no settings app")
        }
        assertFalse(bothFail.open())
    }

    @Test
    fun directorySizeAndDeleteContents_computesAndCleansTempDirectory() {
        val dir = createTempDirectory("e12-cache").toFile()
        try {
            File(dir, "a.bin").writeBytes(ByteArray(2048))
            val sub = File(dir, "nested").apply { mkdirs() }
            File(sub, "b.bin").writeBytes(ByteArray(1024))
            assertEquals(3072L, directorySizeBytes(dir))
            assertNotNull(formatCacheSizeBytes(3072L))
            deleteDirectoryContents(dir)
            assertEquals(0L, directorySizeBytes(dir))
            assertTrue(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
