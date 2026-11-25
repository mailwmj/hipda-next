# 设计卡 D-3pane：大屏三栏布局（版块 | 帖子列表 | 帖子内容）——调研 + 设计稿修订

【角色】设计方案起草者。只改文档，不写代码。

【用户已定】（2026-11-25）
1. 大屏用三栏：左栏版块与功能入口，中栏帖子列表，右栏帖子内容。
2. 左栏内容 = 旧版抽屉：上方功能入口（短消息、信息提醒、浏览记录、今日新帖、我的帖子、我的回复、收藏关注、设置，附未读数），下方版块列表（用户关注的版块，带图标）；三栏时左栏**取代**现有 80dp 左侧导航轨，列表顶部的版块标签行在三栏时隐藏。
3. **主流折叠屏内屏都要能显示三栏**。用户设备是三星 Galaxy Z Fold8。

【第一步：调研（每条附来源链接或文件:行号）】
- 主流折叠屏内屏在默认显示缩放下的窗口宽度（dp），竖拿和横拿各一个值：Galaxy Z Fold8（查不到可靠数据就写"未知"，并给出按分辨率与默认密度推算的值和推算过程）、Z Fold7、Z Fold6、Pixel 9/10 Pro Fold、荣耀 Magic V 系列、vivo X Fold 系列、OPPO Find N 系列。再给常见平板横竖屏宽度。
- Material 3 自适应规范对三栏 / 常驻抽屉 / 可收起侧栏的建议（window size class 的 Medium/Expanded/Large/Extra-large 断点，`ListDetailPaneScaffold`/`SupportingPaneScaffold`/`NavigationSuiteScaffold`/`PermanentNavigationDrawer` 的现成能力与限制）。
- 成熟产品在折叠屏内屏上怎么做三栏：Gmail、Outlook、Telegram、Slack、Reddit 类客户端等，记录它们在 ~700–900dp 宽度时左栏的形态（常驻、可收起成图标轨、浮层等）。

【第二步：方案】基于调研，在 worktree `/home/mir/Documents/hipda-next.wt/D-3pane`（分支 `design/three-pane`）修改 `docs/design/product-design.md`：
- §4.1 窗口分档与导航形态：重写分档表，给出三栏的启用门槛和每栏宽度规则（最小宽、默认宽、可拖动/吸附档位、左栏可否收起成图标轨及收起后的宽度），用调研得到的设备宽度逐台演算（列出每台设备竖/横时是几栏、每栏多宽）。正文栏宽度必须保证阅读体验（给出你认为的最小值和理由）。门槛要让主流折叠屏内屏至少在一个常用握持方向上显示三栏；做不到时写清楚为什么，并给出最接近的方案。
- 与现有规则的衔接：铰链分隔、840dp 断点连续性、底部胶囊在 600–839dp 的位置、顶栏、栏随滚动、"列表收起"吸附、返回栈（三栏时点版块/点帖子分别替换哪一栏）、折叠/展开/旋转时三栏↔两栏↔单栏的状态迁移（§4.3）、§4.2 各页面在三栏下的布局（非论坛目的地，如短消息/提醒，在三栏下中右两栏放什么）、编辑器/登录/看图的覆盖规则。
- §3 信息架构若因左栏取代导航轨需要调整（例如左栏功能入口与底部胶囊目的地的对应关系），一并改。
- §10 验收：截图矩阵加入三栏宽度（含 Fold8 的实际宽度），栏宽单测的断点列表更新。
- 在 `docs/design/` 新增或在设计稿内附一节"三栏方案的取舍"：被否决的备选（例如三栏门槛定在 1200dp、左栏只放版块保留导航轨）及否决理由。

【第三步：影响清单】写入报告：已合并代码中哪些要改（`hipda/src/main/java/net/jejer/hipda/ui/shell/` 下 HiAdaptiveShell、HiNavigationRail、ShellIndicatorLayout、ShellNavigationMode 等，给文件:行号），建议拆成哪几张实现卡、依赖关系、每张卡的验证方法。

【边界】只改 `docs/design/` 下文件；不改代码、不改 main；不访问论坛。提交到 `design/three-pane` 并推送（docs-only 不触发 CI）。

【交付】最后一条回复 ≤600 字，首词 DONE/PARTIAL/NEEDS-INPUT/STUCK：commit、三栏门槛与每栏宽度规则一句话、Fold8 的结论（是否三栏、各栏宽）、未查实的事实、需要用户拍板的问题（如有）。调研明细、逐台演算和影响清单写 `/home/mir/Documents/hipda-next.wt/_reports/D-3pane.md`。
