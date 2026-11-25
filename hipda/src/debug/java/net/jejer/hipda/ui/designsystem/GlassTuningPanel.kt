package net.jejer.hipda.ui.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle

/** 调参面板四档毛玻璃预设分组。 */
internal enum class GlassTuningPreset(
    val defaultStyle: HiGlassStyle,
    val labelRes: Int,
) {
    Navigation(HiGlassStyle.Navigation, R.string.preview_glass_tune_preset_nav),
    TopBar(HiGlassStyle.TopBar, R.string.preview_glass_tune_preset_topbar),
    Toolbar(HiGlassStyle.Toolbar, R.string.preview_glass_tune_preset_toolbar),
    Sheet(HiGlassStyle.Sheet, R.string.preview_glass_tune_preset_sheet),
    ;

    companion object {
        fun matchOrDefault(style: HiGlassStyle): GlassTuningPreset =
            entries.firstOrNull { it.defaultStyle == style } ?: Navigation
    }
}

/** 将当前液态毛玻璃参数格式化为单行稳定文本（固定 `Locale.US`），便于直接复制发给负责人。 */
fun formatGlassStyleParams(style: HiGlassStyle): String {
    val base = String.format(
        Locale.US,
        "blur=%.1fdp refractH=%.1fdp refractAmt=%.1fdp depth=%s ca=%s vibrancy=%s lightTint=%.2f darkTint=%.2f sat=%.2f border=%.2f indicator=%.2f",
        style.blurRadius.value,
        style.refractionHeight.value,
        style.refractionAmount.value,
        if (style.depthEffect) "on" else "off",
        if (style.chromaticAberration) "on" else "off",
        if (style.vibrancyEnabled) "on" else "off",
        style.lightTintAlpha,
        style.darkTintAlpha,
        style.saturation,
        style.borderHighlightStrength,
        style.indicatorAlpha,
    )
    val darkBlur = style.darkBlurRadius
    return if (darkBlur != null) {
        String.format(Locale.US, "%s darkBlur=%.1fdp", base, darkBlur.value)
    } else {
        base
    }
}

/**
 * 仅用于 `src/debug` 预览外壳页的实时毛玻璃调参面板。
 * 使用标准 Material 3 `Card`、`FilterChip`、`Slider`、`Switch` 与按钮组件，不进入 release 包。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassTuningPanel(
    style: HiGlassStyle,
    onStyleChange: (HiGlassStyle) -> Unit,
    modifier: Modifier = Modifier,
    themeMode: HiThemeMode = HiTheme.state.themeMode,
    pureBlack: Boolean = HiTheme.state.pureBlack,
    onThemeChange: ((HiThemeMode, Boolean) -> Unit)? = null,
    selectedPreset: GlassTuningPreset? = null,
    onPresetSelected: ((GlassTuningPreset) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val formatted = remember(style) { formatGlassStyleParams(style) }
    var copiedNotice by remember(formatted) { mutableStateOf(false) }
    var localPreset by remember { mutableStateOf(GlassTuningPreset.matchOrDefault(style)) }
    val activePreset = selectedPreset ?: localPreset
    val selectPreset: (GlassTuningPreset) -> Unit = { preset ->
        localPreset = preset
        onPresetSelected?.invoke(preset)
        onStyleChange(preset.defaultStyle)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = HiShapeTokens.Medium,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onThemeChange != null) {
                    FilterChip(
                        selected = themeMode == HiThemeMode.LIGHT,
                        onClick = { onThemeChange(HiThemeMode.LIGHT, false) },
                        label = { Text(stringResource(R.string.preview_mode_light), style = MaterialTheme.typography.labelSmall) },
                        shape = HiShapeTokens.Small,
                    )
                    FilterChip(
                        selected = themeMode == HiThemeMode.DARK && !pureBlack,
                        onClick = { onThemeChange(HiThemeMode.DARK, false) },
                        label = { Text(stringResource(R.string.preview_mode_dark), style = MaterialTheme.typography.labelSmall) },
                        shape = HiShapeTokens.Small,
                    )
                    FilterChip(
                        selected = themeMode == HiThemeMode.DARK && pureBlack,
                        onClick = { onThemeChange(HiThemeMode.DARK, true) },
                        label = { Text(stringResource(R.string.preview_glass_tune_oled), style = MaterialTheme.typography.labelSmall) },
                        shape = HiShapeTokens.Small,
                    )
                }
                GlassTuningPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = { selectPreset(preset) },
                        label = { Text(stringResource(preset.labelRes), style = MaterialTheme.typography.labelSmall) },
                        shape = HiShapeTokens.Small,
                    )
                }
            }

            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_blur),
                valueText = String.format(Locale.US, "%.1fdp", style.blurRadius.value),
                value = style.blurRadius.value,
                valueRange = 0f..24f,
                onValueChange = { onStyleChange(style.copy(blurRadius = it.dp)) },
            )
            val darkBlur = style.darkBlurRadius
            if (darkBlur != null) {
                GlassTuningSliderRow(
                    label = stringResource(R.string.preview_glass_tune_dark_blur),
                    valueText = String.format(Locale.US, "%.1fdp", darkBlur.value),
                    value = darkBlur.value,
                    valueRange = 0f..24f,
                    onValueChange = { onStyleChange(style.copy(darkBlurRadius = it.dp)) },
                )
            }
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_refraction_height),
                valueText = String.format(Locale.US, "%.1fdp", style.refractionHeight.value),
                value = style.refractionHeight.value,
                valueRange = 0f..32f,
                onValueChange = { onStyleChange(style.copy(refractionHeight = it.dp)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_refraction_amount),
                valueText = String.format(Locale.US, "%.1fdp", style.refractionAmount.value),
                value = style.refractionAmount.value,
                valueRange = 0f..48f,
                onValueChange = { onStyleChange(style.copy(refractionAmount = it.dp)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_light_tint),
                valueText = String.format(Locale.US, "%.2f", style.lightTintAlpha),
                value = style.lightTintAlpha,
                valueRange = 0f..1f,
                onValueChange = { onStyleChange(style.copy(lightTintAlpha = it)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_dark_tint),
                valueText = String.format(Locale.US, "%.2f", style.darkTintAlpha),
                value = style.darkTintAlpha,
                valueRange = 0f..1f,
                onValueChange = { onStyleChange(style.copy(darkTintAlpha = it)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_saturation),
                valueText = String.format(Locale.US, "%.2f", style.saturation),
                value = style.saturation,
                valueRange = 1.0f..1.4f,
                onValueChange = { onStyleChange(style.copy(saturation = it)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_border),
                valueText = String.format(Locale.US, "%.2fx", style.borderHighlightStrength),
                value = style.borderHighlightStrength,
                valueRange = 0f..2f,
                onValueChange = { onStyleChange(style.copy(borderHighlightStrength = it)) },
            )
            GlassTuningSliderRow(
                label = stringResource(R.string.preview_glass_tune_indicator),
                valueText = String.format(Locale.US, "%.2f", style.indicatorAlpha),
                value = style.indicatorAlpha,
                valueRange = 0f..0.6f,
                onValueChange = { onStyleChange(style.copy(indicatorAlpha = it)) },
            )

            GlassTuningSwitchRow(
                label = stringResource(R.string.preview_glass_tune_vibrancy),
                checked = style.vibrancyEnabled,
                onCheckedChange = { onStyleChange(style.copy(vibrancyEnabled = it)) },
            )
            GlassTuningSwitchRow(
                label = stringResource(R.string.preview_glass_tune_depth_effect),
                checked = style.depthEffect,
                onCheckedChange = { onStyleChange(style.copy(depthEffect = it)) },
            )
            GlassTuningSwitchRow(
                label = stringResource(R.string.preview_glass_tune_chromatic_aberration),
                checked = style.chromaticAberration,
                onCheckedChange = { onStyleChange(style.copy(chromaticAberration = it)) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onStyleChange(activePreset.defaultStyle) },
                    modifier = Modifier.weight(1f),
                    shape = HiShapeTokens.Small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.preview_glass_tune_reset),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("HiGlassStyle", formatted))
                        copiedNotice = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = HiShapeTokens.Small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (copiedNotice) {
                                R.string.preview_glass_tune_copied
                            } else {
                                R.string.preview_glass_tune_copy
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Text(
                text = formatted,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassTuningSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        @Suppress("DEPRECATION")
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun GlassTuningSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private val GlassBackdropDotColors: List<Color> = listOf(
    Color(0xFFFF1744),
    Color(0xFFFFEA00),
    Color(0xFF00E676),
    Color(0xFF2979FF),
    Color(0xFFD500F9),
)

/** 高饱和彩色内容横幅，用于在外壳预览中直观检验液态玻璃透色、折射与边缘高光。 */
@Composable
internal fun GlassColorBackdropBanner(
    variant: Int,
    modifier: Modifier = Modifier,
) {
    val rainbowBrush = remember(variant) {
        val base = listOf(
            Color(0xFFFF2D55),
            Color(0xFFFF9500),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF00C7BE),
            Color(0xFF007AFF),
            Color(0xFFAF52DE),
        )
        val shift = (variant % base.size + base.size) % base.size
        Brush.horizontalGradient(base.drop(shift) + base.take(shift))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HiShapeTokens.Medium)
            .background(rainbowBrush)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.preview_glass_backdrop_title, variant + 1),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassBackdropDotColors.forEach { dot ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(HiShapeTokens.Full)
                            .background(dot),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(HiShapeTokens.Small)
                .background(Color.Black.copy(alpha = 0.28f))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.preview_glass_backdrop_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
    }
}
