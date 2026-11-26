# 验证卡 V-E2d 验证报告：独立逐帧向上插页零闪跳与连续加载探针（commit c447d4a）

- **总判定**：`PASS`（无阻断项，无 FAIL 触发路径）
- **被测对象**：`origin/card/E2d-thread-continuous-paging` @ `c447d4a`（相对基线 `3dd65c4`）
- **验证工作区与分支**：
  - Worktree：`/home/mir/Documents/hipda-next.wt/V-E2d`（已保留）
  - 分支：`verify/E2d-probes` @ `14feb3b`（`test(verify): 添加 E2d 独立逐帧零闪跳与连续加载探针`）
  - 未修改 `card/E2d-thread-continuous-paging`、`main` 及任何生产/快照文件
- **新增验证测试**：[E2dProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E2d/hipda/src/test/java/net/jejer/hipda/verify/E2dProbeTest.kt)（7/7 通过，100% 离线假 `ThreadDetailRepository`，零访问真实论坛）
- **CI 证据**：
  - [Android CI #45705157159](https://github.com/mailwmj/hipda-next/actions/runs/45705157159)（Job `139525127879`，`Build, Lint & Unit Tests` in 15m8s）— **SUCCESS**
  - 全仓汇总：**`TOTAL: 1053 tests, 1053 passed, 0 failed, 0 errors, 0 skipped`**
    - `:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）：**`955 tests, 955 passed, 0 failed, 0 skipped`**
    - `E2dProbeTest`：**`7 tests, 7 passed, 0 failed, 0 skipped`**
    - Build, Lint, Room Schema 一致性、Roborazzi 快照验证及 Debug/Release APK 产物校验全部通过
- **本地串行校验**（遵循 `docs/agents/DISPATCH.md` §2，`flock /tmp/hipda-gradle.lock`，`-Xmx3g`，`workers.max=4`，结束后 `./gradlew --stop`）：
  - `:core-parser:test`：**`98/98 passed`**
  - `:emoji:testDebugUnitTest`：**`0 tests (NO-SOURCE)`**
  - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`955/955 passed`**（含 `E2dProbeTest` 7/7）
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**；`lint-results-debug.sarif` 中 `threaddetail` 与 `E2dProbe` 均 **0 条告警**

---

## 1. 基线零漂移与边界核查（含方案 B 核对）

1. **生产文件边界**：
   - `git diff --name-status 3dd65c4...c447d4a` 结果严格仅 3 个文件：
     - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`
     - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`
     - `A hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt`
2. **既有快照零漂移**：
   - `git diff --name-status 3dd65c4...c447d4a -- hipda/src/test/snapshots/` 返回**空**（0 张新增/改动/重录快照），与 `_reports/E2d.md` 声明的“0 张重录”完全一致。
   - 本地与 CI 跑通 `-Proborazzi.test.verify=true`，全部既有 `thread_detail_*.png` 及全仓截图基线 **0 漂移通过**。
3. **方案 B 裁定核对**：
   - `ThreadDetailPaginationBar` 按方案 B 保留（`ThreadDetailPane.kt:323-330,355-374`），由 `visiblePage`（基于 `firstVisibleFloor(listState)`）实时驱动并在连续加载跨页滚动后与当前首可见楼层所在页码一致（已由 `probeB` 实测锁定）。

---

## 2. 探针 (a)：向上插页首帧零闪跳反伪造与 LaunchedEffect 反例实测

所有帧探针均在 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`（确保中文多行文本真实排版折行）及 `composeRule.mainClock.autoAdvance = false` 下运行，使用 `composeRule.mainClock.advanceTimeByFrame()` 逐帧推进并读取 Compose 语义节点 `boundsInRoot.top`（`xhdpi`，`1dp = 2px`）。

假数据 `FakeContinuousThreadDetailRepository` 构造 3 页离线楼层（第 1 页 15 楼 `p101..p115`、第 2 页 15 楼 `p201..p215`、第 3 页 10 楼 `p301..p310`），同页混排**短楼**（单行简评，高 ~`99dp`）、**长楼**（多行正文，高 ~`231.5dp`）与**带 `4:3` 图片占位楼层**（`ContentBlock.Image` → `PostImageBlock`，总高 ~`393.5dp`）。

### 2.1 生产实现（`c447d4a`）实测坐标表

#### 场景 A1：`probeA1_upwardPrepend_firstFrameZeroJump_mixedHeights_underManualClock`
- 触发路径：第 2 页真手势上划触发第 1 页预取，定格在长楼 `p202`（`17楼`，带 `-18px` 偏移）为首可见楼层、下方紧邻 `4:3` 图片占位楼层 `p203`（`18楼`），完成 `page1Gate` 后在首次 `advanceTimeByFrame()` 立即捕获合入帧并继续推进 11 帧。

| 帧序 | 阶段 | 已载楼层数 | 首可见楼层 Key | `p202` (`17楼`) `top (px)` | `p202` (`17楼`) `top (dp)` | 相对合入前偏差 `delta (dp)` | 邻接 4:3 图楼 `p203` `top (dp)` |
|---|---|---|---|---|---|---|---|
| 0 | `PRE_MERGE`（合入前一帧） | 15 | `floor:p202` | `149.0` | `74.5` | `0.0` | `306.0` |
| 1 | **`MERGE_FRAME`（合入首帧）** | **30** | `floor:p202` | **`149.0`** | **`74.5`** | **`0.0`（≤ 1dp）** | **`306.0`** |
| 2 | `POST_MERGE_1`（合入后第 1 帧） | 30 | `floor:p202` | `149.0` | `74.5` | `0.0` | `306.0` |
| 3 | `POST_MERGE_2`（合入后第 2 帧） | 30 | `floor:p202` | `149.0` | `74.5` | `0.0` | `306.0` |
| 4–12 | `POST_MERGE_3..11`（后续 9 帧） | 30 | `floor:p202` | `149.0` | `74.5` | `0.0` | `306.0` |

#### 场景 A2：`probeA2_upwardPrepend_positiveOffsetWithVisibleLoadingHeader_zeroJump`
- 触发路径：顶部 `正在加载…`（`KEY_PREPEND_HEADER`）完全露出在 `p201`（`16楼`）上方，使 `p201` 处于正偏移 `offset > 0`；验证 `PrependAnchorController` 在 `SideEffect` + 同帧 `onGloballyPositioned` `dispatchRawDelta` 下合入第 1 帧零跳变（`p115` 4:3 图楼无缝填补加载行位置）。

| 帧序 | 阶段 | 已载楼层数 | 首可见楼层 Key | `p201` (`16楼`) `top (px)` | `p201` (`16楼`) `top (dp)` | 相对合入前偏差 `delta (dp)` | 邻接 4:3 图楼 `p203` `top (dp)` |
|---|---|---|---|---|---|---|---|
| 0 | `PRE_MERGE`（合入前一帧） | 15 | `floor:p201` | `263.0` | `131.5` | `0.0` | `462.0` |
| 1 | **`MERGE_FRAME`（合入首帧）** | **30** | `floor:p115` | **`263.0`** | **`131.5`** | **`0.0`（≤ 1dp）** | **`462.0`** |
| 2 | `POST_MERGE_1`（合入后第 1 帧） | 30 | `floor:p115` | `263.0` | `131.5` | `0.0` | `462.0` |
| 3 | `POST_MERGE_2`（合入后第 2 帧） | 30 | `floor:p115` | `263.0` | `131.5` | `0.0` | `462.0` |
| 4–12 | `POST_MERGE_3..11`（后续 9 帧） | 30 | `floor:p115` | `263.0` | `131.5` | `0.0` | `462.0` |

#### 场景 A5：`probeA5_upwardPrepend_whileTouchDragging_zeroJump`
- 触发路径：手指按下拖动触发第 1 页预取并在 `listState.isScrollInProgress == true` 期间合入第 1 页，直接覆盖 `ThreadDetailPane.kt:89` 的 `!(primaryKey == anchorKey && listState.isScrollInProgress)` 分支（`p202` 首可见且未被 `y=0` 裁剪）。

| 帧序 | 阶段 | 已载楼层数 | 首可见楼层 Key | `p202` (`17楼`) `top (px)` | `p202` (`17楼`) `top (dp)` | 相对合入前偏差 `delta (dp)` | 邻接 4:3 图楼 `p203` `top (dp)` |
|---|---|---|---|---|---|---|---|
| 0 | `PRE_MERGE`（合入前一帧） | 15 | `floor:p202` | `127.0` | `63.5` | `0.0` | `295.0` |
| 1 | **`MERGE_FRAME`（合入首帧）** | **30** | `floor:p202` | **`127.0`** | **`63.5`** | **`0.0`（≤ 1dp）** | **`295.0`** |
| 2 | `POST_MERGE_1`（合入后第 1 帧） | 30 | `floor:p202` | `127.0` | `63.5` | `0.0` | `295.0` |
| 3 | `POST_MERGE_2`（合入后第 2 帧） | 30 | `floor:p202` | `127.0` | `63.5` | `0.0` | `295.0` |
| 4–5 | `POST_MERGE_3..4` | 30 | `floor:p202` | `127.0` | `63.5` | `0.0` | `295.0` |

### 2.2 自写 `LaunchedEffect` 反例自证（探针稳定性校验）

在测试内构造 `NaiveLaunchedEffectPrependList`（保留同等楼层卡片、短/长/4:3 图混排，仅把同步 `SideEffect` 锚定替换为 `LaunchedEffect` 补偿）并代入同一套逐帧捕获与断言函数 `assertZeroJumpAcrossAllFrames(..., maxAllowedDp = 1f)`：

1. **反例 A3（`probeA3_counterExample_keyedWithPrependHeader_launchedEffectMustFailOnMergeFrame`）**：保留 `floor:$postId` 主键与 `KEY_PREPEND_HEADER`，改用 `LaunchedEffect` 在合入后调用 `scrollToItem` 并恢复加载头正偏移：
   - Frame 0（`PRE_MERGE`）：`p201` `top = 263.0px (131.5dp)`，`delta = 0.0dp`
   - Frame 1（**`MERGE_FRAME`**）：`p201` 向上跳至 `167.0px (83.5dp)`，**实测偏移 `48.0dp > 1dp` → 探针立即判 `FAIL`**
   - Frame 2（`POST_MERGE_1`）：`LaunchedEffect` 补偿回 `263.0px (131.5dp)`（`delta = 0.0dp`），证明仅用 `waitForIdle()` 会漏判该首帧闪跳
2. **反例 A4（`probeA4_counterExample_twoFloorsLaunchedEffect_measuresExactPxDpJumpOnMergeFrame`）**：顶部插入 2 楼（1 条长楼 + 1 条 4:3 图片楼）并由 `LaunchedEffect` 在首帧绘制完成（`withFrameNanos`）后才做 `scrollToItem` 补偿（注：在 `v2.createComposeRule` 的 `StandardTestDispatcher` 下，若不显式跨越 `withFrameNanos` 帧边界，`advanceTimeByFrame()` 会同 tick 排空无挂起的测试协程从而掩盖真实 `AndroidUiDispatcher` 帧滞后）：
   - Frame 0（`PRE_MERGE`）：`p202` `top = 155.0px (77.5dp)`，`delta = 0.0dp`
   - Frame 1（**`MERGE_FRAME`**）：`p202` 向下跳至 `1140.0px (570.0dp)`，**实测偏移 `492.5dp > 1dp` → 探针立即判 `FAIL`**
   - Frame 2（`POST_MERGE_1`）：`LaunchedEffect` 拉回 `155.0px (77.5dp)`（`delta = 0.0dp`）

---

## 3. 探针 (b) & (c) 实测结论

- **探针 (b) 向下 5 楼自动预取、并发防重、末页态与底栏一致性（`probeB_...`，PASSED）**：
  - 距末楼 `> 5` 楼时未预取第 3 页（0 次请求）；滚动进入距末楼 `≤ 5` 楼时自动发出恰好 **1** 次第 3 页预取；
  - 第 3 页在途期间连续 4 次小幅向下拖动，向假 `ThreadDetailRepository` 发起的第 3 页请求始终为 **1** 次（0 重复）；
  - 合入第 3 页并滚至底部后展示 `没有更多了`（`HiFooterState.End`），继续底部上划 0 次第 4 页请求；
  - 底部 `ThreadDetailPaginationBar` 全程随 `firstVisibleFloor` 所在页实时同步：`第 2 / 3 页` → 滚入第 3 页为 `第 3 / 3 页` → 退回第 2 页中部触发并合入第 1 页后保持 `第 2 / 3 页` → 滚入第 1 页后切为 `第 1 / 3 页`。
- **探针 (c) 头/尾加载失败原地整行重试、玻璃拓扑与主键守卫（`probeC_...`，PASSED）**：
  - 向下与向上预取分别注入 `NetworkError` 后：已展示楼层（15 楼 / 25 楼）**不清空**，对应端原地渲染整行 `加载失败，点击重试`，实测触控区宽/高均 `≥ 48dp` 且具备点击语义；点击整行分别成功补载第 3 页（合入至 25 楼）与第 1 页（合入至 40 楼）；
  - 全过程（常规浏览、尾部失败态、尾部重试后、头部失败态、头部重试后）持续断言：`HiGlassMarker == 1`、`HiGlassBlurActive == 1`、`LayerBackdrop == 1`（严格仅在 `LazyColumn`）、`DrawBackdrop == 1`（严格仅在 `ThreadDetailTopBar`）、两者互不为祖先/后代（严格兄弟拓扑）、楼层项主键严格保持 `floor:$postId`。
