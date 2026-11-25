# 任务卡 C4c 交付报告：自适应外壳三栏升级与左侧栏（HiNavigationSidebar + calculateShellPaneLayout）

## 1. 交付摘要

- **分支**：`card/C4c-three-pane-shell`（worktree：`/home/mir/Documents/hipda-next.wt/C4c`）
- **起点基线**：`origin/main` @ `b4ab9160f2afccb63bd9d0601ac86d251ec86d251`（后续按负责人通知合并 `origin/main` @ `7cf0e70` 含 G1 毛玻璃主题 API 与指示器调优）
- **最终 Commit**：`6706b09869ece5329c12738c2782bf303f305b29`（核心实现提交 `7fbc2186409f9a57c66dd8ffb7ef2a5ceed36cbe` + G1 合并与 `shell_*` 基线重录提交 `6706b09869ece5329c12738c2782bf303f305b29`）
- **GitHub Actions CI**：[Run #45603328840](https://github.com/mailwmj/hipda-next/actions/runs/45603328840) — **全绿（`success`）**
  - `Build and lint`：`success`
  - `Verify Room schema consistency`：`success`
  - `Run unit tests and verify Roborazzi screenshots`：`success`（**TOTAL: 601 tests, 601 passed, 0 failed, 0 errors, 0 skipped**）
  - `Verify APK outputs`（Debug + Release）：`success`
- **合并 G1（`7cf0e70`）处理原则**：
  - `HiGlassSurface.kt` 完整保留 G1 全部令牌、主题 API（`HiGlassStyle`、`LocalHiGlassStyle`、`HiGlassHazeConfig`、`toHazeConfig()`、`toHazeBlurStyle()`、`surfaceColor = Color.Unspecified` 与渐变高光描边），仅做三栏结构清理（移除无调用方的 `HiGlassBorderMode` / `EndEdgeOnly` 与 KDoc 中“导航轨”措辞）。
  - `ShellIndicatorLayout.kt` 删除 `Rail` 分支并采用 G1 的选中指示器颜色（`primary.copy(alpha = HiTheme.glass.indicatorAlpha)` 底 + `primary` 前景）。
  - `HiAdaptiveShell.kt` 改为读取 `HiTheme.glass.floatingShadowElevation`（默认 `4.dp`），与 G1 玻璃主题 API 对齐；左侧栏为纯色 `surfaceContainerLow`，不使用毛玻璃（外壳毛玻璃计数在 `≥ 600dp` 严格为 `0`）。
  - `PreviewActivity.kt` 合并保留 G1 的 `GlassTuningPanel`、`GlassColorBackdropBanner`、`LocalHiGlassStyle` 实时调参入口与 C4c 的左侧栏 + `≥ 680dp` 三栏预览，同时通过 `GlassTuningPanelTest`（3/3）与 `HiAdaptiveShellTest`（29/29）。
  - 仅重录 `shell_*` 截图基线，`glass_*` / `theme_*` 等其他基线与 `origin/main` 完全一致。

---

## 2. 验收项逐条核验与证据

### 2.1 边界与零冲突守卫 — **通过**

- **改动文件清单（`git diff --name-status origin/main...HEAD`）**：
  - 生产代码（均在卡片授权范围）：
    - `A hipda/src/main/java/net/jejer/hipda/ui/shell/ForumMetadata.kt`
    - `A hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt`
    - `D hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationRail.kt`（已删除）
    - `M hipda/src/main/java/net/jejer/hipda/ui/shell/ShellNavigationMode.kt`
    - `M hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt`
    - `M hipda/src/main/java/net/jejer/hipda/ui/shell/ShellIndicatorLayout.kt`
    - `M hipda/src/main/java/net/jejer/hipda/ui/shell/ShellBarState.kt`
    - `M hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiTypography.kt`（仅 L14、L152 注释“导航轨”→“左侧栏”）
    - `M hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt`（仅结构清理，零视觉参数改动）
    - `M hipda/src/main/res/values/strings_shell.xml`（全部文案逐字取自 `docs/design/copy.md`）
    - `M hipda/src/main/java/net/jejer/hipda/data/settings/AppSettings.kt`（跨线唯一授权）
    - `M hipda/src/main/java/net/jejer/hipda/data/settings/SettingsRepository.kt`（跨线唯一授权）
    - `M hipda/src/main/java/net/jejer/hipda/data/settings/DataStoreSettingsRepository.kt`（跨线唯一授权）
  - Debug 预览：`M hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`
  - 测试与截图基线：`DataStoreSettingsRepositoryTest.kt`、`ui/shell/*Test.kt`、仅 `hipda/src/test/snapshots/images/shell_*.png`。
- **禁止修改项零触碰**：未触碰 `gradle/libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`、`MotionTokenScanTest` 白名单、`HiNavigationPill.kt`、`HiSnackbarHost.kt`、`feature/`。
- **`src/main/` 零测试专用代码 & 零 debug 符号引用**：
  - `grep -rn "SemanticsPropertyKey\|TestOnly\|VisibleForTesting" hipda/src/main/java/net/jejer/hipda/ui/shell/` → 无命中。
  - `HiGlassSurfaceTest.mainSources_doNotReferenceDebugGlassTuningOrPreviewSymbols` → **PASSED**。
- **证据 1：`git diff origin/main...HEAD -- hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt`**（仅删除未用 import、`HiGlassBorderMode`/`EndEdgeOnly` 与“导航轨”注释，零视觉令牌/参数改动）：
  ```diff
  -import androidx.compose.ui.draw.drawWithContent
  -import androidx.compose.ui.unit.LayoutDirection
  -/** 控制 [HiGlassSurface] 描边的绘制范围：默认四边闭合，贴边导航轨仅画朝向内容的一侧。 */
  -enum class HiGlassBorderMode { AllSides, EndEdgeOnly }
  - * 毛玻璃表面组件，仅用于主导航（底部胶囊、导航轨）与帖子详情底部工具条（同屏最多 2 块）。
  + * 毛玻璃表面组件，仅用于底部导航胶囊与帖子详情底部工具条（同屏最多 2 块）。
  -    borderMode: HiGlassBorderMode = HiGlassBorderMode.AllSides,
  （borderModifier 移除 when(borderMode) 与 EndEdgeOnly 分支，保留 G1 AllSides 渐变高光与轮廓暗线逻辑不变）
  ```
- **证据 2：`git diff --stat origin/main...HEAD -- hipda/src/test/snapshots/`**：
  - 仅涉及 25 个 `shell_*.png` 文件（12 新增 + 9 更新 + 4 退役删除），`git diff --name-only origin/main...HEAD -- hipda/src/test/snapshots/ | grep -v "/shell_"` 输出为空。

---

### 2.2 `calculateShellPaneLayout` 栏宽单测 — **通过**

- 测试类：`net.jejer.hipda.ui.shell.ShellNavigationModeTest`（9/9 PASSED）：
  1. `shellNavigationMode_matchesAllSpecBreakpoints`：验证 `<480dp`、`600dp`、`840dp` 及边界值 `599.9f`/`600f`/`839.9f`/`840f`。
  2. `shellNavigationMode_hingeSeparatingForcesCompactPill`：验证竖向铰链分隔强制走 `CompactPill` 且高 `<480dp` 优先。
  3. `calculateShellPaneLayout_singlePaneCompactAndSidebarSinglePaneHaveZeroRightPane`：验证 `340/411/559/599dp`（单栏）、`891×411dp`（矮屏）、`600/640/679dp`（图标轨单栏）及非整数宽（`599.6f`/`679.6f`）满足 `L + M + R == w` 且 `rightPaneWidthDp == 0`。
  4. `calculateShellPaneLayout_threePaneWidthsExactIntegerClosureAndSnapPoints`：覆盖 `680/744/749/748.59f/800/839/840/852/851.69f/860/883/900/933/1024/1100/1280dp` 与 `leftCollapsedPref = true/false`、`hasDetailContent = true/false`，严格断言先按浮点分档再 `w = widthDp.roundToInt()`、精确满足 `L + M + R == w`、`R >= 360`、吸附点计算与 `< 24dp` 合并、`hasDetailContent == false` 不包含 `0dp` 档。
  5. `calculateShellPaneLayout_verticalHingeTwoPaneAndFallbackRules`：覆盖 `749dp` 铰链双栏（`leftW >= 280 && rightW >= 360`，左列表侧紧凑胶囊、`hasDetailContent` 控制 `0` 档）、`701dp` 单侧不足退单栏（`rightPaneWidthDp == 0`）、`260|440dp` 非对称退单栏（紧凑胶囊居中较宽右侧）、`559dp` 退单栏（两侧 `< 280dp`，紧凑胶囊整窗底部居中）。
  6. `sidebarEntryAndDestination_bidirectionalMappingIsDeterministic`：覆盖 `ShellSidebarEntry ↔ ShellDestination` 全部入口双向映射，及窄→宽在“我的”总菜单时优先恢复本次会话最近访问子项、未访问默认 `FavoritesAndAttention`。
  7. `forumMetadata_hasAll21UniqueBuiltInForumsAndHandlesCollisionAndUnknownFallback`：核验 21 个内置版块 `fid`/短标/图标唯一性、未知 `fid` 自动回退 `MonogramBadge`、同图标碰撞自动双双回退 `MonogramBadge`。
  8. `showLabelForUnselected_switchesAt360dpBoundary`：验证 `360dp` 窄窗文字显示阈值。
  9. `stringsShellXml_containsNoForbiddenTerminologyPhrases`：扫描 `strings_shell.xml` 零禁用术语。

---

### 2.3 首屏版块、浮层交互、减少动态效果、持久化与动效扫描 — **通过**

- **首屏版块与无障碍几何**（`HiAdaptiveShellTest` 29/29 PASSED）：
  - `sidebarCollapsed_fold8Landscape749x559_showsAtLeastThreeForumsWithoutScrolling`：`749×559dp` 下无需滚动，`boundsInRoot` 完整落在窗口内可见 ≥3 个默认关注版块（`Discovery`/`Buy & Sell`/`Geek Talks`），同时断言 `contentDescription` 合并未读数（`短消息，3 条未读`、`提醒，12 条未读`）。
  - `sidebarExpanded_mineGroupAutoExpandsAt760dpAndExposesTraversalPanes`：验证 `<760dp` `我的` 组默认收起、`≥760dp` 默认展开，以及 `isTraversalGroup = true` 与 `paneTitle`（`导航`/`内容`）。
  - `navigationSidebar_clampsFontScaleAt125PercentWhenSystemScaleIs200`：验证系统 `fontScale = 2.0f` 时侧栏内钳制至 `1.25f`。
  - `bottomPill_fold8Portrait559dpIsCenteredAndCappedAt420dp`：验证 `559dp` 底部胶囊居中且宽度 `≤ 420dp`。
  - `foldingFeature_verticalSeparatingHingeUsesCompactPillInLeftPane` / `foldingFeature_asymmetricFallbackCentersCompactPillInWiderSide` / `foldingFeature_horizontalTabletopHingeKeepsCollapsedSidebar` / `foldingFeature_horizontalTabletopHingeKeepsExpandedSidebar`。
  - `glassSurfaceCount_isZeroInCollapsedSidebar` / `glassSurfaceCount_isZeroInExpandedSidebar` / `glassSurfaceCount_isExactlyOneInBottomPill` / `glassSurfaceCount_isExactlyOneInFold8PortraitBottomPill` / `glassSurfaceCount_isExactlyOneInCompactPill`。
- **240dp 模态浮层交互**（`HiAdaptiveShellTest`）：
  - `sidebarModalDrawer_noLeftEdgeSwipe_locksFocus_closesOnBackEscapeAndModalAction`：验证左缘右滑不打开浮层；点 `展开侧栏` 按钮打开 `240dp` 浮层并锁定底层无障碍与键盘焦点（底层节点不可遍历/不可点击）；支持系统返回（真实宿主 `OnBackPressedDispatcher`）、外接键盘 `Key.Escape` 关闭；从浮层点击 `管理版块` 或账号同步先关闭浮层再触发回调；收起态点击 `我的` 一步展开浮层并自动展开 `我的` 分组。
- **减少动态效果（`withAnimatorDurationScale(0f)`）**（`HiAdaptiveShellTest`）：
  - `reducedMotion_modalSidebarDrawerOpensAndClosesInOneFrame`：与 `1f` 对照组对比，验证 `0f` 下浮层开合 1 帧到位。
  - `reducedMotion_permanentSidebarToggleFinishesInOneFrame`：验证 `≥ 840dp` 常驻侧栏 `205dp ↔ 64dp` 伸缩在 `0f` 下 1 帧到位。
  - `reducedMotion_indicatorAndContentFinishImmediately`。
- **持久化与状态恢复**：
  - `DataStoreSettingsRepositoryTest`（14/14 PASSED）：覆盖 `sidebarCollapsedPortrait` / `sidebarCollapsedLandscape` 默认值（`false`）、独立读写、`recreatingRepository_persistsAllModifiedValues` 跨实例持久化恢复、错误类型写入损坏回退默认值。
  - `HiAdaptiveShellTest.wideSidebar_restoresCollapsedPreferenceAfterStateRestoration` & `previewShellDemo_restoresVisibilityAndSelectedDestinationAfterStateRestoration`：`StateRestorationTester` 进程/配置重建保态通过。
- **越界、触控目标、滚动隐藏与按压反馈**：
  - `HiShellBoundsGuardTest`（32/32 PASSED）：覆盖 `BottomPill` / `CompactPill` / `SidebarCollapsed` / `SidebarExpanded` × 多宽高 × `fontScale 1.0/1.3` 无崩溃、无裁切重叠、≥48dp 触控目标。
  - `HiShellScrollAndPressTest`（21/21 PASSED）：覆盖侧栏态不随滚动隐藏（`scrollLinked_sidebar_neverHidesOnScroll`）、侧栏底距仅 `navigationBars` inset（`contentPadding_sidebar_w933_lastItemAboveNavigationBarInset`）、`w559` 胶囊滚底不被遮挡（`contentPadding_bottomPill_w559_lastItemCompletelyVisibleAbovePill`）、侧栏按压缩放 `0.97→1.0` 无触觉（`navItemPress_sidebar_scalesTo097AndReboundsTo1WithoutHaptics`）、`surfaceContainerLow` 对比度校验（`navigationSidebar_surfaceContainerLow_wcagContrast`）。
- **动效令牌与调试符号守卫**：
  - `MotionTokenScanTest`（7/7 PASSED，零白名单改动）
  - `HiGlassSurfaceTest`（9/9 PASSED）、`GlassTuningPanelTest`（3/3 PASSED）、`HiThemeScreenshotTest`（12/12 PASSED）、`HiGlassScreenshotTest`（8/8 PASSED）。

---

### 2.4 组件标准性说明、代码行数说明与截图基线清单 — **通过**

#### 标准 Compose / M3 组件审计（无新增自定义 `Layout` / `Modifier.Node`）
- 本卡新增的 **`HiNavigationSidebar.kt`** 与更新的 **`HiAdaptiveShell.kt`** **完全采用标准 Compose / Material 3 组件**：`Column`、`Row`、`Box`、`LazyColumn`、`Surface`、`HorizontalDivider`、`TextButton`、`TooltipBox`、`PlainTooltip`、`AnimatedVisibility`、`AnimatedContent`、`Modifier.draggable`、`BackHandler`，**零自定义 `Layout`、零自定义 `Modifier.Node`、零手写命令队列**。
- 浮层选择同树 `AnimatedVisibility` + `Surface` + `Modifier.draggable` 而非独立 `Popup`/`Dialog` 窗口的原因：既能精确绑定 `HiMotion.spatialDefault()` / `effectsDefault()` / `exit()` 令牌与内区左滑跟手关闭，又能被 Roborazzi 主窗口 `onRoot().captureHiSnapshot()` 稳定录制，并通过 `focusProperties` + `clearAndSetSemantics { hideFromAccessibility() }` 实现底层锁焦。
- 包内唯一保留的 `Layout` 为既有 `ShellIndicatorLayout.kt:69`（C4a 遗留的底部胶囊单遍横向指示器布局），本卡仅删除了其中旧 `Rail` 纵向分支并瘦身（由 292 行精简至 276 行）。

#### 生产代码行数说明
- `ShellNavigationMode.kt`：455 行（含全量常量、数据模型、`ShellSidebarEntry` + `Saver`、双向映射、`calculateShellPaneLayout` 6 档完整纯函数与测试标签）
- `ForumMetadata.kt`：367 行（含 21 个内置版块元数据、撞车/未知回退纯函数与 14 个自绘制 24×24 矢量图标——因项目不引入 `material-icons-extended` 且弃用旧 FontAwesome）
- `HiNavigationSidebar.kt`：1167 行（合并承载 `64dp` 收起图标轨、`176–220dp` 常驻展开侧栏、`240dp` 模态浮层抽屉、顶部滚动渐变栏、`我的` 折叠组、21 版块展示/空态引导、Tooltip、滚动数字未读角标与无障碍语义合并，全部为短小标准 Composable 组合）
- `HiAdaptiveShell.kt`：372 行（自适应外壳 + 铰链对齐 + 浮层锁焦 + `ShellContentScope`）
- `ShellIndicatorLayout.kt`：276 行（删除 `Rail` 分支并接入 G1 指示器令牌）

#### `shell_*` Roborazzi 截图基线清单（共 21 张活跃基线 + 4 张退役删除）

**1. `HiShellScreenshotTest`（18 张，`shell_*`）**

| 基线文件名 | 窗口规格 / 形态 | 外壳毛玻璃数 | 变更状态 |
|---|---|---|---|
| `shell_w411_h891_light.png` | `411×891` 浅色 · `BottomPill` | 1 | 更新（G1 玻璃+指示器） |
| `shell_w411_h891_dark.png` | `411×891` 深色 · `BottomPill` | 1 | 更新（G1 玻璃+指示器） |
| `shell_w559_h749_light.png` | `559×749` 浅色（Fold8 竖拿）· `BottomPill`（420dp 居中） | 1 | 新增 |
| `shell_w559_h749_dark.png` | `559×749` 深色（Fold8 竖拿）· `BottomPill` | 1 | 新增 |
| `shell_w640_h1024_light.png` | `640×1024` 浅色 · `SidebarSinglePane`（64dp 轨 + 单栏） | 0 | 新增 |
| `shell_w640_h1024_dark.png` | `640×1024` 深色 · `SidebarSinglePane` | 0 | 新增 |
| `shell_w749_h559_light.png` | `749×559` 浅色（Fold8 横展）· `CompactThreePane`（首屏 ≥3 版块） | 0 | 新增 |
| `shell_w749_h559_dark.png` | `749×559` 深色（Fold8 横展）· `CompactThreePane` | 0 | 新增 |
| `shell_w933_h696_light.png` | `933×696` 浅色 · `ExpandedThreePane`（187\|298\|448） | 0 | 新增 |
| `shell_w933_h696_dark.png` | `933×696` 深色 · `ExpandedThreePane` | 0 | 新增 |
| `shell_w1280_h800_light.png` | `1280×800` 浅色 · `ExpandedThreePane`（220\|380\|680） | 0 | 更新（三栏+左侧栏） |
| `shell_w1280_h800_dark.png` | `1280×800` 深色 · `ExpandedThreePane` | 0 | 更新（三栏+左侧栏） |
| `shell_w891_h411_compact_light.png` | `891×411` 浅色 · `CompactPill`（矮屏） | 1 | 更新（G1 玻璃+指示器） |
| `shell_w340_h800_narrow_light.png` | `340×800` 浅色 · `BottomPill`（极窄窗仅选中项文字） | 1 | 更新（G1 玻璃+指示器） |
| `shell_w749_h559_drawer_light.png` | `749×559` 浅色 · `240dp` 模态浮层展开态 | 0 | 新增 |
| `shell_w933_h696_collapsed_light.png` | `933×696` 浅色 · `≥840dp` 左栏主动收起态（64\|348\|521） | 0 | 新增 |
| `shell_w411_h891_step5_font130_light.png` | `411×891` 浅色 · 5 档字号 × 1.3 系统缩放 | 1 | 更新（G1 玻璃+指示器） |
| `shell_w840_h900_step5_font130_light.png` | `840×900` 浅色 · 5 档字号 × 1.3 系统缩放（侧栏钳制 1.25×） | 0 | 新增 |

**2. `HiShellScrollScreenshotTest`（3 张，`shell_scroll_*`）**

| 基线文件名 | 场景 | 外壳毛玻璃数 | 变更状态 |
|---|---|---|---|
| `shell_scroll_w411_h891_bottom_light.png` | `411×891` 滚到底（最后一项完全露出、胶囊可见） | 1 | 更新（G1 玻璃+指示器） |
| `shell_scroll_w411_h891_hidden_light.png` | `411×891` 下滚胶囊全隐中间态 | 1 | 更新 |
| `shell_scroll_w749_h559_sidebar_bottom_light.png` | `749×559` 侧栏态滚到底（底部无胶囊重叠、最后一项完整露出） | 0 | 新增 |

**3. 退役删除的旧基线（4 张）**：`shell_w700_h900_light.png`、`shell_w700_h900_dark.png`、`shell_w900_h1120_light.png`、`shell_w900_h1120_dark.png`。

---

## 3. 本地自检与 CI 日志汇总

### 本地 DISPATCH §2 串行验证（合并 `origin/main` @ `7cf0e70` 后）
1. 重录仅 `shell_*` 基线：
   `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:recordRoborazziDebug --tests "net.jejer.hipda.ui.shell.HiShell*ScreenshotTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4` → `BUILD SUCCESSFUL`
2. 单元测试 + Roborazzi verify（含 G1 玻璃与调参测试）：
   `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.ui.shell.*" --tests "net.jejer.hipda.data.settings.DataStoreSettingsRepositoryTest" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" --tests "net.jejer.hipda.ui.designsystem.glass.*" --tests "net.jejer.hipda.ui.designsystem.GlassTuningPanelTest" --tests "net.jejer.hipda.ui.designsystem.HiThemeScreenshotTest" -Proborazzi.test.verify=true -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
   - `DataStoreSettingsRepositoryTest`: 14/14 PASSED
   - `GlassTuningPanelTest`: 3/3 PASSED
   - `HiThemeScreenshotTest`: 12/12 PASSED
   - `GlassEnvironmentTest`: 14/14 PASSED
   - `HiColorContrastCalibrationTest`: 3/3 PASSED
   - `HiGlassScreenshotTest`: 8/8 PASSED
   - `HiGlassSurfaceTest`: 9/9 PASSED
   - `MotionTokenScanTest`: 7/7 PASSED
   - `HiAdaptiveShellTest`: 29/29 PASSED
   - `HiShellBoundsGuardTest`: 32/32 PASSED
   - `HiShellScreenshotTest`: 18/18 PASSED
   - `HiShellScrollAndPressTest`: 21/21 PASSED
   - `HiShellScrollScreenshotTest`: 3/3 PASSED
   - `ShellNavigationModeTest`: 9/9 PASSED
   - **TOTAL: 182/182 PASSED**
3. 编译与 Lint：
   `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4 && ./gradlew --stop` → `BUILD SUCCESSFUL`，Daemon 已停止。

### GitHub Actions CI 最终结果
- **Run**：https://github.com/mailwmj/hipda-next/actions/runs/45603328840
- **Commit**：`6706b09869ece5329c12738c2782bf303f305b29`
- **结论**：`success`（`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全通过；全仓 **601/601** 单元测试通过，Roborazzi 无 diff）。

---

## 4. 对外公开 API 清单（供 E1 / E3 / E8–E12 接入）

### 4.1 断点、布局计算与侧栏入口模型（`hipda/src/main/java/net/jejer/hipda/ui/shell/ShellNavigationMode.kt`）
- 断点与尺寸常量（`ShellNavigationMode.kt:22-39`）：
  - `SHELL_SIDEBAR_COLLAPSED_WIDTH_BREAKPOINT_DP: Float = 600f`
  - `SHELL_THREE_PANE_WIDTH_BREAKPOINT_DP: Float = 680f`
  - `SHELL_SIDEBAR_EXPANDED_WIDTH_BREAKPOINT_DP: Float = 840f`
  - `SHELL_MINE_GROUP_AUTO_EXPAND_HEIGHT_DP: Float = 760f`
  - `SHELL_SIDEBAR_COLLAPSED_WIDTH_DP = 64`、`SHELL_SIDEBAR_EXPANDED_MIN_WIDTH_DP = 176`、`SHELL_SIDEBAR_EXPANDED_MAX_WIDTH_DP = 220`、`SHELL_SIDEBAR_MODAL_WIDTH_DP = 240`
  - `SHELL_MIDDLE_PANE_MIN_WIDTH_DP = 256`、`SHELL_MIDDLE_PANE_MAX_WIDTH_DP = 380`、`SHELL_MIDDLE_PANE_BALANCED_MAX_WIDTH_DP = 480`、`SHELL_MIDDLE_PANE_SNAP_MERGE_THRESHOLD_DP = 24`、`SHELL_RIGHT_PANE_MIN_WIDTH_DP = 360`
  - `HINGE_LEFT_MIN_WIDTH_DP = 280`、`HINGE_RIGHT_MIN_WIDTH_DP = 360`
- `enum class ShellNavigationMode { BottomPill, CompactPill, SidebarCollapsed, SidebarExpanded; val isSidebar: Boolean }`（`ShellNavigationMode.kt:42-50`）
- `enum class ShellPaneLayoutMode { CompactSinglePane, HingeTwoPane, HingeFallbackSinglePane, SinglePane, SidebarSinglePane, CompactThreePane, ExpandedThreePane }`（`ShellNavigationMode.kt:53-61`）
- `data class ShellHingeBoundsDp(val left: Float, val right: Float)`（`ShellNavigationMode.kt:65-68`）
- `data class ShellPillAnchor(val startDp: Int, val widthDp: Int)`（`ShellNavigationMode.kt:72-75`）
- `data class ShellPaneLayout(val layoutMode: ShellPaneLayoutMode, val navigationMode: ShellNavigationMode, val windowWidthDp: Int, val sidebarWidthDp: Int, val isSidebarExpanded: Boolean, val canToggleSidebarPermanent: Boolean, val middlePaneWidthDp: Int, val rightPaneWidthDp: Int, val hingeBoundsDp: ShellHingeBoundsDp? = null, val middlePaneSnapPointsDp: List<Int> = emptyList(), val pillAnchor: ShellPillAnchor? = null)` 及衍生属性 `isSidebar`、`isThreePane`、`isTwoPane`、`hasDetailPane`、`contentWidthDp`（`ShellNavigationMode.kt:79-107`）
- `sealed interface ShellSidebarEntry`（`TodayNew`、`Search`、`Sms`、`Notify`、`FavoritesAndAttention`、`MyThreads`、`MyReplies`、`History`、`Forum(val fid: Int)`、`Settings`，含 `val isMineGroupChild: Boolean`）（`ShellNavigationMode.kt:139-156`）
- `val ShellSidebarEntrySaver: Saver<ShellSidebarEntry, String>`（`ShellNavigationMode.kt:159-191`）
- `fun ShellSidebarEntry.toShellDestination(): ShellDestination`（`ShellNavigationMode.kt:194-211`）
- `fun mapDestinationToSidebarEntry(destination: ShellDestination, activeForumFid: Int? = null, activeMessageEntry: ShellSidebarEntry = ShellSidebarEntry.Sms, activeMineSubPage: ShellSidebarEntry? = null, lastVisitedMineEntry: ShellSidebarEntry? = null): ShellSidebarEntry`（`ShellNavigationMode.kt:218-237`）
- `fun shellNavigationMode(widthDp: Float, heightDp: Float, hingeSeparating: Boolean = false): ShellNavigationMode`（`ShellNavigationMode.kt:247-257`）
- `fun calculateShellPaneLayout(widthDp: Float, heightDp: Float, hingeBoundsDp: ShellHingeBoundsDp? = null, leftCollapsedPref: Boolean = false, hasDetailContent: Boolean = false): ShellPaneLayout`（`ShellNavigationMode.kt:265-418`）
- `fun showLabelForUnselected(widthDp: Float): Boolean`（`ShellNavigationMode.kt:421-422`）
- `object HiShellTestTags`（含 `NavigationSidebar`、`SidebarModalDrawer`、`SidebarModalScrim`、`SidebarToggleButton`、`SidebarAccountRow`、`SidebarMineGroup`、`SidebarManageForumsButton`、`SidebarList`、`SidebarTopBar`、`PaneDivider`、`sidebarEntry(entry)` 等）（`ShellNavigationMode.kt:425-455`）

### 4.2 21 版块静态元数据（`hipda/src/main/java/net/jejer/hipda/ui/shell/ForumMetadata.kt`）
- `data class ForumMetadata(val fid: Int, val fullName: String, val shortLabel: String, val icon: ImageVector?)`（含 `val badgeMonogram: String`）（`ForumMetadata.kt:25-34`）
- `sealed interface CollapsedForumDisplay`（`IconWithShortLabel(icon, shortLabel)` / `MonogramBadge(badgeText)`）（`ForumMetadata.kt:38-49`）
- `fun resolveCollapsedForumDisplays(forums: List<ForumMetadata>): Map<Int, CollapsedForumDisplay>`（`ForumMetadata.kt:56-76`）
- `fun normalizeForumMonogram(raw: String): String`（`ForumMetadata.kt:79-92`）
- `object ForumMetadataRegistry`：`DefaultFollowedForumIds`、`BuiltInForums`（21 个内置版块）、`resolve(fid: Int, fallbackName: String? = null): ForumMetadata`、`resolveAll(fids: List<Int>, customNames: Map<Int, String> = emptyMap()): List<ForumMetadata>`（`ForumMetadata.kt:98-151`）

### 4.3 左侧栏与自适应外壳（`HiNavigationSidebar.kt` / `HiAdaptiveShell.kt`）
- `data class HiSidebarAccount(val username: String? = null, val avatarLabel: String? = null)`（含 `val isLoggedIn: Boolean`）（`HiNavigationSidebar.kt:150-156`）
- `data class HiSidebarState(val selectedEntry: ShellSidebarEntry = ShellSidebarEntry.TodayNew, val followedForumIds: List<Int> = ForumMetadataRegistry.DefaultFollowedForumIds, val forumNames: Map<Int, String> = emptyMap(), val unreadSmsCount: Int = 0, val unreadNotifyCount: Int = 0, val account: HiSidebarAccount = HiSidebarAccount(), val isCollapsedPref: Boolean? = null, val isFullScreen: Boolean = false)`（`HiNavigationSidebar.kt:164-173`）
- `@Composable fun HiNavigationSidebar(state: HiSidebarState, expanded: Boolean, mineGroupExpanded: Boolean, onToggleSidebar: () -> Unit, onToggleMineGroup: () -> Unit, onCollapsedMineClick: () -> Unit, onSelectEntry: (ShellSidebarEntry) -> Unit, onAccountClick: () -> Unit, onManageForumsClick: () -> Unit, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState())`（`HiNavigationSidebar.kt:182-236`）
- `interface ShellContentScope { val paneLayout: ShellPaneLayout }`（`HiAdaptiveShell.kt:61-63`）
- `@Composable fun HiAdaptiveShell(selected: ShellDestination, onSelect: (ShellDestination) -> Unit, modifier: Modifier = Modifier, barState: ShellBarState = rememberShellBarState(), sidebarState: HiSidebarState? = null, onSelectSidebarEntry: ((ShellSidebarEntry) -> Unit)? = null, onToggleSidebarCollapsed: ((Boolean) -> Unit)? = null, onAccountClick: () -> Unit = {}, onManageForumsClick: () -> Unit = {}, hasDetailContent: Boolean = false, content: @Composable ShellContentScope.(ShellDestination, PaddingValues) -> Unit)`（`HiAdaptiveShell.kt:81-93`）

### 4.4 侧栏收起偏好持久化（跨线授权：`AppSettings.kt` / `SettingsRepository.kt` / `DataStoreSettingsRepository.kt`）
- `AppSettings.sidebarCollapsedPortrait: Boolean = false`（`AppSettings.kt:59`）
- `AppSettings.sidebarCollapsedLandscape: Boolean = false`（`AppSettings.kt:60`）
- `suspend fun SettingsRepository.setSidebarCollapsedPortrait(collapsed: Boolean)`（`SettingsRepository.kt:44`，实现 `DataStoreSettingsRepository.kt:138-139`，Key `sidebar_collapsed_portrait` L55）
- `suspend fun SettingsRepository.setSidebarCollapsedLandscape(collapsed: Boolean)`（`SettingsRepository.kt:45`，实现 `DataStoreSettingsRepository.kt:141-142`，Key `sidebar_collapsed_landscape` L56）
