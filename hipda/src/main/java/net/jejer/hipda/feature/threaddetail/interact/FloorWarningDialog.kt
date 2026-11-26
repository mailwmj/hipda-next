package net.jejer.hipda.feature.threaddetail.interact

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.decodeBody
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** `misc.php?action=viewwarning` 警告弹窗解析结果。 */
@Immutable
data class FloorWarningData(val title: String, val records: List<String>, val summary: String?)

/** 警告记录拉取状态。 */
@Immutable
sealed interface FloorWarningLoadState {
    data object Loading : FloorWarningLoadState
    @Immutable data class Loaded(val data: FloorWarningData) : FloorWarningLoadState
    data object Failed : FloorWarningLoadState
}

/** 经 [SafeLinkPolicy.sanitizeHttpUrl] 组装并校验 `misc.php?action=viewwarning&tid={tid}&uid={uid}` 地址。 */
fun buildSafeWarningUrl(forumServer: String, tid: String, uid: String): String? {
    val cleanTid = tid.trim()
    val cleanUid = uid.trim()
    if (!isPositiveNumericId(cleanTid) || !isPositiveNumericId(cleanUid)) return null
    return buildSafeForumEndpointUrl(forumServer, "misc.php?action=viewwarning&tid=$cleanTid&uid=$cleanUid", preferHttps = false)
}

/** 解析 Discuz `misc.php?action=viewwarning` HTML（`h3.float_ctrl`、`div.floatwrap table.list tbody tr` 与 `div.moreconf`）。 */
fun parseWarningDialogHtml(rawHtml: String): FloorWarningData? {
    if (rawHtml.isBlank()) return null
    val effectiveHtml = if (rawHtml.contains("<root", ignoreCase = true)) {
        Jsoup.parse(rawHtml, "", Parser.xmlParser()).select("root").lastOrNull()?.text()?.takeIf { it.isNotBlank() } ?: rawHtml
    } else {
        rawHtml
    }
    val doc = Jsoup.parse(effectiveHtml)
    val titleEl = doc.select("h3.float_ctrl").firstOrNull()
    val title = titleEl?.clone()?.apply { select("a.float_close").remove() }
        ?.text()?.let(DiscuzTextDecoder::decodeDoubleEscapedEntities)?.trim().orEmpty()
    val records = doc.select("div.floatwrap table.list tbody tr")
        .map { DiscuzTextDecoder.decodeDoubleEscapedEntities(it.text()).trim() }
        .filter { it.isNotEmpty() }
    val summary = doc.select("div.moreconf").firstOrNull()
        ?.text()?.let(DiscuzTextDecoder::decodeDoubleEscapedEntities)?.trim()?.ifEmpty { null }
    val hasContainer = titleEl != null || doc.select("div.floatwrap").isNotEmpty() || doc.select("div.moreconf").isNotEmpty()
    if (!hasContainer && title.isEmpty() && records.isEmpty() && summary == null) return null
    return FloorWarningData(title = title, records = records, summary = summary)
}

/** 经项目统一 [SiteAccessGateway] 拉取并解析警告记录（入口严格过 [buildSafeWarningUrl]）。 */
suspend fun fetchFloorWarning(
    siteAccessGateway: SiteAccessGateway,
    forumServer: String,
    tid: String,
    uid: String,
    priority: AccessPriority = AccessPriority.UserBlocking,
): FloorWarningLoadState {
    val safeUrl = buildSafeWarningUrl(forumServer, tid, uid) ?: return FloorWarningLoadState.Failed
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(safeUrl).get().build()
            siteAccessGateway.execute(request = request, priority = priority).use { response ->
                if (!response.isSuccessful) return@withContext FloorWarningLoadState.Failed
                val contentType = response.header("Content-Type") ?: response.body.contentType()?.toString()
                val html = decodeBody(response.body.bytes(), contentType)
                parseWarningDialogHtml(html)?.let(FloorWarningLoadState::Loaded) ?: FloorWarningLoadState.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            FloorWarningLoadState.Failed
        }
    }
}

/** 被警告楼层记录查看弹窗（M7-G3 #7：加载中 [HiLoadingSpinner]，失败 `加载失败`+`重试`，`BackHandler` 优先关闭）。 */
@Composable
fun FloorWarningDialog(
    floor: PostFloor?,
    onLoadWarning: suspend (uid: String) -> FloorWarningLoadState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (floor == null) return
    var retryKey by remember(floor.postId, floor.uid) { mutableIntStateOf(0) }
    var loadState by remember(floor.postId, floor.uid) { mutableStateOf<FloorWarningLoadState>(FloorWarningLoadState.Loading) }
    LaunchedEffect(floor.postId, floor.uid, retryKey) {
        loadState = FloorWarningLoadState.Loading
        loadState = onLoadWarning(floor.uid)
    }

    val cs = MaterialTheme.colorScheme
    val fallbackTitle = stringResource(R.string.thread_detail_warning_dialog_title)
    val resolvedTitle = (loadState as? FloorWarningLoadState.Loaded)?.data?.title?.ifBlank { fallbackTitle } ?: fallbackTitle
    FloorModalDialogScaffold(title = resolvedTitle, onDismiss = onDismiss, modifier = modifier, titleMaxLines = 2) {
        when (val state = loadState) {
            FloorWarningLoadState.Loading -> Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HiLoadingSpinner(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            FloorWarningLoadState.Failed -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.common_error_load_failed), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                Spacer(Modifier.height(14.dp))
                FilledTonalButton({ retryKey++ }, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(88.dp, 48.dp)) {
                    Text(stringResource(R.string.common_action_retry))
                }
            }
            is FloorWarningLoadState.Loaded -> {
                val data = state.data
                if (data.records.isEmpty() && data.summary.isNullOrBlank()) {
                    Text(stringResource(R.string.thread_detail_warning_empty), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        data.records.forEach { record ->
                            Surface(shape = HiShapeTokens.Small, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Text(record, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                        }
                        data.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}
