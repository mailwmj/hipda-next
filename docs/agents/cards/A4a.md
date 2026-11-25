# 任务卡 A4a：Cloudflare 验证页检测器（纯函数）+ A3b 审查遗留

【角色】你是 hipda-next 的 Android 网络层开发者（Kotlin、OkHttp 5）。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/A4a`（分支 `card/A4a-challenge-detector`，基于 `origin/main` @ fe6b553，已含 A3a、A3b）里工作。只改 `hipda/src/main/java/net/jejer/hipda/data/network/` 与对应测试目录。本卡**不**接入任何拦截器或网关、不排队、不重试、不做冷却（那是 A4b），只提供检测器。敏感区：生产代码约 150 行以内。

【背景】必读：
- `docs/design/cloudflare-research.md` 第四节“实测分析与判断”第 2 条（正常 200 页面与 500 页面正文里也含 `challenge-platform`，来自 JSD 脚本 `scripts/jsd/main.js`——这是误判陷阱）、第五节第 2 小节“验证页检测规则（`ChallengeDetector`）”、第六节第 1 小节的 6 类夹具。
- `docs/design/tech-plan.md` 第 6 节“检测”与“正文兜底只对 403 和 503 生效（T11）”。
- 现有：`data/network/TransportInterceptor.kt`、`HttpClientModule.kt`、`CronetEngineSelector.kt`（A3b）；`docs/agents/lesson.md`（本地 Gradle 先导出 mise 的 ANDROID_HOME/JAVA_HOME；OkHttp `Cookie.parse` 单测需 `OkHttp.initialize`）。

【目标】`ChallengeDetector.inspect(response)` 对 Cloudflare 验证响应返回 `ChallengeSignal.Challenge`，对正常页面（含带 JSD 脚本的 200/500 页面）返回 `ChallengeSignal.None`，不消耗响应体。

【范围】
1. `ChallengeDetector.kt`：
   - `sealed interface ChallengeSignal { data object None; data class Challenge(val statusCode: Int, val byHeader: Boolean) }`
   - `class ChallengeDetector(private val peekLimitBytes: Long = 16 * 1024) { fun inspect(response: Response): ChallengeSignal }`（无状态、线程安全；Hilt 用 `@Inject constructor` 或在 `HttpClientModule` 旁的模块里提供，二选一并说明）。
   - 规则 A（权威）：状态码 ∈ {403, 429, 503} 且任一 `cf-mitigated` 响应头值（名字和值都不区分大小写）包含 `challenge` → `Challenge(byHeader = true)`。
   - 规则 B（兜底，仅当没有 `cf-mitigated` 头）：状态码 ∈ {403, 503}（**200、429、500 等一律不走正文**）；`Content-Type` 为空或包含 `text/html`；用 `response.peekBody(peekLimitBytes)` 读取，命中任一强特征即 `Challenge(byHeader = false)`：含 `_cf_chl_opt`；含 `/cdn-cgi/challenge-platform/h/` 或 `orchestrate/chl_page`；同时含 `<title>Just a moment`（不区分大小写）与 `cloudflare` 或 `cf-challenge`。**仅含 `challenge-platform` 或 `scripts/jsd/main.js` 不算。**
   - 正文按字节窥探后用 ISO-8859-1 或 UTF-8 解码均可（特征都是 ASCII），不要依赖 GBK 解码；不得关闭或消耗原响应体。
2. A3b 审查遗留（R-A3b 建议 4、5，原文）：
   - `TransportInterceptor.kt` 可删减约 20 行样板与仅测试用次构造器：`public constructor(delegateFactory)` 无生产调用点，可与 `internal constructor` 合并默认参数 `isMainThread = ::isAndroidMainThread`；`createCronetEngine` 内二次 `applicationContext` 提取及第 111 行对已过滤 `PROVIDER_NAME_PLAY_SERVICES` 的再次 `selectProvider` 均可精简。
   - 补齐默认工厂路径与 `isAndroidMainThread`/`installPlayServicesCronet` 的变异测试缺口：当前单测全部覆写了 `isMainThread` 与 `installPlayServicesProvider` 且未调用 `TransportInterceptor.create(context)`，把 `isAndroidMainThread()` 改坏为恒返 `false` 或把 `TransportInterceptor.create` 改坏为返 `{ null }` 时现有单测仍全绿。补测试让这两种改坏都会失败（在回复里说明你如何确认：临时改坏、本地跑、看到失败、再改回）。

【步骤】
1. 先写失败的表驱动测试（MockWebServer 或直接构造 `Response`，说明选择），至少覆盖：
   - 命中：403 + `cf-mitigated: challenge` + 托管验证正文；429 + `Cf-Mitigated: Challenge`（大小写）；503 + `cf-mitigated: challenge`；403 无头 + `window._cf_chl_opt`；503 无头 + `/cdn-cgi/challenge-platform/h/`；403 无头 + `<title>Just a moment...</title>` + `cloudflare`。
   - 不命中：200 GBK 正文尾部带 `/cdn-cgi/challenge-platform/scripts/jsd/main.js` 与 `window.__CF$cv$params`；500 同样带 JSD；403 无头 + 只含 `challenge-platform`；429 无头 + 含 `_cf_chl_opt`（规则 B 不看 429）；403 无头 + `Content-Type: application/json` + 含 `_cf_chl_opt`；200 + `cf-mitigated: challenge`（规则 A 不看 200）；特征出现在第 16KB 之后（超出窥探范围）。
   - `inspect` 之后原响应体仍可完整读出，内容逐字节一致。
2. 实现检测器到测试变绿。
3. 做第 2 项遗留并补测试。
4. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步每个用例一行：输入 → 期望 → 测试方法名；CI 通过。
- [ ] `TransportInterceptor.kt` 修改前后 `wc -l`；两种“改坏”都会让新测试失败的说明。
- [ ] 生产代码只在 `data/network/`，约 150 行以内（`git diff --stat origin/main...HEAD`）。
- [ ] CI 全绿（run 链接 + commit hash）。

【交付】分支 `card/A4a-challenge-detector`。最后回复按通用约定，附 API 清单（A4b 会直接调用 `ChallengeDetector.inspect`）。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节（先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME）：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复的第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK；前约 1200 字写：结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过 + 证据位置）、遗留问题；详细证据与日志放在后面。最后附上本卡对外提供的 API 清单（签名 + 文件:行号），供后续卡使用。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

首先 `cd /home/mir/Documents/hipda-next.wt/A4a`，确认 `git status` 干净、HEAD 为 fe6b553；首次推送用 `git push -u origin card/A4a-challenge-detector`。
