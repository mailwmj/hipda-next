# C5b 第 1 轮修改（审查 R-C5b @2387ded 判定 APPROVE，负责人要求合并前完成简化与两处缺陷修正）

在同一个 worktree `/home/mir/Documents/hipda-next.wt/C5b`、同一分支 `card/C5b-feedback` 上继续。原卡 `/home/mir/Documents/hipda-next.wt/_cards/C5b.md` 的边界、通用约定和交付格式仍然有效。本地 Gradle 前先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME。

理由：这些组件会被所有页面复用，现在生产代码约 1120 行，卡片估计 250–400 行；审查给出了可以减半的具体方案。行为和公开 API（`HiSnackbarHost`、`HiRollingNumber`、`HiCountBadge`、`HiIconSwitch`、`HiStrokeCheck`、`Modifier.hiPressScale`、`Modifier.hiListItemPress`、`rememberHiHaptics`、`hiSlideInVertically`/`hiSlideOutVertically`）保持不变，现有 8 个行为测试除了删除对测试专用钩子的依赖外不得放宽断言。

## 审查建议（原文，全部完成）
1. **用逐位 `AnimatedContent` 替代手写状态机与自定义 `Layout`**（`HiRollingNumber.kt` 446 行 → 预计 135–155 行）：去掉 `RollingNumberState`/`RollingDigitSlotState`/`Snapshot.withoutReadObservation` 组合期写状态，同时修复 `5→6→4` 中途反向改为不同数字时原地覆盖 `stepChars[0]` 的跳字瑕疵及列表初次挂载每项启动多协程的开销。
2. **抽取按压节点公共基类并接入 `IndicationNodeFactory`**（`HiPressFeedback.kt` 286 行 → 预计 130–145 行）：合并 `HiPressScaleNode` 与 `HiListItemPressNode` 约 70 行重复的 `InteractionSource` 计数/协程模板，`Animatable` 延迟到首次按压分配，列表项支持 `clickable(indication = ...)` 免除每行手建 `MutableInteractionSource`。
3. **精简 `HiIconFeedback.kt` 冗余包装、逐帧路径重建与测试专用语义键**（155 行 → 预计 95–110 行）：`HiIconSwitch` 直接把 `modifier` 传给 `AnimatedContent` 省去外层 `Box`，`HiStrokeCheck` 改用 `Modifier.drawWithCache` 缓存 `Path`/`PathMeasure` 并移除每帧失效的 `HiStrokeCheckProgressKey`。
4. **修正 `HiSnackbarHost` 无障碍超时参数与大屏宽度约束**（208 行 → 预计 150–165 行）：`toTimeoutMillis` 中 `containsIcons` 应取 `visuals.withDismissAction` 而非写死 `true`，复用 `hiSlideInVertically`/`hiSlideOutVertically` 默认令牌，并加 `widthIn(max = 600.dp)` 适配平板/双栏。
5. **移除只为单测存在的生产代码钩子**：删除 `LocalHiRollingNumberRecompositionHook`、`HiRollingDigitPlaceKey`、`HiCountBadgeSurfaceKey`、`HiStrokeCheckProgressKey`，改用外部包装组合计数与截图/像素断言测试。
6. **补强单测**：补充 `hiListItemPress` 状态层像素断言、`HiSnackbarHost` 入场升降逐帧位移断言、`HiIconSwitch` 双图标并发缩放断言、`HiStrokeCheck` 半程描线断言，以及 `HiRollingNumber` 的 `5→6→4` 异值打断用例（先写这条，确认在旧实现上失败，再改实现）。

## 注意
- 用 `AnimatedContent` 重写逐位滚动后，仍须满足：只滚变化的位、增上减下、打断时从当前位置接续（`5→6` 进行中改为 `6→5` 或 `→4` 都不跳字）、减少动态效果一帧到位、父级无关状态变化时不重组。如果 `AnimatedContent` 在“打断接续”上做不到逐帧连续，保留必要的手写部分并在回复里说明原因和行数。
- `feedback_*` 两张基线若有像素变化，只针对 `HiFeedbackScreenshotTest` 重录并逐张检查；其他基线零改动。

## 验收
- [ ] 建议 1–6 每条：修改位置 文件:行号 → 证明它的测试（文件:方法）。
- [ ] `5→6→4` 打断测试在旧实现上失败、新实现上通过（给出旧实现失败的本地输出摘要）。
- [ ] `feedback/` 生产代码修改前后 `wc -l`。
- [ ] 测试专用钩子已从生产代码删除：`rg -n 'RecompositionHook|HiRollingDigitPlaceKey|HiCountBadgeSurfaceKey|HiStrokeCheckProgressKey' hipda/src/main` 无结果。
- [ ] CI 全绿（run 链接 + 最终 commit hash）。
