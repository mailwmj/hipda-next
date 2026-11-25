package net.jejer.hipda.data.post

import kotlinx.coroutines.flow.Flow
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Repository for compressing images and uploading them via Discuz! multipart `swfupload` endpoint
 * with real byte-level upload progress, concurrency safety, and cooperative cancellation.
 */
interface ImageUploadRepository {
    /**
     * Compresses (when needed) and uploads [input] using [uid] and [hash] obtained from `PrePost`.
     *
     * Emits [ImageUploadProgress.Compressing], zero or more monotonically increasing
     * [ImageUploadProgress.Uploading] updates ending at `fraction == 1f`, and finally either
     * [ImageUploadProgress.Succeeded] or [ImageUploadProgress.Failed].
     */
    fun uploadImage(
        input: ImageUploadInput,
        uid: String,
        hash: String,
        maxUploadBytes: Int? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): Flow<ImageUploadProgress>
}
