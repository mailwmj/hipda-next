# A5a 交付报告：WebView UA 统一与 Cookie 桥

- **状态**：DONE
- **分支**：`card/A5a-webview-identity`（基于 `origin/main` @ `f051255`）
- **最终 Commit**：`931ed93b54a6a363380e867cf29abd97b6d648f1`（`feat(network): 新增 WebView UA 提供者与 Cookie 桥`）
- **CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45544672921 （`Build, Lint & Unit Tests` 11m22s，含 Debug/Release APK、Lint、Room Schema、405 项单测与 Roborazzi 截图验证，全绿）

---

## 一、验收项逐条证据

### 1. 依赖引入与共享文件边界
- **结果**：通过
- **改动**：仅在 `gradle/libs.versions.toml` 新增 `webkit = "1.18.0"` 与 `androidx-webkit = { module = "androidx.webkit:webkit", version.ref = "webkit" }`，在 `hipda/build.gradle.kts` 新增 `implementation(libs.androidx.webkit)`；未修改其他共享文件。
- **证据（`git diff --stat origin/main...HEAD`）**：
```text
 gradle/libs.versions.toml                          |   2 +
 hipda/build.gradle.kts                             |   1 +
 .../jejer/hipda/data/network/UserAgentProvider.kt  |   5 +-
 .../data/network/webview/WebViewCookieAccess.kt    |  51 ++
 .../data/network/webview/WebViewCookieBridge.kt    | 318 ++++++++++
 .../data/network/webview/WebViewNetworkModule.kt   |  41 ++
 .../network/webview/WebViewUserAgentProvider.kt    |  56 ++
 .../hipda/data/network/HttpClientModuleTest.kt     |   3 +-
 .../network/webview/WebViewCookieBridgeTest.kt     | 637 +++++++++++++++++++++
 .../webview/WebViewUserAgentProviderTest.kt        | 218 +++++++
 10 files changed, 1329 insertions(+), 3 deletions(-)
```

### 2. UA 统一（`normalizeWebViewUserAgent` + `WebViewUserAgentProvider` + Hilt 绑定替换）
- **结果**：通过
- **源码位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:21-30`：`normalizeWebViewUserAgent`
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:36-56`：`WebViewUserAgentProvider`（`lazy(LazyThreadSafetyMode.SYNCHRONIZED)` 在调用线程懒加载并缓存，捕获任意 `Throwable` 与空白回退至 `DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA`）
  - `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:17-26`：保留 `DefaultUserAgentProvider` 与 `DEFAULT_MOBILE_CHROME_UA`
  - `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:29-36`：`UserAgentModule.bindUserAgentProvider(impl: WebViewUserAgentProvider): UserAgentProvider`
- **单测证据**：
  - `WebViewUserAgentProviderTest`（12/12 PASSED）：
    - `normalizeWebViewUserAgent_stripsWvAndVersion4FromStandardPixelWebViewUa`
    - `normalizeWebViewUserAgent_stripsWvAndVersion4FromAndroid10ReducedUa`
    - `normalizeWebViewUserAgent_preservesTabletOrFoldableUaWithoutMobileToken`
    - `normalizeWebViewUserAgent_handlesMidParenthesisWvWithoutStraySemicolons`
    - `normalizeWebViewUserAgent_isIdempotentForStandardChromeUa`
    - `normalizeWebViewUserAgent_handlesMixedCaseAndExtraSpacesWithoutManglingOtherTokens`
    - `normalizeWebViewUserAgent_fallsBackToDefaultChromeUaWhenBlankOrEmptyAfterCleanup`
    - `provider_loadsLazilyOnCallingThreadAndCachesResult`
    - `provider_concurrentFirstCallsInvokeSupplierExactlyOnce`
    - `provider_fallsBackAndCachesWhenSupplierThrowsErrorOrRuntimeException`
    - `provider_fallsBackAndCachesWhenSupplierReturnsBlank`
    - `hiltAnnotationsAndUserAgentModuleBindingPointToWebViewUserAgentProvider`
  - `HttpClientModuleTest`（4/4 PASSED）：
    - `hiltModuleAnnotationsAreConfiguredForOkHttpClientAndUserAgent`（校验 `UserAgentModule.bindUserAgentProvider(WebViewUserAgentProvider)`）

### 3. WebView Cookie 桥与 `AndroidWebViewCookieAccess` 薄封装
- **结果**：通过
- **源码位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieAccess.kt:14-20`：`WebViewCookieAccess` 接口
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieAccess.kt:28-51`：`AndroidWebViewCookieAccess`（无实例字段，仅在方法体内按需调用 `CookieManager.getInstance()`，`getCookieInfo` 先检 `WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)` 再调 `CookieManagerCompat.getCookieInfo`）
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:15-318`：`CF_CLEARANCE_COOKIE_NAME`、`ClearanceBackup`、`CookieSyncResult`、`WebViewCookieBridge`
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewNetworkModule.kt:15-41`：`@WebViewCookieDispatcher`、`WebViewNetworkModule`（绑定 `AndroidWebViewCookieAccess` 并提供名为 `"webview-cookie-bridge"` 的 daemon 单线程 `CoroutineDispatcher`）
- **单测证据（`WebViewCookieBridgeTest`，14/14 PASSED）**：
  - c. `snapshotClearances_mergesAndDeduplicatesStoreAndWebViewViaGetCookieInfo`
  - c. `snapshotClearances_mergesAndDeduplicatesViaLegacyGetCookieFallback`
  - d. `clearClearanceAndRestoreClearance_handlesBothSidesWithDomainSecurePartitionedAndPreservesLoginCookies`
  - d. `clearClearanceAndRestoreClearance_legacyFallbackWritesHostOnlyPathRoot`
  - d. `clearClearanceAndRestoreClearance_noOpWhenEmpty`
  - e. `primeWebView_writesLoginCookiesWithFullAttributesAndStrictlyExcludesCfClearance`
  - e. `primeWebView_skipsSetCookieAndFlushWhenStoreEmptyOrOnlyContainsCfClearance`
  - f. `syncFromWebView_getCookieInfoPreservesAttributesFiltersOldAndPicksLatestClearance`
  - f. `syncFromWebView_returnsNullFreshClearanceWhenOnlyOldClearancePresentButStillSyncsLoginCookies`
  - g. `syncFromWebView_legacyGetCookieFallbackParsesEqualsInValuesAndFiltersClearance`
  - h. `syncFromWebView_rejectsCookiesForMismatchedOrUnrelatedHosts`
  - i. `allWebViewCookieAccessCallsRunOnDedicatedCookieDispatcherAndWritesFlush`
  - i. `webViewNetworkModuleProvidesNamedDaemonSingleThreadDispatcherAndHiltBindings`
  - i. `androidWebViewCookieAccessDoesNotHoldCookieManagerFieldInConstructor`

### 4. 无 `runBlocking`、无真实站点网络请求
- **结果**：通过
- **证据**：
  - `rg -n 'runBlocking' hipda/src/main/java/net/jejer/hipda/data/network/webview` 退出码 `1`（无匹配结果）。
  - 全部测试仅使用内存 `InMemorySiteCookieStore` 与 `FakeWebViewCookieAccess`，零真实网络访问。

### 5. CI 全绿
- **结果**：通过
- **证据**：
  - Run URL：https://github.com/mailwmj/hipda-next/actions/runs/45544672921
  - Commit：`931ed93b54a6a363380e867cf29abd97b6d648f1`
  - 结果：`Build, Lint & Unit Tests`（ID `138971298542`）`success`（11m22s），Debug/Release APK 产物均校验通过。

---

## 二、自检与测试汇总

| 检查项 | 命令（均遵循 DISPATCH §2 `flock` 串行 + `--stop`） | 结果 |
| --- | --- | --- |
| 全量编译、单测与 Lint | `./gradlew :hipda:assembleDebug :hipda:testDebugUnitTest :hipda:lintDebug` | SUCCESS，405/405 单测通过；`lint-results-debug.sarif` 对本卡文件零告警（`rg -n "webview\|UserAgentProvider\|webkit"` 无命中） |
| 网络层回归单测（清理 `Thread.id` 弃用告警后） | `./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.data.network.*"` | SUCCESS，144/144 单测通过，零新增编译告警 |
| GitHub Actions Android CI | `gh run watch 45544672921 --interval 60 --exit-status` | SUCCESS（含 release APK 构建、lint、Room schema 校验、全量单测与 Roborazzi 截图验证） |

生产代码行数统计：新增 4 个文件共 466 行（`WebViewUserAgentProvider.kt` 56 行、`WebViewCookieAccess.kt` 51 行、`WebViewCookieBridge.kt` 318 行、`WebViewNetworkModule.kt` 41 行），结构精简无冗余。

---

## 三、对外 API 清单（供 A5b、B1b 直接调用）

### 1. UA 统一
- `fun normalizeWebViewUserAgent(raw: String): String`
  - 位置：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:21`
- `@Singleton class WebViewUserAgentProvider : UserAgentProvider`
  - `@Inject constructor(@ApplicationContext context: Context)`（`WebViewUserAgentProvider.kt:41-44`）
  - `override fun userAgent(): String`（`WebViewUserAgentProvider.kt:55`）
  - Hilt 绑定：`UserAgentModule.bindUserAgentProvider(impl: WebViewUserAgentProvider): UserAgentProvider`（`hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:33-35`）

### 2. WebView Cookie 桥与模型
- `const val CF_CLEARANCE_COOKIE_NAME: String = "cf_clearance"`
  - 位置：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:15`
- `data class ClearanceBackup(val storeCookies: List<Cookie>, val webViewSetCookieHeaders: List<String>)`
  - `val isEmpty: Boolean`
  - 位置：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:24-30`
- `data class CookieSyncResult(val syncedCookies: List<Cookie>, val freshClearance: String?)`
  - 位置：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:33-36`
- `@Singleton class WebViewCookieBridge @Inject constructor(siteCookieStore: SiteCookieStore, webViewCookieAccess: WebViewCookieAccess, @WebViewCookieDispatcher cookieDispatcher: CoroutineDispatcher)`
  - 位置：`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieBridge.kt:45-50`
  - `suspend fun snapshotClearances(url: HttpUrl): Set<String>`（`WebViewCookieBridge.kt:53`）
  - `suspend fun clearClearance(url: HttpUrl): ClearanceBackup`（`WebViewCookieBridge.kt:75`）
  - `suspend fun restoreClearance(url: HttpUrl, backup: ClearanceBackup)`（`WebViewCookieBridge.kt:93`）
  - `suspend fun primeWebView(url: HttpUrl)`（`WebViewCookieBridge.kt:112`）
  - `suspend fun syncFromWebView(url: HttpUrl, excludeClearanceValues: Set<String> = emptySet()): CookieSyncResult`（`WebViewCookieBridge.kt:132-135`）

### 3. Cookie 访问薄封装与 Hilt 模块
- `interface WebViewCookieAccess`（`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewCookieAccess.kt:14-20`）
  - `fun isGetCookieInfoSupported(): Boolean`
  - `fun getCookieInfo(url: String): List<String>`
  - `fun getCookie(url: String): String?`
  - `fun setCookie(url: String, value: String)`
  - `fun flush()`
- `@Singleton class AndroidWebViewCookieAccess @Inject constructor() : WebViewCookieAccess`（`WebViewCookieAccess.kt:28-51`）
- `@Qualifier annotation class WebViewCookieDispatcher`（`hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewNetworkModule.kt:15-17`）
- `abstract class WebViewNetworkModule`（`WebViewNetworkModule.kt:20-41`）
  - `bindWebViewCookieAccess(impl: AndroidWebViewCookieAccess): WebViewCookieAccess`（`WebViewNetworkModule.kt:24-26`）
  - `provideWebViewCookieDispatcher(): CoroutineDispatcher`（`WebViewNetworkModule.kt:31-39`）
