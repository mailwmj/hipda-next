# 调度配置（负责人用）

> 方法见 team-lead skill。本文件只写本项目的配置，冲突时以本文件为准。

## 0. 先问用户
- 要问：改包名 `net.jejer.hipda` 或签名；构建或发布 release；把仓库改为公开或开付费 CI 额度；删除现有功能；用真实账号对论坛做写操作（发帖、回复、短消息、改资料）；在本机启动模拟器。
- 不用问：开卡片分支、审查通过后合并到 `main` 并推送、触发 GitHub Actions、方案内的依赖升级、文档更新。

## 1. 机器与边界
| 机器 | Paseo host | 能做 | 不能做 |
|---|---|---|---|
| mir-A5（本机） | srv__-KdZo8yWrcE | 写代码、git、gh、按第 2 节规则串行编译 | 并发编译；未经同意启动模拟器 |
| Home-PC | srv_6ldXy4lma29O | 暂不使用 | — |
| GitHub Actions | — | 主力编译、单元测试、lint、Roborazzi 截图、阶段验收时的模拟器测试 | — |

## 2. 并发与工作目录
- 每张卡一个 worktree：`git worktree add ../hipda-next.wt/<卡号> -b card/<卡号>-<简名> origin/main`。一个目录同时只有一个写入者；只读的审查、调研可以并行。
- 编译以 GitHub Actions 为准：推送分支 → `gh run watch <id> --interval 60` → 失败时 `gh run view --log-failed`。不用默认间隔（3 秒），不写循环轮询：多个代理同时轮询会在一小时内耗尽 GitHub API 的 5000 次额度。遇到 `API rate limit exceeded` 时，用 `gh api rate_limit` 查重置时间，等到重置后再查，不要当成 CI 失败。
- 先本地、后 CI：代理改完先在本地跑本卡相关的测试（按下一条的方式），本地变绿后才推送或 `gh workflow run`；CI 只作最终证据，不拿 CI 调试半成品（失败的运行会给负责人发邮件）。本机 SDK 路径见 `lesson.md`。
- 本地编译必须这样运行：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后执行 `./gradlew --stop`。
- 同时运行的代理（含只读）最多 8 个；本地编译仍按上一条串行。

## 3. 模型路由
| 工作类型 | 首选 | 备选 | 思考级别 |
|---|---|---|---|
| 开发（全部实现卡） | antigravity `gemini-4-neon` | codex `gpt-6.2-sol` | xhigh（备选 high） |
| 编译与跑测（验证、跑测试） | antigravity `gemini-4-hydrogen`，每张卡开新会话 | antigravity `gemini-4-neon` | xhigh |
| 调研 | antigravity `gemini-4-neon` | antigravity `gemini-4-hydrogen` | xhigh |
| 方案与代码独立审查 | antigravity `gemini-4-carbon` | codex `gpt-6.2-sol` | high |
| 核查 Neon 的调研 | antigravity `gemini-4-carbon` | codex `gpt-6.2-sol` | high |
| 翻代码、清单类调研、代码搜索 | antigravity `gemini-4-neon`（结构清楚的搜索）或 `gemini-4-hydrogen`（涉及 UI/交互/文案判断） | — | xhigh |
| 独立规划（负责人是 Claude） | antigravity `gemini-4-neon` | codex `gpt-6.2-astra` | xhigh |
| 方案、文案、交互、动效顾问 | antigravity `gemini-4-hydrogen` | antigravity `gemini-4-neon` | xhigh |
- agy 的模式一律用 `full-access`；思考级别用 `thinkingOptionId` 传。
- 不用 Haiku：翻代码和搜索按复杂度交给 Neon 或 Hydrogen。Hydrogen 的结论要核对 文件:行号 后才能用。
- Neon 实现的卡由 Carbon 审查；Carbon 不可用时用 Sol 审查。编译跑测以 CI 输出为证据，不算独立审查。

## 4. 阻断项与敏感区
- 只有以下情况阻断合并，并且要能给出具体的触发路径：CI 编译或测试失败；解析器样本测试回归；登录、Cookie、GBK 编码或 Cloudflare 验证通道失效；折叠、展开或旋转导致崩溃，或丢失阅读状态；自动化测试对论坛发起写操作；违反第 2 节的本地编译规则；任何把站点内容（帖子、短消息、签名、链接、图片）交给系统或 WebView 打开、加载的代码未走 scheme 白名单（只允许 `http`、`https` 以及经过校验的 `mailto`，且每种危险 scheme 都要有反例测试）。
- 敏感区（轻量卡也要审查）：`okhttp/`、`async/LoginHelper.java`、`utils/HiParser*`、`bean/HiSettingsHelper.java`、`db/`、`async/PostHelper.java`、`job/PostJob.java`、Cloudflare 验证相关代码。

## 5. 卡片约定
- 流程强度与拆卡按 team-lead skill 的复杂度分级（planning.md），不按行数。本项目补充：探针验证由 Hydrogen 做，用于复杂档卡与界面卡（视觉复验）；每张卡固定开销实测约 45 分钟（起会话、读背景、CI、审查），生产代码不足约 120 行的卡与相邻卡合并。
- 拆卡对照（第三批）：A3a/A3b 拆得对（判定方式不同 + Cronet 未知）；A4a/A4b 拆错（检测器只供网关使用、判定方式相同、不解锁并行）；A5a/A5b 拆得对（A5a 可并行，WebView 部分只能真机判定）。
- 卡号按阶段编：`M0-1`、`M0-2`……；分支 `card/<卡号>-<简名>`。
- 提交用 Conventional Commits，类型用英文，描述可以写中文。
- 自检以 CI 为准：编译 `assembleDebug`、单元测试 `testDebugUnitTest`、`lint`；有截图测试后加 `verifyRoborazziDebug`（以 M0/M4 卡实际落地的任务名为准）。
- 每张卡都要带上这些话：
  - 不要在整张卡提交并推送之前结束回合。
  - 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节。
  - 不要用真实账号对论坛做写操作。
  - 完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。
- 界面字符串放在 `res/values/strings_<功能>.xml`。
- 界面卡另加：优先用 Compose 标准组件，手写 `Layout`/`Modifier.Node`/状态机要说明理由；生产代码不含只为测试存在的代码；超过估计行数 1.5 倍先自查简化。（第三批三张 C 卡都写到估计的 2–3 倍，见 lesson.md）
- 代理最后一条回复不超过 600 字，结论在前；详细证据写到 `/home/mir/Documents/hipda-next.wt/_reports/<卡号>.md`。
- 修改轮的小改动由负责人助理（Neon 新会话或 OPS 会话，不是实现者本人）对照审查项核对，结论写到 `_reports/<卡号>-fixcheck.md`；大段重写或修阻断项仍由原审查者复核。
- 卡片与报告的工作副本在 `/home/mir/Documents/hipda-next.wt/_cards`、`_reports`（代理按绝对路径读写）；每张卡合并后由负责人助理把对应卡片、审查/验证卡和报告同步到仓库 `docs/agents/cards/`、`docs/agents/reports/` 存档。

## 6. 决策记录
- 用户的决定记在 `docs/agents/decisions.md`。
