# 任务卡 D8a：搜索、今日新帖、收藏与关注 Repository（合并 D3d 搜索历史与收藏缓存）

【角色】你是 hipda-next 的 Android Kotlin 数据层、GBK 查询编码与 DataStore 持久化开发者（Kotlin 协程、`StateFlow`、Hilt、Preferences DataStore、MockWebServer）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/D8a`（分支 `card/D8a-search-favorite-repo`，基于 `origin/main` @ `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf`，需已合入 `D2`、`B9b`、`B7`）里工作。
- 允许新增/修改的范围（仅以下目录，与同波 `D9` 及在途 `D6`、`D5`、`C4d` 零文件重叠）：
  - `hipda/src/main/java/net/jejer/hipda/data/search/`（新增 `SearchModels.kt`、`SearchHistoryStore.kt`、`SearchRepository.kt`、`DefaultSearchRepository.kt`、`ForumLinkResolver.kt`）
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/`（新增 `FavoriteModels.kt`、`FavoriteCacheStore.kt`、`FavoriteRepository.kt`、`DefaultFavoriteRepository.kt`）
  - `hipda/src/main/java/net/jejer/hipda/di/SearchAndFavoriteModule.kt`（独立 Hilt `@Module`，提供 `search_history` 与 `favorite_cache` 两个非加密 Preferences DataStore 及 Repository 绑定）
  - `hipda/src/test/java/net/jejer/hipda/data/search/`（新增 `SearchHistoryStoreTest.kt`、`SearchRepositoryTest.kt`、`ForumLinkResolverTest.kt`）
  - `hipda/src/test/java/net/jejer/hipda/data/favorite/`（新增 `FavoriteCacheStoreTest.kt`、`FavoriteRepositoryTest.kt`）
- **严禁修改（多线零冲突守卫）**：
  - **与同波 `D9` 零重叠**：严禁修改 `service/`、`di/NotificationWorkModule.kt`、`data/message/`、`ui/HiNextApplication.kt`、`ui/HiApplication.java`、`src/main/AndroidManifest.xml`、`test/.../service/`、`test/.../data/message/`；
  - **与在途 `D6`、`D5`、`C4d` 零重叠**：
    - 零重叠于 **`D6`**（`data/post/`、`di/PostModule.kt`、`test/.../data/post/`）；
    - 零重叠于 **`D5`**（`data/threaddetail/`、`di/ThreadDetailModule.kt`、`test/.../data/threaddetail/`）；
    - 零重叠于 **`C4d`**（`ui/shell/`、`ui/designsystem/glass/`、`src/debug/`、`test/.../ui/`、`snapshots/`）；
  - 严禁修改 `data/settings/`（仅只读注入 `SettingsRepository`）、`di/SettingsModule.kt`、`data/auth/`、`di/AuthModule.kt`、`data/network/`、`di/HttpClientModule.kt`、`data/account/`、`data/local/`、`di/DatabaseModule.kt`、`data/user/`、`data/threadlist/`、`core-parser/`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、旧 Java 代码（`FavoriteHelper.java`、`SimpleListJob.java`、`FragmentUtils.java`、`HiUtils.java` 等）、样本文件与共享文件（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`.github/`、`MotionTokenScanTest`）。
- **测试约束**：严禁访问真实站点（`4d4y.com`），绝不用真实账号发起收藏/关注增删写操作；全部网络测试使用 `MockWebServer` 与本地脱敏样本；`search_history` 与 `favorite_cache` 均为非敏感本地缓存，不触碰加密备份排除规则。

【背景】
- 必读规格与计划：
  - `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`（第一节 1.4 三栏契约：`D8a` 承接左栏第 1 项 **`今日新帖`**、第二节卡 11 `D8a` 合并 `D3d`、第三节零重叠表）；
  - `docs/design/backlog.md` D3d、D8a；`docs/design/product-design.md` §3、§3.2（左栏 5 入口与 `我的` 分组）、§7.1（收藏/关注/删除搜索历史立即生效并支持 Snackbar 撤销）、§7.7（最近搜索可删除与撤销、筛选胶囊、0 条返回空态而非报错、搜索框识别站内链接）。
- **复用上游 API（引用报告对外 API 清单章节，不抄死签名，以基线 `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf` 实际源码为准）**：
  - `D2`：`/home/mir/Documents/hipda-next.wt/_reports/D2.md` 第四节（4.2–4.4 `ForumHttpClient` 含 `inajax=1` `isUnauthenticated` / `requireAuth = false` 下游提示、`AuthRepository`）；
  - `B9b` & `B7`：`/home/mir/Documents/hipda-next.wt/_reports/B9b.md` 第六节（6.1 `SearchParser`/`SearchPage`/`SearchItem`、6.2 `SimpleListParser`/`MyThreadsPage`/`MyRepliesPage`/`FavoritesListPage`、6.4 `FavoriteParser`/`FavoriteIdsPage`/`FavoriteActionResult`，且 `B7 @ db5857d` 已闭合 `R-B9b S1/S2/S4`）；
  - `B9a`：`/home/mir/Documents/hipda-next.wt/_reports/B9a.md` 第六节（6.1 `DiscuzCommonParser`、`DiscuzUrlHelper`）；
  - 既有参考实现（只读）：`di/SettingsModule.kt`（非加密 Preferences DataStore + `ReplaceFileCorruptionHandler` 建法）、`data/user/BlacklistStore.kt`（按账号分桶缓存参考）。
- 旧代码端点与规则参照（只读，已核对行号）：
  - `hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java:134-135,149,151-154,160-161`、`job/SimpleListJob.java:152-210`：
    - 标题/全文搜索（首页无 `searchId`）：`search.php?srchtype={title|fulltext}&srchtxt={gbkEncodedQuery}&searchsubmit=true&st=on&srchuname={gbkEncodedAuthor}&srchfilter=all&srchfrom=0&before=&orderby=lastpost&ascdesc=desc&srchfid%5B0%5D={fidOrAll}`（`srchtxt`/`srchuname` 按 **GBK** URL 编码，如 `URLEncoder.encode(..., "GBK")`；`page > 1` 追加 `&page={page}`）
    - 今日新帖（首页无 `searchId`）：`search.php?srchfrom=86400&searchsubmit=yes`
    - 带 `searchId` 翻页（搜索与今日新帖共用）：`search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`（`page > 1` 追加 `&page={page}`）
    - 我的帖子：`my.php?item=threads&page={page}`
    - 我的回复：`my.php?item=posts&page={page}`
    - 收藏 / 关注列表：`my.php?item={favorites|attention}&type=thread`（`page > 1` 追加 `&page={page}`）
    - 添加收藏 / 关注（AJAX XML）：`GET my.php?item={favorites|attention}&action=add&inajax=1&ajaxtarget=favorite_msg&tid={tid}`
    - 移除收藏 / 关注（AJAX XML）：`GET my.php?item={favorites|attention}&action=remove&inajax=1&ajaxtarget=favorite_msg&tid={tid}`
    - 列表页表单移除（可选补齐，`FavoriteHelper.java:261-295`）：`POST my.php?item={item}&type=thread`（GBK 表单 `favsubmit="true"` / `attentionsubmit="true"`、`delete[]={tid}`、`formhash`）
  - 站内链接识别参照：`hipda/src/main/java/net/jejer/hipda/ui/FragmentUtils.java:87-169`（`forumdisplay.php?fid=`、`viewthread.php?tid=&page=`、`redirect.php?goto=lastpost&tid=`、`redirect.php?goto=findpost&ptid=&pid=`、`gotopost.php?pid=`、`space.php?uid=`）。
- 真机脱敏样本（只读，4 份）：
  - `hipda/src/test/resources/samples/search/search_result.html`
  - `hipda/src/test/resources/samples/simple_list/my_threads.html`
  - `hipda/src/test/resources/samples/simple_list/my_posts.html`
  - `hipda/src/test/resources/samples/simple_list/my_favorites.html`
  - 合成夹具（测试内构造）：全文搜索 `spctrl` 页、搜索无结果空态页、搜索冷却报错页、收藏/关注增删 AJAX XML（成功 / 已收藏 / 未登录）。

【目标】实现 `SearchRepository`（标题/全文搜索、今日新帖、我的帖子/回复、站内链接识别与可撤销搜索历史存储 `SearchHistoryStore`）及 `FavoriteRepository`（收藏与关注列表加载、增删与撤销同步、按账号隔离的本地 `tid` 缓存 `FavoriteCacheStore`），解锁 `E10`/`E11` 与三栏左栏 `今日新帖`。

【范围】
1. **搜索、今日新帖、我的帖/回复与站内链接识别（`data/search/`）**：
   - **`ForumLinkResolver.kt`**：纯函数识别站内链接（兼容配置的 `forumServer` 主机、`4d4y.com`、历史 `hi-pda.com` 与相对脚本路径），返回密封类型 `ResolvedForumLink`：`Forum(fid)`、`Thread(tid, page)`、`ThreadLastPage(tid)`、`Post(tid, pid)`、`User(uid)`，非站内链接返回 `null`。
   - **`SearchHistoryStore.kt`（合并 `D3d`）**：独立非加密 Preferences DataStore（`search_history`，即 `search_history.preferences_pb`），配 `ReplaceFileCorruptionHandler { emptyPreferences() }` 与读流 `IOException` 兜底；提供 `history: Flow<List<String>>`（最新在前）、`record(query)`（去空白、去重移至队首、默认上限 `20` 条）、`remove(query)`（返回被删位置/项供 Snackbar 撤销）、`restore(query, index)`（撤销恢复原位）与 `clearAll()`。
   - **`SearchRepository` / `DefaultSearchRepository`**：
     - `search(query, author, forumId, fullText, page, searchId, priority)`：校验至少 `query` 或 `author` 非空（或已有 `searchId`），GBK 编码 `srchtxt`/`srchuname`，复用 `ForumHttpClient` 与 `B9b` `SearchParser.parse`；无结果提示（如“没有找到匹配结果”）返回成功的空页（`items = emptyList()`）而不报错，限频（如 15 秒搜索间隔）等真实错误返回 `SiteError`；成功触发搜索时可选写入 `SearchHistoryStore`；
     - `getNewPosts(page, searchId, priority)`：拉取今日新帖（`search.php?srchfrom=86400&searchsubmit=yes` 或 `searchid` 翻页），复用 `SearchParser.parseTitleSearch`；
     - `getMyThreads(page, priority)` / `getMyReplies(page, priority)`：拉取 `my.php?item=threads` / `my.php?item=posts`（`requireAuth = true`），复用 `B9b` `SimpleListParser`。
2. **收藏与关注仓储 + 本地 `tid` 缓存（`data/favorite/`，合并 `D3d`）**：
   - **`FavoriteCacheStore.kt`**：独立非加密 Preferences DataStore（`favorite_cache`，即 `favorite_cache.preferences_pb`），配 `ReplaceFileCorruptionHandler { emptyPreferences() }` 与 `IOException` 兜底，**按 `ownerUid` 分桶存储 `favoriteTids: Set<String>` 与 `attentionTids: Set<String>`**（防止切换账号串号泄露）。
   - **`FavoriteRepository` / `DefaultFavoriteRepository`**：
     - 响应式状态流：`favoriteTids: StateFlow<Set<String>>`、`attentionTids: StateFlow<Set<String>>`、`isFavorite(tid)`、`isAttention(tid)`；监听 `AuthRepository.currentAccount`：退出登录立即清零内存集，切换账号切换对应 `ownerUid` 桶；
     - `getList(kind: FavoriteKind, page: Int = 1, priority)`：拉取 `my.php?item={favorites|attention}&type=thread`（`requireAuth = true`），复用 `SimpleListParser.parseFavoritesList` 返回分页列表并同步将本页有效 `tid` 合并入本地缓存；
     - `syncCache(kind: FavoriteKind, maxPages: Int = 3, priority)`：按 `FavoriteHelper.java:58-92` 最多同步前 3 页 `FavoriteParser.parseFavoriteIdsPage`，仅在全部页面成功无误时覆写该账号本地缓存，中途出错原样保留旧缓存不误清；
     - `add(kind: FavoriteKind, tid: String, priority)` / `remove(kind: FavoriteKind, tid: String, priority)`（`Mutex` 串行，支持 Snackbar “撤销”即反向调用）：校验合法 `tid`；经 `ForumHttpClient.getHtml` 调用 `inajax=1` 端点（妥善处理无 `#umenu` 的 AJAX XML，例如复用 `D2` 自定义 `isUnauthenticated` 谓词或 `requireAuth = false` 避免正常回包误判未登录），交由 `B9b` `FavoriteParser.parseFavoriteActionXml` 判定；仅成功（或“已经收藏”类幂等提示）时更新 `FavoriteCacheStore` 并即时发射 `StateFlow`，网络/鉴权/站点失败时不脏改缓存。
3. **独立 Hilt 绑定（`di/SearchAndFavoriteModule.kt`）**：
   - 独立 `@Module @InstallIn(SingletonComponent::class)`，定义 `@SearchHistoryDataStore` 与 `@FavoriteCacheDataStore` 限定符，提供两份带 `ReplaceFileCorruptionHandler` 的 Preferences DataStore，并绑定 `@Singleton SearchRepository` 与 `@Singleton FavoriteRepository`。

【步骤】
1. 查阅基线 `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf` 源码及 `_reports/{D2,B9a,B9b,B7}.md` 对外 API 清单、`FavoriteHelper.java`、`SimpleListJob.java`、`FragmentUtils.java`。
2. 先写失败单测（TDD）：
   - `ForumLinkResolverTest`：覆盖 `4d4y.com`/`hi-pda.com`/自定义 `forumServer`/相对路径的 6 类站内链接与外部无关文本；
   - `SearchHistoryStoreTest`：覆盖去重置顶、20 条上限裁剪、单条删除与撤销恢复原位、清空、损坏 protobuf 文件与 `IOException` 回退（含直接调用 `SearchAndFavoriteModule` 提供方法校验损坏恢复）；
   - `SearchRepositoryTest`：覆盖真机样本 `search_result.html`/`my_threads.html`/`my_posts.html`、GBK 查询词/作者名 URL 编码断言、`searchId` 翻页、今日新帖路径、空结果不报错 vs 限频 `SiteError`；
   - `FavoriteCacheStoreTest` & `FavoriteRepositoryTest`：覆盖真机样本 `my_favorites.html`、多页 `syncCache` 同步与中途失败不清空缓存、`inajax=1` 添加/移除/撤销实时更新 `StateFlow`（不误触发未登录重试）、多账号切换与退登分桶隔离、损坏文件恢复与 `SearchAndFavoriteModule` Hilt 绑定校验。
3. 实现 `data/search/*`、`data/favorite/*` 与 `di/SearchAndFavoriteModule.kt`。
4. 本地按 DISPATCH §2 串行跑 `:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.search.*" --tests "net.jejer.hipda.data.favorite.*" --tests "net.jejer.hipda.data.account.BackupExclusionRulesTest"`、`:hipda:assembleDebug`、`:hipda:lintDebug`，提交推送 `card/D8a-search-favorite-repo` 并用 `gh run watch <id> --interval 60` 等 CI 全绿。

【自检】
- 先导出 mise 的 `ANDROID_HOME` / `JAVA_HOME`（见 `docs/agents/lesson.md`），按 DISPATCH §2 串行执行：
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.data.search.*" --tests "net.jejer.hipda.data.favorite.*" --tests "net.jejer.hipda.data.account.BackupExclusionRulesTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] **边界与多线零冲突守卫**：仅新增 `data/search/*`、`data/favorite/*`、`di/SearchAndFavoriteModule.kt` 与对应测试；与同波 `D9`（`service/`、`data/message/`、`HiNextApplication.kt`、`AndroidManifest.xml`）及在途 `D6`（`data/post/`）、`D5`（`data/threaddetail/`）、`C4d`（`ui/`、`src/debug/`、快照）及 `data/settings/`、`data/auth/`、`data/local/`、`core-parser/`、旧 Java 代码、样本、共享文件零重叠；全部测试纯离线 MockWebServer，`BackupExclusionRulesTest` 全绿。证据：`git diff --stat origin/main...HEAD`。
- [ ] **搜索、今日新帖、我的帖/回复、链接解析与搜索历史**：真机 4 份样本解析准确；中文搜索词/作者按 GBK URL 编码、`searchId` 翻页与今日新帖路径准确；无结果返回空态不报错；`ForumLinkResolver` 覆盖 6 类站内链接；`SearchHistoryStore` 去重、上限、删除与撤销恢复及损坏回退全部通过。证据：`SearchRepositoryTest` + `SearchHistoryStoreTest` + `ForumLinkResolverTest`。
- [ ] **收藏与关注列表、增删撤销与分账号缓存**：`favoriteTids`/`attentionTids` 响应式更新；`inajax=1` 增删与撤销不误判未登录、失败不脏改缓存；`syncCache` 中途异常不清空既有缓存；多账号切换/退登严格隔离且损坏文件自动恢复。证据：`FavoriteRepositoryTest` + `FavoriteCacheStoreTest`。
- [ ] **对外 API 清单与 CI 全绿**：`_reports/D8a.md` 写出完整对外 API 清单（签名 + 文件:行号，供 `E10`/`E11` 与三栏左栏 `今日新帖` 复用）；新增文件零 Lint 告警且 CI 全绿。证据：`_reports/D8a.md` + CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/D8a-search-favorite-repo`；Conventional Commits（如 `feat(data-search-favorite): 实现搜索今日新帖与收藏关注 Repository 及缓存存储`）。
- 详细证据、测试日志与对外 API 清单（签名 + 文件:行号）写入 `/home/mir/Documents/hipda-next.wt/_reports/D8a.md`；最后一条回复按文末通用约定控制在 ≤600 字。

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

首先 `cd /home/mir/Documents/hipda-next.wt/D8a`，确认 `git status` 干净、HEAD 为 29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf；首次推送用 `git push -u origin card/D8a-search-favorite-repo`。
