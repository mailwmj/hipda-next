- **审查对象**：任务卡 `U3`（`/home/mir/Documents/hipda-next.wt/_cards/U3.md`），分支 `card/U3-compose-polish`
- **目标 Commit**：`fdd73ace1ae49db9e08d351698837a732e906dc8`（基线 `1377a04`）
- **CI 证据**：`gh run view 46046715744`
- **通用清单**：严格执行 `/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`（**禁止阅读**实现者报告 `_reports/U3.md`）
- **本卡重点**：
  1. 核对 `QuickReplySheet.kt`：`ReplyThread`（已登录）隐藏顶部上下文行且 `展开全屏` 移至底栏左、`QuotePost`/`ReplyPost` 保留单行上下文配 `输入回复内容`、输入框去粗糙外边框、发送按钮 `32dp` 视觉胶囊 + `minimumInteractiveComponentSize()`（`48dp` 触控区），以及同步拖拽 `dragOffsetYPx` + `CoroutineStart.UNDISPATCHED` + `try/finally` 无回归；
  2. 核对 `FullEditorScreen.kt` + `FullEditorViewModel.kt`：顶栏单行上下文标题、回复态隐藏重复视觉 placeholder 但保留无障碍语义、仅新增 `hasSendableContent` 派生属性且 `canTapSend` 与校验链 0 改动、发送钮 `32dp` 视觉高 + `48dp` 触控区（空态无底 `primary` 文字仍可点触发校验）、居中模态左上 `×`（`关闭`）+ `0.5dp outlineVariant @ 0.5f` 边框、底栏不加多余字数/草稿噪音；
  3. 核对 `EditAndDeletePostIntegrationTest.kt`（2 行变更）与 `EmojiPanelBehaviorTest.kt`（仅改居中模态关闭描述）是否属合理预期更新且未放宽断言；核对未触碰 `HiGlassSurface.kt`/`HiGlassTokens`、`docs/design/copy.md`；
  4. **视觉目视复验**：只读查看 `/home/mir/Documents/hipda-next.wt/U3/hipda/src/test/snapshots/images/` 中本卡重录的 27 张 PNG（覆盖 `quick_reply_*`、`editor_*`、`emoji_panel_*`、`edit_delete_*`），确认去重清爽、发送钮尺寸克制优雅、模态边界清晰、深浅色与窄/宽屏无裁切重叠，非本卡既有快照 0 漂移。
- **报告路径**：`/home/mir/Documents/hipda-next.wt/_reports/R-U3.md`
