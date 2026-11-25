# 独立代码审查报告 R-A5b：透明验证宿主与 WebView 自动过盾

- **审查对象**：`origin/card/A5b-challenge-host` @ `0e0da1b49f73306e9cab675ed48adfb380024b85`（基线 `0247458`，共 12 个文件，`+2657 / -2`，其中生产代码 1,341 行、测试代码 1,316 行）
- **审查结论**：**CHANGES-REQUESTED**（2 项真机阻断缺陷，修复改动量预计约 30–40 行）
- **CI 核验**：
  - 常规构建/单测/Lint：`45555091962`（Build, Lint & Unit Tests 通过）
  - 模拟器冒烟（`emulator=true`，API 36 debug + release）：`45556082109`（通过）
- **边界核验**：`hipda/src/main/AndroidManifest.xml` 仅新增注册 `ChallengeActivity`（`+7` 行），其余共享文件（`libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication.java`、`.github/`）零改动；`DefaultChallengeSolver` 保留。

---

## 一、阻断项（依 `DISPATCH.md` §4 / §5：真机验证通道失效与整进程崩溃风险）

### [B1] `handle.close()` 异步投递 UI 销毁且晚于 `restoreClearance`，导致 `singleTop` 下“重新验证”被正在 `finish()` 的旧宿主吞掉误杀，并与全局 `CookieManager` 并发踩踏

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeSolveStateMachine.kt:76, 125-131`
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:78-86, 111-119, 453-458`
  - `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeWebViewContract.kt:62`
- **触发路径 1（`UserExplicit`“重新验证”或紧邻下一轮验证被误杀）**：
  1. 第 1 轮验证（`sessionId = S1`）正在 `ChallengeActivity`（`launchMode="singleTop"`）中执行，用户点击“重新验证”（`SiteAccessGateway.kt:102-109, 152-164` `AccessPriority.UserExplicit`）或网关取消旧任务后立即在 `gatewayScope`（`Dispatchers.Default`）启动第 2 轮验证（`S2`）。
  2. 第 1 轮进入 `finally`（`ChallengeSolveStateMachine.kt:125-130`），调用非挂起的 `handle?.close()`（`ChallengeActivity.kt:453-458`），内部仅调用 `activity.runOnUiThread { ... finishWithoutAnimation() }`（`ChallengeActivity.kt:112-117`）把关闭动作投递到主线程 `MessageQueue` 后**立即返回并释放 `solveMutex`**。
  3. 第 2 轮在 `Dispatchers.Default` 立即获得 `solveMutex`，经后台 `cookieDispatcher` 完成快照/清旧/预热（全程不经过主线程），随即调用 `appContext.startActivity(intentForS2)`（`ChallengeActivity.kt:230`）。
  4. 只要主线程此刻正在渲染一帧（尚未执行队列中的 `CloseS1` Runnable），`system_server`（AMS）收到同步 Binder `startActivity` 时看到栈顶 `ChallengeActivity` 的 `finishing == false`，按 `singleTop` 规则不新建实例，而是将 `S2` 封装为 `NewIntent` 事务派发给原 `ChallengeActivity`，排在主线程队列 `CloseS1` **之后**。
  5. 主线程随后按 FIFO 执行：
     - 先执行 `CloseS1`：`activeSessionId` 置空并调用 `activity.finish()`（向 AMS 标记 `finishing = true`，触发后续销毁）；
     - 紧接着执行已入队的 `onNewIntent(intentForS2)`（`ChallengeActivity.kt:78-86` 未检查 `isFinishing || isDestroyed`）：调用 `ChallengeSessionRegistry.takePending(S2)` **消费掉 `S2`** 并将第 2 轮 WebView 挂载到这个已经 `isFinishing == true` 的垂死 Activity 上；
     - 数毫秒后 `onDestroy()` 执行（`ChallengeActivity.kt:88-90`），调用 `detachActiveSession(emitHostDestroyed = true)` 向 `S2` 发送 `HostDestroyed`；
     - 第 2 轮刚启动即以 `SolveResult.Cancelled`（向业务层抛 `ChallengeIncompleteException`）宣告失败，“重新验证”通道失效。
- **触发路径 2（进程级单例 `CookieManager` 恢复/清理与存活 WebView 踩踏）**：
  1. 第 1 轮超时、取消或失败（`!cleared`）进入 `ChallengeSolveStateMachine.kt:125-130` 时，**先**执行 `cookieBridge.restoreClearance(triggerUrl, backup)` 写回旧 `cf_clearance`，**后**调用异步的 `handle?.close()`。
  2. 在主线程真正执行 `wv.stopLoading()` / `wv.destroy()`（`ChallengeActivity.kt:488-495`）前，第 1 轮的 WebView 仍在加载页面/运行挑战脚本并共享进程唯一的 `CookieManager.getInstance()`，既可能改写刚恢复的 Cookie 备份，也会在释放 `solveMutex` 后与第 2 轮的 `clearClearance` / `primeWebView` 并发竞争同一 `CookieManager`。
- **最小修复建议**：
  1. 将 `ChallengeWebViewHandle.close()` 改为 `suspend fun close()`（或在 `AndroidChallengeWebViewHandle` 内用 `CompletableDeferred` 等待主线程执行完 `detachActiveSession` + `finishWithoutAnimation()` 后再返回；主线程直接调用时同步执行防死锁）；
  2. 在 `ChallengeSolveStateMachine.kt:125-130` 的 `withContext(NonCancellable)` 中，**先** `runCatching { handle?.close() }` 确保 WebView 已 `stopLoading()`/`destroy()` 且 `Activity.finish()` 同步通知完 AMS，**再**在 `!cleared` 时执行 `cookieBridge.restoreClearance(triggerUrl, backup)`，最后才退出 `solveMutex`；
  3. 在 `ChallengeActivity.onNewIntent`（`ChallengeActivity.kt:78-86`）开头防御 `if (isFinishing || isDestroyed)`：此时不得 `takePending` 吞掉新会话，而是直接 `applicationContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))` 重新投递并返回。

---

### [B2] `ChallengeActivity` 主线程构造 `WebView(this)` 未捕获异常，系统 WebView 更新或缺失时触发整进程崩溃

- **位置**：`hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt:73, 83, 99, 132-133`
- **触发路径**：
  1. 真机后台 Google Play / 系统应用商店正在更新 `com.google.android.webview`（或 Trichrome），或定制 ROM 禁用/损坏系统 WebView 提供者期间；
  2. 用户浏览论坛触发任意 CF 403，`AndroidChallengeWebViewHost` 自动启动透明 `ChallengeActivity`；
  3. `ChallengeActivity.onCreate` → `attachFromIntent` → `createConfiguredWebView` 在主线程无保护执行 `WebView(this)`（`ChallengeActivity.kt:99, 133`；仅第 153 行的 `CookieManager` 包了 `runCatching`）。
- **后果**：Android 框架抛出 `AndroidRuntimeException`（`WebViewFactory$MissingWebViewPackageException`）或 `UnsatisfiedLinkError`，直接击穿 `onCreate` 导致**整个 App 主进程崩溃退出**，丢失用户当前页面与未提交状态（对比 A5a `WebViewUserAgentProvider.kt:47-51` 已专门用 `catch (_: Throwable)` 防护同一异常源）。
- **最小修复建议**：在 `attachFromIntent` 中用 `runCatching { createConfiguredWebView(session.userAgent) }` 包裹 WebView 创建；捕获 `Throwable` 时调用 `session.failAttach(t)`（让 `handleDeferred.completeExceptionally(t)` 立即唤醒 `acquire()` 进入 `SolveResult.Failed(t)` 与 Cookie 备份恢复，无需干等 5 秒 `hostAcquireTimeout`），随后返回 `false` 由 `finishWithoutAnimation()` 静默关闭宿主。

---

## 二、六项审查重点逐项核查

### 1. Activity 生命周期与窗口/任务栈
- **透明不遮挡、不抢焦点（通过）**：
  - `styles_challenge.xml:3-10` 继承 `android:Theme.Translucent.NoTitleBar`（宿主继承 `androidx.activity.ComponentActivity` 而非 `AppCompatActivity`，无主题崩溃风险），配置 `windowIsTranslucent=true`、`windowBackground=@android:color/transparent`、`backgroundDimEnabled=false`、`windowNoTitle=true`、`windowAnimationStyle=@null`。
  - `ChallengeActivity.kt:59-60, 160-169` 在 `onCreate` 首屏布局前即加 `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` 并禁掉进出场过渡动画，触摸与按键（含返回键、输入法焦点）直接穿透到底层 Activity。
- **旋转 / 折叠 / 多窗口 / 进程被杀（不崩、不漏、不挂死 120s，但折叠屏跨屏 DPI 变化会中断当次验证）**：
  - 旋转与同 DPI 分屏缩放：`AndroidManifest.xml:94` 声明 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`，Activity 不重建，WebView 保持原实例继续求解。
  - 未声明的配置变化（如三星 Galaxy Z Fold 等内外屏 `densityDpi` 不同触发 `density`，或深浅色切换触发 `uiMode`）：旧 Activity 走 `onDestroy` → `detachActiveSession(emitHostDestroyed = true)` 安全销毁 WebView 并唤醒状态机返回 `SolveResult.Cancelled` + 恢复 Cookie 备份（不会崩溃、不泄漏 WebView、不会让网关干等 120s）；重建的新 Activity 因 `ChallengeSessionRegistry.takePending(sessionId)` 已空立即 `finishWithoutAnimation()`（不留空白透明层）。**但进行中的验证会被取消**（A6 弹出可见面板后折叠/展开也会把面板关掉）。因 `backlog.md:16` 规定只有 A5 可改 `src/main/AndroidManifest.xml`、A6 无权改主 Manifest，建议在本轮修改中顺手补齐 `density|uiMode|keyboard|keyboardHidden|navigation|fontScale|locale|layoutDirection`。
  - 进程被杀后恢复：新进程中 `ChallengeSessionRegistry` 为空，`onCreate` 立即 `finishWithoutAnimation()` 露出底层页面，无孤儿透明窗残留。
- **Android 10+ 后台启动 Activity 限制（BAL）（不卡死 120s、不丢备份）**：
  - 纯后台轮询（`AccessPriority.BackgroundSilent`）已在 `SiteAccessGateway.kt:101, 131` 直接抛 `ChallengeAbandonedException`，不会调用 `solver.solve`。
  - 若前台发起的 `UserBlocking` 请求在网络飞行途中用户按 Home 退后台，触发 Android 10+ BAL 静默拦截（`startActivity` 正常返回但系统不拉起 Activity），`ChallengeSolveStateMachine.kt:100-108` 的 `withTimeoutOrNull(hostAcquireTimeout = 5.seconds)` 会在 5 秒内超时并调用 `ChallengeSessionRegistry.abort(session)` 清理挂起会话（即便系统延后拉起 Activity 也会因 `takePending == null` 立即自毁），随后恢复 `cf_clearance` 备份并返回 `SolveResult.Failed(TimeoutException)`，不会静默挂死到 120 秒。
- **`singleTop`、`exported=false`、`taskAffinity` / `excludeFromRecents`（除 [B1] 竞态外设计合理）**：
  - 未单独设空 `taskAffinity` 是正确的：与主界面共享默认 `taskAffinity` 使 `FLAG_ACTIVITY_NEW_TASK` 直接压入当前前台任务栈顶，避免跨任务切换动画或分屏模式跳到另一侧窗口；同时 `excludeFromRecents="true"` 保证极端情况下作为根 Activity 启动时不在最近任务留下空白卡片。

### 2. WebView 配置、JS 桥来源限制与销毁防泄漏
- **视口尺寸与不可交互属性（通过）**：`ChallengeActivity.kt:99-105, 136-143` 使用 `360dp × 480dp`、`View.VISIBLE`、`alpha = 0f`、`isFocusable = false`、`isFocusableInTouchMode = false`、`isClickable = false`、`importantForAccessibility = NO`，配合窗口级 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`，既规避 `0x0`/`GONE` 的 Chromium 渲染节流与 Turnstile 风控，又保证绝对不拦截触摸和无障碍焦点。
- **UA、JS、DOM Storage、Cookie、文件权限（通过）**：`userAgentString` 取自 `UserAgentProvider.userAgent()`（A5a `WebViewUserAgentProvider` 清洗后 UA）；开启 `javaScriptEnabled`、`domStorageEnabled`、`setAcceptCookie(true)`、`setAcceptThirdPartyCookies(this, true)`；显式关闭 `allowFileAccess` 与 `allowContentAccess`。
- **`androidx.webkit:1.18.0` API 与来源限制 / 退化路径（功能正确，来源限制过宽需收紧）**：
  - 经 `javap` 核对 `webkit-1.18.0.aar`：`WebViewCompat.addWebMessageListener(WebView, String, Set<String>, WebMessageListener)`、`addDocumentStartJavaScript(WebView, String, Set<String>)`、`removeWebMessageListener`、`ScriptHandler.remove()`、`WebViewFeature.WEB_MESSAGE_LISTENER` / `DOCUMENT_START_SCRIPT` 签名与特性守卫全部匹配，且 `installChallengeMessageListener()` 与 `loadUrl()` 依次投递主线程 FIFO 队列，保证监听器先于首次导航生效；任一特性不支持或抛异常时回滚已注册监听器并退化到 `@Keep` + `@JavascriptInterface` + `onPageStarted`/`onPageFinished` 注入。
  - **来源限制缺口（建议收紧）**：`ChallengeActivity.kt:363, 373` 对 `addWebMessageListener` 与 `addDocumentStartJavaScript` 均传入了 `setOf("*")`（且回调未校验 `sourceOrigin`，`turnstileMessageScript` 第 597 行未校验 `e.origin`，退化路径第 527/555 行未校验当前 URL 域名）。虽然 `parseTurnstileChallengeEvent` 只接收固定枚举 `"interactiveBegin"` / `"interactiveTimeout"`、不读写任何敏感数据（不构成越权泄露），但一旦过盾跳转到帖子页或 A6 切为可见后加载了含第三方 iframe 的页面，任意来源均可伪造 `postMessage` 触发升级。应将 `allowedOriginRules` 收紧为 `https://4d4y.com`、`https://*.4d4y.com`、`https://challenges.cloudflare.com`、`https://*.challenges.cloudflare.com` 加动态推导的 `${triggerUrl.scheme}://${triggerUrl.host}`（含非默认端口，兼顾自定义论坛服务器与本地测试），并在 JS 监听器和退化注入中同步校验域名。
- **`onRenderProcessGone` 与销毁顺序（通过）**：
  - `ChallengeActivity.kt:561-577` 与 `DetachedWebViewClient`（第 506-511 行）始终返回 `true`，杜绝渲染进程退出连带杀崩宿主进程。
  - `destroyWebViewSafely()`（第 475-501 行）按 `scriptHandler.remove()` → `removeWebMessageListener` / `removeJavascriptInterface` → `stopLoading()` → 替换 `DetachedWebViewClient` → `parent.removeView(wv)` → `onPause()` → `removeAllViews()` → `destroy()` 顺序清理，防止 Activity 引用泄漏与附着态 `destroy()` 崩溃。

### 3. 自动通过判定条件
- **防误判分析（通过）**：
  - `MainFrameStateTracker`（`ChallengeWebViewContract.kt:108-179`）通过 `awaitingPageStarted` 正确兼容了 Chromium 两种回调顺序（`onReceivedHttpError -> onPageStarted -> onPageFinished` 与 `onPageStarted -> onReceivedHttpError -> onPageFinished`）、同一 403 页内 `history.replaceState` 多次触发 `onPageFinished`（`state.copy` 保留 `hasCfMitigatedChallengeHeader` 与 `httpErrorStatusCode`）、二轮连续 403/429/503 挑战以及主帧网络错误（`hasMainFrameLoadError`），同时忽略 `*.challenges.cloudflare.com` 与 `*.dnstest.dev` 探测错误。
  - `CHALLENGE_DOM_CHECK_SCRIPT`（`ChallengeActivity.kt:630-651`）采用 fail-closed 策略（异常、空文档、非 `"false"` 返回值一律视为仍在挑战态），且不匹配正常页面页尾注入的 `scripts/jsd/main.js`，不会把正常 Discuz! 页面卡死。
- **防晚写超时分析（通过）**：除 `PageFinished` 事件触发外，`ChallengeSolveStateMachine.kt:220-225` 每 `500ms` 轮询检查，若 `cf_clearance` 在 `onPageFinished` 之后异步落入 `CookieManager`，下一个 500ms 周期即可检出通过。
- **可改进点**：若某篇技术讨论帖正文恰好出现字面量字符串 `cf-turnstile` 或 `_cf_chl_opt`，`documentElement.innerHTML.indexOf(...)`（第 642-646 行）会产生假阳性（前两步 `typeof window._cf_chl_opt` + `document.querySelector(...)` + 检查 `<title>Just a moment` 或 `<script>` 内容即可精确覆盖）。

### 4. 与 A4b / A5a 的契约
- **超时嵌套**：`hostAcquireTimeout`（5s）+ `backgroundTimeout`（8s）+ `ChallengeInteractionPresenter` 挂起等待均运行在 `SiteAccessGateway` 的 `DEFAULT_SOLVE_TIMEOUT`（120s）之内；120s 总超时取消 `solverJob` 时，`runInteractivePhase` 会取消 `presenterJob` 并在 `NonCancellable` 中执行清理。
- **取消与备份恢复**：除 [B1] 的 `close()` 异步与恢复顺序问题外，所有失败、超时、外部销毁、渲染进程崩溃及协程取消分支均未将 `cleared` 置真，都会恢复 `ClearanceBackup`；调用方取消时清理后重新抛出 `CancellationException`。
- **`syncFromWebView` 调用时机（存在提前写入隐患）**：`ChallengeSolveStateMachine.kt:327-334` 在 `isReadyNonChallengePage && !isDomInChallengeState()` 为真时直接调用 `cookieBridge.syncFromWebView(triggerUrl, snapshot)` 来探测是否有 `freshClearance`。若 WebView 已到达 HTTP 200 页面但新 `cf_clearance` 尚未写入（或仅有快照旧值），每 500ms 轮询都会把 WebView 中的其他 Cookie（如游客 `cdb_sid`）提前写入 `SiteCookieStore` 落盘，即使最终超时取消也无法回滚这部分非 `cf_clearance` 写入。先用只读的 `(cookieBridge.snapshotClearances(triggerUrl) - snapshot).isNotEmpty()` 判断确认已有新凭证后再调 `syncFromWebView`，即可保证严格只在通过后同步。

### 5. 测试评估与“改坏一行测试仍通过”反例
- **假实现建模评估**：
  - `TestWebViewCookieAccess`（`TestWebViewCookieAccess.kt:13-106`）按 `(name, domain, path, hostOnly, partitioned)` 建模并校验 `Secure` / `SameSite=None` 与过期删除，吸取了 A5a 经验。
  - 但 `FakeChallengeWebViewHandle`（`ChallengeSolveStateMachineTest.kt:733-777`）在调用线程同步触发 `onLoadUrl`、同步返回 `isDomInChallengeState`、同步置 `closed = true`，**未模拟真实 WebView 跨主线程异步回调与 `runOnUiThread` 排队延迟**（直接掩盖了 [B1] 竞态），也未在状态机测试中模拟单次导航多次 `onPageFinished` 与 302 跨路径重定向。
- **“改坏一行生产代码，现有全部测试仍 100% 通过”的具体例子**：
  1. `ChallengeActivity.kt:522`：把 `shouldOverrideUrlLoading` 的返回值改为 `return true`（真机上所有 `https://` 重定向全部被拦截，过盾后无法跳回源站，通道彻底瘫痪）——`ChallengeActivityTest` 未测 `shouldOverrideUrlLoading`，全绿通过。
  2. `ChallengeActivity.kt:156`：把 `cookieManager.setAcceptThirdPartyCookies(this, true)` 改为 `false`（真机上 `challenges.cloudflare.com` 跨域 iframe Cookie 被拒，Turnstile 失效）——`ChallengeActivityTest:155` 只断言了 `acceptCookie()` 未断言 `acceptThirdPartyCookies(webView)`，全绿通过。
  3. `ChallengeActivity.kt:352` 或 `604`：把 `installChallengeMessageListener()` 改为空实现，或把注入脚本里的 `'cloudflare-challenge'` 拼错——`ChallengeActivityTest` 从未调用真实 `handle.installChallengeMessageListener()` 或 `FallbackChallengeJsBridge.onChallengeEvent`，全绿通过。
  4. `ChallengeActivity.kt:495`：删掉 `destroyWebViewSafely()` 中的 `wv.destroy()`（真机每次过盾泄漏整个原生 `AwContents`）——`ChallengeActivityTest:171` 只断言了 `rootContainer.childCount == 0`，未断言 `shadowOf(webView).wasDestroyCalled()`，全绿通过。

### 6. 复杂度与真机验收清单核对
- **复杂度评估（DISPATCH §5）**：
  - `WebViewChallengeSolver.kt`（70 行）只是 `ChallengeSolveStateMachine` 的纯转发壳，且包含两个仅供单测使用的 `internal constructor`（第 21-23、40-58 行）；后续可在 A6 或重构时直接合并进 `WebViewChallengeSolver`，省去约 55 行样板代码。
  - `ChallengeSolveStateMachine.kt` 在 `runWithHandle` / `awaitBackgroundOutcome` / `runInteractivePhase` 中嵌套启动了 8 个子协程并重复写了 2 处 `terminalWatcher` 与 4 处 `if (terminalEvent.isCompleted)`；把 `terminalEvent.await()` 提到外层统一竞争可减少约 60 行状态分支（属纯简化建议，按 DISPATCH §5 不单独作为阻断，可带入 A6）。
- **真机验收清单核对（`_reports/A5b.md` 第三节）**：已覆盖后台无感过盾、统一 UA 与二次请求不 403、旧凭证备份恢复、`interactiveBegin` 升级、旋转与折叠、Recents 隐藏、`onRenderProcessGone` 强杀渲染进程、R8 release 包 8 个场景；建议补充第 9 项“验证进行中点‘重新验证’（`UserExplicit`）连续切换会话正常通过”及折叠屏内外屏不等 DPI 切换验证。

---

## 三、非阻断建议汇总（可在修改轮顺手处理或带入 A6）

1. **`AndroidManifest.xml:94` 补齐折叠屏与深浅色 `configChanges`**：趁 A5b 持有主 Manifest 修改权（`backlog.md:16` 限制 A6 改主 Manifest），在 `ChallengeActivity` 的 `android:configChanges` 中补上 `density|uiMode|keyboard|navigation|fontScale|locale|layoutDirection`，避免内外屏不同 DPI 的折叠屏展开/折叠或切换深浅色时销毁 `ChallengeActivity` 中断验证（尤其 A6 可见面板阶段）。
2. **`ChallengeActivity.kt:363, 373, 527, 555, 597` 收紧 JS 桥来源限制**：将 `addWebMessageListener` 与 `addDocumentStartJavaScript` 的 `setOf("*")` 收紧为 `4d4y.com`、`*.4d4y.com`、`challenges.cloudflare.com`、`*.challenges.cloudflare.com` 加 `${triggerUrl.scheme}://${triggerUrl.host}`（含非默认端口），并在 JS `message` 监听与退化注入中同步校验来源。
3. **`ChallengeSolveStateMachine.kt:327-334` 严格只在通过后调 `syncFromWebView`**：在 `tryCheckAndSyncClearance` 中先通过只读的 `(cookieBridge.snapshotClearances(triggerUrl) - snapshot).isEmpty()` 过滤，确认存在新 `cf_clearance` 后再调 `syncFromWebView` 写入 `SiteCookieStore`。
4. **补齐 `ChallengeActivityTest` 关键断言**：补充对 `acceptThirdPartyCookies(webView)`、`shouldOverrideUrlLoading`、`shadowOf(webView).wasDestroyCalled()`、`FallbackChallengeJsBridge` 事件派发及 [B1]/[B2] 修复分支的单测断言。
5. **简化（带入 A6）**：合并 `WebViewChallengeSolver` 与 `ChallengeSolveStateMachine` 消除纯测试转发构造器，收拢两处重复的 `terminalWatcher`；`CHALLENGE_DOM_CHECK_SCRIPT` 去掉对整页 `innerHTML` 的宽泛子串匹配，避免技术帖正文提及 `cf-turnstile` 时假阳性；`createLaunchIntent` 可附带 `Intent.FLAG_ACTIVITY_NO_ANIMATION`。

---

## 四、修复轮 1 复审结论（`0e0da1b..449cf13`）

- **复审对象**：`origin/card/A5b-challenge-host` @ `449cf135c56b399e9d0d46d4ae03762704793856`（增量 `git diff 0e0da1b..449cf13`：6 个文件，`+917 / -110`）
- **复审结论**：**APPROVE**
- **CI 核验**：
  - 常规构建/单测/Lint：`45561411505`（Build, Lint & Unit Tests 通过）
  - 模拟器冒烟（`emulator=true`，API 36 debug + release）：`45562637692`（通过）

### 1. 两项阻断项闭合核查

1. **[B1] 重入竞态与关闭/恢复顺序 — 已真正闭合**：
   - `ChallengeSolveStateMachine.kt:128-135` 在 `withContext(NonCancellable)` 中改为**先** `handle?.closeAndAwait()`，待主线程完成 `wv.stopLoading()`/`wv.destroy()` 及 `finishWithoutAnimation()` 后，**再**在 `!cleared` 时调用 `cookieBridge.restoreClearance(triggerUrl, backup)`，最后才退出 `solveMutex`；`AndroidChallengeWebViewHost.acquire`（`ChallengeActivity.kt:286-290, 324-333`）在握手期被取消时亦通过 `NonCancellable` + `abortAndAwaitClose` 等待已附着句柄关闭完毕，彻底消除了存活 WebView 与进程级 `CookieManager` 并发踩踏及 `acquire` 临界窗口遗漏。
   - `ChallengeActivity.onNewIntent`（`ChallengeActivity.kt:88-102`）新增 `if (isFinishing || isDestroyed)` 守卫：绝不调用 `takePending` 吞掉新会话，而是携带 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_NO_ANIMATION` 重新 `startActivity` 交由 AMS 拉起新实例（若重投异常则 `failPending` 立即失败），彻底堵死 `singleTop` 垂死宿主误杀次轮验证的竞态。
   - 已由 `ChallengeSolveStateMachineTest:closeCompletesBeforeRestoreClearanceAndBeforeNextSolveAcquiresMutex` 与 `ChallengeActivityTest:userExplicitReentry_destroysOldWebViewAndRelaunchesOnDyingOnNewIntentWithoutCancellingSecondSolve` 覆盖验证。

2. **[B2] 系统 WebView 更新/缺失崩溃 — 已真正闭合**：
   - `ChallengeActivity.kt:116, 124-129` 抽出 `protected open fun createWebView()` 并在 `attachFromIntent` 中以 `catch (t: Throwable)` 捕获 `MissingWebViewPackageException`（`AndroidRuntimeException`）与 `UnsatisfiedLinkError` 等异常/错误，调用 `session.failAttach(t)`（`handleDeferred.completeExceptionally(t)`）唤醒挂起的 `acquire()`，同时返回 `false` 触发 `finishWithoutAnimation()`；`ChallengeSolveStateMachine.kt:125-126` 同步扩展为 `catch (t: Throwable) { SolveResult.Failed(t) }`，不崩进程、不干等 5 秒超时、正确恢复备份。
   - 已由 `ChallengeActivityTest:unavailableWebViewPackage_finishesWithoutCrashingAndReturnsFailedImmediately` 与 `webViewNativeLinkError_finishesWithoutCrashingAndReturnsFailedImmediately` 覆盖验证。

### 2. 死锁 / 悬挂与主线程阻塞核查

- **`closeAndAwait()` 不依赖 `Activity.onDestroy()` 且带 500ms 超时兜底**：
  - `ChallengeActivity.kt:144-164` 中 `completed.complete(Unit)` 在主线程 `closeAction` 执行完同步的 `detachActiveSession`（`destroyWebViewSafely()`）+ `finishWithoutAnimation()` 后于 `finally` 立即触发，**不等待**系统异步调度的 `Activity.onDestroy()`；若外部 `onDestroy()` 先行触发，`tearDownFromActivity(..., completeCloseSignal = true)`（第 568-570 行）亦已提前完成 `closeCompleted`。
  - `ChallengeActivity.kt:550-555, 695` 在 `closeAndAwait()` 外层套有 `withTimeoutOrNull(CLOSE_AWAIT_TIMEOUT_MS = 500L)`：即使在极端主线程卡死或单测暂停 Looper 未推进的场景下，最多挂起 500ms 即安全放行，绝不永久悬挂。
- **主线程零阻塞**：
  - 生产代码无 `runBlocking`、`CountDownLatch.await`、`Future.get` 或阻塞等待；`closeAndAwait()` 为纯协程挂起。
  - 当调用方已在主线程时（`Looper.myLooper() == Looper.getMainLooper()`，第 159-161 行），`closeAction.run()` 直接内联同步执行并完成 `closeCompleted`，不会发生主线程投递自锁。
  - `onNewIntent` 的 `isFinishing` 重投分支仅在当前 `ActivityRecord` 已向 AMS 标记 `finishing = true` 时执行，AMS 不会将重投 Intent 再次路由回同一 `finishing` 实例，无自旋重投风险。

### 3. 建议项落实核查

- **折叠屏与深浅色 `configChanges`（已落实）**：`AndroidManifest.xml:94` 已补齐 `density|uiMode|keyboard|keyboardHidden|navigation|fontScale|locale|layoutDirection` 并在 `ChallengeActivityTest:647-658` 断言。
- **JS 桥来源限制（已落实）**：`ChallengeWebViewContract.kt:113-161` 与 `ChallengeActivity.kt:445-491, 625-627, 657-659, 697-746` 移除 `setOf("*")`，收敛为 `https://4d4y.com`、`https://*.4d4y.com`、`https://challenges.cloudflare.com`、`https://*.challenges.cloudflare.com` 及动态 `triggerUrl` 源（含非默认端口与 IPv6 字面量处理），在 `WebViewCompat` 规则、`sourceOrigin` 回调、JS `e.origin` 与降级桥四处同步把关，兼容真实 Turnstile 跨域 iframe `postMessage` 与自定义论坛地址。
- **确认新凭证后再同步（已落实）**：`ChallengeSolveStateMachine.kt:271-281, 316-335, 349-355` 先以只读 `(cookieBridge.snapshotClearances(triggerUrl) - snapshot).isEmpty()` 判新值再调 `syncFromWebView`，并用 `syncedInPresenter` 去重，确保未通过零写库、通过最多同步 1 次。
- **`ChallengeActivityTest` 盲区断言与取消前防启动（已落实）**：新增 `RecordingShadowCookieManager` 断言第三方 Cookie、覆盖 `shouldOverrideUrlLoading` 协议白名单、`wasDestroyCalled()`、降级 JS 桥正反例，以及 `acquire` 前 `ensureActive()` 防无效拉起宿主；`createLaunchIntent` 已附带 `FLAG_ACTIVITY_NO_ANIMATION`。
- **纯简化项（按约留待 A6）**：合并 `WebViewChallengeSolver` 转发壳与收拢重复 `terminalWatcher` 留给后续触及同目录的 A6 处理，符合 `DISPATCH.md` §5。

---

## 五、A6 收尾轮复审（`f0e0a15`）

- **复审对象**：`origin/card/A6-challenge-panel` @ `f0e0a150b700f3c63d33f6cb4945f2f909e3c3f4`（共 7 个文件，`+590 / -405`，删除冗余拆分的 `ChallengeSolveStateMachine.kt` 并合并入 `WebViewChallengeSolver.kt`）
- **复审结论**：**APPROVE**
- **CI 核验**：`45565489337`（`Android CI` Build, Lint & Unit Tests `success`）

### 1. `449cf13` 已闭合六项语义保留核查

逐行核对合并后的 `WebViewChallengeSolver.kt` 及未改动的 `ChallengeActivity.kt` / `ChallengeWebViewContract.kt`：

1. **`closeAndAwait()` 先销毁再 `restoreClearance` 再释放 `solveMutex`（完整保留）**：
   - `WebViewChallengeSolver.kt:102, 152-160` 在 `solveMutex.withLock` 内的 `finally { withContext(NonCancellable) { ... } }` 中严格保持先 `runCatching { handle?.closeAndAwait() }`、再 `if (!cleared) runCatching { cookieBridge.restoreClearance(triggerUrl, backup) }`、最后退出 `withLock`；`ChallengeActivity.kt` 中 `abortAndAwaitClose` 与 `CLOSE_AWAIT_TIMEOUT_MS = 500L` 未改动。
2. **`onNewIntent` 垂死实例重投（完整保留）**：
   - `ChallengeActivity.kt:86-102` 未改动，`isFinishing || isDestroyed` 时不消费 `takePending` 而是重投 `startActivity`（失败则 `failPending`）。
3. **WebView 创建 `Throwable -> SolveResult.Failed`（完整保留）**：
   - `ChallengeActivity.kt:124-129` `catch (t: Throwable) { session.failAttach(t); return false }` 未改动；`WebViewChallengeSolver.kt:147-151` 保留 `catch (e: CancellationException) { throw e } catch (t: Throwable) { SolveResult.Failed(t) }`。
4. **`ensureActive()` 防启动前取消拉起宿主（完整保留）**：
   - `WebViewChallengeSolver.kt:126` 在进入 `withTimeoutOrNull(hostAcquireTimeout)` 前保留 `currentCoroutineContext().ensureActive()`；`ChallengeActivity.kt:279, 282` 双重 `ensureActive()` 未改动。
5. **整轮最多 1 次 `syncFromWebView`、未通过零写库（完整保留）**：
   - `WebViewChallengeSolver.kt:303-338, 343-358` 完整保留先调只读 `(cookieBridge.snapshotClearances(triggerUrl) - snapshot)` 判新值再调 `syncFromWebView`，以及 `syncedInPresenter = AtomicBoolean(false)` 防交互阶段二次写入。
6. **JS 桥来源白名单（完整保留）**：
   - `ChallengeWebViewContract.kt:113-161` 与 `ChallengeActivity.kt` 三层来源校验文件零改动。

### 2. `terminalWatcher` 收拢与 Presenter `ON_DESTROY` 并发/双重完成核查

- **单一外层 `terminalWatcher` 收拢正确**：
  - `WebViewChallengeSolver.kt:193-244` 用单一 `outcome = CompletableDeferred<SolveResult>()` 统一竞争外层 `terminalWatcher`（监听 `HostDestroyed` / `RenderProcessGone`）与 `solveJob`（串行执行 `awaitBackgroundOutcome` → `runInteractivePhase`），消除了原先两处重复的 `terminalWatcher` 与多处 `terminalEvent.isCompleted` 检查；第 234-238 行保留了 `if (terminalEvent.isCompleted) terminalEvent.await().toSolveResult()`，确保 `RenderProcessGone` 与取消同刻到达时仍准确返回 `SolveResult.Failed(ChallengeRenderProcessGoneException)`。
- **Presenter `ON_DESTROY` 与外层 `HostDestroyed` 无双重完成异常、无清理竞态**：
  - 当 `ChallengeActivity.onDestroy()` 执行时，先在 `ChallengeActivity.kt:112` 调 `detachActiveSession(emitHostDestroyed = true)`（置 `closed = true`、销毁 `WebView`、发送 `HostDestroyed`），紧接着 `super.onDestroy()` 同步触发 `ComposeChallengeInteractionPresenter.kt:76-82` 的 `hostDestroyObserver`（`ON_DESTROY`）。
  - 两侧均通过 `CompletableDeferred.complete(...)`（`resultDeferred`、`dismissedDeferred`、`terminalEvent`、`outcome`）收敛，而 `CompletableDeferred.complete` 为无锁幂等 CAS（二次调用仅返回 `false`，不同于 `Continuation.resume` 绝不抛异常）；同时 `ON_DESTROY` 立即完成 `dismissedDeferred.complete(Unit)`，使第 145 行无需干等 1000ms 退场动画超时。
  - 无论 `hostDestroyObserver` 先令 `present()` 返回 `Cancelled`，还是外层 `terminalWatcher` 先取消 `solveJob` 进入 `present()` 的 `finally`（第 151-160 行 `NonCancellable + Dispatchers.Main.immediate`）：`removeObserver` 幂等、`binding.setInteractiveMode(false)` 因 `closed.get() == true` 安全短路、`composeView.disposeComposition()` 与父容器 `removeView` 均在主线程串行安全执行，无竞态或泄漏。

### 3. 其余顺手改动核查

- `ChallengePanel.kt:162-169, 214-215` 与 `ChallengeStatusView.kt:192-195`：补齐面板 `paneTitle` + `isTraversalGroup`、标题 `heading()` 及 2 秒安全检查提示 `LiveRegionMode.Polite` 无障碍语义。
- `ChallengeStatusView.kt:132-135, 190-197`：`AnimatedContent` 补 `contentAlignment = Alignment.Center` 与基于令牌 `HiMotion.spatialDefault()` 的 `SizeTransform(clip = false)`，消除状态切换时的纵向跳变并符合动效令牌约束，已由 `ChallengePanelMotionTest` 逐帧连续性断言覆盖。
