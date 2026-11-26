【验证卡 V-E7】按 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md` 执行（高风险卡，**必做合入 `main` 后的真实链路集成探针**）。
- 对象：`origin/card/E7-emoji-panel` @ `0428dd4fc60de1fde4e86d74ed1e98dbc7154e45`（合入最新 `origin/main` 后验证）；验证分支：`verify/E7-probes`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E7.md`（先独立写探针，不先看实现单测；**涉及 `E5` 签名以合入后的 `ComposeContracts.kt` 为准，派发前负责人复核**）
- 探针 1（合入 `main` 真实链路逐帧 `IME ↔ 表情` 底缘零位移）：在 `NextAppRoot + QuickReplySheet` 与 `FullEditor` 两条真实路径下，`autoAdvance=false` 模拟 IME 展开→点 `表情`（IME 收起过程）→点 `键盘`（IME 重现过程），逐帧断言工具栏/输入框底缘位移 `|Δbottom| == 0dp` 且减动效 0ms；
- 探针 2（无键盘高度回退限高与三栏边界）：在无 IME 高度下实测 `891dp`（`280dp`）、`600dp`（`45%`）、`479dp`/`411dp`（`< 480dp` 钳制 `35%`）；在 `749dp`/`933dp` 三栏右栏内实测表情面板不跨分隔线且 `HiGlassMarker ≤ 3 / LayerBackdrop ≤ 2 / drawBackdrop ≤ 5`；
- 探针 3（光标插入、返回优先级与 `C1b#3`）：实测双宿主中间光标与选区插入 `:)` / `{:2_41:}` / `{:3_57:}` 保持光标与深浅色切图；依次验证 `EmojiPanel` > `QuickReplySheet / FullEditor` > `PositionStack` 返回不穿透；验证分类无障碍文案取自 `strings_emoji.xml`；
- 探针 4（边界与快照零意外漂移）：确认未触碰 `feature/routing/*`/`NextAppRoot.kt`/`PostBodyRenderer.kt`/`:emoji/*`，非授权快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/V-E7.md`
