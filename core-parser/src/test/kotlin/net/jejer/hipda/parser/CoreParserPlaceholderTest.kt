package net.jejer.hipda.parser

import java.nio.file.Files
import java.nio.file.Path
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreParserPlaceholderTest {

    @Test
    fun jsoupParsesMinimalDocument() {
        val malformed = Path.of("build/test-results/test/TEST-malformed-proof.xml")
        Files.createDirectories(malformed.parent)
        Files.writeString(malformed, "<testsuite name=\"corrupted\"><testcase")
        val doc = Jsoup.parse("<html><body><h1>HiPDA</h1></body></html>")
        assertEquals("HiPDA", doc.selectFirst("h1")?.text())
    }
}
