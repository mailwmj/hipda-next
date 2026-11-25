# C4a 第 1 轮修改（审查 R-C4a @63e7c69 判定 CHANGES-REQUESTED + 视觉复验 V-C4a 打磨项）

在同一个 worktree `/home/mir/Documents/hipda-next.wt/C4a`、同一分支 `card/C4a-shell` 上继续。原卡 `/home/mir/Documents/hipda-next.wt/_cards/C4a.md` 的边界、通用约定和交付格式仍然有效，以下为增补。本地 Gradle 前先按 `docs/agents/lesson.md` 导出 mise 的 ANDROID_HOME/JAVA_HOME。

## 必须修复的阻断项（审查原文）

**阻断 1：紧凑胶囊在窄窗口 / 分屏 / 铰链分栏下二次测量同一个 `Measurable` 导致运行时崩溃，且自定义 `Layout` 未钳制 `minHeight`**
- 文件:行号：`HiNavigationPill.kt:157-174`（以及未钳制高度的 `HiNavigationPill.kt:139-140`、`HiNavigationPill.kt:154-155`、`HiNavigationRail.kt:96-97`）
- 触发路径：将应用拖入高度 `< 480dp` 且有效宽度 `< 276dp`（1.25× 字号下 `< 282dp`）的多窗口/自由窗口（Android 自由窗口最小宽 `220dp`；或三键横屏导航栏占 `48dp` 时的 `310×411dp` 分屏；或后续 E3 按 `product-design.md` §4.1 在书本半折姿态把紧凑胶囊放入 `260dp` 列表栏）。此时 `availableForItems = width - 52dp < 224dp = naturalSum`，进入 `else` 分支对第 157 行已 `measure` 过的 `Measurable` 在第 165 行再次调用 `measurable.measure(...)`；另当分屏拖拽使剩余高度 `< 48dp/56dp` 时，`Constraints(minHeight = minTouchPx, maxHeight = constraints.maxHeight)` 因 `minHeight > maxHeight` 直接抛异常。
- 后果：`IllegalStateException` / `IllegalArgumentException`（`measure() may not be called multiple times on the same Measurable` 或 `maxHeight must be >= than minHeight`），应用崩溃。

**阻断 2：预览外壳入口旋转 / 折叠展开 / 进程回收后丢失外壳演示态与选中目的地**
- 文件:行号：`PreviewActivity.kt:77`、`PreviewActivity.kt:765`
- 触发路径：打开 `PreviewActivity` → 点“外壳” → 选中“搜索” → 旋转或折叠/展开或进程重建。
- 后果：`showShellDemo` 与 `selected` 用 `remember` 而非 `rememberSaveable`，重建后退回主题样张页、目的地重置为 `Forum`。

## 一并完成（审查建议中负责人采纳的部分）
- **S1（同时根治阻断 1）**：删除 Pill 与 Rail 两套重复的自定义 `Layout`，改成一个共用的单遍指示器布局（每个 `Measurable` 只 `measure` 一次；窄窗需要压缩时先用 `maxIntrinsicWidth` 算比例再单次测量；所有 `minHeight` 都 `coerceAtMost(constraints.maxHeight)`），或改用标准 `Row`/`Column` + 坐标驱动指示器。窄到放不下时允许文字省略或只显示图标，但不得崩溃、不得让触摸目标小于 48dp（空间实在不够时以不崩溃为先，并在回复里说明）。
- **S2**：删除 `IndicatorCommand` + `Channel` + `runCommandLoop`，直接用 `rememberCoroutineScope()` 调 `Animatable.animateTo` / `snapTo`；用 `pendingSnap` 之类的方式避免同帧二次测量读到旧值；`BottomPill` 与 `CompactPill` 切换时隔离指示器状态（例如 `remember(compact)`），不从旧形态坐标插值。
- **S3、S4**：合并 `ShellNavItem` 横竖分支中重复的 `Icon`+`Text`；内联没有外部调用者的 `HiAdaptiveShellLayout`；提取 `PreviewActivity` 中重复两遍的 `OLED/DARK/LIGHT` 徽章。
- **S6（规格对齐）**：铰链判断改为 `it.isVertical && it.isSeparating`（`product-design.md` §4.1：半折横放的桌面姿态不做特殊布局）。
- **S7（补测，让下面每一处“改坏一行测试仍通过”都会失败）**：
  1. 注入非零 `navigationBars` inset，断言胶囊底边 = 窗口底 − inset − 8dp。
  2. 目的地切换动画进行中逐帧采样内容容器 `positionInRoot`，断言零位移。
  3. 指示器的**宽度**逐帧连续（`assertFrameContinuity` 只采样位置；自己补采样 `size.width`，或在 `MotionTestHelpers` 之外的本卡测试里写一个局部辅助，不改 `MotionTestHelpers.kt`）。
  4. `Rail` 形态下 `fontScale = 2.0` 时文字被钳制在 1.25 倍。
  5. 用 `StateRestorationTester` 证明预览外壳的演示态和选中目的地在重建后保持。
  6. 可行的话用 `WindowPosture`/`FoldingFeature` 测试替身，断言竖直分隔铰链 → 紧凑胶囊、水平铰链（桌面姿态）→ 不变；不可行时在回复里说明原因。
- **视觉打磨（V-C4a，按规格第 5 节“选中项是一个浅色胶囊”）**：
  1. 导航轨的选中指示器改为只包图标的横向小胶囊（约 56×32dp，全圆角），文字在胶囊下方，不再用包住图标和文字的大圆盘。
  2. 导航轨贴屏的三条边不画 0.5dp 高光描边，只保留朝向内容的右侧那条。为此允许在 `ui/designsystem/glass/HiGlassSurface.kt` 增加一个可选参数（例如指定描边绘制在哪些边，默认四边不变），改动不超过约 25 行；8 张 `glass_*` 基线和 12 张 `theme_*` 基线必须零改动。
  3. 紧凑胶囊里选中指示器右缘到相邻图标的视觉间距与其余图标间距一致（在保持每项 ≥ 48dp 触摸目标的前提下）。

## 防止同类问题（Guard the class）
新增一个参数化的“不崩溃 + 不裁切”测试：对 `BottomPill`、`CompactPill`、`Rail` 三种形态，宽度从 200dp 到 1280dp（步长不大于 20dp）、高度覆盖 40dp、300dp、411dp、800dp，字号缩放 1.0 与 1.3，逐一组合渲染外壳，断言不抛异常、每个导航项可见且触摸目标 ≥ 48dp（空间不足以满足 48dp 的极端高度除外，测试里注明）。这条测试要能在把 S1 改回二次测量时失败——在回复里说明你如何确认了这一点（例如临时改坏后本地跑一次看到失败）。

## 验收
- [ ] 阻断 1、2 修复，各有对应测试（文件:方法）。
- [ ] S1–S4、S6、S7 完成；`ui/shell/` 生产代码行数下降（给出修改前后 `wc -l`）。
- [ ] 视觉打磨 1–3 完成；变化的 `shell_*` 基线只针对 `HiShellScreenshotTest` 重新录制并逐张检查；`glass_*`、`theme_*` 基线零改动（`git diff --stat 64fd073 -- hipda/src/test/snapshots/`）。
- [ ] 防同类测试存在，且说明了确认它会失败的方法。
- [ ] CI 全绿（run 链接 + 最终 commit hash）。

回复格式同原卡；另外逐条列出“审查项 → 修改位置 文件:行号 → 证明它的测试”。
