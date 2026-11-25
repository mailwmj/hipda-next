# 验证卡 V-E1：独立视觉复验与行为探针验证 E1（commit 2e8bed9）

【角色】独立视觉与交互行为验证者，不先读实现者的测试与 `_reports/E1.md` 报告；结论只来自你逐张审阅录制截图与自己编写跑出的探针证据。
【边界】对象 `origin/card/E1-forum-page` @ `2e8bed9`，相对 `db7dba6`，只读：不得向 `card/E1-forum-page` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E1 -b verify/E1-probes 2e8bed9`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E1-probes` 并 `gh workflow run android.yml --ref verify/E1-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有网络验证只用离线假实现/`MockWebServer`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E1.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E1.md` 范围与验收节；基座 API 参考 `_reports/{E0,D4,D8a}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §3.1/§4.2/§5/§6.3/§7.1/§10）、`docs/design/copy.md`、`_cards/BATCH-5.md`。只读生产代码与截图，不先读实现者测试与报告。
【视觉与截图基线逐张复验】
1. 执行 `git diff --name-status db7dba6...2e8bed9 -- hipda/src/test/snapshots/`，确认仅新增 `forum_*.png` 且既有基线（`theme_specimen_*`/`shell_*`/`glass_*`/`challenge_*`）**0 漂移**，并在本地与 CI 跑通 `-Proborazzi.test.verify=true`；
2. 用读图工具逐张检查全部 `hipda/src/test/snapshots/images/forum_*.png`：
   - 核对 6 宽 × 浅/深、`891×411`、`340×800`、`411×891` 第 5 档字号×1.3 的排版均衡性、触控区高度（≥ `48dp`）、`管理版块` 固定尾钮不随标签滑走、文字无截断/重叠/出界；
   - 专项检查**下滚折叠上行后透明吸顶标签行 + 荧光笔选中高光**：确认上行已隐藏而标签行完整吸顶、荧光笔为柔和主题色渐变（非 `#FFB003`）、帖子内容可见从玻璃顶栏后方滚过且顶栏文字清晰可读；
   - 专项检查 **`V-C1`** 深色与纯黑截图：视觉确认已读标题明显弱化于未读标题且保持清晰可读；检查 `< 300dp` 窄中栏紧凑副行排版及空/错/骨架态。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt`，Robolectric Compose UI 独立探针，严禁访问真实站点）：
a. **账号异步水合恢复探针（B4-E1 ①）**：构造 `currentAccount` 初始为 `null` 且 `ThreadListRepository` 在未登录时返回 `AuthRequired` 的离线场景；挂载 `ForumPane` 确认展示需要登录提示后，向 `currentAccount` 发射 `SavedAccount`，断言自动触发重拉并展示真实帖子标题，不滞留错误态。
b. **顶栏折叠零跳动与严格兄弟玻璃拓扑探针（B4-E1 ②）**：在 `411×891dp` 下挂载 `ForumPane`（`useGlassTopBar = true`、`showForumTabs = true`），量测展开态首项偏移与 `LazyColumn` 顶部留白，向下滚动触发上行完全收起后断言：标签行仍可见吸顶、`LazyColumn` 顶部 `contentPadding(top)` 未收缩（差值 `== 0dp`）、`HiGlassMarker` 与 `LayerBackdrop` 为严格兄弟且单栏总玻璃区域 `≤ 2`；再在 `useGlassTopBar = false`（铰链双栏）下断言顶栏 0 玻璃节点；减动效下 0ms 一帧到位。
c. **`今日新帖` `searchId` 翻页 + 黑名单/已读联动探针（B4-E1 ③）**：切换至 `今日新帖` 标签，验证第 1 页加载后滚动触底触发第 2 页时 `SearchRepository.getNewPosts` 收到第 1 页返回的精确 `searchId`；运行期向 `BlacklistRepository` 加入某作者断言该帖立即消失，向 `BrowseHistoryDao` 写入某 `tid` 断言该行立即转为已读样式。
d. **排序持久化、刷新新帖胶囊、窄中栏与 `V-C1` 对比度探针**：切换排序菜单断言持久化并以显式 `sortByPostTime` 发起查询（`R-D4 S1`）；注入顶部新增 3 条帖子的刷新结果断言展示 `有 3 条新帖` 胶囊且点击滚顶；在 `280dp` 窄宽下断言紧凑排版无溢出；遍历 20 预设 × 浅/深/OLED 计算未读与已读标题 WCAG 对比度均 `≥ 4.5:1` 且深色/OLED 已读区分度明显改善。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `2e8bed9`、CI 链接、视觉逐张审阅与 4 个探针（a–d）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E1.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E1-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E1.md`
