# 验证报告 V-E6b：`card/E6b-edit-delete-post` 独立真实链路集成与边界探针验证

## 1. 总结结论

- **验证结论**：**PASS（全部探针 5/5 与补充检查项 (a)–(e)、全量单测 1576/1576、Roborazzi 截图校验、Lint 与 Release/Debug APK 构建均通过，既有快照 0 意外漂移）**
- **被测对象**：`origin/card/E6b-edit-delete-post` @ [`7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`](https://github.com/mailwmj/hipda-next/commit/7ac4c829cc7b86eb882364fb9c39ba7a22bb8390)（基准已包含最新 `origin/main` @ [`ed361c255b975874fa0eb7a4a60f431400c2af55`](https://github.com/mailwmj/hipda-next/commit/ed361c255b975874fa0eb7a4a60f431400c2af55)，`git merge --no-edit origin/main` 为 `Already up to date`）
- **实现方 CI**：[`Android CI #45813940387`](https://github.com/mailwmj/hipda-next/actions/runs/45813940387)（`status=completed`, `conclusion=success`, `Build, Lint & Unit Tests` `success`, `Emulator Smoke Test` `skipped`）
- **独立探针分支 / Commit**：`verify/E6b-probes` @ [`2ffd15b52a168d262c9594c3e1d15e9343bb13cd`](https://github.com/mailwmj/hipda-next/commit/2ffd15b52a168d262c9594c3e1d15e9343bb13cd)（worktree `/home/mir/Documents/hipda-next.wt/V-E6b` 保留，主仓 `/home/mir/Documents/hipda-next` 0 修改）
- **独立探针 Android CI**：[`Android CI #45820596823`](https://github.com/mailwmj/hipda-next/actions/runs/45820596823)（`workflow_dispatch` on `verify/E6b-probes`，`status=completed`, `conclusion=success`；`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots` 全过，JUnit 汇总 **`TOTAL: 1576 tests, 1576 passed, 0 failed, 0 skipped`**，`Emulator Smoke Test` `skipped`）
- **独立与离线原则**：探针在阅读任何 E6b 实现方单测及 `_reports/E6b.md` 之前基于 `_cards/V-E6b.md`、`_cards/E6b.md` 及设计规范独立编写；全程使用离线 `MockWebServer`、内存 `Room`、`InMemorySiteCookieStore` 与真实仓库/VM/UI 组合，严禁且未访问 `4d4y.com`。

---

## 2. 独立探针结果总表

新增探针文件（仅此 1 个新增测试文件，0 修改被测生产代码、既有单测或快照）：
- [VE6bRealPathProbesTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E6b/hipda/src/test/java/net/jejer/hipda/feature/compose/VE6bRealPathProbesTest.kt)

| 探针方法 | 覆盖范围（对应 V-E6b 与补充 (a)–(e)） | 本地 & CI 结果 | 实测断言与关键证据 |
|---|---|---|---|
| `probe1_and_b_realPathEditRestoreUndo_cooldownExempt_noTail_postHelperFields_inPlaceRefreshNoJump` | 探针 1 + 补充 (b)：真实 `NextAppRoot + ThreadDetailPane + FullEditorScreen + DefaultPostRepository + DefaultImageUploadRepository + ImageCompressor + Room + MockWebServer` 编辑与 `还原原文` 全链路 | **PASSED** (`VE6bRealPathProbesTest`) | 长按 4 楼点 `编辑` 经 `GET post.php?action=edit` 拉取原文（含已有图 `101`/`102`），UI 仅渲染 `已插入图片` 缩略条（`[attachimg]` 源码数为 `0`），初开隐藏 `还原原文`；修改正文后 `还原原文` 立即显示；点击 `还原原文` 恢复原文并弹 `已还原原文 \| 撤销`；点 `撤销` 恢复编辑态；修改正文 + 删已有图 `101` + 上传新图 `201`（保留未删图 `102`）；30s 发帖冷却活跃期（`getRemainingCooldownSeconds() > 0`、`effectiveCooldownSeconds == 0`）仍成功提交且无小尾巴；提交后删除 `edit:10014` 草稿，原地刷新 4 楼后相邻 3 楼距顶偏差 `<= 1dp` |
| `probe2_and_e_editDraftRD6S2_threePaneGlassBudget_andBackPriorityHierarchy` | 探针 2 + 补充 (e)：749dp 紧凑三栏下 `edit:<pid>` 草稿与 `R-D6 S2`、返回优先级与玻璃预算 | **PASSED** (`VE6bRealPathProbesTest`) | 749dp 三栏与折叠中栏 `RightPane` 投放及 `FloorDeleteConfirmDialog` 均满足 `HiGlassMarker <= 3`；未改退出 0 提示、0 `edit:20031` 写入、不误出 `PositionStack`；修改退出静默保存 `edit:20031` 并弹 `已保存草稿 \| 放弃`；重开恢复显示 `已恢复上次草稿 \| 清空`，分别验证点 `清空` 退出与「手动删空至空白」退出均显式 `deleteDraft` 且再开永不复活（`0` 残留、`0` `已恢复上次草稿`）；返回优先级实测符合 `LoginOverlay > DeleteDialog/EditEditor > PositionStack > MultiPaneBackStack` 以及 `SidebarModalDrawer > RightPane EditEditor > PositionStack > MultiPaneBackStack`（与 E6 一致） |
| `probe3_and_d_deletePostThreePaths_middleFloorNoJump_firstFloorReturnsToList` | 探针 3 + 补充 (d)：真实 `NextAppRoot + ThreadDetailPane + ThreadDetailViewModel + DefaultPostRepository + Room + MockWebServer` 删除三路径 | **PASSED** (`VE6bRealPathProbesTest`) | ① 取消路径：核对逐字文案 `删除这条回复？` / `删除后无法恢复。` / `取消` / `删除`，点 `取消` 发起 `0` 次请求；② 失败路径：返回 Discuz `<div class="alert_info">` 错误后保留 5 楼并原地展示错误文案；③ 成功删除 5 楼（`pid=10015`）：移除 5 楼、相邻 4 楼距顶偏差 `<= 1dp`（阅读位置不跳）、无 `撤销` Snackbar、清理 `edit:10015` 草稿；推进虚拟时钟越过删除冷却后删除 1 楼（`pid=10011`, 302 重定向 `forumdisplay.php` 对应 `threadDeleted=true`）：自动退回论坛列表（`selectedThread == null`）并触发论坛列表刷新（`refreshCalls` 递增） |
| `probeA_nonOwnFloorMenuHidesEditDelete_andUpdatesImmediatelyOnAccountSwitch` | 补充 (a)：非本人楼层菜单隐藏与切换账号即时响应 | **PASSED** (`VE6bRealPathProbesTest`) | 当前为 `UserA` 时长按 3 楼（第三方 `UserC`）与 2 楼（`UserB`）长按菜单均有 `引用回复`/`复制` 且无 `编辑`/`删除`（各 `0` 节点）；在 1 楼（`UserA`）长按菜单**正处于打开状态**下调用真实 `DefaultAuthRepository.switchAccount(UserB)`（不重载帖子），已打开菜单即时移除 `编辑` 与 `删除`；重开 2 楼（`UserB`）长按菜单即时显示 `编辑` 与 `删除`；随后调用 `logout()`，已打开菜单即时隐藏 `编辑` 与 `删除` |
| `probeC_editInProgressProcessRecreation_preservesContentCursorAndRestoreOriginalState` | 补充 (c)：编辑中进程重建（`StateRestorationTester.emulateSavedInstanceStateRestore()`）状态保留 | **PASSED** (`VE6bRealPathProbesTest`) | ① 未改动状态下重建后 `还原原文` 保持隐藏（`0` 节点）；② 修改 1 楼标题与图文混排中图片后的第 2 文本段并将光标定位到偏移 `3`，触发进程重建获得全新 `FullEditorViewModel` 实例后：`isOpen=true`、修改后标题/两段文本/已有图 `101`、`activeTextBlockOrdinal()==1`、`selection=(3,3)`、`canRestoreOriginal=true` 全部无损保留；重建后直接 `performTextInput("Z")` 精确落在第 2 段偏移 `3` 处；点 `还原原文` 仍能还原且点 `撤销` 复原；直接点 `发送` 使用重建后新拉取 `formhash`（`fh_recreate`）完成 `EditPost` 提交并携带首楼 `subject` 与 `typeid=56` |
| `CopyTerminologyScanTest` | 全仓字符串资源术语扫描与禁止词检查 | **PASSED** (3/3) | 本地与 CI 均 `3 tests, 3 passed, 0 failed, 0 skipped` |
| `MotionTokenScanTest` | 主代码与调试代码动效 Token 扫描 | **PASSED** (7/7) | 本地与 CI 均 `7 tests, 7 passed, 0 failed, 0 skipped` |

---

## 3. 补充 (b) POST 请求字段与旧版 `PostHelper` 一致性核查

对照旧版 [PostHelper.java#L145-L171](file:///home/mir/Documents/hipda-next.wt/V-E6b/hipda/src/main/java/net/jejer/hipda/async/PostHelper.java#L145-L171) 与新版 [DefaultPostRepository.kt#L803-L867](file:///home/mir/Documents/hipda-next.wt/V-E6b/hipda/src/main/java/net/jejer/hipda/data/post/DefaultPostRepository.kt#L803-L867)，并由 `MockWebServer` 在 `probe1_and_b`（非首楼 4 楼改正文 + 删图 `101` + 留图 `102` + 新传 `201`）及 `probeC`（首楼 1 楼改标题/正文）实测捕获：

| 请求属性 / 表单键 | 旧版 `PostHelper` 规范 | `MockWebServer` 实测捕获与源码核对 |
|---|---|---|
| 编辑提交 URL | `post.php?action=edit&extra=&editsubmit=yes&mod=&fid=...&tid=...&pid=...&page=...` | 实测 `/forum/post.php?action=edit&extra=&editsubmit=yes&mod=&fid=2&tid=1001&pid=10014&page=1` |
| `formhash` | `PrePost` 隐藏域 `formhash` | `probe1_and_b` 捕获 `fh_edit_p1`；`probeC` 重建后捕获 `fh_recreate` |
| `posttime` | 毫秒级数字时间戳字符串 | 捕获为纯数字时间戳字符串 |
| `wysiwyg` / `usesig` | 固定 `"0"` / `"1"` | 捕获 `wysiwyg=["0"]`、`usesig=["1"]` |
| `message` | 编辑后正文 + 保留附件 `[attachimg]<id>[/attachimg]` + 新图 `[attachimg]<id>[/attachimg]` | 捕获正文含 `"编辑提交后的第 4 楼新正文"`、`"[attachimg]102[/attachimg]"`（未删已有图保留）、`"[attachimg]201[/attachimg]"`（新上传图），不含 `"[attachimg]101[/attachimg]"` 且不含签名小尾巴 |
| `attachnew[][description]` | 仅新上传附件 ID | 捕获 `["201"]`（未删已有图 `102` 不重复入 `attachnew`） |
| `attachdel[]` | 仅本次删除的已有附件 ID | 捕获 `["101"]` |
| `delete` | 编辑请求不传；删除请求固定 `"1"` | `probe1_and_b` 验证 `fieldMap["delete"] == null`；`probe3_and_d` 验证删除请求捕获 `delete=["1"]` |
| `subject` / `typeid` | 仅在 `subject` 非空（首楼/有标题楼层）时提交 | `probe1_and_b`（4 楼）精确键集合 `{"formhash","posttime","wysiwyg","usesig","message","attachnew[][description]","attachdel[]"}`；`probeC`（1 楼）捕获 `subject="进程重建后保留标题"`、`typeid="56"` |

---

## 4. 探针 4：变更边界与快照零漂移核查

核对 `git diff --name-status origin/main...7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`：
- **允许目录范围内的源码/资源改动（9 个文件）**：
  - `M hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`
  - `M hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`
  - `A hipda/src/main/java/net/jejer/hipda/feature/threaddetail/interact/FloorDeleteConfirmDialog.kt`
  - `M hipda/src/main/res/values/strings_compose.xml`
  - `M hipda/src/main/res/values/strings_thread_detail.xml`
- **新增实现单测（3 个文件）**：`EditAndDeletePostScreenshotTest.kt`、`EditPostMockWebServerAndDraftTest.kt`、`EditAndDeletePostIntegrationTest.kt`。
- **Roborazzi 快照变更（仅 4 张新增 `A`，0 张 `M`/`D`/`R`）**：
  - `A hipda/src/test/snapshots/images/editor_edit_delete_dialog_w411_light.png`
  - `A hipda/src/test/snapshots/images/editor_edit_delete_dialog_w749_dark.png`
  - `A hipda/src/test/snapshots/images/editor_edit_phone_w411_restore_light.png`
  - `A hipda/src/test/snapshots/images/editor_edit_tablet_w1280_rightpane_dark.png`
  - 既有非新增快照在本地 diff 与 `Android CI #45820596823`（`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true`）中均呈 **0 漂移**。

---

## 5. 本地验证命令与环境留存

- **本地串行构建与测试**：遵循 `docs/agents/DISPATCH.md` §2（`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew ... -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4; ./gradlew --stop`）执行：
  - `./gradlew :hipda:testDebugUnitTest --tests net.jejer.hipda.feature.compose.VE6bRealPathProbesTest --tests net.jejer.hipda.feature.common.CopyTerminologyScanTest --tests net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest :hipda:assembleDebug` → `BUILD SUCCESSFUL`（15 tests, 15 passed, 0 failed, 0 skipped）。
- **验证分支与目录保留**：已保留分支 `verify/E6b-probes`（`origin/verify/E6b-probes`）及工作树 `/home/mir/Documents/hipda-next.wt/V-E6b`，主仓库 `/home/mir/Documents/hipda-next` 保持洁净未改动。
