# 审查卡 R-E2a：帖子详情核心完整版（commit 60bd5b7）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E2a-thread-detail-core` @ `60bd5b7`，相对 `db7dba6`。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 60bd5b7:<路径>`、`git diff db7dba6...60bd5b7` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45678739131`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E2a.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2a.md`（注：双向连续加载与向上插页零闪跳已拆出至 Wave 3 `E2d`，本卡翻页沿用 `E0` 上下页按钮）；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E2a.md`（可读，所有结论须以代码与 diff 核实为准）。基座与底层 API 参考 `_reports/{E0,D5,B8}.md`（**以基线源码为准**）；产品与设计依据 `docs/design/product-design.md`（v0.4 §5/§6.3/§7.3/§10）、`docs/design/tech-plan.md`（§5 `T6`）、`docs/design/copy.md`、`_cards/BATCH-5.md`（修订版 W2 & §6）。阻断标准 `docs/agents/DISPATCH.md` §4（极端文字色在浅/深/OLED 纯黑下对比度 `< 4.5:1`、详情顶栏圆形动作按钮嵌套调用 `HiGlassSurface`/`drawBackdrop` 或 `detailGlassState` 非严格兄弟、`PostImageBlock` 接缝缺少 `floorPostId`/`listState` 或楼层 `key` 非 `"floor:${it.postId}"`、`只看楼主` 退出未恢复原楼、越界改 `data/threaddetail/`/`feature/app/`/`feature/forum/`/`feature/common/`/`ui/` 或既有非 `thread_detail_*` 快照、破坏 `ThreadDetailPane` 锁定签名、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **`T6` 原生正文、`C1b#2` 表情与 `TextColorContrastAdjuster`（对照 B4-E2a ②）**：逐行审视 `PostBodyRenderer.kt`、`InlineEmojiContent.kt`、`TextColorContrastAdjuster.kt`——确认纯 Compose `AnnotatedString` + `InlineTextContent` + `LinkAnnotation`（无 WebView/旧 AndroidView）；确认 `InlineSpan.Emoji.normalizedKey` 直连 `EmojiMap.drawableForImageSrc(key, dark)` 并处理未知键回退；核验动态调色算法对 `#000000`/`#000080`/`#FFFF00`/`#FFFFFF` × 浅/深/OLED 纯黑 `#000000` 均可达 `≥ 4.5:1` 且不把正常颜色破坏成单一灰白。
2. **详情透明顶栏玻璃拓扑、轻底圆钮与 `只看楼主` 过滤**：逐行核查 `ThreadDetailTopBar.kt`、`FilterCapsuleBar.kt`、`PostFloorCard.kt`、`ThreadDetailPane.kt`——确认顶栏 `HiGlassStyle.TopBar`（`HiGlassMarker == 1`）与挂 `detailGlassState` 的楼层 `LazyColumn` 为严格兄弟节点、圆钮仅用轻量纯色半透明底（零嵌套玻璃、触控区 `≥ 48dp`）；确认 `只看楼主` 过滤胶囊显示、空态回退及退出定位回原楼；确认翻页干净沿用 `E0` 上下页按钮且未夹带半成品连续加载代码。
3. **Wave 3 并行接缝（对照 B2-2）与签名兼容**：逐行核查 `media/PostImageBlock.kt`、`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`——确认 `PostImageBlock.kt` 精确锁死 `image`、`floorPostId`、`listState`、`onOpenImageViewer` 且 `LazyColumn` 使用 `key = { "floor:${it.postId}" }`；确认保留 `ThreadDetailPane` 锁定签名与 `NextAppTracerBulletTest` 兼容 2 参构造。
4. **边界零重叠与代码体量/纯洁性**：核查 `git diff --name-status db7dba6...60bd5b7` 是否严格落在 `E2a` 文件清单内（`data/threaddetail/`、`feature/app/*`、`feature/forum/*`、`feature/login/*`、`feature/common/*`、`ui/*`、`di/*`、非 `thread_detail_*` 快照全部 0 改动）；核查生产行数是否超估计 1.5 倍（> 1275 行需理由）、无测试钩子、无硬编码动效。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `60bd5b7`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E2a.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E2a.md`
