# 任务卡 B9a 交付报告

- **分支**：`card/B9a-parser-core-auth-msg`（基线 `origin/main` @ `c90b3f6888ff0993c8ac1fc289d4bfbab5c3c0c9`）
- **最终 Commit**：`d1263a5dfd219a2b2a74515979e325755529fd79`
- **CI Run**：[Android CI #45603979935](https://github.com/mailwmj/hipda-next/actions/runs/45603979935)（`completed / success`，Build、Lint、Room Schema、`:core-parser:test`、`:hipda:testDebugUnitTest`、Roborazzi 与 APK 产出全部通过）

---

## 一、改动范围与只读边界核查

### 1.1 `git diff --stat origin/main...HEAD`
共 20 个文件改动（`+2282 / -7`）：
- `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt`（R-B1c S1）
- `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SampleLeakScanner.kt`（R-B1c S2）
- `core-parser/src/main/kotlin/net/jejer/hipda/parser/common/{DiscuzCommonModels,DiscuzCommonParser,DiscuzTextDecoder,DiscuzUrlHelper}.kt`
- `core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/{LoginModels,LoginParser}.kt`
- `core-parser/src/main/kotlin/net/jejer/hipda/parser/message/{SmsModels,SmsParser,NotifyModels,NotifyParser}.kt`
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/SampleFixtures.kt`
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/sanitize/HtmlSanitizerTest.kt`
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/common/DiscuzCommonParserTest.kt`
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/auth/LoginParserTest.kt`
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/message/{SmsParserTest,NotifyParserTest}.kt`
- `hipda/src/test/java/net/jejer/hipda/parser/LegacyTestSupport.kt`
- `hipda/src/test/java/net/jejer/hipda/parser/AuthAndMessageParserEquivalenceTest.kt`

### 1.2 只读与零冲突守卫
- `git diff -- hipda/src/main/`：为空（全部旧解析器 `HiParser*.java`、`LoginHelper.java`、`PostSmsAsyncTask.java`、`NotiHelper.java`、旧 Bean、`ui/`、`data/`、`di/`、`AndroidManifest.xml` 均零改动）
- `git diff -- hipda/src/test/resources/samples/`：为空（16 份真机样本零改动）
- 共享文件（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`.github/`、`MotionTokenScanTest`、`src/debug/`、`src/test/snapshots/`）：零改动
- `:core-parser` 保持纯 JVM：零 Android 依赖、零线程启动、零设置读写、绝不修改调用方传入的 Jsoup `Document` DOM（均在测试中断言调用前后 `doc.outerHtml()` 一字不差）。
- 测试全程离线：`LegacyTestSupport` 在初始化及每次 `suppressBackgroundNotifyFetch()` 同步调用 `HiParserThreadList.holdFetchNotify()` 与 `HiSettingsHelper.getInstance().setLastCheckSmsTime(System.currentTimeMillis())`，彻底阻断旧解析器内 `ParseNotifyRunnable` 后台线程及 `NotiHelper.fetchNotification(doc)` 发起任何真实网络请求。

---

## 二、R-B1c S1 / S2 闭环证据

### 2.1 S1：`SanitizeSelectors.buildStandaloneUidRegex`（`SanitizeSelectors.kt:166`）
- 改动：将左边界 `(?<!\d)` 收紧为 `(?:(?<!\d)|(?<=%[0-9A-Fa-f]{2}))`，使末位为数字的百分号转义（如 `%20`、`%22`、`%26`）紧邻本人 UID 时同样被脱敏替换与泄漏扫描捕获，同时保持更长纯数字（含 `94242420`、`1424242`、`4242429`、`%204242429`、`%221424242%22`、`%3D4242420`）不被误伤。
- 新增单测：`HtmlSanitizerTest.rB1cS1SanitizesAndScansSelfUidAdjacentToPercentEncodedSequencesWithoutOvermatchingLongerNumbers`。

### 2.2 S2：`SampleLeakScanner` 三入口 fail-closed（`SampleLeakScanner.kt:97-143`）
- 改动：新增 `normalizeAndValidateSelfUid(selfUid: String?)`，在 `scan`、`scanDirectory`、`scanDirectories` 三入口最前面执行校验（早于空目录/不存在目录/空列表提前返回）：当 `selfUid != null && selfUid.trim().isNotEmpty()` 且含非 ASCII 数字字符时，立即抛出 `IllegalArgumentException`（错误消息仅含长度，不暴露原始敏感值）；`null`、空串、纯空白及 `"10000"` 等纯数字占位 UID 保持既有行为。
- 新增单测：`HtmlSanitizerTest.rB1cS2SampleLeakScannerFailsClosedOnNonNumericSelfUidAcrossAllEntryPoints`。

---

## 三、逐字段新旧等价对照表

| 模块 / 场景 | 样本 / 夹具 | 旧解析器（只读基准） | 新解析器（`:core-parser`） | 逐字段比对结论 |
|---|---|---|---|---|
| **登录与页头（真机）** | `login/login_page.html` | `LoginHelper.checkLoggedin=true`、`HiParser.parseFormhash=null`、`parseErrorMessage=null`、`NotiHelper.threadCount=0` | `DiscuzCommonParser.parsePageMeta` + `LoginParser.parseLoginPage` → `AlreadyLoggedIn(session=(true,"10000","HiUser"), logoutFormhash="00000000")` | 逐字段一致；`parseFormhash=null`、`parseLogoutFormhash="00000000"`、`promptCounts.notifyTotal=0` |
| **登录表单/报错/计数/XML（合成）** | 未登录页、`div.alert_error`、`div.promptcontent(2,1,3,4,5)`、登录 XML ×4 | `checkLoggedin=false`、`parseFormhash="abcd1234"`、`parseErrorMessage="对不起，您没有权限访问该版块"`（删 `<a>`）、`NotiHelper.threadCount=12`、登录 XML 4 分支 | `LoginFormParseResult.Ready("abcd1234")`、`parseErrorMessage`（**不修改 DOM**）、`PromptCounts(2,1,3,4,5)` (`notifyTotal=12`)、`LoginSubmitResult` (`Success`/`Failed(msg,4)`/`Failed(default,null)`/`UnknownError`) | 逐字段一致；新解析器零 DOM 副作用 |
| **短消息列表（真机）** | `sms/sms_list.html`（50 条） | `HiParser.parseSimpleList(TYPE_SMS)` / `parseSMS`：50 条、`uid="20000"`、`author="HiPeer"`、`avatarUrl` (`small→middle`)、`time`、`title`、`isNew=false` | `SmsParser.parseSmsList`：`hasContainer=true`、50 条、`uid`/`author`/`avatarUrl`/`time`/`summary`/`isNew` | 50 条全量逐条逐字段完全一致 |
| **短消息列表（合成）** | 未读红点 `notice_newpm.gif`、缺失容器、`noavatar` | 未读 `isNew=true`；无 `ul.pm_list` 返回 `null`；**已知旧 Bean 缺陷**：`SimpleListItemBean.setAvatarUrl` 缺 `else` 导致 `noavatar_small.gif` 被覆写为 `noavatar_middle.gif` | 未读 `isNew=true` 逐字段一致；无容器 `hasContainer=false`；`noavatar` 按规范返回 `null` | 未读与容器态一致；`noavatar` 修复旧 Bean 缺 `else` 缺陷并在单测中显式锁定 |
| **短消息详情（真机）** | `sms/sms_detail.html`（9 条：4 本人 + 5 对方） | `HiParser.parseSimpleList(TYPE_SMS_DETAIL)` + `parseFormhash="00000000"`：9 条 `uid`/`author`/`avatarUrl`/`time`/`info`/`isNew` | `SmsParser.parseSmsDetail(doc, HiUtils.AvatarBaseUrl)`：`myUid="10000"`、`myUsername="HiUser"`、`formhash="00000000"`、9 条 `uid`/`author`/`avatarUrl`/`time`/`contentHtml`/`isNew`/`isMine` | 9 条全量逐条逐字段完全一致（不传 `avatarBaseUrl` 时返回相对路径 `000/01/00/00_avatar_middle.jpg`） |
| **短消息发送 XML（合成）** | 成功含 `class="summary"`、失败带 `<root>` 提示、空响应、无文案响应 | 对照 `PostSmsAsyncTask.java:90-109` 4 分支 | `SmsParser.parseSmsSendResult`：`Success` / `Failed(message)` | 4 分支状态与错误文案逐字一致 |
| **提醒列表空态（真机）** | `notify/notify_list.html` | `HiParser.parseNotify=null`、`NotiHelper.threadCount=0` | `NotifyParser.parseNotifyList`：`hasFeedContainer=false`、`items=[]`、`promptCounts.notifyTotal=0` | 完全一致 |
| **提醒列表 4 支 / 5 类（合成）** | `ul.feed`：`f_thread`（普通+关注）、`f_quote`、`f_reply`、`f_manage`、`f_buddy`（共 6 条） | `HiParser.parseNotify`：6 条（其中 `f_manage` 使用 `GlideHelper.SYSTEM_AVATAR_FILE.absolutePath`，`f_buddy` 删原 DOM 第 2 个 `<a>`，`f_quote`/`f_reply` 漏调用 `setUid`） | `NotifyParser.parseNotifyList(doc, HiUtils.AvatarBaseUrl)`：`THREAD`/`QUOTE`/`REPLY`/`SYSTEM`/`FRIEND` 6 条，完整保留 `quoteOriginal`/`quoteReply` 与 `authorUid`，**不修改 DOM** | `title`/`info`/`author`/`tid`/`pid`/`time`/`isNew` 逐条完全一致；`SYSTEM` 刻意去 Android 本地路径置 `avatarUrl=null`，其余 `avatarUrl` 一致；`authorUid` 准确补齐 `QUOTE`/`REPLY` |

---

## 四、单行变异自证日志

全部 4 组单行变异均在临时修改后立即触发测试红灯，并在 `finally` 中完整还原（最终提交零变异）：

| 编号 | 变异目标与单行改动 | 运行任务 | 退出码 | 立刻变红的测试 |
|---|---|---|---|---|
| **M1** | `SanitizeSelectors.kt:166` 将 `(?:(?<!\d)|(?<=%[0-9A-Fa-f]{2}))` 改回 `(?<!\d)` | `:core-parser:test --tests HtmlSanitizerTest` | `1` | `HtmlSanitizerTest > rB1cS1SanitizesAndScansSelfUidAdjacentToPercentEncodedSequencesWithoutOvermatchingLongerNumbers()` |
| **M2** | `SampleLeakScanner.kt:138` 将 `require(trimmed.all { it in '0'..'9' })` 改为静默 `return null` | `:core-parser:test --tests HtmlSanitizerTest` | `1` | `HtmlSanitizerTest > rB1cS2SampleLeakScannerFailsClosedOnNonNumericSelfUidAcrossAllEntryPoints(Path)` |
| **M3** | `DiscuzTextDecoder.kt:16` 将 `if (!text.contains("&#")) return text` 改为无条件 `return text` | `:core-parser:test --tests DiscuzCommonParserTest` | `1` | `DiscuzCommonParserTest > commonParser_parsesErrorMessageWithoutMutatingDomAndParsesPromptCounts()`、`textDecoder_decodesDoubleEscapedDecimalAndHexEntitiesIncludingSupplementaryEmoji()` |
| **M4** | `NotifyParser.kt:55` 将 `tid = DiscuzUrlHelper.extractQueryParam(href, "ptid")` 改为 `tid = null` | `:hipda:testDebugUnitTest --tests AuthAndMessageParserEquivalenceTest` | `1` | `AuthAndMessageParserEquivalenceTest > notifyEmptySampleAndSyntheticAllFourBranches_legacyAndNewAreFieldByFieldEquivalent` |

---

## 五、本地自检结果（按 DISPATCH §2 串行加锁执行）

1. `:core-parser:test --rerun-tasks`：**51/51 通过**（`CoreParserPlaceholderTest` 1、`DiscuzCommonParserTest` 7、`LoginParserTest` 3、`SmsParserTest` 5、`NotifyParserTest` 3、`HtmlSanitizerTest` 27、`SamplesAreSanitizedTest` 5）
2. `:hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.*" --tests "net.jejer.hipda.debug.sampleexport.*"`：**24/24 通过**（`AuthAndMessageParserEquivalenceTest` 5、`SampleExportEngineTest` 14、`SampleExportReadOnlyScanTest` 3、`SampleExportScreenshotTest` 2）
3. `:hipda:assembleDebug :hipda:lintDebug`：**通过**（`BUILD SUCCESSFUL`）
4. `./gradlew --stop`：已停止后台 Gradle Daemon。

---

## 六、对外 API 清单（供 `B7` / `B8` / `B9b` / `D2` / `D7` 复用）

### 6.1 公共基座（`net.jejer.hipda.parser.common`）

- **`DiscuzCommonModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/common/DiscuzCommonModels.kt`)
  - `data class DiscuzSessionInfo(val isLoggedIn: Boolean, val uid: String?, val username: String?)` (`:3-7`)
  - `data class PromptCounts(val pm: Int = 0, val announcePm: Int = 0, val systemPm: Int = 0, val friend: Int = 0, val thread: Int = 0)` (`:9-19`)
    - `val notifyTotal: Int`（`= thread + systemPm + friend`，`:17-18`）
  - `data class DiscuzPageMeta(val session: DiscuzSessionInfo, val formhash: String?, val errorMessage: String?, val promptCounts: PromptCounts)` (`:21-26`)

- **`DiscuzCommonParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/common/DiscuzCommonParser.kt`)
  - `object DiscuzCommonParser` (`:10`)
    - `fun parsePageMeta(html: String): DiscuzPageMeta` (`:12`)
    - `fun parsePageMeta(doc: Document): DiscuzPageMeta` (`:14`)
    - `fun parseSession(doc: Document): DiscuzSessionInfo` (`:21`)
    - `fun parseFormhash(doc: Document): String?` (`:45`)
    - `fun parseLogoutFormhash(doc: Document): String?` (`:60`)
    - `fun parseErrorMessage(doc: Document): String?` (`:71`)
    - `fun parsePromptCounts(doc: Document): PromptCounts` (`:81`)

- **`DiscuzTextDecoder.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/common/DiscuzTextDecoder.kt`)
  - `object DiscuzTextDecoder` (`:11`)
    - `fun decodeDoubleEscapedEntities(text: String): String` (`:15`)

- **`DiscuzUrlHelper.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/common/DiscuzUrlHelper.kt`)
  - `object DiscuzUrlHelper` (`:10`)
    - `const val AVATAR_SUFFIX_MIDDLE = "_avatar_middle.jpg"` (`:12`)
    - `const val NEW_PM_IMAGE_MARKER = "images/default/notice_newpm.gif"` (`:13`)
    - `fun middleString(source: String?, start: String, end: String = ""): String` (`:22`)
    - `fun extractQueryParam(url: String?, name: String): String?` (`:39`)
    - `fun avatarRelativePathForUid(uid: String?): String?` (`:65`)
    - `fun avatarUrlForUid(uid: String?, avatarBaseUrl: String? = null): String?` (`:79`)
    - `fun normalizeAvatarUrl(raw: String?): String?` (`:89`)
    - `fun isValidId(id: String?): Boolean` (`:97`)
    - `fun parseIntOrZero(raw: String?): Int` (`:103`)
    - `fun extractFirstIntOrZero(raw: String?): Int` (`:105`)
  - 顶层扩展：
    - `fun Element.firstMatch(selector: String): Element?` (`:120`)
    - `fun Elements.firstElementOrNull(): Element?` (`:122`)
    - `fun Elements.lastElementOrNull(): Element?` (`:124`)

### 6.2 登录解析（`net.jejer.hipda.parser.auth`）

- **`LoginModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginModels.kt`)
  - `sealed interface LoginFormParseResult` (`:5`)
    - `data class Ready(val formhash: String) : LoginFormParseResult` (`:6`)
    - `data class AlreadyLoggedIn(val session: DiscuzSessionInfo, val logoutFormhash: String?) : LoginFormParseResult` (`:8-11`)
    - `data class Error(val message: String) : LoginFormParseResult` (`:13`)
  - `sealed interface LoginSubmitResult` (`:16`)
    - `data class Success(val message: String) : LoginSubmitResult` (`:17`)
    - `data class Failed(val message: String, val remainingAttempts: Int?) : LoginSubmitResult` (`:19-22`)
    - `data class UnknownError(val rawSnippet: String) : LoginSubmitResult` (`:24`)

- **`LoginParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt`)
  - `object LoginParser` (`:13`)
    - `fun parseLoginPage(html: String): LoginFormParseResult` (`:24`)
    - `fun parseLoginPage(doc: Document): LoginFormParseResult` (`:26`)
    - `fun parseLoginSubmitResponse(xml: String): LoginSubmitResult` (`:53`)

### 6.3 短消息与提醒解析（`net.jejer.hipda.parser.message`）

- **`SmsModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/message/SmsModels.kt`)
  - `data class SmsConversationItem(val uid: String, val author: String, val avatarUrl: String?, val time: String, val summary: String, val isNew: Boolean)` (`:5-12`)
  - `data class SmsListPage(val items: List<SmsConversationItem>, val meta: DiscuzPageMeta, val hasContainer: Boolean)` (`:14-18`)
  - `data class SmsMessageItem(val uid: String, val author: String, val avatarUrl: String?, val time: String, val contentHtml: String, val isNew: Boolean, val isMine: Boolean)` (`:20-28`)
  - `data class SmsDetailPage(val myUid: String?, val myUsername: String?, val formhash: String?, val messages: List<SmsMessageItem>, val meta: DiscuzPageMeta, val hasContainer: Boolean = true)` (`:30-37`)
  - `sealed interface SmsSendResult` (`:39`): `data object Success` (`:40`), `data class Failed(val message: String)` (`:41`)

- **`SmsParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/message/SmsParser.kt`)
  - `object SmsParser` (`:15`)
    - `fun parseSmsList(html: String): SmsListPage` (`:21`)
    - `fun parseSmsList(doc: Document): SmsListPage` (`:23`)
    - `fun parseSmsDetail(html: String, avatarBaseUrl: String? = null): SmsDetailPage` (`:56`)
    - `fun parseSmsDetail(doc: Document, avatarBaseUrl: String? = null): SmsDetailPage` (`:59`)
    - `fun parseSmsSendResult(xml: String): SmsSendResult` (`:139`)

- **`NotifyModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/message/NotifyModels.kt`)
  - `enum class NotifyKind { THREAD, QUOTE, REPLY, SYSTEM, FRIEND }` (`:5-11`)
  - `data class NotifyItem(val kind: NotifyKind, val title: String, val info: String, val author: String?, val authorUid: String?, val avatarUrl: String?, val tid: String?, val pid: String?, val time: String?, val isNew: Boolean, val quoteOriginal: String? = null, val quoteReply: String? = null)` (`:13-26`)
  - `data class NotifyListPage(val items: List<NotifyItem>, val meta: DiscuzPageMeta, val hasFeedContainer: Boolean)` (`:28-32`)

- **`NotifyParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/message/NotifyParser.kt`)
  - `object NotifyParser` (`:15`)
    - `fun parseNotifyList(html: String, avatarBaseUrl: String? = null): NotifyListPage` (`:22`)
    - `fun parseNotifyList(doc: Document, avatarBaseUrl: String? = null): NotifyListPage` (`:25`)

### 6.4 跨卡复用测试基座

- **`SampleFixtures.kt`** (`core-parser/src/test/kotlin/net/jejer/hipda/parser/SampleFixtures.kt`)
  - `object SampleFixtures` (`:17`)
    - `const val LOGIN_PAGE = "login/login_page.html"` (`:19`)
    - `const val SMS_LIST = "sms/sms_list.html"` (`:20`)
    - `const val SMS_DETAIL = "sms/sms_detail.html"` (`:21`)
    - `const val NOTIFY_LIST = "notify/notify_list.html"` (`:22`)
    - `fun samplesDir(): Path` (`:26`)
    - `fun samplePath(relativePath: String): Path` (`:34`)
    - `fun loadSample(relativePath: String): String` (`:43`)
    - `fun parseSampleDocument(relativePath: String): Document` (`:48`)
    - `fun parseXmlDocument(xml: String): Document` (`:50`)

- **`LegacyTestSupport.kt`** (`hipda/src/test/java/net/jejer/hipda/parser/LegacyTestSupport.kt`)
  - `object LegacyTestSupport` (`:31`)
    - `fun initLegacyEnvironment(context: Context = ApplicationProvider.getApplicationContext()): Context` (`:33`)
    - `fun suppressBackgroundNotifyFetch()` (`:52`)
    - `fun loadSample(relativePath: String): String` (`:57`)
    - `fun parseSampleDocument(relativePath: String): Document` (`:69`)
