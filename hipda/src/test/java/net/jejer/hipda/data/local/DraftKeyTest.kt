package net.jejer.hipda.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftKeyTest {

    @Test
    fun factoryFunctions_buildCanonicalKeysWithKindAndTargetId() {
        val replyKey = DraftKey.reply("1579403")
        assertEquals("reply:1579403", replyKey.value)
        assertEquals("reply:1", DraftKey.reply("1").value)
        assertEquals(DraftKey.Kind.REPLY, replyKey.kind)
        assertEquals("1579403", replyKey.targetId)

        val newThreadKey = DraftKey.newThread("2")
        assertEquals("new:2", newThreadKey.value)
        assertEquals(DraftKey.Kind.NEW, newThreadKey.kind)
        assertEquals("2", newThreadKey.targetId)

        val editKey = DraftKey.edit("98765432")
        assertEquals("edit:98765432", editKey.value)
        assertEquals(DraftKey.Kind.EDIT, editKey.kind)
        assertEquals("98765432", editKey.targetId)
    }

    @Test
    fun factoryFunctions_rejectInvalidIds() {
        val invalidIds = listOf(
            "",
            "   ",
            "0",
            "-1",
            "0123",
            "abc",
            "123abc",
            "123:456",
            " 123",
            "123 ",
            "123\n",
            "１２３",
            "9223372036854775808",
            "9999999999999999999",
            "10000000000000000000",
            "99999999999999999999999999",
        )

        invalidIds.forEach { badId ->
            assertThrows(IllegalArgumentException::class.java) {
                DraftKey.reply(badId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                DraftKey.newThread(badId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                DraftKey.edit(badId)
            }
        }
    }

    @Test
    fun parse_returnsKeyForValidFormats() {
        assertEquals(DraftKey.reply("1"), DraftKey.parse("reply:1"))
        assertEquals(DraftKey.reply("1579403"), DraftKey.parse("reply:1579403"))
        assertEquals(DraftKey.newThread("2"), DraftKey.parse("new:2"))
        assertEquals(DraftKey.newThread("59"), DraftKey.parse("new:59"))
        assertEquals(DraftKey.edit("9876543210"), DraftKey.parse("edit:9876543210"))
        assertEquals(
            DraftKey.reply(Long.MAX_VALUE.toString()),
            DraftKey.parse("reply:${Long.MAX_VALUE}"),
        )
    }

    @Test
    fun parse_returnsNullForInvalidFormats() {
        val invalidKeys = listOf(
            "",
            "   ",
            "reply",
            "new",
            "edit",
            "reply:",
            "new:",
            "edit:",
            ":123",
            "unknown:123",
            "draft:123",
            "REPLY:123",
            "New:2",
            "EDIT:9",
            "reply:0",
            "new:0",
            "edit:0",
            "reply:-1",
            "new:-2",
            "reply:0123",
            "reply:abc",
            "new:2a",
            "edit:1.5",
            "reply:123:456",
            "new:2:extra",
            " reply:123",
            "reply:123 ",
            "reply: 123",
            "reply:123\n",
            "reply:１２３",
            "reply:9223372036854775808",
            "new:9999999999999999999",
            "edit:10000000000000000000",
            "reply:99999999999999999999999999",
        )

        invalidKeys.forEach { raw ->
            assertNull("Expected null for invalid key: [$raw]", DraftKey.parse(raw))
        }
    }
}
