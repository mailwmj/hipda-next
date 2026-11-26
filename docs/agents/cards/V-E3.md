# 验证卡 V-E3：独立视觉复验与多栏连续性/玻璃预算探针验证 E3（commit 6fa4765）

【角色】独立视觉与多形态交互验证者，不先读实现者的测试与 `_reports/E3.md` 报告；结论只来自你逐张审阅录制截图与自己编写跑出的探针证据。
【边界】对象 `origin/card/E3-multipane-container` @ `6fa4765`，相对 `b7557bf`（含 `E1` 与 `E2a`），只读：不得向 `card/E3-multipane-container` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E3 -b verify/E3-probes 6fa4765`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E3-probes` 并 `gh workflow run android.yml --ref verify/E3-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有数据层依赖只用离线假实现/`MockWebServer`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E3.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E3.md` 范围与验收节；基座与上游 API 参考 `_reports/{E0,R-E0,E1,E2a,R-C4d}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §3.1–§3.2、§4.1–§4.4、§5、§6.3、§10.1–§10.3）、`docs/design/copy.md`、`_cards/BATCH-5.md`。只读生产代码与截图，不先读实现者测试与报告。
【视觉与截图基线逐张复验】
1. 执行 `git diff --name-status b7557bf...6fa4765`，确认零触碰 `feature/forum/*`、`feature/threaddetail/*`、`feature/login/*`、`feature/common/*`，且 `git diff --name-status b7557bf...6fa4765 -- hipda/src/test/snapshots/` 仅新增 `multipane_*.png`（既有 `shell_*`/`forum_*`/`thread_detail_*`/`login_*`/`theme_specimen_*`/`glass_*`/`challenge_*` **0 漂移**）；本地与 CI 跑通 `-Proborazzi.test.verify=true`；
2. 用读图工具逐张检查全部约 18 张 `hipda/src/test/snapshots/images/multipane_*.png`：
   - 核对 6 宽 × 浅/深真实列表+详情协同、`749×559` 浮层展开（右缘 20dp 圆角 + 暗罩）、`933×696` 左栏收起、全屏阅读态（正文最大 `680dp` 居中）的对齐、留白与 `< 300dp` 窄中栏可读性；
   - 核对模拟 `FoldingFeature` 的展平三栏、铰链双栏（左顶栏纯色、无内容压入铰链带、紧凑胶囊居中列表侧）与铰链退单栏（居中较宽侧）的视觉准确度。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E3ProbeTest.kt`，Robolectric Compose UI 独立探针，严禁访问真实站点）：
a. **空右栏拖动底线、拖拽吸附与全屏阅读探针（B4-E3 ①）**：
   - 在 `933×696dp` 三栏 `hasDetailContent == false` 下对分隔线执行大幅向左拖动（逐帧推进），硬断言每一帧中栏宽度均 **`≥ M_default`** 且不可进入 `0dp`；
   - 选中帖子后拖动分隔线验证跟手与松手吸附、`CustomAccessibilityAction` 切换档位及横竖屏持久化；点详情顶栏 `全屏阅读` 断言左中栏隐藏且仅剩 1 个采样源，再点 `显示列表` 精确还原原左栏形态与中栏宽度。
b. **非论坛入口展平↔半折晋升回退与 `R-E0 R2-S3/S4` 反伪造探针（B4-E3 ②）**：
   - 从 `History` 与 `Notify` 入口选帖后在展平三栏 ↔ 半折铰链双栏 ↔ 按返回键间切换，断言左栏承接原来源子列表（绝不跳回论坛）且按返回退回 `我的`/原副列表；
   - 三栏切换 `tidA → tidB` 时在详情内容内放置无显式 `tid` 键的 `rememberSaveable` 计数器，硬断言切到 `tidB` 后计数器归零隔离不串态；
   - 开帖态在左栏/顶栏切换版块（含 `TodayNew`），挂 `AutoCloseable` 监听旧帖 `ThreadDetailViewModel`，硬断言右栏清空且旧 `ViewModelStore` 触发 `onCleared()`/`clear()`；
   - 在 `StateRestorationTester` 下将论坛列表与详情均滚至非首项后模拟进程被杀重建，断言两者 `LazyListState` 滚动偏移完整保留；
   - 在 `411×891dp` 单栏进入帖子详情，硬断言底部 `HiNavigationPill` 退场消失、返回一级页恢复并显示合并未读数角标。
c. **三栏 3 区域玻璃预算、铰链退纯色与多栏 Snackbar 探针**：
   - 联立真实 `E1` + `E2a` 在三栏下遍历 `LayoutInfo` 与语义树：硬断言 `HiGlassMarker ≤ 3`、独立 `LayerBackdrop` 采样源 `≤ 2`（中右跨栏隔离）、静止 `drawBackdrop` pass `≤ 5`；
   - 模拟物理铰链双栏断言左列表顶栏 0 玻璃节点（纯色）、总 `HiGlassMarker ≤ 3`；
   - 触发 `PaneSnackbarTarget.DetailPane` 与 `ContentArea` 断言前者屏幕坐标严格位于分隔线/铰链右侧、不跨线。
d. **`R-C4d S1–S3` 独立复验探针**：验证常驻侧栏传入已滚动 `listState` 首次挂载不被归零（`S2`）、底部胶囊拖拽悬停中目标项呈现预选高亮且松手前不触发切页（`S3`）、未传 `managesGlassSource` 切页首帧无父子同源采样（`S1`）。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `6fa4765`、CI 链接、视觉逐张审阅与 4 个探针（a–d）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E3.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E3-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E3ProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E3.md`
