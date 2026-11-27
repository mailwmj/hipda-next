# 代码审查报告：R-M7-4a（Baseline Profile 基建与生成、`isShrinkResources = true` 与动态资源 Keep 规则）

- **审查结论**：`APPROVE`
- **审查对象**：`origin/card/M7-4a-baseline-profile` @ `85e3de011e24c0afe5c31d315bf80b5d73970b86`（基于 `origin/main` @ `846ff3a8`；按 `M7-4.md` 末负责人裁定第 3 条拆分，R8 规则收紧归属后续 `M7-4b`，跳过 `R-M7-4.md` 中 R8 相关项）
- **变更规模**：`15 files changed, 64,219 insertions(+), 3 deletions(-)`（其中 CI 生成并入库的 `baseline-prof.txt` 33,458 行、`startup-prof.txt` 29,591 行；手写生产/测试/构建与 CI 配置共 1,170 行新增、3 行删除）
- **CI 核验**：
  1. **Profile 生成运行**：[Android CI #45819119325](https://github.com/mailwmj/hipda-next/actions/runs/45819119325) — `headSha == 46380ac7ba8a3efafde756b835ba3858759173a7`，`workflow_dispatch`（`generate_baseline_profile=true`），`Build, Lint & Unit Tests` 与 `Generate Baseline Profile (API 36)` 均 `completed / success`；产出原样提交于 `0b51b2d` 且在 `85e3de0` 零改动。
  2. **最终验证与模拟器冒烟运行**：[Android CI #45824561274](https://github.com/mailwmj/hipda-next/actions/runs/45824561274) — `headSha == 85e3de011e24c0afe5c31d315bf80b5d73970b86`，`workflow_dispatch`（`emulator=true`），`Build, Lint & Unit Tests`（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`:hipda:testDebugUnitTest` 1485/1485 + `:core-parser:test` 100/100 + Roborazzi 0 漂移、`Verify APK outputs`）与 `Emulator Smoke Test (API 36)`（debug/release 双通过）均 `completed / success`，`Generate Baseline Profile (API 36)` 正确 `skipped`。
- **只读纪律核验**：`/home/mir/Documents/hipda-next` 主工作区零签出、零修改、零编译。

---

## 1. 阻断项（Blocking Issues）

**无（0 项）**。

---

## 2. 重点审查逐项核验

### 重点 (1)：Profile 生成 Job 仅按需手动触发（零拖慢常规 CI）+ 生成脚本零访问真实站点

1. **按需手动触发与构建隔离**：
   - `.github/workflows/android.yml:22-26` 新增 `workflow_dispatch.inputs.generate_baseline_profile`（`type: boolean`, `default: false`）。
   - `.github/workflows/android.yml:379-382` 中 `generate-baseline-profile` job 条件严格限定为：
     `if: ${{ github.event_name == 'workflow_dispatch' && (inputs.generate_baseline_profile == true || inputs.generate_baseline_profile == 'true') }}`。
     常规 `push`、`pull_request` 以及仅传 `emulator=true` 的 `workflow_dispatch`（已在 run `45824561274` 实证 `conclusion: skipped`）均完全跳过该模拟器任务。
   - `hipda/build.gradle.kts:76-79` 显式配置 `baselineProfile { automaticGenerationDuringBuild = false; mergeIntoMain = true }`，确保常规 `assembleRelease` 仅编译已入库的 `hipda/src/main/generated/baselineProfiles/*.txt`，绝不触发 `:baselineprofile` 连接设备测试；`Verify APK outputs` 新增的 Python 产物校验耗时仅约 `1.2s`（`13:13:00.906` → `13:13:02.121`），不影响常规 push CI 时长。

2. **生成脚本 100% 离线回环、零访问真实站点（`4d4y.com` / `hi-pda.com`）**：
   - **回环假数据服务端**：`baselineprofile/src/main/java/net/jejer/hipda/baselineprofile/OfflineForumFixtureServer.kt:27,32-33` 使用 `ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))` 仅绑定本机回环地址，针对 `forumdisplay.php` 返回含 `#umenu` 的 25 条合成 GBK 主题列表 HTML（`L74-78,157-200`），针对 `viewthread.php` 返回 18 层含引用块与 `default`/`coolmonkey`/`grapeman` 三组表情标签的合成 GBK 详情 HTML（`L79-83,202-268`），针对头像/图片路径返回 67 字节 `1×1` PNG（`L84-86,138-148`）。
   - **请求时序无竞态泄漏**：`hipda/src/nonMinifiedRelease/java/net/jejer/hipda/baselineprofile/BaselineProfileHostActivity.kt:66-74,100` 以正则 `^http://127\.0\.0\.1:\d{1,5}$` 白名单校验 `EXTRA_FORUM_SERVER`，并在 `setContent { ... }` 实例化 `NextAppHostViewModel` / `ForumViewModel` **之前**通过 `runBlocking { settingsRepository.setForumServer(loopbackServer) }` 同步落盘。核对 `DataStoreSettingsRepository.sanitizeForumServer`（`DataStoreSettingsRepository.kt:234-235`）与 `ForumHttpClient.executeSingleAttempt`（`ForumHttpClient.kt:129-130`）及 `HiApplication.onCreate`（`HiApplication.java:45-87`），确认应用冷启动至测试结束全程无任何协程在 `setForumServer` 生效前访问默认 `https://www.4d4y.com`。
   - **变体隔离**：`BaselineProfileHostActivity` 与其清单仅位于 `hipda/src/nonMinifiedRelease/`，通过 `hipda/build.gradle.kts:97-101` `withBuildType("nonMinifiedRelease")` 挂载清单；CI `aapt2 dump xmltree`（`android.yml:120-123`）与 `ReleaseResourceAndProfileGuardTest`（`L135-166`）双重验证未泄漏至 `release` / `debug` APK，且 `:baselineprofile` 源码去注释后零出现 `4d4y.com` / `hi-pda.com`。
   - **核心路径覆盖**：`BaselineProfileGenerator.kt:51-127` 覆盖（1）冷启动 `generateStartupProfile`、（2）版块列表加载与上下滑动、（3）避开上下悬浮玻璃栏的安全视口点击进帖、（4）帖子详情楼层（引用 + 行内表情）渲染与上下滑动。

---

### 重点 (2)：`baseline-prof.txt` 不含测试/调试类，`profileinstaller` 正确接入

1. **`baseline-prof.txt` 与 `startup-prof.txt` 纯净度扫描**：
   - `BaselineProfileGenerator.kt:60,87,135-136` 通过 `filterPredicate = ::excludeHarnessRule` 在采集端剔除 `Lnet/jejer/hipda/baselineprofile/`。
   - 脚本全量扫描已提交的 `baseline-prof.txt`（33,458 条非空规则）与 `startup-prof.txt`（29,591 条非空规则）：
     - `Lnet/jejer/hipda/baselineprofile/`：**0 条**；
     - `Landroidx/test/`、`Landroidx/benchmark/`、`Lorg/junit/`、`Ljunit/`、`Lorg/robolectric/`、`Lroborazzi/`、`Lleakcanary/`、`Lokhttp3/mockwebserver/`、`Mock*`：**全部 0 条**；
     - 名称中带 `Debug`/`Test`/`Fake` 子串的少量条目全部来自 Jetpack Compose 正式生产运行时内部接口（如 `androidx.compose.runtime.composer.DebugStringFormattable`、`androidx.compose.ui.platform.ViewRootForTest`、`SemanticsNode.emitFakeNodes`），无任何调试构建变体或测试依赖类混入。
   - 热点类分布核验：`baseline-prof.txt` 含 `feature/threaddetail`（680）、`ui/designsystem`（573，含 `HiGlassSurface`）、`ui/shell`（434，含 `HiAdaptiveShell`）、`feature/forum`（422，含 `ForumPane`）、`feature/app`（378，含 `NextAppRoot`）、`data/network`（250）、`parser/threaddetail`（87）、`parser/threadlist`（30）、`EmojiMap` 及 `com/kyant/backdrop`（181），与四条核心路径完全吻合。
2. **`profileinstaller` 接入与打包实证**：
   - `gradle/libs.versions.toml:58,175` 引入 `androidx.profileinstaller:profileinstaller:1.4.1`，`hipda/build.gradle.kts:150-151` 声明 `implementation(libs.androidx.profileinstaller)` 与 `baselineProfile(project(":baselineprofile"))`。
   - `hipda/src/main/AndroidManifest.xml` 保留 `androidx.startup.InitializationProvider`（仅移除 `WorkManagerInitializer`），`ProfileInstallerInitializer` 自动合并生效。
   - CI run `45824561274` 实证 `hipda-ng-release.apk` 内含非空二进制编译产物 `assets/dexopt/baseline.prof`（`19,013 bytes`）与 `assets/dexopt/baseline.profm`（`1,215 bytes`）。

---

### 重点 (3)：`keep.xml` 最小定向保留 + 101 个表情 PNG 守卫测试真实检查 Release 产物

1. **`hipda/src/main/res/raw/keep.xml` 最小化核查**：
   - 规则全文（`keep.xml:12-13`）：
     `tools:keep="@drawable/default_*,@drawable/coolmonkey_*,@drawable/grapeman_*,@drawable/win,@drawable/ic_stat_hi,@drawable/ic_shortcut_*"`
   - 逐项核对全仓资源与源码：
     - `@drawable/default_*`（21）+ `@drawable/coolmonkey_*`（32）+ `@drawable/grapeman_*`（48）= **精确匹配 `:emoji` 全部 101 张论坛表情 PNG**（`hipda/src/main/res` 下无任何同名 `default_*`/`coolmonkey_*`/`grapeman_*` 资源被误保）；
     - `@drawable/win`：`InlineEmojiContent.kt:45` 按 `SmallImage` 字符串键 `"win"` 映射的行内角标；
     - `@drawable/ic_stat_hi`：系统通知栏小图标；
     - `@drawable/ic_shortcut_*`：`res/xml/shortcuts.xml` 引用的桌面快捷方式图标（在 `M7-2` 删除遗留 `ic_shortcut_add.png` 后精确对应 4 枚有效图标）；
     - 提交 `85e3de0` 已移除旧库 Android-Iconics 的 `@string/define_font_*` 规则，且全仓 `Resources.getIdentifier` 扫描（`ReleaseResourceAndProfileGuardTest:96-132`）确认应用包内 **0 处**动态 `getIdentifier` 调用（仅旧 `:emoji` `EmojiPopup.java` 1 处读 `"android"` 系统包且将在 `M7-2` 删除）。无任何过宽通配保留规则。

2. **101 个表情 PNG 守卫测试对 Release 产物的真实性核查**：
   - **单测层（`ReleaseResourceAndProfileGuardTest.kt:209-260`）**：
     - GitHub Actions 构建顺序为 Step 6 先跑 `assembleDebug assembleRelease lintDebug`（`android.yml:60-61`），Step 8 再跑 `testDebugUnitTest`（`android.yml:80-82`），且环境变量 `CI=true` 恒成立。
     - `L215-218` 在 `isCi == true` 时**硬断言** `hipda/build/outputs/apk/release/hipda-ng-release.apk` 与 R8 资源缩减报告 `hipda/build/outputs/mapping/release/resources.txt` 必须同时存在（杜绝 CI 下因文件不存在提前 `return` 变空心测试的可能）。
     - `L222-227` 直接打开 `ZipFile(releaseApk)` 校验 `assets/dexopt/baseline.prof` 与 `baseline.profm` 存在且 `size > 0`。
     - `L230-259` 解析真实 `release/resources.txt`，断言全部 101 个 `drawable:<emoji>` + `win` + `ic_stat_hi` + 4 个 `ic_shortcut_*` 均处于 `" reachable from "` 状态且不在 `" is not reachable."` 集合中，并附带负向自测（`L254-258`）。
   - **CI 二进制产物层（`android.yml:124-159` `Verify APK outputs`）**：
     - 由于 AGP 默认开启资源路径短名优化（APK 内文件名为 `res/xx.png`），CI 脚本直接调用 `aapt2 dump resources hipda-ng-release.apk` 反查全部 101 个 `drawable/<emoji_name>` 在 APK zip 内的真实文件路径，并逐一校验 `max(zf.getinfo(p).file_size) > 100`（既防条目丢失，也防 AGP 资源缩减器将其替换为 67 字节 `1×1` 透明占位 PNG）。CI run `45824561274` 实测输出：`OK all 101 emoji drawables present and non-stubbed in release APK`。

---

### 重点 (4)：红线边界核验（`targetSdk` / `proguard-rules` / 包名 / 签名 / 零私自发版 / 零快照漂移）

- **`targetSdk` 未改动**：`gradle/libs.versions.toml:7` 仍为 `targetSdk = "30"`；CI `aapt2 dump badging` 实测 debug/release 均为 `targetSdkVersion:'30'`（`:baselineprofile` 亦动态读取 `libs.versions.targetSdk`）。
- **`proguard-rules.txt` 未改动**：`git diff origin/main...85e3de0 -- hipda/proguard-rules.txt` 输出为空（0 行改动，严格留归 `M7-4b`）。
- **包名与主清单未改动**：`hipda/build.gradle.kts:20,28` 保持 `namespace = "net.jejer.hipda"`、`applicationId = "net.jejer.hipda.next"`；`hipda/src/main/AndroidManifest.xml` 0 行改动。
- **签名与发布红线**：`build.gradle.kts` / `hipda/build.gradle.kts` 未新增任何 `signingConfigs` 或密钥文件；`gh release list` 为空（`no releases found`），未打发布 tag、未构建或分发正式签名 release 包（`emulator-smoke` 沿用既有 CI 临时单日有效 debug keystore 签名冒烟）。
- **既有测试与快照零漂移**：`hipda/src/test/snapshots/` 0 改动；`MotionTokenScanTest`（7/7）、`CopyTerminologyScanTest`（3/3）、`EmojiMapTest`（5/5）全绿。

---

### 重点 (5)：Gradle 改动最小化核查 + 与 `M7-2`、`M7-3` 的 `merge-tree` 冲突分析与解决建议

#### 5.1 构建脚本最小改动核查
- `settings.gradle.kts`（`+1/-1`）：仅在 L19 将 `include(":hipda", ":emoji", ":core-parser")` 扩展追加 `:baselineprofile`。
- `build.gradle.kts`（`+2/-0`）：仅声明 `android.test` 与 `androidx.baselineprofile` `apply false`。
- `gradle/libs.versions.toml`（`+12/-1`）：仅新增 `androidxTestRunner`、`uiautomator`、`benchmarkMacro`、`baselineprofile`、`profileinstaller` 5 个版本号、4 个 library 坐标与 2 个 plugin 坐标，零改动任何既有依赖版本。
- `hipda/build.gradle.kts`（`+14/-1`）：仅应用 `androidx.baselineprofile` 插件、开启 `isShrinkResources = true`、配置 `baselineProfile` 块（4 行）、挂载 `nonMinifiedRelease` 静态清单（5 行）及添加 `profileinstaller` / `baselineProfile(project(":baselineprofile"))` 两行依赖，未触碰 `tasks.withType<Test>().configureEach` 或既有依赖块。

#### 5.2 `git merge-tree` 冲突矩阵与解决建议

| 合并对象 | `git merge-tree` 结果 | 文本冲突文件与根因剖析 | 语义兼容性与具体解决建议 |
|---|---|---|---|
| **`origin/main`** (`846ff3a`) | **0 冲突**（已是祖先，直接 FF） | 无 | `85e3de0` 已合入 `origin/main`（含 `CI-test-heap`、`E6`、`E6b`、`M7-1`），可直接合入。 |
| **`origin/card/M7-2-remove-legacy`** (`1c2410b`) | **0 文本冲突**（Clean tree `bd3e717dd941848343aa163e3b221dc488a8d453`） | `settings.gradle.kts`（L14 删 `jitpack.io` vs L19 加 `:baselineprofile`）、`gradle/libs.versions.toml`（删旧库段 vs 新增测试/Profile 坐标）、`hipda/build.gradle.kts`（L218-249 删旧依赖 vs 上半部加 Profile/Shrink）**全部自动干净合并**。 | **0 语义冲突**！此前 `R-M7-2` 指出的 `@string/define_font_*` 断言冲突已由本卡 `85e3de0` 提前清除；`M7-2` 删除废弃 `ic_shortcut_add.png` 后保留的 4 枚快捷方式图标与 `ReleaseResourceAndProfileGuardTest` 100% 吻合；`baseline-prof.txt` 中 48 条旧 `HiSettingsHelper` 规则在旧类删除后由 profgen/R8 自动忽略不报错（留 `M7-4b` 重录清理即可）。**建议**：`M7-4a` 与 `M7-2` 可按任意先后顺序直接 `git merge`，无需手工改代码。 |
| **`origin/card/M7-3-target-sdk-36`** (`613c49e`) | **`M7-4a` 自身改动与 `M7-3` 0 冲突**（裸跑 `merge-tree 85e3de0 613c49e` 出现的 3 处冲突全系 `M7-3` 尚未合入 `origin/main` 所致） | 对照 `git merge-tree origin/main 613c49e` 证实：裸跑报出的 3 个冲突文件（`hipda/build.gradle.kts`、`MultiPaneSnackbarHost.kt`、`NextAppRoot.kt`）100% 是 `M7-3`（基于 `c001dee`）与 `origin/main`（`CI-test-heap` `ed361c2` + `E6`/`E6b`）之间的既有冲突。<br>核对 `M7-4a` 自身 diff 与 `M7-3` 自身 diff：<br>- `gradle/libs.versions.toml`：`M7-3` 改 L7 `targetSdk = "36"`，`M7-4a` 改 L46+（自动合并）；<br>- `hipda/build.gradle.kts`：`M7-3` 仅在 `tasks.withType<Test>().configureEach` 首行加 JDK 21 `javaLauncher`，`M7-4a` 完全未改该块（自动合并）；<br>- `.github/workflows/android.yml`：`M7-3` 改 L41-47 `build` job 安装 JDK `17` & `21`，`M7-4a` 改 L22、L119、L379（自动合并）。 | 1. **`hipda/build.gradle.kts`（`CI-test-heap` vs `M7-3` 邻行冲突解法）**：在 `tasks.withType<Test>().configureEach {` 顶部**同时保留**双方配置：<br>`javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })`<br>`maxHeapSize = "1536m"`<br>`forkEvery = 400L`<br>2. **`targetSdk = 36` 语义兼容**：`ReleaseResourceAndProfileGuardTest:40-43` 断言的是 `libs.versions.targetSdk.get().toInt()` 引用形式而非硬编码 `30`，`:baselineprofile/build.gradle.kts:21` 亦同源读取，升至 `36` 后零测试冲突。<br>3. **CI Toolchain 联动微调建议**：见下方非阻断建议 `S1`。 |

---

## 3. 非阻断建议（Non-blocking Suggestions）

- **S1（与 `M7-3` 合入时的 CI `generate-baseline-profile` JDK 21 Toolchain 防御同步）**：`M7-3` 在 `hipda/build.gradle.kts` 为 `Test` 任务声明了 JDK 21 `javaLauncher`，并在 `android.yml` 的 `build` job 配置了 `java-version: [17, 21]`。虽然 `tasks.withType<Test>().configureEach` 是惰性的（`:hipda:generateBaselineProfile` 仅执行仪器测试、默认不实例化单测任务），但建议在 `M7-3` 合入后或 `M7-4b` 中顺手将 `android.yml:390-395`（`generate-baseline-profile` job 的 `actions/setup-java`）也同步为同时安装 `17` 与 `21`，防止未来 Gradle 任务图变更实例化 `Test` 任务时缺少 JDK 21 toolchain。
- **S2（本地执行 `assembleRelease` 时的 `resources.txt` 断言收紧，可选）**：`ReleaseResourceAndProfileGuardTest.kt:215-218` 在 `CI=true` 时已严格要求 `releaseApk` 与 `resourcesReport` 同时存在；`L230` 的 `if (resourcesReport.isFile)` 可在后续 `M7-4b` 中顺手改为 `assertTrue(resourcesReport.isFile)`（因 `L219` 已确认 `releaseApk.isFile` 且 `isShrinkResources = true` 恒成立），使本地手工跑过 `assembleRelease` 后的单测同样强制校验 `resources.txt`。
- **S3（`M7-4b` 刷新 Profile 提醒）**：按 `M7-4a.md` §6 规划，待 `M7-2`（删旧代码/旧依赖）与 `M7-3`（`targetSdk = 36`）均合入且 `M7-4b` 收紧 `proguard-rules.txt` 后，建议手动触发一次 `generate_baseline_profile=true` 刷新 `baseline-prof.txt`/`startup-prof.txt`，顺带自然清除当前因 `HiApplication` 基类尚存而录进的 48 条旧 `HiSettingsHelper` 规则。
