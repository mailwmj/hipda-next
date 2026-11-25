# R-A5c 独立代码审查报告：统一 UA 改为桌面站点身份（commit `d6b767c`）

- **判定**：**APPROVE**
- **审查对象**：`origin/card/A5c-desktop-ua` @ `d6b767c23cc3ef90f43bc3cd5a7a08973e135263`（基于 `origin/main` @ `c6aba62`）
- **任务卡**：`/home/mir/Documents/hipda-next.wt/_cards/A5c.md`
- **实现者报告**：`/home/mir/Documents/hipda-next.wt/_reports/A5c.md`（结论均已通过 `git diff` / `git show` / `javap` 独立核对源码与字节码）
- **CI 核验**：Run [`45587005327`](https://github.com/mailwmj/hipda-next/actions/runs/45587005327)，Job `Build, Lint & Unit Tests`（ID `139111050089`）`success`（6m20s，包含 `Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 及 Debug/Release APK 构建）。
- **只读与边界核验**：
  - 变更范围共 12 个文件（`+613 / -85`）：5 个生产/调试代码文件、1 个设计文档（仅 `docs/design/tech-plan.md:60` 一行）、6 个测试文件。
  - 未触碰 `WebViewCookieBridge.kt`、`WebViewCookieAccess.kt`、`SiteAccessGateway.kt`、`WebViewChallengeSolver.kt`、`ComposeChallengeInteractionPresenter.kt`、`HttpClientModule.kt`、`TransportInterceptor.kt`。
  - `BrowserHeaderInterceptor.kt:10-25` 原本仅设置 `User-Agent`、`Accept`、`Accept-Language`，未发送 `Sec-CH-UA-*`；按卡片“不发则不新增”保持原样，且单测已锁死不发送 `Sec-CH-UA*`。
  - 单测均基于内存替身与本地 `MockWebServer`，零真实站点网络访问；实现者本地编译命令遵循 `docs/agents/DISPATCH.md` §2（`flock` + 内存/worker 限制 + `--stop`）。

---

## 一、阻断项

**无（0 项）**。对照 `docs/agents/DISPATCH.md` §4 全部阻断标准逐项核查，未发现可导致编译/测试失败、解析回归、登录/Cookie/GBK/Cloudflare 验证通道失效、界面崩溃或违规访问站点的缺陷。

---

## 二、重点审查详情

### 1. 桌面 UA 生成规则、禁止词剔除与异常回退（重点 1）

- **实现位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt:21-31`
  - `hipda/src/main/java/net/jejer/hipda/data/network/webview/WebViewUserAgentProvider.kt:11-45`
- **核查结论**：
  1. **模板重建消除全部移动端/WebView 特征词**：`normalizeWebViewUserAgent(raw)` 不再对原始 WebView UA 做易受 OEM 定制 token 干扰的黑名单删词，而是仅通过 `extractChromeMajorVersion(raw)` 提取正整数 Chrome 主版本号后，由 `buildDesktopChromeUserAgent(major)` 固定拼装为：
     `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<major>.0.0.0 Safari/537.36`
     输出在结构上**绝对不含** `Android`（4d4y.com 手机版模板触发词）、`Mobile`、`; wv`、`Version/4.0` 或 `Mobile Safari`。
  2. **Chrome 主版本号提取边界**：
     - 正则 `Regex("""(?:^|\s)Chrome/(\d+)(?:\.\d+)*(?=\s|$)""", RegexOption.IGNORE_CASE)`（`WebViewUserAgentProvider.kt:11`）：
       - 前边界 `(?:^|\s)` 排除 `HeadlessChrome/139.0.0.0`、`EdgChrome/` 等前缀粘连 token；
       - 后边界 `(?=\s|$)` 排除 `Chrome/134beta` 等非纯数字版本段；
       - `toIntOrNull()?.takeIf { it > 0 }?.toString()`（第 20 行）过滤 `Chrome/0.0.0.0` 及 `Int` 溢出异常值，并自动规范化潜在前导零（如 `0131` -> `"131"`）。
     - 兼容标准 Android 10–14+ 四段版本号（`Chrome/131.0.6778.200`）、Reduced UA（`Android 10; K; wv` + `Chrome/134.0.0.0`）、折叠屏/平板无 `Mobile` UA、国产 ROM 尾缀自定义浏览器 token（如末尾带 `HuaweiBrowser/...` 或 `MiuiBrowser/...` 时 `Chrome/...` 两侧仍为空格，可正常提取主版本），以及已转换桌面 UA 的幂等输入。
  3. **异常输入与默认回退**：
     - 空串、纯空白、仅 `; wv Version/4.0`、无 `Chrome/`（如 Firefox/Gecko 或纯 Safari）、`HeadlessChrome`、`Chrome/0.0.0.0`、`Chrome/134beta` 均返回 `null` 并回退至 `DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA`（`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36`）。
     - `WebViewUserAgentProvider`（第 52-70 行）保留 `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)` 与 `try { rawUserAgentSupplier() } catch (_: Throwable) { "" }`：当设备缺失 WebView 包或 native 库链接失败抛出 `Error`/`RuntimeException` 时，安全回退默认桌面 UA 并缓存，进程内不崩且不重复触发失败 IPC。

### 2. 三处 UA 完全一致、设置时序与全仓 WebView 排查（重点 2）

- **三处共享同一字符串链路核查**：
  1. **数据源单例**：`UserAgentModule.bindUserAgentProvider`（`UserAgentProvider.kt:39-40`）将 `@Singleton WebViewUserAgentProvider` 绑定为全进程唯一的 `UserAgentProvider`。
  2. **挑战页 WebView（A5b 静默求解 + A6 交互面板）**：
     - `WebViewChallengeSolver.solve`（`WebViewChallengeSolver.kt:131-134`）读取 `userAgentProvider.userAgent()` 传入 `webViewHost.acquire(triggerUrl, userAgent)`；
     - `ChallengeActivity.attachFromIntent` -> `createConfiguredWebView`（`ChallengeActivity.kt:132, 185-204`）在主线程同步调用 `applyUnifiedDesktopUserAgent(this, userAgent)`，随后才执行 `session.completeAttach(handle)`（第 177 行）；
     - `WebViewChallengeSolver.runWithHandle` 在 `acquire` 返回后才调用 `handle.installChallengeMessageListener()` 与 `handle.loadUrl(triggerUrl.toString())`（`WebViewChallengeSolver.kt:206-207`）；
     - A6 `ComposeChallengeInteractionPresenter`（`ComposeChallengeInteractionPresenter.kt:96-105`）直接复用同一 `binding.webView` 实例（不重建、不重设 UA，刷新走 `reload()`）。
     - **结论**：UA 与 `UserAgentMetadata` 严格在首次 `loadUrl` 前设置完毕。
  3. **调试登录 WebView（B1b `src/debug`）**：
     - `SampleExportActivity`（`SampleExportActivity.kt:218-223, 264`）通过 Hilt `@EntryPoint` 从 `SingletonComponent` 取出同一个 `userAgentProvider()` 传给 `SampleLoginWebViewOverlay`；
     - `SampleLoginWebViewOverlay`（`SampleLoginWebView.kt:111-116`）先同步执行 `configureSampleLoginWebView(...)`（内部第 55 行调用 `applyUnifiedDesktopUserAgent(this, userAgentProvider)`），链式返回后再调用 `.apply { loadUrl(SampleExportConstants.LOGIN_URL_STRING) }`。
     - **结论**：UA 与 `UserAgentMetadata` 严格在 `loadUrl` 前设置完毕。
  4. **OkHttp 网络层（含主客户端、恢复客户端与 Cronet 传输层）**：
     - `HttpClientModule.provideOkHttpClient`（`HttpClientModule.kt:40-56`）注入同一个 `userAgentProvider` 构造 `BrowserHeaderInterceptor(userAgentProvider)`，在每次请求及重定向每一跳强制覆盖 `.header("User-Agent", userAgentProvider.userAgent())`；
     - `@RecoveryHttpClient`（`HttpClientModule.kt:60-67`）通过 `primaryClient.newBuilder()` 完整继承同一拦截器链；
     - `TransportInterceptor`（`TransportInterceptor.kt:95-101`）未在 `CronetEngine.Builder` 上覆盖 UA，`cronet-okhttp` 直接透传上游 `BrowserHeaderInterceptor` 已写入的 `User-Agent` 请求头。
- **全仓遗漏点扫描（基于 `git grep` @ `d6b767c`）**：
  - `hipda/src/main`、`hipda/src/debug`、`core-*` 代码与全部 XML 资源中，应用内 `WebView` 实例化点**仅有两处**：`ChallengeActivity.kt:123`（`WebView(this)`）与 `SampleLoginWebView.kt:113`（`WebView(context)`），均已接入 `applyUnifiedDesktopUserAgent`；`ThreadDetailFragment.java:421` 为外部 `CustomTabsIntent`，非应用内 `WebView`。
  - `hipda/src/main` 与 `hipda/src/debug` 中 `Mobile Safari` 字面量扫描结果为 **0 处**；`Mozilla/5.0` 字面量仅有 `UserAgentProvider.kt:29` 与 `WebViewUserAgentProvider.kt:28` 两处桌面模板（旧版待删 Java 代码 `OkHttpHelper.java` / `AvatarStreamFetcher.java` / `ImageStreamFetcher.java` 用 `HiUtils.getUserAgent()`，不在新网络栈与本卡范围内）。

### 3. `setUserAgentMetadata` 字段一致性与 `androidx.webkit:1.18.0` 字节码核验（重点 3）

通过 `javap -c -p` 反编译本地缓存 `androidx.webkit:webkit:1.18.0`（`UserAgentMetadata`、`UserAgentMetadata$Builder`、`BrandVersion$Builder`、`UserAgentMetadataInternal`、`WebSettingsCompat`、`WebViewFeatureInternal$2`），对照 `WebViewUserAgentConfig.kt` 核验如下：

1. **品牌与版本一致性（`WebViewUserAgentConfig.kt:26-46`）**：
   - 从传入的 `effectiveUa` 提取 `majorVersion`（空或异常时回退 `"131"`），生成 `fullVersion = "$majorVersion.0.0.0"`，即使 `WebViewUserAgentProvider` 走默认回退路径，`UserAgentMetadata` 的版本号也与实际设置的 `userAgentString` 保持 100% 同步。
   - 显式设置 `Chromium`（`majorVersion` / `fullVersion`）、`Google Chrome`（`majorVersion` / `fullVersion`）、`Not_A Brand`（`"24"` / `"24.0.0.0"`）三项非空品牌，符合 `BrandVersion.Builder` 非空校验（空串会抛 `IllegalArgumentException`），且**彻底替换掉系统 WebView 默认含 `"Android WebView"` 的品牌列表**（字节码 `UserAgentMetadataInternal.getBrandVersionArray` 显示若传空列表会传 `null` 导致 Chromium 回退默认 `"Android WebView"` 品牌）。
2. **平台、移动标记与高熵字段抹除（`WebViewUserAgentConfig.kt:48-58`）**：
   - `platform = "Linux"`（非空，通过 `Builder.setPlatform` 校验）→ `Sec-CH-UA-Platform: "Linux"`、`navigator.userAgentData.platform === "Linux"`；
   - `mobile = false`（覆盖 `Builder` 默认值 `mMobile = true`）→ `Sec-CH-UA-Mobile: ?0`、`navigator.userAgentData.mobile === false`；
   - `architecture = "x86"`、`bitness = 64`、`wow64 = false` → 与 UA 中 `X11; Linux x86_64` 严格遵循 Chromium UA-CH 规范对齐；
   - `platformVersion = ""`、`model = ""` → 字节码确认 `setPlatformVersion`/`setModel` 接受空串 `""`，显式置空可防止传 `null` 时 Chromium `AwSettings` 回退暴露真机 Android 版本号与机型名（如 `Pixel 8`）。
3. **跨 WebView 版本 Feature Gate 安全性（`WebViewUserAgentConfig.kt:59-65, 106-142`）**：
   - 字节码确认：`WebSettingsCompat.setUserAgentMetadata` 在 `USER_AGENT_METADATA` 不支持（WebView < 114）时抛 `UnsupportedOperationException`；而 `webkit 1.18.0` 的 `UserAgentMetadata.Builder.setFormFactors` 内部会检查 `WebViewFeatureInternal.USER_AGENT_METADATA_FORM_FACTORS`（`WebViewFeatureInternal$2` 要求已加载 WebView `versionCode >= 636700000L`，即 WebView 124+），在 WebView 114–123 上直接调用 `setFormFactors` 也会抛 `UnsupportedOperationException`！
   - `WebViewUserAgentConfig.kt` 对两层特性分别做了前置 `WebViewFeature.isFeatureSupported(...)` 判断并包裹 `runCatching`：
     - WebView < 114：仅写 `settings.userAgentString`，不调 `setUserAgentMetadata`，不崩；
     - WebView 114–123：跳过 `setFormFactors`，正常写入 `Linux` / `mobile=false` / 桌面品牌列表；
     - WebView 124+：额外写入 `FORM_FACTOR_DESKTOP`（`"Desktop"`），避免发出 `Sec-CH-UA-Form-Factors: "Mobile"`。
   - 调用顺序为先 `settings.userAgentString = effectiveUa` 再 `setUserAgentMetadata`（第 124-136 行），且对空白 `userAgent` 入参兜底 `DEFAULT_DESKTOP_CHROME_UA`（避免向 `settings.userAgentString` 赋空串导致 WebView 重置回系统移动 UA）。

### 4. 状态机/网关/Cookie 桥无误改与测试突变有效性（重点 4）

- **无误改确认**：`git diff c6aba62..d6b767c` 确认 Cookie 桥（`WebViewCookieBridge`）、网关（`SiteAccessGateway`）、求解状态机（`WebViewChallengeSolver`）生产代码 0 行改动；`ChallengeActivity.kt` 与 `SampleLoginWebView.kt` 仅替换单行 UA 配置调用。
- **测试突变有效性核验**：
  - `WebViewUserAgentProviderTest`（12 个测试）：精确断言完整桌面 UA 字符串并显式断言不含 `Android`/`Mobile`/`; wv`/`Version/4.0`；覆盖多主版本（131–138）、大小写混合、幂等、7 类异常/非 Chrome 输入回退、并发单次求值与异常缓存。改坏模板或写死主版本均会立即变红。
  - `WebViewDesktopIdentityTest`（5 个测试）：在 Robolectric 下驱动真实 `AndroidChallengeWebViewHost` + `ChallengeActivity`、`configureSampleLoginWebView`、`HttpClientModule.provideOkHttpClient` + `MockWebServer` 闭环验证三处 UA 完全相等且无 `Sec-CH-UA-*` 请求头；用 `FakeWebSettings` 断言 `platform="Linux"`、`isMobile=false`、品牌版本随 `136`/`141` 动态变化及异常不崩；用源码扫描锁死 `src/main`+`src/debug` 零 `Mobile Safari` 及两处 WebView 必走 `applyUnifiedDesktopUserAgent`。
  - 同时也识别出两处细微的测试突变盲区（生产代码本身正确，归入下方非阻断建议 S1、S2）。

---

## 三、非阻断建议项（可供后续顺手优化）

1. **消除 `formFactors` 重复静态特性检查并补齐单测断言（`WebViewUserAgentConfig.kt:60-64`、`WebViewDesktopIdentityTest.kt:295`）**：`applyUnifiedDesktopUserAgent`（第 126 行）已通过 `metadataBridge.isFormFactorsSupported()` 计算并传入 `includeDesktopFormFactor`，但 `buildDesktopUserAgentMetadata` 第 61 行又再次静态调用 `WebViewFeature.isFeatureSupported(USER_AGENT_METADATA_FORM_FACTORS)`，且 `RecordingMetadataBridge` 的 `formFactorsSupported` 参数在单测中始终为 `false`；若未来误改 `FORM_FACTOR_DESKTOP` 常量单测不会感知。建议由 `metadataBridge` 统一承载该判断或抽出纯数据构建关注点。
2. **收紧品牌列表精确长度断言与全目录裸写 `userAgentString` 扫描（`WebViewDesktopIdentityTest.kt:189-201, 279-290`）**：(a) 对 `metadata.brandVersionList` 增加 `assertEquals(3, metadata.brandVersionList.size)`（或断言不含 `"Android WebView"`），防止未来误追加移动 WebView 品牌时漏检；(b) 将“禁止绕过统一入口直接写 `userAgentString =`”的源码扫描从仅检查 `ChallengeActivity.kt` / `SampleLoginWebView.kt` 两个文件扩展到 `src/main` 与 `src/debug` 全部 `.kt`/`.java` 文件（仅豁免 `WebViewUserAgentConfig.kt`），防止后续新卡新增 WebView 时绕过统一入口。
3. **收窄 `buildDesktopChromeUserAgent` 可见性或校验入参（`WebViewUserAgentProvider.kt:27`）**：`buildDesktopChromeUserAgent(chromeMajorVersion: String)` 目前为 `public` 但未校验入参格式且无包外调用方，建议改为 `internal`（与 `extractChromeMajorVersion` 一致）或对非法入参回退 `DEFAULT_CHROME_MAJOR_VERSION`，从接口层锁死桌面 UA 格式不变量。
