# 独立代码审查报告 R-E0：M5 阅读核心端到端 Tracer Bullet 与 E 线不可逆公共基座

- **审查结论**：**`CHANGES-REQUESTED`**
- **审查对象**：`origin/card/E0-reading-tracer-bullet` @ `114951854e8890f89e0b27fe1e63714544422f5c`（简称 `1149518`）
- **对比基线**：`e71c3fb`（另用 `git merge-tree --write-tree origin/main 1149518` 对照最新 `origin/main` @ `cbcb9c0` 核验，与已合入的 `D6`/`D8a`/`D9` **0 合并冲突**）
- **GitHub Actions CI 核实**：[Android CI #45655944089](https://github.com/mailwmj/hipda-next/actions/runs/45655944089)
  - `headSha`：`114951854e8890f89e0b27fe1e63714544422f5c`（与分支提交 100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139352506820`）：**SUCCESS**，核实 `.github/workflows/android.yml` 已完整执行并通过 `./gradlew assembleDebug assembleRelease lintDebug`、Room schema 校验、`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 Debug/Release APK 产物包名校验
- **阻断项**：**3 项**（`B1`–`B3`，合计改动 `< 60` 行即可闭合）
- **非阻断建议项**：**5 项**（`S1`–`S5`）

---

## 一、阻断项（Blocking Issues）

### B1. 【不可逆契约断层】`今日新帖`（`ShellSidebarEntry.TodayNew`）未在 `NextAppHostViewModel` / `NextAppRoot` 打通，致 Wave 2 `E1` 无法零改 `feature/app/*` 接入

- **文件与行号**：
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt:74-107`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt:88-100`
  - `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt:13`
- **触发路径**：
  1. **宽屏左栏 → 中栏列表断路**：在 `≥ 600dp` 侧栏态点击左栏第一项 `今日新帖`，`HiAdaptiveShell` 回调 `NextAppHostViewModel.selectSidebarEntry(ShellSidebarEntry.TodayNew)`（`NextAppRoot.kt:73` → `NextAppHostViewModel.kt:81`）。`:88` 写为 `activeForumFid = (entry as? ShellSidebarEntry.Forum)?.fid ?: cur.activeForumFid`（且 `:82` 未置 `hasUserSelectedForum = true`），`activeForumFid` 原封不动停留在旧版块（如 `2`）；而 `NextAppRoot.kt:89` 仅向 `ForumPane` 传入 `fid = appState.activeForumFid`。**结果**：左栏高亮切到 `今日新帖`，但中栏 `ForumPane` 根本收不到任何参数变化，永远停在旧版块。
  2. **窄屏标签行 → 宽屏左栏反向断路**：即使 `E1` 在 `< 600dp` 标签行自行约定哨兵值（如 `fid = 0`）调用 `onSelectForum(0)`，`NextAppHostViewModel.selectForum(0)`（`:96-106`）也会将 `selectedSidebarEntry` 硬编码设为 `ShellSidebarEntry.Forum(0)` 而非 `ShellSidebarEntry.TodayNew`；同理 `selectDestination(ShellDestination.Forum)`（`:77`）把非空 `Int` 传给 `mapDestinationToSidebarEntry`（`ShellNavigationMode.kt:225` 约定 `activeForumFid == null` 才映射 `TodayNew`），导致展开至 `≥ 600dp` 时左栏 `今日新帖` 无法高亮，违反 `product-design.md` §3.2 L70 与 §4.3。
- **后果**：按 `BATCH-5.md` L111–118，Wave 2 的 `E1` 必须实现固定首项 `今日新帖` 且**严禁修改 `feature/app/*`**（与同波 `E2a`/`E15` 隔离）。若不在 `E0` 打通，Wave 2 `E1` 必然被迫越界改动 `NextAppHostViewModel.kt` / `NextAppRoot.kt`。
- **修复建议**：在 `ReadingNavContracts.kt` 定义 `const val FORUM_FID_TODAY_NEW: Int = 0`；在 `NextAppHostViewModel.kt` 中：① `selectSidebarEntry(ShellSidebarEntry.TodayNew)` 置 `hasUserSelectedForum = true` 且 `activeForumFid = FORUM_FID_TODAY_NEW`；② `selectForum(fid)` 按 `fid == FORUM_FID_TODAY_NEW` 映射 `ShellSidebarEntry.TodayNew`；③ `selectDestination` 传入 `cur.activeForumFid.takeIf { it != FORUM_FID_TODAY_NEW }`（`E0` 的 `ForumViewModel` 对 `fid <= 0` 暂回退首关注版块，待 `E1` 接 `SearchRepository.getNewPosts`）。同时 `forumListStateFor(0)` 天然为 `今日新帖` 提供独立滚动位缓存。

---

### B2. 【交互死锁】`SingleHostPaneSnackbarController.show` 携带非空 `actionLabel` 时触发 Material 3 默认 `SnackbarDuration.Indefinite` 永久占锁

- **文件与行号**：`hipda/src/main/java/net/jejer/hipda/feature/common/ReadingNavContracts.kt:71-75`
- **触发路径**：下游页面调用 `LocalPaneSnackbarController.current.show(message = "已收藏", target = DetailPane, actionLabel = "撤销", onAction = { ... })` 时，`SingleHostPaneSnackbarController.show` 执行 `hostState.showSnackbar(message = message, actionLabel = actionLabel)`，未传 `duration` 且 `withDismissAction = false`。经 `javap` 反编译 `androidx.compose.material3.SnackbarHostState.showSnackbar$default` 核实：当 `actionLabel != null` 且省略 `duration` 时，Material 3 默认令 `duration = SnackbarDuration.Indefinite`；随即 `HiSnackbarHost.kt:64-75` 中 `toTimeoutMillis` 返回 `Long.MAX_VALUE` 且无关闭图标按钮。
- **后果**：只要用户触发一次带按钮的 Snackbar（如收藏/关注/删除/非末页回复）而未手点该动作按钮，该条 Snackbar 将**永驻屏幕不消失**，且永久持有 `SnackbarHostState` 内部的 `Mutex`，导致此后全应用所有 Snackbar（如 `内容已复制`、`已退出登录`）全部永久挂起死锁。
- **修复建议**：在 `ReadingNavContracts.kt:73` 显式传入 `duration = SnackbarDuration.Short`（含动作按钮时的无障碍推荐停留时长已由 `HiSnackbarHost.toTimeoutMillis` 自动接管）。

---

### B3. 【测试防伪造漏洞 + 生产测试钩子】`src/main` 含仅测试调用的方法/参数，且 `NextAppTracerBulletTest` 存在 4 处绕过真实路径的假阳性漏洞

- **文件与行号**：
  - 生产测试钩子：`hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt:25,58`、`BasicMultiPaneContainer.kt:39,96`、`NextAppRoot.kt:41`
  - 测试漏洞：`hipda/src/test/java/net/jejer/hipda/feature/app/NextAppTracerBulletTest.kt:136-255,309-376,462-466,537-543,806-815`
- **具体触发与变异反例（改坏生产代码测试仍全绿）**：
  1. **生产代码残留只为测试存在的钩子与覆盖入参（违反 `E0.md` L30）**：
     - `ScopedThreadViewModelStoreRegistry.hasStore`（`:25`）与 `NextAppHostViewModel.hasThreadStore`（`:58`）在 `src/main` 与 `src/debug` 零调用，仅服务单测；
     - `NextAppRoot`（`:41`）与 `BasicMultiPaneContainer`（`:39`）暴露的 `threadViewModelFactory: ViewModelProvider.Factory? = null` 在 `NextAppActivity` 恒为 `null`；单测传入 `detailVmFactory` 后在 `BasicMultiPaneContainer.kt:96` 直接命中 `if (overrideFactory != null) return overrideFactory` 短路返回，导致 `:97-114` 真正的 `findHiltComponentActivity()` + `HiltViewModelFactory` / `parentOwner` 默认工厂逻辑**在全套单测中 0 次执行**。
  2. **漏洞 ① — 彻底删除 `movableContentOf` 与 `SaveableStateHolder` 改回 `if/else` 重建，全套测试仍 100% 通过（未闭合 R-BATCH5 B4-E0）**：
     - 原因：`forumListState`、`detailListState` 与 `ViewModelStore` 均挂在 `NextAppHostViewModel` 上，且 `ForumViewModel.bindForum` / `ThreadDetailViewModel.bindArgs` 幂等早退；`NextAppTracerBulletTest.kt:136-255` 未断言任何面板内部组合态（`remember` 实例同一性 / `DisposableEffect` 零销毁计数 / `rememberSaveable` 状态）。若将 `BasicMultiPaneContainer.kt:48-70` 退化为 `if/else` 分支各自直接调用 `currentForumContent`/`currentDetailContent`，全部 6 个测试依然绿灯，但后续 `E1`/`E2c`/`E4` 的面板局部状态（展开错误详情、展开引用、跳页输入）会在折叠/展开时静默丢失。
  3. **漏洞 ② — `RotationHarnessActivity` 完全绕过 Compose 容器与 Hilt 测旋转**：
     - `NextAppTracerBulletTest.kt:806-815` 的 `RotationHarnessActivity` 既非 `@AndroidEntryPoint`，其 `setContent` 也只渲染 `Text("RotationHarness")`，根本未挂载 `NextAppRoot` / `BasicMultiPaneContainer` / `ThreadDetailPane`，而是在 `:327-370` 用命令式 Kotlin 手调 `hostBefore.viewModelStoreOwnerForThread(...)`。若 `BasicMultiPaneContainer` 漏传 `LocalViewModelStoreOwner` 或 `rememberThreadViewModelProviderFactory` 在旋转重建后抛异常，该测试依然全绿。
  4. **漏洞 ③ & ④ — 主题测试自建旁路 `HiTheme`、玻璃拓扑测试错查节点**：
     - `:537-543` 在 `NextAppRoot` 之外另包了一层 `HiTheme(...)` 采集 `observedDark`/`observedPureBlack`/`observedFontStep`——即便把 `NextAppRoot.kt:67` 改坏为硬编码默认 `HiTheme()` 完全忽略 `settingsFlow`，测试仍全绿；
     - `:462-466` 断言 `ContentContainer` 无 `LayerBackdrop`，但 `HiAdaptiveShell.kt:298-307,435-440` 的默认回退源本来就挂在内层 `ShellDestinationHost` 上，且 `waitForIdle()` 后已被 `ForumPane` 的 `sourceClaim` 卸下——即便删掉 `NextAppRoot.kt:78` 的 `managesGlassSource = { it == ShellDestination.Forum }`，测试依然全绿。
- **修复建议**：
  1. 删去 `hasStore`/`hasThreadStore`（单测已有 `vm.addCloseable` 验证 `clear()`）及生产签名上的 `threadViewModelFactory`（非 Hilt 单测在外部用 `CompositionLocalProvider(LocalViewModelStoreOwner provides fakeActivityOwnerWithFactory)` 注入即可直接走通 `BasicMultiPaneContainer.kt:99-106` 真实委托分支）；
  2. 在 `411dp → 749dp → 411dp` 测试中验证面板子树 `DisposableEffect` `onDispose` 次数 `== 0`（或展开 `HiErrorState` `查看详情` 的 `rememberSaveable` 状态跨单/三栏及单栏进退保留），确保改回 `if/else` 立即变红；
  3. 让 `RotationHarnessActivity` 真实挂载 `NextAppRoot`/`BasicMultiPaneContainer` + `ThreadDetailPane` 断言旋转前后子树拿到同一 `ThreadDetailViewModel`（并对 `findHiltComponentActivity` + `HiltViewModelFactory` 路径补测）；
  4. 删去 `:537-543` 旁路 `HiTheme`，直接从 `NextAppRoot` 内部子树（如正文 `TextLayoutResult.layoutInput.style.fontSize == 18.sp` 或 `loginOverlayRenderer` 插槽内读取 `HiTheme.state`）断言主题生效；在论坛首屏 `isLoading = true, items = emptyList()`（`LazyColumn` 未挂载）下断言 `ContentContainer` 整棵子树 0 个 `LayerBackdrop`，锁死 `managesGlassSource`。

---

## 二、生产代码规模与逐文件精简审计（回应负责人重点 1）

`hipda/src/main/` 共新增 17 个文件、**1,256 行**（未超卡片 `850 × 1.5 = 1,275` 硬上限，且未创建 `ThreadInteractSlots`/`ThreadBottomBarSlot`/`PostImageBlock` 空壳），但对照 `600–850` 行估算逐文件审视，约有 **50–65 行**属于可删的死代码、测试专用入口或超前预留：

| 文件（`hipda/src/main/`） | 行数 | 审计结论：是否有重复代码 / 提前抽象 / 卡外功能 / 可删项 |
|---|---:|---|
| `di/AppShellBindingModule.kt` | 16 | 无冗余，`@BindsOptionalOf` 必需。 |
| `di/ImageLoaderModule.kt` | 40 | 无冗余，复用 `@Singleton OkHttpClient` + API 28+ GIF + `500MB` 缓存，零 `runBlocking`。 |
| `feature/common/LoginOverlaySlot.kt` | 14 | 无冗余，`Login` / `AccountMenu` 双模式契约精准匹配 `E15` + S1。 |
| `feature/common/LocalHiImageLoader.kt` | 16 | 无冗余。 |
| `feature/common/HiThemeSettingsAdapter.kt` | 43 | 无冗余，`fontStep (0..4) -> STEP_1..5` 与预设色回退准确。 |
| `feature/common/ReadingNavContracts.kt` | 79 | 契约均属卡片必做（需按 B1 加 1 行 `FORUM_FID_TODAY_NEW`、按 B2 修 `duration`）。 |
| `feature/common/UiError.kt` | 35 | 无冗余。 |
| `feature/common/UiErrorMapper.kt` | 104 | **可精简 ~18 行**：① `:51-60` `mapSilentReloginFailure` 在生产中 0 调用（静默重登失败已由 `ForumHttpClient` 包装进 `AuthRequiredException(RELOGIN_FAILED)`）；② `map(ThreadListError)` 与 `map(ThreadDetailError)` 的 `AuthRequired`/`ChallengeRequired`/`NetworkError` 分支重复可合并小辅助函数。 |
| `feature/app/NextAppState.kt` | 22 | **可删 1 行**：`:18` `val isLoginOverlayVisible` 全仓（含测试）0 引用。 |
| `feature/app/NextAppHostViewModel.kt` | 128 | **可精简 ~10 行**：`:25` `hasStore` 与 `:58` `hasThreadStore` 仅测试调用（B3）；`ScopedThreadViewModelStoreRegistry` 仅本类私有持有，无需公开独立顶层类。 |
| `feature/app/BasicMultiPaneContainer.kt` | 114 | **可精简 ~6 行**：移除仅测试使用的 `threadViewModelFactory` 覆盖参数与 `:96` 短路分支（B3）。 |
| `feature/app/NextAppRoot.kt` | 135 | **可删 2 行**：移除 `threadViewModelFactory` 透传参数（B3）。 |
| `feature/forum/ForumViewModel.kt` | 109 | 结构克制；遵守 `R-D4 S1` 显式传 `s.isSortByPostTime(fid)`（`:46`）。 |
| `feature/forum/ForumPane.kt` | 133 | 最薄真实列表实现，全部复用 M3 / 设计系统标准组件，无手写 `Layout`/`Modifier.Node`。 |
| `feature/threaddetail/ThreadDetailViewModel.kt` | 94 | 最薄单页加载 + 黑名单响应式流实现，无提前做连续分页或位置记忆。 |
| `feature/threaddetail/ThreadDetailPane.kt` | 139 | 最薄纯文本楼层提取与上下翻页，已锁死 `key = { "floor:${it.postId}" }`（`:82`）。 |
| `res/values/strings_common.xml` | 35 | **可删 4–6 行**：`:8-10,17` 共 4 条资源（`common_action_cancel`/`close`/`logout`、`common_error_login_to_operate`）在 `E0` 0 引用（Lint 亦报 `UnusedResources`，且 `取消`/`关闭` 与 `strings_challenge`/`strings_feedback` 重复）。 |

---

## 三、负责人追加 3 条要求逐项核实（回应负责人重点 2）

1. **按 `tid` 托管的 owner 显式提供 `HiltViewModelFactory`，旋转后还是同一个 VM 实例**：
   - **生产代码：已落实**。`ThreadScopedViewModelStoreOwner`（`NextAppHostViewModel.kt:14-20`）同时实现 `ViewModelStoreOwner` 与 `HasDefaultViewModelProviderFactory`；`BasicMultiPaneContainer.kt:54-65,94-114` 在 Hilt `ComponentActivity`（`GeneratedComponentManagerHolder`）下显式构建 `HiltViewModelFactory(activity, delegateFactory)` 并透传当前 Activity 的 `defaultViewModelCreationExtras`；`NextAppHostViewModel` 内仅保存 `tid -> ViewModelStore`（零 `Activity`/`Factory` 引用）。经 `javap` 核查 `dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$2` 与 `SavedStateHandleSupport`：首次创建 `@HiltViewModel` 时 `SavedStateHandle` 所需四项 `CreationExtras` 键齐全，旋转重建新 Activity 后 `ViewModelStore` 命中缓存直接返回原 `ThreadDetailViewModel` 实例且不泄漏旧 Activity。
   - **测试缺口**：见 `B3` 漏洞 ①/②（单测因 `overrideFactory` 短路与空壳 `RotationHarnessActivity` 未覆盖真实 Compose + Hilt 路径）。
2. **单栏返回列表时，滚动位靠 `SaveableStateHolder` 或宿主保存的 `LazyListState` 保住，0 额外请求**：
   - **生产代码：已双层落实**。`BasicMultiPaneContainer.kt:48-51` 以 `saveableStateHolder.SaveableStateProvider("forum_pane")` 包裹 `forumMovable`，同时 `NextAppRoot.kt:95` 传入 `hostViewModel.forumListStateFor(fid)`；返回列表时 `ForumViewModel` 存活且 `initializedQueries` 已记录该 `ThreadListQuery`，网络请求增量为 `0`。`NextAppTracerBulletTest.kt:223-255` 已验证单栏进帖（`ForumPane` 离树）再点返回后 `firstVisibleItemIndex`/`firstVisibleItemScrollOffset` 不变、请求增量 `== 0`、已关帖 `ViewModelStore` 立即 `clear()`（进程回收恢复衔接见 `S4`）。
3. **`NextAppHostViewModel` 不持有业务 Repository**：
   - **100% 落实**。实查 `NextAppHostViewModel.kt:1-128`：构造函数为无参 `@Inject constructor()`，import 与成员字段中零 `Repository` / `Gateway` / `Dao` 依赖。

---

## 四、下游 Wave 2 / Wave 3 原位加厚接口核验（回应负责人重点 4）

| 下游卡 | 对接契约与签名核验结果 | 是否需改 `feature/app/*` |
|---|---|---|
| **`E1` 加厚 `ForumPane`** | `ForumChromeSpec(showForumTabs, useGlassTopBar, externalGlassState)`、`ShellContentScope.shellGlassSource()`、`NextAppRoot` 预配 `managesGlassSource = { it == ShellDestination.Forum }` 及 `onNewThreadClick`/`onManageForumsClick` 均已就位；**唯独缺 `TodayNew` 路由打通（见阻断 `B1`）**。 | **修完 `B1` 后无需改 `feature/app/*`** |
| **`E2a` 加厚 `ThreadDetailPane`** | `ThreadNavArgs`、`DetailChromeSpec(showBack, fullScreenState, suppressBottomGlass)`、`ImageViewerLaunchArgs`、`ReplyRequest`、`onOpenUserProfile` 与 `items(key = { "floor:${it.postId}" })` 全部精确就位（`E2a` 可在自身独占的 `ThreadDetailViewModel`/`ThreadDetailPane` 内注入 `AuthRepository` 监听登录恢复及补充带默认值的站内帖链接回调）。 | **无需改 `feature/app/*`** |
| **`E15` 接 `LoginOverlayRenderer`** | `LoginOverlaySlot.kt`（`LoginOverlayMode.Login` / `AccountMenu`）+ `AppShellBindingModule` `@BindsOptionalOf` + `NextAppActivity` `Optional<LoginOverlayRenderer>` 完整闭环，`E15` 在自身 `di/LoginUiModule.kt` `@Binds` 即可自动生效（建议顺手补 `S1` 窄屏 `Mine` 已登录入口）。 | **无需改 `feature/app/*`** |
| **`E3` 接 `PaneSnackbarController`** | `PaneSnackbarTarget { DetailPane, ContentArea }` 与 `LocalPaneSnackbarController` 签名完整，`E3`（本身独占 `feature/app/*`）升级为多栏分区对齐宿主时**零碰 `feature/common/*`、`feature/forum/*`、`feature/threaddetail/*`**；需先在 `E0` 修好 `SingleHostPaneSnackbarController` 的 `duration` 死锁（见阻断 `B2`）。 | **`E3` 本身归属 `feature/app/*`，零碰公共契约与业务面板** |

---

## 五、其余专项审查核实与非阻断建议（Suggestions）

### 5.1 已通过的专项检查
1. **边界、零越界与快照零漂移**：`git diff --name-status e71c3fb...1149518` 共 26 个文件，严格落在 `E0.md` 授权集合内；`ui/challenge/`、`HiStateViews.kt`、`HiNextApplication.kt`、`HiApplication.java`、主 `AndroidManifest.xml`、`data/`、`service/`、`ui/shell/`、`core-parser/`、`MotionTokenScanTest.kt` 与 `hipda/src/test/snapshots/` 全部 **0 改动**；`PreviewActivity.kt:246-257`「进入新版」按钮严格位于 `L240` `if (onOpenShellDemo != null)` 分支内；`src/main/` 零引用 `src/debug/`、动效白名单零增长。
2. **`UiErrorMapper` 与 `CopyTerminologyScanTest`**：`UiErrorMapper` 全部分支主提示均取自 `copy.md` 字符串资源，技术细节仅入 `details`；`CopyTerminologyScanTest` 覆盖 §8 全部 30 个禁止词组、楼层正则 `(?:第\s*)?(?:\d+|%\d*\$?[ds])\s*层`（不误报 `楼层`/`层级`/`图层`）、异常类名与 HTTP 状态码模板，排除旧 `strings.xml`，并对 `strings_preference.xml` 2 处历史遗留实施过期即报错的只减不增白名单。

### 5.2 非阻断改进建议（`S1`–`S5`）
- **S1 — 为 `< 600dp` 窄屏 `Mine` 占位页补齐已登录 `AccountMenu` 触发按钮（`NextAppRoot.kt:118-123`）**：当前 `account != null` 时 `Mine` 占位页 `action = null`，窄屏无左侧栏头像，导致 Wave 2 真机包在手机窄屏下登录后无入口点出 `E15` 的临时 `退出登录` 菜单；建议 `account != null` 时提供 `HiStateAction(stringResource(R.string.shell_sidebar_account)) { openLoginOrAccount(LoginOverlayMode.AccountMenu) }`。
- **S2 — 按第 2 节表格顺手清理 `src/main` 约 30–40 行无引用代码**：删去 `NextAppState.isLoginOverlayVisible`、`UiErrorMapper.mapSilentReloginFailure` 及 `strings_common.xml` 中 4 条 0 引用字符串。
- **S3 — 慢网切版块防闪现 `暂无帖子` 与详情页登录恢复提示（供 `E1`/`E2a` 收口）**：`ForumViewModel.kt:75` 用串行 `activeQuery.collect { refresh(q) }`，首版块慢网挂起期间切版块会使新版块在 `refresh` 启动前处于 `isLoading = false && items.isEmpty()` 而短暂闪现 `暂无帖子`，`E1` 宜改为按 query 并发/切换取消并以 `fetchedPage == 0` 守卫骨架态；`E2a` 宜在 `ThreadDetailViewModel` 订阅 `AuthRepository.currentAccount` 使 `AuthRequired` 登录后自动重拉。
- **S4 — `LazyListState` 进程回收恢复衔接（供 `E3` 收口）**：`NextAppHostViewModel` 内存 Map 持有的 `LazyListState` 可跨旋转保活但不经 `Saver` 落 Bundle，`E3` 做 `StateRestorationTester` 验收时宜统一迁入按 `fid`/`tid` 键控的 `SaveableStateHolder` + `rememberLazyListState()`；另 `E3` 改 `ui/shell/` 时可将 `sidebarState` 的合并未读数接入 `HiNavigationPill` 并在窄屏单栏二级页退场胶囊（§3.1 L38）。
- **S5 — 加固 `CopyTerminologyScanTest` 标签与资源目录覆盖（`CopyTerminologyScanTest.kt:129-169`）**：当前正则未扫描 `<plurals><item>`、单引号 `name='...'`、`<![CDATA[...]]>` 及 `res/values-*` 限定符目录下的 `strings_*.xml`，建议补齐 `<plurals>` 与 `values*` 目录匹配防止绕过。


---

# 二轮复审报告（Round 2：修复轮 1 @ `fed1070`）

- **复审结论**：**`APPROVE`**
- **复审对象**：`origin/card/E0-reading-tracer-bullet` @ `fed1070b44f582d1c8b56131368c5d4478306810`（简称 `fed1070`，父提交 `9e7bca5` 已无冲突合入 `origin/main` @ `cbcb9c0`；纯修复差异为 `git diff 9e7bca5..fed1070`，共 12 个文件 `+879 / -529`）
- **GitHub Actions CI 核实**：[Android CI #45666086377](https://github.com/mailwmj/hipda-next/actions/runs/45666086377)
  - `headSha`：`fed1070b44f582d1c8b56131368c5d4478306810`（精确匹配）
  - `Build, Lint & Unit Tests`（Job `139388494284`）：**SUCCESS**（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true`、APK 包名校验全绿；`hipda/src/test/snapshots/` 0 改动）
- **阻断项**：**0 项**（一轮 `B1`–`B3` 及 `S1`、`S2`、`S5` 全部核实闭合）
- **非阻断建议项（移交下游 `E1`/`E3` 收口）**：**3 项**（`R2-S1`–`R2-S3`）

---

## 一、一轮审查项闭合核验与负责人三项重点审查

### 1. 重点 1：`B1` 的 `ForumListSource` 能否让 `E1` 零改 `feature/app/*` 直连 `今日新帖`（含 `searchId` 与黑名单）
- **结论：完全可以，`E1` 无需改动 `feature/app/*` 或 `feature/common/*` 任一行代码。**
- **路由与状态对称性核实**：
  - `ReadingNavContracts.kt:14-17` 定义 `@Immutable sealed interface ForumListSource { data object TodayNew; data class Forum(val fid: Int) }`，天然具备值相等性，可直接作为 `NextAppHostViewModel.forumListStates` 的独立滚动缓存键（`NextAppHostViewModel.kt:28,42`）；
  - 宽屏左栏点 `今日新帖` → `selectSidebarEntry(TodayNew)`（`:64-74`）置 `hasUserSelectedForum = true` 并更新 `activeForumSource = TodayNew`；窄屏标签行点芯片 → `selectForumSource(source)`（`:76-82`）双向同步 `selectedSidebarEntry = ShellSidebarEntry.TodayNew` / `Forum(fid)`；底部胶囊切走再回 `论坛` → `selectDestination`（`:57-62`）将 `(activeForumSource as? Forum)?.fid`（`TodayNew` 时为 `null`）传给 `mapDestinationToSidebarEntry`，精确落回 `ShellSidebarEntry.TodayNew`；
  - `NextAppRoot.kt:88-99` 已将 `source`、`onSelectSource` 与按 `source` 隔离的 `listState` 完整传入 `ForumPane`。
- **`searchId` 翻页与黑名单/已读响应式过滤核实**：
  - `ForumViewModel.kt:52,63,123-138`（属 `E1` 独占包 `feature/forum/`）已直接注入 `cbcb9c0` 合入的 `SearchRepository`：首页调 `getNewPosts(page = 1, searchId = null)` 保存 `res.searchId`，加载更多调 `getNewPosts(page = page + 1, searchId = todayNewState.value.searchId)` 并按 `tid` 去重追加；
  - `todayNewItems`（`:63`）经 `threadListRepository.observeFiltered(...)` 统一接入 `DefaultThreadListRepository.kt:252-265` 的响应式管线，自动组合 `BlacklistRepository.blockedUsernames`（隐藏拉黑作者）与 `BrowseHistoryDao.observeVisitedTids()`（已读变灰），零额外请求（另见 `R2-S1` 账号切换清空旧 `todayNewState` 细节，由 `E1` 在本文件内一并收口）。

### 2. 重点 2：`B3` 三项变异自查独立核对 + 构造未列出的新错误实现检验测试捕获力
- **作者所列三项变异逐行核对（确认全部真变红）**：
  1. **变异 1（删 `movableContentOf` 改回 `if/else`）**：`NextAppTracerBulletTest.kt:250-350` 在真实 `BasicMultiPaneContainer` 的 `forumContent`/`detailContent` 内放置 `remember` 令牌、`DisposableEffect` 销毁计数与 `rememberSaveable` 计数器。改回 `if/else` 后 `411dp → 749dp` 分支切换必然触发 `else` 槽位销毁，`:324` `assertEquals(0, detailDisposedCount)` 与 `:325` `assertSame(detailTokenAt411, latestDetailToken)` 确定性变红；若删去 `SaveableStateHolder`，`:326/:340` `assertEquals(7, forumSaveableCounterValue)` 亦确定性变红。
  2. **变异 2（删 `NextAppRoot.kt:76` 的 `managesGlassSource`）**：`ForumPane.kt:91-97` 已将 `Modifier.shellGlassSource()` 收进 `LazyColumn` 实际入树的 `else` 分支；`NextAppTracerBulletTest.kt:713-734` 用 `CompletableDeferred` 卡住首屏使 `ForumPane` 停在骨架态（`sourceClaim.isClaimed == false`），删去 `managesGlassSource` 后 `HiAdaptiveShell.kt:435` `useFallback` 立即为 `true` 挂载 `LayerBackdropElement`，被 `:730` `countLayerBackdrops == 0`（含 `:838` `visited > 5` 与 `:741` 加载后 `== 1` 双向自证）确定性捕获变红。
  3. **变异 3（旋转时重建 `ViewModelStore`）**：`RotationHarnessActivity`（`:1089-1106`）已在 `setContent` 中真实挂载 `NextAppRoot` → `BasicMultiPaneContainer` → `ThreadDetailPane`，并通过 `createdDetailVms` 记录工厂实际创建次数；若 `viewModelStoreOwnerForThread` 每次新建 `ViewModelStore`，`configurationChange` 重建 Activity 后 `:623` `assertEquals(1, harness.createdDetailVms.size)` 立即变红（`2 != 1`）。
- **额外构造的未列出错误实现（测试盲区检验，生产代码本身正确，不构成阻断）**：
  - **反例 A（删去 `BasicMultiPaneContainer.kt:58` 的 `key(args.tid)`）**：在三栏下从 `tid=1001` 切到 `tid=1002` 时，因内层 `scopedOwner = remember(hostViewModel, args.tid, ...)` 自身带 `args.tid` 键，`ViewModelStore` 仍会切换，故 `:392` `threePaneSwitchTidAndClearDetail` 依然全绿（而 `:293/:295` 测试端自己写了 `remember(args.tid)` 且未测切 `tid`）；但面板内任何未按 `tid` 显式加键的 `remember` / `rememberSaveable` 状态（如 `HiErrorState` 展开详情、`E2c` 展开引用、`E4` 内联跳页输入）会发生跨帖串状态。建议 `E3` 在多栏迁移测试中补一条“切 `tid` 时面板内无键 `rememberSaveable` 必重置”断言。
  - **反例 B（打开帖子后跨版块切换漏调 `updateReleasingStaleThread`）**：当前生产代码 `NextAppHostViewModel.kt:67,78` 正确调用了 `updateReleasingStaleThread` 清空 `selectedThread` 并 `clear()` 旧帖 Store，但 `:429` `forumAndTodayNewNavigation` 切换版块前未先打开帖子，若把 `:67/:78` 误改为 `_state.update` 测试不会变红（建议 `E3` `MultiPaneBackStackTest` 覆盖“开帖态切版块自动清空右栏并释放旧 Store”）。
  - **反例 C（`BasicMultiPaneContainer.kt:99` Hilt 包装分支本身）**：`RotationHarnessActivity` 非 `@AndroidEntryPoint`，测试中 `findHiltComponentActivity()` 返回 `null` 走 `delegateFactory`（`:601-602` 仅单测了 `findHiltComponentActivity` 解包函数本身），真 Hilt 路径将在 M5 模拟器/真机冒烟中覆盖。

### 3. 重点 3：修复本身是否引入新问题 & 生产代码 `1,273` 行评估
- **生产代码行数构成**：`src/main` 共 `1,273` 行（较一轮 `1,256` 行净增 `+17` 行，在 `1,275` 行上限内）。增长完全来自 `ForumViewModel.kt`（`109 -> 145`，`+36` 行）与 `ForumPane.kt`（`133 -> 143`，`+10` 行）直接实装了 `SearchRepository.getNewPosts` 今日新帖拉取与窄屏首枚芯片，同时清理了 `UiErrorMapper`/`NextAppHostViewModel`/`strings_common.xml` 约 `38` 行冗余代码，无水分或测试钩子回流。
- **修复引入的边缘问题排查（均在 `ForumViewModel.kt` 内，无崩溃/无跨模块影响，交 `E1` 原位收口）**：
  1. **退登/换号时未同步清空 `todayNewState`（`ForumViewModel.kt:98`）**：`uid != lastUid` 时仅置 `todayNewInitialized = false`，未重置 `todayNewState.value = TodayNewState()`。若已登录拉过 `今日新帖` 后退出登录，重刷返回 `SearchError.AuthRequired` 时 `todayNewState.rawThreads` 仍保留上一账号条目，导致 `ForumPane.kt:87`（`uiState.error != null && uiState.items.isEmpty()`）不显示 `需要登录才能查看` 而残留旧列表。`E0` 尚无退登入口（`E15` 在 Wave 2），交 `E1` 在闭合 `B4-E1 ①/③` 时加一行 `todayNewState.value = TodayNewState()` 即可。
  2. **`TodayNew` 翻页 `searchId` 保底（`ForumViewModel.kt:130`）**：`searchId = res.searchId` 建议在 `E1` 写为 `if (append) (res.searchId ?: cur.searchId) else res.searchId`，防止极端模板中间页未重复解析出 `searchId` 时第 3 页退回空 `searchId`。

---

## 二、移交下游卡的非阻断建议汇总（供派发 Wave 2 / Wave 3 时带入卡片）

- **R2-S1（带入 Wave 2 `E1`）**：在 `ForumViewModel.kt:98` 账号 `uid` 变化分支同步执行 `todayNewState.value = TodayNewState()` 清空旧账号今日新帖内存态，并在 `:130` 翻页合并时保留 `res.searchId ?: cur.searchId`；同时一并收口一轮 `S3`（慢网并发/取消与 `fetchedPage == 0` 骨架守卫）。
- **R2-S2（带入 Wave 2 `E2a`）**：在 `ThreadDetailViewModel` 订阅 `AuthRepository.currentAccount`，使处于 `AuthRequired` 的详情页在 `E15` 登录成功后自动重拉恢复（对齐 §7.11）。
- **R2-S3（带入 Wave 3 `E3`）**：在 `MultiPaneStateMigrationTest` / `MultiPaneBackStackTest` 补齐“三栏切 `tid` 时面板内无键 `rememberSaveable` 状态必隔离重置”与“开帖态切左栏版块自动清空右栏并 `clear()` 旧帖 Store”两条断言，并收口一轮 `S4`（`LazyListState` 进程回收 `Saver` 恢复与窄屏单栏二级页退场胶囊）。
