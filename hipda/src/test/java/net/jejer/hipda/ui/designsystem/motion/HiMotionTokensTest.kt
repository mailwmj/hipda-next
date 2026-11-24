package net.jejer.hipda.ui.designsystem.motion

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class HiMotionTokensTest {

    @Test
    fun springTokens_matchProductDesignSection62() {
        assertSpring(HiMotion.spatialFast<Float>(), expectedDamping = 0.9f, expectedStiffness = 1400f)
        assertSpring(HiMotion.spatialDefault<Float>(), expectedDamping = 0.9f, expectedStiffness = 700f)
        assertSpring(HiMotion.spatialSlow<Float>(), expectedDamping = 0.9f, expectedStiffness = 300f)
        assertSpring(HiMotion.spatialSnap<Float>(), expectedDamping = 0.85f, expectedStiffness = 1400f)

        assertSpring(HiMotion.effectsFast<Float>(), expectedDamping = 1.0f, expectedStiffness = 3800f)
        assertSpring(HiMotion.effectsDefault<Float>(), expectedDamping = 1.0f, expectedStiffness = 1600f)
        assertSpring(HiMotion.effectsSlow<Float>(), expectedDamping = 1.0f, expectedStiffness = 800f)
    }

    @Test
    fun exitToken_doublesStiffnessAndForcesCriticalDampingWithoutOvershoot() {
        val pairs = listOf(
            HiMotion.spatialFast<Float>() to 2800f,
            HiMotion.spatialDefault<Float>() to 1400f,
            HiMotion.spatialSlow<Float>() to 600f,
            HiMotion.spatialSnap<Float>() to 2800f,
            HiMotion.effectsFast<Float>() to 7600f,
            HiMotion.effectsDefault<Float>() to 3200f,
            HiMotion.effectsSlow<Float>() to 1600f,
        )
        pairs.forEach { (enter, expectedExitStiffness) ->
            val exitSpec = HiMotion.exit(enter)
            assertSpring(exitSpec, expectedDamping = 1.0f, expectedStiffness = expectedExitStiffness)
        }

        val withThreshold = HiMotion.spatialDefault(IntOffset.VisibilityThreshold)
        val exitWithThreshold = HiMotion.exit(withThreshold)
        assertEquals(IntOffset.VisibilityThreshold, exitWithThreshold.visibilityThreshold)
        assertSpring(exitWithThreshold, expectedDamping = 1.0f, expectedStiffness = 1400f)
    }

    @Test
    fun springTokens_supportAllCommonComposeValueTypes() {
        val floatSpec: SpringSpec<Float> = HiMotion.spatialFast()
        val dpSpec: SpringSpec<Dp> = HiMotion.spatialDefault(Dp.VisibilityThreshold)
        val offsetSpec: SpringSpec<Offset> = HiMotion.spatialSlow(Offset.VisibilityThreshold)
        val intOffsetSpec: SpringSpec<IntOffset> = HiMotion.spatialSnap(IntOffset.VisibilityThreshold)
        val intSizeSpec: SpringSpec<IntSize> = HiMotion.spatialDefault(IntSize.VisibilityThreshold)
        val colorSpec: SpringSpec<Color> = HiMotion.effectsSlow()

        assertEquals(1400f, floatSpec.stiffness, 0.001f)
        assertEquals(Dp.VisibilityThreshold, dpSpec.visibilityThreshold)
        assertEquals(Offset.VisibilityThreshold, offsetSpec.visibilityThreshold)
        assertEquals(IntOffset.VisibilityThreshold, intOffsetSpec.visibilityThreshold)
        assertEquals(IntSize.VisibilityThreshold, intSizeSpec.visibilityThreshold)
        assertEquals(800f, colorSpec.stiffness, 0.001f)
    }

    @Test
    fun periodicAndTimingTokens_matchProductDesignSection62() {
        assertEquals(1000, HiMotion.spinnerPeriodMillis)
        assertEquals(1500, HiMotion.skeletonPeriodMillis)
        assertEquals(150L, HiMotion.loadingShowDelay)
        assertEquals(250L, HiMotion.loadingMinShow)
        assertEquals(1000L, HiMotion.highlightHold)

        val spinnerVector = HiMotion.spinnerPeriod<Float>().vectorize(Float.VectorConverter)
        assertEquals(1000, spinnerVector.durationMillis)
        assertEquals(0, spinnerVector.delayMillis)
        val start = AnimationVector1D(0f)
        val end = AnimationVector1D(1f)
        val zeroVel = AnimationVector1D(0f)
        // spinnerPeriod 必须是线性（LinearEasing）
        assertEquals(0.25f, spinnerVector.getValueFromNanos(250_000_000L, start, end, zeroVel).value, 0.001f)
        assertEquals(0.50f, spinnerVector.getValueFromNanos(500_000_000L, start, end, zeroVel).value, 0.001f)
        assertEquals(0.75f, spinnerVector.getValueFromNanos(750_000_000L, start, end, zeroVel).value, 0.001f)

        val skeletonVector = HiMotion.skeletonPeriod<Float>().vectorize(Float.VectorConverter)
        assertEquals(1500, skeletonVector.durationMillis)
        assertEquals(0, skeletonVector.delayMillis)

        assertNotNull(HiMotion.spinnerRepeatable<Float>())
        assertNotNull(HiMotion.skeletonRepeatable<Float>())
    }

    @Test
    fun motionScheme_delegatesToSixStandardHiMotionTokens() {
        val scheme = HiMotion.motionScheme
        assertSame(HiMotion.spatialFast<Float>(), scheme.fastSpatialSpec<Float>())
        assertSame(HiMotion.spatialDefault<Float>(), scheme.defaultSpatialSpec<Float>())
        assertSame(HiMotion.spatialSlow<Float>(), scheme.slowSpatialSpec<Float>())
        assertSame(HiMotion.effectsFast<Float>(), scheme.fastEffectsSpec<Float>())
        assertSame(HiMotion.effectsDefault<Float>(), scheme.defaultEffectsSpec<Float>())
        assertSame(HiMotion.effectsSlow<Float>(), scheme.slowEffectsSpec<Float>())
    }

    @Test
    fun transitionBuilders_constructEnterAndExitTransitions() {
        assertNotNull(hiFadeIn())
        assertNotNull(hiFadeOut())
        assertNotNull(hiSlideInHorizontally())
        assertNotNull(hiSlideOutHorizontally())
        assertNotNull(hiScaleIn())
        assertNotNull(hiScaleOut())
        assertNotNull(hiExpandVertically())
        assertNotNull(hiShrinkVertically())
    }

    private fun <T> assertSpring(
        spec: SpringSpec<T>,
        expectedDamping: Float,
        expectedStiffness: Float,
    ) {
        assertEquals(expectedDamping, spec.dampingRatio, 0.0001f)
        assertEquals(expectedStiffness, spec.stiffness, 0.0001f)
    }
}
