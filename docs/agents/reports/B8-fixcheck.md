# 小修核对报告（fixcheck）：B8 修复轮 1

- **核对对象**：`origin/card/B8-parser-thread-detail` @ `9a360c8b3404e53cbccb7f8f06e71e256de2d6cb`（相对初版 `178e548a7847eae4239c0b972db37094621917e6`）
- **CI 核实**：[Android CI #45619352724](https://github.com/mailwmj/hipda-next/actions/runs/45619352724)（Job `139223039732` `Build, Lint & Unit Tests`）
  - `headBranch`：`card/B8-parser-thread-detail`
  - `headSha`：`9a360c8b3404e53cbccb7f8f06e71e256de2d6cb`（与 `git rev-parse 9a360c8` 精确一致）
  - `status / conclusion`：`completed / success`（Build & Lint、Room Schema、全量单元测试、Roborazzi 校验、APK 上传全部通过）
- **总判定**：**PASS**

---

## 一、改动边界核查

- `git diff --stat 178e548..9a360c8`（4 个文件，`+330 / -23`）：
  - `core-parser/src/main/kotlin/net/jejer/hipda/parser/threaddetail/ThreadDetailParser.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/threaddetail/ContentBlockParserTest.kt`
  - `core-parser/src/test/kotlin/net/jejer/hipda/parser/threaddetail/ThreadDetailParserTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/parser/ThreadDetailParserEquivalenceTest.kt`
- `git diff --stat origin/main...9a360c8`：共 8 个新增文件，全部位于 `core-parser/.../parser/threaddetail/` 与 `hipda/src/test/.../parser/ThreadDetailParserEquivalenceTest.kt`，**零越界改动**，未引入审查项以外的无关改动。

---

## 二、逐项对照 `_reports/R-B8.md`（S1 / S2 / S3 / S5）

| 条目 | 状态 | 代码与测试核实（文件:行号） | 说明 |
|---|---|---|---|
| **S1**（`forumBaseUrl == null` 保留站内相对链接并扩展识别） | **落实** | `ThreadDetailParser.kt:1126-1186`；`ContentBlockParserTest.kt:326-367` | `normalizeLinkUrl` 移除 `forumBaseUrl != null` 前置门禁，`isRelativeSiteUrl` 覆盖 `/`、`./`、`../`、`?`、`#` 与无 `/` 的 `*.php`（含 `search.php`/`pm.php`/`my.php`/`viewthread.php`，不误判 `example.com/search.php`）；`resolveForumUrl` 在 `forumBaseUrl == null` 原样保留相对路径，提供时 `/` 按 `extractOrigin` 解析、`*.php` 按 `forumBaseUrl` 解析，`//` 补 `https:`。单测覆盖 8 类链接 × 有/无 `forumBaseUrl` 两态。 |
| **S2**（净化 `Quote.authorAndTime`/`time` 尾部回跳链接与破损 BBCode） | **落实** | `ThreadDetailParser.kt:50-54, 922-929`；`ThreadDetailParserTest.kt:88-107`；`ContentBlockParserTest.kt:116-140`；`ThreadDetailParserEquivalenceTest.kt:101-109, 365-384` | 在局部 `.clone()` 提取 `targetTid`/`targetPid` 后先 `metaFont.select("a").remove()` 再以 `QUOTE_META_TRAILING_BBCODE_REGEX` 剥离尾部 `[img]`/`[url]`/`images/common/back.gif` 并 `.trim()`，调用方 DOM 零修改。真机 `thread_p1.html` 第 49 楼（`post_53529463`）精确产出 `authorAndTime = "p84102 发表于 21:17"`、`time = "21:17"`，且不丢失 `targetTid="2602293"` / `targetPid="53529418"`。 |
| **S3**（紧邻行内元素折叠保留单个空格且不生块首尾空白） | **落实** | `ThreadDetailParser.kt:698-702, 751-790, 1191-1319`；`ContentBlockParserTest.kt:369-440` | `processTextNode` 将纯空白 `TextNode` 及 `URL_REGEX` 间空白改为 `markInlineSpace`，`"td"/"th"` 同步改用 `markInlineSpace(TextFormat.PLAIN)`；`BlockListBuilder` 通过延迟 `pendingSpaceFormat` 仅在后继行内元素到达且两侧尚无空白时输出单个 `" "`，在 `<br>` 与块首尾裁剪 ASCII `' '` 并保留 `&nbsp;`（`\u00a0`）。单测覆盖 4 类行内/换行/块级边界组合。 |
| **S5**（补齐块间交错顺序、表格空格与相对链接等盲区断言） | **落实（主项全齐，附 1 条备忘）** | `ContentBlockParserTest.kt:326-367, 443-473`；`ThreadDetailParserEquivalenceTest.kt:97-99` | 已补齐：① “文本→图片→文本→引用→文本” 5 块精确顺序与真机 `thread_p1` 1 楼 `["Text", "Image", "Text"]` 顺序断言；② 无源码空白 `<table><tr><td>格1</td><td>格2</td></tr>` 单空格分隔且行首无空白断言；③ `forumBaseUrl` 两态相对链接断言。<br>**备忘（非阻断）**：`R-B8.md` S5 末句提到的 `ThreadDetailParserEquivalenceTest.kt:349-351` 仍保留 `if (newFloor.signature != null)`（与 `_reports/B8.md` 第七节“S5 两项补强”口径一致）；核查 `SignatureContainer.java:11-19` 为静态 `uid` 级全局缓存，同作者无签名楼层会被旧缓存污染，而 `ThreadDetailParserTest` 已有签名正向校验，不影响本轮合入。 |

---

## 三、新旧等价测试差异合理性核查

- `ThreadDetailParserEquivalenceTest.kt:365-384` 将引用块校验由 `oldQuotes[i].content == newCombined` 调整为：`author`/`targetTid`/`targetPid` 严格相等、`oldQuotes[i].content.startsWith(newCombined)`，且当无尾缀差异（`oldQuotes[i].content == newCombined`）时严格断言 `oldQuotes[i].time == newQuotes[i].time`；并在 `thread_p1` 主测试（`:101-109`）明文验证旧 49 楼含 `back.gif` 而新版为 `"21:17"`。
- 实核真机样本 `thread_p1.html` `post_53529463` 原始 HTML：
  `<font size="2"><font color="#999999">p84102 发表于 21:17</font><a href="https://www.4d4y.com/forum/redirect.php?goto=findpost&amp;pid=53529418&amp;ptid=2602293" target="_blank">[img][url]https://www.hi-pda.com/forum/images/common/back.gif</a>[/img][/url]</font>`
  对照旧 `ContentQuote.java:20`（`mQuote = postText + authorAndTime`），差异完全源于旧解析器未清理 `font[size=2]` 内回跳 `<a>` 与历史破损 BBCode 尾缀；其余 5 处真机引用新旧完全逐字相等。`_reports/B8.md` §7.2 差异说明与代码完全吻合、理由充分。

---

## 四、临时变异抽查验证（3 项全红，均已还原并清理临时工作树）

在独立临时 detached worktree（`/tmp/b8-fixcheck-wt` @ `9a360c8`，按 DISPATCH §2 `flock` 串行执行，结束后 `./gradlew --stop` 并移除该临时工作树，未触动主目录与 `B8` worktree）抽查 3 项临时破坏：

| 抽查项 | 临时改坏位置（`ThreadDetailParser.kt`） | 执行命令 | 退出码 | 变红用例 |
|---|---|---|---|---|
| **变异 A（S5）** | 移除 `BlockListBuilder.addBlock` 内的 `flushPendingText()` | `:core-parser:test --tests "...ContentBlockParserTest" --rerun-tasks` | `1`（FAIL） | `interleavedBlocks_preserveTextImageTextOrderAndTableCellSpacing`、`inlineWhitespace_preservesSingleSpaceBetweenInlineElementsWithoutBlockEdgeSpaces`（2/11 失败） |
| **变异 B（S1）** | `normalizeLinkUrl` 恢复 `forumBaseUrl != null && isRelativeSiteUrl(lower)` | `:core-parser:test --tests "...ContentBlockParserTest" --rerun-tasks` | `1`（FAIL） | `relativeLinks_preserveRelativePathsWhenForumBaseUrlIsNullAndResolveWhenProvided`（1/11 失败） |
| **变异 C（S2）** | `parseQuoteElement` 跳过 `metaFont.select("a").remove()` 与正则剥离 | `:core-parser:test --tests "...ThreadDetailParserTest" --rerun-tasks` | `1`（FAIL） | `parse_realSampleThreadP1Floor49Quote_stripsAnchorAndBrokenBbcodeSuffixFromAuthorAndTime`（1/7 失败） |

---

## 五、结论

**PASS** — `9a360c8` 与 CI `#45619352724` 精确匹配、零越界改动，S1/S2/S3 与 S5 核心断言均切实落地、3 组临时变异即刻变红，且新旧等价测试的引用块差异有充分真机样本依据，可合入 `main`。
