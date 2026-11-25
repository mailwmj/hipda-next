# 独立代码审查报告 R-D5：帖子详情 Repository

- **审查结论**：**APPROVE**（0 项阻断，4 项非阻断改进建议）
- **审查对象**：`origin/card/D5-thread-detail-repo` @ `65000ff4915a5ea71007aab6687cc2c48cfab1b1`
- **比较基线（`{BASE}`）**：`5c71b358516d0557d447c973c2f22951b82d9651`（`git merge-base origin/main 65000ff4915a5ea71007aab6687cc2c48cfab1b1`；同时核对当前 `origin/main` @ `90c026d` diff 完全一致，零冲突）
- **CI 验证**：[Android CI #45641082123](https://github.com/mailwmj/hipda-next/actions/runs/45641082123)
  - `headBranch`：`card/D5-thread-detail-repo`
  - `headSha`：`65000ff4915a5ea71007aab6687cc2c48cfab1b1`（精确匹配）
  - 状态与结论：`completed / success`（Job `139299589015` Build, Lint & Unit Tests）
  - 已核实日志执行任务：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room Schema 一致性检查、`:core-parser:test`、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）
  - 单元测试汇总：`TOTAL: 788 tests, 788 passed, 0 failed, 0 errors, 0 skipped`（其中 `:hipda:testDebugUnitTest` 690/690 通过，D5 新增 `ThreadDetailRepositoryTest` **14/14** 全部通过）

---

## 一、阻断项（Blockers）

**无**。

已逐条对照 `docs/agents/DISPATCH.md` §4 与审查卡 `R-D5.md` 阻断标准核实：
1. **投票 `inajax=1` 鉴权隔离**：`submitPollVote`（`DefaultThreadDetailRepository.kt:318-323`）调用 `forumHttpClient.postGbkForm(..., requireAuth = false)`，不会把无 `#umenu` 的正常 AJAX XML 回包误判为未登录触发静默重登、重复投票 POST 或死循环（`ThreadDetailRepositoryTest.kt:806, 861` 断言 `reloginCalls == 0`）。
2. **多选投票表单键与排序**：`DefaultThreadDetailRepository.kt:299, 310-315` 对 `optionIds` 去空、去重并按字典序排序（`trimmedOptions.distinct().sorted()`，与旧 `PostHelper.java:172-176` `Collections.sort` 一致），以 `formhash` + 多值重复键 `pollanswers[]` 经 `gbkFormBody` 编码提交。
3. **浏览历史写入与裁剪**：成功加载帖子且 `recordHistory == true`、具合法 `tid` 与非空 `title` 时（`DefaultThreadDetailRepository.kt:457-463, 474-499`），依次执行 `browseHistoryDao.upsert(...)` 与 `browseHistoryDao.trimTo(maxBrowseHistory)`。
4. **写操作与测试 100% 离线核验**：
   - 全量扫描 D5 新增的 5 个文件，**零 `http://` / `https://` 外部地址，零 `4d4y.com` / `hi-pda.com` 域名引用**（仅合成 HTML 面包屑文本含 `4D4Y` 字样）；
   - `ThreadDetailRepositoryTest.setUp()`（`:106-110`）将 `AppSettings.forumServer` 指向本地 `MockWebServer`（`:1027-1028` 变体亦指向同一本地 `server`），全部 302 `Location` 均为站内相对路径（`:329, 349, 382, 828, 888`），`LegacyTestSupport.loadSample`（`LegacyTestSupport.kt:57-67`）为纯本地文件读取且不触发旧解析器后台通知线程；
   - 数据库测试全程使用 `Room.inMemoryDatabaseBuilder`（`:120-122`），`@After` 关闭 `database`、`OkHttpClient` 线程池与 `MockWebServer`（`:129-139`），绝无对真实站点发起读/写请求的路径。
5. **五线零冲突边界**：`git diff --stat 5c71b358516d0557d447c973c2f22951b82d9651...65000ff4915a5ea71007aab6687cc2c48cfab1b1` 仅含 5 个纯新增文件（`+2623 / -0`），对禁止区域零修改。

---

## 二、四项审查重点逐项核验

### 2.1 边界与五线零冲突守卫（通过）
- `git diff --stat 5c71b358516d0557d447c973c2f22951b82d9651...65000ff4915a5ea71007aab6687cc2c48cfab1b1`：
  - `hipda/src/main/java/net/jejer/hipda/data/threaddetail/ThreadDetailModels.kt`（`+154`）
  - `hipda/src/main/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepository.kt`（`+115`）
  - `hipda/src/main/java/net/jejer/hipda/data/threaddetail/DefaultThreadDetailRepository.kt`（`+806`）
  - `hipda/src/main/java/net/jejer/hipda/di/ThreadDetailModule.kt`（`+20`）
  - `hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt`（`+1528`）
- 交叉核对在途分支 `origin/card/D4-thread-list-repo`、`origin/card/C4d-shell-glass-polish`、已合入 `origin/main` 的 `D7` 及 `D6` 目录：
  - `data/local/*`、`di/DatabaseModule.kt`、`HiDatabaseDaoTest.kt`、`data/user/*`、`di/UserModule.kt`、`data/threadlist/*`、`di/ThreadListModule.kt`：**0 改动**；
  - `data/post/*`、`di/PostModule.kt`、`data/message/*`、`di/MessageModule.kt`、`data/auth/*`、`data/settings/*`、`data/network/*`、`data/account/*`：**0 改动**；
  - `ui/*`、`src/debug/*`、Roborazzi 快照、`core-parser/`、样本文件、旧 Java 代码与全部共享构建/Manifest/CI 文件：**0 改动**。

### 2.2 详情加载路径、Room 联动与黑名单响应式过滤（通过，附建议 S1/S3）
- **端点路径与旧版逐字符对齐**（对照 `HiUtils.java:129,136-138`、`ThreadDetailJob.java:124-154`）：
  - 全帖：`viewthread.php?tid={tid}&page={page}`（`DefaultThreadDetailRepository.kt:742`）；
  - 只看楼主（已知 `authorId`）与只看此人（`UserOnly(uid)`）：校验正整数 ID 后请求 `viewthread.php?tid={tid}&page={page}&authorid={uid}`（`:96-110, 740`）；
  - 只看楼主（`authorId == null`）：先请求 `viewthread.php?tid={tid}&page=1` 经 `ThreadDetailParser.parseThreadAuthorId(doc)` 解析楼主 UID（若第 1 页鉴权/站点报错或缺 `#postlist` 立即短路返回错误，不发第 2 次请求、不写历史，优于旧版吞异常退化为全帖），再拼 `&authorid={uid}` 拉取目标页并回传解析后的 `AuthorOnly(uid)` 与 `threadAuthorId`（`:109-120, 376-406`）；
  - 跳末页：`redirect.php?goto=lastpost&from=fastpost&tid={tid}`（`:152, 704`）；
  - 按 `pid` 定位楼层：带 `tid` 走 `redirect.php?goto=findpost&pid={pid}&ptid={tid}`，不带 `tid` 走 `gotopost.php?pid={pid}`（`:181-185`），从重定向最终 `response.url` 提取 `tid`/`ptid` 或回退 `ThreadDetailParser` DOM 提取，并在 `Success.targetPostId` 带出目标 `pid`。
- **鉴权与游客浏览策略（对齐 `product-design.md` §7.1、§9）**：
  - `isSignedIn()` 读取 `authRepository.currentAccount.value != null`（已核实 `DefaultAuthRepository.kt:110-114` 在未登录或 `manuallyLoggedOut == true` 时均为 `null`）；
  - 已登录态传 `requireAuth = true` 复用 `D2` 单飞静默重登一次；游客/手动退出态传 `requireAuth = false` 正常加载公开帖，遇登录表单或未登录提示返回 `ThreadDetailError.AuthRequired`，遇删帖等真实报错返回 `SiteError`（`:501-552`）。
- **Room 联动**：
  - `BrowseHistoryDao`：只读复用 `upsert` 与 `trimTo(maxBrowseHistory)`（默认 `500`），不改表结构与 DAO（非首楼页作者回退细节见建议 S1）；
  - `ReadingPositionDao`：`getReadingPosition` / `saveReadingPosition` 只读复用 `get`/`upsert`，完整保留 `page`（钳制 `>= 1`）、`pid`、`offsetPx`（钳制 `>= 0`，与 Compose `LazyListState` 非负偏移契约一致）及 `ReadingFilterMode`（`NONE` 自动清空 `filterUid`/`filterUserName`）。
- **黑名单响应式遮蔽/过滤**：
  - `observeFloors` 与 `observeVisibleFloors`（`:240-254`）通过 `combine(floorsFlow, blacklistRepository.blockedUsernames)` + `distinctUntilChanged()` 在黑名单增删、撤销、切号或退登时即时重算 `isAuthorBlocked` / 过滤视图，全程 **0 网络请求**；元素 `.trim()` 大小写敏感匹配与 `D8b` `BlacklistStore` 及旧 `HiSettingsHelper.isInBlacklist` 完全一致。
- **协程取消安全**：
  - 全部 4 个生产文件内仅存在 3 处 `catch (e: IOException)`（`:324, 387, 427`），无任何 `catch (Exception)` / `catch (Throwable)` / `runCatching`，`CancellationException` 原样向上传播（已由 `:1108-1127` 并发取消用例验证）。

### 2.3 1 楼投票提交与结果刷新（通过，附建议 S2）
- **前置校验与登录门禁（`:279-308`）**：严格校验 `fid > 0`、正整数 `tid`、非空 `formhash`、`maxChoices >= 1`、`optionIds` 非空且无空项、去重后数量 `<= maxChoices`；未登录/手动退出直接返回 `ThreadDetailError.AuthRequired(NOT_LOGGED_IN)`，**0 网络请求**。
- **表单与端点（`:310-323, 745-746`）**：请求 `misc.php?action=votepoll&fid=$fid&tid=$normalizedTid&pollsubmit=yes&quickforward=yes&inajax=1`（与 `HiUtils.java:132` 完全一致），表单按序写入 `formhash` 与字典序排序的重复键 `pollanswers[]`。
- **响应分类与自动刷新第 1 页（`:328-373, 554-660`）**：
  - 准确区分 AJAX XML `<root>`（优先检查 `div.alert_error` → `div.alert_info` → 纯文本成功/错误（自动剔除延迟跳转 `<script>` 避免报错 XML 因含 `setTimeout("location.href=...")` 误判成功）→ 纯 JS `quickforward` 跳转）与 HTML 落地页（`div.alert_error` / `div.alert_info` / 已登录 `#postlist` / 未登录页）；
  - 失败时直接返回 `SiteError` / `AuthRequired` / `InvalidPage` 且不重拉第 1 页；
  - 成功时若 302 已落到已登录无错的第 1 页则直接解析复用（省去 1 次冗余 GET），否则以 `recordHistory = false` 自动调用 `loadPage(tid, page = 1, filter = All)` 返回最新 `ThreadDetailPage` 与 1 楼 `ThreadPoll`。

### 2.4 对外 API 质量与单行变异盲区分析（通过，附建议 S4）
- **对外 API 清单核对**：已逐行比对 `_reports/D5.md` 第四节（`:85-126`）中 `ThreadDetailModels.kt`、`ThreadDetailRepository.kt`、`DefaultThreadDetailRepository.kt`、`ThreadDetailModule.kt` 的全部类型、方法签名、属性及行号区间，**与 `65000ff4915a5ea71007aab6687cc2c48cfab1b1` 源码 100% 精确吻合**。
- **下游 `E2a`–`E2c` / `E4a` / `E3` 适配度**：
  - `ThreadDetailFloorItem` 提供稳定 `postId` 键且全模型不可变、仓储无每帖可变状态，天然支持 `E3` 单栏/图标轨/铰链双栏/三栏 `movableContentOf` 零重拉迁移；
  - `ThreadDetailLoadResult.Success` 暴露完整 `ThreadDetailPage`（含 `meta.formhash`/`promptCounts`、`postsPerPageEstimate`、`allImages`）、`filter`、`threadAuthorId`、`forumName`、`targetPostId`、`hasNextPage`、`poll`，`PollVoteResult.Success` 直接返回刷新后的 `refreshedPage` 与 `poll`，干净覆盖 `E2a`（连续加载/只看切换）、`E2b`（图片预占位/看图）、`E2c`（引用/只看此人/1 楼投票提交即刷柱条）、`E4a`/`E4b`（阅读位置恢复、末页与引用跳楼高亮）。
- **关键单行变异核查表**：

| 变异场景 | 单行变异位置 | 现有单测是否变红 | 结论 |
|---|---|---|---|
| 删掉投票多选排序或折叠重复键 | `DefaultThreadDetailRepository.kt:299, 313` | **是**（`ThreadDetailRepositoryTest.kt:870-877` 变红） | 已守住（建议补跨位数 ID 区分字典序与数值序） |
| 删掉浏览历史 `trimTo` | `DefaultThreadDetailRepository.kt:498` | **是**（`ThreadDetailRepositoryTest.kt:561` 返回 4 条而非 3 条变红） | 已守住 |
| 删掉投票 `inajax=1` 的 `requireAuth = false` | `DefaultThreadDetailRepository.kt:322` | **是**（`ThreadDetailRepositoryTest.kt:798, 806` 触发重登并返回 `AuthRequired` 变红） | 已守住 |
| 删掉报错 XML 剥离 `<script>` | `DefaultThreadDetailRepository.kt:792` | **是**（`ThreadDetailRepositoryTest.kt:1169-1176` 误判成功变红） | 已守住 |
| `loadLastPage` / `loadByPostId` / `resolveThreadAuthorFromPage1` 误写 `requireAuth = false` | `DefaultThreadDetailRepository.kt:160, 196, 385` | **否**（3 处测试夹具均为已登录页，14/14 仍通过） | **盲区（见 S4-a）** |
| `@Inject` 构造误传 `maxBrowseHistory = 0` | `DefaultThreadDetailRepository.kt:68`（且 `:49` 允许 `>= 0`） | **否**（单测仅调 8 参 `internal constructor`，14/14 仍通过） | **盲区（见 S4-b）** |
| 投票后刷新第 1 页误开 `recordHistory = true` 或删 `fallbackOpAuthor` | `DefaultThreadDetailRepository.kt:360` / `:115, 484, 780` | **否**（投票测试未断言历史表，`AuthorOnly` 夹具含 1 楼未走到 `:484`，14/14 仍通过） | **盲区（见 S4-c）** |

---

## 三、非阻断改进建议（Suggestions）

1. **S1（避免非第 1 页加载覆盖浏览历史的楼主名，`DefaultThreadDetailRepository.kt:483-485`）**：当以 `recordHistory = true` 打开第 2+ 页（如 `E4a` 恢复阅读位置）、`loadLastPage`、`loadByPostId` 落到非首页或 `UserOnly` 模式时，`floors.firstOrNull { it.floor == 1 }` 与 `fallbackOpAuthor` 均为 `null`，`:485` 会取当前页首条回复者写入 `BrowseHistoryEntity.author` 并覆盖此前已记录的真实楼主名（旧 `ThreadDetailFragment.java:1469` + `HistoryDao.java:54` 仅在 `page == 1` 时更新作者）；建议 `E2a`/`E4a` 接入时支持可选 `threadAuthor: String? = null` 入参（与 `forumName` 对齐），并在非 `AuthorOnly` 且无 1 楼时不以普通回帖层主覆盖原楼主。
2. **S2（收紧 `POLL_SUCCESS_MARKERS` 防礼貌/跳转尾缀误判成功，`DefaultThreadDetailRepository.kt:720-726, 803-804`）**：当前列表含宽泛短语 `"感谢您的参与"`、`"非常感谢"`、`"转入主题页面"`（标准 Discuz! 7.2 `thread_poll_voted` 文案为 `'您已参与过这个投票，请返回。'`，现网不误判），若自定义错误提示附带礼貌或跳转尾缀可能误判为 `Succeeded`；建议仅保留 `"投票成功"`、`"投票已经提交"`、`"投票已提交"` 或先排除含 `"对不起"`/`"已经投过"`/`"已参与过"`/`"过期"`/`"关闭"` 的错误提示。
3. **S3（`loadByPostId(tid = null)` 增加页头脚本 `tid = parseInt('...')` 提取兜底，`DefaultThreadDetailRepository.kt:434, 761-766`）**：对齐旧 `ThreadDetailJob.java:84`，在 `gotopost.php` 未走带 `tid` 的 URL 重定向时，可先从响应 `<head>` 脚本提取 `tid = parseInt('...')` 作为 `effectiveTidHint`，再回退 `ThreadDetailParser` 的 `div#wrap a[href]` 扫描。
4. **S4（补齐 4 处单行变异测试盲区，`ThreadDetailRepositoryTest.kt`）**：(a) 为 `loadLastPage`（`:160`）、`loadByPostId`（`:196`）与 `AuthorOnly(null)` 第 1 页预取（`:385`）补充登录态过期静默重登单测；(b) 将 `:49` 收紧为 `require(maxBrowseHistory >= 1)` 并直接测试 6 参 `@Inject constructor` 默认保留上限；(c) 断言 `submitPollVote` XML 刷新分支不写入 `BrowseHistoryDao`（守卫 `:360` `recordHistory = false`）并覆盖 `AuthorOnly(null)` 跳第 2 页无 1 楼时的 `fallbackOpAuthor`（`:484`）；(d) 多选投票用跨位数选项 ID（如 `listOf("10", "2", "10")`）锁死字典序而非数值序。
