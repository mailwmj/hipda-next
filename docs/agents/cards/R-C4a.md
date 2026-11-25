# 审查卡 R-C4a：独立代码审查自适应外壳（commit 63e7c69）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/C4a-shell` @ `63e7c6931a42834b9683e7a32fbeb0b28331c9e0`，相对 `origin/main` @ `64fd073`。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 63e7c69:<路径>`、`git diff 64fd073...63e7c69` 读代码；不要 checkout、写文件、提交或推送；不要在主目录切换分支。本机没有 Android SDK，不要尝试编译。CI 结果用 `gh run view 45529683101`（不要用 `gh run watch`）。截图基线可以用读图工具直接打开 `git show 63e7c69:hipda/src/test/snapshots/images/<文件>` 导出到 `/tmp/R-C4a/` 后查看（只写 `/tmp/R-C4a/`）。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/C4a.md`（范围和验收以它为准）。
- 规格：`docs/design/product-design.md` 第 4.1 节、第 5 节“毛玻璃”“导航胶囊”、第 6.1–6.3 节（令牌唯一、可打断、减少动态效果、一级目的地切换、选择指示器）、第 10.1/10.3 节。
- 后续：C4b 会在此基础上做滚动隐藏、键盘隐藏、内容底部留白，并把 C5b 的 `hiPressScale` 接到导航项；E3 做双栏与铰链布局。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节 + 卡片）：CI 失败、违反卡片范围或声明、破坏既有测试/契约、折叠展开旋转导致崩溃或丢状态——必须给出具体触发路径。其他都是建议。

【审查重点】
1. 规格符合：断点规则（先高度后宽度、铰链 → 紧凑胶囊）、胶囊尺寸（最小高 64/48dp、16dp 边距、最宽 420dp、窗口居中、距手势区 8dp）、导航轨 80dp、宽度 < 360dp 只给选中项文字、字号上限 1.25、毛玻璃恰好 1 块。
2. 动效：选中指示器的位置和宽度都走 `HiMotion.spatialFast`、可打断（新目标从当前位置和速度接续）；窗口尺寸变化（旋转、折叠展开、形态切换）时指示器是否会出现错位一帧或从错误位置飞入；目的地切换只淡入淡出、无位移；减少动态效果下一帧到位。
3. 状态：旋转/折叠展开/进程回收后选中目的地是否保持（`HiAdaptiveShell` 是无状态的还是内部持有状态？若持有，是否用 `rememberSaveable`）。
4. 复杂度：生产代码约 760 行（`HiNavigationPill.kt` 388 行），远超卡片 300–400 行的估计。判断是否有可删的过度设计（自定义 Layout、重复的度量逻辑、Pill 与 Rail 之间可共用的指示器动画），给出具体的简化建议和预计行数。
5. 测试质量：`HiAdaptiveShellTest`、`HiShellScreenshotTest` 是否能在实现被改坏时失败（举“改坏一行测试仍通过”的例子，若存在）；截图是否覆盖卡片要求的 11 种组合。
6. 清理：`HiTypography.kt` 删除 `capFontScale`/`cappedForChrome` 后 `PreviewActivity` 的行为是否等价；实现者以测试证明 M3 默认 `label*` 与本项目定义不同而保留了这些槽位——核对这个证明是否成立（`HiThemeTokensTest` 中的 `labelSlots_...` 用例）。
7. 范围：没改 `motion/`、`glass/`、`HiTheme.kt`、`HiColors.kt`、共享构建文件、`MotionTokenScanTest`；既有 20 张基线零改动。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定（APPROVE / CHANGES-REQUESTED）+ 被审 commit `63e7c69`；阻断项（每条：触发路径、文件:行号、后果）；建议项（每条一行，含简化建议的预计行数）。详细分析放后面。
