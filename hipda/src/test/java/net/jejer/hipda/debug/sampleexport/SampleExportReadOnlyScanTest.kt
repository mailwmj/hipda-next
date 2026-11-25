package net.jejer.hipda.debug.sampleexport

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 只读源码扫描测试（守住 DISPATCH §0 只读红线）：
 * 扫描 `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/` 下所有 `.kt` 源码，
 * 严禁出现任何对论坛的 POST/PUT/DELETE/PATCH 或表单提交构造。
 */
class SampleExportReadOnlyScanTest {

    data class ForbiddenRule(
        val label: String,
        val regex: Regex,
    )

    data class Violation(
        val relativePath: String,
        val line: Int,
        val rule: String,
    ) {
        fun format(): String = "$relativePath:$line 命中只读红线规则 [$rule]"
    }

    companion object {
        private const val TARGET_RELATIVE_DIR = "src/debug/java/net/jejer/hipda/debug/sampleexport"

        private val FORBIDDEN_RULES: List<ForbiddenRule> = listOf(
            ForbiddenRule(".post(", Regex("""\.post\s*\(""")),
            ForbiddenRule(".put(", Regex("""\.put\s*\(""")),
            ForbiddenRule(".delete(", Regex("""\.delete\s*\(""")),
            ForbiddenRule(".patch(", Regex("""\.patch\s*\(""")),
            ForbiddenRule(".postUrl(", Regex("""\.postUrl\s*\(""")),
            ForbiddenRule("FormBody", Regex("""\bFormBody\b""")),
            ForbiddenRule("MultipartBody", Regex("""\bMultipartBody\b""")),
            ForbiddenRule("GbkForm", Regex("""\b(?:GbkForm|gbkFormBody)\b""")),
            ForbiddenRule("loginsubmit", Regex("""loginsubmit""", RegexOption.IGNORE_CASE)),
            ForbiddenRule("action=add", Regex("""action\s*=\s*add\b""", RegexOption.IGNORE_CASE)),
            ForbiddenRule("action=del", Regex("""action\s*=\s*del""", RegexOption.IGNORE_CASE)),
            ForbiddenRule(
                "method(\"POST\"|\"PUT\"|\"DELETE\"|\"PATCH\")",
                Regex("""\.method\s*\(\s*"(?:POST|PUT|DELETE|PATCH)\"""", RegexOption.IGNORE_CASE),
            ),
            ForbiddenRule(
                "submit form param",
                Regex("""(?:newthread|reply|edit|pm|poll)submit""", RegexOption.IGNORE_CASE),
            ),
        )

        private fun resolveHipdaModuleDir(): File {
            val candidates = listOf(File("."), File("hipda"))
            return candidates.firstOrNull { dir ->
                File(dir, "src/main").isDirectory && File(dir, "src/debug").isDirectory
            } ?: error("无法定位 hipda 模块目录（当前目录：${File(".").absolutePath}）")
        }

        internal fun scanKotlinSource(relativePath: String, rawSource: String): List<Violation> {
            val withoutComments = stripCommentsPreservingStringsAndLines(rawSource)
            val violations = mutableListOf<Violation>()
            withoutComments.lineSequence().forEachIndexed { zeroIdx, lineText ->
                val lineNumber = zeroIdx + 1
                for (rule in FORBIDDEN_RULES) {
                    if (rule.regex.containsMatchIn(lineText)) {
                        violations += Violation(relativePath, lineNumber, rule.label)
                    }
                }
            }
            return violations
        }

        /**
         * 仅剥离 `//` 与 `/* ... */` 注释并保留行号；保留字符串字面量，以便捕获 `"action=add"`、`"POST"` 等写操作 URL/方法。
         */
        private fun stripCommentsPreservingStringsAndLines(src: String): String {
            val out = StringBuilder(src.length)
            var i = 0
            val n = src.length
            while (i < n) {
                val c = src[i]
                if (c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n) {
                        if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                            out.append("\"\"\"")
                            i += 3
                            break
                        }
                        out.append(src[i])
                        i++
                    }
                    continue
                }
                if (c == '"') {
                    out.append(c)
                    i++
                    while (i < n) {
                        val sc = src[i]
                        out.append(sc)
                        i++
                        if (sc == '\\' && i < n) {
                            out.append(src[i])
                            i++
                        } else if (sc == '"' || sc == '\n') {
                            break
                        }
                    }
                    continue
                }
                if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                    out.append("  ")
                    i += 2
                    while (i < n && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                    continue
                }
                if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                    var depth = 1
                    out.append("  ")
                    i += 2
                    while (i < n && depth > 0) {
                        if (src[i] == '/' && i + 1 < n && src[i + 1] == '*') {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                            depth--
                            out.append("  ")
                            i += 2
                        } else {
                            out.append(if (src[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                    continue
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }

    @Test
    fun sampleExportSources_containZeroWriteOperations() {
        val moduleDir = resolveHipdaModuleDir()
        val targetDir = File(moduleDir, TARGET_RELATIVE_DIR)
        assertTrue("未找到目录：${targetDir.absolutePath}", targetDir.isDirectory)

        val ktFiles = targetDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        assertTrue("sampleexport 目录下应至少存在 1 个 .kt 源码文件", ktFiles.isNotEmpty())

        val allViolations = ktFiles.flatMap { file ->
            val relPath = file.relativeTo(moduleDir).invariantSeparatorsPath
            scanKotlinSource(relPath, file.readText(Charsets.UTF_8))
        }

        if (allViolations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("样本导出模块违反只读红线（共 ${allViolations.size} 处）：")
                    allViolations.forEach { appendLine("  - ${it.format()}") }
                },
            )
        }
    }

    @Test
    fun scanner_detectsPostPutDeletePatchFormsAndWriteUrls() {
        val badSource = """
            package test.bad
            import okhttp3.FormBody
            import okhttp3.MultipartBody
            import okhttp3.Request
            import net.jejer.hipda.data.network.gbkFormBody

            fun bad(url: String) {
                val r1 = Request.Builder().url(url).post(FormBody.Builder().build()).build()
                val r2 = Request.Builder().url(url).put(MultipartBody.Builder().build()).build()
                val r3 = Request.Builder().url(url).delete().build()
                val r4 = Request.Builder().url(url).patch(gbkFormBody(emptyList())).build()
                val r5 = Request.Builder().url(url).method("POST", null).build()
                val u1 = "https://www.4d4y.com/forum/logging.php?action=login&loginsubmit=yes"
                val u2 = "https://www.4d4y.com/forum/my.php?item=favorites&action=add&tid=1"
                val u3 = "https://www.4d4y.com/forum/my.php?item=favorites&action=del&tid=1"
            }
        """.trimIndent()

        val violations = scanKotlinSource("BadSample.kt", badSource)
        val rules = violations.map { it.rule }.toSet()

        assertTrue("应检出 .post(: $rules", ".post(" in rules)
        assertTrue("应检出 .put(: $rules", ".put(" in rules)
        assertTrue("应检出 .delete(: $rules", ".delete(" in rules)
        assertTrue("应检出 .patch(: $rules", ".patch(" in rules)
        assertTrue("应检出 FormBody: $rules", "FormBody" in rules)
        assertTrue("应检出 MultipartBody: $rules", "MultipartBody" in rules)
        assertTrue("应检出 GbkForm: $rules", "GbkForm" in rules)
        assertTrue("应检出 loginsubmit: $rules", "loginsubmit" in rules)
        assertTrue("应检出 action=add: $rules", "action=add" in rules)
        assertTrue("应检出 action=del: $rules", "action=del" in rules)
        assertTrue(
            "应检出 method(\"POST\"): $rules",
            "method(\"POST\"|\"PUT\"|\"DELETE\"|\"PATCH\")" in rules,
        )
    }
}
