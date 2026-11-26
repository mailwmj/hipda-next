【任务卡 M6-polish：M6 跨卡与各页 6 项小修收口】
分支：`card/M6-polish`（基于 `origin/main` @ `3617fad`）
风险分级：普通档（仅走 `R`，不派 `V`）
预计生产代码净增：约 60–110 行（1.5× 警戒 165 行）

【目标】
集中闭合 M5/M6 第二轮审查留下的 6 项跨卡与各页边角体验/隐私小修（`R-E8 R2-S1`、`R-E8` 跨卡、`R-E3 R2-S1`、`R-E12 R2-S1`、`R-E10 R2-S1`、`R-E14 R2-S1`），每项均补齐撤回修复即变红的自动化回归测试，并严格避开正在修复中的 `E4` 文件边界。

【文件边界（硬红线，避开在途 E4）】
- **严禁修改**：`feature/threaddetail/chrome/*`、`feature/threaddetail/ThreadDetailViewModel.kt`、`feature/threaddetail/ThreadDetailPane.kt`（以及 `feature/threaddetail/*` 全部文件，避免与在途 `E4` 冲突）。
- 项 ③ 的 `detail:$tid` 保存态由 `feature/app/MultiPaneReadingContainer.kt` 中的 `MultiPaneSlotHolder` 托管，**只在 `feature/app/` 侧实现**。
- 全部代码签名以 `3617fad` 源码为准。

【范围（6 项逐条规范）】

### ① `R-E8 R2-S1`：短消息对话冷却/错误提示顶替旧撤销 Snackbar
- **文件**：`hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt`（L68–77）
- **触发路径**：删除一条发送失败气泡弹出 10 秒 `已删除 | 撤销` Snackbar（`undoSnackbarJob` 在途）期间，用户触发 `SmsConversationEvent.CooldownBlocked`（如冷却内重发另一失败气泡）或 `SmsConversationEvent.ShowError`（如操作/刷新报错）。
- **期望行为**：在 `viewModel.events.collect` 调用 `snackbarController.show(...)` / `showMessageUiError(...)` 之前先执行 `undoSnackbarJob?.cancel()`，让冷却/错误提示立即顶替旧撤销条显示，无需等待上一条撤销条自然超时；被取消的旧撤销条不误触发回调。
- **回归测试**（`MessagesScreenBehaviorTest.kt`）：删除失败气泡使 `已删除` Snackbar 在途（不推进超时）→ 立即触发冷却阻塞或错误事件 → 断言新冷却/错误文案立即可见且旧 `已删除` 已顶替消失；撤回 `undoSnackbarJob?.cancel()` 后新提示排队阻塞、测试立即变红。

### ② `R-E8` 跨卡：`NextAppState` `SavedState` 只存短消息对端 `uid`，不存用户名
- **文件**：`hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt`（L75–141 `NextAppStateSaver`）
- **触发路径**：打开短消息根会话（`selectedSmsConversation`）或右栏下钻短消息（`DetailDrilldownEntry.SmsConversation`）后触发 `NextAppStateSaver.save(...)` 写入 Android `Bundle`。
- **期望行为**：`NextAppStateSaver.save` 对 `selectedSmsConversation` 与下钻 `DetailDrilldownEntry.SmsConversation` **仅持久化 `uid`，不把对方 `username` 写入保存列表**（保持 20 元列表槽位兼容：索引 17 写 `null` 或 `""`，下钻 `"s"` 三元组第 3 元写 `""`）；`restore` 恢复为 `SmsConversationArgs(uid, "")`（进入对话后由 `E8` 已落地的 `SmsConversationViewModel.loadConversation` 自动回填对端用户名）；`UserProfile` 及旧 16 元列表恢复保持 100% 兼容。
- **授权更新既有测试**：同步调整 `FeaturePaneSlotsTest.kt` 中既有 `NextAppStateSaver` 往返用例对短消息 `username` 恢复为 `""` 的预期（仅此字段，不放宽其他断言，并在交付报告列出）。
- **回归测试**（`FeaturePaneSlotsTest.kt`）：构造含哨兵用户名（如 `"PEER_NAME_SENTINEL"`）的 `selectedSmsConversation` 与 `DetailDrilldownEntry.SmsConversation` → 调 `NextAppStateSaver.save(...)` → 递归/全量序列化检查保存结果**不含哨兵用户名**，且 `restore` 精确还原 `uid` 与空 `username`（同时保持旧 16 元恢复测试通过）；撤回脱敏立即变红。

### ③ `R-E3 R2-S1`：`detail:$tid` 保存态加 LRU 上限（仅改 `feature/app/`）
- **文件**：`hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt`（`MultiPaneSlotHolder`，L114–151；不碰 `feature/threaddetail/*`）
- **上限与淘汰策略**：
  - 上限固定为 **`20` 个帖子**（常量 `MAX_SAVED_DETAIL_STATES = 20`，`MultiPaneSlotHolder` 内部构造可接受默认参数 `maxSavedDetailStates: Int = MAX_SAVED_DETAIL_STATES`，不暴露测试专用语义键/钩子）；
  - 淘汰策略为**最久未访问（LRU）**：在 `MultiPaneSlotHolder` 维护按访问序排列的已保存 `tid` 集合（如 `LinkedHashMap<String, Unit>(..., accessOrder = true)` 或访问序链表）；每次挂载或重访某 `tid` 时刷新为最近访问（MRU）；当某 `tid` 离树且不在 `activeThreadTids` 时，先保留其 `detail:$tid` 保存态供同会话重开恢复，再检查已记录 `tid` 总数，若超出上限 `20`，按最久未访问顺序淘汰非活跃 `tid`（绝不淘汰 `activeThreadTids` 中的当前活跃帖）并调用 `saveableStateHolder.removeState(detailSaveableKey(evictedTid))`。
- **触发路径**：单次长会话先后浏览超过 20 个不同 `tid` 的帖子，或重访早期帖子将其刷新为 MRU 后继续打开新帖超过上限。
- **期望行为**：最近访问的 `≤ 20` 个帖子重开时保存态保留；超限时最久未访问的非活跃帖子保存态被精准清除（重开回到初始态），重访刷新过的 MRU 帖子与当前活跃帖子绝不被误淘汰。
- **回归测试**（`MultiPaneStateMigrationTest.kt`）：① 依次打开 21 个不同帖子并写入各帖局部 `rememberSaveable` 状态 → 验证第 21 个之后最久未访问的 `tid=1` 重开时状态归零，最近 20 个内的帖子状态保留；② 依次打开 `tid=1..20` → 重访 `tid=1`（刷为 MRU）→ 打开 `tid=21` → 验证被淘汰的是 `tid=2` 而 `tid=1` 仍保留；撤回 LRU 淘汰后 `tid=1`/`tid=2` 未被清除、测试立即变红。

### ④ `R-E12 R2-S1`：黑名单取 `prevNeighbor` 时排除 `removingUsernames` 在途项
- **文件**：`hipda/src/main/java/net/jejer/hipda/feature/settings/BlacklistScreen.kt`（`BlacklistViewModel.removeUser`，L109）
- **触发路径**：黑名单列表为 `[A, B, C, D]`，慢网下先点移出 `C`（`C` 仍在 `removingUsernames` 且尚未从 `activeBlockedSet` 移除），紧接着立即点移出相邻的 `D`；待两请求完成后点最新 Snackbar「撤销」恢复 `D`。
- **期望行为**：`BlacklistViewModel.removeUser` 计算可见列表时排除在途移除项：`state.orderedUsernames.filter { it in state.activeBlockedSet && it !in state.removingUsernames }`，使 `D` 捕获的前邻居锚点直接跳过即将消失的 `C`、锁定为 `B`；后续撤销 `D` 时精确插回 `B` 之后得到 `[A, B, D]`（而不是因锚点 `C` 已消失而回退插到首位 `[D, A, B]`）。
- **回归测试**（`BlacklistScreenAndMockWebServerTest.kt`）：列表 `[A, B, C, D]`，挂起移出 `C`（在途）同时发起移出 `D` → 放行两者成功 → 撤销 `D` → 断言令牌 `previousNeighbor == "B"` 且恢复后列表顺序严格为 `[A, B, D]`；撤回 `&& it !in state.removingUsernames` 后 `D` 锚点为 `"C"` 导致错插首位 `[D, A, B]`、测试立即变红。

### ⑤ `R-E10 R2-S1`：`SearchViewModel` / `FavoritesViewModel` 无未决令牌时刷新秩表
- **文件**：
  - `hipda/src/main/java/net/jejer/hipda/feature/mine/MineCommon.kt`（为 `UndoTokenManager` 补只读 `val hasActiveToken: Boolean get() = activePayload != null`）
  - `hipda/src/main/java/net/jejer/hipda/feature/search/SearchViewModel.kt`（L102–128）
  - `hipda/src/main/java/net/jejer/hipda/feature/mine/FavoritesViewModel.kt`（L40–45、L152–156）
- **触发路径**：
  - **搜索历史**：初始历史 `["苹果", "香蕉", "咖啡"]` 已建立秩表 `{苹果:0, 香蕉:1, 咖啡:2}`；用户重搜旧词 `"咖啡"` 使其提升到首位变为 `["咖啡", "苹果", "香蕉"]`；在无活跃撤销令牌下删除首位 `"咖啡"` 后立即点「撤销」。
  - **收藏/关注**：首页 `[7001, 7002, 7003]` 已建立秩表 `{7001:0, 7002:1, 7003:2}`；下拉刷新首页返回重排新序 `[7003, 7001, 7002]`（无活跃撤销令牌且无在途移除/恢复）；取消首位 `7003` 后立即点「撤销」。
- **期望行为**：
  - `SearchViewModel.syncHistoryRanks(history)`：当 `!undoManager.hasActiveToken` 时清空并按最新 `history` 顺序重建 `historyRanks`（存在活跃令牌时仅增量补充新词，不干扰在途撤销）；
  - `FavoritesViewModel` 在首页（`!append`）拉取成功且 `!undoManager.hasActiveToken && tr.pendingRemovals.isEmpty() && tr.pendingRestores.isEmpty()` 时，按最新 `merged` 重建 `tr.ranks` 与 `tr.nextRank`（翻页 `append` 或存在活跃令牌/在途变更时维持 `ensureRanks` 增量追加）；
  - 上述场景撤销后 `"咖啡"` 与 `7003` 均精确恢复回**第 1 位**。
- **回归测试**：
  - `SearchPaneTest.kt`：初始 `["苹果", "香蕉", "咖啡"]` 触发建秩（如先删再撤/过期）→ 重搜 `"咖啡"` 使其升至首位 `["咖啡", "苹果", "香蕉"]` → 删除 `"咖啡"` 再点「撤销」→ 断言顺序精确为 `["咖啡", "苹果", "香蕉"]`（撤回刷新秩表后错落末尾，立即变红）；
  - `MinePagesBehaviorTest.kt`：首页 `[7001, 7002, 7003]` 建秩 → 刷新首页返回 `[7003, 7001, 7002]` → 取消 `7003` 再点「撤销」→ 断言顺序精确为 `[7003, 7001, 7002]`（撤回刷新秩表后错落末尾，立即变红）。

### ⑥ `R-E14 R2-S1`：多图远滑回收后重返已加载原图页不重复弹提示
- **文件**：`hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt`（L365–370）
- **触发路径**：5 张图片的多图看图浮层中，第 0 页点「查看原图」下载解码完成后弹出一次 `已加载原图` Snackbar；用户横滑至第 4 页使第 0 页离屏回收，再滑回第 0 页时 `ImageViewerPage` 重新解码已缓存的 `pendingOriginal` 原图文件并再次回调 `onOriginalDecodeResult(ok = true, ...)`。
- **期望行为**：`onOriginalDecodeResult` 中先记录 `val wasAlreadyCompleted = stateOf(page).downloadState is OriginalDownloadState.Completed`，仅在 `!wasAlreadyCompleted` 时弹出 `已加载原图` Snackbar；重返第 0 页仍正常展示原图且底栏保持 `已加载原图` 状态，但不再重复弹提示。
- **回归测试**（`ImageViewerHardeningRegressionTest.kt`）：5 张图注入计数 `PaneSnackbarController` → 第 0 页下载原图完成，断言 `已加载原图` 弹出次数 `== 1` → 滑至第 4 页再滑回第 0 页等待重新解码稳定 → 断言仍为原图完成态且 `已加载原图` 弹出次数仍严格 `== 1`；撤回 `!wasAlreadyCompleted` 守卫后弹出次数变为 `2`、测试立即变红。

【验收标准】
1. 上述 6 项小修全部落地，且每项均有至少 1 条对应的自动化回归测试（撤回任一项修复对应测试均必变红）。
2. 文件边界合规：零修改 `feature/threaddetail/*` 与共享构建/清单文件；既有 Roborazzi 截图 0 漂移。
3. 本地按 DISPATCH §2 跑通受影响包单测（`feature/messages`、`feature/app`、`feature/settings`、`feature/search`、`feature/mine`、`feature/imageviewer`）+ `CopyTerminologyScanTest` + `MotionTokenScanTest` + `:hipda:assembleDebug`；推送后 GitHub Actions CI 全绿。

【改哪些文件】
- 修改（生产，严禁碰 `feature/threaddetail/*`）：
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/settings/BlacklistScreen.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/mine/MineCommon.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/search/SearchViewModel.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/mine/FavoritesViewModel.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt`
- 修改（测试）：
  - `hipda/src/test/java/net/jejer/hipda/feature/messages/MessagesScreenBehaviorTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/app/FeaturePaneSlotsTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneStateMigrationTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/settings/BlacklistScreenAndMockWebServerTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/search/SearchPaneTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/mine/MinePagesBehaviorTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/imageviewer/ImageViewerHardeningRegressionTest.kt`
- 报告（仓外）：`/home/mir/Documents/hipda-next.wt/_reports/M6-polish.md`

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 本地测试策略：本地只跑本卡相关的测试（用 `--tests <包或类>` 指定），外加 `CopyTerminologyScanTest`、`MotionTokenScanTest` 与 `:hipda:assembleDebug`（按 `lesson.md` 导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`，`flock` 串行）；全量 `testDebugUnitTest` 与 Roborazzi `verify` 以 GitHub Actions CI 为准（公开仓库 Actions 不计费）。本地变绿后才推送，不要拿 CI 调试半成品。完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。替换旧实现的卡，只允许更新本卡范围清单中明确授权的既有测试和截图；清单之外的失败仍按停止条件处理。
- 卡片预期的可见变化直接导致失效的既有测试/快照，可直接更新不必停下，前提是不放宽断言与预算，并在报告里逐项列出改动供 R 核对。
- 统一撤销模型：每个可撤销操作独立令牌（被删条目 + 前一邻居 id 锚点，不用下标；清空捕获全部条目）；新的可撤销操作立即关闭上一条 Snackbar 并视为确认；撤销只恢复本令牌条目并按锚点放回；须有“连续两次删除再撤销”的测试。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/<卡号>.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。任何把站点内容（帖子、短消息、签名、链接、图片）交给系统或 WebView 打开、加载的代码，必须走 scheme 白名单，只允许 `http`、`https`，以及经过校验的 `mailto`；每种危险 scheme 都要有反例测试。
