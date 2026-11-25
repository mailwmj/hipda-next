# 独立代码审查报告 R-B8：帖子详情、内容块与 1 楼投票解析器

- **审查对象**：`origin/card/B8-parser-thread-detail` @ `178e548a7847eae4239c0b972db37094621917e6`（merge-base `d45b6a2a5f5c8bd28aa466778d094f646b9ba935`，与当前 `origin/main` @ `0895b839605fcf5053c0490a43cdd0a4ba699bb2` 亦零冲突）
- **审查角色**：独立代码审查（只读，基于 `git show` / `git diff` / `gh run view` 核实，未 checkout、未编译、未修改源码）
- **审查结论**：**APPROVE**（阻断项 **0** 条，非阻断改进建议 **5** 条）
- **CI 核实**：[Android CI #45612331356](https://github.com/mailwmj/hipda-next/actions/runs/45612331356)（Job `139198782618`）
  - `headBranch`：`card/B8-parser-thread-detail`
  - `headSha`：`178e548a7847eae4239c0b972db37094621917e6`（精确匹配）
  - `status / conclusion`：`completed / success`
  - 已核实日志执行并通过全部必跑任务：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room Schema 一致性检查、`:core-parser:test`（新增 `ThreadDetailParserTest` 6、`ContentBlockParserTest` 8、`PollParserTest` 4 全绿）、`:hipda:testDebugUnitTest`（含 Roborazzi 校验及新增 `ThreadDetailParserEquivalenceTest` 5 条全绿）、debug/release APK 校验与上传。

---

## 一、阻断项（0 条）

对照 `docs/agents/DISPATCH.md` §4 与 `R-B8.md` 阻断标准逐项排查，**无阻断项**。

---

## 二、五大审查维度逐项核实

### 2.1 不可逆 `ContentBlock` 契约质量（供给 `E2a`–`E2c` Compose 原生渲染）

1. **模型完整性与不可变性**：
   - `ContentBlock.kt`（140 行）、`PollModels.kt`（41 行）、`ThreadDetailModels.kt`（59 行）全部为纯 JVM 不可变 `data class` / `sealed interface` / `data object`，零 `var`、零 Android 依赖。
   - `ContentBlock.Text(spans: List<InlineSpan>)` 完整覆盖 `Plain(text, TextFormat)`、`Link(label, url, isSmallFont)`、`Email(email)`、`Emoji(normalizedKey, rawSrc)`、`SmallImage(key, rawSrc)`、`LineBreak`，可直接一对一映射为 Compose `AnnotatedString` + `SpanStyle` + `LinkAnnotation` + `InlineTextContent`，无需 `E2a` 再跑 `Html.fromHtml` 或正则。
   - 块级元素完整拆分为 `Quote`、`GoToFloor`、`Image(fullUrl, thumbUrl, fileSizeBytes)`、`Attachment(url, title, description)`、`Notice(message)`；1 楼投票独立挂载于 `PostFloor.poll: ThreadPoll?`，满足 `E2a`–`E2c` 分阶段渲染需求（注：真机样本 `thread_p1.html` 中 Discuz! 7.2 `img#aimg_*` 仅输出 `width` 无 `height`，无服务端宽高比属性，`fileSizeBytes` 已精确提取供 `E2b` 流量节省占位文案使用）。
2. **T6 原始文字色透传**：
   - `ThreadDetailParser.kt:656-664` 将 `<font color="...">` 原样 trim 后存入 `TextFormat.rawColor`，支持嵌套 `<font>` 继承、覆盖与出栈恢复（`TraversalFrame` 不可变值拷贝），完全剥离了旧 `TextStyle.java:49` / `ColorHelper` 在解析阶段按当前主题吞掉 `#ffffff` / `black` 的耦合（已由 `ContentBlockParserTest.kt:23-51` 与 `ThreadDetailParserEquivalenceTest.kt:281-290` 实证）。
3. **`C1b#2` 表情键归一化**：
   - `ThreadDetailParser.normalizeSmileyKey`（`ThreadDetailParser.kt:210-222`）截取 `images/smilies/` 后路径、剥离查询串/片段与扩展名、将 `/` 转为 `_` 并小写化（同时兼容旧 `hi-pda.com` 历史表情 URL，优于旧版写死 `4d4y.com/forum/images/smilies/`），产出的 `default_smile`、`default_lol`、`coolmonkey_01`、`coolmonkey_16`、`grapeman_08`、`grapeman_18`、`grapeman_24` 与 `:emoji` `EmojiMap.kt:47-115` 全部 61 个 `imageSrc` 键命名规则 100% 一致，并在 `ThreadDetailParserEquivalenceTest.kt:98-104, 155-159, 292-297` 中通过 `EmojiMap.drawableForImageSrc(key, dark = false/true)` 与 `codeForImageSrc(key)` 双向校验。
4. **复用 `B9a` 公共基座**：
   - 直接复用 `DiscuzPageMeta`、`DiscuzCommonParser.parsePageMeta`、`DiscuzTextDecoder.decodeDoubleEscapedEntities`、`DiscuzUrlHelper`（`extractQueryParam`、`middleString`、`avatarUrlForUid`、`isValidId`、`parseIntOrZero`、`extractFirstIntOrZero`、`firstMatch`、`firstElementOrNull`、`lastElementOrNull`），测试直接复用 `SampleFixtures` 与 `LegacyTestSupport`，无重复造轮子。

### 2.2 DOM 遍历正确性与零副作用（逐分支对照 `HiParserThreadDetail.java:34-664`）

| 旧代码位置 | 分支职责 | 新代码位置 | 对照核查结论 |
|---|---|---|---|
| `HiParserThreadDetail.java:34-41` | `getThreadAuthorId` | `ThreadDetailParser.kt:63-71` | 一致；缺失时返回 `null` 替代空串，兼容带多参数的 `uid` 提取。 |
| `45-59` | 分页 `div.pages` | `386-400` | 一致；改用 `pagesEl.children()` 规避旧版强转空白 `TextNode` 为 `Element` 的潜在 `ClassCastException`。 |
| `72-99` | `div#nav` 标题/`fid` 与 `div#threadtitle` 回退 | `427-456` | 一致；均在 `.clone()` 副本上移除 `<a>`，经 `DiscuzTextDecoder` 解码二次转义 Emoji，原 DOM 零修改。 |
| `101-185` | `#postlist` 楼层头、警告、每页条数估算、头像、签名 | `102-189, 458-476` | 一致；`postListEl.children()` 规避旧版 `childNodeSize()/child(i)` 混用越界隐患；`normalizeTimePost` 修复旧 `substring(4)` 短串越界；不触发黑名单过滤、不写 `HiSettingsHelper` 与 `SignatureContainer`。 |
| `189-199, 279-290` | `div.locked`、缺失正文、空正文 Notice | `494-510, 541-555` | 优先级与提示常量完全一致。 |
| `201-277` | 1 楼 `form#poll` 与 `div.specialmsg` 投票 | `255-344, 515-540` | `titleHtml`（克隆去 `a`）、`timerHtml`、`formhash`、`maxChoices`、选项与比率、`footer`（克隆去 `button` 且用 `lastRowConsumed` 修复无表尾行时把末项比率误作 `footer` 的问题）均正确；无 `form#poll` 的已关闭 `specialmsg` 投票亦升级为结构化 `ThreadPoll`，并通过 `ignoredNodes` 避免混入正文。 |
| `292-299` | `i.pstatus` 编辑状态提取与正文剔除 | `557-565, 367` | 提取首个 `i.pstatus` 并加入 `IdentityHashMap` 驱动的 `ignoredNodes`，不调用 `.remove()` 且不泄漏进正文块。 |
| `303-330` | 正文树遍历 | `357-381, 622-635` | 以显式 `ArrayDeque<TraversalFrame>` 替代旧版 `parent().nextSibling()` 指针攀爬，严格限制在 `postMessageEl` 子树内，消除旧版攀爬至 `Document` 时的潜在 NPE，且 1500 层深嵌套不发生 `StackOverflowError`（复杂度 `O(N)`，无死循环路径）。 |
| `397-415` | 跳楼 `<strong>` vs 普通加粗 | `707-738` | 校验 `a[href]`、`回复 ` 前缀、`#`、有效 `postId`、`floor > 0` 与非空 `author`，命中产出 `ContentBlock.GoToFloor` 并跳过子节点；否则退回 `bold = true`。 |
| `467-489` | 行内 `span` 附件及紧邻兄弟文本消费 | `782-815` | 非 `nothumb=` 的 `attachment.php?` 链接产出 `Attachment`，向后查找未跳过的紧邻 `TextNode` 提取 `description` 并登记至 `skippedNodes`，原 DOM 零 `.remove()`。 |
| `529-553` | `div.quote` 去 `font[size=2]` 与 `display:none` | `892-961` | 在 `divEl.clone()` 上提取 `redirect.php?goto=findpost` 的 `pid`/`ptid`、移除 `font[size=2]` 与 `[style*=display][style*=none]`，经同构 `QUOTE_SAFELIST` 清洗并非递归提取 `contentText`。 |
| `332-369, 602-655` | 行内 `aimg`/`none.gif` + `div#<id>_menu`、`onclick` > `file` > `thumb.`、`dl.attachimg`、`dl.t_attachlist` | `574-609, 963-1080` | 优先级与字节数换算完全对齐；增加 `SAFE_CSS_ID_REGEX` 防非法 `id` 注入 Jsoup 选择器抛异常；`extractZoomAttachmentUrl` 优先提取单引号完整参数，兼容绝对图片域名 URL。 |

- **全仓 DOM 变异核查**：对 `core-parser/src/main/kotlin/net/jejer/hipda/parser/threaddetail/` 扫描全部 Jsoup 写操作方法，仅有的 7 处 `.remove()`（`ThreadDetailParser.kt:265, 329, 442, 451, 596, 912, 914`）100% 作用于局部 `.clone()` 节点，调用方 `Document` 零副作用。

### 2.3 只读边界与零冲突

- `git diff --name-status d45b6a2...178e548` 与 `git diff --name-status origin/main...178e548` 均仅含 8 个新增（`A`）文件：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/threaddetail/{ContentBlock,PollModels,ThreadDetailModels,ThreadDetailParser}.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/threaddetail/{ContentBlockParserTest,PollParserTest,ThreadDetailParserTest}.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/ThreadDetailParserEquivalenceTest.kt`
- `hipda/src/main/`、`core-parser` 其他子包、`SampleFixtures.kt`、`LegacyTestSupport.kt`、`hipda/src/test/resources/samples/`、`:emoji/`、`ui/`、`data/`、Gradle/Manifest/CI 共享文件 **0 改动**；与所有在途分支 `card/*`（`D2`、`B9b`、`G2` 等）实测文件交集为空。
- 测试全程离线：`ThreadDetailParserEquivalenceTest` 在每次调用旧解析器前均执行 `LegacyTestSupport.suppressBackgroundNotifyFetch()`（刷新 `HOLD_FETCH_NOTIFY` 10s 防抖窗口），彻底阻断 `ParseNotifyRunnable` 发起网络请求。

### 2.4 等价测试与变异覆盖（含检出的测试盲区）

- **正向核实**：`ThreadDetailParserEquivalenceTest` 在 `thread_p1.html`（50 楼）、`thread_p2.html`（50 楼，含第 51 楼 `好的&amp;#128076;` → `好的👌` 二次转义 Emoji）、`thread_author_only.html`（15 楼）及单/多选投票、富文本合成夹具上逐楼比对了页头、楼层元数据、图片、引用、跳楼、附件、Notice、表情数 + `EmojiMap` 命中、行内链接与归一化纯文本；报告记录的 4 组单行变异自证逻辑成立。
- **检出的单行变异盲区（改坏关键一行现有 23 条测试仍全部通过，建议在后续卡或修补时补齐断言）**：
  1. **图文/引用混排的块间顺序盲区（最关键）**：若删去 `BlockListBuilder.addBlock` 中的 `flushPendingText()`（`ThreadDetailParser.kt:1181`），楼层中位于图片/引用/跳楼/附件**之前**的正文 `InlineSpan` 会一直滞留到楼层末尾 `build()` 才一次性刷出，导致图文混排顺序错乱；但 `ThreadDetailParserEquivalenceTest.assertFloorEquivalent`（`:341-443`）与 `ContentBlockParserTest` 均按 `filterIsInstance<...>()` 分类型断言（且 `quoteBlock`/`goToFloorBlock` 用例仅在块后有文本、块前无文本），删去 `:1181` 后 23 条测试仍全绿。
  2. **表格单元格空格分隔与签名空值断言盲区**：若删去 `"td", "th"` 分支的 `builder.addPlainText(" ", TextFormat.PLAIN)`（`ThreadDetailParser.kt:695`），`ContentBlockParserTest.kt:315-316` 仅断言 `contains("格1")` / `contains("格2")`，而 `EquivalenceTest:447` 抹除了全部空白字符，测试仍全绿；此外 `EquivalenceTest:335` 以 `if (newFloor.signature != null)` 包裹签名断言，若新解析器漏提签名返回 `null`，`EquivalenceTest` 本身不会报错。
  3. **`isRelativeForumScriptUrl` 零覆盖**：`ThreadDetailParser.kt:1133-1146` 的站内相对脚本链接分支在全部 4 个测试类中均无对应 `<a href="viewthread.php?...">` 用例覆盖。

### 2.5 复杂度与可维护性

- 生产代码共 4 个文件（模型 240 行 + 解析器 1,230 行，含详尽 KDoc），替代了旧版分散在 `HiParserThreadDetail`、`DetailBean`、`PollBean`、`Content*`、`TextStyle*`、`SmallImages`、`TextViewWithEmoticon` 中约 1,600 行带全局副作用的 Java 代码。
- 模块职责单一、分节清晰，无冗余抽象层，无测试专用钩子污染生产代码。

---

## 三、非阻断改进建议（供后续 `D5` / `E2a`–`E2c` 接入或收尾顺带处理）

1. **修复 `forumBaseUrl == null` 时站内相对链接被误加 `https://` 前缀**（`ThreadDetailParser.kt:79, 1133-1146`）：`parse` KDoc 声明 `forumBaseUrl` 为 `null` 时保留相对路径，但 `normalizeLinkUrl` 仅在 `forumBaseUrl != null` 时进入 `isRelativeForumScriptUrl`，导致默认 `forumBaseUrl = null` 下 `<a href="viewthread.php?tid=1">` 落入 `:1136` 变成非法域名 `"https://viewthread.php?tid=1"`；同时建议扩展识别 `search.php`/`pm.php`/`my.php` 及以 `/` 开头的站内路径。
2. **净化 `ContentBlock.Quote.time` 尾部回跳链接残留**（`ContentBlock.kt:101-104`、`ThreadDetailParser.kt:908-912`）：真机样本 `thread_p1.html` 第 49 楼 `font[size=2]` 内含历史破损 BBCode `<a href="...redirect.php?goto=findpost...">` `[img][url]...back.gif</a>[/img][/url]`，`metaFont.text()` 会将其拼入 `authorAndTime`，使 `quote.time` 变成 `"21:17[img][url]...back.gif[/img][/url]"`；建议在提取 `author`/`time` 时剔除 `font[size=2]` 内的 `<a>`（或优先取首个 `<font>` 文本）及残余 `[img]`/`[url]` 尾缀。
3. **保留紧邻行内元素之间的单个空格分隔**（`ThreadDetailParser.kt:752, 776`）：旧版 `DetailBean.addLink`/`addEmail`（`DetailBean.java:98, 121`）靠在 `<a>` 两侧硬编码空格避免粘连；新版 `InlineSpan.Link`/`Email` 不带两侧空格，而 `processTextNode` 沿用旧版丢弃纯空白 `TextNode`（如 `<a href="...">A</a> <a href="...">B</a>` 或 `<b>A</b> <i>B</i>` 之间的 `" "`），会导致渲染成 `"AB"` 粘连（块首尾空白仍可由 `flushPendingText` 的 `isTrimBoundary` 裁剪）。
4. **为 `E2c` 增补投票/引用的免 HTML 解析辅助属性**（`PollModels.kt:26-41`、`ContentBlock.kt:87-105`）：`ThreadPoll` 目前按卡片规格提供 `titleHtml`/`timerHtml`（含 `<strong>` 标签），建议 `E2c` 接入时补充纯文本 `titleText`/`timerText`（或 `Quote` 按需复用 `parseContentBlocks` 解析引用内链接/颜色），避免 UI 层解析 HTML 字符串。
5. **补齐块间交错顺序与相对链接单测断言**（`ContentBlockParserTest.kt`、`ThreadDetailParserEquivalenceTest.kt:335, 339-443`）：增加“正文 → 图片 → 正文 → 引用 → 正文”精确块序列断言（守住 `BlockListBuilder.addBlock` 的 `flushPendingText()`）、表格 `<td>` 空格分隔断言、`forumBaseUrl` 有/无两态下站内相对 `<a>` 断言，以及无条件的楼层签名等价断言。
