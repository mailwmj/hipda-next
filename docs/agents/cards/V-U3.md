- **验证对象**：任务卡 `U3`（`/home/mir/Documents/hipda-next.wt/_cards/U3.md`），目标 Commit：`fdd73ace1ae49db9e08d351698837a732e906dc8`（基线 `1377a04`）
- **独立验证分支 / Worktree**：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-U3 -b verify/U3-probes fdd73ace1ae49db9e08d351698837a732e906dc8`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md`（先写独立探针，不先抄实现者测试，不读 `_reports/U3.md`）
- **独立探针重点**：
  1. `QuickReplySheet`（`ReplyThread` 隐藏上下文行后）：仅凭 `32dp` 拖动把手执行跟手下拉关闭（慢拖越阈值 & 快甩速度触发）和上拉展开全屏（越阈值交接文本与光标），以及未达阈值松手弹回与快速同帧 `down→move→up` 不卡死；
  2. `QuickReplySheet` 矮屏/弹键盘（净高 `< 180dp`、`< 120dp`）与常规态：`ReplyThread` 底栏左侧 `展开全屏`、`QuotePost` 单行上下文 + 非重复 placeholder、发送钮触控区 ≥ `48dp` 且视觉胶囊高 `32dp`；
  3. `FullEditorScreen`：空正文点击无底色 `primary` 发送钮仍触发 `ContentTooShort`（及发新帖逐级校验），输入正文/上传图片后切换实心 `32dp` 胶囊且触控区 ≥ `48dp`；居中模态点 `×`（`关闭`）静默保存草稿并正确退出、全屏态保持 `←`（`返回`）；
  4. `EmojiPanelSlotBox` 在改版后的 `QuickReplySheet`（`ReplyThread` 无上下文行）与 `FullEditorScreen`（居中模态 `×` + 描边）下与键盘等高互换底缘位移 ≤ `0.5dp`，返回键优先级先关表情面板再关编辑框。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/V-U3.md`
