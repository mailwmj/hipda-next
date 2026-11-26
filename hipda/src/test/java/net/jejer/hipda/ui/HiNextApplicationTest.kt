package net.jejer.hipda.ui

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.di.NotificationWorkModule
import net.jejer.hipda.feature.settings.AndroidSettingsErrorLogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34])
class HiNextApplicationTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun hiNextApplication_extendsAndroidApplicationDirectlyWithoutLegacyBaseClass() {
        val rawApp: Application = ApplicationProvider.getApplicationContext()
        assertTrue(rawApp is HiNextApplication)

        // Hilt bytecode transform inserts Hilt_HiNextApplication between HiNextApplication and Application.
        val chain = generateSequence<Class<*>>(HiNextApplication::class.java) { it.superclass }
            .takeWhile { it != Any::class.java }
            .map { it.name }
            .toList()
        assertFalse("Legacy HiApplication must not appear in hierarchy: $chain", chain.any { it.endsWith(".HiApplication") })
        val directBase = HiNextApplication::class.java.superclass
        assertTrue(
            "Expected HiNextApplication superclass to be Application or Hilt_HiNextApplication->Application, got $chain",
            directBase == Application::class.java ||
                (directBase?.name == "net.jejer.hipda.ui.Hilt_HiNextApplication" && directBase.superclass == Application::class.java),
        )
    }

    @Test
    fun hiNextApplication_tracksActivityVisibilityForNotificationWorkModule() {
        val app = ApplicationProvider.getApplicationContext<HiNextApplication>()
        val visibilityProvider = NotificationWorkModule.provideAppVisibilityProvider()
        val activityA = Activity()
        val activityB = Activity()

        assertFalse(HiNextApplication.isAppVisible())
        assertFalse(visibilityProvider.isAppVisible())

        app.onActivityStarted(activityA)
        assertTrue(HiNextApplication.isAppVisible())
        assertTrue(visibilityProvider.isAppVisible())

        app.onActivityStarted(activityB)
        app.onActivityStopped(activityA)
        assertTrue("Activity transition must keep app visible while second activity is started", visibilityProvider.isAppVisible())

        app.onActivityStopped(activityB)
        assertFalse(HiNextApplication.isAppVisible())
        assertFalse(visibilityProvider.isAppVisible())

        // Unbalanced extra stop must clamp at 0 and not underflow
        app.onActivityStopped(activityB)
        assertFalse(HiNextApplication.isAppVisible())
        app.onActivityStarted(activityA)
        assertTrue(HiNextApplication.isAppVisible())
        app.onActivityStopped(activityA)
        assertFalse(HiNextApplication.isAppVisible())
    }

    @Test
    fun hiNextApplication_installsCrashLogHandlerCompatibleWithSettingsErrorLogStore() = runBlocking {
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(
            "HiNextApplication.onCreate must install CrashLogExceptionHandler",
            installed is CrashLogExceptionHandler,
        )

        val logsDir = File(tempFolder.newFolder("app_files"), CrashLogExceptionHandler.LOGS_DIR_NAME)
        val delegatedThread = AtomicReference<Thread?>()
        val delegatedError = AtomicReference<Throwable?>()
        val handler = CrashLogExceptionHandler(
            logsDirProvider = { logsDir },
            deviceInfoProvider = { "设备名称 : TestDevice\n客户端版本 : 5.0.05" },
            delegate = Thread.UncaughtExceptionHandler { thread, throwable ->
                delegatedThread.set(thread)
                delegatedError.set(throwable)
            },
            nowProvider = { Date(1_700_000_000_000L) },
        )

        val simulatedCrash = IllegalStateException("Crash boom uid=987654 cookie=cdb_auth=secret123")
        val currentThread = Thread.currentThread()
        handler.uncaughtException(currentThread, simulatedCrash)

        assertSame(currentThread, delegatedThread.get())
        assertSame(simulatedCrash, delegatedError.get())

        val createdFiles = logsDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertEquals(1, createdFiles.size)
        val rawOnDisk = createdFiles.single().readText()
        assertFalse("On-disk crash log must redact raw uid", rawOnDisk.contains("987654"))
        assertFalse("On-disk crash log must redact cookie secret", rawOnDisk.contains("secret123"))

        val readBack = AndroidSettingsErrorLogStore(logsDir).readSanitizedLogs()
        assertNotNull(readBack)
        assertTrue("Settings error log store must read crash log written by handler", readBack.contains("IllegalStateException"))
        assertTrue("Settings error log store must preserve device info header", readBack.contains("TestDevice"))
        assertFalse(readBack.contains("987654"))
        assertFalse(readBack.contains("secret123"))
    }
}
