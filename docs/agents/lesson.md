# 项目经验

> 只记录仍然有效、属于本项目的经验，按主题分组，括号里写首次发现的日期。模型脾性写进 team-lead skill 的 models.md。已经由检查守住的经验缩成一行，写明是哪个检查。

## 站点
- 用 curl 访问 `https://www.4d4y.com/forum/` 会得到 Cloudflare 验证页（HTTP 403，`cf-mitigated: challenge`），直接用 OkHttp 抓页面会被拦。（2026-11-24）

## 构建
- `.gitignore` 忽略了 `gradle.properties`，写在里面的 Gradle 配置不会进仓库，CI 也读不到。（2026-11-24）

## 协作
- 只读审查员不能在主目录里 `git checkout`：负责人的提交会落到游离的 HEAD 上，推送 `main` 时什么都没推上去。让审查员用 `git show <commit>:<路径>` 读文件，或者在自己新建的 worktree 里检出。（2026-11-24）
