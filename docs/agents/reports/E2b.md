# 任务卡 E2b 交付报告：帖子图片渲染（预占位、淡入、滚上无跳动补偿、省流点击加载与图片 URL 白名单）

- **状态**：`DONE`
- **Worktree**：`/home/mir/Documents/hipda-next.wt/E2b`
- **分支**：`card/E2b-thread-images`
- **基线提交**：`3dd65c4`（`origin/main`，已含 `E0`、`E15`、`E2a`）
- **最终提交（HEAD）**：`1597b4e260995db466d2c37c15c9afe3840ead9a`（`feat(threaddetail): 实现帖子图片按尺寸解码、无跳动滚动补偿、省流点击加载与URL白名单`）
- **GitHub Actions CI 证据**：
  - [Android CI #45698550909](https://github.com/mailwmj/hipda-next/actions/runs/45698550909) — `Build, Lint & Unit Tests`（Job `139502277220`）**SUCCESS**
  - 全仓单测与 Roborazzi 校验汇总：**`TOTAL: 1060 tests, 1060 passed, 0 failed, 0 errors, 0 skipped`**（含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room schema 一致性校验、`:core-parser:test`、`:emoji:testDebugUnitTest`、全量 `:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 及 Debug/Release APK 产物校验）
- **本地串行校验（遵循 `docs/agents/DISPATCH.md` §2，`flock /tmp/hipda-gradle.lock`，`-Xmx3g`，`workers.max=4`，结束后 `./gradlew --stop`）**：
  - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`962 tests, 962 passed, 0 failed, 0 skipped`**
    - `ImageLoadingPolicyTest`：6/6 通过
    - `PostImageBlockTest`：8/8 通过
    - `ImageScrollCompensationTest`：2/2 通过
    - `PostImageScreenshotTest`（Roborazzi verify）：6/6 通过（6 张 `thread_image_*.png` 均已逐张人工目视核验）
    - `PostBodyRendererTest`：6/6 通过（仅按裁定更新图片路由单测用例，其余 5 用例 0 改动）
    - `ThreadDetailScreenshotTest`（Roborazzi verify）：15/15 通过（9 张含 1 楼图片的基线按裁定重录并逐张像素框比对 + 目视核验；其余 6 张空/错/骨架及全仓非本卡快照 0 漂移）
    - `ThreadDetailViewModelTest`：8/8 通过（含 `PostImageBlock` 5 参函数引用签名锁死校验与 `floor:$postId` 键校验）
    - `CopyTerminologyScanTest`：3/3 通过（新增 `strings_thread_media.xml` 0 豁免）
    - `MotionTokenScanTest`：7/7 通过（白名单 0 新增）
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**；核查 `hipda/build/reports/lint-results-debug.sarif`，本卡全部文件与 `strings_thread_detail.xml` **0 条 Lint 告警/错误**。

---

## 1. 边界合规、裁定执行与生产代码行数

- **生产代码总行数（`hipda/src/main/`，4 个 Kotlin 文件 + 1 个字符串资源）**：`610` 行；相对基线 `E2a` 原 `PostImageBlock.kt`（46 行），**净增 `+564` 行**（接近卡面估计 `350–550` 行，远低于 `550 × 1.5 = 825` 行自查线）：
  - `feature/threaddetail/media/PostImageBlock.kt`：425 行（原 46 行，净增 +379）
  - `feature/threaddetail/media/ImageLoadingPolicy.kt`：80 行（新增）
  - `feature/threaddetail/media/ImageAspectRatioCache.kt`：68 行（新增）
  - `feature/threaddetail/media/ImageSizeFormatter.kt`：30 行（新增）
  - `res/values/strings_thread_media.xml`：7 行（新增）
- **零越界与界面卡约束核对**：
  - 生产改动严格独占 `feature/threaddetail/media/` 与 `strings_thread_media.xml`；**零改动** `ThreadDetailViewModel.kt`、`ThreadDetailPane.kt`、`PostFloorCard.kt`、`PostBodyRenderer.kt`、`strings_thread_detail.xml`、`di/*`、`feature/app/*`、`feature/common/*`、`ui/*`、`data/*` 与任何共享构建/清单文件，与并行 `E2d` 零生产文件重叠；
  - 严格保留 `PostImageBlock(image, floorPostId, listState, onOpenImageViewer, modifier)` 5 参签名，兼容 `ThreadDetailViewModelTest` 的 `::PostImageBlock` 引用检查；
  - 全部采用 Compose 标准组件（`Surface`、`Box`、`Column`、`Image`、`Text`、`FilledTonalButton`、`Icon`、`HiLoadingSpinner`、`hiSkeleton`、`hiPressScale`、`Animatable`、`animateFloatAsState`、`LaunchedEffect`、`onGloballyPositioned`），无手写 `Layout`、`Modifier.Node`、状态机或命令队列；
  - 生产代码零测试专用 `testTag` / `SemanticsPropertyKey` / 钩子 / `CompositionLocal`（`ConnectivityManager` 读取真实系统 API，测试通过 Robolectric `ShadowConnectivityManager` + `ShadowNetworkInfo` 驱动）；
  - **字符串复用（按裁定二）**：核对 `docs/design/copy.md:74`，既有 `strings_thread_detail.xml:11` 的 `thread_detail_image_placeholder`（`点击加载图片`）及 `strings_common.xml:4` 的 `common_action_retry`（`重试`）与 `copy.md` 逐字一致，直接复用，避免修改 `strings_thread_detail.xml` 且保持 Lint `UnusedResources` 为 0；其余 3 条新文案（`点击加载图片（%1$s）`、`正在加载图片，点击取消`、`图片加载失败`）放入 `strings_thread_media.xml`。
- **目视自查修复记录**：
  - 首轮录制 `thread_image_w411_{light,dark}.png` 后目视发现 2 列窄卡下「点击加载图片（250 KB）」折行后默认左对齐不够工整，当场为 `TapToLoadContent`、`FailedImageContent` 与 `InvalidImagePlaceholder` 文本补充 `textAlign = TextAlign.Center` 后重录验证。

---

## 2. `ThreadDetailScreenshotTest` 与全套测试「0 真实网络 / 0 DNS」证明（按裁定一.2）

1. **结构性无网络替身（`OfflineTestImageLoader`，`ImageLoadingPolicyTest.kt:211`）**：
   - 直接实现 `coil3.ImageLoader` 接口，**不构造 `RealImageLoader`、不引入 `OkHttpClient`、不触发 `ServiceLoader`、不注册任何网络 `Fetcher`**（`override val components = ComponentRegistry()` 为空表，`memoryCache = null`，`diskCache = null`）。
   - `execute(request)` 在纯内存中直接合成确定性 `Bitmap`（`createSyntheticBitmap`，400×300，4:3）返回 `SuccessResult`（或按测试场景返回 `ErrorResult`/挂起 `CompletableDeferred`），并记录每一次请求的 `ImageRequest`。
   - 相比封装 `RealImageLoader` + Interceptor，此方式在类结构层面彻底不存在任何 Socket、DNS 解析或 HTTP 栈代码路径，不可能发生静默回退到 `OkHttpNetworkFetcherFactory` 的逃逸。
2. **Compose 树全覆盖注入与断言验证（`ThreadDetailScreenshotTest.kt:231-294`）**：
   - `ThreadDetailScreenshotTest.captureDetail` 在根部通过 `CompositionLocalProvider(LocalHiImageLoader provides offlineImageLoader)` 注入该替身，覆盖 `ThreadDetailPane` 下 `PostImageBlock` 与 `FloorAvatar` 的全部 `currentHiImageLoader()` 调用（且所有测试楼层 `avatarUrl = null`）。
   - 每条截图用例完成后均显式断言：
     - 含 1 楼示例图的场景：`offlineImageLoader.requestedUrls` 非空且全部严格等于 `"https://img.example.invalid/sample_photo.jpg"`；
     - 骨架态与错误态：`offlineImageLoader.requestedUrls` 严格为空列表；
     - 全部 15 条用例：`offlineImageLoader.enqueueCount.get() == 0`（无旁路异步入队）。
   - 配合 100% 离线 `SnapshotThreadDetailRepository` 与无网络 `SiteAccessGateway`（`createIdleGateway()` 从不发起请求），整套测试全程零 DNS、零外部网络连接。

---

## 3. 截图基线明细（6 张新增 + 9 张授权重录像素级边界核对，按裁定一.3）

### 3.1 新增 6 张 `thread_image_*.png`（`PostImageScreenshotTest`）
覆盖 `411×891dp` / `749×559dp` / `1280×800dp` × 浅/深，同屏 2×2 展示：**已加载态**、**默认骨架占位态**、**失败重试态（`图片加载失败` + `重试`）** 与 **省流点击加载态（`点击加载图片（250 KB）`，经真实 `ShadowConnectivityManager` 计费+省流路径触发）**：
- `hipda/src/test/snapshots/images/thread_image_w411_light.png`
- `hipda/src/test/snapshots/images/thread_image_w411_dark.png`
- `hipda/src/test/snapshots/images/thread_image_w749_light.png`
- `hipda/src/test/snapshots/images/thread_image_w749_dark.png`
- `hipda/src/test/snapshots/images/thread_image_w1280_light.png`
- `hipda/src/test/snapshots/images/thread_image_w1280_dark.png`

### 3.2 按裁定重录的 9 张 `thread_detail_*.png`（逐张漂移原因与像素框证据）
全部 9 张均因 1 楼 `ContentBlock.Image("https://img.example.invalid/sample_photo.jpg")` 由 `E2a` 静态文字桩（`点击加载图片`）升级为 `E2b` 默认自动加载渲染（通过确定性 4:3 离线合成位图保持与旧桩完全一致的 4:3 高度）；经逐像素比对 `3dd65c4` 与当前 PNG，**变化像素的外接矩形 100% 严格落在该 4:3 图片框内部，顶栏、正文、表情、链接、附件、2 楼与底栏像素 0 变动**：

| 截图文件名 | 尺寸（px） | 变化像素外接框 `bbox`（严格为 1 楼 4:3 图片区） | 漂移原因 |
|---|---:|---|---|
| `thread_detail_w411_light.png` | `822×1782` | `(32,399)..(789,967)`（758×569 = 4:3） | 1 楼图片区由旧静态占位桩变为已加载离线示例图（浅色） |
| `thread_detail_w411_dark.png` | `822×1782` | `(32,399)..(789,967)`（758×569 = 4:3） | 1 楼图片区由旧静态占位桩变为已加载离线示例图（深色） |
| `thread_detail_w411_oled_pure_black.png` | `822×1782` | `(32,399)..(789,967)`（758×569 = 4:3） | 1 楼图片区由旧静态占位桩变为已加载离线示例图（OLED 纯黑） |
| `thread_detail_w559_light.png` | `1118×1498` | `(32,401)..(1085,1191)`（1054×791 = 4:3） | 1 楼图片区由旧静态占位桩变为已加载离线示例图（559dp 浅色） |
| `thread_detail_w640_dark.png` | `1280×2048` | `(32,401)..(1247,1312)`（1216×912 = 4:3） | 1 楼图片区由旧静态占位桩变为已加载离线示例图（640dp 深色） |
| `thread_detail_pane411_filter_light.png` | `822×1118` | `(32,631)..(789,1117)`（底缘至视口底） | 只看楼主下 1 楼图片区由旧静态占位桩变为已加载离线示例图 |
| `thread_detail_pane448_dark.png` | `896×1392` | `(32,399)..(863,1022)`（832×624 = 4:3） | 448dp 详情栏 1 楼图片区由旧静态占位桩变为已加载离线示例图 |
| `thread_detail_pane680_light.png` | `1360×1600` | `(32,295)..(1327,1266)`（1296×972 = 4:3） | 680dp 全屏详情栏 1 楼图片区由旧静态占位桩变为已加载离线示例图 |
| `thread_detail_w411_step5_scale13_light.png` | `822×1782` | `(32,885)..(789,1453)`（758×569 = 4:3） | 第 5 档字号×1.3 下 1 楼图片区由旧静态占位桩变为已加载离线示例图 |

- 其余 6 张 `thread_detail_{empty_filter,error,skeleton}_*.png` 及全仓所有其他既有快照（`theme_specimen_*`、`shell_*`、`glass_*`、`login_*`、`challenge_*` 等）**0 改动、0 漂移**。

---

## 4. 验收项逐条对照

1. **三分支无跳动补偿与缓存回收（`B4-E2b`） — 通过**
   - `ImageScrollCompensationTest.allThreeBranches_aboveCompensatesWithin1dp_belowZeroScroll_recycleHitsCacheOnFirstFrame` 注入离线可控 `LocalHiImageLoader` 同测覆盖三分支：
     - ① 首楼内底缘已越过视口顶边的图片由 `4:3` 变为 `1:3`（`400×1200`），关闭 `mainClock.autoAdvance` 逐帧推进 8 帧断言首楼锚点与 2 楼距顶偏差每帧 **`≤ 1dp`**；另有 `branch1_floorStrictlyAboveFirstVisibleInTopContentPadding_keepsFirstVisibleAnchoredWithin1dp` 验证位于 `contentPadding.top`（`index < firstVisibleItemIndex`）的上方楼层图片变高时首可见楼层距顶偏差同样 **`≤ 1dp`**；
     - ② 首楼未越顶及下方楼层图片由 `4:3` 变 `1:3`：首可见楼层索引、滚动偏移与距顶坐标变化严格 **`== 0dp`（0 误滚）**，且图片高度真实按 `HiMotion.spatialFast()` 增高至 `1:3`；
     - ③ 滑至第 25 楼彻底回收 1 楼，并以未完成 `CompletableDeferred` 拦截重解码后滚回 1 楼：首帧尚未解码即命中 `ImageAspectRatioCache` 的 `1:3` 真实比例（无回退 `4:3`），放行后高度二次变化严格 **`0px`**。
2. **图片 URL 白名单全反例（`R-E2a R2-S1` + 界面卡安全条款） — 通过**
   - `ImageLoadingPolicyTest.sanitizeImageUrl_allowsOnlyHttpAndHttpsWithHostAndRejectsAllDangerousSchemes` 与 `resolveImageUrls_prefersSafeThumbForDisplayAndNeverLeaksDangerousFullOrThumb` 覆盖全部危险 scheme、大小写、空白、控制字符及混合非法 thumb/full 组合；
   - `PostImageBlockTest.dangerousSchemes_neverInvokeImageLoaderOrViewerAndRenderStaticFailure` 与 `mixedValidAndDangerousUrls_blocksDangerousUrlInBothLoaderAndViewerExit` 逐一验证 `file:///etc/hosts`、`content://media/external/images/1`、`javascript:alert(1)`、`intent://scan/#Intent;end`、`data:image/png;base64,AAA`、`mailto:a@b.com` 及大小写/前导空白/控制字符变体在 `PostImageBlock` 中：`ImageLoader` 请求次数严格 **`== 0`**、无重试按钮、点击不触发 `onOpenImageViewer`、原地静态展示 `图片加载失败`；合法 `http://` 与 `https://` 按容器有界像素尺寸（`Dimension.Pixels`）正常自动加载。
3. **自动流量节省、进度取消、失败重试与看图边界上报 — 通过**
   - `ImageLoadingPolicyTest` 覆盖全部 4 种 `(isMetered, isDataSaverEnabled)` 组合及 `RESTRICT_BACKGROUND_STATUS_WHITELISTED`；
   - `PostImageBlockTest` 覆盖仅「流量节省 + 计费网络」切点击加载（展示 `点击加载图片（250 KB）` 与未知大小 `点击加载图片`）、点击进入加载中（内容描述 `正在加载图片，点击取消`，`≥ 48dp`）、再点立即取消在途协程（`cancelledCount == 1`）回占位；覆盖失败展示 `图片加载失败` + `重试`（`≥ 48dp`，`hiPressScale`）点击恢复；覆盖点击已加载图片上报非空非零 `sourceBoundsInWindow`；覆盖 `withAnimatorDurationScale(0f)` 减动效立即到位；
   - `PostBodyRendererTest.postBodyRenderer_rendersAllSixContentBlocksAndRoutesImageToPostImageBlock` 按裁定同步更新，验证自动加载、带大小文案 `点击加载图片（2 KB）` 与非空 `sourceBoundsInWindow`。
4. **截图基线（`PostImageScreenshotTest` + 授权重录 9 张 `thread_detail_*.png`） — 通过**
   - 6 张 `thread_image_*.png` + 9 张授权更新 `thread_detail_*.png` 已录制、逐张目视核验并经本地与 CI `-Proborazzi.test.verify=true` 全绿通过；其余快照 0 漂移。
5. **零父文件改动与全量门禁 — 通过**
   - `git diff --name-status 3dd65c4...HEAD` 确认生产改动严格限于 `feature/threaddetail/media/` 与 `strings_thread_media.xml`；本地与 GitHub Actions `CopyTerminologyScanTest`、`MotionTokenScanTest`（白名单 0 增长）、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 全绿。

---

## 5. 对外 API 清单（签名 + 文件:行号，供 M6 `E14` 等复用）

包名：`net.jejer.hipda.feature.threaddetail.media`

### 5.1 帖子图片渲染组件（`PostImageBlock.kt`）
- `@Composable fun PostImageBlock(image: ContentBlock.Image, floorPostId: String, listState: LazyListState, onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit, modifier: Modifier = Modifier)` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt:140`

### 5.2 图片 URL 白名单与省流策略（`ImageLoadingPolicy.kt`）
- `fun sanitizeImageUrl(rawUrl: String?): String?` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicy.kt:16`
- `@Immutable data class ResolvedImageUrls(val displayUrl: String, val viewerUrl: String)` — `ImageLoadingPolicy.kt:30`
- `fun resolveImageUrls(image: ContentBlock.Image): ResolvedImageUrls?` — `ImageLoadingPolicy.kt:36`
- `enum class ImageLoadMode { AutoLoad, ClickToLoad }` — `ImageLoadingPolicy.kt:45`
- `@Immutable data class ImageNetworkState(val isMetered: Boolean, val isDataSaverEnabled: Boolean)` — `ImageLoadingPolicy.kt:52`
- `object ImageLoadingPolicy` — `ImageLoadingPolicy.kt:58`
  - `fun resolveLoadMode(isMetered: Boolean, isDataSaverEnabled: Boolean): ImageLoadMode` (`:59`)
  - `fun resolveLoadMode(networkState: ImageNetworkState): ImageLoadMode` (`:62`)
  - `fun readNetworkState(context: Context): ImageNetworkState` (`:65`)
  - `fun readNetworkState(connectivityManager: ConnectivityManager): ImageNetworkState` (`:71`)
  - `fun resolveForContext(context: Context): ImageLoadMode` (`:79`)

### 5.3 图片宽高比内存 LRU 缓存（`ImageAspectRatioCache.kt`）
- `class ImageAspectRatioCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES)` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageAspectRatioCache.kt:8`
  - `fun get(url: String): Float?` (`:18`)
  - `fun put(url: String, width: Int, height: Int): Float?` (`:21`)
  - `fun put(url: String, aspectRatio: Float): Float?` (`:27`)
  - `fun resolveInitialAspectRatio(displayUrl: String, fallbackUrl: String? = null): Float` (`:42`)
  - `fun clear()` (`:48`)
  - `val size: Int` (`:52`)
  - `companion object`: `DEFAULT_ASPECT_RATIO` (`4f / 3f`)、`MIN_ASPECT_RATIO` (`0.25f`)、`MAX_ASPECT_RATIO` (`4f`)、`DEFAULT_MAX_ENTRIES` (`512`)、`val Default: ImageAspectRatioCache`、`fun clampAspectRatio(ratio: Float): Float` (`:57-67`)

### 5.4 文件大小格式化（`ImageSizeFormatter.kt`）
- `object ImageSizeFormatter` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageSizeFormatter.kt:7`
  - `fun formatOrNull(bytes: Long): String?` (`:12`)

---

## 6. `git diff --stat 3dd65c4...HEAD`

```text
 .../threaddetail/media/ImageAspectRatioCache.kt    |  68 +++
 .../threaddetail/media/ImageLoadingPolicy.kt       |  80 ++++
 .../threaddetail/media/ImageSizeFormatter.kt       |  30 ++
 .../feature/threaddetail/media/PostImageBlock.kt   | 407 ++++++++++++++++-
 hipda/src/main/res/values/strings_thread_media.xml |   7 +
 .../feature/threaddetail/PostBodyRendererTest.kt   | 114 +++--
 .../threaddetail/ThreadDetailScreenshotTest.kt     |  28 +-
 .../threaddetail/media/ImageLoadingPolicyTest.kt   | 417 +++++++++++++++++
 .../media/ImageScrollCompensationTest.kt           | 320 +++++++++++++
 .../threaddetail/media/PostImageBlockTest.kt       | 497 +++++++++++++++++++++
 .../threaddetail/media/PostImageScreenshotTest.kt  | 214 +++++++++
 .../images/thread_detail_pane411_filter_light.png  | Bin 90369 -> 110593 bytes
 .../images/thread_detail_pane448_dark.png          | Bin 114363 -> 135125 bytes
 .../images/thread_detail_pane680_light.png         | Bin 125413 -> 183725 bytes
 .../snapshots/images/thread_detail_w411_dark.png   | Bin 163491 -> 181074 bytes
 .../snapshots/images/thread_detail_w411_light.png  | Bin 163782 -> 184382 bytes
 .../images/thread_detail_w411_oled_pure_black.png  | Bin 162679 -> 180571 bytes
 .../thread_detail_w411_step5_scale13_light.png     | Bin 141748 -> 161195 bytes
 .../snapshots/images/thread_detail_w559_light.png  | Bin 119617 -> 159153 bytes
 .../snapshots/images/thread_detail_w640_dark.png   | Bin 165664 -> 209698 bytes
 .../snapshots/images/thread_image_w1280_dark.png   | Bin 0 -> 64063 bytes
 .../snapshots/images/thread_image_w1280_light.png  | Bin 0 -> 66017 bytes
 .../snapshots/images/thread_image_w411_dark.png    | Bin 0 -> 35127 bytes
 .../snapshots/images/thread_image_w411_light.png   | Bin 0 -> 35609 bytes
 .../snapshots/images/thread_image_w749_dark.png    | Bin 0 -> 52193 bytes
 .../snapshots/images/thread_image_w749_light.png   | Bin 0 -> 54109 bytes
 26 files changed, 2140 insertions(+), 42 deletions(-)
```
