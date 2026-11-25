# C5a 第 1 轮修改（审查 R-C5a @64bf594 判定 APPROVE，负责人要求合并前完成简化与两处规格偏差修正）

在同一个 worktree `/home/mir/Documents/hipda-next.wt/C5a`、同一分支 `card/C5a-state` 上继续。原卡 `/home/mir/Documents/hipda-next.wt/_cards/C5a.md` 的边界、通用约定和交付格式仍然有效。本地 Gradle 前先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME。

理由：这些组件会被所有页面复用，现在生产代码 816 行，卡片估计 250–400 行；审查给出了可减到约 475 行的具体方案。公开 API（`HiEmptyState`、`HiErrorState`、`HiStateAction`、`Modifier.hiSkeleton`、`HiSkeletonBlock`、`rememberLoadingIndicatorVisible`、`LoadingVisibilityController`、`HiContentState`、`HiLoadState`、`HiListFooter`、`HiFooterState`）和行为保持不变；现有测试不得放宽断言。

## 审查建议（原文，全部完成）
1. 精简生产代码规模（816 行 → 预计约 475 行）：`HiSkeleton.kt:92-155` 改用 `Modifier.drawWithCache` 替代手写 `ModifierNodeElement` + `DrawModifierNode` 并缓存 `Outline`（155 → 约 75 行）；`HiStateViews.kt:83-355` 合并 `HiListFooter` 三分支公共 `Row`、用 `rememberSaveable(hasDetails)` 替代 `LaunchedEffect`、拍平详情区多余 `Box`/`Row`、提取公共动作按钮并收敛冗余 `testTag`（355 → 约 215 行）；`LoadingVisibilityController.kt:21-131` 用 `minShowJob?.isActive == true` 替代 `minShowActive` 布尔位及两处 `try/finally` Job 判等、去掉 `0L` 特判和 `StateFlow`+回调双通道（131 → 约 65 行）；`HiContentState.kt:73-175` 直接用 `hiFadeIn()`/`hiFadeOut()` 默认参数并合并 `EMPTY`/`ERROR` 居中包裹层（175 → 约 120 行）。
2. `HiContentState.kt:56-66`：`rememberLoadingIndicatorVisible` 仅在 `SideEffect` 中调用 `controller.update`，运行期主动操作（`passive = false`）从 `false → true` 切换时首轮组合仍返回 `false`、需触发二次重组才变 `true`；`!passive` 分支要同步返回或同步更新状态以实现严格同帧 0ms（规格第 6.2 节：主动操作按下后 0ms 原地变成加载状态）。
3. `HiContentState.kt:56`：`rememberLoadingIndicatorVisible` 用 `remember` 而非 `rememberSaveable`，宿主旋转重建时慢请求已显示骨架会重置回 150ms 空白再出骨架；用 `rememberSaveable` 保存“本轮加载已显示”标记（仅 `isLoading` 仍为真时生效）。
4. 补齐三处单行变异盲区：断言点击 `HiEmptyState`/`HiErrorState` 的动作按钮触发 `onClick`；覆盖 `HiContentState` 的 `Empty`/`Error` 分支及过渡期间 `x` 坐标零位移；在 Compose 层测 `passive = false`（同一帧内为 true——这条先写，确认在旧实现上失败）。
5. `HiContentState` 的 KDoc 写明：“重试”等用户主动触发的整页重载传 `passive = false`。

## 验收
- [ ] 建议 1–5 每条：修改位置 文件:行号 → 证明它的测试（文件:方法）。
- [ ] 建议 2 的 Compose 层测试在旧实现上失败、新实现上通过（给出旧实现失败的本地输出摘要）。
- [ ] 建议 3 用 `StateRestorationTester` 证明：骨架已显示时重建，重建后立即仍显示骨架。
- [ ] `state/` 生产代码修改前后 `wc -l`。
- [ ] `state_*` 基线若有像素变化，只针对本卡截图测试类重录并逐张检查；其他基线零改动。
- [ ] CI 全绿（run 链接 + 最终 commit hash）。
