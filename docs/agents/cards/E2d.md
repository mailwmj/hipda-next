# 任务卡 E2d：详情双向连续加载与向上插页零闪跳

> **前置派发门禁与重叠说明**：本卡依赖 **`E2a` 已合入 `main`** 后派发。本卡严格只改 `ThreadDetailViewModel.kt` 和 `ThreadDetailPane.kt` 两个生产文件——与 `E2b`（仅 `feature/threaddetail/media/*`）及 `E3`（仅 `feature/app/*`、`ui/shell/*`）**100% 零重叠**；但 **`E2c` 同样需修改 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt`**（跨页跳楼/`只看此人`/1 楼投票），因此 **`E2d` 与 `E2c` 须串行衔接、不可同时在途**（见 `BATCH-5.md` §3.2 与 §5）。

【目标】将 `E2a` 沿用的 `E0` 临时上一页/下一页按钮升级为完整的双向自动连续预取加载（距页尾 5 楼自动预取下一页、首载页 `> 1` 滚近顶部自动预取上一页、头尾失败原地整行可点重试），并彻底解决 Compose `LazyColumn` **向上插入上一页楼层时的同帧零闪跳锚定**（严禁依赖滞后一帧的 `LaunchedEffect` 补偿）。

【范围】
- 基线：`origin/main` @ `3dd65c4`（已包含 `E0` 与 `E2a`）。工作区与分支：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/E2d -b card/E2d-thread-continuous-paging 3dd65c4`。
- 依据文档：
  - `docs/design/product-design.md`（v0.4：§6.3 连续加载/内容替换、§7.3 读帖连续加载、§10.1）
  - `docs/design/copy.md`（v0.4：列表底部加载下一页/顶部加载上一页 `正在加载…`、失败 `加载失败，点击重试`、末页 `没有更多了`）
  - `/home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md`（修订版 W3、§3.2、§6 对照表）
  - 基座与数据层对外 API 清单（**勿抄死签名，全部以基线源码为准**）：
    - `/home/mir/Documents/hipda-next.wt/_reports/E0.md`、`E2a.md`、`D5.md`
- 具体实现项（**严格只修改以下 2 个生产文件**，预计净增/改动 ~150–260 行）：
  1. **双向自动连续预取状态管理（`ThreadDetailViewModel.kt`）**：
     - 维护已加载连续页码区间（如 `firstLoadedPage..lastLoadedPage`）、头/尾各自独立的加载中与错误态、单飞防重入保护（同一方向或同一页码不重复发包）；
     - 向下自动预取：用户滚动至距当前已载最末楼层 5 楼以内且 `lastLoadedPage < totalPages` 时自动请求 `lastLoadedPage + 1` 并按楼层去重追加至尾部；
     - 向上自动预取：当 `firstLoadedPage > 1` 且用户滚近已载头部时自动请求 `firstLoadedPage - 1` 并将新楼层合入头部；
     - 头/尾任一方向加载失败不影响已展示楼层，支持各自独立重试；切换 `只看楼主` 过滤或按不相邻页重载时正确重置连续加载区间；保留与 `E0` `NextAppTracerBulletTest` 兼容的构造路径。
  2. **向上插页同帧零闪跳锚定与头尾状态行（`ThreadDetailPane.kt`）**：
     - 移除 `E0` 遗留的临时上一页/下一页底部按钮栏；头/尾连续加载状态直接复用 `HiListFooter`（`Loading` / `Failed` 整行可点重试 / 尾部 `End`）；
     - **闭合 R-BATCH5 B4-E2a ①（同帧零闪跳锚定铁律）**：向上合入上一页楼层进入 `LazyColumn` 头部时，**严禁依赖滞后一帧的 `LaunchedEffect(floors)` 在重组提交后再调用 `scrollToItem` 回滚**！必须在数据合入后的**同一帧**保持原首可见楼层相对屏幕顶部的像素坐标不变（每帧偏差严格 `≤ 1dp`；如借助合入前同步锚定、稳定 `key = "floor:${it.postId}"` 与 `LazyListState` 同步偏移校正/首帧零跳策略，须确保在 `mainClock.autoAdvance = false` 下首帧即稳）；
     - 严守既有契约：保持 `ThreadDetailPane` 顶层签名、楼层 `LazyColumn` 固定键 `key = { "floor:${it.postId}" }`、`detailGlassState` 严格兄弟拓扑（`HiGlassMarker == 1`）与 `PostImageBlock` 接入完全不变。
- **明确不做**：不改 `PostFloorCard.kt`、`body/*`、`media/*`、`ThreadDetailTopBar.kt`、`FilterCapsuleBar.kt`、`strings_thread_detail.xml`、`data/threaddetail/`、`feature/app/*`、`feature/common/*`、`ui/*`、既有 `thread_detail_*.png` 基线；不做引用展开/跨页跳楼高亮/长按菜单/投票（`E2c`）、不做阅读位置记忆/位置栈/底部三段工具条（`E4`）。

【验收】
1. **向上插页逐帧零闪跳（B4-E2a ①，最高优先级）**：`ContinuousPagingAnchorTest` 在 `mainClock.autoAdvance = false` 下从第 2 页顶部触发向上加载第 1 页并合入数据，逐帧推进并断言**每一帧（必须包含数据合入后的第 1 帧）**原首可见楼层距屏幕顶部偏移变化均 `≤ 1dp`；任何滞后一帧的 `LaunchedEffect` 回滚方案都会在首帧失败。
2. **向下 5 楼预取、并发防重与头尾失败重试**：`ContinuousPagingAnchorTest` 验证：(a) 滚动至距页尾 5 楼内自动预取下一页，重复滚动不重复发包；(b) 向下与向上加载失败分别在对应端渲染 `HiListFooter(Failed)`（`加载失败，点击重试`），点击整行后成功补载；(c) 到达末页尾部展示 `没有更多了`。
3. **严格 2 文件边界与全量回归**：`git diff --name-status 3dd65c4...HEAD` 确认生产代码仅改 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt`（不碰 `media/`、`body/`、`PostFloorCard.kt`、`data/`、`feature/app/`、既有快照）；`ThreadDetailViewModelTest`、`PostBodyRendererTest`、`NextAppTracerBulletTest`、`CopyTerminologyScanTest`、`MotionTokenScanTest`（白名单 0 增长）、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 在本地与 GitHub Actions 全绿。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 先在本地跑本卡相关测试（按 lesson.md 导出 mise 的 ANDROID_HOME/JAVA_HOME，flock 串行），本地变绿后才推送；不要拿 CI 调试半成品。完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/E2d.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

【改哪些文件】
- 修改（生产，严格仅 2 个文件）：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`
- 新增（测试）：
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/E2d.md`

【负责人裁定（开工前 NEEDS-INPUT 后）】
- 采用方案 B：保留 `ThreadDetailPaginationBar`，移除底栏与重录对应快照留给 `E4`。本卡范围与验收中"移除临时上下页按钮"一项作废。
- 既有测试与断言不改不放宽。仅当连续加载的页尾行导致既有截图漂移时，允许重录受此影响的截图，须在 `_reports/E2d.md` 逐张列出图名与漂移原因。
- 新增验收：底栏页码与连续加载后当前可见楼层所在页一致（单测）。
