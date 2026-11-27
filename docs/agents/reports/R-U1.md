# R-U1 独立代码与视觉审查报告

- **审查结论**：`APPROVE`（0 阻断项，2 条非阻断建议）
- **被审卡片**：`/home/mir/Documents/hipda-next.wt/_cards/U1.md`（分支 `card/U1-shell-list-polish`）
- **基线 / 目标 Commit**：`1377a04` → `44810ce874f46f73090d98aa46337adad8fd4fb8`
- **CI 证据**：GitHub Actions Run [`46046699288`](https://github.com/mailwmj/hipda-next/actions/runs/46046699288)（Job [`140667638612`](https://github.com/mailwmj/hipda-next/actions/runs/46046699288/job/140667638612)，`headSha == 44810ce874f46f73090d98aa46337adad8fd4fb8`，`conclusion == success`）
- **只读纪律核验**：全程未阅读 `_reports/U1.md`，未在 `/home/mir/Documents/hipda-next` 或 `/home/mir/Documents/hipda-next.wt/U1` 写入、检出、提交或本地编译。

---

## 1. CI 与范围边界核查

1. **CI 全绿核验**：
   - `Build and lint`（`./gradlew assembleDebug assembleRelease lintDebug --stacktrace`）：`success`
   - `Verify Room schema consistency`：`success`
   - `Run unit tests and verify Roborazzi screenshots`（`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true --continue --stacktrace`）：`success`
   - `Verify APK outputs`（包名 `net.jejer.hipda.next`、Baseline Profile 与 101 枚表情资源防收缩校验）：`success`
2. **文件边界与代码体量核查（命中 `docs/agents/lesson.md:28`）**：
   - 生产代码变更共 8 个文件（`+253 / -114`，**净增 `+139` 行**），与卡片预估约 `130` 行吻合，远低于 `1.5×`（`195` 行）及 `200` 行上限：
     - `PaneDividerHandle.kt`：`+2 / -2`
     - `ForumPane.kt`：`+11 / -3`
     - `ForumTopBar.kt`：`+24 / -17`
     - `ForumViewModel.kt`：`+22 / -6`
     - `ThreadListRow.kt`：`+120 / -61`
     - `ThreadTimeFormatter.kt`（新增）：`+27 / -0`
     - `HiNavigationSidebar.kt`：`+45 / -23`
     - `strings_forum.xml`：`+2 / -2`
   - **禁改与共享文件零触碰**：`git diff 1377a04...44810ce` 验证 `HiGlassSurface.kt`、`HiGlassTokens*`、`ThreadDetail*.kt`、`PostFloorCard.kt`、`ThreadBottomToolbar.kt`、`QuickReplySheet.kt`、`FullEditor*.kt`、`docs/design/copy.md`、`MotionTokenScanTest.kt`、`CopyTerminologyScanTest.kt`、`gradle/*`、`*build.gradle*`、`AndroidManifest.xml`、`.github/*` 均为 **0 diff**。
   - **标准组件与生产纯净度**：全部采用 Compose 标准布局/动效原语（`Box`/`Row`/`Column`/`LazyColumn`/`InlineTextContent`/`animateFloatAsState`），0 手写 `Layout`/`Modifier.Node`/状态机，0 测试专用生产钩子或 `CompositionLocal`。
   - **安全白名单（命中 `docs/agents/lesson.md:33`）**：本卡未新增或改动任何外链/WebView/图片加载通道（`ThreadAuthorAvatar` 沿用既有 `currentHiImageLoader()`）。

---

## 2. 本卡重点逐项核查

### 2.1 左栏与论坛顶栏 `56dp` 地平线对齐（`R-U1` 重点 1）
- **左栏顶栏 `56dp` 统一与底栏 `48dp` 保底**（`HiNavigationSidebar.kt:145-146, 468-496, 624-662, 676-722, 1101-1157`）：
  - `SidebarFixedBarHeight = 56.dp` 仅作用于顶部固定区；底部固定区拆分为独立 `SidebarBottomFixedBarHeight = 48.dp`，展开态「设置」槽与 `64dp` 收起态底部头像槽均维持 `48.dp` 不变，确保 Fold8 `749×559dp` 首屏完整露出默认 3 个关注版块（实测连同下方 `+` 按钮亦完整露出）。
  - `64dp` 收起态顶部展开按钮槽位传入 `slotHeight = SidebarFixedBarHeight`（`56.dp`），`48×48dp` 按钮垂直居中。
  - `SidebarColumnBody` 顶部容器由 `Column` 改为 `Box` 并将 `0.5dp` 底部分隔线 `Modifier.align(Alignment.BottomCenter)` 叠放于底缘，消除了原先外置 `HorizontalDivider` 额外垫高 `1dp` 的偏差，使顶栏净高严格等于 `56.0dp`；静止态 `topBarDividerAlpha == 0f` 隐藏，滚动后以 `HiMotion.effectsDefault()` 淡入 `cs.outlineVariant.copy(alpha = 0.6f)`。
  - 展开态收起图标替换为纯 Compose `ImageVector` 侧栏面板矢量图标 `SidebarCollapseVectorIcon`（`HiNavigationSidebar.kt:156-165`），左栏右侧分栏竖线改为 `0.5.dp` `cs.outlineVariant.copy(alpha = 0.6f)`（`:278`），选中胶囊保持 `cs.secondaryContainer` + `HiShapeTokens.Medium`（`:1085-1086, :1149-1150`）。
  - 抽屉 `BackHandler` LIFO key（`:301-305`，`lesson.md:36`）、`240dp` 抽屉拖拽手势与焦点锁（`:315-423`）0 改动。
- **论坛顶栏静止无底线 / 滚动淡入**（`ForumTopBar.kt:57, 178-206`）：
  - `FORUM_TOP_BAR_TITLE_ROW_HEIGHT = 56.dp` 保持不变；`ForumTopBarScrollState`（`:87-129`，`lesson.md:37`）0 改动。
  - 新增私有子作用域 `ForumTopBarDivider`（`:203-206`），以 `Modifier.align(Alignment.BottomCenter)` 贴齐平移容器底缘（无论无标签行 `56dp` 还是单栏折叠后保留 `48dp` 吸顶标签行，底线位置均精确落于可见底缘且不额外撑高 `0.5dp`），通过 `animateFloatAsState(if (isScrolled()) 1f else 0f, HiMotion.effectsDefault())` + `graphicsLayer { this.alpha = alpha.value }` 控制显隐，且将 `isScrolled()` 状态读取隔离在叶子作用域内不触发上层重组。

### 2.2 中栏去噪、作者全宽、溢出回落、精简时间与分隔线（`R-U1` 重点 2）
- **文案与资源清理**（`strings_forum.xml:25-28`）：新增 `forum_time_yesterday`（`昨天`，逐字对齐 `docs/design/copy.md:36`）；全仓 `git grep forum_badge_pic` 确认为 0 引用后安全删除。
- **可注入时钟与精简绝对时间**（`ThreadTimeFormatter.kt:9-27`、`ForumViewModel.kt:44, 66, 75-87, 95, 109, 120, 125, 172, 221, 239, 255, 296-299`）：
  - `ForumViewModel` 保留 5 参 `@Inject constructor` 委托给带 `todayProvider: () -> LocalDate` 的 `internal constructor`（默认 `LocalDate::now`），不在 Composable 内调系统时钟；初始化、`bindSource`、`refreshInternal`、`executeForumRefresh`、`loadTodayNew` 均同步刷新 `referenceDate`。
  - `formatThreadTime` 支持单双位月日时与可选秒（`yyyy-M-d[ H:mm[:ss]]`），严格校验历法有效性（如拒绝 `2026-02-30`、`25:00` 并透传原文），按 `referenceDate` 输出今日 `HH:mm`（无时分回退原文）、昨日 `昨天`、当年 `MM-dd`、跨年 `yyyy-MM-dd`，非标准相对文案（`刚刚`/`5分钟前`/`昨天`/空串）原样透传。
- **列表项去噪与排版修复**（`ThreadListRow.kt:51-60, 99-190, 209-210`、`ForumPane.kt:149-171`）：
  - 已彻底移除 `withPic`（「图」）及独立彩色徽标 `Row`/`ThreadBadge`。
  - `isSticky` 以 `14.sp` `Placeholder` + `StickyPinVectorIcon`（`tint = cs.onSurfaceVariant`，`contentDescription = "置顶"`，`alternateText = " "`）内联于标题首部。
  - `isNew` 默认内联于标题末尾（`cs.primary` `labelSmall`），经 `onTextLayout` 检测 `result.hasVisualOverflow`：2 行截断溢出时自动锁定 `newBadgeOverflowed = true` 并将 `新` 回落至元信息行首，不手写截断算法，且同宽度下锁定不反向振荡。
  - `isPoll` 与 `typeName` 以 ` · ` 合入作者前（同名 `"投票"` 自动去重），移除原 `Spacer(Modifier.weight(1f))` 腰斩逻辑，改由 `authorMetaText` `Modifier.weight(1f)` 吃满除右侧计数/时间外的全部剩余宽度。
  - `< 300dp` 紧凑行仅显示 `reply.trim()`，标准宽保留 `reply/view`，计数与时间均开启 `fontFeatureSettings = "tnum"` 并以 `·` 分隔。
  - 选中态外包 `padding(horizontal = 8.dp, vertical = 2.dp)` + `clip(HiShapeTokens.Medium)` 胶囊底（`resolveThreadRowBackground` 签名与语义保持不变，未影响 `MessagesListScreen.kt:272` 调用方）；`ForumPane.kt:165-169` 对选中行自身下方及上一行下方隐藏 `HorizontalDivider` 并以 `Spacer(Modifier.height(0.5.dp))` 等高占位防跳动；普通分隔线按 `compactRow` 缩进 `12.dp` / `68.dp`（精确对齐 `8+4=12dp` 与 `8+8+40+12=68dp` 标题文字起点）。
- **三栏中右分隔线**（`PaneDividerHandle.kt:54`）：仅改 `PANE_DIVIDER_LINE_WIDTH = 0.5.dp`，`48dp` 热区、激活态 `2dp`、手势透传与键盘/无障碍动作 0 改动。

### 2.3 测试覆盖与反空心断言核查（`R-U1` 重点 3，命中 `docs/agents/lesson.md:40`）
- `HiAdaptiveShellTest`：新增 `sidebarTopBar_expandedAndCollapsed_alignAt56dpHorizon` 断言展开/收起两态顶栏均为 `56dp` 且按钮垂直居中；既有 `749×559` 守卫测试补充顶栏 `80dp`（含 `24dp` 状态栏）与 3 个版块 `bottom <= bottomAccountBounds.top` 断言。
- `ForumTopBarScrollTest`：新增 `forumTopBar_height56dp_andBottomDividerHiddenAtRestFadesInOnScroll`，用 `@GraphicsMode(NATIVE)` 位图逐像素校验静止态底缘色差 `== 0`、滚动过渡帧平滑渐显（`midDelta in 1 until settledDelta`）及终态 `settledDelta >= 10`。
- `ForumListBehaviorTest`：覆盖 `< 300dp` 仅回复数与 `>= 300dp` `reply/view` 切换、无「图」标、置顶内联图钉坐标、短标题末尾 `新` vs 2 行溢出 `hasVisualOverflow == true` 回落元信息行首、`274dp` 下 `long_author_1` 零截断（`hasVisualOverflow == false`）、投票同名分类去重，以及 `@GraphicsMode(NATIVE)` 位图逐像素核验选中胶囊 `8dp` 外边距留白、上下相邻分隔线隐藏与 `68dp`/`12dp` 普通分隔线缩进。
- `ThreadTimeFormatterTest` & `ForumViewModelTest`：完整覆盖 6 类日期/异常输入及 `todayProvider` 在初始化、`refresh()`、`bindSource()` 下的更新。
- 上述断言与对应实现分支一一绑定，任意改坏核心逻辑一行均会直接触发单测失败，无空心断言。

---

## 3. 视觉目视复验（73/73 张变更 PNG 全量核验，命中 `docs/agents/lesson.md:38`）

1. **既有快照零越界漂移**：仓库共 `263` 张 Roborazzi 快照，本次仅修改 `73` 张（`forum_*` 26 张、`multipane_*` 19 张、`shell_*`/`shell_scroll_*` 12 张，以及挂载了 `≥ 600dp` 侧栏/中栏的 `editor_*` 7 张、`messages_*` 5 张、`quick_reply_threepane_*` 2 张、`emoji_panel_*` 2 张），其余 `190` 张单栏/详情/个人页/设置/看图快照 **0 漂移**。
2. **逐张目视结论（73/73 全部通过）**：
   - **三栏 `56dp` 地平线对齐**：`forum_w749_h559_{light,dark}.png`、`forum_w933_h696_{light,dark}.png`、`forum_w1280_h800_{light,dark}.png`、`multipane_w749_h559_{light,dark,drawer_light}.png`、`multipane_w933_h696_{light,dark,balanced_split_light,sidebar_collapsed_light}.png`、`multipane_w1280_h800_{light,dark}.png`、`shell_w749_h559_{light,dark,drawer_light}.png`、`shell_w933_h696_{light,dark,collapsed_light}.png`、`shell_w1280_h800_{light,dark}.png`、`messages_sms_list_w1280_{light,dark}.png`、`editor_tablet_w1280_rightpane_{light,dark}.png` 中，左栏（含 `64dp` 图标轨、常驻展开态与 `240dp` 浮层态）与中/右栏顶栏高度严格一致；静止态左栏与论坛顶栏底缘干净无横线，滚动态（`forum_w411_h891_scrolled_collapsed_light.png`、`shell_scroll_w749_h559_sidebar_bottom_light.png`）`0.5dp` 细线清晰浮现。
   - **Fold8 `749×559` 首屏 3 版块**：`forum_w749_h559_{light,dark}.png`、`multipane_w749_h559_{light,dark}.png`、`shell_w749_h559_{light,dark,drawer_light}.png` 中，`Discovery`、`Buy & Sell`、`Geek Talks` 三个版块（及图标轨 `+` 按钮）全部完整位于底部 `48dp` 固定栏上方，无任何裁切。
   - **中栏去噪、作者全宽、选中胶囊与分隔线缩进**：彩色徽标块与「图」标已全部移除，版面克制清爽；置顶灰图钉与末尾蓝色 `新` 字比例协调、深浅色对比度清晰；`linus_cn`/`poll_host`/`shutter_bug`/`nas_builder`/`compose_dev` 等作者名在 `274dp`/`298dp` 窄中栏下完整显示无截断；选中胶囊圆角内缩自然且上下紧邻分隔线隐藏，普通行 `0.5dp` 分隔线在标准宽（`68dp`）与紧凑宽（`12dp`）下均精准对齐标题文字左缘。
   - **极端窄屏、横屏矮窗与大字号**：`forum_w340_h800_narrow_light.png`、`forum_w891_h411_landscape_light.png`、`forum_w411_h891_step5_font130_light.png`、`shell_w840_h900_step5_font130_light.png` 均无文字重叠、溢出或触控区挤压。

---

## 4. 阻断项与非阻断建议

### 阻断项（0 项）
无。

### 非阻断建议（2 项）
- **S1（连续拖拽分隔线时的单帧重测简化）**：`hipda/src/main/java/net/jejer/hipda/feature/forum/ThreadListRow.kt:99-100, 164-166` — 当 `widthPx != lastMeasuredWidthPx` 时，当前 `newBadgeOverflowed = if (newBadgeOverflowed) false else result.hasVisualOverflow` 会在 `newBadgeOverflowed == true`（此时测量文本已不含 `"  新"`）时无条件先重置为 `false` 重测一帧；若不带 `"  新"` 的裸标题本身在新宽度下依然 `result.hasVisualOverflow == true`，带 `"  新"` 必然也溢出。在连续多帧拖拽中右分隔线时，长标题新帖的 `新` 会在相邻帧短暂切换（停手后立即稳定收敛至元信息行首，不死循环、不丢状态）。后续可将该分支直接简化为 `newBadgeOverflowed = result.hasVisualOverflow`，并在 `:99-100` 的 `remember` keys 中补入 `LocalDensity.current` 以覆盖纯系统 `fontScale` 热切换场景。
- **S2（截图样例覆盖绝对时间分支）**：`hipda/src/test/java/net/jejer/hipda/feature/forum/ForumScreenshotTest.kt:575-620` — 截图样例的 `timeUpdate` 当前均为非空相对文案（`"刚刚"`/`"10分钟前"`/`"昨天"`/`"前天"`），实际走透传分支，精简绝对时间 `HH:mm`/`MM-dd`/`yyyy-MM-dd` 目前由 `ThreadTimeFormatterTest` 与 `ForumListBehaviorTest:886` 守住；后续顺带重录论坛截图时可将 1–2 条样例的 `timeUpdate` 设为 `yyyy-M-d HH:mm` 以在视觉基线中直观呈现 `tnum` 绝对时间排版。

---

## 5. `docs/agents/lesson.md` 命中核对

- `lesson.md:26`：只读审查不检出主目录，全程使用 `git diff` / `git show <sha>:<path>` / `gh run view`。
- `lesson.md:28`：核查界面卡净增行数（`+139` 行 ≤ `1.5×` 预估）、纯标准 Compose 组件（0 自定义 `Layout`/`Modifier.Node`）、0 测试专用生产钩子。
- `lesson.md:33`：核查站点内容打开/加载安全白名单（本卡无新增/修改外链或 WebView 加载）。
- `lesson.md:36-37`：核查模态侧栏 `BackHandler` LIFO key 与 `ForumTopBarScrollState` / `PaneDividerHandle` 同步拖拽状态未受破坏。
- `lesson.md:38`：独立审查员逐张 `read_file` 目视核验全部 `73/73` 张变更 Roborazzi PNG 基线。
- `lesson.md:40`：核查新增单测与像素断言非空心断言，改坏对应生产逻辑均会变红。
