# 验证卡 V-E2d：独立逐帧向上插页零闪跳与连续加载探针验证 E2d（commit c447d4a）

【角色】独立交互与逐帧行为验证者，不先读实现者的测试与 `_reports/E2d.md` 报告；结论只来自你自己编写跑出的逐帧探针证据与快照零漂移核查。
【边界】对象 `origin/card/E2d-thread-continuous-paging` @ `c447d4a`，相对 `3dd65c4`，只读：不得向 `card/E2d-thread-continuous-paging` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E2d -b verify/E2d-probes c447d4a`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E2d-probes` 并 `gh workflow run android.yml --ref verify/E2d-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有网络验证只用离线假实现。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E2d.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2d.md` 范围与验收节；基座与数据层 API 参考 `_reports/{E0,E2a,D5}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §6.3/§7.3/§10.1）、`docs/design/copy.md`、`_cards/BATCH-5.md`。只读生产代码，不先读实现者测试与报告。
【基线零漂移核查】
1. 执行 `git diff --name-status 3dd65c4...c447d4a`，确认生产代码仅改 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt`，且 `git diff --name-status 3dd65c4...c447d4a -- hipda/src/test/snapshots/` 为空（既有快照 **0 漂移**）；本地与 CI 跑通 `-Proborazzi.test.verify=true`。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E2dProbeTest.kt`，Robolectric Compose UI 独立逐帧探针，严禁访问真实站点）：
a. **向上插页首帧零闪跳反伪造探针（最高优先级，B4-E2a ①）**：
   - 构造离线可控多页假 `ThreadDetailRepository`（第 1 页 15 楼 `p101..p115`、第 2 页 15 楼 `p201..p215`、第 3 页 10 楼 `p301..p310`，均带可辨识楼层文本与不等高多行正文）；
   - 先渲染第 2 页并定位到锚点楼层（如 `p202` 或首可见楼层），记录合入前该锚点楼层节点在窗口中的顶部像素坐标 `topBefore`；
   - 将 **`composeRule.mainClock.autoAdvance = false`**，触发向上预取第 1 页并令第 1 页数据完成返回，**立即推进单帧（`advanceTimeByFrame()` / 16ms）捕获数据合入后的第 1 帧**，并继续逐帧推进至少 10 帧：
     - **硬断言（包含数据合入首帧在内的每一帧）**：锚点楼层距屏幕顶部像素坐标与 `topBefore` 之差严格 **`≤ 1dp`**（详报须打印合入前、首帧及后续帧的实际 px/dp 坐标表）；
     - **反例判定红线**：若实现采用 `LaunchedEffect` 在首帧绘制之后才做 `scrollToItem` 补偿，数据合入后的第 1 帧必定出现明显跳变（`> 1dp`），**必须直接判 FAIL**（即使第 2 帧回弹回原位也不得放过！）。
b. **向下 5 楼自动预取、并发防重与末页态探针**：
   - 从第 2 页向下滚动至距末楼 5 楼内，断言自动触发第 3 页加载且连续多次小幅滚动不会向 `ThreadDetailRepository` 重复发第 3 页请求；第 3 页载完滚到底部展示 `没有更多了` 且不再发第 4 页请求。
c. **头/尾加载失败原地整行重试与玻璃拓扑守卫探针**：
   - 分别在向下预取与向上预取注入失败，断言已展示楼层保持不清空、对应端显示 `加载失败，点击重试`（触控区 ≥ `48dp`），点击整行后成功补载；同时断言全过程 `HiGlassMarker == 1`、`detailGlassState` 严格兄弟拓扑未破坏、楼层 `key = "floor:$postId"` 保持不变。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `c447d4a`、CI 链接、基线零漂移与 3 个探针（a–c，含合入首帧实测偏移 dp）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E2d.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E2d-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E2dProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E2d.md`

【负责人裁定】E2d 采用方案 B：保留上下页底栏（移除留给 E4）；只允许因页尾行漂移的截图重录，逐张核对 _reports/E2d.md 所列图名与原因，其余既有快照必须 0 漂移；核对底栏页码与可见楼层所在页一致。
