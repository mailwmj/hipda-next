# 审查卡 R-C5a：独立代码审查状态组件（commit 64bf594）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/C5a-state` @ `64bf5946369ffcbf3760ff42e43640362cc9e2b5`，相对 `origin/main` @ `64fd073`。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 64bf594:<路径>`、`git diff 64fd073...64bf594` 读代码；不要 checkout、写文件（`/tmp/R-C5a/` 除外，用来导出截图查看）、提交或推送；不要在主目录切换分支。不要编译。CI 结果用 `gh run view 45531056117`（不要用 `gh run watch`）。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/C5a.md`（范围和验收以它为准）。
- 规格：`docs/design/product-design.md` 第 2 节原则 2、6、8；第 6.1 节（减少动态效果；`loadingShowDelay` 不是动画）；第 6.2 节 `skeletonPeriod`、`loadingShowDelay`、`loadingMinShow`；第 6.3 节“加载、完成、出错”“连续加载”；第 8 节错误提示格式；第 10.1 节；文案 `docs/design/copy.md`。
- 后续使用者：所有页面卡（E 线）用 `HiContentState`、`HiEmptyState`、`HiErrorState`、`HiListFooter`、骨架。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节 + 卡片）：CI 失败、违反卡片范围或声明、破坏既有测试/契约——必须给出具体触发路径。其他都是建议。

【审查重点】
1. 规格符合：被动加载 150ms 后才显示、显示后至少 250ms；主动操作 0ms；缩放为 0 时延迟照常生效；首次加载骨架、原地交叉淡入淡出、无跳动；错误主提示不含详情、详情可展开/收起/复制；文案逐字来自 copy.md；触摸目标 ≥ 48dp。
2. 状态机正确性：`LoadingVisibilityController` 的竞态（快速连续开始/结束、显示期间重新开始、协程取消、作用域生命周期）；`rememberLoadingIndicatorVisible` 在重组和配置变化（旋转）时是否会重置计时或泄漏协程。
3. `HiContentState`：`Content` 内值更新（同为 Content）时是否会误触发淡入淡出或重建内容（会丢滚动位置）；状态切换时内容的 key 策略；`Error.retry` 的稳定性与重组。
4. 动效：参数全部来自 `HiMotion`/`hi*`；可打断；减少动态效果下骨架静止、过渡一帧到位。
5. 复杂度：生产代码约 820 行，卡片估计 250–400 行。逐文件判断是否过度设计（`HiStateViews.kt` 355 行尤其要看），给出具体简化方案和预计行数。
6. 测试质量：测试能否在实现被改坏时失败（举“改坏一行测试仍通过”的例子，若存在）；20 张截图是否覆盖卡片要求。
7. 范围：只改了 `state/`、`strings_state.xml` 和对应测试/基线；`motion/`、`MotionTokenScanTest`、既有基线零改动。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定（APPROVE / CHANGES-REQUESTED）+ 被审 commit `64bf594`；阻断项（每条：触发路径、文件:行号、后果）；建议项（每条一行，含简化方案的预计行数）。详细分析放后面。
