# 项目经验

> 只记录仍然有效、属于本项目的经验，按主题分组，括号里写首次发现的日期。模型脾性写进 team-lead skill 的 models.md。已经由检查守住的经验缩成一行，写明是哪个检查。

## 站点
- 用 curl 访问 `https://www.4d4y.com/forum/` 会得到 Cloudflare 验证页（HTTP 403，`cf-mitigated: challenge`），直接用 OkHttp 抓页面会被拦。（2026-11-24）
- Discuz! 7.2 很可能将 6 位十进制实体（如补充平面 Emoji `&#128512;`）二次转义为 `&amp;#128512;`，新解析器和正文渲染需在 jsoup 解码后再做一次实体解码（需真机确认）。（2026-11-25）

## 构建
- `gradle.properties` 已经进仓库（M0-1 把它移出了 `.gitignore`）；Gradle 配置写在里面 CI 才读得到。（2026-11-24）
- targetSdk 在 M7（旧界面全部删除）之前保持 30；升级会触发强制全面屏、预测性返回、存储权限和通知权限的行为变化。（2026-11-24）
- R8 full mode 会剥掉旧代码靠反射用到的泛型签名和类（Gson TypeToken、cookie-store、jobqueue、Iconics），release 包冷启动就崩；由 CI 的 release 模拟器冒烟守住，加依赖或改混淆规则后要跑一次 emulator=true。（2026-11-24）
- Roborazzi 1.79.0 默认以 `roborazzi.outputDir`（`build/outputs/roborazzi`）作为缓存输入，而非 `src/test/snapshots/images/`；已由 `hipda/build.gradle.kts` 显式配置 `roborazzi.outputDir` 守住（在 `card/M0-3-ci-hardening`）。（2026-11-25）
- Android Auto Backup（`backup_config.xml` / `data_extraction_rules.xml`）不支持 `*` 通配符或文件名前缀，新增加密 DataStore 必须在两份 XML 显式列出精确路径；已由 `BackupExclusionRulesTest` 源码扫描守住（在 `card/D3c-credentials`）。（2026-11-25）
- Jsoup 1.24.1 的 `selectFirst`/`first`/`last` 带 `@org.jspecify.annotations.Nullable` 且未传递依赖 `jspecify`，在 Kotlin 2.4 泛型推断下报错；`:core-parser` 中用 `select(selector).firstOrNull()` 规避。（2026-11-25）
- OkHttp 5 Android 的 `Cookie.parse` 解析带 `Domain=` 的 Cookie 会读 `AssetManager` 里的 `PublicSuffixDatabase.list`，单测需用 Robolectric 并在 `@Before` 调 `OkHttp.initialize(ApplicationProvider.getApplicationContext())`。（2026-11-25）
- 本机的 Android SDK 和 JDK 由 mise 安装、不在环境变量里：本地 Gradle 前先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`（不要在 worktree 写 `local.properties`），否则会误报“没有 SDK”。新 Roborazzi 基线用 `:hipda:recordRoborazziDebug --tests <本卡截图测试类>` 在本地按 DISPATCH 第 2 节录制，必须用 `--tests` 限定，以 CI 的 verify 为准。（2026-11-25）

## 协作
- 8 个代理同时用 `gh run watch`（默认每 3 秒查一次）约 40 分钟就耗尽了 GitHub API 每小时 5000 次的额度，所有 `gh` 命令返回 403；规则已写进 DISPATCH §2（`--interval 60`）。（2026-11-24）
- 只读审查员不能在主目录里 `git checkout`：负责人的提交会落到游离的 HEAD 上，推送 `main` 时什么都没推上去。让审查员用 `git show <commit>:<路径>` 读文件，或者在自己新建的 worktree 里检出。（2026-11-24）
- 上一任负责人的会话额度用完后，它“排好”的定时任务和后续卡片并不存在：交接时以 `list_schedules`、`list_agents` 的实际结果为准，不以交接文件为准。（2026-11-24）
