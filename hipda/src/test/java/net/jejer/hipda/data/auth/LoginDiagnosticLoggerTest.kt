package net.jejer.hipda.data.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDiagnosticLoggerTest {

    @Test
    fun redact_removesFormhashPasswordAnswerUsernameAndCookiesInAllRepresentations() {
        val raw = """
            Set-Cookie: cdb_auth=tok_header_secret_111+abc/def%3D; Path=/; HttpOnly
            Cookie: cdb_sid=sid_header_secret_222; cdb_auth=tok_header_secret_333; cf_clearance=cf_hdr_444
            <input type="hidden" name="formhash" value="abc123" />
            <input value="rev_fh_456" id="formhash" type="hidden" />
            <a href="logging.php?action=logout&amp;formhash=abc123&amp;m_formhash=mfh_789">退出</a>
            m_formhash=mfh_789&username=RealAlice99&password=SuperSecretPw!88&answer=MySecretAnswer77
            {"username":"JsonUser66","password":"JsonPw55","answer":"JsonAns44","formhash":"JsonFh33","cdb_auth":"JsonAuth22"}
            cdb_auth=raw_auth_token_11; cdb_sid=raw_sid_token_00
            document.cookie = "cdb_auth=js_cookie_secret_99; path=/"
            欢迎您回来，RealAlice99。
        """.trimIndent()

        val redacted = LoginDiagnosticLogger.redact(
            raw = raw,
            knownSecrets = listOf("RealAlice99", "0123456789abcdef0123456789abcdef", "MySecretAnswer77", "abc123"),
        )

        val forbidden = listOf(
            "tok_header_secret_111",
            "sid_header_secret_222",
            "tok_header_secret_333",
            "cf_hdr_444",
            "abc123",
            "rev_fh_456",
            "mfh_789",
            "RealAlice99",
            "SuperSecretPw!88",
            "MySecretAnswer77",
            "JsonUser66",
            "JsonPw55",
            "JsonAns44",
            "JsonFh33",
            "JsonAuth22",
            "raw_auth_token_11",
            "raw_sid_token_00",
            "js_cookie_secret_99",
        )
        for (secret in forbidden) {
            assertFalse("Redacted output leaked '$secret':\n$redacted", redacted.contains(secret, ignoreCase = true))
        }
        assertTrue("Redacted output should contain placeholder ***", redacted.contains("***"))
        assertTrue("Redacted output should keep diagnostic structure", redacted.contains("logging.php?action=logout"))
    }

    @Test
    fun formatLine_outputsSingleLineTruncatedTo400CharsAndRedactsBoundaryCrossingSecrets() {
        val boundarySecret = "BOUNDARY_SECRET_TOKEN_9876543210"
        val body = "A".repeat(390) + "formhash=$boundarySecret" + "B".repeat(500)

        val line = LoginDiagnosticLogger.formatLine(
            stage = "submit",
            statusCode = 200,
            contentType = "text/xml; charset=gbk\r\nX-Injected: bad",
            body = body,
            knownSecrets = listOf(boundarySecret),
        )

        assertFalse("Diagnostic log must be single-line: $line", line.contains('\n') || line.contains('\r'))
        assertTrue(line.startsWith("stage=submit status=200 contentType=text/xml; charset=gbk"))
        assertFalse("Boundary-crossing secret must not leak full value", line.contains(boundarySecret))
        assertFalse("Boundary-crossing secret must not leak prefix", line.contains("BOUNDARY_SECRET"))

        val bodyPart = line.substringAfter("body=")
        assertTrue("Body snippet must be <= 400 chars, got ${bodyPart.length}", bodyPart.length <= 400)
    }

    @Test
    fun releasePath_guardsAllHxLoginLogCallsWithBuildConfigDebug() {
        val moduleDir = resolveHipdaModuleDir()
        val loggerSrc = File(moduleDir, "src/main/java/net/jejer/hipda/data/auth/LoginDiagnosticLogger.kt").readText()
        val repoSrc = File(moduleDir, "src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt").readText()

        // 1) LoginDiagnosticLogger.logWarning must guard Log.w behind BuildConfig.DEBUG
        val logWarningBody = loggerSrc.substringAfter("fun logWarning(").substringBefore("internal fun formatLine(")
        val debugGuardIdx = logWarningBody.indexOf("if (!BuildConfig.DEBUG) return")
        val logCallIdx = logWarningBody.indexOf("Log.w(")
        assertTrue("LoginDiagnosticLogger.logWarning must check if (!BuildConfig.DEBUG) return", debugGuardIdx >= 0)
        assertTrue("LoginDiagnosticLogger.logWarning must call Log.w", logCallIdx > debugGuardIdx)
        val allLogCallsInLogger = Regex("""\bLog\.[vdiwea]\s*\(""").findAll(loggerSrc).count()
        assertTrue("LoginDiagnosticLogger must have exactly 1 guarded Log call, found $allLogCallsInLogger", allLogCallsInLogger == 1)

        // 2) DefaultAuthRepository must never call Log.* directly with HxLogin, and every logWarning call must be BuildConfig.DEBUG-guarded
        assertFalse("DefaultAuthRepository must not bypass LoginDiagnosticLogger with raw HxLogin tag", repoSrc.contains("\"HxLogin\""))
        val lines = repoSrc.lines()
        val warningCallLines = lines.mapIndexedNotNull { idx, line -> if (line.contains("LoginDiagnosticLogger.logWarning")) idx else null }
        assertTrue("Expected LoginDiagnosticLogger.logWarning calls in DefaultAuthRepository", warningCallLines.isNotEmpty())
        for (lineIdx in warningCallLines) {
            val window = lines.subList((lineIdx - 5).coerceAtLeast(0), lineIdx + 1).joinToString("\n")
            assertTrue(
                "LoginDiagnosticLogger.logWarning at line ${lineIdx + 1} must be guarded by BuildConfig.DEBUG:\n$window",
                window.contains("if (BuildConfig.DEBUG)"),
            )
        }
    }

    private fun resolveHipdaModuleDir(): File {
        val candidates = listOf(File("."), File("hipda"))
        return candidates.firstOrNull { dir ->
            File(dir, "src/main/java/net/jejer/hipda/data/auth/LoginDiagnosticLogger.kt").isFile
        } ?: error("Cannot locate hipda module dir from ${File(".").absolutePath}")
    }
}
