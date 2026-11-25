# 独立验证报告 V-G2：Kyant `backdrop` 迁移视觉、四路降级与性能探针验证

- **总判定**：`PASS`（无阻断项）
- **被测对象**：`origin/card/G2-kyant-backdrop` @ `74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`
- **验证工作区与分支**：`/home/mir/Documents/hipda-next.wt/V-G2`，分支 `verify/G2-probes`，探针提交 `c5ea852c837f221b3fcf5fa6d3a1094191f4dafd`
- **GitHub Actions CI**：[Android CI #45628311985](https://github.com/mailwmj/hipda-next/actions/runs/45628311985)（`Build, Lint & Unit Tests` **SUCCESS**，总计 `672 tests, 672 passed, 0 failed, 0 errors, 0 skipped`；其中 `:hipda:testDebugUnitTest` `603/603`、`G2ProbeTest` `12/12`、`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验与 Roborazzi 截图验证全绿）
- **独立性声明**：本次验证全程未先阅读实现者的测试代码与 `_reports/G2.md`，全部结论来自对生产/`src/debug` 代码、39 张快照图片像素与视觉读图、以及自编探针 [G2ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt) 的实测输出。

---

## 一、视觉基线逐张核查

### 1. 基线变更范围核实（零越界修改）
通过 `git diff --name-status origin/main...74c1c8ceac2bcc106bc5589f3cedf3a73b985b08 -- hipda/src/test/snapshots/` 核对，全仓仅 `24` 张快照产生变更/新增，其余基线全部零改动：
- **完全未改动**：`challenge_*`（7 张）、`feedback_*`（2 张）、`sample_export_*`（2 张）、`state_*`（20 张）、`glass_specimen_*_degraded.png`（6 张）、`shell_scroll_w411_h891_hidden_light.png`、以及所有 `≥ 600dp` 侧栏/三栏 `shell_*`（`w640_h1024_*`、`w749_h559_*`、`w840_h900_*`、`w933_h696_*`、`w1280_h800_*`、`shell_scroll_w749_h559_sidebar_bottom_light.png`）。
- **变更与新增（24 张）**：`glass_specimen_*` 激活态 4 张（新增 `light/dark_api33_refraction.png` 2 张、更新 `light/dark_api31_blur.png` 2 张）、窄屏/胶囊态 `shell_*` 8 张、`theme_specimen_*` 12 张。

### 2. `glass_specimen_*`（10 张：亮/暗 × 激活态与降级态）
- **激活态（`glass_specimen_{light,dark}_api33_refraction.png`、`glass_specimen_{light,dark}_api31_blur.png`）**：
  - 浅色激活态呈现通透的 `surfaceContainerLowest @ 45%/60%` 磨砂效果，背后“第 8 楼”深色卡片文字与紫/蓝底块被平滑模糊，未直接透出锐利干扰字迹；双层高光描边由 `0.5dp` 黑色 6% 轮廓垫底、`0.75dp` 白渐变高光（`0.85 → 0.28 → 0.45`）居上，边缘洁净无脏灰、无发糊。
  - 深色激活态以 `surfaceContainer @ 46%/68%` 承载模糊背透（紫/青卡片色彩柔化透出），渐变白高光（`0.24 → 0.05 → 0.12`）与选中态 `primary @ 12%` 胶囊指示器层次分明，前景文字（`回复` / `第 2 / 8 页` / `更多` / `论坛`）清晰可读。
  - **Roborazzi 渲染实测表现**：在 Robolectric + Roborazzi 环境下，`backdrop` 的 `drawBackdrop` 激活态**并非静态纯色回退**，而是真正对 `layerBackdrop` 采样内容执行了背景模糊与着色叠加；对比 `api33_refraction` 与 `api31_blur` 的胶囊区域像素差异（RGB），浅色最大通道差 `23`（`px>2 = 19,967`）、深色最大通道差 `29`（`px>2 = 53,127`），但边缘透镜几何畸变在软件/Robolectric 管线下视觉差异细微，真实 AGSL `lens()` 光学折射弯曲仍需在真机硬件 GPU 下目视确认。
- **降级态（`api30_degraded`、`power_save_degraded`、`reduce_transparency_degraded` 亮/暗共 6 张）**：
  - 全部呈现 94% 不透明 `surface` 纯色底 + `0.5dp outlineVariant` 细边框，底部“第 8 楼”内容仅以极弱 ~6% 透色隐约可见，完全不干扰工具条与导航胶囊前景可读性与对比度。

### 3. `shell_*`（核查 `411×891`、`559×749`、`891×411`、`340×800`、`749×559`、`933×696`、`1280×800` 共 17 张）
- **胶囊态（`< 600dp` 及矮屏 `891×411`）**：
  - `shell_w411_h891_light/dark.png`、`shell_w411_h891_step5_font130_light.png`、`shell_scroll_w411_h891_bottom_light.png`、`shell_w559_h749_light/dark.png`、`shell_w891_h411_compact_light.png`、`shell_w340_h800_narrow_light.png`：底部悬浮胶囊清晰透出后方第 6 条蓝紫徽章与列表条带模糊光影，边界高光连续无裁切；`340×800` 极窄窗与 `5档×1.3` 大字号下均无文字截断或溢出；`891×411` 横屏紧凑胶囊（`48dp` 高）居中稳固；选中态半透明 `primary @ 12%` 药丸指示器与亮/暗背景对比明确。
- **侧栏与三栏态（`≥ 600dp`：`749×559`、`933×696`、`1280×800`）**：
  - 左侧栏保持纯色 `surfaceContainerLow` + `1dp outlineVariant` 右缘细线，零毛玻璃；抽屉模态层（`shell_w749_h559_drawer_light.png`）、收起态（`shell_w933_h696_collapsed_light.png`）与三栏宽屏（`1280×800` 亮/暗）布局零回归。

### 4. `theme_specimen_*`（12 张：`w411/w900` × 亮/暗/纯黑 × `step3/step5`）
- **纯黑 OLED（`theme_specimen_{w411,w900}_pure_black_step{3,5}.png`）**：`Surface` 色块为纯黑 `#000000`，而“毛玻璃表面”区域内的两块胶囊采用 `surfaceContainer` 提亮基底并叠加模糊背透与白渐变描边，**未发死黑**，边界与文字辨识度良好。
- 色彩令牌、字体排版、按钮与圆角区域保持完全一致，无非预期变化。

---

## 二、五大探针验证结果（[G2ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt)）

| 探针 | 用例名（`G2ProbeTest.kt` 行号） | 验证核心 | 结论 |
|---|---|---|---|
| **a. 四路降级矩阵动态切换** | `probeA_fourTierDegradationMatrix_switchesDynamicallyAndReleasesOffscreenSampling` ([L107](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L107))<br>`probeA_realApi32Environment_selectsBlurOnlyAndDisablesLens` ([L282](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L282))<br>`probeA_realApi30Environment_selectsSolidDegraded` ([L293](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L293)) | 单树顺序驱动 `API 34 正常` → `API 32 正常` → `API 30` → `API 34 + 省电` → `API 34 + 降低透明度` → `API 34 + 高对比度` → `恢复 API 34 正常`，并补 Robolectric 真实 `@Config(sdk = [32])`/`[30]` 校验 | **PASS**：7 态均无崩溃且完成软件 `Canvas` 绘制；`BLUR_ONLY` 精准将 `refractionHeight`/`refractionAmount` 归零并禁用 `lens`；4 路降级均切换至 `SOLID_DEGRADED`、`HiGlassBlurActive=false`、`HiGlassState.hasSourceDemand=false`，且 `Modifier.hiGlassSource` 返回原接收器不再挂载 `layerBackdrop` |
| **b. 同屏毛玻璃上限 3 块边界** | `probeB_maxThreeGlassSurfacesPasses_fourGlassSurfacesThrowsAssertionError` ([L308](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L308))<br>`probeB_glassRegionGroupsSubCapsulesIntoOneMarkerUnit` ([L395](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L395)) | 同屏挂载 3 块 `HiGlassSurface`（模拟右栏顶栏 + 底部工具条 + 面板/胶囊），再动态增至 4 块；另验证 `HiGlassRegion` 归并子胶囊 | **PASS**：`MaxGlassSurfacesOnScreen == 3`；3 块通过 `assertGlassSurfaceCountAtMost(3)`，第 4 块必然抛出 `AssertionError`；`HiGlassRegion` 内 3 枚子胶囊正确仅计为 1 个玻璃区域 |
| **c. 性能与重组隔离** | `probeC_lazyColumnScroll_doesNotRecomposeGlassSurfacePerFrame` ([L448](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L448))<br>`probeC_localHiGlassStyleMutation_viaCompositionLocal_isolatesContentList` ([L516](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L516))<br>`probeC_localHiGlassStyleMutation_viaHiTheme_isolatesContentList` ([L593](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L593))<br>`probeC_glassTuningPanel_refractionSlidersUpdateGlassAndFormatStably` ([L648](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L648)) | `LazyColumn` 挂 `hiGlassSource` 连续滚动 12 帧（每帧触发绘制）；动态修改 `LocalHiGlassStyle` / `HiTheme(glassStyle)` 及拖动 `GlassTuningPanel` 新折射滑杆与景深/色散开关；多 Locale 校验 `formatGlassStyleParams` | **PASS**：列表滚动 12 帧期间 `HiGlassSurface` 外层与内部 `content` 重组增量均为 **0**；动态改 `LocalHiGlassStyle` / `HiTheme(glassStyle)` 及操作调参面板时内容列表节点重组增量为 **0**，`HiGlassSurface` 即时读取新 `blurRadius`/`refractionHeight`/`refractionAmount`/`depthEffect`/`chromaticAberration`/`vibrancyEnabled`；`formatGlassStyleParams` 在 `Locale.GERMANY` 与 `Locale.US` 下输出完全一致的单行文本 |
| **d. 多尺寸/极端参数安全** | `probeD_extremeSizesAndParameters_doNotCrashDuringComposeOrDrawBackdrop` ([L778](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L778))<br>`probeD_allRenderTiers_extremeSizesAndParams_noCrash` ([L897](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L897)) | 5 组极值参数（全 `0dp`、单边 `0dp`、极值 `blur=24dp/refractH=32dp/refractAmt=48dp + depth + ca`、负数输入钳位）× 6 尺寸（`0dp`、`1dp`、`32dp`、`32×1`、`1×32`、`240×64`）× 5 形状（`Full`、`Small`、`Medium`、`Large`、`RectangleShape`）= 150 组合；外加全 3 档 `GlassRenderTier` × 极值 × `0/1/32dp` = 18 组合，全部执行真实 `View.draw` 触发 `drawBackdrop`/`blur`/`lens` | **PASS**：168/168 组合在组合期与绘制期均无除零、无负值崩溃、无异常（非阻断环境说明见下文第三节） |
| **e. Haze 零残留与纯洁性自查** | `probeE_purityAudit_zeroHazeZeroDebugInMainAndApacheLicensePresent` ([L974](file:///home/mir/Documents/hipda-next.wt/V-G2/hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt#L974)) | 扫描 `hipda/src/main/`、`gradle/`、`hipda/build.gradle.kts`、`build.gradle.kts`、`settings.gradle.kts`、核验 `assets/license.txt` 与依赖声明 | **PASS**：`haze`/`dev.chrisbanes.haze`/`libs.haze`/`HazeState`/`HazeBlur` 命中 **0**；`src/main/` 无 `GlassTuningPanel`/`PreviewActivity`/`SampleExportActivity` 引用；`libs.versions.toml` 声明 `io.github.kyant0:backdrop` `2.0.1`；`license.txt` 已含 `Backdrop (AndroidLiquidGlass)`、`Shapes` 与 `kudo-android` 的 `Apache License 2.0` 声明 |

---

## 三、非阻断观察与记录

1. **Robolectric 下 `Outline.Generic` + `Modifier.border` 框架限制**：在探针编写期对 `GenericShape` 进行附加测探时发现，Robolectric 4.18.1（API 34）对任何使用 `GenericShape`（`Outline.Generic`）的 Compose `Modifier.border` 会因 `BorderLogic.createDrawGenericBorderWithImageBitmap` 创建 `ALPHA_8` 位图触发 `ShadowBitmap` `NullPointerException: bm is null`；对照实验验证不经过 `HiGlassSurface`/`backdrop` 的普通 `Box(Modifier.size(32.dp).border(1.dp, Color.Black, genericShape))` 同样复现该 NPE。生产侧 `HiGlassSurface` 全部采用 `CornerBasedShape`（`HiShapeTokens.*`）或 `RectangleShape`（内部映射 `RoundedCornerShape(0.dp)`），不受影响。
2. **真机硬件 GPU 复验边界**：Robolectric/Roborazzi 已验证 `backdrop` 实时模糊渲染、四路档位切换与配置对象精确性；API 33+ 真机 `RuntimeShader` 边缘折射观感与 120Hz 帧率需在真机实测中做最终视觉确认。

---

## 四、本地与 CI 验证证据

- **本地全量校验**（遵循 `DISPATCH.md` §2：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew ... -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4` + `./gradlew --stop`）：
  - `:hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.G2ProbeTest" --tests "net.jejer.hipda.ui.designsystem.*" --tests "net.jejer.hipda.ui.shell.*" -Proborazzi.test.verify=true` → **`258 tests, 258 passed, 0 failed, 0 skipped`**（含 `G2ProbeTest` `12/12`、`HiGlassSurfaceTest` `11/11`、`GlassEnvironmentTest` `16/16`、`GlassTuningPanelTest` `3/3`、`MotionTokenScanTest` `7/7`、`HiAdaptiveShellTest` `29/29` 及全部 Roborazzi 截图验证）。
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug` → **`BUILD SUCCESSFUL`**。
- **GitHub Actions CI**：
  - Run：`https://github.com/mailwmj/hipda-next/actions/runs/45628311985`（分支 `verify/G2-probes`，Commit `c5ea852c837f221b3fcf5fa6d3a1094191f4dafd`）
  - 结果：`Build, Lint & Unit Tests` **SUCCESS**（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、`:core-parser:test`、`:hipda:testDebugUnitTest` `603/603` 含 `G2ProbeTest` `12/12` 及 Roborazzi verify；JUnit XML 汇总 **`672 tests, 672 passed, 0 failed, 0 errors, 0 skipped`**；APK badging `package='net.jejer.hipda.next'`、`targetSdkVersion='30'` 校验通过）。
