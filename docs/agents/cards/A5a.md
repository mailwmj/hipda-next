# 任务卡 A5a：WebView UA 统一与 Cookie 桥（WebViewUserAgentProvider + WebViewCookieBridge）

【角色】你是 hipda-next 的 Android 网络层开发者（Kotlin、协程、OkHttp 5、AndroidX Webkit）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/A5a`（分支 `card/A5a-webview-identity`，基于 `origin/main` @ `f051255`）里工作。
- 允许修改的代码范围：
  - 新增 `hipda/src/main/java/net/jejer/hipda/data/network/webview/` 下的生产代码；
  - 修改 `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt`（仅替换 `UserAgentModule.bindUserAgentProvider` 绑定；**保留 `DefaultUserAgentProvider` 及其 `DEFAULT_MOBILE_CHROME_UA`**，既有测试和失败回退均依赖它）；
  - 新增 `hipda/src/test/java/net/jejer/hipda/data/network/webview/` 下的单测，并同步更新 `hipda/src/test/java/net/jejer/hipda/data/network/HttpClientModuleTest.kt`（第 111–116 行反射断言 `UserAgentModule.bindUserAgentProvider` 的绑定类型）。
- **共享文件例外（覆盖文末通用约定的对应条款，已获负责人批准，当前无其他卡改这两个文件）**：
  - 允许修改 `gradle/libs.versions.toml` 和 `hipda/build.gradle.kts`，**仅限**引入 `androidx.webkit:webkit:1.18.0`（2026-11-25 核实自 Google Maven 最新稳定版）。
  - 其他共享文件（`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/` 等）一律不得修改。
- 不改 `SiteCookieStore` 接口、`DataStoreSiteCookieStore`、`BrowserHeaderInterceptor`、`AppCookieInterceptor`、`ManualRedirectInterceptor`、`TransportInterceptor`、`HttpClientModule`。
- **不做（超出范围）**：`ChallengeActivity`、WebView 页面加载与 JS 注入、`ChallengeSolver` 真实实现（以上为 A5b）、验证面板与提示界面（A6）、登录流程（D2）、Client Hints（`Sec-CH-UA*`）、`ClearanceAuthority`。
- 规模与审查：敏感区卡；审查只做 Carbon 审查（不做独立探针验证）。实际代码若明显超出预期，先自查简化并在回复里说明原因。

【背景】
- 必读规格：
  - `docs/design/cloudflare-research.md`：
    - 第一节第 3 小节（Cookie 同步机制）：边界单向同步；独立线程调用 `CookieManager` 避免主线程 IPC 掉帧；优先 `WebViewFeature.GET_COOKIE_INFO` + `CookieManagerCompat.getCookieInfo` 读取完整属性（`Domain/Path/Expires/Secure/HttpOnly/SameSite/Partitioned`），旧 WebView 退化为 `CookieManager.getCookie()`；验证前先快照并备份两端旧 `cf_clearance` 再清除、失败时恢复备份；过盾后剔除旧快照只采纳本轮新 `cf_clearance`，同属性双变体按 `expiresAt` 最新者胜出；预热 WebView 时显式排除 `cf_clearance`（`cf_clearance` 只允许 WebView → Store 单向流动）。
    - 第一节第 4 小节（UA 处理）：读取设备系统 WebView 真实 UA，清洗掉暴露 WebView 身份的 `; wv` 与 `Version/4.0`，使 WebView 与原生网络请求使用同一 UA。
    - 第五节第 3 小节第 1–2 条：通过 `WebSettings.getDefaultUserAgent(context)` 获取并正则清洗 `; wv` 与 `Version/4.0`；验证前快照并按域名清除旧 `cf_clearance`（失败时恢复备份）。
  - `docs/design/tech-plan.md` 第 6 节“UA”：WebView 和网络层使用同一个 UA，取自 WebView 自己的 UA（去掉 `; wv` 标记）。
  - `docs/agents/lesson.md`：
    - 本地 Gradle 前必须导出 mise 的 `ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0` 和 `JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest`，`PATH="$JAVA_HOME/bin:$PATH"`（不要在 worktree 写 `local.properties`）。
    - OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会读取 `AssetManager` 里的 `PublicSuffixDatabase.list`，单测需用 Robolectric 并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。
- 现有代码（基于 `origin/main` @ `f051255`）：
  - `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt`：`fun interface UserAgentProvider { fun userAgent(): String }`、`DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA`、`UserAgentModule.bindUserAgentProvider`（第 30–35 行）。
  - `hipda/src/main/java/net/jejer/hipda/data/network/BrowserHeaderInterceptor.kt`：直接读取 `userAgentProvider.userAgent()`。
  - `hipda/src/main/java/net/jejer/hipda/data/network/cookie/SiteCookieStore.kt`：`loadForRequest(url: HttpUrl): List<Cookie>`、`saveFromResponse(url: HttpUrl, cookies: List<Cookie>)`、`clear()`、`clearForHost(host: String)`、`snapshot(): List<Cookie>`。
  - `hipda/src/main/java/net/jejer/hipda/data/network/cookie/DataStoreSiteCookieStore.kt`：`saveFromResponse` 校验 `cookie.matchesHost(url.host)` 并按 `(name, domain, path)` 覆盖，传入已过期 Cookie（`expiresAt <= now`）即删除对应条目；`loadForRequest` 按 `Cookie.matches(url)` 过滤。
  - `hipda/src/test/java/net/jejer/hipda/data/network/InMemorySiteCookieStore.kt`：同步内存假实现，单测可直接复用。
  - `hipda/src/test/java/net/jejer/hipda/data/network/HttpClientModuleTest.kt`（第 111–116 行）：校验 `UserAgentModule.bindUserAgentProvider` 参数类型与注解。

【目标】完成统一 WebView UA 提供者（`WebViewUserAgentProvider` 并替换 Hilt 绑定）和 WebView ↔ `SiteCookieStore` 双向边界 Cookie 桥（`WebViewCookieBridge`），所有行为通过离线 Robolectric 单测验证，供 A5b（验证宿主）与 B1b（样本导出登录）直接调用。

【范围】
1. **引入 `androidx.webkit` 依赖（共享文件例外）**：
   - `gradle/libs.versions.toml`：在 `[versions]` `# Network & Parsing` 段新增 `webkit = "1.18.0"`；在 `[libraries]` `# Network & Parsing` 段新增 `androidx-webkit = { module = "androidx.webkit:webkit", version.ref = "webkit" }`。
   - `hipda/build.gradle.kts`：在 `// Network & Parsing` 段新增 `implementation(libs.androidx.webkit)`。
2. **UA 统一（`data/network/webview/WebViewUserAgentProvider.kt` 与 `data/network/UserAgentProvider.kt`）**：
   - 纯函数 `fun normalizeWebViewUserAgent(raw: String): String`：
     - 去掉 `; wv`（不区分大小写，兼容 `; wv)`、`; wv;` 等常见位置，避免留下多余分号或空格）和 `Version/4.0`（及多余间隔空格），折叠连续空白并 `trim()`；
     - 保留真实 Chromium/Chrome 版本号、`Mobile`（若有）及原 UA 其余片段；
     - 清洗后为空白（或原串为空白）时退回 `DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA`。
   - `@Singleton class WebViewUserAgentProvider` 实现 `UserAgentProvider`：
     - `@Inject constructor(@ApplicationContext context: Context)` 委托给 `internal constructor(rawUserAgentSupplier: () -> String)`（便于单测注入）；
     - 首次调用 `userAgent()` 时在**调用线程**懒加载 `WebSettings.getDefaultUserAgent(context)`，经 `normalizeWebViewUserAgent` 清洗后线程安全缓存（如 `lazy(LazyThreadSafetyMode.SYNCHRONIZED)`）；后续调用直接返回缓存值，不重复读取 `WebSettings`；
     - 读取抛任意 `Throwable`（如系统 WebView 异常、缺失包或 Robolectric 环境报错）或返回空白时，退回 `DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA` 并缓存。
   - 修改 `data/network/UserAgentProvider.kt` 中的 `UserAgentModule.bindUserAgentProvider(impl: WebViewUserAgentProvider): UserAgentProvider`；保留 `DefaultUserAgentProvider`。
   - 同步更新 `HttpClientModuleTest.kt` 第 111–116 行反射断言为 `WebViewUserAgentProvider::class.java`。
3. **WebView Cookie 桥（`data/network/webview/`）**：
   - 常量：`const val CF_CLEARANCE_COOKIE_NAME = "cf_clearance"`。
   - **薄封装接口 `WebViewCookieAccess`**（解决 Robolectric `ShadowCookieManager` 能力受限，单测注入假实现）：
     - `fun isGetCookieInfoSupported(): Boolean`
     - `fun getCookieInfo(url: String): List<String>`
     - `fun getCookie(url: String): String?`
     - `fun setCookie(url: String, value: String)`
     - `fun flush()`
     - 真实实现 `AndroidWebViewCookieAccess` 只做薄封装：在各方法体内按需调 `CookieManager.getInstance()`（**禁止在构造函数内提前调 `CookieManager.getInstance()`**，避免 Hilt 装配时在主线程初始化 WebView）、`WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)`、`CookieManagerCompat.getCookieInfo(cookieManager, url)`、`cookieManager.getCookie(url)`、`cookieManager.setCookie(url, value)`、`cookieManager.flush()`。
   - **返回模型**：
     - `data class ClearanceBackup(val storeCookies: List<Cookie>, val webViewSetCookieHeaders: List<String>)`（可提供 `val isEmpty: Boolean`）
     - `data class CookieSyncResult(val syncedCookies: List<Cookie>, val freshClearance: String?)`
   - **`@Singleton class WebViewCookieBridge`**（在 `data/network/webview/WebViewNetworkModule.kt` 中配置 Hilt 绑定与专用单线程 `CoroutineDispatcher`）：
     - 专用单线程 dispatcher：基于 `Executors.newSingleThreadExecutor`（daemon 线程，命名如 `"webview-cookie-bridge"`）+ `.asCoroutineDispatcher()`，构造函数支持注入 `CoroutineDispatcher` 以便单测断言线程隔离；所有调用 `WebViewCookieAccess` 的逻辑都在 `withContext(cookieDispatcher)` 内执行。
     - 5 个公开 `suspend` 方法：
       1. `suspend fun snapshotClearances(url: HttpUrl): Set<String>`：收集 WebView 与 `SiteCookieStore` 中匹配 `url` 的全部非空 `cf_clearance` 值，去重返回。
       2. `suspend fun clearClearance(url: HttpUrl): ClearanceBackup`：
          - 备份 `SiteCookieStore` 中 `loadForRequest(url)` 里 `name == "cf_clearance"` 的原 `Cookie`，以及 WebView 中 `url` 对应的 `cf_clearance` 原始 `Set-Cookie` 串（`GET_COOKIE_INFO` 路径保留原属性串，回退路径格式化为带 `Path=/` 的 `Set-Cookie` 串）；
          - 清除 `SiteCookieStore` 端：为每条备份构造同 `(name, domain, path, hostOnly)` 且已过期（如 `expiresAt = 1L`）的 `Cookie`，调 `siteCookieStore.saveFromResponse(url, expired)` 删除（不得影响 `cdb_auth`、`cdb_sid` 等其他 Cookie）；
          - 清除 WebView 端：对每条备份对应的作用域属性（`Path`、以及原串存在的 `Domain`/`Secure`/`HttpOnly`/`SameSite`/`Partitioned`）写入 `cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT` 过期 `Set-Cookie`（回退路径写入 host-only `Path=/` 过期串），写完调用 `flush()`；
          - 返回 `ClearanceBackup`。
       3. `suspend fun restoreClearance(url: HttpUrl, backup: ClearanceBackup)`：
          - 若 `backup.storeCookies.isNotEmpty()`，调 `siteCookieStore.saveFromResponse(url, backup.storeCookies)` 恢复 Store；
          - 若 `backup.webViewSetCookieHeaders.isNotEmpty()`，在 `cookieDispatcher` 上逐条 `setCookie(url.toString(), header)` 并调用 `flush()` 恢复 WebView。
       4. `suspend fun primeWebView(url: HttpUrl)`：
          - 读取 `siteCookieStore.loadForRequest(url)`，**显式排除 `cf_clearance`**；
          - 将剩余 Cookie 按完整属性（`Path`、非 `hostOnly` 时的 `Domain`、`persistent` 时的 `Expires`、`Secure`、`HttpOnly`、`SameSite`）格式化为 `Set-Cookie` 串，在 `cookieDispatcher` 上逐条 `setCookie(url.toString(), header)`，有写入时调用 `flush()`（无可写 Cookie 则不写也不 `flush()`）。
       5. `suspend fun syncFromWebView(url: HttpUrl, excludeClearanceValues: Set<String> = emptySet()): CookieSyncResult`：
          - 在 `cookieDispatcher` 上读回 WebView 中 `url` 对应的 Cookie：
            - `isGetCookieInfoSupported()` 为 `true` 时调 `getCookieInfo(url.toString())`，逐条用 `Cookie.parse(url, raw)` 解析完整属性；
            - 否则调 `getCookie(url.toString())`，按 `;` 拆分、按首个 `=` 分离键值（trim、跳过空名），用 `Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path("/")` 构造；
          - 做 host 校验（不同 host / 非匹配域的 Cookie 直接丢弃，不写入也不返回）；
          - 对 `cf_clearance`：丢弃空白值与存在于 `excludeClearanceValues` 的旧值；若同 `(domain, path)` 存在多条新候选（如 CHIPS 分区/非分区双变体），保留 `expiresAt` 最新者；
          - 对 `cdb_auth`、`cdb_sid` 等非 `cf_clearance` Cookie：照常保留；
          - 若待写入列表非空，调用 `siteCookieStore.saveFromResponse(url, toSave)`；返回 `CookieSyncResult(syncedCookies = toSave, freshClearance = ...)`。

【步骤】
1. 修改 `gradle/libs.versions.toml` 与 `hipda/build.gradle.kts` 加入 `androidx.webkit:webkit:1.18.0`。
2. 先写失败的单元测试（Robolectric `@Config(application = Application::class, sdk = [34])`，`@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`，使用 `InMemorySiteCookieStore` 与假 `WebViewCookieAccess`），至少覆盖：
   a. `normalizeWebViewUserAgent` 多个真实 UA 样例：标准 Android 14 Pixel WebView（含 `; wv)` + `Version/4.0`）、Android 10 Reduced UA（`; K; wv)` + `Version/4.0`）、折叠屏/平板无 `Mobile` 的 WebView UA、普通 Chrome UA（幂等）、大小写与多余空格、空白串回退 `DEFAULT_MOBILE_CHROME_UA`。
   b. `WebViewUserAgentProvider`：首次调用在调用线程懒加载并缓存（多次调用 supplier 只触发一次）；supplier 抛异常或返回空白时退回 `DEFAULT_MOBILE_CHROME_UA` 并缓存；Hilt 绑定已切换为 `WebViewUserAgentProvider`（含同步更新 `HttpClientModuleTest`）。
   c. `snapshotClearances`：合并 WebView 与 Store 两端的 `cf_clearance` 并去重（覆盖 `GET_COOKIE_INFO` 开启与回退 `getCookie` 两条路径）。
   d. `clearClearance` + `restoreClearance`：清除两端 `cf_clearance` 且保留 `cdb_auth`/`cdb_sid`、写过期串后 `flush()`、失败时 `restoreClearance` 完整恢复两端 `cf_clearance` 并 `flush()`（含带 `Domain`/`Secure`/`Partitioned` 属性的 `cf_clearance`）。
   e. `primeWebView`：将 Store 中登录 Cookie（`cdb_auth`、`cdb_sid` 及完整属性）写入 WebView 并 `flush()`，严格排除 `cf_clearance`；Store 仅有 `cf_clearance` 或为空时不调用 `setCookie`/`flush`。
   f. `syncFromWebView`（`GET_COOKIE_INFO` 路径）：保留完整属性（`Domain`、`Path`、`Secure`、`HttpOnly`、`Expires`、`SameSite`）；只采纳不在 `excludeClearanceValues` 里的新 `cf_clearance`；多条新 `cf_clearance` 按 `expiresAt` 最新者胜出；`cdb_auth`/`cdb_sid` 照常同步写入 `SiteCookieStore`。
   g. `syncFromWebView`（`getCookie` 回退路径）：解析 `k1=v1; k2=v2`（含 value 带 `=`）并按 host-only、`path="/"` 构造；同样只采纳新 `cf_clearance` 并照常同步登录 Cookie。
   h. Host 校验：WebView 返回不匹配 `url.host` 的 Cookie（如 `Domain=.evil.com`、`Domain=other.4d4y.com` 对 `https://www.4d4y.com/forum/`），拒绝写入 `SiteCookieStore` 且不出现在返回结果中。
   i. 专用单线程 dispatcher 与 `flush()`：注入自定义单线程 dispatcher，断言所有 `WebViewCookieAccess` 调用均发生在该专用线程而非调用线程，写操作均调用 `flush()`。
3. 实现生产代码，运行本地测试与 lint 直到全绿。
4. 提交并推送 `card/A5a-webview-identity`，用 `gh run watch <id> --interval 60` 等待 CI 全绿。

【自检】
- 本地按 DISPATCH §2 串行执行（先导出 mise 的 `ANDROID_HOME` 和 `JAVA_HOME`）：
  - 本卡单测：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.data.network.*" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 全量编译与 lint（推送前一次）：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:testDebugUnitTest :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 每次结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿（含 release 构建）为最终证据。

【验收】
- [ ] `gradle/libs.versions.toml` 与 `hipda/build.gradle.kts` 仅新增 `androidx.webkit:webkit:1.18.0`，未改其他共享文件。证据：`git diff --stat origin/main...HEAD`。
- [ ] UA 统一：`normalizeWebViewUserAgent` 多样例、`WebViewUserAgentProvider` 懒加载缓存与失败回退、Hilt 绑定替换（含 `HttpClientModuleTest`）全部通过。证据：测试类:方法名 + `UserAgentProvider.kt` 行号。
- [ ] Cookie 桥：第 2 步 c–i 全部用例通过，真实 `AndroidWebViewCookieAccess` 仅作薄封装且不在构造期调 `CookieManager.getInstance()`。证据：测试类:方法名 + 源码文件:行号。
- [ ] 无 `runBlocking`，无对真实站点网络请求。证据：`rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/webview` 无结果。
- [ ] CI 全绿。证据：CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/A5a-webview-identity`；Conventional Commits（如 `feat(network): 新增 WebView UA 提供者与 Cookie 桥`）。
- 按文末通用约定输出 ≤600 字的最后一条回复，并将详细证据、测试汇总、自检日志与本卡对外 API 清单（签名 + 文件:行号，供 A5b、B1b 使用）写入 `/home/mir/Documents/hipda-next.wt/_reports/A5a.md`。

【清理】只删除本会话自己创建的临时文件。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 先在本地跑本卡相关测试（按 lesson.md 导出 mise 的 ANDROID_HOME/JAVA_HOME，flock 串行），本地变绿后才推送；不要拿 CI 调试半成品。完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/<卡号>.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

首先 `cd /home/mir/Documents/hipda-next.wt/A5a`，确认 `git status` 干净、HEAD 为 f051255；首次推送用 `git push -u origin card/A5a-webview-identity`。
