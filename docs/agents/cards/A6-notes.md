# 给 A6 的遗留说明

起草 A6（需要人工操作时的可见验证面板 + 后台等待 2 秒提示）时带入以下约定与遗留：

- `ChallengeState.Cooldown` 到期后惰性归位 `Idle`（来自 A4b 审查遗留；展示倒计时或状态时结合当前时间判断冷却是否已到期）。
- “重新验证”按钮直接传 `AccessPriority.UserExplicit`（由 `SiteAccessGateway` 清除冷却并允许主动发起验证）。
- `ChallengeInteractionPresenter` 由 A6 实现，并替换 A5b 可替换 Hilt 模块中的默认取消实现，复用同一个运行中的隐藏 `WebView` 切为可见面板（不重新加载）。
