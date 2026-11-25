# 任务卡 A3b：与 Chrome 一致的底层传输（cronet-okhttp），OkHttp 自有传输兜底

【角色】你是 hipda-next 的 Android 网络层开发者（Kotlin、OkHttp 5、Cronet）。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/A3b`（分支 `card/A3b-cronet`，基于 `origin/main` @ 03d2f01，已包含 A3a）里工作。可改：`hipda/src/main/java/net/jejer/hipda/data/network/`（含 A3a 的拦截器，仅限下面“审查遗留”几条）、对应测试目录、`hipda/proguard-rules.txt`（只在第 5 步证明需要时加最小的 keep 规则）。不改 `libs.versions.toml`、`build.gradle.kts` 等共享文件（依赖已经齐全：`cronet-okhttp`、`play-services-cronet` 都已在 `hipda/build.gradle.kts`；需要新依赖就停下回复 NEEDS-INPUT）。不做 Cloudflare 验证检测与排队（A4）。敏感区卡：生产代码约 200 行以内。

【背景】必读：
- `docs/design/tech-plan.md` 第 4 节“网络”行、第 6 节“Cookie”（Cronet 绕过 OkHttp 的 CookieJar，且默认自己跟随重定向，必须关掉，由应用层逐跳处理）、第 9 节风险 1。
- 现状：`data/network/HttpClientModule.kt` 的应用拦截器顺序为 `ManualRedirectInterceptor` → `BrowserHeaderInterceptor` → `AppCookieInterceptor`（A3a，已合并）。本卡把传输拦截器追加在链的**最末**。
- 负责人已确认的事实（从 Gradle 缓存的 jar 里查到，你仍需自己核对）：`org.chromium.net:cronet:500.1.0` 含 `org.chromium.net.impl.HttpEngineNativeProvider`（Android 14+ 系统 HttpEngine）、`NativeCronetProvider`、`JavaCronetProvider`；`cronet-okhttp:0.1.2` 有 `CronetInterceptor.newBuilder(engine)` 和 `RedirectStrategy.withoutRedirects()`。
- `docs/agents/lesson.md`：本地 Gradle 前导出 mise 的 ANDROID_HOME/JAVA_HOME；R8 full mode 会剥掉靠反射加载的类，加了反射路径要跑一次 release 模拟器冒烟。

【目标】Android 14+ 用系统 HttpEngine，其次用 Play 服务的 Cronet；两者都不可用时退回 OkHttp 自己的传输。无论哪种传输，A3a 的逐跳重定向和 Cookie 行为都不变。

【范围】
1. 先查清（写进回复）：用 `./gradlew :hipda:dependencies --configuration releaseRuntimeClasspath` 确认 `org.chromium.net:cronet`、`httpengine-native-provider`、`play-services-cronet` 实际在运行时类路径上；从 jar 里读出各 provider 的名称常量（`CronetProvider.getName()` 返回值，例如 HttpEngine provider、Play 服务 provider、`Fallback-Cronet-Provider`）。
2. `CronetEngineSelector.kt`：纯函数 `selectProvider(candidates: List<ProviderInfo>, sdkInt: Int): ProviderInfo?`（`ProviderInfo` = 名称 + 是否可用），优先级：HttpEngine provider（仅 sdkInt ≥ 34）→ Play 服务 provider → 都没有返回 null。**永远不选** `Fallback-Cronet-Provider`（`JavaCronetProvider`，基于 HttpURLConnection，比 OkHttp 自己的传输更差）。是否选 App-Packaged（需要随包带 native 库）：按第 1 步的结果决定，并在回复里说明。
3. `TransportInterceptor.kt`（应用拦截器，挂在链末）：第一次请求时在 OkHttp 的工作线程上懒加载引擎（Play 服务 provider 需要先 `CronetProviderInstaller.installProvider(context)`，在工作线程上用带超时的 `Tasks.await`，超时约 2 秒），结果缓存（含“不可用”这个结果，不反复尝试）；可用时把请求交给 `CronetInterceptor`（`setRedirectStrategy(RedirectStrategy.withoutRedirects())`），不可用时 `chain.proceed(request)` 走 OkHttp 自己的传输。引擎构建开启 HTTP/2、QUIC、Brotli。不得在主线程上初始化引擎；`HttpClientModule` 构建客户端时不触发初始化。
4. 修改 `HttpClientModule.kt`：在 `AppCookieInterceptor` 之后 `addInterceptor(transportInterceptor)`。
5. R8：构建 release APK，用 `apkanalyzer dex packages` 或 `dexdump` 确认选中的 provider 类和 `CronetProviderInstaller` 在 release 包里没有被剥掉（`CronetProvider.getAllProviders` 靠反射加载 provider 类）；被剥掉就在 `proguard-rules.txt` 加最小 keep 规则。然后用 `gh workflow run android.yml --ref card/A3b-cronet -f emulator=true` 跑一次模拟器冒烟（API 36，release 与 debug 都要冷启动成功）。
6. 审查遗留（R-A3a 建议，一并做）：
   - `ManualRedirectInterceptor` 每跳之前检查 `chain.call().isCanceled()`，已取消就关闭响应并抛 `IOException("Canceled")`。
   - 同 host 不同端口的跳转：评估是否也应剥离 `Authorization`（论坛没有这种场景时保持现状），在回复里写结论。
   - `NetworkInterceptorChainTest` 中 `Cookie`、`User-Agent` 的断言改用 `headers.values(name)` 单元素断言（第 283、578 行附近）。
   - 测试假实现 `InMemorySiteCookieStore.saveFromResponse` 加上与生产 `DataStoreSiteCookieStore` 一致的 `url.host` 校验。

【步骤】
1. 先写失败的测试：
   a. `selectProvider` 表驱动测试：API 33/34/36 × 各 provider 组合（含只有 Fallback 时返回 null）。
   b. 传输不可用时（注入一个返回“不可用”的引擎工厂），整条链对 MockWebServer 的行为与 A3a 完全一致：重跑 A3a 的重定向与逐跳 Cookie 场景（复用 `NetworkInterceptorChainTest` 的场景，不要复制粘贴整份测试，抽一个共用的场景辅助或参数化）。
   c. 引擎工厂只被调用一次（并发 8 个请求时也只初始化一次），且不在 `HttpClientModule.provideOkHttpClient` 时调用。
   d. 传输可用时请求交给 Cronet 拦截器：用一个可注入的 `Interceptor` 工厂替身证明“链末拦截器收到了经过 Redirect/Header/Cookie 处理后的请求”，并证明传给 `CronetInterceptor.Builder` 的重定向策略是 `withoutRedirects`（若 `CronetInterceptor` 无法在 JVM 上构造，就把“构造 builder”的代码隔离成一个小函数并在回复里说明这条只能靠审查和模拟器冒烟证明）。
   e. 取消：已取消的 call 在第二跳之前停止，抛 `IOException`。
2. 实现到测试变绿。
3. 第 5 步的 R8 检查和模拟器冒烟。
4. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步 a–e 测试通过。证据：测试类:方法 + CI 汇总。
- [ ] 依赖与 provider 名称的调查结论（命令输出摘要）。
- [ ] release 包里 provider 相关类存在的证据（命令 + 输出），以及 `emulator=true` 冒烟 run 链接（release 与 debug 冷启动通过）。
- [ ] 生产代码约 200 行以内，只在 `data/network/`（及必要的 `proguard-rules.txt`）。证据：`git diff --stat origin/main...HEAD`。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/A3b-cronet`。最后回复按通用约定，另附“真机上需要人工确认的事项”清单（例如在真机上确认实际选中了哪个 provider、过 Cloudflare 后是否仍被拦），供 M1 阶段验收使用。

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

首先 `cd /home/mir/Documents/hipda-next.wt/A3b`，确认 `git status` 干净、HEAD 为 03d2f01；首次推送用 `git push -u origin card/A3b-cronet`。
