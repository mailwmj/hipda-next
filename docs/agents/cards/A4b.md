# 任务卡 A4b：站点访问网关（协程层排队、单次验证、重试上限、冷却、后台放弃、独立恢复 Dispatcher）

【角色】你是 hipda-next 的 Android 网络层开发者（Kotlin 协程、OkHttp 5）。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/A4b`（分支 `card/A4b-access-gateway`，基于 `card/A4a-challenge-detector` @ e75ef76；A4a 正在审查，若审查要求改动，负责人会通知你 rebase）里工作。只改 `hipda/src/main/java/net/jejer/hipda/data/network/`（新代码放 `data/network/access/`；`HttpClientModule.kt` 只为第 5 项增加一个限定的客户端）与对应测试目录。不改 `ChallengeDetector.kt`（只调用）、不改 A3a/A3b 的拦截器。不做 WebView、不做任何界面（A5、A6）、不做登录（D2）。敏感区：生产代码约 200–250 行。

【背景】必读：
- `docs/design/tech-plan.md` 第 6 节“流程”“并发”“排队不能占住连接（T5）”。
- `docs/design/cloudflare-research.md` 第五节第 4、5 小节（单次验证合流、世代号、冷却）、第六节第 2 小节测试矩阵（本卡实现其中“并发排队”“单协程取消隔离”“防递归与无效凭证冷却”“失败轮次去重与退避冷却”“GBK 表单 POST + 302 重试”五行）。
- `docs/design/product-design.md` 第 7.12 节（取消或连续失败 → `安全验证未完成`；过了验证仍被拒绝（60 秒冷却）→ `验证未通过`；用户点“重新验证”是主动操作，不受冷却限制；后台等待超过 2 秒显示 `正在检查安全环境…`——本卡只提供状态，不做界面）。
- 现有：`data/network/ChallengeDetector.kt`（A4a：`inspect(response): ChallengeSignal`）、`HttpClientModule.kt`（拦截器链 Redirect → Header → Cookie → Transport）、`cookie/SiteCookieStore.kt`。
- 审查遗留（R-A3b 建议 1，原文）：连接与首字节超时在 Cronet 下不受 `connectTimeout`/`readTimeout` 约束：`cronet-okhttp:0.1.2` 用无超时的 `getUninterruptibly(headersFuture)` 等响应头，`readTimeout` 只在收到响应头后生效；建议在协程层加单次调用超时（`Call.cancel()` 后 `CronetInterceptor` 会在 500ms 内取消底层 `UrlRequest`）。
- `docs/agents/lesson.md`：本地 Gradle 先导出 mise 的 ANDROID_HOME/JAVA_HOME；Robolectric + `OkHttp.initialize`。

【目标】业务代码通过 `SiteAccessGateway.execute(request, priority)` 发请求：同一时间只验证一次，其余请求在协程层挂起等结果（不占 OkHttp 线程）；每个请求最多因验证重试 1 次；两类冷却；后台请求遇到验证直接放弃；每次调用有超时。

【范围】（新代码在 `data/network/access/`）
1. 类型：
   - `enum class AccessPriority { BackgroundSilent, UserBlocking, UserExplicit }`
   - `fun interface ChallengeSolver { suspend fun solve(triggerUrl: HttpUrl): SolveResult }`，`sealed interface SolveResult { data object Cleared; data object Cancelled; data class Failed(val cause: Throwable?) }`。A5 会提供真实实现；本卡提供一个默认绑定（直接返回 `Failed`），放在可单独替换的 Hilt `@Module` 里。
   - 对外异常（供以后的错误映射使用，消息里不放用户文案）：`ChallengeIncompleteException`（用户取消或连续失败进入冷却）、`ChallengeRejectedException(cooldownUntilMs)`（过了验证仍被拒绝）、`ChallengeAbandonedException`（后台请求遇到验证而放弃）。
   - `sealed interface ChallengeState { Idle; Solving(startedAtMs); Cooldown(untilMs, rejected: Boolean) }`，以 `StateFlow<ChallengeState>` 暴露（A6 据此在 2 秒后显示提示）。
2. `SiteAccessGateway`（`@Singleton`）：`suspend fun execute(request: Request, priority: AccessPriority = AccessPriority.UserBlocking, timeout: Duration = 30.seconds): Response`
   - 发出前：处于冷却且 `priority != UserExplicit` → 立即抛对应异常；`UserExplicit` 清除冷却。有验证正在进行：`BackgroundSilent` 立即抛 `ChallengeAbandonedException`；其他优先级挂起等这次验证结束。
   - 发送：`client.newCall(request)`，设置 `call.timeout().timeout(timeout)`，用 `enqueue` + `suspendCancellableCoroutine` 桥接，协程取消时 `call.cancel()`。不得在 OkHttp 拦截器里阻塞等待。
   - 收到响应后 `detector.inspect(response)`：`None` → 返回响应。`Challenge` → 关闭响应，然后：
     - 这已经是验证后的重试 → 进入 60 秒“无效凭证”冷却（`ChallengeState.Cooldown(rejected = true)`），抛 `ChallengeRejectedException`。
     - `BackgroundSilent` → 不发起验证，抛 `ChallengeAbandonedException`（不计失败轮次）。
     - 否则 `resolve(epochAtSend)`：持 `Mutex`；若当前世代号 > `epochAtSend`（别人已经验证成功）→ 直接重试；若已有进行中的验证（`CompletableDeferred`）→ 等它；否则发起一次 `solver.solve(triggerUrl)`（在网关自己的 `SupervisorJob` 作用域里运行，调用方取消只取消自己的等待，不取消共享验证）。`Cleared` → 世代号 +1、失败轮次清零、重试一次；`Cancelled`/`Failed` → 失败轮次 +1（一轮只计一次，不按等待者人数计），满 3 轮进入 30 秒冷却（`rejected = false`），抛 `ChallengeIncompleteException`。
     - `triggerUrl`：GET 请求用原 URL；其他方法用同 scheme/host 的 `/forum/`。重试时请求体原样重发（GBK 表单字节不变）。
   - 时钟可注入（测试用虚拟时间）。
3. `fun resetCooldownForUserRetry()`：清除冷却与失败轮次。
4. 不做：WebView、Cookie 同步（solver 的责任）、登录失效检测、展示等级升级、临期预刷新、`ClearanceAuthority`。
5. 恢复用的独立 Dispatcher：在 `HttpClientModule` 增加 `@RecoveryHttpClient` 限定的 `OkHttpClient` = 主客户端 `newBuilder().dispatcher(Dispatcher(独立线程池))`，拦截器链相同。本卡只提供并测试它，调用方（静默重新登录）在 D2。

【步骤】
1. 先写失败的测试（MockWebServer + 假 `ChallengeSolver` + 虚拟时钟；`runTest`）：
   a. 单次验证：10 个协程同时请求，服务端在请求不带 `cf_clearance=fresh` 时返回 403 验证页；假 solver 挂起，放行时往 `SiteCookieStore` 写入 `cf_clearance=fresh`。断言 `solve` 只调用 1 次，10 个请求最终都是 200，重试请求带新 Cookie。
   b. 取消隔离：5 个等待者中取消 1 个，共享验证不中止，其余 4 个成功。
   c. 重试仍被拒：solver 返回 `Cleared` 但服务端始终 403 → 每个请求最多 2 次网络请求；抛 `ChallengeRejectedException`；60 秒内后续请求立即抛同类异常且不调用 solver；虚拟时间推进 61 秒后恢复；`UserExplicit` 在冷却期内可以发起。
   d. 失败轮次：5 个并发请求合流到一轮、solver 返回 `Cancelled` → 只计 1 轮；连续 3 轮后 30 秒冷却；推进 31 秒恢复。
   e. 后台：`BackgroundSilent` 遇到验证页 → 抛 `ChallengeAbandonedException`，不调用 solver，不计轮次；验证进行中时发出的 `BackgroundSilent` 请求不发网络包直接放弃。
   f. GBK 表单 POST 撞 403 → 验证 → 重试 → 302 → GET 200：solver 收到的 `triggerUrl` 是 `/forum/`；重试 POST 的请求体字节与首次完全相同；最终 `response.request.url` 是重定向目标。
   g. 超时：服务端不回响应头（`SocketPolicy` 挂起）时，`execute(timeout = 2.seconds)` 约 2 秒内抛 `InterruptedIOException`（或 `IOException`），并且 call 已取消。
   h. 不占线程：把主客户端 `Dispatcher.maxRequests` 设为 1 并让一个请求挂在验证上，另一个协程的请求仍能完成（证明等待发生在协程层，而不是占着 OkHttp 的调度名额）。
   i. 恢复客户端：主客户端 `Dispatcher` 被挂起的请求占满时，`@RecoveryHttpClient` 的请求仍能完成；两者的 `dispatcher` 不是同一个实例。
   j. `challengeState`：验证开始 → `Solving`；结束 → `Idle`；冷却 → `Cooldown(rejected=…)`。
2. 实现到测试变绿。
3. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步 a–j 全部通过。证据：测试类:方法 + CI 汇总。
- [ ] 网关代码里没有 `runBlocking`，OkHttp 拦截器里没有等待验证的阻塞调用。证据：`rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network` 无结果。
- [ ] 生产代码约 200–250 行，只在 `data/network/`。证据：`git diff --stat origin/card/A4a-challenge-detector...HEAD`。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/A4b-access-gateway`。最后回复按通用约定，附 API 清单（A5、A6、D2 会使用）。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节（先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME）：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复的第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK；前约 1200 字写：结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过 + 证据位置）、遗留问题；详细证据与日志放在后面。最后附上本卡对外提供的 API 清单（签名 + 文件:行号），供后续卡使用。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

首先 `cd /home/mir/Documents/hipda-next.wt/A4b`，确认 `git status` 干净、HEAD 为 e75ef76；首次推送用 `git push -u origin card/A4b-access-gateway`。
