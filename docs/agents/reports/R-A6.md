# 独立代码审查报告 R-A6：可见验证面板与原地等待/冷却提示组件

- **审查对象**：`origin/card/A6-challenge-panel` @ `1fb3d464fd9083d7de6fcfc63f54f6d3371937e3`（基线 `card/A5b-challenge-host` @ `0e0da1b49f73306e9cab675ed48adfb380024b85`，仅审查 A6 自身增量 `git diff 0e0da1b..1fb3d46`：共 16 个文件，`+1774 / -7`，其中新增生产代码 635 行、测试代码 1,133 行、Roborazzi 截图基线 7 张）
- **审查结论**：**APPROVE**（无阻断项；附 5 条非阻断改进建议，可带入后续触及同一区域的 B1b / E 线页面卡）
- **CI 核验**：[GitHub Actions Run 45558716421](https://github.com/mailwmj/hipda-next/actions/runs/45558716421) — `Build, Lint & Unit Tests`（Job `139016942958`，9m6s）全绿通过，覆盖 `assembleDebug`、`assembleRelease`、`lintDebug`、Room Schema 校验、527/527 单元测试与 Roborazzi 截图校验。
- **边界核验**：`git diff --stat 0e0da1b..1fb3d46` 严格限定在 `hipda/src/main/java/net/jejer/hipda/ui/challenge/`、`hipda/src/main/res/values/strings_challenge.xml`、`hipda/src/test/java/net/jejer/hipda/ui/challenge/` 及 7 张新增 `challenge_*` 截图基线；`ui/designsystem/`、`data/network/`、`MotionTokenScanTest` 白名单、共享构建/Manifest 文件及既有 `glass_*`/`theme_*`/`state_*`/`feedback_*`/`shell_*` 截图基线**零改动**。

---

## 一、阻断项核查（依 `DISPATCH.md` §4）

**无阻断项（0 项）。**

对照 `DISPATCH.md` §4 逐条核实：
- CI 编译、Lint、单测与 Roborazzi 截图验证全部通过；
- Cloudflare 可见交互过盾通道闭环完整，严格复用同一运行中 `WebView` 实例，未触发二次 `loadUrl`，Hilt 单例绑定已由 `DefaultChallengeInteractionPresenter` 切换至 `ComposeChallengeInteractionPresenter`；
- 旋转、折叠、分屏跨 `600dp` 断点及宿主销毁场景下无崩溃、无永久挂起、无 `ComposeView` / `WebView` / 窗口标志泄漏；
- 无越界修改共享文件、设计系统或既有截图基线，无真实站点访问。

---

## 二、六项审查重点逐项核查

### 1. `ComposeChallengeInteractionPresenter.present()` 挂起与取消语义

- **代码位置**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ComposeChallengeInteractionPresenter.kt:43-162`
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengePanel.kt:85-100`
- **核查结论：通过（安全收口，无永久挂起或视图/标志泄漏）**
  1. **点击 `取消` 与按系统返回键**：`ChallengePanelOverlay` 在 `visible == true` 时启用 `BackHandler(enabled = visible, onBack = onCancel)`（`ChallengePanel.kt:93`），与顶栏 `取消` 按钮统一汇入 `finishWith(SolveResult.Cancelled)`（`ComposeChallengeInteractionPresenter.kt:61-68, 87`）。`resultDeferred.complete(result)` 保证幂等单次生效，将 `panelVisible.value` 置为 `false` 驱动退场弹簧；退场完成后 `onDismissed` 唤醒 `dismissedDeferred`，`present()` 返回 `SolveResult.Cancelled`。
  2. **验证自动通过**：后台 `pollJob` 每 `500ms` 调 `safeCheckClearance(session)`（`ComposeChallengeInteractionPresenter.kt:117-125, 149-156`），检出新 `cf_clearance` 后调 `finishWith(SolveResult.Cleared)` 自动收起面板并返回 `SolveResult.Cleared`；进入 `present()` 挂载 UI 前亦先做一次快速检查（`L46-48`），避免刚过盾时闪现一帧面板。且未竞争消费 `session.webViewHandle.events`（`Channel.receiveAsFlow()` 单消费者通道仍归 `ChallengeSolveStateMachine` 独占监听终止事件）。
  3. **外部协程取消（如网关 120 秒总超时、`UserExplicit` 抢占）**：`resultDeferred.await()` 或 `safeCheckClearance()` 响应取消抛出 `CancellationException`（`L152-153` 显式重抛 `CancellationException`，不吞取消），进入 `finally` 的 `withContext(NonCancellable + Dispatchers.Main.immediate)`（`L136-144`）：依次执行 `binding.setInteractiveMode(false)`（恢复 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` 与 `WebView` 不可交互态）、`composeView.disposeComposition()`、从 `rootContainer` 移除 `composeView`、从 `AndroidView` 容器摘除 `webView`，随后按协程规范向上传播 `CancellationException`。`@Singleton` 实例本身无任何可变成员字段，跨轮次调用无状态残留。
  4. **`onDismissed` 极端未触发兜底**：`ComposeChallengeInteractionPresenter.kt:130-132` 使用 `withTimeoutOrNull(dismissTimeoutMs)`（默认 `1_000L`）包裹 `dismissedDeferred.await()`。即便用户点 `取消` 的瞬间按 Home 切后台导致 `WindowRecomposer` 帧时钟暂停、`transition` 未走到完成帧，最多等待 `1s` 即强制进入 `finally` 拆除并返回，**绝不会永久挂起**。
  5. **进程内配置变化（旋转 / 折叠 / 窗口跨 `600dp` 断点）**：
     - `ChallengePanelOverlay`（`ChallengePanel.kt:85, 152-181`）对 `< 600dp` 底部面板与 `≥ 600dp` 居中对话框共用**同一个** `transition.AnimatedVisibility` -> `Surface` -> `Column` -> `Box { webViewSlot() }` 组合槽位（仅切换 `containerModifier`、`shape` 与 `EnterTransition`/`ExitTransition`）。窗口宽度跨过 `600dp` 时 `AndroidView` 保持同一组合节点原地重组，不触发卸载或重跑 `factory`，`WebView` 内的验证进度完全保留。
     - 配合 A5b 修复轮在 `AndroidManifest.xml` 中为 `ChallengeActivity` 补齐 `density|uiMode|keyboard|navigation|fontScale|locale|layoutDirection` 后，折叠屏内外屏不等 DPI 展开/合上及深浅色切换均在进程内原地平滑自适应。

### 2. 与 `ChallengeInteractionSession` / `ChallengeActivityBinding` 的生命周期绑定及 A5b 修复轮兼容性

- **代码位置**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ComposeChallengeInteractionPresenter.kt:44, 51-54, 90-103, 136-144`
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeInteractionPresenter.kt:57-77`
- **核查结论：通过（同一 `WebView` 零重载移入/拆除顺序安全；与 A5b 修复轮零冲突）**
  1. **严格复用同一隐藏 `WebView`**：`AndroidView.factory`（`ComposeChallengeInteractionPresenter.kt:91-100`）直接取 `binding.webView`，在同一主线程帧内执行 `(webView.parent as? ViewGroup)?.removeView(webView)`（从 `ChallengeActivity.rootContainer` 的 `360×480dp` 透明承载位摘下）并交给 `AndroidView` 挂载为 `MATCH_PARENT`，同时调用 `binding.setInteractiveMode(true)` 同步清除窗口 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`、将 `alpha` 设为 `1f` 并开启焦点与点击。全程不新建 `WebView`、不调用 `loadUrl`。
  2. **退场拆除顺序**：`finally` 中先恢复非交互窗口标志（`setInteractiveMode(false)`），再 `disposeComposition()` 释放 `AndroidViewHolder`，再移除 `composeView`，最后确保 `webView` 从 `AndroidViewHolder` 摘除（`L137-143`），之后交由 `ChallengeSolveStateMachine` 的 `finally` 执行 `handle.close()` / `destroyWebViewSafely()`，无父容器残留引用或附着态 `destroy()` 问题。
  3. **Activity 意外 `finish()` / `onDestroy()` 收口**：
     - 进入 `present()` 时若 `activity.isFinishing || activity.isDestroyed` 立即返回 `SolveResult.Cancelled`（`L52-54`）。
     - 挂载期间若 `ChallengeActivity.onDestroy()` 触发，`detachActiveSession(emitHostDestroyed = true)` 发送 `HostDestroyed`，`ChallengeSolveStateMachine.runInteractivePhase` 的 `terminalWatcher` 立即完成并取消 `presenterJob`，驱动 `ComposeChallengeInteractionPresenter` 走 `NonCancellable` `finally` 收口（`V-A5b` 的 `probeF` 已在接入 120s 网关下实测 `ActivityScenario.recreate()` 3 秒内干净收口）。
     - **可改进点（见建议 S1）**：`ComposeChallengeInteractionPresenter` 目前依赖外层 `ChallengeSolveStateMachine` 监听 `HostDestroyed` 来取消自己，自身未注册 `activity.lifecycle` 的 `ON_DESTROY` 观察者；若独立调用 `present(session)` 而外层未监听 `HostDestroyed`，宿主销毁后 `pollJob` 会空转轮询。补一行 `DisposableEffect` / `LifecycleEventObserver` 即可完全自洽。
  4. **与 A5b 进行中修复轮的耦合核查**：
     - 经核对 A5b 修复轮改动的 6 个文件（`AndroidManifest.xml`、`ChallengeActivity.kt`、`ChallengeSolveStateMachine.kt`、`ChallengeWebViewContract.kt` 及对应 2 个测试文件），与 A6 改动的 16 个文件（连同 `ChallengeActivityTest.kt:407` 修改位置）**无任何合并冲突**；
     - A5b 修复轮新增的 `ChallengeWebViewHandle.closeAndAwait()` 带有默认实现 `close()`，兼容 `ChallengeStatusAndPresenterTest.kt:542` 的 `TrackingWebViewHandle` 委托；
     - A5b 修复轮在 `ChallengeSolveStateMachine.runInteractivePhase` 中增加了 `syncedInPresenter` 标记，刚好消除 `ComposeChallengeInteractionPresenter` 调 `session.checkAndSyncClearance()` 返回 `Cleared` 后的重复 `syncFromWebView` 调用。

### 3. 文案与产品规则对齐

- **代码位置**：
  - `hipda/src/main/res/values/strings_challenge.xml:1-11`
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeStatusView.kt:46-171`
- **核查结论：通过（逐字一致、业务规则全部落实）**
  1. **`strings_challenge.xml` 逐字对齐 `docs/design/copy.md`**：8 条字符串（`安全验证`、`请完成页面上的验证以继续访问`、`刷新`、`取消`、`正在检查安全环境…`［U+2026 省略号］、`安全验证未完成`、`验证未通过`、`重新验证`）与 `copy.md` 第 59、89–92 行逐字、逐标点完全一致，无感叹号、无自造词。
  2. **2 秒等待门槛且不受减少动态效果影响**：`ChallengeStatusView.kt:96-115, 129-133` 基于注入时钟与 `LaunchedEffect(state)` 的协程 `delay(2_000L - elapsed)` 控制显隐，不走动画缩放通道；`MotionDurationScale = 0f` 下仍严格满 2000ms 后才显示，2000ms 内回到 `Idle` 绝不闪现，挂载时已超 2000ms 则首帧直出。
  3. **`Cooldown` 惰性归位**：提供纯函数 `ChallengeState.effectiveAt(nowMs)`（`L68-69`），并在组件内同时判定 `nowMs < state.untilMs` 与 `LaunchedEffect(state)` 的 `delay(state.untilMs - currentClock())`（`L102-104, 116-122, 134-140`）：挂载时已过期则立即归位为 `Idle`（或回退至页面显式持有的 `failure` 错误态，不误报过期冷却），未到期则到点自动消退。
  4. **“重新验证”固定传出 `AccessPriority.UserExplicit`**：`Incomplete` 与 `Rejected` 两态按钮回调均硬编码调用 `onRetry(AccessPriority.UserExplicit)`（`L157, 165`），并提供 `Throwable.toChallengeFailureKind()`（`L61-65`）方便业务页直接映射网关异常。

### 4. 组件规范、动效令牌与模块边界

- **核查结论：通过**
  - **纯标准组件**：仅使用 `Box`、`Column`、`Row`、`Surface`、`Text`、`TextButton`、`HorizontalDivider`、`updateTransition`、`AnimatedVisibility`、`AnimatedContent`、`Canvas`、`BackHandler`、`AndroidView`；零手写 `Layout`、零手写 `Modifier.Node`、零自定义命令队列；`src/main` 零测试专用语义键、钩子或 `CompositionLocal`。
  - **动效令牌 100% 合规**：底部面板升降 `HiMotion.spatialDefault()` / `HiMotion.exit(...)`、对话框缩放淡入 `HiMotion.spatialFast()`（`0.9f`）+ `HiMotion.effectsFast()` 及对应 `HiMotion.exit(...)`、遮罩 `HiMotion.effectsSlow()` / `HiMotion.exit(...)`、原地状态切换 `hiFadeIn()` / `hiFadeOut()`、进度圈 `HiMotion.spinnerRepeatable()`（`HiTheme.isReducedMotion` 下静态固定 `-90f`），`MotionTokenScanTest` 白名单保持为空且扫描全过。
  - **设计系统复用与视觉**：直接复用 C5a `HiErrorState`/`HiStateAction`、C5b `Modifier.hiPressScale`、`HiShapeTokens.Large`（20dp）/`HiShapeTokens.Small`（8dp），采用 `surfaceContainerHigh` 纯色表面与淡阴影，零毛玻璃（截图测试已用 `assertGlassSurfaceCountAtMost(0)` 守住）。
  - **7 张新增截图基线核验**：目视检查全部 7 张 PNG（411dp 底部面板浅/深色、900dp 居中对话框浅/深色、411dp 原地状态三态浅色），排版居中规整、圆角与层级清晰、无裁切或重叠。

### 5. 无障碍（a11y）

- **核查结论：基本通过（触控目标、按钮角色、返回拦截与跨窗口焦点隔离达标；语义播报细节可进一步增强，见建议 S2）**
  - **触控目标 ≥ 48dp**：`ChallengePanelActionButton`（`刷新`、`取消`，`ChallengePanel.kt:241`）显式声明 `defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` 且已在截图测试中对 `touchBoundsInRoot` 做像素级断言；`重新验证` 复用 `HiErrorState` 内 `FilledTonalButton`（`minWidth = 88.dp, minHeight = 48.dp`）。三者均具备标准 `Role.Button` 与清晰动词标签。
  - **焦点隔离与系统返回**：`ChallengeActivity` 切入交互态后作为栈顶独立焦点窗口自动将 TalkBack 触摸探索隔离在本窗口内（不会穿透到底层页面），`WebView` 恢复 `IMPORTANT_FOR_ACCESSIBILITY_AUTO`，`BackHandler` 正确承接系统返回手势。
  - **可改进点（建议 S2）**：由于未采用 `ModalBottomSheet`/`AlertDialog`（为规避其内置不可控弹簧），自定义 `Surface` 缺少 `semantics { paneTitle = ...; isTraversalGroup = true }`，标题 `安全验证` 缺少 `heading()`，`ChallengeCheckingIndicator`（2 秒后切换出的 `正在检查安全环境…`）缺少 `semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }`，补齐后 TalkBack 在面板弹出与原地切换等待提示时可自动播报。

### 6. 复杂度与测试质量评估（含“改坏一行测试仍通过”反例）

- **代码复杂度（按 `DISPATCH.md` §5）**：新增生产代码共 635 行（3 个 Kotlin 文件 + 1 个字符串 XML），职责切分清晰、无过度抽象。可顺手简化的微小冗余仅两处（见建议 S4）：
  1. `ChallengeStatusView.kt:68` 定义了 `ChallengeState.effectiveAt(nowMs)`，但 `ChallengeStatusView` 内部第 128–142 行未直接复用它，导致 `Cooldown` 过期分支与 `Idle` 分支重复书写 `failure.toVisualState()`；
  2. `ChallengeStatusView.kt:198-216` 的 `ChallengeCheckingSpinner` 与 `HiStateViews.kt:223-241` 的 `private fun HiLoadingSpinner` 逻辑几乎相同（受限于本卡禁止改 `ui/designsystem/` 而本地复制了 20 行，后续可在触及 `HiStateViews.kt` 时公开复用）。
- **测试覆盖与“改坏关键一行生产代码，现有测试仍 100% 通过”的具体反例**：
  现有 20 条测试覆盖了 2000ms 等待门槛、减少动态效果、冷却惰性归位、`UserExplicit` 回调、`WebView` 同一实例复用零重载、`刷新`/`取消`/系统返回/自动通过、逐帧连续性与中途打断、以及 7 张截图，但**缺少对 `ComposeChallengeInteractionPresenter` 外部协程取消、退场恢复窗口标志、`onDismissed` 超时兜底以及跨 `600dp` 动态切换的直接断言**：
  1. **反例 M1（删掉 `NonCancellable` 导致取消时泄漏视图与窗口标志）**：将 `ComposeChallengeInteractionPresenter.kt:136` 的 `withContext(NonCancellable + Dispatchers.Main.immediate)` 改坏为 `withContext(Dispatchers.Main.immediate)`。当网关 120s 超时或 `UserExplicit` 抢占取消 `present()` 时，`finally` 清理块在挂起点直接抛 `CancellationException` 跳过执行，泄漏 `ComposeView` 与窗口可交互标志；因现有测试均走正常返回而未测外部 `cancel()`，**全部测试仍 100% 通过**。
  2. **反例 M2（改坏退场窗口标志复位）**：删掉 `ComposeChallengeInteractionPresenter.kt:137` 的 `binding.setInteractiveMode(false)`（或误写为 `true`）。面板收起后 `ChallengeActivity` 窗口仍保持可触摸/抢焦点，拦截底层页面输入直至 Activity 彻底销毁；因 `ChallengeStatusAndPresenterTest` 仅在面板打开时断言了窗口标志、未在 `present()` 返回后断言 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` 已恢复，**全部测试仍 100% 通过**。
  3. **反例 M3（去掉 `onDismissed` 1 秒兜底导致帧时钟暂停时永久挂起）**：将 `ComposeChallengeInteractionPresenter.kt:130` 的 `withTimeoutOrNull(dismissTimeoutMs)` 改坏为无超时等待 `dismissedDeferred.await()`。真机上点 `取消` 同时按 Home 使帧时钟停摆时 `present()` 将挂死至 120s 网关超时；因单测始终推帧完成动画，**全部测试仍 100% 通过**。
  4. **反例 M4（吞掉首次检查时的协程取消）**：删掉 `ComposeChallengeInteractionPresenter.kt:152-153` 的 `catch (e: CancellationException) { throw e }`，使 `catch (_: Exception)` 吞掉取消信号继续挂载可见面板；因无取消时机测试，**全部测试仍 100% 通过**。

---

## 三、非阻断改进建议（供后续触及该区域的卡片跟进）

1. **S1（Presenter 自洽感知宿主销毁）**：在 `ComposeChallengeInteractionPresenter.kt:51` 注册 `activity.lifecycle` 的 `ON_DESTROY` 观察者调 `finishWith(SolveResult.Cancelled)`，无需依赖外层状态机的 `HostDestroyed` 取消即可独立安全收口。
2. **S2（补齐 TalkBack 面板与进度播报语义）**：在 `ChallengePanel.kt:158, 201` 为面板 `Surface` 增加 `semantics { paneTitle = title; isTraversalGroup = true }`、为标题增加 `heading()`、按钮行加 `4.dp` 间距，并在 `ChallengeStatusView.kt:181` 为 `ChallengeCheckingIndicator` 增加 `semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }`。
3. **S3（便于 E 线页面“原地替换加载提示”接入 & 主题同步）**：E 线接入业务页时，可为 `ChallengeStatusView` 增加可选 `loadingContent: (@Composable () -> Unit)? = null`（在 `Solving` 未满 2 秒时渲染原页面加载指示，满 2 秒自动淡入替换为 `正在检查安全环境…`，避免业务页重复写 2 秒协程计时器）；待 `SettingsRepository` 接入全局应用主题后，将 `ComposeChallengeInteractionPresenter.kt:73` 的 `HiTheme` 同步接入应用内深浅色/纯黑/预设色配置。
4. **S4（消除小幅重复代码）**：在 `ChallengeStatusView.kt:128` 直接复用本文件已定义的 `state.effectiveAt(nowMs)` 合并 `Cooldown` 过期与 `Idle` 分支；后续修改 `ui/designsystem/state/HiStateViews.kt` 时公开 `HiLoadingSpinner` 以替代 `ChallengeCheckingSpinner` 的 20 行重复绘制代码。
5. **S5（补强 Presenter 取消与跨断点单测）**：后续触及 `ui/challenge/` 测试时，补充 `presentDeferred.cancel()` 外部取消后的窗口标志恢复与零视图残留断言、以及面板打开期间从 `411dp` 动态切至 `900dp` 时同一 `WebView` 实例不卸载的单测。
