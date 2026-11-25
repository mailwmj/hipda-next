# 任务卡 D8a 交付报告

- **分支**：`card/D8a-search-favorite-repo`（基线 `origin/main` @ `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf`）
- **最终 Commit**：`fdcd52e844bbf167c42b4184f72dcdcf5290a3ea`
- **CI Run**：[Android CI #45649404489](https://github.com/mailwmj/hipda-next/actions/runs/45649404489)（Job `139329450438`，`completed / success`；Build & Lint、Room Schema、Roborazzi 截图校验、Debug/Release APK 产物及全量 **871/871** 单元测试全部通过，0 failed，0 errors，0 skipped）

---

## 一、改动范围与多线零冲突守卫核查

### 1.1 `git diff --stat origin/main...HEAD`
共 **15 个新增文件**（`+4334 / -0`），工作区干净：
- **生产代码（10 个文件，共 2232 行）**：
  - `hipda/src/main/java/net/jejer/hipda/data/search/ForumLinkResolver.kt`（230 行）
  - `hipda/src/main/java/net/jejer/hipda/data/search/SearchModels.kt`（139 行）
  - `hipda/src/main/java/net/jejer/hipda/data/search/SearchHistoryStore.kt`（170 行）
  - `hipda/src/main/java/net/jejer/hipda/data/search/SearchRepository.kt`（68 行）
  - `hipda/src/main/java/net/jejer/hipda/data/search/DefaultSearchRepository.kt`（386 行）
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteModels.kt`（155 行）
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteCacheStore.kt`（275 行）
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteRepository.kt`（82 行）
  - `hipda/src/main/java/net/jejer/hipda/data/favorite/DefaultFavoriteRepository.kt`（644 行）
  - `hipda/src/main/java/net/jejer/hipda/di/SearchAndFavoriteModule.kt`（83 行）
- **测试代码（5 个文件，共 2102 行）**：
  - `hipda/src/test/java/net/jejer/hipda/data/search/ForumLinkResolverTest.kt`（175 行）
  - `hipda/src/test/java/net/jejer/hipda/data/search/SearchHistoryStoreTest.kt`（201 行）
  - `hipda/src/test/java/net/jejer/hipda/data/search/SearchRepositoryTest.kt`（680 行）
  - `hipda/src/test/java/net/jejer/hipda/data/favorite/FavoriteCacheStoreTest.kt`（180 行）
  - `hipda/src/test/java/net/jejer/hipda/data/favorite/FavoriteRepositoryTest.kt`（866 行）

### 1.2 零冲突与离线/备份安全守卫
- 与同波 `D9`（`service/`、`di/NotificationWorkModule.kt`、`data/message/`、`HiNextApplication.kt`、`HiApplication.java`、`AndroidManifest.xml`）及在途 `D6`/`D5`/`C4d` 零文件重叠。
- 禁止修改目录零改动：`data/settings/`、`di/SettingsModule.kt`、`data/auth/`、`data/network/`、`data/account/`、`data/local/`、`data/user/`、`data/threadlist/`、`core-parser/`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、旧 Java 代码、样本与共享构建配置均零改动。
- 全部网络测试 100% 离线使用 `MockWebServer` 与 4 份真机脱敏样本（`search_result.html`、`my_threads.html`、`my_posts.html`、`my_favorites.html`）+ 合成夹具，零访问 `4d4y.com`。
- `search_history.preferences_pb` 与 `favorite_cache.preferences_pb` 均为非敏感非加密本地缓存；`BackupExclusionRulesTest` 6/6 全绿。
- Lint 核查：`hipda/build/reports/lint-results-debug.sarif` 中 D8a 新增文件 **0 告警**。

---

## 二、核心实现说明

1. **站内链接解析（`ForumLinkResolver.kt`）**：
   - 纯函数支持 `4d4y.com`/`*.4d4y.com`、`hi-pda.com`/`*.hi-pda.com`、动态配置 `forumServer`（含 `/forum` 后缀及本地端口）、协议相对路径、无协议域名与相对脚本路径（`viewthread.php`、`/forum/...`、`./...`）。
   - 覆盖全部 6 类站内链接：`Forum(fid)`、`Thread(tid, page)`、`ThreadLastPage(tid)`、`Post(tid, pid)`（同时覆盖 `redirect.php?goto=findpost` 与 `gotopost.php`）、`User(uid)`；严格拒绝外部域名、仿冒域名（如 `evil-4d4y.com`/`4d4y.com.evil.com`）、非 HTTP(S) 协议、无关目录与非法/非正整数 ID。
2. **搜索历史持久化（`SearchHistoryStore.kt`，合并 D3d）**：
   - 独立非加密 Preferences DataStore `search_history.preferences_pb`，在 `SearchAndFavoriteModule` 装配 `ReplaceFileCorruptionHandler { emptyPreferences() }`，读流捕获 `IOException` 回退空列表（非 `IOException` 正常透传）。
   - 支持去空白、去重移至队首、默认上限 `20` 条、`remove(query)` 返回 `RemovedSearchHistoryEntry(query, index)`、`restore(query, index)` 原位恢复与 `clearAll()`。
3. **搜索、今日新帖与我的帖/回复（`DefaultSearchRepository.kt`）**：
   - `search(...)`：校验至少 `query`/`author` 非空或提供有效 `searchId`；`srchtxt`/`srchuname` 经 `toGbkSafe` + `URLEncoder.encode(..., "GBK")` 编码，`forumId == null` 映射 `srchfid%5B0%5D=all`；复用 `ForumHttpClient` 与 `SearchParser.parse`；无结果文案返回空 `SearchResult.Success`（不报错），15 秒冷却等站点错误返回 `SearchError.SiteError` 且不写入搜索历史；支持首页成功搜索可选自动记录历史。
   - `getNewPosts(...)`：承接三栏左栏“今日新帖”（`search.php?srchfrom=86400&searchsubmit=yes` 或 `searchid` 翻页），已登录走单飞静默重登，游客态允许公开内容并识别登录拦截至 `AuthRequired`。
   - `getMyThreads(...)` / `getMyReplies(...)`：拉取 `my.php?item=threads&page={page}` / `my.php?item=posts&page={page}`（`requireAuth = true`），复用 `SimpleListParser`，区分合法空态与残缺错页 `InvalidPage`。
4. **收藏与关注缓存及仓储（`FavoriteCacheStore.kt` + `DefaultFavoriteRepository.kt`，合并 D3d）**：
   - 独立非加密 Preferences DataStore `favorite_cache.preferences_pb`（配 `ReplaceFileCorruptionHandler` 与 `IOException` 兜底），按 `ownerUid` 分桶隔离 `favoriteTids` 与 `attentionTids`。
   - 监听 `AuthRepository.currentAccount`：退登立即清零内存集，切换账号立即切换至目标 `ownerUid` 桶，并采用按账号隔离的版本号防竞态（规避跨账号异步落盘覆盖与陈旧读回滚）。
   - `getList(kind, page)`：`requireAuth = true` 复用 `SimpleListParser.parseFavoritesList`，校验会话归属与页面结构后将有效 `tid` 合并入缓存（若请求在途发生显式增删/同步则跳过陈旧合并）。
   - `syncCache(kind, maxPages = 3)`：按旧 `FavoriteHelper` 语义最多拉取前 3 页 `FavoriteParser.parseFavoriteIdsPage`（遇末页或空页提前终止），仅在全部页无误且页面结构合法时覆写该账号缓存；中途任一失败或残缺页原样保留旧缓存。
   - `add` / `remove`（`Mutex` 串行，Snackbar 撤销即反向调用）与可选 `removeByForm`：经 `ForumHttpClient.getHtml(..., requireAuth = true, isUnauthenticated = ::isFavoriteAjaxUnauthenticated)` 调用 `inajax=1` 端点——正常无 `#umenu` XML 不误触发未登录重登，未登录 XML 复用单飞静默重登并重试一次；严格解析 `FavoriteParser.parseFavoriteActionXml`，仅确认成功或幂等提示（如“您曾经收藏过这个主题”）时更新缓存并即时发射 `StateFlow`，站点错误（如收藏夹已满、主题不存在或已被删除）/网络/鉴权失败绝不脏改缓存。

---

## 三、本地与 CI 验证证据

### 3.1 本地验证（严格遵守 DISPATCH §2 `flock` + `-Xmx3g` + `--stop`）
1. `:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.search.*" --tests "net.jejer.hipda.data.favorite.*" --tests "net.jejer.hipda.data.account.BackupExclusionRulesTest"`：**32/32 通过**
   - `ForumLinkResolverTest`（4/4）：
     - `resolve_allSixLinkTypesOnCanonical4d4yHost`
     - `resolve_supportsLegacyHipdaCustomForumServerAndSchemeLessUrls`
     - `resolve_supportsRelativeForumScriptPaths`
     - `resolve_rejectsExternalSpoofedInvalidSchemeMalformedAndPlainTextInputs`
   - `SearchHistoryStoreTest`（5/5）：
     - `record_trimsDeduplicatesMovesToFrontAndIgnoresBlankQueries`
     - `record_enforcesDefaultMaxTwentyItemsCap`
     - `removeRestoreAndClearAll_preserveExactIndexForSnackbarUndo`
     - `decodeAndReadFlow_recoversFromMalformedJsonAndIoException`
     - `productionModuleProvideSearchHistoryDataStore_recoversFromCorruptedProtobufFile`
   - `SearchRepositoryTest`（6/6）：
     - `search_realSampleEncodesChineseQueryAndAuthorInGbkDerivesAvatarAndRecordsHistory`
     - `search_fullTextAllForumsPaginationAndSearchIdBuildExactPaths`
     - `search_emptyResultReturnsEmptySuccessWhileRateLimitReturnsSiteError`
     - `getNewPosts_buildsInitialAndSearchIdPaginationPathsAndParsesRealSample`
     - `getMyThreadsAndMyReplies_parseRealSamplesEmptyStateAndMalformedPages`
     - `validationAuthGuestHttpChallengeAndHelpers_behaveDeterministically`
   - `FavoriteCacheStoreTest`（3/3）：
     - `buckets_isolateAccountsAndKindsSeparatelyAcrossReplaceMergeAddRemoveAndClear`
     - `decodeAndReadFlow_recoversFromMalformedJsonAndIoException`
     - `productionModuleProvideFavoriteCacheDataStore_recoversFromCorruptedProtobufFile`
   - `FavoriteRepositoryTest`（8/8）：
     - `getList_realSampleParsesFavoritesMergesCacheAndSupportsAttentionPagination`
     - `syncCache_capsAtMaxThreePagesAndPreservesExistingCacheOnMidwayFailure`
     - `addRemoveAndUndo_inAjaxXmlDoesNotTriggerFalseReloginAndUpdatesStateFlowAtomically`
     - `addAndRemove_failuresAndUnauthXmlNeverDirtyCacheAndSupportReloginRecovery`
     - `getListAndSyncCache_rejectServerUmenuUidMismatchWithoutPollutingCache`
     - `accountSwitchLogoutAndInFlightRace_strictlyIsolateBuckets`
     - `backgroundSilentPriority_abandonsChallengeWithoutInvokingSolver`
     - `searchAndFavoriteModule_declaresExpectedHiltBindingsAndDataStoreFiles`
   - `BackupExclusionRulesTest`（6/6）：全绿
2. `:hipda:assembleDebug :hipda:lintDebug`：`BUILD SUCCESSFUL`，D8a 新增文件 0 Lint 告警。
3. `./gradlew --stop`：已停止后台 Gradle Daemon。

### 3.2 GitHub Actions CI 最终证据
- **Run**：[Android CI #45649404489](https://github.com/mailwmj/hipda-next/actions/runs/45649404489)（Job `139329450438`，Commit `fdcd52e844bbf167c42b4184f72dcdcf5290a3ea`）
- **结果**：`completed / success`；Build & Lint、Room Schema 一致性、Roborazzi 截图验证、Debug/Release APK 产物全部通过；全量单测 `TOTAL: 871 tests, 871 passed, 0 failed, 0 errors, 0 skipped`。

---

## 四、对外 API 清单（供 `E10` / `E11` 与三栏左栏 `今日新帖` 复用）

### 4.1 站内链接解析（`net.jejer.hipda.data.search`）
- **`ForumLinkResolver.kt`**（`hipda/src/main/java/net/jejer/hipda/data/search/ForumLinkResolver.kt`）
  - `sealed interface ResolvedForumLink`（`:12-18`）
    - `data class Forum(val fid: Int) : ResolvedForumLink`（`:13`）
    - `data class Thread(val tid: String, val page: Int = 1) : ResolvedForumLink`（`:14`）
    - `data class ThreadLastPage(val tid: String) : ResolvedForumLink`（`:15`）
    - `data class Post(val tid: String?, val pid: String) : ResolvedForumLink`（`:16`）
    - `data class User(val uid: String) : ResolvedForumLink`（`:17`）
  - `object ForumLinkResolver`（`:30`）
    - `fun resolve(rawInput: String, forumServer: String = AppSettings.DEFAULT_FORUM_SERVER): ResolvedForumLink?`（`:35-87`）

### 4.2 搜索模型与历史存储（`net.jejer.hipda.data.search`）
- **`SearchModels.kt`**（`hipda/src/main/java/net/jejer/hipda/data/search/SearchModels.kt`）
  - `data class RemovedSearchHistoryEntry(val query: String, val index: Int)`（`:21-24`）
  - `sealed interface SearchError : SearchResult, MyThreadsResult, MyRepliesResult`（`:29-63`）
    - `data class AuthRequired(val reason: AuthRequiredReason, val reloginResult: SilentReloginResult? = null, val message: String = "") : SearchError`（`:34-38`）
    - `data class ChallengeRequired(val cooldownUntilMs: Long? = null, val rejected: Boolean = false, val abandoned: Boolean = false) : SearchError`（`:40-44`）
    - `data class NetworkError(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : SearchError`（`:46-50`）
    - `data class SiteError(val message: String) : SearchError`（`:52-54`）
    - `data class InvalidPage(val message: String) : SearchError`（`:56-58`）
    - `data class InvalidInput(val message: String) : SearchError`（`:60-62`）
  - `sealed interface SearchResult`（`:66-77`）
    - `data class Success(val page: Int, val fullText: Boolean, val searchPage: SearchPage) : SearchResult`（`:67-76`，含计算属性 `items`、`:72`、`searchId` `:73`、`maxPage` `:74`、`hasNextPage` `:75`）
  - `sealed interface MyThreadsResult`（`:80-89`）
    - `data class Success(val page: Int, val threadsPage: MyThreadsPage) : MyThreadsResult`（`:81-88`，含计算属性 `items` `:85`、`maxPage` `:86`、`hasNextPage` `:87`）
  - `sealed interface MyRepliesResult`（`:92-101`）
    - `data class Success(val page: Int, val repliesPage: MyRepliesPage) : MyRepliesResult`（`:93-100`，含计算属性 `items` `:97`、`maxPage` `:98`、`hasNextPage` `:99`）
- **`SearchHistoryStore.kt`**（`hipda/src/main/java/net/jejer/hipda/data/search/SearchHistoryStore.kt`）
  - `@Singleton class SearchHistoryStore`（`:36-170`，`@Inject constructor(@SearchHistoryDataStore dataStore: DataStore<Preferences>)` `:41-47`）
    - `val history: Flow<List<String>>`（`:54-63`）
    - `suspend fun read(): List<String>`（`:66`）
    - `suspend fun record(query: String): List<String>`（`:72-89`）
    - `suspend fun remove(query: String): RemovedSearchHistoryEntry?`（`:95-112`）
    - `suspend fun restore(query: String, index: Int): List<String>`（`:118-132`）
    - `suspend fun restore(entry: RemovedSearchHistoryEntry): List<String>`（`:135-136`）
    - `suspend fun clearAll()`（`:139-143`）
    - `const val DEFAULT_MAX_ITEMS: Int = 20`（`:168`）

### 4.3 搜索仓储（`net.jejer.hipda.data.search`）
- **`SearchRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/search/SearchRepository.kt`）
  - `interface SearchRepository`（`:11-68`）
    - `val searchHistory: Flow<List<String>>`（`:14`）
    - `suspend fun search(query: String = "", author: String = "", forumId: Int? = null, fullText: Boolean = false, page: Int = 1, searchId: String? = null, priority: AccessPriority = AccessPriority.UserBlocking, recordHistory: Boolean = true): SearchResult`（`:22-31`）
    - `suspend fun getNewPosts(page: Int = 1, searchId: String? = null, priority: AccessPriority = AccessPriority.UserBlocking): SearchResult`（`:36-40`）
    - `suspend fun getMyThreads(page: Int = 1, priority: AccessPriority = AccessPriority.UserBlocking): MyThreadsResult`（`:43-46`）
    - `suspend fun getMyReplies(page: Int = 1, priority: AccessPriority = AccessPriority.UserBlocking): MyRepliesResult`（`:49-52`）
    - `suspend fun recordSearchHistory(query: String): List<String>`（`:55`）
    - `suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry?`（`:58`）
    - `suspend fun restoreSearchHistory(query: String, index: Int): List<String>`（`:61`）
    - `suspend fun clearSearchHistory()`（`:64`）
    - `suspend fun resolveForumLink(rawInput: String): ResolvedForumLink?`（`:67`）
- **`DefaultSearchRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/search/DefaultSearchRepository.kt`）
  - `@Singleton class DefaultSearchRepository @Inject constructor(forumHttpClient: ForumHttpClient, authRepository: AuthRepository, settingsRepository: SettingsRepository, searchHistoryStore: SearchHistoryStore) : SearchRepository`（`:21-386`）

### 4.4 收藏与关注模型、缓存存储与仓储（`net.jejer.hipda.data.favorite`）
- **`FavoriteModels.kt`**（`hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteModels.kt`）
  - `enum class FavoriteKind(val itemParam: String, val formSubmitField: String)`（`:17-20`）：`FAVORITE("favorites", "favsubmit")`、`ATTENTION("attention", "attentionsubmit")`
  - `data class FavoriteCacheSnapshot(val ownerUid: String? = null, val favoriteTids: Set<String> = emptySet(), val attentionTids: Set<String> = emptySet(), val lastFavoriteSyncedAtMs: Long? = null, val lastAttentionSyncedAtMs: Long? = null)`（`:25-36`，`fun tidsFor(kind: FavoriteKind): Set<String>` `:32-35`）
  - `sealed interface FavoriteError : FavoriteListResult, FavoriteSyncResult, FavoriteMutationResult`（`:41-75`）
    - `AuthRequired`（`:46-50`）、`ChallengeRequired`（`:52-56`）、`NetworkError`（`:58-62`）、`SiteError`（`:64-66`）、`InvalidPage`（`:68-70`）、`InvalidInput`（`:72-74`）
  - `sealed interface FavoriteListResult`（`:78-89`）
    - `data class Success(val kind: FavoriteKind, val page: Int, val listPage: FavoritesListPage) : FavoriteListResult`（`:79-88`，含计算属性 `items` `:84`、`maxPage` `:85`、`formhash` `:86`、`hasNextPage` `:87`）
  - `sealed interface FavoriteSyncResult`（`:92-100`）
    - `data class Success(val kind: FavoriteKind, val tids: Set<String>, val pagesSynced: Int, val lastPage: Int, val syncedAtMs: Long) : FavoriteSyncResult`（`:93-99`）
  - `sealed interface FavoriteMutationResult`（`:103-110`）
    - `data class Success(val kind: FavoriteKind, val tid: String, val message: String, val alreadyApplied: Boolean = false) : FavoriteMutationResult`（`:104-109`）
- **`FavoriteCacheStore.kt`**（`hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteCacheStore.kt`）
  - `@Singleton class FavoriteCacheStore @Inject constructor(@FavoriteCacheDataStore dataStore: DataStore<Preferences>)`（`:41-275`）
    - `fun observe(ownerUid: String): Flow<FavoriteCacheSnapshot>`（`:57-62`）
    - `suspend fun read(ownerUid: String): FavoriteCacheSnapshot`（`:65`）
    - `suspend fun replace(ownerUid: String, kind: FavoriteKind, tids: Collection<String>, syncedAtMs: Long): FavoriteCacheSnapshot`（`:68-92`）
    - `suspend fun merge(ownerUid: String, kind: FavoriteKind, tids: Collection<String>): FavoriteCacheSnapshot`（`:95-116`）
    - `suspend fun add(ownerUid: String, kind: FavoriteKind, tid: String): FavoriteCacheSnapshot`（`:119-139`）
    - `suspend fun remove(ownerUid: String, kind: FavoriteKind, tid: String): FavoriteCacheSnapshot`（`:142-162`）
    - `suspend fun clear(ownerUid: String)`（`:165-177`）
    - `suspend fun clearAll()`（`:180-184`）
- **`FavoriteRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteRepository.kt`）
  - `interface FavoriteRepository`（`:11-82`）
    - `val favoriteTids: StateFlow<Set<String>>`（`:14`）
    - `val attentionTids: StateFlow<Set<String>>`（`:17`）
    - `fun isFavorite(tid: String): Boolean`（`:20`）
    - `fun isAttention(tid: String): Boolean`（`:23`）
    - `suspend fun getList(kind: FavoriteKind, page: Int = 1, priority: AccessPriority = AccessPriority.UserBlocking): FavoriteListResult`（`:29-33`）
    - `suspend fun syncCache(kind: FavoriteKind, maxPages: Int = DEFAULT_SYNC_MAX_PAGES, priority: AccessPriority = AccessPriority.UserBlocking): FavoriteSyncResult`（`:40-44`）
    - `suspend fun add(kind: FavoriteKind, tid: String, priority: AccessPriority = AccessPriority.UserBlocking): FavoriteMutationResult`（`:51-55`）
    - `suspend fun remove(kind: FavoriteKind, tid: String, priority: AccessPriority = AccessPriority.UserBlocking): FavoriteMutationResult`（`:62-66`）
    - `suspend fun removeByForm(kind: FavoriteKind, tid: String, formhash: String, priority: AccessPriority = AccessPriority.UserBlocking): FavoriteMutationResult`（`:72-77`）
    - `const val DEFAULT_SYNC_MAX_PAGES: Int = 3`（`:80`）
- **`DefaultFavoriteRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/favorite/DefaultFavoriteRepository.kt`）
  - `@Singleton class DefaultFavoriteRepository : FavoriteRepository`（`:30-644`，`@Inject constructor` `:38-50`）

### 4.5 Hilt 依赖注入模块（`net.jejer.hipda.di`）
- **`SearchAndFavoriteModule.kt`**（`hipda/src/main/java/net/jejer/hipda/di/SearchAndFavoriteModule.kt`）
  - `@Qualifier annotation class SearchHistoryDataStore`（`:27-29`）
  - `@Qualifier annotation class FavoriteCacheDataStore`（`:31-33`）
  - `@Module @InstallIn(SingletonComponent::class) abstract class SearchAndFavoriteModule`（`:35-83`）
    - `@Binds @Singleton abstract fun bindSearchRepository(impl: DefaultSearchRepository): SearchRepository`（`:39-41`）
    - `@Binds @Singleton abstract fun bindFavoriteRepository(impl: DefaultFavoriteRepository): FavoriteRepository`（`:43-45`）
    - `const val SEARCH_HISTORY_STORE_NAME: String = "search_history"`（`:48`）
    - `const val FAVORITE_CACHE_STORE_NAME: String = "favorite_cache"`（`:49`）
    - `@Provides @Singleton @SearchHistoryDataStore fun provideSearchHistoryDataStore(@ApplicationContext context: Context): DataStore<Preferences>`（`:57-68`）
    - `@Provides @Singleton @FavoriteCacheDataStore fun provideFavoriteCacheDataStore(@ApplicationContext context: Context): DataStore<Preferences>`（`:70-81`）

---

## 五、修复轮 1（闭合 R-D8a B1 与 S1–S4，合并 `origin/main` @ `6732dbd`）

- **最新 Commit**：`6046d62cb734e8441a1ae2b7fbd015f2cb499731`（先合并 `origin/main` @ `6732dbd` 含 `D5`/`D6`，合并提交 `f1f18fb`，零冲突；`git diff --stat origin/main...HEAD` 仍仅含本卡 15 个新增文件，与禁止目录及其他卡 100% 零重叠）
- **最新 CI Run**：[Android CI #45656332519](https://github.com/mailwmj/hipda-next/actions/runs/45656332519)（Job `139353909936`，`completed / success`；Build & Lint、Room Schema、Roborazzi 截图验证、Debug/Release APK 产物及全量 **932/932** 单测通过：`:hipda:testDebugUnitTest` 834/834 + `:core-parser:test` 98/98，0 failed，0 errors，0 skipped；D8a 5 个测试类 **36/36** 全绿，`BackupExclusionRulesTest` 6/6 全绿；D8a 文件 0 Lint 告警）

### 5.1 逐项修复与对应测试清单

1. **B1（阻断：重构 `DefaultFavoriteRepository.classifyAjaxOutcome`，对齐旧版 `FavoriteHelper.java` 语义，消除脆弱正向白名单）**：
   - **核对结论**：经核对旧版 `FavoriteHelper.java:218-228, 247-257`、`samples/simple_list/my_favorites.html:7`（`Discuz! 7.2`）、`samples/thread_detail/thread_p1.html:11205-11206` 与 `PostFavoriteUploadParserTest.kt:216, 225`，旧版对合法 `<root>` XML 响应不依赖脆弱正向子串白名单枚举。
   - **修复**：`DefaultFavoriteRepository.classifyAjaxOutcome`（`:657-676`）改为“排除未登录（由 `isFavoriteAjaxUnauthenticated` 拦截重登）、硬失败词根（`HARD_FAILURE_MARKERS`）与非幂等道歉类错误（`APOLOGY_FAILURE_MARKERS`）后默认判定成功，同时精确识别幂等已存在/未存在（含 `正在关注`/`正在收藏`/`曾经收藏`/`没有收藏` 等）返回 `alreadyApplied = true`”，既与旧版一致不因 Discuz! 7.2 措辞差异误报 `SiteError`，又对真正失败返回 `SiteError` 不脏改缓存。
   - **对应测试**：`FavoriteRepositoryTest.b1_discuz72InAjaxFavoriteAndAttentionFullLifecycleAndFailureGuard`（覆盖 `FAVORITE` 与 `ATTENTION` 各自的 add、幂等重复添加、remove、undo 及 3 类真实失败反例，断言缓存与 `StateFlow` 严格同步且 `reloginCalls == 0`）。

2. **S1（搜索与今日新帖 200 残缺页返回 `InvalidPage`）**：
   - **修复**：`DefaultSearchRepository.executeSearchRequest`（`:279-281`）与 `hasSearchPageStructure`（`:394-402`）在 `items.isEmpty()` 且无 `alert_error`/登录页特征时校验页面结构（`datatable`/`searchlist`/`searchform`/`alert_info`/`nodata`/`暂无数据`/`找到相关主题`/`没有找到`），残缺 200 页返回 `SearchError.InvalidPage` 且不写入搜索历史。
   - **对应测试**：`SearchRepositoryTest.s1_truncatedOrMalformed200SearchAndNewPostsReturnInvalidPageWithoutRecordingHistory`。

3. **S2（`DataStore.edit` 落盘 `IOException` 防逃逸与 `CancellationException` 透传）**：
   - **修复**：
     - `FavoriteModels.kt:76-79` 新增 `FavoriteError.StorageError(val message: String, val cause: Throwable? = null)`；
     - `DefaultSearchRepository.search`（`:83-87`）与 `DefaultFavoriteRepository.getList`（`:160-175`）对顺带落盘捕获 `IOException` 降级忽略；
     - `DefaultFavoriteRepository.syncCache`（`:262-274`）、`removeByForm`（`:371-382`）、`mutateViaAjax`（`:443-455`）捕获 `IOException` 映射为 `FavoriteError.StorageError`；全程仅捕获 `IOException`，`CancellationException` 原样重抛。
   - **对应测试**：
     - `FavoriteRepositoryTest.s2_dataStoreWriteIoExceptionMappedToStorageErrorOrDegradedAndCancellationPropagates`
     - `SearchRepositoryTest.s2_historyStoreWriteIoExceptionDoesNotFailSearchWhileCancellationPropagates`

4. **S3（`syncCache` 多页网络请求移出 `operationMutex`，仅写缓存持锁并校验版本）**：
   - **修复**：`DefaultFavoriteRepository.syncCache`（`:189-290`）在锁外采集 `accountUidBefore`/`mutationVersionBefore` 并完成多页网络拉取，仅落盘时获取 `operationMutex` 复核账号与 `kindMutationVersion`（若在途已有前台增删则不以陈旧同步快照覆写）。
   - **对应测试**：`FavoriteRepositoryTest.s3_syncCacheReleasesMutexDuringNetworkFetchSoForegroundAddRemoveAreNotBlocked`。

5. **S4（4 处单行突变盲区单测补齐）**：
   - Emoji 搜索词 `toGbkSafe` 编码：`SearchRepositoryTest.s4_1_searchEncodesSupplementaryEmojiViaToGbkSafeDecimalEntities`
   - `getList` 在途并发 `remove` 版本守卫：`FavoriteRepositoryTest.s4_2_inFlightGetListDoesNotResurrectTidRemovedConcurrently`
   - 幂等移除 & 游客登录表单/跳转拦截：`FavoriteRepositoryTest.s4_3_idempotentAlreadyRemovedAjaxMessagesReturnAlreadyAppliedAndClearCache`、`SearchRepositoryTest.s4_3_guestLoginFormPageOrLoggingRedirectReturnsAuthRequired`
   - `SearchHistoryStore` 20 条满载恢复裁剪：`SearchHistoryStoreTest.s4_4_restoreAtFullTwentyItemCapacityEnforcesMaxItemsCap`

### 5.2 修复轮 1 新增对外 API 签名
- **`FavoriteModels.kt`**（`hipda/src/main/java/net/jejer/hipda/data/favorite/FavoriteModels.kt`）
  - `FavoriteError.StorageError(val message: String, val cause: Throwable? = null) : FavoriteError`（`:76-79`）
