# 任务卡 B9a：`:core-parser` 公共基座 + 登录/短消息/提醒解析（含旧回归与 R-B1c S1/S2）

【角色】你是 hipda-next 的 Kotlin 纯 JVM HTML/XML 解析与测试开发者（Kotlin、Jsoup、JUnit 5、Robolectric）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/B9a`（分支 `card/B9a-parser-core-auth-msg`，基于 `origin/main` @ `c90b3f6888ff0993c8ac1fc289d4bfbab5c3c0c9`）里工作。
- 允许修改/新增的范围：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt`（仅 R-B1c S1）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SampleLeakScanner.kt`（仅 R-B1c S2）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/common/`（新增公共基座：`DiscuzCommonModels.kt`、`DiscuzCommonParser.kt`、`DiscuzTextDecoder.kt`、`DiscuzUrlHelper.kt`）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/`（新增 `LoginModels.kt`、`LoginParser.kt`）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/message/`（新增 `SmsModels.kt`、`SmsParser.kt`、`NotifyModels.kt`、`NotifyParser.kt`）
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/SampleFixtures.kt`（新增供 `B7`/`B8`/`B9b` 复用的样本加载器）
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/sanitize/HtmlSanitizerTest.kt`（补 R-B1c S1/S2 单测）
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/{common,auth,message}/*Test.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/LegacyTestSupport.kt`（新增供 `B7`/`B8`/`B9b` 复用的 Robolectric 旧解析器测试辅助）
  - `hipda/src/test/java/net/jejer/hipda/parser/AuthAndMessageParserEquivalenceTest.kt`（新增旧回归与新旧等价测试）
- **严禁修改**：
  - **全部旧解析器与旧 Bean 只读不改**（`hipda/src/main/java/net/jejer/hipda/utils/HiParser*.java`、`async/LoginHelper.java`、`async/PostSmsAsyncTask.java`、`service/NotiHelper.java`、`bean/*.java` 及 `hipda/src/main/` 下全部文件均不得改动）；
  - 与在途卡零冲突：严禁触碰 `hipda/src/main/java/net/jejer/hipda/ui/`、`data/`、`di/`、`src/debug/`、`src/test/snapshots/`；
  - 严禁修改样本文件 `hipda/src/test/resources/samples/*`；
  - 严禁修改共享文件（`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）及 `MotionTokenScanTest`。
- **`:core-parser` 纯洁性约束**：`:core-parser` 保持纯 JVM（零 Android 依赖、不读写设置、不启动线程、**不修改调用方传入的 Jsoup `Document` DOM**）；所有模型均为不可变 Kotlin `data class` / `sealed interface` / `enum class`。

【背景】
- 必读规格与计划：
  - `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`（第一节、第二节卡 1 `B9a`、第四节 R-B1c S1/S2）；
  - `docs/design/tech-plan.md` §5（T2：解析器先上测试，再抽模块；不改旧代码）、§8（M2 安全网）；
  - `docs/design/backlog.md` B4、B6（登录）、B9（公共/登录/短消息/提醒）；
  - `docs/agents/lesson.md`（**两条必守规则**：① Jsoup 1.24.1 的 `selectFirst`/`first`/`last` 带 `@org.jspecify.annotations.Nullable`，Kotlin 2.4 泛型推断会报错，统一用 `select(selector).firstOrNull()`；② Discuz! 7.2 二次转义补充平面 Emoji `&amp;#128512;` 已在真机样本中实测证实，新解析器须在 Jsoup 解码后再做一次十进制/十六进制数字实体解码）。
- 已有真机脱敏样本（`hipda/src/test/resources/samples/`，已由 `core-parser/build.gradle.kts:15` 接入 `:core-parser:test` 输入）：
  - `login/login_page.html`（**注意**：采集时已处于登录态，页面为 `#umenu` + “欢迎您回来，HiUser”回跳页，无未登录表单字段；用于测试 `isLoggedIn`、当前用户信息与退出链接 `formhash`，未登录表单与登录提交 XML 需用合成夹具覆盖）；
  - `sms/sms_list.html`、`sms/sms_detail.html`（含真实 `ul.pm_list`、`li.s_clear`、`p.cite`、`div.summary`、`notice_newpm.gif`、`formhash`）；
  - `notify/notify_list.html`（**注意**：账号无未读提醒，页面为 `<p class="nodata">暂无数据</p>` + 完整 `div.promptcontent`；用于测试空提醒页与 `promptcontent` 5 项计数，`ul.feed` 的 4 类提醒需用合成夹具覆盖）。
- 旧代码参照（只读，已核对行号）：
  - `hipda/src/main/java/net/jejer/hipda/utils/HiParser.java:212-523,786-804`（`parseSMS`、`parseNotify`、`parseFriendInfo`、`parseSystemInfo`、`parseNotifyThread`、`parseNotifyQuoteAndReply`、`parseSmsDetail`、`parseFormhash`、`parseErrorMessage`）
  - `hipda/src/main/java/net/jejer/hipda/bean/SimpleListItemBean.java:94-99`（`setAvatarUrl` 将 `small` 替换为 `middle`，`noavatar`/空串置空）
  - `hipda/src/main/java/net/jejer/hipda/async/LoginHelper.java:83-147` + `hipda/src/main/res/values/hipda.xml:4-5`（`欢迎您回来` / `登录失败`）
  - `hipda/src/main/java/net/jejer/hipda/async/PostSmsAsyncTask.java:52-109`（`input#formhash`、发送短消息 XML 返回 `class="summary"` 或 `<root>` 错误文案）
  - `hipda/src/main/java/net/jejer/hipda/service/NotiHelper.java:84-93`（`div.promptcontent a#prompt_threads/prompt_systempm/prompt_friend` 括号数字）
  - `hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java:25,208-221`（`NewPMImage = "images/default/notice_newpm.gif"`、9 位补零四段头像相对路径）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt:158-167`、`SampleLeakScanner.kt:97-130,249-267`

【目标】落地 `:core-parser` 公共解析基座（供 `B7`/`B8`/`B9b` 直接复用），实现登录、短消息、提醒与通知计数的无副作用纯 JVM 解析器并通过旧代码逐字段等价测试，同时关闭 R-B1c S1/S2 脱敏扫描边界。

【范围】
1. **R-B1c 建议 S1 与 S2（`core-parser/.../sanitize/`）**：
   - **S1（`SanitizeSelectors.kt:158-167`）**：收紧 `buildStandaloneUidRegex` 左边界，使末位为数字的百分号转义（如 `%20`、`%22`、`%26`）紧邻本人 UID 时同样命中替换与泄漏扫描（例如 `(?:(?<!\d)|(?<=%[0-9A-Fa-f]{2}))(?:$alternation)(?!\d)`），同时严格保持更长纯数字（如 `94242420`、`1424242`、`4242429`、`%204242429`）不被误伤。在 `HtmlSanitizerTest.kt` 补充 `%3D<selfUid>`、`%20<selfUid>`、`%22<selfUid>` 的正反例单测。
   - **S2（`SampleLeakScanner.kt:97, 115, 128, 249-267`）**：统一在 `scan`、`scanDirectory`、`scanDirectories` 对 `selfUid` 做 fail-closed 校验——当 `selfUid != null` 且 `selfUid.trim().isNotEmpty()` 但含非数字字符（如 `"abc"`、`"uid=424242"`）时，立即抛出 `IllegalArgumentException`（禁止静默退化忽略并返回 0 泄漏）；`null` 或纯空白仍视为未显式传入 `selfUid`，`"10000"` 等合法纯数字占位 UID 照常通过。在 `HtmlSanitizerTest.kt` 补充三入口 fail-closed 单测。
2. **`:core-parser` 公共解析基座（`net.jejer.hipda.parser.common`）**：
   - `DiscuzUrlHelper`：`middleString(source, start, end)`、`extractQueryParam(url, name)`、`avatarRelativePathForUid(uid)`（复用 9 位补零规则，返回 `000/01/00/00_avatar_middle.jpg` 或可拼自定义 `avatarBaseUrl`，不硬编码服务器域名）、`normalizeAvatarUrl(raw)`（`noavatar`→`null`/`""`、`small`→`middle`）、`Element.firstMatch(selector)`（统一用 `select(selector).firstOrNull()` 规避 Jsoup 1.24.1 + Kotlin 2.4 推断问题）。
   - `DiscuzTextDecoder`：纯 JVM 二次 HTML 数字实体解码器 `decodeDoubleEscapedEntities(text: String): String`，精准解码 Discuz! 7.2 二次转义的十进制（`&#128512;`）与十六进制（`&#x1F600;`）数字实体（含补充平面代理对 `Character.toChars(cp)`），对非法码点（`<= 0`、`> 0x10FFFF`、孤立代理码点 `0xD800..0xDFFF`）与残缺实体保持原文不崩，不把 `&lt;`/`&gt;` 等普通标签实体二次误解为 HTML。
   - `DiscuzCommonModels` + `DiscuzCommonParser`：
     - `DiscuzSessionInfo(isLoggedIn: Boolean, uid: String?, username: String?)`
     - `PromptCounts(pm: Int, announcePm: Int, systemPm: Int, friend: Int, thread: Int)`（含计算属性 `notifyTotal = thread + systemPm + friend` 与旧 `NotiHelper` 等价）
     - `DiscuzPageMeta(session: DiscuzSessionInfo, formhash: String?, errorMessage: String?, promptCounts: PromptCounts)`
     - 纯函数（均不修改传入 `Document` DOM）：`parsePageMeta(doc)`、`parseSession(doc)`、`parseFormhash(doc)`（支持 `input[name=formhash]`、`input#formhash`、`#umenu` logout 链接兜底或明确分工，并与旧 `HiParser.parseFormhash` 对齐）、`parseErrorMessage(doc)`（从 `div.alert_error` 排除 `<a>` 后取文本，不调用 `el.select("a").remove()` 修改原 `doc`）、`parsePromptCounts(doc)`。
3. **登录解析器（`net.jejer.hipda.parser.auth`）**：
   - `LoginFormParseResult`（`Ready(formhash: String)` / `AlreadyLoggedIn(session: DiscuzSessionInfo, logoutFormhash: String?)` / `Error(message: String)`）：解析登录页（`input[name=formhash]`、`div.alert_info`、已登录态 `#umenu`）。
   - `LoginSubmitResult`（`Success(message: String)` / `Failed(message: String, remainingAttempts: Int?)` / `UnknownError(rawSnippet: String)`）：解析登录 POST 返回 XML（对照 `LoginHelper.java:120-137` 与 `hipda.xml:4-5` 的“欢迎您回来”/“登录失败”+“次”提取）。
4. **短消息与提醒解析器（`net.jejer.hipda.parser.message`）**：
   - `SmsParser`：
     - `parseSmsList(html / doc): SmsListPage`：`items: List<SmsConversationItem>`（`uid`、`author`、`avatarUrl`、`time`、`summary`、`isNew`）、`meta: DiscuzPageMeta`、`hasContainer: Boolean`（区分“无 `ul.pm_list`”与“空列表”）。
     - `parseSmsDetail(html / doc): SmsDetailPage`：`myUid`、`myUsername`、`formhash`（`input#formhash` / `input[name=formhash]`）、`messages: List<SmsMessageItem>`（`uid`、`author`、`avatarUrl`、`time`、`contentHtml`、`isNew`、`isMine`）、`meta: DiscuzPageMeta`。
     - `parseSmsSendResult(xml: String): SmsSendResult`（`Success` / `Failed(message: String)`，对照 `PostSmsAsyncTask.java:89-109`）。
   - `NotifyParser`：
     - `parseNotifyList(html / doc): NotifyListPage`：`items: List<NotifyItem>`、`meta: DiscuzPageMeta`、`hasFeedContainer: Boolean`；
     - `NotifyItem`（或带 `NotifyKind` 枚举：`THREAD`、`QUOTE`、`REPLY`、`SYSTEM`、`FRIEND`）完整覆盖旧 `HiParser.parseNotify` 4 支字段：`kind`、`title`、`info`、`author`、`authorUid`、`avatarUrl`（系统消息 `SYSTEM` 为 `null`，不耦合 Android `GlideHelper.SYSTEM_AVATAR_FILE` 路径）、`tid`、`pid`、`time`、`isNew`、`quoteOriginal` / `quoteReply`（保留结构化双 `dd` 同时支持等价拼装校验）。
5. **跨卡复用测试基座与新旧等价测试**：
   - `core-parser/src/test/kotlin/net/jejer/hipda/parser/SampleFixtures.kt`：公开 `loadSample(relativePath: String): String` 与 `parseSampleDocument(relativePath: String): Document`（UTF-8 / GB18030 自动读取，定位仓库根目录 `hipda/src/test/resources/samples/`），供 `B7`/`B8`/`B9b` 直接复用。
   - `hipda/src/test/java/net/jejer/hipda/parser/LegacyTestSupport.kt`：公开 Robolectric 旧解析器初始化辅助（初始化 `HiSettingsHelper`、调用 `HiParserThreadList.holdFetchNotify()` 彻底抑制旧解析器内 `ParseNotifyRunnable` 后台线程发起网络请求、从 test resources 加载样本），供 `B7`/`B8`/`B9b` 直接复用。
   - `hipda/src/test/java/net/jejer/hipda/parser/AuthAndMessageParserEquivalenceTest.kt`：先锁定旧 `HiParser.parseSMS`、`parseSimpleList(TYPE_SMS_DETAIL)`、`parseNotify`、`parseFormhash`、`parseErrorMessage`、`LoginHelper.checkLoggedin`、`NotiHelper` 计数在真机样本与合成夹具上的输出，再与 `:core-parser` 新解析器逐字段断言等价。

【步骤】
1. **先写失败测试（TDD）**：
   - 补 R-B1c S1（`%3D`/`%20`/`%22` 紧邻 UID 与更长数字反例）与 S2（`scan`/`scanDirectory`/`scanDirectories` 非空非数字 `selfUid` fail-closed）单测；
   - 建立 `SampleFixtures.kt` 与 `LegacyTestSupport.kt`，先对旧解析器在真机样本（`login_page.html`、`sms_list.html`、`sms_detail.html`、`notify_list.html`）与合成夹具（`ul.feed` 4 类提醒、未登录表单、登录 XML、SMS 发送 XML、`div.alert_error`）上写基线回归测试（确保 100% 离线、不触发任何真实网络请求）。
2. 实现 R-B1c S1/S2 修复、`:core-parser` 公共基座（`common/`）、登录解析器（`auth/`）、短消息与提醒解析器（`message/`）。
3. 编写 `:core-parser:test` 单元测试（覆盖 `DiscuzCommonParser`、`DiscuzTextDecoder`、`LoginParser`、`SmsParser`、`NotifyParser` 及边界/畸形输入）与 `AuthAndMessageParserEquivalenceTest`（真机样本 + 合成夹具逐字段等价断言）。
4. **单行变异自证**（验证测试真能抓错，跑完还原，不提交变异）：至少做 3 组变异——(a) 破坏 S1 百分号左边界或 S2 fail-closed、(b) 破坏 `DiscuzTextDecoder` 二次十进制实体解码、(c) 破坏 `SmsParser`/`NotifyParser` 关键字段提取（如 `uid`/`isNew`/`tid`），确认对应测试立刻变红，记录结果进 `_reports/B9a.md`。
5. 本地按 DISPATCH §2 串行跑 `:core-parser:test`、`:hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.*" --tests "net.jejer.hipda.debug.sampleexport.*"`、`:hipda:assembleDebug`、`:hipda:lintDebug`，核实 `git diff -- hipda/src/main/` 为空且样本零改动，提交推送 `card/B9a-parser-core-auth-msg`，用 `gh run watch <id> --interval 60` 等 CI 全绿。

【自检】
- 先导出 mise 的 `ANDROID_HOME` / `JAVA_HOME`（见 `docs/agents/lesson.md`），按 DISPATCH §2 串行执行：
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :core-parser:test -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.*" --tests "net.jejer.hipda.debug.sampleexport.*" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] **只读边界与零冲突守卫**：全部旧解析器 `HiParser*.java`、`LoginHelper.java`、`PostSmsAsyncTask.java`、`NotiHelper.java` 及整个 `hipda/src/main/` 零改动；`hipda/src/test/resources/samples/` 零改动；共享文件与 `MotionTokenScanTest` 零改动；测试全程离线，不得访问真实站点（`LegacyTestSupport` 阻断 `ParseNotifyRunnable` 后台线程）。证据：`git diff --stat origin/main...HEAD` + `_reports/B9a.md`。
- [ ] **真机样本与合成夹具新旧逐字段等价**：新解析器对 4 个真机样本（`login/login_page.html`、`sms/sms_list.html`、`sms/sms_detail.html`、`notify/notify_list.html`）及合成夹具的输出与旧解析器逐字段一致，并在测试中断言全部对应字段：
  - 登录与页头：`isLoggedIn`、`uid`、`username`、`formhash`、`errorMessage`、`promptCounts`（`thread + systemPm + friend` 等价 `NotiHelper`）、登录 XML 结果（成功 / 失败消息与剩余次数 / 未知）；
  - 短消息列表（`sms_list.html`）：条数及每条 `uid`、`author`、`avatarUrl`（`small→middle` / `noavatar` 规则一致）、`time`、`summary`（等价旧 `title`）、`isNew`；
  - 短消息详情（`sms_detail.html`）：`myUid`、`myUsername`、条数及每条 `uid`（本人与对方）、`author`、`avatarUrl`、`time`、`contentHtml`（等价旧 `info`）、`isNew`；另测 SMS 发送 XML 结果；
  - 提醒列表（`notify_list.html` 空态 + 合成 `ul.feed` 4 类）：`kind`（`THREAD`/`QUOTE`/`REPLY`/`SYSTEM`/`FRIEND`）、`title`、`info`、`author`、`authorUid`、`avatarUrl`（`SYSTEM` 刻意去 Android 本地路径，其余一致）、`tid`、`pid`、`time`、`isNew`。
  - 证据：`AuthAndMessageParserEquivalenceTest` 用例列表 + CI 汇总。
- [ ] **公共基座 API 完整交付**：`DiscuzPageMeta`、`DiscuzCommonParser`、`DiscuzTextDecoder`（覆盖 `&amp;#128512;` 等二次转义补充平面 Emoji 解码）、`DiscuzUrlHelper`、`SampleFixtures`、`LegacyTestSupport` 实现完毕、无 DOM 变更副作用，且完整公开签名（签名 + 文件:行号）写入 `_reports/B9a.md` 的“对外 API 清单”供 `B7`/`B8`/`B9b` 复用。证据：`_reports/B9a.md` 对外 API 清单 + `DiscuzCommonParserTest` 通过。
- [ ] **R-B1c S1 闭环**：`SanitizeSelectors.kt:166` 收紧左边界覆盖 `%20`/`%22`/`%26` 等末位为数字的百分号转义紧邻 UID 且不误伤更长纯数字，`HtmlSanitizerTest.kt` 含 `%3D`/`%20`/`%22` 正反例单测全过。证据：`HtmlSanitizerTest` 新增用例名 + `:core-parser:test` 结果。
- [ ] **R-B1c S2 闭环**：`SampleLeakScanner`（`scan` / `scanDirectory` / `scanDirectories`）对非空非数字 `selfUid` fail-closed 抛出 `IllegalArgumentException`（`null`/空白/`"10000"` 行为不变），`HtmlSanitizerTest.kt` 含对应单测全过。证据：`HtmlSanitizerTest` 新增用例名 + `:core-parser:test` 结果。
- [ ] **单行变异自证与 CI 全绿**：至少 3 组单行变异自证均导致测试立刻变红并记录在 `_reports/B9a.md`；最终提交 CI 全绿（含 `:core-parser:test`、`SamplesAreSanitizedTest`、`:hipda:testDebugUnitTest`、`:hipda:assembleDebug`、`:hipda:lintDebug`）。证据：`_reports/B9a.md` 变异记录 + CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/B9a-parser-core-auth-msg`；Conventional Commits（如 `feat(core-parser): 建立公共解析基座并实现登录短消息提醒解析器`）。
- 详细证据、逐字段等价对照表、变异自证日志与**对外 API 清单（`DiscuzPageMeta`、`DiscuzCommonParser`、`DiscuzTextDecoder`、`DiscuzUrlHelper`、`LoginParser`、`SmsParser`、`NotifyParser`、`SampleFixtures`、`LegacyTestSupport` 的公开签名 + 文件:行号）** 写入 `/home/mir/Documents/hipda-next.wt/_reports/B9a.md`；最后一条回复按文末通用约定控制在 ≤600 字。

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

首先 `cd /home/mir/Documents/hipda-next.wt/B9a`，确认 `git status` 干净、HEAD 为 c90b3f6888ff0993c8ac1fc289d4bfbab5c3c0c9；首次推送用 `git push -u origin card/B9a-parser-core-auth-msg`。
