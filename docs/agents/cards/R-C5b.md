# 审查卡 R-C5b：独立代码审查反馈组件（commit 2387ded）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/C5b-feedback` @ `2387ded69b9ff2af86b3553bb07ca545e427def6`，相对 `origin/main` @ `64fd073`。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 2387ded:<路径>`、`git diff 64fd073...2387ded` 读代码；不要 checkout、写文件（`/tmp/R-C5b/` 除外，用来导出截图查看）、提交或推送；不要在主目录切换分支。不要编译。CI 结果用 `gh run view 45529810175`（不要用 `gh run watch`）。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/C5b.md`（范围和验收以它为准）。
- 规格：`docs/design/product-design.md` 第 6.1 节（令牌唯一、可打断、减少动态效果）、第 6.2 节令牌、第 6.3 节“Snackbar”“数值变化”“图标变化”“按压”“删除与撤销”、第 10.1 节。
- 后续使用者：C4b 把 `hiPressScale` 接到导航项；页面卡（E 线）用 Snackbar、角标、数字滚动。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节 + 卡片）：CI 失败、违反卡片范围或声明、破坏既有测试/契约——必须给出具体触发路径。其他都是建议。

【审查重点】
1. 规格符合：Snackbar 浮在内容上、不推挤、从下方升起 `spatialDefault`、退场走 `exit`；数字逐位滚动方向（增上减下）、只滚变化的位；角标 0 不显示、出现放大/消失缩小；图标切换同时缩放淡入淡出；对勾描线；按钮按压 0.97、列表项只状态层；触感用确认类型。
2. 动效：全部参数来自 `HiMotion`/`hi*`；可打断（新目标从当前位置和速度接续，不从头开始）；减少动态效果下一帧到位、不留中间状态；`HiMotionTransitions.kt` 只在末尾新增、未改已有函数。
3. 复杂度：生产代码约 1120 行，卡片估计 250–400 行。逐文件判断是否过度设计（`HiRollingNumber.kt` 446 行、`HiPressFeedback.kt` 286 行尤其可疑）：有没有自己重写了 Compose 已提供的能力（例如 `AnimatedContent`、`Indication`/ripple、`InteractionSource` 收集），有没有只为测试存在的生产代码路径。给出具体的简化方案和预计行数。
4. 性能：滚动列表中大量使用时（例如每个列表项一个角标或数字）是否有不必要的重组、每帧分配、`Modifier.composed` 之类的老 API。
5. 测试质量：测试是否能在实现被改坏时失败（举“改坏一行测试仍通过”的例子，若存在）；截图是否覆盖卡片要求的状态（Snackbar 在 64dp 模拟胶囊上方 8dp、角标 1/99/100、对勾终态，浅深色）。
6. 范围：只改了 `feedback/`、`strings_feedback.xml`、`HiMotionTransitions.kt` 末尾和对应测试/基线；`MotionTokenScanTest` 与既有基线零改动。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定（APPROVE / CHANGES-REQUESTED）+ 被审 commit `2387ded`；阻断项（每条：触发路径、文件:行号、后果）；建议项（每条一行，含简化方案的预计行数）。详细分析放后面。
