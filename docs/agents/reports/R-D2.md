# 审查报告 R-D2：`AuthRepository`、单飞静默重登与 `ForumHttpClient`

- **审查结论**：**CHANGES-REQUESTED**
- **审查对象**：`origin/card/D2-auth-repository` @ `df1c12dd30701f2e5dad7cd59e29564f3b6f83d1`
- **对比基线**：`d45b6a2a5f5c8bd28aa466778d094f646b9ba935`（`git merge-base origin/main df1c12dd30701f2e5dad7cd59e29564f3b6f83d1`；与当前 `origin/main` 执行 `git merge-tree --write-tree` 校验零冲突，输出树 `29a8e2d9afa68feb3f11d7960eeba34de63fe12a`）
- **CI 核验**：[Android CI #45614344633](https://github.com/mailwmj/hipda-next/actions/runs/45614344633)（Job `139205761255`，`headSha == df1c12dd30701f2e5dad7cd59e29564f3b6f83d1`，`completed / success`）
  - 原始日志逐项核实通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:core-parser:test`、`:hipda:testDebugUnitTest`（`data.auth` 套件 33/33 通过，`:hipda` 608/608，全仓总计 **659/659** 通过）、Room Schema 一致性、Roborazzi 截图验证、Debug/Release APK 产物及包名校验。
- **阻断项（对照 `docs/agents/DISPATCH.md` §4）**：**2 项**（B1 WebView `CookieManager` 登录 Cookie 未随 `logout`/`switchAccount`/`removeAccount` 清理导致过盾后旧会话复活串号；B2 `trySilentRelogin` 单飞合流跨 `AccessPriority` 混用导致前台重登过盾通道被 `BackgroundSilent` 短路放弃、以及 `BackgroundSilent` 被前台交互验证挂起最长 120s 并污染 `AuthState.SessionExpired`）。两项均可在 `D2` 允许范围内（仅改 `DefaultAuthRepository.kt` 与 `data/auth/*Test.kt`，零改动 `data/network/`）闭环修复。

---

## 一、阻断项（Blocking Issues）

### B1. `logout()` / `removeAccount()` / `switchAccount()` 未清理 WebView `CookieManager` 中的论坛登录 Cookie，下次过 Cloudflare 盾时旧账号 30 天 `cdb_auth` 被回灌复活

- **涉及位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt:294, 360, 379, 863-881`（`clearAuthCookiesForServer` 仅清理 `SiteCookieStore`）
  - 交互链路（只读参考，无需修改）：`hipda/src/main/java/net/jejer/hipda/ui/challenge/WebViewChallengeSolver.kt:112, 123, 353-356`、`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:77-99, 122-135, 153-183`、`hipda/src/main/java/net/jejer/hipda/data/auth/ForumHttpClient.kt:64-67`
- **公平性说明**：任务卡 `D2.md:50` 字面提示聚焦于 `SiteCookieStore`，实现者在 `SiteCookieStore` 侧对域变体与 `cf_clearance` 的处理非常细致；但结合真实生产链路中的 `WebViewChallengeSolver` 与 `WebViewCookieBridge`（且后续 `D4`–`D9`、`E15` 均不触碰 Cookie 存储层），仅清 `SiteCookieStore` 会在真实设备上导致退出登录/删除凭据/切换账号后的登录态串号复活，须在本卡闭环。
- **具体触发路径**：
  1. 用户登录账号 A（`DefaultAuthRepository.kt:736` 提交 `cookietime="2592000"`，服务端下发有效期 30 天的持久化 `cdb_auth` 存入 `SiteCookieStore`）。
  2. 在已登录期间任意请求触发一次 Cloudflare 验证，`WebViewChallengeSolver.solve`（`WebViewChallengeSolver.kt:123`）调用 `cookieBridge.primeWebView(triggerUrl)`（`WebViewCookieBridge.kt:122-135`），将账号 A 的 30 天 `cdb_auth`（含 `Expires`/`Domain`/`Path`）写入系统级持久化的 WebView `CookieManager`（或用户曾在 `B1b` WebView 登录过）。
  3. 用户执行 **`logout()`**（手动退出登录）或 **`removeAccount(accountA.uid)`**（清除登录信息并退出）：`DefaultAuthRepository.kt:360, 379` 调用 `clearAuthCookiesForServer` 仅清空了 `SiteCookieStore` 内的 `cdb_auth`，`authState` 变为 `SignedOut`、`manuallyLoggedOut = true`，但 **WebView `CookieManager` 中的 30 天 `cdb_auth` 原封不动残留**。
  4. 用户退出后继续浏览公开版块/帖子（`product-design.md` §7.1“退出登录后停在当前页”），约 30 分钟后 `cf_clearance` 过期触发 `403 cf-mitigated: challenge`，`SiteAccessGateway` 调用 `WebViewChallengeSolver.solve(triggerUrl)`：
     - `clearClearance`（`WebViewCookieBridge.kt:77-99`）仅清除 `cf_clearance`，保留 `cdb_auth`；
     - `primeWebView`（`WebViewCookieBridge.kt:123-126`）见 `SiteCookieStore` 无非 CF Cookie 直接 `return`；
     - WebView 携带残留的账号 A `cdb_auth` 完成过盾后，`syncFromWebView`（`WebViewCookieBridge.kt:153-183`）将 WebView 中所有非 `cf_clearance` Cookie（`otherCookies`，含账号 A 的 `cdb_auth`）**直接通过 `siteCookieStore.saveFromResponse` 回灌进 `SiteCookieStore` 并落盘**。
  5. 同理，在 **`switchAccount(accountB.uid)`** 中：若账号 A 在 WebView 中的 `cdb_auth` 作用域为 `Domain=.4d4y.com` 或 `Path=/forum`，而账号 B 新 Cookie 为 host-only 或不同 `Path`（或切换途中遇验证后因密码错误/网络异常失败退出），`syncFromWebView` 也会将账号 A 的 `cdb_auth` 回灌至 `SiteCookieStore`。
- **后果**：
  - 用户已明确点击“退出登录”甚至“清除登录信息”删除账号 A，经历一次常规 Cloudflare 自动过盾后，账号 A 的登录 Cookie 在 `SiteCookieStore` 中静默复活；后续所有 `ForumHttpClient` 请求（含 `requireAuth = true` 的读写请求，因 `ForumHttpClient.kt:65` 见 `firstResponse.meta.session.isLoggedIn == true` 直接放行）均以**已退出/已删除的账号 A 身份**在服务端执行，而客户端 `AuthRepository.authState` 仍显示 `SignedOut`，造成严重的会话隔离失效与账号串号。
- **不越界最小修复建议**（零修改 `data/network/`）：
  - `net.jejer.hipda.data.network.webview.WebViewCookieAccess` 与 `@WebViewCookieDispatcher CoroutineDispatcher` 已是公开的 Hilt `@Singleton` 绑定（`WebViewCookieAccess.kt:14`、`WebViewNetworkModule.kt:17`）。
  - 在 `DefaultAuthRepository` 的 `@Inject` 构造器注入 `WebViewCookieAccess` 与 `@WebViewCookieDispatcher CoroutineDispatcher`（`internal constructor` 可设默认空实现或单测注入假替身，注意单测替身须按 `(name, domain, hostOnly, path)` 真实建模，见 `lesson.md` R-A5a 经验）。
  - 在 `clearAuthCookiesForServer(forumServer)` 中同步在 `cookieDispatcher` 上清理 `WebViewCookieAccess` 中匹配当前主机且 `!isPreservedCloudflareCookie(name)` 的 Cookie（`isGetCookieInfoSupported() == true` 时按原属性串的 `Domain`/`Path`/`Secure`/`HttpOnly`/`SameSite`/`Partitioned` 构造 `Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT` 过期头；`false` 回退分支对观测到的非 CF Cookie 名枚举 host-only、`url.host`、`.topPrivateDomain` 等父域及 `"/"`、`"/forum"`、`"/forum/"` 路径写入普通与 `; Secure` 过期头），有写入时调用 `flush()`。

---

### B2. `trySilentRelogin` 单飞合流未隔离 `BackgroundSilent` 与前台优先级，导致前台重登过盾失效、后台静默请求排队挂起及 `AuthState.SessionExpired` 误置

- **涉及位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt:457-499, 585-597, 671-681`
- **具体触发路径与后果（同一根因的三面表现）**：
  1. **路径 A（前台 `UserBlocking`/`UserExplicit` 合流到在途 `BackgroundSilent` 航班 → Cloudflare 验证通道失效）**：
     - 会话与 `cf_clearance` 同时过期时，后台 `D9` 轮询先以 `AccessPriority.BackgroundSilent` 进入 `trySilentRelogin`，创建 `ActiveRelogin(..., priority = BackgroundSilent)`（`:477-482`）并发出 `GET logging.php?action=login`。
     - 数十毫秒内用户在前台打开页面/发帖，以 `AccessPriority.UserBlocking` 进入 `trySilentRelogin`，在 `:473-475` 无条件复用上述 `BackgroundSilent` 的 `inFlightRelogin`。
     - `GET logging.php?action=login` 收到 `403 cf-mitigated: challenge` 时，因 `flight.priority == BackgroundSilent`，`recoveryGateway` 直接抛 `ChallengeAbandonedException`（0 次调用 `ChallengeSolver`），`flight.deferred` 完成为 `Failed(CHALLENGE_ABANDONED)`。
     - **后果**：前台 `ForumHttpClient` 直接向用户抛出 `AuthRequiredException(RELOGIN_FAILED)`，**完全跳过了 Cloudflare 自动/人工验证通道**，导致前台正常操作无故报登录失效。
  2. **路径 B（`BackgroundSilent` 合流到在途 `UserBlocking` 航班 → 遇交互验证排队挂起最长 120 秒）**：
     - 前台 `UserBlocking` 请求先启动 `trySilentRelogin`（`flight.priority = UserBlocking`），在 `GET logging.php?action=login` 尚未收到 403 响应头前（此时 `recoveryGateway.challengeState.value` 仍为 `Idle`），后台 `BackgroundSilent` 请求进入 `trySilentRelogin`：通过 `:457-462` 检查后在 `:475` 合流至该前台 `flight` 并挂起在 `flight.deferred.await()`（`:498`）。
     - 随后 `GET` 命中 403 进入人工验证面板（`DEFAULT_SOLVE_TIMEOUT = 120s`）。
     - **后果**：`BackgroundSilent` 调用方被阻塞挂起在 `flight.deferred.await()` 最长 **120 秒**，违反 `tech-plan.md` §6 与审查重点第 2 条“后台请求遇验证立即放弃、不排队等待”的约束。
  3. **路径 C（`BackgroundSilent` 因验证放弃而篡改全局 `authState` 为 `SessionExpired`）**：
     - 在 `:463-464`（网关正处于 `Solving`/`Cooldown` 时 `BackgroundSilent` 提前放弃）及 `:675-679`（`BackgroundSilent` 航班遇 403 返回 `CHALLENGE_ABANDONED`）中，代码无条件将 `runtimeOverlay` 置为 `RuntimeOverlay.SessionExpired(uid, ...)`。
     - **后果**：① 若前台 `UserBlocking` 静默重登正在 `recoveryGateway` 过盾（`authState == Reauthenticating`），后台轮询插队进入 `:457-465` 会瞬间把 UI 的 `authState` 覆盖成 `SessionExpired("后台请求遇安全验证已放弃")`；② 即便空闲期后台轮询因跳过验证而放弃，凭据本身并未失效，却把左栏与账号页观察的 `authState` 从 `SignedIn` 误置为 `SessionExpired`。
- **最小修复建议**：
  1. `CHALLENGE_ABANDONED` 不表示凭据/会话重登失败：`:457-470` 预检放弃时不要修改 `runtimeOverlay`；`:671-681` 收尾若 `rawOutcome.reason == CHALLENGE_ABANDONED`，将 `runtimeOverlay` 恢复为 `RuntimeOverlay.Normal`（而非 `SessionExpired`）。
  2. 当 `priority == AccessPriority.BackgroundSilent` 复用 `flight.priority != BackgroundSilent` 的在途航班时，等待阶段监听 `siteAccessGateway.challengeState` 与 `recoveryGateway.challengeState`（一旦任一非 `Idle` 立即返回 `Failed(CHALLENGE_ABANDONED)` 退出等待，不影响底层前台 `flight`）。
  3. 当 `priority != AccessPriority.BackgroundSilent` 复用了在途 `BackgroundSilent` 航班且拿到 `Failed(CHALLENGE_ABANDONED)` 时，在 `trySilentRelogin` 内用自身前台 `priority` 自动重新发起一次静默重登，确保正常走完 `ChallengeSolver`。

---

## 二、五大审查重点逐项核查

### 1. 单飞静默重登并发状态机与防卡死
- **并发合流与单等待者取消隔离（通过，除 B2 跨优先级问题外）**：
  - 同优先级多协程并发进入 `trySilentRelogin` 时，通过 `synchronized(stateLock)` + `ActiveRelogin`（持有 `CompletableDeferred<SilentReloginResult>`）严格合流为 1 次网络登录（`AuthRepositoryTest:625-701` 覆盖 10 路并发合流）。
  - 实际重登任务运行在独立的仓储级 `scope`（`SupervisorJob() + Dispatchers.Default`），即使首个发起者（initiator）协程取消，也仅取消其自身的 `flight.deferred.await()`，不中断 `flight.job` 与其余 9 个等待者（`AuthRepositoryTest:675-694` 已验证）。
- **防永久卡死核查（参照 V-A4b 教训，通过）**：
  - `launchSilentRelogin`（`DefaultAuthRepository.kt:545-570`）采用 `CoroutineStart.LAZY` + 锁内校验 `flight.completed` + `!job.start()` 兜底收敛，消除了 `launch` 前已被显式操作摘除或 `scope` 已取消时的悬空 `Deferred` 竞态。
  - `runSilentRelogin`（`:572-636`）使用 `try ... catch (_: CancellationException) ... catch (e: Exception) ... finally { completeSilentRelogin(flight, outcome) }`；即便抛出未捕获 `Error`，`finally` 仍以预设失败值清理 `inFlightRelogin = null` 并 `complete` `deferred`。
  - 底层恢复网络请求经 `recoveryGateway` 执行，同时受单次请求 `DEFAULT_TIMEOUT = 30s` 与验证看门狗 `DEFAULT_SOLVE_TIMEOUT = 120s` 上界保护，不存在无界挂起。
- **显式操作与在途静默重登竞态核查**：
  - `login` / `switchAccount` / `logout` / `removeAccount(active)` 统一经 `operationMutex` 串行并进入 `runExplicitOperation`（`:501-525`）：先在 `stateLock` 内递增 `explicitOperationCount` 与 `authGeneration`、原子摘除 `inFlightRelogin` 并置 `old.completed = true`，随后唤醒旧等待者为 `SESSION_INVALIDATED` 并在 `NonCancellable` 下 `detached.job?.cancelAndJoin()`，最后才执行清 Cookie 与写 `CredentialsStore`。
  - `completeSilentRelogin`（`:651-660`）二次校验 `flight.generation == authGeneration && !manuallyLoggedOut && activeAccount?.uid == flight.account.uid`，确保滞后旧航班绝不会把激活态或 `sessionEpoch` 写回。
  - `init` 持久化流收集（`:168-187`，`df1c12d` 修复）采用 `.conflate()` + 读取前后校验 `explicitOperationCount == 0 && authGeneration == genBeforeRead`，消除了滞后快照倒流覆盖新状态的竞态。（关于 OkHttp 传输层刚返回响应头瞬间被 `cancel()` 的极窄拦截器窗口见建议 S3）。

### 2. 手动退出禁重登红线与重试上界
- **`manuallyLoggedOut == true` 零网络请求红线（通过）**：
  - `trySilentRelogin`（`:418, 432-435`）在发起任何网络调用前现读 `CredentialsStore`；只要 `manuallyLoggedOut == true`，甚至早于 `failedEpoch < currentSessionEpoch` 分支（`:442`）即刻返回 `SilentReloginResult.Suppressed`，保证**任意路径 0 次重登网络请求**。
  - 仅显式 `login()`（`:243`）与 `switchAccount()`（`:317`）调用 `credentialsStore.setActive(...)` 复位 `manuallyLoggedOut = false`。
  - 密码失配时通过 `rejectedCredentialKey = uid to passwordMd5`（`:447-455, 672-674`）短路后续静默重登，防止重复撞库触发 Discuz! 15 分钟锁定；网络/5xx 临时错误不锁定，网络恢复后可正常恢复。
- **`ForumHttpClient` 最多重试 1 次（通过）**：
  - `ForumHttpClient.executeWithAuthRetry`（`ForumHttpClient.kt:57-103`）为无循环的两段式结构：首次请求未登录 → 调用 1 次 `trySilentRelogin(epochBeforeFirstAttempt, priority)` → 仅当 `Recovered` 时用全新 `Request` / 可重放 `gbkFormBody` 重试 1 次；若仍未登录立即抛 `AuthRequiredException(SESSION_STILL_UNAUTHENTICATED)`，绝不死循环。
- **`BackgroundSilent` 行为**：单发路径（空闲遇 403 或主网关验证中）测试通过，但与前台并发合流及状态覆写存在阻断项 **B2**。

### 3. `@RecoveryHttpClient` 线程隔离与 Cookie 切换安全
- **独立恢复线程池隔离（通过）**：
  - `DefaultAuthRepository` 注入 `@RecoveryHttpClient OkHttpClient`（`:68`），静默重登独占 `recoveryGateway`（`:587`），运行于 `"OkHttp Recovery Dispatcher-*"` 守护线程；`AuthRepositoryTest:704-783` 验证了主 `OkHttpClient.dispatcher`（`maxRequests = 1`）完全阻塞饱和时静默重登仍无死锁完成。
- **Cookie 清理与切换回退**：
  - **`SiteCookieStore` 侧（通过）**：`clearAuthCookiesForServer`（`:863-908`）精确匹配 host-only（`www.4d4y.com`）与父域（`4d4y.com`）及任意子路径（`/`、`/forum`），按原 `(name, domain, hostOnly, path)` 写 `expiresAt(0L)` 删除非 CF Cookie，完整保留 `cf_clearance` / `__cf_bm` 与兄弟子域（如 `img02.4d4y.com`）Cookie。切换失败时 `CredentialsStore.activeUid` 保持原账号不冒充目标账号，并将原账号标记为 `SessionExpired`（`:328-350, 527-536`）。
  - **WebView `CookieManager` 侧（未覆盖）**：见阻断项 **B1**。

### 4. 密码 MD5、GBK 编码与安全卫生
- **`PasswordMd5` 字节级等价（通过）**：
  - `PasswordMd5.kt:19-36` 空串抛 `IllegalArgumentException("Password must not be empty")`（保留纯空格密码兼容旧版 `TextUtils.isEmpty`）；32 位十六进制摘要转小写直通，32 位非 hex 明文正常哈希；严格按 `LoginHelper.java:175-177` 顺序先 `\` → `\\` 再 `"` → `\"`、单引号不转义，取 UTF-8 字节 32 位小写 hex MD5，并由 `PasswordMd5Test` 反射调用真实 `LoginHelper.processedPassword()` 逐例锁定。
- **GBK 编码与 B9a 解析复用（通过）**：
  - 登录表单提交全部 8 个字段（`m_formhash`、`referer`、`loginfield`、`username`、`password`、`questionid`、`answer`、`cookietime="2592000"`）使用 `gbkFormBody`，响应统一经 `decodeBody` 解码，完全复用 B9a `LoginParser` 与 `DiscuzCommonParser`，零重复解析代码。
- **凭据安全卫生（通过）**：
  - 全量扫描 6 个生产文件无任何 `Log.*` / `Logger` / `println` / `printStackTrace`；异常与返回模型（`AuthState`、`LoginResult`、`SilentReloginResult`、`AuthRequiredException`、`ForumHttpException`）及 `SavedAccount.toString()` 均不含明文密码、`passwordMd5`、`securityAnswer` 或 `cdb_auth` 值；全部 3 个测试类均基于本地 `MockWebServer` 离线运行（`AuthRepositoryTest:377,446` 中的 `4d4y.com` 仅用于内存 `InMemorySiteCookieStore` 域匹配断言，未发起真实连接）。

### 5. 边界、零冲突与对外 API 质量
- **文件边界与零冲突（通过）**：
  - `git diff --stat d45b6a2a...df1c12dd` 仅新增 9 个授权文件（`+3170 / -0`），未触碰任何禁改目录、旧代码或共享文件，与 `origin/main`（含已合入的 `B9b`、`C4c`、`G1`）及在途 `B8`/`G2` 零重叠。
- **对外 API 评估**：
  - `AuthRepository`（`currentAccount`、`savedAccounts`、`authState`、`sessionEpoch` 及 5 个挂起方法）与 `ForumHttpClient`（`getHtml`、`postGbkForm`）职责清晰、类型命名直观，可直接支撑三栏左栏账号槽、`D4`–`D9` 及 `E12`/`E15`。（针对 `inajax=1` XML 端点的小幅易用性增强见建议 S2）。
- **“改坏关键一行测试仍通过”的测试盲区**：
  1. **`ForumHttpClient.kt:72`（高风险盲区）**：将 `priority = priority` 改坏为硬编码 `priority = AccessPriority.UserBlocking`，现有 33 个单测**全部照常通过**（`ForumHttpClientTest` 从未传 `AccessPriority.BackgroundSilent`），但在生产环境中会导致 `D9` 后台轮询遇到登录过期时升级为 `UserBlocking` 并在后台强弹验证窗口。
  2. **`DefaultAuthRepository.kt:460`**：删去 `|| recoveryGateway.challengeState.value !is ChallengeState.Idle`，现有单测仅测了主网关 `Solving`，全部照常通过。
  3. **`DefaultAuthRepository.kt:901`**：删去 `|| name.equals(COOKIE_CF_BM, ignoreCase = true)`，单测未覆盖 `__cf_bm`，全部照常通过。
  4. **`DefaultAuthRepository.kt:205-209` 与 `AuthModels.kt:114-116`**：将 `securityQuestionId == NO_SECURITY_QUESTION` 时的 `""` 改为 `securityAnswer`，或删去 `trimmedServer.endsWith("/forum")` 去重分支，现有单测均照常通过。

---

## 三、非阻断建议项（Suggestions）

- **S1（`login()` 执行前先清理当前主机旧登录 Cookie）**：`DefaultAuthRepository.kt:216-252, 827-837` 中交互式 `login()` 未像 `switchAccount()`（`:294`）和旧 `LoginDialog.java:83-84` 那样先调用 `clearAuthCookiesForServer(forumServer)`；若当前已持有同名账号有效 `cdb_auth`，用户重新输入该用户名但输错密码时会命中 `AlreadyLoggedIn` 跳过 POST 校验并把错误 `passwordMd5` 写入 `CredentialsStore`。建议在 `login()` 调用 `executeLoginProtocol` 前先执行 `clearAuthCookiesForServer(forumServer)`。
- **S2（`ForumHttpClient` 支持可选的自定义未登录响应判定谓词）**：`ForumHttpClient.kt:28-67` 固定以 `!meta.session.isLoggedIn`（HTML `#umenu`）判定未登录，导致 `D7`（发短消息）、`D8a`（收藏增删）等 `inajax=1` XML 端点只能传 `requireAuth = false` 而无法直接复用单次静默重登重试；可为 `getHtml`/`postGbkForm` 增加带默认值的 `isUnauthenticated: (ForumHtmlResponse) -> Boolean = { !it.meta.session.isLoggedIn }` 参数，保持现有调用零改动兼容。
- **S3（加固在途重登取消与 `AppCookieInterceptor` 极窄写回窗口）**：`DefaultAuthRepository.kt:508-518` 中 `detached.job?.cancelAndJoin()` 在协程取消时立即恢复，若此时 `"OkHttp Recovery Dispatcher-*"` 线程恰刚走完传输层返回响应头，`AppCookieInterceptor.kt:30-33` 仍可能写入 `Set-Cookie`；可在 `DefaultAuthRepository` 为恢复用 `OkHttpClient` 挂载轻量外层包裹或待后续网络卡在 `AppCookieInterceptor` 补 `if (!chain.call().isCanceled())` 守卫。
- **S4（修复轮一并补齐单行变异测试盲区）**：在 `ForumHttpClientTest` 补充 `priority = AccessPriority.BackgroundSilent` 透传断言（锁死 `ForumHttpClient.kt:72`），并在 `AuthRepositoryTest` 补齐 `recoveryGateway` 验证态、`__cf_bm` 保留、`securityQuestionId == 0` 忽略残留 `securityAnswer` 及 `forumServer` 含 `/forum` 后缀的断言。


---

## 四、修复轮 1 复审（`df1c12dd..85ac8b11`）

- **复审结论**：**APPROVE**
- **复审对象**：`origin/card/D2-auth-repository` @ `85ac8b1106ec0def225529964b2bebc041d889d7`（增量对比 `df1c12dd30701f2e5dad7cd59e29564f3b6f83d1..85ac8b1106ec0def225529964b2bebc041d889d7`）
- **合并兼容性**：`git merge-tree --write-tree origin/main 85ac8b1106ec0def225529964b2bebc041d889d7` 无冲突直合（输出树 `ea6e8cd7a46c0a68fdf31ddf50998877accf8a79`）。增量仅改 5 个文件（`DefaultAuthRepository.kt`、`ForumHttpClient.kt`、经授权的 `AppCookieInterceptor.kt` +54/-1、以及 `AuthRepositoryTest.kt`、`ForumHttpClientTest.kt`），保持 `AppCookieInterceptor(siteCookieStore)` 构造签名与 `HttpClientModule.kt` 零改动。
- **CI 核验**：[Android CI #45621988888](https://github.com/mailwmj/hipda-next/actions/runs/45621988888)（Job `139232267250`，`headSha == 85ac8b1106ec0def225529964b2bebc041d889d7`，`completed / success`）
  - 原始日志核实 `:hipda:assembleDebug`、`:hipda:lintDebug`、`:hipda:assembleRelease`、`:core-parser:test`（51/51）、`:hipda:testDebugUnitTest`（625/625，其中 `data.auth` **50/50**、`HttpClientModuleTest` 5/5）、全仓总计 **676/676** 单测及 Roborazzi 截图验证全部通过。
- **残余阻断项**：**0 项**。

### 4.1 逐项闭合核验

1. **B1（WebView `CookieManager` 登录 Cookie 清理及旧版 `<110` 变体覆盖）—— 已彻底闭合**：
   - `DefaultAuthRepository.kt:77-102` 在 `@Inject` 构造器注入 `@Singleton WebViewCookieAccess` 与 `@WebViewCookieDispatcher CoroutineDispatcher`（`internal constructor` 保留 `NoOpWebViewCookieAccess` 默认值以兼容 7 参测试/探针）。
   - `clearWebViewAuthCookies`（`:1081-1143`）运行于专用单线程 `cookieDispatcher`（不在 `cookieWriteLock` 或 `stateLock` 锁内挂起），同时读取 `forumUrl`（`/forum/`）与 `rootUrl`（`/`）两路径以观测子路径与根路径 Cookie，严格排除 `cf_clearance` 与 `__cf_bm`：
     - `isGetCookieInfoSupported() == true`（`:1092-1108`）：提取原始 `Set-Cookie` 的精确 `Domain`/`Path`/`Secure`/`HttpOnly`/`SameSite`/`Partitioned` 构造 `Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT` 过期头；
     - `isGetCookieInfoSupported() == false`（`:1109-1134`，Android WebView < 110）：合并 `getCookie(forumUrl)`、`getCookie(rootUrl)` 与刚从 `SiteCookieStore` 清理的 `extraAuthCookieNames`，按 `buildLegacyScopeDomains`（`:1199-1219`：覆盖 `null` host-only、`url.host`、`url.topPrivateDomain()` 逐级 `.parent` 如 `.4d4y.com`）× `LEGACY_AUTH_COOKIE_PATHS`（`"/"`、`"/forum"`、`"/forum/"`）× `secureVariants`（HTTPS 下同时发普通与 `; Secure`）全组合写入过期头并 `flush()`。
   - 单测 `RealisticFakeWebViewCookieAccess`（`AuthRepositoryTest.kt:2370-2460`）严格按 `(name, domain, path, hostOnly, partitioned)` 建模并校验 `SameSite=None`/`Secure`，4 条 `b1_*` 用例完整验证了 `logout`、`removeAccount`、`switchAccount` 及 `<110` 退化分支。

2. **B2（前后台静默重登单飞分槽、遇盾即弃与全局 `authState` 隔离）—— 已彻底闭合**：
   - 拆分为 `inFlightForegroundRelogin` 与 `inFlightBackgroundRelogin` 双槽（`DefaultAuthRepository.kt:170-171, 517-565`）：
     - 前台（`UserBlocking` / `UserExplicit`）只查/写 `inFlightForegroundRelogin`（`:549-564`），永不合流进后台航班，杜绝后台遇 403 放弃短路前台验证通道；
     - 后台（`BackgroundSilent`）预检任一网关非 `Idle` 时立即返回 `CHALLENGE_ABANDONED` 且不改 `runtimeOverlay`（`:521-524`）；若存在在途前台航班，通过 `awaitForegroundForBackground`（`:612-648`）并发竞争 `flight.deferred` 与 `combine(siteAccessGateway.challengeState, recoveryGateway.challengeState).first { blocked }`，任一网关进入 `Solving`/`Cooldown` 立即返回 `CHALLENGE_ABANDONED` 并取消局部监听协程，不阻塞等待 120s 人工验证，也不打断前台航班（若极快失败于验证，`:620-628` 亦映射为 `CHALLENGE_ABANDONED`）；
     - 全局 `authState` 保护：后台航班启动不置 `Reauthenticating`（`:535-542`），`CHALLENGE_ABANDONED` 完成不置 `SessionExpired`（`:844-849`），且后台航班成功/失败在 `inFlightForegroundRelogin != null` 时均不覆盖在途前台的 `Reauthenticating` 状态（`:506, 836, 853`）。`detachAllInFlightReloginsLocked`（`:692-705`）在显式操作时同时摘除并取消双槽。

3. **新增 `AppCookieInterceptor` 代际门控（`AuthCookieWriteGuard` + `cookieWriteLock`）专项核查 —— 安全无回归**：
   - **死锁核查（无死锁）**：全局锁序严格单向固定为 `operationMutex` → `cookieWriteLock` → `{ stateLock, siteCookieStore.stateLock }`。核对全部 17 处 `synchronized` 块：`stateLock` 内部从不获取 `cookieWriteLock` 或 `siteCookieStore.stateLock`；`createCookieWriteGuard`（`:586-592`）在 `cookieWriteLock` 内先获取并释放 `stateLock`，随后调用 `saveAction` 进入 `siteCookieStore.saveFromResponse`（仅持叶锁 `siteCookieStore.stateLock`）；`clearAuthCookiesForServer`（`:1066-1073`）在释放 `cookieWriteLock` 后才挂起进入 `withContext(cookieDispatcher)`。不存在锁序反转或持锁挂起。
   - **非认证 Cookie 核查（不误丢）**：
     - 常规业务态（未取消、`authGeneration == expectedGeneration` 且 `!manuallyLoggedOut`，或显式 `login`/`switchAccount` 传 `allowDuringManualLogout = true`）：`allowAuthCookies == true`，`AppCookieInterceptor.kt:71-72` 原样保存响应中的**全部** Cookie（含 `cdb_*`、`cf_clearance`、`__cf_bm` 及其他任意站点/子域 Cookie）；
     - 无 `AuthCookieWriteGuard` tag 的请求（如 Coil 图片请求、A 线直接调用）：`guard == null`（`:56-60`），只要 `!call.isCanceled()` 即全量保存；`ManualRedirectInterceptor` 的 `request.newBuilder()` 自动继承 `tag`，3xx 逐跳行为一致；
     - 仅在请求已取消、跨代际滞后（`logout`/`switchAccount`/`removeAccount` 之后到达）或处于手动退出态（`manuallyLoggedOut == true`）时 `allowAuthCookies == false`，精准拦截滞后 `cdb_auth`/`cdb_sid` 写回，同时仍放行保存 `cf_clearance` 与 `__cf_bm`（`:74, 81-83`）。
   - **A 线挑战流程 `cf_clearance` 写回核查（零影响且更安全）**：
     - `WebViewChallengeSolver` 过盾后通过 `WebViewCookieBridge.syncFromWebView` 直接写 `SiteCookieStore.saveFromResponse`，不经过 `AppCookieInterceptor` 拦截过滤；
     - 同时 `clearAuthCookiesForServer`（`:1066-1073`）移除了旧版整站 `clearForHost(host)` 分支，改为仅对 `!isPreservedCloudflareCookie` 条目精确写过期 Cookie，消除了清理登录态时误删并发新写入 `cf_clearance` 的窗口；即使响应头直接下发 `cf_clearance` / `__cf_bm`，在 `allowAuthCookies == false` 下也照常落库。

4. **建议项 S1 / S2 / S4 核查 —— 全部落实**：
   - **S1**：`DefaultAuthRepository.login`（`:253`）在获取表单前先调 `clearAuthCookiesForServer(forumServer)`，并由 `s1_loginClearsExistingAuthCookiesBeforeFetchingFormSoWrongPasswordForSameUserIsRejected` 验证同名账号输错密码不会跳过校验或覆盖原凭据。
   - **S2**：`ForumHttpClient.getHtml`（`:36-41`）与 `postGbkForm`（`:56-62`）新增默认参数 `isUnauthenticated: (ForumHtmlResponse) -> Boolean = DEFAULT_UNAUTHENTICATED_PREDICATE`，零破坏兼容现有调用并由 `s2_postGbkForm_customIsUnauthenticatedPredicate_enablesSingleSilentReloginForAjaxXmlEndpoints` 验证 XML 端点单次重登重试与重试上界。
   - **S4**：5 条 `s4_*` 单测完整覆盖 `ForumHttpClient` `BackgroundSilent` 优先级透传、`recoveryGateway` `Solving` 预检、`__cf_bm` 双端保留、`securityQuestionId == 0` 忽略残留答案及 `forumServer` 尾随 `/forum` 去重。
