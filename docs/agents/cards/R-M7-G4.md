【审查卡 R-M7-G4】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（普通档只走 R，须逐张目视涉及新增/更新的截图）。
- 对象：`origin/card/M7-G4-messages-compose-copy` @ `6df20f35f009d95eab27de027a1f6b43a46863ce`（基线 `9338529`）；CI：`45771792019`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/M7-G4.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/M7-G4.md`
- 重点 1：#2 短消息列表顶栏 `发送短消息` 按钮仅在 `Sms` 态出现，新建对话框支持任意用户名调用 `SmsRepository.sendSms(uid = null, username = ..., message = ...)`、含空校验/15 秒冷却/失败保输入/成功刷列表，且 0 违禁词（无 `私信`/`站内信`）；
- 重点 2：#10 短消息气泡长按弹出 `复制`，经 `ClipData.newPlainText` 写入剥离 HTML 的纯文本并弹 `内容已复制`，不冲突正文链接点击与失败 `!` 重发/删除；
- 重点 3：两项撤回即红单测完整；仅修改 `feature/messages/*` 与 `strings_messages.xml`，0 越界；
- 重点 4：核对 `_reports/M7-G4.md` 逐张列出的短消息列表截图变化并目视审查，其余快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-M7-G4.md`
