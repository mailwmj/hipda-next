- **验证对象**：任务卡 `U2`（`/home/mir/Documents/hipda-next.wt/_cards/U2.md`），目标 Commit：`239a1688293c8a1ac07a8047108b850e42c8f33d`（基线 `3a1f5b1`，已含 U1）
- **独立验证分支 / Worktree**：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-U2 -b verify/U2-probes 239a1688293c8a1ac07a8047108b850e42c8f33d`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md`（先写独立探针，不先抄实现者测试，不读 `_reports/U2.md`）
- **独立探针重点**：
  1. `ThreadBottomToolbar` 单页 ↔ 多页动态切换：`lastPage == 1` 时中段 `{N}楼` 不可点（无点击语义、点击不展开 `InlinePageJumpBar`、无障碍读 `第 {N} 楼`）；刷新变为 `lastPage > 1` 后立即恢复可点，点击原地展开 `InlinePageJumpBar`，跳页后自动收起并压入 `PositionStack`，按系统返回精确弹栈恢复；
  2. 底栏滚动与紧凑态：`ThreadDetailPane` 滚动跨楼层时底栏中段实时同步当前首个可见楼层 `{N}楼`（无页码文字、无进度填充）；在 `340dp` 分屏窄窗和 `360dp` 右栏 + `1.25×` 字体缩放下左段自动切 `48dp` 纯图标，三段无裁切重叠且所有交互热区 ≥ `48dp`；
  3. 玻璃拓扑与顶部对齐：`HiGlassMarker` 严格 `≤ 3`（含三栏与单栏，以及快速回复叠起时底栏 `HiGlassRegion` 整块卸载）；在 `749×559`、`933×696`、`1280×800` 下验证 `ThreadDetailTopBarHeight == FORUM_TOP_BAR_TITLE_ROW_HEIGHT == 56.dp` 且顶栏不再渲染 `楼主 · ` 副标题；
  4. 连续加载与向上插页锚定回归：顶栏从 `60.dp → 56.dp` 后，长帖滚动、跳楼高亮与向上插页首可见楼层偏移仍 ≤ `1dp`。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/V-U2.md`
