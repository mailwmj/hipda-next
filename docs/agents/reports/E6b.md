# 任务卡 E6b 交付报告：编辑已发表楼层（含还原原文）与楼层菜单删除自己楼层

- **分支**：`card/E6b-edit-delete-post`（已 fast-forward 合并 `origin/main@ed361c2`）
- **最终 Commit**：`7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`（短哈希 `7ac4c82`）
- **GitHub Actions CI**：[Android CI #45813940387](https://github.com/mailwmj/hipda-next/actions/runs/45813940387) — **success**（`Build, Lint & Unit Tests` 14m28s 全绿：构建、Lint、Room schema、全量单测、全量 Roborazzi 截图验证、Debug/Release APK 全部通过）
- **工作区**：`/home/mir/Documents/hipda-next.wt/E6b`

---

## 1. 交付范围与边界审计

### 1.1 生产代码变更清单（净增 +635 行 ≤ 720 行 1.5× 警戒线）

| 文件 | 变更行数（净增） | 职责摘要 |
| --- | --- | --- |
| `hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt` | +13 / -3 (+10) | `FullEditorHandoff` 新增 `editPid`/`editPage`/`isFirstFloor` 默认字段；`FullEditorReplySubmitted` 新增 `editedPid`/`editedPage` 默认字段；`safeEditDraftKey(pid)` |
| `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt` | +47 / -10 (+37) | `FullEditorRequestBus.openEditPost`；`FullEditorHostChannel` 透传 `notifyThreadDeleted()` 零改 `feature/app/NextAppRoot.kt`；`FullEditorSavedState` + `Saver` 兼容编辑态与 `editOriginal` 基准；消费 `FullEditorEvent.PostEdited`（首楼刷新列表 + 下发原地刷新事件） |
| `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt` | +126 / -24 (+102) | 编辑态顶栏条件显示 `还原原文`（≥48dp、`hiPressScale`）+ `LocalPaneSnackbarController` 独立令牌 `已还原原文 \| 撤销`；`EditOriginalStatusArea` 加载/错误重试；`EditPostMetaSection`（分类/标题无版块选择）；`effectiveCooldownSeconds` 免 30s 冷却展示；**未改动任何 E7 表情接入行** |
| `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt` | +236 / -25 (+211) | `FullEditorMode.EditPost` / `FullEditorRequest.EditPost` / `EditOriginalSnapshot` / `RestoreOriginalSnackbarEvent` / `FullEditorEvent.PostEdited`；`loadEditPostSession` 预取 `PrePostRequest.EditPost` 并反向解析 `[attachimg]` 为可视化缩略图块；`restoreOriginal` / `undoRestoreOriginal` 独立令牌撤销；`DraftKey.edit(pid)` + `R-D6 S2` 显式 `deleteDraft`；编辑免 30s 冷却与 `newEditAttachmentIds`/`deletedEditAttachmentIds` |
| `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/interact/FloorDeleteConfirmDialog.kt` | +102 / -0 (+102) | 新增不可撤销删除确认框（纯色 0 玻璃、`key(state.floor.postId) { BackHandler }` 置顶、逐字对齐 `copy.md:145/150` 文案、错误色确认按钮、确认中 `HiLoadingSpinner` + `正在删除…` 防重入、失败原地脱敏提示与登录引导） |
| `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt` | +115 / -4 (+111) | 注入 `PostRepository`（保留 2/3/4/5 参重载零破坏）；`onPostEdited` 原地刷新目标楼层不改滚动位置；`requestDeleteFloor` / `dismissDeleteFloorDialog` / `confirmDeleteFloor`（普通楼层移除保位且清 `edit:<pid>` 草稿、首楼整帖删除置 `isThreadDeleted = true`、失败保楼层原地提示、严禁撤销 Snackbar） |
| `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt` | +54 / -2 (+52) | 接通 `FloorContextMenuOverlay.onEdit` / `onDelete`；订阅 `LocalFullEditorReplyResults` 的 `editedPid` 分支原地刷新；`isThreadDeleted` 触发 `onThreadDeleted` 或 `FullEditorHostChannel.notifyThreadDeleted()` + 退出全屏阅读 + `onBack()`；挂载 `FloorDeleteConfirmDialog` |
| `hipda/src/main/res/values/strings_compose.xml` | +4 / -0 (+4) | `编辑`、`还原原文`、`已还原原文`、`撤销`（逐字取自 `copy.md`） |
| `hipda/src/main/res/values/strings_thread_detail.xml` | +6 / -0 (+6) | `删除这条回复？`、`删除后无法恢复。`、`取消`、`正在删除…`（逐字取自 `copy.md`） |
| **生产代码合计** | **+703 / -68 (净增 +635)** | 全部在授权边界内，低于 720 行 1.5× 警戒值 |

### 1.2 约束核对

- **禁改目录**：`feature/routing/*`、`feature/forum/*`、`feature/messages/*`、`feature/settings/*`、`feature/profile/*`、`feature/mine/*`、`feature/search/*`、`:emoji/*`、`data/*`、`feature/app/*` 及全部共享构建/清单/CI 文件 **0 改动**。
- **真实站点红线**：全量新增测试文件 grep `4d4y|hi-pda` 结果 **0 命中**，网络闭环 100% 使用 `MockWebServer`。
- **动效/文案/组件合规**：全部使用 Compose 标准组件（无自定义 `Layout`/`Modifier.Node`/手写命令队列）；0 生产测试钩子；`MotionTokenScanTest` 白名单 0 改动（7/7 通过）；`CopyTerminologyScanTest` 0 违规。

---

## 2. 验收标准逐条验证

| # | 验收标准 | 结果 | 核心验证测试与证据 |
| --- | --- | --- | --- |
| 1 | **编辑已发表楼层与「还原原文」闭环**：`MockWebServer` 实测 `PrePostRequest.EditPost` 拉取原文 → `[attachimg]` 反向解析 0 源码暴露 → 偏离原文时 `还原原文` 出现、一致时隐藏 → 点击 `还原原文` 弹 `已还原原文 \| 撤销` → 独立令牌 `撤销` 恢复编辑态（连续两次还原作废旧令牌）→ 免 30s 冷却、不附小尾巴、正确传 `attachdel[]` / `attachnew[][description]`、提交成功清 `edit:<pid>` 草稿并原地刷新楼层不跳位 | **通过** | `EditPostMockWebServerAndDraftTest.mockWebServer_editPostFullFlow_restoreUndoTokens_cooldownBypass_noTail_deletedAndNewAttachments_clearsDraft`、`EditPostMockWebServerAndDraftTest.editPrePostFailureRetry_zeroMsSubmit_andSavedStateSaverRoundTrip`、`EditAndDeletePostIntegrationTest.nextAppRoot1280_editOwnFloorInRightPane_restoreUndo_submitRefreshesFloorWithoutScrollJump` |
| 2 | **`edit:<pid>` 草稿与 `R-D6 S2`（撤回即红）**：未改动原文退出 0 保存 0 提示；改动后返回静默存 `edit:<pid>` 并弹 `已保存草稿 \| 放弃`；恢复旧草稿后手动删空 / 点横条 `清空` / 点 `还原原文` 后退出均显式调 `deleteDraft(DraftKey.edit(pid))`，重开加载线上原文绝不复活 | **通过** | `EditPostMockWebServerAndDraftTest.editDraftLifecycle_unmodifiedZeroSave_modifiedSaveAndDiscard_andRD6S2NeverResurrects`、`EditAndDeletePostIntegrationTest.nextAppRoot1280_editOwnFloorInRightPane_restoreUndo_submitRefreshesFloorWithoutScrollJump` |
| 3 | **删除自己楼层（仅 `MockWebServer`，三路径全覆盖）**：确认框文案逐字对齐 `copy.md`（`删除这条回复？` / `删除后无法恢复。` / `取消` / `删除` + `正在删除…`）；取消与 Back 均 0 网络请求；普通楼层删除成功移除目标楼层、首可见索引与像素偏移零跳动、清理 `edit:<pid>` 草稿、0 撤销 Snackbar；首楼整帖删除（`threadDeleted == true`）退回列表/清空右栏并刷新论坛列表；服务端错误/HTTP 500/30s 冷却失败均保留楼层并原地脱敏提示、允许重试成功 | **通过** | `EditAndDeletePostIntegrationTest.deleteOwnFloor_mockWebServer_cancelSuccessPreserveScrollFailureRetryAndFirstFloorDeleteThread`、`EditAndDeletePostIntegrationTest.nextAppRoot749_editOpensCenteredModal_andFirstFloorDeleteClosesRightPaneAndRefreshesForum`、`EditAndDeletePostIntegrationTest.nextAppRoot411_editOpensFullScreen_andFirstFloorDeleteReturnsToListAndRefreshesForum` |
| 4 | **复用 E6 多栏与返回优先级**：`411dp` 全屏（0 玻璃）、`749dp` 居中模态（玻璃 ≤ 3）、`1280dp` 右栏内展开（≥600dp、玻璃 ≤ 3、0 嵌套 backdrop）；删除确认框与编辑态 `BackHandler` 严格优先于 `PositionStack`，不破坏 E5/E6/M7-G3 | **通过** | `EditAndDeletePostIntegrationTest`（全部 4 项 `411`/`749`/`1280dp` 端到端用例）、`MultiPaneGlassBudgetTest`、`FloorContextMenuTest`、既有 `feature/compose/*` 与 `feature/threaddetail/*` 全套回归 |
| 5 | **边界、截图与 CI**：0 改动禁改目录；既有全部 PNG 快照 0 漂移；新增 4 张 `editor_edit_*.png` 专项快照；本地 targeted 测试 + `:hipda:assembleDebug` + 推送后 GitHub Actions CI 全绿 | **通过** | 本地 243/243 通过 + `:hipda:assembleDebug` 成功；CI [45813940387](https://github.com/mailwmj/hipda-next/actions/runs/45813940387) 全绿 |

---

## 3. 新增 Roborazzi 快照清单（供 `R-E6b` 目视核验）

既有全部快照（`thread_*`、`quick_reply_*`、`editor_*`、`multipane_*`）**0 修改、0 漂移**；本卡仅新增以下 4 张 `editor_edit_*.png` 基线（均已目视检查排版、对比度、无截断与纯色表面）：

1. `hipda/src/test/snapshots/images/editor_edit_phone_w411_restore_light.png`
   - 单栏 411dp 浅色：编辑 1 楼，展示 `编辑` / `第 1 楼 · linus_hi` / `还原原文` / `发送` 顶栏、`已恢复上次草稿 | 清空` 横条、分类下拉与标题输入框、双图上传条（已插入 + `未插入` 标签）及正文内联缩略图块（0 原始 `[attachimg]` 源码），0 玻璃。
2. `hipda/src/test/snapshots/images/editor_edit_tablet_w1280_rightpane_dark.png`
   - 平板 1280dp 深色三栏：右栏（≥560dp）内展开编辑 1 楼，顶栏展示 `还原原文` 与 `发送`，左/中栏保留导航与列表，玻璃预算 ≤ 3。
3. `hipda/src/test/snapshots/images/editor_edit_delete_dialog_w411_light.png`
   - 单栏 411dp 浅色：长按本人楼层点「删除」弹出的纯色二次确认框，完整呈现 `删除这条回复？` / `删除后无法恢复。` / `取消` / 错误色 `删除`。
4. `hipda/src/test/snapshots/images/editor_edit_delete_dialog_w749_dark.png`
   - 749dp 紧凑三栏深色：右栏内弹出删除确认框，深色错误按钮对比度清晰，玻璃预算 ≤ 3。

---

## 4. 对外与模块内新增/变更 API 清单

| 符号与签名 | 位置 | 说明 |
| --- | --- | --- |
| `data class FullEditorHandoff(..., val editPid: String? = null, val editPage: Int = 1, val isFirstFloor: Boolean = false)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt:72-89` | 向后兼容扩展编辑楼层交接载荷 |
| `data class FullEditorReplySubmitted(..., val editedPid: String? = null, val editedPage: Int? = null)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt:128-133` | 编辑器编辑成功后向 `ThreadDetailPane` 透传目标楼层信息 |
| `internal fun safeEditDraftKey(pid: String): DraftKey?` | `hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt:177` | 安全构造 `DraftKey.edit(pid)` |
| `FullEditorMode.EditPost(val fid: Int, val tid: String, val pid: String, val page: Int = 1, val isFirstFloor: Boolean = false, val floorNumber: Int? = null, val author: String? = null)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:36` | 编辑已发表楼层模式 |
| `FullEditorRequest.EditPost(...)` & `FullEditorRequest.fromHandoff(handoff: FullEditorHandoff): FullEditorRequest` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:44-51` | 编辑请求及 `FullEditorHandoff` 自动分发 |
| `data class EditOriginalSnapshot(val subject: String, val typeId: String, val serializedBody: String, val imageIds: List<String>)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:55` | 线上原文归一化基准快照 |
| `data class RestoreOriginalSnackbarEvent(val id: Long, val tokenId: Long)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:58` | `还原原文` 独立撤销令牌事件 |
| `FullEditorUiState.{editOriginal, isLoadingEditOriginal, editLoadError, restoreSnackbarEvent, snackbarResetSeq, isEditPost, showsEditSubjectField, effectiveCooldownSeconds, canRestoreOriginal, newEditAttachmentIds, deletedEditAttachmentIds, canTapSend}` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:114-135` | 编辑态状态、免冷却计算、原文偏离判定与附件增删差集 |
| `FullEditorEvent.PostEdited(val tid: String, val pid: String, val page: Int, val isFirstFloor: Boolean, val redirectedPage: ThreadDetailPage?)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:144` | 编辑成功事件 |
| `FullEditorViewModel.retryEditLoad()` / `restoreOriginal()` / `undoRestoreOriginal(tokenId: Long)` / `consumeRestoreSnackbarEvent(eventId: Long)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:292-320` | 编辑原文重试、`还原原文` 与独立令牌撤销入口 |
| `FullEditorRequestBus.openEditPost(fid: Int, tid: String, pid: String, page: Int = 1, isFirstFloor: Boolean = false, floorNumber: Int? = null, author: String? = null)` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt:60-61` | 单例请求总线直接发起楼层编辑 |
| `internal class FullEditorHostChannel` (`emit`, `notifyThreadDeleted`) | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt:68-74` | 宿主结果通道，支持首楼整帖删除通知列表刷新（零改 `NextAppRoot.kt`） |
| `FullEditorSavedState`（新增 `editOriginal*` 默认字段）与 `FullEditorSavedStateSaver` | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt:81-138` | 编辑态及原文基准跨进程重建无损序列化 |
| `data class FloorDeleteError(...)` & `data class FloorDeleteDialogState(...)` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:101-102` | 删除确认框状态与脱敏错误模型 |
| `ThreadDetailUiState.{deleteDialog, isThreadDeleted}` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:139-140` | 详情页删除确认框与整帖已删除状态 |
| `ThreadDetailViewModel` 新增 4 参 `(..., authRepository, postRepository)` 与 `@Inject` 6 参构造 | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:206-234` | 注入 `PostRepository` 并保持既有 2/3/4/5 参调用零破坏 |
| `ThreadDetailViewModel.onPostEdited(pid: String, page: Int? = null, redirectedPage: ThreadDetailPage? = null)` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:768` | 编辑成功后原地刷新目标楼层内容（不重置滚动位置） |
| `ThreadDetailViewModel.requestDeleteFloor(floor: PostFloor)` / `dismissDeleteFloorDialog()` / `confirmDeleteFloor()` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:793-828` | 楼层删除确认与提交闭环（不可撤销，0 撤销 Snackbar） |
| `FloorDeleteConfirmDialog(state, onConfirm, onDismiss, onOpenLogin, modifier)` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/interact/FloorDeleteConfirmDialog.kt:35-41` | 不可撤销楼层删除二次确认框 |
| `ThreadDetailPane(..., onThreadDeleted: (() -> Unit)? = null)` | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt:455-470` | 首楼整帖删除可选回调（默认自动刷新论坛列表、退出全屏并返回） |

---

## 5. 本地与 CI 测试记录

### 5.1 本地执行（`flock /tmp/hipda-gradle.lock` 串行 + `-Xmx3g` + `--stop`）

- `:hipda:recordRoborazziDebug --tests "net.jejer.hipda.feature.compose.EditAndDeletePostScreenshotTest"`：仅生成 4 张 `editor_edit_*.png`。
- `:hipda:verifyRoborazziDebug --tests "net.jejer.hipda.feature.compose.EditAndDeletePostScreenshotTest" --tests "net.jejer.hipda.feature.compose.FullEditorScreenshotTest" --tests "net.jejer.hipda.feature.threaddetail.*"`：全部通过，既有快照 0 漂移。
- `:hipda:testDebugUnitTest`（`feature.compose.*`、`feature.threaddetail.*`、`MultiPaneGlassBudgetTest`、`CopyTerminologyScanTest`、`MotionTokenScanTest`）+ `:hipda:assembleDebug`：**243/243 通过，0 失败，0 跳过**，`assembleDebug` 成功。

### 5.2 测试阶段捕获并修复的缺陷

1. `ThreadDetailViewModel` 次构造与 `@Inject` 构造 JVM 签名冲突：拆分为 5 参默认构造 + 4 参 `(..., authRepository, postRepository)` + 6 参 `@Inject` 构造，兼容全部历史测试与 Hilt 注入。
2. `FullEditorViewModel` 初始化登录态 `combine` 首次回放导致 `PrePostRequest.EditPost` 失败后静默自动重试：改为仅在 `wasLoggedIn == false -> loggedIn == true` 的真实登录跃迁时自动重拉，避免覆盖失败态与 `重试` 按钮。

### 5.3 GitHub Actions CI 结果

- Run URL: https://github.com/mailwmj/hipda-next/actions/runs/45813940387
- Commit: `7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`
- 状态：`success`（Build & Lint、Room schema、全量 Unit Tests、全量 Roborazzi Verify、Debug/Release APK 全部通过）。
