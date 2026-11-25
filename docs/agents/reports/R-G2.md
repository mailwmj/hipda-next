# 独立代码审查报告 R-G2：毛玻璃底层由 Haze 2.1 迁移至 Kyant `backdrop`

- **审查结论**：`APPROVE`
- **审查对象**：`origin/card/G2-kyant-backdrop` @ `74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`（功能提交 `7b7cdd59b108171e1244026744086b38941d67a7`，已快合 `origin/main` @ `bba139cc593db4bee274d251df662fb83a1c6052`）
- **对比基线**：`bba139cc593db4bee274d251df662fb83a1c6052`（`docs/design/product-design.md` §5 v0.4 定稿）
- **CI 验证核实**：
  - 常规 Push CI（`7b7cdd59`）：[Run #45618800336](https://github.com/mailwmj/hipda-next/actions/runs/45618800336) — `Build, Lint & Unit Tests` **SUCCESS**
  - 含模拟器 Release 冒烟 CI（`headSha = 74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`）：[Run #45621266707](https://github.com/mailwmj/hipda-next/actions/runs/45621266707) — `Build, Lint & Unit Tests`（Job `139229787427`）**SUCCESS** + `Emulator Smoke Test (API 36)`（Job `139236189683`）**SUCCESS**
- **阻断项**：**0 项**
- **建议项**：**3 项**（见第 6 节）

---

## 1. 依赖、R8、Release 冒烟与 Apache-2.0 许可核查

1. **依赖切换与零 Haze 残留**：
   - `gradle/libs.versions.toml:17, 103`：引入 `backdrop = "2.0.1"` 与 `io.github.kyant0:backdrop`，彻底移除 `haze = "2.1.0"` 及 `haze`、`haze-blur`、`haze-blur-materials`。
   - `hipda/build.gradle.kts:156`：替换为 `implementation(libs.backdrop)`，删除全部 `libs.haze*`。
   - 构件核查：`backdrop-android-2.0.1.aar`（传递 `shapes-android-1.2.1.aar`）`minSdk = 21`，与项目 `compose-bom = 2026.11.01`、`kotlin = 2.4.21` 零冲突。
   - 全仓残留扫描：`git grep -in "haze" 74c1c8ceac2bcc106bc5589f3cedf3a73b985b08 -- gradle/ hipda/ core-parser/ build.gradle.kts settings.gradle.kts gradle.properties` 命中 **0 处**（仅 `docs/` 历史卡片与方案文档作为背景提及）。
2. **`proguard-rules.txt` 审查**：
   - `hipda/proguard-rules.txt` **零改动**，未引入过宽 `-keep` 规则。
   - 经 `javap` 反编译实查 `backdrop-android-2.0.1.aar` 与 `shapes-android-1.2.1.aar` 全部类字节码（`DrawBackdropElement`、`DrawBackdropNode`、`LayerBackdropNode`、`LensKt`、`BlurKt`、`ColorFilterKt` 等），均为标准 Compose `ModifierNodeElement` / `Modifier.Node` / `RenderEffect` / `RuntimeShader` 静态调用，无反射实例化或序列化，无需额外 ProGuard 规则（`G2.md:10` 明确“不需要则不改”）。
3. **Release 模拟器冒烟核实**：
   - 实查 `Run #45621266707` Job `139236189683` 原始日志：下载 `release-apk`（`hipda-ng-release.apk`，6,247,424 bytes，`sha256:cc503fef...`），对齐签名后在 API 36 模拟器分别安装并冷启动 `net.jejer.hipda.next/net.jejer.hipda.ui.MainFrameActivity`：
     - `[debug]`：`Smoke check passed [debug]: PID=2237, no app FATAL EXCEPTION or ANR in logcat.`
     - `[release]`：`Smoke check passed [release]: PID=3770, no app FATAL EXCEPTION or ANR in logcat.`
   - 注：M7 切换主入口前 `MainFrameActivity` 仍为旧壳入口，本次冒烟验证了 APK 打包、R8 full mode 与 Application/Hilt 注入图无崩溃；`HiGlassSurface` 真实渲染已由 Robolectric API 35 原生图形栈实测覆盖，待后续主入口切至 Compose 外壳后模拟器冒烟将自动覆盖实时玻璃树。
4. **Apache-2.0 第三方许可声明**：
   - `hipda/src/main/assets/license.txt:30-34` 已按项目既有格式追加 `Backdrop (AndroidLiquidGlass)`、`Shapes`、`kudo-android` 三条 Apache License 2.0 声明与仓库地址。

---

## 2. 四路分级降级与防 Native Crash 逐行审查

1. **四路分级隔离与严禁 API 31–32 调 `lens()`**：
   - `GlassEnvironment.kt:114-122`（`GlassEnvironmentSignals.toEnvironmentState()`）：
     - `sdkInt < 31` → `UNSUPPORTED_API_LEVEL`；`isPowerSaveMode` → `POWER_SAVE_MODE`；`isReduceTransparencyEnabled` → `REDUCE_TRANSPARENCY`；`isHighContrastTextEnabled` → `HIGH_CONTRAST_TEXT`；
     - `refractionSupported = sdkInt >= 33`（`TIRAMISU`）。
   - `GlassEnvironment.kt:82-90`（`GlassEnvironmentState.renderTier`）：
     - `reasons.isNotEmpty()` → `SOLID_DEGRADED`（任意 API 下省电/降低透明度/高对比度或 API ≤ 30 均强制降级）；
     - `reasons.isEmpty() && refractionSupported`（API 33+）→ `REFRACTION_AND_BLUR`；
     - `reasons.isEmpty() && !refractionSupported`（API 31–32）→ `BLUR_ONLY`。
   - **三重防崩溃守卫**：
     1. `HiGlassSurface.kt:373-376, 400-410`：`BLUR_ONLY` 档位下 `refractionHeight = 0.dp`、`refractionAmount = 0.dp`、`refractionEnabled = false`，因此 `:497` 的 `if (backdropConfig.refractionEnabled && canApplyLens)` 在 API 31–32 下恒为 `false`，绝不调用 `lens()`；
     2. `backdrop` 字节码核查：`LensKt.lens` 偏移 `6..12` 首句检查 `PlatformKt.isRuntimeShaderSupported()`（`SDK_INT >= 33`），低于 33 直接返回；
     3. 形状与异常守卫：`LensKt.getCornerRadii` 支持 `RoundedRectangularShape`、`AbsoluteRoundedCornerShape`、`CornerBasedShape`，对 `RectangleShape` 抛 `UnsupportedOperationException`；`HiGlassSurface.kt:427-431` 将 `RectangleShape` 映射为 `RoundedCornerShape(0.dp)` 并以 `shape is CornerBasedShape` 守卫，同时 `:498-508` 捕获 `IllegalArgumentException`（兼容不支持 AGSL `content.eval` 的 SkSL/定制渲染器回退至纯模糊）。
2. **降级态卸载 `drawBackdrop` 并释放离屏录制**：
   - `SOLID_DEGRADED` 下 `useBackdrop == false`（`HiGlassSurface.kt:470`），`:518` 直接使用 `Modifier.background(backdropConfig.fallbackColor)`，不挂载 `Modifier.drawBackdrop`（动态降级时 `DrawBackdropNode.onDetach()` 调用 `releaseGraphicsLayer` 释放图层）。
   - `DisposableEffect(state, useBackdrop)`（`:472-479`）仅在 `useBackdrop == true` 时持有 `acquireSourceDemand()`；降级态下 `state.hasSourceDemand == false`，`Modifier.hiGlassSource`（`:216-227`）不挂载 `layerBackdrop(backdrop)`，`LayerBackdropNode.onDetach()` 清空 `layerCoordinates = null`，停止每帧 `recordLayer` 离屏拷贝。
3. **防循环采样 `SIGSEGV`（严格兄弟拓扑）**：
   - 逐一核查全部挂载点：
     - `HiAdaptiveShell.kt:249-343`：`ContentContainer`（`:258` `.hiGlassSource(glassState)`）与底部胶囊容器 `Box -> HiNavigationPill -> HiGlassSurface`（`:322-342`）为外层 `Box` 下的严格同级兄弟节点；侧栏态（`isSidebar == true`）下两者均不挂载。
     - `PreviewActivity.kt:646-784`、`HiGlassScreenshotTest.kt:241-427`、`HiGlassSurfaceTest.kt:531-545, 588-613`：内容源容器与浮层 `HiGlassSurface` 均为严格兄弟节点，无任何父子嵌套采样。

---

## 3. 对外 API 兼容、令牌设计与性能审查

1. **对外 API 签名兼容**：
   - `HiGlassState`、`rememberHiGlassState()`、`Modifier.hiGlassSource(state, zIndex, key)`、`HiGlassSurface(...)`、`LocalHiGlassStyle`、`HiTheme.glass`、`GlassEnvironment`、`GlassEnvironmentState`（保留 `BlurEnabled` 与单参默认构造）、`resolveHiGlassStyleSpec`（保留 `blurEnabled: Boolean` 重载）全部向后兼容；`ui/shell/` 与 `HiTheme.kt` 零改动编译通过。
   - 按 `G2.md:61` 清理了 `backdrop 2.0.1` 不支持的 `NoiseFactor`/`noiseFactor` 死参数。
2. **区域预算与令牌**：
   - `HiGlassTokens.MaxGlassSurfacesOnScreen == 3`（`HiGlassSurface.kt:92`），并按 `product-design.md` §5（`:241, :244`）提供 `HiGlassRegion`（`:158-174`），使共享同一采样源的多胶囊复合控件（如后续三段式工具条）外层仅计 1 个 `HiGlassMarker`，子表面仍各自暴露 `HiGlassBlurActive`。
   - 浅色激活态双层 `.border` 顺序（`HiGlassSurface.kt:531-536`）：`highlightModifier.border(DegradedBorderWidth, borderOutlineColor, shape)`，因链上前序 `border` 在 `drawContent()` 之后后绘，确保 `0.5dp` 暗轮廓垫底、`0.75dp` 白渐变高光居上，准确闭合 R-G1 遗留项。
   - `highlightBrush`（`HiGlassSurface.kt:524`）与 `GlassColorBackdropBanner` 的 `rainbowBrush`（`GlassTuningPanel.kt:337`）均已用 `remember` 缓存。
   - 零手写 `Layout` / `Modifier.Node`，`src/main/` 无测试专用钩子或 `src/debug/` 引用。
3. **滚动期与重组期性能字节码核查**：
   - **滚动期零分配**：实查 `DrawBackdropNode` 与 `BackdropEffectScopeImpl` 字节码，列表滚动仅触发 `LayerBackdropNode.draw()` 与 `DrawBackdropNode.draw()`（复用已有 `GraphicsLayer` 与缓存的 `RenderEffect`），且 `HiAdaptiveShell.kt:333` 通过 `graphicsLayer { translationY = ... }` 在绘制期隐藏胶囊，滚动全程 `HiGlassSurface` **0 次重组、`effects` 0 次执行**。
   - **重组期小优化空间（见建议项 S1）**：`Modifier.drawBackdrop(...)`（`HiGlassSurface.kt:484-516`）内部每次调用都会新建未重写 `equals` 的 `ShapeProvider` 实例；当父组件因切 Tab 发生重组时，会导致 `DrawBackdropElement.update -> invalidateDrawCache()` 重新求值一次 `effects`。建议用 `remember(layerBackdrop, backdropShape, backdropConfig)` 缓存该修饰符。

---

## 4. Roborazzi 基线诚实性与测试质量核查

1. **Roborazzi 渲染评估核实**：
   - 直接目视检视新增基线 `glass_specimen_{light,dark}_api33_refraction.png`（`@Config(sdk = [35])`）并与 `glass_specimen_{light,dark}_api31_blur.png`（`@Config(sdk = [31])`）比对：API 35 截图在工具条与底部胶囊圆弧边缘清晰呈现出底层文字与卡片的真实 AGSL `lens()` 光学折射弯曲，API 31 截图则为干净平滑的高斯模糊无边缘折射，`_reports/G2.md` 第 1.2–1.3 节关于 API 34 SkSL 限制与 API 35 原生 AGSL 支持的报告完全属实。
2. **单测锁死参数与边界**：
   - `GlassEnvironmentTest`（16 项）完整覆盖 API 33+、API 31–32、API ≤ 30、省电、降低透明度、高对比度及多原因叠加恢复。
   - `HiGlassSurfaceTest`（11 项）通过 `toBackdropConfig_coversAllRenderTiersAndRefractionTokens` 精确锁死三档 `renderTier` 的全部字段（改坏 API 31–32 的 `refractionEnabled` 或 `blurRadius` 单测必红），覆盖同屏 3 过 / 4 败、`HiGlassRegion` 合并计数、全 20 预设 × 浅/深/纯黑降级 WCAG ≥ 4.5:1、降级态卸载 `LayerBackdropNode`（反射断言 `getLayerCoordinates() == null`）、API 35 胶囊与 `RectangleShape` 真折射无崩溃，以及 `mainSources_doNotReferenceDebugGlassTuningOrPreviewSymbols`。
   - `GlassTuningPanelTest`（3 项）覆盖 `Locale.FRANCE` 下单行稳定格式、真实触摸拖拽滑杆 + 3 个新 `Switch` 开关、剪贴板复制与恢复默认。
3. **快照基线变更范围核实**：
   - `git diff --name-status bba139c...74c1c8ceac2bcc106bc5589f3cedf3a73b985b08 -- hipda/src/test/snapshots/` 仅含 24 张受影响图像：4 张 `glass_specimen_*`（2 新增 + 2 更新，6 张降级态零改动）、8 张带底部胶囊的 `shell_*`（13 张侧栏态/隐藏态 `shell_*` 零改动）、12 张含 `GlassSpecimenSection` 的 `theme_specimen_*`；`challenge_*`、`feedback_*`、`sample_export_*`、`state_*` 全部 **0 改动**。

---

## 5. 跨波零冲突核查

核对 `git diff --name-status bba139c...74c1c8ceac2bcc106bc5589f3cedf3a73b985b08` 全部 37 个文件：
- `core-parser/`：**0 改动**
- `hipda/src/main/java/net/jejer/hipda/{data,di,feature,ui/shell}/`：**0 改动**
- `HiApplication`、`hipda/src/main/AndroidManifest.xml`、`hipda/proguard-rules.txt`、`.github/`：**0 改动**
- `MotionTokenScanTest.kt` 白名单与 `hipda/src/test/resources/samples/`：**0 改动**

---

## 6. 建议项（非阻断，供后续 `C4d` 与真机校准顺手采纳）

1. **S1（缓存 `Modifier.drawBackdrop` 避免切 Tab 重组时重建 `RenderEffect`）**：`HiGlassSurface.kt:484-516` 调用的 `Modifier.drawBackdrop` 内部每次都会 `new ShapeProvider`（未重写 `equals`），父级切 Tab 重组时会触发 `DrawBackdropElement.update -> invalidateDrawCache()`；建议在 `C4d` 中用 `remember(layerBackdrop, backdropShape, backdropConfig)` 缓存该 `Modifier`（滚动期本就 0 重组，不受影响）。
2. **S2（对齐 `main @ bba139c` 设计稿 §5 的 `Toolbar` 浅色着色区间与激活态对比度单测 (b)）**：`HiGlassTokens.ToolbarLightBlurTintAlpha`（`HiGlassSurface.kt:63`）沿用了 G1 的 `0.60f`，而合入的 `product-design.md` §5（`:267, :271-274`）已将 `Toolbar` 浅色着色定为 `0.62–0.72` 并新增“CI 单测 (b) — 模糊激活态标准主题背景对比度 `≥ 4.5:1` 及叠加层峰值 alpha 上限”；建议在 `C4d` 补齐 `TopBar`/`Sheet` 档位时一并上调至 `≥ 0.62f` 并补齐单测 (b)。
3. **S3（真机 120Hz 校准时评估双层 `ColorFilterEffect` 合并）**：`HiGlassSurface.kt:488-493` 在 `vibrancyEnabled = true` 且 `saturation != 1f` 时会串联 `VibrantColorFilter` 与 `colorControls` 两层 `ColorFilterEffect`；建议在真机校准观感与 120Hz 帧率时评估是否按档位合并为单层滤镜，并在 M7 主入口切换至 Compose 外壳后复核模拟器冒烟对实时玻璃树的覆盖。
