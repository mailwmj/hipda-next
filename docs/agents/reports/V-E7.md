# V-E7 独立验证报告（E7 表情选择面板与键盘等高原地互换 + `origin/main@ed361c2` 合并态）

- **验证结论**：**FAIL（阻断合并）** — 在合并 `origin/main`（`ed361c2`）的独立 worktree 下，**24 项独立探针通过 21 项、阻断失败 3 项**。失败的 3 项全部精准命中同一生产缺陷（`FullEditorPlacement.CenteredModal` 在 `640×1024`、`749×559`、`933×696` 三种居中模态窗口下，`IME ↔ 表情` 互换首帧出现 **148.0dp–280.0dp** 工具栏底缘剧烈跳变，严重违背补充 `(a)` 底缘零位移 `≤ 1dp` 要求）。其余 `QuickReplySheet` / `FullEditorScreen`（全屏与右栏） / `SmsConversationScreen` 逐帧等高互换与减动效 `0ms`、§7.4 无键盘高度记忆限高（含首次/外接键盘/分屏）及跨宿主记忆复用、三栏右栏边界与 `≤3/≤2/≤5` 玻璃预算、矮屏 `749×559dp` 弹键盘表情按钮可达性、三处光标/选区无损插入与深浅切图 + `C1b#3` + 全层级返回栈优先级、`R-M7-G4 S2` 纯图气泡隐藏「复制」以及边界/Roborazzi 快照零意外漂移全部通过。
- **被测对象**：`origin/card/E7-emoji-panel` @ `0428dd4fc60de1fde4e86d74ed1e98dbc7154e45`（合并基线 `2739d4db5718f44d1889f3363ce8d0bddfc018ed`），在验证分支内合并最新 `origin/main` @ `ed361c255b975874fa0eb7a4a60f431400c2af55`（合并提交 `4a0b08e3b36953acc192742500f29fc5ef9c8d07`，无冲突；`ed361c2` 仅引入 `hipda/build.gradle.kts` 单测堆与 fork 上限）。
- **验证分支 / worktree / 提交**：`verify/E7-probes` @ `774f5cec055aee1b5ca90291b20e7bb226f030fa`（worktree `/home/mir/Documents/hipda-next.wt/V-E7` 保留，仅新增 [E7ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt)；生产代码、既有单测、既有快照与主仓 `/home/mir/Documents/hipda-next` 均 0 改动）。
- **实现方 CI**：[Android CI #45812485275](https://github.com/mailwmj/hipda-next/actions/runs/45812485275) — `Build, Lint & Unit Tests`（Job `139883620023`，`14m7s`）`success`（`Emulator Smoke Test` `skipped`）。
- **验证方 CI**：[Android CI #45817393937](https://github.com/mailwmj/hipda-next/actions/runs/45817393937)（`workflow_dispatch` on `verify/E7-probes` @ `774f5cec`）— `Build, Lint & Unit Tests`（Job `139900566696`，`13m59s`）`failure`：
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`：**TOTAL: 1609 tests, 1606 passed, 3 failed, 0 errors, 0 skipped**
    - `E7ProbeTest`：**24 tests, 21 passed, 3 failed, 0 errors, 0 skipped**（唯一 3 项失败均为下述 `B1`）
    - `EmojiPanelBehaviorTest`（6/6）、`EmojiPanelHeightPolicyTest`（6/6）、`EmojiPanelScreenshotTest`（11/11）、`QuickReplyScreenshotTest`（9/9）、`QuickReplySheetTest`（9/9）、`QuickReplyLayoutMatrixTest`（8/8）、`FullEditorScreenshotTest`（13/13）、`FullEditorIntegrationTest`（7/7）、`MessagesComposeAndCopyTest`（5/5）、`MessagesScreenshotTest`（12/12）、`MultiPaneGlassBudgetTest`（11/11）、`MultiPaneScreenshotTest`（21/21）、`CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）等其余全仓单测：**全通过**
  - `Check for Roborazzi diff images`：**PASS**（0 张差异图）

---

## 一、阻断项明细

### B1：完整编辑器在 `CenteredModal` 投放态下 `IME ↔ 表情` 互换工具栏底缘剧烈位移（`148dp–280dp`）

- **影响窗口**：所有启用 `FullEditorPlacement.CenteredModal` 的折叠屏/双栏/窄三栏尺寸，实测 3 组代表尺寸全部复现失败：
  1. `933×696dp`（三栏右栏 `< 560dp`）：[probe1b_bugcheck...centeredModal933dp](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L425) — **FAIL**：`[CenteredModal-933x696][Editor tapEmoji frame0 toolbar] 底缘发生位移！baseline=408.0dp, actual=148.0dp, |Δbottom|=260.0dp (> 1dp)`
  2. `749×559dp`（三栏右栏 `< 560dp`）：[probe1b_bugcheck...centeredModal749dp](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L436) — **FAIL**：`[CenteredModal-749x559][Editor tapEmoji frame0 toolbar] 底缘发生位移！baseline=281.0dp, actual=133.0dp, |Δbottom|=148.0dp (> 1dp)`（点击表情首帧外部 `imePadding(250dp)` 仍在，内部表情槽同时展开 `250dp`，双倍扣高将正文与底栏挤压上移 `148dp`）
  3. `640×1024dp`（双栏侧栏居中模态）：[probe1b_bugcheck...centeredModal640dp](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L447) — **FAIL**：`[CenteredModal-640x1024][Editor tapEmoji frame0 toolbar] 底缘发生位移！baseline=716.0dp, actual=436.0dp, |Δbottom|=280.0dp (> 1dp)`

- **代码根因**：
  1. [FullEditorHost.kt#L213-L220](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt#L213-L220) 将 `CenteredModal` 的外层 `Surface`（位于 `Alignment.Center` + `heightIn(max = 720.dp)`）包上了外部 `Modifier.imePadding()`，并向下传入 `isModalCard = true`；
  2. [FullEditorScreen.kt#L90](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt#L90) 因此设定 `consumeImeInSlot = !isModalCard`（即 `false`）；
  3. 当 `consumeImeInSlot == false` 时，[EmojiPanelHost.kt#L52-L54](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelHost.kt#L52-L54) 使 `bottomSlotHeightDp` 退化为 `if (mode == Emoji) panelContentHeightDp else 0.dp`，[switchToKeyboard()](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelHost.kt#L138-L145) 也跳过 `SwitchingToIme` 立即 `resetToClosed()`。结果：`IME → 表情` 首帧时外层 `imePadding()` 尚在而内部 `EmojiPanelSlotBox` 已展开，双倍减去键盘高度；`表情 → IME` 首帧时内部槽位立刻塌陷为 `0dp` 而外部 `IME` 尚未升起，底栏向下跌落；此外高屏 `640×1024dp` 因卡片受 `heightIn(max = 720.dp)` 垂直居中限制，在有/无 `imePadding()` 之间还会改变卡片自身居中纵坐标。
- **交付单测漏测原因**：实现方 [EmojiPanelBehaviorTest.kt#L194](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelBehaviorTest.kt#L194) 仅单独渲染默认 `isModalCard = false` 的 `FullEditorScreen(...)`，未通过 `NextAppRoot + FullEditorHost` 挂载 `CenteredModal` 容器，故未命中该路径。
- **修复建议**：将 `CenteredModal` 场景的底部让位与三态高度保持统一到模态框外层/容器协调（例如去掉卡片 `Surface` 上脱离控制器的裸 `.imePadding()`，由 `FullEditorHost`/`CenteredModal` 统一用单一底部避让通道消费 `maxOf(locked/handoff, liveIme)`，或在 `IME ↔ 表情` 交接全过程稳定保持卡片底缘锚点不随居中重新计算上下浮动），并补充真实 `FullEditorHost` 下的 `CenteredModal` `640dp`/`749dp`/`933dp` 逐帧回归测试。

---

## 二、24 项独立探针逐项实测结果

| 探针 | 用例与源码链接 | 结果 | 实测观测与验证要点 |
|---|---|---|---|
| **探针 1a + (a)** | `probe1a_nextAppRoot_quickReply_411dp_...` ([L274](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L274)) & `_749dp_` ([L280](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L280)) | **PASS（2/2）** | `411×891`（含 `24dp` 导航栏、`ime=280dp`）与 `749×559`（`ime=250dp`）真实 `NextAppRoot + QuickReplySheet`，`autoAdvance=false` 逐帧推进 `IME→表情`、`IME` 逐帧下降至 `0`、`表情→键盘` 与 `IME` 逐帧升起：`发送`、`插入图片`、输入栏三者底缘全帧 `|Δbottom| <= 1dp`；支持退场中途反转点「键盘」及点击输入框直接切回键盘。 |
| **探针 1b + (a)** | `probe1b_..._fullScreen411dp_` ([L403](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L403)) & `_rightPane1280dp_` ([L414](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L414)) | **PASS（2/2）** | `FullEditorPlacement.FullScreen`（`411×891`）与 `RightPane`（`1280×800`）在真实 `NextAppRoot + FullEditorHost` 下，`IME ↔ 表情` 全序列底栏与 `插入图片` 按钮底缘逐帧 `|Δbottom| <= 1dp`。 |
| **探针 1b 缺陷项** | `probe1b_bugcheck_..._centeredModal{933dp,749dp,640dp}_...` ([L425](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L425), [L436](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L436), [L447](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L447)) | **FAIL（3/3，B1）** | `933×696` 首帧跳变 `408dp → 148dp`（`260.0dp`）；`749×559` 首帧跳变 `281dp → 133dp`（`148.0dp`）；`640×1024` 首帧跳变 `716dp → 436dp`（`280.0dp`）。 |
| **探针 1c + (a)** | `probe1c_nextAppRoot_smsConversation_411dp_...` ([L558](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L558)) & `_749dp_` ([L564](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L564)) | **PASS（2/2）** | `M7-0 #11` 短消息输入栏（`411×891` 与 `749×559` 三栏右侧）下，左滑/切键盘 `IME ↔ 表情` 逐帧底栏与输入框底缘 `|Δbottom| <= 1dp`；`749×559` 下表情面板严格在右栏内不跨分栏线且玻璃总数 `<= 3`。 |
| **探针 1d** | `probe1d_reducedMotion_zeroScale_swapsInSingleFrame` ([L667](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L667)) | **PASS** | `withAnimatorDurationScale(0f)` 下打开面板、切换分类指示器 1 帧落定无拖尾动画。 |
| **探针 2a + (b)** | `probe2a_fallbackHeights_...` ([L717](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L717)) + 4 组 `probe2a_realUi_noImeMemory_*` ([L729](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L729)–[L747](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L747)) | **PASS（5/5）** | 覆盖首次打开（`411×891 → 280dp`）、分屏（`600×600 → 270dp`）、临界值（`891×479 → 167.65dp`）、矮屏横屏（`891×411 → 143.85dp`）及纯函数 `480dp → 216dp`；在三处真实宿主均精确吻合 §7.4（误差 `< 1.5dp`）、不溢出窗体，且点「键盘」无 IME 到来（模拟外接物理键盘）于 450ms 超时（实测 `600ms` 虚拟时钟推进）干净收敛回关闭态。 |
| **探针 2d + (b)** | `probe2d_crossHostSessionKeyboardMemoryReused_acrossAllThreeHosts` ([L839](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L839)) | **PASS** | 快速回复学得净高 `240dp`（`264dp` 总高含 `24dp` 导航栏）后，在无新 IME 下直接切换到 `FullEditor` 与 `SMS` 对话点「表情」，两处均跨宿主精确复用 `240+24=264dp`（非回退 `280dp`）；窗口高度分桶隔离另由 `EmojiPanelHeightPolicyTest.keyboardHeightTracker_preservesPeakAcrossImeHide_capturesOnSwitch_andIsolatesWindowBuckets` 覆盖通过。 |
| **探针 2b + (d)** | `probe2b_threePane{749dp,933dp}_...` ([L905](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L905), [L911](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L911)) & `_tablet1280dp_` ([L917](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L917)) | **PASS（3/3）** | `749×559`（8 列）、`933×696`（9 列）、`1280×800`（右栏 `680dp` → 14 列）自适应网格单元均 `≥ 48×48dp`，不跨左/中栏分隔线，不越右窗边界；表情面板内 `0` 个 `HiGlassMarker`，全屏总预算严守 `HiGlassMarker <= 3`、`LayerBackdrop <= 2`、嵌套 `0`、`drawBackdrop <= 5`。 |
| **探针 2c + (e)** | `probe2c_supplementE_749x559_imeUpWithDraftAndError_...` ([L1001](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1001)) | **PASS** | `749×559` 三栏预设草稿 + 发送失败错条态，依次叠高 `IME=250/320dp` 与 `IME=390dp+statusBar=24dp`（触发 E5 紧凑单行条降级让位）：「表情」按钮始终 `>= 48×48dp`、位于面板框内且在发送左侧完整可点，实点后顺利弹出面板并插入 `:)` 与互换切换。 |
| **探针 3a** | `probe3a_allThreeHosts_cursorSelectionInsert_..._c1b3` ([L1102](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1102)) | **PASS** | `EmojiMap` 3 组 `21+16+24=61` 表情资源完整且酷猴/呆呆深浅色切图不同；三处宿主均验证光标中间插入（`:)`、`{:2_41:}`、`{:3_57:}`）、选区替换、表情退格删除、浅↔深切换保留选中 Tab 与输入、以及 `C1b#3` Tab `contentDescription` 严格取自 `strings_emoji.xml`（`"默认表情"`/`"酷猴表情"`/`"呆呆表情"`），`0` 原始 `EmojiGroup.name`。 |
| **探针 3b + (c)** | `probe3b_backPriority_drawer_emoji_...` ([L1282](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1282)) | **PASS** | 749×559 完整 `NextAppRoot` 栈验证：`看图 > PositionStack` 不变；`240dp 抽屉 > EmojiPanel > QuickReply > PositionStack` 逐层返回；`LoginOverlay > EmojiPanel > FullEditor` 逐层返回；点把手/返回关闭快速回复或编辑器时均重置表情展开态（重开不残留）；短消息 `240dp 抽屉 > EmojiPanel > SmsConversation` 逐层返回。 |
| **探针 3c（R-M7-G4 S2）** | `probe3c_rM7G4S2_smsImageOnlyBubbleHidesCopyMenu_...` ([L1452](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1452)) | **PASS** | 纯图片短消息气泡无长按 `OnLongClick` 语义动作（隐藏「复制」），同对话内纯文本气泡长按正常弹出「复制」。 |
| **探针 4** | `probe4_emojiPickerSourceDoesNotReadGroupName_...` ([L1517](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1517)) + Git/PNG 审查 | **PASS** | `EmojiPickerPanel.kt` 非注释代码 0 `.name` 访问且消费 `R.string.emoji_group_*`；`CopyTerminologyScanTest`（3/3）与 `MotionTokenScanTest`（7/7）全绿。 |

---

## 三、边界、体量与快照零漂移核查

1. **禁改路径核查（`git diff --name-only origin/main...HEAD`）**：
   `feature/routing/*`、`NextAppRoot.kt`、`PostBodyRenderer.kt`、`emoji/*`（`:emoji` 模块）、`hipda/src/main/java/net/jejer/hipda/data/*`、`hipda/src/debug/*`、`hipda/src/main/AndroidManifest.xml`、`gradle/*`、`build.gradle.kts`、`settings.gradle.kts`、`.github/*` **均为空（0 改动）**。
2. **生产代码体量（`git diff --numstat 2739d4d...0428dd4 -- 'hipda/src/main/*'`）**：
   10 个生产文件共 `+904 / -159`（净增 `+745` 行；仅 `di/ComposeEmojiModule.kt`、`feature/compose/emoji/*`、`strings_emoji.xml`、`QuickReplySheet.kt`、`FullEditorScreen.kt`、`MessagesPaneRendererImpl.kt`、`SmsConversationScreen.kt`）。
3. **Roborazzi 快照像素级与列表核查（`git diff --name-status origin/main...HEAD -- '*.png'` + Pillow RGB diff）**：
   - 新增 **11 张** `emoji_panel_*.png`，更新预授权 **24 张**（`editor_*.png` 12 张，RGB 变化 `0.02%–0.05%` 仅限底栏 38×38px 表情图标位；`quick_reply_*.png` 8 张，非紧凑态变化 `0.03%–0.05%`、891×411 紧凑条变化 `0.60%`；`messages_*.png` 4 张，变化 `0.14%–0.61%` 仅限底部输入栏）；
   - 全仓其余所有既有快照 **0 修改、0 删除**，且 CI `#45817393937` 全量 Roborazzi 校验 `Check for Roborazzi diff images` 为 `0 diff` 通过。

---

# V-E7 第 2 轮复验报告（`e25e057` + `origin/main@846ff3a` 合并态）

- **结论**：**PASS（建议合入）** — 第 1 轮阻断 `B1`（`CenteredModal` 在 `640×1024` / `749×559` / `933×696` 下 `IME ↔ 表情` 底缘位移）已彻底闭合；原始 **24 项**探针与第 2 轮新增 **`(f)` / `(g)` / `(h)` 共 5 项**（合计 **29/29 项**）及全仓 **1643 项**单测、Roborazzi 截图校验、Lint、Room Schema 与 APK 打包全部通过，既有非授权快照 **0 漂移**。
- **复验对象**：`origin/card/E7-emoji-panel` @ `e25e057de6fecd534b7aaef9f5bb8fbf781363d3`（含 `14c7e21` `R-E7 B1/B2/S1` 修复、`3e0a462` 与 `E6b` 主干合并），在验证分支合并最新 `origin/main`（`846ff3aa86f921499ec8b6315ee4af94ef4ac97b`，含 `E6b` 与 `M7-1`；合入提交 `8153db47435c2d291adb325913c3efae3ccf9a66`）。
  - *注*：CI 运行期间 `origin/main` 于 `20:29` 推进至 `4c7f6a0564620a4f1a85324ed10319e4a75a9d64`（`E6b-followup` + `M7-2` 删除旧代码）；经只读 `git merge-tree --write-tree` 实测零冲突（`08f9e75a`）、与 `E7` 改动文件交集为 `0`，且 `M7-2` 删除的 179 个 `strings*.xml` 键与 `E7` 生产/测试代码 `0` 引用冲突。
- **验证分支 / worktree / 提交**：`verify/E7-probes` @ `62bc06b1444222afc15e3f577608e05db30971a7`（`/home/mir/Documents/hipda-next.wt/V-E7` 保留，生产代码、既有单测、既有快照与主仓 `/home/mir/Documents/hipda-next` 均 0 改动）。
- **实现方 CI（第 2 轮）**：[Android CI #45823947592](https://github.com/mailwmj/hipda-next/actions/runs/45823947592) — `Build, Lint & Unit Tests`（Job `139923117610`，`15m45s`）`success`（`TOTAL: 1606 tests, 1606 passed, 0 failed, 0 errors, 0 skipped`；`Emulator Smoke Test` `skipped`）。
- **验证方 CI（第 2 轮）**：[Android CI #45828363977](https://github.com/mailwmj/hipda-next/actions/runs/45828363977)（`workflow_dispatch` on `verify/E7-probes` @ `62bc06b1`）— `Build, Lint & Unit Tests`（Job `139938327772`，`16m20s`）**`success`**：
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）：**PASS**
  - `Verify Room schema consistency`：**PASS**
  - `Run unit tests and verify Roborazzi screenshots`：**TOTAL: 1643 tests, 1643 passed, 0 failed, 0 errors, 0 skipped**
    - [E7ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt)：**29 tests, 29 passed, 0 failed, 0 errors, 0 skipped**
    - `EmojiPanelBehaviorTest`（15/15）、`EmojiPanelHeightPolicyTest`（7/7）、`EmojiPanelScreenshotTest`（11/11）、`QuickReplyScreenshotTest`（9/9）、`QuickReplySheetTest`（9/9）、`QuickReplyLayoutMatrixTest`（8/8）、`FullEditorScreenshotTest`（13/13）、`FullEditorIntegrationTest`（7/7）、`EditAndDeletePostScreenshotTest`（4/4）、`EditAndDeletePostIntegrationTest`（4/4）、`MessagesComposeAndCopyTest`（5/5）、`MessagesScreenshotTest`（12/12）、`MultiPaneGlassBudgetTest`（11/11）、`MainActivityEntryAndManifestTest`（6/6）、`CopyTerminologyScanTest`（3/3）、`MotionTokenScanTest`（7/7）等全仓用例：**全通过**
  - `Verify APK outputs`：**PASS**（已生成并上传 `debug-apk`、`release-apk`）

---

## 四、第 2 轮逐项复验明细（29/29 PASS）

### 4.1 第 1 轮阻断 `B1` 修复核查与原 24 项重跑结果

1. **修复根因闭合验证**：
   - [FullEditorHost.kt#L250-L258](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorHost.kt#L250-L258) 移除了 `CenteredModal` 外层容器上的 `.imePadding()` / `.heightIn(max = 720.dp)`；
   - [FullEditorScreen.kt#L168-L192](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/FullEditorScreen.kt#L168-L192) 将 `CenteredModal` 卡片放入 `Column(Modifier.fillMaxSize())` 上方 `weight(1f)` 区域并在槽位展开态锚定 `Alignment.BottomCenter`（底部归零对齐），使 [EmojiPanelSlotBox](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelHost.kt#L146-L161) 成为全部 3 处宿主唯一 IME 消费点。
2. **重跑原 24 项（重点 3 项居中模态全部 PASS）**：
   - `probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal933dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement`（[L425](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L425)）：**PASS**（第 1 轮跳变 `260.0dp` → 本轮 `IME↔表情` 含首帧全程 `|Δbottom| <= 1dp`）
   - `probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal749dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement`（[L436](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L436)）：**PASS**（第 1 轮跳变 `148.0dp` → 本轮全程 `|Δbottom| <= 1dp`）
   - `probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal640dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement`（[L447](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L447)）：**PASS**（第 1 轮跳变 `280.0dp` → 本轮 `24dp` 导航栏 + `280dp` 键盘全程 `|Δbottom| <= 1dp`）
   - 其余原 21 项（`probe1a` `411/749`、`probe1b` `411/1280`、`probe1c` `411/749`、`probe1d` 减动效、`probe2a` 5 组 §7.4 与外接键盘、`probe2d` 跨宿主记忆、`probe2b` 三栏 `749/933/1280` 玻璃预算 `<=3/<=2/<=5`、`probe2c` 矮屏 `749×559` 按钮可达、`probe3a` 光标插入/深浅切图/`C1b#3`（更新为整枚回删预期）、`probe3b` 返回栈、`probe3c` 纯图藏复制、`probe4` 扫描与 `C1b#3`）：**21/21 全 PASS**。

### 4.2 第 2 轮新增补充 `(f)` / `(g)` / `(h)`（5/5 PASS）

| 补充项 | 用例名与位置 | 结果 | 实测观测 |
|---|---|---|---|
| **补充 (f) 缓存无导航栏污染** | `probe2e_supplementF_baselineQuickReplyAfterIme_noCenteredModalOpened`（[L1080](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1080)）& `probe2e_supplementF_centeredModalOpenEmojiThenQuickReply_zeroNavBarCachePollution`（[L1127](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1127)） | **PASS（2/2）** | 基于 `EmojiInsetsFrame`（[EmojiPanelHost.kt#L25-L39](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPanelHost.kt#L25-L39)）统一采样 `IME - navigationBars`：`640×1024dp`（`navBar=24dp`）下，未开过居中模态的快速回复基线为 `264dp total / 240dp content`；先在 `CenteredModal` 无 IME 打开表情再切快速回复仍保持 §7.4 `280dp`；先在 `CenteredModal` 弹起 `IME=264dp`（含 `24dp` 导航栏）打开表情（`CenteredModal` 占位 `264dp`）并关闭模态后，再在 `IME=0` 打开快速回复表情面板，实测 `264dp total / 240dp content`（误差 `<= 1.5dp`），与未开过模态基线完全一致（未被污染成 `288dp`）。 |
| **补充 (g) E6b 编辑楼层模式** | `probe1e_supplementG_e6bEditPostMode_phone411dp_frameByFrameSwap_wholeBackspaceAndSubmit`（[L714](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L714)）& `_centeredModal933dp_`（[L725](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L725)） | **PASS（2/2）** | 经真实 `NextAppRoot + ThreadDetailPane` 长按本人 1 楼点「编辑」预取原帖 `"edit前{:2_41:}:)后"`：`411×891` 全屏与 `933×696` 居中模态在 `EditPost` 模式下 `IME ↔ 表情` 全帧 `|Δbottom| <= 1dp`；点击「删除」两次整枚删除 `:)` 与 `{:2_41:}` 得 `"edit前后"`，插入 `{:3_57:}` 后「还原原文」正常显示，点击「发送」提交 `PostSubmitRequest.EditPost(content = "edit前{:3_57:}后")`。 |
| **补充 (h) 面板整枚删除** | `probe3d_supplementH_emojiPanelBackspaceDeletesWholeEmojiTokens_monkeyAndSmiley_allThreeHosts`（[L1817](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/test/java/net/jejer/hipda/verify/E7ProbeTest.kt#L1817)） | **PASS** | 经 [previousEmojiOrGraphemeOffset](file:///home/mir/Documents/hipda-next.wt/V-E7/hipda/src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPickerPanel.kt#L71-L79)，在快速回复、完整编辑器、短消息三处实测：`A{:2_41:}:)B`（光标 `11`）连按删除依次整枚删 `:)` → `A{:2_41:}B`（`9`）再整枚删 `{:2_41:}` → `AB`（`1`）再删 `A`；逆序 `A:){:2_41:}B` 依次整枚删 `{:2_41:}` → `A:)B` 再删 `:)` → `AB`；普通右花括号 `测}试` 仅删 `}` → `测试`。 |

---

## 五、第 2 轮边界、体量与快照复核（`origin/main@846ff3a ... e25e057`）

- **禁改路径核查**：`feature/routing/*`、`NextAppRoot.kt`、`MainActivity.kt`、`PostBodyRenderer.kt`、`emoji/*`、`hipda/src/main/java/net/jejer/hipda/data/*`、`src/debug/*`、`AndroidManifest.xml`、`gradle/*`、`build.gradle.kts`、`settings.gradle.kts`、`.github/*` **0 改动**。
- **生产代码体量（相对 `origin/main@846ff3a`）**：12 个生产文件 `+899 / -171`（**净增 `+728` 行**，较第 1 轮 `+745` 更精简）。
- **快照**：第 2 轮 `0428dd4..e25e057` 未再新增或修改 E7 快照（`origin/main...e25e057` 仅含第 1 轮 24 张授权更新 + 11 张新增 `emoji_panel_*.png`，E6b 的 4 张 `editor_edit_*.png` 来自 `b803846` 已含于 `origin/main`）；CI `#45828363977` 全量 Roborazzi 校验全部通过（0 diff）。
