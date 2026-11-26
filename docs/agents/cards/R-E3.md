# 审查卡 R-E3：多栏内容容器与全形态连续性（commit 6fa4765）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E3-multipane-container` @ `6fa4765`，相对 `b7557bf`（含 `E0`、`E15`、`E2a`、`E1`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 6fa4765:<路径>`、`git diff b7557bf...6fa4765` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45711171747`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E3.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E3.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E3.md`（可读，所有结论须以代码与 diff 核实为准）。基座与上游 API 参考 `_reports/{E0,R-E0,E1,E2a,R-C4d}.md`（**以基线源码为准**）；产品与规范依据 `docs/design/product-design.md`（v0.4 §3.1–§3.2、§4.1–§4.4、§5、§6.3、§10.1–§10.3）、`docs/design/copy.md`、`docs/agents/DISPATCH.md` §4、`_cards/BATCH-5.md`（修订版 W3a & §6）。阻断标准 `docs/agents/DISPATCH.md` §4（空右栏拖动仍含 `0dp` 锚点或露出空白、折叠/展开/旋转/进程回收导致崩溃或丢阅读位置/丢 `LazyListState`、切 `tid` 串无键局部状态或开帖切版块泄漏旧 `ViewModelStore`、非论坛入口半折晋升硬编码跳回论坛、三栏超 3 个玻璃区域或 2 个采样源或铰链双栏左顶栏未退纯色、单栏二级页底部胶囊未退场、越界改 `feature/forum/*`/`feature/threaddetail/*`/`feature/login/*`/`feature/common/*`/`data/*` 或既有非 `multipane_*` 快照、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **分隔线拖拽吸附、`hasDetailContent == false` 锚点底线与全屏阅读（对照 B4-E3 ①）**：逐行审计 `PaneDividerHandle.kt`、`MultiPaneReadingContainer.kt`——确认使用 Compose 标准 `AnchoredDraggable`、`hasDetailContent == false` 时锚点严格不含 `0dp`（不靠松手回弹冒充禁用）；确认横竖屏档位持久化、`CustomAccessibilityAction` 与键盘操作；确认 `全屏阅读 / 显示列表` 一键还原进入前的左栏形态与中栏档位并释放中栏玻璃源。
2. **全形态返回栈、物理铰链双栏与 `R-E0 R2-S3/S4`（对照 B4-E3 ②）**：逐行审计 `MultiPaneBackStack.kt`、`NextAppState.kt`、`NextAppHostViewModel.kt`、`NextAppRoot.kt`——核实 `History`/`Notify` 等非论坛入口在展平三栏 ↔ 半折铰链双栏 ↔ 返回键下的左栏晋升与回退；核实切 `tid` 时无键 `remember`/`rememberSaveable` 确已隔离重置（测试反例可抓回退）；核实开帖切版块必 `clear()` 旧帖 Store 且有测试断言；核实 `LazyListState` 已迁入 `SaveableStateHolder` 并通过 `StateRestorationTester` 进程回收恢复（同时跨形态保活 0 额外请求）；核实窄屏单栏二级页退场胶囊与合并未读数接入。
3. **三栏 3 区域玻璃协同、多栏 Snackbar 与 `R-C4d S1–S3`**：审计 `MultiPaneGlassBudgetTest.kt`、`MultiPaneSnackbarHost.kt`、`HiAdaptiveShell.kt`、`HiNavigationSidebar.kt`、`HiAdaptiveShellTest.kt`——核实三栏真实 `E1`+`E2a` 下 `HiGlassMarker ≤ 3`、独立采样源 `≤ 2`、静止 pass `≤ 5`、铰链双栏左顶栏纯色；核实 `DetailPane` Snackbar 居中右栏不跨线/铰链；核实 `R-C4d S1–S3` 全部真正闭合且对应单测回退必红。
4. **零重叠边界与代码体量/纯洁性**：核查 `git diff --name-status b7557bf...6fa4765` 确认零触碰 `feature/forum/*`（`E1`）、`feature/threaddetail/*`（`E2a`/`E2b`/`E2d`）、`feature/login/*`、`feature/common/*`、`data/*`、`di/*` 及既有非 `multipane_*` 快照；核查生产行数是否超估计 1.5 倍（> 1575 行需理由）、无测试钩子、无硬编码动效。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `6fa4765`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E3.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E3.md`
