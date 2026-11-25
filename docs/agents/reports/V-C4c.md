# 验证卡 V-C4c 详报：独立视觉复验与行为探针验证（C4c 三栏自适应外壳）

- **总判定**：**PASS**
- **被验对象**：`origin/card/C4c-three-pane-shell` @ `6706b09869ece5329c12738c2782bf303f305b29`
- **验证分支 / worktree**：`verify/C4c-visual` @ `c467234bfa3409108b5ba4d0c31ad00b72c0a1e4`（`/home/mir/Documents/hipda-next.wt/V-C4c`）
- **CI 证据**：[Android CI Run #45607125357](https://github.com/mailwmj/hipda-next/actions/runs/45607125357)（`workflow_dispatch` on `verify/C4c-visual`，`Build, Lint & Unit Tests` 成功，耗时 7m32s；总计 607 tests / 607 passed / 0 failed / 0 errors / 0 skipped，Roborazzi verify 全过）
- **隔离声明**：未阅读实现者测试源码与 `_reports/C4c.md`，全部结论均来自逐张读图检查、生产代码核对及自建探针 `C4cProbeTest` 与 CI 实跑输出。

---

## 一、边界与零冲突核对

1. **基线隔离检查**（`git diff --stat origin/main...HEAD -- hipda/src/test/snapshots/`）：
   - 仅 `shell_*` 截图基线有变更（共 25 项：删除旧 `shell_w700_h900_{light,dark}.png` 与 `shell_w900_h1120_{light,dark}.png` 4 张，新增/更新当前全部 21 张 `shell_*.png`）。
   - **非 `shell_*` 基线**（`glass_*`、`theme_*`、`state_*`、`feedback_*`、`challenge_*`、`sample_export_*`）相对 `origin/main` **零改动**。
2. **共享文件与毛玻璃参数零改动**（`git diff origin/main...HEAD`）：
   - `gradle/`、`hipda/build.gradle.kts`、`settings.gradle.kts`、`hipda/src/main/AndroidManifest.xml`、`.github/`、`HiApplication.kt`、`MotionTokenScanTest.kt` 均 **0 改动**。
   - `HiGlassSurface.kt` 仅移除未用的 `HiGlassBorderMode` 枚举/分支及更新注释，**零改动任何毛玻璃视觉参数或 `HiGlassTokens` / `HiGlassStyle` 令牌**。
   - `HiNavigationRail.kt` 已确认删除。

---

## 二、逐张视觉检查明细（全部 21 张 `shell_*.png`）

| 检查项 | 截图文件 | 核对结果 | 观察详情 |
|---|---|---|---|
| 1. `559×749` Fold8 竖拿（浅/深） | `shell_w559_h749_light.png`、`shell_w559_h749_dark.png` | **通过** | 单栏内容 + 底部居中毛玻璃胶囊（宽 ≤ 420dp，左右边距对称）；4 个一级导航（`论坛`/`消息`/`搜索`/`我的`）文字完整无裁切，浅/深模式高对比度清晰。 |
| 2. `640×1024` 图标轨单栏（浅/深） | `shell_w640_h1024_light.png`、`shell_w640_h1024_dark.png` | **通过** | 左侧 `64dp` 纯色 `surfaceContainerLow` 图标轨 + 右侧 `1dp` `outlineVariant` 分隔线 + 单栏内容，无底部胶囊；上方 5 入口（含 `3`/`12` 未读角标）、分隔线、关注版块（`D`/`BS`/`极`）、`+`、`⚙` 与底部固定 `H` 头像布局清晰。 |
| 3. `749×559` Fold8 紧凑三栏（浅/深 + `240dp` 浮层） | `shell_w749_h559_light.png`、`shell_w749_h559_dark.png`、`shell_w749_h559_drawer_light.png` | **通过** | 三栏宽度 `64 \| 274 \| 411dp`（中栏 `< 300dp` 无独立大头像列）。`64dp` 轨上 `我的` 默认收起，无需滚动完整露出 3 个默认关注版块（`D`/`BS`/`极`）及 `+`，短标与矢量图标可辨；`240dp` 浮层展开态右侧具 `20dp` 上下圆角，半透明暗罩覆盖中右两栏且不挤压底层布局，文字无裁切无重叠。 |
| 4. `933×696` 折叠屏宽态完整三栏（浅/深 + 主动收起） | `shell_w933_h696_light.png`、`shell_w933_h696_dark.png`、`shell_w933_h696_collapsed_light.png` | **通过** | 常驻展开左栏 `187dp`（`187 \| 298 \| 448dp`），图标、全名与 `3`/`12` 角标清晰；顶部 `48dp` 固定区与主体同为 `surfaceContainerLow` 无异色补丁，底部 `设置` 固定区带 `1dp` 分隔线无异色补丁；主动收起态平滑退为 `64dp` 图标轨（中右让出为 `348 \| 521dp`）。 |
| 5. `840×900` 与 `411×891` 最大字号 `1.3×` | `shell_w840_h900_step5_font130_light.png`、`shell_w411_h891_step5_font130_light.png` | **通过** | `840×900` 左栏为最小展开宽 `176dp`（`176 \| 266 \| 398dp`），因 `HiCappedFontScale` 钳制 `1.25×` 生效，左栏全部条目（含最长 5 字 `收藏与关注`、`我的帖子`、`我的回复`、`浏览历史`、`短消息 3`、`提醒 12`、`设置`）单行完整无裁字无重叠无溢出；`411×891` 底部胶囊 4 项文字完整、无上下裁切。 |
| 6. `1280×800` 平板完整三栏（浅/深） | `shell_w1280_h800_light.png`、`shell_w1280_h800_dark.png` | **通过** | 左栏封顶 `220dp`（`220 \| 380 \| 680dp`），因高 `≥ 760dp`，`我的` 分组默认展开且 4 子项与版块全名清晰，浅/深色层级与对比度清晰。 |
| 7. `891×411`、`340×800`、`411×891` 及滚底截图 | `shell_w891_h411_compact_light.png`、`shell_w340_h800_narrow_light.png`、`shell_w411_h891_{light,dark}.png`、`shell_scroll_w411_h891_{bottom,hidden}_light.png`、`shell_scroll_w749_h559_sidebar_bottom_light.png` | **通过** | `891×411`（矮屏 `< 480dp`）呈现单栏 + 紧凑胶囊（仅选中项带文字）；`340×800`（`< 360dp`）窄窗胶囊仅选中项带文字无裁切；`411×891` 滚底第 60 条完全位于胶囊上方、滚隐态胶囊全隐；`749×559` 侧栏态滚底第 60 条完整露出、底部无胶囊重叠、顶底固定区无裁字无遮挡。 |

---

## 三、独立状态与行为探针验证（`C4cProbeTest.kt`）

文件位置：`hipda/src/test/java/net/jejer/hipda/verify/C4cProbeTest.kt`（共 6 个测试方法，本地与 CI 全部通过）：

1. **探针 a（`probeA_windowSizeDynamicResize_preservesCollapsePrefAndSelectionAndBidirectionalMapping`）— PASS**
   - 在单次会话内通过可变 `LocalWindowInfo` 依次动态切换 `559×749 → 749×559 → 933×696 → 749×559 → 559×749 → 933×696 → 1280×800 → 900×1200 → 559×749 → 749×559 → 1280×800dp`：
   - 验证在 `933×696dp` 主动收起侧栏（`187dp → 64dp`）后，历经紧凑三栏 `749×559` 与单栏 `559×749`，再回到 `933×696` 与 `1280×800` 时横屏收起偏好保持 `64dp` 不丢失，且竖屏 `900×1200dp` 保持独立的展开偏好（`180dp`）。
   - 验证选中关注版块 `Forum(6)` 与“我的”子项 `MyReplies` 跨断点往返时均与 §3.2 双向映射一致（窄→宽优先恢复 `activeMineSubPage` / `lastVisitedMineEntry`，默认 `FavoritesAndAttention`；宽→窄准确高亮 `Forum` / `Mine` / `Messages` / `Search`）。
   - 同步以真实 `DataStoreSettingsRepository` 文件验证 `sidebarCollapsedPortrait` / `sidebarCollapsedLandscape` 独立读写与跨实例重建恢复。
2. **探针 b（`probeB_modalDrawerOpen_resizeAcrossBreakpointsAndStateRestoration_noCrashNoDeadlock`）— PASS**
   - 在 `749×559dp` 分别经顶部 `展开侧栏` 按钮与 `64dp` 轨 `我的` 图标（验证自动展开 `我的` 分组子项）打开 `240dp` 浮层后：
   - 动态切至 `559×749dp`（跨下 `600dp` 切回单栏）与 `933×696dp`（跨上 `840dp` 升常驻展开），断言均无崩溃、无残留 `SidebarModalDrawer` / `SidebarModalScrim`、切回 `749×559dp` 不复活暗罩，且底层按钮交互不死锁。
   - 以 `StateRestorationTester.emulateSaveAndRestore()` 模拟打开浮层跨断点配置重建（`749×559 → 559×749` 与 `749×559 → 933×696`），断言正常清除暗罩且底层交互无死锁；验证浮层内点击 `管理版块` 与 `账号` 时同步关闭浮层。
3. **探针 c（`probeC_floatWidthsAndHingeBoundaries_exactWidthSumAndMinimumConstraints`）— PASS**
   - 对 21 组真机及临界非整数宽度（含 `599.6f`、`679.6f`、`700.95f`、`740.49f`、`748.59f`、`839.6f`、`841.14f`、`851.69f`、`882.87f`、`1023.6f`、`1279.8f`、`1480.4f`）× 收起/展开偏好 × `hasDetailContent` 组合调用 `calculateShellPaneLayout`：
   - 断言严格满足 `L + M + R == widthDp.roundToInt()`、所有三栏与启用铰链双栏 `R ≥ 360dp`、非三栏与铰链退单栏 `R == 0dp`、`hasDetailContent == false` 不包含 `0dp` 吸附档。
   - 验证铰链边界：`749dp` / `748.59f` 居中折痕启用 `HingeTwoPane` 且胶囊锚定左侧 `pillAnchor = [0, leftW]`；`701dp` 半折（单侧 `350.5 < 360dp`）退 `HingeFallbackSinglePane` 且等宽胶囊落左侧 `[0, 351]`；`260|440dp` 退单栏胶囊置宽侧 `[260, 700]`；`440|260dp` 置 `[0, 440]`；`559dp` 双侧不足 `280dp` 居中整窗 `[0, 559]`。
4. **探针 d（`probeD_modalFocusLockBackAndEscape_blocksUnderlyingInteractionAndTraversal` & `probeD_reducedMotionScaleZero_drawerAndPermanentSidebarSettleInOneFrame`）— PASS**
   - 验证 `240dp` 浮层打开后：合并语义树中底层 `ContentContainer` 内容不可遍历（count = 0）；连续 15 次 `Key.Tab` 焦点无法穿过浮层进入底层按钮；点击底层按钮屏幕坐标命中 `SidebarModalScrim` 并关闭浮层（`contentClicks == 0`）；系统返回键、`isFocused` 下 `Key.Escape` 与 `onRoot` 直接派发 `Key.Escape` 均可关闭浮层，关闭后底层交互立即恢复。
   - 在 `withAnimatorDurationScale(0f)` 下关闭自动时钟推进：`749×559dp` 浮层开合与 `933×696dp` 常驻侧栏 `187dp ↔ 64dp` 原地伸缩均在首动画帧一步到位（与 `scale = 1f` 对照组处于 `65..186dp` 过渡中途形成对照）。
5. **探针 e（`probeE_fold8FirstScreenForums_andUnknownOrCollidingForumFallback`）— PASS**
   - 在 `749×559dp` 无需滚动验证 `我的` 默认收起、`短消息，3 条未读` / `提醒，12 条未读` 语义描述合并正确、3 个默认关注版块（`fid = 2, 6, 7`）`boundsInRoot` 全部完整落在 `559dp` 可视高度与 `64dp` 宽轨内。
   - 核对 `ForumMetadataRegistry.BuiltInForums` 含全部 21 个内置版块且图标互不撞车；验证 `resolveCollapsedForumDisplays` 对同图标碰撞组合回退为不同 `MonogramBadge`；在 `HiAdaptiveShell` `64dp` 轨传入未知 `fid`（`9999`、`8888="Open Source"`、`7777="摄影"`）无崩溃，无障碍描述完整保留，且逐节点像素采样（`captureToImage`）验证回退徽标均绘制出非空白且两两可区分的视觉图案。

---

## 四、生产代码核对与实现记录（关键 API 与位置）

- `ShellNavigationMode.kt:20-455`：断点常量、`ShellNavigationMode`、`ShellPaneLayoutMode`、`calculateShellPaneLayout`（`L265-418`，先浮点分档后 `widthDp.roundToInt()` 再按 `L → S → M → R` 整数计算）、`ShellSidebarEntry`（`L139-191`）、双向映射纯函数 `toShellDestination` / `mapDestinationToSidebarEntry`（`L194-237`）。
- `ForumMetadata.kt:25-367`：`ForumMetadataRegistry.BuiltInForums` 21 版块静态元数据与矢量图标、`resolveCollapsedForumDisplays` 碰撞/未知 `fid` 回退（`L56-76`）、`normalizeForumMonogram`（`L79-92`）。
- `HiNavigationSidebar.kt:182-1167`：`HiNavigationSidebar`（`L182-236`）、`HiNavigationSidebarModalOverlay`（`L244-360`）、`SidebarColumnBody`（`L372-602`）全部使用 Compose / Material 3 标准容器（`Column`/`Row`/`LazyColumn`/`Surface`），未手写自定义 `Layout`；`HiCappedFontScale`（`L197`、`L325`）限制文字缩放 `≤ 1.25×`；`strings_shell.xml`（`L1-42`）全部文案对齐 `docs/design/copy.md`。
- `HiAdaptiveShell.kt:81-358`：`HiAdaptiveShell` 按 `calculateShellPaneLayout` 让位（`animatedSidebarWidthDp` 使用 `HiMotion.spatialSlow()` / `exit(spatialSlow())`）、`underlyingInteractionGuard`（`L225-236`）锁定底层焦点与语义、铰链退单栏 `pillAnchor` 对齐（`L303-320`）。
- `AppSettings.kt:59-60`、`SettingsRepository.kt:44-45`、`DataStoreSettingsRepository.kt:55-56,138-142,199-202`：按方向持久化 `sidebarCollapsedPortrait` 与 `sidebarCollapsedLandscape`。

---

## 五、本地与 CI 验证命令实跑汇总

1. **本地验证**（`V-C4c` worktree，遵守 `DISPATCH.md §2` `flock` + `-Xmx3g` + `./gradlew --stop`）：
   - 命令：`./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.C4cProbeTest" --tests "net.jejer.hipda.ui.shell.*" --tests "net.jejer.hipda.data.settings.DataStoreSettingsRepositoryTest" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:lintDebug`
   - 结果：`BUILD SUCCESSFUL`，`139 tests, 139 passed, 0 failed, 0 skipped`（含 `C4cProbeTest` 6 项），`Roborazzi` 校验通过，`ui/shell` 与 `verify` 目录 lint 0 告警。
2. **GitHub Actions CI**：
   - Run：`https://github.com/mailwmj/hipda-next/actions/runs/45607125357`（`verify/C4c-visual` @ `c467234bfa3409108b5ba4d0c31ad00b72c0a1e4`）
   - 结果：`success`（`assembleDebug`、`assembleRelease`、`lintDebug`、`testDebugUnitTest` + `:core-parser:test` + `-Proborazzi.test.verify=true` 全部通过；JUnit 汇总 **607 tests, 607 passed, 0 failed, 0 errors, 0 skipped**，其中 `C4cProbeTest` 6/6、`HiAdaptiveShellTest` 29/29、`HiShellBoundsGuardTest` 32/32、`HiShellScreenshotTest` 18/18、`HiShellScrollAndPressTest` 21/21、`HiShellScrollScreenshotTest` 3/3、`ShellNavigationModeTest` 9/9、`DataStoreSettingsRepositoryTest` 14/14、`MotionTokenScanTest` 7/7）。
