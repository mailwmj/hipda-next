# 任务卡 M7-4b 独立验证报告（R8 规则收紧与 M7-4a+M7-4b 全屏走查）

- **结论**：`PASS`
- **被测对象**：`card/M7-4b-r8-tighten` @ `49c087646c69422dbe73f7ff9f97a62cd70b6afb`（实现方 CI [Run 45835004880](https://github.com/mailwmj/hipda-next/actions/runs/45835004880) `success`）
- **合并态验证基线**：在验证工作树 `/home/mir/Documents/hipda-next.wt/V-M7-4b`（分支 `verify/M7-4b-probes`，`HEAD` `d00e3375f6962bf77d261e2d0fb7f0e65e6fdeb9`）合入最新 `origin/main` @ `adaa6c3f0c6eeba08a6941bf69d6efa497d38ff9`（含 `M7-4a`：`:baselineprofile`、`isShrinkResources = true`、`res/raw/keep.xml`、`OfflineForumFixtureServer`），覆盖 `M7-4a + M7-4b` 合并后 R8 Full Mode + 资源收缩双开启态
- **独立验证 CI（`workflow_dispatch emulator=true`）**：[Run 45853222271](https://github.com/mailwmj/hipda-next/actions/runs/45853222271)（`success`；`Build, Lint & Unit Tests` `2m12s` + `Emulator Smoke Test (API 36)` `4m8s`；`TOTAL: 1609 tests, 1609 passed, 0 failed, 0 errors, 0 skipped`）
- **签名与离线边界**：仅使用 CI 临时调试密钥签名 `hipda-ng-release-signed.apk`（未构建任何正式发布签名包，未执行发布流程）；所有端上请求由 `127.0.0.1:40637` 本地回环夹具处理，全程共 `71` 次本地请求，`logcat` 零外网论坛请求
- **主工作区洁净度 & 快照漂移**：`/home/mir/Documents/hipda-next` 仅 `## main...origin/main [behind 8]` 无未提交修改；`49c087646c69422dbe73f7ff9f97a62cd70b6afb` 仅修改 `hipda/proguard-rules.txt` 与 `hipda/src/test/java/net/jejer/hipda/packaging/ProguardRulesGuardTest.kt`；`origin/main...49c087646c69422dbe73f7ff9f97a62cd70b6afb` 在 `**/snapshots/**` 差异为空（0 处 Roborazzi 漂移），CI `Check for Roborazzi diff images` 跳过（0 差异）

---

## 1. 探针总览

| 探针 / 检查项 | 状态 | 核心结论 |
|---|---|---|
| (a) `proguard-rules.txt` 静态与守卫检查 (`M7_4b_R8TightenProbeTest#probe1`) | **PASS** | 0 条宽泛通配 keep（`-keep class com/androidx/net.jejer.hipda.**`）、0 条已删旧库/旧包死规则；保留 `kyant/backdrop` & `shapes` 定向反射/line-number 规则，`:emoji` / `:core-parser` 无残留多余 `.pro` 文件。 |
| (b) Release APK DEX / 方法数 / 关键运行契约 (`M7_4b_R8TightenProbeTest#probe2`) | **PASS** | 合入 `M7-4a`（`startup-prof.txt` + `isShrinkResources = true`）后产物 [hipda-ng-release.apk](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/release-apk/hipda-ng-release.apk) 为 **`4,553,230 B`**（4.34 MiB，较 `6,299,655 B` 基线减少 27.7%）；受 `startup-prof.txt` DEX 布局优化拆分为启动 `classes.dex`（3,311 类 / 21,979 方法）+ 冷包 `classes2.dex`（4,089 类 / 24,127 方法），去重后唯一方法数 **`38,222`**（< 65k 限额，较收紧前 `70,418` 下降 45.7%）；`seeds.txt` 保持 `hipda.*` 19 类、`androidx.*` 156 类；8 个 `@HiltViewModel`、5 个 `@Serializable` `$$serializer`、`NotificationPollingWorker`、`HiDatabase(_Impl)`、`FallbackChallengeJsBridge`、`kyant.backdrop`/`shapes`、3 份 About assets、Compiled Baseline Profile（`16,309 B` / `965 B`）与 139 个 `res/*.png`（覆盖全部 101 个表情 PNG）全部完整存活；14 个已删旧库前缀全部移除。 |
| (c) API 36 模拟器全屏逐一走查 (`FullScreenReleaseSmokeTest`) | **PASS** | 严格离线（飞行模式 + `127.0.0.1:40637`）对 CI 调试签名 `hipda-ng-release-signed.apk`（`net.jejer.hipda.next` R8 Full Mode）依次完成全部 14 个环节共 30 张截图（外加 debug 首页对照 `main-activity-debug.png`），所有文本/图片/表情/玻璃特效/列表与弹窗渲染正常。 |
| (d) Release WorkManager `NotificationPollingWorker` 实机按类名实例化与执行 | **PASS** | 通过 `cmd jobscheduler run -u 0 -n androidx.work.systemjobscheduler -f net.jejer.hipda.next 0` 强制触发后台轮询，logcat 直接证明 `WM-WorkerWrapper` 按精确类名 `net.jejer.hipda.service.NotificationPollingWorker` 创建并执行成功，请求 `/forum/pm.php?filter=newpm` 并发出 `HIPDA_NOTI` 系统通知。 |
| (e) 进程杀死后恢复验证 | **PASS** | 覆盖 `am kill` 保留 `Activity` 保存状态恢复（`pid 4259` -> 新 PID，回到贴子详情第 1 页）以及 `am force-stop` 彻底冷启动恢复（新 `pid 4640`，DataStore/Tink 解密与 `ProbeTester` 登录态持久化保持完整）。 |
| (f) `logcat-release.txt` 运行期异常审计 | **PASS** | `2,201,016 B` 全量 `logcat-release.txt` 扫描 `FATAL EXCEPTION`、`ClassNotFoundException`、`NoClassDefFoundError`、`NoSuchMethodError`、`SerializationException`、`MissingFieldException`、`serializer` 报错、`VerifyError`、`IncompatibleClassChangeError`、`ANR in` 均为 `0` 命中；`4d4y.com` / `hi-pda.com` 均为 `0` 命中。 |

---

## 2. CI 与本地验证汇总（`M7-4a + M7-4b` 合并态）

- **CI Verify 分支构建**（[Run 45853222271](https://github.com/mailwmj/hipda-next/actions/runs/45853222271)）：
  - `ProguardRulesGuardTest`：`5 passed, 0 failed, 0 skipped`
  - `ReleaseResourceAndProfileGuardTest`：`6 passed, 0 failed, 0 skipped`
  - `M7_4b_R8TightenProbeTest`：`2 passed, 0 failed, 0 skipped`
  - 全量单元测试合计：`TOTAL: 1609 tests, 1609 passed, 0 failed, 0 errors, 0 skipped`
  - Room Schema 一致性检查、Lint、Debug/Release APK 与 `:verify-smoke` 测试包产物校验均通过。
- **补充观察（合并 `M7-4a` 后 R8 DEX 拆包行为）**：合入 `adaa6c3` 提供的 `hipda/src/main/generated/baselineProfiles/startup-prof.txt` 后，AGP R8 启动布局优化会在单包方法远低于 65,536 的前提下主动拆出启动热类 `classes.dex`（`21,979` 方法）与冷类 `classes2.dex`（`24,127` 方法），跨分包去重后仅 **`38,222`** 个唯一方法（较 M7-4b 前基线 `70,418` 大幅收紧），`M7_4b_R8TightenProbeTest` 已适配校验证明两个 DEX 均远低于 65k 上限且非方法数溢出分包。

---

## 3. Release 实机全屏走查路径与截图索引

所有产物与日志保存在 [/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/)：
- 汇总记录：[smoke-summary.txt](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/smoke-summary.txt)
- Instrumentation 输出：[instrumentation-release.log](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/instrumentation-release.log)
- Release 全量 Logcat：[logcat-release.txt](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/logcat-release.txt)
- 对照 Debug 首页截图：[main-activity-debug.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/main-activity-debug.png)

| 步骤 | 覆盖界面 / 操作 | 验证截图路径 | 关键结果与目视核验 |
|---|---|---|---|
| 1. 冷启动 | 游客冷启动 + 回环配置登录 + Tink/DataStore 重启直达 | [01-cold-start.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/01-cold-start.png)<br>[01b-cold-start-logged-in.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/01b-cold-start-logged-in.png) | 初始 `PID=3915`，配置 `http://127.0.0.1:40637` 并登录 `ProbeTester` 后强杀重启（`PID=4259`）直接解密凭据并拉起论坛首页，底部液态玻璃导航栏正常。 |
| 2. 版块列表 | 列表上下滑动、切换 `Buy & Sell` 与切回 `Discovery` | [02-forum-list.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/02-forum-list.png) | 版块页签切换、主题列表头像加载与玻璃底栏渲染正常。 |
| 3. 帖子详情 | 进入 `#1` 主题（滚动、展开内联跳页跳转至第 2 页） | [03-thread-detail.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/03-thread-detail.png)<br>[03b-thread-detail-page-jump.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/03b-thread-detail-page-jump.png) | 内联默认/酷猴/呆呆表情、Windows 客户端角标、主楼配图、引用块与第 2/3 页（`16楼`..`21楼`）跳转渲染完整。 |
| 4. 看图 | 点击贴内大图进入 `ImageViewerOverlay` | [04-image-viewer.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/04-image-viewer.png) | 清晰显示 `第 1 楼 · ProbeAuthor1`、`1 / 1` 与底部“查看原图/保存图片/分享”按钮并正常关闭。 |
| 5. 快速回复 | 点击底部“回复”打开 `QuickReplySheet` | [05-quick-reply.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/05-quick-reply.png) | 回复弹窗模糊背景与顶部把手、图片/表情切换、发送按钮正常。 |
| 6. 表情面板 | 三组表情切换（默认 / 酷猴 / 呆呆）+ 插入与整码退格删除 | [06-emoji-panel-default.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/06-emoji-panel-default.png)<br>[06b-emoji-panel-coolmonkey.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/06b-emoji-panel-coolmonkey.png)<br>[06c-emoji-panel-grapeman.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/06c-emoji-panel-grapeman.png) | 101 个打包表情图在 R8 + `isShrinkResources` 下零缺失；点击插入 `:){:2_41:}` 及“删除”整码移除 `{:2_41:}` 回退至 `:)` 均准确。 |
| 7. 完整编辑器 | 从快速回复“展开全屏”+ 首页右上角“发新帖”进入 | [07-full-editor.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/07-full-editor.png)<br>[07b-full-editor-new-thread.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/07b-full-editor-new-thread.png) | 保留草稿正文 `:)`, 版块选择器与标题栏在发布模式正常渲染。 |
| 8. 短消息与提醒 | 短消息列表 -> `FriendOne` 对话页 -> 提醒列表 | [08-messages-sms.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/08-messages-sms.png)<br>[08b-messages-sms-chat.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/08b-messages-sms-chat.png)<br>[08c-messages-notify.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/08c-messages-notify.png) | 列表显示 `FriendOne` 与“离线测试短消息摘要”、聊天正文“离线测试短消息对话正文”、提醒条目“离线测试回复提醒”及未读红点 `1`。 |
| 9. 搜索 | 打开搜索页输入关键词并执行结果解析 | [09-search.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/09-search.png)<br>[09b-search-results.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/09b-search-results.png) | GBK 编码请求 `search.php` 后正确解析出“离线搜索结果主题 #1”。 |
| 10. 收藏 / 关注 / 历史 | “我的”进入收藏/关注双页签及 Room 浏览历史 | [10-favorites.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/10-favorites.png)<br>[10b-favorites-attention.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/10b-favorites-attention.png)<br>[10c-history.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/10c-history.png) | 收藏（“离线收藏示例主题 #1”）、关注（“离线关注示例主题 #2”）及浏览历史（Room 记录主题 `#1`）全部正常展示。 |
| 11. 设置与关于三文档/日志 | 设置列表 -> 关于页 -> 更新记录/捐助名单/开源许可/错误日志 | [11-settings.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11-settings.png)<br>[11b-about.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11b-about.png)<br>[11c-about-release-notes.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11c-about-release-notes.png)<br>[11d-about-donors.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11d-about-donors.png)<br>[11e-about-license.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11e-about-license.png)<br>[11f-about-error-logs.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/11f-about-error-logs.png) | 3 份 `assets/*.txt` 文档均可打开读取无“加载失败”；错误日志弹窗显示“暂无错误日志”。 |
| 12. 账号管理 | 打开账号面板 -> 添加第 2 个账号 `ProbeTester2` -> 切回 `ProbeTester` | [12-account-management.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/12-account-management.png)<br>[12b-account-multi-switch.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/12b-account-multi-switch.png) | 多账号存储加密切换及返回列表后当前身份恢复 `ProbeTester` 均通过。 |
| 13. 进程杀死后恢复 | `am kill` 后状态恢复 + `am force-stop` 后持久化冷恢复 | [13a-process-kill-icicle-restored.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/13a-process-kill-icicle-restored.png)<br>[13-process-kill-restored.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/13-process-kill-restored.png) | `am kill` 杀进程后重启重建回到 `#1` 帖子详情页（状态栏可见上一步 WorkManager 通知图标）；`am force-stop` 后重建进程（`PID=4640`）仍保留 `ProbeTester` 登录态及未读消息角标 `1`。 |

---

## 4. WorkManager 通知轮询 Worker（Release 按类名实例化）与 Logcat 证据

在应用退到后台（`device.pressHome()` + 等待 `ProcessLifecycleOwner` 进入后台）后，通过 `cmd jobscheduler run -u 0 -n androidx.work.systemjobscheduler -f net.jejer.hipda.next 0`（输出 `Running job [FORCED]`）强制调度 WorkManager 周期任务，在 [logcat-release.txt](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/logcat-release.txt#L15418-L15426) 观测到完整执行链：
```
11-26 15:57:43.342  4259  4259 D WM-SystemJobService: onStartJob for WorkGenerationalId(workSpecId=61ae8766-d7c0-48e4-845a-d4ee1f496cb3, generation=0)
11-26 15:57:43.344  4259  4273 D WM-Processor: o34: processing WorkGenerationalId(workSpecId=61ae8766-d7c0-48e4-845a-d4ee1f496cb3, generation=0)
11-26 15:57:43.345  4259  4259 D WM-WorkerWrapper: Starting work for net.jejer.hipda.service.NotificationPollingWorker
11-26 15:57:43.348  3806  4238 I VerifySmoke: Fixture HTTP GET /forum/pm.php?filter=newpm (session=ProbeTester)
11-26 15:57:43.354  4259  4278 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=61ae8766-d7c0-48e4-845a-d4ee1f496cb3, class=net.jejer.hipda.service.NotificationPollingWorker, tags={ net.jejer.hipda.service.NotificationPollingWorker,hipda_next_notification_polling_tag } ]
11-26 15:57:43.355  4259  4259 D WM-SystemJobService: 61ae8766-d7c0-48e4-845a-d4ee1f496cb3 executed on JobScheduler
```
- `dumpsys notification --noredact` 确认释放包成功发布 `HIPDA_NOTI` 系统通知（在 [13a-process-kill-icicle-restored.png](/home/mir/Documents/hipda-next.wt/_reports/V-M7-4b-artifacts/smoke/13a-process-kill-icicle-restored.png) 状态栏左上方亦可见 `[Hi]` 通知图标）。

---

## 5. 验证分支独立新增产物清单（仅留于 `verify/M7-4b-probes`，未进 `main`）

- CI-only UIAutomator 离线走查模块：`verify-smoke/build.gradle.kts`、`verify-smoke/src/main/AndroidManifest.xml`、`verify-smoke/src/main/java/net/jejer/hipda/verifysmoke/OfflineForumFixtureServer.kt`、`verify-smoke/src/main/java/net/jejer/hipda/verifysmoke/FullScreenReleaseSmokeTest.kt`
- R8 收紧独立探针单测：`hipda/src/test/java/net/jejer/hipda/verify/M7_4b_R8TightenProbeTest.kt`
- 验证专用工作流集成改动：`settings.gradle.kts`、`.github/workflows/android.yml`
