# V-E5 独立验证报告（E5 快速回复面板 + `origin/main@6ff55a9` 合并态）

- **结论**：**FAIL（阻断合并）** — 合并态 12 项独立探针通过 **11 项**、阻断失败 **1 项**（`probe3b_singleBatchHandleDrag_dismissesQuickReplySheet`），命中 [QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L226-L253) 手势拖拽状态异步更新竞态缺陷；其余 IME 逐帧夹紧/矮屏降级/减弱动效、三栏与铰链边界/玻璃预算卸载恢复、五层返回栈防穿透、步进拖拽与 `R-D6 S2`、Roborazzi 既有快照零漂移、三可选插槽 `FullEditorHandoff` 无损交接、`MockWebServer` 30 秒冷却 0 额外请求与只看楼主引用重试全部通过。
- **被测对象**：`card/E5-quick-reply-sheet` @ `73a64682e336e08fb362659e5e078a83ed7b834d`（基线 `92e1563`），合入 `origin/main@6ff55a9`（含 E16 路由/链接收口；后续 `origin/main@9338529` 仅含 `docs/` 归档变更，无代码差异）。
- **验证分支 / worktree / 提交**：`verify/E5-probes` @ `8c12b11242ffc967a3d3a3f0cc376604f801f3e0`（`/home/mir/Documents/hipda-next.wt/V-E5`，合并提交 `1894e97`）。
- **实现方 CI**：[Android CI #45762412787](https://github.com/mailwmj/hipda-next/actions/runs/45762412787) — `Build, Lint & Unit Tests`（Job `139715247530`）`success`（`Emulator Smoke Test` `skipped`）。
- **验证方 CI**：[Android CI #45767374631](https://github.com/mailwmj/hipda-next/actions/runs/45767374631) — `Build, Lint & Unit Tests`（Job `139731432185`，`10m57s`）`failure`：
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`（`-Proborazzi.test.verify=true --continue`）：**TOTAL 1481 tests, 1480 passed, 1 failed, 0 errors, 0 skipped**
    - [E5ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt)：**12 tests, 11 passed, 1 failed**
    - `QuickReplyScreenshotTest`（9/9）、`QuickReplySheetTest`（6/6）、`QuickReplyViewModelTest`（6/6）、`QuickReplyIntegrationBackPriorityTest`（3/3）：全通过
    - `MultiPaneBackStackTest`（7/7）、`MultiPaneDividerGestureTest`（9/9）、`MultiPaneGlassBudgetTest`（9/9）、`MultiPaneScreenshotTest`（21/21）、`MultiPaneStateMigrationTest`（10/10）：全通过
    - `CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）：全通过
    - Roborazzi 快照校对（`Check for Roborazzi diff images`）：0 张差异图（`Upload Roborazzi diff artifacts on failure` 跳过）

---

## 一、阻断项明细

### B1：把手单批/快速下拉关闭竞态导致越阈值不收起并回弹（`probe3b` FAIL）

- **复现用例**：[E5ProbeTest.kt#L957-L1014](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L957-L1014)（`probe3b_singleBatchHandleDrag_dismissesQuickReplySheet`），在 CI `#45767374631` 与本地 `:hipda:testDebugUnitTest` 稳定复现（`expected:<0> but was:<1>`，断言位置 `E5ProbeTest.kt:1003`）。
- **根因路径**：
  1. [QuickReplySheet.kt#L226-L235](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L226-L235) 中，`onDragDelta` 读取当前 `offsetYPx.value` 计算 `next = (offsetYPx.value + deltaY).coerceIn(minOffset, maxOffset)`，却用 `scope.launch { offsetYPx.snapTo(next) }`（默认调度 `CoroutineStart.DEFAULT`）异步写入 `Animatable`；
  2. [QuickReplySheet.kt#L237-L251](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L237-L251) 中，`onDragSettled` 同步读取 `val cur = offsetYPx.value` 判断是否越过 `dismissThresholdPx`（`72.dp`）或 `-expandThresholdPx`（`64.dp`）与速度阈值；
  3. [QuickReplyHeaderZone](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L440) 的 `Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { onDragSettled(it, true) })` 在同一 `Channel` 协程循环中依次处理 `DragDelta` 与紧随其后的 `DragStopped`。当 `ACTION_MOVE(120.dp)` 与 `ACTION_UP` 落在同帧或快速轻扫（`< 1` vsync，或合批触摸事件）时，`onDragStopped` 先于排队到 `AndroidUiDispatcher` 的 `scope.launch { offsetYPx.snapTo(next) }` 执行，导致 `offsetYPx.value` 仍为 `0f`，既不满足 `cur >= dismissThresholdPx` 也不满足 `cur > 0f && velocityY >= dismissVelocityPx`，面板直接弹回原位而非收起保存草稿。
  4. 同理，同帧内多个合批 `MOVE` 事件都会读取过时的 `offsetYPx.value` 互相覆盖而非累加（对把手下拉、把手上拉展开以及 `inputNestedScroll` 的增量累计均有同类隐患；[QuickReplySheet.kt#L254-L282](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L254-L282) 在 `probe3d` 通过仅因 `ScrollableNode` 另起 coroutine 排在 `snapTo` 之后，但无法消除合批多 `MOVE` 互相覆盖的隐患）。
- **修复方向**：在 `QuickReplySheet` 内同步维护拖拽实时位移基准（例如同步 `dragOffsetPx` 状态随 `onDragDelta` 立即累加并限幅，再驱动或同步至 `offsetYPx.snapTo(next)`；或使用 `CoroutineStart.UNDISPATCHED` / `Animatable` 同步基准值供 `onDragSettled` 与 `onPreFling` 读取），确保无论逐帧步进还是同帧合批/快速轻扫都严格一致。

---

## 二、逐项探针验证结果

| 探针 | 用例名 & 行号 | 结果 | 验证要素与实测观测 |
|---|---|---|---|
| **探针 1a** | `probe1a_nextAppRoot_imeFrameByFrameRiseAndFall_clampsHeightAndTracksImeZeroLag` ([L278](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L278)) | **PASS** | 挂载完整 `NextAppRoot + ThreadDetailPane + QuickReplySheet`，`autoAdvance=false` 下驱动 `WindowInsetsCompat.Type.ime()` 从 `0 -> 60 -> 180 -> 300dp` 逐帧升起并回落至 `0`：内容区净高保持 `800 -> 740 -> 620 -> 500 -> 800dp`；软键盘升起后表面夹紧为 `min(300dp, visibleNetHeight * 0.60)`，横屏 `891x411` 且 IME `240dp` 时仍严守 `<= visibleNetHeight * 0.60` 上限。 |
| **探针 1b** | `probe1b_nextAppRoot_shortScreenUnder180dp_hidesHandleAndDraftBannerZeroClipping` ([L386](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L386)) | **PASS** | 横屏 `891x411` 叠高 IME 使净可用高度 `< 180dp` 时，预置草稿下自动进入紧凑单行条：把手（`收起回复面板`）与「已恢复上次草稿」横条全部隐藏，`BasicTextField` 高度 `>= 40dp`、「发送」按钮触控高 `>= 48dp`，且无裁切或底部溢出。 |
| **探针 1c** | `probe1c_reducedMotion_zeroScale_opensDismissesAndExpandsInSingleFrame` ([L520](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L520)) | **PASS** | `withAnimatorDurationScale(0f)` 开启、`autoAdvance=false` 下打开、点暗罩关闭与触发 `展开全屏` 均在 1 帧内到位/卸载，`QuickReplySheet` 首帧即达到 `translationY == 0f` 且无弹簧阻尼拖尾；展开全屏同样不弹「已保存草稿」。 |
| **探针 2a / 2b** | `probe2a_threePane749dp_...` ([L603](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L603)) & `probe2b_threePane933dp_...` ([L609](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L609)) | **PASS** | 749dp 与 933dp 三栏展开后，快速回复表面与全屏暗罩 `boundsInRoot.left` 严格位于右栏分栏右侧、`right <= rootWidth`、零跨栏；打开期间底部玻璃工具条完全卸载，`HiGlassMarker == 2`（顶栏 1 + 面板 1 <= 3）、`LayerBackdrop == 1`（<= 2）、嵌套 `LayerBackdrop == 0`、`DrawBackdrop`（顶栏 + 面板 <= 5）；关闭后工具条与「第 1 / 10 页」胶囊恢复。 |
| **探针 2c** | `probe2c_hingeTwoPane749dp_quickReplyRightOfHingeAndGlassBudget` ([L615](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L615)) | **PASS** | `749dp` 含 `isSeparating == true` 垂直铰链双栏下，`QuickReplySheet` 面板与暗罩严格位于铰链右侧区域，不越过铰链中缝，玻璃预算（`HiGlassMarker <= 3`、`LayerBackdrop <= 2`、嵌套 0、`DrawBackdrop <= 5`）及关闭恢复均满足。 |
| **探针 3a** | `probe3a_mergedBackPriority_...` ([L779](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L779)) | **PASS** | 在 `origin/main@6ff55a9` 合并态完整 `NextAppRoot` 上构建 5 层栈（`ImageViewer` > 240dp `ModalDrawer` > `QuickReplySheet` > `PositionStack` 2 条 `10 -> 1` > 三栏右栏详情）：① 快速回复开启时暗罩拦截下层 `performClick()`，语义点击叠开看图后在看图 `Dismissing` 退场期连按两次 `Back` 仅退看图且草稿原文留在输入框；② 叠开 `ModalDrawer` 按 `Back` 仅退抽屉；③ 快速回复 `Dismissing` 退场期连按两次 `Back` 仅收起面板并存草稿、弹出「已保存草稿」，绝不穿透弹出 `PositionStack`；④ 再两次 `Back` 逐层弹出位置栈回到第 10 页、第 1 页；⑤ 最后一次 `Back` 关闭右栏回占位。 |
| **探针 3b** | `probe3b_singleBatchHandleDrag_dismissesQuickReplySheet` ([L957](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L957)) | **FAIL (B1)** | 在面板开启后执行单批把手下拉 `down -> moveBy(120dp, 40ms) -> up`：因 [QuickReplySheet.kt#L226-L251](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L226-L251) `scope.launch { snapTo }` 未赶在 `onDragSettled` 前执行，面板未收起（`收起回复面板` 语义节点仍为 1 个），阻断。 |
| **探针 3c & 3d** | `probe3c_gestureIsolation_...` ([L1073](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1073)) & `probe3d_singleBatchTopOverscroll_...` ([L1016](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1016)) | **PASS** | 20 行多行输入内上滑/非顶边回滑不关闭；带 `[0, 6)` 选区顶边下拉 140dp 不关闭；把手 `30dp < 72dp` 下拉回弹且不存草稿；**多帧步进**把手 `120dp` 下拉关闭并落盘草稿、弹「已保存草稿 \| 放弃」，点「放弃」立即删库；多行滚回顶部及短行顶边超界下拉均关闭并落盘草稿；验证 `R-D6 S2` 两条路径（恢复草稿后手动改为空白退出、或点「清空」按钮再退出）均显式 `deleteDraft` 且重开不复活旧草稿/引用。 |
| **探针 4** | 快照零漂移与新增图核查 | **PASS** | `git diff --name-status 92e1563...73a64682e336e08fb362659e5e078a83ed7b834d` 仅新增 9 张 `hipda/src/test/snapshots/images/quick_reply_*.png`，既有快照修改/删除数为 **0**；CI `#45767374631` `-Proborazzi.test.verify=true` 下 `QuickReplyScreenshotTest`（9/9）与全仓所有截图测试全部通过，差异输出为 0。 |
| **探针 5** | `probe5_optionalSlotsBinding_...` ([L1275](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1275)) | **PASS** | 未绑定插槽时不渲染 `展开全屏` / `插入图片` / `表情` 且把手上拉无手势跳转；通过 `GeneratedComponentManagerHolder` 绑定 [ComposeSlotsEntryPoint](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt#L97-L108) 的三个假实现后，三按钮全部渲染且触控高 `>= 48dp`，假表情可插入文本并切回键盘；长按 2 楼「引用回复」设文本 `"引用正文XYZ测试"` 与选区 `[2, 6)` 点 `展开全屏`，`FullEditorHandoff` 携带完全一致的文本、选区 `[2, 6)`、引用 `(quotePid="p102", quoteAuthor="坛友2", quoteFloor=2)`、`openImagePickerImmediately=false`；长按 3 楼点 `插入图片` 携带选区 `[4, 4)`、引用 `("p103", "坛友3", 3)`、`openImagePickerImmediately=true`；两次交接全程未弹「已保存草稿」。 |
| **探针 6** | `probe6_mockWebServer_30sCooldown...` ([L1442](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1442)) | **PASS** | 全链路直连真实 `DefaultPostRepository + ForumHttpClient + SiteAccessGateway + MockWebServer`（纯离线，0 真实站点访问）：① 发送中锁定按钮（`正在发送…` 不可点）且连续点击/直调 `submit()` 不重复发请求，成功后清空预置 `reply:1001` 草稿且非末页不改页码；② 30 秒冷却内（刚进入与第 15 秒半程）各连点发送按钮 8 次 + 直调 `submit()` 多次，同时验证冷却期可继续编辑正文，`MockWebServer` 额外请求数严格为 **0**（`server.requestCount == baselineRequestCount`）；③ 30 秒冷却到期自动恢复可发送并保留编辑文本；④ 站点错误 `alert_error` 在面板内保留输入提示「发送失败 \| 重试」且不启冷却，点「重试」发送成功；⑤ 切到「只看楼主」过滤模式仍可正常长按 1 楼引用发送，且发送 URL 正确携带 `repquote=101`。 |

---

## 三、非阻断备注与改动核查

- **编译器弃用警告（非阻断）**：CI `#45767374631` 编译日志出现 [QuickReplyViewModel.kt#L337](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplyViewModel.kt#L337) 警告：`launch(NonCancellable)` 为 Kotlin 协程已弃用重载，建议修复 B1 时一并改用注入非取消作用域（`applicationScope` / `viewModelScope.launch { withContext(NonCancellable) { ... } }`）。
- **改动边界**：`92e1563...73a64682` 仅涉及 `feature/compose/`、`feature/threaddetail/ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`、`strings_compose.xml`、`MultiPaneGlassBudgetTest.kt` 及新增 9 张 `quick_reply_*.png`，未越界改动解析器、数据库表或安全/网关目录。
- **环境说明**：验证分支 `verify/E5-probes`（`8c12b11`）及 worktree `/home/mir/Documents/hipda-next.wt/V-E5` 按规范保留；主仓 `/home/mir/Documents/hipda-next` 未受改动。

---

# V-E5 第 2 轮复验报告（`f1b1714` + `origin/main@9338529`）

- **结论**：**PASS（建议合入）** — 第 2 轮在修复提交 `f1b1714aa3d1b80331c4e726342ed504347f71de` 合并最新 `origin/main`（`9338529`，含 E16 `6ff55a9`）上完成复验：第 1 轮阻断 B1 已闭合，原 12 项与第 2 轮新增探针 7、8 共 **14 项独立探针全部 PASS**，全仓 1493 项单测与 Roborazzi 截图校验全部通过，非 `quick_reply_*.png` 既有快照 0 漂移。
- **复验对象**：`card/E5-quick-reply-sheet` @ `f1b1714aa3d1b80331c4e726342ed504347f71de`（含 `f29ac3b` R-E5 B1 自适应布局与 `f1b1714` V-E5 B1 同步拖拽修复），合入最新 `origin/main@9338529`。
- **验证分支 / worktree / 提交**：`verify/E5-probes` @ `9391987caba0ae8be6b7c3cb2a639cde3f486249`（`/home/mir/Documents/hipda-next.wt/V-E5`）。
- **实现方 CI（第 2 轮）**：[Android CI #45769362211](https://github.com/mailwmj/hipda-next/actions/runs/45769362211)（`f1b1714aa3d1b80331c4e726342ed504347f71de`）— `Build, Lint & Unit Tests`（Job `139738130802`）`success`（`TOTAL: 1479 tests, 1479 passed, 0 failed, 0 errors, 0 skipped`；`Emulator Smoke Test` `skipped`）。
- **验证方 CI（第 2 轮）**：[Android CI #45772421912](https://github.com/mailwmj/hipda-next/actions/runs/45772421912)（`verify/E5-probes @ 9391987caba0ae8be6b7c3cb2a639cde3f486249`）— `Build, Lint & Unit Tests`（Job `139747976984`，`15m35s`）**`success`**：
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`（`-Proborazzi.test.verify=true --continue`）：**TOTAL: 1493 tests, 1493 passed, 0 failed, 0 errors, 0 skipped**
    - [E5ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt)：**14 tests, 14 passed, 0 failed, 0 errors, 0 skipped**
    - `QuickReplyIntegrationBackPriorityTest`（3/3）、`QuickReplyLayoutMatrixTest`（8/8）、`QuickReplyScreenshotTest`（9/9）、`QuickReplySheetTest`（8/8）、`QuickReplyViewModelTest`（6/6）：全通过
    - `MultiPaneBackStackTest`（7/7）、`MultiPaneDividerGestureTest`（9/9）、`MultiPaneGlassBudgetTest`（9/9）、`MultiPaneScreenshotTest`（21/21）、`MultiPaneStateMigrationTest`（10/10）：全通过
    - `CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）：全通过
    - Roborazzi 差异检查（`-Proborazzi.test.verify=true` + `git diff --name-status origin/main...verify/E5-probes`）：既有非 `quick_reply_*.png` 快照 **0** 变更；`quick_reply_landscape_891x411_ime_compact_*.png` 2 张在 `f29ac3b` 随 `QuickReplyAdaptiveSheetLayout` 同步重录并经 CI verify 全绿。

---

## 四、第 2 轮逐项复验明细（14/14 PASS）

| 探针 | 用例名 & 行号 | 轮次结论 | 实测观测 |
|---|---|---|---|
| **探针 1a** | `probe1a_nextAppRoot_imeFrameByFrameRiseAndFall_clampsHeightAndTracksImeZeroLag` ([L282](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L282)) | **PASS** | 合入 `f1b1714` + `9338529` 重跑：`autoAdvance=false` 逐帧 IME 升降零脱节，`min(300dp, 净高×60%)` 钳制与发送/底边边界全过。 |
| **探针 1b** | `probe1b_nextAppRoot_shortScreenUnder180dp_hidesHandleAndDraftBannerZeroClipping` ([L390](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L390)) | **PASS** | `891×411` 横屏叠 `245dp`/`295dp` 键盘，隐藏把手与草稿横条，输入、发送与展开全屏零裁切。 |
| **探针 1c** | `probe1c_reducedMotion_zeroScale_opensDismissesAndExpandsInSingleFrame` ([L524](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L524)) | **PASS** | `withAnimatorDurationScale(0f)` 下开启、关闭、全屏交接单帧到位，不弹「已保存草稿」。 |
| **探针 2a / 2b** | `probe2a_threePane749dp_...` ([L607](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L607)) & `probe2b_threePane933dp_...` ([L613](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L613)) | **PASS** | `749dp`/`933dp` 三栏右栏边界、玻璃预算（`HiGlassMarker <= 3`、`LayerBackdrop <= 2`、嵌套 0、`DrawBackdrop <= 5`）及底栏卸载/恢复全过。 |
| **探针 2c** | `probe2c_hingeTwoPane749dp_quickReplyRightOfHingeAndGlassBudget` ([L619](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L619)) | **PASS** | 含 `isSeparating == true` 垂直铰链双栏右半屏界定、暗罩与玻璃预算全过。 |
| **探针 3a** | `probe3a_mergedBackPriority_...` ([L783](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L783)) | **PASS** | `NextAppRoot` 五层返回栈（看图 > 抽屉 > 快速回复 > 2 条 PositionStack > 三栏右栏）及退场连按防穿透全过。 |
| **探针 3b** | `probe3b_singleBatchHandleDrag_dismissesQuickReplySheet` ([L961](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L961)) | **PASS（B1 闭合）** | [QuickReplySheet.kt#L166-L278](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L166-L278) 以 `isDragging`/`dragOffsetYPx` + `CoroutineStart.UNDISPATCHED` 同步跟踪位移；同批 `down -> moveBy(120dp, 40ms) -> up` 稳定收起面板、弹出「已保存草稿」且草稿 `"单批快速下拉草稿"` 落盘 Room。 |
| **探针 3c & 3d** | `probe3c_gestureIsolation_...` ([L1077](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1077)) & `probe3d_singleBatchTopOverscroll_...` ([L1020](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1020)) | **PASS** | 多行竖滑/选区防误关、把手 `30dp` 回弹、步进/超界下滑收起、「放弃」删库及 `R-D6 S2` 清空不复活全过。 |
| **探针 4** | 快照零漂移与新增图核查 | **PASS** | 既有非 `quick_reply_*.png` 0 漂移；CI `#45772421912` 全量 Roborazzi 校验全绿。 |
| **探针 5** | `probe5_optionalSlotsBinding_...` ([L1279](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1279)) | **PASS** | 未绑定隐藏与三插槽绑定后 `展开全屏` / `插入图片` `FullEditorHandoff` 无损、不重弹草稿提示全过。 |
| **探针 6** | `probe6_mockWebServer_30sCooldown...` ([L1446](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1446)) | **PASS** | 30 秒冷却连点 `MockWebServer` 额外请求数为 0、过期恢复、失败保留重发、只看楼主过滤模式引用（`repquote=101`）全过。 |
| **探针 7（新增）** | `probe7_749x559_ime250And265_draftErrorAllSlotsBound_inputVisibleSendCompleteAndSlotsClickable` ([L1671](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1671)) | **PASS** | `NextAppRoot` 设 `749×559dp`（`FOLD8_LANDSCAPE_749_559` 三栏右栏），预置草稿 + 触发 `发送失败` + 绑定全 3 个插槽：先后测 `IME=250dp / status=0dp`、`IME=265dp / status=0dp` 及 `IME=265dp / status=24dp`。经 [QuickReplyAdaptiveSheetLayout](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L435-L530) 优先级让位，输入框外框 `>= 40dp`、内框高 `>= firstLineHeightPx`（完整显示 `>=1` 行且宽 `>= 72dp`）、`发送` 按钮（`>= 72×48dp`）完整不截断、`插入图片`/`表情`（各 `>= 48×48dp`、`hasClickAction()`）在发送左侧零重叠可点，「发送失败」错误退避 Snackbar 仍可见；实点 `表情 -> 插入假表情 -> 键盘 -> 插入图片` 携带 `"749x559已恢复草稿正文[微笑]"` 与 `openImagePickerImmediately=true` 完成交接。 |
| **探针 8（新增）** | `probe8_fastUpwardDragPastThreshold_withSlotsBound_expandsToFullEditorNoDraftSavedSnackbar` ([L1877](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L1877)) | **PASS** | 三插槽全绑定下：① 把手慢速未越阈值（`-20dp / 220ms`）不触发展开；② 把手（`收起回复面板`）与语境标题行（`引用第 3 楼 · 坛友3`）分别执行单批快速上拉越阈值（`-80dp / 25ms` >= `56dp`），均立即展开全屏，捕获 `FullEditorHandoff`（文本 `"快速上拉展开正文ABCD"` / `"标题行上拉展开EFGH"`、选区 `[2, 8)` / `[4, 4)`、`quotePid="p102"`/`"p103"`、`quoteAuthor="坛友2"`/`"坛友3"`、`quoteFloor=2`/`3`、`openImagePickerImmediately=false`）完全一致，不弹出「已保存草稿」且 `QuickReplyEvent.DraftSaved` 计数为 0。 |

---

# V-E5 第 3 轮复验报告（`a5c9440` + `origin/main@9338529`）

- **结论**：**PASS（建议合入）** — 第 3 轮基于 `a5c9440255516940af46a824715fab6876928621`（`SheetSlotPlan + Column` 纯函数布局与 `launchTerminalTransition` 统一终态调度重写）并合并最新 `origin/main@9338529` 完成全量复验：既有 14 项探针全过，第 3 轮新增动画打断探针 `probe9a` / `probe9b`（入场弹簧中途同帧收起/回弹/上拉展开、退场动画中再拖、展开动画中按返回/点暗罩）全部 **PASS**，共 **16/16 项独立探针全绿**，全仓 1496 项单测与 Roborazzi 截图校验全部通过，既有快照 0 漂移。
- **复验对象**：`card/E5-quick-reply-sheet` @ `a5c9440255516940af46a824715fab6876928621`，合入最新 `origin/main@9338529`。
- **验证分支 / worktree / 提交**：`verify/E5-probes` @ `fce993adf64b89e1482f9b8749929c063c43ac85`（`/home/mir/Documents/hipda-next.wt/V-E5`）。
- **实现方 CI（第 3 轮）**：[Android CI #45773736474](https://github.com/mailwmj/hipda-next/actions/runs/45773736474)（`a5c9440255516940af46a824715fab6876928621`）— `Build, Lint & Unit Tests`（Job `139752279038`）`success`（`TOTAL: 1480 tests, 1480 passed, 0 failed, 0 errors, 0 skipped`；`Emulator Smoke Test` `skipped`）。
- **验证方 CI（第 3 轮）**：[Android CI #45776616381](https://github.com/mailwmj/hipda-next/actions/runs/45776616381)（`verify/E5-probes @ fce993adf64b89e1482f9b8749929c063c43ac85`）— `Build, Lint & Unit Tests`（Job `139761697245`，`17m8s`）**`success`**：
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：**PASS**（第 1 轮提及的 `launch(NonCancellable)` 弃用警告已在 `a5c9440` 改用 `CoroutineStart.UNDISPATCHED` + `withContext(NonCancellable)` 消除）
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`（`-Proborazzi.test.verify=true --continue`）：**TOTAL: 1496 tests, 1496 passed, 0 failed, 0 errors, 0 skipped**
    - [E5ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt)：**16 tests, 16 passed, 0 failed, 0 errors, 0 skipped**
    - `QuickReplyIntegrationBackPriorityTest`（3/3）、`QuickReplyLayoutMatrixTest`（8/8）、`QuickReplyScreenshotTest`（9/9）、`QuickReplySheetTest`（9/9）、`QuickReplyViewModelTest`（6/6）：全通过
    - `MultiPaneBackStackTest`（7/7）、`MultiPaneDividerGestureTest`（9/9）、`MultiPaneGlassBudgetTest`（9/9）、`MultiPaneScreenshotTest`（21/21）、`MultiPaneStateMigrationTest`（10/10）：全通过
    - `CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）：全通过
    - Roborazzi 差异检查：`git diff --name-status origin/main...verify/E5-probes` 核实非 `quick_reply_*.png` 既有快照 **0** 变更，全仓截图校验零差异通过。

---

## 五、第 3 轮复验结果（既有 14 项 + 新增探针 9，16/16 PASS）

| 探针 | 用例名 & 行号 | 第 3 轮结论 | 实测观测 |
|---|---|---|---|
| **既有 1a–1c / 2a–2c / 3a–3d / 4 / 5 / 6 / 7 / 8** | [E5ProbeTest.kt#L282-L2026](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L282-L2026) | **14/14 PASS** | 重写为纯函数 `resolveSheetSlotPlan` + 标准 `Column` 后，`749×559dp`（`250dp`/`265dp` IME + `24dp` 状态栏 + 草稿 + 错误 + 全插槽）、`< 180dp` 矮屏降级、单批下拉收起、快速上拉展开等全部 14 项探针一次性全通过。 |
| **探针 9a（新增）** | `probe9a_midEnterSpring_singleBatchFlicks_dismissReboundExpand_andBackScrimValid` ([L2033](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L2033)) | **PASS** | `autoAdvance = false` 以 `positionInRoot.y` 捕获入场弹簧半途（`delta = posY - openTopPx > 6f`），三项同帧单批打断验证：① 快速下拉 `120dp / 25ms` 打断入场并直接落到关闭终态；② 低速小幅下拉 `14dp / 220ms` 取消入场动画并精准回弹落回 `Open`（`abs(posY - openTopPx) <= 2f`）；③ 绑定三插槽下快速上拉 `-100dp / 25ms` 取消入场并直接展开全屏、触发 1 次 `FullEditorHandoff` 且无「已保存草稿」。三段打断后分别重开验证 **返回键（不穿透详情 `onBackCount == 0`）与点暗罩** 全部持续有效。 |
| **探针 9b（新增）** | `probe9b_midDismissDrag_andMidExpandBack_reachTerminalAndKeepBackScrimValid` ([L2224](file:///home/mir/Documents/hipda-next.wt/V-E5/hipda/src/test/java/net/jejer/hipda/verify/E5ProbeTest.kt#L2224)) | **PASS** | ① **退场中再拖**：`onBackPressed()` 推进 3 帧确认面板已开始下移（`midDismissTopPx > dismissStartTopPx + 2f`）进入 `Dismissing` 后，连续注入反向大距离上拖 `-140dp`、再下拖 `80dp` 及重复按返回键，面板不被拉回或误展开，持续落到退场终态且保存草稿 `"退场再拖草稿"`；② **展开中按返回**：上拉 `-80dp` 进入 `Expanding` 首帧后连按两次 `Back` 并点击暗罩，`Expanding` 吞掉返回与点击（不穿透 `onBackCount == 0`、不转退场、不重发 `QuickReplyEvent.DraftSaved`），精准完成 1 次全屏交接并携带完整文本与选区 `[1, 5)`、`quotePid="p103"`；两段终态后重开验证 **返回键与点暗罩** 仍全部正常收起。 |
