# 独立代码审查报告 R-E2d：详情双向连续加载与向上插页零闪跳

- **审查结论**：`CHANGES-REQUESTED`（生产实现 `ThreadDetailPane.kt` + `ThreadDetailViewModel.kt` **零阻断、真同帧零闪跳**；唯一阻断 **B1** 位于测试 `ContinuousPagingAnchorTest.kt` 的逐帧断言窗口被 `advanceTimeByFrame()` 尾部 `runCurrent()` 与 `fetchSemanticsNode()` 隐式 `waitForIdle()` 吞掉合入首帧，改回 `LaunchedEffect` 或 `requestScrollToItem` 仍会假绿，**仅需改测试文件约 20 行，生产代码无需改动**）
- **审查对象**：`origin/card/E2d-thread-continuous-paging` @ `c447d4add612353052c32c654ad330d6a07b9cbb`（`c447d4a`）
- **对比基线**：`origin/main` @ `3dd65c4b4a2a74306a3b98df0f008996709272ab`（`3dd65c4`）
- **CI 核验**：[Android CI #45697095253](https://github.com/mailwmj/hipda-next/actions/runs/45697095253)（Job `139497221293`）
  - `headSha`：`c447d4add612353052c32c654ad330d6a07b9cbb`（精确匹配 `c447d4a`）
  - 构建与静态检查：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room schema 校验 **SUCCESS**
  - 单测与截图校验：`:hipda:testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` **SUCCESS**（`:hipda` `948/948`、全仓 `1046/1046`、`ContinuousPagingAnchorTest` `8/8`、`ThreadDetailViewModelTest` `8/8`、`ThreadDetailScreenshotTest` `15/15` 既有快照 **0 漂移 / 0 重录**）

---

## 一、核心专项核查：向上插页同帧零闪跳机制（源码级逐阶段拆解）

针对负责人重点关切的问题——**「`SideEffect + onGloballyPositioned` 中，`onGloballyPositioned` 在布局之后回调；为何数据合入的那一帧首可见楼层偏移不变，而不会像回调里调 `scrollToItem`/`requestScrollToItem` 那样闪一帧？」**——结合本仓源码与依赖的 Compose `1.13.0`（`composeBom = "2026.11.01"`：`foundation-android:1.13.0`、`ui-android:1.13.0`）字节码核实如下：

### 1.1 第 0 步：ViewModel 原子合入与防撕裂门控（`ThreadDetailViewModel.kt:126-143, 257-288`）
1. `DetailMetaState` 将 `rawFloors: List<PostFloor>` 收拢进同一 `MutableStateFlow`（`:83, 126`），向上插页成功时 `mergeDirectionalSuccess(prepend = true, ...)`（`:271-277`）在单次 `metaState.update` 中原子更新 `rawFloors = uniqueNew + cur.rawFloors`、`firstLoadedPage`、`isPrepending = false`、`prependError = null` 与 `prependGeneration + 1`。
2. `uiState` 的 `combine(metaState, observedFloors, challengeState)`（`:130-143`）通过：
   ```kotlin
   val synced = floors.size == m.rawFloors.size &&
       floors.firstOrNull()?.postId == m.rawFloors.firstOrNull()?.postId &&
       floors.lastOrNull()?.postId == m.rawFloors.lastOrNull()?.postId
   if (!synced) return@combine null
   ```
   配合 `.filterNotNull()` 过滤掉 `metaState` 已发而 `observedFloors`（黑名单映射流）尚未追平的瞬态。Compose 侧 `collectAsStateWithLifecycle()` 在合入帧**只会收到唯一一次原子快照更新**：`floors`（20 → 40 楼）、`showPrependHeader`（`true` → `false`，因 `isPrepending` 置假）与 `prependGeneration`（`G → G+1`）严格同帧进入重组。

### 1.2 第 1 步：重组尾声 `SideEffect`（发生在 `measureAndLayout` 之前，`ThreadDetailPane.kt:69-97, 271`）
在 Android `Choreographer.doFrame` 的单帧流水线中，**Composition（含 `CompositionImpl.applyChanges()` 执行全部 `SideEffect`）严格先于 Traversal（`AndroidComposeView.onLayout` / `dispatchDraw` 内的 `measureAndLayout`）**。
- 当 `SideEffect { anchorController.syncBeforeMeasure(uiState, listState) }` 执行时，`LazyColumn` 的 `itemProvider` 已更新为新列表（40 楼，顶部状态行移除），但 **`LazyColumn` 尚未测量，`listState.layoutInfo` 仍保存上一帧的 `LazyListMeasureResult`**。
- `firstVisibleFloor(listState)`（`:45-49`）取 `visibleItemsInfo` 中首个 `offset + size > 0` 且键前缀为 `"floor:"` 的项：
  - 在带 `contentPadding(top = topClearance)` 的 `LazyColumn` 中，`offset = 0` 对齐 `topClearance` 下沿（`viewportStartOffset = -topClearance`）；`scrollPosition.index` 之前的任何滚入透明顶栏背后的项均满足 `offset + size <= -scrollOffset <= 0`，因此被精确排除。
- 随后按锚点楼层上一帧的 `anchorOffset = anchorItem.offset` 分两路处理：

#### 分支 A：`anchorOffset <= 0`（99% 常规阅读态：首可见楼层贴顶 `offset == 0` 或局部上滚 `offset < 0`）
- **完全不经过 `onGloballyPositioned`（`clearPending()` 置空，`applyPendingPositiveOffset` 首行直接 `return`）**。
- 在 `measureAndLayout` 开始前的 `SideEffect` 中直接调用 `listState.requestScrollToItem(index = newListIndex, scrollOffset = -anchorOffset)`（`:90`；若当前用户手指正在拖拽/惯性滑行且原首项就是锚点楼层 `:89`，则不调 `requestScrollToItem` 以免打断手势协程，交由 `LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved` 凭稳定主键 `"floor:$postId"` 在紧随其后的测量中自动把索引平移至 `newListIndex` 并保留滑行速度）。
- 核对 `LazyListState.snapToItemIndexInternal(index, offset, forceRemeasure = false)`（`LazyListState.kt`）：同步写入 `scrollPosition`（`newListIndex = 20, scrollOffset = -anchorOffset >= 0`）并标记 `measurementScopeInvalidator`。紧接着同帧进入 `AndroidComposeView.measureAndLayout()`，**第 1 次测量即直接把第 2 页原首可见楼层放在 `newListIndex = 20, offset = anchorOffset`**，与上一帧屏幕坐标 0px 偏差。

#### 分支 B：`anchorOffset > 0`（顶部 `HiListFooter` 状态行曾露出在锚点楼层上方，如点击顶部失败行重试后合入）
1. **为什么 `SideEffect` 不能一步到位**：
   - `LazyListScrollPosition.update` 强制要求 `scrollOffset >= 0`（传负数直接抛 `IllegalStateException("scrollOffset should be non-negative")`）；
   - 要让 `newListIndex = 20`（第 2 页第 1 楼）停在 `+anchorOffset`（如 `+96px`），视口顶部 `[0, +96px)` 必须由第 1 页最末楼层 `index = 19` 填充，而第 1 页此前从未组合测量过，`index = 19` 的像素高度 `H_19` 在测量前未知。
2. **为什么回调里调 `scrollToItem` / `requestScrollToItem` 会闪一帧，而当前实现的 `dispatchRawDelta` 不会闪一帧**：
   核对 `AndroidComposeView.dispatchDraw(Canvas)` 与 `AndroidComposeView.measureAndLayout(Boolean)`（`ui-android:1.13.0`）字节码执行序列：
   ```text
   AndroidComposeView.dispatchDraw(canvas)
    ├─ 1. Owner.measureAndLayout()
    │    ├─ 1a. measureAndLayoutDelegate.measureAndLayout()   [Pass 1]
    │    │       LazyColumn 按 SideEffect 预设的 (20, 0) 测量布局，anchor 暂处于 offset = 0；
    │    │       finally 块将 MeasureAndLayoutDelegate.duringMeasureLayout 复位为 false！
    │    ├─ 1b. measureAndLayoutDelegate.dispatchOnPositionedCallbacks()
    │    │       触发 PostFloorCard 的 Modifier.onGloballyPositioned
    │    │        └─ anchorController.applyPendingPositiveOffset(listState) (:99-107)
    │    │            计算 delta = targetOffset - 0 = +96，立即 clearPending() 防重入，
    │    │            调用 listState.dispatchRawDelta(-96f)
    │    │             └─ LazyListState.onScroll(+96f)
    │    │                 firstVisibleItemScrollOffset - 96 = -96 < 0
    │    │                 → copyWithScrollDeltaWithoutRemeasure 返回 null
    │    │                 → 同步调用 remeasurement.forceRemeasure()！
    │    │                    └─ LayoutNode.forceRemeasure()
    │    │                        └─ owner.measureAndLayout(lazyListNode, lastConstraints) [同步 Pass 2！]
    │    │                            子组合测量 index = 19（第 1 页末楼）并回滚 96px，
    │    │                            将 index = 20（锚点楼层）精确重放置到 offset = +96px！
    │    └─ 1c. rectManager.dispatchCallbacks()
    └─ 2. root.draw(canvas, null)   ← 此时屏幕上第一次绘制，读取到的已是 Pass 2 完成后的 +96px！
   ```
   - **对比反例**：若在 `onGloballyPositioned` 中调 `requestScrollToItem`（内部 `forceRemeasure = false`），它只会标脏 `measurementScopeInvalidator` 并 `view.requestLayout()` 排入下一帧，随后第 2 步 `root.draw(canvas)` 会把 `offset = 0` 的半成品直接画上屏（闪跳 1 帧）；若调挂起函数 `scrollToItem`，则只能抛进协程在当前帧绘制后执行，同样必闪 1 帧。
   - **结论**：生产代码的 `SideEffect + onGloballyPositioned(dispatchRawDelta -> forceRemeasure)` 在 `anchorOffset <= 0`（单次测量到位）与 `anchorOffset > 0`（`dispatchDraw` 绘制前同步二次重测到位）两条路径上均实现了**真正的合入首帧零闪跳**。

---

## 二、阻断项（Blocking）

### B1：`ContinuousPagingAnchorTest` 的逐帧断言被 `advanceTimeByFrame()` 尾部 `runCurrent()` 与 `fetchSemanticsNode()` 隐式同步吞掉合入首帧，改回 `LaunchedEffect` 或 `requestScrollToItem` 仍会假绿
- **文件与行号**：`hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt:140-157, 230-243, 292-303`
- **对照标准**：审查卡 `R-E2d.md` 【边界】阻断标准（「测试未在 `mainClock.autoAdvance = false` 下覆盖合入首帧」）及【审查重点】1（「审视 `ContinuousPagingAnchorTest.kt` 是否真在 `mainClock.autoAdvance = false` 下逐帧（含合入首帧）断言偏移 `≤ 1dp`，尝试推理把实现改回 `LaunchedEffect` 时该测试是否必定变红」）；任务卡 `E2d.md` 【验收】1（「任何滞后一帧的 `LaunchedEffect` 回滚方案都会在首帧失败」）。
- **源码级根因与触发路径（基于 `androidx.compose.ui:ui-test-android:1.13.0` 核实）**：
  当前 3 个锚定测试均采用如下循环：
  ```kotlin
  composeRule.mainClock.autoAdvance = false
  page1Gate.complete(Unit)
  for (frame in 1..15) {
      composeRule.mainClock.advanceTimeByFrame()
      ...
      val currentTop = composeRule.onNodeWithText(anchorText, substring = true)
          .fetchSemanticsNode()
          .boundsInRoot.top
  ```
  存在**两处独立的跨阶段同步泄漏**，直接跳过了真实设备上已经上屏的「合入首帧」状态：
  1. **泄漏 1（`advanceTimeByFrame()` 内部的 `scheduler.runCurrent()` 同刻冲刷 `LaunchedEffect`）**：
     核查 `AbstractMainTestClock.advanceTimeByFrame()` → `advanceScheduler(16L)` 字节码，其内部依次执行 `scheduler.advanceTimeBy(16L)` 与 **`scheduler.runCurrent()`**。
     在 `T + 16ms` 触发 `TestMonotonicFrameClock.performFrame()` 时，`FrameDeferringContinuationInterceptor.runWithoutResumingCoroutines` 会先执行 `Recomposer` 重组与 `onPerformTraversals`（`root.measureAndLayoutForTest()`——**此刻即真实设备 `Choreographer` 上屏绘制的合入首帧**），并将本帧新启动的 `LaunchedEffect` 挂起推迟。然而 `performFrame()` 一退出 `runWithoutResumingCoroutines`，被推迟的 `LaunchedEffect` 立即排入 `StandardTestDispatcher` 同一虚拟时刻 `T + 16ms` 队列，紧接着被 `advanceScheduler` 末尾的 `scheduler.runCurrent()` **在 `advanceTimeByFrame()` 返回前同步执行完毕**！
     - **反例推演 1**：若删掉 `PrependAnchorController`，改用违规的 `LaunchedEffect(uiState.prependGeneration) { listState.scrollToItem(20, -anchorOffset) }`，`onPerformTraversals` 结束时第 2 页首楼其实已被顶出屏幕（真实设备闪跳 1 帧），但紧随其后的 `scheduler.runCurrent()` 在同一 `advanceTimeByFrame()` 调用内执行完 `scrollToItem`（`forceRemeasure = true`）把列表拉回 `index = 20`，测试第 144 行再去读坐标时看到的已是「第 2 次修正后」的结果，**3 个测试全部假绿通过**。
  2. **泄漏 2（`fetchSemanticsNode()` 未包裹 `runWithoutImplicitWait` 触发隐式重排帧）**：
     核查 `SemanticsNodeInteraction.fetchSemanticsNode()` → `AndroidTestOwner.getRoots(true)` → `waitForIdle(true)` → `RobolectricIdlingStrategy.runUntilIdle()`：即便 `mainClock.autoAdvance == false` 阻止了泵送 `TestMonotonicFrameClock`，`runUntilIdle()` 仍会调用 `requestLayoutIfNeeded()`（`view.requestLayout()`）与 `waitForNextChoreographerFrame()`（`postOnAnimation`）冲刷 Robolectric 的 Android `View` 布局帧！
     - **反例推演 2**：若把 `PrependAnchorController.applyPendingPositiveOffset`（`ThreadDetailPane.kt:106`）里的 `dispatchRawDelta` 劣化为 `requestScrollToItem`（无同步 `forceRemeasure`，真实设备必闪 1 帧），`fetchSemanticsNode()` 会在读取 `boundsInRoot.top` 前替它冲刷完下一轮 View `onLayout`，导致 `upwardPrepend_whenTopFooterVisibleAboveAnchor_positiveOffsetZeroJumpEveryFrame` **同样假绿通过**。
- **后果**：后续 Wave 3b `E2c` 与 Wave 4 `E4` 均将直接修改 `ThreadDetailPane.kt` 与 `ThreadDetailViewModel.kt`，假绿测试无法守住 B4-E2a ①「合入首帧零闪跳」回归红线。
- **最小修复方向（仅改测试 `ContinuousPagingAnchorTest.kt`，0 触碰生产代码）**：
  1. 在测试 `setContent` 外层包一层测试辅助节点，在每次 `AndroidComposeView.measureAndLayout()` 的 `dispatchOnPositionedCallbacks()` 之后、`runWithoutResumingCoroutines` 退出之前（即 `TestMonotonicFrameClock.onPerformTraversals` 真实上屏时刻，例如利用随 `listState.layoutInfo` 翻转 `0/1px` 尺寸的旁路 `Spacer` 挂载 `Modifier.onLayoutRectChanged(debounceMillis = 0, throttleMillis = 0)`，其在 `AndroidComposeView.measureAndLayout` 字节码第 85 行严格晚于第 78 行 `dispatchOnPositionedCallbacks` 且早于任何 `LaunchedEffect` 恢复与下一帧 `requestLayout`）直接记录每次 traversal 结束时的 `(uiState.floors.size, listState.layoutInfo.visibleItemsInfo)` 锚点偏移并断言全程偏差 `≤ 1dp`；
  2. 同时将逐帧循环内的语义树查询包裹在 `composeRule.runWithoutImplicitWait { ... }` 中，杜绝 `fetchSemanticsNode()` 隐式触发 `waitForIdle()`。

---

## 三、其余重点逐项核查结果（全部通过）

### 3.1 并发预取防重、去重与页序一致性（`ThreadDetailViewModel.kt:228-288`） — 通过
- **上下同时触发不重复请求、不串扰**：
  - 向上与向下拆分为独立的 `prependJob` / `appendJob` 及独立状态位（`isPrepending`/`prependError` vs `isAppending`/`appendError`）；
  - 因 `firstLoadedPage <= lastLoadedPage`，向上目标页 `firstLoadedPage - 1` 与向下目标页 `lastLoadedPage + 1` 数学上严格不相交，辅以同步 `inFlightPages: HashSet<Pair<Long, Int>>`（`sessionGeneration to targetPage`）单飞锁，杜绝同方向或同页并发重入，且旧会话 `finally` 不会误删新会话在途键；
  - 失败态下 `!isRetry && prependError != null` / `appendError != null`（`:231-232`）阻断滚动与 `NestedScroll` 重刷，仅允许点击整行 `HiListFooter(Failed)`（`minHeight = 48.dp`）走 `retryPrepend()` / `retryAppend()`（`isRetry = true`）原地恢复。
- **页序与楼层主键唯一性**：
  - `mergeDirectionalSuccess` 在 `metaState.update` 原子闭包内合入：无论上下哪一侧先返回，`prepend` 始终执行 `uniqueNew + cur.rawFloors` 并更新 `firstLoadedPage`，`append` 始终执行 `cur.rawFloors + uniqueNew` 并更新 `lastLoadedPage`（含末页删楼被服务端钳制时的 `clampedEnd` 收敛 `:278-280`），页序恒为严格递增连续区间 `firstLoadedPage..lastLoadedPage`；
  - 合入前以 `existingIds` 按 `postId` 过滤重复楼层（`:260-261`），防止跨页楼层漂移触发 `LazyColumn` 重复 `key` 崩溃。

### 3.2 `只看楼主` 过滤切换、跨页跳转、帖子主题（`tid`）切换与 UI 主题切换重置 — 通过
- **`只看楼主` 切换与退出恢复（`:196-216, 329-363`）**：
  - 开启时从 `firstVisibleFloor(listState)` 精确提取锚点楼层真实的 `anchorFloor.page`、`postId` 与 `scrollOffset` 存入 `preFilterAnchor`（即使连续加载了多页也能记住真实所在页而非首载页），随即调 `loadInternal(1, AuthorOnly, scrollToTop = true)`；
  - `loadInternal` 立即执行 `cancelPagingJobs()`、`loadJob?.cancel()`、`++sessionGeneration`（同步推进 `resetEpoch`），将 `isPrepending`/`isAppending`/`prependError`/`appendError` 全部清零，在途旧预取即便晚到也会被 `if (generation != sessionGeneration) return@launch`（`:243`）丢弃，成功后 `firstLoadedPage = resolvedPage, lastLoadedPage = resolvedPage` 重建单页区间；
  - 退出只看（`clearFilter`）按 `preFilterAnchor.page` 重载全帖对应页、重置连续区间并通过 `PendingScrollTarget` 恢复至原 `postId` 与像素偏移。
- **帖子主题（`tid`）切换与 UI 主题（深浅色/纯黑/种子色）切换**：
  - **切换帖子 `tid`**：外壳 `BasicMultiPaneContainer.kt:54` + `NextAppHostViewModel.kt:71-79` 通过 `key(args.tid)` 与 `releaseThreadStore(staleTid)` 销毁旧 `ViewModelStore` 和旧 `LazyListState`；`ThreadDetailViewModel.bindArgs`（`:145-159`）亦清空 `preFilterAnchor`、`cancelPagingJobs()`、`++sessionGeneration` 并重建 `DetailMetaState`（保持 `prependGeneration` 不变以免误触发向上锚定）。
  - **切换应用深浅色/配色主题**：不改变 `args.tid` 与 `resetEpoch`，`PrependAnchorController.syncBeforeMeasure` 因 `!genChanged && !headerChanged` 立即返回（`:76`），`snapshotFlow` 因 `curPos == prevPos` 不发包，连续加载区间与当前阅读像素偏移完整保持（符合 `product-design.md` §10.1）。

### 3.3 方案 B 底栏页码同步、2 文件边界、既有快照与契约零回归 — 通过
- **底栏页码同步（方案 B）**：`visiblePage` 通过 `derivedStateOf`（`ThreadDetailPane.kt:153-159`）按 `firstVisibleFloor(listState)` 所在楼层的 `floor.page` 实时计算，驱动 `ThreadDetailPaginationBar` 并经 `LaunchedEffect(visiblePage)` 回写 `viewModel.onVisiblePageChanged(visiblePage)`，上下滚动跨页后页码文案与上一页/下一页按钮可用态实时同步，且同页内滚动零额外重组。
- **严格 2 生产文件边界与快照核对**：`git diff --name-status 3dd65c4...c447d4a` 生产文件仅含 `ThreadDetailPane.kt`（`+187` 行）与 `ThreadDetailViewModel.kt`（`+95` 行），合计净增 `+282` 行（低于 1.5× 上限 `390` 行）；既有 15 张 `thread_detail_*.png` **0 张修改、0 张漂移**（因截图夹具 `lastPage > 1` 时 `hasNextPage == true` 不出末页尾行，单页短帖亦未改变首屏可视区）。
- **不可逆契约与纯净度**：`ThreadDetailPane` 顶层签名、`key = { "floor:${it.postId}" }`（`:284`）、`detailGlassState` 严格兄弟拓扑（`HiGlassMarker == 1`）、`PostImageBlock` 接缝、`ThreadDetailViewModel` 双参兼容构造均 100% 完好；生产代码零手写 `Layout`/`Modifier.Node`、零测试专用钩子。

---

## 四、非阻断建议项（Suggestions）

1. **S1（`ThreadDetailViewModel.kt:146-158`）**：当前外壳已由 `key(args.tid)` + `threadListStateFor(tid)` 隔离不同帖子的 `LazyListState`；若后续独立调用方在未包 `key(args.tid)` 的场景下对同一 `ViewModel` 与 `LazyListState` 二次 `bindArgs(newTid)`，建议在 `val isRebind = boundTid != null` 时向 `loadInternal` 传 `scrollToTop = isRebind`（保持首次绑定 `false` 以兼容预置滚动偏移的截图测试）。


---

# 独立代码审查报告 R-E2d（第 2 轮复审：修复轮 @ `f8b5d2a`）

- **复审结论**：`APPROVE`（一轮阻断 **B1**、建议 **S1** 及 `V-E2b` 发现的 `E2b + E2d` 同帧竞态已全部闭合；4 项复审重点经源码与 Compose `foundation-android:1.13.0` 字节码逐条核实全部成立，**0 阻断项**）
- **审查对象**：`origin/card/E2d-thread-continuous-paging` @ `f8b5d2a4bff272f48608c7f2a2479a2ea8859f51`（`f8b5d2a`）
- **对比基线**：
  - 相对一轮提交：`git diff c447d4a..f8b5d2a`（中间经 `2a7d6b6` 合入 `origin/main` @ `c0fc15d`，内含 `E1` 与 `E2b` @ `1597b4e`）
  - 剥离 `main` 合入后的纯分支改动：`git diff c0fc15d...f8b5d2a` 严格仅 **4 个文件**（`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`、`media/PostImageBlock.kt`、`ContinuousPagingAnchorTest.kt`）
- **CI 核验**：[Android CI #45708732156](https://github.com/mailwmj/hipda-next/actions/runs/45708732156)（Job `139537320021`）
  - `headSha`：`f8b5d2a4bff272f48608c7f2a2479a2ea8859f51`（精确匹配 `f8b5d2a`）
  - 构建、Lint、Room schema、APK 产物校验：**SUCCESS**
  - 单测与 Roborazzi 截图校验：**SUCCESS**（全仓 `1119/1119` 通过、0 失败；其中 `ContinuousPagingAnchorTest` `15/15`、`ThreadDetailViewModelTest` `8/8`、`ThreadDetailScreenshotTest` `15/15`、`ImageScrollCompensationTest` `2/2`、`PostImageBlockTest` `8/8`、`PostImageScreenshotTest` `6/6`）

---

## 五、第 2 轮四项核心核查

### 5.1 单一滚动权威（`PrependAnchorController` + `AboveViewportHeightCompensationAuthority`）源码级证明 — 通过

1. **同一帧内唯一滚动写入点**：
   - 在 `ThreadDetailPane.kt:232-236, 344-345` 中，`DetailBodySlot.CONTENT` 通过 `CompositionLocalProvider(LocalAboveViewportHeightCompensationAuthority provides scrollCompensationAuthority)` 将首楼越顶图片高度补偿收口至 `anchorController.onAboveViewportImageHeightDelta(floorPostId, deltaPx, listState)`。
   - 在 `PostImageBlock.kt:238-248` 的 `ABOVE_VIEWPORT_COMPENSATE` 分支中，当 `authority != null` 时**不再直接调用 `listState.requestScrollToItem`**，仅上报 `(floorPostId, deltaPx.roundToInt())`；详情列表内所有向上插页锚定与越顶图片高度补偿均由 `PrependAnchorController` 唯一写入 `listState`。
2. **同帧双序与多图叠加既不漏补偿、也不重复补偿**：
   - **情况 1A（同帧先插页 `SideEffect`，后越顶图片解码完成，测量尚未执行，`layoutInfo === L_old`）**：
     1. `syncBeforeMeasure`（`ThreadDetailPane.kt:125-164`）读取 `L_old` 中锚点 `p201`（`index = 1, anchorOffset = -820`），记录 `activeLayoutInfo = L_old`、`activeAnchorKey = "floor:p201"`、`activeTargetIndex = 15`、`activeTargetScrollOffset = 820`，调用 `requestScrollToItem(15, 820)`（此时 `listState.firstVisibleItemIndex` 已变为 `15`，而 `listState.layoutInfo` 仍为 `L_old`）。
     2. 紧接着同帧测量前 `PostImageBlock` 解码完成：`computeImageBottomInViewport`（`PostImageBlock.kt:114-143`）与修改后的 `classifyHeightChangeBranch`（`PostImageBlock.kt:94-112`）**全部仅从同一份 `L_old.visibleItemsInfo` 快照读取索引与几何坐标**（`floorItem.index == 1 == firstVisibleItem.index`），消除了旧版与 `listState.firstVisibleItemIndex (15)` 跨代比较误判为 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP` 的根因，准确进入 `ABOVE_VIEWPORT_COMPENSATE`。
     3. `onAboveViewportImageHeightDelta`（`ThreadDetailPane.kt:96-123`）经 `ensureLayoutEpoch(L_old)` 确认同纪元且 `activeAnchorKey == "floor:p201"`，以 `(baseIndex = 15, baseOffset = 820)` 合成 `nextOffset = 820 + 948 = 1768`，同步更新 `activeTargetScrollOffset = 1768`（若同楼层第 2 张越顶图同帧完成，继续在 `1768` 上累加）并调用 `requestScrollToItem(15, 1768)`。随后的单次 `measureAndLayout` 一步到位测量 `(15, 1768)`。
   - **情况 1B（同帧先越顶图片解码完成，后插页 `SideEffect` 重组，测量尚未执行，`layoutInfo === L_old`）**：
     1. `onAboveViewportImageHeightDelta` 先运行：此时 `activeAnchorKey == null`，从 `L_old` 验证 `listState.firstVisibleItemIndex == floorItem.index (1)`，登记 `activeLayoutInfo = L_old`、`activeImageDeltaFloorKey = "floor:p201"`、`activeImageDeltaPx = +948`（同楼多图则累加 `+= deltaPx`），先写入 `requestScrollToItem(1, 1768)`。
     2. 同帧 `SideEffect` 执行 `syncBeforeMeasure`：`ensureLayoutEpoch(L_old)` 命中 `activeLayoutInfo === L_old` 保留同帧图片增量；虽然 `L_old` 中 `anchorItem.offset` 仍是未重测的 `-820`，但 `:147-148` 计算 `targetScrollOffset = (-anchorOffset + sameFrameImageDelta).coerceAtLeast(0) = 820 + 948 = 1768`（且 `:153` 在 `sameFrameImageDelta != 0` 时强制写入 `requestScrollToItem(15, 1768)`），绝不会把已补偿的 `+948` 覆盖回 `820`。
   - **情况 1C（隔帧：第 1 帧插页并完成测量产生 `L_new`，第 2 帧图片完成）**：
     - 第 1 帧 `LazyColumn` 测量结束时，`LazyListState.applyMeasureResult`（核对 `foundation-android:1.13.0` 字节码偏移 `260-265`：`layoutInfoState.setValue(measureResult)`）无条件写入全新的 `LazyListMeasureResult` 实例 `L_new`（`p201.index == 15, offset == -820`）。
     - 第 2 帧 `onAboveViewportImageHeightDelta` 首行 `ensureLayoutEpoch(L_new)` 检测到 `activeLayoutInfo (L_old) !== L_new`，立即调用 `clearFrameAuthorityState()` 清空上一帧的 `activeAnchorKey` 与 `activeImageDeltaPx`，回退到常规分支在 `(15, 820)` 上一次性加 `+948 -> (15, 1768)`，既不漏算也不二次累加。
   - **情况 2（跨楼层：`p201` 已滚入 `beforeContentPadding`，`p202` 为首可见楼层）**：
     - `L_old` 中 `p201` 满足 `offset + size <= 0`，`firstVisibleItem` 为 `p202`（`index = 2`）；`p201.index (1) < firstVisibleItem.index (2)` 恒命中 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP`（仅 `snapTo` 宽高比、不调滚动权威），`syncBeforeMeasure` 亦仅锚定 `p202`，`p202` 零过度补偿。
3. **无插页（`E2b` 单独生效）行为完全不变**：
   - **详情页内无插页时**：`activeAnchorKey == null`，`onAboveViewportImageHeightDelta`（`:108-123`）校验 `listState.firstVisibleItemIndex == floorItem.index`（与 `classifyHeightChangeBranch` 的 `firstVisibleItem.index` 在未发生跨项改写时恒相等），取 `baseIndex = floorItem.index`、`baseOffset = listState.firstVisibleItemScrollOffset`，写入 `requestScrollToItem(baseIndex, (baseOffset + deltaPx).coerceAtLeast(0))`；若同楼层多图同帧完成，第二次调用读到的 `listState.firstVisibleItemScrollOffset` 已含第一次增量，累加结果与原 `E2b` 完全一致；且 `LazyListState.snapToItemIndexInternal`（字节码偏移 `110-127`）与 `aspectRatioAnim.snapTo` 必定触发下一轮测量更新 `layoutInfo` 引用，不会残留增量。
   - **独立挂载 `PostImageBlock`（无 `ThreadDetailPane` 提供 `CompositionLocal`）时**：`LocalAboveViewportHeightCompensationAuthority.current == null`，`:245-246` 执行与原 `E2b` 逐字一致的回退代码。

### 5.2 合入首帧双反例真实变红验证（闭合一轮 B1） — 通过

核查 `ContinuousPagingAnchorTest.kt:1255-1475` 与 `:405-580`：
1. **探针位置真截获上屏首帧**：`PrependFrameProbe`（`:1326-1355`）每帧翻转 `probeStep` 驱动 `1px/2px` 旁路 `Spacer` 触发 `Modifier.onLayoutRectChanged(debounceMillis = 0L, throttleMillis = 0L)`。在 `AndroidComposeView.measureAndLayout()` 中，`RectManager.dispatchCallbacks()` 严格位于 `dispatchOnPositionedCallbacks()`（正式代码 `dispatchRawDelta -> forceRemeasure` 同步二次重测完成点）之后、且严格早于 `TestMonotonicFrameClock.performFrame()` 退出 `runWithoutResumingCoroutines` 后的 `scheduler.runCurrent()`（`LaunchedEffect` 恢复点）与下一帧 `requestLayout`；同时语义树 `boundsInRoot.top` 查询包裹在 `composeRule.runWithoutImplicitWait`（`:1298-1302`）中，彻底堵死了隐式 `waitForIdle()` 泄漏。
2. **两个反例均在同一套 `verifyPrependMergeFrameByFrame` 下于第 1 帧真实 FAIL**：
   - `counterexample_launchedEffectCompensation_mustFailOnFirstMergeFrame`（`:406-483`）：`CounterexampleLaunchedEffectPane` 在 `LaunchedEffect(uiState.prependGeneration)` 中执行 `scrollToItem` + `dispatchRawDelta`。尽管 `advanceTimeByFrame()` 尾部的 `runCurrent()` 事后把锚点拉回了 `baselineOffsetPx`（`:482` 显式断言验证），`PrependFrameProbe` 仍在 `step == 1`（合入首帧）截获 `anchorOffsetPx == null`（原首楼被 20 楼新内容顶出视口）并抛出 `AssertionError`。
   - `counterexample_onGloballyPositionedRequestScrollToItem_mustFailOnFirstMergeFrame`（`:491-579`）：`CounterexampleDeferredRequestScrollPane` 保留 `SideEffect` 落位但在 `onGloballyPositioned` 中改调无同步重测的 `requestScrollToItem(targetIdx - 1, positivePx)`。`verifyPrependMergeFrameByFrame` 在 `step == 1` 截获 `anchorOffsetPx == 0`（相对基线 `+96px` 产生完整 `48.0dp` 首帧跳变），且 `runWithoutImplicitWait` 读取的 `boundsInRoot.top` 同样测得 `48.0dp` 跳变并立即 FAIL。

### 5.3 `media/` 最小改动与 `E2b` 测试/截图零改动核验 — 通过

- `git diff c0fc15d..f8b5d2a -- hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media` 仅修改 `PostImageBlock.kt` **1 个文件**（`+25/-5` 行）：仅新增 `internal` 级 `AboveViewportHeightCompensationAuthority` + `LocalAboveViewportHeightCompensationAuthority` 委托点，并将 `classifyHeightChangeBranch` 的首可见项索引改为取自同一份 `visibleItems` 快照。`PostImageBlock` 公开 5 参签名、URL 白名单、按尺寸解码、比例缓存、动画与省流重试逻辑 **0 改动**，且无测试专用钩子。
- `git diff c0fc15d..f8b5d2a -- hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media hipda/src/test/snapshots` 输出为空：**`E2b` 原有单测与全仓快照 PNG 均为 0 改动**，CI `45708732156` 原样全绿。

### 5.4 跨帧、切帖、切换过滤与主题切换的状态复位核验 — 通过

1. **常规跨帧**：`ensureLayoutEpoch` 以不可变的 `LazyListLayoutInfo` 引用身份（`!==`）划分未重测窗口；任何触发 `requestScrollToItem`、`scrollToItem` 或 `aspectRatioAnim.snapTo` 的帧在 `measureAndLayout` 后都会由 `applyMeasureResult` 生成新 `LazyListMeasureResult` 实例，下一帧访问时自动执行 `clearFrameAuthorityState()`，不存在跨帧残留。
2. **切帖（切换 `tid`，含一轮 S1 闭合）**：
   - `ThreadDetailPane.kt:229-231` 以 `remember(listState, uiState.tid)` 持有 `PrependAnchorController`，`tid` 变化立即实例化全新控制器；
   - `ThreadDetailViewModel.kt:147, 159` 新增 `val isRebind = boundTid != null` 并在二次绑定时传 `scrollToTop = isRebind`（同时递增 `resetEpoch`），即使调用方复用同一 `ViewModel` 与 `LazyListState` 切帖也自动滚回 `(0, 0)`（已由 `bindArgs_rebindingToNewTid_resetsScrollPositionToTop` `:907-938` 覆盖验证，首次绑定仍保留外部初始偏移不破截图）。
3. **切换过滤（`只看楼主` 开启/退出）与跨页跳转**：
   - `toggleAuthorOnly` / `clearFilter` / `loadPage` / `previousPage` / `nextPage` 均经 `loadInternal` 取消在途 `prependJob`/`appendJob`、清空 `inFlightPages` 并递增 `resetEpoch = ++sessionGeneration`（保持 `prependGeneration` 不变）；
   - `syncBeforeMeasure`（`:129-135`）检测到 `!sameSession` 立即调用 `clearFrameAuthorityState()` 且 `headerChanged` 受 `sameSession && uiState.pendingScroll == null` 门控不会误触发；随后新页到达时 `PendingScrollTarget` 通过 `listState.scrollToItem` 同步 `forceRemeasure()` 生成新 `layoutInfo` 纪元并精准落位。
4. **UI 主题切换（深浅色 / 纯黑 / 种子色 / 减弱动态效果）**：
   - 主题切换触发 `ThreadDetailPane` 重组但不改变 `tid`、`resetEpoch`、`prependGeneration` 与 `showPrependHeader`；
   - `SideEffect` 中 `ensureLayoutEpoch(listState.layoutInfo)` 若发现自上次事件后列表已重测过则顺带清理旧帧权威字段，随后因 `!genChanged && !headerChanged` 在 `:135` 直接返回，不触碰 `listState`，连续加载页区间与像素级阅读位置完整保留。

---

## 六、第 2 轮阻断项与非阻断建议

- **阻断项（Blocking）**：无。
- **非阻断建议项（Suggestions）**：
  1. **S2-R2（`ThreadDetailPane.kt:134`）**：`if (!sameSession) clearFrameAuthorityState()` 可顺带一并调用 `clearPending()`（正常非零高度视口下 `pendingFloorKey` 已在同帧 `onGloballyPositioned` 内被消费清零，不会触发实际问题；合并清理可作为极端零高度布局中断下的防御性兜底，后续顺手维护即可，不阻断合入）。
