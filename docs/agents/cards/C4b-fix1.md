# C4b 第 1 轮修改（审查 R-C4b @6e92adc 判定 APPROVE、视觉复验 V-C4b PASS；负责人要求合并前处理两处真机风险和打磨项）

在同一个 worktree `/home/mir/Documents/hipda-next.wt/C4b`、同一分支 `card/C4b-shell-scroll` 上继续。原卡 `/home/mir/Documents/hipda-next.wt/_cards/C4b.md` 的边界、通用约定和交付格式仍然有效，以下为增补。本地 Gradle 前先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME。

## 审查建议（原文，1–6 全部完成）
1. **隔离退场目的地的残余惯性滚动**（`HiAdaptiveShell.kt:109-125`，预计 +8 行）：把 `nestedScroll` 移入 `AnimatedContent` 内部并仅在 `destination == selected` 时转发给 `barState`，避免旧目的地列表向下 fling 期间切换目的地时，退场页约 120ms 淡出帧里的 `onPreScroll`/`onPostScroll` 取消 `barState.show()` 并把胶囊重新推回全隐（若新目的地无滚动容器则无法手势拉回）。**先写测试复现（旧列表 fling 中切换目的地，最终胶囊应可见），确认在当前实现上失败。**
2. **去掉键盘判断中的 `ime.getBottom > 0` 兜底**（`HiAdaptiveShell.kt:85`、`HiShellScrollAndPressTest.kt:574-576`）：`targetSdk = 30` + edge-to-edge 下部分 ROM 在无键盘时可能残留非零 `ime.bottom`，`|| WindowInsets.ime.getBottom(density) > 0` 会导致胶囊永久消失；严格按规格只读 `WindowInsets.isImeVisible`，测试注入 IME 时统一带 `.setVisible(Type.ime(), true)`；另加一条测试：`ime` 底部非零但 `isImeVisible == false` 时胶囊可见。
3. **`onSizeChanged` 仅在高度实际变化时写状态**（`HiAdaptiveShell.kt:71-82`、`:152`）：改为 `if (it.height != effectivePillHeightPx) measuredPillHeightPx = it.height`，消除首帧后多一次重组。
4. **为 `HiGlassSurface` 增加 `surfaceColor` 参数替代导航轨局部 `MaterialTheme`**（`HiGlassSurface.kt:103-156`、`HiNavigationRail.kt:43-55`）：本轮允许修改 `ui/designsystem/glass/HiGlassSurface.kt`，只加一个默认值为 `MaterialTheme.colorScheme.surface` 的可选参数（约 +3 行），默认行为逐像素不变（8 张 `glass_*` 基线零改动）；导航轨显式传入颜色并删掉局部 `MaterialTheme` 与额外的 `.background`。
5. **精简未用 API 与历史测试钩子**（预计 -14 行）：删除全仓未调用的 `ShellBarState.hide()`、`ShellIndicatorLayout` 冗余参数 `animatorKey`，以及 C4a 遗留的纯测试 `LocalShellItemCompositionObserver`（改用测试包装观察）。
6. **补齐防变异断言**（测试约 +35 行）：`fontScale = 1.3f` 下滚到底断言（守住 `onSizeChanged`）；带初速度的真实 fling 停滚吸附断言；滚动期间 `RecompositionCounter` 为 0 的断言。

## 视觉复验打磨项（V-C4b）
7. 浅色模式导航轨与内容区明度差仅 ΔL*≈2：导航轨底色改用 `surfaceContainer`（深浅色统一，与顶栏滚动后的颜色一致），通过第 4 项的参数传入；重录 4 张导航轨基线并逐张确认文字 ≥ 4.5:1、图标与指示器 ≥ 3:1（若指示器对轨底的对比度因此下降，在回复里给出数值）。
8. 胶囊全隐时阴影还残留 0.5px：隐藏距离加上阴影半径（4dp），胶囊完全隐藏后屏幕底缘零像素影响；重录 `shell_scroll_w411_h891_hidden_light.png`。

## 验收
- [ ] 1–8 每条：修改位置 文件:行号 → 证明它的测试（文件:方法）；第 1、2 项的新测试在旧实现上失败（给出本地失败输出摘要）。
- [ ] `glass_*`、`theme_*`、`state_*`、`feedback_*` 基线零改动；只重录 4 张导航轨图和 `shell_scroll_..._hidden_light.png`（`git diff --stat 6e92adc -- hipda/src/test/snapshots/`）。
- [ ] `ui/shell/` 生产代码行数前后对比（基线 885 行）。
- [ ] CI 全绿（run 链接 + 最终 commit hash）。
