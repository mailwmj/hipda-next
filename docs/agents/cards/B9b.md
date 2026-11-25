# 任务卡 B9b：搜索/简单列表/用户资料/黑名单/发帖表单/收藏/上传样本回归与新解析器

【角色】你是 hipda-next 的 Kotlin 纯 JVM HTML/XML 解析与测试开发者（Kotlin、Jsoup、JUnit 5、Robolectric）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/B9b`（分支 `card/B9b-parser-search-user-post`，基于 `origin/main` @ `d45b6a2a5f5c8bd28aa466778d094f646b9ba935`，需已合入 `B9a`）里工作。
- 允许新增/修改的范围（仅以下目录，与同波 `D2`、`B8` 及在途 `C4c` 零文件重叠）：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/search/`（新增 `SearchModels.kt`、`SearchParser.kt`）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/`（新增 `SimpleListModels.kt`、`SimpleListParser.kt`）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/user/`（新增 `UserModels.kt`、`UserParser.kt`）
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/post/`（新增 `PrePostModels.kt`、`PrePostParser.kt`、`FavoriteParser.kt`、`UploadResponseParser.kt`）
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/{search,simplelist,user,post}/*Test.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/{SearchAndSimpleListEquivalenceTest,UserAndPostFormEquivalenceTest}.kt`
- **严禁修改**：
  - **全部旧解析器与旧 Bean 只读不改**：严禁修改 `hipda/src/main/java/net/jejer/hipda/utils/HiParser.java`、`async/{PrePostAsyncTask,FavoriteHelper,UploadImgHelper,BlacklistHelper}.java`、`bean/*.java` 及整个 `hipda/src/main/`；
  - 与同波 `D2`、`B8` 零重叠：严禁修改 `core-parser/.../parser/{sanitize,common,auth,message,threaddetail,threadlist}/`、`SampleFixtures.kt`、`LegacyTestSupport.kt`、`hipda/src/main/java/.../data/`、`di/`；
  - 与在途 `C4c` / `G1` 零冲突：严禁修改 `ui/`、`data/settings/`、`src/debug/`、`src/test/snapshots/`；
  - 严禁修改 `hipda/src/test/resources/samples/*`、共享文件（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）及 `MotionTokenScanTest`。
- **`:core-parser` 纯洁性约束**：纯 JVM 零 Android 依赖、无设置读写、不启线程、**绝不修改调用方传入的 Jsoup `Document` DOM**；全部模型均为不可变 Kotlin `data class` / `sealed interface`。

【背景】
- 必读规格与计划：
  - `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`（第一节 1.3–1.4、第二节卡 4 `B9b`、第三节零重叠表）；
  - `docs/design/backlog.md` B5、B6（发帖表单/收藏）、B9（其余解析）；`docs/design/tech-plan.md` §5（T2）、§8（M2）；
  - `docs/agents/lesson.md`（Jsoup 1.24.1 用 `firstMatch` / `select(selector).firstOrNull()`；双重转义补充平面 Emoji `&amp;#\d{5,6};` 已在 `search/search_result.html` 实测证实）。
- **复用 `B9a` 公共基座**（重要）：
  - 以基线中已合入的 `core-parser` 源码及 `/home/mir/Documents/hipda-next.wt/_reports/B9a.md` 第六节“对外 API 清单”（6.1 `net.jejer.hipda.parser.common` 与 6.4 `SampleFixtures`、`LegacyTestSupport`）为准直接复用，不得重复实现 `DiscuzPageMeta`、`DiscuzTextDecoder`、`DiscuzUrlHelper`、`SampleFixtures` 或 `LegacyTestSupport`。
- 真机脱敏样本（6 份，均在 `hipda/src/test/resources/samples/`）：
  - `search/search_result.html`（标题搜索结果，含 `searchid`、`pages`、`tbody` 及双重转义 Emoji `&amp;#\d{5,6};`）
  - `simple_list/my_threads.html`（我的帖子，`parseMyPost`）
  - `simple_list/my_posts.html`（我的回复，`parseReplyList`）
  - `simple_list/my_favorites.html`（我的收藏，`parseFavorites` + `input.checkbox[name=delete[]]`）
  - `user_info/user_profile.html`（用户资料，`parseUserInfo`）
  - `post_form/newthread_fid2.html`（发新帖表单，`PrePostAsyncTask.parseRsp`）
  - **真样本缺口须补合成夹具**：全文搜索页（`div.sp_title`/`div.sp_content`/`div.sp_theard` 5 个 `span`）、黑名单页（`div.blacklist` 含条目/空态`"暂无数据"`/无效页）、编辑/回复/引用 `PrePost` 表单（含 `subject`、`input#delete`、`noticeauthor`/`noticeauthormsg`/`noticetrimstr`、`div.upfilelist` 已上传图片与 `insertAttachimgTag`/`insertAttachTag`、`div.uploadinfo` KB/MB 上限、缺 `formhash` 错页）、收藏/关注增删 XML 响应、图片上传响应（`DISCUZUPLOAD|0|<imgId>|1` / `0` / 非法响应）。
- 旧代码参照（只读，已核对行号）：
  - `hipda/src/main/java/net/jejer/hipda/utils/HiParser.java:57-211,525-785,806-825`（`parseReplyList`、`parseMyPost`、`parseSearch`、`parseSearchFullText`、`parseFavorites`、`parseUserInfo`、`parseBlacklist`）
  - `hipda/src/main/java/net/jejer/hipda/async/PrePostAsyncTask.java:86-229`（注意 `parseRsp` 为 `private`，在等价测试中通过测试侧反射调用旧方法作为只读神谕，**绝不修改 `PrePostAsyncTask.java`**）
  - `hipda/src/main/java/net/jejer/hipda/async/FavoriteHelper.java:104-133,218-257`
  - `hipda/src/main/java/net/jejer/hipda/async/UploadImgHelper.java:210-227`

【目标】以 6 份真机样本与合成夹具锁定旧代码全部其余解析入口，并在 `:core-parser` 实现搜索（标题/全文/今日新帖/TA 的帖子）、简单列表（我的帖子/我的回复/收藏/关注）、用户资料与黑名单、发帖预取表单、收藏与上传响应的纯 JVM 解析器，断言新旧逐字段等价。

【范围】
1. **搜索解析器（`parser/search/{SearchModels,SearchParser}.kt`）**：
   - 覆盖标题搜索（兼服务 `今日新帖` 与 `TA 的帖子`）和全文搜索：
     - `SearchPage(searchId: String?, maxPage: Int, items: List<SearchItem>, meta: DiscuzPageMeta)`
     - `SearchItem(tid: String?, pid: String?, title: String, snippet: String?, author: String?, authorUid: String?, avatarUrl: String?, time: String?, forumName: String?)`（`title`/`snippet` 经 B9a `DiscuzTextDecoder` 解码双重转义 Emoji；支持可选 `avatarBaseUrl` 参数）。
2. **简单列表解析器（`parser/simplelist/{SimpleListModels,SimpleListParser}.kt`）**：
   - `parseMyThreads(doc)`（对应旧 `parseMyPost`：`maxPage` + `MyThreadItem(tid, title, time, forumName)` + `meta` + `hasTable`）；
   - `parseMyReplies(doc)`（对应旧 `parseReplyList`：`maxPage` + `MyReplyItem(tid, pid, title, replyText, time, forumName)` + `meta` + `hasTable`）；
   - `parseFavoritesList(doc)`（对应旧 `parseFavorites`：`maxPage` + `FavoriteThreadItem(tid, title, time, forumName)` + `meta`）。
3. **用户资料与黑名单解析器（`parser/user/{UserModels,UserParser}.kt`）**：
   - `parseUserInfo(html / doc)`：`UserProfile(uid, username, isOnline, avatarUrl, formhash, detail, meta)`（逐字段对齐旧 `HiParser.parseUserInfo`）；
   - `parseBlacklist(html / doc)`：`BlacklistParseResult`（`Success(usernames: List<String>, isEmptyConfirmed: Boolean)` / `InvalidPage(reason: String)`，对齐旧 `HiParser.parseBlacklist` 的去重与“无条目且无`暂无数据`判解析错误”规则，但不抛受检 `Exception`）。
4. **发帖表单、收藏与上传响应解析器（`parser/post/{PrePostModels,PrePostParser,FavoriteParser,UploadResponseParser}.kt`）**：
   - `PrePostMode`（`NEW_THREAD`、`REPLY_THREAD`、`QUICK_REPLY`、`REPLY_POST`、`QUOTE_POST`、`EDIT_POST`）与 `PrePostParser.parse(doc, mode)`:
     - `PrePostParseResult`（`Success(info: PrePostInfo)` / `Error(message: String)`）；
     - `PrePostInfo`：`formhash`、`uid`、`hash`、`subject`、`text`、`quoteText`、`isDeletable`、`maxUploadFileSizeBytes: Int?`（纯数值返回，不写 `HiSettingsHelper`）、`noticeAuthor`、`noticeAuthorMsg`、`noticeTrimStr`、`existingImages: List<String>`（合并 `unusedimgattachlist`、`div.upfilelist img[id^=image_]` 与 `insertAttachimgTag`）、`existingAttachments: List<String>`（`insertAttachTag`）、`typeId`、`typeOptions: Map<String, String>`（保序 `LinkedHashMap`）、`meta: DiscuzPageMeta`。
   - `FavoriteParser`：
     - `parseFavoriteIdsPage(doc): FavoriteIdsPage(tids: Set<String>, lastPage: Int, meta: DiscuzPageMeta)`（对应 `FavoriteHelper.java:104-133` 的 `input.checkbox[name=delete[]]` 与分页）；
     - `parseFavoriteActionXml(xml: String): FavoriteActionResult(message: String)`（对应 `FavoriteHelper.java:218-257` `<root>` 截断 `<` 前文案）。
   - `UploadResponseParser.parse(responseText: String): UploadImageResult`（`Success(imageId: String)` / `Failed(reason: String)`，对齐 `UploadImgHelper.java:210-227` `DISCUZUPLOAD|0|<imgId>|1`）。
5. **测试与等价断言**：
   - `:core-parser:test` 4 组纯 JVM 单测（复用 B9a `SampleFixtures`）；
   - `hipda/src/test/java/net/jejer/hipda/parser/{SearchAndSimpleListEquivalenceTest,UserAndPostFormEquivalenceTest}.kt`（复用 B9a `LegacyTestSupport`）：6 份真机样本 + 全部合成夹具逐字段比对新旧输出，并断言原 `Document.outerHtml()` 零变异。

【步骤】
1. 阅读基线中已合入的 `B9a` 源码与 `_reports/B9a.md` 第六节“对外 API 清单”。
2. 先写失败测试（TDD）：在 6 份真机样本与合成夹具上建立旧代码回归基线（`PrePostAsyncTask.parseRsp` 在测试里用反射调用，不改生产文件）。
3. 实现 `search/`、`simplelist/`、`user/`、`post/` 4 组模型与解析器。
4. 补全 `:core-parser:test` 单元测试与两份新旧等价测试类。
5. **单行变异自证**（至少 3 组，跑完还原不提交）：如破坏 `SearchParser` 的 `searchId`/双重转义 Emoji 解码、破坏 `PrePostParser` 的 `typeOptions`/`maxUploadFileSizeBytes`、破坏 `UserParser` 黑名单空态判错或 `UploadResponseParser` `imgId=="0"` 拒收，确认测试立刻变红并记录进 `_reports/B9b.md`。
6. 本地按 DISPATCH §2 串行跑 `:core-parser:test`、`:hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.*EquivalenceTest"`、`:hipda:assembleDebug`、`:hipda:lintDebug`，核实零越界修改，提交推送 `card/B9b-parser-search-user-post`，用 `gh run watch <id> --interval 60` 等 CI 全绿。

【自检】
- 先导出 mise 的 `ANDROID_HOME` / `JAVA_HOME`，按 DISPATCH §2 串行执行：
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :core-parser:test -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.parser.SearchAndSimpleListEquivalenceTest" --tests "net.jejer.hipda.parser.UserAndPostFormEquivalenceTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] **只读边界与零冲突守卫**：全部旧解析器（`HiParser.java`、`PrePostAsyncTask.java`、`FavoriteHelper.java`、`UploadImgHelper.java`）及整个 `hipda/src/main/` 零改动；与同波 `D2`/`B8` 及在途 `C4c`/`G1` 零文件重叠；样本与共享文件零改动；测试全程离线；调用前后 `doc.outerHtml()` 完全一致。证据：`git diff --stat origin/main...HEAD`。
- [ ] **6 份真机样本与合成夹具逐字段等价**：
  - 搜索（`search_result.html` + 合成全文搜索）：`searchId`、`maxPage`、每条 `tid`/`pid`/`title`（含双重转义 Emoji）/`snippet`/`author`/`avatarUrl`/`time`/`forumName`；
  - 简单列表（`my_threads.html`、`my_posts.html`、`my_favorites.html`）：`maxPage`、每条 `tid`/`pid`/`title`/`replyText`/`time`/`forumName`；
  - 用户资料与黑名单（`user_profile.html` + 合成黑名单页）：`uid`、`username`、`isOnline`、`avatarUrl`、`formhash`、`detail`；黑名单用户名列表、空态与非法页错误；
  - 发帖表单/收藏/上传（`newthread_fid2.html` + 合成 edit/reply/quote/XML/upload）：`formhash`、`uid`、`hash`、`subject`、`text`/`quoteText`、`isDeletable`、`maxUploadFileSizeBytes`、3 个 `notice*` 字段、`existingImages`/`existingAttachments`、`typeId`/`typeOptions`；收藏 `tids`/`lastPage`/XML 文案；上传 `imageId`/失败态。
  - 证据：`SearchAndSimpleListEquivalenceTest` + `UserAndPostFormEquivalenceTest` 用例列表与 CI 汇总。
- [ ] **单行变异自证、对外 API 清单与 CI 全绿**：至少 3 组变异自证立刻变红；`_reports/B9b.md` 写出 4 组解析器与模型的完整对外 API 清单（签名 + 文件:行号，供 `D6`/`D8a`/`D8b` 复用）；CI 全绿。证据：`_reports/B9b.md` + CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/B9b-parser-search-user-post`；Conventional Commits（如 `feat(core-parser): 实现搜索简单列表用户资料与发帖收藏上传解析器`）。
- 详细证据、逐字段等价对照表、变异自证日志与对外 API 清单（签名 + 文件:行号）写入 `/home/mir/Documents/hipda-next.wt/_reports/B9b.md`；最后一条回复按文末通用约定控制在 ≤600 字。

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

首先 `cd /home/mir/Documents/hipda-next.wt/B9b`，确认 `git status` 干净、HEAD 为 d45b6a2a5f5c8bd28aa466778d094f646b9ba935；首次推送用 `git push -u origin card/B9b-parser-search-user-post`。
