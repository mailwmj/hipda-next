【审查卡 R-M6-polish】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（普通档小修卡，仅走 R）。
- 对象：`origin/card/M6-polish` @ `0e186fbf7371034e1847f9c71e8ef61691646911`（基线 `3617fad`）；CI：`45752239193`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/M6-polish.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/M6-polish.md`
- 重点 1：硬边界核查——零修改 `feature/threaddetail/*`（避开在途 `E4`），③ 的 LRU 保存态淘汰严格只在 `feature/app/MultiPaneReadingContainer.kt` 实现；
- 重点 2：逐项核实 ①–⑥ 真实闭合与单测“撤回必红”（① 冷却/错误顶替旧撤销条；② `NextAppStateSaver` 不存短消息用户名且兼容旧 16 元与回填；③ 20 帖 LRU 不误淘 MRU/活跃帖；④ 黑名单排在途移除项；⑤ 搜索/收藏无未决令牌时刷新秩表；⑥ 多图回收重返不重复弹 `已加载原图`）；
- 重点 3：核查既有测试调整仅限 ② 项预期且未放宽断言；既有 Roborazzi 快照 0 漂移；无生产测试钩子。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-M6-polish.md`
