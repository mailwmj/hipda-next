package net.jejer.hipda.data.post

import android.net.Uri
import java.io.File
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.parser.post.PrePostMode
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage

/**
 * Target specification for fetching a Discuz! `post.php` form via [PostRepository.fetchPrePost].
 */
sealed interface PrePostRequest {
    val mode: PrePostMode

    data class NewThread(
        val fid: Int,
    ) : PrePostRequest {
        override val mode: PrePostMode = PrePostMode.NEW_THREAD
    }

    data class ReplyThread(
        val tid: String,
        val quickReply: Boolean = false,
    ) : PrePostRequest {
        override val mode: PrePostMode =
            if (quickReply) PrePostMode.QUICK_REPLY else PrePostMode.REPLY_THREAD
    }

    data class ReplyPost(
        val tid: String,
        val pid: String,
    ) : PrePostRequest {
        override val mode: PrePostMode = PrePostMode.REPLY_POST
    }

    data class QuotePost(
        val tid: String,
        val pid: String,
    ) : PrePostRequest {
        override val mode: PrePostMode = PrePostMode.QUOTE_POST
    }

    data class EditPost(
        val fid: Int,
        val tid: String,
        val pid: String,
        val page: Int = 1,
    ) : PrePostRequest {
        override val mode: PrePostMode = PrePostMode.EDIT_POST
    }
}

/**
 * Result of prefetching a `post.php` form.
 */
sealed interface PrePostResult {
    data class Success(val info: PrePostInfo) : PrePostResult

    data object RealNameBindRequired : PrePostResult

    data class AuthRequired(val reason: AuthRequiredReason) : PrePostResult

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : PrePostResult

    data class SiteError(val message: String) : PrePostResult

    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : PrePostResult

    data class InvalidInput(val message: String) : PrePostResult
}

/**
 * Immutable write request covering all 6 forum write modes:
 * [NewThread], [ReplyThread], [ReplyPost], [QuotePost], [EditPost], and [DeletePost].
 */
sealed interface PostSubmitRequest {
    val draftKey: DraftKey?
    val newAttachments: List<String>
    val deletedAttachments: List<String>
    val prePostInfo: PrePostInfo?

    data class NewThread(
        val fid: Int,
        val subject: String,
        val content: String,
        val typeId: String = "0",
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest

    data class ReplyThread(
        val tid: String,
        val content: String,
        val quickReply: Boolean = false,
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest

    data class ReplyPost(
        val tid: String,
        val pid: String,
        val content: String,
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest

    data class QuotePost(
        val tid: String,
        val pid: String,
        val content: String,
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest

    data class EditPost(
        val fid: Int,
        val tid: String,
        val pid: String,
        val content: String,
        val subject: String? = null,
        val typeId: String? = null,
        val page: Int = 1,
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest

    data class DeletePost(
        val fid: Int,
        val tid: String,
        val pid: String,
        val page: Int = 1,
        val subject: String? = null,
        val typeId: String? = null,
        val content: String = "",
        override val draftKey: DraftKey? = null,
        override val newAttachments: List<String> = emptyList(),
        override val deletedAttachments: List<String> = emptyList(),
        override val prePostInfo: PrePostInfo? = null,
    ) : PostSubmitRequest
}

/**
 * Result of executing [PostRepository.submitPost].
 */
sealed interface PostSubmitResult {
    data class Success(
        val tid: String,
        val threadDeleted: Boolean = false,
        val redirectedPage: ThreadDetailPage? = null,
    ) : PostSubmitResult

    /**
     * 30-second post cooldown is active; [remainingSeconds] is rounded UP (`1ms..1000ms -> 1`).
     */
    data class CooldownActive(val remainingSeconds: Int) : PostSubmitResult

    data object RealNameBindRequired : PostSubmitResult

    data class AuthRequired(val reason: AuthRequiredReason) : PostSubmitResult

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : PostSubmitResult

    data class SiteError(val message: String) : PostSubmitResult

    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : PostSubmitResult

    data class InvalidInput(val message: String) : PostSubmitResult
}

/**
 * Image source for [ImageCompressor] and [ImageUploadRepository].
 *
 * [original] defaults to `false` (aligned with `product-design.md` §7.1: auto-compress images
 * before upload) so ordinary photos are compressed within the upload byte budget by default, while
 * compliant GIFs and ultra-long images (`aspectRatio >= 3.0`) still pass through directly via
 * [ImageCompressor]. Callers may explicitly pass `original = true` to upload compliant unrotated
 * images without re-encoding.
 */
sealed interface ImageUploadInput {
    val original: Boolean

    data class ContentUri(
        val uri: Uri,
        override val original: Boolean = false,
    ) : ImageUploadInput

    data class LocalFile(
        val file: File,
        override val original: Boolean = false,
    ) : ImageUploadInput

    data class Bytes(
        val bytes: ByteArray,
        val mimeType: String? = null,
        val fileName: String? = null,
        override val original: Boolean = false,
    ) : ImageUploadInput {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes) &&
                mimeType == other.mimeType &&
                fileName == other.fileName &&
                original == other.original
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + (fileName?.hashCode() ?: 0)
            result = 31 * result + original.hashCode()
            return result
        }
    }
}

/**
 * Structured failure reason for [ImageUploadProgress.Failed] and [ImageCompressionResult.Failure].
 */
sealed interface ImageUploadError {
    val message: String

    data class InvalidInput(override val message: String) : ImageUploadError

    data class GifTooLarge(
        val actualBytes: Long,
        val maxUploadBytes: Int,
        override val message: String = "GIF图片大小超出上传限制",
    ) : ImageUploadError

    data class UnableToCompress(
        val originalBytes: Long,
        val maxUploadBytes: Int,
        override val message: String = "无法压缩图片至上传限制内",
    ) : ImageUploadError

    data class DecodeFailed(
        override val message: String = "无法解析图片文件",
        val cause: Throwable? = null,
    ) : ImageUploadError

    data class AuthRequired(
        val reason: AuthRequiredReason,
        override val message: String = "登录已失效，请重新登录",
    ) : ImageUploadError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        override val message: String = "需要完成安全验证",
    ) : ImageUploadError

    data class SiteRejected(override val message: String) : ImageUploadError

    data class NetworkError(
        override val message: String,
        val cause: Throwable? = null,
    ) : ImageUploadError
}

/**
 * Progress stream emitted by [ImageUploadRepository.uploadImage].
 */
sealed interface ImageUploadProgress {
    data object Compressing : ImageUploadProgress

    data class Uploading(
        val bytesWritten: Long,
        val totalBytes: Long,
        val fraction: Float,
    ) : ImageUploadProgress

    data class Succeeded(
        val imageId: String,
        val compressedBytes: Long,
        val wasCompressed: Boolean,
    ) : ImageUploadProgress

    data class Failed(
        val error: ImageUploadError,
    ) : ImageUploadProgress
}
