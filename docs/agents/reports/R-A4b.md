# 独立代码审查报告 R-A4b：站点访问网关与独立恢复客户端

- **被审分支 / Commit**：`origin/card/A4b-access-gateway` @ `3a22f8f0865ef2fb079e8ca7409c9df04807e55b`（对比 `main` @ `294c705f4abe6e3a67757caac54ac16a40ae5779`，merge-base `e75ef76b55918467d86e8f29e0310d58c74d8694`）
- **CI 状态**：GitHub Actions Run [`45544235439`](https://github.com/mailwmj/hipda-next/actions/runs/45544235439)（`headSha = 3a22f8f0865ef2fb079e8ca7409c9df04807e55b`）`completed / success`（Build, Lint & Unit Tests 3m29s 全绿，423 tests passed）
- **审查结论**：**`APPROVE`**
- **阻断项**：**无**

---

## 一、范围、边界与验收核对

| 检查项 | 结果 | 证据与说明 |
|---|---|---|
| 改动文件范围 | 通过 | `git diff --name-status 294c705...3a22f8f` 仅涉及生产文件 4 个（`HttpClientModule.kt` +21、`access/ChallengeSolver.kt` +37、`access/SiteAccessTypes.kt` +40、`access/SiteAccessGateway.kt` +264）与测试文件 3 个（`ChallengeDetectorTest.kt`、`HttpClientModuleTest.kt`、`access/SiteAccessGatewayTest.kt`） |
| 禁改文件保护 | 通过 | `ChallengeDetector.kt`、4 个拦截器（`ManualRedirectInterceptor.kt`、`BrowserHeaderInterceptor.kt`、`AppCookieInterceptor.kt`、`TransportInterceptor.kt`）以及构建/Manifest 等共享文件均 0 改动 |
| 生产代码体量 | 通过 | 新增生产文件合计 362 行（含包声明、44 行 import、KDoc 与空行；有效代码约 235 行，契合卡片 200–250 行估计且低于 375 行上限），无测试专用钩子 |
| 无 `runBlocking` / 无拦截器阻塞 | 通过 | `access/` 与 `HttpClientModule.kt` 中 `runBlocking` 为 0 处（全 `data/network/` 仅存 A2 既有未改动的 `cookie/DataStoreSiteCookieStore.kt:13,109`） |
| 异常不含用户界面文案 | 通过 | `SiteAccessTypes.kt:20-40` 三类对外异常仅含英文诊断信息，未混入中文 UI 文案 |

---

## 二、11 项审查重点逐项核验

### 1. 单次验证合流的竞态（`SiteAccessGateway.kt:33-63, 75-85, 110-189`）
- **锁与 `CompletableDeferred` 配合**：
  - 采用轻量对象锁 `synchronized(stateLock)`（`SiteAccessGateway.kt:58`）保护纯内存状态读写（`clearanceEpoch`、`consecutiveFailures`、`cooldownUntilMs`、`cooldownRejected`、`activeSolve`、`_challengeState`），锁内**零挂起点、零外部阻塞回调**；`solve.deferred.complete(outcome)` 在释放 `stateLock` 之后调用（`SiteAccessGateway.kt:182`），彻底规避锁内唤醒协程导致的死锁或重入乱序。
  - 所有 `_challengeState.value = ...` 赋值均位于锁内状态字段更新完毕之后（`SiteAccessGateway.kt:122-125, 154-179, 195-197, 215-219`），即便 `Unconfined` 收集者同步重入观察，内部不变量也已一致。
- **世代号（`epochAtSend` vs `clearanceEpoch`）与合流顺序**：
  - 发出前记录 `epochAtSend = clearanceEpoch`（`SiteAccessGateway.kt:77`）；若发出前已有 `activeSolve`，非静默请求通过 `pendingSolve` 挂起等待并在成功后拿到最新 `newEpoch`（`SiteAccessGateway.kt:83-84`），同时标记 `alreadySolved = true`——这避免了“多个前置等待者在拿到无效新凭证后同时撞 403 时，二次触发第二轮 WebView 验证”的竞态窗口。
  - 在 `resolveChallenge`（`SiteAccessGateway.kt:116-127`）中，**先判 `running != null` 再判 `clearanceEpoch > epochAtSend`**：当 `clearanceEpoch > epochAtSend` 且 `running == null` 时直接返回最新世代号重试；而若新世代凭证又已被后续请求撞破并开启了新一轮 `running != null`，旧世代慢请求会直接合流进新一轮 `running`，不会在验证进行中盲发必败重试包。该顺序比先判世代号更严密。
- **单协程取消隔离**：
  - 等待者仅在 `solve.deferred.await()`（`SiteAccessGateway.kt:186`）挂起；调用方协程取消只取消其自身的 `await()`，不会取消 `CompletableDeferred` 或 `gatewayScope` 中的求解任务。
  - 即使发起者（`shouldLaunch == true`）在进入 `resolveChallenge` 时已被外部置为取消态，由于 `synchronized(stateLock)`（第 128 行）与 `gatewayScope.launch`（第 131 行）之间无挂起点，`gatewayScope.launch` 仍会正常启动求解，随后发起者在 `awaitOutcome` 处抛 `CancellationException`，其余等待者不受影响。
- **`SupervisorJob` 作用域与异常/取消清理保证**：
  - `@Inject` 构造器创建独立 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`（`SiteAccessGateway.kt:46`）。
  - `runSolve`（`SiteAccessGateway.kt:139-150`）显式捕获 `CancellationException`（映射为 `SolveResult.Cancelled`）与 `Exception`（映射为 `SolveResult.Failed(e)`），使普通异常和取消异常**绝不向 `gatewayScope` 泄漏**；`finally` 块无条件调用 `completeSolve(solve, result)`（即便发生 `java.lang.Error` 也会清理 `activeSolve = null`、复位 `_challengeState` 并完成 `deferred`）；第 131–133 行还额外兜底了 `gatewayScope` 自身已取消时 `job.isCancelled` 的清理，**不存在永久卡死在 `Solving` 的路径**。

### 2. 失败轮次计数（`SiteAccessGateway.kt:152-183`）
- `completeSolve` 由 `if (solve.completed) return`（第 154 行）保证每轮 `ActiveSolve` 至多执行一次结算；不论同一轮合流了多少个并发等待者（含发出前挂起在 `pendingSolve` 与 403 后合流在 `resolveChallenge` 的协程），`consecutiveFailures++`（第 166 行）**严格按 `solver.solve` 轮次计 1 次**。
- 连续满 `MAX_CONSECUTIVE_FAILURES = 3` 轮时（第 168–174 行），进入 `FAILURE_COOLDOWN_MS = 30_000L` 冷却（`rejected = false`）并将 `consecutiveFailures` 归零；`SolveResult.Cleared` 时（第 160 行）也将 `consecutiveFailures` 归零。

### 3. 两类冷却与主动重试（`SiteAccessGateway.kt:105-108, 191-220`）
- **60s 无效凭证冷却（`rejected = true`）与 30s 连续失败冷却（`rejected = false`）**：
  - `checkCooldownLocked`（第 202–212 行）统一以注入的 `clock()` 判定：`now >= cooldownUntilMs` 时立即清零冷却并把 `ChallengeState.Cooldown` 归为 `Idle`（精确包含 `now == cooldownUntilMs` 边界）；未到期且非 `UserExplicit` 时分别抛 `ChallengeRejectedException(cooldownUntilMs)` 与 `ChallengeIncompleteException(cooldownUntilMs)`。
  - `enterRejectedCooldown(epochAtSend)`（第 191–200 行）带有 `if (clearanceEpoch == epochAtSend && activeSolve == null)` 保护，防止过期世代的慢重试响应覆盖已推进的新世代或正在进行的验证。
- **`UserExplicit` 与 `resetCooldownForUserRetry()` 竞态安全性**：
  - 两者均在 `synchronized(stateLock)` 下清除冷却并重置 `consecutiveFailures = 0`；且 `clearCooldownLocked()`（第 217 行）检查 `activeSolve == null && _challengeState.value is ChallengeState.Cooldown`，不会在验证进行中误把 `Solving` 改回 `Idle`。
  - `checkCooldownLocked(priority)` 同时部署在 `execute` 发出前（第 76 行）和 `resolveChallenge`（第 114 行）两处：若 `UserExplicit` 在飞行途中恰逢另一慢请求写入冷却，第 114 行仍会为 `UserExplicit` 再次清除冷却并发起验证。

### 4. `BackgroundSilent` 两条放弃路径（`SiteAccessGateway.kt:76-81, 95-99`）
- **路径 (a) 验证进行中发起**：第 79–80 行在锁内检测到 `activeSolve != null` 且 `priority == AccessPriority.BackgroundSilent` 时直接抛 `ChallengeAbandonedException()`，不创建 OkHttp `Call`、不发网络包、不调 `solver`、不计 `consecutiveFailures`。
- **路径 (b) 请求发出后命中验证页**：第 95 行先执行 `response.close()` 释放底层流，第 98 行立即抛 `ChallengeAbandonedException()`，不进入 `resolveChallenge`、不调 `solver`、不计失败轮次。
- 补充核对：若网关已处于冷却期，第 76 行 `checkCooldownLocked(BackgroundSilent)` 按任务卡第 2 节规范（“处于冷却且 `priority != UserExplicit` → 立即抛对应异常”）快速失败，同样不发网络包。

### 5. 重试上限与请求体安全（重点核查 `RequestBody.isOneShot()`）
- **单次重试上限与 60s 冷却**：
  - `execute` 内 `alreadySolved` 在经过前置 `pendingSolve` 等待（第 83 行）或完成一次 `resolveChallenge`（第 101 行）后置为 `true`；下一次响应若仍命中挑战，第 97 行立即调用 `enterRejectedCooldown(epochAtSend)` 进入 60 秒冷却并抛 `ChallengeRejectedException`，每个请求最多发出 2 次网络调用（初试 + 1 次重试），绝无死循环。
- **`triggerUrl` 构造（`SiteAccessGateway.kt:245-255`）**：
  - `GET`（不区分大小写）使用原 `request.url`；非 `GET` 保留原 `scheme`、`host`、`port`，将路径替换为 `/forum/`（剥离原 query），符合规范且兼容 `MockWebServer` 动态端口。
- **常规表单请求体字节一致性**：
  - 仓库内表单构造器 `gbkFormBody`（`GbkForm.kt:56`）底层为 `ByteArray.toRequestBody(...)`（`isOneShot() == false`），重试复用同一 `Request` 实例时 `writeTo` 输出字节 100% 一致，测试 (f) 已验证两次 POST 原始 GBK 字节完全相等。
- **一次性请求体（`RequestBody.isOneShot() == true`）深度核查结论**：
  - **现状**：`SiteAccessGateway.kt` **未检查 `request.body?.isOneShot()`**（对比 A3a 的 `ManualRedirectInterceptor.kt:58` 已对 307/308 检查 `isOneShot()`）。
  - **触发时的具体行为**（经核对 OkHttp 5 `CallServerInterceptor` 与 `cronet-okhttp:0.1.2` `RequestBodyConverterImpl` 字节码）：
    1. 首次调用无论走原生 OkHttp 还是 Cronet，均会调用 `requestBody.writeTo(...)` 抽干底层单次流（`CronetInterceptor` 每次 `newCall` 都会新建 `UploadDataProvider`，不会跨 `Call` 复用首次缓冲）；
    2. 首次响应命中 403 验证页后，网关**仍会触发一轮 `solver.solve`**（若升级为可见面板还会白白打扰用户过盾）；
    3. 验证通过进入第 2 次 `executeCall` 重试时：
       - 若调用方传入的是 `contentLength() == -1L`（chunked）且未在 `writeTo` 内关闭的 `Source`/`InputStream`（已处于 EOF），`sink.writeAll(source)` 会读到 0 字节正常返回，导致**静默向服务端发出 0 字节空请求体**；
       - 若 `contentLength() > 0L`（定长）或 `writeTo` 已关闭底层流，则在浪费完一次验证后抛出 `ProtocolException("expected N bytes but received 0")` 或 `IOException("Stream closed")`。
  - **定性**：当前仓库及 D2 登录、M5/M6 常规 GBK 表单均使用内存 `ByteArray` 请求体（`isOneShot() == false`），且任务卡 `A4b.md` 未要求处理 `isOneShot()`，故**不构成合并阻断项**；但未来 M6 多图上传若有人直接封装 `ContentResolver.openInputStream` 单次流走网关就会踩坑。建议在下一张触及 `access/` 的卡里在 `execute` 入口加一行 `require(request.body?.isOneShot() != true)` 快速失败守住契约（见建议 1）。

### 6. 超时、取消与连接防泄漏（`SiteAccessGateway.kt:87-95, 222-243`）
- **单次调用超时覆盖首次与重试 + 覆盖 Cronet 响应头挂起**：
  - `while (true)` 每次迭代均调用 `executeCall(request, timeout)` 创建新 `Call` 并设置 `call.timeout().timeout(timeout.inWholeMilliseconds.coerceAtLeast(1L), MILLISECONDS)`（`coerceAtLeast(1L)` 还防止了亚毫秒正 `Duration` 截断为 `0L` 误关闭超时），首次与重试均独立享有完整超时预算。
  - **Cronet 字节码级核实**：反编译 `cronet-okhttp:0.1.2` 的 `CronetInterceptor` 与 `OkHttpBridgeRequestCallback` 确认——虽然 `getResponse()` 在收到响应头前用无超时的 `getUninterruptibly(headersFuture)` 等待，但 OkHttp `RealCall` 的 `AsyncTimeout` 到期会调用 `RealCall.cancel()`；`CronetInterceptor` 内部每 500ms 轮询 `activeCalls` 中 `call.isCanceled()`，一旦为真即调用 `urlRequest.cancel()`，触发 `OkHttpBridgeRequestCallback.onCanceled` 向 `headersFuture` 注入 `IOException("The request was canceled!")`，从而在 `timeout + ≤500ms` 内可靠唤醒挂起的线程并向上抛出 `InterruptedIOException`。完美闭环 R-A3b 遗留风险。
- **协程取消与 `Response` 零泄漏**：
  - `cont.invokeOnCancellation { call.cancel() }`（第 230 行）保证在途取消可靠传递到底层 `Call`。
  - `onResponse` 使用 Kotlin 3 参数重载 `cont.resume(response) { _, value, _ -> value.close() }`（第 238 行）：若协程在 `onResponse` 触发与恢复执行之间的并发窗口被取消，框架自动回调关闭 `response`。
  - `execute` 拿到 `response` 后无任何挂起点：`detector.inspect` 抛任何 `Throwable` 在 `catch` 中 `response.close()`（第 89–92 行），命中挑战立即在第 95 行 `response.close()`，正常响应原样移交调用方关闭。全路径无连接/响应体泄漏。

### 7. 协程层排队不占 OkHttp 调度名额
- 发出前排队（第 84 行）在 `client.newCall` 之前挂起；撞盾后排队（第 100 行）在 `Callback.onResponse` 已归还 `Dispatcher` 槽位且 `response.close()` 已归还连接之后挂起。测试 (h) 在 `maxRequests = 1, maxRequestsPerHost = 1` 下验证了挂起等待期间 `runningCallsCount() == 0` 且同客户端并发请求立即返回 200。

### 8. `@RecoveryHttpClient` 独立 Dispatcher（`HttpClientModule.kt:57-66`）
- 线程池配置 `ThreadPoolExecutor(0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue())` + `isDaemon = true` 命名线程：核心线程数为 0，空闲 60 秒全部回收，无常驻线程泄漏；并发度由独立 `Dispatcher` 自身的 `maxRequests(64)` / `maxRequestsPerHost(5)` 约束（与 OkHttp 默认 `Dispatcher` 线程池模型完全一致）。
- 通过 `primaryClient.newBuilder().dispatcher(Dispatcher(executor)).build()` 构建：完整继承主客户端的 4 层拦截器链顺序、超时配置与共享 `ConnectionPool` / `@Singleton TransportInterceptor`，无连接池或 `CronetEngine` 重复创建风险。

### 9. `ChallengeState` `StateFlow` 状态转换（对比 `product-design.md` §7.12）
- 状态迁移闭环：`Idle` → `Solving(startedAtMs)` →（成功）`Idle` /（第 1–2 轮取消或失败）`Idle` + 调用方收 `ChallengeIncompleteException(cooldownUntilMs = null)` /（满 3 轮失败）`Cooldown(untilMs, rejected = false)` + 调用方收 `ChallengeIncompleteException(untilMs)` /（验证后重试仍被拒）`Cooldown(untilMs, rejected = true)` + 调用方收 `ChallengeRejectedException(untilMs)`。
- 与 §7.12 完全对齐：`Solving(startedAtMs)` 支撑 A6 计算“后台等待超过 2 秒显示 `正在检查安全环境…`”；`Cooldown(rejected = false)` / `ChallengeIncompleteException` 对应 `安全验证未完成`；`Cooldown(rejected = true)` / `ChallengeRejectedException` 对应 `验证未通过`。
- 冷却到期行为：`Cooldown(untilMs, rejected)` 不起后台定时任务自动跳回 `Idle`，而是在下次 `execute()` 或 `resetCooldownForUserRetry()` 时惰性归为 `Idle`（与虚拟时钟 `clock: () -> Long` 设计及 §7.12 原地错误态停留直到用户操作一致，且受 `activeSolve == null` 保护，绝无覆盖 `Solving` 的状态倒退）。建议在 A6 接线时注意这一点（见建议 3）。

### 10. R-A4a 追加的 4 条检测器测试核验（`ChallengeDetectorTest.kt:412-590`）
1. `inspect_neverCallsPeekBody_forNonFallbackResponses`（第 412–475 行）：**有效**。`throwingProbeBody.source()` 直接抛 `AssertionError`（非 `IOException`，不会被 `peekBodyAscii` 吞掉），一旦改坏 200 拦截、429 禁兜底、`cf-mitigated` 命中/非 challenge 短路或 `application/json` 过滤，必触发 `AssertionError` 失败。
2. `inspect_exact16KbPeekBoundary_hitsAtLimitAndMissesWhenCrossingByOneByte`（第 477–519 行）：**有效**。`_cf_chl_opt` 末字节精确落在第 16,384 字节（index `16383`）断言命中、跨界 1 字节落在第 16,385 字节（index `16384`）断言不命中，并校验原始流完整可读；把 `peekLimitBytes` 改小 1 字节或改大 1 字节均必败。
3. `inspect_gbkAdjacentMarkerAndFallbackBodyContentTypeWithoutHeader`（第 521–566 行）：**有效（含一处可微调点）**。后半部分通过“无 `Content-Type` 头 + `ResponseBody.contentType()` 为 HTML（断言命中）”与“无头 + `throwingProbeBody(application/json)`（断言 `None` 且不读流）”双向夹击锁死了 `ChallengeDetector.kt:67` 的 `?: response.body.contentType()?.toString()` 分支，删掉即败；前半部分验证了真实流上 GBK 中文紧邻 `_cf_chl_opt` 的无头 403 命中与原流保真（注：因 `'文'` 为合法双字节 `0xCE 0xC4`，若把 `ISO_8859_1` 误改为 GBK 解码该串仍能过，前置孤立高字节 `0x81` 才能把紧邻 `'_'` `0x5F` 吞成单汉字使之必败，见建议 4）。
4. `inspect_returnsNone_whenPeekBodySourceThrowsIOException`（第 568–589 行）：**有效**。构造 `read()` 抛 `IOException` 的 `ResponseBody`，若删去 `ChallengeDetector.kt:71-77` 的 `try/catch (IOException)` 必抛异常失败。

### 11. 复杂度与测试质量评估（“改坏关键一行测试仍通过”缺口排查）
- **代码精简度**：`SiteAccessGateway.kt` 与 `SiteAccessTypes.kt` 结构清晰紧凑，几乎无冗余抽象（仅 `ActiveSolve.completed` 与 `deferred` 可微幅内联，不值得单独折腾）。
- **单测变异缺口排查**：`SiteAccessGatewayTest`（12 项）与 `HttpClientModuleTest`（5 项）覆盖了任务卡全部 a–j 场景及请求中取消、Hilt 绑定反射校验，但存在以下 3 处“改坏生产关键分支而现有测试仍全绿”的盲区，建议顺带补齐：
  1. **删掉 `SiteAccessGateway.kt:118-121`（`clearanceEpoch > epochAtSend` 无在途验证直通重试分支）测试仍过**：测试 (a) 中 `releaseSolve` 挂起期间后续请求均落入第 117 行 `running != null`（或前置 `pendingSolve`），没有确定性覆盖“请求 A/B 同在 `epoch=0` 发出 → B 先撞 403 并完成整轮 `Cleared`（`activeSolve` 归 `null`、`epoch=1`）→ 慢请求 A 才收到 403 进入 `resolveChallenge(0)` 跳过二次 `solve` 直接重试”。
  2. **删掉 `SiteAccessGateway.kt:143-146`（`runSolve` 对 `solver.solve` 抛 `CancellationException` / `Exception` 的捕获）测试仍过**：现有测试的假 `ChallengeSolver` 全部正常返回 `SolveResult.*` 对象，没有一例从 `solve()` 内部直接 `throw RuntimeException` 或 `throw CancellationException`。
  3. **删掉 `SiteAccessGateway.kt:160`（`SolveResult.Cleared` 时 `consecutiveFailures = 0`）测试仍过**：现有测试未覆盖“连续失败 2 轮 → 第 3 轮 `Cleared` 成功 → 后续再失败 2 轮不应触发 30 秒冷却”（测试 d 在第 3 轮已由冷却分支归零，测试 j 先跑 `Cleared` 时计数本就为 0）。

---

## 三、非阻断建议汇总（可带入 A5b / A6 / D2 等后续触及同区域的卡）

1. **`SiteAccessGateway.kt:73` 一次性请求体防呆**：在 `execute` 入口加 `require(request.body?.isOneShot() != true) { "One-shot RequestBody is not supported" }`，防止未来流式上传误走网关在撞盾重试时静默发 0 字节空体或白弹一次验证面板后抛异常。
2. **`SiteAccessGatewayTest.kt` 补齐 3 个变异探针**：补充 (a) 慢 403 跨世代直通重试（锁死 `SiteAccessGateway.kt:118-121`）、(b) `solver.solve` 抛 `RuntimeException` / `CancellationException` 不崩作用域且归位 `Idle`（锁死 `SiteAccessGateway.kt:142-149`）、(c) “2 败 → 1 胜 → 2 败不进冷却”及 `now == cooldownUntilMs` 精确边界（锁死 `SiteAccessGateway.kt:160, 204`）。
3. **A6 接入 `challengeState` 与重试交互提示**：注意 `ChallengeState.Cooldown(untilMs, rejected)` 到期后惰性归位 `Idle`（不主动定时翻转）；UI“重新验证”按钮应直接传 `AccessPriority.UserExplicit` 调用 `execute`（发出前与 `resolveChallenge` 双重清冷却，比先调 `resetCooldownForUserRetry()` 再发 `UserBlocking` 更抗在途慢重试竞态）。
4. **`ChallengeDetectorTest.kt:523`（可选）**：若需严格锁死 `ISO_8859_1` 不可退化为 GBK 解码，可在 `_cf_chl_opt` 紧前放置单字节 `0x81`（GBK 会将 `0x81 0x5F` 合字吞掉 `'_'`）。
