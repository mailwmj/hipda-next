# 独立代码审查报告 R-E1：论坛页完整版（commit `2e8bed9`）

- **审查结论**：**`APPROVE`**（0 阻断项，4 项非阻断建议 `S1`–`S4`）
- **审查对象**：`origin/card/E1-forum-page` @ `2e8bed99182e74be5e1e4c921efd5140da87db2a`（简称 `2e8bed9`）
- **对比基线**：`db7dba6962b4a9ce61a81b19ab32fe643c0d5232`（`git merge-base db7dba6 2e8bed9 == db7dba6`）
- **并行合入预检（对照同波 `E2a`）**：`git merge-tree --write-tree 2e8bed9 origin/card/E2a-thread-detail-core` 输出树 `a395c557ab7bdb21524e9ace82dc9d2e3da74541`（退出码 `0`，**0 冲突、0 重叠文件**）
- **GitHub Actions CI 核实**：[Android CI #45680444796](https://github.com/mailwmj/hipda-next/actions/runs/45680444796)
  - `headSha`：`2e8bed99182e74be5e1e4c921efd5140da87db2a`（与分支提交 100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139439266358`）：**`completed / success`**，对照 `.github/workflows/android.yml` 核实已完整通过 `./gradlew assembleDebug assembleRelease lintDebug`、Room schema 一致性检查、`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 Debug/Release APK 包名校验。

---

## 一、阻断项（Blocking Issues）

**无**（对照 `docs/agents/DISPATCH.md` §4 与 `R-E1.md` 阻断标准逐项核查通过：CI 全绿、账号异步水合与重登自动恢复、`LazyColumn` 展开/折叠 `contentPadding(top)` 恒定 0dp 差值、`今日新帖` 翻页携带并保底 `searchId` 且接入黑名单/已读响应式过滤、退登清空 `todayNewState`、透明顶栏与 `LazyColumn` 严格兄弟拓扑且铰链双栏 0 玻璃、无 `#FFB003` 且荧光笔严守峰值 alpha 上限、`V-C1` 20 预设深色/OLED 对比度 `≥ 4.5:1` 且显著拉大已读/未读区分度、全路径遵守 `R-D4 S1` 显式布尔 `sortByPostTime`、`ForumPane` 与 `ForumViewModel` 锁定签名未破、无越界文件改动、生产代码 0 测试钩子）。

---

## 二、负责人补充 4 项重点逐条核查

### 重点 1：生产代码净增 `+919` 行（`+1,018 / -99`，共 1,207 行），逐文件可删清单

生产代码未超卡片 `900 × 1.5 = 1,350` 行硬上限，无手写 `Layout` / `Modifier.Node`，无测试专用钩子。但逐文件审视，共约 **97–122 行**可安全删减，删后净增约 **`800–820` 行**（落回 `650–900` 估算中位）：

| 生产文件（`hipda/src/main/`） | 总行数（净增） | 具体可删行号与简化做法 | 可删行数 |
|---|---:|---|---:|
| `feature/forum/ForumPane.kt` | 254（`+111`） | ① **`:66-80`**：`refreshErrors` 收集块手写重复了 `when (act) { Retry / Login / Reverify }`，可直接复用 `UiError.kt` 已有的 `err.toStateAction(viewModel::retry, onOpenLogin)`（同文件 `:121` 已调用）；<br>② **`:87-92`**：`ownGlassState` 与 `topBarGlassState` 拆成两段重复条件，合并为单表达式 `if (!chromeSpec.useGlassTopBar) null else shellContentScope?.shellGlassState ?: chromeSpec.externalGlassState ?: rememberHiGlassState()`；<br>③ **`:128-133`**：`glassSourceModifier` 的 `externalGlassState` 与 `topBarGlassState` 两分支均调 `Modifier.hiGlassSource(...)`，可二合一为 `(chromeSpec.externalGlassState ?: topBarGlassState)?.let(Modifier::hiGlassSource) ?: Modifier`。 | **~14–19 行** |
| `feature/forum/ForumViewModel.kt` | 286（`+141`） | ① **`:58, 107, 218, 221, 260`**：`ForumAuxState.settledQueries` 完全冗余——`DefaultThreadListRepository.refresh` 正常返回时 `fetchedPage >= 1`（含 `EMPTY_PAGE` 同时置 `stopReason != null`），失败返回时 `err != null` 已被 `:108` `&& err == null` 拦截，可直接删去 `settledQueries`；<br>② **`:84, 97, 109, 217, 240-242, 259, 263-264`**：`refreshInternal` 仅刷新当前 `activeSource.value`，无需额外维护 `lastItemsBySource` 缓存表；且 `:240` `observeFiltered(flowOf(mapped)).first()` 与 `:82` `todayNewItems` 重复建流查库，直接取 `todayNewItems.first()` 即可；<br>③ **`:86-87`**：`activeTodayNewJob` 与 `activeForumRefreshJob` 互斥，合并为单一 `activeRefreshJob: Job?`。 | **~22–26 行** |
| `feature/forum/ForumTopBar.kt` | 255（`+255`） | ① **`:61-62, 96-119`**：`ForumTopBarScrollState` 手写 `settleJob` + `currentVelocity` + `try/finally animate(...)` 状态机（且 `onPostFling` 并未传入松手初速，`currentVelocity` 仅在动画中途被 `expand()` 打断时非零），直接改用标准 `Animatable(0f)`（跟手 `snapTo`、松手/展开 `animateTo(..., HiMotion.spatialDefault(...))`）即可原生继承速度并兼容减动效；<br>② **`:186` 与 `:203`**：`ForumTopBarBackground` 的玻璃分支与纯色分支外层重复包了 `Box(modifier.graphicsLayer { translationY = -scrollState.collapseOffsetPx })`，上提到 `:158` 调用处即可。 | **~28–34 行** |
| `feature/forum/BalancedForumTabRow.kt` | 196（`+196`） | **`:83, 94, 99-132`**：`fitBalanced` 为真/假时把整棵 `Row { tabs.forEach { ForumTabItem(...) } }` 复制了两遍（跨越阈值时还会销毁重建子项动画态），且 `:83` 用 `16.sp` 测量后 `:94` 又乘了一次 `SelectedTabScale(1.07f)`（实际 `:170` 为 `15.sp * 1.07 = 16.05sp`）；合并为单个 `Row` 按 `fitBalanced` 切换 `Modifier.weight(1f)` 与 `horizontalScroll` 即可。 | **~18–28 行** |
| `feature/forum/ThreadListRow.kt` | 142（`+142`） | **`:134-142`**：9 行 `formatReplyViewText` 的 4 分支 `when` 可直接简化为单行 `listOf(reply.trim(), view.trim()).filter(String::isNotEmpty).joinToString("/")`。 | **~8 行** |
| `feature/forum/NewThreadsPill.kt` | 57（`+57`） | **`:38-55`**：外层 `Box(clickable + minHeight=48.dp + hiPressScale)` 包内层非点击 `Surface` 双层冗余，直接合并为单个带 `onClick` 的 M3 `Surface`（同时补齐 §6.3 按钮按压状态层，原来 `indication = null` 仅有缩放无状态层）。 | **~7 行** |
| `res/values/strings_forum.xml` | 17（`+17`） | 9 条字符串全部被 `ForumTopBar`、`NewThreadsPill`、`ThreadListRow` 实际引用，0 冗余，无需删减。 | **0 行** |

---

### 重点 2：透明吸顶顶栏滚动时是否触发整页重组 + 测试断言核查

1. **生产代码逐行核查结论：滚动期间 0 整页重组、0 顶栏重组**
   - **`ForumPane.kt`**：
     - `:105-107`：`isScrolledState` 用 `derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }` 封装，且在 `ForumPane` 主体**零读取**，仅通过 lambda `isScrolled = { uiState.items.isNotEmpty() && isScrolledState.value }`（`:223`）下推至叶子组件 `ForumTopBarBackground`（`ForumTopBar.kt:182`）。列表持续滚动中布尔值恒为 `true`，仅在滚离/回到 `(0, 0)` 临界点时令叶子 `ForumTopBarBackground` 重组 1 次；
     - `:134-141`：`shouldAutoLoadMore` 同样由 `derivedStateOf` 守卫，常规中段滚动布尔值恒为 `false`，不触发 `ForumPane` 重组；
     - `:213`：`NewThreadsPill` 在 `.graphicsLayer { translationY = -topBarScrollState.collapseOffsetPx }` 阶段读取折叠偏移，零组合期读取。
   - **`ForumTopBar.kt`**：
     - 全文件所有连续变化量读取均位于 Draw/Layer 阶段：`:162`、`:186`、`:203` 的 `.graphicsLayer { translationY = -scrollState.collapseOffsetPx }`，`:224` 的 `.graphicsLayer { alpha = (1f - scrollState.collapseFraction)... }`，以及 `:192`/`:196`/`:203` 在 `graphicsLayer` / `drawBehind` 中读取 `glassAlpha.value` 与 `solidColor.value`。组合期零读取 `collapseOffsetPx` / `collapseFraction`。
2. **测试断言核查结论（`ForumTopBarScrollTest.kt:360-463`）**：
   - **确系断言重组次数而非只截图**：`continuousScroll_topBarAndRowsHaveZeroRedundantRecompositions` 使用 `RecompositionCounter`（`MotionTestHelpers.kt:182`，`@NonRestartableComposable Track()` + `SideEffect { count++ }`）分别跟踪顶栏与可见行，在执行 6 步下滑 + 6 步上滑后断言 `assertEquals(topBarCountAfterInit, topBarCounter.count)`（`:434`）及每行 `assertEquals(beforeCount, afterCount)`（`:438`）。
   - **发现的测试防伪造盲区（非阻断，列为建议 `S1`）**：
     1. 测试自建了 `Box + LazyColumn + TrackedTopBar(isScrolled = { true })`（`:374-403, :456`），**未挂载真实 `ForumPane`**——若未来有人在 `ForumPane` 主体误读 `listState.firstVisibleItemScrollOffset` 引发整页重组，该测试不会捕获；
     2. `topBarCounter.Track()` 放在外层包装函数 `TrackedTopBar`（`:448`），而 `ForumTopBar` 本身是独立可重启 `@Composable` 作用域——若有人在 `ForumTopBar` 内部直接读取 `scrollState.collapseOffsetPx`，Compose 只会重跑 `ForumTopBar` 自身的 `RecomposeScope` 而不重跑外层 `TrackedTopBar`，`topBarCounter.count` 仍保持不变（假绿）。建议后续将探针迁入真实 `ForumPane` 并同时校验内层作用域或 lambda 读取计数。

---

### 重点 3：三项修复的代码与“回退修复即变红”单测逐条核查

| 审查项 | 生产实现位置 | 单测位置 | 回退修复后的变异验证结果 |
|---|---|---|---|
| **① 换账号或退登时重置今日新帖**（`R-E0 R2-S1`） | `ForumViewModel.kt:146, 152-155, 256-261`：`uidChanged = key.uid != prev.uid` 命中时调用 `resetForAuthChange(clearItems = true)`，执行 `todayNewState.value = TodayNewState()` 清空旧账号 `rawThreads` / `searchId` 并重拉。 | `ForumViewModelTest.kt:178-212`（`logoutAndAccountSwitch_resetTodayNewStateAndShowLoginErrorInsteadOfStaleItems`） | **回退必败**：若删去 `:259` 的 `todayNewState.value = TodayNewState()`，`authRepo.logout()` 后 `getNewPosts` 返回 `SearchError.AuthRequired`，`todayNewState.rawThreads` 残留 `"9001"`，`:208` `assertTrue(vm.uiState.value.items.isEmpty())` **立即失败**。<br>*细粒度补充（见 `S2`）*：该测试方法名虽含 `AndAccountSwitch`，方法体实际只执行了 `logout()`（`uid: "42" -> null`），未单独走 `accountA -> accountB` 切号时清空后台 `TodayNew` 缓存的断言（生产逻辑 `uidChanged` 已统一覆盖）。 |
| **② `searchId` 回退保底**（`R-E0 R2-S1`） | `ForumViewModel.kt:230, 237`：第 2 页+ 传 `todayNewState.value.searchId`，合并时写为 `searchId = if (append) (res.searchId ?: cur.searchId) else res.searchId`。 | `ForumViewModelTest.kt:216-270`（`todayNewPagination_carriesSearchIdAndRetainsPreviousSearchIdWhenPageOmitsIt`） | **回退必败**：测试令第 1 页返回 `"sid-initial-777"`、第 2 页返回 `searchId = null`（`:235`），再调第 3 页 `loadMore()`。若回退为 `searchId = res.searchId`，第 3 页请求参数变为 `3 to null`，`:263-266` `assertEquals(listOf(1 to null, 2 to "sid-initial-777", 3 to "sid-initial-777"), recordedCalls)` **立即失败**。 |
| **③ 慢网切版块不闪空态**（`R-E0 S3`） | `ForumViewModel.kt:96, 107-108, 121-136`：`init` 改用 `.collectLatest` 取消慢网旧请求（`CancellationException` 回滚 `initializedQueries`），且在 `fetchedPage == 0 && stopReason == null && err == null`（及 `!tn.hasLoadedOnce`）期间强制 `loading = true` 展示骨架屏。 | `ForumViewModelTest.kt:357-414`（`slowNetworkSourceSwitch_neverFlashesEmptyStateAndStartsNewForumImmediately`） | **回退必败**：测试切用 `StandardTestDispatcher`，用 `gateForum2` 卡住版块 2 后切版块 6 并逐帧收集 `observedStates`。若回退为基线 `.collect`，版块 6 请求被版块 2 阻塞，`:385` `assertTrue(refreshQueries.any { it.fid == 6 })` 与 `:401` `assertFalse(flashedEmptyDuringSwitch)` **双双失败**；同时 `:403-408` 反向验证真无帖版块 7 加载完成后正常显示空态（防把 `isLoading` 永远写死为 `true`）。（建议 `S2` 可顺手补 `2 -> 6 -> 2` 往返断言锁死 `:132` `initializedQueries.remove(q)`）。 |

---

### 重点 4：与同波 `E2a` 并行零重叠 & 零改动 `feature/app/*` 核实

1. **`E1` 全量改动文件核实（`git diff --name-status db7dba6...2e8bed9`，共 33 个文件）**：
   - 生产代码（7 个）：仅 `hipda/src/main/java/net/jejer/hipda/feature/forum/{BalancedForumTabRow,ForumPane,ForumTopBar,ForumViewModel,NewThreadsPill,ThreadListRow}.kt` 与 `hipda/src/main/res/values/strings_forum.xml`；
   - 测试与快照（26 个）：仅 `hipda/src/test/java/net/jejer/hipda/feature/forum/{ForumListBehaviorTest,ForumScreenshotTest,ForumTopBarScrollTest,ForumViewModelTest}.kt` 与 22 张新增 `hipda/src/test/snapshots/images/forum_*.png`。
2. **与 `origin/card/E2a-thread-detail-core` 交叉比对**：
   - `E2a` 改动严格落在 `feature/threaddetail/**`、`strings_thread_detail.xml` 与 `thread_detail_*.png`（共 29 个文件）；
   - 两者改动文件交集为 **`0`**；`git merge-tree --write-tree 2e8bed9 origin/card/E2a-thread-detail-core` 实测零冲突干净合并。
3. **禁止目录与共享配置核实**：
   - `feature/app/*`、`feature/common/*`、`feature/threaddetail/*`、`feature/login/*`、`ui/*`、`data/*`、`di/*`、`src/debug/*`、`MotionTokenScanTest.kt`、`CopyTerminologyScanTest.kt`、`build.gradle.kts`、`libs.versions.toml` 及全部既有非 `forum_*` 快照均为 **0 改动**；
   - `ForumPane` 顶层 13 参签名（`ForumPane.kt:41-55`）与 `ForumViewModel` 5 参 `@Inject` 构造函数（`ForumViewModel.kt:69-75`）与基线 `db7dba6` 100% 一致。

---

## 三、其余卡片审查项核查摘要

1. **严格兄弟玻璃拓扑与铰链纯色降级（`R-E1` 重点 1）**：
   - `ForumPane.kt:111-231` 中 `LazyColumn`（挂 `shellGlassSource()` / `hiGlassSource`）与 `ForumTopBar`（内部 `HiGlassSurface(style = HiGlassStyle.TopBar)`）同处 `BoxWithConstraints` 直接子级，无任何祖先/后代包含关系（`ForumTopBarScrollTest.kt:333-347` 双向验证 `isDescendantOf == false` 且单栏总玻璃数 `== 2`）；
   - `chromeSpec.useGlassTopBar == false` 时 `topBarGlassState = null`，`ForumTopBar` 渲染纯色 `surface → surfaceContainer`（0 `HiGlassMarker`），同时 `LazyColumn` 保留 `shellGlassSource()` 供铰链左栏紧凑导航胶囊采样，符合 `product-design.md` §5 拓扑矩阵。
2. **荧光笔高光、`V-C1` 对比度与 `< 300dp` 窄中栏（`R-E1` 重点 1 & 3）**：
   - `BalancedForumTabRow.kt:53-58` 使用 `primaryContainer` 并以 `floor(rawPeak * 255f) / 255f` 消除 8-bit 舍入超界（浅色 `0.5490f ≤ 0.55f`、深色 `0.4471f ≤ 0.45f`），`src/main` 零 `#FFB003`；
   - `ThreadListRow.kt:36, 40-44` 以 `onSurfaceVariant.copy(alpha = 0.74f)` + `FontWeight.Normal` 校准深色/OLED 已读标题，`ForumListBehaviorTest.kt:102-134` 覆盖 20 预设 × 浅/深/OLED × 常态/选中态全部 `≥ 4.5:1` 且深色对比度差值 `≥ 5.0`、比值 `≤ 0.60`；
   - `< 300dp`（如 `749×559` 紧凑三栏 `274dp`、`933×696` `298dp`）自动取消左侧 `40dp` 头像列，改为副信息行 `20dp` 圆头像 + 作者优先省略 + 回复/查看与时间不换行；
   - 目视抽检 `forum_w411_h891_light.png`、`forum_w411_h891_dark.png`、`forum_w411_h891_scrolled_collapsed_light.png`、`forum_w749_h559_light.png`、`forum_w933_h696_dark.png`、`forum_w340_h800_narrow_light.png`、`forum_w411_h891_step5_font130_light.png`、`forum_w891_h411_landscape_light.png` 共 8 张关键快照：排版、荧光笔底条、吸顶折叠态与新帖胶囊、深色已读弱化层次、窄中栏与大字号均准确无裁切。
3. **`R-D4 S1`、刷新 `tid` diff 与文案/动效门禁（`R-E1` 重点 2 & 3）**：
   - `ForumViewModel.kt:101, 129, 164, 269` 全部显式传入 `s.isSortByPostTime(fid)`；`今日新帖` 下隐藏无意义的更多排序菜单；
   - `computeTopNewThreadsCount`（`ForumViewModel.kt:272-282`）以首个非置顶帖为锚点统计顶部新增 `tid`，旧帖顶起或无变化时不误弹胶囊，点击胶囊清除计数并滚回顶部且恢复顶栏展开；
   - `strings_forum.xml` 文案逐字匹配 `docs/design/copy.md`，动效全部取自 `HiMotion` / `hi*` 过渡函数，白名单 0 新增。

---

## 四、非阻断建议项（Suggestions）

- **S1（加固滚动 0 重组单测防伪造能力，`ForumTopBarScrollTest.kt:360-463`）**：当前测试自建 `Box + TrackedTopBar(isScrolled = { true })` 且把 `RecompositionCounter.Track()` 放在 `ForumTopBar` 外部包装作用域，无法捕获 `ForumPane` 主体或 `ForumTopBar` 内部作用域的误重组；后续触碰测试时建议直接挂载真实 `ForumPane` 并对内层读取/组合计数断言。
- **S2（补齐切号清空 `TodayNew` 与慢网往返细粒度断言，`ForumViewModelTest.kt:178-212, 357-414`）**：`logoutAndAccountSwitch_...` 仅执行了 `logout()`，可补一段在版块页执行 `accountA -> accountB` 后切回 `TodayNew` 不残留 A 数据的断言，并在慢网测试补 `Forum(2) -> Forum(6) -> Forum(2)` 往返断言以锁死 `:132` 取消回滚 `initializedQueries.remove(q)`。
- **S3（跨账号切换时屏蔽非 `AuthRequired` 错误下的旧账号版块列表缓存，`ForumViewModel.kt:105-106`）**：当前 `staleAuth` 仅在 `err is ThreadListError.AuthRequired` 时隐藏 `DefaultThreadListRepository` 内存旧条目；若切到无某高权限版块权限的账号 B 返回 `ThreadListError.SiteError`，建议按 `uid` 世代一并屏蔽上一账号遗留的 `load.items`。
- **S4（按第二节表格精简 ~97–122 行冗余代码并补齐胶囊/标签按压状态层）**：后续收尾或维护 `feature/forum/` 时，可按第二节逐文件清单改用 `Animatable`、合并 `BalancedForumTabRow` 双分支 `Row`、复用 `UiError.toStateAction`、删去冗余 `settledQueries` / `lastItemsBySource`，并将 `NewThreadsPill` 合并为带状态层的单层 `Surface(onClick = ...)`。


---

# 二轮复审报告（Round 2：修复轮 1 @ `e24e3c0`）

- **复审结论**：**`APPROVE`**（0 阻断项，一轮 `S1`–`S4` 与 `V-E1` `F1`/`F2` 全部核实闭合）
- **复审对象**：`origin/card/E1-forum-page` @ `e24e3c0107dff2721e22b811d182d88480bf3a27`（简称 `e24e3c0`）
- **合并与差异边界**：`19e7536` 为 `2e8bed9` 合并 `origin/main` @ `00cb3c4` 的纯无冲突合并提交（`git diff --name-status 00cb3c4..19e7536` 仅含 `E1` 一轮 33 个文件）；本轮纯修复差异为 `git diff 19e7536..e24e3c0`（15 个文件：生产 7 个 `+143 / -277`、测试 4 个 `+458 / -120`、快照 4 张）。
- **GitHub Actions CI 核实**：[Android CI #45699211239](https://github.com/mailwmj/hipda-next/actions/runs/45699211239)
  - `headSha`：`e24e3c0107dff2721e22b811d182d88480bf3a27`（100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139504557504`）：**`completed / success`**（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 APK 产物校验全绿）。

---

## 一、阻断项（Blocking Issues）

**无。**

---

## 二、负责人点名 3 项重点核查

### 1. 重点 1：`HiSkeleton.kt`（`V-E1 F1`）改动范围、三主题可见度与 `state_skeleton_*` / `thread_detail_*skeleton*` 零漂移原因

1. **确认仅改取色**：
   - 核查 `git diff 19e7536..e24e3c0 -- hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt`：仅将 `:47-48` 的 `val contentColor = LocalContentColor.current; val baseColor = if (contentColor.isSpecified) contentColor else Color.Gray` 替换为 `val baseColor = MaterialTheme.colorScheme.onSurface`（同步清理对应 import，共 `+2 / -5` 行）。`HiSkeletonTokens`（`0.07f` / `0.17f` / `0.12f`）、呼吸动画曲线、`drawWithCache` 与 `HiSkeletonBlock` 签名和布局 **0 改动**。
2. **浅色 / 深色 / OLED 纯黑三主题可见且不突兀（色值演算 + 像素单测 + 截图目视）**：
   - 对照 `HiColors.kt:147-229`，`HiSkeletonTokens` 呼吸区间 `7%–17%`（静止 `12%`）在三种主题下与背景合成结果：
     - **浅色**：`onSurface = n(10)`（`~#171C1F`）叠在 `surface = n(98)`（`~#F7FAFC`）上，RGB 下沉 `16–38` 阶（静止约 `27` 阶，`~#DDE1E4`），目视 `forum_w411_h891_skeleton_light.png` 呈柔和浅灰占位条，无生硬黑块；
     - **深色**：`onSurface = n(90)`（`~#DEE3E6`）叠在 `surface = n(6)`（`~#0E1417`）上，RGB 抬升 `15–35` 阶（静止约 `25` 阶，`~#282E31`），目视重录后的 `forum_w933_h696_skeleton_dark.png` 6 组中栏紧凑骨架清晰可辨且低调护眼；
     - **OLED 纯黑**：`toPureBlack()`（`HiColors.kt:216-229`）将 `surface` 置 `#000000` 并保留 `onSurface = n(90)`，7%–17% alpha 在 `#000000` 上合成出 `#101010`–`#262728`（静止 `#1B1B1C`，对应 M3 深色 `surfaceContainerLow`~`Highest` 明度带），暗光环境不刺眼且轮廓清楚；
   - `ForumListBehaviorTest.kt:390-453` 在刻意不包 `Surface`（仅 `Box.background`）下用 `@GraphicsMode(NATIVE)` 实测浅/深/纯黑三态位图均满足 `maxDelta >= 12 && visiblePixels > 1_000`（回退修复则深色 `maxDelta=1`、纯黑 `maxDelta=0` 立即变红）。
3. **为什么 `state_skeleton_*`（4 张）与 `thread_detail_*skeleton*`（2 张）0 像素漂移（确认系 `Surface` 提供等价色，非测试盲区）**：
   - **`state_skeleton_*`**：实查 `HiStateScreenshotTest.kt:247-260`，`StateScreenshotSurface` 使用了 `Surface(color = MaterialTheme.colorScheme.background)`；M3 `Surface` 默认传入 `contentColor = contentColorFor(background) = colorScheme.onBackground` 并注入 `LocalContentColor`。
   - **`thread_detail_*skeleton*`**：实查生产代码 `ThreadDetailPane.kt:78, 93`，详情面板根部本身就是 `Surface(color = cs.background, contentColor = cs.onBackground)`，其内部 `ThreadDetailSkeleton` 同样获得了 `LocalContentColor = cs.onBackground`。
   - **关键等式**：实查 `HiColors.kt:148, 150`（浅色 `onBackground = n(10f)`, `onSurface = n(10f)`）与 `HiColors.kt:187, 189`（深色 `onBackground = n(90f)`, `onSurface = n(90f)`），本设计系统中 **`colorScheme.onBackground == colorScheme.onSurface` 逐比特完全相等**。因此这两处原本拿到的 `LocalContentColor.current` 就是 `onSurface`，改后颜色 0 变化，且 CI `Roborazzi verify` 实际逐像素跑过了这 6 张图，不存在测试盲区；唯独 `HiAdaptiveShell` + `ForumPane` 祖先链是 `Box.background` 无 `Surface`（旧实现回退成 `Color.Black`），所以只有 `forum_w411_h891_skeleton_light.png` 与 `forum_w933_h696_skeleton_dark.png` 发生预期更新。

---

### 2. 重点 2：`S3` 的 `uidGeneration` 屏蔽是否在所有路径上均生效

逐行审计 `ForumViewModel.kt:59-60, 102-106, 142-151, 190, 206-213, 247-258`，确认**不仅对 `SiteError` 生效，在跨账号切换或退登（`uidChanged == true`）后的全部 7 条分支路径上均严密生效**：

1. **切号/退登在途加载期（`load.isLoading == true, err == null`）**：`resetForAuthChange(uidChanged = true)`（`:247-258`）立即递增 `uidGeneration` 并清空 `queryUidGenerations = emptyMap()`；`staleUidCache`（`:102`）立即为 `true`，`visibleItems = emptyList()`，同时 `:104` `unsettled = true` 使界面直接切入骨架屏（消除了旧版在途请求期间短暂露出上一账号列表的现象）。
2. **`SiteError`（新账号无版块权限）**：`visibleItems = emptyList()`，展示 `当前账号无权查看这个版块`（`ForumViewModelTest.kt:253-302` 覆盖当前版块 2 与切去历史缓存版块 6 两条子路径）。
3. **`AuthRequired`（退登或新会话失效）**：`visibleItems = emptyList()`，展示登录引导错误态。
4. **`NetworkError` / `InvalidPage`**：`visibleItems = emptyList()`，展示网络/页面错误态；且若此时触发 `refresh()`，`:190` 从 `uiState.value.items` 取 `previous` 亦为 `emptyList()`，不会把上一账号残留帖子混入 `computeTopNewThreadsCount` 或 `_refreshErrors`。
5. **`ChallengeRequired` / 活跃验证态（`ChallengeState != Idle`）**：`visibleItems = emptyList()`，确保 `ForumPane.kt:100` `showChallenge`（要求 `uiState.items.isEmpty()`）正常激活 `ChallengeStatusView`，不被旧账号缓存挡住验证面板。
6. **后续切换到上一账号曾浏览过的其他版块或切换排序**：因 `queryUidGenerations` 已整体清空，任何尚未以新 `uid` 成功返回 `ThreadListBatchResult` 的 `ThreadListQuery` 均满足 `queryUidGenerations[q] != uidGeneration`，一律屏蔽旧缓存直至 `:212` 写入 `(query to snap.uidGeneration)`。
7. **上一账号在途慢请求滞后返回**：被 `:208` `if (auxState.value.authGeneration != snap.authGeneration) return` 直接丢弃，绝不写入新世代 `queryUidGenerations`。

---

### 3. 重点 3：精简掉的 `131` 行生产代码（`1,207 -> 1,076` 行）行为前后等价核验

逐文件逐块比对 `19e7536..e24e3c0` 的 6 个论坛生产文件：

1. **`ForumTopBar.kt`（`255 -> 210`，`-45` 行）**：`ForumTopBarScrollState` 用标准 `Animatable(0f)` + `updateBounds(0f, clamped)` 替代手写 `settleJob`/`currentVelocity`/`animate`；跟手滚动走 `UNDISPATCHED` `snapTo` 同步更新帧内位移并借 `MutatorMutex` 自动打断在途回弹，松手/展开走 `animateTo(..., HiMotion.spatialDefault(...))`，减动效与 `< 0.5px` 阈值仍走 1 帧 `snapTo`；`ForumTopBarBackground` 外提公共 `graphicsLayer { translationY }` 后渲染树与图层变换完全等价（`ForumTopBarScrollTest` 4/4 通过）。
2. **`ForumPane.kt`（`254 -> 216`，`-38` 行）**：`topBarGlassState` 与 `glassSourceModifier` 条件短路优先级完全一致；`isListScrolled`（`Snapshot.withoutReadObservation` 首帧同步初始化 + `snapshotFlow.distinctUntilChanged()`）与自动续载 `LaunchedEffect`（`rememberUpdatedState(uiState)` + `snapshotFlow.distinctUntilChanged()`）保持首帧无闪烁、距底 4 项预取、失败停拉语义完全一致，同时消除了 Compose 1.13 `derivedStateOf` 在滚动中引发的 16 次内层作用域失效。
3. **`BalancedForumTabRow.kt`（`196 -> 170`，`-26` 行）**：双分支 `Row` 合并为单 `Row`，`fitBalanced == true` 时挂 `Modifier.weight(1f)` 且跳过自动滚中、`false` 时挂 `horizontalScroll + onPlaced` 自动居中选中项，布局与动画完全等价且跨阈值不再销毁子项状态。
4. **`NewThreadsPill.kt`（`57 -> 47`，`-10` 行）**：外层 `Box(minHeight = 48.dp, clickable)` + 内层 `Surface` 合并为 M3 `Surface(onClick = ...)`，M3 `Surface` 内置 `minimumInteractiveComponentSize()`（保持 `48dp` 触控盒居中包裹 `36dp` 视觉胶囊，故 `forum_w411_h891_scrolled_collapsed_light.png` 静态像素 **0 漂移**）并补齐了按钮按压状态层。
5. **`ThreadListRow.kt`（`142 -> 135`，`-7` 行）**：`formatReplyViewText` 改用 `listOf(reply.trim(), view.trim()).filter(String::isNotEmpty).joinToString("/")`，四象限真值表（双非空、单非空×2、双空）输出 100% 一致。
6. **`ForumViewModel.kt`（`286 -> 281`，`-5` 行，含新增 `S3` `uidGeneration`）**：移除恒等价冗余的 `settledQueries`、移除 `lastItemsBySource` 并在 `TodayNew` 刷新需算 diff 时直接读取 `todayNewItems.first()`（初载 `diffPrevious.isEmpty()` 直接短路返回 `0`），合并 `activeRefreshJob`，行为完全等价且少一次重复流查询。

---

## 三、非阻断细微备注（0 必修项）

- **备注 1**：`HiSkeleton.kt:24` 顶部 KDoc 注释仍写“基于当前 `LocalContentColor` 叠色”，下次触碰该文件时可顺手改为 `colorScheme.onSurface`。
- **备注 2**：`ForumViewModel.kt:106` 的 `hasMore` 可在后续触碰时顺手写为 `!staleUidCache && load.hasMore`（当前 `visibleItems.isEmpty()` 时 `ForumPane` 走骨架/错误分支不挂载 `LazyColumn`，无界面影响）。
