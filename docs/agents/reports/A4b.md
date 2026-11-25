# A4b 交付报告与详细证据

## 提交记录（分支 `card/A4b-access-gateway`，基于 `card/A4a-challenge-detector` @ `e75ef76`）

1. **公开类型定义（供 A5 基于此开发）**：`2a5cdbf9adb078d9bb3b5e0e11e5ee909f3316df`（`2a5cdbf`）
   - CI: https://github.com/mailwmj/hipda-next/actions/runs/45542248959 (`success`)
2. **网关与 `@RecoveryHttpClient` 实现及单元测试（a–j）**：`eb3043d416ebb5355c37ad84afbe2abe1e69740e`（`eb3043d`）
   - CI: https://github.com/mailwmj/hipda-next/actions/runs/45543050560 (`success`)
3. **追加 R-A4a 审查 4 条测试建议 + 夹具路径修正（未改 `ChallengeDetector.kt`）**：`3a22f8f0865ef2fb079e8ca7409c9df04807e55b`（`3a22f8f`）
   - CI: https://github.com/mailwmj/hipda-next/actions/runs/45544235439 (`success`，`TOTAL: 423 tests, 423 passed, 0 failed, 0 errors, 0 skipped`)

---

## 验收逐条对照

| 验收项 | 状态 | 证据 |
|---|---|---|
| 步骤 1 a–j 全部通过 | 通过 | `SiteAccessGatewayTest`（12/12）+ `HttpClientModuleTest`（5/5），CI `45544235439` |
| R-A4a 追加 4 项测试与夹具路径修正（不改 `ChallengeDetector.kt`） | 通过 | `ChallengeDetectorTest`（25/25，含新增 4 项探针/16KB 边界/GBK 与空头/IOException），CI `45544235439` |
| 网关与拦截器无 `runBlocking` / 无拦截器内阻塞等待 | 通过 | `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/access hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt` 无输出；全 `data/network/` 仅存 A2 既有的 `cookie/DataStoreSiteCookieStore.kt:13,109`（未改动） |
| 生产代码约 200–250 行且只在 `data/network/` | 通过 | 生产文件共 +362 行（在 250 × 1.5 = 375 行以内；含 44 行 import 与 KDoc/空行，可执行代码约 250 行），全部位于 `data/network/` |
| CI 全绿 | 通过 | commit `3a22f8f0865ef2fb079e8ca7409c9df04807e55b`，run https://github.com/mailwmj/hipda-next/actions/runs/45544235439 |

---

## 测试清单与 CI 证据（Run `45544235439`）

### A4b 网关与恢复客户端测试（a–j + 边界）

| 项 | 测试类:方法 | 结果 |
|---|---|---|
| a. 单次验证合流（10 并发） | `SiteAccessGatewayTest:singleFlight_tenConcurrentRequests_solvesOnceAndAllRetryWithFreshCookie` | PASSED |
| b. 单协程取消隔离（5 取消 1） | `SiteAccessGatewayTest:cancellationIsolation_cancellingOneOfFiveWaiters_keepsSharedSolveAliveForRemainingFour` | PASSED |
| c. 重试仍被拒与 60s 冷却 + `UserExplicit` | `SiteAccessGatewayTest:retryStillChallenged_limitsToTwoCalls_enforces60sCooldown_recoversAfter61s_andAllowsUserExplicit` | PASSED |
| d. 失败轮次去重与 30s 冷却 | `SiteAccessGatewayTest:failureRounds_coalescesFiveRequestsAsOneRound_threeRoundsTrigger30sCooldown_andRecoversAfter31s` | PASSED |
| e. `BackgroundSilent` 放弃与验证中不发包 | `SiteAccessGatewayTest:backgroundSilent_abandonsWithoutSolvingOrCounting_andSkipsNetworkWhileSolveActive` | PASSED |
| f. GBK 表单 POST → `/forum/` → 重试同字节 → 302 → GET 200 | `SiteAccessGatewayTest:gbkFormPostChallenge_triggersForumUrl_resendsExactBytes_andFollows302ToFinalUrl` | PASSED |
| g. 单次调用超时与 call 取消 | `SiteAccessGatewayTest:executeTimeout_whenServerStallsBeforeHeaders_throwsInterruptedIOExceptionAndCancelsCall` | PASSED |
| h. 协程层等待不占 OkHttp `Dispatcher` 名额 | `SiteAccessGatewayTest:suspendedChallengeWait_doesNotOccupyOkHttpDispatcherSlot` | PASSED |
| i. `@RecoveryHttpClient` 独立 `Dispatcher` | `HttpClientModuleTest:provideRecoveryOkHttpClient_usesIndependentDispatcherAndCompletesWhenPrimarySaturated` | PASSED |
| j. `challengeState` 状态流转 | `SiteAccessGatewayTest:challengeState_transitionsAcrossIdleSolvingIdleAndBothCooldownModes` | PASSED |
| 补充：重置失败连击 | `SiteAccessGatewayTest:resetCooldownForUserRetry_clearsFailureStreakBeforeThreshold` | PASSED |
| 补充：请求进行中协程取消同步取消 Call | `SiteAccessGatewayTest:callerCancellationDuringInFlightHttpCall_cancelsUnderlyingOkHttpCall` | PASSED |
| 补充：默认绑定与 Hilt 注解 | `SiteAccessGatewayTest:defaultChallengeSolverAndHiltBindings_areConfigured` / `HttpClientModuleTest:hiltModuleAnnotationsAreConfiguredForOkHttpClientAndUserAgent` | PASSED |

### R-A4a 追加 `ChallengeDetectorTest` 补强

| 建议项 | 测试方法 / 修改位置 | 结果 |
|---|---|---|
| 1. 读流抛 `AssertionError` 探针锁死 200/429/`403+cf-mitigated`/`403+application/json` 不调 `peekBody` | `ChallengeDetectorTest$BodyPreservationAndConfiguration:inspect_neverCallsPeekBody_forNonFallbackResponses` | PASSED |
| 2. 16KB 精确边界（末字节落界内命中 / 跨界 1 字节不命中） | `ChallengeDetectorTest$BodyPreservationAndConfiguration:inspect_exact16KbPeekBoundary_hitsAtLimitAndMissesWhenCrossingByOneByte` | PASSED |
| 3. `403` 无头 GBK 中文紧邻 `_cf_chl_opt` 命中；无 `Content-Type` 头但 `ResponseBody.contentType()` 非空（HTML 命中 / JSON 跳过） | `ChallengeDetectorTest$BodyPreservationAndConfiguration:inspect_gbkAdjacentMarkerAndFallbackBodyContentTypeWithoutHeader` | PASSED |
| 4. `peekBody` 的 `source()` 抛 `IOException` 返回 `ChallengeSignal.None` | `ChallengeDetectorTest$BodyPreservationAndConfiguration:inspect_returnsNone_whenPeekBodySourceThrowsIOException` | PASSED |
| 夹具路径修正 | `ChallengeDetectorTest.kt:155`（原第 146 行）：`/cdn-cgi/challenge-platform/h/b/scripts/jsd` → `/cdn-cgi/challenge-platform/h/b/scripts/alpha/invisible.js` | PASSED |

---

## Git Diff 与扫描输出

```text
$ git diff --stat origin/card/A4a-challenge-detector...HEAD
 hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt               |  21 +
 hipda/src/main/java/net/jejer/hipda/data/network/access/ChallengeSolver.kt         |  37 +
 hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessGateway.kt       | 264 ++++++
 hipda/src/main/java/net/jejer/hipda/data/network/access/SiteAccessTypes.kt         |  40 +
 hipda/src/test/java/net/jejer/hipda/data/network/ChallengeDetectorTest.kt          | 223 +++++-
 hipda/src/test/java/net/jejer/hipda/data/network/HttpClientModuleTest.kt           |  93 +++
 hipda/src/test/java/net/jejer/hipda/data/network/access/SiteAccessGatewayTest.kt   | 892 +++++++++++++++++++++
 7 files changed, 1569 insertions(+), 1 deletion(-)

$ rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/access hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt
(none)

$ gh run view 45544235439 --log | grep -E "ChallengeDetectorTest|SiteAccessGatewayTest|HttpClientModuleTest|TOTAL:"
  net.jejer.hipda.data.network.ChallengeDetectorTest$BodyPreservationAndConfiguration: 7 tests, 7 passed, 0 failed, 0 errors, 0 skipped
  net.jejer.hipda.data.network.ChallengeDetectorTest$DetectionMatrix: 18 tests, 18 passed, 0 failed, 0 errors, 0 skipped
  net.jejer.hipda.data.network.HttpClientModuleTest: 5 tests, 5 passed, 0 failed, 0 errors, 0 skipped
  net.jejer.hipda.data.network.access.SiteAccessGatewayTest: 12 tests, 12 passed, 0 failed, 0 errors, 0 skipped
TOTAL: 423 tests, 423 passed, 0 failed, 0 errors, 0 skipped
```

---

## 对外 API 清单（供 A5、A6、D2 使用）

### 1. 挑战求解器与可替换绑定（`net.jejer.hipda.data.network.access`，A5）
- `sealed interface SolveResult { data object Cleared; data object Cancelled; data class Failed(val cause: Throwable? = null) }` — `ChallengeSolver.kt:12-16`
- `fun interface ChallengeSolver { suspend fun solve(triggerUrl: HttpUrl): SolveResult }` — `ChallengeSolver.kt:19-21`
- `@Singleton class DefaultChallengeSolver @Inject constructor() : ChallengeSolver` — `ChallengeSolver.kt:24-28`
- `@Module @InstallIn(SingletonComponent::class) abstract class ChallengeSolverModule { @Binds @Singleton abstract fun bindChallengeSolver(impl: DefaultChallengeSolver): ChallengeSolver }` — `ChallengeSolver.kt:31-37`

### 2. 优先级、状态与异常（`net.jejer.hipda.data.network.access`，A6 / D2 / 业务层）
- `enum class AccessPriority { BackgroundSilent, UserBlocking, UserExplicit }` — `SiteAccessTypes.kt:6-10`
- `sealed interface ChallengeState { data object Idle; data class Solving(val startedAtMs: Long); data class Cooldown(val untilMs: Long, val rejected: Boolean) }` — `SiteAccessTypes.kt:13-17`
- `class ChallengeIncompleteException(val cooldownUntilMs: Long? = null, cause: Throwable? = null) : IOException` — `SiteAccessTypes.kt:20-30`
- `class ChallengeRejectedException(val cooldownUntilMs: Long) : IOException` — `SiteAccessTypes.kt:33-35`
- `class ChallengeAbandonedException(message: String = ...) : IOException` — `SiteAccessTypes.kt:38-40`

### 3. 站点访问网关（`net.jejer.hipda.data.network.access`，A6 / D2 / 业务层）
- `@Singleton class SiteAccessGateway @Inject constructor(client: OkHttpClient, detector: ChallengeDetector, solver: ChallengeSolver)` — `SiteAccessGateway.kt:33-46`
- `val challengeState: StateFlow<ChallengeState>` — `SiteAccessGateway.kt:66`
- `suspend fun execute(request: Request, priority: AccessPriority = AccessPriority.UserBlocking, timeout: Duration = DEFAULT_TIMEOUT): Response` — `SiteAccessGateway.kt:68-103`
- `fun resetCooldownForUserRetry()` — `SiteAccessGateway.kt:105-108`
- `Companion`: `DEFAULT_TIMEOUT = 30.seconds`, `REJECTED_COOLDOWN_MS = 60_000L`, `FAILURE_COOLDOWN_MS = 30_000L`, `MAX_CONSECUTIVE_FAILURES = 3`, `FALLBACK_CHALLENGE_PATH = "/forum/"` — `SiteAccessGateway.kt:257-263`

### 4. 恢复用独立 `OkHttpClient`（`net.jejer.hipda.data.network`，D2）
- `@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class RecoveryHttpClient` — `HttpClientModule.kt:20-22`
- `@Provides @Singleton @RecoveryHttpClient fun provideRecoveryOkHttpClient(primaryClient: OkHttpClient): OkHttpClient` — `HttpClientModule.kt:57-66`
