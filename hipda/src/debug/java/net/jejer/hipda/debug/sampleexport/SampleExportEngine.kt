package net.jejer.hipda.debug.sampleexport

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.decodeBody
import net.jejer.hipda.parser.sanitize.HtmlSanitizer
import net.jejer.hipda.parser.sanitize.Identity
import net.jejer.hipda.parser.sanitize.Leak
import net.jejer.hipda.parser.sanitize.PageType
import net.jejer.hipda.parser.sanitize.SampleLeakScanner
import okhttp3.HttpUrl
import okhttp3.Request

data class SampleExportRunResult(
    val items: List<SampleExportItemState>,
    val zipFile: File?,
    val resolvedTid: String?,
)

@Serializable
private data class ExportManifestDocument(
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val sanitized: Boolean,
    val zeroLeaksVerified: Boolean,
    val includePersonalHistory: Boolean,
    val entries: List<ExportManifestEntry>,
)

@Serializable
private data class ExportManifestEntry(
    val id: String,
    val relativePath: String,
    val pageType: String,
    val status: String,
    val leakCount: Int,
    val byteSize: Int,
    val note: String? = null,
)

/**
 * 纯 Kotlin 只读样本抓取、脱敏、零泄漏校验与 zip 打包引擎（与 UI 解耦）。
 */
class SampleExportEngine(
    private val siteAccessGateway: SiteAccessGateway,
    private val exportDir: File,
    private val baseUrl: HttpUrl = SampleExportConstants.FORUM_BASE_URL,
    private val sanitizer: (html: String, pageType: PageType, identity: Identity) -> String = HtmlSanitizer::sanitize,
    private val leakScanner: (html: String, selfUid: String?) -> List<Leak> = SampleLeakScanner::scan,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { prettyPrint = true }
    private val statusById = linkedMapOf<SampleEntryId, SampleExportStatus>()
    private val verifiedHtmlById = linkedMapOf<SampleEntryId, String>()
    private val pendingIdentityRawHtmlById = linkedMapOf<SampleEntryId, String>()

    private var discoveredIdentity: Identity? = null
    private var discoveredTid: String? = null
    private var discoveredAuthorUid: String? = null
    private var discoveredPeerUid: String? = null
    private var latestZip: File? = null

    init {
        resetStates()
    }

    fun currentItems(): List<SampleExportItemState> =
        SampleExportManifest.entries.map { SampleExportItemState(it, statusById[it.id] ?: SampleExportStatus.Pending) }

    fun latestZipFile(): File? = latestZip

    suspend fun runAll(
        includePersonalHistory: Boolean = true,
        customTid: String? = null,
        onItemUpdated: (SampleExportItemState) -> Unit = {},
    ): SampleExportRunResult {
        resetStates()
        verifiedHtmlById.clear()
        pendingIdentityRawHtmlById.clear()
        discoveredIdentity = null
        discoveredTid = null
        discoveredAuthorUid = null
        discoveredPeerUid = null
        latestZip = null

        val tid = SampleHtmlExtractors.normalizePositiveTid(customTid)
        for (spec in SampleExportManifest.entries) {
            processEntry(spec, includePersonalHistory, tid, onItemUpdated)
        }
        return finishRun(includePersonalHistory, tid)
    }

    suspend fun retryEntry(
        id: SampleEntryId,
        includePersonalHistory: Boolean = true,
        customTid: String? = null,
        onItemUpdated: (SampleExportItemState) -> Unit = {},
    ): SampleExportRunResult {
        enforcePersonalHistoryPolicy(includePersonalHistory, onItemUpdated)
        val spec = SampleExportManifest.entries.first { it.id == id }
        val tid = SampleHtmlExtractors.normalizePositiveTid(customTid)
        processEntry(spec, includePersonalHistory, tid, onItemUpdated)
        return finishRun(includePersonalHistory, tid)
    }

    /**
     * 当用户事后切换隐私开关时立即生效：
     * - 关闭时清除已缓存的 `my_*` 样本、标为跳过并立即基于剩余已校验样本重建无 `my_*` 的 zip；
     * - 重新开启时重置被隐私跳过的条目为待抓取并使旧 zip 失效，要求重新抓取后再分享。
     */
    suspend fun updatePersonalHistoryInclusion(
        includePersonalHistory: Boolean,
        customTid: String? = null,
        onItemUpdated: (SampleExportItemState) -> Unit = {},
    ): SampleExportRunResult {
        val tid = SampleHtmlExtractors.normalizePositiveTid(customTid)
        if (!includePersonalHistory) {
            val hadExistingResults = verifiedHtmlById.isNotEmpty() ||
                pendingIdentityRawHtmlById.isNotEmpty() ||
                latestZip != null
            enforcePersonalHistoryPolicy(includePersonalHistory = false, onItemUpdated = onItemUpdated, markSkipped = hadExistingResults)
            return finishRun(includePersonalHistory = false, customTid = tid)
        }
        for (spec in SampleExportManifest.entries) {
            if (spec.isPersonalHistory) {
                verifiedHtmlById.remove(spec.id)
                pendingIdentityRawHtmlById.remove(spec.id)
                if (statusById[spec.id] is SampleExportStatus.Skipped) {
                    updateStatus(spec, SampleExportStatus.Pending, onItemUpdated)
                }
            }
        }
        withContext(ioDispatcher) { removeOldArchives(keepName = null) }
        latestZip = null
        return SampleExportRunResult(currentItems(), null, tid ?: discoveredTid)
    }

    private suspend fun finishRun(includePersonalHistory: Boolean, customTid: String?): SampleExportRunResult {
        latestZip = writeZipIfAny(includePersonalHistory)
        return SampleExportRunResult(currentItems(), latestZip, customTid ?: discoveredTid)
    }

    private fun enforcePersonalHistoryPolicy(
        includePersonalHistory: Boolean,
        onItemUpdated: (SampleExportItemState) -> Unit,
        markSkipped: Boolean = true,
    ) {
        if (includePersonalHistory) return
        for (spec in SampleExportManifest.entries) {
            if (!spec.isPersonalHistory) continue
            verifiedHtmlById.remove(spec.id)
            pendingIdentityRawHtmlById.remove(spec.id)
            if (markSkipped) {
                updateStatus(spec, SampleExportStatus.Skipped(PRIVACY_SKIP_REASON), onItemUpdated)
            }
        }
    }

    private suspend fun processEntry(
        spec: SampleSpec,
        includePersonalHistory: Boolean,
        customTid: String?,
        onItemUpdated: (SampleExportItemState) -> Unit,
    ) {
        if (spec.isPersonalHistory && !includePersonalHistory) {
            verifiedHtmlById.remove(spec.id)
            pendingIdentityRawHtmlById.remove(spec.id)
            updateStatus(spec, SampleExportStatus.Skipped(PRIVACY_SKIP_REASON), onItemUpdated)
            return
        }

        val url = when (val target = resolveTargetUrl(spec.id, customTid)) {
            is TargetResolution.Ready -> target.url
            is TargetResolution.Skip -> {
                verifiedHtmlById.remove(spec.id)
                pendingIdentityRawHtmlById.remove(spec.id)
                updateStatus(spec, SampleExportStatus.Skipped(target.reason), onItemUpdated)
                return
            }
            is TargetResolution.MissingPrerequisite -> {
                failEntry(spec, target.reason, null, onItemUpdated)
                return
            }
        }

        updateStatus(spec, SampleExportStatus.Fetching, onItemUpdated)

        val fetchedHtml = try {
            val request = Request.Builder().url(url).get().build()
            siteAccessGateway.execute(request, AccessPriority.UserExplicit).use { response ->
                if (!response.isSuccessful) {
                    failEntry(spec, "加载失败（HTTP ${response.code}）", "GET ${url.encodedPath} -> HTTP ${response.code}", onItemUpdated)
                    return
                }
                val contentType = response.header("Content-Type")
                val bytes = withContext(ioDispatcher) { response.body.bytes() }
                decodeBody(bytes, contentType)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRejectedException) {
            failEntry(spec, "验证未通过", e.message, onItemUpdated)
            return
        } catch (e: ChallengeIncompleteException) {
            failEntry(spec, "安全验证未完成", e.message, onItemUpdated)
            return
        } catch (e: IOException) {
            failEntry(spec, "网络连接失败", "${e::class.java.simpleName}: ${e.message.orEmpty()}", onItemUpdated)
            return
        } catch (e: Exception) {
            failEntry(spec, "加载失败", "${e::class.java.simpleName}: ${e.message.orEmpty()}", onItemUpdated)
            return
        }

        val identityBecameComplete = withContext(ioDispatcher) {
            updateDynamicContext(spec.id, fetchedHtml)
        }

        val activeIdentity = discoveredIdentity
        if (identityBecameComplete && activeIdentity != null && activeIdentity.isComplete()) {
            resanitizePendingEntries(activeIdentity, includePersonalHistory, onItemUpdated)
        }

        if (activeIdentity == null || !activeIdentity.isComplete()) {
            pendingIdentityRawHtmlById[spec.id] = fetchedHtml
            verifiedHtmlById.remove(spec.id)
            updateStatus(
                spec,
                SampleExportStatus.Failed(
                    reason = INCOMPLETE_IDENTITY_REASON,
                    detail = "缺少完整登录身份（uid=${activeIdentity?.uid.orEmpty().isNotBlank()}，username=${activeIdentity?.username.orEmpty().isNotBlank()}）",
                ),
                onItemUpdated,
            )
            return
        }

        pendingIdentityRawHtmlById.remove(spec.id)
        sanitizeScanAndRecord(spec, fetchedHtml, activeIdentity, onItemUpdated)
    }

    private suspend fun resanitizePendingEntries(
        completeIdentity: Identity,
        includePersonalHistory: Boolean,
        onItemUpdated: (SampleExportItemState) -> Unit,
    ) {
        val pendingIds = pendingIdentityRawHtmlById.keys.toList()
        for (pendingId in pendingIds) {
            val rawHtml = pendingIdentityRawHtmlById.remove(pendingId) ?: continue
            val pendingSpec = SampleExportManifest.entries.first { it.id == pendingId }
            if (pendingSpec.isPersonalHistory && !includePersonalHistory) {
                verifiedHtmlById.remove(pendingId)
                updateStatus(pendingSpec, SampleExportStatus.Skipped(PRIVACY_SKIP_REASON), onItemUpdated)
                continue
            }
            sanitizeScanAndRecord(pendingSpec, rawHtml, completeIdentity, onItemUpdated)
        }
    }

    private suspend fun sanitizeScanAndRecord(
        spec: SampleSpec,
        rawHtml: String,
        identity: Identity,
        onItemUpdated: (SampleExportItemState) -> Unit,
    ) {
        val (sanitized, leaks) = withContext(ioDispatcher) {
            val cleaned = sanitizer(rawHtml, spec.pageType, identity)
            cleaned to leakScanner(cleaned, identity.uid)
        }

        if (leaks.isNotEmpty()) {
            val leakTypes = leaks.map { it.type.name }.distinct().joinToString("、")
            val detail = leaks.joinToString("\n") { "${it.type.name}: ${it.detail}" }
            failEntry(spec, "脱敏校验未通过：$leakTypes", detail, onItemUpdated)
            return
        }

        verifiedHtmlById[spec.id] = sanitized
        val byteSize = sanitized.toByteArray(Charsets.UTF_8).size
        updateStatus(spec, SampleExportStatus.Success(spec.relativePath, byteSize), onItemUpdated)
    }

    /** 返回是否在本页刚刚补齐完整 `Identity(uid, username)`。 */
    private fun updateDynamicContext(id: SampleEntryId, rawHtml: String): Boolean {
        val wasComplete = discoveredIdentity?.isComplete() == true
        if (!wasComplete) {
            SampleHtmlExtractors.extractIdentity(rawHtml)?.let { extracted ->
                val cur = discoveredIdentity
                discoveredIdentity = Identity(
                    uid = cur?.uid.orEmpty().ifBlank { extracted.uid },
                    username = cur?.username.orEmpty().ifBlank { extracted.username },
                )
            }
        }
        when (id) {
            SampleEntryId.THREAD_LIST_P1_LASTPOST ->
                SampleHtmlExtractors.extractMultiPagePublicTid(rawHtml)?.let { discoveredTid = it }
            SampleEntryId.THREAD_DETAIL_P1 ->
                SampleHtmlExtractors.extractFirstPostAuthorUid(rawHtml)?.let { discoveredAuthorUid = it }
            SampleEntryId.SMS_LIST ->
                discoveredPeerUid = SampleHtmlExtractors.extractFirstSmsPeerUid(rawHtml, discoveredIdentity?.uid)
            else -> Unit
        }
        return !wasComplete && discoveredIdentity?.isComplete() == true
    }

    private fun resolveTargetUrl(id: SampleEntryId, customTid: String?): TargetResolution {
        val activeTid = customTid ?: discoveredTid
        return when (id) {
            SampleEntryId.THREAD_LIST_P1_LASTPOST ->
                TargetResolution.Ready(forumEndpoint("forumdisplay.php", "fid" to "2", "page" to "1"))
            SampleEntryId.THREAD_LIST_P2_LASTPOST ->
                TargetResolution.Ready(forumEndpoint("forumdisplay.php", "fid" to "2", "page" to "2"))
            SampleEntryId.THREAD_LIST_P1_DATELINE ->
                TargetResolution.Ready(forumEndpoint("forumdisplay.php", "fid" to "2", "page" to "1", "orderby" to "dateline"))
            SampleEntryId.THREAD_DETAIL_P1 -> activeTid?.let {
                TargetResolution.Ready(forumEndpoint("viewthread.php", "tid" to it, "page" to "1"))
            } ?: TargetResolution.MissingPrerequisite("未从版块页提取到公开帖子编号，请手动填写 tid 后重试")
            SampleEntryId.THREAD_DETAIL_P2 -> activeTid?.let {
                TargetResolution.Ready(forumEndpoint("viewthread.php", "tid" to it, "page" to "2"))
            } ?: TargetResolution.MissingPrerequisite("未从版块页提取到公开帖子编号，请手动填写 tid 后重试")
            SampleEntryId.THREAD_DETAIL_AUTHOR_ONLY -> when {
                activeTid == null -> TargetResolution.MissingPrerequisite("缺少公开帖子编号，请先抓取版块页或填写 tid")
                discoveredAuthorUid == null -> TargetResolution.MissingPrerequisite("未提取到 1 楼作者编号，请先完成帖子第 1 页")
                else -> TargetResolution.Ready(
                    forumEndpoint("viewthread.php", "tid" to activeTid, "page" to "1", "authorid" to requireNotNull(discoveredAuthorUid)),
                )
            }
            SampleEntryId.SMS_LIST ->
                TargetResolution.Ready(forumEndpoint("pm.php", "filter" to "privatepm"))
            SampleEntryId.SMS_DETAIL -> when {
                discoveredPeerUid != null -> TargetResolution.Ready(
                    forumEndpoint("pm.php", "daterange" to "5", "uid" to requireNotNull(discoveredPeerUid)),
                )
                statusById[SampleEntryId.SMS_LIST] is SampleExportStatus.Success ->
                    TargetResolution.Skip("无短消息对话，已跳过")
                else ->
                    TargetResolution.MissingPrerequisite("前置短消息列表未成功，请先重试短消息列表")
            }
            SampleEntryId.NOTIFY_LIST ->
                TargetResolution.Ready(forumEndpoint("notice.php"))
            SampleEntryId.SEARCH_RESULT ->
                TargetResolution.Ready(forumEndpoint("search.php", "srchfrom" to "86400", "searchsubmit" to "yes"))
            SampleEntryId.USER_PROFILE -> {
                val profileUid = discoveredIdentity?.uid?.takeIf { it.isNotBlank() } ?: discoveredAuthorUid
                profileUid?.let { TargetResolution.Ready(forumEndpoint("space.php", "uid" to it)) }
                    ?: TargetResolution.MissingPrerequisite("未提取到用户编号，请确认已登录后重试")
            }
            SampleEntryId.MY_THREADS ->
                TargetResolution.Ready(forumEndpoint("my.php", "item" to "threads", "page" to "1"))
            SampleEntryId.MY_POSTS ->
                TargetResolution.Ready(forumEndpoint("my.php", "item" to "posts", "page" to "1"))
            SampleEntryId.MY_FAVORITES ->
                TargetResolution.Ready(forumEndpoint("my.php", "item" to "favorites", "type" to "thread", "page" to "1"))
            SampleEntryId.POST_FORM_NEWTHREAD ->
                TargetResolution.Ready(forumEndpoint("post.php", "action" to "newthread", "fid" to "2"))
            SampleEntryId.LOGIN_PAGE ->
                TargetResolution.Ready(forumEndpoint("logging.php", "action" to "login"))
        }
    }

    private fun forumEndpoint(file: String, vararg params: Pair<String, String>): HttpUrl {
        val resolved = requireNotNull(baseUrl.resolve(file)) { "Cannot resolve $file against $baseUrl" }
        if (params.isEmpty()) return resolved
        return resolved.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    private suspend fun writeZipIfAny(includePersonalHistory: Boolean): File? = withContext(ioDispatcher) {
        val eligibleSpecs = SampleExportManifest.entries.filter { spec ->
            !(spec.isPersonalHistory && !includePersonalHistory) && verifiedHtmlById.containsKey(spec.id)
        }
        if (eligibleSpecs.isEmpty()) {
            removeOldArchives(keepName = null)
            return@withContext null
        }
        exportDir.mkdirs()

        val nowMs = clock()
        val stamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(nowMs))
        val targetZip = File(exportDir, "${SampleExportConstants.ZIP_FILE_PREFIX}$stamp${SampleExportConstants.ZIP_FILE_SUFFIX}")
        val tempZip = File(exportDir, "${targetZip.name}.tmp")
        val manifestBytes = buildManifestBytes(nowMs, includePersonalHistory)

        try {
            ZipOutputStream(tempZip.outputStream().buffered()).use { zos ->
                for (spec in eligibleSpecs) {
                    val cleanHtml = verifiedHtmlById[spec.id] ?: continue
                    zos.putNextEntry(ZipEntry(spec.relativePath))
                    zos.write(cleanHtml.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry(SampleExportConstants.MANIFEST_FILE_NAME))
                zos.write(manifestBytes)
                zos.closeEntry()
            }
            if (!tempZip.renameTo(targetZip)) {
                tempZip.copyTo(targetZip, overwrite = true)
                Files.deleteIfExists(tempZip.toPath())
            }
            removeOldArchives(keepName = targetZip.name)
            targetZip
        } catch (e: Throwable) {
            Files.deleteIfExists(tempZip.toPath())
            throw e
        }
    }

    private fun buildManifestBytes(exportedAtEpochMs: Long, includePersonalHistory: Boolean): ByteArray {
        val entries = SampleExportManifest.entries.map { spec ->
            val st = if (spec.isPersonalHistory && !includePersonalHistory) {
                SampleExportStatus.Skipped(PRIVACY_SKIP_REASON)
            } else {
                statusById[spec.id] ?: SampleExportStatus.Pending
            }
            val (label, leaks, bytes, note) = when (st) {
                is SampleExportStatus.Success -> ManifestRow("success", 0, st.byteSize, null)
                is SampleExportStatus.Skipped -> ManifestRow("skipped", 0, 0, st.reason)
                is SampleExportStatus.Failed -> ManifestRow("failed", -1, 0, st.reason)
                SampleExportStatus.Pending, SampleExportStatus.Fetching -> ManifestRow("pending", 0, 0, null)
            }
            ExportManifestEntry(spec.id.name, spec.relativePath, spec.pageType.name, label, leaks, bytes, note)
        }
        val doc = ExportManifestDocument(1, exportedAtEpochMs, sanitized = true, zeroLeaksVerified = true, includePersonalHistory, entries)
        return json.encodeToString(doc).toByteArray(Charsets.UTF_8)
    }

    private fun removeOldArchives(keepName: String?) {
        exportDir.listFiles()
            ?.filter { it.isFile && it.name != keepName && it.name.startsWith(SampleExportConstants.ZIP_FILE_PREFIX) }
            ?.forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun failEntry(
        spec: SampleSpec,
        reason: String,
        detail: String?,
        onItemUpdated: (SampleExportItemState) -> Unit,
    ) {
        verifiedHtmlById.remove(spec.id)
        pendingIdentityRawHtmlById.remove(spec.id)
        updateStatus(spec, SampleExportStatus.Failed(reason, detail), onItemUpdated)
    }

    private fun updateStatus(
        spec: SampleSpec,
        status: SampleExportStatus,
        onItemUpdated: (SampleExportItemState) -> Unit,
    ) {
        statusById[spec.id] = status
        onItemUpdated(SampleExportItemState(spec, status))
    }

    private fun resetStates() {
        for (spec in SampleExportManifest.entries) statusById[spec.id] = SampleExportStatus.Pending
    }

    private fun Identity.isComplete(): Boolean = uid.isNotBlank() && username.isNotBlank()

    private data class ManifestRow(val label: String, val leaks: Int, val bytes: Int, val note: String?)

    private sealed interface TargetResolution {
        data class Ready(val url: HttpUrl) : TargetResolution
        data class Skip(val reason: String) : TargetResolution
        data class MissingPrerequisite(val reason: String) : TargetResolution
    }

    companion object {
        internal const val PRIVACY_SKIP_REASON: String = "已按隐私选项跳过"
        internal const val INCOMPLETE_IDENTITY_REASON: String = "未提取到完整登录身份，拒绝放行"
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US).withZone(ZoneOffset.UTC)
    }
}
