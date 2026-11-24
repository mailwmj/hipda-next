package net.jejer.hipda.data.network

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class SiteCharsetTest {

    @Test
    fun gbkHeaderUsesGb18030Superset() {
        val expected = "中文䶮"
        val bytes = expected.toByteArray(GB18030)

        assertEquals(expected, decodeBody(bytes, "text/html; charset=gbk"))
        assertEquals(expected, decodeBody(bytes, "text/html; charset=\"GBK\""))
        assertEquals(expected, decodeBody(bytes, "text/html; charset=gb2312"))
        assertEquals(expected, decodeBody(bytes, "text/html; charset=cp936"))
    }

    @Test
    fun metaHttpEquivCharsetDecodesGbkWhenHeaderOmitsCharset() {
        val html =
            "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\">" +
                "</head><body>论坛中文䶮内容</body></html>"
        val bytes = html.toByteArray(GB18030)

        assertEquals(html, decodeBody(bytes, "text/html"))
        assertEquals(html, decodeBody(bytes, null))
    }

    @Test
    fun metaWithReversedAttributesOrHtml5CharsetIsDetected() {
        val reversedHtml =
            "<html><head><meta content=\"text/html; charset=gbk\" http-equiv=\"Content-Type\">" +
                "</head><body>反序属性中文</body></html>"
        assertEquals(
            reversedHtml,
            decodeBody(reversedHtml.toByteArray(GBK), "text/html"),
        )

        val html5Meta = "<!doctype html><meta charset=\"gb2312\"><p>中文䶮</p>"
        assertEquals(
            html5Meta,
            decodeBody(html5Meta.toByteArray(GB18030), null),
        )
    }

    @Test
    fun declaredUtf8HeaderOrMetaDecodesAsUtf8() {
        val headerText = "<html><body>UTF-8中文😀䶮</body></html>"
        assertEquals(
            headerText,
            decodeBody(headerText.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8"),
        )

        val metaHtml = "<html><head><meta charset=\"UTF-8\"></head><body>页面中文😀䶮</body></html>"
        assertEquals(
            metaHtml,
            decodeBody(metaHtml.toByteArray(Charsets.UTF_8), "text/html"),
        )

        // Content-Type header takes precedence over HTML meta charset.
        val conflictingMetaHtml =
            "<html><head><meta charset=\"gbk\"></head><body>头优先😀</body></html>"
        assertEquals(
            conflictingMetaHtml,
            decodeBody(conflictingMetaHtml.toByteArray(Charsets.UTF_8), "text/html; charset=UTF-8"),
        )
    }

    @Test
    fun undeclaredCharsetDefaultsToGbk() {
        val text = "<html><body>没有声明编码时的中文内容</body></html>"
        val bytes = text.toByteArray(GBK)

        assertEquals(text, decodeBody(bytes, null))
        assertEquals(text, decodeBody(bytes, "text/html"))
    }

    @Test
    fun metaBeyondFirst4KbIsIgnoredAndFallsBackToGbk() {
        val padding = "a".repeat(4096)
        val html = "<html><body>$padding<meta charset=\"utf-8\">中文</body></html>"
        val bytes = html.toByteArray(GBK)

        assertEquals(html, decodeBody(bytes, null))
    }

    @Test
    fun commentedOutMetaTagIsIgnored() {
        val html =
            "<html><head><!-- <meta charset=\"utf-8\"> --><meta charset=\"gbk\"></head>" +
                "<body>注释外按GBK解码</body></html>"
        assertEquals(html, decodeBody(html.toByteArray(GBK), null))
    }

    @Test
    fun invalidHeaderCharsetFallsThroughToMetaAndEmptyReturnsEmpty() {
        val html = "<html><head><meta charset=\"utf-8\"></head><body>兜底😀</body></html>"
        assertEquals(
            html,
            decodeBody(html.toByteArray(Charsets.UTF_8), "text/html; charset=not-a-charset"),
        )
        assertEquals("", decodeBody(ByteArray(0), null))
    }

    private companion object {
        val GBK: Charset = Charset.forName("GBK")
        val GB18030: Charset = Charset.forName("GB18030")
    }
}
