# 任务卡 A5b：透明 ChallengeActivity 与后台自动过盾 WebViewChallengeSolver

【角色】你是 hipda-next 的 Android 网络与 WebView 验证宿主开发者（Kotlin、协程、Android Activity/WebView、AndroidX Webkit、Hilt）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/A5b`（分支 `card/A5b-challenge-host`，基于 `origin/main` @ `0247458`）里工作。
- 允许修改的源码范围：
  - 新增 `hipda/src/main/java/net/jejer/hipda/ui/challenge/` 下的生产代码（`ChallengeActivity`、`WebViewChallengeSolver`、状态机与 WebView 薄抽象接口、`ChallengeInteractionPresenter` 接口与默认实现、Hilt 模块等）；
  - 新增 `hipda/src/main/res/values/styles_challenge.xml`（透明无动画无标题主题 `Theme.HiPda.ChallengeHost`，避免改动旧的 `styles.xml`）；
  - 修改 `hipda/src/main/java/net/jejer/hipda/data/network/access/ChallengeSolver.kt`（仅替换 `ChallengeSolverModule.bindChallengeSolver` 绑定为 `WebViewChallengeSolver`；**保留 `DefaultChallengeSolver`**，既有测试仍引用它）；
  - 新增 `hipda/src/test/java/net/jejer/hipda/ui/challenge/` 下的单元测试，并同步更新 `hipda/src/test/java/net/jejer/hipda/data/network/access/SiteAccessGatewayTest.kt`（第 851–853 行对 `ChallengeSolverModule.bindChallengeSolver` 的反射断言）。
- **共享文件例外（覆盖文末通用约定对应条款，backlog 已为 A5 预留）**：
  - 只允许修改 `hipda/src/main/AndroidManifest.xml`，**仅限**注册 `ChallengeActivity`（`singleTop`、透明主题、`exported="false"`）。
  - 其他共享文件（`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`.github/` 等）一律不得修改。
- **不做（明确超出范围）**：可见验证面板 UI 与 2 秒等待提示（A6）、表单或交互登录流程（D2）、debug 样本导出页（B1b）、修改 `SiteAccessGateway` 或 `WebViewCookieBridge` 的既有接口。
- 规模与审查：敏感区 + 状态机卡；审查为 Carbon 独立代码审查 + Hydrogen 独立探针验证。实际代码若明显超出预期，先自查简化并在回复里说明原因。

【背景】
- 必读规格：
  - `docs/design/tech-plan.md`：
    - 第 5 节“验证面板有独立的宿主（T3）”：用一个透明的 `ChallengeActivity`（`singleTop`）承载验证用的 WebView，不管前台是新界面还是旧界面，都能弹出验证面板。
    - 第 6 节“流程”“UA”：检测到验证页后，先用不可见的 WebView 自动尝试通过；进入需要人工操作的阶段或超时，再交由可见面板复用同一个 WebView 不重新加载；通过后把 Cookie 同步给网络层，原请求自动重试；WebView 与网络层使用同一个清洗后的 UA。
  - `docs/design/cloudflare-research.md` 第五节第 3 小节（不可见先试、需要交互时再弹出可见 WebView）：
    - 使用真实视口尺寸（如宽 360dp+、高 480dp+）但不可见（`alpha = 0f`）且不消费触摸/焦点的 WebView，避免 `0x0` 或 `View.GONE` 触发风控或暂停 Chromium 渲染；
    - WebView 配置：`javaScriptEnabled = true`、`domStorageEnabled = true`、`CookieManager.setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`、`userAgentString = unifiedUa`；忽略 `*.challenges.cloudflare.com` / `dnstest.dev` 子域的非致命探测错误；
    - 验证前先快照并清除两端旧 `cf_clearance`（失败恢复备份），预热登录 Cookie（不含 `cf_clearance`）；
    - 通过 `WebViewCompat.addDocumentStartJavaScript` + `addWebMessageListener`（不支持时退化）监听 `window.addEventListener('message')` 中 `data?.source === 'cloudflare-challenge'` 的 `interactiveBegin` / `interactiveTimeout`；
    - 后台约 8 秒预算内判定自动通过：主帧不再返回 `cf-mitigated: challenge`、页面加载完成且 DOM 不含 `cf-turnstile` / `challenge-running` / `challenge-stage` / `_cf_chl_opt` / `cf_chl_opt`、且读到不在快照里的新 `cf_clearance`；
    - 实现 `WebViewClient.onRenderProcessGone` 返回 `true` 并安全销毁清理，防止渲染进程崩溃连带杀掉整个 App。
  - `docs/design/product-design.md` 第 7.12 节：后台自动验证期间用户看不到任何界面，取消或失败后恢复旧状态、由上层呈现对应提示。
  - `docs/agents/lesson.md`：本地 Gradle 先导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`；OkHttp `Cookie.parse` 单测需 Robolectric + `OkHttp.initialize(ApplicationProvider.getApplicationContext())`；R8 release 冒烟用 CI `emulator=true` 守住。
- 现有代码（已核对真实路径与签名）：
  - A4b (`net.jejer.hipda.data.network.access`)：
    - `ChallengeSolver.kt`：`fun interface ChallengeSolver { suspend fun solve(triggerUrl: HttpUrl): SolveResult }`、`SolveResult`（`Cleared` / `Cancelled` / `Failed(cause)`）、`DefaultChallengeSolver`、`ChallengeSolverModule.bindChallengeSolver`。
    - `SiteAccessGatewayTest.kt:851-853`：反射断言 `ChallengeSolverModule.bindChallengeSolver` 参数类型，改绑定后需同步更新。
  - A5a (`net.jejer.hipda.data.network` & `net.jejer.hipda.data.network.webview`)：
    - `UserAgentProvider.userAgent(): String`（已由 `WebViewUserAgentProvider` 绑定）。
    - `WebViewCookieBridge`：`snapshotClearances(url)`、`clearClearance(url): ClearanceBackup`、`restoreClearance(url, backup)`、`primeWebView(url)`、`syncFromWebView(url, excludeClearanceValues): CookieSyncResult(syncedCookies, freshClearance)`。
    - 依赖 `androidx.webkit:webkit:1.18.0` 已在 `gradle/libs.versions.toml` 与 `hipda/build.gradle.kts` 中引入。

【目标】交付透明宿主 `ChallengeActivity` 与 `WebViewChallengeSolver : ChallengeSolver`（接入 Hilt 绑定），在不显示任何界面的前提下于后台 8 秒预算内用隐藏 WebView 自动通过 Cloudflare 验证并同步新 `cf_clearance`，超时或需要交互时通过 `ChallengeInteractionPresenter` 扩展点交接（本卡默认取消并恢复旧凭证备份）。

【范围】
1. **Manifest 与透明主题**：
   - `hipda/src/main/res/values/styles_challenge.xml`：定义透明无标题无过渡动画无变暗主题 `Theme.HiPda.ChallengeHost`（`windowIsTranslucent = true`、`windowBackground = @android:color/transparent`、`backgroundDimEnabled = false`、`windowNoTitle = true`、`windowAnimationStyle = @null`）。
   - `hipda/src/main/AndroidManifest.xml`：注册 `net.jejer.hipda.ui.challenge.ChallengeActivity`，设 `android:exported="false"`、`android:launchMode="singleTop"`、`android:theme="@style/Theme.HiPda.ChallengeHost"`、`android:excludeFromRecents="true"`、`android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`。
2. **交互升级扩展点 `ChallengeInteractionPresenter`（为 A6 预留）**：
   - 定义接口（如 `ChallengeInteractionPresenter`），在后台 8 秒预算耗尽或收到 `interactiveBegin` / `interactiveTimeout` 时被状态机调用，接收当前 `ChallengeActivity` / 隐藏中的同一 `WebView` 会话句柄（供 A6 直接把同一 WebView 切为可见面板，不重新加载），挂起等待人工交互结果（`Cleared` / `Cancelled` / `Failed`）；
   - 提供默认实现（如 `DefaultChallengeInteractionPresenter`，直接返回 `SolveResult.Cancelled`，不显示任何界面）并放在可由 A6 独立替换的 Hilt `@Module`（如 `ChallengeInteractionModule`）中。
3. **WebView 薄抽象接口与状态机（可纯离线单测）**：
   - 抽取小接口（如 `ChallengeWebViewHost` / `ChallengeWebViewHandle`）封装 WebView 的动作与回调：加载 `triggerUrl`、注入 Turnstile 消息监听脚本、查询页面是否仍在验证态（检测 `cf-turnstile` / `challenge-running` / `challenge-stage` / `_cf_chl_opt` / `cf_chl_opt`）、读主帧挑战状态、通知关闭/销毁；
   - 独立状态机协调单次验证的完整生命周期（时间/轮询调度器可注入，便于虚拟时钟测试）：
     1. 调 `cookieBridge.snapshotClearances(triggerUrl)` 取快照；
     2. 调 `cookieBridge.clearClearance(triggerUrl)` 清除两端旧 `cf_clearance` 并留存 `backup`；
     3. 调 `cookieBridge.primeWebView(triggerUrl)` 预热登录 Cookie；
     4. 启动宿主 Activity（经可测试的启动器抽象，真实实现用 `@ApplicationContext` + `Intent.FLAG_ACTIVITY_NEW_TASK`）；
     5. 配置并加载 `triggerUrl`，在 8 秒后台预算内结合页面加载完成事件与定时轮询判定自动通过：主帧非验证页（无 `cf-mitigated: challenge` 响应头且非 403/429/503 验证页）、页面加载完成且 DOM 不含活跃挑战特征、且 `cookieBridge.syncFromWebView(triggerUrl, excludeClearanceValues = snapshot)` 拿到 `freshClearance != null` → 关闭 Activity，返回 `SolveResult.Cleared`；
     6. 8 秒预算耗尽，或收到 `interactiveBegin` / `interactiveTimeout` 消息 → 调用 `ChallengeInteractionPresenter`（若返回 `Cleared` 则同步确认并返回 `Cleared`，否则按 `Cancelled`/`Failed` 处理）；
     7. 收到 `onRenderProcessGone` → 安全销毁并返回 `SolveResult.Failed`；
     8. 任意取消（含调用方协程取消、Activity 被外部销毁、presenter 返回取消）或失败路径 → 在 `NonCancellable` 中调 `cookieBridge.restoreClearance(triggerUrl, backup)` 恢复备份、关闭 Activity 并销毁 WebView；调用方取消时清理后按协程约定重新抛出 `CancellationException`，其余返回 `SolveResult.Cancelled` 或 `SolveResult.Failed(cause)`。
4. **真实 `ChallengeActivity` 与 WebView 配置**：
   - 后台静默阶段窗口加 `FLAG_NOT_TOUCHABLE` 与 `FLAG_NOT_FOCUSABLE`，不显示任何可见 UI；挂载真实视口尺寸（如 `360dp × 480dp`，`alpha = 0f`、`isFocusable = false`、`isClickable = false`）的 `WebView`；
   - WebView 配置：`javaScriptEnabled = true`、`domStorageEnabled = true`、`userAgentString = userAgentProvider.userAgent()`、`CookieManager.setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`；
   - 消息监听：支持 `WebViewFeature.DOCUMENT_START_SCRIPT` 与 `WebViewFeature.WEB_MESSAGE_LISTENER` 时，使用 `WebViewCompat.addDocumentStartJavaScript` + `WebViewCompat.addWebMessageListener` 监听 `data?.source === 'cloudflare-challenge'` 的 `interactiveBegin` / `interactiveTimeout`；任一特性不支持时安全退化（如 `addJavascriptInterface` + `onPageStarted`/`onPageFinished` 注入脚本）；
   - `WebViewClient`：`onRenderProcessGone` 返回 `true` 并通知状态机安全失败；`onReceivedError` / `onReceivedHttpError` 忽略 `*.challenges.cloudflare.com` 与 `dnstest.dev` 子域的非致命探测错误，并记录主帧是否收到 `cf-mitigated: challenge`；
   - `onDestroy` 时确保从父容器移除并 `stopLoading()` / `destroy()` WebView，且结束未决会话防泄漏；
   - 真实 `WebViewChallengeSolver : ChallengeSolver`（`@Singleton`）替换 `ChallengeSolverModule.bindChallengeSolver`；同步更新 `SiteAccessGatewayTest.kt:851-853`。
5. **A4b 审查遗留**：
   - `SiteAccessGateway` 的总超时由 A4b 修改轮提供（默认 120 秒），A5b 的 8 秒后台预算 + 可见面板等待必须在这个总超时之内并在超时时正确清理 Activity 与 Cookie 备份；
   - B1b 与 A5b 的 WebView 都必须用 `UserAgentProvider.userAgent()`。

【步骤】
1. 新增透明主题 `styles_challenge.xml` 并在 `AndroidManifest.xml` 注册 `ChallengeActivity`。
2. 先写失败的单元测试（假 WebView 接口 + 假 `ChallengeInteractionPresenter` + `InMemorySiteCookieStore` + `FakeWebViewCookieAccess` + 虚拟时钟，以及 Robolectric Activity 测试），至少覆盖：
   a. 自动通过：快照 → 清旧 → 预热 → 启动 → 页面加载完成且非验证态 + 读到新 `cf_clearance` → 同步入 `SiteCookieStore`、不恢复旧备份、关闭 Activity、返回 `SolveResult.Cleared`。
   b. 旧值不误判通过：页面加载完成但只读到快照中的旧 `cf_clearance` → 不判通过，继续等待直至新值出现或超时。
   c. 8 秒超时：8 秒内未通过 → 触发 `ChallengeInteractionPresenter`，默认实现返回取消 → `restoreClearance` 恢复两端备份、关闭 Activity、返回 `SolveResult.Cancelled`。
   d. `interactiveBegin` 与 `interactiveTimeout`：收到任一消息立即提前触发 `ChallengeInteractionPresenter`（无需干等 8 秒），默认返回取消 → 恢复备份、关闭 Activity、返回 `SolveResult.Cancelled`；注入自定义 presenter 返回 `Cleared` 时成功同步并返回 `Cleared`。
   e. `onRenderProcessGone`：回调返回 `true`，状态机恢复备份、关闭 Activity、返回 `SolveResult.Failed`。
   f. 调用方协程取消：正在等待验证时取消调用方协程 → `restoreClearance` 恢复备份、关闭 Activity、正确抛出 `CancellationException` 且会话复位不卡死下一次调用。
   g. Robolectric Activity 生命周期：验证 `ChallengeActivity` 可由应用 `Context`（带 `FLAG_ACTIVITY_NEW_TASK`）启动、为透明无焦点不可触摸窗口、完成后 `isFinishing == true`、外部 `finish()`/`onDestroy()` 能唤醒挂起的会话返回 `Cancelled`；验证 `ChallengeSolverModule` 与 `ChallengeInteractionModule` Hilt 绑定正确（同步更新 `SiteAccessGatewayTest`）。
3. 实现生产代码，本地按 DISPATCH §2 跑通本卡单测、`:hipda:testDebugUnitTest`、`:hipda:assembleDebug`、`:hipda:lintDebug`。
4. 推送 `card/A5b-challenge-host` 等待常规 CI 变绿，然后执行 `gh workflow run android.yml --ref card/A5b-challenge-host -f emulator=true` 并 `gh run watch <id> --interval 60`，确认 debug + release 模拟器冒烟通过。
5. 在 `/home/mir/Documents/hipda-next.wt/_reports/A5b.md` 中附上详细证据、对外 API 清单（供 A6 与 B1b 使用）以及供 M1/B1b 阶段使用的**真机验收清单**。

【自检】
- 本地按 DISPATCH §2 串行执行（先导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`）：
  - 本卡单测：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.ui.challenge.*" --tests "net.jejer.hipda.data.network.access.*" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 推送前全量检查：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:testDebugUnitTest :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions（常规 CI + `emulator=true` 模拟器冒烟）全绿为最终证据。

【验收】
- [ ] `AndroidManifest.xml` 仅注册 `ChallengeActivity`（`singleTop`、透明主题、`exported="false"`），未改其他共享文件。证据：`git diff --stat origin/main...HEAD`。
- [ ] 第 2 步 a–g 全部单测通过，覆盖自动通过、旧值过滤、8 秒超时、`interactiveBegin`/`interactiveTimeout`、`onRenderProcessGone`、调用方取消与备份恢复、Activity 启停与 Hilt 绑定。证据：测试类:方法名 + CI 汇总。
- [ ] 常规 CI 与 `emulator=true`（debug + release API 36 模拟器冒烟）全部绿灯。证据：两条 CI run 链接 + 最终 commit hash。
- [ ] `_reports/A5b.md` 包含完整对外 API 清单（签名 + 文件:行号，供 A6 接入可见面板与 B1b 使用）及“真机验收清单”（供 M1/B1b 用户执行）。证据：`_reports/A5b.md` 路径与章节。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/A5b-challenge-host`；Conventional Commits（如 `feat(challenge): 新增透明 ChallengeActivity 与后台自动过盾求解器`）。
- 详细证据、日志、对外 API 清单与真机验收清单写入 `/home/mir/Documents/hipda-next.wt/_reports/A5b.md`；最后一条回复按文末通用约定控制在 ≤600 字。

【清理】只删除本会话自己创建的临时文件。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 先在本地跑本卡相关测试（按 lesson.md 导出 mise 的 ANDROID_HOME/JAVA_HOME，flock 串行），本地变绿后才推送；不要拿 CI 调试半成品。完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/<卡号>.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

首先 `cd /home/mir/Documents/hipda-next.wt/A5b`，确认 `git status` 干净、HEAD 为 0247458；首次推送用 `git push -u origin card/A5b-challenge-host`。
