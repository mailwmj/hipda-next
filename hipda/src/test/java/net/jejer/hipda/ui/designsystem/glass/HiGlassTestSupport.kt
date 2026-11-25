package net.jejer.hipda.ui.designsystem.glass

import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 断言当前界面语义树中同时存在的毛玻璃区域（独立 [HiGlassSurface] 或 [HiGlassRegion]）数量不超过 [maxCount]。
 *
 * 使用未合并语义树计数，避免父节点合并语义后漏计嵌套或相邻的玻璃区域；
 * 同一 [HiGlassRegion] 内共享采样源的多段形状（如三段工具条）仅计为 1 个区域。
 */
fun SemanticsNodeInteractionsProvider.assertGlassSurfaceCountAtMost(maxCount: Int) {
    require(maxCount >= 0) { "maxCount must be >= 0, was $maxCount" }
    val count = onAllNodes(
        matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size
    if (count > maxCount) {
        throw AssertionError(
            "Expected at most $maxCount glass regions on screen, but found $count",
        )
    }
}

/**
 * 断言当前界面至少存在一个 [HiGlassSurface] 且所有毛玻璃表面的实时模糊激活态均等于 [expectedBlurActive]。
 */
fun SemanticsNodeInteractionsProvider.assertAllGlassSurfacesBlurActive(expectedBlurActive: Boolean) {
    val nodes = onAllNodes(
        matcher = SemanticsMatcher.keyIsDefined(HiGlassBlurActive),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    if (nodes.isEmpty()) {
        throw AssertionError("Expected at least one HiGlassSurface node, but found 0")
    }
    val mismatched = nodes.count { node ->
        node.config[HiGlassBlurActive] != expectedBlurActive
    }
    if (mismatched > 0) {
        throw AssertionError(
            "Expected all ${nodes.size} HiGlassSurface nodes to have HiGlassBlurActive=$expectedBlurActive, " +
                "but $mismatched differed",
        )
    }
}

/**
 * 单元测试与截图测试使用的可编程 [GlassEnvironmentSignalSource] 替身。
 */
class FakeGlassEnvironmentSignalSource(
    initialSignals: GlassEnvironmentSignals = GlassEnvironmentSignals(
        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        isPowerSaveMode = false,
        isReduceTransparencyEnabled = false,
        isHighContrastTextEnabled = false,
    ),
) : GlassEnvironmentSignalSource {

    private val state = MutableStateFlow(initialSignals)

    override fun currentSignals(): GlassEnvironmentSignals = state.value

    override fun observeSignals(): Flow<GlassEnvironmentSignals> = state.asStateFlow()

    fun emit(signals: GlassEnvironmentSignals) {
        state.value = signals
    }

    fun update(transform: (GlassEnvironmentSignals) -> GlassEnvironmentSignals) {
        state.value = transform(state.value)
    }
}

/** 计算两个不透明颜色的 WCAG 2.1 相对亮度对比度（范围 1.0..21.0）。 */
fun wcagContrastRatio(first: Color, second: Color): Float {
    val l1 = first.luminance()
    val l2 = second.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/**
 * 断言 [foreground] 叠加在 [surface]（若半透明则先合成到 [backdrop] 上）后的 WCAG 对比度不低于 [minRatio]。
 */
fun assertWcagContrastAtLeast(
    minRatio: Float,
    surface: Color,
    foreground: Color,
    label: String,
    backdrop: Color = Color.Black,
) {
    val opaqueSurface = if (surface.alpha < 1f) surface.compositeOver(backdrop) else surface
    val opaqueForeground = if (foreground.alpha < 1f) foreground.compositeOver(opaqueSurface) else foreground
    val ratio = wcagContrastRatio(opaqueSurface, opaqueForeground)
    if (ratio < minRatio) {
        throw AssertionError("$label contrast was $ratio (< $minRatio)")
    }
}
