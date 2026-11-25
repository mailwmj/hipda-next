package net.jejer.hipda.feature.threaddetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*

/** 过滤胶囊栏固定占用高度（含 48dp 触控区与留白），供楼层列表顶部固定留白对齐。 */
internal val FilterCapsuleBarHeight: Dp = 56.dp

/** 顶栏下方常驻的只看过滤胶囊（支持 `只看楼主 ×` 及预留 `只看 {用户名} ×` 供 Wave 3 `E2c` 复用）。 */
@Composable
internal fun FilterCapsuleBar(
    filter: ThreadDetailFilterUi,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = filter !is ThreadDetailFilterUi.All
    val display = remember { mutableStateOf<ThreadDetailFilterUi?>(null) }.apply { if (active) value = filter }.value
    AnimatedVisibility(
        visible = active,
        modifier = modifier.fillMaxWidth(),
        enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(),
        exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut(),
        label = "FilterCapsuleBar",
    ) {
        val label = when (display) {
            is ThreadDetailFilterUi.AuthorOnly -> stringResource(R.string.thread_detail_action_author_only)
            is ThreadDetailFilterUi.UserOnly -> stringResource(R.string.thread_detail_filter_user_only, display.username)
            else -> return@AnimatedVisibility
        }
        val cs = MaterialTheme.colorScheme
        val closeInteraction = remember { MutableInteractionSource() }
        HiCappedFontScale {
            Box(Modifier.fillMaxWidth().heightIn(min = FilterCapsuleBarHeight).padding(horizontal = 16.dp, vertical = 4.dp), Alignment.CenterStart) {
                Surface(shape = HiShapeTokens.Full, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Box(
                            modifier = Modifier.size(48.dp).hiPressScale(closeInteraction).clip(HiShapeTokens.Full)
                                .clickable(closeInteraction, LocalIndication.current, role = Role.Button, onClick = onClearFilter),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.thread_detail_filter_exit), tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
