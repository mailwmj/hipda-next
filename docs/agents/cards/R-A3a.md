# 审查卡 R-A3a：独立代码审查应用层拦截器链（commit 26bb6bf）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/A3a-interceptors` @ `26bb6bfd9051f3ead01e8380eff8818f100c155d`，相对 `origin/main` @ `64fd073`。只读：用 `git -C /home/mir/Documents/hipda-next fetch origin`、`git show 26bb6bf:<路径>`、`git diff 64fd073...26bb6bf` 读代码；不要 checkout、不要写文件、不要提交或推送；不要在主目录里切换分支。本机没有 Android SDK，不要尝试本地编译。CI 结果可以用 `gh run list --branch card/A3a-interceptors` 和 `gh run view` 查看（不要用 `gh run watch`）。

【背景】
- 规格：`docs/design/tech-plan.md` 第 6 节“拦截器顺序（T5）”“Cookie”“排队不能占住连接”；任务卡全文 `/home/mir/Documents/hipda-next.wt/_cards/A3a.md`（范围和验收以它为准）。
- 后续依赖：A3b 会把 `CronetInterceptor` 追加在 `AppCookieInterceptor` 之后（链末），并关闭 Cronet 自动重定向；A4 会在协程层做验证页检测与排队；A5 会替换 `UserAgentProvider` 的 Hilt 绑定为 WebView UA。
- 项目阻断项（`docs/agents/DISPATCH.md` 第 4 节）：只有 CI 失败、登录/Cookie/GBK/Cloudflare 通道失效、违反卡片范围或声明、破坏既有测试或契约，并且能给出具体触发路径，才算阻断。其他都算建议。

【审查重点】
1. 重定向的正确性：方法改写、请求体与实体头的去除、跨 host/降级时的敏感头剥离、16 跳上限、响应体关闭（资源泄漏）、`Location` 解析失败处理。
2. Cookie：每跳加载/保存是否按当次 URL；3xx 的 `Set-Cookie` 是否保存；调用方 Cookie 头的覆盖与删除；与 `SiteCookieStore` 的线程模型是否匹配（拦截器在 OkHttp 线程上同步调用）。
3. 与 A3b 的兼容：Cronet 拦截器挂在链末时，本卡的拦截器是否仍逐跳生效；有没有依赖 OkHttp 网络层才有的东西（例如 `chain.connection()`）。
4. Hilt：`UserAgentProvider` 绑定能否被单独替换；`HttpClientModule` 签名变化是否影响其他调用点。
5. 测试质量：测试是否真的能在实现被改坏时失败（举 1–2 个“改坏一行，测试仍通过”的例子，若存在）；是否遵守 lesson 中 `OkHttp.initialize` 的要求。
6. 范围：生产代码只在 `data/network/`，约 200 行以内；没碰共享文件。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定（APPROVE / CHANGES-REQUESTED）+ 被审 commit `26bb6bf`；阻断项（每条：触发路径、文件:行号、后果）；建议项（每条一行）。详细分析放后面。
