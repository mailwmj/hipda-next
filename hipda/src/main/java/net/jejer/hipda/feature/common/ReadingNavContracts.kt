package net.jejer.hipda.feature.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import net.jejer.hipda.ui.designsystem.glass.HiGlassState

@Immutable
data class ThreadNavArgs(
    val tid: String,
    val title: String = "",
    val fid: Int = 0,
    val forumName: String? = null,
    val author: String? = null,
    val targetPid: String? = null,
)

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
        if (hostState.showSnackbar(message = message, actionLabel = actionLabel) == SnackbarResult.ActionPerformed) {
            onAction?.invoke()
        }
    }
}

val LocalPaneSnackbarController = staticCompositionLocalOf<PaneSnackbarController> { NoOpPaneSnackbarController }
