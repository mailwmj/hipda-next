package net.jejer.hipda.feature.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.HiThemeSettingsAdapter
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.designsystem.state.HiStateAction
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiSidebarAccount
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout

/** M5/M6 新版应用根容器：驱动实时主题、单源设置持久化、宿主级槽位缓存、多栏阅读引擎、分区 Snackbar 与 M6 可选功能插槽。 */
@Composable
fun NextAppRoot(
    settingsFlow: Flow<AppSettings>,
    currentAccountFlow: StateFlow<SavedAccount?>,
    smsUnreadCountFlow: StateFlow<Int>,
    notifyUnreadCountFlow: StateFlow<Int>,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    loginOverlayRenderer: LoginOverlayRenderer? = null,
    messagesPaneRenderer: MessagesPaneRenderer? = null,
    searchMinePaneRenderer: SearchMinePaneRenderer? = null,
    settingsProfilePaneRenderer: SettingsProfilePaneRenderer? = null,
    imageViewerRenderer: ImageViewerRenderer? = null,
    hostViewModel: NextAppHostViewModel = viewModel(),
    forumViewModel: ForumViewModel = viewModel(),
    onFallbackLoginClick: (() -> Unit)? = null,
) {
    // 同步读取 StateFlow 当前值；冷流（如 DataStore 进程重启首帧读盘）在首个真实值到达前不渲染默认分栏，杜绝首帧回跳闪烁（R-E3 B3）
    val initialSettings = remember(settingsFlow) { (settingsFlow as? StateFlow<AppSettings>)?.value }
    val loadedSettings by settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
    val account by currentAccountFlow.collectAsStateWithLifecycle()
    val smsUnread by smsUnreadCountFlow.collectAsStateWithLifecycle()
    val notifyUnread by notifyUnreadCountFlow.collectAsStateWithLifecycle()

    var savedAppState by rememberSaveable(stateSaver = NextAppStateSaver) { mutableStateOf(hostViewModel.state.value) }
    remember(hostViewModel) { hostViewModel.restoreSavedStateIfFresh(savedAppState); true }
    val appState by hostViewModel.state.collectAsStateWithLifecycle()
    SideEffect { if (savedAppState != appState) savedAppState = appState }

    // 提升至 HiAdaptiveShell 的 AnimatedContent 之外，确保跨一级目的地切换保留全部 LazyListState 且同帖平滑移交（R-E3 B2）
    val slotHolder = rememberMultiPaneSlotHolder(hostViewModel)

    val settings = loadedSettings
    if (settings == null) {
        HiTheme { Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) }
        return
    }

    LaunchedEffect(settings.followedForums) { hostViewModel.syncDefaultForum(settings.followedForums) }

    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val isLandscape = windowDpSize.width >= windowDpSize.height
    val persistedSplit = if (isLandscape) settings.paneSplitLandscape else settings.paneSplitPortrait
    val sidebarCollapsedPref = if (isLandscape) settings.sidebarCollapsedLandscape else settings.sidebarCollapsedPortrait

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
    var activeImageViewerArgs by remember { mutableStateOf<ImageViewerLaunchArgs?>(null) }
    val navBarsBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    HiTheme(themeConfig.themeMode, themeConfig.pureBlack, themeConfig.seed, themeConfig.fontSizeStep, themeConfig.lineHeightPreset) {
        CompositionLocalProvider(LocalHiImageLoader provides imageLoader, LocalPaneSnackbarController provides snackbarController) {
            Box(modifier = modifier.fillMaxSize()) {
                HiAdaptiveShell(
                    selected = appState.selectedDestination,
                    onSelect = hostViewModel::selectDestination,
                    sidebarState = sidebarState,
                    onSelectSidebarEntry = hostViewModel::selectSidebarEntry,
                    onToggleSidebarCollapsed = { next -> hostViewModel.persistSidebarCollapsed(isLandscape, next) },
                    onAccountClick = { openLoginOrAccount(if (account != null) LoginOverlayMode.AccountMenu else LoginOverlayMode.Login) },
                    hasDetailContent = appState.hasDetailContent,
                    hideBottomNavigation = appState.isSinglePaneSecondary,
                    messagesUnreadCount = sidebarState.totalUnreadCount,
                    managesGlassSource = { true },
                ) { destination, contentPadding ->
                    if (destination == appState.selectedDestination) {
                        SideEffect {
                            latestPaneLayout = paneLayout
                            latestBottomPaddingDp = contentPadding.calculateBottomPadding()
                        }
                    }
                    MultiPaneReadingContainer(
                        paneLayout = paneLayout,
                        appState = appState,
                        hostViewModel = hostViewModel,
                        contentPadding = contentPadding,
                        persistedPaneSplit = persistedSplit,
                        onPaneSplitChange = { next -> hostViewModel.persistPaneSplit(isLandscape, next) },
                        destination = destination,
                        slotHolder = slotHolder,
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
                                onOpenImageViewer = { launchArgs ->
                                    if (imageViewerRenderer != null) activeImageViewerArgs = launchArgs
                                },
                                onOpenUserProfile = hostViewModel::pushUserProfile,
                            )
                        },
                        secondaryListContent = { entry, showBack, onBack, padding, listState ->
                            val openLogin = { openLoginOrAccount(LoginOverlayMode.Login) }
                            when {
                                (entry == ShellSidebarEntry.Sms || entry == ShellSidebarEntry.Notify) && messagesPaneRenderer != null ->
                                    messagesPaneRenderer.ListContent(
                                        entry, paneLayout.isSidebar, showBack, onBack, appState.selectedSmsConversation?.uid,
                                        hostViewModel::selectSidebarEntry, hostViewModel::openSmsConversation,
                                        hostViewModel::openThread, hostViewModel::pushUserProfile, openLogin, padding, listState,
                                    )
                                entry == ShellSidebarEntry.Search && searchMinePaneRenderer != null ->
                                    searchMinePaneRenderer.SearchContent(
                                        showBack, onBack, hostViewModel::openThread,
                                        { fid -> hostViewModel.selectForumSource(ForumListSource.Forum(fid)) },
                                        hostViewModel::pushUserProfile, openLogin, padding, listState,
                                    )
                                entry.isMineGroupChild && searchMinePaneRenderer != null ->
                                    searchMinePaneRenderer.MineSubPageContent(
                                        entry, paneLayout.isSidebar, showBack, onBack,
                                        hostViewModel::selectSidebarEntry, hostViewModel::openThread, openLogin, padding, listState,
                                    )
                                entry == ShellSidebarEntry.Settings && settingsProfilePaneRenderer != null ->
                                    settingsProfilePaneRenderer.SettingsGroupsContent(
                                        appState.selectedSettingsGroup, paneLayout.isSidebar, showBack, onBack,
                                        { group -> hostViewModel.selectSettingsGroup(group, openDetailInSinglePane = !paneLayout.hasDetailPane) },
                                        padding, listState,
                                    )
                                else -> UnbuiltEntryPlaceholder(entryPlaceholderText(entry), showBack, onBack, padding)
                            }
                        },
                        mineMenuContent = { padding ->
                            val onAccountAction = { openLoginOrAccount(if (account != null) LoginOverlayMode.AccountMenu else LoginOverlayMode.Login) }
                            if (searchMinePaneRenderer != null) {
                                searchMinePaneRenderer.MineMenuContent(account, onAccountAction, hostViewModel::selectSidebarEntry, padding)
                            } else {
                                UnbuiltEntryPlaceholder(
                                    message = account?.username ?: stringResource(R.string.shell_sidebar_mine),
                                    showBack = false,
                                    onBack = null,
                                    contentPadding = padding,
                                    action = HiStateAction(stringResource(if (account == null) R.string.common_action_login else R.string.shell_sidebar_account), onAccountAction),
                                )
                            }
                        },
                        settingsDetailContent = { padding ->
                            val showBack = !paneLayout.hasDetailPane
                            val onBack = if (showBack) ({ hostViewModel.handleBack(paneLayout.layoutMode); Unit }) else null
                            if (settingsProfilePaneRenderer != null) {
                                settingsProfilePaneRenderer.SettingsDetailContent(
                                    appState.selectedSettingsGroup, showBack, onBack,
                                    hostViewModel::openThread, { openLoginOrAccount(LoginOverlayMode.Login) }, padding,
                                )
                            } else {
                                UnbuiltEntryPlaceholder(stringResource(R.string.shell_sidebar_settings), showBack, onBack, padding)
                            }
                        },
                        userProfileContent = { profile, showBack, onBack, padding ->
                            if (settingsProfilePaneRenderer != null) {
                                settingsProfilePaneRenderer.UserProfileContent(
                                    profile.uid, profile.username, showBack, onBack,
                                    hostViewModel::openThread, hostViewModel::pushSmsConversation, { openLoginOrAccount(LoginOverlayMode.Login) }, padding,
                                )
                            } else {
                                UnbuiltEntryPlaceholder(profile.username.ifBlank { profile.uid }, showBack, onBack, padding)
                            }
                        },
                        smsConversationContent = { args, showBack, onBack, padding ->
                            if (messagesPaneRenderer != null) {
                                messagesPaneRenderer.ConversationContent(
                                    args, showBack, onBack, hostViewModel::pushUserProfile, { openLoginOrAccount(LoginOverlayMode.Login) }, padding,
                                )
                            } else {
                                UnbuiltEntryPlaceholder(args.username.ifBlank { args.uid }, showBack, onBack, padding)
                            }
                        },
                    )
                }

                val imageViewerArgs = activeImageViewerArgs
                if (imageViewerArgs != null && imageViewerRenderer != null) {
                    imageViewerRenderer.Overlay(args = imageViewerArgs, onDismiss = { activeImageViewerArgs = null })
                }

                val overlayMode = appState.loginOverlayMode
                if (overlayMode != null && loginOverlayRenderer != null) {
                    loginOverlayRenderer.Content(overlayMode, hostViewModel::dismissLoginOverlay, hostViewModel::dismissLoginOverlay)
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
            }
        }
    }
}

@Composable
private fun entryPlaceholderText(entry: ShellSidebarEntry): String = stringResource(
    when (entry) {
        ShellSidebarEntry.Sms -> R.string.common_empty_sms
        ShellSidebarEntry.Notify -> R.string.common_empty_notify
        ShellSidebarEntry.Search -> R.string.common_search_hint
        ShellSidebarEntry.FavoritesAndAttention -> R.string.shell_sidebar_favorites_and_attention
        ShellSidebarEntry.MyThreads -> R.string.shell_sidebar_my_threads
        ShellSidebarEntry.MyReplies -> R.string.shell_sidebar_my_replies
        ShellSidebarEntry.History -> R.string.shell_sidebar_history
        ShellSidebarEntry.Settings -> R.string.shell_sidebar_settings
        else -> R.string.common_empty_threads
    },
)

@Composable
private fun UnbuiltEntryPlaceholder(
    message: String,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues,
    action: HiStateAction? = null,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            if (showBack && onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = topInset + 4.dp, start = 4.dp).defaultMinSize(48.dp, 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                }
            }
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                HiEmptyState(message = message, action = action)
            }
        }
    }
}
