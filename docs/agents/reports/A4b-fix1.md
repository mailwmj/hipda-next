# A4b 第 1 轮修改报告（V-A4b 阻断修复 + 建议收口 + R-A4b 盲区补测）

- 分支：`card/A4b-access-gateway`（worktree `/home/mir/Documents/hipda-next.wt/A4b`）
- 公开类型 commit（供 A5，未改签名）：`2a5cdbf9adb078d9bb3b5e0e11e5ee909f3316df`
- 本轮修改 commit：
  - `076dd92` — `fix(network): 增加共享验证总超时与 UserExplicit 解卡，禁止一次性请求体撞盾重放`
  - `137376a` — `test(network): 用 CompletableDeferred 消除取消断言的跨线程竞态`
- 最终 commit：`137376a208da059f50583f198c9f82e9bc03303b`（`137376a`）
- 最终 CI：https://github.com/mailwmj/hipda-next/actions/runs/45549137841 （`success`，`TOTAL: 429 tests, 429 passed, 0 failed, 0 errors, 0 skipped`）

---

## 1. 阻断项与一次性请求体在旧实现（`3a22f8f`）上的本地失败复现输出

先在未改 `SiteAccessGateway.kt` 的旧实现上新增复现测试并执行本地 `:hipda:testDebugUnitTest`，确认两项均复现失败：

```text
SiteAccessGatewayTest > stuckSolver_afterCallerTimeout_userExplicitCancelsStuckSolveAndRecovers FAILED
    kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 2000 ms
        at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
        at net.jejer.hipda.data.network.access.SiteAccessGateway.awaitOutcome(SiteAccessGateway.kt:186)
        at net.jejer.hipda.data.network.access.SiteAccessGateway.execute-exY8QGI(SiteAccessGateway.kt:84)

SiteAccessGatewayTest > oneShotRequestBody_onChallenge_doesNotReplayEmptyBodyOrThrowIllegalState FAILED
    java.lang.AssertionError: Expected ChallengeIncompleteException, but completed normally
        at net.jejer.hipda.data.network.access.SiteAccessGatewayTest$oneShotRequestBody_onChallenge_doesNotReplayEmptyBodyOrThrowIllegalState$1.invokeSuspend(SiteAccessGatewayTest.kt:1036)

[:hipda:testDebugUnitTest] TOTAL: FAILURE (2 tests, 0 passed, 2 failed, 0 skipped)
```

---

## 2. 逐项修改与设计说明

### 2.1 阻断项：共享验证总超时 + `UserExplicit` 立即解卡 + `resetCooldownForUserRetry()` 非阻塞
- **修改位置**：
  - `SiteAccessGateway.kt:38-62, 347`：主构造增加 `solveTimeout: Duration = DEFAULT_SOLVE_TIMEOUT`（默认 `120.seconds`），`@Inject` 构造传入 `DEFAULT_SOLVE_TIMEOUT`（不改 Hilt 绑定与公开类型签名）。
  - `SiteAccessGateway.kt:64-69, 184-215, 230-271`：`ActiveSolve` 持有 `solverJob` 与 `timeoutJob`；`launchSolve` 以 `CoroutineStart.LAZY` 登记两个 Job 后启动，超时看门狗在 `solveTimeout` 到期后调用 `completeSolve(..., SolveResult.Failed(TimeoutException(...)), cancelSolver = true)`，按 `Failed` 计 1 轮（满 3 轮进 30s `Cooldown(rejected = false)`，否则恢复 `Idle`），复位 `activeSolve = null`，唤醒所有等待者并取消 `solverJob`；即便底层 solver 不可取消，网关状态也能立刻复位。正常结束时 `completeSolve` 立即取消 `timeoutJob`。
  - `SiteAccessGateway.kt:94-109, 141-158, 170-182`：**设计选择——`UserExplicit` 取消旧的共享验证并立即重新发起**（而非跟着旧验证等满 120 秒）。理由：当隐身或可见验证已卡住、用户主动点“重新验证”时，不应再被卡死 120 秒；`detachActiveSolveLocked` 原子摘除旧 `activeSolve` 并置 `completed = true`（防止旧协程收尾污染新状态或连击计数），在锁外取消旧 `timeoutJob`/`solverJob` 并以 `Incomplete` 唤醒旧等待者，随后 `UserExplicit` 立即发请求并开启新一轮验证。
  - `SiteAccessGateway.kt:133-136`：`resetCooldownForUserRetry()` 保持纯同步 `synchronized(stateLock)` 清冷却与失败轮次，绝不挂起或阻塞。
- **证明测试**：
  - `SiteAccessGatewayTest:stuckSolver_solveTimeoutResetsStateCountsFailedRoundAndAllowsSubsequentSolve`
  - `SiteAccessGatewayTest:stuckSolver_afterCallerTimeout_userExplicitCancelsStuckSolveAndRecovers`

### 2.2 建议 1：一次性请求体（`isOneShot() == true`）撞盾不重放
- **修改位置**：`SiteAccessGateway.kt:126`：`if (request.body?.isOneShot() == true) throw ChallengeIncompleteException()`。
- **设计选择**：采用**撞盾时不重试、直接抛 `ChallengeIncompleteException`**（而非入口 `require` 拒绝），与同仓库 `ManualRedirectInterceptor.kt:58` 保持一致。理由：通行证有效时一次性流式上传仍可正常一次发完拿 200；仅当实际撞盾、请求体已被消费时拒绝重放，既不发空体也不抛 `IllegalStateException`，交由上层重建请求体后重试。
- **证明测试**：`SiteAccessGatewayTest:oneShotRequestBody_onChallenge_doesNotReplayEmptyBodyOrThrowIllegalState`（同时覆盖流式二次写空、严格二次写抛 `IllegalStateException`、以及 200 正常放行三种场景）。

### 2.3 建议 2：R-A4b 3 个测试盲区补齐
- 慢 403 命中 `clearanceEpoch > epochAtSend` 直通重试（`SiteAccessGateway.kt:151-154`）→ `SiteAccessGatewayTest:slow403ArrivingAfterClearanceEpochAdvanced_retriesDirectlyWithoutReSolving`
- `solver.solve` 抛 `RuntimeException` / `CancellationException` 收敛（`SiteAccessGateway.kt:217-228`）→ `SiteAccessGatewayTest:solverThrowingExceptionOrCancellation_convergesCleanlyWithoutStickingGateway`
- “2 败 → `Cleared` → 2 败”清零连击不进冷却（`SiteAccessGateway.kt:240-245`）→ `SiteAccessGatewayTest:twoFailuresThenClearedThenTwoFailures_resetsStreakAndDoesNotEnterCooldown`

---

## 3. 行数、扫描与 CI 汇总

- 生产代码变更（`git diff --stat origin/card/A4a-challenge-detector...HEAD`）：
  - `HttpClientModule.kt` +21、`ChallengeSolver.kt` 37、`SiteAccessTypes.kt` 40、`SiteAccessGateway.kt` 353，合计 451 行（含 48 行 import 与 KDoc/空行；本次为修 V-A4b 阻断新增共享验证超时看门狗、LAZY Job 竞态保护与 `UserExplicit` 取消重来逻辑约 +89 行）。
- `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/access hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt`：无输出。
- CI Run `45549137841`（commit `137376a`）：
  - `ChallengeDetectorTest`: 25/25 PASSED
  - `HttpClientModuleTest`: 5/5 PASSED
  - `SiteAccessGatewayTest`: 18/18 PASSED
  - `TOTAL: 429 tests, 429 passed, 0 failed, 0 errors, 0 skipped`
