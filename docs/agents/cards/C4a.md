# 任务卡 C4a：自适应外壳的导航形态（底部胶囊 / 紧凑胶囊 / 导航轨）+ R-C1 R2 清理

【角色】你是 hipda-next 的 Compose 设计系统开发者。

【边界】只在 worktree `/home/mir/Documents/hipda-next.wt/C4a`（分支 `card/C4a-shell`，基于 `origin/main` @ 64fd073）里工作。可改：`hipda/src/main/java/net/jejer/hipda/ui/shell/`（新代码放这里）、`ui/designsystem/HiTypography.kt`（只做下面的清理）、`ui/designsystem/ComposeFoundationPreview.kt`（删除）、`hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`、`hipda/src/main/res/values/strings_shell.xml`（新建）以及对应测试和新基线图。不改 `ui/designsystem/motion/`、`ui/designsystem/glass/`（只调用）、`HiTheme.kt`、`HiColors.kt`；不改 `ui/designsystem/state/`、`ui/designsystem/feedback/`（另两张卡正在并行写）。不改已有的 20 张基线图。

【背景】必读：
- `docs/design/product-design.md` 第 3 节（4 个一级目的地）、第 4.1 节（窗口分档与导航形态、键盘、字号上限 1.25、宽度 < 360dp 只给选中项显示文字）、第 5 节“毛玻璃”“导航胶囊”、第 6.3 节“一级目的地切换”“选择指示器”、第 10.1 节截图矩阵与可打断、第 10.3 节毛玻璃计数。
- `docs/design/copy.md` “导航与常用操作”的一级导航文本。
- 现有设计系统（只调用，不重写）：`ui/designsystem/glass/HiGlassSurface.kt`（`HiGlassSurface`、语义键 `HiGlassMarker`）、`glass/GlassEnvironment.kt`；`ui/designsystem/motion/HiMotion.kt`（`spatialFast`、`effectsDefault` 等令牌）、`motion/HiMotionTransitions.kt`（`hiFadeIn`/`hiFadeOut` 等）；`HiTypography.kt` 的 `HiCappedFontScale`；`HiTheme.kt`。
- 测试辅助（只调用）：`hipda/src/test/java/net/jejer/hipda/testing/MotionTestHelpers.kt`（`assertFrameContinuity`、`withAnimatorDurationScale`、`RecompositionCounter`）、`test/.../glass/HiGlassTestSupport.kt`（`assertGlassSurfaceCountAtMost`）、`test/.../designsystem/HiScreenshotTestSupport.kt`。

【目标】预览入口里能看到一个自适应外壳：按窗口宽高在底部悬浮毛玻璃胶囊、紧凑胶囊、80dp 左侧导航轨之间切换，选中指示器用 `spatialFast` 滑动；同时完成 R-C1 R2 清理。

【范围】
1. `ui/shell/ShellNavigationMode.kt`：纯函数 `shellNavigationMode(widthDp: Float, heightDp: Float, hingeSeparating: Boolean): ShellNavigationMode`，枚举 `BottomPill`、`CompactPill`、`Rail`。规则（第 4.1 节，先看高度）：高度 < 480dp → `CompactPill`；铰链分隔 → `CompactPill`；宽度 ≥ 840dp → `Rail`；其余 → `BottomPill`。另给 `showLabelForUnselected(widthDp)`：宽度 < 360dp 时为 false。
2. `ui/shell/HiNavigationPill.kt`：4 个目的地（论坛、消息、搜索、我的；图标用 `material-icons-core` 里已有的图标）。`BottomPill`：最小高 64dp，左右 16dp 边距，最宽 420dp，相对整个窗口水平居中，距系统导航栏/手势区 8dp（用 `WindowInsets.navigationBars`）；`CompactPill`：最小高 48dp，只显示图标，选中项加文字。选中项背后是一个浅色胶囊指示器，切换时位置和宽度都用 `HiMotion.spatialFast` 动画，可打断。胶囊本体用 `HiGlassSurface`。文字用 `HiCappedFontScale` 限制到 1.25 倍，文字变大时胶囊长高、不裁切。每个导航项是 ≥ 48dp 的触摸目标、带 `Role.Tab` 与选中语义。按压缩放（0.97）和触感由并行的 C5b 提供，本卡不做。
3. `ui/shell/HiNavigationRail.kt`：宽 80dp，贴左，竖排 4 项，用 `HiGlassSurface`；选中指示器竖向滑动，`spatialFast`。
4. `ui/shell/HiAdaptiveShell.kt`：`@Composable fun HiAdaptiveShell(selected: ShellDestination, onSelect: (ShellDestination) -> Unit, modifier: Modifier = Modifier, content: @Composable (ShellDestination) -> Unit)`。用窗口尺寸（`currentWindowAdaptiveInfo()` 或 `LocalWindowInfo`，说明选择）调用第 1 步的函数决定形态；内容区作为 Haze 源铺满窗口（胶囊浮在内容上，不占布局高度；导航轨占左侧 80dp）；切换目的地时内容原地交叉淡入淡出（`hiFadeIn`/`hiFadeOut` + `effectsDefault`），无位移。滚动隐藏、键盘隐藏、内容底部留白、未读角标都留给 C4b 及之后的卡，本卡不做。
5. `res/values/strings_shell.xml`：`论坛`、`消息`、`搜索`、`我的`。
6. 预览入口：在 `PreviewActivity` 里加一个进入“外壳”演示的入口（4 个目的地各显示一个占位内容，含可以滚动的长列表，用来看毛玻璃效果）。不改预览页现有内容的外观。
7. R-C1 R2 清理（不改变任何现有像素）：
   - `HiTypography.kt` 删除 `TextStyle.capFontScale` 和 `TextStyle.cappedForChrome`，只保留子树级 `HiCappedFontScale`；`PreviewActivity` 里所有 `.cappedForChrome()` 调用改为用 `HiCappedFontScale` 包裹对应的子树；`HiThemeTokensTest` 里测 `capFontScale` 的用例改为测 `HiCappedFontScale` 的同等行为（1.0 不变、1.25 不变、1.5 被限到 1.25），不得只删测试。
   - 删除 `HiTypography.kt` 中与 M3 默认值完全相同的 `label*` 槽位定义（先用测试或比较证明它们与 `Typography()` 默认值相同，再删）。
   - 删除 M0-2 遗留的 `ui/designsystem/ComposeFoundationPreview.kt` 及其测试 `ComposeFoundationPreviewTest.kt`。
本卡生产代码约 300–400 行（不含测试和删除的行）。

【步骤】
1. 先写 `shellNavigationMode` 的单元测试：覆盖 340×800、411×891、599×800、600×800、700×900、744×900、839×900、840×900、900×1120、1280×800、891×411、968×411（后两者是紧凑胶囊），以及 700×900 + 铰链分隔 → `CompactPill`；`showLabelForUnselected` 在 359/360dp 两侧。
2. 实现第 1–5 项；写 Compose 测试：
   a. 毛玻璃计数：任何形态下 `assertGlassSurfaceCountAtMost(2)`，且外壳自身恰好 1 块（`HiGlassMarker` 计数）。
   b. 选中指示器可打断：动画进行到一半时切回原目的地，用 `assertFrameContinuity` 证明位置逐帧连续。
   c. 减少动态效果：`withAnimatorDurationScale(0f)` 下切换目的地，指示器和内容一帧到位。
   d. 重组次数：切换目的地时，未选中的导航项不重组（用 `RecompositionCounter`）。
   e. 无障碍：每个导航项触摸目标 ≥ 48dp，有文本或内容描述，选中项有选中语义。
3. Roborazzi 截图（新基线，文件名前缀 `shell_`）：411×891、700×900、900×1120、1280×800 × 浅/深色（8 张），外加 891×411（紧凑胶囊）、340×800（只给选中项显示文字）、411×891 且第 5 档字号 × 系统字体缩放 1.3（文字不裁切）各 1 张。新基线在本地按 DISPATCH 第 2 节用 `recordRoborazziDebug` 只针对本卡的测试类录制并提交；录完自己逐张打开检查：无裁切、无重叠、胶囊居中、导航轨在左。
4. 做第 7 项清理；确认现有 20 张基线一张都不变（`git diff --stat origin/main -- hipda/src/test/snapshots/` 只出现新增的 `shell_*.png`）。
5. 推送，等 CI 变绿。

【验收】
- [ ] 第 1 步的断点单测全部通过。证据：测试类:方法 + CI 汇总。
- [ ] 第 2 步 a–e 的测试通过。证据：测试类:方法 + CI 汇总。
- [ ] 11 张 `shell_*` 新基线已提交，`verifyRoborazziDebug`（CI 的 Roborazzi 校验步骤）通过；现有 20 张基线零改动。证据：`git diff --stat` 输出 + CI run。
- [ ] R-C1 R2 清理完成：`rg -n 'capFontScale|cappedForChrome|ComposeFoundationPreview' hipda/src` 无结果；`HiThemeTokensTest` 有等价的 `HiCappedFontScale` 用例。证据：命令输出 + 文件:行号。
- [ ] CI 全绿。证据：run 链接 + commit hash。

【交付】分支 `card/C4a-shell`。最后回复按通用约定，并附上新基线图的文件列表。

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

首先 `cd /home/mir/Documents/hipda-next.wt/C4a`，确认 `git status` 干净、HEAD 为 64fd073；首次推送用 `git push -u origin card/...`（本卡分支名）。
