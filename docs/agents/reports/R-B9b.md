# 审查报告 R-B9b：搜索/简单列表/用户资料/黑名单/发帖表单/收藏/上传解析器（含 B9a 遗留 4 项小修）

- **审查结论**：**APPROVE**
- **审查对象**：`origin/card/B9b-parser-search-user-post` @ `14dc33db512d1a4483292d0f72aa52a631e0b7b6`
- **对比基线**：`d45b6a2a5f5c8bd28aa466778d094f646b9ba935`（`git merge-base origin/main 14dc33db...`；当前 `origin/main` @ `9c3b8ef040a25bd359ac474bca8d6afdac94df87` 仅前进 2 个纯文档提交，`git merge-tree --write-tree` 确认零冲突可直合）
- **CI 核验**：[Android CI #45610947130](https://github.com/mailwmj/hipda-next/actions/runs/45610947130)（Job `139193981923`，`headSha == 14dc33db512d1a4483292d0f72aa52a631e0b7b6`，`completed / success`）
  - 已从原始日志逐项核实执行并通过：`:hipda:assembleDebug`、`:hipda:lintDebug`、`:hipda:assembleRelease`、`:core-parser:test`、`:hipda:testDebugUnitTest`、Room Schema 一致性检查、Roborazzi 截图校验、Debug/Release APK 产物包名校验。
  - 全量单测汇总：**654 tests, 654 passed, 0 failed, 0 errors, 0 skipped**。
- **阻断项（Blocking）**：**0 项**（对照 `docs/agents/DISPATCH.md` §4）。

---

## 一、只读边界、零冲突与纯洁性核查（重点 1）

1. **改动文件清单核对**（`git diff --stat d45b6a2a5f5c8bd28aa466778d094f646b9ba935...14dc33db512d1a4483292d0f72aa52a631e0b7b6`）：
   - 共 **22 个文件**（`+2328 / -23`）：
     - **B9b 新增 `:core-parser` 生产文件（10 个）**：
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/search/{SearchModels,SearchParser}.kt`
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/{SimpleListModels,SimpleListParser}.kt`
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/user/{UserModels,UserParser}.kt`
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/post/{PrePostModels,PrePostParser,FavoriteParser,UploadResponseParser}.kt`
     - **B9b 新增测试文件（6 个）**：
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/{search/SearchParserTest,simplelist/SimpleListParserTest,user/UserParserTest,post/PostFavoriteUploadParserTest}.kt`
       - `hipda/src/test/java/net/jejer/hipda/parser/{SearchAndSimpleListEquivalenceTest,UserAndPostFormEquivalenceTest}.kt`
     - **负责人授权追加的 B9a 遗留 4 项小修（3 个生产 + 3 个单测）**：
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt`
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/message/{NotifyParser,SmsParser}.kt`
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/auth/LoginParserTest.kt`
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/message/{NotifyParserTest,SmsParserTest}.kt`
2. **禁改区与并行波零重叠确认**：
   - `hipda/src/main/`（含 `HiParser.java`、`PrePostAsyncTask.java`、`FavoriteHelper.java`、`UploadImgHelper.java`、`BlacklistHelper.java`、全部旧 Bean、`data/`、`di/`、`ui/`、`AndroidManifest.xml`）：**0 改动**；
   - `core-parser/.../parser/{common,sanitize,threaddetail,threadlist}/`、`SampleFixtures.kt`、`LegacyTestSupport.kt`、`AuthAndMessageParserEquivalenceTest.kt`：**0 改动**；
   - `hipda/src/test/resources/samples/*`、共享构建配置（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`.github/`）及 `MotionTokenScanTest`：**0 改动**；
   - 与同波 `D2`（仅改 `hipda/.../data/auth/` 与 `di/AuthModule.kt`）、`B8`（仅改 `parser/threaddetail/` 与 `ThreadDetailParserEquivalenceTest.kt`）及已合入的 `C4c`/`G1` **零文件交集**。
3. **纯 JVM、零副作用与全程离线**：
   - `git grep` 核查 `core-parser/src/main/` 确认零 `android.*`/`androidx.*` 导入、零 `selectFirst` 调用、零 `HiSettingsHelper` 读写、零后台线程启动；全部模型均为不可变 `data class` / `sealed interface` / `enum class`。
   - **零 DOM 变异**：所有新解析器对调用方传入的 `Document` 只读查询（`PrePostParser` 错误页复用 `DiscuzCommonParser.parseErrorMessage` 克隆子树后删 `<a>`；`FavoriteParser`/`LoginParser`/`SmsParser` 的 `remove()` 均作用于内部新建的临时 `parseBodyFragment` 文档），单测与等价测试均显式断言调用前后 `doc.outerHtml()` 逐字符相等。
   - **100% 离线**：`SearchAndSimpleListEquivalenceTest` 在每次调用 `HiParser.parseSimpleList` 前均调用 `LegacyTestSupport.suppressBackgroundNotifyFetch()` 阻断 `ParseNotifyRunnable` 后台网络请求；`UserAndPostFormEquivalenceTest` 通过反射只读调用 `PrePostAsyncTask.parseRsp(Document)`（跳过 `doInBackground` 网络层），且显式验证 `PrePostParser.parse` 不改动 `HiSettingsHelper.maxUploadFileSize`。

---

## 二、复用 `B9a` 基座与下游契约核查（重点 2）

1. **全面复用 `B9a` 公共基座，未重复造轮子**：
   - 统一使用 `DiscuzCommonParser.parsePageMeta(doc)`、`parseFormhash(doc)`、`parseLogoutFormhash(doc)`、`parseErrorMessage(doc)`；
   - 统一使用 `DiscuzTextDecoder.decodeDoubleEscapedEntities` 解码双重转义数字实体（含真机 `search_result.html` 第 `3477944` 帖 `&amp;#127822;` → `🍎`）；
   - 统一使用 `DiscuzUrlHelper`（`extractQueryParam`、`middleString`、`avatarUrlForUid`、`extractFirstIntOrZero`、`isValidId`、`Element.firstMatch`）；
   - 单测复用 `SampleFixtures`，Robolectric 等价测试复用 `LegacyTestSupport`。
2. **`_reports/B9b.md` 第六节对外 API 清单与下游契约核对**：
   - 逐行比对 10 个生产文件的公开类名、方法签名与行号，与源码 **100% 吻合**；
   - 下游接入完备性：
     - **`D6`（发帖/回复/编辑/传图）**：可直接消费 `PrePostMode`、`PrePostParser.parse`（含 `maxUploadFileSizeBytes: Int?`、`existingImages`/`existingAttachments`、保序 `typeOptions`、`notice*` 三字段）与 `UploadResponseParser.parse`；
     - **`D8a`（搜索/今日新帖/我的/收藏/关注）**：可直接消费 `SearchParser`（`parseTitleSearch` 兼供搜索与今日新帖，`parseFullTextSearch` 供全文搜索）、`SimpleListParser`（三类列表 + `meta.formhash` 供表单批量删除）与 `FavoriteParser`（收藏 `tid` 集合同步与增删 XML 响应）；
     - **`D8b`（用户资料/TA 的帖子/黑名单）**：可直接消费 `UserParser.parseUserInfo`、`SearchParser.parseTitleSearch`、`UserParser.parseBlacklist`（配合 `DiscuzCommonParser.parsePageMeta(doc)` 提取黑名单页 `formhash` 与 `alert_error`）。

---

## 三、4 组新解析器与 B9a 追加 4 项小修逐项审查（重点 3）

### 3.1 4 组新解析器逐字段等价与边界核查

| 领域 | 对照旧实现 | 核查结论与边界表现 |
|---|---|---|
| **搜索**（`SearchParser.kt:15-152`） | `HiParser.java:525-669`（`parseSearch` / `parseSearchFullText`） | **等价且更稳健**。① 标题搜索跳过搜索表单与快捷搜索 `tbody`，在 `search_result.html` 全部 75 条上与旧版 `TYPE_SEARCH`/`TYPE_NEW_POSTS`/`TYPE_SEARCH_USER_THREADS` 逐字段一致，并修复 `tid=3477944` 双重转义 `🍎`、补齐 `authorUid`；② 全文搜索严格校验 `pid` 非空与 `div.sp_theard span` 恰好 5 个，准确提取 `forumName`（`span[0]`）、`author`/`authorUid`/`avatarUrl`（`span[1]`）、`time`（`span[4]` 去除冒号后前导空格）与 `snippet`；③ 分页先查 `div.pages_btns div.pages` 再兜底顶栏 `div.pages`，且用 `firstNotNullOfOrNull` 提取 `searchid`，避免旧版首链接不含 `searchid` 时丢失。 |
| **简单列表**（`SimpleListParser.kt:14-178`） | `HiParser.java:57-210, 671-724` | **等价且修复旧版残缺行脏数据**。① `parseMyThreads` 在 `my_threads.html`（75 条、`maxPage=3`）逐字段一致，无表时以 `hasTable=false` 表达空态；② `parseMyReplies` 在 `my_posts.html`（151 个 `tr` = 表头 + 75 对回复，`maxPage=34`）逐字段一致；当奇数头行残缺（如无链接或空态行）时 `pending = null`，偶数行安全跳过不抛 NPE，修复了旧版 `HiParser.java:92,127` 将空 `SimpleListItemBean` 塞入结果的 bug（跨单行插入导致奇偶错位的进一步加固见建议 S2）；③ `parseFavoritesList` 在 `my_favorites.html`（75 条、`maxPage=4`）逐字段一致，末尾 `chkall`/`favsubmit` 提交行因 `th` 无 `<a>` 被干净跳过。 |
| **用户资料与黑名单**（`UserParser.kt:13-99`） | `HiParser.java:726-784, 806-825` | **完全等价**。① `parseUserInfo` 在 `user_profile.html` 上 `uid`、`username`、`isOnline`、`avatarUrl`、`formhash`（`#umenu` 退出链接）及 `detail`（仅取前 2 个 `h3.blocktitle` 段并在首段拼入 `div.main div.s_clear ul.commonlist li`，排除第 3 段 `信用评价`）逐字符一致；② `parseBlacklist` 使用 `LinkedHashSet` 保序去重并过滤空 `username`（修复旧版 `space.php?uid=...` 误塞空串问题），覆盖有条目、`暂无数据` 确认空态（`isEmptyConfirmed=true`）、无条目无标记（`InvalidPage("黑名单数据解析错误")`）与缺 `div.blacklist`（`InvalidPage("页面解析错误")`）四态，不抛受检异常。 |
| **发帖表单 / 收藏 / 上传**（`PrePostParser.kt`、`FavoriteParser.kt`、`UploadResponseParser.kt`） | `PrePostAsyncTask.java:86-229`、`FavoriteHelper.java:104-133, 218-257`、`UploadImgHelper.java:210-227` | **逐字段等价且消除多项旧版隐患**。① `PrePostParser` 对 6 种 `PrePostMode` 严格区分：`REPLY_POST`/`QUOTE_POST` 将 `textarea.text()` 放入 `quoteText` 并提取 `noticeauthor`/`noticeauthormsg`/`noticetrimstr`，其余 4 种模式放入 `text` 且 `notice*` 置 `null`；遍历全部 `<script>` 提取 `discuz_uid` 并兜底 `input[name=uid]`（避免被前置外链/Cloudflare 脚本遮蔽），缺 `hash` 时不再像旧版提前返回漏解析后续字段；`uploadinfo` 支持整数/小数 KB/MB 换算并保留 `> 1024` 阈值（真样本 `8MB -> 8388608`），纯数值返回不写 `HiSettingsHelper`；3 类已上传图片与附件经 `LinkedHashSet` 保序合并去重且仅收 ASCII 纯数字 ID；`#typeid > option` 保持 `LinkedHashMap` 顺序（真样本 16 项），默认首项并兼容 `selected="selected"` 与 HTML5 布尔 `selected`；② `FavoriteParser.parseFavoriteIdsPage` 仅收 `DiscuzUrlHelper.isValidId`（`> 0`）的 `delete[]` 值（真样本 75 个），过滤 `"0"`/非法值与 `chkall`；`parseFavoriteActionXml` 剥离 `<script, style>` 并解码实体（尾随 `<a>` 差异见建议 S1）；③ `UploadResponseParser` 从 `DISCUZUPLOAD` 标记起按 `\|` 切分，对缺标记返回 `"无法获取图片ID"`，对段数 `< 3`、`imgId == "0"` 或非正整数 ID 统一返回 `"无效上传图片ID"`。 |

### 3.2 负责人授权追加的 B9a 遗留 4 项小修审查

| 序号 | 文件与位置 | 审查结论 |
|---|---|---|
| **1** | `NotifyParser.kt:158-163` + `NotifyParserTest.kt:189-219` | **通过**。`parseSystemInfo` 改为 `extractQueryParam(href, "tid") ?: extractQueryParam(href, "ptid")`，闭合 R-B9a S2（兼容 `redirect.php?goto=findpost&ptid=...` 系统管理通知）；单测同时覆盖无 `notice_newpm.gif` 的 `f_manage`/`f_buddy` 已读态（`isNew == false`），一并闭合 R-B9a S4 第二项。 |
| **2** | `LoginParser.kt:85-96` + `LoginParserTest.kt:127-160` | **通过**。`extractVisibleMessageText` 在无 `<root>` 回退整页 HTML 时改为 `Jsoup.parse(xml)` 移除 `script, style` 后取 `body().text().trim()`，修复了旧回退按首个 `<script` 截断在 `<head><script>` 导致丢失 `<body>` 提示的问题（闭合 R-B9a S3）。 |
| **3** | `LoginParser.kt:35, 58-82` + `LoginParserTest.kt:114-124, 161-177` | **通过**。`parseLoginPage` 复用 `DiscuzCommonParser.parseFormhash(doc)` 兜底 `input#formhash`（闭合 V-B9a #1）；`parseLoginSubmitResponse` 移除 `\|\| xml.contains(...)` 原始 XML 字符串回退，仅依据 `visibleText` 判定，彻底消除 XML 注释中含“欢迎您回来”/“登录失败”时的误判（闭合 V-B9a #2）。 |
| **4** | `SmsParser.kt:146-156` + `SmsParserTest.kt:170-191` | **通过**。`parseSmsSendResult` 改用 `Jsoup.parseBodyFragment(rootText)` 移除 `script, style` 后 `.trim()` 并经 `DiscuzTextDecoder` 解码，兼容 `<div class="alert_error">` 包裹的错误文案、消除前导/尾随换行缩进，空内容仍回退 `"短消息发送失败."`（闭合 V-B9a #3）。 |

---

## 四、测试质量、单行变异盲区与复杂度评估（重点 4）

1. **测试覆盖与真实性核查**：
   - 两份 Robolectric 等价测试（`SearchAndSimpleListEquivalenceTest`、`UserAndPostFormEquivalenceTest`）对全部 6 份真机样本均为**全量条目循环逐字段断言**（75 + 75 + 75 + 75 条列表项、1 份完整用户资料、16 个发帖分类键序与 15 个表单字段），并非仅断言非空或首条；实现者执行的 4 组单行变异自证（M1–M4）均精准命中核心逻辑。
2. **细粒度单行变异盲区排查（均非阻断，记入建议 S4）**：
   - **盲区 1（末页 `<strong>` 分页循环）**：`SimpleListParser.kt:164-167` 与 `FavoriteParser.kt:39-42` 的 `select("... strong")` 循环在真机样本中均处于第 1 页（`<strong>1</strong>`，最大页码由 `<a href="...page=N">` 给出），且单测未像 `SearchParserTest:132-143` 那样构造末页夹具（如 `<a href="...page=3">3</a><strong>4</strong>`）；若删去 `strong` 循环，现有测试仍会通过。
   - **盲区 2（两处二次实体解码调用）**：`SimpleListParser.kt:39`（`parseMyThreads` 的 `title`）与 `UserParser.kt:43,46`（`parseUserInfo` 的 `detail`）调用了 `DiscuzTextDecoder.decodeDoubleEscapedEntities`，但对应真机样本与合成夹具未含 `&amp;#\d{5,6};`，若删去这两处包装调用，现有测试仍会通过。
3. **代码复杂度评估**：
   - 10 个新生产文件共 845 行，全部为无状态 `object` + 扁平不可变数据类，结构直白、无多余抽象层或测试专用钩子，复杂度克制合理（`FavoriteParser` 与 `SmsParser` 中各 5 行的 `<root>` 片段提取因遵守 `B9b` 不改 `parser/common/` 边界而保持局部内聚，完全合理）。

---

## 五、非阻断建议项（Suggestions）

- **S1（`FavoriteParser.parseFavoriteActionXml` 剥离尾随操作链接）**：[FavoriteParser.kt:67-69](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/post/FavoriteParser.kt#L67-L69) 当前仅移除 `script, style`；Discuz! 7.2 收藏成功或已收藏 AJAX XML 常附带 `<br /><a href="my.php?item=favorites&type=thread">[查看我收藏的所有主题]</a>`（旧 `FavoriteHelper.java:223-224` 按 `<` 截断会一并去掉），若 `D8a` 需取纯提示文案，建议改为 `fragment.select("script, style, a").remove()`。
- **S2（`SimpleListParser.parseMyReplies` 按头行结构配对防奇偶错位）**：[SimpleListParser.kt:75-80](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParser.kt#L75-L80) 沿用旧版 `i % 2 == 1` 下标配对（单对残缺已安全跳过且优于旧版），若未来表格插入单行分隔/公告导致奇偶翻转会漏掉后续成对回复，可改为按 `parseReplyHeaderRow(row) != null` 识别头行并消费紧随摘要行。
- **S3（`D6` 接入 `PrePostParser` 时闭合编辑/引用态 Emoji 往返）**：[PrePostParser.kt:37-49](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/post/PrePostParser.kt#L37-L49) 为与旧 `PrePostAsyncTask` 输出一致未对 `subject`/`text`/`quoteText` 二次解码数字实体；建议 `D6` 回填编辑器时解码 `&amp;#...;` 残留的 `&#...;` 为真实 Emoji，并在 GBK 提交时统一转回十进制实体。
- **S4（补齐末页 `<strong>` 分页与两处二次实体解码的变异单测）**：可在后续顺手为 [SimpleListParser.kt:39,164](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParser.kt#L39)、[FavoriteParser.kt:39](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/post/FavoriteParser.kt#L39) 与 [UserParser.kt:43](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/user/UserParser.kt#L43) 补充末页 `<strong>` 夹具及 `&amp;#128512;` 用例以锁死单行变异盲区。
