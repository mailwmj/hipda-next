- **审查对象**：任务卡 `U2`（`/home/mir/Documents/hipda-next.wt/_cards/U2.md`），分支 `card/U2-detail-deweb-polish`（已 rebase 至含 U1 的 `origin/main` `3a1f5b1`）
- **目标 Commit**：`239a1688293c8a1ac07a8047108b850e42c8f33d`（基线 `3a1f5b1`）
- **CI 证据**：`gh run view 46051574839`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`（**禁止阅读**实现者报告 `_reports/U2.md`）
- **本卡重点**：
  1. 核对 `ThreadDetailTopBar.kt`：`56.dp == FORUM_TOP_BAR_TITLE_ROW_HEIGHT`，去掉 `楼主 · ` 副标题，纯 `48.dp` 无底描边图标（激活态 `primary` + 实心），全屏图标改侧栏面板隐喻；
  2. 核对 `PostFloorCard.kt` & `ThreadDetailPane.kt`：楼层号完整 `onSurfaceVariant` + `tnum`（严禁叠 alpha 守 WCAG 4.5:1），`楼主` 无底 `primary` `labelSmall`，楼层分隔线 `0.5.dp` 内缩 `16.dp`；`visibleFloor` 用 `derivedStateOf` 同步可见首楼；
  3. 核对 `ThreadBottomToolbar.kt`：删除 `resolveProgressCapsuleColors` / 蓝底进度条 / `HiRollingNumber`，左段可伸缩左对齐轻输入槽（窄屏紧凑自动切 `48dp` 纯图标不裁切），中段贴合内容 `{N}楼`（`lastPage == 1` 被动不可点读 `第 {N} 楼`；`lastPage > 1` 可点展开 `InlinePageJumpBar` 读 `第 {N} 楼，点按展开跳页`），右段 `96dp`、`ThreadBottomToolbarHeight`/`Clearance` 及单 `HiGlassRegion` 拓扑零破坏；
  4. 核对 `NextAppTracerBulletTest.kt:855`（为何新增非零尺寸过滤，确认不是放宽断言或掩盖不可见节点缺陷）、`EmojiPanelBehaviorTest.kt`/`QuickReplyIntegrationBackPriorityTest.kt`/`FullEditorIntegrationTest.kt` 仅改跳页点击描述行，且严禁触碰 `HiGlassSurface.kt`/`HiGlassTokens`/`InlinePageJumpBar.kt`/`docs/design/copy.md`；
  5. **视觉目视复验**：只读查看 `/home/mir/Documents/hipda-next.wt/U2/hipda/src/test/snapshots/images/` 中本卡变更的代表性 PNG（覆盖 `thread_detail_*`、`thread_chrome_*`、`thread_interact_*`、`multipane_w749_*`/`w933_*`/`w1280_*`），核对三栏顶栏 `56dp` 对齐、楼层去蓝、底栏回复槽与弱化 `{N}楼`、无裁切重叠，以及 `HiGlassScreenshotTest` 与其他非本卡快照 0 漂移。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/R-U2.md`
