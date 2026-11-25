# R-A5a 第 2 轮定向复核报告（commit `3c9e3b3`）

- **判定**：**APPROVE**
- **审查对象**：`origin/card/A5a-webview-identity` @ `3c9e3b3eef3fa6af6a69139117a516f9ebbae8d3`（相对第 1 轮被审提交 `931ed93b54a6a363380e867cf29abd97b6d648f1`，分支基线 `f051255`）
- **CI 核验**：Run [`45549294299`](https://github.com/mailwmj/hipda-next/actions/runs/45549294299)（`headSha = 3c9e3b3eef3fa6af6a69139117a516f9ebbae8d3`），`Build, Lint & Unit Tests` `success`（6m24s，含 Debug/Release APK、Lint、Room Schema、412 项单测与 Roborazzi 截图验证）。
- **改动边界核验**：`git diff 931ed93 3c9e3b3` 仅修改 `WebViewCookieBridge.kt`（+59 行）与 `WebViewCookieBridgeTest.kt`（+314 行）；整张卡相对 `f051255` 仍严格保持原允许的 10 个文件，生产代码无 `runBlocking`。

---

## 一、阻断项修复核验（已彻底修复）

### 1. 三类过期头对写入形态的完备性（`WebViewCookieBridge.kt:224-226, 333-364`）

当 `isGetCookieInfoSupported() == false` 且 `getCookie(targetUrl)` 存在非空 `cf_clearance` 时，`buildLegacyClearanceExpirationHeaders(url)` 对 `https://www.4d4y.com/forum/` 生成以下 3 条过期头：
1. `cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None`（覆盖 `www.4d4y.com` host-only）
2. `cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=www.4d4y.com; Path=/; Secure; SameSite=None`（覆盖 `.www.4d4y.com` 精确主机域 Cookie）
3. `cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.4d4y.com; Path=/; Secure; SameSite=None`（覆盖 Cloudflare 实际签发的 `.4d4y.com` 可注册域 Cookie）

逐维度核对 Chromium `CookieMonster` 行为：
- **Domain 与子域边界**：通过 `url.host` 与 OkHttp 5 `url.topPrivateDomain()` 逐级剥离前缀直至可注册域（支持裸域 `4d4y.com`、常规子域 `www.4d4y.com`、多层子域 `a.b.4d4y.com`），绝不向公共后缀（如 `.com`）发无效头；对 IP 字面量跳过 `Domain=` 仅发 host-only；当 `topPrivateDomain()` 为 `null`（如 `localhost`）时跳过循环，无死循环风险。
- **HTTPS 下 `Secure` / `HttpOnly` / `SameSite` 覆盖能力**：Chromium `CanonicalCookie::IsEquivalent` 仅比较 `(name, domain, path, partition_key)`（其中 `domain` 前导点区分 `hostOnly`），不将 `Secure`、`HttpOnly`、`SameSite` 纳入主键；`CookieManager.setCookie` 使用 `CookieOptions::MakeAllInclusive()` 可直接覆盖 `HttpOnly` 条目；在 `url.isHttps == true` 时附带 `; Secure; SameSite=None` 既满足 Chromium 对 `SameSite=None` 必须带 `Secure` 的校验，又能同时覆盖原 `Secure=true`（Cloudflare 下发）与 `Secure=false`（如退化路径 `restoreClearance` 写回的 `Path=/` 备份）条目；非 HTTPS 测试 URL 自动省略 `; Secure; SameSite=None`，避免因非安全源被拒收。
- **CHIPS（`Partitioned`）在新旧 WebView 上的覆盖**：
  - `WebViewFeature.GET_COOKIE_INFO` 引入于 Chromium WebView 110，而 CHIPS（`Partitioned`）默认启用始于 Chromium 114。
  - 因此凡是支持存储 CHIPS 分区副本的 WebView（>= 114）均走 `GET_COOKIE_INFO` 分支（`WebViewCookieBridge.kt:209-217, 313, 330`），从原属性串保留 `; Partitioned` 精确过期；走到回退分支的旧 WebView（< 110）不存在 CHIPS 分区存储。

### 2. `FakeWebViewCookieAccess` 真实度与红→绿验证（`WebViewCookieBridgeTest.kt:844-950`）

- 重写后的 `FakeWebViewCookieAccess` 以 `CookieScopeKey(name, domain, path, hostOnly, partitioned)` 为存储主键，经 `Cookie.parse(parsedUrl, rawHeader)` 解析并校验：
  - `SameSite=None` 缺失 `Secure` 时静默拒收（`:922`）；
  - 非 HTTPS URL 写入 `Secure` Cookie 时静默拒收（`:923`）；
  - `hostOnly = true` 的 `Path=/` 过期头只会移除 `(cf_clearance, www.4d4y.com, /, hostOnly=true, false)`，**绝不会误删 `Domain=.4d4y.com` 或 `Domain=www.4d4y.com`（`hostOnly=false`）条目**；
  - `getCookieInfo(url)` 与 `getCookie(url)` 均解析 `url.toHttpUrl()` 并通过 `Cookie.matches(parsedUrl)` 过滤域名、路径与 `Secure`。
- 新增测试 `clearClearance_legacyFallbackClearsDomainHostAndHostOnlyClearancesSoFreshChallengeSucceeds`（`:222-292`）同时预置 `.4d4y.com`、`Domain=www.4d4y.com`、host-only 三枚旧 `cf_clearance`，在旧实现 `931ed93` 上明确失败（`.4d4y.com` 与 `Domain=www.4d4y.com` 残留），在 `3c9e3b3` 上通过。

---

## 二、三条建议落实与取消回滚安全性核验

1. **建议 1（假对象按域/路径匹配并校验 `url`，补 `[新, 旧]` 逆序与已过期项测试）——已落实**：
   - `syncFromWebView_getCookieInfoPicksLatestClearanceWhenNewerAppearsBeforeOlder`（`:549-572`）：以 `[newer, older]` 逆序注入双变体，锁死 `cookie.expiresAt > current.expiresAt` 逻辑。
   - `syncFromWebViewAndSnapshotAndClear_ignoreAlreadyExpiredCookiesFromGetCookieInfo`（`:574-598`）：注入 `Expires=1970` 与过去时间 Cookie，锁死 `cookie.expiresAt > now` 过滤。
   - `bridgeOperationsPassExactTargetUrlAndIsolateDomainsAndPaths`（`:730-748`）及多处 `queriedUrls` / `setCookieCalls` 断言：锁死 `url.toString()` 传参。
2. **建议 2（`clearClearance` 在 `withContext` 取消或异常时回滚 Store 备份，含 `NonCancellable` 核查）——已落实且取消安全**：
   - 代码（`WebViewCookieBridge.kt:85-95`）：
     ```kotlin
     val webViewBackups = try {
         withContext(cookieDispatcher) {
             clearWebViewClearances(url)
         }
     } catch (t: Throwable) {
         if (storeBackups.isNotEmpty()) {
             runCatching { siteCookieStore.saveFromResponse(url, storeBackups) }
                 .onFailure(t::addSuppressed)
         }
         throw t
     }
     ```
   - **为什么无需 `withContext(NonCancellable)` 即在取消时 100% 生效**：
     1. `SiteCookieStore.saveFromResponse` 是**非挂起同步方法**（`fun saveFromResponse`），在 `catch (t: Throwable)` 中执行不会触发任何协程挂起点或 `Job.ensureActive()` 检查；
     2. `DataStoreSiteCookieStore.saveFromResponse` 内部（`DataStoreSiteCookieStore.kt:45-65`）先走了内存 `synchronized(stateLock)` 立即恢复内存表（由于第 78 行 `loadForRequest` 已完成初始化，`initialized == true`，不会进 `ensureInitializedBlocking`），随后异步落盘任务投递在独立的 `persistScope`（`SupervisorJob() + Dispatchers.IO`）上，与已取消的调用方协程 `Job` 完全隔离；
     3. 回滚后第 94 行原样 `throw t` 重新抛出 `CancellationException`，严格保持结构化并发语义。
   - 单测 `clearClearance_rollsBackStoreBackupWhenWebViewClearThrows`（`:336-368`）与 `clearClearance_rollsBackStoreBackupWhenCancelledDuringWebViewClear`（`:370-408`）分别验证了运行时异常与协程取消两条路径。
3. **建议 3（`syncFromWebView` 采纳新值时清理 Store 其他域/路径旧 `cf_clearance`）——已落实**：
   - `WebViewCookieBridge.kt:171-182`：仅当 `selectedClearances.isNotEmpty()`（确实采纳了本轮新 `cf_clearance`）时，将 `loadForRequest(url)` 中 `(domain, path) !in newClearanceScopes` 的旧 `cf_clearance` 转为过期墓碑，与 `toSave` 在单次 `saveFromResponse` 调用内原子提交；未采纳新值时保留 Store 在位值不动，且 `CookieSyncResult.syncedCookies` 不混入过期墓碑。

---

## 三、回归与新问题检查

- **无新增阻断或回归问题**。
- 留给下游卡片的接口提醒（无需修改本卡）：`A5b`（卡片已包含）与 `B1b` 在初始化各自 `WebView` 时记得设置 `webView.settings.userAgentString = userAgentProvider.userAgent()`。
