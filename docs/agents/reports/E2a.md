# 任务卡 E2a 交付报告：帖子详情核心完整版（透明极简顶栏、Compose 原生正文、表情调色与 `PostImageBlock` 接缝）

- **状态**：`DONE`
- **Worktree**：`/home/mir/Documents/hipda-next.wt/E2a`
- **分支**：`card/E2a-thread-detail-core`
- **基线提交**：`db7dba6`（`origin/main`，已含 `E0`）
- **最终提交（HEAD）**：`60bd5b7b94a1f2eaf386103db568f84c030b09ee`（`feat(threaddetail): 实现帖子详情透明顶栏、Compose原生正文、表情调色与登录自动重载`）
- **GitHub Actions CI 证据**：
  - [Android CI #45678739131](https://github.com/mailwmj/hipda-next/actions/runs/45678739131) — `Build, Lint & Unit Tests`（Job `139433255110`）**SUCCESS**
  - 全仓单测与 Roborazzi 校验汇总：**`TOTAL: 1007 tests, 1007 passed, 0 failed, 0 errors, 0 skipped`**（含 `:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room schema 校验、`:core-parser:test`、`:emoji:testDebugUnitTest`、全量 `:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 与 Debug/Release APK 产物校验）
- **本地串行校验（遵循 `docs/agents/DISPATCH.md` §2，`flock /tmp/hipda-gradle.lock`，`-Xmx3g`，`workers.max=4`，结束后 `./gradlew --stop`）**：
  - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`909 tests, 909 passed, 0 failed, 0 skipped`**
    - `TextColorContrastAdjusterTest`：5/5 通过
    - `PostBodyRendererTest`：3/3 通过
    - `ThreadDetailViewModelTest`：7/7 通过
    - `ThreadDetailScreenshotTest`（Roborazzi verify）：15/15 通过（15 张 `thread_detail_*.png` 均已逐张人工目视核验；既有非 `thread_detail_*` 快照 0 漂移）
    - `CopyTerminologyScanTest`：3/3 通过（新增文案 0 豁免）
    - `MotionTokenScanTest`：7/7 通过（白名单 0 新增）
    - `NextAppTracerBulletTest`：9/9 通过（验证 `ThreadDetailPane` 顶层签名与 `ThreadDetailViewModel` 2 参构造 100% 兼容）
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**；SARIF 核查本卡全部 `feature/threaddetail/**`、`strings_thread_detail.xml` 与 `strings_common.xml` **0 条 Lint 告警/错误**。

---

## 1. 边界合规与生产代码净增行数

- **生产代码总行数（`hipda/src/main/`，9 个 Kotlin 文件 + 1 个字符串资源）**：`1,404` 行；相较基线 `E0` 原有 2 文件（`ThreadDetailPane.kt` 139 行 + `ThreadDetailViewModel.kt` 94 行 = 233 行），**净增 `+1,171` 行**（在卡估算上限 `850 × 1.5 = 1,275` 行以内）。
  - `ThreadDetailViewModel.kt`：316 行（原 94 行，净增 +222）
  - `ThreadDetailPane.kt`：208 行（原 139 行，净增 +69）
  - `ThreadDetailTopBar.kt`：180 行（新增）
  - `FilterCapsuleBar.kt`：79 行（新增）
  - `PostFloorCard.kt`：97 行（新增）
  - `body/PostBodyRenderer.kt`：208 行（新增）
  - `body/TextColorContrastAdjuster.kt`：182 行（新增）
  - `body/InlineEmojiContent.kt`：71 行（新增）
  - `media/PostImageBlock.kt`：50 行（新增）
  - `res/values/strings_thread_detail.xml`：13 行（新增）
- **零越界与实现约束核对**：
  - 仅改动/新增 `feature/threaddetail/**`、`res/values/strings_thread_detail.xml`、`src/test/.../feature/threaddetail/**` 及 `thread_detail_*.png` 快照，与同期并行的 `E1`、`E15` 零文件重叠；
  - 零触碰 `data/threaddetail/`、`feature/app/*`、`feature/common/*`、`feature/forum/*`、`feature/login/*`、`ui/*`、`di/*`、`src/debug/*`、`core-parser/*`、`emoji/*`、`MotionTokenScanTest` 白名单及任何共享构建/清单文件；
  - 全部使用 Compose 标准组件（`Surface`、`Column`、`Row`、`Box`、`LazyColumn`、`AnimatedContent`、`AnimatedVisibility`、`drawBehind`、`graphicsLayer`），无手写 `Layout`、`Modifier.Node`、状态机或命令队列；
  - 生产代码零测试专用 `testTag` / `SemanticsPropertyKey` / 钩子 / `CompositionLocal`；
  - 既有非 `thread_detail_*` 快照 0 漂移。
- **目视截图自查修复记录**：
  - 首次录制后逐张检视 15 张截图，发现并当场修复 2 处真实生产渲染缺陷后重新录制验证：
    1. `ThreadDetailPane` 根容器改为 `Surface(color = cs.background, contentColor = cs.onBackground)`，修复深色模式未下发 `LocalContentColor` 导致 `HiSkeletonBlock` 暗底绘黑块几乎不可见的问题；
    2. 在 `LazyColumn` 的 `.hiGlassSource(detailGlassState)` 内侧补齐 `.background(cs.background)`，使离屏采样层包含不透明底色，彻底消除液态玻璃顶栏下方出现未模糊锐利文字穿透的问题。

---

## 2. 验收项逐条对照（含【负责人追加】）

1. **三主题极端文字色对比度（B4-E2a ②） — 通过**
   - `TextColorContrastAdjuster` 在 OKLCH 空间保持色相并二分校准明度与 sRGB 合法色度，原已达 `≥ 4.5:1` 的可读颜色零失真直通。
   - `TextColorContrastAdjusterTest`（5/5 通过）覆盖 `#000000`、`#000080`、`#FFFF00`、`#FFFFFF`（及 CSS 颜色名/十六进制/RGB 解析与异常回退）× 浅色 / 深色 / OLED 纯黑（`#000000`），全部断言 `wcagContrastRatio ≥ 4.5f` 且原本可读颜色完全原样返回。
2. **Compose 原生正文与 `C1b#2` 表情映射 — 通过**
   - `PostBodyRenderer` 使用标准 `AnnotatedString` + `InlineTextContent` + `LinkAnnotation` 渲染全部 6 类 `ContentBlock`（`Text`、`Quote`、`GoToFloor`、`Image`、`Attachment`、`Notice`），覆盖粗体/斜体/下划线/删除线/字号/动态调色/外链/站内链接/邮箱/换行与内置小图 `win`。
   - `InlineEmojiContent.resolveForumEmoji` 将 `InlineSpan.Emoji.normalizedKey` 直接透传 `EmojiMap.drawableForImageSrc(key, dark)`，未知键安全回退 `EmojiMap.codeForImageSrc(key) ?: "[$key]"`。
   - `PostBodyRendererTest`（3/3 通过）消费 `B8` 脱敏样本与全部 6 类块，验证 3 套论坛表情（`default`/`coolmonkey`/`grapeman`）浅/深主题映射、补充平面 Emoji、链接与未知键回退。
3. **透明顶栏玻璃拓扑、`只看楼主` 过滤、沿用上下页与并行接缝（B2-2） — 通过**
   - `ThreadDetailTopBar` 为严格 1 个 `HiGlassStyle.TopBar` 玻璃区域（`HiGlassMarker == 1`），与挂载 `.hiGlassSource(detailGlassState)` 的 `LazyColumn` 为严格兄弟节点；未滚动纯 `surface`，滚动后按 `HiMotion.effectsDefault()` 渐进显露液态玻璃；左右 `40dp` 轻底纯色圆钮包裹在 `48×48dp` 触控区中（零嵌套 `HiGlassSurface`/`drawBackdrop`，使用 `HiGlassTokens.TopBarActionButtonLightAlpha`/`DarkAlpha` + `TopBarActionButtonBorderWidth` + `hiPressScale`）。
   - `FilterCapsuleBar` 支持 `只看楼主 ×` 及预留给 Wave 3 `E2c` 的 `只看 {用户名} ×`（`×` 描述 `退出只看`）；退出过滤恢复全帖并定位回切前页与 `postId`/偏移；过滤空态展示 `没有符合条件的楼层` + `看全部`；沿用 `E0` 上一页/下一页按钮。
   - `media/PostImageBlock.kt` 锁死签名含 `image: ContentBlock.Image`、`floorPostId: String`、`listState: LazyListState`、`onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit`、`modifier: Modifier = Modifier`，`LazyColumn` 固定使用 `items(items = uiState.floors, key = { "floor:${it.postId}" })`。
   - `ThreadDetailViewModelTest`（7/7 通过）覆盖兄弟拓扑、`HiGlassMarker == 1`、圆钮零嵌套玻璃且触控区 `≥ 48×48dp`、滚动期重组增量门禁、`只看楼主` 过滤与退出回原楼、空态 `看全部`、上下页切页与 `PostImageBlock` 接缝路由。
4. **核心 Roborazzi 截图基线（15 张 `thread_detail_*.png`） — 通过**
   - `ThreadDetailScreenshotTest` 录制并验证 15 张基线（不含连续加载头尾态）：
     - 单栏：`thread_detail_w411_light`、`thread_detail_w411_dark`、`thread_detail_w411_oled_pure_black`、`thread_detail_w559_light`、`thread_detail_w640_dark`；
     - 大屏详情栏：`thread_detail_pane411_filter_light`、`thread_detail_pane448_dark`、`thread_detail_pane680_light`；
     - 字体极值：`thread_detail_w411_step5_scale13_light`（`STEP_5 × 1.3`）；
     - 空/错/骨架：`thread_detail_w411_empty_filter_light`、`thread_detail_w933_empty_filter_dark`、`thread_detail_w411_error_light`、`thread_detail_w933_error_dark`、`thread_detail_w411_skeleton_light`、`thread_detail_w933_skeleton_dark`。
   - 既有非 `thread_detail_*` 快照 0 漂移。
5. **全量门禁 — 通过**
   - 本地与 GitHub Actions `CopyTerminologyScanTest`、`MotionTokenScanTest`、`NextAppTracerBulletTest`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 全绿。
6. **【负责人追加】闭合 `R-E0 R2-S2/S3`（详情登录态自动重载） — 通过**
   - `ThreadDetailViewModel` 主构造 `@Inject` 注入 `AuthRepository` 并保留与 `E0` 一致的 2 参次构造；通过 `observeAuthRecovery()` 监听 `currentAccount`、`authState` 与 `sessionEpoch`，在 `AuthRequired` 状态下登录成功或会话恢复后自动按 `requestedPage` 与当前 `filter` 重载，无需手动刷新。
   - `ThreadDetailViewModelTest.authRequired_autoReloadsOnLoginAndSessionReauthWithoutManualRefresh` 与 `authRecovery_doesNotReloadWhenNotInAuthRequiredState` 验证了首屏登录恢复、非首页（第 2 页）会话失效重登恢复及正常已加载状态下不误触发重载。

---

## 3. 对外 API 清单（签名 + 文件:行号，供 `E2b` / `E2c` / `E2d` / `E3` / `E4` 复用）

### 3.1 帖子详情面板与 ViewModel（`feature/threaddetail/`）
- `sealed interface ThreadDetailFilterUi` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt:23`
  - `data object All : ThreadDetailFilterUi` (`:24`)
  - `@Immutable data class AuthorOnly(val authorId: String? = null) : ThreadDetailFilterUi` (`:25`)
  - `@Immutable data class UserOnly(val uid: String, val username: String) : ThreadDetailFilterUi` (`:26`，预留给 `E2c`)
  - `fun toRepositoryFilter(): ThreadDetailFilter` (`:28`)
- `@Immutable data class PendingScrollTarget(val id: Long, val postId: String?, val scrollOffset: Int = 0)` — `ThreadDetailViewModel.kt:36`
- `@Immutable data class ThreadDetailUiState(val tid: String = "", val title: String = "", val threadAuthorId: String? = null, val threadAuthorName: String? = null, val page: Int = 1, val lastPage: Int = 1, val filter: ThreadDetailFilterUi = ThreadDetailFilterUi.All, val floors: List<ThreadDetailFloorItem> = emptyList(), val isLoading: Boolean = false, val error: UiError? = null, val challengeFailure: ChallengeFailureKind? = null, val challengeState: ChallengeState = ChallengeState.Idle, val pendingScroll: PendingScrollTarget? = null, val contentRevision: Int = 0)` — `ThreadDetailViewModel.kt:39`
- `@HiltViewModel class ThreadDetailViewModel @Inject constructor(threadDetailRepository: ThreadDetailRepository, siteAccessGateway: SiteAccessGateway, authRepository: AuthRepository) : ViewModel()` — `ThreadDetailViewModel.kt:83`
  - 兼容 2 参次构造：`constructor(threadDetailRepository: ThreadDetailRepository, siteAccessGateway: SiteAccessGateway)` (`:89`)
  - `val uiState: StateFlow<ThreadDetailUiState>` (`:103`)
  - `fun bindArgs(args: ThreadNavArgs)` (`:134`)
  - `fun loadPage(page: Int, priority: AccessPriority = AccessPriority.UserBlocking)` (`:149`)
  - `fun previousPage()` (`:152`) / `fun nextPage()` (`:157`)
  - `fun retry(priority: AccessPriority = AccessPriority.UserBlocking)` (`:162`)
  - `fun toggleAuthorOnly(anchorPostId: String? = null, anchorScrollOffset: Int = 0)` (`:165`)
  - `fun filterByUser(uid: String, username: String, anchorPostId: String? = null, anchorScrollOffset: Int = 0)` (`:176`，预留给 `E2c`)
  - `fun clearFilter()` (`:183`)
  - `fun consumePendingScroll(tokenId: Long)` (`:198`)
- `@Composable fun ThreadDetailPane(args: ThreadNavArgs, chromeSpec: DetailChromeSpec, onBack: () -> Unit, onOpenLogin: () -> Unit, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState(), contentPadding: PaddingValues = PaddingValues(0.dp), onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit = {}, onOpenReply: (ReplyRequest) -> Unit = {}, onOpenUserProfile: (uid: String, username: String) -> Unit = { _, _ -> }, viewModel: ThreadDetailViewModel = viewModel(key = "thread:${args.tid}"))` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt:40`
  - 楼层 `LazyColumn` 固定主键：`items(items = uiState.floors, key = { "floor:${it.postId}" })`（`ThreadDetailPane.kt:132`）
- 内部同包辅助组件（供 `E2c`/`E2d`/`E4` 同包复用）：
  - `internal val ThreadDetailTopBarHeight: Dp = 60.dp` — `ThreadDetailTopBar.kt:46`
  - `internal fun ThreadDetailTopBar(...)` — `ThreadDetailTopBar.kt:53`
  - `internal val FilterCapsuleBarHeight: Dp = 56.dp` — `FilterCapsuleBar.kt:30`
  - `internal fun FilterCapsuleBar(filter: ThreadDetailFilterUi, onClearFilter: () -> Unit, modifier: Modifier = Modifier)` — `FilterCapsuleBar.kt:34`
  - `internal val PostMaxContentWidth = 680.dp` — `PostFloorCard.kt:27`
  - `internal fun PostFloorCard(item: ThreadDetailFloorItem, isOp: Boolean, listState: LazyListState, onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit, onAuthorClick: (uid: String, username: String) -> Unit, modifier: Modifier = Modifier, onLinkClick: ((String) -> Unit)? = null)` — `PostFloorCard.kt:31`

### 3.2 Compose 原生正文、表情与对比度调色（`feature/threaddetail/body/`）
- `@Composable fun PostBodyRenderer(blocks: List<ContentBlock>, floorPostId: String, listState: LazyListState, onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit, modifier: Modifier = Modifier, onLinkClick: ((String) -> Unit)? = null)` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/PostBodyRenderer.kt:113`
- `object TextColorContrastAdjuster` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/TextColorContrastAdjuster.kt:20`
  - `const val MIN_CONTRAST_RATIO: Float = 4.5f` (`:22`)
  - `fun wcagContrastRatio(first: Color, second: Color): Float` (`:27`)
  - `fun parseCssColor(rawColor: String?): Color?` (`:34`)
  - `fun resolveCssTextColor(rawColor: String?, background: Color, fallback: Color, minContrastRatio: Float = MIN_CONTRAST_RATIO): Color` (`:46`)
  - `fun adjustForBackground(foreground: Color, background: Color, minContrastRatio: Float = MIN_CONTRAST_RATIO): Color` (`:54`)
- `sealed interface ResolvedForumEmoji` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/InlineEmojiContent.kt:24`
  - `data class Drawable(@param:DrawableRes val drawableResId: Int, val code: String?) : ResolvedForumEmoji` (`:25`)
  - `data class FallbackText(val text: String) : ResolvedForumEmoji` (`:26`)
- `fun resolveForumEmoji(normalizedKey: String, darkTheme: Boolean): ResolvedForumEmoji` — `InlineEmojiContent.kt:37`
- `@DrawableRes fun resolveSmallImageDrawable(key: String): Int?` — `InlineEmojiContent.kt:47`

### 3.3 Wave 3 `E2b` 并行接缝（`feature/threaddetail/media/PostImageBlock.kt`）
- `@Composable fun PostImageBlock(image: ContentBlock.Image, floorPostId: String, listState: LazyListState, onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit, modifier: Modifier = Modifier)` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt:28`

---

## 4. `git diff --stat db7dba6..HEAD`

```text
 .../hipda/feature/threaddetail/FilterCapsuleBar.kt |  79 +++
 .../hipda/feature/threaddetail/PostFloorCard.kt    |  97 +++
 .../hipda/feature/threaddetail/ThreadDetailPane.kt | 239 +++++---
 .../feature/threaddetail/ThreadDetailTopBar.kt     | 180 ++++++
 .../feature/threaddetail/ThreadDetailViewModel.kt  | 264 ++++++++-
 .../threaddetail/body/InlineEmojiContent.kt        |  71 +++
 .../feature/threaddetail/body/PostBodyRenderer.kt  | 208 +++++++
 .../feature/threaddetail/body/TextColorContrastAdjuster.kt | 182 ++++++
 .../feature/threaddetail/media/PostImageBlock.kt   |  50 ++
 .../src/main/res/values/strings_thread_detail.xml  |  13 +
 .../feature/threaddetail/PostBodyRendererTest.kt   | 266 +++++++++
 .../feature/threaddetail/TextColorContrastAdjusterTest.kt  | 146 +++++
 .../feature/threaddetail/ThreadDetailScreenshotTest.kt     | 470 +++++++++++++++
 .../feature/threaddetail/ThreadDetailViewModelTest.kt      | 650 +++++++++++++++++++++
 .../images/thread_detail_pane411_filter_light.png  | Bin 0 -> 90381 bytes
 .../images/thread_detail_pane448_dark.png          | Bin 0 -> 114348 bytes
 .../images/thread_detail_pane680_light.png         | Bin 0 -> 125424 bytes
 .../snapshots/images/thread_detail_w411_dark.png   | Bin 0 -> 163484 bytes
 .../thread_detail_w411_empty_filter_light.png      | Bin 0 -> 36691 bytes
 .../images/thread_detail_w411_error_light.png      | Bin 0 -> 32280 bytes
 .../snapshots/images/thread_detail_w411_light.png  | Bin 0 -> 163807 bytes
 .../thread_detail_w411_oled_pure_black.png         | Bin 0 -> 162688 bytes
 .../images/thread_detail_w411_skeleton_light.png   | Bin 0 -> 34161 bytes
 .../thread_detail_w411_step5_scale13_light.png     | Bin 0 -> 141773 bytes
 .../snapshots/images/thread_detail_w559_light.png  | Bin 0 -> 119630 bytes
 .../snapshots/images/thread_detail_w640_dark.png   | Bin 0 -> 165657 bytes
 .../thread_detail_w933_empty_filter_dark.png       | Bin 0 -> 49387 bytes
 .../images/thread_detail_w933_error_dark.png       | Bin 0 -> 44205 bytes
 .../images/thread_detail_w933_skeleton_dark.png    | Bin 0 -> 46552 bytes
 29 files changed, 2809 insertions(+), 106 deletions(-)
```


---

## 5. 修复轮 1（回应 `R-E2a` `CHANGES-REQUESTED`）

- **合并基线**：已合并 `origin/main` @ `21f41df`（合并提交 `772e4df`，0 冲突）
- **修复提交（HEAD）**：`332c31cb207a853fd46bad774824f348e4fa701f`（`fix(threaddetail): 增加双层链接 scheme 白名单并落实 R-E2a S1/S3/S5 修复`）
- **GitHub Actions CI 证据**：
  - [Android CI #45687741985](https://github.com/mailwmj/hipda-next/actions/runs/45687741985) — `Build, Lint & Unit Tests`（Job `139464712079`）**SUCCESS**
  - 全仓单测与 Roborazzi 校验汇总：**`TOTAL: 1011 tests, 1011 passed, 0 failed, 0 errors, 0 skipped`**（含 `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、`:core-parser:test`、`:emoji:testDebugUnitTest`、全量 `:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 及 APK 产物校验）
- **本地串行校验（遵循 `docs/agents/DISPATCH.md` §2）**：
  - `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`：**`913 tests, 913 passed, 0 failed, 0 skipped`**
    - `PostBodyRendererTest`：6/6 通过
    - `TextColorContrastAdjusterTest`：5/5 通过
    - `ThreadDetailViewModelTest`：8/8 通过
    - `ThreadDetailScreenshotTest`：15/15 通过（仅更新因 `Surface` 替换带来圆角 1px 抗锯齿变化的 9 张 `thread_detail_*.png`，其余非 `thread_detail_*` 快照 0 漂移）
    - `CopyTerminologyScanTest`：3/3、`MotionTokenScanTest`：7/7、`NextAppTracerBulletTest`：9/9 全通过
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**，SARIF 核查本卡全部文件与 `strings_common.xml` **0 条告警**，结束后已 `./gradlew --stop`。
- **生产代码行数（`hipda/src/main/`，10 个文件）**：`1,371` 行（相对 `E0` 基线 233 行**净增 `+1,138` 行**，较初版 `1,404` 行净减 33 行；其中 S1 删减约 82 行冗余，B1 新增约 49 行安全白名单与双层守卫；未触碰 `:core-parser`）。

### 5.1 逐项修复对照与对应测试名

1. **B1（阻断，安全：渲染层 + 点击层双层链接 scheme 白名单）**：
   - 在 `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/PostBodyRenderer.kt:45` 新增纯函数 `fun sanitizePostUrl(rawUrl: String?): String?`：仅放行经 `OkHttp HttpUrl.toHttpUrlOrNull()` 校验且含非空 host 的 `http`/`https`，以及经严格邮箱字符集正则（禁止 `?`/`=`/`&`/`#` 等 URI 参数与控制字符）校验的 `mailto:`；拒绝 `javascript:`、`intent:`、`file:`、`content:`、`data:`、无 scheme、大小写变体、前导/尾随空白（如 `" JavaScript:"`）及控制字符。`:core-parser` 保持原样未改。
   - **渲染层**：`buildPostTextBlock`（`:94-110`）对不通过白名单的 `InlineSpan.Link` 与 `InlineSpan.Email` 退化为纯文本、不生成 `LinkAnnotation`；合法链接显式挂非空 `LinkInteractionListener` 防止回退裸 `LocalUriHandler`；`PostAttachmentBlockView`（`:226-243`）对非法附件 URL 渲染为不可点击 `Surface`。
   - **点击层**：`PostBodyRenderer`（`:162-168`）`resolvedLinkHandler` 入口再次调用 `sanitizePostUrl(rawUrl)` 守卫，拦截任何非法 scheme 进入 `onLinkClick` 或 `LocalUriHandler.openUri`。
   - **对应测试**：
     - `PostBodyRendererTest.sanitizePostUrl_allowsOnlyHttpHttpsAndValidatedMailto`
     - `PostBodyRendererTest.dangerousSchemes_renderAsPlainTextWithoutLinkAnnotationAndNeverInvokeLocalUriHandler`（逐一断言每种危险 scheme 及附件均无 `LinkAnnotation` 且点击字形坐标后 `LocalUriHandler` 调用次数为 `0`）
     - `PostBodyRendererTest.validHttpHttpsAndMailtoLinks_generateLinkAnnotationsAndInvokeLocalUriHandler`（断言合法 `http`/`https`/`mailto:` 链接、邮箱与附件经默认 `LocalUriHandler` 正常可点）
2. **S1（删减约 82 行冗余，逐项落实 `R-E2a.md` §2）**：
   - `ThreadDetailViewModel.kt`（316→288）：改为 2 参主构造 + `@Inject` 3 参次构造，彻底删除 `UnboundAuthRepository` 与空流协程；删除 `contentRevision`/`bumpRevision` 全链路；删除生产 0 调用的 `filterByUser(...)`（保留 `ThreadDetailFilterUi.UserOnly`）；将 `observeAuthRecovery` 合并为单协程 `combine(currentAccount, authState)`。
   - `ThreadDetailPane.kt`（208→187）：删除会造成切页首帧闪烁的 `contentAlpha` / `initialRevision` / `LaunchedEffect(contentRevision)`。
   - `ThreadDetailTopBar.kt`（180→177）：删除独立底线 `Box` 节点，合并至父级 `drawBehind`。
   - `FilterCapsuleBar.kt`（79→72）与 `strings_thread_detail.xml`（13→12）：合并重复的 `只看楼主` 字符串并精简胶囊状态缓存。
   - `InlineEmojiContent.kt`（71→64）：内联单处调用的前缀常量与 ID 包装函数。
   - `PostBodyRenderer.kt`（`PostAttachmentBlockView`）与 `PostImageBlock.kt`（50→46）：改用标准 `Surface(onClick = ..., border = BorderStroke(...))`。
   - `TextColorContrastAdjuster.kt`（182→177）：收敛非 Discuz 冗余命名颜色。
   - **对应测试**：`ThreadDetailViewModelTest`（8/8，含 `filterCapsuleBar_supportsReservedUserOnlyStateForWave3E2c`、`authRequired_autoReloadsOnLoginAndSessionReauthWithoutManualRefresh`、`topBarGlassTopology_strictSiblingZeroNestedGlassAnd48dpTouchTargets`）、`NextAppTracerBulletTest`（9/9）、`CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）、`ThreadDetailScreenshotTest`（15/15）。
3. **S3（对比度测试补强上界与通道差下界）**：
   - `TextColorContrastAdjusterTest.adjustedChromaticColors_preserveDominantHueFamily` 对白底 `#FF0000`、白底 `#FFFF00`、OLED 纯黑 `#000080` 补充 `ratio in 4.5f..5.5f` 与主副通道差 `>= 0.25f` 断言。
4. **S4（对齐对外 API 参数名）**：
   - 已将 §3.1 `PostFloorCard` 第 5 参数由 `onOpenUserProfile` 对齐为实际源码签名 `onAuthorClick: (uid: String, username: String) -> Unit`。
5. **S5（调色背景色语义对齐）**：
   - `PostBodyRenderer.kt:188-190` `PostTextBlockView` 改为传入 `cs.background`（与楼层实际绘制底色一致）。
   - **对应测试**：`PostBodyRendererTest.realSamplesP1AndP2_emojiKeysHitEmojiMapInLightAndDarkAndDecodeSupplementaryEmoji`、`ThreadDetailScreenshotTest`（15/15）。
6. **S2（头像热区）**：按负责人要求留给 `E2c`，本轮未改 `PostFloorCard.kt`。

### 5.2 修复轮 1 对外 API 变更（对齐最新行号）
- **新增**：`fun sanitizePostUrl(rawUrl: String?): String?` — `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/PostBodyRenderer.kt:45`
- **更新**：`ThreadDetailUiState` 移除 `contentRevision` — `ThreadDetailViewModel.kt:41`
- **更新**：`ThreadDetailViewModel` 主构造为 2 参 `(threadDetailRepository, siteAccessGateway)`，`@Inject` 3 参次构造接收 `authRepository`（`:83,:89`）；移除 `filterByUser(...)`（保留 `ThreadDetailFilterUi.UserOnly` 供 `E2c` 与 `FilterCapsuleBar` 复用）
- **更新**：`ThreadDetailPane` 位于 `ThreadDetailPane.kt:36`；`PostBodyRenderer` 位于 `PostBodyRenderer.kt:152`；`PostImageBlock` 位于 `PostImageBlock.kt:26`

### 5.3 `git diff --stat origin/main...HEAD`（对比 `21f41df`）

```text
 .../hipda/feature/threaddetail/FilterCapsuleBar.kt |  72 +++
 .../hipda/feature/threaddetail/PostFloorCard.kt    |  97 +++
 .../hipda/feature/threaddetail/ThreadDetailPane.kt | 220 ++++---
 .../feature/threaddetail/ThreadDetailTopBar.kt     | 177 ++++++
 .../feature/threaddetail/ThreadDetailViewModel.kt  | 240 +++++++-
 .../threaddetail/body/InlineEmojiContent.kt        |  64 ++
 .../feature/threaddetail/body/PostBodyRenderer.kt  | 251 ++++++++
 .../feature/threaddetail/body/TextColorContrastAdjuster.kt | 177 ++++++
 .../feature/threaddetail/media/PostImageBlock.kt   |  46 ++
 .../src/main/res/values/strings_thread_detail.xml  |  12 +
 .../feature/threaddetail/PostBodyRendererTest.kt   | 454 ++++++++++++++
 .../feature/threaddetail/TextColorContrastAdjusterTest.kt  | 157 +++++
 .../feature/threaddetail/ThreadDetailScreenshotTest.kt     | 470 +++++++++++++++
 .../feature/threaddetail/ThreadDetailViewModelTest.kt      | 670 +++++++++++++++++++++
 .../images/thread_detail_pane411_filter_light.png  | Bin 0 -> 90369 bytes
 .../images/thread_detail_pane448_dark.png          | Bin 0 -> 114348 bytes
 .../images/thread_detail_pane680_light.png         | Bin 0 -> 125424 bytes
 .../snapshots/images/thread_detail_w411_dark.png   | Bin 0 -> 163491 bytes
 .../thread_detail_w411_empty_filter_light.png      | Bin 0 -> 36691 bytes
 .../images/thread_detail_w411_error_light.png      | Bin 0 -> 32280 bytes
 .../snapshots/images/thread_detail_w411_light.png  | Bin 0 -> 163782 bytes
 .../thread_detail_w411_oled_pure_black.png         | Bin 0 -> 162679 bytes
 .../images/thread_detail_w411_skeleton_light.png   | Bin 0 -> 34161 bytes
 .../thread_detail_w411_step5_scale13_light.png     | Bin 0 -> 141748 bytes
 .../snapshots/images/thread_detail_w559_light.png  | Bin 0 -> 119617 bytes
 .../snapshots/images/thread_detail_w640_dark.png   | Bin 0 -> 165664 bytes
 .../thread_detail_w933_empty_filter_dark.png       | Bin 0 -> 49387 bytes
 .../images/thread_detail_w933_error_dark.png       | Bin 0 -> 44205 bytes
 .../images/thread_detail_w933_skeleton_dark.png    | Bin 0 -> 46552 bytes
 29 files changed, 2998 insertions(+), 109 deletions(-)
```
