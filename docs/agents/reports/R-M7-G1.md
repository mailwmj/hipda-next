# 独立代码审查报告 R-M7-G1

- **审查对象**：`origin/card/M7-G1-list-topbar-profile` @ `e4690ea1b81a133559fe13210ce3afc76ecfbfe2`（基线 `origin/main` @ `9338529070a9df6c5feeed5bb281ffba411abf35`；含功能提交 `10e7f35` 与三栏测试适配提交 `e4690ea`）
- **CI 核验**：[Android CI #45775081734](https://github.com/mailwmj/hipda-next/actions/runs/45775081734) — `headSha == e4690ea1b81a133559fe13210ce3afc76ecfbfe2`，`status = completed`，`conclusion = success`（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true` 及 APK 产物校验全绿）
- **审查结论**：**`APPROVE`**（0 阻断项，3 条非阻断建议）

---

## 一、通用门禁与卡片边界核查

| 检查维度 | 结果 | 核查证据 |
|---|---|---|
| 主目录只读纪律 | **通过** | `/home/mir/Documents/hipda-next` 零写入、零 checkout、零本地编译（当前与最新 `origin/main@afa3783` 一致，工作区干净） |
| 文件边界硬约束 | **通过** | `git diff --name-status 9338529...e4690ea` 确认 **0 改动** `feature/threaddetail/*`、`feature/messages/*`、`feature/settings/*`、`feature/login/*`、`feature/imageviewer/*`、`:core-parser`、各 `build.gradle.kts`、`libs.versions.toml`、`AndroidManifest.xml`、`.github/`。唯一 `feature/app/` 生产改动为 `NextAppHostViewModel.kt`，严格对应 `M7-G1.md:77`「负责人裁定」授权的 `openThread` 末页最小接线 |
| 生产代码体量与标准组件 | **通过** | `hipda/src/main/` 共 `+734 / -94` 行，**净增 `+640` 行**，低于卡片 1.5× 警戒线（`720` 行）。全量采用 Compose 标准组件（`Box`/`Row`/`Column`/`IconButton`/`DropdownMenu`/`combinedClickable`/`AsyncImage`），0 手写 `Layout`/`Modifier.Node`/状态机，0 测试专用生产钩子/语义键 |
| 动效与术语扫描 | **通过** | 未触碰 `MotionTokenScanTest` 与 `CopyTerminologyScanTest` 白名单；所有动画规格均取自 `HiMotion` 令牌（`effectsDefault`、`spatialDefault`、`hiFadeIn`/`hiFadeOut`） |
| 安全白名单（图片/链接） | **通过** | 资料页大头像 URL 在 `UserProfileViewModel.kt:90` 与 `UserProfileScreen.kt:180` 双重经 `ProfileUrlSanitizer.sanitizeProfileImageUrl` → `SafeLinkPolicy.sanitizeHttpUrl` 校验（仅放行合法 `http`/`https`，拦截 `file:`/`javascript:`/`intent:`/`content:`/`data:`，见 `UserProfileScreenTest.kt:77,105`） |
| 截图零漂移与目视复核 | **通过** | 既有全部 Roborazzi 快照 **0 修改、0 漂移**；仅新增 `forum_bs_filter_w411_light.png` 与 `forum_bs_filter_w411_dark.png` 2 张，已逐张目视确认（详见第六节） |

---

## 二、4 项功能与补充核查项（1）–（4）逐项深审

### 1. `#1` Buy & Sell（`fid=6`）分类筛选与旧版逐项对照【补充 (1)】

| 对照维度 | 旧版实现（`HiUtils.java` / `ThreadListFragment.java` / `ThreadListJob.java`） | 新版实现（`ForumTopBar.kt` / `ForumViewModel.kt` / `ForumPane.kt` / `DefaultThreadListRepository.kt`） | 核查结论 |
|---|---|---|---|
| 入口可见性与图标 | `menu_thread_list.xml:6-9` + `ThreadListFragment.java:223-231`：仅 `mForumId == 6` 显示 `@string/action_filter_by_type`（`分类`），使用 `FontAwesome` 图标 | `ForumTopBar.kt:168,253-280`：仅 `source == ForumListSource.Forum(6)`（`BUY_AND_SELL_FID = 6`）渲染 `48×48dp` `IconButton`（`contentDescription = "分类"`），使用纯矢量 `FilterListVectorIcon`（`L74-82`），**0 FontAwesome**；选中非「全部」时以 `36dp` `primaryContainer` 圆底高亮 + 菜单 `Check` 图标 + `semantics { stateDescription = activeCategoryLabel }` 提示 | **完全对齐且合规** |
| 9 项分类列表与 `typeid` | `HiUtils.java:181-182`：`["全部":"", "手机":"1", "掌上电脑":"2", "笔记本电脑":"3", "无线产品":"4", "数码相机、摄像机":"5", "MP3随身听":"6", "各类配件":"7", "其他好玩的":"8"]` | `ForumTopBar.kt:62-72` + `strings_forum.xml:11-19`：9 项顺序、文案（含顿号与无空格 `MP3随身听`）及 `typeId`（`""`, `"1"`..`"8"`）逐字 100% 一致 | **逐字一致** |
| 默认项与会话记忆 | 默认 `""`（下标 0「全部」）；旧版写 `SharedPreferences`（`HiSettingsHelper.java:956-965`） | 默认 `buyAndSellTypeId = null`（`ForumViewModel.kt:81`，在 `ForumTopBar.kt:231-234` 映射至 `""`「全部」）；会话内跨版块往返（`6 → 2 → 6`）保留记忆，冷启动回到默认「全部」（符合卡片 `ForumViewModel` 落点与不改 `AppSettings` 边界，且避免跨重启遗忘筛选漏看帖子） | **合理且符合卡规** |
| 切换后刷新与回顶 | `ThreadListFragment.java:446-454`：选中不同分类时更新 `BSTypeId`、`mPage = 1`、`scrollToTop()` 并派发 `ThreadListJob`；点已选项不重复刷新 | `ForumPane.kt:98-107`：仅当 `normalized != latestSelectedTypeId` 时调 `viewModel.selectTypeId(normalized)`、`topBarScrollState.expand()` 与 `listState.scrollToItem(0)`；`ForumViewModel.kt:178-191` 取消在途旧刷新、清理新帖计数并以 `ThreadListQuery(fid = 6, sortByPostTime = ..., typeId = normalized)` 触发 `executeForumRefresh`；选「全部」恢复 `typeId = null` | **完全对齐** |
| 下拉刷新与翻页携带 `typeid`、跨版块隔离 | `ThreadListJob.java:94-96`：首页与 `mPage++` 均拼接 `&filter=type&typeid=<id>`，仅 `mForumId == 6` 拼接 | `ForumViewModel.kt:204,223,288-299`：`refresh()` 与 `loadMore()` 均统一调 `forumQuery(src.fid)` 传入当前 `bsTypeId`；`DefaultThreadListRepository.kt:133-190,562-579` 按完整 `ThreadListQuery` 分桶缓存并在 `startPage = fetchedPage + 1` 时拼接 `forumdisplay.php?fid=6&page=N&filter=type&typeid=X`；非 `fid=6` 版块与 `TodayNew` 经 `effectiveTypeId`/`forumQuery` 强制置 `null` | **完全闭环** |

---

### 2. `#3` 顶栏滚顶原语：透明/玻璃顶栏点击隔离、无障碍与减动效瞬移【补充 (2)】

1. **统一收敛与详情页可复用性**：
   - 全仓唯一实现位于 `ui/designsystem/topbar/HiTopBarScrollToTop.kt:55-188`（`Modifier.hiTopBarTapToScrollTop`、`LazyListState.hiScrollToTop`、`ScrollState.hiScrollToTop`、`rememberHiScrollToTopAction` 双重载、`HiSolidTopBar`），`feature/threaddetail/*` 与 `feature/settings/*` 保持 0 改动。
   - 核查即将接入的 `ThreadDetailTopBar.kt:93-123`（`HiGlassSurface(style = HiGlassStyle.TopBar)` 内含 `Row`：左圆钮 + 中间 `Column(Modifier.weight(1f))` + 右圆钮）：`M7-G3` 只需在中间标题 `Column` 挂 `.fillMaxHeight().hiTopBarTapToScrollTop(onScrollToTop)` 即可直接复用，0 玻璃节点新增且 `indication = null` 不会在透明/液态玻璃顶栏上画出矩形水波纹伪影。
2. **透明/玻璃顶栏与各列表顶栏不抢占按钮/输入框/Tab 点击**：
   - **论坛透明/玻璃顶栏（`ForumTopBar.kt:169-192, 238-324`）**：`hiTopBarTapToScrollTop` 仅挂载于上行 `Row` 内 `Modifier.weight(1f).fillMaxHeight()` 标题空白 `Box`（`L244-252`），右侧 `分类`、`发新帖`、`更多` 三个 `IconButton` 与下方 `BalancedForumTabRow` 均为独立兄弟节点，互不重叠。当上行随下滑完全折叠（`translationY = -56.dp`）时，外层 `Box(Modifier.fillMaxWidth().clipToBounds())`（`L173`）同步裁切绘制与指针命中域，已移出上边界的上行不会拦截吸顶标签行的任何点击；展开或侧栏常驻态点击标题空白区会先调 `topBarScrollState.expand()` 再滚回列表首项（`ForumPane.kt:96`）。
   - **纯色顶栏（`HiSolidTopBar` / `MineSolidTopBar` / `UserProfileScreen`）**：仅挂在中间 `weight(1f)` 标题 `Box`（`HiTopBarScrollToTop.kt:167-181`），左侧 `返回` 按钮、右侧 `清空` 等 `trailingAction` 及底部 `MineSegmentedTabs`（`收藏 | 关注`、`主题 | 回复`）完全独立消费点击。
   - **搜索页顶栏（`SearchPane.kt:83-100`）**：挂在顶栏外层 `Column`。Compose `Main` 指针阶段自底向上派发，`返回`、`TextField`、`清空输入`、`FilterChip`（`版块`/`标题`/`作者`）及展开面板内的输入框与按钮均先消费事件，仅点击非控件空白区（如筛选胶囊行右侧空白）才会冒泡触发回顶（`SearchPaneTest.kt:572-588` 已实测验证）。
3. **无障碍可用性**：
   - `HiTopBarScrollToTop.kt:61-68` 使用标准 `Modifier.clickable(..., enabled = true, onClickLabel = stringResource(R.string.common_action_scroll_to_top), onClick = onScrollToTop)`，向无障碍树暴露 `SemanticsActions.OnClick`（动作标签 `"回到顶部"`，`HiSolidTopBar` 同时保留标题 `heading()` 语义）。在 `SearchPane` 中，内部 `IconButton`、`TextField`、`FilterChip` 自身均具 `mergeDescendants = true`，在合并语义树中保持独立可聚焦节点不被父 `Column` 吞并（`SearchPaneTest` 在默认合并树下全部查询与交互正常通过）。
4. **减动效下瞬移（0ms 单帧到位）**：
   - `HiTopBarScrollToTop.kt:72-81`：当 `HiTheme.isReducedMotion == true`（含系统 `animator_duration_scale == 0f`）时直接执行 `scrollToItem(0)` / `scrollTo(0)`，常规模式下执行 `animateScrollToItem(0)` / `animateScrollTo(0, HiMotion.spatialDefault())`。`HiTopBarScrollToTopTest`、`ForumTopBarScrollTest`、`MinePagesBehaviorTest`、`UserProfileScreenTest` 均在 `withAnimatorDurationScale(0f)` + `mainClock.autoAdvance = false` 下推进单帧断言回到 `(0, 0)`。

---

### 3. `#4` 6 类列表长按直达末页及与已有长按菜单/操作无冲突【补充 (3)】

1. **端到端契约与路由闭环**：
   - `ReadingNavContracts.kt:27`：`ThreadNavArgs` 新增 `val openLastPage: Boolean = false`（保持全仓既有调用点向后兼容，且 `NextAppStateSaver` 保存解析落盘后的 `targetPid`，无需持久化瞬态标志）。
   - `NextAppHostViewModel.kt:241-280, 373, 416-438`：`openThread(args)` 在 `args.openLastPage == true` 时委托共享私有方法 `openThreadLastPage(args, clearOverlay = false)`，复用 E16 `threadDetailRepository.loadLastPage` 解析末页最后一条 `postId` 写入 `targetPid` 并将 `openLastPage` 复位为 `false` 后提交 `selectedThread`；网络失败回退至 `args.copy(targetPid = null, openLastPage = false)` 打开帖子展示重试态；在途被新开帖/切页打断时通过 `deepLinkResolveGen` 取消防串台（`OpenThreadLastPageRoutingTest.kt:46-134` 全覆盖）。
2. **与已有长按菜单及行内按钮冲突核查**：
   - 全量检索 `feature/{forum,mine,search,profile}/` 全部 `combinedClickable`、`DropdownMenu` 与手势修饰符确认：
     - 版块帖子/今日新帖（`ThreadListRow.kt:77`）、搜索结果（`SearchPane.kt:129`，不影响 `SearchInitialContent` 最近搜索行）、我的主题/我的回复（`MyPostsPane.kt:73`）、浏览历史（`BrowseHistoryPane.kt:86`）、TA 的帖子（`UserProfileScreen.kt:326`）此前均仅有单击 `.clickable`，**不存在任何既有长按菜单**；现统一升级为 `combinedClickable` + `HapticFeedbackType.LongPress` + `onLongClickLabel = "转到最新回复"`，浏览历史行尾 `×`（删除并支持撤销）作为子 `IconButton` 独立消费点击，与行长按零冲突。
     - 收藏与关注（`FavoritesPane.kt:87-121`）：此前仅有单击与行尾 `×` 按钮、无长按菜单；现按旧版 `SimpleListFragment.java:337-372` 在长按时弹出轻量 `DropdownMenu`，提供 **`转到最新回复`**（`openLastPage = true`）与 **`取消收藏` / `取消关注`**（调用同一 `vm?.removeItem`，完整保留统一撤销令牌与连续删除仅撤回最新项语义，且行尾 `×` 按钮原样保留）。
     - 其他模块的长按菜单（帖子详情楼层 `FloorContextMenu`、短消息列表清空菜单、看图页长按保存/分享菜单）均位于独立组件，零交集、零冲突。

---

### 4. `#8` 头像缓存按 key 精确失效仅影响目标用户【补充 (4)】

- **URL 唯一性**：HiPDA（UCenter）用户头像路径按 9 位补零 `uid` 唯一派生（如 `.../uc_server/data/avatar/000/01/00/00_avatar_middle.jpg`，见 `UserParserTest.kt:24`），不同用户 `safeAvatar` URL 严格互异。
- **按 key 精确失效（`UserProfileScreen.kt:98-107`）**：
  - **内存缓存**：`memCache.keys.filter { it.key == avatarUrl }.forEach { memCache.remove(it) }` + `memCache.remove(MemoryCache.Key(avatarUrl))`。不做全表 `clear()`、不做前缀模糊匹配，仅精确匹配 `it.key == avatarUrl`，既清除资料页 `64dp` 裸键条目，也一并清除同一用户在列表/楼层按尺寸生成的带 `extras`（如 `coil#size`）内存条目，而其他用户的 `MemoryCache.Key` 100% 保留。
  - **磁盘缓存**：切至 `withContext(Dispatchers.IO)` 调用 `imageLoader.diskCache?.remove(avatarUrl)`（非 `clear()`），仅移除该 URL 对应的单条磁盘快照，无主线程磁盘 IO，且全应用 `OkHttpClient`（`HttpClientModule.kt:45-55`）未挂独立 HTTP 磁盘缓存，不存在底层 HTTP 缓存返旧图问题。
  - **确定性重载与无闪烁**：`key(avatarReloadNonce)` 绕过 Coil 3 `DefaultModelEqualityDelegate` 对 `extras`/`CachePolicy` 的去重，配合 `CachePolicy.WRITE_ONLY` 强制拉新覆写缓存，并以 `placeholder = lastAvatarPainter` 避免重载期间闪回首字母底色；`safeAvatar == null` 时零清缓存、零发请求并提示 `用户未设置头像`。
  - **测试实证**：`UserProfileScreenTest.kt:318-454` 同时预置目标用户裸键 + 带尺寸 `extras` 键 + 无关用户 `otherUrl` 内存与磁盘哨兵，实测断言点击后目标键全清、`otherUrl` 内存与磁盘缓存完好无损、连续两次点击均真实发起 `WRITE_ONLY` 请求。

---

## 三、受影响既有测试改动核查【补充 (5)】

- **唯一既有测试改动**：`hipda/src/test/java/net/jejer/hipda/feature/app/MultiPaneDividerGestureTest.kt:127-129`（提交 `e4690ea`）。
- **因果核实**：在 `w749dp-h559dp` 三栏横屏下，中栏 `ForumTopBar` 为 `56dp` 浮空半透明玻璃顶栏（与底层 `LazyColumn` 为兄弟节点）。该测试第 2 步在分隔线附近向上滑动列表后，`"测试帖子标题 1005"` 已滚入 `0..56dp` 顶栏后方被遮挡；`M7-G1` `#3` 落地后，顶栏标题空白区具备 `hiTopBarTapToScrollTop` 点击消费语义，不再将落在顶栏区域内的测试点击穿透给后方被遮挡行。将该步点击目标改为顶栏下方完全可见的 `"测试帖子标题 1008"` 属于 **`#3` 预期可见交互变化的直接后果**，第 1–4 步全部手势、几何与返回栈断言 **0 删减、0 放宽**，符合 `_common.md` 第 12 条规定。

---

## 四、`merge-tree` 合并冲突与语义兼容性核查【补充 (6)】

1. **与最新 `origin/main`（`afa3783`，已合入 `M7-G4` 与 `E5`）**：
   - 执行 `git merge-tree --write-tree origin/main e4690ea1b81a133559fe13210ce3afc76ecfbfe2` → **退出码 `0`**（tree `75723f1b507f930ab5bda028bf8db0a419a77bcf`，`9338529...afa3783` 与本卡修改集零文件交集、**零冲突**）。
2. **与 `origin/card/M7-G2-system-account-viewer-about`（`5317579`）**：
   - 两分支唯一共同修改文件为 `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt`。
   - 执行 `git merge-tree --write-tree origin/card/M7-G2-system-account-viewer-about e4690ea1b81a133559fe13210ce3afc76ecfbfe2` → **退出码 `0`**（tree `9a8c6a08c5bd94d83e4b337da1ddd9a5efd59e9f`，**零文本冲突**）。
   - **合并产物逐行语义核查**：`M7-G2` 改动集中于文件前部（构造器注入 `NotificationScheduler` 与 `ReleaseNotesPromptStore`、`init` 冷启调度与更新记录状态、`dismissReleaseNotesPrompt`），并完整保留了双参兼容构造器 `constructor(settingsRepository: SettingsRepository, threadDetailRepository: ThreadDetailRepository)`（合并后 `L83-86`）；`M7-G1` 改动集中于文件中后部（合并后 `L325-365` `openThread`/`openThreadLastPage` 及 `resolveThreadTargetAsync`），且 `M7-G1` 新增测试 `OpenThreadLastPageRoutingTest` 调用的正是该双参构造器。因此无论 `G1` 与 `G2` 谁先合入 `main`，**文本与编译/运行语义均 100% 零冲突兼容**。

---

## 五、新增 Roborazzi 截图目视检查记录

已通过 `read_file` 逐张目视核验新增 2 张快照：
1. **`forum_bs_filter_w411_light.png`**（`411×891` 浅色，`fid=6` Buy & Sell，`initialTypeId = "1"` 筛选激活态）：顶栏左起 `"Buy & Sell"` 标题清晰无截断，右侧依次排列 `分类`（`36dp` `primaryContainer` 浅蓝圆底 + 深色三横线漏斗图标，视觉选中态醒目）、`发新帖`、`更多` 三个动作按钮，间距均衡无挤压；下方版块标签行 `"Buy & Sell"` 荧光笔高亮、帖子卡片徽标与底部液态玻璃导航胶囊渲染正常。
2. **`forum_bs_filter_w411_dark.png`**（`411×891` 深色，`fid=6` Buy & Sell，`initialTypeId = null` 默认未筛选态）：顶栏 `"Buy & Sell"` 与右侧默认态 `分类`、`发新帖`、`更多` 图标对比度充足、对齐端正，深色未读/已读标题层次分明，底部深色玻璃胶囊干净无异常。

---

## 六、阻断项

无。

---

## 七、非阻断建议

1. **`S1`（文案总表归档同步：`docs/design/copy.md`）**：本卡为严守文件边界并避让 `M7-G2` 对 `docs/design/copy.md` 的并发修改，将卡面指定的旧版对齐词条直接落入了 `strings_common.xml`（`回到顶部`、`转到最新回复`）、`strings_forum.xml`（`分类` 及 9 项 `BS_TYPES`）与 `strings_profile.xml`（`头像`、`刷新头像`、`头像已经刷新`、`用户未设置头像`）。建议在合入 `main` 归档时顺带补录至 `docs/design/copy.md`。
2. **`S2`（多栏同帖已打开态长按跳末页与 `R-E16 S2` 一并收口：`ThreadDetailPane.kt:400` / `ThreadDetailViewModel.kt:281`）**：多栏下若右栏已挂载帖子 `T`（例如正停留在第 1 页），此时在中栏列表长按同一帖 `T` 触发 `openLastPage = true`，`NextAppHostViewModel` 会解析出末页 `lastPid` 并更新 `selectedThread`，但因既有已登记的 `R-E16 S2`（`ThreadDetailPane` 仅 `LaunchedEffect(args.tid)` 且 `bindArgs` 在 `boundTid == args.tid` 时早退），已挂载同帖暂不会响应新 `targetPid` 跳楼。建议在 `E5` 合入后处理 `R-E16 S2` 时一并覆盖同帖末页跳转。
3. **`S3`（顶栏未滚动时无障碍动作提示微调与交付报告笔误备忘：`HiTopBarScrollToTop.kt:55-68`、`_reports/M7-G1.md:27,106`）**：列表已在顶部 `(0, 0)` 时点标题为空操作，但语义树仍暴露 `"回到顶部"` `onClickLabel`，后续打磨可按 `enabled = isScrolled`（论坛叠加 `|| collapseOffsetPx > 0f`）按需启用；另备忘 `_reports/M7-G1.md` 将 `ForumTopBar.kt:267` 的 `primaryContainer` 圆底 + `stateDescription` 笔误写成了 `secondaryContainer + 短标`，且 `forum_bs_filter_w411_dark.png` 实际录制的是 `initialTypeId = null` 默认未筛选态（浅色覆盖选中态、深色覆盖默认态，代码与截图均符合卡规）。
