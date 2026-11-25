# R-G1 独立代码审查报告：毛玻璃观感调参 + debug 实时调参面板

- **审查结论**：`APPROVE`
- **审查对象**：`origin/card/G1-glass-tuning` @ `8352ae114f672d2c5bed75171cbed6842d39eccf`（基于 `b4ab9160f2afccb63bd9d0601ac86d251ec78a19`）
- **任务卡**：`/home/mir/Documents/hipda-next.wt/_cards/G1.md`
- **参考调研与交付报告**：`/home/mir/Documents/hipda-next.wt/_reports/G-glass.md`、`/home/mir/Documents/hipda-next.wt/_reports/G1.md`
- **CI 证据**：[Android CI #45599272318](https://github.com/mailwmj/hipda-next/actions/runs/45599272318) — `Build, Lint & Unit Tests` **SUCCESS**（7m44s，覆盖 `assembleDebug`、`assembleRelease`、`lintDebug`、`testDebugUnitTest`、`:core-parser:test` 及全量 Roborazzi `verify`）
- **变更规模**：41 个文件（9 个源码/资源/测试文件 + 32 张受影响快照基线），`+1226 / -153` 行；其中生产代码仅 3 个文件（`HiGlassSurface.kt` `+226/-39`、`HiTheme.kt` `+9`、`ShellIndicatorLayout.kt` `+13/-3`），共享配置文件零改动。

---

## 一、阻断项（Blocking Issues）

**无**（对照 `docs/agents/DISPATCH.md` §4，无编译/测试回归、无崩溃或状态丢失、无敏感区改动、无违规构建或写操作）。

---

## 二、逐项重点核查

### 1. 主题 API 设计、Haze 2.1.0 调用链路与降级安全性

1. **`HiGlassStyle` / `LocalHiGlassStyle` 主题 API**：
   - `HiGlassSurface.kt:89-118` 定义 `@Immutable data class HiGlassStyle`（内置 `HiGlassStyle.Navigation` 默认档与 `HiGlassStyle.Toolbar` 预留工具条档）及 `LocalHiGlassStyle = compositionLocalOf { HiGlassStyle.Navigation }`；各项默认令牌（导航 `10.dp`、着色 `0.45f/0.46f`、饱和度 `1.18f`、噪点 `0.04f`、描边 `0.75.dp`、指示器 `0.12f`、阴影 `0.07f/0.18f`；工具条 `16.dp`、`0.60f/0.68f`、`1.08f`）与 `G-glass.md` 推荐清单完全一致。
   - `HiTheme.kt:81-84, 114, 159` 由 `HiTheme` 注入 `LocalHiGlassStyle` 默认值并通过只读 `@ReadOnlyComposable val HiTheme.glass` 暴露；生产代码（`HiGlassSurface.kt:303`、`ShellIndicatorLayout.kt:76`）仅作只读消费，无测试专用钩子。
   - 源码隔离核查：`git diff` 与 `HiGlassSurfaceTest.kt:295-336` 静态扫描均确认 `src/main` 对 `src/debug` 调参面板及预览符号零引用。

2. **Haze 2.1.0 API 与效果链顺序（结合字节码实测）**：
   - `HiGlassSurface.kt:263-281`（`HiGlassStyleSpec.toHazeConfig()`）在 `saturation != 1f` 时按 `[HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })), HazeColorEffect.tint(activeTintColor)]` 顺序传入 `colorEffects`；当 `saturation == 1f` 时省略 `colorFilter`，自动保留 Haze 2.1.0 在 Android 13+ 下的 `COMBINED_NOISE_TINT_SKSL` 单 pass 合并渲染路径。
   - 反编译核对 `haze-blur-2.1.0` `BlurRenderEffectKt.createRenderEffect` 字节码确认底层执行顺序为：`backgroundColor` 垫底 → 高斯模糊 → `SoftLight` 噪点（`0.04f`）→ `withTints` 顺序串联 `colorFilter(saturation)` 提鲜 → `tint(activeTintColor)` 罩中性色（浅色 `surfaceContainerLowest`、深色/纯黑 OLED `surfaceContainer`，规避了纯黑 `#000000` 死黑发闷问题）。
   - 激活态渐变描边（`HiGlassSurface.kt:332-346`）采用标准 `Modifier.border` + `Brush.linearGradient(..., start = Offset.Zero, end = Offset.Infinite)`（左上→右下三停点：亮 `0.85→0.28→0.45`、暗 `0.24→0.05→0.12`），零手写 `Path` 或 `Modifier.Node`。
   - *次要细节*：`HiGlassSurface.kt:340-345` 浅色模式下 `outlineModifier.border(styleSpec.borderWidth, highlightBrush, shape)` 中外层 `BorderModifierNode` 在 `drawContent()` 之后绘制描边，使 `0.5dp` `Black @ 0.06f` 实际叠在 `0.75dp` 白色渐变高光之上（仅产生约 6% 轻微压暗，视觉无碍，但调换两个 `.border(...)` 的链式顺序让暗轮廓垫底、白高光居上更契合光学层级）。

3. **API 31 以下及系统降级态安全与对比度**：
   - 在 Android 11（API 30）及以下、省电模式、降低透明度、高对比度文字/色彩下，`GlassEnvironmentSignals.toEnvironmentState()`（`GlassEnvironment.kt:79-86`）返回 `blurEnabled = false`；`HiGlassSurface.kt:323-329` 直接走 `Modifier.background(styleSpec.degradedSurfaceColor)`（`surface @ 0.94f`）+ `0.5dp` `outlineVariant` 描边，完全不挂载 `Modifier.hazeBlur`，使 `HazeSourceNode` 释放离屏图层（由 `HiGlassSurfaceTest.kt:437-477` 验证）。即使在 debug 页开启 `FORCE_BLUR` 运行于 API 30，`ColorMatrix`/`ColorFilter` 亦为纯 Compose 数组封装、无 API 31+ 类加载风险，Haze 自动回退 `fallbackColorEffect`（`degradedSurfaceColor`）。
   - **选中指示器对比度复算**：按 `HiColors.kt` OKLCH 色阶算法逐一复算全 20 个预设主题色 × `{light, dark, pureBlack}`：
     - 未选中项 `onSurfaceVariant` 与工具条 `onSurface`/`primary` 直接叠在 `degradedSurfaceColor` 上，全组合对比度均 ≥ `4.5:1`（由 `HiGlassSurfaceTest.kt:396-434` 覆盖）。
     - 胶囊选中项实际在玻璃表面上再叠了一层 `primary @ 0.12f` 指示器底色（`ShellIndicatorLayout.kt:76`）：在常规 `background` 背景下全 20 色 × 三种模式对比度均 ≥ `5.14:1`；仅在**浅色降级态 + 纯黑 `#000000` 极端背景**下，`red`（`4.44:1`）、`pink`（`4.44:1`）、`deep_orange`（`4.49:1`）因 12% 主题色淡底略降背景亮度而比 `4.50:1` 低 `0.01–0.06`（`HiGlassSurfaceTest.kt:424-430` 未把 `primary @ 0.12f` 纳入合成层计算）。实际阅读场景无感知风险，已列入建议项。

---

### 2. 性能与重组隔离

1. **对象缓存（`remember`）**：
   - `HiGlassSurface.kt:313-339` 中 `styleSpec`、`hazeStyle`（含 `ColorMatrix`、`ColorFilter`、`HazeColorEffect` 列表）、`hazeInput` 以及 `AllSides` 胶囊的 `highlightBrush` 均正确使用 `remember` 缓存；底部胶囊随列表滚动通过 `graphicsLayer { translationY = ... }` 平移时不会每帧重建样式或画刷对象。
   - **发现 1 处可优化项**：`HiGlassSurface.kt:350-364` 的 `HiGlassBorderMode.EndEdgeOnly` 分支在 `Modifier.drawWithContent` 绘制回调内直接调用 `Brush.verticalGradient(styleSpec.borderHighlightColors)`。因每次新建 `ShaderBrush` 实例会丢失其内部 `internalShader` 缓存，每次重绘该节点都会分配新的 `VerticalGradient` 与原生 `LinearGradient` Shader。目前仅大屏 `HiNavigationRail` 走该分支（且 C4c 即将替换为纯色侧栏），不触发滚动逐帧重绘，建议顺手提到组合期 `remember(styleSpec.borderHighlightColors)`（同理 `src/debug` 中 `GlassTuningPanel.kt:288` 的 `Brush.horizontalGradient(rainbowColors)` 也可 `remember`）。

2. **滑杆调参的重组范围**：
   - `LocalHiGlassStyle` 使用动态 `compositionLocalOf`，值变更时仅通知读取了 `LocalHiGlassStyle.current` / `HiTheme.glass` 的组合作用域，不会穿透整个 `MaterialTheme` 子树。
   - 生产环境中 `HiTheme` 默认传入单例 `HiGlassStyle.Navigation`，无任何可变状态源；可变 `glassStyle` 状态仅存在于 `src/debug` 的 `PreviewRootContent`（`PreviewActivity.kt:94`）与 `ShellPreviewScreen`（`PreviewActivity.kt:803`），拖动滑杆仅在 debug 预览页触发局部重组，对正式包零影响。

---

### 3. `ShellIndicatorLayout.kt` 改动范围（C4c 并行兼容性）

- 核对 `git diff b4ab916..8352ae1 -- hipda/src/main/java/net/jejer/hipda/ui/shell/ShellIndicatorLayout.kt`：仅改动 `+13 / -3` 行（`L73-77`、`L85`、`L194-203`）。
- 仅将非 `Rail` 模式（`BottomPill` / `CompactPill`）的指示器底色改为 `primary.copy(alpha = HiTheme.glass.indicatorAlpha.coerceIn(0f, 1f))`，选中项图标与文字改为 `primary`。
- 单遍测量布局、`fitAxis`、`ShellIndicatorAnimator` 零改动；`ShellNavigationMode.Rail` 分支原封不动保留 `secondaryContainer` 指示器与 `onSecondaryContainer` / `onSurface` 前景色，不干扰并行中的 C4c。

---

### 4. 截图基线范围与视觉检视

1. **改动范围精准**：
   - 全仓共 64 张 Roborazzi 快照，本次仅更新受影响的 **32 张**（`shell_*` 12 张、`glass_specimen_*` 8 张、`theme_specimen_*` 12 张）；其余 **32 张**（`challenge_*`、`feedback_*`、`sample_export_*`、`state_*`、`shell_scroll_w411_h891_hidden_light.png`）零改动。
2. **新基线图目视核查**（已逐一查看 `shell_w411_h891_light/dark.png`、`shell_w891_h411_compact_light.png`、`shell_w1280_h800_light/dark.png`、`glass_specimen_light/dark_api31_blur.png`、`glass_specimen_light/dark_api30_degraded.png`、`theme_specimen_w411_light/pure_black_step3.png`）：
   - **浅色胶囊**（`shell_w411_h891_light.png`、`shell_w891_h411_compact_light.png`）：原先 70% 近白厚遮罩与左侧 100% 实心淡蓝药丸的割裂感已消除，下方浅蓝/浅紫卡片色块柔和透出且不发灰；`12%` 半透明选中底色搭配深青蓝 `primary` 图标文字与深灰 `onSurfaceVariant` 未选中项，对比鲜明、字迹清晰，无过透重影；左上迎光高光与微暗轮廓线使胶囊在浅色底上边界清楚、阴影轻盈。
   - **深色与纯黑 OLED**（`shell_w411_h891_dark.png`、`theme_specimen_w411_pure_black_step3.png`）：玻璃基底以 `surfaceContainer` 适度提亮，告别了旧版纯黑死闷与均匀白铁丝框，左上→右下渐变高光自然内敛，前景文字与图标干净醒目。
   - **工具条档与降级态**（`glass_specimen_*`）：API 31 样张中上方工具条档（`16dp`、`0.60/0.68`）比下方导航档（`10dp`、`0.45/0.46`）遮蔽更稳重，「回复 · 第 2 / 8 页 · 更多」压在楼层文字上方依然清晰易读；API 30 降级态 94% 纯色表面与细描边干净稳妥。

---

### 5. Debug 实时调参面板核查

- 代码完全收敛在 `src/debug/`（`GlassTuningPanel.kt` 335 行、`PreviewActivity.kt`、`strings_preview.xml`），体量克制，未引入任何自定义布局、手写绘制或冗余状态机。
- 全部采用标准 Material 3 `Card`、`FilterChip`、`Slider`、`OutlinedButton`、`FilledTonalButton`，开合动画使用设计系统令牌（`hiExpandVertically + hiFadeIn` / `hiShrinkVertically + hiFadeOut`，已通过 `MotionTokenScanTest`）。
- 完整覆盖模糊半径（`0–24dp`）、亮/暗着色透明度（`0–1`）、饱和度（`1.0–1.4`）、噪点（`0–0.1`）、描边高光强度（`0–2x`）、指示器透明度（`0–0.6`）7 根滑杆 + 明暗/OLED/档位快捷切换 + 恢复默认 + `Locale.US` 稳定单行复制参数 + 高饱和七色彩带与文字混合背景；`GlassTuningPanelTest.kt`（3 个 Robolectric 用例）覆盖了真实滑杆拖拽、其余滑杆语义调参、剪贴板单行复制与恢复默认全链路。

---

## 三、非阻断建议项（Suggestions）

1. **`EndEdgeOnly` 绘制期 `Brush` 缓存**（`HiGlassSurface.kt:356`）：`Brush.verticalGradient(styleSpec.borderHighlightColors)` 目前在 `drawWithContent` 内每帧新建，无法复用 `ShaderBrush` 内部的原生 `Shader` 缓存；建议移至组合期 `remember(styleSpec.borderHighlightColors)`（`src/debug` 的 `GlassTuningPanel.kt:288` `Brush.horizontalGradient` 同理可顺手 `remember`）。
2. **浅色双层 `Modifier.border` 叠放顺序**（`HiGlassSurface.kt:340-345`）：Compose `BorderModifierNode` 在 `drawContent()` 后画边，当前 `outlineModifier.border(styleSpec.borderWidth, highlightBrush, shape)` 会使外层 `0.5dp` `Black @ 0.06f` 叠在内层 `0.75dp` 白色渐变高光之上；对调两层 `.border(...)` 顺序可让暗色轮廓线垫底、白色迎光高光完整呈现在最上层。
3. **降级态半透明指示器叠层 WCAG 单测补强**（`HiGlassSurfaceTest.kt:424-430`、`ShellIndicatorLayout.kt:76`）：单测校验了 `primary` 直接在 `degradedSurfaceColor` 上的对比度，若再叠入 `primary @ 0.12f` 指示器底色并在浅色模式取极端 `#000000` 背景，`red`/`pink`/`deep_orange` 三个预设约为 `4.44–4.49:1`（常规背景均 `≥ 5.14:1`）；后续可将合成层纳入单测并将 `IndicatorAlpha` 微调至 `0.10f`（或降级态略减指示器透明度）以在极端黑底下也严格 `≥ 4.5:1`。
4. **真机定参后同步设计文档**（`docs/design/product-design.md:228`）：`product-design.md` §5 仍保留旧版 24dp / 70% / 60% 描述，待真机通过调参面板拍板最终数值后同步回写文档。
