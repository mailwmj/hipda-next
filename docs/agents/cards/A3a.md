# 任务卡 A3a：应用层拦截器链（手动重定向 + 浏览器请求头 + Cookie）

【角色】你是 hipda-next 的 Android 网络层开发者（Kotlin、OkHttp 5）。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/A3a`（分支 `card/A3a-interceptors`，基于 `origin/main` @ 64fd073）里工作。只改 `hipda/src/main/java/net/jejer/hipda/data/network/` 和对应的 `hipda/src/test/java/net/jejer/hipda/data/network/`。不碰旧的 `okhttp/` 目录（旧界面专用，M7 删除）、不碰 `data/network/cookie/` 的实现（只调用它）。本卡不接入 Cronet（那是下一张卡 A3b），不做 Cloudflare 验证检测（A4）。这是敏感区卡：生产代码控制在约 200 行以内。

【背景】必读：
- `docs/design/tech-plan.md` 第 6 节“拦截器顺序（T5）”和“Cookie”两条。
- `hipda/src/main/java/net/jejer/hipda/data/network/HttpClientModule.kt`（现有客户端：已 `followRedirects(false)`、`followSslRedirects(false)`）。
- `hipda/src/main/java/net/jejer/hipda/data/network/cookie/SiteCookieStore.kt`（接口：`loadForRequest(url)`、`saveFromResponse(url, cookies)`）和 `CookieStoreModule.kt`（Hilt 提供 `@Singleton SiteCookieStore`）。
- `hipda/src/test/java/net/jejer/hipda/data/network/HttpClientModuleTest.kt`（现有断言“拦截器为空”，本卡会改变这个事实，需要相应改写这条测试）。
- `docs/agents/lesson.md` 构建一节：OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会读 `PublicSuffixDatabase.list`，单测要用 Robolectric，并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。
- 已定的设计：拦截器都是应用拦截器（`addInterceptor`），顺序为 `ManualRedirectInterceptor`（最外层）→ `BrowserHeaderInterceptor` → `AppCookieInterceptor`。外层每跟一跳都重新走一遍内层，所以每一跳按当次目标地址存取 Cookie。OkHttp 自带的 CookieJar 保持默认的 `NO_COOKIES`。A3b 之后会把 Cronet 拦截器追加在 `AppCookieInterceptor` 之后（链的最末），本卡的设计不能妨碍这一点。

【目标】`HttpClientModule` 提供的 OkHttpClient 按上述顺序挂好三个拦截器，并有 MockWebServer 测试证明重定向、请求头和逐跳 Cookie 的行为。

【范围】新增（都在 `data/network/`）：
1. `ManualRedirectInterceptor.kt`
   - 跟随 301、302、303、307、308；`Location` 用 `response.request.url.resolve()` 解析相对地址；缺 `Location` 或无法解析时，原样返回该响应。
   - 301、302、303 且原方法不是 GET/HEAD 时：改为 GET、去掉请求体以及 `Content-Type`、`Content-Length`、`Transfer-Encoding` 头；307、308 保持原方法和请求体。
   - 下一跳的 host 和上一跳不同时（按 `HttpUrl.host` 比较）：去掉 `Authorization`、`Proxy-Authorization`、`Cookie` 请求头（Cookie 会由内层按新地址重新加）。从 https 降到 http 的跳转同样按跨域处理。
   - 最多跟随 16 跳；第 17 次重定向抛 `java.net.ProtocolException`（消息含 “Too many follow-up requests: 17”）。
   - 跟下一跳之前关闭上一跳的响应体。
2. `BrowserHeaderInterceptor.kt`
   - 每一跳都用注入的 `UserAgentProvider` 覆盖 `User-Agent`；`Accept`、`Accept-Language` 只在请求没有设置时补上（取值写成常量：`Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8`，`Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`）。
   - 新增 `UserAgentProvider.kt`：`fun interface UserAgentProvider { fun userAgent(): String }`，加一个默认实现（返回一个写成常量的 Android Chrome 移动版 UA 字符串）。A5 会把它换成取自 WebView 的 UA，所以 Hilt 绑定要放在一个可单独替换的 `@Module` 里。
3. `AppCookieInterceptor.kt`
   - 发请求前：`siteCookieStore.loadForRequest(request.url)`，非空时按 `name=value; name2=value2` 设置 `Cookie` 头（覆盖请求里已有的 `Cookie` 头）；为空时去掉 `Cookie` 头。
   - 收到响应后：`Cookie.parseAll(request.url, response.headers)`，非空时 `siteCookieStore.saveFromResponse(request.url, cookies)`。3xx 响应同样保存（这是手动重定向的意义所在）。
4. 修改 `HttpClientModule.kt`：`provideOkHttpClient` 注入 `SiteCookieStore` 和 `UserAgentProvider`，按顺序 `addInterceptor`；保留现有超时和关闭自动重定向。
明确不做：Cronet、验证页检测、重试、日志拦截器、旧 `okhttp/` 目录。

【步骤】
1. 先写失败的测试（Robolectric + `mockwebserver3`，用一个内存版 `SiteCookieStore` 假实现或真实 `DataStoreSiteCookieStore` 配临时目录均可，测试里说明选择）：
   a. POST 到 A，A 返回 302 → B：B 收到 GET、无请求体、无 `Content-Type`。303 同理；307 时 B 收到 POST 且请求体逐字节相同。
   b. A 302 时下发 `Set-Cookie: s=1`，跳到同 host 的 B：B 收到 `Cookie: s=1`（证明逐跳保存与加载）。
   c. 跨 host：用两个 MockWebServer，一个用 `localhost`、另一个用 `127.0.0.1` 作为 host 构造 URL；第一跳带 `Authorization` 和已存的 Cookie，跳到另一 host 后这两个头都不出现，且第一个 host 的 Cookie 不会被带过去。
   d. 连续 16 次重定向后的第 17 跳返回 200 时：成功；17 次重定向时抛 `ProtocolException`。
   e. 相对 `Location`（如 `/next?x=1`）正确解析；缺 `Location` 的 302 原样返回。
   f. 每一跳都带注入的 UA；调用方显式设置的 `Accept` 不被覆盖。
   g. 拦截器顺序：`client.interceptors` 依次是这三个类的实例。
2. 实现到测试变绿。
3. 改写 `HttpClientModuleTest` 中“拦截器为空”的断言为顺序断言（不删其他断言）。
4. 推送，等 CI 变绿。

【自检】每步只跑本卡新增或改动的测试，例如 `flock ... ./gradlew :hipda:testDebugUnitTest --tests 'net.jejer.hipda.data.network.*' ...`；最后推送由 CI 跑全量（`assembleDebug assembleRelease lintDebug`、`testDebugUnitTest`、Roborazzi 校验）。

【验收】
- [ ] 步骤 1 的 a–g 全部有对应测试且在 CI 中通过。证据：测试类名 + 方法名列表、CI run 链接里的测试汇总。
- [ ] `HttpClientModule.provideOkHttpClient` 的拦截器顺序为 Redirect → Header → Cookie，且 `followRedirects`/`followSslRedirects` 仍为 false。证据：文件:行号 + 测试。
- [ ] 生产代码新增和修改合计约 200 行以内，只在 `data/network/` 下。证据：`git diff --stat origin/main...HEAD`。
- [ ] CI 全绿（编译、lint、单测、Roborazzi 校验）。证据：run 链接 + commit hash。

【交付】分支 `card/A3a-interceptors`，Conventional Commits。最后回复按通用约定。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
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

首先 `cd /home/mir/Documents/hipda-next.wt/A3a`，确认 `git status` 干净、HEAD 为 64fd073；首次推送用 `git push -u origin card/...`（本卡分支名）。
