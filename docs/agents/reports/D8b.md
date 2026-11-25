# 任务卡 D8b 交付报告

- **分支**：`card/D8b-user-blacklist-repo`（基线 `origin/main` @ `0e5fb577d04c86ad505b3a3af7f859f1eaff914e`）
- **最终 Commit**：`040e6e2444d3cc5eb4a51ff6912828c2b91e420e`
- **CI Run**：[Android CI #45630084159](https://github.com/mailwmj/hipda-next/actions/runs/45630084159)（Job `139260602687`，`completed / success`；Build & Lint、Room Schema、全量 **768/768** 单元测试、Roborazzi 截图验证、Debug/Release APK 产物全部通过）

---

## 一、改动范围与边界守卫核查

### 1.1 `git diff --stat origin/main...HEAD`
共 10 个新增文件（`+2493 / -0`），工作区干净：
- **生产代码（7 个文件，共 1021 行）**：
  - `hipda/src/main/java/net/jejer/hipda/data/user/UserModels.kt`（146 行）
  - `hipda/src/main/java/net/jejer/hipda/data/user/UserRepository.kt`（34 行）
  - `hipda/src/main/java/net/jejer/hipda/data/user/DefaultUserRepository.kt`（176 行）
  - `hipda/src/main/java/net/jejer/hipda/data/user/BlacklistStore.kt`（210 行）
  - `hipda/src/main/java/net/jejer/hipda/data/user/BlacklistRepository.kt`（59 行）
  - `hipda/src/main/java/net/jejer/hipda/data/user/DefaultBlacklistRepository.kt`（334 行）
  - `hipda/src/main/java/net/jejer/hipda/di/UserModule.kt`（62 行）
- **测试代码（3 个文件，共 1472 行）**：
  - `hipda/src/test/java/net/jejer/hipda/data/user/UserRepositoryTest.kt`（538 行）
  - `hipda/src/test/java/net/jejer/hipda/data/user/BlacklistStoreTest.kt`（182 行）
  - `hipda/src/test/java/net/jejer/hipda/data/user/BlacklistRepositoryTest.kt`（752 行）

### 1.2 零冲突与只读守卫
- 与同波 `D7`（`data/message/`、`di/MessageModule.kt`、`test/.../data/message/`）零文件重叠。
- 与在途 `B7`、`B8`、`G2` 及禁止目录零文件重叠：`core-parser/`、`ui/`、`data/auth/`、`di/AuthModule.kt`、`data/settings/`（仅只读注入 `SettingsRepository`）、`di/SettingsModule.kt`、`data/network/`、`data/account/`、`data/local/`、`di/DatabaseModule.kt`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、旧 Java 代码、样本文件与所有共享构建/清单文件均零修改。
- 纯离线测试：全部网络请求均走本地 `MockWebServer` 与脱敏真机样本（`user_info/user_profile.html`、`search/search_result.html`）及合成夹具，不访问真实站点（`4d4y.com`）。
- Lint 核查：`hipda/build/reports/lint-results-debug.sarif` 中 `data/user` 与 `UserModule` 零告警；`BackupExclusionRulesTest` 6/6 全绿（确认非加密 `blacklist_cache` 不触发加密备份排除规则告警）。

---

## 二、核心设计与健壮性说明

1. **统一结果模型（`UserModels.kt`）**：
   - 直接组合复用 `B9b` 的 `UserProfile`、`SearchPage` / `SearchItem`，不做逐字段复制。
   - `sealed interface UserError` 同时实现 `UserProfileResult`、`UserThreadsResult`、`BlacklistSyncResult`、`BlacklistMutationResult`，细分 `AuthRequired`、`ChallengeRequired`、`NetworkError`、`SiteError`、`InvalidPage`、`InvalidInput`，下游 `E12`/`E13` 可既做统一错误映射也可逐项穷举匹配。
   - 仅捕获 `IOException` 子类（覆盖 `AuthRequiredException`、`Challenge*Exception`、`ForumHttpException` 及通用网络异常），绝不吞掉 `CancellationException`。
2. **用户资料与“TA 的帖子”（`DefaultUserRepository.kt`）**：
   - 全部请求经由 `D2` `ForumHttpClient.getHtml(..., requireAuth = true)` 发出，自动复用动态 `forumServer`、GBK/GB18030 解码、代际 Cookie 写入门控与至多 1 次单飞静默重登。
   - `getUserProfile(uid)` 校验正整数 `uid` 后请求 `space.php?uid={uid}`，复用 `B9b` `UserParser.parseUserInfo`；若存在 `meta.errorMessage` 返回 `SiteError`，若 `uid`/`username` 关键字段缺失返回 `InvalidPage`。
   - `getUserThreads(uid, page, searchId)` 严格按 `SimpleListJob.java:190-198` 拼装路径（无 `searchId` 走 `search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid={uid}`，带 `searchId` 走 `search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`，仅 `page > 1` 追加 `&page={page}`）；按 `SettingsRepository` 当前 `forumServer` 推导 `avatarBaseUrl`（自动去重 `/forum` 后缀）传给 `SearchParser.parseTitleSearch`。
   - **空态区分（`product-design.md §7.8`）**：当解析结果为空且无错误提示，或 Discuz! 返回“没有找到匹配结果”等无结果提示时返回 `UserThreadsResult.Success`（`items = emptyList()`），不报错；对限频（如 15 秒搜索冷却）或权限等真实站点报错返回 `UserError.SiteError`。
3. **本地非加密黑名单缓存（`BlacklistStore.kt` + `UserModule.kt`）**：
   - `UserModule` 独立提供 `@BlacklistDataStore` 限定符与 `blacklist_cache` Preferences DataStore（`blacklist_cache.preferences_pb`），配置 `ReplaceFileCorruptionHandler { emptyPreferences() }`。
   - 按 `ownerUid` 分桶持久化每个账号的规范化用户名集合与 `lastSyncedAtMs`；读取流捕获 `IOException` 回退 `emptyPreferences()`；对损坏的 protobuf 文件、畸形 JSON 或错误字段类型均安全回退空快照不崩溃。
4. **响应式黑名单仓储与并发隔离（`DefaultBlacklistRepository.kt`）**：
   - 对外暴露 `blockedUsernames: StateFlow<Set<String>>`、`lastSyncedAtMs: StateFlow<Long?>` 及同步只读 `isBlocked(username)`。
   - `init` 监听 `AuthRepository.currentAccount`：退出登录为 `null` 时立即清零内存态，切换账号时先清前账号态再加载新账号分桶，并采用 `.conflate()` + `mutationVersion` 校验从 `BlacklistStore` 现读最新快照，消除滞后旧帧倒流覆盖竞态。
   - `syncBlacklist` / `addToBlacklist` / `removeFromBlacklist` 全部经 `operationMutex` 串行执行，防止并发同步与增删/撤销互相覆盖；每次网络返回后二次核对当前登录账号 `ownerUid` 与响应 `#umenu` 的 `session.uid`，杜绝切号/退登在途回包污染新账号缓存。
   - `syncBlacklist` 仅在 `UserParser.parseBlacklist` 返回 `BlacklistParseResult.Success` 且无 `meta.errorMessage` 时覆写缓存（含 `isEmptyConfirmed == true` 确认清空）；遇 `InvalidPage`、`div.alert_error`、网络或鉴权失败原样保留本地缓存。
   - `addToBlacklist` / `removeFromBlacklist` 在 `formhash` 缺省时自动 GET `pm.php?action=viewblack`（复用 `DiscuzCommonParser.parseFormhash` / `parseLogoutFormhash`），经 `ForumHttpClient.postGbkForm` 以 GBK 表单提交 `formhash` + `user` 至 `pm.php?action=addblack` / `delblack`；遇 `div.alert_error`（由 `DiscuzCommonParser.parseErrorMessage` 剥离尾随 `<a>` 文案）原样保留缓存，成功后立即更新 `BlacklistStore` 并实时发射 `blockedUsernames`。

---

## 三、测试与构建验证证据

### 3.1 本地验证（遵守 DISPATCH §2 `flock` + `-Xmx3g` + `--stop`）
- TDD 红灯阶段：`:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.user.*"` 编译通过、24/24 红灯。
- TDD 绿灯与回归：`:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.user.*" --tests "net.jejer.hipda.data.account.BackupExclusionRulesTest" --rerun-tasks` → **31/31 通过**（D8b 新增 **25/25** + `BackupExclusionRulesTest` 6/6）。
- `:hipda:assembleDebug :hipda:lintDebug` → **BUILD SUCCESSFUL**（D8b 新增文件零 Lint 告警）。
- `./gradlew --stop` → 已停止后台 Gradle Daemon。

### 3.2 GitHub Actions CI 最终证据
- **Run**：[Android CI #45630084159](https://github.com/mailwmj/hipda-next/actions/runs/45630084159)（Commit `040e6e2444d3cc5eb4a51ff6912828c2b91e420e`）
- **汇总**：`TOTAL: 768 tests, 768 passed, 0 failed, 0 errors, 0 skipped`；Build & Lint、Room Schema、Roborazzi 截图验证与 Debug/Release APK 全部通过。
- **D8b 新增测试套件（25/25 全部通过）**：
  - `UserRepositoryTest`（9/9）：
    - `getUserProfile_realSampleParsesAllFieldsAndFormhash`
    - `getUserProfile_rejectsBlankAndInvalidUidWithZeroRequests`
    - `getUserProfile_siteErrorAndMalformedPageReturnExplicitFailures`
    - `getUserThreads_realSampleParsesFirstPageAndDerivesAvatarBaseUrlFromForumServer`
    - `getUserThreads_buildsExpectedPathsForSearchIdAndPagination`
    - `getUserThreads_emptyUserReturnsSuccessEmptyListNotError`
    - `getUserThreads_genuineSiteErrorReturnsSiteError`
    - `getUserThreads_rejectsInvalidParametersWithZeroRequests`
    - `getUserProfileAndThreads_propagateAuthFailureReloginRecoveryHttpAndChallengeErrors`
  - `BlacklistStoreTest`（6/6）：
    - `freshStore_returnsEmptySnapshotForAnyUid`
    - `replaceAddRemoveAndClear_persistAcrossReopenedDataStore`
    - `perAccountBuckets_isolateMultipleUidsWithoutCrossAccountLeak`
    - `corruptedProtoFileOnDisk_recoversToEmptyWithoutCrashing`
    - `corruptedJsonAndWrongPreferenceType_recoverToEmptyWithoutCrashing`
    - `ioExceptionFromDataStore_emitsEmptySnapshotWithoutThrowing`
  - `BlacklistRepositoryTest`（10/10）：
    - `syncBlacklist_populatedAndConfirmedEmptyPagesUpdateStateFlowAndPersistCache`
    - `syncBlacklist_invalidPageSiteErrorHttpErrorAndAuthFailureNeverClearLocalCache`
    - `addRemoveAndUndoBlacklist_encodesGbkChineseUsernameAndEmitsReactiveStateFlowSequence`
    - `addAndRemoveBlacklist_autoPrefetchesFormhashWhenMissingOrBlank`
    - `addAndRemoveBlacklist_alertErrorAndInvalidInputDoNotMutateLocalCache`
    - `concurrentSyncAndMutation_areSerializedWithoutStaleSyncOverwritingAdd`
    - `accountSwitchAndLogout_isolateCacheAndDropStaleCrossAccountInFlightSync`
    - `corruptedBlacklistCacheFile_recoversCleanlyAndAcceptsSubsequentSync`
    - `addToBlacklist_handles302RedirectAndSilentReloginReplayWithGbkEncoding`
    - `userModule_hasExpectedHiltBindingsAndBlacklistDataStorePath`

---

## 四、对外 API 清单（供 `D4` / `D5` / `E12` / `E13` 复用）

### 4.1 领域模型与结果类型（`net.jejer.hipda.data.user`）
- **`UserModels.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/UserModels.kt`）
  - `data class BlacklistSnapshot(val ownerUid: String? = null, val usernames: Set<String> = emptySet(), val lastSyncedAtMs: Long? = null)`（`:18-22`）
  - `sealed interface UserError : UserProfileResult, UserThreadsResult, BlacklistSyncResult, BlacklistMutationResult`（`:30-65`）
    - `data class AuthRequired(val reason: AuthRequiredReason, val reloginResult: SilentReloginResult? = null, val message: String = "") : UserError`（`:36-40`）
    - `data class ChallengeRequired(val cooldownUntilMs: Long? = null, val rejected: Boolean = false, val abandoned: Boolean = false) : UserError`（`:42-46`）
    - `data class NetworkError(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : UserError`（`:48-52`）
    - `data class SiteError(val message: String) : UserError`（`:54-56`）
    - `data class InvalidPage(val message: String) : UserError`（`:58-60`）
    - `data class InvalidInput(val message: String) : UserError`（`:62-64`）
  - `sealed interface UserProfileResult`（`:68-72`）
    - `data class Success(val profile: UserProfile) : UserProfileResult`（`:69-71`）
  - `sealed interface UserThreadsResult`（`:75-86`）
    - `data class Success(val uid: String, val page: Int, val searchPage: SearchPage) : UserThreadsResult`（`:76-85`）
      - `val items: List<SearchItem>`（`:81`）、`val searchId: String?`（`:82`）、`val maxPage: Int`（`:83`）、`val hasNextPage: Boolean`（`:84`）
  - `sealed interface BlacklistSyncResult`（`:89-97`）
    - `data class Success(val snapshot: BlacklistSnapshot, val isEmptyConfirmed: Boolean) : BlacklistSyncResult`（`:90-96`）
      - `val usernames: Set<String>`（`:94`）、`val lastSyncedAtMs: Long?`（`:95`）
  - `sealed interface BlacklistMutationResult`（`:100-105`）
    - `data class Success(val username: String, val blockedUsernames: Set<String>) : BlacklistMutationResult`（`:101-104`）

### 4.2 用户资料与“TA 的帖子”仓储（`net.jejer.hipda.data.user`）
- **`UserRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/UserRepository.kt`）
  - `interface UserRepository`（`:8-34`）
    - `suspend fun getUserProfile(uid: String, priority: AccessPriority = AccessPriority.UserBlocking): UserProfileResult`（`:13-16`）
    - `suspend fun getUserThreads(uid: String, page: Int = 1, searchId: String? = null, priority: AccessPriority = AccessPriority.UserBlocking): UserThreadsResult`（`:28-33`）
- **`DefaultUserRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/DefaultUserRepository.kt`）
  - `@Singleton class DefaultUserRepository @Inject constructor(forumHttpClient: ForumHttpClient, settingsRepository: SettingsRepository) : UserRepository`（`:13-176`）

### 4.3 黑名单仓储与本地缓存（`net.jejer.hipda.data.user`）
- **`BlacklistRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/BlacklistRepository.kt`）
  - `interface BlacklistRepository`（`:11-59`）
    - `val blockedUsernames: StateFlow<Set<String>>`（`:17`）
    - `val lastSyncedAtMs: StateFlow<Long?>`（`:23`）
    - `fun isBlocked(username: String): Boolean`（`:28`）
    - `suspend fun syncBlacklist(priority: AccessPriority = AccessPriority.UserBlocking): BlacklistSyncResult`（`:34-36`）
    - `suspend fun addToBlacklist(username: String, formhash: String? = null, priority: AccessPriority = AccessPriority.UserBlocking): BlacklistMutationResult`（`:43-47`）
    - `suspend fun removeFromBlacklist(username: String, formhash: String? = null, priority: AccessPriority = AccessPriority.UserBlocking): BlacklistMutationResult`（`:54-58`）
- **`DefaultBlacklistRepository.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/DefaultBlacklistRepository.kt`）
  - `@Singleton class DefaultBlacklistRepository : BlacklistRepository`（`:30-334`，Hilt `@Inject constructor` `:40-51`）
- **`BlacklistStore.kt`**（`hipda/src/main/java/net/jejer/hipda/data/user/BlacklistStore.kt`）
  - `@Singleton class BlacklistStore @Inject constructor(@BlacklistDataStore dataStore: DataStore<Preferences>)`（`:39-210`）
    - `fun observe(ownerUid: String): Flow<BlacklistSnapshot>`（`:55-60`）
    - `suspend fun read(ownerUid: String): BlacklistSnapshot`（`:62`）
    - `suspend fun replace(ownerUid: String, usernames: Collection<String>, syncedAtMs: Long): BlacklistSnapshot`（`:64-82`）
    - `suspend fun add(ownerUid: String, username: String): BlacklistSnapshot`（`:84-98`）
    - `suspend fun remove(ownerUid: String, username: String): BlacklistSnapshot`（`:100-114`）
    - `suspend fun clear(ownerUid: String)`（`:116-128`）
    - `suspend fun clearAll()`（`:130-134`）

### 4.4 Hilt 依赖注入绑定（`net.jejer.hipda.di`）
- **`UserModule.kt`**（`hipda/src/main/java/net/jejer/hipda/di/UserModule.kt`）
  - `@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class BlacklistDataStore`（`:27-29`）
  - `@Module @InstallIn(SingletonComponent::class) abstract class UserModule`（`:31-62`）
    - `@Binds @Singleton abstract fun bindUserRepository(impl: DefaultUserRepository): UserRepository`（`:35-37`）
    - `@Binds @Singleton abstract fun bindBlacklistRepository(impl: DefaultBlacklistRepository): BlacklistRepository`（`:39-41`）
    - `const val BLACKLIST_STORE_NAME: String = "blacklist_cache"`（`:44`）
    - `@Provides @Singleton @BlacklistDataStore fun provideBlacklistDataStore(@ApplicationContext context: Context): DataStore<Preferences>`（`:49-60`）
