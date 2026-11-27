# 负责人交接说明（2026-11-27 14:25 +07）

接手的负责人：先按 `team-lead` skill 的「Session start」读 `docs/agents/DISPATCH.md`、`docs/agents/lesson.md`、`docs/agents/decisions.md`，再读本文件。用中文和用户交流。以 `list_agents`、`list_schedules` 的实际查询结果为准。

---

## 1. 全局进度总览

- 设计与流程文档（均在 `main`）：`docs/design/{tech-plan,backlog,product-design,copy}.md` v0.4；`docs/agents/decisions.md`（`0895b83`）；`docs/agents/DISPATCH.md`、`lesson.md` 与 `docs/agents/cards/_common.md` 已落地站点 scheme 白名单阻断（`00cb3c4`）、**流程提速**（`e735e7a` + `5bae5ab`）、2 条通用新规（`1dc594e`）、“跨卡浮层/返回/玻璃卡必做合入后真实链路探针”（`3617fad`）、“常驻浮层/抽屉 `BackHandler` 条件启用并按打开状态重挂”、“Compose 拖拽态同步 state + `try/finally` 终态”、“单测 JVM 1536m 堆上限 + `forkEvery=400`”、“Roborazzi 截图须由 R 逐张目视”、“基于稳定在审分支提前开工并写清冲突责任方”及“关键回归测试修复轮须做变异红/绿验证”等经验。另外 **`team-lead` skill 已提交 `5a2fc95`，等待用户确认是否推送**。
- **已合并进 `main`**（当前代码 `HEAD`：`f9862b8`，合并态 CI `45956240271`（`emulator=true`）全绿、main 推送 CI `45958045820`绿，已产出 `debug-apk`；**M5 / M6 / 编辑器线 / M7 开发与 P1/P2 收尾全部完成，下一步 `M7-5` 用户真机验收与发布（需用户批准）**；真机验收包已更新至 `docs/device-check.md`）：
  - 第一批：M0-1、M0-2、C1b、D3a、A1、D1、C1；
  - 第二批：M0-3、B1a、A2、D3c、C3、C2（联合 CI 45526302892，223/223）；
  - 第三批与 M1/M2/M3(部分)/M4/三栏/kudo 收口：
    - A3a 拦截器链（`26bb6bf`：R-A3a APPROVE + V-A3a 11 探针 PASS）→ `03d2f01`
    - C5b 反馈组件（R-C5b APPROVE，简化 1120→686 行后复核 APPROVE `dd89039`）→ `3ed1f9e`
    - C4a 自适应外壳（修复后复核 APPROVE + V-C4a PASS `dbb97ae`）→ `92e01d3`
    - C5a 状态组件（简化 816→534 行并修复后复核 APPROVE `8d55671`）→ `461eb91`
    - A3b Cronet 传输（V-A3b PASS + R-A3b APPROVE `@6e91c6e`）→ `fe6b553`
    - A4a Cloudflare 验证页检测器（V-A4a PASS + R-A4a APPROVE `@e75ef76`）→ `294c705`
    - C4b 导航随滚动与键盘隐藏（R-C4b APPROVE + V-C4b PASS `@6e92adc`，C4b-fix1 核对 PASS `@6396593`）→ `4229253`
    - A5a WebView UA 统一与 Cookie 桥（R-A5a 阻断修复后复核 APPROVE `@3c9e3b3`）→ `4d90818`
    - A4b 站点访问网关与独立恢复 Dispatcher（V-A4b 阻断修复后复验 PASS `@137376a`）→ `0247458`
    - A5b 透明 ChallengeActivity 与后台自动过盾（修复轮 1 闭合后复审复验 PASS `@449cf13`）→ `b8c3975`
    - A6 可见验证面板与原地等待/冷却提示（R-A6 APPROVE、V-A6 PASS；收尾轮合并状态机经 R-A5b 复审 `@f0e0a15`）→ `8999d94`
    - B1b debug 样本导出工具页（R-B1b 审查与修复轮复审 PASS `@b9c9cc2`）→ `f7e0bcf`
    - A5c 统一桌面 Linux Chrome UA 与 WebView Client Hints（R-A5c APPROVE `@d6b767c`）→ `15d3e32`
    - D-3pane 大屏与折叠屏三栏自适应布局定稿（R-D3pane 审查及修订轮 APPROVE `@65a1ea5`）→ `b4ab916`
    - B1c 本人独立数字 UID 脱敏扫描补全与 16 个真机脱敏样本 + `manifest.json` 入仓（reviewed `@37df441`；远端临时分支 `samples/b1b` 已删）→ `d5ed2ce`
    - G1 毛玻璃观感调参 + debug 实时调参面板（R-G1 APPROVE `@8352ae1`）→ `7cf0e70`
    - C4c 自适应外壳三栏升级与左侧栏（R-C4c APPROVE + V-C4c 视觉与探针 PASS `@6706b09`）→ `0519096`
    - B9a `:core-parser` 公共基座 + 登录/短消息/提醒解析 + R-B1c S1/S2（R-B9a APPROVE + V-B9a 探针 PASS `@d1263a5`）→ `d45b6a2`
    - B9b 搜索/简单列表/用户资料/黑名单/发帖表单/收藏/上传解析 + B9a 遗留 4 项小修（R-B9b APPROVE `@14dc33d`）→ `c0b8c8c`
    - D-kudo kudo 液态玻璃与交互设计修订至 v0.4（基于 G-kudo 调研，R-Dkudo 复审修订轮 APPROVE `@6f139c4`）→ `bba139c`
    - B8 帖子详情 + 内容块 + 1 楼投票解析器（R-B8 APPROVE + V-B8 PASS `@178e548`；修复轮 1 闭合 S1/S2/S3/S5 核心项经 `B8-fixcheck` PASS `@9a360c8`）→ `cf59951`
    - B7 帖子列表解析器与样本等价测试 + 授权闭合 R-B9b S1/S2/S4（R-B7 APPROVE `@822969a`）→ `db5857d`（main CI 绿 `@db5857d`）
    - D2 `AuthRepository` + `ForumHttpClient` + 代际 Cookie 写门控（R-D2 `@df1c12d` 阻断修复后复审复验 V-D2 PASS `@85ac8b1`）→ `0e5fb57`
    - G2 毛玻璃底层由 Haze 2.1 迁移至 Kyant `backdrop`（API 33+ 折射 / 31–32 纯模糊 / ≤30 降级、上限 3 区域、API 36 release 模拟器冒烟绿；R-G2 APPROVE + V-G2 PASS `@74c1c8c`）→ `5ce63dc`（**main CI 绿 `@5ce63dc`**；真机包清单同步至 `c3344e9`）
    - D8b 用户资料与黑名单 Repository + 分账号本地缓存（R-D8b APPROVE `@040e6e2`）→ `5c71b35`
    - D7 短消息与提醒 Repository + 15s 冷却 + 分离未读流与本地已读状态（R-D7 APPROVE `@437ca88`）→ `18dfa80`
    - D4 帖子列表 Repository + 置顶/黑名单/已读过滤与自动补页 + 闭合 R-B7 S1 与 R-D8b S1–S3（R-D4 APPROVE `@990e3a6`）→ `243aee8`
    - C4d 外壳玻璃拓扑下放、`TopBar`/`Sheet` 档位、底部胶囊拖拽与 R-G2/R-C4c 收口（R-C4d APPROVE + V-C4d PASS `@0554483`）→ `21b1e3c`（真机包清单同步至 `e1eb834`）
    - `fix/D7-notify-poll-test` 修复 D7 提醒/短消息仓储切号空帧假退登根因（reviewed `@075f053`）→ `2a3f7ca`
    - D5 帖子详情 Repository + 浏览历史/阅读位置/1 楼投票/黑名单遮蔽（R-D5 APPROVE `@65000ff`；修复轮 1 `@ec0a5e8` 闭合 S1–S4 经 `D5-fixcheck` PASS）→ `e71c3fb`（**main CI `45650433258` 绿**，push 事件未触发，手动 dispatch）
    - D6 发帖/回复/编辑/上传 Repository + 草稿/小尾巴/30s 冷却 + 闭合 R-B9b S3（R-D6 APPROVE + V-D6 PASS `@ce4840e`）→ `99879a1`
    - D9 提醒轮询 WorkManager + HiltWorker + 免打扰 + 遇验证放弃 + 闭合 R-D7 S1–S4（R-D9 APPROVE + V-D9 PASS `@933a5ac`）→ `3600f45`（**main CI `45655134227` 绿**）
    - D8a 搜索、今日新帖、收藏与关注 Repository + D3d 缓存（R-D8a 复审轮 2 `@6046d62` 闭合 B1 与 S1–S4，APPROVE，不设 V）→ `cbcb9c0`（**main CI `45659929460` 绿**）
    - E0 M5 阅读核心端到端 Tracer Bullet 与 E 线不可逆公共基座（R-E0 复审轮 2 APPROVE + V-E0 复验轮 2 PASS `@fed1070`）→ `db7dba6`
    - E15 登录页、临时退出登录出口与 `R-A6 S3/S4` / `V-A6` 收口（R-E15 APPROVE + V-E15 PASS `@88f6323`；建议修复轮 1 `@e7029fa` fixcheck PASS）→ `65e627f`
    - E2a 帖子详情核心完整版（透明顶栏、Compose 原生正文、表情/三主题调色、双层链接 scheme 白名单、`PostImageBlock` 接缝；R-E2a 复审轮 2 APPROVE `@332c31c` + V-E2a PASS `@60bd5b7`）→ `3dd65c4`
    - E1 论坛页完整版（透明吸顶折叠顶栏、荧光笔标签、`今日新帖`、新帖胶囊、窄中栏、跨号缓存隔离与深色骨架修复；R-E1 复审轮 2 APPROVE + V-E1 复验轮 2 PASS `@e24e3c0`）→ `b7557bf`（**W2 全部收齐**）
    - E2b 帖子图片渲染（按显示尺寸解码、比例缓存、三分支无跳动补偿、省流点击加载与 `http`/`https` 白名单；R-E2b APPROVE + V-E2b PASS `@1597b4e`）→ `c0fc15d`（main CI `45706618318` 绿）
    - E2d 帖子详情双向连续加载、向上插页同帧锚定与单帧单一滚动权威（R-E2d 复审轮 2 APPROVE `@f8b5d2a` + V-E2d PASS `@c447d4a` + V-E2b 合并复验 PASS `@f8b5d2a`）→ `fbeeb28`
    - D-cleanup 数据层/后台轮询/论坛跨号屏蔽与 `DefaultAuthRepository` 凭据订阅降级保护收口（R-D-cleanup `@230e88d` + 修复轮 `@de2681e` OPS fixcheck PASS）→ `5662652`
    - E3 多栏内容容器、中右分隔线拖拽吸附与横竖屏记忆、铰链双栏、5 级返回栈、玻璃协同与 `R-E0`/`R-C4d` 收口（R-E3 复审轮 2 APPROVE + V-E3 复验轮 2 PASS `@ba8f4c2`）→ `db52669`（main CI `45727798720` 绿）
    - E2c 引用展开、跨页同帧跳楼高亮、落地锚点窗口、长按菜单、只看此人、1 楼投票与 `:core-parser` 遗留收口（R-E2c 复审轮 2 APPROVE + V-E2c 复验轮 2 PASS `@770ff7f`）→ `70da638`
    - E-slots M6 四槽位 `@BindsOptionalOf` 路由接口与外壳接线、旧 16 元保存态兼容、铰链设置开帖与看图兜底返回（R-E-slots APPROVE `@f8383ba` + 修复轮 `@a5ddedd` OPS fixcheck PASS）→ `4b6c1f0`
    - E8 短消息 + 提醒（合并原 E8+E9，基于 D7；含并发纪元对齐、发送串行与冷却拦截、独立令牌+前驱锚点撤销、跨号状态隔离、`R-D9 S2` 通知文案归口与锁屏隐私脱敏；R-E8 两轮 APPROVE `@c65a38b`，含 12 张截图目视复验）→ `0883c89`
    - E12 设置 5 组、账号管理、黑名单、管理版块 + 用户资料（合并原 E12+E13，基于 D2/D3a/D8b；含错误日志查看/复制/分享脱敏、黑名单令牌锚点撤销、正式账号页与 `R-E15 S2` 删除临时退登弹窗、旋转保态；R-E12 两轮 APPROVE `@c4046ff`，含新增截图目视复验）→ `158ac9c`
    - E10 搜索 + 我的：收藏与关注、我的帖子、浏览历史（合并原 E10+E11，基于 D8a + D1；含 `ForumLinkResolver` 严格白名单、三类列表统一令牌+锚点撤销与在途防复活、Room 串行清空恢复；R-E10 两轮 APPROVE `@9ee39e9`，含 14 张截图目视复验）→ `eb1ace1`
    - E14 看图全屏浮层（实时缩略图共享边界进退与离屏 `0.85f` 淡出、缩放与下拉关闭、`Dismissing` 吞并返回、看图暂停底层液态玻璃采样、长图贴宽顶对齐、GIF 播放切换、`R-E2b S4` 原图字节级确定进度下载与限尺寸解码、`MediaStore`/API 28 权限保存与安全分享；R-E14 两轮 APPROVE `@76dc92e`；V-E14 首轮独立 13/13 PASS 但合入 `main` 链路 3 FAIL → 第 2 轮 PASS，CI `45747336019`）→ `1cffcd9`（main CI `45749346160` 绿）
    - M6-polish M6 跨卡与各页 6 项小修（`SmsConversationScreen` 冷却/错误顶替旧撤销条、`NextAppStateSaver` 短消息仅存 `uid`、`detail:$tid` 20 帖 LRU 淘汰、`BlacklistViewModel` 排在途移除项、`Search`/`Favorites` 无未决令牌刷新秩表、`ImageViewerOverlay` 多图远滑重返不重弹提示；R-M6-polish APPROVE `@0e186fb`）→ `0eebc8a`
    - E4 阅读位置记忆（`NonCancellable` 串行落盘与首轮门闩）、`PositionStack`（返回优先级低于模态抽屉）、底部三段式液态玻璃工具条（支持 `suppressBottomGlass` 与 `LocalHiGlassSuspended` 暂停采样）、内联跳页条、`rawUserInfo` 链接安全加固、`E2d` 方案 B 移除旧分页栏与 `R-C5b` 收口（R-E4 两轮 APPROVE `@bed7fcc`；V-E4 首轮 FAIL `probeD2` 抽屉返回被位置栈抢占 → 第 2 轮 PASS，CI `45757086073`）→ `92e1563`（**main CI `45758541824` 绿，M5 全部完成**）
    - E16 外部链接、桌面快捷方式、通知直达、帖子站内链接、鉴权附件下载与剩余路由总收口（唯一 `SafeLinkPolicy` 统一 5 份白名单并吸收 `rawUserInfo` 防护、未知链接排他浏览器回落防自环、路径净化与 `MediaStore`/API 28 权限落盘及跨域零 Cookie 隔离、`管理版块` 接通与 `R-M6-polish S1` 撤回必红收口；R-E16 APPROVE `@b415a6c`；V-E16 7 组探针 PASS，CI `45763289885`）→ `6ff55a9`（**M6 全部完成**）
    - M7-0 只读功能对齐审计（Hydrogen 盘点旧版 `ui/` 共 127 项用户可见功能，检出 14 项缺失并拆为 `M7-G1..G4` 与 `E7 #11` 补齐；报告归档 `docs/agents/reports/M7-0.md`）
    - M7-G4 短消息列表顶栏 `发送短消息`（任意用户名 + 15s 冷却）与气泡长按 `复制` 纯文本（R-M7-G4 APPROVE `@6df20f3`，含 7 张截图目视复核）→ `fd414a1`
    - E5 毛玻璃跟手弹出快速回复面板（`HiGlassStyle.Sheet` 卸载底栏守 ≤ 3 区域、IME 逐帧升降与 6 级优先级让位 `SheetSlotPlan + Column`、同步拖拽与 `try/finally` 终态过渡、草稿与 `R-D6 S2` 删空清库、30s 冷却与 `@BindsOptionalOf` 三插槽；R-E5 三轮，第 3 轮 APPROVE `@a5c9440`；V-E5 首轮 FAIL 拖拽异步 `snapTo` → 第 2、3 轮 16/16 探针 PASS）→ `afa3783`
    - M7-G1 Buy&Sell（`fid=6`）9 项分类筛选、设计系统统一顶栏滚顶原语与论坛/我的/搜索/资料页接线、六类列表长按直达末页、资料页点大头像按 Coil key 清除内存+磁盘缓存重载（R-M7-G1 APPROVE `@e4690ea`，含 2 张新截图目视复核；`R-M7-G1 S1` 文案已同步 `copy.md`）→ `83484fd`
    - M7-G2 移除非当前已存账号凭据、冷启动 `NotificationScheduler.syncFromSettings()`、帖子看图多图图集接口（`ThreadImageGallery` + `PostImageBlock`）、关于页三文档（`release-notes`/`donors`/`license`）与按 `versionCode` 升级首启更新记录弹窗（R-M7-G2 APPROVE `@4b3c1b9`；建议修复 `@5317579` 闭合 S1 全新安装静默不弹与 S4 `copy.md` 同步，经负责人核对）→ `f2fbe60`
    - M7-G3 楼层长按 `文字选择`（独立弹窗 `SelectionContainer` 无手势冲突）、单楼 `分享`（规范 `https` + `SafeLinkPolicy` 零敏感参数）、被警告楼层标示与 GBK/CDATA 警告弹窗、详情顶栏滚顶与 `#9` 多图图集 Provider 接线，以及 `R-E16 S2` / `R-M7-G1 S2` 同帖跳楼/末页（单一滚动权威 + 先压 `PositionStack`）（R-M7-G3 APPROVE `@e57c7f8`；`R-M7-G3 S3` 文案已同步 `copy.md`）→ `bb5a870`
    - E6 完整编辑器本体、无损承接 `E5` 快速回复交接、多图并发上传条（默认压缩闭合 `R-D6 S1`，`content://` 校验与临时文件零残留）、发新帖（闭合 `R-D1` 必选正整数 `fid`）/回复草稿（含 `R-D6 S2`）、§4.2 多栏投放（纯色 0 玻璃 + 挂起被遮挡右栏玻璃采样 + 进程重建光标/返回优先级修复与详情底部工具条 Snackbar 避让；`R-E6 S3` 已在实现中同步 `copy.md`）（R-E6 两轮 APPROVE `@adc777b`；V-E6 首轮检出 `probe2b`/`probe4b`/`probe9` → 第 2 轮 14/14 探针 PASS `@adc777b`）→ `2739d4d`
    - CI-heap 单测 JVM 内存余量配置（`hipda/build.gradle.kts` `Test` 任务设 `maxHeapSize = "1536m"` + `forkEvery = 400L`，消除 Roborazzi 大图 OOM 且 `:hipda:testDebugUnitTest` 耗时缩短 12.6%–16.8%；负责人核对 `@822dccb`）→ `ed361c2`
    - E6b 编辑已发表楼层（预取线上原文、`[attachimg]` 反向解析、`还原原文` 独立令牌撤销、`edit:<pid>` 草稿与 `R-D6 S2`、免 30s 冷却与不附小尾巴、`deletedAttachments` 不误删）与楼层菜单删除自己楼层（不可撤销二次确认、保阅读位/首楼删帖退回列表，100% 离线 `MockWebServer`）（R-E6b APPROVE `@7ac4c82`；V-E6b 5/5 探针 PASS `@7ac4c82`）→ `b803846`
    - M7-1 切主入口（`MainActivity` 为正式唯一 `LAUNCHER`）、保留 `.ng.IntentActivity` 别名与 5 条 `pathPrefix`、快捷方式与通知 `PendingIntent`（`FLAG_IMMUTABLE`）单实例单次消费并防 `EXTRA_USERNAME`/`BadParcelableException`、收口 `R-E16 S1`（最小 `<queries>` + `https://` `BROWSABLE` 探测）与 `R-M7-G2 S2`、旧 Activity 全部 `exported="false"` 不可达（包名与签名 0 改动；R-M7-1 APPROVE `@c001dee`；V-M7-1 PASS 含 API 36 模拟器 debug/release 冒烟 `@c001dee`）→ `846ff3a`
    - E6b-followup 闭合 `R-E6b S1/S2`：切号或登出时自动收起已打开删楼确认框且不误发请求，独立末页仅剩单楼被删空时自动回拉前一页（负责人核对 `@e9976d5`）→ `08a43a0`
    - M7-2 删除全部旧 View 界面、旧 Java 解析器（生产 `.java` 归零）与 24 项旧依赖（含移除 `jitpack.io`），`HiNextApplication` 独立接管前后台可见计数与脱敏崩溃日志写入，固化 `C1b#1` 61 组表情四元组单测与 `R-D9 S3` 清理遗留 `"noti_work"`，release APK 体积由 `9.87 MB` 降至 `6.12 MB`（**-37.98%**）（R-M7-2 APPROVE `@1c2410b`；V-M7-2 PASS 含 API 36 模拟器 debug/release 冒烟 `@1c2410b`）→ `4c7f6a0`
    - E7 表情选择面板与键盘等高原地互换（`EmojiPanelSlotBox` 唯一 IME 消费点 + 共享净高缓存防导航栏污染，覆盖快速回复、完整编辑器含居中模态/编辑态及 `M7-0 #11` 短消息输入栏）、§7.4 无记忆限高与外接键盘超时收敛、整枚论坛表情代码回删、闭合 `C1b#3`（`strings_emoji.xml`）与 `R-M7-G4 S2`（纯图片短消息气泡隐藏复制）（R-E7 两轮 APPROVE `@e25e057`；V-E7 首轮检出居中模态底缘位移 → 第 2 轮 29/29 探针 PASS `@e25e057`）→ `83e0948`
    - M7-4a Baseline Profile 基建与按需生成（`:baselineprofile` 离线回环生成、入库 `baseline-prof.txt`/`startup-prof.txt` 配合 `profileinstaller` 冷启 `FirstContent` 降 `-10.7%`）、开启 `isShrinkResources = true` 并以 `keep.xml` + 守卫测试保住 101 枚表情 PNG 与动态图标且提前清除 `R-M7-2 S2` 旧 Iconics 规则（R-M7-4a APPROVE `@85e3de0`；V-M7-4a PASS 含 API 36 离线冒烟 run `45840636512`）→ `adaa6c3`
    - M7-3 `targetSdk` 升至 `36`（JDK 21 单测运行器 + Java 17 编译产物、`POST_NOTIFICATIONS` 权限流程与开关/系统撤权双向同步及拒权降级、`MediaStore` 无存储权限下载/保存、全应用含看图/登录/账号/抽屉/编辑器 Edge-to-Edge 安全区与 `enableOnBackInvokedCallback`；R-M7-3 第 2 轮 APPROVE `@3f8bcd6`；V-M7-3 PASS 含 API 28–36 矩阵与 API 36 模拟器冒烟 run `45853162043`）→ `8a9afe1`
    - M7-4b R8 规则收紧（清除 `net.jejer.hipda.**`/`androidx.**` 过宽通配及已删旧库死规则、定向 `allowobfuscation` 保留 `com.kyant.backdrop`/`shapes` 核心类与行号表、新增 `ProguardRulesGuardTest` 死规则与 Release DEX/mapping 守卫，合并 `M7-4a` 后 Release APK 降至 `4,553,230 B` / 去重 `38,222` 方法；R-M7-4b APPROVE `@49c0876`；V-M7-4b PASS 含 API 36 release 离线全屏 30 张截图走查 run `45853222271`）→ `0916fb1`（**合并态 CI `45920948103`（`emulator=true`）与 main 推送 CI `45922502190` 全绿，M7 开发全部完成**）
    - M7-4c 按当前 main 由 CI 离线生成刷新 `baseline-prof.txt`/`startup-prof.txt`（清除 48 条已删 `HiSettingsHelper` 规则；生成 run `45928689128`，逐字节导入）、删除未引用的 `hipda/proguard-android.txt`、`ProguardRulesGuardTest` 扫描纳入 `:baselineprofile`，闭合 R-M7-4a S3 / R-M7-4b S1/S2（负责人核对 `@0ccf0e6`，CI `emulator=true` `45930117155` 绿）→ `b47836b`
    - M7-P1 看图与帖内图片收尾：HD 直开复用 `4096` 双轴上限防折叠屏超长图 OOM、多图横滑顶栏楼层/作者随页切换、同图多楼与同楼重复 URL 退场飞回所点缩略图，并精简 `feature/threaddetail/media/` 冗余重载 `-57` 行闭合 R-E2b S1 / V-E14 / R-M7-G2 S3（R-M7-P1 APPROVE `@fc63c2d`；修复轮 `@5324cee` 闭合 S1/S2 经负责人核对含变异红/绿，CI `45954360725` 绿）→ `4922caa`
    - M7-P2 链接/分享/零碎收尾：工具条外链复用 `AndroidExternalLinkOpener` 防自环、`navSeq` 支持同帖跳楼/直达末页返回后再触发且旋转不重跳、进程重建优先恢复已存阅读进度、单楼分享非回环统一 `https`、顶栏仅滚动/折叠时暴露滚顶语义、下钻用户资料取消 56dp Snackbar 余量、阅读位置读写加 `Mutex` 与崩溃日志单测补 `formhash` 闭合 V-E16 / R-M7-G3 S1-S2 / R-M7-G1 S3 / R-E6 O1 / R-E4 N1 / R-M7-2 S1（R-M7-P2 APPROVE `@8d17379`；修复轮 `@9f3ada6` 闭合 S1/S2 经负责人核对含变异红/绿；合并态 CI `emulator=true` `45956240271` 全绿）→ `f9862b8`
- **阶段验收状态**：
  - **M1 / M2 / M3 / M4**：均已验收通过或全部合入（M4 待用户用真机包调参回写 §5）。
  - **M5 阅读核心全部完成**（9/9 卡全部合入，`main @ 92e1563`，CI `45758541824` 绿；M5 真机验收清单已更新至 `docs/device-check.md`）。
  - **M6 页面群全部完成**（`E-slots` `4b6c1f0`、`E8` `0883c89`、`E12` `158ac9c`、`E10` `eb1ace1`、`E14` `1cffcd9`、`M6-polish` `0eebc8a`、`E16` `6ff55a9` 全部已合入）。
  - **编辑器线全部完成**（`E5` `afa3783`、`E6` `2739d4d`、`E6b` `b803846`、`E6b-followup` `08a43a0`、`E7` `83e0948` 全部已合入）。
  - **M7 开发与收尾全部完成**（`M7-0`、`M7-G1..G4`、`CI-heap` `ed361c2`、`M7-1` `846ff3a`、`M7-2` `4c7f6a0`、`M7-4a` `adaa6c3`、`M7-3` `8a9afe1`、`M7-4b` `0916fb1`、`M7-4c` `b47836b`、`M7-P1` `4922caa`、`M7-P2` `f9862b8` 全部已合入 `main`）；下一步进入 **`M7-5` 用户真机验收与发布（需用户批准）**。
- 已合并卡的卡片与报告持续归档至 `docs/agents/cards/`、`docs/agents/reports/`（本轮已归档 `M7-4c`、`M7-P1`/`R-M7-P1`、`M7-P2`/`R-M7-P2` 共 10 份卡报；`V-M7-4b-artifacts` 不入仓；`E6b-followup` 为轻量小修无独立卡报，核对记录见 `08a43a0`/`e9976d5`；`E2c`/`E-slots` 卡报位于工作副本待补档）；工作副本位于 `/home/mir/Documents/hipda-next.wt/_cards/`、`_reports/`，通用条款在 `_cards/_common.md`、`_review_common.md`、`_verify_common.md`。

## 2. 进行中与待派发

| 卡号 | 分支 / worktree | 状态 |
|---|---|---|
| M7-3（targetSdk 升至 36：通知权限、MediaStore 下载、全面屏；测试改 JDK21 launcher） | `card/M7-3-target-sdk-36` | **已合入 `main`（`8a9afe1`）** |
| M7-4a（Baseline Profile 基建与生成、`isShrinkResources = true` 与动态资源 keep 规则） | `card/M7-4a-baseline-profile` | **已合入 `main`（`adaa6c3`）** |
| M7-4b（R8 规则收紧与清除已删旧类规则） | `card/M7-4b-r8-tighten` | **已合入 `main`（`0916fb1`）** |
| M7-5（全新安装回归、用户真机验收与发布，**发布/正式签名包须先获用户批准**） | 清单已就绪（`docs/agents/cards/M7-5.md`，依赖 `M7-1`–`M7-4c`、`M7-P1`、`M7-P2` 已全部合入 `main @f9862b8`） | **待用户真机验收与批准** |
| M7-P1（看图收尾：V-E14 HD 超长图 4096 上限、R-M7-G2 S3 图集逐页楼层/作者与坐标键、R-E2b S1 精简 `media/`） | `card/M7-P1-image-polish` | **已合入 `main`（`4922caa`）** |
| M7-P2（链接/分享/零碎：V-E16、R-M7-G3 S1/S2、R-M7-G1 S3、R-E6 O1、R-E4 N1、R-M7-2 S1） | `card/M7-P2-links-polish` | **已合入 `main`（`f9862b8`）** |
| M7-5a（P0：登录成功被误判为「加载失败」、凭据未写入；成功判定对齐旧版、会话确认、失败清 Cookie、失败原因分文案 + debug 脱敏日志 `HxLogin`） | `card/M7-5a-login-fix` | **已合入 `main`（`30ddae7`）**：R-M7-5a APPROVE、V-M7-5a PASS，修复轮 `@99a7808` 负责人核对（变异红/绿） |
| M7-5b（玻璃四组定稿参数、看图保存提示、分享响应、外链 Snackbar Long、CI 上传 `release-apk-testsigned`） | `card/M7-5b-device-polish` | **已合入 `main`（`3378204`）**：R-M7-5b APPROVE `@5298f4f` |
| U1（三栏地平线 56dp、左栏与中栏列表克制化：删图、内联新、修作者截断、精简日期、内缩圆角选中与 0.5dp 分隔线） | `card/U1-shell-list-polish` | **已合入 `main`（`3a1f5b1`）**：R-U1 APPROVE `@44810ce`（73 张截图目视），CI `46046699288` 绿 |
| U2（右栏详情去网页化：56dp 单行顶栏纯图标、楼层号去蓝化、底栏互换伸缩段改弱化 `{N}楼`、单页被动多页展跳页） | `card/U2-detail-deweb-polish` | **已合入 `main`（`47dc302`）**：R-U2 APPROVE `@239a168`（62 张截图目视）、V-U2 PASS（CI `46054726868`），CI `46051574839` 绿 |
| U3（快速回复与完整编辑器克制化：去文案重复、32dp 发送胶囊保 48dp 热区、空态文字态保校验、模态 `×` + 0.5dp 描边） | `card/U3-compose-polish` | **已合入 `main`（`ed59fb6`）**：R-U3 APPROVE `@fdd73ac`/`@7171380`（含 18 张合并态截图目视）、V-U3 PASS（CI `46050319847`），CI `46057585997` 绿；合并后 `V-U-int` 端到端集成探针 PASS（CI `46061080252`） |
| OPS-1 负责人助理（文档维护 / 起草卡片 / 核对修改轮） | 主目录 `main`（纯文档） | 常驻复用中 |

- 本机有 Android SDK（mise 安装，见 `lesson.md`），可本地串行录制 Roborazzi 基线；流程提速规则已生效（`5bae5ab`）。
- 外部仓库待确认：`team-lead` skill 已提交 `5a2fc95`，等待用户确认是否推送。

## 3. BATCH-4 / BATCH-5 / M6 计划与后续主干路径

- **BATCH-4 计划（已全部合入收官，归档至 `docs/agents/cards/BATCH-4.md`）**：12 张卡、5 波全部合入 `main`。
- **BATCH-5 计划（M5 全部完成，归档至 `docs/agents/cards/BATCH-5.md`）**：9 卡 4 波全部合入 `main`（`E0`、`E15`、`E2a`、`E1`、`E2b`、`E2d`、`E3`、`E2c`、`E4 @ 92e1563`，main CI `45758541824` 绿），已出 M5 真机验收包。
- **M6、编辑器线与 M7 开发全部完成**：**M6、编辑器线（`E5`/`E6`/`E6b`/`E6b-followup`/`E7`）与 `M7-0`～`M7-4c`、`M7-P1`、`M7-P2`（含 `M7-4a` `adaa6c3`、`M7-3` `8a9afe1`、`M7-4b` `0916fb1`、`M7-4c` `b47836b`、`M7-P1` `4922caa`、`M7-P2` `f9862b8`）已全部合入 `main`**（`HEAD` `@f9862b8`，合并态 CI `45956240271` `emulator=true` 全绿，main 推送 CI `45958045820` 绿）；下一步进入最终 **`M7-5` 用户真机验收与发布（需用户批准）**。
- **待观察风险**：
  - **Cloudflare 桌面 UA 下 WebView 过验证尚未在真机触发**：A5c 切换为桌面 Linux Chrome UA + Client Hints（`platform=Linux`、`mobile=false`）后，真机 M1 请求均直接 200 通过、未触发 Cloudflare 人机验证；该 UA 组合下的 WebView 自动/人工过盾与 `cf_clearance` 同步路径尚未经真机实测，列为待观察风险，后续真机首次触发验证时重点核实。
  - **安全提问报错文案格式（`R-E15 S5`）**：若真机 4d4y.com 对安全提问错返回不含 `"登录失败"` 前缀的原文，需记录原文以供 `LoginParser` 补配（已列入 `docs/device-check.md` 第 1 项）。
  - **快速惯性滑行途中上方图片加载完的顿停观察（`R-E2b S2`）**：`PostImageBlock` 补偿调用 `requestScrollToItem` 在 fling 途中可能抢占 `MutatorMutex` 打断惯性滑行，已列入 `docs/device-check.md` 第 6 项真机观察。
  - **Kyant `backdrop` 同屏 3 块液态玻璃 120Hz 帧率**：用户决定将同屏实时玻璃上限由 `2` 放宽至 `3`（`0895b83`），须在真机 120Hz 实测帧率并验证无掉帧（已列入 `docs/device-check.md` 第 17 项）。

## 4. 派发后续卡时必须带入的审查遗留建议（尚待处理项）

> 已合入闭环的条目（含 R-B1c S1/S2、A1 S4、B9a/B9b/B8/B7/D1/D2/D4/D5/D6/D7/D8a/D8b/D9/G1/G2/C1b#1/#3/C4c/C4d/C5b/A6/E0/E1/E2a/E2b/E2d/E2c/E3/E4/E5/E6/E6b/E6b-followup/E7/E-slots/E8/E10/E12/E14/E15/E16/D-cleanup/M6-polish/M7-G1/M7-G2/M7-G3/M7-G4/M7-1/M7-2/R-M7-2 S1-S2/R-M7-3/R-M7-4a S1/S3/R-M7-4b S1-S2/M7-3/M7-4a/M7-4b/M7-4c/M7-P1/M7-P2/R-M7-P1 S1-S2/R-M7-P2 S1-S2 全部已闭合项）已移除，下面为**进行中或尚未落地**的条目：

- **→ “毛玻璃定稿”小卡（待用户用真机 debug 实时调参面板给出最终参数后派发）**：
  - 将用户拍板的最终液态玻璃参数与同屏上限 `3` 回写 `docs/design/product-design.md` §5。
- **→ 真机观察（见 `docs/device-check.md`）**：
  - R-E2b S2：快速惯性滑行（fling）途中首楼越顶图片解码完成触发 `requestScrollToItem` 是否打断惯性顿停；若真机可感知则评估不中断手势的偏移修正策略。
  - R-E15 S5：安全提问回答错误时记录真实站点返回原文，确认是否缺 `"登录失败"` 前缀。
- **→ M7-5（真机验收备忘）**：
  - `R-M7-1 S2`：`MainActivity` 为 `launchMode="singleTop"`，外部应用不带 `NEW_TASK` 经 `.ng.IntentActivity` 拉起时留在调用方任务栈（状态按 Activity 隔离，返回回原应用）；`M7-5` 真机验收按用户偏好确认是否需调整。
  - `R-M0-2 R4`：`M7-5` 真机阶段核验 120Hz 帧时间与卡顿率（含三栏 3 块实时液态玻璃）及全新安装回归；**构建正式签名包或发布前必须先获用户批准**。
- **→ 后续轻修备忘**：
  - R-M7-5b S1：看图链接白名单后续统一委托 `SafeLinkPolicy`；S2：退出看图时提前取消 `shareJob`。
  - R-M7-4a S2：**不采纳**（负责人 2026-11-27）：`if (resourcesReport.isFile)` 保持条件检查——本地只跑单测时没有 release 产物，CI 先 `assembleRelease` 再跑单测已守住。
  - R-M7-P1 S3：日后改 `PostBodyRenderer.kt` 可直传楼内图片序号 `imageIndexInFloor`，省去 `ImageViewerBoundsRegistry` 的 `duplicateSlots`（约 25 行）。
  - U2 孤儿令牌清理：中段阅读进度条移除后，`HiGlassTokens` 的 `ProgressFill*` 常量暂保留不动以守住 `HiGlassScreenshotTest` 已拍板玻璃样张，后续统一清理。
  - 按楼层估算跳转（Backlog）：`InlinePageJumpBar` 当前保留按页跳转，日后可评估按估算楼层换算页码并定位楼层。
  - R-U1 S1/S2：`ThreadListRow.kt` 宽度变化分支可直接赋 `result.hasVisualOverflow` 并在 `remember` 补 `LocalDensity.current`；`ForumScreenshotTest` 可补 1–2 条 `yyyy-M-d HH:mm` 精简日期截图样例。
  - R-U2 S1/S2：`ThreadDetailTopBar` 未使用形参 `authorName` 后续可随调用方一并清理；`ThreadBottomToolbar` 跨楼层位数边界（`9↔10`/`99↔100`）时 `leftWidth` 可按需与中段同步弹簧。
  - R-U3 S1/S2：`QuickReplyTestSupport.kt` 可用 `useUnmergedTree` 读子 `Surface` 高度省去 `LayoutInfo` 反射；`FullEditorIntegrationTest.kt` 颜色对照改 `toArgb()`。

---

## 5. 用户要做的事

1. **M7-5 全新安装回归与真机 120Hz 验收 + 液态玻璃四组调参（最新包 `main` @ `f9862b8`，CI `45958045820` `debug-apk`；旧包 `0916fb1`/`92e1563`/`db52669`/`c0fc15d` 留作对照）**：按 `docs/agents/cards/M7-5.md` 与 `docs/device-check.md` 验证全新安装、图标与快捷方式、`.ng.IntentActivity` 外链防自环、通知权限、`MediaStore`、全面屏、读帖/发帖链路及 120Hz 三栏 3 块液态玻璃帧率与真实 Android Keystore 下的 Tink 凭据/Cookie 加密读写恢复，并用同一包「HiPDA 预览 → 外壳 → 调参」给出四档最终参数回写 §5（写操作项均为可选）。
2. **确认 `team-lead` skill（`5a2fc95`）是否推送**。
3. **正式发布批准门禁**：M7-5 全部通过且性能达标后，决定是否批准构建正式签名 release 包或发布。

---

## 6. 空闲的历史会话
第一至三批的实现、审查、验证与方案会话均已空闲，可按需复用做小修（ID 见 git 历史中本文件的早期版本 `6a8d5dd`）。
