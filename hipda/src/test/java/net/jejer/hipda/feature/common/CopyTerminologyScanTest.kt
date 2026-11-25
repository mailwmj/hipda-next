package net.jejer.hipda.feature.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 界面文案术语与错误泄漏门禁（product-design.md §8、§10.1、R-BATCH5 B1-1）。
 *
 * 扫描 `src/main/res/values/strings_*.xml` 与 `src/debug/res/values/strings_*.xml`（显式排除旧 `strings.xml`）：
 * 1. `product-design.md` §8 右栏全部 30 个精确禁止词组；
 * 2. 楼层误用正则 `(?:第\s*)?(?:\d+|%\d*\$?[ds])\s*层`（不误报 `楼层`/`层级`/`图层`）；
 * 3. 用户可见错误泄漏模式（异常类名 `*Exception`/`*Throwable`、HTTP 状态码模板 `HTTP 4xx/5xx`、`错误代码 (xxx)`）。
 *
 * 白名单采用与 `MotionTokenScanTest` 一致的“只能缩短、过期即报错”机制，仅豁免现网遗留 `strings_preference.xml` 两处旧条目，
 * 任何新增 `strings_<功能>.xml` 严格 0 豁免。
 */
class CopyTerminologyScanTest {

    data class AllowlistEntry(
        val relativePath: String,
        val resourceName: String,
        val matchedRule: String,
        val reason: String,
    )

    data class Violation(
        val relativePath: String,
        val line: Int,
        val resourceName: String,
        val rule: String,
        val matchedText: String,
        val value: String,
    ) {
        fun format(): String =
            "$relativePath:$line [$resourceName]: 命中禁用规则 [$rule]（匹配 \"$matchedText\"，原文 \"$value\"）"
    }

    data class ScanReport(
        val scannedFiles: List<String>,
        val unexpectedViolations: List<Violation>,
        val staleAllowlistEntries: List<AllowlistEntry>,
    ) {
        fun assertClean() {
            val messages = buildList {
                unexpectedViolations.forEach { add(it.format()) }
                staleAllowlistEntries.forEach { entry ->
                    add(
                        "白名单条目已无对应违规，白名单只能缩短，请立即删除：${entry.relativePath} " +
                            "[${entry.resourceName} / ${entry.matchedRule}]（${entry.reason}）",
                    )
                }
            }
            if (messages.isNotEmpty()) {
                fail(
                    buildString {
                        appendLine("文案术语与错误泄漏扫描失败（共 ${messages.size} 项）：")
                        messages.forEach { appendLine("  - $it") }
                    },
                )
            }
        }
    }

    companion object {
        /** 只能缩短的遗留白名单：仅限 M7-2 随旧设置页删除的 `strings_preference.xml` 2 处既有命中。 */
        private val ALLOWLIST: List<AllowlistEntry> = listOf(
            AllowlistEntry(
                relativePath = "src/main/res/values/strings_preference.xml",
                resourceName = "pref_category_notification",
                matchedRule = "消息通知",
                reason = "旧设置页遗留字符串，M7-2 删除旧界面时清零",
            ),
            AllowlistEntry(
                relativePath = "src/main/res/values/strings_preference.xml",
                resourceName = "pref_title_forum",
                matchedRule = "版面",
                reason = "旧设置页遗留字符串，M7-2 删除旧界面时清零",
            ),
        )

        /** `product-design.md` §8 右栏全部 30 个精确禁止词组。 */
        val FORBIDDEN_PHRASES: List<String> = listOf(
            "回帖",
            "回复帖子",
            "发表新帖",
            "发表主题",
            "发布新帖",
            "发布帖子",
            "按发表排序",
            "按发表时间排序",
            "引用回帖",
            "私信",
            "站内信",
            "信息提醒",
            "消息提醒",
            "帖子提醒",
            "消息通知",
            "只看主帖作者",
            "只看该作者",
            "只查看该作者",
            "搜索帖子",
            "他的帖子",
            "版面",
            "浏览记录",
            "屏蔽列表",
            "注销",
            "清除账号",
            "退出并清除",
            "没有数据",
            "暂无数据",
            "请稍候",
            "正在处理",
        )

        /** 楼层误用正则：检出 `第3层`/`5层`/`%1$d层`/`%d层`，不误报 `楼层`/`层级`/`图层`。 */
        val FLOOR_MISUSE_REGEX: Regex = Regex("""(?:第\s*)?(?:\d+|%\d*\$?[ds])\s*层""")

        /** 异常类名泄漏正则（覆盖 `IOException`/`SocketTimeoutException` 及裸 `Exception`/`Throwable`）。 */
        val EXCEPTION_CLASS_REGEX: Regex = Regex("""\b(?:[A-Z][A-Za-z0-9_]*)?(?:Exception|Throwable)\b""")

        /** HTTP 状态码模板泄漏正则。 */
        val HTTP_STATUS_TEMPLATE_REGEX: Regex = Regex("""(?:HTTP\s*[45]\d{2}|错误代码\s*\(\s*\d{3}\s*\))""")

        private val XML_COMMENT_REGEX = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
        private val STRING_TAG_REGEX = Regex(
            """<string\b[^>]*\bname\s*=\s*"([^"]+)"[^>]*>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val STRING_ARRAY_REGEX = Regex(
            """<string-array\b[^>]*\bname\s*=\s*"([^"]+)"[^>]*>(.*?)</string-array>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val ITEM_TAG_REGEX = Regex(
            """<item\b[^>]*>(.*?)</item>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        private fun resolveHipdaModuleDir(): File {
            val candidates = listOf(File("."), File("hipda"))
            return candidates.firstOrNull { dir ->
                File(dir, "src/main/res/values").isDirectory && File(dir, "src/debug/res/values").isDirectory
            } ?: error("无法定位 hipda 模块目录（当前工作目录：${File(".").absolutePath}）")
        }

        internal fun collectTargetXmlFiles(moduleDir: File): List<File> {
            val roots = listOf(
                File(moduleDir, "src/main/res/values"),
                File(moduleDir, "src/debug/res/values"),
            )
            return roots
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.listFiles().orEmpty()
                        .filter { file ->
                            file.isFile &&
                                file.name.startsWith("strings_") &&
                                file.name.endsWith(".xml") &&
                                file.name != "strings.xml"
                        }
                }
                .sortedBy { it.relativeTo(moduleDir).invariantSeparatorsPath }
        }

        internal fun scanDirectory(
            moduleDir: File,
            allowlist: List<AllowlistEntry> = ALLOWLIST,
        ): ScanReport {
            val files = collectTargetXmlFiles(moduleDir)
            val sources = linkedMapOf<String, String>()
            for (file in files) {
                val rel = file.relativeTo(moduleDir).invariantSeparatorsPath
                sources[rel] = file.readText()
            }
            return scanXmlMap(sources = sources, allowlist = allowlist)
        }

        internal fun scanXmlMap(
            sources: Map<String, String>,
            allowlist: List<AllowlistEntry> = emptyList(),
        ): ScanReport {
            val violations = mutableListOf<Violation>()
            for ((relPath, xml) in sources) {
                if (relPath.endsWith("/strings.xml") || relPath == "strings.xml") continue
                violations += scanXmlContent(relPath, xml)
            }

            val remainingAllowlist = allowlist.toMutableList()
            val unexpected = mutableListOf<Violation>()
            for (v in violations) {
                val idx = remainingAllowlist.indexOfFirst {
                    it.relativePath == v.relativePath &&
                        it.resourceName == v.resourceName &&
                        it.matchedRule == v.rule
                }
                if (idx >= 0) {
                    remainingAllowlist.removeAt(idx)
                } else {
                    unexpected += v
                }
            }
            return ScanReport(
                scannedFiles = sources.keys.toList(),
                unexpectedViolations = unexpected,
                staleAllowlistEntries = remainingAllowlist.toList(),
            )
        }

        internal fun scanXmlContent(relativePath: String, rawXml: String): List<Violation> {
            val stripped = stripXmlCommentsPreservingLines(rawXml)
            val lineStarts = computeLineStarts(stripped)
            val violations = mutableListOf<Violation>()

            for (match in STRING_TAG_REGEX.findAll(stripped)) {
                val name = match.groupValues[1]
                val value = unescapeXml(match.groupValues[2]).trim()
                val line = lineNumberAt(lineStarts, match.range.first)
                violations += checkEntry(relativePath, line, name, value)
            }

            for (arrayMatch in STRING_ARRAY_REGEX.findAll(stripped)) {
                val arrayName = arrayMatch.groupValues[1]
                val body = arrayMatch.groupValues[2]
                val bodyOffset = arrayMatch.groups[2]!!.range.first
                for (itemMatch in ITEM_TAG_REGEX.findAll(body)) {
                    val value = unescapeXml(itemMatch.groupValues[1]).trim()
                    val line = lineNumberAt(lineStarts, bodyOffset + itemMatch.range.first)
                    violations += checkEntry(relativePath, line, arrayName, value)
                }
            }

            return violations
        }

        private fun checkEntry(
            relativePath: String,
            line: Int,
            resourceName: String,
            value: String,
        ): List<Violation> {
            if (value.isEmpty()) return emptyList()
            val hits = mutableListOf<Violation>()

            for (phrase in FORBIDDEN_PHRASES) {
                if (value.contains(phrase)) {
                    hits += Violation(
                        relativePath = relativePath,
                        line = line,
                        resourceName = resourceName,
                        rule = phrase,
                        matchedText = phrase,
                        value = value,
                    )
                }
            }

            for (m in FLOOR_MISUSE_REGEX.findAll(value)) {
                hits += Violation(
                    relativePath = relativePath,
                    line = line,
                    resourceName = resourceName,
                    rule = "楼层误用(第N层/N层)",
                    matchedText = m.value,
                    value = value,
                )
            }

            for (m in EXCEPTION_CLASS_REGEX.findAll(value)) {
                hits += Violation(
                    relativePath = relativePath,
                    line = line,
                    resourceName = resourceName,
                    rule = "异常类名泄漏",
                    matchedText = m.value,
                    value = value,
                )
            }

            for (m in HTTP_STATUS_TEMPLATE_REGEX.findAll(value)) {
                hits += Violation(
                    relativePath = relativePath,
                    line = line,
                    resourceName = resourceName,
                    rule = "HTTP状态码模板泄漏",
                    matchedText = m.value,
                    value = value,
                )
            }

            return hits
        }

        private fun stripXmlCommentsPreservingLines(xml: String): String =
            XML_COMMENT_REGEX.replace(xml) { match ->
                buildString(match.value.length) {
                    for (ch in match.value) {
                        append(if (ch == '\n') '\n' else ' ')
                    }
                }
            }

        private fun unescapeXml(raw: String): String = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

        private fun computeLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>(64)
            starts += 0
            for (i in text.indices) {
                if (text[i] == '\n') starts += i + 1
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
    }

    @Test
    fun mainAndDebugStringResources_passTerminologyAndErrorLeakScan() {
        val moduleDir = resolveHipdaModuleDir()
        val report = scanDirectory(moduleDir, ALLOWLIST)
        assertTrue(
            "应扫描到 src/main/res/values/strings_common.xml，实际：${report.scannedFiles}",
            "src/main/res/values/strings_common.xml" in report.scannedFiles,
        )
        assertTrue(
            "应扫描到 src/debug/res/values/strings_preview.xml，实际：${report.scannedFiles}",
            "src/debug/res/values/strings_preview.xml" in report.scannedFiles,
        )
        assertFalse(
            "应显式排除旧界面 strings.xml",
            report.scannedFiles.any { it.endsWith("/strings.xml") },
        )
        report.assertClean()
    }

    @Test
    fun scanner_catchesForbiddenPhrasesFloorMisuseExceptionNamesAndHttpStatusTemplates() {
        val badXml = """
            <resources>
                <string name="bad_reply">快速回帖</string>
                <string name="bad_new_thread">发表新帖</string>
                <string name="bad_pm">查看私信</string>
                <string name="bad_wait">请稍候…</string>
                <string name="bad_floor_1">第3层引用</string>
                <string name="bad_floor_2">共 5层</string>
                <string name="bad_floor_3">%1${'$'}d层</string>
                <string name="bad_floor_4">跳转%d层</string>
                <string name="bad_exception">发生 SocketTimeoutException</string>
                <string name="bad_throwable">未知 Throwable</string>
                <string name="bad_http">请求失败 HTTP 403</string>
                <string name="bad_code">错误代码 (502)</string>
                <string-array name="bad_array">
                    <item>只看该作者</item>
                </string-array>
            </resources>
        """.trimIndent()

        val violations = scanXmlContent("src/main/res/values/strings_bad.xml", badXml)
        val byResource = violations.associateBy { it.resourceName }

        assertEquals("回帖", byResource["bad_reply"]?.rule)
        assertEquals("发表新帖", byResource["bad_new_thread"]?.rule)
        assertEquals("私信", byResource["bad_pm"]?.rule)
        assertEquals("请稍候", byResource["bad_wait"]?.rule)
        assertEquals("楼层误用(第N层/N层)", byResource["bad_floor_1"]?.rule)
        assertEquals("楼层误用(第N层/N层)", byResource["bad_floor_2"]?.rule)
        assertEquals("楼层误用(第N层/N层)", byResource["bad_floor_3"]?.rule)
        assertEquals("楼层误用(第N层/N层)", byResource["bad_floor_4"]?.rule)
        assertEquals("异常类名泄漏", byResource["bad_exception"]?.rule)
        assertEquals("异常类名泄漏", byResource["bad_throwable"]?.rule)
        assertEquals("HTTP状态码模板泄漏", byResource["bad_http"]?.rule)
        assertEquals("HTTP状态码模板泄漏", byResource["bad_code"]?.rule)
        assertEquals("只看该作者", byResource["bad_array"]?.rule)
    }

    @Test
    fun scanner_doesNotFalsePositiveOnValidFloorAndLayerTermsAndEnforcesShrinkOnlyAllowlist() {
        val validXml = """
            <resources>
                <!-- 注释里的 回帖 第3层 IOException HTTP 500 不应误报 -->
                <string name="ok_floor">没有符合条件的楼层</string>
                <string name="ok_hierarchy">版块标题与页面层级</string>
                <string name="ok_layer">混合图层效果</string>
                <string name="ok_floor_badge">%1${'$'}d楼</string>
                <string name="ok_floor_title">第 1 楼 · 作者</string>
                <string name="ok_sort">按发帖排序</string>
                <string name="ok_account">账号信息</string>
            </resources>
        """.trimIndent()

        val cleanReport = scanXmlMap(
            sources = mapOf("src/main/res/values/strings_valid.xml" to validXml),
        )
        cleanReport.assertClean()

        val staleEntry = AllowlistEntry(
            relativePath = "src/main/res/values/strings_valid.xml",
            resourceName = "ok_sort",
            matchedRule = "按发表排序",
            reason = "已修复条目",
        )
        val staleReport = scanXmlMap(
            sources = mapOf("src/main/res/values/strings_valid.xml" to validXml),
            allowlist = listOf(staleEntry),
        )
        assertEquals(listOf(staleEntry), staleReport.staleAllowlistEntries)
    }
}
