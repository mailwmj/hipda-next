# 独立代码审查报告：R-M7-G3

- **审查结论**：**APPROVE**
- **审查对象**：`origin/card/M7-G3-floor-actions-detail-top` @ `e57c7f81db59cb6b3a22bcd2bf1b6b432cda2483`（合并基线 `f2fbe6064ee55d55a28bac838b6cd3d7a918a71e`，含 `E5` @ `afa3783`、`M7-G1` @ `83484fd`、`M7-G4` @ `fd414a1`、`M7-G2` @ `f2fbe60`）
- **GitHub Actions CI**：[Android CI #45786387227](https://github.com/mailwmj/hipda-next/actions/runs/45786387227) — `headSha == e57c7f81db59cb6b3a22bcd2bf1b6b432cda2483`，状态 `completed / success`（已通过 `assembleDebug`、`assembleRelease`、`lintDebug`、`Verify Room schema consistency`、`:core-parser:test`、`:hipda:testDebugUnitTest -Proborazzi.test.verify=true`、`Verify APK outputs`）
- **阻断项**：**无（0 项）**
- **只读核验**：主目录 `/home/mir/Documents/hipda-next` 与实现 worktree `/home/mir/Documents/hipda-next.wt/M7-G3` 保持零改动（`git status -sb` 干净）。

---

## 一、规模、文件边界与架构纪律核查

1. **生产代码净增**：共修改/新增 9 个生产文件，`+539 / -12`（**净增 `+527` 行**），低于卡片 `1.5×` 警戒线 `630` 行（且在原卡 #5/#6/#7/G1 顶栏滚顶之外一并完成了卡末追加的 `R-E16 S2`、`R-M7-G1 S2` 与 `M7-G2 #9` 图集 Provider 接线）。
2. **文件边界与零越界**：
   - 仅改动 `feature/threaddetail/{PostFloorCard,ThreadDetailPane,ThreadDetailTopBar,ThreadDetailViewModel}.kt`、`feature/threaddetail/interact/{FloorContextMenu,FloorShareHelper,FloorTextSelectionDialog,FloorWarningDialog}.kt`、`res/values/strings_thread_detail.xml` 及对应测试/授权快照。
   - **0 改动** `feature/compose/*`、`feature/forum/*`、`feature/mine/*`、`feature/search/*`、`feature/profile/*`、`feature/messages/*`、`feature/settings/*`、`feature/app/*`、G1/G2/G4 生产文件及共享构建/清单/`MotionTokenScanTest` 白名单；未碰 `FloorContextMenuOverlay` 的 `onEdit`/`onDelete`（留给 `E6b`）。
   - **零破坏 `E5` 快速回复面板**：`ThreadDetailPane.kt:488-493, 717-775, 994-1016` 中 `QuickReplySheet`、底部工具条玻璃互斥与列表避让逻辑 100% 保持 `E5` 原样。
3. **标准组件与纯净性**：全量采用 Compose 标准组件（`Box`/`Column`/`Row`/`Surface`/`SelectionContainer`/`TextButton`/`FilledTonalButton`/`HiLoadingSpinner`），**0** 手写 `Layout`/`Modifier.Node`/自定义状态机，**0** 测试专用语义键/钩子/`CompositionLocal`。

---

## 二、逐项规格与“撤回即红”单测审计

| 检查项 | 生产落点（`file:line`） | 单测与反空心核验 | 结论 |
|---|---|---|---|
| **#5 楼层长按「文字选择」** | `FloorContextMenu.kt:88,150`；`FloorTextSelectionDialog.kt:34-103`；`ThreadDetailPane.kt:483-484,974,1019-1022` | `FloorActionsAndDetailWiringTest.kt:186-259`：默认长按唤出菜单且 `recordingToolbar.showMenuCalls == 0`；点「文字选择」后在 `PaneTitle == "第 5 楼 · 坛友乙"` 弹窗内通过真实鼠标拖选 + `Ctrl+C` 验证剪贴板收到楼层纯文本（去掉 `SelectionContainer` 剪贴板为空必红）；验证 `Back` 优先关弹窗且保留 `PositionStack`，及 ≥48dp `关闭` 按钮退出 | **PASS** |
| **#6 楼层长按「分享」** | `FloorContextMenu.kt:89,151`；`FloorShareHelper.kt:12-96`；`ThreadDetailPane.kt:975-988`；`strings_thread_detail.xml:27,32-33` | `FloorActionsAndDetailWiringTest.kt:262-375`：1 楼校验 `https://www.4d4y.com/forum/viewthread.php?tid=2602293` + `主题：...`；5 楼校验 `https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988` + `回复：...` + `ACTION_CHOOSER`/`ACTION_SEND` `text/plain`；断言不含 `"回帖"`；`http://` 升 `https://`；20 种危险 scheme/`rawUserInfo`/空白/换行/注入参数全部返回 `null`/`false` 且 `0` 次 `startActivity` | **PASS** |
| **#7 被警告楼层与警告弹窗** | `PostFloorCard.kt:65,146-154,185-205`；`FloorWarningDialog.kt:30-150`；`ThreadDetailViewModel.kt:676-684`；`ThreadDetailPane.kt:485-486,526,854,1023-1027` | `FloorActionsAndDetailWiringTest.kt:378-546`：遍历全 20 套主题预设 × 浅/深/OLED 校验 `errorContainer`/`onErrorContainer` WCAG ≥ 4.5:1；`isWarned == false` 无 `OnClick` 语义而 `isWarned == true` 具 `Role.Button`、≥48×48dp 热区与 `"3楼，查看警告记录"`；`MockWebServer` 模拟 GBK `viewwarning` HTML（去 `.float_close`）与 `<root>` CDATA 双重实体解码，校验标题/每行记录/`moreconf`、HTTP 500 `加载失败`+`重试` 恢复、`Back` 优先于 `PositionStack`、12 种危险 URL `server.requestCount == 0` | **PASS** |
| **G1 详情顶栏滚顶接线** | `ThreadDetailTopBar.kt:46,66,103-115`；`ThreadDetailPane.kt:70,527,890` | `FloorActionsAndDetailWiringTest.kt:549-652`：复用 `Modifier.hiTopBarTapToScrollTop` + `rememberHiScrollToTopAction(listState) { anchorController.clearLandingAnchor() }`；滚至 `(12, 40)` 后点 `返回`/`全屏阅读`/`只看楼主` 均不误滚顶，点标题回 `(0, 0)`；减动效 `withAnimatorDurationScale(0f)` 单帧 0ms 瞬移回 `(0, 0)`；`HiGlassMarker` 保持 `2`（0 新增玻璃） | **PASS** |
| **#9 `M7-G2` 图集 Provider 接线** | `ThreadDetailPane.kt:57-58,807-811` | `FloorActionsAndDetailWiringTest.kt:760-843`：不注入外层 `LocalThreadImageGallery`，真实 `ThreadDetailPane + ImageViewerRendererImpl` 加载 3 楼 6 图帖，点 2 楼第 3 张图断言 `initialIndex == 4`、展示 `5 / 6` 与 `第 2 楼 · 二楼摄影师`，左滑至 `6 / 6`、右滑跨楼至 `1 / 6` | **PASS** |
| **既有测试更新核查** | `FloorContextMenuTest.kt:134,153`；`ImageViewerShellMergeTest.kt:262,277,330-331,342` | `FloorContextMenuTest` 为卡片显式授权追加 `"文字选择"`/`"分享"`（保留全部 `48.dp`、触感与 0 `"举报"` 断言）；`ImageViewerShellMergeTest` 因 `ThreadDetailPane` 接通真实双图图集后第 2 张图页码由单图占位 `"1 / 1"` 合法变为 `"2 / 2"`，仅同步该 5 处页码文本，动态缩略图退场坐标与离屏 `0.85f` 淡出断言 0 放宽 | **PASS** |

---

## 三、负责人 6 项补充重点逐条核验

### (1) 同帖 `targetPid` 跳楼与「已打开帖长按直达末页」：单一滚动权威、`PositionStack` 与 E4 位置恢复门闩
- **只经 `PrependAnchorController`**：
  - `ThreadDetailPane.kt:472-474` 以 `LaunchedEffect(args.tid, args.targetPid, args.openLastPage)` 调用 `viewModel.bindArgs(args) { resolveViewportAnchor(latestUiState, listState, fallbackToFirst = true) }`。
  - 同帖 `targetPid` 变更（`ThreadDetailViewModel.kt:293-302` → `jumpToFloor` `L634-661`）与同帖 `openLastPage` 变更（`L303-308` → `jumpToLastPage` `L664-673`，以及多栏下经 `NextAppHostViewModel.openThreadLastPage` 解析末楼 `targetPid` 后汇入 `jumpToFloor`）均**只写入 `PendingScrollTarget(..., smooth = false)`**，绝不直接操作 `LazyListState`；统一由 `ThreadDetailPane.kt:806` `SideEffect { anchorController.syncBeforeMeasure(...) }` 在 `LazyColumn` 首帧测量前同步 `requestScrollToItem` 并布防落定窗口锚点。
- **跳前先压 `PositionStack`**：
  - 本页分支（`ThreadDetailViewModel.kt:639-642`）与跨页分支（`L659`、`L671`）均在更新滚动状态或发起加载前调用 `pushCurrentAnchorForJump(fromAnchor)` 压栈并置 `canPopPositionStack = true`；旋转重组时 `boundTargetPid`/`boundOpenLastPage` 去重不重复跳楼或污染栈（`L293-313`）。
- **与 E4 位置恢复门闩（`ReadingPositionController`）零冲突**：
  - 切新帖时先 `flushIfHydrated()` 再 `beginSession(args.tid)` 锁死门闩（`hydrated = false`）；若首轮 `bindJob` 恢复尚未完成时触发同帖跳楼/跳末页，`bindJob?.cancel()`（`L296, L306`）与 `++sessionGeneration`（`L356, L1016`）立即作废在途恢复；
  - 跨页跳楼/跳末页加载期间 `isLoading == true`，落位前 `pendingScroll != null`，`ThreadDetailPane.kt:637`（`if (s.isLoading || s.pendingScroll != null || s.floors.isEmpty()) return@snapshotFlow null`）全程屏蔽中间帧锚点上报，绝不会将首屏 `(page=1, offset=0)` 误写入 Room。

### (2) 楼层分享链接：规范 `https`、`SafeLinkPolicy` 与零敏感参数泄露
- `FloorShareHelper.kt:15-45`：
  - 输入 `forumServer` 与拼装后的完整 URL **双层经过 `SafeLinkPolicy.sanitizeHttpUrl(...)`**（拒绝 `javascript:`/`intent:`/`file:`/`content:`/`data:`/`mailto:`、空白/控制/零宽字符、空 host 与 `rawUserInfo` `user@` 混淆）；
  - `parsed.query != null || parsed.fragment != null` 直接拒绝带参 `forumServer`；非回环 `http:80` 强制规范化为 `https://`（`L24-26`）；
  - `tid` 与 `pid` 均经 `isPositiveNumericId`（纯 ASCII 数字且 `> 0`）严格校验；1 楼固定 `viewthread.php?tid={tid}`，非 1 楼固定 `redirect.php?goto=findpost&ptid={tid}&pid={pid}`，正文摘要经 `extractFloorPlainText` 仅取可见纯文本——**零 `uid`、`formhash`、`sid`、`authorid` 等参数泄露**，且零违禁词 `"回帖"`。

### (3) 警告弹窗请求：仅 `MockWebServer`
- 生产侧 `FloorWarningDialog.kt:42-95` 先经 `buildSafeWarningUrl`（`isPositiveNumericId(tid/uid)` + `SafeLinkPolicy.sanitizeHttpUrl`）构造 `misc.php?action=viewwarning&tid={tid}&uid={uid}`，再通过注入的 `SiteAccessGateway.execute` + `decodeBody`（GBK/UTF-8）拉取，无任何硬编码真站域名。
- 测试侧 `FloorActionsAndDetailWiringTest.kt:389-546` 全部连接本地 `MockWebServer()`（覆盖 GBK 200 正常解析、HTTP 500 失败重试、12 种恶意地址 `0` 请求断言）；全量测试 diff 检索 `4d4y` 仅作为纯函数/链接路由的内存字符串输入，**0 次真实站点网络连接**。

### (4) 文字选择与长按菜单、链接点击、图片点击手势无冲突
- 默认阅读态下 `PostFloorCard.kt` 与 `PostBodyRenderer.kt` **完全不包裹 `SelectionContainer`**（`PostBodyRenderer.kt` 0 改动），彻底避免 Compose `SelectionContainer` 长按选词手势抢占 `PostFloorCard` 的 `detectTapGestures(onLongPress = ...)`（仅监听长按，不消费单击）或干扰 `LazyColumn` 竖滑。
- 正文 `LinkAnnotation.Url` 单击（`FloorActionsAndDetailWiringTest.kt:695` 实测 `performTouchInput { click(...) }`）、`PostImageBlock` 单击（`L819` 实测点击看图）、`WarnedFloorBadge` 单击（`L453`）与楼层长按菜单（`L217`）在同一 `ThreadDetailPane` 内互不干扰。
- `SelectionContainer` 仅在用户点击菜单「文字选择」后于独立模态层 `FloorTextSelectionDialog.kt:97` 内按需挂载，外层 `Surface` 带 `.pointerInput(Unit) {}` 阻断穿透底幕，`BackHandler` 随打开态挂载于 LIFO 栈顶，优先于 `PositionStack` 关闭。

### (5) 图集初始索引端到端正确
- `ThreadDetailPane.kt:807-810` 通过 `LocalThreadImageGallery` 提供 `extractThreadGalleryItems(uiState.floors)`（自动跳过 `isAuthorBlocked` 楼层与非 `http`/`https` 图片，与 `PostFloorCard`/`PostImageBlock` 可点击图片集合严格一一对应）。
- `PostImageBlock.kt:314-319` 点击时调用 `buildGalleryLaunchArgs`（`ThreadImageGallery.kt:47-80`），优先按 `(floorPostId, clickedUrl)` 精确匹配跨楼同 URL 场景并算出全局 `initialIndex`，透传至 `NextAppRoot.kt:453-460`（绑定 `ImageViewerBoundsRegistry.createResolver` 实时追踪各页缩略图退场坐标）与 `ImageViewerOverlay.kt:91-92`（`rememberPagerState(initialPage = initialPage)`），端到端验证准确无误。

### (6) `git merge-tree` 冲突核验（`origin/main` 与 `origin/card/E6-full-editor`）
1. **与最新 `origin/main`（`5be4a4c`）**：
   `git merge-tree --write-tree --messages origin/main e57c7f81db59cb6b3a22bcd2bf1b6b432cda2483` → 退出码 `0`（tree `df53ae7b043538500433de727384bb8207f8aaa0`），**0 冲突**。
2. **与已推送 `origin/card/E6-full-editor`（`e3b3443`）**：
   - 直接双分支：`git merge-tree --write-tree --messages origin/card/E6-full-editor e57c7f81db59cb6b3a22bcd2bf1b6b432cda2483` → 退出码 `0`（tree `9dcd2d143e3aba9ee1f233193981f61f81666977`），`ThreadDetailPane.kt` 与 `NextAppRoot.kt` 自动干净合并，**0 冲突**；
   - 三方顺序合入模拟（先合 `origin/main + M7-G3` 得 `df53ae7b`，再合 `origin/card/E6-full-editor`）：`git merge-tree --write-tree --messages --merge-base=afa3783 df53ae7b... origin/card/E6-full-editor` → 退出码 `0`（tree `92405061beac1553382a9c1e0477553bd12a7773`），**0 文本冲突、0 语义冲突**（`E6` 在 `ThreadDetailPane.kt` 仅抽取 `handleReplySubmitted` 与监听 `LocalFullEditorReplyResults` 位于 L714–763，与 `M7-G3` 改动的 L472–528、L807–890、L974–1027 完全分离；测试与快照集合亦无交集）。

---

## 四、Roborazzi 截图目视复验

1. **非授权快照漂移检查**：`git diff --name-status f2fbe60...e57c7f81` 仅修改卡片明确授权的 2 张长按菜单快照，其余全仓快照（`thread_detail_*.png`、`thread_chrome_*.png`、`quick_reply_*.png`、`multipane_*.png` 等）在 CI `-Proborazzi.test.verify=true` 下 **0 漂移**。
2. **逐张目视结果**：
   - `thread_interact_menu_w411_light.png`（411dp 浅色，本人 1 楼 `第 1 楼 · linus_hi`）：8 项菜单（`引用回复` / `回复` / `只看此人` / `复制` / `文字选择` / `分享` + 分割线 + `编辑` / 红色 `删除`）完整呈现在视口内，行高 ≥48dp、左边距对齐一致、深色文字与红色危险项对比度清晰，无裁切或重叠，无“举报”。
   - `thread_interact_menu_w933_dark.png`（933dp 深色，他人 2 楼 `第 2 楼 · geek_reader`）：底部居中 520dp 面板展示 6 项常规菜单（末尾新增 `文字选择`、`分享`），深色表面与前景文字对比度良好、字迹锐利、无截断或错位，不含 `编辑`/`删除`/`举报`。

---

## 五、非阻断建议（供后续收口或归档参考）

- **S1（跨模块小优化，属 `feature/app` 边界外）**：`ThreadDetailViewModel.bindArgs`（`ThreadDetailViewModel.kt:293-313`）为保证屏幕旋转幂等，按 `(targetPid, openLastPage)` 去重。当用户通过正文原始 `<a href="...pid=X">` 链接或多栏长按「直达末页」跳楼后按 `Back` 回到原楼，若再次点击**同一条**原始 URL 链接（注：原生 `QuoteBlock`/`GoToFloor` 直接调 `viewModel.jumpToFloor` 不受影响）或末楼 `pid` 未变时再次长按同一帖，`NextAppState.selectedThread` 因 `ThreadNavArgs` 结构相等不会重新触发 `LaunchedEffect`。后续触碰 `ReadingNavContracts.kt` / `NextAppHostViewModel.kt` 时可考虑为一次性跳转参数附带递增 `navSeq` 或消费复位。
- **S2（纵深防御）**：`FloorShareHelper.kt:24-28` 已将非回环默认端口 `http://`（port 80）自动升级为 `https://`（覆盖现网 `http://www.4d4y.com` 等配置）；若未来希望连自定义非 80 端口的非回环 `http://host:8080` 也不出现在外部分享文本中，可在 `buildSafeFloorShareUrl` 出口收紧为非回环一律要求 `https://`。
- **S3（归档文案表同步）**：本卡严守并行文件边界未改 `docs/design/copy.md`；负责人在归档本卡时可将 `文字选择`、`分享`（楼层菜单）及 `警告记录` / `暂无警告记录` / `{N}楼，查看警告记录` 同步补入 `docs/design/copy.md`。
