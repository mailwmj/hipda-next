# 验证卡 V-A3b：独立验证 Cronet 传输选择与兜底（commit 6e91c6e）

【角色】你是独立验证者。你不是实现者，也没有看过实现者的自评；结论只能来自你自己跑出来的证据。

【边界】被验证对象：`origin/card/A3b-cronet` @ `6e91c6e3aae57a7ba54f97dfcf15e256c605308d`，只读：不得向 `card/A3b-cronet` 推送，不得修改 `main`。你的工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A3b -b verify/A3b-probes 6e91c6e3aae57a7ba54f97dfcf15e256c605308d`，只在这里写文件；不要在 `/home/mir/Documents/hipda-next` 主目录 checkout 或写文件。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再按 `docs/agents/DISPATCH.md` 第 2 节用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`。最终证据以 CI 为准：推送 `verify/A3b-probes`，`gh run watch <id> --interval 60`（不用默认间隔、不写轮询循环）。不访问真实论坛，网络只用 MockWebServer。

【背景】契约（任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A3b.md` 第“范围”节）：
- 传输拦截器挂在应用拦截器链末（Redirect → Header → Cookie → Transport）。
- provider 优先级：`HttpEngine-Native-Provider`（仅 sdk ≥ 34）→ `Google-Play-Services-Cronet-Provider` → 都没有时退回 OkHttp 自己的传输；永不选 `Fallback-Cronet-Provider`。
- 引擎在第一次请求时于 OkHttp 工作线程懒初始化，结果（含“不可用”）缓存，只初始化一次；构建 OkHttpClient 时不触发；不在主线程初始化；Play 服务安装带约 2 秒超时。
- Cronet 拦截器必须用 `RedirectStrategy.withoutRedirects()`（重定向由应用层逐跳处理）。
- 传输不可用时，整条链行为与 A3a 完全一致；已取消的 call 在下一跳之前停止并抛 `IOException`。

【步骤】
1. 只看生产代码（`hipda/src/main/java/net/jejer/hipda/data/network/` 下 `CronetEngineSelector.kt`、`TransportInterceptor.kt`、`HttpClientModule.kt`、`ManualRedirectInterceptor.kt` 的改动），不要先读实现者的测试。
2. 在 `hipda/src/test/java/net/jejer/hipda/verify/A3bProbeTest.kt` 写探针，至少覆盖：
   a. 选择逻辑表驱动：sdk 33/34/36 × {仅 HttpEngine、仅 Play、二者都有、仅 Fallback、HttpEngine 被禁用 + Play 可用、空列表}。
   b. 并发：32 个线程同时发首个请求，引擎工厂只被调用一次；初始化抛异常时缓存为“不可用”，后续请求走 OkHttp 且不再重试初始化。
   c. 初始化在调用 `provideOkHttpClient` 时不发生；在主线程上发起同步请求时（Robolectric 主 Looper），如果实现会在主线程初始化引擎，证明出来（实现应当避免或明确拒绝）。
   d. 传输可用（用替身）时：链末拦截器收到的请求已带 UA、Cookie，且是重定向后的单跳请求；替身返回 302 时由 `ManualRedirectInterceptor` 跟随（证明重定向不在传输层发生）。
   e. 传输不可用时：A3a 的核心场景（POST 302→GET、逐跳 Cookie、跨 host 剥离、16/17 跳上限）在完整客户端上仍成立。
   f. 取消：在第一跳响应返回后取消 call，第二跳不发出，抛 `IOException`。
   g. Play 服务安装超时：替身安装任务永不完成时，约 2 秒内放弃并退回 OkHttp，且不调用会无超时阻塞的路径（若无法替身化，读代码说明依据 文件:行号）。
3. 推送并取得 CI 结果。探针失败时先确认是实现问题还是探针写错（依据契约判断），只有实现问题才算 FAIL。

【验收】
- [ ] 每个探针：名称、断言要点、CI 结果。证据：CI run 链接 + 测试汇总里 `A3bProbeTest` 的行。
- [ ] 每个 FAIL：触发输入、期望、实际、对应生产代码 文件:行号。

【交付】最后一条回复第一个词：DONE（完成验证，无论 PASS 或 FAIL）、NEEDS-INPUT 或 STUCK。前约 1200 字：总判定 PASS/FAIL + 被验 commit `6e91c6e`、CI run 链接、每个探针一行、FAIL 项触发路径。不要在拿到 CI 结果前结束回合。验证结束后保留 `verify/A3b-probes` 分支和 worktree。
