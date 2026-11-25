# 任务拆分与并行计划（v0.3）

> 状态：v0.3，已按 Carbon 两轮评审（T1–T17）及 2026-11-25 大屏三栏定稿（D-3pane）同步修订。定稿后，每一行展开成一张完整的任务卡（模板见 team-lead skill）。

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
- 共享文件（`gradle/libs.versions.toml`、各模块的 `build.gradle.kts`、`settings.gradle.kts`、`HiApplication`、`src/main/AndroidManifest.xml`）只由 M0-2 和 M7 的卡修改；其他卡确实需要改时，在卡片里写明，并且不和其他同样要改的卡同时进行（目前只有 A5 需要在主 Manifest 里注册 `ChallengeActivity`）。debug 专用的页面（预览入口、样本导出工具页）注册在 `src/debug/AndroidManifest.xml`，不进 release 包。
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
| C3 | C | 毛玻璃表面组件和 4 种降级（降级分支和对比度用截图测试） | C1 |
| C4a | C | 自适应外壳：按宽度和高度在底部胶囊和导航轨之间切换；选中胶囊的移动 | C2、C3 |
| C4b | C | 导航随滚动隐藏和出现；内容延伸到导航下方 | C4a |
| C4c | C | 外壳三栏升级：600/680/840dp 断点与 `calculateShellPaneLayout`（整数取整、铰链 `≥280/360dp`）；`HiNavigationSidebar` 取代 `HiNavigationRail`（`64dp` 图标轨 / `240dp` 浮层展开[按钮/`我的` 触发、锁焦、`Escape`] / `176–220dp` 常驻展开、可折叠 `我的` 分组、`surfaceContainerLow`、21 版块静态矢量/短标映射与撞车/未知回退）；经跨线授权同步补 `data/settings/` 的 `sidebarCollapsedPortrait/Landscape` 偏好；更新外壳测试与 6 宽截图基线。优先使用 Compose / Material 3 现成组件，确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C4b、C5b |
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
| E1 | E | 论坛页：版块标签行（`≥ 600dp` 侧栏态隐藏，由左栏驱动）与帖子列表（中栏 `< 300dp` 采用紧凑无独立大头像列排版） | C4c、C5a、D4 |
| E2a | E | 帖子详情：楼层列表、楼层头部、Compose 原生正文（表情、链接、跳楼） | C5a、C1b、D5 |
| E2b | E | 帖子里的图片：预先占位、淡入、不跳动、点击进入看图 | E2a |
| E2c | E | 引用展开、楼层高亮、长按菜单、1 楼投票的展示和提交 | E2b |
| E3 | E | 三栏内容容器（中右双栏）、拖拽吸附与无障碍/键盘动作、全屏阅读、竖向物理铰链双栏（`≥ 280/360dp`，不足退单栏）、三栏/2 栏返回栈、跨形态及展平三栏↔半折双栏无损迁移、多栏 Snackbar 对齐；优先使用 Compose / Material 3 现成组件（如 `Row` + `AnchoredDraggable` 或 `ListDetailPaneScaffold`），确需自写 `Layout`/`Modifier.Node` 时须说明理由 | C4c、E1、E2a（改的是多栏内容容器，不改详情内部，可以和 E2b、E2c 并行） |
| E4a | E | 阅读位置记忆 | E2c、D1 |
| E4b | E | 底部工具条的页码、跳页面板、跳到被引用的楼层 | E4a |
| E5 | E | 快速回复输入条（和键盘同步移动、冷却倒计时） | E4b、D6 |
| E6 | E | 完整编辑器、图片上传条、草稿 | E5 |
| E7 | E | 表情选择面板（用 C1b 的映射）；和键盘互换的动效 | E6 |
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
| M7-4 | — | 收紧 R8 规则；Baseline Profile | M7-2 |
| M7-5 | — | 全新安装的回归测试和真机性能验收（需要用户的设备） | M7-3、M7-4 |

页面卡（E 线）在定稿时按“布局和状态”“交互”“动效”继续检查大小，超过拆分规则的再拆。

## 并行安排
- M0-2 完成后，第一批同时开始：A1、B1a、C1、C1b、D1、D3a（目录互不重叠）。
- A 线和 B 线前半段是串行的：B1b 要等 A6，因为抓取登录后的页面需要通过验证。
- C 线和 D 线不依赖网络，可以一直和 A 线并行。
- E 线：详情页一条串行链（E2a → E2b → E2c → E4a → E4b → E5 → E6 → E7）；其余页面（E1、E3、E8–E16）在依赖满足后并行。

## 用户要做的事
- B1b 之后，一次做完：在 debug 工具页里登录 → 确认能抓到版块页（这也是 M1 的验收）→ 按清单一键导出样本。
- 有真机折叠屏的话：M4、M5、M7 的阶段验收时装测试版，看展开和折叠，并测帧率。

## CI 用量
并行时推送会比较多。每张卡只在提交完一个阶段性结果时推送；同一分支有新推送时，自动取消旧的运行。
