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
 *
 * - 被动加载（`passive = true`）：加载开始超过 [showDelayMillis]（默认 [HiMotion.loadingShowDelay] = 150ms）
 *   仍未结束才变为可见；一旦可见，至少保持 [minShowMillis]（默认 [HiMotion.loadingMinShow] = 250ms）；
 *   在保持期间重新开始加载时不闪烁、持续保持可见。
 * - 主动操作（`passive = false`）：0ms 立即变为可见，加载结束立即隐藏。
 */
class LoadingVisibilityController(
    private val scope: CoroutineScope,
    private val showDelayMillis: Long = HiMotion.loadingShowDelay,
    private val minShowMillis: Long = HiMotion.loadingMinShow,
    initialVisible: Boolean = false,
    private val onVisibilityChange: ((Boolean) -> Unit)? = null,
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
    private var minShowActive: Boolean = false

    fun update(isLoading: Boolean, passive: Boolean = true) {
        this.isLoading = isLoading
        this.isPassive = passive

        if (!passive) {
            cancelShowDelay()
            cancelMinShow()
            setVisible(isLoading)
            return
        }

        if (isLoading) {
            if (_visible.value) {
                cancelShowDelay()
                return
            }
            if (showDelayJob?.isActive == true) {
                return
            }
            if (showDelayMillis == 0L) {
                showWithMinDuration()
            } else {
                showDelayJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val currentJob = coroutineContext[Job]
                    try {
                        delay(showDelayMillis)
                    } finally {
                        if (showDelayJob === currentJob) {
                            showDelayJob = null
                        }
                    }
                    if (this@LoadingVisibilityController.isLoading && this@LoadingVisibilityController.isPassive) {
                        showWithMinDuration()
                    }
                }
            }
        } else {
            cancelShowDelay()
            if (!minShowActive) {
                setVisible(false)
            }
        }
    }

    fun cancel() {
        cancelShowDelay()
        cancelMinShow()
    }

    private fun showWithMinDuration() {
        setVisible(true)
        cancelMinShow()
        if (minShowMillis == 0L) {
            return
        }
        minShowActive = true
        minShowJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val currentJob = coroutineContext[Job]
            try {
                delay(minShowMillis)
            } finally {
                if (minShowJob === currentJob) {
                    minShowActive = false
                    minShowJob = null
                }
            }
            if (!this@LoadingVisibilityController.isLoading) {
                setVisible(false)
            }
        }
    }

    private fun cancelShowDelay() {
        showDelayJob?.cancel()
        showDelayJob = null
    }

    private fun cancelMinShow() {
        minShowJob?.cancel()
        minShowJob = null
        minShowActive = false
    }

    private fun setVisible(value: Boolean) {
        if (_visible.value != value) {
            _visible.value = value
            onVisibilityChange?.invoke(value)
        }
    }
}
