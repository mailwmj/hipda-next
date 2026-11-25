# hipda-next 现代化技术方案（v0.3）

> 状态：v0.3，已按 Carbon 两轮评审（T1–T17，全部采纳）及 2026-11-25 大屏三栏定稿（D-3pane）同步修订。来源：负责人方案与 P1 独立方案合并；版本号已经 F2 核查（P1 的 32 个版本号都存在，而且都是稳定版；需要改的几处已在本文改正），最终以 M0-1 的实测为准。决策记录见 `docs/agents/decisions.md`。

## 1. 目标
- 用户看到的结果：一个现代、流畅的 HiPDA 客户端。手机、折叠屏的外屏和内屏、平板、分屏窄窗口、深色和浅色模式下都好用。窄屏底部导航和帖子详情工具条是毛玻璃材质；大屏与展开后的折叠屏采用常驻左侧栏 + 列表 + 详情三栏布局；动效细腻，有交代。
- 功能范围与现有应用一致，不扩张。只删除已经失效的功能（例如检查更新），以及可以由系统自动判断的设置项。
- 不兼容老版本和老用户（用户决定）：不做数据迁移，不要求沿用原签名，不做覆盖安装测试。
- 包名改为 `net.jejer.hipda.next`（用户决定），可以和旧版同时安装、对照使用。在 M0-2 中修改，连同 `shortcuts.xml`、provider authority 等写死包名的地方。

## 2. 约束
- 站点 4d4y.com 前面有 Cloudflare 人机验证：非浏览器的请求会拿到 403 和 `cf-mitigated: challenge`。所以 CI 不能访问真实站点，解析器只能用离线的 HTML 样本做测试。
- 页面是 Discuz! 生成的 HTML，编码是 GBK，表单也要按 GBK 编码提交。
- 编译以 GitHub Actions 为主；本地编译串行执行，本地启动模拟器要先问用户（见 DISPATCH §2）。
- 不对论坛做自动化的写操作。

## 3. 策略：在原仓库里逐步替换（Strangler）
1. 先把构建链升级到最新稳定版，保证用户看不到任何变化（M0）。
2. 打通新网络层的 Cloudflare 验证通道（M1）。
3. 采集 HTML 样本，给每个解析器写测试，作为后续改造的安全网（M2）。
4. 用 Kotlin 重写数据层，用 Compose 搭新的外壳、设计系统和动效系统（M3、M4）。
5. 逐屏替换旧界面（M5、M6），最后清理旧代码（M7）。

不选的方案：
- Flutter 重写：现有解析器会全部作废，不符合“基础还是当前项目”。
- 只在 View 体系上翻新：毛玻璃和自适应双栏要自己造，而且动效难以统一成一套语言。

## 4. 技术栈（版本以 M0-1 和 F2 核实为准）
| 方面 | 选型 |
|---|---|
| 构建 | AGP 9.5.2、Gradle 9.9.2（截至 2026-11-24 的最新稳定版，F2 已核实）；Kotlin DSL 加 version catalog；JDK 17 及以上。AGP 9 已内置 Kotlin，Android 模块不得再声明 `org.jetbrains.kotlin.android` 插件；注解处理统一用 KSP，不用 kapt |
| SDK | minSdk 26（Compose 1.13 和同批 Jetpack 库要求至少 24）；compileSdk 37.1（Compose BOM 2026.11.01 要求 minor SDK 37.1）；targetSdk 在 M0–M6 保持 30，M7 删除旧界面后升到 36（T1：提前升级会让旧页面钻进系统栏、附件下载失效、通知被静默丢弃） |
| 语言 | Kotlin；旧 Java 代码逐步删除，不做逐文件翻译 |
| UI | Jetpack Compose（BOM）、Material 3（MotionScheme）、Material 3 Adaptive（`adaptive-layout`）、androidx.window。导航外壳采用自研 `HiAdaptiveShell` + `HiNavigationSidebar`（因需支持 `< 600dp` 毛玻璃底部胶囊与 `≥ 600dp` 含关注版块列表的可收起侧栏，不直接用 `material3-adaptive-navigation-suite`）；内部与中右内容双栏优先复用 Compose / Material 3 现成组件（如 `Column`/`LazyColumn` + `ModalNavigationDrawer`/`ModalWideNavigationRail`、`Row` + `AnchoredDraggable` 或自定义 `PaneScaffoldDirective` 的 `ListDetailPaneScaffold`），确需自写 `Layout`/`Modifier.Node` 时须说明理由 |
| 导航 | Navigation 3 1.2.2 加 adaptive-navigation3（F2 已确认是稳定版） |
| 依赖注入 | Hilt（KSP） |
| 异步 | 协程、StateFlow、ViewModel、WorkManager（通知轮询） |
| 网络 | OkHttp 5 作为统一的应用层（拦截器、Cookie、编码）；底层传输可替换：优先用与 Chrome 一致的网络栈（`cronet-okhttp`：Android 14+ 用系统自带的 HttpEngine，其次用 Play 服务的 Cronet），都没有时退回 OkHttp 自己的传输。按 GBK 和 UTF-8 自适应解码；Cloudflare 验证通道见第 6 节 |
| 解析 | jsoup 最新稳定版，放在纯 JVM 模块 `:core-parser`，没有 Android 依赖，测试快 |
| 图片 | Coil 3（与网络层共用同一个 OkHttpClient）、Telephoto（看大图） |
| 存储 | Room（草稿、浏览历史、阅读位置）、DataStore（设置；凭据用 Tink 加密）。全新结构，不迁移旧数据 |
| 毛玻璃 | Haze 2.x（`haze-blur` 模块，API 是 `Modifier.hazeSource` 和 `Modifier.hazeBlur`；1.x 的 `hazeEffect(state, style)` 签名已经移除）：API 31 及以上用真实模糊（API 31 上，任何快照状态变化都会让 Haze 重新抓取背景，需要在 M4 实测性能，必要时只在 32 及以上开启）；API 30 及以下降级为高不透明度的 scrim。Haze 不会自动检测省电模式和系统的“降低透明度”设置，要由应用自己监听，并传入 `blurEnabled(false)`；降级后的描边和阴影也由应用自己画 |
| 测试 | JUnit、MockWebServer、Turbine、Robolectric、Roborazzi（截图）、Compose UI test（动效用 mainClock 逐帧检查）、Macrobenchmark（帧时间，在 CI 模拟器上跑，只在阶段验收时） |

## 5. 架构
- 模块：`:hipda`（应用、界面和 DI），`:core-parser`（纯 JVM 解析器和数据模型），`:emoji`（只保留表情资源和映射，删除旧的 View 组件）。数据层放在 `:hipda` 的 `data/` 包内。
- 数据流：Repository 返回 Flow 或挂起函数 → ViewModel 暴露 UiState → Compose 单向数据流。一次性事件（例如“发帖成功”）也放进状态里，用户处理后清除。
- **旧界面只作参考（用户决定不兼容老版本）**：旧界面保持原样，不接入新的网络层和验证通道，M7 时删除。新代码有自己的 OkHttpClient 和 `SiteCookieStore`，不读写旧的偏好和数据库。
- **新旧界面不混跳（T3）**：M4–M6 期间，新的 Compose 外壳从一个单独的“预览”入口进入（debug 构建里的第二个启动图标），旧界面的入口和跳转都不动。全部新页面完成后，在 M7 一次性切换启动入口、`IntentActivity` 和桌面快捷方式。这样就不需要 EventBus 和双写存储之类的胶水代码。
- **验证面板有独立的宿主（T3）**：用一个透明的 `ChallengeActivity`（singleTop）承载验证用的 WebView，不管前台是新界面还是旧界面，都能弹出验证面板。
- **解析器先上测试，再抽模块（T2）**：现有解析器依赖 Context、HiSettingsHelper，还会在解析时开线程发通知，不能原样搬进纯 JVM 模块。顺序是：样本导出工具（debug 构建里的工具页，内置 WebView 登录，用新的网络层和验证通道抓取页面）→ 在 `:hipda` 里对现有解析入口写样本回归测试（Robolectric）→ 在 `:core-parser` 里用 Kotlin 分批写新的解析器和不可变模型（不依赖 Android、不读写设置、没有副作用；不删改旧的 Java 解析器，M7 时一起删），并把散落在 `PrePostAsyncTask`、`LoginHelper`、`FavoriteHelper`、`PostSmsAsyncTask`、`NotiHelper`、`UploadImgHelper` 里的解析代码收拢过来。每写一批，都用同一批样本断言新旧输出等价。
- **帖子正文用 Compose 原生渲染（T6）**：解析器已经把引用、图片、跳楼、附件、投票拆成了独立的块，正文文本块只含基础的内联标签、链接和表情。直接用 `AnnotatedString` + `InlineTextContent`（表情取自 `:emoji` 的资源）+ `LinkAnnotation` 渲染，不用 AndroidView 包旧的 TextView。文字颜色的可读性判断（`TextStyle.java:49`）从解析阶段移到渲染阶段，切换深浅色后才不会错色。
- **共享文件的改动集中在一张卡里（T7）**：M0-2 一次性把方案确定的全部依赖和插件写进 version catalog，建好空的 `:core-parser` 模块，挂上 `@HiltAndroidApp`。后续各条开发线只改自己目录下的源码。界面字符串按功能拆成 `res/values/strings_<功能>.xml`。

## 6. Cloudflare 验证通道（依据 S1 调研 `docs/design/cloudflare-research.md`，已经 F3 核查）
- 实测：论坛首页对所有非浏览器请求都返回 403 加托管验证（managed challenge）；但版块页和登录页，用浏览器请求头访问时返回 200。图片域名不受验证影响。所以验证是按路径触发的，不是整站拦截。
- 检测：以响应头 `cf-mitigated: challenge` 为准（Cloudflare 官方的检测方法）。403 一定要判断；429 也判断，这一点来自 fluxdo 的实践，官方文档没有明写；没有这个头时，只在 HTML 响应里按特征兜底。不能只因为页面里有 `challenge-platform` 就判定为验证页，因为正常页面也会被注入 Cloudflare 的检测脚本。
- 流程：检测到验证页后，先用不可见的 WebView 自动尝试通过；页面进入需要人工操作的阶段，或者超时，才从底部升起验证面板，并复用同一个 WebView，不重新加载。通过后把 Cookie 同步给网络层，原请求自动重试。
- 并发：同一时间只进行一次验证，其他请求排队等结果；验证失败后冷却 60 秒，避免反复弹出。
- UA：WebView 和网络层使用同一个 UA，取自 WebView 自己的 UA（去掉 `; wv` 标记）。
- 拦截器顺序（T5）：`ManualRedirectInterceptor` 在最外层，`BrowserHeaderInterceptor` 和 `AppCookieInterceptor` 在它里面。这样每一跳都按当次的目标地址存取 Cookie：前一跳下发的 Cookie 能带到下一跳，跳到外部域名时不会泄露登录 Cookie。重定向遇到 301、302、303 时把 POST 改为 GET 并去掉请求体；跨域名时去掉敏感请求头；最多跟随 16 跳。
- 排队不能占住连接（T5）：验证期间的排队放在发起请求之前的协程层，不在 OkHttp 拦截器里阻塞；协调器自己发的恢复请求（例如静默重新登录）用独立的 Dispatcher。后台的提醒轮询遇到验证页时直接放弃本次，不排队等待。
- 正文兜底只对 403 和 503 生效（T11）。
- Cookie：由应用层的 Cookie 拦截器统一管理，因为 Cronet 会绕过 OkHttp 的 CookieJar。Cronet 默认会自己跟随重定向（最多 16 次），这样中间跳转的 `Set-Cookie` 就到不了应用层，所以要关掉 Cronet 的自动重定向，由应用层逐跳处理。
- 测试：用 MockWebServer 模拟验证页，覆盖检测、排队、冷却、重试和 Cookie 同步。

## 7. 大屏与折叠屏
- 统一按 **高度 → 竖向物理铰链 → 窗口宽度（`600 / 680 / 840dp`）** 通用分档，不按单机型特判（详见产品设计 §4.1）：
  - 高度 `< 480dp`（手机横屏）：一律单栏 + 底部紧凑胶囊（T12）。
  - 竖向物理铰链分隔（`isSeparating == true`）：满足 `leftW ≥ 280dp && rightW ≥ 360dp` 时强制启用铰链双栏（对齐 `hingeBounds`，列表侧居中紧凑胶囊，不启三栏）；任一侧不足时退回单栏铺满窗口，紧凑胶囊居中于铰链外较宽一侧（该侧 `< 280dp` 则整窗居中）。内屏完全展平时（`isSeparating == false`）不避让折痕（对齐 `HingePolicy.AvoidSeparating` 语义）。
  - 宽度 `< 600dp`：单栏 + 底部悬浮毛玻璃胶囊。
  - 宽度 `600–679dp`：2 栏（左侧 `64dp` 常驻图标轨 + 单栏内容，点击展开按钮或 `我的` 图标以 `240dp` 浮层展开完整文字侧栏）。
  - 宽度 `680–839dp`：紧凑三栏（左 `64dp` 图标轨 + 中栏 `clamp(0.40×(W−64), 256, 380)` + 右栏 `≥ 360dp`，同上可 `240dp` 浮层展开）。
  - 宽度 `≥ 840dp`：完整三栏（左栏常驻展开 `clamp(0.20×W, 176, 220)`，可主动原地收起为 `64dp` 并按方向记忆 + 中栏 + 右栏 `≥ 360dp`）。
- 何时单栏、图标轨单栏、紧凑三栏或完整三栏完全由窗口尺寸与铰链状态决定，不提供手动强制开关。不使用 Material 默认在 `600–839dp` 退回单栏及 `1200dp` 才开三栏的策略，由 `calculateShellPaneLayout` 统一按整数 `dp` 计算（先 `L` → `S = W − L` → `M` → `R = S − M`）。
- 状态保持：选中的版块/入口、帖子、阅读位置（页码、楼层、偏移量）、正在输入的回复内容、中右分隔线吸附偏好（`paneSplitPortrait/Landscape`）与左栏收起偏好（`sidebarCollapsedPortrait/Landscape`），均存在 SavedStateHandle、Room 或 DataStore 里，通过单一状态源 + `movableContentOf` 在折叠、展开、半折、旋转与进程回收后无损恢复。
- 详细的布局、无障碍和动效规范见产品设计方案（`docs/design/product-design.md`）。

## 8. 分阶段与验收
| 阶段 | 结果 | 证据 |
|---|---|---|
| M0 构建链 | 用新工具链编译通过，CI 建立，用户看不到变化（targetSdk 保持 30）；M0-2 建好依赖目录和模块空壳 | CI 运行记录；模拟器冒烟测试的截图和 logcat |
| M1 验证通道 | 新的网络层能在 Cloudflare 后正常访问论坛 | MockWebServer 测试（检测、排队、冷却、重试、逐跳 Cookie、跨域剥离、POST 改 GET）；B1b 完成后，用户在 debug 工具页登录并抓到版块页的截图 |
| M2 安全网 | 样本导出（脱敏）→ 现有解析器的样本回归测试 → 分批抽到 `:core-parser` | 测试报告；故意改坏一个解析器，测试会失败；脱敏扫描测试 |
| M3 数据层 | Kotlin Repository、Hilt、Room 和 DataStore（全新结构，不迁移旧数据）、提醒轮询 | 单元测试 |
| M4 外壳 | 预览入口里的主题、毛玻璃导航、三栏自适应外壳与左侧栏、动效系统 | Roborazzi 截图；动效逐帧测试；“减少动态效果”测试；重组次数断言 |
| M5 阅读核心 | 版块、帖子列表、帖子详情（Compose 原生正文）、三栏与铰链双栏；折叠、半折和展开时状态保持 | 截图测试；状态恢复测试；重组次数断言 |
| M6 其余页面 | 编辑器、表情、短消息、提醒、搜索、我的、设置、用户资料、看图、登录、外部链接 | 每页的截图测试和交互测试 |
| M7 切换和清理 | 切换启动入口；删除旧代码和旧依赖；targetSdk 36（通知权限、MediaStore 下载、全面屏）；收紧 R8 规则；Baseline Profile | 依赖清单；全新安装后的冒烟测试；真机上的帧时间 |

- **新的存储结构（T4 中仍然适用的部分）**：备份规则排除和 Keystore 绑定的加密文件和 Tink 密钥；草稿用业务键（`reply:<tid>`、`new:<fid>`、`edit:<pid>`，见产品设计 §7.2）。
- **性能验证的分工（T9）**：日常 CI 用重组次数断言守住性能不退化；Roborazzi 只验证毛玻璃的降级分支和对比度；真实的模糊效果、API 31 的性能、P90 帧时间和卡顿率，在 M4、M5、M7 的阶段验收时用真机测（没有真机时用模拟器的硬件渲染，并注明数据不可靠）。
- **截图矩阵分级（T9）**：外壳和多栏核心页面跑 6 种窗口尺寸 × 深浅色（另加 5 种特殊窗口，见产品设计 §10.1）；其他页面的主状态跑 3 种宽度（`411`、`749`、`1280dp`）；空、错、加载状态跑 2 种宽度（`411`、`933dp`）。
- **release 构建（T10）**：CI 每次都编 release；M7 清理 `proguard-rules.txt` 中过宽的 `-keep` 规则。

## 9. 风险
1. Cloudflare：cf_clearance 和设备、IP、UA 绑定，默认 30 分钟过期。网络指纹和浏览器不一致时，可能过了验证仍被拦，所以底层优先用与 Chrome 一致的网络栈。没有这种网络栈的旧设备（Android 13 及以下、又没有 Play 服务）风险最高，退回 OkHttp 加单次重试；实测仍过不了时，按 ABI 分包引入 `cronet-bundled`（T11）。网络切换导致出口 IP 变化后凭证是否失效，官方没有明说（属于工程经验）；做法是：网络切换后再遇到验证页，就重新验证，不预先清掉凭证。
2. 样本采集依赖用户登录，这一步只有用户能做。
3. 帖子正文的 Compose 原生渲染：表情、链接、跳楼的点击和长按菜单要重新实现，旧版 `TextViewWithEmoticon` 的行为要逐项对照（以 B 线的样本为输入做截图测试）。
4. 帖子里的 Emoji：页面按 GBK 提交，Unicode Emoji 要先转成十进制 HTML 实体（`&#...;`），否则会变成问号（`PostHelper.java:84-86`，T8）。
