# 审查报告 R-B7：帖子列表解析器与样本等价测试（含授权追加 R-B9b S1/S2/S4）

- **审查结论**：**APPROVE**
- **审查对象**：`origin/card/B7-parser-thread-list` @ `822969a267e9cd25ba8dbccebf5ddbb8bd8035a9`
- **对比基线**：`ab2d906033cbe7f15ebbf61469a3b1ef38aa87a9`（`git merge-base origin/main 822969a2...`；当前 `origin/main` @ `bba139cc593db4bee274d251df662fb83a1c6052` 仅前进 2 个纯设计文档提交，`git merge-tree --write-tree` 输出 `bd026ad436cdff3140786fc3722a35f3d287e85d` 确认零冲突可直合）
- **CI 核验**：[Android CI #45618707050](https://github.com/mailwmj/hipda-next/actions/runs/45618707050)（Job `139220802156`，`headSha == 822969a267e9cd25ba8dbccebf5ddbb8bd8035a9`，`completed / success`）
  - 已从原始日志逐项核实执行并通过：`:hipda:assembleDebug`、`:hipda:lintDebug`、`:hipda:assembleRelease`、`:core-parser:test`（76 passed）、`:hipda:testDebugUnitTest`（590 passed）、Room Schema 一致性检查、Roborazzi 截图校验、Debug/Release APK 产物校验。
  - 全量单测汇总：**666 tests, 666 passed, 0 failed, 0 errors, 0 skipped**。
- **阻断项（Blocking）**：**0 项**（对照 `docs/agents/DISPATCH.md` §4）。

---

## 一、只读边界、零冲突与纯洁性核查（重点 1）

1. **改动文件清单核对**（`git diff --stat ab2d906033cbe7f15ebbf61469a3b1ef38aa87a9...822969a267e9cd25ba8dbccebf5ddbb8bd8035a9`）：
   - 共 **9 个文件**（`+1028 / -15`）：
     - **B7 核心新增文件（4 个）**：
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/threadlist/ThreadListModels.kt`（`+52`）
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/threadlist/ThreadListParser.kt`（`+177`）
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/threadlist/ThreadListParserTest.kt`（`+318`）
       - `hipda/src/test/java/net/jejer/hipda/parser/ThreadListParserEquivalenceTest.kt`（`+287`）
     - **经负责人授权追加的 R-B9b S1/S2/S4 小修（2 个生产 + 3 个单测）**：
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/post/FavoriteParser.kt`（`+1 / -1`）
       - `core-parser/src/main/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParser.kt`（`+17 / -14`）
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/post/PostFavoriteUploadParserTest.kt`（`+37`）
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/simplelist/SimpleListParserTest.kt`（`+107`）
       - `core-parser/src/test/kotlin/net/jejer/hipda/parser/user/UserParserTest.kt`（`+32`）
2. **禁改区与同波零重叠确认**：
   - `hipda/src/main/`（含 `HiParserThreadList.java`、`ThreadBean.java`、`ThreadListBean.java`、`data/`、`di/`、`ui/`、`HiApplication.java`、`AndroidManifest.xml`）：**0 改动**；
   - `core-parser/.../parser/{common,sanitize,auth,message,search,threaddetail}/`、`UserParser.kt`、`SampleFixtures.kt`、`LegacyTestSupport.kt`、既有 `*EquivalenceTest.kt`、`hipda/src/test/resources/samples/*`、共享构建文件及 `MotionTokenScanTest`：**0 改动**；
   - 与远端在途分支 `origin/card/D2-auth-repository`、`origin/card/B8-parser-thread-detail`、`origin/card/G2-kyant-backdrop` 做 `comm -12` 文件交集比对，均为 **0 文件重叠**。
3. **纯 JVM、零副作用与全程离线**：
   - `git grep` 扫描 `core-parser/src/main/` 确认：零 `android.*`/`androidx.*` 导入、零 `.selectFirst(` 调用（统一使用 `firstMatch` 与 `lastElementOrNull`）、零 `HiSettingsHelper` 读写、零后台线程启动；`ThreadSummary` 与 `ThreadListPage` 均为不可变 `data class`。
   - **零 DOM 变异**：`ThreadListParser` 全程只读查询传入的 `Document`（`DiscuzCommonParser.parseErrorMessage` 在 `clone()` 上移除 `<a>`，`FavoriteParser.parseFavoriteActionXml` 在内部临时 `parseBodyFragment` 上移除 `script, style, a`）；`ThreadListParserTest` 与 `ThreadListParserEquivalenceTest` 在全部真机样本与合成夹具上均断言 `doc.outerHtml()` 调用前后逐字符相等。
   - **100% 离线**：`ThreadListParserEquivalenceTest` 在 `@Before` 初始化 `LegacyTestSupport.initLegacyEnvironment()`，并在每次调用旧 `HiParserThreadList.parse` 前调用 `LegacyTestSupport.suppressBackgroundNotifyFetch()` 刷新 10 秒防抖窗口，彻底阻断 `ParseNotifyRunnable` 后台网络访问；测试代码无任何真实站点访问。

---

## 二、复用 `B9a` 基座与 `D4` 下游契约核查（重点 2）

1. **直接复用 `B9a` 公共基座，零重复造轮子**：
   - `ThreadListParser.kt:29` 复用 `DiscuzCommonParser.parsePageMeta(doc)` 提取登录态 `session`、`formhash`、`errorMessage` 与 5 项 `promptCounts`；
   - `ThreadListParser.kt:62,67` 复用 `DiscuzTextDecoder.decodeDoubleEscapedEntities` 还原双重转义补充平面 Emoji（实测命中 `forum_2_p1_lastpost.html` `tid=3477944` `&amp;#127822;` → `🍎`、`forum_2_p2_lastpost.html` `tid=3477896` `&amp;#128169;` → `💩`，并兼容旧 `emoji-java` 无法识别的 Unicode 14+ 字符如 `&#129760;` `🫠`）；
   - `ThreadListParser.kt:61,79,80,135,155,165,169` 复用 `DiscuzUrlHelper`（`extractQueryParam`、`avatarUrlForUid`、`extractFirstIntOrZero`、`firstMatch`、`lastElementOrNull`），支持 `avatarBaseUrl: String? = null` 可选前缀；
   - 纯 JVM 单测复用 `SampleFixtures`，Robolectric 等价测试复用 `LegacyTestSupport`。
2. **`D4` 下游契约完备性与 `_reports/B7.md` API 清单核实**：
   - **过滤与已读契约**：`ThreadSummary` 原样保留 `isSticky` 与全部 `author`/`authorId`（`newParser_ignoresLegacyStickyAndBlacklistSettings` 已验证不受 `HiSettingsHelper` 影响），稳定暴露 `tid` 与服务端 `isNew`，满足 `D4` 动态结合 `AppSettings.showStickyThreads`、`BlacklistRepository.blockedUsernames` 与 `BrowseHistoryDao.observeVisitedTids()` 的响应式过滤和已读标记需求；
   - **自动补页终止契约**：`ThreadListPage` 提供 `hasThreadRows`（对齐旧 `isParsed`）、`page` 与 `lastPage`；其中 `parsePagination`（`ThreadListParser.kt:146-176`）优先取 `div.pages a[href]` 的 `page=` 查询参数（如真机样本 `<a href="...page=1000" class="last">... 9427</a>` 取服务端真实可访问上限 `1000` 而非不可达的文本 `9427`），无 `page=` 时回退读取锚点文本，并与 `<strong>` 当前页取最大值，可准确支撑 `D4` 末页终止判断；
   - **API 清单核实**：逐行核对 `_reports/B7.md` 第六节列出的 `ThreadListModels.kt`（`:11-37`、`:46-52`）、`ThreadListParser.kt`（`:17`、`:25-26`、`:28-47`）、`FavoriteParser.kt`（`:61-71`）与 `SimpleListParser.kt`（`:64-98`）全部类型、属性、方法签名及行号，与源码 **100% 一致**。

---

## 三、17 字段逐项等价与授权追加 R-B9b S1/S2/S4 审查（重点 3）

### 3.1 `ThreadSummary` 17 字段与跳过规则逐项对照（`HiParserThreadList.java:50-199` vs `ThreadListParser.kt:31-138`）

| 字段 / 规则 | 旧 `HiParserThreadList` | 新 `ThreadListParser` | 审查结论 |
|---|---|---|---|
| 行容器与 `hasThreadRows` | `doc.select("tbody[id]")`，非空置 `isParsed=true` | `doc.select("tbody[id]")`，`tbodies.isNotEmpty()` | **完全等价**；分隔行 `tbody#separatorline` 因无 `th.subject` 自然跳过。 |
| `isSticky` / `isNew` | `td.folder img[src]` 含 `"/pin_"` / `"new"`（旧版按设置跳过置顶） | `td.folder` 首 `img[src]` 含 `"/pin_"` / `"new"`，不过滤 | **提取等价**，符合 `D4` 下沉过滤架构。 |
| `isPoll` | `td.icon img[src]` 含 `"/poll"` | `td.icon` 首 `img[src]` 含 `"/poll"` | **完全等价**。 |
| `tid` / `title` | `th.subject` → 首 `span a`（缺则跳过）；`getMiddleString(href, "tid=", "&")`；`EmojiParser.parseToUnicode(title)` | 同选择器缺失返回 `null`；`extractQueryParam(href, "tid").orEmpty()`（防 `ptid=` 碰撞）；`decodeDoubleEscapedEntities(titleLink.text())` | **真机样本 100% 等价**，且消除参数名后缀碰撞与旧库 Emoji 字典过时限制。 |
| `titleColor` | `getMiddleString(style, "color:", "").trim()`（无 `color:` 得 `""`，遇 `background-color:` 误匹配） | `extractTitleColor`（`:119-131`）按 `;` 拆分声明，精确匹配属性名 `color`（忽略大小写），原样返回 trimmed CSS 色值，无色返回 `null` | **真机样本等价且更稳健**，未提前做深浅色可读性裁剪（符合 `tech-plan.md` T6）。 |
| `typeName` | `subjectTh.select("em a").text()`（无则 `null`） | `subjectTh.select("em a")` 非空解码实体并 `.ifEmpty { null }` | **完全等价**，额外支持分类文本二次实体解码。 |
| `author` / `authorId` / `avatarUrl` / `timeCreate` | `td.author` → `cite a`（缺则跳过；`href.length < "space.php?uid=".length()` 跳过；旧版黑名单跳过）→ `uid=` → 头像 URL → `td.author em`（缺则跳过） | 同一套必选节点与最小链接长度（14）守卫，不过滤黑名单；`extractQueryParam(userLink, "uid")` 防 `xuid=` 碰撞；`avatarUrlForUid` 计算 9 位补零中等头像；`authorTd.firstMatch("em")` 缺失返回 `null` | **跳过条件与字段提取完全等价**。 |
| `timeUpdate` | `td.lastpost em a` 首元素文本，无则 `null` | `tbody.firstMatch("td.lastpost em a")?.text()` | **完全等价**。 |
| `replyCountText` / `viewCountText` | `td.nums` → `strong` / `em`（任一缺失跳过） | 同一套必选节点守卫，另提供 `replyCount`/`viewCount: Int?` 对 `"-"` 安全返回 `null` | **完全等价**。 |
| `lastPostAuthor` | `td.lastpost cite` 首元素文本（缺则跳过） | `tbody.firstMatch("td.lastpost cite")?.text() ?: return null` | **完全等价**。 |
| `withPic` | 遍历 `img.attach`，任一非空 `src.endsWith("image_s.gif")` 置 `true` | `tbody.select("img.attach").any { ... endsWith("image_s.gif") }` | **完全等价**（正确排除 `common.gif` 等普通附件图标及非 `.attach` 图标）。 |
| `maxPage` | 末个 `span.threadpages a[href]` 的纯数字 `page=`，默认 `1` | `lastElementOrNull()` + `extractQueryParam("page")`（防 `mypage=` 碰撞）+ ASCII 数字且 `> 0`，默认 `1` | **真机样本完全等价**，并修复 `page=0` 时旧版置 `0` 的边界瑕疵。 |

### 3.2 负责人授权追加的 R-B9b S1 / S2 / S4 逐项审查

| 编号 | 文件与位置 | 审查结论 |
|---|---|---|
| **R-B9b S1** | `FavoriteParser.kt:68` + `PostFavoriteUploadParserTest.kt:212-230` | **通过**。`parseFavoriteActionXml` 改为 `fragment.select("script, style, a").remove()`，准确剥离收藏成功/重复收藏 AJAX XML 尾随的 `<a href="my.php?item=favorites&type=thread">[查看我收藏的所有主题]</a>`，保留纯净提示文案，与 `DiscuzCommonParser.parseErrorMessage` 语义统一且不修改外部 DOM。 |
| **R-B9b S2** | `SimpleListParser.kt:72-90, 165-180` + `SimpleListParserTest.kt:133-182` | **通过**。`parseMyReplies` 摒弃脆弱的 `i % 2 == 1` 奇偶下标假设，改为由 `parseReplyHeaderRow(row)` 按结构识别头行（含单链接 `redirect.php?goto=` 且具 `td.lastpost`）：前置/中间插入单行分隔公告时不再引发后续全表奇偶翻转漏数据；连续头行（缺摘要行）或表尾孤立头行均通过 `pending?.let { add(it.toMyReplyItem(replyText = null)) }` 安全兜底不丢条；因真机摘要行（`<th colspan="4" class="lighttxt">`）无 `td.lastpost`，摘要行绝不会被误判为头行。 |
| **R-B9b S4** | `PostFavoriteUploadParserTest.kt:232-246`、`SimpleListParserTest.kt:185-235`、`UserParserTest.kt:57-86` | **通过**。完整补齐了 `FavoriteParser.parseFavoriteIdsPage` 与 `SimpleListParser` 三类列表在末页 `<strong>`（最大页码仅出现在 `<strong>`）下的分页断言，以及 `SimpleListParser.parseMyThreads` 标题与 `UserParser.parseUserInfo`（`h3.blocktitle` + `commonlist li` 两处）的双重转义 Emoji 解码断言，彻底闭合 `R-B9b` 指出的 4 处单行变异盲区。 |

---

## 四、测试质量、单行变异盲区与复杂度评估（重点 4）

1. **全量逐字段比对核实**：
   - `ThreadListParserEquivalenceTest.kt:173-202` 在 3 份真机样本（各 75 条，共 **225 条 × 17 字段 = 3,825 次字段级断言**）及合成夹具上全量循环比对，非抽样断言；同时覆盖置顶/黑名单设置无关性、空版块与残缺行跳过。
   - 实现者完成的 12 组单行变异自证（B7 5 组 + R-B9b 7 组）均命中核心分支。
2. **残余细粒度单行变异盲区排查（非阻断，记入建议 S2）**：
   - **盲区 A（`parsePagination` 的无 `page=` 锚点文本回退）**：`ThreadListParser.kt:169` 的 `val pageNum = hrefPage ?: DiscuzUrlHelper.extractFirstIntOrZero(anchor.text())` 中，所有真机样本与合成夹具的 `div.pages a` 均带 `href="...page=N"`，因此 `hrefPage` 恒非空；若将右侧回退改为 `?: 0`，现有测试仍会通过（伪静态如 `forum-2-5.html` 场景未单测）。
   - **盲区 B（`extractThreadMaxPage` 的纯 ASCII 数字预检）**：`ThreadListParser.kt:136` 的 `if (!pageParam.all { it in '0'..'9' }) return 1` 用于拦截 `"+5".toIntOrNull() == 5` 等带正号字符串；现有夹具仅测了 `page=0`，若删去第 136 行保留第 137 行 `toIntOrNull()?.takeIf { it > 0 } ?: 1`，现有测试仍会通过。
3. **代码简洁度评估**：
   - B7 生产代码共 **229 行**（`ThreadListModels.kt` 52 行 + `ThreadListParser.kt` 177 行），相比旧版 `HiParserThreadList` + `ThreadBean` + `ThreadListBean`（共 446 行）精简近半，同时补齐了版块分页、公共页头 `meta`、CSS `color` 属性级解析与二次实体解码；全文件为无状态 `object` 与扁平 `data class`，无过度设计或测试专用钩子。

---

## 五、非阻断建议项（Suggestions）

- **S1（下游 `D4` 接入时过滤或兜底空白 `tid`）**：[ThreadListParser.kt:61](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/threadlist/ThreadListParser.kt#L61) 为对齐旧 `HiParserThreadList.java:86` 在标题链接缺失 `tid=` 参数时返回 `""`；建议 `D4` 组装列表状态与写入浏览历史前校验 `DiscuzUrlHelper.isValidId(tid)`，防止极端异常行产生空串主键引起 Compose `LazyColumn` 重复 `key` 崩溃。
- **S2（可顺手补齐两处防御分支的合成夹具断言）**：后续可在 `ThreadListParserTest` 为 [ThreadListParser.kt:169](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/threadlist/ThreadListParser.kt#L169)（无 `page=` 的伪静态分页链接如 `<a href="forum-2-5.html">5</a>`）与 [ThreadListParser.kt:136](file:///home/mir/Documents/hipda-next/core-parser/src/main/kotlin/net/jejer/hipda/parser/threadlist/ThreadListParser.kt#L136)（`page=+5` 带符号参数回退 `1`）各补一行断言，消除残余单行变异盲区。
