【审查卡 R-E6】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（高风险卡，配合 `V-E6`；须逐张目视新增 `editor_*.png` 与授权更新的 `quick_reply_*.png`）。
- 对象：`origin/card/E6-full-editor` @ `3cbcb9c694782740597e2d154500da80065b5c05`（基线 `afa3783`）；CI：`45790084493`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E6.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/E6.md`
- 重点 1：`R-D6 S1/S2` 与 `R-D1`——`PostModels.kt` 三类 `ImageUploadInput.original` 默认均为 `false` 且有压缩单测（未放宽既有断言）；恢复草稿删空退出显式调 `deleteDraft`；`今日新帖` 发新帖必先选定 `fid > 0` 再建 `new:<fid>` 草稿；
- 重点 2：`E5` 交接与多图上传——`di/ComposeEditorModule.kt` 绑定插槽（**以合入后的 `ComposeContracts.kt` 为准，派发前负责人复核**），文本/光标/引用零丢失且零重弹草稿提示；正文绝不暴露 `[attachimg]` 源码文本、未插图角标与自动附尾、`×` 取消上传、失败单张重试与 `MockWebServer` 实测；
- 重点 3：多栏 §4.2 与 `还原原文`——`411` 全屏、`640`/`749`/`933`（右栏 `< 560dp`）全三栏居中 `≤ 680dp`、`1280`（右栏 `≥ 560dp`）右栏内展开且 `HiGlassMarker ≤ 3`；`还原原文` 仅偏离线上原文时出现且走令牌撤销；无 `引用`/`粗体` 快捷按钮；
- 重点 4：文件边界与截图——确认 0 改动 `feature/routing/*`、`NextAppRoot.kt`、`PostBodyRenderer.kt`、`:emoji/*`；核对 `_reports/E6.md` 逐张列出的 `quick_reply_*.png` 仅因 `展开全屏`/`插入图片` 按钮出现而变化，逐张目视 `quick_reply_*.png` 与 `editor_*.png`，其余快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-E6.md`
