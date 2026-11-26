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
    fun errorLogSanitizer_redactsPasswordsMd5CookiesAndTokens() = runBlocking {
        val raw = """
            java.io.IOException: request failed
            Cookie: cdb_auth=SUPER_SECRET_COOKIE; PHPSESSID=SESS_999; cf_clearance=CF_TOK_777
            Set-Cookie: cdb_sid=SID_888; HttpOnly
            Authorization: Bearer AUTH_SECRET_TOKEN
            form: username=alice&password=PlainPassword123&securityAnswer=SecretAns99
            json: {"passwordMd5":"0123456789abcdef0123456789abcdef","cookie":"RAW_COOKIE_VAL"}
            query: ?formhash=abc12345&cdb_auth=LEAK_AUTH&pwd=MyPwd456
        """.trimIndent()

        val sanitized = ErrorLogSanitizer.sanitize(raw)
        listOf(
            "SUPER_SECRET_COOKIE",
            "SESS_999",
            "CF_TOK_777",
            "SID_888",
            "AUTH_SECRET_TOKEN",
            "PlainPassword123",
            "SecretAns99",
            "0123456789abcdef0123456789abcdef",
            "RAW_COOKIE_VAL",
            "LEAK_AUTH",
            "MyPwd456",
            "abc12345",
        ).forEach { secret ->
            assertFalse("脱敏日志绝不能包含敏感信息 $secret:\n$sanitized", sanitized.contains(secret))
        }
        assertTrue("应保留正常诊断堆栈信息", sanitized.contains("java.io.IOException: request failed"))

        val tempDir = createTempDirectory("e12-logs").toFile()
        try {
            File(tempDir, "crash-1.log").writeText("Cookie: cdb_auth=FILE_COOKIE\npassword=FILE_SECRET\nCrashLine")
            val store = AndroidSettingsErrorLogStore(tempDir)
            val fromStore = store.readSanitizedLogs()
            assertFalse(fromStore.contains("FILE_COOKIE"))
            assertFalse(fromStore.contains("FILE_SECRET"))
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
