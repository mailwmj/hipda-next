package net.jejer.hipda.parser.post

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

/**
 * 发帖/回复/引用/编辑预取表单纯 JVM 解析器（对照旧 `PrePostAsyncTask.java:86-229`）。
 * 纯数值返回 `maxUploadFileSizeBytes`，不读写任何全局设置，也不修改调用方传入的 [Document] DOM。
 */
object PrePostParser {

    private const val DEFAULT_ERROR_MESSAGE = "页面解析错误"
    private const val MIN_UPLOAD_SIZE_BYTES = 1024

    fun parse(html: String, mode: PrePostMode): PrePostParseResult = parse(Jsoup.parse(html), mode)

    fun parse(doc: Document, mode: PrePostMode): PrePostParseResult {
        val formhash = DiscuzCommonParser.parseFormhash(doc)
        if (formhash == null) {
            val errorMessage = DiscuzCommonParser.parseErrorMessage(doc)?.trim()?.ifEmpty { null }
                ?: DEFAULT_ERROR_MESSAGE
            return PrePostParseResult.Error(errorMessage)
        }

        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val textarea = doc.firstMatch("textarea")
        val isReplyOrQuote = mode == PrePostMode.REPLY_POST || mode == PrePostMode.QUOTE_POST
        val text: String
        val quoteText: String
        if (textarea != null) {
            if (isReplyOrQuote) {
                text = ""
                quoteText = textarea.text()
            } else {
                text = textarea.text()
                quoteText = ""
            }
        } else {
            text = ""
            quoteText = ""
        }

        val uid = parseUid(doc)
        val hash = doc.firstMatch("input[name=hash]")?.attr("value")?.trim()?.ifEmpty { null }
        val subject = doc.firstMatch("input[name=subject]")?.attr("value")
        val isDeletable = doc.firstMatch("input#delete") != null
        val maxUploadFileSizeBytes = parseMaxUploadFileSizeBytes(doc)

        val noticeAuthor = if (isReplyOrQuote) doc.firstMatch("input[name=noticeauthor]")?.attr("value") else null
        val noticeAuthorMsg = if (isReplyOrQuote) doc.firstMatch("input[name=noticeauthormsg]")?.attr("value") else null
        val noticeTrimStr = if (isReplyOrQuote) doc.firstMatch("input[name=noticetrimstr]")?.attr("value") else null

        val (existingImages, existingAttachments) = parseExistingMedia(doc)
        val (typeId, typeOptions) = parseTypeOptions(doc)

        return PrePostParseResult.Success(
            PrePostInfo(
                formhash = formhash,
                uid = uid,
                hash = hash,
                subject = subject,
                text = text,
                quoteText = quoteText,
                isDeletable = isDeletable,
                maxUploadFileSizeBytes = maxUploadFileSizeBytes,
                noticeAuthor = noticeAuthor,
                noticeAuthorMsg = noticeAuthorMsg,
                noticeTrimStr = noticeTrimStr,
                existingImages = existingImages,
                existingAttachments = existingAttachments,
                typeId = typeId,
                typeOptions = typeOptions,
                meta = meta,
            ),
        )
    }

    private fun parseUid(doc: Document): String? {
        for (script in doc.select("script")) {
            val uid = DiscuzUrlHelper.middleString(script.data(), "discuz_uid = ", ",").trim()
            if (uid.isNotEmpty()) return uid
        }
        return doc.firstMatch("input[name=uid]")?.attr("value")?.trim()?.ifEmpty { null }
    }

    private fun parseMaxUploadFileSizeBytes(doc: Document): Int? {
        val uploadInfo = doc.firstMatch("div.uploadinfo")?.text() ?: return null
        if (!uploadInfo.contains("文件尺寸")) return null
        val sizeText = DiscuzUrlHelper.middleString(uploadInfo.uppercase(Locale.US), "小于", "B").trim()
        if (sizeText.length < 2) return null

        val numberPart = sizeText.substring(0, sizeText.length - 1).trim()
        val unit = sizeText.substring(sizeText.length - 1)
        val size = numberPart.toFloatOrNull() ?: return null
        if (size <= 0f) return null

        val bytes = when (unit) {
            "K" -> (size * 1024f).toInt()
            "M" -> (size * 1024f * 1024f).toInt()
            else -> return null
        }
        return bytes.takeIf { it > MIN_UPLOAD_SIZE_BYTES }
    }

    private fun parseExistingMedia(doc: Document): Pair<List<String>, List<String>> {
        val images = LinkedHashSet<String>()
        val attachments = LinkedHashSet<String>()

        // 1. 未使用图片附件列表
        for (img in doc.select("div#unusedimgattachlist table.imglist img")) {
            val src = img.attr("src")
            val rawId = img.attr("id")
            if (src.contains("attachments/") && rawId.contains('_')) {
                val imgId = rawId.substringAfterLast('_')
                if (isAsciiDigits(imgId)) {
                    images.add(imgId)
                }
            }
        }

        // 2. 已上传图片列表
        for (img in doc.select("div.upfilelist img[id^=image_]")) {
            val imgId = img.attr("id").removePrefix("image_")
            if (isAsciiDigits(imgId)) {
                images.add(imgId)
            }
        }

        // 3. 附件区脚本链接（insertAttachimgTag / insertAttachTag）
        for (anchor in doc.select("div.upfilelist span a")) {
            val href = anchor.attr("href")
            val onclick = anchor.attr("onclick")
            if (!href.startsWith("javascript")) continue
            when {
                onclick.startsWith("insertAttachimgTag") -> {
                    val imgId = DiscuzUrlHelper.middleString(onclick, "insertAttachimgTag('", "'")
                    if (isAsciiDigits(imgId)) {
                        images.add(imgId)
                    }
                }
                onclick.startsWith("insertAttachTag") -> {
                    val attachId = DiscuzUrlHelper.middleString(onclick, "insertAttachTag('", "'")
                    if (isAsciiDigits(attachId)) {
                        attachments.add(attachId)
                    }
                }
            }
        }

        return images.toList() to attachments.toList()
    }

    private fun parseTypeOptions(doc: Document): Pair<String?, Map<String, String>> {
        var selectedTypeId: String? = null
        val options = buildMap {
            for ((index, option) in doc.select("#typeid > option").withIndex()) {
                val value = option.`val`()
                put(value, option.text())
                if (index == 0 || isOptionSelected(option)) {
                    selectedTypeId = value
                }
            }
        }
        return selectedTypeId to options
    }

    private fun isOptionSelected(option: org.jsoup.nodes.Element): Boolean {
        if (!option.hasAttr("selected")) return false
        val attrVal = option.attr("selected").trim()
        return attrVal.isEmpty() || attrVal.equals("selected", ignoreCase = true)
    }

    private fun isAsciiDigits(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' }
}
