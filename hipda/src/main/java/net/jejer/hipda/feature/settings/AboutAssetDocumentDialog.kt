package net.jejer.hipda.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

/** 关于页与版本升级首启提示展示的内置 `assets/` 文本文档。 */
enum class AboutAssetDocument(
    val assetFileName: String,
    @param:StringRes val titleRes: Int,
) {
    ReleaseNotes("release-notes.txt", R.string.settings_about_release_notes),
    Donors("donors.txt", R.string.settings_about_donors),
    License("license.txt", R.string.settings_about_license),
}

private sealed interface AssetDocumentLoadState {
    data object Loading : AssetDocumentLoadState
    data class Loaded(val text: String) : AssetDocumentLoadState
    data object Failed : AssetDocumentLoadState
}

/** 在 [Dispatchers.IO] 异步读取 `assets/` 文本；读取失败或为空时安全返回 `null`，绝不崩溃。 */
suspend fun loadAboutAssetText(
    context: Context,
    assetFileName: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): String? = withContext(ioDispatcher) {
    runCatching {
        context.assets.open(assetFileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** 可滚动 `assets/` 长文本文档弹窗（供设置「关于」三项文档与版本变化后首次启动更新记录复用，零毛玻璃）。 */
@Composable
fun AboutAssetDocumentDialog(
    document: AboutAssetDocument,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    assetLoader: (suspend (Context, String) -> String?)? = null,
) {
    val context = LocalContext.current
    var loadState by remember(document) { mutableStateOf<AssetDocumentLoadState>(AssetDocumentLoadState.Loading) }

    LaunchedEffect(document, context, assetLoader) {
        loadState = AssetDocumentLoadState.Loading
        val text = try {
            if (assetLoader != null) {
                assetLoader(context, document.assetFileName)
            } else {
                loadAboutAssetText(context, document.assetFileName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        loadState = if (text.isNullOrBlank()) AssetDocumentLoadState.Failed else AssetDocumentLoadState.Loaded(text)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiShapeTokens.Large,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier.padding(horizontal = 24.dp).widthIn(min = 280.dp, max = 520.dp),
        title = { Text(stringResource(document.titleRes)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                when (val cur = loadState) {
                    AssetDocumentLoadState.Loading -> HiLoadingSpinner(Modifier.align(Alignment.Center).size(24.dp))
                    AssetDocumentLoadState.Failed -> Text(
                        text = stringResource(R.string.common_error_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    is AssetDocumentLoadState.Loaded -> Text(
                        text = cur.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                Text(stringResource(R.string.settings_action_close))
            }
        },
    )
}
