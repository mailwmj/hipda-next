# 代码审查报告 R-E6b：编辑已发表楼层（含还原原文）与楼层菜单删除自己楼层

- **审查结论**：**APPROVE**（0 阻断项，2 项非阻断建议，附 `card/E7-emoji-panel` 合并冲突清单与精确解法）
- **被审分支 / Commit**：`origin/card/E6b-edit-delete-post` @ `7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`（父提交即当前最新 `origin/main` @ `ed361c255b975874fa0eb7a4a60f431400c2af55`，已含 `E6@adc777b` 与 `M7-G3@bb5a870`）
- **GitHub Actions CI**：[Android CI #45813940387](https://github.com/mailwmj/hipda-next/actions/runs/45813940387) — `headSha == 7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`，`conclusion == success`（`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿）
- **审查员**：Carbon（只读审查，主目录 `/home/mir/Documents/hipda-next` 0 改动）

---

## 1. 门禁、边界与代码规模核验

| 检查项 | 核验结果 | 证据 |
| --- | --- | --- |
| CI 全套门禁 | **通过** | Run `45813940387`（Job `139888664594`）通过 `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true` |
| 文件边界与禁改目录 | **通过** | `git diff --name-status ed361c2...7ac4c829` 仅含 9 个授权生产文件、3 个新增测试文件与 4 张新增 `editor_edit_*.png`；`feature/routing/*`、`feature/forum/*`、`feature/messages/*`、`feature/settings/*`、`feature/profile/*`、`feature/mine/*`、`feature/search/*`、`:emoji/*`、`data/*`、`feature/app/*` 及构建/清单/CI 共享文件 **0 改动** |
| 生产净增与标准组件 | **通过** | 生产代码 `+703 / -68`（净增 **+635 行** ≤ 卡片 1.5× 警戒线 720 行）；全用 Compose 标准组件（0 手写 `Layout`/`Modifier.Node`），0 测试专用钩子/语义键，`MotionTokenScanTest` 与 `CopyTerminologyScanTest` 白名单 **0 新增** |
| 既有快照零漂移 | **通过** | 既有 `thread_*`、`quick_reply_*`、`editor_*`、`multipane_*` PNG 快照 **0 修改** |
| 安全白名单与错误脱敏 | **通过** | 选图保持 `isAllowedUploadContentUri`（仅非空 authority 的 `content://` 且禁空白/控制字符）；线上已有图片在编辑器内使用本地矢量占位不发起未校验加载；`FullEditorScreen.kt:457` 与 `FloorDeleteConfirmDialog.kt:31` 均拦截 `Exception`/`Throwable`/`HTTP 4xx/5xx`/`[attachimg]` 原文泄漏 |

---

## 2. 卡片重点与负责人 6 项补充逐项审计

### 2.1 补充 (1)：「编辑」「删除」菜单仅对本人楼层显示（uid 比对当前账号，切号后即时更新）
- **判定逻辑**：`FloorContextMenu.kt:124, 152-156` 以 `val isOwnFloor = isLoggedIn && !currentUserUid.isNullOrBlank() && currentUserUid == activeFloor.uid` 严格控制 `编辑` / `删除` 显隐；未登录、会话过期或他人楼层均不渲染，且全菜单无“举报”。
- **切号即时同步**：`ThreadDetailViewModel.kt:300-304, 1002-1021, 1339-1344` 通过 `combine(auth.currentAccount, auth.authState)` 实时监听账号切换、登出与会话失效，立即更新 `metaState.currentUserUid`（`SignedOut`/`SessionExpired` 置 `null`）并重算 `uiState.isLoggedIn`，无需重拉帖子即可驱动 `ThreadDetailPane.kt:965-968` 的菜单重组更新。`requestDeleteFloor`（`ThreadDetailViewModel.kt:796-797`）二次校验 `cur.currentUserUid == floor.uid`。

### 2.2 补充 (2)：编辑原文 `[attachimg]` 反向解析与 `deletedAttachments` 提交正确，不误删未移除附件
- **`[attachimg]` 反向解析**：`FullEditorViewModel.kt:636-639` 调用 `EditorBodyDocument.parse(info.text)`（`EditorContentModel.kt:124, 128-158`），将 `[attachimg]{id}[/attachimg]` 解析为 `EditorBodyBlock.Image` 可视化缩略图块，文本块 0 处暴露 `[attachimg]` 源码；基准快照 `EditOriginalSnapshot.serializedBody` 使用归一化后的 `origDoc.serialize()`，避免因 `\r\n` 或内联换行差异误判“已修改”。
- **`deletedAttachments` 与不误删保障**：
  1. `origImages = (info.existingImages + origDoc.insertedImageIds()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()`（`FullEditorViewModel.kt:638`）完整收录线上文内图片与文末未插入图片附件，非图片附件 `info.existingAttachments` 不进入图片差集，绝不会被误填入 `attachdel[]`；
  2. `deletedEditAttachmentIds`（`FullEditorViewModel.kt:133-134`）仅当用户在 `ImageUploadStrip` 点 `×` 移除某附件使其不再属于 `uploadedImageIds` 时才计入；仅从正文块移除内联图（`onRemoveInlineImageBlock`）仍保留在 `uploads`（标 `未插入`），不会触发 `attachdel[]` 误删；
  3. `newEditAttachmentIds`（`FullEditorViewModel.kt:131-132`）排除 `editOriginal.imageIds`，防止未插入正文的线上既有图片在 `DefaultPostRepository.appendUninsertedImages` 中被当成新图重复追加 `[attachimg]`；
  4. 草稿保存（`FullEditorViewModel.kt:497`）与 `FullEditorSavedStateSaver`（`FullEditorHost.kt:100-138`）完整持久化当前 `uploadedImageIds` 与 `editOriginalImageIds`，草稿恢复与进程重建后均不会误删保留的线上附件。
- **免 30s 冷却与不附小尾巴**：`effectiveCooldownSeconds` 在编辑态恒为 `0`（`FullEditorViewModel.kt:129, 435`），配合 `DefaultPostRepository.kt:151, 209, 766`（`EditPost` 不检冷却、不刷冷却、不拼 `buildTailBbcode`），经 `MockWebServer` 实测抓包验证。

### 2.3 重点 2：`edit:<pid>` 草稿与收口 `R-D6 S2`
- `persistOrCleanDraft`（`FullEditorViewModel.kt:489-499`）：当 `isEmpty || !isDifferentFromEditOriginal(snap) || sig == lastSubmittedSignature` 时不调 `saveDraft`、不发 `DraftSaved` 事件；若 `snap.hadPersistedDraft == true`（恢复旧草稿后手动删空或点 `还原原文` 回到线上原文再退出），**显式调用 `postRepository.deleteDraft(key)`**。
- `clearRestoredDraft`（`FullEditorViewModel.kt:397-411`）：点横条 `清空` 时立即清零状态并显式调用 `postRepository.deleteDraft(key)`。
- 撤回即红验证：`EditPostMockWebServerAndDraftTest.editDraftLifecycle_unmodifiedZeroSave_modifiedSaveAndDiscard_andRD6S2NeverResurrects` 覆盖未改动退出、改动保存与 `放弃`、以及恢复旧草稿后「手动删空 / 点清空 / 点还原原文」三条 `R-D6 S2` 路径重开不复活。

### 2.4 补充 (3) & 重点 3：删除自己楼层确认框、阅读位置保持（经 `PrependAnchorController`）与首楼整帖删退回
- **二次确认框文案与不可撤销**：`FloorDeleteConfirmDialog.kt:45-90` + `strings_thread_detail.xml:77-80` 逐字对齐 `docs/design/copy.md:76, 148`（标题 `删除这条回复？`、副行 `删除后无法恢复。`、按钮 `取消` / 错误色 `删除`、提交中 `正在删除…` 并禁用按钮/遮罩/Back）；`key(state.floor.postId) { BackHandler }` 保证返回优先级高于 `PositionStack`。确认后调 `PostSubmitRequest.DeletePost`，全程无撤销令牌、0 撤销 Snackbar。
- **普通楼层删除保阅读位**：`ThreadDetailViewModel.kt:821` 仅原地过滤 `rawFloors.filterNot { it.postId == floor.postId }` 并通过 `DeletePost(draftKey = DraftKey.edit(pid))` 一并清理对应编辑草稿，**不递增 `resetEpoch`/`prependGeneration`、不下发 `pendingScroll`**；`ThreadDetailPane.kt:841, 859` 依托 `floor:$postId` 稳定键与 `PrependAnchorController` 单一滚动权威（`syncBeforeMeasure` 在 `!genChanged && !headerChanged && pendingScroll == null` 时零干预保持锚点；若被删楼层恰为 `landingAnchorKey`，`stabilizeLandingAnchor` 在 `299-303` 行安全清空），阅读索引与像素偏移零跳动。
- **1 楼 / 整帖删除退回列表**：当服务端重定向 `forumdisplay.php` 返回 `result.threadDeleted == true` 时，`ThreadDetailViewModel.kt:820` 置 `isThreadDeleted = true`；`ThreadDetailPane.kt:790-801` 通知 `FullEditorHostChannel.notifyThreadDeleted()` 刷新论坛列表，若处于全屏阅读先同步调用 `latestFullScreenState?.onToggle()` 复位 `isFullScreenReading = false`，再调 `latestOnBack()`（`MultiPaneBackStack.kt:90-108` 命中 `CloseDetailThread` 或铰链 `DemoteHingeMineSubList`），在单栏退回列表、在三栏清空右栏回 `选一篇帖子开始阅读` 占位。

### 2.5 补充 (4)：「还原原文」的撤销遵循统一令牌模型
- `FullEditorViewModel.kt:58-60, 183-186, 298-320`：每次 `restoreOriginal()` 生成单调递增 `tokenId` 与绑定 `sessionId` 的不可变 `RestoreOriginalUndoToken`（完整捕获 `subject`、`selectedTypeId`、`body` 含光标与图文块、`uploads`、`showDraftRestoredBanner`），覆盖 `activeRestoreUndoToken` 使旧令牌立即作废，同时递增 `snackbarResetSeq`；
- `FullEditorScreen.kt:91-104`：`LaunchedEffect(uiState.snackbarResetSeq)` 先于 `LaunchedEffect(restoreEvent?.id)` 注册，新操作或关闭编辑器立即 `cancel()` 上一条 Snackbar 协程并视为前次操作已确认；点击 `撤销` 调 `undoRestoreOriginal(ev.tokenId)` 仅当 `id == tokenId && sessionId == cur.sessionId` 时恢复。已由“连续两次还原原文再分别用旧/新令牌撤销”单测守住（`EditPostMockWebServerAndDraftTest:267-294`）。

### 2.6 补充 (5)：写操作测试全部 `MockWebServer`
- 全diff检索 `4d4y` / `hi-pda` 命中为 **0**。
- `EditPostMockWebServerAndDraftTest` 与 `EditAndDeletePostIntegrationTest` 均使用本地 `MockWebServer` + 真实 `DefaultPostRepository` / `DefaultImageUploadRepository` 完整覆盖编辑（GET 预取、SWF 上传、POST 失败留页重试、POST 302 成功、30s 冷却豁免、0 小尾巴、`attachdel[]`/`attachnew[][description]`）与删除（取消/Back 0 请求、服务端错误 + HTTP 500 + 30s 冷却失败保楼层、普通楼层删除保位清草稿、首楼整帖删除 302 退回）全部路径。

---

## 3. 新增 Roborazzi PNG 截图逐张目视审查

已逐张 `read_file` 目视核验全部 4 张新增快照：
1. `editor_edit_phone_w411_restore_light.png`（411×891dp 浅色单栏）：顶栏完整显示 `←`、`编辑` / `第 1 楼 · linus_hi`、`还原原文`、`发送`；下方依次为 `已恢复上次草稿 | 清空` 横条、`Discovery` 分类下拉（无版块下拉，符合编辑语义）、标题框、双图上传条（第 2 张带清晰 `未插入` 角标）、正文内联缩略图卡片（0 处 `[attachimg]` 文本）与底部 `插入图片` 栏。纯色 0 玻璃，无裁切重叠。
2. `editor_edit_tablet_w1280_rightpane_dark.png`（1280×800dp 深色完整三栏）：左栏 `220dp` 常驻展开、中栏 `380dp` 列表、右栏 `680dp` 内嵌展开纯色编辑器（符合 §4.2 `≥ 560dp` 右栏内展开规则），顶栏 `还原原文` 与 `发送` 对比度清晰，全屏玻璃区域 ≤ 3。
3. `editor_edit_delete_dialog_w411_light.png`（411×891dp 浅色单栏）：暗罩之上居中弹出 `20dp` 大圆角纯色确认框，`删除这条回复？` / `删除后无法恢复。` / `取消` / 红色 `删除` 逐字对齐 `copy.md:148`，字迹锐利。
4. `editor_edit_delete_dialog_w749_dark.png`（749×559dp 深色紧凑三栏）：暗罩与确认框严格限定在右栏（`411dp`）内部居中，不遮挡左 `64dp` 图标轨与中 `274dp` 列表；M3 深色 `error`/`onError` 按钮对比度充足，无溢出或折行异常。

---

## 4. 补充 (6)：`git merge-tree` 冲突核查与解决建议

### 4.1 与最新 `origin/main`（`ed361c2`）
- 执行：`git merge-tree --write-tree --messages origin/main 7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`
- 结果：**`exit=0`（0 冲突）**，`7ac4c829` 直接基于 `ed361c2` 快进（fast-forward）。

### 4.2 与 `origin/card/E7-emoji-panel`（`0428dd4`）
- 执行：`git merge-tree --write-tree --messages origin/card/E7-emoji-panel 7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`
- 结果：**`exit=1`，仅 1 个文件存在文本冲突**，其余全部生产文件、测试文件与 PNG 快照均自动无冲突合入（`EditAndDeletePostScreenshotTest` 未显式注入 `emojiPanelSlot`，合入 E7 后 4 张 `editor_edit_*.png` 亦不会漂移）。
- **唯一冲突文件**：`hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt`（共 4 处冲突块）
- **合并解决建议（供后合入方逐块执行，兼顾 E6b 编辑态与 E7 等高表情切换）**：
  1. **`FullEditorScreen` 主体容器（冲突块 1，约 `L104-L203`）**：
     - 保留 **E7** 的外层 `BoxWithConstraints`、`emojiController = rememberEmojiPanelController(...)`、`onInputPress` 以及底部的 `EmojiPanelSlotBox`（**切勿在 `Column` 上重复加 E6b 旧的 `.imePadding()`**，因 `!isModalCard` 时已由 `EmojiPanelSlotBox` 的 `bottomSlotHeightDp` 统一消费 IME 高度，重复叠加会导致双倍键盘留白）；
     - 保留 **E6b** 的 `FullEditorTopBar(..., uiState.effectiveCooldownSeconds, uiState.canTapSend, uiState.canRestoreOriginal, closeEditor, viewModel::restoreOriginal, viewModel::submit)`（**注意必须传 `uiState.effectiveCooldownSeconds` 而非 `uiState.cooldownSeconds`**，否则编辑态免 30s 冷却展示会回退）、顶栏下方的 `if (isEditWaitingOriginal) { EditOriginalStatusArea(...); return@Column }` 分支、以及正文区的 `else if (uiState.isEditPost && uiState.showsEditSubjectField) { EditPostMetaSection(..., onInputPress = onInputPress) }`。
  2. **`NewThreadMetaSection` 与 `EditorSubjectField`（冲突块 2 & 3，约 `L330-L370`）**：
     - 保留 **E6b** 抽出的 `EditorSubjectField` 并保留 `syncVersion: Long` 参数透传给 `rememberSyncedTextFieldState(text = subject, syncVersion = syncVersion, ...)`（**不可漏传 `syncVersion`**，否则点 `还原原文` / `撤销` / `清空` 时标题输入框不会同步刷新）；
     - 同时把 **E7** 的 `onInputPress: () -> Unit = {}`（`MutableInteractionSource` 监听 `PressInteraction.Press` 调 `latestOnInputPress()`）合入 `EditorSubjectField`，并由 `NewThreadMetaSection` 与 `EditPostMetaSection` 共同透传 `onInputPress`，使发新帖与编辑首楼点击标题框时都能从表情面板切回软键盘。
  3. **`EditPostMetaSection` 与 `EditorRichBodySection` 签名（冲突块 4，约 `L373-L419`）**：
     - 同时保留 **E6b** 新增的 `EditPostMetaSection` 函数定义与 **E7** 扩展后的 `EditorRichBodySection(..., onActiveFocusRequester: (FocusRequester) -> Unit = {}, onInputPress: () -> Unit = {})` 签名及函数体即可。

---

## 5. 阻断项与非阻断建议

### 5.1 阻断项（Blocking）
- **无（0 项）**。

### 5.2 非阻断建议（Suggestions）
- **S1（多栏切号时的已打开删除确认框联动 + 切号菜单单测补强）**：`ThreadDetailViewModel.kt:807-814, 1014-1020` 在切换账号或登出时已即时更新 `currentUserUid` 并刷新 `FloorContextMenuOverlay`，但若三栏下右栏已先点开 `FloorDeleteConfirmDialog` 再从左侧栏切换到另一已登录账号 B，`deleteDialog` 不会自动收起且 `confirmDeleteFloor()` 仅校验了 `currentUserUid.isNullOrBlank()` 未复核 `currentUserUid == floor.uid`（服务端仍会拒绝越权删除，无安全风险）。建议后续顺手在 `observeAuthRecovery` 中当 `!isDeleting && activeUid != cur.deleteDialog?.floor?.uid` 时自动关闭确认框、在 `confirmDeleteFloor` 补一行 `cur.currentUserUid == floor.uid` 复核，并补一条切号前后长按菜单即时切换 `编辑`/`删除` 可见性的 UI 单测。
- **S2（独立末页仅剩单楼被删空时的体验微调）**：`ThreadDetailViewModel.kt:821` 在普通楼层删除成功时执行 `rawFloors.filterNot { it.postId == floor.postId }` 保位；极端情况下若用户直接跳到仅含 1 条回复的末页并将其删除（`rawFloors` 变空且 `threadDeleted == false`），页面会落入 `EMPTY` 占位需手动点 `刷新`。后续可在过滤后 `remaining.isEmpty() && filter is All` 时优先回填 `result.redirectedPage` 或回拉前一页。
