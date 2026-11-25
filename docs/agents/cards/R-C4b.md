# 审查卡 R-C4b：独立代码审查导航随滚动隐藏与内容留白（commit 6e92adc）

【角色】你是独立代码审查者，只读。

【边界】审查对象：`origin/card/C4b-shell-scroll` @ `6e92adc93afa1ac54ea7e90b4f03c29641879344`，相对 `92e01d3`。只读：`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 6e92adc:<路径>`、`git diff 92e01d3...6e92adc` 读代码；可把截图导出到 `/tmp/R-C4b/`（只写这里）查看；不要 checkout、提交或推送，不要在主目录切换分支，不要编译。CI 用 `gh run view 45538669340`。

【背景】
- 任务卡全文：`/home/mir/Documents/hipda-next.wt/_cards/C4b.md`（范围和验收以它为准）。
- 规格：`docs/design/product-design.md` 第 4.1 节“键盘”、第 5 节“毛玻璃”留白规则与“导航胶囊”、第 6.3 节“栏随滚动”“按压”“一级目的地切换”、第 10.1 节“键盘”“可打断”“减少动态效果”、第 10.3 节。
- 后续：E 线页面会把列表放进 `HiAdaptiveShell` 的 content，用它给的 `PaddingValues`；E3 做双栏（600–839dp 胶囊 + 工具条共用偏移）。
- 阻断标准（`docs/agents/DISPATCH.md` 第 4 节 + 卡片）：CI 失败、违反卡片范围或声明、破坏既有测试/契约、折叠展开旋转崩溃或丢失阅读状态——必须给出具体触发路径。其他都是建议。

【审查重点】
1. 滚动联动：位移是否逐帧等于滚动量；吸附阈值和令牌；fling 结束、拖动无 fling 松手、嵌套滚动（列表里套横向列表）、多个滚动容器（切换目的地后旧列表还在动画）时是否正确；隐藏只用 `graphicsLayer`、无重组风暴（滚动时 `HiAdaptiveShell` 及内容是否每帧重组）。
2. 回到可见：短内容、切换目的地、键盘收起；旋转/折叠展开/形态切换（胶囊 ↔ 导航轨）时栏状态是否合理复位，会不会卡在隐藏状态。
3. 键盘：`isImeVisible || ime.getBottom > 0` 的判断是否会在没有键盘时误判（例如某些设备 ime inset 有残留）；0ms 隐藏的实现方式。
4. 留白：`PaddingValues` 是否按“胶囊完全可见时”计算、不随滚动变化；`onSizeChanged` 回填高度是否会造成首帧跳动或额外一次布局；`Rail` 形态的留白。
5. 导航轨层级：在导航轨里局部 `MaterialTheme(colorScheme = cs.copy(surface = surfaceContainerLow))` 来影响 `HiGlassSurface` 的着色，是否是可以接受的做法（会不会影响导航轨内其他用到 `surface` 的组件、主题切换动画、未来对 glass 的修改），有没有更干净的办法。
6. 复杂度：`ui/shell/` 从 715 到 885 行，其中 `ShellIndicatorLayout.kt` 310 行；按 DISPATCH §5 界面卡条款判断是否有可删的过度设计，给出预计行数。
7. 测试质量：能否在改坏时失败（举“改坏一行测试仍通过”的例子，若存在）；截图与卡片要求是否一致。
8. 范围：没改 `glass/`、`motion/`、`feedback/`、`state/`、主题文件、共享构建文件；基线只新增 `shell_scroll_*` 并重录 4 张导航轨图。

【交付】最后一条回复第一个词：DONE、NEEDS-INPUT 或 STUCK。前约 1200 字：判定 APPROVE / CHANGES-REQUESTED + 被审 commit `6e92adc`；阻断项（触发路径、文件:行号、后果）；建议项一行一条（含预计行数）。
