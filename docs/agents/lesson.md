# 项目经验

> 只记录仍然有效、属于本项目的经验，按主题分组，括号里写首次发现的日期。模型脾性写进 team-lead skill 的 models.md。已经由检查守住的经验缩成一行，写明是哪个检查。

## 站点
- 用 curl 访问 `https://www.4d4y.com/forum/` 会得到 Cloudflare 验证页（HTTP 403，`cf-mitigated: challenge`），直接用 OkHttp 抓页面会被拦。（2026-11-24）
- Discuz! 7.2 很可能将 6 位十进制实体（如补充平面 Emoji `&#128512;`）二次转义为 `&amp;#128512;`，新解析器和正文渲染需在 jsoup 解码后再做一次实体解码（需真机确认）。（2026-11-25）
- 统一 UA 采用移动版 Chrome UA 后，4d4y.com 因 UA 含 `Android` 下发手机版模板（无 `#umenu`，`pm.php`/`notice.php` 返回 500），真机 M1 全部失败。根因是改动对站点可见行为（UA、请求头）的设计没有先对真实站点做一次只读对照（换不同 UA 抓同一页比模板），只验证了 Cloudflare 维度。规则：任何改变站点可见身份的设计（UA、Client Hints、Cookie 策略、请求头），落地前先用只读 GET 对比新旧取值下的响应模板/状态码，把结果写进卡片背景。（2026-11-25）

## 构建
- `gradle.properties` 已经进仓库（M0-1 把它移出了 `.gitignore`）；Gradle 配置写在里面 CI 才读得到。（2026-11-24）
- targetSdk 在 M7（旧界面全部删除）之前保持 30；升级会触发强制全面屏、预测性返回、存储权限和通知权限的行为变化。（2026-11-24）
- R8 full mode 会剥掉旧代码靠反射用到的泛型签名和类（Gson TypeToken、cookie-store、jobqueue、Iconics），release 包冷启动就崩；由 CI 的 release 模拟器冒烟守住，加依赖或改混淆规则后要跑一次 emulator=true。（2026-11-24）
- Roborazzi 1.79.0 默认以 `roborazzi.outputDir`（`build/outputs/roborazzi`）作为缓存输入，而非 `src/test/snapshots/images/`；已由 `hipda/build.gradle.kts` 显式配置 `roborazzi.outputDir` 守住（在 `card/M0-3-ci-hardening`）。（2026-11-25）
- Android Auto Backup（`backup_config.xml` / `data_extraction_rules.xml`）不支持 `*` 通配符或文件名前缀，新增加密 DataStore 必须在两份 XML 显式列出精确路径；已由 `BackupExclusionRulesTest` 源码扫描守住（在 `card/D3c-credentials`）。（2026-11-25）
- Jsoup 1.24.1 的 `selectFirst`/`first`/`last` 带 `@org.jspecify.annotations.Nullable` 且未传递依赖 `jspecify`，在 Kotlin 2.4 泛型推断下报错；`:core-parser` 中用 `select(selector).firstOrNull()` 规避。（2026-11-25）
- OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会读 `AssetManager` 里的 `PublicSuffixDatabase.list`，单测需用 Robolectric 并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。（2026-11-25）
- 平台 API 的测试替身必须按真实语义建模：A5a 的假 `WebViewCookieAccess` 只按 Cookie 名删除，掩盖了真实 `CookieManager` 里 host-only 过期头删不掉 `Domain=.4d4y.com` Cookie 的行为（R-A5a 阻断）。卡片里写明：替身要按 (name, domain, path) 存取，并模拟已知的平台怪癖。（2026-11-25）
- 本机的 Android SDK 和 JDK 由 mise 安装、不在环境变量里：本地 Gradle 前先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`（不要在 worktree 写 `local.properties`），否则会误报“没有 SDK”。新 Roborazzi 基线用 `:hipda:recordRoborazziDebug --tests <本卡截图测试类>` 在本地按 DISPATCH 第 2 节录制，必须用 `--tests` 限定，以 CI 的 verify 为准。（2026-11-25）
- 合并推送到 main 后 GitHub 偶发不触发 push 事件，30 秒内 `gh run list` 查不到时用 `gh workflow run "Android CI" --ref main` 补跑。（2026-11-25）
- 单测 JVM 默认堆不够，测试数过千后 Roborazzi 大图会触发 OOM；已在 `hipda/build.gradle.kts` `Test` 任务设置 `maxHeapSize = "1536m"` 与 `forkEvery = 400L` 守住（`CI-heap` `@822dccb`，耗时反而缩短 12.6%–16.8%）。（2026-11-26）

## 协作
- 8 个代理同时用 `gh run watch`（默认每 3 秒查一次）约 40 分钟就耗尽了 GitHub API 每小时 5000 次的额度，所有 `gh` 命令返回 403；规则已写进 DISPATCH §2（`--interval 60`）。（2026-11-24）
- 只读审查员不能在主目录里 `git checkout`：负责人的提交会落到游离的 HEAD 上，推送 `main` 时什么都没推上去。让审查员用 `git show <commit>:<路径>` 读文件，或者在自己新建的 worktree 里检出。（2026-11-24）
- 上一任负责人的会话额度用完后，它“排好”的定时任务和后续卡片并不存在：交接时以 `list_schedules`、`list_agents` 的实际结果为准，不以交接文件为准。（2026-11-24）
- 第三批 C4a/C5a/C5b（Neon xhigh）生产代码写到卡片估计的 2–3 倍：手写 `Layout`/`Modifier.Node`/命令队列、生产代码里放测试专用钩子；C4a 的手写布局二次测量导致窄窗崩溃。界面卡条款已写进 DISPATCH §5；审查卡里要专门检查行数和“标准组件能否替代”。（2026-11-25）
- 并发/状态机类卡的独立探针验证抓到了审查漏掉的阻断：V-A4b 发现 solver 永不返回时网关永久卡死（R-A4b 判 APPROVE）。所以这类卡保留探针验证（见 DISPATCH §5 流程强度）。（2026-11-25）
- 并行卡除了生产文件，测试文件也要无重叠：A4b 与 A5a 都改了 `HttpClientModuleTest.kt`，合并时冲突；共享测试热点（`HttpClientModuleTest.kt` 这类按模块断言的测试）在派卡时指定唯一一张卡修改，其他卡把断言写进自己的新测试类。（2026-11-25）
- 负责人合并用独立 worktree，合并失败时不要在同一条命令里用 `;` 继续后续步骤（一次合并失败导致后续命令在错误目录建了 worktree）。（2026-11-25）
- 收尾轮顺带做的简化项若触及已验证的并发代码（如合并状态机），按重写处理送原审查者复审，不能当小修由 OPS 核对。（2026-11-25）
- E2a 卡未写链接 scheme 白名单，是独立审查（R-E2a B1）才拦下 `javascript:`/`intent:`/`file:`/`content:`/`data:` 风险；已将“任何把站点内容（帖子、短消息、签名、链接、图片）交给系统或 WebView 打开、加载的代码必须走 http/https/经校验 mailto 白名单，且每种危险 scheme 配反例测试”落入 `DISPATCH.md` §4 阻断清单与 `_cards/_common.md` 界面卡固定检查项。（2026-11-26）
- 每张卡几乎都被独立审查（R）或探针验证（V）拦下真问题（例如：E2a 的 `javascript:` 链接可以点击；E2b 和 E2d 合并后同帧下跳 473dp；E3 在铰链状态下晋升时崩溃；E1 深色模式下骨架屏不可见；D8a 收藏成功却被误判为失败），因此绝不砍审查；提速靠提高实现者并发上限（3→5）、收窄本地测试范围（全量单测与截图校验交由免费公开仓 CI）和按风险分级（界面卡截图由 R 目视，V 只用于复杂档与跨卡共享运行时状态合并加测，建议级小改动只走 OPS fixcheck）。（2026-11-26）
- V-E14 在实现分支的独立组件探针 13/13 全部 PASS，但合入 `main` 后在真实链路（`NextAppRoot + ThreadDetailPane + ImageViewerRendererImpl`）下复现 3 项集成缺陷（`E2b` 上方图片撑高后退场飞回旧坐标、`Dismissing` 退场期连按返回穿透关帖、三栏看图时底层玻璃仍采样）。规则：涉及跨卡浮层、返回栈拦截或玻璃拓扑的卡，必须做合入 `main` 后的真实链路集成探针。（2026-11-26）
- 并发 `BackHandler` 按 LIFO 抢占：V-E4 首轮 `probeD2` 暴露启动即常驻挂载的模态抽屉 `BackHandler(enabled = visible)` 排在后挂载的详情 `PositionStack` 之后，导致抽屉打开时按返回先弹出位置栈而非关闭抽屉。规则：常驻的浮层/抽屉 `BackHandler` 必须只在打开时条件启用，并以打开状态为 `key` 重挂到 LIFO 栈顶。（2026-11-26）
- Compose 拖拽偏移不要用 `scope.launch { Animatable.snapTo }` 异步写——V-E5 首轮 `probe3b` 暴露同帧 `down → move → up` 会让松手回调先于排队协程读到旧值，R-E5 第 2 轮亦查出默认调度的 `stop()` 抢占取消退场协程导致面板锁死。规则：拖动态用同步 state（首次停弹簧用 `CoroutineStart.UNDISPATCHED`），`Animatable` 只管松手动画，所有过渡动画用 `try/finally` 落终态。（2026-11-26）
- 实现方自报「已目视核验截图」不可信：E6 有 7/12 张新增 `editor_*.png` 正文空白的错误基线被 R-E6 逐张目视发现。规则：所有新增或变更的 Roborazzi 截图基线必须由独立审查员（R）逐张目视核验。（2026-11-26）
- 依赖链提速：基于在审分支提前开工（如 `E7`/`E6b` 基于 `E6`、`M7-2`/`M7-3` 基于 `M7-1`）可行，前提是在审卡改动面已稳定、且派卡时写清合并冲突责任方（通常由后合入方负责解冲突并复跑 CI）。（2026-11-26）
