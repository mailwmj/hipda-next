# 任务卡 M6-polish 交付报告

- **任务卡**：`M6-polish`（M6 跨卡与各页 6 项小修收口）
- **分支**：`card/M6-polish`（基于 `origin/main` @ `3617fad`）
- **最终 Commit**：`0e186fbf7371034e1847f9c71e8ef61691646911`（`0e186fb`）
- **GitHub Actions CI**：[Android CI #45752239193](https://github.com/mailwmj/hipda-next/actions/runs/45752239193)（`success`，15m35s；Build & Lint、Room Schema 一致性、全量单元测试与 Roborazzi 截图验证全绿，0 漂移）
- **生产代码变更规模**：8 个生产文件 `+66 / -12`，净增 **+54 行**（预算 60–110 行，1.5× 警戒 165 行；零测试专用钩子/语义键；零手写 `Layout`/`Modifier.Node`/命令队列）

---

## 一、6 项小修落地与回归测试对照

| 编号 | 审查项 | 生产改动与核心行为 | 回归测试（撤回即变红） |
|---|---|---|---|
| ① | `R-E8 R2-S1` | `SmsConversationScreen.kt` L69–80：`viewModel.events.collectLatest` 在调用 `snackbarController.show` / `showMessageUiError` 前先 `undoSnackbarJob?.cancel(); undoSnackbarJob = null`，让 `CooldownBlocked` / `ShowError` 立即顶替 10s 旧撤销 Snackbar，且被取消旧条不触发恢复回调 | `MessagesScreenBehaviorTest.r2s1_smsConversation_cooldownAndErrorEventsImmediatelyReplaceActiveUndoSnackbar`（覆盖 `CooldownBlocked` 与 `ShowError` 双分支；撤回 cancel 后新提示排队阻塞、测试立红） |
| ② | `R-E8` 跨卡 | `NextAppState.kt` L90–140：`NextAppStateSaver.save` 对 `selectedSmsConversation` 写 `null`（索引 17）、对下钻 `DetailDrilldownEntry.SmsConversation` 写 `""`（三元组第 3 元），保持 20 元列表槽位兼容；`restore` 一律还原为 `SmsConversationArgs(uid, "")`；`UserProfile` 与旧 16 元列表恢复 100% 兼容 | `FeaturePaneSlotsTest.rE8_nextAppStateSaver_omitsSmsPeerUsernameFromSavedPayloadAndRestoresEmptyName`（递归全量扫描保存列表不含根/下钻哨兵用户名，校验 20 元槽位、空用户名恢复及旧 20 元带名 Bundle 脱敏恢复） |
| ③ | `R-E3 R2-S1` | `MultiPaneReadingContainer.kt` L108–171：在 `MultiPaneSlotHolder` 新增 `MAX_SAVED_DETAIL_STATES = 20` 与 `maxSavedDetailStates` 默认参数，使用 `LinkedHashMap(..., accessOrder = true)` + `mountedDetailCounts` 维护 `detail:$tid` 的 LRU 访问序；挂载刷新 MRU 并在超限时按最久未访问顺序淘汰非活跃且未挂载 `tid`（绝不淘汰 `activeThreadTids`），调用 `saveableStateHolder.removeState(detailSaveableKey(candidate))`；**未改动 `releaseThreadStore`、`NextAppHostViewModel` 与 `feature/threaddetail/*`** | `MultiPaneStateMigrationTest` 新增 3 项：<br>• `rE3R2S1_savedDetailStates_exceeding20EvictsOldestInactiveAndRetainsRecent20`<br>• `rE3R2S1_savedDetailStates_revisitingOldestRefreshesMruSoSecondOldestIsEvicted`<br>• `rE3R2S1_savedDetailStates_singlePaneEvictsOldestImmediatelyAt21stThread` |
| ④ | `R-E12 R2-S1` | `BlacklistScreen.kt` L109：`BlacklistViewModel.removeUser` 计算可见列表时排除在途移除项 `state.orderedUsernames.filter { it in state.activeBlockedSet && it !in state.removingUsernames }`，使并发移出相邻 `C`、`D` 时 `D` 锚定 `B` 而非即将消失的 `C` | `BlacklistScreenAndMockWebServerTest.rE12R2S1_removeAdjacentWhilePreviousInFlight_skipsRemovingNeighborAsAnchorAndRestoresOrder`（同时覆盖 ViewModel 令牌 `previousNeighbor == "B"` 与 UI 撤销后 `[A, B, D]` 视觉顺序） |
| ⑤ | `R-E10 R2-S1` | `MineCommon.kt` L60：为 `UndoTokenManager` 增加只读 `val hasActiveToken: Boolean get() = activePayload != null`。<br>`SearchViewModel.kt` L113–135：`syncHistoryRanks` 在 `!undoManager.hasActiveToken` 时清空并按最新历史重建秩表（活跃令牌时仅增量补充）；`undoDeleteHistory` 在 `consume` 后先拷贝 `HashMap(historyRanks)` 快照防挂起窗口被并发 `combine` 清空。<br>`FavoritesViewModel.kt` L45、L156–161：首页（`!append`）且 `!undoManager.hasActiveToken && tr.pendingRemovals.isEmpty() && tr.pendingRestores.isEmpty()` 时调用 `tr.rebuildRanks(merged)` 重建秩表，其余情况维持 `tr.ensureRanks(merged)` | • `SearchPaneTest.rE10R2S1_recentHistory_researchPromotesOldQuery_deleteAndUndoRestoresAtFirstPosition`<br>• `MinePagesBehaviorTest.rE10R2S1_favorites_refreshReordersFirstPage_cancelTopAndUndoRestoresAtFirstPosition` |
| ⑥ | `R-E14 R2-S1` | `ImageViewerOverlay.kt` L365–377：`onOriginalDecodeResult` 记录 `val wasAlreadyCompleted = current.downloadState is OriginalDownloadState.Completed`，仅在 `!wasAlreadyCompleted` 时弹出 `已加载原图` Snackbar；远滑回收后重返已加载原图页仍正常重解展示原图与保持底栏完成态，不再重复弹提示 | `ImageViewerHardeningRegressionTest.rE14R2S1_multiPageReturnToCompletedOriginal_doesNotReshowLoadedSnackbar`（5 图翻至第 4 页再返第 0 页，确认 `origFile` 解码请求数 `>= 2` 且 `已加载原图` 弹出次数严格 `== 1`） |

---

## 二、授权更新既有测试清单

仅 1 处（严格对应任务卡第 ② 项授权，不放宽任何其他断言）：
- `hipda/src/test/java/net/jejer/hipda/feature/app/FeaturePaneSlotsTest.kt` L586–610（`nextAppStateSaver_roundTripsSmsConversationSettingsGroupAndDrilldownStack`）：
  - 原断言：`assertEquals(original, restored)`
  - 新断言：将预期对象调整为 `original.copy(selectedSmsConversation = SmsConversationArgs("42", ""), detailDrilldownStack = listOf(DetailDrilldownEntry.UserProfile("42", "alice"), DetailDrilldownEntry.SmsConversation(SmsConversationArgs("77", ""))))`，仅对短消息对端 `username` 预期恢复为 `""`，其余 18 个字段及 `UserProfile` 用户名保持严格全等校验。

---

## 三、文件边界与改动统计

- **硬红线核查**：
  - `feature/threaddetail/*`：0 修改
  - `NextAppHostViewModel.kt` / `releaseThreadStore`：0 修改（满足负责人补充要求，减小与在途 `E4` 合并冲突）
  - 共享构建/清单/配置（`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`AndroidManifest.xml`、`.github/`）：0 修改
  - `MotionTokenScanTest` 白名单：0 修改
  - Roborazzi 截图基线：0 修改、CI 校验 0 漂移

- **修改文件列表（15 个，与卡片清单完全一致）**：
  - 生产（8）：
    - `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt`（+34 / -1）
    - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt`（+4 / -4）
    - `hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt`（+6 / -2）
    - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt`（+4 / -1）
    - `hipda/src/main/java/net/jejer/hipda/feature/mine/FavoritesViewModel.kt`（+7 / -1）
    - `hipda/src/main/java/net/jejer/hipda/feature/mine/MineCommon.kt`（+1 / -0）
    - `hipda/src/main/java/net/jejer/hipda/feature/search/SearchViewModel.kt`（+9 / -2）
    - `hipda/src/main/java/net/jejer/hipda/feature/settings/BlacklistScreen.kt`（+1 / -1）
  - 测试（7）：
    - `hipda/src/test/java/net/jejer/hipda/feature/app/FeaturePaneSlotsTest.kt`（+76 / -1）
    - `hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneStateMigrationTest.kt`（+163 / -0）
    - `hipda/src/test/java/net/jejer/hipda/feature/imageviewer/ImageViewerHardeningRegressionTest.kt`（+81 / -0）
    - `hipda/src/test/java/net/jejer/hipda/feature/messages/MessagesScreenBehaviorTest.kt`（+91 / -0）
    - `hipda/src/test/java/net/jejer/hipda/feature/mine/MinePagesBehaviorTest.kt`（+63 / -0）
    - `hipda/src/test/java/net/jejer/hipda/feature/search/SearchPaneTest.kt`（+36 / -0）
    - `hipda/src/test/java/net/jejer/hipda/feature/settings/BlacklistScreenAndMockWebServerTest.kt`（+90 / -0）

---

## 四、对外 / 内部 API 清单（签名 + 文件:行号）

- `internal const val MAX_SAVED_DETAIL_STATES: Int = 20` — `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt:108`
- `class MultiPaneSlotHolder internal constructor(internal val hostViewModel: NextAppHostViewModel, internal val saveableStateHolder: SaveableStateHolder, private val maxSavedDetailStates: Int = MAX_SAVED_DETAIL_STATES)` — `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt:116-120`
- `val NextAppStateSaver: Saver<NextAppState, Any>`（行为更新：短消息对端 `username` 不落盘、恢复为空串）— `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt:75`
- `internal class UndoTokenManager<T> { val hasActiveToken: Boolean }` — `hipda/src/main/java/net/jejer/hipda/feature/mine/MineCommon.kt:60`
- `fun SmsConversationScreen(...)`（行为更新：`CooldownBlocked`/`ShowError` 先取消 `undoSnackbarJob`）— `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt:51`
- `fun BlacklistViewModel.removeUser(username: String, onRemoved: (BlacklistUndoToken) -> Unit, onFailed: (UiError) -> Unit)`（行为更新：计算前邻居排除 `removingUsernames`）— `hipda/src/main/java/net/jejer/hipda/feature/settings/BlacklistScreen.kt:105`
- `fun SearchViewModel.undoDeleteHistory(tokenId: Long)` / `private fun syncHistoryRanks(history: List<String>)` — `hipda/src/main/java/net/jejer/hipda/feature/search/SearchViewModel.kt:113,127`
- `FavoritesViewModel.KindTracker.rebuildRanks(items: List<FavoriteThreadItem>)` — `hipda/src/main/java/net/jejer/hipda/feature/mine/FavoritesViewModel.kt:45`
- `fun ImageViewerOverlay(...)`（行为更新：原图解码重返不重复弹 `已加载原图`）— `hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt:81`

---

## 五、本地验证与 CI 证据

1. **本地验证（遵守 `DISPATCH.md` §2：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew ... -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`）**：
   - 首轮运行 6 个受影响包 + `CopyTerminologyScanTest` + `MotionTokenScanTest`：229/230 通过（仅 `BlacklistScreenAndMockWebServerTest` 新增测试因单字母用户名 `"A"` 同时命中圆形头像首字母与用户名两个语义节点失败，修正为 `.onLast()` 定位用户名节点）。
   - 复跑 `BlacklistScreenAndMockWebServerTest`（5/5 PASS）+ `:hipda:assembleDebug`（`BUILD SUCCESSFUL in 24s`）。
   - `CopyTerminologyScanTest`：3/3 PASS；`MotionTokenScanTest`：7/7 PASS。
2. **GitHub Actions CI**：
   - Run：`https://github.com/mailwmj/hipda-next/actions/runs/45752239193`（`gh run watch 45752239193 --interval 60`）
   - Commit：`0e186fbf7371034e1847f9c71e8ef61691646911`
   - 结论：`Build, Lint & Unit Tests` 全绿（15m35s），全量单测与 Roborazzi 截图校验全部通过，APK 产物校验通过。
