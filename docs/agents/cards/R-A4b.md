# 审查卡 R-A4b：独立代码审查站点访问网关与独立恢复客户端（commit 3a22f8f）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/A4b-access-gateway` @ `3a22f8f0865ef2fb079e8ca7409c9df04807e55b`，相对 `294c705f4abe6e3a67757caac54ac16a40ae5779`（`main` 上 A4a 的合并；merge-base 为 `e75ef76b55918467d86e8f29e0310d58c74d8694`）。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 3a22f8f:<路径>`、`git diff 294c705...3a22f8f`（务必用三点 diff，避免混入 main 上的纯文档提交）读代码；不要 checkout、提交或推送，不要在主目录切换分支，不要编译。详细审查报告写到 `/home/mir/Documents/hipda-next.wt/_reports/R-A4b.md`。CI 用 `gh run view 45544235439`。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/A4b.md`（范围和验收以它为准）；规格 `docs/design/tech-plan.md` 第 6 节“流程”“并发”“排队不能占住连接（T5）”、`docs/design/cloudflare-research.md` 第五节第 4–5 小节与第六节第 2 小节、`docs/design/product-design.md` 第 7.12 节。
- 后续依赖：A5b 会替换 `ChallengeSolverModule` 的绑定提供真实 WebView 求解器；A6 会订阅 `SiteAccessGateway.challengeState` 在后台等待 2 秒后显示提示及区分两类冷却文案；D2 会使用 `@RecoveryHttpClient` 发静默重新登录请求。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节）：CI 失败、登录/Cookie/GBK/Cloudflare 通道失效、违反卡片范围或声明、破坏既有测试/契约——必须给出具体触发路径。其他都是建议。敏感区，用最高审查强度。

【审查重点】
1. **单次验证合流的竞态**：锁（`Mutex` / `synchronized`）与 `CompletableDeferred` 的配合是否严密；世代号（`epochAtSend` vs 当前世代号）比较是否存在漏检或二次触发窗口；等待者协程取消是否只取消自己的 `await()` 而不中止共享验证；`solver.solve` 是否在网关自己的 `SupervisorJob` 作用域内运行，solver 抛普通异常或 `CancellationException` 时异常是否不泄漏给作用域且必然清理在途状态（不把网关永久卡死在 `Solving`）。
2. **失败轮次计数**：同一轮合流的多个并发等待者遇到 `Cancelled`/`Failed` 时，失败轮次是否严格只计 1 次；满 3 轮进入 30 秒冷却（`rejected = false`），验证成功后是否清零。
3. **两类冷却与主动重试**：60 秒无效凭证冷却（`rejected = true`）与 30 秒连续失败冷却（`rejected = false`）的虚拟时钟计时边界是否正确；`UserExplicit` 与 `resetCooldownForUserRetry()` 清除冷却和失败轮次的时机是否竞态安全；冷却过期后是否自然恢复通行。
4. **`BackgroundSilent` 两条放弃路径**：(a) 验证进行中发起的 `BackgroundSilent` 是否立即抛 `ChallengeAbandonedException`、不发网络包、不调用 solver、不计失败轮次；(b) 请求发出后命中验证页是否关闭响应、立即抛 `ChallengeAbandonedException`、不调用 solver、不计失败轮次。
5. **重试上限与请求体安全**：每个请求是否最多因验证重试 1 次、重试仍被拒是否立即进入 60 秒冷却并抛 `ChallengeRejectedException`；`triggerUrl` 是否满足 GET 用原 URL、非 GET 用同 scheme/host 的 `/forum/`；重试时请求体字节是否不变；**重点检查一次性请求体（`RequestBody.isOneShot() == true`）时会发生什么**（是否会静默发空体、抛异常或已做保护）。
6. **超时、取消与连接防泄漏**：`call.timeout().timeout(timeout)` 是否在首次与重试调用上都生效、是否覆盖 Cronet 响应头挂起风险；协程取消时是否可靠执行 `call.cancel()`；响应在命中验证页、异常、取消等路径上是否全部安全 `close()`。
7. **协程层排队不占 OkHttp 调度名额**：验证等待是否完全在挂起层完成、不阻塞 OkHttp `Dispatcher` 线程、不修改任何 OkHttp 拦截器；生产代码无 `runBlocking`。
8. **`@RecoveryHttpClient` 独立 Dispatcher**：检查 `HttpClientModule.provideRecoveryOkHttpClient` 线程池参数（daemon 线程、空闲回收 `keepAliveTime`、是否存在线程/连接池泄漏风险）以及拦截器链是否与主客户端完全一致。
9. **`ChallengeState` `StateFlow` 状态转换**：`Idle` / `Solving(startedAtMs)` / `Cooldown(untilMs, rejected)` 的全部状态迁移是否与 `docs/design/product-design.md` §7.12 文案语义（2 秒等待提示、`安全验证未完成`、`验证未通过`）一致；冷却到期后 `challengeState.value` 行为是否合理、有无状态倒退或残留。
10. **R-A4a 追加的 4 条检测器测试（commit `3a22f8f`，`ChallengeDetectorTest.kt`）**：逐条核对 `inspect_neverCallsPeekBody_forNonFallbackResponses`、`inspect_exact16KbPeekBoundary_hitsAtLimitAndMissesWhenCrossingByOneByte`、`inspect_gbkAdjacentMarkerAndFallbackBodyContentTypeWithoutHeader`、`inspect_returnsNone_whenPeekBodySourceThrowsIOException` 是否真能在改坏 `ChallengeDetector` 对应实现时失败。
11. **复杂度与测试质量**：检查 `SiteAccessGateway.kt` 与 `SiteAccessTypes.kt` 是否有可精简之处；A4b 单测是否足够有力（若存在“改坏关键一行测试仍通过”的缺口请举例）。

【交付】
- 详细审查分析、逐项核对证据（文件:行号）与测试质量核验写入 `/home/mir/Documents/hipda-next.wt/_reports/R-A4b.md`。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、NEEDS-INPUT 或 STUCK；随后写：判定 `APPROVE` / `CHANGES-REQUESTED` + 被审 commit `3a22f8f`、阻断项（触发路径 + 文件:行号 + 后果，无则写“无”）、建议项一行一条，并给出详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-A4b.md`。
