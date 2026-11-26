【验证卡 V-E6】按 `/home/mir/Documents/hipda-next.wt/_cards/_verify_common.md` 执行（高风险卡，**必做合入 `main` 后的真实链路集成探针**）。
- 对象：`origin/card/E6-full-editor` @ `3cbcb9c694782740597e2d154500da80065b5c05`（合入最新 `origin/main` 后验证）；验证分支：`verify/E6-probes`
- 参考：任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E6.md`（先独立写探针，不先看实现单测；**涉及 `E5` 签名以合入后的 `ComposeContracts.kt` 为准，派发前负责人复核**）
- 探针 1（合入 `main` 真实链路快速回复→完整编辑器零丢失交接）：在 `NextAppRoot + ThreadDetailPane + QuickReplySheet` 输入中文+光标位于中间+引用某楼，分别点 `展开全屏` 与 `插入图片`，验证文本/选区/引用 0 丢失、共用 `reply:<tid>` 草稿且无重复草稿提示，`插入图片` 自动触发选图回调；
- 探针 2（多栏 §4.2 形态、玻璃预算与返回优先级）：在 `749dp`/`933dp`（右栏 `< 560dp`）实测全三栏居中模态宽 `min(W-48, 680dp)`，在 `1280dp`（右栏 `≥ 560dp`）实测右栏内展开且 `HiGlassMarker ≤ 3 / LayerBackdrop ≤ 2 / drawBackdrop ≤ 5`；验证 `ImageViewer/Login` > `FullEditor`（退出存草稿）> `PositionStack` 返回顺序；
- 探针 3（`MockWebServer` 多图上传与 `R-D6 S1/S2`/`R-D1`）：真实 `ImageUploadRepository` + `MockWebServer` 实测默认 `original = false` 压缩、并发进度 `正在上传 {current}/{total}`、单张失败重试、点缩略图插入无 `[attachimg]` 源码文本、未插图发送自动附尾；验证 `编辑` `还原原文` + 撤销、`R-D6 S2` 清空旧草稿不复活、`今日新帖` 未选版块拦发；
- 探针 4（边界与快照零意外漂移）：确认未触碰 `feature/routing/*`/`NextAppRoot.kt`/`PostBodyRenderer.kt`，非授权快照 0 漂移。
- 详报输出：`/home/mir/Documents/hipda-next.wt/_reports/V-E6.md`
