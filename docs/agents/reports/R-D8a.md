# 审查报告 R-D8a：搜索、今日新帖、收藏与关注 Repository（commit `fdcd52e`）

- **审查结论**：**CHANGES-REQUESTED**
- **审查对象**：`origin/card/D8a-search-favorite-repo` @ `fdcd52e844bbf167c42b4184f72dcdcf5290a3ea`
- **比较基线**：`origin/main` @ `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf`（`git merge-base 29c9e6f fdcd52e` 一致）
- **CI 核实**：[Android CI #45649404489](https://github.com/mailwmj/hipda-next/actions/runs/45649404489)（Job `139329450438`）
  - `headBranch` = `card/D8a-search-favorite-repo`，`headSha` = `fdcd52e844bbf167c42b4184f72dcdcf5290a3ea`，`status` = `completed`，`conclusion` = `success`；
  - 日志核实包含并全部通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest`（773/773）及 `:core-parser:test`（全仓总计 **871/871** 单测通过，0 failed，0 errors，0 skipped；`BackupExclusionRulesTest` 6/6、D8a 新增 5 个测试类 26/26 全绿）。

---

## 一、阻断项（Blocker）

### B1. `DefaultFavoriteRepository.classifyAjaxOutcome` 正向白名单遗漏 Discuz! 7.2 原生取消收藏与全部关注（增/幂等/删）XML 提示，导致真机 100% 误判 `SiteError` 且本地缓存失步

- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/DefaultFavoriteRepository.kt:403-405`
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/DefaultFavoriteRepository.kt:524-574`
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/DefaultFavoriteRepository.kt:618-636`
- **根因分析**：
  4d4y.com 运行标准 Discuz! 7.2（见样本 `my_favorites.html:7` `<meta name="generator" content="Discuz! 7.2">` 及 `thread_p1.html:11205-11206` 的 `my.php?item=favorites` / `my.php?item=attention&action=add`）。`DefaultFavoriteRepository` 为防止报错时脏改缓存，在 `classifyAjaxOutcome` 中设计了正向子串白名单（仅命中白名单才更新 `FavoriteCacheStore` 与 `StateFlow`，否则回退 `null` 并在 `:404-405` 返回 `FavoriteError.SiteError(message)`）。
  但白名单仅按单测合成文案（如 `"此主题已从您的收藏夹中移除"`、`"此主题已成功添加到关注列表中"`）编写，与 Discuz! 7.2 `templates/default/messages.lang.php` 的真实 `showmessage` 原文存在逐字偏差——**6 类标准 `inajax=1` 响应中有 4 类完全不命中白名单**：

  | 操作 | Discuz! 7.2 语言键 | `FavoriteParser.parseFavoriteActionXml` 剥离 `<a>`/`<script>` 后的真实文本 | 当前匹配结果 |
  |---|---|---|---|
  | `add(FAVORITE, tid)` 首次 | `favorite_add_thread_succeed` | `"此主题已成功添加到收藏夹中"` | 命中 `"已成功添加"` ✅ |
  | `add(FAVORITE, tid)` 重复 | `favorite_thread_exists` | `"您曾经收藏过这个主题"` | 命中 `"曾经收藏"` ✅ |
  | **`remove(FAVORITE, tid)`** | `favorite_remove_thread_succeed` | **`"此主题已成功从您的收藏夹中移除。"`** | **未命中** ❌（`REMOVE_SUCCESS_MARKERS` 写了 `"已从您的收藏"` 被 `"成功"` 隔断，写了 `"从收藏夹中移除"` 被 `"您的"` 隔断） |
  | **`add(ATTENTION, tid)` 首次** | `favoritethreads_add_succeed` | **`"指定主题已成功加入到关注列表中"`** | **未命中** ❌（`ADD_SUCCESS_MARKERS` 只写了 `"已成功添加"`/`"成功添加"`/`"关注成功"`，未覆盖 `"已成功加入"`/`"加入到关注列表"`） |
  | **`add(ATTENTION, tid)` 重复** | `favoritethreads_exists` | **`"您正在关注这个主题"`** | **未命中** ❌（`ALREADY_ADDED_MARKERS` 只写了 `"曾经关注"`/`"已经关注"`/`"已关注过"`，未覆盖 `"正在关注"`） |
  | **`remove(ATTENTION, tid)`** | `favoritethreads_remove_succeed` | **`"已取消对此主题的关注。"`** | **未命中** ❌（`REMOVE_SUCCESS_MARKERS` 只写了紧邻的 `"已取消关注"`，被 `"对此主题的"` 隔断） |

- **具体触发路径与后果**：
  1. **取消收藏 / 收藏后点 Snackbar“撤销”必现失败与缓存残留**：用户在 `E4b`（帖子详情底部三段工具条）或 `E11` 点击“取消收藏”（或刚点“收藏”后点 Snackbar“撤销”），调用 `FavoriteRepository.remove(FavoriteKind.FAVORITE, tid)` → `GET my.php?item=favorites&action=remove&inajax=1&ajaxtarget=favorite_msg&tid={tid}` → 服务端已在数据库删除收藏并返回 `<?xml ...?><root><![CDATA[此主题已成功从您的收藏夹中移除。]]></root>` → `DefaultFavoriteRepository.kt:403-405` 因 `classifyAjaxOutcome` 返回 `null` 误判为 `FavoriteError.SiteError("此主题已成功从您的收藏夹中移除。")`，且跳过 `:409-416` 的 `favoriteCacheStore.remove` 与 `publishSnapshotLocked`，导致界面报错的同时 `favoriteTids` / `isFavorite(tid)` 永久残留 `true`（本地状态与服务端反向失步）。
  2. **关注（`FavoriteKind.ATTENTION`）的 `inajax=1` 添加、幂等重添、取消与撤销全链路不可用**：用户在帖子详情“更多”菜单或 `E11` 点击“关注”或“取消关注”，调用 `FavoriteRepository.add(ATTENTION, tid)` / `remove(ATTENTION, tid)` → 服务端成功执行并返回 `"指定主题已成功加入到关注列表中..."` / `"您正在关注这个主题..."` / `"已取消对此主题的关注。"` → `DefaultFavoriteRepository.kt:403-405` **100% 返回 `FavoriteError.SiteError`**，`attentionTids` 与 `FavoriteCacheStore` 永远不更新。
- **修复建议**：
  1. 在 `DefaultFavoriteRepository.kt:524-565` 中补齐 Discuz! 7.2 原生词根（同时保留现有兼容词根与 `HARD_FAILURE_MARKERS` 前置拦截）：
     - `ADD_SUCCESS_MARKERS` 增加 `"已成功加入"`、`"成功加入"`、`"加入到关注列表"`、`"加入到收藏夹"`；
     - `ALREADY_ADDED_MARKERS` 增加 `"正在关注"`、`"正在收藏"`；
     - `REMOVE_SUCCESS_MARKERS` 增加 `"从您的收藏夹中移除"`、`"从您的关注列表中移除"`、`"已取消对此主题的关注"`、`"取消对此主题的关注"`。
  2. 在 `FavoriteRepositoryTest` 中使用 Discuz! 7.2 `messages.lang.php` 上述 4 条逐字原文（含 `<br /><a ...>` 尾随操作链接）新增回归断言，锁死 `FAVORITE` 与 `ATTENTION` 的完整增/幂等/删/撤销闭环。

---

## 二、四项审查维度逐项核查结果

### 1. 边界与多线零冲突守卫（通过）
- `git diff --name-status 29c9e6f...fdcd52e` 仅含 **15 个新增文件（`+4334 / -0`）**：
  - `hipda/src/main/java/net/jejer/hipda/data/search/{ForumLinkResolver,SearchModels,SearchHistoryStore,SearchRepository,DefaultSearchRepository}.kt`
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/{FavoriteModels,FavoriteCacheStore,FavoriteRepository,DefaultFavoriteRepository}.kt`
  - `hipda/src/main/java/net/jejer/hipda/di/SearchAndFavoriteModule.kt`
  - `hipda/src/test/java/net/jejer/hipda/data/search/{ForumLinkResolverTest,SearchHistoryStoreTest,SearchRepositoryTest}.kt`
  - `hipda/src/test/java/net/jejer/hipda/data/favorite/{FavoriteCacheStoreTest,FavoriteRepositoryTest}.kt`
- 与同波 `D9`（`service/`、`di/NotificationWorkModule.kt`、`data/message/`、`HiNextApplication.kt`、`HiApplication.java`、`AndroidManifest.xml`）及在途 `D6`（`data/post/`）、`D5`（`data/threaddetail/`）、`C4d`（`ui/`、`src/debug/`、快照）以及 `data/settings/`、`data/auth/`、`data/network/`、`data/account/`、`data/local/`、`core-parser/`、`backup_config.xml`/`data_extraction_rules.xml`、旧 Java 文件、样本与构建共享文件 **100% 零重叠**。
- 全部网络单测均指向本地 `MockWebServer`，零真实站点网络访问；两份新增 DataStore 均为非敏感非加密 Preferences DataStore，`BackupExclusionRulesTest` 6/6 全绿。

### 2. 搜索、今日新帖、我的帖/回复与站内链接识别（通过，附建议 S1/S2）
- **端点与 GBK 编码对齐**：
  - `DefaultSearchRepository.kt:322-347`：`srchtxt` 与 `srchuname` 先经 `toGbkSafe` 转十进制实体再按 `URLEncoder.encode(..., "GBK")` 编码；`srchtype={title|fulltext}`、`srchfid%5B0%5D={fid|all}`、`page > 1` 追加 `&page={page}` 与 `HiUtils.java:149`、`SimpleListJob.java:179-185` 完全一致。
  - `searchId` 翻页（`:335-337, 350-352`）：`search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`（`page > 1` 追加 `&page={page}`），对齐 `HiUtils.java:161`。
  - 今日新帖（`:283, 349-356`）：`search.php?srchfrom=86400&searchsubmit=yes`，对齐 `HiUtils.java:160`；支持已登录单飞重登与游客态浏览公开新帖、被拦返回 `SearchError.AuthRequired`。
  - 我的帖子 / 我的回复（`:126, 166, 286-287`）：`my.php?item=threads&page={page}`、`my.php?item=posts&page={page}`（`requireAuth = true`），对齐 `HiUtils.java:134-135` 与 `SimpleListJob.java:152-157`。
- **空搜索 vs 限频报错**：
  - Discuz! 7.2 `search_nomatch`（`"抱歉，没有找到匹配结果"` / `"对不起，没有找到匹配结果。"`）命中 `EMPTY_SEARCH_MARKERS`（`:291-302`），返回 `SearchResult.Success(items = emptyList())` 不报错；
  - 15 秒搜索间隔（`search_ctrl`）、系统限流（`search_toomany`）、搜索过期（`search_id_invalid`）等真实错误返回 `SearchError.SiteError`，且不污染搜索历史（`:77-84`）。
- **`ForumLinkResolver`（`ForumLinkResolver.kt:35-230`）**：
  - 完整覆盖 6 类站内链接：`Forum(fid)`、`Thread(tid, page)`、`ThreadLastPage(tid)`、`Post(tid, pid)`（同时兼容 `redirect.php?goto=findpost` 与 `gotopost.php`）、`User(uid)`；支持 `4d4y.com`/`*.4d4y.com`、`hi-pda.com`/`*.hi-pda.com`、动态 `forumServer`（含 `/forum` 后缀与端口）、无协议域名与相对脚本路径；严格拒绝仿冒域名（`evil-4d4y.com`、`4d4y.com.evil.com`）、非 HTTP(S) 协议、非论坛路径与非正整数 ID。
  - 相比旧版 `FragmentUtils.java:91` 硬编码 `HiUtils.isForumValid`，新版接受任意正整数 `fid`（兼容真机样本中出现的新增子版块如 `fid=70`），更契合 `product-design.md` §3.2 未知版块徽标回退设计。
- **`SearchHistoryStore`（`SearchHistoryStore.kt:36-170`）**：
  - 去空白、去重移至队首、默认上限 20 条、`remove(query)` 返回原位索引 `RemovedSearchHistoryEntry(query, index)`、`restore(query, index)` 原位恢复、`clearAll()`、损坏 JSON 与读流 `IOException` 回退空列表（非 `IOException` 正常透传）均实现正确。

### 3. 收藏/关注 AJAX XML、缓存同步与多账号隔离（除 B1 外其余机制通过，附建议 S2/S3）
- **`inajax=1` 鉴权判定**：`DefaultFavoriteRepository.kt:378-384, 598-616` 通过 `ForumHttpClient.getHtml(..., requireAuth = true, isUnauthenticated = ::isFavoriteAjaxUnauthenticated)` 处理无 `#umenu` 的 XML 响应——正常业务 XML 不触发无谓静默重登（`reloginCalls == 0`），会话失效 XML（如 Discuz! `not_loggedin` `"对不起，您还未登录，无法进行此操作。"`）走单飞静默重登重试一次；网络/鉴权/硬错误（如 `"收藏夹已满"`、`"指定的主题不存在或已被删除"`）均不脏改缓存。（正向成功白名单缺口见 **B1**。）
- **`syncCache` 多页同步**：`DefaultFavoriteRepository.kt:182-269` 默认最多拉取 3 页 `FavoriteParser.parseFavoriteIdsPage`，遇空页或 `page >= parsed.lastPage` 提前结束；仅在所有页均成功、会话归属一致且页面结构合法后才调用 `favoriteCacheStore.replace` 覆写，第 2 页失败或返回残缺 HTML 时原样保留旧缓存。
- **多账号隔离与并发安全**：
  - `FavoriteCacheStore` 按 `ownerUid` JSON 分桶持久化 `favoriteTids` 与 `attentionTids`，单测验证跨账号读写/清空互不干扰；
  - `DefaultFavoriteRepository` 监听 `authRepository.currentAccount`：退出登录立即清零内存集，切换账号先置空旧账号内存态再切入新桶，配合 `accountReadVersions`、`kindMutationVersions` 与 `verifySessionOwnership`（双向核对本地当前 `uid` 与响应 `#umenu` `uid`）杜绝在途跨账号串号；
  - 全程仅捕获 `IOException`，绝不吞 `CancellationException`。

### 4. DataStore 健壮性、对外 API 清单与测试盲区（通过，附建议 S4）
- `SearchAndFavoriteModule.kt:57-81` 为 `@SearchHistoryDataStore` 与 `@FavoriteCacheDataStore` 均配置了 `ReplaceFileCorruptionHandler { emptyPreferences() }`，且 `@Qualifier` 均使用 `AnnotationRetention.RUNTIME`，并由 `SearchHistoryStoreTest:184-200` 与 `FavoriteCacheStoreTest:164-179` 写入损坏 protobuf 字节直接调用生产 `provide*DataStore` 验证恢复。
- `_reports/D8a.md` 第四节（`:106-209`）列出的全部类名、方法签名、常量与文件行号经与 `fdcd52e` 源码逐行比对 **100% 精确一致**，接口契约完整覆盖 `E10`（搜索与站内链接直达、可撤销搜索历史）、`E11`（收藏与关注、我的主题/回复）、`E4b`（详情收藏/关注切换与撤销）及三栏左栏 `今日新帖`。

---

## 三、非阻断建议项（Suggestions）

- **S1（`DefaultSearchRepository.kt:258-279` 搜索与今日新帖残缺 200 页结构校验）**：`getMyThreads`（`:147`）、`getMyReplies`（`:187`）及 `FavoriteRepository.getList`/`syncCache` 均对既无列表容器又无空态标记的残缺 200 HTML 返回 `InvalidPage`，而 `executeSearchRequest` 在 `items.isEmpty()` 且无 `div.alert_error` 时未做页面结构校验（如检查 `datatable`/`searchlist`/`searchform`/`nodata`/`暂无数据`/`alert_info`），若遇网关截断 200 页会误返回空 `SearchResult.Success`（且 `search` 还会自动记入历史）。建议对齐补上结构校验。
- **S2（`DefaultSearchRepository.kt:83`、`DefaultFavoriteRepository.kt:161,248,339,408,410` DataStore 写入 `IOException` 防护）**：两份 Store 的读流已捕获 `IOException`，但 `dataStore.edit` 落盘异常未捕获——磁盘满时 `search` 已拿到服务端结果却会因 `searchHistoryStore.record` 抛未捕获 `IOException` 失败，`FavoriteRepository` 亦会逃逸未映射的 `IOException`（违背密封返回类型契约）。建议对自动记搜索历史与 `getList` 顺带合并缓存做 `IOException` 降级忽略，对 `syncCache`/`add`/`remove` 将写盘 `IOException` 映射为 `FavoriteError` 或保持内存态一致。
- **S3（`DefaultFavoriteRepository.kt:191-238` `syncCache` 锁粒度优化）**：`syncCache` 在最多 3 页网络请求期间全程持有 `operationMutex`，弱网下会阻塞用户前台点击 `add`/`remove`；建议仿照 `getList`（`:123-173`）在锁外拉取页面，锁内校验账号与 `kindMutationVersion` 未变后再落盘。
- **S4（“改坏关键一行测试仍通过”的单行突变盲区补强）**：
  1. `DefaultSearchRepository.kt:324`：把 `encodeGbkQueryParam` 中的 `toGbkSafe(raw)` 改为 `raw`，现有测试因查询词全在 GBK 基本面内而仍全绿；建议补一条含补充平面 Emoji 搜索词的编码断言。
  2. `DefaultFavoriteRepository.kt:157-158`：删掉 `getList` 合并缓存时的 `kindMutationVersionLocked(targetUid, kind) == mutationVersionBefore` 判断，现有测试仍全绿；建议补同账号在途 `getList` 期间并发执行 `remove` 不得复活已删 `tid` 的竞态单测。
  3. `DefaultFavoriteRepository.kt:632` 与 `DefaultSearchRepository.kt:268,375`：置空 `ALREADY_REMOVED_MARKERS` 分支或令 `isLoginRedirectOrFormPage` 恒返回 `false`，现有测试仍全绿；建议各补 1 条对应分支断言。
  4. `SearchHistoryStore.kt:127`：删掉 `restore` 的 `take(maxItems)` 上限裁剪，现有测试（仅测 ≤5 条）仍全绿；建议补满 20 条时的 `restore` 边界断言。

---

## 四、第 2 轮复审（commit `6046d62`）

- **复审结论**：**APPROVE**
- **复审对象**：`origin/card/D8a-search-favorite-repo` @ `6046d62cb734e8441a1ae2b7fbd015f2cb499731`（已合并 `origin/main` @ `6732dbd`）
- **改动范围核查**：
  - `git diff --name-status 6732dbd...6046d62` 仍仅含本卡允许范围内的 **15 个新增文件**，零触碰 `D9`/`D6`/`D5`/`C4d`/`data/settings/`/`data/auth/`/`core-parser/`/共享构建文件；
  - `git diff fdcd52e..6046d62`（剥离 `main` 合入部分）仅修改本卡内 6 个文件：`DefaultFavoriteRepository.kt`、`FavoriteModels.kt`、`DefaultSearchRepository.kt`、`FavoriteRepositoryTest.kt`、`SearchHistoryStoreTest.kt`、`SearchRepositoryTest.kt`。
- **CI 核实**：[Android CI #45656332519](https://github.com/mailwmj/hipda-next/actions/runs/45656332519)（Job `139353909936`，`headSha` = `6046d62cb734e8441a1ae2b7fbd015f2cb499731`，`completed / success`；`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:hipda:testDebugUnitTest` 834/834 + `:core-parser:test` 98/98 共 **932/932** 单测通过；D8a 5 个测试类由 26 增至 **36/36** 全绿）。

### 4.1 B1 反向核查：“失败排除 + 幂等判定”是否会把失败误判为成功？

逐路径追踪 `DefaultFavoriteRepository.mutateViaAjax`（`:401-473`）、`isFavoriteAjaxUnauthenticated`（`:629-647`）、`FavoriteParser.parseFavoriteActionXml`（`FavoriteParser.kt:61-71`）、`classifyAjaxOutcome`（`:657-676`）及 `removeByForm`（`:326-399`），**全部 6 类异常场景均严格判失败且零写缓存**：

| 失败场景 | 拦截链路与代码位置 | 返回结果 | 本地缓存 / `StateFlow` |
|---|---|---|---|
| **1. 未登录页**（XML `not_loggedin`、HTML `#loginform`、302 `logging.php`、`#umenu` 未登录） | `isFavoriteAjaxUnauthenticated`（`:629-647`）命中 `UNAUTH_AJAX_MARKERS` 或非 XML 登录页特征 → `ForumHttpClient` 最多单飞静默重登 1 次，仍未登录抛 `AuthRequiredException`（`:424-425`）；即便自定义未登录文案如 `"您所在的用户组(游客)无法进行此操作"` 漏过重登谓词，亦在 `:658` 命中 `HARD_FAILURE_MARKERS`（`"无法"`）拦截 | `FavoriteError.AuthRequired`（或 `SiteError`） | **不写缓存** |
| **2. 无权限**（`group_nopermission`、版块/阅读权限不足、`favorite_cannot_favorite`） | 命中 `HARD_FAILURE_MARKERS`（`"无法"`/`"不能"`/`"无权"`/`"没有权限"`/`:561-569`）或 `APOLOGY_FAILURE_MARKERS`（`"对不起"`/`"抱歉"`/`:580-583`） | `FavoriteError.SiteError` | **不写缓存** |
| **3. 超过收藏/关注上限**（`favorite_is_full` 等容量上限提示） | `HARD_FAILURE_MARKERS`（`"已满"`/`"上限"`/`"超限"`/`"超出"`/`"无法"`/`:561-574`）在幂等判断**之前**（`:658`）最先执行，即使文案含 `"收藏"`/`"关注"` 亦直接返回 `null`，外加 `"对不起"` 兜底 | `FavoriteError.SiteError` | **不写缓存** |
| **4. `formhash` 失效 / 非法提交**（`submit_invalid`） | `inajax=1` XML `"您的请求来路不正确…无法提交"` 命中 `HARD_FAILURE_MARKERS`（`"不正确"`/`"无法"`）；`removeByForm` 表单 POST 返回 `div.alert_error` 在 `:366-369` 拦截 | `FavoriteError.SiteError` | **不写缓存** |
| **5. 空响应 / 空 `<root>` / 仅含 `<script>` 或 `<a>` 的 `<root>`** | `FavoriteParser.parseFavoriteActionXml` 剥离 `<script>`/`<style>`/`<a>` 后 `message.isEmpty() == true` → `:434-441` 直接拦截，根本不进入 `classifyAjaxOutcome` | `FavoriteError.InvalidPage` | **不写缓存** |
| **6. HTML 错误页 / HTTP 非 2xx / 网关截断页** | 非 2xx 抛 `ForumHttpException` → `NetworkError`；200 HTML 页因无 `<root>` 节点使 `parseFavoriteActionXml` 返回 `""`，在 `:434-441` 若含 `div.alert_error` 返回 `SiteError(fallbackError)`，否则返回 `InvalidPage` | `NetworkError` / `SiteError` / `InvalidPage` | **不写缓存** |

同时，Discuz! 7.2 全部 6 类真实成功/幂等 XML（含 `"此主题已成功从您的收藏夹中移除。"`、`"指定主题已成功加入到关注列表中"`、`"您正在关注这个主题"`、`"已取消对此主题的关注。"`）均准确落入 `APPLIED` 或 `ALREADY_APPLIED`，**B1 彻底闭合**。

### 4.2 S1–S4 闭合与新单测回退反证核查

| 编号 | 生产修复位置 | 新增单测 | 回退生产修复后的反证表现 | 结论 |
|---|---|---|---|---|
| **B1** | `DefaultFavoriteRepository.kt:559-605, 657-676` | `FavoriteRepositoryTest.b1_discuz72InAjaxFavoriteAndAttentionFullLifecycleAndFailureGuard` | 回退至 `fdcd52e` 白名单后，`favRemove`、`attAdd`、`attDup`、`attRemove` 4 处断言立即因返回 `SiteError` 失败 | **已闭合** |
| **S1** | `DefaultSearchRepository.kt:279-281, 394-402` | `SearchRepositoryTest.s1_truncatedOrMalformed200SearchAndNewPostsReturnInvalidPageWithoutRecordingHistory` | 删掉 `hasSearchPageStructure` 校验后，截断 200 页返回空 `Success` 且污染搜索历史，测试立即变红 | **已闭合** |
| **S2** | `FavoriteModels.kt:76-79`、`DefaultSearchRepository.kt:83-87`、`DefaultFavoriteRepository.kt:160-175, 273-285, 371-382, 446-457` | `FavoriteRepositoryTest.s2_*`、`SearchRepositoryTest.s2_*`（同时验证 `CancellationException` 原样重抛） | 去掉 `try/catch (IOException)` 后，`faultDataStore` 抛出的 `IOException("No space left on device")` / `"disk full"` 直接击穿测试变红 | **已闭合** |
| **S3** | `DefaultFavoriteRepository.kt:198-302`（锁外拉取多页，锁内核对 `accountUidBefore` 与 `kindMutationVersion`） | `FavoriteRepositoryTest.s3_syncCacheReleasesMutexDuringNetworkFetchSoForegroundAddRemoveAreNotBlocked` | 回退为全程持 `operationMutex` 后，`syncCache` 卡在 `releaseSync.await(5s)` 时前台 `withTimeout(2.seconds) { repo.add(...) }` 必超时失败 | **已闭合** |
| **S4** | 锁定既有实现 4 处关键分支 | `s4_1` Emoji `toGbkSafe`、`s4_2` 在途 `getList` 并发 `remove`、`s4_3` 幂等移除与游客 `#loginform`/302 登录跳转、`s4_4` 满 20 条 `restore` 裁剪 | 分别回退对应单行（删 `toGbkSafe`、删 `kindMutationVersionLocked`、删 `ALREADY_REMOVED_MARKERS`/`isLoginRedirectOrFormPage`、删 `take(maxItems)`）均立即触发对应断言失败 | **已闭合** |

- **复审遗留阻断项**：无。
- **复审新增建议项**：无。
