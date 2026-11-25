# R-BATCH5 计划审查报告：M5 阅读核心（8 卡 4 波草案）

- **审查结论**：**`CHANGES-REQUESTED`**
- **审查对象**：[BATCH-5.md](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md)（M5 阅读核心 E 线 8 张卡、4 个波次，OPS 起草）
- **对照基准**：`origin/main` @ `2a3f7ca`（已合入 `C4d @ 21b1e3c`、`D4 @ 243aee8`、`G2 @ 5ce63dc`、`D2 @ 0e5fb57` 等；在途对照 [D5.md](file:///home/mir/Documents/hipda-next.wt/_reports/D5.md)、[R-D5.md](file:///home/mir/Documents/hipda-next.wt/_reports/R-D5.md)、[D8a.md](file:///home/mir/Documents/hipda-next.wt/_cards/D8a.md)、[D9.md](file:///home/mir/Documents/hipda-next.wt/_cards/D9.md)、[C4d.md](file:///home/mir/Documents/hipda-next.wt/_reports/C4d.md)、[R-C4d.md](file:///home/mir/Documents/hipda-next.wt/_reports/R-C4d.md)）
- **审查标准**：`team-lead` skill [planning.md](file:///home/mir/.gemini/config/skills/team-lead/references/planning.md)、[card.md](file:///home/mir/.gemini/config/skills/team-lead/references/card.md)、[review.md](file:///home/mir/.gemini/config/skills/team-lead/references/review.md) 及仓库 [DISPATCH.md](file:///home/mir/Documents/hipda-next/docs/agents/DISPATCH.md)、[handoff.md](file:///home/mir/Documents/hipda-next/docs/agents/handoff.md)、[product-design.md](file:///home/mir/Documents/hipda-next/docs/design/product-design.md)（v0.4）

---

## 一、总体评价

[BATCH-5.md](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md) 的大骨架质量很高：用 `E0` 先跑通真实 `D4 → D5` 端到端阅读闭环、将 `E15` 登录前置到 Wave 2、把 `E2b` 与 `E2c` 拆成正交并行砍掉一波关键路径、合并小体量的 `E4a + E4b` 为 `E4`，以及通过独立 `di/ImageLoaderModule.kt` + `src/debug/AndroidManifest.xml` 完全避开在途 `D9` 修改的 `HiNextApplication.kt` 与主 `AndroidManifest.xml`，方向均符合第一性原理。

但对照现网源码逐文件核查后，发现 **4 项计划级阻断问题（B1–B4）**——包括现网遗留文件必触发 `CopyTerminologyScanTest` 红灯、`E0` 塞入会被后续卡整文件删除重切的空壳与非主线改动、Wave 3 并行卡 `E2c`/`E2b`/`E3` 存在漏列必改文件与跨卡签名断层，以及全部 8 张卡各自存在“能通过计划所列验收却是错误实现”的验收漏洞。建议先修订 `BATCH-5.md` 闭合 B1–B4 后再展开单卡派发。

---

## 二、阻断项（Blocking Issues）

### B1. 【设计门槛 + CI 必红】`E0` 塞入过多非曳光弹内容（含必被重切的空壳槽位），且 `CopyTerminologyScanTest` 直接撞上遗留 `strings_preference.xml`

1. **`CopyTerminologyScanTest` 扫描规则与现网遗留文件冲突（必致 `E0` CI 红灯或越界改旧文件）**
   - **触发路径**：[BATCH-5.md L38](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L38) 规定扫描“**所有 `res/values/strings_*.xml`**”对照 `product-design.md` §8 右栏禁止词组 0 命中，同时禁止修改旧界面文件（直至 M7-2 删除）。
   - **实查证据**：现网 [strings_preference.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/values/strings_preference.xml#L178) 恰好匹配 `res/values/strings_*.xml`，且实测命中 §8 禁止词 **`消息通知`**（`:178`）与 **`版面`**（而 [strings.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/values/strings.xml) 另有 `'发表新帖'`、`'按发表排序'`、`'只看该作者'` 等 9 处命中）。此外，[product-design.md §8 L513](file:///home/mir/Documents/hipda-next/docs/design/product-design.md#L513) 还禁止了正则模式 `第N层` / `N层`（`\d+层`），[§10.1 L553](file:///home/mir/Documents/hipda-next/docs/design/product-design.md#L553) 还要求扫描用户可见字符串不含异常类名或 HTTP 状态码。
   - **修复**：按 [review.md § Mistakes become checks](file:///home/mir/.gemini/config/skills/team-lead/references/review.md#L43)，`CopyTerminologyScanTest` 扫描 `src/{main,debug}/res/values/strings_*.xml`（同步加入 `\d+层`、异常类名 `*Exception` 与 HTTP 状态码模板检查），将遗留旧文件 **`src/main/res/values/strings_preference.xml` 列入“只能缩短、M7-2 归零”的显式白名单**（`strings.xml` 显式排除），新 `strings_<功能>.xml` 严格 0 豁免。

2. **`E0` 塞入过早猜测的占位文件与无关遗留收口，违反 `planning.md § Design bar`**
   - **关于什么该留、什么该移出 `E0`（回应审查重点 1）**：
     - **应当保留在 `E0` 的不可逆基座**：① `ReadingNavContracts` + `NextAppState` + `LoginOverlaySlot`（解锁 Wave 2 三卡并行）；② `UiErrorMapper` + `CopyTerminologyScanTest`（`E0` 自身展示 `D4`/`D5` 错误即需使用，且防止 Wave 2 `E1`/`E2a`/`E15` 三处重复造轮子或争抢 `feature/common/`）；③ **极薄** `ImageLoaderModule` + `LocalHiImageLoader`（因 Wave 2 并行的 `E1` 帖子列表头像与 `E2a` 楼层圆形头像均需加载图片，放 `E0` 可避免 `E1`/`E2a` 冲突；**但 `E0` 严禁在同步 `@Provides @Singleton ImageLoader` 里读取异步 `SettingsRepository.settings.imageCacheLimitMb`**，否则极易写出主线程 `runBlocking { settings.first() }` 埋下冷启动 ANR，只绑既有 `@Singleton OkHttpClient` + GIF 解码 + 静态默认 `500MB` 缓存目录即可）。
     - **应当移出 `E0` 的膨胀项**：
       1. **删去 `ThreadInteractSlots.kt` 与 `ThreadBottomBarSlot.kt`（[BATCH-5.md L82–83](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L82-L83)）**：Wave 3 的 `E2c`（`:204`）和 Wave 4 的 `E4`（`:237`）本来就独占修改 `ThreadDetailViewModel.kt` 与 `ThreadDetailPane.kt`，这两个文件在 `E2c`/`E4` 中被直接写明“替换占位”，属于 0 并行收益的“先写再删（re-cutting）”，违反 `planning.md` L44–45；
       2. **将 `PostImageBlock.kt` 推迟到 `E2a`（Wave 2）创建**：`E0` 只渲染纯文本段落，尚无 `PostBodyRenderer` 与连续加载 `LazyListState`；由 `E2a` 在写 `PostBodyRenderer` 时一并定死 `PostImageBlock` 签名最准确，不逼 `E2a` 迁就 `E0` 的盲猜；
       3. **将 `R-A6 S3/S4` + `V-A6`（[BATCH-5.md L85–87, L96](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L85-L87)，涉及 `ui/challenge/*` 3 个文件 + `HiStateViews.kt` + `340dp` `challenge_*` 截图更新）从 Complex 的 `E0` 移至 Wave 2 轻量的 `E15`（登录页）**：`E15` 自身只有 3 个生产文件且直接集成 `ChallengeStatusView`，Wave 2 中其余两卡不碰 `ui/challenge/` 与 `HiStateViews.kt`，移走后 `E0` 真正回归最薄 Tracer Bullet。

---

### B2. 【拆分与并行冲突】Wave 3 并行卡 `E2c` 漏列必改文件，`E2b` 缺楼层定位键契约，必致编译受阻或与 `E2c` 撞车

核查 Wave 2 `E2a`（[L124–126](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L124-L126)）与 Wave 3 并行卡 `E2b`（[L184–186](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L184-L186)）‖ `E2c`（[L202–206](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L202-L206)）：
1. **`E2c` 改动清单漏列 `PostFloorCard.kt` 与 `PostBodyRenderer.kt`**：
   - `E2a` 将楼层卡片拆在 `PostFloorCard.kt`、将 `ContentBlock` 分发拆在 `body/PostBodyRenderer.kt`；
   - Wave 3 的 `E2c` 需要把 `ContentBlock.Quote`（`QuoteBlock`）与 `ContentBlock.GoToFloor` 点击跳楼接进 `PostBodyRenderer.kt`，并把 1 楼 `ThreadPollCard`、长按菜单 `FloorContextMenu` 与楼层背景高亮 `FloorHighlightModifier` 接进 `PostFloorCard.kt`，但 `BATCH-5.md` L203–204 只允许 `E2c` 修改 `interact/*`、`ThreadDetailViewModel.kt` 和 `ThreadDetailPane.kt`——**按卡片白名单执行时，`E2c` 根本无法把新组件挂进楼层渲染树**！
2. **`E2b` 的无跳动滚动补偿缺少来自 `E2a` 的 `floorPostId` 与 `LazyColumn` 键约定**：
   - `E2b` 严禁修改 `PostFloorCard.kt` / `PostBodyRenderer.kt`（从而与改这两个文件的 `E2c` 保持零文件交集），但 `E2b` 要求“当图片真实比例与占位不同且**该楼层位于当前可见首项上方**时精准补偿 `LazyListState` 滚动距离（首楼偏移 `≤ 1dp`）”。
   - 在 Compose `LazyColumn` 中，仅凭一个裸 `listState: LazyListState` 无法可靠反查某个子 `PostImageBlock` 属于哪一楼、位于 `firstVisibleItemIndex` 上方还是下方（尤其列表顶部还有加载上一页头或过滤条 item）。
- **修复**：
  1. 在 **`E2a`** 创建唯一并行接缝 `feature/threaddetail/media/PostImageBlock.kt`，明确锁死签名包含 `image: ContentBlock.Image`、`floorPostId: String`、`listState: LazyListState`、`onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit`，并规定 `ThreadDetailPane` 的楼层 `LazyColumn` 固定 `items(..., key = { "floor:${it.postId}" })`；
  2. 在 **`E2c`** 允许修改的文件集合中显式补入 `PostFloorCard.kt` 与 `body/PostBodyRenderer.kt`（`E2b` 仍严格只改 `media/*`、`E3` 只改 `feature/app/*` + `ui/shell/*`，Wave 3 三卡依然 100% 零文件交集）。

---

### B3. 【`E3` 与 `E1`/`E2a`/同波 `E2c` 容器边界断层】跨栏玻璃/顶栏控制入参、`managesGlassSource` 归属与 `movableContentOf` ViewModel 作用域未在 `E0` 锁死

逐项核查审查重点 2（`E3` 与 `E1`/`E2a` 对外壳/容器的改动边界）：
1. **Wave 3 中 `E3` 不能碰 `ForumPane.kt` / `ThreadDetailPane.kt`（后者正由同波 `E2c` 修改），但 `E3` 的 3 项核心行为依赖两面板入参**：
   - 铰链双栏强制关闭左列表透明顶栏玻璃但保留版块标签行（[L159](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L159)）→ 需 `ForumPane` 接收容器下发的顶栏模式与采样源；
   - 详情透明顶栏 `全屏阅读 / 显示列表` 圆形按钮显隐与一键隐藏左中栏联动（[L158](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L158)）→ 需 `ThreadDetailPane` 接收 `showBackButton` 与 `fullScreenToggleState`；
   - 多栏 Snackbar 分区对齐（右栏操作居中右栏、全局操作居中内容区，[L161](file:///home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md#L161)）→ 需 `E2c`（复制楼层）与 `E4`（收藏/关注）通过统一的作用域控制器发送 Snackbar，而非各自内建全宽 `SnackbarHost`。
2. **`HiAdaptiveShell(managesGlassSource = ...)` 的调用点在 `NextAppRoot.kt`（`E0`），不在 `ForumPane.kt`（`E1`）**：
   - 实查 [HiAdaptiveShell.kt L141](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L141) 与 [R-C4d.md L28](file:///home/mir/Documents/hipda-next.wt/_reports/R-C4d.md#L28)：`managesGlassSource` 是 `HiAdaptiveShell` 顶层参数，而 `E1`（Wave 2）无权修改 `feature/app/NextAppRoot.kt`。若 `E0` 未在 `NextAppRoot` 预设 `managesGlassSource = { it == ShellDestination.Forum }` 并在 `E0` 的 `ForumPane` `LazyColumn` 上预挂 `Modifier.shellGlassSource()`，`E1` 合入后从纯色占位页切回论坛页首帧就会踩中 `R-C4d S1` 的父子同源采样竞态。
3. **`movableContentOf` 跨单栏/三栏搬移时的 `ViewModelStoreOwner` 陷阱**：
   - 若 `ThreadDetailPane` 直接调默认 `hiltViewModel()`：放在单栏子路由 `NavEntry` 内则在切三栏时 `ViewModelStore` 被销毁导致重拉网络（违反 `product-design.md` §4.3 L198）；放在 Activity 级 `ViewModelStore` 则中栏点第 2 篇帖子时复用上一篇的实例或状态串帖。
- **修复**：在 `BATCH-5.md` §1.3 不可逆门（由 `E0` 在 `ReadingNavContracts.kt` 与 `NextAppRoot.kt` 一次性落成代码签名）补齐：
  1. `ForumChromeSpec(showForumTabs: Boolean, useGlassTopBar: Boolean, externalGlassState: HiGlassState?)` 与 `DetailChromeSpec(showBack: Boolean, fullScreenState: FullScreenToggle?, suppressBottomGlass: Boolean = false)`；
  2. `LocalPaneSnackbarController`（含 `PaneSnackbarTarget.DetailPane` / `ContentArea`），`E0` 给单栏基础实现，`E3` 升级多栏对齐；
  3. `NextAppState` 按 `tid` 维护受控子 `ViewModelStore`（`movableContentOf` 跨单栏/三栏搬移时注入同一 `LocalViewModelStoreOwner` 保证 0 网络重拉，切换不同 `tid` 或清空右栏时 `clear()` 旧 `tid` Store）；
  4. `E0` 的 `NextAppRoot` 直接配置 `managesGlassSource = { it == ShellDestination.Forum }` 且 `ForumPane` `LazyColumn` 从 `E0` 起即挂 `shellGlassSource()`。

---

### B4. 【验收漏洞总表】每张卡均存在“能通过全部现有验收却是错误实现”的漏洞（共 8 张卡逐一补洞）

按 `planning.md` L55 逐卡构造能通过 `BATCH-5.md` 当前全部验证描述的错误实现，并给出必须在卡片 `[验收]` 中写死的闭合条件：

| 卡号 | 能通过当前全部验收的错误实现（反例） | 验收漏洞根因与闭合断言（写进对应卡片 `[验收]`） |
|---|---|---|
| **`E0`** | 单栏分支写 `if (tid != null) ThreadDetailPane(tid) else ForumPane()`，三栏分支另写 `Row { ForumPane(); ThreadDetailPane(tid) }`（未用同一 `movableContentOf` 实例或换了 `ViewModelStoreOwner`）。假 Repo 0ms 秒回数据，离线单测检查“单栏点帖→切三栏后标题与选中态仍在”**全绿**，但真机每次折叠/展开都会**销毁重建组件树、丢滚动偏移并重新请求网络**。 | 仅测了 UI 终态文本，未测组件保活与请求零增量。<br>**补洞**：`NextAppTracerBulletTest` 将列表/详情滚至非首项并记录假 Repo 调用计数，断言 `411dp → 749dp → 411dp` 切换前后 `loadPage` 请求增量 `== 0` 且 `LazyListState` 首项索引与像素偏移完全不变。 |
| **`E1`** | (1) 真机冷启动时 [DefaultAuthRepository.kt:177-209](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt#L177-L209) `_currentAccount` 首帧为 `null`（Tink 异步读取中），`ForumViewModel` 仅在 `init` 拉一次且不订阅 `currentAccount`/`sessionEpoch`：单测预置已就绪假账号**全绿**，真机已登录用户冷启动首帧按游客拉 `Discovery` 报 `AuthRequired` 后永远卡死在“需要登录”页；<br>(2) 下滚收起顶栏上行时动态缩小 `LazyColumn` 的 `contentPadding(top)`，静态截图**全绿**但滚动中途内容被抽高跳动；<br>(3) `今日新帖` 走 `D8a.getNewPosts` 时未存 `searchId` 翻页或漏组合黑名单/已读流。 | 未测账号流异步水合/登录恢复、未测滚动中固定 `topPadding`、未对齐 `D8a` 与 `D4` 差异。<br>**补洞**：① 模拟 `currentAccount` 由 `null → SavedAccount`（含 `E15` 登录成功），断言处于 `AuthRequired` 的列表自动重拉恢复；② 断言顶栏折叠前后 `LazyColumn` `contentPadding(top)` 差值 `== 0dp`；③ 断言 `今日新帖` 第 2 页携带第 1 页返回的 `searchId` 且响应黑名单隐藏与已读变灰。 |
| **`E2a`** | (1) 向上插入上一页时在 `LaunchedEffect(floors.size)` 里调 `scrollToItem` 恢复锚点：因 `LaunchedEffect` 晚于首帧布局运行，真实画面**先闪跳 1 帧到新页顶再跳回**，而单测 `waitForIdle()` 后测偏移 `≤ 1dp` **全绿**；<br>(2) `TextColorContrastAdjuster` 只把浅色模式亮黄字压暗、深色模式暗字提亮，漏测 **OLED 纯黑（`#000000`）** 下深蓝 `#000080` / 纯黑 `#000000` 帖子字色，既有亮/暗截图**全绿**但 OLED 下字色隐形。 | 锚点测试只在静止终态断言，未逐帧拦截闪跳；调色未穷举极端色 × OLED。<br>**补洞**：① `ContinuousPagingAnchorTest` 设 `mainClock.autoAdvance = false` 逐帧推进插页全过程，断言**每一帧（含数据合入首帧）**原首可见楼层距顶偏差均 `≤ 1dp`；② 对 `TextColorContrastAdjuster` 输入 `#000000`/`#000080`/`#FFFF00`/`#FFFFFF` × 浅/深/OLED 纯黑断言输出 `wcagContrastRatio ≥ 4.5:1`。 |
| **`E15`** | 用户先选安全提问“母亲的名字”并输入回答 `"王芳"`，再改回 `无安全提问`（UI 正确收起回答框），点登录时 `LoginViewModel` 仍把未清空的 `securityAnswer = "王芳"` 传给 `AuthRepository.login`；或服务端返回“安全提问回答错误”时一律把错误挂在密码框并聚焦密码框（单测只测了密码错聚焦密码框，**全绿**）。 | 漏测条件字段收起后的提交载荷净化与多字段报错定位路由。<br>**补洞**：① 断言选提问填答再切回 `无安全提问` 后提交的 `securityQuestionId == 0 && securityAnswer == ""`；② 分别注入用户名/密码错与安全提问错，断言各自挂载在对应字段下方并精确将焦点移至该输入框。 |
| **`E3`** | (1) 右栏为空占位（`hasDetailContent == false`）时，`AnchoredDraggable` 锚点集合仍保留 `0dp`（仅在松手后弹回默认档），用户拖中右分隔线时能把中栏实时拖到 `0dp` 露出空白，手势松手回弹测试**全绿**；<br>(2) 状态迁移测试只跑了“论坛列表 + 帖子详情”展平↔半折切换，实现者在半折切铰链双栏时把左栏硬编码回论坛列表，导致从左栏 `浏览历史`/`提醒` 打开帖子再半折时丢失原来源子列表（违反 `product-design.md` §4.3 L191）。 | 空右栏只测了松手终态未测锚点边界；展平↔半折漏测非论坛入口晋升规则。<br>**补洞**：① `hasDetailContent == false` 时向左大幅拖动，逐帧断言中栏宽度恒 `≥ M_default`（锚点集不含 `0dp`）；② 显式测试从非论坛入口（如 `History`/`Notify`）选中帖子后执行展平三栏 ↔ 半折铰链双栏 ↔ 返回键退回，断言左栏承接原入口并按返回退回 `我的` 而非错跳论坛。 |
| **`E2b`** | 图片高度从 `4:3` 变为真实长图时，`onSizeChanged` 未判断该楼层是否在可见首楼上方就一律调用 `dispatchRawDelta(deltaH)`——`ImageScrollCompensationTest` 只测了“首楼上方图片变高不跳”**全绿**，但当**当前可见首楼内部或下方**的图片加载完成时，反而把用户正在读的首楼强行往上推走；且滑出屏幕回收再滑回时未命中 `ImageAspectRatioCache` 又退回 `4:3` 二次跳动。 | 只测了正向分支（首楼上方），漏测反向不干扰分支（首楼及下方）与回收重入缓存。<br>**补洞**：同测覆盖三分支——① 首楼**上方**图片变高：首楼距顶偏差 `≤ 1dp`；② 首楼**自身或下方**图片变高：首楼距顶偏差 **`== 0dp`（0 误滚）**；③ 滚出回收再滚回：首帧直接按缓存真实宽高比布局。 |
| **`E2c`** | 点击跨页引用（`targetPid` 位于第 8 页第 25 楼）时，调 `D5.loadByPostId` 加载第 8 页并把 `highlightedPid = targetPid` 置位，但未等第 8 页列表挂载后执行 `scrollToItem(targetIndex)`（或用楼层号减 1 当索引而算错过滤/缺失楼层下标）——单测检查 `highlightedPid` 状态与第 1 楼样本**全绿**，真机跳到页尾楼层时停在第 8 页顶部根本看不见目标楼。 | 跨页跳楼测试用首屏可见楼层或仅断言 ViewModel 状态，未断言视口可见性。<br>**补洞**：构造 `targetPid` 位于目标页第 15+ 楼（首屏外）且前置楼层号不连续（含删楼缺号）的夹具，断言跳转完成后 `listState.layoutInfo.visibleItemsInfo` 包含 `key == "floor:$targetPid"` 且带高亮语义。 |
| **`E4`** | (1) 打开帖子恢复阅读位置 `(page = 5, pid = "5352999", offsetPx = 180)` 时，网络 `loadPage(5)` 慢于 300ms，`LazyListState` 初始停在 `(0, 0)`，去抖保存流在首次恢复完成前先把 `(page = 1, offsetPx = 0)` 写入 Room **冲掉历史进度**（假 Repo 0ms 同步返回单测**全绿**）；<br>(2) `suppressToolbarGlass = true` 时只隐藏内部 3 枚胶囊，外层 [HiGlassRegion](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt#L217-L233) 仍留在组合树挂着 `HiGlassMarker = true`，三栏未来叠 `E5` 回复面板时语义节点变成 `4 > 3`。 | 恢复与自动保存之间缺“首轮恢复门闩（hydration gate）”慢载时序测试；工具条卸载未断言语义节点归零。<br>**补洞**：① 用 `CompletableDeferred` 挂起首次 `loadPage(5)` 并推进虚拟时钟/模拟中途返回，断言恢复就绪前 `saveReadingPosition` 调用次数 `== 0`；② 断言 `suppressToolbarGlass = true` 时工具条子树 `HiGlassMarker` 节点数 `== 0` 且 `detailGlassState.hasSourceDemand == false`（无其他消费者时）。 |

---

## 三、其余核查维度结论与非阻断建议（Suggestions）

### 1. 并行文件与截图基线零重叠核查结论（除 B2/B3 外全部通过）
- **与在途 BATCH-4 卡（`D6`、`D8a`、`D9`）**：核对 [D8a.md](file:///home/mir/Documents/hipda-next.wt/_cards/D8a.md#L7-L19) 与 [D9.md](file:///home/mir/Documents/hipda-next.wt/_cards/D9.md#L8-L25)，BATCH-5 全程不碰 `HiNextApplication.kt`、主 `AndroidManifest.xml`、`service/`、`data/message/`、`data/post/`、`data/search/`、`data/favorite/`，确为 **0 文件交集**。
- **批内同波截图前缀与测试热点**：
  - Wave 2：`forum_*.png`（`E1`）‖ `thread_detail_*.png`（`E2a`）‖ `login_*.png`（`E15`，若并入 `V-A6` 则另含 `challenge_*` 340dp），互不重叠；
  - Wave 3：`multipane_*.png`（`E3`）‖ `thread_image_*.png`（`E2b`）‖ `thread_interact_*.png`（`E2c`），互不重叠；
  - Wave 4：`thread_chrome_*.png`（`E4`），单卡无竞争；
  - 共享测试热点（`MotionTokenScanTest.kt`、`HiScreenshotTestSupport.kt`、`HiThemeScreenshotTest.kt`、`HiGlassScreenshotTest.kt`、`HiShellScreenshotTest.kt`、`HiDatabaseDaoTest.kt`、`HttpClientModuleTest.kt`）均保持 0 修改。

### 2. 用户里程碑可行性评估（回应审查重点 4：W2 出真机包、`E15` 是否必在 W2、游客浏览能否让包更早可用）
- **结论：将首个面向用户日常刷帖的真机包定在 Wave 2 收尾（含 `E15`）是完全现实且必要的；未登录游客浏览不能让该包提前到 W1 交付用户。**
  1. **为何游客浏览不能替代 `E15`**：实查 [cloudflare-research.md §4 L118](file:///home/mir/Documents/hipda-next/docs/design/cloudflare-research.md#L118) 真机只读探测记录，未登录访问默认首版块 `Discovery（fid=2）` 直接返回 **`“无权进行当前操作”`**（`Buy & Sell fid=6`、`今日新帖 search.php` 同理需登录），默认关注列表 `[2, 6, 7]`（[AppSettings.kt L75](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/settings/AppSettings.kt#L75)）在游客态下几乎全军覆没，且本批尚无 `管理版块` 切去公开版块 `站务与公告（fid=5）`。
  2. **为何 `E15` 放 Wave 2 零额外代价**：`E15` 依赖的 `D2`/`C5a`/`A6` 均已在 `main` 全绿，自身仅 3 个生产文件（Medium 档），与 Wave 2 的 Complex 卡 `E1`、`E2a` 并行时**完全处于非关键路径（不增加哪怕 1 分钟的 W2 墙钟等待时间）**，却能一次解锁 Tink 凭据落盘、`D2` 静默重登、分账号缓存与桌面 UA 过盾真机验证（闭合 [handoff.md §3 L69](file:///home/mir/Documents/hipda-next/docs/agents/handoff.md#L69) 待观察风险）。*(注：W1 `E0` 合入后若需内部开发冒烟，可临时借同 debug 包内 `SampleExportActivity` WebView 登录写入 `SiteCookieStore` 的 `cdb_auth` 验证列表/详情通路，但不具备 `SavedAccount` 与静默重登，不宜作为给用户的里程碑包。)*

### 3. 四条改进建议（Suggestions）
- **S1 — 为里程碑 A（Wave 2）补一个极简“退出登录 / 切换登录”真机调试出口**：Wave 2 时尚未实现 M6 的 `E12`（账号管理页）。若用户装上 W2 包登录成功后，点左栏/顶栏头像完全无响应（`onAccountClick = {}`），在真机上就无法测试“手动退出登录后不再静默重登（§7.1）”或换号重登。建议在 `E15` / `NextAppRoot` 中约定：当 `E12` 尚未接入时，未登录点账号直接弹 `LoginOverlay`，已登录点账号弹出含 `退出登录`（调 `authRepository.logout()`）的极简临时确认/菜单，M6 `E12` 落地后无缝接管。
- **S2 — `PreviewActivity.kt` 新增「进入新版」按钮须放入 `if (onOpenShellDemo != null)` 分支内**：实查 [PreviewActivity.kt L239–249](file:///home/mir/Documents/hipda-next/hipda/src/debug/java/net/jejer/hipda/ui/designsystem/PreviewActivity.kt#L239-L249) 与 [HiThemeScreenshotTest.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/HiThemeScreenshotTest.kt)，`HiThemeScreenshotTest` 以 `onOpenShellDemo = null` 渲染 `ThemeSpecimenScreen`。将「进入新版」按钮放在 `L245` `SampleExportActivity` 按钮同级的 `if (onOpenShellDemo != null)` 行内，即可保证 12 张 `theme_specimen_*.png` 零像素变动。
- **S3 — 同步基线信息与 `C4d` 已落地的 `HiGlassRegion` 符号**：`origin/main` 已推进至 `2a3f7ca`（`C4d` 已合入 `21b1e3c`），且 [HiGlassSurface.kt L217](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt#L217) 已提供现成 `@Composable fun HiGlassRegion(...)`；在 `BATCH-5.md` 与 `E4` 卡片中直接点名调用 `HiGlassRegion` 包裹底部三段工具条即可，避免实现者重造轮子。
- **S4 — Wave 2 `E1` 与在途 `D8a` 的分支衔接**：按 `team-lead` 调度准则，若 `E0` 合入时 `D8a` 仍在审查收尾但对外接口已定（[D8a.md L58](file:///home/mir/Documents/hipda-next.wt/_cards/D8a.md#L58) `SearchRepository.getNewPosts`），`E1` 可直接基于合并了 `D8a` 分支的起点切出开发，无需空等 `D8a` 合并到 `main` 而浪费 Wave 2 并行槽位。
