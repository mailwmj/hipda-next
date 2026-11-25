# B1b 交付报告：debug 样本导出工具页（内置 WebView 登录 + 只读批量脱敏导出）

- **状态**：DONE
- **分支**：`card/B1b-sample-export`（基于 `origin/main` @ `8999d94ea83ea0ed701929c231b8bd6a1de0f936`）
- **最终 Commit**：`795ee029033072b7bceeb5301a6310eb946713cf`（`feat(debug): 新增 debug 样本导出工具页与只读脱敏导出管线`）
- **CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45570686295 （`Build, Lint & Unit Tests`，Job `139056280106`，6m15s，552/552 单测全过、Roborazzi 截图验证全过、Debug/Release APK 与 Lint 全绿）

---

## 一、验收项逐条证据

### 1. Debug 源集边界与 release 包零残留
- **结果**：通过
- **边界核对**：全部改动严格位于 `hipda/src/debug/` 与 `hipda/src/test/`，零修改 `hipda/src/main/`、`core-parser/`、`MotionTokenScanTest` 白名单及任何共享配置文件（`libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）。`PreviewActivity.kt` 仅在 `SpecimenHeader` 的“外壳”按钮旁新增“样本导出”入口按钮。
- **Debug FileProvider 无冲突**：通过 `SampleExportFileProvider : FileProvider()` 在 `hipda/src/debug/AndroidManifest.xml` 注册专用 authority `${applicationId}.debug.sampleexport.provider`（`exported="false"`、`grantUriPermissions="true"`），与主 Manifest 的 `${applicationId}.provider` 互不冲突；`sample_export_provider_paths.xml` 仅暴露私有目录 `filesDir/sample_exports/`。
- **`git diff --stat origin/main...HEAD`**：
```text
 hipda/src/debug/AndroidManifest.xml                |  17 +
 .../debug/sampleexport/SampleExportActivity.kt     | 124 +++
 .../hipda/debug/sampleexport/SampleExportEngine.kt | 378 +++++++++
 .../debug/sampleexport/SampleExportFileProvider.kt |   9 +
 .../debug/sampleexport/SampleExportManifest.kt     | 101 +++
 .../hipda/debug/sampleexport/SampleExportScreen.kt | 431 +++++++++++
 .../debug/sampleexport/SampleExportViewModel.kt    | 162 ++++
 .../debug/sampleexport/SampleHtmlExtractors.kt     |  97 +++
 .../debug/sampleexport/SampleLoginWebView.kt       | 156 ++++
 .../jejer/hipda/ui/designsystem/PreviewActivity.kt |  17 +-
 .../src/debug/res/values/strings_sample_export.xml |  63 ++
 .../debug/res/xml/sample_export_provider_paths.xml |   6 +
 .../debug/sampleexport/SampleExportEngineTest.kt   | 849 +++++++++++++++++++++
 .../sampleexport/SampleExportReadOnlyScanTest.kt   | 215 ++++++
 .../sampleexport/SampleExportScreenshotTest.kt     | 116 +++
 .../snapshots/images/sample_export_w411_dark.png   | Bin 0 -> 139800 bytes
 .../snapshots/images/sample_export_w411_light.png  | Bin 0 -> 138673 bytes
 17 files changed, 2740 insertions(+), 1 deletion(-)
```
- **Release APK 零残留验证（`aapt2` + APK ZIP/DEX 扫描）**：
  - `aapt2 dump xmltree hipda/build/outputs/apk/release/hipda-ng-release.apk --file AndroidManifest.xml | grep -i "sampleexport\|sample_export"` → 无任何匹配（`NONE`）。
  - Release APK 资源条目命中：`[]`；Release DEX 类名（`SampleExportActivity` / `SampleExportFileProvider` / `SampleExportEngine`）命中：`[]`。
  - Debug APK Manifest 正确包含 `net.jejer.hipda.debug.sampleexport.SampleExportActivity` 与 `net.jejer.hipda.debug.sampleexport.SampleExportFileProvider`（authority `net.jejer.hipda.next.debug.sampleexport.provider`）。

### 2. 单测与只读源码扫描测试（步骤 1 a–f）
- **结果**：通过
- **`SampleExportEngineTest`（9/9 PASSED）**：
  - **a（完整 16 项只读 GET、GBK 解码、动态提取、脱敏零泄漏与 zip 打包）**：
    - `SampleExportEngineTest:fullManifest_executesReadOnlyGetsDecodesGbkExtractsIdsSanitizesAndZipsCleanly`（16 个请求全为 GET、跳过置顶帖与单页帖自动选出多页公开帖 `tid`、提取 `authorUid`/`peerUid`/`Identity`、GBK 转 UTF-8、`HtmlSanitizer` + `SampleLeakScanner.scanDirectory` 0 泄漏、`manifest.json` 无敏感信息）
    - `SampleExportEngineTest:fullManifest_supportsCustomTidAndSkipsSmsDetailWhenInboxEmpty`（自定义公开 `tid` 覆盖；无私信对话时 `sms/sms_detail.html` 标为跳过不阻断导出）
  - **b（泄漏拦截门禁）**：
    - `SampleExportEngineTest:leakGate_rejectsUnsanitizedOutputFromDiskAndZip`（注入未脱敏 `POST_FORM` 输出 → 标为失败并展示 `FORMHASH` 摘要，不落盘、不入 zip，其余干净文件正常打包且 0 泄漏）
  - **c（单项失败、隔离与单项重试）**：
    - `SampleExportEngineTest:singleItemFailureAndRetry_completesOthersThenRetriesFailedItemIntoUpdatedZip`（HTTP 500 失败不阻断其余 15 项；单项重试仅发 1 次 GET 并在成功后重建含全部 16 项的新 zip）
    - `SampleExportEngineTest:smsListFailure_marksSmsDetailFailedInsteadOfSkippedUntilRetried`（前置短消息列表失败时，短消息详情标为失败而非误判无对话跳过，重试列表后再重试详情成功）
  - **d（唯一 `cdb_auth` 登录态判断、WebView 统一 UA 与 Cookie 同步）**：
    - `SampleExportEngineTest:loginStateCheck_dependsSolelyOnNonBlankCdbAuthCookieForForumUrl`
    - `SampleExportEngineTest:loginWebViewConfiguration_setsUnifiedUserAgentEnablesCookiesAndSyncsViaBridge`（断言 `settings.userAgentString == userAgentProvider.userAgent()`、`javaScriptEnabled`、`domStorageEnabled`、`acceptCookie`、`acceptThirdPartyCookies`、危险协议拦截以及 `WebViewCookieBridge.syncFromWebView` 同步 `cdb_auth`/`cdb_sid`/`cf_clearance`）
  - **e（R-B1a 建议 8 隐私勾选项）**：
    - `SampleExportEngineTest:privacyToggleOff_skipsPersonalHistoryRequestsAndExcludesFromZip`（取消勾选后不向 `my.php` 发任何请求，3 项标为跳过且不进入 zip）
  - **Manifest / Provider / Share Intent / Hilt EntryPoint 接线校验**：
    - `SampleExportEngineTest:debugManifestProviderShareIntentAndHiltEntryPoint_areWiredProperly`
- **`SampleExportReadOnlyScanTest`（2/2 PASSED）**：
  - **f（只读红线源码扫描）**：
    - `SampleExportReadOnlyScanTest:sampleExportSources_containZeroWriteOperations`（扫描 `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/` 全部 `.kt` 源码，0 处写操作构造）
    - `SampleExportReadOnlyScanTest:scanner_detectsPostPutDeletePatchFormsAndWriteUrls`（验证对 `.post(`、`.put(`、`.delete(`、`.patch(`、`FormBody`、`MultipartBody`、`GbkForm`、`loginsubmit`、`action=add`、`action=del`、`method("POST")` 的精准拦截）
- **`MotionTokenScanTest`（7/7 PASSED）**：`src/main` 与 `src/debug` 零动效令牌违规，白名单保持为空。
- **回归保护**：`HiThemeScreenshotTest`（12/12 PASSED）、`HiAdaptiveShellTest`（24/24 PASSED），确认 `PreviewActivity.kt` 改动未影响任何既有样张或外壳测试。
- **CI 汇总**：`TOTAL: 552 tests, 552 passed, 0 failed, 0 errors, 0 skipped`。

### 3. Roborazzi 截图基线（2 张 `sample_export_*`，既有基线零改动）
- **结果**：通过
- **`git diff --stat origin/main...HEAD -- hipda/src/test/snapshots/`**：
```text
 .../test/snapshots/images/sample_export_w411_dark.png  | Bin 0 -> 139800 bytes
 .../test/snapshots/images/sample_export_w411_light.png | Bin 0 -> 138673 bytes
 2 files changed, 0 insertions(+), 0 deletions(-)
```
- **`SampleExportScreenshotTest`（2/2 PASSED）**：
  - `sample_export_w411_light`（411dp 浅色核心界面：已登录状态卡片 + R-B1a 建议 8 隐私提示卡片与开关 + 公开 `tid` 输入与导出/分享按钮 + 清单含成功/抓取中/失败 `HiErrorState` 单项重试，且重试按钮触摸目标 ≥ 48dp）
  - `sample_export_w411_dark`（411dp 深色核心界面，同上）

### 4. 本地自检与 CI 全绿
- **本地验证**（遵循 DISPATCH §2 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew ... -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，每次结束后执行 `./gradlew --stop`）：
  - `:hipda:recordRoborazziDebug --tests "net.jejer.hipda.debug.sampleexport.SampleExportScreenshotTest"` → 2/2 PASSED
  - `:hipda:testDebugUnitTest --tests "net.jejer.hipda.debug.sampleexport.*" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" --tests "net.jejer.hipda.ui.designsystem.HiThemeScreenshotTest" --tests "net.jejer.hipda.ui.shell.HiAdaptiveShellTest" -Proborazzi.test.verify=true` → 56/56 PASSED
  - `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug` → BUILD SUCCESSFUL，`lint-results-debug.sarif` 对本卡新增/修改文件零告警（`[]`）。
- **GitHub Actions CI**：
  - Run：https://github.com/mailwmj/hipda-next/actions/runs/45570686295
  - Commit：`795ee029033072b7bceeb5301a6310eb946713cf`
  - Job `Build, Lint & Unit Tests`（`139056280106`）：`success`（6m15s）。

---

## 二、代码规模与自查简化说明

- 本卡含抓取引擎、16 项清单、轻量 HTML 提取器、内置登录 WebView、ViewModel、Compose 工具页 UI 及 Activity/FileProvider，首轮完成后先做了一次自查简化（合并引擎失败/打包分支、合并 ViewModel 抓取/重试协程框架、精简 Compose 模板并消除 `LocalContextGetResourceValueCall` lint 提示），debug 生产 Kotlin 代码由 1793 行降至 **1459 行**（8 个文件）：
  - `SampleExportScreen.kt`：431 行
  - `SampleExportEngine.kt`：378 行
  - `SampleExportViewModel.kt`：162 行
  - `SampleLoginWebView.kt`：156 行
  - `SampleExportActivity.kt`：124 行
  - `SampleExportManifest.kt`：101 行
  - `SampleHtmlExtractors.kt`：97 行
  - `SampleExportFileProvider.kt`：9 行
- **组件与测试纯洁性**：全部使用 Compose 标准组件（`Box`/`Column`/`Row`/`LazyColumn`/`Card`/`Surface`/`OutlinedTextField`/`Button`/`FilledTonalButton`/`OutlinedButton`/`Switch`/`LinearProgressIndicator`/`AndroidView`），直接复用 `HiTheme`、`HiShapeTokens`、`HiErrorState`、`HiStateAction`、`Modifier.hiPressScale`、`HiSnackbarHost` 与 `ChallengeStatusView`；零手写 `Layout`、零手写 `Modifier.Node`、零自定义命令队列、零测试专用语义键/钩子/CompositionLocal。

---

## 三、用户操作清单（一次完成 M1 验收 + 脱敏样本采集）

> **只读安全保证**：本工具页仅发起只读 HTTP GET 请求，不对论坛发帖、回复、发短消息或改动收藏，也不保存您的密码。所有导出的 HTML 均经过 `HtmlSanitizer` 脱敏与 `SampleLeakScanner` 零泄漏门禁校验；未通过校验的文件会被拦截，不会写入磁盘也不会进入压缩包。

### 步骤 1：安装 Debug 包并进入工具页
1. 从最新构建（CI Artifacts `debug-apk` 或本地 `:hipda:assembleDebug` 输出 `hipda/build/outputs/apk/debug/hipda-debug.apk`）安装 debug 包（包名 `net.jejer.hipda.next`，可与旧版并存）。
2. 在手机桌面打开第二个启动图标 **「HiPDA 预览」**（`PreviewActivity`）。
3. 点击右上角 **「样本导出」** 按钮（位于“外壳”按钮旁），进入样本导出工具页。

### 步骤 2：内置 WebView 登录与 Cookie 同步
1. 在顶部 **「登录状态」** 卡片确认当前状态；若显示 **「未登录」**，点击 **「登录」** 打开内置 WebView（加载 `https://www.4d4y.com/forum/logging.php?action=login`，与网络层共用同一 UA）。
2. 如遇到 Cloudflare 安全验证，请先在 WebView 内完成验证，随后正常输入账号密码登录论坛（应用不记录密码）。
3. 登录跳转回论坛首页或版块后，点击右上角 **「完成登录」**（页面跳转时也会自动同步 Cookie；也可在主页点 **「同步状态」** 手动刷新）。
4. 确认顶部状态标签变为 **「已登录」**（依据 `SiteCookieStore` 中是否存在非空 `cdb_auth` 判断）。

### 步骤 3：核对隐私提示（R-B1a 建议 8）与可选 `tid`
1. 请阅读页面上高亮的 **「隐私提示」** 卡片：
   - **重要说明**：`simple_list/my_threads.html`（我的帖子）、`simple_list/my_posts.html`（我的回复）、`simple_list/my_favorites.html`（我的收藏）虽然已移除账号名、UID 与令牌，但**仍保留真实帖子编号（`tid`）与楼层编号（`pid`），可在公开论坛反查到发帖人**。
   - **建议**：若不希望暴露关联关系，可**关闭「包含我的帖子、我的回复与收藏」开关**（关闭后这 3 项不发请求也不进压缩包），或使用**测试账号**导出，或在上方「自定义公开帖子编号（可选）」中手动填入一个公开普通多页帖子的 `tid`（留空则自动从 D 版块第 1 页挑选公开多页普通帖）。

### 步骤 4：一键抓取、完成 M1 验收并导出压缩包
1. 点击 **「一键抓取并导出」**。若请求触发 Cloudflare 验证，应用会自动弹出 A5b/A6 验证通道，按提示完成即可，完成后会自动继续抓取。
2. **M1 阶段验收确认**：
   - 观察清单第 1 项 **「1. 版块列表 · 第 1 页（按回复）」**（`thread_list/forum_2_p1_lastpost.html`）变为 **「成功」** 并显示文件大小，即证明新网络层 + Cloudflare 验证通道已在真实站点打通。
   - **请截取一张当前工具页截图留存作为 M1 验收证据**（包含顶部“已登录”及第 1 项“成功”）。
3. 查看其余条目状态：
   - 若账号无私信对话，第 8 项 **「短消息对话详情」** 会自动标为 **「已跳过」**，不影响整体导出。
   - 若个别条目因网络波动或验证超时标为 **「失败」**，可直接在卡片内的错误视图点击 **「重试」** 做单项补抓（重试成功后会自动重建包含该项的新压缩包）。
   - 若某条目提示 **「脱敏校验未通过」**，可点「查看详情」复制泄漏类型反馈给开发团队，该文件不会被写入压缩包。
4. 全部完成后，点击 **「分享」** 按钮，通过系统分享把 `hipda-samples-<时间戳>.zip` 发送到电脑（压缩包位于应用私有目录 `filesDir/sample_exports/`）。

### 步骤 5：放置样本目录与复检
1. 在开发机将收到的 `hipda-samples-<时间戳>.zip` 解压到仓库目录：
   - **目标目录**：`hipda/src/test/resources/samples/`
   - 解压后应形成如下结构（与 `SamplesAreSanitizedTest` 扫描规则完全对齐）：
     - `thread_list/forum_2_p1_lastpost.html`、`forum_2_p2_lastpost.html`、`forum_2_p1_dateline.html`
     - `thread_detail/thread_p1.html`、`thread_p2.html`、`thread_author_only.html`
     - `sms/sms_list.html`、`sms_detail.html`（无私信时跳过）
     - `notify/notify_list.html`
     - `search/search_result.html`
     - `user_info/user_profile.html`
     - `simple_list/my_threads.html`、`my_posts.html`、`my_favorites.html`（若关闭隐私开关则不含）
     - `post_form/newthread_fid2.html`
     - `login/login_page.html`
     - `manifest.json`（各条状态与脱敏确认摘要，不含敏感信息；提交样本前可保留或仅保留 `.html` 文件）
2. 提交样本到仓库前，建议在仓库根目录跑一次脱敏扫描复检：
   ```bash
   ./gradlew :core-parser:test --tests "net.jejer.hipda.parser.sanitize.SamplesAreSanitizedTest"
   ```
   通过即确认所有样本 0 泄漏，随后可解锁 B2–B6 现有解析器回归测试。

---

## 四、对外 API 清单（`src/debug`，仅供 debug 工具页与单测使用）

### 1. 清单与登录态工具（`net.jejer.hipda.debug.sampleexport`）
- `object SampleExportConstants` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportManifest.kt:11`
  - `FORUM_BASE_URL` / `LOGIN_URL` / `EXPORT_DIR_NAME` / `PROVIDER_AUTHORITY_SUFFIX` / `AUTH_COOKIE_NAME`
- `enum class SampleEntryId(@param:StringRes val titleRes: Int)` — `SampleExportManifest.kt:25`
- `data class SampleSpec(val id: SampleEntryId, val relativePath: String, val pageType: PageType, val isPersonalHistory: Boolean = false)` — `SampleExportManifest.kt:45`
- `object SampleExportManifest` — `SampleExportManifest.kt:55`（16 条只读样本规范与 `initialItems()`）
- `sealed interface SampleExportStatus`（`Pending` / `Fetching` / `Success` / `Skipped` / `Failed`）— `SampleExportManifest.kt:79`
- `data class SampleExportItemState(val spec: SampleSpec, val status: SampleExportStatus = SampleExportStatus.Pending)` — `SampleExportManifest.kt:88`
- `fun isForumLoggedIn(siteCookieStore: SiteCookieStore, baseUrl: HttpUrl = SampleExportConstants.FORUM_BASE_URL): Boolean` — `SampleExportManifest.kt:96`

### 2. 轻量 HTML 动态 ID 提取器
- `object SampleHtmlExtractors` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleHtmlExtractors.kt:11`
  - `fun extractIdentity(html: String): Identity?` — `SampleHtmlExtractors.kt:19`
  - `fun extractMultiPagePublicTid(html: String): String?` — `SampleHtmlExtractors.kt:36`
  - `fun extractFirstPostAuthorUid(html: String): String?` — `SampleHtmlExtractors.kt:62`
  - `fun extractFirstSmsPeerUid(html: String, selfUid: String? = null): String?` — `SampleHtmlExtractors.kt:69`

### 3. 只读抓取、脱敏、零泄漏门禁与 zip 打包引擎
- `data class SampleExportRunResult(val items: List<SampleExportItemState>, val zipFile: File?, val resolvedTid: String?)` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportEngine.kt:32`
- `class SampleExportEngine(...)` — `SampleExportEngine.kt:62`
  - `fun currentItems(): List<SampleExportItemState>` — `SampleExportEngine.kt:85`
  - `fun latestZipFile(): File?` — `SampleExportEngine.kt:88`
  - `suspend fun runAll(includePersonalHistory: Boolean = true, customTid: String? = null, onItemUpdated: (SampleExportItemState) -> Unit = {}): SampleExportRunResult` — `SampleExportEngine.kt:90`
  - `suspend fun retryEntry(id: SampleEntryId, includePersonalHistory: Boolean = true, customTid: String? = null, onItemUpdated: (SampleExportItemState) -> Unit = {}): SampleExportRunResult` — `SampleExportEngine.kt:110`

### 4. 内置登录 WebView、ViewModel、Compose UI 与宿主入口
- `fun configureSampleLoginWebView(webView: WebView, userAgentProvider: UserAgentProvider, onPageNavigationChanged: (String?) -> Unit): WebView` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleLoginWebView.kt:49`
- `data class SampleExportUiState(...)` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportViewModel.kt:22`
- `class SampleExportViewModel(...)` — `SampleExportViewModel.kt:36`
- `@Composable fun SampleExportScreen(...)` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportScreen.kt:61`
- `@EntryPoint @InstallIn(SingletonComponent::class) interface SampleExportEntryPoint` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportActivity.kt:30`
- `fun createSampleExportShareIntent(context: Context, zipFile: File): Intent` — `SampleExportActivity.kt:37`
- `class SampleExportActivity : ComponentActivity()` — `SampleExportActivity.kt:49`
- `class SampleExportFileProvider : FileProvider()` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportFileProvider.kt:9`

---

## 五、修复轮 1（落实 R-B1b 建议 S1–S5）

- **新 Commit**：`b9c9cc2b5d903eea59a0cc58aa8227f86dac2d6a`（`fix(debug): 落实 R-B1b 建议 S1–S5（隐私开关重打包、身份补全回溯脱敏、登录态与正整数拦截、跨行只读扫描）`）
- **CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45576607291 （`Build, Lint & Unit Tests`，Job `139076003907`，4m42s，`TOTAL: 557 tests, 557 passed, 0 failed, 0 errors, 0 skipped`，Roborazzi 截图校验、Debug/Release APK 与 Lint 全绿）
- **本地验证**：`:hipda:testDebugUnitTest` + `-Proborazzi.test.verify=true` + `:hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug` 全通过；`lint-results-debug.sarif` 对本卡文件零告警（`[]`），Release APK 零残留；debug 生产 Kotlin 代码共 1613 行（8 文件），零测试专用方法/钩子。

### 逐项改动与对应测试证据

1. **S1（事后切换隐私开关的 zip 过滤与缓存失效）**：
   - `SampleExportEngine.kt`：在 `writeZipIfAny` 写入条目时强制过滤 `!(spec.isPersonalHistory && !includePersonalHistory)`；在 `retryEntry` 入口调用 `enforcePersonalHistoryPolicy` 同步清除已缓存的 `my_*` 并标为跳过；新增 `updatePersonalHistoryInclusion`（关闭时清除内存缓存与旧 zip 并立即基于剩余已校验条目重打包无 `my_*` 的新 zip；重新开启时使旧 zip 失效并将被跳过条目重置为 `Pending`）。
   - `SampleExportViewModel.kt`：`onIncludePersonalHistoryChange` 同步置空 `exportedZipFile` 阻断旧包分享，再调用 `engine.updatePersonalHistoryInclusion` 更新状态与归档。
   - **对应测试**：`SampleExportEngineTest:privacyToggleTurnedOffAfterFullExport_rebuildsZipWithoutPersonalHistoryAndInvalidatesCache`（覆盖“先抓完 16 项 → 关隐私开关 → 分享新 zip 无 `my_*` 且旧 zip 已删 → 单项重试无关项仍无 `my_*` → 重开开关旧 zip 失效且 `my_*` 重置待抓取”）。
2. **S3（完整身份门禁、回溯重新脱敏与 `parseInt` 提取）**：
   - `SampleHtmlExtractors.kt`：将 `DISCUZ_UID_REGEX` 升级支持 `parseInt('...')`、`Number("...")` 与 JSON `"discuz_uid": "..."`。
   - `SampleExportEngine.kt`：`updateDynamicContext` 改为在 `uid` 或 `username` 任一缺失时持续补全；身份未补全（`uid.isBlank() || username.isBlank()`）时拒绝放行（不进 `verifiedHtmlById`、不落盘、不入 zip），仅暂存原始 HTML 于内存 `pendingIdentityRawHtmlById`；一旦后续页面补齐完整 `Identity(uid, username)`，立即触发 `resanitizePendingEntries` 对所有更早条目回溯重跑 `HtmlSanitizer.sanitize` + `SampleLeakScanner.scan` 并清理临时原文。
   - **对应测试**：`SampleExportEngineTest:incompleteIdentity_blocksReleaseWhenUsernameMissing_andRetroactivelyResanitizesWhenCompletedLater`（覆盖 `parseInt`/`Number`/JSON 提取、全程缺 `username` 时 0 放行且无 zip、首页缺 `#umenu` 后续补全时首页被回溯脱敏消除真实用户名并通过 `SampleLeakScanner.scanDirectory`）。
3. **S4（未登录态禁用抓取/导出与 `customTid` 正整数校验）**：
   - `SampleExportScreen.kt`：`!state.isLoggedIn` 时禁用“一键抓取并导出”与“分享”按钮。
   - `SampleExportViewModel.kt`：`runExportTask`（覆盖 `startBatchExport` 与 `retryItem`）实时核验 `isForumLoggedIn`，未登录直接拦截并弹出 `sample_export_feedback_login_required` 提示；`onCustomTidChange` 与 `SampleHtmlExtractors.normalizePositiveTid` 严格要求正整数 `^[1-9]\d*$`（拒绝 `0`、`000`、`012`、负数、非数字）。
   - **对应测试**：`SampleExportEngineTest:loggedOutState_blocksFetchAndExport_andCustomTidRequiresPositiveInteger`。
4. **S5（关闭登录 WebView 前同步 Cookie 与空 scheme 默认拦截）**：
   - `SampleExportViewModel.kt`：`closeLoginWebView()` 改为调用 `syncCookiesFromWebView(showFeedback = false, closeWebViewAfterSync = true)`，先同步 Cookie 落库再关闭浮层并刷新登录态。
   - `SampleLoginWebView.kt`：`shouldOverrideUrlLoading` 中 `request.url?.scheme` 为 `null` 时改为 `return true` 默认拦截。
   - **对应测试**：`SampleExportEngineTest:closeLoginWebView_syncsCookiesBeforeClosing_andNullSchemeIsBlocked`。
5. **S2（跨行全文件只读源码扫描加固 + 单行改坏自证）**：
   - `SampleExportReadOnlyScanTest.kt`：升级为剥离注释后的全文件跨行扫描并映射精确行号；新增拦截 `.method(`（含跨行与变量参数）、`Request(method=...)`、`toRequestBody`、`evaluateJavascript`、`loadUrl("javascript:...")`、`HttpURLConnection`/`openConnection(`、URL 与 Pair 形式的 `action=add/del/remove/addblack/delblack/logout`、以及 `topicsubmit`/`deletesubmit`。
   - **对应测试**：
     - `SampleExportReadOnlyScanTest:sampleExportSources_containZeroWriteOperations`
     - `SampleExportReadOnlyScanTest:scanner_detectsMultilineMethodVariableFormsPairActionsJsAndHttpUrlConnection`
     - `SampleExportReadOnlyScanTest:mutationSelfProof_mutatingSingleLineInRealEngineImmediatelyFailsScan`（读取真实生产文件 `SampleExportEngine.kt` 在内存中分别改坏 1 行为跨行 `.method(`、`action=remove`、`topicsubmit`，均立即变红）。
