# 验证卡 V-E0：独立端到端真保活与基座探针验证 E0（commit 1149518）

【角色】独立视觉与端到端行为验证者，不先读实现者的测试与 `_reports/E0.md` 报告；结论只来自你独立核查截图零漂移与自己编写跑出的探针证据。
【边界】对象 `origin/card/E0-reading-tracer-bullet` @ `1149518`，只读：不得向 `card/E0-reading-tracer-bullet` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E0 -b verify/E0-probes 1149518`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E0-probes` 并 `gh workflow run android.yml --ref verify/E0-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有网络验证只用离线 `MockWebServer` + 仓内脱敏样本。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E0.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E0.md` 范围与验收节；依据 `docs/design/product-design.md`（v0.4 §4–§5、§8、§10.1）、`docs/design/copy.md`、`_cards/BATCH-5.md`。只读生产与 `src/debug` 代码，不先读实现者测试与报告。
【视觉与基线零漂移检查】
1. 执行 `git diff --name-status e71c3fb...1149518 -- hipda/src/test/snapshots/`，确认返回空（**0 张既有快照漂移**，特别是 `theme_specimen_*.png`、`shell_*.png`、`glass_specimen_*.png`、`challenge_*.png` 零变动），并在本地及 CI 跑通 `-Proborazzi.test.verify=true`；
2. 逐张检查 `E0` 端到端渲染出的单栏列表、单栏详情与 `749×559dp` 三栏（列表 + 详情）在探针中不崩溃、无遮挡/文字截断错误，且 `PreviewActivity` 的 `ThemeSpecimenScreen(onOpenShellDemo = null)` 与 `onOpenShellDemo != null` 分支表现正确。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt`，Robolectric Compose UI + 离线 `MockWebServer` 测试，严禁访问真实站点）：
a. **真实数据层端到端通路探针（真实 `DefaultThreadListRepository` → 真实 `DefaultThreadDetailRepository`）**：
   - 使用离线 `MockWebServer` + 仓内真实脱敏样本（`thread_list/forum_2_p1_lastpost.html` 与 `thread_detail/thread_p1.html`）+ 内存 Room 构造**真实的 `DefaultThreadListRepository` 与 `DefaultThreadDetailRepository`** 驱动 `NextAppRoot`；
   - 验证进入论坛页即渲染真实样本中的帖子标题，点击帖子进入详情页渲染真实楼层内容与页码；验证注入网络/鉴权/站点错误时，页面经 `UiErrorMapper` 显示符合 `copy.md` 的中文原因短语，绝不包含异常类名或 HTTP 状态码。
b. **单栏 ↔ 三栏真保活反伪造探针（最高优先级，严防用销毁重建冒充保活）**：
   - 使用带请求计数器的离线可控 Repository（模拟 30 条帖子与 25 层楼），启动于 `411×891dp` 单栏：
     1. 向下滚动论坛列表至非首项（如第 8 项以后，记录当前列表首可见项索引与偏移及 `ThreadListRepository` 请求次数 `listCallsBefore`）；
     2. 点击一篇帖子 `tidA` 进入详情，向下滚动楼层至非首楼（如第 6 楼以后，记录当前详情首可见楼层索引与偏移、`ThreadDetailRepository` 请求次数 `detailCallsBefore` 以及当前详情内部状态/实例标识）；
     3. **动态变更窗口尺寸为 `749×559dp`（三栏）**，再切回 **`411×891dp`（单栏）**，最后按返回回到列表：
        - 硬断言每次跨形态切换后 `detailCalls == detailCallsBefore`（**0 额外详情请求**）、`listCalls == listCallsBefore`（**0 额外列表请求**）；
        - 硬断言详情的首可见楼层与像素偏移在 `411 → 749 → 411` 全程完全保持，且同一 `ThreadDetailViewModel` 实例存活未被重建（不准销毁后靠 0ms 假数据重绘冒充！）；
        - 硬断言返回列表后列表首可见项索引与偏移保持不回顶；
     4. 在 `749×559dp` 三栏中栏点击另一篇帖子 `tidB`（再清空右栏）：硬断言 `tidB` 加载自身内容不串显 `tidA` 状态，且 `tidA` 对应的 `ViewModelStore` 确已触发清理（`onCleared` 生效，无内存泄漏）。
c. **`managesGlassSource` 严格兄弟拓扑、纯色页回退与实时主题切换探针**：
   - 在 `411×891dp` 单栏下检查 `NextAppRoot` 的 `LayoutInfo` 树：论坛页仅 `LazyColumn` 挂 `LayerBackdrop`（外层 `ShellDestinationHost` 零残留），切换至纯色占位页（如 `搜索`/`我的`）时外壳自动回退挂源且全屏 `assertGlassSurfaceCountAtMost(2)` 通过；
   - 在读帖滚至中间楼层时动态修改 `SettingsRepository` 的深浅色/纯黑/字号，断言页面不重建、阅读楼层位置不变且详情请求增量 `== 0`。
d. **术语门禁反例自证与 `ImageLoaderModule` 主线程非阻塞探针**：
   - 独立向 `CopyTerminologyScanTest` 扫描引擎喂入合成违规 XML（含 `发表新帖`、`第3层`、`12层`、`%1$d层`、`NullPointerException`、`HTTP 503`），断言全部精准检出；喂入合法中文（含 `楼层`、`层级`、`图层`、`第 3 楼`、`3楼`），断言 0 误报；
   - 在主线程直接调用 `ImageLoaderModule.provideImageLoader` 验证零阻塞（无 `runBlocking` 卡顿）、配置了 GIF 解码并正确绑定传入的 `OkHttpClient`。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `1149518`、CI 链接、视觉/基线零漂移检查与 4 个探针（a–d）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E0.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
