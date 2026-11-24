package net.jejer.hipda.parser

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreParserPlaceholderTest {

    @Test
    fun jsoupParsesMinimalDocument() {
        val doc = Jsoup.parse("<html><body><h1>HiPDA</h1></body></html>")
        assertEquals("HiPDA", doc.selectFirst("h1")?.text())
    }
}
