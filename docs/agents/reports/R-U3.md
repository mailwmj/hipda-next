# 独立代码与视觉审查报告：R-U3

- **审查卡片**：`/home/mir/Documents/hipda-next.wt/_cards/R-U3.md`（被审卡：`/home/mir/Documents/hipda-next.wt/_cards/U3.md`）
- **审查分支 / Commit**：`card/U3-compose-polish` @ `fdd73ace1ae49db9e08d351698837a732e906dc8`（基线 `1377a04`）
- **CI 核验**：[GitHub Actions Run #46046715744](https://github.com/mailwmj/hipda-next/actions/runs/46046715744) — `headSha == fdd73ace1ae49db9e08d351698837a732e906dc8`，结论 `success`（`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿）。
- **审查结论**：**APPROVE**（0 阻断项，2 条非阻断建议）
- **只读纪律核验**：全程未读取 `_reports/U3.md`，未在 `/home/mir/Documents/hipda-next` 或 `/home/mir/Documents/hipda-next.wt/U3` 写入、检出、提交或本地编译代码。

---

## 1. 范围、行数与禁改边界核验

`git diff --numstat 1377a04...fdd73ace1ae49db9e08d351698837a732e906dc8` 共变更 38 个文件（`+410 / -77`）：

| 类别 | 文件 | 行数变动 | 核验结果 |
|---|---|---|---|
| 生产代码 | `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt` | `+72 / -20` | 合规（标准组件，无测试专用钩子） |
| 生产代码 | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt` | `+39 / -49` | 合规（净减 10 行，单行标题与模态精致化） |
| 生产代码 | `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorViewModel.kt` | `+1 / -0` | 合规（仅新增 `hasSendableContent` 派生属性） |
| 字符串资源 | `hipda/src/main/res/values/strings_compose.xml` | `+2 / -0` | 逐字对齐 `docs/design/copy.md:31,41-42` |
| 单测/集成测试 | `FullEditorIntegrationTest.kt` (`+126/-2`)、`FullEditorViewModelTest.kt` (`+48/-0`)、`QuickReplySheetTest.kt` (`+87/-2`)、`QuickReplyLayoutMatrixTest.kt` (`+20/-1`)、`QuickReplyTestSupport.kt` (`+11/-0`)、`EmojiPanelBehaviorTest.kt` (`+3/-2`)、`EditAndDeletePostIntegrationTest.kt` (`+1/-1`) | `+296 / -8` | 全部属于卡片显式授权或预期可见变更直接关联断言更新，0 放宽 |
| Roborazzi 快照 | `editor_*` (14) + `emoji_panel_*` (5) + `quick_reply_*` (8) | 27 张 PNG | 属于授权范围；非本卡既有快照 **0 漂移** |

- **禁改文件核验**：`HiGlassSurface.kt` / `HiGlassTokens`、`ThreadDetail*.kt`、`ThreadBottomToolbar.kt`、`HiNavigationSidebar.kt`、`Forum*.kt`、`docs/design/copy.md`、`gradle/libs.versions.toml`、`build.gradle.kts`、`MotionTokenScanTest` 白名单均为 **0 改动**。
- **代码体量与组件选型（`lesson.md:28`）**：生产代码总计 `+114 / -69`（净增 `+45` 行），远低于卡片预估（110–150 行）的 1.5× 上限；全部采用 `Box` / `Surface` / `Row` / `Column` / `AnimatedContent` / `Modifier.minimumInteractiveComponentSize()` 标准组件，无手写 `Layout` 或 `Modifier.Node`，生产代码 0 测试专用语义键或钩子。

---

## 2. 本卡核心逻辑逐项核验

### 2.1 `QuickReplySheet.kt`
1. **`ReplyThread` 去重与底栏展开按钮（`:108, :137-147, :404-410, :454-486, :650-656`）**：
   - `suppressContextRow = uiState.isLoggedIn && uiState.target.mode == QuickReplyMode.ReplyThread`（`:404`）传入 `resolveSheetSlotPlan`，使已登录 `ReplyThread` 下 `showFullCtx = false`、`showCtx = false`，释放顶部 `48dp` 纵向预算，同时自动激活已有 `expandInBottomBar = !useCompact && canExpandToFullEditor && !showFullCtx`（`:147`），将 `展开全屏` 置于底栏左侧紧跟 `插入图片` 与 `表情` 图标（`:651-653`）。
   - `inputPlaceholder = if (plan.showContext) stringResource(R.string.compose_reply_body_hint) else contextLabel`（`:410`）：当顶部上下文行隐藏时，输入框占位承载 `回复 {用户名}`（缺作者回退 `回复`），且 `:437-439` 的 `1dp` `LiveRegionMode.Polite` 节点继续播报回复对象；当 `QuotePost` / `ReplyPost` 显示单行顶部上下文（`:455`）时，输入框占位自动切为 `输入回复内容`，彻底消除上下重复（若极端矮屏压缩掉顶部上下文行，占位还会自动回退为 `contextLabel` 保住目标语义）。
   - 未登录态（`:123-128, :458`）保持显示顶部上下文行 + `登录后即可操作` 引导不变（`quick_reply_phone_w411_guest_light.png` 0 漂移）。
2. **输入框去粗糙描边（`:590-595`）**：
   - 移除 `border(0.75.dp, ...)`，改用 `HiShapeTokens.Medium` + `cs.surfaceContainerHigh.copy(alpha = 0.72f)` 柔和填充槽。
3. **发送按钮瘦身（`:670-746`）**：
   - `ComposeSendCapsuleButton` 外层 `Box` 挂 `.clickable(..., role = Role.Button).minimumInteractiveComponentSize()` 保证 `≥ 48dp` 触控区，内层 `Surface` 固定 `.height(32.dp).defaultMinSize(minWidth = 56.dp)`、`HiShapeTokens.Full`、`PaddingValues(horizontal = 14.dp, vertical = 0.dp)`；空态 `canSend == false` 禁用态、`16dp` 提交进度圈与 `HiRollingNumber` 冷却倒计时及无障碍描述完整保留。
4. **同步拖拽与手势状态机无回归（`:205-206, :248-368`，对应 `lesson.md:37`）**：
   - `dragOffsetYPx` 同步 state、首次停弹簧 `CoroutineStart.UNDISPATCHED`、`launchTerminalTransition` 与 `onDragSettled` 的 `try/finally` 终态回收逻辑 **0 行改动**；`32dp` `QuickReplyDragHandle`（下拉关闭 + 上拉展开全屏）与输入框顶部超界 `NestedScroll` 完全保留，`QuickReplySheetTest` 单批手势与弹簧中断测试全绿。

### 2.2 `FullEditorScreen.kt` + `FullEditorViewModel.kt`
1. **顶栏单行上下文标题与正文占位去重（`FullEditorScreen.kt:133-137, :251-287, :403-406, :475-497`）**：
   - 移除副标题参数与 `resolveEditorSubtitle`，`resolveEditorTitle` 单行输出：`NewThread` → `发新帖`；`ReplyThread`/`ReplyPost` → `回复 {用户名}`（缺作者回退 `回复`）；`QuotePost` → `引用第 {N} 楼 · {用户名}`（缺楼层回退 `回复 {用户名}`，再缺作者回退 `引用回复`）；`EditPost` → `编辑`。
   - `resolveBodyVisualPlaceholder` 在 `FullEditorMode.Reply` 下返回 `null`（视觉不渲染重复占位），`NewThread` 与 `EditPost` 返回 `正文`；同时 `bodyContentDescription = visualBodyPlaceholder ?: titleText` 赋给正文 `BasicTextField` 的 `semantics { contentDescription = ... }`（`:403`），无障碍与集成测试语义不退化。
2. **发送按钮视觉与可点性解耦（`FullEditorViewModel.kt:135-136`、`FullEditorScreen.kt:291-293`）**：
   - `FullEditorViewModel.kt` 仅在 `:136` 新增一行派生属性 `val hasSendableContent: Boolean get() = !body.isVisuallyEmpty() || uploads.any { it.imageIdOrNull != null }`（复用零分配 `EditorBodyDocument.isVisuallyEmpty()`，精确覆盖空文本、纯空白、有效文本、内联图片块与已上传未插入附件）；`canTapSend`（`:135`）与 `submit()` / `validateForSubmit()`（`:428-468, :764-789`）**0 行改动**，`FullEditorViewModelTest.kt:268-282` 原有四级内联校验测试 0 改动全绿。
   - `EditorSendButton` 委托 `ComposeSendCapsuleButton(..., enabled = canTapSend, filled = hasSendableContent)`：有内容时为 `32dp` 实心 `primary` 胶囊；空内容但 `canTapSend == true` 时为透明无底 `primary` 文字态且保持 `48dp` 可点击触控区，点击照常触发 `ContentTooShort` 等内联校验。
3. **居中模态左上 `×`（`关闭`）与 `0.5dp` 描边（`FullEditorScreen.kt:179-183, :258-267`）**：
   - `isModalCard == true` 时顶栏左钮切换为 `Icons.Rounded.Close` + `R.string.compose_action_close`（`关闭`），`isModalCard == false`（全屏与 `1280dp` 右栏内嵌态）保持 `ArrowBack`（`返回`）。
   - 模态 `Surface` 移除外层重复 `.clip(cardShape)` 并添加 `border = BorderStroke(0.5.dp, cs.outlineVariant.copy(alpha = 0.5f))`，不使用白色高光描边；底栏未加任何多余草稿/字数噪音。

### 2.3 既有测试改动核验
- `FullEditorIntegrationTest.kt`：`:160` 因 `QuotePost` 快速回复占位去重由 `"引用第 2 楼 · 楼层作者2"` 更新为 `"输入回复内容"`；`:376` 因 `749dp` 居中模态左上按钮由 `"返回"` 更新为 `"关闭"`（而 `:267` 的 `1280dp` 右栏内嵌态正确保留 `"返回"`）；`:409-524` 新增端到端几何、`captureToImage()` 像素级无底/实心切换与点击空态发送触发 `ContentTooShort` 测试。
- `EmojiPanelBehaviorTest.kt`：`:534` 跳页点击行未动；仅 `:661` 与 `:991` 将 `CenteredModal` 顶栏左钮定位由 `"返回"` 更新为 `"关闭"`（非模态仍取 `"返回"`），`1.0f` dp 底缘零位移容差与正文防塌陷断言完全未放宽。
- `EditAndDeletePostIntegrationTest.kt`：仅 `:537` 在 `749dp` 紧凑三栏居中模态编辑场景将 `"返回"` 更新为 `"关闭"`，模态左缘 `16f..90f` 与玻璃数 `≤ 3` 断言完全未放宽。

---

## 3. `docs/agents/lesson.md` 命中核对

- **`lesson.md:19`**（Roborazzi `--tests` 精确录制与不写 `local.properties`）：仓库干净，仅本卡波及的 27 张 PNG 更新，CI `-Proborazzi.test.verify=true` 一次通过。
- **`lesson.md:26`**（只读审查员严禁主目录 `git checkout`）：全程只读检查，主目录与 U3 worktree 状态零变更。
- **`lesson.md:28`**（控制生产代码体量、标准组件优先、0 测试专用生产钩子）：生产净增仅 `+45` 行，0 自定义 `Layout`/`Modifier.Node`，0 生产侧测试钩子。
- **`lesson.md:36`**（`BackHandler` 以会话为 `key` 按打开态挂载防抢占）：`FullEditorScreen.kt:121` 与 `FullEditorHost.kt:238` 保持完好，LIFO 返回栈单测通过。
- **`lesson.md:37`**（拖拽用同步 state + `CoroutineStart.UNDISPATCHED` + `try/finally`）：`QuickReplySheet.kt:205-206, :248-336` 零改动保留。
- **`lesson.md:38`**（独立审查员逐张目视核验全部变更截图）：27 张变更 PNG 已逐张调用 `read_file` 目视复验（见第 4 节），正文全部正常渲染无空白基线。
- **`lesson.md:40`**（防改坏实现仍绿的空心断言）：新增单测与 UI 测试通过文本计数、节点坐标、`32dp`/`48dp` 尺寸及 `captureToImage()` 像素比对直接锁死核心逻辑，任一核心分支改坏均会变红。

---

## 4. 27 张 Roborazzi PNG 逐张目视复验记录

已逐张 `read_file` 目视检查 `/home/mir/Documents/hipda-next.wt/U3/hipda/src/test/snapshots/images/` 下全部 27 张变更截图（并抽检未改动的 `quick_reply_phone_w411_guest_light.png`、`editor_edit_delete_dialog_w411_light.png`、`editor_edit_delete_dialog_w749_dark.png` 确认无需变更）：

1. **快速回复（8 张 `quick_reply_*`）**：
   - `quick_reply_phone_w411_light.png` / `_dark.png`：已登录 `ReplyThread` 顶部无重复上下文行，`32dp` 把手居中，无边框柔和输入槽在浅/深色下对比清晰，底栏左 `[插入图片][表情][展开全屏]`、右 `32dp` 实心 `发送` 胶囊比例克制协调。
   - `quick_reply_phone_w411_quote_draft_light.png`：`QuotePost` 顶部保留单行 `引用第 2 楼 · geek_reader_2` + 右侧 `展开全屏`，下方 `已恢复上次草稿` 横幅与无边框输入框层次分明，底栏不重复渲染 `展开全屏`。
   - `quick_reply_phone_w411_cooldown_dark.png`：冷却数字 `28` 居中于 `32dp` 胶囊内，输入框文字清晰。
   - `quick_reply_landscape_891x411_ime_compact_light.png` / `_dark.png`：横屏矮窗单行紧凑态下无边框输入框、三枚工具图标与 `32dp` 发送胶囊单行完整排布，无裁切或重叠。
   - `quick_reply_threepane_w749_light.png` / `quick_reply_threepane_w933_dark.png`：严格限定在三栏右栏底部升起，暗罩仅盖右栏，草稿条、输入框与 `32dp` 发送胶囊无挤压。
2. **表情面板联动（5 张 `emoji_panel_*`）**：
   - `emoji_panel_quick_reply_w411_light.png`、`emoji_panel_short_landscape_891x411_light.png`、`emoji_panel_threepane_w749_light.png`、`emoji_panel_tablet_w1280_dark.png`、`emoji_panel_full_editor_w411_dark.png`：键盘切换图标、`展开全屏` 底栏位、`32dp` 发送胶囊与底部多列表情网格衔接严密，完整编辑器顶栏单行 `引用第 2 楼 · geek_reader` 清晰无副标题重复。
3. **完整编辑器（14 张 `editor_*`）**：
   - `editor_phone_w411_reply_light.png` / `_dark.png`、`editor_phone_w411_cooldown_error_dark.png`：单行上下文标题（`引用第 2 楼 · geek_reader` / `回复 linus_hi`），左上 `←`，右上 `32dp` 发送/冷却胶囊（`25`），正文内容完整清晰，底栏干净无多余信息。
   - `editor_phone_w411_new_thread_light.png` / `_dark.png`、`_draft_validation_light.png`、`_upload_inline_light.png`：单行 `发新帖` 标题、版块/分类下拉、标题校验红框（`标题至少 6 个字`）、多图上传条（`正在上传 4/5`、`未插入`）及正文内联缩略图块排布正常。
   - `editor_twopane_w640_modal_light.png`、`editor_threepane_w749_modal_light.png`、`editor_threepane_w933_modal_dark.png`：居中模态左上角均为 `×`，`20dp` 圆角卡片四周 `0.5dp` `outlineVariant @ 0.5f` 细描边在浅色与深色暗罩上边界清晰克制、无亮白高光。
   - `editor_tablet_w1280_rightpane_light.png` / `_dark.png`：`1280dp` 右栏内嵌态左上角保持 `←`（`返回`），与单行标题和 `32dp` 发送胶囊对齐一致。
   - `editor_edit_phone_w411_restore_light.png`、`editor_edit_tablet_w1280_rightpane_dark.png`：编辑模式顶栏单行显示 `编辑`（已去除冗余楼层副标题），右侧 `还原原文` 按钮与 `32dp` 发送胶囊间距得当、无拥挤截断。

---

## 5. 阻断项与非阻断建议

### 阻断项
- **无**（0 项）。

### 非阻断建议
1. **S1（测试可维护性）**：`hipda/src/test/java/net/jejer/hipda/feature/compose/QuickReplyTestSupport.kt:340-348` 中 `firstChildLayoutHeightPx()` 通过反射查找 `LayoutInfo` 的内部 `getChildren` 方法读取 `32dp` 胶囊子层高度。因内层非可点 `Surface` 自带独立未合并语义节点（`isTraversalGroup = true`），后续可在测试中改用公开 API `onNodeWithContentDescription("发送", useUnmergedTree = true).fetchSemanticsNode().children` 读取子 `Surface` 尺寸，避免未来升级 Compose UI 时内部方法改名导致测试编译/反射报错。
2. **S2（测试表达清晰度）**：`hipda/src/test/java/net/jejer/hipda/feature/compose/FullEditorIntegrationTest.kt:425-426` 使用 `Color.hashCode()` 而非 `Color.toArgb()` 记录主题色对照值（虽然 `:483` 已通过 `captureToImage()` 真实像素断言 `emptyPillPixel != filledPillPixel` 守住了无底态→实心态切换），后续顺手改为 `toArgb()` 直接比对顶栏底色与 `primary` 像素值会更直观。

---

## 6. Rebase 后联合态（`U1 + U2 + U3`）只读补审记录

- **补审基线 / 目标 Commit**：`47dc302`（含 `U1 + U2` 的 `origin/main`）→ `71713807d1b5d127f306f96bdac610ce95a0a830`
- **CI 核验**：[GitHub Actions Run #46057585997](https://github.com/mailwmj/hipda-next/actions/runs/46057585997) — `headSha == 71713807d1b5d127f306f96bdac610ce95a0a830`，`status == completed`，`conclusion == success`。
- **补审结论**：**APPROVE**（0 阻断项）

### 6.1 Rebase 范围与零漂移核验
1. `git diff --name-status 47dc302...71713807d1b5d127f306f96bdac610ce95a0a830` 变更集合仍严格为上述 **11 个源码/资源/测试文件 + 27 张授权 Roborazzi PNG**（共 38 个文件），仓库其余全部既有快照 **0 漂移**。
2. `diff -u <(git diff 1377a04...fdd73ace1ae49db9e08d351698837a732e906dc8 -- ':(exclude)*.png') <(git diff 47dc302...71713807d1b5d127f306f96bdac610ce95a0a830 -- ':(exclude)*.png')` 确认：除 2 个测试文件 git blob 索引号随基线推进外，`hipda/src/main/` 与 `hipda/src/test/` 全部非 PNG 改动与首轮 `APPROVE` 的 `fdd73ac` 逐行完全一致。

### 6.2 `U1 + U2 + U3` 联合态 18 张重录截图逐张目视复验
已通过 `read_file` 逐张只读复核 `/home/mir/Documents/hipda-next.wt/U3/hipda/src/test/snapshots/images/` 下联合态重录的全部 18 张 PNG：
1. **单栏与横屏快速回复 / 表情叠放（8 张）**：`quick_reply_phone_w411_{light,dark,cooldown_dark,quote_draft_light}.png`、`quick_reply_landscape_891x411_ime_compact_{light,dark}.png`、`emoji_panel_quick_reply_w411_light.png`、`emoji_panel_short_landscape_891x411_light.png`
   - 底层详情页已更新为 `U1+U2` 的 `56dp` 单行无副标题顶栏、纯 `48dp` 无底图标、纯色文字 `楼主` 与弱灰楼层号；`QuickReplySheet` 升起时底层三段工具条已完全卸载无残留，无边框柔和输入槽、底栏左 `[插入图片][表情][展开全屏]` 与右 `32dp` 发送/冷却胶囊排布自然、零裁切重叠。
2. **三栏快速回复与表情面板叠放（4 张）**：`quick_reply_threepane_{w749_light,w933_dark}.png`、`emoji_panel_threepane_w749_light.png`、`emoji_panel_tablet_w1280_dark.png`
   - 左/中/右三栏 `56dp` 顶栏严格水平对齐，`0.5dp` 竖分隔线、中栏内缩圆角选中行与精简元信息行（`U2`）清晰；右栏顶栏 `全屏阅读` 侧栏面板隐喻图标与快速回复底栏 `展开全屏` 对角箭头语义区分明确、互不撞车；快速回复与表情面板严格收敛于右栏宽度内，暗罩仅盖右栏，底层工具条零残留。
3. **双栏 / 三栏居中模态与 `1280dp` 右栏内嵌完整编辑器（6 张）**：`editor_twopane_w640_modal_light.png`、`editor_threepane_{w749_modal_light,w933_modal_dark}.png`、`editor_tablet_w1280_rightpane_{light,dark}.png`、`editor_edit_tablet_w1280_rightpane_dark.png`
   - `w640`/`w749`/`w933` 居中模态卡片左上 `×`、单行标题、`32dp` 发送胶囊与四周 `0.5dp` `outlineVariant @ 0.5f` 描边在 `U1+U2` 底图暗罩之上层次干净利落（暗罩下透出的右栏底栏亦已同步为 `U2` 新版左伸缩回复槽+弱灰楼层胶囊，无旧版蓝色进度填充工具条残留）；
   - `w1280` 平板右栏内嵌态顶栏与左栏、中栏 `56dp` 顶栏完美齐平，左上保持 `←`，编辑态 `还原原文` + `32dp` 发送胶囊无挤压截断。
