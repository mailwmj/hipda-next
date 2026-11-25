package net.jejer.hipda.data.network

import java.nio.charset.Charset
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbkFormTest {

    @Test
    fun nonGbkCodePointBecomesDecimalEntity() {
        assertEquals("回复&#128512;&#19886;", toGbkSafe("回复😀䶮"))
        assertEquals("", toGbkSafe(""))
        assertEquals("HiPDA 普通中文 123", toGbkSafe("HiPDA 普通中文 123"))
        assertEquals("&#128512;&#128513;&#19886;", toGbkSafe("😀😁䶮"))
    }

    @Test
    fun unpairedSurrogateBecomesDecimalEntityWithoutThrowing() {
        assertEquals("前&#55357;后", toGbkSafe("前\uD83D后"))
        assertEquals("尾&#55357;", toGbkSafe("尾\uD83D"))
        assertEquals("&#56832;", toGbkSafe("\uDE00"))
    }

    @Test
    fun variationSelectorAndKeycapEmojiSequencesBecomeDecimalEntities() {
        assertEquals("&#10084;&#65039;", toGbkSafe("❤️"))
        assertEquals("1&#65039;&#8419;", toGbkSafe("1️⃣"))
    }

    @Test
    fun tildeIsPercentEncodedInGbkFormBody() {
        val body = gbkFormBody(listOf("k" to "~"))
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals("k=%7E", buffer.readUtf8())
    }

    @Test
    fun gbkFormBodyEncodesGbkBytesAndReservedChars() {
        val body = gbkFormBody(listOf("message" to "中 文&=+😀"))
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals(
            "message=%D6%D0+%CE%C4%26%3D%2B%26%23128512%3B",
            buffer.readUtf8(),
        )
        assertEquals(
            "application/x-www-form-urlencoded; charset=GBK",
            body.contentType().toString(),
        )
    }

    @Test
    fun gbkFormBodyEscapesPercentHashSemicolonAndJoinsMultipleFields() {
        val body = gbkFormBody(
            listOf(
                "subject" to "100%#;a-b_c.d*e",
                "attachnew[1]" to "中文",
            ),
        )
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals(
            "subject=100%25%23%3Ba-b_c.d*e&attachnew%5B1%5D=%D6%D0%CE%C4",
            buffer.readUtf8(),
        )
    }

    @Test
    fun emptyGbkFormBodyProducesEmptyPayloadWithGbkContentType() {
        val body = gbkFormBody(emptyList())
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals(0L, body.contentLength())
        assertEquals("", buffer.readUtf8())
        assertEquals(
            "application/x-www-form-urlencoded; charset=GBK",
            body.contentType().toString(),
        )
    }

    @Test
    fun postGbkFormBodyViaMockWebServerSendsExpectedBodyAndCharset() {
        val server = MockWebServer()
        server.start()
        try {
            val responseText = "发帖成功䶮"
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/html; charset=gbk")
                    .body(Buffer().write(responseText.toByteArray(Charset.forName("GB18030"))))
                    .build(),
            )

            val client = HttpClientModule.provideOkHttpClient(
                siteCookieStore = InMemorySiteCookieStore(),
                userAgentProvider = DefaultUserAgentProvider(),
            )
            val request = Request.Builder()
                .url(server.url("/forum/post.php?action=reply"))
                .post(gbkFormBody(listOf("message" to "中 文&=+😀")))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                val decoded = decodeBody(response.body.bytes(), response.header("Content-Type"))
                assertEquals(responseText, decoded)
            }

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals(
                "message=%D6%D0+%CE%C4%26%3D%2B%26%23128512%3B",
                recorded.body?.utf8(),
            )
            val contentType = recorded.headers["Content-Type"].orEmpty()
            assertTrue(
                "Expected Content-Type to contain charset=GBK, actual=$contentType",
                contentType.contains("charset=GBK"),
            )
        } finally {
            server.close()
        }
    }
}
