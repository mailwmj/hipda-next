# 验证报告 V-E6：`card/E6-full-editor` 独立集成与边界探针验证

## 1. 总结结论

- **验证结论**：**FAIL（主链路与补充主测 9/9 探针通过，补充边界诊断探针发现 2 个 E6 阻塞/待修实现缺陷 + 1 个宿主 Snackbar 遮挡联动缺陷）**
- **被测对象**：`origin/card/E6-full-editor` @ [`3cbcb9c694782740597e2d154500da80065b5c05`](https://github.com/mailwmj/hipda-next/commit/3cbcb9c694782740597e2d154500da80065b5c05)（基于 `e3b344389b2a0888047c84eac4c0c4c285e5d524` 合并最新 `origin/main` @ `bb5a87053b0f01f2c010756e334d8167ae8bfeca`，`git merge origin/main` 已是最新，无待合并冲突）
- **实现方 CI**：[`Android CI #45790084493`](https://github.com/mailwmj/hipda-next/actions/runs/45790084493)（`status=completed`, `conclusion=success`, `event=push`, `headSha=3cbcb9c694782740597e2d154500da80065b5c05`）
- **独立探针分支 / Commit**：`verify/E6-probes` @ `c0ffea21690135c09b2d9860b901c2217e0f618f`（worktree `/home/mir/Documents/hipda-next.wt/V-E6` 保留，主仓 `/home/mir/Documents/hipda-next` 全程 0 改动）
- **独立探针 Android CI**：[`Android CI #45797385707`](https://github.com/mailwmj/hipda-next/actions/runs/45797385707)（`workflow_dispatch` on `verify/E6-probes`，`Build and lint`、`Verify Room schema consistency`、`Roborazzi verify` 全部通过；全量单测 `1565 tests, 1563 passed, 2 failed, 0 skipped` —— 唯一 2 项失败即下方诊断探针 `probe2b` 与 `probe4b`）
- **执行优先级依据**：遵循 `/home/mir/Documents/hipda-next.wt/_cards/E6.md` 卡末「负责人派发前复核」裁定，编辑/删除楼层移至后续 `E6b`、`R-E16 S2` 移至 `M7-G3`，授权 `NextAppRoot.kt` 与 `ThreadDetailPane.kt` 做最小接线；独立探针先于阅读 E6 交付单测与 `_reports/E6.md` 完成设计并在全离线替身 / `MockWebServer` 运行（零访问 `4d4y.com`）。

---

## 2. 独立探针结果总表

探针文件仅新增至 `verify/E6-probes`（未改任何生产代码、既有单测或快照）：
- [FullEditorProbeIntegrationTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/test/java/net/jejer/hipda/feature/compose/FullEditorProbeIntegrationTest.kt)
- [FullEditorProbeUploadAndBoundaryTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/test/java/net/jejer/hipda/feature/compose/FullEditorProbeUploadAndBoundaryTest.kt)

| 探针 | 覆盖范围（对应 V-E6 与补充 (a)–(d)） | 本地 & CI 结果 | 关键实测断言与证据 |
|---|---|---|---|
| `probe1` | 探针 1：真实 `NextAppRoot + ThreadDetailPane + QuickReplySheet` 中文 + 中间光标 `(2,2)` + 引用楼层点 `展开全屏` 与 `插入图片` | **PASSED** | `甲乙丙丁` 光标 `(2,2)` 插入 `中` 后为 `甲乙中丙丁`，引用 `1001_2`（2 楼/楼层作者2）保留；退出 `已保存草稿` 写入 `reply:1001`；点 `插入图片` 零丢失交接并自动触发 `image/*` 选图；交接不触发多余 `saveDraft` 与重复 `已保存草稿` |
| `probe2` | 补充 (a)：快速回复 → 展开全屏 → 多轮旋转（`411`→`891×411`→`749×559`→`1280×800`→`411`）→ `StateRestorationTester` 全新 `ViewModelStore` 进程重建 | **PASSED** | `createdHostVms`、`createdEditorVms` 均换入新实例后，`isOpen=true`、`body="甲乙丙丁戊"`、`selection=(2,2)`、`targetPid="1001_2"` 完整保留，直接聚焦输入得 `"甲乙X丙丁戊"` |
| `probe2b` | 补充 (a-extra) 诊断：正文插入内联图片块形成 `t0 + img + t2`，光标留在 `t2` 偏移 `1` 后经 `StateRestorationTester` 进程重建 | **FAILED（真实缺陷 #1）** | `ComparisonFailure: 多段图文重建后光标应留在图片后的文本块 (t2)，实际 active=t0 expected:<t[2]> but was:<t[0]>`；根因见第 3.1 节 |
| `probe3` | 探针 2 + 补充 (b)：`411`/`640`/`749`/`933`/`1280` 多栏投放尺寸与玻璃预算 | **PASSED** | `411dp` 全屏宽 `411dp`（0 玻璃 / 0 Backdrop）；`640dp` 居中模态 `592dp`；`749dp` 与 `933dp` 全三栏（右栏 `<560dp`）居中模态宽 `680dp` 居中；`1280dp` 右栏展开（左缘 `>=600dp`、宽 `<=680dp`、中栏列表可见）下 `HiGlassMarker <= 3`、`LayerBackdrop <= 2`、`nestedLayerBackdropCount == 0`、`drawBackdrop <= 5` |
| `probe4` | 补充 (b)：实时交互下返回优先级顺序 | **PASSED** | `1280dp` 右栏实测 `Login > FullEditor(存草稿) > QuickReply > PositionStack(页3->页1)`；`749dp` 收起中栏右栏展开实测 `Login > SidebarModalDrawer > FullEditor > PositionStack` |
| `probe4b` | 补充 (b-extra) 诊断：`749×559` 收起中栏右栏展开编辑器且同时打开 240dp 模态抽屉，经 `StateRestorationTester` 重建后按返回 | **FAILED（真实缺陷 #2）** | `AssertionError: 按返回应优先关闭抽屉，但抽屉仍存在(1)，底层编辑器 open=false`；重建后底层右栏编辑器被误关闭而顶层抽屉滞留，违反 `抽屉 > 编辑器`；根因见第 3.2 节 |
| `probe5` | 补充 (d) + 探针 3：`今日新帖` 未选版块拦发（R-D1）、分类/标题/正文校验、冷却与 `R-D6 S2` 草稿清理 | **PASSED** | 从 `今日新帖` 点 `发新帖` 时 `selectedFid=null` 且 `prePostRequests` 为空，填满标题正文点发送拦发提示 `请选择版块`；选 `Buy & Sell(fid=6)` 后触发 `PrePostRequest.NewThread(6)`，未选分类拦发提示 `请选择分类`；标题 `<6` / 正文 `<6` 校验与游客登录提示准确；验证删空退出（`reply:1001`）与点 `清空`（`new:2`）均显式调用 `deleteDraft` 且重开不复活；12s 冷却禁用发送 |
| `probe6` | 探针 3 + 补充 (c)：`ImageCompressor + DefaultImageUploadRepository + MockWebServer` 并发 5 图上传、压缩、取消、失败重试与临时文件清零 | **PASSED** | 默认压缩上传字节显著小于原图 PNG；3 成功 + 1 失败 + 1 上传中显示 `正在上传 5/5`（4 终态 + 第 5 张在途）；取消在途图后放行服务端，被取消图不复活且 `uploadSummaryProgress` 清零；失败图点击 `上传失败，点击重试` 补传成功；`MockWebServer` 精确收到 `6` 次 `swfupload` 请求（5 首轮 + 1 重试），工作目录临时文件 `0` 残留 |
| `probe7` | 探针 3：`DefaultPostRepository + Room DraftDao + MockWebServer` 点击缩略图插块（UI `0` `[attachimg]`）、未插图附尾与清草稿 | **PASSED** | 全语义树扫描含 `[attachimg]` 为空；点第一张插图 + 第二张保留 `未插入`，提交时 `message` 中包含行内 `[attachimg]<id1>[/attachimg]` 且末尾自动附加 `[attachimg]<id2>[/attachimg]`，`attachnew` 含两张图，提交成功后 Room 草稿清除 |
| `probe8` | 探针 3 / `R-D6 S1`：3 种 `ImageUploadInput` 默认 `original == false` 与压缩/直传矩阵 | **PASSED** | `ContentUri`/`LocalFile`/`Bytes` 默认 `original == false`；普通大图压缩为 JPEG；超长图（AR 4.0）与合规 GIF 在 `original=false` 下仍直传（`wasCompressed=false`）且临时文件 `0` 残留 |
| `probe9` | 诊断：`FullEditorRequestBus` + `NextAppRoot` 单例消费，以及退出存草稿 Snackbar 遮挡坐标测量 | **PASSED（记录联动缺陷 #3）** | `bus.open(handoff)` 立即唤醒 `FullEditorScreen` 并消费 `pendingRequest=null`；退出在 `ThreadDetailPane` 弹出 `已保存草稿` 时实测 `SNACKBAR_BOUNDS=Rect(64,1684,69,1719)` 与 `REPLY_BOUNDS=Rect(32,1670,208,1766)` 重叠（`OVERLAPS_REPLY=true`） |
| `CopyTerminologyScanTest` & `MotionTokenScanTest` | 全仓文案术语扫描与动效 Token 扫描 | **PASSED** | `3/3` 与 `7/7` 全过 |

---

## 3. 实测发现的缺陷详情

### 3.1 缺陷 #1（阻断 / 补充 (a) 边界）：含已插入图片块的多段正文在进程重建后光标跳回第一段（`probe2b`）
- **复现路径**：在完整编辑器中输入 `"前段后段"`，于偏移 `2` 插入上传图片生成 `Text("前段", id=t0) + Image + Text("后段", id=t2)`，将光标置于 `t2` 偏移 `1`（`"后|段"`），触发 `StateRestorationTester.emulateSavedInstanceStateRestore()` 重建 `FullEditorViewModel`。
- **实际结果**：`restoredVm.uiState.value.body.activeTextBlockId` 从 `t2` 退回 `t0`（光标落回图片前的 `"前|段"`）。
- **代码根因**：
  - [FullEditorViewModel.kt#L244-L249](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt#L244-L249) `exportSavedState()` 直接保存块内相对偏移 `s.body.activeSelectionStart` / `activeSelectionEnd`（例如 `t2` 内的偏移 `1`），未保存 `activeTextBlockId` 也未换算为 `serializedBody` 全局偏移。
  - [FullEditorViewModel.kt#L234](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt#L234) `restoreSavedStateIfFresh()` 调用 `EditorBodyDocument.parse(saved.serializedBody, saved.selectionStart, saved.selectionEnd)`，而 [EditorContentModel.kt#L169-L173](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/EditorContentModel.kt#L169-L173) 将传入的 `selectionStart` 视作整篇序列化字符串的全局索引，因此偏移 `1` 命中首段 `0..match.range.first` 落入 `t0`。

### 3.2 缺陷 #2（阻断 / 补充 (b) 返回优先级）：抽屉与右栏编辑器同时恢复时返回优先级逆转为「编辑器 > 抽屉」（`probe4b`）
- **复现路径**：`749×559dp` 下打开帖子并收起中栏（右栏 `685dp >= 560dp` → `FullEditorPlacement.RightPane`），依次打开完整编辑器与 240dp `SidebarModalDrawer`，随后执行 `StateRestorationTester.emulateSavedInstanceStateRestore()`（保留 `ViewModelStore` 模拟配置变更/Activity 重建）后按一次系统返回。
- **实际结果**：`SidebarModalDrawer` 仍停留在屏幕最上层（节点数 `1`），而底层 `RightPane` 完整编辑器被误关闭（`restoredEditorVm.uiState.value.isOpen == false`）。
- **代码根因**：
  - 正常交互时（`probe4`），后打开的 [HiNavigationSidebar.kt#L285-L289](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/ui/shell/HiNavigationSidebar.kt#L285-L289) `BackHandler` 后入 `OnBackPressedDispatcher` 栈顶，抽屉优先关闭。
  - 但在重建时按组合树自上而下挂载：先执行 [NextAppRoot.kt#L337-L447](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L337-L447) 内的 `HiAdaptiveShell` 注册抽屉 `BackHandler`，再执行 [NextAppRoot.kt#L487-L490](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L487-L490) `key(editorHost.sessionId) { BackHandler(enabled = true, onBack = editorHost.handleBack) }` 将编辑器 `BackHandler` 压在抽屉之上，导致返回优先级逆转。

### 3.3 联动缺陷 #3（非独立 E6 代码修改造成，影响退出编辑器后的详请页操作）：`已保存草稿` Snackbar 遮挡底部「回复」按钮（`probe9`）
- **实测证据**：`probe9`（`411×891dp`）从详情页退出完整编辑器保存草稿，在 `PaneSnackbarTarget.DetailPane` 弹出 10 秒 `SnackbarDuration.Long`（`已保存草稿 | 放弃`），实测坐标 `Rect(64.0, 1684.0, 69.0, 1719.0)` 与 `ThreadBottomToolbar` 的 `回复` 按钮 `Rect(32.0, 1670.0, 208.0, 1766.0)` 重叠（`OVERLAPS_REPLY=true`），需等待 11 秒自动消失后才可稳定点击「回复」。
- **代码位置**：[NextAppRoot.kt#L517-L527](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L517-L527) 传给 [MultiPaneSnackbarHost.kt#L62-L100](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneSnackbarHost.kt#L62-L100) 的留白未计入详情页底部三段工具条高度，当 `isSidebar` 为真或 `bottomPillVisible == false` 时仅留 `defaultBottom`（`navBarsBottomDp + 16.dp`）。

---

## 4. 探针 4：边界、体量与快照零意外漂移核查

### 4.1 边界核查（`git diff origin/main...3cbcb9c694782740597e2d154500da80065b5c05`）
- **禁止修改路径核查**：`feature/routing/*`、`PostBodyRenderer.kt`、`feature/threaddetail/interact/*`、`emoji/`、`hipda/src/debug/`、`hipda/src/main/AndroidManifest.xml`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`.github/` 均 **0 改动**（已通过限定路径 `git diff --name-only` 实测为空）。
- **已修改生产文件（13 个文件，含 26 行 XML 总计 `+2019 / -28`，净增 `+1991` 行；Kotlin 净增 `+1965` 行）**：
  - 新增：`di/ComposeEditorModule.kt` (+19)、`feature/compose/EditorContentModel.kt` (+226)、`feature/compose/FullEditorHost.kt` (+295)、`feature/compose/FullEditorScreen.kt` (+463)、`feature/compose/FullEditorViewModel.kt` (+700)、`feature/compose/ImageUploadStrip.kt` (+179)
  - 修改：`data/post/PostModels.kt` (+8/-6)、`feature/compose/ComposeContracts.kt` (+19/0)、`feature/compose/QuickReplySheet.kt` (+1/-1，`ComposeVectorIcons` 改为 `internal`)、`feature/app/MultiPaneReadingContainer.kt` (+9/-2)、`feature/app/NextAppRoot.kt` (+44/-2，按复核授权做最小右栏/居中模态与 `BackHandler` 接线)、`feature/threaddetail/ThreadDetailPane.kt` (+30/-17，仅按复核授权接线 `onExpandToFullEditor` 与 `LocalFullEditorReplyResults`)、`res/values/strings_compose.xml` (+26/0)
  - 说明：Kotlin 净增 `+1965` 行略高于 `1,950`（1.5× 上限 `1,300`），代码结构已按组件复用，未见明显冗余重复。

### 4.2 快照漂移与像素比对核查
- **其它快照**：除下述 E6 授权项外，全仓既有快照 **0 漂移**；CI `#45797385707` `Verify Roborazzi screenshots` 与 `Check for Roborazzi diff images`（`has_diffs=false`）全通过。
- **新增 `editor_*.png`（12 张）**：`editor_phone_w411_{new_thread_light,new_thread_dark,reply_light,reply_dark,upload_inline_light,draft_validation_light,cooldown_error_dark}.png`、`editor_twopane_w640_modal_light.png`、`editor_threepane_w749_modal_light.png`、`editor_threepane_w933_modal_dark.png`、`editor_tablet_w1280_rightpane_{light,dark}.png`。
- **预授权变更 `quick_reply_*.png`（共 9 张：8 张因绑定 `FullEditorLauncher` 显示「展开全屏」与「插入图片」图标更新，1 张访客态 0 变化）**：
  用 Pillow 逐像素对比 `origin/main` 与 `3cbcb9c`，变化严格限于快速回复工具条区（0 意外扩散）：
  - `quick_reply_phone_w411_guest_light.png`：**0 变化**（访客态无工具栏）
  - `quick_reply_landscape_891x411_ime_compact_dark.png` (`1782×822`)：变化 `1.07%`（`rgb_bbox=(1398, 242, 1614, 322)`）
  - `quick_reply_landscape_891x411_ime_compact_light.png` (`1782×822`)：变化 `1.07%`（`rgb_bbox=(1398, 242, 1614, 322)`）
  - `quick_reply_phone_w411_cooldown_dark.png` (`822×1782`)：变化 `3.76%`（`rgb_bbox=(0, 1346, 822, 1782)`）
  - `quick_reply_phone_w411_dark.png` (`822×1782`)：变化 `3.76%`（`rgb_bbox=(0, 1346, 822, 1782)`）
  - `quick_reply_phone_w411_light.png` (`822×1782`)：变化 `4.40%`（`rgb_bbox=(0, 1358, 822, 1782)`）
  - `quick_reply_phone_w411_quote_draft_light.png` (`822×1782`)：变化 `4.45%`（`rgb_bbox=(0, 1246, 822, 1782)`）
  - `quick_reply_threepane_w749_light.png` (`1498×1118`)：变化 `3.78%`（`rgb_bbox=(678, 582, 1498, 1118)`）
  - `quick_reply_threepane_w933_dark.png` (`1866×1392`)：变化 `2.28%`（`rgb_bbox=(972, 855, 1866, 1392)`）

---

## 5. CI 与本地执行记录

- **本地串行测试**：
  `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.feature.compose.FullEditorProbe*" --tests "*CopyTerminologyScanTest" --tests "*MotionTokenScanTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4 --console=plain`（随后 `./gradlew --stop`）
  → 共 `21 tests`：`FullEditorProbeUploadAndBoundaryTest` `3/3 PASSED`、`FullEditorProbeIntegrationTest` `6/8 PASSED`（`probe2b` / `probe4b` 失败）、`CopyTerminologyScanTest` `3/3 PASSED`、`MotionTokenScanTest` `7/7 PASSED`。
- **GitHub Actions CI（`verify/E6-probes`）**：[`runs/45797385707`](https://github.com/mailwmj/hipda-next/actions/runs/45797385707)
  → `assembleDebug` + `assembleRelease` + `lintDebug` + Room Schema + Roborazzi Verify 全过；全量单测 `1565 tests, 1563 passed, 2 failed, 0 errors, 0 skipped`（`:hipda:testDebugUnitTest` `1465` 项仅 `probe2b` 与 `probe4b` 失败，`:core-parser:test` `100/100` 全过）。


---

## 6. 第 2 轮复验结论与证据（PASS）

- **第 2 轮判定**：**PASS（第 1 轮全部 11 项原始探针 + 新增 `(e)/(f)/(g)` 共 14 项探针全过，E3/E4 与扫描回归及 Roborazzi/Lint/Build 全绿）**
- **复验对象**：`origin/card/E6-full-editor` @ [`adc777be6742bdb72cc9f4135694a52b65aa7cea`](https://github.com/mailwmj/hipda-next/commit/adc777be6742bdb72cc9f4135694a52b65aa7cea)（历经 `3cbcb9c -> 8c8b030 -> adc777b`，已含最新 `origin/main` @ `bb5a87053b0f01f2c010756e334d8167ae8bfeca`，`git merge origin/main` 已是最新）
- **实现方 CI**：[`Android CI #45802399764`](https://github.com/mailwmj/hipda-next/actions/runs/45802399764)（`status=completed`, `conclusion=success`, `event=push`, `headSha=adc777be6742bdb72cc9f4135694a52b65aa7cea`）
- **探针分支 / Commit**：`verify/E6-probes` @ `c8e513fed707092474e4a52357644b4600a2d1a3`（在 `/home/mir/Documents/hipda-next.wt/V-E6` 变基至 `adc777b` 并合并 `origin/main`，保留全部原始探针并追加 `(e)-(g)`，生产代码/既有单测/快照 0 改动）
- **探针 Android CI**：[`Android CI #45806470683`](https://github.com/mailwmj/hipda-next/actions/runs/45806470683)（`completed`, `success`，耗时 `12m9s`；`Build and lint`、`Verify Room schema consistency`、`Verify APK outputs`、`Roborazzi verify` 0 diff；全量单测 `1574 tests, 1574 passed, 0 failed, 0 errors, 0 skipped`）

### 6.1 第 1 轮阻断项复测（重点 `probe2b`、`probe4b`、`probe9`）

| 探针 | 第 1 轮结果 | 第 2 轮结果（`adc777b`） | 验证证据与修复核对 |
|---|---|---|---|
| `probe2b` | FAILED | **PASSED** | [EditorContentModel.kt](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/EditorContentModel.kt#L26-L32) 新增 `activeTextBlockOrdinal()` / `selectTextBlockByOrdinal()`，配合 `FullEditorSavedState` 持久化块序号；实测插入图片后于 `t2` 偏移 `1` 经 `StateRestorationTester` 重建后仍精准停留在 `lastTextBlockId (t2)` 且偏移 `1` |
| `probe4b` | FAILED | **PASSED** | [FullEditorHost.kt#L144-L147](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt#L144-L147) 首帧同步 `restoreSavedStateIfFresh` 并按投放位置在 [FullEditorHost.kt#L198-L202](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt#L198-L202) 挂载 `BackHandler`；`749×559` 收起列表右栏编辑器 + 模态抽屉重建后按返回先关抽屉、右栏编辑器保持打开 |
| `probe9` | PASSED（记录重叠） | **PASSED（硬断言过）** | [NextAppRoot.kt#L538-L550](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L538-L550) 向 [MultiPaneSnackbarHost.kt#L67-L105](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneSnackbarHost.kt#L67-L105) 传入 `detailBottomClearanceDp`；实测 `PROBE9_SNACKBAR_BOUNDS=Rect(64.0, 1588.0, 69.0, 1623.0)`，与 `REPLY_BOUNDS=Rect(32.0, 1670.0, 208.0, 1766.0)` 零重叠（`OVERLAPS_REPLY=false`），Snackbar 悬停期间不 `advanceTimeBy` 直接点击底部「回复」立刻展开快速回复 |

### 6.2 第 2 轮新增探针 `(e)`–`(g)` 与保留探针明细

| 探针 / 套件 | 结果 | 实测核心路径 |
|---|---|---|
| `probe1`–`probe8`（加 §6.1 共 11 项原始探针全集） | **PASSED（8/8；原 11/11）** | 快速回复→全屏/插图交接、多轮旋转、单段重建、`411`/`640`/`749`/`933`/`1280` 与玻璃预算、实时 5 级返回栈、`今日新帖` R-D1、MockWebServer 5 图取消重试清零、`DefaultPostRepository` 插块附尾、`R-D6 S1/S2` 全过 |
| `probe10`（补充 `(e)`） | **PASSED** | `1280×800dp` 在 Discovery (`fid=2`) 右栏输入标题正文并把焦点停留在标题栏，再点同版块「发新帖」：同 `sessionId` 保活、标题正文 0 丢失、未冗余 `saveDraft` 与未弹 `已保存草稿`、`focusRequestVersion` 递增且焦点切回正文；左栏切 Buy & Sell (`fid=6`) 再点「发新帖」：先保存 `new:2` 草稿并弹 `已保存草稿` 后切换为 `selectedFid=6` 空编辑器；切回 Discovery 重开准确恢复 `new:2` 草稿 |
| `probe11`（补充 `(f)`） | **PASSED** | `reply:1001` 恢复旧草稿后在快速回复输入框手动删空（不点「清空」）再点「展开全屏」：完整编辑器正文为空（不回灌旧草稿）、不显示恢复横条、`hadPersistedDraft=true` 透传；直接按返回关闭后显式调用 `deleteDraft(reply:1001)`、无多余 `已保存草稿` 提示，重开不再复活 |
| `probe12`（补充 `(g)`） | **PASSED** | 验证 [NextAppRoot.kt#L296-L316](file:///home/mir/Documents/hipda-next.wt/V-E6/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L296-L316) 首帧初始化 `initialPaneLayout`/`latestMiddleWidthDp` 后：`1280dp` 首帧一次成形左/中/右三栏；预置 `ReadingPositionEntity(tid="1001", page=2, pid="1001_10", offsetPx=40)` 只水合 1 次并精准滚到中部；`PositionStack` 出栈回第 2 页后连续切换 `749×559`、`900×800 HingeTwoPane`、`411×891`、`749×559` 均 `0` 冗余 `loadPage`；`StateRestorationTester` 进程重建后列表/详情滚动位置均 `> 0f`，且右栏退出 `已保存草稿` Snackbar 严格落在列表右侧并避让底部工具条 |
| 本地 E3/E4 与扫描全套回归（`101/101 PASS`） | **PASSED** | `FullEditorProbeIntegrationTest` (11/11)、`FullEditorProbeUploadAndBoundaryTest` (3/3)、`MultiPaneStateMigrationTest` (10/10)、`MultiPaneBackStackTest` (7/7)、`MultiPaneDividerGestureTest` (9/9)、`MultiPaneGlassBudgetTest` (10/10)、`FeaturePaneSlotsTest` (9/9)、`NextAppTracerBulletTest` (9/9)、`ContinuousPagingAnchorTest` (15/15)、`ReadingPositionAndStackTest` (8/8)、`CopyTerminologyScanTest` (3/3)、`MotionTokenScanTest` (7/7) |

### 6.3 第 2 轮范围、体量与快照复核（`origin/main...adc777b`）

- **禁改路径**：`feature/routing/*`、`PostBodyRenderer.kt`、`feature/threaddetail/interact/*`、`emoji/`、`hipda/src/debug/`、`AndroidManifest.xml`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`.github/` **0 改动**；授权调整范围外新增修改 `MultiPaneSnackbarHost.kt`（`+13/-6`，仅用于修复 `probe9` 底部工具条避让）与 `docs/design/copy.md`（`+2`，补齐测试覆盖字段/提示登记）以及 `QuickReplyViewModel.kt`（抽共用 helper 并修复 `(f)` 透传）。
- **生产代码体量**：15 个变更/新增生产文件（含 `strings_compose.xml` `+26`）总计 `+1794 / -121`，**Kotlin 净增 `+1647` 行**（已压降至 ≤ 1,850 行以下且结构紧凑）。
- **快照**：第 2 轮新增 `editor_phone_w411_draft_saved_snackbar_light.png`（实测「已保存草稿 / 放弃」悬于详情底栏上方 8dp），并更新 8 张 `editor_*.png` 丰富真实图片/文本示例；全仓其余快照（含 `quick_reply_*.png` 仅保留第 1 轮工具栏授权变更）**0 漂移**，CI `#45806470683` `Verify Roborazzi screenshots` 全过。
