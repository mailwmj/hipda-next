# 审查卡 R-D-cleanup：数据层、后台轮询与零冲突小修收尾（commit 230e88d）

【角色】独立代码审查者，只读。本卡不设 V 卡。
【边界】对象 `origin/card/D-cleanup-followups` @ `230e88d`，相对 `fbeeb28`（含 `E2d`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 230e88d:<路径>`、`git diff fbeeb28...230e88d` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45715881101`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-D-cleanup.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/D-cleanup.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/D-cleanup.md`（可读，所有结论须以代码与 diff 核实为准）。上游原始审查条目见 `docs/agents/reports/{R-D6,D5-fixcheck,R-D9,R-E1,R-E2b}.md` 与 `_reports/E2d.md`（**以基线源码为准**）。阻断标准 `docs/agents/DISPATCH.md` §4（任一收口项未闭合或新单测回退修复后仍通过、`NotificationPollingWorker` 吞掉 `CancellationException`、`R-E2b S1` 精简破坏 `PostImageBlock` 公开签名/`http`/`https` 白名单或 `E2d` `LocalAboveViewportHeightCompensationAuthority` 同帧协作、造成任何 Roborazzi 快照漂移、越界修改 `E2c`/`E3`/`E4` 归属文件或共享构建文件，须给具体触发路径）。
【审查重点】
1. **数据层与后台服务防御收口（`R-D6 S3/S4`、`D5-fixcheck`、`R-D9 S1`）**：逐行审计 `DefaultImageUploadRepository.kt`、`NotificationPollingWorker.kt` 及 5 个对应测试文件——确认 `#umenu` 分支直接按 `!meta.session.isLoggedIn` 返回；确认 `NotificationPollingWorker.hasActiveAccount` 放宽至 `catch (_: Exception)` 同时显式重抛 `CancellationException` 且冷启动门闩消除调度竞态；确认 `R-D6 S4` 3 处单行变异补测与 `D5-fixcheck` 空作者夹具在回退对应生产单行时确定性变红。
2. **`R-E1` 末两条与 `R-E2b S1` 等价精简**：审计 `HiSkeleton.kt`（确认仅改 KDoc 无逻辑改动）、`ForumViewModel.kt`（`hasMore = !staleUidCache && load.hasMore`）与 `feature/threaddetail/media/{PostImageBlock,ImageLoadingPolicy,ImageAspectRatioCache}.kt`——逐段核验精简后的 `InvalidImagePlaceholder` 合并、单趟 `computeImageBottomInViewport`、去 `Triple` 包装与重载收敛与原实现 100% 行为等价，特别核实 **`http`/`https` 白名单全反例**与 **`E2d` `LocalAboveViewportHeightCompensationAuthority` + 同快照 `classifyHeightChangeBranch`** 未被误删或改坏。
3. **严格文件边界与零快照漂移**：核查 `git diff --name-status fbeeb28...230e88d` 确认严格落在 `D-cleanup.md` 的 7 生产 + 9 测试文件内，零碰 `E2c`/`E3`/`E4` 文件与任何 `hipda/src/test/snapshots/` PNG，CI 全量测试（含 `ContinuousPagingAnchorTest` `e2bE2dMerge_*` 与 Roborazzi verify）全绿。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `230e88d`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-D-cleanup.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-D-cleanup.md`

【负责人裁定】D-cleanup 已删除 R-E2b S1，核对零碰 feature/threaddetail/media/*。
