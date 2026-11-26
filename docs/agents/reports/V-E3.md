# V-E3 验证报告：`origin/card/E3-multipane-container @ 6fa4765`

## 1. 总判定与产物概览

- **总判定**：**FAIL**（复现 1 个 P0 必现崩溃：铰链双栏下在右栏 `MineSubList` 点击条目晋升左栏时，`AnimatedContent` 退场槽与左栏晋升槽同帧双重持有 `SaveableStateProvider("sidebar:History")`，触发 `java.lang.IllegalArgumentException: Key sidebar:History was used multiple times`）
- **验证对象**：`origin/card/E3-multipane-container @ 6fa4765`（对比基线 `b7557bf`）
- **探针分支与 Worktree**（已保留）：
  - Worktree：`/home/mir/Documents/hipda-next.wt/V-E3`
  - 分支：`verify/E3-probes` @ `e664ad99ba10de12a46cca5cb67f950831223bb5`
  - 独立探针文件：[E3ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/test/java/net/jejer/hipda/verify/E3ProbeTest.kt)（17 个测试方法：16 PASSED / 1 FAILED）
- **CI 验证链接**：[Android CI Run #45718726463](https://github.com/mailwmj/hipda-next/actions/runs/45718726463)
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）：**success**
  - `Verify Room schema consistency`：**success**
  - `Check for Roborazzi diff images`：**success**（0 张基线漂移）
  - `Run unit tests and verify Roborazzi screenshots`：**failure** — CI 全仓合计 `1144 tests, 1143 passed, 1 failed, 0 errors, 0 skipped`，唯一失败为 `E3ProbeTest.probeB1a_2_hingeMineSubList_tapItemToPromote_mustNotCollideSaveableKey`

---

## 2. Diff 边界与基线截图审计

`git diff --name-status b7557bf...6fa4765` 核查结论：
- **零越界**：`feature/forum/*`、`feature/threaddetail/*`、`feature/login/*`、`feature/common/*`、`data/*`、`di/*`、`src/debug/*` 均未触碰。
- **截图基线增量**：`hipda/src/test/snapshots/` 仅新增 18 张 `multipane_*.png`，既有基线 0 变更；本地 `-Proborazzi.test.verify=true` 与 CI 均 0 diff 通过。

---

## 3. 逐张目视审阅结论（18 张 `multipane_*.png`）

基线目录：[hipda/src/test/snapshots/images/](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/test/snapshots/images)

1. **`multipane_w411_h891_light.png` / `multipane_w411_h891_dark.png`**（单栏 `411×891dp`）：
   - 顶栏透明玻璃与 `今日新帖 | Discovery | Buy & Sell | Geek Talks | +` 粘性标签行完整显示；`论坛` 高亮、`消息` 展示合并未读角标 `7`；列表内容自然穿过底部全宽液态玻璃导航胶囊，明暗对比清晰。
2. **`multipane_w559_h749_light.png` / `multipane_w559_h749_dark.png`**（Fold8 竖屏单栏 `559×749dp`）：
   - 底部导航胶囊居中且宽度受 `420dp` 上限约束未拉伸畸变，角标 `7` 清晰；顶部粘性版块标签均匀分布不拥挤。
3. **`multipane_w640_h1024_light.png` / `multipane_w640_h1024_dark.png`**（双栏图标轨单栏 `640×1024dp`）：
   - 左侧 `64dp` 收起图标轨展示折叠按钮、主目的地（带独立 `2`/`5` 角标）、关注版块图标与缩写短名、`+`、设置及固定底部用户头像 `H`；右侧列表隐藏粘性标签行并保留 `40dp` 头像列，底部无胶囊。
4. **`multipane_w749_h559_light.png` / `multipane_w749_h559_dark.png`**（**重点点名：Fold8 749×559 三栏**）：
   - `64 | 274 | 411dp` 比例规整；`274dp < 300dp` 触发中栏紧凑子行布局（隐藏左侧 `40dp` 头像列，标题下方紧跟 `20dp` 小头像+作者/统计/时间），首贴选中态高亮明确；中/右间 `1dp` 分割线清晰；右栏 `411dp` 完整渲染 `ThreadDetailTopBar`（含 `全屏阅读` 与 `只看楼主` 圆形按钮）、楼主引用块及底部分页栏（`上一页 / 第 1 / 3 页 / 下一页`）；`559dp` 矮屏下左栏滚动区底部仅见 `设置` 齿轮上沿轻度露出并留有固定 `H` 头像底栏，属于可滚动暗示且无遮挡冲突。
5. **`multipane_w749_h559_drawer_light.png`**（Fold8 `749×559` 模态抽屉展开）：
   - `240dp` 宽模态抽屉右侧 `20dp` 圆角与下方半透明遮罩均匀覆盖中右两栏，未挤压/重排底图三栏；高度 `< 760dp` 自动保持 `我的` 折叠，保证全部 3 个关注版块（`Discovery` 高亮）+ `管理版块` + 底部 `设置` 同屏直达。
6. **`multipane_w933_h696_light.png` / `multipane_w933_h696_dark.png`**（展开三栏 `933×696dp`）：
   - `187 | 298 | 448dp` 分布合理；`696dp < 760dp` 保持 `我的` 折叠以露出关注版块；`298dp < 300dp` 中栏采用紧凑子行布局。
7. **`multipane_w933_h696_sidebar_collapsed_light.png`**（`933×696` 侧栏主动收起）：
   - 左栏收起为 `64dp` 后，中栏扩展至 `348dp >= 300dp`，自动恢复左列 `40dp` 头像，右栏 `521dp`。
8. **`multipane_w933_h696_fullscreen_light.png`**（`933×696` 全屏阅读）：
   - 左中两栏完全隐藏，详情占满全屏，正文按 `680dp` 阅读最大宽度居中两侧留白舒适，详情顶栏按钮切换为高亮 `显示列表`。
9. **`multipane_w1280_h800_light.png` / `multipane_w1280_h800_dark.png`**（大屏三栏 `1280×800dp`）：
   - `220 | 380 | 680dp`；`800dp >= 760dp` 自动展开 `我的` 4 子项与 3 个关注版块同屏展示；中栏 `380dp >= 300dp` 显示 `40dp` 头像列。
10. **`multipane_hinge_flat_w749_h559_light.png`**（Fold8 非分离铰链展平态）：
    - `isSeparating = false` 时正确忽略折痕，呈现标准 `64 | 274 | 411dp` 三栏。
11. **`multipane_hinge_twopane_w749_h559_light.png`**（**重点点名：铰链双栏**）：
    - `isSeparating = true` 隐藏侧栏，左右严格避开物理铰链各自成栏；左顶栏退为纯白实体底（无玻璃模糊）并恢复粘性标签行（`~374dp` 宽下 4 标签末项 `Geek Talks` 显示 `Geek T...` 轻微省略，在窄半屏下属预期截断）；`CompactPill` 严格居中于左列表半屏（仅选中 `论坛` 显字、`消息` 带 `7` 徽标）；右栏正常显示正文与 `全屏阅读` 按钮。
12. **`multipane_hinge_fallback_w701_h841_light.png`**（铰链退单栏 `701×841dp`）：
    - 右侧 `< 360dp` 正确退单栏满屏，底栏收缩为 `CompactPill` 并偏移居中于左半屏避开中缝折痕。

### 截图集合覆盖缺口（重点点名 3 项行为已改由独立探针补充验证）
- **空右栏**：18 张静态基准截图全部为已选帖子态，**缺失空右栏占位状态图**（`RightPaneSlot.Empty` / `"选一篇帖子开始阅读"`）；已在 `probeA1`、`probeB1c`、`probeB2b` 中完成交互级验证。
- **拖拽后的分隔线**：18 张截图均为默认 `PaneSplit.DEFAULT` 静帧，**缺失 `BALANCED`/`COLLAPSED` 档位或按压抓手胶囊形态截图**；已在 `probeA1`、`probeA2`、`probeA3`、`probeC` 中完成拖拽、吸附与无障碍验证。
- **单栏二级页退场胶囊**：单栏截图均为论坛一级页，**缺失单栏进入帖子/`我的`子页后底部胶囊退场截图**；已在 `probeB3`、`probeB4`、`probeB5` 中完成退场与返回恢复验证。

---

## 4. 独立探针与专项需求逐项结论

| 探针 / 专项要求 | 用例方法（[E3ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/test/java/net/jejer/hipda/verify/E3ProbeTest.kt)） | 结论 |
|---|---|---|
| **额外要求 #2 宽度边界**（680 / 749 / 839 / 840 / 1280dp） | `widthBoundaries_680_749_839_840_1280_enforcePaneCountsAndExactWidths` | **PASS**（680→`64\|256\|360`；749→`64\|274\|411`；839→`64\|310\|465`；840→`176\|266\|398`；1280→`220\|380\|680`） |
| **探针 a**：空右栏拖拽底线（逐帧 ≥ `M_default`、绝不为 `0dp`） | `probeA1_emptyRightPane_largeLeftDragFrameByFrameNeverEntersZeroOrBelowDefault` | **PASS** |
| **探针 a**：拖拽吸附、`CustomAccessibilityAction`、键盘 `Enter`/方向键、横竖屏持久化、全屏还原+采样源释放 | `probeA2_dividerDragSnapA11yKeyboardOrientationPersistence_andFullScreenRoundTrip` | **PASS** |
| **探针 a**：`reducedMotion` `0ms` 单帧立即吸附 | `probeA3_reducedMotion_snapsDividerInSingleFrame` | **PASS** |
| **探针 b**：`History` 展平→半折晋升→展平三栏、半折返回 `MineMenu\|History` | `probeB1a_1_history_flatToHingePromotionAndDemotionToMineMenu` | **PASS** |
| **探针 b**：**铰链右栏 `MineSubList(History)` 点击条目晋升左栏** | `probeB1a_2_hingeMineSubList_tapItemToPromote_mustNotCollideSaveableKey` | **FAIL**（见第 5 节触发路径） |
| **探针 b**：`Notify` 展平↔铰链双栏晋升与回退 | `probeB1b_notify_flatToHingePromotionAndDemotion` | **PASS** |
| **探针 b**：连点 5 帖不压栈 + 5 级返回栈优先级 | `probeB1c_fiveThreadsNonStacking_andFiveLevelBackPriority` | **PASS** |
| **探针 b**：切 `tidA -> tidB` 隔离无显式 key `rememberSaveable` | `probeB2a_switchTidIsolatesUnkeyedSaveableState` | **PASS** |
| **探针 b**：开帖态左栏/TodayNew/铰链标签切版块清空右栏并 `clear()` 旧 `ThreadDetailViewModel` | `probeB2b_forumSwitchAndHingeTabSwitch_clearOldThreadStore` | **PASS** |
| **额外要求 #3 + R-E0 S4**：Fold8 `749 → 411（进程恢复）→ 749` 保阅读位且请求增量为 0 | `probeB3_fold8_749_to_411_processRestoration_to_749_preservesScrollOffsetsWithZeroExtraRequests` | **PASS**（列表/详情偏移偏差 `≤ 2px`，请求均为 `1 -> 1`，增量 `0`） |
| **额外要求 #3 补充**：进程恢复伴随重建新 `NextAppHostViewModel` | `probeB5_fold8_749_to_411_newHostViewModelProcessRecreation_to_749_preservesState` | **PASS**（列表/详情滚动位均保留；冷重建详情 VM 时重拉 1 次） |
| **探针 b**：单栏二级页退场胶囊、返回恢复、合并未读数（`7` / `105→99+` / `0`） | `probeB4_singlePaneSecondaryPage_dismissesPill_andRestoresWithMergedUnreadBadge` | **PASS** |
| **探针 c**：真实 `E1+E2a` 三栏玻璃预算（`Marker<=3`、`LayerBackdrop==2`、pass `<=5`、严格跨栏隔离）、铰链左顶栏 0 玻璃、`DetailPane`/`ContentArea` Snackbar 严格不跨分隔线与物理铰链 | `probeC_threePaneGlassBudget_hingeSolidTopBar_andMultiPaneSnackbarAlignment` | **PASS** |
| **探针 d**：`R-C4d S2` 常驻侧栏已有 `LazyListState(4, 22)` 首次挂载不归零 | `probeD1_rc4d_S2_sidebarListStatePreservedAndCollapseSyncs` | **PASS** |
| **探针 d**：`R-C4d S3` 底部胶囊横拖悬停未松手显 `primary` 预选高亮且 `onSelect == 0` | `probeD2_rc4d_S3_pillDragHoverPreSelectsWithoutFiringOnSelect` | **PASS** |
| **探针 d**：`R-C4d S1` 未传 `managesGlassSource` 切页前 0..8 帧无父子同源采样循环 | `probeD3_rc4d_S1_omittedManagesGlassSourceHasNoFrameZeroCycle` | **PASS** |

---

## 5. FAIL 触发路径与根因定位

- **触发路径**：
  1. 设备处于物理铰链分离态（如 Fold8 半折 `749×559dp`，`FoldingFeature.isSeparating = true`），处于 `HingeTwoPane` 模式；
  2. 进入 `我的 -> 浏览历史`（即 `MineMenu` 在左栏、`RightPaneSlot.MineSubList(History)` 在右栏 `AnimatedContent` 内，或从三栏 `History` 开帖半折后按一次系统返回进入该双栏态）；
  3. **在右栏副列表内点击任意一条帖子**，调用 `openThread(args)`；
  4. `resolveHingeTwoPaneRoles(appState)` 使左栏立即切为 `HingeLeftPaneRole.PromotedMineSubList(History)`，右栏 `AnimatedContent` 从 `RightPaneSlot.MineSubList(History)` 切换到 `RightPaneSlot.Thread(args)`。
- **源码根因**（[MultiPaneReadingContainer.kt](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L160-L375)）：
  - 左栏在 [第 372–375 行](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L372-L375) 立即调用 `secondaryMovableFor(promoted)`（内部包裹 `saveableStateHolder.SaveableStateProvider("sidebar:History")`，[第 160–166 行](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L160-L166)）；
  - 与此同时，右栏 `RenderRightSlot` 使用 `AnimatedContent(targetState = slot)`（[第 233–254 行](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L233-L254)），退场动画期间仍保留 `RightPaneSlot.MineSubList(History)` 处于组合树中并调用同一个 `secondaryMovableFor(History)`；
  - 同一帧内 `"sidebar:History"` 同时存在于左右两个存活槽位，`SaveableStateHolderImpl.SaveableStateProvider` 抛出 **`java.lang.IllegalArgumentException: Key sidebar:History was used multiple times`** 导致应用直接崩溃。按同结构逻辑，`Favorites` / `MyThreads` / `MyReplies` 等所有 `MineSubList` 项，以及右栏同贴下钻同 `tid`（`detailMovableFor(tid)` 在 `RightPaneSlot.Thread` 与 `LinkedThread` 间切换 [第 252、257 行](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L252-L257)）亦存在同类双占位键冲突风险。

---

## 6. 本地与 CI 执行实录

- **本地命令（`flock` 串行 + 限内存）**：
  - `:core-parser:test :emoji:testDebugUnitTest :hipda:testDebugUnitTest -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug --continue`
  - 汇总：`119` XML 套件共 `1144` tests：`1143 passed, 1 failed, 0 errors, 0 skipped`；Roborazzi 0 diff；`assembleDebug`、`assembleRelease`、`lintDebug` 全通过；`CopyTerminologyScanTest`（3/3）与 `MotionTokenScanTest`（7/7）全通过。
- **CI 命令与结果**：
  - `gh workflow run android.yml --ref verify/E3-probes` -> [Run 45718726463](https://github.com/mailwmj/hipda-next/actions/runs/45718726463)
  - 状态：`failure`（仅 `Run unit tests and verify Roborazzi screenshots` 失败：`1144 tests, 1143 passed, 1 failed`；`Build and lint`、`Verify Room schema consistency`、`Check for Roborazzi diff images` 均为 `success`）。

---

## 7. 第 2 轮复验结果：`origin/card/E3-multipane-container @ ba8f4c2`（已合入 `main @ fbeeb28`）

### 7.1 总判定与 CI 实录

- **第 2 轮总判定**：**PASS**（第 1 轮 F1 P0 崩溃已确认修复；17 个既有探针断言原样重跑 + 第 2 轮 3 项新增检查共 20/20 全部通过；全仓 1194 项测试与 21 张基线截图 0 失败、0 漂移）
- **验证对象**：`origin/card/E3-multipane-container @ ba8f4c2`（已合入 `main @ fbeeb28` 含 E2b/E2d）
- **探针分支**：`verify/E3-probes` rebase 至 `ba8f4c2`，提交 `e87fc47e57604015278790c3393b4c5975f79724`（Worktree `/home/mir/Documents/hipda-next.wt/V-E3` 已保留）
- **本地全套命令与结论**：
  - `:core-parser:test :emoji:testDebugUnitTest :hipda:testDebugUnitTest -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`
  - 汇总：`124` XML 套件共 `1194` tests：**`1194 passed, 0 failed, 0 errors, 0 skipped`**，`ROBORAZZI_DIFFS=0`，Debug/Release APK 与 Lint 全部通过。
- **CI 验证链接**：[Android CI Run #45725693992](https://github.com/mailwmj/hipda-next/actions/runs/45725693992)
  - 结论：**`success`**（`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 及所有产物上传步骤全部 `success`；单测汇总 **`TOTAL: 1194 tests, 1194 passed, 0 failed, 0 errors, 0 skipped`**，`E3ProbeTest: 20 tests, 20 passed`）。

### 7.2 既有 17 项探针原样重跑结论（断言 0 改动）

因 `ba8f4c2` 将 `NextAppRoot` 的 `secondaryListContent` 与内置占位 `我的` 子项移至外层 `MultiPaneReadingContainer` / `UnbuiltEntryPlaceholder`，仅调整了测试脚手架 `ProbeHarness.RenderRoot` 的槽位传入方式（17 项测试内全部断言**逐字保持未改**），重跑结果：
- **原 FAIL 确认已修复**：`probeB1a_2_hingeMineSubList_tapItemToPromote_mustNotCollideSaveableKey` 在 `ba8f4c2` 下由 **FAIL → PASS**（[MultiPaneReadingContainer.kt#L311-L317](file:///home/mir/Documents/hipda-next.wt/V-E3/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L311-L317) 通过 `if (target == currentSlot)` 令 `AnimatedContent` 退场槽即时让出 `secondaryMovableFor` 唯一持有权，彻底消除同帧 `"sidebar:History"` 双占位崩溃）。
- **全部 17 项既有探针**：**17 / 17 PASS**（宽度边界 `680/749/839/840/1280`、探针 a–d、`Fold8 749→411(进程恢复)→749` 均稳定通过）。

### 7.3 第 2 轮额外三项检查结论

1. **检查 (1) 目视 3 张新增基线截图**（均在 Roborazzi 0 diff 下核对）：
   - **`multipane_w749_h559_empty_detail_light.png`（空右栏）**：**PASS**。左 `64dp` 收起图标轨、中 `274dp` 紧凑列表（`Discovery` 顶栏+发帖笔+更多 `⋮`、首贴无选中底色）、`1dp` 竖分割线与右 `411dp` 居中 `选一篇帖子开始阅读` 占位排版干净，无多余按钮或重叠。
   - **`multipane_w933_h696_balanced_split_light.png`（拖拽后的分隔线 - 均衡档）**：**PASS**。`187 | 373 | 373dp`，中栏升至 `373dp >= 300dp` 自动恢复左侧 `40dp` 圆形头像列，`1dp` 分隔线精确处于 `560dp` 等分边界，中栏右上 `⋮` 与右栏正文互不干扰遮挡。
   - **`multipane_w411_h891_detail_pill_hidden_light.png`（单栏二级页退场胶囊）**：**PASS**。`411×891dp` 单栏进帖子详情后底部 `HiNavigationPill` 完全退场、无残影或空白占位；底部分页条 `上一页 / 第 1 / 3 页 / 下一页` 安全边距完好，顶栏返回箭头+标题+仅看按钮与 1–3 楼层次清晰。
2. **检查 (2) 三栏「更多」/「返回」触发与分隔线附近竖滑**：**PASS**（`check2_threePaneTopBarMoreAndBackTrigger_andVerticalScrollNearDividerSucceeds`）：
   - 分别在 `933×696dp`（展开三栏）与 `749×559dp`（Fold8 紧凑三栏）验证：中栏右上 `更多`（紧邻分隔线左 `24dp` 内）点击正常展开 `按发帖排序` / `按回复排序` 菜单；右栏下钻 `UserProfile` 后左上 `返回`（紧邻分隔线右 `24dp` 内）点击正常回退原帖；从分隔线左 `-6dp` 与右 `+6dp`（`48dp` 热区内）垂直上滑均稳定驱动对应列表滚动且分隔线横向位置 `<= 1px` 零漂移。
3. **检查 (3) 论坛滚动保留与跨区零额外请求**：**PASS**（`check3a_singlePane_...` & `check3b_threePane_...`）：
   - 单栏 `411×891dp`（经 `BottomPill`）与大屏三栏 `1280×800dp`（经 `HiNavigationSidebar`）均验证：论坛滚至中段第 12 项（`2013` 可见、`2001` 滚出顶部）后依次切 `搜索` → `我的` → `消息` → 切回 `论坛`，垂直滚动值与可见目标项纵坐标偏差 `≤ 2px`，`FakeThreadListRepository.totalNetworkCalls` 始终维持 `1 -> 1`（请求增量为 `0`）。
