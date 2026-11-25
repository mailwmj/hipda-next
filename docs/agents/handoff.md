# 负责人交接说明（2026-11-25 18:05 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计文档（均在 `main`）：`docs/design/tech-plan.md` v0.3、`backlog.md` v0.3、`product-design.md` v0.3、`copy.md` v0.3（大屏三栏方案已定稿合入 `b4ab916`）。
- **已合并进 `main`**（当前 `HEAD`：`d5ed2ce`）：
  - 第一批：M0-1、M0-2、C1b、D3a、A1、D1、C1；
  - 第二批：M0-3、B1a、A2、D3c、C3、C2（联合 CI 45526302892，223/223）；
  - 第三批与 M1/M4 关键收口：
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
    - A5c 统一桌面 Linux Chrome UA 与 WebView Client Hints（解决移动 UA 触发手机版模板导致 `pm.php`/`notice.php` 500，R-A5c APPROVE `@d6b767c`）→ `15d3e32`
    - D-3pane 大屏与折叠屏三栏自适应布局定稿（R-D3pane 审查及修订轮 APPROVE `@65a1ea5`）→ `b4ab916`
    - B1c 本人独立数字 UID 脱敏扫描补全与 16 个真机脱敏样本 + `manifest.json` 入仓（`hipda/src/test/resources/samples/`，reviewed `@37df441`；远端临时分支 `samples/b1b` 已删）→ `d5ed2ce`
- **阶段验收状态**：
  - **M1 真机验收通过**：用户已在真机使用 A5c 构建完成登录、抓取全部 16 个页面并导出脱敏样本入仓（`d5ed2ce`）。
  - **M4 真机验收通过**：外壳、导航与折叠屏功能在 Galaxy Z Fold8 真机验收通过；毛玻璃观感待校准，已派发 `G1` 调参并附 debug 实时调参面板，待用户实测定值。
- 卡片与报告工作副本在 `/home/mir/Documents/hipda-next.wt/_cards/`、`_reports/`，合并后同步存档至 `docs/agents/cards/`、`docs/agents/reports/`，通用条款在 `_cards/_common.md`。

## 2. 进行中与已起草待派发

| 卡号 | 分支 / worktree | 状态 |
|---|---|---|
| C4c 自适应外壳三栏升级与左侧栏 | `card/C4c-three-pane-shell` / `../hipda-next.wt/C4c` | 进行中（基于 `b4ab916`；审查卡 `R-C4c.md` 与验证卡 `V-C4c.md` 已起草） |
| G1 毛玻璃观感调参 + debug 实时调参面板 | `card/G1-glass-tuning` / `../hipda-next.wt/G1` | 进行中（基于 `b4ab916`；完成后由用户用 debug 调参面板在真机定值） |
| B9a（BATCH-4 Wave 1） | `card/B9a-parser-core-auth-msg` / `../hipda-next.wt/B9a` | 卡片 `B9a.md`、`R-B9a.md`、`V-B9a.md` 已起草，待派发（与 C4c/G1 零文件冲突） |
| OPS-1 负责人助理（文档维护 / 起草卡片 / 核对修改轮） | 主目录 `main`（纯文档） | 常驻复用中 |

- 本机有 Android SDK（mise 安装，见 `lesson.md`），可本地串行录制 Roborazzi 基线。

## 3. BATCH-4 计划与后续主干路径

- **BATCH-4 计划**（已批准，全文见 `/home/mir/Documents/hipda-next.wt/_cards/BATCH-4.md`）：按 DISPATCH §5 第一性原理将 B2–B9、D2/D3d/D4–D9 与 R-B1c S1/S2 收拢为 **12 张卡**（省 5 张固定开销），全批与在途 `C4c`/`G1` 零文件冲突，按 **5 波**推进（同时在跑的实现卡 ≤ 3）：
  1. **Wave 1**（立即与 `C4c`/`G1` 并行）：`B9a` [Complex]（`:core-parser` 公共基座 + 登录/短消息/提醒解析含旧回归 + R-B1c S1/S2）
  2. **Wave 2**（3 并行）：`D2` [Complex]（`AuthRepository` + `ForumHttpClient`）、`B8` [Complex]（帖子详情+内容块+1 楼投票）、`B9b` [Medium]（搜索/简单列表/用户/黑名单/发帖表单/收藏/上传）
  3. **Wave 3**（3 并行）：`B7` [Medium]（帖子列表解析器，M2 收官）、`D8b` [Medium]（用户资料+黑名单缓存）、`D7` [Medium]（短消息+提醒 Repository，独立双未读数）
  4. **Wave 4**（3 并行 → 解锁 M5 阅读核心）：`D4` [Medium]（帖子列表 Repository）、`D5` [Medium]（帖子详情 Repository）、`D6` [Complex]（发帖/回复/编辑/上传 Repository）
  5. **Wave 5**（2 并行 + 1 槽可启 `E1`/`E2a`）：`D8a` [Medium]（合并 `D3d`：搜索/今日新帖/收藏与关注 + 独立缓存 DataStore）、`D9` [Complex]（WorkManager 提醒轮询，唯一授权修改 `HiApplication`/`AndroidManifest.xml`）
- **待观察风险**：
  - **Cloudflare 桌面 UA 下 WebView 过验证尚未在真机触发**：A5c 切换为桌面 Linux Chrome UA + Client Hints（`platform=Linux`、`mobile=false`）后，真机 M1 请求均直接 200 通过、未触发 Cloudflare 人机验证；该 UA 组合下的 WebView 自动/人工过盾与 `cf_clearance` 同步路径尚未经真机实测，列为待观察风险，后续真机首次触发验证时重点核实。
- 第三批遗留优化（后续卡顺带处理）：R-C5b 复核建议 `HiPressFeedbackNode.onDetach` 复位 `anim`、`displayedCount`/`displayedChar` 改普通引用。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 已被已合并卡片或 BATCH-4 收口的条目已更新/剔除，下面为**尚未落地**的条目：

- **→ B9a（已写入卡片）**：
  - R-B1c S1：`SanitizeSelectors.kt:166` 左边界收紧覆盖 `%20`/`%22` 等末位为数字的百分号转义紧邻 UID，并补 `%3D`/`%20`/`%22` 单测。
  - R-B1c S2：`SampleLeakScanner` 对非空非数字 `selfUid` fail-closed（抛出 `IllegalArgumentException`）。
  - A1 S4（**真机样本已证实**）：`thread_list/`、`thread_detail/thread_p2.html`、`search/search_result.html` 实测含双重转义补充平面 Emoji `&amp;#\d{5,6};`，由 `B9a` 建立统一 `DiscuzTextDecoder` 供 `B7`/`B8`/`B9b` 复用。
- **→ B8 / E2a**：
  - C1b#2：`EmojiMap.drawableForImageSrc` / `codeForImageSrc` 接收归一化键（`default_smile`，旧版 `TextViewWithEmoticon.java:86-89` 去扩展名并将 `/` 换 `_`）；`B8` 与 `E2a` 卡需写明归一化归属。
- **→ D2**：
  - 旧 `LoginHelper.java:175-177` 密码转义 `.replace("'", "\'")` 在 Java 字面量中实际不转义单引号（仅转义 `\` 与 `"`），`D2` 须用对照测试锁定精确 MD5 语义。
- **→ E 线接入验证提示与面板**：
  - R-A6 S3：E 线接入时为 `ChallengeStatusView` 补齐未满 2s 时的原加载指示插槽；验证面板的 `HiTheme` 接入用户主题配置。
  - R-A6 S4：复用 `ChallengeState.effectiveAt`，并将 `HiLoadingSpinner` 公开去重。
  - V-A6：340dp 窄屏 + 2x 字号下验证面板副行过长时做省略处理，避免挤占内容区。
- **→ E1**：V-C1 — 深色模式下已读标题与未读标题区分度偏弱（`10.05:1` vs `13.23:1`），列表页接入时评估拉大已读弱化程度。
- **→ E6**：R-D1 — 在“今日新帖”里点“发新帖”时必须先选定版块（正整数 `fid`）再生成 `DraftKey.newThread(fid)`。
- **→ E7**：C1b#3 — `EmojiGroup.name` 是 `:emoji` 里写死的中文，表情面板分类标签的无障碍 `contentDescription` 需走 `:hipda` 的 `strings_emoji.xml`。
- **→ M5 / M7 性能验收**：R-M0-2 R4 — `tech-plan.md` §4 提到的 `Macrobenchmark` 尚未加入 `libs.versions.toml`；真机帧时间与卡顿率在 M5/M7 阶段核验。
- **→ M7-2**：C1b#1 — 删除旧 `EmojiHandler` 等类时，将 `EmojiMapTest` 固化为 `(code, imageSrc, lightRes, darkRes)` 四元组表驱动测试。

---

## 5. 用户要做的事

1. **G1 完成后**：在真机 debug 预览页使用毛玻璃实时调参面板选定最终观感参数并“复制当前参数”回传负责人。
2. **待观察核实**：真机后续首次遇到 Cloudflare 人机验证时，确认桌面 UA 下的 WebView 自动/人工过盾与 `cf_clearance` 同步正常。
3. **阶段验收（M5、M7）**：在折叠屏真机核验三栏/半折铰链双栏连续性、帧率，以及真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写与恢复。

---

## 6. 空闲的历史会话
第一至三批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的早期版本 `6a8d5dd`）。
