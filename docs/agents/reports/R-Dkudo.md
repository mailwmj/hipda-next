# R-Dkudo 设计审查报告：`origin/design/kudo-glass` @ `102b1c3`

- **审查结论**：**`CHANGES-REQUESTED 102b1c3`**
- **审查对象**：`origin/design/kudo-glass` @ `102b1c3`（对比基准 `origin/main` @ `0895b83`，涉及 `docs/design/{product-design,copy,tech-plan,backlog}.md` 共 4 个文件，`+135 / -85`）
- **对照依据**：
  - 调研报告：[G-kudo.md](file:///home/mir/Documents/hipda-next.wt/_reports/G-kudo.md)
  - 修订摘要：[D-kudo.md](file:///home/mir/Documents/hipda-next.wt/_reports/D-kudo.md)
  - 已派发玻璃替换卡：[G2.md](file:///home/mir/Documents/hipda-next.wt/_cards/G2.md)
  - 已合并 `main` 外壳与玻璃代码：[HiGlassSurface.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt)、[HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt)、[HiGlassTestSupport.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/glass/HiGlassTestSupport.kt)、[HiColorContrastCalibrationTest.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/theme/HiColorContrastCalibrationTest.kt)
  - 旧版功能边界对照：[fragment_post.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/layout/fragment_post.xml)、[menu_reply.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/menu/menu_reply.xml)、[PostFragment.java](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/PostFragment.java)

---

## 一、总体评价与用户已拍板事项核对

用户已拍板的 4 项核心决策在 `102b1c3` 中均已准确落地（本审查不推翻任何已拍板方向）：
1. **底层引擎换为 Kyant `io.github.kyant0:backdrop`（Apache-2.0）**：已写入 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L232)、[tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L42) 与 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L53)，并保留了 R8 `-keep` 规则与 CI `emulator=true` release 冒烟要求。
2. **同屏实时玻璃上限放宽至 3 块（须经 120Hz 真机实测 + 4 级预定回退）**：已写入 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L233-L255) 与 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L525)。
3. **窄屏底部胶囊保持 4 项、不加中间 `"+"` 按钮**：已写入 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L80-L83)、[product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L259-L260) 与 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L551)，`发新帖` 继续留在论坛顶栏。
4. **详情阅读进度胶囊原地展开内联跳页条 + `首页` / `末页` 快捷按钮**：已写入 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L264) 与 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L374-L377)。

但结合已合并的 `C4c` / `G1` 源码、WCAG 2.1 相对亮度公式与各窗口尺寸几何演算，发现 **3 项阻断性自相矛盾（B1–B3）**，若不先在设计稿中闭合，后续 `C4d` / `E1` / `E2a` / `E3` / `E4b` / `E5` 实现时将必然遇到 **RenderThread 原生崩溃（`SIGSEGV`）、`assertGlassSurfaceCountAtMost(3)` 断言必败、对比度单测数学无解或窄窗控件溢出重叠**。

---

## 二、阻断项（Blocking Issues）

### B1. 【§5 L233–239 / L250 / L262 与 §10.3 L541 vs 已合入 `HiAdaptiveShell.kt` / `HiGlassSurface.kt`（及 `G2`）】“3 块上限”节点/渲染计数矛盾 + 单栏“1 采样源”循环采样崩溃冲突

#### 矛盾点 1a：“按 1 个逻辑区域计数”与真实 GPU 模糊 pass 及语义计数断言冲突
- **设计稿表述**：[product-design.md §5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L233) 规定“任何布局下全屏活跃实时玻璃区域（slot）同时最多 3 块……同一栏内的三段工具条或顶栏圆形动作按钮共享同一个兄弟 `layerBackdrop`，按 1 个逻辑区域计数”，[§5 L262](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L262) 定义底部工具条为“**三段独立液态玻璃胶囊**（段间 `8dp`）”，[§5 L229](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L229) 定义详情透明液态玻璃顶栏内含左右独立 `40dp` 圆形动作按钮；而 [§10.3 L541](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L541) 要求以**“语义计数断言”**验证 `≤ 3`。
- **事实冲突**：
  1. **GPU 渲染负载**：在 `io.github.kyant0:backdrop` 中，`LayerBackdrop` 仅共享源图层的 1 次离屏录制，**每一个调用 `drawBackdrop`（即 `HiGlassSurface`）的独立胶囊都会创建自己的离屏 `GraphicsLayer` 独立执行 `RenderEffect.createBlurEffect` + AGSL `lens()` 着色器**。因此三栏下“中栏顶栏（1）+ 右栏顶栏（1）+ 右栏三段独立胶囊（3）”每帧实际运行 **5 次实时模糊/折射 pass**；若详情顶栏左右 3 个圆形动作按钮也各自调用 `HiGlassSurface`，更会飙升至 **7–8 次 pass**（且圆钮位于玻璃顶栏内部，还会造成二次叠加模糊）。此外若 `C4d` 底部胶囊跟手拖拽镜片在 API 33+ 也用独立 `drawBackdrop` 做边缘折射（[§5 L259](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L259)），单栏拖拽期间也会多出 1 个 `drawBackdrop` 节点。
  2. **语义断言必败**：现网 [HiGlassSurface.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt#L344) 与 [HiGlassTestSupport.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/glass/HiGlassTestSupport.kt#L20-L31)（以及已派发 [G2.md](file:///home/mir/Documents/hipda-next.wt/_cards/G2.md#L54) 的硬约束）通过逐个统计 `HiGlassMarker == true` 节点执行 `assertGlassSurfaceCountAtMost(3)`。单栏详情 `1（顶栏）+ 3（三段胶囊）= 4 > 3`、三栏 `1 + 1 + 3 = 5 > 3`，在 `E2a` / `E3` / `E4b` 联调时必然触发 `AssertionError`。
- **修复建议**：
  1. 明确**详情透明顶栏内的 `40dp` 圆形动作按钮**位于 `TopBar` 玻璃层内部，只使用轻量半透明纯色底（如 `surfaceContainerHigh @ 55%–65%`），**严禁嵌套调用 `HiGlassSurface`**；
  2. 对底部三段独立胶囊与胶囊拖拽镜片，明确区分**逻辑槽位（`HiGlassSlot` ≤ 3，按 `slotId` 去重断言）**与**物理 `drawBackdrop` 节点上限（如 ≤ 5，单栏详情 ≤ 4、三栏 ≤ 5）**，并在 `C4d`（或 `E4b`）同步扩展语义断言辅助函数，同时将第 5 节 120Hz 回退第 1 级细化包含“若三段独立 `drawBackdrop` 超预算，合并或退化左右小胶囊 `lens()`”。

#### 矛盾点 1b：单栏一级页“2 块玻璃共享 1 个采样源”与已合并 `HiAdaptiveShell.kt` 结构触发循环采样崩溃
- **设计稿表述**：[product-design.md §5 L234](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L234) 写明“单栏一级页：论坛吸顶顶栏（1）+ 底部导航胶囊（1）= **2 块（1 采样源）**”；[§5 L250](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L250) 铁律要求“`layerBackdrop` 采样源与所有消费它的毛玻璃表面必须保持**严格兄弟节点**关系，严禁毛玻璃表面自身或其祖先落入该采样源（防止 RenderThread `libhwui.so` 崩溃）”。
- **事实冲突**：已合入 `main` 的 [HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L253) 将整个 `ContentContainer` 包裹在 `.hiGlassSource(glassState)` 中供底部 `HiNavigationPill` 采样，而 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L57) 中 `C4d` 仅改底部胶囊拖拽、`E1` 仅能改 `feature/forum/`：
  - 若 `E1` 复用外壳的唯一采样源（满足 L234“1 采样源”），则位于 `ContentContainer` 内部的论坛透明液态玻璃顶栏直接落入自身采样的 `layerBackdrop` 子树中，**在 API 31+ 真机必现循环采样 `SIGSEGV` 原生崩溃**；
  - 若 `E1` 在 `feature/forum/` 内部新建第二个 `HiGlassState` 仅包裹帖子 `LazyColumn`，则单栏实际变成 **2 个父子嵌套的 `layerBackdrop`**（外层 `shellBackdrop` 每帧重复录制内层玻璃顶栏，且与 L234“1 采样源”矛盾）。
- **修复建议**：在 [product-design.md §5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L234) 与 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L57)（建议并入 `C4d` 外壳微调）明确单栏无环兄弟拓扑：由 `HiAdaptiveShell` 暴露与 `ContentContainer` 同级的 `topOverlay` 槽位（或将 `shellGlassState` 通过作用域下放给页面，仅挂在纯滚动列表体 `LazyColumn` 上，使顶栏与底部胶囊同为列表 `layerBackdrop` 的外部兄弟节点），真正实现“1 个采样源、0 循环采样、0 嵌套录制”。

---

### B2. 【§5 L227 / L245–249 vs §10.1 L528 / §10.3 L540】模糊激活态在极端黑白背景及主题色荧光笔下“对比度 ≥ 4.5:1”数学无解且 Robolectric 不可测

1. **WCAG 2.1 相对亮度精确验算（数学不可达）**  
   对照现网 [HiColors.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/theme/HiColors.kt) 令牌亮度（浅色：`surfaceContainerLowest = #FFFFFF` $Y=1.0$、`onSurface = n(10)` $Y \approx 0.023$、`onSurfaceVariant = nv(30)` $Y \approx 0.0624$、`primary = p(40)` $Y \approx 0.1125$；深色：`surfaceContainer = n(12)` $Y \approx 0.030$、`onSurface = n(90)` $Y \approx 0.761$、`primary = p(80)` $Y \approx 0.567$），代入 [§5 L245–248](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L245-L248) 给定的着色不透明度区间，当帖子内大面积深黑配图（`#000000`，高斯模糊在色块内部输出仍为 `#000000`）或纯白截图（`#FFFFFF`）滑过玻璃背后时：

   | 场景 | 着色参数（§5 L245–248） | 极端背景 | 玻璃合成表面亮度 $Y_{\text{bg}}$ | 前景角色与亮度 $Y_{\text{fg}}$ | 实际对比度 | 是否达 `≥ 4.5:1` |
   |---|---|---|---|---|---|---|
   | 浅色 `Navigation` 胶囊 | 白 `@ 0.45` | 纯黑图 `#000000` | `0.1704` | 未选中 `onSurfaceVariant` (`0.0624`) | **`1.96 : 1`** | 否（连纯黑字也仅 `4.41:1`） |
   | 浅色 `Navigation` 胶囊 | 白 `@ 0.45` + 指示器 `p(40) @ 0.12` | 纯黑图 `#000000` | `0.1570` | 选中文字 `primary` (`0.1125`) | **`1.27 : 1`** | 否 |
   | 浅色 `TopBar` 版块标签 | 白 `@ 0.60` | 纯黑图 `#000000` | `0.3185` | 未选中标签 `onSurfaceVariant` (`0.0624`) | **`3.28 : 1`** | 否（需着色 `≥ 0.71` 才达 `4.5:1`） |
   | 深色 `TopBar` / `Toolbar` | `n(12) @ 0.65` | 纯白图 `#FFFFFF` | `0.1486` | 标题/页码 `onSurface` (`0.761`) | **`4.08 : 1`** | 否（需着色 `≥ 0.69` 才达 `4.5:1`） |
   | 深色 `Toolbar` 进度胶囊 | `n(12) @ 0.65` + 进度 `p(80) @ 0.26` | 纯白图 `#FFFFFF` | `0.244` | 页码 `onSurface` (`0.761`) | **`2.77 : 1`** | 否 |

   此外，[§5 L227](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L227) 规定版块选中荧光笔底条采用“`primary` 或 `primaryContainer` 的半透明横向渐变”却**未限定峰值不透明度**：kudo 用 `#FFB003 @ 0.95` 能看清黑字是因为亮黄 $Y \approx 0.68$；而 HiPDA M3 浅色 `primary = p(40)` 是深暗色（$Y \approx 0.1125$），若峰值 alpha 取 `0.50–0.95`，与 `onSurface` 对比度会跌至 **`1.35:1–2.86:1`**；深色 `primary = p(80)`（$Y \approx 0.567$）与浅字 `onSurface`（$Y \approx 0.761$）高 alpha 叠加同样会跌破 `2:1`。

2. **测试手段不可行且与已派发 `G2` 脱节**  
   - [§10.1 L528](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L528) 指定用“**Robolectric Compose 无障碍检查**”验证模糊激活态对比度，但 Robolectric 的 ATF 检查器无法在 JVM 上执行硬件 `RenderEffect` 高斯模糊与 AGSL `RuntimeShader`（`lens()`）像素回读；
   - 若改用静态 alpha 合成单测（如 [HiColorContrastCalibrationTest.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/theme/HiColorContrastCalibrationTest.kt)）对模糊态跑极端 `#000000` / `#FFFFFF` 背景，除非把所有玻璃着色拉到 `≥ 0.72–0.85`（彻底变成毛水泥失去通透感），否则单测必然 100% 失败。事实上，已派发的 [G2.md](file:///home/mir/Documents/hipda-next.wt/_cards/G2.md#L95) 只对 **94% 降级态**跑极端 `#FFFFFF/#000000` 背景 `≥ 4.5:1` 单测，与 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L53) 中 `C3b` 的验收要求已经不一致。

3. **修复建议**  
   将 [§5 L227 / L249](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L249) 与 [§10.1 L528](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L528)、[§10.3 L540](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L540) 修订为可量化、可测试的双层验收契约：
   - **CI 确定性计算单测**：(a) **94% 纯色降级态**在极端 `#000000` / `#FFFFFF` 背景下全部前景对比度 `≥ 4.5:1`（对齐 `G2`）；(b) **模糊激活态**（含荧光笔渐变底条与进度填充条叠加）在**标准主题背景（`background` / `surface` / `surfaceContainerLow`）**下对比度 `≥ 4.5:1`；同时明确荧光笔底条与进度填充条的峰值不透明度上限（浅色用 `primaryContainer @ ≤ 0.55` 或 `primary @ ≤ 0.22`，深色用 `primaryContainer @ ≤ 0.45` 或 `primary @ ≤ 0.24`）；
   - **极端配图可读性工程保障与真机验收**：在 `TopBar` / `Toolbar` 的 `colorControls` 中限制采样亮度极值（防止纯黑/纯白直穿），或为玻璃前景文字提供极淡反向护读底衬/阴影，并在阶段验收（M4/M5）通过真机极端纯白/纯黑/高饱和配图滚动样张验证主观与采样点可读性。

---

### B3. 【§4.1 L88 / §6.3 L320 / §7.3 L375 & L383 / §10.1 L523】窄单栏内联跳页条溢出 + 矮窗/IME 下快速回复面板高度溢出与内联跳页条滚屏矛盾

1. **窄单栏（`340–411dp`）遗漏紧凑双行规则，导致内联跳页条挤爆重叠**  
   - [product-design.md §4.1 L88](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L88) 与 [§7.3 L375](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L375) 仅规定“**窄右栏（`360–384dp`）**下自动采用紧凑双行排布”，遗漏了**窄屏单栏**（`411dp` 手机竖屏扣左右 `16dp` 边距后可用宽仅 `379dp`；`340dp` 分屏窄窗可用宽仅 **`308dp`**，比三栏右栏 `360dp` 还窄 `52dp`）。
   - 代入单行控件宽度：`首页(52dp) + 末页(52dp) + 页码框(56dp) + "/999"(40dp) + 关闭/跳转(48dp) + 内边距与间距(40dp) = 288dp`（`1.25×` 字体缩放下约 `330dp`）。在 `340dp` 分屏窄窗下留给 `Slider` 的宽度仅 **`20dp`（大字号下 `-22dp` 直接溢出重叠，低于 `48dp` 触控目标）**；在 `411dp` 单栏大字号下 `Slider` 也仅剩 `49dp` 无法有效拖动。
   - **修复建议**：将紧凑三段态与内联跳页条紧凑双行的触发条件从“窄右栏”改为**按工具条容器可用宽度统一判定（单栏与多栏通用）**——三段工具条自然总宽 `> 可用宽 − 32dp` 时切紧凑三段态；**工具条外框可用宽 `< 480dp` 时内联跳页条一律采用紧凑双行排布**（从而自动覆盖 `340dp` 分屏、`411dp` 手机单栏与 `360–465dp` 三栏右栏）。

2. **矮窗/横屏弹起软键盘时快速回复面板高度溢出 + `插入图片` 承载缺失 + 内联跳页条滚屏副作用**  
   - **矮窗 IME 高度冲突**：手机横屏 `891 × 411dp`（或 Fold8 横屏 `749 × 559dp`）弹起系统软键盘（常见高 `220–260dp`）后，键盘与状态栏之间仅剩 **`130–165dp`**。而快速回复面板（`32dp` 把手与上下文行 + 草稿恢复条 `36dp` + 多行输入框 + 底部 `48dp` 快捷工具行 `≈ 200–250dp`）将直接超出可视高度，更不可能同时满足 [§6.3 L320](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L320) 与 [§10.1 L523](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L523) 的硬性验收“**正在读的那一楼仍然可见**”。建议规定：快速回复面板最大高度动态钳制为 `≤ 可视净高（扣除状态栏与 IME）× 60%`（输入框最低退至 1–2 行内部滚动），当键盘上方净高 `< 180dp`（如矮屏 `< 480dp` 横屏弹起非全屏键盘）时，自动隐藏把手与草稿横条或直接展开进入完整编辑器，并将 §10.1 L523“正在读的那一楼仍然可见”限定于常规竖屏/非矮屏窗口（高度 `≥ 480dp`）。
   - **`E5` 快速回复面板内点 `插入图片` 的状态归属未定义**：[§7.3 L383](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L383) 在 `E5` 快速回复面板放了 `插入图片` 按钮，但多图上传缩略图条定义在 [§7.4 L391](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L391) 且在 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L79-L80) 中排在后置卡 `E6`（`E5` 先于 `E6` 开发）。需明确：在快速回复面板点 `插入图片` 是直接携带文本/引用跳转到 `E6` 完整编辑器选图上传，还是在 `E5` 面板内也内嵌缩略图上传条（若后者须调整 `E5`/`E6` 依赖与高度预算）。
   - **内联跳页条聚焦输入框不应推动底层列表滚动**：[§6.3 L320](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L320) 与 [§10.1 L523](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L523) 将“内联跳页条聚焦输入框”与快速回复面板并列写入“正在读的那一楼随内容上移，保持可见”。但内联跳页条是悬浮跳页控件，若弹键盘时改动帖子 `LazyColumn` 的滚动偏移，用户点 `关闭 ×`（[§7.3 L376](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L376)“收起不跳”）后阅读位置反而被键盘推偏。应明确：**仅快速回复面板唤起时按需上移目标楼层；内联跳页条聚焦输入框仅通过 `imePadding()` 上移工具条自身，不滚动底层楼层列表**。

---

## 三、五个审查维度逐项核查明细

### 维度 (1)：与已定稿三栏规则（§4）的一致性
- **透明吸顶版块标签行在 `≥ 600dp` 侧栏态隐藏**：**已自洽**。[§4.1 L84–86](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L84-L86)、[§4.2 L147](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L147)、[§5 L228](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L228) 与 [backlog.md L72](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L72) 均一致规定：`600–679dp` 图标轨单栏、`680–839dp` 紧凑三栏与 `≥ 840dp` 完整三栏全部隐藏横向版块标签行（中栏顶栏仅显示版块名+动作并使用 `TopBar` 透明液态玻璃）；仅 `< 600dp`、矮屏 `< 480dp` 与 `isSeparating == true` 铰链双栏（无左侧栏时）保留吸顶版块标签行，且铰链双栏左列表顶栏保持纯色以守住全屏 3 块上限。
- **底部三段工具条限定在右栏内**：**多栏边界已自洽**（[§4.1 L88](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L88)、[§5 L262](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L262)、[§7.3 L373–374](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L373-L374) 均限定在右栏宽度内，不跨中右分隔线与铰链；窄单栏跳页条双行遗漏见 **B3**）。
- **毛玻璃快速回复面板仅在右栏升起**：**已自洽**（[§4.2 L156](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L156)、[§5 L266](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L266)、[§7.3 L382](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L382) 明确三栏与铰链双栏仅在右栏底部升起，背后 `scrim` 仅盖右栏，左栏与中栏不遮挡；展开覆盖三段工具条时暂停工具条实时玻璃绘制；点 `展开全屏` 按 [§4.2 L157](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L157) 右栏 `< 560dp` 居中模态 / `≥ 560dp` 右栏内展开）。
- **3 块玻璃分布与 `≤ 2` 采样源规则**：各档位逻辑槽位表（单栏 2、图标轨单栏 1–2、三栏 3、铰链双栏 3、全屏阅读隐藏左中栏后退为右栏 2 块 / 1 采样源）在宏观分区上清晰，但存在 **B1** 的节点计数与单栏 `HiAdaptiveShell` 采样拓扑冲突。

### 维度 (2)：模糊/折射上文字 `≥ 4.5:1` 的可验收性
- 见阻断项 **B2**。当前规定把静态纯色可测条件直接套到了半透明模糊玻璃 + 任意极端背景上，数学无解且 Robolectric 不可测，需按 **B2** 拆分为“CI 标准背景/降级态确定性计算单测 + 荧光笔/进度填充 alpha 硬上限 + 真机极端样张验收”。

### 维度 (3)：交互手势与键盘冲突
- **内联跳页条与快速回复面板的宽度/IME 冲突**：见阻断项 **B3**。
- **快速回复面板下拉关闭 / 上拉展开全屏 vs 多行输入滚动**：[§6.3 L330](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L330) 与 [§7.3 L383–384](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L383-L384) 支持把手下拉关闭、上拉越阈值进入完整编辑器，但未写明面板中部多行 `TextField` 超过限高出现内部纵向滚动或拖动文本选择水滴手柄时的手势优先级（见建议 **S2**）。
- **底部胶囊跟手拖拽 vs 系统手势 / 轻点 / 无障碍**：胶囊底部距手势条 `8dp + 底部手势区` 避开了底缘系统上滑；胶囊内无其他横向滚动容器，无内部横向冲突；但在 `< 452dp` 窄屏下胶囊左右距屏幕边缘 `16dp`，两端 `8dp` 落入 Android 默认 `24dp` 边缘预测性返回热区，且尚未给出竖向“拖离容差”具体 `dp` 值（见建议 **S3**）。

### 维度 (4)：旧规定清理与 §10 验收/截图矩阵同步
- **残留旧规定扫描**：对 `docs/design/*.md` 全量检索 `haze`、`纯色`、`2 块`、`最多 2`、`跳页面板`、`输入条`：
  - 旧“顶栏纯色不用毛玻璃”、“同屏最多 2 块毛玻璃”、“独立跳页 BottomSheet”、“贴底回复输入条”**已全部清理干净**；`Haze` 仅在 [tech-plan.md L42](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L42) 与 [backlog.md L52–53](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L52-L53) 作为已完成 `C3` 与替换历史说明保留，无残留现行要求。
  - [§10.1](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L517-L528)、[§10.3](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L540-L544)、[§10.4](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L547) 及 [附录 A](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L570-L572) 已同步更新专项截图与自动化测试清单。
- **待同步缺口**：`C3b` 与已派发卡 `G2` 的命名及预设范围差异（见建议 **S1**）；除论坛与详情外的其余列表页顶栏材质归属未点明（见建议 **S4**）；帖子 `更多` 菜单存在重复动作项（见建议 **S5**）。

### 维度 (5)：kudo 品牌资产与非 HiPDA 功能边界核查
- **视觉资产与社区功能隔离良好**：`#FFB003` 品牌黄、Font Awesome CC-BY 分类图标、kudo 应用图标，以及热门、排行榜、助推（Boost）、点赞、关注作者、窄屏底栏中间 `+` 均已在 [§5](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L227) 与 [§11](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L551) 明确禁用。
- **发现 1 处从 kudo 误搬入的非旧版编辑器功能**：[product-design.md §7.3 L383](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L383)、[copy.md L37](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L37) 与 [backlog.md L79](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L79) 在毛玻璃快速回复面板快捷工具行加入了 **`引用`、`粗体`**。实查旧版源码 [fragment_post.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/layout/fragment_post.xml#L109-L143)、[menu_reply.xml](file:///home/mir/Documents/hipda-next/hipda/src/main/res/menu/menu_reply.xml#L5-L21) 与 [PostFragment.java](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/PostFragment.java#L252)，旧版 HiPDA 编辑器**仅提供 `插入图片` 与 `表情` 两个工具，从未提供 BBCode `引用`/`粗体` 格式化按钮**（且 `E6` 完整编辑器也无此按钮、[§7.4 L391](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L391) 明确不裸露标签）。该两项源自 kudo 的 Discourse Markdown 回复条，违反 [tech-plan.md §1 L7](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L7)“功能范围与现有应用一致，不扩张”，应移除（见建议 **S6**）。

---

## 四、建议项（Non-blocking Suggestions）

1. **S1 — 统一玻璃替换卡号 `C3b → G2`，并对齐 `TopBar` / `Sheet` 预设补齐归属**：将 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L244)、[tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L3) 与 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L53) 中的 `C3b` 统一改为已派发的 `G2`；同时注意已派发 [G2.md](file:///home/mir/Documents/hipda-next.wt/_cards/G2.md#L55) 仅包含 `Navigation` 与 `Toolbar` 两档 `HiGlassStyle`（尚未含 v0.4 新增的 `TopBar` 与 `Sheet` 两档及对应对比度校验），须在 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L57) 明确由 `C4d`（或紧接的补齐小卡）扩展 `TopBar` / `Sheet` 档位与槽位语义，防止 `E1` / `E5` 找不到枚举值。
2. **S2 — 快速回复面板垂直拖拽与多行文本滚动的手势隔离**：在 [§6.3 L330](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L330) 与 [§7.3 L383–384](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L383-L384) 写明：下拉关闭与上拉展开全屏手势仅由顶部 `32dp` 把手及标题区直接响应（或输入框滚到顶边界且无文本选择态时经 `NestedScroll` 超界传递），输入框内部优先响应多行纵向滚动与光标/选区手柄拖拽。
3. **S3 — 底部胶囊跟手拖拽的阈值与边缘手势细节**：在 [§5 L259](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L259) / [§6.3 L314](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L314) 明确横向位移越过 `horizontalTouchSlop` 后才进入跟手拖拽（不拦截普通轻点与 TalkBack 双击激活），给出“拖离容差”量化值（如手指竖向离开胶囊上下边界 `> 48dp` 视为取消并弹回原项），并在 `< 452dp` 窄屏下说明左端 `16dp` 边距处通过水平斜率优先或局部 `64dp` 高 `systemGestureExclusion` 避免与系统左缘返回抢手势。
4. **S4 — 明确非论坛列表页的顶栏材质归属**：在 [§4.2 L148–155](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L148-L155) 与 [§5 L228–230](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L228-L230) 明确 `消息（短消息/提醒）`、`搜索`、`我的`、`浏览历史/我的帖/收藏`、`用户资料` 的顶栏材质（建议：纯列表类顶栏可按同一预算复用 `TopBar` 透明玻璃或随滚变色纯色；带常驻输入框/复杂头部的 `搜索`、`短消息对话`、`我的` 总页、`用户资料` 归入 [§5 L230](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L230) 纯色顶栏例外）。
5. **S5 — 帖子详情“更多”菜单去重**：详情顶栏右侧已有独立 `只看楼主` 圆形按钮、底部工具条右段已有独立 `收藏` 按钮，而 [copy.md L36](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L36) 的帖子 `更多` 菜单仍重复列出了 `收藏 / 取消收藏` 与 `只看楼主 / 看全部`，建议从 `更多` 菜单移除这两组重复项，贯彻第 2 节“此刻没用的，就不出现”。
6. **S6 — 移除从 kudo 误搬入的快速回复 `引用` / `粗体` 快捷按钮**：删除 [product-design.md §7.3 L383](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L383)、[copy.md L37](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L37) 与 [backlog.md L79](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L79) 中的 `引用`、`粗体`，保持与旧版 HiPDA 一致的 `插入图片` + `表情` 两个快捷入口（无需横向滚动条，也避免在编辑器里裸露 Discuz! `[quote]`/`[b]` 标签）。


---

# 复审报告（修订轮 1）：`origin/design/kudo-glass` @ `6f139c4`

- **复审结论**：**`APPROVE 6f139c4`**
- **审查范围**：`git diff 102b1c3..6f139c4 -- docs/design`（4 个文件，`+116 / -90`）及 [D-kudo.md](file:///home/mir/Documents/hipda-next.wt/_reports/D-kudo.md#L64-L113) 第五节追加说明
- **对照源码**：[HiAdaptiveShell.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt)、[HiColors.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiColors.kt)、[HiGlassSurface.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/glass/HiGlassSurface.kt)、[HiGlassTestSupport.kt](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/glass/HiGlassTestSupport.kt)

---

## 一、三项阻断闭合逐项核验

### 1. B1 闭合验证：玻璃区域计数逐档自洽 + `C4d` 下放 `hiGlassSource` 对照 `HiAdaptiveShell.kt` 可行性

1. **区域语义计数与物理渲染预算逐档自洽（已闭合）**
   - [product-design.md §5 L240–246](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L240-L246)、[§6.4 L359](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L359)、[§10.3 L567](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L567) 与 [tech-plan.md §4 L42](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/tech-plan.md#L42) 明确将“1 块”锚定为**1 个独立玻璃区域（`HiGlassMarker = true` 根节点）**：
     - 透明顶栏 = 1 个 `HiGlassMarker`；**详情顶栏内 `40dp` 圆形动作按钮严禁嵌套 `HiGlassSurface` / `drawBackdrop`**，改为 `TopBar` 内部的轻量半透明纯色底（浅 `surfaceContainerHigh @ 55%–65%` / 深 `60%–70%` + `0.5dp` 细边）；
     - 底部导航胶囊（含 API 33+ 拖动折射镜片）= 1 个 `HiGlassMarker`；
     - 底部三段式工具条（及展开后的内联跳页条）= 外层容器记 1 个 `HiGlassMarker`（内部左/中/右 3 胶囊共享 `detailGlassState` 绘制，子胶囊不单独挂 `HiGlassMarker`）；
     - 毛玻璃快速回复面板 = 1 个 `HiGlassMarker`（展开时**停绘并卸载**底部工具条的 `drawBackdrop` 与 `HiGlassMarker`，互斥活跃）。
   - 全档位验算表（全部满足 `HiGlassMarker ≤ 3`、独立 `LayerBackdrop ≤ 2`、静止 `drawBackdrop ≤ 5`）：
     - **单栏一级页**（`< 600dp` / `< 480dp` / 铰链退单栏）：论坛/消息 `2` 区域、`1` 源、`2` pass；搜索/我的 `1` 区域、`1` 源、`1` pass；
     - **单栏二级页（帖子详情）**：`2` 区域（顶栏 + 三段工具条*或*回复面板）、`1` 源、`4` pass（跳页条展开或回复面板态降为 `2` pass）；
     - **图标轨单栏（`600–679dp`）**：左轨纯色 `0`；右侧列表页 `0–1` 区域、`0–1` 源；帖子详情 `2` 区域、`1` 源、`≤ 4` pass；
     - **紧凑三栏（`680–839dp`）与完整三栏（`≥ 840dp`）**：左栏及 `240dp` 浮层纯色 `0`；中栏列表顶栏 `1` + 右栏详情顶栏 `1` + 右栏三段工具条/回复面板 `1` = **`3` 区域、`2` 源、静止 `5` pass**（搜索/设置中栏纯色则降为 `≤ 2` 区域、`≤ 1` 源）；
     - **全屏阅读**：隐藏左中栏并释放 `middleGlassState`，退为 **`2` 区域、`1` 源、`≤ 4` pass**；
     - **铰链双栏（`isSeparating == true` 且 `≥ 280/360dp`）**：左列表顶栏纯色 `0` + 左紧凑胶囊 `1` + 右详情顶栏 `1` + 右底部工具条/回复面板 `1` = **`3` 区域、`2` 源、静止 `5` pass**。
   - 与已派发 `G2` 零返工衔接：`G2` 现有的 `MaxGlassSurfacesOnScreen = 3` 与 `assertGlassSurfaceCountAtMost(3)` 无需改动即可直接守卫终态。

2. **对照现网 `HiAdaptiveShell.kt` 核查 `C4d` 下放 `hiGlassSource` 的可行性（完全可行）**
   - 核查 [HiAdaptiveShell.kt L59–68](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L59-L68)、[L248–283](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L248-L283) 与 [L321](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L321)：现网仅在 `L253` 的外层 `ContentContainer` 挂了 `.then(if (isSidebar) Modifier else Modifier.hiGlassSource(glassState))`，而底部 `HiNavigationPill` 位于 `L321` 的外部兄弟分支。
   - `C4d` 移除 `L253` 的外层 `hiGlassSource`，改为在 `ShellContentScope` 暴露 `val shellGlassState: HiGlassState?`（`!isSidebar` 时传入 `glassState`，侧栏态为 `null`）或提供同级 `topOverlay` 槽位后：页面内部仅给纯滚动 `LazyColumn` 挂 `.hiGlassSource(shellGlassState)`，页面透明顶栏与外壳 `HiNavigationPill` 均位于该 `LazyColumn` 的 `LayoutNode` 子树之外（分别为同父兄弟与叔父分支），Kyant `LayerBackdrop` 按屏幕/根坐标对齐采样，**无循环采样、无父子双录制，且改动严格收敛在 C 线负责的 `ui/shell/` + `ui/designsystem/` 目录内**。
   - *给 `C4d` 实现的非阻断落地提示*：
     1. [HiAdaptiveShell.kt L259](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/shell/HiAdaptiveShell.kt#L259) 使用 `AnimatedContent(targetState = selected)` 做一级目的地交叉淡入淡出，过渡期间进出场两个 `destination` 同时存在于组合树中；建议仿照 `L271` 的 `gatedNestedScrollConnection`，仅对 `currentSelected == destination` 的目标页暴露非空 `shellGlassState`（或门控 `hiGlassSource` 绑定），避免切换瞬间两个 `LazyColumn` 同时向同一个 `LayerBackdrop` 写入；
     2. 为兼容 `E1–E11` 尚未合入前的占位预览页及纯色顶栏页，可在 `HiAdaptiveShell` 增加默认自动挂载回退（例如页面未声明自管透明顶栏时仍由内层默认槽位挂 `shellGlassState`），防止纯色顶栏页漏挂导致底部胶囊无采样内容。

---

### 2. B2 闭合验证：三层对比度验收可执行性与全 20 套预设峰值 alpha 数学验算

移植 [HiColors.kt](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiColors.kt) 的 OKLCH 色阶生成算法与 [HiGlassTestSupport.kt L92–105](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/designsystem/glass/HiGlassTestSupport.kt#L92-L105) 的 `Color.compositeOver`（sRGB 合成）+ `wcagContrastRatio`，对 `6f139c4` 规定的全部参数做全矩阵穷举验算（**20 套预设 × 浅色/深色/OLED 纯黑 × 4 种标准背景 `background`/`surface`/`surfaceContainerLow`/`surfaceContainer` × 四档着色上下界 × 全部叠加层峰值 alpha**）：

| 验收层与测试组合 | 规定参数（§5 L230–232、L265–274、L289） | 全矩阵最低对比度（最差预设/场景） | 是否 `≥ 4.5:1` |
|---|---|---|---|
| **层 (a) 94% 降级态 × 极端黑白** | `surface @ 0.94` over `#000000` / `#FFFFFF`，前景 `onSurface` / `onSurfaceVariant` / `primary` | **`5.38 : 1`**（`cyan/light/black/primary`） | **全通过（0 失败）** |
| **层 (b) `Navigation` 基础与指示器** | 浅 `0.42–0.50` / 深 `0.44–0.52` + 指示器 `primary @ ≤ 0.12` | 基础 **`5.92 : 1`**；叠指示器 **`4.86 : 1`**（`red/light/surfaceContainer/a=0.42`） | **全通过（0 失败）** |
| **层 (b) `TopBar` 基础与纯色圆钮** | 浅 `0.64–0.72` / 深 `0.68–0.76`；圆钮 `surfaceContainerHigh` 浅 `0.55–0.65` / 深 `0.60–0.70` | 基础 **`6.12 : 1`**；圆钮 **`5.56 : 1`**（`red/light/ba=0.65/primary`） | **全通过（0 失败）** |
| **层 (b) 版块荧光笔底条** | `primaryContainer` 浅 `≤ 0.55` / 深 `≤ 0.45`（或 `primary` 浅 `≤ 0.22` / 深 `≤ 0.24`），前景 `onSurface` | `primaryContainer` **`≥ 8.5 : 1`**；`primary` **`7.13 : 1`**（`grey/dark`） | **全通过（0 失败）** |
| **层 (b) `Toolbar` 与阅读进度填充** | 浅 `0.62–0.72` / 深 `0.68–0.76`；填充 `primaryContainer` 浅 `≤ 0.50` / 深 `≤ 0.45`（或 `primary` `≤ 0.22/0.24`） | 基础 **`6.11 : 1`**；叠进度填充 **`7.13 : 1`**（`grey/dark`） | **全通过（0 失败）** |
| **层 (b) `Sheet` 快速回复面板** | 浅 `0.72–0.80` / 深 `0.70–0.78` | **`6.20 : 1`**（`red/light/surfaceContainer/a=0.72/primary`） | **全通过（0 失败）** |
| **层 (c) 真机极端样张验收** | 上调 `TopBar`/`Toolbar` 至浅 `0.62–0.72` / 深 `0.68–0.76` + 真机纯黑/纯白/七色样张人工核验 | 浅 `0.72` 叠 `#000000` 达 `4.67:1`、深 `0.70` 叠 `#FFFFFF` 达 `4.54:1`，移除不可测 Robolectric 模糊像素要求 | **可执行且余量充足** |

- *给 `C4d` / `E1` / `E4b` 的非阻断工程提示*：若未来个别绘制路径使用线性光空间（F16 linear）而非默认 sRGB `compositeOver` 混合，优先采用设计稿推荐的 `primaryContainer` 分支在线性混合下仍稳达 `≥ 5.8:1`（若用深色 `primary` 分支则取 `0.18–0.20` 即可同时兼顾 sRGB 与线性 `≥ 4.65:1`，完全在 `≤ 0.24` 上限内）。

---

### 3. B3 闭合验证：内联跳页条 `< 480dp` 统一双行 + 矮屏回复面板限高与流转

- **内联跳页条 `< 480dp` 统一双行 & 紧凑三段态通用判定（已闭合）**：[product-design.md §4.1 L88–90](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L88-L90)、[§5 L288–289](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L288-L289) 与 [§7.3 L400](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L400) 统一按详情容器可用宽 `< 480dp` 切换双行排布，完整覆盖 `340dp` 分屏窄窗、`411dp` 手机竖屏单栏与 `360–465dp` 三栏右栏。（*工程小提示*：`340dp` 分屏在 `1.25×` 字体缩放下扣边距后下行剩余给滑块约 `132–140dp`，`E4b` 实现时滑块用 `Modifier.weight(1f)` 自适应填充而不要写死 `Modifier.widthIn(min = 140.dp)`，即可在 `340dp` 极端档零挤压通过。）
- **矮屏弹键盘回复面板限高、`E5` 插入图片流转、跳页条不滚底层列表（已闭合）**：
  - 面板按 `min(300dp, 可视净高 × 60%)` 动态限高；净高 `< 180dp` 自动隐藏把手与草稿横条压为紧凑行，不再强求矮屏露出原楼；
  - `E5` 点 `插入图片` 明确携带当前文本、光标与引用直入 `E6` 完整编辑器并拉起选图，职责边界清晰；
  - 聚焦内联跳页条仅 `imePadding()` 上移自身，底层楼层列表滚动偏移严格锁定 `0dp`（[§6.3 L345](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L345)、[§10.1 L549](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L549)）。

---

## 二、建议项 S1–S6 落实核查与跨章节一致性

- **S1（`C3b → G2` 统一与 `TopBar`/`Sheet` 归属）**：全套文档 `C3b` 残留为 0；`G2` 与 `C4d` 职责边界及 `E1`/`E2a`/`E3`/`E4b`/`E5` 依赖链（统一依赖 `C4d`）完全对齐。
- **S2（快速回复面板垂直拖拽隔离）**：已在 [§6.3 L355](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L355)、[§7.3 L409](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L409)、[§10.4 L573](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L573) 落地。
- **S3（底部胶囊 `horizontalTouchSlop`、竖向 `> 48dp` 弹回与 `< 452dp` 防返回冲突）**：已在 [§5 L284](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L284)、[§6.3 L339](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L339)、[§10.4 L573](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L573) 落地。
- **S4（其余非论坛页面的顶栏材质归属）**：已在 [§4.2 L147–163](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L147-L163) 与 [§5 L229–237](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L229-L237) 逐页明确，且与玻璃拓扑表一致。
- **S5（帖子“更多”菜单去重 `收藏` 与 `只看楼主`）**：已在 [copy.md L36](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L36) 与 [product-design.md §5 L290](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L290) 落地。
- **S6（删除误搬自 kudo 的 `引用`/`粗体` 快捷按钮）**：已在 [product-design.md §7.3 L409](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L409)、[§11 L577](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/product-design.md#L577)、[copy.md L37](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/copy.md#L37) 与 [backlog.md L79](file:///home/mir/Documents/hipda-next.wt/D-kudo/docs/design/backlog.md#L79) 全量清理。
- **新冲突检查**：全量核对四份设计文档的章节交叉引用、任务依赖链与验收矩阵，**零新引入矛盾，准予合入定稿**。
