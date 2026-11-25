# 负责人交接说明（2026-11-25 01:15 UTC / 08:15 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准（当前 **0 个**其他子代理在运行，**0 个**活跃定时任务）。

---

## 1. 全局进度总览

- **设计文档（已全部定稿并在 `main`）**：
  - `docs/design/tech-plan.md` v0.3（Carbon 两轮评审 T1–T17 全采纳）
  - `docs/design/backlog.md` v0.3
  - `docs/design/product-design.md` **v0.2.1**（Carbon C2 两轮 + Hydrogen H1 全部采纳，覆盖全部 42 条 P0 状态）
  - `docs/design/copy.md` v0.2（完整界面文案表，已去掉“举报”）
- **已合并进 `main`（最新代码合并 commit `657ba07`，文档最新在 `961ee6f`，CI 全绿、99/99 测试通过）**：
  - `M0-1` 构建链与 CI（`ac97265`）
  - `M0-2` 新包名 `net.jejer.hipda.next`、全量依赖目录、`:core-parser`、Hilt、统一 Tink AEAD、CI 测试可见（`b264266`）
  - `C1b` 无状态表情映射（61 个表情 + 101 张深浅图，对照旧版单测）（`e5c0079`）
  - `D3a` DataStore 设置存储（19 个字段对齐 §7.10，含损坏恢复/并发/单例测试）（`b64d65c`）
  - `A1` OkHttp 5 客户端与 GBK/GB18030/UTF-8 编解码、表单编码（`40f9bb7`）
  - `D1` Room 数据库（草稿/浏览历史/阅读位置 + `ReadingFilterMode` + version 1 schema）（`4152536`）
  - `C1` M3 主题、debug 预览入口（`PreviewActivity`）与 12 张 Roborazzi 基线（`657ba07`）
- **已完成开发、独立验证/审查与全部修复（含阻断项和建议项），等待合并进 `main` 的 6 张卡**：见第 2 节。按用户要求本轮未执行合并、未派发下一批，6 个分支改动文件**零重叠**，接手后可直接依次合并。

---

## 2. 就绪待合并的 6 个分支（均已复核通过 + CI 全绿）

> 合并前按 `review.md` 核对 `git rev-parse origin/card/<分支>` 等于下表中的已判定 commit hash。

| 卡号 | 分支 | 已判定最终 Commit | CI Run（绿） | 审查与验证状态 | 核心交付与接口摘要 |
|---|---|---|---|---|---|
| **B1a** | `card/B1a-sanitizer` | `9002385721f86f5e8898e197619bb3a01d065fc1` | [45521821669](https://github.com/mailwmj/hipda-next/actions/runs/45521821669) | **R-B1a（`58bc7d58`）三轮定向复核通过**（原 6 阻断 + 二轮 2 回归全解，`:core-parser:test` 27/27 通过） | `net.jejer.hipda.parser.sanitize`：`HtmlSanitizer.sanitize(html, page, identity)`、`SampleLeakScanner.scan(html)`、`SamplesAreSanitizedTest`（支持 `.html`/`.htm`/`.xml`，覆盖短消息内链图、附件 `aid` Base64、无空格中文名、XHTML `<?xml>` 与 `<root>` XML）。 |
| **A2** | `card/A2-cookie-store` | `384edb3b306bfa7a954962ff27ccc09a96b63522` | [45521808146](https://github.com/mailwmj/hipda-next/actions/runs/45521808146) | **V-A2（`595e18ea`）通过 + R-A2（`65bfd6a7`）通过**，S1–S3 增量 diff 已由负责人核对（118/118，含 A2 19 条） | `net.jejer.hipda.data.network.cookie`：`SiteCookieStore`（Hilt `@Singleton`，RFC 6265、`saveFromResponse` 校验 `url.host`、内存缓存+串行异步写盘+`finally` 复位、Tink 加密落盘 `files/datastore/hx_site_cookies`、损坏恢复为空）。 |
| **D3c** | `card/D3c-credentials` | `3173aca03144b4ab879dba05397fedcfaf1cac34` | [45522128350](https://github.com/mailwmj/hipda-next/actions/runs/45522128350) | **V-D3c（`58b5cdd7`）通过 + R-D3c（`166d85d5`）通过**，S1–S4 增量 diff 已由负责人核对（127/127，含 D3c 28 条） | `net.jejer.hipda.data.account`：`SavedAccount`（仅存 32 位小写 `passwordMd5`）、`CredentialsStore`（Hilt `@Singleton`，`CredentialsModule` 收口 DataStore，0 字节显式 `CorruptionException`）；`backup_config.xml` + `data_extraction_rules.xml` 排除 `hx_tink_keysets.xml`、`datastore/hx_credentials`、`datastore/hx_site_cookies`，由强化版 `BackupExclusionRulesTest`（剥离注释 + 解析 `const val`/具名参数 + 禁 `<include>`）守卫；`TinkAeadProvider` 完成 R7（纯 `IOException` 不删主密钥）。 |
| **C3** | `card/C3-glass` | `e1515c6d758429fc5cc2966224d072a79d0b1cdb` | [45522067773](https://github.com/mailwmj/hipda-next/actions/runs/45522067773) | **R-C3（`445893cd`）通过 + V-C3（`6f23169e`）视觉复验通过**，S1–S3 增量 diff 已由负责人核对（129/129 + 20 张基线全过） | `net.jejer.hipda.ui.designsystem.glass`：`GlassEnvironment`（4 类降级、`stateIn` 默认 `WhileSubscribed(5_000)`）、`HiGlassSurface`（Haze 2.1.0，降级态不挂 `hazeBlur` 改直绘 `.background` 释放 `HazeSourceNode` 图层）、`assertGlassSurfaceCountAtMost`；`HiColors.kt` OKLCH L\* 二分校准（60 套配色全 ≥ 4.61:1，深色 `outline` ≥ 3.04:1）；修复预览页 V-C1 4 项视觉问题并调高 `HiThemeScreenshotTest` 画布（`w411dp-h1800dp` / `w900dp-h1120dp`）零裁切。 |
| **C2** | `card/C2-motion` | `dbac973729e48ba9c213a8dd71368d0961df426e` | [45522635025](https://github.com/mailwmj/hipda-next/actions/runs/45522635025) | **R-C2（`cfc485fc`）复核通过**（原 3 阻断 + 4 建议全解，120/120 + `verifyRoborazziDebug`，零基线改动，已验证在 C3 `e1515c6` 上零误报） | `net.jejer.hipda.ui.designsystem.motion`：`HiMotion`（§6.2 全部令牌、`reified T` 自动默认 `VisibilityThreshold`、`skeletonRepeatable` 750ms×Reverse=1500ms）、`hi*` 过渡函数、`HiSystemMotionDurationScale` 接入 `HiTheme`（含 R-C1 R3 修复）；`MotionTokenScanTest`（递归 `${...}` 词法器、前瞻泛型解析、`import ... as` 别名、拦截 `*Spec`/`repeatable`、白名单逐条消费）；`net.jejer.hipda.testing.MotionTestHelpers`（`assertFrameContinuity` 含 `minTotalTravelDp`、`withAnimatorDurationScale`、`RecompositionCounter` 区分 `count` 与 `recompositionCount`）。 |
| **M0-3** | `card/M0-3-ci-hardening` | `3435c0ad8acd2ebf48b580d3d2a2240598a1900d` | [45522776953](https://github.com/mailwmj/hipda-next/actions/runs/45522776953) | **R-M0-3（`9a9e52c3`）复核通过**（全部 5+1 项均在 CI 先红后绿实证，99/99，仅改 3 文件） | `.github/workflows/android.yml`、`core-parser/build.gradle.kts`、`hipda/build.gradle.kts`：冒烟与 `aapt2` 去 `SIGPIPE` 隐患；单测 `--continue` + XML `ParseError` 容错；单步 `testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` + 按需上传 `roborazzi-diffs`；`roborazzi { outputDir.set("src/test/snapshots/images") }` 修复基线图缓存盲区（实证仅改 PNG 触发失效变红）；编译后 `Verify Room schema consistency`；`:core-parser:test` 绑定 `hipda/src/test/resources/samples` 为可选相对路径缓存输入；`build` job 补 `persist-credentials: false`。 |

---

## 3. 接手后建议立即执行的步骤

1. **批量合并上述 6 个分支到 `main` 并验证联合 CI**：
   - 6 个分支改动文件经检查**零重叠**，可依次 `git merge --no-ff`（建议顺序：`M0-3` → `B1a` → `A2` → `D3c` → `C3` → `C2`），合并完一次 `git push origin main`。
   - 用 `gh run watch <id> --interval 60`（**切记遵守 DISPATCH §2，不用默认 3 秒间隔，避免再耗尽 GitHub API 5000/h 额度**）等联合 CI 跑绿。这轮联合 CI 会同时验证三个跨分支组合：
     - `A2` + `D3c`：`BackupExclusionRulesTest` 真实扫到 `CookieStoreModule.kt` 的 `hx_site_cookies` 且与两个备份 XML 对齐；
     - `C2` + `C3`：`MotionTokenScanTest` 扫描 C3 全部源码零违规；
     - `M0-3` + `B1a` + `C3`：单次 `testDebugUnitTest` + 20 张 Roborazzi 基线验证 + `:core-parser:test` 27 条测试 + Room schema 一致性全通过。
   - 合并变绿后，可清理 `../hipda-next.wt/` 下已合并卡的 worktree（共 12 个目录：`M0-1`、`A1`、`A2`、`B1a`、`C1`、`C1b`、`C2`、`C3`、`D1`、`D3a`、`D3c`、`M0-3`）。
2. **基于联合变绿的 `main` 派发第三批卡**（实现用 `antigravity/gemini-4-neon` xhigh，验证用 `gemini-4-hydrogen` xhigh 新会话，审查用 `gemini-4-carbon` high，同时最多 8 代理）：
   - **A3**（A 线关键路径）：Cronet-OkHttp（Android 14+ `HttpEngine` → Play Services Cronet → OkHttp fallback）+ `ManualRedirectInterceptor` 在外层（POST 301/302/303 改 GET、跨域剥离敏感头、最多 16 跳）+ `BrowserHeaderInterceptor` 与 `AppCookieInterceptor`（接 A2 `SiteCookieStore`）在内层。
   - **C4a**（C 线外壳）：自适应外壳（按 `product-design.md` §4.1 宽/高断点切换底部悬浮毛玻璃胶囊、紧凑胶囊、80dp 左侧导航轨；选中胶囊 `spatialFast` 滑移；调用 C3 的 `assertGlassSurfaceCountAtMost(2)` 守住毛玻璃上限）。**顺手带做 R-C1 R2 清理**：`HiTypography.kt` 删 `capFontScale`/`cappedForChrome` 只留 `HiCappedFontScale`、删与 M3 默认相同的 `label*` 槽位、删 M0-2 遗留的 `ComposeFoundationPreview.kt` 及其测试（顺带清掉 `createComposeRule` 弃用警告）。
   - **C5a**（C 线状态组件）：空状态、错误状态（按 `product-design.md` §8 有/无按钮分流 + 可展开 `查看详情`）、骨架屏（用 C2 `HiMotion.skeletonRepeatable()`）及原地加载/完成/出错切换（主动 0ms，被动 `loadingShowDelay` 150ms + `loadingMinShow` 250ms）。
   - **C5b**（C 线反馈组件）：浮在内容上的可撤销 Snackbar、数字逐位滚动、图标切换与描线对勾、列表项（仅状态层不缩放）vs 按钮/导航项（`0.97` 缩放 + 触感反馈）。
   - **D3d**（可选，若代理名额宽裕）：搜索历史和收藏缓存的存储（依赖 `D3a`；或留到下一批）。
3. **后续主干路径**：
   - A 线串行推进：`A3 → A4（验证检测/协程层排队/60s 冷却/独立恢复 Dispatcher/后台直接放弃）→ A5（透明 ChallengeActivity + 不可见 WebView 自动过盾 + Cookie 同步 + UA 统一）→ A6（可见验证面板：<600dp 底部面板 / ≥600dp 居中对话框，等待 >2s 提示“正在检查安全环境…”）→ B1b（debug 样本导出工具页）`。
   - **B1b 完成后找用户**：一次做完登录、确认抓到版块页（M1 验收）、按清单一键导出脱敏样本，随后解锁 B2–B9 解析器线与 D2/D4–D9 数据层。
   - C 线：`C4a → C4b（导航随滚动隐藏、内容延伸至毛玻璃下方）` 完成后进入 M4 外壳阶段验收。

---

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 第一、二批中已被 `M0-3`/`D3c`/`C2`/`C3` 关闭的建议已剔除，下面均为**尚未落地**的条目：

- **→ C4a**：R-C1 R2 — `HiTypography.kt` 删除样式级 `capFontScale`/`cappedForChrome`（统一使用子树级 `HiCappedFontScale`）、删除未改动的 M3 默认 `label*` 槽位、删除 M0-2 占位 `ComposeFoundationPreview.kt` 及其单测 `ComposeFoundationPreviewTest.kt`（一并清掉 `createComposeRule` v1→v2 弃用警告）。
- **→ A3 / A4**：A2 备注 — OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会通过 `AssetManager` 读 `PublicSuffixDatabase.list`，单测需用 Robolectric 并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。
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

## 6. 现有 Worktree 与关键会话 ID（均带标签 `team-lead=hipda-next`）

- **Worktree 目录**（都在 `/home/mir/Documents/hipda-next.wt/`）：
  - 已合并（可删）：`M0-1`（当前检出 `card/M0-2-foundation`）、`A1`、`C1`、`C1b`、`D1`、`D3a`
  - 待合并（合并后再删）：`B1a`、`A2`、`D3c`、`C3`、`C2`、`M0-3`
- **第二批实现会话（空闲，可复用做后续小修）**：
  - `B1a` `037d7a6f`、`A2` `4b20882c`、`D3c` `237b469a`、`C3` `d7fcdc42`、`C2` `0a35d020`、`M0-3` `be2fe2db`
- **第二批审查与验证会话（空闲）**：
  - `R-B1a` `58bc7d58`、`R-A2` `65bfd6a7`、`V-A2` `595e18ea`、`R-D3c` `166d85d5`、`V-D3c` `58b5cdd7`、`R-C3` `445893cd`、`V-C3` `6f23169e`、`R-C2` `cfc485fc`、`R-M0-3` `9a9e52c3`
- **第一批与方案阶段会话（空闲）**：
  - 实现：`M0-1/M0-2` `e0494703`、`A1` `3415ca52`、`C1` `0732782b`、`C1b` `60c8eb8c`、`D1` `dffbea3f`、`D3a` `355358c1`
  - 审查/验证：`R-M0-1` `03d809eb`、`V-M0-1` `2054f242`、`R-M0-2` `5701a912`、`V-M0-2` `6be2196f`、`R-C1b` `9580bf7a`、`R-D3a` `b1c3ba62`、`R-A1` `95482924`、`V-A1` `b90255e2`、`R-D1` `1be743d1`、`R-C1` `af28ce38`、`V-C1` `24be6cd5`
  - 方案评审与调研：`C1(技术评审)` `4e554492`、`C2(产品评审)` `dd219b3b`、`H1(Hydrogen顾问)` `ae1f9fc4`、`P1` `6e72a897`、`R1` `afb8550e`、`R2` `714fe8b8`、`S1` `f7fec42d`、`F1` `37a0d0cf`、`F2` `0a068aa6`、`F3` `66c5439b`、`F4` `cee6daee`
