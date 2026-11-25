package net.jejer.hipda.feature.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.HiThemeSettingsAdapter
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.designsystem.state.HiStateAction
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiSidebarAccount
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout

/** M5 新版应用根容器：驱动实时主题、侧栏/胶囊未读角标、多栏阅读引擎、分区对齐 Snackbar 与可选登录覆盖层插槽。 */
@Composable
fun NextAppRoot(
    settingsFlow: Flow<AppSettings>,
    currentAccountFlow: StateFlow<SavedAccount?>,
    smsUnreadCountFlow: StateFlow<Int>,
    notifyUnreadCountFlow: StateFlow<Int>,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    loginOverlayRenderer: LoginOverlayRenderer? = null,
    hostViewModel: NextAppHostViewModel = viewModel(),
    forumViewModel: ForumViewModel = viewModel(),
    onFallbackLoginClick: (() -> Unit)? = null,
    onPersistPaneSplit: ((isLandscape: Boolean, split: PaneSplit) -> Unit)? = null,
    onPersistSidebarCollapsed: ((isLandscape: Boolean, collapsed: Boolean) -> Unit)? = null,
    secondaryListContent: (@Composable (ShellSidebarEntry, Boolean, (() -> Unit)?, PaddingValues, LazyListState, (ThreadNavArgs) -> Unit) -> Unit)? = null,
) {
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val account by currentAccountFlow.collectAsStateWithLifecycle()
    val smsUnread by smsUnreadCountFlow.collectAsStateWithLifecycle()
    val notifyUnread by notifyUnreadCountFlow.collectAsStateWithLifecycle()

    var savedAppState by rememberSaveable(stateSaver = NextAppStateSaver) { mutableStateOf(hostViewModel.state.value) }
    remember(hostViewModel) { hostViewModel.restoreSavedStateIfFresh(savedAppState); true }
    val appState by hostViewModel.state.collectAsStateWithLifecycle()
    SideEffect { if (savedAppState != appState) savedAppState = appState }
    LaunchedEffect(settings.followedForums) { hostViewModel.syncDefaultForum(settings.followedForums) }

    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val isLandscape = windowDpSize.width >= windowDpSize.height

    var splitPortrait by rememberSaveable { mutableStateOf(settings.paneSplitPortrait) }
    var splitLandscape by rememberSaveable { mutableStateOf(settings.paneSplitLandscape) }
    LaunchedEffect(settings.paneSplitPortrait) { splitPortrait = settings.paneSplitPortrait }
    LaunchedEffect(settings.paneSplitLandscape) { splitLandscape = settings.paneSplitLandscape }
    val persistedSplit = if (isLandscape) splitLandscape else splitPortrait

    var collapsedPortrait by rememberSaveable { mutableStateOf(settings.sidebarCollapsedPortrait) }
    var collapsedLandscape by rememberSaveable { mutableStateOf(settings.sidebarCollapsedLandscape) }
    LaunchedEffect(settings.sidebarCollapsedPortrait) { collapsedPortrait = settings.sidebarCollapsedPortrait }
    LaunchedEffect(settings.sidebarCollapsedLandscape) { collapsedLandscape = settings.sidebarCollapsedLandscape }
    val sidebarCollapsedPref = if (isLandscape) collapsedLandscape else collapsedPortrait

    val themeConfig = remember(settings) { HiThemeSettingsAdapter.toThemeConfig(settings) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarController = remember(snackbarHostState) { MultiPaneSnackbarController(snackbarHostState) }
    val openLoginOrAccount: (LoginOverlayMode) -> Unit = { mode ->
        if (loginOverlayRenderer != null) hostViewModel.openLoginOverlay(mode) else onFallbackLoginClick?.invoke()
    }
    val sidebarState = remember(appState.selectedSidebarEntry, settings.followedForums, smsUnread, notifyUnread, account, sidebarCollapsedPref, appState.isFullScreenReading) {
        HiSidebarState(
            selectedEntry = appState.selectedSidebarEntry,
            followedForumIds = settings.followedForums,
            unreadSmsCount = smsUnread,
            unreadNotifyCount = notifyUnread,
            account = HiSidebarAccount(username = account?.username),
            isCollapsedPref = sidebarCollapsedPref,
            isFullScreen = appState.isFullScreenReading,
        )
    }

    var latestPaneLayout by remember { mutableStateOf(calculateShellPaneLayout(windowDpSize.width.value, windowDpSize.height.value)) }
    var latestMiddleWidthDp by remember { mutableStateOf(latestPaneLayout.middlePaneWidthDp.dp) }
    var latestBottomPaddingDp by remember { mutableStateOf(0.dp) }
    val navBarsBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    HiTheme(themeConfig.themeMode, themeConfig.pureBlack, themeConfig.seed, themeConfig.fontSizeStep, themeConfig.lineHeightPreset) {
        CompositionLocalProvider(LocalHiImageLoader provides imageLoader, LocalPaneSnackbarController provides snackbarController) {
            Box(modifier = modifier.fillMaxSize()) {
                HiAdaptiveShell(
                    selected = appState.selectedDestination,
                    onSelect = hostViewModel::selectDestination,
                    sidebarState = sidebarState,
                    onSelectSidebarEntry = hostViewModel::selectSidebarEntry,
                    onToggleSidebarCollapsed = { next ->
                        if (isLandscape) collapsedLandscape = next else collapsedPortrait = next
                        hostViewModel.persistSidebarCollapsed(isLandscape, next)
                        onPersistSidebarCollapsed?.invoke(isLandscape, next)
                    },
                    onAccountClick = { openLoginOrAccount(if (account != null) LoginOverlayMode.AccountMenu else LoginOverlayMode.Login) },
                    hasDetailContent = appState.hasDetailContent,
                    hideBottomNavigation = appState.isSinglePaneSecondary,
                    messagesUnreadCount = sidebarState.totalUnreadCount,
                    managesGlassSource = { true },
                ) { _, contentPadding ->
                    SideEffect {
                        latestPaneLayout = paneLayout
                        latestBottomPaddingDp = contentPadding.calculateBottomPadding()
                    }
                    MultiPaneReadingContainer(
                        paneLayout = paneLayout,
                        appState = appState,
                        hostViewModel = hostViewModel,
                        contentPadding = contentPadding,
                        persistedPaneSplit = persistedSplit,
                        onPaneSplitChange = { next ->
                            if (isLandscape) splitLandscape = next else splitPortrait = next
                            hostViewModel.persistPaneSplit(isLandscape, next)
                            onPersistPaneSplit?.invoke(isLandscape, next)
                        },
                        shellGlassState = shellGlassState,
                        onMiddlePaneWidthChanged = { latestMiddleWidthDp = it },
                        forumContent = { chromeSpec, padding, listState ->
                            ForumPane(
                                source = appState.activeForumSource,
                                chromeSpec = chromeSpec,
                                selectedTid = appState.selectedThread?.tid,
                                onSelectSource = hostViewModel::selectForumSource,
                                onOpenThread = hostViewModel::openThread,
                                onOpenLogin = { openLoginOrAccount(LoginOverlayMode.Login) },
                                listState = listState,
                                contentPadding = padding,
                                shellContentScope = this,
                                viewModel = forumViewModel,
                            )
                        },
                        detailContent = { args, chromeSpec, padding, listState, onBack ->
                            ThreadDetailPane(
                                args = args,
                                chromeSpec = chromeSpec,
                                onBack = onBack,
                                onOpenLogin = { openLoginOrAccount(LoginOverlayMode.Login) },
                                listState = listState,
                                contentPadding = padding,
                                onOpenUserProfile = hostViewModel::pushUserProfile,
                            )
                        },
                        secondaryListContent = { entry, showBack, onBack, padding, listState ->
                            if (secondaryListContent != null) {
                                secondaryListContent(entry, showBack, onBack, padding, listState, hostViewModel::openThread)
                            } else {
                                DefaultSecondaryEntryPane(
                                    entry = entry,
                                    isSidebar = paneLayout.isSidebar,
                                    showBack = showBack,
                                    onBack = onBack,
                                    selectedSettingsGroup = appState.selectedSettingsGroup,
                                    onSelectMessageEntry = hostViewModel::selectMessageEntry,
                                    onSelectMineEntry = hostViewModel::openMineSubPage,
                                    onSelectSettingsGroup = hostViewModel::selectSettingsGroup,
                                    contentPadding = padding,
                                    listState = listState,
                                )
                            }
                        },
                        mineMenuContent = { padding ->
                            DefaultMineMenuPane(
                                account = account,
                                onAccountClick = { openLoginOrAccount(if (account != null) LoginOverlayMode.AccountMenu else LoginOverlayMode.Login) },
                                onSelectSubEntry = hostViewModel::openMineSubPage,
                                contentPadding = padding,
                            )
                        },
                        settingsDetailContent = { group, padding -> DefaultSettingsDetailPane(group, padding) },
                        userProfileContent = { profile, showBack, onBack, padding -> DefaultUserProfilePane(profile, showBack, onBack, padding) },
                    )
                }

                val bottomPillVisible = !latestPaneLayout.isSidebar && !appState.isFullScreenReading && (!appState.isSinglePaneSecondary || latestPaneLayout.hasDetailPane)
                MultiPaneSnackbarHost(
                    hostState = snackbarHostState,
                    paneLayout = latestPaneLayout,
                    effectiveMiddleWidthDp = latestMiddleWidthDp,
                    isFullScreen = appState.isFullScreenReading,
                    bottomPillVisible = bottomPillVisible,
                    bottomPillClearanceDp = latestBottomPaddingDp,
                    navBarsBottomDp = navBarsBottomDp,
                )

                val overlayMode = appState.loginOverlayMode
                if (overlayMode != null && loginOverlayRenderer != null) {
                    loginOverlayRenderer.Content(overlayMode, hostViewModel::dismissLoginOverlay, hostViewModel::dismissLoginOverlay)
                }
            }
        }
    }
}

@Composable
private fun DefaultSecondaryEntryPane(
    entry: ShellSidebarEntry,
    isSidebar: Boolean,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    selectedSettingsGroup: SettingsGroup,
    onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
    onSelectMineEntry: (ShellSidebarEntry) -> Unit,
    onSelectSettingsGroup: (SettingsGroup) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyListState,
) {
    val cs = MaterialTheme.colorScheme
    val showMessageTabs = !isSidebar && (entry == ShellSidebarEntry.Sms || entry == ShellSidebarEntry.Notify)
    val showMyPostsTabs = !isSidebar && (entry == ShellSidebarEntry.MyThreads || entry == ShellSidebarEntry.MyReplies)
    val titleRes = when {
        showMessageTabs -> R.string.shell_nav_messages
        showMyPostsTabs -> R.string.shell_sidebar_my_threads
        entry == ShellSidebarEntry.Sms -> R.string.shell_sidebar_sms
        entry == ShellSidebarEntry.Notify -> R.string.shell_sidebar_notify
        entry == ShellSidebarEntry.Search -> R.string.shell_sidebar_search
        entry == ShellSidebarEntry.FavoritesAndAttention -> R.string.shell_sidebar_favorites_and_attention
        entry == ShellSidebarEntry.MyThreads -> R.string.shell_sidebar_my_threads
        entry == ShellSidebarEntry.MyReplies -> R.string.shell_sidebar_my_replies
        entry == ShellSidebarEntry.History -> R.string.shell_sidebar_history
        entry == ShellSidebarEntry.Settings -> R.string.shell_sidebar_settings
        else -> R.string.shell_nav_forum
    }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize()) {
            SolidPaneTopBar(stringResource(titleRes), showBack, onBack)
            if (showMessageTabs) {
                SegmentedTabRow(
                    options = listOf(ShellSidebarEntry.Sms to R.string.shell_sidebar_sms, ShellSidebarEntry.Notify to R.string.shell_sidebar_notify),
                    selected = entry,
                    onSelect = onSelectMessageEntry,
                )
            }
            if (showMyPostsTabs) {
                SegmentedTabRow(
                    options = listOf(ShellSidebarEntry.MyThreads to R.string.app_tab_topics, ShellSidebarEntry.MyReplies to R.string.app_tab_replies),
                    selected = entry,
                    onSelect = onSelectMineEntry,
                )
            }
            when (entry) {
                ShellSidebarEntry.Settings -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    items(SettingsGroup.entries, key = { it.name }) { group ->
                        val selected = group == selectedSettingsGroup
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .clip(HiShapeTokens.Medium)
                                .background(if (selected) cs.secondaryContainer else cs.surface)
                                .clickable { onSelectSettingsGroup(group) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(group.labelRes), style = MaterialTheme.typography.titleMedium, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
                        }
                    }
                }
                ShellSidebarEntry.FavoritesAndAttention -> {
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    SegmentedTabRow(listOf(0 to R.string.app_tab_favorites, 1 to R.string.app_tab_attention), selectedTab) { selectedTab = it }
                    Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                        HiEmptyState(stringResource(if (selectedTab == 0) R.string.app_empty_favorites else R.string.app_empty_attention))
                    }
                }
                else -> {
                    val emptyRes = when (entry) {
                        ShellSidebarEntry.Sms -> R.string.common_empty_sms
                        ShellSidebarEntry.Notify -> R.string.common_empty_notify
                        ShellSidebarEntry.Search -> R.string.common_search_hint
                        ShellSidebarEntry.History -> R.string.app_empty_history
                        ShellSidebarEntry.MyThreads -> R.string.app_empty_my_threads
                        ShellSidebarEntry.MyReplies -> R.string.app_empty_my_replies
                        else -> R.string.common_empty_threads
                    }
                    Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) { HiEmptyState(stringResource(emptyRes)) }
                }
            }
        }
    }
}

@Composable
private fun DefaultMineMenuPane(
    account: SavedAccount?,
    onAccountClick: () -> Unit,
    onSelectSubEntry: (ShellSidebarEntry) -> Unit,
    contentPadding: PaddingValues,
) {
    val cs = MaterialTheme.colorScheme
    val entries = listOf(
        ShellSidebarEntry.FavoritesAndAttention to R.string.shell_sidebar_favorites_and_attention,
        ShellSidebarEntry.MyThreads to R.string.shell_sidebar_my_threads,
        ShellSidebarEntry.History to R.string.shell_sidebar_history,
        ShellSidebarEntry.Settings to R.string.shell_sidebar_settings,
    )
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize()) {
            SolidPaneTopBar(stringResource(R.string.shell_sidebar_mine), false, null)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "mine_account") {
                    HiEmptyState(
                        message = account?.username ?: stringResource(R.string.shell_sidebar_mine),
                        action = HiStateAction(stringResource(if (account == null) R.string.common_action_login else R.string.shell_sidebar_account), onAccountClick),
                    )
                }
                items(entries, key = { it.first.toString() }) { (entry, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(HiShapeTokens.Medium)
                            .background(cs.surfaceContainerLow)
                            .clickable { onSelectSubEntry(entry) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium, color = cs.onSurface) }
                }
            }
        }
    }
}

@Composable
private fun DefaultSettingsDetailPane(group: SettingsGroup, contentPadding: PaddingValues) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize()) {
            SolidPaneTopBar(stringResource(group.labelRes), false, null)
            Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                Text(stringResource(group.labelRes), style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DefaultUserProfilePane(
    profile: DetailDrilldownEntry.UserProfile,
    showBack: Boolean,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val cs = MaterialTheme.colorScheme
    var tab by rememberSaveable(profile.uid) { mutableIntStateOf(0) }
    val displayName = profile.username.ifBlank { profile.uid }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize()) {
            SolidPaneTopBar(displayName, showBack, onBack)
            SegmentedTabRow(listOf(0 to R.string.app_tab_profile, 1 to R.string.app_tab_user_threads), tab) { tab = it }
            Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                Text(if (tab == 0) displayName else stringResource(R.string.app_tab_user_threads), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun <T> SegmentedTabRow(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { idx, (value, labelRes) ->
            SegmentedButton(selected = selected == value, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(idx, options.size)) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun SolidPaneTopBar(title: String, showBack: Boolean, onBack: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxWidth().background(cs.surface).padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack && onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_action_back), tint = cs.onSurface)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = if (showBack && onBack != null) 4.dp else 8.dp),
            )
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}
