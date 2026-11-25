# 独立代码审查报告 R-B1b：debug 样本导出工具页与只读脱敏导出管线

- **判定**：**APPROVE**
- **审查对象**：`origin/card/B1b-sample-export` @ `795ee029033072b7bceeb5301a6310eb946713cf`（基线 `main` @ `8999d94ea83ea0ed701929c231b8bd6a1de0f936`）
- **改动统计**：17 个文件（`+2740 / -1`），其中 `hipda/src/debug/` 新增/修改 12 个文件（生产 Kotlin 代码 8 个新文件 1,458 行 + `PreviewActivity.kt` 入口增量 16 行，Manifest 17 行，资源 XML 69 行），`hipda/src/test/` 新增 3 个测试文件（1,180 行）及 2 张 `sample_export_*` Roborazzi 截图基线
- **CI 核验**：[GitHub Actions Run `45570686295`](https://github.com/mailwmj/hipda-next/actions/runs/45570686295)
  - `headSha`：`795ee029033072b7bceeb5301a6310eb946713cf`（与 `origin/card/B1b-sample-export` 完全一致）
  - Job `Build, Lint & Unit Tests`（`139056280106`，耗时 6m15s）：`conclusion = success`
  - 覆盖任务：`:hipda:assembleDebug`、`:hipda:assembleRelease`（含 `:hipda:minifyReleaseWithR8`）、`:hipda:lintDebug`、Room schema 一致性检查、`:hipda:testDebugUnitTest` + `:core-parser:test -Proborazzi.test.verify=true`（`TOTAL: 552 tests, 552 passed, 0 failed, 0 errors, 0 skipped`）及 `aapt2 dump badging` 校验

---

## 一、阻断项核查（依 `DISPATCH.md` §0/§4 与 `R-B1b.md` 阻断标准）

**无阻断项（0 项）。**

对照本卡四条硬性阻断红线逐一核实：
1. **对论坛写操作**：**零处**。`SampleExportEngine.kt:155` 唯一请求构造显式调用 `Request.Builder().url(url).get().build()`，16 条清单 URL 均为只读 GET；内置登录 `WebView`（`SampleLoginWebView.kt:115`）仅 `loadUrl("https://www.4d4y.com/forum/logging.php?action=login")` 供用户人工输入登录；单测 100% 运行在本地 `MockWebServer` 与 Robolectric 环境，零真实站点访问。
2. **脱敏漏网写入磁盘或 zip**：**零处**。原始 HTML 仅驻留局部变量内存，绝不落盘；每条响应均先经 `decodeBody` → `HtmlSanitizer.sanitize` → `SampleLeakScanner.scan` 内存校验，`leaks.isNotEmpty()` 时立即调 `failEntry` 从 `verifiedHtmlById` 移除并返回（`SampleExportEngine.kt:188-193, 346-348`），既不生成散落 `.html` 文件也不写入 `.zip`。真实 HiPDA 已登录场景下第 1 项版块页即含 `#umenu cite a`，`Identity(uid, username)` 首项即完整提取并传递至后续全部条目。
3. **debug 代码或资源进入 release 包**：**零残留**。独立下载 CI Run `45570686295` 产出的 `release-apk`（`hipda-ng-release.apk`）做三层核验：
   - `aapt2 dump xmltree hipda-ng-release.apk --file AndroidManifest.xml` 检索 `sampleexport|sample_export|PreviewActivity`：**0 命中**；
   - `unzip -l hipda-ng-release.apk` 检索 `sampleexport|sample_export`：**0 命中**；
   - 扫描全部 `classes*.dex` 字节串（`SampleExportActivity`、`SampleExportFileProvider`、`SampleExportEngine`、`sample_exports`、`debug.sampleexport.provider`）：**0 命中**。
4. **FileProvider 越权暴露应用私有文件**：**零越权**。`SampleExportFileProvider` 仅注册于 `src/debug/AndroidManifest.xml`（`exported="false"`、`grantUriPermissions="true"`），`sample_export_provider_paths.xml` 仅声明 `<files-path name="sample_exports" path="sample_exports/" />`，与 `files/datastore/hx_credentials`、`files/datastore/hx_site_cookies`、`shared_prefs/hx_tink_keysets.xml`、`databases/`、`app_webview/` 完全物理隔离；分享 Intent 仅授予 `Intent.FLAG_GRANT_READ_URI_PERMISSION`。

---

## 二、六项审查重点逐项详查

### 1. 只读红线与源码扫描测试有效性

#### 1.1 生产代码抓取路径核查
- **HTTP 请求构造（唯一入口）**：`SampleExportEngine.kt:155-156`
  ```kotlin
  val request = Request.Builder().url(url).get().build()
  siteAccessGateway.execute(request, AccessPriority.UserExplicit)
  ```
  全包仅此一处构造 OkHttp `Request`，固定 `.get()`，无任何 `RequestBody`、`FormBody`、`MultipartBody`、`GbkForm` 或 `HttpURLConnection`。
- **16 条目标 URL 逐条核查（`SampleExportEngine.kt:221-280`）**：
  - `forumdisplay.php`（`fid=2&page=1`、`fid=2&page=2`、`fid=2&page=1&orderby=dateline`）：只读版块列表 GET；
  - `viewthread.php`（`tid=<tid>&page=1`、`page=2`、`page=1&authorid=<authorUid>`）：只读帖子详情 GET；
  - `pm.php`（`filter=privatepm`、`daterange=5&uid=<peerUid>`）：只读短消息列表与对话历史 GET；
  - `notice.php`：只读提醒列表 GET；
  - `search.php?srchfrom=86400&searchsubmit=yes`：对应旧版 `HiUtils.NewPostsUrl`（`HiUtils.java:160`，“查看 24 小时新帖”只读查询，仅触发服务端搜索结果页跳转，不修改论坛业务数据）；
  - `space.php?uid=<uid>`：只读用户资料页 GET；
  - `my.php`（`item=threads&page=1`、`item=posts&page=1`、`item=favorites&type=thread&page=1`）：只读个人列表 GET（无 `action=add`/`remove`/`deletesubmit`）；
  - `post.php?action=newthread&fid=2`：只读获取发新帖空表单 HTML（无 `topicsubmit=yes`、无 `formhash`、无 POST 体，与旧版 `PrePostAsyncTask` 预取空表单一致，不在服务端创建主题）；
  - `logging.php?action=login`：只读获取登录空表单页（无 `loginsubmit=yes`）。
- **WebView 路径（`SampleLoginWebView.kt:49-81, 110-116`）**：仅执行一次 `loadUrl("https://www.4d4y.com/forum/logging.php?action=login")`，无 `postUrl`、无 `evaluateJavascript`、无 `addJavascriptInterface`、无 `addDocumentStartJavaScript`，且 `shouldOverrideUrlLoading` 拦截 `javascript:` 等非 `http`/`https`/`about` 协议。

#### 1.2 只读源码扫描测试（`SampleExportReadOnlyScanTest.kt`）有效性评估
- **已拦截项（`SampleExportReadOnlyScanTest.kt:31-51, 77-146`）**：
  - 剥离 `//` 与嵌套 `/* ... */` 注释同时保留字符串字面量与行号；
  - 单行 `.post(`、`.put(`、`.delete(`、`.patch(`、`.postUrl(`；
  - 标识符 `FormBody`、`MultipartBody`、`GbkForm`、`gbkFormBody`；
  - 单行字面量 `.method("POST"|"PUT"|"DELETE"|"PATCH", ...)`；
  - 字符串中的 `loginsubmit`、`action=add`、`action=del`、`(newthread|reply|edit|pm|poll)submit`。
  - 若将 `SampleExportEngine.kt:155` 的 `.get()` 直接改坏为 `.post(...)` 或单行 `.method("POST", ...)`，扫描测试**必然失败**。
- **可绕过盲区评估（“改坏一行但现有扫描测试仍通过”的具体反例，归入建议 S2）**：
  由于 `scanKotlinSource`（`SampleExportReadOnlyScanTest.kt:63`）按 `lineSequence()` **逐行**匹配正则，且部分规则绑定了字符串拼接形式而未适配本模块自身的 `forumEndpoint("k" to "v")` 写法，以下单点改动目前会漏检：
  1. **跨行或变量传参 `.method(...)` 及 OkHttp 5 构造器**：
     - 跨行：`Request.Builder().url(url).method(\n "POST", body)`（`\.method\s*\(\s*"POST"` 跨行失配）；
     - 变量传参：`val m = "POST"; Request.Builder().url(url).method(m, body)`；
     - 构造器：`Request(url = url, method = "POST", body = "x".toRequestBody())`（未使用 `FormBody`/`MultipartBody`/`GbkForm` 时一并绕过）。
  2. **`forumEndpoint` Pair 形式的 Discuz! GET 写操作参数与真实参数名差异**：
     - `SampleExportEngine.kt:276-280` 全部通过 `forumEndpoint("my.php", "action" to "add", "tid" to "1")` 传参，源码中是 `"action" to "add"` 而非 `"action=add"`，`action\s*=\s*add\b` 不会命中；
     - 旧版取消收藏真实 GET 地址为 `my.php?item=favorites&action=remove&...`（`HiUtils.java:153`）、黑名单增删为 `pm.php?action=addblack` / `delblack`（`HiUtils.java:157-158`）、退出登录为 `logging.php?action=logout`（会使服务端会话失效），均未列入规则；
     - Discuz! 7.2 发新帖提交参数实为 `topicsubmit=yes`（见 `PostHelper.java:108`），而扫描规则第 49 行写成了 `newthreadsubmit`，漏掉了 `topicsubmit` 与收藏批量删除的 `deletesubmit`。
  3. **WebView JS 提交表单与 `HttpURLConnection`**：
     - `webView.evaluateJavascript("document.forms[0].submit()", null)` 或 `webView.loadUrl("javascript:document.forms[0].submit()")` 未设规则；
     - `(URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true }` 未设规则。

---

### 2. 脱敏与零泄漏门禁

- **管线顺序与覆盖范围（`SampleExportEngine.kt:161-198`）**：
  1. `decodeBody(bytes, contentType)`（`SiteCharset.kt`，自动识别 BOM / `Content-Type` / `<meta charset>` / `GB18030` 超集解码为 UTF-8 字符串）；
  2. `HtmlSanitizer.sanitize(fetchedHtml, spec.pageType, activeIdentity)`（覆盖 `formhash`/`m_formhash`、`hash`/`sid`/`auth`/`seccode*`、`discuz_uid`、`cdb_*`/`cf_clearance`/`__cf_bm` Cookie 值、`#umenu` 当前用户 `uid`/用户名/头像路径、本人签名、短消息正文 `div.summary` 与链接/图片及对方 `uid`/用户名/头像/标题、提醒页 `f_buddy`/`f_manage` 附言、附件 `aid` Base64 内嵌 `uid`、邮箱、IPv4/IPv6）；
  3. `SampleLeakScanner.scan(cleaned)` 对称复检：若 `leaks.isNotEmpty()`，立即调用 `failEntry(spec, "脱敏校验未通过：$leakTypes", detail, onItemUpdated)`，从 `verifiedHtmlById` 中移除该条目并直接 `return`，**绝不写入磁盘或打包进 zip**。
  4. `manifest.json`（`SampleExportEngine.kt:325-337`）对失败项仅写入 `st.reason`（只含 `LeakType` 枚举名，不含具体值），不构成侧信道泄漏。
- **`Identity` 提取与无 `#umenu` 页面覆盖（`SampleHtmlExtractors.kt:19-31`、`SampleExportEngine.kt:200-209`）**：
  - 纯 Jsoup 选择器提取（`#umenu cite a` → `div#umenu a` → `discuz_uid` 脚本正则），完全不依赖旧版 `LoginHelper` 或任何登录解析器；
  - 跨页传递：首个含 `#umenu` 的已登录页面（清单第 1 项 `forumdisplay.php?fid=2&page=1`）提取出 `Identity(uid, username)` 缓存在 `discoveredIdentity` 中，后续即使遇到无 `#umenu` 的页面也将完整 `activeIdentity` 传给 `HtmlSanitizer.sanitize`。
  - **可加固边界（建议 S3）**：`SampleExportEngine.kt:201` 条件写为 `if (discoveredIdentity == null || discoveredIdentity?.uid.isNullOrBlank())`，若极端情况下首张响应仅有 `var discuz_uid = '54321'` 而无 `#umenu`（得 `Identity("54321", "")`），后续页面即使出现 `#umenu` 也不会再补齐 `username`，且首张页面不会在发现完整身份后回溯重脱敏；同时 `SampleHtmlExtractors.kt:17` 的 `DISCUZ_UID_REGEX` 可对齐 `SanitizeSelectors.DISCUZ_UID_REGEX` 支持 `parseInt('...')` 与 JSON `"discuz_uid": "..."`。
- **Zip 目录结构与 `SamplesAreSanitizedTest` 对齐核查**：
  - `SampleExportManifest.kt:56-73` 定义的 16 个 `relativePath`（`thread_list/*` 3 项、`thread_detail/*` 3 项、`sms/*` 2 项、`notify/*` 1 项、`search/*` 1 项、`user_info/*` 1 项、`simple_list/*` 3 项、`post_form/*` 1 项、`login/*` 1 项）与任务卡 `B1b.md:54-69` 逐字一致，全部以 UTF-8 `.html` 写入 zip；
  - 解压至 `hipda/src/test/resources/samples/` 后，`SamplesAreSanitizedTest`（扫描 `SampleLeakScanner.DEFAULT_SAMPLE_RELATIVE_PATHS` 下所有 `.html`/`.htm`/`.xml`）可递归覆盖全部 16 个样本文件，单测 `SampleExportEngineTest:204` 已用 `SampleLeakScanner.scanDirectory(unzippedDir.toPath())` 实测 0 泄漏。

---

### 3. debug 与 release 严格隔离

- **文件路径核验（`git diff --name-status 8999d94...795ee02`）**：
  - 全部 12 个新增/修改生产与资源文件均位于 `hipda/src/debug/`；
  - 全部 5 个新增测试与截图文件均位于 `hipda/src/test/`；
  - `hipda/src/main/`、`core-parser/`、`emoji/`、`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`、`MotionTokenScanTest.kt` 白名单、以及全部既有 Roborazzi 截图基线均为 **0 改动**；
  - `PreviewActivity.kt`（`hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt:51, 59, 195, 207-225`）仅在 `SpecimenHeader` 的“外壳”按钮左侧并列新增“样本导出”入口按钮。
- **CI Release 构建与产物隔离核验**：
  - CI Run `45570686295` 中 `:hipda:assembleRelease`（含 `:hipda:minifyReleaseWithR8`）成功产出 `hipda-ng-release.apk`；
  - 经对 CI 产物 `hipda-ng-release.apk` 独立执行 `aapt2 dump xmltree`、ZIP 条目列表检查与全量 DEX 字节检索，确认 `SampleExportActivity`、`SampleExportFileProvider`、`SampleExportEngine`、`strings_sample_export.xml`、`sample_export_provider_paths.xml` 在 release 包中 **100% 零存在**；`hipda-debug.apk` 中上述组件与 provider authority 均正常注册。

---

### 4. FileProvider 隔离与最小暴露

- **独立子类与独立 Authority（`hipda/src/debug/AndroidManifest.xml:24-32`、`SampleExportFileProvider.kt:9`）**：
  - 采用空子类 `SampleExportFileProvider : FileProvider()` 避免与主 Manifest（`hipda/src/main/AndroidManifest.xml:69-77`）的 `androidx.core.content.FileProvider` 发生 Manifest Merger 节点合并冲突；
  - `android:authorities="${applicationId}.debug.sampleexport.provider"`（即 `net.jejer.hipda.next.debug.sampleexport.provider`），与主 Manifest 的 `${applicationId}.provider` 完全隔离；
  - 显式声明 `android:exported="false"` 与 `android:grantUriPermissions="true"`。
- **路径最小化暴露核查（`hipda/src/debug/res/xml/sample_export_provider_paths.xml:1-6`）**：
  - 唯一配置项为 `<files-path name="sample_exports" path="sample_exports/" />`，`FileProvider.getUriForFile` 的规范化前缀校验严格锁死在 `<dataDir>/files/sample_exports/` 子目录；
  - 加密凭据 `files/datastore/hx_credentials`、加密 Cookie `files/datastore/hx_site_cookies`、用户设置 `files/datastore/settings.preferences_pb`、Tink 密钥集 `shared_prefs/hx_tink_keysets.xml`、Room 数据库 `databases/`、WebView 存储 `app_webview/` 均不在该子目录内，无法通过该 Provider 生成或访问 URI；
  - 且 `files/sample_exports/` 内只写入已通过 `HtmlSanitizer` + `SampleLeakScanner` 零泄漏校验的 `.zip` 归档（写完新包即清理旧包，`SampleExportEngine.kt:311, 334-338`）。
- **分享权限最小化（`SampleExportActivity.kt:37-46`）**：
  - `createSampleExportShareIntent` 使用 `Intent.ACTION_SEND`，仅添加 `Intent.FLAG_GRANT_READ_URI_PERMISSION`，未授予 `FLAG_GRANT_WRITE_URI_PERMISSION`、`FLAG_GRANT_PERSISTABLE_URI_PERMISSION` 或 `FLAG_GRANT_PREFIX_URI_PERMISSION`。

---

### 5. WebView 登录安全与隐私提示

- **WebView 登录安全与零密码接触（`SampleLoginWebView.kt:49-99, 101-155`）**：
  - 仅加载官方登录页 `https://www.4d4y.com/forum/logging.php?action=login`，由用户本人在 WebView 页面内手动输入账号密码提交；
  - 应用层零 JS 注入、零表单 DOM 读取、零 `shouldInterceptRequest` 拦截、零密码内存驻留或磁盘存储；
  - 显式关闭 `allowFileAccess = false`、`allowContentAccess = false`，拦截非 `http`/`https`/`about` 导航，且 `onRenderProcessGone` 安全销毁实例并返回 `true` 防止渲染进程异常拖垮宿主；退出登录浮层时通过 `DisposableEffect` 完整执行 `stopLoading` → 解绑 client → 移除父视图 → `destroy()`。
- **R-A5a 遗留落实（`SampleLoginWebView.kt:55`）**：
  - 显式设置 `settings.userAgentString = userAgentProvider.userAgent()`，开启 `javaScriptEnabled = true`、`domStorageEnabled = true`、`CookieManager.setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`，确保 WebView 登录与 `SiteAccessGateway` / Cloudflare 校验共用完全一致的归一化 UA（单测 `SampleExportEngineTest:442-448` 已断言）。
- **唯一登录态判断与 Cookie 同步（`SampleExportManifest.kt:96-101`、`SampleExportViewModel.kt:76-94`）**：
  - `isForumLoggedIn` 唯一依据 `siteCookieStore.loadForRequest(baseUrl).any { it.name == "cdb_auth" && it.value.isNotBlank() }`；
  - 页面跳转（`onPageFinished` / `doUpdateVisitedHistory`）与点击“完成登录” / “同步状态”均通过 `webViewCookieBridge.syncFromWebView(loginUrl)` 将 `cdb_auth`、`cdb_sid`、`cf_clearance` 同步至 `SiteCookieStore`；点击“完成登录”时先完成协程同步再卸载 WebView（`SampleExportViewModel.kt:80-86`），无提前 `destroy()` 竞态（点“关闭”/返回键的小竞态见建议 S5）。
- **R-B1a 建议 8 隐私提示（`SampleExportScreen.kt:235-271`、`strings_sample_export.xml:19-21`、`_reports/B1b.md:122-126`）**：
  - 页面首屏显眼位置使用 `tertiaryContainer` 卡片展示“隐私提示”，明确告知“我的帖子、我的回复、收藏含真实帖子与楼层编号，可在论坛反查发帖人。建议挑选公开普通帖，或使用测试号导出”，并提供整行可点击（高度 ≥ 48dp，`Role.Switch`）的开关控制这 3 项；
  - `_reports/B1b.md` 第三节“用户操作清单”同步完整写明了 M1 验收截图步骤、R-B1a 隐私提示及 `hipda/src/test/resources/samples/` 放置路径与复检命令。
  - **状态一致性瑕疵（建议 S1）**：若用户先保持默认开启完成一次抓取，随后关闭隐私开关并直接点“分享”或对其他失败项点“单项重试”，`SampleExportEngine.writeZipIfAny`（`SampleExportEngine.kt:297-302`）未按当前 `includePersonalHistory` 过滤 `verifiedHtmlById` 中已缓存的 `MY_THREADS`/`MY_POSTS`/`MY_FAVORITES`，且 `onIncludePersonalHistoryChange`（`SampleExportViewModel.kt:96`）未使旧 `exportedZipFile` 失效，会导致 `manifest.json` 标记 `includePersonalHistory=false` 但 zip 内仍含这 3 个已脱敏文件。用户按操作清单先在步骤 3 设定开关再点“一键抓取并导出”则完全正常。

---

### 6. UI 复用、动效与测试质量

- **UI 组件复用与视觉核验**：
  - 全部采用 Compose Material 3 标准组件，直接复用设计系统 `HiTheme`、`HiShapeTokens`、`HiCappedFontScale`、C5a `HiErrorState` + `HiStateAction`（失败项原地展示原因、可展开查看详情与复制、单项重试按钮 ≥ 48dp）、C5b `Modifier.hiPressScale` + `HiSnackbarHost`、以及 A6 `ChallengeStatusView`；
  - 零手写 `Layout`、零手写 `Modifier.Node`、零自定义命令队列、零测试专用钩子或 `CompositionLocal`；
  - `MotionTokenScanTest`（7/7）全绿通过，白名单零修改；`SampleExportScreen.kt:396` 对 `HiTheme.isReducedMotion` 做了静态进度条降级；
  - 目视核验 `sample_export_w411_light.png` 与 `sample_export_w411_dark.png`：浅色与深色对比度清晰、卡片层级分明、徽章与长路径单行省略规整、无文字裁切或重叠。
- **代码规模评估（`DISPATCH.md` §5）**：
  - 8 个 debug Kotlin 文件共 1,458 行（引擎 378 行、界面 431 行、ViewModel 162 行、登录 WebView 156 行、Activity 124 行、清单 101 行、HTML 提取器 97 行、Provider 9 行），模块职责正交、结构扁平直接，无过度设计。
- **测试质量核验**：
  - `SampleExportEngineTest`（9 个测试）、`SampleExportReadOnlyScanTest`（2 个测试）、`SampleExportScreenshotTest`（2 个测试）全部离线运行，覆盖 16 项全量 GBK 解码与动态 ID 提取、置顶/单页帖过滤、无私信自动跳过、泄漏门禁拒绝落盘与入包、单项失败隔离与单项重试更新 zip、前置短消息列表失败传导、`cdb_auth` 唯一登录态判定、WebView UA 与 Cookie 同步、隐私开关跳过 `my.php`、Manifest/FileProvider/ShareIntent/Hilt EntryPoint 接线及浅深色截图基线。

---

## 三、非阻断改进建议（供后续顺手收口或用户导出时留意）

1. **S1（事后切换隐私开关时的 zip 一致性，`SampleExportEngine.kt:110-125, 297-306`、`SampleExportViewModel.kt:96-97`）**：在 `writeZipIfAny` 写入条目时增加 `if (spec.isPersonalHistory && !includePersonalHistory) continue`（并在 `retryEntry` 入口同步将 `MY_THREADS`/`MY_POSTS`/`MY_FAVORITES` 移出 `verifiedHtmlById` 置为 `Skipped`），同时在 `onIncludePersonalHistoryChange` 变更开关时清空旧 `exportedZipFile`（或触发重打包），防止“先开着抓完 → 后关开关 → 直接点分享或单项重试”时仍把 `simple_list/my_*.html` 带入 zip。（当前用户操作时按步骤先定开关再点“一键抓取并导出”即可规避。）
2. **S2（加固只读源码扫描测试防绕过，`SampleExportReadOnlyScanTest.kt:31-72`）**：将逐行扫描升级为去注释后的跨行/空白归一化匹配，直接禁止任意 `.method(`（只允许 `.get()`）、`Request(` 命名参数 `method =`、`toRequestBody`、`HttpURLConnection` / `openConnection(`、`evaluateJavascript`、`loadUrl("javascript:`，并将 Discuz! 写操作参数规则扩展为同时覆盖 URL 查询串与 Pair 字面量（`"action"\s*to\s*"(?:add|del|remove|addblack|delblack|logout)"`）以及 `topicsubmit`、`deletesubmit`。
3. **S3（无 `#umenu` 极端页的 `Identity` 补全与回溯脱敏，`SampleExportEngine.kt:201`、`SampleHtmlExtractors.kt:17`）**：将 `updateDynamicContext` 的判断放宽为 `discoveredIdentity == null || discoveredIdentity?.uid.isNullOrBlank() || discoveredIdentity?.username.isNullOrBlank()`，当后续页面补齐了先前缺失的 `uid`/`username` 时对更早缓存的条目重跑一次 `sanitizer` + `leakScanner`，并将 `SampleHtmlExtractors.DISCUZ_UID_REGEX` 扩展支持 `parseInt('...')` 与 JSON `"discuz_uid": "..."`。
4. **S4（未登录态导出拦截与 `customTid` 正整数校验，`SampleExportScreen.kt:314`、`SampleExportViewModel.kt:101`、`SampleExportEngine.kt:364`）**：在 `!state.isLoggedIn` 时禁用“一键抓取并导出”（或弹出未登录确认提示），避免未登录误点时将 Discuz! 返回 HTTP 200 的“游客无权操作”提示页当作有效样本标为 `Success`；同时对 `customTid` 过滤全 `"0"` 输入（要求正整数，与 `SampleHtmlExtractors.isPositiveUid` 一致）。
5. **S5（登录 WebView 关闭时同步 Cookie 与空 scheme 拦截，`SampleExportViewModel.kt:71-74`、`SampleLoginWebView.kt:68`）**：将 `closeLoginWebView()` 改为调用 `syncCookiesFromWebView(showFeedback = false, closeWebViewAfterSync = true)`，避免用户登录后点“关闭”/系统返回键（而非“完成登录”）时因异步同步尚未落库而短暂显示“未登录”；`shouldOverrideUrlLoading` 中 `request.url?.scheme` 为 `null` 时返回 `true` 默认拦截。

---

## 四、复审记录（修复轮 1 @ `b9c9cc2b5d903eea59a0cc58aa8227f86dac2d6a`）

- **复审判定**：**APPROVE**
- **审查增量**：`git diff 795ee029033072b7bceeb5301a6310eb946713cf..b9c9cc2b5d903eea59a0cc58aa8227f86dac2d6a`（共 8 个文件，全部位于 `hipda/src/debug/` 与 `hipda/src/test/`，`hipda/src/main/`、`core-parser/`、共享文件与既有截图基线 **0 改动**）
- **CI 核验**：[GitHub Actions Run `45576607291`](https://github.com/mailwmj/hipda-next/actions/runs/45576607291)
  - `headSha`：`b9c9cc2b5d903eea59a0cc58aa8227f86dac2d6a`
  - Job `Build, Lint & Unit Tests`（`139076003907`，耗时 4m42s）：`conclusion = success`
  - 单测与截图汇总：`SampleExportEngineTest` 13/13、`SampleExportReadOnlyScanTest` 3/3、`SampleExportScreenshotTest` 2/2、`MotionTokenScanTest` 7/7、`SamplesAreSanitizedTest` 4/4，全仓 `TOTAL: 557 tests, 557 passed, 0 failed, 0 errors, 0 skipped`

### S1–S5 逐项核实

1. **S1（隐私开关关闭后 zip / 单项重试零 `my_*` 残留）—— 已完整落实**：
   - `SampleExportViewModel.onIncludePersonalHistoryChange`（`SampleExportViewModel.kt:93-117`）在切换瞬间同步将 `exportedZipFile` 置为 `null`（立即禁用“分享”按钮，杜绝重建窗口期误分享旧包），随后调用 `engine.updatePersonalHistoryInclusion`；
   - `SampleExportEngine.updatePersonalHistoryInclusion` / `enforcePersonalHistoryPolicy`（`SampleExportEngine.kt:130-176`）在关闭开关时从 `verifiedHtmlById` 与 `pendingIdentityRawHtmlById` 双重移除 `MY_THREADS`/`MY_POSTS`/`MY_FAVORITES` 并标为 `Skipped`，重建不含 `my_*` 的新 zip 并调 `removeOldArchives` 物理删除磁盘上的旧 zip；重新开启开关时重置为 `Pending` 并清理旧 zip；
   - `retryEntry`（`SampleExportEngine.kt:118`）入口强制执行 `enforcePersonalHistoryPolicy`，`writeZipIfAny`（`SampleExportEngine.kt:388-390`）与 `buildManifestBytes`（`SampleExportEngine.kt:429-433`）再次按 `!(spec.isPersonalHistory && !includePersonalHistory)` 过滤，三重防线确保开关关闭后任何重试或重打包路径均绝不含 `simple_list/my_*.html`；
   - 单测 `SampleExportEngineTest:privacyToggleTurnedOffAfterFullExport_rebuildsZipWithoutPersonalHistoryAndInvalidatesCache`（`L624-690`）完整覆盖“全量开启导出 → 事后关开关重建并校验旧包已删 → 单项重试仍无 `my_*` → 重新开开关使旧包失效”。

2. **S2（跨行只读源码扫描与真实源码改坏自证）—— 已完整落实**：
   - `SampleExportReadOnlyScanTest.scanKotlinSource`（`SampleExportReadOnlyScanTest.kt:73-103`）改为剥离注释后在**整文件字符串**上执行跨行 `findAll` 并通过行首偏移二分定位行号；
   - `FORBIDDEN_RULES`（`L31-64`）补齐跨行 `\.\s*method\s*\(`（无论字面量或变量传参，只允许 `.get()`）、`Request(..., method = ...)` 构造器、`toRequestBody`、`evaluateJavascript`、`loadUrl("javascript:...")`（含多引号跨行）、`HttpURLConnection` / `.openConnection(`、URL 查询串与 `forumEndpoint` Pair 双形态的 `action =/to add|del|remove|addblack|delblack|logout`、以及 `\b(login|topic|newthread|reply|edit|pm|poll|delete)submit\b`；
   - 新增自证测试 `mutationSelfProof_mutatingSingleLineInRealEngineImmediatelyFailsScan`（`L274-312`）：直接读取真实生产文件 `SampleExportEngine.kt`，先断言原文件 0 违规，再分别对真实代码做 3 处单点改坏（`.get()` 改为跨行变量 `.method(verb, null)`、`my_favorites` 改为 `"action" to "remove"`、`post.php` 增加 `"topicsubmit" to "yes"`），均实测命中对应规则变红。

3. **S3（身份缺失阻断与后续补全回溯重脱敏）—— 已完整落实**：
   - `SampleHtmlExtractors.DISCUZ_UID_REGEX`（`SampleHtmlExtractors.kt:17-18, 32`）已扩展支持 `parseInt('54321')`、`Number("65432")` 及 JSON `"discuz_uid": "76543"`，并继续过滤游客 `'0'`；
   - `SampleExportEngine.processEntry` 与 `updateDynamicContext`（`SampleExportEngine.kt:234-277, 303-324`）要求 `Identity.isComplete()`（`uid` 与 `username` 均非空）才允许进入 `sanitizeScanAndRecord` 写入 `verifiedHtmlById`；若任一字段缺失，仅在内存 `pendingIdentityRawHtmlById` 暂存原始 HTML 并将条目标为 `Failed("未提取到完整登录身份，拒绝放行")`（`detail` 仅记录布尔值，不暴露敏感串），全程零落盘、零入 zip；
   - 一旦后续页面补齐完整 `Identity(uid, username)`，`resanitizePendingEntries` 立即对前置暂存页逐条补跑 `HtmlSanitizer.sanitize` + `SampleLeakScanner.scan`；
   - 单测 `incompleteIdentity_blocksReleaseWhenUsernameMissing_andRetroactivelyResanitizesWhenCompletedLater`（`SampleExportEngineTest.kt:694-748`）实测覆盖“全站无 `#umenu` 用户名时 0 成功、不生成 zip”与“首张无 `#umenu` 但第 2 张起含 `#umenu` 时回溯擦除首张 `SecretLoginUser` 与 `54321`”。

4. **S4（未登录禁用导出与 `customTid` 正整数校验）—— 已完整落实**：
   - `SampleExportScreen.kt:328, 337` 在 `!isLoggedIn` 时禁用“一键抓取并导出”与“分享”按钮；`SampleExportViewModel.runExportTask`（`SampleExportViewModel.kt:151-159`）在启动抓取或单项重试前复查 `isForumLoggedIn`，未登录时零网络请求并提示 `请先登录后再抓取样本`；
   - `SampleHtmlExtractors.normalizePositiveTid`（`SampleHtmlExtractors.kt:19-22`）以 `^[1-9]\d*$` 严格校验正整数，拒绝 `"0"`、`"000"`、`"012"`、负数与非数字（单测 `L753-821` 已覆盖）。

5. **S5（关闭登录 WebView 先同步 Cookie 与空 `scheme` 默认拦截）—— 已完整落实**：
   - `SampleExportViewModel.closeLoginWebView`（`SampleExportViewModel.kt:71-73`）改为调用 `syncCookiesFromWebView(showFeedback = false, closeWebViewAfterSync = true)`（`runCatching` 兜底保证异常时仍能关闭浮层，不会卡死界面）；
   - `SampleLoginWebView.kt:68` 对 `request.url?.scheme == null` 返回 `true` 默认拦截（单测 `L576-620` 已覆盖）。

### 回归与安全性核查
- **无新增写操作**：全模块仍仅 `SampleExportEngine.kt:208` 一处 `.get()` 请求与 `SampleLoginWebView.kt:115` 登录页加载。
- **无新增泄漏路径**：`pendingIdentityRawHtmlById` 仅驻留内存并在失败、隐私跳过或重跑时同步清理，未通过完整身份脱敏与 `SampleLeakScanner` 零泄漏门禁的内容绝不落盘或进入 zip。
- **无越界改动**：`src/main/` 与共享配置文件零触碰。
