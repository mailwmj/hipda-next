package net.jejer.hipda.feature.mine

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.search.SearchPane
import net.jejer.hipda.feature.search.SearchViewModel
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** [SearchMinePaneRenderer] 生产实现：零改外壳自动承接搜索、单栏我的菜单及 3 个我的子页面。 */
@Singleton
class SearchMinePaneRendererImpl @Inject constructor(
    private val searchRepository: SearchRepository,
    private val favoriteRepository: FavoriteRepository,
    private val browseHistoryDao: BrowseHistoryDao,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : SearchMinePaneRenderer {

    @Composable
    override fun SearchContent(showBack: Boolean, onBack: (() -> Unit)?, onOpenThread: (ThreadNavArgs) -> Unit, onOpenForum: (fid: Int) -> Unit, onOpenUserProfile: (uid: String, username: String) -> Unit, onOpenLogin: () -> Unit, contentPadding: PaddingValues, listState: LazyListState) {
        val factory = remember(searchRepository, settingsRepository, authRepository, siteAccessGateway) {
            viewModelFactory { initializer { SearchViewModel(searchRepository, settingsRepository, authRepository, siteAccessGateway) } }
        }
        SearchPane(viewModel(factory = factory), showBack, onBack, onOpenThread, onOpenForum, onOpenUserProfile, onOpenLogin, contentPadding = contentPadding, listState = listState)
    }

    @Composable
    override fun MineMenuContent(account: SavedAccount?, onAccountClick: () -> Unit, onSelectSubEntry: (ShellSidebarEntry) -> Unit, contentPadding: PaddingValues) =
        MineMenuScreen(account, onAccountClick, onSelectSubEntry, contentPadding = contentPadding)

    @Composable
    override fun MineSubPageContent(entry: ShellSidebarEntry, isSidebar: Boolean, showBack: Boolean, onBack: (() -> Unit)?, onSelectMineEntry: (ShellSidebarEntry) -> Unit, onOpenThread: (ThreadNavArgs) -> Unit, onOpenLogin: () -> Unit, contentPadding: PaddingValues, listState: LazyListState) {
        when (entry) {
            ShellSidebarEntry.MyThreads, ShellSidebarEntry.MyReplies -> {
                val initialTab = if (entry == ShellSidebarEntry.MyReplies) MyPostsTab.Replies else MyPostsTab.Threads
                val factory = remember(searchRepository, authRepository, siteAccessGateway) { viewModelFactory { initializer { MyPostsViewModel(searchRepository, authRepository, siteAccessGateway, initialTab) } } }
                MyPostsPane(viewModel(factory = factory), entry, isSidebar, showBack, onBack, onSelectMineEntry, onOpenThread, onOpenLogin, contentPadding = contentPadding, listState = listState)
            }
            ShellSidebarEntry.History -> {
                val factory = remember(browseHistoryDao) { viewModelFactory { initializer { BrowseHistoryViewModel(browseHistoryDao) } } }
                BrowseHistoryPane(viewModel(factory = factory), showBack, onBack, onOpenThread, contentPadding = contentPadding, listState = listState)
            }
            else -> {
                val factory = remember(favoriteRepository, authRepository, siteAccessGateway) { viewModelFactory { initializer { FavoritesViewModel(favoriteRepository, authRepository, siteAccessGateway) } } }
                FavoritesPane(viewModel(factory = factory), showBack, onBack, onOpenThread, onOpenLogin, contentPadding = contentPadding, listState = listState)
            }
        }
    }
}
