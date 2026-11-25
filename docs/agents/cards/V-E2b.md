# 验证卡 V-E2b：独立视觉复验与图片三分支补偿/安全白名单探针验证 E2b（commit 1597b4e）

【角色】独立视觉与交互行为验证者，不先读实现者的测试与 `_reports/E2b.md` 报告；结论只来自你逐张审阅录制截图与自己编写跑出的探针证据。
【边界】对象 `origin/card/E2b-thread-images` @ `1597b4e`，相对 `3dd65c4`（或实际基线 `3dd65c4`），只读：不得向 `card/E2b-thread-images` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E2b -b verify/E2b-probes 1597b4e`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E2b-probes` 并 `gh workflow run android.yml --ref verify/E2b-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有图片请求只用离线可控 `LocalHiImageLoader`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E2b.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2b.md` 范围与验收节；基座 API 参考 `_reports/{E2a,E0,B8}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §6.3/§6.4/§7.9/§10.1）、`docs/design/copy.md`、`docs/agents/DISPATCH.md` §4、`_cards/BATCH-5.md`。只读生产代码与截图，不先读实现者测试与报告。
【视觉与截图基线逐张复验】
1. 执行 `git diff --name-status 3dd65c4...1597b4e`，确认生产代码仅改 `feature/threaddetail/media/*` 与 `strings_thread_media.xml`，且 `git diff --name-status 3dd65c4...1597b4e -- hipda/src/test/snapshots/` 仅新增 `thread_image_*.png`（既有非 `thread_image_*` 快照 **0 漂移**）；本地与 CI 跑通 `-Proborazzi.test.verify=true`；
2. 用读图工具逐张检查全部 6 张 `hipda/src/test/snapshots/images/thread_image_*.png`（`411`/`749`/`1280dp` × 浅/深）：核对默认 `4:3` 占位态、已加载态圆角与裁切、失败重试态、省流 `点击加载图片（{size}）` 态的层次清晰度、触控区高度（`≥ 48dp`）及深浅色对比度。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E2bProbeTest.kt`，Robolectric Compose UI 独立探针，严禁访问真实站点）：
a. **三分支无跳动滚动补偿与回收缓存反伪造探针（最高优先级，B4-E2b）**：在 `LazyColumn`（固定键 `"floor:$postId"`）中注入可控 `CompletableDeferred` 的离线 `LocalHiImageLoader`：
   - **分支 ①（首楼上方变长图）**：先滚至中段楼层 `floorAnchor` 并记录其屏幕顶部像素坐标 `topBefore`，再释放位于其**上方**楼层图片返回真实高图（如 `1:2` 长图），推进渲染后硬断言 `floorAnchor` 距顶变化 **`≤ 1dp`**；
   - **分支 ②（首楼自身/下方变高）**：释放 `floorAnchor` **自身或下方**楼层图片返回真实高图，推进渲染后硬断言 `floorAnchor` 距顶变化严格 **`== 0dp`（0 误滚）**；
   - **分支 ③（回收再入命中缓存）**：将该已加载图片楼层滑出视口回收后再滚回，硬断言首帧即按真实宽高比布局（非 `4:3` 回退跳变）。
b. **图片 URL 白名单全反例探针（`R-E2a R2-S1` + `DISPATCH.md` §4）**：构造记录请求的假 `ImageLoader` 与 `onOpenImageViewer` 回调，逐一渲染携带 `file:///etc/hosts`、`content://media/external/images/1`、`javascript:alert(1)`、`intent://x#Intent;end`、`data:image/png;base64,AA==`、`mailto:user@example.com`、`" FILE:///a.png"`、`"http://evil.com/\nfoo"` 的 `ContentBlock.Image`，硬断言所有危险 URI 的假 `ImageLoader` 请求数 **`== 0`**、点击节点后 `onOpenImageViewer` 回调数 **`== 0`**；再喂合法 `https://` 图片断言正常发起加载并点击上报非零 `sourceBoundsInWindow`。
c. **自动流量节省、点击加载取消与失败重试探针**：模拟省流开启+计费网络，断言默认不发请求且显示 `点击加载图片（{size}）`（未知大小无括号），点击后进入加载中（含 `正在加载图片，点击取消` 语义）再点取消能中止在途请求并回退占位；注入加载失败断言显示 `图片加载失败` + `重试`，点 `重试` 后成功加载。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `1597b4e`、CI 链接、视觉逐张审阅与 3 个探针（a–c）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E2b.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E2b-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E2bProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E2b.md`

【负责人裁定】E2b 获授权改 PostBodyRendererTest 图片用例与 ThreadDetailScreenshotTest（注入离线 ImageLoader），重录 9 张 thread_detail_*.png：逐张核对图片区域以外 0 变化、测试 0 真实网络。
