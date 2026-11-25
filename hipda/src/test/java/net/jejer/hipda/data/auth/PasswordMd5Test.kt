package net.jejer.hipda.data.auth

import android.app.Application
import net.jejer.hipda.async.LoginHelper
import net.jejer.hipda.bean.HiSettingsHelper
import net.jejer.hipda.parser.LegacyTestSupport
import net.jejer.hipda.utils.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PasswordMd5Test {

    @Before
    fun setUp() {
        LegacyTestSupport.initLegacyEnvironment()
    }

    @Test
    fun computeLegacyPasswordMd5_rejectsEmptyStringWithoutLeakingInput() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            computeLegacyPasswordMd5("")
        }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun computeLegacyPasswordMd5_normalizesExisting32CharHexMd5ToLowercase() {
        val lower = "0123456789abcdef0123456789abcdef"
        val upper = "0123456789ABCDEF0123456789ABCDEF"
        val mixed = "A1b2C3d4E5f60718293a4B5c6D7e8F90"

        assertEquals(lower, computeLegacyPasswordMd5(lower))
        assertEquals(lower, computeLegacyPasswordMd5(upper))
        assertEquals(mixed.lowercase(), computeLegacyPasswordMd5(mixed))
    }

    @Test
    fun computeLegacyPasswordMd5_hashes32CharNonHexPlaintextInsteadOfPassingThrough() {
        val nonHex32 = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        val result = computeLegacyPasswordMd5(nonHex32)

        assertEquals(32, result.length)
        assertNotEquals(nonHex32, result)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(result))
        assertEquals(Utils.md5(nonHex32), result)
    }

    @Test
    fun computeLegacyPasswordMd5_matchesLegacyLoginHelperByteForByteAcrossSpecialCharsAndUnicode() {
        val cases = listOf(
            "simple123",
            "pass'word", // Java "\'" literal does NOT escape single quote
            "pass\"word", // double quote becomes \"
            "pass\\word", // backslash becomes \\
            "a\\b'c\"d", // combined special characters
            "\\\"mixed\\'combo\\\\end\"", // backslash before quotes must double first
            "   ", // spaces only are valid non-empty password
            "密码测试中文",
            "pass😀emoji'\"\\",
            "leadingZeroDigestCandidate_42",
        )

        val helper = LoginHelper()
        val processedPasswordMethod = LoginHelper::class.java.getDeclaredMethod("processedPassword").apply {
            isAccessible = true
        }

        for (raw in cases) {
            HiSettingsHelper.getInstance().setPassword(raw)
            val legacyDigest = processedPasswordMethod.invoke(helper) as String
            val actualDigest = computeLegacyPasswordMd5(raw)

            assertEquals("Mismatch with legacy LoginHelper for input length=${raw.length}", legacyDigest, actualDigest)
            assertEquals(32, actualDigest.length)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(actualDigest))
        }
    }

    @Test
    fun computeLegacyPasswordMd5_doesNotEscapeSingleQuoteUnlikeBackslashAndDoubleQuote() {
        val singleQuoteInput = "abc'def"
        val escapedWrongly = "abc\\'def"

        assertEquals(Utils.md5(singleQuoteInput), computeLegacyPasswordMd5(singleQuoteInput))
        assertNotEquals(Utils.md5(escapedWrongly), computeLegacyPasswordMd5(singleQuoteInput))

        val doubleQuoteInput = "abc\"def"
        assertEquals(Utils.md5("abc\\\"def"), computeLegacyPasswordMd5(doubleQuoteInput))
        assertFalse(computeLegacyPasswordMd5(doubleQuoteInput) == Utils.md5(doubleQuoteInput))

        val backslashInput = "abc\\def"
        assertEquals(Utils.md5("abc\\\\def"), computeLegacyPasswordMd5(backslashInput))
    }
}
