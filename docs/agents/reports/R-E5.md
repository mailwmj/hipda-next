# 独立代码审查报告 R-E5：毛玻璃跟手弹出快速回复面板

- **审查结论**：**CHANGES-REQUESTED**（**1 项阻断 B1**，3 项非阻断建议 S1–S3）
- **审查对象**：`origin/card/E5-quick-reply-sheet` @ `73a64682e336e08fb362659e5e078a83ed7b834d`
- **比较基线**：`92e15637c8491291652af4fdd25cfe67aae6739c`（含 `E4` 合入点，`git merge-base` 精确匹配）
- **合并冲突核验（补充项 3）**：
  - 与已合入 `E16` 的最新 `origin/main`（`9338529070a9df6c5feeed5bb281ffba411abf35`，含 `6ff55a9` Merge `card/E16-routing-links-attachments`）：`git merge-tree --write-tree` 退出码 `0`（tree `68f68c4b34e889afc150288fe0b8f33762e42436`），**零冲突、零重叠文件**（E16 改 `PostBodyRenderer.kt`、`NextAppRoot.kt`、`ThreadBottomToolbar.kt:88-90`；E5 仅改 `ThreadDetailPane.kt` 与 `ThreadDetailViewModel.kt`）。
- **CI 核验**：[Android CI #45762412787](https://github.com/mailwmj/hipda-next/actions/runs/45762412787)
  - `headSha`：`73a64682e336e08fb362659e5e078a83ed7b834d`（精确一致）
  - 状态与结论：`completed / success`（Job `139715247530` Build, Lint & Unit Tests，耗时 12m40s）
  - 已核实通过步骤：`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 一致性校验、`testDebugUnitTest` + `:core-parser:test` + `-Proborazzi.test.verify=true`、APK 包名与产物校验。

---

## 一、阻断项（Blockers）

### B1：中等可视净高（含主力折叠屏 `749×559dp` 弹键盘）与紧凑临界区存在输入框高度归零（`0px`）、底栏按钮裁切溢出及误隐藏 `插入图片`/`表情` 问题
- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:77`（`QuickReplyTightSheetThreshold = 164.dp`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:305, 333-387`（`HiGlassSurface(heightIn(max = maxSheetHeightDp))` 内 `Column` 固定高子项与 `weight(1f, fill = false)` 分配及 `isTightSheet` 分支）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:460-480, 485-499, 530-554, 574-593`（头部双行叠加 `60dp`/`80dp`、草稿条 `56dp`、错误条 `48dp`、底栏 `60dp`）
  - 测试漏检：`hipda/src/test/java/net/jejer/hipda/feature/compose/QuickReplySheetTest.kt:207-272`（仅在 `891dp` 竖屏断言外框 `sheetHeightDp <= expectedMaxDp`，未断言中等净高下输入框非零可见高与按钮零裁切）
- **违反条款**：任务卡范围 §2（`min(300.dp, 可视净高 × 60%)`，输入框**最低保底 1–2 行**；仅 `可视净高 < 180dp` 压成紧凑输入行保障输入框与 `发送` 不溢出截断）、范围 §4（底部工具行保留 `插入图片`、`表情` + `发送`）、验收标准 §2 及 `R-E5` 重点 2（`min(300dp, 可视净高×60%)` 与 `< 180dp` 紧凑态零溢出）。
- **具体触发路径（3 条确定性复现路径）**：
  1. **路径 ① — 主力折叠屏 `Galaxy Z Fold8` 横屏（`749×559dp`）弹软键盘且存在恢复草稿（或报错）时输入框被挤压为 `0px` 不可见且 `发送` 被裁切**：
     - 在 `749×559dp`（状态栏 `24dp`）打开含已存草稿 `reply:<tid>` 的快速回复面板，弹出常规软键盘 `IME = 250dp`：
       `visibleNetHeightDp = 559 - 24 - 250 = 285dp`（`>= 180dp`，非矮屏），`maxSheetHeightDp = 285 × 0.60 = 171dp`（`>= 164dp`，`isTightSheet == false`），进入 `QuickReplySheet.kt:366` `else` 分支。
     - `Column` 先测量非 `weight` 子项：
       - `QuickReplyHeaderZone(tightMode = false)`：把手 `32dp` + 标题行 `28dp` = `60dp`（`E6` 绑定 `展开全屏` 后为 `32 + 48 = 80dp`）；
       - `QuickReplyDraftRestoredBanner`：`TextButton(48dp)` + `vertical = 4dp×2` = `56dp`；
       - `QuickReplyBottomActionBar`：`SendButton(48dp)` + `top 4dp + bottom 8dp` = `60dp`；
       - 非权重子项合计 **`176dp`**（`E6` 绑定后 **`196dp`**；若同时有 `inlineError` 则达 **`224dp` / `244dp`**，连 `933×696dp` 完整三栏弹 `320dp` 键盘 `maxSheet = 211dp` 也会溢出）。
     - `QuickReplyInputBox` 带 `Modifier.weight(1f, fill = false)`（`:370`），收到剩余高度约束 `maxHeight = (171 - 176).coerceAtLeast(0) = 0`，内部 `.heightIn(min = 64.dp)` 被 `incomingMax = 0` 钳为 **`0px`**——**输入框彻底消失无法点击输入**，同时底部 `发送` 按钮溢出 `HiGlassSurface` 被 `.clip(shape)` 裁切 `5–25dp`。
  2. **路径 ② — `visibleNetHeightDp ∈ [180dp, 273dp)`（即 `maxSheetHeightDp ∈ [108dp, 164dp)`，`isTightSheet == true`）误丢弃 `插入图片`/`表情` 工具栏，且出错时裁切 `29–45dp`**：
     - 在 `749×559dp`（状态栏 `24dp`）弹出 `IME = 265dp` 时，`visibleNetHeightDp = 270dp`（远高于 `< 180dp` 阈值），但 `maxSheetHeightDp = 162dp < 164dp` 命中 `:347` `isTightSheet == true`，整块跳过 `QuickReplyBottomActionBar`，导致 `E6`/`E7` 绑定后在折叠屏横屏弹键盘时**`插入图片`与`表情`按钮彻底消失**；
     - 同分支在 `visibleNetHeightDp = 185dp`（`maxSheetHeightDp = 111dp >= 96dp`）且提交失败出现 `inlineError`（`发送失败 | 重试`）时，叠加 `Header(32/48dp) + ErrorStrip(48dp) + Row(60dp) = 140–156dp > 111dp`，底部输入框与 `发送` 按钮被裁切 **`29–45dp`**。
  3. **路径 ③ — `< 180dp` 矮屏紧凑态（`visibleNetHeightDp ∈ [160dp, 180dp)`，即 `maxSheetHeightDp ∈ [96dp, 108dp)`）带内联错误时溢出**：
     - `:348` 条件为 `metrics.maxSheetHeightDp >= 96.dp`，当 `netHeight = 165dp`（`maxSheetHeightDp = 99dp`）且 `inlineError != null` 时，`QuickReplyInlineErrorStrip(48dp)` + 紧凑输入行 `Row(48dp + 12dp = 60dp)` = **`108dp > 99dp`**，底部裁切 `9dp`。
- **最小修复建议**：
  1. 将 `QuickReplyHeaderZone` 合并为单行紧凑头部（把手居中叠于顶部 `6dp` 处 + 左上下文提示 + 右可选 `48×48dp` `展开全屏`，未绑定高 `36dp`、绑定高 `48dp`，省去拆两行的额外 `32dp`）；
  2. 删去 `isTightSheet` 对 `QuickReplyBottomActionBar` 的截断：凡 `netHeight >= 180dp`（`!isCompactShortScreen`）保持正常三段（头部 + 保底 ≥ 1 行输入框 + 底部 `插入图片`/`表情`/`发送` 工具行），按 `maxSheetHeightDp` 扣除必保项（头部 + 保底输入框 + 底栏）后的剩余净高动态决定是否展示 `QuickReplyDraftRestoredBanner` / `QuickReplyInlineErrorStrip`（空间不足时收起次要横条或压紧上下内边距，绝不让 `weight(1f)` 输入框跌破 1 行保底高）；`< 180dp` 紧凑态下仅当 `maxSheetHeightDp >= 112.dp` 才额外渲染独立错误行；
  3. 在 `QuickReplySheetTest` 补齐中等净高（如 `749×559dp` + `IME = 250dp/265dp` 含草稿 + 错误 + 绑定插槽）与紧凑态（`netHeight = 165dp` 含错误）下 `inputBounds.height >= 36.dp`、三枚动作按钮可见且 `bounds.bottom <= sheetBounds.bottom` 的硬断言。

---

## 二、卡面重点与通用清单逐项核验

| 检查维度 | 结果 | 核验依据（`file:line`） |
|---|---|---|
| **重点 1：毛玻璃拓扑与三栏边界** | **通过** | `ThreadDetailPane.kt:480-481, 874, 957-979`：快速回复展开时 `effectiveSuppressBottomGlass = true`，整块不挂载 `ThreadBottomToolbar`（其内部 `:181` 亦有 `if (suppressBottomGlass) return` 双重守卫），`QuickReplySheet` 复用同一 `detailGlassState` 与楼层 `LazyColumn` 保持严格兄弟拓扑（0 新增采样源、0 嵌套）；面板与暗罩位于 `ThreadDetailPane` 内部，三栏（`749dp`/`933dp`）与铰链双栏严格限在右栏，守住 `HiGlassMarker = 3 (≤ 3)`、`LayerBackdrop = 2 (≤ 2)`、`drawBackdrop = 3 (≤ 5)`；`LocalHiGlassSuspended = true` 时同步归零。 |
| **重点 2：IME、限高、手势隔离与返回栈** | **部分未过（见 B1）** | **通过部分**：`QuickReplySheet.kt:254-282, 440` 手势隔离正确——`NestedScrollConnection` 置于 `.verticalScroll(scrollState)` 父侧，非 `UserInput` 或非折叠选区（`!selection.collapsed`）直接放行内部滚动与选区拖拽，仅把手或 `inputScrollState.value == 0` 顶边界超界下拉触发跟手关闭（且输入框超界传 `allowUpwardExpand = false` 绝不误触展开全屏）；点暗罩、系统返回、把手点击与无障碍 `CustomAccessibilityAction` 统一走 `requestDismiss`；减动效 0ms `snapTo` 一帧到位；`BackHandler` 在 `if (isQuickReplyOpen)` 内条件挂载（晚于 `MultiPaneReadingContainer:269` 与 `PositionStack:485`，早于打开时动态挂载的 `ModalDrawer`/`LoginOverlay`/`ImageViewerOverlay`），`Dismissing` 退场期吞掉连按返回。<br>**未过部分**：见阻断项 **B1**。 |
| **重点 3：草稿、`R-D6 S2`、冷却、提交与过滤模式** | **通过** | `QuickReplyViewModel.kt:185-219, 335-340`：恢复旧草稿标记 `hadPersistedDraft = true`；手动删空（含全空白）退出或点横条 `清空` 均经 `NonCancellable` 串行队列显式调用 `postRepository.deleteDraft(DraftKey.reply(tid))`，彻底收口 `R-D6 S2`（撤回即红单测 + 真实 Room 端到端单测均已覆盖）；`PostRepository.cooldownUntilMs` 向上取整倒计时、允许继续打字、0ms 切 `isSubmitting`、失败原地保留输入；`ThreadDetailPane.kt:728-747, 984-996` 非末页回复不移位 + `DetailPane` Snackbar `回复已发送 \| 查看` 先压 `PositionStack` 再跳新楼，末页末尾附近直接合入新楼并平滑滚底，过滤模式（`只看楼主`/`只看此人`）下正常回复且不污染过滤列表。 |
| **重点 4：工具行纯净度、授权范围与代码规模** | **通过** | 底部仅含可选 `插入图片`、`表情` 与固定 `发送`（无 `引用`/`粗体`、无多图缩略图条）；`git diff --name-status` 核实仅触碰卡面授权的 6 个生产文件（净增 `+1,492` 行 `< 1,575` 警戒线，全标准 Compose 组件，0 手写 `Layout`/`Modifier.Node`）、1 个授权既有测试（`MultiPaneGlassBudgetTest.kt` 纯新增用例，0 放宽上限）、5 个新增 `feature/compose` 测试与 9 张新增 `quick_reply_*.png`（既有快照 **0 漂移**）；`data/post/*`、`NextAppRoot.kt`、`MotionTokenScanTest` 白名单与构建配置 **0 改动**。 |
| **文案表逐字对齐（`copy.md`）** | **通过** | `strings_compose.xml:1-27` 全部 19 条字符串与 `docs/design/copy.md`（§导航与常用操作 `:30, 37, 39, 41`、§加载与进行中 `:69`、§错误与校验 `:84, 88, 89`、§Snackbar 与确认框 `:131, 132, 134`）逐字、逐标点一致。 |

### 新增 Roborazzi 截图逐张目视复验（9/9 已用 `read_file` 逐张核验）

| 快照文件 | 规格与场景 | 目视结果 |
|---|---|---|
| `quick_reply_phone_w411_light.png` | `411×891dp` 浅色常规态 | 通过：`20dp` 圆角液态玻璃面板、顶部把手、`回复 linus_hi`、多行输入框与光标、右侧 `发送` 清晰无裁切；未绑定态下 3 个可选按钮均隐藏。 |
| `quick_reply_phone_w411_dark.png` | `411×891dp` 深色常规态 | 通过：深色 `12dp` 模糊与 `74%` 着色对比度充足，双行换行与主色 `发送` 按钮清晰自然。 |
| `quick_reply_phone_w411_quote_draft_light.png` | `411×891dp` 浅色引用 + 草稿恢复 | 通过：`引用第 2 楼 · geek_reader_2`、`已恢复上次草稿 \| 清空` 横条与输入框层次分明，无重叠。 |
| `quick_reply_phone_w411_cooldown_dark.png` | `411×891dp` 深色 30s 冷却态 | 通过：右下按钮禁用显示滚动数字 `28` 且对比度良好，输入框保持可编辑光标态。 |
| `quick_reply_phone_w411_guest_light.png` | `411×891dp` 浅色未登录态 | 通过：原地显示 `登录后即可操作` 与 `登录` 按钮，排版对齐无截断。 |
| `quick_reply_landscape_891x411_ime_compact_light.png` | `891×411dp` 浅色 `IME=240dp` 紧凑态 | 通过：隐藏把手与草稿横条，贴合 `240dp` 键盘顶缘压为单行输入框 + `发送`，文字与按钮零裁切。 |
| `quick_reply_landscape_891x411_ime_compact_dark.png` | `891×411dp` 深色 `IME=240dp` 紧凑态 | 通过：深色紧凑输入行描边、字色与 `发送` 按钮清晰无溢出。 |
| `quick_reply_threepane_w749_light.png` | `749×559dp` 紧凑三栏浅色 | 通过：左 `64dp` 图标轨与中 `274dp` 列表无暗罩遮挡，右栏底部三段工具条已卸载，面板与暗罩严格限在右栏分隔线右侧。 |
| `quick_reply_threepane_w933_dark.png` | `933×696dp` 完整三栏深色 | 通过：左 `187dp` 常驻侧栏与中栏列表保持通透可交互，面板与暗罩严格落在右栏内。 |

---

## 三、负责人补充核查项专答

### 补充 (1)：E6 / E7 三个可选插槽 `@BindsOptionalOf` 契约完整性与下游交接签名清单

1. **零改 E5 文件接入结论**：
   - 在修复 **B1**（确保折叠屏/中等净高弹键盘不隐藏底部动作栏）并建议顺带补齐下方 **S2**（为 `FullEditorHandoff` 增加带默认值的 `replyPid` / `threadAuthor`）后，`ComposeContracts.kt` 的 `@BindsOptionalOf` + `EntryPointAccessors.fromApplication(..., ComposeSlotsEntryPoint::class.java)` 机制可让 **`E6` 与 `E7` 在各自新增的 Hilt `@Module` 中 `@Binds @Singleton` 绑定实现后，零修改 `feature/compose/*`（`ComposeContracts.kt`、`QuickReplySheet.kt`、`QuickReplyViewModel.kt`）自动点亮「展开全屏 / 把手上拉」「插入图片」「表情 / 键盘」三个插槽**；同时非 Hilt 单测可通过 `ThreadDetailPane` / `QuickReplySheet` 的显式可选参数直接注入测试替身。
   - **给负责人派发 `E6` 卡的范围提示（非 E5 缺陷，属跨卡路由衔接）**：`QuickReplySheet` 交接给 `E6` 后即结束自身会话（`activeReplyTarget = null`）；当用户在 **`E6` 完整编辑器**内点「发送」成功回复当前打开的帖子时，或从帖子详情楼层长按菜单点「编辑 / 删除」（`ThreadDetailPane.kt:908` 处 `FloorContextMenuOverlay` 的 `onEdit`/`onDelete` 目前仍为默认空回调 `{}`）时，`E6` 卡需获授权在 `NextAppRoot.kt` / `ThreadDetailPane.kt` 接通完整编辑器宿主挂载、楼层编辑删除入口，以及完整编辑器回帖成功后的详情页刷新回调（直接复用 E5 已在 `ThreadDetailViewModel` 暴露的 `onReplySuccessAtTail` 与 `jumpToNewReply`）。此外，`E6` 承接 `FullEditorHandoff` 时应以 `handoff.text` 为准，并查询一次 `observeDraft(DraftKey.reply(tid)).first() != null` 初始化 `E6` 自身的 `hadPersistedDraft`，保证在快速回复里删空旧草稿后直接点「展开全屏」再退出 `E6` 时同样触发 `deleteDraft`。

2. **供下游 `E6` / `E7` 直接使用的完整契约签名（以 `ComposeContracts.kt` 为准）**：
   ```kotlin
   // hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt

   // 1. E5 -> E6 完整编辑器无损交接载荷（:56-66）
   @Immutable
   data class FullEditorHandoff(
       val tid: String,
       val fid: Int,
       val text: String,
       val selectionStart: Int,
       val selectionEnd: Int,
       val quotePid: String? = null,
       val quoteAuthor: String? = null,
       val quoteFloor: Int? = null,
       val openImagePickerImmediately: Boolean = false,
   )

   // 2. E6 绑定目标接口（:70-72）：在 E6 的 @Module @InstallIn(SingletonComponent::class) 中 @Binds @Singleton 即可激活「展开全屏」「把手上拉」「插入图片」
   @Immutable
   fun interface FullEditorLauncher {
       fun open(handoff: FullEditorHandoff)
   }

   // 3. E5 传给 E7 表情面板的宿主回调与键盘高度上下文（:76-82）
   @Immutable
   data class QuickReplyEmojiPanelHost(
       val keyboardHeightDp: Dp,          // 已按 calculateEmojiPanelHeight 解析的有效高度（有键盘历史取键盘高，否则 ≤280dp & 窗口高 45%/35%）
       val windowHeightDp: Dp,
       val onInsertEmoji: (code: String) -> Unit,
       val onBackspace: () -> Unit,
       val onClose: () -> Unit,
   )

   // 4. E7 绑定目标接口（:86-89）：在 E7 的 @Module @InstallIn(SingletonComponent::class) 中 @Binds @Singleton 即可激活「表情/键盘」切换与等高面板容器
   @Immutable
   interface QuickReplyEmojiPanelRenderer {
       @Composable
       fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier)
   }

   // 5. 已内置的 Hilt 可选绑定与 EntryPoint（:94-107）
   @Module
   @InstallIn(SingletonComponent::class)
   abstract class ComposeSlotBindingModule {
       @BindsOptionalOf abstract fun bindOptionalFullEditorLauncher(): FullEditorLauncher
       @BindsOptionalOf abstract fun bindOptionalQuickReplyEmojiPanelRenderer(): QuickReplyEmojiPanelRenderer
   }

   // 6. 详情页已就绪的新回复落位接口（ThreadDetailViewModel.kt:434, 471，可供 E6 回复成功后复用）
   fun ThreadDetailViewModel.jumpToNewReply(targetPid: String? = null, fromAnchor: ReadingPositionAnchor? = null, redirectedPage: ThreadDetailPage? = null)
   fun ThreadDetailViewModel.onReplySuccessAtTail(redirectedPage: ThreadDetailPage? = null)
   ```

### 补充 (2)：论坛写操作安全、MockWebServer 隔离与零测试钩子核查
- **100% 离线、零真实站点访问**：全量扫描 `feature/compose` 生产与测试代码确认 **0 处 `4d4y.com` 或任何公网域名**。唯一触发真实 HTTP 栈的测试 `QuickReplyViewModelTest.realDefaultPostRepository_withMockWebServer_submitsReplyDeletesDraftAndStarts30sCooldown`（`:300-413`）将 `AppSettings.forumServer` 显式指向本地 `MockWebServer`（`127.0.0.1` 随机端口），使用内存 Room 数据库与本地构造的 GBK 报文/站内相对路径 `302 Location`，并在 `finally` 关闭 `MockWebServer` 与 `OkHttpClient` 线程池；其余单测与截图测试全部使用内存 `FakePostRepository` / `FakeComposeThreadDetailRepository`，绝无向真实站点发起读写请求的可能。
- **零测试专用生产钩子**：扫描全部 6 个生产文件确认 **0 处 `Modifier.testTag`**、**0 处测试专用 `SemanticsPropertyKey`**、**0 处测试专用 `CompositionLocal`**。

### 补充 (3)：与已合入 `E16` 的最新 `origin/main` 合并零冲突核查
- `git merge-tree --write-tree origin/main 73a64682e336e08fb362659e5e078a83ed7b834d`（`origin/main` @ `9338529070a9df6c5feeed5bb281ffba411abf35`，已合入 `E16` @ `6ff55a9`）→ 退出码 `0`（tree `68f68c4b34e889afc150288fe0b8f33762e42436`），**0 冲突、0 文件重叠**。

---

## 四、非阻断建议（Suggestions，建议在修 B1 时一并顺手收口）

1. **S1（`QuickReplyEmojiPanelHost.onBackspace` 代理对安全回删，`QuickReplySheet.kt:401-412`）**：当前无选区回删调用 `delete(s - 1, s)` 按单个 UTF-16 `Char` 删除；若用户在输入框中通过系统输入法输入了补充平面字符（如 Unicode Emoji `😀` 或扩展汉字 `𠮷`，占 2 个 `Char`）后点 `E7` 表情面板的回删键，只会删掉低代理项并残留孤立高代理项。建议改用 `val prev = Character.offsetByCodePoints(asCharSequence(), s, -1); delete(prev, s); selection = TextRange(prev)`（或 `BreakIterator.getCharacterInstance()`）。
2. **S2（`FullEditorHandoff` 补充默认参数 `replyPid` 与 `threadAuthor`，`ComposeContracts.kt:56-66` / `QuickReplyViewModel.kt:234-244`）**：当前用户在楼层菜单点「回复」（`QuickReplyMode.ReplyPost`，非「引用回复」）后再点「展开全屏 / 插入图片」时，`quotePid` 为 `null`，目标楼层 `targetPid`（用于 Discuz! `reppost` 提醒原层主）与普通回复的 `threadAuthor`（用于 `回复 {用户名}` 占位）未随 `FullEditorHandoff` 传出。建议以末尾带默认值的可选属性追加 `val replyPid: String? = null, val threadAuthor: String? = null`，保持现有调用 100% 兼容，使 `E6` 完全无需回头改 `ComposeContracts.kt`。
3. **S3（单测去除 `Thread.sleep(20L)` 轮询，`QuickReplyViewModelTest.kt:391-395`）**：建议用 `withContext(Dispatchers.Default) { withTimeout(5_000L) { ... } }` 或 `Turbine` 等待 `MockWebServer` 异步提交事件，避免墙钟 `Thread.sleep`。


---

# 独立代码审查报告 R-E5（第 2 轮复审）

- **审查结论**：**CHANGES-REQUESTED**（`R1-B1` 与 `S1–S3` 已收口；检出 **1 项新阻断 `B1-R2`**：V-E5 拖拽修复在动画运行期同帧 `down → move → up` 下反转协程调度顺序导致面板永久卡死不可关闭；附 `SubcomposeLayout` 替代方案与削减至 `≤ 1,575` 行的具体位置）
- **审查对象**：`origin/card/E5-quick-reply-sheet` @ `f1b1714aa3d1b80331c4e726342ed504347f71de`（已合入 `origin/main@9338529` 经 `4efe859`）
- **合并冲突核验**：`git merge-base origin/main f1b1714` 为最新 `origin/main`（`9338529070a9df6c5feeed5bb281ffba411abf35`），`git merge-tree --write-tree origin/main f1b1714aa3d1b80331c4e726342ed504347f71de` 退出码 `0`（tree `3e88fb1b2456063f735f65ed68fddce71bcb71f9`，快进零冲突）。
- **CI 核验**：[Android CI #45769362211](https://github.com/mailwmj/hipda-next/actions/runs/45769362211)
  - `headSha`：`f1b1714aa3d1b80331c4e726342ed504347f71de`（精确匹配）
  - 状态与结论：`completed / success`（Job `139738130802` Build, Lint & Unit Tests，耗时 18m16s，`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`:core-parser:test`、`testDebugUnitTest -Proborazzi.test.verify=true` 全绿，0 弃用警告）。

---

## 一、第 2 轮阻断项（Blocker）

### B1-R2：V-E5 拖拽修复在入场/回弹弹簧未静止时遭遇同帧 `down → move → up` 会取消退场协程，致面板永久锁死在 `Dismissing`/`Expanding` 僵尸态
- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:258`（`onDragDelta` 内 `if (offsetYPx.isRunning) scope.launch { offsetYPx.stop() }` 使用默认 `CoroutineStart.DEFAULT`）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:204-220`（`requestDismiss` 先设 `phase = SheetMotionPhase.Dismissing` 再用 `CoroutineStart.UNDISPATCHED` 启动 `snapTo`/`animateTo`，且 `latestOnDismissFinished()` 未做异常中断兜底）
  - `hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt:230-244`（`requestExpandToFullEditor` 同理先设 `phase = SheetMotionPhase.Expanding`，`handler(handoff); latestOnDismissFinished()` 未做中断兜底）
- **触发路径（确定性 100% 复现）**：
  1. 面板刚打开处于入场弹簧结算期（`LaunchedEffect(Unit)` `spatialDefault` 约 `0..220ms`，`offsetYPx.isRunning == true`），**或**用户轻拉把手 `< 72dp` 松手触发 `spatialSnap()` 回弹期（`:273`，约 `0..180ms`，`offsetYPx.isRunning == true`）；
  2. 用户在此期间快速轻扫把手执行同帧/单批 `down → moveBy(120dp) → up`（或绑定 E6 时上拉 `-90dp`）：
     - `moveBy` 同步调用 `onDragDelta`（`:250`）：更新 `dragOffsetYPx = base + 120dp`、`isDragging = true`，因 `offsetYPx.isRunning == true`，第 `:258` 行以 **`CoroutineStart.DEFAULT`** 调用 `scope.launch { offsetYPx.stop() }`——该任务仅排入 `AndroidUiDispatcher` 队列尾部，**尚未执行**；
     - 同一输入批次紧接着处理 `up`，同步调用 `onDragSettled` → `requestDismiss`（`:201`）：第 `:204` 行将 `phase` 置为 `SheetMotionPhase.Dismissing`，第 `:216` 行以 **`CoroutineStart.UNDISPATCHED`** 启动退场协程并**立即在当前调用栈先行进入 `offsetYPx.snapTo(startY)` → `Animatable.mutatorMutex.mutate`**，登记 `currentMutator = DismissJob`；
     - 输入批次返回后，`AndroidUiDispatcher` 执行刚才排队在后的 `{ offsetYPx.stop() }`：`offsetYPx.stop()` 后进入同一个 `Animatable.mutatorMutex.mutate`（同为 `MutatePriority.Default`），**直接向先进入的 `DismissJob` 抛出 `MutationInterruptedException` 将其取消**！
  3. **后果**：`DismissJob` 在 `snapTo`/`animateTo` 处异常中止，第 `:219` 行 `latestOnDismissFinished()`（即 `activeReplyTarget = null`）**永远不会执行**；而 `phase` 已永久停留在 `SheetMotionPhase.Dismissing`（上拉展开则永久停在 `Expanding` 且丢弃 `handler(handoff)`），导致暗罩点击、把手点击/拖拽、系统返回键（`BackHandler(enabled = true)` 持续抢占并走 `requestDismiss` 短路返回）**全部失效，整个详情右栏/单栏被半透明暗罩与面板永久锁死**。
  - 注：`QuickReplySheetTest.singleBatchHandleDrag_...`（`:733-741`）之所以未抓到，是因为测试先调了 `waitForIdle()` 等入场弹簧完全停稳（`offsetYPx.isRunning == false`）后才发单批手势，未覆盖 `offsetYPx.isRunning == true` 分支。
- **修复建议**：
  1. 在 `onDragDelta`（`:255-259`）先记录 `val wasDragging = isDragging`，仅在 `!wasDragging && offsetYPx.isRunning` 时以 **`CoroutineStart.UNDISPATCHED`** 同步调用 `scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetYPx.stop() }`，保证 `stop()` 严格先于同帧随后的 `onDragSettled` 进入 `MutatorMutex`，且同一次拖拽的多段 `MOVE` 不重复派发 `stop()`；
  2. 在 `requestDismiss`（`:216-220`）与 `requestExpandToFullEditor`（`:240-244`）中用 `try { ... } finally { isDragging = false; dragOffsetYPx = 0f; if (scope.coroutineContext[Job]?.isActive != false) { /* handler + */ latestOnDismissFinished() } }` 兜底，确保只要组件树仍在（`scope` 未销毁），即使 `Animatable` 被中断也绝不残留僵尸 `Dismissing`/`Expanding` 态；
  3. 在 `QuickReplySheetTest` 补一条 `mainClock.autoAdvance = false` 下推进 `32ms`（`offsetYPx.isRunning == true` 入场中途及 `30dp` 轻拉回弹中途）立即执行单批 `down → moveBy(120dp) → up` 的回归断言。

---

## 二、负责人第 2 轮指定核查项逐条结论

### 1. R1-B1 让位优先级核对（通过，已闭合）
逐行核对 `QuickReplyAdaptiveSheetLayout`（[QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L453-L529)）：
- **优先级 1（最高，永不裁切）`输入框(≥1行) + 发送`**：双行态先预扣 `bottomBarEstPx + minStandardInputPx`（`:514`，含 `postBody` 实测单行高 + 内外边距共 `30dp`），不足时（`:497-500`）自动切紧凑单行 `compactRow`（预扣 `compactMinContentPx >= 48dp`）；所有路径 `inputMaxH` 保底 ≥ 1 行实测高，`发送` 恒定 `≥ 72×48dp`。
- **优先级 2 `已绑定按钮可达`**：双行态下当剩余高度不足 `fullCtxEstPx(48dp)` 时，右上角 `展开全屏` 自动迁入底部动作栏（`:517, 521` `bottomActionBar(canExpandToFullEditor && !showFullCtx)`）；紧凑单行态下 `插入图片`/`表情`/`展开全屏` 全部并入同一行（`:401-403`，固定总宽 `236dp`，在最窄 `360dp`/`411dp` 下仍留 `124–175dp` 输入宽，触控区均 `48×48dp`）。
- **优先级 3 `错误提示`**：在剩余空间 `rem` 中最先分配（`:503, 515`）；双行放不下但紧凑单行放得下错误条时优先切紧凑单行保住内联错误（`:498-500`）；连紧凑单行也放不下时置 `onErrorShedChanged(true)`（`:320-332, 510, 527`），自动转由 `LocalPaneSnackbarController(DetailPane)` 携带 `重试`/`登录` 动作弹出，不丢错误反馈。
- **优先级 4 > 5 > 6 `上下文行 > 草稿横条 > 32dp 把手`**：分配完错误后先判 `Context`（`:505, 517-519`；若让位则挂 `1×1px` `liveRegionFallback` 保无障碍播报），再进 `measureDraftAndHandle`（`:478-484`）严格先判 `Draft`、最后判 `Handle`（且 `< 180dp` 矮屏强制隐藏 `Draft` 与 `Handle`）。顺序与要求 **100% 一致**。

### 2. 248 格参数化矩阵测试断言强度核查（通过，真断言非空心）
核查 [QuickReplyLayoutMatrixTest.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/test/java/net/jejer/hipda/feature/compose/QuickReplyLayoutMatrixTest.kt#L70-L251) 与 [QuickReplySheetTest.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/test/java/net/jejer/hipda/feature/compose/QuickReplySheetTest.kt#L629-L694)：
- 覆盖 `8` 组参数（`draft × error × slotsBound`）× `31` 档净高（`420..120dp` 步长 `10dp`）= **248 格**，另加 `Fold8 749×559dp`（状态栏 `24dp`、`IME=250/265dp`）实机尺寸测试；
- 每格均执行 **6 项几何与行为硬断言**（非仅跑通不崩）：① 面板实测高 `<= min(300dp, netHeight×60%)` 且位于状态栏与键盘顶之间；② 输入框剪裁后可见高 `boundsInRoot.height >= 实测单行高`；③ `发送` 高度 `>= 48dp` 且**未裁切原始坐标** `positionInRoot + size`（穿透 `.clip` 检测溢出）完整落在面板内；④ 绑定态三枚动作按钮存在、含 `OnClick` 且未裁切坐标在面板内（未绑定态严格为 0）；⑤ 递归遍历面板**全部语义后代节点**校验未裁切 `positionInRoot + size` 零越界；⑥ 有错误时必内联可见或已投递 `DetailPane` Snackbar。

### 3. `SubcomposeLayout` 必要性评估与生产代码行数削减清单（当前净增 `1,588` 行，超 `1,575` 警戒线 `+13` 行）
- **`SubcomposeLayout` 是否必要**：当前实现仅对选定显示的槽位做单次 `subcompose`（无二次测量崩溃隐患，且在 `_reports/E5.md:169` 给出了说明），**但并非必须用 `SubcomposeLayout`**。原因：`QuickReplySheet` 外层已有 `BoxWithConstraints`（`:341`）拿到精确 `maxSheetHeightDp`，且 `QuickReplyAdaptiveSheetLayout` 内部本就先靠固定/单行估算值 `*EstPx`（`:458-468`）决定各槽显隐；由于所有非输入行均为 `maxLines = 1` + `HiCappedFontScale`，只需给各单行条显式锁高（把手 `32.dp`、上下文行 `48.dp`/`28.dp`、草稿条 `56.dp`、错误条 `48.dp`/`28.dp`、底栏 `52.dp + innerBottomPadding`），估算高即等于实测高，直接用纯函数 `resolveSheetSlotPlan(...)` + 标准 `Column` 即可实现完全一致的 6 级让位、0 残留语义节点，还能省去 `onErrorShedChanged` 测量期回写状态。
- **可削减位置（修 `B1-R2` 时任取 (a) 或 (b)+(c) 即可压回 `≤ 1,575` 行以内）**：
  - **(a) `QuickReplySheet.kt:319, 380-411, 435-530`（可减 ~55 行）**：用纯函数 `SheetSlotPlan` + 标准 `Column` 替换 `SubcomposeLayout`、`QuickReplySlot` 枚举及 9 个槽位 lambda 转发，`LaunchedEffect(uiState.inlineError, plan.shedErrorToSnackbar)` 直接在组合期读取；
  - **(b) `QuickReplySheet.kt:201-248`（可减 ~14 行）**：`requestDismiss` 与 `requestExpandToFullEditor` 共用同一退场/交接启动辅助函数（统一处理 `phase` 守卫、收键盘、同步 `startY` 与 `finally` 收尾）；
  - **(c) `QuickReplyViewModel.kt:108-115, 324-336`（可减 ~12 行）**：`init` 的仓库冷却倒计时与 `applyCooldownFailure` 的本地兜底倒计时抽取共用 `private suspend fun runCooldownTicker(untilMs: Long)`，消除重复 `while` 循环。

### 4. S1–S3 收口与重录截图目视核验（全部通过）
- **S1**：`previousGraphemeOffset`（[QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L118-L125)）采用 `BreakIterator.getCharacterInstance()` + `Character.offsetByCodePoints` 兜底，单测验证 `":)😀𠮷"` 连删两次准确剩 `":)"` 无孤立代理项。
- **S2**：`FullEditorHandoff`（[ComposeContracts.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/ComposeContracts.kt#L66-L67)）已追加默认属性 `replyPid: String? = null`、`threadAuthor: String? = null` 并在 `QuickReplyViewModel.onHandoffToFullEditor`（`:247-248`）完整透传，下游 `E6` 零改 `ComposeContracts.kt` 即可区分 `ReplyPost`（`reppost`）与 `ReplyThread`。
- **S3 与编译警告**：`QuickReplyViewModelTest.kt:319-434` 已移除 `Thread.sleep(20L)` 改为 `CompletableDeferred` + 虚拟时钟精确断言 `30s → 0s`；`QuickReplyViewModel.kt:342-345` 已改为 `withContext(NonCancellable)` 消除弃用警告。
- **2 张重录横屏紧凑态截图目视复验（已调 `read_file` 逐张核验，其余 7 张与全仓快照 0 字节变化）**：
  - `quick_reply_landscape_891x411_ime_compact_light.png` & `quick_reply_landscape_891x411_ime_compact_dark.png`：`891×411dp` + `IME=240dp`（`netHeight=171dp < 180dp`，`maxSheet=102.6dp`）下，把手与草稿横条按规隐藏，利用富余高度完整保留更高优先级的 `回复 linus_hi` 上下文行 + 紧凑输入框 + `发送`（总高 `88dp ≤ 102.6dp`），浅/深色对比度清晰、无裁切无重叠。


---

# 独立代码审查报告 R-E5（第 3 轮最终复审）

- **审查结论**：**APPROVE**（0 项阻断，全部历史阻断 `R1-B1`、`V-E5 B1`、`B1-R2` 与建议 `S1–S3` 均已彻底闭合，无新引入问题）
- **审查对象**：`origin/card/E5-quick-reply-sheet` @ `a5c9440255516940af46a824715fab6876928621`
- **合并冲突核验**：`git merge-base origin/main a5c9440` 为最新 `origin/main`（`9338529070a9df6c5feeed5bb281ffba411abf35`），`git merge-tree --write-tree origin/main a5c9440255516940af46a824715fab6876928621` 退出码 `0`（tree `149caa47f9d62ec0db7f27c36bf5d4061df69360`，快进 **零冲突**）。
- **CI 核验**：[Android CI #45773736474](https://github.com/mailwmj/hipda-next/actions/runs/45773736474)
  - `headSha`：`a5c9440255516940af46a824715fab6876928621`（精确匹配）
  - 状态与结论：`completed / success`（Job `139752279038` Build, Lint & Unit Tests，耗时 14m20s：`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`:core-parser:test`、`testDebugUnitTest -Proborazzi.test.verify=true`、APK 校验全部绿色通过）。

---

## 第 3 轮逐项核查结论

1. **`B1-R2` 根治与终态安全核查（通过）**：
   - `onDragDelta`（[QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L294-L308)）：先快照 `val wasDragging = isDragging`，仅在首次进入拖拽且弹簧仍在运行（`!wasDragging && offsetYPx.isRunning`）时以 `CoroutineStart.UNDISPATCHED` 同步执行 `offsetYPx.stop()`，严格先于同帧 `onDragSettled` 进入 `Animatable.mutatorMutex`，且同次拖拽内多段 `MOVE` 不重复启动 `stop()`；
   - `launchTerminalTransition`（[QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L241-L266)）：统一承载 `Dismissing` 与 `Expanding` 终态过渡，`try/finally` 重置 `isDragging = false; dragOffsetYPx = 0f`，并通过 `if (completed || scope.coroutineContext[Job]?.isActive == true) onComplete() else phase = SheetMotionPhase.Open` 精确区分两类中断：① 组件仍在树上时若动画被中断，`scope` 仍活跃，必定单次执行 `onComplete()`（交付 `handoff` 并调 `latestOnDismissFinished()` 卸载面板，绝不残留僵尸态，且入口受 `phase` 守卫不会双调）；② 组件自身因提交成功/切帖离开组合树时，`scope` 已取消，不误触发过期 `onComplete()`；回弹分支（`:318-325`）亦具 `try/finally` 清理；
   - 回归单测 `midSpringSingleBatchGestures_andExitExpandInterrupts_neverDeadlockSheet`（[QuickReplySheetTest.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/test/java/net/jejer/hipda/feature/compose/QuickReplySheetTest.kt#L785-L924)）在修复前稳定跑红，修复后完整覆盖入场弹簧中途单批下拉收起、回弹弹簧中途单批下拉收起/上拉展开、退场中途重拖与展开中途按返回等全部中断路径。
2. **`SubcomposeLayout` 移除后 `SheetSlotPlan + Column` 与 248 格矩阵核查（通过）**：
   - `resolveSheetSlotPlan`（[QuickReplySheet.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplySheet.kt#L106-L155)）与各单行组件显式 `.height(...)`（把手 `32dp`、上下文行 `ctxH`、草稿条 `draftPx`、错误条 `errorPx`、底栏 `bottomBarPx`、未登录条 `60dp/68dp+pad`）像素级严格对齐，100% 保持六级让位顺序且消除了布局期状态回写；
   - `QuickReplyLayoutMatrixTest.kt` 在本轮 **0 行改动、0 断言放宽**，248/248 格全部通过；9 张 `quick_reply_*.png` 与全仓快照 **0 像素漂移**。
3. **冷却倒计时去重核查（通过）**：
   - `runCooldownTicker`（[QuickReplyViewModel.kt](file:///home/mir/Documents/hipda-next.wt/E5/hipda/src/main/java/net/jejer/hipda/feature/compose/QuickReplyViewModel.kt#L328-L335)）保持 `collectLatest` 取消、`localCooldownJob` 互斥、向上取整与虚拟时钟推进语义 100% 不变。
4. **生产代码净增规模与无新增问题核查（通过）**：
   - 相对 `origin/main@9338529` 共修改/新增 6 个生产文件（`+1,572 / -17`），净增 **1,555 行**（低于 1.5× 警戒线 `1,575` 行，余量 20 行），全程标准 Compose 组件（0 `Layout` / `SubcomposeLayout` / `Modifier.Node`），无新引入问题，准予合并。
