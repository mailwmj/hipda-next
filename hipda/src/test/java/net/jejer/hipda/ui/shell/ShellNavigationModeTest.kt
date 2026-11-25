package net.jejer.hipda.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationModeTest {

    @Test
    fun shellNavigationMode_matchesAllSpecBreakpoints() {
        val bottomPillCases = listOf(
            340f to 800f,
            411f to 891f,
            599f to 800f,
            600f to 800f,
            700f to 900f,
            744f to 900f,
            839f to 900f,
        )
        bottomPillCases.forEach { (width, height) ->
            assertEquals(
                "Expected BottomPill for ${width}x$height",
                ShellNavigationMode.BottomPill,
                shellNavigationMode(widthDp = width, heightDp = height, hingeSeparating = false),
            )
        }

        val railCases = listOf(
            840f to 900f,
            900f to 1120f,
            1280f to 800f,
        )
        railCases.forEach { (width, height) ->
            assertEquals(
                "Expected Rail for ${width}x$height",
                ShellNavigationMode.Rail,
                shellNavigationMode(widthDp = width, heightDp = height, hingeSeparating = false),
            )
        }

        val compactHeightCases = listOf(
            891f to 411f,
            968f to 411f,
            1280f to 479.9f,
        )
        compactHeightCases.forEach { (width, height) ->
            assertEquals(
                "Expected CompactPill for ${width}x$height",
                ShellNavigationMode.CompactPill,
                shellNavigationMode(widthDp = width, heightDp = height, hingeSeparating = false),
            )
        }

        assertEquals(
            "Height at 480dp exact boundary should not be compact",
            ShellNavigationMode.Rail,
            shellNavigationMode(widthDp = 840f, heightDp = 480f, hingeSeparating = false),
        )
        assertEquals(
            "Height at 480dp exact boundary with width < 840dp should be BottomPill",
            ShellNavigationMode.BottomPill,
            shellNavigationMode(widthDp = 839.9f, heightDp = 480f, hingeSeparating = false),
        )
    }

    @Test
    fun shellNavigationMode_hingeSeparatingForcesCompactPill() {
        assertEquals(
            ShellNavigationMode.CompactPill,
            shellNavigationMode(widthDp = 700f, heightDp = 900f, hingeSeparating = true),
        )
        assertEquals(
            ShellNavigationMode.CompactPill,
            shellNavigationMode(widthDp = 840f, heightDp = 900f, hingeSeparating = true),
        )
        assertEquals(
            ShellNavigationMode.CompactPill,
            shellNavigationMode(widthDp = 411f, heightDp = 891f, hingeSeparating = true),
        )
    }

    @Test
    fun showLabelForUnselected_switchesAt360dpBoundary() {
        assertFalse(showLabelForUnselected(340f))
        assertFalse(showLabelForUnselected(359f))
        assertFalse(showLabelForUnselected(359.9f))
        assertTrue(showLabelForUnselected(360f))
        assertTrue(showLabelForUnselected(411f))
    }
}
