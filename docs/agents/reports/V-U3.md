# V-U3 独立探针验证报告

## 结论：`PASS`

- **验证对象**：任务卡 `U3`（`card/U3-compose-polish`，目标 Commit `fdd73ace1ae49db9e08d351698837a732e906dc8`，基线 `1377a04`）
- **独立验证分支 / Worktree**：`verify/U3-probes` @ `db3045937a6a7758e38bf4f45158744e4ebf1ed7`（保留在 `/home/mir/Documents/hipda-next.wt/V-U3`）
- **探针测试文件**：`hipda/src/test/java/net/jejer/hipda/feature/compose/U3ComposePolishVerificationProbeTest.kt`（26 个独立离线探针，未改动主目录、生产代码及原实现单测）
- **全量 Android CI**：[`https://github.com/mailwmj/hipda-next/actions/runs/46050319847`](https://github.com/mailwmj/hipda-next/actions/runs/46050319847) — `success`（`Build, Lint & Unit Tests` 16m1s，`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿）
- **本地串行验证**：`U3ComposePolishVerificationProbeTest`（26/26）+ `CopyTerminologyScanTest`（3/3）+ `MotionTokenScanTest`（7/7）+ `:hipda:assembleDebug` 共 **36/36 PASSED**，结束后已 `./gradlew --stop`。

---

## 1. 既有快照零漂移核查

`git diff --name-status 1377a04...fdd73ace1ae49db9e08d351698837a732e906dc8` 确认 U3 仅改动：
- **生产文件（4 处）**：`FullEditorScreen.kt`、`FullEditorViewModel.kt`、`QuickReplySheet.kt`、`strings_compose.xml`
- **授权测试文件（7 处）**：`FullEditorIntegrationTest.kt`、`FullEditorViewModelTest.kt`、`QuickReplyLayoutMatrixTest.kt`、`QuickReplySheetTest.kt`、`QuickReplyTestSupport.kt`、`EmojiPanelBehaviorTest.kt`、`EditAndDeletePostIntegrationTest.kt`
- **授权快照（27 张）**：`editor_*`（14 张）、`emoji_panel_*`（5 张）、`quick_reply_*`（8 张）；其余全量快照 **0 漂移**。
- `git diff --name-status fdd73ace1ae49db9e08d351698837a732e906dc8...HEAD` 在 `verify/U3-probes` 仅新增 `A hipda/src/test/java/net/jejer/hipda/feature/compose/U3ComposePolishVerificationProbeTest.kt`。

---

## 2. 逐条独立探针验证结果（26/26 PASS）

### 重点 1：`QuickReplySheet`（`ReplyThread` 隐藏上下文行后）`32dp` 把手拖拽与瞬态同帧保护

| 探针用例 | 验证内容与观察结果 | 结果 |
|---|---|---|
| `probe1a_replyThread_dragHandle_slowDragDown_tracksFrameAndDismissesAndSavesDraft` | `32dp` 把手慢拖逐帧跟手下移越 `72dp` 阈值后关闭，静默保存草稿并触发 `DraftSaved` | PASS |
| `probe1b_replyThread_dragHandle_fastFlickDown_triggersDismissBelowDistanceThreshold` | 仅下移 `28dp`（`< 72dp`）但快甩速度越 `720dp/s` 仍触发收起 | PASS |
| `probe1c_replyThread_dragHandle_upwardDragPastThreshold_handsOffTextAndCursorToFullEditor` | 仅凭 `32dp` 把手上拉 `68dp` 越 `56dp` 阈值，无损交接 `"HelloWorld"` 与选区 `(5, 8)` 且不弹 `DraftSaved` | PASS |
| `probe1c2_replyThread_dragHandle_fastFlickUp_triggersHandoffWithCursor` | 上甩 `26dp`（`< 56dp`）快甩速度触发全屏展开交接，保留文本与光标 `(2, 2)` | PASS |
| `probe1d_replyThread_dragHandle_subThresholdRelease_snapsBackBothDirections` | 下拉 `32dp` / 上拉 `24dp` 未达阈值松手均弹回原位，不触发关闭/展开 | PASS |
| `probe1e_replyThread_dragHandle_sameFrameDownMoveUp_and_midFlightInterruptDoNotLock` | `mainClock.autoAdvance = false` 下同帧 `down→move→up` 及回弹中途打断再越阈值拖关均不卡死 | PASS |
| `probe1e2_replyThread_dragHandle_sameFrameDuringEntering_doesNotLock` | 入场动画未结束时同帧 `down→move→up` 越阈值拖拽仍正常完成收起 | PASS |

### 重点 2：`QuickReplySheet` 常规态去重、矮屏/弹键盘（`< 180dp`、`< 120dp`）与按钮尺寸

| 探针用例 | 验证内容与观察结果 | 结果 |
|---|---|---|
| `probe2a_quickReply_replyThread_bottomExpand_noContext_and_32dpCapsuleWith48dpTouch` | 已登录 `ReplyThread` 隐藏顶部上下文行，`展开全屏` 位于底栏图片/表情右侧；输入后 placeholder `"回复 张三"` 消失；输入框无实线 `outlineVariant` 边框；`发送` 触控区 `>= 48dp`（点击 `top+3dp` 边缘可触发），像素实测实心胶囊高 `32dp` | PASS |
| `probe2a2_quickReply_quotePost_singleLineContext_nonDuplicatedPlaceholder_and_guestMode` | `QuotePost` 保留单行 `"引用第 12 楼 · 李四"` + 右侧 `展开全屏`，输入框用通用占位 `"输入回复内容"`，输入后上下文保留、占位消失 | PASS |
| `probe2a3_quickReply_guestMode_retainsContextAndLoginPrompt` | 未登录态 `ReplyThread` 保留上下文行与 `"登录后即可操作"` / `"登录"` | PASS |
| `probe2b_quickReply_shortScreenAndIme_below180dpAndBelow120dp_layoutAndExpandWork` | `w411×411dp` 注入 `ime=251dp`（净高 `160dp < 180dp`）与 `ime=311dp`（净高 `100dp < 120dp`）：隐藏 `32dp` 把手、`shouldLiftUnderlyingList=false`、紧凑行零溢出，极矮屏输入并点 `展开全屏` 正常交接 | PASS |
| `probe2b2_quickReply_quotePostShort160dp_and_narrowShort100dp_noOverlapOrClip` | `w360×411dp` 下 `QuotePost` 在 `160dp` 净高保留单行上下文与 `"输入回复内容"`；降到 `100dp` 让出上下文行后 placeholder 自动回退为 `"引用第 8 楼 · 矮屏引用作者"`，不丢对象且无裁切 | PASS |

### 重点 3：`FullEditorScreen` 去重、发送钮解耦与校验链、模态 `×` 语义

| 探针用例 | 验证内容与观察结果 | 结果 |
|---|---|---|
| `probe3a_fullEditor_replyThreadAndQuote_singleLineTitle_noVisualBodyPlaceholder_keepsA11y` | `ReplyThread` 顶栏单行 `"回复 Alice"`，正文视觉无重复占位但保留无障碍描述并可输入 | PASS |
| `probe3a2_fullEditor_quoteAndNewThreadAndEdit_titlesAndPlaceholders` | `QuotePost` 顶栏单行 `"引用第 7 楼 · Carol"`，无视觉 `"正文"` 占位且无障碍描述完整 | PASS |
| `probe3a3_fullEditor_newThread_keepsBodyPlaceholder` | `NewThread` 顶栏 `"发新帖"`，正文保留 `"正文"` 占位 | PASS |
| `probe3a4_fullEditor_editPost_singleLineEditTitle_and_keepsBodyPlaceholder` | `EditPost` 顶栏 `"编辑"`，正文保留 `"正文"` 占位 | PASS |
| `probe3b_fullEditor_replySendButtonDecoupling_emptyVsText_32dpPill_48dpTouch` | 空正文与纯空白时 `发送` 呈无底透明 + `primary` 文字且触控区 `>= 48dp`（点边缘仍触发 `"内容至少 6 个字"`）；输入 `"ab"` 后切换 `32dp` 实心 `primary` 胶囊且仍触发 `"内容至少 6 个字"` | PASS |
| `probe3b2_fullEditor_uploadedImageSwitchesToFilled32dpPill` | 正文为空但上传图完成后 `hasSendableContent == true`，切换实心 `32dp` 胶囊 | PASS |
| `probe3b3_fullEditor_newThreadStepwiseValidationChain` | `NewThread` 逐级触发 `"请选择版块"` → `"请选择分类"` → `"请输入标题"` → `"标题至少 6 个字"` → `"内容至少 6 个字"`，输入有效正文后切换 `32dp` 实心胶囊并成功提交 | PASS |
| `probe3b4_fullEditor_disabledStates_guestAndCooldown_keep48dpTouchAndDisableSend` | 冷却态展示 `"9 秒后可发送"`、禁用态且保持 `>= 48dp` 触控高度 | PASS |
| `probe3c_fullEditor_modalCloseIconAndBorder_and_silentDraftSave` | 居中模态 `isModalCard=true` 左上角为 `×`（`"关闭"`，无 `"返回"`），像素采样确认 `0.5dp outlineVariant@0.5f` 描边存在；点 `×` 静默保存草稿并发出 `DraftSaved`/`Closed` | PASS |
| `probe3c2_fullEditor_fullscreenKeepsBackIconAndSavesDraft` | 全屏态 `isModalCard=false` 保持 `←`（`"返回"`），点击静默保存草稿并退出 | PASS |

### 重点 4：`EmojiPanelSlotBox` 键盘/表情等高互换（`<= 0.5dp`）与返回键优先级

| 探针用例 | 验证内容与观察结果 | 结果 |
|---|---|---|
| `probe4a_emojiSlot_quickReplyReplyThread_zeroBottomDrift_and_backPriority` | 改版 `QuickReplySheet`（`ReplyThread` 无上下文行）在 `ime=280dp / nav=24dp` 与逐帧收起/升起 `IME ↔ 表情` 互换中底缘与顶缘位移全程 `<= 0.5dp`；首按返回先关表情面板，次按关闭快速回复并存草稿 | PASS |
| `probe4b_emojiSlot_fullEditorCenteredModal_zeroBottomDrift_and_backPriority` | `FullEditorScreen`（`isModalCard=true`，`w749×559dp`）在 `ime=220dp` 下双向切换 `IME ↔ 表情` 底部工具栏底缘位移 `<= 0.5dp`；首按返回先关表情，次按静默存草稿退出 | PASS |
| `probe4b2_emojiSlot_fullEditorHostControllerCenteredModal_backPriority` | 通过 `FullEditorHostController` 真实 `CenteredModal` 宿主持有 `registerBackHandler=false` 链路验证：首按返回关表情，次按关弹窗并存草稿 | PASS |
| `probe4b3_emojiSlot_fullEditorTallTabletModal_zeroBottomDrift` | 大屏 `w800×1280dp` 居中模态下 `ime=280dp → 0dp` 切表情底缘位移 `<= 0.5dp` | PASS |

---

## 3. 合规检查

- **离线合规**：全套探针使用纯内存替身，无任何对 `4d4y.com` 的网络请求。
- **扫描守卫**：`CopyTerminologyScanTest`（3/3）与 `MotionTokenScanTest`（7/7）全绿。
- **工作区纪律**：主目录 `/home/mir/Documents/hipda-next` 干净未改；`V-U3` worktree 与 `verify/U3-probes` 分支已完整保留。
