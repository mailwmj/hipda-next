# R-B9a 独立代码审查报告：`:core-parser` 公共基座 + 登录/短消息/提醒解析 + R-B1c S1/S2

- **审查结论**：`APPROVE`
- **审查对象**：`origin/card/B9a-parser-core-auth-msg` @ `d1263a5dfd219a2b2a74515979e325755529fd79`（相对分叉基线 `c90b3f6888ff0993c8ac1fc289d4bfbab5c3c0c9`，且与当前 `origin/main` @ `0c4eefa91d3ead94e5bee13ce0e836d674b5bd2e` 经只读 `git merge-tree --write-tree` 验证零冲突可干净合入）
- **任务卡与背景**：`/home/mir/Documents/hipda-next.wt/_cards/B9a.md`、`/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`、`/home/mir/Documents/hipda-next.wt/_reports/B9a.md`
- **CI 证据**：[Android CI #45603979935](https://github.com/mailwmj/hipda-next/actions/runs/45603979935)（Job `139169907518`）
  - `headSha`：`d1263a5dfd219a2b2a74515979e325755529fd79`（精确匹配）
  - 状态与结论：`completed / success`
  - 日志实证执行并通过的任务：`> Task :hipda:assembleDebug`、`> Task :hipda:lintDebug`、`> Task :hipda:assembleRelease`、`> Task :core-parser:test`、`> Task :hipda:testDebugUnitTest`（含 Room Schema 校验、Roborazzi 校验及 APK 产物核验）
- **变更规模**：20 个文件（12 个 `:core-parser` 生产源码文件 + 8 个测试/辅助文件），`+2282 / -7` 行（其中生产源码约 870 行，测试与辅助约 1,412 行）。

---

## 一、阻断项（Blocking Issues）

**无**（对照 `docs/agents/DISPATCH.md` §4 与 `R-B9a.md` 阻断标准：CI 全绿；真机脱敏样本与全分支合成夹具新旧逐字段等价通过；脱敏扫描器 S1/S2 严格 fail-closed；全程离线零访问真实站点；旧解析器、样本库、共享文件及在途 `C4c`/`G1` 范围零改动）。

---

## 二、五大审查重点逐项核查

### 1. 只读边界、纯洁性与零副作用核查

1. **改动范围与零越界验证**：
   - `git diff --name-status c90b3f6888ff0993c8ac1fc289d4bfbab5c3c0c9...d1263a5dfd219a2b2a74515979e325755529fd79` 共 20 个文件，100% 落在 `B9a.md` 授权集合内。
   - `hipda/src/main/`（含全部旧 `HiParser*.java`、`LoginHelper.java`、`PostSmsAsyncTask.java`、`NotiHelper.java`、旧 Bean、`ui/`、`data/`、`di/`、`AndroidManifest.xml`、`HiApplication.java`）：**0 行改动**。
   - `hipda/src/test/resources/samples/`（16 份真机样本）、`hipda/src/debug/`、`hipda/src/test/snapshots/`、共享构建配置（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`.github/`）及 `MotionTokenScanTest`：**0 行改动**。
2. **`:core-parser` 纯 JVM 与零 DOM 变更副作用**：
   - `git grep` 确认 `core-parser/` 下零 `android.*` / `androidx.*` 依赖、零设置读写、零后台线程启动；全部模型均为不可变 `data class` / `sealed interface` / `data object` / `enum class`。
   - **DOM 不变性**：旧代码存在两处原位删节点副作用（`HiParser.parseErrorMessage` 在 `HiParser.java:800` 执行 `el.select("a").remove()`；`HiParser.parseFriendInfo` 在 `HiParser.java:342` 执行 `aES.get(1).remove()`）。新代码在 `DiscuzCommonParser.kt:73-74` 与 `NotifyParser.kt:195-196` 均先对子树调用 `clone()`（脱离父 `Document` 的独立深拷贝）后再 `.remove()`，原 `Document` 零变更；`DiscuzCommonParserTest.kt:168-169`、`NotifyParserTest.kt:91-92` 与 `AuthAndMessageParserEquivalenceTest.kt:124, 334, 379-380` 均对调用前后 `doc.outerHtml()` 及子链接计数作了显式断言。
3. **`LegacyTestSupport` 阻断后台真实网络请求核查**：
   - 旧代码 `HiParser.parseSimpleList`（`HiParser.java:25`）、`HiParserThreadList.parse`（`HiParserThreadList.java:23`）、`HiParserThreadDetail.parse`（`HiParserThreadDetail.java:61`）每次调用都会 `new Thread(new ParseNotifyRunnable(context, doc)).start()`；`ParseNotifyRunnable.run()` 在 `System.currentTimeMillis() > HOLD_FETCH_NOTIFY + 10_000` 时调用 `NotiHelper.fetchNotification(mDoc)`，后者在 `doc == null || HiSettingsHelper.getInstance().isCheckSms()`（`> mLastCheckSmsTime + 30_000`）时通过 `OkHttpHelper.getInstance().get(HiUtils.NewSMS)` 请求真实论坛。
   - `LegacyTestSupport.kt:33-55` 在 `@Before initLegacyEnvironment()` 及每次 `suppressBackgroundNotifyFetch()` 同步刷新 `HiParserThreadList.holdFetchNotify()` 与 `HiSettingsHelper.getInstance().setLastCheckSmsTime(System.currentTimeMillis())`（由于 `Thread.start()` 具备 JLS §17.4.5 happens-before 语义，后台线程必定看到已刷新的时间戳并直接短路退出），同时注入测试 `Application` Context 与临时 `GlideHelper.SYSTEM_AVATAR_FILE`。`B9a` 测试中所有旧入口调用耗时均 <50ms，100% 离线阻断有效（针对极端慢速 CI 环境的进一步加固见建议 S1）。

---

### 2. 不可逆公共基座契约核查（供给 `B7`/`B8`/`B9b`/`D2`/`D7`）

1. **包结构与数据契约**：
   - `net.jejer.hipda.parser.common` 下拆分 `DiscuzCommonModels`、`DiscuzCommonParser`、`DiscuzTextDecoder`、`DiscuzUrlHelper`，职责边界干净，命名与可空性清晰：
     - `DiscuzSessionInfo(isLoggedIn: Boolean, uid: String?, username: String?)`：对齐 `LoginHelper.checkLoggedin`（`div#umenu` 内含 `space.php`）并同时校验 `uid` 纯数字，直接满足 `D2`（`ForumHttpClient` 会话失效检测与身份提取）消费需求。
     - `PromptCounts(pm, announcePm, systemPm, friend, thread)`：独立暴露私信 `pm`/`announcePm` 与提醒 `notifyTotal = thread + systemPm + friend`（对齐 `NotiHelper.java:86`），直接契合 `BATCH-4.md` §1.4 中 `D7` 分离 `smsUnreadCount` 与 `notifyUnreadCount` 的三栏角标契约。
     - `DiscuzPageMeta(session, formhash, errorMessage, promptCounts)`：`parsePageMeta` 将表单 `input[name=formhash]`/`input#formhash` 与 `#umenu` 退出链接 `formhash` 串联兜底，同时保留独立的 `parseFormhash(doc)` 与 `parseLogoutFormhash(doc)`，兼顾了无表单页面（如资料页、空列表页）取 `formhash` 与严格表单校验两类场景。
   - `DiscuzUrlHelper`：
     - `middleString` 与 `Utils.getMiddleString`（`Utils.java:483-502`）边界行为一致，且补齐了 `source == null` 空安全；
     - `extractQueryParam` 按完整键名匹配查询参数（忽略 `#fragment`，兼容 `&amp;` 与 `;` 分隔），消除了旧 `getMiddleString(..., "uid=", "&")` 将 `newbuddyid=`/`suid=` 误匹配或 `"tid="` 误匹配 `"ptid="` 的子串碰撞隐患；
     - `avatarRelativePathForUid` / `avatarUrlForUid` 不硬编码 `img02.4d4y.com`，在 `avatarBaseUrl == null` 时返回 9 位补零四段相对路径（`000/01/00/00_avatar_middle.jpg`），传入 `avatarBaseUrl` 时自动规范化末尾 `/`；
     - `normalizeAvatarUrl` 修复了旧 `SimpleListItemBean.setAvatarUrl`（`SimpleListItemBean.java:95-98`）漏写 `else` 导致 `noavatar_small.gif` 被覆写为 `noavatar_middle.gif` 的历史缺陷（与 `DetailBean.java:240-244`、`ThreadBean.java:151-155` 的正确意图一致）；
     - 预置 `isValidId`、`parseIntOrZero`、`extractFirstIntOrZero`、`Element.firstMatch`、`Elements.firstElementOrNull`、`Elements.lastElementOrNull`，全仓 `:core-parser` 生产代码零处直接调用 `selectFirst`/`first()`/`last()`，彻底规避 Jsoup 1.24.1 `@Nullable` + Kotlin 2.4 泛型推断问题（`lesson.md`）。
   - `SampleFixtures` 与 `LegacyTestSupport`：`loadSample(relativePath)` / `parseSampleDocument(relativePath)` 支持任意样本相对路径及 UTF-8 → GB18030 自动回退解码，下游 `B7`/`B8`/`B9b` 可直接只读调用而无需修改这两个文件，避免 Wave 2/3 并行分支冲突。
2. **`DiscuzTextDecoder.decodeDoubleEscapedEntities` 安全性核查**：
   - 正则 `&#(?:([0-9]+)|[xX]([0-9a-fA-F]+));` 仅匹配完整带分号的十进制/十六进制数字字符引用：
     - 正确处理补充平面代理对（`Character.toChars(codePoint)`），并在 `DiscuzCommonParserTest.kt:85-94` 用真机样本 `thread_detail/thread_p2.html`（`好的👌`）与 `thread_list/forum_2_p1_lastpost.html`（`🍎`）实测通过；
     - `toIntOrNull(10/16)` 防止超长数字溢出崩溃；`isValidDecodedCodePoint` 严格限定 `1..0x10FFFF` 并剔除孤立代理区 `0xD800..0xDFFF`，对 `&#0;`、`&#1114112;`、`&#xD800;`、`&#xDFFF;`、残缺实体 `&#128512` 原样保留；
     - 不解码 `&lt;`、`&gt;`、`&amp;`、`&quot;` 等命名实体，且所有调用点均位于 Jsoup `.text()` 提取纯文本之后（`SmsParser.parseSmsDetail` 的富文本 `contentHtml = summaryEl.html()` 保持原 HTML 不二次解码，防止把实体误还原为标签）。
3. **对外 API 清单核查**：
   - `_reports/B9a.md` 第六节完整列出了 `DiscuzCommonModels`、`DiscuzCommonParser`、`DiscuzTextDecoder`、`DiscuzUrlHelper`、`LoginModels`、`LoginParser`、`SmsModels`、`SmsParser`、`NotifyModels`、`NotifyParser`、`SampleFixtures`、`LegacyTestSupport` 的公开签名与精确文件行号，经逐一核对源码完全一致。

---

### 3. 登录、短消息、提醒解析正确性与逐字段等价核查

| 模块 | 对照旧代码 | 核查结果 |
|---|---|---|
| **`LoginParser.parseLoginPage`** | `LoginHelper.java:83-98, 144-147` | 真机 `login/login_page.html` 准确识别 `AlreadyLoggedIn(DiscuzSessionInfo(true, "10000", "HiUser"), logoutFormhash="00000000")`；合成未登录表单返回 `Ready(formhash)`；无表单时依次取 `div.alert_info` → `div.alert_error` → 默认 `"无法获取登录凭据"`，与旧逻辑对齐且覆盖已登录回跳页场景。 |
| **`LoginParser.parseLoginSubmitResponse`** | `LoginHelper.java:120-137` + `hipda.xml:4-5` | 覆盖 `欢迎您回来`（剥离 CDATA 内尾随 `<script>` 后返回 `Success`）、`登录失败` 含 `次`（截取 `登录失败...次` 并提取 `remainingAttempts: Int?`）、`登录失败` 不含 `次`（回退 `"登录失败,请检查账户信息"`）、未知响应与空白响应（`UnknownError`），4 分支与旧代码逐字一致（全 HTML 回退细节见建议 S3）。 |
| **`SmsParser.parseSmsList`** | `HiParser.java:212-276` (`parseSMS`) | 真机 `sms/sms_list.html` 全部 50 条会话逐条比对 `uid`、`author`、`avatarUrl`、`time`、`summary`（旧 `title`）、`isNew` 100% 一致；合成未读 `notice_newpm.gif`、缺失 `ul.pm_list`（`hasContainer=false`）、空 `ul.pm_list`（`hasContainer=true`）及 `noavatar` 修正均已单测锁定。 |
| **`SmsParser.parseSmsDetail`** | `HiParser.java:454-523` (`parseSmsDetail`) + `PostSmsAsyncTask.java:53-59` | 真机 `sms/sms_detail.html` 全部 9 条消息（4 本人 `li.s_clear.self` 无 `a.avatar[href]` + 5 对方 `a.avatar[href=space.php?uid=20000]`）逐条比对 `myUid="10000"`、`myUsername="HiUser"`、`formhash="00000000"`、`uid`、`author`、`avatarUrl`、`time`、`contentHtml`（旧 `info`）、`isNew`、`isMine` 100% 一致。 |
| **`SmsParser.parseSmsSendResult`** | `PostSmsAsyncTask.java:89-109` | 覆盖空串（`"短消息发送失败 :  无返回结果"`）、含 `class="summary"`（`Success`）、`<root>` 错误文案（在首个 `<` 处截断内嵌 `<script>`）、空 `<root>` 兜底（`"短消息发送失败."`），4 分支完全一致。 |
| **`NotifyParser.parseNotifyList`** | `HiParser.java:278-452` (`parseNotify` 4 支) + `NotiHelper.java:84-93` | 真机 `notify/notify_list.html` 空态（`<p class="nodata">`，`hasFeedContainer=false`、`notifyTotal=0`）与合成 `ul.feed` 全部 5 种 `NotifyKind`（`THREAD` 普通/关注、`QUOTE`、`REPLY`、`SYSTEM`、`FRIEND`）逐条比对 `title`、`info`、`author`、`tid`、`pid`、`time`、`isNew`、`avatarUrl` 完全等价；刻意差异清晰合理：① `SYSTEM` 的 `avatarUrl = null`（剥离 Android `GlideHelper.SYSTEM_AVATAR_FILE` 本地路径）；② `QUOTE`/`REPLY` 补齐旧代码漏设的 `authorUid` 并同时暴露结构化 `quoteOriginal`/`quoteReply`；③ `FRIEND` 克隆节点移除第 2 个 `<a>`，不污染原 DOM（`SYSTEM` 链接参数兼容建议见 S2）。 |

---

### 4. R-B1c S1 与 S2 核查

1. **R-B1c S1（`SanitizeSelectors.kt:166`）**：
   - 新正则：`(?:(?<!\d)|(?<=%[0-9A-Fa-f]{2}))(?:$alternation)(?!\d)`，由 `HtmlSanitizer`（替换）与 `SampleLeakScanner`（泄漏扫描）共用同一构造函数 `buildStandaloneUidRegex`，严格对称。
   - 边界推导核验（以 `uid = "424242"` 为例）：
     - `%20424242`、`%22424242%22`、`%26424242`、`%3D424242`、`123%20424242`：左邻 3 字符匹配 `(?<=%[0-9A-Fa-f]{2})` 且右邻满足 `(?!\d)`，均正确命中脱敏与扫描（`123%20424242` 精准替换为 `123%2010000`，保留前序数字与 `%20`）；
     - `%204242429`、`%3D4242420`、`4242429`：右邻为数字，被 `(?!\d)` 拒绝，不误伤；
     - `%221424242%22`、`%204424242`、`94242420`、`12424242`：`424242` 左邻为数字且前第 3 字符非 `%`，两支左断言均为假，不误伤；
     - 短 UID（`< 4` 位）：仍由 `MIN_STANDALONE_SELF_UID_LENGTH = 4` 过滤后走 `shortUids` 语境正则，行为不变。
   - `HtmlSanitizerTest.kt:1160-1218` 覆盖了 `%3D`、`%20`、`%22`、`%26` 正例与 6 组更长数字反例（补充大小写字母前导十六进制字节正例的建议见 S4）。
2. **R-B1c S2（`SampleLeakScanner.kt:97-143`）**：
   - `scan`（`:98`）、`scanDirectory`（`:117`）、`scanDirectories`（`:131`）三个公开入口均在**函数第一行**调用 `normalizeAndValidateSelfUid(selfUid)`：
     - 早于 `scanDirectory` 的 `!Files.exists(directory)` 提前返回与 `scanDirectories` 的空列表遍历；
     - 当 `selfUid != null && selfUid.trim().isNotEmpty()` 且含任何非 ASCII 数字（`it !in '0'..'9'`，比 `Char::isDigit` 更严，连全角数字也一并拦截）时立即抛出 `IllegalArgumentException`，且异常消息仅打印长度（`length=${trimmed.length}`），不泄露传入的原始敏感串；
     - `null`、`""`、`"   "`、`"10000"`、`" 10000 "` 及 `" 424242 "` 保持既有行为。
   - `HtmlSanitizerTest.kt:1220-1261` 对三入口分别覆盖了正常目录、**不存在目录 `missingDir`**、**空目录列表 `emptyList()`** 下的异常抛出断言，任一入口漏掉校验均会立刻导致单测失败。

---

### 5. 复杂度与测试覆盖盲区评估（`DISPATCH.md` §5）

1. **复杂度评估**：
   - 全部解析器均为无状态单例 `object` + 纯函数，模型均为扁平不可变数据结构，无多余接口层、工厂类或仅服务于测试的生产钩子；代码结构清晰紧凑。
2. **变异测试与潜在测试盲区排查**：
   - 已有效守住的变异：S1 退回 `(?<!\d)`、S2 任一入口漏调 `normalizeAndValidateSelfUid`、`DiscuzTextDecoder` 跳过解码、`SmsParser` 漏 `isNew`、`avatarRelativePathForUid` 任意下标错位（由 `"123456789"` 9 位互异数字守住）、`parseErrorMessage` / `parseFriendInfo` 原位 `.remove()` 破坏 DOM，均会立即令现有单测变红。
   - 发现 2 处非阻断的细粒度单测变异盲区（列入建议 S4，可在后续卡片顺手补强）：
     1. **S1 正例十六进制首位覆盖不全**：`HtmlSanitizerTest.kt:1162-1167` 的 4 个正例为 `%3D`、`%20`、`%22`、`%26`——其中 `%3D` 靠 `(?<!\d)` 即可命中，而 `%20`/`%22`/`%26` 两位均为十进制数字；若有人把 `SanitizeSelectors.kt:166` 的 `[0-9A-Fa-f]{2}` 误改为 `[0-9]{2}`，现有测试不会变红，但 UTF-8 中文编码字节紧邻 UID（如 `"号424242"` 编码为 `%e5%8f%b7424242`，末字节 `%b7` 首字符为字母、末字符为数字）会漏检。
     2. **`NotifyParser` 的 `SYSTEM`/`FRIEND` 分支缺已读反例**：`NotifyParserTest.kt:68-80` 与 `AuthAndMessageParserEquivalenceTest.kt:464-476` 中 `f_manage` 和 `f_buddy` 均携带 `notice_newpm.gif`（`isNew = true`），若误把 `parseSystemInfo`/`parseFriendInfo` 的 `isNew` 像 `parseNotifyThread` 一样硬编码为 `true`，现有单测仍会通过。

---

## 三、非阻断建议项（Suggestions）

- **S1（`LegacyTestSupport` 离线窗口永久化）**：`hipda/src/test/java/net/jejer/hipda/parser/LegacyTestSupport.kt:52-55` 当前用 `System.currentTimeMillis()` 刷新 10s/30s 防抖窗口；下游 `B7`/`B8`/`B9b` 若单测在极端 GC 下耗时超 10s 且未紧邻调用 `suppressBackgroundNotifyFetch()`，`ParseNotifyRunnable` 可能越过时间窗。后续可将 `setLastCheckSmsTime(Long.MAX_VALUE / 2)` 并反射设 `HiParserThreadList.HOLD_FETCH_NOTIFY = Long.MAX_VALUE / 2`，使整个测试进程生命周期永久离线。
- **S2（`NotifyParser.parseSystemInfo` 兜底 `ptid=`）**：`core-parser/src/main/kotlin/net/jejer/hipda/parser/message/NotifyParser.kt:160` 仅提取 `extractQueryParam(it, "tid")`；旧 `HiParser.java:354` 的 `getMiddleString(href, "tid=", "&")` 因后缀重合顺带兼容了 `redirect.php?goto=findpost&ptid=...` 形式的管理通知，建议兜底 `extractQueryParam(it, "tid") ?: extractQueryParam(it, "ptid")`。
- **S3（`LoginParser.extractVisibleMessageText` 整页 HTML 回退）**：`core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt:88-92` 在无 `<root>` 节点回退到整页 HTML 时按首个 `<script` 截断会截在 `<head><script>` 处；建议对 `Jsoup.parseBodyFragment(rootText)` 调用 `select("script, style").remove()` 后取 `.text().trim()`（或仅对 `<root>` 文本截断未闭合 `<script`）。
- **S4（补齐两处细粒度变异单测盲区）**：`core-parser/src/test/kotlin/net/jejer/hipda/parser/sanitize/HtmlSanitizerTest.kt:1162` 建议增加 `%b7424242` / `%A0424242` 正例以锁死 `[A-Fa-f]` 首位十六进制分支；`core-parser/src/test/kotlin/net/jejer/hipda/parser/message/NotifyParserTest.kt:68` 建议增加不带 `notice_newpm.gif` 的 `f_manage`/`f_buddy` 已读用例以锁死 `isNew = false` 分支。
