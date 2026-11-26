【审查卡 R-M7-G1】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（普通档只走 R，须逐张目视涉及新增/更新的截图）。
- 对象：`origin/card/M7-G1-list-topbar-profile` @ `e4690ea1b81a133559fe13210ce3afc76ecfbfe2`（基线 `9338529`）；CI：`45775081734`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/M7-G1.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/M7-G1.md`
- 重点 1：#1 Buy&Sell（`fid=6`）9 项分类筛选仅在 `fid=6` 出现、正确透传 `ThreadListQuery.typeId` 且无 FontAwesome；
- 重点 2：#3 点顶栏空白滚顶是否统一收敛于 `ui/designsystem/topbar/` 一处、不抢占按钮/输入框点击、减动效 0ms，且 0 改动 `feature/threaddetail/*`；
- 重点 3：#4 六类列表长按直达末页（`ThreadNavArgs.openLastPage` 兼容默认 `false`，收藏/关注保留撤销与 `转到最新回复`）及 #8 资料页点大头像精确清除 Coil 内存（含 extras）+ 磁盘缓存（IO 线程）并重载、无头像提示 `用户未设置头像`；
- 重点 4：全部 4 项撤回即红单测完整；0 改动 `feature/threaddetail/*`、`feature/messages/*`、`feature/settings/*`、`feature/app/*`；非授权截图 0 漂移并目视变动截图。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-M7-G1.md`
