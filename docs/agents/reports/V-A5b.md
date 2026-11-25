# 验证报告 V-A5b：透明验证宿主与求解状态机独立探针验证

## 1. 结论摘要

- **总判定**：**FAIL**（7 组探针中共检出 4 项真实缺陷，分别位于探针 `a`、`c`、`e`、`g`；探针 `b`、`d`、`f` 全部通过）
- **被验对象**：`origin/card/A5b-challenge-host` @ `0e0da1b49f73306e9cab675ed48adfb380024b85`（只读未修改）
- **探针分支 / Commit**：`verify/A5b-probes` @ `ed291785fe7ddfa35b208de9fa033d514345b91b`（worktree `/home/mir/Documents/hipda-next.wt/V-A5b` 已保留）
- **探针文件**：`/home/mir/Documents/hipda-next.wt/V-A5b/hipda/src/test/java/net/jejer/hipda/verify/A5bProbeTest.kt`（全程未先读实现者测试，仅依据生产代码、`A5b.md` 范围节、`cloudflare-research.md` §5.3 与 `V-A5b.md` 建模）
- **CI 证据**：https://github.com/mailwmj/hipda-next/actions/runs/45559071483
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：通过
  - `Verify Room schema consistency`：通过
  - `Summarize unit test results`：
    - `net.jejer.hipda.verify.A5bProbeTest: 15 tests, 10 passed, 5 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeActivityTest: 7 tests, 7 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeSolveStateMachineTest: 15 tests, 15 passed, 0 failed, 0 errors, 0 skipped`
    - `TOTAL: 522 tests, 517 passed, 5 failed, 0 errors, 0 skipped`（全部 507 条既有单测与 Roborazzi 截图测试全绿，仅本卡探针 5 项失败）
- **本地自检**：
  - `./gradlew :hipda:assembleDebug :hipda:lintDebug :hipda:testDebugUnitTest --continue`：编译与 lint 通过，`:hipda:testDebugUnitTest` 共 `495 tests, 490 passed, 5 failed, 0 skipped`（`A5bProbeTest` 15 条中 10 通过、5 失败，与 CI 一致）

---

## 2. 探针明细（a–g）

| 探针 | 测试方法 (`A5bProbeTest.kt`) | 建模与断言要点 | 结果 |
|---|---|---|---|
| **a** | `probeA_normalPass_delayedClearanceAndMultiplePageFinished_singleSync` (`L155`) | 第 1 次为 403 `cf-mitigated: challenge` 页且连续触发两次 `onPageFinished`，第 2 次为正常页且新 `cf_clearance` 晚 1 秒写入 WebView；断言返回 `Cleared`、关闭宿主、且 `syncFromWebView` 仅调用 1 次 | **FAIL**（过盾本身成功并返回 `Cleared`，但 `syncFromWebView` 被调用 4 次而非 1 次） |
| **b** | `probeB_onlySnapshotOldClearance_escalatesAfter8s_restoresBackupAndCancels` (`L289`) | 正常页加载完成但 WebView 仅残留快照旧值 `old_snapshot_clearance`（`Partitioned`）；`7999ms` 前不判通过不升级，满 `8s` 后以 `BackgroundTimeout` 交接默认 presenter 取消、恢复两端备份、关闭宿主并返回 `Cancelled` | **PASS** |
| **c(1)** | `probeC1_validInteractiveBegin_escalatesImmediatelyBefore8s` (`L382`)、`probeC1b_validOriginsInteractiveBeginAndTimeout_acceptedByChallengeActivity` (`L436`) | 状态机主线程投递 `InteractiveBegin` 及真实 `ChallengeActivity` 接收 `https://challenges.cloudflare.com` 的 `interactiveBegin`；不等 8 秒立即交接 presenter 并恢复备份 | **PASS** |
| **c(2)** | `probeC2_forgedOriginInteractiveBegin_ignoredByChallengeActivityWebMessageBridge` (`L487`) | 真实 `ChallengeActivity` 开启 `WEB_MESSAGE_LISTENER` + `DOCUMENT_START_SCRIPT`，通过 `ShadowWebViewCompat` 从 `https://evil.example`、`https://evil-4d4y.com`、`https://notchallenges.cloudflare.com` 发送伪造 `interactiveBegin`，并检查 `allowedOriginRules` 与 JS `e.origin` 校验 | **FAIL**（`allowedOriginRules=[*]`，伪造消息直接触发 presenter，`expected:<0> but was:<1>`） |
| **c(3)** | `probeC3_forgedOriginInteractiveBegin_ignoredByFallbackJsBridge` (`L583`) | 关闭新特性走降级 `addJavascriptInterface` 路径，模拟导航至 `https://evil.example/phish` 并调用 `FallbackChallengeJsBridge.onChallengeEvent("interactiveBegin")` | **FAIL**（降级桥无来源域名校验，伪造消息直接触发 presenter，`expected:<0> but was:<1>`） |
| **d** | `probeD_onRenderProcessGone_noCrash_returnsFailed_restoresBackup_closesActivity` (`L656`) | 真实 `ChallengeActivity` 触发 `WebViewClient.onRenderProcessGone(didCrash=true)`；验证返回 `true` 不崩溃、返回 `SolveResult.Failed(ChallengeRenderProcessGoneException)`、恢复两端备份、`WebView.destroy()` 且 `isFinishing == true` | **PASS** |
| **e(0)** | `probeE0_callerCancel_beforeStartActivity_restoresBackupAndNeverLaunchesActivity` (`L743`) | 启动 Activity 前（`primeWebView` 后、`acquire`/`startActivity` 前）取消 `solve` 协程；验证抛 `CancellationException`、备份恢复且不发起 `startActivity` | **FAIL**（协程已取消仍调用了 `startActivity`，`expected:<0> but was:<1>`） |
| **e(1)** | `probeE1_callerCancel_beforeActivityAttach_restoresBackupAndClosesIfLaunched` (`L806`) | `startActivity` 已发出、`ChallengeActivity.onCreate` 尚未执行时取消协程，随后投递 Intent 触发 `onCreate`；验证立即 `finish()`、未创建/挂载 WebView、备份恢复 | **PASS** |
| **e(2)** | `probeE2_callerCancel_duringPageLoad_closesActivityRestoresBackupNoWebViewLeak` (`L868`) | `onPageStarted` 页面加载中取消 `solve` 协程；验证抛 `CancellationException`、`isFinishing == true`、`WebView.destroy()` 且从父容器摘除（`parent == null`）、两端备份恢复 | **PASS** |
| **e(3)** | `probeE3_callerCancel_whileWaitingPresenter_closesActivityRestoresBackupNoWebViewLeak` (`L942`) | 升级到 presenter（且切为 `setInteractiveMode(true)`）挂起等待期间取消 `solve` 协程；验证 presenter 收到取消、`isFinishing == true`、`WebView.destroy()` 且 `parent == null`、两端备份恢复 | **PASS** |
| **f(1)** | `probeF_activityRecreatedDuringSolve_viaActivityScenarioRecreate_noCrashAndNo120sGatewayHang` (`L1035`) | 接入 `SiteAccessGateway`（120 秒总超时），presenter 等待中执行 `ActivityScenario.recreate()`；不崩溃，求解在 3 秒内结束，不悬挂到 120 秒网关超时 | **PASS** |
| **f(2)** | `probeF2_activityRecreatedDuringBackgroundLoad_noCrashAndNo120sGatewayHang` (`L1147`) | 接入 `SiteAccessGateway`（120 秒总超时），后台静默加载阶段先断言 `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_FOCUSABLE` / `alpha=0f` / `360x480` / UA / JS 与 DOMStorage，再执行 `ActivityScenario.recreate()`；不崩溃， promptly 结束 | **PASS** |
| **g(1)** | `probeG1_twoConsecutiveSolves_useDistinctFreshActivityAndWebView` (`L1268`) | 第 1 次 `solve` 完成后立即第 2 次 `solve`（第 1 个 Activity 已进入销毁）；第 2 次使用全新 `ChallengeActivity` 与全新 `WebView`，不复用已销毁实例，第 1 个 `WebView` 保持 `wasDestroyCalled() == true` | **PASS** |
| **g(2)** | `probeG2_singleTopRace_onNewIntentAfterFinish_mustNotAttachToFinishingActivity` (`L1381`) | 模拟 `singleTop` 竞态：`solve1` 在 `Dispatchers.Default` 返回后立即发起 `solve2`，UI 线程已执行 `finish()`（`isFinishing == true` 尚未 `onDestroy`）时向旧实例投递 `onNewIntent(intent2)` | **FAIL**（`onNewIntent` 未拦截 `isFinishing` 状态并绑定了第 2 轮会话，随即在 `onDestroy` 被误杀返回 `Cancelled`） |

---

## 3. FAIL 项触发路径与根因定位

### FAIL 1（探针 `c`）：`interactiveBegin` 消息完全缺乏来源校验，非 `4d4y.com`/`challenges.cloudflare.com` 伪造消息可触发升级

- **触发路径**：
  1. 主路径（`WEB_MESSAGE_LISTENER` + `DOCUMENT_START_SCRIPT`）：`ChallengeActivity` 启动后注册消息监听器，测试以 `Uri.parse("https://evil.example")`、`Uri.parse("https://evil-4d4y.com")`、`Uri.parse("https://notchallenges.cloudflare.com")` 投递 `"interactiveBegin"`。`presenterCalls` 从预期 `0` 变为 **`1`**，立即触发 `ChallengeInteractionPresenter`。
  2. 降级路径（`addJavascriptInterface`）：在不支持上述特性的 WebView 上导航至 `https://evil.example/phish`，调用 `window.__hipdaChallengeFallback.onChallengeEvent("interactiveBegin")`，`presenterCalls` 同样变为 **`1`**。
- **对应生产代码**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:360-374`：`WebViewCompat.addWebMessageListener` 与 `addDocumentStartJavaScript` 均传入通配符 `setOf("*")`，且 `WebMessageListener` 回调 `{ _, message, _, _, _ ->` 丢弃 `sourceOrigin` 与 `isMainFrame`。
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:592-628`：`turnstileMessageScript`（被 `DOCUMENT_START_BRIDGE_SCRIPT` 与 `FALLBACK_LISTENER_SCRIPT` 共用）中的 `window.addEventListener('message', function(e) { ... })` 未检查 `e.origin`，任意第三方 iframe 发送 `postMessage({source: 'cloudflare-challenge', event: 'interactiveBegin'}, '*')` 均会通过桥接转发。
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:397-402, 581-589`：`FallbackChallengeJsBridge` 未校验当前 WebView URL / origin。

### FAIL 2（探针 `e`）：启动 Activity 前取消 `solve` 协程仍会发出 `startActivity(ChallengeActivity)`

- **触发路径**：
  1. `WebViewChallengeSolver.solve` 完成 `clearClearance` / `primeWebView` 后，在进入 `webViewHost.acquire` 前调用方取消 `solve` 协程（例如在 `userAgentProvider.userAgent()` 返回前或刚挂起恢复时取消）。
  2. 测试观测到 `countingContext.startActivity(intent)` 仍被调用 **`1`** 次（预期 `0`），发起了本不该启动的 `ChallengeActivity` Intent，之后才在 `session.handleDeferred.await()` 处抛出 `CancellationException`。
- **对应生产代码**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeSolveStateMachine.kt:99-103`：`withTimeoutOrNull(hostAcquireTimeout) { handle = webViewHost.acquire(triggerUrl, userAgent) }` 未先检查 `coroutineContext.ensureActive()`，且 `withTimeoutOrNull` 以 undispatched 方式同步进入块内。
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:223-232`：`AndroidChallengeWebViewHost.acquire` 在首个挂起点 `session.handleDeferred.await()` 之前直接执行 `appContext.startActivity(intent)`，未调用 `currentCoroutineContext().ensureActive()`。

### FAIL 3（探针 `a`）：新 `cf_clearance` 晚于正常页 `onPageFinished` 1 秒出现时，`syncFromWebView` 被重复调用 4 次

- **触发路径**：
  1. 预置 `cdb_sid=sid_abc` 与旧 `cf_clearance`。第 1 次 `onPageFinished` 为 403 验证页（连续触发两次 `onPageFinished`），状态机未调 `syncFromWebView`；`t=600ms` 第 2 次 `onPageFinished` 为正常非验证页，新 `cf_clearance` 于 `t=1600ms` 才写入 WebView。
  2. 在第 2 次 `onPageFinished` 之后直至 `t=2000ms` 确认新 `cf_clearance` 期间，状态机在 `t=600ms`、`1000ms`、`1500ms`、`2000ms` 共 **4 次**调用 `cookieBridge.syncFromWebView`（`SiteCookieStore.saveFromResponse` 与 `WebViewCookieAccess` 读操作各 4 次），预期仅在确认新值后同步 **1 次**。
- **对应生产代码**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeSolveStateMachine.kt:220-242, 322-335`：`tryCheckAndSyncClearance` 在 `isReadyNonChallengePage && !isDomInChallengeState()` 满足后直接调用带写入副作用的 `cookieBridge.syncFromWebView(...)`，未先用只读的 `cookieBridge.snapshotClearances(triggerUrl)` 检查是否存在不在快照中的新 `cf_clearance`，导致在 Cookie 延迟落盘窗口内每 500ms 轮询触发一次全量同步与 Store 持久化。
  - 另观察到 `ChallengeSolveStateMachine.kt:271, 306` 在交互分支中若自定义 presenter 已调用 `session.checkAndSyncClearance()` 并返回 `Cleared`，第 `306` 行会再次调用 `syncFromWebView`。

### FAIL 4（探针 `g`）：连续 `solve` 在 `singleTop` 异步关闭竞态下可能将新会话绑到正在 `finish()` 的旧 `ChallengeActivity`

- **触发路径**：
  1. 第 1 次 `solve` 在 `Dispatchers.Default` 完成并调用 `handle.close()` 后立即返回；`AndroidChallengeWebViewHandle` 通过 `activity.runOnUiThread { detachActiveSession(...); finishWithoutAnimation() }` 异步关闭旧 `ChallengeActivity`，并未等待关闭完成才释放 `solveMutex`。
  2. 调用方紧接着发起第 2 次 `solve`。在 Android `singleTop` 调度竞态下，若旧实例 `isFinishing == true` 尚未 `onDestroy` 时收到 `onNewIntent(intent2)`，`ChallengeActivity.onNewIntent` 直接调用 `attachFromIntent(intent2)` 把第 2 次会话挂到即将销毁的旧 Activity 上；随后旧 Activity 执行 `onDestroy()` 触发 `HostDestroyed`，直接把第 2 次 `solve` 误杀为 `SolveResult.Cancelled`。
- **对应生产代码**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:78-86, 111-118, 453-458`：`onNewIntent` 未守卫 `if (isFinishing || isDestroyed)`，且 `close()` 异步 `runOnUiThread` 结束旧 Activity 未与下一次 `acquire` 隔离。

---

## 4. 探针 `f`（`ActivityScenario.recreate()`）实测行为记录

- 在 `probeF`（presenter 等待中）与 `probeF2`（后台加载中）中，使用 `SiteAccessGateway`（`solveTimeout = 120.seconds`）+ `ActivityScenario.launch(intent)` 后执行 `scenario.recreate()`：
  1. **无异常崩溃**：`scenario.recreate()` 未抛出异常（`recreateError == null`）。
  2. **实际终止行为**：原 `ChallengeActivity` 执行 `onDestroy()` 时通过 `detachActiveSession(emitHostDestroyed = true)` 发出 `ChallengeWebViewEvent.HostDestroyed`，使 `ChallengeSolveStateMachine` 立即以 `SolveResult.Cancelled` 退出并恢复 Cookie 备份、`SiteAccessGateway` 随即在 3 秒内以 `ChallengeIncompleteException` 结束请求并把 `challengeState` 复位为 `ChallengeState.Idle`（**不会**悬挂到 120 秒网关总超时）。
  3. **重建实例状态**：重建出的新 `ChallengeActivity` 在 `onCreate()` 中调用 `ChallengeSessionRegistry.takePending(sessionId)` 返回 `null`，随即调用 `finishWithoutAnimation()`（`recreated.isFinishing == true`）。


---

## 5. 修复轮 1 复验（`449cf13`）

- **复验总判定**：**PASS**（第 1 轮 4 项 FAIL 已全部修复；不改任何原有 `a–g` 探针断言全部一次通过，新增 `UserExplicit` 连续 3 次立即重入探针 `h(1)`/`h(2)` 全绿）
- **被验对象**：`origin/card/A5b-challenge-host` @ `449cf135c56b399e9d0d46d4ae03762704793856`（`449cf13`）
- **探针分支 / Commit**：`verify/A5b-probes` @ `0975d4170c12b132283b22f51cff5f097f46d6dd`（将原探针基线 `b75c12d` rebase 到 `449cf13` 后追加提交 `0975d41`；worktree `/home/mir/Documents/hipda-next.wt/V-A5b` 已保留）
- **CI 证据**：https://github.com/mailwmj/hipda-next/actions/runs/45564020111 （`success`）
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：通过
  - `Verify Room schema consistency`：通过
  - `Run unit tests and verify Roborazzi screenshots`：通过
  - `Verify APK outputs`：通过
  - CI `Summarize unit test results`：
    - `net.jejer.hipda.verify.A5bProbeTest: 17 tests, 17 passed, 0 failed, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeActivityTest: 11 tests, 11 passed, 0 failed, 0 errors, 0 skipped`
    - `net.jejer.hipda.ui.challenge.ChallengeSolveStateMachineTest: 20 tests, 20 passed, 0 failed, 0 errors, 0 skipped`
    - `TOTAL: 533 tests, 533 passed, 0 failed, 0 errors, 0 skipped`
- **本地全量验证**：
  - `./gradlew :hipda:assembleDebug :hipda:lintDebug :hipda:testDebugUnitTest`：`BUILD SUCCESSFUL`，`:hipda:testDebugUnitTest` 共 `506 tests, 506 passed, 0 failed, 0 skipped`（`A5bProbeTest` 17/17 通过）

### 复验探针明细（原有 `a–g` 未改断言 + 新增 `h`）

| 探针 | 测试方法 (`A5bProbeTest.kt`) | 复验结果与证据 |
|---|---|---|
| **a** | `probeA_normalPass_delayedClearanceAndMultiplePageFinished_singleSync` (`L158`) | **PASS**：`tryCheckAndSyncClearance` 先用只读 `snapshotClearances` 确认新 `cf_clearance` 后才调 `syncFromWebView`（`ChallengeSolveStateMachine.kt:349-355`），`saveFromResponse` 精确 1 次、返回 `Cleared` 并关窗 |
| **b** | `probeB_onlySnapshotOldClearance_escalatesAfter8s_restoresBackupAndCancels` (`L292`) | **PASS**：仅旧快照值不判通过，8s 后交接默认 presenter 恢复两端备份、关窗并返回 `Cancelled` |
| **c(1)** | `probeC1_validInteractiveBegin_escalatesImmediatelyBefore8s` (`L385`)、`probeC1b_validOriginsInteractiveBeginAndTimeout_acceptedByChallengeActivity` (`L439`) | **PASS**：`https://challenges.cloudflare.com` 合法消息不等 8s 立即交接 |
| **c(2)** | `probeC2_forgedOriginInteractiveBegin_ignoredByChallengeActivityWebMessageBridge` (`L490`) | **PASS**：`buildAllowedChallengeOriginRules` 收紧规则、`addWebMessageListener` 校验 `isAllowedChallengeOrigin(sourceOrigin, triggerUrl)` 且 `turnstileMessageScript` 校验 `e.origin`（`ChallengeActivity.kt:445-462, 709-728`、`ChallengeWebViewContract.kt:130-161`），伪造来源均被忽略 |
| **c(3)** | `probeC3_forgedOriginInteractiveBegin_ignoredByFallbackJsBridge` (`L586`) | **PASS**：降级路径 `FallbackChallengeJsBridge` 与 `onPageStarted`/`onPageFinished` 脚本注入均校验 `isAllowedChallengeOrigin`（`ChallengeActivity.kt:484-491, 625, 657`），`https://evil.example/phish` 伪造消息被忽略 |
| **d** | `probeD_onRenderProcessGone_noCrash_returnsFailed_restoresBackup_closesActivity` (`L659`) | **PASS**：`onRenderProcessGone` 返回 `true` 不崩，返 `Failed(ChallengeRenderProcessGoneException)`、恢复备份、`WebView.destroy()` 且 `isFinishing == true` |
| **e(0)–(3)** | `probeE0` (`L746`), `probeE1` (`L809`), `probeE2` (`L871`), `probeE3` (`L945`) | **PASS**：`ChallengeSolveStateMachine.kt:102` 与 `AndroidChallengeWebViewHost.acquire`（`ChallengeActivity.kt:279, 282`）补齐 `ensureActive()` 前检查，启动前取消不再发出 `startActivity`；4 个时点均关闭宿主、恢复备份、无泄漏 |
| **f(1)–(2)** | `probeF` (`L1038`), `probeF2` (`L1150`) | **PASS**：`ActivityScenario.recreate()` 不崩，发 `HostDestroyed` 立即结束，不挂死到 120s 网关超时 |
| **g(1)–(2)** | `probeG1` (`L1271`), `probeG2` (`L1384`) | **PASS**：常规连续两次用全新实例；`onNewIntent` 已拦截 `isFinishing \|\| isDestroyed` 并重发新任务（`ChallengeActivity.kt:88-102`），`finally` 先 `handle?.closeAndAwait()` 再放锁（`ChallengeSolveStateMachine.kt:131`），`singleTop` 竞态不再误杀第 2 轮 |
| **h(1)** | `probeH1_userExplicitImmediateReentryThreeTimes_onlyFinalRoundSurvivesNoLeakNoCancelledMisjudgment` (`L1470`) | **PASS**：初始 `UserBlocking` 进入验证后连续 3 次立即 `UserExplicit` 重入，前 3 轮各自收到 `ChallengeIncompleteException` 并完成 `isFinishing` + `WebView.destroy()` + 摘除父容器，仅第 4 轮存活通关返回 `200:OK_FINAL`，无 `Cooldown`、无 `Cancelled` 误判、后续请求直接 200 |
| **h(2)** | `probeH2_userExplicitBurstThreeTimesDuringAcquire_noLeakNoCancelledMisjudgment` (`L1725`) | **PASS**：在上一轮刚进 `solve` 尚未挂载完界面时连续 3 次突刺重入，直击 `closeAndAwait` 与 `PendingChallengeSession` 取消竞态；仅最后一轮存活返回 `200:OK_BURST_FINAL`，全部已建 Activity/WebView 均销毁无泄漏、状态回 `Idle` 且全程未触发 `Cooldown` |
