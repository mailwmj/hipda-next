# 任务卡 B1c：补上脱敏漏网的本人 UID，并清洗已导出的真机样本

【背景】用户真机导出的样本在分支 `origin/samples/b1b` @ `01907ca`，`SamplesAreSanitizedTest` 已通过。负责人独立扫描发现：`hipda/src/test/resources/samples/user_info/user_profile.html` 第 121 行"加为好友"链接 `my.php?item=buddylist&newbuddyid=<本人UID>&buddysubmit=yes` 中仍保留**用户本人的真实 UID**。`HtmlSanitizer` 没替换，`SampleLeakScanner` 也没报。用户名、formhash、IP 已正确处理。

【隐私规则（硬性）】不得把用户真实 UID 写进任何提交、测试、文档、报告或回复。需要时从上述文件第 121 行运行时读取。测试一律用假 UID（如 `424242`）。

【目标】
1. `core-parser` 的 `HtmlSanitizer`：只要知道本人 UID，页面中任何位置**作为独立数字记号出现的本人 UID**（URL 查询参数如 `uid=`、`newbuddyid=`、`touid=`、`buddyid=`、`authorid=`，JS 变量，属性值，纯文本），前后不是数字时一律替换为脱敏占位值（沿用现有 UID 占位约定）。不得误伤恰好包含该数字串的更长数字。
2. `SampleLeakScanner`：同样按"独立数字记号"检测本人 UID，命中即报泄漏。
3. 测试：用假 UID 覆盖 `newbuddyid=`、`touid=`、JS 变量、纯文本这几种形态，脱敏后为 0 泄漏、扫描器能拦住未脱敏版本；再加一个"更长数字包含该 UID 但不应替换"的反例。改坏一行测试会红。
4. 清洗样本：从 `origin/samples/b1b` 取出 17 个样本文件，用修好的 `HtmlSanitizer` 以本人身份（UID 从第 121 行读取；用户名已脱敏，不需要）重新脱敏，再用 `SampleLeakScanner` 复查为 0。把清洗后的样本提交到本卡分支的 `hipda/src/test/resources/samples/`（一次提交里同时有代码修复和样本），提交说明不得出现真实 UID。写一个一次性脚本或测试辅助完成清洗，用完不留进 `src/main`。
5. 自查：在清洗后的全部样本里用 `grep -rw` 查本人 UID，结果必须为 0（报告里只写"0 处"，不写 UID）。

【范围】`core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/`、对应测试、`hipda/src/test/resources/samples/`。不改 B1b 的 debug 页面逻辑（除非导出流程需要把本人 UID 传给新的规则，那就最小改动并说明）。

【交付】worktree `/home/mir/Documents/hipda-next.wt/B1c`，分支 `card/B1c-uid-scrub`，基于 `origin/main` @ `65233b6`。先本地跑 `:core-parser:test` 和相关测试再推送。最终回复 ≤600 字：DONE/PARTIAL/NEEDS-INPUT/STUCK + commit + CI 链接 + 每条目标一行；细节写 `/home/mir/Documents/hipda-next.wt/_reports/B1c.md`。
