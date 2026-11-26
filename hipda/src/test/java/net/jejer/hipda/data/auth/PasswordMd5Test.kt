package net.jejer.hipda.data.auth

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordMd5Test {

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
        assertEquals("28cf6b7df6d1fa51cfd038294e5676d5", result)
        assertEquals(md5Hex(nonHex32), result)
    }

    @Test
    fun computeLegacyPasswordMd5_matchesGoldenLegacyDigestsAcrossSpecialCharsAndUnicode() {
        // Golden vectors locked verbatim from Discuz! legacy LoginHelper.processedPassword()
        val cases = listOf(
            "simple123" to "ee2da41de9eb269679017db7613bc778",
            "pass'word" to "2207c76a60a81a772e62152e395e4a98", // Java "\'" does NOT escape single quote
            "pass\"word" to "78293aacfe32de660e2d78117038c09c", // double quote becomes \"
            "pass\\word" to "b0c47f06c12a4d2ad85bc339dbe4d4d6", // backslash becomes \\
            "a\\b'c\"d" to "4b602dcf11cf8500e6279b716b39df68", // combined special characters
            "\\\"mixed\\'combo\\\\end\"" to "df6d639064a94016b87e0ae3935d6a19", // backslash before quotes doubles first
            "   " to "628631f07321b22d8c176c200c855e1b", // spaces only are valid non-empty password
            "密码测试中文" to "578951476babf1059881feb56002b04c",
            "pass😀emoji'\"\\" to "cdbbddd5050d05b85abee5676a6b4a78",
            "leadingZeroDigestCandidate_42" to "82c1f2b446d1cca79be1f0253203b17d",
        )

        for ((raw, expectedDigest) in cases) {
            val actualDigest = computeLegacyPasswordMd5(raw)
            assertEquals("Mismatch for golden input length=${raw.length}", expectedDigest, actualDigest)
            assertEquals(32, actualDigest.length)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(actualDigest))
        }
    }

    @Test
    fun computeLegacyPasswordMd5_doesNotEscapeSingleQuoteUnlikeBackslashAndDoubleQuote() {
        val singleQuoteInput = "abc'def"
        val escapedWrongly = "abc\\'def"

        assertEquals(md5Hex(singleQuoteInput), computeLegacyPasswordMd5(singleQuoteInput))
        assertNotEquals(md5Hex(escapedWrongly), computeLegacyPasswordMd5(singleQuoteInput))

        val doubleQuoteInput = "abc\"def"
        assertEquals(md5Hex("abc\\\"def"), computeLegacyPasswordMd5(doubleQuoteInput))
        assertFalse(computeLegacyPasswordMd5(doubleQuoteInput) == md5Hex(doubleQuoteInput))

        val backslashInput = "abc\\def"
        assertEquals(md5Hex("abc\\\\def"), computeLegacyPasswordMd5(backslashInput))
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
