# 任务拆分与并行计划（v0.4）

> 状态：v0.4，已按 Carbon 两轮评审（T1–T17）、2026-11-25 大屏三栏定稿（D-3pane）及 2026-11-25 液态玻璃与 kudo 交互定稿（D-kudo）同步修订。定稿后，每一行展开成一张完整的任务卡（模板见 team-lead skill）。

## 拆分规则（按改动量和可验证性，不按时间）
- 一张卡只交付一个行为，有 2–5 条验收。
- 拆卡按第一性原理（判定方式、是否解锁并行、未知领域、审查状态量），不按行数（2026-11-25 修订，见 DISPATCH §5）。
- 预计 1–3 轮 CI 就能变绿；超过 3 轮还没变绿，说明卡的范围没写清楚，要重新切。
- 纯搬代码的卡可以超过行数上限，但不能顺手改逻辑。审查时用 `git diff --color-moved` 确认只是搬动。
- 也不要切得太碎：每张卡都有约 20–30 分钟的固定开销（开新会话、读文档、跑 CI、测试、审查）。不足约 80 行、又和相邻的卡改同一批文件的，就合并成一张。
- 界面卡按状态和行为来切，不按页面切：同一个页面的布局、各种状态、动效，可以分成几张卡依次完成。
- 实现交给 Neon xhigh；跑测试和验证交给 Hydrogen xhigh，在新会话里进行；审查交给 Carbon high。
- 并行的前提是文件不重叠：同时进行的卡，改动范围不能有交集。每条开发线有自己负责的目录，如下表。
- 同时运行的代理最多 8 个。一般安排 4–5 个写代码，其余用于跑测试和审查。
- 后面的卡从前面那张卡的分支开始做，不必等它合并。
- 共享文件（`gradle/libs.versions.toml`、各模块的 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`、`hipda/proguard-rules.txt`）只由 M0-2 和 M7 的卡修改；其他卡确实需要改时，在卡片里写明，并且不和其他同样要改的卡同时进行（目前为 `A5` 在主 Manifest 注册 `ChallengeActivity`，以及玻璃替换卡 `C3b` 修改 `libs.versions.toml`、`hipda/build.gradle.kts` 与 `hipda/proguard-rules.txt`）。debug 专用的页面（预览入口、样本导出工具页）注册在 `src/debug/AndroidManifest.xml`，不进 release 包。
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
| C3b | C | **玻璃引擎替换（Haze → Kyant backdrop）**：经跨线授权独占修改 `libs.versions.toml`、`hipda/build.gradle.kts`（移除 Haze，接入 `io.github.kyant0:backdrop` + `shapes`，优先 `2.0.1/1.2.1`、遇冲突退 `1.0.6/1.2.0`）、`hipda/proguard-rules.txt`（补 `com.kyant.backdrop.**` 与 `com.kyant.shapes.**` keep 规则）及随包 Apache-2.0 致谢；在既有 `HiGlassSurface`/`HiGlassState`/`Modifier.hiGlassSource` 门面下实现 API 33+ 液态玻璃（模糊 + `vibrancy()`/`colorControls()` + AGSL 边缘折射 `lens()`）、API 31–32 纯模糊（关 `lens()`）、API ≤ 30 与 4 路 `GlassEnvironment` 94% 纯色降级；扩展四档 `HiGlassStyle`（`Navigation`/`TopBar`/`Toolbar`/`Sheet`），全屏活跃实时玻璃上限放宽至 3 块（独立采样源 ≤ 2，严格兄弟节点防采样环），校验模糊激活态与降级态对比度 ≥ 4.5:1；适配 debug 调参面板与重录玻璃/外壳截图基线；加跑一次 CI `emulator=true` release 冒烟验证 R8 零崩溃 | C3、C4c |
| C4a | C | 自适应外壳：按宽度和高度在底部胶囊和导航轨之间切换；选中胶囊的移动 | C2、C3 |
| C4b | C | 导航随滚动隐藏和出现；内容延伸到导航下方 | C4a |
| C4c | C | 外壳三栏升级：600/680/840dp 断点与 `calculateShellPaneLayout`（整数取整、铰链 `≥280/360dp`）；`HiNavigationSidebar` 取代 `HiNavigationRail`（`64dp` 图标轨 / `240dp` 浮层展开[按钮/`我的` 触发、锁焦、`Escape`] / `176–220dp` 常驻展开、可折叠 `我的` 分组、`surfaceContainerLow`、21 版块静态矢量/短标映射与撞车/未知回退）；经跨线授权同步补 `data/settings/` 的 `sidebarCollapsedPortrait/Landscape` 偏好；更新外壳测试与 6 宽截图基线。优先使用 Compose / Material 3 现成组件，确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C4b、C5b |
| C4d | C | **外壳微调（底部胶囊跟手拖拽预选）**：窄屏/矮屏/铰链底部液态玻璃胶囊保持 4 项（不加中间 `+`，发新帖留在论坛顶栏）；在 `ShellIndicatorLayout`/`HiNavigationPill` 新增横向跟手拖拽预选交互（拖动中 `snapTo` 直接跟手实时更新 `previewIndex` 预选高亮且不切页，松手以 `HiMotion.spatialFast` 吸附切页，拖离容差或取消以 `HiMotion.spatialSnap` 弹回原项；API 33+ 拖动期间镜片微放大并带边缘光学折射）；确保 TalkBack 单击探索不受拦截、减少动态效果下 0ms 落位；补齐外壳手势逐帧测试、无障碍测试与截图基线 | C3b、C4c |
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
| E1 | E | 论坛页：透明液态玻璃吸顶顶栏（`< 600dp`/矮屏向下滚动仅收起上行标题/`发新帖`/`更多`，**版块标签行吸顶不隐藏**，列表通过 `hiGlassSource` 从后滚过且顶部固定留白；采用 `BalancedTabRow` 自适应排布、主题色横向渐变荧光笔选中高光[严禁用 kudo `#FFB003`]、`48dp` 触控区、对比度 ≥ 4.5:1；`发新帖` 留在论坛顶栏；`≥ 600dp` 侧栏态隐藏版块标签行、中栏顶栏启用透明液态玻璃；铰链双栏左列表顶栏保持纯色守 3 块上限）与帖子列表（中栏 `< 300dp` 采用紧凑无独立大头像列排版） | C3b、C4c、C5a、D4 |
| E2a | E | 帖子详情：透明液态玻璃极简顶栏 + 独立圆形动作按钮（`40dp` 视觉圆钮包裹 `48×48dp` 触控区：左 `返回` + 标题/楼主简讯，右 `只看楼主` 快捷切换，正文通过 `hiGlassSource` 从顶栏后滚过并做顶部留白，严格兄弟节点防采样环，不含 kudo 关注作者按钮）、楼层列表、楼层头部、Compose 原生正文（表情、链接、跳楼） | C3b、C5a、C1b、D5 |
| E2b | E | 帖子里的图片：预先占位、淡入、不跳动、点击进入看图 | E2a |
| E2c | E | 引用展开、楼层高亮、长按菜单、1 楼投票的展示和提交 | E2b |
| E3 | E | 三栏内容容器（中右双栏）、拖拽吸附与无障碍/键盘动作、详情透明顶栏圆形 `全屏阅读 / 显示列表` 按钮适配、三栏 3 块实时液态玻璃协同（中栏顶栏 + 右栏顶栏 + 右栏底部三段工具条，独立采样源 ≤ 2）与铰链双栏左列表顶栏纯色守 3 块上限、右栏底部浮层（三段工具条/内联跳页条/毛玻璃快速回复面板）严格限定在右栏宽度内不跨分隔线、竖向物理铰链双栏（`≥ 280/360dp`，不足退单栏）、三栏/2 栏返回栈、跨形态及展平三栏↔半折双栏无损迁移、多栏 Snackbar 对齐；阶段验收含真机 120Hz 帧率实测与 §5 4 级回退验证；优先使用 Compose / Material 3 现成组件（如 `Row` + `AnchoredDraggable` 或 `ListDetailPaneScaffold`），确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C3b、C4c、E1、E2a（改的是多栏内容容器，不改详情内部，可以和 E2b、E2c 并行） |
| E4a | E | 阅读位置记忆 | E2c、D1 |
| E4b | E | 帖子详情底部三段式液态玻璃工具条（左 `回复` 胶囊、中带主题色阅读进度填充的 `第 N / M 页` 进度胶囊、右 `收藏` + `更多` 独立双 `48dp` 按钮胶囊；窄右栏 `< 384dp` + 大字号自动切紧凑态）+ 中段点按原地展开内联跳页条（滑块仅本地预览不滚屏 + 页码输入框 + `首页`/`末页` 快捷按钮 + `关闭`/`跳转` 动态按钮，`imePadding()` 贴键盘，窄右栏紧凑双行，任何跳转自动收起并压位置栈；TalkBack 自动聚焦与滑块播报、减少动态效果 0ms 到位）+ 跳到被引用的楼层 | C3b、E4a |
| E5 | E | 毛玻璃跟手弹出快速回复面板（`20dp` 顶部圆角、`Sheet` 护读玻璃档、复用详情 `layerBackdrop` 且展开覆盖底部工具条时暂停工具条实时玻璃绘制守 ≤ 3 块；顶部 `32dp` 拖动把手支持下拉关闭并自动保存 `reply:<tid>` 草稿，点暗罩/系统返回等效；`WindowInsetsAnimation`/`imePadding()` 逐帧随键盘升降；内置回复/引用提示、单行横向滚动快捷工具[`插入图片`/`表情`/`引用`/`粗体`]、冷却倒计时与 `发送`；右上角 `48×48dp` `展开全屏` 按钮[或上拉越阈值]一键携带文本/光标/引用/已选图片进 `E6`；三栏与铰链双栏仅在右栏底部升起、暗罩仅盖右栏不遮挡左中栏） | C3b、E4b、D6 |
| E6 | E | 完整编辑器（无缝承接 `E5` 毛玻璃快速回复面板 `展开全屏` 的文本、光标、引用楼层与已添加/上传中图片，单栏全屏、多栏按 §4.2 右栏 `< 560dp` 居中模态 / `≥ 560dp` 右栏内展开）、图片上传条、草稿 | E5 |
| E7 | E | 表情选择面板（用 C1b 的映射）；和键盘等高原地互换的动效，同时服务 `E5` 毛玻璃快速回复面板与 `E6` 完整编辑器（底缘零位移，无键盘高度时按 §7.4 限高） | E6 |
| E8 | E | 短消息：会话列表和对话 | C5b、C1b、D7 |
| E9 | E | 提醒 | E8 |
| E10 | E | 搜索 | C5b、D8a |
| E11 | E | 我的：收藏和关注、历史、我的帖子 | C5b、D8a、D1 |
| E12 | E | 设置、账号管理、黑名单 | C5b、D3a、D2、D8b |
| E13 | E | 用户资料 | C5a、D8b |
| E14 | E | 看图 | E2b |
| E15 | E | 登录 | C5a、A6、D2 |
| E16 | E | 外部链接、桌面快捷方式、帖子里的站内链接、附件下载（在预览入口里接通） | C4a、E1、E4b、E8、E10、E11、E13 |
| M7-1 | — | 切换启动入口、`IntentActivity` 和快捷方式到新外壳（保留 `.ng.IntentActivity` 别名） | E1–E16 |
| M7-2 | — | 删除旧界面、EventBus、jobqueue、MaterialDrawer、Calligraphy 等旧依赖，以及 `:emoji` 的旧 View | M7-1 |
| M7-3 | — | targetSdk 升到 36：通知权限、MediaStore 下载、全面屏 | M7-2 |
| M7-4 | — | 收紧 R8 规则（保留经冒烟验证的 `com.kyant.backdrop`/`shapes` 必要规则并复跑 release 冒烟）；Baseline Profile | M7-2 |
| M7-5 | — | 全新安装的回归测试和真机性能验收（含三栏 3 块实时液态玻璃 120Hz 帧率复验，需要用户的设备） | M7-3、M7-4 |

页面卡（E 线）在定稿时按“布局和状态”“交互”“动效”继续检查大小，超过拆分规则的再拆。

## 并行安排
- M0-2 完成后，第一批同时开始：A1、B1a、C1、C1b、D1、D3a（目录互不重叠）。
- A 线和 B 线前半段是串行的：B1b 要等 A6，因为抓取登录后的页面需要通过验证。
- C 线和 D 线不依赖网络，可以一直和 A 线并行；**`C3b`（玻璃引擎替换）独占修改共享依赖与混淆文件，完成后即解锁 `C4d` 与 `E1`/`E2a`/`E3`/`E4b`/`E5`**（`C4d` 聚焦底部胶囊手势，不阻塞 `E1`/`E2a` 并行）。
- E 线：详情页一条串行链（E2a → E2b → E2c → E4a → E4b → E5 → E6 → E7）；其余页面（E1、E3、E8–E16）在依赖满足后并行。

## 用户要做的事
- B1b 之后，一次做完：在 debug 工具页里登录 → 确认能抓到版块页（这也是 M1 的验收）→ 按清单一键导出样本。
- 有真机折叠屏的话：在 `C3b`/M4、M5、M7 的阶段验收时装测试版，实测展开/折叠、API 33+ 液态边缘折射、极端背景可读性，以及三栏 3 块实时液态玻璃的 **120Hz 滚动帧率**（不达标按 `product-design.md` §5 4 级回退顺序降级确认）。

## CI 用量
并行时推送会比较多。每张卡只在提交完一个阶段性结果时推送；同一分支有新推送时，自动取消旧的运行。
