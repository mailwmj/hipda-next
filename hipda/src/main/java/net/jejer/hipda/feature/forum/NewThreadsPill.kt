package net.jejer.hipda.feature.forum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*

/** 顶部新帖提示悬浮胶囊（`有 {N} 条新帖`，纯色不占玻璃预算，48dp 触控区 + 按压状态层与微缩放，点击滚顶并清除）。 */
@Composable
fun NewThreadsPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = hiFadeIn(HiMotion.effectsDefault()) + hiSlideInVertically(HiMotion.spatialDefault()) { -it },
        exit = hiFadeOut(HiMotion.exit(HiMotion.effectsDefault())) + hiSlideOutVertically(HiMotion.exit(HiMotion.spatialDefault())) { -it },
        label = "ForumNewThreadsPill",
    ) {
        val interaction = remember { MutableInteractionSource() }
        val cs = MaterialTheme.colorScheme
        Surface(
            onClick = onClick,
            shape = HiShapeTokens.Full,
            color = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            shadowElevation = 3.dp,
            interactionSource = interaction,
            modifier = Modifier.hiPressScale(interaction),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.forum_new_threads_pill, count), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
