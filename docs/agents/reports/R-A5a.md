# R-A5a 独立代码审查报告：WebView UA 统一与 Cookie 桥（commit `931ed93`）

- **判定**：**CHANGES-REQUESTED**
- **审查对象**：`origin/card/A5a-webview-identity` @ `931ed93b54a6a363380e867cf29abd97b6d648f1`（相对 `f051255`）
- **CI 核验**：Run [`45544672921`](https://github.com/mailwmj/hipda-next/actions/runs/45544672921)（`headSha = 931ed93b54a6a363380e867cf29abd97b6d648f1`），`Build, Lint & Unit Tests` `success`（11m22s，含 `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、全量单测与 Roborazzi 截图验证、APK badging 校验）。
- **范围与红线核验**：
  - 改动文件共 10 个（`+1329 / -3`），共享文件仅 `gradle/libs.versions.toml`（+2）与 `hipda/build.gradle.kts`（+1）引入 `androidx.webkit:webkit:1.18.0`，未触碰 `SiteCookieStore`、`DataStoreSiteCookieStore`、`BrowserHeaderInterceptor`、`AppCookieInterceptor`、`ManualRedirectInterceptor`、`TransportInterceptor`、`HttpClientModule` 等受保护文件。
  - `DefaultUserAgentProvider` 及其 `DEFAULT_MOBILE_CHROME_UA` 完整保留（`UserAgentProvider.kt:17-26`）。
  - `git grep runBlocking 931ed93 -- hipda/src/main/java/net/jejer/hipda/data/network/webview/` 无匹配；单测零真实网络请求。

---

## 一、阻断项（1 项）

### B1. `getCookie` 退化路径的 `clearClearance` 只写 host-only `Path=/` 过期头，无法删除真实 `CookieManager` 中 `.4d4y.com` 作用域的 `cf_clearance`，且被 `FakeWebViewCookieAccess` 掩盖

- **位置**：
  - 生产代码：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:198-206`
  - 掩盖问题的假实现：`hipda/src/test/java/net/jejer/hipda/data/network/webview/WebViewCookieBridgeTest.kt:592-609`
- **背景说明（公平性说明）**：任务卡 `A5a.md:72` 括号中写了“（回退路径写入 host-only `Path=/` 过期串）”，实现者系按卡片字面实现；但对照 `R-A5a.md` 审查重点第 2、4 条与 `docs/design/cloudflare-research.md` §1.3 / §5.3，该写法在真实 Chromium `CookieManager` 上无法清除 `.4d4y.com` 的 `cf_clearance`，且后续 `A5b`/`A6` 边界不允许修改 `WebViewCookieBridge.kt`，需在本卡修正。
- **根因分析**：
  1. `WebViewFeature.GET_COOKIE_INFO` 随 `androidx.webkit 1.6.0` / Chromium WebView ~110+ 引入。项目 `minSdk = 26`，未装 GMS / 未更新系统 WebView 的国行 Android 8–13（WebView < 110）设备会进入 `isGetCookieInfoSupported() == false` 退化分支（且同一批设备无 API 34 `HttpEngine` 与 `play-services-cronet`，OkHttp 回退 Conscrypt，正是过盾与二次挑战的高发机型）。
  2. Cloudflare 下发的 `cf_clearance` 恒为域级安全 Cookie（`Domain=.4d4y.com; Path=/; Secure; HttpOnly; SameSite=None`）。即使旧版 WebView Java 层不支持 `GET_COOKIE_INFO`，其底层 Chromium `CookieMonster` 依然严格按 `(name, domain, hostOnly, path)` 存储。
  3. `WebViewCookieBridge.kt:204` 仅写入 `cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/`（无 `Domain`、无 `Secure`），在 Chromium `CookieMonster` 中只会命中 `(cf_clearance, www.4d4y.com, hostOnly=true, /)`，**完全不会删除 `(cf_clearance, .4d4y.com, hostOnly=false, /)`**。
  4. 单测 `FakeWebViewCookieAccess`（`WebViewCookieBridgeTest.kt:593-609`）在 `getCookieInfoSupported == false` 分支仅按 `it.first == name` 过滤删除，忽略了 `Domain`/`Path`/`Secure`，从而掩盖了该缺陷。
- **具体触发路径与后果**：
  1. 国行无 GMS Android 8–13（WebView < 110）首次过盾成功，WebView `CookieManager` 存入 `cf_clearance=<old>; Domain=.4d4y.com; Path=/; Secure; HttpOnly; SameSite=None`，`SiteCookieStore` 同步 `<old>`。
  2. 后续 OkHttp（Conscrypt TLS）请求因 TLS/H2 指纹差异或 CF Precursor Armed 模式降级收到 `403 cf-mitigated: challenge`，触发 `A5b` 的 `WebViewChallengeSolver`。
  3. 求解器依次调用 `snapshot = snapshotClearances(url)`（得 `{"<old>"}`）和 `clearClearance(url)`。`SiteCookieStore` 中的 `<old>` 被删除，但 WebView 端因只写 host-only `Path=/` 过期串，`.4d4y.com` 下的 `<old>` **原封不动残留**。
  4. `ChallengeActivity` 加载 `https://www.4d4y.com/forum/` 时 WebView 仍携带 `Cookie: cf_clearance=<old>`；Cloudflare 对 WebView 的 Chromium TLS 指纹放行 `<old>`，直接返回 HTTP 200 论坛页且**不展示挑战、不下发新 `Set-Cookie: cf_clearance`**（见 `cloudflare-research.md:39`）。
  5. `syncFromWebView(url, excludeClearanceValues = {"<old>"})` 将 `<old>` 当作旧快照剔除，返回 `freshClearance = null`；`WebViewChallengeSolver` 空等 8 秒超时失败并恢复旧备份，连续 3 轮后打入冷却，**Cloudflare 验证恢复通道在该有效期内（最长 30 分钟）彻底失效**。
- **最小修复建议**：
  - 在 `clearWebViewClearances` 的 `else` 分支（`WebViewCookieBridge.kt:198-206`），利用 `url.host`（如 `www.4d4y.com`）与 OkHttp 5 自带的 `url.topPrivateDomain()`（如 `4d4y.com`）枚举候选域（`null` host-only、`url.host`、`.${url.host}`，以及 `topPrivateDomain` 非空时的 `topDomain` 与 `.$topDomain`）和路径（`"/"` 与 `url.encodedPath` 的父目录路径），对每个组合同时发送普通与 `; Secure; SameSite=None` 过期头（参考 `fluxdo` `MainActivity.kt:599-630`）。
  - 同步修正 `FakeWebViewCookieAccess` 在 `getCookieInfoSupported == false` 下也复用底层带作用域（`headerScopeKey`）的存储，`getCookie()` 只投影 `name=value`，使单测真实反映 `CookieManager` 域匹配删除语义。

---

## 二、逐项审查详情

### 1. UA 统一（`WebViewUserAgentProvider.kt`、`UserAgentProvider.kt`）

1. **归一化正则精确性（`WebViewUserAgentProvider.kt:11-30`）**：
   - `VERSION_4_0_REGEX = Regex("""\bVersion/4\.0(?=\s|$)""", RegexOption.IGNORE_CASE)`：`\b` 与 `(?=\s|$)` 双重边界确保只剥离独立的 `Version/4.0` 标记，不会误伤 `MyVersion/4.0` 或 `Version/4.0.1`。
   - `WV_TOKEN_REGEX = Regex("""\s*;\s*wv(?=\s*(?:[;)]|$))""", RegexOption.IGNORE_CASE)`：兼容 `; wv)`（标准 Pixel / Android 10 `K` Reduced UA）与括号中部 `; wv;`（`; wv; Pixel 8`），不会误删 `WVGA-Device`、`Build/WV123` 等含 `wv` 子串的机型名；配合 `SPACE_BEFORE_DELIM_REGEX` 与 `MULTI_WHITESPACE_REGEX` 清理多余空白，同时避免产生双分号 `;;` 或控制换行符。
   - 完整保留真实 `Chrome/<major>.<minor>.<build>.<patch>`、`Mobile`（手机有、平板/折叠屏展开无）与其余内核标记；原串或清洗后为空白时回退 `DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA`。
2. **`WebSettings.getDefaultUserAgent(context)` 调用线程安全性**：
   - **构造期零调用**：`WebViewUserAgentProvider`（`WebViewUserAgentProvider.kt:41-53`）使用 `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`，Hilt 在主线程装配 `OkHttpClient` / `BrowserHeaderInterceptor` 时不会触发 `WebViewFactory` 加载。
   - **OkHttp 工作线程首次调用安全**：Android AOSP `WebSettings.getDefaultUserAgent(Context)` 走静态 `WebViewFactory.getProvider().getStatics().getDefaultUserAgent(context)`（Chromium `SharedStatics` / `AwSettings.getDefaultUserAgent()`），不要求 UI 线程、不实例化 `WebView`、也不向主线程 `Looper` `postAndWait`（不会与主线程死锁）；首次调用在 OkHttp 后台线程完成 WebView provider 类加载（约 20–100ms Binder/IO），反而避免了冷启动主线程卡顿。
   - **主线程触发可能性**：常规撞盾路径下，必先有一次 OkHttp 请求经过 `BrowserHeaderInterceptor` 在工作线程完成初始化并缓存，`ChallengeActivity` 在主线程读 `userAgent()` 时直接命中内存常量；仅在未发任何 OkHttp 请求前直接打开登录/调试 WebView（如 B1b）时才会在主线程首次触发，而该场景本身就在主线程执行 `new WebView(context)`（同样加载 `WebViewFactory.getProvider()`），无额外风险。
3. **并发懒加载与失败回退**：
   - `LazyThreadSafetyMode.SYNCHRONIZED` 保证多线程并发首次访问只执行一次 `rawUserAgentSupplier()`（单测 `provider_concurrentFirstCallsInvokeSupplierExactlyOnce` 已验证）。
   - `try { rawUserAgentSupplier() } catch (_: Throwable) { "" }` 在 `lazy` 块**内部**捕获全部 `Exception` 与 `Error`（如 `MissingWebViewPackageException`、`UnsatisfiedLinkError`），使 `lazy` 正常完成初始化并缓存 `DEFAULT_MOBILE_CHROME_UA`，不会在每次请求时反复重试失败的 IPC。
4. **WebView 与网络层 UA 一致性**：
   - 网络层：`UserAgentModule` 已绑定 `WebViewUserAgentProvider`（`UserAgentProvider.kt:35`），`BrowserHeaderInterceptor`（`BrowserHeaderInterceptor.kt:17`）在每一跳强制覆盖 `.header("User-Agent", userAgentProvider.userAgent())`，且 `cronet-okhttp` 会将该 `User-Agent` 头原样透传给 `CronetEngine`（覆盖 Cronet 默认 UA），Coil 3 共用同一 `OkHttpClient`。
   - WebView 侧契约：系统 `new WebView(context)` 默认仍带 `; wv` 与 `Version/4.0`，必须显式设置 `webView.settings.userAgentString = userAgentProvider.userAgent()` 才与网络层完全一致。已核对 `A5b.md:63` 明确包含该设置；**需提醒 B1b / D2 的登录 WebView 同样必须设置 `settings.userAgentString = userAgentProvider.userAgent()`**。

---

### 2. Cookie 桥（`WebViewCookieBridge.kt`、`WebViewCookieAccess.kt`、`WebViewNetworkModule.kt`）

1. **`cf_clearance` 单向流动与快照过滤**：
   - `primeWebView`（`WebViewCookieBridge.kt:113-115`）显式过滤 `it.name != CF_CLEARANCE_COOKIE_NAME`，严守 `cf_clearance` 仅从 WebView → Store 单向同步。
   - `syncFromWebView`（`WebViewCookieBridge.kt:148-156`）丢弃空白值与 `excludeClearanceValues` 中的旧快照值；同 `(domain, path)` 下存在多枚新候选（如 CHIPS 分区 + 非分区双变体）时按 `cookie.expiresAt > current.expiresAt` 选最晚过期者，与 `cloudflare-research.md` §1.3 一致。
2. **清除与备份恢复**：
   - `GET_COOKIE_INFO` 路径（`WebViewCookieBridge.kt:188-196, 266-305`）：Chromium `CookieManager::GetCookieInfo` 底层调用 `CanonicalCookie::BuildCookieAttributesLine`，对 host-only Cookie 省略 `domain=`、对域级 Cookie 输出 `domain=.4d4y.com`、对 CHIPS Cookie 输出 `Partitioned`；`buildClearanceExpirationHeader` 大小写不敏感提取并保留 `Domain`/`Path`/`Secure`/`HttpOnly`/`SameSite`/`Partitioned`，可精确清除 host-only、`.4d4y.com` 及 CHIPS 分区副本，同时 `backups` 保留原始属性串供 `restoreClearance` 完整恢复。
   - 退化路径（`WebViewCookieBridge.kt:197-206`）：见阻断项 **B1**。
   - 原子性小瑕疵（建议项）：`clearClearance`（`WebViewCookieBridge.kt:76-86`）先同步删除了 `siteCookieStore` 中的 `cf_clearance`，再 `withContext(cookieDispatcher)` 清 WebView；若此时协程被取消或 WebView 抛异常，调用方拿不到 `ClearanceBackup`，Store 中已删的旧 `cf_clearance` 无法恢复。建议在 `clearClearance` 内捕获异常回滚 `storeBackups` 或先读 WebView 后统一写删。
3. **`getCookieInfo` 与退化路径属性解析（经 `javap` 核对 `androidx.webkit:1.18.0` 与 OkHttp 5 `Cookie` 字节码）**：
   - 解包 `webkit-1.18.0.aar` 核实：`CookieManagerCompat.getCookieInfo(CookieManager, String)` 在不支持特性时抛 `UnsupportedOperationException`；`AndroidWebViewCookieAccess.getCookieInfo`（`WebViewCookieAccess.kt:35-38`）先检 `WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)`，防御得当；且不在构造函数中调 `CookieManager.getInstance()`。
   - 经 `javap` 核对 OkHttp 5 `Cookie$Companion.parse$okhttp`：大小写不敏感解析 `Expires`、`Max-Age`（优先于 `Expires`）、`Domain`（去前导点并校验 `PublicSuffixDatabase`）、`Path`、`Secure`、`HttpOnly`、`SameSite`（存入 `Cookie.sameSite: String?`，且 `CookieStoreSerialization.kt:31` 已持久化 `sameSite`）；对未知属性 `Partitioned` 安全忽略不报错。`readWebViewCookies`（`WebViewCookieBridge.kt:176`）额外校验 `cookie.expiresAt > now` 过滤已过期条目。
   - 退化路径 `parseLegacyCookieHeader`（`WebViewCookieBridge.kt:217-234`）：按 `;` 切分、首个 `=` 拆键值（保留值内 `=` 如 Base64 `==`），构造 `hostOnlyDomain(url.host)` + `path("/")` 会话 Cookie（`DataStoreSiteCookieStore` 会持久化会话 Cookie 以保登录态）。
4. **写入 Store 的 Host 校验**：
   - `GET_COOKIE_INFO` 路径经 `Cookie.parse`（`domainMatch` + `PublicSuffixDatabase`）与 `cookie.matchesHost(url.host)` 双重校验，并在 `DataStoreSiteCookieStore.saveFromResponse` 第三次校验；退化路径强制 `hostOnlyDomain(url.host)`；跨站域（`.evil.com`）、兄弟子域（`other.4d4y.com`）、后缀欺骗（`www.4d4y.com.evil.com`）与公共后缀（`.com`）均被拒绝且不混入 `CookieSyncResult`。
5. **`flush()` 时机与 IPC 顺序**：
   - `clearClearance`、`restoreClearance`、`primeWebView` 仅在确实调用了 `setCookie` 时于批末调一次 `flush()`；只读操作与空写操作不调 `flush()`。
   - 架构契合点：`"webview-cookie-bridge"` 是无 Android `Looper` 的普通后台线程，若用 3 参数 `setCookie(url, value, callback)` 反而会因线程无 `Looper` 出问题；而 Chromium `android_webview/browser/cookie_manager.cc` 中 2 参数 `setCookie` 与同步阻塞的 `flush()` / `getCookieInfo()` / `getCookie()` 全部在同一个串行 `cookie_store_task_runner_` 上按 FIFO 执行，因此 `setCookie` 批末跟一次 `flush()` 既保证内存与磁盘落库完毕，又避免主线程 IPC 掉帧。
6. **专用单线程 Dispatcher 线程生命周期（`WebViewNetworkModule.kt:31-39`）**：
   - `@Singleton` + `@Provides` 保证全进程仅创建 1 个名为 `"webview-cookie-bridge"` 的 `isDaemon = true` 线程，静态工厂 lambda 不捕获 `Activity`/`Context`，**无线程泄漏风险**。

---

### 3. `androidx.webkit:1.18.0` 依赖与 Release R8

- `gradle/libs.versions.toml` 与 `hipda/build.gradle.kts` 仅新增 `androidx.webkit:webkit:1.18.0`（AAR `classes.jar` 308 KB，仅依赖 `androidx.annotation`、`androidx.core`、`org.jspecify`）。
- 解包核对 `webkit-1.18.0.aar/proguard.txt`：自带 consumer keep 规则 `-keep public class org.chromium.support_lib_boundary.* { public *; }` 保护反射边界接口；CI Run `45544672921` 中 `assembleRelease`（启用 `isMinifyEnabled = true`）及 APK 校验一次通过。

---

### 4. 测试质量与突变检验（`WebViewCookieBridgeTest` / `WebViewUserAgentProviderTest`）

#### 4.1 `FakeWebViewCookieAccess` 掩盖的真实 `CookieManager` 差异
1. **退化模式作用域缺失**：`FakeWebViewCookieAccess` 在 `getCookieInfoSupported == false` 时仅按 `name` 存取删除（`WebViewCookieBridgeTest.kt:593-609`），掩盖了阻断项 **B1**（真实 `CookieManager` 仍按 `(name, domain, hostOnly, path)` 匹配）。
2. **忽略 `url` 参数**：`getCookieInfo(url)` / `getCookie(url)` / `setCookie(url, value)` 完全忽略 `url`（`WebViewCookieBridgeTest.kt:571-591`），真实 `CookieManager` 要求带合法 scheme 的 URL 并校验域/路径/CHIPS 顶级站点分区。
3. **无条件接受非法属性组合**：真实 Chromium 会静默丢弃无 `Secure` 的 `SameSite=None` 或无 `Secure` 的 `Partitioned` Cookie（2 参数 `setCookie` 无返回值提示），假实现不做校验。
4. **`getCookieInfo` 大小写与 `Max-Age` 覆盖**：真实 Chromium `BuildCookieAttributesLine` 输出全小写属性名（`domain=.4d4y.com; path=/; expires=...; secure; httponly; samesite=none; partitioned`），而单测夹具全部使用首字母大写属性名，也未覆盖 `Max-Age=` 属性解析。

#### 4.2 “改坏一行生产代码，全部单测依然通过”的实证例子
1. **改坏传给 WebView 的 URL（`WebViewCookieBridge.kt:171` 或 `99`、`119`、`184`）**：把 `val targetUrl = url.toString()` 改成 `val targetUrl = ""`（或 `url.host`）。由于 `FakeWebViewCookieAccess` 忽略 `url` 且所有测试断言都只取 `setCookieCalls` 的 `.second`（甚至在 `WebViewCookieBridgeTest.kt:279` 写成 `(_, header)` 丢弃 `url`），**全部单测照常通过**，真机上 `CookieManager` 读写全部失效。
2. **删掉同域多候选的 `expiresAt` 比较（`WebViewCookieBridge.kt:153`）**：把 `if (current == null || cookie.expiresAt > current.expiresAt)` 改成 `if (true)`（无条件取最后一条而非最晚过期）。因为单测 `WebViewCookieBridgeTest.kt:336-337` 把较早过期的 `fresh_earlier` 放在前、较晚过期的 `fresh_latest_chips` 放在后（未测 `[较晚, 较早]` 逆序），**全部单测照常通过**，遇到 Chromium 先返回新值再返回旧分区副本时就会选错旧值。
3. **删掉 `readWebViewCookies` 的过期过滤（`WebViewCookieBridge.kt:176`）**：删去 `&& cookie.expiresAt > now`。单测从未向 `cookieInfoHeaders` 放入已过期 Cookie，**全部单测照常通过**，但真机若残留 `Expires=1970` 条目会让 `syncFromWebView` 返回非空 `freshClearance` 误报过盾成功。
4. **删掉 `toExpiredStoreCookie` 的 `hostOnly` 分支（`WebViewCookieBridge.kt:243`）**：把 `if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)` 改成 `domain(cookie.domain)`，`clearClearance` 测试未覆盖 Store 内 host-only `cf_clearance` 且 Store 键不含 `hostOnly`，**全部单测照常通过**。

---

## 三、建议项（非阻断，可在修改轮一并顺手补齐或带入后续卡）

1. **修 B1 时补强 `WebViewCookieBridgeTest`**：(a) 断言 `setCookieCalls` / `getCookie*` 收到的 `url == forumUrl.toString()`；(b) 将 `syncFromWebView` 双变体测试改为 `[newer, older]` 顺序并加入 1 条已过期 `Expires=1970` 干扰项，锁死 `expiresAt > current.expiresAt` 与 `expiresAt > now`；(c) 加入一条小写属性串（`domain=.4d4y.com; path=/; expires=...; secure; httponly; samesite=none; partitioned`）用例。
2. **`clearClearance` 异常/取消回滚（`WebViewCookieBridge.kt:76-86`）**：若 `withContext(cookieDispatcher)` 抛 `CancellationException` 或运行时异常，在 `catch` 中将 `storeBackups` 写回 `siteCookieStore` 再重抛，避免调用方未拿到 `ClearanceBackup` 导致 Store 端旧凭证丢失。
3. **`syncFromWebView` 跨作用域清理旧 `cf_clearance`（供 B1b/D2 直接调用时兜底）**：当未经过 `clearClearance` 直接调 `syncFromWebView`（如交互登录）且拿到了新 `cf_clearance`（如 `.4d4y.com`）时，若 `SiteCookieStore` 先前存有另一 `domain`（如旧退化路径留下的 host-only `www.4d4y.com`）的 `cf_clearance`，可在写入新值时顺带过期其他 `(domain, path)` 的同名 `cf_clearance`，避免 `AppCookieInterceptor` 发出双 `cf_clearance` 头。
4. **下游调用提醒（供 A5b / B1b）**：所有用于站点验证或登录的 `WebView` 实例必须显式设置 `settings.userAgentString = userAgentProvider.userAgent()`（`A5b.md` 已写明，B1b 需同样遵守）。
