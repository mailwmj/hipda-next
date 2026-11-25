# 独立验证报告 V-E2b：帖子图片渲染与 E2b+E2d 叠加滚动补偿验证

- **总判定**：**`E2b @ 1597b4e` 独立验收 PASS**；**【负责人加测】`E2b + E2d` 合并叠加补偿 FAIL**（发现同帧并发向上插页与首可见楼层内越顶图片变高时，补偿被竞态吞没导致首可见楼层下跳 `473.5 dp`）
- **验证对象**：`origin/card/E2b-thread-images` @ `1597b4e260995db466d2c37c15c9afe3840ead9a`（基线 `3dd65c4`）
- **验证分支与工作区**：
  - V-E2b 主验证分支：`verify/E2b-probes` @ `7042c0d`（worktree `/home/mir/Documents/hipda-next.wt/V-E2b`，仅新增 `E2bProbeTest.kt`）
  - E2b+E2d 合并加测临时分支（本地）：`verify/E2b-E2d-merge-probe` @ `d9d79cf`（merge commit `61e67f9`，合并 `1597b4e` + `origin/card/E2d-thread-continuous-paging` @ `c447d4a`；worktree `/home/mir/Documents/hipda-next.wt/V-E2b-E2d-merge`，仅新增 `E2bE2dMergeProbeTest.kt`）
- **GitHub Actions CI 证据（`verify/E2b-probes`）**：
  - [Android CI #45704591421](https://github.com/mailwmj/hipda-next/actions/runs/45704591421) — `Build, Lint & Unit Tests`（Job `139523182372`）**SUCCESS**（11m18s）
  - CI 汇总：**`TOTAL: 1064 tests, 1064 passed, 0 failed, 0 errors, 0 skipped`**（`:hipda:testDebugUnitTest` 966 + `:core-parser:test` 98；含 `-Proborazzi.test.verify=true`、`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验与 Debug/Release APK 包名校验）

---

## 一、改动边界与 0 真实网络核查

1. **生产改动边界（`git diff --name-status 3dd65c4...1597b4e`）**：
   - 仅改/增 `feature/threaddetail/media/*` 4 个文件（`PostImageBlock.kt` 425 行、`ImageLoadingPolicy.kt` 80 行、`ImageAspectRatioCache.kt` 68 行、`ImageSizeFormatter.kt` 30 行）与 `strings_thread_media.xml`（7 行），相对 `3dd65c4` 净增 `564` 行，零改动任何父文件（`PostBodyRenderer.kt`、`PostFloorCard.kt`、`ThreadDetailPane.kt`、`ThreadDetailViewModel.kt`、`strings_thread_detail.xml`）。
   - 快照目录 `git diff --name-status 3dd65c4...1597b4e -- hipda/src/test/snapshots/` 仅含 6 张新增 `thread_image_*.png` 与获授权重录的 9 张 `thread_detail_*.png`，其余全部基线快照 0 漂移。
2. **授权测试改动 0 真实网络核查**：
   - 核查 `3dd65c4...1597b4e` 对 `PostBodyRendererTest.kt` 与 `ThreadDetailScreenshotTest.kt` 的改动：`ThreadDetailScreenshotTest` 通过 `LocalHiImageLoader` 注入确定性离线 `OfflineTestImageLoader`（请求地址 `https://img.example.invalid/sample_photo.jpg`，并硬断言 `enqueueCount == 0` 及请求列表全部命中该离线测试替身）；`PostBodyRendererTest` 同样通过 `LocalHiImageLoader` 注入 `OfflineTestImageLoader`，全程 0 真实网络访问。

---

## 二、逐张视觉复验（6 张 `thread_image_*.png` + 9 张重录 `thread_detail_*.png`）

### 2.1 新增 6 张 `thread_image_*.png`（`411`/`749`/`1280dp` × 浅/深）

全部 6 张截图均通过 `read_file` 逐张审阅，2×2 网格依次展示「已加载态」「默认加载骨架占位态」「失败重试态」「省流点击加载态」：
- `thread_image_w411_light.png` / `thread_image_w411_dark.png`：4:3 圆角容器（`12dp` 圆角 + `0.5dp` `outlineVariant` 边框）裁切干净，已加载风景插图在浅/深主题下无毛刺溢出；失败态居中显示 `图片加载失败` 与 `重试` `FilledTonalButton`（高度 `≥ 48dp`），深浅色对比度清晰；省流态居中显示 `点击加载图片（250 KB）`（注：在 `w411` 双列演示网格半宽 ~180dp 下换行显示为 `点击加载图片（250` / `KB）`，真实帖内全宽单列 ~379dp 下可单行展示）。
- `thread_image_w749_light.png` / `thread_image_w749_dark.png`：4 态层次清晰，`点击加载图片（250 KB）` 单行居中，`重试` 按钮与文字深浅主题均清晰可读。
- `thread_image_w1280_light.png` / `thread_image_w1280_dark.png`：内容最大宽约束与居中留白合理，4 态比例、圆角与边框一致性良好。

### 2.2 重录 9 张 `thread_detail_*.png`（图片区域外 0 像素漂移实测）

通过 `read_file` 逐张目视检查，并以 Python PIL 对 `3dd65c4` 与 `1597b4e` 逐像素比对 RGB/Alpha 差分边界框（`bbox_a` 全部为 `None`）：

| 截图文件名 | 图像尺寸 (px) | 差异 RGB 边界框 `bbox_rgb` | 核对结论 |
|---|---|---|---|
| `thread_detail_pane411_filter_light.png` | `822 × 1118` | `(32, 631, 790, 1118)` | 仅 1 楼 4:3 图片区由占位替换为离线山景图；顶栏、`只看楼主 ×` 胶囊、楼层头、正文及表情 0 变化 |
| `thread_detail_pane448_dark.png` | `896 × 1392` | `(32, 399, 864, 1023)` | 仅 4:3 图片区更新为深色山景图；透明顶栏、附件卡片、2 楼及底栏 0 变化 |
| `thread_detail_pane680_light.png` | `1360 × 1600` | `(32, 295, 1328, 1267)` | 仅 4:3 图片区变化（`1296×972px`）；全屏/个人圆钮、正文及底栏 0 变化 |
| `thread_detail_w411_dark.png` | `822 × 1782` | `(32, 399, 790, 968)` | 仅 4:3 图片区变化（`758×569px`）；其余全部区域 0 像素漂移 |
| `thread_detail_w411_light.png` | `822 × 1782` | `(32, 399, 790, 968)` | 仅 4:3 图片区变化（`758×569px`）；其余全部区域 0 像素漂移 |
| `thread_detail_w411_oled_pure_black.png` | `822 × 1782` | `(32, 399, 790, 968)` | 仅 4:3 图片区变化（`758×569px`）；OLED `#000000` 纯黑背景与正文 0 变化 |
| `thread_detail_w411_step5_scale13_light.png` | `822 × 1782` | `(32, 885, 790, 1454)` | 仅 4:3 图片区变化（`758×569px`）；5 档×1.3 大字号排版与截断/附件区 0 变化 |
| `thread_detail_w559_light.png` | `1118 × 1498` | `(32, 401, 1086, 1192)` | 仅 4:3 图片区变化（`1054×791px`）；Fold8 竖屏单栏其余区域 0 变化 |
| `thread_detail_w640_dark.png` | `1280 × 2048` | `(32, 401, 1248, 1313)` | 仅 4:3 图片区变化（`1216×912px`）；640dp 详情页其余区域 0 变化 |

---

## 三、独立探针验证结果（`E2bProbeTest`，`verify/E2b-probes` @ `7042c0d`）

| 探针 | 覆盖场景与硬断言 | 结论 |
|---|---|---|
| **a. 三分支滚动补偿与回收缓存** (`probeA_*` + `probeA2_*`) | ①-A 同楼层越顶图片由 `4:3` 变为 `1:2` 长图（`400×800`）：`p2` 与 `p1` 可见尾部距顶偏移 `≤ 1dp`；①-B 位于首楼更上方 `beforeContentPadding` 的 `p0` 图片变长：首楼 `p1` 偏移 `≤ 1dp`；② 首楼自身/下方图片变高：手动逐帧推进 30 帧至终态，首楼距顶偏移严格 `== 0dp`（无误滚）；③ 滑出回收后更换未决 `Deferred` 再滚回，未待重解完成首帧宽高比即 `0.5f ± 0.03f`（非 `4:3` 回退），重解完成高度跳变 `≤ 1px`；另加测同楼层多图同时变长与 `4:1` 宽图缩矮复合补偿偏移 `≤ 1dp`。 | **PASS** (2/2) |
| **b. URL 白名单全反例** (`probeB_*`) | 逐一喂入 `file:///etc/hosts`、`content://media/external/images/1`、`javascript:alert(1)`、`intent://x#Intent;end`、`data:image/png;base64,AA==`、`mailto:user@example.com`、`" FILE:///a.png"`、`"http://evil.com/\nfoo"`、`JaVaScRiPt:alert(1)`、`//evil.com/a.png`、`relative/path.png`、`""`：假 `ImageLoader` 请求数 `== 0`、`enqueue == 0`、点击 `onOpenImageViewer == 0`、原地安全展示 `图片加载失败`。混合非法 `thumb`/`full` 时绝不泄露非法 URI；合法 `http://`/`https://` 按有界容器像素宽请求解码并点击上报非零 `sourceBoundsInWindow`。 | **PASS** (1/1) |
| **c. 自动流量节省、取消与重试** (`probeC_*`) | 覆盖 4 种计费/省流组合策略；Robolectric 模拟计费+省流开启：`256_000L` 显示 `点击加载图片（250 KB）`、`0L` 显示 `点击加载图片`（无括号），默认 0 请求；点击进入加载中（语义 `正在加载图片，点击取消` 且 `≥ 48dp`）再点取消中止在途协程并回退占位；注入失败显示 `图片加载失败` + `重试`（`≥ 48dp`），点 `重试` 后恢复成功加载。 | **PASS** (1/1) |

本地与 CI 校验证据：
- 本地串行（`DISPATCH.md` §2）：`:core-parser:test` 98/98、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 966/966（含 `E2bProbeTest` 4/4、`CopyTerminologyScanTest` 3/3、`MotionTokenScanTest` 7/7、`ThreadDetailScreenshotTest` 15/15、`PostImageScreenshotTest` 6/6）、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`（SARIF 对 `feature/threaddetail/media/*`、`strings_thread_media.xml`、`E2bProbeTest.kt` 命中 **0** 条告警）全部通过；结束已执行 `./gradlew --stop`。
- GitHub Actions CI（[Android CI #45704591421](https://github.com/mailwmj/hipda-next/actions/runs/45704591421)）：1064/1064 全部通过。

---

## 四、【负责人额外加测】`E2b + E2d` 合并叠加补偿探针（**FAIL**）

- **测试分支与文件**：本地 `verify/E2b-E2d-merge-probe` @ `d9d79cf`（`61e67f9` 无冲突合并 `E2b @ 1597b4e` + `E2d @ c447d4a`，新增 `hipda/src/test/java/net/jejer/hipda/verify/E2bE2dMergeProbeTest.kt`）
- **实测结果矩阵（5 项：3 PASS / 2 FAIL）**：
  1. `baseline_imageAboveViewportGrowsWithoutPrepend_staysZeroJump`（仅首楼 `p201` 越顶图从 `4:3` 变 `1:2`，不插页）：`offset: 820 -> 1768`，`p202DeltaDp = 0.5dp` — **PASS**
  2. `mergeProbe2_upwardPrependSimultaneousWithAboveFloorInPaddingGrowing`（`p202` 为首可见楼层、`p201` 在 `beforeContentPadding` 区内变高，与第 1 页向上插页同帧完成）：`firstIdx: 2 -> 16, offset=20`，`deltaDp = 0.0dp` — **PASS**（跨楼层无过度补偿）
  3. `mergeProbe1C_imageOneFrameAfterPrepend_aboveViewportImageInFirstVisibleFloor`（向上插页完成 1 帧、`layoutInfo` 更新后再完成 `p201` 越顶图加载）：`firstIdx=15, offset=1768`，`p202DeltaDp = 0.5dp` — **PASS**
  4. `mergeProbe1A_prependThenImageSameTick_aboveViewportImageInFirstVisibleFloor`（同帧先释放第 1 页插页、同 tick 再释放 `p201` 越顶图）：`firstIdx=15, offset=820`，`p202DeltaDp = 473.5dp`、`p201TailDeltaDp = 473.5dp` — **FAIL**
  5. `mergeProbe1B_imageThenPrependSameTick_aboveViewportImageInFirstVisibleFloor`（同帧先释放 `p201` 越顶图、同 tick 再释放第 1 页插页）：`firstIdx=15, offset=820`，`p202DeltaDp = 473.5dp`、`p201TailDeltaDp = 473.5dp` — **FAIL**

### 根因与精确触发路径
1. **前提**：用户处于第 2 页首楼 `p201` 中下段（`showPrependHeader = true` 占 index `0`，`p201` 占 index `1`、`offset = -820px`），`p201` 上方一张 `4:3` 图片底缘已越过顶栏底边（`bottomInViewport <= 0`），此时第 1 页向上预取正在等待返回。
2. **竞态触发（非过度补偿，而是补偿丢失）**：
   - `E2d` `ThreadDetailPane.kt:276` 在组合期 `SideEffect` 调用 `PrependAnchorController.syncBeforeMeasure`（`:74-102`），同步执行 `listState.requestScrollToItem(index = 15, scrollOffset = 820)`。该调用立刻改写 `listState.firstVisibleItemIndex = 15` 与 `firstVisibleItemScrollOffset = 820`，但本帧 `measureAndLayout` 尚未运行，`listState.layoutInfo.visibleItemsInfo` 仍保留插页前快照（`floor:p201` 的 `index == 1`、`offset == -820`）。
   - `E2b` `PostImageBlock.kt:222-233` 的 `LaunchedEffect` 在本帧布局完成前恢复，调用 `classifyHeightChangeBranch("p201", listState, bottomInViewport)`（`:83-99`）：从旧 `visibleItemsInfo` 查到 `floorItem.index == 1`，与新 `listState.firstVisibleItemIndex == 15` 比较得出 `1 < 15`，**误判为 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP`**，直接 `aspectRatioAnim.snapTo(0.5f)` 而跳过 `requestScrollToItem(..., 820 + 948)` 偏移补偿。
   - 反之若 `PostImageBlock` 先于 `syncBeforeMeasure` 执行 `requestScrollToItem(1, 1768)`，由于尚未 remeasure，`syncBeforeMeasure` 仍从旧 `layoutInfo.visibleItemsInfo` 读到 `anchorItem.offset == -820` 并随后调用 `requestScrollToItem(15, 820)` 将 `+948px` 补偿覆盖归零。
3. **现象**：两路路径均未发生过度补偿，但在同一帧窗口内会彻底丢失图片变高对应的 `+948px` 滚动补偿，导致首可见楼层尾部及后续 `p202` 当场下跳 **`473.5 dp`**。
4. **给负责人的修复参考**（仅记录不越界修改）：合入 `E2b` + `E2d` 时，应在 `E2b` 补偿或协调层处理未重测窗口——例如在 `PostImageBlock` 读取 `listState.layoutInfo` 前检查或用 `floor:$floorPostId` 与 `firstVisibleItemScrollOffset` 并在必要时等待 `withFrameNanos` / 通过 `listState.dispatchRawDelta` 或统一单帧锚点协调，确保向上插页更新 `firstVisibleItemIndex` 时不致使同一楼层越顶图误入 `ABOVE_FIRST_VISIBLE_FLOOR_SNAP` 且不被 `syncBeforeMeasure` 覆盖。
