# 设计方案审查报告 R-D3pane：大屏三栏布局（`origin/design/three-pane @ 71b20fd`）

- **审查结论**：**CHANGES-REQUESTED**（评审对象：`origin/design/three-pane` @ `71b20fd`）
- **对标范围**：
  - 设计稿变更：[product-design.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md)、[copy.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/copy.md)（对比 `origin/main...71b20fd`）
  - 关联设计文档：[tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/tech-plan.md)、[backlog.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/backlog.md)
  - 任务卡与起草报告：[D-3pane.md (card)](file:///home/mir/Documents/hipda-next.wt/_cards/D-3pane.md)、[D-3pane.md (report)](file:///home/mir/Documents/hipda-next.wt/_reports/D-3pane.md)
  - 已合并代码基线：`origin/main`（`hipda/src/main/`、`hipda/src/debug/`、`hipda/src/test/`）

---

## 0. 结论总览

起草方案整体架构清晰：遵循“不按机型特判、按高度→物理铰链→窗口宽度（`600 / 680 / 840dp`）通用分档”原则，借鉴了 FluxDO“600dp 起左侧图标轨取代底栏 + 先扣左栏宽再算内容双栏 + 同树单一状态栈保活”的核心优点，并在 `749 × 559dp`（用户 Galaxy Z Fold8 真机实测自然展开）下达成了 `64 | 274 | 411dp` 的紧凑三栏。

但逐档代入演算、跨章节交叉核对与对照 `main` 已合并源码后，发现 **4 项阻断缺陷（B1–B4）** 与 **8 项改进建议（S1–S8）**。若直接按当前 `71b20fd` 派发实现卡，会导致：
1. `C4c/E3` 的栏宽单测在铰链分隔场景下因 §4.1（`≥260dp`）与 §10.2（`恒 ≥360dp`）断言直接冲突而无法通过；
2. 折叠屏从展平三栏半折进入书本态（`isSeparating: false → true`）时，`浏览历史 / 我的帖子 / 收藏与关注` 右栏正在阅读的帖子详情无落点而丢失阅读状态（触犯 `DISPATCH.md §4` 阻断红线）；
3. §5 左栏未滚动顶栏色块与侧栏底色割裂、§6.3 切换版块存在两套冲突动效令牌；
4. 用户真机 Fold8 横屏（高 `559dp`）下左栏首屏被 9 个功能项占满，**关注版块 100% 被挤出可视区**，且“自左栏右滑”与 Android 系统边缘预测返回手势冲突、旧版版块图标缺 Compose 矢量资源与碰撞回退。

---

## 一、分档规则自洽性审查与逐点演算

### 1.1 左栏宽度是否计入门槛？是否存在断点自激振荡？
1. **左栏宽度已严格计入门槛**：
   - 紧凑三栏门槛 `680dp = 64dp（左图标轨）+ 256dp（中栏最小宽）+ 360dp（右栏最小宽）`，先扣 `L = 64dp` 得 `S = W − 64dp`，再分配中右两栏，不存在漏扣左栏宽导致的挤压带。
   - 完整三栏门槛 `840dp`：展开左栏 `L = clamp(0.20 × 840, 176, 220) = 176dp`，剩余 `S = 664dp`，`M = 265.6dp`，`R = 398.4dp ≥ 360dp`。
2. **断点连续性与无振荡证明**：
   - 所有分档条件（`H < 480`、`hingeSeparating`、`W < 600`、`W < 680`、`W < 840`、`W ≥ 840`）均为外层窗口尺寸 `containerDpSize` 与硬件姿态的纯函数，不依赖子组件测量回传，**不存在任何阈值自激振荡（layout oscillation）**。
   - 在展平态 `W ≥ 680dp` 全定义域内，分段导数恒非负：
     - `680 ≤ W < 840`：`L = 64`；`W ∈ [680, 704]` 时 `M = 256, R = W − 320 ∈ [360, 384]`；`W ∈ (704, 840)` 时 `M = 0.4(W−64) ∈ (256, 310.4)`，`R = 0.6(W−64) ∈ (384, 465.6)`。
     - `W ≥ 840`（展开）：`W ∈ [840, 880]` 时 `L = 176, M ∈ [265.6, 281.6], R ∈ [398.4, 422.4]`；`W ∈ (880, 1100]` 时 `L = 0.2W, M = 0.32W, R = 0.48W`；`W ∈ (1100, 1170]` 时 `L = 220, M = 0.4(W−220), R = 0.6(W−220)`；`W > 1170` 时 `L = 220, M = 380, R = W − 600`。
     - `839dp → 840dp` 处：栏数维持 3 栏不变；若左栏默认展开，因 `L` 从 `64dp` 升级为 `176dp`（`+112dp`），中栏由 `310dp` 收至 `265.6dp`、右栏由 `465dp` 收至 `398.4dp`（属左栏展开的预期台阶，由 `spatialSlow` 过渡）；若用户主动收起左栏，则 `64 | 310 | 465` → `64 | 310.4 | 465.6` 逐像素连续。
   - 均衡档 `M_balanced = min(0.50 × S, S − 360, 480)` 在 `S ≥ 616` 上恒满足 `M_balanced ≥ M_default` 且 `R_balanced = S − M_balanced ≥ 360dp`；当且仅当 `S < 640`（紧凑三栏 `680 ≤ W < 704dp`）时 `M_balanced − M_default < 24dp` 自动合并为 2 档 `[0, 256]`，`W ≥ 704dp` 均稳定为 3 档。

### 1.2 指定宽度与铰链分隔逐点演算表

> 前提：常规行设高度 `H ≥ 480dp`（Fold8 横拿 `749 × 559dp` 因 `enableEdgeToEdge()` 窗口高 `559dp ≥ 480dp` 成立）且 `hingeSeparating == false`；最后两行演算物理铰链分隔。

| 代入条件 (`W` dp) | 命中档位 | 栏数 | 左栏 `L` (dp) | 剩余 `S` (dp) | 中栏默认 `M` (dp) | 右栏默认 `R` (dp) | 中右吸附档位（右栏有内容） | 最小宽与矛盾检查 |
|---|---|---|---|---|---|---|---|---|
| **`559`**（Fold8 竖） | 窄屏单栏 (`<600`) | 1 | `0`（底部胶囊 ≤420） | `559` | — | 单栏 `559` | — | 正常（对照 `599dp` 为单栏 `599`） |
| **`600`** | 图标轨单栏 (`600–679`) | 2（轨+单栏） | `64`（浮层 `240`） | `536` | — | 单栏 `536` | — | 正常；599↔600 仅切导航载体 |
| **`679`** | 图标轨单栏 (`600–679`) | 2（轨+单栏） | `64`（浮层 `240`） | `615` | — | 单栏 `615` | — | 正常 |
| **`680`** | 紧凑三栏 (`680–839`) | 3 | `64` | `616` | `256`（下限钳制） | **`360`** | `[0, 256]`（均衡 `256` 合并） | 正常；`64+256+360=680` 精确闭合 |
| **`749`**（Fold8 横） | 紧凑三栏 (`680–839`) | 3 | `64` | `685` | `274` | **`411`** | `[0, 274, 325]`（均衡 `R=360`） | 横向宽度成立；**但纵向 `H=559dp` 首屏放不下一条版块（见 B4）** |
| **`839`** | 紧凑三栏 (`680–839`) | 3 | `64` | `775` | `310` | **`465`** | `[0, 310, 387.5(≈388)]` | 正常 |
| **`840`**（默认展开） | 完整三栏 (`≥840`) | 3 | `176`（下限钳制） | `664` | `265.6`（`≈266`） | **`398.4`**（`≈398`） | `[0, 265.6(≈266), 304]` | `R ≥ 360` 成立；需约定浮点取整规则（见 S1） |
| **`840`**（主动收起） | 完整三栏 (`≥840`) | 3 | `64` | `776` | `310.4`（`≈310`） | **`465.6`**（`≈466`） | `[0, 310.4(≈310), 388]` | 与 `839dp` 完全连续 |
| **`900`**（默认展开） | 完整三栏 (`≥840`) | 3 | `180` | `720` | `288` | **`432`** | `[0, 288, 360]` | `180+288+432=900` 精确整除 |
| **`1024`**（默认展开） | 完整三栏 (`≥840`) | 3 | `204.8`（`≈205`） | `819.2`（`≈819`） | `327.68`（`≈328`） | **`491.52`**（`≈491`） | `[0, 327.7(≈328), 409.6(≈410)]` | **注意**：若三栏各自四舍五入得 `205+328+492=1025≠1024`（见 S1） |
| **`1280`**（默认展开） | 完整三栏 (`≥840`) | 3 | `220`（上限钳制） | `1060` | `380`（上限钳制） | **`680`** | `[0, 380, 480]` | `220+380+680=1280`，`R` 恰等于正文最大行宽 |
| **铰链分隔 A**：`W ∈ [520, 719]`（如 `559 / 639 / 680 / 701dp` 半折，`hingeBounds` 居中宽 `0–12dp`） | 铰链分隔 (`isSeparating=true`) | 2（左列表\|右详情） | `0`（列表侧紧凑胶囊） | `W − H_hinge` | 单侧 **`260–359.5`**（如 `559→279.5`，`639→319.5`，`701→350.5`） | **右栏 `260–359.5`（`< 360dp`！）** | 固定铰链 + `[0]` | **【阻断 B1】** 右栏跌破 §4.1 L82 的 `360dp` 硬底线，且直接令 §10.2 L460 单测断言失败！ |
| **铰链分隔 B**：任一侧 `< 260dp`（如 `W < 520dp` 或多窗口偏置跨铰链 `200 \| 380dp`） | 铰链分隔回退单栏 | 1 | **未定义** | `W` | — | 单栏 `W` | — | **【阻断 B1】** 内容退回单栏铺满跨过铰链，但 `shellNavigationMode` 仍强制返回 `CompactPill`，胶囊放哪未定义！ |

### 1.3 【阻断 B1】铰链分隔规则与右栏 `360dp` 硬底线及 §10.2 单测断言自相矛盾
- **涉及位置**：[product-design.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L76)、[L82](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L82)、[L100](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L100) vs [L460](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L460)。
- **矛盾细节**：
  1. §4.1 L76 与 L100 规定竖向铰链分隔时“单侧最低 `260dp`”，意味着总宽 `520–719dp`（例如 vivo X Fold 初代竖拿 `639dp` 半折单侧 `319.5dp`、Pixel Fold 竖拿 `701dp` 半折单侧 `350.5dp`、或 `559–600dp` 竖向铰链设备单侧 `279.5–300dp`）会开启铰链双栏，此时**右栏宽度仅 `260–359dp`**。
  2. 但 §4.1 L82 明确写道：“因此以 `360dp` 作为**任何三栏/双栏配置下右栏不可跌破的硬底线**”，且同一段刚证明右栏底部毛玻璃工具条（`回复` + `第 N / M 页` + `更多`）净宽就需 `280–304dp`，加上左右各 `16dp` 边距共需 `312–336dp`，在 `260–300dp` 右栏内必然挤压裁切；同时左栏 `260dp` 内放紧凑胶囊（左右各 `16dp` 边距后剩 `228dp`），在 `1.25×` 字体缩放下 `4` 项宽度之和达 `~268dp > 228dp`。
  3. **单测契约直接冲突**：§10.2 L460 要求单元测试覆盖 `340..1280dp`、高度 `<480dp` 及**竖向物理铰链分隔**时，断言 **`右栏宽（恒 ≥ 360dp）`**（且 `<680dp` 与矮屏本身并无右栏）。实现者无论按 `260dp` 还是 `360dp` 写代码都会违反设计稿其中一处。
  4. 当 `isSeparating == true` 但任一侧 `< 260dp` 退回单栏铺满窗口时，当前 `ShellNavigationMode.kt:69` 无条件返回 `CompactPill`，未说明退回单栏后应恢复常规单栏底栏规则（按宽度 `<600dp` 标准胶囊 / `≥600dp` 紧凑胶囊居中避开铰链）。
- **修复方向**：
  - 要么将铰链双栏的右栏（或双侧）最低宽度提高至 `320–360dp`（例如列表侧 `≥ 280dp`、详情侧 `≥ 340/360dp`，不足时退回单栏；Fold8 横展半折 `749dp` 单侧约 `374dp`、Fold6 横展 `860dp` 单侧 `430dp`、Pixel 9 Pro Fold `852dp` 单侧 `426dp` 均完全不受影响！），并在 §4.1 L82 与 §10.2 L460 精确区分“常规三栏（`≥680dp`）右栏恒 `≥360dp`；铰链双栏按铰链边界校验最低宽；单栏/图标轨单栏断言 `rightPaneWidth == 0dp`”；
  - 同时补齐“铰链分隔但单侧不足最低宽退回单栏时”的导航栏形态规则。

---

## 二、与设计稿其余章节及同目录设计文档的冲突审查

### 2.1 旧描述清理核查（`product-design.md` & `copy.md`）
- **原 `80dp` 左侧导航轨**：在 `product-design.md` 中已全部清理完毕，仅在版本说明（L3）、替代说明（L41、L78、L86）和否决方案（L183）中作为历史对照出现。
- **原 `600–839dp` 底部胶囊 + 详情工具条双层叠放**：在 §4.1、§5、§10.3 中已全部清理完毕，仅在 §4.4 否决项 #2（L179–180）提及。
- **论坛顶部横向版块标签行**：已在 §3.1/§3.2、§4.1、§4.2、§4.3、§6.3、§10.2 中明确规定 `<600dp` / `<480dp` / 铰链双栏保留，`≥600dp`（含 `600–679dp` 图标轨单栏与 `≥680dp` 三栏）一律隐藏。
- **微小残留文案**：[copy.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/copy.md#L31) 仍写 `双栏顶栏的切换按钮 | 全屏阅读 / 显示列表`（应改为 `双栏 / 三栏详情顶栏的切换按钮`）；[product-design.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L224) `spatialSlow` 仍只写 `双栏出现或消失`。

### 2.2 【阻断 B2】§4.2 与 §4.3 漏掉“展平三栏 ↔ 半折铰链双栏”状态迁移，且铰链双栏下 `我的` 类子页丢失正在阅读的帖子详情
- **涉及位置**：[product-design.md §4.2](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L136-L140)、[L148-L158](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L148-L158)、[§4.3](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L160-L171)、[§10.2](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L461)。
- **具体触发路径**：
  1. 用户在折叠屏完全展平态（`isSeparating == false`，如 Fold8 `749dp` 或 Fold6 `860dp` 三栏）下，点左栏 `浏览历史`（或 `我的帖子`、`我的回复`、`收藏与关注`），在中栏点开帖子 `T` 阅读到第 3 页第 12 楼，并在右栏底部快速回复框输入了半句话；
  2. 用户将内屏微折成半折书本态（`isSeparating` 变为 `true`，窗口总宽仍为 `749dp/860dp`），按 §4.1 第 2 优先级强制从**三栏**切入**铰链双栏**（导航切回 4 目的地紧凑胶囊）。
- **矛盾与状态丢失**：
  - 看 §4.2 第 136–138 行“2 栏 / 铰链双栏”列：
    - `浏览历史`：`铰链双栏：左我的菜单 | 右历史列表`
    - `我的帖子 / 我的回复`：`铰链双栏：左我的菜单 | 右主题/回复列表`
    - `收藏与关注`：`铰链双栏：左我的菜单 | 右收藏与关注`
  - 两栏已被 `我的菜单` 和 `子列表` 全部占满：**既未定义在铰链双栏下点击 `右历史列表` 中的帖子后详情页在哪里打开（右栏内下钻压栈 `[历史列表, 帖子详情]`？还是左栏变成历史列表、右栏放详情？），更导致从展平三栏半折进入铰链双栏时，右栏原本打开的帖子 `T` 与未发出的快速回复没有承载位置而直接丢失**（违反 `DISPATCH.md §4` 阻断条款：“折叠、展开或旋转导致崩溃，或丢失阅读状态”）！同理，`设置`（L140：`铰链双栏：左分组 | 右子页`）如何从铰链双栏的 `我的` 目的地进退也未闭环。
  - §4.3 与 §10.2 L461 列举了 4 对宽度切换，唯独漏掉了折叠屏实机最常触发的**同宽度姿态切换：`展平三栏（isSeparating=false）↔ 半折铰链双栏（isSeparating=true）`**。
  - 此外，§4.2 L148–158 返回栈规则只写了三栏的“中栏 + 右栏”，漏写了 **`600–679dp` 图标轨单栏（2 栏）**下点击左栏切换入口时如何替换单栏内容栈（例如正在 `[Discovery 列表, 帖子 A]` 时点左栏 `Geek Talks` 应清空 `帖子 A` 并直接替换根列表为 `Geek Talks`，不压版块历史）。

### 2.3 【阻断 B3】§5 顶栏变色与左栏底色冲突、§6.3 切换版块双动效冲突、`tech-plan.md` 引用失真
- **矛盾 3a（§5 顶栏与左栏表面色割裂）**：
  - [product-design.md L198](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L198) 写：“内容未滚动时是 `surface`，滚动后用 `effectsDefault` 渐变为 `surfaceContainer`。双栏或三栏时，**左栏顶部固定账号行**、中栏顶栏、右栏顶栏各自独立随本栏滚动变色。”
  - 但 [L205](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L205) 紧接着写：“**左侧栏（`≥ 600dp`）：纯色 `surfaceContainer` 表面**”。
  - 结果：左栏列表未滚动时，顶部固定账号行是 `surface`，而左栏其余区域已经是 `surfaceContainer`——未滚动时顶部反而有一块异色补丁，滚动后才变成与侧栏同色！左栏顶部固定区应恒为 `surfaceContainer`（滚动后用 `1dp` `outlineVariant` 底部分隔线或 `surfaceContainerHigh` 区分）。
- **矛盾 3b（§6.3“切换版块”同时出现在两行且令牌不同）**：
  - [product-design.md L243](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L243)（`同级换内容`）写：“在中栏（或左栏）点另一条帖子、另一个会话、**另一个版块** | 中栏/右栏**原地交叉淡入淡出，没有位移** | `effectsDefault`”。
  - [L248](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L248)（`内容替换`）却仍写：“**切换版块**、切换‘只看楼主’、跳到不相邻的页 | 旧内容淡出；新内容淡入，**同时从下方 8dp 移到原位** | 淡出 `exit(effectsDefault)`；淡入 `effectsDefault` + `spatialDefault`”。
  - 同一“切换版块”动作被赋予“无位移纯淡入”与“下方 8dp 位移淡入”两套互斥规范，`E1` 与 `E3` 动效逐帧测试无法统一判定。
- **矛盾 3c（跨文档双向引用不一致）**：
  - [product-design.md L104](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L104) 标注 `（tech-plan §7）`，[L435](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L435) 标注 `截图矩阵（分级，与 tech-plan §8 一致）`，但同目录下的 [tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/tech-plan.md#L67-L89)（§1/§4/§7/§8 仍写 `300+380=680dp` 双栏与 `4 种宽度 × 深浅色`）和 [backlog.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/backlog.md#L53-L73)（`C4c` 未入表、`E3` 仍写双栏）均未同步更新（D-3pane 卡片边界允许修改 `docs/design/` 下全部文档）。

### 2.4 其他细节不一致（建议级）
- **§3.2 窄→宽映射非确定性措辞**（[L67](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L67)）：“默认高亮 `收藏与关注`（或最近访问子项）”含“或”，测试断言不唯一；建议固定为“优先恢复本次会话最近访问的‘我的’子项，未访问过时默认 `收藏与关注`”。同时 §3.1 [L37](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L37) 二级页面列表漏列了 `收藏与关注`、`我的帖子`、`浏览历史`、`管理版块`、`短消息对话`。
- **§4.2 完整编辑器在 `600–679dp` 覆盖范围不一致**（[L143](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L143)）：`600–679dp` 写“覆盖整个内容区”（露出左侧 `64dp` 轨），而 `680–1159dp` 写“覆盖全部三栏水平居中模态打开”（盖住左侧 `64dp` 轨），`679dp ↔ 680dp` 差 1dp 却一个露轨一个盖轨；应与登录/管理版块统一为“覆盖全部栏水平居中”。
- **`copy.md` 空状态缺口**：§3.2 L58 提到“未关注任何版块时引导点 `管理版块`”，§4.2 L139 三栏下点 `搜索` 时右栏未选结果时的空占位文案，均未在 [copy.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/copy.md#L53-L56) 定义（或者明确规定 `管理版块` 至少保留 1 个关注版块、搜索右栏复用 `选一篇帖子开始阅读`）。

---

## 三、易用性与无障碍风险审查

### 3.1 【阻断 B4】Fold8 横屏（`749 × 559dp`）首屏版块完全不可见 + 版块图标缺回退 + 左边缘右滑与系统返回手势冲突
1. **纵向高度算术硬伤：用户真机 Fold8 自然展开下首屏看不到任何关注版块！**
   - 用户 Fold8 自然展开窗口为 **`749 × 559dp`**（窗口高 `559dp`）。
   - 按 [product-design.md §3.2 L43-L58](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L43-L58) 与 [§4.1 L86/L102](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L86) 逐项累加左栏高度：
     - 系统状态栏 + 底部手势栏/任务栏内边距：约 `40–64dp` → 左栏净可用高仅 **`495–519dp`**；
     - 顶部固定 `展开侧栏` 按钮（`48dp` + 上下内边距 `8dp` = `56dp`）+ 底部固定 `账号头像`（收起态 `48dp` + `8dp` = `56dp`）→ 中间滚动区仅剩 **`383–407dp`**；
     - 上方 9 个功能入口（`搜索`、`短消息`、`提醒`、`浏览历史`、`今日新帖`、`我的帖子`、`我的回复`、`收藏与关注`、`设置`），每项最小触摸高 `48dp`：`9 × 48dp =` **`432dp > 407dp`**！
   - **后果**：在用户的 Fold8 内屏横拿时，无论常驻 `64dp` 图标轨还是点开的 `240dp` 浮层侧栏，**首屏只能看到前 8 个个人/工具入口，用户关注的版块（`Discovery`、`Buy & Sell`、`Geek Talks`）100% 被挤在首屏视野之外，每次切版块都必须先滚左栏**！
   - **根因对照**：旧版 [MainFrameActivity.java:272-298](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/MainFrameActivity.java#L272-L298) 默认把 `我的帖子 / 我的回复 / 收藏` 收进一个可折叠的 `ExpandableDrawerItem`；而 FluxDO（报告 §2.1 L81）直接把**收藏版块放在左轨最上方主区域**、低频个人项放底部或二级抽屉。
   - **修复建议**：
     - 方案 A（推荐，最贴合高频切版块）：在 `64dp` 收起图标轨态，上半部只放高频项（如 `今日新帖`、`搜索`、`短消息`、`提醒` 4 项 = `192dp`）紧接**关注版块列表**，低频个人归档项（`浏览历史 / 我的帖子 / 我的回复 / 收藏与关注 / 设置`）在点开 `240dp` 浮层/`≥840dp` 展开态全部列出（或在收起轨底部归入一个 `更多/我的` 或置于版块下方）；或者在左栏将**关注版块 + 今日新帖置于上方、个人归档功能置于下方**（需与用户确认是否接受调整上下顺序或收起轨精简）。
     - 方案 B（若严格保持 9 项在上）：像旧版抽屉一样把 `我的帖子 / 我的回复 / 收藏与关注 / 浏览历史` 默认收合为 1 个可展开组，使上方压缩到 5–6 项（`240–288dp`），保证 `559dp` 高度首屏至少露出 3 个常用版块。
2. **`64dp` 图标轨上版块图标的源码现状、撞车与无图标回退缺失**：
   - [product-design.md L58](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L58) 写“HiPDA 各版块均内置独立图标”，但核查 [HiUtils.java:84-106](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java#L84-L106) 与 [hipda/build.gradle.kts:144](file:///home/mir/Documents/hipda-next/hipda/build.gradle.kts#L144)：
     1. 旧版 21 个版块图标全部来自 `FontAwesome.Icon`（随 `M7-2` 删除 `iconics` 一起废弃），且当前 `:hipda` 只依赖了 `material-icons-core`（无 `material-icons-extended`），Compose 侧尚无这 21 个版块的矢量图标资源；
     2. 旧版图标在无文字的 `64dp` 轨上严重撞车或表意不明：`Joggler (faw_cogs)` 与上方 `设置` 同样是齿轮；`PalmOS (mobile)` 与 `Smartphone (phone_square)` 同样是手机；`Geek Talks`、`改版建议` 与 `短消息` 都是气泡；`Discovery`、`意欲蔓延`、`只讨论2.0` 看纯图标难以对号入座；
     3. 若站点新增版块或 `fid` 不在静态映射表中，**无任何缺省回退定义**。
   - **修复建议**：在 `64dp` 收起轨中，版块项采用 **“带版块短标（1–2 字简称，如 `D` / `BS` / `极` / `墨` / `果` / `安`）的圆角徽章，或小图标 + 下方 `10–11sp` 两字简称”**，并对未知 `fid` 自动回退为版块名首字徽标；既零学习成本一眼认出版块、彻底消解相似图标和无图标问题，又不必为了猜图标每次长按弹 Tooltip。
3. **“自左栏右滑展开 `240dp` 浮层”与 Android 系统边缘预测返回手势直接冲突**：
   - [product-design.md L78/L79/L87](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md#L87) 规定在 `600–839dp` 可“自左栏右滑”展开 `240dp` 浮层。
   - 在 Android 全面屏手势导航下，屏幕左边缘 `0..24/32dp` 是系统级预测性返回热区（且 Android 限制 `systemGestureExclusionRects` 单边最多排除 `200dp` 高度），用户在 `64dp` 左栏做右滑极易误触发系统返回（直接关掉右栏帖子甚至退出应用！），同时也与 §7.1 L293“只用系统边缘预测性返回、取消自定义侧滑手势”自相矛盾。
   - **修复建议**：**展开**统一由顶部 `48×48dp` `展开侧栏` 按钮触发（不做左缘右滑唤出）；浮层**展开后**支持向左跟手拖拽收回（起点在 `x ≈ 240dp` 屏幕内部，完全不碰系统边缘返回区）、点遮罩、点条目、按返回键或 `Escape` 关闭；并且点击浮层内的 `管理版块` 或 `账号/登录` 打开居中模态框时同步收起该 `240dp` 浮层，避免双层模态叠放。

### 3.2 紧凑三栏 `274 / 411dp`（及 `680dp` 极值 `256 / 360dp`）可读性评估
- **右栏正文（Fold8 `411dp`，极值 `360dp`）**：`411dp` 等同主流直板大屏手机竖屏全宽（`16sp` 净宽 `379dp` 约 `23.7` 字/行；最大字号 `18sp × 1.3 = 23.4sp` 约 `16.2` 字/行），配合一键 `全屏阅读` 或将中栏拖至 `0dp`（右栏扩至 `685dp`、正文 `680dp`），正文阅读体验完全达标。
- **中栏列表（Fold8 `274dp`，极值 `256dp`）**：`16sp` 净宽 `224–242dp`（约 `14–15` 字/行，双行标题约 `28–30` 字）在纯文本紧凑排版下可读；**但需在 §4.2 / `E1` 明确窄列表排版约束**：当中栏宽 `< 300dp` 时，列表项**不得在左侧放置独立 `40dp` 大头像列**（否则标题净宽骤降至 `172–190dp`、最大字号下每行仅 `7–8` 字），头像应缩为 `20–24dp` 与作者/时间/回复数合排在副信息行，作者名设省略号截断以保住回复数与时间完整显示。

### 3.3 TalkBack 与外接键盘导航风险（§10.1 L456 未覆盖项）
1. **中右可拖拽分隔线无法通过无障碍与键盘操作**：纯拖拽手势对 TalkBack、开关控制与外接键盘用户不可达；需在 §4.1 / §10.1 要求分隔线节点暴露 `CustomAccessibilityAction`（切换 `默认宽度` / `均衡` / `收起列表`）及键盘焦点回车/方向键切换。
2. **`600–839dp` `240dp` 浮层焦点逃逸与 `Escape` 键**：若浮层不用 `ModalNavigationDrawer` 而用自定义 `Box` 叠层，TalkBack 左右扫读与蓝牙键盘 `Tab` 键会穿透暗罩落入底层中右两栏；必须约束浮层展开时底层内容树对无障碍隐藏（或用模态语义容器锁焦），并支持键盘 `Escape` 关闭。
3. **`64dp` 收起图标轨读屏语义与三栏遍历顺序**：收起态图标的 `contentDescription` 须合并名称与未读数（如 `短消息，3 条未读`），不能只靠长按 Tooltip；三栏容器需设 `isTraversalGroup = true` 与 `paneTitle`（左→中→右），防止读屏在三栏顶栏之间横向跳读。

---

## 四、实现可行性、影响清单核查与卡片拆分评估

### 4.1 Compose Material 3 Adaptive 组件覆盖度 vs 自写布局（对照 `DISPATCH.md §5` 与 `lesson.md:25`）
1. **外层导航（`HiNavigationSidebar`）**：
   - 不用 `NavigationSuiteScaffold` 的理由成立（`<600dp` 为毛玻璃滚隐胶囊，`≥600dp` 为含动态关注版块列表的双态侧栏）。
   - **但内部严禁手写自定义 `Layout`**：左栏只是“顶部固定行 + 中间 `LazyColumn(Modifier.weight(1f))` + 收起态底部固定头像”，用标准 `Column` / `Row` / `LazyColumn` 即可零自定义测量完成；`600–839dp` 的 `240dp` 浮层优先评估直接复用 M3 `ModalNavigationDrawer` / `ModalWideNavigationRail`（原生自带跟手拖回、遮罩、返回拦截与无障碍锁焦，且库字节码不受 `MotionTokenScanTest` 误伤，只需核验 `MotionDurationScale=0` 行为）。必须在 `C4c` 卡片写死“不准为侧栏手写 `Layout`”，防止重演 `C4a` 代码量膨胀 2–3 倍与二次测量崩溃（`lesson.md:25`）。
2. **中右内容双栏（`E3`）**：
   - 扣掉左栏后，内容区 `S = W − L` 是纯粹的 **2 栏 `List + Detail`**（工程上已引入 `material3-adaptive-layout:1.3.0` 与 `adaptive-navigation3:1.3.0`，见 [libs.versions.toml:96-99](file:///home/mir/Documents/hipda-next/gradle/libs.versions.toml#L96-L99)）。
   - 起草报告否定 `ListDetailPaneScaffold` 用于“三栏”是对的，但用于**中右两栏**时有两个可行路径：`ListDetailPaneScaffold`（自定义 `PaneScaffoldDirective` + `PaneExpansionState`）或轻量 `BoxWithConstraints + Row + AnchoredDraggable`（约 150 行标准组件组合，便于精确绑定 `HiMotion.spatialSnap()` 初速度继承与右栏空时动态移除 `0dp` 锚点）。无论选哪条，都不需要手写复杂 `SubcomposeLayout`/`Layout`，建议在 `E3` 卡中明确优先用标准 `Row` + `AnchoredDraggable`（或 `ListDetailPaneScaffold`），禁止手写多遍测量布局。

### 4.2 起草报告影响清单遗漏的已合并代码（核对 `origin/main`）

起草报告（[D-3pane.md §4](file:///home/mir/Documents/hipda-next.wt/_reports/D-3pane.md#L157-L182)）对 `ui/shell/` 核心文件定位准确，但**遗漏了以下已合并到 `main` 的生产与测试代码**：

| 遗漏文件与精确行号 | `main` 现状 | 为何受三栏方案影响 |
|---|---|---|
| [AppSettings.kt:24-28, 57-58](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/settings/AppSettings.kt#L57-L58)<br>[SettingsRepository.kt:42-43](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/settings/SettingsRepository.kt#L42-L43)<br>[DataStoreSettingsRepository.kt:53-54, 130-134, 181-190](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/data/settings/DataStoreSettingsRepository.kt#L53-L54)<br>[DataStoreSettingsRepositoryTest.kt:92-93, 201-205](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/data/settings/DataStoreSettingsRepositoryTest.kt#L92-L93) | 卡 `D3a` 已合入，仅持久化了中右分栏 `paneSplitPortrait` / `paneSplitLandscape`，**没有左栏展开/收起偏好字段**。 | 设计稿 §4.1 L87、§4.3 L171、§10.2 L461 新增要求“按横竖方向分别记住左栏展开/收起偏好并在进程回收后恢复”，必须新增 `sidebarCollapsedPortrait` / `sidebarCollapsedLandscape` 布尔偏好与读写方法。 |
| [HiShellScrollScreenshotTest.kt:79-82, 117-120, 147-154](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/shell/HiShellScrollScreenshotTest.kt#L79-L154) | 报告 §4.2 完全漏列该测试文件；目前仅测 `411×891` 滚到底与隐藏态。 | 既调用 `HiAdaptiveShell`，又直接对应设计稿 §10.3 L470 新增验收项（“侧栏态下底部无胶囊重叠。证据：滚动到底部时的截图测试”），须补 `≥600dp` 侧栏滚底截图用例。 |
| [HiShellScreenshotTest.kt:204-208](file:///home/mir/Documents/hipda-next/hipda/src/test/java/net/jejer/hipda/ui/shell/HiShellScreenshotTest.kt#L204-L208) | `captureShell()` 写死 `assertEquals("外壳截图应恰好包含 1 块毛玻璃", 1, glassCount)`。 | 报告 §4.2 L181 只提到重录截图基线，漏掉 L208 硬编码 `1` 块毛玻璃断言——`≥600dp` 侧栏改为纯色 `surfaceContainer` 后外壳自身毛玻璃数为 `0`，不改此断言将直接红灯。 |
| [HiTypography.kt:14, 152](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiTypography.kt#L14) | 注释写“导航胶囊、导航轨与底部工具条”。 | 顺手更新注释为“左侧栏”，保持术语一致。 |
| **版块图标映射与矢量资源**（参考旧版 [HiUtils.java:84-106](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/utils/HiUtils.java#L84-L106)） | 新代码尚无 21 个版块的 Compose 图标或短标定义，且未引 `material-icons-extended`。 | `C4c` 渲染 `HiNavigationSidebar` 版块列表与 `PreviewActivity` 示例时即需消费统一的版块图标/短标纯映射，报告未指定放在哪个包、哪张卡创建。 |

### 4.3 卡片拆分合理性评估
- **合理之处**：拆分为 **`C4c`（外壳侧栏升级）→ 解锁 `E1` / `E3` / `E8–E12` 并行**，且将各业务页的三栏胶水逻辑并入既有 `E1`、`E6`、`E8–E15` 而不另拆碎卡，完全符合 `DISPATCH.md §5` 第一性原理（每张卡省约 45 分钟固定开销，且 `ui/shell/` 与 `feature/` 目录不重叠）。
- **需修正的边界问题**：
  1. **跨线文件授权**：`data/settings/*` 属 D 线目录，建议在派发 `C4c`（或 `E3`，二选一唯一写入者，遵守 `lesson.md:27` 测试文件不重叠规则）时显式授权顺带补齐 `sidebarCollapsedPortrait/Landscape`（生产改动约 25 行，不值得单开 D 卡）。
  2. **版块元数据归属**：21 个内置版块的静态元数据（`fid`、名称、短标/矢量图标资源、未知 `fid` 回退规则）应明确放在 `C4c`（或 `:core-parser` / `ui/shell/` 共享小模型）一次性建好，避免 `C4c` 与 `E1` 各写一套。
  3. **`Snackbar` 宽屏对齐约定**：[HiSnackbarHost.kt:77-83](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/feedback/HiSnackbarHost.kt#L77-L83) 目前全屏底部居中（`maxWidth = 600dp`），三栏下会横跨中右分隔线且与右栏底部工具条高度耦合；需在 §5/§6.3 与 `E3` 中明确三栏下 Snackbar 是居中于右栏/内容区还是整窗，并传入正确的 `bottomClearance`。

---

## 五、阻断项与建议项清单汇总

### 阻断项（修复后再合入设计稿 / 派发 `C4c`）
- **B1（§4.1 L76/L82/L100 vs §10.2 L460）**：铰链双栏允许单侧 `260dp`（`W=520–719dp` 半折时右栏仅 `260–359dp`，装不下 `312–336dp` 的右栏工具条），与 §4.1 L82“任何三栏/双栏下右栏不可跌破 `360dp`”及 §10.2 L460“铰链分隔下断言右栏恒 `≥360dp`（且未排除无右栏的单栏档）”直接矛盾；另缺单侧 `<260dp` 退回单栏时的导航形态规则。
- **B2（§4.2 L136–140/L148–158、§4.3 L160–171、§10.2 L461）**：铰链双栏下 `浏览历史/我的帖子/收藏与关注` 两栏被 `我的菜单 | 子列表` 占满，未定义帖子详情落点，且 §4.3 漏掉“展平三栏 ↔ 半折铰链双栏”迁移规则，半折时会丢失右栏正在阅读的帖子与输入态；§4.2 返回栈漏掉 `600–679dp` 图标轨单栏切左栏的栈替换规则。
- **B3（§5 L198 vs L205、§6.3 L243 vs L248、`tech-plan.md` / `backlog.md`）**：§5 左栏底色 `surfaceContainer` 与顶部固定行未滚动 `surface` 色块割裂；§6.3“切换版块”同时匹配无位移 `effectsDefault`（L243）与 8dp 位移 `spatialDefault`（L248）；L104/L435 引用 `tech-plan.md` §7/§8 但未同步更新 `tech-plan.md` 与 `backlog.md`。
- **B4（§3.2 L43–58、§4.1 L78/L79/L87）**：Fold8 实测横展 `749 × 559dp` 下左栏 `9×48dp` 功能项 + 顶底固定区 + 系统栏 `> 559dp`，首屏看不到任何关注版块；`64dp` 轨版块图标依赖待删 FontAwesome、存在撞车且无未知版块短标/首字回退；“自左栏右滑”与系统左缘预测性返回手势冲突。

### 建议项
- **S1（§4.1 / §10.2）**：明确 `calculateShellPaneLayout` 的取整顺序（如 `L`、`M` 先 `roundToInt` dp 或转 px 后令 `R = W − L − M`），消除 `840dp`（`265.6/398.4`）和 `1024dp`（独立四舍五入和为 `1025`）的单测精度歧义。
- **S2（§3.1 L37、§3.2 L67、§4.2 L143、`copy.md` L31/L53）**：固定窄→宽“我的”默认高亮顺序（去掉“或”），统一 `600–679dp` 完整编辑器为覆盖全部栏居中，补齐 `copy.md` 搜索右栏占位/未关注版块引导文案并更新 L31“双栏”残留措辞。
- **S3（§4.2 / 中栏排版）**：规定中栏宽 `< 300dp`（如 `680–749dp` 紧凑三栏的 `256–274dp`）时列表项不设独立左侧大头像列，改用紧凑副信息行头像，保障大字号下标题每行字数。
- **S4（§4.1 / §10.1 无障碍）**：为中右拖拽分隔线补 `CustomAccessibilityAction` 与键盘切换档位，要求 `240dp` 浮层锁焦并支持 `Escape` 关闭、`64dp` 图标轨 `contentDescription` 含未读数、三栏设 `isTraversalGroup` 与 `paneTitle`。
- **S5（影响清单与拆卡）**：补入已合并的 `AppSettings` / `DataStoreSettingsRepository`（左栏横竖收起偏好持久化）、`HiShellScrollScreenshotTest.kt`（补侧栏滚底截图）、`HiShellScreenshotTest.kt:208`（侧栏态 0 块毛玻璃断言）及 21 版块矢量/短标映射归属，并在 `C4c`/`E3` 卡中明确优先复用标准组件与 `ModalNavigationDrawer`/`AnchoredDraggable`、严禁无必要手写 `Layout`。

---

## 六、复审报告（修订轮 1：`71b20fd..65a1ea5`）

- **复审结论**：**APPROVE**（评审对象：`origin/design/three-pane` @ `65a1ea5`）
- **复审范围**：`git diff 71b20fd..65a1ea5 -- docs/design`（涉及 [product-design.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/product-design.md)、[copy.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/copy.md)、[tech-plan.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/tech-plan.md)、[backlog.md](file:///home/mir/Documents/hipda-next.wt/D-3pane/docs/design/backlog.md)，共 4 文件 `+124 / -88`）及 [D-3pane.md (report)](file:///home/mir/Documents/hipda-next.wt/_reports/D-3pane.md) 第 4–7 节更新。

### 6.1 逐档重新代入演算（按 `65a1ea5` 整数取整与新铰链规则）

规则链：`H < 480`（矮屏单栏）→ `isSeparating`（`leftW ≥ 280 && rightW ≥ 360` 启铰链双栏，否则退单栏）→ `W < 600`（窄屏单栏）→ `600–679`（图标轨单栏）→ `680–839`（紧凑三栏 `L=64`）→ `≥ 840`（完整三栏 `L=clamp(0.2W,176,220).roundToInt()` 或收起 `64`），中右按 `S = W − L`、`M = clamp(0.4S,256,380).roundToInt()`、`R = S − M`、`M_bal = min(0.5S, S−360, 480).roundToInt()` 计算：

| 代入条件 (`W` dp) | 命中档位 | `L \| M \| R` (dp) | `L+M+R == W` | 吸附档位（右栏有内容） | 复审验证结论 |
|---|---|---|---|---|---|
| **`559`**（Fold8 竖 `559×749`） | 窄屏单栏 | `0 \| 单栏 559 \| 0` | — | — | 单栏 + 底部胶囊（`≤420dp`），`R=0` 符合 §10.2 |
| **`600`** | 图标轨单栏 | `64 \| 单栏 536 \| 0` | `64+536=600` | — | 599→600 无振荡，`R=0` 符合 §10.2 |
| **`679`** | 图标轨单栏 | `64 \| 单栏 615 \| 0` | `64+615=679` | — | 680 左侧极值正常，`R=0` |
| **`680`** | 紧凑三栏 | `64 \| 256 \| 360` | `680 == 680` | `[0, 256]`（`M_bal=256` 合并） | 左栏宽计入门槛，`R=360 ≥ 360` 严丝合缝 |
| **`749`**（Fold8 横 `749×559`） | 紧凑三栏 | `64 \| 274 \| 411` | `749 == 749` | `[0, 274, 325]`（均衡 `R=360`） | 横向与纵向（`559−48−96−249=166dp ≥ 3×48dp` 首屏 3 版块）均成立 |
| **`839`** | 紧凑三栏 | `64 \| 310 \| 465` | `839 == 839` | `[0, 310, 388]`（均衡 `R=387`） | 840 左侧极值正常，`R ≥ 360` |
| **`840`**（展开 / 收起） | 完整三栏 | 展开 `176 \| 266 \| 398`<br>收起 `64 \| 310 \| 466` | `840 == 840`<br>`840 == 840` | 展开 `[0, 266, 304]`<br>收起 `[0, 310, 388]` | 整数闭合消除 `265.6/398.4` 歧义；`R ≥ 398 ≥ 360` |
| **`900`**（展开） | 完整三栏 | `180 \| 288 \| 432` | `900 == 900` | `[0, 288, 360]` | 精确闭合（顺验 `1024dp`：`205+328+491=1024` 消除 `1025` 误差） |
| **`1280`**（展开） | 完整三栏 | `220 \| 380 \| 680` | `1280 == 1280` | `[0, 380, 480]` | `L/M` 双封顶，`R=680` 恰等于最大行宽 |
| **铰链分隔：`749dp` / `860dp`**（居中折痕 `0–12dp`） | 铰链双栏 | `leftW ≈ 369–430` \| `rightW ≈ 369–430` | 对齐 `hingeBounds` | 固定铰链 + `[0]` | 满足 `leftW ≥ 280 && rightW ≥ 360`：左栏容下 `1.25×` 紧凑胶囊，右栏 `≥ 360` |
| **铰链分隔：`701dp` / `639dp` / `559dp` / `260\|440dp`** | 铰链退回单栏 | 单栏 `W`（`R=0`） | — | — | `rightW < 360` 或 `leftW < 280` 退单栏；`701/639` 胶囊置左半屏、`260\|440` 置右宽侧、`559`（宽侧 `279.5<280`）整窗居中，完全自洽 |

### 6.2 四项阻断闭合核查
1. **B1 已闭合**：铰链双栏门槛收紧至 `leftW ≥ 280dp && rightW ≥ 360dp`，补齐窄右栏+大字号紧凑工具条态（图标回复 + `{N}/{M}` + 更多）与退单栏胶囊落点规则，§10.2 单测按单栏（`rightPaneWidth == 0dp`）与多栏（`≥ 360dp`）分流断言，逻辑与测试契约零冲突。
2. **B2 已闭合**：§4.2 明确铰链双栏下 `浏览历史/我的帖子/我的回复/收藏与关注` 选帖或半折带入帖子时左栏晋升为子列表（带返回 `我的` 箭头）、右栏保活帖子详情，按返回退回 `左：我的菜单 | 右：原副列表`；§4.3 与 §10.2 补齐展平三栏 ↔ 半折铰链双栏双向无损迁移；补齐 `600–679dp` 图标轨单栏切左栏清栈与 3 级返回栈。
3. **B3 已闭合**：§5 左栏主体与未滚动顶部固定区统一为 `surfaceContainerLow`（对照 [HiColors.kt:166-167, 205-206, 221-222](file:///home/mir/Documents/hipda-next/hipda/src/main/java/net/jejer/hipda/ui/designsystem/HiColors.kt#L166-L222)，浅色 `96`、深色 `10`、纯黑 `4` 均与内容区 `surface` 及滚动态 `surfaceContainer` 阶梯清晰无割裂）；§6.3 切换版块统一走 `内容替换`（下方 8dp 移入），`同级换内容` 仅限右栏换详情；`tech-plan.md`（未触碰 §6 UA）与 `backlog.md` 已完整同步。
4. **B4 已闭合**：左栏上方收拢为 5 行（可折叠 `我的` 在 `H < 760dp` 默认收起），Fold8 `749 × 559dp` 首屏余约 `166dp` 可完整显示 3 个关注版块；弃用旧 FontAwesome，在 `C4c` 建 21 版块静态元数据表，收起轨用 `18dp` 图标 + `10sp` 短标并在无图标/同图标时回退 `28×28dp` 圆角短标/首字徽标；彻底移除左缘右滑唤出，仅由按钮/`我的` 触发 `240dp` 浮层。

### 6.3 建议项与新冲突核查
- **S1–S5 全部落实**：整数取整顺序、确定性映射与 `copy.md` 新文案（已核对全部符合 §8 术语表无禁用词组）、`<300dp` 窄中栏排版、无障碍/键盘动作/焦点锁定/`paneTitle`、影响清单补漏（`AppSettings`、`HiShellScrollScreenshotTest`、`HiShellScreenshotTest:204-208`、`HiTypography`、`HiSnackbarHost`、`ForumMetadata`）与优先复用标准组件条款均已到位，未引入任何新的章节冲突。
- **给 `C4c` 实现卡的一条非阻断落地提示**：`LocalWindowInfo.current.containerDpSize.width.value` 为 `Float`（如真机 `748.59f`、`851.69f`），`calculateShellPaneLayout` 入口在完成 `< 600f / < 680f / < 840f` 分档判定后，先对窗口宽做一次 `val w = widthDp.roundToInt()` 再执行 `L → S = w − L → M → R = S − M` 整数运算，可保证真机非整数 dp 窗口下同样严格满足 `L + M + R == w`。
