【审查卡 R-E8】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（**普通页仅走 R，须目视检查全部新增 `messages_*.png`**）。
- 对象：`origin/card/E8-messages-and-notify` @ `b40fb4a`（基线 `89f8e37`）；CI：`45732999389`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E8.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/E8.md`
- 重点 1：短消息乐观发送、失败重发/删除撤销、15 秒冷却（倒计时允许继续输入）与清空二次确认是否真实覆盖且无竞态；
- 重点 2：提醒是否严格单条点击才标已读（绝不进入即全清），`全部已读` 独立且与 `NotifyRepository` 一致；
- 重点 3：短消息气泡链接/图片 `http`/`https`/经校验 `mailto` scheme 白名单及全套危险 scheme 反例测试是否扎实；
- 重点 4：`R-D9 S2` 通知文案归口 `strings_notification.xml` + `docs/design/copy.md` 术语合规；文件边界零越界（不碰 `feature/app/*` 等）；
- 重点 5：逐张 `read_file` 目视检查新增 `messages_*.png` 排版/对比度/深浅色与既有快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-E8.md`
