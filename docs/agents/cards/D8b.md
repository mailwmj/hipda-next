# 任务卡 D8b：用户资料与黑名单 Repository（含本地黑名单 DataStore 缓存）

【角色】你是 hipda-next 的 Android Kotlin 数据层与持久化开发者（Kotlin 协程、`StateFlow`、Hilt、Preferences DataStore、MockWebServer）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/D8b`（分支 `card/D8b-user-blacklist-repo`，基于 `origin/main` @ `0e5fb577d04c86ad505b3a3af7f859f1eaff914e`，需已合入 `D2` 与 `B9b`）里工作。
- 允许新增/修改的范围（仅以下目录，与同波 `D7` 及在途 `B7`、`B8`、`G2` 零文件重叠）：
  - `hipda/src/main/java/net/jejer/hipda/data/user/`（新增 `UserModels.kt`、`UserRepository.kt`、`DefaultUserRepository.kt`、`BlacklistStore.kt`、`BlacklistRepository.kt`、`DefaultBlacklistRepository.kt`）
  - `hipda/src/main/java/net/jejer/hipda/di/UserModule.kt`（独立 Hilt `@Module`，提供 `blacklist_cache` 非加密 Preferences DataStore 及 `UserRepository` / `BlacklistRepository` 绑定）
  - `hipda/src/test/java/net/jejer/hipda/data/user/`（新增 `UserRepositoryTest.kt`、`BlacklistRepositoryTest.kt`，可按需新增 `BlacklistStoreTest.kt`）
- **严禁修改**：
  - **与同波 `D7` 零文件重叠**：严禁修改 `hipda/src/main/java/net/jejer/hipda/data/message/`、`hipda/src/main/java/net/jejer/hipda/di/MessageModule.kt`、`hipda/src/test/java/net/jejer/hipda/data/message/` 及 `notify_read_state` DataStore；
  - **与在途 `B7`、`B8`、`G2` 零冲突**：
    - 零重叠于 **`B7`**（`core-parser` 中 `parser/threadlist`、`simplelist`、`favorite` 及对应测试）；
    - 零重叠于 **`B8`**（`core-parser` 中 `parser/threaddetail` 及对应测试）；
    - 零重叠于 **`G2`**（`ui/designsystem/glass/`、`ui/shell/`、`src/debug/`、`snapshots/`、`gradle/libs.versions.toml`、`hipda/build.gradle.kts`、`hipda/proguard-rules.txt`、`assets/license.txt`）；
    - 严禁修改整个 `core-parser/`、`hipda/src/test/java/net/jejer/hipda/parser/` 与 `ui/` 下任何文件；
  - **既有数据/网络/共享热点与旧代码只读**：严禁修改 `data/auth/`、`di/AuthModule.kt`、`data/settings/`（**仅只读注入 `SettingsRepository`**）、`di/SettingsModule.kt`、`data/network/`、`di/HttpClientModule.kt`、`data/account/`、`data/local/`、`di/DatabaseModule.kt`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、`HttpClientModuleTest.kt`、`DataStoreSettingsRepositoryTest.kt`、旧 Java 代码（`BlacklistHelper.java`、`UserinfoFragment.java`、`SimpleListJob.java`、`HiUtils.java`、`HiParser.java` 等）、样本文件与共享文件（`gradle/libs.versions.toml`、`*build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`、`MotionTokenScanTest`）。
- **测试约束**：严禁访问真实站点（`4d4y.com`），网络测试全部使用 `MockWebServer`；`blacklist_cache` 仅保存非敏感本地缓存（黑名单用户名集合与同步时间戳），不涉及加密凭据与备份排除规则。

【背景】
- 必读规格与计划：
  - `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`（第一节 1.4 三栏契约、第二节卡 6 `D8b`、第三节零重叠表）；
  - `docs/design/backlog.md` D8b；`docs/design/product-design.md` §7.1（黑名单移除进度与 Snackbar 撤销）、§7.8（用户资料 `资料 | TA 的帖子` 双标签；**TA 没发过帖时返回空态而不报错**；操作含加/移黑名单）、§7.10（设置 → 论坛与发帖 → 黑名单）；
  - `docs/agents/lesson.md`（平台替身按真实语义建模、非加密 DataStore 用 `ReplaceFileCorruptionHandler` 损坏回退）。
- **复用 `D2` 认证与网络层 API（引用清单章节，不抄死签名）**：
  - 查阅 `/home/mir/Documents/hipda-next.wt/_reports/D2.md` 第四节“对外 API 清单”（4.2 认证与网络模型、4.3 `AuthRepository`、4.4 `ForumHttpClient`）并以基线 `0e5fb577d04c86ad505b3a3af7f859f1eaff914e` 中实际合入的 `hipda/src/main/java/net/jejer/hipda/data/auth/` 源码为准直接复用（`D2` 经复审/修复轮可能微调精确签名，一律以基线源码为准）；
  - 全部 HTTP GET / GBK POST 请求必须经由 `ForumHttpClient` 发出（自动动态读取 `forumServer`、GBK 编解码、单飞静默重登最多 1 次），不得另建网络客户端或重复实现 `SiteAccessGateway`/`GbkForm`/静默重登。
- **复用 `B9a` / `B9b` 解析器 API（不重复实现 HTML 解析）**：
  - 查阅 `/home/mir/Documents/hipda-next.wt/_reports/B9a.md` 第六节（6.1 `DiscuzCommonParser`、`DiscuzPageMeta`、`DiscuzUrlHelper`）与 `/home/mir/Documents/hipda-next.wt/_reports/B9b.md` 第六节（6.1 `SearchParser` / `SearchPage`、6.3 `UserParser` / `UserProfile` / `BlacklistParseResult`），直接复用解析器与模型。
- 现有参考实现（只读）：
  - DataStore 建法参照：`hipda/src/main/java/net/jejer/hipda/di/SettingsModule.kt` 与 `data/settings/DataStoreSettingsRepository.kt`（`PreferenceDataStoreFactory.create` + `ReplaceFileCorruptionHandler { emptyPreferences() }` + `IOException` 兜底）；
  - 旧端点与表单规则参照（已核对行号）：
    - `hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java:150,155,157-159,161`：
      - 用户资料：`space.php?uid={uid}`
      - TA 的帖子（首页/无 `searchId`）：`search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid={uid}`（`page > 1` 附 `&page={page}`）
      - TA 的帖子（带 `searchId` 翻页）：`search.php?searchid={searchid}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`（`page > 1` 附 `&page={page}`）
      - 拉取黑名单：`pm.php?action=viewblack`
      - 添加黑名单：`POST pm.php?action=addblack`（GBK 表单字段：`formhash`、`user`）
      - 移除黑名单：`POST pm.php?action=delblack`（GBK 表单字段：`formhash`、`user`）
    - `hipda/src/main/java/net/jejer/hipda/async/BlacklistHelper.java:29-142`（POST `formhash` + `user`，失败检查 `div.alert_error` 与 `HiParser.parseErrorMessage`，成功更新本地黑名单集合与同步时间）；
    - `hipda/src/main/java/net/jejer/hipda/job/SimpleListJob.java:190-198` 与 `ui/UserinfoFragment.java:203-294,370-399`。
- 真机脱敏样本（只读）：
  - `hipda/src/test/resources/samples/user_info/user_profile.html`
  - `hipda/src/test/resources/samples/search/search_result.html`
  - 合成夹具（在测试代码内构造）：含多用户的 `div.blacklist` 页、确认空态 `div.blacklist`（含“暂无数据”）、残缺/异常黑名单页（验证 `InvalidPage` 不清空缓存）、添加/移除黑名单成功与 `div.alert_error` 失败回包、空结果用户帖子页。

【目标】实现 `UserRepository`（用户资料与“TA 的帖子”分页加载）与 `BlacklistRepository`（黑名单服务端拉取/同步、添加、移除与本地非加密 DataStore 持久化缓存，对外暴露响应式 `blockedUsernames: StateFlow<Set<String>>` 作为 `D4`/`D5`/`E12`/`E13` 唯一事实源），通过全部离线 MockWebServer 与 DataStore 测试。

【范围】
1. **领域模型与结果封装（`data/user/UserModels.kt`）**：
   - 定义清晰、不可变的领域结果与快照类型（如用户资料/“TA 的帖子”分页结果、黑名单同步/增删操作结果、缓存快照含 `usernames: Set<String>` 与 `lastSyncedAtMs: Long?`），直接组合复用 `B9b` 的 `UserProfile`、`SearchPage`/`SearchItem` 而非无意义逐字段复制。
2. **用户资料与“TA 的帖子”仓储（`data/user/{UserRepository,DefaultUserRepository}.kt`）**：
   - `getUserProfile(uid: String)`: 校验非空 `uid`，经 `ForumHttpClient`（`requireAuth = true`）请求 `space.php?uid={uid}`，复用 `B9b` `UserParser.parseUserInfo` 解析；若 `meta.errorMessage` 非空或资料关键字段缺失返回明确失败结果，否则返回 `UserProfile`（含 `formhash` 供 `E13` 发起加/移黑名单）。
   - `getUserThreads(uid: String, page: Int = 1, searchId: String? = null)`: 按 `SimpleListJob.java:190-198` 规则拼装路径（`searchId` 为空走 `search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid={uid}`，非空走 `search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`，`page > 1` 追加 `&page={page}`），经 `ForumHttpClient`（`requireAuth = true`）请求并复用 `B9b` `SearchParser.parseTitleSearch`（按 `SettingsRepository` 当前 `forumServer` 推导 `avatarBaseUrl`）；
   - **空态区分（`product-design.md §7.8`）**：当用户未发过帖（解析出 0 条且非网络/鉴权错误，或站点返回“没有找到匹配结果”类无结果提示）时返回成功的空页（`items = emptyList()`），**不报错**；仅在真正的鉴权/网络/站点错误时返回失败。
3. **本地黑名单缓存存储（`data/user/BlacklistStore.kt`）**：
   - 基于独立非加密 Preferences DataStore（文件名 `blacklist_cache`，即 `blacklist_cache.preferences_pb`，通过 `UserModule` 提供）持久化黑名单用户名集合、`lastSyncedAtMs` 以及所属账号 `ownerUid`；
   - 配置 `ReplaceFileCorruptionHandler { emptyPreferences() }` 并在读流中对 `IOException` 回退空集（损坏文件不崩溃）；
   - 支持按账号隔离或切换账号重置（当 `ownerUid` 与当前登录账号不同时自动清空旧账号缓存或按 `uid` 分桶，防止多账号切换时账号 A 的黑名单误屏蔽账号 B 的帖子）。
4. **黑名单仓储（`data/user/{BlacklistRepository,DefaultBlacklistRepository}.kt`）**：
   - 响应式状态（面向 `D4`/`D5` 即时重过滤、`E12`/`E13`）：
     - `blockedUsernames: StateFlow<Set<String>>`（主事实源，去空白、规范化；当 `AuthRepository.currentAccount` 退出登录为 `null` 或切换账号时同步隔离/刷新，不泄漏前账号状态）
     - `lastSyncedAtMs: StateFlow<Long?>`
     - 便捷只读查询 `isBlocked(username: String): Boolean`（与 `blockedUsernames` 一致）
   - 核心方法（写/同步操作以 `Mutex` 串行，防并发同步与增删/撤销互相覆盖）：
     - `syncBlacklist(): ...`：经 `ForumHttpClient`（`requireAuth = true`）GET `pm.php?action=viewblack`，复用 `B9b` `UserParser.parseBlacklist`；
       - 仅当返回 `BlacklistParseResult.Success` 且无 `meta.errorMessage` 时覆盖写入 `BlacklistStore` 并更新 `lastSyncedAtMs`（含 `isEmptyConfirmed == true` 确认清空场景）；
       - 若返回 `BlacklistParseResult.InvalidPage`、页面含错误提示或网络/鉴权失败，**绝不清空既有本地缓存**，原样保留并返回失败结果。
     - `addToBlacklist(username: String, formhash: String? = null): ...`：校验非空 `username`；若 `formhash` 未传或为空则先经 `ForumHttpClient` GET `pm.php?action=viewblack` 提取 `formhash`（复用 `DiscuzCommonParser.parseFormhash`）；再用 `ForumHttpClient.postGbkForm` 提交 `pm.php?action=addblack`（GBK 表单字段 `formhash`、`user`）；检查响应中的 `div.alert_error` / `DiscuzCommonParser.parseErrorMessage`（清理其中尾随 `<a>` 链接文本），失败时**不改本地缓存**，成功时立即写入 `BlacklistStore` 使 `blockedUsernames` 实时发射（支持 `E12`/`E13` 撤销移除时重新加入）。
     - `removeFromBlacklist(username: String, formhash: String? = null): ...`：同上获取/校验 `formhash` 并 GBK POST `pm.php?action=delblack`（`formhash`、`user`）；失败时保留缓存，成功时立即从 `BlacklistStore` 移除使 `blockedUsernames` 实时发射。
5. **独立 Hilt 绑定（`di/UserModule.kt`）**：
   - 独立 `@Module @InstallIn(SingletonComponent::class)`，定义 `@BlacklistDataStore` 限定符，创建 `blacklist_cache` Preferences DataStore（含 `ReplaceFileCorruptionHandler`），绑定 `@Singleton UserRepository` 与 `@Singleton BlacklistRepository`；不触碰任何既有 DI Module。

【步骤】
1. 查阅基线 `0e5fb577d04c86ad505b3a3af7f859f1eaff914e` 中已合入的 `data/auth/` 源码、`_reports/D2.md` 第四节“对外 API 清单”，以及 `_reports/B9a.md`、`_reports/B9b.md` 第六节“对外 API 清单”，确认全部可复用签名。
2. 先写失败单测（TDD）：
   - `UserRepositoryTest`：覆盖真机样本 `user_profile.html` 解析、非法/错误资料页、真机样本 `search_result.html` 加载“TA 的帖子”首页与带 `searchId` 翻页路径断言、用户未发帖返回空列表不报错、鉴权失败传播；
   - `BlacklistRepositoryTest`：覆盖 `viewblack` 同步成功与确认空态（`isEmptyConfirmed = true`）、`InvalidPage` / 网络异常保留本地缓存不清空、添加与移除（含中文用户名 GBK 表单编码断言、缺省 `formhash` 自动预取、服务端 `div.alert_error` 拒绝时不脏改缓存）、添加→移除→撤销重新添加的 `blockedUsernames: StateFlow<Set<String>>` 响应式序列、并发操作序列化、多账号切换/退出登录缓存隔离、DataStore 损坏文件回退不崩溃及 `UserModule` Hilt 绑定检查。
3. 实现 `UserModels`、`UserRepository`、`DefaultUserRepository`、`BlacklistStore`、`BlacklistRepository`、`DefaultBlacklistRepository` 与 `UserModule`。
4. 本地按 DISPATCH §2 串行跑 `:hipda:testDebugUnitTest --tests "net.jejer.hipda.data.user.*"`、`:hipda:assembleDebug`、`:hipda:lintDebug`，确认零越界文件改动，提交推送 `card/D8b-user-blacklist-repo` 并用 `gh run watch <id> --interval 60` 等 CI 全绿。

【自检】
- 先导出 mise 的 `ANDROID_HOME` / `JAVA_HOME`（见 `docs/agents/lesson.md`），按 DISPATCH §2 串行执行：
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.data.user.*" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] **边界与零冲突守卫**：仅新增 `data/user/*`、`di/UserModule.kt` 与 `test/.../data/user/*`；与同波 `D7`（`data/message`、`MessageModule`）及在途 `B7`（`core-parser` `parser/threadlist`/`simplelist`/`favorite`）、`B8`（`parser/threaddetail`）、`G2`（`ui/designsystem/glass`、shell 基线、`libs.versions.toml`）零文件重叠；未改 `data/settings/`、`data/auth/`、`data/network/`、`data/local/`、`backup_config.xml`、`data_extraction_rules.xml`、`BackupExclusionRulesTest.kt`、旧 Java 代码、样本与共享文件；全部测试纯离线 MockWebServer。证据：`git diff --stat origin/main...HEAD`。
- [ ] **用户资料与“TA 的帖子”加载**：复用 `D2` `ForumHttpClient` 与 `B9b` `UserParser`/`SearchParser`，正确拉取 `space.php?uid=` 与 `search.php`（首屏 `srchuid` 与翻页 `searchid` + `page`），真机样本 `user_profile.html`/`search_result.html` 字段准确，且无帖子结果返回空态而非错误。证据：`UserRepositoryTest` 用例列表与 CI 汇总。
- [ ] **黑名单同步、增删撤销、防误清与缓存健壮性**：`blockedUsernames: StateFlow<Set<String>>` 实时反映同步/添加/移除/撤销；GBK POST 编码中文用户名正确；`formhash` 缺失可自动预取；`InvalidPage` 或 `div.alert_error` 或网络异常绝不清空/脏改本地缓存；切换账号或退出登录不串号泄露；`blacklist_cache` DataStore 损坏文件自动回退不崩溃。证据：`BlacklistRepositoryTest` 用例列表与 CI 汇总。
- [ ] **对外 API 清单与 CI 全绿**：`_reports/D8b.md` 写出 `UserRepository`、`BlacklistRepository`、`blockedUsernames` 及全部结果模型对外 API 清单（签名 + 文件:行号，供 `D4`/`D5`/`E12`/`E13` 复用）；新增文件零 Lint 告警且 CI 全绿。证据：`_reports/D8b.md` + CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/D8b-user-blacklist-repo`；Conventional Commits（如 `feat(data-user): 实现用户资料与黑名单 Repository 及本地缓存`）。
- 详细证据、测试日志与对外 API 清单（签名 + 文件:行号）写入 `/home/mir/Documents/hipda-next.wt/_reports/D8b.md`；最后一条回复按文末通用约定控制在 ≤600 字。

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

首先 `cd /home/mir/Documents/hipda-next.wt/D8b`，确认 `git status` 干净、HEAD 为 0e5fb577d04c86ad505b3a3af7f859f1eaff914e；首次推送用 `git push -u origin card/D8b-user-blacklist-repo`。
