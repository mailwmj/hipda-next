# 任务卡 C5a：状态组件（空、错误、骨架屏、原地加载切换、列表尾部状态）

【角色】你是 hipda-next 的 Compose 设计系统开发者。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/C5a`（分支 `card/C5a-state`，基于 `origin/main` @ 64fd073）里工作。可改：`hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/`（新建）、`hipda/src/main/res/values/strings_state.xml`（新建）、对应测试和 `state_` 前缀的新基线图。不改 `ui/designsystem/motion/`、`glass/`、`HiTheme.kt`、`HiTypography.kt`、`HiColors.kt`、`PreviewActivity.kt`、`ui/shell/`、`ui/designsystem/feedback/`（另两张卡正在并行写这些区域）。不改已有基线图。

【背景】必读：
- `docs/design/product-design.md` 第 2 节原则 2、6、8；第 6.1 节“减少动态效果”（`loadingShowDelay` 不是动画，缩放为 0 时照常生效）；第 6.2 节 `skeletonPeriod`、`loadingShowDelay`、`loadingMinShow`；第 6.3 节“加载、完成、出错”“连续加载”；第 8 节“错误提示的格式”；第 10.1 节截图矩阵（空、错、加载状态 2 种宽度）和无障碍。
- `docs/design/copy.md` “空状态”“加载与进行中”“错误与校验”三节。
- 只调用、不重写：`ui/designsystem/motion/HiMotion.kt`（`HiMotion.skeletonRepeatable()`、`HiMotion.loadingShowDelay`、`HiMotion.loadingMinShow`、`effectsDefault` 等）、`motion/HiMotionTransitions.kt`（`hiFadeIn`、`hiFadeOut`、`hiExpandVertically`、`hiShrinkVertically`、`Modifier.hiAnimateContentSize`）；测试辅助 `test/.../testing/MotionTestHelpers.kt`、`test/.../designsystem/HiScreenshotTestSupport.kt`。

【目标】`ui/designsystem/state/` 提供一套页面卡可以直接使用的状态组件，行为符合产品设计第 6.3 节“加载、完成、出错”，并有逐帧和截图证据。

【范围】
1. `HiEmptyState(message: String, modifier, action: HiStateAction? = null)`：居中的说明文字 + 可选按钮（`HiStateAction(label, onClick)`）。不加次级说明文字。
2. `HiErrorState(message: String, modifier, action: HiStateAction? = null, details: String? = null)`：原因短语 + 可选按钮；`details` 非空时显示 `查看详情`，点开后原地展开详情（`hiExpandVertically` / `hiShrinkVertically`，可打断），按钮变为 `收起详情`，展开区有 `复制详情`（写入剪贴板）。主提示里不得出现 `details` 的内容。
3. 骨架：`Modifier.hiSkeleton(shape: Shape)` 和 `HiSkeletonBlock(width, height, shape)`，明暗呼吸用 `HiMotion.skeletonRepeatable()`；动画时长缩放为 0 时静止在一个固定的中间明度（不闪、不残留中间帧）。
4. 延迟显示状态机：`rememberLoadingIndicatorVisible(isLoading: Boolean, passive: Boolean): State<Boolean>`。被动加载：加载开始 150ms（`HiMotion.loadingShowDelay`）后仍未结束才变为 true；一旦为 true，至少保持 250ms（`HiMotion.loadingMinShow`）。主动操作（`passive = false`）：0ms 变为 true，结束立即 false。逻辑抽成一个不依赖 Compose 的纯协程类（例如 `LoadingVisibilityController`），可以用虚拟时间单测。
5. `HiContentState`：`@Composable fun <T> HiContentState(state: HiLoadState<T>, skeleton: @Composable () -> Unit, empty: @Composable () -> Unit, error: @Composable (HiLoadState.Error) -> Unit, content: @Composable (T) -> Unit)`；`HiLoadState` 是 `Loading` / `Content(value)` / `Empty` / `Error(message, details, retry)` 的密封接口。首次加载显示骨架（受第 4 项延迟规则约束：150ms 内完成就直接显示内容，不闪骨架）；状态之间原地交叉淡入淡出（`effectsDefault`，无位移、不跳动）。
6. `HiListFooter(state: HiFooterState)`：`Loading` 显示 `正在加载…`，`Failed(onRetry)` 显示整行可点的 `加载失败，点击重试`，`End` 显示 `没有更多了`。整行触摸目标 ≥ 48dp。
7. `res/values/strings_state.xml`：`查看详情`、`收起详情`、`复制详情`、`正在加载…`、`加载失败，点击重试`、`没有更多了`。（各页面自己的空状态文本由页面卡放进自己的字符串文件，本卡不放。）
不做：Snackbar、数字滚动、图标切换、按压反馈（C5b）；错误映射函数（网络层卡）；页面接入。生产代码约 250–400 行。

【步骤】
1. 先为第 4 项写失败的单测（`kotlinx-coroutines-test` 虚拟时间）：100ms 结束 → 从不显示；200ms 结束 → 150ms 显示、400ms 隐藏；显示期间重新开始加载不闪烁；主动操作 0ms 显示。另加一条：`withAnimatorDurationScale(0f)` 下 150ms 延迟仍然生效。
2. 实现第 1–7 项。
3. Compose 测试：
   a. `HiErrorState` 展开详情可打断：展开到一半时收起，用 `assertFrameContinuity` 证明高度逐帧连续；减少动态效果下一帧到位。
   b. `HiContentState` 从 `Loading` 到 `Content` 的替换前后，内容第一项到容器顶部的距离不变（误差 ≤ 1dp）。
   c. 骨架在缩放为 0 时，相隔 500ms 的两帧像素/透明度相同。
   d. 无障碍：按钮和尾部行触摸目标 ≥ 48dp；`HiErrorState` 主提示不含 `details` 文本。
4. Roborazzi 新基线（前缀 `state_`，在 411dp 和 900dp 两种宽度，浅色和深色）：空状态带按钮、错误状态收起、错误状态展开详情、骨架（缩放为 0 的静止帧）、列表尾部三种状态。新基线按 DISPATCH 第 2 节在本地只对本卡测试类执行 `recordRoborazziDebug` 后提交，录完逐张打开检查无裁切、文字对比度正常。
5. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步的状态机单测全部通过。证据：测试类:方法 + CI 汇总。
- [ ] 第 3 步 a–d 通过。证据：测试类:方法 + CI 汇总。
- [ ] `state_*` 新基线已提交并通过 CI 的 Roborazzi 校验；已有基线零改动。证据：`git diff --stat origin/main -- hipda/src/test/snapshots/` + CI run。
- [ ] 所有动效参数来自 `HiMotion`/`hi*`，`MotionTokenScanTest` 在 CI 通过且白名单未改。证据：CI run + `git diff origin/main -- '**/MotionTokenScanTest.kt'` 为空。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/C5a-state`。最后回复按通用约定。

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

首先 `cd /home/mir/Documents/hipda-next.wt/C5a`，确认 `git status` 干净、HEAD 为 64fd073；首次推送用 `git push -u origin card/...`（本卡分支名）。
