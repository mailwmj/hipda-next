package net.jejer.hipda.ui.designsystem.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 源码扫描测试：守住“所有动效只能从 HiMotion 令牌取参数，不得直接写时长与曲线，不得调用无动画参数的默认过渡 API”。
 */
class MotionTokenScanTest {

    data class AllowlistEntry(
        val relativePath: String,
        val rule: String,
        val reason: String,
    )

    data class Violation(
        val relativePath: String,
        val line: Int,
        val rule: String,
        val detail: String,
    ) {
        fun format(): String =
            "$relativePath:$line: 禁止直接使用 $rule（$detail），请改用 HiMotion 令牌或 hi* 过渡函数"
    }

    data class ScanReport(
        val scannedFileCount: Int,
        val exemptTokenFileFound: Boolean,
        val unexpectedViolations: List<Violation>,
        val staleAllowlistEntries: List<AllowlistEntry>,
    ) {
        fun assertClean() {
            val messages = buildList {
                unexpectedViolations.forEach { add(it.format()) }
                staleAllowlistEntries.forEach { entry ->
                    add(
                        "白名单条目已无对应违规，白名单只能缩短，请立即删除：${entry.relativePath} " +
                            "[${entry.rule}]（${entry.reason}）",
                    )
                }
            }
            if (messages.isNotEmpty()) {
                fail(
                    buildString {
                        appendLine("动效令牌扫描失败（共 ${messages.size} 项）：")
                        messages.forEach { appendLine("  - $it") }
                    },
                )
            }
        }
    }

    companion object {
        private const val TOKEN_FILE_RELATIVE_PATH =
            "src/main/java/net/jejer/hipda/ui/designsystem/motion/HiMotion.kt"

        /**
         * 例外白名单（只能缩短，不能残留无效项）。目前 main 上零违规。
         */
        private val ALLOWLIST: List<AllowlistEntry> = emptyList()

        private val BANNED_CALLS = setOf(
            "tween",
            "spring",
            "keyframes",
            "keyframesWithSpline",
            "snap",
            "repeatable",
            "TweenSpec",
            "SpringSpec",
            "SnapSpec",
            "KeyframesSpec",
        )

        private val TRANSITION_CALLS_REQUIRING_SPEC = setOf(
            "fadeIn",
            "fadeOut",
            "slideIn",
            "slideInHorizontally",
            "slideInVertically",
            "slideOut",
            "slideOutHorizontally",
            "slideOutVertically",
            "scaleIn",
            "scaleOut",
            "expandIn",
            "expandHorizontally",
            "expandVertically",
            "shrinkOut",
            "shrinkHorizontally",
            "shrinkVertically",
            "animateContentSize",
        )

        private val ANIMATE_AS_STATE_REGEX = Regex("^animate[A-Za-z0-9_]*AsState$")

        private fun resolveHipdaModuleDir(): File {
            val candidates = listOf(File("."), File("hipda"))
            return candidates.firstOrNull { dir ->
                File(dir, "src/main").isDirectory && File(dir, "src/debug").isDirectory
            } ?: error(
                "无法定位 hipda 模块目录（当前工作目录：${File(".").absolutePath}）",
            )
        }

        internal fun scanDirectory(
            moduleDir: File,
            allowlist: List<AllowlistEntry> = ALLOWLIST,
        ): ScanReport {
            val scanRoots = listOf(File(moduleDir, "src/main"), File(moduleDir, "src/debug"))
            val ktFiles = scanRoots
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
                .sortedBy { it.path }

            var exemptFound = false
            val sources = linkedMapOf<String, String>()
            for (file in ktFiles) {
                val rel = file.relativeTo(moduleDir).invariantSeparatorsPath
                if (rel == TOKEN_FILE_RELATIVE_PATH) {
                    exemptFound = true
                    continue
                }
                sources[rel] = file.readText()
            }
            return scanSources(
                sources = sources,
                allowlist = allowlist,
                exemptTokenFileFound = exemptFound,
            )
        }

        internal fun scanSources(
            sources: Map<String, String>,
            allowlist: List<AllowlistEntry> = emptyList(),
            exemptTokenFileFound: Boolean = true,
        ): ScanReport {
            val allViolations = mutableListOf<Violation>()
            for ((relPath, rawContent) in sources) {
                if (relPath == TOKEN_FILE_RELATIVE_PATH) continue
                allViolations += scanKotlinSource(relPath, rawContent)
            }

            val remainingAllowlist = allowlist.toMutableList()
            val unexpected = mutableListOf<Violation>()
            for (v in allViolations) {
                val idx = remainingAllowlist.indexOfFirst {
                    it.relativePath == v.relativePath && it.rule == v.rule
                }
                if (idx >= 0) {
                    remainingAllowlist.removeAt(idx)
                } else {
                    unexpected += v
                }
            }
            val stale = remainingAllowlist.toList()
            return ScanReport(
                scannedFileCount = sources.size,
                exemptTokenFileFound = exemptTokenFileFound,
                unexpectedViolations = unexpected,
                staleAllowlistEntries = stale,
            )
        }

        private val IMPORT_ALIAS_REGEX = Regex(
            "^\\s*import\\s+(?:[A-Za-z0-9_`]+\\.)*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+as\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*;?\\s*$",
        )

        private fun collectImportAliases(cleaned: String): Map<String, String> {
            val aliases = mutableMapOf<String, String>()
            for (rawLine in cleaned.lineSequence()) {
                val match = IMPORT_ALIAS_REGEX.matchEntire(rawLine) ?: continue
                val original = match.groupValues[1]
                val alias = match.groupValues[2]
                aliases[alias] = original
            }
            return aliases
        }

        internal fun scanKotlinSource(relativePath: String, rawSource: String): List<Violation> {
            val cleaned = stripCommentsAndStringsPreservingLines(rawSource)
            val importAliases = collectImportAliases(cleaned)
            val lineStarts = computeLineStarts(cleaned)
            val violations = mutableListOf<Violation>()

            var i = 0
            val n = cleaned.length
            while (i < n) {
                if (!isIdentStart(cleaned[i])) {
                    i++
                    continue
                }
                val start = i
                i++
                while (i < n && isIdentPart(cleaned[i])) {
                    i++
                }
                val rawIdent = cleaned.substring(start, i)
                val line = lineNumberAt(lineStarts, start)

                if (isOnImportOrPackageLine(cleaned, lineStarts, line)) {
                    continue
                }

                val ident = importAliases[rawIdent] ?: rawIdent
                val aliasSuffix = if (ident != rawIdent) "（通过别名 $rawIdent 导入）" else ""

                // 1) durationMillis 任意位置禁止出现
                if (ident == "durationMillis") {
                    violations += Violation(
                        relativePath = relativePath,
                        line = line,
                        rule = "durationMillis",
                        detail = "直接硬编码动画时长$aliasSuffix",
                    )
                    continue
                }

                // 跳过函数定义名（如 fun foo(...)），仅检查调用点
                if (isPrecededByKeyword(cleaned, start, "fun")) {
                    continue
                }

                // 2) tween( / spring( / keyframes( / keyframes { / snap( / repeatable( / *Spec(
                if (ident in BANNED_CALLS) {
                    val afterTypeArgs = skipWhitespaceAndOptionalTypeArgs(cleaned, i)
                    val isInvocation = afterTypeArgs < n && (
                        cleaned[afterTypeArgs] == '(' ||
                            ((ident == "keyframes" || ident == "keyframesWithSpline") && cleaned[afterTypeArgs] == '{')
                        )
                    if (isInvocation) {
                        val ruleLabel = if (cleaned[afterTypeArgs] == '{') "$ident{" else "$ident("
                        violations += Violation(
                            relativePath = relativePath,
                            line = line,
                            rule = ruleLabel,
                            detail = "禁止在令牌文件外直接构造动画规格$aliasSuffix",
                        )
                    }
                    continue
                }

                // 3) fadeIn / fadeOut / slideIn* / slideOut* / scaleIn / scaleOut / expand* / shrink* / animateContentSize
                if (ident in TRANSITION_CALLS_REQUIRING_SPEC) {
                    val afterTypeArgs = skipWhitespaceAndOptionalTypeArgs(cleaned, i)
                    if (afterTypeArgs < n && cleaned[afterTypeArgs] == '(') {
                        val closeIdx = findMatchingParen(cleaned, afterTypeArgs)
                        if (closeIdx > afterTypeArgs) {
                            val argsText = cleaned.substring(afterTypeArgs + 1, closeIdx)
                            val args = splitTopLevelArgs(argsText)
                            val hasSpec = hasNamedArg(args, "animationSpec") ||
                                (args.isNotEmpty() && !isNamedArg(args[0]) && !args[0].startsWith("{"))
                            if (!hasSpec) {
                                violations += Violation(
                                    relativePath = relativePath,
                                    line = line,
                                    rule = "$ident(",
                                    detail = "无参或仅传非动画参数，缺少显式 animationSpec 令牌$aliasSuffix",
                                )
                            }
                        }
                    } else if (afterTypeArgs < n && cleaned[afterTypeArgs] == '{') {
                        // 例如 slideInHorizontally { it } / animateContentSize { _, _ -> }
                        violations += Violation(
                            relativePath = relativePath,
                            line = line,
                            rule = "$ident(",
                            detail = "仅传尾随 lambda，缺少显式 animationSpec 令牌$aliasSuffix",
                        )
                    }
                    continue
                }

                // 4) animate*AsState(...) 与 animateTo(...) 必须传入 animationSpec
                val isAnimateAsState = ANIMATE_AS_STATE_REGEX.matches(ident)
                val isAnimateTo = ident == "animateTo"
                if (isAnimateAsState || isAnimateTo) {
                    val afterTypeArgs = skipWhitespaceAndOptionalTypeArgs(cleaned, i)
                    if (afterTypeArgs < n && cleaned[afterTypeArgs] == '(') {
                        val closeIdx = findMatchingParen(cleaned, afterTypeArgs)
                        if (closeIdx > afterTypeArgs) {
                            val argsText = cleaned.substring(afterTypeArgs + 1, closeIdx)
                            val args = splitTopLevelArgs(argsText)
                            val positionalCount = args.takeWhile { !isNamedArg(it) }.size
                            val requiredPositionalForSpec = if (ident == "animateValueAsState") 3 else 2
                            val hasSpec = hasNamedArg(args, "animationSpec") ||
                                positionalCount >= requiredPositionalForSpec
                            if (!hasSpec) {
                                violations += Violation(
                                    relativePath = relativePath,
                                    line = line,
                                    rule = "$ident(",
                                    detail = "调用缺少 animationSpec 参数$aliasSuffix",
                                )
                            }
                        }
                    }
                }
            }

            return violations
        }

        private class KotlinSourceStripper(private val src: String) {
            private val out = StringBuilder(src.length)
            private val n = src.length
            private var i = 0

            fun strip(): String {
                scanCode(stopAtClosingBrace = false)
                return out.toString()
            }

            private fun scanCode(stopAtClosingBrace: Boolean) {
                var braceDepth = 0
                while (i < n) {
                    val c = src[i]
                    if (stopAtClosingBrace) {
                        if (c == '{') {
                            braceDepth++
                            out.append('{')
                            i++
                            continue
                        }
                        if (c == '}') {
                            if (braceDepth == 0) {
                                return
                            }
                            braceDepth--
                            out.append('}')
                            i++
                            continue
                        }
                    }
                    if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                        scanLineComment()
                        continue
                    }
                    if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                        scanBlockComment()
                        continue
                    }
                    if (c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                        scanRawString()
                        continue
                    }
                    if (c == '"') {
                        scanRegularString()
                        continue
                    }
                    if (c == '\'') {
                        scanCharLiteral()
                        continue
                    }
                    out.append(c)
                    i++
                }
            }

            private fun scanLineComment() {
                out.append("  ")
                i += 2
                while (i < n && src[i] != '\n') {
                    out.append(' ')
                    i++
                }
            }

            private fun scanBlockComment() {
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
            }

            private fun scanRegularString() {
                out.append(' ')
                i++
                while (i < n) {
                    val sc = src[i]
                    if (sc == '\\' && i + 1 < n) {
                        out.append(if (src[i + 1] == '\n') " \n" else "  ")
                        i += 2
                        continue
                    }
                    if (sc == '$' && i + 1 < n && src[i + 1] == '{') {
                        out.append("  ")
                        i += 2
                        scanCode(stopAtClosingBrace = true)
                        if (i < n && src[i] == '}') {
                            out.append(' ')
                            i++
                        }
                        continue
                    }
                    out.append(if (sc == '\n') '\n' else ' ')
                    i++
                    if (sc == '"' || sc == '\n') break
                }
            }

            private fun scanRawString() {
                out.append("   ")
                i += 3
                while (i < n) {
                    val rc = src[i]
                    if (rc == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                        while (i + 3 < n && src[i + 3] == '"') {
                            out.append(' ')
                            i++
                        }
                        out.append("   ")
                        i += 3
                        break
                    }
                    if (rc == '$' && i + 1 < n && src[i + 1] == '{') {
                        out.append("  ")
                        i += 2
                        scanCode(stopAtClosingBrace = true)
                        if (i < n && src[i] == '}') {
                            out.append(' ')
                            i++
                        }
                        continue
                    }
                    out.append(if (rc == '\n') '\n' else ' ')
                    i++
                }
            }

            private fun scanCharLiteral() {
                out.append(' ')
                i++
                while (i < n) {
                    val cc = src[i]
                    if (cc == '\\' && i + 1 < n) {
                        out.append(if (src[i + 1] == '\n') " \n" else "  ")
                        i += 2
                        continue
                    }
                    out.append(if (cc == '\n') '\n' else ' ')
                    i++
                    if (cc == '\'' || cc == '\n') break
                }
            }
        }

        private fun stripCommentsAndStringsPreservingLines(src: String): String =
            KotlinSourceStripper(src).strip()

        private fun computeLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>(128)
            starts += 0
            for (idx in text.indices) {
                if (text[idx] == '\n') {
                    starts += idx + 1
                }
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

        private fun isOnImportOrPackageLine(text: String, lineStarts: IntArray, line: Int): Boolean {
            val start = lineStarts[line - 1]
            val end = if (line < lineStarts.size) lineStarts[line] else text.length
            val trimmed = text.substring(start, end).trimStart()
            return trimmed.startsWith("import ") || trimmed.startsWith("package ")
        }

        private fun isPrecededByKeyword(text: String, identStart: Int, keyword: String): Boolean {
            var j = identStart - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            if (j < 0) return false
            // 支持泛型声明 fun <T> foo(...)
            if (text[j] == '>') {
                var depth = 1
                j--
                while (j >= 0 && depth > 0) {
                    if (text[j] == '>') depth++ else if (text[j] == '<') depth--
                    j--
                }
                while (j >= 0 && text[j].isWhitespace()) j--
            }
            val end = j + 1
            val start = end - keyword.length
            if (start < 0) return false
            if (text.substring(start, end) != keyword) return false
            return start == 0 || !isIdentPart(text[start - 1])
        }

        /**
         * 前瞻匹配合法泛型实参片段 `<...>`（支持跨行与嵌套泛型，支持函数类型 `->`）；
         * 若内部出现非法类型字符（运算符/分号/花括号等）或未闭合，立即回退返回 -1（按比较符号 `<` 处理）。
         */
        private fun tryMatchTypeArgList(
            text: String,
            openAngleIndex: Int,
            requireValidFollow: Boolean,
        ): Int {
            if (openAngleIndex >= text.length || text[openAngleIndex] != '<') return -1
            var depth = 1
            var i = openAngleIndex + 1
            while (i < text.length && depth > 0) {
                val c = text[i]
                when {
                    c == '<' -> {
                        depth++
                        i++
                    }
                    c == '-' && i + 1 < text.length && text[i + 1] == '>' -> {
                        // 函数类型箭头 -> 不消耗泛型深度
                        i += 2
                    }
                    c == '>' -> {
                        depth--
                        i++
                    }
                    c.isWhitespace() || isIdentPart(c) || c == '.' || c == ',' || c == '?' ||
                        c == '*' || c == '@' || c == '(' || c == ')' || c == ':' || c == '`' -> {
                        i++
                    }
                    else -> return -1
                }
            }
            if (depth != 0) return -1
            if (requireValidFollow) {
                val next = skipWhitespace(text, i)
                if (next < text.length) {
                    val follow = text[next]
                    val validFollow = follow == '(' || follow == '{' || follow == '.' ||
                        follow == ':' || follow == ',' || follow == ')' || follow == '>' ||
                        follow == ']' || follow == '?' || follow == '&' || follow == '|'
                    if (!validFollow) return -1
                }
            }
            return i
        }

        private fun skipWhitespaceAndOptionalTypeArgs(text: String, start: Int): Int {
            var i = skipWhitespace(text, start)
            if (i < text.length && text[i] == '<') {
                val afterAngles = tryMatchTypeArgList(text, i, requireValidFollow = false)
                if (afterAngles > i) {
                    i = skipWhitespace(text, afterAngles)
                }
            }
            return i
        }

        private fun skipWhitespace(text: String, start: Int): Int {
            var i = start
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        private fun findMatchingParen(text: String, openParenIndex: Int): Int {
            var depth = 0
            for (i in openParenIndex until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun splitTopLevelArgs(argsText: String): List<String> {
            val result = mutableListOf<String>()
            var paren = 0
            var bracket = 0
            var brace = 0
            var argStart = 0
            var i = 0
            while (i < argsText.length) {
                when (argsText[i]) {
                    '(' -> {
                        paren++
                        i++
                    }
                    ')' -> {
                        if (paren > 0) paren--
                        i++
                    }
                    '[' -> {
                        bracket++
                        i++
                    }
                    ']' -> {
                        if (bracket > 0) bracket--
                        i++
                    }
                    '{' -> {
                        brace++
                        i++
                    }
                    '}' -> {
                        if (brace > 0) brace--
                        i++
                    }
                    '<' -> {
                        if (paren == 0 && bracket == 0 && brace == 0) {
                            val afterAngles = tryMatchTypeArgList(argsText, i, requireValidFollow = true)
                            if (afterAngles > i) {
                                i = afterAngles
                                continue
                            }
                        }
                        i++
                    }
                    ',' -> {
                        if (paren == 0 && bracket == 0 && brace == 0) {
                            val token = argsText.substring(argStart, i).trim()
                            if (token.isNotEmpty()) result += token
                            argStart = i + 1
                        }
                        i++
                    }
                    else -> i++
                }
            }
            val tail = argsText.substring(argStart).trim()
            if (tail.isNotEmpty()) result += tail
            return result
        }

        private val NAMED_ARG_PREFIX = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)")

        private fun isNamedArg(arg: String): Boolean = NAMED_ARG_PREFIX.containsMatchIn(arg)

        private fun hasNamedArg(args: List<String>, name: String): Boolean =
            args.any { arg -> NAMED_ARG_PREFIX.find(arg)?.groupValues?.get(1) == name }

        private fun isIdentStart(c: Char): Boolean = c == '_' || c.isLetter()
        private fun isIdentPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
    }

    @Test
    fun mainAndDebugSources_haveNoMotionTokenViolations() {
        val moduleDir = resolveHipdaModuleDir()
        val report = scanDirectory(moduleDir, ALLOWLIST)
        assertTrue("应扫描到 src/main 与 src/debug 下的 Kotlin 源码", report.scannedFileCount > 0)
        assertTrue("未找到豁免的令牌文件 $TOKEN_FILE_RELATIVE_PATH", report.exemptTokenFileFound)
        report.assertClean()
    }

    @Test
    fun scanner_catchesMultilineAnimateAsStateAnimateToNoArgTransitionsAndRawSpecs() {
        val sample = """
            package test.sample
            import androidx.compose.animation.fadeIn
            import androidx.compose.animation.core.animateFloatAsState

            fun demo(target: Float) {
                val a = animateFloatAsState(
                    target
                )
                val b = animateDpAsState(
                    targetValue = 8.dp,
                    label = "pad",
                )
                val c = fadeIn()
                val d = fadeIn(initialAlpha = 0.2f)
                val e = slideInHorizontally { it }
                val f = Modifier.animateContentSize(alignment = Alignment.Center)
                val g = tween(durationMillis = 300)
                val h = spring(stiffness = 500f)
                val i = keyframes { durationMillis = 200 }
                val j = snap()
                anim.animateTo(
                    1f,
                    initialVelocity = 10f,
                )
                val nestedFake = animateFloatAsState(
                    compute(animationSpec = HiMotion.spatialDefault()),
                )
            }
        """.trimIndent()

        val violations = scanKotlinSource("src/main/java/Sample.kt", sample)
        val rules = violations.map { it.rule }

        assertTrue("应检出跨行 animateFloatAsState(: $rules", "animateFloatAsState(" in rules)
        assertTrue("应检出跨行 animateDpAsState(: $rules", "animateDpAsState(" in rules)
        assertEquals("应检出 2 处违规 fadeIn(: $violations", 2, violations.count { it.rule == "fadeIn(" })
        assertTrue("应检出尾随 lambda slideInHorizontally(: $rules", "slideInHorizontally(" in rules)
        assertTrue("应检出无 animationSpec 的 animateContentSize(: $rules", "animateContentSize(" in rules)
        assertTrue("应检出 tween(: $rules", "tween(" in rules)
        assertTrue("应检出 durationMillis: $rules", "durationMillis" in rules)
        assertTrue("应检出 spring(: $rules", "spring(" in rules)
        assertTrue("应检出 keyframes{: $rules", "keyframes{" in rules)
        assertTrue("应检出 snap(: $rules", "snap(" in rules)
        assertTrue("应检出跨行 animateTo(: $rules", "animateTo(" in rules)
        assertEquals(
            "嵌套调用里的 animationSpec 不能蒙混过关: $violations",
            2,
            violations.count { it.rule == "animateFloatAsState(" },
        )
        violations.forEach { v ->
            val msg = v.format()
            assertTrue("错误信息应含 文件:行: $msg", msg.startsWith("src/main/java/Sample.kt:${v.line}:"))
            assertTrue("错误信息应含引导文案: $msg", "改用 HiMotion 令牌或 hi* 过渡函数" in msg)
        }
    }

    @Test
    fun scanner_allowsTokenizedCallsCommentsStringsAndFlagsStaleAllowlist() {
        val validSample = """
            package test.sample
            // tween(300) spring() fadeIn() durationMillis
            /*
             * animateFloatAsState(target)
             * keyframes { durationMillis = 100 }
             */
            const val DOC = "fadeIn() spring() animateTo(1f)"

            fun ok(target: Float) {
                val a = animateFloatAsState(
                    targetValue = target,
                    animationSpec = HiMotion.effectsDefault(),
                )
                val b = animateFloatAsState(target, HiMotion.spatialFast())
                val c = hiFadeIn() + hiSlideInHorizontally()
                val d = fadeIn(animationSpec = HiMotion.effectsDefault(), initialAlpha = 0.2f)
                val e = Modifier.hiAnimateContentSize()
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = HiMotion.spatialSnap(),
                )
            }
        """.trimIndent()

        val cleanReport = scanSources(mapOf("src/main/java/Valid.kt" to validSample))
        cleanReport.assertClean()

        val staleEntry = AllowlistEntry(
            relativePath = "src/main/java/Valid.kt",
            rule = "fadeIn(",
            reason = "已修复的旧例外",
        )
        val staleReport = scanSources(
            sources = mapOf("src/main/java/Valid.kt" to validSample),
            allowlist = listOf(staleEntry),
        )
        assertEquals(listOf(staleEntry), staleReport.staleAllowlistEntries)
    }

    @Test
    fun scanner_handlesStringTemplateInterpolationNestedQuotesAndMimeWildcard() {
        // 阻断 1 触发用例 1：${...} 内嵌套双引号不应误报
        val validWithTemplates = """
            package test.sample

            fun format(json: Any, enabled: Boolean) {
                val raw = "dur=${'$'}{json.optString("durationMillis")}"
                val label = "mode=${'$'}{if (enabled) "spring(1)" else "none"}"
                val triple = ${"\"\"\""}
                    outer ${'$'}{if (enabled) "fadeIn()" else "image/*"} tail
                ${"\"\"\""}.trimIndent()
            }
        """.trimIndent()
        val validViolations = scanKotlinSource("src/main/java/TemplateValid.kt", validWithTemplates)
        assertEquals("字符串插值内的嵌套引号不应误报：$validViolations", emptyList<Violation>(), validViolations)

        // 阻断 1 触发用例 2："image/*" 不能吞掉后续业务代码，且插值中的真实违规调用也应检出
        val mimeFollowedByViolations = """
            package test.sample

            fun bindAttachment(mimeType: String?, target: Float) {
                val desc = "支持格式：${'$'}{mimeType ?: "image/*"}"
                val alpha = animateFloatAsState(target)
                val enter = fadeIn()
                val inTemplate = "v=${'$'}{animateDpAsState(target)}"
            }
        """.trimIndent()
        val caught = scanKotlinSource("src/main/java/Attachment.kt", mimeFollowedByViolations)
        val caughtRules = caught.map { it.rule }
        assertTrue("image/* 后的 animateFloatAsState( 必须被检出：$caught", "animateFloatAsState(" in caughtRules)
        assertTrue("image/* 后的 fadeIn( 必须被检出：$caught", "fadeIn(" in caughtRules)
        assertTrue("插值内部的真实违规 animateDpAsState( 必须被检出：$caught", "animateDpAsState(" in caughtRules)
    }

    @Test
    fun scanner_doesNotFalsePositiveOnLessThanComparisonInNamedArgs() {
        // 阻断 2 触发用例：命名参数含不带括号的 < 比较表达式时不能吞掉后续 animationSpec
        val validComparison = """
            package test.sample

            fun panel(itemCount: Int, maxVisibleItems: Int, a: Int, b: Int, c: Int, d: Int) {
                val enter = expandVertically(
                    clip = itemCount < maxVisibleItems,
                    animationSpec = HiMotion.spatialDefault(IntSize.VisibilityThreshold),
                )
                val second = expandVertically(
                    clip = a < b,
                    expandFrom = if (c > d) Alignment.Top else Alignment.Bottom,
                    animationSpec = HiMotion.spatialDefault(IntSize.VisibilityThreshold),
                )
            }
        """.trimIndent()
        val violations = scanKotlinSource("src/main/java/ComparisonArg.kt", validComparison)
        assertEquals("命名参数含 < 比较表达式不应误报：$violations", emptyList<Violation>(), violations)
    }

    @Test
    fun scanner_catchesImportAliasesAndMultilineGenericTypeArgs() {
        // 阻断 3 触发用例 1：import ... as 别名绕过
        val aliasSample = """
            package test.sample
            import androidx.compose.animation.core.tween as myTween
            import androidx.compose.animation.fadeIn as myFadeIn
            import androidx.compose.animation.animateContentSize as myResize

            fun demo() {
                val spec = myTween<Float>(300)
                val enter = myFadeIn()
                val mod = Modifier.myResize()
            }
        """.trimIndent()
        val aliasViolations = scanKotlinSource("src/main/java/AliasBypass.kt", aliasSample)
        val aliasRules = aliasViolations.map { it.rule }
        assertTrue("别名 myTween 必须被检出：$aliasViolations", "tween(" in aliasRules)
        assertTrue("别名 myFadeIn 必须被检出：$aliasViolations", "fadeIn(" in aliasRules)
        assertTrue("扩展函数别名 myResize 必须被检出：$aliasViolations", "animateContentSize(" in aliasRules)

        // 阻断 3 触发用例 2：跨行泛型实参绕过
        val multilineGenericSample = """
            package test.sample

            fun demo(target: IntOffset) {
                val offset by animateValueAsState<
                    IntOffset,
                    AnimationVector2D,
                >(
                    targetValue = target,
                    typeConverter = IntOffset.VectorConverter,
                )
            }
        """.trimIndent()
        val genericViolations = scanKotlinSource("src/main/java/MultilineGeneric.kt", multilineGenericSample)
        assertEquals(
            "跨行泛型实参的 animateValueAsState( 必须被检出：$genericViolations",
            listOf("animateValueAsState("),
            genericViolations.map { it.rule },
        )
    }

    @Test
    fun scanner_catchesSpecConstructorsRepeatableAndConsumesAllowlistOneToOne() {
        // 建议 4：*Spec 构造器与 repeatable
        val specConstructors = """
            package test.sample

            fun rawSpecs() {
                val a = TweenSpec<Float>(300)
                val b = SpringSpec<Float>(stiffness = 200f)
                val c = SnapSpec<Float>()
                val d = KeyframesSpec(KeyframesSpec.KeyframesSpecConfig<Float>())
                val e = repeatable<Float>(iterations = 2, animation = HiMotion.spinnerPeriod())
            }
        """.trimIndent()
        val specRules = scanKotlinSource("src/main/java/RawSpecs.kt", specConstructors).map { it.rule }
        assertTrue("应检出 TweenSpec(: $specRules", "TweenSpec(" in specRules)
        assertTrue("应检出 SpringSpec(: $specRules", "SpringSpec(" in specRules)
        assertTrue("应检出 SnapSpec(: $specRules", "SnapSpec(" in specRules)
        assertTrue("应检出 KeyframesSpec(: $specRules", "KeyframesSpec(" in specRules)
        assertTrue("应检出 repeatable(: $specRules", "repeatable(" in specRules)

        // 建议 4：单条白名单只能放行 1 处违规，同文件新增第 2 处同类违规必须被检出
        val twoFadeIns = """
            package test.sample
            fun two() {
                val first = fadeIn()
                val second = fadeIn()
            }
        """.trimIndent()
        val singleEntry = AllowlistEntry(
            relativePath = "src/main/java/TwoFadeIns.kt",
            rule = "fadeIn(",
            reason = "仅允许 1 处旧例",
        )
        val report = scanSources(
            sources = mapOf("src/main/java/TwoFadeIns.kt" to twoFadeIns),
            allowlist = listOf(singleEntry),
        )
        assertEquals(
            "单条白名单只能消费 1 处，第 2 处 fadeIn() 必须报出：${report.unexpectedViolations}",
            1,
            report.unexpectedViolations.size,
        )
        assertEquals(emptyList<AllowlistEntry>(), report.staleAllowlistEntries)
    }
}
