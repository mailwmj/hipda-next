# 独立代码审查报告 R-E2b：帖子图片渲染与图片 URL 白名单（commit 1597b4e）

- **审查结论**：**`APPROVE`**
- **审查对象**：`origin/card/E2b-thread-images` @ `1597b4e260995db466d2c37c15c9afe3840ead9a`（简称 `1597b4e`）
- **对比基线**：`3dd65c4b4a2a74306a3b98df0f008996709272ab`（简称 `3dd65c4`）
  - 与当前 `origin/main`（`00cb3c4`）无冲突预合并核验：`git merge-tree --write-tree origin/main 1597b4e` → `d411899`（**0 冲突**）
  - 与并行卡 `origin/card/E2d-thread-continuous-paging`（`c447d4a`）无冲突预合并核验：`git merge-tree --write-tree origin/card/E2d-thread-continuous-paging 1597b4e` → `c680c1b`（**0 冲突**）
- **GitHub Actions CI 核实**：[Android CI #45698550909](https://github.com/mailwmj/hipda-next/actions/runs/45698550909)
  - `headSha`：`1597b4e260995db466d2c37c15c9afe3840ead9a`（与分支 HEAD 100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139502277220`）：**SUCCESS**，核实 `.github/workflows/android.yml` 完整执行并通过 `./gradlew assembleDebug assembleRelease lintDebug --stacktrace`、Room schema 一致性校验、`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true --continue --stacktrace` 及 Debug/Release APK 包名与产物校验
- **阻断项**：**0 项**
- **非阻断建议项**：**4 项**（`S1`–`S4`）

---

## 一、阻断项（Blocking Issues）

**无阻断项（0 项）。**

对照 `docs/agents/DISPATCH.md` §4 与审查卡 `R-E2b.md` 全部阻断红线逐项核实：
- 图片 URL 加载（Coil 请求）与点击看图（`onOpenImageViewer`）两层均严格强制执行含非空 host 的 `http`/`https` 白名单，且全套危险 scheme 均有反例单测守住；
- 三分支滚动补偿在首楼越顶与顶部 `contentPadding` 上方保持阅读锚点偏差 `≤ 1dp`，在视口内及下方严格 `0dp` 零误滚，回收再入首帧命中 `ImageAspectRatioCache`；
- 按容器像素尺寸有界解码（`Dimension.Pixels(widthPx) × Dimension.Pixels(maxDecodeHeightPx)`），零 `Size.ORIGINAL` 原图无界解码；
- 生产代码改动 100% 严格限于 `feature/threaddetail/media/*` 与 `strings_thread_media.xml`（零碰 `E2d`/`E2c`/`E3` 父文件或共享文件，`PostImageBlock` 5 参签名完全兼容）；
- 9 张授权重录 `thread_detail_*.png` 像素变化 100% 严格落在原 1 楼 `4:3` 图片框内（框外变动像素数为 `0`），测试全程 `0` 真实网络；
- 生产代码无测试专用钩子、无手写 `Layout`/`Modifier.Node`。

---

## 二、负责人五项补充重点逐项深度核查

### 重点 1：文件隔离边界核查（只改 `media/` + `strings_thread_media.xml`，零碰 `E2d` 文件）

经 `git diff --name-status 3dd65c4...1597b4e` 全量核实，共 26 个文件变更：

| 类别 | 文件清单 | 核查结果 |
|---|---|---|
| **生产代码（5 个）** | `M feature/threaddetail/media/PostImageBlock.kt`<br>`A feature/threaddetail/media/ImageLoadingPolicy.kt`<br>`A feature/threaddetail/media/ImageAspectRatioCache.kt`<br>`A feature/threaddetail/media/ImageSizeFormatter.kt`<br>`A res/values/strings_thread_media.xml` | **✅ 100% 合规**。零触碰 `ThreadDetailViewModel.kt`、`ThreadDetailPane.kt`（`E2d` 独占）、`PostFloorCard.kt`、`body/PostBodyRenderer.kt`、`strings_thread_detail.xml`、`di/*`、`ui/*`、`data/*` 及任何构建/清单文件。`PostImageBlock` 顶层 5 参签名（`PostImageBlock.kt:140-146`）与基线完全一致。 |
| **本卡测试与截图（10 个）** | `A feature/threaddetail/media/{ImageLoadingPolicyTest,PostImageBlockTest,ImageScrollCompensationTest,PostImageScreenshotTest}.kt`<br>`A snapshots/images/thread_image_w{411,749,1280}_{light,dark}.png`（6 张） | **✅ 100% 合规**。6 张截图均已逐张目视复验（已加载、默认骨架占位、失败重试、省流点击加载 4 态齐全，窄屏折行居中工整）。 |
| **按负责人裁定授权更新的测试与快照（11 个）** | `M PostBodyRendererTest.kt`（仅改图片路由单测用例，其余 5 用例 0 改动）<br>`M ThreadDetailScreenshotTest.kt`（仅注入 `OfflineTestImageLoader` 并加离线断言）<br>`M snapshots/images/thread_detail_*.png`（9 张含 1 楼图片的基线） | **✅ 严格符合裁定授权范围**。其余 6 张 `thread_detail_{empty_filter,error,skeleton}_*.png` 及全仓所有其他快照 0 改动、0 漂移。 |

---

### 重点 2：图片 `http`/`https` 双层白名单与 `R-E2a` 同一套绕过手法穿透测试

#### 2.1 两层独立拦截链路核查
1. **第 1 层 — 渲染/加载入口（`ImageLoadingPolicy.kt:16-42` → `PostImageBlock.kt:147-151, 189-198`）**：
   - `PostImageBlock` 首行通过 `resolveImageUrls(image)` 分别对 `image.thumbUrl` 与 `image.fullUrl` 执行 `sanitizeImageUrl`：
     - **两者均不合法**：`resolvedUrls == null`，在 `:148-151` 直接早退渲染无点击事件、无重试按钮的静态 `InvalidImagePlaceholder`（`图片加载失败`），**根本不进入 `currentHiImageLoader()` 或 `LaunchedEffect`，Coil `ImageRequest` 构造次数与请求次数严格为 `0`**；
     - **一真一假混合（如恶意缩略图 `file:///etc/hosts` + 合法原图，或合法缩略图 + 恶意原图 `content://...`）**：`resolveImageUrls`（`:37-41`）在两侧均丢弃非法方并回退至合法方，确保非法 `thumbUrl` 绝不进 `displayUrl`（防触发 Coil 本地 `FileFetcher`/`ContentUriFetcher`），非法 `fullUrl` 绝不进 `viewerUrl`。
2. **第 2 层 — 点击看图出口（`PostImageBlock.kt:282-290`）**：
   - 在已加载图片的 `clickable` 回调中，调用 `onOpenImageViewer` 前**再次独立执行 `val verifiedViewerUrl = sanitizeImageUrl(viewerUrl) ?: return@clickable`**，即使未来重构绕过上层结构，出口仍独立阻断一切非 `http`/`https` 地址。

#### 2.2 按 `R-E2a` 二轮同一套绕过向量逐项穿透核验（联立 `ThreadDetailParser.kt:981-1067, 1127-1142`）

| 绕过手法（对齐 `R-E2a` 测试集 + Coil 特有本地 URI） | 解析器行为与 `sanitizeImageUrl` 拦截落点 | 结论与单测覆盖 |
|---|---|---|
| **危险本地/系统/脚本 scheme 及大小写混写**<br>`file:///etc/hosts`、`FILE://`、`content://media/...`、`CONTENT://`、`android.resource://...`、`javascript:alert(1)`、`JavaScript:`、`intent://...#Intent;end`、`INTENT://`、`data:image/png;base64,AAA`、`DATA:` | 即使攻击者通过 `<img id="aimg_1" file="file:///..." src="images/common/none.gif">` 绕过 `ThreadDetailParser:1020` 的 `data:image/` 过滤使原始危险串进入 `ContentBlock.Image.fullUrl`，`sanitizeImageUrl` 在 `ImageLoadingPolicy.kt:20` 强制要求 `http://`/`https://` 前缀，全部直接返回 `null`。 | **已拦截**（`ImageLoadingPolicyTest:126-140`、`PostImageBlockTest:91-105, 156-175`） |
| **`mailto:` 协议（图片严禁放行，严于正文链接）**<br>`mailto:a@b.com`、`MAILTO:a@b.com`、`mailto:javascript:alert(1)` | 与 `PostBodyRenderer.sanitizePostUrl` 不同，`sanitizeImageUrl` 完全移除了 `mailto:` 分支（`:20` 直接返 `null`）。 | **已拦截**（`ImageLoadingPolicyTest:141-142`、`PostImageBlockTest:104-105`） |
| **前导/尾随/内嵌空白与控制字符**<br>`" file:///..."`、`"\tcontent://..."`、`"javascript:...\n"`、`"java\nscript:..."`、`" https://..."`、`"https://...\u0000..."`、`"http://evil .com"` | `:18` 拒绝首尾任意空白字符（含 `\u00A0`/`\u3000`）；`:19` `any { it.isISOControl() }` 拒绝全串 `\u0000..\u001F` 与 `\u007F..\u009F`；主机名内嵌空格被 `:23` `toHttpUrlOrNull()` 拒绝。 | **已拦截**（`ImageLoadingPolicyTest:128-136, 150-154`、`PostImageBlockTest:94, 99, 109`） |
| **URL 编码 / HTML 实体编码危险 scheme**<br>`file%3a///etc/hosts`、`&#102;ile:///etc/hosts`、`java%73cript:alert(1)` | 直传 `sanitizeImageUrl` 因不以 `http(s)://` 开头在 `:20` 返 `null`；经 `ThreadDetailParser` 时 HTML 实体解码后 `file://` 被 `:20` 拦截，百分号串被 `resolveForumUrl` 拼为站内 `https://www.4d4y.com/forum/...` 普通 HTTP 路径，不会落入本地文件/脚本通道。 | **已拦截** |
| **协议相对、无 scheme、空 host 与反斜杠畸形地址**<br>`//evil.com/a.jpg`、`///etc/hosts`、`http://`、`https://`、`http:\\evil.com`、`relative/a.jpg` | 直传时无 `http(s)://` 在 `:20` 返 `null`；若 `///etc/hosts` 经 `resolveForumUrl` 变为 `https:///etc/hosts`，在 `:23-24` 因 `toHttpUrlOrNull() == null` / `host.isBlank()` 返 `null`；`http:\\evil.com` 在 `:20` 返 `null`。 | **已拦截**（`ImageLoadingPolicyTest:145-149`、`PostImageBlockTest:106-107`） |

---

### 重点 3：9 张重录 `thread_detail_*.png` 逐张像素级比对（对比基线 `3dd65c4`）

使用独立脚本提取 `git show 3dd65c4:<png>` 与 `git show 1597b4e:<png>` 全分辨率 RGBA 像素矩阵逐像素比对，并独立扫描 `3dd65c4` 旧图中 1 楼原 `PostImageBlock` `4:3` 占位卡片边界 `old_placeholder_box`：

| # | 截图文件名 | 图像尺寸（px） | `3dd65c4` 旧 1 楼图片框坐标 | `1597b4e` 变化像素外接框 `diff_bbox` | 变化像素数 | **图片框外变化像素数** |
|---:|---|---:|---|---|---:|---:|
| 1 | `thread_detail_w411_light.png` | `822×1782` | `(32,399)..(789,967)` | `(32,399)..(789,967)` | 428,419 | **`0`** |
| 2 | `thread_detail_w411_dark.png` | `822×1782` | `(32,399)..(789,967)` | `(32,399)..(789,967)` | 428,422 | **`0`** |
| 3 | `thread_detail_w411_oled_pure_black.png` | `822×1782` | `(32,399)..(789,967)` | `(32,399)..(789,967)` | 428,420 | **`0`** |
| 4 | `thread_detail_w559_light.png` | `1118×1498` | `(32,401)..(1085,1191)` | `(32,401)..(1085,1191)` | 829,795 | **`0`** |
| 5 | `thread_detail_w640_dark.png` | `1280×2048` | `(32,401)..(1247,1312)` | `(32,401)..(1247,1312)` | 1,104,508 | **`0`** |
| 6 | `thread_detail_pane411_filter_light.png` | `822×1118` | `(32,631)..(789,1117)` | `(32,631)..(789,1117)` | 367,297 | **`0`** |
| 7 | `thread_detail_pane448_dark.png` | `896×1392` | `(32,399)..(863,1022)` | `(32,399)..(863,1022)` | 516,028 | **`0`** |
| 8 | `thread_detail_pane680_light.png` | `1360×1600` | `(32,295)..(1327,1266)` | `(32,295)..(1327,1266)` | 1,254,945 | **`0`** |
| 9 | `thread_detail_w411_step5_scale13_light.png` | `822×1782` | `(32,885)..(789,1453)` | `(32,885)..(789,1453)` | 428,419 | **`0`** |

- **结论**：9 张重录图的 `diff_bbox` 与 `3dd65c4` 原 1 楼 `4:3` 图片占位框精确到 1px 完全重合，**图片框以外全部区域（透明玻璃顶栏、只看楼主胶囊、1 楼作者/正文/表情/调色/链接/附件、2–4 楼与底部分页栏）变化像素严格为 `0`**。
- **0 真实网络核实**：`OfflineTestImageLoader`（`ImageLoadingPolicyTest.kt:248-314`）直接实现 `coil3.ImageLoader` 接口（空 `ComponentRegistry()`，不含 `RealImageLoader`、`OkHttpClient` 或 `OkHttpNetworkFetcherFactory`），`ThreadDetailScreenshotTest.kt:283-292` 对每张截图均断言离线请求命中与 `enqueueCount == 0`。

---

### 重点 4：三分支滚动补偿机制及与 `E2d` 向上插页锚定的合并交互分析

#### 4.1 `E2b` 自身三分支滚动补偿与边界核实（`PostImageBlock.kt:69-130, 211-243`）
1. **视口上方变高/变矮（保持正在读的位置不动，`≤ 1dp`）**：
   - **子情况 1a（图片所属楼层位于 `firstVisibleItemIndex` 之上，例如停留在透明顶栏背后的 `beforeContentPadding` 区域内，`floorItem.index < firstVisibleIndex`）**：命中 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP`（`:94, 230-233`），直接 `aspectRatioAnim.snapTo(cachedRatio)` **且不调用 `requestScrollToItem`**。原因：Compose `LazyColumn` 以 `(firstVisibleItemIndex, firstVisibleItemScrollOffset)` 为测量锚点向上排版 `index < firstVisibleIndex` 的项，高度变化已由 `LazyColumn` 自动向上吸收，若再手动增加 `scrollOffset` 反而会错误下滚首可见楼层（`ImageScrollCompensationTest.kt:248-319` 已专门覆盖此 `contentPadding(top = 80.dp)` 边界）。
   - **子情况 1b（图片位于 `firstVisibleItemIndex` 楼层内部，且图片自身底缘已滚过内容区顶线 `beforeContentPadding`，`imageBottomInViewportPx <= 0.5f`）**：命中 `ABOVE_VIEWPORT_COMPENSATE`（`:95-96, 224-229`），`aspectRatioAnim.snapTo(cachedRatio)` 并调用 `listState.requestScrollToItem(listState.firstVisibleItemIndex, (firstVisibleItemScrollOffset + deltaPx.roundToInt()).coerceAtLeast(0))`。
     - 经字节码核实，`LazyListState.requestScrollToItem` 同步更新 `scrollPosition` 快照状态且设 `forceRemeasure = false`：下一帧组合阶段 `PostImageBlock` 切换新宽高比后，测量阶段在同一帧同时应用新高度与新 `scrollOffset`，既不会因旧楼层高度较矮而提前截断 `targetOffset`，也天然支持**同一首楼内多张越顶图片同帧完成时的增量累加**，以及宽图（`16:9`，`deltaPx < 0`）变矮时的双向精确补偿。
     - 当首楼内上方图片已越顶、下方第 2 张图片仍在视口内时：`computeImageBottomInViewport` 测量的是**当前图片自身**的 `LayoutCoordinates`，上方图片走 `COMPENSATE` 保住下方图片不跳，下方图片走 `ANIMATE` 不误滚，多图楼层判定精确。
2. **视口内或下方变高（不做补偿，`== 0dp`）**：
   - 当 `floorItem.index > firstVisibleIndex` 或图片底缘仍在视口内（`imageBottomInViewportPx > 0.5f`）时，命中 `IN_OR_BELOW_VIEWPORT_ANIMATE`（`:97, 234-241`），不触碰 `listState`，高度以 `HiMotion.spatialFast()` 平滑过渡（减动效 `snapTo`）。
3. **滚出回收再滚回（缓存首帧命中）**：
   - `ImageAspectRatioCache`（`maxEntries = 512` 线程安全 LRU，比例钳制 `[0.25f, 4f]`）按规范化 URL 缓存真实宽高比；回收再入组合首帧在 `remember` 阶段即同步拿到真实比例，`deltaPx == 0f` 零二次跳动。
4. **单测变红能力核实**：若删掉 `:227` 补偿，`ImageScrollCompensationTest:171,175,184` 立即变红（偏移差 ~853dp）；若对视口内/下方误补，`:201,203`（`0.01f` 容差）立即变红；若对 `index < firstVisibleIndex` 误调 `requestScrollToItem`，`:313,316` 立即变红；若旁路比例缓存，`:236` 未放行解码门闩前的首帧高度断言立即变红。

#### 4.2 `E2b` 与 `E2d`（`origin/card/E2d-thread-continuous-paging` @ `c447d4a`）合并后同时生效的交互分析

逐行对照 `E2d` 的 `ThreadDetailPane.kt:45-108, 202-231, 271-299` 与 `E2b` 的 `PostImageBlock.kt`，核查全部 4 类并发/先后场景——**结论：不会重复补偿，常规场景零干扰**：

1. **头部 `HiListFooter`（`key = "thread_prepend_header"`）改变列表下标：零干扰**
   - `E2d` 在 `showPrependHeader == true` 时在 `LazyColumn` 的 `index = 0` 插入头部状态行，使楼层在 `LazyColumn` 中的 `index` 整体 `+1`。
   - `E2b` 的 `classifyHeightChangeBranch`（`PostImageBlock.kt:90-96`）与 `:227` 完全使用 `LazyColumn` 原生项信息（`visibleItems.firstOrNull { it.key == "floor:$floorPostId" }.index` 对比 `listState.firstVisibleItemIndex`），从不假设楼层在 `floors` 列表中的下标等于 `LazyColumn` 下标：
     - 当顶部 `HiListFooter` 滚出视口后（首可见项为楼层 `index = 1`）：`floorItem.index == 1 == firstVisibleItemIndex`，`E2b` 精确补偿 `index = 1`；
     - 当顶部 `HiListFooter` 露在视口顶部时（`firstVisibleItemIndex == 0` 为 header，首楼在 `index = 1` 且顶缘 `offset > 0` 位于视口内）：`floorItem.index (1) > firstVisibleIndex (0)`，首楼内图片必然处于视口内/下方，`E2b` 走 `IN_OR_BELOW_VIEWPORT_ANIMATE` 不误滚。
2. **向上插页合入上一页（`page P-1`）后，新插入楼层里的图片陆续解码完成：绝不重复补偿**
   - **合入帧 `T0`**：`E2d` 的 `SideEffect { anchorController.syncBeforeMeasure(uiState, listState) }` 在 `measureAndLayout` 之前将锚点重定位到原首可见楼层（如第 2 页首楼 `"floor:p51"`，新 `index = 50`）；此时新合入的第 1 页 `1..50` 楼全部位于 `index 0..49 < firstVisibleItemIndex (50)`，尚未测量宽度（`containerWidthPx == 0`），`T0` 帧不会触发任何 `E2b` 滚动补偿。
   - **后续解码帧 `T1+`**：
     - `E2d` 侧：`uiState.prependGeneration == handledGeneration` 且 `showPrependHeader == lastShowHeader`，`syncBeforeMeasure` 在 `ThreadDetailPane.kt:76` 直接 `return`（纯空操作），`pendingFloorKey == null` 使 `applyPendingPositiveOffset` 也直接 `return`；
     - `E2b` 侧：若第 50 楼（`index = 49`）位于顶部 `topClearance` 内完成图片解码，`floorItem.index (49) < firstVisibleIndex (50)` 命中 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP`，只 `snapTo` 宽高比、**不调用 `requestScrollToItem`**，由 `LazyColumn` 自身锚定 `"floor:p51"` 向上吸收高度差；若用户已继续上滚进入第 48 楼并越过其首图，此时 `E2d` 早已静默，仅 `E2b` 单独执行一次 `ABOVE_VIEWPORT_COMPENSATE`。**两卡触发条件在帧维度严格正交，不存在叠加双倍偏移的可能。**
3. **`E2b` 补偿偏移对 `E2d` 滚动方向预取监听（`ThreadDetailPane.kt:202-231`）的影响：良性无副作用**
   - 首楼越顶长图变高（`deltaPx > 0`）使 `firstOffset` 增大，在 `E2d` `snapshotFlow` 中表现为 `movedDown = true`（不触发向上预取；仅当已近页尾 5 楼时才预取下一页，符合预期）；宽图变矮（`deltaPx < 0`）使 `firstOffset` 减小时，仅在用户本就处于已载顶部 5 楼内（`firstFloorIndex <= 5 && hasPrev`）时才会触发后台静默预取上一页，且上一页合入由 `PrependAnchorController` 无跳动锚定，不移动阅读位置。
4. **极端同拍微竞态（非重复补偿，附跨卡优化建议 `S3`）**
   - 若“当前首可见楼层 `"floor:p51"` 内部越顶图片的 `LaunchedEffect` 解码完成（调 `requestScrollToItem` 更新 `scrollPosition.scrollOffset`，尚未进布局）”与“`E2d` 上一页数据流恰好在同一毫秒发射 `prependGeneration++`”落在**同一帧的 `measureAndLayout` 之前**：两者均调用绝对定位 `requestScrollToItem`（覆盖写而非累加 `dispatchRawDelta`），因此**绝不会重复补偿**；但 `E2d` 的 `syncBeforeMeasure`（`E2d ThreadDetailPane.kt:91`）当前从上一帧 `layoutInfo` 读取 `anchorItem.offset`，在该极端同帧重合下可能覆盖掉 `E2b` 尚未布局的新 `scrollOffset`。此点在 `E2b` 侧无需改动，已记为跨卡建议 `S3` 供后续修改 `ThreadDetailPane.kt` 的 `E2c`/`E4` 顺手用 `firstVisibleItemScrollOffset` 收口。

---

### 重点 5：生产代码净增 `+564` 行逐文件瘦身审计

本卡 5 个生产文件共 `610` 行，相对基线 `PostImageBlock.kt`（`46` 行）**净增 `+564` 行**（接近卡面估计上限 `550` 行，远低于 `1.5× = 825` 行门禁线；0 测试钩子、0 手写 `Layout`/`Modifier.Node`）。逐文件审视后，**在不改对外契约、不降安全性与测试覆盖的前提下，可删减约 `60–65` 行**（非阻断，列为 `S1`）：

| 生产文件 | 当前行数（净增） | 可删减的具体位置与原因 | 可省行数 |
|---|---:|---|---:|
| `PostImageBlock.kt` | 425 (`+379`) | ① **合并 `:329-346` `InvalidImagePlaceholder`（18 行）**：其外层 `Surface`（圆角/底色/0.5dp 描边/`4:3`）与主 `Surface`（`:264-269`）完全重复，内部 `Text` 与 `FailedImageContent`（`:409-414`）完全重复；将 `PostImageLoadState.Failed` 改为 `Failed(val canRetry: Boolean)`（`resolvedUrls == null` 时初始置 `Failed(canRetry = false)` 且跳过加载），即可整块删除 `InvalidImagePlaceholder`。<br>② **合并 `:113-129` `computeImageBottomInViewport` 的双 `while` 循环**：两轮向上遍历 `parentLayoutCoordinates` 可合并为单趟遍历（记录首个尺寸匹配 `viewportSize` 的祖先并优先命中 `expectedItemTop` 匹配项）。<br>③ **删除 `:166-167, 174-182` 的 `nextLoadId` 与 `Triple` 包装**：Compose 原生支持 4 键 `LaunchedEffect(displayUrl, activeLoadId, imageLoader, hasMeasuredWidth)`，无需 `remember { Triple(...) }`；取消态将 `activeLoadId` 置 `0`、启动/重试只需单个递增计数器（如 `var loadSeq` + `var loadingActive` 或负号休眠）即可。 | **~36 行** |
| `ImageLoadingPolicy.kt` | 80 (`+80`) | **删 `:51-55, 62-77` 的 `ImageNetworkState` 数据类与中间重载**：生产代码仅在 `PostImageBlock.kt:159` 调用 `ImageLoadingPolicy.resolveForContext(context)` 一处；`ImageNetworkState`、`resolveLoadMode(ImageNetworkState)` 与拆开的 `readNetworkState(Context)`/`readNetworkState(ConnectivityManager)` 仅被 `ImageLoadingPolicyTest` 调用，而该单测已通过 Robolectric `ShadowConnectivityManager` 直接测通 `resolveForContext(context)`，保留 `resolveLoadMode(isMetered, isDataSaverEnabled)` + `resolveForContext(context)` 两个函数即可。 | **~18 行** |
| `ImageAspectRatioCache.kt` | 68 (`+68`) | **收敛 `:18, 27, 52-53` 零生产调用的方法**：生产代码仅调 `resolveInitialAspectRatio` 与 `put(url, width, height)`（测试清理调 `clear()`）；`size` 属性、`get(url)` 及公开浮点 `put(url, aspectRatio)` 重载均无生产调用方（`put(url, aspectRatio)` 改为 `private` 即可）。 | **~10 行** |
| `ImageSizeFormatter.kt` | 30 (`+30`) | 逻辑紧凑清晰（处理 `<= 0` 返回 `null`、`B`/`KB`/`MB`/`GB` 与一位小数舍入），无水分。 | **0 行** |
| `strings_thread_media.xml` | 7 (`+7`) | 仅 3 条新增文案（复用既有 `点击加载图片` 与 `重试`），极简无冗余。 | **0 行** |

---

## 三、其余审查维度核实与非阻断建议（Suggestions）

### 3.1 已核实通过的其余审查项
1. **按显示尺寸有界解码（防原图 OOM）**：`PostImageBlock.kt:186-194` 仅在 `containerWidthPx > 0` 后发起请求，显式传入 `.size(Dimension.Pixels(widthPx), Dimension.Pixels(maxDecodeHeightPx))`（`maxDecodeHeightPx = 4 * widthPx`）+ `.scale(Scale.FIT)` + `.precision(Precision.INEXACT)`，杜绝 `Size.ORIGINAL` 无界解码；`PostImageBlockTest:246-254` 已对 `sizeResolver.size()` 断言双轴均为正像素值 `Dimension.Pixels`。
2. **自动流量节省、可取消加载、失败重试与看图坐标上报**：
   - `ImageLoadingPolicy` 严格仅在 `isActiveNetworkMetered && restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_ENABLED` 时返回 `ClickToLoad`（应用处于流量节省白名单 `WHITELISTED` 时仍自动加载）；
   - 点击加载中再次点击置 `activeLoadId = 0` 使 `LaunchedEffect` 键变化立即取消在途 `imageLoader.execute` 协程并退回 `TapToLoad`（`PostImageBlockTest:344-348` 验证 `cancelledCount == 1`）；
   - 取消热区与失败态 `重试` 按钮均保证 `≥ 48dp` 触控目标并挂载 `hiPressScale`；点击已加载图片经 `onGloballyPositioned` + `positionInWindow()` 上报非零窗口 `Rect`；
   - 全部文案逐字对齐 `docs/design/copy.md`，全部动效取自 `HiMotion`（`effectsDefault`、`spatialFast`、`hiSkeleton`、`HiLoadingSpinner`、`hiPressScale`），减动效 `0ms` 一帧到位，`CopyTerminologyScanTest` 与 `MotionTokenScanTest` 零豁免全绿。

### 3.2 非阻断改进建议（`S1`–`S4`）
- **S1 — 按第二节重点 5 表格精简 `media/` 内部约 `60–65` 行冗余包装与零生产调用重载（`PostImageBlock.kt`、`ImageLoadingPolicy.kt`、`ImageAspectRatioCache.kt`）**：可在后续维护或 M6 `E14` 联动时顺手合并 `InvalidImagePlaceholder`、单趟化 `computeImageBottomInViewport`、去掉 `LaunchedEffect` 的 `Triple` 包装，并收敛 `ImageNetworkState` 等仅测试调用的中间重载，将净增行数压回 `~500` 行。
- **S2 — 快速惯性滑行（fling）途中首楼越顶图片解码完成的手势连续性备忘（`PostImageBlock.kt:226-227`）**：Compose `LazyListState.requestScrollToItem` 在 `listState.isScrollInProgress == true` 时会通过内部空 `scroll {}` 抢占 `MutatorMutex` 中断当前惯性滑行；M5 真机长帖快速滑动验收时若体感偶发顿停，可评估在滑行态下改用不中断手势的偏移修正策略。
- **S3 — 跨卡备忘（供 `E2c`/`E4` 修改 `ThreadDetailPane.kt` 时参考）：极端同帧重合下优先读取 `firstVisibleItemScrollOffset`（`E2d ThreadDetailPane.kt:91`）**：当 `anchorItem.index == listState.firstVisibleItemIndex` 且 `anchorItem.offset <= 0` 时，将 `E2d` `PrependAnchorController.syncBeforeMeasure` 中的 `-anchorOffset` 改为 `listState.firstVisibleItemScrollOffset`，可连同“首楼越顶图片解码与上一页合入恰好落在同一帧 `measureAndLayout` 之前”的极端微竞态一并闭合。
- **S4 — 全屏看图器原图下载确定进度分工备忘（`PostImageBlock.kt:388-396`）**：受限于本卡不改 `di/ImageLoaderModule.kt`，帖内省流点击加载按卡面“或可用进度”采用 `HiLoadingSpinner` + 关闭图标 + `正在加载图片，点击取消` 语义；M6 `E14` 实现全屏看图器“下载原图”时需按 `product-design.md` §6.3 接入真实字节级确定进度圈。
