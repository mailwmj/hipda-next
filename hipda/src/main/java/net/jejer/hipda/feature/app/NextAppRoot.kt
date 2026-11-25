package net.jejer.hipda.feature.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.designsystem.state.HiStateAction
import net.jejer.hipda.ui.shell.*

/** M5 新版应用根容器：驱动实时主题、侧栏账号与双角标、玻璃采样预配及可选登录覆盖层插槽。 */
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
    threadViewModelFactory: ViewModelProvider.Factory? = null,
    onFallbackLoginClick: (() -> Unit)? = null,
) {
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val account by currentAccountFlow.collectAsStateWithLifecycle()
    val smsUnread by smsUnreadCountFlow.collectAsStateWithLifecycle()
    val notifyUnread by notifyUnreadCountFlow.collectAsStateWithLifecycle()
    val appState by hostViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings.followedForums) { hostViewModel.syncDefaultForum(settings.followedForums) }

    val themeConfig = remember(settings) { HiThemeSettingsAdapter.toThemeConfig(settings) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarController = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
    val openLoginOrAccount: (LoginOverlayMode) -> Unit = { mode ->
        if (loginOverlayRenderer != null) hostViewModel.openLoginOverlay(mode) else onFallbackLoginClick?.invoke()
    }
    val sidebarState = remember(appState.selectedSidebarEntry, settings.followedForums, smsUnread, notifyUnread, account) {
        HiSidebarState(
            selectedEntry = appState.selectedSidebarEntry,
            followedForumIds = settings.followedForums,
            unreadSmsCount = smsUnread,
            unreadNotifyCount = notifyUnread,
            account = HiSidebarAccount(username = account?.username),
        )
    }

    HiTheme(themeConfig.themeMode, themeConfig.pureBlack, themeConfig.seed, themeConfig.fontSizeStep, themeConfig.lineHeightPreset) {
        CompositionLocalProvider(LocalHiImageLoader provides imageLoader, LocalPaneSnackbarController provides snackbarController) {
            Box(modifier = modifier.fillMaxSize()) {
                HiAdaptiveShell(
                    selected = appState.selectedDestination,
                    onSelect = hostViewModel::selectDestination,
                    sidebarState = sidebarState,
                    onSelectSidebarEntry = hostViewModel::selectSidebarEntry,
                    onAccountClick = { openLoginOrAccount(if (account != null) LoginOverlayMode.AccountMenu else LoginOverlayMode.Login) },
                    hasDetailContent = appState.selectedThread != null,
                    managesGlassSource = { it == ShellDestination.Forum },
                ) { destination, contentPadding ->
                    when (destination) {
                        ShellDestination.Forum -> BasicMultiPaneContainer(
                            paneLayout = paneLayout,
                            selectedThread = appState.selectedThread,
                            hostViewModel = hostViewModel,
                            contentPadding = contentPadding,
                            onCloseThread = hostViewModel::closeThread,
                            shellGlassState = shellGlassState,
                            threadViewModelFactory = threadViewModelFactory,
                            forumContent = { chromeSpec, padding ->
                                ForumPane(
                                    fid = appState.activeForumFid,
                                    chromeSpec = chromeSpec,
                                    selectedTid = appState.selectedThread?.tid,
                                    onSelectForum = hostViewModel::selectForum,
                                    onOpenThread = hostViewModel::openThread,
                                    onOpenLogin = { openLoginOrAccount(LoginOverlayMode.Login) },
                                    listState = hostViewModel.forumListStateFor(appState.activeForumFid),
                                    contentPadding = padding,
                                    shellContentScope = this,
                                    viewModel = forumViewModel,
                                )
                            },
                            detailContent = { args, chromeSpec, padding ->
                                ThreadDetailPane(
                                    args = args,
                                    chromeSpec = chromeSpec,
                                    onBack = hostViewModel::closeThread,
                                    onOpenLogin = { openLoginOrAccount(LoginOverlayMode.Login) },
                                    listState = hostViewModel.threadListStateFor(args.tid),
                                    contentPadding = padding,
                                )
                            },
                        )
                        ShellDestination.Messages -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            HiEmptyState(stringResource(if (appState.selectedSidebarEntry == ShellSidebarEntry.Notify) R.string.common_empty_notify else R.string.common_empty_sms))
                        }
                        ShellDestination.Search -> Box(Modifier.fillMaxSize(), Alignment.Center) { HiEmptyState(stringResource(R.string.common_search_hint)) }
                        ShellDestination.Mine -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            HiEmptyState(
                                message = account?.username ?: stringResource(R.string.shell_sidebar_mine),
                                action = if (account == null) HiStateAction(stringResource(R.string.common_action_login)) { openLoginOrAccount(LoginOverlayMode.Login) } else null,
                            )
                        }
                    }
                }
                HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 88.dp)
                val overlayMode = appState.loginOverlayMode
                if (overlayMode != null && loginOverlayRenderer != null) {
                    loginOverlayRenderer.Content(overlayMode, hostViewModel::dismissLoginOverlay, hostViewModel::dismissLoginOverlay)
                }
            }
        }
    }
}
