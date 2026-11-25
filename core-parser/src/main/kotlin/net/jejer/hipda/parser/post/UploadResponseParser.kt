package net.jejer.hipda.parser.post

import net.jejer.hipda.parser.common.DiscuzUrlHelper

sealed interface UploadImageResult {
    data class Success(val imageId: String) : UploadImageResult
    data class Failed(val reason: String) : UploadImageResult
}

/**
 * Discuz! SWF/普通上传图片响应解析器（对照旧 `UploadImgHelper.java:210-227`）。
 * 典型成功响应格式：`DISCUZUPLOAD|0|<imgId>|1`。
 */
object UploadResponseParser {

    private const val MARKER = "DISCUZUPLOAD"
    private const val ERROR_INVALID_ID = "无效上传图片ID"
    private const val ERROR_MISSING_ID = "无法获取图片ID"

    fun parse(responseText: String): UploadImageResult {
        val markerIndex = responseText.indexOf(MARKER)
        if (markerIndex < 0) {
            return UploadImageResult.Failed(ERROR_MISSING_ID)
        }
        val payload = responseText.substring(markerIndex).trim()
        val segments = payload.split('|')
        if (segments.size < 3) {
            return UploadImageResult.Failed(ERROR_INVALID_ID)
        }
        val imgId = segments[2].trim()
        return if (imgId == "0" || !DiscuzUrlHelper.isValidId(imgId)) {
            UploadImageResult.Failed(ERROR_INVALID_ID)
        } else {
            UploadImageResult.Success(imgId)
        }
    }
}
