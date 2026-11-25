package net.jejer.hipda.debug.sampleexport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.state.HiErrorState
import net.jejer.hipda.ui.designsystem.state.HiStateAction

@Composable
fun SampleExportScreen(
    state: SampleExportUiState,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onSyncCookies: () -> Unit,
    onIncludePersonalHistoryChange: (Boolean) -> Unit,
    onCustomTidChange: (String) -> Unit,
    onStartExport: () -> Unit,
    onShareZip: () -> Unit,
    onRetryItem: (SampleEntryId) -> Unit,
    onConsumeSnackbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackText = state.feedbackMessageRes?.let { stringResource(it) }

    LaunchedEffect(state.feedbackMessageRes) {
        val text = feedbackText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onConsumeSnackbar()
    }

    val successCount = state.items.count { it.status is SampleExportStatus.Success }
    val skippedCount = state.items.count { it.status is SampleExportStatus.Skipped }
    val failedCount = state.items.count { it.status is SampleExportStatus.Failed }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SampleExportTopBar(onBack = onBack)
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "login_card") {
                        LoginStatusCard(state.isLoggedIn, onOpenLogin, onSyncCookies)
                    }
                    item(key = "privacy_card") {
                        PrivacyWarningCard(state.includePersonalHistory, !state.isRunning, onIncludePersonalHistoryChange)
                    }
                    item(key = "controls_card") {
                        ExportControlsCard(
                            customTidInput = state.customTidInput,
                            customTidError = state.customTidError,
                            isRunning = state.isRunning,
                            exportedZipName = state.exportedZipFile?.name,
                            onCustomTidChange = onCustomTidChange,
                            onStartExport = onStartExport,
                            onShareZip = onShareZip,
                        )
                    }
                    if (state.challengeState !is ChallengeState.Idle) {
                        item(key = "challenge_status") {
                            ChallengeStatusView(
                                state = state.challengeState,
                                onRetry = { onStartExport() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(key = "list_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.sample_export_list_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.sample_export_summary, successCount, state.items.size, skippedCount, failedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(items = state.items, key = { _, item -> item.spec.id }) { index, item ->
                        SampleManifestRow(index = index + 1, item = item, onRetry = { onRetryItem(item.spec.id) })
                    }
                }
            }

            HiSnackbarHost(
                hostState = snackbarHostState,
                bottomClearance = 16.dp,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SampleExportTopBar(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sample_export_back_cd))
            }
            Text(
                text = stringResource(R.string.sample_export_title),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = stringResource(R.string.sample_export_readonly_badge),
                containerColor = cs.secondaryContainer,
                contentColor = cs.onSecondaryContainer,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun LoginStatusCard(
    isLoggedIn: Boolean,
    onOpenLogin: () -> Unit,
    onSyncCookies: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val loginInteraction = remember { MutableInteractionSource() }
    val syncInteraction = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sample_export_login_section), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                StatusBadge(
                    text = stringResource(if (isLoggedIn) R.string.sample_export_login_status_logged_in else R.string.sample_export_login_status_logged_out),
                    containerColor = if (isLoggedIn) cs.primaryContainer else cs.errorContainer,
                    contentColor = if (isLoggedIn) cs.onPrimaryContainer else cs.onErrorContainer,
                )
            }
            Text(stringResource(R.string.sample_export_login_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onOpenLogin,
                    shape = HiShapeTokens.Small,
                    interactionSource = loginInteraction,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).hiPressScale(loginInteraction),
                ) { Text(stringResource(R.string.sample_export_action_open_login)) }
                OutlinedButton(
                    onClick = onSyncCookies,
                    shape = HiShapeTokens.Small,
                    interactionSource = syncInteraction,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).hiPressScale(syncInteraction),
                ) { Text(stringResource(R.string.sample_export_action_sync_cookies)) }
            }
        }
    }
}

@Composable
private fun PrivacyWarningCard(
    includePersonalHistory: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.sample_export_privacy_title), style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer)
            Text(stringResource(R.string.sample_export_privacy_body), style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .toggleable(value = includePersonalHistory, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sample_export_privacy_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onTertiaryContainer,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Switch(checked = includePersonalHistory, onCheckedChange = null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun ExportControlsCard(
    customTidInput: String,
    customTidError: Boolean,
    isRunning: Boolean,
    exportedZipName: String?,
    onCustomTidChange: (String) -> Unit,
    onStartExport: () -> Unit,
    onShareZip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val startInteraction = remember { MutableInteractionSource() }
    val shareInteraction = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = customTidInput,
                onValueChange = onCustomTidChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning,
                singleLine = true,
                isError = customTidError,
                label = { Text(stringResource(R.string.sample_export_tid_label)) },
                placeholder = { Text(stringResource(R.string.sample_export_tid_placeholder)) },
                supportingText = if (customTidError) {
                    { Text(stringResource(R.string.sample_export_tid_invalid)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = HiShapeTokens.Small,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartExport,
                    enabled = !isRunning && !customTidError,
                    shape = HiShapeTokens.Small,
                    interactionSource = startInteraction,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).hiPressScale(startInteraction),
                ) {
                    Text(stringResource(if (isRunning) R.string.sample_export_action_running else R.string.sample_export_action_start))
                }
                FilledTonalButton(
                    onClick = onShareZip,
                    enabled = exportedZipName != null && !isRunning,
                    shape = HiShapeTokens.Small,
                    interactionSource = shareInteraction,
                    modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp).hiPressScale(shareInteraction),
                ) { Text(stringResource(R.string.sample_export_action_share)) }
            }
            if (exportedZipName != null) {
                Text(
                    text = stringResource(R.string.sample_export_zip_ready, exportedZipName),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SampleManifestRow(
    index: Int,
    item: SampleExportItemState,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (statusLabelRes, badgeBg, badgeFg) = when (item.status) {
        SampleExportStatus.Pending -> Triple(R.string.sample_export_status_pending, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        SampleExportStatus.Fetching -> Triple(R.string.sample_export_status_fetching, cs.secondaryContainer, cs.onSecondaryContainer)
        is SampleExportStatus.Success -> Triple(R.string.sample_export_status_success, cs.primaryContainer, cs.onPrimaryContainer)
        is SampleExportStatus.Skipped -> Triple(R.string.sample_export_status_skipped, cs.surfaceContainerHigh, cs.onSurfaceVariant)
        is SampleExportStatus.Failed -> Triple(R.string.sample_export_status_failed, cs.errorContainer, cs.onErrorContainer)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$index. ${stringResource(item.spec.id.titleRes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                StatusBadge(stringResource(statusLabelRes), badgeBg, badgeFg)
            }
            val metaText = when (val status = item.status) {
                is SampleExportStatus.Success -> "${item.spec.relativePath} · ${formatByteSize(status.byteSize)}"
                is SampleExportStatus.Skipped -> "${item.spec.relativePath} · ${status.reason}"
                else -> item.spec.relativePath
            }
            Text(
                text = metaText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.status is SampleExportStatus.Fetching) {
                val barModifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                if (HiTheme.isReducedMotion) LinearProgressIndicator(progress = { 0.45f }, modifier = barModifier)
                else LinearProgressIndicator(modifier = barModifier)
            }
            if (item.status is SampleExportStatus.Failed) {
                HiErrorState(
                    message = item.status.reason,
                    action = HiStateAction(stringResource(R.string.sample_export_action_retry), onRetry),
                    details = item.status.detail,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    HiCappedFontScale {
        Surface(modifier = modifier, shape = HiShapeTokens.Full, color = containerColor) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun formatByteSize(bytes: Int): String =
    if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"
