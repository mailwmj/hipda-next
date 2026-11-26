# 任务卡 D-cleanup：数据层、后台轮询与零冲突小修收尾（非界面卡，不设 V）

> **派发门禁与文件零重叠核对**：
> - **与 `E2c`、`E3`、`E4` 零重叠**：逐条核对本卡全部文件集合后确认，本卡 6 项收口与 `E2c`（`interact/*`、`PostFloorCard`/`PostBodyRenderer`/`ThreadDetailViewModel`/`ThreadDetailPane`、`core-parser`）、`E3`（`feature/app/*`、`ui/shell/*`）、`E4`（`chrome/*`、`ThreadDetailViewModel`/`ThreadDetailPane`、`ui/designsystem/feedback/*`）**100% 零文件重叠**（无需改分任何条目至 E 卡）。
> - **与在途 `E2d` 的衔接门禁**：第 6 项 `R-E2b S1` 需精简 `feature/threaddetail/media/PostImageBlock.kt`，而在途 `E2d` 修复轮（`f8b5d2a`）同步修改了该文件（新增 `LocalAboveViewportHeightCompensationAuthority` 与同快照 `classifyHeightChangeBranch`）。**因此须待 `E2d` 合入 `main`、拿到包含 `E2d` 的基线 `fbeeb28` 后再派发本卡**；合入后本卡即可与 `E2c`、`E3`、`E4` 任意并行且 0 冲突。

【目标】一次性收拢 `docs/agents/handoff.md` 及前期审查报告中所有归属 `D-cleanup` 的非阻断小修与单行变异补测（`R-D6 S3/S4`、`D5-fixcheck` 微注、`R-D9 S1`、`R-E1` 末两条、`R-E2b S1`），加固数据层/后台服务防御边界、补齐单测变异盲区并精简 `media/` 约 60 行冗余代码，保持对外行为与全部 Roborazzi 截图基线 **0 漂移**。

【范围】
- 基线：`origin/main` @ `fbeeb28`（待 `E2d` 合入 `main` 后填写）。工作区与分支：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/D-cleanup -b card/D-cleanup-followups fbeeb28`。
- 依据报告（**具体行号与签名以基线源码为准**）：
  - `docs/agents/handoff.md` §4 `→ D-cleanup`
  - `docs/agents/reports/R-D6.md`（`S3`、`S4`）、`docs/agents/reports/D5-fixcheck.md`（`S4-c` 微注）、`docs/agents/reports/R-D9.md`（`S1`）、`docs/agents/reports/R-E1.md`（二轮备注 1–2）、`docs/agents/reports/R-E2b.md`（重点 5 与 `S1`）、`_reports/E2d.md`（§6.3 `PostImageBlock.kt` 新增的单一滚动权威委托点）
- 具体实现项（共 6 项，预计净代码变化约为精简 `-30..+40` 行生产代码 + 补强单测）：
  1. **`R-D6 S3`（上传未登录检测收紧）**：
     - 文件：`hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt`、`hipda/src/test/java/net/jejer/hipda/data/post/ImageUploadRepositoryTest.kt`
     - 改动：在 `isUploadResponseUnauthenticated` 中检测到响应包含 `#umenu` 页头时直接 `return !meta.session.isLoggedIn`（不再落入宽松子串 `UNAUTH_UPLOAD_MARKERS` 兜底），避免已登录错误页因正文偶然含关键词触发无谓静默重登；补齐含 `#umenu` 已登录错误页 0 重登与未登录正常触发重登的对照单测。
  2. **`R-D6 S4`（发帖与压缩 3 处单行变异盲区补测）**：
     - 文件：`hipda/src/test/java/net/jejer/hipda/data/post/PostRepositoryTest.kt`、`hipda/src/test/java/net/jejer/hipda/data/post/ImageCompressorTest.kt`
     - 改动：(a) 为 `fetchPrePost` 与 `submitPost` 补齐会话过期静默重登并重试成功的单测（锁死 `requireAuth = true`）；(b) 用 Turbine 直接订阅 `postRepo.cooldownUntilMs` 验证切号/退登自动推送 `0L`（锁死后台收集器）；(c) 在 `ImageCompressorTest` 补齐 `content://` URI 读取与 EXIF 旋转校正单测（纯离线）。
  3. **`D5-fixcheck` 微注（`AuthorOnly` 第 2 页 `fallbackOpAuthor` 夹具加固）**：
     - 文件：`hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt`
     - 改动：补一条 `AuthorOnly` 第 2 页返回楼层作者为空/空楼层列表的夹具用例，锁死 `DefaultThreadDetailRepository` 中历史楼主回退分支（避免仅由次级首楼作者分支兜住导致单行变异不红）。
  4. **`R-D9 S1`（后台轮询 Keystore 异常兜底与冷启动确定性单测）**：
     - 文件：`hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt`、`hipda/src/test/java/net/jejer/hipda/service/NotificationPollingWorkerTest.kt`
     - 改动：将 `NotificationPollingWorker.hasActiveAccount` 中的 `catch (_: IOException)` 放宽为 `catch (_: Exception)`（同时确保 `CancellationException` 照常重抛），防止后台唤醒时 Keystore `GeneralSecurityException`/`ProviderException` 逸出致 Worker 失败；在 `NotificationPollingWorkerTest` 中补非 `IOException` 兜底用例并用门闩挂起初始凭据流消除冷启动调度不确定性。
  5. **`R-E1` 末两条（`HiSkeleton` KDoc 对齐 + `ForumViewModel` `hasMore` 跨号屏蔽）**：
     - 文件：`hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt`、`hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt`、`hipda/src/test/java/net/jejer/hipda/feature/forum/ForumViewModelTest.kt`
     - 改动：① 将 `HiSkeleton.kt` 顶部 KDoc 中过时的 `LocalContentColor` 注释改为 `MaterialTheme.colorScheme.onSurface`（纯注释，0 行为改动）；② 将 `ForumViewModel.kt` 的 `hasMore` 改为 `!staleUidCache && load.hasMore`，并在 `ForumViewModelTest` 补跨号切换未就绪时 `uiState.hasMore == false` 断言。
  6. **`R-E2b S1`（`feature/threaddetail/media/` 精简约 60 行，纯内部重构、0 视觉/行为变化）**：
     - 文件：`hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/{PostImageBlock,ImageLoadingPolicy,ImageAspectRatioCache}.kt` 及对应 `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/{PostImageBlockTest,ImageLoadingPolicyTest,ImageScrollCompensationTest}.kt`
     - 改动（对照 `docs/agents/reports/R-E2b.md` 重点 5）：
       - `PostImageBlock.kt`：将 `Failed` 态改为带 `canRetry: Boolean` 合并移除重复的 `InvalidImagePlaceholder`；合并 `computeImageBottomInViewport` 双 `while` 为单趟遍历；移除 `LaunchedEffect` 的 `Triple` 包装与冗余 `nextLoadId`；**严禁破坏 `E2d` 新增的 `LocalAboveViewportHeightCompensationAuthority` 与同快照 `classifyHeightChangeBranch`，且保持 `PostImageBlock` 5 参公开签名与 `http`/`https` 白名单 100% 不变**；
       - `ImageLoadingPolicy.kt`：收敛仅测试调用的 `ImageNetworkState` 中间重载（保留 `resolveLoadMode(isMetered, isDataSaverEnabled)` 与 `resolveForContext(context)`），同步更新 `ImageLoadingPolicyTest`；
       - `ImageAspectRatioCache.kt`：收敛零生产调用的 `size`/`get`/公开浮点 `put` 重载（改 `private`），同步精简对应测试断言；
       - **硬约束**：全部 `thread_image_*.png` 与 `thread_detail_*.png` 截图 **0 像素漂移**，`ContinuousPagingAnchorTest` 全套 `e2bE2dMerge_*` 保持 100% 绿灯。
- **明确不做**：不碰 `E2c`/`E3`/`E4` 任一文件（`ThreadDetailViewModel.kt`、`ThreadDetailPane.kt`、`PostFloorCard.kt`、`PostBodyRenderer.kt`、`interact/*`、`chrome/*`、`feature/app/*`、`ui/shell/*`、`ui/designsystem/feedback/*`、`core-parser/*`、`res/values/*`、任何截图 PNG）。

【验收】
1. **6 项收口全部落地且回退必红**：每项改动均配备精准单测（含 `R-D6 S3/S4`、`D5-fixcheck`、`R-D9 S1`、`R-E1` `hasMore`），在报告中说明对应测试如何验证修复生效且回退变红。
2. **`R-E2b S1` 等价精简与零回归**：`feature/threaddetail/media/` 净减 ~55–65 行，保持 `PostImageBlock` 公开签名、`http`/`https` 白名单全反例、`E2d` `LocalAboveViewportHeightCompensationAuthority` 集成不变；`PostImageBlockTest`、`ImageLoadingPolicyTest`、`ImageScrollCompensationTest`、`PostImageScreenshotTest`、`ContinuousPagingAnchorTest` 全部通过且全仓快照 **0 漂移**。
3. **严格文件边界与全量 CI**：`git diff --name-status fbeeb28...HEAD` 严格落在下方清单内（零碰 `E2c`/`E3`/`E4` 任何文件与快照）；`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`、`:core-parser:test`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 在本地与 GitHub Actions 全绿。

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
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/D-cleanup.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。任何把站点内容（帖子、短消息、签名、链接、图片）交给系统或 WebView 打开、加载的代码，必须走 scheme 白名单，只允许 `http`、`https`，以及经过校验的 `mailto`；每种危险 scheme 都要有反例测试。

【改哪些文件】
- 修改（生产，共 7 个文件，与 `E2c`/`E3`/`E4` 零重叠；仅 `PostImageBlock.kt` 须待 `E2d` 合入后）：
  - `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt`（`R-D6 S3`）
  - `hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt`（`R-D9 S1`）
  - `hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt`（`R-E1` 备注 1，仅 KDoc）
  - `hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt`（`R-E1` 备注 2，`hasMore`）
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt`（`R-E2b S1`）
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicy.kt`（`R-E2b S1`）
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageAspectRatioCache.kt`（`R-E2b S1`）
- 修改（测试，共 9 个文件，零快照改动）：
  - `hipda/src/test/java/net/jejer/hipda/data/post/ImageUploadRepositoryTest.kt`（`R-D6 S3`）
  - `hipda/src/test/java/net/jejer/hipda/data/post/PostRepositoryTest.kt`（`R-D6 S4`）
  - `hipda/src/test/java/net/jejer/hipda/data/post/ImageCompressorTest.kt`（`R-D6 S4`）
  - `hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt`（`D5-fixcheck` 微注）
  - `hipda/src/test/java/net/jejer/hipda/service/NotificationPollingWorkerTest.kt`（`R-D9 S1`）
  - `hipda/src/test/java/net/jejer/hipda/feature/forum/ForumViewModelTest.kt`（`R-E1` 备注 2）
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlockTest.kt`（`R-E2b S1`）
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicyTest.kt`（`R-E2b S1`）
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageScrollCompensationTest.kt`（`R-E2b S1`）
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/D-cleanup.md`

【负责人裁定（派发前）】
- 删除 `R-E2b S1`（media/ 精简约 60 行）整项：`PostImageBlock.kt` 刚在 E2d 修复轮里承载了同帧单一滚动权威，经逐帧与合并复验才稳定，为 60 行精简去动它属于"清理触碰已验证的时序敏感代码"，按 lesson 应视同重写，收益不抵风险。本卡零碰 `feature/threaddetail/media/*` 及其测试。
- 其余各项照做；`ForumViewModel.kt` 仅改 `hasMore` 一处。
