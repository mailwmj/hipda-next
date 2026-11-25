# 任务卡 A5c 执行报告：统一 UA 改为桌面站点身份

## 1. 交付摘要
- **结论**：DONE
- **分支**：`card/A5c-desktop-ua`（基于 `origin/main` @ `c6aba62`）
- **Worktree**：`/home/mir/Documents/hipda-next.wt/A5c`
- **最终 Commit**：`d6b767c`（`d6b767c23cc3ef90f43bc3cd5a7a08973e135263`）
- **CI Run**：`https://github.com/mailwmj/hipda-next/actions/runs/45587005327`（Android CI `Build, Lint & Unit Tests` 全部通过，耗时 6m20s；包含 Build & Lint、Room Schema 校验、全量单元测试 + Roborazzi 截图验证、Debug/Release APK 校验）

## 2. 实现变更清单

| 文件 | 变更内容 |
|---|---|
| `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt` | `DefaultUserAgentProvider` 默认常量由 `DEFAULT_MOBILE_CHROME_UA` 改为桌面格式 `DEFAULT_DESKTOP_CHROME_UA`（`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36`）并新增 `DEFAULT_CHROME_MAJOR_VERSION = "131"`。 |
| `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt` | 改写 `normalizeWebViewUserAgent`：从系统 WebView 真实 UA 中提取正整数 Chrome 主版本号（忽略 `HeadlessChrome`、非数字、`0` 等异常 token），生成 `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<主版本>.0.0.0 Safari/537.36`（不含 `Android`、`Mobile`、`; wv`、`Version/4.0`）；提取不到或空白时回退 `DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA`。保持 `WebViewUserAgentProvider` 懒加载线程安全缓存与异常回退逻辑不变。 |
| `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentConfig.kt` | 新增统一 WebView 桌面身份配置入口 `applyUnifiedDesktopUserAgent` 与 `buildDesktopUserAgentMetadata`：先写 `settings.userAgentString`（空白时回退桌面默认 UA，避免回落系统默认移动 UA）；若 `WebViewFeature.USER_AGENT_METADATA` 可用，经 `WebSettingsCompat.setUserAgentMetadata` 写入 `platform="Linux"`、`mobile=false`、`architecture="x86"`、`bitness=64`、`platformVersion=""`、`model=""`、`wow64=false`、`fullVersion="<主版本>.0.0.0"` 与对齐 Chrome 主版本的 `Chromium`/`Google Chrome`/`Not_A Brand` 品牌列表（若同时支持 `USER_AGENT_METADATA_FORM_FACTORS` 则追加 `FORM_FACTOR_DESKTOP`）；不支持或异常时仅设置 UA，不崩溃。 |
| `hipda/src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt` | `createConfiguredWebView` 改为调用统一入口 `applyUnifiedDesktopUserAgent(this, userAgent)`（不改 Cookie 桥、网关、求解状态机）。 |
| `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleLoginWebView.kt` | `configureSampleLoginWebView` 改为调用统一入口 `applyUnifiedDesktopUserAgent(this, userAgentProvider)`。 |
| `hipda/src/main/java/net/jejer/hipda/data/network/BrowserHeaderInterceptor.kt` | 经核查仅设置 `User-Agent`/`Accept`/`Accept-Language`，不发送 `Sec-CH-UA-*`，按卡片要求“不发则不新增”保持原样。 |
| `docs/design/tech-plan.md` | 第 60 行改写为桌面版 Linux Chrome UA + `UserAgentMetadata` 对齐策略，并记录原因（4d4y.com 以 `Android` 为触发词下发缺失 `#umenu` 且 `pm.php`/`notice.php` 500 的手机版模板）。 |
| `hipda/src/test/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProviderTest.kt` | 更新为覆盖 Android 10/14、`; wv`、`Version/4.0`、折叠屏无 `Mobile`、混合大小写/多余空格、桌面 UA 幂等、空白/无 Chrome/非法版本回退、`DefaultUserAgentProvider` 桌面格式、懒加载并发缓存、异常回退与 Hilt 注解（12 个测试）。 |
| `hipda/src/test/java/net/jejer/hipda/data/network/webview/WebViewDesktopIdentityTest.kt` | 新增 Robolectric + MockWebServer 闭环单测与源码扫描测试（5 个测试）：三处 UA 一致性、`UserAgentMetadata` 不可用不崩、假 `WebSettings` 断言 `platform="Linux"` / `mobile=false` / 动态品牌版本、异常容错、`src/main`+`src/debug` 零 `Mobile Safari` 扫描及统一入口校验。 |
| `hipda/src/test/java/net/jejer/hipda/data/network/NetworkInterceptorChainTest.kt` | 第 614 行 `DefaultUserAgentProvider` 断言同步改为桌面格式。 |
| `hipda/src/test/java/net/jejer/hipda/data/network/access/SiteAccessGatewayTest.kt` | 第 1309 行常量引用更新为 `DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA`。 |
| `hipda/src/test/java/net/jejer/hipda/ui/challenge/ChallengeActivityTest.kt` | 第 76 行 `unifiedUa` 测试样例同步更新为桌面 UA。 |
| `hipda/src/test/java/net/jejer/hipda/debug/sampleexport/SampleExportEngineTest.kt` | 第 85 行 `unifiedUa` 测试样例同步更新为桌面 UA。 |

## 3. 逐条验收与证据

### 验收 1：多组真实 WebView UA 转换 + 改坏一行测试会红
- **状态**：通过
- **测试证据**：`WebViewUserAgentProviderTest` 12/12 通过，覆盖 Android 14 Pixel 8（含 `; wv`、`Version/4.0`、`Chrome/131.0.6778.200`）、Android 10 Reduced（`Android 10; K; wv`、`Chrome/134.0.0.0`）、折叠屏无 `Mobile`（`Chrome/135.0.7049.111`）、括号中段 `; wv;` 与混合大小写（`Chrome/132`/`136`）、桌面 UA 幂等（`Chrome/138`）、空串/空白/`; wv Version/4.0`/无 Chrome/HeadlessChrome/`Chrome/0.0.0.0`/`Chrome/134beta` 回退默认桌面 UA（`Chrome/131.0.0.0`），全部断言不含 `Android`/`Mobile`/`; wv`/`Version/4.0` 且保留 Chrome 主版本。
- **改坏一行自证**：将 `WebViewUserAgentProvider.kt` 中 `Chrome/$chromeMajorVersion.0.0.0` 临时改为固定 `Chrome/131.0.0.0`，本地跑 `WebViewUserAgentProviderTest` 立即失败（`12 tests, 6 passed, 6 failed`，exit code `1`），例如 `normalizeWebViewUserAgent_convertsAndroid10ReducedWebViewUaToDesktopKeepingChromeMajor` 报 `expected:<...Chrome/13[4].0.0.0...> but was:<...Chrome/13[1].0.0.0...>`；还原后 12/12 通过。

### 验收 2：挑战 WebView、登录 WebView、OkHttp 请求三处 UA 字符串完全相同
- **状态**：通过
- **测试证据**：`WebViewDesktopIdentityTest.challengeWebView_loginWebView_andOkHttpRequest_shareExactSameDesktopUserAgent` 使用同一 `WebViewUserAgentProvider`（输入 Android 14 Pixel 8 真实 WebView UA `Chrome/136.0.7103.60`），分别通过：
  1. `AndroidChallengeWebViewHost` + `ChallengeActivity` 取得挑战页 `WebView.settings.userAgentString`；
  2. `configureSampleLoginWebView` 取得调试登录页 `WebView.settings.userAgentString`；
  3. `HttpClientModule.provideOkHttpClient` + `BrowserHeaderInterceptor` 向本地 `MockWebServer` 发起 GET 取得请求头 `User-Agent`；
  三处均严格等于 `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36`，且确认 OkHttp 请求未新增 `Sec-CH-UA` / `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform`。

### 验收 3：metadata 不可用时不崩；可用时 platform=Linux、mobile=false（假 WebSettings 断言）
- **状态**：通过
- **测试证据**：
  - `WebViewDesktopIdentityTest.applyUnifiedDesktopUserAgent_whenMetadataUnavailable_setsUaOnlyAndDoesNotCrash`：使用假 `WebSettings`（`FakeWebSettings : RoboWebSettings`）分别在 `metadataSupported = false` 和真实 `AndroidWebViewUserAgentMetadataBridge`（Robolectric 无 WebView 边界实现）下调用，不崩、仅设置 `userAgentString`、不调用 `setUserAgentMetadata`。
  - `WebViewDesktopIdentityTest.applyUnifiedDesktopUserAgent_whenMetadataAvailable_setsLinuxNonMobileAndMatchingBrandVersionsOnFakeWebSettings`：在 `metadataSupported = true` 下对假 `WebSettings` 断言 `platform == "Linux"`、`isMobile == false`、`architecture == "x86"`、`bitness == 64`、`platformVersion == ""`、`model == ""`、`isWow64 == false`、`fullVersion == "136.0.0.0"`，且 `brandVersionList` 中 `Chromium` / `Google Chrome` 主版本与全版本均随 UA 动态对齐（另用 `Chrome/141.0.0.0` 验证不写死）。
  - `WebViewDesktopIdentityTest.applyUnifiedDesktopUserAgent_handlesBlankUaAndMetadataExceptionsWithoutCrashing`：验证空白 UA 回退默认桌面 UA，以及 `isMetadataSupported` / `setUserAgentMetadata` 抛异常时不崩。

### 验收 4：源码扫描 `src/main` 与 `src/debug` 不再出现 `Mobile Safari` 字面量
- **状态**：通过
- **证据**：
  - 命令行扫描：`grep -rn "Mobile Safari" hipda/src/main hipda/src/debug core-*` 输出 0 处匹配。
  - 自动化测试：`WebViewDesktopIdentityTest.sourceScan_mainAndDebugContainZeroMobileSafariLiterals_andWebViewsUseSharedEntry` 遍历 `hipda/src/main` 与 `hipda/src/debug` 全部 `.kt`/`.java` 文件断言 0 处 `Mobile Safari`，包含内存改坏 1 行（在 `UserAgentProvider.kt` 注入 `Mobile Safari`）立即变红的自证，并校验 `ChallengeActivity.kt` 与 `SampleLoginWebView.kt` 均调用 `applyUnifiedDesktopUserAgent` 且无裸写 `userAgentString =`。

### 验收 5：CI 全绿且不访问真实站点
- **状态**：通过
- **本地串行测试（DISPATCH §2）**：
  - 命令：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests ... -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4` 后 `./gradlew --stop`
  - 结果：8 个测试类共计 `88 tests, 88 passed, 0 failed, 0 skipped`（`HttpClientModuleTest` 5、`NetworkInterceptorChainTest` 21、`SiteAccessGatewayTest` 18、`WebViewDesktopIdentityTest` 5、`WebViewUserAgentProviderTest` 12、`SampleExportEngineTest` 13、`SampleExportReadOnlyScanTest` 3、`ChallengeActivityTest` 11）。
- **GitHub Actions CI**：
  - Run：`https://github.com/mailwmj/hipda-next/actions/runs/45587005327`（commit `d6b767c`）
  - Job：`Build, Lint & Unit Tests`（ID `139111050089`）`success`；所有网络测试均使用 `MockWebServer`，未访问 `4d4y.com`。

## 4. 对外 API 清单（签名 + 文件:行号）
- `UserAgentProvider.userAgent(): String` — `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:12-14`
- `DefaultUserAgentProvider.DEFAULT_CHROME_MAJOR_VERSION: String` — `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:26`
- `DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA: String` — `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:28-29`
- `DefaultUserAgentProvider.userAgent(): String` — `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:23`
- `buildDesktopChromeUserAgent(chromeMajorVersion: String): String` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:27-28`
- `normalizeWebViewUserAgent(raw: String): String` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:41-45`
- `WebViewUserAgentProvider.userAgent(): String` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:52-71`
- `buildDesktopUserAgentMetadata(userAgent: String, includeDesktopFormFactor: Boolean = ...): UserAgentMetadata` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentConfig.kt:22-68`
- `applyUnifiedDesktopUserAgent(settings: WebSettings, userAgent: String)` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentConfig.kt:78-87`
- `applyUnifiedDesktopUserAgent(settings: WebSettings, userAgentProvider: UserAgentProvider)` — `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentConfig.kt:90-98`
