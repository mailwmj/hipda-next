package net.jejer.hipda.parser.threaddetail

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstElementOrNull
import net.jejer.hipda.parser.common.firstMatch
import net.jejer.hipda.parser.common.lastElementOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 帖子详情页（`viewthread.php`）纯 JVM 无副作用解析器（对照旧 `HiParserThreadDetail.java`）。
 *
 * 不可变与纯洁性保证：
 * - 零 Android 依赖、零全局设置/缓存读写、零后台线程启动；
 * - 绝不修改调用方传入的 [Document] DOM（全流程非递归显式栈遍历 + 局部 `.clone()` 提取）；
 * - 行内表情按 `C1b#2` 规则输出归一化键，文字颜色按 T6 原样透传至渲染层。
 */
object ThreadDetailParser {

    const val NOTICE_CONTENT_NOT_FOUND = "[[!!找不到帖子内容，可能是该帖被管理员或版主屏蔽!!]]"
    const val NOTICE_EMPTY_CONTENT = "[[无内容]]"
    const val NOTICE_UNPARSED_IMG_PREFIX = "[[ERROR:UNPARSED IMG:"

    const val SMALL_IMAGE_WIN_KEY = "win"
    const val SMALL_IMAGE_WIN_PATH = "attachments/day_140621/1406211752793e731a4fec8f7b.png"

    private const val SMILIES_PATH_MARKER = "images/smilies/"
    private const val OLD_FORUM_DOMAIN = ".hi-pda.com"
    private const val NEW_FORUM_DOMAIN = ".4d4y.com"
    private const val POST_ID_PREFIX = "post_"

    /** 与旧 `Utils.URL_REGEX` 语义对齐的纯文本 URL/邮箱匹配模式。 */
    private val URL_REGEX = Regex(
        """[(http(s)?):\/\/(www\.)?a-zA-Z0-9@:%._\+~#=]{2,256}\.[a-z]{2,6}\b([-a-zA-Z0-9@:%_\+.~#?&//=]*)""",
    )

    private val SAFE_CSS_ID_REGEX = Regex("""^[A-Za-z0-9_-]+$""")
    private val QUOTED_ARG_REGEX = Regex("""'([^']+)'""")

    /** 清理引用块 `font[size=2]` 移除回跳 `<a>` 后可能残余的历史破损 BBCode/回跳图标尾缀。 */
    private val QUOTE_META_TRAILING_BBCODE_REGEX = Regex(
        """(?:\s*(?:\[/?(?:img|url)(?:=[^\]]*)?\]|https?://\S*images/common/back\.gif))+\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** 与旧 `Utils.clean` 保持一致的引用块 HTML 白名单。 */
    private val QUOTE_SAFELIST: Safelist = Safelist()
        .addTags("a", "br", "p", "b", "i", "strike", "strong", "u", "font")
        .addAttributes("a", "href")
        .addAttributes("font", "color")
        .addProtocols("a", "href", "http", "https")

    private val QUOTE_OUTPUT_SETTINGS: Document.OutputSettings =
        Document.OutputSettings().prettyPrint(false)

    /**
     * 提取页面首个楼层的作者 UID（对照旧 `HiParserThreadDetail.getThreadAuthorId`）。
     */
    fun parseThreadAuthorId(html: String): String? = parseThreadAuthorId(Jsoup.parse(html))

    fun parseThreadAuthorId(doc: Document): String? {
        val anchor = doc.firstMatch("td.postauthor div.postinfo a") ?: return null
        val href = anchor.attr("href")
        val uid = DiscuzUrlHelper.extractQueryParam(href, "uid")
            ?: DiscuzUrlHelper.middleString(href, "uid=", "&").ifEmpty { null }
        return uid?.trim()?.ifEmpty { null }
    }

    /**
     * 解析帖子详情 HTML 字符串。
     *
     * @param html 页面 HTML 原文。
     * @param tid 可选主题 ID（传入非空非纯数字值时置 `null`；传 `null` 时尝试从页面链接回退提取）。
     * @param avatarBaseUrl 可选头像基地址（`null` 时输出 UCenter 相对路径，与 B9a 保持一致）。
     * @param forumBaseUrl 可选论坛基地址（如 `https://www.4d4y.com/forum/`），用于拼接相对图片/附件 URL；为 `null` 时保留相对路径。
     */
    fun parse(
        html: String,
        tid: String? = null,
        avatarBaseUrl: String? = null,
        forumBaseUrl: String? = null,
    ): ThreadDetailPage = parse(Jsoup.parse(html), tid, avatarBaseUrl, forumBaseUrl)

    /**
     * 解析已构建的 Jsoup [Document]，保证调用前后 `doc.outerHtml()` 一字不差。
     */
    fun parse(
        doc: Document,
        tid: String? = null,
        avatarBaseUrl: String? = null,
        forumBaseUrl: String? = null,
    ): ThreadDetailPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val (page, lastPage) = parsePagination(doc)
        val resolvedTid = resolveTid(doc, tid)
        val (fid, title) = parseNavAndTitle(doc)

        val postListElements = doc.select("div#wrap div#postlist")
        if (postListElements.size != 1) {
            return ThreadDetailPage(
                tid = resolvedTid,
                fid = fid,
                title = title,
                page = page,
                lastPage = lastPage,
                floors = emptyList(),
                postsPerPageEstimate = null,
                meta = meta,
                hasPostList = false,
            )
        }

        val postListEl = postListElements.firstElementOrNull()!!
        val postChildren = postListEl.children()
        var postsPerPageEstimate: Int? = null
        val floors = ArrayList<PostFloor>(postChildren.size)

        for (index in postChildren.indices) {
            val postEl = postChildren[index]
            val rawId = postEl.attr("id")
            if (!rawId.startsWith(POST_ID_PREFIX) || rawId.length <= POST_ID_PREFIX.length) {
                continue
            }
            val postId = rawId.substring(POST_ID_PREFIX.length)

            val timeEl = postEl.firstMatch("table tbody tr td.postcontent div.postinfo div.posterinfo div.authorinfo em")
                ?: continue
            val timePost = normalizeTimePost(timeEl.text())

            val floorEl = postEl.firstMatch("table tbody tr td.postcontent div.postinfo strong a em")
                ?: continue
            val floorText = floorEl.text().trim()
            val floorNumber = DiscuzUrlHelper.parseIntOrZero(floorText)

            val isWarned = postEl.select("table tbody tr td.postcontent span.postratings a")
                .any { it.attr("href").contains("viewwarning") }

            if (floors.isEmpty()) {
                postsPerPageEstimate = computePostsPerPageEstimate(
                    page = page,
                    lastPage = lastPage,
                    postCountInPage = postChildren.count { it.attr("id").startsWith(POST_ID_PREFIX) },
                    firstFloor = floorNumber,
                )
            }

            val authorAnchor = postEl.firstMatch("table tbody tr td.postauthor div.postinfo a")
                ?: continue
            val uidUrl = authorAnchor.attr("href")
            val uid = DiscuzUrlHelper.extractQueryParam(uidUrl, "uid")
                ?: DiscuzUrlHelper.middleString(uidUrl, "uid=", "&").ifEmpty { null }
                ?: continue
            val author = DiscuzTextDecoder.decodeDoubleEscapedEntities(authorAnchor.text())
            val avatarUrl = DiscuzUrlHelper.avatarUrlForUid(uid, avatarBaseUrl)

            val signature = postEl.firstMatch("td.postbottom > div.signatures")
                ?.text()
                ?.trim()
                ?.ifEmpty { null }
                ?.let { DiscuzTextDecoder.decodeDoubleEscapedEntities(it) }

            val floorParsed = parseFloorBodyAndPoll(
                doc = doc,
                postEl = postEl,
                isFirstFloor = floorNumber == 1,
                forumBaseUrl = forumBaseUrl,
            )

            floors.add(
                PostFloor(
                    postId = postId,
                    floor = floorNumber,
                    page = page,
                    uid = uid,
                    author = author,
                    avatarUrl = avatarUrl,
                    timePost = timePost,
                    isWarned = isWarned,
                    postStatus = floorParsed.postStatus,
                    signature = signature,
                    poll = floorParsed.poll,
                    blocks = floorParsed.blocks,
                ),
            )
        }

        return ThreadDetailPage(
            tid = resolvedTid,
            fid = fid,
            title = title,
            page = page,
            lastPage = lastPage,
            floors = floors,
            postsPerPageEstimate = postsPerPageEstimate,
            meta = meta,
            hasPostList = true,
        )
    }

    /**
     * 将 Discuz! 表情图片路径按 `C1b#2` 规则归一化为 `:emoji` `EmojiMap` 键。
     *
     * 规则：截取 `images/smilies/` 之后路径、去除查询/片段与文件扩展名、将 `/` 替换为 `_` 并转小写。
     * 例如：`https://img02.4d4y.com/forum/images/smilies/default/smile.gif` -> `"default_smile"`。
     */
    fun normalizeSmileyKey(rawSrc: String): String? {
        if (rawSrc.isBlank()) return null
        val cleaned = rawSrc.trim().substringBefore('?').substringBefore('#')
        val markerIdx = cleaned.indexOf(SMILIES_PATH_MARKER, ignoreCase = true)
        if (markerIdx == -1) return null
        val relative = cleaned.substring(markerIdx + SMILIES_PATH_MARKER.length).trimStart('/')
        if (relative.isEmpty() || relative.contains("..")) return null
        val dotIdx = relative.lastIndexOf('.')
        val slashIdx = relative.lastIndexOf('/')
        val withoutExt = if (dotIdx > slashIdx) relative.substring(0, dotIdx) else relative
        val key = withoutExt.replace('/', '_').lowercase(Locale.ROOT).trim('_')
        return key.ifEmpty { null }
    }

    /**
     * 解析文件大小文本（对照旧 `Utils.parseSizeText`）：支持 `Bytes`、`KB`、`MB`；无法解析返回 `-1L`。
     */
    fun parseSizeText(sizeText: String?): Long {
        val normalized = sizeText?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return -1L
        return try {
            when {
                normalized.endsWith("KB") -> {
                    val value = normalized.removeSuffix("KB").trim().toDouble()
                    if (!value.isFinite() || value < 0.0) -1L else (value * 1024.0).roundToLong()
                }
                normalized.endsWith("MB") -> {
                    val value = normalized.removeSuffix("MB").trim().toDouble()
                    if (!value.isFinite() || value < 0.0) -1L else (value * 1024.0 * 1024.0).roundToLong()
                }
                normalized.endsWith("BYTES") -> {
                    val value = normalized.removeSuffix("BYTES").trim().toLong()
                    if (value < 0L) -1L else value
                }
                else -> -1L
            }
        } catch (_: IllegalArgumentException) {
            -1L
        }
    }

    /**
     * 从投票容器（`form#poll` 或含 `div.pollinfo`/`div.pollchart` 的 `div.specialmsg`/`div.postmessage`）解析 1 楼投票。
     * 不修改调用方传入的任何 DOM 节点。
     */
    fun parsePoll(pollContainer: Element): ThreadPoll? {
        val pollInfoEl = pollContainer.firstMatch("div.pollinfo")
        val rows = pollContainer.select("div.pollchart > table > tbody > tr")
            .ifEmpty { pollContainer.select("div.pollchart table tbody tr") }
        if (pollInfoEl == null && rows.isEmpty()) {
            return null
        }

        val titleHtml = if (pollInfoEl != null) {
            val clonedInfo = pollInfoEl.clone()
            clonedInfo.select("a").remove()
            DiscuzTextDecoder.decodeDoubleEscapedEntities(clonedInfo.html())
        } else {
            ""
        }

        val timerHtml = pollContainer.firstMatch("p.polltimer")
            ?.html()
            ?.let { DiscuzTextDecoder.decodeDoubleEscapedEntities(it) }
            ?.ifEmpty { null }

        val formhash = pollContainer.firstMatch("input[name=formhash]")
            ?.attr("value")
            ?.trim()
            ?.ifEmpty { null }

        var maxChoices = 1
        val infoText = pollInfoEl?.text()?.trim().orEmpty()
        if (infoText.startsWith("多选投票")) {
            val parsedMax = DiscuzUrlHelper.parseIntOrZero(
                DiscuzUrlHelper.middleString(infoText, "最多可选", "项"),
            )
            if (parsedMax > 1) {
                maxChoices = parsedMax
            }
        }

        val options = ArrayList<PollOption>()
        var lastRowConsumed = false
        for (row in rows) {
            val checkEl = row.firstMatch("td.selector input")
            val textEl = row.firstMatch("td.polloption label") ?: row.firstMatch("td.polloption")
            if (textEl != null) {
                val optionId = checkEl?.attr("value")?.trim()?.ifEmpty { null }
                val text = DiscuzTextDecoder.decodeDoubleEscapedEntities(textEl.text())
                options.add(PollOption(optionId = optionId, text = text, ratesText = null))
                lastRowConsumed = true
            } else {
                val hasButton = row.select("button").isNotEmpty()
                val ratesTdEl = row.select("td").lastElementOrNull()
                val canAttachRates = !hasButton &&
                    options.isNotEmpty() &&
                    options.last().ratesText == null &&
                    ratesTdEl != null &&
                    ratesTdEl.select("em").isNotEmpty()
                if (canAttachRates) {
                    val rates = DiscuzTextDecoder.decodeDoubleEscapedEntities(ratesTdEl.text())
                        .trim()
                        .ifEmpty { null }
                    val lastIdx = options.lastIndex
                    options[lastIdx] = options[lastIdx].copy(ratesText = rates)
                    lastRowConsumed = true
                } else {
                    lastRowConsumed = false
                }
            }
        }

        var footer: String? = null
        val lastRow = rows.lastElementOrNull()
        if (lastRow != null && !lastRowConsumed) {
            val lastTd = lastRow.select("td").lastElementOrNull()
            if (lastTd != null) {
                val clonedTd = lastTd.clone()
                clonedTd.select("button").remove()
                footer = DiscuzTextDecoder.decodeDoubleEscapedEntities(clonedTd.text())
                    .trim()
                    .ifEmpty { null }
            }
        }

        return ThreadPoll(
            titleHtml = titleHtml,
            timerHtml = timerHtml,
            formhash = formhash,
            maxChoices = maxChoices,
            options = options,
            footer = footer,
        )
    }

    /**
     * 独立解析 `td.t_msgfont` 正文 DOM 为不可变 [ContentBlock] 列表（不修改 DOM）。
     */
    fun parseContentBlocks(
        postMessageEl: Element,
        forumBaseUrl: String? = null,
        ignoredElements: Set<Node> = emptySet(),
    ): List<ContentBlock> {
        if (postMessageEl.childNodeSize() == 0) {
            return listOf(ContentBlock.Notice(NOTICE_EMPTY_CONTENT))
        }
        val skippedNodes: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        skippedNodes.addAll(ignoredElements)

        val builder = BlockListBuilder()
        val stack = ArrayDeque<TraversalFrame>()
        pushChildrenReversed(stack, postMessageEl, TextFormat.PLAIN)

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val node = frame.node
            if (node in skippedNodes) continue

            val childFormat = processNode(
                node = node,
                inheritedFormat = frame.format,
                builder = builder,
                skippedNodes = skippedNodes,
                forumBaseUrl = forumBaseUrl,
            )
            if (childFormat != null && node.childNodeSize() > 0) {
                pushChildrenReversed(stack, node, childFormat)
            }
        }

        return builder.build()
    }

    // region Page-level helpers

    private fun parsePagination(doc: Document): Pair<Int, Int> {
        val pagesEl = doc.firstMatch("div#wrap div.forumcontrol div.pages") ?: return 1 to 1
        var page = 1
        var lastPage = 1
        for (child in pagesEl.children()) {
            val number = DiscuzUrlHelper.extractFirstIntOrZero(child.text())
            if (number > lastPage) {
                lastPage = number
            }
            if (child.nodeName() == "strong" && number > 0) {
                page = number
            }
        }
        return page to lastPage
    }

    private fun resolveTid(doc: Document, providedTid: String?): String? {
        if (providedTid != null) {
            return providedTid.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } }
        }
        val inputTid = doc.firstMatch("input[name=tid]")?.attr("value")?.trim()
        if (!inputTid.isNullOrEmpty() && inputTid.all { it in '0'..'9' }) {
            return inputTid
        }
        for (anchor in doc.select("div#wrap a[href]")) {
            val href = anchor.attr("href")
            val candidate = when {
                href.contains("viewthread.php") || href.contains("post.php") ->
                    DiscuzUrlHelper.extractQueryParam(href, "tid")
                href.contains("redirect.php") ->
                    DiscuzUrlHelper.extractQueryParam(href, "ptid")
                        ?: DiscuzUrlHelper.extractQueryParam(href, "tid")
                else -> null
            }
            if (!candidate.isNullOrEmpty() && candidate.all { it in '0'..'9' }) {
                return candidate
            }
        }
        return null
    }

    private fun parseNavAndTitle(doc: Document): Pair<Int, String> {
        var fid = 0
        var title = ""
        val navEl = doc.firstMatch("div#nav")
        if (navEl != null) {
            for (link in navEl.select("a")) {
                val href = link.attr("href")
                if (href.indexOf("fid=") > 0) {
                    val rawFid = DiscuzUrlHelper.extractQueryParam(href, "fid")
                        ?: DiscuzUrlHelper.middleString(href, "fid=", "&")
                    fid = DiscuzUrlHelper.parseIntOrZero(rawFid)
                    break
                }
            }
            val clonedNav = navEl.clone()
            clonedNav.select("a").remove()
            title = DiscuzTextDecoder.decodeDoubleEscapedEntities(
                clonedNav.text().replace("»", "").trim(),
            )
        }
        if (title.isEmpty()) {
            val threadTitleEl = doc.firstMatch("div#threadtitle")
            if (threadTitleEl != null) {
                val clonedTitle = threadTitleEl.clone()
                clonedTitle.select("a").remove()
                title = DiscuzTextDecoder.decodeDoubleEscapedEntities(clonedTitle.text().trim())
            }
        }
        return fid to title
    }

    private fun computePostsPerPageEstimate(
        page: Int,
        lastPage: Int,
        postCountInPage: Int,
        firstFloor: Int,
    ): Int? = when {
        page == 1 && lastPage > 1 && postCountInPage > 0 -> postCountInPage
        page > 1 && firstFloor > 1 -> ((firstFloor - 1) / (page - 1)).takeIf { it > 0 }
        else -> null
    }

    private fun normalizeTimePost(rawTime: String): String {
        val trimmed = rawTime.trim()
        return when {
            trimmed.startsWith("发表于 ") -> trimmed.substring(4)
            trimmed.startsWith("发表于") -> trimmed.substring(3).trimStart(' ', '\u00a0')
            else -> trimmed
        }
    }

    // endregion

    // region Floor body + poll + tail attachments

    private data class FloorBodyResult(
        val postStatus: String?,
        val poll: ThreadPoll?,
        val blocks: List<ContentBlock>,
    )

    private fun parseFloorBodyAndPoll(
        doc: Document,
        postEl: Element,
        isFirstFloor: Boolean,
        forumBaseUrl: String?,
    ): FloorBodyResult {
        var postMessageEl = postEl.firstMatch(
            "table tbody tr td.postcontent div.defaultpost div.postmessage div.t_msgfontfix table tbody tr td.t_msgfont",
        )

        if (postMessageEl == null) {
            val lockedEl = postEl.firstMatch(
                "table tbody tr td.postcontent div.defaultpost div.postmessage div.locked",
            )
            if (lockedEl != null) {
                val notice = DiscuzTextDecoder.decodeDoubleEscapedEntities(lockedEl.text())
                return FloorBodyResult(
                    postStatus = null,
                    poll = null,
                    blocks = listOf(ContentBlock.Notice(notice)),
                )
            }
        }

        val ignoredNodes: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        var poll: ThreadPoll? = null

        if (isFirstFloor) {
            val pollFormEl = postEl.firstMatch("form#poll") ?: doc.firstMatch("form#poll")
            if (pollFormEl != null) {
                poll = parsePoll(pollFormEl)
                ignoredNodes.add(pollFormEl)
            } else {
                val specialMsgContainer = postEl.firstMatch(
                    "table tbody tr td.postcontent div.defaultpost div.postmessage",
                )
                if (specialMsgContainer != null &&
                    (specialMsgContainer.firstMatch("div.pollinfo") != null ||
                        specialMsgContainer.firstMatch("div.pollchart") != null)
                ) {
                    poll = parsePoll(specialMsgContainer)
                    specialMsgContainer.select("div.pollinfo, p.polltimer, div.pollchart")
                        .forEach { ignoredNodes.add(it) }
                }
            }
        }

        if (postMessageEl == null) {
            postMessageEl = postEl.firstMatch(
                "table tbody tr td.postcontent div.defaultpost div.postmessage div.specialmsg table tbody tr td.t_msgfont",
            )
        }

        if (postMessageEl == null) {
            return FloorBodyResult(
                postStatus = null,
                poll = poll,
                blocks = listOf(ContentBlock.Notice(NOTICE_CONTENT_NOT_FOUND)),
            )
        }

        if (postMessageEl.childNodeSize() == 0) {
            return FloorBodyResult(
                postStatus = null,
                poll = poll,
                blocks = listOf(ContentBlock.Notice(NOTICE_EMPTY_CONTENT)),
            )
        }

        val firstPostStatusEl = postMessageEl.firstMatch("i.pstatus")
        val postStatus = firstPostStatusEl?.text()
            ?.trim()
            ?.ifEmpty { null }
            ?.let { DiscuzTextDecoder.decodeDoubleEscapedEntities(it) }
        if (firstPostStatusEl != null) {
            ignoredNodes.add(firstPostStatusEl)
        }

        val blocks = ArrayList(
            parseContentBlocks(
                postMessageEl = postMessageEl,
                forumBaseUrl = forumBaseUrl,
                ignoredElements = ignoredNodes,
            ),
        )

        // 文末图片附件：div.postattachlist dl.attachimg
        val attachImgLists = postEl.select(
            "table tbody tr td.postcontent div.defaultpost div.postmessage div.postattachlist dl.attachimg",
        )
        for (dlEl in attachImgLists) {
            val sizeEm = dlEl.firstMatch("em")
            val size = if (sizeEm != null) {
                parseSizeText(DiscuzUrlHelper.middleString(sizeEm.text(), "(", ")"))
            } else {
                0L
            }
            val imgEl = dlEl.firstMatch("img") ?: continue
            blocks.add(buildInternalImage(imgEl, size, forumBaseUrl))
        }

        // 文末普通附件：dl.t_attachlist p.attachname
        val attachmentNames = postEl.select("dl.t_attachlist p.attachname")
        for (attachEl in attachmentNames) {
            val linkEl = attachEl.firstMatch("a[href]") ?: continue
            val href = linkEl.attr("href")
            if (href.startsWith("attachment.php?")) {
                val clonedAttach = attachEl.clone()
                clonedAttach.select("a[href]").remove()
                val desc = DiscuzTextDecoder.decodeDoubleEscapedEntities(clonedAttach.text())
                    .trim()
                    .ifEmpty { null }
                val title = DiscuzTextDecoder.decodeDoubleEscapedEntities(linkEl.text())
                blocks.add(
                    ContentBlock.Attachment(
                        url = resolveForumUrl(href, forumBaseUrl),
                        title = title,
                        description = desc,
                    ),
                )
            }
        }

        return FloorBodyResult(
            postStatus = postStatus,
            poll = poll,
            blocks = blocks,
        )
    }

    // endregion

    // region Iterative DOM node parsing

    private data class TraversalFrame(
        val node: Node,
        val format: TextFormat,
    )

    private fun pushChildrenReversed(
        stack: ArrayDeque<TraversalFrame>,
        parent: Node,
        format: TextFormat,
    ) {
        for (idx in parent.childNodeSize() - 1 downTo 0) {
            stack.addLast(TraversalFrame(parent.childNode(idx), format))
        }
    }

    /**
     * 处理单个 DOM 节点。
     *
     * @return 非 `null` 表示继续遍历子节点并沿用该 [TextFormat]；返回 `null` 表示跳过子节点。
     */
    private fun processNode(
        node: Node,
        inheritedFormat: TextFormat,
        builder: BlockListBuilder,
        skippedNodes: MutableSet<Node>,
        forumBaseUrl: String?,
    ): TextFormat? {
        val name = node.nodeName()
        return when (name) {
            "i" -> inheritedFormat.copy(italic = true)
            "u" -> inheritedFormat.copy(underline = true)
            "strike" -> inheritedFormat.copy(strikethrough = true)
            "b" -> inheritedFormat.copy(bold = true)
            "em", "ol", "ul", "hr", "blockquote" -> inheritedFormat
            "font" -> {
                val fontEl = node as Element
                val isSmall = fontEl.attr("size").trim() == "1"
                val colorAttr = fontEl.attr("color").trim()
                inheritedFormat.copy(
                    smallFont = inheritedFormat.smallFont || isSmall,
                    rawColor = if (colorAttr.isNotEmpty()) colorAttr else inheritedFormat.rawColor,
                )
            }
            "strong" -> processStrongElement(node as Element, inheritedFormat, builder)
            "#text" -> {
                processTextNode(node as TextNode, inheritedFormat, builder)
                null
            }
            "li" -> {
                builder.addLineBreakIfPendingContent()
                inheritedFormat
            }
            "br" -> {
                builder.addLineBreak()
                null
            }
            "p" -> {
                val pEl = node as Element
                if (pEl.hasClass("imgtitle")) null else inheritedFormat
            }
            "img" -> {
                processImageElement(node as Element, builder, forumBaseUrl)
                null
            }
            "span" -> processSpanElement(node as Element, inheritedFormat, builder, skippedNodes, forumBaseUrl)
            "a" -> processAnchorElement(node as Element, inheritedFormat, builder, forumBaseUrl)
            "div" -> processDivElement(node as Element, inheritedFormat, builder)
            "table", "tbody", "thead", "tfoot", "dl", "dt", "dd" -> inheritedFormat
            "tr" -> {
                builder.addLineBreak()
                inheritedFormat
            }
            "td", "th" -> {
                builder.markInlineSpace(TextFormat.PLAIN)
                inheritedFormat
            }
            "script", "#data" -> {
                processVideoScriptNode(node, builder)
                null
            }
            "#comment" -> null
            else -> null
        }
    }

    private fun processStrongElement(
        strongEl: Element,
        inheritedFormat: TextFormat,
        builder: BlockListBuilder,
    ): TextFormat? {
        val rawText = strongEl.text()
        val floorLink = strongEl.firstMatch("a[href]")
        if (floorLink != null && rawText.startsWith("回复 ") && rawText.contains("#")) {
            val href = floorLink.attr("href")
            val postId = DiscuzUrlHelper.extractQueryParam(href, "pid")
                ?: DiscuzUrlHelper.middleString(href, "pid=", "&").ifEmpty { null }
            val tid = DiscuzUrlHelper.extractQueryParam(href, "ptid")
                ?: DiscuzUrlHelper.middleString(href, "ptid=", "&").ifEmpty { null }
            val floor = DiscuzUrlHelper.extractFirstIntOrZero(rawText.substringBefore("#"))
            val author = DiscuzTextDecoder.decodeDoubleEscapedEntities(
                rawText.substringAfterLast("#").trim(),
            )
            if (author.isNotEmpty() && DiscuzUrlHelper.isValidId(postId) && floor > 0) {
                builder.addBlock(
                    ContentBlock.GoToFloor(
                        label = DiscuzTextDecoder.decodeDoubleEscapedEntities(rawText),
                        targetTid = tid,
                        targetPid = postId,
                        floor = floor,
                        author = author,
                    ),
                )
                return null
            }
        }
        return inheritedFormat.copy(bold = true)
    }

    private fun processTextNode(
        textNode: TextNode,
        format: TextFormat,
        builder: BlockListBuilder,
    ) {
        val raw = textNode.text()
        if (raw.isEmpty()) return
        val decoded = DiscuzTextDecoder.decodeDoubleEscapedEntities(raw)
        if (decoded.trim().isEmpty()) {
            builder.markInlineSpace(format)
            return
        }

        var lastPos = 0
        for (match in URL_REGEX.findAll(decoded)) {
            val prefix = decoded.substring(lastPos, match.range.first)
            if (prefix.trim().isNotEmpty()) {
                builder.addPlainText(prefix, format)
            } else if (prefix.isNotEmpty()) {
                builder.markInlineSpace(format)
            }
            val token = match.value
            if (token.contains("@") && !token.contains("/")) {
                builder.addSpan(InlineSpan.Email(token))
            } else {
                val url = normalizeLinkUrl(
                    rawUrl = token,
                    isSmallFont = format.smallFont,
                    forumBaseUrl = null,
                )
                builder.addSpan(
                    InlineSpan.Link(
                        label = token,
                        url = url,
                        isSmallFont = format.smallFont,
                    ),
                )
            }
            lastPos = match.range.last + 1
        }
        if (lastPos < decoded.length) {
            val tail = decoded.substring(lastPos)
            if (tail.trim().isNotEmpty()) {
                builder.addPlainText(tail, format)
            } else if (tail.isNotEmpty()) {
                builder.markInlineSpace(format)
            }
        }
    }

    private fun processSpanElement(
        spanEl: Element,
        inheritedFormat: TextFormat,
        builder: BlockListBuilder,
        skippedNodes: MutableSet<Node>,
        forumBaseUrl: String?,
    ): TextFormat? {
        var isInternalAttach = false
        for (anchor in spanEl.select("a")) {
            val href = anchor.attr("href")
            if (href.contains("attachment.php?") && !href.contains("nothumb=")) {
                var desc: String? = null
                var sib = spanEl.nextSibling()
                while (sib != null && sib in skippedNodes) {
                    sib = sib.nextSibling()
                }
                if (sib is TextNode) {
                    desc = DiscuzTextDecoder.decodeDoubleEscapedEntities(sib.text())
                        .trim()
                        .ifEmpty { null }
                    skippedNodes.add(sib)
                }
                builder.addBlock(
                    ContentBlock.Attachment(
                        url = resolveForumUrl(href, forumBaseUrl),
                        title = DiscuzTextDecoder.decodeDoubleEscapedEntities(anchor.text()),
                        description = desc,
                    ),
                )
                isInternalAttach = true
            }
        }
        return if (isInternalAttach) null else inheritedFormat
    }

    private fun processAnchorElement(
        anchorEl: Element,
        inheritedFormat: TextFormat,
        builder: BlockListBuilder,
        forumBaseUrl: String?,
    ): TextFormat? {
        val rawHref = anchorEl.attr("href").trim()
        val label = DiscuzTextDecoder.decodeDoubleEscapedEntities(anchorEl.text())

        if (anchorEl.childNodeSize() > 0 && anchorEl.childNode(0).nodeName() == "img") {
            if (rawHref.isNotEmpty() && !rawHref.startsWith("javascript:", ignoreCase = true)) {
                val url = normalizeLinkUrl(rawHref, isSmallFont = false, forumBaseUrl = forumBaseUrl)
                builder.addSpan(InlineSpan.Link(label = url, url = url, isSmallFont = false))
            }
            return inheritedFormat
        }

        if (rawHref.startsWith("attachment.php?")) {
            builder.addBlock(
                ContentBlock.Attachment(
                    url = resolveForumUrl(rawHref, forumBaseUrl),
                    title = label,
                    description = null,
                ),
            )
            return null
        }

        if (rawHref.startsWith("mailto:", ignoreCase = true)) {
            val email = rawHref.substringAfter(':').trim()
            if (email.isNotEmpty()) {
                builder.addSpan(InlineSpan.Email(email))
                return null
            }
        }

        val firstChild = if (anchorEl.childNodeSize() > 0) anchorEl.childNode(0) else null
        val isSmallFont = inheritedFormat.smallFont ||
            (firstChild is Element && firstChild.nodeName() == "font" && firstChild.attr("size").trim() == "1")

        if (rawHref.isNotEmpty()) {
            val normalizedUrl = normalizeLinkUrl(rawHref, isSmallFont = isSmallFont, forumBaseUrl = forumBaseUrl)
            builder.addSpan(
                InlineSpan.Link(
                    label = label,
                    url = normalizedUrl,
                    isSmallFont = isSmallFont,
                ),
            )
        } else if (label.trim().isNotEmpty()) {
            builder.addPlainText(label, inheritedFormat.copy(smallFont = isSmallFont))
        }

        // 少数情况下 <a> 内同时包含文本与 <img>
        for (img in anchorEl.select("img")) {
            processImageElement(img, builder, forumBaseUrl)
        }
        return null
    }

    private fun processDivElement(
        divEl: Element,
        inheritedFormat: TextFormat,
        builder: BlockListBuilder,
    ): TextFormat? {
        if (divEl.hasClass("t_attach") || divEl.hasClass("attach_popup")) {
            return null
        }
        if (divEl.hasClass("quote")) {
            builder.addBlock(parseQuoteElement(divEl))
            return null
        }
        return inheritedFormat
    }

    private fun parseQuoteElement(divEl: Element): ContentBlock.Quote {
        val cloned = divEl.clone()

        var targetTid: String? = null
        var targetPid: String? = null
        for (anchor in cloned.select("a")) {
            val href = anchor.attr("href")
            if (href.contains("redirect.php?goto=findpost")) {
                targetPid = DiscuzUrlHelper.extractQueryParam(href, "pid")
                    ?: DiscuzUrlHelper.middleString(href, "pid=", "&").ifEmpty { null }
                targetTid = DiscuzUrlHelper.extractQueryParam(href, "ptid")
                    ?: DiscuzUrlHelper.middleString(href, "ptid=", "&").ifEmpty { null }
                break
            }
        }

        val metaFont = cloned.firstMatch("font[size=2]")
        val authorAndTime = if (metaFont != null) {
            metaFont.select("a").remove()
            val rawMeta = DiscuzTextDecoder.decodeDoubleEscapedEntities(metaFont.text())
            QUOTE_META_TRAILING_BBCODE_REGEX.replace(rawMeta, "").trim()
        } else {
            ""
        }
        metaFont?.remove()

        cloned.select("[style*=display][style*=none]").remove()

        val cleanedHtml = DiscuzTextDecoder.decodeDoubleEscapedEntities(
            Jsoup.clean(cloned.html(), "", QUOTE_SAFELIST, QUOTE_OUTPUT_SETTINGS),
        )
        val contentText = extractPlainTextFromCleanHtml(cleanedHtml)

        return ContentBlock.Quote(
            authorAndTime = authorAndTime,
            contentHtml = cleanedHtml,
            contentText = contentText,
            targetTid = targetTid,
            targetPid = targetPid,
        )
    }

    private fun extractPlainTextFromCleanHtml(cleanedHtml: String): String {
        val body = Jsoup.parseBodyFragment(cleanedHtml).body()
        val sb = StringBuilder()
        val stack = ArrayDeque<Node>()
        for (idx in body.childNodeSize() - 1 downTo 0) {
            stack.addLast(body.childNode(idx))
        }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when {
                node is TextNode -> sb.append(node.text())
                node.nodeName() == "br" -> sb.append('\n')
                node.nodeName() == "p" -> {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    for (idx in node.childNodeSize() - 1 downTo 0) {
                        stack.addLast(node.childNode(idx))
                    }
                }
                else -> {
                    for (idx in node.childNodeSize() - 1 downTo 0) {
                        stack.addLast(node.childNode(idx))
                    }
                }
            }
        }
        return DiscuzTextDecoder.decodeDoubleEscapedEntities(
            sb.toString()
                .replace('\u3000', ' ')
                .replace('\u00a0', ' ')
                .trim(),
        )
    }

    private fun processImageElement(
        imgEl: Element,
        builder: BlockListBuilder,
        forumBaseUrl: String?,
    ) {
        val rawSrc = imgEl.attr("src").trim()
        val resolvedSrc = resolveForumUrl(rawSrc, forumBaseUrl)
        val id = imgEl.attr("id").trim()

        if (id.startsWith("aimg") || rawSrc.contains("images/common/none.gif")) {
            var size = 0L
            if (id.isNotEmpty() && SAFE_CSS_ID_REGEX.matches(id)) {
                val scope = imgEl.parent()?.parent() ?: imgEl.parent()
                val menuEl = scope?.firstMatch("div#${id}_menu")
                if (menuEl != null) {
                    val sizeText = DiscuzUrlHelper.middleString(menuEl.text(), "(", ")")
                    size = parseSizeText(sizeText)
                }
            }
            builder.addBlock(buildInternalImage(imgEl, size, forumBaseUrl))
            return
        }

        val smileyKey = normalizeSmileyKey(resolvedSrc)
        if (smileyKey != null) {
            builder.addSpan(InlineSpan.Emoji(normalizedKey = smileyKey, rawSrc = resolvedSrc))
            return
        }

        val cleanPath = resolvedSrc.substringBefore('?').substringBefore('#')
        if (cleanPath.endsWith(SMALL_IMAGE_WIN_PATH, ignoreCase = true)) {
            builder.addSpan(InlineSpan.SmallImage(key = SMALL_IMAGE_WIN_KEY, rawSrc = resolvedSrc))
            return
        }

        if (isForumUiIcon(rawSrc, resolvedSrc, forumBaseUrl)) {
            return
        }

        if (rawSrc.contains("data:image/", ignoreCase = true)) {
            return
        }

        if (resolvedSrc.isNotEmpty()) {
            builder.addBlock(
                ContentBlock.Image(
                    fullUrl = resolvedSrc,
                    thumbUrl = null,
                    fileSizeBytes = 0L,
                ),
            )
            return
        }

        builder.addBlock(ContentBlock.Notice("$NOTICE_UNPARSED_IMG_PREFIX$resolvedSrc]]"))
    }

    private fun buildInternalImage(
        imgEl: Element,
        size: Long,
        forumBaseUrl: String?,
    ): ContentBlock.Image {
        val src = resolveForumUrl(imgEl.attr("src").trim(), forumBaseUrl)
        val file = resolveForumUrl(imgEl.attr("file").trim(), forumBaseUrl)
        val rawOnclick = imgEl.attr("onclick").trim()

        val onclickPath = extractZoomAttachmentUrl(rawOnclick)
        val onclickUrl = if (onclickPath.isNotEmpty()) {
            resolveForumUrl(onclickPath, forumBaseUrl)
        } else {
            ""
        }

        val thumbUrl = src.takeIf { it.isNotEmpty() && it.contains("thumb.") }
        val fullUrl = when {
            onclickUrl.isNotEmpty() -> onclickUrl
            file.isNotEmpty() -> file
            !thumbUrl.isNullOrEmpty() -> thumbUrl
            else -> ""
        }

        return ContentBlock.Image(
            fullUrl = fullUrl,
            thumbUrl = thumbUrl,
            fileSizeBytes = size,
        )
    }

    private fun extractZoomAttachmentUrl(onclick: String): String {
        if (!onclick.startsWith("zoom") || !onclick.contains("attachment")) return ""
        for (match in QUOTED_ARG_REGEX.findAll(onclick)) {
            val candidate = match.groupValues[1]
            if (candidate.contains("attachment")) {
                return candidate
            }
        }
        val tail = DiscuzUrlHelper.middleString(onclick, "attachment", "'")
        return if (tail.isNotEmpty()) "attachment$tail" else ""
    }

    private fun isForumUiIcon(rawSrc: String, resolvedSrc: String, forumBaseUrl: String?): Boolean {
        val checkRaw = rawSrc.lowercase(Locale.ROOT)
        val checkResolved = resolvedSrc.lowercase(Locale.ROOT)
        if (checkRaw.startsWith("images/") || checkRaw.startsWith("/forum/images/")) {
            return true
        }
        if (checkResolved.contains("4d4y.com/forum/images/") || checkResolved.contains("hi-pda.com/forum/images/")) {
            return true
        }
        val base = forumBaseUrl?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (base.isNotEmpty()) {
            val normalizedBase = if (base.endsWith("/")) base else "$base/"
            if (checkResolved.startsWith("${normalizedBase}images/")) {
                return true
            }
        }
        return false
    }

    private fun processVideoScriptNode(node: Node, builder: BlockListBuilder) {
        val html = node.toString()
        var url = DiscuzUrlHelper.middleString(html, "'src', '", "'")
        if (url.startsWith("http://player.youku.com/player.php")) {
            val sid = DiscuzUrlHelper.middleString(url, "sid/", "/v.swf")
            url = "http://v.youku.com/v_show/id_$sid"
            if (!url.endsWith(".html")) {
                url += ".html"
            }
            builder.addSpan(
                InlineSpan.Link(
                    label = "YouKu视频自动转换手机通道 $url",
                    url = url,
                    isSmallFont = false,
                ),
            )
        } else if (url.startsWith("http")) {
            builder.addSpan(
                InlineSpan.Link(
                    label = "FLASH VIDEO,手机可能不支持 $url",
                    url = url,
                    isSmallFont = false,
                ),
            )
        }
    }

    private fun resolveForumUrl(url: String, forumBaseUrl: String?): String {
        if (url.isEmpty() || url.contains("://") || url.startsWith("data:", ignoreCase = true)) {
            return url
        }
        if (url.startsWith("//")) {
            return "https:$url"
        }
        val base = forumBaseUrl?.trim().orEmpty()
        if (base.isEmpty()) return url
        if (url.startsWith("/")) {
            val origin = extractOrigin(base)
            if (origin != null) return origin + url
        }
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        return normalizedBase + url.trimStart('/')
    }

    private fun extractOrigin(baseUrl: String): String? {
        val schemeIdx = baseUrl.indexOf("://")
        if (schemeIdx == -1) return null
        val authorityEnd = baseUrl.indexOf('/', schemeIdx + 3)
        return if (authorityEnd == -1) baseUrl else baseUrl.substring(0, authorityEnd)
    }

    private fun normalizeLinkUrl(
        rawUrl: String,
        isSmallFont: Boolean,
        forumBaseUrl: String?,
    ): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return ""
        if (isSmallFont && url.contains(OLD_FORUM_DOMAIN)) {
            url = url.replace(OLD_FORUM_DOMAIN, NEW_FORUM_DOMAIN)
        }
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("javascript:")) {
            return url
        }
        if (lower.startsWith("//")) {
            return "https:$url"
        }
        if (isRelativeSiteUrl(lower)) {
            return resolveForumUrl(url, forumBaseUrl)
        }
        return "https://$url"
    }

    private fun isRelativeSiteUrl(lowerUrl: String): Boolean {
        if (lowerUrl.startsWith("/") ||
            lowerUrl.startsWith("./") ||
            lowerUrl.startsWith("../") ||
            lowerUrl.startsWith("?") ||
            lowerUrl.startsWith("#")
        ) {
            return true
        }
        val pathBeforeQuery = lowerUrl.substringBefore('?').substringBefore('#')
        return !pathBeforeQuery.contains('/') && pathBeforeQuery.endsWith(".php")
    }

    // endregion

    // region Block accumulator

    private class BlockListBuilder {
        private val blocks = ArrayList<ContentBlock>()
        private val pendingSpans = ArrayList<InlineSpan>()
        private var pendingSpaceFormat: TextFormat? = null

        fun markInlineSpace(format: TextFormat = TextFormat.PLAIN) {
            if (pendingSpaceFormat == null &&
                hasMeaningfulSpan(pendingSpans) &&
                pendingSpans.lastOrNull() != InlineSpan.LineBreak
            ) {
                pendingSpaceFormat = format
            }
        }

        fun addPlainText(text: String, format: TextFormat) {
            if (text.isEmpty()) return
            val atLineStart = pendingSpans.isEmpty() || pendingSpans.lastOrNull() == InlineSpan.LineBreak
            val normalizedText = if (atLineStart) text.trimStart(' ') else text
            if (normalizedText.isEmpty()) {
                markInlineSpace(format)
                return
            }
            flushPendingSpaceIfNeeded(incomingStartsWithSpace = normalizedText.first().isInlineSpace())
            appendPlainTextRaw(normalizedText, format)
        }

        fun addSpan(span: InlineSpan) {
            flushPendingSpaceIfNeeded(incomingStartsWithSpace = false)
            pendingSpans.add(span)
        }

        fun addLineBreak() {
            pendingSpaceFormat = null
            trimTrailingAsciiSpaceFromLastPlain()
            pendingSpans.add(InlineSpan.LineBreak)
        }

        fun addLineBreakIfPendingContent() {
            pendingSpaceFormat = null
            trimTrailingAsciiSpaceFromLastPlain()
            if (hasMeaningfulSpan(pendingSpans) && pendingSpans.lastOrNull() != InlineSpan.LineBreak) {
                pendingSpans.add(InlineSpan.LineBreak)
            }
        }

        fun addBlock(block: ContentBlock) {
            flushPendingText()
            blocks.add(block)
        }

        fun build(): List<ContentBlock> {
            flushPendingText()
            return blocks
        }

        private fun flushPendingSpaceIfNeeded(incomingStartsWithSpace: Boolean) {
            val spaceFormat = pendingSpaceFormat ?: return
            pendingSpaceFormat = null
            if (incomingStartsWithSpace || lastSpanEndsWithSpace()) {
                return
            }
            appendPlainTextRaw(" ", spaceFormat)
        }

        private fun lastSpanEndsWithSpace(): Boolean {
            val last = pendingSpans.lastOrNull()
            return last is InlineSpan.Plain && last.text.isNotEmpty() && last.text.last().isInlineSpace()
        }

        private fun appendPlainTextRaw(text: String, format: TextFormat) {
            val last = pendingSpans.lastOrNull()
            if (last is InlineSpan.Plain && last.format == format) {
                pendingSpans[pendingSpans.lastIndex] = last.copy(text = last.text + text)
            } else {
                pendingSpans.add(InlineSpan.Plain(text = text, format = format))
            }
        }

        private fun trimTrailingAsciiSpaceFromLastPlain() {
            val last = pendingSpans.lastOrNull() as? InlineSpan.Plain ?: return
            val trimmed = last.text.trimEnd(' ')
            if (trimmed.isEmpty()) {
                pendingSpans.removeAt(pendingSpans.lastIndex)
            } else if (trimmed.length != last.text.length) {
                pendingSpans[pendingSpans.lastIndex] = last.copy(text = trimmed)
            }
        }

        private fun flushPendingText() {
            pendingSpaceFormat = null
            if (pendingSpans.isEmpty()) return
            if (!hasMeaningfulSpan(pendingSpans)) {
                pendingSpans.clear()
                return
            }
            var start = 0
            var end = pendingSpans.size
            while (start < end && isTrimBoundary(pendingSpans[start])) {
                start++
            }
            while (end > start && isTrimBoundary(pendingSpans[end - 1])) {
                end--
            }
            if (start < end) {
                val sliced = pendingSpans.subList(start, end).toMutableList()
                val first = sliced.firstOrNull()
                if (first is InlineSpan.Plain) {
                    val trimmedFirst = first.text.trimStart(' ')
                    if (trimmedFirst.length != first.text.length) {
                        sliced[0] = first.copy(text = trimmedFirst)
                    }
                }
                val last = sliced.lastOrNull()
                if (last is InlineSpan.Plain) {
                    val trimmedLast = last.text.trimEnd(' ')
                    if (trimmedLast.length != last.text.length) {
                        sliced[sliced.lastIndex] = last.copy(text = trimmedLast)
                    }
                }
                val nonEmpty = sliced.filterNot { it is InlineSpan.Plain && it.text.isEmpty() }
                if (nonEmpty.isNotEmpty()) {
                    blocks.add(ContentBlock.Text(nonEmpty))
                }
            }
            pendingSpans.clear()
        }

        private fun Char.isInlineSpace(): Boolean = this == ' ' || this == '\u00a0' || isWhitespace()

        private fun hasMeaningfulSpan(spans: List<InlineSpan>): Boolean = spans.any { span ->
            when (span) {
                is InlineSpan.LineBreak -> false
                is InlineSpan.Plain -> span.text.trim().isNotEmpty()
                is InlineSpan.Link,
                is InlineSpan.Email,
                is InlineSpan.Emoji,
                is InlineSpan.SmallImage,
                -> true
            }
        }

        private fun isTrimBoundary(span: InlineSpan): Boolean = when (span) {
            is InlineSpan.LineBreak -> true
            is InlineSpan.Plain -> span.text.trim().isEmpty()
            else -> false
        }
    }

    // endregion
}
