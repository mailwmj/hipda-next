package net.jejer.hipda.feature.mine

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.shell.ShellSidebarEntry

private val MINE_MENU_ITEMS = listOf(
    Triple(ShellSidebarEntry.FavoritesAndAttention, R.string.shell_sidebar_favorites_and_attention, Icons.Outlined.FavoriteBorder),
    Triple(ShellSidebarEntry.MyThreads, R.string.shell_sidebar_my_threads, Icons.AutoMirrored.Rounded.List),
    Triple(ShellSidebarEntry.History, R.string.shell_sidebar_history, Icons.Outlined.DateRange),
    Triple(ShellSidebarEntry.Settings, R.string.shell_sidebar_settings, Icons.Outlined.Settings),
)

/** 单栏「我的」总菜单页：顶部账号入口卡 + 4 项二级入口（纯色顶栏，零毛玻璃，全项 ≥ 48dp 与 `hiPressScale`）。 */
@Composable
fun MineMenuScreen(
    account: SavedAccount?, onAccountClick: () -> Unit, onSelectSubEntry: (ShellSidebarEntry) -> Unit,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val cs = MaterialTheme.colorScheme; val title = stringResource(R.string.shell_nav_mine)
    val scrollState = rememberScrollState(); val isScrolled by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    val dir = LocalLayoutDirection.current; val displayName = account?.username?.trim()?.takeIf(String::isNotEmpty)

    Column(modifier.fillMaxSize().background(cs.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(title, isScrolled)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(contentPadding.calculateStartPadding(dir) + 16.dp, 16.dp, contentPadding.calculateEndPadding(dir) + 16.dp, contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val cardInt = remember { MutableInteractionSource() }
            Surface(Modifier.fillMaxWidth().hiPressScale(cardInt), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(cardInt, hiListItemPressIndication(), onClick = onAccountClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).clip(HiShapeTokens.Full).background(if (displayName != null) cs.primaryContainer else cs.surfaceContainerHigh), Alignment.Center) {
                        if (displayName != null) Text(displayName.first().uppercaseChar().toString(), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                        else Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(displayName ?: stringResource(R.string.shell_sidebar_login), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (displayName != null) R.string.shell_sidebar_account else R.string.mine_account_login_prompt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth()) {
                    MINE_MENU_ITEMS.forEachIndexed { idx, (entry, labelRes, icon) ->
                        val rowInt = remember(entry) { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).hiPressScale(rowInt).clickable(rowInt, hiListItemPressIndication()) { onSelectSubEntry(entry) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        if (idx < MINE_MENU_ITEMS.lastIndex) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 52.dp))
                    }
                }
            }
        }
    }
}
