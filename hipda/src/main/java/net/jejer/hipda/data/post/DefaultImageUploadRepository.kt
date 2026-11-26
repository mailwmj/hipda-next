package net.jejer.hipda.data.post

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.auth.buildForumUrl
import net.jejer.hipda.data.network.AuthCookieWriteGuard
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.decodeBody
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.post.UploadImageResult
import net.jejer.hipda.parser.post.UploadResponseParser
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

@Singleton
class DefaultImageUploadRepository internal constructor(
    private val siteAccessGateway: SiteAccessGateway,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val imageCompressor: ImageCompressor,
    private val nowMs: () -> Long,
) : ImageUploadRepository {

    @Inject
    constructor(
        siteAccessGateway: SiteAccessGateway,
        settingsRepository: SettingsRepository,
        authRepository: AuthRepository,
        imageCompressor: ImageCompressor,
    ) : this(
        siteAccessGateway = siteAccessGateway,
        settingsRepository = settingsRepository,
        authRepository = authRepository,
        imageCompressor = imageCompressor,
        nowMs = System::currentTimeMillis,
    )

    override fun uploadImage(
        input: ImageUploadInput,
        uid: String,
        hash: String,
        maxUploadBytes: Int?,
        priority: AccessPriority,
    ): Flow<ImageUploadProgress> = channelFlow {
        val trimmedUid = uid.trim()
        val trimmedHash = hash.trim()
        if (trimmedUid.isEmpty() || trimmedHash.isEmpty()) {
            send(ImageUploadProgress.Failed(ImageUploadError.InvalidInput("缺少上传所需的认证参数")))
            return@channelFlow
        }
        val effectiveMaxBytes = maxUploadBytes ?: ImageCompressor.DEFAULT_MAX_UPLOAD_BYTES
        if (effectiveMaxBytes <= 0) {
            send(ImageUploadProgress.Failed(ImageUploadError.InvalidInput("上传大小限制无效")))
            return@channelFlow
        }

        send(ImageUploadProgress.Compressing)

        val prepared = when (val compressed = imageCompressor.prepare(input, effectiveMaxBytes)) {
            is ImageCompressionResult.Success -> compressed.image
            is ImageCompressionResult.Failure -> {
                send(ImageUploadProgress.Failed(compressed.error))
                return@channelFlow
            }
        }

        val cancelled = AtomicBoolean(false)
        val completionHandle = coroutineContext.job.invokeOnCompletion { cancelled.set(true) }
        val maxReportedBytes = AtomicLong(0L)
        try {
            val fileName = buildUploadFileName(
                wasCompressed = prepared.wasCompressed,
                mimeType = prepared.mimeType,
                timestampMs = nowMs(),
            )
            val totalBytes = prepared.byteSize.coerceAtLeast(1L)
            val mediaType = prepared.mimeType.toMediaTypeOrNull()

            suspend fun performAttempt(): String {
                val forumServer = settingsRepository.settings.first().forumServer
                val uploadUrl = buildForumUrl(forumServer, UPLOAD_RELATIVE_PATH)
                val fileBody = CountingFileRequestBody(
                    file = prepared.file,
                    contentType = mediaType,
                    cancelled = cancelled,
                    onBytesWritten = { written, total ->
                        val previousMax = maxReportedBytes.get()
                        val clamped = written.coerceIn(previousMax, total)
                        if (clamped > previousMax &&
                            maxReportedBytes.compareAndSet(previousMax, clamped)
                        ) {
                            val fraction = (clamped.toDouble() / total.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                            trySend(
                                ImageUploadProgress.Uploading(
                                    bytesWritten = clamped,
                                    totalBytes = total,
                                    fraction = fraction,
                                ),
                            )
                        }
                    },
                )
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("uid", trimmedUid)
                    .addFormDataPart("hash", trimmedHash)
                    .addFormDataPart("Filedata", fileName, fileBody)
                    .build()

                val rawRequest = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBody)
                    .build()

                val guard = (authRepository as? DefaultAuthRepository)?.captureCookieWriteGuard()
                val request = if (guard != null) {
                    rawRequest.newBuilder()
                        .tag(AuthCookieWriteGuard::class.java, guard)
                        .build()
                } else {
                    rawRequest
                }

                siteAccessGateway.execute(request = request, priority = priority).use { response ->
                    if (!response.isSuccessful) {
                        throw ForumHttpException(response.code, response.request.url.toString())
                    }
                    val bytes = response.body.bytes()
                    val responseContentType = response.header("Content-Type")
                        ?: response.body.contentType()?.toString()
                    return decodeBody(bytes, responseContentType)
                }
            }

            val epochBeforeFirstAttempt = authRepository.sessionEpoch
            var responseText = performAttempt()
            var parsed = UploadResponseParser.parse(responseText)

            if (parsed is UploadImageResult.Failed && isUploadResponseUnauthenticated(responseText)) {
                when (
                    val relogin = authRepository.trySilentRelogin(
                        failedEpoch = epochBeforeFirstAttempt,
                        priority = priority,
                    )
                ) {
                    is SilentReloginResult.Recovered -> {
                        responseText = performAttempt()
                        parsed = UploadResponseParser.parse(responseText)
                        if (parsed is UploadImageResult.Failed &&
                            isUploadResponseUnauthenticated(responseText)
                        ) {
                            send(
                                ImageUploadProgress.Failed(
                                    ImageUploadError.AuthRequired(
                                        reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                                    ),
                                ),
                            )
                            return@channelFlow
                        }
                    }

                    SilentReloginResult.Suppressed -> {
                        send(
                            ImageUploadProgress.Failed(
                                ImageUploadError.AuthRequired(
                                    reason = AuthRequiredReason.MANUALLY_LOGGED_OUT,
                                ),
                            ),
                        )
                        return@channelFlow
                    }

                    SilentReloginResult.NotLoggedIn -> {
                        send(
                            ImageUploadProgress.Failed(
                                ImageUploadError.AuthRequired(
                                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                                ),
                            ),
                        )
                        return@channelFlow
                    }

                    is SilentReloginResult.Failed -> {
                        send(
                            ImageUploadProgress.Failed(
                                ImageUploadError.AuthRequired(
                                    reason = AuthRequiredReason.RELOGIN_FAILED,
                                ),
                            ),
                        )
                        return@channelFlow
                    }
                }
            }

            when (val finalResult = parsed) {
                is UploadImageResult.Success -> {
                    if (maxReportedBytes.getAndSet(totalBytes) < totalBytes) {
                        send(
                            ImageUploadProgress.Uploading(
                                bytesWritten = totalBytes,
                                totalBytes = totalBytes,
                                fraction = 1f,
                            ),
                        )
                    }
                    send(
                        ImageUploadProgress.Succeeded(
                            imageId = finalResult.imageId,
                            compressedBytes = prepared.byteSize,
                            wasCompressed = prepared.wasCompressed,
                        ),
                    )
                }

                is UploadImageResult.Failed -> {
                    send(
                        ImageUploadProgress.Failed(
                            ImageUploadError.SiteRejected(finalResult.reason),
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            cancelled.set(true)
            throw e
        } catch (e: ChallengeIncompleteException) {
            send(
                ImageUploadProgress.Failed(
                    ImageUploadError.ChallengeRequired(
                        cooldownUntilMs = e.cooldownUntilMs,
                        rejected = false,
                    ),
                ),
            )
        } catch (e: ChallengeRejectedException) {
            send(
                ImageUploadProgress.Failed(
                    ImageUploadError.ChallengeRequired(
                        cooldownUntilMs = e.cooldownUntilMs,
                        rejected = true,
                    ),
                ),
            )
        } catch (e: ChallengeAbandonedException) {
            send(
                ImageUploadProgress.Failed(
                    ImageUploadError.NetworkError(
                        message = e.message ?: "安全验证已放弃",
                        cause = e,
                    ),
                ),
            )
        } catch (e: ForumHttpException) {
            send(
                ImageUploadProgress.Failed(
                    ImageUploadError.NetworkError(
                        message = "图片上传失败，服务器返回异常",
                        cause = e,
                    ),
                ),
            )
        } catch (e: IOException) {
            send(
                ImageUploadProgress.Failed(
                    ImageUploadError.NetworkError(
                        message = "网络连接失败",
                        cause = e,
                    ),
                ),
            )
        } finally {
            cancelled.set(true)
            completionHandle.dispose()
            prepared.cleanup()
        }
    }.buffer(Channel.UNLIMITED)

    private class CountingFileRequestBody(
        private val file: File,
        private val contentType: MediaType?,
        private val cancelled: AtomicBoolean,
        private val onBytesWritten: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ) : RequestBody() {

        override fun contentType(): MediaType? = contentType

        override fun contentLength(): Long = file.length()

        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            val reportStep = maxOf(CHUNK_SIZE_BYTES.toLong(), total / MAX_PROGRESS_SEGMENTS)
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var bytesWritten = 0L
            var lastReported = 0L

            FileInputStream(file).use { input ->
                while (true) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted) {
                        throw IOException("Upload cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    sink.write(buffer, 0, read)
                    bytesWritten += read
                    if (bytesWritten >= total || bytesWritten - lastReported >= reportStep) {
                        lastReported = bytesWritten
                        onBytesWritten(bytesWritten, total)
                    }
                }
            }
            if (bytesWritten > lastReported) {
                onBytesWritten(bytesWritten, total)
            }
        }
    }

    companion object {
        const val UPLOAD_RELATIVE_PATH: String =
            "misc.php?action=swfupload&operation=upload&simple=1&type=image"

        private const val CHUNK_SIZE_BYTES: Int = 8 * 1024
        private const val MAX_PROGRESS_SEGMENTS: Long = 32L

        private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyMMdd_HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())

        private val UNAUTH_UPLOAD_MARKERS: List<String> = listOf(
            "您还未登录",
            "您尚未登录",
            "尚未登录",
            "请先登录",
            "您需要先登录",
        )

        internal fun buildUploadFileName(
            wasCompressed: Boolean,
            mimeType: String,
            timestampMs: Long,
        ): String {
            val separator = if (wasCompressed) "_" else "-"
            val timestamp = FILE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs))
            val suffix = ImageCompressor.fileSuffixForMime(mimeType)
            return "Hi$separator$timestamp.$suffix"
        }

        internal fun isUploadResponseUnauthenticated(responseText: String): Boolean {
            if (responseText.contains("DISCUZUPLOAD")) return false
            if (responseText.contains("id=\"umenu\"", ignoreCase = true) ||
                responseText.contains("id='umenu'", ignoreCase = true)
            ) {
                val meta = DiscuzCommonParser.parsePageMeta(responseText)
                return !meta.session.isLoggedIn
            }
            return UNAUTH_UPLOAD_MARKERS.any { marker -> responseText.contains(marker) }
        }
    }
}
