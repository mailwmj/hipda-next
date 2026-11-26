# 任务拆分与并行计划（v0.4）

> 状态：v0.4，已按 Carbon 两轮评审（T1–T17）、2026-11-25 大屏三栏定稿（D-3pane）及 2026-11-25 液态玻璃与 kudo 交互定稿（D-kudo，含 R-Dkudo 审查修订）同步修订。定稿后，每一行展开成一张完整的任务卡（模板见 team-lead skill）。

## 拆分规则（按改动量和可验证性，不按时间）
- 一张卡只交付一个行为，有 2–5 条验收。
- 拆卡按第一性原理（判定方式、是否解锁并行、未知领域、审查状态量），不按行数（2026-11-25 修订，见 DISPATCH §5）。
- 预计 1–3 轮 CI 就能变绿；超过 3 轮还没变绿，说明卡的范围没写清楚，要重新切。
- 纯搬代码的卡可以超过行数上限，但不能顺手改逻辑。审查时用 `git diff --color-moved` 确认只是搬动。
- 也不要切得太碎：每张卡都有约 20–30 分钟的固定开销（开新会话、读文档、跑 CI、测试、审查）。不足约 80 行、又和相邻的卡改同一批文件的，就合并成一张。
- 界面卡按状态和行为来切，不按页面切：同一个页面的布局、各种状态、动效，可以分成几张卡依次完成。
- 实现交给 Neon xhigh；高风险卡（手势、滚动锚定、玻璃拓扑、编辑器/键盘联动、跨卡共享滚动或状态、安全相关）走 `R + V`（审查 Carbon high + 独立探针 Hydrogen xhigh），普通列表/表单页只走 `R`（Carbon 顺带目视新增截图），建议级小修由 OPS fixcheck 核对（见 `DISPATCH.md` §5）。
- 并行的前提是文件不重叠：同时进行的卡，改动范围不能有交集。每条开发线有自己负责的目录，如下表。
- 实现者并发上限为 **5**（同时运行的代理含只读审查/验证最多 8 个），本地编译按 `DISPATCH.md` §2 串行。
- 后面的卡从前面那张卡的分支开始做，不必等它合并。
- 共享文件（`gradle/libs.versions.toml`、各模块的 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`hipda/proguard-rules.txt`）只由 M0-2 和 M7 的卡修改；其他卡确实需要改时，在卡片里写明，并且不和其他同样要改的卡同时进行（目前为 `A5` 在主 Manifest 注册 `ChallengeActivity`，以及玻璃替换卡 `G2` 修改 `libs.versions.toml`、`hipda/build.gradle.kts` 与 `hipda/proguard-rules.txt`）。debug 专用的页面（预览入口、样本导出工具页）注册在 `src/debug/AndroidManifest.xml`，不进 release 包。
- 界面字符串放在 `res/values/strings_<功能>.xml`，每个功能一个文件。

## 开发线与负责的目录
| 开发线 | 负责的目录（只改这些） |
|---|---|
| A 网络与验证 | `okhttp/`、`data/network/`、`ui/challenge/` |
| B 解析与样本 | `src/test/`（解析器测试和样本）、`:core-parser`、`src/debug/` 下的样本导出工具页 |
| C 设计系统与外壳 | `ui/designsystem/`、`ui/shell/`、`src/debug/` 下的预览入口、`:emoji` 里新增的纯映射文件（旧的 Java 类不动） |
| D 数据与存储 | `data/`（不含 network）、`db/`、`di/`、`service/` |
| E 页面 | `feature/<页面>/`、`res/values/strings_<页面>.xml` |

## 卡片列表
| 卡号 | 开发线 | 结果 | 依赖 |
|---|---|---|---|
| M0-1 | — | 构建链升级（targetSdk 保持 30），建立 CI | — |
| M0-2 | — | 包名改为 `net.jejer.hipda.next`（含 `shortcuts.xml`、provider authority 等写死包名的地方）；依赖目录一次配齐方案中的全部依赖和插件；建空的 `:core-parser` 模块；接入 Hilt（`@HiltAndroidApp`，旧代码不动）；统一的 Tink AEAD 提供者（A2 和 D3c 共用，加密文件名统一前缀）；主 Manifest 加上 `android:dataExtractionRules` 和 `android:fullBackupContent` 指向两个规则文件；建好各开发线的空目录、`src/debug/AndroidManifest.xml` 和 `strings_<功能>.xml` 的约定 | M0-1 |
| A1 | A | OkHttp 5 客户端：GBK 和 GB18030 兼容解码、GBK 表单编码、Unicode Emoji 转十进制实体 | M0-2 |
| A2 | A | `SiteCookieStore`（持久化、加密存储登录 Cookie） | A1 |
| A3 | A | 与 Chrome 一致的底层传输（`cronet-okhttp`）和拦截器链：手动重定向在外层（POST 改 GET、跨域去掉敏感头、最多 16 跳），Cookie 拦截器在内层 | A2 |
| A4 | A | 验证页检测；同一时间只验证一次，其余排队（在协程层，不占连接）；失败冷却；恢复请求用独立的 Dispatcher；后台任务遇到验证直接放弃 | A3 |
| A5 | A | 透明的 `ChallengeActivity`：不可见的 WebView 自动通过验证；Cookie 同步；UA 统一 | A4 |
| A6 | A | 需要人工操作时的验证面板（小于 600dp 用底部面板，600dp 及以上用居中对话框）；后台等待超过 2 秒时显示“正在检查安全环境…” | A5 |
| B1a | B | HTML 脱敏器（`#umenu`、`discuz_uid`、`formhash`、`hash`、短消息内容和 UID），附扫描全部样本目录的脱敏测试；纯离线，用合成的 HTML 测试 | M0-2 |
| B1b | B | debug 样本导出工具页：内置 WebView 登录（打开论坛登录页，登录后用 A5 的 Cookie 同步把 `cdb_auth`、`cdb_sid`、`cf_clearance` 同步进 `SiteCookieStore`，不依赖任何登录解析器）；按清单批量抓取页面，经 B1a 脱敏后导出；给用户的操作清单 | B1a、A6 |
| B2 | B | 帖子列表解析器的样本回归测试（Robolectric，测现有代码） | B1b + 用户导出的样本 |
| B3 | B | 帖子详情解析器的样本回归测试 | 同上 |
| B4 | B | 短消息和提醒的解析测试 | 同上 |
| B5 | B | 搜索、简单列表、用户信息的解析测试 | 同上 |
| B6 | B | 发帖表单（`PrePostAsyncTask`）、登录、收藏的解析测试 | 同上 |
| B7 | B | 在 `:core-parser` 里用 Kotlin 写新的帖子列表解析器和不可变模型，不依赖 Android 和设置；用 B2 的同一批样本断言新旧输出等价。不删改 `hipda/` 下原有的 Java 解析器和 Bean（M7-2 时随旧代码一起删） | B2 |
| B8 | B | 同 B7 的做法：新的帖子详情解析器（含内容块和 1 楼投票），用 B3 的样本断言等价 | B3 |
| B9 | B | 同 B7 的做法：其余解析（短消息、搜索、用户信息、发帖表单、登录、收藏、通知、上传）的新实现，用 B4–B6 的样本断言等价 | B4–B6 |
| C1 | C | 预览入口（注册在 debug Manifest）；主题：颜色、字体、形状令牌，深浅色和纯黑；Roborazzi 基础设施 | M0-2 |
| C1b | C | `:emoji` 里新增无状态的表情映射：表情代码 → 深浅色两套 Drawable 资源（新增文件，不改旧的 `EmojiHandler`） | M0-2 |
| C2 | C | 动效令牌和“不得直接写时长和曲线”的扫描测试；逐帧测试、“减少动态效果”测试、重组次数断言的辅助工具 | C1 |
| C3 | C | 毛玻璃表面组件初版（Haze 2.x）和 4 种降级（降级分支和对比度用截图测试，已完成） | C1 |
| G2 | C | **玻璃引擎替换（Haze → Kyant backdrop，已派发）**：经跨线授权独占修改 `libs.versions.toml`、`hipda/build.gradle.kts`（移除 Haze，接入 `io.github.kyant0:backdrop:2.0.1` + `shapes:1.2.1`）、`hipda/proguard-rules.txt`（按实测补 `com.kyant.backdrop`/`shapes` 最小 keep 规则）及 `hipda/src/main/assets/license.txt` Apache-2.0 致谢；在既有 `HiGlassSurface`/`HiGlassState`/`Modifier.hiGlassSource` 门面下实现 API 33+ 液态玻璃（模糊 + `vibrancy()`/`colorControls()` + `lens()` 折射）、API 31–32 纯模糊（关 `lens()`）、API ≤ 30 与 4 路 `GlassEnvironment` 94% 纯色降级（释放离屏录制）；交付 `Navigation`/`Toolbar` 两档折射令牌与 `HiGlassBackdropConfig` 单测，同屏上限 `MaxGlassSurfacesOnScreen` 升至 `3`（`HiGlassMarker` 区域断言），校验 94% 降级态全 20 预设极端黑白对比度 ≥ 4.5:1；适配 debug 调参面板与重录受影响截图基线；加跑一次 CI `emulator=true` release 冒烟 | C3、C4c |
| C4a | C | 自适应外壳：按宽度和高度在底部胶囊和导航轨之间切换；选中胶囊的移动 | C2、C3 |
| C4b | C | 导航随滚动隐藏和出现；内容延伸到导航下方 | C4a |
| C4c | C | 外壳三栏升级：600/680/840dp 断点与 `calculateShellPaneLayout`（整数取整、铰链 `≥280/360dp`）；`HiNavigationSidebar` 取代 `HiNavigationRail`（`64dp` 图标轨 / `240dp` 浮层展开[按钮/`我的` 触发、锁焦、`Escape`] / `176–220dp` 常驻展开、可折叠 `我的` 分组、`surfaceContainerLow`、21 版块静态矢量/短标映射与撞车/未知回退）；经跨线授权同步补 `data/settings/` 的 `sidebarCollapsedPortrait/Landscape` 偏好；更新外壳测试与 6 宽截图基线。优先使用 Compose / Material 3 现成组件，确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C4b、C5b |
| C4d | C | **外壳微调与玻璃预设/拓扑补齐**：(1) 补齐 `HiGlassStyle.TopBar` 与 `HiGlassStyle.Sheet` 两档预设（含调整后的着色范围、标准主题背景全 20 预设 WCAG ≥ 4.5:1 单测与荧光笔/进度填充峰值 alpha 令牌校验），为 `HiGlassSurface` 支持复合区域单 `HiGlassMarker` 标记（使三段工具条整块计 1 个 `HiGlassMarker`、`assertGlassSurfaceCountAtMost(3)` 直接通行）；(2) 改造 `HiAdaptiveShell` 的 `hiGlassSource` 挂载位置（下放 `shellGlassState` 至页面纯滚动列表体或提供同级 `topOverlay` 槽位，根除单栏玻璃顶栏与外层 `ContentContainer` 的循环采样崩溃）；(3) 窄屏/矮屏/铰链底部胶囊保持 4 项（不加 `+`），新增横向跟手拖拽预选（超 `horizontalTouchSlop` 触发、`snapTo` 实时高亮 `previewIndex`、松手 `spatialFast` 吸附切页、竖向离胶囊边界 `> 48dp` 或取消 `spatialSnap` 弹回、`< 452dp` 左缘 `16dp` 局部防系统返回、API 33+ 拖动镜片微折射不增计 `HiGlassMarker`、TalkBack 探索兼容、减少动态效果 0ms 落位）；更新手势逐帧/无障碍单测与截图基线 | G2、C4c |
| C5a | C | 状态组件：空、错误、骨架屏，以及加载、完成、出错之间的原地切换 | C2 |
| C5b | C | 反馈组件：可撤销的 Snackbar、数字滚动、图标切换、按压反馈和触感 | C2 |
| D1 | D | Room：草稿表（业务键 `reply:<tid>`、`new:<fid>`、`edit:<pid>`）、浏览历史表、阅读位置表（全新结构，不迁移旧数据） | M0-2 |
| D2 | D | `AuthRepository`：表单登录（密码按旧版规则转义后做 MD5）、多账号切换、登录失效后静默重新登录一次、手动退出后不再自动登录 | A4、B9、D3c |
| D3a | D | DataStore：全部设置项（清单见产品设计 §7.10） | M0-2 |
| D3c | D | 账号凭据用 Tink 加密存储（用 M0-2 的 AEAD 提供者）；`backup_config.xml` 和 `data_extraction_rules.xml` 都排除凭据和 A2 的 Cookie 加密文件 | D3a |
| D3d | D | 搜索历史和收藏缓存的存储 | D3a |
| D4 | D | 帖子列表的 Repository（已读状态、置顶和黑名单过滤、不足一屏时自动补页） | A4、D2、B7、D1、D3a |
| D5 | D | 帖子详情的 Repository（写入浏览历史；1 楼投票的提交） | A4、D2、B8、D1 |
| D6 | D | 发帖、回复和上传的 Repository（草稿、小尾巴设置、30 秒冷却） | A4、D2、B9、D1、D3a |
| D7 | D | 短消息和提醒的 Repository（含 15 秒冷却） | A4、D2、B9 |
| D8a | D | 搜索、收藏和关注的 Repository（搜索历史、收藏缓存） | A4、D2、B9、D3d |
| D8b | D | 用户资料和黑名单的 Repository | A4、D2、B9、D3a |
| D9 | D | 提醒轮询（WorkManager，免打扰时段，遇到验证直接放弃） | D7、A4 |
| E1 | E | 论坛页：透明液态玻璃吸顶顶栏（复用 `C4d` 提供的 `HiGlassStyle.TopBar` 与外壳无环兄弟采样拓扑；`< 600dp`/矮屏向下滚动仅收起上行标题/`发新帖`/`更多`，**版块标签行吸顶不隐藏**，纯列表体挂 `hiGlassSource` 且顶部固定留白；`BalancedTabRow` 自适应排布、主题色横向渐变荧光笔选中高光[遵循峰值 alpha 上限，严禁用 kudo `#FFB003`]、`48dp` 触控区；`发新帖` 留在论坛顶栏；`≥ 600dp` 侧栏态隐藏版块标签行、中栏顶栏启用透明液态玻璃；铰链双栏左列表顶栏保持纯色守 3 区域上限）与帖子列表（中栏 `< 300dp` 采用紧凑无独立大头像列排版） | C4d、C5a、D4 |
| E2a | E | 帖子详情：透明液态玻璃极简顶栏（1 个 `TopBar` 玻璃区域）+ 独立轻量半透明纯色圆形动作按钮（`40dp` 视觉圆钮包裹 `48×48dp` 触控区，**严禁嵌套 `HiGlassSurface`**；左 `返回` + 标题/楼主简讯，右 `只看楼主` 快捷切换，楼层 `LazyColumn` 挂 `detailGlassState` 为严格兄弟节点并做顶部留白，不含 kudo 关注作者按钮）、楼层列表、楼层头部、Compose 原生正文（表情、链接、跳楼） | C4d、C5a、C1b、D5 |
| E2b | E | 帖子里的图片：预先占位、淡入、不跳动、点击进入看图 | E2a |
| E2c | E | 引用展开、楼层高亮、长按菜单、1 楼投票的展示和提交 | E2b |
| E3 | E | 三栏内容容器（中右双栏）、拖拽吸附与无障碍/键盘动作、详情透明顶栏轻量纯色圆形 `全屏阅读 / 显示列表` 按钮适配、三栏 3 区域实时液态玻璃协同（中栏顶栏 + 右栏顶栏 + 右栏底部三段工具条，`HiGlassMarker ≤ 3`、独立采样源 `≤ 2`、静止 `drawBackdrop ≤ 5`）与铰链双栏左列表顶栏纯色守上限、右栏底部浮层（三段工具条/内联跳页条/毛玻璃快速回复面板）严格限定在右栏宽度内不跨分隔线、竖向物理铰链双栏（`≥ 280/360dp`，不足退单栏）、三栏/2 栏返回栈、跨形态及展平三栏↔半折双栏无损迁移、多栏 Snackbar 对齐；阶段验收含真机极端样张可读性、120Hz 帧率实测与 §5 4 级回退验证；优先使用 Compose / Material 3 现成组件（如 `Row` + `AnchoredDraggable` 或 `ListDetailPaneScaffold`），确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C4d、E1、E2a（改的是多栏内容容器，不改详情内部，可以和 E2b、E2c 并行） |
| E4（合并 E4a+E4b） | E | 阅读位置记忆、位置栈、底部三段式液态玻璃工具条与原地展开内联跳页条（**已合入 `92e1563`**，main CI `45758541824` 绿；R-E4 两轮 APPROVE `@bed7fcc`；V-E4 首轮 FAIL `probeD2` 抽屉返回被位置栈抢占 → 第 2 轮 PASS，CI `45757086073`；收口 `R-C5b` 与 `E2d` 方案 B，**M5 全部完成**） | C4d、E2c、E3、D1、D8a |
| E5 | E | 毛玻璃跟手弹出快速回复面板（**审查/验证中**，高风险走 `R + V`；复用 `C4d` 提供的 `HiGlassStyle.Sheet`、`20dp` 顶部圆角、整体 1 个 `HiGlassMarker`，复用详情 `detailGlassState` 且展开覆盖底部工具条时停绘并卸载工具条 `HiGlassMarker` 守 ≤ 3 区域；高度动态钳制 `min(300dp, 可视净高 × 60%)`，矮屏净高 `< 180dp` 自动隐藏把手/草稿条压成紧凑态不溢出；顶部 `32dp` 把手或输入框顶边超界下拉关闭自动保存 `reply:<tid>` 草稿，**收口 `R-D6 S2` 清空旧草稿显式调 `deleteDraft`**，输入框内优先多行滚动与选区拖拽；`WindowInsetsAnimation`/`imePadding()` 逐帧随键盘升降；底部仅留与旧版一致的 `插入图片`、`表情` + 30 秒冷却倒计时与 `发送`[无 `引用`/`粗体`，面板不内嵌多图条，点 `插入图片` 携文本/光标/引用展开进 `E6` 拉起选图]；右上角 `48×48dp` `展开全屏` 按钮[或上拉越阈值]一键携参进 `E6`；三栏与铰链双栏仅在右栏底部升起、暗罩仅盖右栏不遮挡左中栏） | C4d、E4、D6 |
| E6 | E | 完整编辑器（**待派**；无缝承接 `E5` 毛玻璃快速回复面板点 `展开全屏` 或 `插入图片` 传入的文本、光标与引用楼层，点 `插入图片` 时立即拉起系统选图进入多图上传条；单栏全屏，多栏按 §4.2 右栏 `< 560dp` 居中模态 / `≥ 560dp` 右栏内展开；含楼层删除与收口 `R-D6 S1/S2`、`R-D1`、`R-E16 S2` 同帖跳楼）、图片上传条、草稿 | E5 |
| E7 | E | 表情选择面板（**待派**，用 C1b 的映射）；和键盘等高原地互换的动效，同时服务 `E5` 毛玻璃快速回复面板与 `E6` 完整编辑器（底缘零位移，无键盘高度时按 §7.4 限高） | E6 |
| E-slots | E | M6 页面零耦合路由插槽（**已合入 `4b6c1f0`**）：4 个 `@BindsOptionalOf` 可选渲染器接口（`Messages`/`SearchMine`/`SettingsProfile`/`ImageViewer`）、外壳分发、旧 16 元保存态兼容、铰链设置开帖与看图兜底返回 | E3 |
| E8 | E | 短消息 + 提醒（合并原 E8+E9，基于 D7，**已合入 `0883c89`**，R-E8 两轮 APPROVE `@c65a38b`）：会话列表、气泡乐观发送/失败重发/令牌锚点撤销、15 秒冷却、单条已读提醒与“全部已读”，并收口 `R-D9 S2` 通知文案归口 `copy.md` + `strings_notification.xml`；普通页仅走 `R`（含目视截图） | C5b、C1b、D7、E-slots |
| E10 | E | 搜索 + 我的（合并原 E10+E11，基于 D8a + D1，**已合入 `eb1ace1`**，R-E10 两轮 APPROVE `@9ee39e9`）：搜索初始态（最近搜索令牌锚点撤销 + 可打断筛选面板 + `ForumLinkResolver` “打开这个链接”白名单）、我的菜单页、收藏与关注（令牌锚点撤销）、我的帖子（主题/回复）、浏览历史（单删/清空令牌撤销）；普通页仅走 `R`（含目视截图） | C5b、D8a、D1、E-slots |
| E12 | E | 设置、账号管理、黑名单、管理版块 + 用户资料（合并原 E12+E13，基于 D2/D3a/D8b，**已合入 `158ac9c`**，R-E12 两轮 APPROVE `@c4046ff`）：5 组设置（外观与阅读/论坛与发帖/通知与免打扰/网络与缓存/关于，含错误日志查看/复制/分享脱敏）、黑名单原地进度移除与令牌锚点撤销、正式账号管理页（并收口 `R-E15 S2` 删除临时账号菜单）、用户资料（`资料 \| TA 的帖子`）；普通页仅走 `R`（含目视截图） | C5a、C5b、D2、D3a、D8b、D9、E-slots、E15 |
| E14 | E | 看图全屏浮层（高风险走 `R + V`，**已合入 `1cffcd9`**，main CI `45749346160` 绿；R-E14 两轮 APPROVE `@76dc92e`；V-E14 首轮合入链路 3 FAIL → 第 2 轮 PASS，CI `45747336019`）：多图横滑、实时缩略图共享边界进退与离屏原地缩小淡出、双击/捏合缩放与竖向跟手下拉关闭、退场返回吞并与底层玻璃暂停、长图（AR>2.5）贴宽顶对齐、GIF 播放切换、收口 `R-E2b S4` 原图字节级确定进度下载与限尺寸解码、`MediaStore` 保存与分享、`http`/`https` 白名单 | E2b、E-slots |
| E15 | E | 登录（**已合入 `65e627f`**） | C5a、A6、D2 |
| M6-polish | E | M6 跨卡与各页 6 项小修（**已合入 `0eebc8a`**，R-M6-polish APPROVE `@0e186fb`）：① `R-E8 R2-S1` 撤销条顶替；② `R-E8` 跨卡 `NextAppState` 仅存短消息 `uid`；③ `R-E3 R2-S1` `detail:$tid` 20 帖 LRU 淘汰；④ `R-E12 R2-S1` 黑名单排在途移除项；⑤ `R-E10 R2-S1` 无未决令牌刷新秩表；⑥ `R-E14 R2-S1` 多图回收重返不重弹提示 | E8、E10、E12、E14 |
| E16 | E | 外部链接、桌面快捷方式、通知直达、帖子里的站内链接、鉴权附件下载与剩余路由总收口（**已合入 `6ff55a9`**，R-E16 APPROVE `@b415a6c`，V-E16 PASS，CI `45763289885`；安全相关走 `R + V`，在预览入口 `NextAppActivity` 接通；唯一 `SafeLinkPolicy` 统一全仓 5 处白名单、排他浏览器回落防自环、跨域零 Cookie 隔离并收口 `R-M6-polish S1`，**M6 全部完成**） | C4a、E1、E3、E-slots、E8、E10、E12、E14、M6-polish |
| M7-1 | — | 切换启动入口、`IntentActivity` 和快捷方式到新外壳（保留 `.ng.IntentActivity` 别名）；收口 `R-E16 S1`：改主清单时补 `<queries>`（浏览器 intent 可见性），`PostLinkDispatcher.kt:52` 增 `https://` `BROWSABLE` 探测 | E1–E16 |
| M7-2 | — | 删除旧界面、EventBus、jobqueue、MaterialDrawer、Calligraphy 等旧依赖，以及 `:emoji` 的旧 View | M7-1 |
| M7-3 | — | targetSdk 升到 36：通知权限、MediaStore 下载、全面屏 | M7-2 |
| M7-4 | — | 收紧 R8 规则（保留经冒烟验证的 `com.kyant.backdrop`/`shapes` 必要规则并复跑 release 冒烟）；Baseline Profile | M7-2 |
| M7-5 | — | 全新安装的回归测试和真机性能验收（含三栏 3 块实时液态玻璃 120Hz 帧率复验，需要用户的设备） | M7-3、M7-4 |

页面卡（E 线）在定稿时按“布局和状态”“交互”“动效”继续检查大小，超过拆分规则的再拆。

## 并行安排
- M0-2 完成后，第一批同时开始：A1、B1a、C1、C1b、D1、D3a（目录互不重叠）。
- A 线和 B 线前半段是串行的：B1b 要等 A6，因为抓取登录后的页面需要通过验证。
- C 线和 D 线不依赖网络，可以一直和 A 线并行；**`G2`（玻璃引擎替换）独占修改共享依赖与混淆文件，完成后即解锁 `C4d`（补齐 `TopBar`/`Sheet` 预设、`HiAdaptiveShell` 无环兄弟采样拓扑与底部胶囊拖拽预选），`C4d` 完成后解锁 `E1`/`E2a`/`E3`/`E4b`/`E5`**。
- E 线：
  - **M5 阅读核心全部完成**（`E0`、`E15`、`E2a`、`E1`、`E2b`、`E2d`、`E3`、`E2c`、`E4 @ 92e1563` 全合入，main CI `45758541824` 绿）；
  - **M6 页面群全部完成**（`E-slots` `4b6c1f0`、`E8` `0883c89`、`E12` `158ac9c`、`E10` `eb1ace1`、`E14` `1cffcd9`、`M6-polish` `0eebc8a`、`E16` `6ff55a9` 均已合入）；
  - **发帖链路与后续里程碑**：`E5`（毛玻璃快速回复，高风险 R+V）**审查/验证中**，`E6`（完整编辑器）、`E7`（表情面板）**待派**；之后进入 **`M7-1..5`**。

## 用户要做的事
- B1b 之后，一次做完：在 debug 工具页里登录 → 确认能抓到版块页（这也是 M1 的验收）→ 按清单一键导出样本。
- 有真机折叠屏的话：在 `G2`/M4、M5、M7 的阶段验收时装测试版，实测展开/折叠、API 33+ 液态边缘折射、极端黑白/高饱和配图样张可读性，以及三栏 3 区域实时液态玻璃的 **120Hz 滚动帧率**（不达标按 `product-design.md` §5 4 级回退顺序降级确认）。

## CI 用量
并行时推送会比较多。每张卡只在提交完一个阶段性结果时推送；同一分支有新推送时，自动取消旧的运行。
