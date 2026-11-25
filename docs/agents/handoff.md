# 负责人交接说明（2026-11-25 21:48 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/{tech-plan,backlog,product-design,copy}.md` v0.4（大屏三栏方案 `b4ab916` + kudo 液态玻璃与交互设计修订 `bba139c`，经 R-Dkudo 审查与修订轮 APPROVE `@6f139c4`）；`docs/agents/decisions.md` 已记录 2026-11-25 用户拍板（参考 `kudo-android`、毛玻璃换用 Kyant `backdrop`、同屏上限 `2→3` 等，`0895b83`）。
- **已合并进 `main`**（当前代码 `HEAD`：`18dfa80`；`G2 @5ce63dc` **main CI 绿**）：
  - 第一批：M0-1、M0-2、C1b、D3a、A1、D1、C1；
  - 第二批：M0-3、B1a、A2、D3c、C3、C2（联合 CI 45526302892，223/223）；
  - 第三批与 M1/M2/M3(部分)/M4/三栏/kudo 收口：
    - A3a 拦截器链（`26bb6bf`：R-A3a APPROVE + V-A3a 11 探针 PASS）→ `03d2f01`
    - C5b 反馈组件（R-C5b APPROVE，简化 1120→686 行后复核 APPROVE `dd89039`）→ `3ed1f9e`
    - C4a 自适应外壳（修复后复核 APPROVE + V-C4a PASS `dbb97ae`）→ `92e01d3`
    - C5a 状态组件（简化 816→534 行并修复后复核 APPROVE `8d55671`）→ `461eb91`
    - A3b Cronet 传输（V-A3b PASS + R-A3b APPROVE `@6e91c6e`）→ `fe6b553`
    - A4a Cloudflare 验证页检测器（V-A4a PASS + R-A4a APPROVE `@e75ef76`）→ `294c705`
    - C4b 导航随滚动与键盘隐藏（R-C4b APPROVE + V-C4b PASS `@6e92adc`，C4b-fix1 核对 PASS `@6396593`）→ `4229253`
    - A5a WebView UA 统一与 Cookie 桥（R-A5a 阻断修复后复核 APPROVE `@3c9e3b3`）→ `4d90818`
    - A4b 站点访问网关与独立恢复 Dispatcher（V-A4b 阻断修复后复验 PASS `@137376a`）→ `0247458`
    - A5b 透明 ChallengeActivity 与后台自动过盾（修复轮 1 闭合后复审复验 PASS `@449cf13`）→ `b8c3975`
    - A6 可见验证面板与原地等待/冷却提示（R-A6 APPROVE、V-A6 PASS；收尾轮合并状态机经 R-A5b 复审 `@f0e0a15`）→ `8999d94`
    - B1b debug 样本导出工具页（R-B1b 审查与修复轮复审 PASS `@b9c9cc2`）→ `f7e0bcf`
    - A5c 统一桌面 Linux Chrome UA 与 WebView Client Hints（R-A5c APPROVE `@d6b767c`）→ `15d3e32`
    - D-3pane 大屏与折叠屏三栏自适应布局定稿（R-D3pane 审查及修订轮 APPROVE `@65a1ea5`）→ `b4ab916`
    - B1c 本人独立数字 UID 脱敏扫描补全与 16 个真机脱敏样本 + `manifest.json` 入仓（reviewed `@37df441`；远端临时分支 `samples/b1b` 已删）→ `d5ed2ce`
    - G1 毛玻璃观感调参 + debug 实时调参面板（R-G1 APPROVE `@8352ae1`）→ `7cf0e70`
    - C4c 自适应外壳三栏升级与左侧栏（R-C4c APPROVE + V-C4c 视觉与探针 PASS `@6706b09`）→ `0519096`
    - B9a `:core-parser` 公共基座 + 登录/短消息/提醒解析 + R-B1c S1/S2（R-B9a APPROVE + V-B9a 探针 PASS `@d1263a5`）→ `d45b6a2`
    - B9b 搜索/简单列表/用户资料/黑名单/发帖表单/收藏/上传解析 + B9a 遗留 4 项小修（R-B9b APPROVE `@14dc33d`）→ `c0b8c8c`
    - D-kudo kudo 液态玻璃与交互设计修订至 v0.4（基于 G-kudo 调研，R-Dkudo 复审修订轮 APPROVE `@6f139c4`）→ `bba139c`
    - B8 帖子详情 + 内容块 + 1 楼投票解析器（R-B8 APPROVE + V-B8 PASS `@178e548`；修复轮 1 闭合 S1/S2/S3/S5 核心项经 `B8-fixcheck` PASS `@9a360c8`）→ `cf59951`
    - B7 帖子列表解析器与样本等价测试 + 授权闭合 R-B9b S1/S2/S4（R-B7 APPROVE `@822969a`）→ `db5857d`（main CI 绿 `@db5857d`）
    - D2 `AuthRepository` + `ForumHttpClient` + 代际 Cookie 写门控（R-D2 `@df1c12d` 阻断修复后复审复验 V-D2 PASS `@85ac8b1`）→ `0e5fb57`
    - G2 毛玻璃底层由 Haze 2.1 迁移至 Kyant `backdrop`（API 33+ 折射 / 31–32 纯模糊 / ≤30 降级、上限 3 区域、API 36 release 模拟器冒烟绿；R-G2 APPROVE + V-G2 PASS `@74c1c8c`）→ `5ce63dc`（**main CI 绿 `@5ce63dc`**；真机包清单同步至 `c3344e9`）
    - D8b 用户资料与黑名单 Repository + 分账号本地缓存（R-D8b APPROVE `@040e6e2`）→ `5c71b35`
    - D7 短消息与提醒 Repository + 15s 冷却 + 分离未读流与本地已读状态（R-D7 APPROVE `@437ca88`）→ `18dfa80`
- **阶段验收状态**：
  - **M1 真机验收通过**：用户已在真机使用 A5c 构建完成登录、抓取全部 16 个页面并导出脱敏样本入仓（`d5ed2ce`）。
  - **M2 解析器安全网与 `:core-parser` 全部合入完成**：`B9a`（`d45b6a2`）、`B9b`（`c0b8c8c`）、`B8`（`cf59951`）、`B7`（`db5857d`）全部合入 `main` 且 main CI 绿（`@db5857d`）。
  - **M4 真机验收通过 + Kyant `backdrop` 底层已落地**：`G2` 已合入 `5ce63dc`（main CI 绿），`C4d` 正在落地 `TopBar`/`Sheet` 档位、`hiGlassSource` 下放与底部胶囊拖拽；待用户用真机 debug 实时调参面板实测定值后回写 §5。
- 已合并卡的卡片与报告持续归档至 `docs/agents/cards/`、`docs/agents/reports/`（本轮已归档 `G2`、`R-G2`、`V-G2`、`D8b`、`R-D8b`、`D7`、`R-D7`；`D2`/`R-D2`/`V-D2` 待下轮补档）；工作副本位于 `/home/mir/Documents/hipda-next.wt/_cards/`、`_reports/`，通用条款在 `_cards/_common.md`。

## 2. 进行中与待派发

| 卡号 | 分支 / worktree | 状态 |
|---|---|---|
| C4d 外壳玻璃拓扑下放、`TopBar`/`Sheet` 档位、底部胶囊拖拽与 R-G2/R-C4c 收口 | `card/C4d-shell-glass-polish` / `../hipda-next.wt/C4d` | 实现中（审查卡 `R-C4d.md`、验证卡 `V-C4d.md` 已起草） |
| D4（BATCH-4 Wave 4）帖子列表 Repository + R-B7 S1 + 授权 R-D8b S1–S3 | `card/D4-thread-list-repo` / `../hipda-next.wt/D4` | 实现中（审查卡 `R-D4.md` 已起草，Medium 档无 V 卡） |
| D5（BATCH-4 Wave 4）帖子详情 Repository（浏览历史、阅读位置、1 楼投票、黑名单遮蔽） | `card/D5-thread-detail-repo` / `../hipda-next.wt/D5` | 实现中（审查卡 `R-D5.md` 已起草，Medium 档无 V 卡） |
| D6（BATCH-4 Wave 4）发帖/回复/编辑/上传 Repository（草稿、小尾巴、30s 冷却、R-B9b S3） | `card/D6-post-upload-repo` / `../hipda-next.wt/D6` | 卡片 `D6.md`、`R-D6.md`、`V-D6.md` 已就绪（Complex 档），待槽位空出后派发 |
| OPS-1 负责人助理（文档维护 / 起草卡片 / 核对修改轮） | 主目录 `main`（纯文档） | 常驻复用中 |

- 本机有 Android SDK（mise 安装，见 `lesson.md`），可本地串行录制 Roborazzi 基线。

## 3. BATCH-4 计划与后续主干路径

- **BATCH-4 计划**（已批准并归档至 `docs/agents/cards/BATCH-4.md`，工作副本 `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`）：按 DISPATCH §5 第一性原理收拢为 **12 张卡**、**5 波**推进（同时在跑的实现卡 ≤ 3）：
  1. **Wave 1（已合入）**：`B9a` [Complex] → `d45b6a2`
  2. **Wave 2（全部合入）**：`B9b` [Medium] → `c0b8c8c`、`B8` [Complex] → `cf59951`、`D2` [Complex] → `0e5fb57`（同期 `G2` 换用 `backdrop` → `5ce63dc`）
  3. **Wave 3（全部合入）**：`B7` [Medium] → `db5857d`（**M2 收官**）、`D8b` [Medium] → `5c71b35`、`D7` [Medium] → `18dfa80`
  4. **Wave 4（进行中 → 解锁 M5 阅读核心）**：`D4` [Medium]、`D5` [Medium] 实现中（与 `C4d` 占满 3 并行槽位）；`D6` [Complex] 卡片已就绪待空位
  5. **Wave 5（2 并行 + 1 槽可启 `E1`/`E2a`）**：`D8a` [Medium]（合并 `D3d`：搜索/今日新帖/收藏与关注 + 独立缓存 DataStore）、`D9` [Complex]（WorkManager 提醒轮询，唯一授权修改 `HiApplication`/`AndroidManifest.xml`，并授权顺带修改 `data/message/` 闭合 R-D7 S1–S4）
- **待观察风险**：
  - **Cloudflare 桌面 UA 下 WebView 过验证尚未在真机触发**：A5c 切换为桌面 Linux Chrome UA + Client Hints（`platform=Linux`、`mobile=false`）后，真机 M1 请求均直接 200 通过、未触发 Cloudflare 人机验证；该 UA 组合下的 WebView 自动/人工过盾与 `cf_clearance` 同步路径尚未经真机实测，列为待观察风险，后续真机首次触发验证时重点核实。
  - **Kyant `backdrop` 同屏 3 块液态玻璃 120Hz 帧率**：用户决定将同屏实时玻璃上限由 `2` 放宽至 `3`（`0895b83`），须在真机 120Hz 实测帧率并验证无掉帧。
- 第三批遗留优化（后续卡顺带处理）：R-C5b 复核建议 `HiPressFeedbackNode.onDetach` 复位 `anim`、`displayedCount`/`displayedChar` 改普通引用。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 已合入闭环的条目（含 R-B1c S1/S2、A1 S4 `DiscuzTextDecoder`、B9a 遗留 4 条小修、R-B9b S1/S2/S4、R-B8 S1/S2/S3/S5 核心项、D2 密码转义、R-G1 2 条小修）已移除，下面为**进行中或尚未落地**的条目：

- **→ 解析器小修（等下一张碰 `core-parser` 的卡顺带）**：
  - R-B7 S2：为 `ThreadListParserTest` 补两处页码回退防御分支的合成夹具与变异断言（`ThreadListParser.kt:169` 无 `page=` 的伪静态分页链接如 `<a href="forum-2-5.html">5</a>`，以及 `ThreadListParser.kt:136` 带符号参数 `page=+5` 回退 `1`）。
- **→ D4（实现中，已写入卡片）**：
  - R-B7 S1：`ThreadListParser.kt:61` 为对齐旧实现在缺失 `tid=` 时返回 `""`；`D4` 用 `DiscuzUrlHelper.isValidId(tid)` 过滤空 `tid`，防止 Compose `LazyColumn` 重复空主键崩溃。
  - R-D8b S1/S2/S3（已授权改 `data/user/` 对应文件）：S1 `mutationVersion++` 切号窗口修复；S2 `viewblack` `formhash` 透出与按账号缓存复用并去掉二次 `Jsoup.parse`；S3 补齐 4 处单行变异盲区单测。
- **→ D6（卡片已就绪待空位，已写入卡片）**：
  - R-B9b S3：`PrePostParser` 为对齐旧实现保留原始实体；`D6` 回填编辑器时须对 `subject` / `text` / `quoteText` 做二次数字实体解码还原真实 Emoji，并在 GBK 表单提交时统一重编码为十进制数字实体（闭合编辑/引用态 Emoji 往返）。
- **→ D9（提醒轮询，授权改 `data/message/` 对应文件）**：
  - R-D7 S1：收紧 `DefaultSmsRepository.kt:359-366, 393-395` `UNAUTH_AJAX_MARKERS` 中的 `"无权进行当前操作"`（排除含 `"您已经登录"` 的组受限 `NOPERM`），防止已登录无发信权限时误触发静默重登并错映射为 `AuthRequired`。
  - R-D7 S2：`DefaultNotifyRepository.kt:355-368` `updateFromPageMeta` 在 `promptCounts.notifyTotal == 0` 时同步清理持久化的 `suppressedServerNotifyCount` 水位（与 `pollUnreadCounts` 对齐），防止进程重启后误压制同数量新提醒。
  - R-D7 S3：`DefaultNotifyRepository.kt:302-325` `pollUnreadCounts` 补齐 `!hasContainer && errorMessage`（`alert_error`）守卫，并对 `:320`/`:142` 的 `readStateStore` 写盘加 `IOException` 兜底，防止磁盘异常向 `D9` Worker 抛出未捕获异常。
  - R-D7 S4：补齐 3 处单行变异单测（切号/退登重置 15s 冷却、冷启动未调 `getNotifications()` 即校验 `hydrateAccount` 恢复水位与计数、重登重试成功回显含 `"您还未登录"` 锁死短路分支），并顺带修正 `_reports/D7.md:169` 行号笔误（`:349` → `:354`）。
- **→ C4d（实现中，已写入卡片）**：
  - R-Dkudo 实现提示：① `HiAdaptiveShell` 的 `AnimatedContent` 切页过渡期按 `currentSelected == destination` 门控 `shellGlassState`；② 纯色顶栏页与占位预览页保留默认挂源回退。
  - R-G2 S1/S2/S3：`remember` 缓存 `drawBackdrop` 修饰链、对齐 `Toolbar` 浅色着色 `0.62–0.72` + 补激活态对比度单测 (b)、评估 `vibrancy` 与 `saturation` 双层 `ColorFilter` 合并。
  - R-C4c 4 条建议：① 浮层底层容器补 `.focusGroup()` + `Key.Tab` 单测；② 左滑关闭 `snapTo(0f)` 跳帧修复；③ 常驻侧栏 `AnimatedContent` 不共用 `LazyListState`；④ 临界浮点宽（`599.6f`/`679.6f`/`839.6f`）与非零 `12dp` 折痕宽单测。
- **→ “毛玻璃定稿”小卡（待用户用 `G2`/`C4d` debug 实时调参面板给出最终参数后派发）**：
  - 将用户拍板的最终液态玻璃参数与同屏上限 `3` 回写 `docs/design/product-design.md` §5。
- **→ E2a（帖子详情正文渲染）**：
  - C1b#2：`B8` 已通过 `InlineSpan.Emoji.normalizedKey` 完成表情键归一化（如 `default_smile`），`E2a` 渲染直接传给 `EmojiMap.drawableForImageSrc` / `codeForImageSrc`。
- **→ E2c（引用展开、楼层高亮、长按菜单与 1 楼投票）**：
  - R-B8 S4：`ThreadPoll`（`PollModels.kt:26-41`）当前仅提供 `titleHtml`/`timerHtml`；`E2c` 接入时补充纯文本 `titleText` / `timerText`，避免 UI 层解析 HTML 字符串。
- **→ E4b（底部三段式工具条与内联跳页条）**：
  - R-Dkudo 实现提示：内联跳页条滑块使用 `Modifier.weight(1f)` 自适应填充，**勿写死最小宽（如 `widthIn(min = 140.dp)`）**，确保 `340dp` 分屏窄窗 + `1.25×` 字体缩放下零挤压通过。
- **→ E 线接入验证提示与面板**：
  - R-A6 S3：E 线接入时为 `ChallengeStatusView` 补齐未满 2s 时的原加载指示插槽；验证面板的 `HiTheme` 接入用户主题配置。
  - R-A6 S4：复用 `ChallengeState.effectiveAt`，并将 `HiLoadingSpinner` 公开去重。
  - V-A6：340dp 窄屏 + 2x 字号下验证面板副行过长时做省略处理，避免挤占内容区。
- **→ E1**：V-C1 — 深色模式下已读标题与未读标题区分度偏弱（`10.05:1` vs `13.23:1`），列表页接入时评估拉大已读弱化程度。
- **→ E6**：R-D1 — 在“今日新帖”里点“发新帖”时必须先选定版块（正整数 `fid`）再生成 `DraftKey.newThread(fid)`。
- **→ E7**：C1b#3 — `EmojiGroup.name` 是 `:emoji` 里写死的中文，表情面板分类标签的无障碍 `contentDescription` 需走 `:hipda` 的 `strings_emoji.xml`。
- **→ M5 / M7 性能验收**：R-M0-2 R4 — `tech-plan.md` §4 提到的 `Macrobenchmark` 尚未加入 `libs.versions.toml`；真机 120Hz 帧时间与卡顿率在 M5/M7 阶段核验。
- **→ M7-2**：C1b#1 — 删除旧 `EmojiHandler` 等类时，将 `EmojiMapTest` 固化为 `(code, imageSrc, lightRes, darkRes)` 四元组表驱动测试。

---

## 5. 用户要做的事

1. **液态玻璃参数定值**：`G2` 已合入 `5ce63dc`（真机清单包 `c3344e9`），可安装真机 debug 包（或待 `C4d` 合入含 `TopBar`/`Sheet` 档位后）在「HiPDA 预览 → 外壳」调参面板选定最终液态玻璃参数并点“复制当前参数”发回负责人，用于“毛玻璃定稿”小卡与 §5 回写。
2. **待观察核实**：真机后续首次遇到 Cloudflare 人机验证时，确认桌面 UA 下的 WebView 自动/人工过盾与 `cf_clearance` 同步正常。
3. **阶段验收（M5、M7）**：在折叠屏真机核验 3 块液态玻璃 120Hz 帧率、三栏/半折铰链双栏连续性，以及真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写与恢复。

---

## 6. 空闲的历史会话
第一至三批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的早期版本 `6a8d5dd`）。
