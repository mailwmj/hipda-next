【验证卡 V-E5】按 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md` 执行（高风险卡，**必做合入 `main` 后的真实链路集成探针**）。
- 对象：`origin/card/E5-quick-reply-sheet` @ `73a64682e336e08fb362659e5e078a83ed7b834d`（合入最新 `origin/main` 后验证）；验证分支：`verify/E5-probes`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E5.md`（先独立写探针，不先看实现单测）
- 探针 1（合入 `main` 真实链路 IME 逐帧升降与矮屏限高）：`NextAppRoot + ThreadDetailPane + QuickReplySheet` 模拟 IME 逐帧升降，校验 `autoAdvance=false` 高度钳制 `min(300dp, 可视净高×60%)`、`< 180dp` 矮屏隐藏把手/草稿条零裁切、减动效 0ms；
- 探针 2（三栏/铰链右栏边界与玻璃预算）：`749dp`/`933dp` 三栏展开快速回复，实测面板与暗罩严格在右栏内不跨分隔线，底部工具条 `HiGlassMarker` 与 `DrawBackdropElement` 彻底卸载（全屏 `≤ 3 / ≤ 2 / ≤ 5`），关闭后恢复；
- 探针 3（合入 `main` 真实链路返回优先级与手势隔离）：依次叠加看图/抽屉/快速回复/跳楼 `PositionStack`/三栏详情，逐次 `onBackPressed()` 验证精确优先级且不穿透；多行输入框内竖滑不误关、把手/顶边超界下拉存草稿 + `R-D6 S2` 清空旧草稿不复活；
- 探针 4（快照零漂移）：确认既有非 `quick_reply_*.png` 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/V-E5.md`
