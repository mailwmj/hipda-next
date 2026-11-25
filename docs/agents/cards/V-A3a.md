# 验证卡 V-A3a：独立验证应用层拦截器链（commit 26bb6bf）

【角色】你是独立验证者。你不是实现者，也没有看过实现者的自评；你的结论只能来自你自己跑出来的证据。

【边界】被验证对象是 `origin/card/A3a-interceptors` @ `26bb6bfd9051f3ead01e8380eff8818f100c155d`，只读：不得向 `card/A3a-interceptors` 推送，不得修改 `main`。你自己的工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A3a -b verify/A3a-probes 26bb6bfd9051f3ead01e8380eff8818f100c155d`，只在这里写文件；不要在 `/home/mir/Documents/hipda-next` 主目录里 checkout 或写文件。本机没有 Android SDK，不能本地编译：把探针测试推到 `verify/A3a-probes`，由 GitHub Actions 运行（`gh run watch <id> --interval 60`，不用默认间隔、不写轮询循环；失败看 `gh run view <id> --log-failed`）。不访问真实论坛（4d4y.com），网络只用 MockWebServer。

【背景】规格（实现必须满足的契约）：
- `docs/design/tech-plan.md` 第 6 节“拦截器顺序（T5）”“Cookie”。
- 契约要点：应用拦截器顺序 `ManualRedirectInterceptor` → `BrowserHeaderInterceptor` → `AppCookieInterceptor`；跟随 301/302/303/307/308，相对 Location 正确解析，缺 Location 原样返回；301/302/303 且非 GET/HEAD → 改 GET、去请求体与 `Content-Type`/`Content-Length`/`Transfer-Encoding`；307/308 保持方法和请求体；跨 host 或 https→http 时去掉 `Authorization`、`Proxy-Authorization`、`Cookie`；最多 16 跳，第 17 次重定向抛 `ProtocolException`；跟下一跳前关闭上一跳响应体；每一跳都覆盖 UA，`Accept`/`Accept-Language` 仅缺省时补；每一跳按当次 URL 从 `SiteCookieStore` 加载 Cookie（为空时去掉 `Cookie` 头），并把响应（含 3xx）的 `Set-Cookie` 存回；`HttpClientModule` 仍 `followRedirects(false)`、`followSslRedirects(false)`。
- 单测需 Robolectric，并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`（见 `docs/agents/lesson.md`）。

【目标】用你自己写的探针测试，判断上述契约在 26bb6bf 上是否成立，给出 PASS / FAIL。

【步骤】
1. 只看生产代码（`hipda/src/main/java/net/jejer/hipda/data/network/` 下 A3a 新增/修改的 5 个文件），不要先读实现者的测试。
2. 在 `hipda/src/test/java/net/jejer/hipda/verify/A3aProbeTest.kt` 写探针，至少覆盖实现者可能漏掉的边界：
   a. 重定向链中途下发的 Cookie 在后续同 host 跳上带上；跨 host 跳不带原 host 的 Cookie，但目标 host 自己已存的 Cookie 照常带上。
   b. https → http 降级跳转去掉敏感头（MockWebServer 可用 `useHttps` 或说明无法构造时的替代证明）。
   c. 恰好 16 次重定向后 200 成功；17 次抛 `ProtocolException`；中间各跳的响应体都被关闭（可用 MockWebServer 连接复用计数或自定义 `EventListener` 证明）。
   d. 307 带大请求体（例如 64KB）跳转后请求体逐字节一致；303 对 PUT/DELETE 也改 GET。
   e. 调用方显式设置的 `User-Agent` 被覆盖，而显式的 `Accept-Language` 保留。
   f. `Set-Cookie` 带 `Domain=` 的域 Cookie 在子域跳转上的行为与 `SiteCookieStore` 语义一致（`Cookie.matches`）。
   g. 无 Cookie 时请求里不存在 `Cookie` 头（不是空字符串）。
3. 推送 `verify/A3a-probes`，取得 CI 结果。探针失败时，先确认是实现问题还是探针写错（读规格判断），只有实现问题才算 FAIL。

【验收】
- [ ] 每个探针：名称、断言要点、CI 结果。证据：CI run 链接 + 测试汇总里 `A3aProbeTest` 的行。
- [ ] 对每个 FAIL：触发输入、期望、实际、对应生产代码 文件:行号。

【交付】最后一条回复第一个词：DONE（完成验证，无论 PASS 或 FAIL）、NEEDS-INPUT 或 STUCK。前约 1200 字：总判定 PASS/FAIL + 被验证 commit `26bb6bf`、CI run 链接、每个探针一行结果、FAIL 项的触发路径。不要在拿到 CI 结果之前结束回合。
【清理】验证结束后不删除 `verify/A3a-probes` 分支和 worktree（负责人会处理）。
