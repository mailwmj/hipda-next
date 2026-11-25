package net.jejer.hipda.feature.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dagger.hilt.internal.GeneratedComponentManagerHolder
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.shell.ShellPaneLayout
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode

/** 基础自适应阅读容器（供 `E3` 原位加厚）：以 `movableContentOf` + `SaveableStateHolder` + 按 `tid` `LocalViewModelStoreOwner` 真保活。 */
@Composable
fun BasicMultiPaneContainer(
    paneLayout: ShellPaneLayout,
    selectedThread: ThreadNavArgs?,
    hostViewModel: NextAppHostViewModel,
    contentPadding: PaddingValues,
    onCloseThread: () -> Unit,
    modifier: Modifier = Modifier,
    shellGlassState: HiGlassState? = null,
    threadViewModelFactory: ViewModelProvider.Factory? = null,
    forumContent: @Composable (ForumChromeSpec, PaddingValues) -> Unit,
    detailContent: @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val currentForumContent by rememberUpdatedState(forumContent)
    val currentDetailContent by rememberUpdatedState(detailContent)

    val forumMovable = remember(saveableStateHolder) {
        movableContentOf { chromeSpec: ForumChromeSpec, padding: PaddingValues ->
            saveableStateHolder.SaveableStateProvider("forum_pane") { currentForumContent(chromeSpec, padding) }
        }
    }
    val detailMovable = remember(hostViewModel, threadViewModelFactory) {
        movableContentOf { args: ThreadNavArgs, chromeSpec: DetailChromeSpec, padding: PaddingValues ->
            val factory = rememberThreadViewModelProviderFactory(threadViewModelFactory)
            val parentOwner = LocalViewModelStoreOwner.current
            val extras = remember(parentOwner) {
                (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
            }
            key(args.tid) {
                val scopedOwner = remember(hostViewModel, args.tid, factory, extras) {
                    hostViewModel.viewModelStoreOwnerForThread(args.tid, factory, extras)
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
                    currentDetailContent(args, chromeSpec, padding)
                }
            }
        }
    }

    val forumChromeSpec = remember(paneLayout.isSidebar, paneLayout.layoutMode, shellGlassState) {
        ForumChromeSpec(!paneLayout.isSidebar, paneLayout.layoutMode != ShellPaneLayoutMode.HingeTwoPane, shellGlassState)
    }
    val detailChromeSpec = remember(paneLayout.hasDetailPane) { DetailChromeSpec(showBack = !paneLayout.hasDetailPane) }

    if (paneLayout.hasDetailPane) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(Modifier.width(paneLayout.middlePaneWidthDp.dp).fillMaxHeight()) { forumMovable(forumChromeSpec, contentPadding) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (selectedThread != null) detailMovable(selectedThread, detailChromeSpec, contentPadding)
                else HiEmptyState(stringResource(R.string.shell_empty_select_thread))
            }
        }
    } else {
        BackHandler(enabled = selectedThread != null, onBack = onCloseThread)
        Box(modifier = modifier.fillMaxSize()) {
            if (selectedThread != null) detailMovable(selectedThread, detailChromeSpec, contentPadding)
            else forumMovable(forumChromeSpec, contentPadding)
        }
    }
}

@Composable
internal fun rememberThreadViewModelProviderFactory(
    overrideFactory: ViewModelProvider.Factory? = null,
): ViewModelProvider.Factory {
    if (overrideFactory != null) return overrideFactory
    val context = LocalContext.current
    val parentOwner = LocalViewModelStoreOwner.current
    val delegateFactory = remember(parentOwner) {
        (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.NewInstanceFactory()
    }
    return remember(context, delegateFactory) {
        context.findHiltComponentActivity()?.let { HiltViewModelFactory(it, delegateFactory) } ?: delegateFactory
    }
}

private tailrec fun Context.findHiltComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> if (this is GeneratedComponentManagerHolder) this else null
    is ContextWrapper -> baseContext.findHiltComponentActivity()
    else -> null
}
