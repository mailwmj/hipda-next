# 审查卡 R-E2b：帖子图片渲染与图片 URL 白名单（commit 1597b4e）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/E2b-thread-images` @ `1597b4e`，相对 `3dd65c4`（或实际派发基线 `3dd65c4`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 1597b4e:<路径>`、`git diff 3dd65c4...1597b4e` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45698550909`（核实 `headSha` 匹配、含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 及 Roborazzi verify）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-E2b.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2b.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/E2b.md`（可读，所有结论须以代码与 diff 核实为准）。基座与接缝 API 参考 `_reports/{E2a,R-E2a,E0,B8}.md`（**以基线源码为准**）；产品与安全依据 `docs/design/product-design.md`（v0.4 §6.3/§6.4/§7.9/§10.1）、`docs/design/copy.md`、`docs/agents/DISPATCH.md` §4（scheme 白名单阻断条款）、`_cards/BATCH-5.md`（修订版 W3 & §6）。阻断标准 `docs/agents/DISPATCH.md` §4（图片 URL 未走 `http`/`https` 白名单致 `file://`/`content://`/`data:`/`javascript:`/`intent:`/`mailto:` 传入 Coil 或 `onOpenImageViewer`、缺少任一危险 scheme 反例测试、三分支滚动补偿在首楼下方误滚或在首楼上方跳动 `> 1dp`、未按显示尺寸解码导致原图 OOM、越界修改 `ThreadDetailViewModel.kt`/`ThreadDetailPane.kt`/`PostFloorCard.kt`/`PostBodyRenderer.kt`/`strings_thread_detail.xml` 或任何非 `media/` 生产文件、改坏 `PostImageBlock` 签名、生产代码含测试钩子，须给具体触发路径）。
【审查重点】
1. **图片 URL Scheme 白名单（最高优先级，对照 `DISPATCH.md` §4 与 `R-E2a R2-S1`）**：逐行审计 `PostImageBlock.kt` 中 `thumbUrl` 与 `fullUrl` 的白名单校验——确认**仅允许含非空 host 的 `http`/`https`**（严拒 `file:`、`content:`、`data:`、`javascript:`、`intent:`、`mailto:`、空白/控制字符、大小写变体、无 scheme、协议相对路径）；确认非法 URL 在渲染层 0 请求（绝不构造 Coil `ImageRequest`）且在点击看图出口同样被拦截；审视 `PostImageBlockTest` 是否对每种危险 scheme 均有反例断言。
2. **三分支无跳动滚动补偿与比例缓存（对照 B4-E2b）**：逐行审视 `PostImageBlock.kt` 与 `ImageAspectRatioCache.kt`——追踪图片高度变化时如何通过 `floorPostId` 与 `listState.layoutInfo.visibleItemsInfo`（键 `"floor:$floorPostId"`）区分“首楼上方”与“首楼自身/下方”，严查误判边界（如首楼包含多张图片或跨楼层）；确认 `ImageAspectRatioCache` 有容量上限防内存泄漏且回收再入首帧直接命中；审视 `ImageScrollCompensationTest` 删掉补偿或误补首楼下方时是否必变红。
3. **按显示尺寸解码、自动流量节省与取消/重试**：核查 Coil `ImageRequest` 是否传入容器目标尺寸（非 `Size.ORIGINAL`）；核查 `ImageLoadingPolicy` 仅在系统流量节省 + 计费网络时切点击加载；核查点击加载中再点取消能真正取消在途请求、失败重试与看图 `sourceBoundsInWindow` 非零上报；确认文案逐字来自 `copy.md` 且动效来自 `HiMotion`。
4. **严格隔离边界与代码体量/纯洁性**：核查 `git diff --name-status 3dd65c4...1597b4e` 确认生产改动严格仅限 `feature/threaddetail/media/*` 与 `strings_thread_media.xml`（零碰 `E2d`/`E3`/`E2c` 任一文件，既有非 `thread_image_*` 快照 0 漂移）；核查生产行数是否超估计 1.5 倍（> 825 行需理由）、无测试专用钩子、无手写无必要的 `Layout`/`Modifier.Node`。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `1597b4e`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-E2b.md`。

【改哪些文件】
- 代码仓库：只读，0 文件改动。
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/R-E2b.md`

【负责人裁定】E2b 获授权改 PostBodyRendererTest 图片用例与 ThreadDetailScreenshotTest（注入离线 ImageLoader），重录 9 张 thread_detail_*.png：逐张核对图片区域以外 0 变化、测试 0 真实网络。
