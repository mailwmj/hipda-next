# 独立审查报告 R-D7：短消息与提醒 Repository

- **审查结论**：**APPROVE**（无阻断项，附 4 条非阻断改进建议）
- **审查对象**：`origin/card/D7-sms-notify-repo` @ `437ca8862d29c27081156d69fa8509e550df6bea`
- **比较基线**：`0e5fb577d04c86ad505b3a3af7f859f1eaff914e`（与当前 `origin/main` @ `5ce63dcc45fb9447d2795dcf548be0c6446004cd` 的 `merge-base` 一致，相对 `origin/main` 的 diff 完全相同）
- **CI 核验**：[Android CI #45631220736](https://github.com/mailwmj/hipda-next/actions/runs/45631220736)
  - `headSha`：`437ca8862d29c27081156d69fa8509e550df6bea`（精确匹配）
  - 状态与结论：`completed / success`
  - 已核实执行任务：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、`:core-parser:test`、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true` 与 Room Schema 校验）
  - 单元测试汇总：`TOTAL: 780 tests, 780 passed, 0 failed, 0 errors, 0 skipped`（其中 D7 新增 `SmsRepositoryTest` 14/14、`NotifyRepositoryTest` 15/15、`NotifyReadStateStoreTest` 8/8，共 37/37 全绿；`BackupExclusionRulesTest` 6/6 全绿）

---

## 一、专项审查：`inajax=1` 发送 `isUnauthenticated` 谓词、“成功回显短路防重发”与重登重试防重发

实现者将第 2 步 `POST pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1` 从卡片原始提示的 `requireAuth = false` 升级为复用 `D2` 修复轮新增接口：
`forumHttpClient.postGbkForm(..., requireAuth = true, isUnauthenticated = ::isSmsAjaxUnauthenticated)`（`DefaultSmsRepository.kt:223-229, 381-395`）。

逐路径代码核查结论：**不存在重复发出同一条短消息的路径**。具体证据链如下：

1. **正常发送成功且消息正文含未登录关键字回显（第 1 次 POST 即成功）**：
   - `DefaultSmsRepository.kt:214-215` 恒携带非空 `lastdaterange` 与 `handlekey = "pmreply"`，Discuz! 7.2 `pm.php` 在 `uc_pm_send(...) > 0` 后渲染 `pm_node.htm`（含外层模板字面量 `<div class="summary">...</div>`，已对照真机样本 `sms_detail.html:100` 核实）。
   - `DefaultSmsRepository.isSmsAjaxUnauthenticated`（`DefaultSmsRepository.kt:381-385`）第一行先调用 `SmsParser.parseSmsSendResult(response.html)`；只要含 `class="summary"` 即判定 `SmsSendResult.Success` 并**无条件短路返回 `false`**（早于 HTML `#umenu` 检查与 `UNAUTH_AJAX_MARKERS` 文本匹配）。
   - `ForumHttpClient.executeWithAuthRetry`（`ForumHttpClient.kt:84-86`）见 `!isUnauthenticated(firstResponse)` 立即返回 `firstResponse`，**0 次静默重登、0 次重试 POST**（已由 `SmsRepositoryTest.kt:429-456` 锁定验证）。
   - 即便极端情况下服务端未走 `pm_node` 而返回静态 `showmessage('pm_send_succeed')`（`短消息发送成功。`），该模板不回显用户 `message` 且不命中 `UNAUTH_AJAX_MARKERS`，`isSmsAjaxUnauthenticated` 同样返回 `false`，绝不触发二次 POST。

2. **会话过期静默重登并自动重试（第 1 步前过期 vs 第 1、2 步之间过期）**：
   - **第 1 步前已过期**：`GET pm.php?daterange=1&uid=...`（`requireAuth = true`）在 `getHtml` 内完成单飞静默重登与重试并拿到新会话 `formhash`，此时尚未发起任何 POST；随后第 2 步仅执行 **1 次 POST**。
   - **第 1 步与第 2 步之间恰好过期**：
     1. 第 1 次 POST 到达 Discuz! 7.2 `pm.php` 入口时，脚本在 `submitcheck` 与 `uc_pm_send` 之前即命中 `if(!$discuz_uid) showmessage('not_loggedin', NULL, 'NOPERM')` 并 `dexit()`，**服务端落库数为 0**；
     2. `isSmsAjaxUnauthenticated(firstResponse)` 识别 `"您还未登录"` 返回 `true`，触发单飞 `trySilentRelogin`；
     3. 重登恢复后，`ForumHttpClient.kt:95` 仅调用单次 `executeSingleAttempt`（非递归，最多重试 1 次）。此时服务端首次且唯一一次执行 `uc_pm_send`；
     4. **关键防重发闭环（`ForumHttpClient.kt:96`）**：重试响应返回后，`ForumHttpClient` 再次调用 `!isUnauthenticated(retriedResponse)`。若此处没有 `parsed is SmsSendResult.Success -> return false` 短路，用户发送含 `"您还未登录"` 的短消息在重登重试成功后会被误判为 `SESSION_STILL_UNAUTHENTICATED` 抛 `AuthRequiredException`，导致不进 15 秒冷却、UI 标红提示失败并诱导用户点“重发”造成二次发送。由于 `DefaultSmsRepository.kt:382-385` 在最顶端无条件短路 `SmsSendResult.Success`，重试成功回显含敏感词时同样稳态返回 `false` → `SmsSendOutcome.Sent` 并启动 15 秒冷却。

3. **静默重登失败后用户在 UI 手动重登再点“重发”**：
   - 返回 `SmsSendOutcome.AuthRequired` 的所有分支（第 1 步 GET 鉴权失败、第 2 步首次 POST 未登录且重登失败/被抑制、第 2 步重试 POST 仍未登录）在服务端均未到达 `uc_pm_send`（已发条数严格为 `0`）；网络断连/HTTP 5xx 在 `ForumHttpClient.kt:140-143` 直接抛 `IOException`/`ForumHttpException`，不走自动重试。用户手动重登后点击“重发”只会发出 1 条短消息。

4. **谓词边界小瑕疵（非阻断，见建议 S1）**：
   - `DefaultSmsRepository.kt:365` 的 `UNAUTH_AJAX_MARKERS` 包含宽泛子串 `"无权进行当前操作"`。Discuz! 7.2 对**已登录但用户组受限**（如禁言组/新手组无发信权限）输出的 `NOPERM` 文案同样含 `"您无权进行当前操作……您已经登录，但您的帐号或其所在的用户组无权访问当前页面"`。由于服务端拒绝发信，不会重发消息，但会多触发 1 次无谓静默重登 + 1 次重试，并把结果从 `SmsSendOutcome.Rejected(...)` 误映射为 `SmsSendOutcome.AuthRequired(SESSION_STILL_UNAUTHENTICATED)`。

---

## 二、四项审查重点逐条核验

### 1. 边界与五线零冲突守卫（通过）
- `git diff --name-status 0e5fb577d04c86ad505b3a3af7f859f1eaff914e...437ca8862d29c27081156d69fa8509e550df6bea`（共 10 个新增文件，`+3325 / -0`）：
  - `hipda/src/main/java/net/jejer/hipda/data/message/{MessageModels,SmsRepository,DefaultSmsRepository,NotifyReadStateStore,NotifyRepository,DefaultNotifyRepository}.kt`（6 个文件，1494 行）
  - `hipda/src/main/java/net/jejer/hipda/di/MessageModule.kt`（1 个文件，62 行）
  - `hipda/src/test/java/net/jejer/hipda/data/message/{NotifyReadStateStoreTest,SmsRepositoryTest,NotifyRepositoryTest}.kt`（3 个文件，1769 行）
- 与同波 `origin/card/D8b-user-blacklist-repo`（`data/user/*`、`di/UserModule.kt`）及 `B7`、`B8`、`G2`（已合入 `origin/main` 的液态玻璃改动）、`data/settings/`、`data/auth/`、`data/network/`、`data/local/`、`data/account/`、`backup_config.xml`/`data_extraction_rules.xml`、既有测试热点、旧 Java 代码与共享文件 **100% 零文件重叠**。
- 全量源码扫描确认 10 个文件内 **零 `Thread.sleep`、零 `delay(`**，测试全部命中本地 `MockWebServer`（`@Before` 覆写 `forumServer = server.url("")`）与 `AtomicLong` 虚拟时钟。

### 2. 短消息两步发送、GBK 表单与 15 秒冷却语义（通过）
- **两步发送与字段核对**（对照 `HiUtils.java:139-147`、`PostSmsAsyncTask.java:50-109`）：
  - 第 1 步：`GET pm.php?daterange=1&uid=$encodedUid`（`requireAuth = true`，按用户名发送时 `uid=`），通过 `DiscuzCommonParser.parseFormhash(Jsoup.parse(prefetchResponse.html))` 仅提取表单 `<input>` `formhash`（避免误取 `#umenu` 退出链接 `formhash`），缺失立即返回 `SmsSendOutcome.FormhashMissing` 且不发 POST（`DefaultSmsRepository.kt:188-204`）。
  - 第 2 步：按 UID 提交 `pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=$encodedUid`，按用户名提交 `pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1` 并附 `msgto`；GBK 表单含 `formhash`、`lastdaterange`、`handlekey="pmreply"`、`message`（补充平面 Emoji 由 `gbkFormBody` 转为 `&#128512;`，中文按 GBK 编码）及无 `uid` 时的 `msgto`（`DefaultSmsRepository.kt:206-220`）。
- **15 秒冷却与并发互斥**：
  - `sendMutex` 串行化冷却检查与网络请求；`computeRemainingCooldownSeconds`（`DefaultSmsRepository.kt:368-372`）采用 `((remainingMs + 999L) / 1_000L).toInt()` 严格向上取整（`1..1000ms -> 1s`，`14_001..15_000ms -> 15s`，`0ms -> 0s`）；冷却期内直接返回 `CooldownActive` 且发起 **0 次网络请求**。
  - 仅在 `SmsSendResult.Success` 分支写入 `_cooldownUntilMs = sentAt + 15_000L`（`DefaultSmsRepository.kt:237-246`）；参数校验失败、缺 `formhash`、业务拒绝、鉴权失败、验证异常与网络错误均不触发冷却。
- **会话详情与清空对话**：
  - `getConversations`（`privatepm`/`newpm`）、`getConversationDetail`（`pm.php?daterange=5&uid=...`，动态拼接 `avatarBaseUrl` 并对 `pm = 0` 抑制旧页头 `prompt_pm` 回弹）、`clearConversation`（`pm.php?action=del&uid=...&filter=privatepm`，检查 `meta.errorMessage`）路径与语义均正确。

### 3. 分离未读流、单条/全部已读持久化与 `D9` 透传契约（通过）
- **双流严格分离**：`DefaultNotifyRepository.kt:85-90` 独立维护 `smsUnreadCount: StateFlow<Int>` 与 `notifyUnreadCount: StateFlow<Int>`，`PromptCounts.notifyTotal` 仅含 `thread + systemPm + friend`，两条流互不混加，符合 `_reports/D-3pane.md` §5 与 `BATCH-4.md` §1.4。
- **稳定提醒键与不误清未读**：
  - `NotifyEntry.computeKey`（`MessageModels.kt:95-115`）对 `(kind, tid, pid, authorUid, author, time, title, info)` 做长度前缀编码后取 SHA-256 前 128 位，刻意排除随刷新变化的 `isNew` 与随域名变化的 `avatarUrl`。
  - `getNotifications` 加载 `notice.php` 仅与 `readKeys` 对照生成 `isRead` 并持久化 `unreadKeys`，绝不将未打开提醒自动标为已读；后续刷新 `notice.php`、`updateFromPageMeta(PromptCounts(0))` 或 `pollUnreadCounts` 均保留本地未打开提醒计数。
  - `markNotificationRead` / `markAllNotificationsRead` 落盘持久化并即时更新 `notifications` 与 `notifyUnreadCount`。
- **`pollUnreadCounts` 面向 `D9` 透传契约**（`DefaultNotifyRepository.kt:272-348`）：
  - 原样透传 `priority` 至 `ForumHttpClient.getHtml("pm.php?filter=newpm", priority = priority, requireAuth = true)`；
  - 同时捕获直接抛出的 `ChallengeAbandonedException` 与静默重登包装的 `AuthRequiredException(RELOGIN_FAILED, Failed(CHALLENGE_ABANDONED))`，统一返回 `NotificationPollResult.ChallengeAbandoned`，不调 `ChallengeSolver`、不清空既有计数；
  - 当且仅当 `unreadSmsItems.size == 1` 时带出 `latestUnreadSms` 预览，对齐 `NotiHelper.java:76-78`。
- **多账号切换与退出隔离**：`A -> B` 与 `A -> null` 立即递增 `accountGeneration`、清零内存双未读流与列表，并在 `NotifyReadStateStore` 中按 `account_<uid>` 独立分键，在途旧代际响应均被拦截丢弃。

### 4. DataStore 健壮性、对外 API 清单与变异盲区核查（通过，附建议 S2–S4）
- **DataStore 健壮性**：`MessageModule.kt:54-60` 配置 `ReplaceFileCorruptionHandler { emptyPreferences() }`；`NotifyReadStateStore` 具备读流 `IOException -> emptyPreferences()`、损坏 JSON / 错类型单账号回退（不影响其他账号）及 `MAX_READ_KEYS = 500` / `MAX_UNREAD_KEYS = 500` 保尾去重裁剪。
- **对外 API 清单核对**：`_reports/D7.md` 第四节完整覆盖 `D9`/`E8`/`E9` 与三栏左栏所需的全部公开接口与模型（仅一处细微行号笔误：`DefaultSmsRepository.SMS_COOLDOWN_MS` 实际位于 `DefaultSmsRepository.kt:354`，报告误标为 `:349`）。
- **单行变异与测试盲区排查**：
  - 审查卡列举的 4 类核心变异（`inajax=1` 漏传自定义谓词或去掉 `Success` 短路、发送失败误启冷却、单条已读未落盘、双未读流混加）均会立即使现有单测变红；
  - 额外找出的非阻断盲区与边界项归入下方 S1–S4。

---

## 三、阻断项

无。

---

## 四、非阻断改进建议

- **S1（收紧 `UNAUTH_AJAX_MARKERS` 中的 `"无权进行当前操作"`）**：`DefaultSmsRepository.kt:359-366, 393-395` 将 `"无权进行当前操作"` 视为未登录，会把已登录但用户组受限的 Discuz! `NOPERM` 响应（含 `"您已经登录"`）误判为会话失效，多发 1 次静默重登与重试并返回 `AuthRequired(SESSION_STILL_UNAUTHENTICATED)` 而非 `Rejected`；建议排除含 `"您已经登录"` 的文本或移除独立的 `"无权进行当前操作"`（未登录 `NOPERM` 已含 `"您还未登录"`/`"尚未登录"`/`"请先登录"`）。
- **S2（`updateFromPageMeta` 归零时同步清理持久化的 `suppressedServerNotifyCount` 水位）**：`DefaultNotifyRepository.kt:355-368` 在 `promptCounts.notifyTotal == 0` 时仅将内存 `suppressedServerNotifyCount` 置 `0`，未像 `pollUnreadCounts`（`:315-324`）那样异步调用 `readStateStore.clearSuppressedServerCount`；若此后进程重启且尚未打开 `notice.php`，`hydrateAccount`（`:531-534`）会从磁盘恢复旧水位并压制同数量的新到达页头提醒。
- **S3（`pollUnreadCounts` 补齐 `alert_error` 守卫与 DataStore 写异常兜底）**：`DefaultNotifyRepository.kt:302-325` 未像 `getConversations`（`DefaultSmsRepository.kt:109`）和 `getNotifications`（`:123`）那样拦截 `!smsPage.hasContainer && !smsPage.meta.errorMessage.isNullOrBlank()`，且 `:320` / `:142` 的 `readStateStore` 写操作位于 `IOException` `try/catch` 之外，磁盘满时可能向 `D9` Worker 抛出未捕获 `IOException`。
- **S4（补齐 3 处单行变异测试盲区）**：(a) `SmsRepositoryTest` 补充切换账号/退出登录立即重置 15 秒冷却用例（当前删掉 `DefaultSmsRepository.kt:71-80, 306-316` 全部 37 条单测仍通过）；(b) `NotifyRepositoryTest` 补充重启后未调 `getNotifications()` 前即断言冷启动水合恢复 `notifyUnreadCount` 与 `suppressedServerNotifyCount` 的用例（当前清空 `DefaultNotifyRepository.kt:508-539` `hydrateAccount` 全部 37 条单测仍通过）；(c) `SmsRepositoryTest` 在会话过期重登重试成功用例中让回显正文直接含 `"您还未登录"`，锁死 `ForumHttpClient.kt:96` 重试后的短路分支；顺带修正 `_reports/D7.md:169` 中 `SMS_COOLDOWN_MS` 行号（`:349` → `:354`）。
