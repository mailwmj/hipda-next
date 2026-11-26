# 验证卡 V-E4：独立视觉复验与位置门闩/玻璃卸载/内联跳页探针验证 E4（commit 6526c1b677d934b2381aec94defc398ff3a26887）

【角色】独立视觉与交互行为验证者，不先读实现者的测试与 `_reports/E4.md` 报告；结论只来自你逐张审阅录制截图与自己编写跑出的探针证据。
【边界】对象 `origin/card/E4-thread-chrome-and-position` @ `6526c1b677d934b2381aec94defc398ff3a26887`，相对 `70da638`，只读：不得向 `card/E4-thread-chrome-and-position` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E4 -b verify/E4-probes 6526c1b677d934b2381aec94defc398ff3a26887`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E4-probes` 并 `gh workflow run android.yml --ref verify/E4-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），全部数据层交互只用离线假实现/`MockWebServer`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E4.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E4.md` 范围与验收节；基座与上游 API 参考 `_reports/{E0,E2a,E2d,E2c,E3,D5,D8a,V-C4d}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §4.1/§5/§6.3/§7.1/§7.3/§10）、`docs/design/copy.md`、`docs/agents/DISPATCH.md` §4、`_cards/BATCH-5.md`。只读生产代码与截图，不先读实现者测试与报告。
【视觉与截图基线逐张复验】
1. 执行 `git diff --name-status 70da638...6526c1b677d934b2381aec94defc398ff3a26887 -- hipda/src/test/snapshots/`，确认仅新增 `thread_chrome_*.png` 及按方案 B 移除 `ThreadDetailPaginationBar` 更新的 `thread_detail_*.png`（`forum_*`/`login_*`/`shell_*`/`multipane_*`/`thread_image_*`/`thread_interact_*` **0 漂移**）；本地与 CI 跑通 `-Proborazzi.test.verify=true`；
2. 用读图工具逐张检查全部 `thread_chrome_*.png`（约 10 张）与重录后的 `thread_detail_*.png`：
   - 核对标准三段工具条（`411`/`749`/`1280dp` × 浅/深）、窄容器紧凑态（`340dp` & `360dp` 右栏 + `1.25×`）与三栏右栏居中不跨线；
   - 核对进度填充在浅/深/纯黑下页码文字清晰可读（符合 `V-C4d`）；
   - 核对内联跳页条 `< 480dp` 双行（含 `340dp` + `1.25×` 零挤压）与 `≥ 480dp` 单行排版，以及 `ThreadDetailPaginationBar` 彻底移除后底部留白干净无截断。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E4ProbeTest.kt`，Robolectric Compose UI 独立探针，严禁访问真实站点）：
a. **慢加载恢复门闩防冲写与位置栈探针（最高优先级，B4-E4 ①）**：预置历史阅读位置 `(page = 5, pid = "p503", offset = 120)`，用 `CompletableDeferred` 挂起首次 `loadPage(5)` 请求并推进虚拟时钟（含滚动尝试与提前退出），硬断言门闩未打开前 `saveReadingPosition` 调用次数严格 **`== 0`**；放行第 5 页恢复定位后再滚动，断言去抖后正确落盘新位置；再从第 5 页点内联跳页去 `首页` 后按系统返回，断言先出栈回到第 5 页 `p503` 再按才离开帖子。
b. **`suppressBottomGlass` 整块卸载 `HiGlassRegion` 与三栏满配预算探针（B4-E4 ②）**：
   - 在单栏详情切换 `DetailChromeSpec.suppressBottomGlass = false -> true -> false`：断言 `true` 时工具条子树（含外层 `HiGlassRegion`）`HiGlassMarker == 0` 且在独立挂载工具条测试下 `detailGlassState.hasSourceDemand == false`；`false` 时工具条整体仅计 `1` 个 `HiGlassMarker`；
   - 联立真实 `E1` + `E3` + `E4` 三栏满载，断言全屏 `HiGlassMarker == 3`、独立 `LayerBackdrop` 采样源 `≤ 2`、静止 `drawBackdrop` pass `≤ 5`。
c. **内联跳页 IME 零滚底、可打断、`V-C4d` 对比度与外链白名单探针**：
   - 在 `340dp` + `1.25×` 下展开内联跳页条断言双行全控件 `≥ 48dp` 且无重叠溢出；拖动滑块验证仅预览不触发 `loadPage`/滚屏；注入底部 IME insets 上移跳页条，硬断言底层楼层 `LazyColumn` 首可见项像素偏移变化 **`== 0dp`**；展开中途反向点击关闭断言逐帧连续；点 `首页`/`末页`/输入确认均自动收起；
   - 遍历全 20 套预设 × 浅/深/OLED 断言进度填充前景文字 WCAG 对比度 `≥ 4.5:1`；
   - 测试“更多”菜单 `在浏览器中打开` 对危险 scheme（`javascript:`/`intent:`/`file:`/`content:`/`data:`）零触发系统 `LocalUriHandler`，合法 `https://` 正常放行，收藏/关注撤销 Snackbar 闭环通过。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `6526c1b677d934b2381aec94defc398ff3a26887`、CI 链接、视觉逐张审阅与 3 个探针（a–c）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E4.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E4-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E4ProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E4.md`

【负责人追加重点】核对 E4 卡末“派发前核查追加”1–5：cancelPagingJobs 修复与回退必红单测；预授权的既有测试仅改触发方式、断言未放宽；PositionStack 返回优先于 E3 返回栈且 MultiPaneBackStackTest 未改；滚动写入点唯一。

【负责人追加重点】逐条核对 E4 报告既有测试改动清单：玻璃预算上限 ≤3/≤2/≤5 未放宽、偏移 ≤1dp 未放宽、无断言被删；重录 PNG 仅底部区域变化。

【负责人追加：合入 main 后的集成探针】E4 基于 70da638，之后 main 已合入 E-slots、E8、E10、E12（当前 origin/main@1dc594e，merge-tree 零冲突）。在 verify/E4-probes 上先 `git merge --no-ff origin/main`，探针 a–c 在合并态上全部重跑并分别给结论；另加：(d) 合并态下 E-slots 的看图/消息/设置浮层打开时，E4 底部工具条与位置栈不响应返回、不误卸载/误恢复；(e) 从 M6 页面（搜索结果、收藏、短消息里的帖子链接）进入帖子后返回，位置恢复门闩只触发一次且落在正确楼层。
