package net.jejer.hipda.emoji

import android.app.Application
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.vanniktech.emoji.R as EmojiR
import net.jejer.hipda.parser.SampleTestLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 表驱动锁定全部 61 个论坛表情的 `(code, imageSrc, lightRes, darkRes)` 四元组、3 组分类顺序/首图与
 * 跨模块 `:core-parser` 真机样本表情键命中校验（收口 C1b#1，零旧 `EmojiHandler` 依赖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EmojiMapTest {

    private data class ExpectedEmoji(
        val code: String,
        val imageSrc: String,
        @DrawableRes val lightRes: Int,
        @DrawableRes val darkRes: Int,
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun all61ExpectedEmojis_matchFourTupleTableInLightAndDarkModesWithValidDrawables() {
        assertEquals(21, DEFAULT_EMOJIS.size)
        assertEquals(16, MONKEY_EMOJIS.size)
        assertEquals(24, DUMB_EMOJIS.size)
        assertEquals(61, ALL_EXPECTED_EMOJIS.size)
        assertEquals(61, ALL_EXPECTED_EMOJIS.map { it.code }.toSet().size)
        assertEquals(61, ALL_EXPECTED_EMOJIS.map { it.imageSrc }.toSet().size)

        for (expected in ALL_EXPECTED_EMOJIS) {
            val actualLightByCode = EmojiMap.drawableFor(expected.code, dark = false)
            val actualDarkByCode = EmojiMap.drawableFor(expected.code, dark = true)
            assertEquals("Light drawableFor mismatch for ${expected.code}", expected.lightRes, actualLightByCode)
            assertEquals("Dark drawableFor mismatch for ${expected.code}", expected.darkRes, actualDarkByCode)

            val actualLightBySrc = EmojiMap.drawableForImageSrc(expected.imageSrc, dark = false)
            val actualDarkBySrc = EmojiMap.drawableForImageSrc(expected.imageSrc, dark = true)
            assertEquals("Light drawableForImageSrc mismatch for ${expected.imageSrc}", expected.lightRes, actualLightBySrc)
            assertEquals("Dark drawableForImageSrc mismatch for ${expected.imageSrc}", expected.darkRes, actualDarkBySrc)

            assertEquals("codeForImageSrc mismatch for ${expected.imageSrc}", expected.code, EmojiMap.codeForImageSrc(expected.imageSrc))

            assertNotNull("Light drawable failed to load for ${expected.code}", ContextCompat.getDrawable(context, expected.lightRes))
            assertNotNull("Dark drawable failed to load for ${expected.code}", ContextCompat.getDrawable(context, expected.darkRes))
        }

        // Default 组深浅共用同一资源；Monkey 与 Dumb 组深浅资源必须严格区分
        for (entry in DEFAULT_EMOJIS) {
            assertEquals("Default emoji ${entry.code} should share light/dark resource", entry.lightRes, entry.darkRes)
        }
        for (entry in MONKEY_EMOJIS) {
            assertNotEquals("Monkey emoji ${entry.code} must have distinct dark resource", entry.lightRes, entry.darkRes)
        }
        for (entry in DUMB_EMOJIS) {
            assertNotEquals("Dumb emoji ${entry.code} must have distinct dark resource", entry.lightRes, entry.darkRes)
        }
    }

    @Test
    fun drawableFor_isStatelessAcrossAlternatingLightAndDarkLookups() {
        val monkeyCode = MONKEY_EMOJIS.first().code
        val dumbCode = DUMB_EMOJIS.first().code

        repeat(3) {
            assertEquals(EmojiR.drawable.coolmonkey_01_dark, EmojiMap.drawableFor(monkeyCode, dark = true))
            assertEquals(EmojiR.drawable.grapeman_01_dark, EmojiMap.drawableFor(dumbCode, dark = true))
            assertEquals(EmojiR.drawable.coolmonkey_01, EmojiMap.drawableFor(monkeyCode, dark = false))
            assertEquals(EmojiR.drawable.grapeman_01, EmojiMap.drawableFor(dumbCode, dark = false))
        }
    }

    @Test
    fun drawableFor_andImageSrcLookups_returnNullForUnknownInputs() {
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
        for (dark in listOf(false, true)) {
            for (code in unknownCodes) {
                assertNull("EmojiMap should return null for '$code' (dark=$dark)", EmojiMap.drawableFor(code, dark = dark))
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
        for (dark in listOf(false, true)) {
            for (imgSrc in unknownSrcs) {
                assertNull("drawableForImageSrc should return null for '$imgSrc' (dark=$dark)", EmojiMap.drawableForImageSrc(imgSrc, dark = dark))
                assertNull("codeForImageSrc should return null for '$imgSrc'", EmojiMap.codeForImageSrc(imgSrc))
            }
        }
    }

    @Test
    fun groups_preserveExactThreeCategoryOrderCountsAndRepresentativeIcons() {
        val groups = EmojiMap.groups
        assertEquals(3, groups.size)

        val expectedGroups = listOf(
            Triple("默认", DEFAULT_EMOJIS.map { it.code }, ":)"),
            Triple("酷猴", MONKEY_EMOJIS.map { it.code }, "{:2_41:}"),
            Triple("呆呆", DUMB_EMOJIS.map { it.code }, "{:3_57:}"),
        )

        groups.forEachIndexed { index, group ->
            val (expectedName, expectedCodes, expectedIconCode) = expectedGroups[index]
            assertEquals("Group $index name mismatch", expectedName, group.name)
            assertEquals("Group $index count mismatch", expectedCodes.size, group.codes.size)
            assertEquals("Group $index codes mismatch", expectedCodes, group.codes)
            assertEquals("Group $index iconCode mismatch", expectedIconCode, group.iconCode)
            assertEquals(group.codes.first(), group.iconCode)
            assertNotNull(EmojiMap.drawableFor(group.iconCode, dark = false))
            assertNotNull(EmojiMap.drawableFor(group.iconCode, dark = true))
        }

        val flattened = groups.flatMap { it.codes }
        assertEquals(61, flattened.size)
        assertEquals(ALL_EXPECTED_EMOJIS.map { it.code }, flattened)
    }

    @Test
    fun realThreadSamples_allParsedSmileyKeysResolveInEmojiMap() {
        val p1 = ThreadDetailParser.parse(SampleTestLoader.parseSampleDocument("thread_detail/thread_p1.html"), tid = "2602293")
        val p1Keys = p1.floors
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Emoji>()
            .map { it.normalizedKey }
            .toSet()
        assertEquals(setOf("default_lol", "coolmonkey_01", "grapeman_08", "grapeman_18"), p1Keys)

        val p2 = ThreadDetailParser.parse(SampleTestLoader.parseSampleDocument("thread_detail/thread_p2.html"), tid = "2602293")
        val p2Keys = p2.floors
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Emoji>()
            .map { it.normalizedKey }
            .toSet()
        assertEquals(setOf("coolmonkey_16"), p2Keys)

        for (key in p1Keys + p2Keys) {
            val lightRes = EmojiMap.drawableForImageSrc(key, dark = false)
            val darkRes = EmojiMap.drawableForImageSrc(key, dark = true)
            assertNotNull("Light drawable missing for sample smiley $key", lightRes)
            assertNotNull("Dark drawable missing for sample smiley $key", darkRes)
            assertNotNull("Canonical code missing for sample smiley $key", EmojiMap.codeForImageSrc(key))
            assertTrue(ContextCompat.getDrawable(context, lightRes!!) != null)
            assertTrue(ContextCompat.getDrawable(context, darkRes!!) != null)
        }
    }

    companion object {
        private val DEFAULT_EMOJIS: List<ExpectedEmoji> = listOf(
            ExpectedEmoji(":)", "default_smile", EmojiR.drawable.default_smile, EmojiR.drawable.default_smile),
            ExpectedEmoji(":sweat:", "default_sweat", EmojiR.drawable.default_sweat, EmojiR.drawable.default_sweat),
            ExpectedEmoji(":huffy:", "default_huffy", EmojiR.drawable.default_huffy, EmojiR.drawable.default_huffy),
            ExpectedEmoji(":cry:", "default_cry", EmojiR.drawable.default_cry, EmojiR.drawable.default_cry),
            ExpectedEmoji(":titter:", "default_titter", EmojiR.drawable.default_titter, EmojiR.drawable.default_titter),
            ExpectedEmoji(":handshake:", "default_handshake", EmojiR.drawable.default_handshake, EmojiR.drawable.default_handshake),
            ExpectedEmoji(":victory:", "default_victory", EmojiR.drawable.default_victory, EmojiR.drawable.default_victory),
            ExpectedEmoji(":curse:", "default_curse", EmojiR.drawable.default_curse, EmojiR.drawable.default_curse),
            ExpectedEmoji(":dizzy:", "default_dizzy", EmojiR.drawable.default_dizzy, EmojiR.drawable.default_dizzy),
            ExpectedEmoji(":shutup:", "default_shutup", EmojiR.drawable.default_shutup, EmojiR.drawable.default_shutup),
            ExpectedEmoji(":funk:", "default_funk", EmojiR.drawable.default_funk, EmojiR.drawable.default_funk),
            ExpectedEmoji(":loveliness:", "default_loveliness", EmojiR.drawable.default_loveliness, EmojiR.drawable.default_loveliness),
            ExpectedEmoji(":(", "default_sad", EmojiR.drawable.default_sad, EmojiR.drawable.default_sad),
            ExpectedEmoji(":D", "default_biggrin", EmojiR.drawable.default_biggrin, EmojiR.drawable.default_biggrin),
            ExpectedEmoji(":cool:", "default_cool", EmojiR.drawable.default_cool, EmojiR.drawable.default_cool),
            ExpectedEmoji(":mad:", "default_mad", EmojiR.drawable.default_mad, EmojiR.drawable.default_mad),
            ExpectedEmoji(":o", "default_shocked", EmojiR.drawable.default_shocked, EmojiR.drawable.default_shocked),
            ExpectedEmoji(":P", "default_tongue", EmojiR.drawable.default_tongue, EmojiR.drawable.default_tongue),
            ExpectedEmoji(":lol:", "default_lol", EmojiR.drawable.default_lol, EmojiR.drawable.default_lol),
            ExpectedEmoji(":shy:", "default_shy", EmojiR.drawable.default_shy, EmojiR.drawable.default_shy),
            ExpectedEmoji(":sleepy:", "default_sleepy", EmojiR.drawable.default_sleepy, EmojiR.drawable.default_sleepy),
        )

        private val MONKEY_EMOJIS: List<ExpectedEmoji> = listOf(
            ExpectedEmoji("{:2_41:}", "coolmonkey_01", EmojiR.drawable.coolmonkey_01, EmojiR.drawable.coolmonkey_01_dark),
            ExpectedEmoji("{:2_42:}", "coolmonkey_02", EmojiR.drawable.coolmonkey_02, EmojiR.drawable.coolmonkey_02_dark),
            ExpectedEmoji("{:2_43:}", "coolmonkey_03", EmojiR.drawable.coolmonkey_03, EmojiR.drawable.coolmonkey_03_dark),
            ExpectedEmoji("{:2_44:}", "coolmonkey_04", EmojiR.drawable.coolmonkey_04, EmojiR.drawable.coolmonkey_04_dark),
            ExpectedEmoji("{:2_45:}", "coolmonkey_05", EmojiR.drawable.coolmonkey_05, EmojiR.drawable.coolmonkey_05_dark),
            ExpectedEmoji("{:2_46:}", "coolmonkey_06", EmojiR.drawable.coolmonkey_06, EmojiR.drawable.coolmonkey_06_dark),
            ExpectedEmoji("{:2_47:}", "coolmonkey_07", EmojiR.drawable.coolmonkey_07, EmojiR.drawable.coolmonkey_07_dark),
            ExpectedEmoji("{:2_48:}", "coolmonkey_08", EmojiR.drawable.coolmonkey_08, EmojiR.drawable.coolmonkey_08_dark),
            ExpectedEmoji("{:2_49:}", "coolmonkey_09", EmojiR.drawable.coolmonkey_09, EmojiR.drawable.coolmonkey_09_dark),
            ExpectedEmoji("{:2_50:}", "coolmonkey_10", EmojiR.drawable.coolmonkey_10, EmojiR.drawable.coolmonkey_10_dark),
            ExpectedEmoji("{:2_51:}", "coolmonkey_11", EmojiR.drawable.coolmonkey_11, EmojiR.drawable.coolmonkey_11_dark),
            ExpectedEmoji("{:2_52:}", "coolmonkey_12", EmojiR.drawable.coolmonkey_12, EmojiR.drawable.coolmonkey_12_dark),
            ExpectedEmoji("{:2_53:}", "coolmonkey_13", EmojiR.drawable.coolmonkey_13, EmojiR.drawable.coolmonkey_13_dark),
            ExpectedEmoji("{:2_54:}", "coolmonkey_14", EmojiR.drawable.coolmonkey_14, EmojiR.drawable.coolmonkey_14_dark),
            ExpectedEmoji("{:2_55:}", "coolmonkey_15", EmojiR.drawable.coolmonkey_15, EmojiR.drawable.coolmonkey_15_dark),
            ExpectedEmoji("{:2_56:}", "coolmonkey_16", EmojiR.drawable.coolmonkey_16, EmojiR.drawable.coolmonkey_16_dark),
        )

        private val DUMB_EMOJIS: List<ExpectedEmoji> = listOf(
            ExpectedEmoji("{:3_57:}", "grapeman_01", EmojiR.drawable.grapeman_01, EmojiR.drawable.grapeman_01_dark),
            ExpectedEmoji("{:3_58:}", "grapeman_02", EmojiR.drawable.grapeman_02, EmojiR.drawable.grapeman_02_dark),
            ExpectedEmoji("{:3_59:}", "grapeman_03", EmojiR.drawable.grapeman_03, EmojiR.drawable.grapeman_03_dark),
            ExpectedEmoji("{:3_60:}", "grapeman_04", EmojiR.drawable.grapeman_04, EmojiR.drawable.grapeman_04_dark),
            ExpectedEmoji("{:3_61:}", "grapeman_05", EmojiR.drawable.grapeman_05, EmojiR.drawable.grapeman_05_dark),
            ExpectedEmoji("{:3_62:}", "grapeman_06", EmojiR.drawable.grapeman_06, EmojiR.drawable.grapeman_06_dark),
            ExpectedEmoji("{:3_63:}", "grapeman_07", EmojiR.drawable.grapeman_07, EmojiR.drawable.grapeman_07_dark),
            ExpectedEmoji("{:3_64:}", "grapeman_08", EmojiR.drawable.grapeman_08, EmojiR.drawable.grapeman_08_dark),
            ExpectedEmoji("{:3_65:}", "grapeman_09", EmojiR.drawable.grapeman_09, EmojiR.drawable.grapeman_09_dark),
            ExpectedEmoji("{:3_66:}", "grapeman_10", EmojiR.drawable.grapeman_10, EmojiR.drawable.grapeman_10_dark),
            ExpectedEmoji("{:3_67:}", "grapeman_11", EmojiR.drawable.grapeman_11, EmojiR.drawable.grapeman_11_dark),
            ExpectedEmoji("{:3_68:}", "grapeman_12", EmojiR.drawable.grapeman_12, EmojiR.drawable.grapeman_12_dark),
            ExpectedEmoji("{:3_69:}", "grapeman_13", EmojiR.drawable.grapeman_13, EmojiR.drawable.grapeman_13_dark),
            ExpectedEmoji("{:3_70:}", "grapeman_14", EmojiR.drawable.grapeman_14, EmojiR.drawable.grapeman_14_dark),
            ExpectedEmoji("{:3_71:}", "grapeman_15", EmojiR.drawable.grapeman_15, EmojiR.drawable.grapeman_15_dark),
            ExpectedEmoji("{:3_72:}", "grapeman_16", EmojiR.drawable.grapeman_16, EmojiR.drawable.grapeman_16_dark),
            ExpectedEmoji("{:3_73:}", "grapeman_17", EmojiR.drawable.grapeman_17, EmojiR.drawable.grapeman_17_dark),
            ExpectedEmoji("{:3_74:}", "grapeman_18", EmojiR.drawable.grapeman_18, EmojiR.drawable.grapeman_18_dark),
            ExpectedEmoji("{:3_75:}", "grapeman_19", EmojiR.drawable.grapeman_19, EmojiR.drawable.grapeman_19_dark),
            ExpectedEmoji("{:3_76:}", "grapeman_20", EmojiR.drawable.grapeman_20, EmojiR.drawable.grapeman_20_dark),
            ExpectedEmoji("{:3_77:}", "grapeman_21", EmojiR.drawable.grapeman_21, EmojiR.drawable.grapeman_21_dark),
            ExpectedEmoji("{:3_78:}", "grapeman_22", EmojiR.drawable.grapeman_22, EmojiR.drawable.grapeman_22_dark),
            ExpectedEmoji("{:3_79:}", "grapeman_23", EmojiR.drawable.grapeman_23, EmojiR.drawable.grapeman_23_dark),
            ExpectedEmoji("{:3_80:}", "grapeman_24", EmojiR.drawable.grapeman_24, EmojiR.drawable.grapeman_24_dark),
        )

        private val ALL_EXPECTED_EMOJIS: List<ExpectedEmoji> = DEFAULT_EMOJIS + MONKEY_EMOJIS + DUMB_EMOJIS
    }
}
