# 任务卡 B1b：debug 样本导出工具页（内置 WebView 登录 + 只读批量脱敏导出）

【角色】你是 hipda-next 的 Android 工具页与网络集成开发者（Kotlin、Jetpack Compose、OkHttp 5、WebView、Hilt、Roborazzi）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/B1b`（分支 `card/B1b-sample-export`，基于 `origin/main` @ `8999d94ea83ea0ed701929c231b8bd6a1de0f936`）里工作。
- **全部代码与资源严格放在 debug 源集与测试目录，绝不进入 release 包**：
  - `hipda/src/debug/AndroidManifest.xml`（注册 `SampleExportActivity` 与 debug 专用 `FileProvider`）；
  - `hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`（**仅在 `SpecimenHeader` 增加“样本导出”入口按钮**，其余代码不动）；
  - 新增 `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/`（工具页 UI、ViewModel/抓取器、清单、zip 打包与导出逻辑）；
  - 新增 `hipda/src/debug/res/values/strings_sample_export.xml` 与 `hipda/src/debug/res/xml/sample_export_provider_paths.xml`；
  - 新增 `hipda/src/test/java/net/jejer/hipda/debug/sampleexport/` 下的单测、只读源码扫描测试与 Roborazzi 截图测试（仅新增 `sample_export_*` 截图基线，不动任何既有基线）。
- **不得修改**：`hipda/src/main/` 下任何生产代码或资源、`core-parser/src/main/`、任何共享文件（`gradle/libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）、`MotionTokenScanTest` 白名单。
- **只读红线（DISPATCH §0）**：本卡**只做只读 GET 抓取**，不涉及任何对论坛的写操作（发帖、回复、短消息发送、收藏增删、改资料等），登录由用户在内置 WebView 手动操作且应用不保存密码。代码里严禁出现任何对论坛的 POST/表单提交逻辑，必须由自动化源码扫描测试守住。
- 规模与审查：界面 + 工具集成卡；审查为 Carbon 独立代码审查 + Hydrogen 视觉复验。实际代码若明显超出预期，先自查简化并在回复里说明原因。

【背景】
- 必读规格：
  - `docs/design/backlog.md` B1b 行与“用户要做的事”：debug 样本导出工具页，内置 WebView 登录并用 A5a Cookie 同步把 `cdb_auth`、`cdb_sid`、`cf_clearance` 同步进 `SiteCookieStore`（不依赖任何登录解析器），按清单批量抓取经 B1a 脱敏后导出，给用户操作清单；B1b 完成后用户一次做完登录、确认抓到版块页（M1 验收）和一键导出样本。
  - `docs/design/tech-plan.md` §3、§5“解析器先上测试，再抽模块（T2）”、§8 M1/M2。
  - `docs/agents/handoff.md` §4“→ B1b”（**R-B1a 建议 8**）：导出清单与页面提示中必须明确说明——“我的帖子 / 我的回复 / 收藏”含真实 `tid`/`pid`，可在论坛反查发帖人，建议挑选公开普通帖或使用测试号导出。
  - **R-A5a 审查遗留**：登录 WebView 必须显式设置 `settings.userAgentString = userAgentProvider.userAgent()`，保证 WebView 与 `SiteAccessGateway` 使用同一 UA。
  - `docs/agents/lesson.md`：本地 Gradle 前导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`；OkHttp `Cookie.parse` 单测需 Robolectric + `OkHttp.initialize(ApplicationProvider.getApplicationContext())`；新 Roborazzi 基线本地按 DISPATCH §2 用 `--tests` 限定录制。
- 现有代码（已核对真实路径与签名）：
  - B1a（`:core-parser`，`net.jejer.hipda.parser.sanitize`）：
    - `HtmlSanitizer.sanitize(html: String, page: PageType, identity: Identity): String`
    - `SampleLeakScanner.scan(html: String): List<Leak>`
    - `SampleLeakScanner.DEFAULT_SAMPLE_RELATIVE_PATHS = listOf("core-parser/src/test/resources/samples", "hipda/src/test/resources/samples")`（`SamplesAreSanitizedTest` 递归扫描这两个目录下全部 `.html`/`.htm`/`.xml` 文件）
    - `PageType`：`THREAD_LIST`、`THREAD_DETAIL`、`SMS_LIST`、`SMS_DETAIL`、`NOTIFY`、`SEARCH`、`SIMPLE_LIST`、`USER_INFO`、`POST_FORM`、`LOGIN`、`BLACKLIST`、`OTHER`
    - `Identity(val uid: String, val username: String)`
  - A1/A2/A4b/A5a（`net.jejer.hipda.data.network`）：
    - `decodeBody(bytes: ByteArray, contentType: String?): String`（`SiteCharset.kt`，自动按 UTF-8 BOM / Content-Type / `<meta>` / GB18030 解码）
    - `SiteCookieStore.loadForRequest(url: HttpUrl): List<Cookie>`
    - `SiteAccessGateway.execute(request: Request, priority: AccessPriority = AccessPriority.UserBlocking, timeout: Duration = 30.seconds): Response`（与 `AccessPriority.UserExplicit`）
    - `UserAgentProvider.userAgent(): String`
    - `WebViewCookieBridge.syncFromWebView(url: HttpUrl, excludeClearanceValues: Set<String> = emptySet()): CookieSyncResult`
  - C1/C2/C5a/C5b（`net.jejer.hipda.ui.designsystem`）：`HiTheme`、`HiShapeTokens`、`HiMotion`、`HiErrorState`、`HiStateAction`、`Modifier.hiPressScale`、`HiSnackbarHost`。
  - `hipda/src/debug/AndroidManifest.xml` 与 `hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`（第 206–221 行 `SpecimenHeader`）。

【目标】在 `src/debug/` 交付完整的样本导出工具页：支持内置 WebView 手动登录并同步 Cookie、按清单通过 `SiteAccessGateway` 只读 GET 抓取并经 `HtmlSanitizer` + `SampleLeakScanner` 零泄漏校验后打包 zip 分享导出，同时提供单测、只读源码扫描测试、截图基线与面向用户的操作清单。

【范围】
1. **Debug 入口与 FileProvider（不进 release 包）**：
   - `hipda/src/debug/res/xml/sample_export_provider_paths.xml`：仅暴露应用私有导出目录（如 `<files-path name="sample_exports" path="sample_exports/" />`）。
   - `hipda/src/debug/AndroidManifest.xml`：注册 `SampleExportActivity`（`exported="false"`）与 debug 专用 `androidx.core.content.FileProvider`（`authorities="${applicationId}.debug.sampleexport.provider"`，`exported="false"`，`grantUriPermissions="true"`，不与主 Manifest 的 `${applicationId}.provider` 冲突）。
   - `PreviewActivity.kt`：仅在 `SpecimenHeader` 的“外壳”按钮旁新增“样本导出”入口按钮跳转 `SampleExportActivity`（1 处最小改动）。
2. **内置 WebView 登录与 Cookie 同步**：
   - 展示当前登录态（“已登录 / 未登录”），**判断依据唯一**：`siteCookieStore.loadForRequest(FORUM_BASE_URL).any { it.name == "cdb_auth" && it.value.isNotBlank() }`，不依赖任何登录解析器，不保存用户密码。
   - 点击登录在页内/内置 WebView 打开 `https://www.4d4y.com/forum/logging.php?action=login`：
     - **必须配置**：`settings.userAgentString = userAgentProvider.userAgent()`、`javaScriptEnabled = true`、`domStorageEnabled = true`、`CookieManager.setAcceptCookie(true)`、`setAcceptThirdPartyCookies(webView, true)`；
     - 页面导航变化或用户点击“完成登录/同步状态”时调用 `webViewCookieBridge.syncFromWebView(loginUrl)`，将 `cdb_auth`、`cdb_sid`、`cf_clearance` 同步进 `SiteCookieStore` 并刷新登录态显示。
3. **写死的只读 GET 抓取清单与样本目录约定**：
   - 解压后的目录与文件命名严格对齐 `hipda/src/test/resources/samples/`（与 `SamplesAreSanitizedTest` 扫描规则一致，全部以 UTF-8 写入 `.html`）：
     1. `thread_list/forum_2_p1_lastpost.html` — `GET https://www.4d4y.com/forum/forumdisplay.php?fid=2&page=1`（`THREAD_LIST`，兼作 M1 验收验证）
     2. `thread_list/forum_2_p2_lastpost.html` — `GET https://www.4d4y.com/forum/forumdisplay.php?fid=2&page=2`（`THREAD_LIST`）
     3. `thread_list/forum_2_p1_dateline.html` — `GET https://www.4d4y.com/forum/forumdisplay.php?fid=2&page=1&orderby=dateline`（`THREAD_LIST`）
     4. `thread_detail/thread_p1.html` — `GET https://www.4d4y.com/forum/viewthread.php?tid=<tid>&page=1`（`THREAD_DETAIL`；默认从项 1 公开普通帖中自动挑选一个多页长帖 `tid`，界面允许用户手填公开 `tid` 覆盖）
     5. `thread_detail/thread_p2.html` — `GET https://www.4d4y.com/forum/viewthread.php?tid=<tid>&page=2`（`THREAD_DETAIL`）
     6. `thread_detail/thread_author_only.html` — `GET https://www.4d4y.com/forum/viewthread.php?tid=<tid>&page=1&authorid=<authorUid>`（`THREAD_DETAIL`；`authorUid` 从项 4 1 楼作者链接提取）
     7. `sms/sms_list.html` — `GET https://www.4d4y.com/forum/pm.php?filter=privatepm`（`SMS_LIST`）
     8. `sms/sms_detail.html` — `GET https://www.4d4y.com/forum/pm.php?daterange=5&uid=<peerUid>`（`SMS_DETAIL`；`peerUid` 从项 7 第一条对话提取；若无短消息则标为“跳过”，不阻断整体导出）
     9. `notify/notify_list.html` — `GET https://www.4d4y.com/forum/notice.php`（`NOTIFY`）
     10. `search/search_result.html` — `GET https://www.4d4y.com/forum/search.php?srchfrom=86400&searchsubmit=yes`（`SEARCH`，只读 GET）
     11. `user_info/user_profile.html` — `GET https://www.4d4y.com/forum/space.php?uid=<uid>`（`USER_INFO`；取当前登录用户或项 4 楼主公开 `uid`）
     12. `simple_list/my_threads.html` — `GET https://www.4d4y.com/forum/my.php?item=threads&page=1`（`SIMPLE_LIST`）
     13. `simple_list/my_posts.html` — `GET https://www.4d4y.com/forum/my.php?item=posts&page=1`（`SIMPLE_LIST`）
     14. `simple_list/my_favorites.html` — `GET https://www.4d4y.com/forum/my.php?item=favorites&type=thread&page=1`（`SIMPLE_LIST`）
     15. `post_form/newthread_fid2.html` — `GET https://www.4d4y.com/forum/post.php?action=newthread&fid=2`（`POST_FORM`，**仅 GET 抓取空表单页，绝不提交**）
     16. `login/login_page.html` — `GET https://www.4d4y.com/forum/logging.php?action=login`（`LOGIN`）
   - **抓取、脱敏、零泄漏校验与打包管线**（纯 Kotlin 引擎，与 UI 解耦便于 JVM/Robolectric 单测）：
     - 每项通过 `SiteAccessGateway.execute(Request.Builder().url(url).get().build(), AccessPriority.UserExplicit)` 只读 GET 获取字节流，非 2xx 判为失败；
     - 用 `decodeBody(bytes, response.header("Content-Type"))` 解码为 UTF-8 字符串；
     - 从首张登录页 `#umenu` 自动提取 `Identity(uid, username)`（无需登录解析器），传给 `HtmlSanitizer.sanitize(html, entry.pageType, identity)` 脱敏；
     - 调用 `SampleLeakScanner.scan(sanitized)`：**若 `leaks.isNotEmpty()`，拒绝写入磁盘与 zip，将该项标为失败并展示泄漏类型摘要**；
     - 全部完成后将通过校验的文件（加一份无敏感信息的 `manifest.json` 汇总各条状态与脱敏确认）写入应用私有目录 `filesDir/sample_exports/hipda-samples-<timestamp>.zip`，并通过 `Intent.ACTION_SEND` + debug `FileProvider` URI + `FLAG_GRANT_READ_URI_PERMISSION` 调起系统分享。
4. **Compose 工具页 UI（`strings_sample_export.xml`）**：
   - 全部字符串放 `hipda/src/debug/res/values/strings_sample_export.xml`；使用 Compose 标准组件，复用 `HiTheme`、`HiShapeTokens`、`HiErrorState`、`HiStateAction`、`Modifier.hiPressScale`（不手写多余组件，遵守 `MotionTokenScanTest`）。
   - **隐私提示卡片（落实 R-B1a 建议 8）**：页面显眼位置显示提示——“我的帖子、我的回复、收藏含真实帖子与楼层编号，可在论坛反查发帖人；建议挑选公开普通帖或使用测试号导出”，并允许勾选/取消勾选这三项（默认勾选或开关清晰可见）。
   - 顶部登录态卡片（已登录/未登录 + 打开内置登录 WebView 按钮 + 同步 Cookie 按钮）、可选自定义公开 `tid` 输入框、“一键抓取并导出”按钮、“分享 zip”按钮、抓取清单列表（每项显示待抓取/抓取中/成功/跳过/失败；失败项以 `HiErrorState` 原地展示原因并支持**单项重试**）。

【步骤】
1. 先写失败的测试（严禁访问真实站点）：
   - **抓取与脱敏管线单测**（`SampleExportEngineTest.kt`，MockWebServer + 假 gateway/OkHttp + `InMemorySiteCookieStore`）：
     a. 完整清单只读 GET 流程：验证所有请求 method 均为 `GET`、GBK 响应正确解码、动态提取公开 `tid`/`authorUid`/`peerUid`/`Identity`、全部样本经 `HtmlSanitizer.sanitize` 脱敏且 `SampleLeakScanner.scan` 均为 0 泄漏、生成的 zip 解压后目录结构符合 `samples/` 约定且整目录通过 `SampleLeakScanner.scanDirectory`。
     b. 泄漏拦截门禁：模拟脱敏后仍含未脱敏敏感内容（注入测试用假 sanitizer 或未清洗输入）→ 断言该项标为失败、文件不被写入私有目录也不进入 zip。
     c. 单项失败与单项重试：某一项返回 500 或网络失败时，其余项照常完成；对该失败项执行单项重试成功后状态更新并纳入新导出 zip。
     d. 登录态判定与 WebView UA 配置：验证仅依据 `SiteCookieStore` 中是否存在非空 `cdb_auth` 判断已登录/未登录；验证内置登录 WebView 显式设置 `userAgentString = userAgentProvider.userAgent()` 且同步调用 `WebViewCookieBridge.syncFromWebView`。
     e. 隐私勾选项：取消勾选“我的帖子/我的回复/收藏”时，这 3 项不发请求也不进 zip。
   - **只读源码扫描测试**（`SampleExportReadOnlyScanTest.kt`）：
     f. 扫描 `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/` 下所有 `.kt` 源码，断言不得出现 `.post(`、`.put(`、`.delete(`、`.patch(`、`FormBody`、`MultipartBody`、`GbkForm`、`loginsubmit`、`action=add`、`action=del`、`method("POST"` 等写操作构造（如把一条 GET 临时改坏为 `.post(...)`，该扫描测试必然失败）。
   - **Roborazzi 截图测试**（`SampleExportScreenshotTest.kt`，前缀 `sample_export_`）：
     g. 411dp 浅色与深色核心界面（已登录 + 列表含成功/抓取中/失败 `HiErrorState` 单项重试 + R-B1a 建议 8 隐私提示）。
2. 实现 debug 源集生产代码与 `PreviewActivity` 入口按钮。
3. 本地按 DISPATCH §2 录制 `sample_export_*` 截图基线并跑通本卡测试、`MotionTokenScanTest`、`:hipda:assembleDebug`、`:hipda:assembleRelease`（确认 debug 工具页完全不进 release 包）与 `:hipda:lintDebug`。
4. 提交并推送 `card/B1b-sample-export`，用 `gh run watch <id> --interval 60` 等待 CI 全绿。
5. 在 `/home/mir/Documents/hipda-next.wt/_reports/B1b.md` 中附上详尽的**用户操作清单**（一次做完：安装 debug 包 → 打开“HiPDA 预览” → 点“样本导出” → 内置 WebView 登录 → 确认“已登录”与版块页抓取成功并留存 M1 验收截图 → 确认 R-B1a 隐私提示并决定是否包含我的帖子/回复/收藏 → 一键抓取并分享 zip → 告知放置目录 `hipda/src/test/resources/samples/`）以及对外说明与测试证据。

【自检】
- 本地按 DISPATCH §2 串行执行（先导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`）：
  - 录制本卡截图：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:recordRoborazziDebug --tests "net.jejer.hipda.debug.sampleexport.SampleExportScreenshotTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 本卡全部测试 + 动效扫描：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.debug.sampleexport.*" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" -Proborazzi.test.verify=true -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 验证 debug/release 编译与 lint：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 每次结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] 全部改动严格在 `hipda/src/debug/` 与 `hipda/src/test/`，未修改 `src/main/` 或共享文件，`assembleRelease` 成功且 release APK 不含 `SampleExportActivity`。证据：`git diff --stat origin/main...HEAD` + `aapt2`/CI 输出。
- [ ] 第 1 步 a–f 全部单测与只读源码扫描测试通过（全 GET、GBK 解码、脱敏零泄漏、泄漏拦截、单项重试、登录 WebView 设置统一 UA、`cdb_auth` 登录态判断、源码无 POST）。证据：测试类:方法名 + CI 汇总。
- [ ] `sample_export_*` Roborazzi 截图基线已提交且 CI verify 通过，既有截图基线零改动；页面上明确展示 R-B1a 建议 8 隐私提示。证据：`git diff --stat origin/main...HEAD -- hipda/src/test/snapshots/`。
- [ ] `_reports/B1b.md` 包含完整“用户操作清单”（含 M1 验收步骤、R-B1a 建议 8 隐私提示及 zip 目标目录说明）。证据：`_reports/B1b.md` 路径与章节。
- [ ] CI 全绿。证据：CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/B1b-sample-export`；Conventional Commits（如 `feat(debug): 新增 debug 样本导出工具页与只读脱敏导出管线`）。
- 详细证据、用户操作清单与对外说明写入 `/home/mir/Documents/hipda-next.wt/_reports/B1b.md`；最后一条回复按文末通用约定控制在 ≤600 字。

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

首先 `cd /home/mir/Documents/hipda-next.wt/B1b`，确认 `git status` 干净、HEAD 为 8999d94ea83ea0ed701929c231b8bd6a1de0f936；首次推送用 `git push -u origin card/B1b-sample-export`。
