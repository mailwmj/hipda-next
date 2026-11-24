# hipda-next 现代化技术方案（草案 v0.1）

> 状态：草案，待 Carbon 评审。来源：负责人方案与 P1 独立方案合并；版本号已经 F2 核查（P1 的 32 个版本号都存在，而且都是稳定版；需要改的几处已在本文改正），最终以 M0-1 的实测为准。决策记录见 `docs/agents/decisions.md`。

## 1. 目标
- 用户看到的结果：一个现代、流畅的 HiPDA 客户端。手机、折叠屏的外屏和内屏、平板、分屏窄窗口、深色和浅色模式下都好用。导航是毛玻璃材质；展开后是列表和详情双栏；动效细腻，有交代。
- 功能范围与现有应用一致，不扩张。只删除已经失效的功能（例如检查更新），以及可以由系统自动判断的设置项。
- 包名 `net.jejer.hipda.ng` 保持不变，老用户覆盖安装后，设置、登录、草稿和历史都保留。

## 2. 约束
- 站点 4d4y.com 前面有 Cloudflare 人机验证：非浏览器的请求会拿到 403 和 `cf-mitigated: challenge`。所以 CI 不能访问真实站点，解析器只能用离线的 HTML 样本做测试。
- 页面是 Discuz! 生成的 HTML，编码是 GBK，表单也要按 GBK 编码提交。
- 编译以 GitHub Actions 为主；本地编译串行执行，本地启动模拟器要先问用户（见 DISPATCH §2）。
- 不对论坛做自动化的写操作。

## 3. 策略：在原仓库里逐步替换（Strangler）
1. 先把构建链升级到最新稳定版，保证用户看不到任何变化（M0）。
2. 打通 Cloudflare 验证通道，让现有应用重新能访问论坛（M1）。
3. 采集 HTML 样本，给每个解析器写测试，作为后续改造的安全网（M2）。
4. 用 Kotlin 重写数据层，用 Compose 搭新的外壳、设计系统和动效系统（M3、M4）。
5. 逐屏替换旧界面（M5、M6），最后清理旧代码（M7）。

不选的方案：
- Flutter 重写：现有解析器会全部作废，不符合“基础还是当前项目”。
- 只在 View 体系上翻新：毛玻璃和自适应双栏要自己造，而且动效难以统一成一套语言。

## 4. 技术栈（版本以 M0-1 和 F2 核实为准）
| 方面 | 选型 |
|---|---|
| 构建 | AGP 9.5.2、Gradle 9.9.2（截至 2026-11-24 的最新稳定版，F2 已核实）；Kotlin DSL 加 version catalog；JDK 17 及以上 |
| SDK | minSdk 26（Compose 1.13 和同批 Jetpack 库要求至少 24）；targetSdk 36（Play 要求从 2026-08-31 起生效）；compileSdk 37.1（Compose BOM 2026.11.01 要求 minor SDK 37.1） |
| 语言 | Kotlin；旧 Java 代码逐步删除，不做逐文件翻译 |
| UI | Jetpack Compose（BOM）、Material 3（MotionScheme）、Material 3 Adaptive（`adaptive-layout` 里的 ListDetailPaneScaffold；导航外壳自己写，因为要做毛玻璃胶囊和中等宽度的双栏，不直接用 `material3-adaptive-navigation-suite`）、androidx.window |
| 导航 | Navigation 3 加 adaptive-navigation3（需 F2 确认是稳定版；不是的话改用 Navigation Compose 2.x） |
| 依赖注入 | Hilt（KSP） |
| 异步 | 协程、StateFlow、ViewModel、WorkManager（通知轮询） |
| 网络 | OkHttp 5 作为统一的应用层（拦截器、Cookie、编码）；底层传输可替换：优先用与 Chrome 一致的网络栈（`cronet-okhttp`：Android 14+ 用系统自带的 HttpEngine，其次用 Play 服务的 Cronet），都没有时退回 OkHttp 自己的传输。按 GBK 和 UTF-8 自适应解码；Cloudflare 验证通道见第 6 节 |
| 解析 | jsoup 最新稳定版，放在纯 JVM 模块 `:core-parser`，没有 Android 依赖，测试快 |
| 图片 | Coil 3（与网络层共用同一个 OkHttpClient）、Telephoto（看大图） |
| 存储 | Room（迁移旧的 content.db 和 history.db）、DataStore（迁移旧的 SharedPreferences；凭据加密） |
| 毛玻璃 | Haze 2.x（`haze-blur` 模块，API 是 `Modifier.hazeSource` 和 `Modifier.hazeBlur`；1.x 的 `hazeEffect(state, style)` 签名已经移除）：API 31 及以上用真实模糊（API 31 上，任何快照状态变化都会让 Haze 重新抓取背景，需要在 M4 实测性能，必要时只在 32 及以上开启）；API 30 及以下降级为高不透明度的 scrim。Haze 不会自动检测省电模式和系统的“降低透明度”设置，要由应用自己监听，并传入 `blurEnabled(false)`；降级后的描边和阴影也由应用自己画 |
| 测试 | JUnit、MockWebServer、Turbine、Robolectric、Roborazzi（截图）、Compose UI test（动效用 mainClock 逐帧检查）、Macrobenchmark（帧时间，在 CI 模拟器上跑，只在阶段验收时） |

## 5. 架构
- 模块：`:hipda`（应用、界面和 DI），`:core-parser`（纯 JVM 解析器和数据模型），`:emoji`（保留表情资源和映射，删除旧的 View 组件）。数据层先放在 `:hipda` 的 `data/` 包内；以后编译时间成问题时再拆成独立模块。
- 数据流：Repository 返回 Flow 或挂起函数 → ViewModel 暴露 UiState → Compose 单向数据流。一次性事件，例如“发帖成功”，也放进状态里，用户处理后清除，不用 SharedFlow 发事件。
- 新旧并存期：新界面放在单 Activity 的 Compose 外壳里，还没迁移的页面继续用旧 Activity 打开；EventBus 和 jobqueue 只给旧页面用，M7 时删除。

## 6. Cloudflare 验证通道（依据 S1 调研 `docs/design/cloudflare-research.md`，已经 F3 核查）
- 实测：论坛首页对所有非浏览器请求都返回 403 加托管验证（managed challenge）；但版块页和登录页，用浏览器请求头访问时返回 200。图片域名不受验证影响。所以验证是按路径触发的，不是整站拦截。
- 检测：以响应头 `cf-mitigated: challenge` 为准（Cloudflare 官方的检测方法）。403 一定要判断；429 也判断，这一点来自 fluxdo 的实践，官方文档没有明写；没有这个头时，只在 HTML 响应里按特征兜底。不能只因为页面里有 `challenge-platform` 就判定为验证页，因为正常页面也会被注入 Cloudflare 的检测脚本。
- 流程：检测到验证页后，先用不可见的 WebView 自动尝试通过；页面进入需要人工操作的阶段，或者超时，才从底部升起验证面板，并复用同一个 WebView，不重新加载。通过后把 Cookie 同步给网络层，原请求自动重试。
- 并发：同一时间只进行一次验证，其他请求排队等结果；验证失败后冷却 60 秒，避免反复弹出。
- UA：WebView 和网络层使用同一个 UA，取自 WebView 自己的 UA（去掉 `; wv` 标记）。
- Cookie：由应用层的 Cookie 拦截器统一管理，因为 Cronet 会绕过 OkHttp 的 CookieJar。Cronet 默认会自己跟随重定向（最多 16 次），这样中间跳转的 `Set-Cookie` 就到不了应用层，所以要关掉 Cronet 的自动重定向，由应用层逐跳处理。
- 测试：用 MockWebServer 模拟验证页，覆盖检测、排队、冷却、重试和 Cookie 同步。

## 7. 大屏与折叠屏
- 宽度断点用 Material 窗口尺寸类：紧凑（小于 600dp）、中等（600–839dp）、展开（840dp 及以上）。
- 何时分双栏由系统根据可用宽度决定，不提供开关。Material 默认的 `calculatePaneScaffoldDirective` 在中等宽度（600–839dp）只给单栏；我们用自定义的 `PaneScaffoldDirective`：宽度够放下“列表 300dp 加详情 380dp”时就分双栏（规则见产品设计 4.1）。
- 铰链：默认的 `HingePolicy.AvoidSeparating` 只在铰链把屏幕物理分开时避让，内屏展平时不避让，这正是我们想要的。
- 状态保持：选中的帖子、阅读位置（页码、楼层、偏移量）、正在输入的回复内容，都存在 SavedStateHandle 或数据库里。折叠、展开、旋转、进程被系统回收后都能恢复。
- 详细的布局和动效规范见产品设计方案（`docs/design/product-design.md`，待写）。

## 8. 分阶段与验收
| 阶段 | 结果 | 证据 |
|---|---|---|
| M0 构建链 | 用新工具链编译通过，CI 建立，用户看不到变化 | CI 运行记录；模拟器冒烟测试的截图和 logcat |
| M1 验证通道 | 现有应用能在 Cloudflare 后正常访问论坛 | MockWebServer 测试（检测验证页、并发请求排队、重试）；用户在真机或模拟器上看到版块列表的截图 |
| M2 安全网 | 每个解析器都有样本测试；样本由用户登录后用 debug 版里的工具一次性导出，并做脱敏 | 测试报告；故意改坏一个解析器，测试会失败 |
| M3 数据层 | Kotlin Repository、Hilt、Room/DataStore 迁移 | 单元测试；用旧版数据库文件做的迁移测试 |
| M4 外壳 | 主题、毛玻璃导航、自适应外壳、动效系统、设计令牌 | Roborazzi：3 种宽度 × 深浅色；动效逐帧测试；“减少动态效果”下的测试 |
| M5 阅读核心 | 版块、帖子列表、帖子详情、双栏；折叠和展开时状态保持 | 截图测试；状态恢复测试；长帖滚动的帧时间 |
| M6 其余页面 | 发帖、短消息、搜索、用户信息、设置、看图、登录 | 每页的截图测试和交互测试 |
| M7 清理 | 删除旧代码和旧依赖；targetSdk 37；Baseline Profile | 依赖清单；覆盖安装的回归测试；启动和滚动的基准数据 |

## 9. 风险
1. Cloudflare：cf_clearance 和设备、IP、UA 绑定，默认 30 分钟过期。网络指纹和浏览器不一致时，可能过了验证仍被拦，所以底层优先用与 Chrome 一致的网络栈。没有这种网络栈的旧设备（Android 13 及以下、又没有 Play 服务）风险最高，退回 OkHttp 加单次重试。网络切换导致出口 IP 变化后凭证是否失效，官方没有明说（属于工程经验）；做法是：网络切换后再遇到验证页，就重新验证，不预先清掉凭证。
2. 样本采集依赖用户登录，这一步只有用户能做。
3. 帖子正文是富文本（引用、表情、图片、投票），在 Compose 里渲染的工作量大。先用 AndroidView 包一层旧的 TextView 渲染，再逐步改写。
4. 老数据迁移：要有覆盖安装测试。
