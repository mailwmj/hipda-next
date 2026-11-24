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

    @Test
    fun utf8BomForcesUtf8AndStripsLeadingBom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = bom + "中文".toByteArray(Charsets.UTF_8)

        assertEquals("中文", decodeBody(payload, null))
        assertEquals("中文", decodeBody(payload, "text/html; charset=utf-8"))
        assertEquals("", decodeBody(bom, null))
        assertEquals("", decodeBody(bom, "text/html; charset=utf-8"))
    }

    @Test
    fun unclosedHtmlCommentAcross4KbIgnoresCommentedMeta() {
        val html = "<!-- <meta charset=\"utf-8\"> " + "x".repeat(4100) + " -->中文"
        val bytes = html.toByteArray(GBK)

        assertEquals(html, decodeBody(bytes, null))
    }

    @Test
    fun metaTagCrossing4KbBoundaryIsStillDetected() {
        val html = " ".repeat(4086) + "<meta charset=\"utf-8\">中文"
        val bytes = html.toByteArray(Charsets.UTF_8)

        assertEquals(html, decodeBody(bytes, null))
    }

    @Test
    fun quotedParamContainingCharsetDoesNotShadowRealCharset() {
        val gbkText = "中文䶮"
        val gbkBytes = gbkText.toByteArray(GB18030)

        assertEquals(
            gbkText,
            decodeBody(gbkBytes, "text/html; param=\"foo; charset=utf-8\"; charset=gbk"),
        )
        assertEquals(
            gbkText,
            decodeBody(gbkBytes, "text/html; param=\"foo; charset=utf-8\""),
        )

        val utf8Text = "回退正则取首个合法字符集😀"
        assertEquals(
            utf8Text,
            decodeBody(
                utf8Text.toByteArray(Charsets.UTF_8),
                "text/html; broken param; charset=bad!name; charset=utf-8",
            ),
        )
    }

    @Test
    fun gbkAliasesSingleQuotesAndMalformedCharsetNameHandledSafely() {
        val expected = "中文䶮"
        val bytes = expected.toByteArray(GB18030)

        assertEquals(expected, decodeBody(bytes, "text/html; charset=x-gbk"))
        assertEquals(expected, decodeBody(bytes, "text/html; charset='gbk'"))
        assertEquals(expected, decodeBody(bytes, "text/html; charset=bad!name"))
    }

    @Test
    fun multibyteCharactersCrossing4KbBoundaryDecodeIntact() {
        val gbkCrossing = "a".repeat(4095) + "中文测试"
        assertEquals(gbkCrossing, decodeBody(gbkCrossing.toByteArray(GBK), null))

        val utf8Head = "<meta charset=\"utf-8\">"
        val utf8Crossing = utf8Head + "a".repeat(4095 - utf8Head.length) + "中文😀测试"
        assertEquals(utf8Crossing, decodeBody(utf8Crossing.toByteArray(Charsets.UTF_8), null))

        val metaWithUtf8Crossing =
            " ".repeat(4080) + "<meta name=\"中\" charset=\"utf-8\">中文"
        assertEquals(
            metaWithUtf8Crossing,
            decodeBody(metaWithUtf8Crossing.toByteArray(Charsets.UTF_8), null),
        )
    }

    private companion object {
        val GBK: Charset = Charset.forName("GBK")
        val GB18030: Charset = Charset.forName("GB18030")
    }
}
