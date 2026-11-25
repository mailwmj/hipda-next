# 验证报告 V-B9a：`:core-parser` 公共基座、登录/短消息/提醒解析与 R-B1c S1/S2 独立探针验证

## 1. 总判定与基线信息

- **总判定**：**PASS**（未发现 `docs/agents/DISPATCH.md` 第 4 节或 `B9a` 契约下的阻断项；记录 3 处与旧版一致的非阻断局部脆弱点供后续加固参考）
- **验证对象**：`origin/card/B9a-parser-core-auth-msg` @ `d1263a5dfd219a2b2a74515979e325755529fd79`
- **只读边界**：未读取实现者测试文件与 `_reports/B9a.md`；未修改任何生产代码、样本文件或共享文件。
- **探针分支与提交**：`verify/B9a-probes` @ `7e85a0c24756985eaea21935b4d9e325755529fd79`（worktree `/home/mir/Documents/hipda-next.wt/V-B9a`）
- **新增探针文件**：
  - [B9aProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-B9a/core-parser/src/test/kotlin/net/jejer/hipda/verify/B9aProbeTest.kt)（7 个 JUnit 5 纯 JVM 探针用例）
  - [B9aLegacyCompareProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-B9a/hipda/src/test/java/net/jejer/hipda/verify/B9aLegacyCompareProbeTest.kt)（5 个 Robolectric 新旧对照探针用例，均调用 `HiParserThreadList.holdFetchNotify()` 与 `HiSettingsHelper.getInstance().setLastCheckSmsTime(...)` 彻底阻断后台网络请求）
- **CI 运行证据**：
  - GitHub Actions (`workflow_dispatch` on `verify/B9a-probes`)：[45606855339](https://github.com/mailwmj/hipda-next/actions/runs/45606855339) — **success**（`Build, Lint & Unit Tests` 用时 10m27s）
  - CI JUnit XML 全量汇总：**604 tests, 604 passed, 0 failed, 0 errors, 0 skipped**（含 `B9aProbeTest`: 7/7、`B9aLegacyCompareProbeTest`: 5/5、`SamplesAreSanitizedTest`: 5/5、`HtmlSanitizerTest`: 27/27、`AuthAndMessageParserEquivalenceTest`: 5/5 等全部套件及 `assembleDebug`/`assembleRelease`/`lintDebug`/Roborazzi 校验）

---

## 2. 探针逐项结论（a–f）

| 探针 | 结论 | 核心结论与新旧差异要点 |
|---|---|---|
| **a. GBK / GB18030 边界字符与补充平面 Emoji** | **新版更稳健**（字符集往返一致，二次数字实体解码大幅增强） | GBK 生僻字（“镕”“喆”）、全角标点、GB18030 四字节字符（`\uD840\uDC00\uD842\uDFB7`）及一次实体（`&#128512;` / `&#x1F600;`）经 `GB18030`/`UTF-8` 字节流解码后新旧输出完全一致且零崩溃。对于 Discuz! 7.2 二次转义实体（`&amp;#128512;`、`&amp;#x1F600;`、ZWJ 序列 `&amp;#128104;&amp;#8205;&amp;#128105;`），旧版 `HiParser` 在 Jsoup 解析后残留 `&#128512;` 原文，新版通过 `DiscuzTextDecoder.decodeDoubleEscapedEntities` 精准还原为 `😀` / `👨‍👩`；对畸形/越界/代理实体（`&#0;`、`&#99999999;`、`&#999999999999;`、`&#55296;`、`&#57343;`、`&#;`、`&#xZZ;`）安全保留原文不崩。注：`SmsParser.parseSmsSendResult` 走 `xmlParser` 未经 `parseBodyFragment`，可解 CDATA 内一次 `&#128512;`，对 `&amp;#128512;` 保留字面量（与旧 `PostSmsAsyncTask` 一致）。 |
| **b. 缺失 `#umenu` 与游客页** | **登录态一致；`parseSmsDetail` 新版更稳健** | (1) 无 `#umenu` 片段与 (2) 仅含登录/注册链接的游客页：`DiscuzCommonParser.parseSession` 与旧 `LoginHelper.checkLoggedin` 均判 `false`、无 NPE；无 `#umenu` 的短消息对话页旧 `HiParser.parseSmsDetail` 直接返回 `null`，新版 `SmsParser.parseSmsDetail` 可回退 `myUid=null, myUsername=null` 并完整解析消息。(3) `#umenu cite a` 缺 `noborder` class 时，旧 `HiParser.parseSmsDetail`（硬匹配 `#umenu cite a.noborder`）返回 `null`，新版回退 `DiscuzCommonParser.parseSession`（`cite a` / `a[href*=space.php]`）正常识别 `uid="10000"`、`username="HiUser"` 与 `isMine`；`href` 缺 `uid=` 时新旧均不抛 NPE（新版 `session.uid=null`、`SmsMessageItem.uid=""`、`avatarUrl=null`）。 |
| **c. 空短消息列表与空/残缺提醒页** | **容错一致；空态语义与 `noavatar` 新版更稳健** | (1) 缺 `ul.pm_list`：旧 `HiParser.parseSMS` 返回 `null`，新版返回 `hasContainer=false, items=emptyList()`。(2) 空 `<ul class="pm_list"></ul>`：新旧均返回空集合（`hasContainer=true`）。(3) 缺 `ul.feed` / `<p class="nodata">暂无数据</p>`（含真机 `notify/notify_list.html`）：旧 `HiParser.parseNotify` 返回 `null`，新版返回 `hasFeedContainer=false, items=emptyList()`；4 个真机样本（`login_page.html`、`sms_list.html`、`sms_detail.html`、`notify_list.html`）字段与旧版等价。(4) 混合残缺页（缺 `div.summary`、`p.cite`、`cite a`、`em`、无 `div` 的 `li`）均跳过残缺项而不拖垮前后完整项；旧 `SimpleListItemBean.setAvatarUrl` 因缺 `return` 将 `noavatar_small.gif` 误改写为 `noavatar_middle.gif`，新版 `DiscuzUrlHelper.normalizeAvatarUrl` 正确返回 `null`；新版 `NotifyKind.SYSTEM` 的 `avatarUrl=null` 解耦旧版 `GlideHelper.SYSTEM_AVATAR_FILE` 本地路径强依赖。 |
| **d. HTML 实体与 DOM 无副作用** | **新版更稳健**（彻底消除原 DOM 破坏） | `&amp;`、`&lt;b&gt;`、`&quot;`、`&amp;lt;b&amp;gt;`、`&amp;#128512;` 与 `&nbsp;`（Jsoup `Element.text()` 规整为空格 `' '`）在 `DiscuzCommonParser`、`SmsParser`、`NotifyParser` 中解码准确，不把 `&lt;b&gt;` / `&amp;lt;b&amp;gt;` 二次误解为 HTML 标签。连续两次对同一 `Document` 调用 `DiscuzCommonParser.parseErrorMessage`、`parsePageMeta` 与 `NotifyParser.parseNotifyList`，结果完全一致，原 `Document` 中 `div.alert_error a`（1 个）与 `div.f_buddy a`（3 个）均保持完整；对比旧版 `HiParser.parseErrorMessage`（原地 `el.select("a").remove()` 清空链接）与 `HiParser.parseFriendInfo`（原地 `aES.get(1).remove()` 导致第 2 次调用返回内容漂移），新版无 DOM 副作用。 |
| **e. 站点改版小变化（鲁棒性对照）** | **主路径一致或更稳健；记录 3 处与旧版一致的非阻断脆弱点** | (1) `#umenu cite a` 增删 class：无 `noborder` 或无 `<cite>` 时新版 `DiscuzCommonParser` / `SmsParser` 仍可识别（**新版更稳健**）；多 class 一致。(2) `li.s_clear pm_item` 额外 class：**一致**。(3) `promptcontent` 括号空格 `帖子消息 ( 3 )` 与未知 `prompt_extra (2)`：**一致**，且负数计数被新版 `coerceAtLeast(0)` 钳制为 `0`（**新版更稳健**）。(4) `input` formhash：`DiscuzCommonParser.parseFormhash` 与 `SmsParser.parseSmsDetail` 兼容仅 `id="formhash"`（**新版更稳健**），属性乱序、标签/属性名大写及 `name="FORMHASH"` 在 Jsoup `[name=formhash]` 下均匹配（**一致**），`id="FormHash"` 对大小写敏感（**一致**）；但 `LoginParser.parseLoginPage` 仅查 `input[name=formhash]` 未复用 `DiscuzCommonParser.parseFormhash` 兜底 `input#formhash`（与旧 `LoginHelper` 一致，属局部脆弱点）。(5) 多 `<img>` 场景（图标前置在 `notice_newpm.gif` 前）新版 `.any` 正确判 `isNew=true` 而旧版 `.first()` 漏判（**新版更稳健**）；XML 含换行/注释/多 CDATA 时主路径与旧版一致，但 `LoginParser.parseLoginSubmitResponse` 的 `|| xml.contains("欢迎您回来")` 会被 XML 注释中同名关键字误导，`SmsParser.parseSmsSendResult` 截断 `<` 前未 `.trim()` 且 CDATA 首字符为 `<`（如 `<div class="alert_error">...</div>`）会截为空而回退默认错误（均与旧 `LoginHelper` / `PostSmsAsyncTask` 一致，非阻断）。 |
| **f. R-B1c S1 & S2 边界探针** | **PASS**（正反例与 fail-closed 全数命中） | **S1**：`%20<uid>`、`%22<uid>`、`%26<uid>`、`%3D<uid>`、`%2f<uid>`（及 `%3d` / `%2F`）在 URL `href`、元素属性 `title` 与文本节点中均被 `HtmlSanitizer.sanitize` 脱敏为 `10000` 并被 `SampleLeakScanner.scan` 检出 `LeakType.SELF_UID`，脱敏后再次扫描 0 泄漏；更长纯数字 `9<uid>0`、`1<uid>`、`<uid>9`、`%20<uid>9`、`%201<uid>` 均不被误伤也不误报。**S2**：`SampleLeakScanner.scan`、`scanDirectory`（含空目录与不存在目录）、`scanDirectories`（含空列表与含空/不存在目录列表）传入 `"abc"`、`"uid=424242"`、`"424242a"`、`"-424242"`、`" 424242a "`、`"４２４２４２"` 时全部在入口 fail-closed 抛出 `IllegalArgumentException`；传入 `null`、`""`、`"   "`、`"\t\n"`、`"10000"` 正常返回无异常。 |

---

## 3. 非阻断局部脆弱点记录（不触发 FAIL）

以下 3 处行为均与旧版（`LoginHelper` / `PostSmsAsyncTask`）一致，真实 Discuz! 7.2 页面不会触发阻断，记作后续可选加固项：
1. [LoginParser.kt:35](file:///home/mir/Documents/hipda-next.wt/V-B9a/core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt#L35)：`parseLoginPage` 直接查询 `doc.firstMatch("input[name=formhash]")`，未调用 `DiscuzCommonParser.parseFormhash(doc)`，若登录表单仅提供 `id="formhash"` 则返回 `Error("无法获取登录凭据")`。
2. [LoginParser.kt:60](file:///home/mir/Documents/hipda-next.wt/V-B9a/core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt#L60)：`parseLoginSubmitResponse` 包含 `|| xml.contains(LOGIN_SUCCESS_MARKER)` 回退，当 XML 注释含“欢迎您回来”而 `<root>` 正文为“登录失败”时会判定为 `Success`。
3. [SmsParser.kt:148-156](file:///home/mir/Documents/hipda-next.wt/V-B9a/core-parser/src/main/kotlin/net/jejer/hipda/parser/message/SmsParser.kt#L148-L156)：`parseSmsSendResult` 从 `root.text()` 截取首个 `<` 之前文本后未执行 `.trim()`（会保留 `<script>` 前换行与缩进），且未使用 `Jsoup.parseBodyFragment` 解码 CDATA 内 `&amp;#...;` 或剥离外层 HTML 标签（若错误文案以 `<div>` 包裹，`ltIndex == 0` 会回退默认错误信息）。

---

## 4. 验证执行记录

1. **本地验证（遵守 `DISPATCH.md` §2 `flock` + `-Xmx3g` + `--stop`）**：
   - 命令：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :core-parser:test :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.*" --tests "net.jejer.hipda.parser.*" --tests "net.jejer.hipda.debug.sampleexport.*" :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4; ./gradlew --stop`
   - 结果：`BUILD SUCCESSFUL`，`:core-parser:test` 全过（含 `B9aProbeTest` 7/7）、`:hipda:testDebugUnitTest` 29/29 通过（含 `B9aLegacyCompareProbeTest` 5/5、`AuthAndMessageParserEquivalenceTest` 5/5、`SampleExportEngineTest` 14/14、`SampleExportReadOnlyScanTest` 3/3、`SampleExportScreenshotTest` 2/2）、`:hipda:lintDebug` 通过。
2. **远程 CI 验证**：
   - 分支 `verify/B9a-probes`（`7e85a0c24756985eaea21935b4d9e384bae40912`）触发 `gh workflow run android.yml --ref verify/B9a-probes`
   - Run [45606855339](https://github.com/mailwmj/hipda-next/actions/runs/45606855339)：全量 `assembleDebug`、`assembleRelease`、`lintDebug`、`Verify Room schema consistency`、`testDebugUnitTest`、`:core-parser:test`、Roborazzi 校验全部通过（**604/604**）。
3. **只读范围核查**：
   - `git diff --stat d1263a5dfd219a2b2a74515979e325755529fd79..HEAD` 仅含 `B9aProbeTest.kt`（710 行）与 `B9aLegacyCompareProbeTest.kt`（442 行），零触碰生产源码、样本与共享文件。
