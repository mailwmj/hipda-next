# 独立代码审查报告 R-C4c：自适应外壳三栏升级与左侧栏

- **审查结论**：**APPROVE**
- **审查对象**：`origin/card/C4c-three-pane-shell` @ `6706b09869ece5329c12738c2782bf303f305b29`（核心实现提交 `7fbc2186409f9a57c66dd8ffb7ef2a5ceed36cbe` + 合并 `origin/main` @ `7cf0e70` G1 毛玻璃调优与重录 `shell_*` 基线提交 `6706b09869ece5329c12738c2782bf303f305b29`）
- **对比基线**：`7cf0e70...6706b09869ece5329c12738c2782bf303f305b29`（46 个文件变更，`+3808 / -741`）
- **CI 核实**：[GitHub Actions Run #45603328840](https://github.com/mailwmj/hipda-next/actions/runs/45603328840)
  - `headSha`：`6706b09869ece5329c12738c2782bf303f305b29`（精确匹配）
  - `Build and lint`（`./gradlew assembleDebug assembleRelease lintDebug --stacktrace`）：`success`
  - `Verify Room schema consistency`：`success`
  - `Run unit tests and verify Roborazzi screenshots`（`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true --continue --stacktrace`）：`success`（全仓 **601 tests, 601 passed, 0 failed, 0 errors, 0 skipped**；`:hipda` 570 + `:core-parser` 31）
  - `Verify APK outputs`（Debug + Release 包名与产物核验）：`success`
- **对标依据**：`docs/design/product-design.md`（v0.3 §3、§4、§5、§6.3、§8、§10）、`docs/design/copy.md`（v0.3）、`_cards/C4c.md`、`_reports/D-3pane.md` §5、`_reports/R-D3pane.md` §6.3、`docs/agents/DISPATCH.md` §4–§5。

---

## 0. 结论总览

`C4c` 完整落地了 `product-design.md` v0.3 的三栏自适应外壳基础架构：
1. `calculateShellPaneLayout` 严格按 `<480f → 竖向物理铰链 → <600f → <680f → <840f → ≥840f` 浮点优先级分档后，先执行 `val w = widthDp.roundToInt()` 再按 `L → S = w − L → M → R = S − M` 整数顺序计算，在全部整宽与真机非整数宽下精确满足 `L + M + R == w`、三栏及启用铰链双栏 `R ≥ 360dp`、单栏/退单栏 `R == 0dp`；
2. 硬边界与 G1 合并完全干净：`HiGlassSurface.kt` 零改动任何 `HiGlassTokens` 视觉参数或模糊/降级绘制逻辑，非 `shell_*` 截图基线、共享构建文件、`MotionTokenScanTest` 白名单、`HiNavigationPill.kt`、`HiSnackbarHost.kt`、`feature/` 全部零触碰；`HiNavigationRail.kt` 已删除且 `ui/` 下无任何 `Rail` 残留；
3. `HiNavigationSidebar.kt` 全程采用 Compose / Material 3 标准组件组合，零新增自定义 `Layout` 或 `Modifier.Node`，`src/main/` 零测试专用钩子，所有动效均经由 `HiMotion` / `HiMotionTransitions` 令牌驱动，`749 × 559dp` 首屏完整露出 3 个默认关注版块，侧栏态外壳毛玻璃数为 `0` 且滚底无胶囊重叠。

对照 `DISPATCH.md` §4 阻断红线，**无阻断项（0 项）**，准予合并；同时识别出 **4 项非阻断改进与测试补强建议（S1–S4）**，其中 S1/S2/S4 直接对应审查重点第 6 项“改坏关键一行测试仍通过”的变异盲区，可在后续 `E3` 多栏容器卡或收尾轮顺带收口。

---

## 一、`calculateShellPaneLayout` 数学正确性与非整数闭合审查

### 1.1 分档优先级与整数闭合代码核实（`ShellNavigationMode.kt:265-418`）

逐分支核对 `ShellNavigationMode.kt`：
- **L272**：`val w = widthDp.roundToInt().coerceAtLeast(0)`，仅用于输出整数栏宽，**不参与后续分档比较**。
- **优先级 1（矮屏，L275）**：`if (heightDp < SHELL_COMPACT_HEIGHT_BREAKPOINT_DP)`（`< 480f`，浮点比较）→ `CompactSinglePane` + `CompactPill`，`L = 0, M = w, R = 0`，`pillAnchor = (0, w)`。
- **优先级 2（竖向物理铰链分隔，L290–336）**：`HiAdaptiveShell.kt:99` 仅在 `it.isVertical && it.isSeparating` 时构造 `ShellHingeBoundsDp`（水平 Tabletop 折痕返回 `null`，不误触发）：
  - `leftW = hingeBoundsDp.left.roundToInt().coerceIn(0, w)`，`rightW = (w - hingeBoundsDp.right.roundToInt().coerceIn(hingeLeftInt, w)).coerceAtLeast(0)`；
  - 满足 `left ≥ 280 && right ≥ 360`（浮点与取整双守卫）时进入 `HingeTwoPane` + `CompactPill`，`middlePaneSnapPointsDp = if (hasDetailContent) listOf(0, leftW) else listOf(leftW)`，`pillAnchor = (0, leftW)`；
  - 任一侧不足时退回 `HingeFallbackSinglePane`（`L = 0, M = w, R = 0`），`pillAnchor` 在 `leftFloat >= rightFloat && leftW >= 280` 时取左侧（满足“等宽取左”），右侧更宽且 `>= 280` 时取右侧 `(hingeRightInt, rightW)`，两侧均 `< 280` 时取整窗 `(0, w)`。
- **优先级 3（窄屏单栏，L339）**：`if (widthDp < 600f)` → `SinglePane` + `BottomPill`，`L = 0, M = w, R = 0`。
- **优先级 4（图标轨单栏，L354）**：`if (widthDp < 680f)` → `SidebarSinglePane` + `SidebarCollapsed`，`L = 64, M = w - 64, R = 0`。
- **优先级 5 & 6（紧凑三栏 `680–839dp` / 完整三栏 `≥ 840dp`，L370–418）**：
  - `isExpandedBand = widthDp >= 840f`（浮点比较）；`isSidebarExpanded = isExpandedBand && !leftCollapsedPref`；
  - `L = if (isSidebarExpanded) (0.20f * w).roundToInt().coerceIn(176, 220) else 64`；
  - `S = (w - L).coerceAtLeast(0)`；
  - `M = (0.40f * S).roundToInt().coerceIn(256, 380)`；
  - `R = S - M`（在 `widthDp >= 680f` 即 `w >= 680` 全域内：紧凑三栏 `S >= 616` 故 `R >= 360`；完整三栏展开态 `S >= 664`、`M <= 0.4S + 0.5` 故 `R >= 398`）；
  - `M_balanced = minOf((0.50f * S).roundToInt(), S - 360, 480)`；当 `M_balanced - M >= 24` 时才加入吸附档位；仅当 `hasDetailContent == true` 时前置加入 `0`。

### 1.2 独立代入验算表

| 输入 (`widthDp × heightDp`, 姿态) | 分档结果 | `w` | `L \| M \| R` | `L+M+R==w` | 吸附档 (`detail=true / false`) | 结论 |
|---|---|---|---|---|---|---|
| `559f × 749f` | `SinglePane` / `BottomPill` | `559` | `0 \| 559 \| 0` | `559 == 559` | `[] / []` | 符合（`R == 0`） |
| `599.6f × 800f`（临界浮点） | `SinglePane` / `BottomPill` | `600` | `0 \| 600 \| 0` | `600 == 600` | `[] / []` | 先浮点分档再取整闭合成成立 |
| `600f × 900f` | `SidebarSinglePane` | `600` | `64 \| 536 \| 0` | `600 == 600` | `[] / []` | 符合（`R == 0`） |
| `679f × 900f` / `679.6f × 900f` | `SidebarSinglePane` | `679 / 680` | `64 \| 615(616) \| 0` | 闭合 | `[] / []` | 符合（`R == 0`） |
| `680f × 900f` | `CompactThreePane` | `680` | `64 \| 256 \| 360` | `680 == 680` | `[0, 256] / [256]` | `M_bal=256` 差 `0 < 24` 合并 |
| `701f × 841f` | `CompactThreePane` | `701` | `64 \| 256 \| 381` | `701 == 701` | `[0, 256] / [256]` | `M_bal=277` 差 `21 < 24` 合并 |
| `748.59f × 559f` / `749f × 559f` | `CompactThreePane` | `749` | `64 \| 274 \| 411` | `749 == 749` | `[0, 274, 325] / [274, 325]` | 符合（`R = 411 ≥ 360`） |
| `839f × 900f` | `CompactThreePane` | `839` | `64 \| 310 \| 465` | `839 == 839` | `[0, 310, 388] / [310, 388]` | 符合 |
| `840f × 900f`（展开 / 收起） | `ExpandedThreePane` | `840` | `176\|266\|398` / `64\|310\|466` | `840 == 840` | `[0,266,304]` / `[0,310,388]` | 消除浮点舍入偏差 |
| `851.69f × 883f` | `ExpandedThreePane` | `852` | `176 \| 270 \| 406` | `852 == 852` | `[0, 270, 316] / [270, 316]` | 真机非整数宽闭合 |
| `900f × 900f` | `ExpandedThreePane` | `900` | `180 \| 288 \| 432` | `900 == 900` | `[0, 288, 360] / [288, 360]` | 符合 |
| `1024f × 640f` | `ExpandedThreePane` | `1024` | `205 \| 328 \| 491` | `1024 == 1024` | `[0, 328, 410] / [328, 410]` | 消除独立四舍五入 `1025` 误差 |
| `1280f × 800f` | `ExpandedThreePane` | `1280` | `220 \| 380 \| 680` | `1280 == 1280` | `[0, 380, 480] / [380, 480]` | `L/M` 封顶，`R = 680` |
| `891f × 411f`（矮屏） | `CompactSinglePane` | `891` | `0 \| 891 \| 0` | `891 == 891` | `[] / []` | 高 `< 480f` 最优先 |
| `749f` 竖向铰链 `374.5\|374.5` | `HingeTwoPane` | `749` | `0 \| 375 \| 374` | `749 == 749` | `[0, 375] / [375]` | `leftW≥280 && rightW≥360` |
| `701f` 竖向铰链 `350.5\|350.5` | `HingeFallbackSinglePane` | `701` | `0 \| 701 \| 0` | `701 == 701` | `[]`，锚点 `(0, 351)` | `rightW < 360` 退单栏，等宽取左 |
| `700f` 竖向铰链 `260\|440` | `HingeFallbackSinglePane` | `700` | `0 \| 700 \| 0` | `700 == 700` | `[]`，锚点 `(260, 440)` | `leftW < 280` 退单栏，放右宽侧 |
| `559f` 竖向铰链 `279.5\|279.5` | `HingeFallbackSinglePane` | `559` | `0 \| 559 \| 0` | `559 == 559` | `[]`，锚点 `(0, 559)` | 两侧 `< 280` 整窗居中 |

- **单测核实（`ShellNavigationModeTest.kt:15-230`）**：已覆盖 `340、411、559、599、600、640、679、680、701、744、748.59f、749、800、839、840、851.69f、852、860、883、900、933、1024、1100、1280dp`、矮屏与上述 4 类铰链场景。
- **微小报告出入（非阻断，见 S4）**：实现报告 `_reports/C4c.md:68` 称单测覆盖了 `599.6f`/`679.6f`，核对 `ShellNavigationModeTest.kt:83-105` 实际仅在三栏用例传了 `748.59f`/`851.69f`，未在 `calculateShellPaneLayout` 中传临界点下沿浮点（`599.6f`/`679.6f`/`839.6f`）及非零宽度折痕。

---

## 二、硬边界与零冲突核查

对 `git diff 7cf0e70...6706b09869ece5329c12738c2782bf303f305b29` 全量 46 个变更文件逐一核查：

1. **`HiGlassSurface.kt` 零视觉参数改动**：
   - `HiGlassTokens`（模糊半径、着色不透明度、饱和度、噪点、降级不透明度、高光渐变、轮廓线、阴影高度）及 `HiGlassStyle` / `LocalHiGlassStyle` / `toHazeConfig()` / `toHazeBlurStyle()` **0 行改动**；
   - 仅删除旧 `HiNavigationRail` 专用的 `HiGlassBorderMode`（`AllSides` / `EndEdgeOnly`）枚举、无调用方的 `borderMode` 参数与 `EndEdgeOnly` 绘制分支，并将 L284 KDoc 中“主导航（底部胶囊、导航轨）”更新为“底部导航胶囊”。
2. **禁止触碰范围零改动**：
   - 共享构建与入口文件（`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）：**0 改动**；
   - `MotionTokenScanTest.kt`（白名单保持 `emptyList()`）、`HiNavigationPill.kt`、`HiSnackbarHost.kt`、`feature/`：**0 改动**；
   - 跨线修改严格限于卡片唯一授权的 `AppSettings.kt`（+2 行）、`SettingsRepository.kt`（+2 行）、`DataStoreSettingsRepository.kt`（+12 行）与 `DataStoreSettingsRepositoryTest.kt`（+17 行）；因复用既有明文 `settings` DataStore 而非新增加密存储，`BackupExclusionRulesTest`（6/6）全绿。
3. **截图基线隔离与旧 `Rail` 清理**：
   - `git diff --name-status 7cf0e70...6706b09 -- hipda/src/test/snapshots/` 仅含 25 个 `shell_*.png`（12 新增、9 更新、4 删除退役的 `shell_w700_*` / `shell_w900_*`），`glass_*`、`theme_*`、`state_*`、`feedback_*`、`challenge_*`、`sample_export_*` **0 改动**；
   - `HiNavigationRail.kt` 已删除（`D`），`ShellIndicatorLayout.kt` 已移除全部 `Rail` 分支与 `Rail*` 常量；`git grep "Rail" 6706b09 -- hipda/src/main/java/net/jejer/hipda/ui/` 输出为空。

---

## 三、标准组件原则、代码纯洁性与动效合规

1. **标准组件复用**：
   - 新增 `HiNavigationSidebar.kt` 与更新后的 `HiAdaptiveShell.kt` 完全使用标准 `Row`、`Column`、`Box`、`LazyColumn`、`Surface`、`HorizontalDivider`、`TextButton`、`TooltipBox`、`PlainTooltip`、`AnimatedVisibility`、`AnimatedContent`、`Modifier.draggable`、`BackHandler`；**零新增自定义 `Layout`、零自定义 `Modifier.Node`、零手写状态机/命令队列**。
   - 包内唯一 `Layout` 为既有 `ShellIndicatorLayout.kt:69`（C4a 底部胶囊单遍横向布局，本卡删去纵向 `Rail` 分支后由 292 行减至 276 行）。
2. **`src/main/` 纯洁性**：
   - 扫描 `SemanticsPropertyKey`、`VisibleForTesting`、`TestOnly`、`CompositionLocal` 在 `ui/shell/` 下均为 0 命中；仅包含常规 `Modifier.testTag(HiShellTestTags.*)`，且 `HiGlassSurfaceTest.mainSources_doNotReferenceDebugGlassTuningOrPreviewSymbols`（9/9）通过。
3. **动效令牌与毛玻璃约束**：
   - 全部动画参数均来自 `HiMotion` / `HiMotionTransitions`：常驻侧栏宽度 `HiMotion.spatialSlow()` / `exit(spatialSlow())`（`HiAdaptiveShell.kt:207-215`），模态浮层 `hiSlideInHorizontally(HiMotion.spatialDefault()) + hiFadeIn(HiMotion.effectsDefault())` 与对应 `exit`（`HiNavigationSidebar.kt:318-324`），未达阈值回弹 `HiMotion.spatialSnap()`（`L281`），顶栏滚变色 `HiMotion.effectsDefault()`（`L393, 398, 403`），`我的` 箭头 `HiMotion.spatialFast()`（`L751`）与子项展开 `hiExpandVertically(HiMotion.spatialDefault())`（`L791`），未读角标缩放 `HiMotion.spatialFast()` + `HiRollingNumber`（`L1140-1163`）；`MotionTokenScanTest`（7/7）全绿。
   - `≥ 600dp` 侧栏态不挂载 `hiGlassSource` 与 `HiNavigationPill`（`HiAdaptiveShell.kt:253, 285`），外壳毛玻璃数经 `HiAdaptiveShellTest` 与 `HiShellScreenshotTest` 实测恒为 `0`，内容底部 `contentPadding` 仅保留 `navigationBars` inset（`HiAdaptiveShell.kt:219`）；直观核验 `shell_scroll_w749_h559_sidebar_bottom_light.png` 确认第 60 条完整露出、底部无胶囊遮挡。

---

## 四、侧栏交互、无障碍、文案与持久化核查

1. **首屏可见性与 `我的` 分组高度自适应**：
   - `HiAdaptiveShellTest.sidebarCollapsed_fold8Landscape749x559_showsAtLeastThreeForumsWithoutScrolling`（`L790-848`）注入 `24dp + 24dp` 系统栏 inset 后实测 `Discovery (2)`、`Buy & Sell (6)`、`Geek Talks (7)` 全部完整落在 `SidebarList` 与窗口可视边界内；直观核验 `shell_w749_h559_light.png` 与 `shell_w749_h559_drawer_light.png` 确认首屏完整显示 3 个默认版块。
   - `mineGroupExpanded = mineGroupOverride ?: (heightDp >= 760f)`（`HiAdaptiveShell.kt:135`）：`< 760dp` 默认收起、`≥ 760dp` 默认展开（见 `shell_w840_h900_step5_font130_light.png`）；在 `64dp` 收起轨点击 `我的`（`handleCollapsedMineClick`，`L169-176`）一步将 `mineGroupOverride = true` 并弹出 `240dp` 浮层（`600–839dp`）或原地展开常驻左栏（`≥ 840dp`）。
2. **`240dp` 模态浮层触发与关闭**：
   - 无任何屏幕左边缘手势检测器（仅在已展开的浮层与暗罩上挂水平 `draggable` 收回，不与系统左缘预测性返回冲突），左缘右滑不打开已由 `HiAdaptiveShellTest:882-889` 验证；
   - 支持点暗罩、`收起侧栏`、任一导航条目、系统返回（`BackHandler`）、外接键盘 `Key.Escape` 关闭；从浮层点击 `管理版块` 或账号行时均执行 `{ onDismiss(); onManageForumsClick()/onAccountClick() }`（`HiNavigationSidebar.kt:352-354`），先同步收起浮层避免双层模态叠放。
   - **无障碍与键盘锁焦细查**：底层容器在 `isModalDrawerOpen` 时挂 `Modifier.clearAndSetSemantics { hideFromAccessibility() }`（`HiAdaptiveShell.kt:232`），TalkBack 合并语义树完全隐藏底层内容；浮层打开时自动 `drawerFocusRequester.requestFocus()`。**但** `HiAdaptiveShell.kt:226-229` 的 `.focusProperties { canFocus = !isModalDrawerOpen; onEnter = { if (isModalDrawerOpen) cancelFocusChange() } }` 挂在无 `.focusGroup()` 的普通 `Box` 上（缺少消费该属性的 `FocusTargetNode`），外接蓝牙键盘持续按 `Tab` 仍可越出浮层落入底层可聚焦节点（详见建议项 **S1**）。
3. **`64dp` 轨语义、触控目标、`1.25×` 钳制与 21 版块元数据**：
   - `SidebarNavigationRow`（`L676, 704`）与 `SidebarCollapsedSlot`（`L1080`）将完整名称与未读数合并进 `contentDescription`（如 `短消息，3 条未读`、`提醒，120 条未读`，角标 `> 99` 显示 `99+`），并配 `TooltipBox`；
   - 侧栏与内容容器分别声明 `isTraversalGroup = true` 与 `paneTitle`（`导航` / `内容`，`PreviewActivity` 中右两栏亦声明 `列表` / `正文`）；
   - 所有交互槽位最小尺寸 `48×48dp`（展开态胶囊高 `44dp` 外包 `48dp` 槽位），`HiCappedFontScale` 将侧栏字体缩放钳制在 `1.25×`（`navigationSidebar_clampsFontScaleAt125PercentWhenSystemScaleIs200` 与 `shell_w840_h900_step5_font130_light.png` 核验无换行无裁切）；
   - `ForumMetadata.kt` 完整收录 `HiUtils.java:84-106` 全部 21 个内置版块（`fid` 与名称逐一核对一致，21 个矢量图标互不重复），未知 `fid` 或多版块同图标碰撞时由 `resolveCollapsedForumDisplays` 自动回退 `28×28dp` `MonogramBadge`。
4. **文案与术语表核查**：
   - `strings_shell.xml` 新增字符串逐字对齐 `docs/design/copy.md`（v0.3），不含 `product-design.md` §8 右栏任何禁用词组（`stringsShellXml_containsNoForbiddenTerminologyPhrases` 通过）。
5. **跨形态状态保持与 DataStore 持久化**：
   - `AppSettings` / `SettingsRepository` / `DataStoreSettingsRepository` 新增 `sidebarCollapsedPortrait` / `sidebarCollapsedLandscape`（默认 `false`，`safeBoolean` 类型安全读取，错误类型写入时回退 `false` 不抛 `ClassCastException`，`DataStoreSettingsRepositoryTest` 14/14 覆盖默认值、读写、跨实例恢复与损坏回退）；
   - `ShellSidebarEntry ↔ ShellDestination` 双向映射纯函数完全确定性，窄→宽停在“我的”总菜单时优先恢复 `lastVisitedMineEntry`、未访问默认 `FavoritesAndAttention`；
   - `HiAdaptiveShell.kt:137-141` 在跨越 `840dp`（进入常驻可切换态）、退回 `< 600dp` 胶囊态或进入全屏态时自动复位 `isModalDrawerOpen = false`，旋转/折叠无悬空暗罩、无状态丢失或崩溃。

---

## 五、复杂度、性能与测试变异质量分析

### 5.1 代码规模与性能评估
- **规模合理性**：新增/重构生产代码主要集中在 `ShellNavigationMode.kt`（455 行）、`ForumMetadata.kt`（367 行，其中约 215 行为替代旧 FontAwesome 且避免引入庞大 `material-icons-extended` 的 14 个声明式 `ImageVector` 路径）、`HiNavigationSidebar.kt`（1167 行，承载收起轨、常驻展开栏、240dp 模态浮层、滚动顶栏、`我的` 折叠组、版块列表/空态、Tooltip 与滚动数字角标）、`HiAdaptiveShell.kt`（372 行）。全为直观扁平的标准组件拆分，无过度抽象。
- **滚动与切换重组控制**：
  - `SidebarColumnBody` 中 `isScrolled` 与 `canScrollDown` 均以 `remember(listState) { derivedStateOf { ... } }` 包裹（`HiNavigationSidebar.kt:385-390`），列表滚动过程不会在每像素触发重组；
  - 分隔线透明度（`L434, 584`）、浮层拖拽位移（`L331`）与底部胶囊滚隐位移（`HiAdaptiveShell.kt:328-330`）均在 `Modifier.graphicsLayer` 阶段读取状态，零布局重排；
  - 胶囊切换未选中项零重组由 `RecompositionCounter` 守住。

### 5.2 “改坏关键一行测试仍通过”的具体盲区（共 3 例）

1. **例 1（键盘 `Tab` 锁焦失效未被捕获）**：删除 `HiAdaptiveShell.kt:226-229` 的 `.focusProperties { canFocus = !isModalDrawerOpen; onEnter = { if (isModalDrawerOpen) cancelFocusChange() } }`，全部 29 个 `HiAdaptiveShellTest` 依然通过——一方面当前代码在该 `Box` 上漏挂了 `.focusGroup()` 使 `FocusProperties` 未绑定到 `FocusTargetNode`，另一方面测试只断言了 `clearAndSetSemantics` 语义隐藏与暗罩点击拦截，未注入 `Key.Tab` 验证外接键盘焦点不逃逸。
2. **例 2（`240dp` 浮层内区左滑关闭手势完全删除仍通过，且存在松手首帧回跳）**：删除 `HiNavigationSidebar.kt:308, 335` 的 `.modalDismissDrag(dragState, onDragStopped)`，全部测试依然通过；且因缺乏逐帧手势测试，掩盖了 `HiNavigationSidebar.kt:265-268` 中 `LaunchedEffect(visible) { dragOffsetPx.snapTo(0f) }` 在左滑松手触发 `onDismiss()`（`visible = false`）首帧将 `dragOffsetPx` 瞬间清零回跳到 `0f`、随后才开始播放 `AnimatedVisibility` 滑出退场动画的视觉瑕疵。
3. **例 3（临界浮点先取整再分档 / 非零铰链右边界写错仍通过）**：若将 `ShellNavigationMode.kt:354, 370` 误改为用取整后的 `w < 680` / `w >= 840` 判断分档（违反先浮点分档再 `roundToInt` 规则），或将 `L292, 294` 的 `hingeBoundsDp.right` 误写为 `hingeBoundsDp.left`，`ShellNavigationModeTest` 全部 9 个测试仍会通过——因为三栏浮点用例只测了远离断点的 `748.59f` / `851.69f`（未测 `599.6f`、`679.6f`、`839.6f`），且所有铰链用例均为 `left == right`（0dp 宽折痕）。

---

## 六、阻断项与改进建议汇总

### 阻断项
- **无**。

### 建议项（非阻断，供后续 `E3` 或收尾轮采纳）
- **S1（补全 `240dp` 浮层外接键盘 `Tab` 焦点闭环）**：在 [HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next.wt/C4c/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L225-L247) 的 `underlyingInteractionGuard`（`.focusProperties` 之后）补上 `.focusGroup()` 使其生成 `FocusTargetNode` 拦截进入焦点，或在 [HiNavigationSidebar.kt](file:///home/mir/Documents/hipda-next.wt/C4c/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L332-L336) 浮层容器上约束 `Tab` 焦点不越出浮层，并在 `HiAdaptiveShellTest` 补 `Key.Tab` 断言。
- **S2（修复 `240dp` 浮层左滑关闭松手首帧回跳并补手势测试）**：将 [HiNavigationSidebar.kt](file:///home/mir/Documents/hipda-next.wt/C4c/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L265-L268) 的 `dragOffsetPx.snapTo(0f)` 移入 `if (visible)` 分支内（关闭退场期间保留松手时的负向位移避免跳回 `x = 0`），并在 `HiAdaptiveShellTest` 补左滑过阈值关闭与未过阈值 `spatialSnap` 回弹断言。
- **S3（避免常驻侧栏伸缩过渡期双 `LazyColumn` 共享同一 `LazyListState`）**：[HiNavigationSidebar.kt](file:///home/mir/Documents/hipda-next.wt/C4c/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L209-L232) 的 `AnimatedContent(targetState = expanded)` 在交叉淡入淡出帧内同时挂载展开与收起两个 `SidebarColumnBody` 并共用单一 `listState`（互相覆盖 `remeasurement` / `layoutInfoState`）；建议改为单一 `SidebarColumnBody` 内仅对文字做淡入淡出，或按 `isExpanded` 独立持有 `LazyListState` 并同步首项滚动位。
- **S4（补强临界点下沿浮点宽与有宽度物理铰链单测）**：在 [ShellNavigationModeTest.kt](file:///home/mir/Documents/hipda-next.wt/C4c/hipda/src/test/java/net/jejer/hipda/ui/shell/ShellNavigationModeTest.kt#L83-L230) 补入 `599.6f`、`679.6f`、`839.6f` 临界浮点宽与 `12dp` 非零折痕（如 `ShellHingeBoundsDp(368f, 380f)`）断言，同时修正 `_reports/C4c.md:68` 的描述偏差。
