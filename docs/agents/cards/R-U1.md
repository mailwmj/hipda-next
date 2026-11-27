- **审查对象**：任务卡 `U1`（`/home/mir/Documents/hipda-next.wt/_cards/U1.md`），分支 `card/U1-shell-list-polish`
- **目标 Commit**：`44810ce874f46f73090d98aa46337adad8fd4fb8`（基线 `1377a04`）
- **CI 证据**：`gh run view 46046699288`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`（**禁止阅读**实现者报告 `_reports/U1.md`）
- **本卡重点**：
  1. 核对左栏展开态、左栏 `64dp` 收起态顶部与论坛顶栏均严格 `56dp`（底部固定区保持 `48dp`），静止底线隐藏/滚动淡入，未误改 `ForumTopBarScrollState` 或抽屉焦点/手势逻辑；
  2. 核对中栏删「图」、去独立徽标行、置顶图钉、投票/分类合入元信息、作者全宽不截断、`isNew` 2 行溢出回落、精简日期注入时钟且非标准原文透传、选中胶囊与内缩 `0.5dp` 分隔线、`PaneDividerHandle` 仅改线宽 `0.5dp`；
  3. 核对未触碰 `HiGlassSurface.kt`/`HiGlassTokens`、`docs/design/copy.md` 及越界文件，且不存在改坏逻辑仍绿的空心断言；
  4. **视觉目视复验**：只读查看 `/home/mir/Documents/hipda-next.wt/U1/hipda/src/test/snapshots/images/` 中本卡变更的代表性 PNG（覆盖 `forum_*` 浅/深/窄/宽/大字、`shell_w749_*`/`shell_w933_*`/`shell_w1280_*`、`multipane_*`），确认对齐、无截断/重叠、749×559 首屏 3 版块完整、视觉克制优雅，且非本卡波及的既有快照 0 漂移。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/R-U1.md`
