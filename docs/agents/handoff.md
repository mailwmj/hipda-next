# 负责人交接（2026-11-24 17:15 UTC 更新）

接手的负责人：先按 team-lead skill 的“Session start”读 DISPATCH、lesson、decisions，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际结果为准（lesson.md）。

## 当前状态（2026-11-24 18:25 UTC）
- 已定稿：`tech-plan.md` v0.3、`backlog.md` v0.3、`product-design.md` v0.2.1、`copy.md`。
- 已合并到 main（`657ba07`，CI 绿，99 个测试）：M0-1、M0-2、A1、C1、C1b、D1、D3a。
- B1a（`card/B1a-sanitizer`，会话 037d7a6f）：审查有 6 条阻断（脱敏漏网），在修；修完交原审查员 58bc7d58 复核。
- 第二批（基于 `657ba07`，worktree `../hipda-next.wt/<卡号>`，卡片全文在负责人会话 scratch 目录 `card-<卡号>.md`）：A2 4b20882c、C2 0a35d020、C3 d7fcdc42、D3c 237b469a、M0-3 be2fe2db。
- 审查遗留建议汇总在负责人会话 scratch 目录 `followups.md`，派后续卡时写进卡里（重要：Discuz 会把 6 位十进制实体二次转义，B7–B9/E2a 必须做第二次实体解码）。
- 看 CI 用 `gh run watch <id> --interval 60`（DISPATCH §2）。
- 暂缓：D3d（搜索历史与收藏缓存，用到它的 E10/E11 还远）。

## 用户要做的事
- B1b 完成后：在 debug 工具页登录 → 确认能抓到版块页 → 一键导出样本。
- 有真机折叠屏的话，阶段验收时测一次。

## 会话 ID（都带标签 team-lead=hipda-next）
- 第一批实现（可复用做后续修改）：A1 3415ca52、B1a 037d7a6f、C1 0732782b、C1b 60c8eb8c、D1 dffbea3f、D3a 355358c1、M0-1/M0-2 e0494703。
- 审查/验证：R-M0-2 5701a912、V-M0-2 6be2196f、R-C1b 9580bf7a、R-D3a b1c3ba62、R-A1 95482924、V-A1 b90255e2、R-D1 1be743d1、R-C1 af28ce38、V-C1 24be6cd5、R-B1a 58bc7d58。
- 方案阶段：C2(产品评审) dd219b3b、H1(Hydrogen 顾问) ae1f9fc4、C1(技术评审) 4e554492、V-M0-1 2054f242、R-M0-1 03d809eb；调研与核查 P1 6e72a897、R1 afb8550e、R2 714fe8b8、S1 f7fec42d、F1–F4。
