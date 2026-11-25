# 审查报告 R-D8b：用户资料与黑名单 Repository（`040e6e2444d3cc5eb4a51ff6912828c2b91e420e`）

- **审查结论**：**APPROVE**（0 阻断项，3 项非阻断改进建议）
- **审查对象**：`origin/card/D8b-user-blacklist-repo` @ `040e6e2444d3cc5eb4a51ff6912828c2b91e420e`
- **对比基线（`{BASE}`）**：`0e5fb577d04c86ad505b3a3af7f859f1eaff914e`（`git merge-base origin/main 040e6e2444d3cc5eb4a51ff6912828c2b91e420e`；亦核对当前 `origin/main` @ `5ce63dcc45fb9447d2795dcf548be0c6446004cd` 零冲突）
- **CI 验证**：[Android CI #45630084159](https://github.com/mailwmj/hipda-next/actions/runs/45630084159)
  - `headBranch`: `card/D8b-user-blacklist-repo`
  - `headSha`: `040e6e2444d3cc5eb4a51ff6912828c2b91e420e`（与审查提交完全一致）
  - 状态与结论：`completed / success`（Job `139260602687` Build, Lint & Unit Tests）
  - 已核实日志执行任务：`:hipda:assembleDebug`、`:hipda:lintDebug`、`:hipda:assembleRelease`、`:hipda:testDebugUnitTest`（670/670 passed；全仓汇总 `TOTAL: 768 tests, 768 passed, 0 failed, 0 errors, 0 skipped`；D8b 新增 `UserRepositoryTest` 9/9、`BlacklistStoreTest` 6/6、`BlacklistRepositoryTest` 10/10 共 25/25 全绿）

---

## 一、阻断项（Blockers）

**无**（对照 `docs/agents/DISPATCH.md` §4 与 `R-D8b.md` 阻断标准逐项核实：无异常页/网络失败误清本地黑名单，无 GBK 表单或 `formhash` 错误导致增删失效，无跨账号黑名单串号泄露，无真实站点网络请求，无越界文件修改）。

---

## 二、非阻断建议项（Suggestions）

### S1. 修复极端切号窗口下全局 `mutationVersion++` 误丢新账号内存态初载（`DefaultBlacklistRepository.kt:82-92, 138-143, 227-232`）
- **现象与触发路径**：
  1. 账号 A（`10001`）的 `syncBlacklist()` 或 `addToBlacklist()`/`removeFromBlacklist()` 在网络返回时尚处于账号 A，通过 `verifySessionOwnership`（`:119` / `:212`），随后进入 `blacklistStore.replace/add/remove("10001", ...)`（`:133` / `:223`）挂起写盘。
  2. 若恰在该亚毫秒落盘窗口内切到账号 B（`10002`，磁盘已有缓存 `["SpammerB"]`），`init` 中 `collectLatest` 先将 `mutationVersion` 递增为 `V1`（`:78`），读取 `versionBeforeRead = V1`（`:85`）并挂起执行 `blacklistStore.read("10002")`（`:86`）。
  3. 账号 A 写盘结束进入 `:138-143`（或 `:227-232`）：尽管 `currentActiveUid() == targetUid` 为 `false`（不会把 A 的黑名单串入 B），但外层的 `mutationVersion++`（`:139` / `:228`）仍将全局计数递增为 `V2`。
  4. 账号 B 的 `blacklistStore.read("10002")` 返回后，在 `:88` 因 `mutationVersion (V2) != versionBeforeRead (V1)` 丢弃快照；又因 `blacklistStore.observe("10002")` 带 `.distinctUntilChanged()`（`BlacklistStore.kt:59`）且 B 桶未再变化而不再重发，导致账号 B 的内存态 `blockedUsernames` 停留在空集直至下一次同步或增删（磁盘缓存未丢失，亦无跨账号数据泄漏）。
- **建议改法**：将 `:139` 与 `:228` 的 `mutationVersion++` 移入 `if (currentActiveUid() == targetUid)` 分支内；同时 `blacklistStore.observe(uid)` 本身已由单次 `Preferences` 原子解码出 `BlacklistSnapshot`，`init` 中可直接消费流元素或按每账号维度维护版本号，省去二次 `blacklistStore.read(uid)`。

### S2. 透出或缓存 `viewblack` 的 `formhash`，消除 `E12` 移除/撤销的额外预取往返并去掉重复 Jsoup 解析（`UserModels.kt:90-96`、`DefaultBlacklistRepository.kt:132-148, 267-269`）
- **现象**：
  - 旧版 `BlacklistFragment.java:117,144` 在拉取黑名单页时即保存 `mFormhash` 供后续移除操作直接提交。当前 `syncBlacklist()` 请求 `pm.php?action=viewblack` 时，`ForumHttpClient` 已在 `response.meta.formhash` 解析出 `formhash`，但 `BlacklistSyncResult.Success` 未透出该字段、`DefaultBlacklistRepository` 亦未按 `ownerUid` 缓存最近 `formhash`；导致下游 `E12`（设置 → 黑名单页，`product-design.md` §7.1）在未显式传 `formhash` 时，每次点“移除”及点 Snackbar“撤销”都会额外多发一次 `GET pm.php?action=viewblack`。
  - 此外 `fetchFormhash`（`DefaultBlacklistRepository.kt:267-269`）对 `response.html` 二次 `Jsoup.parse` 提取 `parseFormhash ?: parseLogoutFormhash`，与 `ForumHttpClient.kt:147` 已计算的 `response.meta.formhash`（`DiscuzCommonParser.kt:16`）完全等价，可直接复用 `response.meta.formhash`。
- **建议改法**：在 `BlacklistSyncResult.Success` 增加 `val formhash: String? = null`（或在仓储内按 `ownerUid` 记录最近一次有效 `formhash` 供缺省时优先使用），并在 `fetchFormhash` 中直接读取 `response.meta.formhash`。

### S3. 补齐 4 处单行变异测试盲区（`UserModule.kt:52-60`、`BlacklistRepositoryTest.kt`、`UserRepositoryTest.kt`）
- **盲区清单**（详见第四节 4.3）：
  1. `BlacklistRepositoryTest` 与 `BlacklistStoreTest` 均通过测试私有助手 `createDataStore` 自建 `ReplaceFileCorruptionHandler`，未直接调用 `UserModule.provideBlacklistDataStore(context)` 验证损坏文件恢复（若误删 `UserModule.kt:55-57` 的 `corruptionHandler`，现有 25 条单测仍全部通过）；
  2. `DefaultBlacklistRepository.verifySessionOwnership`（`:296-304`）中“`accountUidBefore == activeUidAfter` 但服务端响应 `#umenu` 的 `session.uid` 为另一账号”的防串号分支无专属用例（现有切号用例已在 `:288` 提前返回）；
  3. `BlacklistRepository` 三个挂起方法的 `priority: AccessPriority` 参数透传无非默认值（如 `AccessPriority.BackgroundSilent`）断言；
  4. 无 `<input name="formhash">` 时回退 `#umenu` 退出链接 `logoutFormhash` 的兜底分支（`DefaultBlacklistRepository.kt:269`、`DefaultUserRepository.kt:46-50`）未被单独命中。

---

## 三、四项审查重点逐项核查

### 3.1 边界与五线零冲突守卫（通过）
- `git diff --name-status 0e5fb577d04c86ad505b3a3af7f859f1eaff914e...040e6e2444d3cc5eb4a51ff6912828c2b91e420e` 共 **10 个纯新增文件**（`+2493 / -0`，7 个生产文件 1021 行 + 3 个测试文件 1472 行），0 修改文件：
  - `hipda/src/main/java/net/jejer/hipda/data/user/{UserModels,UserRepository,DefaultUserRepository,BlacklistStore,BlacklistRepository,DefaultBlacklistRepository}.kt`
  - `hipda/src/main/java/net/jejer/hipda/di/UserModule.kt`
  - `hipda/src/test/java/net/jejer/hipda/data/user/{UserRepositoryTest,BlacklistStoreTest,BlacklistRepositoryTest}.kt`
- **跨卡零重叠核实**：
  - 与同波 `origin/card/D7-sms-notify-repo`（`data/message/*`、`di/MessageModule.kt`、`test/.../data/message/*`）交集为 **0 个文件**；
  - 与 `B7`、`B8`（`core-parser/` 及 `parser/` 测试）、已合入 `origin/main` 的 `G2`（`ui/designsystem/glass`、`ui/shell`、`libs.versions.toml`、`hipda/build.gradle.kts`、`proguard-rules.txt`、`assets/license.txt`）交集为 **0 个文件**；
  - `data/settings/`（仅只读消费 `SettingsRepository`）、`data/auth/`、`data/network/`、`data/local/`、`data/account/`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、`HttpClientModuleTest.kt`、旧 Java 代码、脱敏样本与共享配置均零改动。
- **离线测试与备份规则核实**：
  - 3 个测试文件全部通过本地 `MockWebServer` 驱动网络交互，不触发旧 `HiParser` 后台通知线程，零真实站点访问；
  - `blacklist_cache` 为非敏感非加密 Preferences DataStore（仅存被屏蔽用户名与同步毫秒时间戳），符合 `BackupExclusionRulesTest` 扫描约束（CI 全绿通过）。

### 3.2 复用 `D2` 与 `B9a`/`B9b` API、路径拼装与无帖空态（通过）
- **网络层完全复用 `D2` `ForumHttpClient`**：
  - `DefaultUserRepository`（`:27-31, :80-84`）与 `DefaultBlacklistRepository`（`:110-114, :199-207, :246-250`）全部经 `ForumHttpClient.getHtml(..., requireAuth = true)` / `postGbkForm(..., requireAuth = true)` 发起请求，完整继承动态 `forumServer`、GBK/GB18030 编解码、`SiteAccessGateway` 验证协调、`AuthCookieWriteGuard` 代际 Cookie 门控与单飞静默重登最多 1 次重试。
  - 异常捕获限定为 `catch (e: IOException)`（`DefaultUserRepository.kt:32,85`、`DefaultBlacklistRepository.kt:115,208,251`），绝不吞掉协程 `CancellationException`。
- **解析层完全复用 `B9a`/`B9b`**：
  - 资料解析复用 `UserParser.parseUserInfo`（`DefaultUserRepository.kt:36`）；
  - “TA 的帖子”复用 `SearchParser.parseTitleSearch`（`DefaultUserRepository.kt:90-93`），按当前 `forumServer` 去重 `/forum` 后缀推导 `avatarBaseUrl`（`:162-171`，与同波 `D7` 约定一致）；
  - 黑名单解析复用 `UserParser.parseBlacklist`（`DefaultBlacklistRepository.kt:129`），`formhash` 与 `div.alert_error` 解析复用 `DiscuzCommonParser`（`:268-269` 及 `response.meta`）。
- **端点路径与空态核对**：
  - `space.php?uid={uid}`：严格校验正整数 `uid`（`:137-142`），拦截空串、`0`、负数及参数注入；
  - `search.php`（对照 `SimpleListJob.java:190-198`、`HiUtils.java:150,161`）：无 `searchId` 拼装 `search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid={uid}`，有 `searchId`（经 `validateSearchId` 校验 `[0-9a-zA-Z_-]+` 防注入）拼装 `search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`，仅在 `page > 1` 时追加 `&page={page}`（`:153-160`），逐字符对齐旧版；
  - **无帖空态（`product-design.md` §7.8）**：当 `parsedPage.items` 为空且无 `errorMessage`，或 `div.alert_error` 为“没有找到匹配结果”等空搜索提示（`:126-135, :173-174`）时返回 `UserThreadsResult.Success(items = emptyList(), hasNextPage = false)`；而对“15 秒内只能进行一次搜索”等真实站点错误准确返回 `UserError.SiteError`（`:103-109`）。

### 3.3 黑名单防误清、GBK 提交、并发串行与多账号隔离（通过）
- **防误清保护（`DefaultBlacklistRepository.kt:106-150`）**：
  - 网络/HTTP/鉴权/验证异常（`:115-117`）→ 直接映射 `UserError`，不触碰 `BlacklistStore`；
  - 切号/退登/`#umenu` 会话不匹配（`:119-122`）→ 返回 `UserError.AuthRequired`，不写库；
  - 页面含 `div.alert_error`（`:124-127`，即便剥离 `<a>` 后为空串仍因 `siteError != null` 拦截）→ 返回 `UserError.SiteError`，不写库；
  - `BlacklistParseResult.InvalidPage`（缺 `div.blacklist` 或无条目且无“暂无数据”，`:130`）→ 返回 `UserError.InvalidPage`，原样保留本地缓存与 `lastSyncedAtMs`；
  - **仅在** `BlacklistParseResult.Success`（含 `isEmptyConfirmed == true` 确认空态“暂无数据”）且无错误提示时调用 `blacklistStore.replace` 更新缓存与同步时间。
- **GBK 表单编码与 `formhash` 预取（`DefaultBlacklistRepository.kt:176-275`）**：
  - `addToBlacklist`（`pm.php?action=addblack`）与 `removeFromBlacklist`（`pm.php?action=delblack`）均通过 `ForumHttpClient.postGbkForm` 提交 `formhash` 与 `user`（底层 `gbkFormBody` 以 `application/x-www-form-urlencoded; charset=GBK` 编码中文字符如 `"测试用户"` → `%B2%E2%CA%D4%D3%C3%BB%A7`），并支持 302 重定向与会话过期静默重登后重放 GBK 表单；
  - `formhash` 为 `null` 或空白时自动 `GET pm.php?action=viewblack` 提取令牌，缺失时返回 `UserError.InvalidPage` 且不发 POST；
  - 提交响应含 `div.alert_error`（由 `DiscuzCommonParser.parseErrorMessage` 克隆节点移除 `<a>` 标签文本，零 DOM 副作用）时返回 `UserError.SiteError`，绝不脏改本地缓存。
- **并发串行与多账号隔离**：
  - `syncBlacklist`、`addToBlacklist`、`removeFromBlacklist` 全程受 `operationMutex` 保护（`:107, :186`），防止旧 `sync` 覆盖新 `add` 或快速“移除 → 撤销”乱序；
  - `BlacklistStore` 按 `ownerUid` JSON 分桶存储，`DefaultBlacklistRepository` 监听 `authRepository.currentAccount`：退出登录立即清零内存流（`blockedUsernames = emptySet()`, `lastSyncedAtMs = null`），切换账号立即切换分桶，`isBlocked()`（`:98-104`）同步二次核对 `snapshot.ownerUid == currentActiveUid()`，即使在协程调度间隙也零泄漏前账号黑名单。

### 3.4 DataStore 健壮性、下游契约与测试盲区分析（通过，附改进建议）

#### 4.1 DataStore 健壮性
- `UserModule.provideBlacklistDataStore`（`UserModule.kt:49-60`）配置了 `ReplaceFileCorruptionHandler { emptyPreferences() }`，文件名为 `blacklist_cache.preferences_pb`；
- `BlacklistStore`（`BlacklistStore.kt:44-53, 163-204`）在 `dataStore.data` 上对 `IOException` 回退 `emptyPreferences()`，并对畸形 JSON、错误 Preference 类型、单桶字段损坏做了逐级容错（单桶损坏不影响其他合法账号桶）。

#### 4.2 下游契约评估（`D4` / `D5` / `E12` / `E13`）与 API 清单核实
- 已逐行核对 `_reports/D8b.md` 第四节“对外 API 清单”中全部 7 个生产文件的类名、方法签名与行号区间，**100% 与提交 `040e6e2444d3cc5eb4a51ff6912828c2b91e420e` 实际源码精确吻合**。
- 下游适配度评估：
  - **`D4`（帖子列表过滤与自动补页）/ `D5`（帖子详情楼层屏蔽）**：可直接 `combine` 消费 `BlacklistRepository.blockedUsernames: StateFlow<Set<String>>`（元素已 `.trim()` 去空白、大小写敏感，与旧版 `HiSettingsHelper.isInBlacklist` 一致）或同步调 `isBlocked(author)`，黑名单增删/撤销/切号实时触发重过滤；
  - **`E12`（设置 → 黑名单页）**：`blockedUsernames`、`lastSyncedAtMs`、`syncBlacklist()`、`removeFromBlacklist()`、`addToBlacklist()`（用于 Snackbar 撤销）完备，错误统一实现 `UserError` 密封接口便于 UI 分支提示（采纳 S2 后可进一步省去预取 `formhash` 的额外 GET）；
  - **`E13`（用户资料 `资料 | TA 的帖子` 双标签）**：`getUserProfile(uid)` 返回 `UserProfile`（含 `formhash` 可直接传给 `addToBlacklist`/`removeFromBlacklist` 实现单次 POST 加/移黑名单），`getUserThreads(uid, page, searchId)` 提供 `items`/`searchId`/`maxPage`/`hasNextPage` 且对未发帖用户返回空列表 `Success`，直接匹配 `product-design.md` §7.8。

#### 4.3 关键单行变异核查表
| 变异场景 | 若改坏关键一行 | 现有测试是否拦截 | 核查结果 |
|---|---|---|---|
| `InvalidPage` 误清空缓存 | `DefaultBlacklistRepository.kt:130` 调 `replace(..., emptyList())` | **是**（`BlacklistRepositoryTest:231-249` 变红） | 已守住 |
| 中文用户名改用 UTF-8 编码 | `postGbkForm` 改 UTF-8 编码 `"测试用户"` | **是**（`BlacklistRepositoryTest:320-325` 断言 `%B2%E2%CA%D4%D3%C3%BB%A7` 变红） | 已守住 |
| 切换账号/退登未隔离缓存 | 去掉 `ownerUid` 分桶或 `currentAccount` 监听 | **是**（`BlacklistStoreTest:109-122`、`BlacklistRepositoryTest:510-581` 变红） | 已守住 |
| `div.alert_error` 仍改本地缓存 | 去掉 `DefaultBlacklistRepository.kt:217-220` 错误拦截 | **是**（`BlacklistRepositoryTest:429-448` 变红） | 已守住 |
| 删掉生产 `UserModule` 损坏处理器 | `UserModule.kt:55-57` 移除 `ReplaceFileCorruptionHandler` | **否**（测试用私有 `createDataStore`，未调 `UserModule.provideBlacklistDataStore`） | **盲区（见 S3-1）** |
| 删掉响应 `#umenu` `session.uid` 一致性校验 | 删除 `DefaultBlacklistRepository.kt:296-304` | **否**（现有切号测试在 `:288` 已提前返回） | **盲区（见 S3-2）** |
| 硬编码忽略 `BlacklistRepository` 的 `priority` | `DefaultBlacklistRepository.kt:112,190,205,248` 写死 `UserBlocking` | **否**（`BlacklistRepositoryTest` 未传非默认 `AccessPriority`） | **盲区（见 S3-3）** |
| 删掉 `#umenu` `logoutFormhash` 兜底 | 删除 `DefaultBlacklistRepository.kt:269` 或 `DefaultUserRepository.kt:46-50` | **否**（夹具均同时含 `<input name=formhash>` 或真样本已有 `formhash`） | **盲区（见 S3-4）** |
