# 验证报告 V-E4：帖子阅读底部工具条、内联跳页与位置恢复闭环（E4 & main 合并集成）

## 1. 总结论

- **总体判定**：**FAIL**（`E4 @ 6526c1b` 独立预合入全部 12 项探针 `(a)–(c)` + `RC5b`、既有回归、新增/重录快照逐张视觉审阅及负责人核查追加 1–5 **全部 PASS**；但在 `verify/E4-probes` 合入 `origin/main`（`1dc594e`，含 `E-slots`/`E8`/`E10`/`E12`）后的集成探针 `(d)` 中，`probeD2` 复现了**模态侧栏抽屉浮层 `HiNavigationSidebarModalOverlay` 与 E4 `PositionStack` 的系统返回拦截 LIFO 抢占缺陷**）。
- **验证对象**：`origin/card/E4-thread-chrome-and-position` @ `6526c1b677d934b2381aec94defc398ff3a26887`（相对基线 `70da638`，仅只读审阅未修改/未推送实现分支）
- **合并验证基线与分支**：
  - 独立工作区：`/home/mir/Documents/hipda-next.wt/V-E4`（分支 `verify/E4-probes`，原仓 `/home/mir/Documents/hipda-next` 零改动）
  - 合入前探针提交：`61e3801`
  - 合并 `origin/main`（`1dc594e`，零冲突）：`6c5f243`
  - 合入后含 `(d)(e)` 最终推送提交：`ac01341d22a73bac18aee173a769734ce4d6f45d`（仅包含合并节点与新增探针文件 [E4ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/verify/E4ProbeTest.kt)，生产代码、原单测与快照基线 **0 改动**）
- **CI 验证链接**：[Android CI #45750510340](https://github.com/mailwmj/hipda-next/actions/runs/45750510340)（触发自 `verify/E4-probes` @ `ac01341d22a73bac18aee173a769734ce4d6f45d`）
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`：总计 `1380 tests, 1379 passed, 1 failed, 0 errors, 0 skipped`（`:core-parser:test` `100/100 PASS`，`:hipda:testDebugUnitTest` `1279/1280 PASS`；`Check for Roborazzi diff images ✓`，**0** 截图差异；唯一失败用例为预期复现集成缺陷的 `E4ProbeTest.probeD2_mergedModalDrawer_andSettingsSubPageOverlays_doNotConsumePositionStackOrRehydrate`）。

---

## 2. 视觉逐张复验与快照零漂移核查

### 2.1 `git diff --name-status 70da638...6526c1b` 快照范围与像素边界检查

仅涉及 **37 张** 截图文件，跨区（`forum_*`、`login_*`、`shell_*`、`thread_image_*`、`theme_specimen_*`、`glass_specimen_*`、`challenge_*`）**0 漂移**；通过 Pillow 逐像素 RGB `ImageChops.difference` 验证非零变化区域全部严格局限在底部/右栏底部工具条区域：

| 分组 | 变更文件数 | 未变文件 | 像素变化区域边界实测 |
| --- | --- | --- | --- |
| 新增 `thread_chrome_*.png` | 10（`A`） | — | 全新覆盖 411/749/1280 × 浅/深、340+1.25×、360 右栏+1.25×、双/单行跳页条 |
| 授权更新 `multipane_*.png` | 13（`M`） | `multipane_w749_h559_empty_detail_light.png`、`multipane_hinge_fallback_w701_h841_light.png` | 全 13 张变化严格局限 `y >= 89.2%`；三栏/铰链均仅右栏 `x >= 44.3%..60.1%`（仅单栏无胶囊/全屏右栏图横向 `0..100%`） |
| 授权重录 `thread_detail_*.png` | 9（`M`） | 6 张骨架屏/错误/空态无变化 | 全 9 张变化严格局限 `y >= 90.0%`（移除旧 `ThreadDetailPaginationBar` 并替换为真实底栏留白） |
| 授权更新 `thread_interact_*.png` | 5（`M`） | `thread_interact_menu_w411_light.png` | 全 5 张变化严格局限 `y >= 91.9%` |

### 2.2 逐张读图核对结论

- **全部 10 张 `thread_chrome_*.png`**：
  - `toolbar_w411_light/dark`、`toolbar_w749_light/dark`、`toolbar_w1280_light/dark`：三段胶囊对齐、玻璃描边柔和、进度填充与数字对比度清晰；
  - `compact_w340_scale125_light`、`compact_rightpane_w360_scale125_dark`：左回复胶囊自动退化图标紧凑态，中间胶囊显示 `12/45` 无换行截断、各控件互不挤压；
  - `jumpbar_compact_w411_light`（< 480dp 清晰双行，滑块 + 输入框 + 确认/关闭无溢出）与 `jumpbar_wide_w749_dark`（≥ 480dp 单行排布平衡）。
- **9 张重录 `thread_detail_*.png` & 抽检 `multipane_*` / `thread_interact_*`**：
  - 彻底移除旧版 `ThreadDetailPaginationBar`，底部列表留白干净，OLED 纯黑/深色/浅色下进度文字清晰；三栏右栏底部工具条居中于右栏内部、零跨分隔线；铰链双栏未越过物理铰链缝。

---

## 3. 负责人追加核查 1–5

| 核查项 | 证据 | 结论 |
| --- | --- | --- |
| **#1 `R-E2c S1-R2` `cancelPagingJobs()` 与回退必红单测** | [ThreadDetailViewModel.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L544-L556) 本页 `jumpToFloor` 递增 `sessionGeneration` 前同步调用 `cancelPagingJobs()` 并复位 `isPrepending`/`isAppending`；[ReadingPositionAndStackTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionAndStackTest.kt#L305-L358) 与独立探针 `E4ProbeTest.probeA3_*` 均先构造上下预取在途再本页跳楼并验证后续连续加载仍可正常合入。 | **PASS** |
| **#2 既有测试改动核对（无放宽/无删断言）** | 见第 4 节逐文件 diff 清单：`MultiPaneBackStackTest.kt` `0` diff（`7/7` PASS）；玻璃上限 `≤3 / ≤2 / ≤5` 未放宽（`hingeTwoPane` 补齐 `drawBackdrop <= 5`）；`ContinuousPagingAnchorTest` 锚点 `≤1dp` 未放宽；`FloorContextMenuTest` 限定 `PaneTitle` 查找菜单项且无断言被删。 | **PASS** |
| **#3 `PositionStack` 优先级先于 `E3` 返回栈** | [ThreadDetailPane.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L411-L415) 注册 `BackHandler(enabled = uiState.canPopPositionStack)`；`probeA1`、`probeA2`、`probeD1`、`probeD3` 实测栈非空优先回退原楼层、栈空才交还 `E3` 退右栏或退出页面，`MultiPaneBackStackTest` 全绿。 | **PASS** |
| **#4 滚动写入点唯一性** | `rg` 全搜 `scrollToItem\|animateScrollToItem\|requestScrollToItem\|dispatchRawDelta`：`feature/threaddetail/` 下仅出现在 [ThreadDetailPane.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L170-L360) `PrependAnchorController`（`L170, 208, 244, 256, 272, 330, 337, 341, 360`）与未改动的 `media/PostImageBlock.kt:246`。 | **PASS** |
| **#5 `E4` 未碰禁改目录** | `git diff --name-status 70da638...6526c1b` 生产改动仅限 `feature/threaddetail/*`、`ui/designsystem/feedback/{HiPressFeedback,HiRollingNumber}.kt`、`strings_thread_detail.xml`。 | **PASS** |

---

## 4. 既有测试改动逐条核对清单（`70da638...6526c1b`）

1. [MultiPaneBackStackTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneBackStackTest.kt)：**0 diff**（7/7 通过）。
2. [MultiPaneGlassBudgetTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneGlassBudgetTest.kt)：`L79-81`、`L124-142`、`L174-200` 移除模拟 `HiGlassRegion` 占位，改为挂载真实 `ThreadDetailPane`；全屏/三栏/铰链 `HiGlassMarker` 按真实组件计数更新（`2->3` / `1->2`），全局预算 `HiGlassTokens.MaxGlassSurfacesOnScreen`（`≤3`）、`layerBackdropCount <= 2`、`nestedLayerBackdropCount == 0`、`drawBackdrop <= 5` 无任何放宽，且在 `hingeTwoPane` 补增 `forumAudit.drawBackdropCount <= 5` 硬断言。
3. [ThreadDetailScreenshotTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ThreadDetailScreenshotTest.kt)：`L213` `assertGlassSurfaceCountAtMost(1) -> (2)`（顶栏 1 + 真实底栏 `HiGlassRegion` 1）。
4. [ThreadInteractScreenshotTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/interact/ThreadInteractScreenshotTest.kt)：`L168`、`L196`、`L246` `assertGlassSurfaceCountAtMost(1) -> (2)` 并加注释说明，无其它断言放宽。
5. [FloorContextMenuTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/interact/FloorContextMenuTest.kt)：`L127-169` 限定菜单 `PaneTitle`（`"第 1 楼 · 本人账号"` / `"第 2 楼 · 路人坛友"`）匹配菜单项避免与底部工具条“回复”胶囊重名；`L236` `onNodeWithText("下一页")` 改为 `vm.nextPage()`，无断言被删。
6. [ThreadDetailViewModelTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModelTest.kt)：`L148` 与 `L290` 改为 `vm.nextPage()`；`L238-278` `topBarGlassTopology` 按顶栏+三段胶囊真实结构更新为 `2` 个 `HiGlassMarker`、`4` 个 `HiGlassBlurActive` 与 `DrawBackdrop`，仍逐一断言每个 `HiGlassMarker` 与 `LazyColumn` 严格兄弟零嵌套且 `LayerBackdrop == 1`；`L289-309` 改用底部进度胶囊描述与展开“首页”，保留退出只看 `36px` 像素偏移断言。
7. [ContinuousPagingAnchorTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt)：`L735-792` `paginationBarPageIndicator_*` 改查底部进度胶囊描述与展开后“首页”/“末页”启用态；`L1034` 与 `L1152` 改由 `vm.nextPage()` 触发，所有 `≤ 1dp` 帧锚点漂移断言原封不动保留。

---

## 5. 独立探针执行明细（[E4ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/verify/E4ProbeTest.kt)）

### 5.1 合入前（`6526c1b` + `61e3801`）本地结果：`40/40 PASS`

- `E4ProbeTest`：**12/12 PASS**（`probeA1`, `probeA2`, `probeA3`, `probeB1`, `probeB1b`, `probeB2`, `probeC1`, `probeC2`, `probeC2b`, `probeC3`, `probeC4`, `probeRC5b`）。
- 回归与截图基线（同 `6526c1b` 本地复验）：`ContinuousPagingAnchorTest` `15/15`、`ThreadDetailViewModelTest` `8/8`、`FloorContextMenuTest` `5/5`、`MultiPaneBackStackTest` `7/7`、`MultiPaneGlassBudgetTest` `8/8`、`MultiPaneScreenshotTest` `21/21`、`ThreadDetailScreenshotTest` `15/15`、`ThreadChromeScreenshotTest` `10/10`、`ThreadInteractScreenshotTest` `6/6`（全绿，Roborazzi `verify=true` 0 差异）。

### 5.2 合入 `origin/main` 后（`6c5f243` -> `ac01341`）结果

本地执行与 [Android CI #45750510340](https://github.com/mailwmj/hipda-next/actions/runs/45750510340) 结果一致：

| 探针编号 | 覆盖目标 | 结果 | 关键实测断言 |
| --- | --- | --- | --- |
| **probeA1** | 慢加载恢复门闩、`save` 防冲写、去抖落盘、`PositionStack` 出栈 | **PASS** | `CompletableDeferred` 挂起 `loadPage(5)` 含滚动与提前退出期间 `saveCalls == 0`；放行精确恢复 `floor:p503 @ 120px`（`<=4px`）且 `saveCalls == 0`；滚动 `p506 @ 48px` 在 200ms 未落盘、400ms 去抖后准确写库；跳“首页”后首次系统返回先出栈回第 5 页 `p506`，栈空不再持有 `BackHandler` 且顶栏返回离开。 |
| **probeA2** | 折叠屏三栏 `PositionStack` 优先于 `E3` 多栏返回栈 | **PASS** | 跳首页后第 1 次系统返回回退第 5 页，第 2 次由 `E3` 关闭右栏回 `"选一篇帖子开始阅读"`。 |
| **probeA3** | `R-E2c S1-R2` 上下预取在途本页跳楼取消并恢复连续加载 | **PASS** | `loadPreviousPage` + `loadNextPage` 并发挂起时触发 `jumpToFloor("p208")` 立即复位 `isPrepending`/`isAppending == false`；放行旧门闩不脏改 `[2..2]`；后续 `loadNextPage`/`loadPreviousPage` 顺利扩为 `[1..3]`。 |
| **probeB1 / probeB1b** | `suppressBottomGlass` 整块卸载 `HiGlassRegion` 与释放 `hasSourceDemand` | **PASS** | 单独挂载：`false` 仅 1 `HiGlassMarker` / 3 `DrawBackdrop` / `hasSourceDemand == true`；切 `true` 后 `HiGlassMarker == 0` 且 `hasSourceDemand == false`。全页 `ThreadDetailPane` 切换 `2 -> 1 -> 2` 无异常。 |
| **probeB2** | 真实 `E1` + `E3` + `E4` 三栏满配玻璃预算与右栏居中 | **PASS** | `EXPANDED_933_696` 下全屏 `HiGlassMarker == 3`、`LayerBackdrop <= 2`、`nestedLayerBackdrop == 0`、`drawBackdrop <= 5`；工具条进度胶囊左右边界严格落入右栏 `"正文"` Pane 之内。 |
| **probeC1** | `340dp + 1.25×` 内联跳页尺寸/滑块/IME `0dp` 滚底/自动收起 | **PASS** | 全控件 `≥ 48dp` 且双行无重叠不越出 `340dp`；滑块轨内向右拖动仅更新预览态与“跳转”图标、`loadPageCalls == 0`、底层列表索引与像素偏移未变；注入与撤除 `260dp` IME insets 时 `LazyColumn` 首可见项索引及像素偏移变化 `== 0dp`；跳转/回车跳转均自动收起；首末页禁用对应快捷按钮。 |
| **probeC2 / probeC2b** | 展开中途反向关闭逐帧连续性 & 减动效 1 帧到位 | **PASS** | `assertFrameContinuity` 中途第 4 帧反向往返位移连续（`≥ 12dp`）；`animator_duration_scale = 0f` 下 1 帧展开/收起且次帧位置不变。 |
| **probeC3** | `V-C4d` 全 20 套主题预设 × Light/Dark/OLED WCAG 对比度 | **PASS** | 60 组组合在 `ActiveGlass`、`ActiveProgressFill` 及 `DegradedProgress`（含 `#000000`/`#FFFFFF` 背景）均满足对比度 `≥ 4.5:1` 且填充 alpha 未超上限。 |
| **probeC4** | 外链白名单与离线 `MockWebServer` 收藏/关注/撤销闭环 | **PASS** | 拦截 `javascript:`/`JAVASCRIPT:`/`intent:`/`file:`/`content:`/`data:`/`mailto:`/`tel:`/`vbscript:`、含控制符与空 host URL（`LocalUriHandler` 与系统 `Intent` `0` 触发）；放行 `https://`；`MockWebServer` 实测收藏→“已收藏”→“撤销”与关注→“已关注”→“撤销”端到端闭环。 |
| **probeRC5b** | `HiRollingNumber` 数字更新与 `HiPressScale` detach 清理 | **PASS** | 位数从 `9` 增至 `10` 与宿主卸载均安全无异常。 |
| **probeD1** | `(d)` 合并三栏 `E-slots` 看图浮层、`E12` 账号管理浮层、`E12/E8` 资料→短消息下钻 | **PASS** | 看图浮层打开期间不误卸载、`getReadingPositionCount == 1`，返回先关看图浮层；打开并关闭 `AccountManagementOverlay` 不影响 `PositionStack`（仍第 1 页且非空）；点击楼主进入 `UserProfile -> SmsConversation` 下钻再逐级返回，不触发二次水合（`count == 1`）；上层全退后下次返回才由 `PositionStack` 回第 5 页、再返回交还 `E3`。 |
| **probeD2** | `(d)` 合并单栏折叠抽屉态（`600dp` `SidebarModalDrawer`）覆盖 `PositionStack` 帖子 | **FAIL** | **触发路径详见第 6 节**；按下系统返回未关闭 `SidebarModalDrawer`，底层 `detailVm.uiState.value.page` 由 `1` 退栈回 `4`。 |
| **probeD3** | `(d)` 单栏设置子页（管理版块）、`关于 -> 客户端发布帖 (1579403)` 叠加 `AccountMenu` 与切换消息 | **PASS** | `Settings -> 论坛与发帖 -> 管理版块` 返回逐级正常；进释放帖首次恢复第 4 页 `count == 1`，入栈后叠 `AccountMenu` 返回仅关浮层，再退先弹栈回第 4 页再返回回“关于”（`count` 维持 `1`），切换“消息”展示“消息探针用户”。 |
| **probeE** | `(e)` M6 入口（搜索结果、搜索直达链接、收藏列表、消息提醒深链/回复贴）进入与返回 | **PASS** | 搜索结果 `9101`（`4/p406@90px`）、搜索站内链接 `9102`（`3/p304@75px`）、收藏 `9201`（`6/p608@110px`）、提醒回复 `9301`（`7/p704@80px`）进入时 `getReadingPosition` 严格仅调用 **1** 次、精确定位目标楼层并隐藏首页 `p*01`，返回后 `count` 不二次增长；提醒引用 `targetPid = p205`（`9302`）直达第 2 页 `p205`、`getReadingPosition == 0` 且不误写 `saveCalls == 0`。 |

---

## 6. 阻断性集成缺陷与硬化建议

### 6.1 【FAIL 阻断】模态侧栏抽屉浮层（`HiNavigationSidebarModalOverlay`）被 E4 `PositionStack` `BackHandler` 抢占

- **复现方法**：`./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.E4ProbeTest.probeD2_mergedModalDrawer_andSettingsSubPageOverlays_doNotConsumePositionStackOrRehydrate"`（已在 CI [#45750510340](https://github.com/mailwmj/hipda-next/actions/runs/45750510340) 与本地同时稳定复现）。
- **现象与日志**：在 `@Config(qualifiers = "w600dp-h800dp")` 下先打开帖子 `1001` 并跳页至第 1 页使 `canPopPositionStack == true`，再点击左上角 `HiShellTestTags.SidebarToggleButton` 展开覆盖全屏的 `SidebarModalDrawer`，此时按下系统返回：
  `java.lang.AssertionError: 关闭导航抽屉不得出栈 E4 PositionStack（抽屉未拦截导致底层退栈） expected:<1> but was:<4>`（抽屉依然停留在 `(0, 0, 240, 800)` 打开状态，底层线程详情却已在无感知状态下退出位置栈跳回第 4 页）。
- **根因定位**：
  1. [HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L436-L447) 在应用启动时即常驻挂载 `HiNavigationSidebarModalOverlay`，而 [HiNavigationSidebar.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L283) 使用 `BackHandler(enabled = visible, onBack = onDismiss)`；Compose `BackHandler` 仅在首次进入组合（`t = 0`）时向 `OnBackPressedDispatcher` 注册回调，`enabled` 从 `false -> true` 时**不会**重新压到派发栈顶。
  2. 之后用户打开帖子（`t = 1`），[ThreadDetailPane.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L413) 挂载的 `BackHandler(enabled = uiState.canPopPositionStack)` 位于 `OnBackPressedDispatcher` LIFO 栈更靠后位置，因此优先于常驻抽屉的 `BackHandler` 消费系统返回。
- **同构隐患提示**：[NextAppRoot.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L275-L276) 中为 `E14` 看图退场动画准备的兜底 `BackHandler(enabled = isImageViewerOpen) { activeImageViewerArgs = null }` 同样常驻在 `t = 0`，一旦 `imageViewerRenderer.Overlay` 在内部退场动画期间卸载自身 `BackHandler`，后挂载的 `ThreadDetailPane.kt:413` 亦会先于该兜底拦截消费返回键。
- **修复方向建议**：在 `M6-polish` 或合入前收口阶段，将 `HiNavigationSidebarModalOverlay`（`HiNavigationSidebar.kt:283`）与 `NextAppRoot.kt:276` 的模态/兜底拦截改为条件挂载 `if (visible) BackHandler(...)` / `if (isImageViewerOpen) BackHandler(...)`（或随抽屉开启 `key(visible)` 挂载），确保每次浮层开启时回调位于 `OnBackPressedDispatcher` LIFO 栈顶。

### 6.2 【非阻断硬化建议】`sanitizeExternalHttpUrl` 的 `@` / `userinfo` 校验边界

- 在 [ThreadBottomToolbar.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt#L115-L116)，`sanitizeExternalHttpUrl` 仅检查 `host.contains('@')`；但 Java `URI("https://user@www.4d4y.com/forum/viewthread.php?tid=1")` 会将 `user` 拆入 `uri.rawUserInfo`，导致 `uri.host` 为 `"www.4d4y.com"` 而放行带 userinfo 的链接。虽然当前 `forumServer` 来自用户配置且危险 scheme 均已 100% 拦截，仍建议同步比照 E10 [ForumLinkGuard.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/search/ForumLinkGuard.kt#L13) 增加 `!uri.rawUserInfo.isNullOrEmpty()` 拒绝判断。

---

## 7. 工作区与产物保留状态

- **验证分支**：`verify/E4-probes` @ `ac01341d22a73bac18aee173a769734ce4d6f45d`（已推至 `origin/verify/E4-probes`，按要求保留）
- **验证 Worktree**：`/home/mir/Documents/hipda-next.wt/V-E4`（工作区干净，按要求保留；已执行 `./gradlew --stop` 无残留 Gradle Daemon）


---

## 8. 第 2 轮复验结论（`bed7fcc` + `main@3617fad` 含 `E14@1cffcd9`）

### 8.1 总结论与验证基线

- **第 2 轮总体判定**：**PASS**（第 1 轮唯一阻断 `probeD2`、`rawUserInfo` 加固、`a–e + RC5b` 全部回归及新增集成探针 `(f)` `(g)` 共 **20 / 20** 项全部 **PASS**）。
- **验证对象**：
  - E4 修复提交：`origin/card/E4-thread-chrome-and-position` @ `bed7fcc3df489b945d1f1316dfedc18a5078f653`（关联修复 CI [#45753025942](https://github.com/mailwmj/hipda-next/actions/runs/45753025942)）
  - 集成主干基线：`origin/main` @ `3617fadcdab928bdd979ed41ae416c7d5e0a06fc`（已合入 `1cffcd9` E14 看图；合入节点 `d3a1ef7`、`f59439a`，0 冲突）
  - 验证分支最终提交：`verify/E4-probes` @ `3fe21d31f11ee52c29fe383b2102a8bd64fde163`（保留第 1 轮全部原始探针，仅修改 [E4ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/test/java/net/jejer/hipda/verify/E4ProbeTest.kt)，原仓 `/home/mir/Documents/hipda-next`、生产代码与快照基线 **0 改动**）
- **本地全量验证**（`flock /tmp/hipda-gradle.lock`，`-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`）：
  - `:core-parser:test :hipda:assembleDebug :hipda:lintDebug :hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**PASS**（`E4ProbeTest` `20/20 PASS`；`:hipda:testDebugUnitTest` `1333/1333 PASS`；`finalizeTestRoborazziDebug` 0 差异；`lintDebug` PASS）
- **GitHub Actions CI**：[Android CI #45757086073](https://github.com/mailwmj/hipda-next/actions/runs/45757086073)（触发自 `verify/E4-probes` @ `3fe21d31f11ee52c29fe383b2102a8bd64fde163`）—— **success**（`12m21s`）：
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`：`:hipda:testDebugUnitTest` `1333 tests, 1333 passed, 0 failed, 0 skipped`（含 `E4ProbeTest` `20/20 PASS`），`:core-parser:test` `FROM-CACHE`，`finalizeTestRoborazziDebug` **0** 截图差异。

### 8.2 第 2 轮逐项判定清单

| 验证项 | 覆盖探针 | 判定 | 本轮关键实测证据 |
| --- | --- | --- | --- |
| **probeD2（抽屉优先退栈修复）** | `probeD2_mergedModalDrawer_andSettingsSubPageOverlays_doNotConsumePositionStackOrRehydrate` | **PASS** | [HiNavigationSidebar.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L284-L289) 条件挂载 `key(visible)` `BackHandler` 后：抽屉打开时首次返回仅关闭 `SidebarModalDrawer`，`detailVm` 保持第 1 页且 `canPopPositionStack == true`、`getReadingPositionCount == 1`；抽屉关闭后第 2 次返回才弹出 `PositionStack` 回第 4 页；第 3 次返回离开帖子。 |
| **(a) 慢加载门闩 / B1 落盘回归** | `probeA1`, `probeA2`, `probeA3` | **PASS** | B1 改用 `ReadingPositionController` + `NonCancellable` 串行写盘后，未水合阶段滚动与提前退场 `saveCalls == 0` 仍严格守住；三栏优先退 `PositionStack` 与前后预取取消完全正常。 |
| **(b) 玻璃卸载与三栏预算** | `probeB1`, `probeB1b`, `probeB2` | **PASS** | `suppressBottomGlass` 卸载 `HiGlassRegion` 并释放 `hasSourceDemand`；三栏满配 `HiGlassMarker == 3`、`LayerBackdrop <= 2`、`nestedLayerBackdrop == 0`、`drawBackdrop <= 5`。 |
| **(c) 跳页条/动效/对比度/白名单** | `probeC1`, `probeC2`, `probeC2b`, `probeC3`, `probeC4`, `probeRC5b` | **PASS** | 第 2 轮特地在 `probeC4` 补充 `https://user@www.4d4y.com/...` 与 `https://user:pass@www.4d4y.com/...` 反例（覆盖工具函数与设置域名后的 `"在浏览器中打开"` 菜单），结合 [ThreadBottomToolbar.kt](file:///home/mir/Documents/hipda-next.wt/V-E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt#L99) `rawUserInfo` 校验已 100% 拦截；其余 `C1–C3`/`MockWebServer` 闭环/`RC5b` 全通。 |
| **(d)(e) 主干入口与浮层集成** | `probeD1`, `probeD3`, `probeE` | **PASS** | `E-slots`/`E8`/`E10`/`E12`、设置发布帖与 `M6` 搜索/收藏/提醒入口仅单次水合、浮层与下钻均不误冲写/误清 `PositionStack`。 |
| **(f) 停手 `<400ms` 立即退场/切帖可挂起落盘** | `probeF1`, `probeF2` | **PASS** | 使用独立测试替身 `ProbeThreadDetailRepository.saveDelaysByTid`（`CompletableDeferred` 真实挂起，不用实现方假仓库）：<br>1. **单栏立即返回（`probeF1`）**：从 `p502@20px` 手指下滚停稳后 `<400ms`（`saveStartedCalls == 0`）挂载写盘闸门并立即按系统返回，待 `ThreadDetailPane` `onDispose` + `onCleared()` 使旧帖子 `vmScopeJob.isActive == false` 且 `saveCalls == 0` 时再放行闸门，写入仍在 `NonCancellable` 中完成并写入更新后位置，重进精确恢复到该楼层。<br>2. **三栏立即切帖 + 在途慢写串行交接（`probeF2`）**：Part A 在 `<400ms` 切去 `1002` 且 `1001` `vmFirstJob.isActive == false` 后放行闸门仍准确落盘；Part B 让第 1 次慢写挂起于 `slowGate1`，期间再次滚动并 `<400ms` 切去 `1003`（`vmSecondJob.isActive == false`），放行后严格按 FIFO 先完成 `midWrite1` 再落盘第 2 次位置，最终 DB 状态为最新滚位、未被慢写逆序覆盖。 |
| **(g) 合并 `E14` 真看图 0 玻璃协同** | `probeG1`, `probeG2` | **PASS** | 1. 独立断言（`probeG1`）：`LocalHiGlassSuspended = true` 时 `ThreadBottomToolbar` `HiGlassMarker` 由 `1 -> 0`、3 胶囊 `HiGlassBlurActive` 由 `true -> false`、`DrawBackdrop` `3 -> 0`、`LayerBackdrop -> 0`、`hasSourceDemand == false`；恢复 `false` 后全部复原。<br>2. 完整 `NextAppRoot` + 真实 `ImageViewerRendererImpl` 集成（`probeG2`）：三栏看图前 `HiGlassMarker == 3`、`HiGlassBlurActive==true` 至少 5 处；点击楼层图弹出真实 `E14 ImageViewerOverlay` 后同屏 **`HiGlassMarker == 0`、`HiGlassBlurActive==true == 0`、`DrawBackdrop == 0`、`LayerBackdrop == 0`**（底部工具条与双顶栏全部暂停并保持降级存在）；点击“关闭”完成退场动画后不触发二次水合，`HiGlassMarker == 3`、活跃模糊与底部进度跳页交互完整恢复。 |

### 8.3 保留状态（第 2 轮收尾）

- **验证分支**：`verify/E4-probes` @ `3fe21d31f11ee52c29fe383b2102a8bd64fde163`（已推至 `origin/verify/E4-probes`，按卡保留）
- **验证 Worktree**：`/home/mir/Documents/hipda-next.wt/V-E4`（工作区干净、无残留 Gradle Daemon，原仓 `/home/mir/Documents/hipda-next` 零改动）
