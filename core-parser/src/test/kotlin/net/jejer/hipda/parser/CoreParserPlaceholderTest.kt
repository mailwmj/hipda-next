package net.jejer.hipda.parser

import java.nio.file.Files
import java.nio.file.Path
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CoreParserPlaceholderTest {

    @Test
    fun jsoupParsesMinimalDocument() {
        val doc = Jsoup.parse("<html><body><h1>HiPDA</h1></body></html>")
        assertEquals("HiPDA", doc.selectFirst("h1")?.text())
    }

    @Test
    fun hipdaSamplesDirContainsNoCanaryLeak() {
        var dir: Path? = Path.of("").toAbsolutePath().normalize()
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent
        }
        val samplesDir = checkNotNull(dir).resolve("hipda/src/test/resources/samples")
        if (!Files.isDirectory(samplesDir)) return
        Files.walk(samplesDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val content = Files.readString(file)
                assertFalse(
                    content.contains("UNSANITIZED_LEAK_CANARY"),
                    "Detected canary leak in sample file: $file",
                )
            }
        }
    }
}
