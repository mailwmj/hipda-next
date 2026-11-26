【任务卡 E6b：编辑已发表楼层（含还原原文）与楼层菜单删除自己楼层（高风险 R+V，收口 edit 草稿 R-D6 S2）】
分支：`card/E6b-edit-delete-post`（基于含 `E6` 与 `M7-G3` 的 `origin/main` @ `adc777b`）
> **前置派发门禁与文件边界（硬约束）**：本卡**串行依赖 `E6`（完整编辑器本体）与 `M7-G3`（楼层交互与详情接线）全部合入 `main`**，必须基于含两者的最新 `origin/main`（`adc777b`）开工。
> **边界约束**：仅修改 `feature/threaddetail/interact/*`（`FloorContextMenuOverlay` 的 `onEdit`/`onDelete` 与删除确认对话框）、`feature/threaddetail/ThreadDetailPane.kt` / `ThreadDetailViewModel.kt` 最小接线、以及复用 `E6` 在 `feature/compose/*` 的完整编辑器（仅补齐编辑模式、`还原原文`、`deletedAttachments` 与 `DraftKey.edit(pid)` 最小扩展）。**严禁触碰 `feature/routing/*`、`feature/forum/*`、`feature/messages/*`、`feature/settings/*`、`feature/profile/*`、`feature/mine/*`、`feature/search/*`、`:emoji/*`、`data/*` 与共享构建/清单文件。**
> **写操作红线**：编辑与删除均为论坛真实写操作，**严禁对真实站点 `4d4y.com` 发起请求，所有网络测试只用 `MockWebServer`**。

风险分级：**高风险（走 `R + V`）** — 涉及完整编辑器与帖子详情跨组件状态同步、草稿键 `edit:<pid>`（含 `R-D6 S2`）、不可逆删除写操作与列表阅读位置保持（`V` 必须含合入 `main` 后的真实链路集成探针）。
预计生产代码净增：约 320–480 行（1.5× 警戒 720 行）

【目标】
从原 `E6` 拆分独立交付两项楼层写操作闭环（复用 `E6` 完整编辑器与多栏投放能力）：
1. **编辑已发表楼层与「还原原文」**（接通 `FloorContextMenuOverlay.onEdit`、`PrePostRequest.EditPost`、`DraftKey.edit(pid)`、顶栏条件显示 `还原原文` 及统一令牌撤销、`PostSubmitRequest.EditPost`、支持 `deletedAttachments` 与编辑态多图增删）；
2. **楼层菜单「删除」自己的楼层**（接通 `FloorContextMenuOverlay.onDelete`、逐字取自 `copy.md` 的不可撤销二次确认框、`PostSubmitRequest.DeletePost`、成功后移除楼层保阅读位/首楼删帖退回列表、失败原地提示）。

【参考与基线接口（以含 `E6` 与 `M7-G3` 的 `adc777b` 源码为准）】
- 产品与文案规范：`docs/design/product-design.md` §4.2、§7.2（`edit:<pid>` 与 `还原原文`）、§7.3（楼层长按菜单）、§7.4（多图上传与提交）、§8、`docs/design/copy.md`（§导航与常用操作 `:30, 35, 38`、§加载与进行中 `:69, 73`、§错误与校验 `:88, 89`、§Snackbar 与确认框 `:137-139, 145`）
- 数据层（已合入 `main`，只读消费）：`data/post/PostRepository.kt`、`PostModels.kt`（`PrePostRequest.EditPost(fid, tid, pid, page)`、`PostSubmitRequest.EditPost`、`PostSubmitRequest.DeletePost`、`PostSubmitResult.Success(tid, threadDeleted, redirectedPage)`）、`data/local/Draft.kt`（`DraftKey.edit(pid)`）、`core-parser/.../PrePostModels.kt`（`PrePostInfo`: `subject`/`text`/`typeId`/`typeOptions`/`existingImages`/`isDeletable`）
- 界面基座（以合入 `E6` 与 `M7-G3` 后的源码为准）：`feature/compose/*`（`E6` 完整编辑器、`FullEditorScreen`、`FullEditorViewModel`、`ImageUploadStrip`、`EditorContentModel`、`ComposeContracts.kt`）、`feature/threaddetail/interact/FloorContextMenu.kt`（`onEdit` / `onDelete`）、`feature/threaddetail/ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`

【范围】
1. **编辑已发表楼层与「还原原文」（复用 `E6` 完整编辑器）**：
   - **入口接线**：在 `ThreadDetailPane.kt` 接通 `FloorContextMenuOverlay.onEdit`（菜单已限定仅本人且已登录楼层显示 `编辑`），将 `(fid, tid, pid, page, isFirstFloor)` 投入 `E6` 完整编辑器（严格沿用 `E6` 的 §4.2 多栏形态：单栏全屏、`< 560dp` 全三栏居中模态、`≥ 560dp` 右栏内展开，纯色 0 玻璃）；
   - **预取线上原文与反向解析**：进入编辑态时调用 `postRepository.fetchPrePost(PrePostRequest.EditPost(fid, tid, pid, page))` 获取线上原文（标题 `subject`、分类 `typeId`/`typeOptions`、正文 `text`、已有图片 `existingImages`）；加载中显示 `HiLoadingSpinner`，失败原地显示错误原因与 `重试`；已有 `[attachimg]` 反向解析为缩略图块，**UI 绝不暴露原始 `[attachimg]` 标签文本**；在上传条移除线上已有图片时记录至 `deletedAttachments`；
   - **业务草稿 `DraftKey.edit(pid)` 与收口 `R-D6 S2`**：
     - 再次打开同 `pid` 编辑页自动恢复草稿并在输入框上方展示横条 `已恢复上次草稿`（按钮 `清空`）；
     - 顶栏返回、系统返回（`BackHandler` 按打开态 key 重挂）与预测性返回走同一出口：若内容与线上原文不同且非空，静默调 `saveDraft(DraftKey.edit(pid), ...)` 并弹 `已保存草稿`（按钮 `放弃`，点击调 `deleteDraft`）；与线上原文完全一致时不保存、不提示；
     - **收口 `R-D6 S2`**：若恢复了已有 `edit:<pid>` 草稿（`hadPersistedDraft == true`），随后手动删空或点 `清空` 后退出，**必须显式调用 `postRepository.deleteDraft(DraftKey.edit(pid))`**，杜绝下次进入复活旧草稿（附撤回即红单测）；
   - **顶栏「还原原文」与令牌撤销**：
     - 编辑器顶栏按钮 **`还原原文`**（`copy.md:38`）**仅当当前标题、分类、正文或图片集合与线上原文不同时出现**，与原文一致时彻底隐藏；
     - 点击 `还原原文` 立即将标题、分类、正文与已有图片换回线上原文，并经 `LocalPaneSnackbarController` 弹出 **`已还原原文`**（按钮 **`撤销`**，`copy.md:139`），撤销走统一独立令牌模型恢复点击前的编辑内容；
   - **提交编辑（`PostSubmitRequest.EditPost`）**：
     - 遵循 `D6` 规则：编辑**不受 30 秒冷却限制**（不因冷却禁用 `发送`、成功后不启动冷却），且**绝不附加小尾巴**；
     - 点 `发送` 0ms 按钮内切 `HiLoadingSpinner`（`正在发送…`）且留在本页；失败留在本页保留全部输入原地提示 `发送失败`（按钮 `重试`）或服务端原因；成功后删除 `edit:<pid>` 草稿、关闭编辑器并在 `ThreadDetailViewModel` 原地刷新该楼层内容同时保持当前阅读位置不跳动。
2. **楼层菜单「删除」自己的楼层（`FloorContextMenuOverlay.onDelete`）**：
   - **入口与二次确认框**：在 `ThreadDetailPane.kt` 与 `feature/threaddetail/interact/` 接通 `FloorContextMenuOverlay.onDelete`（仅本人且已登录楼层可见）；点击弹出确认对话框，文案逐字取自 `docs/design/copy.md:145`：
     - 标题：**`删除这条回复？`**
     - 副行：**`删除后无法恢复。`**
     - 按钮：**`取消`** / **`删除`**（错误色强调；确认中显示 `正在删除…` 并禁用重复点击；点 `取消` 或按返回不发起任何网络请求）；
   - **提交删除（`PostSubmitRequest.DeletePost`）与不可撤销约定**：
     - 确认后调用 `postRepository.submitPost(PostSubmitRequest.DeletePost(fid, tid, pid, page))`；
     - **删除不可撤销，严禁走撤销模型、严禁弹“撤销”Snackbar**；
     - **成功处理**：
       - 普通楼层（`result.threadDeleted == false`）：从当前 `ThreadDetailUiState.floors` 移除目标 `pid` 楼层，**保持当前阅读位置不跳动**（经既有列表锚点/不重置滚动位）；若同时存在该楼的 `edit:<pid>` 草稿一并清理；
       - 首楼删除致整帖删除（`result.threadDeleted == true`）：关闭帖子详情（单栏返回列表 / 多栏清空右栏回占位）并通知列表刷新；
     - **失败处理**：保持该楼层仍在列表中不被误删，原地提示失败原因（如服务端错误 / 30 秒冷却提示 / `网络连接失败`）并允许重新触发。
   - **全量 `MockWebServer` 测试（3 路径必覆盖）**：使用 `MockWebServer` + 真实 `DefaultPostRepository`（0 访问 `4d4y.com`）完整覆盖删除的 **成功（普通楼层移除保位 + 首楼整帖删除）、失败（保留楼层并提示）、取消（0 网络请求）** 三条路径。

【授权预期失效的既有测试与截图清单】
- `FloorContextMenuTest`（`feature/threaddetail/interact/`）：仅当补充 `onEdit` / `onDelete` 默认联调验证时允许新增用例，严禁删除或放宽既有断言；
- 既有全部 PNG 快照（`thread_*`、`quick_reply_*`、`editor_*`、`multipane_*`）**严格 0 漂移**；本卡可新增编辑态含 `还原原文` 与删除确认框专项快照 `hipda/src/test/snapshots/images/editor_edit_*.png`（约 2–4 张，浅/深色），须在 `_reports/E6b.md` 逐张列出供 `R-E6b` 目视。

【验收标准】
1. **编辑已发表楼层与 `还原原文` 闭环**：经 `MockWebServer` 实测 `PrePostRequest.EditPost` 拉取原文 → 偏离原文时 `还原原文` 出现、一致时隐藏 → 点击 `还原原文` 恢复原文并弹 `已还原原文 | 撤销` → 点 `撤销` 精确恢复编辑态 → 编辑提交免 30s 冷却、不附小尾巴、正确传递 `deletedAttachments`、成功后删 `edit:<pid>` 草稿并刷新楼层不跳位。
2. **`edit:<pid>` 草稿与 `R-D6 S2`（撤回必红）**：未改动原文退出 0 保存 0 提示；改动后任意返回途径静默存草稿弹 `已保存草稿 | 放弃`；恢复旧草稿后删空或点 `清空` 退出显式调用 `deleteDraft(DraftKey.edit(pid))`，重开不复活。
3. **删除自己楼层（仅 `MockWebServer`，三路径全覆盖）**：确认框文案逐字对齐 `copy.md:145`（`删除这条回复？` / `删除后无法恢复。` / `取消` / `删除`）；取消路径 0 网络请求；成功路径移除楼层保持阅读位（且无撤销 Snackbar），首楼整帖删除退回列表；失败路径保留楼层原地提示。
4. **复用 `E6` 多栏与返回优先级**：`411`/`749`/`1280dp` 编辑态与删除确认框复用 `E6` 投放与玻璃预算（`HiGlassMarker ≤ 3`），返回优先级严格高于 `PositionStack` 且不破坏 `E5`/`E6`/`M7-G3`。
5. **边界、截图与 CI**：0 改动禁改目录；非授权既有快照 0 漂移；本地跑通 `feature/compose`、`feature/threaddetail`、`MultiPaneGlassBudgetTest`、`CopyTerminologyScanTest`、`MotionTokenScanTest` 与 `:hipda:assembleDebug`，推送后 GitHub Actions CI 全绿。

【改哪些文件（待 `E6` 与 `M7-G3` 合入后开工）】
- 新增（生产）：`feature/threaddetail/interact/FloorDeleteConfirmDialog.kt`（或内聚于 `interact/`）
- 修改（生产，**严禁改 `feature/routing/*`、`feature/forum/*`、`feature/messages/*`、`feature/settings/*`、`feature/profile/*`、`feature/mine/*`、`feature/search/*`、`:emoji/*`、`data/*` 与共享构建/清单文件**）：
  - `feature/threaddetail/interact/FloorContextMenu.kt`（仅如需补充最小状态协作）
  - `feature/threaddetail/ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`（接通 `onEdit`/`onDelete`、编辑成功后刷新楼层、删除成功后移除楼层保位/整帖删除退回）
  - `feature/compose/*`（复用 `E6` 完整编辑器，补齐 `EditPost` 模式、`还原原文` 顶栏按钮与撤销令牌、`DraftKey.edit(pid)` + `R-D6 S2`、`deletedAttachments`）
  - `res/values/strings_compose.xml`、`res/values/strings_thread_detail.xml`（文案逐字取自 `docs/design/copy.md`）
- 新增与修改（测试与截图）：`hipda/src/test/java/net/jejer/hipda/feature/compose/*`、`feature/threaddetail/*`、`hipda/src/test/snapshots/images/editor_edit_*.png`
- 报告（仓外）：`/home/mir/Documents/hipda-next.wt/_reports/E6b.md`

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

【负责人提速裁定（优先于上文前置门禁）】基于 `card/E6-full-editor@adc777b` 开工，这个版本已包含 M7-G3。不必等 E6 合入；E6 合入 main 后，合并 origin/main 即可。E7 同时在并行，会改 `feature/compose` 中表情相关的接入。本卡在编辑器里只加编辑模式需要的最小扩展，冲突由后合入的一方解决。
