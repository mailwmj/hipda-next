# 独立代码审查报告 R-E2a：帖子详情核心完整版（透明极简顶栏、Compose 原生正文、表情调色与 `PostImageBlock` 接缝）

- **审查结论**：**`CHANGES-REQUESTED`**
- **审查对象**：`origin/card/E2a-thread-detail-core` @ `60bd5b7b94a1f2eaf386103db568f84c030b09ee`（简称 `60bd5b7`）
- **对比基线**：`db7dba6`（另经 `git merge-tree --write-tree origin/main 60bd5b7` 对照当前 `origin/main` @ `21f41df` 核验，**0 合并冲突**）
- **GitHub Actions CI 核实**：[Android CI #45678739131](https://github.com/mailwmj/hipda-next/actions/runs/45678739131)
  - `headSha`：`60bd5b7b94a1f2eaf386103db568f84c030b09ee`（与分支 HEAD 100% 匹配）
  - `Build, Lint & Unit Tests`（Job `139433255110`）：**SUCCESS**，核实 `.github/workflows/android.yml` 已完整执行并通过 `./gradlew assembleDebug assembleRelease lintDebug`、Room schema 一致性校验、`./gradlew testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 Debug/Release APK 包名校验
- **阻断项**：**1 项**（`B1`，仅需修改 `PostBodyRenderer.kt` 约 15–20 行 + 补 `PostBodyRendererTest.kt` 反例单测即可闭合）
- **非阻断建议项**：**5 项**（`S1`–`S5`）

---

## 一、阻断项（Blocking Issues）

### B1. 【安全边界漏洞 · 对应负责人重点 3】`PostBodyRenderer` 未校验链接与附件 URL scheme，将 `javascript:` / `intent:` / `file:` / `content:` / `data:` 直接样式化为可点链接并交由系统 `ACTION_VIEW` 打开

- **文件与行号**：
  - `hipda/src/main/java/net/jejer/hipda/feature/threaddetail/body/PostBodyRenderer.kt:68-77, 122-125, 197`
  - 上游保留行为印证：`core-parser/src/main/kotlin/net/jejer/hipda/parser/threaddetail/ThreadDetailParser.kt:871-879, 1128, 1162`
- **具体触发路径**：
  1. 论坛帖子正文 `td.t_msgfont` 出现带非 HTTP(S) 或危险 scheme 的 `<a href="...">`——例如 Discuz! 7.2 模板常见的 `<a href="javascript:;">` / `<a href="javascript:history.back()">[ 返回 ]</a>`、或帖内恶意的 `<a href="javascript:alert(1)">点我</a>`、`<a href="intent://...#Intent;component=...;end">点我</a>`、`<a href="file:///data/user/0/net.jejer.hipda.next/...">`、`<a href="content://...">`、`<a href="data:text/html,...">`（及 `ContentBlock.Attachment(url = ...)`）。
  2. `:core-parser` 中 `ThreadDetailParser.processAnchorElement`（`:871-879`）调 `normalizeLinkUrl`（`:1162`），对 `lower.startsWith("javascript:")` **原样保留**进 `InlineSpan.Link.url`；`resolveForumUrl`（`:1128`）对任何含 `://`（如 `intent://`、`file://`、`content://`）与 `data:` 的 URL 亦**原样保留**。
  3. 生产链路 `ThreadDetailPane.kt:136` → `PostFloorCard.kt:72` 调用 `PostBodyRenderer(..., onLinkClick = null)`。
  4. `PostBodyRenderer.kt:69-71` 无条件将 `span.url` 包装为 `LinkAnnotation.Url(span.url, ...)`（使 `javascript:;`、`#` 等非导航锚点也被渲染成带下划线的蓝色可点击链接）；随后点击该链接或点击 `PostAttachmentBlockView`（`:197`）时，`:123-125` 的默认处理器 `onLinkClick ?: { url -> runCatching { uriHandler.openUri(url) }; Unit }` 未做任何 scheme 白名单校验，直接调用 `LocalUriHandler.current.openUri(url)`（即 `context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))`）。同时，若外部直接调用 `buildPostTextBlock(..., onLinkClick = null)`，`LinkAnnotation.Url` 的 `linkInteractionListener == null` 也会回退触发 Compose 默认 `LocalUriHandler.openUri(span.url)`。
  5. `PostBodyRendererTest.kt` 仅以自定义回调 `onLinkClick = { openedLinks += it }` 测试了合法 `https://` 地址（`:245, 264`），未测试生产默认 `onLinkClick = null`（走 `LocalUriHandler`）路径，也无任何危险 scheme 反例断言。
- **后果**：
  - 不受信任的帖子富文本可将 `javascript:`、`intent:`、`file:`、`content:`、`data:`、`tel:` 或任意第三方自定义 scheme 直接送入系统隐式 `Intent(ACTION_VIEW)`，存在唤起非预期外部组件、探测本地 `file://`/`content://` URI 或命中第三方注册了特殊 scheme 的浏览器/查看器的安全风险；同时帖内无害的 `javascript:;` / `#` 伪链接会被误渲染为蓝色可点链接误导用户。
- **修复建议**：
  1. 在 `PostBodyRenderer.kt` 增加纯函数白名单校验（严格只放行安全 scheme，其余一律拦截）：
     - 对 `InlineSpan.Link` 与 `ContentBlock.Attachment`：**仅允许 `http://` 与 `https://` 且含合法非空 host**（可直接用项目已依赖的 `okhttp3.HttpUrl.Companion.toHttpUrlOrNull()` 校验 `scheme in {"http", "https"} && host.isNotBlank()`；注意 `DefaultThreadDetailRepository.kt:432,792` 已固定传入 `forumBaseUrl = "https://www.4d4y.com/forum/"`，真实站内相对链接如 `viewthread.php?tid=...` / `redirect.php?...` / `attachment.php?...` 在解析期均已归一化为 `https://...`，`http`/`https` 白名单同时完整覆盖外链与站内链接）；
     - 对不通过白名单的 `InlineSpan.Link`（如 `javascript:;`、`javascript:alert(1)`、`data:...`、`file://...`、`intent://...`、`#`）：在 `buildPostTextBlock` 中**退化为普通文本 `append(span.label)`**（不挂 `LinkAnnotation.Url`、不加链接下划线色）；对合法链接始终挂载非空 `LinkInteractionListener`（阻断 `onLinkClick == null` 时回退裸 `LocalUriHandler`）；
     - 对 `InlineSpan.Email`：校验 `email = span.email.trim()` 非空、含 `@`、不含空白/控制字符与 `:`/`/`，再构造 `mailto:$email`；
     - 在 `PostBodyRenderer` 的 `resolvedLinkHandler` 入口再次守卫：仅允许合法 `http`/`https`（及经校验的 `mailto:`）进入 `onLinkClick` 或 `uriHandler.openUri`。
  2. 在 `PostBodyRendererTest.kt` 用 `CompositionLocalProvider(LocalUriHandler provides recordingUriHandler)` 测试 `onLinkClick = null` 生产默认路径：验证点击 `javascript:alert(1)`、`JAVASCRIPT:history.back()`、`data:text/html,<script>`、`file:///etc/hosts`、`content://media/external`、`intent://scan/#Intent;end`、`#top`（含 `InlineSpan.Link` 与 `ContentBlock.Attachment`）时 `openedUris` 严格为空（且 `javascript:` 文本无 `LinkAnnotation`），而合法外链、站内 `https://` 链接与合法邮箱正常触发。

---

## 二、负责人四项补充重点逐项核查

### 重点 1：生产代码净增 `+1,171` 行——逐文件可删减审计

本卡 `hipda/src/main/` 共 10 个文件 `1,404` 行，相对 `E0` 基线（`ThreadDetailPane.kt` 139 行 + `ThreadDetailViewModel.kt` 94 行 = 233 行）**净增 `+1,171` 行**（未超 `850 × 1.5 = 1,275` 行上限，0 测试专用钩子/tag，0 手写 `Layout`/`Modifier.Node`）。逐文件审视后，**在本卡边界内可直接删减约 `80–90` 行**（另有一处跨 `ui/` 边界的重复算法约 `55` 行留待授权改 `ui/` 的卡统一去重）：

| 生产文件 | 当前行数（净增） | 可直接删掉 / 精简的具体部分（含行号与理由） | 预计可省 |
|---|---:|---|---:|
| `ThreadDetailViewModel.kt` | 316 (`+222`) | ① **删 `:11-12, 306-316` 整块 `UnboundAuthRepository` 假实现**：将字段改为可空 `private val authRepository: AuthRepository?`（`@Inject` 主构造仍收非空 `AuthRepository`，2 参次构造 `:89-92` 传 `null`，`observeAuthRecovery()` 首行判空早退），100% 兼容 `NextAppTracerBulletTest` 且不启动 2 个无意义的空流协程。<br>② **删 `:53, 75, 126, 150, 154, 159, 173, 180, 194, 241, 280` 的 `contentRevision` / `bumpRevision` 全链路**（见下一行 `ThreadDetailPane.kt` 说明）。<br>③ **删 `:176-181` 生产 0 调用的 `filterByUser(...)`**：本卡无长按菜单入口（仅单测直接调用），`E2c` 本就修改本文件，届时随菜单一并落地即可（保留 1 行 `ThreadDetailFilterUi.UserOnly` 供 `FilterCapsuleBar` 即可）。<br>④ **合并 `:231-232` 双协程订阅**：改用单个 `combine(currentAccount, authState)` 消除两个独立协程修改共享局部变量 `prevAccount`/`prevState`/`prevEpoch`。 | **~36 行** |
| `ThreadDetailPane.kt` | 208 (`+69`) | **删 `:4, 17, 59-71, 130` 的 `contentAlpha = Animatable(1f)` + `initialRevision` + `LaunchedEffect(uiState.contentRevision)`**：`LaunchedEffect` 在新页楼层**首帧完成布局与绘制之后**才执行 `snapTo(0f)` 再淡入，导致切页/切只看时出现“新内容先亮 1 帧 → 闪黑为 0 → 再淡入”的闪烁伪影，且把 `alpha=0` 录进了 `.hiGlassSource(detailGlassState)` 离屏层；空/骨架/错与正文切换已由外层标准 `AnimatedContent`（`:99-105`，原生支持减动效 0ms）处理。 | **~18 行** |
| `ThreadDetailTopBar.kt` | 180 (`+180`) | **删 `:120-127` 独立的底部分隔线 `Box` 组件节点**：`:82-85` 父 `Box` 已有 `.drawBehind`，在同一个 `drawBehind` 里用 2 行画底部 `0.5.dp` 分隔线即可省去 8 行代码和 1 个多余 `LayoutNode`。（注：`:165-180` 的 16 行 `TopBarVectorIcons` 因 `:hipda` 仅依赖 `material-icons-core` 未引 `extended` 属合理保留。） | **~8 行** |
| `FilterCapsuleBar.kt` | 79 (`+79`) | 整体较克制（卡面指定独立文件并预留 `UserOnly` 文案）；可精简 `:39-42, 50-55` 的局部缓存样板或用 `AnimatedContent` 直出。 | **~6 行** |
| `PostFloorCard.kt` | 97 (`+97`) | 97 行覆盖头像首字回退、`AsyncImage`、`楼主` 徽标、黑名单遮蔽与 `postStatus`，无水分（触控区改进见 `S2`）。 | **0–3 行** |
| `body/PostBodyRenderer.kt` | 208 (`+208`) | `:184-200` `PostAttachmentBlockView` 手写 `.clip.background.border.clickable` 8 行修饰符链可改用标准 `Surface(onClick = ..., border = BorderStroke(...))` 省 ~6 行（修 `B1` 安全校验另需约 `+15` 行）。 | **~6 行**（修前） |
| `body/TextColorContrastAdjuster.kt` | 182 (`+182`) | *(跨边界说明)* `:106-160` 的 OKLCH 矩阵/色域二分实现（约 55 行）与 `ui/designsystem/HiColors.kt:398-496` 完全重复，因本卡严禁改 `ui/*` 暂独立存放，后续在授权改 `ui/designsystem/` 的卡（如 `E4` 或 M7）抽为内部共享函数即可删掉 ~55 行；本文件内注意 `TextColorContrastAdjusterTest` 为纯 JVM 测试（未挂 Robolectric），不可替换为 `android.graphics.Color.parseColor`，但 `:162-181` 的 74 个 W3C 颜色名中约半数不在 Discuz `coloroptions`（40 色）内，可按需收敛。 | **本卡 ~5–10 行**（后续共享可省 **~55 行**） |
| `body/InlineEmojiContent.kt` | 71 (`+71`) | `:19-20, 50-53` 仅单处调用的前缀常量与单行包装函数 `emojiInlineId` / `smallImageInlineId` 可内联。 | **~6 行** |
| `media/PostImageBlock.kt` | 50 (`+50`) | `:38-46` 同样可用 `Surface(onClick = ...)` 替代手写 `.clip.background.border.clickable` 省 ~6 行（Wave 3a `E2b` 将原位重写该文件）。 | **~6 行** |
| `res/values/strings_thread_detail.xml` | 13 (`+13`) | `:4` `thread_detail_action_author_only` 与 `:6` `thread_detail_filter_author_only` 均为 `只看楼主`，合并为 1 条即可。 | **1 行** |

---

### 重点 2：`TextColorContrastAdjuster` 双重目标（`≥ 4.5:1` + 正常色不过度改色）与测试反例核查

1. **生产算法核验（`TextColorContrastAdjuster.kt:54-91`）—— 满足两点要求**：
   - **已达标正常色零改动**：`:61` `if (wcagContrastRatio(fg, bg) >= minContrastRatio) return fg` 在对比度已 `≥ 4.5:1` 时直接原样返回 RGB（零色相/明度/色度漂移）。
   - **不达标颜色最小明度推移且保色相**：`:71-90` 将颜色转入 OKLCH 固定色相 `fgH`，以 `targetRatio = 4.58f` 在 `[0, fgL]`（浅底压暗取最大合法 `lowL`）或 `[fgL, 1]`（深底/纯黑提亮取最小合法 `highL`）做 24 次二分搜索，并在每步先做 `clampChromaToSrgb` 与 8-bit `quantizeSrgb` 后再算 WCAG 对比度。这保证了：① 8-bit 舍入后对比度恒 `≥ 4.5:1`（覆盖 `#000000`、`#000080`、`#FFFF00`、`#FFFFFF` × 浅/深/OLED 纯黑 `#000000` 及全 20 套主题预设）；② 校准后对比度精准停在 `~4.58:1` 附近而不会粗暴压成纯黑/纯白或灰阶。
2. **现有单测反例覆盖与可加固盲区（`TextColorContrastAdjusterTest.kt:80-127`）**：
   - **已覆盖的反例**：
     - `:81-110` `alreadyReadableColors_arePreservedWithoutDistortion` 已覆盖浅底 5 种可读色（含 `#B71C1C` 深红、`#1B5E20` 深绿、`#4A148C` 深紫）与深底/OLED 5 种可读色（含 `#80DEEA` 青、`#FFB74D` 橙、`#A5D6A7` 浅绿），用 `assertEquals` 严格断言**零失真原样返回**（若实现把所有颜色统一改成 `onSurface` 必挂）；
     - `:113-126` `adjustedChromaticColors_preserveDominantHueFamily` 已验证白底 `#FFFF00` 压暗后 `red > blue && green > blue`、OLED 纯黑 `#000080` 提亮后 `blue > red && blue > green`（若实现把不达标色退化为纯黑/纯白/中性灰必挂）。
   - **建议顺手补强的 1 个边界盲区（见 `S3`）**：当前 `:113-126` 只断言了 `ratio >= 4.5f` 和通道大小关系（`blue > red`），若错误实现把白底 `#FF0000`（原对比度约 `3.9:1`，属帖内最常见正常红字）过度压黑到 `#100000`（对比度 `19:1`，视觉近乎纯黑，但仍满足 `red > blue`），现有断言不会变红。建议在修复轮补一条断言：白底 `#FF0000`、白底 `#FFFF00`、OLED `#000080` 校准后的 `ratio in 4.5f..5.5f` 且主副通道差值 `>= 0.25f`，彻底锁死“最小必要改色”。

---

### 重点 3：富文本外链与站内链接点击安全核查

- 已作为唯一阻断项 **`B1`** 详述（见第一节）：
  - 外链与站内链接在 `DefaultThreadDetailRepository` 下均由 `ThreadDetailParser` 携带 `forumBaseUrl = "https://www.4d4y.com/forum/"` 归一化为 `http://`/`https://` 地址（站内应用内路由按 `backlog.md` 归属 M6 `E16`，当前里程碑 A 走外部浏览器打开合法 `https://` 链接符合预期）；
  - 但 `ThreadDetailParser` 原样保留了 `javascript:`（以及潜在 `intent:`/`file:`/`content:`/`data:`），而 `PostBodyRenderer` 零 scheme 过滤直接交给 `LocalUriHandler.openUri`，必须在修复轮按 `B1` 补齐严格 `http`/`https`（及 `InlineSpan.Email` 的 `mailto`）白名单与反例单测。

---

### 重点 4：确认 Wave 3 `E2b`、`E2c`、`E2d` 能否分别只改各自文件

逐一核对生产代码签名、调用链与 `BATCH-5.md` / `E2d.md`：

| 下游卡 | 负责人关注的文件边界 | 核查结论 | 代码证据与具体说明 |
|---|---|---|---|
| **`E2b`** | 只改 `feature/threaddetail/media/`（+ `strings_thread_media.xml`） | **✅ 完全成立（0 父文件改动）** | 1. `media/PostImageBlock.kt:28-34` 已精确锁定签名 `(image: ContentBlock.Image, floorPostId: String, listState: LazyListState, onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit, modifier: Modifier = Modifier)`；<br>2. `ThreadDetailPane.kt:134` 锁定 `items(items = uiState.floors, key = { "floor:${it.postId}" })`，`PostFloorCard.kt:72` 准确传入 `floor.postId`，`E2b` 可直接在 `PostImageBlock` 内对比 `listState.layoutInfo.visibleItemsInfo` 的 `"floor:$floorPostId"` 并调 `listState.dispatchRawDelta` 完成三分支滚动补偿，经 `currentHiImageLoader()` 加载图片、经 `onGloballyPositioned` 上报缩略图坐标。 |
| **`E2d`** | 只改 `ThreadDetailViewModel.kt` 和 `ThreadDetailPane.kt` | **✅ 完全成立（0 其他文件改动）** | 1. 临时上下页栏 `ThreadDetailPaginationBar` 为 `ThreadDetailPane.kt:189-208` 内部 `private` 函数，移除不波及外部；<br>2. 头尾连续加载状态行复用既有 `HiListFooter`（`HiStateViews.kt:182`，已自带 `正在加载…`/`加载失败，点击重试`/`没有更多了` 文案），**无需改 `strings_thread_detail.xml`**；<br>3. `ThreadDetailTopBar`、`FilterCapsuleBar`、`PostFloorCard`、`body/*`、`media/*` 均与单页/连续页状态解耦。 |
| **`E2c`** | 只改 `PostFloorCard` 和 `PostBodyRenderer`（+ 新增 `interact/*`、`strings_thread_detail.xml`、`:core-parser`） | **⚠️ 不成立——仍须同时改 `ThreadDetailViewModel.kt` 和 `ThreadDetailPane.kt`** | 在 `60bd5b7` 中，以下 3 处 `E2c` 必做能力尚未接通 `VM` / `Pane`：<br>1. **跨页跳楼与高亮**：`VM` 无调用 `D5.loadByPostId` 的跳楼方法与目标楼层高亮状态，`Pane:136` 未向 `PostFloorCard` 传跳楼回调与高亮标记；<br>2. **长按菜单**：`Pane:49` 的 `onOpenReply` 标了 `@Suppress("UNUSED_PARAMETER")` 未传给 `PostFloorCard:136`；`Pane:136` 未传 `viewModel::filterByUser`（`只看此人`）；`ThreadDetailUiState` 未暴露当前登录 `uid`（无法判断本人楼层以显示 `编辑`/`删除`）；<br>3. **1 楼投票提交**：`VM` 未保存 `fid` 且无调用 `D5.submitPollVote` 的方法。<br>**调度结论**：这与 `BATCH-5.md` §2.8 L252 及 `E2d.md` L3 既定排期一致——**`E2d`（Wave 3a）与 `E2c`（Wave 3b）必须串行衔接，不可同波并行**（且因跨页跳楼和投票刷新都会重置 `E2d` 引入的 `firstLoadedPage..lastLoadedPage` 连续页区间，保持 `E2d → E2c` 串行并允许 `E2c` 修改 `VM`+`Pane` 比在 `E2a` 预埋单页桩代码更干净无冲突）。 |

---

## 三、其余审查维度核实与非阻断建议（Suggestions）

### 3.1 已核实通过的审查项
1. **`T6` 原生渲染与 `C1b#2` 表情映射**：`PostBodyRenderer.kt` 纯粹使用 `AnnotatedString` + `InlineTextContent` + `LinkAnnotation`（0 `WebView` / 0 `AndroidView` / 0 `Html.fromHtml`）；`InlineEmojiContent.kt:40-42` 直接把 `InlineSpan.Emoji.normalizedKey` 传入 `EmojiMap.drawableForImageSrc(key, dark = darkTheme)`，未知键回退 `EmojiMap.codeForImageSrc(key) ?: "[$key]"`，空键回退 `"[表情]"`；`PostBodyRendererTest` 已用 `thread_p1.html` / `thread_p2.html` / `thread_author_only.html` 真机脱敏样本验证 3 套表情浅深色切换与补充平面 Emoji `👌`。
2. **透明顶栏玻璃拓扑与轻底圆钮**：`ThreadDetailPane.kt:97-160` 中挂 `.hiGlassSource(detailGlassState).background(cs.background)` 的 `LazyColumn` 与 `ThreadDetailTopBar`（`HiGlassSurface(style = HiGlassStyle.TopBar)`，`HiGlassMarker == 1`）为严格同级兄弟节点；圆钮 `TopBarCircleActionButton`（`:133-163`）采用 `48×48dp` 触控区包裹 `40dp` 纯色半透明圆底（`TopBarActionButtonLightAlpha = 0.60f` / `DarkAlpha = 0.65f` + `0.5dp` 细边 + `hiPressScale`），全树 `DrawBackdrop == 1`、`LayerBackdrop == 1`，零嵌套玻璃；滚动期间楼层卡片重组增量为 `0`。
3. **`只看楼主` 过滤胶囊、退出回原楼与登录态自动重载（`R-E0 R2-S2/S3`）**：开启 `只看楼主` 后展示 `只看楼主 ×`（`×` 描述 `退出只看`、`48×48dp`），空态显示 `没有符合条件的楼层` + `看全部`，退出后按切换前保存的 `(page, postId, scrollOffset)` 准确恢复原页与像素偏移；`observeAuthRecovery` 覆盖首次游客 `AuthRequired` 登录后自动重载、会话过期同号重登自动重载第 2 页及首屏请求在途水合竞态重试。
4. **签名兼容、术语/动效门禁与截图基线**：`ThreadDetailPane` 顶层签名与 `E0` 完全一致；15 张 `thread_detail_*.png` 覆盖单栏/多栏宽/浅深与纯黑/第 5 档×1.3/空错骨架且不含连续加载头尾态；其余快照、共享构建文件与其他模块 0 改动。

### 3.2 非阻断改进建议（`S1`–`S5`）
- **S1 — 按第二节表格顺手删减 `E2a` 内部约 `75–85` 行冗余代码（`ThreadDetailViewModel.kt`、`ThreadDetailPane.kt`、`ThreadDetailTopBar.kt`、`strings_thread_detail.xml`）**：重点移除 `UnboundAuthRepository`（改可空 `AuthRepository?`）、会导致切页首帧闪烁的 `contentRevision` + `contentAlpha` `LaunchedEffect`、0 调用的 `filterByUser`、`ThreadDetailTopBar` 独立底线 `Box` 节点以及重复的 `只看楼主` 字符串条目。
- **S2 — 楼层头部作者头像/用户名点击触控区与 TalkBack 语义优化（`PostFloorCard.kt:47-63, 85-90`）**：当前 `.clickable` 直接挂在 `36×36dp` 的 `FloorAvatar` 上（小于 `product-design.md` §10.1 规定的 `48dp` 下限，且作者名本身不可点、TalkBack 仅读出首字母 `initial`）；建议后续在 `E2c` 改造 `PostFloorCard.kt` 时将点击热区扩至左侧「头像 + 作者名/时间」组合区域并赋予完整作者名无障碍语义。
- **S3 — 补强 `TextColorContrastAdjusterTest` 防“过度压黑/漂白”上界断言（`TextColorContrastAdjusterTest.kt:113-126`）**：对白底 `#FF0000` / `#FFFF00` 与纯黑底 `#000080` 补充 `ratio in 4.5f..5.5f` 及主副通道最小差值（`>= 0.25f`）断言，用单测锁死“最小必要明度调整”。
- **S4 — 对齐 `_reports/E2a.md` 对外 API 文档参数名（`_reports/E2a.md:108` vs `PostFloorCard.kt:36`）**：报告中将 `PostFloorCard` 第 5 个参数写成了 `onOpenUserProfile`，实际源码签名为 `onAuthorClick: (uid: String, username: String) -> Unit`，修复轮更新报告时同步对齐以免误导 `E2c`。
- **S5 — 调色背景色取用与跨模块 OKLCH 去重备忘（`PostBodyRenderer.kt:152-153`、`TextColorContrastAdjuster.kt:106-160`）**：`ThreadDetailPane.kt:95,129` 实际楼层底色绘制的是 `cs.background`，虽然当前 20 预设与纯黑下 `cs.surface == cs.background`，`PostTextBlockView` 直接传 `cs.background` 语义更严密；另 `TextColorContrastAdjuster` 中约 55 行 OKLCH 转换与 `HiColors.kt:398-496` 重复，可在后续授权修改 `ui/designsystem/` 的卡片中抽为共用工具。


---

# 二轮复审报告（Round 2：修复轮 1 @ `332c31c`）

- **复审结论**：**`APPROVE`**
- **复审对象**：`origin/card/E2a-thread-detail-core` @ `332c31cb207a853fd46bad774824f348e4fa701f`（简称 `332c31c`；已于 `772e4df` 无冲突合入 `origin/main` @ `21f41df`，纯修复差异为 `git diff 772e4df..332c31c`，共 21 个文件改动，0 越界）
- **GitHub Actions CI 核实**：[Android CI #45687741985](https://github.com/mailwmj/hipda-next/actions/runs/45687741985)
  - `headSha`：`332c31cb207a853fd46bad774824f348e4fa701f`（精确匹配）
  - `Build, Lint & Unit Tests`（Job `139464712079`）：**SUCCESS**（`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema、`testDebugUnitTest :core-parser:test -Proborazzi.test.verify=true` 及 APK 产物校验全绿）
- **阻断项**：**0 项**（一轮 `B1` 及 `S1`、`S3`、`S4`、`S5` 全部核实闭合）
- **移交下游卡的非阻断提醒**：**2 项**（`R2-S1`、`R2-S2`）

---

## 一、`B1` 专项安全复核（对照负责人三项重点）

### 1. 渲染层与点击层双层白名单（`PostBodyRenderer.kt:45-68, 98-116, 162-168, 225-243`）
- **渲染层（第 1 层）**：
  - `buildPostTextBlock` 对 `InlineSpan.Link`（`:98-107`）与 `InlineSpan.Email`（`:108-116`）均先调用 `sanitizePostUrl`：返回 `null` 时直接 `append` 纯文本、**不创建 `LinkAnnotation.Url`**、不着色不加下划线；返回合法 URL 时显式挂载非空 `LinkInteractionListener { onLinkClick?.invoke(safeUrl) }`，彻底封死 `onLinkClick == null` 时回退触发 Compose 内置裸 `LocalUriHandler` 的旁路。
  - `PostAttachmentBlockView`（`:227, 238-242`）对 `sanitizePostUrl(attachment.url) == null` 的附件渲染为无 `onClick` 的静态 `Surface`，标题退回 `cs.onSurface`。
- **点击层（第 2 层）**：
  - `PostBodyRenderer` 的 `resolvedLinkHandler`（`:162-168`）在触发 `onLinkClick` 或 `uriHandler.openUri` 前**再次执行 `sanitizePostUrl(rawUrl)`**，双层独立生效。

### 2. 绕过向量逐项穿透核验（`sanitizePostUrl` + `ThreadDetailParser` 联立）
| 绕过尝试 | 拦截机理与落点 | 结果 |
|---|---|---|
| **大小写混写**（`JaVaScRiPt:alert(1)`、`INTENT://`、`FILE://`、`DATA:`、`MAILTO:`） | `:49, 53` 用 `ignoreCase = true` 仅匹配 `mailto:`/`http://`/`https://` 前缀；`HttpUrl` 将合法 `HTTP://` 规范化为小写 `http`/`https`，其余全部返回 `null`。 | **已拦截**（有单测） |
| **前导/尾随/内嵌空白与控制字符**（`" JavaScript:"`、`"\tjavascript:"`、`"java\nscript:"`、`"java\u0000script:"`、`"http://evil .com"`） | `:47` 拦截首尾任意空白字符；`:48` `any { it.isISOControl() }` 拦截全串 `\u0000..\u001F`（含 `\t`/`\n`/`\r`/`\0`）与 `\u007F..\u009F`；`mailto:` 内嵌空格被 `MAILTO_ADDRESS_REGEX` 拒绝；`http(s)://` 主机名内嵌空格被 `toHttpUrlOrNull()` 拒绝，路径空格被百分号编码为 `%20` 不改变 `http(s)` scheme。 | **已拦截**（有单测） |
| **URL 编码危险 scheme**（`java%73cript:alert(1)`、`javascript%3aalert(1)`、`&#106;avascript:alert(1)`） | ① 直传 `sanitizePostUrl`：不以 `http(s)://` 或 `mailto:` 开头，`:53` 直接返 `null`；<br>② 经 `ThreadDetailParser`：HTML 实体解码后 `javascript:` 被 `:53` 拦截；`java%73cript:alert(1)` 被解析器拼为 `https://java%73cript:alert(1)` 后，在 `:56` `toHttpUrlOrNull()` 因 `:alert(1)` 非合法端口返 `null`。 | **已拦截** |
| **协议相对地址**（`//evil.com/path`、`///evil`） | 直传 `sanitizePostUrl("//evil.com/path")` 在 `:53` 返 `null`（单测 `:302, 330` 已锁）；经解析器 `<a href="//evil.com">` 转为标准外链 `https://evil.com`（恶意残缺 `///` 在 `toHttpUrlOrNull()` 返 `null`）。 | **已拦截**（有单测） |
| **`mailto:` 夹带 `javascript` 或头部注入**（`mailto:javascript:alert(1)`、`mailto:a@b.com?bcc=x`） | `:37, 61-68` `isValidMailtoAddress` 限制纯 `[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+`，禁止 `:`、`/`、`?`、`&`、`=`、`#`、`(`、`)` 与空白。 | **已拦截**（有单测） |
| **`http` 链接文本指向危险 `href`**（`<a href="javascript:alert(1)">https://www.4d4y.com</a>`） | `ThreadDetailParser.processAnchorElement`（`:873-888`）生成 `InlineSpan.Link(label="https://...", url="javascript:...")` 且不再对子文本跑 `URL_REGEX`；`buildPostTextBlock`（`:99-102`）对 `span.url` 验白名单得 `null`，仅把 `label` 作纯文本 `append`，`0` 个 `LinkAnnotation`、点击零触发。 | **已拦截** |

### 3. `LinkAnnotation` 之外的导出/交互路径核查
- **无障碍 / TalkBack 链接动作**：因非法链接在渲染层未写入 `LinkAnnotation`，无障碍语义树 `getLinkAnnotations` 为空，无法通过系统无障碍菜单触发危险 URL。
- **`onAuthorClick` 与头像加载（`PostFloorCard.kt:48-52, 94`）**：`onAuthorClick(floor.uid, floor.author)` 仅传业务 ID 与昵称字符串（`ThreadDetailPane` 默认空回调，不走 `Intent`）；`floor.avatarUrl` 经 `DiscuzUrlHelper.avatarRelativePathForUid` 强制校验纯数字 UID（≤9 位），不可能拼出危险 URI。
- **看图与错误复制**：`PostImageBlock` 仅将 `ImageViewerLaunchArgs` 传给应用内看图回调（默认 `{}`，不调系统 `ACTION_VIEW`）；`HiErrorState` 仅以 `ClipData.newPlainText` 写剪贴板；楼层长按复制菜单归属 `E2c` 本卡未涉及。

---

## 二、`S1` 精简后行为等价性及 `S3–S5` 核实

- **生产行数**：`hipda/src/main/` 10 个文件由 `1,404` 行降至 `1,371` 行（S1 删减 ~82 行，B1 新增 ~49 行安全白名单）。
- **`S1` 行为零退化核实**：
  1. `ThreadDetailViewModel.kt:83-95` 改为 2 参主构造 + `@Inject` 3 参次构造：属性 `rawFloors`/`metaState`/`uiState` 先完成初始化后再执行次构造内的 `observeAuthRecovery(authRepository)`，Hilt 注入与 `NextAppTracerBulletTest` 2 参构造均与修前完全等价，且消除了 `UnboundAuthRepository` 空流协程。
  2. `observeAuthRecovery`（`:194-219`）合并为 `combine(auth.currentAccount, auth.authState).drop(1)`：因两者均为 `StateFlow`，首次同步发射初始快照被 `.drop(1)` 精确丢弃，后续任一流更新均触发单协程快照比对，与修前语义一致且无双协程共享变量竞态。
  3. `ThreadDetailPane.kt` 移除 `contentRevision` / `contentAlpha` 后消除了切页首帧闪黑伪影，`AnimatedContent` 空/错/骨架/正文切换与 `只看楼主` 退出原楼像素偏移恢复均保持不变。
  4. `ThreadDetailTopBar.kt:84-88` 底部分隔线合入同一 `drawBehind`，坐标与透明度完全等价；`PostAttachmentBlockView` 与 `PostImageBlock` 改用标准 `Surface`（重录 9 张 `thread_detail_*.png` 圆角 1px 抗锯齿，其余非本卡快照 0 漂移）。
- **`S3` / `S4` / `S5`**：`TextColorContrastAdjusterTest:118-136` 已补白底 `#FF0000`/`#FFFF00` 与纯黑 `#000080` 的 `ratio in 4.5f..5.5f` 及主副通道差 `>= 0.25f` 断言；`_reports/E2a.md:108` 已对齐 `onAuthorClick`；`PostBodyRenderer.kt:189-190` 已改传 `cs.background`。

---

## 三、移交 Wave 3 下游卡的非阻断提醒

- **R2-S1（带入 Wave 3a `E2b`）**：`PostImageBlock` 接入真实 Coil 加载时，对 `image.fullUrl` / `image.thumbUrl` 同样校验仅加载 `http`/`https`（防止恶意 `<img src="file:///...">` 或 `content://` 触发 Coil 本地 URI 读取）。
- **R2-S2（带入 Wave 3b `E2c`）**：扩充 `PostFloorCard` 作者区点击热区至 `≥ 48dp` 并合并用户名无障碍描述；`FloorContextMenu` 复制操作仅使用 `ClipData.newPlainText`。
