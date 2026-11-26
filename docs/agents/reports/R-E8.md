# 独立代码审查报告 R-E8：短消息与提醒（含 R-D9 S2 系统通知文案归口，commit `b40fb4a`）

- **审查结论**：**`CHANGES-REQUESTED`**
- **审查对象**：`origin/card/E8-messages-and-notify` @ `b40fb4aa9f42c027a5e84703951bb2db81874d50`（简称 `b40fb4a`）
- **对比基线**：`89f8e37`（与当前 `origin/main` @ `4b6c1f0` 执行 `git merge-tree --write-tree 4b6c1f0 b40fb4a` 输出 tree `cf1d52bd61a9a3017615b83f7e90c7c3b8a389da`，`0` 冲突）
- **审查角色**：独立代码审查（Carbon 只读审查 + 12 张新增 Roborazzi PNG 逐张目视复验；主目录 `/home/mir/Documents/hipda-next` 保持 `0` 改动、未 checkout、未本地编译）
- **GitHub Actions CI 核实**：[Android CI #45732999389](https://github.com/mailwmj/hipda-next/actions/runs/45732999389)（Job `139619337699`）
  - `headSha`：`b40fb4aa9f42c027a5e84703951bb2db81874d50`（精确匹配 `b40fb4a`）
  - `status / conclusion`：`completed / success`
  - 已核实通过步骤：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`，`Check for Roborazzi diff images` 0 漂移跳过）、`Verify APK outputs`。
- **阻断项（Blocking）**：**3 项**（`B1`–`B3`）
- **非阻断建议项（Suggestions）**：**4 项**（`S1`–`S4`，含跨卡提醒 1 项）

---

## 一、阻断项清单（Blocking Issues）

### `B1` — 乐观发送气泡 `local:*` 与 `loadConversation` (`server:*`) 缺少并发纪元对齐，慢网/下拉刷新竞态下会导致已确认短消息消失或重复成两条
- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationViewModel.kt:213-216, 244-248`
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationScreen.kt:141-146`
- **机理分析**：
  - Discuz! 7.2 `pm.php?action=send...&inajax=1` 成功 XML 仅返回 `<li class="s_clear self"><div class="summary">...</div></li>`，`SmsParser.parseSmsSendResult` 返回无服务端消息 ID 的 `SmsSendResult.Success`。因此 `dispatchSend`（`SmsConversationViewModel.kt:213-216`）仅靠协程闭包捕获的 `localId`（`"local:N"`）把原乐观气泡原地置为 `Delivered`。
  - 然而 `loadConversation`（`:244-248`）在合入服务端列表（`id = "server:$index:$uid:$time:${contentHtml.hashCode()}"`）时直接执行：
    ```kotlin
    val merged = serverBubbles + cur.messages.filter { it.deliveryStatus != SmsBubbleDeliveryStatus.Delivered }
    ```
    两者之间没有任何单调水位/纪元对齐；同时 `SmsConversationScreen.kt:141` 在首屏 `isLoading == true`（`!hasLoadedOnce`）及下拉 `isRefreshing == true` 期间均允许输入并点击发送。
- **具体触发路径**：
  1. **路径 ①（已确认发送成功的气泡凭空消失）**：
     - 慢网打开对话（`bindConversation` 发起 `GET pm.php?daterange=5&uid=...` 在途），或用户点发送后立即下拉刷新（`refresh()`）；
     - 服务端先拍下不含新消息 `M1` 的旧列表快照，但响应在网络上滞后；
     - 期间 `sendSms(M1)` 先返回 `SmsSendOutcome.Sent`，`:215` 将 `local:1` 更新为 `Delivered`；
     - 随后滞后的旧 `GET` 响应到达主线程，`:246` 过滤掉全部 `deliveryStatus == Delivered` 的本地气泡，并用不含 `M1` 的旧 `serverBubbles` 覆盖——**`M1` 明明已发送成功，却在界面上直接消失**。
  2. **路径 ②（同一条短消息永久显示为两条重复气泡）**：
     - 用户发送 `M1`（`local:1` 处于 `Sending`），服务端已完成 `POST` 落库，但客户端协程尚未恢复到 `:213`；
     - 并发在途的 `loadConversation` 在服务端落库之后命中 `pm.php?daterange=5`，拉回已包含 `M1` 的 `serverBubbles`，并先执行 `:246`：因 `local:1` 此刻仍为 `Sending`，`merged` 同时保留了 `server:*:M1` 与 `local:1(Sending)`；
     - 紧接着 `dispatchSend` 恢复执行，把 `local:1` 改为 `Delivered`——**对话列表末尾永久出现两条一模一样的 `M1` 气泡**。
- **修复要求**：
  - 首屏未完成首次加载（`!hasLoadedOnce && isLoading`）时禁用 `canSend`，防止首屏盲发被初始加载冲掉；
  - 在 `SmsConversationViewModel` 中维护发送水位（如每次发起/完成发送递增 `sendMutationSeq`，`loadConversation` 在请求发起前记录 `seqAtLoadStart`）：若 `loadConversation` 返回时发现存在跨请求周期新增或刚确认的发送，丢弃过期的旧列表快照或由 `Sent` 完成分支补发一次静默对齐刷新，确保任何交错时序下既不丢已确认消息，也不产生 `server:*` + `local:*` 双份气泡，并补齐并发交错单测。

---

### `B2` — 慢网连发两条与失败重发顺序错乱，且「冷却期间重发失败消息」零单测覆盖（删 `:155` 一行全绿）
- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationViewModel.kt:151-158, 171-174, 206-229`
  - `hipda/src/test/java/net/jejer/hipda/feature/messages/MessagesScreenBehaviorTest.kt:485-486`
  - `hipda/src/test/java/net/jejer/hipda/feature/messages/MessagesViewModelMockWebServerTest.kt:168-225`
- **具体触发路径与问题拆分**：
  1. **失败重发导致界面顺序与服务端真实时序颠倒（`SmsConversationViewModel.kt:156, 231-232`）**：
     - 慢网/弱网下首条消息 `M1` 在途期间 `cooldownSeconds == 0`，用户继续输入并发送第二条 `M2`，界面气泡为 `[M1(Sending), M2(Sending)]`；
     - 若 `M1` 因超时/500 失败（转为 `Failed`，不触发冷却），紧随其后的 `M2` 发送成功（在服务端以时间 `T1` 落库），界面变为 `[M1(Failed), M2(Delivered)]`；
     - 15 秒冷却结束后，用户点击 `M1` 旁红感叹号选择 `重发`（`resendMessage`），`:156` 原地更新 `M1` 状态而不将其移至队尾，`M1` 在服务端以 `T2 > T1` 落库后，**界面定格为 `[M1(Delivered), M2(Delivered)]`，与服务端实际记录顺序 `[M2, M1]` 完全相反**（下次刷新即发生跳变互换）。
     - 同理，`restoreDeletedMessage`（`:171-174`）按删除时记录的静态 `snapshot.index` 插回：若删除失败气泡后发生了下拉刷新并拉回对端新消息，点 Snackbar `撤销` 会把本地失败气泡插进历史服务端消息中间。
  2. **ViewModel 层缺少显式发送串行化，且慢网连发两条 0 测试（`SmsConversationViewModel.kt:209`）**：
     - `dispatchSend` 每次调用直接 `viewModelScope.launch { smsRepository.sendSms(...) }`，自身无 `Mutex` / FIFO 队列，仅偶然依赖 `Dispatchers.Main.immediate` 在无前置挂起点时同步进入 `DefaultSmsRepository.sendMutex`（`DefaultSmsRepository.kt:178`）。对任何锁前含挂起点或不含内部锁的 `SmsRepository`（包括 `FakeSmsRepository` `:485`），慢请求 `M1` 与快请求 `M2` 会直接在网络层乱序送达；全卡未编写任何「慢网连发两条」测试。
  3. **冷却期间重发拦截存在「删一行核心代码仍全绿」的空心盲区（违反 `_review_common.md` §3）**：
     - `SmsConversationViewModel.kt:155` 虽调用了 `if (checkCooldownBlocked(cur.cooldownSeconds)) return`，但 `MessagesScreenBehaviorTest` 与 `MessagesViewModelMockWebServerTest` 全程从未在 `cooldownSeconds > 0` 时触发 `resendMessage` 或 `sendMessage`，也从未断言 `CooldownBlocked`（`发送太快，请等 {N} 秒后再试`）；
     - 同时测试替身 `FakeSmsRepository.sendSms`（`MessagesScreenBehaviorTest.kt:485`）未按真实平台/仓库语义检查 `_cooldownUntilMs`（违反 `lesson.md` 替身真实语义准则）。**若直接删去 `SmsConversationViewModel.kt:155` 一行，本卡全部单元与 UI 测试依然 100% 变绿**。
- **修复要求**：
  - 在 `SmsConversationViewModel` 内用 `Mutex` 或串行队列显式保证发送/重发严格按用户触发的 FIFO 顺序执行；
  - `resendMessage` 通过冷却校验真正发起重发时，将该气泡移至当前 `messages` 队尾并触发滚底（`restoreDeletedMessage` 若原位置已被服务端消息占据则钳制到服务端消息之后）；
  - 修正 `FakeSmsRepository.sendSms` 同步模拟冷却期返回 `SmsSendOutcome.CooldownActive`，并新增自动化测试覆盖：(a) 慢网连发两条（含首条成功次条命中冷却、首条失败次条成功后再重发首条）顺序严格正确；(b) 冷却倒计时期间点击失败气泡 `重发`，状态保持 `Failed`、发出 `发送太快，请等 N 秒后再试` 提示且网络请求数为 `0`。

---

### `B3` — 跨账号切号与退出登录未隔离/清空对话态，泄漏上一账号短消息正文与未发送草稿；且未支持纯 `uid` 恢复用户名
- **位置**：
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/SmsConversationViewModel.kt:106-111, 194-196, 244-248, 253`
  - `hipda/src/main/java/net/jejer/hipda/feature/messages/MessagesPaneRendererImpl.kt:64`
  - （跨卡关联，只读提示不越界修改）`hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt:91, 95`（`origin/main` @ `4b6c1f0`）
- **具体触发路径**：
  1. **路径 ①（账号 A → 账号 B 切号直接暴露账号 A 的短消息正文与未发草稿）**：
     - 在宿主 `MultiPaneReadingContainer` 中，`ConversationContent` 挂载于 Activity 级 `ViewModelStoreOwner`，`MessagesPaneRendererImpl.kt:64` 仅以 `"sms_conversation:${args.uid.ifBlank { args.username }}"` 作为 ViewModel `key`（不含当前登录账号 `uid`）；
     - `SmsConversationViewModel.kt:106-111` 监听账号变化时丢弃了 `uidChanged` 参数（写成 `{ _, signedIn ->`）：当用户从账号 A 直接切换到账号 B（`uidChanged == true, signedIn == true`）且查看同一 `peerUid` 时，**既未清空账号 A 的 `messages`，也未清空 `inputText`**；
     - 紧接着 `loadConversation(false)`（`:238-239`）因 `had = _uiState.value.messages.isNotEmpty()` 为 `true` 而将 `isLoading` 置为 `false`，在网络请求在途期间**直接把账号 A 的私密短消息气泡与输入框草稿原样展示给账号 B**；若账号 B 的请求遇到断网/500/Cloudflare 验证失败，`:253`（`messages = if (isAuth) emptyList() else it.messages`）更会将**账号 A 的短消息永久保留在账号 B 屏幕上**。
  2. **路径 ②（账号 A 退出登录后 `inputText` 草稿残留泄漏）**：
     - 账号 A 在输入框输入私密短消息未发出，随后退出登录；`applySignedOut`（`:194-196`）清空了 `messages`，**却漏掉了 `inputText = ""`**；随后账号 B 登录并打开与该 `uid` 的对话，Activity `ViewModelStore` 复用同一实例，账号 B 在输入框里直接看到账号 A 留下的未发送短消息正文。
  3. **路径 ③（`SavedState` 纯 `uid` 恢复闭环缺口）**：
     - 核查本卡 `feature/messages/*` 自身未写 `SavedState`，但刚合入 `origin/main`（`4b6c1f0`）的宿主 `NextAppState.kt:91, 95` 在 `NextAppStateSaver` 中把对方 `username` 写入了 `Bundle`（违反「`SavedState` 里不能出现对方 UID 以外的个人信息」）。一旦宿主剥离 `username` 仅恢复 `SmsConversationArgs(uid, "")`（或从仅带 `EXTRA_UID` 的入口进入），本卡 `SmsConversationViewModel.loadConversation`（`:244-248`）未从 `res.data.messages` 中非己方消息（`!it.isMine`）回填 `peerUsername`，导致顶栏标题空白、清空确认框显示 `清空与  的全部短消息？`。
- **修复要求（严格守住本卡文件边界，不碰 `feature/app/*`）**：
  - `MessagesPaneRendererImpl.kt:64` 将当前登录 `account?.uid.orEmpty()` 拼入 `SmsConversationViewModel` 的 `key`，同时在 `SmsConversationViewModel` 的 `uidChanged` 分支与 `applySignedOut` 中显式重置 `messages = emptyList()`、`inputText = ""`、`isLoading` 等全部会话私密状态，并补跨账号切换/退登隔离单测；
  - `loadConversation` 成功收到 `res.data.messages` 时，若 `cur.peerUsername.isBlank()`，自动从 `res.data.messages.firstOrNull { !it.isMine && it.author.isNotBlank() }?.author` 回填 `peerUsername`；
  - **跨卡提醒负责人**：`feature/app/NextAppState.kt:91, 95`（属 `E-slots` 已合入文件，本卡禁改区）在 `NextAppStateSaver` 中保存了 `sms?.username` 与下钻 `SmsConversation` 的 `username`，建议由负责人在宿主收口卡（如 `E4` 或 M6 收尾）改为仅持久化 `uid`、恢复时 `username = ""`，与本卡回填逻辑无缝闭环。

---

## 二、负责人三项补充重点与卡片五项重点逐项核查

### 补充点 1：生产代码净增 `+1,702` 行逐文件可删清单
卡片预估 `950–1,150` 行（1.5× 警戒线 `1,725` 行），当前实测净增 **`+1,702` 行**（且多处通过单行压至 150–180 字符才勉强卡在 `1,725` 以内）。逐文件审计可安全删除/复用的真实结构性冗余共约 **`260–310` 行**（扣除修复 `B1`–`B3` 约 `+25` 行后，净增可降至 **`~1,415–1,465` 行**）：

| 生产文件 | 当前净增 | 可删行数 | 具体可删位置与依据 |
|---|---:|---:|---|
| `feature/messages/SmsConversationScreen.kt` | `+365` | **~95–110** | 1. **删除整套自建 `SmsInlineEmojiPicker` 及状态**（`:83, :295-298, :314-352`，约 `-48` 行）：`E8.md:23` 仅要求气泡支持 `:emoji` C1b 渲染，未要求自建选择器；且 `:330` 写死 `height(216.dp)` 未与 IME 互斥，真机弹键盘时打开会叠在键盘上方违背 `product-design.md §7.4`（等高替换键盘面板属 `E6` 范畴），全卡 0 测试。<br>2. **复用 `:hipda` 既有 `InlineEmojiContent.kt` 并删富文本样式分支**（`:223-274`，约 `-38` 行）：`resolveForumEmoji` 与 `buildInlineTextContentMap` 在模块内现成可用，无需重写 `buildSmsInlineContent`；`bold`/`italic`/`underline`/`strikethrough` 组合 `SpanStyle` 非本卡需求。<br>3. **删除顶栏 `⋮` 中重复的 `资料` 菜单项与独立骨架**（`:90, :103, :354-365`，约 `-14` 行）：顶栏标题点击 `:94` 已支持跳资料，`copy.md` 未定义对话顶栏「更多 → 资料」。 |
| `feature/messages/MessagesListScreen.kt` | `+426` | **~60–75** | 1. **删除 UI 层 `Jsoup` 死代码**（`:40, :329`，`-4` 行）：`NotifyParser` 在双 `<dd>` 引用分支已填 `quoteOriginal`/`quoteReply`（不走 `cleanInfo`），其余分支均为 `.text()` 纯文本，UI 层无需二次 `Jsoup.parseBodyFragment`。<br>2. **复用 `ThreadListRow.kt` 常量/颜色函数**（`:43-45, :292, :418-419`，`-10` 行）：`300.dp`、`0.36f`、`0.74f` 与 `resolveReadTextColor` 同 `ThreadListRow.kt:33-48` 100% 重复。<br>3. **复用设计系统 `HiContentState` 替代手写 `MessagePaneStateBox`**（`:240-256`，约 `-15` 行，同时补齐 `150ms` `loadingShowDelay`）。<br>4. **简化分段按钮 `Triple` 包装与本地假分页切片**（`:46, :104-111, :123-127, :166-175`，约 `-30` 行）：`NotifyRepository.getNotifications()` 单次返回全量内存列表且无 `page` 参数，`LazyColumn` 已做视口虚拟化，内存 `take(20)` 无实际收益（若仅因字面响应 `E8.md:25`「分页加载」保留，也应在报告注明）。 |
| `feature/messages/MessageUrlSanitizer.kt` | `+155` | **~25–35** | 1. **删除 `SmsTextStyleFlags` 及 `b/strong/i/em/u/s/del/strike/blockquote` 富文本树传递**（`:47-48, :52, :82, :87-91`，约 `-18` 行）。<br>2. **本地乐观气泡直接走纯文本/表情扫描，删 `escapeHtmlText` 往返转义**（`:117-119`，约 `-8` 行；与 `PostBodyRenderer.sanitizePostUrl` 的合并因禁改 `feature/common/*` 留待 M6 收尾）。 |
| `feature/messages/MessagesErrorMapper.kt` | `+104` | **~25–30** | 1. **删除与 `UiErrorMapper.map(Throwable)` 100% 重复的 `mapRawMessageError` + `buildErrorDetails`**（`:91-104`，`-14` 行）：`cause != null` 直接委托 `UiErrorMapper.map(cause)`。<br>2. **删除多余包装 `runCatchingExceptCancel` 与 `MappedMessageFailure`**（`:18, :22-28`，约 `-12` 行）：`SmsRepository`/`NotifyRepository` 实现内部已全量捕获异常并重抛 `CancellationException`。 |
| `feature/messages/MessagesListViewModel.kt` | `+239` | **~25–30** | 1. **合并同构的 `refresh`/`retry`**（`:121-132`，`-7` 行）。<br>2. **移除 `notifyState` 与 `repo.notifications` 双写及假分页状态**（`:21, :37, :45-47, :157, :169, :181-184`，约 `-20` 行）。 |
| `feature/messages/SmsConversationViewModel.kt` | `+264` | **~15–20** | 删除 `appendEmojiCode`（`:129`）、UI 空消费的死事件 `ConversationCleared`（`:62, :186`）、重复包装 `retry`（`:138`）及 UI 未读取的 `SmsBubbleUiItem.uid` 字段（`:27`），腾出预算用于修复 `B1`–`B3`。 |
| `feature/messages/MessagesPaneRendererImpl.kt` | `+67` | **0** | 对接 `FeaturePaneSlots.kt` 锁定接口的最小胶水实现，无冗余。 |
| `service/NotificationPoster.kt` | `+21` | **0** | 收口 `R-D9 S2` 替换硬编码通知文案为资源读取，精炼合理。 |
| `di/MessagesUiModule.kt` | `+19` | **0** | 单方法 `@Binds @Singleton` Hilt 模块，零冗余。 |
| `res/values/strings_messages.xml` | `+29` | **2–3** | 随自建表情面板与重复 `资料` 菜单删去 `messages_action_emoji`、`messages_action_keyboard`、`messages_action_profile`；其余 17 项均逐字对齐 `copy.md`。 |
| `res/values/strings_notification.xml` | `+13` | **0** | 9 项系统通知标准文案，零冗余。 |

---

### 补充点 2：短消息隐私与通知预览摘要核查
1. **日志（通过）**：全量扫描 `feature/messages/*`、`data/message/*`、`service/NotificationPoster.kt`、`service/NotificationPollingWorker.kt` 与 `HttpClientModule.kt`——`0` 处 `Log.*`/`Logger.*`/`println`，且 OkHttp 未挂载任何 `HttpLoggingInterceptor`，短消息请求体与响应体绝不落日志。
2. **崩溃信息与错误详情（通过）**：
   - `DefaultSmsRepository` 发消息将收件人用户名 `msgto` 与正文 `message` 全部置于 POST 表单体（URL 仅含 `uid`），`ForumHttpException.url` 与 `MessagesErrorMapper` 产出的 `UiError.details` 均不包含短消息正文或对方用户名；`dispatchSend` 失败仅将气泡状态标为 `Failed`，不向 `HiErrorState` 暴露任何消息内容。
3. **`SavedState` 与本地存储（检出 `B3` 内存跨账号残留 + 宿主跨卡提醒）**：
   - 本卡 `feature/messages/*` 内 `0` 处 `SavedStateHandle`、`0` 处 `rememberSaveable`（仅 `LazyListState` 保存滚动下标整数）；`NotifyReadStateStore` 仅按 `account_<ownerUid>` 存储单向 `SHA-256` 前 16 字节哈希键，无明文。
   - **阻断缺口见 `B3`**：Activity 级 `ViewModelStore` 切号/退登残留 `messages`/`inputText`，以及宿主 `NextAppState.kt:91, 95`（`E-slots`）在 `Bundle` 中保存了对方 `username` 而本卡 `loadConversation` 未支持无 `username` 时的自动回填。
4. **系统通知预览文本仅取摘要核实（通过，附加固建议 `S2`）**：
   - 链路核实：`NotificationPollingWorker` → `DefaultNotifyRepository.pollUnreadCounts` 仅请求列表页 `GET pm.php?filter=newpm`（绝不请求详情页 `pm.php?daterange=5`）→ `SmsParser.parseSmsList`（`SmsParser.kt:47`）仅提取 `ul.pm_list > li > div.summary` 的 `summaryElement.text()`（服务端截断纯文本摘要，剥离全部 HTML 标签）→ `AndroidNotificationPoster.formatNotificationContent`（`NotificationPoster.kt:122-133`）仅在 `smsUnreadCount == 1 && notifyUnreadCount == 0` 时以 `singleSms.summary.trim()` 填入单行 `setContentText`（未挂 `BigTextStyle`），当未读 `> 1` 条或含提醒时一律退化为纯数字汇总（`您有 N 条新的短消息`），绝不读取或拼装短消息全文。

---

### 补充点 3 & 卡片重点 1：乐观发送、慢网连发保序、冷却重发与清空二次确认
- **本地 id 与服务器确认映射**：单次无并发刷新的会话内通过 `local:${seq}` 闭包更新为 `Delivered` 可正常工作，但与 `loadConversation` 并发时存在丢消息与双气泡竞态（已列为 **`B1`**）。
- **慢网连发两条与重发保序**：首条失败次条成功后再重发首条会导致界面与服务端顺序颠倒，且 ViewModel 层未自持串行锁、缺慢网连发单测（已列为 **`B2`**）。
- **冷却期间重发失败消息**：生产代码 `SmsConversationViewModel.kt:155` 已接入 `checkCooldownBlocked`（并与 `DefaultSmsRepository.sendSms` 双重守卫），但单测 `0` 覆盖且 `FakeSmsRepository` 未建模冷却，删掉 `:155` 仍全绿（已列为 **`B2`**）。
- **15 秒冷却期间可继续输入 & 清空二次确认**：
  - `SmsSolidInputBar`（`SmsConversationScreen.kt:299-312`）在 `cooldownSeconds > 0` 时仅禁用发送圆钮（切换为 `HiRollingNumber` 倒计时 + `{N} 秒后可发送` 无障碍描述），`OutlinedTextField` 始终可编辑（已由 UI 单测与 `MockWebServer` 虚拟时钟 `14,001ms -> 1s`、`+999ms -> 0s` 向上取整覆盖）。
  - 清空二次确认框（`MessagesListScreen.kt:375-383`）标题 `清空与 {用户名} 的全部短消息？`、副行 `清空后无法恢复。`、按钮 `取消`/`清空` 逐字匹配 `copy.md:140`，取消零请求、确认发 `GET pm.php?action=del&uid=...&filter=privatepm` 已实测覆盖。

---

### 卡片重点 2：提醒按单条点击标已读与独立「全部已读」
- **核查结论：通过**。
- 进入提醒列表仅调 `notifyRepository.getNotifications(priority)`，绝不自动清零未读；点击单条提醒才调 `viewModel.onNotificationOpened(item)` → `notifyRepository.markNotificationRead(entry.key)` 并通过 `dispatchNotificationTarget`（`MessagesListScreen.kt:421-426`）携带精确 `targetPid` 触发 `onOpenThread`；顶栏 `全部已读` 独立调用 `notifyRepository.markAllNotificationsRead()` 并在成功后弹出 `已全部标为已读` Snackbar。`MessagesScreenBehaviorTest` 与 `MessagesViewModelMockWebServerTest` 双层覆盖扎实。

---

### 卡片重点 3：短消息链接/图片 Scheme 白名单与反例测试
- **核查结论：通过（三层防御严密）**。
- **链接白名单（`sanitizeMessageLinkUrl`，`MessageUrlSanitizer.kt:21-31`）**：解析层、渲染层（`buildSmsAnnotatedText` `:247`，显式绑定非空 `LinkInteractionListener` 阻断回退裸 `LocalUriHandler`）与点击层（`SmsConversationScreen.kt:64`）三层强校验；拦截空白/控制字符/`\u200B`/`\uFEFF`，仅放行合法非空 host 的 `http`/`https` 与纯邮箱格式 `mailto:`（拒绝 `?bcc=` 等查询参数注入）。
- **图片与头像白名单（`sanitizeMessageImageUrl`，`MessageUrlSanitizer.kt:34-35`）**：气泡配图（`:105` + `SmsConversationScreen.kt:214`）与会话/提醒/气泡圆形头像 `MessageAvatar`（`MessagesListScreen.kt:389`）均强制只放行 `http`/`https`，明确拒绝 `mailto:` 及一切危险 scheme；`<script>/<style>/<iframe>/<object>/<embed>` 在 Jsoup 解析期直接移除。
- **反例单测（`MessageUrlSanitizerTest.kt`）**：覆盖 `javascript:`/`intent:`/`file:`/`content:`/`data:`/`vbscript:`/`blob:` 及大小写、前导空白、换行拆分（`java\nscript:`）、零宽/控制字符、非法 `mailto:`、相对路径共 50+ 载荷，同时断言纯函数返回 `null`、`LinkAnnotation` 数为 `0`、真实点击下 `UriHandler` 与 `ImageLoader` 调用次数均为 `0`。

---

### 卡片重点 4：`R-D9 S2` 系统通知文案归口与零越界检查
- **核查结论：通过**。
- `docs/design/copy.md:152-159` 新增 `## 系统通知` 章节，`res/values/strings_notification.xml` 完整承接 9 条文案，`NotificationPoster.kt` 已移除全部硬编码中文常量并经 `Context.getString` 读取；`CopyTerminologyScanTest`（0 新增白名单）与 `NotificationPollingWorkerTest`（12/12）全部通过，零禁用词组。
- `git diff --name-status 89f8e37...b40fb4a` 严格落在 `E8.md` 授权文件清单内，零触碰 `feature/app/*`、`di/AppShellBindingModule.kt`、`feature/common/*`、`MotionTokenScanTest` 及共享构建配置。

---

### 卡片重点 5：新增 12 张 Roborazzi 截图逐张目视检查与既有快照 0 漂移
- **既有快照零漂移**：`git diff --name-status 89f8e37...b40fb4a -- '*.png'` 仅含 12 张新增 `A .../messages_*.png`，既有全仓快照 `0` 修改，CI `verifyRoborazziDebug` 全绿。
- **12 张新增截图逐张 `read_file` 目视复验结果**：
  1. `messages_sms_list_w411_light.png` / `messages_sms_list_w411_dark.png`：纯色顶栏 `消息` + 双分段胶囊（含未读红点），未读项蓝点加粗与已读项弱化层次清晰（深色 `0.74f` alpha 可读性良好），右侧时间与 `⋮` 无挤压重叠，底部 `没有更多了` 居中干净。
  2. `messages_sms_list_w749_light.png` / `messages_sms_list_w749_dark.png`：紧凑三栏（左 `64dp` 图标轨带独立数字角标 `2`/`2` 且首屏露出 `D`/`BS`/`极` 3 个关注版块；中栏隐藏分段标签直显 `短消息`；右栏居中显示 `选一条短消息开始查看`）。注：`274dp` 窄中栏内右侧 `48dp` `⋮` 按钮使较长用户名缩略为 `coffee_n...`（无重叠裁切，优化建议见 `S4`）。
  3. `messages_sms_list_w1280_light.png` / `messages_sms_list_w1280_dark.png`：完整三栏（`220 | 380 | 680dp`），左栏展开高亮 `短消息 2`，中栏 `linus_cn` 选中底色柔和，右栏对话内 `:emoji` 表情（含酷猴深浅色变体）、下划线链接 `Discovery 主帖`、主客气泡对比度与底部纯色输入条排版规整。
  4. `messages_conversation_w411_light.png` / `messages_conversation_w411_dark.png`：纯色顶栏含返回箭头与 `linus_cn`，失败气泡左侧红色圆形感叹号醒目（深浅色对比度充足），底部输入条展示编辑中文本 `冷却中继续编辑下一句` 与右侧禁用态倒计时圆钮 `12`，垂直居中对齐准确。
  5. `messages_notify_w411_light.png` / `messages_notify_w411_dark.png`：顶栏右侧 `全部已读` 清晰可辨，引用提醒带 `3dp` 主题色竖条引用块，系统提醒带铃铛圆标，未读/已读对比鲜明，无文字截断。
  6. `messages_empty_w411_light.png`：无未读时分段标签不显示多余红点，正文居中 `暂无短消息`。
  7. `messages_error_w933_dark.png`：`933×696dp` 三栏深色未登录态，左栏因高 `< 760dp` 自动收起 `我的` 分组，中栏顶栏 `全部已读` 置灰禁用、正文居中展示 `需要登录才能查看` + `登录` 按钮，右栏展示 `选一条提醒查看对应帖子`，文案逐字合规。

---

## 三、非阻断改进建议（Suggestions）

1. **`S1` — 按第 二.1 节清单精简冗余生产代码（预计可删 `260–310` 行）**：优先删去非本卡范围且硬编码高度的 `SmsInlineEmojiPicker`、复用 `InlineEmojiContent.kt` 与 `UiErrorMapper.map(Throwable)`、移除 `MessagesListScreen.kt:329` 死代码 `Jsoup`，将生产代码净增压回 `~1,400` 行区间（注意：若修改涉及对话列表截图顶栏菜单或输入条左侧表情图标，按 `DISPATCH.md §2` 重新录制 `MessagesScreenshotTest`）。
2. **`S2` — 系统通知锁屏隐私等级与超长摘要截断兜底（`NotificationPoster.kt:72-81, 129-131`）**：建议在 `NotificationCompat.Builder` 上显式设置 `.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` 并附带隐藏发件人与摘要的 `.setPublicVersion(...)`（标题 `HiPDA 论坛通知`、正文 `您有 1 条新的短消息`），同时对 `singleSms.summary` 折叠换行空白并限制最大字符数（如 `80` 字），防止极端长摘要穿透至锁屏或穿戴端。
3. **`S3` — 接入 `HiContentState` 守住被动加载 `150ms` 防闪延迟（`MessagesListScreen.kt:240-256`）**：当前手写 `MessagePaneStateBox` 在 `isLoading` 首帧 0ms 直出骨架屏，建议改为复用 `ui/designsystem/state/HiContentState.kt` 以对齐 `product-design.md §6.2` `loadingShowDelay (150ms)`。
4. **`S4` — `< 300dp` 窄中栏短消息行释放右侧 `48dp` 宽度（`MessagesListScreen.kt:294-316`）**：`SmsConversationRow` 已支持整行长按弹出清空菜单（`:294`），在 `compact`（`< 300dp`，如 `749dp` 紧凑三栏 `274dp` 中栏）下可隐藏右侧常驻 `⋮` 按钮，避免 10 字符用户名被过早截断为 `coffee_n...`。

---

# 独立代码审查报告 R-E8（第 2 轮复审，commit `c65a38b`）

- **复审结论**：**`APPROVED`**
- **审查对象**：`origin/card/E8-messages-and-notify` @ `c65a38ba1a6513612861fd92ae3324d7eaa76bda`（简称 `c65a38b`，已合入 `origin/main` @ `4b6c1f0`）
- **与 `origin/main` 合并校验**：`git merge-tree --write-tree origin/main c65a38b` → `2adb640caf069db2714b8cfc3a7b3de2fa7dbe05`（退出码 `0`，零冲突；`git diff --name-status 4b6c1f0...c65a38b` 共 30 个文件，100% 位于 `E8.md` 授权边界内，零触碰 `feature/app/*`、`feature/common/*`、`di/AppShellBindingModule.kt`、`MotionTokenScanTest` 与非本卡快照）
- **GitHub Actions CI 核实**：[Android CI #45739394059](https://github.com/mailwmj/hipda-next/actions/runs/45739394059)（Job `139640403999`）
  - `headSha`：`c65a38ba1a6513612861fd92ae3324d7eaa76bda`（精确匹配 `c65a38b`）
  - `status / conclusion`：`completed / success`（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 及 APK 产物校验全绿，`Check for Roborazzi diff images` 跳过即 0 像素漂移）
- **生产代码净增复核**：对比 `origin/main` @ `4b6c1f0` 生产代码净增 **`+1,564` 行**（较第 1 轮 `+1,702` 净减 `138` 行；扣除本轮新增并发水位/串行锁/锚点撤销/锁屏通知保护后实际清理冗余代码约 `245` 行，位于 `1,725` 警戒线以内）
- **阻断项（Blocking）**：**0 项**
- **非阻断建议项（Suggestions）**：**1 项**（`R2-S1`）+ **跨卡提醒 1 项**（延续第 1 轮宿主 `NextAppState.kt` 提示）

---

## 一、第 2 轮阻断项（Blocking Issues）

**无（0 项）。** 第 1 轮 `B1`–`B3` 均已在代码与回归单测两层彻底闭合，且未引入新问题。

---

## 二、第 1 轮问题（`B1`–`B3`、`S1`–`S4`）与统一撤销模型逐项核验（含回归变异推演）

### 1. `B1`（乐观发送 `local:*` 与 `loadConversation` 并发水位对齐 + 首屏禁发）— **已修复**
- **代码核实**：
  - 首屏门控：`SmsConversationUiState.canSend`（`SmsConversationViewModel.kt:45`）增加 `hasLoadedOnce && !isLoading`，`sendMessage()`（`:136`）同步拦截保留 `inputText` 草稿。
  - 水位对齐与在途吸收：`reconcileServerAndLocalBubbles`（`:297-314`）先计算历史 `prevServer` 尾段与新 `incoming` 前缀的最大重叠水位 `overlap`（若 `incoming` 是更旧的严格前缀快照则保留 `prevServer` 不回退），仅提取 `server.drop(overlap)` 中新增的己方消息经 `normalizeSmsCompareText`（统一纯文本与服务端 HTML/表情码）与本地非失败 `local:*` 按 FIFO 逐一配对消重：
    - 滞后的旧刷新因 `server.drop(overlap)` 为空（即便历史中已有同文 `"ok"` 也处于水位之前），不会误吞刚变为 `Delivered` 的新 `local:*`；
    - 服务端已落库而客户端仍在 `Sending` 的本地气泡被新增服务端消息配对吸收后，其 `id` 记入 `reconciledLocalIds`，待 `dispatchSend` 恢复（`:235-253`）时跳过状态回写，杜绝双份气泡。
- **单测回归有效性验证（`SmsConversationConcurrencyTest.kt:66-198`）**：
  - 若删去 `:45` 或 `:136` 首屏门控，`b1_slowInitialLoadInterleavedWithSend_blocksSendUntilLoadedAndPreservesDraft` 在 `:84` / `:87-88` 立即失败；
  - 若将 `reconcileServerAndLocalBubbles` 退回第 1 轮逻辑，`b1_slowRefreshInterleavedWithSend_preservesConfirmedLocalAndDeduplicatesInFlightSend` 在路径 ①（`:159`，同文 `"ok"` 被滞后刷新吞成 2 条）与路径 ②（`:188`，在途 `Sending` 重复成 5 条）均立即变红。

### 2. `B2`（ViewModel 显式串行保序 + 失败队尾重发 + 冷却重发拦截 + 假仓库真实冷却）— **已修复**
- **代码核实**：
  - `dispatchSend`（`SmsConversationViewModel.kt:230-255`）使用 `CoroutineStart.UNDISPATCHED` + `sendMutex.withLock` 在调用线程按触发顺序同步入 FIFO 锁队列，彻底消除跨仓库实现的并发乱序；
  - `resendMessage`（`:145-154`）先过 `checkCooldownBlocked` 拦截（冷却期内返回，不改状态、不挪位），通过冷却后将目标气泡移至 `messages` 队尾并递增 `scrollToBottomEventId`，保证重发成功后的界面顺序与服务端落库时序一致；
  - `FakeSmsRepository.sendSms`（`MessagesScreenBehaviorTest.kt:575-582`）补齐 `sendCallCount`、真实冷却拦截返回 `CooldownActive` 及 `Sent` 后自动挂载 `15_000ms` 冷却。
- **单测回归有效性验证**：
  - 若移除 `sendMutex`，`b2_slowNetworkSerialSend_m1FailsM2SucceedsThenResendM1_finalOrderIsM2ThenM1`（`SmsConversationConcurrencyTest.kt:242-243`）因 `maxConcurrentSends == 2` / `sentOrder` 越界立即变红；若移除队尾重排，`:263` 断言 `listOf("M2", "M1")` 立即变红；
  - 若删去 `SmsConversationViewModel.kt:149`（`if (checkCooldownBlocked(cur.cooldownSeconds)) return`），`b2_resendDuringCooldown_blockedWithZeroRepoCallsAndOrderPreserved`（`:298-299`）与 UI 测试 `smsConversation_resendBlockedDuringCooldown_keepsFailedAndShowsCooldownSnackbar`（`MessagesScreenBehaviorTest.kt:250-252`）均因 `sendCallCount` 变为 `3` 及顺序改变立即变红。

### 3. 统一撤销模型（独立令牌 + 前驱邻居锚点 + 新操作关旧 Snackbar 视作确认 + 连续删两条测试）— **已核实合规**
- **令牌与锚点（不用下标）**：`DeletedSmsUndoToken(val tokenId: Long, val item: SmsBubbleUiItem, val anchorPrevId: String?)`（`SmsConversationViewModel.kt:35`）不含任何下标字段；删除时记录唯一自增 `tokenId` 与前驱气泡 `id`（首项为 `null`），并更新 `activeUndoTokenId = nextToken.tokenId`（`:161-163`），旧令牌即时作废视为已确认（`clearConversation` `:192` 与切号/退登 `resetGeneration` `:201` 同步清空 `activeUndoTokenId`）。
- **立即关闭上一条 Snackbar**：`SmsConversationScreen.kt:64, 121-128, 142` 维护 `undoSnackbarJob`，新删除或确认清空会话时立即 `undoSnackbarJob?.cancel()`，触发 Material3 `SnackbarHostState.showSnackbar` 的 `suspendCancellableCoroutine` 取消与 `finally { currentSnackbarData = null }`，旧 Snackbar 当帧关闭且绝不触发旧 `onAction`。
- **仅恢复本令牌并按锚点放回**：`restoreDeletedMessage`（`:169-181`）先校验 `activeUndoTokenId == token.tokenId` 并一次性置 `null`；恢复位置按 `anchorPrevId` 查找前驱当前位置 `+ 1`，同时通过 `maxOf(..., serverEnd)` 钳制在服务端消息之后（前驱不存在时落队尾）。
- **「连续删两条再撤销」双层测试与变异验证**：
  - ViewModel 层 `b2_consecutiveDeleteTwoFailedThenUndo_restoresOnlySecondAtAnchorAndInvalidatesFirstToken`（`SmsConversationConcurrencyTest.kt:345-388`）：在 `[历史消息, 失败1, 失败2, 尾条成功]` 中连删 `失败1`、`失败2`，验证调 `restoreDeletedMessage(token1)` 无效、调 `restoreDeletedMessage(token2)` 仅恢复 `失败2` 且精确落在 `历史消息` 与 `尾条成功` 之间，二次重复调用幂等；另有 `b2_restoreDeletedFailedBubble_afterRefreshClampsAfterServerMessages`（`:309-341`）验证刷新插入对端新消息后的锚点钳制。
  - UI 层 `smsConversation_consecutiveDeleteTwoFailedThenUndo_dismissesFirstSnackbarAndRestoresOnlySecondInPlace`（`MessagesScreenBehaviorTest.kt:259-326`）：不推进 10 秒超时直接连删两条并点当前 Snackbar `撤销`——若删去 `:122` 的 `undoSnackbarJob?.cancel()`，新 Snackbar 会阻塞在旧 Snackbar 锁后，点击 `撤销` 会命中已作废的 `token1` 导致 `失败第二条` 无法恢复并在 `:318` 立即变红。

### 4. `B3`（跨账号切号/退登私密状态隔离 + 纯 `uid` 恢复回填用户名）— **已修复**
- `MessagesPaneRendererImpl.kt:63-68` 将当前登录 `currentAccount?.uid.orEmpty()` 纳入 `SmsConversationViewModel` 的 `viewModel(key = ...)`；`SmsConversationViewModel` 在 `uidChanged`（`:105` → `resetForLoad` `:203-209`）与退登（`:104` → `applySignedOut` `:211-217`）中均立即清空 `messages`、`inputText`、`reconciledLocalIds` 与 `activeUndoTokenId`；`loadConversation`（`:272-276`）在 `peerUsername` 为空时从首个 `!it.isMine` 消息回填 `author`。`b3_switchAccountAndLogout_clearsMessagesAndDraft_andBackfillsPeerUsername`（`:392-459`）完整覆盖切号后网络失败不残留 A 消息/草稿、退登清草稿及纯 `uid` 回填用户名。

### 5. `S1`–`S4` 与视觉截图复验 — **全部落实且无新问题**
- **`S1`**：已移除非本卡范围的 216dp 自建表情选择器、`SmsTextStyleFlags`、`escapeHtmlText`、UI 层死代码 `Jsoup`、本地假分页、`ConversationCleared` 空事件及重复错误/配色工具函数，复用 `InlineEmojiContent.kt`、`ThreadListRow.kt` 与 `UiErrorMapper.map(Throwable)`；新增 `parseSmsBubblePlainText` 使本地气泡免去 HTML 转义往返，更简洁安全。
- **`S2`**：`AndroidNotificationPoster`（`NotificationPoster.kt:72-95, 176-180`）已补 `VISIBILITY_PRIVATE` + 脱敏 `publicVersion`（标题 `HiPDA 论坛通知`、正文 `您有 1 条新的短消息`）并将单条摘要折叠空白截断至 `≤ 80` 字，`s2_notificationPoster_setsPrivateVisibilityPublicVersionAndTruncatesLongSummary` 实测通过。
- **`S3`**：`MessagePaneStateBox`（`MessagesListScreen.kt:218-240`）已委托 `HiContentState(passive = true)`，`s3_messagesList_hiContentStateDelaysSkeletonBy150msOnFastPassiveLoad` 实测 80ms 内快响不闪屏。
- **`S4` & 6 张更新截图逐张目视核验**：
  - `messages_sms_list_w749_light.png` / `messages_sms_list_w749_dark.png`：`274dp` 窄中栏隐藏行尾 `⋮` 后，`coffee_note` 等全部用户名与右侧时间戳完整显示、零截断，长按整行仍弹清空菜单（已配 UI 单测 `:398-436`）；
  - `messages_conversation_w411_light.png` / `messages_conversation_w411_dark.png` 与 `messages_sms_list_w1280_light.png` / `messages_sms_list_w1280_dark.png`：移除输入条左侧自建表情按钮后，输入框与右侧发送/倒计时圆钮（`12`）水平留白匀称、垂直居中准确，气泡内 `:emoji` 深浅色表情、安全外链与失败红色感叹号清晰无变化；其余 6 张本卡截图及全仓既有快照 `0` 改动。

---

## 三、第 2 轮非阻断建议与跨卡提醒（Suggestions）

1. **`R2-S1`（极轻微交互细节，`SmsConversationScreen.kt:68-77`）**：当删除失败气泡的 10 秒 `已删除 | 撤销` Snackbar 正在显示时，若用户触发 `CooldownBlocked` 或 `ShowError` 事件，可在调用 `snackbarController.show(...)` 前顺手执行 `undoSnackbarJob?.cancel()`，让冷却/错误提示立即顶替显示而无需排队等待上一条撤销提示自然超时（不影响功能正确性，后续顺手改即可）。
2. **跨卡提醒（宿主 `feature/app/NextAppState.kt:91, 95`，非本卡修改范围）**：本卡已支持 `SmsConversationArgs(uid, username = "")` 加载后自动回填对端用户名；后续宿主收口卡只需在 `NextAppStateSaver` 中去掉 `sms?.username` 与下钻 `SmsConversation` 的 `username`（仅存 `uid`），即可完成宿主 `SavedState` 端到端脱敏闭环。
