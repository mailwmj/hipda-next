# 负责人交接说明（2026-11-25 08:40 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/tech-plan.md` v0.3、`backlog.md` v0.3、`product-design.md` v0.2.1、`copy.md` v0.2。
- **已合并进 `main`（`64fd073`，联合 CI [45526302892](https://github.com/mailwmj/hipda-next/actions/runs/45526302892) 全绿，223/223 测试 + 20 张 Roborazzi 基线）**：M0-1、M0-2、C1b、D3a、A1、D1、C1（第一批），M0-3、B1a、A2、D3c、C3、C2（第二批，判定 commit 依次为 `3435c0a`、`9002385`、`384edb3`、`3173aca`、`e1515c6`、`dbac973`）。联合 CI 已确认三个跨分支组合：`BackupExclusionRulesTest`（A2+D3c）、`MotionTokenScanTest`（C2 扫 C3）、`:core-parser` 27 条 + Room schema 校验。
- 已合并卡的 12 个旧 worktree 与本地分支已清理（远端分支保留）。

## 2. 第三批（进行中，均基于 `64fd073`）

卡片全文在 `/home/mir/Documents/hipda-next.wt/_cards/<卡号>.md`。实现均为 antigravity `gemini-4-neon` xhigh，标签 `team-lead=hipda-next`。

| 卡号 | 分支 / worktree | 实现会话 | 下一步 |
|---|---|---|---|
| A3a 拦截器链（手动重定向、浏览器请求头、逐跳 Cookie） | `card/A3a-interceptors` / `../hipda-next.wt/A3a` | `56c5538b` | 敏感区：Hydrogen 验证 + Carbon 审查 |
| C4a 自适应外壳导航形态 + R-C1 R2 清理 | `card/C4a-shell` / `../hipda-next.wt/C4a` | `a6b69806` | Carbon 审查 + Hydrogen 视觉复验 |
| C5a 状态组件 | `card/C5a-state` / `../hipda-next.wt/C5a` | `d10d3800` | Carbon 审查 |
| C5b 反馈组件 | `card/C5b-feedback` / `../hipda-next.wt/C5b` | `65058fa8` | Carbon 审查 |

- 原 backlog 的 A3 拆成 **A3a**（应用层拦截器，MockWebServer 可测）和 **A3b**（Cronet 传输：Android 14+ HttpEngine → Play 服务 Cronet → OkHttp 回退，关闭 Cronet 自动重定向，`CronetInterceptor` 挂在链末），因为合在一起超出敏感区约 200 行的上限，且 HttpEngine 分支无法在 Robolectric 下测。A3b 等 A3a 判定后从 A3a 分支开始（两者都改 `HttpClientModule.kt`）。
- C 线三卡的文件隔离：只有 C4a 能改 `PreviewActivity.kt` 和 `HiTypography.kt`；只有 C5b 能在 `HiMotionTransitions.kt` 末尾追加 `hi*` 函数；三卡都不改 `MotionTokenScanTest` 白名单；字符串分别在 `strings_shell.xml`、`strings_state.xml`、`strings_feedback.xml`；新基线前缀分别为 `shell_`、`state_`、`feedback_`。三卡合并时预计零文件冲突。
- C4a 不做按压缩放（C5b 提供 `hiPressScale`），滚动隐藏、键盘隐藏、内容底部留白、角标留给 C4b。
- D3d 暂缓：“收藏缓存”的字段取决于 B9 的收藏解析模型，现在做只能猜结构；搜索历史部分可在 D8a 前单独做。

## 3. 后续主干路径
- A 线：`A3a → A3b → A4（验证检测/协程层排队/60s 冷却/独立恢复 Dispatcher/后台直接放弃）→ A5（透明 ChallengeActivity + 不可见 WebView + Cookie 同步 + UA 统一，替换 A3a 的 `UserAgentProvider` 默认绑定）→ A6 → B1b`。**B1b 完成后找用户**：登录、确认抓到版块页（M1 验收）、按清单导出脱敏样本，随后解锁 B2–B9 与 D2/D4–D9。
- C 线：`C4a → C4b`（导航随滚动隐藏、键盘隐藏、内容延伸至毛玻璃下方、按压缩放接入导航项）完成后进入 M4 外壳阶段验收。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 第一、二批中已被 `M0-3`/`D3c`/`C2`/`C3` 关闭的建议已剔除，下面均为**尚未落地**的条目：

- **→ C4a（已写入卡片）**：R-C1 R2 — `HiTypography.kt` 删除样式级 `capFontScale`/`cappedForChrome`（统一使用子树级 `HiCappedFontScale`）、删除未改动的 M3 默认 `label*` 槽位、删除 M0-2 占位 `ComposeFoundationPreview.kt` 及其单测 `ComposeFoundationPreviewTest.kt`（一并清掉 `createComposeRule` v1→v2 弃用警告）。
- **→ A3a（已写入卡片）/ A4**：A2 备注 — OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会通过 `AssetManager` 读 `PublicSuffixDatabase.list`，单测需用 Robolectric 并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。
- **→ B1b**：R-B1a 建议 8 — 给用户的导出清单里必须明确提示：“我的帖子 / 我的回复 / 收藏”含真实 `tid`/`pid` 可在论坛反查发帖人，建议挑选公开普通帖或使用测试号导出。
- **→ B7 / B8 / B9**：
  - A1 S4（**重要**）：Discuz! 7.2 很可能把 6 位十进制实体（`&#128512;` 等补充平面 Emoji）二次转义为 `&amp;#128512;`（旧版在 jsoup `.text()`/`.attr()` 之后仍须调 `EmojiParser.parseToUnicode`，见 `HiParserThreadList.java:88`、`HiParserThreadDetail.java:89`、`PostFragment.java:701-705`、`TextViewWithEmoticon.java:112`）。新解析器与 E2a 渲染必须做第二次实体解码并用真实样本验证（需真机确认）。
  - B1a 备注：jsoup 1.24.1 的 `selectFirst`/`first`/`last` 含 `@org.jspecify.annotations.Nullable`，Kotlin 2.4 泛型推断会报错；`:core-parser` 内继续用 `select(selector).firstOrNull()` 规避（或以后加 `compileOnly("org.jspecify:jspecify")`）。
  - C1b#2：`EmojiMap.drawableForImageSrc` / `codeForImageSrc` 接收归一化键（`default_smile`），旧版在 `TextViewWithEmoticon.java:86-89` 先去扩展名并把 `/` 换为 `_`；B8 与 E2a 卡需写明归一化归属。
- **→ E1**：V-C1 — 深色模式下已读标题与未读标题区分度偏弱（`10.05:1` vs `13.23:1`），列表页接入时评估拉大已读弱化程度。
- **→ E6**：R-D1 — 在“今日新帖”里点“发新帖”时必须先选定版块（正整数 `fid`）再生成 `DraftKey.newThread(fid)`。
- **→ E7**：C1b#3 — `EmojiGroup.name` 是 `:emoji` 里写死的中文，表情面板分类标签的无障碍 `contentDescription` 需走 `:hipda` 的 `strings_emoji.xml`。
- **→ M4 性能验收**：R-M0-2 R4 — `tech-plan.md` §4 提到的 `Macrobenchmark` 尚未加入 `libs.versions.toml`；且 API 31+ 真机模糊观感与帧率需在 M4 阶段真机核验。
- **→ M7-2**：C1b#1 — 删除旧 `EmojiHandler` 等类时，将 `EmojiMapTest` 固化为 `(code, imageSrc, lightRes, darkRes)` 四元组表驱动测试。

---

## 5. 用户要做的事

1. **B1b 完成后**：在 debug 样本导出工具页登录 → 确认能抓到版块页（M1 验收）→ 按清单一键导出脱敏样本。
2. **阶段验收（M4、M5、M7）**：有真机（尤其是折叠屏）时，验证 API 31+ 真实毛玻璃效果与帧率、折叠展开连续性，以及真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写与恢复。

---

## 6. 空闲的历史会话
第一、二批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的上一版本 `6a8d5dd`）。
