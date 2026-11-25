# 独立验证报告 V-E2a：帖子详情核心完整版视觉复验与行为探针（`60bd5b7`）

- **总判定**：**`PASS`**（无阻断项）
- **被测对象**：`origin/card/E2a-thread-detail-core` @ `60bd5b7`（相对基线 `db7dba6`）
- **验证工作区与分支**：`/home/mir/Documents/hipda-next.wt/V-E2a`，分支 `verify/E2a-probes`，探针提交 `2ddfbddc9951f1baf27fe3988ade87efbc81e0a2`
- **GitHub Actions CI**：[Android CI #45683767762](https://github.com/mailwmj/hipda-next/actions/runs/45683767762)
  - `headBranch`：`verify/E2a-probes`
  - `headSha`：`2ddfbddc9951f1baf27fe3988ade87efbc81e0a2`
  - `Build, Lint & Unit Tests`（Job `139450834609`）：**`success`**（耗时 13m4s；`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿；`Check for Roborazzi diff images` 为 `skipped` 表示零视觉差异；汇总 **`TOTAL: 1013 tests, 1013 passed, 0 failed, 0 errors, 0 skipped`**，其中 `E2aProbeTest` **`6/6`**）
- **本地串行校验（遵循 `docs/agents/DISPATCH.md` §2 `flock` + `-Xmx3g` + `--stop`）**：
  - `:core-parser:test :hipda:testDebugUnitTest -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug`：**`BUILD SUCCESSFUL`**（2m12s）
  - `:hipda:testDebugUnitTest`：**`915 tests, 915 passed, 0 failed, 0 skipped`**（含 `E2aProbeTest` `6/6`、`finalizeTestRoborazziDebug`、`CopyTerminologyScanTest`、`MotionTokenScanTest`、`NextAppTracerBulletTest`）
  - `hipda/build/reports/lint-results-debug.sarif` 核查：`threaddetail` 与 `E2aProbeTest` **0 条 Lint 告警**
- **独立性声明**：本次验证全程未阅读 `_reports/E2a.md` 及实现者测试（`ThreadDetailViewModelTest.kt`、`PostBodyRendererTest.kt`、`TextColorContrastAdjusterTest.kt`、`ThreadDetailScreenshotTest.kt`）；全部结论来自 `git diff` 核查、生产源码走读、对全部 15 张基线截图与自录 `749dp` 三栏读帖截图的逐张目视检查，以及独立离线探针 [E2aProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt) 实测输出（严禁访问真实论坛 `4d4y.com`）。

---

## 一、快照基线零漂移核查

- 执行 `git diff --name-status db7dba6...60bd5b7 -- hipda/src/test/snapshots/`：
  - 仅新增 **15** 张 `hipda/src/test/snapshots/images/thread_detail_*.png`；
  - 既有全部基线（`theme_specimen_*.png`、`shell_*.png`、`glass_*.png`、`challenge_*.png`）**0 新增、0 修改、0 删除（0 漂移）**；
  - 本地与 CI `-Proborazzi.test.verify=true` 均全绿通过。
- 验证分支增量核对（`git diff --name-status 60bd5b7..2ddfbdd`）：仅新增 `hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt`，未修改任何生产代码或快照基线，未向 `card/E2a-thread-detail-core` 或 `main` 推送。

---

## 二、15 张 `thread_detail_*.png` 逐张目视检查

### 2.1 负责人重点项专项目视结论

1. **浅色、深色、OLED 纯黑三主题对照**（[w411_light](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_light.png)、[w411_dark](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_dark.png)、[w411_oled_pure_black](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_oled_pure_black.png)、[pane448_dark](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_pane448_dark.png)、[pane680_light](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_pane680_light.png)、[w933_skeleton_dark](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w933_skeleton_dark.png)）：
   - **浅色**：纯净极浅灰白背景，文字黑色锐利，图片占位框、附件卡片、引用块使用柔和中性容器底色与 `0.5dp` 细描边区分层级；顶栏滚过文字后呈轻量磨砂玻璃，两枚 `40dp` 圆形动作钮底色轻透且轮廓清晰。
   - **深色**：标准深灰蓝黑表面，正文与次级时间/编辑后缀层次清楚，引用块与附件容器与背景对比适中；暗色玻璃顶栏下滚动文字隐约透出但不干扰标题 `折腾折叠屏客户端：Compose 原生正...` 与副行 `楼主 · linus_hi`。
   - **OLED 纯黑**：背景为纯黑 `#000000`，引用块、图片占位块与附件卡片使用压暗后的纯黑容器阶梯色与细边框，边界清楚而不发灰；顶栏圆钮（`←`、`👤`）外框与图标清晰可辨，底部分页条在纯黑屏底上对比鲜明。

2. **极端文字色可读性实测检查**（同一批样张内 `极端色动态校准：纯黑#000000 深蓝#000080 明黄#FFFF00 纯白#FFFFFF 正红#FF0000`）：
   - **浅色底**（`w411_light`、`w559_light`、`w411_step5_scale13_light`、`pane411_filter_light`、`pane680_light`）：`纯黑#000000` 与 `深蓝#000080` 保持本色锐利；原本白底极难辨识的 `明黄#FFFF00` 被收暗为沉稳橄榄黄，`纯白#FFFFFF` 被校准为清晰中深灰，`正红#FF0000` 微幅降明呈饱和正赤红，全部肉眼轻松可读且保留原始色相辨识。
   - **深色底 & OLED 纯黑底**（`w411_dark`、`w411_oled_pure_black`、`w640_dark`、`pane448_dark`）：原本黑底隐身的 `纯黑#000000` 被提亮为中性浅灰，`深蓝#000080` 提亮为明亮钴蓝，`明黄#FFFF00` 与 `纯白#FFFFFF` 保持高亮度，`正红#FF0000` 提亮为清晰珊瑚红；在标准深灰与 `#000000` 纯黑上均无发暗糊成一片的问题。

3. **三套论坛表情与补充平面 Emoji 检查**（`😁`、`coolmonkey` 猴、`grapeman` 蓝圆脸及 `👌`）：
   - **基线对齐**：行内表情随正文行内尺寸自适应（`1.4 × fontSize`），垂直居中对齐汉字行（`TextCenter`），在普通字号（16sp）与第 5 档 × 1.3 倍字号下均无上下切边或拉裂行距。
   - **深浅/OLED 主题切换**：浅色样张使用浅底标准资源；深色（`w411_dark`、`w640_dark`、`pane448_dark`）与 OLED 纯黑（`w411_oled_pure_black`）样张中 `coolmonkey`（1 楼与 2 楼句尾）和 `grapeman` 均呈现深色专用资源（描边与高光过渡干净，黑底上无刺眼白边/锯齿毛边）。

4. **第 5 档字号 × 1.3 系统字体缩放检查**（[thread_detail_w411_step5_scale13_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_step5_scale13_light.png)）：
   - **顶栏钳制**：顶栏包裹 `HiCappedFontScale`（上限 `1.25×`），60dp 顶栏内标题与副行 `楼主 · linus_hi` 稳定居左、未与左右两枚 `40dp` 圆钮碰撞，圆钮尺寸与描边无变形。
   - **过滤胶囊**：`只看楼主 ×` 胶囊文本与 `×` 按钮（48dp 触控区）保持单行紧凑、无截断。
   - **楼层头与正文**：`L` 圆形头像、`linus_hi` + `楼主` 角标、时间行与右侧 `1楼`（及底部露出的 `4楼`）留白充足不拥挤；1 楼正文与行内表情随 `18sp × 1.3` 同步放大，极端色校准文与链接自然换行，图片占位框、附件卡片、编辑时间与下一楼衔接整齐，**0 裁切、0 重叠、0 横向溢出**。

### 2.2 全部 15 张截图逐张核查清单

| # | 文件名 | 尺寸（px → dp @ 2x） | 状态/场景 | 逐张目视核查结论 |
|---|---|---|---|---|
| 1 | [thread_detail_w411_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_light.png) | `822×1782` (`411×891`) | 单栏浅色滚动后 | 透明顶栏现轻磨砂玻璃+底线，左右圆钮清晰无双层模糊；5 极端色、3 套表情+`👌`、链接/邮箱、4:3 占位框、附件、编辑注、2 楼头像/`2楼`/引用/`回复 1#`/末尾猴表情及分页条均衡 |
| 2 | [thread_detail_w411_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_dark.png) | `822×1782` (`411×891`) | 单栏深色滚动后 | 暗色透明顶栏透出下方滚动字迹但标题仍锐利；`#000000`/`#000080`/`#FF0000` 动态提亮清晰，深色表情无白边，分页条对比清晰 |
| 3 | [thread_detail_w411_oled_pure_black.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_oled_pure_black.png) | `822×1782` (`411×891`) | 单栏 OLED 纯黑滚动后 | 列表底色为纯黑 `#000000`；黑/深蓝/红文字可读，图片占位/附件/引用容器靠暗阶与细边与纯黑背景区分，顶栏圆钮轻底边界清晰 |
| 4 | [thread_detail_w411_step5_scale13_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_step5_scale13_light.png) | `822×1782` (`411×891`) | 第 5 档字号×1.3 + `只看楼主` | 顶栏与过滤胶囊受缩放钳制不挤占，`1楼`/`4楼` 角标与 `linus_hi 楼主` 不拥挤；大字号正文与表情等比放大并自然折行，零裁切溢出 |
| 5 | [thread_detail_w559_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w559_light.png) | `1118×1498` (`559×749`) | 折叠屏竖拿浅色 | 559dp 单栏顶栏展现更多标题字符，4:3 图片占位随栏宽同比例舒展，下方附件/编辑注/分页条对齐协调 |
| 6 | [thread_detail_w640_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w640_dark.png) | `1280×2048` (`640×1024`) | 2 栏深色内容区（`showBack=true`） | 完整显示主帖长标题与双圆钮；1 楼与 2 楼全貌同屏展开，深色表情、极端色、引用与 `回复 1#` 排版均衡，640dp 内含 16dp 安全边距 |
| 7 | [thread_detail_pane411_filter_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_pane411_filter_light.png) | `822×1118` (`411×559`) | 三栏右栏 411dp + `只看楼主` | 无返回钮，右侧同时展示 `全屏阅读` 与激活态（主题色边框+图标）`只看楼主` 圆钮；下方 `只看楼主 ×` 胶囊与 1 楼顶部留白精准不遮挡 |
| 8 | [thread_detail_pane448_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_pane448_dark.png) | `896×1392` (`448×696`) | 三栏右栏 448dp 深色滚动后 | 无返回钮，右置 `全屏阅读` + `只看楼主` 圆钮轻底透亮；滚动后玻璃顶栏层次干净，1 楼与 2 楼头部衔接、底部分页条比例稳定 |
| 9 | [thread_detail_pane680_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_pane680_light.png) | `1360×1600` (`680×800`) | 平板三栏右栏 680dp 浅色 | 顶栏完整展现标题，`ShowList`/`FullScreen` 与 `只看楼主` 圆钮居右；正文恰好在 680dp 最大行宽内（净宽 648dp）舒适铺开，4:3 占位不变形 |
| 10 | [thread_detail_w411_empty_filter_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_empty_filter_light.png) | `822×1782` (`411×891`) | 411dp 浅色过滤空态 | 顶栏下方常驻 `只看楼主 ×` 胶囊；内容区居中展示 `没有符合条件的楼层` 与按钮 `看全部`，符合 `copy.md` 规范 |
| 11 | [thread_detail_w933_empty_filter_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w933_empty_filter_dark.png) | `1866×1392` (`933×696`) | 933dp 深色过滤空态 | 宽屏下顶栏双按钮居右、`只看楼主 ×` 胶囊居左对齐，居中空态在宽画布垂直水平居中无偏移 |
| 12 | [thread_detail_w411_error_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_error_light.png) | `822×1782` (`411×891`) | 411dp 浅色错态 | 居中展示 `网络连接失败` + `重试` 按钮 + 折叠的 `查看详情`，主提示简洁无技术异常泄漏 |
| 13 | [thread_detail_w933_error_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w933_error_dark.png) | `1866×1392` (`933×696`) | 933dp 深色未登录错态 | 居中展示 `需要登录才能查看` + `登录` 按钮 + `查看详情`，深色对比清晰、位置居中 |
| 14 | [thread_detail_w411_skeleton_light.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w411_skeleton_light.png) | `822×1782` (`411×891`) | 411dp 浅色骨架态 | 4 组楼层骨架（36dp 圆形头像骨架 + 双行头 + 三行不同宽度正文条）整齐排列于顶栏下方，顶部留白匹配真实内容 |
| 15 | [thread_detail_w933_skeleton_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/snapshots/images/thread_detail_w933_skeleton_dark.png) | `1866×1392` (`933×696`) | 933dp 深色骨架态 | 顶栏铺满 933dp，4 组骨架块受 `680dp` 最大宽约束并水平居中于 933dp 屏内，布局比例稳健 |

---

## 三、`749dp` 三栏读帖实景截屏核查

- 截屏方法：`E2aProbeTest.visual_captureThreePane749dp_threadDetail_forManualInspection`（[E2aProbeTest.kt#L221](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L221)）
- 输出文件：[v_e2a_w749_h559_three_pane_thread_detail.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E2a/v_e2a_w749_h559_three_pane_thread_detail.png)（在 `NextAppRoot` + `ProvideGlassEnvironment(RefractionAndBlur)` + `MockWebServer` 离线消费脱敏样本 `forum_2_p1_lastpost.html` 与 `thread_p1.html` 下录制）
- 目视结果：
  - **左栏（`64dp` 收起图标轨）**：高亮当前版块 `D`（Discovery），底栏固定 `H` 头像与功能图标区清晰无拥挤；
  - **中栏（`274dp` 帖子列表）**：正确隐藏横向标签行，选中高亮项 `发帖原则，禁泛政治化贴，新规严禁股票基金推荐、拉群！` 与右栏主帖完全对应，右侧由 `1dp` 细线分隔；
  - **右栏（`410dp` 帖子详情 `E2a` 完整版）**：极简透明顶栏正确隐藏返回钮、右置 `40dp` 轻底圆形 `只看楼主` 钮；`1楼 zhuyi`（含 `楼主` 胶囊、`2019-10-7 10:44`、`1楼` 右对齐）、原生加粗黑字与红色规则正文排版宽松清晰；底部显示 `上一页 / 第 1 / 10 页 / 下一页`，全屏 `HiGlassMarker <= 3` 通过。

---

## 四、独立行为探针结果（[E2aProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt)）

| 探针 | 用例名（行号） | 验证要点 | 实测结论 |
|---|---|---|---|
| **a. 极端文字色三主题 WCAG 对比度（B4-E2a ②）** | `probeA_extremeTextColors_acrossLightDarkAndOledPureBlack_allMeetWcag45` ([L269](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L269)) | `#000000`、`#000080`、`#FFFF00`、`#FFFFFF`（及 CSS 命名色）在浅色、深色、OLED 纯黑（验证 `surface == Color.Black`）下调色对比度全覆盖 `≥ 4.5:1`；扩展全 20 套主题预设 × 3 模式 × 8 色；验证原可读色无过度失真；并在真实 `PostBodyRenderer` Compose 树内读取 `SemanticsProperties.Text` 的 `SpanStyle.color` 验证渲染期动态重算 | **PASS**（全部 12 组必检组合、480 组 20 预设矩阵与渲染期语义树着色全部 `≥ 4.5:1`） |
| **b. 原生富文本、3 套表情深浅切换、上下页与只看退出定位** | `probeB_nativeRichTextEmojiThemeSwitch_pagination_andAuthorOnlyFilterRestore` ([L392](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L392))<br>`probeB_realSampleEmojisAndAuthRequiredAutoReload` ([L601](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L601)) | 含 `default_lol` / `coolmonkey_01` / `grapeman_08` 三套表情 `normalizedKey`、粗/斜/下划线/删除线、`LinkAnnotation.Url` + 邮箱点击触发 `LocalUriHandler`、`\n`、`GoToFloor` 与极端色；切至中段楼层 `(5, 29)` 实时切 `Dark + pureBlack=true` 后核对像素/表情节点实时变化与零重拉零丢位；测 `上一页`/`下一页`；第 2 页滚至 `(4, 33)` 开启 `只看楼主` 出 `只看楼主 ×` 胶囊，点 `×` 退回第 2 页且精确回到 `(4, 33)`；空过滤显示 `没有符合条件的楼层` + `看全部`；真实 `thread_p1.html` 3 套表情命中与 `AuthRequired` 登录后自动重载 | **PASS**（切主题后楼层 `(5, 29)` 0 丢失、请求增量 0、表情与文本像素实时变化；切页正常；点 `×` 精确回第 2 页 `(4, 33)`；空态与 `看全部` 正常；`AuthRequired` 登录后自动发起第 2 次请求并显示 `1楼`） |
| **c. 玻璃拓扑、圆钮触控区与 Wave 3 `E2b` 接缝（B2-2）** | `probeC_glassTopology_strictSiblings_circleButtonTouchTargets_andE2bSeam` ([L672](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L672))<br>`probeC_wideScreen933dp_centersPostContentAtMax680dp` ([L792](file:///home/mir/Documents/hipda-next.wt/V-E2a/hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt#L792)) | 在 `RefractionAndBlur` 环境下检查语义树与 `LayoutInfo`：硬断言 `HiGlassMarker == 1`、`HiGlassBlurActive == true`；`TopBar` 与 `LazyColumn` 互不为祖先（严格兄弟）；全树仅 1 个 `LayerBackdrop` 且位于 `LazyColumn` 上不包 `TopBar`；仅 1 个 `DrawBackdropElement` 且位于 `TopBar`；`返回`/`全屏阅读`/`只看楼主` 3 枚圆钮零 `HiGlassMarker`/`HiGlassBlurActive`/`DrawBackdropElement` 且触控区均 `≥ 48×48dp`；反射与 UI 校验 `PostImageBlock` 签名与 4:3 占位挂载及点击回调；核对 `LazyListState.layoutInfo.visibleItemsInfo` 全部使用 `"floor:$postId"` 键；验证 `933dp` 下正文 `≤ 680dp` 对称居中 | **PASS**（全项硬断言通过） |

---

## 五、非阻断观察注记

1. 749dp 实景截屏中三栏 `DetailChromeSpec` 仅传递 `showBack = false`、暂未出现 `全屏阅读` 圆钮，因 `NextAppRoot`/`BasicMultiPaneContainer` 当前仍为 `E0` 基座版本，`fullScreenState` 将按 `BATCH-5.md` 规划在 Wave 3 `E3` 接入；在 `E2a` 自带的 `pane411_filter_light` / `pane448_dark` / `pane680_light` 及 `E2aProbeTest` 探针 `c` 中已独立验证 `fullScreenState` 传参时 `全屏阅读 / 显示列表` 圆钮及其触控区与零嵌套玻璃行为均正常。
2. 未滚动状态下 `ThreadDetailTopBar` 底缘可见极细 0.5dp 轮廓线，源于 `HiGlassSurface` 激活态外围描边绘制在内容覆盖层之上（与 `HiGlassSurface.kt:594-612` 一致），浅/深/纯黑三色均干净无双层模糊痕迹。
