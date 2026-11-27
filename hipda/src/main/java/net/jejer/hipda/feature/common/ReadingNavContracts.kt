package net.jejer.hipda.feature.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import net.jejer.hipda.ui.designsystem.glass.HiGlassState

/** 论坛列表页单一列表来源（供 `NextAppState` 与 `ForumPane`/`E1` 共享，统一宽屏左栏与窄屏标签行）。 */
@Immutable
sealed interface ForumListSource {
    data object TodayNew : ForumListSource
    data class Forum(val fid: Int) : ForumListSource
}

@Immutable
data class ThreadNavArgs(
    val tid: String,
    val title: String = "",
    val fid: Int = 0,
    val forumName: String? = null,
    val author: String? = null,
    val targetPid: String? = null,
    val openLastPage: Boolean = false,
    val navSeq: Long = 0L,
)

@Immutable
data class SmsConversationArgs(
    val uid: String,
    val username: String,
)

/** 设置 5 个顶层分组（docs/design/copy.md §设置、product-design.md §7.10）。 */
enum class SettingsGroup {
    Appearance,
    ForumAndPost,
    Notification,
    NetworkAndCache,
    About,
}

@Immutable
data class ForumChromeSpec(
    val showForumTabs: Boolean,
    val useGlassTopBar: Boolean,
    val externalGlassState: HiGlassState? = null,
)

@Immutable
data class FullScreenToggle(val isFullScreen: Boolean, val onToggle: () -> Unit)

@Immutable
data class DetailChromeSpec(
    val showBack: Boolean,
    val fullScreenState: FullScreenToggle? = null,
    val suppressBottomGlass: Boolean = false,
)

@Immutable
data class ImageViewerLaunchArgs(
    val urls: List<String>,
    val initialIndex: Int = 0,
    val sourceBoundsInWindow: Rect? = null,
    val floorNumber: Int? = null,
    val author: String? = null,
)

@Immutable
data class ReplyRequest(
    val tid: String,
    val quotePid: String? = null,
    val quoteAuthor: String? = null,
    val quoteFloor: Int? = null,
)

enum class PaneSnackbarTarget { DetailPane, ContentArea }

@Stable
interface PaneSnackbarController {
    suspend fun show(
        message: String,
        target: PaneSnackbarTarget = PaneSnackbarTarget.ContentArea,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    )
}

object NoOpPaneSnackbarController : PaneSnackbarController {
    override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) = Unit
}

class SingleHostPaneSnackbarController(private val hostState: SnackbarHostState) : PaneSnackbarController {
    override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) {
        val duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        if (hostState.showSnackbar(message = message, actionLabel = actionLabel, duration = duration) == SnackbarResult.ActionPerformed) {
            onAction?.invoke()
        }
    }
}

val LocalPaneSnackbarController = staticCompositionLocalOf<PaneSnackbarController> { NoOpPaneSnackbarController }
