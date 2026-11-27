# 端到端集成探针验证报告：`V-U-int`（`U1 + U2 + U3` 合入 `main`）

## 1. 总结结论

- **结论**：**PASS**
- **验证对象**：`U1 + U2 + U3` 完整合入 `main` 后的真实三栏端到端集成链路（`NextAppRoot` / `MultiPaneReadingContainer` / `HiAdaptiveShell` / `HiNavigationSidebar` / `ForumPane` / `ThreadDetailPane` / `ThreadBottomToolbar` / `QuickReplySheet` / `FullEditorScreen`）
- **目标 Commit（`main`）**：`ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c`
- **验证分支 / Commit**：`verify/U-int-probes` @ `9510f991ddd61670a43a76971ef5affde9c17cb3`
- **独立 Worktree（已保留）**：`/home/mir/Documents/hipda-next.wt/V-U-int`
- **主目录核查**：`/home/mir/Documents/hipda-next` 保持洁净且位于 `ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c`
- **改动面**：仅新增独立集成探针测试类 [UIntegrationProbesTest.kt](file:///home/mir/Documents/hipda-next.wt/V-U-int/hipda/src/test/java/net/jejer/hipda/feature/app/UIntegrationProbesTest.kt)，生产代码与既有单测 **0 修改**；探针全程离线（基于内存测试替身与 `FakePostRepository`/`FakeImageUploadRepository`），未访问 `4d4y.com`。
- **探针 CI 证据**：GitHub Actions `Android CI` Run [#46061080252](https://github.com/mailwmj/hipda-next/actions/runs/46061080252) **success**（`Build, Lint & Unit Tests` 耗时 `13m31s`，含编译、Lint、Room Schema 校验、全量单测与 Roborazzi 截图验证）。

---

## 2. 逐条探针验证结果（8 项全通过）

测试文件：[UIntegrationProbesTest.kt](file:///home/mir/Documents/hipda-next.wt/V-U-int/hipda/src/test/java/net/jejer/hipda/feature/app/UIntegrationProbesTest.kt)

| 探针 | 用例方法名 | 覆盖窗口 / 场景 | 验证要点与实测断言 | 结果 |
|---|---|---|---|---|
| 1. 三栏顶栏垂直中线齐平 & 749 首屏 3 版块 | `probe1_749x559_threePaneTopBarsAlignedWithin1dp_andFirstScreen3ForumsVisible` | `749×559dp`（左 `64dp` 收起轨），含 `0dp` inset 与 `24dp` 状态栏 + `24dp` 导航栏（共 `48dp` 系统栏） | 测量 `SidebarTopBar`、中栏 `ForumTopBar`、右栏 `ThreadDetailTopBar` 的 `top`/`bottom`/整框高度（`56dp` 或 `24+56=80dp`）、整框中线、图标中线与标题中线误差均 `≤ 1dp`；无 `楼主 ·` 副标题；`Discovery`/`Buy & Sell`/`Geek Talks` 3 版块完整位于底部头像槽之上 | **PASS** |
| 1. 三栏顶栏垂直中线齐平 | `probe1_933x696_threePaneTopBarsAlignedWithin1dp` | `933×696dp`（完整三栏） | 左栏账号行/收起钮、中栏论坛顶栏与标题/发新帖钮、右栏详情顶栏与标题/只看楼主钮，高度 `56dp` 且所有垂直中线误差 `≤ 1dp` | **PASS** |
| 1. 三栏顶栏垂直中线齐平 | `probe1_1280x800_threePaneTopBarsAlignedWithin1dp` | `1280×800dp`（平板完整三栏） | 三栏顶栏高度一致 `56dp`，所有垂直中线误差 `≤ 1dp` | **PASS** |
| 2. 快速回复叠放新底栏与玻璃卸载 | `probe2_threePane_quickReplyOverlaysNewToolbar_unmountsBottomGlass_andDragDismissRestores` | `749×559dp` 三栏态 | 初态验证左段伸缩「回复」槽（宽 `> 120dp`）、静态 `1楼`（`第 1 楼，点按展开跳页`）、右段 `收藏`/`更多`；点开「回复」后断言 `ReplyThread` 无顶部上下文行（无 `输入回复内容`，仅 1 处输入框内占位 `回复 楼层作者1` 紧邻 `32dp` 把手）、底栏左侧顺序为 `插入图片`→`表情`→`展开全屏`→`发送`、`发送` 视觉胶囊 `32dp` + 触控区 `≥ 48×48dp`、底层 `ThreadBottomToolbar` 的 `HiGlassRegion` 整块卸载（底栏楼层/收藏/更多/回复全部不存在，全屏 `HiGlassMarker == 3`、独立 `LayerBackdrop == 2`、0 嵌套、`drawBackdrop ≤ 5`）；通过 `swipeDown(120dp)` 下拉 `32dp` 把手收起后底栏干净恢复 | **PASS** |
| 3. 完整编辑器居中模态（749×559） | `probe3_749x559_quickReplyHandoffToCenteredModal_emptyValidation_filledCapsule_closeSavesDraft_preservesReadingAndPositionStack` | `749×559dp` 三栏态 + 跳至第 3 页建 `PositionStack` | 经内联跳页条「末页」跳至第 3 页（`21楼`）后点「回复」→「展开全屏」进入 `680dp` 居中模态；断言单行标题 `回复 楼层作者1`（`lineCount == 1`、无截断）、无重复 `输入回复内容`/`正文` 占位、左上 `×`（`关闭`）、模态 `Surface` 具 `0.5dp` 描边；空内容时发送钮背景 `alpha == 0f` 且 `32dp`/`≥ 48dp`、点击触发内联校验 `内容至少 6 个字`；输入正文后校验清除且发送钮切换为实心 `32dp` 胶囊（像素与透明背景区分）；点 `×` 关窗保存草稿至 `DraftKey.reply("1001")` 并弹 `已保存草稿`；底层三栏仍停留在第 3 页 `21楼`，系统返回先按 `PositionStack` 退回第 1 页 `1楼`、再按才关帖 | **PASS** |
| 3. 完整编辑器居中模态（933×696） | `probe3_933x696_quickReplyHandoffWithTextToCenteredModal_systemBackSavesDraft_preservesReadingAndPositionStack` | `933×696dp` 三栏态 + 快速回复带入文本 + 系统返回关窗 | 快速回复输入 `"带入正文"` 后点「展开全屏」无损带入 `680dp` 居中模态；清空后验证空态无底发送与 `内容至少 6 个字` 校验，再输入新正文变实心 `32dp` 胶囊；系统返回正确关窗存草稿（`已保存草稿`）且底层第 3 页阅读态与 `PositionStack` 完整保活（次次返回回到第 1 页） | **PASS** |
| 4. 全场景玻璃预算守卫（态迁移） | `probe4_allScenariosGlassBudgetGuard_neverExceeds3Markers_andTokensMatch1377a04` | `411×891` 单栏、`640×1024` 图标轨单栏、`749×559` 紧凑三栏、快速回复开启、居中模态开启、`933×696` & `1280×800` 完整三栏 | 逐一断言活跃 `HiGlassMarker`（单栏列表 `2` / 详情 `2`；图标轨列表 `1` / 详情 `2`；紧凑三栏 `3`；快速回复开启 `3`；居中模态开启 `3`；完整三栏 `3`）均严格 `≤ 3`，独立 `LayerBackdrop ≤ 2`，`nestedLayerBackdropCount == 0`，`drawBackdrop ≤ 5`；核实 `HiGlassTokens` 全部定稿值与 `1377a04` 一致 | **PASS** |
| 4. 全场景玻璃预算守卫（铰链双栏） | `probe4_hingeTwoPane_andQuickReply_staysWithin3GlassBudget` | `749×559dp` 物理竖向铰链双栏（正常态 & 快速回复开启） | 左列表顶栏退纯色（`0` 玻璃），全屏保持 `3` 块 `HiGlassMarker`（左紧凑胶囊 + 右顶栏 + 右底栏/快速回复）、`2` `LayerBackdrop`、`0` 嵌套、`drawBackdrop ≤ 5` | **PASS** |

---

## 3. 本地测试与 CI 执行记录

1. **本地 `flock` 串行验证**（按 `DISPATCH.md` §2 与 `lesson.md`）：
   - 命令：
     ```bash
     export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 \
            JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest
     export PATH="$JAVA_HOME/bin:$PATH"
     flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest \
       --tests "net.jejer.hipda.feature.app.UIntegrationProbesTest" \
       --tests "net.jejer.hipda.ui.designsystem.motion.MotionTokenScanTest" \
       --tests "net.jejer.hipda.feature.common.CopyTerminologyScanTest" \
       -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4
     ./gradlew --stop
     ```
   - 结果：**BUILD SUCCESSFUL**（`18 tests completed, 0 failed`：`UIntegrationProbesTest` 8/8、`CopyTerminologyScanTest` 3/3、`MotionTokenScanTest` 7/7）。
2. **GitHub Actions CI**：
   - 分支：`verify/U-int-probes`（commit `9510f991ddd61670a43a76971ef5affde9c17cb3`）
   - 运行链接：<https://github.com/mailwmj/hipda-next/actions/runs/46061080252>
   - 监控命令：`gh run watch 46061080252 --repo mailwmj/hipda-next --interval 60 --exit-status`
   - 结果：**success**（`Build, Lint & Unit Tests` 全部通过，含 Roborazzi 截图验证 `verifyRoborazziDebug`）。

---

## 4. `HiGlassSurface.kt` 零改动与快照零漂移核查

1. **`HiGlassSurface.kt` 相对 `1377a04` 核查**：
   - 执行 `git diff 1377a04...ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c -- hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt`，输出为空（**0 改动**）。
2. **快照零漂移核查（`git diff --name-status 1377a04...ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c -- hipda/src/test/snapshots/`）**：
   - 共 `126` 张基线随 `U1`/`U2`/`U3` 卡片授权更新：
     - `editor_*`（17 张）
     - `emoji_panel_*`（5 张）
     - `forum_*`（26 张）
     - `messages_*` 三栏态（5 张，因 `U1` 左栏顶栏 56dp 与 0.5dp 分隔线变更波及三栏截图）
     - `multipane_*`（21 张）
     - `quick_reply_*`（9 张）
     - `shell_*` / `shell_scroll_*`（12 张）
     - `thread_chrome_*` / `thread_detail_*` / `thread_interact_*`（31 张）
   - 所有非授权快照（如 `glass_*`、单栏 `messages_sms_list_w411_*`、`login_*`、`search_*`、`mine_*`、`settings_*`、`profile_*`、`image_viewer_*` 等）**0 漂移**。
