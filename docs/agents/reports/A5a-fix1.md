# A5a 第 1 轮修改报告（R-A5a CHANGES-REQUESTED 修复）

- **状态**：DONE
- **分支**：`card/A5a-webview-identity`
- **基线 Commit**：`931ed93b54a6a363380e867cf29abd97b6d648f1`
- **最终 Commit**：`3c9e3b3eef3fa6af6a69139117a516f9ebbae8d3`（`fix(network): 补齐旧 WebView 全域清盾、clearClearance 回滚与异域旧值清理`）
- **CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45549294299 （`Build, Lint & Unit Tests` 6m24s，含 Debug/Release APK、Lint、Room Schema、412 项单测与 Roborazzi 截图验证，全绿）

---

## 一、阻断项修复与红→绿复现证据

### 1. 阻断项：旧 WebView（无 `GET_COOKIE_INFO`）清不掉 `Domain=.4d4y.com` 的 `cf_clearance`
- **修改位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:218-227`（`clearWebViewClearances` 回退分支调用 `buildLegacyClearanceExpirationHeaders(url)`）
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:333-364`（`buildLegacyClearanceExpirationHeaders` 与 `legacyClearanceExpirationHeader`）
  - `hipda/src/test/java/net/jejer/hipda/data/network/webview/WebViewCookieBridgeTest.kt:844-949`（重写 `FakeWebViewCookieAccess`，统一按 `(name, domain, path, hostOnly, partitioned)` 建键，严格模拟 Chromium `CookieManager`“host-only 过期头删不掉域 Cookie”及 `Cookie.matches(url)` URL/域/路径过滤语义）
- **证明测试**：
  - `WebViewCookieBridgeTest.clearClearance_legacyFallbackClearsDomainHostAndHostOnlyClearancesSoFreshChallengeSucceeds`（`WebViewCookieBridgeTest.kt:222-292`）
  - `WebViewCookieBridgeTest.clearClearanceAndRestoreClearance_legacyFallbackRestoresAfterClear`（`WebViewCookieBridgeTest.kt:294-317`）

### 2. 为什么这组过期头覆盖了旧 WebView 所有可能的写入形态
当 `isGetCookieInfoSupported() == false` 时，对 `https://www.4d4y.com/forum/` 写入 3 条过期头（顺序为 host-only、`Domain=www.4d4y.com`、`Domain=.4d4y.com`；对更深子域还会逐级补齐到 `url.topPrivateDomain()`）：
```text
cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None
cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=www.4d4y.com; Path=/; Secure; SameSite=None
cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.4d4y.com; Path=/; Secure; SameSite=None
```
覆盖完备性依据：
1. **Domain 维度**：匹配 `https://www.4d4y.com/...` 的 Cookie 在 Chromium `CookieMonster` 中只能是 host-only（`www.4d4y.com`, `hostOnly=true`）、精确主机域 Cookie（`Domain=www.4d4y.com`, `hostOnly=false`）或父/可注册域 Cookie（`Domain=.4d4y.com`, `hostOnly=false`，Cloudflare 实际下发形态；深层子域逐级遍历至 `topPrivateDomain()` 同样全覆盖；IP 主机跳过 `Domain=` 仅写 host-only）。
2. **Path 维度**：Cloudflare 签发的 `cf_clearance` 固定作用域为 `Path=/`。
3. **属性维度（`Secure`/`HttpOnly`/`SameSite`）**：RFC 6265 与 Chromium 的 Cookie 唯一键不包含 `Secure`、`HttpOnly`、`SameSite`；`CookieManager.setCookie` 位于原生 HTTP 层可覆盖 `HttpOnly`，HTTPS 下显式携带 `Secure; SameSite=None` 保证 Chromium 接受 `SameSite=None` 且可覆盖任意原 `Secure`/`HttpOnly`/`SameSite` 组合（非 HTTPS 场景不附 `Secure; SameSite=None` 避免被 Chromium 拒收）。
4. **CHIPS / `Partitioned` 维度**：`WebViewFeature.GET_COOKIE_INFO` 自 Chromium WebView 110 引入，而 CHIPS（`Partitioned`）自 Chromium 114 才启用；走到回退路径的 WebView 必然 `< 110`，不存在分区 Cookie，WebView `>= 110` 走 `GET_COOKIE_INFO` 路径逐条保留原 `Partitioned` 与 `Path` 精确过期。
5. **过期维度**：同时写 `Max-Age=0` 与 `Expires=Thu, 01 Jan 1970 00:00:00 GMT`，屏蔽客户端时钟偏差并兼容全部 Chromium 版本。

### 3. 阻断项与新增测试在旧实现（`931ed93`）上的本地失败输出摘要
先仅改 `WebViewCookieBridgeTest.kt`（未改 `WebViewCookieBridge.kt`），按 DISPATCH §2 运行 `:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.network.webview.WebViewCookieBridgeTest"`，结果 `21 tests, 16 passed, 5 failed`：
```text
WebViewCookieBridgeTest > clearClearance_legacyFallbackClearsDomainHostAndHostOnlyClearancesSoFreshChallengeSucceeds FAILED
    org.junit.ComparisonFailure: expected:<c[]db_auth=keep_auth> but was:<c[f_clearance=old_domain_tok; cf_clearance=old_exact_domain_tok; c]db_auth=keep_auth>
        at ...WebViewCookieBridgeTest.kt:263

WebViewCookieBridgeTest > clearClearanceAndRestoreClearance_legacyFallbackRestoresAfterClear FAILED
    org.junit.ComparisonFailure: expected:<cdb_auth=keep[]> but was:<cdb_auth=keep[; cf_clearance=legacy_val]>
        at ...WebViewCookieBridgeTest.kt:307

WebViewCookieBridgeTest > clearClearance_rollsBackStoreBackupWhenWebViewClearThrows FAILED
    java.lang.AssertionError: expected:<[cf_clearance=store_tok; expires=Wed, 25 Nov 2026 05:15:02 GMT; domain=4d4y.com; path=/; secure; httponly; samesite=None]> but was:<[]>
        at ...WebViewCookieBridgeTest.kt:358

WebViewCookieBridgeTest > syncFromWebView_clearsOtherDomainAndPathVariantsOfOldCfClearanceInStoreWhenAdoptingFresh FAILED
    java.lang.AssertionError: expected:<[fresh_domain]> but was:<[old_subpath, old_host_only, fresh_domain]>
        at ...WebViewCookieBridgeTest.kt:622
```
清晰复现：旧实现仅清掉了 `old_host_only_tok`，`Domain=.4d4y.com`（`old_domain_tok`）与 `Domain=www.4d4y.com`（`old_exact_domain_tok`）双双残留；且 `clearClearance` 抛错丢失 store 备份、`syncFromWebView` 保留了 store 异域/异路径旧变体。

---

## 二、一并修（3 项审查建议）明细

| 审查项 | 修改位置 | 证明测试 |
| --- | --- | --- |
| 假对象按域/路径匹配并校验 `url`；补 `[新, 旧]` 逆序与已过期项测试（防 `:153`/`:171`/`:176` 回归） | `WebViewCookieBridgeTest.kt:844-949`（`FakeWebViewCookieAccess` 统一 `CookieScopeKey` + `Cookie.matches(url)` + `queriedUrls` 校验） | `syncFromWebView_getCookieInfoPicksLatestClearanceWhenNewerAppearsBeforeOlder`（`:549-572`）、`syncFromWebViewAndSnapshotAndClear_ignoreAlreadyExpiredCookiesFromGetCookieInfo`（`:574-598`）、`bridgeOperationsPassExactTargetUrlAndIsolateDomainsAndPaths`（`:730-748`） |
| `clearClearance` 在 `withContext` 取消或异常时回滚 store 备份 | `WebViewCookieBridge.kt:85-95`（`try/catch(t: Throwable)` 同步回写 `storeBackups`，回滚异常以 `addSuppressed` 挂到原异常后重抛） | `clearClearance_rollsBackStoreBackupWhenWebViewClearThrows`（`:336-368`）、`clearClearance_rollsBackStoreBackupWhenCancelledDuringWebViewClear`（`:370-408`） |
| `syncFromWebView` 采纳新值时清掉 store 里其他域/路径变体的同名旧 `cf_clearance` | `WebViewCookieBridge.kt:168-183`（仅当 `selectedClearances.isNotEmpty()` 时，过滤 `loadForRequest(url)` 中 `(domain, path) !in newClearanceScopes` 的旧 `cf_clearance` 转过期项，与 `toSave` 在单次 `saveFromResponse` 原子提交；返回值 `syncedCookies` 不含过期墓碑） | `syncFromWebView_clearsOtherDomainAndPathVariantsOfOldCfClearanceInStoreWhenAdoptingFresh`（`:600-643`）、`syncFromWebView_returnsNullFreshClearanceWhenOnlyOldClearancePresentButStillSyncsLoginCookies`（`:645-669`，确认未采纳新值时不误删在位值） |

---

## 三、自检与 CI 验证

- `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/webview`：退出码 `1`（无匹配）。
- 本地全量编译/单测/Lint（DISPATCH §2 `flock` 串行 + `./gradlew --stop`）：`./gradlew :hipda:assembleDebug :hipda:testDebugUnitTest :hipda:lintDebug` → **BUILD SUCCESSFUL**，**412/412** 单测通过（`WebViewCookieBridgeTest` 21/21、`WebViewUserAgentProviderTest` 12/12、`HttpClientModuleTest` 4/4），`lint-results-debug.sarif` 对本卡文件零告警。
- `git diff --stat origin/main...HEAD`：仍仅包含原卡允许范围的 10 个文件，未触碰任何额外共享文件。
- 远端 Android CI：https://github.com/mailwmj/hipda-next/actions/runs/45549294299 → `success`（6m24s，含 release APK、lint、Room schema、412 项单测与 Roborazzi 截图校验）。
