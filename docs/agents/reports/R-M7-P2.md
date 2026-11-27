# 独立代码审查报告 R-M7-P2：链接、分享与零碎收尾

- **审查对象**：`origin/card/M7-P2-links-polish` @ `8d1737933e88081495470221b8c12b8c0402f3e4`（基线 `origin/main` @ `0f067be`）
- **任务卡 / 实现报告**：[M7-P2.md](file:///home/mir/Documents/hipda-next.wt/_cards/M7-P2.md) / [M7-P2.md](file:///home/mir/Documents/hipda-next.wt/_reports/M7-P2.md)
- **审查卡 / 通用清单**：[R-M7-P2.md](file:///home/mir/Documents/hipda-next.wt/_cards/R-M7-P2.md) / [_review_common.md](file:///home/mir/Documents/hipda-next.wt/_cards/_review_common.md)
- **GitHub Actions CI**：[Android CI #45950041483](https://github.com/mailwmj/hipda-next/actions/runs/45950041483)（`headSha == 8d1737933e88081495470221b8c12b8c0402f3e4`，`status = completed`，`conclusion = success`）
- **审查结论**：**APPROVE**（**0 阻断项**，2 条非阻断建议）

---

## 一、通用审查清单核验

1. **只读纪律核验**：全程未在主仓库 `/home/mir/Documents/hipda-next` 执行任何 `checkout`、文件写入、提交或编译操作，仅执行 `git fetch origin`、`git show`、`git diff`、`git merge-tree`、`gh run view`、`rg`。
2. **CI 完整性核验**：`gh run view 45950041483 --json headSha,status,conclusion,jobs` 确认 `headSha` 精确匹配 `8d1737933e88081495470221b8c12b8c0402f3e4`，Job `Build, Lint & Unit Tests`（`#140343529219`）全步骤成功通过：
   - `Build and lint`（`assembleDebug assembleRelease lintDebug`）：`success`
   - `Verify Room schema consistency`：`success`
   - `Run unit tests and verify Roborazzi screenshots`（`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true`）：`success`
   - `Verify APK outputs`：`success`
3. **范围、代码体量与通用红线核验**：
   - `git diff --name-status 0f067be...8d1737933e88081495470221b8c12b8c0402f3e4` 共改动 20 个文件（13 个生产文件、7 个测试文件）：生产代码 `+76 / -34`（净增 **+42 行**，显著低于卡片估算上限 `≤ 120` 行）。
   - 禁改与共享文件（`gradle/libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`.github/`、`MotionTokenScanTest` 白名单）：**0 改动**。
   - 生产代码无测试专用钩子、语义键或测试专用 `CompositionLocal`；全部 UI 状态计算使用标准 `derivedStateOf` / `rememberUpdatedState`，无手写 `Layout` / `Modifier.Node` / 命令队列。
   - 新增与修改测试全部使用内存假实现或本地 `ShadowPackageManager`，**0 真实站点（`4d4y.com`）网络访问、0 写操作**。
4. **Roborazzi 截图核验**：
   - 本卡涉及无障碍点击语义条件开关与详情下钻态动态 Snackbar 余量，无静态快照变更，`src/test/snapshots/` **0 改动**，CI `-Proborazzi.test.verify=true` 全量 **0 漂移**。

---

## 二、本卡六大审查重点逐项深审

### 1. a 项（安全）：工具条外链复用 `AndroidExternalLinkOpener` 与白名单防线
- **入口白名单先验守卫**：
  - [ThreadBottomToolbar.kt:113-121](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbar.kt#L113-L121) 中 `openExternalHttpUrl` 第一行仍强制执行 `val safeUrl = sanitizeExternalHttpUrl(rawUrl) ?: return false`，而 `sanitizeExternalHttpUrl`（`:91`）直接委托 [SafeLinkPolicy.kt:27-55](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/common/SafeLinkPolicy.kt#L27-L55) 的 `sanitizeHttpUrl`。
  - 注意安全分层细节：`AndroidExternalLinkOpener.open` 内部走的是允许合规纯邮箱的 `SafeLinkPolicy.sanitizeLinkUrl`（[PostLinkDispatcher.kt:33](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/routing/PostLinkDispatcher.kt#L33)），而工具条 `openExternalHttpUrl` 在调用 `opener.open(safeUrl)` **之前**先用更严格的 `sanitizeHttpUrl` 拦截了 `mailto:` 以及一切非 `http`/`https` scheme，即使注入自定义 `ExternalLinkOpener` 也绝不会被危险 URL 触发（由 [ThreadBottomToolbarTest.kt:514-530](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L514-L530) 锁定）。
- **注入复用与无第二套 opener**：
  - [PostLinkDispatcher.kt:118](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/routing/PostLinkDispatcher.kt#L118) 将 `DefaultPostLinkDispatcher.externalLinkOpener` 从 `private` 放宽为模块内 `internal`；
  - [ThreadDetailPane.kt:697,960-965](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L697) 经既有 `LocalPostLinkDispatcher.current` 取出宿主在 [NextAppRoot.kt:174-192](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L174-L192) 构建的同一 `ExternalLinkOpener` 实例传入，打开失败时在 `DetailPane` 弹出 `R.string.routing_error_no_browser`（`"未找到可用的浏览器"`）。
- **防自环与反例覆盖**：
  - 反例矩阵：结合 [ThreadBottomToolbarTest.kt:445-475,519-530](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L445-L475) 与既有 `SafeLinkAndRoutingSecurityTest`，完整覆盖 `javascript:` / `JAVASCRIPT:`（大小写绕过）、`intent:`、`file:`、`content:`、`data:`、`mailto:`、`tel:`、`ftp:`、`vbscript:`、`about:`、空白（前导/中间空格、`\n`）、控制字符（`\u0000`、零宽字符在 `SafeLinkPolicy` 层拦截）、空 host、`user@` / `user:pass@` `rawUserInfo` 混淆。
  - 防自环不变：`ShadowPackageManager` 仅本应用 `.ng.IntentActivity` 匹配 `https://www.4d4y.com/forum/viewthread.php` 时返回 `false` 且 `0` 次 `startActivity`（`:493-498`）；注册外部 Chrome 后显式指定 `chromeComponent`（`:501-510`）。
  - **既有测试变更审查**：[ThreadBottomToolbarTest.kt:419-437](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/chrome/ThreadBottomToolbarTest.kt#L419-L437) 因 Robolectric 默认加载 `AndroidManifest.xml` 中本应用的 `www.4d4y.com` 深链过滤器且无外部浏览器，旧实现未防自环故误放行，改走 `AndroidExternalLinkOpener` 后必须注册外部浏览器才允许拉起；测试在保留原有 `ACTION_VIEW` 断言基础上新增 `assertEquals(chromeComponent, launchedIntents.single().component)`，**确认为断言加强而非放宽**。

### 2. b 项 `navSeq`：旋转/进程重建、保存态兼容、多栏/铰链/`PositionStack` 与调用方盘点
- **调用方全量盘点（无漏传、无误增）**：
  - 全仓 `hipda/src/main/java/` 构造 `ThreadNavArgs` 的入口（`ForumPane` 单击与长按末页、`SearchPane`、`ForumLinkGuard`、`BrowseHistoryPane`、`FavoritesPane`、`MyPostsPane`、`UserProfileScreen`、`MessagesListScreen`、`SettingsScreens`、`FullEditorHost` 以及 `NextAppHostViewModel` 内部深链/末页解析）全部统一汇聚至唯一提交点 [NextAppHostViewModel.kt:328-334](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt#L328-L334) `commitOpenThread`，由 `args.copy(navSeq = ++threadNavSeq)` 唯一递增盖章，无任何外部调用方私自写 `navSeq` 或绕过 `commitOpenThread`。
- **旋转 / 折叠 / 展开幂等性**：
  - 配置变更时 `NextAppHostViewModel` 与 `threadStores` 内按 `tid` 托管的 `ThreadDetailViewModel` 均保活，`restoreSavedStateIfFresh` 因 `hasMutatedState == true` 直接早退；UI 重建触发 [ThreadDetailPane.kt:476](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt#L476) `LaunchedEffect(args.tid, args.targetPid, args.openLastPage, args.navSeq)` 重入 [ThreadDetailViewModel.kt:321-348](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L321-L348) 时，`args.navSeq == boundNavSeq` 使 `isNewNavEvent == false`，即使已按 `Back` 弹回 1 楼再旋转也绝不重复跳转。
- **保存态（`NextAppStateSaver`）兼容性与进程重建分析**：
  - `navSeq` 未写入 [NextAppState.kt:75-142](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt#L75-L142) `NextAppStateSaver`，严格保持既有 20 元列表槽位与旧 16 元保存态 100% 兼容。进程重建恢复出的 `ThreadNavArgs` 带默认 `navSeq = 0L`，后续新交互从 `++threadNavSeq = 1L` 起递增，`isNewNavEvent` 正常工作。
  - 细微边界观察（非阻断，见建议 S1）：基线既有的 `NextAppStateSaver` 第 14 槽持久化了 `t?.targetPid`；若用户在同帖点链接跳至 `pid=X` 后按 `Back` 回 1 楼，随后遭遇**系统杀进程并从 `Bundle` 重建全新 `ThreadDetailViewModel`（`boundTid == null`）**，因 `selectedThread.targetPid` 未被清空，`ThreadDetailViewModel.kt:386` 初始绑定分支会优先走 `loadByPostIdInternal(incomingPid)` 而非 `getReadingPosition(tid)`。该行为属基线 `NextAppStateSaver` 既有设计且不在旋转/折叠范畴，列为 S1 供后续可选优化。
- **多栏、铰链与 `PositionStack` 下重复点击行为**：
  - 在三栏与铰链双栏下，[MultiPaneReadingContainer.kt:97,153-184,334-354](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/MultiPaneReadingContainer.kt#L97) 的 `RightPaneSlot.Thread` 以 `"thread:${args.tid}"` 为 `AnimatedContent` 的 `contentKey` 且 `detailMovableFor(tid)` 仅按 `tid` 键控；同帖 `navSeq` 递增时不会卸载重挂 `ThreadDetailViewModel` 或 `LazyListState`，新 `args` 原地传入 `ThreadDetailPane` 触发 `bindArgs` → `jumpToFloor` / `jumpToLastPage`，跳前将当前锚点压入 `PositionStack`（若当前已停在目标楼层顶部 `(cleanPid, offset=0)` 则由 `ThreadDetailViewModel.kt:677` 防重不重复压栈）；同时在三栏中栏再次普通单击已打开帖子（`targetPid == null && !openLastPage`）会命中 `:342-346` `else` 分支仅更新 `boundNavSeq`，不重置右侧阅读进度。

### 3. c 项：`FloorShareHelper.buildSafeFloorShareUrl`
- [FloorShareHelper.kt:20-31](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/interact/FloorShareHelper.kt#L20-L31) 先经 `SafeLinkPolicy.sanitizeHttpUrl` 校验，且显式拒绝 `parsed.query != null || parsed.fragment != null`；`tid` 与 `postId` 均经 `isPositiveNumericId` 严格限制为正整数数字串，仅拼接固定模板 `viewthread.php?tid=$cleanTid` 或 `redirect.php?goto=findpost&ptid=$cleanTid&pid=$cleanPid`，**绝无可能带出 `formhash`、`sid` 等敏感参数**。
- 协议与端口处理：非回环（排除 `localhost`、`127.0.0.1`、`::1`）`http` 地址在 `preferHttps = true` 时一律升级为 `https`；当原端口为 `80` 时同步设为 `443`（使 OkHttp 省略默认端口输出 `https://www.4d4y.com/...`），自定义端口如 `http://example.com:8080` 则保留端口输出 `https://example.com:8080/...`；回环地址与 `FloorWarningDialog`（`preferHttps = false`）保持原行为不变。

### 4. d/e 项：顶栏滚顶语义与详情下钻 Snackbar 余量
- **d 项（顶栏语义）**：
  - [HiTopBarScrollToTop.kt:56-70](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/ui/designsystem/topbar/HiTopBarScrollToTop.kt#L56-L70) 在 `!enabled` 时直接返回原 `Modifier`，不挂载 `clickable` 与 `"回到顶部"` `onClickLabel`。
  - 全仓全部 4 处接入点均已对齐：`HiSolidTopBar`（`:171`，覆盖我的/历史/收藏/我的帖子/用户资料）、`ThreadDetailTopBar`（`:108`）、`SearchPane`（`:84`）均传 `enabled = isScrolled`；[ForumTopBar.kt:170-173,252](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumTopBar.kt#L170-L173) 以 `derivedStateOf { latestIsScrolled() || scrollState.collapseOffsetPx > 0f }` 控制启用，且 `ForumPane.kt:96` 点击回调会执行 `topBarScrollState.expand()`，折叠偏移 `> 0f` 时点击确有实际展开动作。
- **e 项（Snackbar 底部余量）**：
  - [NextAppRoot.kt:546-553](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppRoot.kt#L546-L553) 按 `appState.detailDrilldownStack.lastOrNull()` 精确判断当前详情区可见顶层：`UserProfile` 为 `false`（去掉 56dp 工具条余量），`SmsConversation` 为 `true`（保留避让 `SmsSolidInputBar`），栈空时 `selectedThread != null || selectedSmsConversation != null` 为 `true`（帖子页与根短消息对话均保留底部避让，且设置详情/空占位等无底栏页面仍为 `0.dp`）。全形态无回归，并由 [DetailDrilldownSnackbarClearanceTest.kt:220-394](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/feature/app/DetailDrilldownSnackbarClearanceTest.kt#L220-L394) 在单栏与三栏真实 `NextAppRoot` 上闭环实测。

### 5. f 项（`data/` 敏感区相邻）：`readingPositionMutex` 与 `NonCancellable`
- **无死锁核验**：[DefaultThreadDetailRepository.kt:52,207-250](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/data/threaddetail/DefaultThreadDetailRepository.kt#L52) 中 `readingPositionMutex.withLock` 临界区内仅调用纯 Room DAO 方法 `readingPositionDao.get(normalizedTid)`、`readingPositionDao.upsert(...)` 与无锁 `clock()`，不回调任何仓库方法、Flow 或控制器，不存在同锁重入或跨锁逆序等待，**无死锁风险**。
- **`NonCancellable` 包裹顺序正确**：`saveReadingPosition`（`:234-249`）采用外层 `withContext(NonCancellable)` 包裹内层 `readingPositionMutex.withLock`，保证即使调用协程在排队等锁期间被取消（如退帖销毁 `viewModelScope`），挂起的 `withLock` 也不会因外层取消而丢弃待落盘进度。
- **A-f 测试反演（改坏必红）**：[ThreadDetailRepositoryTest.kt:48-156](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt#L48-L156) 同时用双闸门 `CompletableDeferred` 与真实内存 Room 验证：若去掉任一侧 `withLock`，`:108`（持锁阻塞断言）、`:114`（写后首读新值）与 `:134`（`maxConcurrent == 1`）立即变红；若去掉 `withContext(NonCancellable)` 或将其移入 `withLock` 内部，`:132`（等锁期取消外部协程仍落盘 `"303"`）立即变红。

### 6. 与并行卡 `M7-P1` 的 `git merge-tree` 冲突与语义兼容性核查
- 执行 `git merge-tree --write-tree 8d1737933e88081495470221b8c12b8c0402f3e4 origin/card/M7-P1-image-polish`（`fc63c2da`）输出干净树 `e48924d675fe3ac11a8c65cfabe6456702b0a69a`（退出码 `0`）；与最新 `origin/main`（`72472e1c`）合并亦输出干净树 `8db0dead393bbdf3dd5a52c9d97e30c8126113bb`。
- 核查合并后 `ReadingNavContracts.kt` 全文及 `M7-P1` 全部 14 个改动文件：
  - `M7-P2` 仅在 `ThreadNavArgs` 末尾追加默认参数 `val navSeq: Long = 0L`；
  - `M7-P1` 仅新增 `ImageViewerPageMeta` 并扩展 `ImageViewerLaunchArgs`（末尾追加默认参数 `val pageMeta: List<ImageViewerPageMeta> = emptyList()` 及按页查询方法），未触碰 `NextAppRoot.kt` 或 `ThreadDetailPane.kt`；
  - 两卡改动的数据结构、运行时状态与测试文件完全正交，**0 文本冲突且 0 语义冲突**。

---

## 三、阻断项（Blocking Issues）

无。

---

## 四、非阻断建议（Suggestions）

1. **S1（进程被杀重建与一次性 `targetPid` 优先级，可选优化）**：基线既有 [NextAppState.kt:89,134](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/app/NextAppState.kt#L89) 在 `NextAppStateSaver` 第 14 槽保存了 `selectedThread?.targetPid`。旋转/折叠因 ViewModel 保活不受影响；但若用户同帖跳楼后按 `Back` 回原楼，随后遭遇系统后台杀进程并重建新 `ThreadDetailViewModel`（`boundTid == null`），[ThreadDetailViewModel.kt:386-387](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt#L386-L387) 会因恢复出的 `args.targetPid != null` 走 `loadByPostIdInternal` 而跳过 Room `getReadingPosition`。后续若需打磨进程死亡恢复体验，可在初始绑定分支当 `args.navSeq == 0L`（来自保存态恢复）且 Room 已有该帖阅读进度时优先恢复持久化阅读进度。
2. **S2（多栏/铰链容器层同帖重跳集成回归，可选加固）**：[SameThreadRepeatJumpNavSeqTest.kt:120](file:///home/mir/Documents/hipda-next.wt/M7-P2/hipda/src/test/java/net/jejer/hipda/feature/threaddetail/SameThreadRepeatJumpNavSeqTest.kt#L120) 直接挂载 `ThreadDetailPane` 配合 `NextAppHostViewModel` 验证了重跳与旋转幂等；后续若触碰多栏测试，可顺手在 `w933dp` 三栏 `NextAppRoot` 下补一条同帖重复跳楼断言以直接覆盖 `MultiPaneReadingContainer` 槽位透传链路。
