# 独立代码审查报告 R-E4：阅读位置记忆、位置栈、底部三段式液态玻璃工具条与内联跳页条（commit `6526c1b677d934b2381aec94defc398ff3a26887`）

- **审查结论**：**`CHANGES-REQUESTED`**（1 项阻断 `B1`，3 项非阻断建议 `S1`–`S3`）
- **审查对象**：`origin/card/E4-thread-chrome-and-position` @ `6526c1b677d934b2381aec94defc398ff3a26887`（简称 `6526c1b`）
- **对比基线**：`70da63824eb7fb22640de5fcacdb1386520366f1`（简称 `70da638`，已含 `E2c` 与 `E3`）
- **与最新 `origin/main` 合入预演（`git merge-tree`）**：
  - 当前 `origin/main`：`1dc594e9cae6133c50062b0c51c4babb3ca1896e`（已含 `E-slots`、`E8`、`E10`、`E12`）
  - `git merge-tree $(git merge-base origin/main 6526c1b) origin/main 6526c1b`：**0 冲突**
  - `git merge-tree --write-tree origin/main 6526c1b`：退出码 `0`（生成树 `06e9e6d9b12c1261e01e7deffa2c3d378142a827`，文本与语义均零冲突；同时核实 `1dc594e` 新增的看图/账号浮层 `BackHandler` 注册在 `HiAdaptiveShell` 之后，与 `ThreadDetailPane` 内部 `PositionStack` `BackHandler` 的 LIFO 优先级完全兼容）
- **GitHub Actions CI 核实**：[Android CI #45743939633](https://github.com/mailwmj/hipda-next/actions/runs/45743939633)（Job `139655342464`）
  - `headSha`：`6526c1b677d934b2381aec94defc398ff3a26887`（精确匹配被审提交）
  - `status / conclusion`：`completed / success`
  - 已通过步骤：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true`，0 快照差异）、`Verify APK outputs`（Debug & Release 包名与产物校验）
- **只读纪律核实**：主仓库 `/home/mir/Documents/hipda-next` 全程仅执行只读 `git fetch`、`git show`、`git diff`、`git grep`、`git merge-tree` 与 `gh run view`；`HEAD` 保持 `refs/heads/main @ 1dc594e`，工作区 `0` 文件改动、未编译。

---

## 一、阻断项（Blocking Issues，依据 `DISPATCH.md` §4「丢失阅读状态」）

### B1 — 离开/切换帖子时 `flushIfHydrated()` 在 `viewModelScope` 上裸启动且未加 `NonCancellable`，同帧被 `releaseThreadStore` 取消导致 `< 400ms` 未去抖的阅读位置丢失

- **涉及位置**：
  - [ReadingPositionController.kt:97-108](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionController.kt#L97-L108)
  - [ThreadDetailViewModel.kt:231-244](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L231-L244)、[ThreadDetailViewModel.kt:411-413](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L411-L413)
  - [ThreadDetailPane.kt:417-419](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L417-L419)
  - 交互上游（基线既有代码）：[MultiPaneReadingContainer.kt:127-135](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L127-L135)、[NextAppHostViewModel.kt:60-68, 81-83](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt#L60-L83)、[DefaultThreadDetailRepository.kt:226-237](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/threaddetail/DefaultThreadDetailRepository.kt#L226-L237)
- **具体触发路径与机理**：
  1. 用户打开帖子 `T`（首轮恢复完成，`isHydrated == true`），向下滚动到新楼层（如第 15 楼 `offsetPx = 120`）。`onPositionObserved` 将快照记入 `pendingSnapshot` 并启动 `400ms` `debounceJob`（`:81-86`）。
  2. 用户在停手后 **`400ms` 内**离开该帖——例如：① 单栏/图标轨单栏下按系统返回或顶栏返回回列表（下一帧立即卸载 `detailMovableFor(T)`）；② 三栏/铰链双栏下在中栏点击另一篇帖子 `T2` 或按返回关闭右栏（`RenderRightSlot` 的 `exit(effectsDefault)` 约 `110–140ms < 400ms` 结束并卸载 `detailMovableFor(T)`）；或 ③ 直接关闭宿主 Activity。
  3. 在 `detailMovableFor(T)` 卸载的**同一次主线程 `RememberEventDispatcher` 释放遍历**中（按 LIFO 逆序执行 `onDispose`）：
     - 第一步：内层 `ThreadDetailPane` 的 `DisposableEffect`（`ThreadDetailPane.kt:417-419`）执行 `viewModel.flushReadingPosition()` → `ReadingPositionController.flushIfHydrated()`（`:97-108`），先取消 `debounceJob`（`:101`），再调用 `scope.launch { savePosition(toFlush) }`（`:105`，其中 `scope` 是 `ThreadDetailViewModel.viewModelScope`）。在真实生产环境中，`DefaultThreadDetailRepository.saveReadingPosition`（`:226`）调用 Room `@Upsert suspend fun upsert(...)`，通过 `DBUtil.performSuspending` 切往 Room 后台事务线程池并**立即挂起返回主线程**；
     - 第二步：同一主线程循环紧接着执行外层 `MultiPaneSlotHolder.detailMovableFor` 的 `DisposableEffect.onDispose`（`MultiPaneReadingContainer.kt:130`）→ `hostViewModel.onThreadDisposed(tid)` → 因 `tid !in activeThreadTids` 立即调用 `releaseThreadStore(tid)` → `ViewModelStore.clear()`，**当场取消 `viewModelScope`**；
     - 第三步：尚未在 Room 后台线程执行完（或刚排入队列）的 `savePosition(toFlush)` 协程因父 `Job`（`viewModelScope`）被取消而直接以 `CancellationException` 中止，SQL `UPSERT` 被丢弃。（此外 `ThreadDetailViewModel` 未重写 `onCleared()`，若 Activity 销毁路径先触发 `ViewModelStore.clear()` 再走组合销毁，`flushIfHydrated()` 甚至在 `viewModelScope` 已取消后才调用，同样变为空操作。）
- **后果**：`debounceJob` 与 `flushIfHydrated` 双双被取消，用户滚到的最新楼层与偏移量未写入 Room，再次打开帖子 `T` 时回退到旧阅读位置（违反 `DISPATCH.md` §4「丢失阅读状态」与 `product-design.md` §2 原则 3 / §7.3）。
- **既有单测漏检原因**：`ReadingPositionAndStackTest.kt`（`:486-498`）中的假替身 `PositionTestRepository.saveReadingPosition` 是零挂起点的内存 Map 同步赋值，在 `Dispatchers.Main.immediate` 下于 `onDispose` 返回前已同步跑完，掩盖了真实 Room `@Upsert` 跨线程挂起期间被 `ViewModelStore.clear()` 取消的竞态（对照 `lesson.md`「测试替身必须按真实语义建模」）。
- **修复建议**：
  1. 在 `ReadingPositionController.flushIfHydrated()`（`:105`）中使用 `scope.launch(NonCancellable) { savePosition(toFlush) }`（`NonCancellable` 仅在 `:98` `if (!hydrated) return` 门闩通过后生效，绝不破坏 `B4-E4 ①` 未水合零落盘保证，同时替换 `scope` 中被取消的父 `Job` 并保留原 `CoroutineDispatcher` 以兼容 `TestDispatcher`）；
  2. 在 `ThreadDetailViewModel` 中补充 `override fun onCleared() { readingPositionController.flushIfHydrated(); super.onCleared() }` 兜底 Activity 级销毁路径；
  3. 在 `ReadingPositionAndStackTest` 中为 `PositionTestRepository.saveReadingPosition` 增加可配置的异步挂起门闩（模拟 Room IO 切换），补一条「滚动后 `< 400ms` 内按返回触发真实 `MultiPaneTestHarness` / `ViewModelStore.clear()` 关闭帖子 → 放行 Room 挂起后断言新位置仍成功落盘（回退 `NonCancellable` 必红）」回归单测。

---

## 二、审查卡 4 大重点与负责人追加项逐条核查

### 1. 首轮恢复门闩、位置栈与 `R-E2c S1-R2`（重点 1 & 追加 #1/#3/#4）
- **首轮恢复门闩（`B4-E4 ①`）**：通过。`bindArgs`（`ThreadDetailViewModel.kt:295`）在发起 `getReadingPosition` 前调用 `readingPositionController.beginSession(args.tid)` 将 `hydrated` 置 `false`；仅当首次 `loadPage` / `loadByPostId` / `loadLastPage` 返回 `ThreadDetailLoadResult.Success` 时才调 `markHydratedIfNeeded`（`:839, :915, :1027`）并同步原子写入 `pendingScroll`。慢网挂起、加载报错或中途退出期间，`onReadingPositionChanged`（`:385`）、`onPositionObserved`（`ReadingPositionController.kt:72`）与 `flushIfHydrated`（`:98`）三道门控 100% 阻断 `saveReadingPosition`；`ReadingPositionAndStackTest.hydrationGate_slowLoadAndMidLoadExit_zeroSavesBeforeHydrated_thenDebouncesAfterRestore` 用 `CompletableDeferred` 挂起 `loadPage(5)` 验证慢载与中途退出期间 `saveCalls.size == 0`，水合落位在原锚点未移动时亦因 `normalized == lastSavedSnapshot` 零冗余写盘，滚动越过 `400ms` 后精确落盘。
- **`PositionStack` 与返回优先级（追加 #3）**：通过。跳页（`首页`/`末页`/滑块/回车）、本页/跨页引用跳楼、`jumpToNewReply` 前均压入 `(page, postId, scrollOffsetPx, filter)`（连续同锚点自动去重，上限 32）；`ThreadDetailPane.kt:413` 注册 `BackHandler(enabled = uiState.canPopPositionStack)`——在 Compose LIFO 顺序下晚于 `MultiPaneReadingContainer.kt:231` 的多栏返回栈 `BackHandler`，早于 `ThreadBottomToolbar` 内联跳页收起（`:224`）、`FloorContextMenuOverlay` 菜单关闭、`HiNavigationSidebar` 240dp 抽屉及 `NextAppRoot` 顶层登录/看图浮层；当右栏下钻至 `UserProfile` 时 `RightPaneSlot.Thread` 卸载让出 `BackHandler`，下钻返回后再恢复 `PositionStack` 拦截。`MultiPaneBackStackTest.kt` **0 行改动**，新增 `backPriority_threePane_popsPositionStackBeforeClosingRightPane` 验证三栏先弹位置栈再关右栏。
- **单一滚动权威（追加 #4）**：通过。全仓扫描 `scrollToItem|animateScrollToItem|requestScrollToItem|dispatchRawDelta|scrollBy|animateScrollBy|bringIntoView|\.scroll\s*\{`，`feature/threaddetail/` 内除基线未改动的 `media/PostImageBlock.kt:246` 独立单测回退分支外，全部 **9 处**调用 100% 位于 `ThreadDetailPane.kt` 的 `PrependAnchorController`（`:170, 208, 244, 256, 272, 330, 337, 341, 360`），`0` 新增外部滚动写入点。
- **`R-E2c S1-R2` 修复（追加 #1）**：通过。`ThreadDetailViewModel.jumpToFloor` 本页分支（`:544-558`）在递增 `++sessionGeneration` 前调用 `cancelPagingJobs()` 并同步复位 `isPrepending = false, isAppending = false, prependError = null, appendError = null`；`samePageJumpToFloor_duringInFlightPrependAndAppend_cancelsJobsAndAllowsSubsequentPaging` 在上下双向预取同时挂起时触发本页跳楼，验证状态立即复位且后续双向连续加载成功合入 60 楼（回退必红）。

### 2. 复合 `HiGlassRegion` 卸载门禁、三栏玻璃预算、`V-C4d` 对比度、外链白名单与独立撤销令牌（重点 2）
- **`suppressBottomGlass` 整块卸载（`B4-E4 ②`）**：通过。`ThreadDetailPane.kt:757` 与 `ThreadBottomToolbar.kt:214` 双重前置短路，在 `suppressBottomGlass == true` 时将外层 `HiGlassRegion` 与内部 3 枚 `HiGlassSurface` 整块移出组合树，子树 `HiGlassMarker == 0` 且释放 `detailGlassState.hasSourceDemand`（已由 `ThreadBottomToolbarTest.suppressBottomGlass_unmountsEntireHiGlassRegionAndReleasesSourceDemand` 锁定）。
- **玻璃预算（`≤ 3` 区域 / `≤ 2` 源 / `≤ 5` pass）**：通过。工具条左/右/中 3 枚 `HiGlassStyle.Toolbar` 胶囊包裹于单个外层 `HiGlassRegion`（仅记 `1` 个 `HiGlassMarker`），与详情 `LazyColumn`（唯一挂载 `hiGlassSource(detailGlassState)`）及 `ThreadDetailTopBar` 为严格兄弟节点。与真实 `E1` + `E3` 联立下：三栏满载 `HiGlassMarker == 3`、`LayerBackdrop == 2`、嵌套 `== 0`、静止 `drawBackdrop pass == 5`；铰链双栏 `HiGlassMarker == 3`、`LayerBackdrop == 2`、`drawBackdrop pass <= 5`；全屏阅读释放中栏后 `HiGlassMarker == 2`、`LayerBackdrop == 1`。
- **`V-C4d` 进度胶囊对比度**：通过。`resolveProgressCapsuleColors`（`ThreadBottomToolbar.kt:90-103`）采用 `primaryContainer` 浅色 `0.48f`（`≤ 0.50f`）/ 深色 `0.44f`（`≤ 0.45f`）配 `cs.onSurface` 文字（绝不用 `cs.primary`），经单测遍历全部 20 套预设 × 浅/深/OLED × 降级极端黑白背板与激活态 4 种标准背板，WCAG 对比度 100% `≥ 4.5:1`。
- **外部 URL scheme 白名单与菜单去重**：通过。`sanitizeExternalHttpUrl`（`ThreadBottomToolbar.kt:109-118`）拦截空白/控制字符并经 `java.net.URI` 严格校验 `scheme in {"http", "https"}`、非空 `host`/`rawAuthority` 且禁止 `@`，`buildSafeThreadUrl`（`:121-138`）额外要求 `tid` 全 ASCII 数字；`openExternalHttpUrl`、`shareExternalHttpUrl`、`copyThreadLinkPlainText`（严格仅 `ClipData.newPlainText`）均强制先过白名单，17 组危险/畸形载荷（`javascript:`/`JAVASCRIPT:`/`intent:`/`file:`/`content:`/`data:`/`mailto:`/`tel:`/`ftp:`/`vbscript:`/`about:`/控制字符/缺 host 等）反例测试全覆盖。“更多”菜单仅含 `刷新`、`关注`/`取消关注`、`复制链接`、`在浏览器中打开`、`分享`，严格不重复 `收藏`、`只看楼主`，不含 `举报`。
- **独立撤销令牌模型**：通过。`enqueueFavoriteMutation`（`ThreadDetailViewModel.kt:1073-1122`）用 `FavoriteUndoToken` + `snackbarResetSeq` + `CoroutineStart.LAZY` `prevJob?.join()` 串行队列实现“新操作立即关闭旧 Snackbar 并确认旧操作、旧令牌失效、撤销仅恢复当前令牌对应单步”，经 `MockWebServer` + 真 `DefaultFavoriteRepository` + 真 `HiSnackbarHost` 端到端验证。

### 3. 内联跳页条、方案 B 移除旧底栏与 `R-C5b`（重点 3）
- **内联跳页条与 `R-Dkudo`**：通过。`< 480dp` 紧凑双行、`≥ 480dp` 单行；`PageJumpSlider`（`InlinePageJumpBar.kt:242-292`）使用 `Modifier.weight(1f)` 包在解除紧最小宽的 `Box` 内并留 `10.dp` 水平聚焦边距，无任何硬编码最小宽，`340dp` + `1.25×` 下实测滑块宽 `> 60dp`、全控件 `≥ 48dp` 且零重叠零溢出；拖动滑块仅改本地预览与输入框（0 滚屏、0 网络）；`imePadding()` 仅挂在 `ThreadBottomToolbar` 底部外框（`ThreadBottomToolbar.kt:267`），注入 `280dp` IME Insets 实测跳页条上移 `> 200dp` 而底层 `LazyColumn` 偏移变化严格 `0.00dp`；展开/收起中途反向打断 `assertFrameContinuity` 逐帧连续，减动效 0ms 单帧到位。
- **方案 B 移除旧底栏与 `R-C5b`**：通过。`ThreadDetailPaginationBar` 已从 `ThreadDetailPane.kt` 彻底删除；`HiPressFeedback.kt:93` 在 `onDetach()` 补 `anim = null` 消除重挂载残留 `0.97f` 缩放，`HiRollingNumber.kt:121-123, 161-163` 将 `displayedCount` / `displayedChar` 改为普通引用容器消除组合体内写 `MutableState` 触发的额外重组，两处均有针对性单测验证。

---

## 三、既有测试改动清单逐项核查（负责人指定重点）

核查 `git diff 70da638...6526c1b` 中全部 **6 个既有测试文件**，100% 落在任务卡预授权（追加 #2）与负责人开工前裁定的 5 处授权范围内，**无任何产品预算上限或锚点精度断言被放宽，无任何有效断言被删**：

| 文件 | 核查结果 | 预算 / 精度守恒核实 |
|---|---|---|
| `MultiPaneGlassBudgetTest.kt` | 合规（裁定 #1） | 移除模拟工具条改测真实 `ThreadDetailPane`；精确区域数由 `2→3`（全屏阅读 `1→2`），**上限 `HiGlassMarker ≤ 3`、`LayerBackdrop ≤ 2`、嵌套 `== 0`、静止 `drawBackdrop pass ≤ 5` 完全未放宽**，铰链双栏还新增了 `drawBackdropCount <= 5` 断言。 |
| `ContinuousPagingAnchorTest.kt` | 合规（追加 #2 & 裁定 #5） | 仅将旧底栏文本/按钮替换为进度胶囊 `contentDescription`、`首页`/`末页` 启禁用态与 `vm.nextPage()`；两条 `e2bE2dMerge_*` 的 **`≤ 1dp` 同帧偏移断言原字未改**。 |
| `ThreadDetailScreenshotTest.kt` | 合规（方案 B） | 单详情页玻璃上限由 `assertGlassSurfaceCountAtMost(1)` 更新为 `2`（对齐 `product-design.md` §5 表格「单栏二级页帖子详情 = 2 区域」），离线图片加载断言原样保留。 |
| `ThreadDetailViewModelTest.kt` | 合规（追加 #2 & 裁定 #5） | `下一页`/`上一页` 改为 `vm.nextPage()` 与内联跳页 `首页`，退出只看恢复第 2 页 index `7` / offset `36px` 断言原样保留；`topBarGlassTopology_*` 更新为 `2` 区域、`4` `DrawBackdrop`（顶栏 1 + 底部 3，圆钮仍零嵌套）、`1` `LayerBackdrop`，并对全部玻璃区域逐一校验与 `LazyColumn` 严格兄弟关系。 |
| `FloorContextMenuTest.kt` | 合规（裁定 #4） | 因底部工具条新增“回复”文案，用 `hasAnyAncestor(PaneTitle)` 将菜单项查找限定在长按菜单内，6 项/4 项/`≥ 48dp`/零“举报”/`ReplyRequest` 及 `20px` 偏移恢复断言 **0 删减**。 |
| `ThreadInteractScreenshotTest.kt` | 合规（裁定 #3） | 3 处 `assertGlassSurfaceCountAtMost(1)` 更新为 `2` 并附注释，其余代码 0 改动。 |

---

## 四、视觉截图与像素差异独立复验（37 张 PNG 全部目视 + 像素边界校验）

1. **快照改动范围核验**：`git diff --name-status 70da638...6526c1b -- hipda/src/test/snapshots/` 共 **37 张**（新增 10 张 `thread_chrome_*.png`；更新 13 张 `openThread=true` 的 `multipane_*.png`、9 张正文态 `thread_detail_*.png`、5 张 `thread_interact_*.png`）。其余所有非授权快照（含 8 张未开帖 `multipane_*`、6 张骨架/错误/需登录/空过滤 `thread_detail_*`、`thread_interact_menu_w411_light.png` 及全部 `forum_*`/`login_*`/`shell_*`/`glass_*`/`theme_specimen_*`/`challenge_*`/`thread_image_*`）**0 像素漂移**。
2. **27 张重录 PNG 独立逐像素边界扫描结论**：
   - 13 张 `multipane_*.png`：全部差异严格落在 `y = 89.2%..100.0%`；三栏下 `x ≥ 44.3%`、铰链双栏 `multipane_hinge_twopane_w749_h559_light.png` `x = 50.5%..99.9%`（严格在右栏内，零跨分隔线/零跨铰链，左中栏与顶栏 0 像素变化）。
   - 9 张 `thread_detail_*.png` 与 5 张 `thread_interact_*.png`：全部差异严格落在底部 `y = 90.0%..100.0%`，顶部透明顶栏、只看胶囊、正文富文本/表情/图片、投票卡、引用高亮与长按菜单上半区 0 像素漂移。
3. **37 张 PNG 逐张目视检查结论**：
   - 标准三段态（`411`/`749`/`1280dp` 浅/深）：`回复`、`第 3 / 12 页`（含 25% 胶囊内裁切进度填充）、`收藏`+`更多` 排布匀称，深浅色及 OLED 纯黑下文字与图标对比清晰，宽屏严格居中限宽不贴边；
   - 窄容器紧凑态（`340dp` 浅色、`360dp` 右栏深色，`1.25×`）：左段自动收缩为 `48×48dp` 纯笔形图标，中段缩为 `12/45` 并保留进度填充，右段双图标不拥挤、无裁切；
   - 内联跳页展开态（`411dp` 浅色双行、`749dp` 深色单行）：`首页`/`第 4 / 12 页`/`末页`、滑块手柄、页码输入框 `/ 12` 与圆形 `×` 按钮对齐规整，术语 100% 对齐 `docs/design/copy.md`。

---

## 五、生产代码规模（净增 `+1,931` 行 vs 上限 `1,575` 行）评估与可削减冗余清单

1. **超行理由是否成立**：**部分成立（不构成阻断）**。
   - 经逐文件核查，全卡 **0 手写 `Layout`/`SubcomposeLayout`、0 新增 `Modifier.Node`、0 测试专用钩子/语义键**，未重蹈第三批 `C4a/C5a/C5b` 过度设计的覆辙；
   - 超出原估算 `750–1,050` 行的增量中，有约 **`~425` 行**来自估算后叠加的硬性需求：M6 对齐的独立撤销令牌串行模型（`FavoriteUndoToken` + `snackbarResetSeq` + `FavoriteError` 映射，约 `230` 行）、`DISPATCH.md` §4 外链白名单与纯文本分享/复制/浏览器辅助函数（约 `80` 行）、`V-C4d` 进度胶囊对比度解析（约 `30` 行）、追加 #1 `R-E2c S1-R2` 修复及为 `E5` 预留的 `jumpToNewReply`（约 `85` 行）。扣除这部分增量后等效约 `~1,506` 行，故超行有客观原因，不按阻断处理。
2. **可削减的具体冗余（合计可瘦身约 `~365` 行，直接压回 `< 1,575` 行上限内，列入 `S1`）**：
   - **`ThreadDetailViewModel.kt`（净增 `+507` 行 → 可省 `~135` 行）**：
     - `:1001-1057` 新增的 `loadLastPageInternal()` 与既有 `loadByPostIdInternal()`（`:810-874`）、`loadInternal()`（`:876-945`）构成**三份高度重复的单页加载模板**（`R-E2c S3` 已提示合并前两者，本卡又复制了第三份），抽取共享 `launchSinglePageLoad(...)` 可省 **`~90` 行**；
     - `:432-443` `jumpToFirstPage` 与 `jumpToLastPage` 在全仓（含 `src/main` 与 `src/test`）**0 处调用**（`InlinePageJumpBar` 直接调 `onJumpToPage(1)` / `onJumpToPage(safeLastPage)`），可直接删去省 **`~18` 行**；
     - `:189-217` 的 `2/3/4/5` 参四级串联次构造函数可合并为 1 个 `@Inject` 主构造 + 1 个默认可空参测试构造，省 **`~25` 行**。
   - **`chrome/InlinePageJumpBar.kt`（`404` 行 → 可省 `~70` 行）**：
     - `:74, 106-112` 与 `:302-315` 同时维护 `var inputText: String` 与 `rememberTextFieldState`，并用两条 `LaunchedEffect` + `snapshotFlow` 双向搬运字符串；直接将 `TextFieldState` 提升至 `InlinePageJumpBar` 可省 **`~28` 行**并消除 1 帧异步同步，合并展开过散的单参分行修饰符可再省 **`~42` 行**。
   - **`chrome/ThreadBottomToolbar.kt`（`655` 行）与 `ThreadDetailPane.kt`（净增 `+108` 行）→ 可省 `~160` 行**：
     - `ThreadBottomToolbar.kt:279-334` 用 3 次 `TextMeasurer.measure` 手算 `groupStart` / `collapsedMiddleStart` / `rightStart` X 像素偏移；未展开态直接用居中 `Row(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))` 排布、展开态叠放/伸展中段即可省去约 **`~50` 行**手算坐标，收拢单参分行修饰符与向量构建样板可省 **`~85` 行**；
     - `ThreadDetailPane.kt:550-558` 与 `:624-638`（`captureViewportAnchor`）重复计算首可见楼层页码与像素偏移，且 `:709-714` `onJumpToFloor` 借道调用 `onReadingPositionChanged`（副作用触发一次去抖写盘计时）来更新跳前锚点，改为共用 `currentViewportAnchor(...)` 并直接向 `jumpToFloor` 传 `fromAnchor` 可省 **`~25` 行**且无副作用。

---

## 六、非阻断建议汇总（Suggestions）

1. **S1（瘦身减重）**：在修复 `B1`（或 `M6-polish`）时顺手执行第五节第 2 点去重（合并 `ThreadDetailViewModel` 三处单页加载模板、删零调用的 `jumpToFirstPage`/`jumpToLastPage`、消除 `InlinePageJumpBar` 双份输入状态同步、提取 `ThreadDetailPane` 共享视口锚点计算），预计可净减约 `~365` 行，将生产净增压回 `1,575` 行预算内。
2. **S2（无障碍播报节点尺寸与底部固定留白）**：① [InlinePageJumpBar.kt:124-130](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/InlinePageJumpBar.kt#L124-L130) 的 `LiveRegionMode.Polite` 播报节点当前为 `Modifier.size(0.dp)`，部分 Android 系统无障碍服务会过滤 `0×0` 不可见节点的 `LiveRegion` 事件，建议挂在非零尺寸外层容器或 `1.dp` 节点上；② [ThreadDetailPane.kt:616](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L616) 在 `suppressBottomGlass == true` 时把 `LazyColumn` 底部留白从 `64.dp` 缩为 `8.dp`，未来 `E5` 快速回复面板在帖尾开关时可能引发底部最大滚位收缩跳动，建议恒定保留 `bottomInset + ThreadBottomToolbarClearance`。
3. **S3（报告方法名对齐）**：实现者报告 `_reports/E4.md` 第 5 节首行引用的方法名与源码 [MultiPaneGlassBudgetTest.kt:71](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneGlassBudgetTest.kt#L71)（`..._andSimulatedToolbar_...`）存在轻微出入，可将测试方法名同步重命名为 `..._andRealToolbar_...`。


---

# 二轮复审报告 R-E4（Round 2：commit `bed7fcc3df489b945d1f1316dfedc18a5078f653`）

- **复审结论**：**`APPROVED`**（0 项阻断；首轮 `B1` 及 `S1`–`S3`、`V-E4` `probeD2` 与 `rawUserInfo` 加固、`E14` `LocalHiGlassSuspended` 集成全部核验闭合；附 2 条零改动要求的信息备注 `N1`–`N2`）
- **复审对象**：`origin/card/E4-thread-chrome-and-position` @ `bed7fcc3df489b945d1f1316dfedc18a5078f653`（简称 `bed7fcc`；已合入 `origin/main@1cffcd9`，含 `E-slots`、`E8`、`E10`、`E12`、`E14`）
- **本轮增量范围**：`9ae2415..bed7fcc`（共 2 个提交：`7430d55`、`bed7fcc`；改动 6 个生产文件、5 个测试文件，`0` 张快照 PNG 变动，`0` 处 `data/*` / `feature/app/*` / `feature/forum/*` / `media/*` 改动）
- **与最新 `origin/main` 合入预演（`git merge-tree`）**：
  - 当前最新 `origin/main`：`5fa2bfb2e6dba396df057099f707f893cddc80d0`（已合入 `E14` @ `1cffcd9` 与 `M6-polish` @ `0eebc8a`）
  - `git merge-tree --write-tree origin/main bed7fcc3df489b945d1f1316dfedc18a5078f653`：退出码 `0`（生成树 `e4365a60b39b843a074c76920e046bb4f6214f27`），**文本与语义均 0 冲突**（核实 `M6-polish` 在 `MultiPaneReadingContainer.kt` 新增的 20 帖 `SaveableState` LRU 淘汰与本卡 `B1` 的 Room `NonCancellable` 持久化完全互补：超出 20 帖淘汰内存 `LazyListState` 后重开仍由 Room 精确恢复阅读位置）
- **GitHub Actions CI 核实**：[Android CI #45753025942](https://github.com/mailwmj/hipda-next/actions/runs/45753025942)
  - `headSha`：`bed7fcc3df489b945d1f1316dfedc18a5078f653`（精确匹配）
  - `status / conclusion`：`completed / success`（`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿）
- **只读纪律核实**：主仓库 `/home/mir/Documents/hipda-next` 全程仅执行只读命令（`git fetch` 后远端推进至 `5fa2bfb`），工作区 `0` 文件改动、未执行任何本地构建。

---

## 七、负责人指定二轮核查项逐条审计

### 1. `B1` 修复核验：`NonCancellable` 串行落盘 + `onCleared` 兜底、假仓库真实挂起与跨 VM 安全性

1. **`NonCancellable` 串行落盘 + `onCleared()` 兜底机制**：
   - [ReadingPositionController.kt:87-112](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionController.kt#L87-L112) 新增 `activeSaveJob` 与 `enqueueNonCancellableSave(snapshot)`：`onPositionObserved`（`:83`）与 `flushIfHydrated`（`:94`）均在通过 `if (!hydrated) return` 首轮恢复门闩后，以 `scope.launch(NonCancellable) { runCatching { prev?.join(); savePosition(snapshot) }.onFailure { if (it is CancellationException) throw it } }` 串行执行落盘。`B4-E4 ①` 未水合零写盘保证丝毫无损。
   - [ThreadDetailViewModel.kt:275-278](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L275-L278) 重写 `onCleared()` 调用 `readingPositionController.flushIfHydrated()`。即便 AndroidX `ViewModel.clear()` 先关闭 `CloseableCoroutineScope`（取消 `viewModelScope`）再回调 `onCleared()`，`scope.launch(NonCancellable)` 会用 `NonCancellable`（`isActive == true`）替换上下文中的已取消 `Job`，同时保留原 `CoroutineDispatcher`（生产为 `Dispatchers.Main.immediate`，单测为 `TestDispatcher`），确保兜底协程依然正常启动并跑完。
2. **测试假仓库是否真实挂起、撤回修复是否必红**：
   - **真实挂起且撤回必红**。[ReadingPositionAndStackTest.kt:794-805](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionAndStackTest.kt#L794-L805) 在 `PositionTestRepository.saveReadingPosition` **写入 `savedPositions[tid]` 之前**调用 `saveHook`，钩子内依次执行 `saveStarted.complete(Unit)`、`yield()`（强制让出 `Main.immediate`）与 `saveGate.await()`（阻塞在未完成的 `CompletableDeferred` 上）。
   - 两条回归测试（[ReadingPositionAndStackTest.kt:556-761](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionAndStackTest.kt#L556-L761)）覆盖全部 3 条销毁路径：① 单栏停手 `< 400ms` 按系统返回触发真 `MultiPaneReadingContainer` / `MultiPaneSlotHolder.onDispose` 同帧 `releaseThreadStore`；② 三栏停手 `< 400ms` 切帖（`8202 → 8203`）退场清空旧 `ViewModelStore`；③ 直接调用 `directStore.clear()` 验证 `viewModelScope` 先被取消后的 `onCleared()` 兜底。三条路径均先断言 `clearedVms.contains(vm)` 且 `!vm.viewModelScope.isActive`，再放行 `saveGate.complete(Unit)` 断言新位置落盘——若撤回 `NonCancellable`，`saveGate.await()` 在 `ViewModelStore.clear()` 当场抛 `CancellationException` 中止于赋值之前，`savedMap[tid]` 为 `null` 必红；若撤回 `onCleared()`，路径 ③ `onClearedSaveStarted.isCompleted` 为 `false` 必红。
3. **`ViewModel` 清除后是否会访问已释放对象，或与新 `ViewModel` 的写入乱序**：
   - **绝不访问已释放对象**：`snapshot` 是值拷贝且已规范化的 `@Immutable ReadingPositionSnapshot`；`savePosition` 闭包（[ThreadDetailViewModel.kt:223-225](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L223-L225)）仅调用 `@Singleton DefaultThreadDetailRepository.saveReadingPosition` → `@Singleton ReadingPositionDao.upsert`（`SingletonComponent` 全局 Room 实例），不读取任何 `ViewModel` 可变状态、`Closeable`、`Context` 或 `Activity`（旧 `ViewModel` 实例仅被闭包隐式持有至 ~1–5ms 的 SQLite `UPSERT` 结束即释放，且外包 `runCatching` 防护异常）。
   - **跨 `ViewModel` 写入顺序评估**：
     - **常规路径（无积压旧写，占 99.9%+ 场景）严格保序**：`viewModelScope` 使用 `Dispatchers.Main.immediate`；当旧 `VM1` 在主线程 `onDispose`/`onCleared` 中调用 `enqueueNonCancellableSave` 且 `prev == null`（或已完成）时，协程在主线程**同步直达** `readingPositionDao.upsert` → `DBUtil.performSuspending`，在 `onDispose` 返回前已将 `UPSERT` 任务同步排入 `@Singleton HiDatabase.transactionExecutor`（Room 全局单线程 FIFO 事务队列）。即便用户随后立刻重进同一 `tid` 创建 `VM2`，`VM1` 的写入在事务队列中也必然先于 `VM2` 的任何后续写入执行，不会倒序覆盖。
     - **极端边界（非阻断，记入 `N1`）**：① 因 Room `readingPositionDao.get(tid)` 走 `queryExecutor`（WAL 并发读连接），若用户在退帖后 1–5ms 内（`UPSERT` 尚未提交前）秒点回同一 `tid`，`VM2` 首读可能读到上一版进度；② 因 `activeSaveJob` 挂在每实例 `ReadingPositionController` 上，仅当 `VM1` 已有前序 `prev` 写盘因极端磁盘卡顿挂起 `> 400ms`（使 `flush` 停在 `prev.join()` 尚未入 `transactionExecutor`），且 `VM2` 在该卡顿窗口内完成网络加载 + 水合 + 400ms 去抖写盘时，才存在理论乱序窗口。两者若需彻底根除，应在仓库层 `DefaultThreadDetailRepository` 加按 `tid` 或全局的读写串行 `Mutex`（属 `data/*`，在本卡禁改边界外，不构成阻断）。

### 2. `S1` 瘦身重构审计：生产净增 `+1,931` → `+1,479` 行（`≤ 1,575` 上限），行为 100% 等价

按 `lesson.md`「简化触及已验证并发/状态代码须按重写重审」逐文件核对 `1cffcd9...bed7fcc`（生产总计 `+1,744 / -265 = +1,479` 行）：
- **[ThreadDetailViewModel.kt](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt)（净增由 `+507` 降至 `+423` 行）**：
  - 抽取 `launchSinglePageLoad` + `commitSinglePageSuccess`（`:789-932`）统一 `loadByPostIdInternal`、`loadLastPageInternal`、`loadInternal` 三处单页加载模板：逐字段核对确认 `sessionGeneration` 单调递增、`cancelPagingJobs()`、`loadJob?.cancel()`、`keepExistingFloorsOnError` 分支（`loadByPostId`/`loadLastPage` 在已有楼层报错时保留原楼层并发 `ThreadDetailNotice`，空楼层报错时置 `error` + `challengeFailure`）、`loadInternal` 专属 `onAuthRetry`（`!authRetryAttempted && authVersion != startedAuthVersion`）、`loadByPostIdInternal` 清空 `preFilterAnchor`、`markHydratedIfNeeded(curTid)` 调用时机以及 `scrollTokenSeq`/`highlightedFloor` 完全等价（且补齐了首轮 `loadLastPageInternal` 空楼层报错时漏写 `challengeFailure` 的细节）。
  - 合并次构造函数至 `initOptionalRepositories`（`:189-207, :261-273`），保留 2/3/4/5 参全部构造兼容；删除全仓零调用的 `jumpToFirstPage`/`jumpToLastPage`。
- **[InlinePageJumpBar.kt](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/InlinePageJumpBar.kt)（由 `404` 降至 `280` 行）**：
  - 将 `rememberTextFieldState` 直接提升至 `InlinePageJumpBar`（`:73-103`），删除双份字符串状态与两条 `LaunchedEffect`/`snapshotFlow` 异步桥接；核实初始挂载、滑块拖动同步改字、键盘清空/越界回退 `sliderPreviewPage`、合法目标页即时切换 `✓`/`×` 按钮及外部 `currentPage` 变更同步等全部分支行为一致，且消除了 1 帧异步延迟。
- **[ThreadBottomToolbar.kt](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt)（由 `655` 降至 `444` 行）**：
  - 保持原 `BoxWithConstraints` + `TextMeasurer` + `animateDpAsState` 几何计算与弹簧可打断过渡逻辑零改动，仅收拢单参多行修饰符与矢量路径排版，CI 全部 37 张 Roborazzi 快照零像素差异通过。
- **[ThreadDetailPane.kt](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt)（净增由 `+108` 降至 `+83` 行）**：
  - 提取共享纯函数 `resolveViewportAnchor(s, listState, fallbackToFirst)`（`:780-797`）；`onJumpToFloor`（`:684`）改为直接向 `viewModel.jumpToFloor(targetPid, targetTid, fromAnchor)` 传入跳前视口锚点，消除了首轮借道 `onReadingPositionChanged` 触发无谓去抖计时的副作用；全包滚动写入点依然唯一收口于 `PrependAnchorController`（共 9 处）。

### 3. `S2` 与 `S3` 修复核验

- **`S2 ①`（无障碍播报节点非零尺寸）**：[InlinePageJumpBar.kt:113-120](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/InlinePageJumpBar.kt#L113-L120) 将 `LiveRegionMode.Polite` 播报节点由 `0.dp` 改为 `1.dp`，并由 [InlinePageJumpBarTest.kt:229-232](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/InlinePageJumpBarTest.kt#L229-L232) 断言 `boundsInRoot` 宽高均 `> 0f`。
- **`S2 ②`（`suppressBottomGlass` 下恒定底部留白）**：[ThreadDetailPane.kt:608](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L608) 固定 `fixedBottomClearance = bottomInset + ThreadBottomToolbarClearance`，并由 [ThreadBottomToolbarTest.kt:233-270](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L233-L270) 验证滚至帖尾后切换 `suppressBottomGlass = true` 时 `afterContentPadding` 与首可见楼层索引/像素偏移严格 `0` 跳动。
- **`S3`（预算测试方法名对齐）**：[MultiPaneGlassBudgetTest.kt:71, 92](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneGlassBudgetTest.kt#L71) 已重命名为 `..._andRealToolbar_...` / `..._withRealThreeCapsuleToolbar_...`，与 `_reports/E4.md` 完全一致。

### 4. `E14` `LocalHiGlassSuspended` 在底部工具条及详情页的联动核验

- 链路核实：`origin/main@1cffcd9` 的 [NextAppRoot.kt:152](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L152) 在看图浮层打开期间注入 `LocalHiGlassSuspended provides isImageViewerOpen`。`ThreadBottomToolbar` 外层 `HiGlassRegion`（[HiGlassSurface.kt:231-235](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt#L231-L235)）在 `glassSuspended == true` 时自动摘除 `HiGlassMarker`；内部左/右/中 3 枚 `HiGlassSurface`（含展开态跳页胶囊，`:535-560, :629-633`）自动切至 `SOLID_DEGRADED`（`HiGlassBlurActive = false`、不挂 `drawBackdrop`），并通过 `DisposableEffect(state, useBackdrop)` 释放 `detailGlassState` 采样计数；当 `ThreadDetailTopBar` 与 `ThreadBottomToolbar` 同步释放后，`LazyColumn.hiGlassSource(detailGlassState)`（`:290-293`）因 `hasSourceDemand == false` 自动卸下 `layerBackdrop` 离屏录制。
- 单测核实：[ThreadBottomToolbarTest.kt:279-366](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L279-L366) 同时覆盖独立 `ThreadBottomToolbar`（收起态 + 展开跳页态）与完整 `ThreadDetailPane`，验证 `LocalHiGlassSuspended = true` 时 `HiGlassMarker == 0`、`HiGlassBlurActive(true) == 0`、`layerBackdropCount == 0`、`drawBackdropCount == 0`，恢复 `false` 后精确回到 `2` 区域 / `1` 采样源 / `4` `DrawBackdrop` pass。

### 5. `V-E4` `probeD2` 修法（`HiNavigationSidebar.kt`）与 `rawUserInfo` 拦截定性

1. **`probeD2` 修法审计（[HiNavigationSidebar.kt:284-289](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L284-L289)）**：
   - **根因与修法**：`HiAdaptiveShell` 在应用启动时常驻挂载 `HiNavigationSidebarModalOverlay`，旧实现 `BackHandler(enabled = visible)` 在 `t = 0` 注册，导致 `t = 1` 进帖后挂载的 `ThreadDetailPane` `PositionStack` `BackHandler` 在 `OnBackPressedDispatcher` 的 LIFO 链表中排在模态抽屉之前。本轮改为 `if (visible) { key(visible) { BackHandler(enabled = true, onBack = onDismiss) } }`：抽屉关闭时完全移出组合与分发器，每次在 `w600dp` 打开抽屉时重新注册于 LIFO 栈顶，严格满足「模态抽屉浮层 > 详情 `PositionStack` > 宿主多栏/退帖返回」。（同时核查 [NextAppRoot.kt:288-302](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L288-L302)：看图浮层与登录/账号浮层均位于 `if (args/mode != null)` 条件分支内按需后挂载，天然位于 LIFO 最顶层，无同类隐患。）
   - 已由 [HiAdaptiveShellTest.kt:2196-2235](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/ui/shell/HiAdaptiveShellTest.kt#L2196-L2235) 与 [ReadingPositionAndStackTest.kt:502-547](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ReadingPositionAndStackTest.kt#L502-L547) 双重回归锁定。
2. **「`rawUserInfo` 拦截」是什么、是否属于测试钩子或不必要的绕法**：
   - **具体内容**：位于 [ThreadBottomToolbar.kt:99](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt#L99) `sanitizeExternalHttpUrl` 中的 `!uri.rawUserInfo.isNullOrEmpty()` 判断（来源为独立验证报告 [V-E4.md:110-112](file:///home/mir/Documents/hipda-next.wt/_reports/V-E4.md#L110-L112) §6.2 建议）。
   - **定性结论**：**绝非测试钩子，亦非不必要的绕法，而是正当且必要的 RFC 3986 URL 安全白名单加固**。首轮代码写有 `host.contains('@')` 意图拦截带用户凭证或域名混淆的链接（如 `https://user:pass@www.4d4y.com/...` 或 `https://www.4d4y.com@evil.example/...`），但 `java.net.URI` 解析标准 authority 时会将 `@` 前缀拆入 `uri.rawUserInfo`，使 `uri.host` 不含 `@` 从而绕过原检查。补上 `!uri.rawUserInfo.isNullOrEmpty()` 修复了该解析盲区，与主干 `E10` 的 [ForumLinkGuard.kt:13](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/feature/search/ForumLinkGuard.kt#L13)（`it.username.isEmpty() && it.password.isEmpty()`）完全对齐，并在 [ThreadBottomToolbarTest.kt:440-441](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L440-L441) 补齐反例断言；全卡生产代码 `0` 测试钩子。

---

## 八、非阻断信息备注（无需返工）

1. **N1（跨 `ViewModel` 极端窗口备忘，属 `data/*` 范畴）**：常规退帖重进下 `NonCancellable` 经 `Main.immediate` 同步入队 Room 单线程 `transactionExecutor`，写入严格保序；若未来希望连「1–5ms 内秒重进同一 `tid` 时 WAL 并发读早于 `UPSERT` 提交」的微秒级窗口也一并消除，可在 `DefaultThreadDetailRepository` 内对 `readingPositionDao` 的读写加轻量 `Mutex` 串行化。
2. **N2（微小语法冗余）**：[HiNavigationSidebar.kt:285-288](file:///home/mir/Documents/hipda-next.wt/E4/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L285-L288) 中 `if (visible)` 分支内的 `key(visible)` 因分支内 `visible` 恒为 `true` 实际由外层 `if (visible)` 单独完成卸载/重挂载，保留无任何副作用。
