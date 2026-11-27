# V-U2 独立探针验证报告

## 结论：`PASS`

- **验证对象**：任务卡 `U2`（`card/U2-detail-deweb-polish`，目标 Commit `239a1688293c8a1ac07a8047108b850e42c8f33d`，基线 `3a1f5b1` 已含 `U1`）
- **独立验证分支 / Worktree**：`verify/U2-probes` @ `09490dd91bce7b8c7635727464eddabe860cfde4`（保留在 `/home/mir/Documents/hipda-next.wt/V-U2`）
- **探针测试文件**：`hipda/src/test/java/net/jejer/hipda/verify/U2ProbeTest.kt`（13 个独立离线探针，未改动主目录、生产代码及原实现单测）
- **全量 Android CI**：[`https://github.com/mailwmj/hipda-next/actions/runs/46054726868`](https://github.com/mailwmj/hipda-next/actions/runs/46054726868) — `success`（`Build, Lint & Unit Tests` 16m10s，`Build and lint`、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`、`Verify APK outputs` 全绿）
- **本地串行验证**（`DISPATCH.md` §2 `flock` + `-Xmx3g` + `workers.max=4`，结束后已 `./gradlew --stop`）：`U2ProbeTest`（13/13）+ `CopyTerminologyScanTest`（3/3）+ `HiColorContrastCalibrationTest`（4/4）+ `MotionTokenScanTest`（7/7）= **27/27 PASSED**。

---

## 1. 既有快照零漂移与边界核查

`git diff --name-status 3a1f5b1...239a1688293c8a1ac07a8047108b850e42c8f33d` 核查结果：
- **生产文件（5 处，+130 / -141 行）**：`PostFloorCard.kt`、`ThreadDetailPane.kt`、`ThreadDetailTopBar.kt`、`ThreadBottomToolbar.kt`、`strings_thread_detail.xml`。`HiGlassSurface.kt` / `HiGlassTokens`、`InlinePageJumpBar.kt`、`HiNavigationSidebar.kt`、`ForumTopBar.kt`、`ThreadListRow.kt`、`ForumPane.kt`、`QuickReplySheet.kt`、`FullEditorScreen.kt`、`FullEditorViewModel.kt`、`PostImageBlock.kt` 与 `docs/design/copy.md` 均 **0 改动**。
- **测试文件（10 处）**：`ThreadBottomToolbarTest.kt`、`InlinePageJumpBarTest.kt`、`ReadingPositionAndStackTest.kt`、`ThreadDetailViewModelTest.kt`、`ContinuousPagingAnchorTest.kt`、`ThreadChromeScreenshotTest.kt`、`EmojiPanelBehaviorTest.kt`、`QuickReplyIntegrationBackPriorityTest.kt`、`FullEditorIntegrationTest.kt`、`NextAppTracerBulletTest.kt`（含 4 处跨模块点击行及 1 处滚出顶部裁切区可见性过滤，无断言放宽）。
- **快照（62 张变更 PNG，其余非波及快照 0 漂移）**：
  - `thread_detail_*`（15 张）、`thread_chrome_*`（10 张）、`thread_interact_*`（6 张）、`multipane_*`（13 张）；
  - 叠加在 `ThreadDetailPane` 之上的浮层快照 18 张：`quick_reply_*`（9 张）、`emoji_panel_*`（4 张：`quick_reply`/`short_landscape`/`tablet_w1280`/`threepane_w749`）、`editor_*`（5 张：`edit_delete_dialog_*` 2 张、`phone_w411_draft_saved_snackbar` 1 张、`threepane_*` 2 张）；
  - `glass_specimen_*`（12 张）、`forum_*`、`shell_*`、`state_*`、`theme_specimen*`、`image_viewer*`、`thread_image*`、`login*`、`messages*`、`mine*`、`profile*`、`search*`、`settings*`、`challenge*`、`account*`、`feedback_specimen*`、`m7_3_*`、`sample_export*` 均 **0 漂移**。
- `git diff --name-status 239a1688293c8a1ac07a8047108b850e42c8f33d...HEAD` 在 `verify/U2-probes` 仅新增 `A hipda/src/test/java/net/jejer/hipda/verify/U2ProbeTest.kt`。

---

## 2. 逐条独立探针验证结果（13/13 PASS）

### 重点 1：`ThreadBottomToolbar` 单页 ↔ 多页动态切换

| 探针用例 | 验证内容与实测结果 | 结果 |
|---|---|---|
| `probe1_bottomToolbar_singleToMultiPageSwitch_jumpAndPositionStackBack` | `lastPage == 1` 时滚至 3 楼，中段无障碍为 `第 3 楼`、无 `OnClick`/无 `Role.Button`/无 `"点按展开跳页"`，触摸点击不展开 `InlinePageJumpBar`；点 `更多 → 刷新` 变为 `lastPage = 4` 后立即切换为 `第 3 楼，点按展开跳页` 并可点，展开后左右胶囊退场、点 `末页` 跳至 19 楼自动收起并压入 `PositionStack`，首次系统返回优先弹栈回 3 楼（顶部偏差 `≤ 1dp`），再次返回才交还宿主退出 | PASS |
| `probe1b_bottomToolbar_autoCollapsesJumpBarWhenLastPageDropsToOne` | `InlinePageJumpBar` 展开态下 `lastPage` 由 `3` 降回 `1` 时自动回调 `onExpandedChange(false)` 收起跳页条，中段恢复不可点 `第 5 楼` | PASS |

### 重点 2：底栏滚动同步与窄容器紧凑态

| 探针用例 | 验证内容与实测结果 | 结果 |
|---|---|---|
| `probe2a_detailPaneScroll_syncsFloorInRealTime_withoutPageTextOrProgressFill` | `ThreadDetailPane` 滚跨第 1 → 4 → 8 楼并触发第 2 页追加后滚至 12 楼，中段胶囊实时同步 `第 {N} 楼，点按展开跳页`；无任何 `X/Y` 或 `第 X / Y 页` 文案，且中段 `Box` 仅有唯一 `Text` 子节点、无背景/进度填充修饰符 | PASS |
| `probe2b_compactToolbar_at340dpSplitAnd125FontScale_switchesLeftTo48dpIcon_noOverlapOrClip` | `340dp` 分屏窄窗 + `1.25×` 字体缩放下左段自动切为 `48dp` 纯图标（无 `"回复"` 文字），三段间隙 `≥ 7.5dp`、无裁切重叠且全部热区 `≥ 48dp`；点开双行 `InlinePageJumpBar` 均不越界且控件高 `≥ 48dp` | PASS |
| `probe2c_compactToolbar_at360dpRightPaneAnd125FontScale_switchesLeftTo48dpIcon_noOverlapOrClip` | `360dp` 右栏 + `1.25×` 字体缩放下左段切 `48dp` 纯图标，三段无裁切重叠、全部热区 `≥ 48dp`，展开双行跳页条不越界 | PASS |
| `probe2d_wideToolbar_replyCapsuleExpandsAndLeftAligns_cappedAt150FontScale` | `411dp` + `1.5×`（经 `HiCappedFontScale` 钳制至 `1.25×`）下三段热区 `≥ 48dp`、无重叠越界 | PASS |
| `probe2e_real680dpThreePane_rightPane360dpAnd125FontScale_switchesLeftTo48dpIcon` | 真实 `NextAppRoot` `680×700dp` 紧凑三栏（实测右栏 `360dp`）+ `1.25×` 字体缩放下底栏左段切 `48dp` 纯图标、无 `"回复"` 文字、零重叠零裁切 | PASS |

### 重点 3：玻璃拓扑、顶部对齐与克制化排版

| 探针用例 | 验证内容与实测结果 | 结果 |
|---|---|---|
| `probe3a_singlePane_glassBudgetAndQuickReplyUnmountsBottomToolbarRegion` | `411×891dp` 单栏详情常态 `HiGlassMarker == 2`、`LayerBackdrop == 1`、`nested == 0`、`drawBackdrop ≤ 4`；点 `回复` 展开 `QuickReplySheet` 时底栏 `HiGlassRegion`（`收藏`/`更多`/楼层胶囊）整块卸载，`HiGlassMarker == 2`、`drawBackdrop ≤ 2`；返回收起后底栏恢复 | PASS |
| `probe3b_threePane749x559_topBar56dpAligned_noOpSubtitle_glassBudgetAndQuickReplyUnload` | `749×559dp` 下实测中右顶栏高均为 `56dp`、`top`/`bottom` 及左/中/右顶栏按钮垂直中心对齐（误差 `≤ 1.5px`），无 `楼主 · ` 副标题；常态/跳页展开/快速回复展开全程 `HiGlassMarker == 3`、`LayerBackdrop == 2`、`nested == 0`、`drawBackdrop ≤ 5`（快速回复打开时底栏整块卸载）；切 `全屏阅读` 降至 `2` 块 `1` 源，恢复回 `3` 块 `2` 源 | PASS |
| `probe3c_threePane933x696_topBar56dpAligned_noOpSubtitle_glassBudgetAndQuickReplyUnload` | `933×696dp` 下实测左/中/右顶栏 `56dp` 对齐、无 `楼主 · ` 副标题、跳页/快速回复卸载及全屏切换全链玻璃预算严格达标 | PASS |
| `probe3d_threePane1280x800_topBar56dpAligned_noOpSubtitle_glassBudget` | `1280×800dp` 下实测左/中/右顶栏 `56dp` 对齐、无 `楼主 · ` 副标题、`HiGlassMarker == 3`、`LayerBackdrop == 2`、`nested == 0`、`drawBackdrop ≤ 5` | PASS |
| `probe5_topBarRestraint_floorTypographyTnumOnSurfaceVariant_opBadgeNoBg_divider05dpInset16dp` | `xxxhdpi`（4x 密度）下验证：顶栏仅单行标题无副标题、常/激活态按钮 `48dp` 无圆底背景；`1楼` 为完整 `onSurfaceVariant`（`alpha=1f`）+ `tnum`；`楼主` 为纯 `primary` `11sp` 无背景；楼层分隔线实测高 `2px`（`0.5dp`）、宽 `1516px`（左右内缩 `16dp`） | PASS |

### 重点 4：连续加载与向上插页锚定回归

| 探针用例 | 验证内容与实测结果 | 结果 |
|---|---|---|
| `probe4_continuousPaging_prependAnchorDriftAtMost1dp_and_jumpHighlightAfter56dpTopBar` | 顶栏 `56dp` 下从第 2 页第 13 楼（`offset=48px`）上滑触发第 1 页 prepend 并门控停在 `isPrepending=true`，第 1 页插入前后首可见楼层顶部位置偏移 `≤ 1dp` 且底栏楼层同步不变；第 15 楼点击引用跳至第 3 楼后目标楼层落于 `56dp` 顶栏下方无遮挡并暴露 `"已定位到此楼层"` 高亮语义，按系统返回精确弹栈回第 15 楼（偏移 `≤ 1dp`） | PASS |

---

## 3. 合规检查

- **离线合规**：探针全程采用内存替身 `ProbeThreadDetailRepository` 与 `MultiPaneTestHarness`，无任何 `4d4y.com` 网络访问。
- **工作区与留存**：`/home/mir/Documents/hipda-next` 主目录干净未改；`verify/U2-probes` 分支与 `/home/mir/Documents/hipda-next.wt/V-U2` worktree 完整保留供查验。
