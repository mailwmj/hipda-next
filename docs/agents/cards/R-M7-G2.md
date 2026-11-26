【审查卡 R-M7-G2】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（普通档只走 R，须逐张目视涉及新增/更新的截图）。
- 对象：`origin/card/M7-G2-system-account-viewer-about` @ `4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf`（基线 `9338529`）；CI：`45773214724`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/M7-G2.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/M7-G2.md`
- 重点 1：#13 移除非当前已存账号确认后仅删目标凭据、不影响当前活跃账号与 Cookie；#14 冷启动在 `feature/app/` 调用 `NotificationScheduler.syncFromSettings()` 且 0 改动 `HiApplication`/`HiNextApplication`；
- 重点 2：#9 `ThreadImageGallery` + `PostImageBlock` 正确解析多图 `urls` 与精确 `initialIndex`（含重复 URL 区分与单图安全回退），所有 URL 经 `http`/`https` 白名单；
- 重点 3：#12 关于页 `更新记录`/`捐助名单`/`开源许可` 均在 IO 线程读取 `assets/{release-notes,donors,license}.txt` 并支持长文本滚动；按 `versionCode` 首次启动仅弹一次更新记录、同版本/旋转不重弹；
- 重点 4：全部 4 项撤回即红单测完整；0 改动 `ThreadDetailPane.kt` 及 G1/G3/G4 文件；非授权快照 0 漂移（确认首启弹窗不污染既有截图）并目视变动截图。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-M7-G2.md`
