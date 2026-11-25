package net.jejer.hipda.data.network

import java.io.IOException
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Unit tests for [ChallengeDetector] using [MockWebServer] so `response.peekBody` runs against
 * real streaming OkHttp response bodies rather than pre-buffered in-memory doubles.
 */
@RunWith(Enclosed::class)
class ChallengeDetectorTest {

    @RunWith(Parameterized::class)
    class DetectionMatrix(
        private val caseName: String,
        private val statusCode: Int,
        private val headers: List<Pair<String, String>>,
        private val bodyBytes: ByteArray,
        private val expected: ChallengeSignal,
    ) {

        @Test
        fun inspect_matchesExpectedSignal() {
            val responseBuilder = MockResponse.Builder()
                .code(statusCode)
                .body(Buffer().write(bodyBytes))
            for ((name, value) in headers) {
                responseBuilder.addHeader(name, value)
            }
            server.enqueue(responseBuilder.build())

            val request = Request.Builder()
                .url(server.url("/forum/test/$caseName"))
                .build()

            client.newCall(request).execute().use { response ->
                val actual = detector.inspect(response)
                assertEquals(caseName, expected, actual)
                // Every matrix case also asserts the original response body remains intact.
                assertArrayEquals(
                    "Body must remain intact after inspect ($caseName)",
                    bodyBytes,
                    response.body.bytes(),
                )
            }
        }

        companion object {
            private val GBK: Charset = Charset.forName("GBK")
            private val detector = ChallengeDetector()
            private lateinit var server: MockWebServer
            private lateinit var client: OkHttpClient

            @JvmStatic
            @BeforeClass
            fun startServer() {
                server = MockWebServer()
                server.start()
                client = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            }

            @JvmStatic
            @AfterClass
            fun stopServer() {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }

            @JvmStatic
            @Parameterized.Parameters(name = "{0}")
            fun cases(): List<Array<Any>> = listOf(
                // === Positive hits: Rule A (cf-mitigated header) ===
                case(
                    name = "hit_403_cfMitigatedHeader_managedChallengeBody",
                    statusCode = 403,
                    headers = listOf(
                        "cf-mitigated" to "challenge",
                        "server" to "cloudflare",
                        "Content-Type" to "text/html; charset=UTF-8",
                    ),
                    bodyBytes = MANAGED_CHALLENGE_HTML.toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 403, byHeader = true),
                ),
                case(
                    name = "hit_429_cfMitigatedHeader_caseInsensitive",
                    statusCode = 429,
                    headers = listOf(
                        "Cf-Mitigated" to "Challenge",
                        "Content-Type" to "text/html; charset=UTF-8",
                    ),
                    bodyBytes = "<html><body>Rate limit challenge</body></html>".toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 429, byHeader = true),
                ),
                case(
                    name = "hit_503_cfMitigatedHeader",
                    statusCode = 503,
                    headers = listOf(
                        "cf-mitigated" to "challenge",
                        "Content-Type" to "text/html",
                    ),
                    bodyBytes = "<html><body>Service unavailable challenge</body></html>".toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 503, byHeader = true),
                ),
                case(
                    name = "hit_403_multipleCfMitigatedHeaders_matchesSecondValue",
                    statusCode = 403,
                    headers = listOf(
                        "cf-mitigated" to "other",
                        "CF-MITIGATED" to "managed-challenge",
                    ),
                    bodyBytes = "blocked".toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 403, byHeader = true),
                ),
                // === Positive hits: Rule B (body fallback without cf-mitigated) ===
                case(
                    name = "hit_403_noHeader_cfChlOptMarker",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                    bodyBytes = "<html><script>window._cf_chl_opt={cType:'managed'};</script></html>"
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                ),
                case(
                    name = "hit_503_noHeader_challengePlatformHPath",
                    statusCode = 503,
                    headers = listOf("Content-Type" to "text/html"),
                    bodyBytes = """<html><script src="/cdn-cgi/challenge-platform/h/b/scripts/alpha/invisible.js"></script></html>"""
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 503, byHeader = false),
                ),
                case(
                    name = "hit_403_noHeader_orchestrateChlPage",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "TEXT/HTML; charset=UTF-8"),
                    bodyBytes = """<html><script src="/cdn-cgi/challenge-platform/orchestrate/chl_page/v1"></script></html>"""
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                ),
                case(
                    name = "hit_403_noHeader_justAMomentAndCloudflare",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                    bodyBytes = """<html><head><title>Just a moment...</title></head><body>Performance &amp; security by Cloudflare</body></html>"""
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                ),
                case(
                    name = "hit_503_noHeader_emptyContentType_justAMomentAndCfChallenge",
                    statusCode = 503,
                    headers = emptyList(),
                    bodyBytes = """<html><head><TITLE>JUST A MOMENT...</TITLE></head><body class="cf-challenge">Checking</body></html>"""
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.Challenge(statusCode = 503, byHeader = false),
                ),
                // === Negative misses ===
                case(
                    name = "miss_200_gbkPageWithJsdScriptAndCfCvParams",
                    statusCode = 200,
                    headers = listOf("Content-Type" to "text/html; charset=GBK"),
                    bodyBytes = (
                        "<html><head><title>Discovery - HiPDA</title></head><body>" +
                            "<div id=\"umenu\"><a href=\"space.php\">中文用户</a></div>" +
                            "<p>正常帖子列表内容</p>" +
                            JSD_FOOTER_SNIPPET +
                            "</body></html>"
                        ).toByteArray(GBK),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_500_nginxErrorPageWithJsdScript",
                    statusCode = 500,
                    headers = listOf("Content-Type" to "text/html"),
                    bodyBytes = (
                        "<html><head><title>500 Internal Server Error</title></head>" +
                            "<body><center><h1>500 Internal Server Error</h1></center><hr><center>nginx</center>" +
                            JSD_FOOTER_SNIPPET +
                            "</body></html>"
                        ).toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_403_noHeader_onlyChallengePlatformAndJsd",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "text/html"),
                    bodyBytes = (
                        "<html><head><title>403 Forbidden</title></head><body>Access denied by Cloudflare" +
                            JSD_FOOTER_SNIPPET +
                            "</body></html>"
                        ).toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_429_noHeader_bodyFallbackDoesNotInspect429",
                    statusCode = 429,
                    headers = listOf("Content-Type" to "text/html"),
                    bodyBytes = MANAGED_CHALLENGE_HTML.toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_403_noHeader_jsonContentTypeWithCfChlOpt",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "application/json; charset=utf-8"),
                    bodyBytes = """{"error":"forbidden","debug":"window._cf_chl_opt"}""".toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_200_cfMitigatedChallengeHeader",
                    statusCode = 200,
                    headers = listOf(
                        "cf-mitigated" to "challenge",
                        "Content-Type" to "text/html; charset=UTF-8",
                    ),
                    bodyBytes = MANAGED_CHALLENGE_HTML.toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_403_noHeader_markerBeyond16KbPeekLimit",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                    bodyBytes = (
                        "a".repeat(ChallengeDetector.DEFAULT_PEEK_LIMIT_BYTES.toInt()) +
                            "window._cf_chl_opt={cType:'managed'};"
                        ).toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_403_noHeader_justAMomentWithoutCloudflareOrCfChallenge",
                    statusCode = 403,
                    headers = listOf("Content-Type" to "text/html"),
                    bodyBytes = "<html><head><title>Just a moment...</title></head><body>Please wait</body></html>"
                        .toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
                case(
                    name = "miss_403_cfMitigatedNonChallengeHeader_skipsBodyFallback",
                    statusCode = 403,
                    headers = listOf(
                        "cf-mitigated" to "block",
                        "Content-Type" to "text/html",
                    ),
                    bodyBytes = MANAGED_CHALLENGE_HTML.toByteArray(Charsets.UTF_8),
                    expected = ChallengeSignal.None,
                ),
            )

            private fun case(
                name: String,
                statusCode: Int,
                headers: List<Pair<String, String>>,
                bodyBytes: ByteArray,
                expected: ChallengeSignal,
            ): Array<Any> = arrayOf(name, statusCode, headers, bodyBytes, expected)

            private const val MANAGED_CHALLENGE_HTML =
                """<!DOCTYPE html><html><head><title>Just a moment...</title></head>""" +
                    """<body><script>window._cf_chl_opt={cType:'managed'};</script>""" +
                    """<footer>Performance &amp; security by Cloudflare</footer></body></html>"""

            private const val JSD_FOOTER_SNIPPET =
                "<script>(function(){window.__CF\$cv\$params={r:'abc123',t:'MTcwMDAw'};" +
                    "var a=document.createElement('script');a.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js';" +
                    "document.head.appendChild(a);})();</script>"
        }
    }

    class BodyPreservationAndConfiguration {

        @Test
        fun inspect_doesNotConsumeOrCloseOriginalBody_acrossLargePayloads() {
            val gbk = Charset.forName("GBK")
            val largeGbkBytes = (
                "<html><body>" + "论坛中文内容测试".repeat(3000) +
                    "<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></body></html>"
                ).toByteArray(gbk)
            val largeChallengeBytes = (
                "<html><head><title>Just a moment...</title></head><body>" +
                    "<script>window._cf_chl_opt={cType:'managed'};</script>" +
                    "x".repeat(24 * 1024) + "TAIL_MARKER</body></html>"
                ).toByteArray(Charsets.UTF_8)
            val largePostPeekMarkerBytes = (
                "p".repeat(16 * 1024) + "window._cf_chl_opt={cType:'managed'};" + "q".repeat(4 * 1024)
                ).toByteArray(Charsets.UTF_8)

            val server = MockWebServer()
            server.start()
            val client = OkHttpClient.Builder().followRedirects(false).build()
            val detector = ChallengeDetector()
            try {
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=GBK")
                        .body(Buffer().write(largeGbkBytes))
                        .build(),
                )
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html; charset=UTF-8")
                        .body(Buffer().write(largeChallengeBytes))
                        .build(),
                )
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html; charset=UTF-8")
                        .body(Buffer().write(largePostPeekMarkerBytes))
                        .build(),
                )

                client.newCall(Request.Builder().url(server.url("/gbk")).build()).execute().use { resp ->
                    assertEquals(ChallengeSignal.None, detector.inspect(resp))
                    assertArrayEquals(largeGbkBytes, resp.body.bytes())
                }
                client.newCall(Request.Builder().url(server.url("/challenge")).build()).execute().use { resp ->
                    assertEquals(
                        ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                        detector.inspect(resp),
                    )
                    assertArrayEquals(largeChallengeBytes, resp.body.bytes())
                }
                client.newCall(Request.Builder().url(server.url("/late-marker")).build()).execute().use { resp ->
                    assertEquals(ChallengeSignal.None, detector.inspect(resp))
                    assertArrayEquals(largePostPeekMarkerBytes, resp.body.bytes())
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }
        }

        @Test
        fun inspect_respectsCustomPeekLimitBytes() {
            val server = MockWebServer()
            server.start()
            val client = OkHttpClient.Builder().followRedirects(false).build()
            val smallPeekDetector = ChallengeDetector(peekLimitBytes = 64L)
            try {
                val withinLimit = ("a".repeat(32) + "_cf_chl_opt").toByteArray(Charsets.UTF_8)
                val beyondLimit = ("a".repeat(64) + "_cf_chl_opt").toByteArray(Charsets.UTF_8)

                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html")
                        .body(Buffer().write(withinLimit))
                        .build(),
                )
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html")
                        .body(Buffer().write(beyondLimit))
                        .build(),
                )

                client.newCall(Request.Builder().url(server.url("/within")).build()).execute().use { resp ->
                    assertEquals(
                        ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                        smallPeekDetector.inspect(resp),
                    )
                    assertArrayEquals(withinLimit, resp.body.bytes())
                }
                client.newCall(Request.Builder().url(server.url("/beyond")).build()).execute().use { resp ->
                    assertEquals(ChallengeSignal.None, smallPeekDetector.inspect(resp))
                    assertArrayEquals(beyondLimit, resp.body.bytes())
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }
        }

        @Test
        fun inspect_neverCallsPeekBody_forNonFallbackResponses() {
            val detector = ChallengeDetector()
            val htmlMediaType = "text/html; charset=UTF-8".toMediaType()
            val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

            val cases = listOf(
                Triple(
                    buildSyntheticResponse(
                        code = 200,
                        headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                        body = throwingProbeBody(htmlMediaType),
                    ),
                    ChallengeSignal.None,
                    "200 HTML",
                ),
                Triple(
                    buildSyntheticResponse(
                        code = 429,
                        headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                        body = throwingProbeBody(htmlMediaType),
                    ),
                    ChallengeSignal.None,
                    "429 without cf-mitigated",
                ),
                Triple(
                    buildSyntheticResponse(
                        code = 403,
                        headers = listOf(
                            "cf-mitigated" to "challenge",
                            "Content-Type" to "text/html; charset=UTF-8",
                        ),
                        body = throwingProbeBody(htmlMediaType),
                    ),
                    ChallengeSignal.Challenge(statusCode = 403, byHeader = true),
                    "403 with cf-mitigated: challenge",
                ),
                Triple(
                    buildSyntheticResponse(
                        code = 403,
                        headers = listOf(
                            "cf-mitigated" to "block",
                            "Content-Type" to "text/html; charset=UTF-8",
                        ),
                        body = throwingProbeBody(htmlMediaType),
                    ),
                    ChallengeSignal.None,
                    "403 with cf-mitigated: block",
                ),
                Triple(
                    buildSyntheticResponse(
                        code = 403,
                        headers = listOf("Content-Type" to "application/json; charset=UTF-8"),
                        body = throwingProbeBody(jsonMediaType),
                    ),
                    ChallengeSignal.None,
                    "403 with application/json header",
                ),
            )

            for ((response, expected, label) in cases) {
                assertEquals(label, expected, detector.inspect(response))
            }
        }

        @Test
        fun inspect_exact16KbPeekBoundary_hitsAtLimitAndMissesWhenCrossingByOneByte() {
            val marker = "_cf_chl_opt"
            val limit = ChallengeDetector.DEFAULT_PEEK_LIMIT_BYTES.toInt()
            val exactHitBytes = ("a".repeat(limit - marker.length) + marker + "TAIL").toByteArray(Charsets.UTF_8)
            val crossByOneBytes = ("a".repeat(limit - marker.length + 1) + marker + "TAIL").toByteArray(Charsets.UTF_8)

            val server = MockWebServer()
            server.start()
            val client = OkHttpClient.Builder().followRedirects(false).build()
            val detector = ChallengeDetector()
            try {
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html; charset=UTF-8")
                        .body(Buffer().write(exactHitBytes))
                        .build(),
                )
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html; charset=UTF-8")
                        .body(Buffer().write(crossByOneBytes))
                        .build(),
                )

                client.newCall(Request.Builder().url(server.url("/exact-hit")).build()).execute().use { resp ->
                    assertEquals(
                        ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                        detector.inspect(resp),
                    )
                    assertArrayEquals(exactHitBytes, resp.body.bytes())
                }
                client.newCall(Request.Builder().url(server.url("/cross-by-one")).build()).execute().use { resp ->
                    assertEquals(ChallengeSignal.None, detector.inspect(resp))
                    assertArrayEquals(crossByOneBytes, resp.body.bytes())
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }
        }

        @Test
        fun inspect_gbkAdjacentMarkerAndFallbackBodyContentTypeWithoutHeader() {
            val gbk = Charset.forName("GBK")
            val gbkBodyBytes = "<html><body>安全校验中文_cf_chl_opt校验继续</body></html>".toByteArray(gbk)
            val detector = ChallengeDetector()

            // 403 without cf-mitigated + Content-Type: text/html; charset=GBK + GBK text adjacent to _cf_chl_opt
            val server = MockWebServer()
            server.start()
            val client = OkHttpClient.Builder().followRedirects(false).build()
            try {
                server.enqueue(
                    MockResponse.Builder()
                        .code(403)
                        .addHeader("Content-Type", "text/html; charset=GBK")
                        .body(Buffer().write(gbkBodyBytes))
                        .build(),
                )
                client.newCall(Request.Builder().url(server.url("/gbk-challenge")).build()).execute().use { resp ->
                    assertEquals(
                        ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                        detector.inspect(resp),
                    )
                    assertArrayEquals(gbkBodyBytes, resp.body.bytes())
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }

            // No Content-Type header, but ResponseBody.contentType() is non-null HTML -> hits body fallback
            val htmlBodyNoHeader = buildSyntheticResponse(
                code = 403,
                headers = emptyList(),
                body = gbkBodyBytes.toResponseBody("text/html; charset=GBK".toMediaType()),
            )
            assertEquals(
                ChallengeSignal.Challenge(statusCode = 403, byHeader = false),
                detector.inspect(htmlBodyNoHeader),
            )

            // No Content-Type header, but ResponseBody.contentType() is non-null JSON -> skips peekBody
            val jsonBodyNoHeader = buildSyntheticResponse(
                code = 403,
                headers = emptyList(),
                body = throwingProbeBody("application/json; charset=UTF-8".toMediaType()),
            )
            assertEquals(ChallengeSignal.None, detector.inspect(jsonBodyNoHeader))
        }

        @Test
        fun inspect_returnsNone_whenPeekBodySourceThrowsIOException() {
            val detector = ChallengeDetector()
            val ioFailingBody = object : ResponseBody() {
                override fun contentType(): MediaType = "text/html; charset=UTF-8".toMediaType()
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        throw IOException("simulated peek read failure")
                    }

                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() {}
                }.buffer()
            }
            val response = buildSyntheticResponse(
                code = 403,
                headers = listOf("Content-Type" to "text/html; charset=UTF-8"),
                body = ioFailingBody,
            )

            assertEquals(ChallengeSignal.None, detector.inspect(response))
        }

        @Test
        fun hiltAnnotationsAreConfiguredForInjection() {
            assertTrue(ChallengeDetector::class.java.isAnnotationPresent(Singleton::class.java))
            val noArgConstructor = ChallengeDetector::class.java.getDeclaredConstructor()
            assertTrue(noArgConstructor.isAnnotationPresent(Inject::class.java))
        }

        private fun buildSyntheticResponse(
            code: Int,
            headers: List<Pair<String, String>>,
            body: ResponseBody,
        ): Response = Response.Builder()
            .request(Request.Builder().url("https://www.4d4y.com/forum/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test")
            .apply {
                for ((name, value) in headers) {
                    addHeader(name, value)
                }
            }
            .body(body)
            .build()

        private fun throwingProbeBody(mediaType: MediaType?): ResponseBody = object : ResponseBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource {
                throw AssertionError("peekBody must not read ResponseBody for this response")
            }
        }
    }
}
