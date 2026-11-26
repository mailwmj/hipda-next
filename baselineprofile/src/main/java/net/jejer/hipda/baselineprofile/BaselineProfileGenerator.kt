package net.jejer.hipda.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
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
        Log.i(TAG, "OfflineForumFixtureServer started at ${fixtureServer.baseUrl}")
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
            Log.i(TAG, "Iteration start: launching $HOST_ACTIVITY with ${fixtureServer.baseUrl}")
            pressHome()
            startActivityAndWait(hostIntent)

            // 2. Wait for forum list items and scroll the forum list down & back up
            val threadListReady = device.wait(
                Until.hasObject(By.textContains(OfflineForumFixtureServer.THREAD_TITLE_PREFIX)),
                WAIT_TIMEOUT_MS,
            )
            check(threadListReady) {
                "Timed out waiting for forum list item '${OfflineForumFixtureServer.THREAD_TITLE_PREFIX}'"
            }
            device.waitForIdle()
            Log.i(TAG, "Forum list ready; scrolling forum list")

            scrollListVertically()

            // 3. Tap a visible thread safely inside the middle viewport (clear of floating top bar & bottom pill)
            val clickableThread = findThreadRowInSafeViewport()
                ?: error("Unable to find visible thread row matching '${OfflineForumFixtureServer.THREAD_TITLE_PREFIX}'")
            Log.i(TAG, "Clicking thread item: ${clickableThread.text} at ${clickableThread.visibleCenter}")
            clickableThread.click()

            val detailReady = device.wait(
                Until.hasObject(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER)),
                WAIT_TIMEOUT_MS,
            )
            check(detailReady) {
                "Timed out waiting for thread detail floor '${OfflineForumFixtureServer.FIRST_FLOOR_MARKER}'"
            }
            device.waitForIdle()
            Log.i(TAG, "Thread detail ready; scrolling floors")

            // 4. Scroll through thread detail floors down & back up
            scrollListVertically()
            Log.i(TAG, "Iteration complete")
        }
    }

    private fun MacrobenchmarkScope.findThreadRowInSafeViewport(): UiObject2? {
        val minY = (device.displayHeight * 0.28f).toInt()
        val maxY = (device.displayHeight * 0.76f).toInt()
        val candidates = device.findObjects(By.textContains(OfflineForumFixtureServer.THREAD_TITLE_PREFIX))
        val safe = candidates.firstOrNull { obj ->
            val cy = obj.visibleCenter.y
            cy in minY..maxY
        }
        if (safe != null) return safe
        return candidates.firstOrNull()
    }

    private fun MacrobenchmarkScope.scrollListVertically() {
        val centerX = device.displayWidth / 2
        val upperY = (device.displayHeight * 0.34f).toInt()
        val lowerY = (device.displayHeight * 0.72f).toInt()
        repeat(2) {
            device.swipe(centerX, lowerY, centerX, upperY, 18)
            device.waitForIdle()
        }
        repeat(2) {
            device.swipe(centerX, upperY, centerX, lowerY, 18)
            device.waitForIdle()
        }
    }

    companion object {
        private const val TAG = "BaselineProfile"
        private const val TARGET_PACKAGE = "net.jejer.hipda.next"
        private const val HOST_ACTIVITY = "net.jejer.hipda.baselineprofile.BaselineProfileHostActivity"
        private const val EXTRA_FORUM_SERVER = "net.jejer.hipda.baselineprofile.EXTRA_FORUM_SERVER"
        private const val WAIT_TIMEOUT_MS = 25_000L
    }
}
