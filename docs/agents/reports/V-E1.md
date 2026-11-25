# V-E1 验证报告 — M5 论坛页完整版（`origin/card/E1-forum-page @ 2e8bed9`）

## 1. 总结论与验证环境

- **总结论**：**`FAIL`**（命中 1 个阻塞性真实渲染缺陷：**深色主题及 OLED 纯黑下论坛列表骨架屏缺少 `LocalContentColor`，导致骨架块与背景几乎同色/纯黑完全不可见**；同时 22 张 `forum_*.png` 基线截图**缺少**铰链双栏纯色顶栏场景覆盖；另记录 1 项次要 UX 观察。）
- **被测只读对象**：`origin/card/E1-forum-page @ 2e8bed9`（基线 `db7dba6`；worktree `/home/mir/Documents/hipda-next.wt/V-E1`，未修改任何 `src/main/`、`src/debug/`、`hipda/src/test/snapshots/` 或既有测试源码）
- **独立探针分支与代码**：`verify/E1-probes @ e914168ca74ab04639682fb92530aadff3394144`，仅新增 [E1ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt)（含 `E1ProbeTest` 与 `E1ReducedMotionProbeTest`）
- **GitHub Actions CI**：[Run #45689675641](https://github.com/mailwmj/hipda-next/actions/runs/45689675641)（`workflow_dispatch` 触发于 `verify/E1-probes`）
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）：**通过**
  - `Verify Room schema consistency`：**通过**
  - `Check for Roborazzi diff images`：**通过（0 差异图，全站快照基线零漂移）**
  - `Run unit tests and verify Roborazzi screenshots`：**`1029 tests, 1028 passed, 1 failed, 0 errors, 0 skipped`**（其中 `:hipda:testDebugUnitTest` 为 `931 tests, 930 passed, 1 failed`，唯一失败为本轮主动拦截缺陷的 `visual_darkAndPureBlackSkeleton_mustRenderVisibleSkeletonBlocksOverBackground`）
- **独立性与访问约束核对**：
  - 未读取 `/home/mir/Documents/hipda-next.wt/_reports/E1.md`，未读取 `hipda/src/test/java/net/jejer/hipda/feature/forum/*` 源码。
  - 全程 100% 离线执行（`MockWebServer` + `Room.inMemoryDatabaseBuilder` + 本地内存 Fake 仓库 + `WindowInfoTracker` 装饰器），严禁且未访问 `4d4y.com`。
  - `git diff --stat db7dba6...2e8bed9 -- hipda/src/test/snapshots/`：仅新增 22 张 `forum_*.png`，非 `forum_*` 快照（`theme_specimen_*` / `shell_*` / `glass_*` / `challenge_*`）**0 改动、0 漂移**；本地与 CI `Roborazzi verify` 全通过。

---

## 2. 视觉审视：22 张 `forum_*.png` 基线与新增诊断图

### 2.1 负责人专项点名审视（逐张核查结论）

1. **透明顶栏的滚动前 vs 滚动后（PASS）**
   - **滚动前**：[forum_w411_h891_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_light.png) 顶部双行展开为实色 `surface` 纯白底，`Discovery` 标题、发新帖/更多排序图标按钮、标签行与底部导航胶囊完整展示，列表首项从安全内容边距下方清晰起排。
   - **滚动后**：[forum_w411_h891_scrolled_collapsed_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_scrolled_collapsed_light.png) 上行完全收起，仅保留 `BalancedForumTabRow` 吸顶并切换为透明磨砂玻璃态（`HiGlassTopBar`），背后滚动上去的列表行文字与圆圈明显半透明模糊透出；`有 3 条新帖` 悬浮胶囊紧贴在吸顶标签行下方且不遮挡标签文字，底部导航胶囊随下滚自动隐藏。
   - **补截 411dp 列表中段实景**：[v_e1_w411_h891_mid_scroll_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w411_h891_mid_scroll_light.png) 显示滚动到第 17–24 帖时，第 17 帖标题清晰半透在玻璃吸顶栏背后，主体 `#18`–`#24` 排版均匀，底部胶囊隐藏。

2. **荧光笔标签行（PASS）**
   - 核查 [forum_w411_h891_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_light.png)、[forum_w411_h891_scrolled_collapsed_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_scrolled_collapsed_light.png)、[forum_w340_h800_narrow_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w340_h800_narrow_light.png) 与 [forum_w411_h891_step5_font130_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_step5_font130_light.png)：
     - 选中页签（`Discovery`）高亮采用低饱和 `primaryContainer` 水平渐变荧光笔（浅色为 `#D5F1FF`/`#D8F2FF` 系，深色保持低 alpha 护眼底色），未出现 `#FFB003` 高饱和品牌黄；文字权重（加粗选中 / 常规未选）与底色高对比易读。
     - `340dp` 窄宽自动切为横向可滚动标签条且右侧 `+`（管理版块）按钮固定不随内容滚出；`step5 × 1.3` 字体缩放下顶栏与标签栏被安全限制，长作者名温和省略而保留完整 `128/6.4k` 与时间。

3. **窄于 300dp 的紧凑副行（PASS）**
   - 核查 [forum_w749_h559_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w749_h559_light.png) / [forum_w749_h559_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w749_h559_dark.png)（中栏 `274dp < 300dp`）以及 [forum_w933_h696_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w933_h696_light.png) / [forum_w933_h696_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w933_h696_dark.png)（中栏 `298dp < 300dp`）：均取消独立 `40dp` 左侧头像列，标题全宽起排，并将 `20dp` 小头像内嵌到元信息副行首位，与作者、回复/查看数、时间保持单行对齐。
   - 对比 [forum_w1280_h800_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w1280_h800_light.png) / [forum_w1280_h800_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w1280_h800_dark.png)（中栏 `380dp >= 300dp`），正确保留 `40dp` 独立头像列，`< 300dp` 阈值分界准确。
   - **补截 749dp 三栏实景**：[v_e1_w749_h559_three_pane_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w749_h559_three_pane_light.png) 展示左 `56dp` Rail、中栏 `274dp` 紧凑副行列表（选中第 2 帖高对比高亮，不重复显示版块标签行）与右栏真实阅读页（`1楼 linus_cn` ~ `5楼` 及底部 `上一页 第 1/2 页 下一页`），三栏间距与层次均整洁。

4. **深色主题（内容态 PASS；骨架屏态 BLOCKING FAIL）**
   - **内容/空态/错误态（`w411`、`w559`、`w640`、`w749`、`w933`、`w1280` 的 `*_dark.png` 与 `*_empty_dark.png`、`*_error_dark.png`）**：已读标题（`old_timer` 等）以 `FontWeight.Normal` + `onSurfaceVariant.copy(alpha = 0.74f)` 弱化并与未读加粗亮字形成明显梯度且不失可读性；选中行、分隔线与徽章在深色下界线清晰。
   - **阻塞缺陷（深色 & OLED 纯黑骨架屏隐身）**：
     - 目视对比 [forum_w933_h696_skeleton_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w933_h696_skeleton_dark.png) 与设计系统基线 [state_skeleton_w411_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/state_skeleton_w411_dark.png)：`forum_w933_h696_skeleton_dark.png` 中栏骨架块几乎完全融入深色背景（`#0D1316` 叠在 `#0E1417` 背景上，而 `state_skeleton_w411_dark.png` 正确呈现 `#282E31` 叠在 `#0E1417` 上）。
     - 独立探针复现截图 [v_e1_w933_h696_skeleton_dark.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_dark.png)（深色：`bg=#111215, maxDelta=1, brighterPixels=0`）与 [v_e1_w933_h696_skeleton_pure_black.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_pure_black.png)（OLED 纯黑：`bg=#000000, maxDelta=0, brighterPixels=0`）中，中栏骨架区几乎或完全黑屏消失。
     - **触发链路**：[HiTheme.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiTheme.kt#L156) 未提供 `LocalContentColor`，且 [HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L288-L291) 用带 `Modifier.background(...)` 的 `Box` 而非 `Surface` 包裹页面，[ForumPane.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumPane.kt#L242-L248) 中的 `ForumListSkeleton` 也未通过 `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface)` 显式提供内容前景色，导致 [HiSkeleton.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt#L50)（`LocalContentColor.current`）回落为 Compose 默认 `Color.Black`，黑色以低透明度叠在深灰/纯黑底上完全不可见。

5. **铰链纯色顶栏（基线缺失 + 探针与诊断图实证 PASS）**
   - **基线覆盖缺口**：`22` 张 `forum_*.png` 快照中**未包含**纵向折叠屏铰链双栏（`isSeparating=true`）场景截图（现有基线全为 `w340`/`w411`/`w559`/`w640`/`w749`/`w891`/`w933`/`w1280` 常规形态）。
   - **实测截图与拓扑校验**：探针 `probeB_realNextAppRootHingeTwoPane_topBarZeroGlass_andCompactPillOnly` 注入 `FoldingFeature(VERTICAL, HALF_OPENED, isSeparating = true)` 并截取 [v_e1_hinge_two_pane_solid_topbar_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_hinge_two_pane_solid_topbar_light.png)，目视与语义共同验证左栏为纯白实色 `TopBar` + 完整 `BalancedForumTabRow`，右栏为空态占位，整棵 UI 树仅底部 `CompactPill` 拥有 `1` 个 `HiGlassMarker`（顶栏玻璃节点严格为 `0`）。

### 2.2 22 张基线清单一览

| 截图 | 形态 | 结论 | 说明 |
|---|---|---|---|
| `forum_w340_h800_narrow_light` | 340dp 窄手机浅色 | PASS | 横向可滚标签 + `+` 固定 + 底栏隐去未选文字 |
| `forum_w411_h891_light` | 411dp 标准浅色 | PASS | 展开双行实色 `surface` 顶栏 + 渐变荧光笔 + 底部玻璃胶囊 |
| `forum_w411_h891_dark` | 411dp 标准深色 | PASS | 未读/已读对比鲜明，层次清楚 |
| `forum_w411_h891_scrolled_collapsed_light` | 411dp 上行折叠态 | PASS | 标签栏吸顶转玻璃透出列表 + `有 3 条新帖` 胶囊 + 底栏隐藏 |
| `forum_w411_h891_step5_font130_light` | 411dp step5×1.3 字号 | PASS | 顶栏/标签抗放大不破版，长作者优雅省略 |
| `forum_w411_h891_empty_light` | 411dp 空态 | PASS | 空状态居中与操作按钮清晰 |
| `forum_w411_h891_error_light` | 411dp 错误态 | PASS | 错误主文与重试按钮清晰 |
| `forum_w411_h891_skeleton_light` | 411dp 浅色骨架 | PASS | 浅色下默认黑+alpha 呈浅灰块，误打误撞可见 |
| `forum_w559_h749_light` / `_dark` | 559dp 中屏（单栏+Rail） | PASS | 无顶部标签行，列表居中/铺排均正常 |
| `forum_w640_h1024_light` / `_dark` | 640dp 平板 | PASS | 左侧自适应导航区 + 列表区稳定 |
| `forum_w749_h559_light` / `_dark` | 749dp 三栏（中栏 274dp） | PASS | 触发 `< 300dp` 紧凑副行，20dp 内嵌小头像整齐 |
| `forum_w891_h411_landscape_light` | 891×411 横屏手机 | PASS | 紧凑横屏布局不拥挤、不裁切 |
| `forum_w933_h696_light` / `_dark` | 933dp 三栏（中栏 298dp） | PASS | `< 300dp` 紧凑副行准确命中 |
| `forum_w933_h696_empty_dark` | 933dp 深色空态 | PASS | 空态在三栏中栏清晰显示 |
| `forum_w933_h696_error_dark` | 933dp 深色错误态 | PASS | 错误文案与按钮对比充分 |
| `forum_w933_h696_skeleton_dark` | 933dp 深色骨架 | **FAIL** | 骨架块 `#0D1316` 几乎与背景 `#0E1417` 融为一体 |
| `forum_w1280_h800_light` / `_dark` | 1280dp 大屏（中栏 380dp） | PASS | `>= 300dp` 恢复独立 `40dp` 头像列 |

---

## 3. 行为与状态探针结果（`verify/E1-probes @ e914168`）

| 探针 / 测试方法 | 验证内容与关键断言 | 结果 | 代码位置 |
|---|---|---|---|
| **阻断视觉缺陷探针** `visual_darkAndPureBlackSkeleton_mustRenderVisibleSkeletonBlocksOverBackground` | 真实渲染深色与 OLED 纯黑骨架，像素采样对比背景亮度 delta >= 12 | **FAILED**（真实缺陷） | [E1ProbeTest.kt#L308-L392](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L308-L392) |
| **探针 a** `probeA_asyncAccountHydration_recoversForumAndTodayNew_sameAccountRelogin_logoutReset_andSlowSwitch` | `currentAccount=null` 遇 `AuthRequired` -> Tink 水合发 `SavedAccount` 自动恢复清除错误；同 `uid` `SessionExpired -> SignedIn`（更新 `lastLoginAt`）自动重拉（并发现下述次要 UX 观察）；`TodayNew` 切号即时清空不残留；慢网切版块 0 帧误闪“暂无帖子” | **PASSED** | [E1ProbeTest.kt#L394-L556](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L394-L556) |
| **探针 b-1** `probeB_topBarCollapseZeroJump_strictSiblingGlassTopology_andScrollRecompositionBudget` | 展开/下滚折叠/上划恢复三态 `beforeContentPadding` 差值严格 `0dp`；单栏玻璃 `<= 2`（顶栏+底胶囊）且 `LayerBackdrop` 与 `HiGlassMarker` 严格兄弟不互套；折叠态上行滑出而标签行与 `+` 吸顶 | **PASSED** | [E1ProbeTest.kt#L558-L636](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L558-L636) |
| **探针 b-2** `probeB_hingeTwoPane_solidTopBarHasZeroGlassNodes` | 直接以 `useGlassTopBar=false` 验证 `ForumPane` 为 0 玻璃节点与 0 `LayerBackdrop` | **PASSED** | [E1ProbeTest.kt#L638-L662](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L638-L662) |
| **探针 b-3** `probeB_realNextAppRootHingeTwoPane_topBarZeroGlass_andCompactPillOnly` | 假纵向分离铰链下 `NextAppRoot` 仅底胶囊有 1 玻璃、实色顶栏 0 玻璃、生成实拍诊断图 | **PASSED** | [E1ProbeTest.kt#L666-L704](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L666-L704) |
| **探针 b-4** `E1ReducedMotionProbeTest.probeB_reducedMotion_zeroMsSingleFrame_andZeroScrollRecomposition` | `animator_duration_scale=0f` 切最右标签滚入视野与新帖胶囊滚顶均 1–2 帧即位；连续滚动期间 `ForumPane` 顶层重组增量 `== 0` | **PASSED** | [E1ProbeTest.kt#L1271-L1339](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1271-L1339) |
| **探针 c** `probeC_todayNewSearchIdPagination_andRealBlacklistAndRoomBrowseHistoryReactiveUpdates` | `MockWebServer` 真实加载 `Forum(2)` + 切 `TodayNew` 隐藏排序更多按钮、第 1 页 `searchId=null` 返回 `"sid-p1-exact-777"`、第 2 页携带且刻意返回 `null` 后第 3 页仍保留 `"sid-p1-exact-777"`；运行期拉黑实时消贴（0 额外分页/搜索请求）；运行期真实 Room `BrowseHistoryDao.upsert("9001")` 立即降为 `FontWeight.Normal`（0 额外分页/搜索请求） | **PASSED** | [E1ProbeTest.kt#L714-L1000](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L714-L1000) |
| **探针 d-1** `probeD_sortPersistence_refreshNewThreadsPill_narrow280dpCompactLayout_andVc1Contrast` | 版块 2 与版块 6 排序互不串联且在重建 VM / 切回版块后从 `SettingsRepository` 持久化恢复；刷新新增 3 帖显示 `有 3 条新帖` 不拽顶、点击后滚顶收起 | **PASSED** | [E1ProbeTest.kt#L1005-L1078](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1005-L1078) |
| **探针 d-2** `probeD_narrow280dpMiddlePane_rendersCompactSubRowWithoutOverflowOrLargeAvatar` | `280dp < 300dp` 验证 20dp 小头像与作者/统计/时间同排、长作者省略而不挤压 `128/6.4k` 与 `10分钟前`（字形实际绘制宽均未超盒宽、单行无垂直溢出） | **PASSED** | [E1ProbeTest.kt#L1082-L1169](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1082-L1169) |
| **探针 d-3** `probeD_all20Presets_vc1ReadUnreadWcagContrastAndTabHighlighterAlphaGuard` | 全 20 套 `HiColorPresets` × `{light, dark, pureBlack}` × `{unselected, selected}`：未读/已读对比度均 >= `4.5:1`、深色与 OLED 已读弱化差 >= `4.0`；荧光笔非 `#FFB003`、alpha 未越上限且选中文字对比度 >= `4.5:1` | **PASSED** | [E1ProbeTest.kt#L1173-L1249](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1173-L1249) |
| **诊断截图采集** `visual_capture411dpMidScroll_forManualInspection` & `visual_capture749dpThreePane_forManualInspection` | 分别生成 `411dp` 列表中段滚动图与 `749dp` 真实三栏阅读图 | **PASSED** | [E1ProbeTest.kt#L242-L304](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L242-L304) |

---

## 4. FAIL 触发路径与其它发现

### 4.1 阻塞性 FAIL：`ForumListSkeleton` 深色/纯黑骨架块隐身

- **触发路径**：`ForumPane` 加载中且 `items.isEmpty()`（例如冷启动或切版块首次等待网络、且由 [HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L288-L291) 以 `Box.background(...)` 承接内容时）→ `ForumPane` 在未提供 `LocalContentColor` 的情况下渲染 [ForumListSkeleton](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumPane.kt#L242-L248) → [HiSkeletonBlock](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt#L50) 读取 `LocalContentColor.current`，因 [HiTheme.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiTheme.kt#L156) 也未设置 `LocalContentColor`，回落至默认 `Color.Black` → 以微弱 alpha 叠加在深色背景 `#0E1417`/`#111215` 上产生比背景更暗的 `#0D1316`（`maxDelta=1, brighterPixels=0`），在 OLED 纯黑 `#000000` 上完全全黑消失（`maxDelta=0, brighterPixels=0`）。
- **修复方向供负责人/执行卡参考**：在 `HiTheme` 根部（`CompositionLocalProvider` 中提供 `LocalContentColor provides colorScheme.onSurface`）及/或 `HiAdaptiveShell` 内容容器/`ForumListSkeleton` 显式提供 `LocalContentColor`（或改用 `Surface`），并顺带补充铰链双栏的 `forum_*.png` 截图基线。

### 4.2 非阻塞 UX 观察（记录供评估）

- **已加载列表刷新遭遇同账号 `SessionExpired` / `RELOGIN_FAILED` 的静默错误表现**：当用户已在 `Forum(2)` 加载了帖子（`items.isNotEmpty()`）且之后在同 `uid` 下会话过期刷新返回 `ThreadListError.AuthRequired(RELOGIN_FAILED)` 时：
  1. [ForumViewModel.kt#L146-L155](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt#L146-L155) 仅在 `uidChanged || sameAccountRecovered` 时调用 `resetForAuthChange`（`SignedIn -> SessionExpired` 本身不递增 `authGeneration`），故 [ForumViewModel.kt#L105-L106](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt#L105-L106) `staleAuth` 为 `false`，`visibleItems` 仍非空；
  2. [ForumViewModel.kt#L222](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt#L222) 对 `AuthRequired` 跳过 `_refreshErrors.tryEmit(...)`（无 Snackbar）；
  3. [ForumPane.kt#L120](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumPane.kt#L120) 仅在 `uiState.items.isEmpty()` 时显示 `HiErrorState("登录已失效")`（无空态遮挡下仅在列表末尾 [ForumPane.kt#L189](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ForumPane.kt#L189) 显示通用的 `HiFooterState.Failed`）。虽然一旦通过 E15 重新登录成功发射 `SignedIn`，`sameAccountRecovered` 会立即触发自动刷新恢复，但刷新失败当刻用户看不到顶层 `"登录已失效"` + `"登录"` 按钮提示（若非刻意保留旧列表无感待重登设计，建议评估补充横幅或 Snackbar 提示）。
- **`probeD_narrow280dpMiddlePane` 度量备注**：[ThreadListRow.kt#L103-L104](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/feature/forum/ThreadListRow.kt#L103-L104) 对回复/查看数与时间使用 `maxLines = 1, softWrap = false`，Compose `TextLayoutResult.didOverflowWidth` 因 `multiParagraph.width == constraints.maxWidth`（`460 > 94`）返回 `true`，但实际字形宽度与绘制完全未发生截断或裁剪。

---

## 5. 产物清单（按卡要求保留 worktree 与分支）

- 探针代码：[hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt)（已推送 `origin/verify/E1-probes @ e914168`）
- 本验证报告：[/home/mir/Documents/hipda-next.wt/_reports/V-E1.md](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1.md)
- 新增视觉诊断图目录：[/home/mir/Documents/hipda-next.wt/_reports/V-E1/](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/)
  - [v_e1_w411_h891_mid_scroll_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w411_h891_mid_scroll_light.png)（负责人指定 411dp 中段滚动图）
  - [v_e1_w749_h559_three_pane_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w749_h559_three_pane_light.png)（负责人指定 749dp 三栏图）
  - [v_e1_hinge_two_pane_solid_topbar_light.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_hinge_two_pane_solid_topbar_light.png)（铰链双栏纯色顶栏补充图）
  - [v_e1_w933_h696_skeleton_dark.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_dark.png)（深色骨架屏缺陷复现图）
  - [v_e1_w933_h696_skeleton_pure_black.png](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_pure_black.png)（OLED 纯黑骨架屏缺陷复现图）
- Worktree `/home/mir/Documents/hipda-next.wt/V-E1` 与分支 `verify/E1-probes` 已按卡内【交付】要求保留未删。


---

## 6. 复验（第 2 轮）— 修复轮 1（`origin/card/E1-forum-page @ e24e3c0`）

- **第 2 轮总结论**：**`PASS`**（阻塞缺陷已全局修复，基线截图缺口已补齐，全站 1095 项单测与 Roborazzi/Lint/Build 在本地与 CI 均全通过）
- **被测对象与基线合入**：`origin/card/E1-forum-page @ e24e3c0`（已合入 `main @ 19e7536` / 含 E2a 与 E15 合并提交）；独立探针分支 `verify/E1-probes` 已 rebase 到 `e24e3c0` 并推送修复轮探针 `@ 10109821aeee79db6823fc2dcfb94a5d42bc2d07`。
- **修复机制核查**：[HiSkeleton.kt#L48](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt#L48) 已移除对 `LocalContentColor.current`（易受 `Box.background` 容器未设前景色影响回落默认黑色）的依赖，改为直接读取语义色 `MaterialTheme.colorScheme.onSurface` 作为骨架基准色。
- **GitHub Actions CI**：[Run #45703235903](https://github.com/mailwmj/hipda-next/actions/runs/45703235903) — **`success`**
  - `Build and lint`（`assembleDebug` / `assembleRelease` / `lintDebug`）、`Verify Room schema consistency`、`Verify APK outputs`：全通过
  - `Run unit tests and verify Roborazzi screenshots`：**`TOTAL: 1095 tests, 1095 passed, 0 failed, 0 errors, 0 skipped`**（`:hipda:testDebugUnitTest` `997/997`，其中 `E1ProbeTest` `12/12`、`E1ReducedMotionProbeTest` `1/1`、`ForumScreenshotTest` `24/24`、`ThreadDetailScreenshotTest` `15/15`、`LoginScreenshotTest` `8/8`、`HiStateScreenshotTest` `20/20`）
  - 本地全量执行 `:core-parser:test :hipda:testDebugUnitTest -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:lintDebug` 同步取得 `997/997` 及零错误通过。

### 6.1 补检 1：重录骨架图与新增 `forum_hinge_*` 基线目视结论

1. [forum_w411_h891_skeleton_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w411_h891_skeleton_light.png)（**PASS**）：浅色 `bg=#F2FBFF` 上呈现中性浅灰骨架块（`#E3EBEF`），顶部 `Discovery` 实色双行顶栏与底部玻璃胶囊布局协调。
2. [forum_w933_h696_skeleton_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_w933_h696_skeleton_dark.png)（**PASS**）：三栏中栏 `< 300dp` 紧凑副行骨架（标题长条 + `20dp` 小头像圆圈 + 双段元信息条）在深色背景上清晰可见，第 1 轮“深黑融合隐身”缺陷已彻底消除。
3. [forum_hinge_w860_h744_light.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_hinge_w860_h744_light.png) & [forum_hinge_w860_h744_dark.png](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/snapshots/images/forum_hinge_w860_h744_dark.png)（**PASS**）：`860×744dp` 纵向分离铰链双栏浅色/深色基线补齐（`forum_*.png` 由 22 张增至 24 张）。顶栏在浅/深双色下均为实色非玻璃并保留完整 `BalancedForumTabRow` 渐变荧光笔，左栏展示 `40dp` 头像列表（含 `old_timer` 已读清晰弱化）且底部仅悬停单枚紧凑玻璃胶囊，右栏垂直对齐“选一篇帖子开始阅读”。

### 6.2 补检 2：Forum 与 E2a 详情深色 / OLED 纯黑骨架全局探针

| 探针用例 | 验证场景 | 实测像素对比度（933×696dp，2×） | 结果 | 源码 / 复现截图 |
|---|---|---|---|---|
| `visual_darkAndPureBlackSkeleton_mustRenderVisibleSkeletonBlocksOverBackground` | Forum `ForumListSkeleton`（中栏 `200..470dp`） | 深色 `bg=#121316, fg=#212225, maxDelta=15, brighter=138112`；OLED `bg=#000000, fg=#101010, maxDelta=16, brighter=138171` | **PASSED** | [E1ProbeTest.kt#L309-L348](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L309-L348) / [深色图](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_dark.png) / [纯黑图](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_w933_h696_skeleton_pure_black.png) |
| `visual_detailSkeleton_darkAndPureBlack_mustRenderVisibleSkeletonBlocksOverBackground` | E2a `ThreadDetailSkeleton`（`NextAppRoot` 右栏 `500..910dp`） | 深色 `bg=#121316, fg=#212225, maxDelta=15, brighter=271007`；OLED `bg=#000000, fg=#101010, maxDelta=16, brighter=271077` | **PASSED** | [E1ProbeTest.kt#L350-L398](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L350-L398) / [E2a 深色图](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_e2a_w933_h696_detail_skeleton_dark.png) / [E2a 纯黑图](file:///home/mir/Documents/hipda-next.wt/_reports/V-E1/v_e1_e2a_w933_h696_detail_skeleton_pure_black.png) |

### 6.3 第 2 轮完整探针清单（13 / 13 PASSED）

- `E1ProbeTest`（12/12 PASSED）：`visual_capture411dpMidScroll_forManualInspection`、[L243](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L243)；`visual_capture749dpThreePane_forManualInspection`、[L280](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L280)；`visual_darkAndPureBlackSkeleton_...`、[L309](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L309)；`visual_detailSkeleton_darkAndPureBlack_...`、[L350](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L350)；`probeA_asyncAccountHydration_...`、[L466](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L466)；`probeB_topBarCollapseZeroJump_...`、[L630](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L630)；`probeB_hingeTwoPane_...`、[L710](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L710)；`probeB_realNextAppRootHingeTwoPane_...`、[L738](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L738)；`probeC_todayNewSearchIdPagination_...`、[L786](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L786)；`probeD_sortPersistence_...`、[L1077](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1077)；`probeD_narrow280dpMiddlePane_...`、[L1154](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1154)；`probeD_all20Presets_...`、[L1245](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1245)。
- `E1ReducedMotionProbeTest`（1/1 PASSED）：`probeB_reducedMotion_zeroMsSingleFrame_andZeroScrollRecomposition`、[L1343](file:///home/mir/Documents/hipda-next.wt/V-E1/hipda/src/test/java/net/jejer/hipda/verify/E1ProbeTest.kt#L1343)。
