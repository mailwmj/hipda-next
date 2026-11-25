package net.jejer.hipda.feature.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ForumChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.shell.ShellPaneLayout

/**
 * 兼容 `E0` 签名的薄委托壳：完整委托至 [MultiPaneReadingContainer]，复用 `movableContentOf` +
 * `SaveableStateHolder` + 按 `tid` 隔离 `LocalViewModelStoreOwner` 与中右分隔线引擎。
 */
@Composable
fun BasicMultiPaneContainer(
    paneLayout: ShellPaneLayout,
    selectedThread: ThreadNavArgs?,
    hostViewModel: NextAppHostViewModel,
    contentPadding: PaddingValues,
    onCloseThread: () -> Unit,
    modifier: Modifier = Modifier,
    shellGlassState: HiGlassState? = null,
    forumContent: @Composable (ForumChromeSpec, PaddingValues) -> Unit,
    detailContent: @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues) -> Unit,
) {
    val appState = remember(selectedThread) { NextAppState(selectedThread = selectedThread) }
    MultiPaneReadingContainer(
        paneLayout = paneLayout,
        appState = appState,
        hostViewModel = hostViewModel,
        contentPadding = contentPadding,
        persistedPaneSplit = PaneSplit.DEFAULT,
        onPaneSplitChange = {},
        modifier = modifier,
        shellGlassState = shellGlassState,
        forumContent = { chromeSpec, padding, _ -> forumContent(chromeSpec, padding) },
        detailContent = { args, chromeSpec, padding, _, _ -> detailContent(args, chromeSpec, padding) },
    )
    androidx.activity.compose.BackHandler(enabled = !paneLayout.hasDetailPane && selectedThread != null, onBack = onCloseThread)
}
