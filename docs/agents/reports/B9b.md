# 任务卡 B9b 交付报告

- **分支**：`card/B9b-parser-search-user-post`（基线 `origin/main` @ `d45b6a2a5f5c8bd28aa466778d094f646b9ba935`）
- **最终 Commit**：`14dc33db512d1a4483292d0f72aa52a631e0b7b6`
- **CI Run**：[Android CI #45610947130](https://github.com/mailwmj/hipda-next/actions/runs/45610947130)（Job `139193981923`，`completed / success`；`assembleDebug`、`assembleRelease`、`lintDebug`、Room Schema 校验、Roborazzi 校验与全量单测 **654/654 通过**，0 failed，0 errors，0 skipped）

---

## 一、改动范围与只读边界核查

### 1.1 `git diff --stat origin/main...HEAD`
共 22 个文件改动（`+2328 / -23`）：
- **新增 `:core-parser` 生产源码（10 个）**：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/search/{SearchModels,SearchParser}.kt`
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/{SimpleListModels,SimpleListParser}.kt`
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/user/{UserModels,UserParser}.kt`
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/post/{PrePostModels,PrePostParser,FavoriteParser,UploadResponseParser}.kt`
- **负责人追加授权修改 B9a 遗留小修（3 个生产 + 3 个单测）**：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/auth/LoginParser.kt`
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/message/{NotifyParser,SmsParser}.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/auth/LoginParserTest.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/message/{NotifyParserTest,SmsParserTest}.kt`
- **新增测试文件（6 个）**：
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/search/SearchParserTest.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParserTest.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/user/UserParserTest.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/post/PostFavoriteUploadParserTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/SearchAndSimpleListEquivalenceTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/UserAndPostFormEquivalenceTest.kt`

### 1.2 只读与零冲突守卫
- `git diff --stat origin/main...HEAD -- hipda/src/main/ hipda/src/test/resources/samples/`：**为空**（全部旧解析器 `HiParser.java`、`PrePostAsyncTask.java`、`FavoriteHelper.java`、`UploadImgHelper.java`、`BlacklistHelper.java`、旧 Bean、`data/`、`di/`、`ui/`、`AndroidManifest.xml` 及 16 份真机样本零改动）。
- 同波 `D2`/`B8` 及在途 `C4c`/`G1` 零文件重叠：`parser/{sanitize,common,threaddetail,threadlist}/`、`SampleFixtures.kt`、`LegacyTestSupport.kt`、`AuthAndMessageParserEquivalenceTest.kt`、共享 Gradle 配置与 `MotionTokenScanTest` 零改动。
- `:core-parser` 纯洁性：`grep` 确认零 `android.*`/`androidx.*` 依赖、零 `.selectFirst(` 调用、零设置读写、零线程启动；全部模型为不可变 `data class`/`sealed interface`/`enum class`；全部新解析器在测试中断言调用前后 `doc.outerHtml()` 逐字符一致（零 DOM 变异）。
- 全程离线：旧 `HiParser.parseSimpleList` 调用前均通过 `LegacyTestSupport.suppressBackgroundNotifyFetch()` 阻断后台线程网络请求；旧 `PrePostAsyncTask.parseRsp` 在测试侧通过反射只读调用。

---

## 二、B9a 审查/验证遗留 4 项小修闭环证据

| 编号 | 修改目标 | 实现位置 | 补充单测 |
|---|---|---|---|
| **1** | `NotifyParser.parseSystemInfo`：`tid` 缺失时兜底取 `ptid`（对齐旧版 `redirect.php?ptid=`） | `NotifyParser.kt:158-163` | `NotifyParserTest.parseSystemInfo_fallsBackToPtidWhenTidMissingAndRespectsReadStatus`（同时补测 `f_manage`/`f_buddy` 已读 `isNew=false`） |
| **2** | `LoginParser.extractVisibleMessageText`：无 `<root>` 回退整页 HTML 时先移除 `script, style` 再取可见文本 | `LoginParser.kt:85-96` | `LoginParserTest.parseLoginSubmitResponse_stripsHeadScriptStyleOnFullPageFallbackAndIgnoresXmlComments` |
| **3** | `LoginParser` 复用 `DiscuzCommonParser.parseFormhash`，且 `parseLoginSubmitResponse` 仅基于 `visibleText` 判定，不受 XML 注释关键字干扰 | `LoginParser.kt:35, 58-82` | `LoginParserTest.parseLoginPage_reusesCommonFormhashFallbackForIdOnlyInput` + 上述 XML 注释干扰用例 |
| **4** | `SmsParser.parseSmsSendResult`：改用 `Jsoup.parseBodyFragment` 移除 `script, style` 并 `.trim()`，解码外层标签与双重转义实体 | `SmsParser.kt:146-156` | `SmsParserTest.parseSmsSendResult_trimsWhitespaceAndParsesBodyFragmentWrappedHtml` |

---

## 三、逐字段新旧等价对照表

| 模块 / 场景 | 样本 / 夹具 | 旧解析器（只读基准） | 新解析器（`:core-parser`） | 逐字段比对结论 |
|---|---|---|---|---|
| **标题搜索 / 今日新帖 / TA 的帖子（真机）** | `search/search_result.html`（75 条） | `HiParser.parseSimpleList(TYPE_SEARCH / TYPE_NEW_POSTS / TYPE_SEARCH_USER_THREADS, false)`：`searchId="511"`、`maxPage=5`、75 条 | `SearchParser.parseTitleSearch(doc, HiUtils.AvatarBaseUrl)`：`searchId="511"`、`maxPage=5`、75 条 | 75 条 `tid`/`author`/`avatarUrl`/`time`/`forumName` 全一致；74 条 `title` 逐字一致，第 `3477944` 帖旧版残留 `&#127822;`、新版准确还原为 `🍎` |
| **全文搜索（合成）** | `table.datatable tr` 含 `div.sp_title`/`div.sp_content`/`div.sp_theard` 5 个 `span` + 双重转义 Emoji | `HiParser.parseSimpleList(TYPE_SEARCH, true)`：`searchId="8899"`、`maxPage=4`、2 条 | `SearchParser.parseFullTextSearch(doc, HiUtils.AvatarBaseUrl)`：`searchId="8899"`、`maxPage=4`、2 条 | `pid`/`author`/`avatarUrl`/`forumName` 一致；`title`/`snippet` 经 `DiscuzTextDecoder` 解码 Emoji；`time` 清理旧 `getMiddleString(":", "&")` 残留的前导空格（`oldItem.time.trim() == newItem.time`） |
| **我的帖子（真机 + 空态）** | `simple_list/my_threads.html`（75 条）+ 无表空页 | `HiParser.parseSimpleList(TYPE_MYPOST)`：`maxPage=3`、75 条；无表返回 `null` | `SimpleListParser.parseMyThreads`：`hasTable=true`、`maxPage=3`、75 条；无表 `hasTable=false` | 75 条 `tid`/`title`/`time`/`forumName` 及 `maxPage` 完全一致 |
| **我的回复（真机 + 残缺行）** | `simple_list/my_posts.html`（75 条）+ 残缺奇数行 | `HiParser.parseSimpleList(TYPE_MYREPLY)`：`maxPage=34`、75 条 | `SimpleListParser.parseMyReplies`：`hasTable=true`、`maxPage=34`、75 条 | 75 条 `tid`/`pid`/`title`/`replyText`（旧 `info`）/`time`/`forumName` 及 `maxPage` 完全一致 |
| **我的收藏 / 关注（真机）** | `simple_list/my_favorites.html`（75 条，含末尾提交按钮行） | `HiParser.parseSimpleList(TYPE_FAVORITES / TYPE_ATTENTION)`：`maxPage=4`、75 条 | `SimpleListParser.parseFavoritesList`：`maxPage=4`、75 条 | 75 条 `tid`/`title`/`time`/`forumName` 及 `maxPage` 完全一致，末尾提交按钮行干净跳过 |
| **用户资料（真机 + 离线态）** | `user_info/user_profile.html` + 离线最小页 | `HiParser.parseUserInfo`：`uid="10000"`、`username="HiUser"`、`isOnline=true`、`avatarUrl`、`formhash="00000000"`、`detail` | `UserParser.parseUserInfo`：`UserProfile` + `meta` | 6 个核心字段（含 `detail` 换行与仅截前 2 段 `h3.blocktitle`）逐字符完全一致 |
| **黑名单（合成 4 态）** | 含重复条目页、`"暂无数据"` 空态、无条目无标记页、缺 `div.blacklist` 页 | `HiParser.parseBlacklist`（列表去重 / 空列表 / 抛 `"黑名单数据解析错误"` / 抛 `"页面解析错误"`） | `UserParser.parseBlacklist`：`Success(usernames, isEmptyConfirmed)` / `InvalidPage(reason)` | 4 态行为与错误文案完全等价，且以不可变密封结果替代受检 `Exception` |
| **发帖预取表单（真机 + 合成全 6 模式 + 错页）** | `post_form/newthread_fid2.html` + 合成 `EDIT_POST`/`QUOTE_POST`/`REPLY_POST`/`REPLY_THREAD`/`QUICK_REPLY`/缺 `formhash` 错页 | 反射调用只读 `PrePostAsyncTask.parseRsp` + `HiSettingsHelper.maxUploadFileSize` | `PrePostParser.parse(doc, mode)`：`PrePostParseResult.Success(PrePostInfo)` / `Error(message)` | `formhash`/`uid`/`hash`/`subject`/`text`/`quoteText`/`isDeletable`/`maxUploadFileSizeBytes`（真样本 `8388608`，合成 `524288`/`2097152`）/3 个 `notice*`/`existingImages`/`existingAttachments`/`typeId`/`typeOptions`（16 项保序）及错误文案全量等价；绝不写 `HiSettingsHelper`、绝不变异 DOM |
| **收藏 ID 与增删 XML（真机 + 合成）** | `simple_list/my_favorites.html` + 增删 XML | 对照 `FavoriteHelper.java:104-133, 218-257`：`lastPage=4`、75 个合法 `tid`、XML 截断 `<` 前文案 | `FavoriteParser.parseFavoriteIdsPage` + `parseFavoriteActionXml` | `lastPage`、75 个 `tids` 集合、增删提示文案完全一致 |
| **图片上传响应（合成 8 例）** | `DISCUZUPLOAD|0|1721652|1`、`DISCUZUPLOAD|0|0|1`、段数不足、非法 ID、`"0"`、HTML 错误页 | 对照 `UploadImgHelper.java:210-227` | `UploadResponseParser.parse`：`Success(imageId)` / `Failed(reason)` | 合法 ID 与两类失败文案（`"无效上传图片ID"` / `"无法获取图片ID"`）逐字一致 |

---

## 四、单行变异自证日志

全部 4 组单行变异均在临时修改后立即触发单测变红（退出码 `1`），并在 `finally` 中完整还原（最终提交零变异）：

| 编号 | 变异目标与单行改动 | 运行任务 | 退出码 | 立刻变红的测试 |
|---|---|---|---|---|
| **M1** | `SearchParser.kt:37` 将 `DiscuzTextDecoder.decodeDoubleEscapedEntities(titleLink.text())` 改为 `titleLink.text()` | `:core-parser:test --tests SearchParserTest` | `1` | `SearchParserTest > parseTitleSearch_realSampleParsesSearchIdMaxPageItemsDoubleEscapedEmojiWithoutMutatingDom()` |
| **M2** | `PrePostParser.kt:101` 将 `"M" -> (size * 1024f * 1024f).toInt()` 改为 `"M" -> (size * 1024f).toInt()` | `:core-parser:test --tests PostFavoriteUploadParserTest` | `1` | `PostFavoriteUploadParserTest > prePostParser_realNewThreadSampleParsesFormFieldsUploadLimitAndOrderedTypeOptions()`、`prePostParser_syntheticEditQuoteReplyAndErrorFixturesCoverAllBranches()` |
| **M3** | `UserParser.kt:88` 将 `if (blacklistDivs.text().contains(EMPTY_BLACKLIST_MARKER))` 改为 `if (true)` | `:core-parser:test --tests UserParserTest` | `1` | `UserParserTest > parseBlacklist_coversPopulatedDeduplicatedEmptyConfirmedAndInvalidPages()` |
| **M4** | `UploadResponseParser.kt:31` 将 `if (imgId == "0" \|\| !DiscuzUrlHelper.isValidId(imgId))` 改为 `if (imgId.isEmpty())` | `:core-parser:test --tests PostFavoriteUploadParserTest` | `1` | `PostFavoriteUploadParserTest > uploadResponseParser_parsesValidZeroMissingAndInvalidResponses()` |

---

## 五、本地与 CI 验证结果

### 5.1 本地自检（遵守 DISPATCH §2 `flock` + `-Xmx3g` + `--stop`）
1. `:core-parser:test --rerun-tasks`：**69/69 通过**（`CoreParserPlaceholderTest` 1、`DiscuzCommonParserTest` 7、`LoginParserTest` 5、`SmsParserTest` 6、`NotifyParserTest` 4、`SearchParserTest` 3、`SimpleListParserTest` 4、`UserParserTest` 3、`PostFavoriteUploadParserTest` 4、`HtmlSanitizerTest` 27、`SamplesAreSanitizedTest` 5）
2. `:hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.*"`：**15/15 通过**（`AuthAndMessageParserEquivalenceTest` 5、`SearchAndSimpleListEquivalenceTest` 5、`UserAndPostFormEquivalenceTest` 5）
3. `:hipda:assembleDebug :hipda:lintDebug`：**通过**（`BUILD SUCCESSFUL`）
4. `./gradlew --stop`：已停止后台 Gradle Daemon。

### 5.2 GitHub Actions CI
- Run：[Android CI #45610947130](https://github.com/mailwmj/hipda-next/actions/runs/45610947130)（Commit `14dc33db512d1a4483292d0f72aa52a631e0b7b6`）
- 结论：**`completed / success`**；全量单测 **654/654 通过**，Build、Lint、Room Schema、Roborazzi 与 Debug/Release APK 产物均通过。

---

## 六、对外 API 清单（供 `D6` / `D8a` / `D8b` 复用）

### 6.1 搜索解析（`net.jejer.hipda.parser.search`）

- **`SearchModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/search/SearchModels.kt`)
  - `data class SearchItem(val tid: String?, val pid: String?, val title: String, val snippet: String?, val author: String?, val authorUid: String?, val avatarUrl: String?, val time: String?, val forumName: String?)` (`:5-15`)
  - `data class SearchPage(val searchId: String?, val maxPage: Int, val items: List<SearchItem>, val meta: DiscuzPageMeta)` (`:17-22`)

- **`SearchParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/search/SearchParser.kt`)
  - `object SearchParser` (`:15`)
    - `fun parse(html: String, fullText: Boolean = false, avatarBaseUrl: String? = null): SearchPage` (`:17`)
    - `fun parse(doc: Document, fullText: Boolean = false, avatarBaseUrl: String? = null): SearchPage` (`:20`)
    - `fun parseTitleSearch(html: String, avatarBaseUrl: String? = null): SearchPage` (`:27`)
    - `fun parseTitleSearch(doc: Document, avatarBaseUrl: String? = null): SearchPage` (`:30`)
    - `fun parseFullTextSearch(html: String, avatarBaseUrl: String? = null): SearchPage` (`:71`)
    - `fun parseFullTextSearch(doc: Document, avatarBaseUrl: String? = null): SearchPage` (`:74`)

### 6.2 简单列表解析（`net.jejer.hipda.parser.simplelist`）

- **`SimpleListModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/SimpleListModels.kt`)
  - `data class MyThreadItem(val tid: String, val title: String, val time: String, val forumName: String?)` (`:5-10`)
  - `data class MyThreadsPage(val maxPage: Int, val items: List<MyThreadItem>, val meta: DiscuzPageMeta, val hasTable: Boolean)` (`:12-17`)
  - `data class MyReplyItem(val tid: String, val pid: String, val title: String, val replyText: String?, val time: String, val forumName: String?)` (`:19-26`)
  - `data class MyRepliesPage(val maxPage: Int, val items: List<MyReplyItem>, val meta: DiscuzPageMeta, val hasTable: Boolean)` (`:28-33`)
  - `data class FavoriteThreadItem(val tid: String, val title: String, val time: String?, val forumName: String?)` (`:35-40`)
  - `data class FavoritesListPage(val maxPage: Int, val items: List<FavoriteThreadItem>, val meta: DiscuzPageMeta)` (`:42-46`)

- **`SimpleListParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParser.kt`)
  - `object SimpleListParser` (`:14`)
    - `fun parseMyThreads(html: String): MyThreadsPage` (`:16`)
    - `fun parseMyThreads(doc: Document): MyThreadsPage` (`:18`)
    - `fun parseMyReplies(html: String): MyRepliesPage` (`:62`)
    - `fun parseMyReplies(doc: Document): MyRepliesPage` (`:64`)
    - `fun parseFavoritesList(html: String): FavoritesListPage` (`:106`)
    - `fun parseFavoritesList(doc: Document): FavoritesListPage` (`:108`)

### 6.3 用户资料与黑名单解析（`net.jejer.hipda.parser.user`）

- **`UserModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/user/UserModels.kt`)
  - `data class UserProfile(val uid: String?, val username: String?, val isOnline: Boolean, val avatarUrl: String?, val formhash: String?, val detail: String, val meta: DiscuzPageMeta)` (`:5-13`)
  - `sealed interface BlacklistParseResult` (`:15`)
    - `data class Success(val usernames: List<String>, val isEmptyConfirmed: Boolean) : BlacklistParseResult` (`:16-19`)
    - `data class InvalidPage(val reason: String) : BlacklistParseResult` (`:21-23`)

- **`UserParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/user/UserParser.kt`)
  - `object UserParser` (`:13`)
    - `fun parseUserInfo(html: String): UserProfile` (`:20`)
    - `fun parseUserInfo(doc: Document): UserProfile` (`:22`)
    - `fun parseBlacklist(html: String): BlacklistParseResult` (`:67`)
    - `fun parseBlacklist(doc: Document): BlacklistParseResult` (`:69`)

### 6.4 发帖表单、收藏与上传响应解析（`net.jejer.hipda.parser.post`）

- **`PrePostModels.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/post/PrePostModels.kt`)
  - `enum class PrePostMode { NEW_THREAD, REPLY_THREAD, QUICK_REPLY, REPLY_POST, QUOTE_POST, EDIT_POST }` (`:5-12`)
  - `data class PrePostInfo(val formhash: String, val uid: String?, val hash: String?, val subject: String?, val text: String, val quoteText: String, val isDeletable: Boolean, val maxUploadFileSizeBytes: Int?, val noticeAuthor: String?, val noticeAuthorMsg: String?, val noticeTrimStr: String?, val existingImages: List<String>, val existingAttachments: List<String>, val typeId: String?, val typeOptions: Map<String, String>, val meta: DiscuzPageMeta)` (`:14-31`)
  - `sealed interface PrePostParseResult` (`:33`)
    - `data class Success(val info: PrePostInfo) : PrePostParseResult` (`:34`)
    - `data class Error(val message: String) : PrePostParseResult` (`:35`)

- **`PrePostParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/post/PrePostParser.kt`)
  - `object PrePostParser` (`:14`)
    - `fun parse(html: String, mode: PrePostMode): PrePostParseResult` (`:19`)
    - `fun parse(doc: Document, mode: PrePostMode): PrePostParseResult` (`:21`)

- **`FavoriteParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/post/FavoriteParser.kt`)
  - `data class FavoriteIdsPage(val tids: Set<String>, val lastPage: Int, val meta: DiscuzPageMeta)` (`:12-16`)
  - `data class FavoriteActionResult(val message: String)` (`:18-20`)
  - `object FavoriteParser` (`:26`)
    - `fun parseFavoriteIdsPage(html: String): FavoriteIdsPage` (`:28`)
    - `fun parseFavoriteIdsPage(doc: Document): FavoriteIdsPage` (`:30`)
    - `fun parseFavoriteActionXml(xml: String): FavoriteActionResult` (`:61`)

- **`UploadResponseParser.kt`** (`core-parser/src/main/kotlin/net/jejer/hipda/parser/post/UploadResponseParser.kt`)
  - `sealed interface UploadImageResult` (`:5`)
    - `data class Success(val imageId: String) : UploadImageResult` (`:6`)
    - `data class Failed(val reason: String) : UploadImageResult` (`:7`)
  - `object UploadResponseParser` (`:14`)
    - `fun parse(responseText: String): UploadImageResult` (`:20`)
