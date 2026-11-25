# G-glass 调研与方案报告：毛玻璃视觉效果对比与优化建议

> 调研日期：2026-11-25
> 参考仓库：`fluxdo`（`https://github.com/Lingyan000/fluxdo`，克隆于 `/tmp/fluxdo`）
> 目标环境：Galaxy Z Fold8（Android 16 / API 36，实测短边 `559dp`，内屏 `1828×2448px`，密度约 `3.27×`）
> 当前实现：`hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt`（Haze `2.1.0`）、`ui/shell/`、`docs/design/product-design.md` §5

---

## 一、fluxdo 全部毛玻璃与模糊实现盘点

经全仓检索 `/tmp/fluxdo`（`lib/` 与 `packages/`），fluxdo 的毛玻璃分为 **三大类共 12 处**，其中以自研 `common_ui` 局部光学玻璃材质为核心。

### 1.1 核心局部玻璃系统（`common_ui`：`GlassSurface` + `GlassSurfaceFrame` + `GlassCaptureHost`）

#### 架构与渲染管线
- **全局截取宿主**：`lib/main.dart:962-971` 用 `AppGlassSettings`（`lib/widgets/common/app_glass_settings.dart:12-36`）与 `GlassCaptureHost`（`packages/common_ui/lib/src/glass_capture.dart:47-156`）包裹根路由树。每帧在根部收集所有玻璃面 `_GlassPaneLayer`，按层级合并至最多 `1/8` 分辨率的离屏 atlas（`_captureFrost = 4`, `_maxDivisor = 8`, `_captureSoftness = 0.5`，`glass_capture.dart:28-33, 292-320`），再执行 `ui.ImageFilter.blur(..., tileMode: TileMode.clamp)`（`glass_capture.dart:421-428`）。宿外回退 `pushBackdropFilter`（`glass_capture.dart:909-916`）。
- **材质分级（`GlassRecipe`）**：`packages/common_ui/lib/src/glass_surface.dart:19-224` 与 `docs/glass_readability.md:5-15` 定义 5 档配方；同一组件切换配方时通过 `GlassRecipe.lerp` 平滑插值（`glass_surface.dart:94-119`）。
- **两层渲染路径**：
  1. **完整玻璃（optical，`GlassStyle.optical` + `GlassEffectLevel.auto/full`）**：`GlassBackdrop` 绑定 `packages/common_ui/shaders/glass_surface.frag`（`glass_surface.dart:490-560`），在一次片段着色器中完成局部纹理取样、SDF/超椭圆边缘透镜折射、R/B 色散、BT.709 色彩控制（饱和度/亮度/对比度）与三通道去带噪；其上叠中性半透明色罩；最顶层（前景内容之上）由 `GlassOpticalBorder` 绘制 3 条内侧光学轮廓带（`glass_surface.dart:442-473`，`glass_optical_border.dart:86-115`）。
  2. **基础磨砂（basic / 降级，`GlassStyle.frosted` 或 `GlassEffectLevel.basic`）**：`GlassBackdrop` 仅采样模糊后的背景（无折射 shader），其上色罩不透明度自动加厚 **`+0.08`** 补偿层次（`glass_surface.dart:565-603`），外壳改用 `GlassEdgePainter` 画单条 `0.5dp` 方向性 `SweepGradient` 白边（`glass_surface_frame.dart:57-64`、`glass_edge_painter.dart:7-67`）。
  3. **关闭玻璃 / 系统高对比度**：`GlassSettings.allowsBlur` 返回 `false`（`glass_settings.dart:40-41`），直接渲染 `alpha = 1.0` 的不透明纯色 `tint`（`glass_surface.dart:446-452`）。

#### 5 档 `GlassRecipe` 详细参数（`packages/common_ui/lib/src/glass_surface.dart:125-224`）

| 配方 | 使用处（`文件:行号`） | 模糊半径 `blurSigmaPx`（物理 px）与 dp 换算 | 色罩颜色与 Alpha（亮 / 暗；基础档 +0.08） | 饱和度 / 亮度 / 对比度 | 噪点 `noise` | 光学折射 / 色散（逻辑 px） | 描边 / 内阴影 | 外阴影 | 圆角（超椭圆） |
|---|---|---|---|---|---|---|---|---|---|
| **`navigation`** | 悬浮底栏胶囊 `lib/widgets/layout/adaptive_navigation.dart:593-613`；全屏游戏浮球收起态 `lib/modules/mini_apps/mini_app_floating_controls.dart:421` | **`14 px`**<br>（`dpr=3` ≈ `4.67dp`；`3.27×` ≈ **`4.28dp`**；`dpr=2` ≈ `7dp`） | 亮：`surfaceContainerLowest` @ **`.32`**<br>暗：`surfaceContainer` @ **`.32`**<br>（基础档 `.40/.40`） | `sat = 1.1025`<br>`bri = 0.0`<br>`con = 1.0` | `0.012` | `refractH = 12`<br>`refractAmt = 8`<br>`depth = 0.60`<br>`chroma = 0.45` | 完整档：`highlightAlpha = 0.95`（3 层内带含 `0.65dp` 黑暗槽）<br>基础档：`GlassEdgePainter` `0.5dp` 白渐变（亮 `0.46→0.19` / 暗 `0.188→0.042`）；无 Frame 时均匀 `0.5dp` 亮 `.46` / 暗 `.08` | `GlassSurfaceFrame` 双层柔影：<br>亮 `0.068@blur8 + 0.014@blur3.5`<br>暗 `0.052@blur7 + 0.010@blur3`，`spread -0.75` | `9999`（全高半圆 Stadium `RoundedSuperellipseBorder`） |
| **`toolbar`** | 编辑器工具岛收起态 `composer_island.dart:40-47`；聊天输入岛 `_chat_composer.dart:526-530`；上传条 `upload_task_panel.dart:482-486`；紧凑对象条 `composer_object_surface.dart:16-19`；游戏浮球展开 `mini_app_floating_controls.dart:420` | **`32 px`**<br>（`dpr=3` ≈ `10.67dp`；`3.27×` ≈ `9.79dp`） | 亮：`surfaceContainerLowest` @ **`.56`**<br>暗：`surfaceContainer` @ **`.70`**<br>（基础档 `.64/.78`） | `sat = 1.04`<br>`bri = 0`<br>`con = 1` | `0.012` | `refractH = 10`<br>`refractAmt = 5`<br>`depth = 0.35`<br>`chroma = 0.20` | 完整档：`highlightAlpha = 0.70`<br>基础档：同 `GlassEdgePainter`（独立 `GlassSurface` 均匀 `0.5dp` 亮 `.40` / 暗 `.08`） | 同上 `GlassSurfaceFrame`（`_chat_composer` / `composer_island` 共用） | `20dp`（工具岛/上传条）或 `24dp`（聊天输入岛/紧凑对象条），`RoundedSuperellipseBorder` |
| **`menu`** | 灵动胶囊 Toast `toast_service.dart:629-632`；对象菜单 `composer_object_surface.dart:18` | **`48 px`**<br>（`dpr=3` = `16dp`） | 亮：`surfaceContainerLowest` @ **`.74`**<br>暗：`surfaceContainer` @ **`.80`**<br>（基础档 `.82/.88`） | `sat = 1.0`<br>`bri = 0`<br>`con = 1` | `0.008` | `refractH = 10`<br>`refractAmt = 3`<br>`depth = 0.25`<br>`chroma = 0.12` | 完整档：`highlightAlpha = 0.55`<br>独立均匀描边 `0.5dp` 亮 `.36` / 暗 `.08` | Toast 另加外阴影：`亮 0.10 / 暗 0.28`, `blur 24`, `spread -6`, `offset(0,10)`（`toast_service.dart:620-627`） | Toast `28dp`，对象菜单 `24dp` |
| **`sheet`** | 编辑器工具岛展开 `composer_island.dart:45`；上传面板展开 `upload_task_panel.dart:485` | **`64 px`**<br>（`dpr=3` ≈ `21.33dp`） | 亮：`surfaceContainerLowest` @ **`.80`**<br>暗：`surfaceContainer` @ **`.86`**<br>（基础档 `.88/.94`） | `sat = 1.0` | `0.012` | `refractH = 16`<br>`refractAmt = 6`<br>`depth = 0.30`<br>`chroma = 0.20` | 完整档：`highlightAlpha = 0.72` | `GlassSurfaceFrame` 双层柔影 | `20dp`（工具岛） / 配方注释建议 `36dp` |
| **`dialog`** | 预留聚焦确认材质（定义于 `glass_surface.dart:206-223`） | **`80 px`**<br>（`dpr=3` ≈ `26.67dp`） | 亮：`surfaceContainerLowest` @ **`.86`**<br>暗：`surfaceContainer` @ **`.90`**<br>（基础档 `.94/.98`） | `sat = 1.0` | `0.008` | `refractH = 16`<br>`refractAmt = 4`<br>`depth = 0.22`<br>`chroma = 0.12` | 完整档：`highlightAlpha = 0.68` | `GlassSurfaceFrame` 双层柔影 | 配方注释建议 `40dp` |

#### 细节机制补全（附 `文件:行号`）
1. **Sigma 与 dp 换算**：Flutter `ImageFilter.blur(sigmaX, sigmaY)` 接收逻辑像素（内部按图层变换乘 `dpr` 变成物理像素高斯 $\sigma$）。fluxdo 在 `glass_surface.dart:505, 573` 刻意做 `recipe.blurSigmaPx / dpr`，确保在不同 DPR 屏幕上模糊半径保持恒定的**物理像素核**（注释明确警告：*“写成逻辑像素会在 dpr=3 的机器上模糊过量 3 倍”*，`glass_surface.dart:39-40`）。
2. **着色色源刻意回避 `surfaceContainer`（浅色）与 `surface`（深色）**：`GlassRecipe.tintFor`（`glass_surface.dart:88-91`）浅色取 `surfaceContainerLowest`（接近纯白 0.99 亮度）、深色取 `surfaceContainer`（约 0.12 亮度深灰），注释直指：*“浅色用最亮一级容器，深色用 `surfaceContainer`……不要换成强调色或 `primaryContainer` 一类的彩色容器，会把玻璃染成彩色塑料板”*（`glass_surface.dart:84-87`、`adaptive_navigation.dart:607-608`）。
3. **饱和度与色彩矩阵**：`glass_surface.frag:148-153, 227-230` 在折射采样后、色罩叠加前执行 BT.709 矩阵（`luma = dot(c, vec3(0.2126, 0.7152, 0.0722))`），`navigation` 提饱和度至 **`1.1025`**，`toolbar` 至 **`1.04`**（注释：*“缺了这步背景透过来会发灰”*，`glass_surface.dart:51-52`）。
4. **渐变高光描边与内暗槽**：
   - 完整档 `GlassOpticalBorder`（`glass_optical_border.dart:21-115`）：以左上角光源 `(-0.6, -0.8)` 计算超椭圆法线光照 `0.12 + 0.72 * pow(facing, 3) + 0.16 * pow(reflected, 6)`，在**色罩上层**绘制 3 层内侧三角形带（均内缩，不溢出圆角）：
     1. 内侧柔光：`inset 1.8dp, width 2.4dp`，白色（暗 `0.09` / 亮 `0.14` × `highlightAlpha`）
     2. **内暗槽（内阴影）**：`inset 1.05dp, width 0.65dp`，黑色（暗 `0.12` / 亮 `0.09` × `highlightAlpha`，随 `1 - light` 分布在背光侧）
     3. 最外窄高光：`inset 0.45dp, width 0.85dp`，白色（暗 `0.72` / 亮 `0.94` × `highlightAlpha`）
   - 降级/基础档 `GlassEdgePainter`（`glass_edge_painter.dart:14-62`）：`0.5dp` 单线内缩描边，按光源角 `atan2(-0.82, -0.58) ≈ -125.3°` 做三次方余弦 `Gradient.sweep`：亮色峰值 **`0.46`**→背光 **`0.19`**，暗色峰值 **`0.188`**→背光 **`0.042`**（注释：*“深色下白边必须收得很狠，否则像描了一条荧光笔”*，`glass_surface.dart:72-73`）。
   - 游戏全屏浮球另有自绘 `_DockEdgePainter`（`mini_app_floating_controls.dart:665-722`）：`.65dp` `LinearGradient(topLeft→bottomRight)` 随背景采样明暗插值 + 黑色阴影 `alpha 0.12 * (1 - ink), blur 2`。
5. **在滚动内容上方时的分层顺序与胶囊指示器**：
   - `AdaptiveScaffold` 设置 `extendBody: true`（`lib/widgets/layout/adaptive_scaffold.dart:106`），内容区 `body` 延伸到底栏下方，列表内通过 `MediaQuery.padding.bottom` 留白（`bottom_nav_clearance.dart:5-12`），底栏随滚动使用 `FractionalTranslation` 做纯绘制层平移（`adaptive_scaffold.dart:338-395`）。
   - 底栏内部堆栈从底到顶（`glass_surface_frame.dart:32-66`、`glass_surface.dart:442-485`）：外阴影 `DecoratedBox` → `ClipRSuperellipse` → `GlassBackdrop`（截取图模糊+折射+提饱和+去带噪）→ `DecoratedBox`（中性色罩）→ 前景（滑动选中 pill + 图标文字）→ `GlassOpticalBorder` / `GlassEdgePainter`（最上层，不被色罩压暗）。
   - **关键点——选中指示 pill 也是半透明的**：`adaptive_navigation.dart:736, 780-788` 中，选中 pill 背景仅用 **`scheme.primary.withValues(alpha: 0.11)`**（约 11% 不透明度），图标/文字用 `scheme.primary`（`adaptive_navigation.dart:843`）。因此整条胶囊从左到右**没有任何实心块遮挡玻璃通透感**。

---

### 1.2 fluxdo 其余 `BackdropFilter` / `ImageFilter.blur` 实现清单

| 场景 | 文件:行号 | 模糊半径（逻辑 px） | 着色/遮罩（亮 / 暗） | 饱和度/亮度/噪点 | 描边 / 阴影 / 圆角 / 分层 |
|---|---|---|---|---|---|
| 全局模态对话框 / BottomSheet | `lib/utils/dialog_utils.dart:32-65, 179-189, 234-243, 704-712, 830-840, 960-970`<br>`lib/utils/blur_config.dart:10-85` | Dialog `25.0`；Sheet `18.0`（`TileMode.mirror`） | 纯黑：`dialog` 亮 `0.20` / 暗 `0.32`；`sheet` 亮 `0.12` / 暗 `0.24`；默认亮 `~0.15` / 暗 `~0.28` | **无**（`blur_config.dart:61-62` 明注低精度像素叠矩阵会放大噪点）；无噪点 | 无描边、无阴影、无圆角；作为 `ModalBarrier` 叠于路由下方 |
| AI 模型管理独立弹窗 | `packages/ai_model_manager/lib/utils/dialog_utils.dart:11-54, 93, 103` | `25.0`（`TileMode.mirror`） | 纯黑：亮 `~0.15 (0x26)` / 暗 `~0.28 (0x47)` | **`saturationBoost = 1.3`**（BT.709 矩阵与模糊 `ImageFilter.compose`） | 无描边阴影；作为 `ModalBarrier` 叠于路由下方（macOS/Windows 跳过透明 Rail） |
| Cloudflare 验证遮罩 | `lib/services/cf_challenge_service.dart:2657-2668` | `25.0`（`createBlurFilter`） | `blurBarrierColor`（亮 `~0.15` / 暗 `~0.28` 黑） | 无 | 验证卡片外带 `blurRadius 32` 阴影与 `20dp` 超椭圆圆角（L2720-2737） |
| 通知快捷面板背景 | `lib/widgets/notification/notification_quick_panel.dart:301-303, 366-377, 432-443` | `25.0 * anim`（`TileMode.mirror`） | `blurBarrierColor`（亮 `~0.15` / 暗 `~0.28` 黑） | 无 | 用 `_ExcludeRRectClipper` 挖空面板区域（`20dp` 圆角）避免自模糊；面板 `elevation: 8` |
| 登录页磨砂表单卡片 | `lib/pages/login_page.dart:405-450` | `25.0`（`createBlurFilter`） | `surfaceContainer.withValues(alpha: 0.72)`（明暗同透明） | 无 | 描边 `outlineVariant @ 0.4`；外影 `暗 0.4 / 亮 0.1, blur 24, offset(0,8)`；`24dp` 超椭圆；在渐变背景上 |
| 长按图片抬起遮罩 | `lib/widgets/common/image_lift_menu.dart:536-543` | `4.0 * t`（`createBlurFilter`） | `scheme.scrim @ 0.50 * t` | 无 | 预览图单独 `shadow @ 0.18*t, blur 16*t, offset(0,4*t)`，`12dp` 超椭圆 |
| 聊天消息长按菜单遮罩 | `lib/pages/chat/channel/chat_message_menu.dart:344-350` | `16.0 * t`（默认 `TileMode`） | 纯黑 `@ 0.32 * t` | 无 | 浮起气泡 `BoxShadow(黑@0.25*t, blur 24*t, offset(0,8*t))`，`16dp` 超椭圆 |
| 浏览器标签总览遮罩 | `lib/widgets/browser/browser_tab_overview.dart:59-70` | `10.0 * value` | `colors.scrim @ 0.32 * value` | 无 | 全屏 `Stack` 背景层 |
| 二维码过期/已扫遮罩 | `lib/widgets/auth/qr_login_sheet.dart:469-531` | `6.0` | `scheme.scrim @ 0.45` | 无 | `ClipRSuperellipse(28dp)`，无描边阴影 |
| 个人主页封面下拉/收起模糊 | `lib/widgets/user/user_profile_header.dart:94, 118-137` | `12.0 * blurProgress`（`ImageFiltered`） | `scheme.surface @ 0.28 * blurProgress` | 无 | `ClipRect`，随滚动进度叠在封面图上 |
| 自定义背景图模糊 | `lib/widgets/common/app_background_layer.dart:47-74` | `0..20` 用户可调（`ImageFiltered`） | 纯白/纯黑 `@ userScrim` | 无 | 位于页面最底层背景 |
| MiniApp 模态/浮球图标动效 | `mini_app_modal_blur.dart:184`；`mini_app_floating_controls.dart:441-449` | `blur.sigma` / `2.5 * (1 - icons)` | 仅模糊网页截图或图标淡出 | 无 | 规避原生 PlatformView `BackdropFilter` 性能陷阱 |

---

## 二、我们当前的实现与截图基线诊断

### 2.1 当前代码与参数全览

1. **视觉令牌（`HiGlassTokens`，`HiGlassSurface.kt:40-53` & `product-design.md` §5 L95-101）**：
   - `BlurRadius = 24.dp`：同时作用于底部导航胶囊与详情底部工具条（Rail 暂也复用）。
   - `NoiseFactor = 0f`：显式关闭噪点（Haze `2.1.0` 默认其实为 `0.15f`）。
   - `LightBlurTintAlpha = 0.70f` / `DarkBlurTintAlpha = 0.60f`：叠加在模糊图上的 `surfaceColor` 透明度。
   - `surfaceColor` 默认取 `MaterialTheme.colorScheme.surface`（`HiGlassSurface.kt:149`、`HiNavigationPill.kt:41`）：
     - 浅色 `surface = n(98f)`（接近白，含微弱种子灰，`HiColors.kt:149`）
     - 深色 `surface = n(6f)`（接近黑，`HiColors.kt:188`）；`pureBlack` 下为 `Color.Black` (`#000000`，`HiColors.kt:218`)
     - 左侧导航轨 `HiNavigationRail.kt:51` 传入 `surfaceContainer`（`n(94f)` / `n(12f)` / pureBlack `8f`）。
   - `DegradedSurfaceAlpha = 0.94f`：降级态不挂载 `hazeBlur`，绘制 `94%` 纯色 + `colorScheme.outlineVariant` 细描边（`HiGlassSurface.kt:111-115, 171-173`）。
   - `BorderWidth = 0.5.dp`，`LightHighlightBorderAlpha = 0.40f` / `DarkHighlightBorderAlpha = 0.12f`：统一四边闭合的均匀纯白描边 `Color.White.copy(...)`（`HiGlassSurface.kt:112, 175`）。
   - `FloatingShadowElevation = 4.dp`，`LightShadowAlpha = 0.12f` / `DarkShadowAlpha = 0.28f`：Compose `Modifier.shadow(...)` 外阴影（`HiGlassSurface.kt:192-198`）。
   - `MaxGlassSurfacesOnScreen = 2`：约束同屏最多 2 块毛玻璃。
2. **Haze 2.1.0 调用链路（`HiGlassSurface.kt:159-170`）**：
   - `HazeBlurStyle` 仅设了 `blurEnabled(true)`、`blurRadius(24.dp)`、`noiseFactor(0f)`、`backgroundColor(styleSpec.surfaceColor)`、`colorEffects(listOf(HazeColorEffect.tint(styleSpec.activeTintColor)))`、`fallbackColorEffect(...)`。
   - 未传 `performanceMode`（默认 `HazePerformanceMode.Default` = `Balanced` = `Fixed(0.5f)`，输入图层缩放到 $\sqrt{0.625} \approx 0.79$）。
3. **外壳分层与选中指示器（`HiAdaptiveShell.kt:116-177`、`HiNavigationPill.kt:41`、`ShellIndicatorLayout.kt:75-80`）**：
   - 底部胶囊下，`ContentContainer` 铺满窗口并挂 `.hiGlassSource(glassState)`，`HiNavigationPill` 悬浮其上（正常）。
   - **Rail 缺陷**：`HiAdaptiveShell.kt:119` 给 `ContentContainer` 加了 `.padding(start = 80.dp)` 再挂 `.hiGlassSource(glassState)`，导致 `HiNavigationRail` 背后没有任何内容进入 `HazeState`，`80dp` 左侧轨在模糊态下只是对透明空域叠一层 `surfaceContainer` 实底，白白启用了离屏模糊源录制。
   - **指示器堵死透光**：`ShellIndicatorLayout.kt:79` 选中胶囊背景是 **100% 不透明 `MaterialTheme.colorScheme.secondaryContainer`**，在 4 项底部胶囊里占满约 1/4（窄屏更宽）面积。

### 2.2 截图基线（`shell_*` & `glass_specimen_*`）客观观感分析

> **观测环境说明**：核对 `hipda/src/test/snapshots/images/` 中的 `shell_w411_h891_light/dark.png`、`shell_scroll_w411_h891_bottom_light.png`、`shell_w700_h900_light/dark.png`、`shell_w891_h411_compact_light.png`、`shell_w1280_h800_light/dark.png` 以及 `glass_specimen_light/dark_api31_blur.png`、`glass_specimen_light/dark_api30_degraded.png`。需先指出：如 `HiGlassScreenshotTest.kt:50` 所述，Robolectric RNG 渲染不代表真机硬件 `RenderEffect` 表现，但对比 `api30_degraded`（背后文字“长帖连续加载零跳动”清晰锐利透出 6%）与 `api31_blur`（背后文字确实被涂抹成模糊暗团，且浅色/深色 `shell_*` 底部隐约透出下层卡片模糊色块），仍能客观诊断出以下核心视觉缺陷（也是 Z Fold8 真机观感差的主因）：

1. **模糊半径过大（`24.dp`），内容质感被彻底摊平**：
   - Haze 2.1.0 `BlurVisualEffectUtilsKt.resolveBlurRadiusPx` 直接把 `blurRadius * scale` 转为像素传入 `RenderEffect.createBlurEffect`（即高斯 $\sigma$）。在 Z Fold8 约 `3.27×` 密度下，`24.dp` 在默认 `Balanced` 缩放（`0.79`）下等价于 **`62 px`** 的高斯 $\sigma$（`Quality` 模式下达 **`78.5 px`**）。在仅 `64dp` 高的胶囊后面，任何卡片边界、头像色块、文字行都会被平均成几乎均匀的一片雾，用户感知不到“背后有内容在滚动折射”，几乎退化成静态半透明塑料条。
2. **着色太厚 + 底色发灰/发黑，浅色成白片、深色显暗闷**：
   - **浅色（`shell_w411_h891_light.png`、`shell_scroll_w411_h891_bottom_light.png`）**：`surface (tone 98) @ 0.70f` 仅允许 **30%** 背景透出。列表底色本就是低反差浅色（`n(98)` / `n(96)`），盖上 70% 近白罩后，整条胶囊看起来几乎是一块**不透明的纯白药丸**，仅在底缘勉强透出一丝极淡的蓝紫边。
   - **深色（`shell_w411_h891_dark.png`、`glass_specimen_dark_api31_blur.png`）**：`surface (tone 6) @ 0.60f` 把接近纯黑的底色（OLED 纯黑下更是 `#000000`）以 60% 盖在暗色列表上，透出光感被压黑压暗，胶囊比周围更显得黑闷沉重，既不晶莹也不通透。
3. **缺乏饱和度提升，透出色块发灰发脏**：
   - 高斯模糊本身会稀释局部色彩饱和度，叠加高比例中性 `surface` tint 后，卡片里的主题蓝/紫圆标透出后完全褪成灰蓝泥团（在 `glass_specimen_light_api31_blur.png` 工具条“回复 第 2 / 8 页 更多”下方尤为明显，黑字被糊成一片灰暗污迹）。
4. **高光描边平直失效（浅色隐形、深色生硬）**：
   - 在 `shell_w411_h891_light.png` 与 `shell_w700_h900_light.png` 中，浅色胶囊 `0.5dp` `Color.White @ 0.40f` 叠在接近纯白的 `surface` 上，肉眼几乎完全分辨不出高光边缘，只能靠 `4.dp` 阴影圈出灰色轮廓；
   - 在 `shell_w411_h891_dark.png` 与 `shell_w700_h900_dark.png` 中，均匀一圈 `Color.White @ 0.12f` 细线像在暗胶囊四周画了一圈平直的金属丝，缺乏顶面迎光亮、底面背光暗的体积感。
5. **100% 不透明 `secondaryContainer` 指示器割裂玻璃连续性**：
   - `shell_w411_h891_light/dark.png` 与 `shell_w891_h411_compact_light.png` 中，“论坛”选中块是一整块完全不透明的淡蓝/深青实心药丸，**遮断了胶囊左侧约 1/4～1/3 区域的毛玻璃背景穿透**，破坏了整体材质一致性。
6. **无噪点去带、阴影偏贴纸、Rail 空模糊**：
   - `NoiseFactor = 0f` 缺少微粒磨砂触感；`4.dp` 外影相对重（尤以浅色白片+黑影最为“贴纸感”）；`1280×800` 模式下左侧 `NavigationRail` 背后无内容却开启模糊。

---

### 2.3 hipda-next 与 fluxdo 逐项对照表

| 对比维度 | hipda-next（现状） | fluxdo（`GlassRecipe.navigation` / `toolbar`） | 差距与观感影响 |
|---|---|---|---|
| **模糊半径（物理 $\sigma$）** | 统一 `24.dp`（Fold8 `3.27×` + Balanced 缩放 ≈ **`62 px`**；Quality ≈ **`78.5 px`**） | 导航 **`14 px`**（Fold8 约 `4.3dp`）；工具条 **`32 px`**（约 `9.8dp`） | **差 4.5–5.6 倍**：我方过糊，内容轮廓与色块流动感完全丧失 |
| **着色底色** | 默认 `colorScheme.surface`（浅 `tone 98` / 深 `tone 6` / 纯黑 `#000000`） | 浅 **`surfaceContainerLowest`**（`0.99` 最亮白）<br>暗 **`surfaceContainer`**（`0.12` 微亮深灰） | 我方浅色偏灰白、深色发黑发闷（尤其 pureBlack 完全黑死） |
| **着色透明度（亮 / 暗）** | 统一 **`0.70f` / `0.60f`**（仅剩 30%/40% 透出） | 导航 **`.32 / .32`**（基础档 `.40 / .40`）<br>工具条 **`.56 / .70`**（基础档 `.64 / .78`） | 导航胶囊我方遮罩过厚一倍以上，几乎看不出下方内容 |
| **场景分级** | 导航胶囊、底部工具条、Rail 共用同一组常量 | 5 档分级：`navigation` 通透、`toolbar` 抑制字形、`menu`/`sheet`/`dialog` 逐级增强 | 我方导航被工具条可读性需求拖累，被迫用过高模糊和透明度 |
| **色彩/饱和度增强** | **无**（仅单层 `HazeColorEffect.tint`） | shader 内 BT.709 **`saturation = 1.1025`**（navigation）/ `1.04`（toolbar） | 我方模糊+白灰遮罩后色彩坍缩成脏灰 |
| **噪点纹理** | `NoiseFactor = 0f`（显式关闭） | `noise = 0.012` 三通道伪随机去带噪 | 我方平滑渐变易有轻微色带且缺磨砂质感 |
| **渐变高光描边 & 内暗槽** | 统一均匀 `0.5dp` 纯白（亮 `0.40` / 暗 `0.12`），在白底上隐形、暗底上呆板 | 完整档 3 层法线内轮廓（含 `0.65dp` 黑暗槽 + `0.85dp` 迎光高光）；基础档 `0.5dp` 左上→右下三次方 `SweepGradient`（亮 `0.46→0.19` / 暗 `0.188→0.042`） | 我方缺少左上受光、右下收敛的玻璃边缘厚度感 |
| **边缘光学折射 / 色散** | **无** | 完整档 SDF/超椭圆透镜弯曲（`12dp/8dp`）+ R/B 色散（`0.45dp`） | 真机边缘缺少透镜弯折感（但非唯一痛点，基础磨砂也很好看） |
| **外阴影** | `4.dp`（亮 `0.12` / 暗 `0.28`） | `GlassSurfaceFrame` 双层极柔（亮 `0.068@8 + 0.014@3.5`，暗 `0.052@7 + 0.010@3`，`spread -0.75`） | 我方在浅色白片下外影偏硬，略显“贴纸” |
| **圆角** | Compose `CircleShape`（标准圆弧胶囊） | `RoundedSuperellipseBorder`（连续曲率超椭圆） | 超椭圆过渡更顺滑，但胶囊形态下与 `CircleShape` 差异相对次要 |
| **选中指示器** | **100% 不透明 `secondaryContainer`** 实心大块 | **`primary @ 0.11`** 半透明淡底，文字图标高亮 | **极大差异**：我方直接挡死 1/4–1/3 毛玻璃面积 |
| **导航轨（Rail）层级** | 背后内容 `.padding(start = 80.dp)`，Rail 实际模糊空背景 | 非 macOS/Win 桌面使用实色导航轨 + 1dp 分隔线（不强行模糊空区） | 我方大屏 Rail 浪费离屏图层却无模糊视觉收益 |

---

## 三、Haze 2.1.0 实际 API 核实与改造方案

### 3.1 Haze 2.1.0 实际 API 核实（基于本仓 `.gradle` 缓存字节码实测）

当前项目锁定 `dev.chrisbanes.haze:haze` / `haze-blur` / `haze-blur-materials` **`2.1.0`**（`gradle/libs.versions.toml:17`）。核实 `haze-blur-api.jar` 与 `haze-materials-api.jar` 字节码确认：

- 官方文档：[https://chrisbanes.github.io/haze/](https://chrisbanes.github.io/haze/)
- 官方源码仓库：[https://github.com/chrisbanes/haze](https://github.com/chrisbanes/haze)（对应 `haze-blur/src/commonMain/kotlin/dev/chrisbanes/haze/blur/HazeBlurStyle.kt`、`BlurRenderEffect.kt`、`haze-blur/src/androidMain/.../BlurRenderEffect.android.kt`、`haze-blur-materials/.../CupertinoMaterials.kt`）

实际可用 API 与底层行为如下：
1. **`HazeBlurStyleScope`**（`HazeBlurStyle.kt`）：
   - `blurEnabled(Boolean)` / `blurRadius(Dp)` / `noiseFactor(Float)` / `backgroundColor(Color)` / `alpha(Float)` / `mask(Brush?)` / `progressive(HazeProgressive?)` / `blurredEdgeTreatment(Shape)`。
   - `colorEffects(List<HazeColorEffect>)`：按列表顺序在模糊结果上串联效果。
   - `fallbackColorEffect(HazeColorEffect?)`：不可模糊时兜底着色。
2. **`HazeColorEffect` 三个工厂方法**（`HazeColorEffect.Companion`）：
   - `HazeColorEffect.tint(color: Color, blendMode: BlendMode = BlendMode.SrcOver)`（`TintColorHazeColorEffect`）
   - `HazeColorEffect.tint(brush: Brush, blendMode: BlendMode = BlendMode.SrcOver)`（`TintBrushHazeColorEffect`，可传渐变 `Brush`）
   - `HazeColorEffect.colorFilter(colorFilter: ColorFilter, blendMode: BlendMode = BlendMode.SrcOver)`（`ColorFilterHazeColorEffect`）——经 `BlurRenderEffectKt.withColorFilter` 字节码核实：它将 Compose `ColorFilter` 转为原生 `android.graphics.ColorFilter`，生成 `RenderEffect.createColorFilterEffect(platformColorFilter, input)` 并按 `blendMode`（默认 `SrcOver`）混合。因此传入 `ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.15f) })` 可直接在 GPU `RenderEffect` 链上完成提饱和度，**先执行它再执行 `tint(...)`** 即可先提鲜背景再罩中性色。
3. **`noiseFactor` 与单 Pass AGSL 优化**（`BlurRenderEffect_androidKt.createCombinedNoiseTintRenderEffectOrNull`）：
   - Android 13+（API 33+）且 `colorEffects` 为单个 `TintColorHazeColorEffect(BlendMode.SrcOver)`、无 `progressive/mask` 时，Haze 2.1.0 会走内置 **`COMBINED_NOISE_TINT_SKSL` `RuntimeShader`**，把 `haze_noise.webp` 的 `softLight` 噪点与 `tintColor` 在**同一个 shader pass** 内完成。
   - 若 `colorEffects` 有多个项（如增加 `ColorFilterHazeColorEffect`）或 API 31–32，则回退为 `createNoiseEffect`（`Softlight`）+ `withTints` 逐个串联 `RenderEffect`。
4. **`HazeBlurStyle` 预设库（`haze-blur-materials` 已在依赖中）**：
   - 包含 `HazeMaterials`（`ultraThin`~`ultraThick`，固定 24dp）、`CupertinoMaterials`（浅色自动叠加 `BlendMode.ColorDodge`、深色叠加 `BlendMode.Overlay` 提亮透色，24dp）、`FluentMaterials`（`BlendMode.Color` + `BlendMode.Luminosity` + `noiseFactor = 0.02f`）。因项目采用 M3 动态取色且需严控模糊半径与降级逻辑，建议继续维护 `HiGlassTokens` + 自定义 `HazeBlurStyle`，不必直接套用 `HazeMaterials` 24dp 预设（可借鉴 `CupertinoMaterials` 的双层提鲜或 `ColorMatrix` 饱和度思路）。
5. **`Modifier.hazeBlur(..., performanceMode = ...)`**（`HazeBlur.kt`、`BlurInputScalePolicy.kt`）：
   - `HazePerformanceMode.Default` / `Balanced`（输入缩放 $\sqrt{0.625} \approx 0.79$）、`Quality`（`1.0`）、`Performance`（`0.5`）。
6. **不支持的能力**：Haze 2.1.0 **不提供**边缘透镜折射（refraction）/ R-B 色散（chromatic aberration）AGSL 采样入口；若要做玻璃边缘凹凸折射，需自行管理离屏截图与 `RuntimeShader`。

---

### 3.2 分层改造方案（区分“只改令牌”与“需要加效果层”）

#### 阶段 A：【只改令牌】（零结构/效果层改动，直接调 `HiGlassTokens`）

> 仅调整 `HiGlassTokens` 常量（并同步 `product-design.md` §5 与 `HiGlassSurfaceTest` 断言），即可立刻解决“糊成一片、白得发死、暗得发闷、缺颗粒感、外影偏贴纸”五大核心问题中的大部分。

| 变更项 | 参数：旧值 → 新值 | Haze 2.x 对应 API | 预期观感 | 性能影响 |
|---|---|---|---|---|
| 1. 减小模糊半径（导航胶囊） | `BlurRadius`: `24.dp` → **`10.dp`**（备选 `12.dp`）<br>*注：若工具条也保持同令牌则取 `12.dp`；若走阶段 B 分档则胶囊 `8.dp–10.dp`、工具条 `14.dp–16.dp`* | `HazeBlurStyleScope.blurRadius(styleSpec.blurRadius)` | 在 Fold8（`3.27×`）上等效高斯 $\sigma \approx 26\text{–}33\text{ px}$（比旧版 `62 px` 锐利一倍），能透过胶囊看到下方卡片色块与文字柔和流动，不再糊成一片死雾 | **正收益**：`RenderEffect.createBlurEffect` 采样卷积核半径减半，GPU 带宽与填充率明显下降 |
| 2. 降低浅/深色着色透明度 | `LightBlurTintAlpha`: `0.70f` → **`0.45f`**（参考范围 `0.42–0.50`）<br>`DarkBlurTintAlpha`: `0.60f` → **`0.46f`**（参考范围 `0.42–0.50`） | `HazeColorEffect.tint(surfaceColor.copy(alpha = tintAlpha))` | 透出率从 30%/40% 提升至 **54%–55%**，浅色不再像不透明白塑料，深色透出微光，同时保留足够隔离度保障图标文字辨识 | **零额外开销** |
| 3. 开启微弱磨砂噪点 | `NoiseFactor`: `0f` → **`0.04f`**（对比 Haze 默认 `0.15f`、fluxdo `0.012`） | `HazeBlurStyleScope.noiseFactor(HiGlassTokens.NoiseFactor)` | 消除 8-bit 大面积柔和色块的色带（banding），赋予玻璃细腻的微磨砂颗粒质感，同时避免 `0.15f` 过糙 | **极低**：API 33+ 与单 tint 时走 `COMBINED_NOISE_TINT_SKSL` 单 pass；加效果层后多 1 次轻纹理混合（`haze_noise.webp` 为小平铺图） |
| 4. 柔化外阴影 | `LightShadowAlpha`: `0.12f` → **`0.07f`**<br>`DarkShadowAlpha`: `0.28f` → **`0.18f`**<br>（`FloatingShadowElevation` 暂保持 `4.dp` 以兼容 `ShellBarState` 隐藏偏移） | Compose `Modifier.shadow(ambientColor, spotColor)` | 消除浅色模式下一圈过深的灰黑轮廓感，胶囊从“贴纸”变为轻盈悬浮 | **零额外开销** |
| 5. 收敛高光描边（仅当暂不加渐变时） | `LightHighlightBorderAlpha`: `0.40f` → **`0.65f`**（仅阶段 A 临时，或直接走阶段 B 方向渐变）<br>`DarkHighlightBorderAlpha`: `0.12f` → **`0.16f`** | `Modifier.border(...)` | 提高浅色/深色边缘辨识，但纯白均匀线在浅色纯白底上仍偏弱，**强烈建议搭配阶段 B 方向渐变描边** | **零额外开销** |

#### 阶段 B：【需要加效果层 / 渲染层微调】（无需自研离线截图折射，全部基于 Haze 2.1.0 原生与 Compose 标准修饰符）

| 变更项 | 具体改法（旧 → 新） | Haze 2.x / Compose 对应 API | 预期观感 | 性能影响 |
|---|---|---|---|---|
| **B1. 修正玻璃基底色源**（`surface` → 亮白/暗灰容器） | 导航胶囊 / 工具条的激活态 `surfaceColor`：<br>- 浅色：`colorScheme.surface` (`n(98)`) → **`colorScheme.surfaceContainerLowest`**（纯白，提亮通透）<br>- 深色：`colorScheme.surface` (`n(6)`) / `Black` → **`colorScheme.surfaceContainer`**（普通深色 `n(12)`，OLED 纯黑模式下至少用 `surfaceContainerHigh` 或 `surfaceContainer` 而非 `#000000`）<br>*注：降级态 `degradedSurfaceColor` 仍保留 `surface` / `surfaceContainer` 以守住 94% WCAG 4.5:1* | `HazeBlurStyleScope.backgroundColor(styleSpec.activeBaseColor)` + `HazeColorEffect.tint(styleSpec.activeTintColor)` | 解决浅色偏灰脏、深色发黑发闷的问题；在壁纸取色或 OLED 纯黑下玻璃本身呈现清亮/微亮浮层质感 | **零额外开销** |
| **B2. 增加模糊背景饱和度增强层（核心去脏灰）** | 新增 `BlurSaturation = 1.18f`（建议范围 `1.15f–1.25f`）：<br>将 `colorEffects` 从单元素 `[tint]` 改为 **`[colorFilter(sat), tint]`** | `HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.18f) }))` 置于 `HazeColorEffect.tint(...)` 之前 | 补偿高斯模糊与色罩造成的色彩流失，背景中的头像/版块徽标色彩透过玻璃后鲜活饱满，彻底告别“脏灰泥色” | **极低（需关注低精度噪点）**：会退出 `COMBINED_NOISE_TINT_SKSL` 单 pass 而多串联 1 个 `ColorFilterEffect` GPU pass；同屏最多 2 块小胶囊，Fold8 无感。需留意高饱和系数在暗部是否放大 8-bit 量化噪点（配合 `NoiseFactor = 0.04f` 可压制） |
| **B3. 方向性高光渐变描边 + 微内暗边** | 把单色 `Modifier.border(0.5.dp, Color.White.copy(...))` 替换为：<br>1. 主迎光渐变描边（`0.75.dp` 或 `0.6.dp`）：**左上→右下 `Brush.linearGradient`**（浅色：左上高光 `White @ 0.85f` → 中段 `White @ 0.28f` → 右下微反射 `White @ 0.45f`，并叠加一圈极淡外/内轮廓暗线 `Black @ 0.06f` 让浅色白底可见轮廓；深色：左上 `White @ 0.24f` → 中段 `White @ 0.05f` → 右下 `White @ 0.12f`，严控防荧光边） | Compose `Modifier.border(width, brush, shape)`（零自定义 `Path`/`Vertices`）或在前景后 `drawWithContent` 绘于 tint 之上，避免被色罩压暗 | 让整块胶囊立刻出现“顶部迎光厚度与晶莹轮廓”，浅色下终于看得出玻璃边界，深色下告别死板白线框 | **极低**：静态 `LinearGradient` 描边画笔，无额外离屏图层或 shader 编译 |
| **B4. 胶囊选中指示器改为半透明** | `ShellIndicatorLayout.kt:79`：<br>`colorScheme.secondaryContainer`（100% 不透明）→ **`colorScheme.primary.copy(alpha = 0.12f)`**（或 `secondaryContainer.copy(alpha = 0.52f)`），选中项图标/文字配 `primary`（或保持 `onSecondaryContainer` 校验对比度） | Compose `Modifier.background(...)` | **释放被挡死的 1/4–1/3 胶囊面积**！整条导航胶囊从头到尾都是连续透光的完整毛玻璃，视觉提升立竿见影 | **零额外开销** |
| **B5. 区分“导航胶囊”与“阅读工具条”两档密度（推荐）** | 为 `HiGlassSurface` 或 `HiGlassStylePreset` 增加 `Navigation`（轻透：`10.dp`, `0.45/0.46`, `sat 1.18f`）与 `Toolbar`（护读：`16.dp`, `0.60/0.68`, `sat 1.08f`）两档；`HiAdaptiveShell` 大屏 `Rail` 因背后无滚动内容，建议不再套 `HiGlassSurface` 或显式 `blurEnabled = false` 省掉空离屏图层 | `HiGlassStylePreset` 枚举入参 → 对应 `HiGlassTokens` 分档常量 | 底部导航通透灵动，同时保证后续帖子详情底部工具条（盖在密集正文/配图上时）文字不花、对比度稳妥 | **正收益**：大屏去掉 Rail 的无效 `hazeSource` 录屏开销 |
| **可选二期（暂不推荐）**：真边缘透镜折射 + 色散 | 需用 Android 13+ `RuntimeShader` 对截取图做 SDF 边缘折射（类似 fluxdo `glass_surface.frag`） | Haze 2.1.0 **无内置折射 API**，需自建离屏 Capture 或 Fork Haze | 仅在边缘 10–12dp 处产生苹果 Liquid Glass 弯曲折射感 | **高风险/高复杂度**：需维护两套渲染架构且违背“不照抄、优先 Compose 标准能力”，建议先完成 A+B 真机验收后再决定是否需要 |

---

## 四、需要用户真机确认的项（Galaxy Z Fold8，Android 16）

由于 Robolectric 无法真实反映 Android 16 硬件 `RenderEffect` 与 `RuntimeShader` 的颜色精度和帧率，最终调优需用户在真机对比确认以下 6 项：

1. **模糊半径档位手感（`8.dp` vs `10.dp` vs `12.dp`）**：Fold8 展开与外屏密度约 `3.27×`，需真机滑过列表头像和彩色标签时确认哪一档既能看清色块柔和流动、又不会让背后文字笔画干扰图标标签识别。
2. **着色透明度与极端背景可读性（`0.42f–0.50f`）**：当深色/浅色混合卡片或**帖子高饱和彩色图片 / 纯白图**从胶囊和工具条下方划过时，验证未选中项 `onSurfaceVariant` 图标文字是否仍然清晰、不费眼（特别是深色模式 + 纯黑 OLED 下 `surfaceContainer @ 0.46f` 的亮度）。
3. **饱和度增强（`1.15f–1.20f`）是否产生暗部噪点或偏色**：在 Android `RenderEffect` + 动态壁纸取色（`HiThemeSeed.Wallpaper`）下观察暗色区域是否有 8-bit 量化噪点或色彩过艳。
4. **噪点系数（`NoiseFactor = 0.04f` vs `0.02f`）**：确认 `0.04f` 在 Fold8 高分屏上是细腻的纸感磨砂还是可见粗颗粒。
5. **半透明选中指示器方案二选一**：对比 `primary @ 0.12f + primary 前景色`（fluxdo 方案，最通透）与 `secondaryContainer @ 0.55f + onSecondaryContainer`（更贴合现有 M3 胶囊视觉）的清晰度与喜好。
6. **滚动 120Hz 帧率稳定性**：确认启用 `ColorFilter` + `NoiseFactor` 后（2 块玻璃同时在底部且快速惯性滑动列表时），在 Fold8 120Hz 下是否仍保持零掉帧。
