# 验证报告 V-A6：独立视觉复验与探针验证可见验证面板及原地提示

## 1. 结论摘要

- **总判定**：**PASS**（满足产品规格、视觉基线、无障碍大字号与行为探针要求，无 `DISPATCH.md` §4 阻断合并项；同时记录 2 项非阻断视觉打磨建议供后续涉及 `ui/challenge/` 的卡片顺手优化）
- **被验对象**：`origin/card/A6-challenge-panel` @ `1fb3d464fd9083d7de6fcfc63f54f6d3371937e3`（只读未修改；基于 A5b `0e0da1b49f73306e9cab675ed48adfb380024b85`）
- **独立边界**：全程未阅读实现者测试与 `_reports/A6.md` 报告，仅依据 `A6.md` 范围节、`product-design.md` §4.1/§5/§6.3/§7.12/§8、`copy.md`、生产代码变更（`git diff 0e0da1b..1fb3d46 -- hipda/src/main/`）、逐张读图检查与独立探针结果作判定。
- **验证分支 / Commit**：`verify/A6-visual` @ `70db0279d30f04ae60906f3bf8e89fa10f0673eb`（worktree `/home/mir/Documents/hipda-next.wt/V-A6` 已保留）
- **探针文件**：`hipda/src/test/java/net/jejer/hipda/verify/A6ProbeTest.kt`
- **CI 证据**：https://github.com/mailwmj/hipda-next/actions/runs/45562986336
  - `Build, Lint & Unit Tests`（ID `139030897127`）：**SUCCESS**（9m43s，含 `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、全量单元测试与 Roborazzi verify）
  - `Summarize unit test results`：`TOTAL: 544 tests, 544 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.verify.A6ProbeTest`：`17 tests, 17 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengePanelScreenshotTest`：`7 tests, 7 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengePanelMotionTest`：`4 tests, 4 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeStatusAndPresenterTest`：`9 tests, 9 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeActivityTest`：`7 tests, 7 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeSolveStateMachineTest`：`15 tests, 15 passed, 0 failed, 0 errors, 0 skipped`
- **本地验证证据**：
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.A6ProbeTest" --tests "net.jejer.hipda.ui.challenge.*" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`：**66 tests, 66 passed, 0 failed**，Roborazzi verify、`MotionTokenScanTest`（7/7）、`assembleDebug`、`lintDebug` 全部通过。
  - 基线核实：`git diff --stat 0e0da1b..1fb3d46 -- hipda/src/test/snapshots/` 仅新增 7 张 `challenge_*.png`，既有基线零改动；`ui/designsystem/`、`data/network/`、共享构建文件及 `MotionTokenScanTest` 白名单零改动。

---

## 2. 逐张视觉检查（7 张基线截图 + 大字号 / 复杂背景临时图）

### 2.1 仓库 7 张 `challenge_*` 基线逐张结论

| 截图 | 像素尺寸 (dp @xhdpi) | 逐张视觉与规格核对结论 | 判定 |
|---|---|---|---|
| `challenge_panel_w411_light.png` | `822×1830` (`411×915dp`) | 宽度 `< 600dp` 呈底部面板；顶部双圆角 `20dp`，底边完全贴合底部（`y=545..1829`，`top=272.5dp`, `bottom=0dp`）；顶栏左 `安全验证` / `请完成页面上的验证以继续访问`，右 `刷新` / `取消` 间距均衡且均 ≥48dp；纯色 `surfaceContainerHigh` 表面（顶栏 `(242,251,255)` 实底，无毛玻璃层叠），背景 `40%` scrim 清晰可见且不穿帮；对比度高 | **PASS** |
| `challenge_panel_w411_dark.png` | `822×1830` (`411×915dp`) | 深色底部面板，`y=545..1829` 贴底、顶角 `20dp` 圆角清晰；顶栏 `安全验证`（白字）、副行（浅灰）及 `刷新`/`取消`（浅蓝）在深色实底 `(14,20,23)` 上对比度高，分割线清晰，Mock WebView 内容可读，无毛玻璃 | **PASS** |
| `challenge_dialog_w900_light.png` | `1800×1440` (`900×720dp`) | `≥ 600dp` 切居中对话框；实测像素外框 `x=420..1379`（精确宽 `480.0dp`，左右各 `210.0dp` 水平居中），`top=136.0dp` / `bottom=136.0dp` / 高 `448.0dp`（垂直居中）；四角 `20dp` 圆角与淡阴影清晰；纯白/浅蓝实底面板在灰色遮罩背景上对比突出，无毛玻璃 | **PASS** |
| `challenge_dialog_w900_dark.png` | `1800×1440` (`900×720dp`) | `≥ 600dp` 深色居中对话框，外框 `x=420..1379`（宽 `480.0dp`，左右各 `210.0dp`），顶/底各 `136.0dp`；深色表面（顶栏 `(36,43,46)`，主体 `(14,20,23)`）配淡阴影与 `40%` scrim，文字、按钮与分割线可读性优良 | **PASS** |
| `challenge_status_checking_w411_light.png` | `822×1830` (`411×915dp`) | 原地等待态居中显示环形进度圈 + 文案；逐字核对 `strings_challenge.xml:7` 确为 `正在检查安全环境…`（Unicode `U+2026` 省略号；与既有 `state_list_footer_w411_light.png` 中的 `正在加载…` 同款字体渲染为基线三点）；居中留白充足、清晰易读 | **PASS** |
| `challenge_status_incomplete_w411_light.png` | `822×1830` (`411×915dp`) | 复用 C5a `HiErrorState`：主文案 `安全验证未完成` 居中，下方 `16dp` 间距为 ≥48dp 高度圆角 `8dp` `FilledTonalButton` 按钮 `重新验证`；字色对比高、留白均衡 | **PASS** |
| `challenge_status_rejected_w411_light.png` | `822×1830` (`411×915dp`) | 复用 C5a `HiErrorState`：主文案 `验证未通过` + 按钮 `重新验证`，与 `copy.md` 逐字一致，排版、圆角与对比度正常 | **PASS** |

### 2.2 大字号 `fontScale = 2.0f` 与复杂底层背景目检（临时导出至 `/tmp/V-A6/`，未提交仓库）

- `/tmp/V-A6/panel_w411_light_font2x.png`（411dp 底部面板，`fontScale = 2.0f`）：`安全验证`（1 行）、`请完成页面上的验证以继续访问`（2 行换行：`请完成页面上的验证` / `以继续访问`，14/14 字完整）、`刷新`/`取消`（单行水平排列）均在窗口与面板边界内完整可见，无截断、无重叠。
- `/tmp/V-A6/dialog_w900_light_font2x.png`（900dp 对话框，`fontScale = 2.0f`）：宽 `480dp` 居中，副行 2 行完整（`请完成页面上的验证以继续` / `访问`，14/14 字完整），按钮无溢出或重叠。
- `/tmp/V-A6/panel_w411_step5_font2x.png`（额外压力：411dp + `HiFontSizeStep.STEP_5` 18sp + `fontScale = 2.0f`）：14/14 字仍完整显示（`ellipsized=false`）。
- `/tmp/V-A6/panel_w340_step3_font2x.png`（额外极端窄屏诊断：340dp + `fontScale = 2.0f`）：由于 `ChallengePanel.kt:212` 对副行固定了 `maxLines = 2, overflow = TextOverflow.Ellipsis` 且与两枚横向按钮同列，340dp 超窄分屏在 2.0 倍字体下副行被省略为 `请完成页面上` / `的验证以继...`（`ellipsized=true`）。
- `/tmp/V-A6/status_checking_w411_light_font2x.png`、`/tmp/V-A6/status_incomplete_w411_light_font2x.png`、`/tmp/V-A6/status_rejected_w411_light_font2x.png`（411dp 三态，`fontScale = 2.0f`）：三态文案与 `重新验证` 按钮均单行居中完整显示，无裁切、无重叠。
- `/tmp/V-A6/dialog_w900_dark_complex_bg.png`（900dp 深色对话框置于高对比米白底深蓝/深红密集文本干扰背景上）：`40%` scrim + 纯实底 `surfaceContainerHigh` 完全隔绝底层干扰，无透底混色或毛玻璃伪影，标题/副行/按钮与槽位文本对比清晰。

---

## 3. 独立探针明细（`A6ProbeTest.kt`）

| 探针 | 测试方法 (`A6ProbeTest.kt`) | 实测结果与观测指标 | 判定 |
|---|---|---|---|
| **a** | `probeA_externalCancellationDuringPresent_cleansUpAndAllowsSecondPresent` (`L111`)、`probeA2_immediateCancellationBeforeFirstFrame_doesNotHangOrLeak` (`L209`) | 外部取消进行中与首帧前即时取消均在超时前正确结束（`CancellationException`）；`ComposeView` 从 `rootContainer` 摘除无视图泄漏；`FLAG_NOT_TOUCHABLE \| FLAG_NOT_FOCUSABLE` 与 `webView.alpha=0f`/`isFocusable=false`/`isClickable=false` 恢复；取消后同宿主再次 `present()` 仍复用同一 `WebView` 正常弹窗并在通过后返回 `SolveResult.Cleared` | **PASS** |
| **b** | `probeB1_clickCancelButton_dismissesPanelAndReturnsCancelled` (`L247`)、`probeB2_systemBackPress_dismissesPanelAndReturnsCancelled` (`L288`)、`probeB3_clickRefreshButton_reloadsWithoutCallingLoadUrl` (`L331`) | 点击 `取消` 与系统返回键均先收起面板、`ComposeView` 移除、窗口不可交互标志恢复，并返回 `SolveResult.Cancelled`（系统返回被 `BackHandler` 拦截，未提前销毁 `Activity`）；点击 `刷新` 精确触发 `reload()` 且不调用 `loadUrl()` | **PASS** |
| **c** | `probeC_statusView_transitionsLazyCooldownUserExplicitAndGeometry` (`L388`)、`probeC2_statusView_reducedMotionStillEnforces2sBusinessDelay` (`L700`)、`probeC3_statusView_columnPlacementGeometryObservation` (`L737`) | `Idle` 空白；`Solving` `< 2000ms`（1900ms 内及提前切回 `Idle`）均不闪现；`durationScale=0f` 下 2s 业务门槛仍生效；`≥ 2000ms` 显示 `正在检查安全环境…`；`Cooldown(false)`/`Cooldown(true)` 分别显示 `安全验证未完成`/`验证未通过` 且点击 `重新验证` 均传出 `AccessPriority.UserExplicit`；到期后惰性归位 `Idle`。`Incomplete ↔ Rejected` 切换 0 空白帧、容器高度/纵坐标 0px 跳动；**另观测到打磨点**：居中容器下 `Checking → Incomplete` 首帧因 `AnimatedContent` 默认 `TopStart` + `.using(null)` 导致容器高度瞬时 `+64dp`（`143px→271px`）、渐出文本跳升 `32dp`（`892px→828px`） | **PASS**（含非阻断打磨记录） |
| **d** | `probeD1_fontScale2x_phone411_bottomPanelAndStatusViews_noTruncationNoOverlap` (`L553`)、`probeD2_fontScale2x_expanded900_dialog_noTruncationNoOverlap` (`L584`)、`probeD3_fontScale2x_statusThreeStates_noTruncationNoOverflow` (`L615`)、`probeD4_dialog900Dark_overComplexBrightTextBackground_contrastInspection` (`L662`)、`probeD5_...` (`L785`)、`probeD6_...` (`L826`) | `fontScale = 2.0f` 下 411dp 底部面板（含 `STEP_5`）、900dp 对话框（宽 `≤ 480dp`）及三态提示的标题、副行、`刷新`/`取消`/`重新验证` 均在窗口边界内 `100%` 排版完成（`ellipsized=false, didExceedMaxLines=false, didOverflowHeight=false`）、无两两矩形重叠，且按钮触控目标均 ≥ `48dp`（仅 `340dp` 超窄分屏下副行出现省略号） | **PASS** |
| **e** | `probeE_panelOverlay_interruptibleEnterToExit_andReducedMotionOneFrame` (`L863`)、`probeE1b_sheetOverlay_reducedMotionOneFrame` (`L915`)、`probeE2_dialogOverlay_reducedMotionOneFrame_andTouchTargets` (`L958`) | 411dp 底部面板入场中途转退场平滑连续反转（`y: 1760→1549→1313→1094→1075→1229→...→1828`）；`durationScale = 0f` 下 411dp 面板与 900dp 对话框均直接到位退场并准确回调 `onDismissed`，无中间插值帧 | **PASS** |

---

## 4. 非阻断问题与后续打磨建议（供下一张涉及 `ui/challenge/` 的卡顺手修复）

1. **`ChallengeStatusView` 在 `Checking ↔ Incomplete/Rejected` 切换时居中对齐与尺寸跳变**（`hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeStatusView.kt:144-149`）
   - **触发路径**：当 `ChallengeStatusView` 放在居中容器（如 `Box(fillMaxSize(), Alignment.Center)`）且从 `Checking`（单行高 `71.5dp / 143px`）切换至 `Cooldown`（`HiErrorState` 高 `135.5dp / 271px`）时，由于 `AnimatedContent` 使用默认 `contentAlignment = Alignment.TopStart` 且 `.using(null)` 禁用了 `SizeTransform`，第 1 帧容器高度瞬时由 `143px` 变为 `271px`（`top` 由 `844px` 跳至 `780px`），正在淡出的 `正在检查安全环境…` 同步向上跳变 `64px (32dp)`（`892px → 828px`）；放在 `Column` 中时下方兄弟组件在第 1 帧同步下跳 `64dp`（`143px → 271px`）。
   - **建议改法**：`AnimatedContent` 显式传入 `contentAlignment = Alignment.Center`，并将 `transitionSpec` 改为 `(hiFadeIn() togetherWith hiFadeOut()).using(SizeTransform(clip = false) { _, _ -> HiMotion.spatialDefault() })`（或保留无裁剪平滑尺寸变化）。

2. **340dp 超窄分屏 + `fontScale = 2.0f` 下面板副行截断**（`hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengePanel.kt:190-226`）
   - **触发路径**：窗口宽 `340dp`（`HiScreenshotTestSupport.Qualifiers.NARROW_340`）且系统 `fontScale = 2.0f` 时，左侧 `Column(weight(1f))` 仅余约 `152dp`，副行 `请完成页面上的验证以继续访问` 受 `maxLines = 2, overflow = TextOverflow.Ellipsis` 限制会截断为 `请完成页面上` / `的验证以继…`。
   - **建议改法**：将副行放宽至 `maxLines = 3`（或对顶栏 chrome 按钮/副行在 `< 360dp` 时允许按钮紧凑换行）。

3. **`ComposeChallengeInteractionPresenter` 退出清理后未将 `webView` 放回 `rootContainer`**（`hipda/src/main/java/net/jejer/hipda/ui/challenge/ComposeChallengeInteractionPresenter.kt:140-143`）
   - **现象**：`finally` 仅从 Compose `AndroidView` 容器摘除 `webView`（`webView.parent == null`），不再挂回 `rootContainer`。单次求解结束时状态机随即 `handle.close()` 销毁窗口无影响；探针 `a` 已确认同宿主二次 `present()` 仍可由 `AndroidView` 重新挂载复用。若未来同一 `ChallengeActivity` 会退回后台静默续跑，建议顺手将摘下的 `webView` 以 `SILENT_WEBVIEW_WIDTH_DP × SILENT_WEBVIEW_HEIGHT_DP` 挂回 `rootContainer`。
