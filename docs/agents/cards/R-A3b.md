# 审查卡 R-A3b：独立代码审查 Cronet 传输选择与兜底（commit 6e91c6e）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/A3b-cronet` @ `6e91c6e3aae57a7ba54f97dfcf15e256c605308d`，相对 `03d2f01`。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 6e91c6e:<路径>`、`git diff 03d2f01...6e91c6e` 读代码；可以把依赖的 jar 从 `~/.gradle/caches/modules-2/files-2.1/` 解压到 `/tmp/R-A3b/` 用 `javap` 查看（只写 `/tmp/R-A3b/`）；不要 checkout、提交或推送，不要在主目录切换分支，不要编译。CI 用 `gh run view 45534948349`（含模拟器冒烟 job）。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/A3b.md`（范围和验收以它为准）；规格 `docs/design/tech-plan.md` 第 4 节“网络”、第 6 节“Cookie”“排队不能占住连接”、第 9 节风险 1。
- 后续依赖：A4 在协程层做验证页检测与排队，A5 用 WebView 过验证并同步 Cookie、统一 UA。Cloudflare 的 `cf_clearance` 与网络指纹绑定，所以“真的走到 Cronet”是本卡的核心价值。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节）：CI 失败、登录/Cookie/GBK/Cloudflare 通道失效、违反卡片范围或声明、破坏既有测试/契约——必须给出具体触发路径。其他都是建议。敏感区，用最高审查强度。

【审查重点】
1. 线程与阻塞：引擎初始化是否可能发生在主线程；`Tasks.await` 超时是否真的生效；实现者称 `PlayServicesCronetProvider.isEnabled()` 会无超时同步调用安装检查，因此绕开了它——用 `javap` 核对这个说法，并检查绕开后的逻辑是否仍正确（安装成功后如何拿到 provider、是否仍需检查 enabled）。
2. 并发与缓存：只初始化一次（锁/双检/`lazy` 的正确性），初始化失败缓存为不可用；初始化期间其他请求是否被长时间阻塞，以及阻塞的是哪个线程（OkHttp Dispatcher 线程池耗尽的风险）。
3. Cronet 交互：`CronetInterceptor` 是否 `withoutRedirects`；请求头（UA、Cookie）是否原样传给 Cronet；Cronet 是否有自己的 Cookie 存储或缓存会干扰应用层 Cookie；响应头里的 `Set-Cookie` 是否能被 `AppCookieInterceptor` 读到；调用超时与取消是否传递到 Cronet；HTTP/2、QUIC、Brotli 配置。
4. 兜底：传输不可用时是否完全等同 A3a；`chain.proceed` 的使用是否正确（链末拦截器调用 `proceed` 会进入 OkHttp 的网络层）。
5. R8：release 包里 provider 类存在的证据是否充分（实现者用 `apkanalyzer`/`dexdump`）；`CronetProvider.getAllProviders` 的反射加载路径在 R8 下是否还有风险。
6. R-A3a 遗留：取消检查、`headers.values` 断言、`InMemorySiteCookieStore` 的 host 校验是否落实；跨端口结论是否合理。
7. 复杂度与测试质量：`TransportInterceptor.kt` 152 行是否有可删的部分；测试是否能在改坏时失败（举“改坏一行测试仍通过”的例子，若存在）。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定 APPROVE / CHANGES-REQUESTED + 被审 commit `6e91c6e`；阻断项（触发路径、文件:行号、后果）；建议项一行一条。详细分析放后面。
