# 验证卡 V-G2：独立视觉、四路降级与性能探针验证 Kyant `backdrop` 迁移（commit 74c1c8ceac2bcc106bc5589f3cedf3a73b985b08）

【角色】独立视觉与性能验证者，不先读实现者的测试与 `_reports/G2.md` 报告；结论只来自你逐张看图检查与自己编写跑出的探针证据。
【边界】对象 `origin/card/G2-kyant-backdrop` @ `74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`，只读：不得向 `card/G2-kyant-backdrop` 推送，不得修改 `main`，不得覆盖既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-G2 -b verify/G2-probes 74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/G2-probes` 并 `gh workflow run android.yml --ref verify/G2-probes` 跑一次 CI，`gh run watch <id> --interval 60`。不访问真实论坛。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-G2.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/G2.md` 范围节；依据 `docs/agents/decisions.md`（2026-11-25 决策）、`/home/mir/Documents/hipda-next.wt/_reports/G-kudo.md` 第一节、`/home/mir/Documents/hipda-next.wt/_reports/G1.md`。只读生产代码，不先读实现者测试与报告。
【视觉检查】（用读图工具逐张打开受影响的 `glass_specimen_*`、`shell_*`、`theme_specimen_*` 新基线，并核实 `challenge_*`/`feedback_*`/`sample_export_*`/`state_*` 零改动）：
1. **`glass_specimen_*`（亮/暗 × 激活态与降级态）**：核查激活态着色、双层高光描边（浅色暗轮廓垫底、白高光居上无脏灰/发糊）与降级态（API 30 / 省电 / 降低透明度）94% 纯色可读性与对比度；记录 Roborazzi 截图里 `backdrop` 激活态实际可见表现。
2. **`shell_*`（含 `411×891`、`559×749`、`891×411`、`340×800`、`749×559`、`933×696`、`1280×800`）**：确认底部胶囊外观通透清晰无裁切、选中半透明指示器对比清晰、大屏 `≥ 600dp` 纯色左侧栏与三栏布局零回归。
3. **`theme_specimen_*`（亮/暗/纯黑）**：确认纯黑 OLED 下玻璃底色不发死黑、其余主题色板与排版区域无非预期变动。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/G2ProbeTest.kt`，Robolectric + `FakeGlassEnvironmentSignalSource`）：
a. **四路降级矩阵动态切换**：在单个 Composable 树中依次驱动环境信号：`API 34 正常（REFRACTION_AND_BLUR）` → `API 32 正常（BLUR_ONLY，确认不启 lens）` → `API 30（SOLID_DEGRADED）` → `API 34 + 省电模式（SOLID_DEGRADED）` → `API 34 + 降低透明度（SOLID_DEGRADED）` → `API 34 + 高对比度（SOLID_DEGRADED）` → 恢复 `API 34 正常`，验证均不崩溃、配置与语义状态精准切换，且降级态停止离屏采样。
b. **同屏毛玻璃上限 3 块边界**：在单屏同时挂载 3 块 `HiGlassSurface`（模拟三栏右栏顶栏 + 底部工具条 + 面板/顶栏）验证 `assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)` 通过；再加至 4 块验证必然抛出 `AssertionError`。
c. **性能与重组隔离探针**：
   - 列表滚动探针：在带 `hiGlassSource` 的 `LazyColumn` 滚动多帧时，断言 `HiGlassSurface` 内部组合计数不随列表滚动每帧无谓重组；
   - 调参面板局部重组探针：动态修改 `LocalHiGlassStyle`（含新折射高度/强度滑杆值），断言内容列表节点不发生整树重组，且 `HiGlassSurface` 立即响应新折射/模糊参数；验证 `formatGlassStyleParams` 输出稳定包含新折射参数。
d. **多尺寸/极端参数安全探针**：传入 `blurRadius = 0.dp`、`refractionHeight = 0.dp`、`refractionAmount = 0.dp`、以及极大值（`blur = 24.dp`、`refractionHeight = 32.dp`、`refractionAmount = 48.dp`）和极小组件尺寸（`0.dp` / `1.dp` / `32.dp`），验证 `drawBackdrop` / `lens` 无除零、无负值崩溃、无异常。
e. **Haze 零残留与纯洁性自查**：验证 `src/main/`、`gradle/`、`hipda/build.gradle.kts` 无 Haze 引用，`src/main/` 无 `GlassTuningPanel` 引用，`assets/license.txt` 含 Apache-2.0 第三方声明。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `74c1c8ceac2bcc106bc5589f3cedf3a73b985b08`、CI 链接、视觉检查项（1–3）与 5 个探针（a–e）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-G2.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
