# 独立代码审查报告 R-D6：发帖、回复、编辑、删除与图片上传 Repository

- **审查结论**：**APPROVE**（0 项阻断，4 项非阻断建议）
- **审查对象**：`origin/card/D6-post-upload-repo` @ `ce4840e8604ad7a4b68d57ec70b3a654389e6021`
- **比较基线（`{BASE}`）**：`90c026dc370e7edd2f4a44e9dfe823eb9ff9aec2`（`git merge-base origin/main ce4840e8604ad7a4b68d57ec70b3a654389e6021`；同时核对当前 `origin/main` @ `29c9e6fc1b73cf54b9a7c0fd49cbd9012aee3baf` 的 diff 完全一致，`git merge-tree --write-tree` 零冲突）
- **CI 核验**：[Android CI #45645357478](https://github.com/mailwmj/hipda-next/actions/runs/45645357478)
  - `headSha`：`ce4840e8604ad7a4b68d57ec70b3a654389e6021`（精确匹配）
  - 状态与结论：`completed / success`（Job `139314770633` Build, Lint & Unit Tests）
  - 已从原始日志逐项核实执行并通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room Schema 一致性检查、`:core-parser:test`、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）、Debug/Release APK 产物校验
  - 单元测试汇总：`TOTAL: 841 tests, 841 passed, 0 failed, 0 errors, 0 skipped`（其中 `:hipda:testDebugUnitTest` 743/743 通过，D6 新增 `PostRepositoryTest` 16/16、`ImageCompressorTest` 7/7、`ImageUploadRepositoryTest` 7/7，共 **30/30** 全绿）

---

## 一、阻断项（Blockers）

**无**（对照 `docs/agents/DISPATCH.md` §4 与审查卡 `R-D6.md` 全部阻断标准逐条核验通过）。

---

## 二、四项审查重点逐条核验

### 1. 安全红线、边界与五线零冲突守卫（通过）

1. **改动范围核验**（`git diff --name-status 90c026dc370e7edd2f4a44e9dfe823eb9ff9aec2...ce4840e8604ad7a4b68d57ec70b3a654389e6021`）：
   - 共新增 **10 个文件**（`+4485 / -0`），无任何既有文件被修改或删除：
     - 生产代码（7 个文件，2287 行）：
       - `hipda/src/main/java/net/jejer/hipda/data/post/PostModels.kt`（304 行）
       - `hipda/src/main/java/net/jejer/hipda/data/post/PostRepository.kt`（62 行）
       - `hipda/src/main/java/net/jejer/hipda/data/post/DefaultPostRepository.kt`（923 行）
       - `hipda/src/main/java/net/jejer/hipda/data/post/ImageCompressor.kt`（552 行）
       - `hipda/src/main/java/net/jejer/hipda/data/post/ImageUploadRepository.kt`（25 行）
       - `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt`（397 行）
       - `hipda/src/main/java/net/jejer/hipda/di/PostModule.kt`（24 行）
     - 测试代码（3 个文件，2198 行）：
       - `hipda/src/test/java/net/jejer/hipda/data/post/PostRepositoryTest.kt`（1293 行）
       - `hipda/src/test/java/net/jejer/hipda/data/post/ImageCompressorTest.kt`（320 行）
       - `hipda/src/test/java/net/jejer/hipda/data/post/ImageUploadRepositoryTest.kt`（585 行）
2. **禁改区与跨分支零重叠核查**：
   - `data/local/*`、`di/DatabaseModule.kt`、`HiDatabaseDaoTest.kt`、`data/user/*`、`data/threadlist/*`、`data/threaddetail/*`、`data/message/*`、`data/auth/*`（含 `ForumHttpClient.kt`）、`data/network/*`、`data/settings/*`、`data/account/*`、`ui/*`、`src/debug/*`、快照、`core-parser/`、样本文件、旧 Java 代码与所有共享构建/Manifest/CI 文件：**0 改动**；
   - 与已合入 `origin/main` 的 `D4`、`C4d`、`D7`、`D8b` 及在途 `origin/card/D5-thread-detail-repo`、`card/D8a-search-favorite-repo`、`card/D9-notification-polling` 逐分支执行文件交集比对，交集均为空。
3. **离线安全、去旧依赖与无睡眠等待核查**：
   - 全量源码扫描确认 **0 处 `id.zelory.compressor` 引用**（并有 `ImageUploadRepositoryTest.dataPostSources_haveZeroZeloryCompressorReferences` 源码扫描测试常驻守卫）；
   - 全量扫描确认 **0 处 `Thread.sleep`、0 处 `delay(`**；
   - `PostRepositoryTest.setUp()`（`:106-108`）与 `ImageUploadRepositoryTest.setUp()`（`:82-84`）均将 `AppSettings.forumServer` 指向本地 `MockWebServer`，302 `Location` 均为站内相对路径，`LegacyTestSupport.loadSample` 纯读本地脱敏 HTML 样本，测试代码唯一出现的 `4d4y.com` 字符串为 `PostRepositoryTest.kt:384` 小尾巴链接格式纯文本入参（未发起网络请求），100% 离线、绝无向真实站点发起读写操作的路径。

---

### 2. R-B9b S3 Emoji 往返、6 种模式表单与小尾巴红线（通过）

1. **`PrePost` 预取端点与实名绑定拦截**（对照 `PrePostAsyncTask.java:47-83`、`HiUtils.java:130-133`）：
   - `DefaultPostRepository.buildPrePostPath`（`:623-663`）精确覆盖 5 类请求（6 种 `PrePostMode`）：
     - `NewThread`：`post.php?action=newthread&fid={fid}`（校验 `fid > 0`）
     - `ReplyThread`（含 `quickReply = true/false`）：`post.php?action=reply&tid={tid}`
     - `ReplyPost`：`post.php?action=reply&tid={tid}&reppost={pid}`
     - `QuotePost`：`post.php?action=reply&tid={tid}&repquote={pid}`
     - `EditPost`（及 `DeletePost` 自动预取）：`post.php?action=edit&fid={fid}&tid={tid}&pid={pid}&page={safePage}`（修复了旧 `PrePostAsyncTask.java:63` 硬编码 `&page=1` 的问题）
   - `isPostResponseUnauthenticated`（`:508-511`）在 `response.url` 命中 `memcp.php?action=bind` 时短路返回 `false`，防止无 `#umenu` 的实名绑定页误触发静默重登，随后在 `:121-123` 与 `:318-320` 准确返回 `RealNameBindRequired`。
2. **R-B9b S3 Emoji 解码与 GBK 十进制重编码往返闭合**：
   - `decodeNumericEntitiesForEditor`（`:456-457, 477-498`）匹配 `(?:(?i:&amp;)|&)#(?:([0-9]+)|[xX]([0-9a-fA-F]+));`，对 `subject`、`text`、`quoteText`（`:499-503`）同时还原双重转义 `&amp;#128512;` / `&amp;#x1F600;` 与单次数字实体 `&#128512;` / `&#x1F600;` 为真实 Unicode 字符；不触碰 `&amp;`、`&lt;`、`&gt;` 等命名实体与 BBCode，且过滤 `<= 0`、代理区 `0xD800..0xDFFF` 及 `> 0x10FFFF` 非法码点；
   - `buildFinalMessage`（`:773-778`）在拼接 `ReplyPost` / `QuotePost` 的 `prePostInfo.quoteText` 前再次幂等调用 `decodeNumericEntitiesForEditor`，即使调用方直接传入未经 `fetchPrePost` 解码的 `PrePostInfo` 也不会在 `gbkFormBody` 中把 `&#128512;` 二次编码成 `%26amp%3B...` 或残留十六进制实体；
   - 提交经 `ForumHttpClient.postGbkForm` → `gbkFormBody`（`GbkForm.kt:18-73`）统一将 Emoji 与 GB18030 扩展字重编码为十进制 `&#<cp>;` 并做 GBK 百分号编码（`PostRepositoryTest.kt:247-377` 对线字节 `%26%23128512%3B`、`%26%23134071%3B` 及解码后表单键值做了双向断言）。
3. **6 种提交模式端点与表单字段逐项核对**（对照 `PostHelper.java:96-190`）：
   - `NewThread`：`POST post.php?action=newthread&fid={fid}&typeid={typeId}&topicsubmit=yes`（`typeId` 为空时依次回退 `prePostInfo.typeId` 与 `"0"`），附带 `subject`、`attention_add="1"`；
   - `ReplyThread` / `ReplyPost` / `QuotePost`：`POST post.php?action=reply&tid={tid}&replysubmit=yes`；`ReplyPost` / `QuotePost` 在 `noticeAuthor` 非空时附带 `noticeauthor`、`noticeauthormsg`、`noticetrimstr`，正文前拼 `"$decodedQuote\n\n    $body"`；
   - `EditPost` / `DeletePost`：`POST post.php?action=edit&extra=&editsubmit=yes&mod=&fid={fid}&tid={tid}&pid={pid}&page={safePage}`；`DeletePost` 附带 `delete="1"`；`subject` 非空时附带 `subject` 及非空 `typeid`；
   - 全模式公共字段：`formhash`、`posttime`、`wysiwyg="0"`、`usesig="1"`、`message`、多值重复键 `attachnew[][description]` 与 `attachdel[]`；
   - 成功判定（`:313-356`）：`DeletePost` 跳转 `forumdisplay.php` 返回 `Success(tid, threadDeleted = true, redirectedPage = null)`；跳转 `viewthread.php` 或 `redirect.php` 且提取到合法 `tid`/`ptid` 返回 `Success(..., threadDeleted = false, redirectedPage = ...)` 并复用 B8 `ThreadDetailParser.parse` 解析落地页；否则依次提取 `div.alert_error` / `div.alert_info`（剥离 `<a>` 链接）返回 `SiteError`。
4. **小尾巴红线、未插图自动附尾与裸 URL 包装**：
   - **小尾巴红线**：`DeletePost` 在 `buildFinalMessage` 首行（`:750-752`）直接返回原文；`EditPost` 在 `:766` 由 `if (request !is PostSubmitRequest.EditPost)` 严格排除，**编辑与删除在任何设置下绝不拼小尾巴**；其余 4 种模式在 `tailEnabled && tailText.trim().isNotEmpty()` 且末尾未含该尾巴时拼 `"  $tail"`（`tailUrl` 非空自动补 `https://` 包 `[url=...][size=1]...[/size][/url]`，否则 `[size=1]...[/size]`，与 `HiSettingsHelper.java:566-580` 一致）；
   - **未插图自动附尾**（`:533-546`）：按序筛选正文中未出现 `[attachimg]$imgId[/attachimg]` 的新附件 ID 追加在末尾（`DeletePost` 不追加）；
   - **裸 URL 包装**（`:552-621`）：跳过成对 `[tag]...[/tag]` 块（大小写不敏感匹配闭合标签），对未闭合的孤立开标签如 `[url=https://a.com]` 仅复制标签头本身并推进游标（修复了旧 `PostHelper.java:292` 在缺闭合标签时回退替换整段破坏 `[url=...]` 的缺陷），同时保留成对括号（如 Wikipedia `Foo_(bar)`）并剥离句末标点。

---

### 3. 30 秒冷却并发状态机与草稿一致性（通过）

1. **可注入时钟与向上取整**：
   - `computeRemainingCooldownSeconds`（`:465-469`）：`remainingMs <= 0L -> 0`，正数按 `((remainingMs + 999L) / 1_000L).toInt()` 严格向上取整（`1..1000ms -> 1s`，`29_999ms` 已过即剩 `1ms -> 1s`，`30_000ms -> 0s`）。
2. **`Mutex` 串行与冷却状态机边界**：
   - `submitMutex.withLock`（`:147`）串行包裹冷却检查、自动 `PrePost` 预取、表单提交、冷却更新与草稿删除，彻底阻断并发双击重复发帖（`PostRepositoryTest.kt:868-912` 已用门闩实测验证并发双协程仅发出 1 个 POST、第 2 个立即返回 `CooldownActive(30)`）；
   - 非编辑模式（含 `DeletePost`）在发起任何网络请求（含自动 `PrePost` GET）前先判冷却，活跃时 0 网络请求直接返回 `CooldownActive`；
   - `EditPost`（`enforcesCooldown = false`，`:151`）既不受冷却阻塞，成功后也不写入或刷新 `_cooldownUntilMs`；
   - 仅当 `submitResult is PostSubmitResult.Success`（`:208-218`）时才更新 `_cooldownUntilMs = completedAt + 30_000L`；参数非法、自动预取失败、实名拦截、鉴权失败、Cloudflare 验证未过、站点业务报错与网络错误均不启动冷却；
   - 账号切换与退出登录通过 `init` 订阅 `authRepository.currentAccount`（`:82-91`）及 `getRemainingCooldownSeconds`/`submitPost` 同步兜底调用 `resetCooldownIfAccountChanged`（`:374-384`）立即清零旧账号冷却，且冷启动 `cooldownOwnerUid == null` 水合期间不误清刚产生的冷却。
3. **草稿一致性与取消安全**：
   - 仅在 `submitResult is PostSubmitResult.Success` 时于 `withContext(NonCancellable)`（`:219-224`）精确删除 `request.draftKey`，不影响其他 `DraftKey` 草稿；任意失败路径 100% 保留草稿；
   - `DefaultPostRepository.kt:115-116, 196-197` 显式捕获 `CancellationException` 并原样重抛，`parseRedirectedThreadPage` 的 `runCatching`（`:362`）内部仅调用纯同步 Jsoup 解析无挂起点，绝不吞协程取消。

---

### 4. 图片压缩、Multipart 进度、可重放性与取消安全（通过）

1. **`ImageCompressor` 纯 Android 实现与资源清理**（`ImageCompressor.kt`）：
   - 支持 `LocalFile`、`Bytes`、`ContentUri`（含 `8KB` 分块读取 `ensureActive()` 响应取消与 `file://` 回退）；`LocalFile` 标记 `isTempFile = false` 绝不误删调用方原文件；
   - GIF 魔数与逻辑屏幕尺寸解析（`:469-480`）：`> maxUploadBytes` 直接返回 `ImageUploadError.GifTooLarge`（不转静图），合规 GIF 原样直传；
   - EXIF 方向通过 `androidx.exifinterface.media.ExifInterface` 解析并在 `applyExifOrientation`（`:412-433`）完整处理旋转（90°/180°/270°）与镜像（水平/垂直翻转、Transpose/Transverse）；需 EXIF 变换或非支持 MIME（如 WebP）禁止直传，自动走解码重编码为 JPEG；
   - 压缩循环以 2 的幂 `inSampleSize`（含 3 次 OOM 降级）+ `2560px` 上限 8 档尺寸递减 × 5 档 JPEG 质量（`80..40`）有界终止（最多 45 步，每步 `ensureActive()`）；内外双层 `finally`（`:205-209, 371-388`）确保 `decoded`、`orientedBitmap`、`scaledBitmap` 全部 `recycle()` 且源拷贝临时文件与未达标压缩临时文件零残留。
2. **`DefaultImageUploadRepository` 进度流、可重放性与并发/取消安全**：
   - `CountingFileRequestBody`（`:313-354`）：`contentLength() = file.length()`（非 chunked，兼容 OkHttp 与 Cronet 传输层），`isOneShot() = false`，每次 `writeTo` 重新打开 `FileInputStream(file)` 从头传输；在 `SiteAccessGateway` 遇 Cloudflare 403 过盾自动重放同一 `Request`（`SiteAccessGateway.kt:113-130`）及会话过期 `trySilentRelogin` 成功后重建请求重试 1 次（`:171-193`）两种场景下均可完整重放 Multipart 请求体，且由 `maxReportedBytes: AtomicLong` 保证跨尝试进度单调不降、成功前补齐 `fraction = 1f`（`:232-240`）；
   - 多图并发：每次 `uploadImage` 收集拥有独立的 `channelFlow`、独立 UUID 临时文件与独立 OkHttp `Call`，无全局互斥锁，并发上传互不阻塞；
   - 单图取消：`coroutineContext.job.invokeOnCompletion { cancelled.set(true) }`（`:98`）配合 `CountingFileRequestBody.writeTo` 分块检查 `cancelled.get()`（`:335`）与 `SiteAccessGateway.executeCall` 的 `cont.invokeOnCancellation { call.cancel() }`（`SiteAccessGateway.kt:318`）立即中止写流与底层 `Call`；`:258-260` 原样重抛 `CancellationException`，`:306-310` `finally` 确保释放回调并调用 `prepared.cleanup()` 删除临时文件。
3. **`_reports/D6.md` 对外 API 清单核对与测试盲区排查**：
   - 逐行核对 `_reports/D6.md` 第四节（`:108-189`）全部 7 个生产文件的接口、密封类、方法签名、常量与行号，与 `ce4840e8604ad7a4b68d57ec70b3a654389e6021` 源码 **100% 精确一致**，可直接供下游 `E5`（快速回复）与 `E6`（完整编辑器）复用；
   - 细粒度单行变异测试盲区已整理入下方建议 S4。

---

## 三、非阻断改进建议（Suggestions）

1. **S1（`ImageUploadInput.original` 默认值与 `product-design.md` §7.1“图片上传前自动压缩”衔接，`PostModels.kt:204-219` / `ImageCompressor.kt:151-155`）**：当前 `ImageUploadInput.{ContentUri,LocalFile,Bytes}` 的 `original` 默认均为 `true`，而 HiPDA 发帖页 `maxUploadFileSizeBytes` 通常为 `8MB`（`newthread_fid2.html:279`）；若下游 `E6` 直接用默认 `original = true` 调 `uploadImage`，无 EXIF 旋转的 7MB 横幅大图或 PNG 截图会命中 `canDirectUpload` 原图直传（复现旧 `PostFragment.java:522` 未压缩问题）。因 `ImageCompressor` 在 `original = false` 时已对合规 GIF 与长宽比 `>= 3.0` 超长图直传放行，建议 `E6` 选图上传默认传 `original = false`（或后续将默认值改为 `false`）。
2. **S2（编辑器清空已有草稿退出时的删除衔接，`DefaultPostRepository.kt:233-256`）**：`saveDraft` 对全空白内容直接 `return` 不写库（符合新建空草稿不落盘约定，且已在 `PostRepository.kt:48-51` KDoc 说明）；但若用户恢复了一份旧草稿后手动删空全部文字退出，下游 `E5`/`E6` 需显式调用 `deleteDraft(key)`（或在仓储层增设清理逻辑），以免旧草稿在下次进入时“复活”。
3. **S3（收紧 `isUploadResponseUnauthenticated` 含 `#umenu` 已登录页分支，`DefaultImageUploadRepository.kt:386-395`）**：当上传响应包含 `#umenu` 页头且 `meta.session.isLoggedIn == true` 时，当前代码未直接 `return false` 而会继续匹配 `UNAUTH_UPLOAD_MARKERS` 子串；建议在检测到 `#umenu` 时直接 `return !meta.session.isLoggedIn`，避免已登录错误页因正文偶然包含提示词触发一次无谓静默重登。
4. **S4（补齐 3 处细粒度单行变异测试盲区，`PostRepositoryTest.kt` / `ImageCompressorTest.kt`）**：(a) 为 `fetchPrePost` 与 `submitPost` 补充会话过期自动静默重登并重试成功的用例（锁死 `DefaultPostRepository.kt:112, 193` 的 `requireAuth = true`，目前仅 `ImageUploadRepositoryTest` 测了重登重放）；(b) 用 Turbine 直接订阅 `postRepo.cooldownUntilMs` 验证切号/退登自动推送 `0L`（锁死 `DefaultPostRepository.kt:82-91` 后台收集器，避免被同步 `getRemainingCooldownSeconds()` 掩盖）；(c) 为 `ImageCompressorTest` 补充 `content://` `ContentResolver` 流式拷贝用例及 `ORIENTATION_ROTATE_180`/`270`/镜像分支断言（当前 `ImageUploadRepositoryTest:363` 使用 `Uri.fromFile` 走的是 `file://` 回退分支）。
