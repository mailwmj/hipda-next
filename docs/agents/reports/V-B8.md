# 独立探针验证报告 V-B8：帖子详情、内容块与 1 楼投票解析器

- **被测对象**：`origin/card/B8-parser-thread-detail` @ `178e548a7847eae4239c0b972db37094621917e6`
- **验证分支 / 工作区**：`verify/B8-probes` @ `c12c0ec3320d9991f8ccbcd652928e24a2b0749e`（`/home/mir/Documents/hipda-next.wt/V-B8`）
- **总判定**：**PASS**（未触发任何阻断项；3 份真机样本全楼层新旧等价，多处历史解析缺陷新版明显更稳健）
- **CI 证据**：[Android CI #45616280466](https://github.com/mailwmj/hipda-next/actions/runs/45616280466)（`success`，`660 tests, 660 passed, 0 failed, 0 errors, 0 skipped`；含 `B8ProbeTest` 6/6、`B8LegacyCompareProbeTest` 5/5、`ThreadDetailParserEquivalenceTest` 5/5、`assembleDebug`、`assembleRelease`、`lintDebug`、Roborazzi 验证）

---

## 1. 边界与纯洁性核验

| 检查项 | 结果 | 证据 |
|---|---|---|
| 仅新增探针文件、零生产/样本/共享文件改动 | 通过 | `git diff 178e548..c12c0ec --stat` 仅包含 `core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt`（618 行）与 `hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt`（410 行） |
| `origin/card/B8-parser-thread-detail` 与 `main` 只读未动 | 通过 | 未向 `card/B8-parser-thread-detail` 或 `main` 推送 |
| 测试全程离线、阻断旧解析器后台线程 | 通过 | 调用旧 `HiParserThreadDetail.parse` 前均执行 `LegacyTestSupport.initLegacyEnvironment()` 与 `HiParserThreadList.holdFetchNotify()` |
| 无 DOM 副作用 | 通过 | 所有真机样本与合成用例均在调用前/后断言 `doc.outerHtml()` 逐字符相等 |
| 独立视角原则 | 通过 | 全程未读取实现者测试文件与 `_reports/B8.md`，仅对照生产源码与旧解析器编写探针 |

---

## 2. 探针逐项结论与证据

### 探针 a：3 份真机样本全楼层对照与 DOM 零副作用

- **结论**：**通过（核心字段与块计数新旧一致；页内帖数估算新版更稳健）**
- **覆盖样本**：
  - `thread_detail/thread_p1.html`（第 1 页、共 50 楼，含 `i.pstatus` 位于第 2 楼 `53528035`、`aimg_4134918`、`div.quote`、`div.locked` 及 `coolmonkey_01` / `default_lol` / `grapeman_08` / `grapeman_18` 表情）
  - `thread_detail/thread_p2.html`（第 2 页、共 50 楼，含 `53529502` 二次转义补充平面 Emoji `好的&amp;#128076;`、4 个锁定楼层、`div.quote`、`coolmonkey_16` 表情）
  - `thread_detail/thread_author_only.html`（第 1 页、共 15 楼，含 4 处引用、`aimg_4134918` 与 `i.pstatus`）
- **新旧对照**：
  - 页级：`threadAuthorId`、`page`、`lastPage`、`tid`、`fid`、`title` 全部与 `HiParserThreadDetail.parse` 一致；`tid = null` 时回退提取 `2602293` 正确。
  - 楼层级：全部 115 个楼层（50 + 50 + 15）逐一核对 `postId`、`floor`、`page`、`uid`、`author`、`avatarUrl`、`timePost`、`isWarned`、`postStatus`、旧 `SignatureContainer` vs 新 `floor.signature` 均一致。
  - 块与表情：图片数量/原图/缩略图/字节数、附件数、引用（`tid`/`pid`/`author`/`time`）、跳楼数、锁定 `Notice` 数量一致；样本内解析出的全部表情 `normalizedKey` 均可命中 `EmojiMap.drawableForImageSrc`（深/浅色）与 `EmojiMap.codeForImageSrc`。
  - **差异说明（新版设计 / 更稳健）**：
    1. `thread_p2.html` 帖子 `53529502` 的 `好的&amp;#128076;`：旧版在 `ContentText` 中保留 `&#128076;` 留待 `Html.fromHtml` 渲染，新版直接在 `InlineSpan.Plain` 解码为 `好的👌`（`"\uD83D\uDC4C"`）。
    2. `postsPerPageEstimate`：新版仅返回计算值（`50` / `50` / `null`）不写全局 `HiSettingsHelper`，且第 1 页按 `post_` 子元素数统计，不混入空白文本节点。
  - DOM 不变性：3 份样本在调用 `parseThreadAuthorId` 与 `parse` 前后 `doc.outerHtml()` 完全相等。
- **探针位置**：[B8ProbeTest.kt#L25-L96](file:///home/mir/Documents/hipda-next.wt/V-B8/core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt#L25-L96)、[B8LegacyCompareProbeTest.kt#L50-L186](file:///home/mir/Documents/hipda-next.wt/V-B8/hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt#L50-L186)。

### 探针 b：复杂嵌套格式、跳楼、引用与文本安全

- **结论**：**通过（跳楼/引用/URL/邮箱/文本安全新旧一致；T6 颜色保留与 `<b>` 支持新版更稳健）**
- **实测表现**：
  - **多层格式**：`<font color="#ffffff" size="1"><b><i><strike><u>多层白色小字</u></strike></i></b></font>` 解析出 `TextFormat(bold=true, italic=true, underline=true, strikethrough=true, smallFont=true, rawColor="#ffffff")`，`<font color="black"><font size="1">嵌套黑字</font></font>` 保留 `rawColor="black"` 与 `smallFont=true`。
  - **新版更稳健**：对照实测 `HiParserThreadDetail` 在浅色模式下通过 `TextStyle` / `ColorHelper.isTextColorReadable` 丢弃了 `#ffffff`，且旧版 `HiParserThreadDetail.java:379-396` 未把 `"b"` 列入样式分支，导致 `<b>B标签正文</b>` 的文本被旧版彻底丢弃；新版按 T6 原样透传颜色并正确保留 `<b>`（`bold=true`）。
  - **跳楼与普通加粗**：`<strong><a href="redirect.php?goto=findpost&pid=99&ptid=88">回复 5# Alice</a></strong>` 生成 `ContentBlock.GoToFloor(floor=5, author="Alice", targetTid="88", targetPid="99")`；普通 `<strong>加粗文本</strong>` 生成 `InlineSpan.Plain(..., bold=true)`；无链接 `<strong>回复 5# 非跳楼无链接</strong>` 回退加粗文本、无效 PID `<strong><a href="...pid=abc...">回复 5# 无效PID</a></strong>` 回退 `InlineSpan.Link`，均不丢文本。
  - **引用块**：`div.quote` 正确提取 `authorAndTime`/`author`/`time`/`targetTid`/`targetPid`，排除 `font[size=2]` 作者行及 `style="display:none"` / `style="DISPLAY: NONE !important;"` 隐藏噪声，不改原 DOM；引文内 `&amp;#128512;` 在 `contentText` 解码为 `😀`。
  - **列表/表格/裸链/邮箱/文本安全**：`<ul><li>`、`<ol><li>`、`<table><tr><td>`、裸 URL、小字号 `.hi-pda.com` -> `.4d4y.com` 域名替换、邮箱、`&lt;script&gt;alert('xss')&lt;/script&gt;` 纯文本、十进制 `&amp;#128512;` 与十六进制 `&amp;#x1F680;` 均完整保留且准确结构化。
- **非阻断观察**：
  1. `BlockListBuilder.addPlainText` 会把仅被空白换行分隔且 `TextFormat` 相同的相邻 `Plain` 片段合并为一个 `InlineSpan.Plain`。
  2. `Quote.contentHtml`（以及 `ThreadPoll.titleHtml`）由 Jsoup `.html()` 二次输出，仍保持 `&amp;#128512;` HTML 实体形态，而 `Quote.contentText` / `PollOption.text` / `ThreadPoll.footer` 均已解码为 Unicode 字符 `😀`。
- **探针位置**：[B8ProbeTest.kt#L103-L248](file:///home/mir/Documents/hipda-next.wt/V-B8/core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt#L103-L248)、[B8LegacyCompareProbeTest.kt#L188-L236](file:///home/mir/Documents/hipda-next.wt/V-B8/hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt#L188-L236)。

### 探针 c：`C1b#2` 表情键归一化与图片/附件优先级

- **结论**：**通过（新旧一致）**
- **实测表现**：
  - 表情归一化：`images/smilies/default/smile.gif` -> `default_smile`（`:)`）、`.../coolmonkey/01.gif?v=1` -> `coolmonkey_01`（`{:2_41:}`）、`.../grapeman/01.gif#hash` -> `grapeman_01`（`{:3_57:}`），全部命中 `EmojiMap.drawableForImageSrc`（深/浅色）与 `EmojiMap.codeForImageSrc`。
  - 内图优先级与尺寸：`aimg_1` 验证 `onclick("zoom(this, 'attachment.php?aid=xyz')")` 优先于 `file` 优先于 `thumb`，`thumbUrl` 保留，从 `div#aimg_1_menu` 提取 `12.5 KB` -> `12800L`；`aimg_2` 验证无 `onclick` 时 `file` 优先于 `none.gif`（`2 MB` -> `2097152L`）；`aimg_3` 验证回退 `thumb`（`708 Bytes` -> `708L`）。
  - `dl.attachimg`：与旧版 `dlEl.select("img").first()` 对齐（`ThreadDetailParser.kt:585`），正常提取图片及 `em` 尺寸；探针初测发现若人工在 `dl.attachimg` 的 `<dt>` 内塞入图标 `<img>` 则会命中第一个图标（真实 Discuz `dl.attachimg` `<dt>` 不含图标，而 `dl.t_attachlist` 普通附件才会把文件图标放在 `<dt>`，属于新旧一致语义）。
  - 附件与图标跳过：行内 `span a[href*=attachment.php?]` 紧邻 `(256.5 KB)` 吞并至 `Attachment.description` 且不重复进正文；`span` 内含 `nothumb=yes` 时与旧版一致不吞并相邻文本节点而是作为 `description=null` 的 `Attachment`（`div.t_attach` 内的 `nothumb=yes` 被完全跳过）；`dl.t_attachlist p.attachname` 正确提取 `title` 与 `description`；外链图保留；`SmallImages`（`attachments/day_140621/...png`）识别为 `InlineSpan.SmallImage("win", ...)`；站点 UI 图标与 `data:image/png;base64,...` 正确跳过。
- **探针位置**：[B8ProbeTest.kt#L256-L356](file:///home/mir/Documents/hipda-next.wt/V-B8/core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt#L256-L356)、[B8LegacyCompareProbeTest.kt#L238-L278](file:///home/mir/Documents/hipda-next.wt/V-B8/hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt#L238-L278)。

### 探针 d：1 楼投票全变体

- **结论**：**通过（正常表单新旧一致；无表尾行/无 `form#poll` 的 `div.specialmsg`/无 `<label>` 选项新版明显更稳健）**
- **实测表现**：
  - 单选可投（`form#poll` + `formhash` + `p.polltimer` + `div.pollinfo` 去 `<a>` + 柱条占比 + `<button>` 去按钮表尾）与多选投票（`多选投票: ( 最多可选 3 项 )` -> `maxChoices=3, isMultipleChoice=true`）字段完全准确，且 1 楼正文完全无投票 HTML 污染、DOM 零修改。
  - **新版更稳健（探针实测发现旧版缺陷）**：
    1. 投票存在 `form#poll` 且无独立 footer 行（仅选项行 + 占比行）时，旧版 `HiParserThreadDetail.java:241-248` 误把最后一项占比 `"40.00% (4)"` 当成 `PollBean.footer`，新版 `lastRowConsumed` 守卫正确输出 `footer = null`。
    2. 已投过、无 `<input>`、无 `form#poll` 仅保留 `div.pollinfo` + `div.pollchart` 的 `div.specialmsg` 形态下，旧版 `detail.poll` 为 `null` 且将投票文字拼进 `ContentText`，新版正确解析 `ThreadPoll` 并从正文完全隔离。
    3. `<td class="polloption">` 不含 `<label>` 时旧版选项表为空，新版回退 `td.polloption` 正常提取。
- **探针位置**：[B8ProbeTest.kt#L363-L452](file:///home/mir/Documents/hipda-next.wt/V-B8/core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt#L363-L452)、[B8LegacyCompareProbeTest.kt#L280-L329](file:///home/mir/Documents/hipda-next.wt/V-B8/hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt#L280-L329)。

### 探针 e：极端与畸形 HTML 容错

- **结论**：**通过（极端场景不崩溃、无栈溢出/死循环；多处旧版崩溃路径新版安全通过）**
- **实测表现**：
  - 锁定楼层 `div.locked` -> `ContentBlock.Notice`；警告标记 `viewwarning` -> `isWarned = true`；`i.pstatus` 含多次编辑信息剥离至 `postStatus` 且不进正文（含 2 条 `i.pstatus` 时第 1 条进 `postStatus`、第 2 条按普通斜体保留且不崩溃，与旧版对齐）；缺失 `#postlist` -> `hasPostList = false`；空 `td.t_msgfont` -> `[[无内容]]`；无 `div#nav` 或 `div#nav` 缺标题时回退 `div#threadtitle`（排除 `<a>` 且不改 DOM，还解码双重转义 Emoji）；60 层深嵌套 `<div><span>...` 与未知标签 `<video>`/`<svg>` 迭代遍历安全完成，深层文本完整保留。
  - **新版更稳健（探针复现旧版 3 处崩溃）**：
    1. `td.t_msgfont` 仅含单个 `<i class="pstatus">` 时，旧版在 `HiParserThreadDetail.java:298` 移除后于 `303` 行 `postmessageE.childNode(0)` 抛 `IndexOutOfBoundsException`，新版安全返回空 `blocks` 并保留 `postStatus`。
    2. `div#postlist` 内存在换行空白文本节点时，旧版在 `HiParserThreadDetail.java:106-107` 以 `childNodeSize()` 遍历 `.child(i)` 抛 `IndexOutOfBoundsException`，新版使用 `.children()` 正常解析。
    3. 无尾随兄弟节点的最小 DOM 上，旧版 `HiParserThreadDetail.java:319` 的 `while (contentN.parent().nextSibling() == null)` 越过 `level = 0` 爬至 `Document.parent() == null` 抛 `NullPointerException`，新版显式栈仅遍历 `postMessageEl` 子节点，安全完成。
- **探针位置**：[B8ProbeTest.kt#L459-L555](file:///home/mir/Documents/hipda-next.wt/V-B8/core-parser/src/test/kotlin/net/jejer/hipda/verify/B8ProbeTest.kt#L459-L555)、[B8LegacyCompareProbeTest.kt#L331-L392](file:///home/mir/Documents/hipda-next.wt/V-B8/hipda/src/test/java/net/jejer/hipda/verify/B8LegacyCompareProbeTest.kt#L331-L392)。

---

## 3. 本地与 CI 执行记录

### 3.1 本地验证（均遵守 DISPATCH §2 `flock` + 限内存 + `--stop`）

- 命令：`./gradlew :core-parser:test :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.B8LegacyCompareProbeTest" :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
- 结果：`BUILD SUCCESSFUL`
  - `:core-parser:test`：`75 tests, 75 passed, 0 failed, 0 skipped`（其中 `B8ProbeTest`: `6/6`）
  - `:hipda:testDebugUnitTest`（筛选 `B8LegacyCompareProbeTest`）：`5 tests, 5 passed, 0 failed, 0 skipped`
  - `:hipda:assembleDebug`、`:hipda:lintDebug`：全部通过

### 3.2 GitHub Actions CI（最终证据）

- 分支：`verify/B8-probes`（commit `c12c0ec`）
- Run 链接：https://github.com/mailwmj/hipda-next/actions/runs/45616280466 （Job ID `139212396999`，状态 `success`）
- 汇总：`TOTAL: 660 tests, 660 passed, 0 failed, 0 errors, 0 skipped`
  - `net.jejer.hipda.verify.B8ProbeTest`：`6/6` 通过
  - `net.jejer.hipda.verify.B8LegacyCompareProbeTest`：`5/5` 通过
  - `net.jejer.hipda.parser.ThreadDetailParserEquivalenceTest`：`5/5` 通过
  - `assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 一致性与 Roborazzi 截图验证全绿
