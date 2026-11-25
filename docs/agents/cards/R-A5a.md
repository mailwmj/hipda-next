# 审查卡 R-A5a：独立代码审查 WebView UA 统一与 Cookie 桥（commit 931ed93）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/A5a-webview-identity` @ `931ed93b54a6a363380e867cf29abd97b6d648f1`，相对 `f051255`。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 931ed93:<路径>`、`git diff f051255...931ed93` 读代码；可把 jar 解压到 `/tmp/R-A5a/` 用 `javap` 核对 `androidx.webkit` API；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45544672921`。详细报告写 `/home/mir/Documents/hipda-next.wt/_reports/R-A5a.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A5a.md`（范围与验收以它为准）；依据 `docs/design/cloudflare-research.md` 第一节第 3、4 小节、第五节第 3 小节第 1–2 条；`docs/design/tech-plan.md` §6“UA”“Cookie”。后续：A5b 的 `WebViewChallengeSolver` 与 B1b 的登录同步直接调用本卡 API。阻断标准见 `docs/agents/DISPATCH.md` §4（登录、Cookie、Cloudflare 通道失效属阻断，须给触发路径）。敏感区。
【审查重点】
1. UA：归一化正则是否只去 `; wv` 与 ` Version/4.0`、保留真实 Chrome 版本；`WebSettings.getDefaultUserAgent` 的调用线程（OkHttp 工作线程上首次调用是否安全、是否可能在主线程被触发）、并发懒加载只算一次、失败回退；WebView 与网络层是否确实同一个 UA。
2. Cookie 桥：`cf_clearance` 只从 WebView → store 单向、只采纳快照外的新值；预热排除 `cf_clearance`；清除是否覆盖 host-only 与 `.4d4y.com` 两种域变体（含 CHIPS 分区副本，若 API 能取到）；备份恢复是否完整；`getCookieInfo` 解析（Domain/Path/Expires/Max-Age/Secure/HttpOnly/SameSite/Partitioned）与退化路径构造的 Cookie 属性；写入 store 的 host 校验；`flush()` 时机；专用单线程 dispatcher 是否会泄漏线程。
3. `androidx.webkit:1.18.0` 依赖：只加了这一项，release R8 无问题（CI release 构建通过即可）。
4. 测试：假 `WebViewCookieAccess` 是否掩盖了真实 `CookieManager` 行为差异（例如 `setCookie` 回调、`getCookie` 返回格式）；举“改坏一行测试仍通过”的例子（若存在）。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：判定 APPROVE/CHANGES-REQUESTED + `931ed93`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径。
