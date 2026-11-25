# 验证卡 V-D9：独立探针验证 WorkManager 提醒轮询、免打扰与 R-D7 收口（commit 933a5ac）

【角色】独立黑盒与边界探针验证者，不先读实现者的测试与 `_reports/D9.md` 报告；结论只来自你对照规格与生产源码自己编写并跑出的探针证据。
【边界】对象 `origin/card/D9-notification-polling` @ `933a5ac`，只读：不得向 `card/D9-notification-polling` 推送，不得修改 `main`。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-D9 -b verify/D9-probes 933a5ac`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部探针，再推送 `verify/D9-probes` 并 `gh workflow run android.yml --ref verify/D9-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-D9.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/D9.md` 范围节；依据 `docs/design/tech-plan.md` §6（T5）、`docs/design/product-design.md` §7.10、§8、`hipda/src/main/java/net/jejer/hipda/ui/HiNextApplication.kt`、`AndroidManifest.xml` 与 `_reports/R-D7.md`。只读生产源码，不先读实现者测试与报告。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/D9ProbeTest.kt`，Robolectric + `MockWebServer` + `androidx.work.testing`，零 `Thread.sleep`、零真实网络）：
a. **免打扰时段穷举边界探针**：
   - 跨午夜默认窗口 `22:00–08:00`（`1320..480`）：验证 `21:59`（false）、`22:00`（true）、`23:59`（true）、`00:00`（true）、`07:59`（true）、`08:00`（false）；
   - 同日窗口 `13:00–14:30`（`780..870`）：验证 `12:59`（false）、`13:00`（true）、`14:29`（true）、`14:30`（false）；
   - 退化与关闭边界：`begin == end`（`600..600` 返回 false）、`silentModeEnabled = false`（任何分钟均返回 false）、极值 `00:00–23:59` 与 `23:59–00:00`。
b. **`BackgroundSilent` 遇 Cloudflare 验证直接放弃 + 4 级零请求门禁探针**：
   - 分别在① 通知关闭、② 免打扰时段内、③ 未登录、④ 手动退出、⑤ 应用前台可见 5 种场景执行 `NotificationPollingWorker.doWork()`，断言全部返回 `Result.success()` 且 `MockWebServer` 请求数严格为 **0**；
   - 在后台、已登录、非免打扰下让 `MockWebServer` 返回 HTTP 403 + `cf-mitigated: challenge`：断言 Worker 使用 `AccessPriority.BackgroundSilent`，`ChallengeSolver` 调用数为 **0**，不弹出验证界面，返回 **`Result.success()`（绝非 `Result.retry()`）**，且本地既有未读数不被误清零。
c. **`WorkManager` 真实初始化链路 + 旧 `NotiWorker` 共存探针**：
   - 核验 `HiNextApplication` 实现 `androidx.work.Configuration.Provider` 且其 `workManagerConfiguration.workerFactory` 为 `HiltWorkerFactory`；
   - 使用 `WorkManagerTestInitHelper` 初始化 `WorkManager`，测试 `NotificationScheduler` 调度 15 分钟周期任务（校验 `NetworkType.CONNECTED`、独立任务名不覆盖 `"noti_work"`、`KEEP` 幂等与取消），并实测通过该 `workerFactory` 回退构造旧 `NotiWorker` 无崩溃、解析编译期/清单确认 `WorkManagerInitializer` 已配置 `tools:node="remove"`。
d. **R-D7 S1 / S2 / S3 独立回归探针**：
   - S1：模拟已登录用户发送短消息时服务端返回含 `"您已经登录……您无权进行当前操作"` 的组受限 `NOPERM` AJAX XML，断言仅发起 **1 次 POST、0 次静默重登** 并返回 `SmsSendOutcome.Rejected`（而非 `AuthRequired`）；同时对照未登录 `NOPERM`（`"您还未登录"`）仍正常触发 1 次静默重登并重试成功（含回显正文带 `"您还未登录"` 不误报失败）；
   - S2：标记提醒全部已读建立磁盘 `suppressedServerNotifyCount` 后，通过 `updateFromPageMeta(PromptCounts(0))` 归零，随后重建新 `DefaultNotifyRepository` 实例（模拟冷启动重启），验证新到达同数量页头提醒不会被旧磁盘水位吞掉；
   - S3：向 `pollUnreadCounts` 注入无短消息容器但含 `div.alert_error` 的页面以及写盘抛 `IOException` 的 DataStore，验证正确返回结构化错误/降级而不向 Worker 抛出未捕获异常。
e. **通知发布、术语与边界纯洁性探针**：
   - 模拟轮询返回“1 条未读短消息 + 0 提醒”与“2 条未读短消息 + 3 条提醒”，断言系统通知文案清晰且不含 `product-design.md §8` 禁用词（“私信”“站内信”“消息通知”等）；验证 `HiApplication.java` 与旧 `NotiWorker.java` 未被破坏，且零真实域名访问。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `933a5ac`、CI 链接、5 个探针（a–e）逐项结论、FAIL 触发路径与复现步骤、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-D9.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
