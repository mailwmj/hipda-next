package net.jejer.hipda.feature.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jejer.hipda.BuildConfig
import net.jejer.hipda.R
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.service.hasNotificationPermission
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
import net.jejer.hipda.ui.shell.ForumMetadata
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

private enum class SettingsSubPage { ManageForums, Blacklist }
private enum class SettingsDialogKind { TailText, TailUrl, ForumServer, CacheLimit, QuietBegin, QuietEnd, ErrorLogs }

/** 纯色设置顶栏（零毛玻璃，`surface -> surfaceContainer` 随滚渐变）。 */
@Composable
internal fun SettingsSolidTopBar(
    title: String,
    scrolled: Boolean,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(if (scrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "SettingsTopBarColor")
    Column(modifier.fillMaxWidth().background(bgColor)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.hiTopSafeBar))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (showBack) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack && onBack != null) {
                val interaction = remember { MutableInteractionSource() }
                IconButton(onBack, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction), interactionSource = interaction) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), tint = cs.onSurface)
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = if (showBack) 4.dp else 0.dp).semantics { heading() })
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

@Composable
internal fun HiExpandableSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = hiExpandVertically(HiMotion.spatialDefault(), expandFrom = Alignment.Top) + hiFadeIn(),
        exit = hiShrinkVertically(HiMotion.exit(HiMotion.spatialDefault()), shrinkTowards = Alignment.Top) + hiFadeOut(),
    ) { content() }
}

/** 5 组设置主目录页（单栏点进二级详情；宽屏/铰链作为分组栏常显并高亮当前组，零毛玻璃）。 */
@Composable
fun SettingsGroupsPane(
    selectedGroup: SettingsGroup,
    isSidebar: Boolean,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    onSelectGroup: (SettingsGroup) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
    highlightSelected: Boolean = isSidebar,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(cs.surface, HiMotion.effectsSlow(), label = "SettingsGroupsBg")
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    Column(modifier.fillMaxSize().background(bgColor)) {
        SettingsSolidTopBar(stringResource(R.string.settings_title), scrolled, showBack, onBack)
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(SettingsGroup.entries, key = { it.name }) { group ->
                val selected = highlightSelected && group == selectedGroup
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = if (highlightSelected) 8.dp else 0.dp, vertical = 2.dp)
                        .clip(if (highlightSelected) HiShapeTokens.Medium else HiShapeTokens.Small)
                        .background(if (selected) cs.secondaryContainer else Color.Transparent)
                        .defaultMinSize(minHeight = 56.dp)
                        .clickable(remember { MutableInteractionSource() }, hiListItemPressIndication()) { onSelectGroup(group) }
                        .semantics { if (highlightSelected) this.selected = selected }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(settingsGroupIcon(group), null, tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text(settingsGroupTitle(group), style = MaterialTheme.typography.titleMedium, color = if (selected) cs.onSecondaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!highlightSelected) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** 5 组设置详情页（含管理版块、黑名单下钻子页；纯色顶栏零毛玻璃；主题切换与旋转均保留滚动位置与未提交文本）。 */
@Composable
fun SettingsDetailPane(
    group: SettingsGroup,
    viewModel: SettingsViewModel,
    onOpenSystemNotificationSettings: () -> Boolean,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    onOpenThread: (ThreadNavArgs) -> Unit,
    blacklistContent: @Composable (onBack: () -> Unit, contentPadding: PaddingValues) -> Unit,
    contentPadding: PaddingValues,
    isDynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    appVersionName: String = BuildConfig.VERSION_NAME,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var subPage by rememberSaveable(group) { mutableStateOf<SettingsSubPage?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }
    when (subPage) {
        SettingsSubPage.ManageForums -> { ManageForumsScreen(uiState.settings.followedForums, viewModel::addFollowedForum, viewModel::removeFollowedForum, viewModel::moveFollowedForum, { subPage = null }, contentPadding, modifier); return }
        SettingsSubPage.Blacklist -> { blacklistContent({ subPage = null }, contentPadding); return }
        null -> Unit
    }

    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(cs.surface, HiMotion.effectsSlow(), label = "SettingsDetailBg")
    val snackbar = LocalPaneSnackbarController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestViewModel by rememberUpdatedState(viewModel)
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var pendingEnableNotificationsAfterSettings by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasNotificationPermission(context)) {
                notificationPermissionDenied = false
                if (pendingEnableNotificationsAfterSettings) {
                    pendingEnableNotificationsAfterSettings = false
                    latestViewModel.setNotificationsEnabled(true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                notificationPermissionDenied = false
                pendingEnableNotificationsAfterSettings = false
                viewModel.setNotificationsEnabled(true)
            } else {
                notificationPermissionDenied = true
                pendingEnableNotificationsAfterSettings = false
                viewModel.setNotificationsEnabled(false)
            }
        }
    } else {
        null
    }
    val onToggleNotifications: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            notificationPermissionDenied = false
            pendingEnableNotificationsAfterSettings = false
            viewModel.setNotificationsEnabled(false)
        } else if (hasNotificationPermission(context)) {
            notificationPermissionDenied = false
            pendingEnableNotificationsAfterSettings = false
            viewModel.setNotificationsEnabled(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationPermissionLauncher != null) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionDenied = true
            pendingEnableNotificationsAfterSettings = false
            viewModel.setNotificationsEnabled(false)
        }
    }
    val onOpenPermissionSettings: () -> Unit = {
        if (onOpenSystemNotificationSettings()) {
            pendingEnableNotificationsAfterSettings = true
        }
    }
    var activeDialog by rememberSaveable(group) { mutableStateOf<SettingsDialogKind?>(null) }
    var activeAboutDocument by rememberSaveable(group) { mutableStateOf<AboutAssetDocument?>(null) }
    val groupListStates = SettingsGroup.entries.associateWith { g -> key(g) { rememberLazyListState() } }
    val activeListState = groupListStates.getValue(group)
    val scrolled by remember(activeListState) { derivedStateOf { activeListState.firstVisibleItemIndex > 0 || activeListState.firstVisibleItemScrollOffset > 0 } }

    val clearedFmt = stringResource(R.string.settings_cache_cleared)
    val copiedMsg = stringResource(R.string.settings_copied_content)
    val logsEmptyMsg = stringResource(R.string.settings_error_logs_empty)
    val releaseTitle = stringResource(R.string.settings_release_thread_title)
    val logsTitle = stringResource(R.string.settings_error_logs_title)
    fun showContentSnackbar(msg: String) { scope.launch { snackbar.show(msg, PaneSnackbarTarget.ContentArea) } }
    fun withLogs(onNonEmpty: (String) -> Unit) = viewModel.loadErrorLogs { safe -> if (safe.isBlank()) showContentSnackbar(logsEmptyMsg) else onNonEmpty(safe) }

    Column(modifier.fillMaxSize().background(bgColor)) {
        SettingsSolidTopBar(settingsGroupTitle(group), scrolled, showBack, onBack)
        AnimatedContent(
            targetState = group,
            transitionSpec = { (hiFadeIn() togetherWith hiFadeOut()).using(null) },
            label = "SettingsGroupDetail",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { targetGroup ->
            LazyColumn(
                state = groupListStates.getValue(targetGroup),
                contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when (targetGroup) {
                    SettingsGroup.Appearance -> appearanceItems(uiState.settings, isDynamicColorSupported, viewModel)
                    SettingsGroup.ForumAndPost -> forumAndPostItems(
                        uiState.settings, viewModel,
                        onOpenManageForums = { subPage = SettingsSubPage.ManageForums },
                        onOpenBlacklist = { subPage = SettingsSubPage.Blacklist },
                        onEditTailText = { activeDialog = SettingsDialogKind.TailText },
                        onEditTailUrl = { activeDialog = SettingsDialogKind.TailUrl },
                    )
                    SettingsGroup.Notification -> notificationItems(
                        settings = uiState.settings,
                        vm = viewModel,
                        onToggleNotifications = onToggleNotifications,
                        notificationPermissionDenied = notificationPermissionDenied,
                        onOpenPermissionSettings = onOpenPermissionSettings,
                        onOpenSystemNotificationSettings = onOpenSystemNotificationSettings,
                        onPickBegin = { activeDialog = SettingsDialogKind.QuietBegin },
                        onPickEnd = { activeDialog = SettingsDialogKind.QuietEnd },
                    )
                    SettingsGroup.NetworkAndCache -> networkAndCacheItems(
                        uiState, { activeDialog = SettingsDialogKind.ForumServer }, { activeDialog = SettingsDialogKind.CacheLimit },
                        onClearImageCache = { viewModel.clearImageCache { showContentSnackbar(String.format(clearedFmt, it)) } },
                        onClearWebCache = { viewModel.clearWebCache { showContentSnackbar(String.format(clearedFmt, it)) } },
                    )
                    SettingsGroup.About -> aboutItems(
                        appVersionName = appVersionName,
                        onOpenReleaseThread = { onOpenThread(ThreadNavArgs(tid = CLIENT_RELEASE_TID, title = releaseTitle)) },
                        onOpenDocument = { activeAboutDocument = it },
                        onViewLogs = { activeDialog = SettingsDialogKind.ErrorLogs; viewModel.loadErrorLogs(null) },
                        onCopyLogs = { withLogs { safe -> runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(logsTitle, safe)) }; showContentSnackbar(copiedMsg) } },
                        onShareLogs = { withLogs { safe -> runCatching { val s = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, logsTitle); putExtra(Intent.EXTRA_TEXT, safe) }; context.startActivity(Intent.createChooser(s, logsTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } },
                    )
                }
            }
        }
    }
    SettingsActiveDialogHost(activeDialog, uiState, viewModel) { activeDialog = null }
    activeAboutDocument?.let { doc ->
        AboutAssetDocumentDialog(document = doc, onDismiss = { activeAboutDocument = null })
    }
}

private fun LazyListScope.appearanceItems(settings: AppSettings, isDynamicColorSupported: Boolean, vm: SettingsViewModel) {
    item("theme_mode") {
        SegmentedSettingBlock(
            stringResource(R.string.settings_theme_title),
            listOf(ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system), ThemeMode.LIGHT to stringResource(R.string.settings_theme_light), ThemeMode.DARK to stringResource(R.string.settings_theme_dark)),
            settings.themeMode, vm::setThemeMode,
        )
    }
    item("pure_black") { SwitchSettingRow(stringResource(R.string.settings_pure_black_title), stringResource(R.string.settings_pure_black_summary), settings.pureBlack, vm::setPureBlack) }
    item("theme_color") { ThemeSeedSelectorBlock(settings.themeSeed, isDynamicColorSupported, vm::setThemeSeed) }
    item("font_size") { FontSizeSettingBlock(settings.fontStep, settings.lineSpacing, vm::setFontStep) }
    item("line_spacing") {
        SegmentedSettingBlock(
            stringResource(R.string.settings_line_spacing_title),
            listOf(LineSpacing.COMPACT to stringResource(R.string.settings_line_spacing_compact), LineSpacing.STANDARD to stringResource(R.string.settings_line_spacing_standard), LineSpacing.LOOSE to stringResource(R.string.settings_line_spacing_loose)),
            settings.lineSpacing, vm::setLineSpacing,
        )
    }
}

private fun LazyListScope.forumAndPostItems(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpenManageForums: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onEditTailText: () -> Unit,
    onEditTailUrl: () -> Unit,
) {
    item("manage_forums") {
        val summary = remember(settings.followedForums) { ForumMetadataRegistry.resolveAll(settings.followedForums).joinToString("、") { it.fullName } }
        ClickableSettingRow(stringResource(R.string.settings_manage_forums_title), summary.ifBlank { null }, showChevron = true, onClick = onOpenManageForums)
    }
    item("show_sticky") { SwitchSettingRow(stringResource(R.string.settings_show_sticky_title), null, settings.showStickyThreads, vm::setShowStickyThreads) }
    item("tail") {
        Column(Modifier.fillMaxWidth()) {
            SwitchSettingRow(stringResource(R.string.settings_tail_title), null, settings.tailEnabled, vm::setTailEnabled)
            HiExpandableSection(settings.tailEnabled) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                    ClickableSettingRow(stringResource(R.string.settings_tail_text_title), settings.tailText.ifBlank { null }, onClick = onEditTailText)
                    ClickableSettingRow(stringResource(R.string.settings_tail_url_title), settings.tailUrl.ifBlank { null }, onClick = onEditTailUrl)
                }
            }
        }
    }
    item("blacklist") { ClickableSettingRow(stringResource(R.string.settings_blacklist_title), null, showChevron = true, onClick = onOpenBlacklist) }
}

private fun LazyListScope.notificationItems(
    settings: AppSettings,
    vm: SettingsViewModel,
    onToggleNotifications: (Boolean) -> Unit,
    notificationPermissionDenied: Boolean,
    onOpenPermissionSettings: () -> Unit,
    onOpenSystemNotificationSettings: () -> Boolean,
    onPickBegin: () -> Unit,
    onPickEnd: () -> Unit,
) {
    item("notifications_enabled") {
        Column(Modifier.fillMaxWidth()) {
            SwitchSettingRow(stringResource(R.string.settings_notifications_title), null, settings.notificationsEnabled, onToggleNotifications)
            HiExpandableSection(notificationPermissionDenied) {
                NotificationPermissionDeniedBanner(onOpenSettings = onOpenPermissionSettings)
            }
        }
    }
    item("notification_sound") { ClickableSettingRow(stringResource(R.string.settings_notification_sound_title), stringResource(R.string.settings_notification_sound_summary), showChevron = true) { onOpenSystemNotificationSettings() } }
    item("quiet_hours") {
        Column(Modifier.fillMaxWidth()) {
            SwitchSettingRow(stringResource(R.string.settings_quiet_hours_title), null, settings.silentModeEnabled, vm::setSilentModeEnabled)
            HiExpandableSection(settings.silentModeEnabled) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                    ClickableSettingRow(stringResource(R.string.settings_quiet_hours_begin), formatMinutesOfDay(settings.silentBeginMinutes), onClick = onPickBegin)
                    ClickableSettingRow(stringResource(R.string.settings_quiet_hours_end), formatMinutesOfDay(settings.silentEndMinutes), onClick = onPickEnd)
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionDeniedBanner(onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_notification_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenSettings,
                interactionSource = interaction,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).hiPressScale(interaction),
            ) {
                Text(stringResource(R.string.settings_action_open_system_settings))
            }
        }
    }
}

private fun LazyListScope.networkAndCacheItems(
    uiState: SettingsUiState,
    onEditForumServer: () -> Unit,
    onPickCacheLimit: () -> Unit,
    onClearImageCache: () -> Unit,
    onClearWebCache: () -> Unit,
) {
    item("forum_server") { ClickableSettingRow(stringResource(R.string.settings_forum_server_title), uiState.settings.forumServer, onClick = onEditForumServer) }
    item("image_cache_limit") { ClickableSettingRow(stringResource(R.string.settings_image_cache_limit_title), formatCacheLimitMb(uiState.settings.imageCacheLimitMb), onClick = onPickCacheLimit) }
    cacheClearRow("clear_image_cache", R.string.settings_clear_image_cache_title, uiState.isClearingImageCache, { stringResource(R.string.settings_cache_used, formatCacheSizeBytes(uiState.imageCacheSizeBytes)) }, onClearImageCache)
    cacheClearRow("clear_web_cache", R.string.settings_clear_web_cache_title, uiState.isClearingWebCache, { null }, onClearWebCache)
}

private fun LazyListScope.cacheClearRow(key: String, titleRes: Int, clearing: Boolean, idleSummary: @Composable () -> String?, onClick: () -> Unit) {
    item(key) {
        ClickableSettingRow(
            title = stringResource(titleRes),
            summary = if (clearing) stringResource(R.string.settings_cache_clearing) else idleSummary(),
            enabled = !clearing,
            trailing = if (clearing) ({ HiLoadingSpinner(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
            onClick = onClick,
        )
    }
}

private fun LazyListScope.aboutItems(
    appVersionName: String,
    onOpenReleaseThread: () -> Unit,
    onOpenDocument: (AboutAssetDocument) -> Unit,
    onViewLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
) {
    item("version") { ClickableSettingRow(stringResource(R.string.settings_version_title), appVersionName, enabled = false, onClick = {}) }
    item("release_thread") { ClickableSettingRow(stringResource(R.string.settings_release_thread_title), null, showChevron = true, onClick = onOpenReleaseThread) }
    item("release_notes") { ClickableSettingRow(stringResource(R.string.settings_about_release_notes), null, showChevron = true) { onOpenDocument(AboutAssetDocument.ReleaseNotes) } }
    item("donors") { ClickableSettingRow(stringResource(R.string.settings_about_donors), null, showChevron = true) { onOpenDocument(AboutAssetDocument.Donors) } }
    item("license") { ClickableSettingRow(stringResource(R.string.settings_about_license), null, showChevron = true) { onOpenDocument(AboutAssetDocument.License) } }
    item("error_logs") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_error_logs_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.settings_error_logs_view to onViewLogs, R.string.settings_error_logs_copy to onCopyLogs, R.string.settings_error_logs_share to onShareLogs).forEach { (resId, action) ->
                    val interaction = remember { MutableInteractionSource() }
                    FilledTonalButton(action, Modifier.defaultMinSize(64.dp, 48.dp).hiPressScale(interaction), shape = HiShapeTokens.Small, interactionSource = interaction) { Text(stringResource(resId)) }
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedSettingBlock(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, (value, label) ->
                SegmentedButton(value == selected, { onSelect(value) }, SegmentedButtonDefaults.itemShape(idx, options.size), Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ThemeSeedSelectorBlock(selectedSeed: String, isDynamicColorSupported: Boolean, onSelectSeed: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_theme_color_title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 16.dp))
        if (isDynamicColorSupported) {
            val interaction = remember { MutableInteractionSource() }
            FilterChip(
                selected = selectedSeed.equals(AppSettings.THEME_SEED_WALLPAPER, ignoreCase = true),
                onClick = { onSelectSeed(AppSettings.THEME_SEED_WALLPAPER) },
                label = { Text(stringResource(R.string.settings_theme_color_wallpaper)) },
                interactionSource = interaction,
                modifier = Modifier.padding(horizontal = 16.dp).defaultMinSize(minHeight = 48.dp).hiPressScale(interaction),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(HiColorPresets.ALL, key = { it.id }) { preset ->
                val isSelected = selectedSeed == preset.id
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier.size(48.dp).clip(HiShapeTokens.Full)
                        .clickable(interaction, hiListItemPressIndication()) { onSelectSeed(preset.id) }
                        .hiPressScale(interaction)
                        .semantics { contentDescription = preset.label; selected = isSelected; role = Role.RadioButton },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(34.dp).clip(HiShapeTokens.Full).background(preset.color)
                            .then(if (isSelected) Modifier.border(2.dp, cs.onSurface, HiShapeTokens.Full) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (isSelected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FontSizeSettingBlock(fontStep: Int, lineSpacing: LineSpacing, onSelectStep: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val stepEnum = remember(fontStep) { HiThemeSettingsAdapter.resolveFontSizeStep(fontStep) }
    val linePreset = when (lineSpacing) { LineSpacing.COMPACT -> HiLineHeightPreset.COMPACT; LineSpacing.STANDARD -> HiLineHeightPreset.STANDARD; LineSpacing.LOOSE -> HiLineHeightPreset.RELAXED }
    val previewTokens = remember(stepEnum, linePreset) { hiTypographyTokens(stepEnum, linePreset) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_font_size_title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
            Text("${stepEnum.baseSp.toInt()}sp", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        Surface(Modifier.fillMaxWidth(), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow, border = BorderStroke(0.5.dp, cs.outlineVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_font_preview_title), style = previewTokens.threadTitle, color = cs.onSurface)
                Text(stringResource(R.string.settings_font_preview_body), style = previewTokens.postBody, color = cs.onSurfaceVariant)
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HiFontSizeStep.entries.forEachIndexed { idx, step ->
                SegmentedButton(idx == fontStep.coerceIn(AppSettings.MIN_FONT_STEP, AppSettings.MAX_FONT_STEP), { onSelectStep(idx) }, SegmentedButtonDefaults.itemShape(idx, HiFontSizeStep.entries.size), Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text("${step.baseSp.toInt()}", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SwitchSettingRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    ClickableSettingRow(title, summary, role = Role.Switch, trailing = { Switch(checked = checked, onCheckedChange = null) }) { onCheckedChange(!checked) }

@Composable
private fun ClickableSettingRow(
    title: String,
    summary: String?,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    role: Role? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .then(if (enabled) Modifier.clickable(remember { MutableInteractionSource() }, hiListItemPressIndication(), role = role, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            showChevron -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** 管理版块页：增删与排序关注版块，实时写入 [SettingsRepository]（零毛玻璃）。 */
@Composable
fun ManageForumsScreen(
    followedForumIds: List<Int>,
    onAddForum: (Int) -> Unit,
    onRemoveForum: (Int) -> Unit,
    onMoveForum: (Int, Int) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val followed = remember(followedForumIds) { ForumMetadataRegistry.resolveAll(followedForumIds) }
    val available = remember(followedForumIds) { followedForumIds.toSet().let { set -> ForumMetadataRegistry.BuiltInForums.filterNot { it.fid in set } } }

    Column(modifier.fillMaxSize().background(cs.surface)) {
        SettingsSolidTopBar(stringResource(R.string.settings_manage_forums_title), scrolled, showBack = true, onBack = onBack)
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            item("hdr_f") { Text(stringResource(R.string.settings_manage_forums_followed), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }) }
            itemsIndexed(followed, key = { _, f -> "f_${f.fid}" }) { idx, forum ->
                ForumRow(forum, Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                    IconButton({ onMoveForum(idx, idx - 1) }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = idx > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "${stringResource(R.string.settings_manage_forums_move_up)} ${forum.fullName}") }
                    IconButton({ onMoveForum(idx, idx + 1) }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = idx < followed.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "${stringResource(R.string.settings_manage_forums_move_down)} ${forum.fullName}") }
                    IconButton({ onRemoveForum(forum.fid) }, Modifier.defaultMinSize(48.dp, 48.dp)) { Icon(Icons.Rounded.Close, "${stringResource(R.string.settings_manage_forums_remove)} ${forum.fullName}") }
                }
            }
            if (available.isNotEmpty()) {
                item("hdr_a") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
                    Text(stringResource(R.string.settings_manage_forums_available), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
                }
                items(available, key = { "a_${it.fid}" }) { forum ->
                    ForumRow(forum, Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                        IconButton({ onAddForum(forum.fid) }, Modifier.defaultMinSize(48.dp, 48.dp)) { Icon(Icons.Rounded.Add, "${stringResource(R.string.settings_manage_forums_add)} ${forum.fullName}", tint = cs.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumRow(forum: ForumMetadata, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerHigh), Alignment.Center) {
            if (forum.icon != null) Icon(forum.icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            else Text(forum.badgeMonogram, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Text(forum.fullName, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsActiveDialogHost(dialog: SettingsDialogKind?, uiState: SettingsUiState, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val urlErr = stringResource(R.string.settings_url_invalid)
    when (dialog) {
        SettingsDialogKind.TailText -> TextInputDialog(stringResource(R.string.settings_tail_text_title), uiState.settings.tailText, { null }, onDismiss) { vm.setTailText(it); onDismiss() }
        SettingsDialogKind.TailUrl -> TextInputDialog(stringResource(R.string.settings_tail_url_title), uiState.settings.tailUrl, { if (SettingsUrlValidator.validateTailUrl(it) == null) urlErr else null }, onDismiss) { if (vm.setTailUrl(it)) onDismiss() }
        SettingsDialogKind.ForumServer -> TextInputDialog(stringResource(R.string.settings_forum_server_title), uiState.settings.forumServer, { if (SettingsUrlValidator.validateForumServer(it) == null) urlErr else null }, onDismiss) { if (vm.setForumServer(it)) onDismiss() }
        SettingsDialogKind.CacheLimit -> AlertDialog(
            onDismissRequest = onDismiss, shape = HiShapeTokens.Large,
            title = { Text(stringResource(R.string.settings_image_cache_limit_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    IMAGE_CACHE_LIMIT_OPTIONS_MB.forEach { mb ->
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.RadioButton) { vm.setImageCacheLimitMb(mb); onDismiss() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(mb == uiState.settings.imageCacheLimitMb, onClick = null); Text(formatCacheLimitMb(mb), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onDismiss, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_close)) } },
        )
        SettingsDialogKind.QuietBegin, SettingsDialogKind.QuietEnd -> {
            val isBegin = dialog == SettingsDialogKind.QuietBegin
            val mins = Math.floorMod(if (isBegin) uiState.settings.silentBeginMinutes else uiState.settings.silentEndMinutes, 24 * 60)
            val state = rememberTimePickerState(mins / 60, mins % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = onDismiss, shape = HiShapeTokens.Large,
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 280.dp, max = 480.dp),
                title = { Text(stringResource(if (isBegin) R.string.settings_quiet_hours_begin else R.string.settings_quiet_hours_end)) },
                text = { TimeInput(state) },
                dismissButton = { TextButton(onDismiss, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_cancel)) } },
                confirmButton = { TextButton({ val m = state.hour * 60 + state.minute; if (isBegin) vm.setSilentBeginMinutes(m) else vm.setSilentEndMinutes(m); onDismiss() }, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_confirm)) } },
            )
        }
        SettingsDialogKind.ErrorLogs -> AlertDialog(
            onDismissRequest = onDismiss, shape = HiShapeTokens.Large,
            title = { Text(stringResource(R.string.settings_error_logs_title)) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    val logs = uiState.errorLogsText.orEmpty()
                    when {
                        uiState.isLoadingLogs -> HiLoadingSpinner(Modifier.align(Alignment.Center).size(24.dp))
                        logs.isBlank() -> Text(stringResource(R.string.settings_error_logs_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                        else -> Text(logs, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                    }
                }
            },
            confirmButton = { TextButton(onDismiss, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_close)) } },
        )
        null -> Unit
    }
}

@Composable
private fun TextInputDialog(title: String, initialValue: String, validator: (String) -> String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable(title, initialValue) { mutableStateOf(initialValue) }
    var err by rememberSaveable(title) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = HiShapeTokens.Large,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 280.dp, max = 480.dp),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it; err = null }, singleLine = true, isError = err != null,
                supportingText = err?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = { TextButton(onDismiss, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_cancel)) } },
        confirmButton = { TextButton({ val e = validator(text); if (e != null) err = e else onConfirm(text) }, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.settings_action_confirm)) } },
    )
}

@Composable
internal fun settingsGroupTitle(group: SettingsGroup): String = stringResource(
    when (group) {
        SettingsGroup.Appearance -> R.string.settings_group_appearance
        SettingsGroup.ForumAndPost -> R.string.settings_group_forum_post
        SettingsGroup.Notification -> R.string.settings_group_notification
        SettingsGroup.NetworkAndCache -> R.string.settings_group_network_cache
        SettingsGroup.About -> R.string.settings_group_about
    },
)

private fun settingsGroupIcon(group: SettingsGroup): ImageVector = when (group) {
    SettingsGroup.Appearance -> Icons.Rounded.Settings
    SettingsGroup.ForumAndPost -> Icons.AutoMirrored.Rounded.List
    SettingsGroup.Notification -> Icons.Rounded.Notifications
    SettingsGroup.NetworkAndCache -> Icons.Rounded.Build
    SettingsGroup.About -> Icons.Rounded.Info
}
