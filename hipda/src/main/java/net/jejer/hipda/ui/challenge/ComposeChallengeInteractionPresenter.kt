package net.jejer.hipda.ui.challenge

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.HiThemeSettingsAdapter
import net.jejer.hipda.ui.designsystem.HiTheme

/**
 * 生产用 [ChallengeInteractionPresenter]：在现有 [ChallengeActivity] 窗口中挂载 [ChallengePanelOverlay]，
 * 实时订阅 [SettingsRepository] 同步用户主题/纯黑/种子色/字号至 [HiTheme]，
 * 将后台运行中的同一 `WebView` 从隐藏状态切换为可见可交互（不重新调用 `loadUrl`），
 * 并在用户点击刷新/取消、按下系统返回或后台轮询检测到验证通过时完成收起与结果回传。
 */
@Singleton
class ComposeChallengeInteractionPresenter internal constructor(
    private val settingsFlow: Flow<AppSettings>,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val dismissTimeoutMs: Long = DEFAULT_DISMISS_TIMEOUT_MS,
) : ChallengeInteractionPresenter {

    @Inject
    constructor(settingsRepository: SettingsRepository) : this(
        settingsFlow = settingsRepository.settings,
        pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
        dismissTimeoutMs = DEFAULT_DISMISS_TIMEOUT_MS,
    )

    internal constructor(
        pollIntervalMs: Long,
        dismissTimeoutMs: Long,
    ) : this(
        settingsFlow = flowOf(AppSettings()),
        pollIntervalMs = pollIntervalMs,
        dismissTimeoutMs = dismissTimeoutMs,
    )

    override suspend fun present(session: ChallengeInteractionSession): SolveResult {
        val binding = session.activityBinding ?: return SolveResult.Cancelled

        if (safeCheckClearance(session)) {
            return SolveResult.Cleared
        }

        val initialSettings = readInitialSettings()

        return withContext(Dispatchers.Main.immediate) {
            val activity = binding.activity
            if (
                activity.isFinishing ||
                activity.isDestroyed ||
                activity.lifecycle.currentState == Lifecycle.State.DESTROYED
            ) {
                return@withContext SolveResult.Cancelled
            }

            val panelVisible = mutableStateOf(false)
            val resultDeferred = CompletableDeferred<SolveResult>()
            val dismissedDeferred = CompletableDeferred<Unit>()
            var panelShown = false

            fun finishWith(result: SolveResult) {
                if (!resultDeferred.complete(result)) return
                if (panelShown) {
                    panelVisible.value = false
                } else {
                    dismissedDeferred.complete(Unit)
                }
            }

            val hostDestroyObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    resultDeferred.complete(SolveResult.Cancelled)
                    dismissedDeferred.complete(Unit)
                    panelVisible.value = false
                }
            }
            activity.lifecycle.addObserver(hostDestroyObserver)

            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
                    val themeConfig = remember(settings) { HiThemeSettingsAdapter.toThemeConfig(settings) }
                    HiTheme(
                        themeMode = themeConfig.themeMode,
                        pureBlack = themeConfig.pureBlack,
                        seed = themeConfig.seed,
                        fontSizeStep = themeConfig.fontSizeStep,
                        lineHeightPreset = themeConfig.lineHeightPreset,
                    ) {
                        LaunchedEffect(Unit) {
                            if (!resultDeferred.isCompleted) {
                                panelShown = true
                                panelVisible.value = true
                            }
                        }
                        ChallengePanelOverlay(
                            visible = panelVisible.value,
                            onRefresh = {
                                if (!resultDeferred.isCompleted) {
                                    session.webViewHandle.reload()
                                }
                            },
                            onCancel = { finishWith(SolveResult.Cancelled) },
                            onDismissed = { dismissedDeferred.complete(Unit) },
                            webViewSlot = {
                                AndroidView(
                                    factory = {
                                        val webView = binding.webView
                                        (webView.parent as? ViewGroup)?.removeView(webView)
                                        webView.layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                        binding.setInteractiveMode(true)
                                        webView
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                        )
                    }
                }
            }

            binding.rootContainer.addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )

            val pollJob = launch {
                while (isActive && !resultDeferred.isCompleted) {
                    delay(pollIntervalMs)
                    if (safeCheckClearance(session)) {
                        finishWith(SolveResult.Cleared)
                        break
                    }
                }
            }

            try {
                val result = resultDeferred.await()
                pollJob.cancel()
                withTimeoutOrNull(dismissTimeoutMs) {
                    dismissedDeferred.await()
                }
                result
            } finally {
                pollJob.cancel()
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    activity.lifecycle.removeObserver(hostDestroyObserver)
                    binding.setInteractiveMode(false)
                    composeView.disposeComposition()
                    (composeView.parent as? ViewGroup)?.removeView(composeView)
                    val webView = binding.webView
                    if (webView.parent !== binding.rootContainer) {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                    }
                }
            }
        }
    }

    private suspend fun readInitialSettings(): AppSettings =
        try {
            withTimeoutOrNull(INITIAL_SETTINGS_TIMEOUT_MS) { settingsFlow.first() } ?: AppSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppSettings()
        }

    private suspend fun safeCheckClearance(session: ChallengeInteractionSession): Boolean =
        try {
            session.checkAndSyncClearance()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
        const val DEFAULT_DISMISS_TIMEOUT_MS: Long = 1_000L
        private const val INITIAL_SETTINGS_TIMEOUT_MS: Long = 100L
    }
}
