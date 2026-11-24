# 移动端大屏与折叠屏设计调研报告（面向 hipda-next）

> 调研定位：只读外部设计规范、开源 Jetpack Compose 项目与主流闭源大屏标杆应用，为 HiPDA（4d4y.com）论坛客户端 `hipda-next` 的 Kotlin + Jetpack Compose + Material 3 + Material 3 Adaptive 现代化重构提供可验证、可直接落地的架构、布局、材质与动效模式。
>
> 证据约定：所有事实均标注原始来源（官方 URL，或开源仓库 `文件:行号` + `commit hash`）；官方未公开或开源实现未涉及的维度标注“未知”或“文档未公开”，不编造版本、参数或链接。

---

## 1. 调研总览与参考清单

本次共选取 **9 个核心参考**（含 2 套官方规范与动效标杆、4 个开源 Jetpack Compose 项目/样本集、3 个闭源大屏标杆应用），另在模式实现阶段补充核实了 **ReadYou**（开源 Compose 阅读器）与 **Haze 2**（Compose 模糊/玻璃材质库）的源码实现。

| 编号 | 参考名称 | 类别 | 核心价值 | 核实来源 / Commit |
| :--- | :--- | :--- | :--- | :--- |
| R1 | Material 3 Adaptive + M3 Expressive + Android Adaptive App Quality | 官方规范 | 5 档窗口断点、3 种规范布局、Tier 1–3 质量分级、官方弹簧 Token | Android Developers 官方文档；`androidx/androidx` (`6eae178508e7748f0a6a90515dede899d6d792c2`) |
| R2 | Apple HIG（iPadOS Split Views / Sidebars / Motion / Materials）与 SwiftUI 弹簧体系 | 官方规范与动效标杆 | 2/3 栏分栏、可收起侧边栏、Liquid Glass 分层原则、可打断弹簧动效哲学与预设参数 | Apple Developer Documentation JSON/Web；WWDC18 #803；WWDC23 #10158 |
| R3 | Harmonic for Hacker News | 开源 Compose 论坛客户端 | 700dp 折叠屏双栏断点、方向独立记忆+铰链磁吸分隔条、720dp 阅读列宽、Haze 2 三档材质、AOSP 预测性返回 | `github.com/SimonHalvdansson/Harmonic-HN` (`d25cdc5365e1e49bc5bccbdcb345034dded297f9`) |
| R4 | Capy Reader | 开源 Compose 阅读客户端 | Navigation 3 + `ListDetailSceneStrategy`、多档锚点拖拽分隔条、同窗口跨双栏看图浮层、双源“减少动态效果” | `github.com/jocmp/capyreader` (`7639ad309966f791bdd75cfded545e6f2fc9f610`) |
| R5 | Now in Android | 开源 Compose 官方参考 | `NavigationSuiteScaffold` 顶层导航切换、Feed 自适应多列瀑布流与可拖拽滚动条、Interests 列表-详情占位 | `github.com/android/nowinandroid` (`2982e63cb691ad49cd009aab43dc206fee6699c2`) |
| R6 | Android Compose Official Samples（Reply / JetNews / Jetcaster） | 开源 Compose 官方样本 | Book/Separating/Tabletop 折叠姿态、BottomBar/Rail/PermanentDrawer 三段切换、Nav3 自定义双栏与 `SharedTransitionLayout` | `github.com/android/compose-samples` (`09aae2013b3e5897f9679e2b7539f505f4f6f7f6`) |
| R7 | Gmail（Android 大屏/折叠屏版） | 闭源标杆 | 可拖拽分栏分隔条（支持推至边缘退回单栏）、横屏链接/附件右半屏并排展开、侧边导航栏 | Google Workspace Updates 官方博客（2023-10-20、2025-04-25） |
| R8 | Samsung One UI 与 Galaxy Z 原生应用 | 闭源标杆 | Flex Mode 半折上下分区、App Continuity 无缝内外屏切换、多实例并行与 3+ 窗口多任务 | Samsung Developer Galaxy Z 官方文档 |
| R9 | Telegram（Android 版） | 闭源标杆 | 2026 新一代界面重构提速、透明/玻璃与动效的 `Power Saving` 功耗分级控制；同时提供折叠屏强制分栏反例 | Telegram 官方博客（2026-01-03、2026-02-09）及公开社区反馈 |

*补充源码参考*：
- **ReadYou**：`github.com/Ashinch/ReadYou`（commit `d2b979ccad9a3e54b9499929a9dc2824578b0fd9`）——沉浸阅读收起列表栏时动态放宽文本列宽。
- **Haze 2**：`github.com/chrisbanes/haze`（commit `f6e4b5795d4060364c9396d5adb44745c35972be`）——Compose 跨 API 级别模糊/玻璃材质降级基线。

---

## 2. 9 个参考的七维度对比调研

### R1. Google Material 3 Adaptive / M3 Expressive / Android Adaptive App Quality（官方规范）

- **来源**：
  - 窗口尺寸类：https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
  - 规范布局总览：https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts
  - 列表-详情：https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail
  - 辅助面板：https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout
  - 自适应导航：https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
  - 折叠屏感知：https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware
  - 大屏质量指南：https://developer.android.com/docs/quality-guidelines/adaptive-app-quality
  - M3 Expressive 动效：https://m3.material.io/styles/motion/overview/how-it-works ；https://m3.material.io/blog/building-with-m3-expressive
  - AndroidX 源码（`androidx/androidx` commit `6eae178508e7748f0a6a90515dede899d6d792c2`）：`PaneScaffoldDirective.kt`、`NavigationSuiteScaffold.kt`、`PaneMotion.kt`、`PaneExpansionState.kt`、`ListDetailPaneScaffold.kt`、`ThreePaneScaffoldPredictiveBackHandler.android.kt`、`MotionScheme.kt`、`ExpressiveMotionTokens.kt`、`StandardMotionTokens.kt`、`DragHandleTokens.kt`。

1. **导航形态随宽度的切换与过渡**：
   - 官方 5 档宽度断点（`use-window-size-classes`）：Compact（`<600dp`）、Medium（`600–839dp`）、Expanded（`840–1199dp`）、Large（`1200–1599dp`）、Extra-large（`≥1600dp`）；3 档高度断点：Compact（`<480dp`）、Medium（`480–899dp`）、Expanded（`≥900dp`）。
   - `NavigationSuiteScaffoldDefaults.navigationSuiteType()`（`NavigationSuiteScaffold.kt:1168-1181`）：宽度 Compact → `ShortNavigationBarCompact`（底栏，图标在上）；桌面模式（`isTabletop`）或高度 Compact → `ShortNavigationBarMedium`（底栏，图标在左）；其余（Medium/Expanded+ 且非桌面模式）→ `WideNavigationRailCollapsed`（可展开宽侧栏，取代旧版 `NavigationRail` / `PermanentDrawerSheet`，源码注释第 652–653、1129、1140 行明确建议迁移至 `WideNavigationRail`）。
   - 过渡与状态：通过 `NavigationSuiteScaffoldState`（`rememberSaveable` 持久化，L143–149）支持隐藏/显示动画；通过 `movableContentOf` 在导航形态切换时尽量保留导航项组合状态（L80、L667）。
2. **列表-详情与规范布局分栏规则**：
   - 官方 3 种规范布局：List-Detail（`NavigableListDetailPaneScaffold` / `ListDetailPaneScaffold`）、Supporting Pane（`NavigableSupportingPaneScaffold`）、Feed（网格流）。
   - 默认分栏阈值（`PaneScaffoldDirective.kt:48-76, 345-348`）：Compact/Medium 默认 `maxHorizontalPartitions = 1`（`spacer = 0.dp`），Expanded 为 `2` 栏（`spacer = 24.dp`，`defaultPanePreferredWidth = 360.dp`），Large/XL（`≥1200dp`）为 `3` 栏（`defaultPanePreferredWidth = 412.dp`）。另提供 `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth()`（L127-148）在 Medium 宽度启用双栏（但文档提示需防过挤）。
   - 可拖动分隔条：`ListDetailPaneScaffold` 默认不渲染拖柄，传入 `paneExpansionDragHandle` 与 `PaneExpansionState` 后开启拖拽（`ListDetailPaneScaffold.kt:42-51`）；支持按比例（`PaneExpansionAnchor.Proportion`）或按起止偏移（`PaneExpansionAnchor.Offset.fromStart/fromEnd`）设吸附锚点，并通过 `rememberPersistentlyWithKey` 跨生命周期持久化（`PaneExpansionState.kt:268-275, 800-890`）。M3 `VerticalDragHandle` 视觉尺寸默认为 `4×48dp`，按下/拖拽时形变为 `12×52dp`、颜色由 `Outline` 变为 `OnSurface`（`DragHandleTokens.kt:23-62`）。
   - 空详情占位与第三级页面：`ListDetailPaneScaffold` 原生提供三槽位 `List`（Secondary）、`Detail`（Primary）、`Extra`（Tertiary）（`ListDetailPaneScaffold.kt:224-259`）；详情页再打开下一级时可推入 `Extra` 槽位（Expanded 双栏下切为 `Detail + Extra`，Large 三栏下三栏并列），或按需改用独立全屏路由/浮层。
3. **折叠、展开、旋转时的状态保持**：
   - Android Adaptive App Quality Tier 3（基础必过项，`adaptive-app-quality`）明确要求：不得 letterbox、不得锁死方向或禁用 `resizeableActivity`、配置变更/折叠/分屏调整后必须保持关键 UI 状态（滚动位置、用户输入、媒体状态）。
   - Compose 层通过 `rememberSaveable`、`ThreePaneScaffoldNavigator` 内置历史栈、`PaneExpansionStateDataSaver`（`PaneExpansionState.kt:933-971`）维持选中项与分栏比例。
4. **铰链与桌面模式（Tabletop / Book Posture）**：
   - `collectFoldingFeaturesAsState()` 监听 Jetpack WindowManager 的 `FoldingFeature`（`state`: `FLAT`/`HALF_OPENED`、`orientation`: `HORIZONTAL`/`VERTICAL`、`occlusionType`: `NONE`/`FULL`、`isSeparating`）（`make-your-app-fold-aware`）。文档明确提醒：**API 不暴露精确铰链角度**，不要写依赖具体角度的脆弱动画。
   - `calculatePaneScaffoldDirective()` 默认 `HingePolicy.AvoidSeparating` 自动将分隔型竖向铰链放入 `excludedBounds`（`PaneScaffoldDirective.kt:50, 103-104, 150-157`），把物理折痕当作双栏天然分隔线；当 `isTabletop` 为真时将 `maxVerticalPartitions` 设为 `2`（`verticalPartitionSpacerSize = 24.dp`，`defaultPanePreferredHeight = 420.dp`，L81-94）。
5. **窄窗口与分屏多窗口**：
   - 窗口尺寸类明确基于**应用当前可用窗口尺寸**而非物理屏幕尺寸（`use-window-size-classes`）；同时考虑高度尺寸类（如手机横屏/翻盖展开时宽度 Medium 但高度 Compact，不适合双栏，应回落单栏+底栏）。
   - Adaptive App Quality 分级（`adaptive-app-quality`）：Tier 3（Adaptive ready：全窗口、不黑边、状态保持、基础键鼠触控笔支持）、Tier 2（Adaptive optimized：多栏响应式布局优化、增强外设支持）、Tier 1（Adaptive differentiated：折叠姿态适配、多实例、跨窗口拖拽、桌面级悬停/滚轮体验）。
6. **动效与预测性返回**：
   - M3 Expressive 推出以物理弹簧为核心的 `MotionScheme`（`MotionScheme.kt:45-261`），区分 **Spatial（空间移动/尺寸/圆角，可微弹）** 与 **Effects（颜色/透明度，阻尼比 1.0 零过冲）**，并分 Fast / Default / Slow 三档速度（精确参数见第 4 节）。
   - 分栏动画默认使用 `PaneMotionDefaults.AnimationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)`，与 M3 Expressive Default Spatial 完全一致（`PaneMotion.kt:155-160`）。
   - 共享元素：`SharedTransitionLayout` + `Modifier.sharedElement()`（视觉一致内容）与 `Modifier.sharedBounds()`（容器变换，视觉不同内容）（`shared-elements`）。
   - 预测性返回：`NavigableListDetailPaneScaffold` 内置 `ThreePaneScaffoldPredictiveBackHandler`，使用 `CubicBezierEasing(0.1f, 0.1f, 0f, 1f)` 映射手势进度，并按当前可见栏数缩放预览比例（单栏 `0.1f`、双栏 `0.15f`、三栏 `0.2f`）（`ThreePaneScaffoldPredictiveBackHandler.android.kt:90-104`）。
7. **毛玻璃/半透明材质与降级**：
   - Material 3 官方组件体系以不透明 Tonal Surface 层级（`surfaceContainerLowest` 至 `surfaceContainerHighest`）和 Scrim 为主，未将大面积毛玻璃作为默认规范；官方不建议在无降级保护的低端设备上强行实时模糊（详见第 3 节 Haze 2 实践）。

---

### R2. Apple HIG（iPadOS Split Views / Sidebars / Motion / Materials）与 SwiftUI 弹簧体系（官方规范与动效标杆）

- **来源**：
  - Split Views：https://developer.apple.com/design/human-interface-guidelines/split-views
  - Sidebars：https://developer.apple.com/design/human-interface-guidelines/sidebars
  - Motion：https://developer.apple.com/design/human-interface-guidelines/motion
  - Materials：https://developer.apple.com/design/human-interface-guidelines/materials
  - SwiftUI Spring 与预设：https://developer.apple.com/documentation/swiftui/spring/init(duration:bounce:) ；https://developer.apple.com/documentation/swiftui/animation/smooth(duration:extrabounce:) ；`snappy(duration:extrabounce:)` ；`bouncy(duration:extrabounce:)`
  - WWDC 视频：WWDC18 #803 *Designing Fluid Interfaces*（https://developer.apple.com/videos/play/wwdc2018/803/ ）；WWDC23 #10158 *Animate with springs*（https://developer.apple.com/videos/play/wwdc2023/10158/ ）。

1. **导航形态随宽度的切换与过渡**：
   - Sidebar 仅在水平和垂直空间充足时展示在首侧（leading edge）；空间受限时退化为更紧凑的 Tab Bar，或采用 iPadOS 支持“顶部 Tab Bar ↔ 左侧 Sidebar”动态形态切换的模式（`sidebars`）。
   - 侧边栏默认不应隐藏（确保可发现性），但允许用户通过边缘滑出或顶栏按钮主动收起以放大阅读区；侧边栏内层级不超过两级，超过两级应使用三栏 Split View，在侧边栏和详情之间插入内容列表栏（`sidebars`）。
2. **列表-详情的分栏规则**：
   - iPadOS 支持 2 栏（如 Mail：列表+详情）或 3 栏（如 Keynote：侧边栏+内容+检查器），仅在 Regular 宽度环境下分栏，Compact 宽度绝不强行挤压分栏（`split-views`）。
   - **选中态持久高亮**：所有通向详情栏的前置分栏必须持久高亮当前选中项，帮助用户建立空间定位（`split-views`）。
   - 分隔线与可调尺寸：偏好 `1pt` 细分隔线以最大化内容区；若允许拖拽调整栏宽，必须设合理的最小/最大宽度下限，防止拖得太窄导致分隔条“消失”；允许隐藏辅助分栏时必须提供多种恢复路径（顶栏按钮、菜单/快捷键）（`split-views`）。
   - 详情再打开下一级：支持三栏布局（Primary → Secondary → Tertiary）或在详情栏内维持独立导航栈。
3. **折叠/尺寸变化/旋转时状态保持**：
   - Apple 无折叠屏硬件，但 iPadOS 多窗口/Stage Manager 允许任意自由缩放窗口；HIG 要求布局在窄窗口、紧凑与中间宽度下都能维持合理导航逻辑，不丢失当前焦点内容（`split-views`）。
4. **铰链与桌面模式**：
   - 不适用（iPadOS 无物理铰链）。
5. **窄窗口与分屏多窗口**：
   - 明确要求同时针对窄窗口、Compact 与中间宽度设计分栏降级，多栏自动折叠为单栏导航栈（`split-views`）。
6. **动效原则与量化标杆**：
   - **HIG Motion 原则**（`motion`）：动效必须有目的性，绝不为动而动；反馈动效要**简短、精准**；**高频交互一般不要叠加额外动效**（系统标准轻反馈已足够，避免每次点击都在等动画）；**必须允许随时打断/取消动效**，绝不让用户等待动画播完才能继续操作；遵守手势方向一致性（从顶部下拉出现，就不该侧滑关闭）；绝不把动效作为传递重要信息的唯一通道。
   - **WWDC18 Fluid Interfaces**（#803）：手势与思考并行，交互随时可重定向；打断时必须从**当前实时呈现值（live presentation value）**起算而非瞬间跳回逻辑目标；手势松手时必须把手指末速度（`initialVelocity`）注入弹簧进行动量投射。
   - **WWDC23 Animate with Springs**（#10158）与 SwiftUI 官方文档：用直观的 `duration`（感知时长）+ `bounce`（弹性，`0` 临界阻尼无过冲、`>0` 欠阻尼弹跳、`<0` 过阻尼）取代晦涩参数；三大内置预设默认 `duration = 0.5s`：`.smooth`（`bounce = 0.0`）、`.snappy`（`bounce = 0.15`）、`.bouncy`（`bounce = 0.3`）。
   - **Reduce Motion**：尊重系统减少动态效果设置，将大幅位移/缩放/弹跳替换为平滑淡入淡出或更强阻尼的零过冲过渡（`motion`）。
7. **毛玻璃/半透明材质与降级**：
   - HIG Materials（`materials`）将材质明确分层：**Liquid Glass 仅用于浮于内容之上的功能控制层（如 Tab Bar、Sidebar、工具栏、弹窗）**，绝不能滥用到正文内容层（会制造视觉噪声和层级混乱）；内容层中的滑块/开关仅在被激活瞬间短暂呈现玻璃态。
   - 分两个变体：**Regular**（自动模糊并调整背景亮度以保证文字/控件可读性，适用于含大量文字的侧边栏、弹窗、提示框）与 **Clear**（高透，仅适合盖在图片/视频等丰富媒体之上，亮背景下需人工垫暗色遮罩）。
   - 无障碍降级：系统开启“降低透明度（Reduce Transparency）”或“增强对比度”时，自动回退为高对比不透明/纯色底座（`materials`）。

---

### R3. Harmonic for Hacker News（开源 Jetpack Compose 论坛/讨论客户端）

- **来源**：https://github.com/SimonHalvdansson/Harmonic-HN （commit `d25cdc5365e1e49bc5bccbdcb345034dded297f9`，Compose Multiplatform + Material 3 + Material 3 Adaptive + Navigation 3 + Haze 2.1.0）。

1. **导航形态随宽度的切换与过渡**：
   - 主界面为论坛信息流+评论详情双层结构（无传统多 Tab 底栏，采用顶部过滤器芯片栏 + 独立 Settings/Submissions/Editor 路由栈，`HarmonicAppRoot.kt:87-96`）；设置页内部采用主从分栏列表（`SettingsScaffold.kt:135-200`）。
   - 页面切换使用仿 Android 原生 Activity 的 `ActivityNavigationTransition`（`ActivityNavigationTransition.kt:36-106`）：`96.dp` 水平位移、`450ms` M3 Emphasized `PathEasing` 曲线、伴随 `83ms` 线性淡入（延迟 `50ms`）/淡出（延迟 `35ms`），并用离屏 `GraphicsLayer` 边缘采样拉伸避免边缘露黑（L203-267）。
2. **列表-详情的分栏规则**：
   - **核心亮点——针对折叠屏定制 700dp 断点**（`HarmonicPaneScaffoldDirective.kt:13-27`）：注释直言 Material 默认 `840dp` 对书本折叠屏（如展开宽度 700–839dp）偏晚，而一刀切降到 `600dp` 又会让所有 Medium 窄窗口太挤，因此仅当 `supportsTwoPane && isFoldable && windowWidth >= 700.dp` 时调用 `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo)`，非折叠屏仍沿用 `840dp`！
   - 折叠屏判定：`adaptiveInfo.windowPosture.hingeList.isNotEmpty() || context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)`（`AndroidMainNavigationHost.kt:208-210`）。
   - 分栏默认比例与间距：折叠屏默认 `0.5f` 对半、`spacer = 12.dp`；平板读取资源权重 `stories_pane_weight` 与 `margin_between_panes`（`AndroidMainNavigationHost.kt:213-240`）。
   - **可拖动分隔条 + 方向独立记忆 + 铰链磁吸**（`SplitPaneViewport.kt:99-351`、`SplitRatioPreferences.kt:3-25`）：
     - 比例严格钳制在 `0.3f..0.7f`，避免任何一栏被压扁；按窗口宽高分别存储 `Portrait` 与 `Landscape` 两套比例。
     - 折叠屏开启 `snapToCenter`：拖入 `0.47f..0.53f` 自动吸附 `0.5f` 折痕中线；内部把 `rawRatio` 与吸附后 `ratio` 分离防止手指黏死在 50%，并带速度继承的 `spring(stiffness = 2500f, visibilityThreshold = 0.0001f)` 磁吸落位（`SplitPaneViewport.kt:103, 153-170`）。
     - 拖柄热区 `48.dp × 64.dp`，视觉胶囊静态 `4×32dp`（`onSurfaceVariant`）、按压/拖拽变为 `8×42dp`（`primary`）（`tween(150, FastOutSlowInEasing)`，L269-284），同时支持 TalkBack `ProgressBarRangeInfo` 语义与键盘方向键 `±5%` 微调（L285-303）。
     - 浏览器展开折叠列表时，`horizontalPartitionSpacerSize` 按 `animatedRatio / Minimum` 线性归零，消除到达全屏瞬间的间距跳变（L190-195）；`SplitPaneListContent` 在折叠区保持最小测量宽度并裁剪，避免文字疯狂折行重排（L355-366）。
   - **阅读宽度约束**：`CommentsReadingWidth.kt:15-18` 用 `.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)` 将评论区限制在最大 `720.dp` 居中，同时保持全宽滚动热区。
   - **空详情占位与三级页面**：`MainNavigationScene.kt:86-97` 在 `ListDetailSceneStrategy.listPane(detailPlaceholder = ...)` 展示空详情占位；详情内点开外链网页时由 `BrowserSplitSession` 接管同屏联动，发帖/回复编辑器与沉浸看图作为独立栈顶路由覆盖（`HarmonicAppRoot.kt:87-95`）。
3. **折叠、展开、旋转时状态保持**：
   - **极佳实践——`movableContentOf` 跨形态搬移帖子组件**（`HarmonicAppRoot.kt:63-80`）：单栏全屏看帖与双栏右侧看帖切换时，按 `request.serial` 将 `comments` 包在 `movableContentOf` 中搬移，**完整保留 WebView / 评论滚动位置与局部状态**，不重新加载！
   - 设置页通过 `LocalSettingsListState` 把 `LazyListState` 托管在 Navigation 3 栏数重建边界之外（`SettingsScaffold.kt:103-108`）；配合 `rememberSaveableStateHolderNavEntryDecorator()` 保存路由状态（`MainNavigationScene.kt:125`）。
4. **铰链与桌面模式**：
   - 通过 `isFoldable` + `0.5f` 默认比例与 `0.47f..0.53f` 磁吸显式对齐竖向折痕；单元测试 `HarmonicPaneScaffoldDirectiveTest.kt:54-60` 验证了 700dp 折叠屏同时保留 `calculatePaneScaffoldDirective` 的 Tabletop 策略，但源码中未见专门为 Tabletop 定制上下分屏 UI（评论与文章均为竖向长滚动阅读，适合保持完整高度）。
5. **窄窗口与分屏多窗口**：
   - 通过 `LocalConfiguration.current.smallestScreenWidthDp >= 600` 门控 `supportsTwoPane`（`AndroidMainNavigationHost.kt:207`），窄窗口或分屏半宽自动退化为单栏，并配合 `commentsReadingWidth()` 保证宽窄窗口均有合适边距。
6. **动效与预测性返回**：
   - 列表项进入详情（双栏内切换）：`PaneDetailSwitchIn`（`PaneMotion.kt:26-83`）做 `0.85f → 1f` 缩放（`300ms` M3 Emphasized `PathEasing`）+ 延迟 `50ms` 的 `50ms` 线性淡入，且刻意不双挂载旧 WebView。
   - 容器变换（Container Transform）：`AskMotion.kt:70-197` 与 `CommentActionSharedTransition.kt:103-250` 基于 `GraphicsLayer` 离屏录屏实现源卡片到浮层/问答页的无缝几何插值与圆角同步插值，且在渲染期读取进度避免对话子树逐帧重组（L80, L101）。
   - M3 Expressive 按压形变按钮：`ExpressiveButtons.kt:20-125` 封装 `ButtonDefaults.shapes()`，按钮按压时圆角随弹簧形变。
   - 预测性返回：`DefaultActivityPredictiveBackAnimation.kt:40-164` 使用 AOSP `BackGestureInterpolator`（`CubicBezierEasing(0.1f, 0.1f, 0f, 1f)`），离屏录制整页避免 Haze 采样坐标偏移（L62-63），手势过程缩至 `0.85f`、跟随手指纵向位移（上限 `height/20 - 8.dp`）、贴合真实屏幕物理圆角，松手时结合手势速度完成收尾。双栏模式下按返回键交回系统处理任务根返回，除非详情内浏览器历史或浮层需要优先消费（`AndroidMainNavigationHost.kt:275-280`）。
   - 减少动态效果：多处读取 `MotionDurationScale` 协程上下文并在单测中验证 `scaleFactor = 0f` 下的行为（`IosReaderModePreview.kt:54`、`SettingsModalLayoutTest.kt:344`）。
7. **毛玻璃/半透明材质与降级**：
   - 集成 Haze 2.1.0，提供 **Solid（纯色）/ Blur（磨砂）/ Glass（折射玻璃）** 三档用户可切换材质（`Haze.kt:114-210`）。
   - 针对不同元素分权重调光：`FloatingButton` 全高光、`Dialog` 中等高光、`Subtle`（返回按钮/徽章）降为 `0.4×` 高光与 `0.5×` 环境响应（L135-150）；自适应光学按尺寸调节模糊半径 `64dp→12dp`、`176dp→19.2dp`、`220dp→20dp`（L170-178）。
   - 容器变换期间用 `CompositingStrategy.Offscreen` + `BlendMode.Plus` 叠加过渡，避免半透明到半透明形变时出现中间帧亮度塌陷（L68-80）；`HazeBlurStyle` 显式配置 `fallbackColorEffect` 兜底低版本降级（L202）。

---

### R4. Capy Reader（开源 Jetpack Compose RSS 阅读器）

- **来源**：https://github.com/jocmp/capyreader （commit `7639ad309966f791bdd75cfded545e6f2fc9f610`，minSdk 30，Navigation 3 `1.2.0` + Material 3 Adaptive Navigation3 `1.3.0` + Material 3 `1.5.0-alpha29`）。

1. **导航形态随宽度的切换与过渡**：
   - 采用窗口级 `ModalNavigationDrawer` 承载订阅源/分类列表（类似论坛版块树），刻意将 Drawer 提升至 `NavDisplay` 之上，使抽屉打开时的遮罩能覆盖左右双栏（`App.kt:99-126`）；当列表页离场时用 `LaunchedEffect(hasDrawerContent)` 自动关闭抽屉避免残留空抽屉（L108-113）。
   - 抽屉仅在已打开状态开启手势（`gesturesEnabled = drawerState.isOpen`，L122），避免和系统边缘返回手势或列表左滑操作冲突。
2. **列表-详情的分栏规则**：
   - 使用官方 `rememberListDetailSceneStrategy<NavKey>` + `calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())`，断点遵循 M3 默认 `WIDTH_DP_EXPANDED_LOWER_BOUND`（`840dp`）（`App.kt:75-97`、`LayoutHelpers.kt:10-23`）。
   - **可拖动分隔条 + 多档锚点 + 一键全屏阅读**（`ArticlePaneExpansion.kt:34-134`、`App.kt:84-96`）：
     - 使用 M3 官方 `VerticalDragHandle` + `Modifier.paneExpansionDraggable`。
     - 锚点序列设计极其实用：`0f`（详情全屏、列表收起）→ `defaultPanePreferredWidth + spacer/2`（M3 默认推荐宽）→ `30%..70%` 每 `5%` 一档 → `1f`（列表全屏、详情隐藏）。
     - 通过 `AppPreferences.paneExpansionAnchor` 持久化用户习惯比例，并用 `lastAnchorIndex` 记录上次分栏位置；文章顶栏提供“全屏/还原”按钮，调用 `toggleFullscreen()` 在 `0f` 与 `lastAnchorIndex` 间平滑切换（L61-69, 108-116）。
   - **空详情占位**：`Route.ArticleList` 的 `detailPlaceholder` 居中显示 `CapyPlaceholder()`（`App.kt:164-174`、`ArticleDetailScreen.kt:103-111`）。
   - **详情内打开下一级（图片/视频看图）**：自定义 **`MediaSceneStrategy`（Navigation 3 `OverlayScene`）**（`MediaSceneStrategy.kt:25-75`、`App.kt:140, 216-223`）——在同窗口直接叠画在左右双栏之上（不使用平台 `Dialog` 避免断层与转场丢失），淡入淡出 `220ms`，且作为真实回退栈条目天然接入系统返回手势！
   - **连续切帖不撑爆回退栈**：`openArticle()` 当栈顶已是 `ArticleDetail` 时直接原地替换栈顶条目（`this[lastIndex] = ...`），保持栈结构恒为 `[list, detail]`（`App.kt:261-267`）。
3. **折叠、展开、旋转时状态保持**：
   - `NavDisplay` 配置 `rememberSaveableStateHolderNavEntryDecorator()` + `rememberViewModelStoreNavEntryDecorator()`（`App.kt:136-139`），每个路由独立保留状态与 ViewModel。
   - `Route.ArticleDetail` 设置稳定 `clazzContentKey = { "article_detail" }`（`App.kt:188`），在双栏或上下篇切换时复用详情框架，顶栏不闪烁、不重建。
   - `ArticlePaneExpansion` 用 `rememberSaveable` 保存 `lastAnchorIndex` 并在 `AppPreferences` 落盘，横竖屏与折叠展开恢复用户栏宽偏好（`ArticlePaneExpansion.kt:96-116`）。
4. **铰链与桌面模式**：
   - 通过 `currentWindowAdaptiveInfoV2()` + `calculatePaneScaffoldDirective()` 自动继承 M3 Adaptive 的 `HingePolicy.AvoidSeparating` 避让分隔铰链；未单独定制 Tabletop 专用上下界面。
5. **窄窗口与分屏多窗口**：
   - 低于 `840dp` 自动切回单栏（`LayoutHelpers.kt:21-23`），且 `WindowInsets.listPaneInsets()` 在双栏展示时只消费 `Start + Vertical` 边距、单栏或详情隐藏时消费全边安全区（`ArticlePaneExpansion.kt:137-145`），防止双栏中缝多出重复系统栏内边距。
6. **动效与预测性返回**：
   - 仅在手机单栏场景下对 `ArticleDetail` 应用 Material Shared-Axis-X 动效（偏移量为屏幕宽度的 `10%`，淡出占前 `35%` 时长、淡入延迟 `35%` 占后 `65%` 时长），平板/折叠展开时双栏共享同一 Scene 不会误触发滑动（`App.kt:189-205, 241-253`、`CapyAnimatedPane.kt:29-57`）。
   - `SettingsScaffold` 使用 `NavigableListDetailPaneScaffold`（`BackNavigationBehavior.PopUntilContentChange`）内置预测性返回（`SettingsScaffold.kt:20-34`）；`NavDisplay` 配置了 `predictivePopTransitionSpec`（`App.kt:202-205`）。
   - **减少动态效果双源接管**（`AppMotionDurationScale.kt:7-30`、`MainActivity.kt:31, 40-47, 59-62, 76-84`）：实现 `MotionDurationScale` 注入 `createLifecycleAwareWindowRecomposer(coroutineContext = motionDurationScale)`，在 `onResume()` 读取系统 `Settings.Global.ANIMATOR_DURATION_SCALE`，同时叠加应用内 `reduceMotion` 偏好（含墨水屏设备自动开启），开启时统一返回 `0f`！
7. **毛玻璃/半透明材质与降级**：
   - 未使用实时毛玻璃模糊，以纯色 Material 3 主题与高对比阅读底色为主（兼顾电子墨水屏 `EInkDevice`）。

---

### R5. Now in Android（开源 Jetpack Compose 官方生产级参考应用）

- **来源**：https://github.com/android/nowinandroid （commit `2982e63cb691ad49cd009aab43dc206fee6699c2`，minSdk 24，Navigation 3 + Material 3 Adaptive）。

1. **导航形态随宽度的切换与过渡**：
   - 封装 `NiaNavigationSuiteScaffold`，基于 `NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(windowAdaptiveInfo)` 在 Compact 宽/高或 Tabletop 姿态显示底栏 `NavigationBar`，在 Medium/Expanded+ 显示侧边 `NavigationRail`（`Navigation.kt:186-234`、`NiaApp.kt:163-191`）。
   - 侧边 Rail 设为透明容器融入背景渐变，底部栏 `tonalElevation = 0.dp`，保持大屏简洁统一（`Navigation.kt:104, 169, 225-229`）。
2. **列表-详情与 Feed 分栏规则**：
   - **Feed 规范布局**：`ForYouScreen.kt:166-174` 使用 `LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(300.dp))`，随窗口从手机单列平滑扩展为折叠屏双列、平板三列+，并配 `DraggableScrollbar` 提升大屏长流定位效率（L232-243）。
   - **Interests 列表-详情**：`NiaApp.kt:258, 268-272` 在 `NavDisplay` 注册 `rememberListDetailSceneStrategy<NavKey>()`；`InterestsEntryProvider.kt:33-37` 声明 `ListDetailSceneStrategy.listPane { InterestsDetailPlaceholder() }`，未选主题时右侧呈现圆角卡片引导占位（`InterestsDetailPlaceholder.kt:39-64`）。
   - 分隔条：未提供可拖拽分隔条（使用 M3 默认比例）。
3. **折叠、展开、旋转时状态保持**：
   - 通过 `NiaAppState` + `NavigationState` 维护多顶层 Tab 独立回退栈；弹窗开关等轻状态使用 `rememberSaveable`（`NiaApp.kt:97`）；`LazyVerticalStaggeredGrid` 通过 `rememberLazyStaggeredGridState()` 保持滚动位置（`ForYouScreen.kt:156`）。
4. **铰链与桌面模式**：
   - 由 `currentWindowAdaptiveInfo()` 自动流入 `NiaNavigationSuiteScaffold`（Tabletop 切回底栏）与 `rememberListDetailSceneStrategy`（默认避让分隔铰链）。
5. **窄窗口与分屏多窗口**：
   - `StaggeredGridCells.Adaptive(300.dp)` 与 `WindowInsets.safeDrawing` 分离消费（`NiaApp.kt:198-256`）确保任何窄分屏窗口均自动收束为 1 列且不遮挡系统栏/IME 键盘；包含多尺寸截图回归测试 `NiaAppScreenSizesScreenshotTests.kt`。
6. **动效与预测性返回**：
   - 同步加载指示条使用 `AnimatedVisibility` + 顶部滑入淡入（`ForYouScreen.kt:210-218`）；页面切换由 Navigation 3 `NavDisplay` 默认转场驱动；源码中未发现大规模共享元素动画或自定义弹簧体系。
7. **毛玻璃/半透明材质与降级**：
   - 不使用实时模糊；以 `NiaGradientBackground` 浅色径向/线性渐变与纯色 `Surface` 构造视觉层次（`NiaApp.kt:99-106`）。

---

### R6. Android Compose Official Samples（Reply / JetNews / Jetcaster）

- **来源**：https://github.com/android/compose-samples （commit `09aae2013b3e5897f9679e2b7539f505f4f6f7f6`）。

1. **导航形态随宽度的切换与过渡**：
   - **Reply**（`ReplyNavigationComponents.kt:77-169`）：展示完整四段式切换逻辑——`isTabletop` 或宽高任一 Compact → 底栏 `NavigationBar`；宽 `≥1200dp`（Large+）→ 左侧常驻抽屉 `PermanentDrawerSheet`（宽 `200–300dp`，内含 `ExtendedFloatingActionButton`）；其余 Medium/Expanded → 左侧 `NavigationRail`（顶部带菜单按钮可呼出 `ModalNavigationDrawer` + 图标版 `FAB`）。高度为 Medium+ 时侧栏导航项垂直居中（`ReplyNavigationContentPosition.CENTER`，更贴合大屏双手握持拇指热区，L103-107, 458-468）。
   - **JetNews**（`JetnewsApp.kt:54-95`）：Compact/Medium 用 `ModalNavigationDrawer`（开启侧滑手势）；Expanded（`≥840dp`）禁用抽屉手势并自动锁定关闭，左侧常驻 `AppNavRail`（L74-83, 102-115）。
2. **列表-详情与辅助面板分栏规则**：
   - **Reply**（`ReplyApp.kt:76-88`、`ReplyListContent.kt:80-100`）：Compact 单栏；Medium 若处于 `BookPosture` 或 `Separating` 姿态则启用双栏，否则单栏；Expanded 恒为双栏（`HorizontalTwoPaneStrategy(splitFraction = 0.5f, gapWidth = 16.dp)`）。首开大屏默认选中第一封邮件，避免空白详情页（`ReplyHomeViewModel.kt:47-51`）。
   - **JetNews**（`ListDetailScene.kt:39-132`）：展示 Navigation 3 自定义 `ListDetailScene`——在 Expanded 宽度下并排渲染 `listEntry` 与 `detailEntry`，详情切换采用 `AnimatedContent` 交叉淡入淡出，空详情显示占位（L57-69）；列表点击新文章用 `popUpTo(HomeKey)` 保持回退栈干净（`JetnewsNavDisplay.kt:71`）。
   - **Jetcaster**（`Home.kt:132-172, 237-250`）：示范 **Supporting Pane**（`SupportingPaneScaffold`），并专门修正了横屏手机高度 Compact 时强制降级为 1 栏的细节（L138-141）。
3. **折叠、展开、旋转时状态保持**：
   - Reply 将 `openedEmail`、`selectedEmails`、`isDetailOnlyOpen` 提升至 `ReplyHomeViewModel`（`ReplyHomeViewModel.kt:30-91`），并在 `ReplyInboxScreen` 共享同一个 `emailLazyListState`（`ReplyListContent.kt:76`），避免单双栏切换重置列表位置；当由双栏退回单栏且用户未显式进入纯详情态时自动回到列表态（L70-74）。
   - JetNews 用 `rememberUpdatedState(isExpandedScreen)` 确保配置变更后 `NavEntry` 内闭包总能读到最新屏幕宽度状态（`JetnewsNavDisplay.kt:64`）。
4. **铰链与桌面模式（Tabletop / Book Posture）**：
   - **Jetcaster 播放器**（`WindowInfoUtil.kt:23-41`、`PlayerScreen.kt:254-334`）是官方最完整的折叠姿态范例：
     - `isTableTopPosture`（`HALF_OPENED + HORIZONTAL`）或横向分隔铰链 → `VerticalTwoPaneStrategy(0.5f)` 上半屏展示封面与信息、下半屏放播放控制条；
     - `isBookPosture`（`HALF_OPENED + VERTICAL`）或 Expanded 宽屏 → `HorizontalTwoPaneStrategy(0.5f)` 左右分栏避开中缝；
     - 其余 → 竖屏单栏常规布局。
   - Reply 在 `ReplyApp.kt:64-74` 检测 `isBookPosture` / `isSeparating`，把 `displayFeatures` 传入 `TwoPane` 自动按物理铰链位置切分左右栏。
5. **窄窗口与分屏多窗口**：
   - Reply 与 Jetcaster 均显式判断**宽度或高度任一为 Compact** 时退回单栏+底栏（`ReplyNavigationComponents.kt:77-78`、`Home.kt:138-141`），防止翻盖/横屏分屏出现极扁双栏。
6. **动效与预测性返回**：
   - **JetNews 预测性返回 + 共享元素**（`JetnewsNavDisplay.kt:89-128`）：`SharedTransitionLayout` 包裹 `NavDisplay`；`predictivePopTransitionSpec` 使用 `unveilIn() togetherWith scaleOut(targetScale = .8f)`，且按滑动边缘动态设置缩放锚点（左侧滑返回 `TransformOrigin(0.8f, 0.5f)`，右侧滑 `TransformOrigin(0.2f, 0.5f)`，L118-127），极具跟手方向感！
   - **Jetcaster 共享元素**：`PlayerScreen.kt:350-380` 通过 `LocalSharedTransitionScope` 与 `LocalAnimatedVisibilityScope` 将列表封面平滑共享过渡到全屏播放器。
   - **Reply FAB 滚动联动**：`ReplyListContent.kt:124-125` 根据 `emailLazyListState.lastScrolledBackward || !emailLazyListState.canScrollBackward` 自动展开/收起底栏 FAB 文字。
7. **毛玻璃/半透明材质与降级**：
   - Jetcaster 采用基于封面取色的垂直/径向半透明渐变遮罩（`PlayerScreen.kt:193-199, 298-302`），无实时高斯模糊，全 API 级别性能一致。

---

### R7. Gmail（Android 大屏与折叠屏闭源标杆）

- **来源**：
  - Google Workspace Updates 官方博客（2025-04-25，可拖拽分隔条）：https://workspaceupdates.googleblog.com/2025/04/release-notes-04-25-2025.html
  - Google Workspace Updates 官方博客（2023-10-20，横屏链接/附件并排展开与拖拽）：https://workspaceupdates.googleblog.com/2023/10/release-notes-10-20-2023.html
  - Android Developers Adaptive App Quality 与生态博客：https://developer.android.com/docs/quality-guidelines/adaptive-app-quality ；https://android-developers.googleblog.com/2026/05/android-adaptive-development-ecosystem.html

1. **导航形态随宽度的切换与过渡**：
   - 手机窄屏使用底部导航栏 + 左侧抽屉（标签/文件夹）；平板与折叠屏展开态切至左侧 Navigation Rail（聚合 Mail / Chat / Meet 等顶层模块，点击菜单按钮展开标签抽屉，来源：Google Workspace 大屏更新与行业实测汇总）。切换过渡细节未公开源码。
2. **列表-详情的分栏规则**：
   - 折叠屏展开内屏与平板采用经典双栏（左邮件列表 + 右会话详情）。
   - **可拖动分隔条（2025 年 4 月上线）**：官方公告明确说明，平板与折叠屏用户可直接拖动列表与会话间的竖向分隔条，自由调节双栏宽度，且**推至一侧边缘可主动在单栏与双栏之间切换**（`release-notes-04-25-2025.html`）。
   - **详情内打开下一级（2023 年 10 月上线）**：在横屏平板与折叠屏上点击邮件内链接或附件，不会粗暴覆盖全屏，而是在**右半屏与邮件正文并排打开**，同样支持拖动分隔柄调宽或扩至全屏（`release-notes-10-20-2023.html`）。
   - 空详情占位：未选邮件时右侧展示品牌插画/引导占位（推测基于常规 Material 3 邮件双栏规范，官方更新日志未细述具体文案）。
3. **折叠、展开、旋转时状态保持**：
   - 符合 Google 自身 Adaptive App Quality 标准，内外屏切换与旋转后维持当前打开的会话与草稿输入状态；未公开具体实现代码。
4. **铰链与桌面模式**：
   - 双栏分隔天然对齐折叠屏竖向中缝；桌面模式（Tabletop）专属改造：未知（官方未见 Gmail 独立 Tabletop 文档）。
5. **窄窗口与分屏多窗口**：
   - 在分屏窄窗口自动回落单栏，并支持从其他应用（如相册、Drive）跨窗口拖拽图片/附件进入撰写区（Android 大屏生产力案例）。
6. **动效与预测性返回**：
   - 遵循 Material 3 标准过渡与系统预测性返回；具体弹簧参数闭源未知。
7. **毛玻璃/半透明材质与降级**：
   - 以 Material 3 Tonal Elevation 纯色卡片分区为主，无实时毛玻璃效果。

---

### R8. Samsung One UI 与 Galaxy Z 原生应用（闭源折叠屏系统生态标杆）

- **来源**：
  - Galaxy Z 概览：https://developer.samsung.com/galaxy-z/overview.html
  - Flex Mode 官方指南：https://developer.samsung.com/galaxy-z/flex-mode.html
  - App Continuity 官方指南：https://developer.samsung.com/galaxy-z/app-continuity.html
  - Multi-Window / Multi-Instance 官方指南：https://developer.samsung.com/galaxy-z/multi-window.html
  - One UI 官方入口：https://developer.samsung.com/one-ui

1. **导航形态随宽度的切换与过渡**：
   - One UI 核心人机工程学（单手可达区：上方展示区、下方操作区）在大屏/折叠屏内屏延展为左侧侧边栏/Rail + 右侧双栏内容区（如 Samsung Notes / My Files / Settings）；具体内部动画曲线未公开。
2. **列表-详情的分栏规则**：
   - Galaxy Z Fold 展开内屏广泛采用 50:50 或主从双栏列表-详情布局，减少层级跳转；系统级支持在多窗口中拖拽边界实时重算布局（`overview.html`、`multi-window.html`）。
   - 详情内打开更多对照内容时，三星原生应用（Samsung Internet、Samsung Notes、My Files）强调 **Multi-Instance（多实例并排）**——允许用户左右同时开两个网页或两篇笔记对照阅读（`multi-window.html`）。
3. **折叠、展开、旋转时状态保持**：
   - **App Continuity**（`app-continuity.html`）：官方将外屏 ↔ 内屏连续无缝续接列为折叠屏第一原则，强制要求声明 `android:resizeableActivity="true"`，并在配置变更时完整保存任务进度与滚动位置；若设为 `false`，系统被迫提示重启应用，体验严重断裂。
4. **铰链与桌面模式（Flex Mode）**：
   - **Flex Mode**（`flex-mode.html`）：基于 Android Jetpack WindowManager `FoldingFeature`（要求 ≥1.0.0 stable）检测 `POSTURE_HALF_OPENED` 状态，将界面沿横向折痕一分为二——上半屏承载纯观看/展示内容（视频、图片、预览），下半屏承载交互控件、导航或输入区。对于未原生适配的应用，One UI Labs 提供系统级 Flex Mode Panel 兜底（下半屏补出触控板与快捷控制）。
5. **窄窗口与分屏多窗口**：
   - One UI 在 Galaxy Z Fold 上原生支持 **2 窗口、甚至 3 个以上应用同时同屏**（含 App Pairs 与 Pop-up 浮窗），并强调跨窗口 **Drag and Drop** 拖放文字、图片与文件（`multi-window.html`）。
6. **动效与预测性返回**：
   - 要求进入/退出 Flex Mode 与分屏尺寸变化时布局过渡平滑，避免瞬间跳闪；具体参数闭源未知。
7. **毛玻璃/半透明材质与降级**：
   - One UI 顶部/弹出面板与快捷面板部分采用实时窗口模糊（基于 Android 12+ 系统级模糊能力），在低功耗或兼容模式下回退半透明底色（官方文档未公开具体模糊半径数值，标“参数未知”）。

---

### R9. Telegram（Android 大屏/高帧率与材质分级标杆 + 反例警示）

- **来源**：
  - Telegram 官方博客 2026-02-09（Android 大规模重构、底栏与 Power Saving）：https://telegram.org/blog/crafting-android-design-and-more
  - Telegram 官方博客 2026-01-03（Liquid Glass 与 Power Saving）：https://telegram.org/blog/new-design-ai-summaries
  - Telegram Android 公开源码仓：https://github.com/TelegramMessenger/Telegram-Android （注：非 Jetpack Compose，属自研 View/Canvas 渲染，此处按闭源/非 Compose 大屏参考，仅引用官方博客与公开社区评测事实）。

1. **导航形态随宽度的切换与过渡**：
   - 2026 年 2 月发布的 Android 史上最大改版重构了界面代码以提升响应速度，在手机端取消传统汉堡抽屉，改为常驻底部导航栏（Chats / Contacts / Settings / Profile）一步直达（`crafting-android-design-and-more`）。大屏/平板保留左侧会话列表+右侧聊天主视图的双栏结构。
2. **列表-详情的分栏规则**：
   - 折叠屏展开态与平板强制进入双栏“平板模式”（左会话列表、右当前聊天），但**反例警示**：社区长期反馈 Telegram **未提供手动关闭/收起左侧列表栏的开关**，在近似正方形的折叠屏竖屏展开态下，右侧阅读长消息/频道图文时会显得偏窄；用户不得不改系统 DPI 或用第三方客户端规避（公开社区反馈，详见第 3 节反例）。
3. **折叠、展开、旋转时状态保持**：
   - 正常场景下保持聊天与输入草稿；但折叠屏用户多次反馈在折叠/展开瞬间偶发界面无响应或点击失效（未见官方博客披露根因，标“社区反馈现象，源码未深挖”）。
4. **铰链与桌面模式**：
   - 未知（官方博客未宣布 Tabletop/Flex 专属布局）。
5. **窄窗口与分屏多窗口**：
   - 窄窗口下自动回到单栏底栏布局，支持常规多窗口运行。
6. **动效与预测性返回**：
   - 以极高帧率与物理弹性交互闻名，官方在 2026 年改版中彻底重写 Android 界面渲染路径追求极致顺滑（`crafting-android-design-and-more`）；但因非 Compose 栈，具体内部弹簧系数不在本次 Compose 审计范围。
7. **毛玻璃/半透明材质与降级（核心借鉴点）**：
   - 2026 年推出 Liquid Glass 透明/折射与磨砂视觉风格，并明确给出工程化性能保障策略：在 **`Settings > Power Saving`** 中提供显式界面特效控制开关，允许用户按设备性能与续航需求关闭/降级模糊与重动效（`new-design-ai-summaries`、`crafting-android-design-and-more`）。

---

## 3. 可用于 hipda-next 的模式清单与“不要学”反例

### 3.1 推荐采用的 12 个核心模式（映射到 hipda-next 全页面）

| # | 模式名称 | 来源 | 适用 hipda-next 界面 | 为什么适合论坛阅读 | Compose 实现方式（API/库） | 最低 API | 核实状态 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| P1 | **折叠屏 700dp 专属双栏断点 + 非折叠屏 840dp 断点** | R3 `HarmonicPaneScaffoldDirective.kt:13-27`；R1 `PaneScaffoldDirective.kt:127-148` | 帖子列表+帖子详情、短消息列表+会话、设置分类+设置项 | 书本折叠屏展开竖屏宽常在 `670–740dp`，M3 默认 `840dp` 会浪费大屏成巨幅单栏，而一刀切 `600dp` 又会挤爆普通分屏窗；仅在检测到物理铰链且宽 `≥700dp` 时开双栏最平衡 | `currentWindowAdaptiveInfo()` + `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth()` + `PackageManager.FEATURE_SENSOR_HINGE_ANGLE`（API 30+ 辅助检测） | API 24（`androidx` 默认）；铰链传感器检测 API 30+ 降级仅用 `hingeList` | 已核实源码 |
| P2 | **可拖拽双栏分隔条 + 方向独立记忆 + 铰链磁吸 + 一键全屏沉浸** | R3 `SplitPaneViewport.kt:99-351`、`SplitRatioPreferences.kt:3-25`；R4 `ArticlePaneExpansion.kt:34-134`；R7 Gmail 2025-04-25 | 帖子列表+帖子详情、短消息、设置 | 论坛用户常在“快速扫帖（左宽）”和“沉浸读长楼/看多图（右宽/全屏）”间切换；折叠屏拖到 47%–53% 磁吸折痕对齐，横竖屏分别记忆比例，顶栏一键在 `0f` 与上次比例间动画切换 | `ListDetailPaneScaffold` / `rememberListDetailSceneStrategy` + `rememberPaneExpansionState(anchors)` + M3 `VerticalDragHandle` + `Modifier.paneExpansionDraggable`；并配无障碍 `semantics` 与键盘微调 | API 24（`compose-material3-adaptive` 1.1+ / `material3` 1.4+） | 已核实源码 |
| P3 | **自适应顶层导航四段切换（底栏 ↔ 可展开宽侧栏）** | R1 `NavigationSuiteScaffold.kt:1168-1181`；R5 `Navigation.kt:186-234`；R6 `ReplyNavigationComponents.kt:93-169` | 全局顶层壳（版块/帖子、短消息、搜索、我的/设置） | 手机单手拇指易达底栏；平板与展开折叠屏改为左侧 `WideNavigationRail`（高 Medium+ 时项垂直居中），省下宝贵纵向阅读高度；桌面模式或横屏矮窗自动回落底栏 | `androidx.compose.material3:material3-adaptive-navigation-suite` 的 `NavigationSuiteScaffold` + `NavigationSuiteScaffoldDefaults.navigationSuiteType()`（优先用 `ShortNavigationBar` + `WideNavigationRail`） | API 24（旧版 1.3 为 API 21） | 已核实源码 |
| P4 | **正文阅读列宽上限 `720dp` 居中 + 全宽滚动热区** | R3 `CommentsReadingWidth.kt:15-18`；ReadYou `ArticleListReadingPage.kt:110-118` | 帖子详情（楼层与引用块）、发帖/回复正文、用户信息 | 论坛长文在 `1000dp+` 平板或全屏模式下每行文字过长极易串行；将内容居中限宽 `max = 720.dp`（同时背景与 `LazyColumn` 仍铺满全宽），既保最佳每行字数，又让双手在屏幕边缘即可顺手滚动 | 列表内每项应用 `Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)`；沉浸切全屏时可用 `animateDpAsState` 平滑过渡列宽 | API 21/24（Compose Foundation） | 已核实源码 |
| P5 | **`movableContentOf` 跨单双栏无损搬移帖子详情 + 滚动位保留** | R3 `HarmonicAppRoot.kt:63-80`、`SettingsScaffold.kt:103-108`；R4 `App.kt:136-139, 188` | 帖子详情（折叠↔展开）、发帖/回复草稿、帖子列表 | 折叠屏合上/打开或旋转时最怕帖子重新请求、回到 1 楼、写一半的回复草稿丢失；把帖子详情按 `tid` 包进 `movableContentOf`，配合 `rememberSaveableStateHolderNavEntryDecorator` 与 ViewModel 托管草稿/翻页状态，实现零重载无缝衔接 | `androidx.compose.runtime.movableContentOf` + `rememberSaveable` + `SavedStateHandle` + Navigation 3 `clazzContentKey` / `rememberViewModelStoreNavEntryDecorator` | API 21/24 | 已核实源码 |
| P6 | **同窗口跨双栏全屏看图浮层（Overlay Scene）+ 共享元素转场** | R4 `MediaSceneStrategy.kt:25-75`；R1 `shared-elements`；R6 `PlayerScreen.kt:350-380` | 帖子详情点击图片 → 看图器 | 论坛看图若只局限在右侧半屏会太小，若用系统 `Dialog` 又会丢失转场与状态；在 Navigation 3 中用 `OverlayScene` 把看图器直接叠在双栏之上、接入系统返回，并用 `SharedTransitionLayout` + `Modifier.sharedElement()` 实现从楼层缩略图丝滑放大 | `androidx.navigation3.scene.OverlayScene`（或 `ListDetailPaneScaffold` 外的顶层覆盖层）+ `androidx.compose.animation.SharedTransitionLayout` + `Modifier.sharedElement()` | API 24（Compose Animation 1.7+ / Nav3 1.0+） | 已核实源码 |
| P7 | **空详情引导占位 + 持久选中高亮 + 干净回退栈** | R2 `split-views`；R4 `App.kt:165-174, 261-267`；R5 `InterestsDetailPlaceholder.kt:39-64` | 帖子列表+详情、短消息、设置 | 双栏初进未选帖子时避免右半屏留白发空，可放 HiPDA 徽标与快捷键/手势提示；左栏持续高亮当前阅读帖帮助定位；双栏下连点多帖时原地替换栈顶 `Detail` 条目，按一次返回即退回而不是连按十次 | `ListDetailSceneStrategy.listPane(detailPlaceholder = { ... })`；左栏 `selected = (tid == currentTid)` 高亮；替换栈顶 `backStack[lastIndex] = ThreadDetail(tid)` | API 24（Adaptive / Nav3） | 已核实源码 |
| P8 | **三级深入交互分流：轻量对照进 `Extra` / 侧滑面板，重任务独立路由** | R1 `ListDetailPaneScaffold.kt:253-258`；R7 Gmail 2023-10-20；R4 `App.kt:155-163` | 帖子详情点楼层作者头像→用户信息；点引用/回复→半屏底部或侧边撰写；点站内链接→右半屏对照 | 论坛详情里经常要顺手看某用户资料或引用回复某楼，若全屏跳转会打断上下文；在宽屏把“用户信息/楼层引用上下文”放进 `Extra` 栏或悬浮卡片，把“长篇发新帖/全量设置”保留独立路由或双栏设置页 | `ListDetailPaneScaffoldRole.Extra`（支持 3 栏 Large 屏并列、Expanded 屏 `Detail + Extra`）或 `ModalBottomSheet` / `SupportingPaneScaffold` | API 24（`compose-material3-adaptive`） | 已核实源码 |
| P9 | **预测性返回手势（跟手缩放 + 边缘锚点 + 分栏感知）** | R1 `ThreePaneScaffoldPredictiveBackHandler.android.kt:61-104`；R3 `DefaultActivityPredictiveBackAnimation.kt:40-164`；R6 `JetnewsNavDisplay.kt:118-127` | 单栏看帖返回列表、看图下滑/侧滑返回、搜索与设置页返回 | 预测性返回最能体现苹果级跟手感：左/右边缘滑出时按滑动边设缩放锚点（`TransformOrigin`），页面轻微缩放并显露圆角与底层暗罩，取消手势弹性回弹；双栏态下按返回优先收起浮层/恢复全屏，根态交还系统退出 | `NavigableListDetailPaneScaffold` 内置支持，或 `NavDisplay(predictivePopTransitionSpec = ...)` + `PredictiveBackHandler`；`AndroidManifest.xml` 开启 `android:enableOnBackInvokedCallback="true"` | 编译 API 24；手势实时进度回调需 **Android 14 (API 34)+**，系统级开启需 API 33+ | 已核实源码与官方文档 |
| P10 | **列表与搜索的大屏网格/流式适配 + 可拖拽滚动条** | R5 `ForYouScreen.kt:166-174, 232-243` | 版块列表（大屏多列网格卡片）、搜索结果、收藏/相册 | 版块列表在宽屏若仍拉成一根极长的单列，扫视效率低；用 `GridCells.Adaptive(260.dp..300.dp)` 自动排 2–4 列版块卡片；长帖百楼与长列表加可拖动快速滚动条 | `LazyVerticalGrid(columns = GridCells.Adaptive(280.dp))` / `LazyVerticalStaggeredGrid` + 自定义 `DraggableScrollbar` | API 21/24（Compose Foundation） | 已核实源码 |
| P11 | **克制分层毛玻璃材质 + 按 API 与性能自动降级** | R2 `materials`（Liquid Glass 仅限控制层）；R3 `Haze.kt:114-210`；R9 Telegram Power Saving；Haze `docs/blur/platforms.md` | 顶部悬浮栏、底部导航栏、看图浮层工具条、引用/操作浮窗（**不用于帖子正文卡片**） | 论坛主体是高密度文字，毛玻璃只应留在悬浮导航与工具栏上建立层次，正文保持高对比纯色；按 API 分级降级并在设置中提供“纯色/磨砂/玻璃”及省电开关，绝不牺牲 120Hz 滚动帧率 | `dev.chrisbanes.haze:haze` + `haze-blur`（或 `haze-glass`）：API 31+ `RenderEffect` 硬件模糊、API 33+ AGSL 折射玻璃、API ≤30 自动降级为半透明 Scrim（`fallbackColorEffect`）；系统“降低透明度”或省电模式切回不透明 `surfaceContainer` | 库 minSdk API 23；硬件模糊 **API 31+**；AGSL 玻璃 **API 33+**；原生窗口 Backdrop **API 36/37+** | 已核实源码与文档 |
| P12 | **桌面模式（Tabletop）针对性适配（看图/视频与发帖输入）** | R1 `PaneScaffoldDirective.kt:81-92`；R6 `PlayerScreen.kt:267-293`；R8 `flex-mode.html` | 看图器（半折时上图下操作栏）、发帖/回复（上半屏预览或被引用楼层、下半屏编辑与键盘）、顶栏导航自动切底栏 | 论坛阅读列表在 Tabletop 下没必要硬拆成上下两半，但在**看图器**与**引用回复**场景，半折状态将“预览/原楼内容”放上半屏、“编辑器/图片操作条”放下半屏极符合直觉且避开物理折痕 | `collectFoldingFeaturesAsState()` 判断 `HALF_OPENED && HORIZONTAL` → 切换上下分区布局，间隔留足 `foldingFeature.bounds.height` 或 `24.dp` | API 24（Jetpack WindowManager 1.0+ / Adaptive） | 已核实源码与文档 |

### 3.2 “不要学”的反例清单

1. **反例 1：强制双栏且不允许用户临时展开全屏单栏（Telegram 折叠屏痛点，R9）**
   - **问题**：在近方形折叠屏内屏（尤其竖屏或大字号用户）下，强锁双栏会让帖子正文被挤在半边窄列里，看多图长帖非常局促。
   - **对策**：必须同时提供可拖动分隔条（拖至边缘可全屏）+ 帖子顶栏一键全屏/还原按钮（学习 R4 Capy Reader 与 R7 Gmail）。
2. **反例 2：只看宽度断点不看高度断点，导致手机横屏/翻盖屏出现极扁双栏（R1 `use-window-size-classes`、R6 `Jetcaster/Home.kt:138-141`）**
   - **问题**：普通大屏手机横屏宽可达 `750–900dp`（Medium/Expanded），但高度 `<480dp`（Compact）；若盲目开双栏或左侧 Rail，垂直可读内容不足三行。
   - **对策**：高度 Compact 时强制降级为单栏 + 紧凑底栏/可隐藏顶栏。
3. **反例 3：在正文内容层滥用大面积实时毛玻璃，或动画中直接移动含模糊源的节点（R2 `materials`、R3 `DefaultActivityPredictiveBackAnimation.kt:62-63`、Haze `docs/performance.md`）**
   - **问题**：正文卡片叠毛玻璃会降低文字对比度、加剧 GPU 过绘并拖垮 120Hz 列表滚动；页面转场或低端 API 31 设备每帧重建 RenderEffect 会引发明显掉帧。
   - **对策**：仅在悬浮导航/工具栏/浮层使用毛玻璃，滚动正文保持纯色；API ≤30 默认 Scrim 降级，API 31 实测性能，并提供“纯色/省电”开关；离屏转场时录制静态 `GraphicsLayer` 固定采样坐标。
4. **反例 4：用 `resizeableActivity="false"` 或锁死竖屏逃避大屏适配（R1 Adaptive Quality Tier 3、R8 `app-continuity.html`）**
   - **问题**：在 Android 16/17 与折叠屏上会导致系统强制信箱黑边（letterbox），折叠展开时强行重启 Activity 丢失读帖进度和回复草稿，无法通过 Google Play Tier 3 基线。
   - **对策**：全面放开方向与窗口缩放，全量依赖 `WindowAdaptiveInfo` 响应窗口实时尺寸。
5. **反例 5：用固定时长 `tween` 驱动手势跟手或可反复触发的布局变化（R2 WWDC18/WWDC23、R1 M3 Expressive）**
   - **问题**：用户在帖子列表快速连点或展开侧栏中途反悔划回时，固定 `tween` 会忽略手势速度、打断时瞬间跳变或机械重新计时，显得“僵硬、不跟手”。
   - **对策**：一切空间位移、尺寸、形变与手势释放一律改用物理 `spring`（自动保留当前速度连续过渡），只有纯颜色/透明度等极少数场景可用临界阻尼弹簧或短时长曲线。
6. **反例 6：把高频微操作做得又慢又爱弹跳，或无视“减少动态效果”（R2 `motion`、R4 `AppMotionDurationScale.kt`）**
   - **问题**：帖子列表快速切帖、折叠引用楼层、点赞/收藏等高频动作如果套用慢速高弹跳动画，会把用户拖在动画后面；前庭敏感用户和墨水屏设备会感到眩晕或残影。
   - **对策**：高频交互走 `fastSpatial` / `fastEffects` 或极简微反馈，绝不阻塞下一次点击；统一接入系统 `ANIMATOR_DURATION_SCALE` 与应用内“减少动态效果”开关。

---

## 4. 动效语言建议（量化参数体系）

### 4.1 两大官方标杆的精确原始参数对照

#### A. Material 3 Expressive 与 Standard 官方弹簧 Token

直接核实自 `androidx/androidx` commit `6eae178508e7748f0a6a90515dede899d6d792c2`：
- `ExpressiveMotionTokens.kt:21-34`
- `StandardMotionTokens.kt:19-32`
- `MotionScheme.kt:136-260`
- `PaneMotion.kt:155-178`

| Token 档位 | M3 Expressive (`dampingRatio`, `stiffness`) | M3 Standard (`dampingRatio`, `stiffness`) | 官方设计语义 |
| :--- | :--- | :--- | :--- |
| **Fast Spatial** | `0.6f`, `800f` | `0.9f`, `1400f` | 小组件、高频控件的空间形变/位移（如开关、芯片、图标、按钮形变） |
| **Default Spatial** | `0.8f`, `380f` | `0.9f`, `700f` | 常规容器移动、分栏伸缩（`PaneMotionDefaults.AnimationSpec` 默认即 `0.8f / 380f`）、局部展开 |
| **Slow Spatial** | `0.8f`, `200f` | `0.9f`, `300f` | 大面积全屏页面切换、共享元素容器变换、抽屉/大面板进出 |
| **Fast Effects** | `1.0f`, `3800f` | `1.0f`, `3800f` | 极快颜色、透明度、按压态亮度变化（临界阻尼，严禁过冲） |
| **Default Effects** | `1.0f`, `1600f` | `1.0f`, `1600f` | 常规淡入淡出、选中背景色、遮罩透明度变化 |
| **Slow Effects** | `1.0f`, `800f` | `1.0f`, `800f` | 大面积遮罩、主题色切换、慢速内容渐显 |

*补充传统曲线参考*（`MotionTokens.kt:23-69`）：Emphasized 曲线 `CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)`（标准两段式路径 `PathEasing` 见 `Harmonic-HN/ActivityNavigationTransition.kt:272-278`），Emphasized Decelerate `(0.05f, 0.7f, 0.1f, 1.0f)`，Emphasized Accelerate `(0.3f, 0.0f, 0.8f, 0.15f)`；时长档位 `Short1–4 = 50/100/150/200ms`，`Medium1–4 = 250/300/350/400ms`，`Long1–4 = 450/500/550/600ms`。预测性返回手势进度映射曲线：`CubicBezierEasing(0.1f, 0.1f, 0f, 1f)`（`ThreePaneScaffoldPredictiveBackHandler.android.kt:101`）。

#### B. Apple SwiftUI 官方弹簧预设参数

直接核实自 Apple Developer Documentation（`swiftui/animation/smooth(duration:extrabounce:)`、`snappy`、`bouncy`、`spring/init(duration:bounce:)`）及 WWDC23 #10158：

| Apple 预设 | 官方默认 `duration` | 官方基础 `bounce` | 物理特征与等价 Compose `dampingRatio` 说明 | 典型适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **`.smooth`** | `0.5s` | `0.0` | 临界阻尼（无任何回弹过冲，等价 `dampingRatio = 1.0f`） | 常规视图切换、分栏滑入/收起、列表行重排 |
| **`.snappy`** | `0.5s`（交互常用调短至 `0.25–0.35s`） | `0.15` | 利落干脆、极轻微弹性（等价 `dampingRatio ≈ 0.85f`；注：为推导说明，Apple 官方以 `bounce` 定义） | 切换控件、悬浮按钮、微交互反馈、磁吸归位 |
| **`.bouncy`** | `0.5s` | `0.3` | 明显弹性与活力（等价 `dampingRatio ≈ 0.70f`） | 强调性趣味反馈（论坛克制风格下应少用） |

> **给 hipda-next 的融合定调**：论坛是高频阅读工具，要求“苹果级细腻丝滑 + 克制、解释发生了什么”。建议**以 M3 Standard Spatial（`dampingRatio = 0.9f`）与 Apple `.smooth` / 轻 `.snappy` 的克制阻尼为基底，配合 M3 Expressive 的 6 档 Token 结构**（即常规阅读交互以 `0.85f–1.0f` 几乎无过冲为主，仅在明确需要手感回弹的拖拽磁吸/开关/按钮形变处采用 `0.75f–0.85f`，避免 M3 Expressive `FastSpatial 0.6f` 在高频文字界面中显得过于“QQ 糖”）。

---

### 4.2 按 6 类变化划分的量化动效建议表（hipda-next 统一动效语言）

| 变化类型 | 典型论坛场景 | 推荐参数（Compose `AnimationSpec`） | 视觉意图（解释发生了什么） | 参数来源 |
| :--- | :--- | :--- | :--- | :--- |
| **1. 空间移动** | 帖子卡片→详情容器变换、分栏出现/消失、分隔条松手吸附、侧栏展开/收起、看图共享元素缩放 | 常规分栏/布局位移：`spring(dampingRatio = 0.8f–0.9f, stiffness = 380f–700f)`（推荐 `0.9f, 500f` 或直接用 `PaneMotionDefaults.AnimationSpec` `0.8f, 380f`）；全屏级/大图共享元素：`spring(dampingRatio = 0.85f–0.9f, stiffness = 300f)`（对齐 `Standard SlowSpatial`）；折痕磁吸落位：`spring(dampingRatio = 1.0f, stiffness = 2500f)` | 建立空间因果：告诉用户内容“从哪来、到哪去”，保持对象恒常性 | `PaneMotion.kt:155-160`；`StandardMotionTokens.kt:20-29`；`ExpressiveMotionTokens.kt:22-31`；`SplitPaneViewport.kt:163` |
| **2. 出现与消失** | 看图浮层淡入淡出、Snackbar/提示浮条、顶部搜索栏、返回顶部悬浮按钮、引用预览弹窗 | 透明度/颜色：`spring(dampingRatio = 1.0f, stiffness = 1600f)`（`DefaultEffects`，约等价 `150–220ms` 临界收敛）或 `tween(220ms)`；伴随微小位移/缩放时：进入 `scale(0.95f→1f)` + `DefaultSpatial`，退出加快且无回弹 `FastEffects (1.0f, 3800f)` + `scale(1f→0.97f)` | 出现有来处、消失干净不拖沓；透明度严禁使用 `<1.0f` 阻尼（否则会闪烁） | `ExpressiveMotionTokens.kt:24-29`；`MediaSceneStrategy.kt:66, 73`；`MotionTokens.kt:39` |
| **3. 内容替换** | 双栏内点击另一篇帖子切换详情、切换帖子排序/版块筛选、加载骨架屏切真实楼层 | 双栏内换帖：**不重排容器，仅正文交叉替换**——`fadeOut`（`FastEffects: 1.0f, 3800f`）与 `fadeIn`（`DefaultEffects: 1.0f, 1600f`）或极轻量级 Shared-Axis-X `10%` 偏移（单栏进出）；Harmonic 式短缩放替换（`0.85f→1f`, `300ms` Emphasized，延迟 `50ms` 淡入 `50ms`） | 明确区分“换了同一槽位里的内容”与“进入了新层级页面”，防止大屏双栏晃动晕眩 | `CapyReader/App.kt:188-205, 241`；`JetNews/ListDetailScene.kt:57-62`；`Harmonic/PaneMotion.kt:26-83`；`StandardMotionTokens.kt:22-27` |
| **4. 数值变化** | 帖子未读红点计数、回复数/页码跳转、双栏宽度百分比提示、发帖字数统计 | `AnimatedContent` 纵向数字滚动 + `spring(dampingRatio = 0.9f, stiffness = 1400f)`（`Standard FastSpatial`）配合 `FastEffects (1.0f, 3800f)` 淡入淡出；进度条宽度用 `DefaultSpatial (0.9f, 700f)` | 方向编码增减（增大向上滚、减小向下滚），瞬时可读不滞后 | `StandardMotionTokens.kt:24-27`；Apple HIG `motion`（精准简短反馈原则） |
| **5. 按压与触觉反馈** | 帖子行点击、底部/侧栏导航项、发送/引用/收藏按钮、分隔条按压抓取 | 按钮按压形变/微缩放：`scale(1f→0.97f/0.98f)` 或 M3 Expressive `ButtonDefaults.shapes()` 圆角形变，使用 `spring(dampingRatio = 0.82f–0.9f, stiffness = 1400f)`（进入可略快、松手略舒展）；按压色/涟漪：`FastEffects (1.0f, 3800f)`；分隔条抓握变粗：`tween(150ms, FastOutSlowInEasing)` | 给指尖即时物理确认（`<100ms` 内起效），配合轻触觉反馈（`HapticFeedbackType`），但绝不延迟点击事件触发 | `StandardMotionTokens.kt:24-27`；`Haze/docs/effects/glass/index.md:594-606`（`0.82f / StiffnessMedium`）；`SplitPaneViewport.kt:269-280`；`Harmonic/ExpressiveButtons.kt:20-44` |
| **6. 加载到完成或出错** | 下拉刷新、帖子/图片加载完成、发帖提交中→成功/失败提示 | 骨架屏/加载态→内容：`DefaultEffects (1.0f, 1600f)` 平滑交叉淡入（忌用弹跳）；提交按钮加载态→成功对勾：图标容器形变用 `Standard DefaultSpatial (0.9f, 700f)`；**出错微震动**：水平阻尼衰减弹簧（短幅 `±6dp` 轻晃，高刚度 `stiffness = 1400f–2500f`, `dampingRatio = 0.4f–0.5f`，仅用于明确错误校验，总收敛 `<300ms`——注：出错晃动幅度与阻尼为基于 M3 高刚度弹簧的工程实践推导，非官方固定 Token） | 区分中性完成（稳重过渡）与异常阻断（短促警示+文字/触觉双重反馈，不单靠动画传达） | `StandardMotionTokens.kt:20-23`；Apple HIG `motion`（不把动效当唯一信息通道） |

### 4.3 高频操作加速、可打断性与“减少动态效果”降级

1. **高频操作如何更快（不让用户等动画）**：
   - 遵循 Apple HIG `motion` 与 M3 `Fast` 档位分工：切帖、翻页、展开/折叠引用楼层、点赞/收藏、搜索输入过滤一律走 **`Fast` 档位**（`stiffness = 1400f–3800f`）或直接免去位移动画仅保留淡入/颜色反馈。
   - **永不阻塞输入**：动效进行期间不屏蔽下一条点击（例如用户快速连点左栏 3 个帖子，右侧直接把目标值更新到第 3 帖，前两帖的过渡自动中断汇入）。
   - **渲染期读值，零重组开销**：借鉴 `Harmonic-HN`（`AskMotion.kt:101, 140-149`、`ActivityNavigationTransition.kt:205-207`），将 `translationX`/`scale`/`alpha` 动画值放在 `Modifier.graphicsLayer { ... }` 或 `drawWithContent` lambda 内读取，避免每帧触发帖子长列表重组。
2. **如何保证跟手与可打断（苹果级手感核心）**：
   - **全空间动效用 `spring` 不用固定 `tween`**：在 Compose 中，`Animatable.animateTo(..., spring(...))` 与 `animate*AsState(..., spring(...))` 在目标变化或中途被手势打断时，会**自动保留当前实时位置与当前速度（velocity preservation）**平滑转向，绝不会重置跳帧（契合 WWDC18 Fluid Interfaces 原则）。
   - **手势 1:1 跟随 + 速度交接**：拖拽分隔条、看图下拉关闭、边缘预测性返回期间，手势位移直接 `snapTo`（或经 `BackGestureEasing` 实时映射）贴手渲染，松手时将 `VelocityTracker` 测得的末速度作为 `initialVelocity` 传入 `Animatable.animateTo`（参见 `SplitPaneViewport.kt:161-168`、`DefaultActivityPredictiveBackAnimation.kt:133-147`）。
   - **预测性返回与共享元素联动**：优先采用 `NavigableListDetailPaneScaffold` 或 Navigation 3 `NavDisplay.predictivePopTransitionSpec`，手势中途取消时无缝弹回原位。
3. **尊重“减少动态效果”的分级降级方案**：
   - **双源监听机制**（借鉴 R4 `Capy Reader` `AppMotionDurationScale.kt:7-30` & `MainActivity.kt:40-62`）：
     1. 监听 Android 系统 `Settings.Global.ANIMATOR_DURATION_SCALE`（当系统开启“移除动画 / Reduce motion”时其值为 `0f`）；
     2. 在 `hipda-next` 设置中同步提供应用内“减少动态效果”开关（覆盖未提供独立入口的旧系统或墨水屏用户）。
   - **分场景优雅降级（而非粗暴全瞬切卡顿）**：
     - 当 `ANIMATOR_DURATION_SCALE == 0f` 时，Compose 协程上下文默认将所有动画时长缩为 `0` 瞬时完成。
     - 当应用内开启“减少动态效果”或需对齐 Apple Reduce Motion 体验时：通过全局 `LocalReducedMotion` CompositionLocal，**禁用空间位移、缩放、弹跳、视差与容器形变**，统一替换为短促无过冲的**交叉淡入淡出**（`fadeIn/fadeOut` `120–150ms` 或 `snap()`），并同步将 Haze 材质的 `interactionReducedMotionPolicy` 设为 `GlassReducedMotionPolicy.System` / `Reduced`（`Haze/docs/effects/glass/index.md:611-613`），且保留所有状态变化与非动画视觉提示（选中高亮、边框、文字提示）。

---

## 5. 自检：随机抽检 6 条事实回源核对结果

以下 6 条事实于报告成文后重新打开原始 URL 或本地克隆源码文件逐行核对，全部一致：

| 抽检编号 | 报告中事实摘要 | 回源核对对象 | 核对结果 |
| :--- | :--- | :--- | :--- |
| C1 | M3 Expressive 官方弹簧 Token：`DefaultSpatial(0.8f, 380.0f)`、`FastSpatial(0.6f, 800.0f)`、`SlowSpatial(0.8f, 200.0f)`、`FastEffects(1.0f, 3800.0f)`、`DefaultEffects(1.0f, 1600.0f)`、`SlowEffects(1.0f, 800.0f)` | 重拉 `androidx/androidx` (`6eae178508e7748f0a6a90515dede899d6d792c2`) `ExpressiveMotionTokens.kt:21-34` | **完全一致**：L22–L33 常量名与浮点数值逐字吻合 |
| C2 | Apple SwiftUI `.snappy` 预设默认 `duration = 0.5`、`extraBounce = 0.0`、基础弹性 `0.15` | 重拉 Apple 官方接口 `https://developer.apple.com/tutorials/data/documentation/swiftui/animation/snappy(duration:extrabounce:).json` | **完全一致**：函数声明为 `static func snappy(duration: TimeInterval = 0.5, extraBounce: Double = 0.0) -> Animation`，文档原文明确为 `base bounce of 0.15` |
| C3 | Harmonic-HN 对折叠屏使用 `windowWidth >= 700.dp` 触发 Medium 双栏；分栏比例限定 `0.3f..0.7f`，折叠屏 `0.47f..0.53f` 磁吸到 `0.5f` | 重新读取本地仓（`git rev-parse HEAD = d25cdc5365e1e49bc5bccbdcb345034dded297f9`）`HarmonicPaneScaffoldDirective.kt:19-26` 与 `SplitRatioPreferences.kt:4-11` | **完全一致**：行号、常量区间与 `snapToCenter` 条件完全吻合 |
| C4 | Capy Reader 分栏锚点含 `0f`、`defaultPanePreferredWidth + spacer/2`、`30..70 step 5`、`1f`；`AppMotionDurationScale` 监听 `Settings.Global.ANIMATOR_DURATION_SCALE` 且 `reduceMotion` 开启时返回 `0f` | 重新读取本地仓（`git rev-parse HEAD = 7639ad309966f791bdd75cfded545e6f2fc9f610`）`ArticlePaneExpansion.kt:39-48` 与 `AppMotionDurationScale.kt:14-29` | **完全一致**：锚点构造逻辑与 `scaleFactor` / `refreshSystemScale` 行号和实现吻合 |
| C5 | JetNews 预测性返回使用 `unveilIn() togetherWith scaleOut(targetScale = .8f)` 与 `PIVOT_FRACTION_OFFSET = .2f` 动态锚点；Jetcaster 在 `isTableTopPosture` 下切为 `VerticalTwoPaneStrategy(0.5f)` | 重新读取本地仓（`git rev-parse HEAD = 09aae2013b3e5897f9679e2b7539f505f4f6f7f6`）`JetnewsNavDisplay.kt:48, 118-127` 与 `PlayerScreen.kt:267-293` | **完全一致**：行号、缩放系数、锚点表达式与 Tabletop 垂直策略完全吻合 |
| C6 | Gmail 2025-04-25 官方更新公告宣布在大屏平板/折叠屏支持拖拽分隔条切换单/双栏并控宽；Telegram 2026-02-09 官方博客明确提供 `Settings > Power Saving` 控制界面特效与续航 | 重拉 `https://workspaceupdates.googleblog.com/2025/04/release-notes-04-25-2025.html` 与 `https://telegram.org/blog/crafting-android-design-and-more` | **完全一致**：两篇官方原文均命中对应原句 |

---

## 6. 主流折叠屏内屏的窗口尺寸

### 6.1 口径说明与判断基线

- **应用窗口 dp 与硬件 PPI 的区别**：Android 应用窗口的 dp 尺寸由系统设定的**逻辑密度（`densityDpi` / `devicePixelRatio`）**决定（公式：`dp = px ÷ (densityDpi ÷ 160)`），而非由屏幕物理 PPI 机械换算；此外厂商默认的“显示大小”档位、内/外屏是否采用不同逻辑密度，以及是否按 Edge-to-Edge 全窗或扣除系统任务栏/地址栏计量，都会造成小幅差异。
- **严格溯源与推算标注规则**：
  - 下表仅采纳可直接核实的一手来源（Android Studio AOSP 设备定义 `nexus.xml` / `devices.xml`、`screens.guide` Android 16 模拟器实测、Chromium DevTools `EmulatedDevices.ts`、厂商官方适配指南）。
  - 凡来源**直接给出** dp 或逻辑视口尺寸的，标为“实测/官方定义”；凡**同一可信来源同时给出像素分辨率与逻辑 `densityDpi`** 的，按 `px ÷ (densityDpi ÷ 160)` 四舍五入至整数 dp，并显式标注**“推算”**；缺乏逻辑 `densityDpi` 或可信实测来源的机型一律标**“未知”**（不按硬件 PPI 推算，亦不采信标明 *Specs pending verification* 的第三方估算站）。
- **“列表 300dp + 详情 380dp”判定标尺**：
  - **纯双栏最低宽**：`300 + 380 = 680dp`；
  - **含标准分隔条/间距**（`12–24dp`，对应 M3 Adaptive 常规间距或紧凑拖动把手）：**`692–704dp`**；
  - **再叠加左侧常驻 `NavigationRail`（`~80dp`）**：**`760–784dp`**。
  - 下表对每个机型的**内屏竖着拿、内屏横着拿、外屏**三组尺寸分别给出明确判断。

---

### 6.2 主流折叠屏窗口尺寸汇总（单位：dp，宽 × 高）

| 机型 | 内屏竖着拿（宽 × 高） | 内屏横着拿（宽 × 高） | 外屏（竖持，宽 × 高） | 放得下“列表 300dp + 详情 380dp”吗？（逐尺寸判断） | 数据来源 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Google Pixel Fold** | **`701 × 841 dp`**（实测 `700.95 × 841.14`；`1840×2208 px @ 420dpi`） | **`841 × 701 dp`**（实测 `841.14 × 700.95`；自然展开态 `2208×1840 px @ 420dpi`） | **`411 × 797 dp`**（实测 `411.43 × 796.95`；`1080×2092 px @ 420dpi`） | - **内屏竖着拿（`701dp`）**：**无侧栏时勉强放得下**（`701 ≥ 680`，留 `21dp` 分隔间距），但**加上 `80dp` 侧边 Rail 后仅剩 `621dp`，放不下**。<br>- **内屏横着拿（`841dp`）**：**完全放得下**（连同 `80dp` Rail + `24dp` 间距共 `784dp` 后仍余 `57dp`）。<br>- **外屏（`411dp`）**：**放不下**（远小于 `680dp`，必须单栏；外屏横拿实测 `797 × 411dp` 扣打孔安全区 `41.9dp` 后宽约 `755dp`，但高仅 `411dp` 属 Compact 高度，不适合双栏）。 | AOSP `nexus.xml:3804-3830`（commit `e42834169b1946ec9e0124e096c67f0be0c05f72`，`https://android.googlesource.com/platform/tools/base/+/e42834169b1946ec9e0124e096c67f0be0c05f72/sdklib/src/main/resources/com/android/sdklib/devices/nexus.xml`）；Android 16 模拟器实测 `https://screens.guide/data/v1/devices.json`（build `BE2A.250530.026.F3`，2026-10-15） |
| **Google Pixel 9 Pro Fold** | **`852 × 883 dp`**（Android 16 实测 `851.69 × 882.87`，`2076×2152 px @ 390dpi`）；Chrome DevTools 定义 **`836 × 842 dp`** | **`883 × 852 dp`**（Android 16 实测 `882.87 × 851.69`）；Chrome DevTools 定义 **`842 × 836 dp`** | **`443 × 994 dp`**（Android 16 模拟器 `1080×2424 px @ 390dpi`）；Chrome DevTools 定义 **`412 × 922 dp`**（DPR `2.625`） | - **内屏竖着拿（`836–852dp`）**：**完全放得下**（连同 `80dp` 侧边 Rail + `24dp` 间距共 `784dp` 后仍余 `52–68dp`）。<br>- **内屏横着拿（`842–883dp`）**：**完全放得下**（含侧栏与间距后仍余 `58–99dp`）。<br>- **外屏（`412–443dp`）**：**放不下**（必须单栏）。 | AOSP `nexus.xml:4768-4794`（同上 commit）；`https://screens.guide/data/v1/devices.json`；Chromium DevTools `EmulatedDevices.ts:1776-1805`（commit `ab4bdbd3c9e214646ad41ddad9a0519e2b385cf2`，`https://chromium.googlesource.com/devtools/devtools-frontend/+/ab4bdbd3c9e214646ad41ddad9a0519e2b385cf2/front_end/models/emulation/EmulatedDevices.ts`） |
| **Google Pixel 10 Pro Fold** | **`852 × 883 dp`**（**推算**：`2076×2152 px ÷ (390/160)`） | **`883 × 852 dp`**（**推算**：`2152×2076 px ÷ (390/160)`） | **`443 × 970 dp`**（**推算**：按同文件 `1080×2364 px @ 390dpi`；若真机外屏独立设 `420dpi` 则略窄，真机实测未知） | - **内屏竖着拿（`852dp`，推算）**：**完全放得下**（含 `80dp` Rail + `24dp` 间距后余 `68dp`）。<br>- **内屏横着拿（`883dp`，推算）**：**完全放得下**（含侧栏与间距后余 `99dp`）。<br>- **外屏（`443dp`，推算）**：**放不下**（必须单栏）。 | AOSP `nexus.xml:5256-5282`（同上 commit，文件同时给出内外屏像素与 `pixel-density = 390dpi`） |
| **Samsung Galaxy Z Fold 6** | **`744 × 860 dp`**（`vertical-spanned`，铰链 `x=372`） | **`860 × 744 dp`**（`horizontal-spanned`，铰链 `y=372`） | **`412 × 968 dp`**（`vertical`，DPR `2.625`） | - **内屏竖着拿（`744dp`）**：**无常驻侧边 Rail 时放得下**（扣 `24dp` 间距后剩 `720 ≥ 680`，余 `40dp`），但**叠加 `80dp` 侧边 Rail 后仅剩 `664dp`，放不下**（需隐藏 Rail、改底栏或压窄列表）。<br>- **内屏横着拿（`860dp`）**：**完全放得下**（含 `80dp` Rail + `24dp` 间距共 `784dp` 后余 `76dp`）。<br>- **外屏（`412dp`）**：**放不下**（必须单栏）。 | Chromium DevTools `EmulatedDevices.ts:1831-1860`（同上 commit，设备型号 `SM-F956U`）；物理内屏 `1856×2160 px` 另核实自三星开发者页 `https://developer.samsung.com/galaxy-emulator-skin/galaxy-z.html`（注：同文件 `L1881-1910` 亦记录 **Galaxy Z Fold 5** 为内屏竖 `690×829 dp`、内屏横 `829×690 dp`、外屏 `344×882 dp`，竖持 `690dp` 仅无侧栏且间距 `≤10dp` 时卡线放下） |
| **Samsung Galaxy Z Fold 7** | **未知**（三星开发者页仅公开物理分辨率 `1968×2184 px`、硬件 `~368 ppi`，未公开逻辑 `densityDpi`） | **未知** | **未知** | - **内屏竖着拿 / 内屏横着拿 / 外屏**：**均因缺乏可靠逻辑 dp 来源而无法精确判断**（外屏按常规折叠形态预计远小于 `680dp` 而放不下）。 | 物理参数核实自 `https://developer.samsung.com/galaxy-emulator-skin/galaxy-z.html`（官方 Emulator Skin 压缩包需登录三星账号，未能获取）；第三方估算站如 `ihatebugs.dev` 页面自注 *Specs pending verification*、`yesviz.com` 机械按 `2.0x` 计算，均未采信 |
| **荣耀 Magic V 系列（Magic V / V2 / V3 / V5）** | **未知**（官网仅公开物理像素如 Magic V3 内屏 `2344×2156 px` 等，未公开默认逻辑 `densityDpi`） | **未知** | **未知**（Magic V3 外屏物理 `2376×1060 px`，逻辑 dp 未知） | - **内屏竖着拿 / 内屏横着拿 / 外屏**：**均因缺乏可核实的官方逻辑 `densityDpi` 或实测 dp 来源而无法精确判断**（外屏按单屏比例预计放不下）。 | 官网参数页 `https://www.honor.com/cn/phones/honor-magic-v/spec/`、`https://www.honor.com/cn/phones/honor-magic-v3/spec/`；经检索荣耀开发者文档门户为前端动态壳且未获可核实静态一手逻辑密度表，按停止条件标未知 |
| **vivo X Fold（初代，型号 `V2178A`）** | **`639 × 720 dp`**（**推算**：`1916×2160 px ÷ (480/160)`，精确 `638.67 × 720`） | **`720 × 639 dp`**（**推算**：`2160×1916 px ÷ (480/160)`） | **`360 × 840 dp`**（**推算**：`1080×2520 px ÷ (480/160)`） | - **内屏竖着拿（`639dp`，推算）**：**放不下**（`639 < 680`，即便去侧栏与分隔条仍差 `41dp`，须退回单栏或放宽最小栏宽）。<br>- **内屏横着拿（`720dp`，推算）**：**无常驻侧边 Rail 时放得下**（扣 `24dp` 间距剩 `696 ≥ 680`，余 `16dp`），但**叠加 `80dp` 侧边 Rail 后仅剩 `640dp`，放不下**。<br>- **外屏（`360dp`，推算）**：**放不下**（必须单栏）。 | vivo 官方《vivo X Fold 折叠屏适配指南》（IT之家全文转载 `https://www.ithome.com/0/616/094.htm`、腾讯新闻 `https://news.qq.com/rain/a/20220430A03AIW00` 均明确给出：`adb shell wm density 480`、展开态 `wm size 1916x2160`、折叠态 `wm size 1080x2520` 且“展开态和折叠态 DPI 一样都是 480”；物理分辨率另与 `https://www.vivo.com.cn/vivo/param/xfold` 核对一致） |
| **vivo X Fold3 / X Fold5** | **未知** | **未知** | **未知** | - **内屏竖着拿 / 内屏横着拿 / 外屏**：**均因缺乏可核实的逻辑 `densityDpi` 或实测 dp 来源而无法精确判断**。 | 经多途径检索未见公开官方逻辑 `densityDpi` 或真机 `wm size`+`wm density` 一手实测记录，按停止条件标未知 |
| **OPPO Find N 系列（Find N / N2 / N3 / N5 及同源 OnePlus Open）** | **未知**（官网仅公开物理像素与硬件 PPI，如 Find N 内屏 `1792×1920 px, 370 PPI`） | **未知** | **未知**（Find N 外屏 `988×1972 px, 402 PPI`；社区偶见 OnePlus Open 默认外屏 smallestWidth 约 `403dp` 反馈，但缺完整一手实测） | - **内屏竖着拿 / 内屏横着拿 / 外屏**：**均因缺乏可核实的官方逻辑 `densityDpi` 或真机 dp 实测而无法精确判断**（外屏预计放不下）。 | 官网参数页 `https://www.oppo.com/cn/smartphones/series-find-n/find-n/specs/`；OPPO 开放平台折叠屏适配文档 `https://open.oppomobile.com/documentation/page/info?id=11832` 为动态壳未获静态逻辑密度数据，按停止条件标未知 |
| **附：Android Studio 通用折叠屏基准 — Foldable (4:3)** | **`768 × 1024 dp`**（**推算**：`2016×2688 px ÷ (420/160)`） | **`1024 × 768 dp`**（**推算**：`2688×2016 px ÷ (420/160)`） | **`480 × 768 dp`**（**推算**：`1260×2016 px ÷ (420/160)`） | - **内屏竖着拿（`768dp`，推算）**：**无侧栏轻松放得下**；叠加 `80dp` 侧栏 + `12dp` 间距（需 `772dp`）差 `4dp` 略紧。<br>- **内屏横着拿（`1024dp`，推算）**：**完全放得下且非常充裕**。<br>- **外屏（`480dp`，推算）**：**放不下**。 | AOSP `devices.xml:2253-2279`（commit `e42834169b1946ec9e0124e096c67f0be0c05f72`，`https://android.googlesource.com/platform/tools/base/+/e42834169b1946ec9e0124e096c67f0be0c05f72/sdklib/src/main/resources/com/android/sdklib/devices/devices.xml`） |
| **附：Android Studio 通用折叠屏基准 — Foldable (1:1)** | **`864 × 864 dp`**（**推算**：`2106×2106 px ÷ (390/160)`） | **`864 × 864 dp`**（**推算**：`2106×2106 px ÷ (390/160)`） | **`432 × 768 dp`**（**推算**：`1053×1872 px ÷ (390/160)`） | - **内屏竖着拿 / 横着拿（均 `864dp`，推算）**：**无论横竖均完全放得下**（含 `80dp` Rail + `24dp` 间距共 `784dp` 后仍余 `80dp`）。<br>- **外屏（`432dp`，推算）**：**放不下**。 | AOSP `devices.xml:2399-2425`（同上 commit） |

> **注：关于同一机型在不同来源下的轻微差异（以 Pixel 9 Pro Fold 为例）**：AOSP `nexus.xml` 与 `screens.guide` Android 16 模拟器测得的是 Edge-to-Edge 完整应用窗口尺寸（`852 × 883 dp`，同时报告 `safeAreaInsets` 顶 `55.79dp` / 底 `32dp`）；Chromium DevTools `EmulatedDevices.ts` 定义的 `836 × 842 dp` 面向浏览器内视口且基于 Android 14 配置。实际开发中，应用横竖屏可用**宽度**除侧边打孔外通常接近完整窗口宽，但仍应通过 `WindowSizeClass` 与 `WindowInsets` 动态读取，不要针对特定机型写死像素。

---

### 6.3 这些尺寸对 hipda-next 双栏设计的直接结论

把实测与官方定义的宽度放进数轴，可以清楚看到折叠屏内屏存在**三档现实区间**，直接决定了“列表 300dp + 详情 380dp”能否落地：

1. **充裕双栏带（`width ≥ 840dp`）**：涵盖 **Pixel 9 Pro Fold / Pixel 10 Pro Fold 横竖两向（`836–883dp`）、Pixel Fold 横拿（`841dp`）、Galaxy Z Fold 6 横拿（`860dp`）及 AOSP 1:1 / 4:3 横屏**。此处 `80dp NavigationRail + 300dp 列表 + 24dp 分隔间距 + 380dp 详情 = 784dp` 仍有 `52–99dp` 裕量，**全功能三段式（侧栏 + 列表 + 详情）直接成立**。
2. **关键夹层带（`700dp ≤ width < 784dp`）**：涵盖 **Pixel Fold 竖拿（`701dp`）、Galaxy Z Fold 6 竖拿（`744dp`）、vivo X Fold 横拿（`720dp`）、AOSP 4:3 竖拿（`768dp`）**，这也是折叠屏用户**单手/双手自然竖持展开**最高频的真实尺寸！
   - 若同时常驻 `80dp` 左侧 `NavigationRail`，剩余宽度仅 `621–688dp`，**无法同时保住 `300dp + 380dp` 与分隔间距**。
   - **给 hipda-next 的解法**：在 `700–839dp` 区间若坚持以 `300dp + 380dp` 展示双栏（借鉴 R3 `Harmonic-HN` 的 `≥ 700dp` 断点），**必须让主导航退回紧凑底栏或可收起抽屉/悬浮顶栏（不占用 80dp 横向宽），并把分隔条收紧到 `12–16dp`**；或者若坚持左侧常驻 `NavigationRail`，则允许左栏列表在 `700–839dp` 区间弹性下探至 `260–280dp`（并把 `300 + 380` 作为 `≥ 840dp` 下的舒适默认值）。
3. **强制单栏带（`width < 680dp`）**：涵盖 **vivo X Fold 竖拿（`639dp`）、所有折叠屏外屏（`360–443dp`）以及内屏左右分屏单侧窗口（约 `350–440dp`）**。物理上放不下 `300dp + 380dp`，必须保持单栏并确保内容优雅自适应。


