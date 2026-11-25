# C4b-fix1 修改轮核对报告

- **对照范围**：`6e92adc` → `63965936a59cca19fa5f62ba02d7996ab98c5a78`（`origin/card/C4b-shell-scroll`）
- **CI 核验**：Run [#45542641845](https://github.com/mailwmj/hipda-next/actions/runs/45542641845) — `conclusion: success`，`headSha: 63965936a59cca19fa5f62ba02d7996ab98c5a78`（完全匹配）
- **总体结论**：**PASS**（第 1–8 项全部落实，无审查项以外的生产改动，截图范围与视觉检查全部通过）

## 逐条核对（1–8）

| # | 审查项 | 状态 | 修改位置与证据 |
|---|---|---|---|
| 1 | 隔离退场目的地的残余惯性滚动 | **落实** | `HiAdaptiveShell.kt:75,115-145,181-193`：移除外层 `Box` 的 `.nestedScroll`，在 `AnimatedContent` 内部按 `currentSelected == destination` 经 `gatedNestedScrollConnection`（覆盖 `onPreScroll`/`onPostScroll`/`onPostFling`）转发。<br>**旧实现必败核验**：`HiShellScrollAndPressTest.kt:526-580`（`destinationSwitch_duringExitingListFling_keepsPillVisible`）在高速向下 fling 推进 2 帧后切到无滚动容器的 `Messages` 并推进 40 帧，断言胶囊回到 `baseTop` 且可见；在旧实现 `6e92adc` 上，外层 `nestedScroll` 仍会收到退场页淡出期（~120ms）的惯性滚动，触发 `cancelSettle()` 取消 `barState.show()` 并把胶囊推回隐藏位，无滚动容器无法拉回，测试在旧实现上必然失败。 |
| 2 | 去掉 `ime.getBottom > 0` 兜底，仅读 `WindowInsets.isImeVisible` | **落实** | `HiAdaptiveShell.kt:11,96`：删去 `WindowInsets.ime` 导入，第 96 行改为 `val imeVisible = WindowInsets.isImeVisible`。<br>**旧实现必败核验**：`HiShellScrollAndPressTest.kt:610-611` 保留 `.setVisible(Type.ime(), true)` 并移除旧兜底断言；新增 `HiShellScrollAndPressTest.kt:648-683`（`imeInsets_nonZeroBottomWithImeNotVisible_keepsPillVisible`）注入非零 `ime` 底部且 `setVisible(Type.ime(), false)`，断言胶囊停留在 `baseTop` 且可见。在旧实现 `6e92adc` 上，`ime.getBottom(density) > 0` 为真会把胶囊推至 `rootBottom` 隐藏（旧测试原第 574–592 行即据此断言隐藏通过），故新测试在旧实现上必然失败。 |
| 3 | `onSizeChanged` 仅在高度实际变化时写状态 | **落实** | `HiAdaptiveShell.kt:173-175`：改为 `if (it.height != effectivePillHeightPx) measuredPillHeightPx = it.height`。由 `HiShellScrollAndPressTest.kt:1013-1058` 首帧 `contentCounter.assertEquals(1)` 与 `assertRecompositionsAtMost(0)` 守住。 |
| 4 | `HiGlassSurface` 增加 `surfaceColor` 参数替代导航轨局部 `MaterialTheme` | **落实** | `HiGlassSurface.kt:108,120-122,149,155-156`：为 `resolveHiGlassStyleSpec` 和 `HiGlassSurface` 新增可选参数 `surfaceColor`（默认值分别为 `colorScheme.surface` / `MaterialTheme.colorScheme.surface`），默认路径与原 `val surface = colorScheme.surface` 行为完全一致，8 张 `glass_*` 基线零改动。`HiNavigationRail.kt:40-52` 删除局部 `MaterialTheme` 与 `.background(cs.surfaceContainerLow)`，直接传入 `surfaceColor = MaterialTheme.colorScheme.surfaceContainer`。 |
| 5 | 精简未用 API 与历史测试钩子 | **落实** | `ShellBarState.kt:88-93` 删除 `hide()`；`ShellIndicatorLayout.kt:62-69` 删除 `animatorKey`（同步清理 `HiAdaptiveShellTest.kt:226-229`）；`ShellNavigationMode.kt:79-87` 与 `ShellIndicatorLayout.kt:177-186` 删除 `LocalShellItemCompositionObserver` 及 `SideEffect`；`HiAdaptiveShellTest.kt:449-538` 改用测试内 `ObservedShellNavItem` + `RecompositionCounter.Track()` 覆盖三形态未选中项零重组。全仓 `git grep` 确认 `ui/shell/` 无残留引用。 |
| 6 | 补齐防变异断言 | **落实** | `HiShellScrollAndPressTest.kt:896-935`（`fontScale = 1.3f` + `STEP_5` 先断言胶囊长高超过 64dp 再断言第 59 项底边 ≤ 胶囊顶边）；`HiShellScrollAndPressTest.kt:939-1009`（向下与向上带初速度真实 fling，先断言松手后继续惯性滚动再断言胶囊吸附全隐/全显）；`HiShellScrollAndPressTest.kt:1013-1058`（首帧与拖动/吸附全程内容 `RecompositionCounter` 零重组）。 |
| 7 | 导航轨底色改用 `surfaceContainer`，重录 4 张图并核对对比度 | **落实** | `HiNavigationRail.kt:51` 传入 `surfaceColor = MaterialTheme.colorScheme.surfaceContainer`；`HiShellScrollAndPressTest.kt:1061-1097` 验证 Light/Dark/PureBlack 的文字与图标 ≥ 4.5:1。读图与像素实测见下节：导航轨与内容区在深浅色下均清晰可区分；**说明**：浅色选中胶囊指示器 `(194,232,251)` 相对 `surfaceContainer` 轨底 `(230,239,244)` 对比度为 `1.11:1`，深色 `(41,75,91)` 对 `(27,32,37)` 为 `1.76:1`（属 Material 3 `secondaryContainer` 落于 `surfaceContainer` 的预期变化，选中的深/亮色图标在胶囊内对比度 ≥ 4.5:1，可读性不受损）。 |
| 8 | 胶囊全隐距离加阴影半径 4dp，重录 `shell_scroll_w411_h891_hidden_light.png` | **落实** | `HiAdaptiveShell.kt:80,91-95` 将 `HiGlassTokens.FloatingShadowElevation`（4dp）计入 `maxHiddenOffsetPx`；`ShellBarState.kt:38` 同步注释；`HiShellScrollAndPressTest.kt:237-267,325-343,492-559,603-641` 更新全隐目标坐标。 |

## 截图范围与视觉检查（5 张）

- `git diff --stat 6e92adc 6396593 -- hipda/src/test/snapshots/` 严格仅变更以下 5 张图片（`glass_*`、`theme_*`、`state_*`、`feedback_*` 零改动）：
  1. `shell_w1280_h800_light.png`：导航轨 RGB `(230,239,244)` (`L*=93.92`) vs 内容区 `(242,251,255)` (`L*=98.07`)，**ΔL*=4.15** + 右侧细描边，层级清晰可区分。
  2. `shell_w900_h1120_light.png`：同上（ΔL*=4.15），文字与图标清晰无裁切。
  3. `shell_w1280_h800_dark.png`：导航轨 RGB `(27,32,37)` (`L*=11.96`) vs 内容区 `(14,20,23)` (`L*=5.92`)，**ΔL*=6.03**，清晰可区分。
  4. `shell_w900_h1120_dark.png`：同上（ΔL*=6.03），视觉正常。
  5. `shell_scroll_w411_h891_hidden_light.png`：底部肉眼全净无残影；与旧图 `6e92adc` 逐像素比对仅最底行 `y=1781`（x=81..740 共 513 像素）由原先被阴影压暗的 `(235,244,249)` 恢复为纯卡片底色 `(236,245,250)`，屏幕底缘零像素残留。

## 范围外改动与规模核查

- **审查项以外的改动**：无生产代码额外改动（仅在 `HiShellScrollAndPressTest.kt:504` 为既有用例补 1 行 `moveBy(Offset.Zero)` 稳定化清零释放速度）。
- **`ui/shell/` 生产代码行数**：`885 → 898`（+13 行）；`HiGlassSurface.kt` 净增 +1 行。
