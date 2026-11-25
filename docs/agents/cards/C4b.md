# 任务卡 C4b：导航随滚动隐藏与出现、键盘时隐藏、内容延伸到导航下方、导航项按压反馈

【角色】你是 hipda-next 的 Compose 设计系统开发者。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/C4b`（分支 `card/C4b-shell-scroll`，基于 `origin/main` @ 92e01d3，已含 C4a 外壳与 C5b 反馈组件）里工作。可改：`hipda/src/main/java/net/jejer/hipda/ui/shell/`、`hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`（只改外壳演示部分）、对应测试和 `shell_` 前缀基线。只调用、不改：`ui/designsystem/motion/`、`glass/`、`feedback/`、`state/`、`HiTheme.kt`、`HiColors.kt`、`HiTypography.kt`。不改 `glass_*`、`theme_*`、`state_*`、`feedback_*` 基线。生产代码新增约 200–300 行；同时第 5 项要净删除约 60 行。

【背景】必读：
- `docs/design/product-design.md` 第 4.1 节“键盘”、第 5 节“毛玻璃”（内容延伸到毛玻璃下面、每一栏末尾的留白规则）和“导航胶囊”、第 6.3 节“栏随滚动”“按压”“一级目的地切换”（切换后胶囊回到可见）、第 10.1 节“键盘”、第 10.3 节第 3 条（列表最后一项完全可见）。
- 现有代码（只调用）：`ui/shell/HiAdaptiveShell.kt`、`HiNavigationPill.kt`、`HiNavigationRail.kt`、`ShellNavigationMode.kt`；`ui/designsystem/feedback/HiPressFeedback.kt` 的 `Modifier.hiPressScale(interactionSource)`；`motion/HiMotion.kt` 的 `spatialDefault` 等令牌。
- 测试辅助（只调用）：`test/.../testing/MotionTestHelpers.kt`、`test/.../glass/HiGlassTestSupport.kt`、`test/.../designsystem/HiScreenshotTestSupport.kt`、`test/.../ui/shell/HiShellBoundsGuardTest.kt`（必须继续通过）。
- `docs/agents/lesson.md`：本地 Gradle 前导出 mise 的 ANDROID_HOME/JAVA_HOME；新基线只对本卡截图测试类 `recordRoborazziDebug --tests ...`。

【目标】外壳里的底部胶囊（含紧凑胶囊）随内容滚动跟手隐藏和出现，键盘弹出时立即隐藏；内容延伸到胶囊下方滚动，滚到底时最后一项完全露出；导航项按下有 0.97 缩放。

【范围】
1. 滚动联动（只作用于 `BottomPill`、`CompactPill`；`Rail` 不隐藏）：
   - 一个外壳持有的栏状态（例如 `ShellBarState`，内部是“已隐藏的偏移量”，范围 0 到胶囊高度 + 8dp + 底部手势区高度），通过 `NestedScrollConnection` 接收内容的滚动：向下滚动时偏移量跟着滚动量增加（胶囊滑出屏幕），向上滚动时减少，逐帧跟手、不跑动画。
   - 停止滚动（`onPostFling` 结束，或拖动松手且没有 fling）时，按已滚出的比例吸附到全显或全隐，用 `HiMotion.spatialDefault`。
   - 以下情况胶囊回到可见（`spatialDefault`）：内容短于一屏（无法再向下滚动）、切换一级目的地、键盘收起后。
   - 胶囊的隐藏只用 `graphicsLayer` 的位移，不改布局尺寸。
2. 键盘：`WindowInsets.isImeVisible` 为真时胶囊立即隐藏（0ms，不跑动画），键盘收起后按第 1 项的规则回到可见。
3. 内容留白：给 `HiAdaptiveShell` 的内容 lambda 提供底部留白值（例如 `content: @Composable (ShellDestination, PaddingValues) -> Unit`，或一个 `CompositionLocal`；在回复里说明选择，并更新预览和现有测试的调用点）：`BottomPill`/`CompactPill` 为“胶囊高度 + 8dp + 底部手势区”，**按胶囊完全可见时计算，不随胶囊收起而变化**（避免内容跳动）；`Rail` 为底部手势区。双栏（600–839dp 的胶囊 + 工具条叠加）不在本卡范围。
4. 导航项按压：胶囊和导航轨的每个导航项接入 `Modifier.hiPressScale(interactionSource)`（与 `clickable`/`selectable` 共用同一个 `interactionSource`），不加触感。
5. R-C4a 遗留的简化（净删约 60 行，行为不变，`HiShellBoundsGuardTest` 与现有截图零变化）：把共用的 `ShellIndicatorLayout` 和导航轨常量从 `HiNavigationPill.kt` 拆到单独的 `ShellIndicatorLayout.kt`；删除与 `ShellNavigationMode` 一一对应的内部枚举 `ShellLayoutArrangement`、`ShellNavItemStyle`，直接用 `ShellNavigationMode`；三个分支的测量与偏移累加循环统一为一套主轴/副轴循环；`itemContent`/`iconNode` 两层闭包收拢；指示器动画在 `HiTheme.isReducedMotion` 时直接 `snapTo`。
6. 预览：外壳演示的长列表用第 3 项的留白，能看到滚动隐藏、滚到底最后一项完全可见。
7. 导航轨与内容区的层级（视觉复验 V-C4a 遗留）：导航轨底色与内容区背景几乎相同，只靠 0.5dp 线区分。在不改 `glass/` 的前提下，用导航轨自己的着色参数（如果 `HiGlassSurface` 已有着色参数）或导航轨后面一层 `surfaceContainerLow` 底色让两者可区分；做不到就在回复里说明，不要改 `glass/`。

【步骤】
1. 先写失败的测试：
   a. 向下拖动 N px（N 小于胶囊可隐藏距离），胶囊位移正好等于 N（误差 ≤ 1px），逐帧跟手；向上拖动等量后回到 0。
   b. 停止时已滚出 > 50% 吸附到全隐，≤ 50% 吸附到全显；吸附过程用 `assertFrameContinuity` 证明逐帧连续；减少动态效果下一帧到位。
   c. 内容短于一屏时，向下拖动不会让胶囊隐藏（或隐藏后立即回到可见，二选一并说明）。
   d. 胶囊全隐时切换一级目的地，胶囊回到可见。
   e. 注入 IME insets（`WindowInsets.ime` 非零）时胶囊在同一帧内不可见；移除 IME insets 后回到可见。
   f. 内容为 60 项的列表，滚到底时最后一项的底边 ≤ 胶囊顶边（胶囊完全可见时），在 411×891、700×900 两种窗口下断言；`Rail`（900×1120）下最后一项底边 ≤ 窗口底边 − 底部手势区。
   g. 导航项按下后缩放稳定在 0.97（误差 0.001），松开回到 1.0。
   h. 毛玻璃计数仍为 1；`HiShellBoundsGuardTest` 继续通过。
2. 实现第 1–4、6、7 项；做第 5 项简化，确认现有 `shell_*` 基线零变化（第 7 项导致的导航轨像素变化除外，只重录这 4 张导航轨基线并逐张检查）。
3. 新增截图（前缀 `shell_scroll_`）：411×891 浅色滚到底（最后一项完全露出、胶囊可见）；411×891 浅色胶囊已隐藏的中间状态（滚动后静止于全隐）。
4. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步 a–h 全部通过。证据：测试类:方法 + CI 汇总。
- [ ] 第 5 项：`ui/shell/` 生产代码修改前后 `wc -l`（基线 715 行，加上第 1–4 项的新增后给出总数和第 5 项的净删除数）；`HiShellBoundsGuardTest` 通过。
- [ ] 基线：只新增 `shell_scroll_*`，以及（若做了第 7 项）重录的 4 张导航轨基线；其他基线零改动（`git diff --stat origin/main -- hipda/src/test/snapshots/`）。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/C4b-shell-scroll`。最后回复按通用约定。

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

首先 `cd /home/mir/Documents/hipda-next.wt/C4b`，确认 `git status` 干净、HEAD 为 92e01d3；首次推送用 `git push -u origin card/C4b-shell-scroll`。
