# 验证卡 V-A5b：独立探针验证透明验证宿主与求解状态机（commit 0e0da1b）

【角色】独立验证者，没看过实现者自评；结论只来自你跑出的证据。
【边界】对象 `origin/card/A5b-challenge-host` @ `0e0da1b49f73306e9cab675ed48adfb380024b85`，只读。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A5b -b verify/A5b-probes 0e0da1b49f73306e9cab675ed48adfb380024b85`，只在这里写。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部探针，再推送并 `gh workflow run android.yml --ref verify/A5b-probes` 跑一次 CI，`gh run watch <id> --interval 60`。不访问真实论坛。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-A5b.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A5b.md` 范围节；依据 `docs/design/cloudflare-research.md` 第五节第 3 小节。只看生产代码，不先读实现者测试。
【探针】（Robolectric + 本卡的 WebView 抽象接口的你自己写的假实现；按真实 WebView 行为建模：`onPageFinished` 可能多次触发、Cookie 写入可能晚于页面完成、回调在主线程）
a. 正常通过：第一次 `onPageFinished` 仍是验证页、第二次是正常页且新 `cf_clearance` 晚 1 秒才出现 → 判定通过、`syncFromWebView` 只调用一次、Activity 关闭、返回 `Cleared`。
b. 只拿到快照里的旧 `cf_clearance` → 不得判通过；8 秒后交给 presenter（默认取消）→ 备份恢复、Activity 关闭、返回 `Cancelled`。
c. 收到 `interactiveBegin` 消息 → 立即交给 presenter，不等满 8 秒；来源不是 4d4y.com/challenges.cloudflare.com 的伪造消息被忽略。
d. `onRenderProcessGone` → 不崩溃、返回失败、备份恢复、Activity 关闭。
e. 调用方取消（取消 solve 协程）在 3 个时点（启动 Activity 前、页面加载中、presenter 等待中）→ Activity 一定关闭、备份恢复、无泄漏的 WebView。
f. Activity 在求解中被重建（模拟旋转：`ActivityScenario.recreate()`）→ 不崩溃；求解要么继续要么以失败结束，不会悬挂到网关 120 秒超时（给出实际行为）。
g. 两次连续 `solve`（第一次结束后立即第二次）→ 第二次使用新的 Activity/WebView，不复用已销毁的实例。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `0e0da1b`、CI 链接、每个探针一行、FAIL 触发路径、详报路径。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
