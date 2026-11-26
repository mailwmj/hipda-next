package net.jejer.hipda.feature.routing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.jejer.hipda.feature.common.SafeLinkPolicy
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 鉴权附件下载结果。 */
sealed interface AttachmentDownloadResult {
    data class Success(val saved: SavedAttachment) : AttachmentDownloadResult
    data object InvalidUrl : AttachmentDownloadResult
    data object PermissionDenied : AttachmentDownloadResult
    data object Failed : AttachmentDownloadResult
}

/** 鉴权附件下载器抽象（默认复用项目 OkHttp 携论坛 Cookie 与 `cf_clearance` 流式下载，可注入单测）。 */
interface AttachmentDownloader {
    suspend fun download(
        rawUrl: String,
        suggestedFileName: String? = null,
    ): AttachmentDownloadResult
}

@Singleton
class OkHttpAttachmentDownloader(
    private val callFactory: Call.Factory,
    private val saver: AttachmentSaver,
    private val hasLegacyStoragePermission: () -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentDownloader {

    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        saver: AndroidAttachmentMediaStoreSaver,
        @ApplicationContext context: Context,
    ) : this(
        callFactory = okHttpClient,
        saver = saver,
        hasLegacyStoragePermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        },
        sdkInt = Build.VERSION.SDK_INT,
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun download(
        rawUrl: String,
        suggestedFileName: String?,
    ): AttachmentDownloadResult {
        // 严格 http/https 白名单：拒绝 mailto: 与全部危险 scheme，0 网络请求
        val safeUrl = SafeLinkPolicy.sanitizeHttpUrl(rawUrl) ?: return AttachmentDownloadResult.InvalidUrl
        if (requiresLegacyDownloadPermission(sdkInt) && !hasLegacyStoragePermission()) {
            return AttachmentDownloadResult.PermissionDenied
        }
        return withContext(ioDispatcher) {
            val call = callFactory.newCall(Request.Builder().url(safeUrl).get().build())
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext AttachmentDownloadResult.Failed
                    val body = response.body
                    val expectedLength = body.contentLength().takeIf { it > 0L }
                    val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    val rawCandidate = suggestedFileName?.takeIf { it.isNotBlank() }
                        ?: parseContentDispositionFileName(response.header("Content-Disposition"))
                        ?: extractUrlPathFileName(safeUrl)
                    val safeName = sanitizeAttachmentFileName(rawCandidate, contentType)
                    val effectiveMime = resolveAttachmentMimeType(contentType, safeName)
                    val saved = saver.save(safeName, effectiveMime) { output ->
                        var totalRead = 0L
                        body.byteStream().use { input ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                totalRead += read
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (expectedLength != null && totalRead < expectedLength) {
                            throw IOException("Incomplete attachment stream: $totalRead < $expectedLength")
                        }
                        totalRead
                    }
                    if (saved != null) AttachmentDownloadResult.Success(saved) else AttachmentDownloadResult.Failed
                }
            } catch (cancelled: CancellationException) {
                call.cancel()
                throw cancelled
            } catch (_: Throwable) {
                if (currentCoroutineContext()[Job]?.isActive == false) {
                    throw CancellationException("Attachment download cancelled")
                }
                AttachmentDownloadResult.Failed
            } finally {
                cancelHandle?.dispose()
            }
        }
    }

    private fun extractUrlPathFileName(safeUrl: String): String? {
        val seg = safeUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() } ?: return null
        return seg.takeUnless { it.endsWith(".php", ignoreCase = true) }
    }

    companion object {
        private const val STREAM_BUFFER_BYTES = 8192
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingBindingModule {
    @Binds
    @Singleton
    abstract fun bindAttachmentDownloader(impl: OkHttpAttachmentDownloader): AttachmentDownloader
}
