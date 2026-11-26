# 审查卡 R-E4：阅读位置记忆、位置栈、底部三段式液态玻璃工具条与内联跳页条（commit 6526c1b677d934b2381aec94defc398ff3a26887）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E4-thread-chrome-and-position` @ `6526c1b677d934b2381aec94defc398ff3a26887`，相对 `70da638`（已含 `E2c` 与 `E3`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 6526c1b677d934b2381aec94defc398ff3a26887:<路径>`、`git diff 70da638...6526c1b677d934b2381aec94defc398ff3a26887` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45743939633`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E4.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E4.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E4.md`（可读，所有结论须以代码与 diff 核实为准）。基座与上游 API 参考 `_reports/{E0,E2a,E2d,E2c,E3,D5,D8a,V-C4d,R-Dkudo,R-C5b}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §4.1/§5/§6.3/§7.1/§7.3/§10）、`docs/design/copy.md`、`docs/agents/DISPATCH.md` §4、`_cards/BATCH-5.md`。阻断标准 `docs/agents/DISPATCH.md` §4（首轮恢复完成前误写 `(1,0)` 冲掉 Room 历史进度、`suppressBottomGlass = true` 未整块卸载外层 `HiGlassRegion` 致 `HiGlassMarker > 0` 或残留采样需求、三栏满载超 3 玻璃区域/2 采样源/5 pass、进度条配色违反 `V-C4d` 对比度低于 `4.5:1`、内联跳页滑块写死最小宽致 `340dp`+`1.25×` 挤压或弹键盘滚动底层楼层、`在浏览器中打开` 未走 `http`/`https` scheme 白名单或缺少反例测试、未移除 `ThreadDetailPaginationBar`、越界改 `media/*`/`feature/app/*`/`feature/forum/*`/`data/*`、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **首轮恢复门闩与位置栈（对照 B4-E4 ①）**：逐行审计 `ReadingPositionController.kt`、`PositionStack.kt`、`ThreadDetailViewModel.kt`——追踪慢网加载首次 `loadPage(5)` 挂起期间及中途退出路径，核实 `hydrationGate` 在恢复落地前是否 100% 阻断 `saveReadingPosition`（回退门闩则单测必红）；核实跳页/引用跳楼前先压栈、返回先出栈且回跳经 `PrependAnchorController` 单一滚动权威落位。
2. **复合 `HiGlassRegion` 卸载门禁、三栏 5 pass 预算与 `V-C4d` 对比度（对照 B4-E4 ②）**：逐行审计 `ThreadBottomToolbar.kt`、`ThreadDetailPane.kt`——确认 3 枚胶囊共用外层 `HiGlassRegion`（整体仅 1 个 `HiGlassMarker`）且 `suppressBottomGlass == true` 时整块卸载 `HiGlassRegion`（释放 `hasSourceDemand`）；核实三栏满载 `HiGlassMarker == 3` / 采样源 `≤ 2` / 静止 pass `≤ 5`；核实进度填充遵守 `V-C4d`（优先 `primaryContainer` 浅 `≤ 0.50`/深 `≤ 0.45`，绝不在浅色 `primary @ 0.22f` 上放 `cs.primary` 文字）且全 20 预设单测 `≥ 4.5:1`；核实 `在浏览器中打开` 走 `http`/`https` 白名单并有危险 scheme 反例测试、收藏/关注与复制（仅 `ClipData.newPlainText`）菜单严格去重。
3. **内联跳页条、方案 B 移除旧底栏与 `R-C5b`**：审计 `InlinePageJumpBar.kt`、`ThreadDetailPane.kt`、`HiPressFeedback.kt`、`HiRollingNumber.kt`——确认 `< 480dp` 双行 / `≥ 480dp` 单行、滑块用 `Modifier.weight(1f)` 无硬编码最小宽、`imePadding()` 仅上移跳页条底层 `0dp` 偏移、任何跳转先压栈并收起、展开/收起可打断；确认 `ThreadDetailPaginationBar` 已彻底移除且仅重录受影响的 `thread_detail_*.png`；确认 `R-C5b` 两处修复真正闭合。
4. **边界零重叠与代码体量/纯洁性**：核查 `git diff --name-status 70da638...6526c1b677d934b2381aec94defc398ff3a26887` 确认零碰 `media/*`/`feature/app/*`/`feature/forum/*`/`data/*`/`service/*`；核查生产行数是否超估计 1.5 倍（> 1575 行需理由）、无测试钩子、无硬编码动效。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `6526c1b677d934b2381aec94defc398ff3a26887`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E4.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E4.md`

【负责人追加重点】核对 E4 卡末“派发前核查追加”1–5：cancelPagingJobs 修复与回退必红单测；预授权的既有测试仅改触发方式、断言未放宽；PositionStack 返回优先于 E3 返回栈且 MultiPaneBackStackTest 未改；滚动写入点唯一。

【负责人追加重点】逐条核对 E4 报告既有测试改动清单：玻璃预算上限 ≤3/≤2/≤5 未放宽、偏移 ≤1dp 未放宽、无断言被删；重录 PNG 仅底部区域变化。
