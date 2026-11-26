【审查卡 R-E6b】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（高风险卡，配合 `V-E6b`；须逐张目视新增 `editor_edit_*.png`）。
- 对象：`origin/card/E6b-edit-delete-post` @ `7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`（基线 `adc777b`）；CI：`45813940387`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E6b.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/E6b.md`
- 重点 1（编辑与还原原文）：`FloorContextMenuOverlay.onEdit` 复用 `E6` 完整编辑器与 §4.2 多栏投放；`还原原文` 仅偏离线上原文时出现且走令牌撤销；`EditPost` 免 30s 冷却、不附小尾巴、正确传 `deletedAttachments`、正文 0 处 `[attachimg]` 原文；
- 重点 2（`edit:<pid>` 与 `R-D6 S2`）：未改原文退出 0 保存 0 提示；恢复旧草稿删空或清空退出显式调 `deleteDraft(DraftKey.edit(pid))`（撤回必红）；
- 重点 3（删除自己楼层与离线红线）：确认框文案逐字对齐 `copy.md:145`（`删除这条回复？` / `删除后无法恢复。`），删除不可撤销（无撤销 Snackbar）；仅用 `MockWebServer` 覆盖成功（保阅读位 + 首楼整帖删退回）、失败（保留楼层提示）、取消（0 请求）三路径；
- 重点 4（边界与漂移）：确认未破坏 `E5`/`E6`/`M7-G3`，0 改动禁改目录，既有快照 0 漂移并逐张目视 `editor_edit_*.png`。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-E6b.md`
