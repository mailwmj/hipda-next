# G-kudo 调研与方案报告：kudo-android 毛玻璃界面与交互借鉴分析

> 调研日期：2026-11-25  
> 参考仓库：`https://github.com/woliubang256/kudo-android`（浅克隆于 `/tmp/kudo`）  
> 对照基准：`hipda-next` `main` @ `9c3b8ef`（含 `G1` `7cf0e70`、`C4c` `0519096`、`product-design.md` v0.3、`copy.md` v0.3）  
> 约束声明：本调研仅只读分析代码与文档，未修改 `hipda-next` 仓库。

---

## 一、kudo-android 源码剖析（逐项核查与定位）

### 1. 许可证与复用边界
- **主体许可**：`kudo-android` 主体代码采用 **Apache License 2.0**（Copyright 2026 Bang Liu，见 [LICENSE](file:///tmp/kudo/LICENSE#L1-L179) 与 [THIRD_PARTY_NOTICES.md](file:///tmp/kudo/THIRD_PARTY_NOTICES.md#L5)）。
- **核心玻璃组件来源**：`ui/components/liquid/` 下 8 个文件（`LiquidBottomTabs`、`LiquidBottomTab`、`LiquidButton`、`LiquidSlider`、`LiquidModal`、`DampedDragAnimation`、`InteractiveHighlight`、`DragGestureInspector`）改编自 **Kyant0/AndroidLiquidGlass** `1.0.6` 官方 catalog 示例（Copyright 2025 Kyant，同样为 **Apache-2.0**，完整副本存于 [backdrop-demo-LICENSE.txt](file:///tmp/kudo/app/src/main/assets/licenses/backdrop-demo-LICENSE.txt#L1-L191)，源码溯源见 [THIRD_PARTY_NOTICES.md](file:///tmp/kudo/THIRD_PARTY_NOTICES.md#L28-L30) 与 [LICENSE](file:///tmp/kudo/LICENSE#L181-L193)）。
- **品牌/图标资产边界**：23 个分类图标由 Font Awesome Free 6.7.2 SVG 转矢量，受 **CC BY 4.0** 约束（[THIRD_PARTY_NOTICES.md](file:///tmp/kudo/THIRD_PARTY_NOTICES.md#L18,L34)）；应用图标、黄色主色 `#FFB003` 与商标不可直接搬用（[LICENSE](file:///tmp/kudo/LICENSE#L141-L144)）。
- **对本项目的结论**：核查 `hipda-next` 根目录无 `LICENSE` 文件，仅 [README.md](file:///home/mir/Documents/hipda-next/README.md#L1-L15) 与 [license.txt](file:///home/mir/Documents/hipda-next/hipda/src/main/assets/license.txt#L1-L28) 列出第三方组件致谢（未声明 GPL-2.0-only 等不兼容条款）。Apache-2.0 允许复制、修改并在自研或再分发项目中引用。**结论：设计思路与 Apache-2.0 源码（如 `BalancedTabRow`、手势交互逻辑等）均可借鉴；但 `ui/components/liquid/` 强绑定第三方依赖 `kyant0:backdrop`，且本卡要求不复刻品牌色与视觉资产，因此建议优先借鉴设计模式并在既有 Haze 2.1.0 上落地。** 若直接复制 Apache-2.0 代码，须按 [LICENSE §4](file:///tmp/kudo/LICENSE#L92-L131) 保留原版权声明、修改说明并在致谢中随包附带许可。

---

### 2. 毛玻璃技术栈、全量参数与 API 降级机制
- **底层实现**：主界面全部毛玻璃均基于 **`io.github.kyant0:backdrop:1.0.6`** + **`io.github.kyant0:shapes:1.2.0`**（[app/build.gradle.kts](file:///tmp/kudo/app/build.gradle.kts#L500-L501)），支持三档运行模式 `GlassMode`（`LIQUID` / `BLUR` / `TRANSLUCENT`，[AppSettings.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/data/AppSettings.kt#L9)）。另有三处辅助用 Compose 原生 `Modifier.blur()`（NSFW 封面 `14.dp` [Common.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/Common.kt#L202)、剧透 `8.dp` [SpoilerContent.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/SpoilerContent.kt#L52)、卡片转场背景 `0..6.dp` [TopicTransition.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopicTransition.kt#L177-L185)），以及触点高光在 API 33+ 使用自写 AGSL `RuntimeShader`（[InteractiveHighlight.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/InteractiveHighlight.kt#L48-L96)）。
- **API 分级与降级**（`minSdk = 26`，[app/build.gradle.kts](file:///tmp/kudo/app/build.gradle.kts#L409)）：
  - **API 33+（Android 13+）且 `GlassMode.LIQUID`**：开启模糊 + AGSL 光学边缘折射 `lens()`（+ 色散）+ 触点 `RuntimeShader`（[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L74-L75)、[ScreenMaterial.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/ScreenMaterial.kt#L28-L29)）。
  - **API 31–32（Android 12/12L）或 `GlassMode.BLUR`**：仅开模糊与 `vibrancy()`/`colorControls()`，关闭 `lens()` 折射。
  - **API 26–30（Android 8–11）或 `GlassMode.TRANSLUCENT`**：`canBlur = false`，直接以 **94%（对话框 96%）不透明 `surface` / 着色**降级（[FeedScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/feed/FeedScreen.kt#L144)、[LiquidButton.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidButton.kt#L71,L122)、[CommunitySheet.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/CommunitySheet.kt#L120)）。**注意：kudo 未监听系统省电模式、“降低透明度”或高对比度开关**，仅依赖设置页手动切换 `GlassMode`（[SettingsScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/account/SettingsScreen.kt#L73,L110)）。
- **基线颜色**（[KudoTheme.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/theme/KudoTheme.kt#L25-L40)）：浅色 `surface = #FFFFFF`、暗色 `surface = #1C1D20`、品牌黄 `LogoYellow = #FFB003`、深色文字 `Ink = #202124`。

| 部位（`文件:行号`） | 模糊半径 | 着色（亮 / 暗） | 降级底色 | 饱和度/亮度/噪点 | 折射 `lens` / 色散 | 描边高光与阴影 |
|---|---|---|---|---|---|---|
| **首页吸顶顶栏**<br>[FeedScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/feed/FeedScreen.kt#L138-L145) | `0 → 4.dp`（随折叠 `collapsedFraction`） | 亮/暗均 `1.0 → 0.20` × `surface` | `1.0 → 0.94` × `surface` | `vibrancy()`；无噪点 | `lens(24.dp, 24.dp) * collapsed`；无色散 | 无（`drawPlainBackdrop` 矩形 `0.dp`） |
| **详情/常规页面顶栏**<br>[ScreenMaterial.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/ScreenMaterial.kt#L21-L36) | `4.dp * fraction` | 亮/暗均 `surface @ 20%`（由 `expandedColor` 插值） | `surface @ 94%` | `vibrancy()`；无噪点 | `lens(24.dp, 24.dp) * progress`；无色散 | 无 |
| **底部导航底板**<br>[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L76,L188-L201) | `4.dp` | 亮/暗均 `surface @ 20%` | `surface @ 94%` | `vibrancy()`；无噪点 | `lens(24.dp, 24.dp)`；无色散 | `Capsule()`，触点 `InteractiveHighlight`（API 33+ AGSL 加法白光 `0.08/0.15`） |
| **导航隐藏采样层 & 滑动镜片**<br>[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L220-L280) | 采样层 `4.dp`；前景镜片 **`0.dp`**（中心清晰） | 静止选中灰：亮 `Black @ 12%` / 暗 `White @ 12%`（随按压淡出）；运动态 `Black @ 3% * progress` | 无（不绘制镜片） | 采样层 `vibrancy()`；无噪点 | 镜片按 `48/56` 缩：`lens(8.57.dp, 12.dp) * progress` + **`chromaticAberration = true`** | `Highlight.Default * progress`、`Shadow * progress`、`InnerShadow(6.86.dp * progress)`（静止全为 0） |
| **详情顶栏按钮 & 底部写回复/操作组**<br>[LiquidButton.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidButton.kt#L82-L128)、[TopBarMaterial.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopBarMaterial.kt#L128-L153)、[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L165-L234) | `2.dp` | 图标按钮/写回复/赞组：`surface @ 30%`；<br>进度胶囊/跳楼条：`surface @ 45%` + 进度填充 `LogoYellow @ 27%`；<br>关注实心按钮：`tint = LogoYellow`（Hue + `75%`） | `surface @ 94%`（`tint` 按钮 `tint @ 94%`） | `vibrancy()`；无噪点 | `lens(12.dp, 24.dp)`；无色散 | `drawBackdrop` 默认高光/阴影 + 触点 `InteractiveHighlight` + 按压 `tanh` 阻尼微移 |
| **回复底部面板 `CommunitySheet`**<br>[CommunitySheet.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/CommunitySheet.kt#L108-L122) | **亮 `18.dp` / 暗 `10.dp`** | **亮 `surface @ 68%` / 暗 `surface @ 52%`** | `surface @ 94%` | 无 `vibrancy`/噪点 | `lens(20.dp, 40.dp, depthEffect = true)` | 描边 `1.dp` `onSurface @ 16%`（无采样回退 `8%`）；背后暗罩 `Black @ 38%` |
| **居中弹窗 `LiquidModal`**<br>[LiquidModal.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidModal.kt#L94,L120-L128) | **亮 `16.dp` / 暗 `8.dp`** | **亮 `surface @ 60%` / 暗 `surface @ 40%`** | `surface @ 96%` | `colorControls(brightness = 亮 0.2f / 暗 0f, saturation = 1.5f)` | `lens(24.dp, 48.dp, depthEffect = true)` | `Highlight.Plain`；`RoundedRectangle(36.dp)` |
| **编辑器 `@` 提及浮层**<br>[EditorMentions.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorMentions.kt#L108-L117) | `24.dp` | 亮 `White @ 82%` / 暗 `#202020 @ 82%` | `94%` | 无 | 无 | `shadow(3.dp)`，`12.dp` 圆角 |

---

### 3. 顶部透明标签栏（结构、滚动吸顶、荧光笔高光）
- **结构与分层**（[FeedScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/feed/FeedScreen.kt#L84-L175)）：
  - 内容区 `HorizontalPager` 挂 `.layerBackdrop(backdrop)` 并占满全屏（L117），通过 `onSizeChanged` 测出顶栏总高度 `headerHeight` 后以 `PaddingValues(top = contentTopPadding)` 传递给内部 `LazyColumn`（L84-85, 218），使卡片滚动时从顶栏及状态栏后方经过。
  - 顶栏作为兄弟 `Column` 浮于上方（L137-175）：上行是可折叠搜索栏（最小 `44dp`，含 `28dp` 头像/Logo 与 `30dp` 内层搜索胶囊，L146-164）；下行是常驻标签行（`FeedTabBar` `weight(1f)` + 右侧 `48dp` `EditNote` 编辑首页 Tab 按钮，L165-174）。
- **滚动行为**：使用 Material 3 `TopAppBarDefaults.enterAlwaysScrollBehavior()`（L78, 86）。**上滑先折叠搜索行，标签行吸顶不隐藏；稍向下滑立即展开搜索行**。同时 `collapsedFraction`（`0..1`）驱动顶栏背景由**纯色不透明 `surface` 平滑变为 `20%` 透明 + `4.dp` 模糊 + `24.dp` 折射的玻璃**（L139-144）；切换分页时清零 `heightOffset`（L106-109）。
- **选中指示器与排版**（[BalancedTabRow.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/BalancedTabRow.kt#L18-L35)、[FeedScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/feed/FeedScreen.kt#L238-L258)）：
  - **自适应排布**：`BalancedTabRow` 用 `TextMeasurer` 按 `17.sp Bold` 测最宽标签（每项至少 `widest + 24.dp`，下限 `48.dp`），全能放下则等分居中（`weight(1f)`），否则启用 `horizontalScroll` 并由 `BringIntoViewRequester` 自动滚入选中项。
  - **字号/字重**：选中 `17.sp / 24.sp` `FontWeight.Bold` `onSurface`；未选中 `15.sp / 21.sp` `FontWeight.Normal` `onSurfaceVariant`（注：普通字号下单项高约 `34dp` 低于 48dp 无障碍推荐值）。
  - **荧光笔高光绘制**：在 `Text` 上直接用 `Modifier.drawBehind` 画圆角矩形（`cornerRadius = 3.dp`），位于文字下半部 `topLeft = Offset(0f, size.height * 0.58f)`、`size = Size(size.width, size.height * 0.34f)`，三段横向渐变 `Brush.horizontalGradient(LogoYellow @ 0.25f → 0.95f → 0.40f)`（L248-L252）。**没有位移动画，随 `selected == value` 直接切显**。

---

### 4. 底部悬浮胶囊导航与中间“+”按钮
- **尺寸与间距**（[KudoApp.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/KudoApp.kt#L257-L260)、[Glass.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/Glass.kt#L25-L49)、[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L70-L71,L204,L282)）：
  - 外层 `navigationBarsPadding().padding(20.dp).widthIn(max = 530.dp).fillMaxWidth()`。
  - 胶囊高 `56.dp`（内边距 `4.dp`），5 等分；图标 `22.dp` + `2.dp` 间距 + `10.sp / 14.sp` 文字（[LiquidBottomTab.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTab.kt#L48)）。
  - **中间“+”按钮**（`actionIndex = 2`）：固定 `44.dp × 34.dp` `CircleShape` 实心 `LogoYellow` 底 + `24.dp` `Ink` 色 `+`/搜索图标（[Glass.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/Glass.kt#L34-L37)）。
- **选中态与跟手交互**：
  - 静止态：当前格背后垫 `Black @ 12%`（亮）/ `White @ 12%`（暗）透明中性灰胶囊（宽 `tabWidth`、高 `48.dp`，四边留 `4dp`）；选中文字/图标用 `LogoYellow`。
  - 拖动/按压态：整个底栏按 `1 + 16dp / width` 微放大；灰色选中底淡出，前景镜片放大至 `78/56 ≈ 1.39×` 高度且变宽 `+8dp`，**透明且对经过的黄色“+”按钮产生真实光学折射**（因先在隐藏层 `tabsBackdrop` 放大绘制了一遍内容，再通过 `CombinedBackdrop` 喂给前景镜片，[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L209-L259)）。
  - 预选与防误触：拖动实时计算 `previewIndex` 让“原选中项 + 手指经过项”双高亮，但 `actionIndex = 2` 强制不作为 `previewIndex` 落点，松手才切页，拖至中间松手会弹回原项（[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L133-L138,L168)）。
- **随滚动显隐**：**底栏不随列表滚动隐藏**，列表通过 `bottom = 112.dp` `contentPadding` 避让（[FeedScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/feed/FeedScreen.kt#L218)）。仅有回顶悬浮按钮 `QuickScrollToTop`（`32.dp` 圆形 `LiquidButton`）在滚过一屏且上滑时淡入、键盘弹出时隐藏（[QuickScrollToTop.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/QuickScrollToTop.kt#L103,L133-L140)）。

---

### 5. 帖子详情顶栏与底部三段式工具条
- **顶栏**（[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L62-L101)、[TopicScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicScreen.kt#L241-L245)）：
  - `MaterialPageScaffold` 承载 `screenMaterial` 透明毛玻璃顶栏（高 `min 60.dp` + `statusBarsPadding()`）。
  - 左侧：`40dp` 圆形返回按钮（`48dp` 触控区）+ 可点击作者行（`32dp` 圆形头像 + 昵称 `titleMedium` + 时间/编辑历史）；右侧：设备标识 + `40dp` 高实心 `LogoYellow` “关注/已关注”胶囊 + `40dp` 圆形“更多”按钮。
- **底部三段式工具条**（[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L136-L254)）：
  - 位于屏幕底部 `navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp)`，使用自定义 `Layout` 排三段（`gap = 8.dp`）：
    1. **左“写回复”胶囊**：`LiquidButton`（`surface @ 30%`，`minHeight = 48.dp`），`20dp` 笔形图标 + `labelLarge` 文案；`maxWidth < 320dp` 或大字号/万楼窄屏下退化为纯图标胶囊（L154, 165-188）。
    2. **中“阅读进度胶囊”**：`LiquidButton`（`surface @ 45%`），占满左右之间剩余宽；
    3. **右“点赞 / Boost（或编辑）”胶囊**：非整块点击容器（`onClick = null`，`surface @ 30%`），内含两个独立 `48dp` 按钮（L215-234）。
- **阅读进度计算与绘制**：
  - **计算**（[TopicScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicScreen.kt#L188-L198)）：过滤掉完全位于顶部 `contentPadding` 下的楼层（`offset + size > 0`）；若到底且无更多分页则取末楼 `last.number`，否则取最靠上的可见 `Post.number`；分母为 `highestPostNumber`。文本用 `BasicText` + `TextAutoSize.StepBased(9.sp..12.sp)` 显示 `"$floor / $highest"`。
  - **进度绘制**（[LiquidButton.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidButton.kt#L124-L126)、[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L198)）：在 `drawBackdrop` 的 `onDrawSurface` 内先画 `surface @ 45%`，再按 `progress = floor / highest` 从左到右画矩形 `drawRect(LogoYellow.copy(alpha = 0.27f), size = Size(size.width * progress, size.height))`，由外层 `Capsule()` 裁切自然形成胶囊内进度填充。
- **点按展开与列表联动**（[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L158-L161,L208-L251)、[TopicFloorNavigation.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicFloorNavigation.kt#L26-L88)）：
  - **点按中段胶囊不弹 Sheet**，而是触发 `expansion`（`0→1`，`tween(280, FastOutSlowInEasing)`）动画：左/右两段淡出并向左右滑出屏幕，中段原地扩至全宽变成 `FloorNavigationBar`（内含 `LiquidSlider` + 测宽数字输入框 + `/总楼层` + 关闭/确认动态图标按钮），容器附 `imePadding()` 顶在数字键盘上方。
  - 拖动 `LiquidSlider` 仅在本地维护 `0..1` 连续浮点位置并同步更新输入框文本预览（[LiquidSlider.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidSlider.kt#L94-L129)），**不实时触发列表滚动**；点击确认才调用 `jumpTo(number)` 跳转并收起内联条。

---

### 6. 回复编辑面板
- **组件选择**：未使用 Material 3 `ModalBottomSheet`，而是自写同窗口浮层 **`CommunitySheet`**（[EditorScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorScreen.kt#L50-L55)、[CommunitySheet.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/CommunitySheet.kt#L42-L145)），放于 `TopicScreen` 外层 `Box` 中作为同窗口兄弟节点（[KudoApp.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/KudoApp.kt#L440-L473)）。
- **IME 上推与高度**：Activity 声明 `adjustResize`（[AndroidManifest.xml](file:///tmp/kudo/app/src/main/AndroidManifest.xml#L44)），`CommunitySheet` 内层 `BoxWithConstraints` 挂 **`Modifier.imePadding()`**（[CommunitySheet.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/CommunitySheet.kt#L105)）；弹出后一帧自动请求焦点并唤起键盘（[EditorScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorScreen.kt#L85-L87)）。面板内部依次为 `32dp` 顶部拖动把手（`36×5dp` 胶囊条）、限制 `heightIn(max = 300.dp)` 的表单（输入框限 `80..180dp`，占位 `"回复 $displayName"`）、字数校验行 `"0 / 16 字"` 与底行可横滚 `EditorToolbar` + 右侧固定 `"发布"` 按钮（[EditorScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorScreen.kt#L116-L164)）。格式/表情子面板通过根部 `EditorToolBackdropHost` 跨面板弹出，防止被 `300dp` 容器裁切（[EditorToolbar.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorToolbar.kt#L66-L97,L222-L243)）。
- **毛玻璃背景**：父级对 `TopicScreen` 挂 `.layerBackdrop(replyBackdrop)`（[KudoApp.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/KudoApp.kt#L441)），`CommunitySheet` 对 `28.dp` 顶部圆角做 `drawBackdrop`（亮 `18.dp` / 暗 `10.dp` 模糊、`lens(20.dp, 40.dp, depthEffect = true)`、亮 `68%` / 暗 `52%` 着色、降级 `94%`、`1.dp` 描边、`38%` 黑遮罩随拖拽淡出）。
- **展开到全屏编辑的方式**：**kudo 的回复面板内没有“展开到全屏”按钮或切换途径**。`EditorScreen` 的紧凑回复态（`reply = true`）与全屏发帖态（`reply = false`，从底栏 `+` 或分类 FAB 进入，[KudoApp.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/KudoApp.kt#L161,L549-L567)）在进入前已按路由分流。

---

### 7. 动效参数总览
- **底栏与液态控件**（[DampedDragAnimation.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/DampedDragAnimation.kt#L43-L52,L143-L149)、[LiquidBottomTabs.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidBottomTabs.kt#L114)、[InteractiveHighlight.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/InteractiveHighlight.kt#L34-L37)）：
  - 位置点击/吸附：`spring(1f, 1000f)`（真拖中改 `CoroutineStart.UNDISPATCHED` + `snapTo` 直接跟手）；
  - 速度形变：`spring(0.5f, 300f)`（停手 `100ms` 归零）；
  - 按压进度：`spring(1f, 1000f)`；按压缩放 `scaleX/Y`：`spring(0.6f, 250f)` / `spring(0.7f, 250f)`（比 `78/56`）；
  - 整栏水平弹性：`spring(1f, 300f, 0.5f)`；触点高光：`spring(0.5f, 300f)`。
- **面板与弹窗**：
  - `CommunitySheet`（[CommunitySheet.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/CommunitySheet.kt#L81,L103,L113)）：遮罩淡入 `180ms` / 淡出 `160ms`；面板上滑 `240ms` / 下滑 `180ms`；回弹 `spring(stiffness = Spring.StiffnessMediumLow)`。
  - `LiquidModal`（[LiquidModal.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/LiquidModal.kt#L100,L115-L116)）：`fadeIn(180ms) + scaleIn(180ms, 0.94f)` / `fadeOut(140ms) + scaleOut(140ms, 0.96f)`。
- **楼层控制 & 转场**：
  - 底部进度条内联展开：`tween(280, FastOutSlowInEasing)`（[TopicControls.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/TopicControls.kt#L159)）。
  - NavHost 切换：进 `280ms` / 出 `180ms` / 返回 `220ms`（[KudoApp.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/KudoApp.kt#L208-L209)）；帖子整卡进 `300ms` / 退 `480ms`（[TopicTransition.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopicTransition.kt#L59-L60)）；头像共享元素 `360ms`（[AvatarTransition.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/AvatarTransition.kt#L127)）；看图进 `280ms` / 退 `240ms`（CubicBezier `0.2,0,0,1`）+ 回弹 `spring(0.9f, 500f)`（[ImageScreen.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/ImageScreen.kt#L93,L115)、[GalleryTransform.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/topic/GalleryTransform.kt#L118)）。

---

### 8. 性能、模糊层数量与已知卡顿/崩溃处理
- **模糊层数量与实时采样**：
  - **首页滚动时**：吸顶顶栏（1 层 `drawPlainBackdrop`）+ 底栏（**3 层 `drawBackdrop`** ：底板 + 隐藏放大采样层 `tabsBackdrop` + 组合镜片）+ 上滑出现的回顶圆钮（1 层 `LiquidButton`），即**同屏有 4–5 个 Backdrop 渲染节点在实时模糊正在滚动的 `LazyColumn`**。
  - **帖子详情页**：顶栏 `screenMaterial`（1）+ 顶栏 2–3 个 `LiquidButton` + 底部 3 个 `LiquidButton` + 回顶/目录圆钮（1–2），弹回复再叠 `replyBackdrop` + `CommunitySheet`（若再弹工具/提及层又叠按需 `LayerBackdrop`）。列表卡片内部不使用毛玻璃（仅 NSFW 封面 `Modifier.blur(14.dp)`）。
- **已验证的工程防坑与卡顿处理**（均来自源码及 `.agents/notes/`）：
  1. **防循环采样 Native Crash**：早期分类/消息顶栏因将自身放入祖先 `layerBackdrop`，在 RenderThread `libhwui.so` 触发 `SIGSEGV`。修复铁律是**采样源必须与毛玻璃表面为严格兄弟节点**（[TopBarMaterial.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopBarMaterial.kt#L54)、[2026-10-16-directory-material-render-crash.md](file:///tmp/kudo/.agents/notes/implemented/bug-fix/2026-10-16-directory-material-render-crash.md#L7-L15)）。
  2. **按需创建/销毁采样层**：`EditorToolBackdropHost` 仅在工具或 `@` 浮层打开时创建 `rememberLayerBackdrop()` 并挂 `.layerBackdrop()`（[EditorToolbar.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/editor/EditorToolbar.kt#L70,L79-L80)）；`WebPageScaffold` 明确不建采样层以免 WebView 进入实时模糊录制（[TopBarMaterial.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopBarMaterial.kt#L45-L51)）。
  3. **进场卡顿错峰**：`TopicTransitionBody` 在 300ms 进场动画未完成前延迟重量级正文排版组合，三段骨架扫光仅在 `Canvas` 绘制期读取动画值，避免重组（[TopicTransition.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/TopicTransition.kt#L149-L160)、[2026-10-20-topic-opening-render-budget.md](file:///tmp/kudo/.agents/notes/implemented/bug-fix/2026-10-20-topic-opening-render-budget.md#L9-L19)）。
  4. **高刷跟手拖动优化**：`DampedDragAnimation` 最初每次拖动重启 `spring()` 导致一加 13 滞后，改为 `CoroutineStart.UNDISPATCHED` 立即 `snapTo`，弹簧只管点击/松手吸附（[DampedDragAnimation.kt](file:///tmp/kudo/app/src/main/java/com/kudo/android/ui/components/liquid/DampedDragAnimation.kt#L143-L144)、[2026-10-11-navigation-drag-tracking.md](file:///tmp/kudo/.agents/notes/implemented/bug-fix/2026-10-11-navigation-drag-tracking.md#L7-L11)）。
  5. **未关闭的性能缺口**：kudo 的 [TASKS.md](file:///tmp/kudo/TASKS.md#L95) 明确注明 **Android 12 模糊分支、平板、折叠屏及正式帧率基准均未实测**，且缺少省电模式/降低透明度自动降级；同时其首页 20% 透明 + 4dp 模糊顶栏在笔记中也承认会让高对比配图透出较明显（[2026-10-16-home-header-liquid-material.md](file:///tmp/kudo/.agents/notes/implemented/bug-fix/2026-10-16-home-header-liquid-material.md#L40)）。

---

## 二、与 hipda-next 规范及代码现状逐项对照表

> 已核对 `docs/design/product-design.md`（§3–§7）、`docs/design/copy.md`、`hipda/.../ui/designsystem/glass/{HiGlassSurface,GlassEnvironment}.kt`、`hipda/.../ui/shell/{HiAdaptiveShell,HiNavigationPill,ShellIndicatorLayout,HiNavigationSidebar,ShellBarState}.kt`、`hipda/.../ui/designsystem/motion/HiMotion.kt`。

| 维度 | kudo-android 怎么做 | hipda-next 规范怎么写 | hipda-next 代码现状 | 核心差距与诊断 |
|---|---|---|---|---|
| **毛玻璃引擎与性能守卫** | `kyant0:backdrop:1.0.6` + `shapes:1.2.0`（API 33+ AGSL 折射+色散、API 31–32 模糊、API 26–30 94%纯色）；仅在设置手动切 `GlassMode` | `tech-plan.md §4` 选定 Haze 2.x；`product-design.md §5, §10.3` 要求同屏 ≤ 2 块毛玻璃；4 路自动降级（API ≤ 30 / 省电 / 降低透明度 / 高对比度）+ 降级 ≥ 4.5:1 | `HiGlassSurface.kt:42-367`（Haze 2.1.0）；`G1` 已落地 `Navigation`（`10dp`, `0.45/0.46`, `sat 1.18`, `noise 0.04`, 渐变高光 `0.75dp`）与 `Toolbar` 预留档（`16dp`, `0.60/0.68`, `sat 1.08`）；`GlassEnvironment.kt:48-245` 实现 4 路系统监听 | 我方**基础玻璃品质与降级完备度不输甚至更稳健**（缺的只是“透明顶栏/详情三段工具条/回复面板”的场景应用与部分互动细节），无须为单一折射效果换库 |
| **毛玻璃规范文本滞后** | — | `product-design.md:228` 仍写旧值 `24dp`、着色 `70%/60%`、`0.5dp 40%/12%` 均匀边 | 代码 `HiGlassTokens`（`HiGlassSurface.kt:42-80`）已在 `G1` 调优为 `10dp/16dp`、`0.45/0.46` 等并待用户实机拍板 | 规范文本落后于 `G1` 实装代码，待用户实机选定参数后需同步回写 `§5` |
| **论坛顶部栏与版块标签** | 透明吸顶毛玻璃（`4dp` 模糊、`20%` 着色）；上滑折叠搜索行、Tab 吸顶；`BalancedTabRow` 等分/横滑；选中 `17sp Bold` + 横向渐变荧光笔底条；右侧编辑按钮 | `§3.1` 论坛顶栏+版块标签行随滚动**一起收起**；`§5`（L226）规定**“顶栏纯色，不用毛玻璃”**（`surface` → `surfaceContainer`）；`≥ 600dp` 隐藏版块标签行 | 尚在 M5 前（`E1` 未开工）；`ShellNavigationMode` 与 `HiNavigationSidebar` 已就位，缺业务页落地 | 1. 现规范禁止顶栏用毛玻璃，导致列表滑动缺乏通透延伸感；<br>2. 现规范滚动时标签行全收起，不如“折叠标题/操作、吸顶版块标签行”顺手切版块；<br>3. 现规范未定义荧光笔高光与 `BalancedTabRow` |
| **底部悬浮导航胶囊** | `56dp` 高，5 槽（含居中 `44×34dp` 实心黄 `+` 按钮）；静止灰底 `12%`；支持横向拖动跟手、双高亮与前景折射；不随滚动隐藏 | `§3.1, §5`：`64dp` 最小高（紧凑 `48dp`），最宽 `420dp`，4 项（`论坛/消息/搜索/我的`）；`§6.3` 随滚动滑出，IME 弹出隐藏；`≥ 600dp` 消失 | `HiNavigationPill.kt:17-62`、`ShellIndicatorLayout.kt:54-276`（`G1` 已改半透明 `primary @ 12%` 指示器 + `spatialFast` 滑动）；`ShellBarState.kt` 跟手隐显 | 1. 我方已有半透明指示器与滚动隐显（比 kudo 更省空间），但胶囊仅支持点击切页，不支持横向拖拽预选；<br>2. 我方无中间快捷“+”发新帖按钮（发新帖在论坛顶栏） |
| **帖子详情顶栏** | `screenMaterial` 透明毛玻璃顶栏，左右独立 `40dp` 圆形胶囊返回/更多 + 作者行 + 右侧关注实心胶囊 | `§5` 纯色顶栏（未滚动 `surface` → `surfaceContainer`）；`§4.1` 大屏右栏顶栏含 `全屏阅读 / 显示列表` 按钮 | `E2a` / `E3` 未开工 | 缺少透明毛玻璃顶栏与独立圆形动作按钮的轻盈层级 |
| **帖子详情底部工具条** | **三段式分离胶囊**（左“写回复”、中带 `LogoYellow @ 27%` 进度条的“N / M”胶囊、右点赞/Boost 组）；点中段原地滑开左右变为内联滑块+数字输入条（跟键盘上推） | `§5`（L234）写为**单条**毛玻璃工具条含“回复”、“第 N / M 页”和“更多”；`§7.3` 点页码从底部升起**跳页面板**（Sheet，含输入框、滑块、`首页/-1/+1/末页/跳转`）；`§4.1` 右栏窄宽自动切换紧凑态 | `HiGlassStyle.Toolbar` 令牌已预留（`16dp`、`0.60/0.68`、`sat 1.08`），`E4b` 未开工 | 1. 单条合集不如 kudo 三段式直觉且缺乏阅读进度填充条可视化；<br>2. 跳页另起弹窗不如原地内联展开轻量，但 HiPDA 长帖高频需要 `首页/末页` 快捷按钮 |
| **回复编辑与完整编辑器** | `CommunitySheet`（毛玻璃 `18/10dp`、`68%/52%`、`28dp` 顶部圆角、把手、限高 `300dp`、`imePadding()` 上推、工具行+发布）；**无全屏展开入口** | `§7.3` 点“回复”升起**纯输入条**（随键盘逐帧联动）+ `§7.4` 表情键盘等高互换；`§4.2` 完整编辑器（含图片条）在窄屏全屏、大屏居中模态或右栏展开 | `E5` / `E6` / `E7` 未开工 | 1. 我方“纯输入条”缺拖动把手、背景毛玻璃、便捷格式快捷按钮与呼吸感；<br>2. kudo 缺从回复面板一键展开到全屏完整编辑器（多图上传/分类/标题等）的通道 |
| **动效语言** | 多处手写 `tween(180..480)` 与 `spring(...)`，`DampedDragAnimation` 按实测高刷改 `snapTo` 跟手 | `§6.1-6.2, §10.1` 严禁业务写 `tween`/`spring`，全部走 `HiMotion` 7 档 M3 `MotionScheme.standard` 弹簧 + `MotionTokenScanTest` 扫码门禁 | `HiMotion.kt:32-201`、`MotionTokenScanTest.kt:12-150` 已生效（白名单 0 条） | 采纳 kudo 任意交互（如拖拽/内联展开）时，**绝不可引入手写 `tween`/`spring`**，必须全量映射为 `HiMotion` 令牌 |

---

## 三、采纳建议与改造方案

### 1. 单向门决策：是否引入新依赖（`kyant0:backdrop` + `shapes`）？

| 方案 | 利 | 弊与风险 |
|---|---|---|
| **方案 A（不推荐）：引入 `io.github.kyant0:backdrop:1.0.6` + `shapes:1.2.0` 替换/并存** | 1. API 33+ 可获得真边缘透镜折射 `lens()` 与色散；<br>2. 可直接复用/微调 kudo `ui/components/liquid/` 现成源码。 | 1. **推翻既有成果**：废掉已通过 CI 的 `C3` + `G1`（`HiGlassSurface`、`HiGlassHazeConfig`、`GlassTuningPanel`、截图基线），重复投入；<br>2. **触发共享文件与 R8 风险**：必须改动冻结目录 `libs.versions.toml`，并重新走 `release` 混淆冒烟（[lesson.md:13](file:///home/mir/Documents/hipda-next/docs/agents/lesson.md#L13)）与随包许可审查；<br>3. **多栏与兼容性红利有限**：`lens()` 仅 API 33+ 有效，API 31–32 退回普通模糊；三栏同时多个 `layerBackdrop` + `CombinedBackdrop` 离屏绘制在折叠屏 120Hz 下更易超帧预算；<br>4. **违背项目规则**：[DISPATCH.md §5](file:///home/mir/Documents/hipda-next/docs/agents/DISPATCH.md#L54) 强调优先用 Compose 标准组件，而 `LiquidBottomTabs` 含多层隐藏采样和手写 `pointerInput` 拦截器，维护成本高。 |
| **方案 B（推荐）：不加新依赖，继续留用 `Haze 2.1.0` + Compose 标准组件** | 1. **零单向门风险**：不碰 `libs.versions.toml` 和既有 C3/G1 架构；<br>2. **观感 90% 可在 Haze 上实现**：用户直观认可的“透明毛玻璃吸顶标签+荧光笔高光、三段式进度工具条+内联展开、毛玻璃跟手回复面板”均是**布局、着色密度、信息分层与动效**问题，Haze 2.1.0 原生支持 `hazeBlur`、`colorFilter` 提饱和、`tint` 渐变罩、`noiseFactor` 及 `progressive` 渐隐（见 [G-glass.md:145-157](file:///home/mir/Documents/hipda-next.wt/_reports/G-glass.md#L145-L157)）；<br>3. **守住 4 路自动降级与性能门禁**：完整保留 `GlassEnvironment` 与 `HiGlassMarker` 同屏数量断言。 | 1. 暂缺 API 33+ 的边缘凹凸透镜弯曲/色散（属于“锦上添花”而非可读性/交互核心痛点）。 |

> **建议**：**不引入新依赖**，用现有 Haze 2.1.0 落地。若将来真机实测完 Haze 方案后用户仍强烈需要边沿透镜折射，再单开 **C3b（二期折射评估）** 独立验证，不阻断当前 M5/E 线推进。

---

### 2. 最值得采纳的 5 项改造建议（附设计稿章节与卡片映射）

#### 建议 1：论坛页透明毛玻璃吸顶标签栏 + 荧光笔选中高光（不吸顶全收起）
- **改设计稿哪一节**：`product-design.md` §3.1（论坛行）、§5（“顶栏”“毛玻璃”）、§6.3（“栏随滚动”）、§10.3。
- **怎么改**：
  1. **滚动策略**：`< 600dp` / 铰链双栏时，论坛顶栏（版块名 + `发新帖` + `更多`）随向下滚动收起，而下方**版块标签行**（首项 `今日新帖` + 关注版块 + 右侧固定 `管理版块` 按钮）**吸顶不隐藏**，稍微上划即可重新展开顶栏；内容从标签行背后滚过（列表用 `contentPadding(top = ...)` 避让）并被实时模糊。
  2. **样式与适配**：标签使用自适应 `BalancedTabRow` 逻辑（放得下时等分居中，放不下时横向滚动并自动滚入居中）；选中项字重提升/略放大，文字下半部绘横向渐变荧光笔底条（使用 `MaterialTheme.colorScheme.primary` 或 `primaryContainer`，**不抄 kudo 品牌黄**）。每项触控区**严格保留 `48dp` 高**（满足 §10.1 无障碍，视觉可紧凑）。
  3. **参数与大屏**：顶栏毛玻璃**不抄 kudo 的 `4dp + 20%`**（防配图花眼导致对比度不达 4.5:1），采用 `HiGlassStyle.Toolbar` 档（或 `14–16dp` 模糊、亮 `0.62–0.70` / 暗 `0.68–0.74` + `4` 路降级）。`≥ 600dp` 侧栏态中栏仍**隐藏版块标签行**（避免与左栏重复），仅中栏顶栏随滚动显现轻量毛玻璃或维持纯色（见第 3 节拍板项）。
- **对应卡**：`毛玻璃定稿`（补充顶栏毛玻璃样式与 §5 回写）+ **`E1`**（论坛页实现版块标签行与列表 `hiGlassSource`）。

#### 建议 2：帖子详情底部“三段式毛玻璃工具条 + 进度填充 + 原地内联跳楼”
- **改设计稿哪一节**：`product-design.md` §4.1（窄右栏紧凑态）、§5（“帖子详情的底部工具条”）、§6.3、§7.3（“跳页面板”）、`copy.md`（“读帖、跳页与位置”）。
- **怎么改**：
  1. **三段独立胶囊**（`HiGlassStyle.Toolbar` 档，`48dp` 高、段间 `8dp`）：
     - 左：**回复胶囊**（笔形图标 + `回复`；右栏窄宽 `< 384dp` 或大字号自动缩为纯图标）；
     - 中：**阅读进度胶囊**（`第 N / M 页`，窄态 `{N}/{M}`），胶囊内左起绘制 **`primary @ ~22%–27%` 半透明填充色**直观表示阅读进度（按可见首楼所在页/总页数计算）；
     - 右：**操作组胶囊**（按 HiPDA 功能替换为 **`收藏` + `更多`**，两个独立 `48dp` 触控区，不含“助推”）。
  2. **点按中段原地内联展开**（代替另弹 BottomSheet 遮挡正文）：用 `HiMotion.spatialDefault` 将左右两段淡出滑离，中段原地扩至工具条全宽，展开为单行内联跳楼条（`Slider` + 页码输入框 + `/M` + 快捷跳转按钮/`×`/`✓` 动态图标，输入聚焦时 `imePadding()` 顶在键盘上方）；若用户更需要 `首页 / 末页` 一键直达，可在滑块下方或长按保留轻量快捷芯片，且任何跳转执行后自动收起。
  3. **三栏/大屏适配**：在三栏与铰链双栏下，整组三段工具条居中放于**右栏底部**（距底 `8dp + 手势区`），不跨中右分隔线。
- **对应卡**：**`E4b`**（底部工具条页码、进度填充与内联跳页面板）+ **`E5`**（对接左段快速回复入口）。

#### 建议 3：帖子详情“透明毛玻璃极简顶栏 + 圆形动作按钮”
- **改设计稿哪一节**：`product-design.md` §5（“顶栏”“毛玻璃”）、§4.1/§4.2（右栏全屏按钮）、§10.3。
- **怎么改**：
  1. 取消详情顶栏的实色分割感：内容全屏滚动并通过 `contentPadding(top = ...)` 避让，顶栏背景使用渐变/轻护读毛玻璃（与正文共享 `hiGlassSource` 为兄弟节点，防采样环）。
  2. 顶栏动作（左 `返回` 圆形按钮 + 标题/楼主简讯；右大屏独有的 `全屏阅读 / 显示列表` 与 `只看楼主` / `更多`）采用独立轻量圆形/胶囊容器（`48dp` 触控区包裹 `40dp` 视觉圆钮，`surfaceContainerLowest/High` 半透明底 + 细高光描边），**不搬运** Discourse 实心黄“关注作者”按钮。
- **对应卡**：**`E2a`**（详情顶栏与楼层列表容器）+ **`E3`**（三栏右栏顶栏 `全屏阅读` 适配）。

#### 建议 4：快速回复升级为“毛玻璃跟手弹出编辑面板 + 一键展开全屏”
- **改设计稿哪一节**：`product-design.md` §4.2（快速回复/完整编辑器）、§6.3（键盘联动）、§7.3（“回复”）、§7.4（编辑器）。
- **怎么改**：
  1. **面板形态**：点工具条“回复”或楼层“引用回复”时，从底部升起贴边毛玻璃编辑面板（基于 Compose 标准容器 + 同窗口兄弟 `hiGlassSource` 采样，`20dp` 顶部圆角符合 §5 大圆角规范，顶部 `32dp` 拖动把手支持向下跟手拖关并自动保存 `reply:<tid>` 草稿，背后内容被模糊）。
  2. **跟键盘与内嵌工具**：通过 `imePadding()` 随键盘升起；面板内限高展示被引用提示/占位 `"回复 {用户名}"`、输入框、冷却倒计时（`{N}` 秒）+ `发送` 胶囊按钮，以及单行横向滚动的常用快捷工具（**图片、表情、引用/粗体等**，其中表情支持与键盘等高原地切换 §7.4）。
  3. **补齐 kudo 缺失的“展开全屏”通道**：面板右上角增加 **`48dp` 展开全屏图标按钮**（或手势上拉越过阈值展开），无缝带着当前输入文本、光标、已上传图片进入 `E6` 完整编辑器（大屏按 §4.2 规则切换居中模态或右栏展开）。
  4. **三栏位置**：在 `≥ 680dp` 三栏/铰链双栏下，该毛玻璃编辑面板仅在**右栏底部**宽度内升起（最大宽受右栏限制，左栏与中栏不遮挡、可继续浏览）。
- **对应卡**：**`E5`**（毛玻璃快速回复面板与冷却倒计时）+ **`E6`**（展开至完整编辑器与多图条衔接）+ **`E7`**（面板内表情与键盘等高互换）。

#### 建议 5：底部导航胶囊跟手拖拽预选交互（以及可选的中间快捷“+”按钮）
- **改设计稿哪一节**：`product-design.md` §3.1、§5（“导航胶囊”）、§6.3（“选择指示器”）。
- **怎么改**：
  1. **跟手拖拽与预选高亮**：在 `ShellIndicatorLayout.kt` 现有 `primary @ 12%` 半透明滑动指示器基础上，给窄屏底部胶囊增加横向手势跟踪（手指按下拖动时指示器用 `snapTo` 实时跟手滑过各目的地，`previewIndex` 使经过的标签临时高亮，松手用 `HiMotion.spatialFast` 吸附并切页，拖出或取消弹回原项，不依赖 `kyant0:backdrop`）。
  2. **关于中间“+”按钮（见第 3 节拍板项）**：仅在底部胶囊存在时（`< 600dp` / 矮屏 / 铰链）可考虑在中间加入圆形 `发新帖` 快捷按钮（形成 `论坛 / 消息 / + / 搜索 / 我的` 5 槽），拖动经过不触发、不作为预选落点；`≥ 600dp` 无底部胶囊时仍由中栏顶栏承担 `发新帖`。
- **对应卡**：可单开一张轻量外壳微调卡（如 **`C4d`**，不阻塞 `BATCH-4` 与 `E1` 前期结构），或并入 **`毛玻璃定稿`** 收口卡。

---

### 3. 需要用户拍板的 4 个产品与架构问题

1. **全屏毛玻璃上限（§5 / §10.3“最多 2 块”）如何调整？**
   - 若同时采纳“透明顶栏 + 底部胶囊/三段工具条”：单栏下同屏仍是 2 块；但在 **`≥ 680dp` 三栏**下，如果**中栏顶栏 + 右栏顶栏 + 右栏底部工具条**全开毛玻璃会变成 **3 块**，且同时采样两个独立滚动列表有 GPU 负载风险。
   - **选项**：(A) **推荐**：仅**单栏列表页顶栏 + 详情页顶栏 + 底部胶囊/工具条**用毛玻璃，三栏态下**中栏列表顶栏保持纯色**（或仅右栏底工具条+单栏列表顶栏开毛玻璃），严守同屏 `≤ 2` 块；(B) 放宽为三栏最多 3 块，并在 Fold8 真机用 Macrobenchmark 实测 120Hz 帧率再定。
2. **底部胶囊是否增加中间圆形“+”（发新帖）按钮？**
   - **选项**：(A) 保持 4 项（`论坛/消息/搜索/我的`），`发新帖` 统一放在论坛顶栏（与 `≥ 600dp` 左侧栏+中栏顶栏完全一致，`340dp` 分屏也宽松）；(B) `< 600dp` 底部胶囊改为 5 槽居中放圆形 `+` 发帖按钮（单手高频发帖更顺手，类似 kudo）。
3. **详情跳楼交互：完全内联替换 vs 保留“首页 / 末页”快捷入口？**
   - kudo 内联条只有 `滑块 + 输入框 + 取消/确定`；HiPDA 动辄百页长帖，常需一键跳 `首页` / `末页` / `-1` / `+1`。
   - **选项**：(A) 采纳三段工具条 + 原地内联展开，并在展开后滑块上方/旁紧凑附带 `首页` / `末页` 小胶囊（兼顾直观与长帖效率）；(B) 保持点中段进度胶囊后从底部升起标准跳页面板。
4. **“毛玻璃定稿”参数基准如何收敛？**
   - 用户安装合入 `G1` 与 `C4c` 的 debug 包后，建议在真机「HiPDA 预览 → 外壳 → 调参」分别确认两组参数：(1) 底部导航胶囊档；(2) 若决定采纳透明顶栏与三段工具条，其**护读毛玻璃参数**是否沿用 `Toolbar` 档（`16dp`、`0.60/0.68`、`sat 1.08`）或略微调透（如 `14dp`、`0.52/0.58`）。
