# 任务卡 E2d 交付报告：帖子详情双向连续加载与向上插页同帧零闪跳

- **状态**：`DONE`（按负责人拍板执行**方案 B**：保留 `ThreadDetailPaginationBar` 至 `E4` 再移除并统一重录快照，且底栏页码与连续加载后当前可见楼层所在页实时同步；既有测试 0 改动、既有快照 0 漂移）
- **Worktree**：`/home/mir/Documents/hipda-next.wt/E2d`
- **分支**：`card/E2d-thread-continuous-paging`
- **基线提交**：`3dd65c4`（`origin/main`，已含 `E0`、`E2a`、`E15`）
- **最终提交（HEAD）**：`c447d4add612353052c32c654ad330d6a07b9cbb`（`feat(threaddetail): 实现帖子详情双向连续加载与向上插页同帧锚定`）
- **GitHub Actions CI 证据**：
  - [Android CI #45697095253](https://github.com/mailwmj/hipda-next/actions/runs/45697095253) — `Build, Lint & Unit Tests`（Job `139497221293`）**SUCCESS**（10m19s）
  - 全仓单测与 Roborazzi 校验汇总：**`TOTAL: 1046 tests, 1046 passed, 0 failed, 0 errors, 0 skipped`**
    - `:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）：**`948 tests, 948 passed, 0 failed, 0 skipped`**
    - `ContinuousPagingAnchorTest`：**8/8 通过**
    - `ThreadDetailViewModelTest`：**8/8 通过**（既有测试未改动）
    - `ThreadDetailScreenshotTest`：**15/15 通过**（既有 `thread_detail_*.png` **0 张漂移、0 张重录**）
    - `PostBodyRendererTest`：**6/6 通过**、`TextColorContrastAdjusterTest`：**5/5 通过**
    - `NextAppTracerBulletTest`：**9/9 通过**
    - `CopyTerminologyScanTest`：**3/3 通过**、`MotionTokenScanTest`：**7/7 通过**（白名单 0 增长）
  - Build & Lint、Room schema 一致性校验、Debug/Release APK 产物校验全部通过。
- **本地串行校验（遵循 `docs/agents/DISPATCH.md` §2，`flock /tmp/hipda-gradle.lock`，`-Xmx3g`，`workers.max=4`，临界区内 `./gradlew --stop`）**：
  - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`948 tests, 948 passed, 0 failed, 0 skipped`**
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**
  - SARIF 核查（`hipda/build/reports/lint-results-debug.sarif`）：`feature/threaddetail/**` **0 条 Lint 告警/错误**。

---

## 1. 边界合规、代码体量与组件规范核对

- **严格 2 生产文件边界**：`git diff --name-status 3dd65c4...HEAD` 仅包含 3 个文件：
  - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`
  - `A hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt`
- **零越界与并行隔离**：
  - 与同波并行的 `E2b`（`feature/threaddetail/media/*`、`thread_image_*.png`）及 `E3`（`feature/app/*`、`ui/shell/*`、`multipane_*.png`）**100% 零文件重叠**；
  - 零触碰 `PostFloorCard.kt`、`body/*`、`media/*`、`ThreadDetailTopBar.kt`、`FilterCapsuleBar.kt`、`strings_*.xml`、`data/`、`ui/`、`ThreadDetailViewModelTest.kt`、`ThreadDetailScreenshotTest.kt`、既有快照及任何共享构建/清单文件。
- **生产代码行数核对**：
  - `ThreadDetailViewModel.kt`：`383` 行（基线 `288` 行，净增 **`+95` 行**）
  - `ThreadDetailPane.kt`：`374` 行（基线 `187` 行，净增 **`+187` 行**）
  - 两文件合计 `757` 行，相对基线 `475` 行净增 **`+282` 行**（远低于卡估计 1.5× 上限 `390` 行；略高于上限 `260` 行系按方案 B 保留 `ThreadDetailPaginationBar` 并新增可见楼层页码同步逻辑所致）。
- **界面卡组件与纯净度规范**：
  - 全部使用 Compose 标准组件与标准 API（`LazyColumn`、`SideEffect`、`Modifier.onGloballyPositioned`、`NestedScrollConnection`、`snapshotFlow`、`derivedStateOf`、`HiListFooter`），**无手写 `Layout`、`Modifier.Node` 或命令队列**；
  - 生产代码**不含任何测试专用** `testTag`、`SemanticsPropertyKey`、钩子或 `CompositionLocal`；
  - 既有 15 张 `thread_detail_*.png` 快照 **0 张漂移**（无需重录）。

---

## 2. 核心设计说明

### 2.1 向上插页同帧零闪跳锚定（闭合 R-BATCH5 B4-E2a ①）
- **彻底杜绝滞后一帧的 `LaunchedEffect` 回滚**：向上合入新页楼层时不使用任何 `LaunchedEffect(floors)` 补偿，改由 `PrependAnchorController` 在数据合入的**同一帧**完成锚定：
  1. **原子状态发布（`ThreadDetailViewModel.kt:126-143`）**：将 `rawFloors` 收拢进 `DetailMetaState` 并在 `combine(metaState, observedFloors, challengeState)` 中过滤掉 `floors` 与 `m.rawFloors` 尚未同步的中间态，确保 `floors`、`firstLoadedPage`、`isPrepending` 与 `prependGeneration` 在同一原子帧进入 Compose，杜绝拆帧撕裂；
  2. **同帧 `SideEffect`（`measureAndLayout` 之前，`ThreadDetailPane.kt:59-88,266`）**：在合入新页（`prependGeneration` 递增）或顶部加载行显隐变化的同一帧 `SideEffect` 中，通过 `firstVisibleFloor(listState)`（纯基于 `listState.layoutInfo.visibleItemsInfo` 中 `item.offset + item.size > 0` 的首个 `floor:` 项，排除躲在 `contentPadding(top)` 透明顶栏背后的上一页尾楼）定位锚点 `postId` 与 `anchorOffset`：
     - 当 `anchorOffset <= 0`（正常贴顶或局部滚出）：在 `measureAndLayout` 前同步调用 `listState.requestScrollToItem(newListIndex, -anchorOffset)`（若原首项为同一楼层且正处于拖动/滑行，交由 `LazyListScrollPosition` 按稳定 `key = "floor:$postId"` 同帧重定位并保留滑行速度）；
     - 当 `anchorOffset > 0`（顶部 `HiListFooter` 露出在首可见楼层上方，例如点击顶部失败行重试后合入）：先同步 `requestScrollToItem(newListIndex, 0)`，再在**同一帧**布局收尾的 `Modifier.onGloballyPositioned` 回调中通过 `listState.dispatchRawDelta(-delta.toFloat())` 触发同步重测，按实际测出的上一页尾楼高度精确回填正偏移，保证**合入第 1 帧渲染前**目标楼层即稳停于原屏幕像素坐标（实测偏差 `0.0dp ≤ 1dp`）。

### 2.2 双向连续预取、防重入与头尾独立错误恢复
- **向下自动预取**：`LaunchedEffect(listState)` 监听向下滚动位移，当距末尾楼层 `≤ 5` 楼（`lastFloorIndex >= totalFloors - 1 - 5`）且 `hasNextPage` 时自动调用 `loadNextPage()`；短页贴底继续上滑时由 `NestedScrollConnection.onPostScroll(available.y < 0f)` 兜底触发。
- **向上自动预取**：仅当 `firstLoadedPage > 1` 且用户主动向上滚近头部（`firstFloorIndex <= 5`）或在已载头部继续下拉（`NestedScrollConnection.onPostScroll(available.y > 0f)`）时触发 `loadPreviousPage()`，首次跨页落顶或 `pendingScroll` 恢复位均不误触发预取。
- **并发防重与防串页**：头/尾拆分为独立 `prependJob` / `appendJob` 及独立状态字段（`isPrepending`/`prependError`、`isAppending`/`appendError`），辅以 `inFlightPages: HashSet<Pair<Long, Int>>`（`sessionGeneration to targetPage`）实现同方向与同页码单飞保护，避免跨会话取消时 `finally` 误删新会话在途页码；跨页跳转或切换 `只看楼主` 时递增 `sessionGeneration` 并立即取消在途预取。
- **头尾状态行与底栏页码同步**：复用 `HiListFooter` 渲染头部 `Loading`/`Failed`（整行可点重试，顶部首楼处于 `offset == 0` 且首次向上失败时自动露出失败行）与尾部 `Loading`/`Failed`/`End`（`没有更多了`）；`visiblePage` 通过 `derivedStateOf` 读取 `firstVisibleFloor(listState)` 对应楼层的 `floor.page` 实时驱动 `ThreadDetailPaginationBar` 并同步回 `ViewModel.onVisiblePageChanged(visiblePage)`。

---

## 3. 验收项逐条对照与测试清单

1. **向上插页逐帧零闪跳（B4-E2a ①，最高优先级） — 通过**
   - `ContinuousPagingAnchorTest.upwardPrepend_fromTopOfPage2_zeroJumpOnEveryFrameIncludingFirstMergeFrame`（`offset == 0`，从加载启动到 `autoAdvance = false` 合入首帧及后续 15 帧逐帧断言偏差 `≤ 1dp` 且实测命中合入首帧）
   - `ContinuousPagingAnchorTest.upwardPrepend_whenTopFooterVisibleAboveAnchor_positiveOffsetZeroJumpEveryFrame`（`offset > 0` 顶部失败行露出 → 点击重试 → `autoAdvance = false` 合入首帧及后续 15 帧逐帧断言偏差 `≤ 1dp`）
   - `ContinuousPagingAnchorTest.upwardPrepend_withPartialScrollOffset_preservesPixelOffsetOnEveryFrame`（`offset < 0` 带 34px 局部偏移，逐帧断言 `≤ 1dp`）
2. **向下 5 楼预取、并发防重与头尾失败重试 — 通过**
   - `ContinuousPagingAnchorTest.downwardPrefetchWithin5Floors_singleFlightDedupAndEndFooter`（验证 `> 5` 楼不预取、`≤ 5` 楼自动预取、在途重复滚动 0 重复发包、`postId` 去重、末页展示 `没有更多了` 且继续下滑 0 请求）
   - `ContinuousPagingAnchorTest.headAndTailFailures_preserveLoadedFloorsAndRetryIndependently`（验证头/尾各自失败保留已载楼层、展示 `≥ 48dp` 整行可点 `加载失败，点击重试`、失败态滚动不刷包、点击整行成功补载）
   - `ContinuousPagingAnchorTest.concurrentOppositeDirectionLoads_mergeIndependentlyWithoutInterference`（验证双向并发加载互不干扰且防重）
   - `ContinuousPagingAnchorTest.nonAdjacentJumpAndAuthorFilter_resetLoadedRangeAndDropStaleInFlightPages`（验证跨页跳页与 `只看楼主` 切换重置区间并丢弃过期在途预取）
3. **【负责人追加】底栏页码与连续加载后当前可见楼层所在页保持一致 — 通过**
   - `ContinuousPagingAnchorTest.paginationBarPageIndicator_staysSyncedWithVisibleFloorPageAfterContinuousPaging`（覆盖向下连续加载 1→2→3→1 页及从第 2 页向上预取第 1 页后滚入第 1 页的底栏页码与按钮可用态同步）
4. **严格 2 文件边界与全量回归 — 通过**
   - 本地与 GitHub Actions `ThreadDetailViewModelTest`（8/8）、`ThreadDetailScreenshotTest`（15/15，0 漂移）、`PostBodyRendererTest`（6/6）、`NextAppTracerBulletTest`（9/9）、`CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7，白名单 0 增长）、全量 `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`（948/948）、全仓 `1046/1046`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 全绿。

---

## 4. 对外 API 清单（签名 + 文件:行号，供 `E2c` / `E4` 复用）

### 4.1 `ThreadDetailViewModel.kt`（`hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`）
- `sealed interface ThreadDetailFilterUi`（`:25`）：`All`（`:26`）、`AuthorOnly`（`:27`）、`UserOnly`（`:28`）
- `data class PendingScrollTarget(val id: Long, val postId: String?, val scrollOffset: Int = 0)`（`:38`）
- `data class ThreadDetailUiState(...)`（`:41`，保留原有 13 个字段与默认值，尾部新增 8 个连续加载字段）：
  - `val firstLoadedPage: Int = 1`（`:55`）
  - `val lastLoadedPage: Int = 1`（`:56`）
  - `val isPrepending: Boolean = false`（`:57`）
  - `val isAppending: Boolean = false`（`:58`）
  - `val prependError: UiError? = null`（`:59`）
  - `val appendError: UiError? = null`（`:60`）
  - `val prependGeneration: Long = 0L`（`:61`）
  - `val resetEpoch: Long = 0L`（`:62`）
  - 派生属性：`val hasPreviousPage: Boolean`（`:64`）、`val hasNextPage: Boolean`（`:65`）、`val showPrependHeader: Boolean`（`:66`）
- `@HiltViewModel class ThreadDetailViewModel`（`:102`，保留 2 参主构造与 `@Inject` 3 参次构造 `:108`）：
  - `val uiState: StateFlow<ThreadDetailUiState>`（`:130`）
  - `fun bindArgs(args: ThreadNavArgs)`（`:145`）
  - `fun loadPage(page: Int, priority: AccessPriority = AccessPriority.UserBlocking)`（`:161`）
  - `fun previousPage()`（`:164`） / `fun nextPage()`（`:169`） / `fun retry(priority: AccessPriority = AccessPriority.UserBlocking)`（`:174`）
  - 新增双向连续加载与重试入口：
    - `fun loadPreviousPage(priority: AccessPriority = AccessPriority.UserBlocking)`（`:177`）
    - `fun loadNextPage(priority: AccessPriority = AccessPriority.UserBlocking)`（`:180`）
    - `fun retryPrepend(priority: AccessPriority = AccessPriority.UserExplicit)`（`:183`）
    - `fun retryAppend(priority: AccessPriority = AccessPriority.UserExplicit)`（`:186`）
    - `fun onVisiblePageChanged(visiblePage: Int)`（`:189`）
  - `fun toggleAuthorOnly(anchorPostId: String? = null, anchorScrollOffset: Int = 0)`（`:196`）
  - `fun clearFilter()`（`:210`） / `fun consumePendingScroll(tokenId: Long)`（`:218`）

### 4.2 `ThreadDetailPane.kt`（`hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`）
- `@Composable fun ThreadDetailPane(...)`（`:133`，顶层参数签名与 `E0`/`E2a` 100% 保持不变；楼层 `LazyColumn` 主键锁定 `key = { "floor:${it.postId}" }` `:281`；`detailGlassState` 严格兄弟拓扑 `HiGlassMarker == 1` 与 `PostImageBlock` 接缝完全不变）

---

## 5. `git diff --stat 3dd65c4...HEAD`

```text
 .../hipda/feature/threaddetail/ThreadDetailPane.kt | 217 +++++-
 .../feature/threaddetail/ThreadDetailViewModel.kt  | 229 +++++--
 .../threaddetail/ContinuousPagingAnchorTest.kt     | 744 +++++++++++++++++++++
 3 files changed, 1108 insertions(+), 82 deletions(-)
```


---

## 6. 修复轮 1（R-E2d B1/S1 + V-E2b E2b+E2d 同帧叠加竞态）

- **状态**：`DONE`
- **已合并基线**：`origin/main` @ `c0fc15d`（已含 `E1` 与 `E2b`，无冲突合并提交 `2a7d6b6`）
- **本轮提交**：
  - `c6fd52c`：`fix(threaddetail): 修复向上插页首帧探针与反例自证并补齐切帖滚顶`（R-E2d B1 + S1）
  - **`f8b5d2a4bff272f48608c7f2a2479a2ea8859f51`（`f8b5d2a`，最终 HEAD）**：`fix(threaddetail): 以单帧单一滚动权威消除 E2b+E2d 叠加竞态并接入合并探针`
- **GitHub Actions CI 证据（最终 HEAD `f8b5d2a`）**：
  - [Android CI #45708732156](https://github.com/mailwmj/hipda-next/actions/runs/45708732156) — `Build, Lint & Unit Tests`（Job `139537320021`）**SUCCESS**
  - 全仓单测与 Roborazzi 校验汇总：**`TOTAL: 1119 tests, 1119 passed, 0 failed, 0 errors, 0 skipped`**
    - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`1021/1021`**；`:core-parser:test`：**`98/98`**
    - `ContinuousPagingAnchorTest`：**15/15 通过**（含 3 条向上插页逐帧零跳动 + 2 条 B1 反例自证 + 1 条 S1 切帖滚顶 + 4 条 V-E2b 合并探针 + 5 条双向连续加载/底栏同步/过滤测试）
    - `ThreadDetailScreenshotTest`：**15/15**（0 漂移）、`ThreadDetailViewModelTest`：**8/8**、`ImageScrollCompensationTest`：**2/2**、`PostImageBlockTest`：**8/8**、`PostImageScreenshotTest`：**6/6**（0 漂移）、`CopyTerminologyScanTest`：**3/3**、`MotionTokenScanTest`：**7/7**
- **本地串行校验（`DISPATCH.md` §2，`flock /tmp/hipda-gradle.lock`，`-Xmx3g`，`workers.max=4`，临界区内 `./gradlew --stop`）**：
  - `:hipda:testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**（`:hipda` 1021/1021，SARIF 对 `feature/threaddetail/**` 命中 **0** 条告警/错误）。

### 6.1 B1 修复与双反例自证实测数据（`ContinuousPagingAnchorTest.kt`）

- **观测机制修复**：
  1. 在测试树挂载旁路 `PrependFrameProbe`（`Spacer` 随 `probeStep % 2` 逐帧翻转 `1px ↔ 2px` + `Modifier.onLayoutRectChanged(debounceMillis = 0L, throttleMillis = 0L)`），每推进一帧前显式调用 `Snapshot.sendApplyNotifications()` 再 `mainClock.advanceTimeByFrame()`。回调在 `AndroidComposeView.measureAndLayout()` 末尾的 `RectManager.dispatchCallbacks()` 触发——严格晚于 `dispatchOnPositionedCallbacks()`，且严格早于 `root.draw()`、`advanceTimeByFrame()` 尾部的 `scheduler.runCurrent()`（`LaunchedEffect` 恢复）以及下一帧 `requestLayout`。
  2. 每帧语义树 `boundsInRoot.top` 读取一律包裹在 `composeRule.runWithoutImplicitWait { ... }` 中，彻底禁止 `fetchSemanticsNode()` 隐式触发 `waitForIdle()` / 下一帧 `onLayout`。
  3. 正式实现 `ThreadDetailPane` 全部 3 条向上插页逐帧用例（`offset == 0`、`offset > 0`、`offset < 0`）在合入第 1 帧及后续 15 帧内均 **PASS**（偏移 `0.0dp ≤ 1dp`）。
- **两个反例实测失败数据（同一校验器 `verifyPrependMergeFrameByFrame`）**：
  1. **反例 1（`CounterexampleLaunchedEffectPane`：`LaunchedEffect(prependGeneration)` 内调 `scrollToItem + dispatchRawDelta` 补偿）** — **在合入第 1 帧 FAIL**：
     - 基线（`xhdpi`，`density = 2.0`）：原首楼 `floor:5101_p2_1` 位于顶部加载行下方 **`+96px`（`+48.0dp`）**。
     - **失败帧**：**第 1 帧**（精确命中合入首帧 `floorsCount = 40, layoutTotalItemsCount = 40, firstLoadedPage = 1`）。
     - **实测偏移**：traversal 探针读得 `anchorOffsetPx = null`、`anchorIndex = null`、`firstVisibleKey = floor:5101_p1_1`——原首楼被新合入的 20 楼整屏顶出 `891dp` 视口（偏移 `> 1 屏 / 20 楼`），立即触发 FAIL。
     - **防假绿自证**：同次 `advanceTimeByFrame()` 返回后（尾部 `scheduler.runCurrent()` 执行完 `LaunchedEffect` 补偿），`listState` 已将锚点拉回 **`+96px`（`0.0dp` 偏差）**；旧测试会假绿，而新探针在第 1 帧上屏瞬间将其精准拦截。
  2. **反例 2（`CounterexampleDeferredRequestScrollPane`：保留 `SideEffect` 落位，在 `onGloballyPositioned` 中将 `dispatchRawDelta` 改调为无同步重测的 `requestScrollToItem`）** — **在合入第 1 帧 FAIL**：
     - 基线：原首楼 `floor:5102_p2_1` 位于 **`+96px`（`+48.0dp`）**。
     - **失败帧**：**第 1 帧**（精确命中合入首帧 `floorsCount = 40, layoutTotalItemsCount = 40, firstLoadedPage = 1`）。
     - **实测偏移**：`SideEffect` 将 `floor:5102_p2_1` 落位至 `anchorIndex = 20, anchorOffsetPx = 0px`，`onGloballyPositioned` 改调 `requestScrollToItem` 仅排入下一帧未触发同步二次重测，traversal 探针测得精确偏移 **`96px`（`48.0dp`）**；同帧 `runWithoutImplicitWait` 读取语义树 `boundsInRoot.top` 同样测得精确偏移 **`96.0px`（`48.0dp`）**，立即触发 FAIL。

### 6.2 S1 修复（`ThreadDetailViewModel.kt:145-160`）

- `bindArgs(args)` 新增 `val isRebind = boundTid != null`，向 `loadInternal` 传 `scrollToTop = isRebind`：首次绑定保持 `false`（不干扰预设偏移的既有截图测试），复用同一 VM/`LazyListState` 切换新 `tid` 时自动滚动复位至顶部。
- 新增单测 `ContinuousPagingAnchorTest.bindArgs_rebindingToNewTid_resetsScrollPositionToTop` **PASS**。

### 6.3 E2b + E2d 同帧叠加竞态修复：基于 `LazyListLayoutInfo` 帧纪元的单一滚动权威

- **生产改动（最小化，仅 `PostImageBlock.kt` + `ThreadDetailPane.kt`）**：
  1. **`PostImageBlock.kt:87-113, 166, 238-248`**（`+25/-5` 行，保持 5 参公开签名与独立可用性不变）：
     - `classifyHeightChangeBranch` 改为从同一不可变 `listState.layoutInfo.visibleItemsInfo` 快照中取 `firstVisibleItem = visibleItems.firstOrNull { it.offset + it.size > 0 } ?: visibleItems.first()` 进行同代比较，不再把旧 `layoutInfo` 中的 `floorItem.index` 与未重测的 `listState.firstVisibleItemIndex` 跨代比较。
     - 新增 `internal fun interface AboveViewportHeightCompensationAuthority` 与 `internal val LocalAboveViewportHeightCompensationAuthority`（默认 `null`，独立调用 `PostImageBlock` 沿用原有直调逻辑）；在 `ThreadDetailPane` 内进入 `ABOVE_VIEWPORT_COMPENSATE` 分支时，将 `(floorPostId, deltaPx)` 统一委托给单一滚动权威，不再各自直调 `requestScrollToItem`。
  2. **`ThreadDetailPane.kt:62-174, 229-236, 342-370`**：
     - `PrependAnchorController` 升级为详情列表同帧单一滚动权威，通过 `CompositionLocalProvider(LocalAboveViewportHeightCompensationAuthority provides scrollCompensationAuthority)` 注入 `LazyColumn` 子树（`detailGlassState` 严格兄弟拓扑零变化）。
     - 以不可变 `LazyListLayoutInfo` 对象引用身份（`activeLayoutInfo !== layoutInfo`）作为**未重测帧纪元**：每次 `measureAndLayout` 产生新 `LazyListMeasureResult` 实例后，上一帧累计的 `activeAnchorKey` / `activeTargetIndex` / `activeTargetScrollOffset` / `activeImageDeltaPx` 自动归零失效；跨会话（`!sameSession`）亦主动清空。
- **为什么既不会漏补偿、也不会重复补偿**：
  1. **同帧先插页、后图片变高（场景 1A）**：`syncBeforeMeasure` 在纪元 `L0` 登记 `activeAnchorKey = "floor:p201", activeTargetIndex = 15, activeTargetScrollOffset = 820` 并调 `requestScrollToItem(15, 820)`；同帧 `PostImageBlock` 按 `L0` 正确归类为 `ABOVE_VIEWPORT_COMPENSATE` 并报告 `+948px`，权威命中 `activeAnchorKey == "floor:p201"`，在 `(15, 820)` 基础上合成 `(15, 1768)`，一次重测即准确到位。
  2. **同帧先图片变高、后插页（场景 1B）**：`PostImageBlock` 在纪元 `L0` 登记 `activeImageDeltaFloorKey = "floor:p201", activeImageDeltaPx = +948` 并调 `requestScrollToItem(1, 1768)`；同帧 `syncBeforeMeasure` 仍处 `L0`，以 `-anchorOffset (820) + sameFrameImageDelta (948) = 1768` 合成 `requestScrollToItem(15, 1768)`（即便滑行中因 `sameFrameImageDelta != 0` 也不会因 `lastKnownFirstItemKey` 被清而跳过），绝不被旧 `anchorItem.offset` 覆盖归零；同一楼层多张越顶图同帧完成时亦线性累加，任意排列结果恒等。
  3. **隔帧（场景 1C：第 1 帧插页 → 第 2 帧图片变高；或反序）**：第 1 帧 `measureAndLayout` 完成后 `listState.layoutInfo` 切换为新对象 `L1`，`ensureLayoutEpoch(L1)` 自动清空上一帧的 `activeAnchorKey` 与 `activeImageDeltaPx`，第 2 帧基于 `L1` 中已生效的新 `index`/`offset` 独立计算，数学上杜绝跨帧重复叠加。
  4. **跨楼层（场景 2：`p202` 为首可见楼层、`p201` 在 `beforeContentPadding` 内变高）**：`L0` 中 `p201.offset + p201.size <= 0`，`firstVisibleItem` 为 `p202`，`p201.index < p202.index` 直接落入 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP`（仅 `snapTo` 比例、0 滚动请求），`PrependAnchorController` 仅锚定 `p202`，无过度补偿。
- **合并探针正式接入测试（均用 B1 `PrependFrameProbe` + `runWithoutImplicitWait` 逐帧断言 `≤ 1dp`）**：
  - `ContinuousPagingAnchorTest.e2bE2dMerge_sameFrame_prependThenAboveViewportImage_zeroJumpOnEveryFrame` — **PASS**（原下跳 `473.5dp` → 全 20 帧 `≤ 1dp`）
  - `ContinuousPagingAnchorTest.e2bE2dMerge_sameFrame_aboveViewportImageThenPrepend_zeroJumpOnEveryFrame` — **PASS**（原下跳 `473.5dp` → 全 20 帧 `≤ 1dp`）
  - `ContinuousPagingAnchorTest.e2bE2dMerge_nextFrame_aboveViewportImageOneFrameAfterPrepend_zeroJumpOnEveryFrame` — **PASS**（全 20 帧 `≤ 1dp`）
  - `ContinuousPagingAnchorTest.e2bE2dMerge_crossFloor_aboveFloorInTopPaddingGrowsSameFrameAsPrepend_zeroJumpOnEveryFrame` — **PASS**（全 20 帧 `≤ 1dp`，0 过度补偿）

### 6.4 相对 `origin/main`（`c0fc15d`）最终变更汇总

```text
 .../hipda/feature/threaddetail/ThreadDetailPane.kt |  291 +++-
 .../feature/threaddetail/ThreadDetailViewModel.kt  |  232 ++-
 .../feature/threaddetail/media/PostImageBlock.kt   |   30 +-
 .../threaddetail/ContinuousPagingAnchorTest.kt     | 1735 ++++++++++++++++++++
 4 files changed, 2200 insertions(+), 88 deletions(-)
```
- 授权范围严格合规：仅 3 个生产文件（`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`、`media/PostImageBlock.kt`）+ 1 个新增测试文件（`ContinuousPagingAnchorTest.kt`）；既有测试与全部快照 **0 修改、0 漂移**。
