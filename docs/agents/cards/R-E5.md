【审查卡 R-E5】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（高风险卡，配合 `V-E5`；须逐张目视新增 `quick_reply_*.png`）。
- 对象：`origin/card/E5-quick-reply-sheet` @ `73a64682e336e08fb362659e5e078a83ed7b834d`（基线 `92e1563`）；CI：`45762412787`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E5.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/E5.md`
- 重点 1：玻璃拓扑——展开覆盖底部工具条时 `ThreadBottomToolbar` 的 `HiGlassRegion`/`HiGlassMarker` 是否彻底卸载，三栏满配严守 `≤ 3 / ≤ 2 / ≤ 5` 且右栏暗罩不跨分隔线；
- 重点 2：IME 与手势隔离——`min(300dp, 可视净高×60%)` 与 `< 180dp` 紧凑态是否零溢出；输入框多行滚动/选区是否优先于下拉关闭；非手势关闭与返回优先级是否正确；
- 重点 3：草稿与 `R-D6 S2`——恢复旧草稿后删空退出是否显式调 `deleteDraft`（撤回必红）；30 秒冷却倒计时、非末页回复不移位+压栈查看、过滤模式回复、`展开全屏`/`插入图片` 零重弹草稿提示；
- 重点 4：底部仅留 `插入图片`/`表情`/`发送`（无 `引用`/`粗体`、无多图条）；授权清单外零改动、零放宽预算、既有 PNG 0 漂移并逐张目视 `quick_reply_*.png`。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-E5.md`
