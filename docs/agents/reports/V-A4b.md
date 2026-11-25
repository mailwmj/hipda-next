# 验证报告 V-A4b：站点访问网关与独立恢复客户端独立探针验证

## 1. 结论摘要

- **总判定**：**FAIL**（存在 2 项真实实现缺陷：`b(2)` 共享验证状态永久卡死在 `Solving`、`g` 一次性 POST 请求体 `isOneShot() == true` 二次写入且无保护）
- **被验对象**：`origin/card/A4b-access-gateway` @ `3a22f8f0865ef2fb079e8ca7409c9df04807e55b`
- **探针分支 / Commit**：`verify/A4b-probes` @ `e4237132d35e3650cdead901006e4b2b2d71d1b6`（worktree `/home/mir/Documents/hipda-next.wt/V-A4b` 已保留）
- **探针文件**：`hipda/src/test/java/net/jejer/hipda/verify/A4bProbeTest.kt`
- **CI 证据**：https://github.com/mailwmj/hipda-next/actions/runs/45546321447
  - `Build and lint` (`assembleDebug assembleRelease lintDebug`)：通过
  - `Verify Room schema consistency`：通过
  - CI `Summarize unit test results`：
    - `net.jejer.hipda.verify.A4bProbeTest: 11 tests, 8 passed, 3 failed, 0 skipped`
    - `[:hipda:testDebugUnitTest] TOTAL: FAILURE (407 tests, 404 passed, 3 failed, 0 skipped)`
    - `TOTAL: 434 tests, 431 passed, 3 failed, 0 skipped`（其余已有单测及 Roborazzi 截图测试全绿，仅本卡探针 `probeB2`、`probeG1`、`probeG2` 失败）
- **本地验证**：
  - `./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.A4bProbeTest"`：`11 tests, 8 passed, 3 failed, 0 skipped`（与 CI 完全一致）
  - `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network`：`data/network/access/` 与 `HttpClientModule.kt` 均无 `runBlocking`（仅既有 `cookie/DataStoreSiteCookieStore.kt:13,109` 有历史引用）。

---

## 2. 探针明细（a–h + 额外契约探针）

| 探针项 | 测试方法 (`A4bProbeTest.kt`) | 断言要点 | 结果 |
|---|---|---|---|
| **a** | `probeA_fiftyCoroutinesConcurrentChallenge_randomHalfCancelled` (`L143`) | 50 协程并发同时撞 403，固定种子 `Random(20261125L)` 取消 25 个；`solve` 仅调用 1 次；25 个抛 `CancellationException`；25 个带 `cf_clearance=fresh` 重试返回 200；最终 `challengeState == Idle` | **PASS** |
| **b(1)** | `probeB1_solverThrowsRuntimeException_singleFailureRoundAndRecoversToIdle` (`L242`) | 3 并发等待时 solver 抛 `RuntimeException`；全部等待者收到 `ChallengeIncompleteException(cause=RuntimeException)`；`CoroutineExceptionHandler` 无泄漏；失败轮次仅计 1 轮（第 2 轮失败仍无冷却）；第 3 轮成功并 200；状态复位 `Idle` | **PASS** |
| **b(2)** | `probeB2_solverHangsForeverAndCallerTimesOut_stateRecoversForSubsequentRequests` (`L345`) | solver 挂起永不返回，调用方 `withTimeout(250ms)` 超时后，调用 `resetCooldownForUserRetry()` 与 `AccessPriority.UserExplicit`（`withTimeout(1s)`）尝试恢复；期望不永久卡死并完成请求 | **FAIL** |
| **c** | `probeC_inFlightOldEpoch403EnteringResolveAfterSolveCompletes_retriesWithoutSecondSolve` (`L420`) | 请求 B 在世代 0 发出并在服务端被扣住；请求 A 撞 403 并完成首轮验证（世代 `0 -> 1`，状态回 `Idle`）；随后释放 B 的旧世代 403 进入 `resolveChallenge`；断言不触发第 2 次 `solve`，直接携带新 Cookie 重试 200 | **PASS** |
| **d** | `probeD_cooldownBoundariesWithVirtualClock` (`L484`) | 虚拟时钟验证：(1) 60s 无效凭证冷却在 `t+59_900ms` 抛 `ChallengeRejectedException` 且零请求/零 solve，`t+60_100ms` 放行 200；(2) 30s 3 轮失败冷却在 `t+29_900ms` 抛 `ChallengeIncompleteException` 且零请求/零 solve，`t+30_100ms` 放行 200 | **PASS** |
| **e** | `probeE_userExplicitClears60sAnd30sCooldowns` (`L571`) | 分别在 60s 冷却内（`+10s`）和 30s 冷却内（`+5s`）用 `UserExplicit` 发起请求并过盾 200 后，紧接着普通 `UserBlocking` 请求不被冷却拦截且直接 200，状态为 `Idle` | **PASS** |
| **f** | `probeF_backgroundSilentTwoPaths` (`L669`) | (1) 验证进行中发出 `BackgroundSilent` 立即抛 `ChallengeAbandonedException`，MockWebServer 请求数零增加；(2) 空闲期连续 5 次 `BackgroundSilent` 遇 403 立即抛 `ChallengeAbandonedException`，不调 solver，不累计失败轮次（后续 2 次 `UserBlocking` 失败仍不进冷却） | **PASS** |
| **g(1)** | `probeG1_oneShotStreamingPostBody_preservesBytesOnRetry` (`L760`) | 流式 `isOneShot() == true` POST 撞 403 并过盾，验证重试请求体是否保持原字节或受明确保护 | **FAIL** |
| **g(2)** | `probeG2_oneShotStrictPostBody_protectedAgainstDoubleWriteCrash` (`L813`) | 严格式 `isOneShot() == true`（二次 `writeTo` 抛 `IllegalStateException`）POST 撞 403 并过盾，验证网关是否避免二次写入崩溃 | **FAIL** |
| **h** | `probeH_recoveryClientSucceedsWhilePrimarySaturated` (`L862`) | 主客户端 `maxRequests=1` 被挂起请求占满且第 2 个主请求进入队列阻塞时，`@RecoveryHttpClient` 请求 2 秒内完成 200（携带 `User-Agent`）；`dispatcher` 与线程池均不同实例且线程名以 `OkHttp Recovery Dispatcher-` 开头；拦截器类型顺序完全一致 | **PASS** |
| **extra** | `probeExtra_triggerUrlGbkPostBytePreservationAndCallTimeout` (`L981`) | GET `triggerUrl` 保留原 URL 与查询串；普通 GBK POST `triggerUrl` 为同源 `/forum/`、字节逐字节不变且跟随 302 到 200；服务端挂起响应头时 `timeout=300ms` 按期取消并抛 `IOException` | **PASS** |

---

## 3. FAIL 项详细复现与根因定位

### FAIL 1：`b(2)` solver 挂起永不返回 + 调用方超时取消导致网关永久锁死在 `Solving`

- **触发输入**：
  1. 首个 `UserBlocking` 请求命中 403，启动 `solver.solve(triggerUrl)`；solver 内部永久挂起（例如 `CompletableDeferred<SolveResult>().await()` 永不返回，模拟 WebView/挑战页异常卡死不回调）。
  2. 调用方使用 `withTimeout(250.milliseconds)`（或取消自身协程）。
  3. 调用方超时退出后，服务端已恢复可直接返回 200；调用 `gateway.resetCooldownForUserRetry()`，随后发起 `AccessPriority.UserExplicit` 请求（带 `withTimeout(1.seconds)`）以及 `AccessPriority.BackgroundSilent` 请求。
- **期望**：网关不应因为首轮唯一等待者已取消/超时而永久卡死在 `ChallengeState.Solving`；后续用户主动发起 `UserExplicit`（或孤儿验证超期/无人等待取消）应能解除僵死状态并恢复正常访问。
- **实际（CI 与本地复现输出）**：
  - `gateway.challengeState.value` 永久停留在 `Solving(startedAtMs=1795580937954)`。
  - `gateway.resetCooldownForUserRetry()` 无法恢复；`BackgroundSilent` 立即抛出 `ChallengeAbandonedException`。
  - 后续 `UserExplicit` 请求未发出任何网络包即在 `withTimeout(1000ms)` 处抛出 `TimeoutCancellationException: Timed out waiting for 1000 ms`。整个进程生命周期内 `SiteAccessGateway` 单例彻底不可用。
- **对应生产代码（文件:行号）**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:131-134`：`gatewayScope.launch { runSolve(targetSolve, triggerUrl) }` 未保存 `Job` 引用，无法取消。
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:139-150`：`runSolve` 调用 `solver.solve(triggerUrl)` 没有任何超时保护，也未在无活跃等待者时自动取消；`activeSolve` 仅在 `runSolve` 的 `finally -> completeSolve`（`SiteAccessGateway.kt:152-183`, 第 `156` 行）清空。
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:79-84`：只要 `activeSolve != null`，后续任何 `UserBlocking` / `UserExplicit` 请求都会无条件进入 `awaitOutcome(pendingSolve)`（`SiteAccessGateway.kt:185-189`）永久挂起；`BackgroundSilent` 在第 `80` 行直接放弃。
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:105-108`：`resetCooldownForUserRetry()` 只清理冷却和失败计数，不清理/取消僵死的 `activeSolve`。

### FAIL 2：`g` 一次性 POST 请求体（`isOneShot() == true`）重试二次写入，导致静默发空体或崩溃

- **触发输入**：
  - 用法 1（流式 `isOneShot() == true`）：`RequestBody` 包装单次消费流（如 `ByteArrayInputStream`，总计 29 字节 `subject=hello&message=oneshot`），发 POST 请求撞 403，solver 返回 `Cleared` 后网关自动重试。
  - 用法 2（严格式 `isOneShot() == true`）：`RequestBody` 在第 2 次调用 `writeTo(sink)` 时按 OkHttp 一次性约定抛出 `IllegalStateException("One-shot RequestBody cannot be written twice")`，发 POST 撞 403，solver 返回 `Cleared` 后网关自动重试。
- **期望（对照契约）**：契约要求“重试请求体字节不变”（以及对不可重放请求体应有明确处理/保护，不得在未检查的情况下二次写入导致静默丢体或触发 `IllegalStateException`；同包内 `ManualRedirectInterceptor.kt:58` 已对 `isOneShot()` 做了显式保护）。
- **实际（CI 与本地复现输出）**：
  - 用法 1（`probeG1`）：`writeTo` 被调用 2 次（`writeCount=2`）；第 2 次 POST 送到 `MockWebServer` 的请求体为空字符串 `""`（0 字节），且网关向调用方静默返回 HTTP `200`，业务侧无法察觉 POST 数据已丢失。CI 报错：`expected:<[subject=hello&message=oneshot]> but was:<[]>`。
  - 用法 2（`probeG2`）：`writeTo` 被调用 2 次（`writeCount=2`）；OkHttp 内部捕获 `IllegalStateException` 后取消调用，网关抛出 `java.io.IOException: canceled due to java.lang.IllegalStateException: One-shot RequestBody cannot be written twice`。
- **对应生产代码（文件:行号）**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:86-102`：`while (true)` 重试循环直接把同一个 `request` 对象再次传入 `executeCall(request, timeout)`（第 `87` 行），全程未检查 `request.body?.isOneShot() == true`，既未在首次发送前对允许大小内的一次性 body 做内存快照缓存，也未在无法重放时阻止/明确报错拒绝重试。
  - `hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt:222-243`：`executeCall` 直接将原 `request` 交给 `client.newCall(request)`。
