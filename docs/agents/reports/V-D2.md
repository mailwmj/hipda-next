# 独立验证报告 V-D2：`AuthRepository` 并发状态机与 `ForumHttpClient`

## 1. 总判定

- **总判定**：**FAIL**
- **验证对象**：`origin/card/D2-auth-repository` @ `df1c12dd30701f2e5dad7cd59e29564f3b6f83d1`
- **探针分支与提交**：`verify/D2-probes` @ `bc4708798be561f546e967d4f184849607c1d5fd`（worktree `/home/mir/Documents/hipda-next.wt/V-D2`）
- **探针源码**：[D2ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt)（1,692 行，12 项独立探针用例；未先阅读实现者测试与 `_reports/D2.md`）
- **CI 证据**：[Android CI run 45619016103](https://github.com/mailwmj/hipda-next/actions/runs/45619016103)
  - `Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）：**success**
  - `Verify Room schema consistency`：**success**
  - `Run unit tests and verify Roborazzi screenshots`：**failure**（仅 `D2ProbeTest.probeB2b_logoutRacingLateResponseBeforeAppCookieSave_doesNotLeakStaleCookies` 失败；全库 `671 tests, 670 passed, 1 failed, 0 errors, 0 skipped`；`:hipda:testDebugUnitTest` `620 tests, 619 passed, 1 failed`；`D2ProbeTest` `12 tests, 11 passed, 1 failed`）

---

## 2. 探针逐项结论（a–f）

| 探针 | 覆盖项与用例 | 判定 | 结论摘要 |
|---|---|---|---|
| **a** | `probeA_concurrentExpirationStorm_coalescesSingleFlightAndIsolatesInitiatorCancellation`（[L153-L274](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L153-L274)） | **PASS** | 10 路并发 `ForumHttpClient.getHtml(..., requireAuth = true)` 首访均遇游客页后，单飞合流为 `1 GET + 1 POST`；在途取消发起者协程 0 后，其余 9 路全部拿到 `Recovered(1)` 并重试成功；随后再次过期可重新触发第 2 轮重登（无卡死槽位）。 |
| **b** | `probeB1`（[L280-L359](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L280-L359)）、`probeB2`（[L361-L433](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L361-L433)）、`probeB2b`（[L436-L511](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L436-L511)）、`probeB3`（[L513-L588](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L513-L588)） | **FAIL** | `probeB1`（退登后并发调用零登录请求）、`probeB2`（服务端阻塞请求未发响应时退登，socket 被取消，无状态回写/Cookie 泄漏）、`probeB3`（显式 `login`/`switchAccount` 清除 `manuallyLoggedOut` 并恢复静默重登）均通过；但 **`probeB2b` 失败**：在途重登请求已从传输层拿到 `200 OK + Set-Cookie` 响应、即将交给 `AppCookieInterceptor.saveFromResponse` 时并发调用 `logout()`，退登完成后旧重登线程把 `cdb_auth` 与 `cdb_sid` 写入 `SiteCookieStore`，造成已手动退登状态下登录 Cookie 残留。 |
| **c** | `probeC_boundedRetry_stopsAfterOneReloginWhenProtectedPageRemainsGuest`（[L594-L686](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L594-L686)） | **PASS** | `getHtml(requireAuth = true)` 与 `postGbkForm(requireAuth = true)` 遇游客页触发 1 次静默重登后携带新 Cookie 重试，若服务端仍返回游客页则均在第 2 次业务请求后立即抛出 `AuthRequiredException(SESSION_STILL_UNAUTHENTICATED)`，绝不触发第 2 次重登或死循环。 |
| **d** | `probeD_accountSwitch_cookieDomainVariantsClearedAndFailureRollback`（[L692-L849](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L692-L849)） | **PASS** | 账号 A 持有 host-only `cdb_auth`（`www.4d4y.com`, `/forum/`）、`Domain=.4d4y.com` `cdb_sid`（`/`）及 `Domain=.4d4y.com` `cf_clearance`；切到 B 失败（密码错与 `SocketEffect.CloseSocket()` 网络错）时，A 的 `cdb_auth`/`cdb_sid` 在发往 B 登录前即被清除、`cf_clearance` 保留、`activeUid` 未切为 B；切 B 成功时 `activeUid == "2002"`、B 的 Cookie 生效且 `cf_clearance` 未被误清。 |
| **e** | `probeE1`（[L855-L941](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L855-L941)）、`probeE2`（[L943-L1107](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L943-L1107)）、`probeE2b`（[L1109-L1160](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1109-L1160)） | **PASS** | 主 `OkHttpClient`（`maxRequests = 2`）100% 满载阻塞时，静默重登仍在 `OkHttp Recovery Dispatcher-*` 独立线程完成且未死锁；恢复请求遇 `IOException`、`ChallengeSolver` 取消、`SiteAccessGateway` 抛 `CancellationException`、HTTP 403 `cf-mitigated: challenge`（含 `BackgroundSilent` 零 solver 调用及主网关 `Solving` 时零网络请求放弃）均立即释放 `inFlightRelogin` 槽位，无永久挂起。 |
| **f** | `probeF1`（[L1166-L1207](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1166-L1207)）、`probeF2`（[L1209-L1288](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1209-L1288)） | **PASS** | `computeLegacyPasswordMd5` 对 `\`、`'`、`"`、中文、空格、32 位大小写 hex 与旧 `LoginHelper.java:170-181` + `Utils.java:611-623` 字节等价（空串抛 `IllegalArgumentException`，非 hex 32 位明文按规范取 MD5）；登录 `POST` `Content-Type` 含 `application/x-www-form-urlencoded; charset=GBK`，中文用户名与安全提问回答均正确按 GBK 百分号编码（空格为 `+`）。 |

---

## 3. FAIL 阻断项详述与复现路径

### 失败用例
`D2ProbeTest > probeB2b_logoutRacingLateResponseBeforeAppCookieSave_doesNotLeakStaleCookies`

### 断言失败输出（本地与 CI 完全一致）
```
java.lang.AssertionError: Late relogin response leaked cookies into SiteCookieStore after logout(): [cdb_auth=late_race_auth; path=/; httponly, cdb_sid=late_race_sid; domain=4d4y.com; path=/]
    at net.jejer.hipda.verify.D2ProbeTest$probeB2b_logoutRacingLateResponseBeforeAppCookieSave_doesNotLeakStaleCookies$1.invokeSuspend(D2ProbeTest.kt:506)
```

### 精确触发路径
1. 账号 A 已登录；`DefaultAuthRepository.trySilentRelogin` 启动协程 `runSilentRelogin(flight)`，通过 `recoveryGateway`（包装了注入的 `@RecoveryHttpClient`）向 `logging.php?action=login&loginsubmit=yes&inajax=1` 发送静默重登 `POST`（[DefaultAuthRepository.kt:586-596](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L586-L596)）。
2. `@RecoveryHttpClient` 拦截器链顺序为 `ManualRedirectInterceptor -> BrowserHeaderInterceptor -> AppCookieInterceptor -> TransportInterceptor`（[HttpClientModule.kt:51-65](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt#L51-L65)）。底层传输（生产中的 `CronetInterceptor` 异步回调，或 OkHttp `CallServerInterceptor` 刚读完响应头/体）在 `OkHttp Recovery Dispatcher-1` 线程把带 `Set-Cookie: cdb_auth=...; cdb_sid=...` 的 `200 OK` 响应返回给 `TransportInterceptor` / `AppCookieInterceptor.intercept()` 第 29 行（[AppCookieInterceptor.kt:29](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/network/AppCookieInterceptor.kt#L29)），此时线程尚未执行第 32 行 `siteCookieStore.saveFromResponse(...)`。
3. 此时调用方触发 `authRepository.logout()`（[DefaultAuthRepository.kt:355-368](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L355-L368)），进入 `runExplicitOperation`：
   - 递增 `authGeneration` 并剥离 `inFlightRelogin`（[DefaultAuthRepository.kt:502-506](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L502-L506)）；
   - 调用 `detached.job?.cancelAndJoin()`（[DefaultAuthRepository.kt:516](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L516)）。
4. `SiteAccessGateway.executeCall` 使用 `suspendCancellableCoroutine`，在 `cont.invokeOnCancellation { call.cancel() }` 后立即以 `CancellationException` 恢复协程，**并不等待** `OkHttp Recovery Dispatcher-1` 线程退出拦截器链（[SiteAccessGateway.kt:317-330](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt#L317-L330)）。
5. `logout()` 随即完成 `credentialsStore.markManualLogout()` 与 `clearAuthCookiesForServer(...)`（[DefaultAuthRepository.kt:358-359](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L358-L359)），将状态设为 `SignedOut`、`manuallyLoggedOut = true` 后返回。
6. 随后 `OkHttp Recovery Dispatcher-1` 从 `TransportInterceptor` 返回至 `AppCookieInterceptor.kt:30-33`，因 `AppCookieInterceptor` 未检查 `chain.call().isCanceled()`，且 `DefaultAuthRepository` 未对 `@RecoveryHttpClient` 写入 `SiteCookieStore` 做世代/退登门控或同锁互斥，导致 `siteCookieStore.saveFromResponse(...)` 在 `logout()` 之后执行，把 `cdb_auth=late_race_auth` 与 `cdb_sid=late_race_sid` 重新写入 `SiteCookieStore`。

### 影响与修复方向
- **影响**：用户执行手动退出并收到 `logout()` 完成、`AuthState.SignedOut` 与 `manuallyLoggedOut == true` 后，`SiteCookieStore` 仍残存有效的 `cdb_auth` / `cdb_sid`，后续 `requireAuth = false` 请求（或 `WebView` / 图片请求）会继续向服务器发送已退登账号的鉴权 Cookie。
- **修复方向（可在 `DefaultAuthRepository.kt` 内完成，不改 `data/network/` 共享文件；若跨卡改 `AppCookieInterceptor` 检查 `isCanceled()` 亦可一并加固显式登录/切换场景）**：
  - 在 `DefaultAuthRepository` 构建 `recoveryGateway` 时，用受 `stateLock` 与 `flight.generation == authGeneration && !persistentSnapshot.manuallyLoggedOut` 门控的 `SiteCookieStore` 包装（或重写恢复客户端 `AppCookieInterceptor` 链路，并在 `TransportInterceptor` 返回后校验 `call.isCanceled()`），同时用同一锁保护 `saveFromResponse` 与 `clearAuthCookiesForServer` 的临界区顺序，杜绝在途重登响应在 `logout()` / `switchAccount()` / `removeAccount()` 之后补写 Cookie。

---

## 4. 本地与 CI 执行记录

- **本地探针运行**（`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.D2ProbeTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4; ./gradlew --stop`）：
  - `12 tests completed, 1 failed`（仅 `probeB2b_logoutRacingLateResponseBeforeAppCookieSave_doesNotLeakStaleCookies` 失败，其余 11 项全部 `PASSED`）。
- **CI 运行**：[Android CI run 45619016103](https://github.com/mailwmj/hipda-next/actions/runs/45619016103)（触发分支 `verify/D2-probes` @ `bc4708798be561f546e967d4f184849607c1d5fd`）：
  - `Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）：`success`
  - `Verify Room schema consistency`：`success`
  - `Run unit tests and verify Roborazzi screenshots`：`failure`（`TOTAL: 671 tests, 670 passed, 1 failed, 0 errors, 0 skipped`；`net.jejer.hipda.verify.D2ProbeTest: 12 tests, 11 passed, 1 failed, 0 errors, 0 skipped`）
- **边界核查**：`git diff --stat df1c12dd30701f2e5dad7cd59e29564f3b6f83d1...HEAD` 仅新增 `hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt`（+1692 行），零生产代码改动；未访问真实 `4d4y.com`（使用 `MockWebServer` + 回环 `Dns`）。

---

## 5. 本次验证核对的生产公开 API 签名（供对照）

- [AuthRepository.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/AuthRepository.kt#L11-L59)：`AuthRepository`（`currentAccount` L14、`savedAccounts` L17、`authState` L20、`sessionEpoch` L23、`login(...)` L26-31、`switchAccount(uid)` L34、`logout()` L41、`removeAccount(uid)` L47、`trySilentRelogin(failedEpoch, priority)` L55-58）
- [DefaultAuthRepository.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L52-L83)：`DefaultAuthRepository` `@Singleton`（`@Inject constructor` L62-83）
- [ForumHttpClient.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/ForumHttpClient.kt#L21-L55)：`ForumHttpClient`（`getHtml(...)` L28-37、`postGbkForm(...)` L40-55）
- [AuthModels.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/AuthModels.kt#L10-L109)：`AuthState` L10-18、`LoginResult` L21-45、`SilentReloginFailureReason` L48-56、`SilentReloginResult` L59-68、`ForumHtmlResponse` L71-76、`AuthRequiredReason` L79-84、`AuthRequiredException` L87-102、`ForumHttpException` L105-109
- [PasswordMd5.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/data/auth/PasswordMd5.kt#L19-L36)：`computeLegacyPasswordMd5(rawOrMd5: String): String`
- [AuthModule.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/main/java/net/jejer/hipda/di/AuthModule.kt#L17-L36)：`AuthModule`（`bindAuthRepository` L21、`provideForumHttpClient` L26-34）


---

## 6. 复验轮 1（对象 `origin/card/D2-auth-repository` @ `85ac8b1106ec0def225529964b2bebc041d889d7`）

### 6.1 复验总判定与基线信息

- **复验判定**：**PASS**
- **复验对象**：`origin/card/D2-auth-repository` @ `85ac8b1106ec0def225529964b2bebc041d889d7`
- **探针分支与提交**：`verify/D2-probes` @ `2034fcb0da62552f0899e804a469ac4389642bdf`（在 `85ac8b1106ec0def225529964b2bebc041d889d7` 上变基并保留全部原 a–f 探针断言不变，新增探针 g1/g2）
- **探针源码**：[D2ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt)（2,197 行，14 项探针用例）
- **CI 证据**：[Android CI run 45624628233](https://github.com/mailwmj/hipda-next/actions/runs/45624628233)
  - `Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）：**success**
  - `Verify Room schema consistency`：**success**
  - `Run unit tests and verify Roborazzi screenshots`：**success**（全库 `TOTAL: 690 tests, 690 passed, 0 failed, 0 errors, 0 skipped`；`:hipda:testDebugUnitTest` `639 tests, 639 passed`；`D2ProbeTest` `14 tests, 14 passed, 0 failed, 0 skipped`）
  - `Verify APK outputs`：**success**

### 6.2 复验逐项结果（a–f 原探针 + 新增 2 探针）

| 探针 | 用例与行号 | 判定 | 说明 |
|---|---|---|---|
| **a** | `probeA_concurrentExpirationStorm_coalescesSingleFlightAndIsolatesInitiatorCancellation`（[L166-L287](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L166-L287)） | **PASS** | 原断言未改；10 路并发仅 1 GET + 1 POST，取消发起者后其余 9 路恢复成功，二次过期可再次恢复。 |
| **b** | `probeB1`（[L293](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L293)）、`probeB2`（[L374](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L374)）、`probeB2b`（[L449](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L449)）、`probeB3`（[L526](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L526)） | **PASS** | 原阻断用例 `probeB2b` 在不改任何断言下直接通过：`AuthCookieWriteGuard` + `cookieWriteLock` + `AppCookieInterceptor` 的 `!callCanceled && allowAuthCookies` 门控成功拦截了 `logout()` 后迟到响应的 `cdb_auth`/`cdb_sid` 写回。 |
| **c** | `probeC_boundedRetry_stopsAfterOneReloginWhenProtectedPageRemainsGuest`（[L607](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L607)） | **PASS** | 原断言未改；GET 与 POST 遇持续游客页均最多静默重登并重试 1 次即止。 |
| **d** | `probeD_accountSwitch_cookieDomainVariantsClearedAndFailureRollback`（[L705](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L705)） | **PASS** | 原断言未改；host-only `cdb_auth` 与 `Domain=.4d4y.com` `cdb_sid` 均清理，`cf_clearance` 保留，失败不切号、成功切 B 生效。 |
| **e** | `probeE1`（[L868](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L868)）、`probeE2`（[L956](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L956)）、`probeE2b`（[L1122](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1122)） | **PASS** | 原断言未改；独立 `OkHttp Recovery Dispatcher-*` 线程池不死锁，异常/取消/403 挑战均释放槽位。 |
| **f** | `probeF1`（[L1179](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1179)）、`probeF2`（[L1222](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1222)） | **PASS** | 原断言未改；密码转义 MD5 与旧版字节等价，登录表单 `Content-Type` 与中文参数按 GBK 编码。 |
| **新1（切号赛跑×100）** | `probeG1_switchAccountRacingOldSilentRelogin_100RandomInterleavings_neverOverwritesNewAccountCookies`（[L1310-L1494](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1310-L1494)） | **PASS** | 固定种子 `Random(20261125L)` 连续 100 轮随机交错（4 种阻塞/时序模式：传输层返回前/中/切号后及服务端阻塞 + `0..5ms` 随机抖动），旧账号在途静默重登返回同名同域路径 `Set-Cookie: cdb_auth=stale_auth_i; cdb_sid=stale_sid_i` 与并发 `switchAccount` 赛跑：100/100 轮均保持 `activeUid` 为新账号、`SiteCookieStore` 持有 `fresh_auth_i`/`fresh_sid_i`、零 `stale_*` 残留，且每 10 轮及末轮实发请求验证服务端收到新账号 Cookie。 |
| **新2（退出+CF挑战）** | `probeG2_logoutDuringInFlightCloudflareChallenge_preservesCfClearanceAndLeavesZeroAuthCookies`（[L1500-L1705](file:///home/mir/Documents/hipda-next.wt/V-D2/hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt#L1500-L1705)） | **PASS** | 覆盖两条并发路径：(A) 前台 `ForumHttpClient` 遇 403 触发 `ChallengeSolver`（通过 `WebViewCookieBridge` + `FaithfulWebViewCookieAccess` 预先注入 `cdb_auth`/`cdb_sid`）并在途调用 `logout()`，随后挑战通过并同步 `cf_clearance` 且重试响应同时携带 `Set-Cookie: cf_clearance=cf_from_http_1` 与 `cdb_auth`/`cdb_sid`；(B) 恢复网关 `trySilentRelogin` 遇 403 触发挑战并在途 `logout()`。两条路径均确认 `manuallyLoggedOut == true`、`AuthState.SignedOut`、`cf_clearance` 正常写回保留（`SiteCookieStore` 与 WebView 均有），且 `cdb_*` 零残留。 |

### 6.3 本地与边界核查（复验轮 1）

- **本地仅原 12 探针先验复跑**（`2034fcb` 添加 g1/g2 前）：`12 tests, 12 passed, 0 failed, 0 skipped`（`probeB2b` 首次转绿）。
- **本地全量 14 探针运行**（`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.D2ProbeTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4; ./gradlew --stop`）：`14 tests, 14 passed, 0 failed, 0 skipped`。
- **边界核查**：`git diff --stat 85ac8b1106ec0def225529964b2bebc041d889d7..HEAD` 仅含 `hipda/src/test/java/net/jejer/hipda/verify/D2ProbeTest.kt`（+2,197 行），零生产代码改动；全部测试纯离线 `MockWebServer` + 回环 `Dns`。
