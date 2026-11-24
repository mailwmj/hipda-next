# hipda-next（4d4y.com）Cloudflare 验证层设计方案

> 调研性质：只读调研与架构设计（未改动仓库代码）。
> 外部事实均附一手出处链接，无法从官方文档或公开代码核实的推断一律标明**【推测】**。

---

## 一、参考实现拆解：fluxdo 如何应对 Cloudflare

以下基于 `/tmp/hipda-research/fluxdo` 源码逐项核实：

### 1. 验证页检测规则

- **状态码范围**：同时拦截 HTTP `403` 与 `429`（Cloudflare 速率限制规则动作配置为 `managed_challenge` / `js_challenge` 时返回 `429` 而非 `403`），并跳过带 `skipCfChallenge` 标记的重试请求防递归（`lib/services/network/interceptors/cf_challenge_interceptor.dart:120-128`）。恢复层的 `RateLimitPolicy` 通过注入 `isChallengeResponse` 钩子把“挑战型 429”让渡给 CF 拦截器，只处理真限流（`lib/services/network/discourse_dio.dart:96-98`）。
- **响应头主信号（权威）**：优先检查 `cf-mitigated` 响应头是否包含 `challenge`（`lib/services/cf_challenge_service.dart:481-489`），**刻意不把 `server: cloudflare` 当作一票否决前置条件**，避免部分传输通道改写响应头导致漏判。
- **正文兜底规则**：若缺失 `cf-mitigated`，仅在 `content-type` 为空或包含 `text/html` 时检查正文（避免误伤 JSON/纯文本业务 403，`lib/services/cf_challenge_service.dart:491-501`）：
  - 命中 `cf_chl_opt` 即判定（`cf_challenge_service.dart:508`）；
  - 或同时命中 `challenge-platform` 与 `cloudflare`（`cf_challenge_service.dart:510-512`）；
  - 或同时命中 `Just a moment` 与 `cloudflare` / `cf-challenge`（`cf_challenge_service.dart:514-517`）。
- **WebView 页面内挑战态判定**：通过 `hasActiveCfChallenge` 检测 `cf-turnstile` / `challenge-running` / `challenge-stage` / `cf_chl_opt`（`cf_challenge_service.dart:523-528`），判断页面是否仍在盾页，或已跳转到达源站文档（`cf_challenge_service.dart:1980-1997`）。

### 2. 不可见 vs 可见 WebView 策略

- **四档渐进打扰等级**（`CfVerifyPresentation`：`hidden` / `pill` / `blocking` / `foreground`，`lib/services/cf_challenge_service.dart:51-67`）：
  - 同一轮共享验证的打扰等级“只升不降”（`raisePresentation`，`cf_challenge_service.dart:130-135, 624`）：例如后台静默过盾期间来了前台阻塞请求，立即升级展示。
  - 拦截器自动触发时，静默请求用 `pill`、用户等待中的请求用 `blocking`，**一律先在后台尝试**（`cf_challenge_interceptor.dart:214-222`）；Session Bootstrap 失败时用完全不可见的 `hidden`（`lib/services/browser_trust_coordinator.dart:474-481`）。
- **后台 WebView 渲染方式**：使用正常面板尺寸（非 `0x0`，避免风控异常），但通过 `Positioned` 放置在屏幕外负坐标（`cf_challenge_service.dart:2755-2805`），并套 `IgnorePointer` 避免劫持触摸（`cf_challenge_service.dart:2330-2331`）。
- **从后台升级到前台的触发条件**：
  1. 在 `AT_DOCUMENT_START` 注入 `window.addEventListener('message')`，监听 Turnstile iframe 发往宿主页的 `{ source: 'cloudflare-challenge', event: 'interactiveBegin' | 'interactiveTimeout' }`，一收到即刻通过 JS Handler `onChallengeInteractive` 触发升级，不必干等超时（`cf_challenge_service.dart:1050-1055, 1389-1399`）；
  2. 后台每秒轮询最多 8 秒（`_backgroundMaxCheckCount = 8`）仍未自动通过则超时升级（`cf_challenge_service.dart:939-940, 1474-1482`）。
  - 升级行为按等级分流：`hidden` 直接静默放弃且不计失败冷却；`pill` 露出悬浮胶囊提示用户点按（60 秒无人点按则放弃）；`blocking` 自动调用 `_promoteToForeground` 弹出模态验证面板（`cf_challenge_service.dart:1020-1047, 1705-1727`）。
- **视觉防闪烁**：验证页加载与跳转完成瞬间，使用 JS 全屏 DOM 遮罩 + Flutter 覆盖层双重兜底，遮住 `/challenge` 跳转后源站 404 的闪现（`cf_challenge_service.dart:1185-1387, 1539-1630, 2623-2639`）。

### 3. Cookie 同步机制

- **边界单向同步为主**：不在常态下高频双向同步，而是在过盾成功、登录收口、Session Bootstrap 等边界由 `BoundarySyncService.syncFromWebView` 加 `_syncLock` 串行执行 WebView → CookieJar 同步（`lib/services/network/cookie/boundary_sync_service.dart:22-32, 127-149`）。
- **Android 原生属性读取与线程隔离**：通过自有 MethodChannel `com.fluxdo/raw_cookie` 投递到独立 `HandlerThread` 执行，优先检测 `WebViewFeature.GET_COOKIE_INFO` 后调用 `CookieManagerCompat.getCookieInfo(mgr, url)` 拿完整 `Domain/Path/Expires/Secure/HttpOnly/SameSite/Partitioned` 属性，旧 WebView 退化为 `CookieManager.getCookie()`，避免主线程 IPC 引起滚动掉帧（`lib/services/network/cookie/strategy/android_cookie_strategy.dart:7-45`、`android/app/src/main/kotlin/com/github/lingyan000/fluxdo/MainActivity.kt:47, 743-852`）。
- **验证前清旧 + 备份恢复 + 新值筛选（应对 CHIPS 双变体）**：
  - 发起验证前先快照并备份 jar 和 WebView 中全部旧 `cf_clearance`，再删除两端旧 `cf_clearance`（否则 WebView 带旧值可能跳过盾页），验证失败时再把备份写回（`cf_challenge_service.dart:655-671, 834-838`）；
  - 过盾后从 WebView 读回所有 `cf_clearance` 候选值，剔除验证前快照 `_initialCfClearances`，只采纳本轮新值并以 `trusted=true` + `acceptValues` 原子替换 jar 内所有同名分区/非分区副本（`cf_challenge_service.dart:1059-1093, 1924-1952`、`boundary_sync_service.dart:388-402`）；
  - 双变体打分选优刻意不按 `value.length` 加分，同属性时按 `expiresDate` 最新者胜出（`boundary_sync_service.dart:516-600`）。
- **在位值粘性保护（`CfClearanceAuthority`）**：只要 jar 当前 `cf_clearance` 未过期、未进入 30 分钟换届窗且未被撞盾，普通同步（如后台续期或残余同步）不得用异值覆盖它；只有撞盾调用 `noteIncumbentChallenged`、临期、jar 为空或本轮验证确认新值才允许替换（`lib/services/cf_clearance_authority.dart:7-98`、`cf_challenge_interceptor.dart:161-166`）。
- **预热方向单向隔离**：打开 WebView 前 `WebViewCookiePriming.prime` 只把业务登录 Cookie 从 jar 灌入 WebView，**显式排除 `cf_clearance`**，保证 `cf_clearance` 仅从 WebView → Jar 单向流动（`lib/services/network/cookie/webview_cookie_priming.dart:108-115, 139-146`）。
- **后台主动续期**：登录且拿到 `data-sitekey` 后，非 Windows 平台由 `CfClearanceRefreshService` 维护一个持久 `HeadlessInAppWebView` 加载同源 Turnstile widget 持续续期并同步回 jar；Android 上检测到列表滚动时每 500ms 切 `pause()/resume()` 降低 WebView JS 对主线程的抢占（`lib/services/cf_clearance_refresh_service.dart:18-45, 231-353, 625-665`）。

### 4. UA 与浏览器特征头处理

- 启动期通过 `InAppWebViewController.getDefaultUserAgent()` 读取当前设备系统 WebView 真实 UA，保证 UA 版本与本机内核匹配（`lib/constants.dart:56-106`）。
- Android 清洗掉暴露 WebView 身份的 `; wv` 与 `Version/4.0`（`lib/constants.dart:181-196`）。
- 移动端将清洗后的同一 UA 同时写入 WebView 配置与 Dio 请求头（`lib/constants.dart:274-281`、`lib/services/webview_settings.dart:117, 155, 354`、`lib/services/network/interceptors/request_header_interceptor.dart:29`）。
- 由 Chrome 主版本号按 Chromium GREASE 规则推导出一致的低熵 Client Hints（`sec-ch-ua`、`sec-ch-ua-mobile`、`sec-ch-ua-platform`）随 Android 请求发送（`lib/constants.dart:162-174`、`lib/services/network/client_hints.dart:22-52`、`request_header_interceptor.dart:31-35`），XHR 请求同步补齐 `Origin`、`Referer` 与 `Sec-Fetch-Dest/Mode/Site`（`request_header_interceptor.dart:98-105`）。

### 5. 验证后使用的 HTTP 栈

**fluxdo 确实没有继续用默认非浏览器 TLS 栈硬扛，而是采用了三层网络栈架构**：

1. **常规栈（进程内 Rust 网络核心 `fluxdo_net`）**：基于 `rama` + `BoringSSL` + `QUIC` 编译成 `libfluxdo_net.so` 经 Dart FFI 驱动（`core/net_core/Cargo.toml:34-40`、`lib/services/network/core/net_core_http_adapter.dart:20-24`、`android/app/src/main/kotlin/com/github/lingyan000/fluxdo/NetCoreBridge.kt:14-29`）。启动后用隐藏 WebView 访问本地探针 `https://localhost:<port>/fluxdo-probe`（及 `/fluxdo-h3`）实时采样本机 WebView 的 TLS ClientHello、HTTP/2 与 HTTP/3 特征作模板，使原生出站指纹镜像当前 WebView（`lib/services/network/fingerprint/fingerprint_template_service.dart:14-193`、`core/net_core/src/browser_tls.rs:1-14`）。
2. **应急栈（Dart `IOHttpClientAdapter`）**：仅当 Rust 核心不可用时降级顶上，保持与 WebView 一致的系统代理出口（`lib/services/network/adapters/platform_adapter.dart:21-30, 118-162`）。
3. **会话级兼容栈（`WebViewHttpAdapter`）**：当原生栈在过盾后重试依然被 CF 拦截（403/429）时，弹窗询问用户是否在本次会话切入兼容模式，通过隐藏 WebView 内执行同源 `fetch()` 直接走真实浏览器网络栈（`cf_challenge_interceptor.dart:276-327`、`lib/services/network/adapters/webview_http_adapter.dart:26-34, 269-335`、`platform_adapter.dart:164-281`）。该适配器仅承接同源 API 与同源图片，明确不支持 `text/html` 主文档和跨域二进制流（`platform_adapter.dart:236-274`）。

### 6. 失败与重试流程

1. **流量冻结与并发合流**：验证进行期间（`isVerifying == true`），`RequestSchedulerInterceptor` 直接取消静默后台请求（防恢复瞬间突发 429），挂起用户请求等待 `waitUntilIdle()` 与 `waitForCompletionCookieSync()` 并把展示抬高到 `blocking`（`lib/services/network/interceptors/request_scheduler_interceptor.dart:183-207, 378-385`）。同时撞盾的多个请求在 `CfChallengeService.verify` 合流到同一个 `_verifyCompleter` 列表等待结果（`cf_challenge_service.dart:206, 618-629, 811-815`）。
2. **单次共享同步 + 单次防环重试**：验证通过后所有等待者复用 `_syncCookiesOnce()`（`cf_challenge_interceptor.dart:49-83, 228-230`），各自设置 `skipCfChallenge = true` 与 `skipCfBlock = true`，清除旧 `Cookie` 头并从 jar 重新拼装后再调用 `dio.fetch(retryOptions)`（`cf_challenge_interceptor.dart:99-109, 234-266`）。
3. **重试仍被拒的处置**：若非静默且支持 WebView 适配器，询问切入会期兼容模式重放一次（失败则立即回滚 `disableSessionFallback()` 防锁死，`cf_challenge_interceptor.dart:279-327`）；否则立即触发 **60 秒无效凭证冷却**（`startIneffectiveClearanceCooldown`，`cf_challenge_service.dart:231, 318-326`、`cf_challenge_interceptor.dart:342-344`）。
4. **取消/超时与轮次去重冷却**：验证取消或超时后恢复旧 Cookie 备份，按验证轮次 `_verifyRound` 去重记失败，连续失败 3 轮进入 **30 秒冷却**（`cf_challenge_service.dart:216-232, 292-312, 834-854`）；用户主动点“手动验证”时经 `showManualVerifyNow` 重置冷却（`cf_challenge_service.dart:860-866`）。

---

## 二、`cf_clearance` 与 UA、IP、TLS 指纹的绑定关系

下面严格区分**官方公开文档核实**、**开源工程实证**与**未公开细节推测**：

| 维度 | 结论与性质 | 依据与来源 |
| --- | --- | --- |
| **基本定位与有效期** | **【核实-官方】** `cf_clearance` 包含两层状态：Challenge clearance（默认有效 30 分钟，建议 15–45 分钟；XHR 校验额外宽限 1 小时；不适用于 Rate Limiting 规则）与 Precursor clearance（Armed 模式下可按后续会话行为中途降级或作废）。Cookie 绑定到签发时的访客与设备，防止跨机器复用。 | [Cloudflare Clearance Docs](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/)；[Challenge Passage Docs](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/challenge-passage/) |
| **IP 地址绑定** | **【核实-官方】** 挑战下发与求解若来自不同 IP，求解无效并会触发挑战循环。因此移动端 Wi-Fi/蜂窝切换、开关系统代理/VPN 导致出口 IP 变化后，原有 `cf_clearance` 会失效或触发重新验证。 | [How Challenges Work - Limitations](https://developers.cloudflare.com/cloudflare-challenges/concepts/how-challenges-work/) |
| **User-Agent 绑定** | **【核实-官方 + 开源实证】** Cloudflare 官方移动端 Turnstile 文档明确指出“会话中修改 User-Agent 会导致验证失败”；FlareSolverr 官方文档明确指出复用 `cf_clearance` 时必须携带与求解浏览器完全一致的 UA，否则重新触发挑战。 | [Turnstile Mobile Implementation](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)；[FlareSolverr README](https://github.com/FlareSolverr/FlareSolverr/blob/master/README.md) |
| **CHIPS 分区属性** | **【核实-官方 + 开源实证】** Cloudflare 官方说明 Bot/JSD 场景下 `cf_clearance` 带 `SameSite=None; Secure; Partitioned`。fluxdo 实测同域名下非分区（Challenge Page）与分区（Turnstile/JSD）两枚同名 Cookie 可并存，读取不当会导致旧值反复顶替新值。 | [Cloudflare Cookies Docs](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/cloudflare-cookies/)；`fluxdo` `boundary_sync_service.dart:716-730` |
| **TLS / JA3 / JA4 指纹计算** | **【核实-官方】** Cloudflare 在 TLS 握手阶段按 `ClientHello` 计算 JA3/JA4 及 JA4 Signals（含 `browser_ratio_1h`、`h2h3_ratio_1h` 等）。自定义规则/Workers 直接读取 JA3/JA4 字段仅限 Enterprise Bot Management，但官方注明 Free/Pro 的 Bot Fight Mode / Super Bot Fight Mode 使用相同底层 Bot 检测技术。 | [JA3/JA4 Fingerprint Docs](https://developers.cloudflare.com/bots/additional-configurations/ja3-ja4-fingerprint/)；[Bot Fight Mode Docs](https://developers.cloudflare.com/bots/get-started/bot-fight-mode/) |
| **WebView 过盾后同步给纯 OkHttp（同 UA、同 IP）能否继续访问？TLS/JA3/JA4 不同会不会仍 403？** | **【核实-开源实证 + 推测】** Cloudflare **未公开**验证 `cf_clearance` 时内部对 JA3/JA4 与 HTTP/2 指纹的精确校验公式，以下分层结论结合开源工程实证与**【推测】**：<br>1. **多数普通 WAF Managed Challenge 站点（如 Mihon/Tachiyomi 覆盖的大量站点）**：Android 平台 OkHttp 默认走系统 Conscrypt（底层也是 BoringSSL），同步 `CookieManager` 的 `cf_clearance` 且严格对齐 WebView UA 后**通常可以继续访问**（来源：[Mihon `CloudflareInterceptor.kt`](https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt)）。<br>2. **高敏 Bot 防护 / TLS-UA 一致性校验 / HTTP/2 指纹敏感站点**：Conscrypt 与 Chromium WebView（完整 BoringSSL + Chrome GREASE 布局 + ALPS + 证书压缩 + Chromium H2 SETTINGS 帧 + HTTP/3 QUIC）在 TLS/H2 层仍有明显差异，存在**过盾后原生请求依然 403**的真实案例——`fluxdo` 在生产中持续观测到“验证入口无盾但原生重试仍被 CF 拒绝”（`cf_challenge_interceptor.dart:276-343`），为此构建了镜像 WebView 指纹的 Rust 栈与 `WebViewHttpAdapter` 兜底；Mihon 社区也长期存在过盾后 403 循环报告。<br>3. **【推测】针对 4d4y.com**：本次实测观察到其开启了 Managed Challenge 与 JavaScript Detections（`scripts/jsd/main.js`），仅同步 Cookie 给纯 OkHttp 在宽松规则下**可能**可用，但在规则收紧或低声誉 IP 下有明显 403 循环风险；改用与 Android WebView 同源的 Chromium 网络栈（**Cronet / 平台 `HttpEngine`**）可将 TLS/H2/H3 传输层差异降到极低。 | [Mihon `CloudflareInterceptor.kt`](https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt)；`fluxdo` `cf_challenge_interceptor.dart:276-343`；[Android Conscrypt](https://developer.android.com/privacy-and-security/cryptography) |

---

## 三、可选方案及取舍

围绕新架构（Kotlin + 协程 + OkHttp 5 + Compose + Coil 3，面向中国大陆含无 GMS 国行设备在内的 Android 用户），对比 4 类方案：

| 对比维度 | (a) 纯 OkHttp 5 + WebView 过盾同步 Cookie | (b) OkHttp 5 + Cronet / 平台 `HttpEngine` 传输层 + WebView 过盾 | (c) 页面请求全部走 WebView（隐藏 WebView `fetch()` 或整页套壳） | (d) 其他（d1: Rust/BoringSSL 自研镜像栈；d2: 服务端中转） |
| --- | --- | --- | --- | --- |
| **最低 API** | API 21（OkHttp 5 基线）；`CookieManagerCompat.getCookieInfo` 需 `androidx.webkit` 运行时检查 `GET_COOKIE_INFO`，低版本退化 `getCookie()` | • `android.net.http.HttpEngine`：**API 34**（Android 14）及 **Android S Extensions 7**（Android 12–13 获 Mainline 更新设备，来源：[HttpEngine API Docs](https://developer.android.com/reference/android/net/http/HttpEngine)）<br>• `com.google.net.cronet:cronet-okhttp:0.1.2`（2026-10 已兼容 OkHttp 5+）：**API 24**（来源：[cronet-okhttp v0.1.2 Release](https://github.com/google/cronet-transport-for-okhttp/releases/tag/v0.1.2)）<br>• 三级回退（平台 `HttpEngine` → `play-services-cronet` → 纯 OkHttp 5 或 `cronet-bundled`）可平滑覆盖 API 24+ | API 21+（二进制流零拷贝 `WEB_MESSAGE_ARRAY_BUFFER` 需较新 WebView，否则退化 Base64，见 `fluxdo` `webview_http_adapter.dart:78-83`） | d1: API 21–24（需 NDK/Rust 交叉编译）；d2: API 21+（但架构不合规） |
| **包体积增量** | **极小（~0.3 MB）**：仅 `androidx.webkit`（AAR 0.30 MB） | • **零原生 `.so` 方案**（`cronet-okhttp:0.1.2` 0.10 MB + `org.chromium.net:cronet:500.1.0` 含 `httpengine-native-provider` + `play-services-cronet:18.1.1` 0.09 MB）：经 R8 裁剪后增量 **~0.5–1 MB**<br>• **全内置方案**（加 `org.chromium.net:cronet-bundled:500.1.0`，官方已用其替代弃用的 `cronet-embedded`）：多 ABI 完整 AAR **13.31 MB**，AAB/ABI 拆包后单 `arm64-v8a` 约 **3–5 MB**（来源：Google Maven POM/HEAD 实测） | **0 MB**（复用系统 WebView） | d1: **大（~5–12 MB** 多 ABI Rust+BoringSSL `.so`）；d2: 0 MB（但需运维中转服务器） |
| **GBK 表单提交与响应解码** | **完全原生无损**：OkHttp 5 支持自定义 GBK `FormBody`/字节流 `RequestBody` 与 Multipart 上传，响应按 `Content-Type` / GBK 直接解码 | **完全无损，但有 2 处必做适配**：`RequestResponseConverter.java:88-124` 原样转发字节流与 `Content-Type`，不影响 GBK；但 `CronetInterceptor` 绕过 OkHttp 核心 `BridgeInterceptor` 与自动重定向（来源：[cronet-okhttp README](https://github.com/google/cronet-transport-for-okhttp/blob/master/README.md)），**必须在它之前放自定义应用层 CookieInterceptor，并设 `RedirectStrategy.withoutRedirects()` 手动跟 302**（以保留 `response.request.url` 获取发帖后 `tid` 及每跳 Cookie） | **坑极多**：<br>1. `fetch()` 的 `URLSearchParams`/`FormData` 固定按 UTF-8 编码，无法直接发 GBK 表单，必须在原生手工 GBK 百分号编码再过桥；<br>2. 不能调 `response.text()`（UTF-8 解码会把 GBK 中文全部变乱码），必须用 `arrayBuffer()` 跨进程传字节流再按 GBK 解码；<br>3. JS 禁止改写 `Referer`/`Cookie`/`User-Agent` 等 Forbidden Headers，`redirect: 'manual'` 返回 `opaqueredirect` 拿不到 302 `Location` | d1: 完全可控；d2: 编码可控，但密码和发帖内容明文经中转站存在严重安全隐私风险 |
| **图片加载（含 `img02.4d4y.com` 与同域附件）** | **无缝统一**：Coil 3 (`coil-network-okhttp`) 直接共用同一 `OkHttpClient`（同 Cookie、同 UA、同连接池） | **无缝统一**：Coil 3 接受任意 OkHttp `Call.Factory`，自动共享 Chromium 传输层（含 HTTP/3 弱网收益）与同一套 Cookie/UA | **体验与兼容性差**：同源 `fetch()` 受 CORS 限制无法读无 CORS 头的第三方外链帖内图片字节；大量图片走 JS Bridge + WebView 进程会导致主线程卡顿与内存暴涨（`fluxdo` 明确只允许同源图走 WebView，`platform_adapter.dart:240-247`） | d1: 可通过桥接统一，但改造成本高；d2: 带宽成本不可控 |
| **中国大陆无 GMS 机型兼容** | **100% 兼容** | • Android 14+（API 34+）平台自带 `android.net.http.HttpEngine`，**不依赖 GMS**（【推测】极少数深度魔改 ROM 若裁切异常，可运行时 `try/catch` 回退）；<br>• Android 12–13 无 GMS 机可能未获 Mainline S Ext 7，`play-services-cronet` 也不可用，需回退纯 OkHttp 5 或按需引入 `cronet-bundled` | **100% 兼容**（依赖系统 Android WebView） | d1: 100% 兼容；d2: 易遭集中封 IP |
| **抗 CF 稳定性与维护成本** | 维护成本最低；但 TLS/H2 指纹与 WebView 不一致，严格规则下有 403 循环风险 | **综合最优**：与 WebView 同源 Chromium 网络栈 + 标准 OkHttp 5 API 编程模型，无需自维 C/Rust 栈；残余差异仅在于平台 `HttpEngine` 与系统 WebView 的 Chromium 小版本可能略微不同 | 过盾后 100% 同指纹，但 WebView 进程崩溃（`onRenderProcessGone`）、内存占用和 GBK/重定向桥接复杂度极高，仅适合极端兜底 | d1: 极高维护成本（单 Android 应用属于过度工程）；d2: 不可行（共享 IP 必触发 CF 封禁 + 凭据泄露风险） |

**取舍结论**：**选 (b) 为主力方案，并以 (a) 作为旧机无 GMS 时的无缝底层回退**。原因：`com.google.net.cronet:cronet-okhttp:0.1.2` 已原生兼容 OkHttp 5+ 且底层升级到 `org.chromium.net:cronet:500.1.0`（自带 `httpengine-native-provider`），以 **<1 MB** 的增量即可让主流 Android 14+（含无 GMS 国行机）与 GMS 手机获得 Chromium 同源 TLS/H2/H3 传输层，同时完整保留 OkHttp 5 + Coil 3 + GBK 编解码 + `MockWebServer3` 离线单测的全部工程红利；若上线灰度发现 Android 7–13 无 GMS 旧机仍高频出现“过盾后 OkHttp 403”，再低成本切入 `cronet-bundled`（单 `arm64-v8a` ~3–5 MB）即可，架构零返工。

---

## 四、4d4y.com 只读 GET 实测记录与分析

### 1. 实测明细（严格控制在 10 次只读 GET 预算内，禁用自动重定向，不登录、不提交表单）

说明：移动版 Chrome UA 为 `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36`；“完整浏览器头”包含 `Accept`（HTML/AVIF/WebP）、`Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`、`Sec-CH-UA` / `Sec-CH-UA-Mobile: ?1` / `Sec-CH-UA-Platform: "Android"`、`Sec-Fetch-Dest: document`、`Sec-Fetch-Mode: navigate`、`Sec-Fetch-Site: none`、`Sec-Fetch-User: ?1`、`Upgrade-Insecure-Requests: 1`。

| # | 目标 URL | 协议 | UA | 附加头 | 状态码 | `cf-mitigated` | `Content-Type` | 响应正文关键特征 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `https://www.4d4y.com/forum/` | HTTP/2 | curl 默认 UA | 无 | **403** | `challenge` | `text/html; charset=UTF-8` | `Just a moment...`，含 `window._cf_chl_opt`（`cType: 'managed'`），`server: cloudflare` |
| 2 | `https://www.4d4y.com/forum/` | HTTP/2 | `net.jejer.hipda  2.8.19`（现网旧版 UA） | 无 | **403** | `challenge` | `text/html; charset=UTF-8` | 同上（Managed Challenge） |
| 3 | `https://www.4d4y.com/forum/` | HTTP/2 | 移动版 Chrome 131 UA | 无 | **403** | `challenge` | `text/html; charset=UTF-8` | 同上（Managed Challenge） |
| 4 | `https://www.4d4y.com/forum/` | HTTP/2 | 移动版 Chrome 131 UA | 完整浏览器头 | **403** | `challenge` | `text/html; charset=UTF-8` | 同上（Managed Challenge） |
| 5 | `https://www.4d4y.com/forum/` | HTTP/1.1 | 移动版 Chrome 131 UA | 完整浏览器头 | **403** | `challenge` | `text/html; charset=UTF-8` | 同上（Managed Challenge） |
| 6 | `https://www.4d4y.com/forum/logging.php?action=login` | HTTP/2 | 移动版 Chrome 131 UA | 完整浏览器头 | **200** | 无 | `text/html; charset=GBK` | 真实 Discuz! 登录页 HTML，下发 `cdb_sid`，**页尾注入 CF JSD 脚本**（`/cdn-cgi/challenge-platform/scripts/jsd/main.js`、`window.__CF$cv$params`） |
| 7 | `https://img02.4d4y.com/forum/images/default/notice_newpm.gif` | HTTP/2 | curl 默认 UA | 无 | **200** | 无 | `image/gif` | 真实 `GIF89a`（367 B），`server: cloudflare`，`cf-cache-status: HIT` |
| 8 | `https://www.4d4y.com/forum/logging.php?action=login` | HTTP/2 | curl 默认 UA | 无 | **500** | 无 | `text/html` | 源站 `<center>nginx</center>` 500 错误页，**同样被 CF 注入了 JSD 脚本**（`scripts/jsd/main.js`） |
| 9 | `https://www.4d4y.com/forum/forumdisplay.php?fid=2` | HTTP/2 | 移动版 Chrome 131 UA | 完整浏览器头 | **200** | 无 | `text/html; charset=GBK` | 真实 Discuz! 版块页（提示游客“无权进行当前操作”），下发 `cdb_sid`，**页尾注入 CF JSD 脚本** |
| 10 | `https://www.4d4y.com/forum/forumdisplay.php?fid=2` | HTTP/2 | `net.jejer.hipda  2.8.19` | 无 | **200** | 无 | `text/html; charset=GBK` | 真实 Discuz! 7.2 版块页，下发 `cdb_sid`，**页尾注入 CF JSD 脚本** |

### 2. 实测分析与判断

1. **【核实】`/forum/` 的验证并非简单 UA 过滤，仅伪造 UA 和浏览器请求头无法通过**：测试 #1–#5 无论使用 curl 默认 UA、旧客户端 UA、移动 Chrome UA，还是补齐全部 `Sec-CH-UA*`/`Sec-Fetch-*` 头并在 HTTP/2 与 HTTP/1.1 间切换，全部返回 `403` + `cf-mitigated: challenge`（`cType: 'managed'`），且响应头带有 `accept-ch`/`critical-ch` 与 `alt-svc: h3=":443"`。
2. **【核实】全站 HTML 页面启用了 Cloudflare JavaScript Detections（JSD）**：#6、#8、#9、#10 的 HTML 尾部均注入了 `/cdn-cgi/challenge-platform/scripts/jsd/main.js`（`window.__CF$cv$params`）。据 Cloudflare 官方文档，Bot Fight Mode 自动强制开启 JSD，Super Bot Fight Mode / Bot Management 可选开启 JSD（[JavaScript Detections Docs](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/javascript-detections/)）。
3. **【核实 + 推测】防护存在路径与主机差异，且暴露出两处关键工程约束**：
   - 本次实测中 `/forum/` 必出 Managed Challenge，而 `forumdisplay.php?fid=2`（#9、#10）和 `logging.php`（#6）直接返回 200 GBK 正文，独立图片域名 `img02.4d4y.com`（#7）返回 200 缓存命中。**【推测】** 站点可能对 `/forum/` 入口配置了 WAF Managed Challenge 规则，或动态依据路径/请求特征与 IP 声誉触发挑战；且站方随时可将规则扩展到 `viewthread.php`、`post.php` 或登录接口，绝不能因为单次探测部分路径 200 就放弃 CF 拦截链路。
   - 测试 #8 显示默认 `curl` UA 访问 `logging.php` 返回源站 nginx 500（非 CF 403），而 #6 使用移动 Chrome UA + 完整头返回 200。**【推测】** 源站或中间层对异常/工具 UA 有兼容问题或阻断，因此新客户端**不应再沿用自定义非浏览器 UA**（如 `net.jejer.hipda ...` 或默认 `okhttp/5.x`），应统一采用本机 WebView 清洗后的真实移动浏览器 UA。
   - **重要检测陷阱（实测直接发现）**：正常的 HTTP 200 页面（#6、#9、#10）和 500 页面（#8）正文里都含有 `challenge-platform`（来自无害的 `scripts/jsd/main.js`）！如果检测逻辑仅匹配正文中的 `challenge-platform` 而不限制状态码或排除 `scripts/jsd/`，会造成严重的误判死循环。

---

## 五、推荐方案设计

### 1. 总体架构与拦截器链顺序

新网络层对外完全暴露标准 **OkHttp 5 + Kotlin 协程** 接口，内部按以下固定顺序组装应用拦截器（注意：若启用 `CronetInterceptor`，它必须作为**最后一个应用拦截器**，因为其会接管底层传输并跳过 OkHttp 核心 `BridgeInterceptor` / `RetryAndFollowUpInterceptor`）：

```text
业务请求 (Kotlin suspend / Coil 3)
  │
  ▼
1. SiteAccessInterceptor        ← 统一入口：验证进行中排队；命中 CF 403/429 或登录失效时委托 SiteAccessCoordinator 恢复并单次重试
2. BrowserHeaderInterceptor     ← 注入统一清洗后的 WebView UA、Client Hints (Sec-CH-UA*)、Accept-Language、Sec-Fetch-*
3. AppCookieInterceptor         ← 主动从 SiteCookieStore 读写 Cookie / Set-Cookie（确保走 CronetInterceptor 时 Cookie 仍 100% 生效）
4. ManualRedirectInterceptor    ← 禁用 Cronet/OkHttp 隐式跳转，手动跟进 301/302/303/307/308，逐跳落盘 Set-Cookie 并准确保留最终 response.request.url
5. [可选] CronetInterceptor     ← 仅在平台 HttpEngine 或 play-services-cronet 可用时挂载；不可用时直接进入 OkHttp 5 原生连接池（Conscrypt）
```

**传输层自动选择策略（`HttpTransportFactory`）**：
1. **Android 14+（API 34+）或 Android 12–13 且 `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7`**：通过 `org.chromium.net:cronet:500.1.0` 内置的 `HttpEngineNativeProvider` 创建系统 `HttpEngine` 驱动的 `CronetEngine`（0 MB Native 体积，不依赖 GMS，开启 HTTP/2 + QUIC/HTTP3）。
2. **其余 Android 7–13（API 24+）且设备具备 GMS**：通过 `com.google.android.gms:play-services-cronet:18.1.1` 动态加载 GMS Cronet。
3. **无 GMS 的旧安卓（Android 7–13 且无 S Ext 7）或 Cronet 初始化异常**：平滑回退到纯 OkHttp 5（Conscrypt），并保留埋点观测过盾后二次 403 率；若线上数据显示旧机受影响显著，可在后续发布中直接打开 `org.chromium.net:cronet-bundled:500.1.0`（按 ABI 分包）。

### 2. 验证页检测规则（`ChallengeDetector`）

严格遵循“权威头优先、正文兜底排除 JSD”的规则：

1. **规则 A（主规则，权威）**：HTTP 状态码属于 `{403, 429, 503}` 且响应头 `cf-mitigated`（不区分大小写）包含 `challenge` → 直接判定为 `ChallengeSignal.CloudflareChallenge`。
2. **规则 B（兜底规则，无 `cf-mitigated` 时）**：
   - 状态码必须属于 `{403, 503}`（**绝不对 200/500 触发**）；
   - `Content-Type` 为空或包含 `text/html`；
   - 使用 `response.peekBody(16 * 1024)` 仅窥探前 16 KB（不消耗原始流），匹配以下任一强特征：
     - 包含 `window._cf_chl_opt` 或 `cf_chl_opt`；
     - 包含 `/cdn-cgi/challenge-platform/h/` 或 `orchestrate/chl_page`（**显式不匹配**普通页面末尾的 `scripts/jsd/main.js`）；
     - 同时包含 `<title>Just a moment` 与 `cloudflare`（或 `cf-challenge`）。
3. **登录态失效检测（与 CF 正交）**：在页面解析层/业务网关检测 HTTP 200 GBK HTML 中是否缺失 `div#umenu` 的 `space.php` 链接或出现“无权进行当前操作”/跳转 `logging.php`，走同一协调器的 `ensureAuthenticated` 分支。

### 3. 不可见先试、需要交互时再弹出可见 WebView

紧扣“系统能判断的不留给用户；能替人做的直接做；不打断人手上的事”原则：

1. **统一 UA 与 WebView 环境准备**：
   - 应用启动后异步初始化 `UserAgentProvider`：读取 `WebSettings.getDefaultUserAgent(context)`，正则清洗 `; wv` 与 `Version/4.0`（保留真实 Chromium 版本号），同时推导对应版本的 `Sec-CH-UA` / `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform`。
   - WebView 配置严格遵守 Cloudflare 官方移动端要求（[Turnstile Mobile Implementation](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)）：`javaScriptEnabled = true`、`domStorageEnabled = true`、`CookieManager.setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`、`userAgentString = unifiedUa`，允许访问 `challenges.cloudflare.com`、`about:blank`、`about:srcdoc` 及 `*.4d4y.com`，并且**忽略 `*.challenges.cloudflare.com` / `dnstest.dev` 子域的非致命探测错误**（官方明确说明该 DNS 失败属 Turnstile 正常行为，[Challenge Solve Issues](https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/)）。
2. **第一阶段：不可见静默过盾（不打断用户）**：
   - 在当前前台 `Activity` 的根 Compose 层挂载一个**真实视口尺寸（如宽 360dp+、高 480dp+）但不可见且不消费触摸事件**的 `AndroidView(WebView)`（例如 `Modifier.alpha(0f)` 置底且禁用点击/焦点，避免 `0x0` 尺寸或 `View.GONE` 触发风控或暂停渲染）。
   - 验证前先快照当前 WebView 与应用 Store 中的所有 `cf_clearance` 值，并按域名清除旧 `cf_clearance`（失败时恢复备份）。
   - 加载目标 URL：若原请求为 `GET` 则加载原 URL；若为 `POST`（如发帖/回复/登录提交），改加载稳定触发入口 `https://www.4d4y.com/forum/`（WebView 只能 GET 导航，且 `cf_clearance` 作用域覆盖整个站点，参考 Mihon `CloudflareInterceptor.kt:138-142`）。
   - 通过 `androidx.webkit.WebViewCompat`（支持 `JS_INJECTION_IN_FRAME_AND_WORLD` 时注入隔离世界 + `addWebMessageListener`，低版本退化为 `addJavascriptInterface`）在 `DOCUMENT_START` 监听 `window.addEventListener('message')` 中 `data?.source === 'cloudflare-challenge'` 的事件。
3. **第二阶段：按需无缝升级为可见面板**：
   - 若后台在 **6–8 秒内** 自动通过（主帧不再返回 `cf-mitigated: challenge`、页面加载完成且无 `cf_chl_opt`/`cf-turnstile`、且 `CookieManagerCompat.getCookieInfo` 读到区别于快照的新 `cf_clearance`）：直接 `CookieManager.flush()` → 同步入 `SiteCookieStore` → 销毁 WebView → 唤醒排队请求重试。**用户全程零打扰。**
   - 若收到 `interactiveBegin` / `interactiveTimeout` 事件，或超过 8 秒静默预算仍处于挑战页：
     - **纯后台任务（`AccessPriority.BackgroundSilent`，如新短消息轮询、回前台预刷新）**：直接静默中止、销毁 WebView、不计失败冷却，绝不打断用户当前阅读；
     - **用户等待中的前台请求（`AccessPriority.UserBlocking`）**：**不销毁重建 WebView**，直接将同一个 `WebView` 实例状态切换为可见（Compose 顶层 `ModalBottomSheet` / 自适应折叠屏居中 `Dialog`，带清晰中文提示“正在完成站点安全检查”和刷新/取消按钮），待用户完成勾选后自动收起并继续原操作。
   - **健壮性保护**：实现 `WebViewClient.onRenderProcessGone`（返回 `true` 并安全销毁重建，防止 WebView 渲染进程崩溃连带杀掉整个 App）。

### 4. 验证与登录统一管理（`SiteAccessCoordinator`）

将「CF 过盾」与「Discuz! 登录（`cdb_auth`）」统一收敛在同一个状态机中，解决旧架构里“登录接口撞 403、业务线程并发调 `LoginHelper().login()`”互相踩踏的问题：

- **单一会话世代（`sessionGeneration: Long`）**：登出或切换账号时递增世代号，任何早于当前世代的在途请求、WebView Cookie 同步结果均直接丢弃，杜绝旧账号 Cookie 回灌。
- **统一状态机**：`Idle` → `SolvingChallenge(presentation)` / `AutoLoggingIn` / `WaitingInteractiveLogin` / `Cooldown(until)`。
- **两条协同路径**：
  1. **已保存凭据的自动登录**：若在读帖/发帖时发现未登录（`cdb_auth` 失效），先确保具备有效站点通行状态（若取 `formhash` 或提交 `logging.php` 命中 CF 403，内部先串行过盾拿到 `cf_clearance` 再继续自动登录），全程后台完成。
  2. **需人工介入的交互登录（统一 WebView 复用）**：当用户首次登录、密码失效或站点弹出额外验证时，使用同一套 `WebViewChallengeHost` 打开 `https://www.4d4y.com/forum/logging.php?action=login`——用户在同一可见 WebView 中一次完成“可能出现的 CF 验证 + 账号登录”，监听到主域写入 `cdb_auth`（或跳转回非登录页）后一次性把 `cf_clearance` 与 `cdb_auth`/`cdb_sid` 原子同步至 `SiteCookieStore`。

### 5. 并发请求排队（Single-Flight Gate）

- 每个发出的请求在拦截器进入前记录当前 `clearanceEpoch`（单调递增版本号）。
- **撞盾合流**：当多个并发请求（如折叠屏双栏同时拉帖子列表 + 帖子详情 + 通知）同时返回 CF 403/429，均调用 `coordinator.resolveChallenge(triggerUrl, failedEpoch, priority)`：
  - 内部持 `Mutex` 检查：若 `currentEpoch > failedEpoch`，说明已有前序请求过盾成功，直接返回 `AccessOutcome.Cleared(currentEpoch)` 立即重试；
  - 若当前已有进行中的 `activeChallenge: CompletableDeferred<AccessOutcome>`，调用方不新开 WebView，而是调用 `escalatePriority(priority)`（静默轮次收到前台请求立即升档为可弹 UI）后 `await()` 同一个 `Deferred`；
  - 调用方协程被取消（如用户退出页面）时，仅取消自己的 `await()`，不影响共享验证本身。
- **验证期间新请求拦截**：`SiteAccessInterceptor` 在请求发出前检查 `coordinator.isChallengeActive`：
  - 后台静默请求直接快速失败/跳过（避免积压洪峰）；
  - 前台请求在发出网络包前直接挂起等待当前验证与 Cookie 同步完成，再读取最新 `Cookie` 发出，避免明知 `cf_clearance` 失效还继续打 403。
- **防环与冷却**：
  - 请求通过 OkHttp 5 `Call.tag` 或 `Request.tag` 携带重试计数，单次请求最多触发 **1 次** CF 自动重试；
  - 若过盾成功且已同步新 Cookie 后重试仍返回 CF 403/429：进入 **60 秒无效凭证冷却**，避免反复弹窗死循环，并向 UI 呈现可操作的提示与重试入口；
  - 验证取消或超时按轮次去重计失败，连续失败 3 轮进入 **30 秒冷却**（用户主动点击“重新验证”可清冷却）。

### 6. Cookie 过期后的自动续期策略

鉴于实测未在 `4d4y.com` 发现供常驻复用的独立 Turnstile `data-sitekey`（且 `fluxdo` 的实战教训表明常驻 Headless Turnstile WebView 在 Android 上极易抢占主线程造成滚动掉帧，需专门做滚动挂起逻辑，`cf_clearance_refresh_service.dart:625-665`），推荐**“按需无感续期 + 轻量临期预刷新 + 在位值粘性”**，**不常驻后台 WebView**：

1. **在位值粘性（`ClearanceAuthority`）**：在 `SiteCookieStore` 维护当前生效的 `cf_clearance` 元数据（值、签发时间、`Expires`、所属网络标识）。在位值健康（未过期、未临期、未被服务端 403/429 挑战）时，拒绝普通同步以异值覆盖，彻底规避 CHIPS 双变体旧值倒灌。
2. **轻量临期/切网预刷新（短生命周期）**：
   - 通过 `ProcessLifecycleOwner` 监听回前台，以及 `ConnectivityManager.NetworkCallback` 感知默认网络切换（Wi-Fi ↔ 蜂窝导致出口 IP 变化）；
   - 仅当本地存在 `cf_clearance` 且满足“距 `Expires` 不足 5 分钟”或“距离上次验证已超 25 分钟”或“网络刚切换”时，在后台发起一次 `AccessPriority.BackgroundSilent` 的一次性不可见 WebView 访问（触发 JSD / 静默续期），拿到新 `cf_clearance` 并同步后**立刻 `destroy()` 释放**；若需人工交互则静默放弃，不打扰用户。
3. **运行时过期兜底**：即便 Precursor 提前撤销 `cf_clearance` 或过期瞬间命中 403/429，业务请求通过 `SiteAccessInterceptor` 自动进入“不可见先试 → 按需升级可见 → 无感重试”，用户无感知完成续期。

### 7. Kotlin 接口草图（仅类名、模型与方法签名）

```kotlin
package net.jejer.hipda.network.access

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// ==================== 1. UA 与浏览器特征头 ====================

data class BrowserIdentity(
    val userAgent: String,
    val clientHints: Map<String, String>,
    val acceptLanguage: String,
)

interface UserAgentProvider {
    suspend fun getIdentity(): BrowserIdentity
    fun getCachedIdentityOrFallback(): BrowserIdentity
}

// ==================== 2. 挑战与登录失效检测 ====================

sealed interface AccessSignal {
    data object None : AccessSignal
    data class CloudflareChallenge(
        val statusCode: Int,
        val viaMitigatedHeader: Boolean,
        val triggerUrl: HttpUrl,
    ) : AccessSignal
    data class SessionExpired(val reason: String) : AccessSignal
}

interface ChallengeDetector {
    fun inspect(request: Request, response: Response): AccessSignal
}

// ==================== 3. Cookie 存储、WebView 同步与在位值粘性 ====================

data class ClearanceMetadata(
    val value: String,
    val domain: String,
    val expiresAtEpochMs: Long?,
    val isPartitioned: Boolean?,
    val issuedAtEpochMs: Long,
    val networkKey: String?,
)

enum class ClearanceReplaceDecision { Allow, SkipSameValue, SkipHealthyIncumbent }

interface ClearanceAuthority {
    fun noteIncumbentChallenged(challengedValue: String?)
    suspend fun evaluateReplacement(
        candidateValue: String,
        verifiedByChallenge: Boolean,
    ): ClearanceReplaceDecision
    fun reset()
}

interface SiteCookieStore : CookieJar {
    suspend fun currentClearance(url: HttpUrl): ClearanceMetadata?
    suspend fun hasAuthCookie(url: HttpUrl): Boolean
    suspend fun syncFromWebView(
        url: HttpUrl,
        onlyNames: Set<String>? = null,
        verifiedClearanceValue: String? = null,
        sessionGeneration: Long,
    ): Boolean
    suspend fun primeWebViewCookies(url: HttpUrl, excludeNames: Set<String> = setOf("cf_clearance"))
    suspend fun snapshotWebViewClearances(url: HttpUrl): Set<String>
    suspend fun clearClearanceEverywhere(url: HttpUrl): ClearanceMetadata?
    suspend fun restoreClearance(url: HttpUrl, backup: ClearanceMetadata)
    suspend fun clearAuthCookies()
}

// ==================== 4. 渐进式 WebView 验证与登录宿主 ====================

enum class AccessPriority { BackgroundSilent, UserBlocking, UserExplicit }

sealed interface ChallengeUiState {
    data object Hidden : ChallengeUiState
    data class InvisibleRunning(val targetUrl: HttpUrl) : ChallengeUiState
    data class VisibleRequired(
        val title: String,
        val targetUrl: HttpUrl,
        val webView: WebView,
        val isLoginFlow: Boolean,
    ) : ChallengeUiState
}

sealed interface WebViewFlowResult {
    data class Cleared(val freshClearance: String?) : WebViewFlowResult
    data object LoggedIn : WebViewFlowResult
    data object AbandonedSilently : WebViewFlowResult
    data object UserCancelled : WebViewFlowResult
    data class Failed(val cause: Throwable?) : WebViewFlowResult
}

interface WebViewChallengeHost {
    val uiState: StateFlow<ChallengeUiState>

    suspend fun solveChallenge(
        targetUrl: HttpUrl,
        priority: AccessPriority,
        oldClearanceSnapshot: Set<String>,
    ): WebViewFlowResult

    fun escalatePriority(priority: AccessPriority)

    suspend fun runInteractiveLogin(loginUrl: HttpUrl): WebViewFlowResult

    fun cancelActiveFlow()
}

// ==================== 5. 统一站点准入与登录协调器 ====================

sealed interface AccessOutcome {
    data class Ready(val epoch: Long) : AccessOutcome
    data class Cooldown(val untilEpochMs: Long) : AccessOutcome
    data object CancelledOrAbandoned : AccessOutcome
    data class Failed(val message: String, val cause: Throwable? = null) : AccessOutcome
}

interface SiteAccessCoordinator {
    val currentEpoch: Long
    val sessionGeneration: Long
    val isChallengeActive: Boolean
    val uiState: StateFlow<ChallengeUiState>

    suspend fun awaitIfChallengeActive(priority: AccessPriority)

    suspend fun resolveChallenge(
        triggerUrl: HttpUrl,
        failedEpoch: Long,
        priority: AccessPriority,
        isNonGetRequest: Boolean,
    ): AccessOutcome

    suspend fun ensureAuthenticated(priority: AccessPriority): AccessOutcome

    suspend fun maybeRefreshClearanceSilently(reason: String)

    fun notifyNetworkChanged(newNetworkKey: String?)

    fun reportPostClearanceRetryStillBlocked(epoch: Long, sentClearance: String?)

    fun resetCooldownForUserRetry()

    suspend fun onLogout()
}

// ==================== 6. 传输层与拦截器组装 ====================

enum class TransportKind { PlatformHttpEngine, PlayServicesCronet, PlainOkHttp }

interface HttpTransportFactory {
    fun resolveAvailableTransport(context: Context): TransportKind
    fun createOkHttpClient(
        context: Context,
        cookieStore: SiteCookieStore,
        userAgentProvider: UserAgentProvider,
        challengeDetector: ChallengeDetector,
        coordinator: SiteAccessCoordinator,
        preferCronet: Boolean = true,
    ): OkHttpClient
}

interface SiteAccessInterceptor : Interceptor
interface AppCookieInterceptor : Interceptor
interface ManualRedirectInterceptor : Interceptor
```

> **实现要点补充**：
> - `SiteAccessInterceptor` 放在同步 OkHttp `Interceptor.intercept(chain)` 内时，需通过 `runBlocking` 桥接挂起函数，并配合 `chain.call()` 取消监听（或 OkHttp 5 `okhttp-coroutines` 的 `Call.executeAsync()` 在协程挂起层外包一层 `SiteHttpClient` 做统一重试，**推荐优先在协程网关层做挂起式排队与重试**，避免长时间占用 OkHttp Dispatcher 线程池！同时在 `OkHttpClient` 内仍保留轻量的 `ChallengeDetector` 拦截器供 Coil 3 图片请求共用）。
> - `Request.tag` / `Call.tag` 用于标记 `AccessPriority`、`retryCount`、`skipChallenge`、`sessionGeneration`，与 OkHttp 5.3+ 的 `Call.tag` 能力天然契合。

---

## 六、CI 离线测试方案（基于 OkHttp 5 `MockWebServer`）

OkHttp 5 已将 MockWebServer 独立为 `com.squareup.okhttp3:mockwebserver3:5.5.1`（与项目所选 OkHttp 5.x 同版本，`import mockwebserver3.MockWebServer`、`mockwebserver3.MockResponse`、`mockwebserver3.Dispatcher`，来源：[OkHttp MockWebServer README](https://github.com/square/okhttp/blob/master/mockwebserver/README.md)）。

通过把 `WebViewChallengeHost`、`SiteCookieStore` 与时间源 `Clock` 抽象为接口，**全部核心网络状态机测试可在纯 JVM 单元测试中毫秒级运行，不依赖真实外网或 Android 模拟器**。

### 1. 用 MockWebServer 构造逼真响应夹具（Fixture）

基于本次对 `4d4y.com` 的实测样本，在测试工具类 `CloudflareFixtures` 中构造 6 类标准 `MockResponse`：

1. **标准 Managed Challenge 403**：`code(403)` + `addHeader("cf-mitigated", "challenge")` + `addHeader("server", "cloudflare")` + `addHeader("Content-Type", "text/html; charset=UTF-8")` + 含 `<title>Just a moment...</title>` 与 `window._cf_chl_opt = { cType: 'managed' }` 的精简正文。
2. **限流挑战型 429**：`code(429)` + `addHeader("Cf-Mitigated", "challenge")`（刻意测大小写不敏感）。
3. **无 `cf-mitigated` 头的兜底 403**：`code(403)` + `Content-Type: text/html` + 正文含 `cf_chl_opt`。
4. **正常 Discuz! 200 GBK 页面（含 JSD 负向用例）**：`code(200)` + `Content-Type: text/html; charset=GBK` + **真实 GBK 字节数组**（含中文标题、`<div id="umenu"><a href="space.php">...</a></div>`，且尾部刻意附带 `/cdn-cgi/challenge-platform/scripts/jsd/main.js` 与 `window.__CF$cv$params`）。
5. **源站 nginx 500（含 JSD 负向用例）**：`code(500)` + `500 Internal Server Error` + 尾部附带 `scripts/jsd/main.js`。
6. **业务普通 403（非 CF）**：`code(403)` + 无 `cf-mitigated` + `Content-Type: application/json` 或无 CF 标记的 HTML。

### 2. 核心测试用例矩阵

| 类别 | 测试场景 | `MockWebServer` + Fake 编排方式 | 关键断言 |
| --- | --- | --- | --- |
| **检测准确性** | 403/429 挑战识别 + JSD 负向防误判 | 依次入队上述 6 类 Fixture | 前 3 类命中 `CloudflareChallenge`；后 3 类（含带 `scripts/jsd/main.js` 的 200 GBK 页与 500 页）全部返回 `None`，不触发 `FakeChallengeHost` |
| **并发排队（Single-Flight）** | 10 个协程同时撞 403，仅开 1 次验证并全部重试成功 | `Dispatcher` 检查请求头 `Cookie` 是否含 `cf_clearance=fresh_v2`，无则返回 403 挑战，有则返回 200 GBK；`FakeChallengeHost` 用 `CompletableDeferred` 挂起验证，完成后往 `FakeCookieStore` 写入 `cf_clearance=fresh_v2` | `fakeHost.solveInvocations == 1`；10 个请求全部最终收到 200 且正确按 GBK 解码；`takeRequest()` 验证重试请求携带一致的 `User-Agent` 与新 `cf_clearance` |
| **展示等级动态升级** | 后台 `BackgroundSilent` 正在过盾时，前台 `UserBlocking` 请求加入 | 先起 1 个静默请求卡在 `FakeChallengeHost`，再发 1 个前台请求 | `fakeHost.escalatedPriorities` 收到 `UserBlocking`；两者共享同一次验证结果 |
| **单协程取消隔离** | 并发 5 个请求等待过盾时取消其中 1 个 | `job1.cancel()` 后放行 `FakeChallengeHost` | 仅 `job1` 抛 `CancellationException`，共享验证不被中止，其余 4 个请求重试成功 |
| **防递归与无效凭证冷却** | 过盾返回 `Cleared` 但服务端仍回 403 挑战 | `Dispatcher` 始终返回 403 挑战 | 单次请求最多发出 2 次网络包（初试 + 1 次重试），不死循环；立即进入 60s 冷却，冷却期内后续请求快速返回 `Cooldown` 不再调用 `FakeChallengeHost` |
| **失败轮次去重与退避冷却** | 5 个并发请求合流到同一轮，用户取消验证 | 连续模拟 3 轮取消（配合注入虚拟 `Clock` 推进时间） | 第 1 轮 5 个并发只记 1 次失败（不瞬间打满 3 次阈值）；满 3 轮后进入 30s 冷却；虚拟时钟推进 31s 后自动恢复 |
| **GBK 表单 POST + 302 重试** | `POST /forum/post.php`（GBK 表单）撞 403 → 过盾 → 重试 → 302 跳 `viewthread.php?tid=123` | `Dispatcher` 断言初试 POST 无 Cookie 回 403；过盾后重试 POST 校验请求体原始字节为合法 GBK 编码并回 `302 Location: viewthread.php?tid=123` + `Set-Cookie`；随后 GET 回 200 GBK | `FakeChallengeHost` 收到的触发 URL 是 GET `/forum/` 而非 POST URL；重试 POST 字节无乱码；最终 `response.request.url` 正确指向 `viewthread.php?tid=123` |
| **CHIPS 双变体与在位值粘性** | 旧分区副本与本轮新值并存，验证健康值不被旧副本覆盖 | 对 `SiteCookieStore` 与 `ClearanceAuthority` 纯 JVM 单测 | 仅采纳非快照新值；在位值健康时 `evaluateReplacement` 返回 `SkipHealthyIncumbent`；调用 `noteIncumbentChallenged` 后放行替换 |

> **补充测试建议**：在 Android 端可选加少量 Robolectric / Instrumented 测试覆盖 `WebViewClient` 状态机（`interactiveBegin` 升级可见、`onRenderProcessGone` 不崩溃）及 `CookieManagerCompat.getCookieInfo` 解析逻辑。

---

## 七、对现有代码的影响

以下是 `/home/mir/Documents/hipda-next` 中所有涉及 **HTTP 403 / 非 2xx 处理、UA 构造与设置、Cookie 隔离、以及需统一迁入新网络层的请求入口**（精确定位到 `文件:行号`）：

### 1. 现有 UA 定义与使用位置（全部硬编码为自定义非浏览器 UA，或遗漏未设）

| 文件:行号 | 现状 | 迁移影响 |
| --- | --- | --- |
| `hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java:18, 72, 227-231` | `UserAgentPrefix = "net.jejer.hipda "`，`getUserAgent()` 拼出 `"net.jejer.hipda  <appVersion>"`（且含双空格） | 废弃硬编码自定义 UA，改为由 `UserAgentProvider` 异步采集并清洗本机 WebView 真实 UA |
| `hipda/src/main/java/net/jejer/hipda/okhttp/OkHttpHelper.java:109, 136` | `buildGetRequest` / `buildPostFormRequest` 逐一写死 `.header("User-Agent", HiUtils.getUserAgent())` | 统一收口到 `BrowserHeaderInterceptor`，同步补齐 `Sec-CH-UA*` 与 `Accept-Language` |
| `hipda/src/main/java/net/jejer/hipda/async/UploadImgHelper.java:200-205` | 直接 `OkHttpHelper.getInstance().getClient().newCall(request)` 发图上传，**完全未设 `User-Agent`**（落回 `okhttp/4.9.1`） | 改为走统一客户端与 `BrowserHeaderInterceptor`，避免上传接口因 UA 不一致撞盾 |
| `hipda/src/main/java/net/jejer/hipda/glide/ImageStreamFetcher.java:78-82` | 对包含 `.4d4y.com/` 的图片 URL 强制替换为 `HiUtils.getUserAgent()` | 新应用使用 Coil 3 共享同一 OkHttp 5 客户端，自动获得统一 UA 与 Cookie |
| `hipda/src/main/java/net/jejer/hipda/glide/AvatarStreamFetcher.java:107-109` | 头像请求同样硬替换为 `HiUtils.getUserAgent()` | 同上，并入统一图片网络层 |
| `hipda/src/main/java/net/jejer/hipda/utils/Utils.java:529-555`（尤其 `545-548`） | `DownloadManager` 附件下载写死 `User-agent: HiUtils.getUserAgent()`，且仅手动塞 `Cookie: cdb_auth=...`，**缺失 `cf_clearance`** | 附件下载若访问主站域名会被 CF 拦截；需改为复用应用网络栈流式下载落盘（MediaStore），或至少同步注入完整域名 Cookie（含 `cf_clearance`）与清洗后 WebView UA |

### 2. 现有 403 / 非 2xx 错误处理与重试位置（均直接报错，无挑战识别与重试）

| 文件:行号 | 现状 | 迁移影响 |
| --- | --- | --- |
| `hipda/src/main/java/net/jejer/hipda/okhttp/OkHttpHelper.java:237-240` | `getResponseBody` 遇 `!response.isSuccessful()` 直接抛 `IOException("Unexpected code " + response.code() + ", " + response.message())`，**未读 `cf-mitigated` 头与正文** | 新架构在响应进入业务解析前由 `SiteAccessInterceptor` + `ChallengeDetector` 拦截并恢复；真正失败时返回结构化错误（区分网络错误 / CF 需验证 / 冷却中 / 权限 403） |
| `hipda/src/main/java/net/jejer/hipda/okhttp/OkHttpHelper.java:291-314` | `getErrorMessage` 解析 `ERROR_CODE_PREFIX` 把 403 统一转为 `"错误代码 (403)"` | UI 层应能区分“CF 验证冷却/已取消（提供‘重试验证’操作）”与普通 HTTP 错误 |
| `hipda/src/main/java/net/jejer/hipda/okhttp/RetryIntercepter.java:28-51` | 仅在 `GET` 抛 `IOException` 时固定 `Thread.sleep(500)` 重试最多 3 次，**对 HTTP 403/429 不做任何处理** | 替换为协程退避重试 + `SiteAccessInterceptor` 过盾重试 |
| `hipda/src/main/java/net/jejer/hipda/async/UploadImgHelper.java:206-207` | `!response.isSuccessful()` 抛 `IOException(ERROR_CODE_PREFIX + response.networkResponse().code())`（注意：若走 `CronetInterceptor`，`networkResponse()` 为 null 会触发 NPE！） | 新代码统一读 `response.code`，不依赖 `networkResponse()` |
| `hipda/src/main/java/net/jejer/hipda/async/PrePostAsyncTask.java:69-83` | 调 `getAsResponse` 仅检查 `respObj.isSuccessful()`，非 2xx（含 403）直接静默返回 `null`；且 `71` 行靠 `respObj.request().url()` 检查是否重定向到 `memcp.php?action=bind` | 必须确保 `ManualRedirectInterceptor` 保留最终重定向 URL，且非 2xx 正确透出错误/走 CF 恢复 |
| `hipda/src/main/java/net/jejer/hipda/async/PostHelper.java:195-215` | 调 `postAsResponse` + `getResponseBody`；`197, 204-212` 行依赖 302 跳转后的 `response.request().url()` 提取 `tid=` 与删帖结果 | 同上，若底层用 `CronetInterceptor` 必须禁内部自动重定向并由应用拦截器手动跟 302 |
| `hipda/src/main/java/net/jejer/hipda/glide/ImageStreamFetcher.java:58-60` | 非 2xx 直接抛 `IOException("Unexpected code " + code)` | 共享网络层后具备自动过盾与统一 Cookie 能力 |
| `hipda/src/main/java/net/jejer/hipda/glide/AvatarStreamFetcher.java:63-70` | 非 2xx 中仅处理 404，403 被静默忽略导致头像加载为 null | 同上 |

### 3. 现有 Cookie 存储、图片客户端隔离与登录耦合

| 文件:行号 | 现状 | 迁移影响 |
| --- | --- | --- |
| `hipda/src/main/java/net/jejer/hipda/okhttp/OkHttpHelper.java:70-72, 316-339` | 用 `SharedPreferencesCookieStore` + `JavaNetCookieJar` 持久化 Cookie，与系统 `android.webkit.CookieManager` 完全隔离；只暴露 `cdb_auth` 查询 | 替换为支持完整属性持久化（含 `Expires` / `Domain` / `Secure` / `HttpOnly` / CHIPS 选优）的 `SiteCookieStore`，并与系统 WebView `CookieManager` 做受控边界同步 |
| `hipda/src/main/java/net/jejer/hipda/glide/MyGlideModule.java:69-111` | Glide **单独 `new OkHttpClient.Builder()` 且未配置 `CookieJar`** | 新版 Coil 3 必须与主业务共享同一 `OkHttpClient`（或共享 `SiteCookieStore` + `BrowserHeaderInterceptor` + 传输层），否则主站附件图片无法带 `cf_clearance` 与 `cdb_auth` |
| `hipda/src/main/java/net/jejer/hipda/async/LoginHelper.java:38-142` | 直接在后台线程调用 `OkHttpHelper.get(LoginGetFormHash)` 与 `post(LoginSubmit)`，无并发锁；`ThreadListJob.java:60` / `ThreadDetailJob.java:70` 并发发现未登录时会各自 `new LoginHelper().login()` | 统一迁入 `SiteAccessCoordinator` 单例状态机，消除并发登录与 CF 过盾竞态 |
| `hipda/src/main/java/net/jejer/hipda/bean/HiSettingsHelper.java:712-733, 998-1026` | 维护编码配置（默认 GBK）与可配置 `forumServer` / `imageHost` | 新 `SiteCookieStore` 与 `ChallengeDetector` 应读取动态配置的论坛域名，而非写死 `www.4d4y.com` |

---

## 八、主要风险与应对

1. **【风险】平台 `HttpEngine` / Cronet 与系统 WebView 的 Chromium 小版本差异，或旧无 GMS 机型回退纯 OkHttp 5 后触发严格 TLS/H2 指纹校验，导致过盾后仍 403 循环。**
   - **应对**：严格执行“单请求最多重试 1 次 + 60 秒无效凭证冷却”，杜绝无限弹窗；埋点监控不同 `TransportKind` 下的过盾后二次 403 率；若线上数据显示特定机型仍被拦截，按需为旧无 GMS 机型开启 `cronet-bundled:500.1.0`（或针对极端场景开放可选的会话级 WebView 兼容模式）。
2. **【风险】`CronetInterceptor` 绕过 OkHttp 核心导致 Cookie 失效或 302 后 `response.request.url` 未更新，破坏 Discuz! 登录态与发帖跳转。**
   - **应对**：强制将 `AppCookieInterceptor` 与 `ManualRedirectInterceptor` 置于 `CronetInterceptor` 之前，并配置 `RedirectStrategy.withoutRedirects()`；用 `MockWebServer3` 回归测试锁定该行为。
3. **【风险】CHIPS 分区 `cf_clearance` 双变体残留导致过盾后仍带上旧值。**
   - **应对**：Android 上优先经 `CookieManagerCompat.getCookieInfo` 读完整属性，验证前快照旧值并清理、验证后只采纳新值，并配合 `ClearanceAuthority` 在位值粘性防止旧副本覆盖。
4. **【风险】Android WebView 兼容性与崩溃风险（旧 WebView 不支持最新 Turnstile JS、渲染进程 OOM 崩溃 `onRenderProcessGone`、后台不可见 WebView 渲染暂停）。**
   - **应对**：确保不可见 WebView 具备正常非零视口尺寸且保持活跃；实现 `onRenderProcessGone` 拦截防主进程崩溃；当检测系统 WebView 过旧导致连续失败时，明确引导用户更新 Android System WebView 或检查网络代理。
5. **【风险】站方升级 WAF 规则（如扩大到全部 `.php` 与 `img02.4d4y.com`、开启更短 Challenge Passage 或强制交互式验证）。**
   - **应对**：由于全部页面请求、登录、表单、Coil 3 图片与附件下载都统一经由 `SiteAccessCoordinator` + 共享 `SiteCookieStore`，规则扩大时无需改业务代码即可自动覆盖。
6. **【风险】移动网络切换（Wi-Fi ↔ 蜂窝）或代理变更导致出口 IP 变化，原 `cf_clearance` 立即失效。**
   - **应对**：监听 `ConnectivityManager.NetworkCallback` 标记网络变化并清空连接池，在回前台或下次请求时自动走无感续期。

---

## 参考来源

1. Cloudflare 官方文档 — 检测 Challenge 响应头（`cf-mitigated: challenge`）：https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
2. Cloudflare 官方文档 — `cf_clearance` 与 Precursor / Turnstile Pre-clearance：https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/
3. Cloudflare 官方文档 — Challenge Passage 有效期与 XHR 宽限：https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/challenge-passage/
4. Cloudflare 官方文档 — How Challenges Work（IP 变更限制）：https://developers.cloudflare.com/cloudflare-challenges/concepts/how-challenges-work/
5. Cloudflare 官方文档 — Cloudflare Cookies（含 `SameSite=None; Secure; Partitioned` / CHIPS）：https://developers.cloudflare.com/fundamentals/reference/policies-compliances/cloudflare-cookies/
6. Cloudflare 官方文档 — JavaScript Detections（`/cdn-cgi/challenge-platform/scripts/jsd/...`）：https://developers.cloudflare.com/cloudflare-challenges/challenge-types/javascript-detections/
7. Cloudflare 官方文档 — Bot Fight Mode：https://developers.cloudflare.com/bots/get-started/bot-fight-mode/
8. Cloudflare 官方文档 — JA3/JA4 Fingerprint：https://developers.cloudflare.com/bots/additional-configurations/ja3-ja4-fingerprint/
9. Cloudflare 官方文档 — Turnstile Mobile WebView 集成要求与 UA 一致性：https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/
10. Cloudflare 官方文档 — Challenge Solve Issues（PAT 401 与 `*.challenges.cloudflare.com` 非致命错误）：https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/
11. Android 官方文档 — `android.net.http.HttpEngine`（API 34 / S Extensions 7）：https://developer.android.com/reference/android/net/http/HttpEngine
12. Android 官方文档 — Cronet 集成指南：https://developer.android.com/develop/connectivity/cronet/start
13. Android 官方文档 — `androidx.webkit.CookieManagerCompat`（`getCookieInfo` / `GET_COOKIE_INFO`）：https://developer.android.com/reference/androidx/webkit/CookieManagerCompat
14. Google `cronet-transport-for-okhttp` 仓库与 v0.1.2 发布说明（OkHttp 5+ 兼容、minSdk 24、Cronet 500.1.0、限制清单）：https://github.com/google/cronet-transport-for-okhttp/blob/master/README.md ；https://github.com/google/cronet-transport-for-okhttp/releases/tag/v0.1.2
15. Google Maven 工件元数据（实测 `cronet-bundled:500.1.0` AAR 13.31 MB、`cronet:500.1.0` 含 `httpengine-native-provider`、`play-services-cronet:18.1.1` AAR 0.09 MB、`cronet-okhttp:0.1.2` AAR 0.10 MB）：https://dl.google.com/dl/android/maven2/org/chromium/net/cronet/500.1.0/cronet-500.1.0.pom ；https://dl.google.com/dl/android/maven2/org/chromium/net/cronet-bundled/500.1.0/cronet-bundled-500.1.0.pom
16. Square OkHttp 5 变更日志与 `mockwebserver3:5.5.1` 文档：https://github.com/square/okhttp/blob/master/CHANGELOG.md ；https://github.com/square/okhttp/blob/master/mockwebserver/README.md
17. Mihon（Tachiyomi 继任者）开源实现 — `CloudflareInterceptor.kt` 与 `AndroidCookieJar.kt`：https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt ；https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/AndroidCookieJar.kt
18. FlareSolverr 官方 README（`cf_clearance` 与 UA 一致性说明）：https://github.com/FlareSolverr/FlareSolverr/blob/master/README.md
19. 本地参考仓库 `fluxdo`（`/tmp/hipda-research/fluxdo`）与目标仓库 `hipda-next`（`/home/mir/Documents/hipda-next`）源码（具体 `文件:行号` 见正文第一、七节）。
