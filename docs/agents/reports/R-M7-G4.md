# R-M7-G4 独立代码审查报告

- **审查结论**：**`APPROVED`（`APPROVE`）**（0 阻断项，3 项非阻断建议）
- **审查对象**：`origin/card/M7-G4-messages-compose-copy` @ `6df20f35f009d95eab27de027a1f6b43a46863ce`（`6df20f3`）
- **基线提交**：`origin/main` @ `9338529070a9df6c5feeed5bb281ffba411abf35`（`9338529`）
- **CI 核验**：[Android CI #45771792019](https://github.com/mailwmj/hipda-next/actions/runs/45771792019)
  - `headSha == 6df20f35f009d95eab27de027a1f6b43a46863ce`，`status == completed`，`conclusion == success`；
  - 已核实全绿通过：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`:hipda:testDebugUnitTest` + `:core-parser:test -Proborazzi.test.verify=true`，Roborazzi diff 检查与上传因 0 差异自动 `skipped`）、`Verify APK outputs`（`aapt2 dump badging` 校验 debug/release 包名与构建产物）。
- **生产代码规模**：5 个生产文件 `+327 / -21`，净增 **306 行**（Kotlin `+294` 行 + `strings_messages.xml` `+12` 行；位于卡片估计 `180–280` 行合理区间边缘，显著低于 `420` 行 1.5× 警戒线）；全部使用标准 Compose 组件（`AlertDialog` / `OutlinedTextField` / `Button` / `AnimatedContent` / `DropdownMenu` / `combinedClickable`），0 手写 `Layout` / `Modifier.Node` / 自定义状态机，0 测试专用语义键/钩子/CompositionLocal，0 毛玻璃表面。
- **只读纪律核验**：全程未在 `/home/mir/Documents/hipda-next` 执行 checkout、写文件、提交、推送或编译，`git status` 保持干净。

---

## 1. 负责人 4 项补充核查逐项详答

### (1) 新建短消息行为与旧版 `action_send_sms` 逐项对照（入口、校验、冷却、成功后跳转/刷新）

> **旧版类归属说明**：旧版中 `SmsFragment.java` 实为**单人对话详情页**（仅含 `menu_sms_detail.xml` 的 `action_clear_sms` 与底部对已知 `mUid` 发消息的输入栏 `CountdownButton`）；顶栏主动新建短消息菜单项 `R.id.action_send_sms`（`发送短消息`）在旧版位于**短消息会话列表 `SimpleListFragment.java:171-177,232-234`**（`menu_sms_list.xml:5-8`）及用户资料页 `UserinfoFragment.java:220-248`（`menu_userinfo.xml:5-8`），二者共同调用基类 **`BaseFragment.showSendSmsDialog(uid, username, listener)`**（`BaseFragment.java:163-246`）与 **`PostSmsAsyncTask.java:23-150`**。下表将新版 `M7-G4` 与旧版列表页 `action_send_sms`（及 `SmsFragment` 冷却机制）逐项对照：

| 维度 | 旧版实现（`SimpleListFragment` + `BaseFragment.showSendSmsDialog` + `PostSmsAsyncTask` + `SmsFragment`） | 新版实现（`MessagesListScreen` + `NewSmsDialog` + `MessagesListViewModel` + `DefaultSmsRepository`） | 差异评估 |
|---|---|---|---|
| **入口位置与可见态** | `SimpleListFragment.java:171-180`：仅 `mType == TYPE_SMS` 加载 `menu_sms_list.xml`（`action_send_sms`，`gmd_insert_comment` 图标，标题 `发送短消息`）；`TYPE_THREAD_NOTIFY`（提醒列表）不显示。 | `MessagesListScreen.kt:142-154`：仅 `activeEntry != ShellSidebarEntry.Notify`（单栏 `短消息` 标签、宽屏侧栏 `短消息` 入口及空列表态）在顶栏右侧渲染 `48×48dp` `IconButton`（`Icons.Rounded.Edit`，无障碍描述 `发送短消息`）；切到 `提醒` 时切换为 `全部已读` 按钮。 | **对齐且交互更清晰**：图标升级为 Material 3 `Icons.Rounded.Edit`（铅笔新建语义），`Sms` 与 `Notify` 严格互斥。 |
| **未登录前置拦截** | **无前置拦截**：未登录点击仍直接弹出输入 `AlertDialog`，待用户输入完点「发送」请求 `pm.php?daterange=1&uid=` 拿不到 `formhash` 才报 `"无法获取发送凭据"`（`PostSmsAsyncTask.java:54-56`）。 | **前置拦截**：`MessagesListViewModel.onNewSmsClick()`（`:139-142`）在 `!isSignedIn` 时不弹窗，直接发 `RequireLogin` 事件，由 `MessagesListScreen.kt:68-69` 在 `ContentArea` 弹 Snackbar `登录后即可操作` + `登录` 按钮（对齐 `copy.md:85`）。 | **正向改进**：避免未登录用户白填内容后提交才失败；切号/退登时（`:128-135`）还会自动关闭弹窗并清空草稿防串号。 |
| **输入非空校验** | `BaseFragment.java:181-246`：用双 `TextWatcher` 判断原始文本是否 `TextUtils.isEmpty`（未 `trim`）来切换 `BUTTON_POSITIVE` 启用态；若输入纯空格则按钮可点，点击后 `trim()` 为空弹系统 `UIUtils.toast("请填写收件人")` / `UIUtils.toast("请填写内容")` 并保留弹窗。 | `MessagesListViewModel.kt:160-164`、`NewSmsDialog.kt:59-74`：非发送/非冷却态下 `发送` 按钮可点，点击时对 `recipient.trim()` 与 `body.trim()` 校验；为空或纯空白时原地置 `OutlinedTextField` `isError = true` 并显示字段下方红字 `请输入收件人` / `请输入内容`（输入修改即清错），0 次仓库/网络请求。 | **有意交互升级（对齐 `M7-G4.md:18` 与 `copy.md:88`）**：以字段内联校验替代旧版“置灰不可点 + 纯空格漏网弹 Toast”。 |
| **15 秒发送冷却** | **旧版列表新建弹窗漏接冷却**：`PostSmsAsyncTask.java:25-26,108,117` 用进程静态变量 `LAST_SMS_TIME` 计 15 秒，仅对话详情页 `SmsFragment.java:298-300,338` 的 `CountdownButton` 与重发菜单接入了倒计时；**`SimpleListFragment` 的 `showSendSmsDialog` 完全未读 `getWaitTimeToSendSms()`**，冷却期内无倒计时且允许直接提交给服务器报错，静态时间戳切号亦不清零。 | **全链路统一共享 15 秒冷却**：`MessagesListViewModel.kt:111-121,165-166,178-181` 与对话页共享按账号隔离的 `SmsRepository.cooldownUntilMs`（`DefaultSmsRepository.kt:69-70,244,311-321`）。冷却中 `NewSmsDialog.kt:40-49,97-111` 的 `发送` 按钮原地切换为禁用态 `HiRollingNumber` 倒计时（无障碍 `{N} 秒后可发送`），同时保持 `收件人` 与 `内容` 可继续编辑，ViewModel 与 Repository 双重拦截重发（0 网络请求）。 | **修复旧版遗漏**：补齐了列表新建弹窗缺失的客户端 15 秒倒计时与账号级冷却共享。 |
| **发送中反馈与失败保输入** | 发送中叠加第二层全屏阻塞 `HiProgressDialog("正在发送...")`（`SimpleListFragment.java:408`）；失败时 `dismissError(message)` 短暂提示后消失，原 `AlertDialog` 虽保留输入，但窗内无持久错误文案，也无重登/重过盾入口。 | 点击 `发送` 0ms 按钮内切 `HiLoadingSpinner`（`正在发送…`），锁定输入与关闭并留在单层 `NewSmsDialog` 内（`:52-53,96-109`）；底层复用 `smsRepository.sendSms(uid = null, username = recipient, message = body)`（同样先 `GET pm.php?daterange=1&uid=` 取 `formhash`，再 GBK `POST pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1` 提交 `msgto=`）。失败时留窗保留 `recipient`/`body`，窗内持久显示原因（服务端拒绝原文 / `网络连接失败` / 登录失效附 `登录` 按钮 / 安全验证失败下次点发送自动升 `UserExplicit`）。 | **正向改进**：消除双层阻塞弹窗，补齐错误原因持久可见与登录/Cloudflare 恢复闭环，且 ViewModel 持有状态旋转不丢输入。 |
| **成功后跳转 / 刷新** | `SimpleListFragment.java:413-418`：`mSmsPostProgressDialog.dismiss("短消息发送成功.")` → `dialog.dismiss()` **关闭弹窗、停留在短消息会话列表页（不跳转 `SmsFragment`）** → 调 `onRefresh()` 重拉第 1 页 `pm.php?filter=privatepm`。（注：`UserinfoFragment:360-365` 成功后仅关窗不跳转也不刷列表。） | `MessagesListViewModel.kt:174-177`：`composeState` 重置关闭对话框，**同样停留在短消息列表页（不主动跳转对话详情页）**，立即调用 `loadTab(ShellSidebarEntry.Sms, isRefresh = smsState.value.items.isNotEmpty(), ...)` 刷新 `pm.php?filter=privatepm` 会话列表（非空列表走下拉刷新态，空列表走加载态拉出新会话）。 | **跳转/刷新策略与旧版 100% 一致**（唯一小差异是不再弹多余的 `"短消息发送成功."` Toast，因关窗+列表刷新已是直接结果反馈，符合 `copy.md` 规范；且 Discuz 按 `msgto` 发送的成功 XML 不返回对端 `uid`，必须刷列表获取 `uid`，故不直跳详情页完全合理）。 |

---

### (2) 复制正文是否只复制纯文本、不含 HTML / 内部链接参数

**核查结论：严格只复制纯文本，100% 不含任何 HTML 标签、剪贴板 HTML MIME、危险协议、站内链接查询参数或内部渲染标记。**

逐层代码审计证据如下：
1. **系统剪贴板写入通道唯一且纯粹（`SmsConversationScreen.kt:183-187`）**：
   - `copySmsBubblePlainTextToClipboard` 仅调用 `ClipData.newPlainText(label, plainText)`（`label` 为 `"复制"`），绝不调用 `ClipData.newHtmlText`、`ClipData.newRawUri` 或 `ClipData.newIntent`。
   - `MessagesComposeAndCopyTest.kt:408-418` 对真实 Robolectric `ClipboardManager.primaryClip` 逐项断言：`hasMimeType(MIMETYPE_TEXT_PLAIN) == true`、`hasMimeType(MIMETYPE_TEXT_HTML) == false`、`item.htmlText == null`、`item.uri == null`、`item.intent == null`。
2. **HTML 剥离、实体解码与危险节点清除（`MessageUrlSanitizer.kt:49-90` + `SmsConversationScreen.kt:167-180`）**：
   - 解析器源头（`SmsParser.kt:92,121`）：`contentHtml` 仅取 `li.s_clear` 下 `div.summary.html()`，相邻兄弟 `<span class="action"><a href="pm.php?action=new&pmid=...">转发</a></span>`（`sms_detail.html:101-103`）从源头即不在 `contentHtml` 内。
   - `parseSmsBubbleContent` 先通过 `Jsoup.parseBodyFragment(contentHtml)` 执行 `select("script, style, iframe, object, embed").remove()` 彻底移除脚本与嵌入节点及其内部文本，再经 `DiscuzTextDecoder.decodeDoubleEscapedEntities` 解码 HTML 实体与 Discuz 二次转义补充平面 Emoji（如 `&amp;#128512;` → `😀`），`<br>` / `<p>` / `<div>` / `<li>` / `<blockquote>` 转为纯文本换行 `\n`。
3. **不泄漏任何链接 `href` / 内部链接参数 / 内部渲染 ID**：
   - **超链接 `<a href="...">` 处理（`MessageUrlSanitizer.kt:67-75` + `SmsConversationScreen.kt:174`）**：
     - 对 `<a href="https://www.4d4y.com/forum/viewthread.php?tid=2602293&extra=page%3D1">Discovery 主帖</a>`、`redirect.php?goto=findpost&ptid=...&pid=...`、`space.php?uid=...` 乃至恶意 `<a href="javascript:alert(1)">纯标签</a>`，`extractSmsBubblePlainText` 在 `is SmsTextPart.Link -> append(part.label)` 分支**只提取用户可见锚文本 `part.label`**（如 `"Discovery 主帖"`、`"纯标签"`），**完全丢弃 `href` 属性及其中的 `tid` / `ptid` / `pid` / `uid` / `extra` / `formhash` 等站内参数与 `javascript:` 等危险协议**（仅当服务端 `<a>` 标签可见文本本身为空且 `href` 通过 `SafeLinkPolicy` `http`/`https`/`mailto` 校验时，才与屏幕可见渲染一致回退为 `safeUrl`；若 `<a>` 内嵌 `<img>` 且无文字则递归子图，不输出外层 `href`）。
   - **不经过 Compose `AnnotatedString` 复制**：`SmsBubbleBody` 渲染时在 `AnnotatedString` 中注入了 `LinkAnnotation.Url` 与内部占位 ID `appendInlineContent("sms_emoji:...", desc)`（`SmsConversationScreen.kt:288,295-297`），但 `extractSmsBubblePlainText` 直接从领域结构 `SmsBubbleContent` 构建纯字符串，完全绕开 `AnnotatedString` 内部注解、`U+FFFC` 占位符及 `sms_emoji:*` 内部键。
   - **表情与纯图气泡处理（`SmsConversationScreen.kt:170,175`）**：仅提取 `SmsBubbleSegment.Text`（自动忽略 `SmsBubbleSegment.Image` 的图片 URL），并将 `default` / `coolmonkey` / `grapeman` 三套论坛表情还原为规范代码（如 `:lol:`、`{:2_41:}`、`{:3_64:}`）；空内容、纯 `<br/>` 或纯图片气泡提取结果为空串，安全返回 `false`、不崩溃、不覆盖剪贴板已有内容且不误弹 `内容已复制`。

---

### (3) 7 张 `messages_*.png` 逐张目视审查与仅顶栏按钮变动核验

1. **快照变更范围与非授权快照 0 漂移**：
   - `git diff --name-status 9338529...6df20f35f009d95eab27de027a1f6b43a46863ce` 中截图改动严格仅限 `_reports/M7-G4.md` 列出的 **7 张短消息列表态 PNG**。
   - 消息模块其余 5 张快照（`messages_notify_w411_light.png`、`messages_notify_w411_dark.png`、`messages_conversation_w411_light.png`、`messages_conversation_w411_dark.png`、`messages_error_w933_dark.png`）及全仓所有其他模块快照严格 **0 漂移**。
2. **像素级差分与逐张 `read_file` 目视结果（7/7 通过）**：
   - 将基线 `9338529` 与本提交 `6df20f3` 的 7 张 PNG 逐像素比对并逐张调用 `read_file` 目视检查，**7 张图的像素变化数全部精确等于 `424 px`**，且差异外接矩形 `100%` 落在 `56dp`（`112px @ xhdpi`）顶栏右侧的 `34×34px`（`y ∈ [39, 72]`）铅笔图标字形区域内，顶栏以外的标签栏、列表行、分割线、侧栏与右栏详情 **0 像素变化**：

| # | 快照文件 | 尺寸 (px) | 差异像素与坐标框 | 目视审查结论 |
|---:|---|---|---|---|
| 1 | `messages_sms_list_w411_light.png` | `822×1782` | `424 px`，`(745, 39)–(778, 72)` | 411dp 单栏浅色：顶栏左「消息」、右 `Icons.Rounded.Edit` 深色图标清晰锐利、垂直居中，与下方 `短消息 • \| 提醒 •` 分段控件留白充裕，无裁切或挤压。 |
| 2 | `messages_sms_list_w411_dark.png` | `822×1782` | `424 px`，`(745, 39)–(778, 72)` | 411dp 单栏深色：顶栏右上铅笔图标采用 `onSurface` 浅色着色，暗底对比度充足，深浅色视觉完全一致。 |
| 3 | `messages_sms_list_w749_light.png` | `1498×1118` | `424 px`，`(599, 39)–(632, 72)` | 749dp 折叠屏双栏浅色（含左侧 `NavigationRail`）：中栏顶栏左「短消息」、右铅笔图标收束在分栏竖线内侧，右栏空态 `选一条短消息开始查看` 零变动。 |
| 4 | `messages_sms_list_w749_dark.png` | `1498×1118` | `424 px`，`(599, 39)–(632, 72)` | 749dp 折叠屏双栏深色：中栏顶栏「短消息」与右上铅笔图标对比度清晰，角标与分栏线对齐无误。 |
| 5 | `messages_sms_list_w1280_light.png` | `2560×1600` | `424 px`，`(1123, 39)–(1156, 72)` | 1280dp 平板三栏浅色：展开左侧栏 + 中栏「短消息」顶栏右上铅笔图标 + 右栏 `linus_cn` 对话顶栏 `⋮` 各自独立归位，无任何重叠或错位。 |
| 6 | `messages_sms_list_w1280_dark.png` | `2560×1600` | `424 px`，`(1123, 39)–(1156, 72)` | 1280dp 平板三栏深色：三栏深色层级分明，中栏顶栏新增铅笔按钮清晰自然，右栏气泡、表情与链接渲染完好。 |
| 7 | `messages_empty_w411_light.png` | `822×1782` | `424 px`，`(745, 39)–(778, 72)` | 411dp 短消息空态浅色：因渲染 `ShellSidebarEntry.Sms` 空列表，顶栏右侧合理展示新建短消息按钮（无历史会话时亦可主动发首条短消息），中央 `暂无短消息` 居中无偏移。 |

---

### (4) `git merge-tree` 与最新 `origin/main`（及并行分支）零冲突核验

执行 `git fetch origin` 后实测：
- **与最新 `origin/main`（`9338529070a9df6c5feeed5bb281ffba411abf35`）**：
  - `git merge-tree --write-tree origin/main 6df20f35f009d95eab27de027a1f6b43a46863ce` → **`exit=0`**，输出树 `d45dce047a9bf1acc8184fd396a66353828a0509`（纯 fast-forward，**0 冲突**）。
- **与同批在途并行分支交叉核验**：
  - `origin/card/M7-G1-list-topbar-profile`（`e4690ea`）→ **`exit=0`**（树 `22c76758c9986e95b446723758c814e1505c033c`，**0 冲突**）；
  - `origin/card/M7-G2-system-account-viewer-about`（`4b3c1b9`）→ **`exit=0`**（树 `fab430fadd80b9a52e857da82167529845956cc5`，**0 冲突**）；
  - `origin/card/E5-quick-reply-sheet`（`a5c9440`）→ **`exit=0`**（树 `542f5acd37621bdc6fd91adade262653066110c1`，**0 冲突**）。

---

## 2. 通用门禁、术语扫描与测试完备度核验

1. **术语红线与动效扫描**：
   - `hipda/src/main/res/values/strings_messages.xml:23-34` 新增 8 条文案（`发送短消息`、`收件人`、`内容`、`请输入收件人`、`请输入内容`、`登录后即可操作`、`复制`、`内容已复制`），全部逐字取自 `strings.xml:34,50-51` 与 `docs/design/copy.md:24,29-30,35,85,88,140`，**0 出现 `私信` / `站内信` 等 30 个禁用词组**，`CopyTerminologyScanTest` 与 `MotionTokenScanTest` 全绿且白名单 0 修改。
2. **安全白名单**：
   - 本卡未新增任何外部/WebView URL 打开入口；短消息气泡既有链接点击（`SmsConversationScreen.kt:70,286`）与图片加载（`:270`）继续严格经过 `SafeLinkPolicy`（链接仅 `http`/`https`/纯邮箱 `mailto`，图片仅 `http`/`https`），55+ 组危险 scheme 反例单测（`MessageUrlSanitizerTest`）保持全绿；剪贴板复制严格只走 `ClipData.newPlainText`。
3. **撤回即红单测与离线纪律**：
   - `MessagesComposeAndCopyTest.kt`（4 个测试）与 `MessagesViewModelMockWebServerTest.kt:310-404`（新增端到端 GBK `MockWebServer` 测试）完整覆盖：顶栏按钮 `Sms`/`Notify` 互斥与 `48×48dp` 触控尺寸、未登录 Snackbar 拦截、空值/纯空白内联校验 0 网络请求、失败留窗保输入与服务端原因展示、0ms 按钮内 `正在发送…` 进度圈、成功关窗刷新会话列表、15s 冷却倒计时与可编辑、切号清草稿、HTML/表情/危险协议剥离纯文本复制、失败气泡无障碍 `OnLongClick` 复制及与链接点击/失败 `!` 重发删除零冲突；全程使用假替身或本地 `MockWebServer`，0 访问 `4d4y.com`。

---

## 3. 阻断项与非阻断建议

### 阻断项（Blocking）
- **无**。

### 非阻断建议（Non-Blocking）
1. **S1（细微手势边界 — 气泡内纯链接文本上的长按，`SmsConversationScreen.kt:208-213,288`）**：Compose `Text` 的 `LinkAnnotation.Url` 内部由 `TextLinkScope` 在链接字形区域挂载子级 `Clickable`，在 `Main` 阶段优先消费按下事件以保证单击跳链灵敏度；因此若某条气泡正文字面 100% 全是超链接，手指正按在链接文字字形上长按时不会冒泡到父级 `combinedClickable`（长按气泡四周 `12×9dp` 内边距、非链接文字/表情或通过无障碍 `OnLongClick` 均可正常弹出 `复制`，与 `PostFloorCard` 行为一致）。无需阻塞，后续若做局部交互打磨可改用自定义 `pointerInput` 同时识别长按与链接点按。
2. **S2（纯图片气泡长按菜单体验微调，`SmsConversationScreen.kt:196-218`）**：对仅含图片而无任何文字的气泡（`extractSmsBubblePlainText` 为空），当前点 `复制` 会安全返回 `false`（不崩、不污染剪贴板、不误弹提示），但长按仍会弹出 `复制` 菜单项；后续可按 `hasCopyableText` 条件展示菜单项以省去一次空操作点击。
3. **S3（三栏同会话并发刷新体验备注，`MessagesListViewModel.kt:174-177`）**：平板三栏（`1280dp`）下若右栏恰好已打开用户 `X` 的对话页，用户又从中栏顶栏「发送短消息」弹窗向同一用户名 `X` 发送成功，当前会刷新中栏会话列表并同步 15 秒冷却至右栏，但右栏已有消息流需下拉或下次进入时刷新；属极低频路径且行为自洽，仅作备忘。
