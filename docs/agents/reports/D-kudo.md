# D-kudo 设计稿修订报告：Kyant backdrop 液态玻璃与 kudo 交互定稿（v0.4）

- **Worktree / 分支**：`/home/mir/Documents/hipda-next.wt/D-kudo` / `design/kudo-glass`（基于 `9c3b8ef`，已推送 `origin/design/kudo-glass`）
- **Commit**：`102b1c3ed41ff82747642cfabec385bbb5279483`（`102b1c3`，纯文档改动 `docs-only`，4 个文件 `+135 / -85`）
- **调研依据**：`/home/mir/Documents/hipda-next.wt/_reports/G-kudo.md`（kudo-android，Apache-2.0，第三节 5 条建议）+ 2026-11-25 用户 4 条拍板（覆盖报告与之冲突的推荐）
- **外部依赖核查**：实查 Maven Central 元数据与 AAR 字节码确认：`io.github.kyant0:backdrop` 最新稳定版为 `2.0.1`（传递 `shapes:1.2.1`，`minCompileSdk=37`，Kotlin `2.4.10`，主包名 `com.kyant.backdrop.**` / `com.kyant.shapes.**`），纯 Android Jetpack Compose 备选稳定版为 `1.0.6`（`shapes:1.2.0`）；两版本 AAR **均未随包附带有效 consumer ProGuard 规则**（`2.0.1` 无 `proguard.txt`，`1.0.6` 为 0 字节），故必须在 `proguard-rules.txt` 显式加 `-keep` 并走 CI `emulator=true` release 冒烟。

---

## 一、修改摘要（逐文件）

| 文件 | 版本 | 改动要点 |
|---|---|---|
| [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md) | v0.3 → **v0.4** | 更新版本说明与 §3.1（底部胶囊 4 项无 `+` + 跟手拖拽预选；论坛透明吸顶版块标签行 + `BalancedTabRow` + 荧光笔高光）、§4.1/§4.2（窄栏三段工具条紧凑态与右栏限宽、圆形 `全屏阅读` 按钮、三栏/铰链位置与快速回复面板右栏限定）、§5（删除“顶栏纯色不用毛玻璃”；重写 Kyant `backdrop` 三级硬件分档 + 四路环境降级 + 4 档参数起点 + 模糊态与降级态 ≥ 4.5:1 可读性 + 同屏 3 块上限与 2 采样源预算 + 防循环采样铁律 + **4 级 120Hz 性能回退顺序** + 三段式工具条与快速回复面板视觉规范）、§6.3/§6.4（全量映射至既有 `HiMotion` 令牌：胶囊拖拽预选、荧光笔切显、顶栏上行折叠与标签行吸顶、三段工具条内联跳页展开、快速回复面板升降/展开全屏、同屏 3 块性能约束）、§7.1/§7.3/§7.4（三段工具条、原地内联跳页条附 `首页`/`末页`、毛玻璃快速回复面板 + 一键 `展开全屏` 无损携参、快速回复内表情等高互换，附 TalkBack 与减少动态效果规定）、§10（补充专项截图矩阵、真机 120Hz 三栏 3 块帧率与回退验证、R8 release 冒烟、对比度与无障碍自动化验收）、§11（明确禁止搬用 kudo `#FFB003`/图标资产与非 HiPDA 功能）与附录 A（S28）。 |
| [copy.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md) | v0.3 → **v0.4** | 新增/更新帖子详情透明顶栏圆形动作描述（`返回`/`全屏阅读`/`显示列表`/`只看楼主`/`看全部`）、底部三段工具条动作（`回复`/`收藏`/`取消收藏`/`更多`）、帖子“更多”菜单落点、毛玻璃快速回复面板文案（`收起回复面板`/`展开全屏`/`回复 {用户名}`/`引用第 {N} 楼 · {用户名}`/`插入图片`/`表情`/`键盘`/`引用`/`粗体`/`发送`）、中段进度胶囊内容描述（`第 {N} 页，共 {M} 页，点按展开跳页`）与内联跳页条文案（`页码`、`/ {M}`、`首页`/`末页`、`关闭`/`跳转`、`第 {目标} / {总页} 页`、`已展开跳页，当前第 {N} 页，共 {M} 页`）。已全量校验零禁用术语、零感叹号。 |
| [tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md) | v0.3 → **v0.4** | §1 目标同步液态玻璃覆盖范围与 3 块上限；§4 毛玻璃行将 Haze 2.x 替换为 Kyant `io.github.kyant0:backdrop`（优先 `2.0.1` + `shapes:1.2.1`，遇 BOM/AGP 9 冲突退 `1.0.6` + `shapes:1.2.0`），写清统一门面、API 33+/31–32/≤30 + 4 路 `GlassEnvironment` 降级路径、严格兄弟节点防循环采样、`com.kyant.backdrop.**`/`com.kyant.shapes.**` R8 keep 规则、Apache-2.0 致谢与 CI `emulator=true` release 冒烟；§5 授权 `C3b` 独占修改共享依赖与混淆文件；§8 更新 M4/M5/M7 验收与 T9/T10 规则；§9 新增第 5 条风险。 |
| [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md) | v0.3 → **v0.4** | 新增玻璃替换卡 **`C3b`** 与外壳微调卡 **`C4d`**；更新 **`E1`、`E2a`、`E3`、`E4b`、`E5`、`E6`、`E7`** 以及 `M7-4`/`M7-5` 的范围与依赖；同步共享文件修改授权、并行安排与用户真机 120Hz 验收项。 |

---

## 二、用户拍板与采纳建议落位映射

### A. 2026-11-25 四条用户拍板

| 拍板 | 定稿内容 | 落位文件与章节 |
|---|---|---|
| **决定 1** | 毛玻璃引擎改用 Kyant `io.github.kyant0:backdrop`（API 33+ 边缘折射/透镜，31–32 退纯模糊，≤30 与 4 路系统降级退 94% 纯色），替换 Haze | `product-design.md` 版本头、[§5 液态玻璃](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L232-L258)、[§10.3](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L539-L544)；`tech-plan.md` [§4 毛玻璃](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L42)、[§5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L53)、[§8](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L86-L95)、[§9 风险 5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L100)；`backlog.md` [共享文件规则](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L16)、[`C3b`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L53)、[`M7-4`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L94) |
| **决定 2** | 同屏活跃实时玻璃上限放宽至 **3 块**（三栏：中栏顶栏 + 右栏顶栏 + 右栏底部三段工具条；铰链双栏左列表顶栏保持纯色以守 ≤3 块；回复面板打开暂停底层工具条绘制）；真机 **120Hz** 帧率实测，不达标按 **4 级预定回退顺序**降级（①关折射留模糊 → ②三栏中栏顶栏退纯色缩为 2 块 → ③全顶栏退纯色仅留核心底浮层 → ④降采样或退 94% 纯色） | `product-design.md` [§4.1 铰链行](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L82)、[§4.2](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L147-L157)、[§5 适用部位与 4 级回退顺序](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L233-L254)、[§6.4](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L332-L335)、[§10.1 性能](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L525)、[§10.3](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L541-L544)；`tech-plan.md` [§1](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L6)、[§4](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L42)、[§8](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L86-L93)、[§9 风险 5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L100)；`backlog.md` [`C3b`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L53)、[`E3`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L76)、[`M7-5`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L95) |
| **决定 3** | 窄屏/矮屏/铰链底部胶囊固定 4 项，**不加中间“+”**，发新帖留在论坛顶栏 | `product-design.md` [§3.1](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L26-L31)、[§4.1](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L81-L83)、[§5 导航胶囊](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L259-L260)、[§11](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L551)；`backlog.md` [`C4d`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L57)、[`E1`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L72) |
| **决定 4** | 帖子详情底部三段工具条中段进度胶囊点按**原地展开为滑块 + 页码输入**，另附 **`首页` / `末页`** 快捷按钮（不弹 Sheet 遮正文） | `product-design.md` [§4.1 窄栏适配](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L88)、[§5 底部三段工具条](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L262-L265)、[§6.3](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L315)、[§7.1](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L342-L360)、[§7.3](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L372-L378)、[§10.1/§10.4](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L517-L547)、[附录 A S28](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L570)；`copy.md` [读帖、跳页与位置](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L119-L122)；`backlog.md` [`E4b`](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L78) |

### B. 调研报告 `G-kudo.md` 5 条建议落位

1. **建议 1（透明吸顶版块标签行 + 荧光笔高光）**：`product-design.md` §3.1、§4.1、§4.2、§5 顶栏、§6.3 选择指示器/栏随滚动、§10.1/§10.4；`backlog.md` `E1`。
2. **建议 2（底部三段式工具条 + 主题色进度填充 + 原地内联跳页）**：`product-design.md` §4.1、§5 底部三段工具条、§6.3、§7.3、§10.1/§10.4；`copy.md` L32/L36/L119/L122；`backlog.md` `E4b`。
3. **建议 3（帖子详情透明极简顶栏 + 独立圆形动作按钮）**：`product-design.md` §4.1、§4.2、§5 顶栏、§7.3、§10.1；`copy.md` L31；`backlog.md` `E2a`、`E3`。
4. **建议 4（毛玻璃跟手快速回复面板 + 一键展开全屏）**：`product-design.md` §4.2、§5 毛玻璃快速回复面板、§6.3 出现和消失/键盘联动/拖拽、§7.3 回复、§7.4、§10.1/§10.3/§10.4；`copy.md` L37；`backlog.md` `E5`、`E6`、`E7`。
5. **建议 5（底部胶囊跟手拖拽预选，保留 4 项无 `+`）**：`product-design.md` §3.1、§4.1、§5 导航胶囊、§6.3 选择指示器、§10.1/§10.4；`backlog.md` `C4d`。

---

## 三、被替换的旧规定清单

| # | 旧规定（v0.3 及之前） | 新定稿（v0.4） | 原位置 |
|---|---|---|---|
| 1 | 毛玻璃底层库为 **Haze 2.x**（`haze` / `haze-blur` / `haze-blur-materials`，`Modifier.hazeSource` / `hazeBlur`）；报告 `G-kudo.md` §3.1 方案 B 推荐保留 Haze | 改为 **Kyant `io.github.kyant0:backdrop`**（配套 `io.github.kyant0:shapes`），API 33+ 液态边缘折射、API 31–32 纯模糊、API ≤ 30 及 4 路系统降级退 94% 纯色；移除 Haze 依赖并补 R8 keep + release 冒烟 | `tech-plan.md` §4、§8；`backlog.md` `C3` → 新增 `C3b` |
| 2 | **“顶栏：纯色，不用毛玻璃”**（单栏、中栏、右栏顶栏全部纯色 `surface → surfaceContainer`；§10.3 断言“顶栏都是纯色”） | 删除该限制：论坛顶栏/吸顶版块标签行、大屏中栏顶栏、帖子详情顶栏采用 `TopBar` 透明液态玻璃；仅左侧栏（`surfaceContainerLow`）、铰链双栏左列表顶栏与表单/WebView 页保持纯色 | `product-design.md` §5 顶栏、§10.3 |
| 3 | **同屏毛玻璃上限最多 2 块**（宽屏侧栏态最多 1 块）；旧参数起点写死为 `24dp` 模糊、`70%/60%` 着色、均匀 `0.5dp` 白边 | 同屏活跃实时玻璃区域上限放宽至 **3 块**（独立 `layerBackdrop` ≤ 2 个；三栏可同时开中栏顶栏 + 右栏顶栏 + 右栏底工具条），并定四档预设起点与 **120Hz 不达标 4 级预定回退顺序** | `product-design.md` §5、§6.4、§10.1、§10.3；`tech-plan.md` §4、§8 |
| 4 | 论坛版块标签行**随顶栏一起向下滚动完全收起**，选中指示器为横向滑动条 | 向下滚动仅折叠上行标题与动作行，**版块标签行吸顶不隐藏**；采用 `BalancedTabRow` 自适应 + 主题色横向渐变**荧光笔底条**（`effectsFast` 切显，`48dp` 触控区） | `product-design.md` §3.1、§4.1、§5、§6.3；`backlog.md` `E1` |
| 5 | 底部导航胶囊仅支持点击切页 | 保留 4 项（不加中间 `+`），新增**横向跟手拖拽预选**（拖动 `snapTo` 实时高亮 `previewIndex`、松手 `spatialFast` 吸附切页、拖离 `spatialSnap` 弹回） | `product-design.md` §3.1、§5、§6.3；`backlog.md` 新增 `C4d` |
| 6 | 帖子详情底部为**单条**毛玻璃工具条（`回复` + `第 N / M 页` + `更多`），点页码从底部升起**独立跳页面板**（Sheet，含 `首页`/`-1`/`+1`/`末页`/`跳转`） | 升级为**三段式液态玻璃工具条**（左 `回复`、中带主题色进度填充的进度胶囊、右 `收藏` + `更多`）；点中段**原地展开内联跳页条**（滑块 + 页码输入 + `首页` / `末页` 快捷按钮 + `关闭`/`跳转`，不再另弹 Sheet，移除冗余 `-1`/`+1`） | `product-design.md` §4.1、§5、§6.3、§7.1、§7.3、§10.4、附录 A；`copy.md` L32/L36/L119/L122；`backlog.md` `E4b` |
| 7 | 快速回复为**纯底部输入条**，无毛玻璃背景、拖动把手、内嵌快捷工具行与一键展开全屏入口 | 升级为**毛玻璃跟手弹出快速回复面板**（`20dp` 顶部圆角、`32dp` 把手下拉保存草稿、快捷工具行、右上角 `48dp` `展开全屏` 无损进完整编辑器；三栏/铰链仅在右栏底部升起且覆盖工具条时暂停工具条实时绘制） | `product-design.md` §4.2、§5、§6.3、§7.3、§7.4、§10.1/§10.4；`copy.md` L37；`backlog.md` `E5`、`E6`、`E7` |

---

## 四、遗留需用户确认项（不阻塞开卡，可在 C3b / M5 真机校准时拍板）

1. **`C3b` 落地时的 `backdrop` 版本与真机四档参数手感**：已按 Maven Central 实查默认首选 `2.0.1`（兼容失败退 `1.0.6`），待 `C3b` 出包后在 Galaxy Z Fold8 真机通过 debug 调参面板确认 `Navigation` / `TopBar` / `Toolbar` / `Sheet` 四档模糊半径、着色透明度与边缘 `lens()` 折射强度，校准后回写 `product-design.md` §5。
2. **内联跳页条精简 `-1 / +1` 按钮**：按定稿仅保留 `滑块 + 页码输入 + 首页/末页 + 关闭/跳转`（相邻页由连续加载覆盖）；如用户仍习惯一键翻上/下一页，可在 `E4b` 评审前决定是否加回。
3. **三栏 3 块实时液态玻璃 120Hz 实测档位**：待 `E3` / `E4b` 合入后在 Fold8 真机按 120Hz 实测帧率，如不达标将按 §5 预定 4 级顺序降级并报用户确认最终落盘档位。

---

## 五、修订轮 1（对照 `R-Dkudo` @ `102b1c3` 全量闭合）

- **本轮 Commit**：`6f139c46933ab8dc7031be91657b513fea6528fc`（`6f139c4`，`origin/design/kudo-glass`，`docs-only`，4 文件 `+116 / -90`）

### 5.1 三个阻断项闭合明细

1. **B1 — 可断言的“玻璃区域”语义计数 + 全宽度/全页面严格兄弟采样拓扑**
   - **可断言计数语义**：在 `product-design.md` §5（L240–246）、§6.4、§10.3 与 `tech-plan.md` §4 中将“同屏 ≤ 3 块”明确定义为**按独立“玻璃区域（`HiGlassRegion`）”计数的 `HiGlassMarker` 根节点数 ≤ 3**：
     - 透明顶栏（论坛/列表/详情）= 1 区域（1 个 `HiGlassMarker`）；**详情顶栏内的 `40dp` 圆形动作按钮严禁嵌套调用 `HiGlassSurface` 或 `drawBackdrop`**，仅用位于 `TopBar` 玻璃层内部的轻半透明纯色底（浅 `surfaceContainerHigh @ 55%–65%` / 深 `60%–70%` + `0.5dp` 细边）；
     - 底部导航胶囊（含 `C4d` API 33+ 拖动镜片）= 1 区域（1 个 `HiGlassMarker`）；
     - 底部三段式工具条（及展开后的内联跳页条）= 1 区域（外层容器记 1 个 `HiGlassMarker`，3 胶囊共享同一详情 `LayerBackdrop` 绘制，子胶囊不单独挂 `HiGlassMarker`）；
     - 毛玻璃快速回复面板 = 1 区域（1 个 `HiGlassMarker`；展开覆盖底部三段工具条时**停绘并卸载底部工具条的 `drawBackdrop` 与 `HiGlassMarker`**，互斥活跃）；
     - 同步明确物理预算：独立 `LayerBackdrop` 源 `≤ 2` 个，静止物理 `drawBackdrop` pass `≤ 5` 次（三栏 `1 + 1 + 3 = 5`；单栏详情 `1 + 3 = 4`；单栏一级页 `1 + 1 = 2`），第 1 级 120Hz 回退优先关顶栏与三段左右小胶囊 `lens()`。
   - **严格兄弟采样拓扑（参照现网 `HiAdaptiveShell.kt:253`）**：在 `product-design.md` §5（L247–258）补全 6 档窗口/页面拓扑表，明确 `C4d` 改造 `HiAdaptiveShell` 不再将 `.hiGlassSource(shellGlassState)` 粗暴包在包含透明顶栏的外层 `ContentContainer` 上，而是通过 `ShellContentScope` 下放 `shellGlassState` 仅挂在纯滚动内容体 `LazyColumn` 上（或外壳提供同级 `topOverlay` 槽位承载透明顶栏），使透明顶栏与外壳 `HiNavigationPill` 同为列表采样源的严格兄弟，彻底根除循环采样 `libhwui.so SIGSEGV` 与父子双录制。

2. **B2 — 对比度拆分为三层可验收契约 + 叠加层峰值 alpha 硬上限 + 着色范围调整**
   - 在 `product-design.md` §5（L264–276）、§10.1、§10.3 与 `tech-plan.md` §4/§8 拆为三层：
     - **(a) CI 确定性单测（94% 纯色降级态 × 极端 `#000000` / `#FFFFFF`）**：全 20 套主题预设 × 浅/深/纯黑，全部前景角色（`onSurface` / `onSurfaceVariant` / `primary`）`≥ 4.5:1`（对齐 `G2`）；
     - **(b) CI 确定性单测（模糊激活态 × 标准主题背景）+ 叠加层峰值 alpha 硬上限**：全 20 预设在标准背景（`background` / `surface` / `surfaceContainerLow` / `surfaceContainer`）下四档激活态及叠加层全部 `≥ 4.5:1`；经 WCAG 2.1 精确验算锁定硬上限——版块荧光笔底条：`primaryContainer` 浅 `≤ 0.55` / 深 `≤ 0.45`（或 `primary` 浅 `≤ 0.22` / 深 `≤ 0.24`）；阅读进度填充条：`primaryContainer` 浅 `≤ 0.50` / 深 `≤ 0.45`（或 `primary` 浅 `≤ 0.22` / 深 `≤ 0.24`）；底部胶囊指示器：`primary @ ≤ 0.12`；
     - **(c) 真机极端样张人工验收（纯黑 `#000000`、纯白 `#FFFFFF`、高饱和配图）**：不再错误要求 Robolectric 回读硬件模糊像素；同时上调护读档着色范围（`TopBar` 浅 `0.64–0.72` / 深 `0.68–0.76`、`Toolbar` 浅 `0.62–0.72` / 深 `0.68–0.76`、`Sheet` 浅 `0.72–0.80` / 深 `0.70–0.78`，`Navigation` `0.42–0.50` / `0.44–0.52`），使极端黑白背景具备可达标余量。

3. **B3 — 窄宽 `< 480dp` 统一双行、矮屏 IME 回复限高、`E5` 插入图片流转、跳页条不滚底层列表**
   - **内联跳页条 `< 480dp` 统一双行**：`product-design.md` §4.1、§5、§7.3、§10.1/§10.2/§10.4 与 `backlog.md` `E4b` 统一按详情容器可用宽判定（`< 480dp` 统一双行，自动覆盖 `340dp` 分屏、`411dp` 手机单栏与 `360–465dp` 三栏右栏，滑块留足 ≥ `140dp`；三段紧凑态同理按自然总宽 `> 详情容器宽 − 32dp` 通用触发）；
   - **矮屏弹键盘回复面板限高与原楼可见策略**：动态钳制 `min(300dp, 可视净高 × 60%)`（输入框超出后内部纵向滚动、保底 1–2 行）；键盘上方净高 `< 180dp`（如矮屏 `< 480dp` 横屏）自动隐藏把手与草稿横条压成紧凑行，`< 120dp` 或全屏 IME 可展开/退入完整编辑器；“正在读的那一楼保持可见”明确限定于常规非矮屏窗口（高 `≥ 480dp` 且净高 `≥ 180dp`）；
   - **`E5` 的 `插入图片` 归属**：明确 `E5` 面板不重复内嵌多图上传缩略图条，点 `插入图片` 自动携带当前文本、光标与引用直接展开进入 `E6` 完整编辑器并立即拉起选图（落入 `E6` 多图上传条）；
   - **内联跳页条聚焦不滚底层列表**：`product-design.md` §6.3、§7.3、§10.1、§10.4 与 `backlog.md` `E4b` 明确聚焦页码输入框仅 `imePadding()` 上移跳页条自身，底层 `LazyColumn` 滚动偏移变化严格为 `0dp`。

### 5.2 六项建议同步闭合明细

- **S1（`C3b → G2` 统一与 `TopBar`/`Sheet` 归属）**：全量替换 `C3b` 为 `G2`；在 `product-design.md` §5、`tech-plan.md` §4 与 `backlog.md` 明确 `G2` 负责 `Navigation`/`Toolbar` 与引擎迁移、`C4d` 补齐 `TopBar`/`Sheet` 预设、复合区域单 `HiGlassMarker` 支持及 `HiAdaptiveShell` 无环兄弟拓扑，并更新 `E1`/`E2a`/`E3`/`E4b`/`E5` 依赖指向 `C4d`。
- **S2（快速回复垂直拖拽隔离）**：明确仅由顶部 `32dp` 把手/标题区直接响应，或输入框滚至顶边界且无文本选区态时经 `NestedScroll` 超界传递；输入框内优先多行滚动与选区手柄拖拽。
- **S3（底部胶囊拖拽容差与防系统返回）**：明确超 `horizontalTouchSlop` 触发、竖向离胶囊上下边界 `> 48dp` 弹回取消、`< 452dp` 窄屏通过胶囊高度区局部 `systemGestureExclusion` 或避开左缘 `16dp` 防与预测性返回抢手势。
- **S4（其余页面顶栏材质归属）**：在 `product-design.md` §4.2 与 §5 逐页写定——纯滚动列表页（`论坛`、`帖子详情`、`消息[短消息列表/提醒列表]`、`浏览历史`、`我的帖子/回复`、`收藏与关注`）用 `TopBar` 透明液态玻璃；`搜索`、`我的` 总页、`用户资料`、`短消息对话`、`设置`、登录/账号/管理版块/完整编辑器/Cloudflare WebView、左侧栏顶部与铰链双栏左列表顶栏用纯色。
- **S5（`copy.md` 帖子“更多”菜单去重）**：移除已外置的 `收藏`/`取消收藏` 与 `只看楼主`/`看全部`，精简为 `刷新` / `关注` / `取消关注` / `复制链接` / `在浏览器中打开` / `分享`。
- **S6（删除误搬的 `引用`/`粗体` 按钮）**：同步移除 `product-design.md`、`copy.md` 与 `backlog.md` 中源自 kudo 的 `引用`/`粗体`，快速回复面板仅保留旧版既有的 `插入图片` + `表情` 两个图标按钮，并在 §11 写入禁用项。

---

### 5.3 给 `G2` 实现卡的计数语义对齐说明

- **核心语义（可直接承接 `G2` 现行 `assertGlassSurfaceCountAtMost(3)`）**：
  > **“同屏 ≤ 3 块”按“独立玻璃区域（`HiGlassMarker` 根节点）”计数**——全屏语义树上 `HiGlassMarker == true` 节点上限为 `3`（即 `G2` 当前的 `HiGlassTokens.MaxGlassSurfacesOnScreen = 3` 与 `assertGlassSurfaceCountAtMost(3)` 无需改语义、直接生效）。
- **`G2` 无需返工项与与 `C4d` 的衔接**：
  1. `G2` 当前只须继续保持 `HiGlassTokens.MaxGlassSurfacesOnScreen = 3`、`HiGlassMarker` / `HiGlassBlurActive` 与 `assertGlassSurfaceCountAtMost(3)`，以及 `Navigation` / `Toolbar` 两档和 94% 降级态极端黑白 `≥ 4.5:1` 单测即可，**不必改动 `ui/shell/`，也不必在本卡新增 `TopBar`/`Sheet`**；
  2. 后续由 **`C4d`** 在 `HiGlassSurface` 上平滑增加可选区域标记参数（如 `countsAsGlassRegion: Boolean = true`，或外层 `HiGlassRegion` 容器标 1 个 `HiGlassMarker`、内部三段子胶囊传 `countsAsGlassRegion = false`），从而让：
     - 详情透明顶栏 = 1 个 `HiGlassMarker`（内部圆钮为轻底纯色按钮、非 `HiGlassSurface`）；
     - 底部三段式工具条 = 外层 1 个 `HiGlassMarker`（3 个共享同一 `LayerBackdrop` 的胶囊子项 `countsAsGlassRegion = false`）；
     - 毛玻璃快速回复面板 = 1 个 `HiGlassMarker`（展开时底部工具条退出组合，释放 `drawBackdrop` 与 `HiGlassMarker`）；
     - 底部导航胶囊（含拖动镜片）= 1 个 `HiGlassMarker`。
  3. 由此全屏所有形态（单栏 ≤ 2、图标轨单栏 ≤ 2、三栏 ≤ 3、铰链双栏 ≤ 3）均可由 `G2` 交付的 `assertGlassSurfaceCountAtMost(3)` 一致守住，同时 CI 单测/审查另守 `LayerBackdrop` 采样源 `≤ 2` 与静止 `drawBackdrop` pass `≤ 5`。
