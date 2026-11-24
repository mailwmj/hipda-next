# 项目经验

> 只记录仍然有效、属于本项目的经验，按主题分组，括号里写首次发现的日期。模型脾性写进 team-lead skill 的 models.md。已经由检查守住的经验缩成一行，写明是哪个检查。

## 站点
- 用 curl 访问 `https://www.4d4y.com/forum/` 会得到 Cloudflare 验证页（HTTP 403，`cf-mitigated: challenge`），直接用 OkHttp 抓页面会被拦。（2026-11-24）

## 构建
- `gradle.properties` 已经进仓库（M0-1 把它移出了 `.gitignore`）；Gradle 配置写在里面 CI 才读得到。（2026-11-24）
- targetSdk 在 M7（旧界面全部删除）之前保持 30；升级会触发强制全面屏、预测性返回、存储权限和通知权限的行为变化。（2026-11-24）
- R8 full mode 会剥掉旧代码靠反射用到的泛型签名和类（Gson TypeToken、cookie-store、jobqueue、Iconics），release 包冷启动就崩；由 CI 的 release 模拟器冒烟守住，加依赖或改混淆规则后要跑一次 emulator=true。（2026-11-24）

## 协作
- 只读审查员不能在主目录里 `git checkout`：负责人的提交会落到游离的 HEAD 上，推送 `main` 时什么都没推上去。让审查员用 `git show <commit>:<路径>` 读文件，或者在自己新建的 worktree 里检出。（2026-11-24）
- 上一任负责人的会话额度用完后，它“排好”的定时任务和后续卡片并不存在：交接时以 `list_schedules`、`list_agents` 的实际结果为准，不以交接文件为准。（2026-11-24）
