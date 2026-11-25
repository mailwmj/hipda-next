# 验证卡 V-C4c：独立视觉复验与状态探针验证三栏自适应外壳（commit 6706b09869ece5329c12738c2782bf303f305b29）

【角色】独立视觉复验与行为验证者，不先读实现者的测试与 `_reports/C4c.md` 报告；结论只来自你逐张看图检查与自己跑出的探针证据。
【边界】对象 `origin/card/C4c-three-pane-shell` @ `6706b09869ece5329c12738c2782bf303f305b29`，只读：不得向 `card/C4c-three-pane-shell` 推送，不得修改 `main`，不得覆盖或修改既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-C4c -b verify/C4c-visual 6706b09869ece5329c12738c2782bf303f305b29`，只在这里写。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/C4c-visual` 并 `gh workflow run android.yml --ref verify/C4c-visual` 跑一次 CI，`gh run watch <id> --interval 60`。不访问真实论坛。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-C4c.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/C4c.md` 范围节；依据 `docs/design/product-design.md` §3、§4、§5、§6.3、§10、`docs/design/copy.md` 及 `/home/mir/Documents/hipda-next.wt/_reports/D-3pane.md` 第五节“探针验证”。只读生产代码，不先读实现者测试与报告。
【视觉检查】（用读图工具逐张打开 `hipda/src/test/snapshots/images/shell_*.png` 全部新/更新基线，并核实非 `shell_*` 基线零改动）：
1. `559×749`（Fold8 竖拿，浅/深）：单栏 + 底部居中毛玻璃胶囊（宽 ≤ 420dp）、文字不裁切。
2. `640×1024`（图标轨单栏，浅/深）：左侧 `64dp` 纯色 `surfaceContainerLow` 图标轨 + 右侧 `1dp` 分隔线 + 单栏内容，无底部胶囊。
3. `749×559`（Fold8 横展紧凑三栏，浅/深 + `240dp` 浮层展开态）：`64dp` 轨首屏无需滚动完整可见 ≥3 个默认关注版块、`我的` 分组默认收起、短标/图标清晰可辨；浮层展开态右侧 `20dp` 圆角 + 暗罩不挤压底层内容、文字无裁切无重叠。
4. `933×696`（折叠屏宽态完整三栏，浅/深 + 主动收起态）：常驻展开左栏（`187dp`）图标/全名/角标清晰，顶底 `48dp` 固定区无异色补丁；主动收起态平滑退为 `64dp` 图标轨。
5. `840×900` 最大字号 `1.3×`（及 `411×891` 最大字号）：验证 `176dp` 最小展开左栏在最大字号下 `1.25×` 钳制生效、不裁字不重叠不溢出。
6. `1280×800`（平板完整三栏，浅/深）：左栏封顶 `220dp`，层级清晰、对比度 ≥ 4.5:1。
7. `891×411`（紧凑胶囊）、`340×800`（窄窗胶囊）、`411×891` 及 **侧栏态滚底截图**：确认侧栏态滚到底部无胶囊重叠、顶底固定区与内容无裁字无遮挡。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/C4cProbeTest.kt`）：
a. **窗口尺寸动态变化 `559 ↔ 749 ↔ 933 ↔ 1280dp`**：在单次会话中依次动态变更窗口尺寸（单栏 ↔ 紧凑三栏 ↔ 完整三栏往返），验证在 `≥ 840dp` 主动收起侧栏后，切到 `749` / `559` 再回到 `933` / `1280` 时侧栏收起偏好与当前选中项（关注版块或 `我的` 子项）均不丢失、双向映射与 §3.2 一致。
b. **浮层打开时旋转 / 跨断点不崩**：在 `749×559dp` 点 `展开侧栏` 打开 `240dp` 浮层，分别动态切换至 `559×749dp`（跨下 600dp 切回单栏）与 `933×696dp`（跨上 840dp 升常驻展开），以及模拟配置重建，断言均不崩溃、无残留暗罩或死锁。
c. **非整数浮点宽与铰链分隔边界**：向 `calculateShellPaneLayout` 传入真机非整数宽度（如 `748.59f`、`851.69f`、`1023.6f`）与铰链分隔场景（`749dp` 居中折痕、`701dp` 半折退单栏、`260|440dp` 非对称退单栏），断言 `L + M + R == widthDp.roundToInt()`、三栏与启用铰链双栏 `R ≥ 360`、退单栏 `R == 0` 且紧凑胶囊避开铰链。
d. **浮层焦点锁定 / 返回 / `Escape` 与减少动态效果**：`240dp` 浮层打开后验证底层内容不可遍历/不可交互、系统返回与 `Escape` 均可关闭；`withAnimatorDurationScale(0f)` 下浮层开合与 `≥ 840dp` 侧栏原地伸缩均 1 帧到位。
e. **Fold8 `749×559dp` 首屏 ≥3 版块与碰撞/未知 `fid` 回退**：验证首屏无需滚动可见 ≥3 个关注版块；传入未知 `fid` 与同图标版块组合，验证自动回退为短标/首字徽标无崩溃且视觉可区分。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `6706b09869ece5329c12738c2782bf303f305b29`、CI 链接、上述视觉检查项与 5 个探针逐项结论、FAIL 触发路径与裁切/重叠问题、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-C4c.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
