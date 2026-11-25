# 任务卡 E2b：帖子图片渲染（预占位、淡入、滚上无跳动补偿、省流点击加载与图片 URL 白名单）

> **派发状态与并行隔离**：本卡**不依赖 `E1`**，可立即基于 **`3dd65c4`（已含 `E2a`）** 派发。本卡严格独占 `feature/threaddetail/media/*`、`strings_thread_media.xml` 与 `thread_image_*.png`，**严禁修改任何父文件**（零碰 `ThreadDetailViewModel.kt`、`ThreadDetailPane.kt`、`PostFloorCard.kt`、`PostBodyRenderer.kt`、`strings_thread_detail.xml`），与已派出的 `E2d`（仅改 `ThreadDetailViewModel`/`ThreadDetailPane`）及同波 `E3`（仅改 `feature/app/*` 与 `ui/shell/`）**100% 零文件重叠**。

【目标】在 `E2a` 锁定的 `feature/threaddetail/media/PostImageBlock.kt` 接缝内加厚实现完整的帖子图片渲染：按显示尺寸解码（Coil 3 `LocalHiImageLoader`）与 `effectsDefault` 淡入、按 `ImageAspectRatioCache` → 图片尺寸 → `4:3` 预占位并实现三分支精准无跳动滚动补偿（上方变高补偿 `≤ 1dp`、首楼自身/下方 `== 0dp` 零误滚、回收再入命中缓存）、支持系统流量节省+计费网络自动切点击加载与可取消进度圈、原地失败重试、点击上报缩略图窗口边界，并严格闭合 `R-E2a R2-S1` 图片加载 `http`/`https` 白名单。

【范围】
- 基线：`origin/main` @ `3dd65c4`（已含 `E0`、`E15`、`E2a`）。工作区与分支：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/E2b -b card/E2b-thread-images 3dd65c4`。
- 依据文档：
  - `docs/design/product-design.md`（v0.4：§6.3 图片占位/动效、§6.4 按显示尺寸解码、§7.9 图片加载与自动流量节省、§8、§10.1 无布局跳动）
  - `docs/design/copy.md`（v0.4：`点击加载图片（{size}）`、`点击加载图片`、`正在加载图片，点击取消`、`图片加载失败`、`重试`）
  - `docs/design/backlog.md`（E2b）与 `/home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md`（修订版 W3、§3.2、§6 对照表）
  - 基座与底层对外 API 清单（**勿抄死签名，全部以基线源码为准**）：
    - `/home/mir/Documents/hipda-next.wt/_reports/E2a.md`（`PostImageBlock` 锁定签名、楼层主键 `floor:$postId`、`sanitizePostUrl` 参考实现）
    - `/home/mir/Documents/hipda-next.wt/_reports/E0.md`（`LocalHiImageLoader` / `currentHiImageLoader()`、`ImageViewerLaunchArgs`、`FeatureScreenshotQualifiers`、`CopyTerminologyScanTest`）
    - `/home/mir/Documents/hipda-next.wt/_reports/R-E2a.md`（`R2-S1`：图片加载仅放行 `http`/`https`）
    - `/home/mir/Documents/hipda-next.wt/_reports/B8.md`（`ContentBlock.Image` 模型字段）
- 具体实现项（预计生产代码净增/改动 ~350–550 行，**严格限于 `feature/threaddetail/media/` 与 `strings_thread_media.xml`**）：
  1. **严守 `E2a` 接缝签名，零改父文件**：
     - 保持 `PostImageBlock(image, floorPostId, listState, onOpenImageViewer, modifier)` 顶层签名与调用方 `PostBodyRenderer.kt` 100% 源码兼容；不得修改 `PostBodyRenderer.kt`、`PostFloorCard.kt`、`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt` 或 `strings_thread_detail.xml`。
  2. **图片 URL Scheme 白名单（闭合 `R-E2a R2-S1` + 界面卡安全条款）**：
     - 校验 `image.thumbUrl` 与 `image.fullUrl`：**仅允许经校验且含非空 host 的 `http` / `https` URL**（禁止空白/控制字符、大小写变体如 `FILE://`、`Content://`、`JavaScript:`、无 scheme、协议相对路径等；图片加载不放行 `mailto:`，严防恶意 `<img src="file:///...">` 或 `content://` 触发 Coil 本地 URI 读取）；
     - 渲染层与看图回调出口双层守卫：不通过白名单的图片**绝不向 `ImageLoader` 发起任何加载请求**，也不将非法 URL 交给 `onOpenImageViewer`，原地展示安全的 `图片加载失败` 静态占位；为每种危险 scheme（`file:`、`content:`、`javascript:`、`intent:`、`data:`、`mailto:` 及大小写/空白/控制字符变体）编写反例单测。
  3. **预占位、比例缓存与三分支精准无跳动补偿（闭合 R-BATCH5 B4-E2b，`ImageAspectRatioCache.kt`、`PostImageBlock.kt`）**：
     - 占位宽高比解析顺序：优先命中内存 LRU `ImageAspectRatioCache`（按规范化图片 URL 键控）→ 其次读取图片已知尺寸/解码后真实尺寸 → 默认 `4:3` 占位（防极窄/极长异常比例失控做合理上下界钳制）；
     - 经 `currentHiImageLoader()`（测试可经 `LocalHiImageLoader` 100% 离线注入假 `ImageLoader`）按实际容器像素显示尺寸请求解码（严禁无界解码原图），加载成功后写入 `ImageAspectRatioCache` 并以 `HiMotion.effectsDefault()` 淡入（减动效 0ms 到位）；
     - **三分支滚动补偿逻辑**：依赖 `E2a` 固定的 `floorPostId` 与 `LazyColumn` 项键 `"floor:$floorPostId"` 定位当前楼层在 `listState.layoutInfo.visibleItemsInfo` 中的位置：
       - **分支 ①（首可见楼层上方）**：当某张图片加载完成改变高度、且其所属楼层位于当前可见首楼**上方**（或位于首楼内且该图片底缘已滚过视口顶边导致高度差压推首楼）时，同步补偿 `LazyListState` 滚动距离，保证当前首可见楼层距屏幕顶部偏差 **`≤ 1dp`**；
       - **分支 ②（首楼自身未越顶或首楼下方）**：图片在视口内或下方改变高度时，**绝不误滚**（首可见楼层距顶偏差严格 **`== 0dp`**），容器高度以 `HiMotion.spatialFast()` 平滑过渡；
       - **分支 ③（滚出回收再滚回）**：再次进入视口时首帧直接命中 `ImageAspectRatioCache` 真实宽高比，不回退 `4:3`、无二次高度跳动。
  4. **自动流量节省模式与格式化（§7.9，`ImageLoadingPolicy.kt`、`ImageSizeFormatter.kt`）**：
     - 读取 Android `ConnectivityManager` 的计费网络状态（`isActiveNetworkMetered`）与流量节省程序状态（`restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_ENABLED`，允许通过内部纯函数/策略接口离线单测注入，**生产代码不加测试专用钩子**）：
       - 仅当“系统流量节省开启 **且** 当前为计费网络”时，默认进入点击加载模式，占位显示 `点击加载图片（{size}）`（`ContentBlock.Image` 无大小信息或 `<= 0` 时显示 `点击加载图片`，不带空括号）；
       - 点击后开始加载并显示确定进度圈（或可用进度，内容描述 `正在加载图片，点击取消`），加载中再次点击立即取消对应协程/Coil 请求并回到点击加载占位；
       - 非上述条件默认自动加载缩略图/常规尺寸（优先合法 `thumbUrl`，否则合法 `fullUrl`）。
  5. **错误重试、看图坐标上报与文案合规**：
     - 加载失败原地展示 `图片加载失败` 与 `重试` 按钮（触控目标 `≥ 48dp`，`hiPressScale` 反馈），点击重新触发加载；
     - 点击已加载图片（或允许直开看图的合法图）通过 `onGloballyPositioned` 采集图片在窗口内的 `Rect` 坐标，构造 `ImageViewerLaunchArgs(urls = listOf(safeFullUrl), sourceBoundsInWindow = bounds)` 透传 `onOpenImageViewer`；
     - 全部界面文案放入 `hipda/src/main/res/values/strings_thread_media.xml`，逐字取自 `docs/design/copy.md`，通过 `CopyTerminologyScanTest`（0 豁免）。
- **明确不做**：不改任何父文件（`PostBodyRenderer.kt`、`PostFloorCard.kt`、`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`、`strings_thread_detail.xml`）；不实现全屏看图器（M6 `E14`）；不改 `di/ImageLoaderModule.kt`、`feature/app/*`、`feature/common/*`、`ui/*`、`data/*`；不碰既有非 `thread_image_*` 快照。

【验收】
1. **三分支无跳动补偿与缓存回收（B4-E2b）**：`ImageScrollCompensationTest` 注入离线可控 `LocalHiImageLoader` 同测覆盖三分支——① 首楼**上方**图片由 `4:3` 变真长图：首楼距顶偏差 **`≤ 1dp`**；② 首楼**自身/下方**图片变高：首楼距顶偏差 **`== 0dp`（0 误滚）**；③ 滑出视口回收再滚回：首帧直接命中 `ImageAspectRatioCache` 真实比例不二次跳动。
2. **图片 URL 白名单全反例（`R-E2a R2-S1` + 界面卡安全条款）**：`PostImageBlockTest` 逐一验证 `file:///etc/hosts`、`content://media/external/images/1`、`javascript:alert(1)`、`intent://scan/#Intent;end`、`data:image/png;base64,AAA`、`mailto:a@b.com` 及大小写/前导空白/控制字符变体均被拦截：假 `ImageLoader` 请求次数严格 **`== 0`**、点击不触发 `onOpenImageViewer`、原地安全展示失败态；合法 `http://` 与 `https://` 正常加载。
3. **自动流量节省、进度取消、失败重试与看图边界上报**：`ImageLoadingPolicyTest` 与 `PostImageBlockTest` 覆盖 4 种网络/省流组合（仅省流+计费切点击加载）；验证带大小 `点击加载图片（{size}）` 与未知大小 `点击加载图片`、点击进入加载中（描述 `正在加载图片，点击取消`）再点取消；失败展示 `图片加载失败` + `重试`（`≥ 48dp`）点击恢复；点击已加载图片回调非零 `sourceBoundsInWindow`。
4. **截图基线（`PostImageScreenshotTest` + Roborazzi）**：录制并校验 6 张 `hipda/src/test/snapshots/images/thread_image_*.png`（`411`/`749`/`1280dp` × 浅/深，覆盖默认占位态、已加载态、失败重试态与省流点击加载态）；既有非 `thread_image_*` 快照 0 漂移。
5. **零父文件改动与全量门禁**：`git diff --name-status 3dd65c4...HEAD` 确认生产改动严格限于 `feature/threaddetail/media/` 与 `strings_thread_media.xml`；`CopyTerminologyScanTest`、`MotionTokenScanTest`（白名单 0 增长）、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 在本地与 GitHub Actions 全绿。

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
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/E2b.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。任何把站点内容（帖子、短消息、签名、链接、图片）交给系统或 WebView 打开、加载的代码，必须走 scheme 白名单，只允许 `http`、`https`，以及经过校验的 `mailto`；每种危险 scheme 都要有反例测试。

【改哪些文件】
- 修改（生产，严格独占 `feature/threaddetail/media/`，零改任何父文件）：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt`
- 新增（生产）：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicy.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageAspectRatioCache.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageSizeFormatter.kt`
  - `hipda/src/main/res/values/strings_thread_media.xml`
- 新增（测试与截图基线）：
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlockTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicyTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageScrollCompensationTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/PostImageScreenshotTest.kt`
  - `hipda/src/test/snapshots/images/thread_image_*.png`（6 张）
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/E2b.md`

【负责人裁定（开工前 NEEDS-INPUT 后）】
- 方案 A：授权改 `PostBodyRendererTest.kt` 图片相关用例（注入离线 `LocalHiImageLoader`，断言改为本卡行为）与 `ThreadDetailScreenshotTest.kt`（注入确定性离线 `LocalHiImageLoader`，0 网络并在报告给出证明）；允许重录受影响的 9 张 `thread_detail_*.png`，报告逐张列图名与原因，图片区域以外不得变化。
- `thread_detail_image_placeholder`：与 copy.md 逐字一致则复用，否则删除该行，新串放 `strings_thread_media.xml`。
