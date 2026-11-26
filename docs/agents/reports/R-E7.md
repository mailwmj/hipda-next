# 独立代码审查报告 R-E7：表情选择面板与键盘等高原地互换（含 M7-0 #11 与 R-M7-G4 S2）

- **审查结论**：**`CHANGES-REQUESTED`**（检出 **2 项阻断项 `B1`、`B2`**，3 项非阻断建议 `S1`–`S3`）
- **审查对象**：`origin/card/E7-emoji-panel` @ `0428dd4fc60de1fde4e86d74ed1e98dbc7154e45`（`0428dd4`，父提交为已合入 `E6` 的 `main@2739d4d`）
- **CI 核验**：[Android CI #45812485275](https://github.com/mailwmj/hipda-next/actions/runs/45812485275)
  - `headSha == 0428dd4fc60de1fde4e86d74ed1e98dbc7154e45`（精确匹配），`status == completed`，`conclusion == success`（Job `139883620023` `Build, Lint & Unit Tests`，耗时 14m07s）；
  - 已核实全绿通过：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`，`Check for Roborazzi diff images` 因 0 差异自动 `skipped`）、`Verify APK outputs`。
- **生产代码规模**：10 个生产文件（新增 6 个 + 修改 4 个）`+904 / -159`，净增 **+745 行**（低于卡片 1.5× 警戒线 `975` 行；扣除负责人裁定 #2/#3 短消息新增的 `+146` 行后，E7 核心净增 `+599` 行，落在预估 `450–650` 行区间内）；0 手写 `Layout` / `Modifier.Node`，0 测试专用语义键/钩子/`CompositionLocal`，表情面板纯色表面 0 `HiGlassMarker`。
- **只读纪律核验**：全程在 `/home/mir/Documents/hipda-next` 仅执行只读 `git` / `gh` 查询，未执行 checkout、写文件、提交、推送或编译，`git status --short` 保持干净。

---

## 一、阻断项（Blocking Issues）

### B1：完整编辑器在 `CenteredModal` 投放形态（`isModalCard == true`：覆盖 Fold8 `749×559dp`、`933×696dp`、`640×1024dp` 及铰链双栏）下 `IME ↔ 表情` 严重跳变/塌陷（`|Δbottom|` 达 `122–260dp`），且 `navBarBottomPx = 0` 将含导航栏的原始 IME 高污染进全局 `SessionKeyboardHeightStore`

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt:217`（`FullEditorPlacement.CenteredModal` 的外层 `Surface` 保留了 `.imePadding()`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt:82, 91`（`isModalCard == true` 时强制传 `navBarBottomPx = 0` 与 `consumeImeInSlot = false`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelHost.kt:53-55, 142`（`!consumeImeInSlot` 分支在 `IME → 表情` 首帧无视尚未退场的外部 `.imePadding()` 直接叠加 `panelContentHeightDp`；在 `表情 → 键盘` 首帧命中 `if (!consumeImeInSlot ...) resetToClosed()` 立即将占位归零）
- **违反条款**：任务卡范围 §2 与验收标准 §1（`FullEditor` 路径 `IME → 表情` 与 `表情 → IME` 切换期间工具条底缘全过程 `|Δbottom| == 0dp`，严禁先塌陷到底再弹起；记忆高度须为扣除导航栏底部 inset 后的净键盘高）、`R-E7` 重点 1。
- **具体触发路径（100% 折叠屏内屏与中等平板复现）**：
  - 按 `FullEditorHost.kt:37-43`，右栏宽 `< 560dp` 的全部三栏/双栏窗口（含主力机型 Galaxy Z Fold8 自然横展 `749×559dp`、`933×696dp`、Pixel 9/10 Pro Fold `852×883dp`、`640×1024dp` 图标轨单栏及铰链双栏）打开完整编辑器均走 `FullEditorPlacement.CenteredModal`（`isModalCard = true`）；现有单测 `EmojiPanelBehaviorTest.fullEditor_frameByFrameImeEmojiSwap_zeroToolbarBottomShift`（`:186-232`）仅测了 `411×891dp` 默认 `isModalCard = false`，漏测了 `CenteredModal`。
  - **路径 ①（`IME → 表情` 双重计高挤压正文并上跳下移）**：在 Fold8 `749×559dp`（`statusBar = 24dp, navBar = 24dp, IME = 260dp`）打开完整编辑器，`Surface` 外层 `.systemBarsPadding().imePadding().padding(vertical = 24.dp)` 使卡片高仅 `227dp`、底部工具条底缘位于 `y = 275dp`。点击 `表情` 首帧（`ime` 仍为 `260dp`）及全部退场中间帧（`200/140/80/20dp`），外层 `Surface` 仍被 `.imePadding()` 顶起 `260dp`，而内层 `EmojiPanelSlotBox`（`:53-55`）又立即索取 `260dp` 高度——**双重叠加导致编辑器 `weight(1f)` 正文区瞬间被压成 `0dp`，底部工具条首帧向上猛跳 `122.5dp` 至 `y = 152.5dp`，随后随键盘退场一路下滑至 `y = 251dp`**。
  - **路径 ②（`表情 → 键盘` 首帧原地塌陷到底再弹起）**：在 `CenteredModal` 展开表情面板后点 `键盘`（或点击正文输入框），`EmojiPanelController.switchToKeyboard()`（`EmojiPanelHost.kt:142`）因 `!consumeImeInSlot` 为 `true` 直接执行 `resetToClosed()`，在 `ime == 0dp` 的首帧把 `EmojiPanelSlotBox` 从 `260dp` 瞬时清零为 `0dp`，底部工具条**首帧向下塌陷 `260dp` 直落模态卡底（`y = 511dp`）**，待系统软键盘逐帧升起才重新被推回，触发卡面明令禁止的「先塌陷到底再弹起」。
  - **路径 ③（`navBarBottomPx = 0` 跨组件污染会话净键盘高度缓存）**：`FullEditorScreen.kt:82` 在 `isModalCard == true` 时向 `rememberEmojiPanelController` 传入 `navBarBottomPx = 0`，但 `imeBottomPx` 仍是含系统导航栏的窗口总高（如 `260dp = 236dp 净键盘 + 24dp 导航栏`），导致 `SessionKeyboardHeightStore[rootView][559]` 被写入未扣导航栏的 `260dp`；随后用户关闭完整编辑器回到同一 `749×559dp` 窗口的 `QuickReplySheet`（或 `SmsConversationScreen`）点 `表情` 时，`resolveEmojiPanelTotalHeight(260dp, 24dp, 559dp)` 算出 `284dp != 260dp`，连带破坏快速回复面板的等高零位移（跳变 `+24dp`）。
- **最小修复建议**：
  1. **修正净键盘高采样（消除缓存污染）**：`FullEditorScreen.kt:82` 始终读取真实 `val systemNavBarBottomPx = WindowInsets.navigationBars.getBottom(density)` 传给 `KeyboardHeightTracker`（仅模态卡自身底部安全区内边距传 `modalInnerNavDp = 0.dp`）。
  2. **统一 `CenteredModal` 外框与内槽的总底缘恒等式 `outerBottom(t) + innerSlot(t) == targetBottom`**：
     - 在 `FullEditorPlacementFrame`（`FullEditorHost.kt:217`）令 `CenteredModal` 外层底部避让为 `outerBottomDp = maxOf(systemNavBarDp + 24.dp, liveImeBottomDp)`（键盘弹起时不额外叠加多余的 `24dp` 悬空边距），并令上层编辑区高度在切换期间锚定为 `min(720.dp, windowH - statusBarDp - 24.dp - maxOf(systemNavBarDp + 24.dp, emojiController.bottomSlotHeightDp))`；
     - 在 `FullEditorScreen`（`isModalCard == true`）去掉 `consumeImeInSlot = false` 短路特判，`EmojiPanelController` 正常经历 `Closed / Emoji / SwitchingToIme` 锁定 `bottomSlotHeightDp`（即 `targetBottomDp`），模态卡内部 `EmojiPanelSlotBox` 高度取 `(emojiController.bottomSlotHeightDp - outerBottomDp).coerceAtLeast(0.dp)`（内部 `panelInnerNavBarPaddingDp = 0.dp`）。
     - 由此在任意窗口高度（含 `749×559dp` 与 `640×1024dp`）和任意中间帧 `ime(t)` 下，工具条底缘恒定在 `windowH - max(navBar + 24.dp, bottomSlotHeightDp)`，全程严格 **`|Δbottom| == 0.0dp`**。
  3. 在 `EmojiPanelBehaviorTest` 新增真实 `NextAppRoot`（或 `FullEditorPlacementFrame`）在 `749×559dp` 与 `640×1024dp` `CenteredModal` 下的逐帧 `IME ↔ 表情` 零位移测试，并验证从 `CenteredModal` 关闭后打开 `QuickReplySheet` 净高记忆无 `+navBar` 漂移。

---

### B2：表情面板右上角「删除」键在三处宿主均仅删单个字形簇（1 个 ASCII 字符），未整枚回删论坛表情代码，留下残缺代码（如 `{:2_41:`、`:loveliness`）

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:156-163, 497-503`（`previousGraphemeOffset` 与快速回复 `onBackspace`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/EditorContentModel.kt:109-119`（`EditorBodyDocument.backspaceAtCursor`，由 `FullEditorViewModel.onBackspace` `:272` 调用）
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt:231-246`（短消息对话页 `onBackspace`）
  - `hipda/src/test/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelBehaviorTest.kt:315-317, 340`（测试将单次回删 `{:2_41:}` 断言为了残缺的 `"头:){:2_41:尾"` 并直接发出）
- **违反条款**：负责人补充审查项 **(3)**（「插入按论坛代码、在光标/选区处、**回删整表情**」）与任务卡范围 §4（光标无损插入、删除）。
- **具体触发路径**：
  - 旧版 `EmojiEditText.backspace()`（`EmojiEditText.java:56-59` + `EmojiHandler.java:105`）之所以能一键回删整枚论坛表情，是因为输入框内挂了 `EmojiSpan extends DynamicDrawableSpan`（`ReplacementSpan`），系统 `BaseKeyListener` 对紧邻光标左侧的 `ReplacementSpan` 会整段删除 `[spanStart, spanEnd)`。
  - 新版按裁定 #1 在输入框内保留纯文本论坛代码（不挂图片 Span），而 `:emoji` 全部 61 个论坛表情代码均为 `2–12` 个 ASCII 字符（如 `:)`、`:loveliness:`、`{:2_41:}`、`{:3_57:}`）。当前三处宿主的 `onBackspace` 直接调用 `BreakIterator.getCharacterInstance()` 的 `previousGraphemeOffset`，导致用户在表情面板刚点完 `酷猴 {:2_41:}`（8 字符）或 `:loveliness:`（12 字符）想撤回重选、点一下面板右上角 `删除` 时，只删掉末尾 `}` 或 `:`，输入框残留损坏的半截代码 **`{:2_41:`** / **`:loveliness`**（需连点 8–12 次才能删掉刚插入的 1 个表情）。
- **最小修复建议**：
  1. 在 `feature/compose/emoji/`（或 `QuickReplySheet.kt` 旁）新增约 12 行的共享纯函数 `previousForumEmojiOrGraphemeOffset(text: CharSequence, cursor: Int): Int`：
     - 预缓存 `EmojiMap.groups.flatMap { it.codes }.sortedByDescending { it.length }`（共 61 个规范代码，最长 12 字符）；
     - 无选区且 `safeCursor > 0` 时，检查光标前子串是否以任一规范论坛表情代码结尾（只需截取 `max(0, safeCursor - 12)..safeCursor` 匹配后缀）；命中则返回 `safeCursor - code.length` 一次删去整枚表情代码，未命中时回退至 `previousGraphemeOffset(text, safeCursor)`（继续保持补充平面 Unicode Emoji / 汉字代理对安全回删）。
  2. 在 `QuickReplySheet`、`EditorBodyDocument.backspaceAtCursor` 与 `SmsConversationScreen` 三处 `onBackspace` 统一调用该函数，并更新 `EmojiPanelBehaviorTest` 覆盖默认（`:)` / `:loveliness:`）、酷猴（`{:2_41:}`）、呆呆（`{:3_57:}`）及普通中文/代理对混合回删断言。

---

## 二、卡面重点与通用门禁逐项核验

| 检查维度 | 结果 | 核验依据（`file:line`） |
|---|---|---|
| **重点 1：等高原地互换与 §7.4 回退限高** | **单栏/右栏/SMS 通过；`FullEditor` 居中模态见 B1** | `EmojiPanelHeightPolicy.kt:25-30` 纯函数精确实现 `windowHeight ≥ 480dp → min(280dp, H×0.45)`、`< 480dp → min(280dp, H×0.35)`（`891→280`、`600→270`、`480→216`、`479→167.65`、`411→143.85dp` 全断点精确吻合）。`QuickReplySheet`、全屏/右栏 `FullEditorScreen` 与 `SmsConversationScreen` 去掉外层 `.imePadding()` 统一由 `EmojiPanelSlotBox` 承载底部高度，单栏逐帧测试验证 `|Δbottom| == 0dp`；按钮通过 `HiIconSwitch` 切 `表情` ↔ `键盘`（`48×48dp` 触控区，文案对齐 `copy.md:40,42`）。`CenteredModal` 形态缺陷见 **B1**。 |
| **重点 2：`:emoji` C1b 复用与闭合 `C1b#3`** | **通过** | `:emoji/*` 严格 **0 改动**。`EmojiPickerPanel.kt:77-105, 137-139, 158` 直接消费 `EmojiMap.groups`（`21 + 16 + 24 = 61`）与 `EmojiMap.drawableFor(code, dark = HiTheme.state.darkTheme)`，深浅色切换时酷猴/呆呆立即切换亮/暗资源且 `rememberSaveable` 保持当前分类；`calculateEmojiGridColumns` 保证任意宽度每格 `≥ 48×48dp`；分类标签取 `group.iconCode` 首图，指示器用 `animateDpAsState(targetX, HiMotion.spatialFast())` + `graphicsLayer`（减动效 1 帧 0ms 到位）；`strings_emoji.xml:1-11` + `resolveEmojiGroupLabelRes`（`:67-72`）彻底消除 `EmojiGroup.name` 硬编码，闭合 **`C1b#3`**，并通过 `CopyTerminologyScanTest` 与 `MotionTokenScanTest`（白名单 0 改动）。 |
| **重点 3：三宿主集成、插入/回删与 `BackHandler` 优先级** | **DI/插入/返回通过；回删见 B2** | `di/ComposeEmojiModule.kt:14-18` `@Binds @Singleton` 绑定 `EmojiPanelRendererImpl -> QuickReplyEmojiPanelRenderer`，精确对齐合入后的 `ComposeContracts.kt:94-121`；`MessagesPaneRendererImpl.kt:34, 81-87` 注入 `Optional<QuickReplyEmojiPanelRenderer>` 透传短消息页。三处宿主均支持中间光标插入与选区替换并保持焦点/草稿（整表情回删见 **B2**）。`EmojiPanelSlotBox`（`EmojiPanelHost.kt:187-189`）仅在 `isBackInterceptActive` 时以 `key(backHandlerKey)` 重挂 `BackHandler`（`FullEditorHost` 托管时通过 `handleBack` 优先拦截），实测满足 `表情面板 > QuickReply / FullEditor / SmsConversation > PositionStack`。 |
| **重点 4：文件边界、玻璃预算与安全白名单** | **通过** | `git diff --name-status 2739d4d...0428dd4` 核实：`feature/routing/*`、`NextAppRoot.kt`、`PostBodyRenderer.kt`、`:emoji/*`、`data/*`、`src/debug/*`、构建脚本、`AndroidManifest.xml`、`.github/` 全部 **0 改动**。`MultiPaneGlassBudgetTest.kt:573-630` 实测 `749×559dp` 三栏右栏展开快速回复 + 表情面板时严格限于右栏（`leftDp ≥ 338dp`），守住 `HiGlassMarker == 3 (≤ 3)`、`LayerBackdrop == 2 (≤ 2)`、0 嵌套、`drawBackdrop ≤ 5`。未引入任何新外部 URL 打开通道，短消息链接与剪贴板继续严守 `SafeLinkPolicy` 与 `ClipData.newPlainText`。 |

---

## 三、负责人 5 项补充核查逐项详答

### (1) 24 张更新截图逐张像素差分与目视核验 + 11 张新增截图逐张目视

- **范围与零漂移核验**：`git diff --name-status 2739d4d...0428dd4 -- 'hipda/src/test/snapshots/images/*'` 共变更 **35 张 PNG**（修改 24 张 = `8 quick_reply_*` + `12 editor_*` + `4 messages_*`；新增 11 张 `emoji_panel_*`），与 `_reports/E7.md` 第 5 节逐一吻合；未授权快照（`quick_reply_phone_w411_guest_light.png`、`editor_phone_w411_draft_saved_snackbar_light.png`、其余 8 张 `messages_*` 及全仓 `thread_*`/`forum_*`/`multipane_*`/`settings_*`/`user_*`）**严格 0 漂移**。
- **24 张授权更新 PNG 逐像素差分框（RGB+Alpha 全通道）与 `read_file` 逐张目视结果（24/24 确认仅因表情按钮出现而变化）**：

| # | 快照文件 | 尺寸 (px) | 差异像素与外接框 | 逐张目视结论 |
|---:|---|---|---|---|
| 1 | `quick_reply_phone_w411_light.png` | `822×1782` | `725 px`，`(141,1699)–(178,1736)` | 仅底栏 `插入图片` 右侧新增 `表情` 笑脸图标字形，其余 0 像素变化。 |
| 2 | `quick_reply_phone_w411_dark.png` | `822×1782` | `725 px`，`(141,1699)–(178,1736)` | 同上（深色 `onSurface` 着色清晰，毛玻璃底板与正文 0 变化）。 |
| 3 | `quick_reply_phone_w411_cooldown_dark.png` | `822×1782` | `725 px`，`(141,1699)–(178,1736)` | 同上，右侧 `28` 秒冷却胶囊 0 变化。 |
| 4 | `quick_reply_phone_w411_quote_draft_light.png` | `822×1782` | `725 px`，`(141,1699)–(178,1736)` | 同上，引用提示与 `已恢复上次草稿` 横条 0 变化。 |
| 5 | `quick_reply_threepane_w749_light.png` | `1498×1118` | `725 px`，`(817,1035)–(854,1072)` | 仅右栏快速回复底栏新增 `表情` 图标，左轨与中栏 0 变化。 |
| 6 | `quick_reply_threepane_w933_dark.png` | `1866×1392` | `725 px`，`(1111,1309)–(1148,1346)` | 仅右栏快速回复底栏新增 `表情` 图标，三栏深色布局 0 变化。 |
| 7 | `quick_reply_landscape_891x411_ime_compact_light.png` | `1782×822` | `8733 px`，`(1302,242)–(1488,321)` | 严格限于矮屏紧凑单行右侧 `[插入图片][表情][展开全屏]` 区（输入框右缘左收 `48dp` 让位给新增 `表情` 按钮，文字未截断），其余区域 0 变化。 |
| 8 | `quick_reply_landscape_891x411_ime_compact_dark.png` | `1782×822` | `8737 px`，`(1302,242)–(1488,321)` | 同上（深色紧凑单行清晰无重叠），其余区域 0 变化。 |
| 9 | `editor_phone_w411_cooldown_error_dark.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏 `插入图片` 右侧新增 `表情` 图标，冷却 `25` 与错误行 0 变化。 |
| 10 | `editor_phone_w411_draft_validation_light.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，草稿横条与红字校验 0 变化。 |
| 11 | `editor_phone_w411_new_thread_dark.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，深色发新帖表单与正文 0 变化。 |
| 12 | `editor_phone_w411_new_thread_light.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，浅色发新帖表单与正文 0 变化。 |
| 13 | `editor_phone_w411_reply_dark.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，深色引用回复视图 0 变化。 |
| 14 | `editor_phone_w411_reply_light.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，浅色引用回复视图 0 变化。 |
| 15 | `editor_phone_w411_upload_inline_light.png` | `822×1782` | `725 px`，`(141,1707)–(178,1744)` | 仅底栏新增 `表情` 图标，多图上传条与内联大图块 0 变化。 |
| 16 | `editor_tablet_w1280_rightpane_dark.png` | `2560×1600` | `725 px`，`(1341,1525)–(1378,1562)` | 仅 1280dp 右栏编辑器底栏新增 `表情` 图标，左中栏 0 变化。 |
| 17 | `editor_tablet_w1280_rightpane_light.png` | `2560×1600` | `725 px`，`(1341,1525)–(1378,1562)` | 同上（浅色 1280dp 右栏），其余 0 变化。 |
| 18 | `editor_threepane_w749_modal_light.png` | `1498×1118` | `725 px`，`(210,995)–(247,1032)` | 仅 749dp 居中模态卡底栏新增 `表情` 图标，其余 0 变化。 |
| 19 | `editor_threepane_w933_modal_dark.png` | `1866×1392` | `725 px`，`(394,1269)–(431,1306)` | 仅 933dp 居中模态卡底栏新增 `表情` 图标，其余 0 变化。 |
| 20 | `editor_twopane_w640_modal_light.png` | `1280×2048` | `725 px`，`(189,1925)–(226,1962)` | 仅 640dp 居中模态卡底栏新增 `表情` 图标，其余 0 变化。 |
| 21 | `messages_conversation_w411_light.png` | `822×1782` | `8946 px`，`(24,1649)–(693,1765)` | 严格限于底部输入栏 `y ≥ 1649`：左侧新增 `48×48dp` `表情` 按钮使输入框左缘右移，顶栏与全部消息气泡（`y < 1649`）0 像素变化。 |
| 22 | `messages_conversation_w411_dark.png` | `822×1782` | `8944 px`，`(24,1649)–(693,1765)` | 同上（深色对话页输入栏），气泡与冷却按钮 `12` 正常。 |
| 23 | `messages_sms_list_w1280_light.png` | `2560×1600` | `5827 px`，`(1226,1467)–(2431,1583)` | 严格限于 1280dp 右栏（`x ≥ 1200`）底部输入栏（`y ≥ 1467`）左侧新增 `表情` 按钮，左侧栏、中栏会话列表与右栏气泡 0 像素变化。 |
| 24 | `messages_sms_list_w1280_dark.png` | `2560×1600` | `5828 px`，`(1226,1467)–(2431,1583)` | 同上（深色 1280dp 三栏），其余区域 0 像素变化。 |

- **11 张新增 `emoji_panel_*.png` 逐张 `read_file` 目视结论（11/11 排版清晰无异常）**：
  1. `emoji_panel_default_w411_light.png`：8 列排布默认表情 21 枚（8+8+5），顶栏三组首图分类图标、选中胶囊+底部指示条与右侧删除图标清晰对齐。
  2. `emoji_panel_monkey_w411_light.png`：选中第二组酷猴（指示条居中对齐），16 枚亮色酷猴（8×2）黑边清晰。
  3. `emoji_panel_monkey_w411_dark.png`：深色 `surface` 背景 + `surfaceContainerLow` 面板，16 枚酷猴与标签栏图标同步切换为白描边暗色资源（`coolmonkey_*_dark`），无亮边锯齿。
  4. `emoji_panel_dumb_w411_light.png`：选中第三组呆呆，24 枚亮色呆呆（8×3）整齐无裁切。
  5. `emoji_panel_dumb_w411_dark.png`：深色模式下 24 枚呆呆命中 `grapeman_*_dark` 暗色资源，对比度协调。
  6. `emoji_panel_quick_reply_w411_light.png`：411dp 单栏毛玻璃快速回复面板下方无缝衔接 `280dp` 纯色表情面板，底栏图标已切换为 `键盘`，无透底或留缝。
  7. `emoji_panel_full_editor_w411_dark.png`：411dp 深色完整编辑器（`引用回复`）底栏切换为 `键盘` 图标，下方展示酷猴暗色表情面板，纯色层级自然。
  8. `emoji_panel_threepane_w749_light.png`：Fold8 `749×559dp` 紧凑三栏右栏（`411dp` → 8 列）内展开快速回复与表情面板，严格收束于中右分隔线右侧，左轨与中栏完全不被遮挡。
  9. `emoji_panel_tablet_w1280_dark.png`：`1280×800dp` 完整三栏右栏（`680dp` → 自适应 14 列，14+10 排完 24 枚呆呆表情）快速回复展开表情面板，每格 ≥ 48dp，草稿恢复横条与输入框未挤压（注：报告文字误写为“完整编辑器”，见 **S2**）。
  10. `emoji_panel_short_landscape_891x411_light.png`：`891×411dp` 矮屏横屏触发 `< 480dp` 的 `35%` 回退限高（`143.85dp`），18 列自适应排布，快速回复自动隐藏顶把手保住输入与工具按钮完整可见，第二行末尾 3 枚表情在可滚动视口底缘自然露半提示可纵向滚动。
  11. `emoji_panel_sms_w411_light.png`：411dp 短消息对话页输入栏左侧切换为 `键盘` 图标，下方无缝展开 `280dp` 默认表情面板，上方对话气泡内 `:)` 与 `{:2_41:}` 已正常渲染为论坛表情图。

---

### (2) 短消息表情（M7-0 #11）与纯图气泡隐藏复制（R-M7-G4 S2）

1. **短消息输入栏表情（M7-0 #11）**：
   - 对齐旧版 `fragment_sms.xml:17-25`（输入栏左侧 `ib_emoji_switch`）与 `SmsFragment.java:132-133`（`setUpEmojiPopup`），同时将旧版 `32×32dp` 触控区升级至合规的 **`48×48dp`**（`SmsConversationScreen.kt:434-439`）；
   - `MessagesPaneRendererImpl.kt:34, 81-87` 通过构造函数注入 `Optional<QuickReplyEmojiPanelRenderer>`（并保留 4 参次构造函数兼容旧单测），`SmsConversationScreen.kt:76` 同时支持 `rememberResolvedEmojiPanelSlot` 兜底；
   - 外层移除原 `.imePadding()`，交由 `EmojiPanelController` + `EmojiPanelSlotBox` 统一接管，实测 `IME ↔ 表情` 底缘零位移、点输入框自动切回键盘、按系统返回先关表情面板不误退对话页、切会话或登录失效态自动收起面板（唯面板 `删除` 键整表情回删同受 **B2** 影响）。
2. **纯图气泡隐藏长按「复制」（闭合 R-M7-G4 S2）**：
   - `SmsConversationScreen.kt:295-316` 计算 `canCopy = remember(rawContent, isPlainText) { extractSmsBubblePlainText(rawContent, isPlainText).isNotEmpty() }`；
   - 纯图片气泡（如 `<img src="https://.../only.jpg" />`）或空内容气泡 `canCopy == false`：既不挂载 `combinedClickable(onLongClick = ...)`（无无障碍误报 `OnLongClick("复制")`、无空触感反馈、不干扰图片展示），也不挂载 `DropdownMenu`；
   - 图文混排气泡与**纯论坛表情气泡**（如 `<img src="images/smilies/coolmonkey/01.gif" />`，`extractSmsBubblePlainText` 还原为 `"{:2_41:}"` 非空）均保持 `canCopy == true`，长按正常弹出 `复制`，已由 `MessagesComposeAndCopyTest.kt:466-548` 撤回即红单测覆盖。

---

### (3) 插入按论坛代码、在光标/选区处、回删整表情

- **按论坛代码插入**：通过（插入 `:)`、`{:2_41:}`、`{:3_57:}` 等纯文本规范代码，不在输入框内渲染为图片，符合裁定 #1）。
- **光标中间插入与选区替换**：通过（`QuickReplySheet` 的 `TextFieldState.edit`、`FullEditorViewModel` 的 `EditorBodyDocument.insertTextAtCursor`、`SmsConversationScreen` 的 `TextFieldValue` 均按 `[selection.min, selection.max)` 精确插入或替换，并将光标置于新插入代码末尾，焦点与草稿无损）。
- **回删整表情**：**未通过（已列为阻断项 `B2`）**——三处宿主的 `onBackspace` 目前均只调用按 Unicode 字形簇回退 1 字符的 `previousGraphemeOffset`，点表情面板 `删除` 键会把 `{:2_41:}` 删成残缺的 `{:2_41:`。

---

### (4) 「3 态控制器」是否必要、有无更简单写法

1. **概念上是否必要：是，区分 `Closed / Emoji / SwitchingToIme`（或等价的升起期高度锁存态 + 外接键盘超时）确有必要。**
   - 原 `E5`/`E6` 预留的单个 `isEmojiPanelOpen: Boolean` 在点「键盘」时面临两难：若首帧立即置 `false`，此时系统 `WindowInsets.ime` 仍为 `0px`（Android IPC 唤起软键盘首帧前有数帧空白，且升起动画持续 ~250ms），输入栏首帧必塌陷到底再随键盘弹起（`|Δbottom| = 260dp`）；若等到 `ime >= rememberedHeight` 才置 `false`，则按钮图标在 250ms 内卡在「键盘」不响应，且外接物理键盘不弹软键盘时会永久卡死在表情面板。因此需要一个「已收起表情面板 UI、按钮已切回『表情』，但底部仍用 `max(holdHeight, liveIme)` 托住直到软键盘追上或超时」的过渡态。
2. **代码上能否明显简化：可以，当前 `EmojiPanelHost.kt`（200 行）存在状态重复与组合期写状态，可精简 ~65–75 行（见 `S1`）：**
   - `lockedEmojiTotalHeightDp`（仅 `Emoji` 态非零）与 `handoffHoldHeightDp`（仅 `SwitchingToIme` 态非零）生命周期严格互斥，且 `bottomSlotHeightDp` 在两态下公式完全相同（均为 `maxOf(holdHeightDp, effectiveImeDp)`），可直接合并为**单一 `holdHeightDp: Dp` 字段**；
   - `syncFrame(...)` 在组合阶段直接将 `imeBottomPx / navBarBottomDp / windowHeightDp` 拷入 6 个 `mutableStateOf` 镜像属性（`:68-79`），既制造冗余状态又可能在组合期写快照触发额外重组；改为把当帧尺寸作为只读参数传入计算函数即可删去全部 `live*` 镜像字段，并顺带消除 `consumeImeInSlot` 分支（修复 **B1**）。

---

### (5) `git merge-tree` 与最新 `origin/main`（`ed361c2`）及 `card/E6b-edit-delete-post` 冲突核验

1. **与最新 `origin/main`（`ed361c2`）**：
   - 执行 `git merge-tree --write-tree origin/main 0428dd4fc60de1fde4e86d74ed1e98dbc7154e45` → **`exit=0`**（输出树 `ce73c2059a75da859bbc2b8acadcbdb543f3ce5b`），**0 冲突**。
2. **与已推送的并行分支 `origin/card/E6b-edit-delete-post`（`7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`）**：
   - 执行 `git merge-tree --write-tree origin/card/E6b-edit-delete-post 0428dd4fc60de1fde4e86d74ed1e98dbc7154e45` → **`exit=1`**，**仅 1 个文件存在文本冲突**：`hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt`（`ComposeContracts.kt`、`FullEditorHost.kt`、`FullEditorViewModel.kt`、`QuickReplySheet.kt` 均自动合并无文本冲突；即使修复 `B1`/`B2` 改动 `FullEditorHost.kt:217` 与 `EditorContentModel.kt:116`，与 `E6b` 修改区亦零行号重叠）。
   - **`FullEditorScreen.kt` 合并要点（供后合入方对表解决，工作量极小）**：
     1. 保留 `E7` 外层 `BoxWithConstraints` + `rememberEmojiPanelController` + 底栏 `EmojiPanelSlotBox`，同时保留 `E6b` 新增的 `restoreOriginal` 可撤销 Snackbar `LaunchedEffect`、`FullEditorTopBar` 的 `uiState.effectiveCooldownSeconds` / `uiState.canRestoreOriginal` / `viewModel::restoreOriginal` 参数、以及 `isEditWaitingOriginal -> EditOriginalStatusArea` 加载/错误占位分支；
     2. **语义衔接点**：`E6b` 将标题输入框抽成了共用组件 `EditorSubjectField` 并新增了 `EditPostMetaSection`（编辑首楼标题/分类）；合并时需把 `E7` 的 `onInputPress: () -> Unit = {}` 下传至 `EditorSubjectField`（覆盖 `NewThreadMetaSection` 与 `EditPostMetaSection` 两处调用），使编辑首楼时点标题框同样能从表情面板切回软键盘；
     3. **截图兼容性核实**：`E6b` 新增的 `EditAndDeletePostScreenshotTest`（4 张 `editor_edit_*.png`）使用 `@Config(application = Application::class)` 且未显式传 `emojiPanelSlot`，`rememberComposeSlotsEntryPoint()` 在纯 `Application` 下安全返回 `null`，因此两卡合并后 **不会导致 CI `verifyRoborazziDebug` 意外变红**（后合入方可视需要为其中 2 张编辑器截图显式注入 `emojiPanelSlot` 重录以与其余 12 张 `editor_*.png` 底栏视觉统一）。

---

## 四、非阻断建议（Non-Blocking Suggestions）

1. **S1（精简 `EmojiPanelController` 状态字段并改善退场中途重弹键盘响应，`EmojiPanelHost.kt:35-97`）**：在修复 `B1` 时顺带将互斥的 `lockedEmojiTotalHeightDp` 与 `handoffHoldHeightDp` 合并为单一 `holdHeightDp: Dp`，移除组合期写状态的 `syncFrame` 6 个 `live*` 镜像字段；并将 `:91` 的 `if (safeImePx == 0) awaitingImeHide = false` 放宽为 `if (safeImePx == 0 || safeImePx < prevPx) awaitingImeHide = false`，使软键盘退场中途（尚未到 `0px`）若被系统或其他输入框重新拉起时也能即时切入 `SwitchingToIme`。
2. **S2（同步交付报告截图说明文字，`_reports/E7.md:168` / `EmojiPanelScreenshotTest.kt:142`）**：`emoji_panel_tablet_w1280_dark.png` 实际渲染的是 `1280dp` 三栏右栏 `QuickReplySheet` 展开 14 列呆呆表情面板（符合任务卡 `_cards/E7.md:46` 要求），但 `_reports/E7.md:168` 误写为“完整编辑器展开表情面板”，修复轮更新报告时顺手对齐即可。
3. **S3（与 `E6b` 后合入方的语义衔接备忘，`FullEditorScreen.kt`）**：后合入的一方在解决 `FullEditorScreen.kt` 唯一冲突时，记得将 `onInputPress` 透传进 `E6b` 抽出的 `EditorSubjectField`（详见 §三(5)）。


---

# R-E7 第 2 轮复审结论（`APPROVE`）

- **复审结论**：**`APPROVE`**（首轮阻断项 `B1`、`B2` 及建议 `S1`–`S3` 全部闭合，0 新增阻断项，0 新问题）
- **复审提交**：`origin/card/E7-emoji-panel` @ `e25e057de6fecd534b7aaef9f5bb8fbf781363d3`（`e25e057`，已合入含 `E6b` 的 `main@b803846`）
- **CI 核验**：[Android CI #45823947592](https://github.com/mailwmj/hipda-next/actions/runs/45823947592)
  - `headSha == e25e057de6fecd534b7aaef9f5bb8fbf781363d3`（精确匹配），`status == completed`，`conclusion == success`（Job `139923117610` `Build, Lint & Unit Tests`，耗时 15m45s）；
  - 全绿通过 `Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`，`Check for Roborazzi diff images` 为 `skipped` 即 0 差异）、`Verify APK outputs`。
- **最新 `origin/main` 合并冲突核验**：
  - 当前最新 `origin/main` 为 `846ff3aa86f921499ec8b6315ee4af94ef4ac97b`（`846ff3a`，已含 `E6b` 与 `M7-1`）；
  - `git merge-tree --write-tree origin/main e25e057de6fecd534b7aaef9f5bb8fbf781363d3` → **`exit=0`**，输出树 `ae842f5c77f555072be4ede737714b7a64652cce`（**0 冲突**）。
- **代码规模与边界复验**：相对 `origin/main@846ff3a` 共变更 12 个生产文件 `+899 / -171`，净增 **`+728` 行**（比首轮 `+745` 行再减 17 行，远低于 975 行警戒线）；禁改文件、共享构建/清单文件与两个扫描测试白名单继续严格 **0 改动**；主目录 `/home/mir/Documents/hipda-next` 全程只读零修改。
- **快照增量核验**：`git diff --name-status 0428dd4...e25e057 -- 'hipda/src/test/snapshots/images/*'` 除合入 `main@b803846` 带入的 4 张 `E6b` 快照外，E7 自身的 24 张授权更新与 11 张新增 PNG **0 字节变动**，全仓其余快照（含 `E6b` 4 张 `editor_edit_*.png`）0 漂移。

## 五、第 2 轮逐项核查明细

### 1. B1 核验：每宿主唯一 IME 消费点、共享净键盘高换算与居中模态/右栏红转绿测试 — **已闭合**

1. **每宿主唯一 IME 消费点**：
   - 全包扫描 `git grep -n "WindowInsets\.ime\|imePadding" e25e057 -- 'hipda/src/main/java/net/jejer/hipda/feature/compose/*' 'hipda/src/main/java/net/jejer/hipda/feature/messages/*'` 确认：**全仓仅存唯一一处 `WindowInsets.ime` 读取**（`EmojiPanelHost.kt:33`），且仅由 `EmojiPanelSlotBox`（`EmojiPanelHost.kt:152-160`）单点消费为底部占位高度。
   - `FullEditorHost.kt:250-258` 已移除 `FullEditorPlacement.CenteredModal` 外层的 `.imePadding()`（仅保留水平 `systemBars` 与模态卡宽度容器）；`FullEditorScreen.kt:167-193` 在 `isModalCard == true` 时将模态 `Surface`（底槽 `> 0.dp` 时平底角 `bottomStart/End = 0.dp` + `Alignment.BottomCenter` 紧贴底槽，底槽 `== 0.dp` 时恢复 `HiShapeTokens.Large` + `Alignment.Center` + `24dp` 边距）置于上方 `weight(1f)` 容器、下方紧接唯一的 `EmojiPanelSlotBox`（`Emoji`/`SwitchingToIme` 态挂 `.pointerInput(Unit) {}` 阻断点击穿透至背后暗罩，卡片外侧与上方暗罩点击仍正常触发收起）。
2. **三宿主共用 `净键盘高 = IME − 导航栏` 换算**：
   - `EmojiPanelHost.kt:25-36` 抽出共享 `rememberEmojiInsetsFrame(windowHeightDp)`，三处宿主（`QuickReplySheet.kt:421`、`FullEditorScreen.kt:107`、`SmsConversationScreen.kt:126`）统一经 `rememberEmojiPanelController` 读取原始 `WindowInsets.ime.getBottom` 与 `WindowInsets.navigationBars.getBottom` 计算 `IME − navigationBars` 写入 `SessionKeyboardHeightStore`，彻底杜绝 `isModalCard` 传 `0` 或外部 `contentPadding` 混入造成的跨宿主缓存污染。
3. **居中模态 / 右栏逐帧测试“修复前红、修复后绿”**：
   - `EmojiPanelBehaviorTest.kt:620-859` 新增 `749×559`、`933×696`、`640×1024` 居中模态（含同窗切回 `QuickReplySheet` 验证 `236 + 24 = 260dp` 无 `+24dp` 污染）与 `1280×800` 右栏三宿主逐帧测试，同时校验工具栏底缘 `≤ 1.0dp` 与正文区高度不塌陷（修复前首帧跳变 `114dp / 251dp / 260dp` 红灯，修复后全绿）；
   - `EmojiPanelBehaviorTest.kt:1025-1173` 原样移植 `V-E7` 的 3 项真实 `NextAppRoot + FullEditorHost` 单帧步进探针（`mainClock.autoAdvance = false` + `Snapshot.sendApplyNotifications()` + `advanceTimeByFrame()`），全部转绿固化。

### 2. B2 核验：共享整枚回删函数覆盖全场景 — **已闭合**

- `EmojiPickerPanel.kt:66-79` 新增零分配共享纯函数 `previousEmojiOrGraphemeOffset(text, cursor)`：基于 `EmojiMap.groups` 全部 61 枚论坛表情代码按长度降序（12 → 2）用 `CharSequence.regionMatches` 匹配光标前完整后缀，命中则一次返回 `safeCursor - code.length` 整枚删除，未命中回退至 `previousGraphemeOffset`。
- `QuickReplySheet.kt:491`、`EditorContentModel.kt:116`（`EditorBodyDocument.backspaceAtCursor`）、`SmsConversationScreen.kt:230` 三处宿主已全部统一调用，且均保持非空选区优先删选区、块首回删内联图片块（`FullEditor`）原语义不变。
- `EmojiPanelHeightPolicyTest.kt:164-187` 逐一遍历断言全部 61 枚规范代码（含 `:)` 短码、`:loveliness:` 长码、`{:2_41:}`、`{:3_57:}`）、残缺代码（`头{:2_41:`、`头:` 仅退 1 字符不误删前文）、中文、Unicode 代理对（`😀`、`𠮷`）与组合变音字形簇（`e\u0301`）；`EmojiPanelBehaviorTest.kt:353, 863-961` 在三宿主 UI 上逐项实测通过。

### 3. S1 核验：控制器精简后状态机等价性与行为不变性 — **已闭合（无新问题）**

- 逐分支核查重写后的 `EmojiPanelController`（`EmojiPanelHost.kt:40-142`，由 200 行精简至 161 行）：
  - `frameProvider: () -> EmojiInsetsFrame` 读取 `rememberUpdatedState(frame)`（`structuralEqualityPolicy`），任意读取 `bottomSlotHeightDp` / `panelContentHeightDp` / `navBarBottomDp` 的组合作用域均自动建立精确的 Compose Snapshot 订阅，且消除了原 `syncFrame` 在组合期写多个独立 `mutableStateOf` 的冗余开销；
  - 单一 `holdHeightDp` 替代原互斥双字段：从有键盘态切入 `Emoji` 时先锁存 `current.imeBottomDp` 并将净高提交 `tracker`，退场帧（`safeImePx == 0 || safeImePx < prevPx`）清零 `awaitingImeHide` 与 `holdHeightDp` 后无缝由等价的 `panelTotalHeightDp`（`= netHeight + navBar`）接力；若退场中途系统重弹键盘（`!awaitingImeHide && safeImePx > prevPx`），立即锁存 `hold` 切入 `SwitchingToIme`（由新增单测 `EmojiPanelBehaviorTest.kt:193-203` 覆盖）；外接物理键盘 450ms 超时收敛与更矮键盘重采样（`onHandoffTimeout`）均完整保留。

### 4. S3 核验：与 `E6b` 冲突解决语义完整性及编辑模式表情可用性 — **已闭合**

- 逐行比对 `git diff b803846...e25e057 -- FullEditorScreen.kt` 确认 `E6b` 全部能力完整保留：
  1. 顶栏 `FullEditorTopBar` 传入 `uiState.effectiveCooldownSeconds`（编辑免 30s 冷却）、`uiState.canRestoreOriginal` 与 `viewModel::restoreOriginal`（`:135`），以及 `restoreOriginal` 独立令牌可撤销 Snackbar（`:81-98`）；
  2. 原文加载/错误占位 `isEditWaitingOriginal -> EditOriginalStatusArea`（`:102, 113-115, 136-138, 185`），等待原文期间自动收起表情控制器并隐藏底栏与 `EmojiPanelSlotBox`；
  3. 编辑首楼 `EditPostMetaSection`（`:147-149, 352-365`）与抽出的共用标题框 `EditorSubjectField`（`:368-385`）同时保留了 `syncVersion = uiState.bodySyncVersion`（点「还原原文」/「撤销」即时刷新标题 UI）和 `onInputPress`（发新帖与编辑首楼点击标题框均自动从表情切回软键盘）；
  4. 新增 `EmojiPanelBehaviorTest.fullEditor_editPostMode_emojiPanel_zeroShift_insertWholeBackspace_andSubjectTapSwitchesToKeyboard`（`:1177-1247`）端到端验证 `EditPost` 下 `IME ↔ 表情` 零位移、插入 `{:2_41:}` 联动顶栏出现「还原原文」、点删除整枚回删恢复原文、点标题框零位移切回键盘。
