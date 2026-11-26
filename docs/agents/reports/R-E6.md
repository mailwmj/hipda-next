# 独立代码审查报告 R-E6：完整编辑器、多图上传条与草稿（含 R-D6 S1/S2 与 R-D1 收口）

- **审查结论**：**CHANGES-REQUESTED**（检出 **2 项阻断 `B1`、`B2`**，3 项非阻断建议 `S1`–`S3`）
- **审查对象**：`origin/card/E6-full-editor` @ `3cbcb9c694782740597e2d154500da80065b5c05`（合入 `origin/main@bb5a870` 含 `M7-G3`；E6 主体提交 `e3b3443`，原开发基线 `afa3783`）
- **合并冲突核验**：
  - 最新 `origin/main` 为 `bb5a87053b0f01f2c010756e334d8167ae8bfeca`（`bb5a870`）；
  - `git merge-tree --write-tree origin/main 3cbcb9c694782740597e2d154500da80065b5c05` 退出码 `0`（tree `92405061beac1553382a9c1e0477553bd12a7773`），**零冲突（可直接 fast-forward）**。
- **CI 核验**：[Android CI #45790084493](https://github.com/mailwmj/hipda-next/actions/runs/45790084493)
  - `headSha`：`3cbcb9c694782740597e2d154500da80065b5c05`（精确匹配）
  - 状态与结论：`completed / success`（Job `139806596358` Build, Lint & Unit Tests）
  - 已从 CI 原始日志逐项核实执行并通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`Verify Room schema consistency`、`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`（全仓 `1554/1554` 单测通过，`:hipda` `1454/1454` 通过，`0` 失败/`0` 跳过）及 `Verify APK outputs`。
- **只读核验**：主目录 `/home/mir/Documents/hipda-next` 与实现 worktree `/home/mir/Documents/hipda-next.wt/E6` 全程零修改（`git status --short` 干净）。

---

## 一、阻断项（Blockers）

### B1：已打开态再次触发 `open()` 会无条件清空在编内容且漏存草稿（`1280dp` 右栏展开态可点左/中栏直触），且 `QuickReplySheet` 手动删空旧草稿后交接 `FullEditor` 会令已删草稿复活

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:199-228`（`open(request)` 未检查 `_uiState.value.isOpen` 同会话幂等，也未在切换会话前对在编内容执行草稿保存/清理即直接 `cancelSessionJobs()` + `resetSession(...)`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:224, 511-533`（`loadDraftForSession` 仅以 `handoffHadText = h.text.isNotEmpty()` 判断是否覆盖正文，未区分“快速回复已恢复旧草稿后被用户手动删空为 `""`”与“尚未水合草稿”）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplyViewModel.kt:215-241` & `ComposeContracts.kt:59-71`（`onHandoffToFullEditor` 未向 `FullEditorHandoff` 透传 `hadPersistedDraft` 状态）
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt:382-384`（`ForumPane` 顶栏 `发新帖` 在 `1280dp` 三栏下常驻无暗罩可点，直接调用 `editorHost.open(FullEditorRequest.NewThread(...))`）
- **违反条款**：负责人补充核查项 `(1)`（多次请求处理与草稿保护）、任务卡验收标准 §2（`E5` 零丢失交接且共用 `reply:<tid>` 草稿一致）、验收标准 §3（草稿自动保存与 `R-D6 S2` 删空旧草稿不复活）及 `DISPATCH.md` §4（丢状态）。
- **具体触发路径（3 条确定性复现路径）**：
  1. **路径 ①（`1280dp` 右栏内展开态重复点「发新帖」瞬间丢失全部在编输入与已传图片）**：
     - 在 `1280×800dp` 平板横屏（`FullEditorPlacement.RightPane`）下，右栏展开完整编辑器，左栏与中栏 `ForumPane` 顶栏无遮罩、完全可交互；
     - 用户在 `Discovery (fid=2)` 点中栏顶栏「发新帖」打开编辑器，输入标题、数百字正文并上传多张图片（尚未退出，Room 尚未落盘）；
     - 用户再次点到中栏顶栏「发新帖」（或外部再次向 `FullEditorRequestBus` 发送同一请求）：`NextAppRoot.kt:383` 调 `editorHost.open(NewThread(2))` → `FullEditorViewModel.open`（`:200-207`）无条件取消在途上传并将 `subject`、`body`、`uploads` 重置为空（若库内曾有更早草稿还会倒退回旧草稿），在编内容瞬间归零。
  2. **路径 ②（`1280dp` 右栏内展开态跨会话切换漏调 `saveDraft` / `deleteDraft` 导致草稿丢失）**：
     - 用户在 `1280dp` 右栏完整编辑器中正在撰写帖子 `1001` 的回复（或 `fid=2` 的新帖），已输入非空正文；随后在左栏切到 `Buy & Sell (fid=6)` 或直接点中栏顶栏「发新帖」触发 `open(NewThread(...))`；
     - `FullEditorViewModel.open` 直接覆盖 `_uiState` 为新会话，**未对前序 `isOpen == true` 会话执行 `saveDraft`（或 `hadPersistedDraft` 删空时的 `deleteDraft`）**，前序回复/新帖输入永久丢失。
  3. **路径 ③（`QuickReplySheet` 手动删空已恢复草稿后点「展开全屏 / 插入图片」，旧草稿在完整编辑器原地复活）**：
     - 帖子 `tid=1001` 存有旧草稿 `"上次未发出的草稿"`；用户打开 `QuickReplySheet` 自动恢复该草稿（`QuickReplyViewModel.uiState.hadPersistedDraft == true`）；
     - 用户在快速回复输入框中全选回删至空串 `""`（未点横条 `清空`、未收起面板），随即点击右上角「展开全屏」或底部「插入图片」准备重新写带图回复；
     - `QuickReplyViewModel.onHandoffToFullEditor` 传入 `handoff.text = ""`；`FullEditorViewModel.open`（`:224`）计算 `handoffHadText = false`，`loadDraftForSession`（`:522`）得出 `applyBody = !handoffHadText && cur.body.isVisuallyEmpty() && draft.content.isNotBlank() == true`，**将用户刚删掉的 `"上次未发出的草稿"` 重新恢复进完整编辑器正文**；若用户随后按返回退出，`requestClose` 甚至会把旧草稿再次 `saveDraft` 写回数据库（注：此边界在 `R-E5.md` §三(1) 已专门预警）。
- **最小修复建议**：
  1. 在 `FullEditorViewModel.open(request)` 入口：若 `_uiState.value.isOpen == true`：
     - 当新请求与当前活跃会话目标一致（例如同为 `NewThread` 且 `request.initialFid?.takeIf { it > 0 } == _uiState.value.selectedFid`）时直接幂等保留当前输入态（若 `request is Reply && request.handoff.openImagePickerImmediately` 仅递增 `pendingImagePickerTrigger`）；
     - 当新请求切换到不同会话目标时，先调用内部静默落盘函数（复用 `requestClose(emitSavedSnackbar = false)` 的 `saveDraft` / `hadPersistedDraft -> deleteDraft` 分支）保存或清理旧会话草稿，再初始化新会话。
  2. 在 `FullEditorHandoff`（`ComposeContracts.kt:59`）末尾追加默认字段 `val hadPersistedDraft: Boolean = false`，由 `QuickReplyViewModel.onHandoffToFullEditor`（`:223`）传入 `_uiState.value.hadPersistedDraft`；`FullEditorViewModel.open(Reply)` 以 `hadPersistedDraft = h.hadPersistedDraft` 初始化，并在 `loadDraftForSession` 中令 `Reply` 交接在 `h.hadPersistedDraft == true` 时绝不用库内旧 `draft.content` 覆盖用户交接过来的空正文（同时保留 `hadPersistedDraft = true` 确保空退时触发 `deleteDraft`）。
  3. 在 `FullEditorViewModelTest` 补齐上述 3 条路径的“撤回即红”单测。

---

### B2：`EditorRichBodySection` 单块文本同步漏监听 `block.text`，导致 7/12 张 `editor_*.png` 基线快照丢失正文文本（仅渲染灰字占位符），且进程恢复测试缺少 UI 可见性断言

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt:329-335`（`LaunchedEffect(syncVersion, block.id)` 未将 `block.text` 纳入 key，与 `:273` 标题框 `LaunchedEffect(subject)` 不一致）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt:286-295`（`onTextBlockChanged` 在 `blocks.size` 未变时不递增 `bodySyncVersion`）
  - 失真基线快照（7 张）：
    - `hipda/src/test/snapshots/images/editor_phone_w411_new_thread_light.png`
    - `hipda/src/test/snapshots/images/editor_phone_w411_new_thread_dark.png`
    - `hipda/src/test/snapshots/images/editor_twopane_w640_modal_light.png`
    - `hipda/src/test/snapshots/images/editor_threepane_w749_modal_light.png`
    - `hipda/src/test/snapshots/images/editor_threepane_w933_modal_dark.png`
    - `hipda/src/test/snapshots/images/editor_tablet_w1280_rightpane_light.png`
    - `hipda/src/test/snapshots/images/editor_tablet_w1280_rightpane_dark.png`
  - 空心 UI 断言：`hipda/src/test/java/net/jejer/hipda/feature/compose/FullEditorIntegrationTest.kt:424, 448-451`（`resizeAndProcessRecreation_preservesOpenEditorContentAndInlineImages`）
- **违反条款**：`_review_common.md` §3（存在改坏 UI 绑定仍绿的空心断言）与 §4（新增 Roborazzi PNG 逐张目视核验）。
- **具体触发路径与目视证据**：
  1. **状态失步机制**：当 `FullEditorScreen` 已挂载后，任何经 `FullEditorViewModel.onTextBlockChanged(...)` 发起的单段文本更新会写入 `uiState.value.body`，但因 `blocks.size` 未变（`bodySyncVersion` 不变）且 `FullEditorScreen.kt:329` 的 `LaunchedEffect(syncVersion, block.id)` 未观察 `block.text`，界面 `BasicTextField` 内部的 `TextFieldState` 保持旧值/空串，形成“ViewModel 持有新文本、界面显示旧文本/占位符，且用户下一击键即用旧 `TextFieldState` 反过来冲掉 ViewModel 文本”的双向失步。
  2. **目视截图铁证**：`FullEditorScreenshotTest.kt:77, 92, 361, 371` 明确为上述 7 张截图注入了发帖/回复正文（如 `"纯色编辑器表面对比度清晰，支持自动保存草稿与多图并发上传。"`、`"从快速回复面板无损展开至完整编辑器，纯色表面零玻璃开销。"`），但逐张目视核验发现**全部 7 张 PNG 的正文区均为空白，仅渲染灰色占位符 `正文` 或 `回复 linus_hi`**（唯独 `upload_inline_light` 因第二阶段调用 `onInsertUploadedImage` 递增了 `bodySyncVersion` 才把正文同步上屏）。
  3. **进程恢复测试漏检 UI**：`FullEditorIntegrationTest.kt:446-451` 在 `restorationTester.emulateSavedInstanceStateRestore()` 后仅断言了内存 `activeVm.uiState.value`，未在 Compose 语义树上断言恢复后的标题、正文文本与 `已插入图片` 节点实际可见。
- **最小修复建议**：
  1. 在 `FullEditorScreen.kt:329` 将同步副作用改为 `LaunchedEffect(syncVersion, block.id, block.text)`：当 `block.text != lastTxt || fieldState.text.toString() != block.text` 时同步更新 `lastTxt` / `lastSel` 并执行 `fieldState.edit`（用户在 `BasicTextField` 内正常打字或拼音组词时，`snapshotFlow` 已先行将 `lastTxt` 更新为 `fieldState.text`，条件为 `false`，零打断输入法 composing 区间）；
  2. 重录上述 7 张 `editor_*.png` 使正文真实渲染入基线（顺带建议在 `FullEditorScreenshotTest.editor_phone_w411_upload_inline_light` 为 `u1`/`u2` 在 `ShadowContentResolver` 注册本地测试样张流，使上传条与正文 `136dp` 内联图片块展示真实缩略图而非灰底空框）；
  3. 在 `FullEditorIntegrationTest.kt:448-451` 补充 `composeRule.onNodeWithText("跨形态保活标题测试").assertIsDisplayed()`、`onNodeWithText("旋转保活正文").assertIsDisplayed()` 与 `onNodeWithContentDescription("已插入图片").assertIsDisplayed()`。

---

## 二、卡面重点与通用清单逐项核验

| 检查维度 | 结果 | 核验依据（`file:line`） |
|---|---|---|
| **重点 1：`R-D6 S1/S2` 与 `R-D1`** | **S1 / R-D1 通过；S2 交接分支见 B1** | `PostModels.kt:207, 212, 219`：`ImageUploadInput.{ContentUri,LocalFile,Bytes}.original` 默认值全部改为 `false` 并同步更新 KDoc；`ImageCompressorTest.kt:331-380`（`+50/-0`）与 `ImageUploadRepositoryTest.kt:581-618`（`+35/-0`）纯新增默认压缩 + GIF/长图（`AR >= 3.0`）直传单测，**既有断言 0 修改、0 放宽**。<br>`R-D1`：`NextAppRoot.kt:382-384` 在 `TodayNew` 下传 `initialFid = null`；`FullEditorViewModel.kt:205-207, 256-271, 319, 629, 640, 650` 严格保证未选正整数 `fid > 0` 前不建 `DraftKey.newThread`、不发 `fetchPrePost`、禁选图上传、退出不写草稿、点发送内联提示 `请选择版块`。<br>`R-D6 S2`：完整编辑器内直接恢复草稿后点 `清空`（`:368-379`）或手动删空退出（`:393-395`）均显式调 `deleteDraft`；唯从 `QuickReplySheet` 手动删空旧草稿后交接进入完整编辑器的分支见 **B1**。 |
| **重点 2：`E5` 交接与多图上传** | **交接主路径与上传通过（删空交接见 B1）** | `ComposeEditorModule.kt:14-18` `@Binds @Singleton` 绑定 `FullEditorRequestBus -> FullEditorLauncher`；`QuickReplySheet` 自动显示 `展开全屏` 与 `插入图片`（`E7` `QuickReplyEmojiPanelRenderer` 保持未绑定隐藏 `表情` 按钮，且遵循 §11 无 `引用`/`粗体` 按钮）；非空文本、选区 `[selectionStart, selectionEnd]`、`quotePid`/`replyPid`/`quoteAuthor`/`quoteFloor`/`threadAuthor` 及 `openImagePickerImmediately` 0 丢失且不重弹草稿 Snackbar。<br>`EditorContentModel.kt:20-219` 严格维护文本块与图片块交替双射，粘贴含 `[attachimg]` 文本亦即时转块，UI 语义树 0 处 `[attachimg]` 源码文本；`ImageUploadStrip.kt:45-165` 完整覆盖压缩态、`Uploading(fraction)`、`正在上传 {current}/{total}`、单张失败 `上传失败，点击重试`、`×` 取消协程与底层 `Call`、未插图 `未插入` 角标与提交自动附尾，并有 `FullEditorUploadMockWebServerTest` 全链路验证。 |
| **重点 3：多栏 §4.2、玻璃预算与拆卡边界** | **通过** | `FullEditorHost.kt:49-65`：`411dp` 与矮屏 `< 480dp` 走 `FullScreen`（`NextAppRoot.kt:336` 挂起底层玻璃 `HiGlassMarker == 0`）；`640dp` 2 栏、物理铰链双栏、`749dp`（右栏 `411 < 560`）与 `933dp`（右栏 `448 < 560`）走跨全栏 `CenteredModal`（宽 `min(W - 48dp, 680dp)`）；`1280dp`（右栏 `680 ≥ 560`）走 `RightPane`（含从空右栏 `ForumPane` 点 `发新帖`）并传 `suppressDetailBottomGlass = true`，编辑器自身纯色 `0` `HiGlassMarker`，`MultiPaneGlassBudgetTest.kt:486-564` 实测守住 `HiGlassMarker ≤ 3`、`LayerBackdrop ≤ 2`、0 嵌套、`drawBackdrop ≤ 5`。「编辑楼层 / 还原原文 / 删除楼层」按派发前复核准确拆至 `E6b`，同帖跳楼拆至 `M7-G3`。 |
| **重点 4：文件边界、安全白名单与扫描门禁** | **通过** | `git diff --name-status bb5a870...3cbcb9c` 核实：`feature/routing/*`、`PostBodyRenderer.kt`、`feature/threaddetail/interact/*`、`ForumPane.kt`、`:emoji/*`、`src/debug/*`、构建脚本、`AndroidManifest.xml`、`.github/`、`MotionTokenScanTest` 与 `CopyTerminologyScanTest` 白名单全部 **0 改动**；生产代码 0 `testTag` / 0 测试专用语义键 / 0 手写 `Layout` 或 `Modifier.Node`；`isAllowedUploadContentUri`（`EditorContentModel.kt:222-226`）严格限非空 authority 的 `content://` URI 并拦截 `file://`/`javascript:`/`intent:`/`data:`/`http(s):`/`mailto:` 等 13 种反例。 |

---

## 三、负责人 6 项补充核查专答

### (1) `FullEditorRequestBus` 单例：重建/旋转恢复、引用泄漏与多次请求
1. **零 Activity / Composable 引用**：`FullEditorRequestBus`（`FullEditorHost.kt:70-80`）仅持有 `AtomicLong` 与 `MutableStateFlow<PendingFullEditorRequest?>`，载荷 `FullEditorRequest` / `FullEditorHandoff` 全部为不可变基本类型与 `String`，**0 处 `Activity`、`Context`、`View` 或 `@Composable` 闭包引用**。
2. **旋转 / 折叠缩放 / 进程重建恢复**：
   - 跨宽缩放与旋转：`rememberFullEditorHostController`（`FullEditorHost.kt:245-251`）用 `movableContentOf` 在 `FullScreen` / `CenteredModal` / `RightPane` 间无损搬移，旋转时 `ViewModelStore` 保留活跃 `FullEditorViewModel`（含在途上传协程）；
   - 进程被杀重建：每次 `uiState` 更新通过 `SideEffect`（`:196-198`）同步至 `rememberSaveable(stateSaver = FullEditorSavedStateSaver)`，冷启动新 `FullEditorViewModel` 后由 `LaunchedEffect` 调 `restoreSavedStateIfFresh`（`FullEditorViewModel.kt:231-241`）恢复打开态、模式、版块/分类、标题、正文与内联图片 ID 并重拉 `PrePostInfo`（唯测试需补齐 UI 可见性断言，见 **B2**）。
3. **多次请求行为**：
   - **不会重复弹出多层编辑器**：全局仅单实例 `FullEditorHostController` 与单一渲染出口，且 `QuickReplySheet.kt:284` 以 `SheetMotionPhase.Expanding` 阻断连点、`FullEditorRequestBus.consume(id)` 原子 `compareAndSet` 消费单次请求；
   - **但已打开态再次收到 `open()` 存在覆盖丢数据缺陷**：在 `1280dp` `RightPane` 展开态下，中栏「发新帖」仍可点击，再次触发 `open()` 会无条件清空在编内容或漏存前序会话草稿，已列为阻断项 **B1**。

### (2) 图片上传 `content://` URI 权限边界、临时文件清理与 `original = false` 全局生效
1. **只读调用方授权的 `content://` URI**：选图仅由 `ActivityResultContracts.GetMultipleContents()`（`FullEditorScreen.kt:70`）发起系统临时读授权；`onPickImages`（`FullEditorViewModel.kt:314`）先经 `isAllowedUploadContentUri` 强制校验 `scheme == "content"`、非空 `authority` 且无空白/控制字符，随后 `ImageCompressor.openUriInputStream`（`ImageCompressor.kt:269`）只通过 `context.contentResolver.openInputStream(uri)` 流式读取，`file://` 分支在编辑器链路不可达；应用 `AndroidManifest.xml` 的 `FileProvider` `exported="false"`，无越权面。
2. **临时文件在成功 / 失败 / 取消三条路径均清理**：
   - `ImageCompressor.createTempFile`（`:503-504`）仅构造路径对象不预建空文件；`ContentUri` 流式拷贝异常或取消由 `:261-264` `catch` 立即删除 `uri_*.tmp`；压缩失败/取消由 `:385-387` 删除 `cmp_*.jpg` 并由外层 `:206-208` `finally` 删除 `uri_*.tmp`；压缩成功立即在 `:206-208` 删除源拷贝 `uri_*.tmp`，再由 `DefaultImageUploadRepository.kt:306-310` `finally { prepared.cleanup() }` 在上传成功、失败或 `CancellationException`（`×` `removeUpload`）时删除最终上传临时文件；`FullEditorUploadMockWebServerTest.kt:278, 297` 实测验证 `workDir` 零残留（极窄协程切换窗口防御建议见下方 **S2**）。
3. **默认 `original = false` 对所有调用方生效**：全仓 `git grep ImageUploadInput` 核实三种子类 `ContentUri`、`LocalFile`、`Bytes`（`PostModels.kt:207, 212, 219`）默认值均为 `false`；唯一生产调用点 `FullEditorViewModel.kt:455` 使用默认构造 `ImageUploadInput.ContentUri(uri)`，自动命中压缩策略。

### (3) 发新帖 `R-D1`：从「今日新帖」进入必先选 `fid > 0`
- **核验通过**：`NextAppRoot.kt:383` 对 `ForumListSource.TodayNew` 传入 `NewThread(initialFid = null)`；`FullEditorViewModel` 在 `selectedFid == null`（或 `<= 0`）时：① `open`（`:205-207`）不调 `loadDraftForSession`、不调 `prefetchPrePostIfEligible`；② `onPickImages`（`:319`）与 `submit`（`:640`）直接拦截至 `FullEditorValidationError.SelectForum`（`请选择版块`）；③ `requestClose`（`:388, 629`）`currentDraftKey` 返回 `null` 绝不落盘非法草稿；④ 用户通过 `selectForum(fid)`（`:256-271`）选定正整数 `fid > 0` 后才首次生成 `DraftKey.newThread(fid)` 加载草稿并发起 `PrePostRequest.NewThread(fid)` 拉取分类。

### (4) 生产代码行数核算与可削减清单
1. **精确行数（`git diff --numstat bb5a870...3cbcb9c -- hipda/src/main`）**：
   - 新增 Kotlin 6 文件：`19 + 226 + 295 + 463 + 700 + 179 = 1,882` 行；
   - 修改 Kotlin 6 文件：`+111 / -28 = +83` 净增行；
   - 修改资源 XML 1 文件（`strings_compose.xml`）：`+26` 行；
   - **生产净增合计**：Kotlin **`+1,965` 行**，含 XML 共 **`+1,991` 行**（注：`_reports/E6.md` 统计为 `1,969` / `1,995` 行，有 4 行手工汇总误差），较拆卡后 `1.5×` 警戒线（`1,300 × 1.5 = 1,950` 行）略超 `+15` 行（含 XML 超 `+41` 行）。全部使用标准 Compose 组件，报告已附自简说明。
2. **修 `B1`/`B2` 时可顺手削减约 `85–110` 行的具体位置（压回 `≤ 1,900` 行以内）**：
   - **① 复用同包 `feature/compose` 重复逻辑（可省 `~35` 行）**：`FullEditorViewModel.kt:593-624, 635, 694-699` 中的 `resolveIsLoggedIn`、`nextCooldownTickDelayMs`、`safeReplyDraftKey`、`runCooldownTicker` 与 `enqueueDraftMutation` 与同包 `QuickReplyViewModel.kt:306-344, 347, 371-376` 高度重复，提取为包内 `internal` 共用函数即可双端复用；
   - **② 收敛 `FullEditorHost.kt` 重复 Hilt 查找与双状态订阅（可省 `~20` 行）**：`rememberHiltFullEditorRequestBus`（`FullEditorHost.kt:83-90`）与 `ComposeContracts.kt:135-140` 重复做 `ComposeSlotsEntryPoint` 查找；`:179-196` 对 `mountedVm` 与 `activeVm` 做了两次 `collectAsStateWithLifecycle()` 分支，直接令 `val vm = if (shouldMount) (injectedViewModel ?: mountedVm ?: viewModel()) else mountedVm` 单次收集即可；
   - **③ 抽取 `TextFieldState` 双向同步辅助函数（可省 `~15` 行，同时根治 `B2`）**：合并 `FullEditorScreen.kt:271-281`（标题框）与 `:325-341`（正文块）两处重复的 `lastSynced + LaunchedEffect + snapshotFlow` 模板；
   - **④ 为 `FullEditorMode` 提取 `Reply` 子接口（可省 `~15` 行）**：`ReplyThread` / `ReplyPost` / `QuotePost`（`FullEditorViewModel.kt:33-44`）共享 `Reply : FullEditorMode { val tid: String; val fid: Int; val threadAuthor: String? }`，可直接删去 `replyTidOrNull` 扩展并简化 `FullEditorSavedStateSaver` 与 `resolveEditorSubtitle`。

### (5) 快照零漂移与 20 张 PNG（8 张 `quick_reply_*.png` + 12 张 `editor_*.png`）逐张目视核验
1. **非授权快照零漂移**：`git diff --name-status bb5a870...3cbcb9c` 确认除预授权的 8 张已登录 `quick_reply_*.png`（第 9 张访客态 `quick_reply_phone_w411_guest_light.png` 保持 **0 字节变化**）与 12 张新增 `editor_*.png` 外，全仓其余所有快照 **0 漂移**。
2. **8 张更新的 `quick_reply_*.png`（像素差分包围盒 + 逐张 `read_file` 目视核验：全部通过）**：
   与 `bb5a870` 逐像素比对 RGB 差分包围盒并逐张目视：
   - `quick_reply_phone_w411_light.png` / `dark.png` / `quote_draft_light.png` / `cooldown_dark.png`（`822×1782px`）：`y < 1246px` 的帖子顶栏与楼层区 **0 像素变化**；变化完全限于底部面板因右上角新增 `展开全屏`（上下文行由 `28dp` 增高至 `48dp` 触控高）与左下角新增 `插入图片` 图标，E7 表情图标未出现，文字、光标、草稿横条与冷却数字 `28` 清晰无裁切；
   - `quick_reply_landscape_891x411_ime_compact_light.png` / `dark.png`（`1782×822px`）：RGB 差分严格落在 `(1398, 242)–(1614, 322)`（仅 `发送` 左侧新增 `插入图片`、`展开全屏` 两枚图标的 `108×40dp` 区域），单行输入框无截断溢出；
   - `quick_reply_threepane_w749_light.png`（`1498×1118px`，差分 `x >= 678px`）与 `quick_reply_threepane_w933_dark.png`（`1866×1392px`，差分 `x >= 972px`）：左栏与中栏 **0 像素变化**，仅右栏内快速回复面板新增 `展开全屏` 与 `插入图片` 图标。
3. **12 张新增 `editor_*.png` 逐张 `read_file` 目视核验（5 张通过，7 张命中阻断项 `B2`）**：

| 快照文件 | 场景与尺寸 | 目视核验结果 |
|---|---|---|
| `editor_phone_w411_new_thread_light.png` | `411×891` 浅色发新帖 | **未通过（`B2`）**：顶栏 `发新帖 \| 发送`、`Discovery` 版块/分类下拉框、标题与底栏仅 `插入图片` 排版规整，但**正文区丢失测试注入文本，仅显示灰字占位 `正文`** |
| `editor_phone_w411_new_thread_dark.png` | `411×891` 深色发新帖 | **未通过（`B2`）**：深色对比度与控件边框清晰，但**正文区丢失测试注入文本，仅显示灰字占位 `正文`** |
| `editor_phone_w411_reply_light.png` | `411×891` 浅色引用回复 | **通过**：顶栏 `引用回复` + 副行 `引用第 2 楼 · geek_reader`、多行正文与底栏 `插入图片` 清晰完整，无冗余 BBCode 按钮 |
| `editor_phone_w411_reply_dark.png` | `411×891` 深色引用回复 | **通过**：深色纯色表面层级与正文字色对比清晰，无裁切 |
| `editor_phone_w411_upload_inline_light.png` | `411×891` 多图上传全状态 + 内联图块 | **基本通过（附建议 `S1`）**：汇总 `正在上传 4/5`、已插图无角标、未插图 `未插入` 角标、进度环、压缩转圈、右缘失败重试卡片、右上角 `×` 及两段正文间的 `136dp` 内联图片块均完整且 **0 处 `[attachimg]` 文本**（建议注册样张流使缩略图非纯灰底） |
| `editor_phone_w411_draft_validation_light.png` | `411×891` 草稿恢复横条 + 标题内联校验 | **通过**：`已恢复上次草稿 \| 清空` 横条、`短标题` 红框与下方红字 `标题至少 6 个字`、已恢复正文层次分明无重叠 |
| `editor_phone_w411_cooldown_error_dark.png` | `411×891` 深色冷却倒计时 + 发送失败 | **通过**：右上禁用按钮显示滚动数字 `25`、原正文完整保留、下方 `发送失败 \| 重试` 对比清晰 |
| `editor_twopane_w640_modal_light.png` | `640×1024` 2 栏跨全栏居中模态 | **未通过（`B2`）**：`592dp` 宽、`20dp` 圆角居中卡片与半透明遮罩覆盖左侧 `64dp` 图标轨与内容栏正确，但**正文区丢失注入文本，仅显示 `正文`** |
| `editor_threepane_w749_modal_light.png` | `749×559` 紧凑三栏跨全栏居中模态 | **未通过（`B2`）**：右栏 `411 < 560` 正确升格为 `680dp` 跨三栏居中模态，但**正文区丢失注入文本，仅显示 `回复 linus_hi`** |
| `editor_threepane_w933_modal_dark.png` | `933×696` 完整三栏跨全栏居中模态 | **未通过（`B2`）**：右栏 `448 < 560` 深色 `680dp` 居中模态与背景三栏层次清晰，但**正文区丢失注入文本，仅显示 `回复 linus_hi`** |
| `editor_tablet_w1280_rightpane_light.png` | `1280×800` 平板右栏内展开（回复） | **未通过（`B2`）**：左栏 `220dp` + 中栏 `380dp` + 右栏 `680dp` 纯色编辑器精准落在右栏内且无底层工具条玻璃穿透，但**正文区丢失注入文本，仅显示 `回复 linus_hi`** |
| `editor_tablet_w1280_rightpane_dark.png` | `1280×800` 平板右栏内展开（发新帖） | **未通过（`B2`）**：空右栏从 `ForumPane` 点 `发新帖` 按方案 B 在右栏内展开正确，但**正文区丢失注入文本，仅显示 `正文`** |

### (6) 写操作与上传测试 100% 离线 `MockWebServer` 核查
- 全量检索本卡新增与修改文件确认 **0 处 `4d4y.com` 或任何外部域名**；
- `FullEditorUploadMockWebServerTest.kt`（`:120-299`）与 `ImageUploadRepositoryTest.kt`（`:581-618`）均将 `AppSettings.forumServer` 指向本地 `127.0.0.1` `MockWebServer`，使用内存 `InMemorySiteCookieStore`、`InMemoryDraftDao`、`ShadowContentResolver` 内存字节流与相对路径 `Location: viewthread.php?tid=8800123`，并在 `@After` 关闭 `MockWebServer` 与 `OkHttpClient` 线程池，**100% 离线、绝无访问真实站点或发起真实写操作路径**。

---

## 四、非阻断改进建议（Suggestions）

1. **S1（`editor_phone_w411_upload_inline_light` 注册样张预览流，`FullEditorScreenshotTest.kt:137-141`）**：当前测试传入的 `content://net.jejer.hipda.test/media/u1.jpg` 等 URI 未在 `ShadowContentResolver` 注册流，`AsyncImage` 加载失败呈纯灰底块；在修 `B2` 重录截图时建议顺手用 `TestImageFactory` 注册一张双色测试图，使上传条缩略图与正文内联缩略图块更直观展示裁切预览效果。
2. **S2（`1280dp` 右栏完整编辑器覆盖期间暂停右栏被遮挡详情顶栏玻璃采样，`MultiPaneReadingContainer.kt:454-455`）**：当前 `rightPaneOverlay != null` 时已通过 `suppressDetailBottomGlass = true` 卸载了右栏底部三段工具条（全屏 `HiGlassMarker = 2 ≤ 3`，已符合预算），但被右栏纯色 `FullEditorPlacementFrame` 完全遮挡的 `RenderRightSlot` 内 `ThreadDetailTopBar` 仍在离屏采样 `detailGlassState`；用 `CompositionLocalProvider(LocalHiGlassSuspended provides (LocalHiGlassSuspended.current || rightPaneOverlay != null))` 包裹 `RenderRightSlot` 可在平板右栏编辑期间进一步省掉这 1 路被完全遮挡的 GPU 离屏模糊。
3. **S3（归档时同步 `copy.md` 新增多图上传辅助文案，`strings_compose.xml:46-52`）**：新增的 7 条无障碍与上传状态文案（`移除图片`、`未插入`、`已插入图片`、`图片正在上传，请等待完成后再发送`、`有图片上传失败，请重试或移除后再发送`、`无法读取所选图片`、`需要完成实名认证后才能继续操作`）已 0 豁免通过 `CopyTerminologyScanTest` 且符合 `product-design.md` §8 句式规范；本卡未改共享文档 `docs/design/copy.md` 属合规并行隔离，建议负责人在合入归档时同步补入 `docs/design/copy.md`。


---

# 独立代码审查报告 R-E6（第 2 轮复审）

- **审查对象**：`origin/card/E6-full-editor` @ `adc777be6742bdb72cc9f4135694a52b65aa7cea`（前序 `8c8b030c1c1030fa4ebd8fdad4b68129fc7eae79`，基线 `origin/main` @ `bb5a87053b0f01f2c010756e334d8167ae8bfeca`）
- **审查结论**：**APPROVE**（0 Blocker，1 条极低优先级非阻塞观察 O1）
- **远端 CI 核验**：[`actions/runs/45802399764`](https://github.com/mailwmj/hipda-next/actions/runs/45802399764)（job `139848726917`），`headSha == adc777be6742bdb72cc9f4135694a52b65aa7cea`，`status == completed` / `conclusion == success`；全绿通过 `assembleDebug`、`assembleRelease`、`lintDebug`、`Verify Room schema consistency`、`:core-parser:test`（100/100）、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`（1460/1460，总计 **1560/1560** passed, 0 failed）、`finalizeTestRoborazziDebug` 与 `Verify APK outputs`。
- **合并冲突与工作区核验**：`git fetch origin` 后执行 `git merge-tree --write-tree origin/main adc777be6742bdb72cc9f4135694a52b65aa7cea` 退出码 `0`（tree `b8e22f98e3f7e2de4f6eb137590b37e3b662f1b8`，**零冲突**，快进祖先 `bb5a870`）；主工作区与 `E6` worktree 均保持干净只读。

---

## 一、首轮 Blocker（B1①②、B2）逐项复核

| 编号 | 首轮问题 | 修复定位与机制核查 | 结论 |
|---|---|---|---|
| **B1①** | 编辑器已打开时再次收到 `open(request)` 无条件 `resetSession` 清空在途草稿与上传 | `FullEditorViewModel.open`（`FullEditorViewModel.kt:178-218`）与 `isSameSessionTarget`（`:575-582`）区分两类场景：<br>1. **同目标重入**（相同 `draftKey`，或 `TodayNew` 已选/未选版块下再次点中栏发新帖 `NewThread(null)`）：完全幂等保留标题、正文、焦点块、选区与在途上传，仅递增 `focusRequestVersion` 触发当前活跃文本块 `FocusRequester.requestFocus()`（`FullEditorScreen.kt:264`），若 `openImagePickerImmediately=true` 则递增选图信号。<br>2. **跨目标切换**（如 `1280dp` 右栏从 `new:2` 切到 `new:7` 或从 `reply:1001` 切到 `new:2`）：先对旧会话执行 `persistOrCleanDraft(cur, emitSavedSnackbar = true)`（有内容串行 `saveDraft` 并发出 `DraftSaved` 带「放弃」动作；无内容但曾有持久化草稿则 `deleteDraft`），再 `cancelSessionJobs()`（`finally` 清理压缩临时文件）+ `resetSession(...)`，且新会话 `launchDraftRead` / `syncReplyDraftMeta` 均先 `draftMutationJob?.join()`（`:471, :500`）保证严格先后顺序；同时 `FullEditorHost.kt:170` 仅在 `Closed`/`*Submitted` 清空 `savedState`，跨会话 `DraftSaved` 不会误清刚切换的新会话 `savedState`。<br>单测 `FullEditorViewModelTest.kt:336-380` 与集成测试 `FullEditorIntegrationTest.kt:279-307` 已覆盖。 | **已彻底修复** |
| **B1②** | `QuickReplySheet` 删空恢复草稿后展开至 `FullEditor`，异步重读 DB 使已删旧草稿复活，且违反 `R-D6 S2` | `FullEditorHandoff`（`ComposeContracts.kt:84-85`）新增 `hadPersistedDraft` 与 `showDraftRestoredBanner`；`QuickReplyViewModel.onHandoffToFullEditor`（`QuickReplyViewModel.kt:218-235`）在删空文本展开时立即串行 `deleteDraft(key)` 并透传 `hadPersistedDraft=true, showDraftRestoredBanner=false`；`FullEditorViewModel.open(Reply)`（`FullEditorViewModel.kt:207-215`）以 `handoff.text` 为唯一正文权威源，绝不用 DB 覆盖正文，`syncReplyDraftMeta`（`:499-505`）仅在 `handoffHasContent == true` 时合并历史附件，且空内容关闭时再次按 `hadPersistedDraft` 兜底 `deleteDraft`（`:394`）。单测 `FullEditorViewModelTest.kt:383-420` 已覆盖。 | **已彻底修复** |
| **B2** | `rememberSyncedTextFieldState` 仅监听 `syncVersion` 导致外部状态更新不同步，7 张 `editor_*.png` 正文空白，`StateRestorationTester` 未断言 UI 节点可见 | 1. `FullEditorScreen.kt:122-148` 改为 `LaunchedEffect(syncVersion, text)` 同时核对 `verBumped \|\| text != lastTxt`：用户输入/IME 组合态先在 `snapshotFlow` 更新 `lastTxt` 不回写 `state.edit`（零破坏拼音预编辑），而外部程序化 `onTitleChanged`/`onTextBlockChanged`、异步草稿加载与状态恢复均可靠同步文本与选区。<br>2. `FullEditorIntegrationTest.kt:330-358` 在 `emulateSavedInstanceStateRestore()` 后显式断言标题与多段正文语义节点 `assertIsDisplayed()`。<br>3. 本轮已用 `read_file` 逐张目视核验全部 **12 张既有 `editor_*.png` + 1 张新增 `editor_phone_w411_draft_saved_snackbar_light.png`（共 13 张）**，正文全部清晰可见（详下表）。 | **已彻底修复** |

### 13 张 `editor_*.png` 逐张目视核验记录（含首轮 12 张 + V-E6 `probe9` 新增 1 张）

| # | 快照文件 | 目视核验结果 |
|---|---|---|
| 1 | `editor_phone_w411_new_thread_light.png` | 顶栏「发新帖 / 发送」、双下拉 `Discovery`、标题与正文（「纯色编辑器表面对比度清晰，支持自动保存草稿与多图并发上传。」）清晰可见，纯色表面对比度合规，底栏仅单选图按钮。 |
| 2 | `editor_phone_w411_new_thread_dark.png` | 深色纯色表面，标题与正文（「纯色表面零玻璃开销，底部仅保留插入图片工具项。」）清晰可见。 |
| 3 | `editor_phone_w411_reply_light.png` | 顶栏「引用回复 / 引用第 2 楼 · geek_reader」，正文（「承接自快速回复的完整回复内容，文本、光标与引用楼层全部零丢失保留。」）清晰可见（首轮空白已修复）。 |
| 4 | `editor_phone_w411_reply_dark.png` | 深色引用回复，正文（「深色模式引用第 2 楼回复视图，无冗余 BBCode 快捷按钮。」）清晰可见（首轮空白已修复）。 |
| 5 | `editor_phone_w411_upload_inline_light.png` | 汇总行「正在上传 4/5」（`HiLoadingSpinner`）+ 5 态缩略图卡（已插入、未插入角标、压缩中 `HiLoadingSpinner`、上传确定进度环、失败重试）+ 第一段文字 + 136dp 圆角内联图块 + 第二段文字全部清晰可见，零裸 `[attachimg]` 文本，圆角与遮罩已切设计令牌。 |
| 6 | `editor_phone_w411_draft_validation_light.png` | 「已恢复上次草稿 [清空]」提示条、「短标题」红框 +「标题至少 6 个字」校验文案、正文（「上次未写完的草稿内容已经自动恢复。」）清晰可见（首轮空白已修复）。 |
| 7 | `editor_phone_w411_cooldown_error_dark.png` | 顶栏发送胶囊倒计时 `25` 禁用态、正文（「提交失败后内容完整保留，原地展示失败提示与重试按钮。」）与原地「发送失败 / 重试」清晰可见（首轮空白已修复）。 |
| 8 | `editor_twopane_w640_modal_light.png` | `640dp` 居中模态卡片覆盖双栏背景遮罩，标题与正文清晰可见。 |
| 9 | `editor_threepane_w749_modal_light.png` | `749dp` 紧凑三栏居中模态卡片，顶栏「回复 / 回复 linus_hi」与正文（「从快速回复面板无损展开至完整编辑器，纯色表面零玻璃开销。」）清晰可见（首轮空白已修复）。 |
| 10 | `editor_threepane_w933_modal_dark.png` | `933dp` 深色三栏居中模态卡片，正文清晰可见（首轮空白已修复）。 |
| 11 | `editor_tablet_w1280_rightpane_light.png` | `1280dp` 左栏 `240dp` + 中栏 `360dp` 完全无遮挡，右栏 `680dp` 原地展开纯色回复编辑器，正文清晰可见（首轮空白已修复）。 |
| 12 | `editor_tablet_w1280_rightpane_dark.png` | `1280dp` 深色右栏原地发新帖编辑器，标题与正文清晰可见，左中两栏可交互。 |
| 13 | `editor_phone_w411_draft_saved_snackbar_light.png` | **新增（`probe9`）**：手机 `411dp` 帖子详情页底部三段液态玻璃工具条（`回复` / `第 1 / 3 页` / `☆ ⋮`）完整露出，「已保存草稿 [放弃]」Snackbar 稳居工具条正上方无任何遮挡。 |

> 另核对首轮 9 张 `quick_reply_*.png` 与其余全仓快照：`3cbcb9c...adc777b` 间零改动、零漂移。

---

## 二、S1–S3 落实与生产代码精简（`+2,380 -> +1,647` 行）等价性核查

1. **S1（设计系统令牌统一）**：`FullEditorScreen.kt` 与 `ImageUploadStrip.kt` 全面改用 `HiShapeTokens.ExtraLarge/Large/Medium/Small`、`MaterialTheme.colorScheme` 语义色（消除硬编码 `Color.White`/`Color.Black`）及支持 `LocalHiReducedMotion` 的 `HiLoadingSpinner`（`FullEditorScreen.kt:184`、`ImageUploadStrip.kt:44,73`）。
2. **S2（右栏纯色覆盖时挂起底层玻璃采样）**：`MultiPaneReadingContainer.kt:457` 在 `rightPaneOverlay != null` 时对底层 `RenderRightSlot` 提供 `LocalHiGlassSuspended = true`；`MultiPaneGlassBudgetTest.kt:555-570` 实测 `1280dp` 右栏打开编辑器时全屏 `HiGlassMarker` 由 `3 -> 1`、独立 `LayerBackdrop` 由 `2 -> 1`，关闭编辑器后自动恢复 `3 / 2`。
3. **S3（`copy.md` 同步）**：`docs/design/copy.md` §3.4 已补入 `未插入`（`+2 / -0`），`CopyTerminologyScanTest` 零豁免通过。
4. **生产代码精简（Kotlin 净增 `+1,768 / -121 = +1,647` 行，含 `strings_compose.xml` 共 `+1,673` 行，较首轮削减 `-733` 行 / `-30.8%`）行为等价性**：
   - 抽取至 `ComposeContracts.kt:167-205` 的 6 个纯函数/协程辅助（`safeReplyDraftKey`、`resolveComposeLoggedIn`、`nextCooldownTickDelayMs`、`runCooldownTickerLoop`、`enqueueSerialDraftMutation`、`mapPostSubmitFailureUiError`）逐行比对 `origin/main` 的 `QuickReplyViewModel`：冷却倒计时亚秒对齐、串行 `NonCancellable` Room 草稿写删、登录态双重判定完全等价；`mapPostSubmitFailureUiError` 还为 `QuickReplyViewModel` 补齐了 `RealNameBindRequired -> compose_error_real_name_required` 精确提示（原落入通用发送失败分支）。
   - `EditorContentModel`、`FullEditorViewModel`、`FullEditorScreen`、`FullEditorHost`、`ImageUploadStrip` 的精简均为提取内部局部子组件（`FormDropdownSelector`、`RemoveCornerButton`、`ThumbnailPreviewImage`、`updateSession`）与紧凑列表 `Saver`（17 项固定槽位），未删减任何状态分支、并发上限（`Semaphore(2)`）、临时文件 `finally` 清理或无障碍语义（48dp 触控热区、`LiveRegionMode.Polite`、字号倍率钳制）。

---

## 三、V-E6 首轮 3 项缺陷修法与 E3/E4 影响专项评估

1. **`probe2b`（多段图文进程重建光标块归位，`EditorContentModel.kt:26-32`、`FullEditorViewModel.kt:46, 224-258`）**：
   - `EditorBodyDocument` 严格保持 `Text(0), Image(0), Text(1), ..., Text(N)` 交替双射不变量（即使连续插图也保留空文本块占位，`parse` 与 `serialize` 的 `Text` 块数量恒为 `N + 1`）。`FullEditorSavedState` 新增 `activeTextBlockOrdinal`（索引 `16`），恢复时通过 `selectTextBlockByOrdinal` 精确还原跨图块焦点与段内光标偏移，修法严密。
2. **`probe4b`（进程重建后右栏编辑器与模态抽屉返回顺序，及 `NextAppRoot` 首帧 `initialPaneLayout` 对 E3/E4 的影响）**：
   - **返回栈机制核查**：① `FullEditorHost.kt:145-147` 用 `remember(vm) { vm.restoreSavedStateIfFresh(savedState); true }` 在重建首帧组合期同步恢复会话状态；② `FullEditorHost.kt:200` 将 `key(sessionId) { BackHandler(...) }` 随 `renderInPlacement` 挂载——`RightPane` 态位于 `MultiPaneReadingContainer` 右栏槽内（晚于 `MultiPaneReadingContainer` 自身 `BackHandler`，**早于** `HiAdaptiveShell.kt:437` 的 `HiNavigationSidebarModalOverlay` `BackHandler`），因此在 `749–839dp` 收起中栏使右栏 `>= 560dp` 且展开模态侧栏时，无论是交互态还是进程重建态，`OnBackPressedDispatcher` LIFO 顺序恒为 `LoginOverlay > ImageViewer > 模态侧栏抽屉 > 右栏编辑器 > 多栏阅读导航`；而在 `FullScreen`/`CenteredModal` 态挂载于 `NextAppRoot.kt:508`（晚于 `HiAdaptiveShell`、早于 `ImageViewer`/`LoginOverlay`），覆盖全窗时优先响应返回。
   - **对 E3（多栏容器/铰链双栏/分隔条拖拽）与 E4（帖子阅读位置与 prepend 锚点恢复）的影响核查**：**零负面影响**。
     - `MultiPaneReadingContainer`（E3）与 `ThreadDetailPane`（E4）接收的是 `HiAdaptiveShell` 内部 `ShellContentScope` 实时计算的 `paneLayout`（含 `FoldingFeature` 铰链态）与 `MultiPaneSlotHolder` 的 `SaveableStateHolder`，**完全不读取** `NextAppRoot` 的 `initialPaneLayout`、`latestPaneLayout` 或 `latestMiddleWidthDp`（`NextAppRoot.kt:376-429`）。
     - `latestPaneLayout` 与 `latestMiddleWidthDp` 仅服务于 `FullEditorHost` 形态判定与 `MultiPaneSnackbarHost` 区域计算，且首帧过后即由 `NextAppRoot.kt:371-374` 与 `MultiPaneReadingContainer.kt:417, 495, 552` 的 `SideEffect` 实时同步。修复前首帧硬编码 `411×891` + `0.dp` 会导致大屏重建首帧误判为 `FullScreen`（短暂触发一帧 `LocalHiGlassSuspended = true` 及跨槽重挂载），修复后首帧直接按真实窗口尺寸与 `resolveEffectivePaneSplit(persistedSplit, anchors)` 算出一致布局，消除了首帧跳变。
3. **`probe9`（详情页底部三段玻璃工具条避让，`MultiPaneSnackbarHost.kt:75-105`、`NextAppRoot.kt:538-550`）**：
   - 当 `selectedThread != null` 且未处于全屏看图、右栏编辑器或全屏编辑器时，向 `MultiPaneSnackbarHost` 传入 `detailBottomClearanceDp = navBarsBottomDp + ThreadBottomToolbarHeight + ThreadBottomToolbarBottomGap`（`56.dp + navBars`），仅对 `PaneSnackbarTarget.DetailPane` 抬高至 `navBars + 64.dp`，不影响 `ListPane`（中栏发新帖草稿提示仍贴底 `16.dp` 或避让单栏底胶囊）。不仅修复了「已保存草稿 / 放弃」遮挡「回复」按钮，也一并保护了详情页内其他 `DetailPane` 提示条不遮挡底栏。

---

## 四、非阻塞观察（可留后续顺手微调，无需重开本卡）

- **O1（`NextAppRoot.kt:538` 详情子页下钻时的 Snackbar 抬高余量）**：当 `appState.selectedThread != null` 且用户从帖子内点击头像下钻至 `UserProfile`（`appState.detailDrilldownStack.isNotEmpty()`）时，当前顶层显示的是无底部三段工具条的用户主页，`detailToolbarClearanceDp` 仍保持 `56.dp + navBarsBottomDp`，若此时触发 `DetailPane` Snackbar 会比底边多留 `48.dp` 空白（不遮挡任何控件，视觉无害；若下钻为 `SmsConversation` 则恰好也有底部输入栏）。后续如需极致贴边可按顶层非 `Thread` 槽位微调。
