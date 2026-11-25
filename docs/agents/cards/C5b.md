# 任务卡 C5b：反馈组件（浮动可撤销 Snackbar、数字逐位滚动与角标、图标切换与描线对勾、按压反馈与触感）

【角色】你是 hipda-next 的 Compose 设计系统开发者。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/C5b`（分支 `card/C5b-feedback`，基于 `origin/main` @ 64fd073）里工作。可改：`hipda/src/main/java/net/jejer/hipda/ui/designsystem/feedback/`（新建）、`hipda/src/main/res/values/strings_feedback.xml`（新建）、`ui/designsystem/motion/HiMotionTransitions.kt`（**只允许在文件末尾追加**本卡需要的 `hi*` 过渡函数，例如 `hiSlideInVertically`/`hiSlideOutVertically`，参数必须取自 `HiMotion` 令牌；不改已有函数）、对应测试和 `feedback_` 前缀的新基线图。不改 `HiMotion.kt`、`glass/`、`HiTheme.kt`、`HiTypography.kt`、`PreviewActivity.kt`、`ui/shell/`、`ui/designsystem/state/`（另两张卡正在并行写）。不改已有基线图。

【背景】必读：
- `docs/design/product-design.md` 第 2 节原则 5；第 6.3 节“Snackbar”“数值变化”“图标变化”“按压”“删除与撤销”；第 6.1 节“减少动态效果”（数字直接变）；第 5 节“层级”（悬浮物带很淡的阴影）。
- `docs/design/copy.md` “Snackbar 与确认框”。
- 只调用、不重写：`ui/designsystem/motion/HiMotion.kt`（`spatialFast`、`spatialDefault`、`effectsFast`、`exit` 换算）、`motion/HiMotionTransitions.kt`；测试辅助 `test/.../testing/MotionTestHelpers.kt`（`assertFrameContinuity`、`withAnimatorDurationScale`、`RecompositionCounter`）、`test/.../designsystem/HiScreenshotTestSupport.kt`。

【目标】`ui/designsystem/feedback/` 提供 Snackbar、数字滚动、角标、图标切换、描线对勾、按压缩放和触感 6 类组件，行为符合第 6.3 节，并有逐帧和截图证据。

【范围】
1. `HiSnackbarHost(hostState: SnackbarHostState, bottomClearance: Dp, modifier)`：浮在内容之上，底边距离容器底部 `bottomClearance`（由调用方传入“胶囊或工具条高度 + 8dp”），不占布局、不推挤内容；从下方升起（`spatialDefault`），退场用 `exit` 换算的令牌；带动作按钮时显示动作（例如 `撤销`）。不用 M3 `SnackbarHost` 自带的动画（它的参数不是我们的令牌）；可以复用 M3 的 `Snackbar` 外观和 `SnackbarHostState` 的排队与时长。
2. `HiRollingNumber(value: Int, modifier, style)`：逐位滚动，增加时新数字从下往上、减少时从上往下（`spatialFast`）；只有变化的位滚动；减少动态效果时直接变。
3. `HiCountBadge(count: Int, modifier)`：count 为 0 时不显示；0 → >0 从中心放大出现（`spatialFast`），>0 → 0 缩小消失（`exit`）；数字用第 2 项滚动。
4. `HiIconSwitch(target: ImageVector, contentDescription, modifier)`：旧图标缩小并淡出、新图标放大并淡入，同时进行（`spatialFast` + `effectsFast`）。`HiStrokeCheck(visible: Boolean, modifier)`：对勾用路径描线画出（`PathMeasure` 截取，进度用 `spatialFast` 驱动）。
5. 按压：`Modifier.hiPressScale(interactionSource)`：按下立即缩到 0.97、松开弹回（`spatialFast`），无延迟；`Modifier.hiListItemPress(interactionSource)`：只显示状态层（`effectsFast`），不缩放。触感：`rememberHiHaptics()` 返回 `confirm()`，调用 `LocalHapticFeedback` 的确认类型（Compose 当前版本有 `HapticFeedbackType.Confirm` 就用它，否则用 `LongPress`，在回复里写明实际用了哪个）。
6. `res/values/strings_feedback.xml`：`撤销`。
不做：空/错/骨架（C5a）；外壳和导航（C4a）；页面接入。生产代码约 250–400 行。

【步骤】
1. 先写失败的测试：
   a. `HiRollingNumber` 从 9 → 10 时十位和个位都滚动、方向向上；10 → 9 方向向下；99 → 100 位数增加时新位出现；减少动态效果下一帧到位。
   b. 数字滚动可打断：动画到一半时改回原值，`assertFrameContinuity` 证明逐帧连续。
   c. `HiCountBadge` 0 时不在语义树中；0→3 有放大过程，3→0 动画结束后从语义树消失。
   d. `hiPressScale`：按下后稳定在 0.97（误差 0.001），松开回到 1.0；`hiListItemPress` 的缩放始终是 1.0。
   e. `HiSnackbarHost`：显示时底边位置等于 `bottomClearance`（误差 ≤ 1dp），同层内容的位置不变；减少动态效果下一帧到位；动作按钮点击回调为 `SnackbarResult.ActionPerformed`。
   f. 重组：父级无关状态变化时 `HiRollingNumber` 不重组（`RecompositionCounter`）。
2. 实现到测试变绿。
3. Roborazzi 新基线（前缀 `feedback_`，411dp 宽，浅色和深色）：带 `撤销` 的 Snackbar 浮在一个 64dp 模拟胶囊上方 8dp 处；角标 1、99、100 三个值；对勾画完的终态。新基线按 DISPATCH 第 2 节在本地只对本卡测试类执行 `recordRoborazziDebug` 后提交，录完逐张打开检查。
4. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步 a–f 全部通过。证据：测试类:方法 + CI 汇总。
- [ ] `feedback_*` 新基线已提交并通过 CI 的 Roborazzi 校验；已有基线零改动。证据：`git diff --stat origin/main -- hipda/src/test/snapshots/` + CI run。
- [ ] `HiMotionTransitions.kt` 只在末尾新增函数，已有函数零改动；`MotionTokenScanTest` 通过且白名单未改。证据：`git diff origin/main -- '**/HiMotionTransitions.kt' '**/MotionTokenScanTest.kt'` + CI run。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/C5b-feedback`。最后回复按通用约定。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
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

首先 `cd /home/mir/Documents/hipda-next.wt/C5b`，确认 `git status` 干净、HEAD 为 64fd073；首次推送用 `git push -u origin card/...`（本卡分支名）。
