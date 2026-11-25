# 审查卡 R-A6：可见验证面板与原地等待/冷却提示组件（commit 1fb3d46）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/A6-challenge-panel` @ `1fb3d464fd9083d7de6fcfc63f54f6d3371937e3`，基于 A5b `0e0da1b49f73306e9cab675ed48adfb380024b85`（A5b 修复轮进行中，**审查只看 A6 自己的 diff**：`git diff 0e0da1b..1fb3d46`）。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 1fb3d46:<路径>`、`git diff 0e0da1b..1fb3d46` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45558716421`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-A6.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A6.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/A6.md`（可读，但所有结论须以代码与 diff 核实为准）。依据 `docs/design/product-design.md` §4.1、§5、§6.3、§7.12、§8，`docs/design/copy.md`“加载与进行中”与“Cloudflare 验证”两节。阻断标准 `docs/agents/DISPATCH.md` §4（Cloudflare 通道失效、旋转/折叠/配置变化崩溃或悬挂、违反卡片边界或擅改既有设计系统/截图基线，须给触发路径）。
【审查重点】
1. `ComposeChallengeInteractionPresenter.present()` 挂起与取消语义：用户点击 `取消`、按系统返回键、外部协程取消（如网关 120 秒总超时）、验证自动通过、以及进程内配置变化（旋转/折叠/窗口跨 600dp 断点）时，`present()` 是否一定结束返回（或按协程规范取消）且不泄漏 `ComposeView`、`WebView` 或窗口标志；若 `onDismissed` 在极端情况下未触发是否会永久挂起。
2. 与 `ChallengeInteractionSession` / `ChallengeActivityBinding` 的生命周期绑定：是否严格复用同一个运行中的隐藏 `WebView`（不重建、不重新 `loadUrl`）；WebView 从隐藏容器移入面板与退场拆除/归还顺序是否安全；Activity 意外 `finish()`/`onDestroy()` 时如何收口；与 A5b 进行中的修复轮是否存在接口耦合风险。
3. 文案与产品规则对齐：`strings_challenge.xml` 是否逐字对齐 `docs/design/copy.md`；`ChallengeStatusView` 的 2 秒等待门槛（不受减少动态效果影响）、`Cooldown` 惰性归位（`effectiveAt` + 到期定时消退）、“重新验证”固定传 `AccessPriority.UserExplicit` 是否全部落实。
4. 组件规范与边界：是否只用标准 Compose 组件（无手写 `Layout`/`Modifier.Node`/命令队列）、无测试专用代码（语义键/钩子/CompositionLocal）进 `main`；动效参数是否全走 `HiMotion`/`HiMotionTransitions` 且 `MotionTokenScanTest` 白名单未改；是否直接复用 C5a `HiErrorState` 与 C5b `hiPressScale`、不使用毛玻璃；是否零修改 `ui/designsystem/`、`data/network/`、共享文件及既有截图基线（仅新增 7 张 `challenge_*`）。
5. 无障碍（a11y）：面板出现后的焦点管理与系统返回键拦截、TalkBack 语义标签（标题/进度提示/按钮角色与描述）、所有交互按钮（`刷新`/`取消`/`重新验证`）是否满足 ≥48dp 触控目标。
6. 复杂度与测试质量：新增 635 行生产代码按 DISPATCH §5 有无可删的过度设计；测试是否覆盖上述边界，举“改坏关键一行测试仍通过”的例子（若存在）。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `1fb3d46`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-A6.md`。
