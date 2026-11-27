# 独立代码审查报告：R-M7-P1（看图与帖内图片收尾）

- **审查结论**：**`APPROVE`**（**0 项阻断**，3 项非阻断建议 `S1`–`S3`）
- **审查对象**：`origin/card/M7-P1-image-polish` @ `fc63c2da0bffa0f18339a93dbeee0c0bedea73c8`（基线 `origin/main` @ `0f067be`）
- **GitHub Actions CI**：[Android CI #45949989116](https://github.com/mailwmj/hipda-next/actions/runs/45949989116)
  - `headSha == fc63c2da0bffa0f18339a93dbeee0c0bedea73c8`，`status == completed`，`conclusion == success`（Job `140343363292`，耗时 `15m54s`）；
  - 已核实通过：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（全仓 `1696/1696` 通过，`Check for Roborazzi diff images` 因 0 差异自动 `skipped`）、`Verify APK outputs`。
- **代码规模与边界**：仅改动允许范围内的 9 个生产文件（`+196 / -145`，净增 **`+51` 行**；其中 a+b 约 `+108` 行 ≤ 卡片 `120` 行估计，c 项纯重构删减 `-57` 行）与 5 个测试文件；`hipda/src/test/snapshots/` **0 变化**；0 手写 `Layout`/`Modifier.Node`/状态机，0 测试专用钩子/语义键/`CompositionLocal`。
- **只读纪律核验**：主目录 `/home/mir/Documents/hipda-next` 全程零 checkout、零写文件、零提交、零编译；临时复现 worktree `/home/mir/Documents/hipda-next.wt/R-M7-P1` 按 `DISPATCH.md` §2 `flock` 串行执行后已清理移除。

---

## 1. 阻断项（依 `docs/agents/DISPATCH.md` §4 与 `M7-P1` 验收 A1–A6）

- **无（0 项）**。

---

## 2. 审查卡 `R-M7-P1` 五项重点逐项核查

### 重点 1：HD 直开 4096 上限复用、双轴封顶与普通图/小图清晰度
1. **同一常量与同一计算函数复用**：
   - 常量唯一位于 [ImageViewerGeometry.kt:22](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerGeometry.kt#L22)（`ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX = 4096`）。
   - 计算函数唯一位于 [ImageViewerGeometry.kt:29-45](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerGeometry.kt#L29-L45)（`computeOriginalDecodeBounds`）：内部统一先转 `Long` 防整型乘法溢出，再对 `width` 与 `height` 双轴均执行 `.coerceAtMost(maxDim)`（`4096L`）封顶。
   - [ImageViewerPage.kt:168,179-182,197](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerPage.kt#L168-L198) 三阶段全部复用该函数：
     - 缩略图预显（`:168`）：`computeOriginalDecodeBounds(viewportW, viewportH, widthMultiplier = 1, heightMultiplier = 1)`；
     - HD 直开（`:179-181`）：`computeOriginalDecodeBounds(viewportW, viewportH, widthMultiplier = 1, heightMultiplier = 1, minHeightWidthMultiplier = 6)`（`2560×1600` 视口得 `2560×4096`，`5000×5000` 视口得 `4096×4096`，双轴严格 `≤ 4096`）；
     - 查看原图解码（`:197`）：`computeOriginalDecodeBounds(viewportW, viewportH)`（默认 `2×` 倍率并封顶 `4096`，与改动前 100% 一致）。
2. **普通图与小图清晰度零受损**：
   - [ImageViewerPage.kt:86-91](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerPage.kt#L86-L91) 构造 `ImageRequest` 使用 `.size(Dimension.Pixels(maxW), Dimension.Pixels(maxH))` + `.scale(Scale.FIT)` + `.precision(Precision.INEXACT)`。
   - 按 Coil 3 `Precision.INEXACT` 语义，当源图宽高小于或等于请求上限时解码倍率为 `1.0×`（小图既不被缩小，也不会在解码期被强行放大糊化）；常规横图/竖图（如 `1920×1080`、`1280×960` 乃至 `1000×3500`）在手机/平板视口下的 `Scale.FIT` 限制因子与改动前完全相同，仅超长/超大位图（`> 4096px`）被安全降采样以避免 `Canvas: trying to draw too large bitmap` 崩溃。

### 重点 2：逐页 `floorNumber`/`author`、三场景坐标键唯一性与离屏回收防残留
1. **顶栏逐页元数据切换**：
   - [ReadingNavContracts.kt:63-86](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/ReadingNavContracts.kt#L63-L86) 新增 `@Immutable data class ImageViewerPageMeta(floorNumber, author, boundsKey)` 与 `ImageViewerLaunchArgs.pageMeta`。
   - `floorNumberForPage(page)` / `authorForPage(page)` 使用下标判断 `if (page in pageMeta.indices) pageMeta[page].xxx else xxx`：当 `pageMeta` 非空且某页字段为 `null` 时不会误串回初始页的楼层/作者；当单图入口未传 `pageMeta`（`emptyList()`）时回退顶层 `floorNumber`/`author`。
   - [ImageViewerOverlay.kt:314-321,425](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/imageviewer/ImageViewerOverlay.kt#L314-L321) 随 `pagerState.currentPage` 实时计算 `topTitle` 喂给 `ImageViewerTopBar`。
2. **三种场景下坐标键的唯一性与稳定性**：
   - **① 同一 URL 多楼层**：[ThreadImageGallery.kt:38-46](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ThreadImageGallery.kt#L38-L46) 与 [PostImageBlock.kt:279](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt#L279) 均生成 `imageViewerBoundsKey(floorPostId, imageIndexInFloor)`（`"$floorPostId#$imageIndexInFloor"`，如 `"p1#0"` 与 `"p3#0"`），按 Discuz 全局唯一 `pid` 隔离。
   - **② 同楼多图（含同楼重复 URL）**：同楼不同 URL 直接通过 `matchingGallery` 唯一命中各自的 `imageIndexInFloor`（`"p1#0"`、`"p1#1"`）；同楼内贴有多张完全相同 URL 时（`matchingGallery.size > 1`），[PostImageBlock.kt:270-287](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt#L270-L287) 经 `boundsRegistry.registerOccurrence("$floorPostId|$verifiedViewerUrl", layoutTracker)` 按 `Column` 顺序分配槽位 `0, 1, ...` 并映射至 `matchingGallery[dupSlot].imageIndexInFloor`，且非法 scheme 图片在 `extractThreadGalleryItems` 与 `PostImageBlock` 两侧同步跳过，序号严格对齐（帖内编辑局部增删块边界见建议 `S3`）。
   - **③ 单图入口（非图集）**：
     - 外部直接构造 `ImageViewerLaunchArgs(urls = listOf(url), sourceBoundsInWindow = rect, floorNumber = 5, author = "solo")` 时：`boundsKeyForPage(0) == null`，[FeaturePaneSlots.kt:173-184](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/FeaturePaneSlots.kt#L173-L184) `trackedAtOpen[0] == false`，退场走 `idx == args.initialIndex -> args.sourceBoundsInWindow`，行为 100% 不变；
     - 未挂载 `LocalThreadImageGallery` 的独立 `PostImageBlock`：`boundsKey` 取 `"$floorPostId#$verifiedViewerUrl"` 并经 `fallbackBoundsKey` 写入单元素 `pageMeta`，兼顾单图回退与实时坐标追踪。
3. **楼层离屏回收与翻页无过期坐标残留**：
   - `coordsByKey` 仅存实时 `LayoutCoordinates` 引用（不缓存静态 `Rect`）。
   - 楼层滑出 `LazyColumn` 回收或切换筛选/跳页卸载时，[PostImageBlock.kt:284-287](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt#L284-L287) `DisposableEffect.onDispose` 立即按 `coordsByKey[key] === coords` 精确移除（空坐标分支小建议见 `S2`）；[FeaturePaneSlots.kt:160-170](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/FeaturePaneSlots.kt#L160-L170) `resolveVisibleBounds` 再叠加 `!coords.isAttached` 与 `visible.width < w - 2f || visible.height < h - 2f` 双重校验，离屏或边缘裁切一律返回 `null` 走原地缩回淡出兜底。
   - 连续翻页（`loadPreviousPage` 向上插页）期间：因 `imageIndexInFloor` 为楼内局部序号而非全帖全局下标，新页合入不会改变已打开楼层缩略图的 `boundsKey`，实时坐标追踪保持连续。

### 重点 3：`feature/common/` 全量调用方核查与 `M7-P2` / `origin/main` `git merge-tree`
1. **全仓调用点核查（`rg` 覆盖 `hipda/src` 与 `baselineprofile/src`）**：
   - `ImageViewerLaunchArgs` 在末尾追加带默认值的 `pageMeta: List<ImageViewerPageMeta> = emptyList()`，保持原 5 参数顺序与默认参数二进制/源码向后兼容；生产调用点（`NextAppRoot.kt`、`ThreadDetailPane.kt`、`PostFloorCard.kt`、`PostBodyRenderer.kt`、`PostImageBlock.kt`、`ThreadImageGallery.kt`、`FeaturePaneSlots.kt`、`ImageViewerOverlay.kt`）与全部既有单测/截图测试（`ImageViewerScreenshotTest`、`ImageViewerShellMergeTest`、`ImageViewerMultiImageGalleryTest`、`ImageViewerHardeningRegressionTest`、`ImageViewerSchemeSecurityTest`、`ImageViewerGestureAndFeaturesTest`、`ThreadImageGalleryTest`、`PostImageBlockTest`、`FloorActionsAndDetailWiringTest`）零受影响。
   - `ImageViewerBoundsRegistry` 公开方法签名（参数类型与返回值）完全未变，仅形参名由 `url` 语义化为 `key`，全仓无 `url =` 具名调用方。
2. **并行分支合并冲突核查**：
   - **与 `origin/card/M7-P2-links-polish`（`8d1737933e88081495470221b8c12b8c0402f3e4`）**：
     - `git merge-tree --write-tree origin/card/M7-P1-image-polish origin/card/M7-P2-links-polish` → **`exit=0`**（合成树 `e48924d675fe3ac11a8c65cfabe6456702b0a69a`），**0 文本冲突、0 语义冲突**。
     - `M7-P1` 未触碰 `NextAppRoot.kt` 与 `ThreadDetailPane.kt`；两卡唯一交集文件为 `ReadingNavContracts.kt`（`M7-P2` 仅改第 28 行 `ThreadNavArgs.navSeq`，`M7-P1` 仅改第 63–86 行 `ImageViewerPageMeta`/`ImageViewerLaunchArgs`），人工核对合成树 `ReadingNavContracts.kt` 完全正确。
   - **与最新 `origin/main`（`72472e1cc77a82c6f44ebf142c94386ba5f2c150`）**：
     - `git merge-tree --write-tree origin/main origin/card/M7-P1-image-polish` → **`exit=0`**（合成树 `0e3eba55e2824ba2104afa3d2383475f2c234be4`），**0 冲突**。

### 重点 4：c 项 `media/` 纯重构核对与 4 个既有测试断言核查
1. **被删重载/包装逐项行为零变化核实**：
   - [ImageAspectRatioCache.kt:18-30](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageAspectRatioCache.kt#L18-L30)（`68 → 54` 行，`-14`）：删除零生产调用的 `get(url)`、`size`、`put(url, Float)` 与 `clampAspectRatio`；因 `put(url, width, height)` 前置已拦截 `<= 0` 与非有限浮点，内联 `ratio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)` 与原逻辑 100% 等价。
   - [ImageLoadingPolicy.kt:51-63](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicy.kt#L51-L63)（`80 → 63` 行，`-17`）：删除零生产调用的 `ImageNetworkState`、`resolveLoadMode(ImageNetworkState)` 与 `readNetworkState` 重载；`resolveForContext` 保留完全相同的空服务兜底、`runCatching` 防护与 `isActiveNetworkMetered && RESTRICT_BACKGROUND_STATUS_ENABLED` 判定。
   - [PostImageBlock.kt](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt)（c 项纯删减约 `-26` 行，三文件合计 `-57` 行）：
     - `InvalidImagePlaceholder` 合并入 `PostImageLoadState.Failed(canRetry = false)`（`:189, 367-373, 431-457`）：`displayUrl == null` 时不触发 `currentHiImageLoader()`、不查改比例缓存、不注册 `boundsRegistry`、不显示重试按钮，外层 `Surface`（`4:3`、`HiShapeTokens.Medium`、`surfaceContainerLow`、`0.5.dp outlineVariant`）与居中失败文案完全一致；
     - `computeImageBottomInViewport`（`:128-143`）双循环合并为单趟向上遍历：优先立即返回首个满足 `abs(localY - expectedItemTop) <= 2f` 的视口尺寸祖先，未命中时回退遍历中记录的首个 `parent.size == viewportSize` 祖先，再回退 `positionInRoot()`，数学结果逐像素恒等；
     - 去掉 `Triple` 与 `nextLoadId`，改用 4 键 `LaunchedEffect(displayUrl, loadToken, imageLoader, hasMeasuredWidth)` 与有符号 `loadToken`（`:183-185, 197-198, 220, 354, 360, 371`）：自动加载 `1`、省流待机 `0`、取消/失败置 `-abs(loadToken)`（变更键值立即取消在途协程且新协程因 `<= 0` 直接退出）、点击加载/重试置 `abs(loadToken) + 1`（严格递增正数重触发），状态机转换严密闭合。
2. **4 个既有测试断言零放宽核实**：
   - [ContinuousPagingAnchorTest.kt:1106,1248](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ContinuousPagingAnchorTest.kt#L1106)、[ThreadInteractFrameAnchorTest.kt:301](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/interact/ThreadInteractFrameAnchorTest.kt#L301)、[ImageScrollCompensationTest.kt:186](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageScrollCompensationTest.kt#L186)：`cache.get(url)!!` 改为 `cache.resolveInitialAspectRatio(url)`，期望值 `0.5f` / `1f/3f` 与未命中默认值 `4f/3f` 相差 `> 0.83f`，断言容差 `0.001f` 完全未变。
   - [ImageLoadingPolicyTest.kt:59-96,220-254](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/media/ImageLoadingPolicyTest.kt#L59-L96)：完整保留 4 组布尔表与 5 组 `ConnectivityManager`（含 `WHITELISTED`）断言，且在 `imageAspectRatioCache` 测试中用 `resolveInitialAspectRatio` 验证 LRU 保活与淘汰回退 `4:3`，并新增 `fallbackUrl` 命中、零高、负宽与空白 URL 反例，覆盖强度不降反升。

### 重点 5：A4 真实全链路核实与 A1/A2/A3 单行变异验证
1. **A4 真实链路核实（通过）**：
   - [ImageViewerPolishTest.kt:541-666](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/imageviewer/ImageViewerPolishTest.kt#L541-L666) 两条 A4 测试均直接挂载真实生产组件链：`NextAppRoot`（自建 `ImageViewerBoundsRegistry` 与 `createResolver`）→ `HiAdaptiveShell` → `MultiPaneReadingContainer` → `ThreadDetailPane`（自建 `extractThreadGalleryItems` 提供 `LocalThreadImageGallery`）→ `PostFloorCard` → `PostBodyRenderer` → `PostImageBlock` → `ImageViewerRendererImpl` → `ImageViewerOverlay`，绝非测试替身拼装。
2. **A1 / A2 / A3 改坏一行变红核实**：
   - **A1（必红）**：去掉 `computeOriginalDecodeBounds` 的 `.coerceAtMost(maxDim)` 或改回 `(viewportW * 6).coerceAtLeast(viewportH)`，`a1_hdDirectOpenOn2560pxViewportWith1to6LongImage_capsRequestAndDecodeAt4096` 因 `reqH = 15360 > 4096`、`decodedHeight = 6000 > 4096` 立即失败。
   - **A2（必红）**：将 `ImageViewerOverlay.kt:314` 改回 `args.floorNumber`，`a2_twoFloorsTwoImagesEach_*`（`:385`）与 `a4_realNextAppRoot_*_crossFloorSwipe_*`（`:588`）在滑至 `3 / 4` 时因找不到 `"第 2 楼 · bob"` / `"第 2 楼 · 回复乙"` 立即失败。
   - **A3（发现后写覆盖巧合盲区，列为建议 `S1`）**：
     - 在隔离 worktree `/home/mir/Documents/hipda-next.wt/R-M7-P1` 中将 [FeaturePaneSlots.kt:123](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/FeaturePaneSlots.kt#L123) 改为常量 `"COLLIDED_KEY"`（模拟所有楼层/同楼多图退化回共享同一键）并执行 `ImageViewerPolishTest`，实测结果 **`8 tests, 8 passed, 0 failed`（`MUTATION_TEST_EXIT=0`）**。
     - **根因**：`ImageViewerBoundsRegistry.update`（`coordsByKey[key] = coords`）为后写覆盖；Compose 自上而下布局时 3 楼（及同楼第 2 张）`thumbs[1]` 晚于 1 楼 `thumbs[0]` 写入，故键冲突时注册表恰好保留 `thumbs[1]` 的坐标。而卡面 A3 原文指定“从 3 楼那张进入、直接关闭”，测试（`:466, :530, :649`）遂全部只点击了 `thumbs[1]`。
     - **定性**：生产代码本身对 1 楼（`"p1#0"`）与 3 楼（`"p3#0"`）均已正确隔离无缺陷，且字面满足 `M7-P1.md` A3 验收描述，依派卡规则不作合并阻断；建议在合入或 `OPS fixcheck` 时按 `S1` 补 6 行点击 `thumbs[0]`（1 楼 / 第 1 张）或横滑至 `1 / 2` 关闭的反向断言即可彻底锁死。

---

## 3. 通用清单与验收项核对汇总

| 维度 | 结果 | 证据 |
|---|---|---|
| **A1–A6 卡片验收** | **全部达成** | A1–A4 见 `ImageViewerPolishTest`（8/8 通过）；A5 报告已列逐文件行数对比且 `src/test/snapshots/` 0 变化；A6 CI `#45949989116` 全绿。 |
| **Scheme 安全白名单** | **通过** | `PostImageBlock`、`extractThreadGalleryItems`、`buildGalleryLaunchArgs`、`ImageViewerOverlay`/`ImageViewerPage` 保持四重 `http`/`https` 白名单校验，既有全套危险 scheme 反例测试全绿。 |
| **动效、术语与零真站请求** | **通过** | 未改任何字符串 XML 与 `MotionTokenScanTest` 白名单；`ImageViewerPolishTest` 仅用本地 `MockWebServer` 与内存 `ViewerTestImageLoader`，0 外网请求。 |
| **Roborazzi 截图复验** | **通过** | `git diff --name-status 0f067be...fc63c2d -- hipda/src/test/snapshots/` 为空（0 新增/修改快照，CI `-Proborazzi.test.verify=true` 0 漂移）。 |

---

## 4. 非阻断建议（Suggestions）

1. **S1（高优测试补强 — 补齐 A3/A4 重复 URL 点击首张缩略图的反向退场断言，消除后写覆盖盲区）**：
   - **位置**：[ImageViewerPolishTest.kt:466,530,649](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/test/java/net/jejer/hipda/feature/imageviewer/ImageViewerPolishTest.kt#L466)
   - **原因**：如重点 5 实测，因 `coordsByKey` 后写覆盖，`thumbs[1]`（3 楼 / 同楼第 2 张）恰好是最后布局写入的一方；仅测“点 `thumbs[1]` 直接关闭”在 `imageViewerBoundsKey` 退化为常量或按 URL 索引时仍会通过。
   - **改法**：在 `a3_sameUrlInFloor1AndFloor3_*`、`a3_sameFloorDuplicateUrls_*` 与 `a4_realNextAppRoot_*_sameUrlInFloor1AndFloor3_*` 末尾各追加一步点击 `thumbs[0]`（1 楼 / 第 1 张）并断言 `dismissAndCaptureFinalRect("1 / 2")` 对齐 `floor1Bounds` / `firstBounds`（或从 `2 / 2` 右滑至 `1 / 2` 后关闭断言飞回 1 楼），约 6–10 行测试代码即可使键冲突改动 100% 变红。
2. **S2（防御性细节 — `ImageViewerBoundsRegistry.remove` 空坐标守卫）**：
   - **位置**：[FeaturePaneSlots.kt:136-140](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/FeaturePaneSlots.kt#L136-L140)
   - **说明**：当某 `PostImageBlock` 尚未完成首次布局（`layoutTracker.coords == null`，从未调用过 `update`）即被卸载时，`if (coords == null || coordsByKey[key] === coords)` 会无条件移除 `key`。生产中 `pid` 全局唯一故无害，但改为 `if (coords != null && coordsByKey[key] === coords) coordsByKey.remove(key)` 可避免未来跨容器同键过渡期未布局旧实例误删已布局新实例。
3. **S3（后续可简化项 — `PostBodyRenderer` 直传楼内图片序号替代 `duplicateSlots`）**：
   - **位置**：[PostImageBlock.kt:270-275](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt#L270-L275)、[FeaturePaneSlots.kt:129,145-157](file:///home/mir/Documents/hipda-next.wt/M7-P1/hipda/src/main/java/net/jejer/hipda/feature/common/FeaturePaneSlots.kt#L145-L157)
   - **说明**：受本卡不改 `PostBodyRenderer.kt` 边界限制，同楼内完全相同 URL 靠 `DisposableEffect` 注册顺序分配槽位；常规阅读、翻页、整楼回收再入均稳定，仅在“同楼含重复 URL 且用户编辑该楼层局部增删前置图片块”时未重跑的旧槽位可能失步。日后若触碰 `PostBodyRenderer.kt`，可在循环中直接计数传入 `imageIndexInFloor: Int = -1`，一步省去 `duplicateSlots` 约 25 行代码。
