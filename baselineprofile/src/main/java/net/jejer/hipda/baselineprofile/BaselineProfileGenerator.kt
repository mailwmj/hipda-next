package net.jejer.hipda.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile (and Startup Profile) covering the four required journeys:
 * 1. Cold start (`NextAppRoot` + `HiAdaptiveShell` + `ForumPane`)
 * 2. Forum thread list load and vertical scrolling
 * 3. Opening thread detail (`ThreadDetailPane`)
 * 4. Thread detail floor rendering (inline emojis, quotes) and vertical scrolling
 *
 * Uses [OfflineForumFixtureServer] on `127.0.0.1` so generation never touches `4d4y.com`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private lateinit var fixtureServer: OfflineForumFixtureServer

    @Before
    fun setUp() {
        fixtureServer = OfflineForumFixtureServer().apply { start() }
    }

    @After
    fun tearDown() {
        if (::fixtureServer.isInitialized) {
            fixtureServer.close()
        }
    }

    @Test
    fun generateBaselineProfile() {
        val hostIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(TARGET_PACKAGE, HOST_ACTIVITY)
            putExtra(EXTRA_FORUM_SERVER, fixtureServer.baseUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            maxIterations = 5,
            stableIterations = 2,
        ) {
            // 1. Cold start into NextAppRoot (ForumPane default feed)
            pressHome()
            startActivityAndWait(hostIntent)

            // 2. Wait for forum list items and scroll the forum list down & back up
            val threadListReady = device.wait(
                Until.hasObject(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE)),
                WAIT_TIMEOUT_MS,
            )
            check(threadListReady) {
                "Timed out waiting for forum list item '${OfflineForumFixtureServer.FIRST_THREAD_TITLE}'"
            }
            device.waitForIdle()

            scrollPrimaryList()

            // 3. Tap the first thread to open ThreadDetailPane
            val firstThread = device.wait(
                Until.findObject(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE)),
                WAIT_TIMEOUT_MS,
            ) ?: error("Unable to find first thread item after scrolling back to top")
            firstThread.click()

            val detailReady = device.wait(
                Until.hasObject(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER)),
                WAIT_TIMEOUT_MS,
            )
            check(detailReady) {
                "Timed out waiting for thread detail floor '${OfflineForumFixtureServer.FIRST_FLOOR_MARKER}'"
            }
            device.waitForIdle()

            // 4. Scroll through thread detail floors down & back up
            scrollPrimaryList()
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollPrimaryList() {
        val scrollable = device.findObject(By.scrollable(true))
        if (scrollable != null) {
            scrollable.setGestureMargin(device.displayWidth / 5)
            repeat(2) {
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
            }
            repeat(2) {
                scrollable.fling(Direction.UP)
                device.waitForIdle()
            }
        } else {
            val centerX = device.displayWidth / 2
            val topY = (device.displayHeight * 0.25f).toInt()
            val bottomY = (device.displayHeight * 0.78f).toInt()
            repeat(2) {
                device.swipe(centerX, bottomY, centerX, topY, 20)
                device.waitForIdle()
            }
            repeat(2) {
                device.swipe(centerX, topY, centerX, bottomY, 20)
                device.waitForIdle()
            }
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "net.jejer.hipda.next"
        private const val HOST_ACTIVITY = "net.jejer.hipda.baselineprofile.BaselineProfileHostActivity"
        private const val EXTRA_FORUM_SERVER = "net.jejer.hipda.baselineprofile.EXTRA_FORUM_SERVER"
        private const val WAIT_TIMEOUT_MS = 15_000L
    }
}
