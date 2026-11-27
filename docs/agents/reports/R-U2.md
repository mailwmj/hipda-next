# R-U2 独立代码与视觉审查报告

## 1. 审查结论与核验元数据

- **结论**：**APPROVE**（0 阻断项，2 项非阻断建议）
- **被审任务卡**：`/home/mir/Documents/hipda-next.wt/_cards/U2.md`（右栏帖子详情去网页化与阅读区克制化改造）
- **审查卡**：`/home/mir/Documents/hipda-next.wt/_cards/R-U2.md` + `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`
- **分支与 Commit**：`card/U2-detail-deweb-polish` @ `239a1688293c8a1ac07a8047108b850e42c8f33d`（已 rebase 至含 `U1` 的 `origin/main` 基线 `3a1f5b1`；包含 `4023b0f`、`307509d`、`239a168` 共 3 个提交）
- **独立只读纪律**：全程未阅读实现者报告 `_reports/U2.md`；`/home/mir/Documents/hipda-next` 与 `/home/mir/Documents/hipda-next.wt/U2` 均保持 0 写入、0 checkout、0 本地编译。
- **CI 核验**：[GitHub Actions Run #46051574839](https://github.com/mailwmj/hipda-next/actions/runs/46051574839)
  - `headBranch`: `card/U2-detail-deweb-polish`
  - `headSha`: `239a1688293c8a1ac07a8047108b850e42c8f33d`（与目标 Commit 完全一致）
  - `status` / `conclusion`: `completed` / `success`
  - 已核实通过步骤（对照 `.github/workflows/android.yml:62-160`）：
    - Step 6 `Build and lint`：`./gradlew assembleDebug assembleRelease lintDebug --stacktrace` → `success`
    - Step 7 `Verify Room schema consistency`：`hipda/schemas/` 零漂移 → `success`
    - Step 8 `Run unit tests and verify Roborazzi screenshots`：`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true --continue --stacktrace` → `success`
    - Step 11 `Verify APK outputs`：debug/release APK 包名、Baseline Profile 资产与 101 枚表情资源校验 → `success`

---

## 2. `docs/agents/lesson.md` 命中条款核查

| `lesson.md` 行号 | 经验条款 | 核查结果 |
|---|---|---|
| `lesson.md:26` | 只读审查员严禁在主目录 `git checkout` | **通过**：仅执行只读 `git fetch/diff/show`、`gh run view` 与 `read_file`，`hipda-next` 保持 `main...origin/main` 干净状态。 |
| `lesson.md:28` | 界面卡防膨胀：核查生产代码行数（≤ 估计 1.5×）、标准组件优先、生产零测试钩子 | **通过**：生产代码 `5` 个文件共 `+130 / -141`（净 `-11` 行，吻合卡片 `120–150` 行预期）；全用标准 Compose 组件（`Row`/`Box`/`BoxWithConstraints`/`AnimatedVisibility`/`HorizontalDivider`/`derivedStateOf`），0 手写 `Layout`/`Modifier.Node`，0 测试专用语义键或钩子。 |
| `lesson.md:30` | 并行卡测试文件无越界重叠 | **通过**：跨模块集成测试严格只改授权的 4 行（`EmojiPanelBehaviorTest.kt:534`、`QuickReplyIntegrationBackPriorityTest.kt:136,291`、`FullEditorIntegrationTest.kt:361`），未触碰 `HiTopBarScrollToTopTest.kt` 等非授权测试；并在 rebase 至合入 `U1` 的 `3a1f5b1` 后重录 18 张跨三栏合并态快照。 |
| `lesson.md:33` | 站点内容/外链必须走 `http`/`https`/经校验 `mailto` 白名单并配危险 scheme 反例测试 | **通过**：`ThreadBottomToolbar.kt:71-123` 外链消毒与打开/分享/复制纯文本逻辑零削弱，`ThreadBottomToolbarTest.kt:616-743` 保留 19 种危险/畸形 URL 反例测试。 |
| `lesson.md:35` | 跨卡浮层、返回栈拦截与玻璃拓扑真实链路守卫 | **通过**：底部工具条保持单 `HiGlassRegion`（1 个 `HiGlassMarker`）、`suppressBottomGlass` 整块卸载、`LocalHiGlassSuspended` 看图暂停采样；`MultiPaneGlassBudgetTest` 全屏 `HiGlassMarker ≤ 3`、`LayerBackdrop ≤ 2`、静止 `drawBackdrop ≤ 5` 零破坏。 |
| `lesson.md:36` | `BackHandler` 仅在打开时条件启用，避免抢占下层栈 | **通过**：`ThreadBottomToolbar.kt:159,163` 使用 `effectiveJumpBarExpanded = isJumpBarExpanded && canJumpPage` 控制 `BackHandler(enabled = effectiveJumpBarExpanded)`，单页帖绝不挂载无效返回拦截，多页收起态不抢占 `PositionStack`（`ThreadDetailPane.kt:501`）。 |
| `lesson.md:38` | 新增/变更的 Roborazzi 截图基线必须由 `R` 逐张目视核验 | **通过**：本报告第 5 节已通过 `read_file` 逐张目视核验全部 `62/62` 张变更 PNG 快照，确认零空白、零裁切、零重叠、深浅色/OLED 一致且文案逐字对齐 `docs/design/copy.md`。 |
| `lesson.md:40` | 防止改坏核心逻辑仍绿的空心断言 | **通过**：新增 4 组针对性单测直接锁死 `56.dp` 高度、无底无框按钮、`onSurfaceVariant` 完整 alpha=1f + `tnum`、全 20 套预设 × 3 模式 WCAG ≥ 4.5:1、单页被动/刷新变多页恢复可点、`340dp`/`360dp` + `1.25×` 紧凑无裁切；且在 `ContinuousPagingAnchorTest`/`ReadingPositionAndStackTest` 将胶囊描述由页码改为楼层时同步补上了 `assertEquals(..., vm.uiState.value.page)`，未丢失页码状态断言。 |

---

## 3. 边界、禁止文件与改动统计核查

### 3.1 禁止修改文件零触碰核实（`git diff --name-status 3a1f5b1...239a168`）
- `HiGlassSurface.kt` / `HiGlassTokens`：**0 改动**（`glass_specimen_*.png` 全部 12 张样张 **0 漂移**）
- `InlinePageJumpBar.kt`、`HiNavigationSidebar.kt`、`ForumTopBar.kt`、`ThreadListRow.kt`、`ForumPane.kt`、`QuickReplySheet.kt`、`FullEditorScreen.kt`、`FullEditorViewModel.kt`、`PostImageBlock.kt`、`docs/design/copy.md`：**0 改动**
- 共享构建与动效白名单（`gradle/libs.versions.toml`、`build.gradle.kts`、`AndroidManifest.xml`、`.github/`、`MotionTokenScanTest`）：**0 改动**
- `ThreadBottomToolbarHeight`（`48.dp`）、`ThreadBottomToolbarBottomGap`（`8.dp`）、`ThreadBottomToolbarClearance`、`PositionStack` 与 `PrependAnchorController`：**0 改动**

### 3.2 改动文件清单与行数
| 类别 | 文件 | `+/-` 行数 | 核查结论 |
|---|---|---|---|
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailTopBar.kt` | `+43 / -48` | 合规 |
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/PostFloorCard.kt` | `+5 / -4` | 合规 |
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt` | `+15 / -1` | 合规 |
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt` | `+65 / -86` | 合规 |
| 生产 | `hipda/src/main/res/values/strings_thread_detail.xml` | `+2 / -2` | 合规（逐字对齐 `copy.md:132`） |
| **生产合计** | **5 个文件** | **`+130 / -141`（净 `-11`）** | **简洁克制** |
| 测试 | `ThreadBottomToolbarTest.kt` | `+289 / -42` | 授权更新 + 新增 U2 全部验收单测 |
| 测试 | `ContinuousPagingAnchorTest.kt` / `InlinePageJumpBarTest.kt` / `ReadingPositionAndStackTest.kt` / `ThreadDetailViewModelTest.kt` / `ThreadChromeScreenshotTest.kt` | `+40 / -35` | 授权更新中段胶囊无障碍描述，并补齐 `page` 断言 |
| 测试 | `FullEditorIntegrationTest.kt:361`、`QuickReplyIntegrationBackPriorityTest.kt:136,291`、`EmojiPanelBehaviorTest.kt:534` | `+4 / -4` | 精确命中卡片授权的 4 行，其余行 0 改动 |
| 测试 | `NextAppTracerBulletTest.kt:858-860` | `+3 / -1` | 因顶栏/分隔线高度预期变化导致的合理修正（见 4.4 节） |
| 快照 | `hipda/src/test/snapshots/images/*.png` | `62` 张更新 | 全部由详情顶栏/楼层/底栏可见变化直接引起，非详情快照 0 漂移 |

---

## 4. 本卡重点逐项技术核查（`R-U2.md` 1–4）

### 4.1 `ThreadDetailTopBar.kt`（56dp 对齐、去副标题、无底纯图标、侧栏隐喻）
- **高度对齐**：`ThreadDetailTopBar.kt:46` 将 `ThreadDetailTopBarHeight` 设为 `56.dp`，`ThreadBottomToolbarTest.kt:367-368` 直接断言 `FORUM_TOP_BAR_TITLE_ROW_HEIGHT == ThreadDetailTopBarHeight` 且等于 `56.dp`；`ThreadDetailPane.kt:692` 的 `topClearance` 自动随之同步为 `statusBarTop + 56.dp (+ FilterCapsuleBarHeight)`。
- **单行标题与左右边距对齐**：`ThreadDetailTopBar.kt:95,101-110` 移除原 `Column` 与 `楼主 · $opName` 副标题，改为单行 `titleMedium` 居中左对齐；无返回键（三栏/铰链根详情）时 `start = 16.dp, end = 4.dp`，与中栏 `ForumTopBar.kt:255`（`start = 16.dp, end = 4.dp`）像素级一致。
- **纯 `48.dp` 无底无框动作按钮**：`TopBarActionButton`（`ThreadDetailTopBar.kt:132-150`）移除 `40.dp` `surfaceContainerHigh` 圆底与 `border`，改为 `48.dp` 纯点击热区包裹 `22.dp` `Icon`（`active = true` 着 `cs.primary`，常态着 `cs.onSurface`）：
  - 只看楼主切换（`:119-124`）：常态 `Icons.Outlined.Person`（`onSurface`）↔ 激活态 `Icons.Rounded.Person` 实心（`primary`）。
  - 全屏阅读切换（`:111-118, 159-181`）：常态 `TopBarVectorIcons.HideList`（圆角面板 + 左侧栏分割线 + 向左收起折线，`onSurface`）↔ 激活态 `TopBarVectorIcons.ShowList`（左侧栏实心填充 `path(fill = strokeBrush)` + 向右展开折线，`primary`），彻底消除与 `QuickReplySheet` 右上角对角箭头「展开全屏」的同屏符号撞车。
- **静止态无底线 / 滚动渐显细线**：`ThreadDetailTopBar.kt:83-87` 在 `isScrolledState.value == false` 时纯 `cs.surface` 且不绘底线；滚动后以 `HiMotion.effectsDefault()` 渐显液态玻璃与底部 `0.5.dp` `cs.outlineVariant` 分隔线。

### 4.2 `PostFloorCard.kt` & `ThreadDetailPane.kt`（楼层去蓝、WCAG 对比度、内缩细分隔线、`visibleFloor`）
- **楼层号**：`PostFloorCard.kt:151-154` 使用 `MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")`（经 `remember(style)` 缓存）与完整不透明 `color = cs.onSurfaceVariant`（未叠加任何 alpha 衰减）；被警告楼层 `WarnedFloorBadge`（`:144-149`）保持 `errorContainer` 警示样式不变。`ThreadBottomToolbarTest.kt:389-473` 对全部 `20` 套预设色 × 浅色/深色/OLED 纯黑验证 `onSurfaceVariant.alpha == 1f` 且在 `background`/`surface` 及工具条降级态/激活态表面上对比度均 `≥ 4.5:1`，并通过 `SemanticsActions.GetTextLayoutResult` 实测渲染文本样式与颜色。
- **`楼主` 标识**：`PostFloorCard.kt:137-139` 移除 `primaryContainer` `Surface` 底色胶囊，改为纯文字 `Text(opBadge, style = MaterialTheme.typography.labelSmall, color = cs.primary, maxLines = 1)`；作者行整体 `mergeDescendants = true` 无障碍描述 `"${floor.author}，$opBadge，${floor.timePost}"`（`:98-106`）完整保留。
- **楼层分隔线**：`ThreadDetailPane.kt:907-911` 改为 `Modifier.widthIn(max = PostMaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp)`、`thickness = 0.5.dp`、`color = cs.outlineVariant.copy(alpha = 0.5f)`，无论常规宽还是 `> 680dp` 居中正文区，均精确对齐 `PostFloorCard` 左右 `16.dp` 正文起止边界。
- **`visibleFloor` 派生同步**：`ThreadDetailPane.kt:519-526` 新增 `val visibleFloor by remember(listState) { derivedStateOf { ... } }`，复用 `firstVisibleFloor(listState)` 与 `resolveFloorIndex(...)`（与 `visiblePage` 完全同源），读取首个可见楼层 `PostFloor.floor`（兜底首项或 `1`）传入 `ThreadBottomToolbar(currentFloor = visibleFloor)`（`:983`）。因读取 `latestUiState`（`MutableState`）与 `listState.layoutInfo`，滚动跨楼层与跳页/过滤换内容均实时更新，且楼层内滚动不触发多余重组。

### 4.3 `ThreadBottomToolbar.kt` & `strings_thread_detail.xml`（底栏去网页化与三段伸缩互换）
- **清理网页页码与蓝底进度**：已彻底删除 `ProgressCapsuleColors`、`resolveProgressCapsuleColors`、半透明蓝色进度条绘制、页码拼接文案及 `HiRollingNumber`（全仓 grep 确认生产/测试源码 0 残留引用），删除废弃字符串 `thread_detail_page_indicator_compact` 与 `thread_detail_page_capsule_description`，新增 `thread_detail_floor_capsule_description_static`（`第 %1$d 楼`）与 `thread_detail_floor_capsule_description_jumpable`（`第 %1$d 楼，点按展开跳页`）（`strings_thread_detail.xml:50-51`，逐字匹配 `docs/design/copy.md:132`）。
- **三段宽度分配与窄容器紧凑降级**（`ThreadBottomToolbar.kt:211-224, 255-269`）：
  - 中段 `collapsedMiddleWidth = (floorTextWidthDp + 28.dp).coerceIn(48.dp, maxMiddleWidth)`，贴合 `{N}楼` 文本宽度并保证 `≥ 48.dp` 触控宽度。
  - 左段 `remainingReplyWidth = collapsedBarWidth - collapsedMiddleWidth - RightCapsuleWidth - ToolbarSegmentGap * 2`，最小完整回复槽阈值 `minFullReplyWidth = (18.dp + 8.dp + replyTextWidthDp + 32.dp).coerceAtLeast((136f * density.fontScale).dp)`：常规宽度（含 `360dp @ 1.0×`）下左段伸展为左对齐轻输入槽（`Arrangement.Start`、`16.dp` 水平内边距、`18.dp` `onSurface` 笔形图标 + `8.dp` 间距 + `onSurfaceVariant` `回复`）；窄容器叠加放大字号（`340dp`/`360dp` + `1.25×`）时 `remainingReplyWidth < minFullReplyWidth` 自动置 `isCompact = true`，左段缩为 `48×48dp` 居中纯图标胶囊（保留 `contentDescription = "回复"`），整体按 `actualBarWidth` 居中排布，零裁切零重叠。
- **单页被动 vs 多页可点展开跳页**（`ThreadBottomToolbar.kt:158-163, 345-369`）：
  - `lastPage == 1`（`canJumpPage == false`）：中段无 `clickable`、无 `hiPressScale`、无 `Role.Button`，仅挂 `semantics(mergeDescendants = true) { contentDescription = "第 {N} 楼" }`（内部 `Text` 使用 `clearAndSetSemantics {}` 防止 TalkBack 重复朗读 `"第 1 楼，1楼"`）。
  - `lastPage > 1`（`canJumpPage == true`）：中段挂 `hiPressScale` + `clickable(role = Role.Button)`，无障碍描述 `"第 {N} 楼，点按展开跳页"`（保留 `"点按展开跳页"` 后缀兼容 `MultiPaneGlassBudgetTest.kt:454,550`），点击原地展开既有 `InlinePageJumpBar`；刷新后 `lastPage` 从 `1` 变为 `> 1` 时立即可点，反之若从多页切至单页（如开只看楼主仅剩 1 页）则通过 `effectiveJumpBarExpanded` 与 `LaunchedEffect(canJumpPage, isJumpBarExpanded)` 自动收起跳页条。
- **右段与玻璃拓扑**：右段保持 `96.dp`（`收藏` + `更多` 两个 `48×48dp` 按钮），整块底栏仍保持唯一外层 `HiGlassRegion` 包裹 3 枚 `HiGlassStyle.Toolbar` 胶囊。

### 4.4 `NextAppTracerBulletTest.kt:855` 非零尺寸过滤专项核查
- **变更内容**（提交 `307509d`，`NextAppTracerBulletTest.kt:850-861`）：测试辅助函数 `findFirstVisibleFloorText(tid)` 在 `.fetchSemanticsNodes()` 后由 `.first()` 改为 `.first { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }`。
- **根因验证**：`singlePaneToThreePaneAndBack_preservesViewModelScrollAndZeroExtraRequests`（`:204`）与 `liveThemeAndFontSwitch_updatesRealNextAppRootHiThemeWithZeroReloads`（`:683`）分别将详情 `LazyColumn` 向下滚动 `1200f` / `1100f` px 后调用 `findFirstVisibleFloorText`。本卡将顶栏由 `60.dp` 改为 `56.dp`（xhdpi 下 `-8px`）且楼层分隔线由 `1.dp` 改为 `0.5.dp`（每楼 `-1px`），使滚过 `1200px` 处的临界楼层出现「`PostFloorCard` 底部 `14.dp` 内边距仍留在 `LazyColumn` 视口内（条目未回收），但其内部正文 `Text` 子节点已完全滚出 `LazyColumn` 顶部 `clipToBounds` 裁切线（`SemanticsNode.boundsInRoot` 宽高被裁为 `0×0`）」的正常几何现象。旧版 `.first()` 未判可见性，误取到该已滚出顶部的 `0×0` 文本节点，导致随后 `:226` `assertIsDisplayed()` 失败。
- **非放宽断言确认**：`.first { width > 0f && height > 0f }` 仅使辅助函数名副其实地取回视口内首个可见楼层正文（若列表渲染异常为空或全部 `0×0`，`.first` 会直接抛 `NoSuchElementException` 变红），后续 `:226` `assertIsDisplayed()`、`:228-232` 跨 `411→749dp` 像素坐标偏差 `≤ 2f`、`:244` 滚动值偏差 `≤ 1f` 及 `:704-706` 切字号/主题保活断言 100% 原样保留。

---

## 5. Roborazzi 快照逐张目视复验（62 张变更 + 非详情 0 漂移）

已使用 `read_file` 逐张目视检查 `/home/mir/Documents/hipda-next.wt/U2/hipda/src/test/snapshots/images/` 下全部 **62 张** 变更 PNG：

1. **多栏与折叠屏合并态（`multipane_*`，13 张）**：
   - `multipane_w749_h559_light.png`、`multipane_w749_h559_dark.png`、`multipane_w749_h559_drawer_light.png`、`multipane_hinge_flat_w749_h559_light.png`、`multipane_hinge_twopane_w749_h559_light.png`
   - `multipane_w933_h696_light.png`、`multipane_w933_h696_dark.png`、`multipane_w933_h696_balanced_split_light.png`、`multipane_w933_h696_sidebar_collapsed_light.png`、`multipane_w933_h696_fullscreen_light.png`
   - `multipane_w1280_h800_light.png`、`multipane_w1280_h800_dark.png`、`multipane_w411_h891_detail_pill_hidden_light.png`
   - **目视结论**：合并 `U1` 后，左栏（`64dp` 图标轨、`240dp` 抽屉浮层、`187/220dp` 常驻侧栏）、中栏 `ForumTopBar`、右栏 `ThreadDetailTopBar` 三栏顶栏高度严格 `56dp` 水平一线对齐，静止态均无顶栏底线；`w933_fullscreen` 右上角「显示列表」呈 `primary` 左侧实心侧栏图标，与展开态空心「全屏阅读」成对清晰；右栏底部左段「回复」轻输入槽舒展自然，中段弱灰 `1楼` 干净去蓝。
2. **底部三段工具条与内联跳页专项（`thread_chrome_*`，10 张）**：
   - `thread_chrome_compact_w340_scale125_light.png`、`thread_chrome_compact_rightpane_w360_scale125_dark.png`：左段自动降级为 `48×48dp` 纯笔形图标，中段 `111楼` 完整无截断，右段收藏/更多间距匀称，整体居中零溢出。
   - `thread_chrome_jumpbar_compact_w411_light.png`（双行跳页条）、`thread_chrome_jumpbar_wide_w749_dark.png`（单行跳页条）：原地展开形态、滑块/`首页`/`末页`/页码框完整无破坏。
   - `thread_chrome_toolbar_w411_light/dark.png`、`w749_light/dark.png`、`w1280_light/dark.png`：中段同步显示当前首可见楼层 `21楼`，液态玻璃折射透底自然，无蓝色进度条。
3. **帖子详情主体、字号缩放与状态页（`thread_detail_*`，15 张）**：
   - `thread_detail_pane411_filter_light.png`、`pane448_dark.png`、`pane680_light.png`、`w411_light/dark.png`、`w411_oled_pure_black.png`、`w411_step5_scale13_light.png`、`w559_light.png`、`w640_dark.png`、`w411/w933_empty_filter`、`w411/w933_error`、`w411/w933_skeleton`
   - **目视结论**：只看楼主态右上角激活为无底实心蓝色小人图标；滚动态顶栏渐显 `0.5dp` 细底线；`楼主` 为纯 `primary` 小字，`1楼`/`2楼`/`4楼` 为清晰 `onSurfaceVariant` 灰字；`step5_scale13` 最大字号下顶栏与底栏零挤压重叠；OLED 纯黑模式对比度清晰。
4. **楼层交互、快速回复、表情与编辑器背景联动（`thread_interact_*` 6 张、`quick_reply_*` 9 张、`emoji_panel_*` 4 张、`editor_*` 5 张，共 24 张）**：
   - 全部系底层或前景 `ThreadDetailPane` 顶栏/楼层样式/底栏预期变化所致；尤其 `quick_reply_threepane_w749_light.png` 与 `w933_dark.png` 直观验证了右栏顶栏侧栏图标与快速回复右上角对角展开箭头同屏互不撞车，且快速回复展开时底层三段工具条已完全卸载。
5. **非本卡快照 0 漂移核验**：
   - `glass_specimen_*`（12 张）、`forum_*`（24 张）、`shell_*`（19 张）、`theme_specimen_*`（12 张）、`thread_image_*`（6 张）、`image_viewer_*`（11 张）、全屏纯色 `editor_*`（其余 12 张）及 `messages_*`/`mine_*`/`search_*`/`settings_*`/`login_*`/`challenge_*` 全部 **0 改动**。

---

## 6. 阻断项与建议项

### 阻断项（Blocking）
- **无**。

### 非阻断建议项（Suggestions）
- **S1（清理未使用兼容形参）**：`ThreadDetailTopBar.kt:55` 为避免触碰本卡授权清单外的 `HiTopBarScrollToTopTest.kt:280` 保留了 `@Suppress("UNUSED_PARAMETER") authorName: String? = null`（符合 `U2.md` 范围第 1 条备选约定）；后续收尾整理时可一并移除该形参及 `ThreadDetailPane.kt:932`、`HiTopBarScrollToTopTest.kt:280` 的传参。
- **S2（跨十进位楼层边界左段宽度过渡细节）**：`ThreadBottomToolbar.kt:219,232-233` 中，收起态左段 `leftWidth` 直接赋值而中段 `animatedMiddleOffsetX`/`animatedMiddleWidth` 走 `HiMotion.exit(spatialDefault)` 弹簧；因 `tnum` 等宽数字保证了同位数楼层宽度恒定，仅在滚过 `9↔10`、`99↔100` 等位数变化边界的 ~60ms 内左段右缘瞬跳约 `8dp`、中段平滑跟进（`offset + width` 和守恒，零重叠、零文字裁切）。若后续真机追求极致连贯，可让收起态左段宽度同步使用同一弹簧或仅在 `effectiveJumpBarExpanded` 切换时启用动画。
