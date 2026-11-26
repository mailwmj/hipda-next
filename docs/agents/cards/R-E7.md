【审查卡 R-E7】按 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md` 执行（高风险卡，配合 `V-E7`；须逐张目视新增 `emoji_panel_*.png` 与授权更新的 `quick_reply_*.png`、`editor_*.png`）。
- 对象：`origin/card/E7-emoji-panel` @ `0428dd4fc60de1fde4e86d74ed1e98dbc7154e45`（基线 `adc777b`）；CI：`45812485275`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E7.md`、交付报告 `/home/mir/Documents/hipda-next.wt/_reports/E7.md`
- 重点 1：等高原地互换与限高——`IME ↔ 表情` 切换全程底缘零位移（无先塌陷再弹起）；无键盘高度时精确遵守 `min(280dp, 45%)` 及 `< 480dp` 时 `35%`；按钮 `HiIconSwitch` 切 `表情`/`键盘`；
- 重点 2：`:emoji` C1b 与 `C1b#3`——直接消费 `EmojiMap.groups`/`drawableFor(code, dark)`，自适应列数每格 `≥ 48dp`，首图分类图标 + `HiMotion.spatialFast()` 指示器；分类 `contentDescription` 来自 `strings_emoji.xml`（0 硬编码 `EmojiGroup.name`，`:emoji/*` 0 改动）；
- 重点 3：双宿主集成与返回优先级——`di/ComposeEmojiModule.kt` 绑定 `E5`+`E6` 插槽（**以合入后的 `ComposeContracts.kt` 为准，派发前负责人复核**）；光标中间/选区插入代码无损；`BackHandler` 按打开态重挂先关表情面板不误关编辑器/快速回复；
- 重点 4：文件边界与截图——0 改动 `feature/routing/*`、`NextAppRoot.kt`、`PostBodyRenderer.kt`、`:emoji/*`、`data/*`；核对 `_reports/E7.md` 逐张列出的 `quick_reply_*.png` / `editor_*.png` 仅因 `表情` 按钮出现而变化并逐张目视，其余快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/R-E7.md`
