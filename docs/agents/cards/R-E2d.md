# 审查卡 R-E2d：详情双向连续加载与向上插页零闪跳（commit c447d4a）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E2d-thread-continuous-paging` @ `c447d4a`，相对 `3dd65c4`（含 `E0` 与 `E2a`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show c447d4a:<路径>`、`git diff 3dd65c4...c447d4a` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45697095253`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E2d.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2d.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E2d.md`（可读，所有结论须以代码与 diff 核实为准）。基座与数据层 API 参考 `_reports/{E0,E2a,D5}.md`（**以基线源码为准**）；产品依据 `docs/design/product-design.md`（v0.4 §6.3/§7.3/§10.1）、`docs/design/copy.md`、`_cards/BATCH-5.md`（修订版 W3 & §6）。阻断标准 `docs/agents/DISPATCH.md` §4（向上插页依赖滞后一帧的 `LaunchedEffect` 补偿导致首帧闪跳、测试未在 `mainClock.autoAdvance = false` 下覆盖合入首帧、向下/向上预取存在竞态重复请求或页码区间错乱、越界修改 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt` 之外的任何生产文件或既有快照、破坏 `key = "floor:${it.postId}"` 或 `detailGlassState` 严格兄弟拓扑、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **向上插页同帧零闪跳机制（最高优先级，对照 B4-E2a ①）**：逐行审计 `ThreadDetailPane.kt` 与 `ThreadDetailViewModel.kt`——追踪上一页楼层合入 `LazyColumn` 头部的完整时间线（状态更新 → 重组 → 测量布局 → 绘制首帧），**严查是否存在靠 `LaunchedEffect` 在提交后异步回滚的伪锚定**；审视 `ContinuousPagingAnchorTest.kt` 是否真在 `mainClock.autoAdvance = false` 下逐帧（含合入首帧）断言偏移 `≤ 1dp`，尝试推理把实现改回 `LaunchedEffect` 时该测试是否必定变红。
2. **双向预取状态机、防重入与头尾错误恢复**：逐行审计 `ThreadDetailViewModel.kt`——核实距尾 5 楼向下预取与 `firstLoadedPage > 1` 向上预取的触发条件、单飞/去重逻辑（防同一页重复请求、楼层按 `postId` 去重防 `LazyColumn` 重复键崩溃）、头尾失败互不覆盖且点击整行 `HiListFooter(Failed)` 可重试恢复、切换 `只看楼主` 时连续页码区间与预取任务正确取消重置。
3. **严格 2 文件边界与契约零回归**：核查 `git diff --name-status 3dd65c4...c447d4a`——生产文件必须严格仅含 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt`（零触碰 `PostFloorCard.kt`、`body/*`、`media/*`、`data/*`、`feature/app/*`、`strings_*.xml` 与既有快照）；确认 `ThreadDetailPane` 顶层签名、`key = "floor:${it.postId}"`、`detailGlassState` 严格兄弟拓扑（`HiGlassMarker == 1`）与 `NextAppTracerBulletTest` 兼容构造均完好；核查生产净增行数未失控且无测试专用钩子。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `c447d4a`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E2d.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E2d.md`

【负责人裁定】E2d 采用方案 B：保留上下页底栏（移除留给 E4）；只允许因页尾行漂移的截图重录，逐张核对 _reports/E2d.md 所列图名与原因，其余既有快照必须 0 漂移；核对底栏页码与可见楼层所在页一致。
