# 任务卡 C4c：自适应外壳三栏升级与左侧栏（HiNavigationSidebar + calculateShellPaneLayout）

【角色】你是 hipda-next 的 Android Jetpack Compose 自适应布局与外壳开发者（Kotlin、Compose、Material 3、WindowManager、DataStore、Roborazzi）。

【边界】
- 只在 worktree `/home/mir/Documents/hipda-next.wt/C4c`（分支 `card/C4c-three-pane-shell`，基于 `origin/main` @ `b4ab9160f2afccb63bd9d0601ac86d251ec78a19`）里工作。
- 允许修改的范围（严格以 `/home/mir/Documents/hipda-next.wt/_reports/D-3pane.md` 第五节“卡 1：C4c”为准）：
  - `hipda/src/main/java/net/jejer/hipda/ui/shell/`（更新 `ShellNavigationMode.kt`、`HiAdaptiveShell.kt`、`ShellIndicatorLayout.kt`、`ShellBarState.kt`；**删除 `HiNavigationRail.kt`**；新增 `HiNavigationSidebar.kt`、`ForumMetadata.kt`）；
  - `hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiTypography.kt`（仅更新第 14、152 行注释“导航轨”→“左侧栏”）；
  - `hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt`（**硬约束：只允许做三栏所需的结构性改动**——更新注释去掉“导航轨”、在确认无其他调用方时清理 `HiGlassBorderMode.EndEdgeOnly`；**严禁修改任何毛玻璃视觉参数或令牌**，包括 `HiGlassTokens` 中的模糊半径、着色不透明度、降级不透明度、描边宽度/透明度、阴影高度/透明度及模糊绘制参数——另有毛玻璃调参卡会改这些，避免合并冲突）；
  - `hipda/src/main/res/values/strings_shell.xml`（补齐左侧栏与多栏文案，全部逐字取自 `docs/design/copy.md`）；
  - `hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt`（更新 `ShellPreviewScreen` 展示左侧栏、`我的` 分组、示例版块、未读角标及 `≥ 680dp` 预览中右双栏占位）；
  - **跨线授权唯一修改（不与 D 线并行冲突）**：`hipda/src/main/java/net/jejer/hipda/data/settings/{AppSettings,SettingsRepository,DataStoreSettingsRepository}.kt` 及 `hipda/src/test/java/net/jejer/hipda/data/settings/DataStoreSettingsRepositoryTest.kt`（仅新增 `sidebarCollapsedPortrait` 与 `sidebarCollapsedLandscape` 布尔偏好、Preference Keys 与读写方法）；
  - `hipda/src/test/java/net/jejer/hipda/ui/shell/` 与 `hipda/src/test/snapshots/images/shell_*.png`（**只新增/更新/删除 `shell_*` 截图基线**，严禁触碰 `glass_*`、`theme_*`、`state_*`、`feedback_*`、`challenge_*`、`sample_export_*` 基线）。
- **不得修改**：任何共享文件（`gradle/libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`）、`MotionTokenScanTest` 白名单、`HiGlassTokens` 与毛玻璃视觉参数、`HiNavigationPill.kt`、`HiSnackbarHost.kt`（归 E3）、`feature/`。
- **不做（明确超出范围，归 E3 及业务页卡）**：中右双栏拖拽分隔线、`AnchoredDraggable` 吸附与键盘切档、详情顶栏“全屏阅读”内容联动、Snackbar 多栏对齐、`movableContentOf` 三栏业务返回栈与形态迁移（以上均为 E3）；业务页面接入（E1/E4b/E8–E15）。
- **界面卡与审查约束**：优先使用 Compose / Material 3 标准组件（`Column`/`Row`/`LazyColumn`/`Surface` + `ModalNavigationDrawer` 或标准 `AnimatedVisibility` 模态遮罩/侧滑关闭），**不得为侧栏无故手写自定义 `Layout` 或 `Modifier.Node`**（若确需自写 `Layout`，必须在 `_reports/C4c.md` 写明标准组件为何不够）；`src/main/` 中不得包含只为测试存在的代码（测试专用语义键、钩子、CompositionLocal）。审查为 Carbon 独立代码审查 + Hydrogen 视觉与状态复验。

【背景】
- 必读规格（均已合入 `main` @ `b4ab916`）：
  - `docs/design/product-design.md` §3（3.1 底部胶囊、3.2 `≥ 600dp` 常驻左侧栏：顶底 `48dp` 固定区、上方 5 行功能入口含 `< 760dp` 默认收起的 `我的` 折叠组、`749×559dp` 首屏完整露出 ≥3 关注版块、21 版块静态元数据与撞车/未知回退、窄↔宽双向映射表）、§4（4.1 通用分档 `480 / 铰链(leftW≥280 && rightW≥360) / 600 / 680 / 840dp`、整数取整顺序 `L → S → M → R`、`240dp` 浮层仅由按钮/`我的` 触发且无左缘右滑、浮层锁焦与 `Escape` 关闭、三栏 `isTraversalGroup` + `paneTitle`）、§5（左侧栏纯色 `surfaceContainerLow` + `1dp` 右分隔线，顶部固定区未滚动同为 `surfaceContainerLow`、滚动后渐变 `surfaceContainer` + `1dp` 底线；`≥ 600dp` 无底部胶囊、外壳毛玻璃数变为 0）、§6.3（左侧栏浮层 `spatialDefault`+`effectsDefault` / `exit`，常驻伸缩 `spatialSlow`+`effectsDefault`，减少动态效果一帧到位）、§8（术语表）、§10（10.1 截图矩阵与无障碍/键盘/减少动态效果验收、10.2 栏宽单测与首屏 ≥3 版块验收、10.3 侧栏滚底无胶囊重叠）。
  - `docs/design/copy.md`（v0.3，左侧栏与多栏全部文案）、`docs/design/tech-plan.md`（§7/§8）、`docs/design/backlog.md`（C4c 与 E3 边界）。
  - `/home/mir/Documents/hipda-next.wt/_reports/D-3pane.md` 第 3、4、5 节（卡 1：C4c 完整设计、逐台设备演算表与精确代码影响清单）。
  - `/home/mir/Documents/hipda-next.wt/_reports/R-D3pane.md` 第 6.3 节复审提示：`LocalWindowInfo.current.containerDpSize.width.value` 为 `Float`（如真机 `748.59f`、`851.69f`），`calculateShellPaneLayout` 在完成 `< 480f / 铰链 / < 600f / < 680f / < 840f` 分档判定后，**必须先对窗口宽做 `val w = widthDp.roundToInt()` 再代入整数公式 `L → S = w − L → M → R = S − M`**，保证非整数窗口宽同样严格满足 `L + M + R == w`。
  - `docs/agents/lesson.md`：本地 Gradle 前导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`；新 Roborazzi 基线按 DISPATCH §2 用 `--tests` 限定录制。
- 现有代码（已核对真实路径）：
  - `ui/shell/{ShellNavigationMode,HiAdaptiveShell,HiNavigationRail,HiNavigationPill,ShellIndicatorLayout,ShellBarState}.kt`
  - `ui/designsystem/feedback/HiRollingNumber.kt`（复用 `HiCountBadge`，注意 `> 99` 显示 `99+` 可在侧栏角标或小封装中复用动效令牌）、`ui/designsystem/feedback/HiPressFeedback.kt`（复用 `Modifier.hiPressScale`）、`ui/designsystem/HiTheme.kt`（`HiShapeTokens`、`HiCappedFontScale`、`HiTheme.isReducedMotion`）、`ui/designsystem/motion/{HiMotion,HiMotionTransitions}.kt`
  - `data/settings/{AppSettings,SettingsRepository,DataStoreSettingsRepository}.kt`
  - `utils/HiUtils.java:84-106`（21 个内置版块原始 `fid` 与名称参考，不依赖旧 FontAwesome）
  - `hipda/src/test/java/net/jejer/hipda/ui/shell/HiShellScreenshotTest.kt:204-208`（原硬编码 `assertEquals(1, glassCount)` 须按胶囊态 1 / 侧栏态 0 修正）与 `HiShellScrollScreenshotTest.kt`（补侧栏态滚底截图）

【目标】按 `product-design.md` v0.3 定稿将自适应外壳从“840dp 80dp 毛玻璃导航轨”升级为“600dp 起纯色可切换左侧栏 + 三栏布局计算纯函数 + 21 版块元数据 + 按方向持久化侧栏收起偏好”，通过全部栏宽单测、首屏/无障碍/动效测试与新 `shell_*` 截图矩阵。

【范围】
1. **分档与纯函数 `calculateShellPaneLayout`（`ui/shell/ShellNavigationMode.kt`）**：
   - 新增常量：`SHELL_SIDEBAR_COLLAPSED_WIDTH_BREAKPOINT_DP = 600f`、`SHELL_THREE_PANE_WIDTH_BREAKPOINT_DP = 680f`、`SHELL_SIDEBAR_EXPANDED_WIDTH_BREAKPOINT_DP = 840f`、`HINGE_LEFT_MIN_WIDTH_DP = 280`、`HINGE_RIGHT_MIN_WIDTH_DP = 360`。
   - `ShellNavigationMode`：`BottomPill`、`CompactPill`、`SidebarCollapsed`（`600–839dp`）、`SidebarExpanded`（`≥ 840dp`）（移除 `Rail`）。
   - 纯函数 `calculateShellPaneLayout(widthDp: Float, heightDp: Float, hingeBoundsDp: ...? = null, leftCollapsedPref: Boolean = false, hasDetailContent: Boolean = false)`：
     - 严格按优先级匹配：**高 `< 480f`（矮屏单栏 + 紧凑胶囊）→ 竖向物理铰链分隔（`hingeBoundsDp != null`）→ 宽 `< 600f`（窄屏单栏 + 底部胶囊）→ `600–679dp`（图标轨单栏）→ `680–839dp`（紧凑三栏）→ `≥ 840dp`（完整三栏）**；
     - **先分档判定，再 `val w = widthDp.roundToInt()`**，随后按整数顺序计算：
       - `L`：单栏/矮屏/铰链为 `0`；`600–839dp` 或 `≥ 840dp` 主动收起为 `64`；`≥ 840dp` 展开为 `(0.20f * w).roundToInt().coerceIn(176, 220)`；
       - `S = w - L`；
       - 仅在三栏（`≥ 680dp`）计算：`M = (0.40f * S).roundToInt().coerceIn(256, 380)`，`R = S - M`（恒 `≥ 360`），保证 `L + M + R == w`；
       - 吸附档位：`M_balanced = minOf((0.50f * S).roundToInt(), S - 360, 480)`，与 `M` 差 `< 24` 时合并；仅在 `hasDetailContent == true` 时包含 `0`（列表收起）档；
       - 竖向铰链分隔：`leftW = hingeBoundsDp.left.roundToInt()`、`rightW = (w - hingeBoundsDp.right.roundToInt()).coerceAtLeast(0)`；满足 `leftW >= 280 && rightW >= 360` 启用铰链双栏（对齐 `hingeBounds`，紧凑胶囊居中于左列表侧）；任一侧不足退回单栏铺满窗口（`rightPaneWidth == 0`），紧凑胶囊居中于较宽一侧（等宽取左；较宽侧 `< 280` 时整窗底部居中）；
       - 单栏、矮屏、`600–679dp` 图标轨单栏、铰链退回单栏一律输出 `rightPaneWidthDp == 0`。
   - 新增 `ShellSidebarEntry` 模型（`TodayNew`、`Search`、`Sms`、`Notify`、`FavoritesAndAttention`、`MyThreads`、`MyReplies`、`History`、`Forum(val fid: Int)`、`Settings`）及与 `ShellDestination` 的确定性双向映射纯函数（窄→宽在“我的”总菜单未进子页时：优先恢复本次会话最近访问的“我的”子项，未访问过默认 `FavoritesAndAttention`）。
   - 更新 `HiShellTestTags`（替换 `NavigationRail` 为 `NavigationSidebar`、`SidebarModalDrawer`、`SidebarToggleButton` 等）。
2. **21 版块静态元数据表（`ui/shell/ForumMetadata.kt`）**：
   - 参考 `HiUtils.java:84-106` 建立 21 个内置版块元数据（`fid`、`fullName`、`shortLabel`、`icon: ImageVector?`，不依赖 FontAwesome、不加新依赖）；
   - 提供纯函数计算收起轨展示模式：默认 `18dp` 矢量图标 + `10sp` 1–2 字短标；遇到未知 `fid` 或关注列表中多个版块映射到同一图标时，自动回退为 `28×28dp` 圆角短标/首字徽标（最多 2 个拉丁字母或 1 个汉字）。
3. **左侧栏组件 `HiNavigationSidebar.kt`（删除 `HiNavigationRail.kt`）**：
   - 使用标准 Compose/M3 组件（`Column`/`Row`/`LazyColumn`/`Surface` + 标准模态抽屉/暗罩，严禁无故手写 `Layout`），`HiCappedFontScale` 限制文字缩放 `≤ 1.25×`，每项触摸目标 `≥ 48dp`，单行省略不换行。
   - 视觉与结构严格遵循 `product-design.md` §3.2、§5、§6.3：
     - 纯色 `surfaceContainerLow` 主体与未滚动顶部固定区，右侧 `1dp outlineVariant` 细分隔线，**无毛玻璃**；滚动时顶部固定区以 `HiMotion.effectsDefault()` 渐变为 `surfaceContainer` 并显现 `1dp outlineVariant` 底线；
     - 顶部 `48dp` 固定区（展开态：头像 + 用户名/未登录 `登录` + 右侧 `48×48dp` `收起侧栏`；收起态：居中 `48×48dp` `展开侧栏`）；
     - 中间 `LazyColumn(Modifier.weight(1f))`：上方 5 行（`今日新帖`、`搜索`、`短消息`[未读角标，`0` 不显示，`>99` 显示 `99+`]、`提醒`[同上]、`我的` 可折叠分组[窗口高 `< 760dp` 默认收起、`≥ 760dp` 默认展开，展开后含 4 子项；收起态点 `我的` 立即弹出 `240dp` 浮层或展开左栏并自动展开 `我的` 分组]）+ `9dp` 分隔线 + 关注版块列表（展开态含 `版块` 标题与 `管理版块` 图标按钮、空时显示 `去管理版块添加关注的版块` + `管理版块`；收起态不占独立标题行，末尾放 `+` `管理版块` 与 `设置`），确保 `749 × 559dp` 首屏 `64dp` 轨完整露出 ≥3 个默认关注版块；
     - 底部 `48dp` 固定区（展开态固定 `设置` + 上方 `1dp outlineVariant` 细线；收起态固定 `48×48dp` 圆形账号头像）；
     - `600–839dp` `240dp` 浮层展开（右侧 `20dp` 圆角 + 暗罩，**仅由顶部 `展开侧栏` 按钮或 `我的` 图标触发，绝不设屏幕左缘右滑手势**；收回支持内区左滑/点罩/点项/系统返回/键盘 `Escape`；展开时**锁定无障碍与键盘焦点**；从浮层点 `管理版块` 或账号打开居中模态框时同步先关闭浮层；动效 `spatialDefault`+`effectsDefault` / `exit`）；
     - `≥ 840dp` `176–220dp` ↔ `64dp` 原地伸缩（`spatialSlow`+`effectsDefault`）；
     - `64dp` 轨各项 `contentDescription` 合并全名与未读数（如 `短消息，3 条未读`），长按/悬停展示完整名称 Tooltip。
4. **外壳集成与周边清理**：
   - `HiAdaptiveShell.kt`：用 `isSidebar` 替换 `isRail`，按 `calculateShellPaneLayout` 计算的 `animatedSidebarWidthDp`（`HiMotion.spatialSlow()`）让位（支持全屏阅读隐藏左栏至 `0dp`）；仅在胶囊态启用 `hiGlassSource` 与 `nestedScroll`，侧栏态底部 `contentPadding` 仅留 `navigationBars` inset；铰链退单栏时将紧凑胶囊置于较宽一侧避开铰链；为侧栏与内容区声明 `isTraversalGroup = true` 与 `paneTitle`；对外保持清晰稳定的状态回调，供 `PreviewActivity` 与后续 `E1`/`E3`/`E8–E12` 接入。
   - `ShellIndicatorLayout.kt`：删除 `Rail` 分支与 `Rail*` 常量；`ShellBarState.kt` 更新注释；`HiTypography.kt` 更新注释；`HiGlassSurface.kt` 仅做结构性注释/未用 `EndEdgeOnly` 清理，**零改动任何毛玻璃视觉参数/令牌**。
   - `strings_shell.xml`：逐字取自 `docs/design/copy.md` 补齐左侧栏与多栏文案（通过术语表扫描，无禁用词组）。
   - `AppSettings.kt` / `SettingsRepository.kt` / `DataStoreSettingsRepository.kt`（跨线授权）：新增 `sidebarCollapsedPortrait: Boolean = false`、`sidebarCollapsedLandscape: Boolean = false`、对应 Preference Keys（`sidebar_collapsed_portrait`、`sidebar_collapsed_landscape`）及 setter。
   - `PreviewActivity.kt`：更新 `ShellPreviewScreen` 展示左侧栏、`我的` 分组、关注版块、未读角标及 `≥ 680dp` 预览中右双栏占位。

【步骤】
1. 先更新 `AppSettings` / `SettingsRepository` / `DataStoreSettingsRepository` 并补齐 `DataStoreSettingsRepositoryTest` 的默认值、读写与损坏回退测试。
2. 先写失败的纯函数与 UI 测试：
   a. `ShellNavigationModeTest`：`calculateShellPaneLayout` 覆盖 `340、411、559、599、600、640、679、680、701、744、749、748.59f、800、839、840、852、860、883、900、933、1024、1100、1280dp`、矮屏 `< 480dp` 与多种铰链分隔（`749dp` 铰链双栏、`701dp` 单侧不足退单栏、`260|440dp` 非对称退单栏胶囊放宽侧、`559dp` 退单栏胶囊整窗居中），严格断言 `L + M + R == w`、三栏与启用铰链双栏 `R ≥ 360`、单栏/图标轨单栏/矮屏/退单栏 `R == 0`、吸附点与 `< 24dp` 合并、`hasDetailContent == false` 无 `0dp` 档；验证 `ShellSidebarEntry ↔ ShellDestination` 双向映射与 `ForumMetadata` 21 版块撞车/未知 `fid` 回退。
   b. `HiAdaptiveShellTest` & `HiShellBoundsGuardTest` & `HiShellScrollAndPressTest`：
      - `749 × 559dp` 下 `64dp` 图标轨首屏无需滚动完整可见 ≥3 个默认关注版块（断言 `boundsInRoot` 全部在可视窗口内）；
      - `240dp` 浮层展开（仅按钮/`我的` 触发）、焦点锁定（底层内容不可遍历/不可点击）、系统返回关闭、外接键盘 `Escape` 关闭、打开居中模态框同步收起；
      - `withAnimatorDurationScale(0f)` 减少动态效果下浮层开合与 `≥ 840dp` 常驻侧栏伸缩 1 帧到位；
      - `我的` 分组 `< 760dp` 默认收起 / `≥ 760dp` 默认展开、`64dp` 轨点 `我的` 一步展开浮层并展开分组；
      - `64dp` 轨 `contentDescription` 合并未读数、三栏 `isTraversalGroup` / `paneTitle`、≥48dp 触控区、`1.25×` 字体钳制、`StateRestorationTester` 保态；
      - 侧栏态不随滚动隐藏且底部仅留 `navigationBars` inset；`w559dp` 胶囊居中上限 `420dp` 且滚到底不被遮挡。
3. 实现 `ShellNavigationMode`、`ForumMetadata`、`HiNavigationSidebar`、`HiAdaptiveShell`、`ShellIndicatorLayout` 及周边清理。
4. 更新 `HiShellScreenshotTest`（修复 L204–208 按胶囊态 1 / 侧栏态 0 断言毛玻璃数）与 `HiShellScrollScreenshotTest`（补齐 `≥ 600dp` 侧栏态滚底截图），按 `product-design.md` §10.1 矩阵删除旧 `shell_w700_h900_*`、`shell_w900_h1120_*` 并仅录制新的 `shell_*` 基线（6 尺寸 × 深浅色 + 特殊窗口：`891×411`、`340×800`、`749×559` 浮层展开态、`933×696` 左栏主动收起态、`411×891` 与 `840×900` 最大字号 `1.3×` + 侧栏滚底）。
5. 本地按 DISPATCH §2 跑通本卡全部测试、`MotionTokenScanTest`、`DataStoreSettingsRepositoryTest`、Roborazzi verify、`:hipda:assembleDebug`、`:hipda:lintDebug`，确认非 `shell_*` 基线零改动且 `HiGlassTokens` 零改动，提交推送 `card/C4c-three-pane-shell` 并用 `gh run watch <id> --interval 60` 等 CI 全绿。

【自检】
- 本地按 DISPATCH §2 串行执行（先导出 mise 的 `ANDROID_HOME`/`JAVA_HOME`）：
  - 录制本卡截图：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:recordRoborazziDebug --tests "net.jejer.hipda.ui.shell.HiShell*ScreenshotTest" -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 验证本卡全部测试 + 设置测试 + 动效扫描：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.ui.shell.*" --tests "net.jejer.hipda.data.settings.DataStoreSettingsRepositoryTest" --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" -Proborazzi.test.verify=true -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 推送前编译与 lint：`flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:assembleDebug :hipda:lintDebug -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`
  - 每次结束后执行 `./gradlew --stop`。
- 推送后以 GitHub Actions 全绿为最终证据。

【验收】
- [ ] **边界与零冲突守卫**：`HiGlassSurface.kt` 零改动任何毛玻璃视觉参数/令牌（仅结构性改动）；仅改动/新增 `shell_*` 截图基线；未改任何共享文件或 `MotionTokenScanTest` 白名单；`src/main/` 无测试专用代码；`HiNavigationRail.kt` 已删除。证据：`git diff origin/main...HEAD -- hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt` + `git diff --stat origin/main...HEAD -- hipda/src/test/snapshots/`。
- [ ] **`calculateShellPaneLayout` 栏宽单测**：覆盖 `559、600、679、680、749、839、840、900、1280dp`（及浮点非整数宽）与铰链双栏/退单栏多种情况，断言 `widthDp` 先 `roundToInt` 后精确满足 `L + M + R == w`、三栏与启用铰链双栏 `R ≥ 360`、单栏/图标轨单栏/矮屏/退单栏 `R == 0`、吸附点与合并正确。证据：`ShellNavigationModeTest` 用例列表 + CI 汇总。
- [ ] **首屏版块、浮层交互与减少动态效果**：`749 × 559dp` 首屏完整可见 ≥3 个默认关注版块；`240dp` 浮层锁焦、返回与 `Escape` 关闭且无左缘右滑；`withAnimatorDurationScale(0f)` 一帧到位；`sidebarCollapsedPortrait/Landscape` 持久化与 `StateRestorationTester` 通过；`MotionTokenScanTest` 与术语扫描通过。证据：测试类:方法名 + CI 汇总。
- [ ] **组件与截图矩阵**：全使用标准 Compose/M3 组件（若有任何自写 `Layout` 已在 `_reports/C4c.md` 写明理由）；新 `shell_*` 截图矩阵（含侧栏滚底无胶囊重叠）已录制提交且 CI verify 全过。证据：`_reports/C4c.md` 截图清单 + CI run 链接 + 最终 commit hash。

【停止条件】遇到同一项用 3 种不同方法都失败即停下，回复 STUCK 并说明原因；不得修改验收标准、不得跳过检查、不得删除或放宽已有测试。

【交付】
- 分支：`card/C4c-three-pane-shell`；Conventional Commits（如 `feat(shell): 升级自适应外壳为三栏与左侧栏体系`）。
- 详细证据、自写 Layout 理由说明（如有）、截图清单、自检日志与本卡对外 API 清单（签名 + 文件:行号，供 E1、E3、E8–E12 使用）写入 `/home/mir/Documents/hipda-next.wt/_reports/C4c.md`；最后一条回复按文末通用约定控制在 ≤600 字。

【清理】只删除本会话自己创建的临时文件。

【通用约定（每张卡都适用）】
- 不要在整张卡提交并推送之前结束回合。
- 本地编译必须遵守 `docs/agents/DISPATCH.md` 第 2 节：只用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`；编译以 GitHub Actions 为准。
- 不要用真实账号对论坛做写操作；测试不得访问真实站点（4d4y.com），网络测试只用 MockWebServer。
- 先在本地跑本卡相关测试（按 lesson.md 导出 mise 的 ANDROID_HOME/JAVA_HOME，flock 串行），本地变绿后才推送；不要拿 CI 调试半成品。完成标准以 CI 运行结果为证据（给出 run 链接和 commit hash）。推送后用 `gh run watch <id> --interval 60` 等结果（不要用默认间隔、不要写轮询循环）；失败用 `gh run view <id> --log-failed`；遇到 `API rate limit exceeded` 用 `gh api rate_limit` 查重置时间，等重置，不当成 CI 失败。
- 只在本卡的 worktree 里写文件；不要在 `/home/mir/Documents/hipda-next`（负责人的主目录）里 checkout、写文件或提交。
- 不改共享文件：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`。需要改时停下来回复 NEEDS-INPUT。
- 不改 `MotionTokenScanTest` 的白名单，也不往白名单里加条目；动效参数只从 `ui/designsystem/motion/HiMotion.kt` 的令牌和 `HiMotionTransitions.kt` 的 `hi*` 函数取。
- 界面字符串放在本卡指定的 `res/values/strings_<功能>.xml`，文本逐字取自 `docs/design/copy.md`，不自己编。
- 提交用 Conventional Commits（类型英文，描述可中文）；只在有阶段性结果时推送。
- 遇到同一项用 3 种不同方法都失败：停下，回复 STUCK 并说明。不得修改验收标准、不得跳过检查、不得删除或放宽已有测试来让 CI 变绿。
- 清理：只删除本会话自己创建的临时文件。
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/<卡号>.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

首先 `cd /home/mir/Documents/hipda-next.wt/C4c`，确认 `git status` 干净、HEAD 为 b4ab9160f2afccb63bd9d0601ac86d251ec78a19；首次推送用 `git push -u origin card/C4c-three-pane-shell`。
