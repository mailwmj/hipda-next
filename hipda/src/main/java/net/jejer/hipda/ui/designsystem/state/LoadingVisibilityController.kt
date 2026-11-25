package net.jejer.hipda.ui.designsystem.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.motion.HiMotion

/**
 * 纯协程加载指示器可见性状态机（不依赖 Compose，便于虚拟时间单测）。
 * 被动加载超 [showDelayMillis] 才显示且至少停留 [minShowMillis]；主动操作（`passive = false`）0ms 显隐。
 */
class LoadingVisibilityController(
    private val scope: CoroutineScope,
    private val showDelayMillis: Long = HiMotion.loadingShowDelay,
    private val minShowMillis: Long = HiMotion.loadingMinShow,
    initialVisible: Boolean = false,
) {
    init {
        require(showDelayMillis >= 0L) { "showDelayMillis must be >= 0, got $showDelayMillis" }
        require(minShowMillis >= 0L) { "minShowMillis must be >= 0, got $minShowMillis" }
    }

    private val _visible = MutableStateFlow(initialVisible)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var isLoading: Boolean = initialVisible
    private var isPassive: Boolean = !initialVisible
    private var showDelayJob: Job? = null
    private var minShowJob: Job? = null

    fun update(isLoading: Boolean, passive: Boolean = true) {
        this.isLoading = isLoading
        this.isPassive = passive
        if (!passive) {
            cancel()
            _visible.value = isLoading
            return
        }
        if (isLoading) {
            if (_visible.value || showDelayJob?.isActive == true) return
            showDelayJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(showDelayMillis)
                if (!this@LoadingVisibilityController.isLoading || !this@LoadingVisibilityController.isPassive) return@launch
                _visible.value = true
                minShowJob?.cancel()
                minShowJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(minShowMillis)
                    if (!this@LoadingVisibilityController.isLoading) _visible.value = false
                }
            }
        } else {
            showDelayJob?.cancel()
            showDelayJob = null
            if (minShowJob?.isActive != true) _visible.value = false
        }
    }

    fun cancel() {
        showDelayJob?.cancel()
        showDelayJob = null
        minShowJob?.cancel()
        minShowJob = null
    }
}
