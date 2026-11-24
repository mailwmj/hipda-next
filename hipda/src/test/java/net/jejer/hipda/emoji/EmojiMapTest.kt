package net.jejer.hipda.emoji

import android.app.Application
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.vanniktech.emoji.EmojiHandler
import com.vanniktech.emoji.emoji.Default
import com.vanniktech.emoji.emoji.Dumb
import com.vanniktech.emoji.emoji.DumbDark
import com.vanniktech.emoji.emoji.Monkey
import com.vanniktech.emoji.emoji.MonkeyDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EmojiMapTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun drawableFor_matchesLegacyEmojiHandlerInLightAndDarkModes() {
        EmojiHandler.init(true)
        val lightLegacyMap = readLegacyMap("EMOJIS_MAP")

        EmojiHandler.init(false)
        val darkLegacyMap = readLegacyMap("EMOJIS_MAP")

        val expectedAllCodes = (Default.EMOJIS.toList() + Monkey.EMOJIS.toList() + Dumb.EMOJIS.toList()).toSet()
        assertEquals(61, expectedAllCodes.size)
        assertEquals(expectedAllCodes, lightLegacyMap.keys)
        assertEquals(expectedAllCodes, darkLegacyMap.keys)

        // Verify light theme parity against both EmojiHandler.EMOJIS_MAP and EmojiHandler.addEmojis
        EmojiHandler.init(true)
        for ((code, expectedResId) in lightLegacyMap) {
            val spanResId = resolveViaLegacyAddEmojis(code)
            assertEquals("Legacy addEmojis mismatch in light mode for $code", expectedResId, spanResId)

            val actualResId = EmojiMap.drawableFor(code = code, dark = false)
            assertEquals("EmojiMap light mismatch for $code", expectedResId, actualResId)
            assertNotNull("Drawable resource missing for $code (light)", ContextCompat.getDrawable(context, actualResId!!))
        }

        // Verify dark theme parity against both EmojiHandler.EMOJIS_MAP and EmojiHandler.addEmojis
        EmojiHandler.init(false)
        for ((code, expectedResId) in darkLegacyMap) {
            val spanResId = resolveViaLegacyAddEmojis(code)
            assertEquals("Legacy addEmojis mismatch in dark mode for $code", expectedResId, spanResId)

            val actualResId = EmojiMap.drawableFor(code = code, dark = true)
            assertEquals("EmojiMap dark mismatch for $code", expectedResId, actualResId)
            assertNotNull("Drawable resource missing for $code (dark)", ContextCompat.getDrawable(context, actualResId!!))
        }

        // Explicit group-level light/dark resource checks against legacy arrays
        for (i in Default.EMOJIS.indices) {
            val code = Default.EMOJIS[i]
            assertEquals(Default.DRAWABLES[i], EmojiMap.drawableFor(code, dark = false))
            assertEquals(Default.DRAWABLES[i], EmojiMap.drawableFor(code, dark = true))
        }
        for (i in Monkey.EMOJIS.indices) {
            val code = Monkey.EMOJIS[i]
            assertEquals(Monkey.DRAWABLES[i], EmojiMap.drawableFor(code, dark = false))
            assertEquals(MonkeyDark.DRAWABLES[i], EmojiMap.drawableFor(code, dark = true))
            assertNotEquals("Monkey dark drawable should differ from light for $code", Monkey.DRAWABLES[i], MonkeyDark.DRAWABLES[i])
        }
        for (i in Dumb.EMOJIS.indices) {
            val code = Dumb.EMOJIS[i]
            assertEquals(Dumb.DRAWABLES[i], EmojiMap.drawableFor(code, dark = false))
            assertEquals(DumbDark.DRAWABLES[i], EmojiMap.drawableFor(code, dark = true))
            assertNotEquals("Dumb dark drawable should differ from light for $code", Dumb.DRAWABLES[i], DumbDark.DRAWABLES[i])
        }
    }

    @Test
    fun drawableFor_isStatelessAndIndependentOfLegacyThemeInit() {
        val monkeyCode = Monkey.EMOJIS.first()
        val dumbCode = Dumb.EMOJIS.first()

        // Even when legacy EmojiHandler is initialized in light mode, EmojiMap(dark = true) returns dark drawables.
        EmojiHandler.init(true)
        assertEquals(MonkeyDark.DRAWABLES.first(), EmojiMap.drawableFor(monkeyCode, dark = true))
        assertEquals(DumbDark.DRAWABLES.first(), EmojiMap.drawableFor(dumbCode, dark = true))
        assertEquals(Monkey.DRAWABLES.first(), EmojiMap.drawableFor(monkeyCode, dark = false))
        assertEquals(Dumb.DRAWABLES.first(), EmojiMap.drawableFor(dumbCode, dark = false))

        // Even when legacy EmojiHandler is initialized in dark mode, EmojiMap(dark = false) returns light drawables.
        EmojiHandler.init(false)
        assertEquals(Monkey.DRAWABLES.first(), EmojiMap.drawableFor(monkeyCode, dark = false))
        assertEquals(Dumb.DRAWABLES.first(), EmojiMap.drawableFor(dumbCode, dark = false))
        assertEquals(MonkeyDark.DRAWABLES.first(), EmojiMap.drawableFor(monkeyCode, dark = true))
        assertEquals(DumbDark.DRAWABLES.first(), EmojiMap.drawableFor(dumbCode, dark = true))
    }

    @Test
    fun drawableFor_returnsNullForUnknownCodes() {
        val unknownCodes = listOf(
            "",
            " ",
            "hello",
            ":",
            ":unknown:",
            ":SWEAT:",
            ":p",
            ":d",
            ":O",
            ":) ",
            " :)",
            ":)extra",
            "{:2_40:}",
            "{:2_57:}",
            "{:3_56:}",
            "{:3_81:}",
            "{:9_99:}",
            "[attachimg]123[/attachimg]",
            "default_smile",
            "coolmonkey_01",
            "grapeman_01",
        )
        val nonPrefixUnknownCodes = unknownCodes.filterNot { it.contains(":)") }

        for (isLightTheme in listOf(true, false)) {
            EmojiHandler.init(isLightTheme)
            val legacyMap = readLegacyMap("EMOJIS_MAP")
            val dark = !isLightTheme

            for (code in unknownCodes) {
                assertFalse("Legacy EMOJIS_MAP should not contain '$code'", legacyMap.containsKey(code))
                assertNull("EmojiMap should return null for '$code' (dark=$dark)", EmojiMap.drawableFor(code, dark = dark))
            }
            for (code in nonPrefixUnknownCodes) {
                val spannable = SpannableStringBuilder(code)
                EmojiHandler.addEmojis(context, spannable, 48)
                val spans = spannable.getSpans(0, spannable.length, DynamicDrawableSpan::class.java)
                assertEquals("Legacy addEmojis should not attach spans for '$code'", 0, spans.size)
            }
        }
    }

    @Test
    fun drawableForImageSrc_and_codeForImageSrc_matchLegacyEmojiHandler() {
        EmojiHandler.init(true)
        val lightLegacyDrawables = readLegacyMap("DRAWABLE_MAP")

        EmojiHandler.init(false)
        val darkLegacyDrawables = readLegacyMap("DRAWABLE_MAP")

        val expectedAllSrcs = (Default.IMG_SRCS.toList() + Monkey.IMG_SRCS.toList() + Dumb.IMG_SRCS.toList()).toSet()
        assertEquals(61, expectedAllSrcs.size)
        assertEquals(expectedAllSrcs, lightLegacyDrawables.keys)
        assertEquals(expectedAllSrcs, darkLegacyDrawables.keys)

        for (isLightTheme in listOf(true, false)) {
            EmojiHandler.init(isLightTheme)
            val dark = !isLightTheme

            for (imgSrc in expectedAllSrcs) {
                val legacyResId = EmojiHandler.getDrawableResId(imgSrc)
                assertNotEquals("Legacy getDrawableResId returned 0 for $imgSrc", 0, legacyResId)

                assertEquals(
                    "EmojiMap.drawableForImageSrc mismatch for $imgSrc (dark=$dark)",
                    legacyResId,
                    EmojiMap.drawableForImageSrc(imgSrc, dark = dark),
                )

                val canonicalCode = EmojiMap.codeForImageSrc(imgSrc)
                assertNotNull("EmojiMap.codeForImageSrc returned null for $imgSrc", canonicalCode)
                assertEquals(
                    "Code lookup via codeForImageSrc mismatch for $imgSrc (dark=$dark)",
                    legacyResId,
                    EmojiMap.drawableFor(canonicalCode!!, dark = dark),
                )
            }
        }

        val unknownSrcs = listOf(
            "",
            "unknown",
            ":)",
            "{:2_41:}",
            "default/smile.gif",
            "coolmonkey_00",
            "coolmonkey_17",
            "grapeman_00",
            "grapeman_25",
        )
        for (isLightTheme in listOf(true, false)) {
            EmojiHandler.init(isLightTheme)
            val dark = !isLightTheme
            for (imgSrc in unknownSrcs) {
                assertEquals(0, EmojiHandler.getDrawableResId(imgSrc))
                assertNull(EmojiMap.drawableForImageSrc(imgSrc, dark = dark))
                assertNull(EmojiMap.codeForImageSrc(imgSrc))
            }
        }
    }

    @Test
    fun groups_matchLegacyEmojiPanelOrderCountsAndIconCodes() {
        val groups = EmojiMap.groups
        assertEquals(3, groups.size)

        val expectedGroups = listOf(
            Triple("默认", Default.DATA.map { it.emoji }, Default.EMOJIS.first()),
            Triple("酷猴", Monkey.DATA.map { it.emoji }, Monkey.EMOJIS.first()),
            Triple("呆呆", Dumb.DATA.map { it.emoji }, Dumb.EMOJIS.first()),
        )

        groups.forEachIndexed { index, group ->
            val (expectedName, expectedCodes, expectedIconCode) = expectedGroups[index]
            assertEquals("Group $index name mismatch", expectedName, group.name)
            assertEquals("Group $index count mismatch", expectedCodes.size, group.codes.size)
            assertEquals("Group $index codes mismatch", expectedCodes, group.codes)
            assertEquals("Group $index iconCode should be first code", expectedIconCode, group.iconCode)
            assertEquals(group.codes.first(), group.iconCode)
            assertNotNull(EmojiMap.drawableFor(group.iconCode, dark = false))
            assertNotNull(EmojiMap.drawableFor(group.iconCode, dark = true))
        }

        val flattened = groups.flatMap { it.codes }
        assertEquals(61, flattened.size)
        assertEquals("Group codes must not contain duplicates", flattened.size, flattened.toSet().size)

        EmojiHandler.init(true)
        assertEquals(readLegacyMap("EMOJIS_MAP").keys, flattened.toSet())
    }

    private fun resolveViaLegacyAddEmojis(code: String): Int {
        val spannable = SpannableStringBuilder(code)
        EmojiHandler.addEmojis(context, spannable, 48)
        val spans = spannable.getSpans(0, spannable.length, DynamicDrawableSpan::class.java)
        assertEquals("Expected exactly 1 EmojiSpan for $code", 1, spans.size)
        val span = spans[0]
        assertEquals("Span start mismatch for $code", 0, spannable.getSpanStart(span))
        assertEquals("Span end mismatch for $code", code.length, spannable.getSpanEnd(span))
        assertTrue("Expected legacy EmojiSpan instance for $code", span.javaClass.name == "com.vanniktech.emoji.EmojiSpan")
        assertNotNull("Legacy EmojiSpan drawable failed to load for $code", span.drawable)

        val resourceIdField = span.javaClass.getDeclaredField("resourceId").apply { isAccessible = true }
        return resourceIdField.getInt(span)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readLegacyMap(fieldName: String): Map<String, Int> {
        val field = EmojiHandler::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        val map = field.get(null) as Map<String, Int>
        return LinkedHashMap(map)
    }
}
