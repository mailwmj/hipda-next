# 负责人交接说明（2026-11-25 10:30 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/tech-plan.md` v0.3、`backlog.md` v0.3、`product-design.md` v0.2.1、`copy.md` v0.2。
- **已合并进 `main`**：第一批 M0-1、M0-2、C1b、D3a、A1、D1、C1；第二批 M0-3、B1a、A2、D3c、C3、C2（联合 CI 45526302892，223/223）；第三批：
  - A3a 拦截器链（判定 `26bb6bf`：R-A3a APPROVE + V-A3a 11 个探针 PASS）→ `03d2f01`
  - C5b 反馈组件（R-C5b APPROVE `2387ded`，按建议简化 1120→686 行后复核 APPROVE `dd89039`）→ `3ed1f9e`
  - C4a 自适应外壳（R-C4a 2 阻断：窄窗二次测量崩溃、预览不保态；修复后复核 APPROVE + V-C4a 视觉复验 PASS `dbb97ae`）→ `92e01d3`（CI 全绿）
  - C5a 状态组件（R-C5a APPROVE `64bf594`，简化 816→534 行并修正主动操作同帧显示、重建保态后复核 APPROVE `8d55671`）→ `461eb91`
- 卡片全文在 `/home/mir/Documents/hipda-next.wt/_cards/<卡号>.md`（含 `-fix1` 修改轮与 `R-`/`V-` 审查验证卡），通用条款在 `_cards/_common.md`。

## 2. 进行中

| 卡号 | 分支 / worktree | 会话 | 状态 |
|---|---|---|---|
| A3b Cronet 传输（HttpEngine → Play 服务 Cronet → OkHttp 兜底） | `card/A3b-cronet` / `../hipda-next.wt/A3b` | 实现 `5ff4f475`；验证 V-A3b `9c4f76e5`；审查 R-A3b `c07cd701` | 交付 `6e91c6e`（CI + 模拟器冒烟绿），验证与审查进行中 |
| C4b 导航随滚动隐藏、键盘隐藏、内容留白、导航项按压缩放、R-C4a 遗留简化 | `card/C4b-shell-scroll` / `../hipda-next.wt/C4b` | 实现 `427be0b9` | 实现中 |

- D3d 暂缓：“收藏缓存”的字段取决于 B9 的收藏解析模型；搜索历史部分可在 D8a 前单独做。
- 本机有 Android SDK（mise 安装，见 lesson.md），可本地录制 Roborazzi 基线。

## 3. 后续主干路径
- A 线：`A3b → A4（验证检测/协程层排队/60s 冷却/独立恢复 Dispatcher/后台直接放弃）→ A5（透明 ChallengeActivity + 不可见 WebView + Cookie 同步 + UA 统一，替换 A3a 的 `UserAgentProvider` 默认绑定）→ A6 → B1b`。**B1b 完成后找用户**：登录、确认抓到版块页（M1 验收）、按清单导出脱敏样本，随后解锁 B2–B9 与 D2/D4–D9。
- C 线：C4b 完成后进入 M4 外壳阶段验收（需要真机核验真实模糊与帧率）。
- 第三批建议（未落实，后续卡带上）：R-C5b 复核建议 `HiPressFeedbackNode.onDetach` 复位 `anim`、`displayedCount`/`displayedChar` 改普通引用；V-C4a 浅色毛玻璃在强对比文字上的真实模糊观感待 M4 真机校准。

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
