# 独立代码审查报告 R-D9：提醒轮询（`WorkManager` + `HiltWorker` + 免打扰 + R-D7 S1–S4 收口）

- **审查结论**：**APPROVE**（0 项阻断，3 项非阻断建议）
- **审查对象**：`origin/card/D9-notification-polling` @ `933a5ac80eb43193bcdf2e1160b8c778177c615b`
- **比较基线**：`2a3f7ca14c74c51f47bd335cfcecc2b165785df9`（同时核对当前 `origin/main` @ `6732dbd` 及在途 `origin/card/D8a-search-favorite-repo` 零文件冲突）
- **CI 核验**：
  1. **Push CI**：[Android CI #45649677136](https://github.com/mailwmj/hipda-next/actions/runs/45649677136)
     - `headSha`：`933a5ac80eb43193bcdf2e1160b8c778177c615b`（精确匹配）
     - 状态与结论：`completed / success`（Job `139330439369` Build, Lint & Unit Tests）
     - 已核实执行并通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room Schema 校验、`:core-parser:test`、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）、APK 产物校验；全量 **873/873** 单元测试通过。
  2. **API 36 Emulator Smoke CI（`emulator=true`）**：[Android CI #45651379584](https://github.com/mailwmj/hipda-next/actions/runs/45651379584)
     - `headSha`：`933a5ac80eb43193bcdf2e1160b8c778177c615b`（精确匹配）
     - 状态与结论：`completed / success`
     - `Build, Lint & Unit Tests`（Job `139336499441`）：`success`
     - `Emulator Smoke Test (API 36)`（Job `139336927205`）：`success`（Debug `PID=2156`、R8 混淆 Release `PID=3165` 冷启动 `MainFrameActivity` 均零 `FATAL EXCEPTION`、零 `ANR`）。

---

## 一、阻断项（Blockers）

**无**（对照 `docs/agents/DISPATCH.md` §4 与审查卡 `R-D9.md` 全部阻断标准逐项核验通过）。

---

## 二、专项深查：共享文件自定义 WorkManager 初始化与旧 `NotiWorker` 共存（防崩溃 + 防双重通知）

### 1. 自定义 WorkManager 初始化链路与防重复初始化崩溃核验（通过）
- **`AndroidManifest.xml:79-88`**：
  - 仅新增 `<provider android:name="androidx.startup.InitializationProvider" android:authorities="${applicationId}.androidx-startup" android:exported="false" tools:node="merge">`，并在其下精确声明 `<meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />`。
  - `tools:node="merge"` 保留了 `EmojiCompatInitializer`、`ProcessLifecycleInitializer` 等其他 AndroidX Startup 组件，仅剔除默认 `WorkManagerInitializer`（已由 `NotificationSchedulerTest.kt:77-86` 通过 `PackageManager.GET_META_DATA` 实测断言）。
- **`HiNextApplication.kt:8-18` 注入时序与零重复初始化**：
  - 全仓检索确认生产代码 **0 处调用 `WorkManager.initialize(...)`**，彻底排除 `IllegalStateException: WorkManager is already initialized` 风险。
  - `HiNextApplication` 标注 `@HiltAndroidApp` 并继承 `HiApplication`，Hilt 生成的 `Hilt_HiNextApplication.onCreate()` 先执行 `hiltInternalInject()` 完成 `@Inject lateinit var workerFactory: HiltWorkerFactory` 注入，随后才调用 `super.onCreate()`（且 `HiApplication.java:43-81` 本身不触碰 `WorkManager`）。
  - 无论是前台启动 `MainFrameActivity.onCreate()`（`:169` 调 `NotiWorker.scheduleOrCancelWork()`），还是后台进程被系统 `SystemJobService` 冷唤醒执行任务，Android 框架均保证 `HiNextApplication.onCreate()` 先运行完毕；首次调用 `WorkManager.getInstance(context)` 按需触发 `HiNextApplication.workManagerConfiguration` 时，`workerFactory` 100% 已完成注入，绝无 `UninitializedPropertyAccessException`。
  - `WorkManagerNotificationScheduler.kt:57` 将 `WorkManager.getInstance(context)` 封装在惰性 `workManagerProvider: () -> WorkManager` 中，构造器期零提前触发初始化。
- **`HiltWorkerFactory` 反射回退与 R8 Release 兼容**：
  - `HiltWorkerFactory.createWorker` 对带 `@HiltWorker` 的 `NotificationPollingWorker` 走 Hilt `AssistedFactory` 注入；对未标注 `@HiltWorker` 的旧 Java `NotiWorker` 返回 `null`，由 `WorkerFactory.createWorkerWithDefaultFallback` 自动回退至默认 `(Context, WorkerParameters)` 反射构造（`NotiWorker.java:33` 公开构造器由 `proguard-rules.txt:11` `-keep class net.jejer.hipda.** { *; }` 保留）。
  - 已由 Robolectric（`NotificationSchedulerTest.kt:90-109`）与 API 36 R8 Release 真模拟器冷启动冒烟（Run `#45651379584`）双重验证通过。

### 2. 与旧 `NotiWorker` 共存不会产生双重通知核验（通过）
逐层核对确认 **不存在向用户弹出双重通知的路径**：
1. **当前阶段（M3–M5，`933a5ac`）零自动入队**：全仓检索确认 `NotificationScheduler` 仅在 `NotificationWorkModule` 绑定供后续 `E12` 与新入口接线调用，当前无自动启动调用点，旧界面运行期间不会额外启动 `NotificationPollingWorker`。
2. **M6 预览并存期的凭据与开关物理隔离（`tech-plan.md` §5）**：
   - 旧 `NotiWorker` 默认关闭（`HiSettingsHelper.java:761` `PERF_NOTI_TASK_ENABLED` 默认 `false`），且依赖旧 `LoginHelper.isLoggedIn()` 与旧 `OkHttpHelper` CookieStore（直连 `4d4y.com` 无 Cloudflare 验证通道会获 403，见 `lesson.md` §站点）；
   - 新 `NotificationPollingWorker` 依赖新 `SettingsRepository` 与 Tink 加密 `CredentialsStore`（`hasActiveAccount()`），仅在旧界面登录时命中 Gate 4 直接静默退出。
3. **极端双端同时开启时的通知槽位合一兜底**：即便测试者在调试环境中同时开启新旧两套轮询并均拿到未读数据，`NotiHelper.java:51, 203, 217` 与 `AndroidNotificationPoster.kt:77, 84, 112, 114` 共用完全相同的通知渠道 `CHANNEL_ID = "HIPDA_NOTI"`、通知 ID `NOTIFICATION_ID = 0`（无 tag）且均设置 `.setOnlyAlertOnce(true)`，系统 `NotificationManager` 只会原地覆盖同一条通知槽位且不二次响铃震动，绝不堆叠两条状态栏通知。

---

## 三、四项审查重点逐条核验

### 1. 共享文件最小改动与多线零冲突守卫（通过）
- `git diff --name-status 2a3f7ca...933a5ac` 共涉及 **14 个文件**（`+1937 / -16`）：
  - 新增生产文件（5 个）：`service/{QuietHoursChecker,NotificationPoster,NotificationPollingWorker,NotificationScheduler}.kt`、`di/NotificationWorkModule.kt`；
  - 授权共享文件最小修改（2 个）：`ui/HiNextApplication.kt`、`AndroidManifest.xml`；
  - 授权闭合 R-D7 S1–S4 文件（4 个）：`data/message/{DefaultSmsRepository,DefaultNotifyRepository}.kt`、`test/.../data/message/{SmsRepositoryTest,NotifyRepositoryTest}.kt`；
  - 新增单测文件（3 个）：`test/.../service/{QuietHoursCheckerTest,NotificationPollingWorkerTest,NotificationSchedulerTest}.kt`。
- 与当前 `origin/main`（已合入 `D4`/`D5`/`D6`/`C4d`）及在途 `origin/card/D8a-search-favorite-repo` 做 `comm -12` 文件集交集比对，结果均为 **0 重叠**；`HiApplication.java`、`NotiWorker.java`、`NotiHelper.java`、Gradle 构建文件、`proguard-rules.txt`、`core-parser/`、样本文件与 `MotionTokenScanTest` 均为 **0 改动**。
- 全量核查确认新增/修改代码 **0 处 `Thread.sleep`**，测试网络请求 100% 指向本地 `MockWebServer`。

### 2. 后台验证静默放弃、4 级门禁与免打扰边界（通过）
- **4 级零网络前置门禁**（`NotificationPollingWorker.kt:56-88`）：
  1. `appVisibilityProvider.isAppVisible()`（默认委托 `HiApplication.isAppVisible()`，基于全局 `ActivityLifecycleCallbacks` 计数，同时覆盖旧 Activity 与新 Compose Activity）；
  2. `!settingsRepository.settings.first().notificationsEnabled`（`SettingsRepository.settings` 为基于 DataStore 的冷流，挂起读取真实磁盘值无默认值误判竞态）；
  3. `QuietHoursChecker.isInQuietHours(...)`（经可注入 `PollingClock` 提供本地分钟数）；
  4. `!hasActiveAccount()`（优先快判 `authRepository.currentAccount.value != null`；为 `null` 时检查 `credentialsStore.manuallyLoggedOut` / `activeUid` / `accounts`，手动退出或未登录立即返回 `false`，后台进程冷启动有活跃账号时最多等待 `2_000ms` 完成 `DefaultAuthRepository` 首次凭据水合）。
  - 四种门禁命中均发起 **0 次网络请求** 并返回 `Result.success()`；请求返回后（`:102`）二次校验 `!appVisibilityProvider.isAppVisible() && authRepository.currentAccount.value != null`，防止飞行途中切回前台或退出登录误发陈旧通知。
- **`BackgroundSilent` 与绝不重试风暴**（`NotificationPollingWorker.kt:90-115`）：
  - 显式传入 `AccessPriority.BackgroundSilent`（`:91`）；
  - 直接遇 Cloudflare 403 `cf-mitigated: challenge`（`SiteAccessGateway.kt:101, 125` 立即抛 `ChallengeAbandonedException`，0 次调用 `ChallengeSolver`）或会话过期静默重登途中遇验证（`DefaultAuthRepository.kt:527, 850` 映射 `CHALLENGE_ABANDONED` 且不污染全局 `authState`）均转为 `NotificationPollResult.ChallengeAbandoned` → Worker 安静返回 `Result.success()`；
  - `AuthRequired`、`ForumHttpException`、`IOException` 与站点 `alert_error` 均返回 `Result.success()`，全文件 **0 处 `Result.retry()`**；
  - `:64-65`、`:92-93`、`:129-130` 均显式捕获并原样重抛 `CancellationException`。
- **`QuietHoursChecker` 全分支核验**（`QuietHoursChecker.kt:14-30`）：
  - `!silentModeEnabled -> false`；
  - 输入经 `Math.floorMod(..., 1440)` 归一至 `[0, 1439]`；
  - `begin == end -> false`（避免误全天静音）；
  - 同日 `begin < end`：`current in begin until end`（左闭右开 `[begin, end)`）；
  - 跨午夜 `begin > end`（如 `22:00–08:00`）：`current >= begin || current < end`（`21:59 -> false`、`22:00 -> true`、`23:59 -> true`、`00:00 -> true`、`07:59 -> true`、`08:00 -> false`）。

### 3. 周期调度与通知文案合规（通过）
- **`WorkManagerNotificationScheduler`**（`NotificationScheduler.kt:46-104`）：
  - 唯一任务名 `WORK_NAME = "hipda_next_notification_polling"`（与旧 `"noti_work"` 互不覆盖，已由 `NotificationSchedulerTest.kt:138-153` 实测验证取消新任务不影响旧任务）；
  - 周期 `15L` 分钟、`NetworkType.CONNECTED`、`ExistingPeriodicWorkPolicy.KEEP` 幂等保计时器；提供 `syncSchedule(Boolean)`、`syncFromSettings()`、`schedule()`、`cancel()` 完整覆盖 `E12` 需求。
- **通知文案与术语表核对**（`NotificationPoster.kt:112-137`）：
  - 单条未读短消息：`"<发送者> 的短消息"` + 摘要；汇总：`"HiPDA 论坛通知"` + `"您有 N 条新的短消息，M 条新的提醒"`（单侧非零仅显示对应项，全角中文逗号，无感叹号）；双计数归零自动 `cancel()`；
  - 严格使用 `product-design.md §8` 标准术语“短消息 / 提醒 / 通知”，**零出现**“私信 / 站内信 / 信息提醒 / 消息提醒 / 帖子提醒 / 消息通知”等禁用词组；`notify()` 对 API 33+ 潜在 `SecurityException` 做了静默保护（`:83-87`）。

### 4. R-D7 S1–S4 与 `cooldownLock` 锁内读 `currentUid` 收口核验（通过）
- **`cooldownLock` 内读取 `currentUid`（`DefaultSmsRepository.kt:240-246`）**：
  - `val currentUid = authRepository.currentAccount.value?.uid` 已移入 `synchronized(cooldownLock)` 内部，与 `syncCooldownAccount()`（`:311-321`）按同一把锁严格全序化：若 `logout()`/`switchAccount()` 先发生则不写入冷却，若后发生则由 `syncCooldownAccount()` 立即清零 `_cooldownUntilMs`，彻底闭合 R-D7 补审指出的纳秒级 TOCTOU 窗口。
- **S1（`DefaultSmsRepository.kt:364-371, 388-405`）**：
  - 从 `UNAUTH_AJAX_MARKERS` 移除宽泛的 `"无权进行当前操作"` 并新增 `if (errorText.contains("您已经登录")) return false`；已登录用户组受限 `NOPERM` 直接返回 `SmsSendOutcome.Rejected`（0 重登、0 重试 POST），而未登录 `NOPERM`（含 `"您还未登录"`）仍正常触发单飞静默重登与重试（`rD7S1` + 既有重登单测双绿）。
- **S2（`DefaultNotifyRepository.kt:385-437`）**：
  - `updateFromPageMeta` 在 `notifyTotal == 0 && state.suppressedServerNotifyCount > 0` 时捕获 `(ownerUid, accountGeneration)` 异步调用 `clearSuppressedServerCountSafely`；在 `storeMutex.withLock` 内二次加 `stateLock` 校验 `accountGeneration == expectedGen && activeOwnerUid == ownerUid && suppressedServerNotifyCount == 0` 后再清盘，既防止进程重启后旧水位误压制新提醒，又保证期间若并发发生新的 `markAllNotificationsRead()` 绝不误清更新水位（`rD7S2` 验证通过）。
- **S3（`DefaultNotifyRepository.kt:144-156, 215-221, 264-275, 329-358, 427-436`）**：
  - `pollUnreadCounts` 补齐 `!smsPage.hasContainer && !smsPage.meta.errorMessage.isNullOrBlank()` 站点 `alert_error` 拦截（不误清既有未读数）；全文件所有 `readStateStore` 写盘点均包裹 `IOException` 兜底且原样重抛 `CancellationException`（`rD7S3` 验证通过）。
- **S4（`SmsRepositoryTest.kt:802-898`、`NotifyRepositoryTest.kt:898-971`）**：
  - 3 处单行变异盲区全部补齐且精准命中有缺陷变异：`rD7S4a` 锁死切号/退出即时清零 15 秒冷却；`rD7S4b` 在不调 `getNotifications()` 的前提下锁死冷启动 `hydrateAccount` 恢复 `notifyUnreadCount` 与 `suppressedServerNotifyCount`；`rD7S4c` 让重登后重试成功的短消息正文回显 `"您还未登录"`，锁死 `ForumHttpClient.kt:96` 重试后的 `SmsSendResult.Success` 短路分支。

---

## 四、非阻断改进建议（供后续 `E12` / `M7` 顺带吸纳）

- **S1（`NotificationPollingWorker.hasActiveAccount` 异常兜底对齐与冷启动单测确定性加固）**：`NotificationPollingWorker.kt:131` 仅捕获 `IOException`（不同于同文件 `:66` 与 `:94` 的 `catch (_: Exception)`），若后台唤醒时设备 Keystore 不可用导致 `TinkAeadProvider` 抛出 `GeneralSecurityException` / `ProviderException` 会逸出使 Worker 记为 `FAILURE`；建议统一改为 `catch (_: Exception) { return false }`（保留重抛 `CancellationException`），并在 `NotificationPollingWorkerTest.kt:340` 对初始凭据流加门闩挂起，使冷启动水合等待分支 100% 不依赖 `Dispatchers.Default` 调度先后。
- **S2（通知栏文案在 `E12`/`M7` 归口 `copy.md` 与 `strings_notification.xml`）**：受本卡禁改 `res/values/` 边界约束，`NotificationPoster.kt:112-135` 的通知渠道名与标题/正文目前定义为 Kotlin 常量（术语已完全符合 `product-design.md §8`）；后续 `E12` 接通知设置或 `M7` 切换通知点击入口时，建议将其补入 `docs/design/copy.md` 并提取至 `res/values/strings_notification.xml`，纳入统一的字符串资源术语扫描守卫。
- **S3（`M7` 移除旧 `NotiWorker` 时顺带取消遗留 `"noti_work"` 唯一任务）**：当前 M3–M6 共存期保留独立任务名互不干扰是正确的；待 `M7` 删除 `NotiWorker.java` 时，建议在启动初始化或 `NotificationScheduler` 中执行一次 `WorkManager.cancelUniqueWork("noti_work")`，清理开发/预览阶段可能残留在 `androidx.work.workdb` 中的旧周期 `WorkSpec`，避免类删除后触发 `WorkerFactory` 反射找不到类的报错日志。
