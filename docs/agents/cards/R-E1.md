# 审查卡 R-E1：论坛页完整版（commit 2e8bed9）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E1-forum-page` @ `2e8bed9`，相对 `db7dba6`（含 `E0` 与 `D8a`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 2e8bed9:<路径>`、`git diff db7dba6...2e8bed9` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45680444796`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E1.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E1.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E1.md`（可读，所有结论须以代码与 diff 核实为准）。基座与数据层 API 参考 `_reports/{E0,D4,D8a,D8b}.md`（**以基线源码为准**）；产品与文案依据 `docs/design/product-design.md`（v0.4 §3.1/§4.2/§5/§6.3/§7.1/§8/§10）、`docs/design/copy.md`、`_cards/BATCH-5.md`（修订版 W2 & §6）。阻断标准 `docs/agents/DISPATCH.md` §4（账号异步水合仍卡死“需要登录”、上行折叠动态改 `contentPadding(top)` 导致跳动、`今日新帖` 翻页丢 `searchId` 或漏黑名单/已读、透明顶栏与 `LazyColumn` 非严格兄弟触发循环采样、硬编码 `#FFB003` 或超荧光笔 alpha 上限、`V-C1` 深色对比度跌破 `4.5:1` 或未拉大区分度、未守 `R-D4 S1` 显式 `sortByPostTime`、越界改 `feature/app/`/`feature/common/`/`data/`/`ui/` 或既有非 `forum_*` 快照、破坏 `ForumPane` 锁定签名、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **顶栏折叠零跳动、吸顶标签行与严格兄弟玻璃拓扑（对照 B4-E1 ②）**：逐行核查 `ForumPane.kt`、`ForumTopBar.kt`、`BalancedForumTabRow.kt`——确认 `useGlassTopBar == true` 时 `HiGlassStyle.TopBar` 与挂 `shellGlassSource()`/`externalGlassState` 的 `LazyColumn` 严格为兄弟节点，无父子同源/循环采样；确认 `useGlassTopBar == false`（铰链双栏）退纯色且 0 玻璃节点；确认上行收起时版块标签行吸顶不隐藏且 `LazyColumn` `contentPadding(top)` 恒定不变（差值 `== 0dp`）；确认荧光笔底条遵守 `HiGlassTokens.TabHighlighter*` 峰值上限且绝无 `#FFB003`；核查滚动中是否存在逐帧触发全表重组的读取（如在 Composable 主体直接读 `firstVisibleItemScrollOffset` 而不是 `graphicsLayer`/状态推迟读取）。
2. **账号异步水合、`今日新帖` `searchId` 与 `R-D4 S1`（对照 B4-E1 ①③）**：逐行审计 `ForumViewModel.kt`——核实 `AuthRepository.currentAccount` 从 `null → SavedAccount`（或登录态刷新）时确能自动重拉 `AuthRequired` 列表且不会在已正常加载时重复发请求；核实 `TODAY_NEW_FID` 路径调用 `D8a` `SearchRepository.getNewPosts` 时第 2 页+ 携带第 1 页 `searchId`，并与 `BlacklistRepository`、`BrowseHistoryDao` 组合即时响应；核实常规版块调用 `ThreadListRepository` 始终显式传入 `AppSettings.isSortByPostTime(fid)`（`R-D4 S1`），并保留与 `E0` `NextAppTracerBulletTest` 兼容的构造路径。
3. **刷新 diff、窄中栏、`V-C1` 对比度与文案/动效规范**：核查 `ThreadListRow.kt`、`NewThreadsPill.kt`、`strings_forum.xml`——确认下拉刷新按 `tid` diff、仅顶部有新帖才显示 `有 {N} 条新帖`；确认 `< 300dp` 窄中栏取消独立大头像列且不截断；确认 `V-C1` 深色/OLED 已读与未读标题均 `≥ 4.5:1` 且层次分明；确认文案逐字来自 `copy.md`、无硬编码时长/曲线且未改 `MotionTokenScanTest` 白名单。
4. **边界零重叠与代码体量/纯洁性**：核查 `git diff --name-status db7dba6...2e8bed9` 是否严格落在 `E1` 文件清单内（`feature/app/*`、`feature/common/*`、`feature/threaddetail/*`、`feature/login/*`、`ui/*`、`data/*`、`di/*`、非 `forum_*` 快照全部 0 改动）；核查生产行数是否超估计 1.5 倍（> 1350 行需理由）、是否有无必要的手写 `Layout`/`Modifier.Node` 或测试专用钩子。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `2e8bed9`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E1.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E1.md`
