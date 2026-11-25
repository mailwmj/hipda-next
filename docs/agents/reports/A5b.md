# A5b 交付报告：透明 ChallengeActivity 与后台自动过盾 WebViewChallengeSolver

- **状态**：DONE（含修复轮 1：R-A5b + V-A5b 全部阻断与安全/真机风险项已闭环）
- **分支**：`card/A5b-challenge-host`（基于 `origin/main` @ `0247458`）
- **初版 Commit**：`0e0da1b49f73306e9cab675ed48adfb380024b85`（`feat(challenge): 新增透明 ChallengeActivity 与后台自动过盾求解器`）
- **最终 Commit（修复轮 1）**：`449cf135c56b399e9d0d46d4ae03762704793856`（`fix(challenge): 修复重入竞态、WebView 不可用容错、来源校验与同步时机`）
- **常规 CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45561411505 （`Build, Lint & Unit Tests` 8m59s，516/516 单测全过、Debug/Release APK、Lint、Room Schema 与 Roborazzi 截图验证全绿）
- **模拟器冒烟 CI Run（`emulator=true`）**：https://github.com/mailwmj/hipda-next/actions/runs/45562637692 （`Build, Lint & Unit Tests` 1m11s + `Emulator Smoke Test (API 36)` 2m49s，debug PID=1975 与 R8 release PID=3353 均通过且无 FATAL EXCEPTION / ANR）

---

## 一、修复轮 1（R-A5b + V-A5b）逐项改动与测试对照

| 编号 / 来源 | 改动摘要与文件位置 | 对应回归测试与自验探针 |
|---|---|---|
| **1. B1 重入竞态 & 关闭/恢复顺序**（R-A5b B1 + V-A5b 探针 `g`） | `ChallengeWebViewContract.kt:63-71` 保留 `close()` 兼容接口并新增 `suspend fun closeAndAwait()`；`ChallengeActivity.kt:83-102, 134-156, 324-332, 542-571` 中 `closeAndAwait()` 等待主线程同步完成 `stopLoading()`/`destroy()` 与 `finish()`（`CLOSE_AWAIT_TIMEOUT_MS = 500L` 防测试暂停 Looper 死锁），`onNewIntent` 遇到 `isFinishing || isDestroyed` 时不消费新会话而是 `startActivity` 重投新实例；`ChallengeSolveStateMachine.kt:126-135` `NonCancellable` 中先 `handle?.closeAndAwait()` 再 `restoreClearance`，之后才释放 `solveMutex`。 | `ChallengeSolveStateMachineTest:closeCompletesBeforeRestoreClearanceAndBeforeNextSolveAcquiresMutex`<br>`ChallengeActivityTest:userExplicitReentry_destroysOldWebViewAndRelaunchesOnDyingOnNewIntentWithoutCancellingSecondSolve`<br>V-A5b `A5bProbeTest:probeG1_twoConsecutiveSolves_useDistinctFreshActivityAndWebView`、`probeG2_singleTopRace_onNewIntentAfterFinish_mustNotAttachToFinishingActivity`（PASS） |
| **2. B2 WebView 不可用容错**（R-A5b B2） | `ChallengeActivity.kt:59, 116, 123-128, 316-322` 将 `ChallengeActivity` 开放 `protected open fun createWebView()`，`attachFromIntent` 以 `catch (t: Throwable)` 捕获 `MissingWebViewPackageException` / `UnsatisfiedLinkError`，立即 `session.failAttach(t)` 并 `finishWithoutAnimation()`；`ChallengeSolveStateMachine.kt:123-125` 捕获非 `CancellationException` 的 `Throwable` 恢复备份并返回 `SolveResult.Failed(t)`（非 `Cancelled`、进程不崩）。 | `ChallengeActivityTest:unavailableWebViewPackage_finishesWithoutCrashingAndReturnsFailedImmediately`<br>`ChallengeActivityTest:webViewNativeLinkError_finishesWithoutCrashingAndReturnsFailedImmediately` |
| **3. 折叠屏与深浅色 `configChanges`**（R-A5b #3） | `hipda/src/main/AndroidManifest.xml:94` 补齐 `orientation|screenSize|screenLayout|smallestScreenSize|density|uiMode|keyboard|keyboardHidden|navigation|fontScale|locale|layoutDirection`。 | `ChallengeActivityTest:manifestRegistrationAndHiltBindings_areConfiguredCorrectly`（断言 `CONFIG_DENSITY`/`CONFIG_UI_MODE`/`CONFIG_SCREEN_SIZE`/`CONFIG_SMALLEST_SCREEN_SIZE`/`CONFIG_SCREEN_LAYOUT`/`CONFIG_ORIENTATION`/`CONFIG_KEYBOARD_HIDDEN`） |
| **4. JS 桥来源收紧**（R-A5b #4 + V-A5b 探针 `c`） | `ChallengeWebViewContract.kt:113-163` 新增 `formatOrigin`、`buildAllowedChallengeOriginRules`、`isAllowedChallengeOrigin`（仅允许 `https://4d4y.com`、`https://*.4d4y.com`、`https://challenges.cloudflare.com`、`https://*.challenges.cloudflare.com` 及 `triggerUrl` 源，移除 `setOf("*")`）；`ChallengeActivity.kt:430-489, 597-642, 696-739` 三层校验：`WebViewCompat` `allowedOriginRules` + `sourceOrigin` 回调校验、JS `e.origin` 校验、降级注入与 `FallbackChallengeJsBridge` 当前主帧 URL 校验。 | `ChallengeSolveStateMachineTest:allowedChallengeOriginRulesAndValidation_restrictToForumCloudflareAndTriggerOrigin`<br>`ChallengeActivityTest:installChallengeMessageListener_registersFallbackBridgeInjectsRestrictedScriptAndDispatchesEvents`<br>V-A5b `A5bProbeTest:probeC1_validInteractiveBegin_escalatesImmediatelyBefore8s`、`probeC1b_validOriginsInteractiveBeginAndTimeout_acceptedByChallengeActivity`、`probeC2_forgedOriginInteractiveBegin_ignoredByChallengeActivityWebMessageBridge`、`probeC3_forgedOriginInteractiveBegin_ignoredByFallbackJsBridge`（PASS） |
| **5. 确认新凭证后再同步**（R-A5b #5 + V-A5b 探针 `a`） | `ChallengeSolveStateMachine.kt:271-334, 344-354` 轮询检查先调只读 `(cookieBridge.snapshotClearances(triggerUrl) - snapshot).isEmpty()`，确认存在新 `cf_clearance` 后再调 `syncFromWebView`；交互分支用 `syncedInPresenter` 避免 presenter 已同步后二次写入。整轮最多调用 1 次 `syncFromWebView`，未通过绝不写库。 | `ChallengeSolveStateMachineTest:unsuccessfulPollingWithoutFreshClearance_doesNotWriteUnrelatedCookiesToStore`<br>`ChallengeSolveStateMachineTest:delayedClearanceAfterNormalPageFinished_invokesSyncFromWebViewExactlyOnce`<br>V-A5b `A5bProbeTest:probeA_normalPass_delayedClearanceAndMultiplePageFinished_singleSync`（PASS） |
| **6. 取消前禁止拉起 Activity**（V-A5b 探针 `e`） | `ChallengeSolveStateMachine.kt:102` 进入 `withTimeoutOrNull(hostAcquireTimeout)` 前与 `ChallengeActivity.kt:279, 282` 进入 `register`/`startActivity` 前均加 `currentCoroutineContext().ensureActive()`；已取消时不发起 `startActivity`，备份正常恢复。 | `ChallengeSolveStateMachineTest:callerCancellationBeforeAcquire_neverLaunchesHostAndRestoresBackup`<br>V-A5b `A5bProbeTest:probeE0_callerCancel_beforeStartActivity_restoresBackupAndNeverLaunchesActivity`、`probeE1`–`probeE3`（PASS） |
| **7. `ChallengeActivityTest` 断言加固**（R-A5b #6） | 新增 `RecordingShadowCookieManager` 拦截并校验 `setAcceptThirdPartyCookies(webView, true)`；补齐 `shouldOverrideUrlLoading`（放行 `http`/`https`/`about`，拦截 `intent`/`file`/`javascript`）、`shadowOf(webView).wasDestroyCalled()`、以及真实 `installChallengeMessageListener()` + `FallbackChallengeJsBridge` 注入脚本与事件派发断言。 | `ChallengeActivityTest:launchFromApplicationContext_configuresTransparentNonInteractiveSilentHostAndDestroysWebViewOnClose`<br>`ChallengeActivityTest:realWebViewClient_filtersNavigationSchemesIgnoresProbeErrorsAndTracksMainFrameChallengeHeader`<br>`ChallengeActivityTest:installChallengeMessageListener_registersFallbackBridgeInjectsRestrictedScriptAndDispatchesEvents` |

- **本地参照 `verify/A5b-probes @ ed29178` 自验**：临时将 `A5bProbeTest.kt` 复制到工作区并原样执行 `:hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.A5bProbeTest" --tests "net.jejer.hipda.ui.challenge.*" --tests "net.jejer.hipda.data.network.access.*"`，结果 **`A5bProbeTest` 15/15 PASSED**、**总计 64/64 PASSED**，随后已清理临时探针文件（未合并探针分支）。
- **明确不纳入本轮的纯简化建议（留待 A6）**：合并 `WebViewChallengeSolver` 转发壳与收拢 `terminalWatcher`。

---

## 二、验收项逐条证据

### 1. Manifest 注册与共享文件边界
- **结果**：通过
- **证据（`git diff --stat origin/main...HEAD`）**：
```text
 hipda/src/main/AndroidManifest.xml                 |    7 +
 .../hipda/data/network/access/ChallengeSolver.kt   |    3 +-
 .../jejer/hipda/ui/challenge/ChallengeActivity.kt  |  771 +++++++++++++++
 .../ui/challenge/ChallengeInteractionPresenter.kt  |   78 ++
 .../ui/challenge/ChallengeSolveStateMachine.kt     |  363 +++++++
 .../hipda/ui/challenge/ChallengeWebViewContract.kt |  239 +++++
 .../hipda/ui/challenge/WebViewChallengeSolver.kt   |   70 ++
 hipda/src/main/res/values/styles_challenge.xml     |   12 +
 .../data/network/access/SiteAccessGatewayTest.kt   |    2 +-
 .../hipda/ui/challenge/ChallengeActivityTest.kt    |  783 +++++++++++++++
 .../ui/challenge/ChallengeSolveStateMachineTest.kt | 1032 ++++++++++++++++++++
 .../hipda/ui/challenge/TestWebViewCookieAccess.kt  |  106 ++
 12 files changed, 3464 insertions(+), 2 deletions(-)
```
- `AndroidManifest.xml` 仅注册 `ChallengeActivity`（`singleTop`、透明主题 `Theme.HiPda.ChallengeHost`、`exported="false"`、`excludeFromRecents="true"`、折叠屏/深浅色 `configChanges`），未改动其他共享文件；`DefaultChallengeSolver` 完整保留。

### 2. 单测覆盖（a–g + R-A5b + V-A5b 全绿）
- **结果**：通过
- `ChallengeSolveStateMachineTest`（20/20 PASSED）：覆盖自动通过、旧值过滤、主帧/DOM 挑战态阻断、8 秒超时默认取消恢复备份、`interactiveBegin`/`interactiveTimeout` 提前升级与自定义 presenter 同步/无新凭证拒收/自取消收敛、后台与交互期 `onRenderProcessGone`、调用方取消恢复备份与后续求解不卡死、启动前取消不拉起宿主、异步关闭先于恢复并持有 `solveMutex`、轮询未通过不写库且延迟写入整轮仅同步 1 次、宿主外部销毁、宿主启动超时、主帧双序状态机与来源白名单校验。
- `ChallengeActivityTest`（11/11 PASSED）：覆盖应用 Context + `FLAG_ACTIVITY_NEW_TASK` 启动、透明无焦点不可触摸窗口、360dp×480dp `alpha=0f` WebView、统一 UA/JS/DOM/1P+3P Cookie、A6 交互模式复用同一 WebView、`handle.close()` 销毁 WebView（`wasDestroyCalled()`）并 `finish()`、JS 桥注册/来源限制/事件派发、外部销毁恢复备份、真实 `WebViewClient.onRenderProcessGone` 返回 `true`、`UserExplicit` 重入与垂死 `onNewIntent` 重投、`MissingWebViewPackageException` / `UnsatisfiedLinkError` 不崩溃并立即 `Failed`、孤儿启动自毁、`shouldOverrideUrlLoading` 协议过滤、探针域名错误忽略、`singleTop` `onNewIntent`、Manifest 与全部 Hilt 绑定。
- `SiteAccessGatewayTest`（18/18 PASSED）：同步校验 `ChallengeSolverModule.bindChallengeSolver(WebViewChallengeSolver)`。
- 本地全量自检：`:hipda:assembleDebug :hipda:testDebugUnitTest :hipda:lintDebug` 全绿（489/489 passed，本卡文件零 Lint 告警，生产代码零 `runBlocking`，结束后已 `./gradlew --stop`）。
- CI 全量汇总：`TOTAL: 516 tests, 516 passed, 0 failed, 0 errors, 0 skipped`。

### 3. CI 常规构建与 `emulator=true` API 36 模拟器冒烟
- **结果**：通过
- **最终 Commit**：`449cf135c56b399e9d0d46d4ae03762704793856`
- **常规 CI**：https://github.com/mailwmj/hipda-next/actions/runs/45561411505 （`Build, Lint & Unit Tests` 8m59s，`success`）
- **模拟器冒烟 CI（`emulator=true`）**：https://github.com/mailwmj/hipda-next/actions/runs/45562637692 （`Build, Lint & Unit Tests` 1m11s + `Emulator Smoke Test (API 36)` 2m49s，`success`）
  - `Smoke check passed [debug]: PID=1975, no app FATAL EXCEPTION or ANR in logcat.`
  - `Smoke check passed [release]: PID=3353, no app FATAL EXCEPTION or ANR in logcat.`

---

## 三、对外 API 清单（供 A6 与 B1b 使用）

### 1. 求解器与 Hilt 绑定（`net.jejer.hipda.data.network.access` / `net.jejer.hipda.ui.challenge`）
- `@Singleton class WebViewChallengeSolver : ChallengeSolver`
  - `@Inject constructor(cookieBridge, userAgentProvider, webViewHost, interactionPresenter)` — `hipda/src/main/java/net/jejer/hipda/ui/challenge/WebViewChallengeSolver.kt:21-39`
  - `override suspend fun solve(triggerUrl: HttpUrl): SolveResult` — `WebViewChallengeSolver.kt:60`
- `ChallengeSolverModule.bindChallengeSolver(impl: WebViewChallengeSolver): ChallengeSolver` — `hipda/src/main/java/net/jejer/hipda/data/network/access/ChallengeSolver.kt:34-38`（`DefaultChallengeSolver` 保留于第 25-29 行）
- `ChallengeHostModule.bindChallengeWebViewHost(impl: AndroidChallengeWebViewHost): ChallengeWebViewHost` — `WebViewChallengeSolver.kt:66-70`

### 2. A6 可见验证面板扩展点（`net.jejer.hipda.ui.challenge`）
- `enum class ChallengeInteractionReason { BackgroundTimeout, InteractiveBegin, InteractiveTimeout }` — `ChallengeInteractionPresenter.kt:16-20`
- `interface ChallengeActivityBinding` — `ChallengeInteractionPresenter.kt:26-33`（`activity: ComponentActivity`、`webView: WebView`、`rootContainer: FrameLayout`、`fun setInteractiveMode(interactive: Boolean)`）
- `class ChallengeInteractionSession` — `ChallengeInteractionPresenter.kt:36-48`（`triggerUrl`、`reason`、`webViewHandle`、`activityBinding`、`suspend fun checkAndSyncClearance(): Boolean`）
- `fun interface ChallengeInteractionPresenter { suspend fun present(session: ChallengeInteractionSession): SolveResult }` — `ChallengeInteractionPresenter.kt:58-60`
- `@Singleton class DefaultChallengeInteractionPresenter : ChallengeInteractionPresenter` — `ChallengeInteractionPresenter.kt:63-67`
- `abstract class ChallengeInteractionModule` — `ChallengeInteractionPresenter.kt:72-78`（A6 替换 `bindChallengeInteractionPresenter` 即可接入可见面板）

### 3. 宿主 Activity 与 WebView 契约（供 A6 / B1b 参考）
- `open class ChallengeActivity : ComponentActivity` — `ChallengeActivity.kt:59-263`
  - `protected open fun createWebView(): WebView` — `ChallengeActivity.kt:116`
  - `EXTRA_SESSION_ID` / `SILENT_WEBVIEW_WIDTH_DP = 360` / `SILENT_WEBVIEW_HEIGHT_DP = 480` / `createLaunchIntent(context, sessionId)` — `ChallengeActivity.kt:248-262`
- `@Singleton class AndroidChallengeWebViewHost @Inject constructor(@ApplicationContext appContext: Context) : ChallengeWebViewHost` — `ChallengeActivity.kt:271-294`
- `data class MainFrameState`（`isChallengedResponse`、`isReadyNonChallengePage`）— `ChallengeWebViewContract.kt:9-30`
- `sealed interface ChallengeWebViewEvent`（`PageFinished`、`InteractiveBegin`、`InteractiveTimeout`、`RenderProcessGone`、`HostDestroyed`）— `ChallengeWebViewContract.kt:33-42`
- `class ChallengeRenderProcessGoneException(val didCrash: Boolean, val rendererPriorityAtExit: Int)` — `ChallengeWebViewContract.kt:45-50`
- `interface ChallengeWebViewHandle`（`events`、`activityBinding`、`installChallengeMessageListener`、`loadUrl`、`reload`、`mainFrameState`、`isDomInChallengeState`、`close`、`suspend fun closeAndAwait`）— `ChallengeWebViewContract.kt:53-72`
- `fun interface ChallengeWebViewHost` — `ChallengeWebViewContract.kt:75-80`
- 主题资源：`@style/Theme.HiPda.ChallengeHost` — `hipda/src/main/res/values/styles_challenge.xml:3-11`

---

## 四、真机验收清单（供 M1 / B1b 阶段人工执行）

1. **后台无感过盾（零视觉打扰、零触摸劫持）**：清掉 `cf_clearance` 后请求 `https://www.4d4y.com/forum/`，8 秒内静默通过并重试拿到 200 GBK 页面；期间无黑屏/闪烁/转场动画，底层页面滑动点击及返回键不被拦截。
2. **统一 UA 与 TLS/Cookie 一致性**：核对 WebView 与 OkHttp/Cronet 发送相同清洗后 UA；过盾后同步入 `SiteCookieStore` 的 `cf_clearance` 可直接访问后续 `forumdisplay.php` / `viewthread.php`，无二次 403 循环。
3. **旧凭证清理、未通过不污染与失败恢复**：保留登录态（`cdb_auth`、`cdb_sid`）与旧 `cf_clearance`，弱网/断网触发 8 秒超时取消后，旧 `cf_clearance` 恢复到两端、登录态完好、未写入中途游客 Cookie。
4. **`interactiveBegin` / 8 秒预算升级路径（A5b 与 A6）**：高风控环境触发复选框时，A5b 收到 `interactiveBegin` 后不等 8 秒立即静默取消并恢复备份；接入 A6 后复用同一 WebView 展示可见面板不重新加载。
5. **屏幕旋转、折叠屏不等 DPI 切换与深浅色切换**：验证进行中旋转屏幕、展开/折叠内外屏不同 DPI 的折叠屏或切换系统深浅色模式，`ChallengeActivity` 不重建、WebView 不中断。
6. **最近任务隐藏与孤儿防残留**：验证中切出最近任务列表不出现 `ChallengeActivity`；结束后任务栈无透明空白层残留。
7. **渲染进程崩溃保护（`onRenderProcessGone`）**：`adb shell kill <webview_sandboxed_process_pid>` 强杀渲染进程后，App 主进程不崩、返回失败并恢复备份，下次验证可正常拉起新 WebView。
8. **R8 Release 包真机验证**：安装签名 release APK 重复第 1、2 项，确认 `@Keep`/`@JavascriptInterface`、`WebViewCompat` 与 Hilt 绑定正常。
9. **验证进行中触发 `UserExplicit`“重新验证”**：在首轮验证尚未结束时触发手动重新验证，旧 WebView 销毁、旧宿主安全退出，第二轮启动全新实例并正常通过，不被旧宿主 `onDestroy` 误杀。
