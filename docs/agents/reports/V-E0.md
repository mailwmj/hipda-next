# 独立验证报告 V-E0：端到端真保活与基座探针验证（`114951854e8890f89e0b27fe1e63714544422f5c`）

- **总判定**：**`PASS`**（无阻断项）
- **被测对象**：`origin/card/E0-reading-tracer-bullet` @ `114951854e8890f89e0b27fe1e63714544422f5c`（对比基线 `e71c3fb2440b21662bd481f56250fa92a55a1555`）
- **验证工作区与分支**：`/home/mir/Documents/hipda-next.wt/V-E0`，分支 `verify/E0-probes`，探针提交 `19af83fe2ef95d82cff07491d1fef3930f9f3852`
- **GitHub Actions CI**：[Android CI #45661575118](https://github.com/mailwmj/hipda-next/actions/runs/45661575118)
  - `headBranch`：`verify/E0-probes`
  - `headSha`：`19af83fe2ef95d82cff07491d1fef3930f9f3852`
  - `Build, Lint & Unit Tests`（Job `139372514834`）：**`success`**（耗时 12m42s；`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 一致性校验、`:core-parser:test`、`98/98` + `:hipda:testDebugUnitTest` `794/794` + `:hipda:finalizeTestRoborazziDebug` 截图校验、Debug/Release APK `package='net.jejer.hipda.next'`/`targetSdkVersion='30'` 校验；汇总 **`892 tests, 892 passed, 0 failed, 0 errors, 0 skipped`**，其中 `E0ProbeTest` **`10/10` 全绿**、`CopyTerminologyScanTest` `3/3`、`MotionTokenScanTest` `7/7`、`HiThemeScreenshotTest` `12/12`、`HiShellScreenshotTest` `18/18`）
- **独立性声明**：本次验证全程未先阅读 `_reports/E0.md` 与实现者功能单测（`NextAppTracerBulletTest.kt`、`UiErrorMapperTest.kt`、`HiThemeSettingsAdapterTest.kt`）；仅在编写探针 `d` 前核对了 `CopyTerminologyScanTest` 的扫描器方法签名与规则常量。全部结论均来自基线差异核查、对 `src/main` / `src/debug` 源码的独立核对、独立截屏目视检查与 [E0ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt) 实测输出。

---

## 一、视觉与基线零漂移检查

### 1. 既有快照零漂移校验
- 执行 `git diff --name-status e71c3fb...1149518 -- hipda/src/test/snapshots/` 返回空（**0 张快照新增/改动**，全部 `theme_specimen_*.png`、`shell_*.png`、`glass_specimen_*.png`、`challenge_*.png` 零漂移）。
- 本地串行执行 `:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 与 CI `:hipda:finalizeTestRoborazziDebug` 均全绿通过。

### 2. `PreviewActivity` 双分支与 `411dp` / `749dp` 目视核查
- **`PreviewActivity` 入口分支**（`visual_previewActivity_onOpenShellDemoNullVsNonNull_branchesCorrect` [L248](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L248)）：
  - `onOpenShellDemo = null` 时不渲染「进入新版」与「外壳」按钮，仅保留 `ThemeModeBadge`；
  - `onOpenShellDemo != null` 时在 `if (onOpenShellDemo != null)` 内正常渲染「进入新版」并拉起 `NextAppActivity`，无遮挡或崩塌。
- **`411×891dp` 单栏截屏**（[v_e0_w411_h891_single_pane.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w411_h891_single_pane.png)，`visual_captureSinglePane411dp_forManualInspection` [L278](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L278)）：
  - 真实脱敏样本 `Discovery` 版块列表渲染清晰，顶栏按 `E0` 契约呈纯色 `Surface`（含 `Discovery`、`Buy & Sell`、`Geek Talks` 标签，透明吸顶玻璃顶栏留待 `E1`）；底部 `HiNavigationPill` 液态玻璃清晰呈现对下方帖子文字的实时模糊与边缘折射，无遮挡或异常截断。
- **`749×559dp` 三栏空右栏与详情截屏**（[v_e0_w749_h559_three_pane_empty_right.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w749_h559_three_pane_empty_right.png)、[v_e0_w749_h559_three_pane_detail.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w749_h559_three_pane_detail.png)，`visual_captureThreePane749dp_emptyRightAndSelectedDetail_forManualInspection` [L321](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L321)）：
  - 左 `64dp` 收起图标轨 + 中 `274dp` 帖子列表（标签行自动隐藏）+ `1dp` 分隔线 + 右 `410dp` 详情区布局完整；
  - 未选中帖子时右栏居中显示空占位 `选一篇帖子开始阅读`；点击帖子后右栏渲染真实楼层（`1楼 zhuyi`、正文、底部分页 `上一页 / 第 1 / 10 页 / 下一页`），无崩溃与截断错误。（注：`64dp` 图标轨在 `559dp` 矮窗底部设置齿轮紧邻 `H` 头像固定区与既有 `shell_w749_h559_light.png` 基线一致，非 `E0` 引入）。

---

## 二、四大探针验证结果（[E0ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt)）

| 探针 | 用例名（行号） | 验证核心 | 实测结论 |
|---|---|---|---|
| **a. 真实数据层端到端通路** | `probeA_realRepositories_sampleListToDetail_andErrorsConformToCopyMd` ([L370](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L370)) | 内存 Room + `OkHttpClient` + `SiteAccessGateway` + `ForumHttpClient` + **真实 `DefaultThreadListRepository` / `DefaultThreadDetailRepository`** 接 `MockWebServer` 脱敏样本（`forum_2_p1_lastpost.html` / `thread_p1.html`）；注入 HTTP 500、断网 `IOException`、站点无权、未登录及帖子不存在错误 | **PASS**：列表即刻渲染真实帖子标题，点帖进入详情渲染真实楼层号与页码 `第 1 / 10 页`；5 类错误主视图分别显示 `加载失败`/`网络连接失败`/`当前账号无权查看这个版块`/`需要登录才能查看`/`帖子不存在或已被删除`，折叠详情前 **0 异常类名/HTTP 状态码泄漏** |
| **b. 单栏 ↔ 三栏真保活反伪造** | `probeB_realComposeTree_411_749_411_and_stateRestoration_preservesVmScrollAndZeroRequests` ([L480](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L480))<br>`probeB_realActivityConfigChange_recreatesActivity_preservesThreadVmScrollAndZeroRequests` ([L686](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L686)) | 真实 `NextAppRoot` Compose 树内通过 `DeviceConfigurationOverride.ForcedSize then WindowSize` 完成 `411×891 → 749×559 → 411×891` 切换，并在 `749dp` 期间执行 `StateRestorationTester.emulateSavedInstanceStateRestore()`；另附 `ActivityScenario.recreate()` 真实 Activity 重建对照；切换 `tidB` 与清空右栏验证旧 `ViewModelStore` 清理 | **PASS**：`411 → 749 → StateRestorationTester → 411` 全程及 `recreate()` 后：① `ThreadDetailViewModel` 为同一实例（`assertSame` 与 `System.identityHashCode` 一致，`AutoCloseable` 监听 `cleared == 0`）；② 详情列表（`index`/`offset`）与帖子列表（`index`/`offset`）完全不变，返回列表不回顶；③ `MockWebServer` 列表/详情/总请求增量 **严格为 `0`**；切至 `tidB` 与 `closeThread()` 后旧 `tid` `ViewModelStore` 立即 `clear()`（`cleared == 1`、`hasThreadStore == false`、无串帖） |
| **c. `managesGlassSource` 兄弟拓扑、纯色回退与实时主题切换** | `probeC_managesGlassSource_strictSiblingTopology_andSolidPageFallback` ([L792](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L792))<br>`probeC_liveThemeFontAndPureBlackChange_preservesScrollAndZeroDetailRequests` ([L861](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L861)) | `411×891dp` 下遍历 `ContentContainer` 全部语义与非语义 `LayoutInfo` 节点检查 `LayerBackdrop` 归属及与 `BottomPill` 兄弟关系；切至 `搜索`/`我的` 纯色页校验自动回退与玻璃计数；读帖滚至第 9 楼后动态切 `ThemeMode.DARK + pureBlack=true + fontStep=4` | **PASS**：论坛页全内容子树仅 `ForumPane` `LazyColumn` 挂 1 个 `LayerBackdrop`（`ContentContainer` 与 `ShellDestinationHost` 零残留），与 `BottomPill` 严格兄弟，`assertGlassSurfaceCountAtMost(2)` 与 `HiGlassBlurActive == true` 通过；切至 `搜索`/`我的` 自动回退挂源且全屏玻璃 `<= 2`；实时主题/纯黑/字号切换使背景像素变为纯黑 `#000000`，同一 `ThreadDetailViewModel` 不重建、楼层 `(index=9, offset=40)` 不变且详情请求增量 `== 0` |
| **d. 术语门禁反例自证与 `ImageLoaderModule` 非阻塞** | `probeD_copyTerminologyScanner_catchesSyntheticViolationsAndZeroFalsePositives` ([L949](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L949))<br>`probeD_imageLoaderModule_mainThreadNonBlocking_gifDecoderAndBoundOkHttpClient` ([L987](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L987)) | 向 `CopyTerminologyScanTest.scanXmlContent` 喂入 6 条合成违规与 5 条合法中文；主线程直接调用 `ImageLoaderModule.provideImageLoader` 并通过返回的 `ImageLoader` 请求 `MockWebServer` PNG 验证传入 `OkHttpClient` 绑定 | **PASS**：精准检出 `发表新帖`、`第3层`、`12层`、`%1$d层`、`NullPointerException`、`HTTP 503`（`6/6`），合法词 `楼层`/`层级`/`图层`/`第 3 楼`/`3楼` 误报为 `0`；主线程调用耗时 `< 1s`（零 `runBlocking` 阻塞），注册 `AnimatedImageDecoder.Factory`（API 34），`500MB` `image_cache` 目录正确，请求图片实测命中传入 `OkHttpClient` 的自定义拦截器头 `X-Probe-Client: bound` |

---

## 三、下游提示（非阻断）

1. **`TopBar` 透明玻璃留待 `E1`/`E2a`**：按 `E0.md` 契约，当前 `ForumPane.kt`（[L53](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumPane.kt#L53)）与 `ThreadDetailPane.kt`（[L45](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L45)）顶栏为纯色 `Surface`，`ForumChromeSpec.useGlassTopBar` 与 `Modifier.shellGlassSource()` 已就绪，将在 `E1`/`E2a` 接入 `HiGlassStyle.TopBar`。
2. **窄测试容器尺寸驱动备注**：`HiAdaptiveShell.kt`（[L144](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L144)）读取 `LocalWindowInfo.current.containerDpSize`，在 Compose UI Test 中联合使用 `DeviceConfigurationOverride.ForcedSize(...) then DeviceConfigurationOverride.WindowSize(...)` 即可同步驱动外层布局约束与 `LocalWindowInfo`。

---

## 四、第 2 轮复验（E0 修复轮 1 @ `fed1070`）

- **总判定**：**`PASS`**（无阻断项）
- **被测对象**：`fed1070b44f582d1c8b56131368c5d4478306810`（E0 修复轮 1：今日新帖与版块统一为 `ForumListSource`、带动作 Snackbar 改为 `Long`、移除测试钩子并走真实 `ViewModelProvider.Factory`）
- **验证分支与提交**：`verify/E0-probes` 已基于 `fed1070` 干净 rebase，最新探针提交 `1f03846f8e63025253549c38883dae305fb7e68a`
- **GitHub Actions CI**：[Android CI #45669780368](https://github.com/mailwmj/hipda-next/actions/runs/45669780368)
  - `headBranch`：`verify/E0-probes`
  - `headSha`：`1f03846f8e63025253549c38883dae305fb7e68a`
  - `Build, Lint & Unit Tests`（Job `139401605169`）：**`success`**（耗时 9m17s；`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿，`Check for Roborazzi diff images` 为 `skipped` 表示零视觉差异；汇总 **`990 tests, 990 passed, 0 failed, 0 errors, 0 skipped`**，其中 `:core-parser` `98/98`、`:hipda` `892/892`、`E0ProbeTest` **`13/13` 全绿**）
- **本地全量验证（`flock` 串行）**：`:hipda:testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:lintDebug` 全绿（`:hipda:testDebugUnitTest` **`892/892`**、`E0ProbeTest` **`13/13`**、`:core-parser:test UP-TO-DATE`、`finalizeTestRoborazziDebug`、`assembleDebug`、`lintDebug` 均成功）。
- **既有快照零漂移**：`git diff --name-status e71c3fb..fed1070 -- hipda/src/test/snapshots/` 与 `git diff --name-status fed1070 -- hipda/src/test/snapshots/` 均返回空（**0 张快照改动**）。

### 1. 接口适配与复验截屏核查

- [E0ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt) 已随 `fed1070` 接口变化移除对 `threadViewModelFactory` / `hasThreadStore` 测试钩子的依赖，改用 `LocalViewModelStoreOwner.defaultViewModelProviderFactory` 真实工厂路径注入 `ForumViewModel` / `ThreadDetailViewModel` / `NextAppHostViewModel`，接入 `ForumListSource` 与真实 `DefaultSearchRepository`。
- 视觉分支与双尺寸截屏（`visual_previewActivity_onOpenShellDemoNullVsNonNull_branchesCorrect` [L294](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L294)、`visual_captureSinglePane411dp_forManualInspection` [L324](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L324)、`visual_captureThreePane749dp_emptyRightAndSelectedDetail_forManualInspection` [L359](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L359)）在 `fed1070` 上重跑全绿，已复核 [v_e0_w411_h891_single_pane.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w411_h891_single_pane.png)、[v_e0_w749_h559_three_pane_empty_right.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w749_h559_three_pane_empty_right.png)、[v_e0_w749_h559_three_pane_detail.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E0/v_e0_w749_h559_three_pane_detail.png)，窄屏顶栏首枚「今日新帖」标签、三栏占位及液态底栏折射均渲染正常。

### 2. `a–f` 全部探针实测结果

| 探针 | 用例名（行号） | 验证要点 | 复验结果 |
|---|---|---|---|
| **a** | `probeA_realRepositories_sampleListToDetail_andErrorsConformToCopyMd` ([L400](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L400)) | 真实仓储链路（列表→详情）与 `COPY.md` 错误映射（含 `DefaultSearchRepository` 统一构造） | **PASS** |
| **b** | `probeB_realComposeTree_411_749_411_and_stateRestoration_preservesVmScrollAndZeroRequests` ([L504](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L504))<br>`probeB_realActivityConfigChange_recreatesActivity_preservesThreadVmScrollAndZeroRequests` ([L693](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L693)) | 去掉测试钩子走真实 `ViewModelProvider.Factory` 后，在 `411↔749↔411`、`StateRestorationTester` 及 `ActivityScenario.recreate()` 下验证 `ThreadDetailViewModel` 同实例、列表/详情滚动位置不变、零冗余请求与旧帖状态清理 | **PASS** |
| **c** | `probeC_managesGlassSource_strictSiblingTopology_andSolidPageFallback` ([L779](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L779))<br>`probeC_liveThemeFontAndPureBlackChange_preservesScrollAndZeroDetailRequests` ([L841](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L841)) | `managesGlassSource` 严格兄弟拓扑、纯色页自动回退及实时主题/纯黑/字号切换保活 | **PASS** |
| **d** | `probeD_copyTerminologyScanner_catchesSyntheticViolationsAndZeroFalsePositives` ([L922](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L922))<br>`probeD_imageLoaderModule_mainThreadNonBlocking_gifDecoderAndBoundOkHttpClient` ([L960](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L960)) | `CopyTerminologyScanTest.scanXmlContent` 合成违规全检出且零误报；`ImageLoaderModule` 主线程非阻塞与自定义 `OkHttpClient` 绑定 | **PASS** |
| **e** | `probeE_wide749dp_todayNewAndForumSwitch_queriesAndSidebarHighlightRestore` ([L1026](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L1026))<br>`probeE_narrow411dp_todayNewAndForumTabsSwitch_queriesAndChipHighlightRestore` ([L1086](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L1086)) | 宽屏 `749dp` 点击左栏「今日新帖」触发 `search.php?srchfrom=86400` 且左栏高亮 `TodayNew`，点回「Buy & Sell」/「Discovery」触发 `forumdisplay.php?fid=6`/`2` 且列表与左栏高亮恢复；窄屏 `411dp` 通过顶部 `FilterChip` 标签完成相同往返切换，请求与标签选中状态一致 | **PASS** |
| **f** | `probeF_consecutiveActionSnackbars_firstAutoDismissesInFiniteLongDuration_secondAppears` ([L1142](file:///home/mir/Documents/hipda-next.wt/V-E0/hipda/src/test/java/net/jejer/hipda/verify/E0ProbeTest.kt#L1142)) | `NextAppRoot` 内 `LocalPaneSnackbarController` 连发两条带动作 Snackbar；第 1 条在 `500ms`、`5s` 时仍在屏（非 `Short` 4s），推进累计超 `10s`（`Long` 有限时长）后自动消失且 `firstJob.isCompleted`，第 2 条随即正常显示，再推进 `11.5s` 后同样自动消失 | **PASS** |
