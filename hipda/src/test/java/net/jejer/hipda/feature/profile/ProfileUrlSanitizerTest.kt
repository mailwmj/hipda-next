package net.jejer.hipda.feature.profile

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ProfileUrlSanitizerTest {

    @Test
    fun sanitizeProfileLinkUrl_allowsHttpHttpsAndValidatedMailtoAndRejectsDangerousSchemes() {
        assertEquals("https://example.com/u/1", ProfileUrlSanitizer.sanitizeProfileLinkUrl("https://example.com/u/1"))
        assertEquals("http://4d4y.com/forum/", ProfileUrlSanitizer.sanitizeProfileLinkUrl("http://4d4y.com/forum/"))
        assertEquals("mailto:alice@example.com", ProfileUrlSanitizer.sanitizeProfileLinkUrl("mailto:alice@example.com"))
        assertEquals("mailto:alice@example.com", ProfileUrlSanitizer.sanitizeProfileLinkUrl("MAILTO:alice@example.com"))

        val dangerous = listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "InTeNt://x",
            "file:///etc/passwd",
            "FILE:///sdcard/a",
            "content://com.android.contacts/contacts/1",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "tel:10086",
            "mailto:alice@example.com?subject=pwn",
            "mailto:alice@example.com\r\nBcc:evil@x.com",
            "mailto:bad..dot@example.com",
            "mailto:not-an-email",
            " https://example.com",
            "https://example.com ",
            "https://",
            "http:///nohost",
            "https://example.com/\nfoo",
        )
        dangerous.forEach { bad ->
            assertNull("资料链接白名单必须拦截: $bad", ProfileUrlSanitizer.sanitizeProfileLinkUrl(bad))
        }
    }

    @Test
    fun sanitizeProfileImageUrl_allowsOnlyHttpAndHttps() {
        assertEquals("https://img.example.com/a.jpg", ProfileUrlSanitizer.sanitizeProfileImageUrl("https://img.example.com/a.jpg"))
        assertEquals("http://img.example.com/a.png", ProfileUrlSanitizer.sanitizeProfileImageUrl("http://img.example.com/a.png"))

        val blocked = listOf(
            "mailto:alice@example.com",
            "file:///etc/passwd",
            "content://media/external/images/1",
            "data:image/png;base64,iVBORw0KGgo=",
            "javascript:alert(1)",
            "intent://x",
            "https://",
            " https://img.example.com/a.jpg",
        )
        blocked.forEach { bad ->
            assertNull("资料图片白名单必须仅放行 http/https，拦截: $bad", ProfileUrlSanitizer.sanitizeProfileImageUrl(bad))
        }
    }

    @Test
    fun buildSafeAnnotatedTextAndSignatureImages_onlyAttachSafeLinksAndImages() {
        val clicked = mutableListOf<String>()
        val raw = """
            主页: https://example.com/home 邮箱: mailto:hi@example.com
            [url=https://safe.example.org]安全链接[/url]
            [url=javascript:alert(1)]恶意JS[/url]
            危险: javascript:alert(2) intent://bad file:///etc/passwd content://bad/1 data:text/html,hi
            [img]https://img.example.com/sig.png[/img]
            [img]file:///etc/hosts[/img]
            [img]data:image/svg+xml,<svg/>[/img]
        """.trimIndent()

        val annotated = ProfileUrlSanitizer.buildSafeAnnotatedText(raw, Color.Blue) { clicked += it }
        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(
            listOf("https://example.com/home", "mailto:hi@example.com", "https://safe.example.org/"),
            linkAnnotations.map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url },
        )

        val images = ProfileUrlSanitizer.extractSafeSignatureImageUrls(raw)
        assertEquals(listOf("https://img.example.com/sig.png"), images)
    }
}
