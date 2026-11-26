# 独立代码审查报告：R-M6-polish

- **审查对象**：`origin/card/M6-polish` @ `0e186fbf7371034e1847f9c71e8ef61691646911`（基线 `3617fadcdab928bdd979ed41ae416c7d5e0a06fc`）
- **最新 `origin/main` 合并预检**：`git merge-tree --write-tree --messages origin/main 0e186fbf7371034e1847f9c71e8ef61691646911`（`origin/main` = `3617fadcdab928bdd979ed41ae416c7d5e0a06fc`）输出树 `c068882ee55acc948af554ed3942a9caac00c618`，退出码 `0`（**零冲突**）
- **CI 核验**：[Android CI #45752239193](https://github.com/mailwmj/hipda-next/actions/runs/45752239193)
  - `headSha`：`0e186fbf7371034e1847f9c71e8ef61691646911`（与送审 commit 严格一致）
  - 状态与结论：`completed` / `success`
  - 通过任务：`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 一致性校验、`testDebugUnitTest`、`:core-parser:test`、`-Proborazzi.test.verify=true`、APK 产物签名与包名校验
- **审查结论**：**APPROVE**（0 阻断项，1 条非阻断建议）

---

## 一、硬边界、规模与通用阻断核查

| 检查维度 | 核查结果 | 证据说明 |
|---|---|---|
| `feature/threaddetail/*` 硬红线 | **通过（0 修改）** | `git diff --name-status 3617fad...0e186fb` 仅修改卡片指定的 15 个文件（8 生产 + 7 测试），`feature/threaddetail/*` 与 `NextAppHostViewModel.kt` 均 0 改动，完全避开在途 `E4` |
| 项 ③ 实现位置边界 | **通过** | `MAX_SAVED_DETAIL_STATES = 20` 与 LRU 淘汰仅在 `feature/app/MultiPaneReadingContainer.kt` 的 `MultiPaneSlotHolder` 内实现 |
| 共享构建/清单/白名单 | **通过（0 修改）** | `gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`AndroidManifest.xml`、`.github/`、`MotionTokenScanTest` 白名单均 0 修改 |
| 生产代码规模与标准组件 | **通过** | 8 个生产文件 `+66 / -12`，净增 **+54 行**（卡片预算 60–110 行，1.5× 警戒线 165 行）；零手写 `Layout`/`Modifier.Node`/命令队列 |
| 生产测试专用钩子 | **通过** | 无测试专用语义键、钩子或 `CompositionLocal`；`MultiPaneSlotHolder` 的 `private val maxSavedDetailStates: Int = MAX_SAVED_DETAIL_STATES` 为卡片显式授权默认参数，且测试直接走真实 `20` 上限 |
| 既有测试修改与快照零漂移 | **通过** | 既有测试仅调整 `FeaturePaneSlotsTest.kt:601-610` 短消息对端 `username` 恢复为 `""`（卡片第 ② 项显式授权，其余 18 个字段及 `UserProfile` 全等断言未放宽）；`src/test/snapshots/` 0 文件变更，CI Roborazzi `verify` 0 漂移 |
| 安全白名单与离线测试纪律 | **通过** | 未新增外部/WebView/图片 URL 打开通道；新增测试全部使用内存替身或本地 `MockWebServer`（测试域名 `img.example.invalid`），零真实站点请求 |

---

## 二、6 项小修逐条闭合与“撤回必红”核验

### ① `R-E8 R2-S1`：短消息对话冷却/错误提示顶替旧撤销 Snackbar
- **生产代码**（`hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt:69-80`）：
  - `viewModel.events.collectLatest` 在调用 `snackbarController.show(...)` / `showMessageUiError(...)` 之前先执行 `undoSnackbarJob?.cancel(); undoSnackbarJob = null`。
  - 取消 `undoSnackbarJob` 会中断正在挂起的 `SnackbarHostState.showSnackbar`（释放 `SnackbarHostState` 内部互斥锁并清空当前视觉项，不会返回 `SnackbarResult.ActionPerformed`，因此不误触发 `restoreDeletedMessage`），使 `CooldownBlocked` / `ShowError` 立即上屏。改为 `collectLatest` 同时保证连续冷却/错误提示也不会互相排队积压。
- **回归测试与撤回必红**（`MessagesScreenBehaviorTest.kt:599-687`）：
  - 删除失败气泡使 10 秒 `已删除 | 撤销` 在途（不推进时钟）→ 立即点击重发另一失败气泡触发 `CooldownBlocked(15)` → 断言 `发送太快，请等 15 秒后再试` 立即显示、`已删除`/`撤销` 立即消失且被删气泡未复活；随后再次删除第二条失败气泡并触发刷新 `ShowError`，同样在不推进 10 秒超时的前提下断言错误条立即顶替旧撤销条。
  - **撤回必红验证**：若撤回 `undoSnackbarJob?.cancel()`，`SnackbarHostState` 互斥锁仍被 10 秒 `Long` 撤销条占住，`waitForIdle()` 不会跨过 `delay(10_000L)`，L659 `onNodeWithText("发送太快，请等 15 秒后再试").assertIsDisplayed()` 与 L660/L680 `assertDoesNotExist()` 必立即失败。

### ② `R-E8` 跨卡：`NextAppStateSaver` 只存短消息对端 `uid`、不存用户名
- **生产代码**（`hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt:90-140`）：
  - `save`：索引 15 下钻栈中 `DetailDrilldownEntry.SmsConversation` 写 `listOf("s", e.args.uid, "")`（保持三元组对齐），`UserProfile` 保持 `listOf("u", e.uid, e.username)`；索引 16 存 `sms?.uid`，索引 17 固定写 `null`，严格维持 20 元列表槽位兼容。
  - `restore`：索引 16 恢复为 `SmsConversationArgs(it, "")`，下钻 `"s"` 恢复为 `DetailDrilldownEntry.SmsConversation(SmsConversationArgs(uid, ""))`（即使旧 20 元 Bundle 残留了名字也统一脱敏为空串）；`r.size < 20` 的旧 16 元 `UserProfile` 下钻恢复分支完全保留。恢复后进入 `SmsConversationScreen` 由 `SmsConversationViewModel.loadConversation`（`SmsConversationViewModel.kt:272-276`）从会话消息回填 `peerUsername`。
- **回归测试与撤回必红**（`FeaturePaneSlotsTest.kt:614-677`）：
  - 构造含根哨兵 `"PEER_NAME_SENTINEL_ROOT"` 与下钻哨兵 `"PEER_NAME_SENTINEL_DRILL"` 的状态，调用 `save` 后递归提取全部字符串元素断言不含任一短消息用户名哨兵且保留 `UserProfile` 用户名，同时验证 `restore` 与旧 20 元残留名恢复均得到空用户名，旧 16 元测试（L681）继续通过。
  - **撤回必红验证**：撤回任一处脱敏，L650/L651 哨兵扫描断言或 L655/L669 空串恢复断言立即失败。

### ③ `R-E3 R2-S1`：`detail:$tid` 保存态 20 帖 LRU 上限（仅改 `feature/app/`）
- **生产代码**（`hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt:108-171`）：
  - `MultiPaneSlotHolder` 使用 `LinkedHashMap<String, Unit>(savedDetailLimit + 1, 0.75f, true)`（`accessOrder = true`）记录访问序，配合 `mountedDetailCounts` 跟踪挂载引用计数。
  - 挂载时 `onDetailStateMounted` 更新计数、`savedDetailLru[tid] = Unit` 刷新 MRU 并执行 `evictExcessSavedDetailStates()`；离树时 `onDetailStateDisposed` 递减计数，若非活跃（`args.tid !in activeThreadTids`）再次检查淘汰。
  - `evictExcessSavedDetailStates()` 从最久未访问端遍历，仅在 `candidate !in activeThreadTids && (mountedDetailCounts[candidate] ?: 0) == 0` 时移除并调用 `saveableStateHolder.removeState(detailSaveableKey(candidate))`；因使用 `iterator.hasNext()` 为循环上界，即使极端情况下超 20 个槽位同时处于动画挂载期也不会死循环，待退场动画结束离树时自动收敛回 20 个以内。
- **回归测试与撤回必红**（`MultiPaneStateMigrationTest.kt:443-601`）：
  - 覆盖双栏连续浏览 `1..21` 淘汰 `tid=1` 保留 `2..20`、浏览 `1..20` 后重访 `tid=1` 刷新 MRU 再开 `21` 精准淘汰 `tid=2` 保留 `tid=1`、以及窄屏单栏无过渡动画先离树后挂载场景下第 21 帖立即淘汰 `tid=1` 共 3 条测试。
  - **撤回必红验证**：撤回淘汰逻辑或将 `accessOrder` 改为 `false`（退化为 FIFO），`save=1:0` 或 `save=1:1` / `save=2:0` 断言立即失败。

### ④ `R-E12 R2-S1`：黑名单取 `prevNeighbor` 排除 `removingUsernames` 在途项
- **生产代码**（`hipda/src/main/java/net/jejer/hipda/feature/settings/BlacklistScreen.kt:109`）：
  - `val visible = state.orderedUsernames.filter { it in state.activeBlockedSet && it !in state.removingUsernames }`，当 `C` 正在网络移出途中紧接着移出 `D` 时，`D` 尚未加入 `removingUsernames` 而 `C` 已在其中，故 `visible` 为 `[A, B, D]`，`D` 的 `prevNeighbor` 精准跨过 `C` 锁定为 `"B"`；无论后续 `C` 成功移出还是失败留存，`insertAtAnchor` 均能将 `D` 插回 `B` 之后，不会因找不到已消失的 `C` 而错插列表首位。
- **回归测试与撤回必红**（`BlacklistScreenAndMockWebServerTest.kt:200-286`）：
  - 双层验证：ViewModel 层挂起 `C` 同时移出 `D`，放行后直接断言 `tokenD.previousNeighbor == "B"` 且恢复顺序为 `[A, B, D]`；Compose UI 端到端层点击移出 `C`、`D` 后点 Snackbar「撤销」，同时断言状态列表与语义节点纵向坐标 `topA < topB < topD`。
  - **撤回必红验证**：撤回 `&& it !in state.removingUsernames` 后 `tokenD.previousNeighbor` 变为 `"C"`，L232 断言及后续 `[D, A, B]` 错序断言立即失败。

### ⑤ `R-E10 R2-S1`：`SearchViewModel` / `FavoritesViewModel` 无未决令牌时刷新秩表
- **生产代码**：
  - `MineCommon.kt:60`：`UndoTokenManager` 新增只读 `val hasActiveToken: Boolean get() = activePayload != null`。
  - `SearchViewModel.kt:113-135`：`syncHistoryRanks` 在 `!undoManager.hasActiveToken` 时清空并按当前 `history` 重建 `historyRanks`（有活跃令牌时按 `max + 1` 增量补新词）；并在 `undoDeleteHistory` 消费令牌后立即同步快照 `val ranksSnapshot = HashMap(historyRanks)`，防止 `historyMutex` 等待期间 `combine` 因 `hasActiveToken` 变为 `false` 提前冲掉被删词的秩。
  - `FavoritesViewModel.kt:45, 156-160`：`loadPage` 首页（`!append`）成功且 `!undoManager.hasActiveToken && tr.pendingRemovals.isEmpty() && tr.pendingRestores.isEmpty()` 时调用 `tr.rebuildRanks(merged)` 重建秩表，其余情况维持 `tr.ensureRanks(merged)`。
- **回归测试与撤回必红**：
  - `SearchPaneTest.kt:146-179`：初始 `["苹果", "香蕉", "咖啡"]` 先删再撤建立旧秩且清空令牌 → 点击 `"咖啡"` 重搜升至首位 → 删除首位 `"咖啡"` 再点「撤销」→ 断言数据流与界面坐标严格恢复为 `["咖啡", "苹果", "香蕉"]`。
  - `MinePagesBehaviorTest.kt:414-474`：首页 `[7001, 7002, 7003]` 建秩 → 刷新返回 `[7003, 7001, 7002]` → 取消首位 `7003` 再点「撤销」→ 断言数据流与界面坐标严格恢复为 `[7003, 7001, 7002]`。
  - **撤回必红验证**：撤回秩表重建后，`insertByAnchor` 因 `anchor == null` 且新表头旧秩（`0`）小于被删项旧秩（`2`）走 `byRank()` 将被删项插到末尾，两条测试均立即失败。

### ⑥ `R-E14 R2-S1`：多图远滑回收后重返已加载原图页不重复弹提示
- **生产代码**（`hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt:365-377`）：
  - `onOriginalDecodeResult` 先读取 `val wasAlreadyCompleted = current.downloadState is OriginalDownloadState.Completed`，更新 `downloadState = completed` 后仅在 `!wasAlreadyCompleted` 时弹出 `已加载原图` Snackbar；页面离屏回收再滑回时，`ImageViewerPage` 仍根据 `pendingOriginal` 重新限尺寸解码原图并展示、底栏维持 `已加载原图`，但不再重复弹提示。
- **回归测试与撤回必红**（`ImageViewerHardeningRegressionTest.kt:509-586`）：
  - 5 张图第 0 页加载原图完成（确认解码请求数 `== 1`、提示次数 `== 1`）→ 横滑至第 4 页使第 0 页离屏回收 → 横滑回第 0 页并等待 `loader.requests.count { it.data == origFile } >= 2`（证明真实触发了二次解码回调）→ 断言底栏仍显示 `已加载原图` 且 `snackbars.messages.count { it == "已加载原图" } == 1`。
  - **撤回必红验证**：撤回 `!wasAlreadyCompleted` 守卫后二次解码回调使计数变为 `2`，L581 `assertEquals(..., 1, ...)` 立即失败。

---

## 三、阻断项

无。

---

## 四、非阻断建议

- **S1（`SearchPane.kt:48-51` / `FavoritesPane.kt:35-39` / `SearchViewModel.kt:155` / `FavoritesViewModel.kt:167`）**：当前 `SearchPane` 与 `FavoritesPane` 在 `collectLatest` 中顺序执行 `snackbar.show(...)` 后再调 `viewModel.expireHistoryUndo(ev.token.id)` / `viewModel.expireUndo(ev.token.id)`（未使用 `try ... finally`），且下拉刷新报错分支发 `RefreshError` / `ActionError` 时调用的是 `undoManager.nextId()` 而非 `invalidate()`。若用户在 10 秒撤销条尚未超时期间切走页面（触发 `LaunchedEffect` 取消）或恰遇后台刷新报错顶替撤销条，`expire*` 不会被执行，`undoManager.hasActiveToken` 会保持 `true` 直至下一次删除操作或切号重置，期间暂退回增量 `ensureRanks`。不影响数据与崩溃安全（且 `SearchPane.kt`/`FavoritesPane.kt` 不在本卡授权文件清单内），后续如有顺手维护可在 `snackbar.show` 外包一层 `try ... finally { viewModel.expire*(...) }`。
