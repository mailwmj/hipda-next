# 验证卡 V-A6：独立视觉复验与探针验证可见验证面板及原地提示（commit 1fb3d46）

【角色】独立视觉复验与行为验证者，不先读实现者的测试与 `_reports/A6.md` 报告；结论只来自你逐张看图检查与自己跑出的探针证据。
【边界】对象 `origin/card/A6-challenge-panel` @ `1fb3d464fd9083d7de6fcfc63f54f6d3371937e3`（基于 A5b `0e0da1b49f73306e9cab675ed48adfb380024b85`），只读：不得向 `card/A6-challenge-panel` 推送，不得修改 `main`，不得覆盖既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A6 -b verify/A6-visual 1fb3d464fd9083d7de6fcfc63f54f6d3371937e3`，只在这里写。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/A6-visual` 并 `gh workflow run android.yml --ref verify/A6-visual` 跑一次 CI，`gh run watch <id> --interval 60`。不访问真实论坛。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-A6.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A6.md` 范围节；依据 `docs/design/product-design.md` §4.1、§5、§6.3、§7.12、`docs/design/copy.md`。只读生产代码（`git diff 0e0da1b..1fb3d46 -- hipda/src/main/`），不先读实现者测试与报告。
【视觉检查】（用读图工具逐张打开 `hipda/src/test/snapshots/images/` 下 7 张 `challenge_*` 新基线，并核实未改动任何其他既有基线）：
1. 窄屏底部面板（`challenge_panel_w411_light.png`、`challenge_panel_w411_dark.png`）：顶部 20dp 圆角、标题 `安全验证` / 副行 / `刷新` / `取消` 排版与 ≥48dp 触控区、底边贴合、深浅色对比度与无毛玻璃层叠。
2. 宽屏居中对话框（`challenge_dialog_w900_light.png`、`challenge_dialog_w900_dark.png`）：≥600dp 切居中对话框且宽 ≤480dp、20dp 圆角、深浅色与半透明遮罩/底色上的可读性（含复杂/类玻璃底层背景下的对比可读性）。
3. 原地提示三态（`challenge_status_checking_w411_light.png`、`challenge_status_incomplete_w411_light.png`、`challenge_status_rejected_w411_light.png`）：逐字核对 `正在检查安全环境…`、`安全验证未完成`、`验证未通过`、`重新验证` 文案、居中留白与可读性。
4. 大字号 `fontScale = 2.0f` 检视：在探针测试中以 `fontScale = 2.0f` 渲染 411dp 底部面板、900dp 对话框与三态提示，验证文字与按钮无截断、无溢出、无重叠（若可导出临时图到 `/tmp/V-A6/` 供读图目检，不提交进仓库）。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/A6ProbeTest.kt`）：
a. `present()` 期间取消外部协程 → 面板消失、不泄漏视图或窗口标志、正确结束（抛 `CancellationException` 或返回 `SolveResult.Cancelled`，不得挂死）；取消后再开启新一次 `present()` 仍可正常工作。
b. 用户点击关闭（`取消` 按钮或系统返回键）→ 面板收起、`present()` 返回 `SolveResult.Cancelled`。
c. `ChallengeStatusView` 状态切换连续性：`Idle` → `Solving`（未满 2s 保持空白不闪现）→ ≥2s 显示 `正在检查安全环境…` → `Cooldown(rejected=false)` / `Cooldown(rejected=true)` → 冷却到期惰性归位 `Idle`，断言原地切换不闪烁、容器几何位置不跳动，且点击 `重新验证` 传出 `AccessPriority.UserExplicit`。
d. 大字号 `fontScale = 2.0f` 边界探针：411dp 底部面板与 900dp 对话框的标题、副行、`刷新`/`取消` 按钮在窗口边界内完整可见、不重叠且触控目标 ≥48dp。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `1fb3d46`、CI 链接、7 张图逐张结论 + 4 个探针一行一条、FAIL 触发路径与截断/可读性问题、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-A6.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
