# 负责人交接说明（2026-11-26 04:55 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/{tech-plan,backlog,product-design,copy}.md` v0.4（大屏三栏方案 `b4ab916` + kudo 液态玻璃与交互设计修订 `bba139c`，经 R-Dkudo 审查与修订轮 APPROVE `@6f139c4`）；`docs/agents/decisions.md` 已记录 2026-11-25 用户拍板（参考 `kudo-android`、毛玻璃换用 Kyant `backdrop`、同屏上限 `2→3` 等，`0895b83`）；`docs/agents/DISPATCH.md` §4 与 `lesson.md` 已固化站点内容 scheme 白名单阻断规则（`00cb3c4`）。
- **已合并进 `main`**（当前代码 `HEAD`：`c0fc15d`；**W2 全部收齐且 W3a `E2b` 已合入**，main CI `45706618318` 绿，已具备里程碑 A 真机验证包）：
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
    - D4 帖子列表 Repository + 置顶/黑名单/已读过滤与自动补页 + 闭合 R-B7 S1 与 R-D8b S1–S3（R-D4 APPROVE `@990e3a6`）→ `243aee8`
    - C4d 外壳玻璃拓扑下放、`TopBar`/`Sheet` 档位、底部胶囊拖拽与 R-G2/R-C4c 收口（R-C4d APPROVE + V-C4d PASS `@0554483`）→ `21b1e3c`（真机包清单同步至 `e1eb834`）
    - `fix/D7-notify-poll-test` 修复 D7 提醒/短消息仓储切号空帧假退登根因（reviewed `@075f053`）→ `2a3f7ca`
    - D5 帖子详情 Repository + 浏览历史/阅读位置/1 楼投票/黑名单遮蔽（R-D5 APPROVE `@65000ff`；修复轮 1 `@ec0a5e8` 闭合 S1–S4 经 `D5-fixcheck` PASS）→ `e71c3fb`（**main CI `45650433258` 绿**，push 事件未触发，手动 dispatch）
    - D6 发帖/回复/编辑/上传 Repository + 草稿/小尾巴/30s 冷却 + 闭合 R-B9b S3（R-D6 APPROVE + V-D6 PASS `@ce4840e`）→ `99879a1`
    - D9 提醒轮询 WorkManager + HiltWorker + 免打扰 + 遇验证放弃 + 闭合 R-D7 S1–S4（R-D9 APPROVE + V-D9 PASS `@933a5ac`）→ `3600f45`（**main CI `45655134227` 绿**）
    - D8a 搜索、今日新帖、收藏与关注 Repository + D3d 缓存（R-D8a 复审轮 2 `@6046d62` 闭合 B1 与 S1–S4，APPROVE，不设 V）→ `cbcb9c0`（**main CI `45659929460` 绿**）
    - E0 M5 阅读核心端到端 Tracer Bullet 与 E 线不可逆公共基座（R-E0 复审轮 2 APPROVE + V-E0 复验轮 2 PASS `@fed1070`）→ `db7dba6`
    - E15 登录页、临时退出登录出口与 `R-A6 S3/S4` / `V-A6` 收口（R-E15 APPROVE + V-E15 PASS `@88f6323`；建议修复轮 1 `@e7029fa` fixcheck PASS）→ `65e627f`
    - E2a 帖子详情核心完整版（透明顶栏、Compose 原生正文、表情/三主题调色、双层链接 scheme 白名单、`PostImageBlock` 接缝；R-E2a 复审轮 2 APPROVE `@332c31c` + V-E2a PASS `@60bd5b7`）→ `3dd65c4`
    - E1 论坛页完整版（透明吸顶折叠顶栏、荧光笔标签、`今日新帖`、新帖胶囊、窄中栏、跨号缓存隔离与深色骨架修复；R-E1 复审轮 2 APPROVE + V-E1 复验轮 2 PASS `@e24e3c0`）→ `b7557bf`（**W2 全部收齐**）
    - E2b 帖子图片渲染（按显示尺寸解码、比例缓存、三分支无跳动补偿、省流点击加载与 `http`/`https` 白名单；R-E2b APPROVE + V-E2b PASS `@1597b4e`）→ `c0fc15d`（**main CI `45706618318` 绿**）
- **阶段验收状态**：
  - **M1 真机验收通过**：用户已在真机使用 A5c 构建完成登录、抓取全部 16 个页面并导出脱敏样本入仓（`d5ed2ce`）。
  - **M2 解析器安全网与 `:core-parser` 全部合入完成**：`B9a`（`d45b6a2`）、`B9b`（`c0b8c8c`）、`B8`（`cf59951`）、`B7`（`db5857d`）全部合入 `main` 且 main CI 绿（`@db5857d`）。
  - **M3 数据层全部合入完成**：`D1`/`D2`/`D3a`/`D3c`/`D4`/`D5`/`D6`/`D7`/`D8a`（含 D3d 缓存）/`D8b`/`D9` 已全部合入 `main`（`D8a @ cbcb9c0` main CI `45659929460` 绿；剩余小修归入 `D-cleanup`）。
  - **M4 真机验收通过 + Kyant `backdrop` 底层与 `C4d` 外壳微调全部合入**：`G2`（`5ce63dc`）、`C4d`（`21b1e3c`）均已合入；待用户用新包（`c0fc15d`）在调参面板实测定值后回写 §5。
  - **M5 里程碑 A 真机验证包就绪**：W2（`E1`/`E2a`/`E15`）已收齐且 `E2b` 已合入 `c0fc15d`（CI `45706618318` 绿），可执行操作清单已同步至 `docs/device-check.md`。
- 已合并卡的卡片与报告持续归档至 `docs/agents/cards/`、`docs/agents/reports/`（本轮已归档 `E1`/`E2a`/`E15`/`E2b` 全部实现/R/V 卡与报告及 `V-E1/`、`V-E2a/` 截图目录；`D4`/`C4d` 待下轮补档）；工作副本位于 `/home/mir/Documents/hipda-next.wt/_cards/`、`_reports/`，通用条款在 `_cards/_common.md`。

## 2. 进行中与待派发

| 卡号 | 分支 / worktree | 状态 |
|---|---|---|
| E2d（BATCH-5 Wave 3a）详情双向连续加载与向上插页零闪跳 | `card/E2d-thread-continuous-paging` / `/home/mir/Documents/hipda-next.wt/E2d` | **修复轮进行中**（同帧竞态 + 测试首帧断言） |
| E3（BATCH-5 Wave 3a）多栏内容容器与全形态连续性（含 `R-C4d S1–S3`、`R-E0 R2-S3/S4`） | `card/E3-multipane-container` / `/home/mir/Documents/hipda-next.wt/E3` | **实现中**（基于含 E1 基线 `b7557bf`） |
| OPS-1 负责人助理（文档维护 / 起草卡片 / 核对修改轮） | 主目录 `main`（纯文档） | 常驻复用中 |

- 本机有 Android SDK（mise 安装，见 `lesson.md`），可本地串行录制 Roborazzi 基线。

## 3. BATCH-4 / BATCH-5 计划与后续主干路径

- **BATCH-4 计划（已全部合入收官，归档至 `docs/agents/cards/BATCH-4.md`）**：12 张卡、5 波全部合入 `main`：
  1. **Wave 1–3（全部合入）**：`B9a` → `d45b6a2`、`B9b` → `c0b8c8c`、`B8` → `cf59951`、`D2` → `0e5fb57`、`B7` → `db5857d`（**M2 收官**）、`D8b` → `5c71b35`、`D7` → `18dfa80`（同期 `G2` → `5ce63dc`、`C4d` → `21b1e3c`）
  2. **Wave 4（全部合入）**：`D4` → `243aee8`、`D5` → `e71c3fb`（main CI `45650433258` 绿）、`D6` → `99879a1`
  3. **Wave 5（M3 全部收官）**：`D9` → `3600f45`（main CI `45655134227` 绿）、`D8a` → `cbcb9c0`（main CI `45659929460` 绿）
- **BATCH-5 计划**（已归档至 `docs/agents/cards/BATCH-5.md`，已按 `R-BATCH5` 修订并按风险拆出 `E2d`）：M5 阅读核心 **9 卡 4 波**：
  - **Wave 1（已合入）**：`E0` → `db7dba6`
  - **Wave 2（已全部收齐 → 里程碑 A 包 `c0fc15d` 就绪）**：`E15` → `65e627f`、`E2a` → `3dd65c4`、`E1` → `b7557bf`
  - **Wave 3a（`E2b` 已合入，`E2d`/`E3` 在途）**：`E2b` → `c0fc15d`（CI `45706618318` 绿）；`E2d` 修复轮进行中（同帧竞态 + 测试首帧）；`E3` 实现中
  - **Wave 3b（待 `E2d` 合入后派发，避免 2 文件重叠）**：`E2c`（引用/高亮/长按菜单/1 楼投票 + `R-B8 S4` & `R-B7 S2` + `R-E2a R2-S2` + `R-E2b S3`）
  - **Wave 4**：`E4`（阅读位置记忆门闩/三段工具条/内联跳页 + `R-C5b` + `R-E2b S3` → 达里程碑 B 完整验收包）
- **待观察风险**：
  - **Cloudflare 桌面 UA 下 WebView 过验证尚未在真机触发**：A5c 切换为桌面 Linux Chrome UA + Client Hints（`platform=Linux`、`mobile=false`）后，真机 M1 请求均直接 200 通过、未触发 Cloudflare 人机验证；该 UA 组合下的 WebView 自动/人工过盾与 `cf_clearance` 同步路径尚未经真机实测，列为待观察风险，后续真机首次触发验证时重点核实。
  - **安全提问报错文案格式（`R-E15 S5`）**：若真机 4d4y.com 对安全提问错返回不含 `"登录失败"` 前缀的原文，需记录原文以供 `LoginParser` 补配（已列入 `docs/device-check.md` 第 1 项）。
  - **快速惯性滑行途中上方图片加载完的顿停观察（`R-E2b S2`）**：`PostImageBlock` 补偿调用 `requestScrollToItem` 在 fling 途中可能抢占 `MutatorMutex` 打断惯性滑行，已列入 `docs/device-check.md` 第 6 项真机观察。
  - **Kyant `backdrop` 同屏 3 块液态玻璃 120Hz 帧率**：用户决定将同屏实时玻璃上限由 `2` 放宽至 `3`（`0895b83`），须在真机 120Hz 实测帧率并验证无掉帧。
- 第三批遗留优化（归入 `E4` 顺带处理）：R-C5b 复核建议 `HiPressFeedbackNode.onDetach` 复位 `anim`、`displayedCount`/`displayedChar` 改普通引用。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 已合入闭环的条目（含 R-B1c S1/S2、A1 S4 `DiscuzTextDecoder`、B9a 遗留 4 条小修、R-B9b S1–S4、R-B8 S1/S2/S3/S5 核心项、D2 密码转义、R-G1 2 条小修、R-G2 S1–S3、R-C4c 4 条、R-Dkudo `C4d` 提示、R-B7 S1、R-D8b S1–S3、R-D5 S1–S4、R-D7 S1–S4、R-D8a B1 & S1–S4、R-D4 S1、V-C1、C1b#2、R-A6 S3/S4、V-A6、R-E0 R2-S1/R2-S2/S3、R-E15 S1/S3/S4、R-E2a B1/S1/S3/S4/S5/R2-S1、R-E1 S1–S4）已移除，下面为**进行中或尚未落地**的条目：

- **→ D-cleanup（下一张清理卡，未建卡）**：
  - R-D6 S3：`DefaultImageUploadRepository.kt:386-395` 含 `#umenu` 时直接按 `isLoggedIn` 判断，避免误走宽松登录文本兜底。
  - R-D6 S4：补齐 `PrePost`/提交静默重登、`cooldownUntilMs` 切号流、`content://` 与 EXIF 旋转单测。
  - D5-fixcheck 微注：`AuthorOnly` 第 2 页夹具作者非空导致 `DefaultThreadDetailRepository.kt:493` `fallbackOpAuthor` 单删被 `:494` 兜住的夹具冗余（补作者为空/无作者夹具锁死 `:493`）。
  - R-D9 S1：`NotificationPollingWorker.kt:131` 放宽 `catch` 到 `Exception`，`CancellationException` 照常重抛。
  - R-E2b S1：精简 `feature/threaddetail/media/` 约 60–65 行冗余包装与零生产调用重载（合并 `InvalidImagePlaceholder`、单趟化 `computeImageBottomInViewport`、去掉 `LaunchedEffect` 的 `Triple` 包装、收敛 `ImageLoadingPolicy`/`ImageAspectRatioCache` 仅测试调用的中间重载）。
- **→ “毛玻璃定稿”小卡（待用户用 `c0fc15d` debug 实时调参面板给出最终参数后派发）**：
  - 将用户拍板的最终液态玻璃参数与同屏上限 `3` 回写 `docs/design/product-design.md` §5。
- **→ 真机观察（见 `docs/device-check.md`）**：
  - R-E2b S2：快速惯性滑行（fling）途中首楼越顶图片解码完成触发 `requestScrollToItem` 是否打断惯性顿停；若真机可感知则评估不中断手势的偏移修正策略。
  - R-E15 S5：安全提问回答错误时记录真实站点返回原文，确认是否缺 `"登录失败"` 前缀。
- **→ E3（多栏容器与外壳收口，Wave 3a 实现中）**：
  - R-C4d S1–S3：`HiAdaptiveShell.kt` 加固未传 `managesGlassSource` 首帧门控并对齐 KDoc；`HiNavigationSidebar.kt` 跳过首次挂载无条件 `scrollToItem` 并补偏移断言；`HiAdaptiveShellTest.kt` 补底部胶囊拖拽预选视觉态断言。
  - R-E0 R2-S3 & S4：`MultiPaneStateMigrationTest`/`MultiPaneBackStackTest` 补“三栏切 `tid` 时面板内无键 `rememberSaveable` 必隔离重置”与“开帖态切左栏版块自动清空右栏并 `clear()` 旧帖 Store”断言；`LazyListState` 迁入按 `fid`/`tid` 键控的 `SaveableStateHolder` 承接 `StateRestorationTester` 进程回收恢复；另改 `ui/shell/` 时将合并未读数接入 `HiNavigationPill` 并在窄屏单栏二级页退场胶囊（§3.1 L38）。
- **→ E2d（详情双向连续加载与向上插页零闪跳，Wave 3a 修复轮进行中）**：
  - 修复同帧竞态并补齐 `mainClock.autoAdvance = false` 首帧 `≤ 1dp` 锚定断言（反例红线：`LaunchedEffect` 滞后补偿必判 FAIL）；须先于 `E2c` 合入。
- **→ E2c（引用展开、楼层高亮、长按菜单与 1 楼投票；Wave 3b，待 `E2d` 合入后派发；同卡清零 `:core-parser` 遗留）**：
  - R-B8 S4：`ThreadPoll`（`PollModels.kt:26-41`）当前仅提供 `titleHtml`/`timerHtml`；`E2c` 接入时补充纯文本 `titleText` / `timerText`，避免 UI 层解析 HTML 字符串。
  - R-B7 S2：为 `ThreadListParserTest` 补两处页码回退防御分支的合成夹具与变异断言（`ThreadListParser.kt:169` 无 `page=` 的伪静态分页链接如 `<a href="forum-2-5.html">5</a>`，以及 `ThreadListParser.kt:136` 带符号参数 `page=+5` 回退 `1`）。
  - R-E2a R2-S2：扩充 `PostFloorCard` 作者区点击热区至 `≥ 48dp` 并合并用户名无障碍描述；`FloorContextMenu` 复制仅用 `ClipData.newPlainText`。
  - R-E2b S3（`E2c`/`E4` 改 `ThreadDetailPane.kt` 时收口）：在 `PrependAnchorController.syncBeforeMeasure` 极端同帧重合下优先读取 `listState.firstVisibleItemScrollOffset`，避免覆盖同帧 `E2b` 尚未布局的新偏移。
- **→ E4（合并 E4a+E4b：阅读位置、底部三段式工具条与内联跳页条）**：
  - R-Dkudo 实现提示：内联跳页条滑块使用 `Modifier.weight(1f)` 自适应填充，**勿写死最小宽（如 `widthIn(min = 140.dp)`）**，确保 `340dp` 分屏窄窗 + `1.25×` 字体缩放下零挤压通过。
  - V-C4d 进度条配色提示：优先用 `primaryContainer`（浅 `≤ 0.50` / 深 `≤ 0.45`）配 `onSurface`；若选用 `primary @ 0.22f` 浅色分支，严禁直接放 `cs.primary` 文字。
  - R-C5b：`HiPressFeedbackNode.onDetach` 复位 `anim`、`HiRollingNumber` `displayedCount`/`displayedChar` 改普通引用。
  - R-E2b S3（若 `E2c` 未改则由 `E4` 收口）：同上 `firstVisibleItemScrollOffset` 同帧读取对齐。
- **→ E5 / E6（快速回复与完整编辑器）**：
  - R-D6 S2：清空草稿后退出编辑器/回复面板时调用 `deleteDraft`，避免旧草稿残留。
- **→ E6（完整编辑器）**：
  - R-D1：在“今日新帖”里点“发新帖”时必须先选定版块（正整数 `fid`）再生成 `DraftKey.newThread(fid)`。
  - R-D6 S1：`E6` 图片上传显式传 `original = false`（按规范压缩后上传）。
- **→ E7**：C1b#3 — `EmojiGroup.name` 是 `:emoji` 里写死的中文，表情面板分类标签的无障碍 `contentDescription` 需走 `:hipda` 的 `strings_emoji.xml`。
- **→ E12（设置、账号与通知）**：
  - R-D9 S2：将通知文案迁移到 `docs/design/copy.md` 和 `res/values/strings_notification.xml`。
  - R-E15 S2：接入正式账号页时整块删除临时 `AccountMenuOverlay` 及 `LoginViewModel` 临时退登脚手架（~100 行）。
- **→ E14（全屏看图器）**：
  - R-E2b S4：全屏看图器“下载原图”按 `product-design.md` §6.3 接入真实字节级确定进度圈（帖内省流点击加载因未改 `ImageLoaderModule` 暂用 `HiLoadingSpinner` + 取消图标）。
- **→ M5 / M7 性能验收**：R-M0-2 R4 — `tech-plan.md` §4 提到的 `Macrobenchmark` 尚未加入 `libs.versions.toml`；真机 120Hz 帧时间与卡顿率在 M5/M7 阶段核验。
- **→ M7-2**：
  - C1b#1：删除旧 `EmojiHandler` 等类时，将 `EmojiMapTest` 固化为 `(code, imageSrc, lightRes, darkRes)` 四元组表驱动测试。
  - R-D9 S3：删除旧 `NotiWorker` 时调用 `WorkManager.cancelUniqueWork("noti_work")` 清理旧周期任务。

---

## 5. 用户要做的事

1. **W2+E2b 真机验证 + 液态玻璃四组调参（共用新包 `main` @ `c0fc15d`，CI `45706618318`）**：按 `docs/device-check.md` 逐项验证登录/退登、Cloudflare、顶栏玻璃、读帖、图片（含快速甩动顿停观察）、Fold8 单/三栏与深色骨架，并用同一新包在「HiPDA 预览 → 外壳 → 调参」给出四档最终参数回写 §5。
2. **阶段验收（M5 完整包、M7）**：在折叠屏真机核验 3 块液态玻璃 120Hz 帧率、三栏拖拽/半折铰链双栏连续性，以及真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写与恢复。

---

## 6. 空闲的历史会话
第一至三批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的早期版本 `6a8d5dd`）。
