# 复验报告 V-A4b-r2：站点访问网关修复后独立探针复验

## 1. 结论摘要

- **总判定**：**PASS**（第 1 轮两项 FAIL 已修复并由独立探针与 CI 全绿实证）
- **被验对象**：`origin/card/A4b-access-gateway` @ `137376a208da059f50583f198c9f82e9bc03303b`（`137376a`）
  - 较第 1 轮（`3a22f8f`）新增修复提交：`076dd92`（共享验证总超时 + `UserExplicit` 取消卡住旧验证 + 一次性请求体不重放）与 `137376a`（消除取消测试跨线程竞态）
- **探针分支 / Commit**：`verify/A4b-probes` @ `9a61dfe0a057c8ea5ac96c550dc7fee5df803f14`（worktree `/home/mir/Documents/hipda-next.wt/V-A4b` 已保留）
- **探针文件**：`hipda/src/test/java/net/jejer/hipda/verify/A4bProbeTest.kt`
- **CI 证据**：https://github.com/mailwmj/hipda-next/actions/runs/45549855776 （`success`）
  - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：通过
  - `Verify Room schema consistency`：通过
  - `Run unit tests and verify Roborazzi screenshots`：通过
  - `Verify APK outputs`：通过
  - CI `Summarize unit test results`：
    - `net.jejer.hipda.verify.A4bProbeTest: 12 tests, 12 passed, 0 failed, 0 skipped`
    - `TOTAL: 441 tests, 441 passed, 0 failed, 0 errors, 0 skipped`
- **本地验证**：
  - `./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.A4bProbeTest"`：`12 tests, 12 passed, 0 failed, 0 skipped`
  - `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/access hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt`：无结果。

---

## 2. 探针明细（a–h + 额外契约探针）

| 探针项 | 测试方法 (`A4bProbeTest.kt`) | 断言要点与复验结论 | 结果 |
|---|---|---|---|
| **a** | `probeA_fiftyCoroutinesConcurrentChallenge_randomHalfCancelled` (`L146`) | 50 协程并发撞 403，固定种子 `Random(20261125L)` 取消 25 个；`solve` 严格 1 次；25 个抛 `CancellationException`；25 个携 `cf_clearance=fresh` 重试 200；状态回 `Idle` | **PASS** |
| **b(1)** | `probeB1_solverThrowsRuntimeException_singleFailureRoundAndRecoversToIdle` (`L245`) | 3 并发等待时 solver 抛 `RuntimeException`；全员收 `ChallengeIncompleteException`、无异常泄漏、失败轮次仅计 1 轮（第 2 轮不进冷却），第 3 轮恢复 200，状态 `Idle` | **PASS** |
| **b(2a)** | `probeB2a_solverHangsForever_solveTimeoutResetsStateCountsOneRoundAndAllowsRetry` (`L349`) | 默认 `DEFAULT_SOLVE_TIMEOUT == 120.seconds`；注入 `solveTimeout = 250ms`、solver 永久挂起且调用方 80ms 超时后，看门狗在 250ms 取消卡住 solver 并将状态复位 `Idle`，精确计 1 轮失败（第 2 轮不进冷却），第 3 轮清盾 200 | **PASS** |
| **b(2b)** | `probeB2b_stuckSolve_userExplicitCancelsAndRecoversWithoutWaitingFullTimeout` (`L432`) | 默认 `120s` 超时下 solver 永久挂起且调用方 100ms 超时；存在 `UserBlocking` 等待者时发起 `UserExplicit`：立即摘除并取消旧 solver、旧等待者收 `ChallengeIncompleteException`、2s 内（无需等满 120s）重跑 solver 拿 200，后续 `UserBlocking` 直接 200 | **PASS** |
| **c** | `probeC_inFlightOldEpoch403EnteringResolveAfterSolveCompletes_retriesWithoutSecondSolve` (`L514`) | 旧世代 B 在世代 `0 -> 1` 完成且 `Idle` 后才带 403 进入 `resolveChallenge`，不触发第 2 次 `solve`，直接携新 Cookie 重试 200 | **PASS** |
| **d** | `probeD_cooldownBoundariesWithVirtualClock` (`L578`) | 60s 拒绝冷却在 `t+59_900ms` 拒发（`ChallengeRejectedException`）/`t+60_100ms` 恢复；30s 失败冷却在 `t+29_900ms` 拒发（`ChallengeIncompleteException`）/`t+30_100ms` 恢复 | **PASS** |
| **e** | `probeE_userExplicitClears60sAnd30sCooldowns` (`L665`) | 60s 与 30s 冷却期内 `UserExplicit` 过盾 200 后，紧接着 `UserBlocking` 不受冷却拦截直接 200 | **PASS** |
| **f** | `probeF_backgroundSilentTwoPaths` (`L763`) | (1) 验证中 `BackgroundSilent` 零网络包抛 `ChallengeAbandonedException`；(2) 空闲撞 403 连发 5 次抛 `ChallengeAbandonedException`、零 `solve`、不增失败轮次 | **PASS** |
| **g(1)** | `probeG1_oneShotStreamingPostBody_onChallengeThrowsIncompleteWithoutEmptyReplay` (`L854`) | 流式 `isOneShot() == true` POST 撞 403 时直接抛 `ChallengeIncompleteException`，`writeCount == 1`、服务端只收到 1 次完整请求（无空体二次重放）、零 `solve`、状态 `Idle` | **PASS** |
| **g(2)** | `probeG2_oneShotStrictPostBody_onChallengeThrowsIncompleteWithoutIllegalStateException` (`L909`) | 严格式 `isOneShot() == true`（二次写入抛 `IllegalStateException`）POST 撞 403 直接抛 `ChallengeIncompleteException`（不触发 `IllegalStateException`）、`writeCount == 1`；无盾 200 场景仍正常一次性发完拿 200 | **PASS** |
| **h** | `probeH_recoveryClientSucceedsWhilePrimarySaturated` (`L988`) | 主客户端 `maxRequests=1` 被挂起请求与排队请求占满时，`@RecoveryHttpClient` 2s 内完成 200；`dispatcher` 与 `executorService` 独立且线程名为 `OkHttp Recovery Dispatcher-*`；拦截器类型顺序一致 | **PASS** |
| **extra** | `probeExtra_triggerUrlGbkPostBytePreservationAndCallTimeout` (`L1107`) | GET `triggerUrl` 保留原 URL/查询串；普通 GBK POST `triggerUrl` 为同源 `/forum/`、字节一致并跟 302 到 200；挂起响应头时 `timeout=300ms` 取消调用抛 `IOException` | **PASS** |

---

## 3. 原 FAIL 项修复核验（生产代码对照）

1. **共享验证防卡死与 `UserExplicit` 立即解卡**：
   - `SiteAccessGateway.kt:44, 61, 184-215, 230-271, 347`：主构造函数增加 `solveTimeout: Duration = DEFAULT_SOLVE_TIMEOUT`（默认 `120.seconds`）。`launchSolve` 以 `CoroutineStart.LAZY` 登记 `solverJob` 与 `timeoutJob` 避免竞态；超时触发 `completeSolve(..., cancelSolver = true)` 原子复位 `activeSolve = null`、计 1 轮失败（满 3 轮进 30s 冷却否则回 `Idle`）、取消 `solverJob` 并唤醒等待者。
   - `SiteAccessGateway.kt:102-105, 146-149, 170-182`：`UserExplicit` 在 `activeSolve != null` 时通过 `detachActiveSolveLocked()` 原子置 `old.completed = true` 并摘除 `activeSolve`，锁外调用 `cancelDetachedSolve` 取消旧任务并唤醒旧等待者后立即重新发请求/验证，防止旧协程收尾污染新状态。
2. **一次性请求体（`isOneShot() == true`）防重放**：
   - `SiteAccessGateway.kt:126`：撞盾后、发起验证/重试前判定 `if (request.body?.isOneShot() == true) throw ChallengeIncompleteException()`，与 `ManualRedirectInterceptor.kt:58` 保持一致；无盾时允许一次性流式上传正常通过，撞盾后不再二次写入导致发空体或抛 `IllegalStateException`。
