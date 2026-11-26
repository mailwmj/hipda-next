# 独立代码审查报告 R-E3：多栏内容容器与全形态连续性（commit `6fa4765`）

- **审查结论**：**`CHANGES-REQUESTED`**
- **审查对象**：`origin/card/E3-multipane-container` @ `6fa4765be9d4ad7cf6c5463eacc1c5b72d3d85d6`（简称 `6fa4765`）
- **对比基线**：`b7557bf651192b78974ca8d31ad3767ef66378f0`（含 `E0`、`E15`、`E2a`、`E1`）
- **与最新 `origin/main`（含 `E2b` + `E2d` @ `fbeeb28`）合并预演**：`git merge-tree --write-tree fbeeb28 6fa4765` → 输出顶层 tree `f10599378eb43cb679354c4a6917833ba9128ecc`，退出码 **`0`（0 文本冲突、0 文件交集，签名与快照兼容）**
- **GitHub Actions CI 核实**：[Android CI #45711171747](https://github.com/mailwmj/hipda-next/actions/runs/45711171747)
  - `headSha`：`6fa4765be9d4ad7cf6c5463eacc1c5b72d3d85d6`（与分支提交 100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139545667692`）：**`SUCCESS`**（已核实包含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room schema 校验、`:hipda:testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 APK 校验）
- **阻断项**：**3 项**（`B1`–`B3`）
- **非阻断建议项**：**2 项**（`S1`–`S2`）

---

## 一、阻断项（Blocking Issues）

### B1. 【交互死区阻断】`PaneDividerHandle`（`48dp × 全窗高`）作为顶层非共享兄弟节点拦截指针命中，吞没中栏顶栏 `更多` 按钮与右栏下钻 `返回` 按钮点击及竖滑

- **文件与行号**：
  - `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt:305-337`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/PaneDividerHandle.kt:90-124`
- **根因与字节码核实**：
  - `MultiPaneReadingContainer.kt:305-337` 在外层 `Box` 中先放 `Row`（中栏列表 + 右栏详情），再叠放 `width(48.dp).fillMaxHeight()` 的兄弟节点 `PaneDividerHandle`，其上挂载 `Modifier.anchoredDraggable(...)`（底层 `AnchoredDraggableNode -> DragGestureNode -> PointerInputModifierNode`）。
  - 经 `javap` 反编译当前依赖 `androidx.compose.ui:ui-android:1.13.0` 的 `PointerInputModifierNode` 与 `InnerNodeCoordinator.hitTestChild` 核实：`PointerInputModifierNode.sharePointerInputWithSiblings()` 默认恒返回 `false`（`iconst_0; ireturn`）；当任意触摸落入顶层兄弟 `PaneDividerHandle` 的 `48dp × 全窗高` 矩形时，`InnerNodeCoordinator.hitTestChild`（字节码偏移 `186..225`）记录命中后直接跳出 `zSortedChildren` 循环，**根本不会对底层 `Row`（`ForumPane` 与 `ThreadDetailPane`）执行 `hitTest`**——即便手指只是轻点或纵向滑动且未触发横向拖拽，底层组件也收不到任何指针事件。
- **具体触发路径与后果**：
  1. **中栏顶栏右上角 `更多`（`⋮` 排序菜单）失灵**：在 `749×559dp` 紧凑三栏下，中栏范围 `64..338dp`，`ForumTopBar` 右端 `更多` 按钮位于 `~290..338dp`；而 `PaneDividerHandle` 热区横跨 `314..362dp × 0..559dp`，直接盖住 `更多` 按钮右半部及图标视觉中心，用户点击 `⋮` 毫无反应；中栏列表右侧 `24dp` 区域点帖与竖滑同样死区。
  2. **右栏 `收起列表`（`0dp`）下钻页左上角 `返回`（`←`）圆钮完全点不动**：当分隔线吸附至 `PaneSplit.COLLAPSED`（`currentMiddlePx == 0f`）时，`handleOffsetPx = 0`，`PaneDividerHandle` 完整覆盖右栏左缘 `0..48dp × 全高`；此时在右栏点开下钻页（`UserProfile` 等），`ThreadDetailTopBar` 左上角 `返回` 按钮（`8..56dp`，中心 `32dp`）正中死区无法点击返回（正常档位下左缘 `24dp` 亦被截断，且后续 Wave 4 `E4` 右栏底部最左段 `回复` 胶囊左半区同样会被遮死）。
- **修复建议**：
  - 将 `PaneDividerHandle` 改为纯视觉 + 无障碍语义（`CustomAccessibilityAction`）+ `.focusable()` 键盘节点（不挂指针输入），把 `[dividerX - 24.dp, dividerX + 24.dp]` 起点门控的横向拖拽手势挂在包裹 `Row` 的**父容器 `Box`** 上（在 `PointerEventPass.Initial` 中仅当起点落入 `48dp` 热区且 `abs(dx) > touchSlop && abs(dx) > abs(dy)` 时 `consume()` 并驱动 `dragState.dispatchRawDelta` / `animateTo(..., HiMotion.spatialSnap())`，与仓库既有 `ShellIndicatorLayout.kt:176-195` 同构）；或者用极薄 `PointerInputModifierNode` 显式覆写 `sharePointerInputWithSiblings() = true`（按卡片约定说明理由）。补一条点击中栏 `更多` 按钮与 `COLLAPSED` 态点击右栏下钻 `返回` 按钮的单测。

---

### B2. 【丢 `LazyListState` + 切页双实例】`MultiPaneReadingContainer` 置于 `HiAdaptiveShell` 的 `AnimatedContent(selected)` 内且忽略 `destination`，跨一级目的地切换必丢列表滚动位置并在过渡帧双份挂载

- **文件与行号**：
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt:169-235`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt:129, 152-166, 309-313, 414-425`
  - `hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt:322-363`
- **具体触发路径与后果**：
  1. **跨目的地/侧栏分组切换彻底丢失 `LazyListState`（命中 `DISPATCH.md` §4 与 `R-E3.md` 阻断标准）**：
     - `E3` 从 `NextAppHostViewModel` 移除了内存 `forumListStates` Map，改为在 `MultiPaneReadingContainer.kt:129` 内部调用 `val saveableStateHolder = rememberSaveableStateHolder()` 保存各版块 `rememberLazyListState()`。
     - 但 `NextAppRoot.kt:169` 把 `MultiPaneReadingContainer` 放在 `HiAdaptiveShell` 的 `AnimatedContent(targetState = selected)`（`selected: ShellDestination`）内部，而 `AnimatedContent` 并未按 `ShellDestination` 包裹外层 `SaveableStateHolder`。
     - **触发路径**：用户在单栏或三栏 `论坛`（如 `Discovery`）把帖子列表滚到第 15 条 → 点击底部胶囊或左侧栏进入 `消息`（`提醒`/`短消息`）、`搜索` 或 `我的`（`浏览历史`/`设置`），`selectedDestination` 由 `Forum` 变为 `Messages`/`Search`/`Mine` → `AnimatedContent` 退场结束后**整棵销毁旧 `MultiPaneReadingContainer` 及其 `saveableStateHolder`** → 用户再切回 `Discovery`，新建的 `saveableStateHolder` 使列表滚动位置完全归零到 `(0, 0)`，违反 `product-design.md` §4.2 L167/169（“各版块与功能入口的列表滚动位置各自记住，切回时恢复”）。
  2. **切页交叉淡入淡出期间新旧两分支同时渲染同一目标页（破坏过渡与玻璃配额）**：
     - `NextAppRoot.kt:169` 写为 `{ _, contentPadding -> MultiPaneReadingContainer(..., appState = appState) }`，丢弃了 `AnimatedContent` 传入的分支快照参数 `destination`，导致退场分支与进场分支在过渡期内同时读取实时 `appState.selectedDestination`。
     - **触发路径**：在三栏下从 `搜索` 切回 `Discovery`（`Search -> Forum`），过渡帧内退场 `Search` 分支立即也变成渲染 `ForumPane`，画面中 `Search` 0ms 瞬消失、两份 `ForumPane` 自身叠印，且同时产生 **2 个 `middleGlassState` `LayerBackdrop` + 2 个中栏 `ForumTopBar` `HiGlassMarker`**。
- **修复建议**：
  - 将 `val saveableStateHolder = rememberSaveableStateHolder()` 提升到 `NextAppRoot`（位于 `HiAdaptiveShell` 之外，或置于 `HiAdaptiveShell` 的 `AnimatedContent` 之外）传入 `MultiPaneReadingContainer`；同时在 `NextAppRoot.kt:169` 接收 `{ destination, contentPadding -> }` 并传给 `MultiPaneReadingContainer`（使退场分支按自身 `destination` 渲染，避免新旧分支同帧争用同一 `SaveableStateProvider("forum:...")` key 抛 `IllegalArgumentException` 及双份挂载）。补一条“论坛滚至非首项 → 切 `搜索`/`浏览历史` → 切回论坛，断言滚动偏移保持不变”的单测。

---

### B3. 【代码膨胀超 200 行可删红线 + 测试专用钩子 + 分栏持久化另起存储】可删冗余代码约 ~345 行，含 M6 先写再删伪 UI、生产测试钩子及两处非 `SettingsRepository` 存储

- **文件与行号**：
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt:92-94, 110-120, 154, 156, 175, 177, 207-209, 258-458`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt:39-50, 171-199, 212-225, 245-248, 289-295, 320-323`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/BasicMultiPaneContainer.kt:1-44`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt:17-23, 31-32, 79-149`
  - `hipda/src/main/res/values/strings_app.xml:1-22`
- **具体问题与触发路径**：
  1. **分栏持久化在 `SettingsRepository` 之外另起两套存储（违反负责人重点 4）**：
     - `NextAppHostViewModel.kt:290` 执行 `savedStateHandle[if (isLandscape) KEY_SPLIT_LAND else KEY_SPLIT_PORT] = split.name`，全仓 **0 处读取**（纯只写死存储），并连带衍生 `:39-50` 四个构造函数与 `@Suppress("UNUSED_PARAMETER") hasRepository`；
     - `NextAppRoot.kt:110-120` 没有直接消费 `settings.paneSplitPortrait/Landscape` 与 `settings.sidebarCollapsedPortrait/Landscape`，而是另建 4 个 `rememberSaveable` 影子状态 + 4 个 `LaunchedEffect(settings.*)`。**具体副作用路径**：横屏将分隔线拖至 `BALANCED`（或收起常驻左栏）后发生进程回收重建 → 首帧 `DataStore` 冷流异步读盘前 `collectAsStateWithLifecycle(initialValue = AppSettings())` 初值为 `DEFAULT`/`false` → `LaunchedEffect(settings.paneSplitLandscape)` 在首次挂载时立即执行，把 `rememberSaveable` 恢复出的 `BALANCED`/`true` 反向覆盖回默认值，引发恢复首帧回跳闪烁。
  2. **生产组件暴露零调用/仅测试调用钩子（违反 `E3.md` L73）**：
     - `NextAppRoot.kt:92-93, 156, 177` 的 `onPersistPaneSplit`、`onPersistSidebarCollapsed` 全仓（含测试）**0 次调用**；
     - `NextAppRoot.kt:94, 207-209` 的 `secondaryListContent` 在生产宿主 `NextAppActivity.kt:48-60` 恒为 `null`，仅 `MultiPaneTestSupport.kt:248` 单测注入（单测可直接测已公开槽位的 `MultiPaneReadingContainer`）。
  3. **越期手写 BATCH-6（`E8`/`E9`/`E11`/`E12`/`E13`）伪页面与死委托壳，总可删约 ~345 行（超过负责人 200 行阻断线）**：详见第二节逐文件可删清单。

---

## 二、负责人 5 项补充审查重点逐项核查报告

### 重点 1：生产代码净增 1,553 行（含 XML 1,575 行）逐文件可删清单

基准 `git diff --numstat b7557bf...6fa4765 -- hipda/src/main/` 实测：Kotlin `+1,782 / -229`（净增 **`+1,553`** 行），连同 `strings_app.xml` `+22` 行合计净增 **`+1,575`** 行（恰好卡在 `1050 × 1.5 = 1575` 顶格线）。逐文件审计如下：

| 生产文件（`hipda/src/main/`） | 净增行数（`+增/-删`） | 现文件总行 | 可删行数 | 性质归类与具体行号依据 |
|---|---:|---:|---:|---|
| `feature/app/NextAppRoot.kt` | `+322` (`+379/-57`) | 458 | **~164** | **① 测试/死钩子（8 行）**：`:92-94, 156, 177, 207-209`（`onPersistPaneSplit`/`onPersistSidebarCollapsed` 全仓 0 调用；`secondaryListContent` 仅测试调用）；<br>**② 另起影子存储（11 行）**：`:110-120, 154, 175` 4 个 `rememberSaveable` + 4 个 `LaunchedEffect`，改为直接读 `settings.*` 仅需 2 行；<br>**③ 卡外 M6 先写再删功能（~145 行）**：`:258-458` 违背 `BATCH-5.md` §1.1“未落地一级入口先展示干净占位页”，提前手写 `E8`/`E9`/`E11` 的 `SingleChoiceSegmentedButtonRow` 分段标签切换（`短消息\|提醒`、`主题\|回复`、`收藏\|关注`）、`E12` 5 行 `SettingsGroup` 列表与 `DefaultSettingsDetailPane`、`E11` 完整 `DefaultMineMenuPane`、`E13` 带 `资料\|TA 的帖子` 标签的 `DefaultUserProfilePane` 及纯色 `SolidPaneTopBar`（真页在 M6 实为玻璃顶栏，届时 100% 推倒删除）。缩回复用 `HiEmptyState` 的干净占位仅需 ~55 行。 |
| `feature/app/BasicMultiPaneContainer.kt` | `-66` (`+25/-91`) | 44 | **44** | **提前抽象 / 死兼容壳（整文件 44 行）**：全仓 `src/main` 与 `src/debug` **0 次调用**，仅 `NextAppTracerBulletTest.kt` 2 处测试挂载。按 `E3.md` L80/L91 将这两处测试切至 `MultiPaneReadingContainer` 即可整文件删除。 |
| `feature/app/NextAppHostViewModel.kt` | `+221` (`+260/-39`) | 338 | **~62** | **① 另起死存储与多构造器（~16 行）**：`:40, 42, 45-50, 290, 320-323` `SavedStateHandle` 只写不读；<br>**② 重复代码（28 行）**：`:171-199` `selectMessageEntry` 与 `openMineSubPage` 逻辑同 `:137-154` `selectSidebarEntry` 高度重复，直接统一调 `selectSidebarEntry` 即可删除；<br>**③ 卡外/零调用预留（18 行）**：`:212-225` `selectSettingsGroup`（属 `E12`）与 `:245-248` `pushLinkedThread`（生产 0 调用）。 |
| `feature/app/NextAppState.kt` | `+127` (`+128/-1`) | 149 | **~46** | **① 卡外/提前抽象（21 行）**：`:17-23, 45` `SettingsGroup` 5 分组枚举（属 `E12`）与 `:31-32, 67` `DetailDrilldownEntry.LinkedThread`（生产 0 入口）；<br>**② 过度手写编解码（~25 行）**：`:79-149` 用 `\u001F` 手写字符串拼拆 71 行，直接用 `listSaver` 扁平存基础类型可省 ~25 行。 |
| `res/values/strings_app.xml` | `+22` (`+22/-0`) | 22 | **~22** | **卡外功能文案（22 行）**：E3 所需的分隔线动作、全屏阅读、面板标题与空占位早已在 `strings_shell.xml:30-41` 齐备；本文件 16 条资源 100% 服务于 `NextAppRoot.kt` 中越界手写的 M6 伪页面（`E11`/`E12`/`E13`）。 |
| `feature/app/MultiPaneReadingContainer.kt` | `+456` (`+456/-0`) | 456 | **~10** | 核心多栏容器实现克制（标准 `AnchoredDraggable` + `movableContentOf`）；随 `SettingsGroup` 精简 `:90, 123, 136, 253` 等约 10 行。 |
| `feature/app/MultiPaneBackStack.kt` | `+161` (`+161/-0`) | 161 | **0–3** | 纯函数表达铰链双栏角色、5 级返回与锚点表，无水分。 |
| `feature/app/PaneDividerHandle.kt` | `+144` (`+144/-0`) | 144 | **0** | 标准 `AnchoredDraggable` + `CustomAccessibilityAction` + 键盘方向键/`Enter`，无冗余（需修 `B1` 命中问题）。 |
| `feature/app/MultiPaneSnackbarHost.kt` | `+123` (`+123/-0`) | 123 | **0** | 分区几何计算与 `HiSnackbarHost` 委托清晰克制。 |
| `ui/shell/HiAdaptiveShell.kt` | `+32` (`+64/-32`) | 491 | **0** | 精确收口 `R-C4d S1` 与 `R-E0 S4` 单栏二级页退场胶囊，无水分。 |
| `ui/shell/HiNavigationSidebar.kt` | `+9` (`+12/-3`) | 1168 | **0** | 精确收口 `R-C4d S2` 跳过首次挂载重置滚动位 + `totalUnreadCount`。 |
| `ui/shell/{HiNavigationPill,ShellIndicatorLayout}.kt` | `+24` (`+30/-6`) | — | **0** | 落实 `E3.md` L40/L55 底部胶囊消息合并未读角标必需改动（BATCH-5 无其他卡改 `ui/shell/`，0 冲突）。 |
| **合计** | **`+1,575`**（Kotlin `+1,553`） | — | **`~348` 行** | **可删约 `348` 行（`> 200` 行上限），瘦身后净增约 `~1,227` 行，已列为阻断项 `B3`。** |

---

### 重点 2：三栏纯宽度通用规则、零机型特判、`749×559` → `64|274|411` 与铰链 `≥280 / ≥360` 核实

- **零机型特判核实：通过**。全仓扫描 `src/main` 与 `src/debug`，**0 处** `Build.MODEL`/`MANUFACTURER` 判断、**0 处** `Fold8` 或 `749` 机型硬编码常量（`FOLD8_*` 仅存在于 `src/test/.../FeatureScreenshotQualifiers.kt`）。分档完全由 `ShellNavigationMode.kt:265-418` `calculateShellPaneLayout` 按通用规则（高 `< 480f` → 竖向分离铰链 → 宽 `< 600f` → `< 680f` → `< 840f` → `≥ 840f`）计算。
- **`749 × 559dp` 计算与布局实测：通过**。
  - 公式代入：`heightDp = 559f >= 480f`、`hingeBoundsDp = null`、`680f <= 749f < 840f` → `CompactThreePane`：`L = 64`，`S = 749 - 64 = 685`，`M_default = (0.40f * 685).roundToInt().coerceIn(256, 380) = 274`，`R_default = 685 - 274 = 411`（`64 + 274 + 411 == 749`）；均衡档 `M_balanced = min(343, 685 - 360 = 325, 480) = 325`（差值 `51 >= 24`）。
  - 布局实测：分隔线叠放于 `Row` 外不占布局宽，`MultiPaneDividerGestureTest.kt:58-76` 与 `multipane_w749_h559_light.png` 实测语义边界精确为 `64 | 274 | 411dp`。
- **物理铰链双栏 `左栏 ≥ 280dp`、`右栏 ≥ 360dp`：通过**。
  - `ShellNavigationMode.kt:38-39, 290-315` 定义 `HINGE_LEFT_MIN_WIDTH_DP = 280`、`HINGE_RIGHT_MIN_WIDTH_DP = 360`；仅当 `hingeBoundsDp` 两侧同时满足 `left >= 280 && right >= 360` 时进入 `HingeTwoPane`（中间由 `Spacer(Modifier.width(hingeGapDp))` 精确避开折痕），任一侧不足（如 `701×841dp` 半折单侧 `347dp < 360dp`）立即退回 `HingeFallbackSinglePane` 单栏并将紧凑胶囊置于较宽侧。

---

### 重点 3：5 级返回栈与“展平/半折无损晋升回退”是否过度设计？`product-design.md` §4 是否真有要求？

- **`product-design.md` §4 原文确实逐字要求了这两项**：
  1. **5 级返回栈**：见 `docs/design/product-design.md` **§4.2 L173–182**，明文规定了 1–5 级系统返回优先级（关顶层浮层 → 退全屏阅读 → 右栏下钻出栈 → 右栏根内容回占位/铰链子列表降级回我的菜单 → 非默认入口回默认论坛 → 交还系统）。
  2. **展平三栏 ↔ 半折铰链双栏晋升与回退**：见 `product-design.md` **§4.2 L152–156** 与 **§4.3 L191–196**，明文规定 `浏览历史 / 我的帖子 / 收藏与关注` 选帖半折后左栏承接原来源子列表（带返回 `我的` 箭头）、按返回退回 `左：我的菜单 | 右：原副列表`；且该项在 `BATCH-5.md` L200/L428 与 `E3.md` L33/L50 中被列为 `B4-E3 ②` 必测验收项。
- **过度设计边界判定**：
  - **纯函数状态模型层（`MultiPaneBackStack.kt` 161 行）并不重，不建议删**：不到 100 行无副作用纯函数就把全形态路由定型了，M6 `E9`/`E11`/`E12` 接入时无需返工改容器。
  - **真正过度设计的是外围 M6 伪页面 UI（已归入 `B3` 要求删减）**：在 M5 阶段 `E8`–`E13` 均未开发，无需在 `NextAppRoot.kt` 里为此手写 201 行分段标签/设置分组/伪个人主页 UI 与 `strings_app.xml`——用极简 `HiEmptyState` 占位配合 `MultiPaneReadingContainer` 槽位测试即可完整闭环 `B4-E3 ②`。

---

### 重点 4：分栏拖拽持久化是否严格走 `SettingsRepository`、有无另起存储

- **写入与主读取接了 `SettingsRepository`**（`DataStoreSettingsRepository` 的 `paneSplitPortrait` / `paneSplitLandscape` / `sidebarCollapsedPortrait` / `sidebarCollapsedLandscape`），未新建 `SharedPreferences` 或新 `DataStore` 文件。
- **但违规另起了两处冗余存储（已列入阻断项 `B3`）**：
  1. `NextAppHostViewModel.kt:290` 向 `SavedStateHandle` 写入从不读取的 `"pane_split_portrait"` / `"pane_split_landscape"`；
  2. `NextAppRoot.kt:110-120` 用 4 个 `rememberSaveable` 变量另存 Bundle 影子副本，且在进程恢复首帧被冷流初始值 `AppSettings()` 经 `LaunchedEffect` 反向冲回默认值。应全部删去，统一只读写 `SettingsRepository`。

---

### 重点 5：与 `origin/main` @ `fbeeb28`（已合入 `E2b` + `E2d`）的合并兼容性

1. **Git 树级预演**：执行 `git merge-tree --write-tree fbeeb28 6fa4765` 输出合并树 `f10599378eb43cb679354c4a6917833ba9128ecc`，退出码 **`0`**，**零冲突**（`b7557bf..fbeeb28` 仅改 `feature/threaddetail/*`、`strings_thread_media.xml`、`thread_*` 快照与 `docs/agents/*`，与 E3 文件集交集为空）。
2. **语义、构造器与快照兼容性核实**：
   - `fbeeb28` 的 `ThreadDetailPane` 顶层签名与 `ThreadDetailViewModel(threadDetailRepository, siteAccessGateway)` 双参主构造器完好保留，E3 生产与测试代码 100% 编译兼容；
   - `fbeeb28` 的 `ThreadDetailViewModel.bindArgs` 在新实例首次绑定时 `isRebind == false`（不发 `scrollToTop`），不会破坏 E3 `StateRestorationTester` 恢复的详情滚动偏移；
   - `fbeeb28` 仍保留 `ThreadDetailPaginationBar`（待 Wave 4 `E4` 替换），且 E3 `MultiPaneScreenshotTest` 的 `richFloors` 为第 1/3 页纯文本+引用（无图片块、无头尾分页 footer），18 张 `multipane_*.png` 合并后无需重录。

---

## 三、`R-E3.md` 其余专项检查核实与非阻断建议（Suggestions）

### 3.1 已核实通过项
1. **空右栏拖动锚点硬底线（B4-E3 ①）与全屏阅读**：`resolvePaneSplitAnchors` 在 `hasDetailContent == false` 时严格不含 `PaneSplit.COLLAPSED (0)`，`MultiPaneDividerGestureTest` 在 `autoAdvance = false` 下逐帧验证向左大幅拖动中栏宽恒 `≥ M_default` 且不暴露 `收起列表`；`全屏阅读 / 显示列表` 准确隐藏/还原左中栏并释放 `middleGlassState`（`LayerBackdrop` `2 -> 1`）。
2. **`R-E0 R2-S3/S4` 四项核实**：① 切 `tid` 通过 `key(args.tid)` + `SaveableStateProvider("detail:$tid")` 彻底隔离重置无键 `remember`/`rememberSaveable`；② 开帖态切左栏版块/`TodayNew`/铰链顶栏标签自动清空 `selectedThread` 并在退场后立即 `clear()` 旧帖 `ViewModelStore`（无僵尸 VM 重建）；③ 单栏进二级页退场底部胶囊且 `HiNavigationPill` 正确显示合并未读角标（`7`、`99+`、归零隐藏）；④ `StateRestorationTester` 进程回收恢复已覆盖（跨目的地保留需按 `B2` 修复）。
3. **三栏 3 区域玻璃预算、多栏 Snackbar 与 `R-C4d S1–S3`**：稳态三栏 `HiGlassMarker ≤ 3`、独立 `LayerBackdrop ≤ 2`、0 嵌套源、静止 `drawBackdrop ≤ 5`、铰链双栏左顶栏纯色（0 玻璃）；`R-C4d S1`（`fallbackArmed` 首帧防同源循环）、`S2`（`previousExpanded` 跳过首次挂载归零并断言偏移保留）、`S3`（胶囊拖拽悬停未松手 `primary` 预选色断言）全部闭环且回退必红；`MotionTokenScanTest` 白名单 0 改动、`CopyTerminologyScanTest` 全绿、既有非 `multipane_*` 快照 0 漂移。

### 3.2 非阻断改进建议（`S1`–`S2`）
- **S1 — 缓存 `MultiPaneSnackbarHost` 退场动画期的目标分区（`MultiPaneSnackbarHost.kt:110`）**：当前 `hostState.currentSnackbarData` 在超时/关闭瞬间变为 `null`，`target` 立即回退为 `PaneSnackbarTarget.ContentArea`，导致右栏 `DetailPane` Snackbar 在 ~150ms 退场滑出动画期间横跨分隔线或铰链跳到左侧内容区；建议用 `remember` 记录最近一次非空 `target`。
- **S2 — 对齐空右栏回退后的 `PaneSplit` 同步与真实返回键测试（`MultiPaneReadingContainer.kt:275-289`、`MultiPaneBackStackTest.kt:218-234`）**：持久化为 `COLLAPSED` 时若因空右栏回退到 `DEFAULT` 后再点开帖子，`dragState` 停在 `DEFAULT` 但 `persistedPaneSplit` 仍为 `COLLAPSED`，旋转横竖屏再转回会意外收起列表；另 `modalDrawerOverlay_priority1` 当前点的是遮罩（`:230`），宜改为触发 `OnBackPressedDispatcher` 验证真实系统返回键优先级。


---

# 二轮复审报告（Round 2：修复轮 1 @ `ba8f4c2`）

- **复审结论**：**`APPROVE`**
- **复审对象**：`origin/card/E3-multipane-container` @ `ba8f4c204a863dfaa995a23a4f49f783cba0f6e8`（简称 `ba8f4c2`；其中 `c53e046` 为零冲突合入 `origin/main` @ `fbeeb28`，纯修复差异为 `git diff c53e046..ba8f4c2`）
- **与最新 `origin/main`（`5662652`）合并预演**：`git merge-tree --write-tree origin/main ba8f4c2` → 输出 tree `0acf918b6822c719a0f50e9bee60f80bc63d1b93`，退出码 **`0`（0 冲突）**
- **GitHub Actions CI 核实**：[Android CI #45722612209](https://github.com/mailwmj/hipda-next/actions/runs/45722612209)
  - `headSha`：`ba8f4c204a863dfaa995a23a4f49f783cba0f6e8`（100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139584393325`）：**`SUCCESS`**（含 `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 全量单测与 21 张 `multipane_*.png` 及既有快照 0 diff）
- **阻断项**：**0 项**（一轮 `B1`–`B3`、`S1`–`S2` 及 `V-E3 F1` 全部核实闭合）
- **非阻断建议项**：**1 项**（`R2-S1`，供后续 `E4` 顺手衔接）

---

## 一、负责人 4 项二轮审查重点逐项核实

### 1. 重点 1（`B1`）：分隔线热区范围、竖滑透传，以及测试是否真点到「更多」和「返回」

- **生产实现核实（`PaneDividerHandle.kt:64-218`、`MultiPaneReadingContainer.kt:384-434`）**：
  1. **热区严格限缩在分隔线 `48dp` 邻域且不消费轻点/竖滑**：拖拽手势 `Modifier.paneDividerDrag` 移至包裹 `Row` 的父容器 `Box`（`:386`），在 `PointerEventPass.Initial` 下若起点 `down.position.x !in hitLeft..(hitLeft + 48.dp)` 立即早退；落入 `48dp` 热区后，`down` 不调 `consume()`，手指直接抬起（轻点）或 `abs(dy) > touchSlop && abs(dy) > abs(dx)`（竖滑）均直接 `break` 退出循环、0 消费完整透传给底层 `Row`；仅当 `abs(dx) > touchSlop && abs(dx) >= abs(dy)` 时进入拖拽、消费事件并调用 `dragState.dispatchRawDelta` + `Snapshot.sendApplyNotifications()`（同帧 1:1 跟手，松手继承初速走 `HiMotion.spatialSnap()`）。
  2. **顶层兄弟手柄零指针拦截**：`PaneDividerLine`（`1dp/2dp`）无任何输入/语义修饰符；`PaneDividerHandle` 缩为居中 `48×96dp`（完全避开顶栏/底栏），且仅挂 `.focusable()` + `.onKeyEvent` + `.semantics`——经 `javap` 核实 `androidx.compose.foundation.FocusableNode` 仅实现 `SemanticsModifierNode`/`FocusTargetModifierNode`，**不含 `PointerInputModifierNode`**，在任意纵坐标上均不拦截指针命中。
- **单测真实命中核实（`MultiPaneDividerGestureTest.kt:289-344`，确认绝非绕过）**：
  1. **「更多」按钮（`:297`）**：调用 `onNodeWithContentDescription("更多").performClick()`（底层派发真实触摸 `MotionEvent` 走 `LayoutNode.hitTest`，非 `SemanticsActions.OnClick`），成功展开菜单并点击「按发帖排序」写入设置；
  2. **交界竖滑（`:308-321`）**：在 `rootX = 328dp`（距 `338dp` 分隔线仅 `10dp`，深处 `314..362dp` 热区内）派发竖向 `down + moveBy(0, -32dp) × 6 + up`，实测中栏 `LazyColumn` 滚动偏移增大；
  3. **「返回」按钮（`:326-343`）**：先将分隔线拖至 `COLLAPSED`（此时 `48dp` 拖拽热区位于右栏左缘 `contentX = 0..48dp`），再 `pushUserProfile` 渲染左上角 `返回` `IconButton`（`UnbuiltEntryPlaceholder` `start = 4.dp`、`48×48dp`，中心 `contentX = 28dp` **正处 `0..48dp` 拖拽热区正中央**），调用真实触摸 `performClick()` 成功弹出下钻栈。

---

### 2. 重点 2（`B2`）：容器状态外提至 `AnimatedContent` 之外，过渡期间零双份页面、零双份采样源

- **状态外提与跨目的地保活核实（`NextAppRoot.kt:86, 152-153`、`MultiPaneReadingContainer.kt:111-154`）**：
  - `rememberMultiPaneSlotHolder(hostViewModel)` 在 `NextAppRoot:86`（`HiAdaptiveShell` 及其 `AnimatedContent(selected)` 之外）创建唯一 `SaveableStateHolder` 与 `detailMovables`。
  - `MultiPaneStateMigrationTest.b2_forumScrollSwitchToSearchMineMessagesAndBack_preservesListAndDetailScrollWithZeroExtraRequests`（`:880-931`）实测单栏（`411dp`）与三栏（`749dp`）下论坛滚至中段后依次切遍 `Search -> Mine -> Messages -> Forum`，列表与重开帖子的 `LazyListState` 滚动偏移无损保留且网络请求增量为 `0`。
- **过渡期间单实例与单采样源核实（`MultiPaneReadingContainer.kt:188, 236-280, 400-409, 480`）**：
  - `NextAppRoot:138, 152` 将 `AnimatedContent` 分支快照 `destination` 传入容器；退场分支 `isActiveDestination == false` 时：
    1. 按自身 `destination` 解析 `branchSidebarEntry`（各分支 `ShellDestination` 互异，绝不与进场分支同渲 `ForumPane` 或争用同一 `SaveableStateProvider` key）；
    2. `forumChromeSpec` 强制 `useGlassTopBar = false, externalGlassState = null`，`listGlassModifier = Modifier`，且右栏 `if (isActiveDestination) RenderRightSlot(...)` 直接不挂载退场右栏。
  - `b2_destinationCrossfade_neverMountsDuplicateForumPaneOrDuplicateGlassSource`（`:939-984`）在 `autoAdvance = false` 下逐帧推进 `Search ↔ Forum` 双向过渡共 32 帧，每帧硬断言 `ForumPane <= 1`、`HiGlassMarker <= 1`、`LayerBackdrop <= 1`、嵌套源 `== 0`。

---

### 3. 重点 3（`B3`）：删除 374 行后净增 1,384 行复盘（有无残留可删项）及 `SettingsRepository` 首帧无闪

- **净增行数与可删残余逐文件复核（`git diff --numstat fbeeb28...ba8f4c2 -- hipda/src/main/`）**：
  - 生产代码相对 `fbeeb28` 共 `+1,631 / -247` = **净增 `+1,384` 行**（满足 `< 1,575` 行 1.5 倍上限；较一轮 `+1,575` 净减 `191` 行，在删除 374 行冗余的同时新增了 `B1` 父容器手势透传与 `B2`/`F1` 槽位外提修复）。
  - 一轮指出的应删项已 **100% 清零**：`BasicMultiPaneContainer.kt`（整文件删）、`strings_app.xml`（整文件删）、`NextAppRoot` 3 个测试/死钩子与 4 个 `rememberSaveable` 影子状态及 201 行 M6 伪业务页（缩为 24 行 `UnbuiltEntryPlaceholder`，文件从 458 行降至 264 行）、`NextAppHostViewModel` 的 `SavedStateHandle` 死写与 `selectMessageEntry`/`openMineSubPage`/`selectSettingsGroup`/`pushLinkedThread`、`NextAppState` 的 `SettingsGroup`/`LinkedThread`/`\u001F` 手写编解码器全部删除。
  - 复查现存全部 11 个生产改动文件：无任何卡外功能、无测试专用钩子、无死包装类残留。
- **仅走 `SettingsRepository` 且首帧不闪核实（`NextAppRoot.kt:74-100`、`NextAppHostViewModel.kt:235-247`）**：
  - 分栏比例与侧栏收起读写唯一走 `SettingsRepository`；`NextAppRoot:74-92` 对 `StateFlow` 同步取 `.value`（0 延迟），对 `DataStore` 冷流在首个真实磁盘值发出前渲染纯背景占位（且先完成 `savedAppState` 与 `slotHolder` 恢复注册），首帧挂载即直达持久化的 `BALANCED` 与 `64dp` 收起态（不触发 `HiAdaptiveShell` `187dp -> 64dp` 动画闪烁），已由 `b3_coldSettingsFlow_restoresPersistedSplitAndSidebarOnFirstRenderedFrameWithoutFlash` 逐帧锁死。

---

### 4. 重点 4（`V-E3 F1`）：全槽位 key 互斥证明与晋升/回退状态保留

- **无双槽同持同一 key 核实（`MultiPaneReadingContainer.kt:283, 311-317, 469-475`）**：
  - 在 `RenderRightSlot` 内通过 `val currentSlot by rememberUpdatedState(slot)` 同步捕获当前目标槽位，`RightPaneSlot.MineSubList` 分支写为 `if (target == currentSlot) secondaryMovableFor(...) else Box(Modifier.fillMaxSize())`。
  - **晋升（右 → 左）**：点击右栏 `History` 条目同帧，`currentSlot` 变为 `RightPaneSlot.Thread`，右栏 `AnimatedContent` 退场的 `MineSubList(History)` 分支在同一组合遍立即让出 `secondaryMovableFor(History)`，左栏 `PromotedMineSubList` 同遍接管该唯一 `movableContentOf` 实例；
  - **回退（左 → 右）**：按系统返回或点左栏「返回我的」同帧，左栏 `when (roles.left)` 无动画切回 `MineMenu` 立即释放 `secondaryMovableFor(History)`，右栏进场分支 `target == currentSlot` 同遍接管；
  - 核查全部三类键族（`"forum:*"`、`"detail:*"`、`"sidebar:*"`）在单栏/三栏/铰链双栏及跨目的地过渡下，任意单帧最大持有槽数恒 `<= 1`。
- **状态保留与视觉基线核实**：`f1_hingeTwoPane_mineSubListHistory_tapToPromoteAndBackToDemote_noCrashAndPreservesScrollState`（`:1267-1427`）在 `autoAdvance = false` 下逐帧验证晋升、真实 `OnBackPressedDispatcher` 回退与二次晋升全程零崩溃且 `History` 滚动偏移严格保留；新增 3 张基线截图（`empty_detail`、`balanced_split`、`detail_pill_hidden`）目视复核无误。

---

## 二、非阻断建议（供下游 `E4` 顺手衔接）

- **R2-S1 — 已关闭帖子的 `SaveableStateHolder` 条目上限治理（`MultiPaneReadingContainer.kt:130-135`）**：为支持同会话重开同一帖子保留滚动位，本轮移除了 `onDispose` 中的 `saveableStateHolder.removeState("detail:$tid")`（`ViewModelStore` 仍即时 `clear()`，不同 `tid` 间仍严格隔离）；待 Wave 4 `E4` 接入 Room `ReadingPositionDao` 持久化阅读位置后，可对已关闭帖子的 `"detail:$tid"` 保存态加轻量 LRU 上限或交由 `E4` 统一恢复，避免超长会话下 Bundle 累积大量历史键。
