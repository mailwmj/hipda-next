【验证卡 V-E6b】按 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md` 执行（高风险卡，**必做合入 `main` 后的真实链路集成探针**）。
- 对象：`origin/card/E6b-edit-delete-post` @ `7ac4c829cc7b86eb882364fb9c39ba7a22bb8390`（合入最新 `origin/main` 后验证）；验证分支：`verify/E6b-probes`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E6b.md`（先独立写探针，不先看实现单测；全部网络测试只用 `MockWebServer`）
- 探针 1（合入 `main` 真实链路编辑与 `还原原文`）：在 `NextAppRoot + ThreadDetailPane` 长按本人楼层点 `编辑`，经 `MockWebServer` 拉取原文，验证初开不显示 `还原原文`→修改后显示→点 `还原原文` 还原并弹 `已还原原文 | 撤销`→点 `撤销` 恢复编辑态→冷却活跃时仍可提交 `EditPost`（无小尾巴、携带 `deletedAttachments`）→删 `edit:<pid>` 草稿并原地刷新楼层不跳位；
- 探针 2（`edit:<pid>` `R-D6 S2` 与返回优先级）：验证编辑未改退出 0 提示、改后退出存草稿弹 `已保存草稿 | 放弃`、恢复旧草稿删空退出调 `deleteDraft` 不复活；验证返回优先级 `EditEditor / DeleteDialog` > `PositionStack` > 多栏返回栈且三栏 `HiGlassMarker ≤ 3`；
- 探针 3（合入 `main` 真实链路删除三路径）：长按本人楼层点 `删除` 经 `MockWebServer` 实测：① 取消（0 请求）；② 失败（保留楼层并提示）；③ 成功删除中间楼层（列表移除、相邻楼层距顶偏差 `≤ 1dp`、无撤销 Snackbar）及删除首楼 `threadDeleted = true` 退回列表；
- 探针 4（快照零意外漂移）：确认全部既有非新增快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/V-E6b.md`
