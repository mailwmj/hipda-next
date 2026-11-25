package net.jejer.hipda.ui.designsystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.glass.GlassDegradationReason
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberGlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.ShellDestination

/** Debug 预览入口（仅注册在 `src/debug/AndroidManifest.xml`，不进入 release 包）。 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var state by remember { mutableStateOf(ThemeSpecimenState()) }
            var showShellDemo by remember { mutableStateOf(false) }
            HiTheme(
                themeMode = state.themeMode,
                pureBlack = state.pureBlack,
                seed = state.seed,
                fontSizeStep = state.fontSizeStep,
                lineHeightPreset = state.lineHeightPreset,
            ) {
                if (showShellDemo) {
                    ShellPreviewScreen(
                        onBack = { showShellDemo = false },
                    )
                } else {
                    ThemeSpecimenScreen(
                        state = state,
                        onStateChange = { state = it },
                        onOpenShellDemo = { showShellDemo = true },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}

enum class GlassPreviewMode {
    SYSTEM,
    FORCE_BLUR,
    SIMULATE_POWER_SAVE,
    SIMULATE_REDUCE_TRANSPARENCY,
}

@Immutable
data class ThemeSpecimenState(
    val themeMode: HiThemeMode = HiThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val seed: HiThemeSeed = HiColorPresets.Brand.toSeed(),
    val fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
    val lineHeightPreset: HiLineHeightPreset = HiLineHeightPreset.DEFAULT,
    val glassPreviewMode: GlassPreviewMode = GlassPreviewMode.SYSTEM,
)

@Composable
fun ThemeSpecimenScreen(
    state: ThemeSpecimenState,
    onStateChange: (ThemeSpecimenState) -> Unit,
    modifier: Modifier = Modifier,
    onOpenShellDemo: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val isWide = maxWidth >= 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SpecimenHeader(state = state, onOpenShellDemo = onOpenShellDemo)
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ControlsSection(state = state, onStateChange = onStateChange)
                            ColorSwatchesSection()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TypographySection()
                            ButtonsSection()
                            ShapesAndCardsSection()
                            GlassSpecimenSection(state = state, onStateChange = onStateChange)
                        }
                    }
                } else {
                    ControlsSection(state = state, onStateChange = onStateChange)
                    ColorSwatchesSection()
                    TypographySection()
                    ButtonsSection()
                    ShapesAndCardsSection()
                    GlassSpecimenSection(state = state, onStateChange = onStateChange)
                }
            }
        }
    }
}

@Composable
private fun SpecimenHeader(
    state: ThemeSpecimenState,
    onOpenShellDemo: (() -> Unit)? = null,
) {
    val seedLabel = when (val s = state.seed) {
        HiThemeSeed.Wallpaper -> stringResource(R.string.preview_seed_wallpaper)
        is HiThemeSeed.ColorSeed -> HiColorPresets.findById(s.id)?.label ?: s.id
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.preview_launcher_label),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "${stringResource(R.string.preview_subtitle)} · $seedLabel · ${state.fontSizeStep.baseSp.toInt()}sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpenShellDemo != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onOpenShellDemo,
                    shape = HiShapeTokens.Small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    HiCappedFontScale {
                        Text(text = "外壳", style = MaterialTheme.typography.labelMedium)
                    }
                }
                HiCappedFontScale {
                    Surface(
                        shape = HiShapeTokens.Full,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = if (HiTheme.state.pureBlack) "OLED" else if (HiTheme.state.darkTheme) "DARK" else "LIGHT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        } else {
            HiCappedFontScale {
                Surface(
                    shape = HiShapeTokens.Full,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = if (HiTheme.state.pureBlack) "OLED" else if (HiTheme.state.darkTheme) "DARK" else "LIGHT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsSection(
    state: ThemeSpecimenState,
    onStateChange: (ThemeSpecimenState) -> Unit,
) {
    val modes = listOf(
        HiThemeMode.SYSTEM to R.string.preview_mode_system,
        HiThemeMode.LIGHT to R.string.preview_mode_light,
        HiThemeMode.DARK to R.string.preview_mode_dark,
    )
    val lineHeights = listOf(
        HiLineHeightPreset.COMPACT to R.string.preview_line_height_compact,
        HiLineHeightPreset.STANDARD to R.string.preview_line_height_standard,
        HiLineHeightPreset.RELAXED to R.string.preview_line_height_relaxed,
    )
    SpecimenCard(title = stringResource(R.string.preview_section_controls)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { (mode, labelRes) ->
                FilterChip(
                    selected = state.themeMode == mode,
                    onClick = { onStateChange(state.copy(themeMode = mode)) },
                    label = {
                        HiCappedFontScale {
                            Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    shape = HiShapeTokens.Small,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.preview_pure_black), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.pureBlack,
                onCheckedChange = { onStateChange(state.copy(pureBlack = it)) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.preview_seed_label), style = MaterialTheme.typography.labelMedium)
            FilterChip(
                selected = state.seed is HiThemeSeed.Wallpaper,
                onClick = { onStateChange(state.copy(seed = HiThemeSeed.Wallpaper)) },
                label = {
                    HiCappedFontScale {
                        Text(stringResource(R.string.preview_seed_wallpaper), style = MaterialTheme.typography.labelMedium)
                    }
                },
                shape = HiShapeTokens.Small,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HiColorPresets.ALL.forEach { preset ->
                val selected = (state.seed as? HiThemeSeed.ColorSeed)?.id == preset.id
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(HiShapeTokens.Full)
                        .semantics { contentDescription = preset.label }
                        .clickable(role = Role.RadioButton) {
                            onStateChange(state.copy(seed = preset.toSeed()))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(HiShapeTokens.Full)
                            .background(preset.color)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, HiShapeTokens.Full)
                                } else {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, HiShapeTokens.Full)
                                },
                            ),
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HiFontSizeStep.entries.forEach { step ->
                FilterChip(
                    selected = state.fontSizeStep == step,
                    onClick = { onStateChange(state.copy(fontSizeStep = step)) },
                    label = {
                        HiCappedFontScale {
                            Text("${step.baseSp.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    shape = HiShapeTokens.Small,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lineHeights.forEach { (preset, labelRes) ->
                FilterChip(
                    selected = state.lineHeightPreset == preset,
                    onClick = { onStateChange(state.copy(lineHeightPreset = preset)) },
                    label = {
                        HiCappedFontScale {
                            Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    shape = HiShapeTokens.Small,
                )
            }
        }
    }
}

@Composable
private fun ColorSwatchesSection() {
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(
        Triple("Primary", cs.primary, cs.onPrimary),
        Triple("PrimaryC", cs.primaryContainer, cs.onPrimaryContainer),
        Triple("Secondary", cs.secondary, cs.onSecondary),
        Triple("Tertiary", cs.tertiary, cs.onTertiary),
        Triple("Surface", cs.surface, cs.onSurface),
        Triple("SurfLow", cs.surfaceContainerLow, cs.onSurface),
        Triple("SurfCont", cs.surfaceContainer, cs.onSurface),
        Triple("SurfHigh", cs.surfaceContainerHigh, cs.onSurface),
        Triple("Error", cs.error, cs.onError),
    )
    SpecimenCard(title = stringResource(R.string.preview_section_colors)) {
        swatches.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowItems.forEach { (name, bg, fg) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(HiShapeTokens.Small)
                            .background(bg)
                            .border(0.5.dp, cs.outlineVariant, HiShapeTokens.Small)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        HiCappedFontScale {
                            Text(
                                text = name,
                                color = fg,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypographySection() {
    val tokens = HiTheme.typography
    SpecimenCard(title = stringResource(R.string.preview_section_typography)) {
        Text(text = stringResource(R.string.preview_sample_headline), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.preview_sample_thread_unread), style = tokens.threadTitle)
        Text(
            text = stringResource(R.string.preview_sample_thread_read),
            style = tokens.threadTitleRead,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = stringResource(R.string.preview_sample_body), style = tokens.postBody)
        Text(
            text = stringResource(R.string.preview_sample_meta),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonsSection() {
    val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    SpecimenCard(title = stringResource(R.string.preview_section_buttons)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = {}, shape = HiShapeTokens.Small, contentPadding = compactPadding) {
                Text(stringResource(R.string.preview_btn_primary))
            }
            FilledTonalButton(onClick = {}, shape = HiShapeTokens.Small, contentPadding = compactPadding) {
                Text(stringResource(R.string.preview_btn_tonal))
            }
            OutlinedButton(onClick = {}, shape = HiShapeTokens.Small, contentPadding = compactPadding) {
                Text(stringResource(R.string.preview_btn_outlined))
            }
            TextButton(onClick = {}, shape = HiShapeTokens.Small, contentPadding = compactPadding) {
                Text(stringResource(R.string.preview_btn_text))
            }
        }
    }
}

@Composable
private fun ShapesAndCardsSection() {
    val cs = MaterialTheme.colorScheme
    SpecimenCard(title = stringResource(R.string.preview_section_shapes)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                R.string.preview_shape_small to HiShapeTokens.Small,
                R.string.preview_shape_medium to HiShapeTokens.Medium,
                R.string.preview_shape_large to HiShapeTokens.Large,
                R.string.preview_shape_full to HiShapeTokens.Full,
            ).forEach { (labelRes, shape) ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = shape,
                    color = cs.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        HiCappedFontScale {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        HiCappedFontScale {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                shape = HiShapeTokens.Full,
                color = cs.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        R.string.preview_nav_forum,
                        R.string.preview_nav_messages,
                        R.string.preview_nav_search,
                        R.string.preview_nav_mine,
                    ).forEachIndexed { idx, labelRes ->
                        val bg = if (idx == 0) cs.secondaryContainer else Color.Transparent
                        val fg = if (idx == 0) cs.onSecondaryContainer else cs.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .clip(HiShapeTokens.Full)
                                .background(bg)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = fg,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassSpecimenSection(
    state: ThemeSpecimenState,
    onStateChange: (ThemeSpecimenState) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val systemGlassState by rememberGlassEnvironmentState()
    val effectiveGlassState = when (state.glassPreviewMode) {
        GlassPreviewMode.SYSTEM -> systemGlassState
        GlassPreviewMode.FORCE_BLUR -> GlassEnvironmentState.BlurEnabled
        GlassPreviewMode.SIMULATE_POWER_SAVE -> GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE))
        GlassPreviewMode.SIMULATE_REDUCE_TRANSPARENCY -> GlassEnvironmentState(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY))
    }
    val glassModes = listOf(
        GlassPreviewMode.SYSTEM to R.string.preview_glass_mode_system,
        GlassPreviewMode.FORCE_BLUR to R.string.preview_glass_mode_blur,
        GlassPreviewMode.SIMULATE_POWER_SAVE to R.string.preview_glass_mode_power_save,
        GlassPreviewMode.SIMULATE_REDUCE_TRANSPARENCY to R.string.preview_glass_mode_reduce_transparency,
    )
    val glassState = rememberHiGlassState()

    SpecimenCard(title = stringResource(R.string.preview_section_glass)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            glassModes.forEach { (mode, labelRes) ->
                FilterChip(
                    selected = state.glassPreviewMode == mode,
                    onClick = { onStateChange(state.copy(glassPreviewMode = mode)) },
                    label = {
                        HiCappedFontScale {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    shape = HiShapeTokens.Small,
                )
            }
        }
        Text(
            text = stringResource(
                if (effectiveGlassState.blurEnabled) {
                    R.string.preview_glass_status_blur
                } else {
                    R.string.preview_glass_status_degraded
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        ProvideGlassEnvironment(state = effectiveGlassState) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(216.dp)
                    .clip(HiShapeTokens.Medium)
                    .background(cs.background)
                    .border(0.5.dp, cs.outlineVariant, HiShapeTokens.Medium),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .hiGlassSource(glassState)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        Triple(R.string.preview_glass_sample_row_1, cs.primaryContainer, cs.onPrimaryContainer),
                        Triple(R.string.preview_glass_sample_row_2, cs.tertiaryContainer, cs.onTertiaryContainer),
                        Triple(R.string.preview_glass_sample_row_3, cs.secondaryContainer, cs.onSecondaryContainer),
                    ).forEach { (textRes, accentBg, accentFg) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(HiShapeTokens.Small)
                                .background(cs.surfaceContainer)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(HiShapeTokens.Small)
                                    .background(accentBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                HiCappedFontScale {
                                    Text(
                                        text = "Hi",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentFg,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(textRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HiCappedFontScale {
                        HiGlassSurface(
                            state = glassState,
                            shape = HiShapeTokens.Full,
                            floating = true,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.preview_glass_toolbar_reply),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.primary,
                                )
                                Text(
                                    text = stringResource(R.string.preview_glass_toolbar_page),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.preview_glass_toolbar_more),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HiCappedFontScale {
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 420.dp)
                                .heightIn(min = 64.dp),
                            shape = HiShapeTokens.Full,
                            floating = true,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf(
                                    R.string.preview_nav_forum,
                                    R.string.preview_nav_messages,
                                    R.string.preview_nav_search,
                                    R.string.preview_nav_mine,
                                ).forEachIndexed { idx, labelRes ->
                                    val bg = if (idx == 0) cs.secondaryContainer else Color.Transparent
                                    val fg = if (idx == 0) cs.onSecondaryContainer else cs.onSurfaceVariant
                                    Box(
                                        modifier = Modifier
                                            .clip(HiShapeTokens.Full)
                                            .background(bg)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = stringResource(labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = fg,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecimenCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
fun ShellPreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(ShellDestination.Forum) }
    val cs = MaterialTheme.colorScheme
    HiAdaptiveShell(
        selected = selected,
        onSelect = { selected = it },
        modifier = modifier,
    ) { destination ->
        val items = remember(destination) {
            val prefix = when (destination) {
                ShellDestination.Forum -> "Discovery · 帖子示例"
                ShellDestination.Messages -> "消息列表 · 对话与提醒示例"
                ShellDestination.Search -> "搜索结果 · 标题与正文匹配示例"
                ShellDestination.Mine -> "我的空间 · 收藏与历史条目示例"
            }
            List(24) { index -> "$prefix #${index + 1}" }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(destination.labelRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface,
                )
                TextButton(onClick = onBack, shape = HiShapeTokens.Small) {
                    Text(text = "返回样张")
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items) { rowTitle ->
                    val hash = rowTitle.hashCode() and 0x7fffffff
                    val accentBg = when (hash % 3) {
                        0 -> cs.primaryContainer
                        1 -> cs.tertiaryContainer
                        else -> cs.secondaryContainer
                    }
                    val accentFg = when (hash % 3) {
                        0 -> cs.onPrimaryContainer
                        1 -> cs.onTertiaryContainer
                        else -> cs.onSecondaryContainer
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(HiShapeTokens.Medium)
                            .background(cs.surfaceContainerLow)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(HiShapeTokens.Full)
                                .background(accentBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(destination.labelRes).take(1),
                                style = MaterialTheme.typography.labelLarge,
                                color = accentFg,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = rowTitle,
                                style = HiTheme.typography.threadTitle,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "向下滚动可观察底部毛玻璃导航胶囊对内容色彩与层次的实时折射效果。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
