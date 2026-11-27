- **验证对象**：`U1 + U2 + U3` 完整合入 `main` 后的真实三栏端到端集成验证（`NextAppRoot` / `MultiPaneReadingContainer` 真实链路）
- **目标 Commit**：`ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c`（`main`）
- **独立验证分支 / Worktree**：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-U-int -b verify/U-int-probes ed59fb6eb875a0533eaa9696d1f27ef42a57ab9c`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md`（仅增补独立集成探针测试文件，不改生产代码或既有单测）
- **4 项必验集成探针**：
  1. **三栏顶栏垂直中线齐平（`≤ 1dp`）**：在 `749×559dp`（左 `64dp` 收起图标轨）、`933×696dp`（完整三栏）、`1280×800dp`（平板完整三栏）三个窗口下，于真实 `NextAppRoot` / 三栏容器测量左栏顶栏、中栏论坛顶栏、右栏详情顶栏的真实垂直中线（`top`/`bottom` 及标题/图标中线），断言三栏误差均 `≤ 1dp` 且高度一致 `56dp`，同时复核 `749×559dp` 首屏 3 版块完整可见；
  2. **快速回复叠放新底栏与玻璃卸载**：在三栏态点开左段伸缩「回复」槽拉起 `QuickReplySheet`（`ReplyThread` 无顶部上下文行、底栏左「展开全屏」、32dp 发送胶囊 + 48dp 触控区），断言底层 `ThreadBottomToolbar` 的 `HiGlassRegion` 整块卸载（不再留下底栏玻璃节点），全屏 `HiGlassMarker` 严格 `≤ 3`，且把手下拉收起后底栏干净恢复；
  3. **完整编辑器居中模态叠放三栏**：从快速回复点底栏左「展开全屏」无缝交接进入 `FullEditorScreen` 居中模态卡片（`749×559` / `933×696`），断言单行上下文标题、左上 `×`（`关闭`）与 `0.5dp` 描边、空内容无底色发送钮点击仍触发内联校验、输入内容变实心 `32dp` 胶囊、点 `×` 或系统返回正确关窗存草稿且不破坏底层三栏阅读态与 `PositionStack`；
  4. **全场景玻璃预算守卫（`≤ 3`）**：覆盖单栏、图标轨单栏、紧凑三栏、完整三栏、铰链双栏、快速回复开启、居中模态开启，逐一断言活跃 `HiGlassMarker ≤ 3` 且 `HiGlassSurface.kt` 相对 `1377a04` 0 改动。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/V-U-int.md`
