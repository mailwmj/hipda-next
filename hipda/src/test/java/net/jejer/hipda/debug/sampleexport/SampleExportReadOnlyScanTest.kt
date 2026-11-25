package net.jejer.hipda.debug.sampleexport

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 只读源码扫描测试（守住 DISPATCH §0 只读红线）：
 * 对 `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/` 下全部 `.kt` 源码剥离注释后做跨行全文件扫描，
 * 严禁出现任何对论坛的 POST/PUT/DELETE/PATCH、可变 `.method(`、表单提交构造、JS 表单注入或 `HttpURLConnection` 写路径。
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
            ForbiddenRule(".post(", Regex("""\.\s*post\s*\(""")),
            ForbiddenRule(".put(", Regex("""\.\s*put\s*\(""")),
            ForbiddenRule(".delete(", Regex("""\.\s*delete\s*\(""")),
            ForbiddenRule(".patch(", Regex("""\.\s*patch\s*\(""")),
            ForbiddenRule(".method(", Regex("""\.\s*method\s*\(""")),
            ForbiddenRule("Request(method=...)", Regex("""\bRequest\s*\([^)]{0,400}\bmethod\s*=""")),
            ForbiddenRule("toRequestBody", Regex("""\btoRequestBody\b""")),
            ForbiddenRule(".postUrl(", Regex("""\.\s*postUrl\s*\(""")),
            ForbiddenRule("FormBody", Regex("""\bFormBody\b""")),
            ForbiddenRule("MultipartBody", Regex("""\bMultipartBody\b""")),
            ForbiddenRule("GbkForm", Regex("""\b(?:GbkForm|gbkFormBody)\b""")),
            ForbiddenRule("evaluateJavascript", Regex("""\bevaluateJavascript\b""")),
            ForbiddenRule(
                "loadUrl(\"javascript:...\")",
                Regex("""loadUrl\s*\(\s*"{1,3}\s*javascript:""", RegexOption.IGNORE_CASE),
            ),
            ForbiddenRule(
                "HttpURLConnection/openConnection(",
                Regex("""\bHttpURLConnection\b|\.\s*openConnection\s*\("""),
            ),
            ForbiddenRule(
                "action query write param",
                Regex("""action\s*=\s*(?:add|del|remove|addblack|delblack|logout)\b""", RegexOption.IGNORE_CASE),
            ),
            ForbiddenRule(
                "action Pair write param",
                Regex(""""action"\s*(?:to\s*|,\s*)"(?:add|del|remove|addblack|delblack|logout)"""", RegexOption.IGNORE_CASE),
            ),
            ForbiddenRule(
                "submit form param",
                Regex("""\b(?:login|topic|newthread|reply|edit|pm|poll|delete)submit\b""", RegexOption.IGNORE_CASE),
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
            val lineStarts = computeLineStarts(withoutComments)
            val violations = mutableListOf<Violation>()
            for (rule in FORBIDDEN_RULES) {
                for (match in rule.regex.findAll(withoutComments)) {
                    val lineNumber = lineNumberAt(lineStarts, match.range.first)
                    violations += Violation(relativePath, lineNumber, rule.label)
                }
            }
            return violations.sortedWith(compareBy<Violation>({ it.line }, { it.rule }))
        }

        private fun computeLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>(128)
            starts += 0
            for (idx in text.indices) {
                if (text[idx] == '\n') starts += idx + 1
            }
            return starts.toIntArray()
        }

        private fun lineNumberAt(lineStarts: IntArray, offset: Int): Int {
            var lo = 0
            var hi = lineStarts.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lineStarts[mid] <= offset) lo = mid + 1 else hi = mid - 1
            }
            return hi + 1
        }

        /**
         * 仅剥离 `//` 与 `/* ... */` 注释并保留行号；保留字符串字面量，以便跨行捕获写操作 URL、Pair 与方法名。
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
    fun scanner_detectsMultilineMethodVariableFormsPairActionsJsAndHttpUrlConnection() {
        val badSource = """
            package test.bad
            import java.net.HttpURLConnection
            import java.net.URL
            import okhttp3.FormBody
            import okhttp3.MultipartBody
            import okhttp3.Request
            import okhttp3.RequestBody.Companion.toRequestBody
            import net.jejer.hipda.data.network.gbkFormBody

            fun bad(url: String, verb: String, webView: android.webkit.WebView) {
                val r1 = Request.Builder().url(url).post(FormBody.Builder().build()).build()
                val r2 = Request.Builder().url(url).put(MultipartBody.Builder().build()).build()
                val r3 = Request.Builder().url(url).delete().build()
                val r4 = Request.Builder().url(url).patch(gbkFormBody(emptyList())).build()
                val r5 = Request.Builder()
                    .url(url)
                    .method(
                        verb,
                        "x".toRequestBody(),
                    )
                    .build()
                val r6 = Request(
                    url = url,
                    method = verb,
                )
                val u1 = "https://www.4d4y.com/forum/logging.php?action=logout&loginsubmit=yes"
                val u2 = "https://www.4d4y.com/forum/my.php?item=favorites&action=remove&tid=1"
                val p1 = forumEndpoint(
                    "my.php",
                    "action"
                        to "add",
                )
                val p2 = forumEndpoint("post.php", "topicsubmit" to "yes")
                webView.evaluateJavascript("document.forms[0].submit()", null)
                webView.loadUrl("javascript:document.forms[0].submit()")
                val conn = URL(url).openConnection() as HttpURLConnection
            }
        """.trimIndent()

        val violations = scanKotlinSource("BadSample.kt", badSource)
        val rules = violations.map { it.rule }.toSet()

        assertTrue("应检出 .post(: $rules", ".post(" in rules)
        assertTrue("应检出 .put(: $rules", ".put(" in rules)
        assertTrue("应检出 .delete(: $rules", ".delete(" in rules)
        assertTrue("应检出 .patch(: $rules", ".patch(" in rules)
        assertTrue("应检出跨行/变量 .method(: $rules", ".method(" in rules)
        assertTrue("应检出 Request(method=...): $rules", "Request(method=...)" in rules)
        assertTrue("应检出 toRequestBody: $rules", "toRequestBody" in rules)
        assertTrue("应检出 FormBody: $rules", "FormBody" in rules)
        assertTrue("应检出 MultipartBody: $rules", "MultipartBody" in rules)
        assertTrue("应检出 GbkForm: $rules", "GbkForm" in rules)
        assertTrue("应检出 evaluateJavascript: $rules", "evaluateJavascript" in rules)
        assertTrue("应检出 loadUrl(\"javascript:...\"): $rules", "loadUrl(\"javascript:...\")" in rules)
        assertTrue("应检出 HttpURLConnection/openConnection(: $rules", "HttpURLConnection/openConnection(" in rules)
        assertTrue("应检出 action query write param: $rules", "action query write param" in rules)
        assertTrue("应检出 action Pair write param: $rules", "action Pair write param" in rules)
        assertTrue("应检出 submit form param: $rules", "submit form param" in rules)
    }

    /**
     * 自证测试：基于真实生产源码 `SampleExportEngine.kt` 在内存中仅改坏 1 行，扫描测试必然变红。
     */
    @Test
    fun mutationSelfProof_mutatingSingleLineInRealEngineImmediatelyFailsScan() {
        val moduleDir = resolveHipdaModuleDir()
        val engineFile = File(moduleDir, "$TARGET_RELATIVE_DIR/SampleExportEngine.kt")
        val realSource = engineFile.readText(Charsets.UTF_8)
        assertTrue(
            "未改坏前的真实源码应零违规",
            scanKotlinSource("SampleExportEngine.kt", realSource).isEmpty(),
        )

        // 1) 把唯一请求行的 .get() 改坏为跨行变量 .method(verb, body)
        val methodTarget = "Request.Builder().url(url).get().build()"
        assertTrue("真实源码应包含预期 GET 构造", realSource.contains(methodTarget))
        val mutatedMethod = realSource.replace(
            methodTarget,
            "Request.Builder().url(url)\n.method(\nverb,\nnull,\n).build()",
        )
        val methodViolations = scanKotlinSource("SampleExportEngine.kt", mutatedMethod)
        assertTrue("改坏 .get() 为跨行 .method( 必须检出违规: $methodViolations", methodViolations.any { it.rule == ".method(" })

        // 2) 把 my_favorites 的 forumEndpoint 改坏一行加入 "action" to "remove"
        val favTarget = """forumEndpoint("my.php", "item" to "favorites", "type" to "thread", "page" to "1")"""
        assertTrue("真实源码应包含收藏只读端点", realSource.contains(favTarget))
        val mutatedFav = realSource.replace(
            favTarget,
            """forumEndpoint("my.php", "item" to "favorites", "action" to "remove", "page" to "1")""",
        )
        val favViolations = scanKotlinSource("SampleExportEngine.kt", mutatedFav)
        assertTrue("改坏 forumEndpoint 加入 action=remove 必须检出违规: $favViolations", favViolations.any { it.rule == "action Pair write param" })

        // 3) 把 post_form 的 forumEndpoint 改坏一行加入 "topicsubmit" to "yes"
        val postTarget = """forumEndpoint("post.php", "action" to "newthread", "fid" to "2")"""
        assertTrue("真实源码应包含发帖空表单端点", realSource.contains(postTarget))
        val mutatedPost = realSource.replace(
            postTarget,
            """forumEndpoint("post.php", "action" to "newthread", "fid" to "2", "topicsubmit" to "yes")""",
        )
        val postViolations = scanKotlinSource("SampleExportEngine.kt", mutatedPost)
        assertTrue("改坏 post.php 加入 topicsubmit 必须检出违规: $postViolations", postViolations.any { it.rule == "submit form param" })
    }
}
