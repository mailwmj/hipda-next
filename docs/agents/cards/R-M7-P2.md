【审查卡 R-M7-P2】对象：`origin/card/M7-P2-links-polish` @ `8d1737933e88081495470221b8c12b8c0402f3e4`（基线 `origin/main@0f067be`）；CI：Android CI #45950041483。任务卡：`/home/mir/Documents/hipda-next.wt/_cards/M7-P2.md`；实现报告：`/home/mir/Documents/hipda-next.wt/_reports/M7-P2.md`。通用清单：`/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`。
本卡重点：
1. a 项（安全）：工具条外链改走 `AndroidExternalLinkOpener` 后，`SafeLinkPolicy.sanitizeHttpUrl` 仍在入口生效；危险 scheme 与大小写/空白/控制字符绕过反例齐全；防自环行为不变。被改的既有外链测试断言是加强而非放宽。
2. b 项 `navSeq`：旋转/进程重建（保存态恢复）不重复跳转；`navSeq` 是否进入保存态及其与旧保存态的兼容；多栏、铰链、返回栈（`PositionStack`）下再点同链接的行为；是否有其他构造 `ThreadNavArgs` 的调用方漏传或误增。
3. c 项：`buildSafeFloorShareUrl` 对非回环一律 https，回环与端口处理正确，未把 `formhash`/`sid` 等参数带出。
4. d/e 项：语义 `enabled` 条件与论坛折叠偏移；Snackbar 余量按顶层槽位判断，`SmsConversation` 等带底部输入栏的页面不回归。
5. f 项（`data/` 敏感区相邻）：`Mutex` 不在持锁时调用可能回到同锁的挂起路径（无死锁），`NonCancellable` 落盘语义保留；A-f 测试改坏实现会红。
6. 与并行卡 M7-P1 的 `git merge-tree`（负责人预检为无文本冲突）是否存在语义冲突：两卡都改了 `ReadingNavContracts.kt`。
