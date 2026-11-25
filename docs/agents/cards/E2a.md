# 任务卡 E2a：帖子详情核心完整版（透明极简顶栏、Compose 原生正文、表情调色与 `PostImageBlock` 接缝）

【目标】在 `E0` 落地的最薄 `ThreadDetailPane`/`ThreadDetailViewModel` 上原位加厚为完整的 M5 原生读帖页：渲染 `TopBar` 透明极简顶栏与轻底纯色圆形动作按钮（严禁嵌套玻璃）、实现 `只看楼主` 过滤胶囊与退出回原楼、以 Compose 原生 `AnnotatedString` + `InlineTextContent` + `LinkAnnotation` 渲染楼层正文（消费 `EmojiMap` 闭合 `C1b#2`、`T6` 浅/深/OLED 纯黑动态文字调色），并精准定型 Wave 3 `E2b` 并行接缝 `media/PostImageBlock.kt` 与楼层主键 `floor:$postId`。**翻页本卡先沿用 `E0` 的上一页/下一页按钮**（双向连续加载与向上插页同帧零闪跳已拆分至 Wave 3 `E2d`）；**`R-D5 S1–S4` 已在 `main` 闭环，本卡严禁修改 `data/threaddetail/`。**

【范围】
- 基线：`origin/main` @ `db7dba6`（已包含 `E0`）。工作区与分支：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/E2a -b card/E2a-thread-detail-core db7dba6`。
- 依据文档：
  - `docs/design/product-design.md`（v0.4：§4.1–§4.2、§5 详情透明顶栏与轻底圆钮/兄弟拓扑、§6.3 内容替换/主题切换、§7.3、§8、§10.1、§10.3）
  - `docs/design/tech-plan.md`（v0.4 §5 `T6` 原生正文渲染）
  - `docs/design/copy.md`（v0.4：详情透明顶栏圆形动作、只看过滤胶囊、楼层角标 `{N}楼`、加载与空/错文案）
  - `docs/design/backlog.md`（E2a）与 `/home/mir/Documents/hipda-next.wt/_cards/BATCH-5.md`（修订版 W2、§6 对照表）
  - 基座与底层对外 API 清单（**勿抄死签名，全部以基线源码为准**）：
    - `/home/mir/Documents/hipda-next.wt/_reports/E0.md`（`ThreadNavArgs`、`DetailChromeSpec`、`FullScreenToggle`、`ImageViewerLaunchArgs`、`ReplyRequest`、`ThreadDetailPane` 锁定顶层签名、`UiErrorMapper`、`LocalPaneSnackbarController`、`LocalHiImageLoader`、`FeatureScreenshotQualifiers`、`CopyTerminologyScanTest`）
    - `/home/mir/Documents/hipda-next.wt/_reports/D5.md`（`ThreadDetailRepository`、`ThreadDetailFloorItem`、`ThreadDetailFilter`）
    - `/home/mir/Documents/hipda-next.wt/_reports/B8.md`（`ContentBlock`、`InlineSpan`、`InlineSpan.Emoji.normalizedKey`、`PostFloor`）
    - `emoji/src/main/java/net/jejer/hipda/emoji/EmojiMap.kt`（`drawableForImageSrc`、`codeForImageSrc`）
    - `ui/designsystem/glass/HiGlassSurface.kt`（`HiGlassStyle.TopBar`、`HiGlassTokens.TopBarActionButton*`、`rememberHiGlassState`、`hiGlassSource`）
- 具体实现项（预计生产代码净增/改动 ~600–850 行）：
  1. **顶层签名兼容与 `ViewModel` 构造兼容（零改 `feature/app/` 与 `data/`）**：
     - 严格保持 `E0` 锁定的 `ThreadDetailPane` 顶层 Composable 签名（不得破坏 `NextAppRoot` 调用）；
     - 若 `ThreadDetailViewModel` 增加依赖注入参数，**必须保留与 `E0` 一致的 2 参次构造函数**（或默认参数）以兼容 `feature/app/NextAppTracerBulletTest.kt`，严禁修改 `feature/app/` 与 `data/threaddetail/`；
     - 翻页交互本卡**直接沿用 `E0` 既有的上一页/下一页按钮**（切换 `只看楼主` 等内容替换走 `HiMotion.effectsDefault()`，减动效 0ms 到位；主题/字号/行距切换不重建页面、不丢当前阅读楼层）。
  2. **透明极简顶栏、轻底圆钮与 `只看楼主` 过滤胶囊（`ThreadDetailTopBar.kt`、`FilterCapsuleBar.kt`）**：
     - 详情栏自持独立 `detailGlassState`（`rememberHiGlassState()`），楼层 `LazyColumn` 挂 `Modifier.hiGlassSource(detailGlassState)` 并设置等于顶栏展开高度的固定顶部 `contentPadding(top)`；
     - 顶栏整体为 **1 个 `HiGlassStyle.TopBar` 玻璃区域**（`HiGlassMarker == 1`），与楼层 `LazyColumn` 为**严格兄弟节点**；未滚动纯净 `surface`，向下滚动以 `HiMotion.effectsDefault()` 渐进呈现护读液态玻璃；
     - 顶栏左右动作采用 `40dp` 视觉圆形按钮包裹于 `48×48dp` 触控区（`返回` 按 `chromeSpec.showBack` 显隐；右侧按 `chromeSpec.fullScreenState` 显示 `全屏阅读 / 显示列表` 与 `只看楼主 / 看全部`），**严禁嵌套调用 `HiGlassSurface` 或 `drawBackdrop`**，仅用轻量半透明纯色底（遵守 `HiGlassTokens.TopBarActionButtonLightAlpha`/`DarkAlpha` + `TopBarActionButtonBorderWidth` 细边）与 `hiPressScale`；
     - 开启 `只看楼主` 后，顶栏下方常驻 `FilterCapsuleBar`（`只看楼主 ×`，`×` 内容描述 `退出只看`，预留按作者过滤态 `只看 {用户名} ×` 供 Wave 3 `E2c` 复用）；点击 `×` 退出过滤恢复全帖并定位回切换前正在阅读的楼层，过滤结果为空展示 `没有符合条件的楼层`（按钮 `看全部`）。
  3. **楼层卡片与 Compose 原生正文渲染（`PostFloorCard.kt`、`body/PostBodyRenderer.kt`、`body/InlineEmojiContent.kt`、`body/TextColorContrastAdjuster.kt`）**：
     - `PostFloorCard`：内容最大宽 `680dp` 居中，头部含圆形头像（`LocalHiImageLoader` 自动加载）、`{N}楼` 角标、作者、楼主标识、发帖时间，黑名单作者保持清晰遮蔽态；
     - `PostBodyRenderer`（`T6` 原生渲染）：消费 `B8` `ContentBlock`（`Text`、`Quote` 基础可读态留给 `E2c` 展开/高亮、`GoToFloor` 可读标记、`Image` 路由至 `PostImageBlock`、`Attachment`、`Notice`），使用标准 `AnnotatedString` + `InlineTextContent` + `LinkAnnotation` 渲染粗体/斜体/下划线/删除线/字号/文字颜色/外链/站内链接/邮箱/换行；
     - **闭合 `C1b#2`（`InlineEmojiContent.kt`）**：将 `InlineSpan.Emoji.normalizedKey` 直接传入 `EmojiMap.drawableForImageSrc(key, dark)`，以 `InlineTextContent` 内联渲染 3 套论坛表情（随深浅色主题切换），无法匹配时优雅回退 `EmojiMap.codeForImageSrc(key)` 或可读占位；
     - **闭合 `B4-E2a ②`（`TextColorContrastAdjuster.kt`）**：对帖子作者指定的任意前景色在渲染期结合当前主题背景色（浅色 / 深色 / OLED 纯黑 `#000000`）动态校正明度/色度，确保输出文字对比度全覆盖 **`wcagContrastRatio ≥ 4.5:1`**，避免白底黄字、黑底黑字或深蓝字不可读。
  4. **锁死 Wave 3 `E2b` 并行接缝（闭合 R-BATCH5 B2-2）**：
     - 创建 `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt`（最小静态占位实现），锁定顶层 Composable 签名必须包含：`image: ContentBlock.Image`、`floorPostId: String`、`listState: LazyListState`、`onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit`（可附 `modifier: Modifier = Modifier`）；
     - 楼层 `LazyColumn` 固定键锁定为 `items(items = uiState.floors, key = { "floor:${it.postId}" })`，保证 Wave 3 `E2b` 仅修改 `feature/threaddetail/media/` 零触碰任何父文件；
     - 全部界面文案放入 `hipda/src/main/res/values/strings_thread_detail.xml`，逐字取自 `docs/design/copy.md`，通过 `CopyTerminologyScanTest`（0 豁免）。
- **明确不做**：不做双向自动连续预取加载与向上插页同帧锚定（拆分至 Wave 3 `E2d`）；不改 `data/threaddetail/`（`R-D5` 已闭环）；不实现图片真实加载/省流/滚动补偿（`E2b`）、长引用展开/跨页跳楼高亮/长按菜单/1 楼投票（`E2c`）、阅读位置记忆/位置栈/底部三段工具条/内联跳页（`E4`）；不改 `feature/app/*`、`feature/common/*`、`feature/forum/*`、`feature/login/*`、`ui/*`、`di/*`、`src/debug/*`；不碰既有非 `thread_detail_*` 快照。

【验收】
1. **三主题极端文字色对比度（B4-E2a ②）**：`TextColorContrastAdjusterTest` 对 `#000000`、`#000080`、`#FFFF00`、`#FFFFFF` × 浅色 / 深色 / OLED 纯黑（`#000000`）全部断言输出 `wcagContrastRatio ≥ 4.5:1`，且对原本可读的正常色不做过度失真。
2. **Compose 原生正文与 `C1b#2` 表情映射**：`PostBodyRendererTest` 消费 `B8` 仓内脱敏真机样本（含 3 套论坛表情、补充平面 Emoji、链接、跳楼文本、彩色文字与行内样式），验证 `InlineSpan.Emoji.normalizedKey` 正确命中 `EmojiMap.drawableForImageSrc`（浅/深切换）且未知键安全回退。
3. **透明顶栏玻璃拓扑、`只看楼主` 过滤、沿用上下页与并行接缝（B2-2）**：
   - 单测断言 `detailGlassState` 与 `TopBar` 为严格兄弟、`HiGlassMarker == 1`、顶栏圆形按钮零嵌套玻璃、所有圆钮触控区 ≥ `48×48dp`；滚动期间重组增量门禁通过；
   - 开启 `只看楼主` 后展示 `只看楼主 ×` 胶囊，点 `×` 退出后回到全帖并定位原阅读楼层；过滤空态展示 `没有符合条件的楼层` + `看全部`；沿用 `E0` 的上一页/下一页按钮切页工作正常；
   - 确认 `media/PostImageBlock.kt` 签名包含 `image`、`floorPostId`、`listState`、`onOpenImageViewer` 且 `LazyColumn` 使用 `key = { "floor:${it.postId}" }`。
4. **核心截图基线（`ThreadDetailScreenshotTest` + Roborazzi）**：录制并校验约 12–14 张 `hipda/src/test/snapshots/images/thread_detail_*.png`（**不含连续加载头尾状态**，覆盖单栏 `411×891`/`559×749`/`640×1024` 与大屏详情宽 `411`/`448`/`680dp` × 浅/深、透明顶栏轻底圆钮与 `只看楼主` 胶囊、原生富文本+表情+浅/深/OLED 调色、第 5 档字号×1.3、空/错/骨架 `411` & `933dp`）；既有非 `thread_detail_*` 快照 0 漂移。
5. **全量门禁**：`CopyTerminologyScanTest`、`MotionTokenScanTest`（白名单 0 增长）、`NextAppTracerBulletTest`、`:hipda:testDebugUnitTest`（含 `-Proborazzi.test.verify=true`）、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 在本地与 GitHub Actions 全绿。

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
- 最后一条回复不超过 600 字，第一个词只能是 DONE、PARTIAL、NEEDS-INPUT 或 STUCK，随后是结论、最终 commit hash、CI run 链接、每条验收一行（通过/未通过）、遗留问题；详细证据、日志和对外 API 清单（签名 + 文件:行号）写到 `/home/mir/Documents/hipda-next.wt/_reports/E2a.md` 并在回复里给出路径。
- 界面卡：优先用 Compose 标准组件（`Row`/`Column`/`AnimatedContent`/`AnimatedVisibility`/`drawWithCache`/`Indication`）；手写 `Layout`、`Modifier.Node`、状态机或命令队列必须在回复里说明标准组件为什么不够。生产代码不得包含只为测试存在的代码（测试专用的语义键、钩子、CompositionLocal）。生产代码超过卡片估计行数 1.5 倍时，先自查简化再交付，并在回复里给出行数和理由。

【改哪些文件】
- 修改（原位加厚 `E0` 预留薄壳，Wave 2 独占）：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModel.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailPane.kt`
- 新增（生产）：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/ThreadDetailTopBar.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/FilterCapsuleBar.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/PostFloorCard.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/PostBodyRenderer.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/TextColorContrastAdjuster.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/InlineEmojiContent.kt`
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/media/PostImageBlock.kt`
  - `hipda/src/main/res/values/strings_thread_detail.xml`
- 新增（测试与截图基线）：
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ThreadDetailViewModelTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/PostBodyRendererTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/TextColorContrastAdjusterTest.kt`
  - `hipda/src/test/java/net/jejer/hipda/feature/threaddetail/ThreadDetailScreenshotTest.kt`
  - `hipda/src/test/snapshots/images/thread_detail_*.png`（约 12–14 张，不含连续加载头尾态）
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/E2a.md`

【负责人追加（E0 合入后）】
- 闭合 R-E0 R2-S2 与 S3（详情部分）：`ThreadDetailViewModel` 订阅账号流，处于 `AuthRequired` 时登录成功后自动重载当前页，不需要手动刷新；补单测。
