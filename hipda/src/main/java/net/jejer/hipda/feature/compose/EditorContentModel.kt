package net.jejer.hipda.feature.compose

import android.net.Uri
import androidx.compose.runtime.Immutable

/** 编辑器正文块模型：文本块与可视化缩略图块严格交替，UI 永不暴露 `[attachimg]` 原始标签。 */
@Immutable
sealed interface EditorBodyBlock {
    val id: String

    @Immutable data class Text(override val id: String, val text: String) : EditorBodyBlock
    @Immutable data class Image(override val id: String, val imageId: String) : EditorBodyBlock
}

/**
 * 编辑器正文不可变文档（首尾必为 [EditorBodyBlock.Text]，文本块与图片块严格交替；
 * `[attachimg]{id}[/attachimg]` 双射解析/序列化，保障草稿与提交零丢失）。
 */
@Immutable
data class EditorBodyDocument(
    val blocks: List<EditorBodyBlock>,
    val activeTextBlockId: String,
    val activeSelectionStart: Int,
    val activeSelectionEnd: Int,
    val nextBlockSeq: Int,
) {
    val firstTextBlockId: String get() = (blocks.first() as EditorBodyBlock.Text).id
    val lastTextBlockId: String get() = (blocks.last() as EditorBodyBlock.Text).id

    fun insertedImageIds(): Set<String> = blocks.mapNotNullTo(LinkedHashSet()) { (it as? EditorBodyBlock.Image)?.imageId }

    fun plainTextTrimmedCodePoints(): Int {
        val trimmed = buildString { blocks.forEach { if (it is EditorBodyBlock.Text) append(it.text) } }.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.codePointCount(0, trimmed.length)
    }

    fun isVisuallyEmpty(): Boolean = blocks.all { it is EditorBodyBlock.Text && it.text.isBlank() }

    /** 透明序列化为含 `[attachimg]{imageId}[/attachimg]` 的 Discuz! 正文。 */
    fun serialize(): String = buildString {
        blocks.forEachIndexed { i, block ->
            when (block) {
                is EditorBodyBlock.Text -> if (block.text.isNotEmpty()) {
                    if (i > 0 && blocks[i - 1] is EditorBodyBlock.Image) append('\n')
                    append(block.text)
                }
                is EditorBodyBlock.Image -> {
                    val prevNonEmptyText = i > 0 && (blocks[i - 1] as? EditorBodyBlock.Text)?.text?.isNotEmpty() == true
                    val prevImage = i > 1 && blocks[i - 2] is EditorBodyBlock.Image && (blocks[i - 1] as? EditorBodyBlock.Text)?.text.isNullOrEmpty()
                    if (prevNonEmptyText || prevImage) append('\n')
                    append("[attachimg]").append(block.imageId).append("[/attachimg]")
                }
            }
        }
    }

    fun updateTextBlock(blockId: String, newText: String, selectionStart: Int, selectionEnd: Int): EditorBodyDocument {
        val idx = blocks.indexOfFirst { it.id == blockId && it is EditorBodyBlock.Text }
        if (idx < 0) return this
        if (ATTACH_IMG_REGEX.containsMatchIn(newText)) {
            val parsed = parse(newText, selectionStart, selectionEnd, seqStart = nextBlockSeq)
            val merged = blocks.subList(0, idx) + parsed.blocks + blocks.subList(idx + 1, blocks.size)
            return normalize(merged, parsed.activeTextBlockId, parsed.activeSelectionStart, parsed.activeSelectionEnd, parsed.nextBlockSeq)
        }
        val s = selectionStart.coerceIn(0, newText.length)
        val e = selectionEnd.coerceIn(0, newText.length)
        val existing = blocks[idx] as EditorBodyBlock.Text
        if (existing.text == newText && activeTextBlockId == blockId && activeSelectionStart == s && activeSelectionEnd == e) return this
        return copy(
            blocks = blocks.toMutableList().apply { this[idx] = existing.copy(text = newText) },
            activeTextBlockId = blockId, activeSelectionStart = s, activeSelectionEnd = e,
        )
    }

    fun updateSelection(blockId: String, selectionStart: Int, selectionEnd: Int): EditorBodyDocument {
        val block = blocks.firstOrNull { it.id == blockId } as? EditorBodyBlock.Text ?: return this
        val s = selectionStart.coerceIn(0, block.text.length)
        val e = selectionEnd.coerceIn(0, block.text.length)
        return if (activeTextBlockId == blockId && activeSelectionStart == s && activeSelectionEnd == e) this
        else copy(activeTextBlockId = blockId, activeSelectionStart = s, activeSelectionEnd = e)
    }

    fun insertImageAtCursor(imageId: String): EditorBodyDocument {
        val cleanId = imageId.trim()
        if (cleanId.isEmpty()) return this
        val idx = activeTextBlockIndex()
        val cur = blocks[idx] as EditorBodyBlock.Text
        val start = minOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        val end = maxOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        var seq = nextBlockSeq
        val imgBlock = EditorBodyBlock.Image("i${seq++}", cleanId)
        val afterBlock = EditorBodyBlock.Text("t${seq++}", cur.text.substring(end))
        val updated = buildList(blocks.size + 2) {
            addAll(blocks.subList(0, idx))
            add(cur.copy(text = cur.text.substring(0, start)))
            add(imgBlock)
            add(afterBlock)
            addAll(blocks.subList(idx + 1, blocks.size))
        }
        return copy(blocks = updated, activeTextBlockId = afterBlock.id, activeSelectionStart = 0, activeSelectionEnd = 0, nextBlockSeq = seq)
    }

    fun removeImageBlock(blockId: String): EditorBodyDocument {
        val idx = blocks.indexOfFirst { it.id == blockId && it is EditorBodyBlock.Image }
        if (idx < 0) return this
        val prevText = blocks.getOrNull(idx - 1) as? EditorBodyBlock.Text
        val seam = prevText?.text?.length ?: 0
        return normalize(blocks.filterIndexed { i, _ -> i != idx }, prevText?.id ?: "t$nextBlockSeq", seam, seam, nextBlockSeq)
    }

    fun removeImagesByImageId(imageId: String): EditorBodyDocument {
        if (blocks.none { it is EditorBodyBlock.Image && it.imageId == imageId }) return this
        return normalize(blocks.filterNot { it is EditorBodyBlock.Image && it.imageId == imageId }, activeTextBlockId, activeSelectionStart, activeSelectionEnd, nextBlockSeq)
    }

    fun insertTextAtCursor(snippet: String): EditorBodyDocument {
        if (snippet.isEmpty()) return this
        val cur = blocks[activeTextBlockIndex()] as EditorBodyBlock.Text
        val start = minOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        val end = maxOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        val cursor = start + snippet.length
        return updateTextBlock(cur.id, cur.text.substring(0, start) + snippet + cur.text.substring(end), cursor, cursor)
    }

    fun backspaceAtCursor(): EditorBodyDocument {
        val idx = activeTextBlockIndex()
        val cur = blocks[idx] as EditorBodyBlock.Text
        val start = minOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        val end = maxOf(activeSelectionStart, activeSelectionEnd).coerceIn(0, cur.text.length)
        return when {
            start != end -> updateTextBlock(cur.id, cur.text.substring(0, start) + cur.text.substring(end), start, start)
            start > 0 -> {
                val prev = previousGraphemeOffset(cur.text, start)
                updateTextBlock(cur.id, cur.text.substring(0, prev) + cur.text.substring(start), prev, prev)
            }
            else -> (blocks.getOrNull(idx - 1) as? EditorBodyBlock.Image)?.let { removeImageBlock(it.id) } ?: this
        }
    }

    private fun activeTextBlockIndex(): Int =
        blocks.indexOfFirst { it.id == activeTextBlockId && it is EditorBodyBlock.Text }.takeIf { it >= 0 } ?: blocks.lastIndex

    companion object {
        private val ATTACH_IMG_REGEX = Regex("""\[attachimg\](\d+)\[/attachimg\]""", RegexOption.IGNORE_CASE)

        fun empty(): EditorBodyDocument = EditorBodyDocument(listOf(EditorBodyBlock.Text("t0", "")), "t0", 0, 0, 1)

        fun parse(raw: String, selectionStart: Int? = null, selectionEnd: Int? = null, seqStart: Int = 0): EditorBodyDocument {
            val matches = ATTACH_IMG_REGEX.findAll(raw).toList()
            var seq = seqStart
            if (matches.isEmpty()) {
                val id = "t${seq++}"
                val s = (selectionStart ?: raw.length).coerceIn(0, raw.length)
                return EditorBodyDocument(listOf(EditorBodyBlock.Text(id, raw)), id, s, (selectionEnd ?: s).coerceIn(0, raw.length), seq)
            }
            val out = ArrayList<EditorBodyBlock>(matches.size * 2 + 1)
            val rawStart = selectionStart?.coerceIn(0, raw.length)
            val rawEnd = selectionEnd?.coerceIn(0, raw.length)
            var activeId: String? = null
            var activeStart = 0
            var activeEnd = 0
            var cursor = 0
            matches.forEachIndexed { i, match ->
                val segStart = if (i > 0) trimOneLeadingNewline(raw, cursor, match.range.first) else cursor
                val segEnd = trimOneTrailingNewline(raw, segStart, match.range.first)
                val textId = "t${seq++}"
                val textVal = raw.substring(segStart, segEnd)
                out += EditorBodyBlock.Text(textId, textVal)
                if (activeId == null && rawStart != null && rawStart in cursor..match.range.first) {
                    activeId = textId
                    activeStart = (rawStart - segStart).coerceIn(0, textVal.length)
                    activeEnd = ((rawEnd ?: rawStart) - segStart).coerceIn(0, textVal.length)
                }
                out += EditorBodyBlock.Image("i${seq++}", match.groupValues[1])
                cursor = match.range.last + 1
            }
            val tailStart = trimOneLeadingNewline(raw, cursor, raw.length)
            val tailId = "t${seq++}"
            val tailVal = raw.substring(tailStart)
            out += EditorBodyBlock.Text(tailId, tailVal)
            val resolvedActiveId = activeId ?: tailId
            if (activeId == null) {
                activeStart = if (rawStart != null) (rawStart - tailStart).coerceIn(0, tailVal.length) else tailVal.length
                activeEnd = if (rawEnd != null) (rawEnd - tailStart).coerceIn(0, tailVal.length) else activeStart
            }
            return EditorBodyDocument(out, resolvedActiveId, activeStart, activeEnd, seq)
        }

        private fun normalize(rawBlocks: List<EditorBodyBlock>, preferredId: String, selStart: Int, selEnd: Int, seqStart: Int): EditorBodyDocument {
            var seq = seqStart
            val res = ArrayList<EditorBodyBlock>(rawBlocks.size + 2)
            for (b in rawBlocks) when (b) {
                is EditorBodyBlock.Text -> {
                    val last = res.lastOrNull()
                    if (last is EditorBodyBlock.Text) res[res.lastIndex] = last.copy(text = last.text + b.text) else res += b
                }
                is EditorBodyBlock.Image -> {
                    if (res.isEmpty() || res.last() !is EditorBodyBlock.Text) res += EditorBodyBlock.Text("t${seq++}", "")
                    res += b
                }
            }
            if (res.isEmpty() || res.last() !is EditorBodyBlock.Text) res += EditorBodyBlock.Text("t${seq++}", "")
            val active = (res.firstOrNull { it.id == preferredId } as? EditorBodyBlock.Text) ?: (res.last() as EditorBodyBlock.Text)
            return EditorBodyDocument(res, active.id, selStart.coerceIn(0, active.text.length), selEnd.coerceIn(0, active.text.length), seq)
        }

        private fun trimOneLeadingNewline(raw: String, s: Int, e: Int): Int = when {
            s + 1 < e && raw[s] == '\r' && raw[s + 1] == '\n' -> s + 2
            s < e && raw[s] == '\n' -> s + 1
            else -> s
        }

        private fun trimOneTrailingNewline(raw: String, s: Int, e: Int): Int = when {
            e - 2 >= s && raw[e - 2] == '\r' && raw[e - 1] == '\n' -> e - 2
            e - 1 >= s && raw[e - 1] == '\n' -> e - 1
            else -> e
        }
    }
}

/** 选图 URI 安全白名单：仅允许带非空 authority 的 `content://` URI，拒绝 `file://`/`javascript:`/`intent:`/`data:`/`http(s):`/`mailto:` 等危险 scheme。 */
fun isAllowedUploadContentUri(uri: Uri?): Boolean =
    uri != null &&
        "content".equals(uri.scheme, ignoreCase = true) &&
        !uri.authority.isNullOrBlank() &&
        uri.toString().none { it.isISOControl() || it.isWhitespace() || Character.isSpaceChar(it) }
