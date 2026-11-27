# 任务卡 M7-4a 独立验证报告（Baseline Profile 与 `isShrinkResources = true`）

- **结论**：`PASS`
- **被测对象**：`origin/card/M7-4a-baseline-profile` @ `85e3de011e24c0afe5c31d315bf80b5d73970b86`（拆分自 `M7-4` 负责人裁定第 3 条；`R8` 规则收紧已延后至 `M7-4b`，本卡按要求跳过）
- **被测实现 CI**：[Run #45824561274](https://github.com/mailwmj/hipda-next/actions/runs/45824561274)（`success`，`Build, Lint & Unit Tests` + `Emulator Smoke Test (API 36)` 均通过，`Generate Baseline Profile (API 36)` 因非 `workflow_dispatch` 跳过）
- **合并 `origin/main` 验证**：已于验证工作树 `/home/mir/Documents/hipda-next.wt/V-M7-4a`（分支 `verify/M7-4a-probes`，当前 `HEAD` `d181df0f91a91739bd8f14dc792010b36c454ec7`）无冲突合入最新 `origin/main` @ `3fa3ea1853e2c5605007d6117ea278175ec25a49`（含 `M7-2` 旧代码/旧资源移除 `4c7f6a0` 与 `E7` 表情面板 `83e0948`）
- **独立探针 CI**：[Run #45840636512](https://github.com/mailwmj/hipda-next/actions/runs/45840636512)（`workflow_dispatch emulator=true`，`success`；`Build, Lint & Unit Tests` 15m43s、`Emulator Smoke Test (API 36)` 4m15s）
- **主工作区洁净度 & 快照漂移**：`/home/mir/Documents/hipda-next` `git status --short` 为空；`git diff --name-status origin/main...HEAD -- hipda/src/test/snapshots/` 为空（0 处快照漂移）；CI 全量单测 `1605/1605 passed`（`[:hipda:testDebugUnitTest]` `1505/1505`）

---

## 1. 探针总览

| 探针 | 状态 | 核心结论 |
|---|---|---|
| (a) CI Release APK 解包与资源收缩/Profile 核验 | **PASS** | `baseline.prof` / `baseline.profm` 均非空且魔数有效；新代码（含 `E7`）及 Manifest/XML 0 处引用被 shrink 资源、0 处不安全按名反射查找；101 枚表情、`ic_stat_hi`、`win`、4 个快捷方式图标、`ic_launcher` 及 `assets/` 文档全部存在且未降级为 1x1 stub（`splash`/`splash_screen` 已随 `M7-2` 删除 `MainFrameActivity` 合理移除）。 |
| (b) `workflow_dispatch emulator=true` Release 离线冒烟 | **PASS** | 离线飞行模式 + `127.0.0.1:33593` 回环夹具（49 次请求，logcat 0 次命中 `4d4y.com`/`hi-pda.com`）驱动全量新栈界面逐屏走查，0 崩溃（0 `FATAL EXCEPTION` / 0 `ANR`）、16 张截图逐张核验无缺图；`E7` 表情面板三组标签与插入/回删实机验证通过。 |
| (c) 冷启动有/无 Baseline Profile 对比（仅报告数据） | **PASS** | API 36 实机模拟器 `am start -W` + 首屏列表就绪（`FirstContent`）：`speed-profile` 较 `verify` 的 `TotalTime` 均值降低 `-5.5%`（`552ms` vs `584ms`），`FirstContent` 均值降低 `-10.7%`（`2727ms` vs `3054ms`，中位数 `2714ms` vs `3014ms`，`-10.0%`）；较 `--reset` `FirstContent` 均值亦低 `-2.4%`（`2727ms` vs `2795ms`）。 |
| 跳过项（作用域外） | **SKIPPED** | `R8` `proguard-rules.txt` 规则收紧与去混淆堆栈核验不在 `M7-4a` 范围（延后至 `M7-4b`）。 |

---

## 2. 探针 (a)：Release APK 解包、Baseline Profile 与资源收缩核验

### 2.1 产物体积与资源表对比（含合入 `M7-2` + `E7` 前后）

直接下载并解包比对以下 4 份 CI `release-apk` 产物（使用 `aapt2 dump resources` 与 Python `zipfile` 统计）：

| 构建对象 | CI Run | Release APK | `resources.arsc` | `aapt2` 资源键数 | `res/` 文件数（`<=100B`） | `baseline.prof` / `profm` | `splash` 状态 |
|---|---|---|---|---|---|---|---|
| `origin/main` @ `846ff3a`（无 shrink） | [45823655260](https://github.com/mailwmj/hipda-next/actions/runs/45823655260) | `9,996,241 B` | `2,325,884 B` | `8,760` | `1,392`（2） | `10,286 B` / `1,685 B` | 存在（旧 `MainFrameActivity`） |
| `M7-4a` @ `85e3de0` | [45824561274](https://github.com/mailwmj/hipda-next/actions/runs/45824561274) | `8,684,814 B`（`-13.1%`） | `1,145,220 B`（`-50.8%`） | `3,654`（`-58.3%`） | `719`（1） | `19,013 B` / `1,215 B` | 存在（`True`） |
| `origin/main` @ `83e0948`（含 `M7-2`+`E7`，无 shrink） | [45831673808](https://github.com/mailwmj/hipda-next/actions/runs/45831673808) | `6,299,655 B` | `905,488 B` | `1,114` | `240`（1） | `8,288 B` / `1,502 B` | 已随 `M7-2` 删除（`False`） |
| **`M7-4a` 合入 `origin/main@3fa3ea1`** | **[45840636512](https://github.com/mailwmj/hipda-next/actions/runs/45840636512)** | **`6,086,933 B`**（较主干 `-3.4%`） | **`509,116 B`**（`-43.8%`） | **`845`**（`-24.1%`） | **`146`**（**`0`**） | **`16,941 B`** / **`1,038 B`** | 已随 `M7-2` 删除（`False`） |

- **说明**：合入 `origin/main` 后，即使 `M7-2` 已清零旧界面/旧依赖，开启 `isShrinkResources = true` 仍进一步移除 `269` 个不可达资源键（本地 [`resources.txt`](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/build/outputs/mapping/release/resources.txt) 显示 `reachable=808`、`unreachable=302`、`total=1,088`）、将 `resources.arsc` 压减 `-396,372 B`（`-43.8%`）、`res/` 文件条目由 `240` 降至 `146`，且无任何 `<=100 B` 占位 stub 资源；因新增 `androidx.profileinstaller` 等库，`classes*.dex` 总未压缩体积由 `11,388,300 B` 增至 `12,062,184 B`，整包仍净减 `212,722 B`。

### 2.2 Baseline Profile 完整性与隔离性

- **二进制 Profile**：CI `45840636512` release APK 内 [`assets/dexopt/baseline.prof`](file:///home/mir/.gemini/antigravity-cli/brain/6c06a39f-7fcf-4f02-8588-6adfb9805e00/scratch/ci-45840636512/release-apk/hipda-ng-release.apk) 为 `16,941 B`（魔数 `b'pro\x00010\x00'`），`baseline.profm` 为 `1,038 B`（魔数 `b'prm\x00002\x00'`）；目标包 `85e3de0`（CI `45824561274`）内为 `19,013 B` / `1,215 B`。
- **提交文本 Profile**：[`baseline-prof.txt`](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/main/generated/baselineProfiles/baseline-prof.txt)（`33,457` 行）与 [`startup-prof.txt`](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/main/generated/baselineProfiles/startup-prof.txt)（`29,590` 行）含冷启动（`HiNextApplication`、`NextAppRootKt`、`HiAdaptiveShellKt`）、帖子列表与详情（`ForumPaneKt`、`ThreadDetailPaneKt`、`ThreadListParser`、`ThreadDetailParser`）、内联表情（`EmojiMap`、`InlineEmojiContentKt`）及玻璃材质热路径（`com.kyant.backdrop`），且 `Lnet/jejer/hipda/baselineprofile/` 出现次数均为 `0`。
- **Manifest 隔离与包属性**：`aapt2 dump xmltree ... AndroidManifest.xml` 确认 `BaselineProfileHostActivity` 未泄漏进 release Manifest；`aapt2 dump badging` 确认 `package: name='net.jejer.hipda.next'`、`versionCode='5005'`、`versionName='5.0.05'`、`targetSdkVersion:'30'`、`launchable-activity: name='net.jejer.hipda.feature.app.MainActivity'` 未发生漂移。

### 2.3 新代码资源引用、动态查找与关键资源留存

由独立单元测试 [M7ResourceShrinkAndProfileProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/test/java/net/jejer/hipda/packaging/M7ResourceShrinkAndProfileProbeTest.kt)（本地 `3/3` 通过，CI `45840636512` 通过）及实机探针 [ReleaseSmokeAndStartupProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/ReleaseSmokeAndStartupProbeTest.kt) 联合核验：

1. **静态 + Shrink Report 交叉扫描**：扫描全部新栈源码（`hipda/src/main/java/net/jejer/hipda/{feature,data,di,ui/shell,ui/designsystem,ui/challenge}`、含 `E7` 的 `feature/compose/emoji/` 与 `ComposeEmojiModule.kt`、`HiNextApplication.kt`、`service/Notification*.kt`、`emoji/EmojiMap.kt`）以及 `AndroidManifest.xml` / `shortcuts.xml` / `backup_config.xml` / `data_extraction_rules.xml` / `provider_paths.xml` / `ic_launcher.xml` 中全部 `R.*` 与 `@type/name` 引用，对照 `resources.txt` 报告 **0 处** 引用被标为不可达或缺失（`brokenRefs = []`）。
2. **按名动态查找扫描**：全扫描上述新代码及 `emoji/` 源码中的 `Resources.getIdentifier(...)`（非 `"android"` 包）与 `R$*` 反射，命中数 = **0**；已有守卫 [ReleaseResourceAndProfileGuardTest.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/test/java/net/jejer/hipda/packaging/ReleaseResourceAndProfileGuardTest.kt) `6/6`（含负向自检）全部通过。
3. **关键资源实机解码验证（`45840636512` 实机 `BitmapFactory`）**：
   - **全部表情**：`101/101` 枚 PNG（`21 default_*`、`32 coolmonkey_*`、`48 grapeman_*`，覆盖 `EmojiMap` 61 枚表情深浅色双套）全部解码且 `width > 1 && height > 1`；`E7` 文案资源 `string:emoji_panel_title` / `emoji_group_*` / `emoji_item_cd` / `emoji_action_backspace` 全部可达。
   - **通知角标与小图徽章**：`drawable/ic_stat_hi`（实机 `63x63`）、`drawable/win`（实机 `42x42`）保持在 [`keep.xml`](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/main/res/raw/keep.xml) 中并完整解码。
   - **快捷方式图标**：4 枚图标 `ic_shortcut_wb_sunny` / `ic_shortcut_search` / `ic_shortcut_mail` / `ic_shortcut_favorite`（实机均 `126x126`）全部完整保留。
   - **启动图与桌面图标说明**：`85e3de0`（`45824561274`）合入 `M7-2` 前尚保留 `drawable/splash` 与 `drawable/splash_screen`；合入最新 `origin/main`（`3fa3ea1`）后，`M7-2`（`1c2410b`）已刻意删除旧 `MainFrameActivity`、`drawable-xxxhdpi/splash.png` 与 `drawable/splash_screen.xml`，现由 `MainActivity` 使用 `@android:style/Theme.Material.Light.NoActionBar` + `mipmap/ic_launcher` 作为系统启动图标。实机已验证 `mipmap/ic_launcher` 与 `mipmap/ic_launcher_foreground` 均完整可加载（`AndroidManifest.xml` 未声明 `android:roundIcon`，故未引用的 `mipmap/ic_launcher_round` 被 `isShrinkResources` 正常移除）。
   - **About 静态 `assets/` 文档**：`assets/release-notes.txt`（383 chars）、`assets/donors.txt`（1979 chars）、`assets/license.txt`（1041 chars）在 release APK 中非空可读。

---

## 3. 探针 (b)：CI `workflow_dispatch emulator=true` Release 离线冒烟

在 CI [Run #45840636512](https://github.com/mailwmj/hipda-next/actions/runs/45840636512) 的 `Emulator Smoke Test (API 36)` 任务中，对 CI 调试签名 `release` APK（`net.jejer.hipda.next`）全程开启飞行模式（`cmd connectivity airplane-mode enable`、`svc wifi disable`、`svc data disable`）并以本地回环服务 [ProbeOfflineForumServer.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/ProbeOfflineForumServer.kt)（`http://127.0.0.1:33593`）替代真实论坛执行端到端遍历：

- **网络与异常审计**：[`startup-benchmark.txt`](file:///home/mir/.gemini/antigravity-cli/brain/6c06a39f-7fcf-4f02-8588-6adfb9805e00/scratch/ci-45840636512/smoke/startup-benchmark.txt) 共记录 49 次回环夹具请求；完整 29,153 行 [`logcat-release.txt`](file:///home/mir/.gemini/antigravity-cli/brain/6c06a39f-7fcf-4f02-8588-6adfb9805e00/scratch/ci-45840636512/smoke/logcat-release.txt) 中 **`4d4y.com` / `hi-pda.com` 命中数 = 0**，**`FATAL EXCEPTION` / `ANR in` / 异常进程死亡 = 0**。
- **逐屏走查结果（已逐张人工目视核对 16 张产物截图）**：
  1. **冷启动 & 配置离线服务**：`01-cold-start.png`、`02-settings-network.png`、`07-mine-logged-in.png`（登录 `ProbeTester` 后退出登录框并正确展示账号卡）。
  2. **设置/关于页与 `assets/` 文档**：`03-settings-about.png` 及 3 份原生文档弹窗 `04-about-release-notes.png`、`05-about-donors.png`、`06-about-license.png` 全文可读、0 处 `加载失败`。
  3. **论坛列表 & 滚动**：`08-forum-list.png` 正常展示 25 条离线主题、头像与「新」标记，上下滚动正常。
  4. **帖子详情 & 内联表情/图片**：`09-thread-detail.png` 清晰渲染 `default/smile`、`coolmonkey/01`、`grapeman/01`、`win` 徽章、`240x160` 帖子附图及后续楼层表情，上下滚动流畅。
  5. **看图页**：`10-image-viewer.png` 全屏查看大图、`1 / 1` 计数与「查看原图/保存图片/分享」控件正常弹出与关闭。
  6. **快速回复、E7 表情面板与全屏编辑器**：`11-quick-reply-sheet.png` 正常滑出且可见「表情」按钮；`12-emoji-panel-default.png`、`13-emoji-panel-monkey.png`、`14-emoji-panel-dumb.png` 三套表情网格图标全部清晰渲染无缺图，并完成 `:)` 插入与 `删除` 键整码回删验证；`15-full-editor.png` 与 `16-full-editor-emoji.png` 验证全屏编辑器控件与表情面板切换无异常。

---

## 4. 探针 (c)：冷启动有/无 Baseline Profile 对比（仅报告数据）

在同一次 CI 实机任务（[startup-benchmark.txt](file:///home/mir/.gemini/antigravity-cli/brain/6c06a39f-7fcf-4f02-8588-6adfb9805e00/scratch/ci-45840636512/smoke/startup-benchmark.txt)）中，先验证 `ProfileInstaller` 安装完成（logcat：`RESULT_INSTALL_SUCCESS`，broadcast `result=1`），随后按 `verify` → `speed-profile` → `--reset` 三组条件对 `net.jejer.hipda.next/net.jejer.hipda.feature.app.MainActivity` 执行 `am force-stop` + `am start -W` 并等待 UIAutomator 检测到首个主题渲染（`FirstContent`）：

| 编译模式（`dexopt` 状态） | 轮次 | `TotalTime` (`am start -W`) | `FirstContent`（冷启动至首屏主题就绪） |
|---|---:|---|---|
| `cmd package compile -m verify -f`（`status=verify reason=cmdline`） | 5 | 均值 **`584 ms`** / 中位 `540 ms`（`508–701 ms`） | 均值 **`3,054 ms`** / 中位 `3,014 ms`（`2,821–3,339 ms`） |
| **`cmd package compile -m speed-profile -f`**（`status=speed-profile reason=cmdline`） | 5 | 均值 **`552 ms`** / 中位 `550 ms`（`421–632 ms`） | 均值 **`2,727 ms`** / 中位 `2,714 ms`（`2,615–2,818 ms`） |
| `cmd package compile --reset`（`status=verify reason=install`） | 3 | 均值 **`553 ms`** / 中位 `542 ms`（`535–582 ms`） | 均值 **`2,795 ms`** / 中位 `2,828 ms`（`2,702–2,856 ms`） |

- **数据摘要**：开启 Baseline Profile（`speed-profile`）后，`FirstContent` 均值较 `verify` 缩短 `327 ms`（**`-10.7%`**，中位缩短 `300 ms` / `-10.0%`，且 `speed-profile` 最慢一轮 `2,818 ms` 仍快于 `verify` 最快一轮 `2,821 ms`）；`TotalTime` 均值由 `584 ms` 降至 `552 ms`（`-5.5%`）。
- **非阻塞观测备忘**：当前随包提交的 `baseline-prof.txt`（生成于 `85e3de0`、合入 `M7-2` 与 `E7` 之前）由 [BaselineProfileGenerator.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/BaselineProfileGenerator.kt) 经 `BaselineProfileHostActivity` 采集，已覆盖 `NextAppRoot` 与详情/表情热路径；待后续全部 M7 卡片收口后，可用 `M7-4a` 已提供的 `workflow_dispatch -f generate_baseline_profile=true` 刷新一次文本 profile 以顺带覆盖 `E7` `EmojiPickerPanel` 与正式入口 `MainActivity` 路径。

---

## 5. 独立探针变更清单（保留于 `verify/M7-4a-probes`）

- [M7ResourceShrinkAndProfileProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/hipda/src/test/java/net/jejer/hipda/packaging/M7ResourceShrinkAndProfileProbeTest.kt) — Baseline Profile 魔数/热点/隔离验证、新代码（含 `E7`）全量 `R.*`/XML/动态 lookup 扫描、`resources.txt` 交叉断言。
- [ProbeOfflineForumServer.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/ProbeOfflineForumServer.kt) — `127.0.0.1` 离线 GBK HTTP 夹具服务端（`logging.php`、`forumdisplay.php`、`viewthread.php` 含三套内联表情/`win`/附图）。
- [ReleaseSmokeAndStartupProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/ReleaseSmokeAndStartupProbeTest.kt) — 实机解码核验 + 离线全屏冒烟（含 `E7` 三组表情面板与插入/回删）+ `verify` vs `speed-profile` vs `reset` 冷启动采集。
- [.github/workflows/android.yml](file:///home/mir/Documents/hipda-next.wt/V-M7-4a/.github/workflows/android.yml) — 在 `verify/M7-4a-probes` 上触发并采集 `:baselineprofile:assembleNonMinifiedRelease` 与上述实机探针。
