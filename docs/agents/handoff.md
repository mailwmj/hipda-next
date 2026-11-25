# 负责人交接说明（2026-11-25 20:30 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/{tech-plan,backlog,product-design,copy}.md` v0.4（大屏三栏方案 `b4ab916` + kudo 液态玻璃与交互设计修订 `bba139c`，经 R-Dkudo 审查与修订轮 APPROVE `@6f139c4`）；`docs/agents/decisions.md` 已记录 2026-11-25 用户拍板（参考 `kudo-android`、毛玻璃换用 Kyant `backdrop`、同屏上限 `2→3` 等，`0895b83`）。
- **已合并进 `main`**（当前代码 `HEAD`：`db5857d`，**main CI 绿 `@db5857d`**）：
  - 第一批：M0-1、M0-2、C1b、D3a、A1、D1、C1；
  - 第二批：M0-3、B1a、A2、D3c、C3、C2（联合 CI 45526302892，223/223）；
  - 第三批与 M1/M2/M4/三栏/kudo 设计收口：
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
- **阶段验收状态**：
  - **M1 真机验收通过**：用户已在真机使用 A5c 构建完成登录、抓取全部 16 个页面并导出脱敏样本入仓（`d5ed2ce`）。
  - **M2 解析器安全网与 `:core-parser` 全部合入完成**：`B9a`（`d45b6a2`）、`B9b`（`c0b8c8c`）、`B8`（`cf59951`）、`B7`（`db5857d`）全部合入 `main` 且 main CI 绿（`@db5857d`）。
  - **M4 真机验收通过**：外壳、导航与折叠屏功能在 Galaxy Z Fold8 真机验收通过；用户随后拍板参考 `kudo-android` 将毛玻璃底层由 Haze 2.1 换为 Kyant `backdrop`（`0895b83`），设计稿修订已合入 `bba139c`，由 `G2`（及后续 `C4d`）实施并在完成后用 debug 实时调参面板实测定值、回写 §5。
- 已合并卡的卡片与报告均已归档至 `docs/agents/cards/`、`docs/agents/reports/`（本轮已同步 `B9b`、`R-B9b`、`B8`、`R-B8`、`V-B8`、`B8-fixcheck`、`B7`、`R-B7`、`G-kudo`、`D-kudo`、`R-Dkudo`）；工作副本位于 `/home/mir/Documents/hipda-next.wt/_cards/`、`_reports/`，通用条款在 `_cards/_common.md`。

## 2. 进行中与待派发

| 卡号 | 分支 / worktree | 状态 |
|---|---|---|
| D2（BATCH-4 Wave 2）`AuthRepository` + `ForumHttpClient` | `card/D2-auth-repository` / `../hipda-next.wt/D2` | 修复轮 1 `@85ac8b1` 复审（R-D2）与复验（V-D2）中 |
| G2 毛玻璃底层迁移至 Kyant `backdrop`（含四路降级与调参） | `card/G2-kyant-backdrop` / `../hipda-next.wt/G2` | 实现中（基于 `0895b83`；审查卡 `R-G2.md`、验证卡 `V-G2.md` 已起草） |
| D8b（BATCH-4 Wave 3）用户资料与黑名单 Repository | `card/D8b-user-blacklist-repo` / `../hipda-next.wt/D8b` | 卡片 `D8b.md` 与 `R-D8b.md` 已起草（Medium 档，无 V 卡），待 `D2` 合入后派发 |
| D7（BATCH-4 Wave 3）短消息与提醒 Repository | `card/D7-sms-notify-repo` / `../hipda-next.wt/D7` | 卡片 `D7.md` 与 `R-D7.md` 已起草（Medium 档，无 V 卡），待 `D2` 合入后派发 |
| OPS-1 负责人助理（文档维护 / 起草卡片 / 核对修改轮） | 主目录 `main`（纯文档） | 常驻复用中 |

- 本机有 Android SDK（mise 安装，见 `lesson.md`），可本地串行录制 Roborazzi 基线。

## 3. BATCH-4 计划与后续主干路径

- **BATCH-4 计划**（已批准并归档至 `docs/agents/cards/BATCH-4.md`，工作副本 `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`）：按 DISPATCH §5 第一性原理收拢为 **12 张卡**、**5 波**推进（同时在跑的实现卡 ≤ 3）：
  1. **Wave 1（已合入）**：`B9a` [Complex] → `d45b6a2`
  2. **Wave 2（收尾中）**：`B9b` [Medium] 已合入 → `c0b8c8c`；`B8` [Complex] 已合入 → `cf59951`；`D2` [Complex] 修复轮 1 `@85ac8b1` 复审复验中（另并行 `G2` 换用 `backdrop`）
  3. **Wave 3（M2 已收官，D 线待 `D2` 合入齐发）**：`B7` [Medium] 已合入 → `db5857d`（**M2 收官**）；`D8b` [Medium]（用户资料+黑名单缓存）、`D7` [Medium]（短消息+提醒 Repository，独立双未读数）卡片均已预起草
  4. **Wave 4（3 并行 → 解锁 M5 阅读核心）**：`D4` [Medium]（帖子列表 Repository）、`D5` [Medium]（帖子详情 Repository）、`D6` [Complex]（发帖/回复/编辑/上传 Repository）
  5. **Wave 5（2 并行 + 1 槽可启 `E1`/`E2a`）**：`D8a` [Medium]（合并 `D3d`：搜索/今日新帖/收藏与关注 + 独立缓存 DataStore）、`D9` [Complex]（WorkManager 提醒轮询，唯一授权修改 `HiApplication`/`AndroidManifest.xml`）
- **待观察风险**：
  - **Cloudflare 桌面 UA 下 WebView 过验证尚未在真机触发**：A5c 切换为桌面 Linux Chrome UA + Client Hints（`platform=Linux`、`mobile=false`）后，真机 M1 请求均直接 200 通过、未触发 Cloudflare 人机验证；该 UA 组合下的 WebView 自动/人工过盾与 `cf_clearance` 同步路径尚未经真机实测，列为待观察风险，后续真机首次触发验证时重点核实。
  - **Kyant `backdrop` 同屏 3 块液态玻璃 120Hz 帧率**：用户决定将同屏实时玻璃上限由 `2` 放宽至 `3`（`0895b83`），须在真机 120Hz 实测帧率并验证无掉帧。
- 第三批遗留优化（后续卡顺带处理）：R-C5b 复核建议 `HiPressFeedbackNode.onDetach` 复位 `anim`、`displayedCount`/`displayedChar` 改普通引用。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 已合入闭环的条目（含 R-B1c S1/S2、A1 S4 `DiscuzTextDecoder`、B9a 遗留 4 条小修、R-B9b S1/S2/S4、R-B8 S1/S2/S3/S5 核心项）已移除，下面为**进行中或尚未落地**的条目：

- **→ 解析器小修（等下一张碰 `core-parser` 的卡顺带）**：
  - R-B7 S2：为 `ThreadListParserTest` 补两处页码回退防御分支的合成夹具与变异断言（`ThreadListParser.kt:169` 无 `page=` 的伪静态分页链接如 `<a href="forum-2-5.html">5</a>`，以及 `ThreadListParser.kt:136` 带符号参数 `page=+5` 回退 `1`）。
- **→ D4（帖子列表 Repository）**：
  - R-B7 S1：`ThreadListParser.kt:61` 为对齐旧实现在缺失 `tid=` 时返回 `""`；`D4` 组装列表状态与写入浏览历史前须用 `DiscuzUrlHelper.isValidId(tid)` 过滤空 `tid`，防止 Compose `LazyColumn` 重复空主键崩溃。
- **→ D6（发帖、回复、编辑与图片上传 Repository）**：
  - R-B9b S3：`PrePostParser` 为对齐旧实现保留原始实体；`D6` 回填编辑器时须对 `subject` / `text` / `quoteText` 做二次数字实体解码还原真实 Emoji，并在 GBK 表单提交时统一重编码为十进制数字实体（闭合编辑/引用态 Emoji 往返）。
- **→ D2（修复轮 1 `@85ac8b1` 复审复验中，已写入卡片）**：
  - 旧 `LoginHelper.java:175-177` 密码转义 `.replace("'", "\'")` 在 Java 字面量中实际不转义单引号（仅转义 `\` 与 `"`），`D2` 须用对照测试锁定精确 MD5 语义。
- **→ C4d（外壳玻璃源下放、底部胶囊拖拽与 `TopBar`/`Sheet` 档位补齐）**：
  - R-Dkudo 实现提示：① `HiAdaptiveShell` 的 `AnimatedContent` 切页过渡期存在进出场两个目的地并存，须门控 `shellGlassState` 仅对当前目标页暴露非空（避免双 `LazyColumn` 同时写入同一 `LayerBackdrop`）；② 保留默认挂源回退（页面未声明自管透明顶栏时仍由内层默认槽位挂 `shellGlassState`），防止占位预览页与纯色顶栏页漏挂导致底部胶囊无采样内容。
- **→ “毛玻璃定稿”小卡（待用户用 `G2` 升级后的 debug 实时调参面板给出最终参数后派发）**：
  - R-G1 2 条建议：① 浅色双层 `Modifier.border` 叠放顺序对调（已同步写入 `G2` 顺带收口，届时复核）；② `EndEdgeOnly` `Brush` 缓存（C4c 已删该分支，`GlassTuningPanel` 彩虹 `Brush` 缓存同步写入 `G2` 收口）。
  - 将用户拍板的最终液态玻璃参数与同屏上限 `3` 回写 `docs/design/product-design.md` §5（并按需补降级态半透明指示器叠层对比度单测）。
- **→ E2a（帖子详情正文渲染）**：
  - C1b#2：`B8` 已通过 `InlineSpan.Emoji.normalizedKey` 完成表情键归一化（如 `default_smile`），`E2a` 渲染直接传给 `EmojiMap.drawableForImageSrc` / `codeForImageSrc`。
- **→ E2c（引用展开、楼层高亮、长按菜单与 1 楼投票）**：
  - R-B8 S4：`ThreadPoll`（`PollModels.kt:26-41`）当前仅提供 `titleHtml`/`timerHtml`；`E2c` 接入时补充纯文本 `titleText` / `timerText`，避免 UI 层解析 HTML 字符串。
- **→ E3（三栏内容容器）**：
  - R-C4c 4 条建议：① `240dp` 浮层底层容器补 `.focusGroup()` 彻底锁定外接键盘 `Tab` 焦点；② 浮层左滑关闭 `snapTo` 跳帧收口；③ `AnimatedContent` 切换形态时共用 `LazyListState` 避免状态冲突；④ `calculateShellPaneLayout` 补临界点下沿浮点宽（如 `599.6f`/`679.6f`/`839.6f`）单测。
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

1. **液态玻璃参数定值**：待 `G2` 合入后，安装真机 debug 包在「HiPDA 预览 → 外壳」调参面板选定最终液态玻璃（含折射/模糊）参数并点“复制当前参数”发回负责人，用于“毛玻璃定稿”小卡与 §5 回写。
2. **待观察核实**：真机后续首次遇到 Cloudflare 人机验证时，确认桌面 UA 下的 WebView 自动/人工过盾与 `cf_clearance` 同步正常。
3. **阶段验收（M5、M7）**：在折叠屏真机核验 3 块液态玻璃 120Hz 帧率、三栏/半折铰链双栏连续性，以及真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写与恢复。

---

## 6. 空闲的历史会话
第一至三批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的早期版本 `6a8d5dd`）。
