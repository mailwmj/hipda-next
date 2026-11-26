【审查卡 R-M7-G3】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（普通档只走 R，须逐张目视涉及新增/更新的截图）。
- 对象：`origin/card/M7-G3-floor-actions-detail-top` @ `e57c7f81db59cb6b3a22bcd2bf1b6b432cda2483`（基线 `afa3783`）；CI：`45786387227`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/M7-G3.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/M7-G3.md`
- 重点 1：#5 楼层 `文字选择` 仅在开启时启用 `SelectionContainer`（不干扰默认长按与滚动）；#6 楼层 `分享` 含 `pid` 规范 `https` 链接、强制过 `SafeLinkPolicy` 且有危险 scheme 反例、无违禁词 `回帖`；
- 重点 2：#7 被警告楼层（`isWarned`）角标具高对比度与 `≥ 48dp` 热区、点按经 `MockWebServer` 实测解析 `viewwarning` 标题/记录/说明弹窗与失败重试；
- 重点 3：详情顶栏滚顶复用 `M7-G1` 设计系统统一原语、不误触 3 个圆钮且 0 新增玻璃；确认未破坏已合入的 `E5` 快速回复面板；
- 重点 4：全部 4 项撤回即红单测完整；0 改动 `feature/compose/*` 及 G1/G2/G4 文件；非授权快照 0 漂移并目视变动截图。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-M7-G3.md`
