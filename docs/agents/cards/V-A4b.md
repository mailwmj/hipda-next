# 验证卡 V-A4b：独立探针验证站点访问网关与独立恢复客户端（commit 3a22f8f）

【角色】你是独立验证者。你不是实现者，也没有看过实现者的自评；结论只能来自你自己跑出来的证据。

【边界】被验证对象：`origin/card/A4b-access-gateway` @ `3a22f8f0865ef2fb079e8ca7409c9df04807e55b`，只读：不得向 `card/A4b-access-gateway` 推送，不得修改 `main`。你的工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A4b -b verify/A4b-probes 3a22f8f0865ef2fb079e8ca7409c9df04807e55b`，只在这里写文件；不要在 `/home/mir/Documents/hipda-next` 主目录 checkout 或写文件。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再按 `docs/agents/DISPATCH.md` 第 2 节用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`。**先在本地把全部探针跑通再推一次 CI**：推送 `verify/A4b-probes` 后，因 `android.yml` 的 `push` 只匹配 `main` 和 `card/**`，用 `gh workflow run android.yml --ref verify/A4b-probes` 触发一次 CI，再用 `gh run watch <id> --interval 60` 等结果（不用默认间隔、不写轮询循环）。不访问真实论坛，网络只用 MockWebServer。详细验证报告写到 `/home/mir/Documents/hipda-next.wt/_reports/V-A4b.md`。

【背景】契约（任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A4b.md` 第“范围”节）：
- `SiteAccessGateway.execute(request, priority, timeout)`：同一时间只验证一次，其余请求在协程层挂起等结果（不占 OkHttp 线程）；每个请求最多因验证重试 1 次；调用方取消只取消自身等待，不中止共享验证。
- 世代号合流：验证进行中或并发撞盾的请求，若当前世代号已大于发出时世代号，直接重试而不重复调用 solver。
- 两类冷却：重试仍被拒 → 60 秒无效凭证冷却（`ChallengeState.Cooldown(rejected = true)`，`ChallengeRejectedException`）；验证取消/失败按轮次去重计次，满 3 轮 → 30 秒冷却（`ChallengeState.Cooldown(rejected = false)`，`ChallengeIncompleteException`）；`UserExplicit` 清除冷却并允许发起。
- 后台放弃：`BackgroundSilent` 在验证进行中直接放弃（不发网络包），撞验证页直接放弃，均抛 `ChallengeAbandonedException` 且不计失败轮次。
- 重试请求：`triggerUrl` 为 GET 原 URL / 非 GET 同源 `/forum/`；重试请求体字节不变；每次调用受 `timeout` 约束，协程取消时 `call.cancel()`。
- `@RecoveryHttpClient`：使用独立 `Dispatcher` 线程池且拦截器链与主客户端相同。

【步骤】
1. 只看生产代码（`hipda/src/main/java/net/jejer/hipda/data/network/access/` 下全部文件及 `HttpClientModule.kt`），不要先读实现者的测试。
2. 在 `hipda/src/test/java/net/jejer/hipda/verify/A4bProbeTest.kt` 写独立探针，至少覆盖以下 8 项：
   a. **50 协程并发撞盾 + 随机取消一半**：50 个协程并发请求全部命中 403 验证页，假 solver 挂起期间用固定种子随机取消 25 个协程，随后 solver 写入新 `cf_clearance` 并返回 `Cleared`。断言 `solve` 严格只调用 1 次、被取消的 25 个协程以 `CancellationException` 结束、未取消的 25 个全部重试拿到 200 且携带新 Cookie、最终 `challengeState` 回到 `Idle`。
   b. **solver 抛异常与挂起永不返回**：(1) solver 抛出未捕获 `RuntimeException` 时，所有等待者收到 `ChallengeIncompleteException`（不崩溃/不泄漏异常），失败轮次只计 1 轮，`challengeState` 复位到 `Idle` 且后续请求仍可正常发起新一轮验证；(2) solver 挂起永不返回且调用方自身 `withTimeout` / 取消时，核验共享验证状态是否会永久卡在 `Solving` 导致后续请求无法恢复（如卡死则记为 FAIL 并附复现路径与文件:行号）。
   c. **验证进行中又有新验证页响应（世代号已变化）**：A 请求撞盾启动 solver 挂起；在 solver 完成前已发出的 B 请求也带旧世代号返回 403 验证页（在 solver 刚完成、世代号递增后才进入 `resolve`）。断言 B 不触发第 2 次 `solve`，直接用新 Cookie 重试并 200。
   d. **冷却边界（虚拟时钟）**：(1) 60 秒无效凭证冷却：在 `t + 59_900ms` 发起普通请求仍抛 `ChallengeRejectedException` 且不调 solver；推进到 `t + 60_100ms` 恢复通行。(2) 30 秒 3 轮失败冷却：在 `t + 29_900ms` 仍抛 `ChallengeIncompleteException`；推进到 `t + 30_100ms` 恢复通行。
   e. **`UserExplicit` 清冷却**：在 60 秒和 30 秒冷却期内，用 `AccessPriority.UserExplicit` 发起请求并验证成功后，断言冷却已清除，紧接着普通 `UserBlocking` 请求不再被冷却拦截且正常 200。
   f. **`BackgroundSilent` 两条路径**：(1) 验证进行中发出的 `BackgroundSilent` 立即抛 `ChallengeAbandonedException`，且 MockWebServer 收到的请求总数零增加；(2) 空闲期发出的 `BackgroundSilent` 遇 403 验证页立即抛 `ChallengeAbandonedException`，不调 solver，不增加失败轮次（连续 5 次后普通请求不进入冷却）。
   g. **POST 一次性请求体（`isOneShot() == true`）的重试行为**：用自定义 `isOneShot() = true` 的 `RequestBody` 发 POST 撞 403 并过盾，探明并断言网关的实际行为（是否会二次写入抛 `IllegalStateException`/发空体，还是有明确保护），对照契约判断是 PASS 还是 FAIL。
   h. **主客户端满载时 `@RecoveryHttpClient` 仍可完成**：将主客户端 `Dispatcher.maxRequests = 1` 并用挂起请求占满，同时通过 `HttpClientModule.provideRecoveryOkHttpClient` 构造的恢复客户端发请求，断言恢复请求在短时间内 200 完成、两者 `dispatcher` 不同实例、且拦截器类型顺序一致。
3. 先在本地按 DISPATCH §2 跑通 `:hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.A4bProbeTest"`，确认探针本身无误后再提交、推送 `verify/A4b-probes`，运行 `gh workflow run android.yml --ref verify/A4b-probes` 并 `gh run watch <id> --interval 60` 取得最终 CI 结果。探针失败时先区分是实现缺陷还是探针写错（依据契约），只有实现缺陷才算 FAIL。

【验收】
- [ ] a–h 全部探针已编写并在本地与 CI 执行完毕（每个探针给出名称、断言要点、结果）。证据：CI run 链接 + 测试汇总中 `A4bProbeTest` 行。
- [ ] 每个 FAIL 给出触发输入、期望、实际与对应生产代码 文件:行号。

【交付】
- 详细探针报告与失败复现证据写入 `/home/mir/Documents/hipda-next.wt/_reports/V-A4b.md`。
- 最后一条回复不超过 600 字，第一个词只能是 DONE（完成验证，无论 PASS 或 FAIL）、NEEDS-INPUT 或 STUCK；随后写：总判定 PASS/FAIL + 被验 commit `3a22f8f`、CI run 链接、每个探针一行（通过/失败）、FAIL 项触发路径，以及详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-A4b.md`。不要在拿到 CI 结果前结束回合。验证结束后保留 `verify/A4b-probes` 分支和 worktree。
