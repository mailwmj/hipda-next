# R-M7-G2 独立代码审查报告

- **审查结论**：**`APPROVE`**（0 阻断项，4 项非阻断建议 / M7-2 切换提醒）
- **审查对象**：`origin/card/M7-G2-system-account-viewer-about` @ `4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf`（功能提交 `9a3df7037cdea9d8a732995071fe4cc72d96bd93`，测试内存收口提交 `4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf`）
- **基线提交**：`origin/main` @ `9338529070a9df6c5feeed5bb281ffba411abf35`
- **CI 核验**：[Android CI #45773214724](https://github.com/mailwmj/hipda-next/actions/runs/45773214724)
  - `headSha == 4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf`，`status == completed`，`conclusion == success`；
  - 已核实通过：`Build and lint`（`assembleDebug`、`assembleRelease`、`lintDebug`）、`Verify Room schema consistency`、`Run unit tests and verify Roborazzi screenshots`（`:hipda:testDebugUnitTest` + `:core-parser:test -Proborazzi.test.verify=true`，`1461/1461` 全绿，Roborazzi diff 上传步骤因 0 差异自动 `skipped`）、`Verify APK outputs`。
- **生产代码规模**：9 个生产文件 `+424 / -13`，净增 **411 行**（位于卡片预计 `320–480` 行区间内，低于 `720` 行 1.5× 警戒线）；0 手写 `Layout` / `Modifier.Node` / 自定义状态机，0 测试专用语义键或 CompositionLocal。
- **主目录只读核验**：全程未在 `/home/mir/Documents/hipda-next` 执行 checkout、写文件、提交、推送或编译。

---

## 1. 负责人 5 项补充核查逐项详答

### (1) `#14` 冷启动 `syncFromSettings` 位置、启动频次（含通知/深链冷启动）与 WorkManager 幂等性
1. **代码落点**：
   - 位于 `hipda/src/main/java/net/jejer/hipda/feature/app/NextAppHostViewModel.kt:69-81,115-125`。
   - `NextAppHostViewModel` 通过 `@HiltViewModel` `@Inject` 构造函数注入 `NotificationScheduler`（由 `di/NotificationModule.kt` 绑定单例 `WorkManagerNotificationScheduler`），在 `init` 块中执行 `viewModelScope.launch { scheduler.syncFromSettings() }`（捕获非取消异常，重抛 `CancellationException`）。
   - **严格 0 改动** `HiApplication.java` 与 `HiNextApplication.kt`，符合卡面硬停止条件。
2. **是否每次进程启动恰好一次（含通知/深链冷启动）**：
   - **进入新版宿主（`NextAppActivity`）的所有冷启动路径均恰好执行 1 次**：`NextAppActivity.onCreate`（`hipda/src/debug/java/net/jejer/hipda/debug/next/NextAppActivity.kt:59-68`）无论由桌面启动（`createIntent`）、深链（`createDeepLinkIntent`）、桌面快捷方式（`createShortcutIntent`）还是系统通知（`createNotificationIntent`）冷启动，都会初始化 Activity 级 `hostViewModel` 恰好一次，触发 `init` 中 `syncFromSettings()` 1 次。
   - **旋转/折叠配置变更与 `singleTop` 热启动不重复执行**：旋转时 Activity 复用 `ViewModelStore`，`NextAppHostViewModel` 不重建；宿主位于栈顶收到新深链/通知走 `onNewIntent`（`NextAppActivity.kt:91-95`）时同样复用已有 ViewModel，均不会二次调用 `syncFromSettings()`（已由 `StartupNotificationAndReleaseNotesTest.kt:265-274` 实测旋转 `syncFromSettingsCalls == 1`）。
   - **边界说明（见建议 S2）**：
     - 因受卡面约束放在 Activity 级 `ViewModel.init` 而非 `Application.onCreate`，若用户按返回键彻底销毁宿主 Activity（进程仍留驻后台缓存）后再次重新拉起 Activity，新 Activity 会新建 `NextAppHostViewModel` 再执行一次 `syncFromSettings()`；纯后台 Worker 唤醒进程不拉起 UI 时则不经过 `NextAppHostViewModel`（此时周期任务本已在 WorkManager 队列中）。
     - 在 `M7-2` 正式切换入口前，现网 `src/main/AndroidManifest.xml:26-63` 的 `MAIN/LAUNCHER`、站内链接 `IntentActivity` 及 `NotificationPoster.kt:51` 的通知 `PendingIntent` 仍指向旧版 `MainFrameActivity` / `IntentActivity`（旧链路走 `MainFrameActivity.java:169`）；`M7-2` 删旧切新时将三处入口统一改指新宿主 Activity（建议 `launchMode="singleTask"`）后，全部冷/热启动路径即完整收口至 `NextAppHostViewModel`。
3. **是否会重复排队 WorkManager 任务**：
   - **绝不会重复排队**。`WorkManagerNotificationScheduler.schedule()`（`hipda/src/main/java/net/jejer/hipda/service/NotificationScheduler.kt:73-80`）调用：
     `enqueueUniquePeriodicWork("hipda_next_notification_polling", ExistingPeriodicWorkPolicy.KEEP, request)`。
   - `ExistingPeriodicWorkPolicy.KEEP` 保证当同名唯一周期任务已处于 `ENQUEUED` 或 `RUNNING` 时直接保留原调度（既不产生重复 Worker，也不重置 15 分钟计时周期）；关闭开关时 `cancelUniqueWork("hipda_next_notification_polling")` 同样幂等。`StartupNotificationAndReleaseNotesTest.kt:114-171` 已用真实 `WorkManagerTestInitHelper` 断言队列中唯一任务数恒为 `1`。

---

### (2) `#13` 移除非当前账号：Cookie / 凭据 / 头像缓存清理范围、当前账号隔离与二次确认
1. **二次确认步骤**：
   - **具备完整二次确认且无违禁词**。`AccountManagementOverlay.kt:178,196-206` 仅对 `!isCurrent`（`account.uid != uiState.currentAccount?.uid`）渲染 `48×48dp` `×` 按钮（无障碍描述 `清除 %1$s 的登录信息`）。
   - 点击后仅设置 `confirmRemoveSaved = account`（`AccountManagementOverlay.kt:199`），弹出 Material 3 `AlertDialog`（`AccountManagementOverlay.kt:253-260`）：标题 `清除 %1$s 的保存密码？`，按钮 `取消` / `清除`（错误色强调）。点 `取消` 或对话框外关闭不触发任何删除；点 `清除` 才调用 `AccountManagementViewModel.removeSavedAccount`（`AccountManagementOverlay.kt:91-107`），完成后留在本页并弹出 Snackbar `已清除 %1$s 的登录信息`。
2. **凭据（Credentials）**：
   - **仅清除目标非当前账号凭据，不影响当前活跃账号**。`DefaultAuthRepository.removeAccount(uid)`（`DefaultAuthRepository.kt:434-477`）判定 `removingActive = (beforeSnapshot.activeUid == targetUid)` 为 `false`，走 `454-475` 分支调用 `credentialsStore.remove(targetUid)`（`CredentialsStore.kt:187-199`），从 Tink AEAD 加密的 `hx_credentials` DataStore 中仅过滤掉目标 `uid`，`activeUid` 与 `manuallyLoggedOut` 保持原值不变；若 `rejectedCredentialKey` 恰属该 `targetUid` 一并清理。
3. **Cookie（`SiteCookieStore` + `WebView` + `cf_clearance`）**：
   - **非当前账号本无独立持久化 Cookie，且当前账号活跃 Cookie 100% 完好保留**。
   - 新版架构中 `DataStoreSiteCookieStore`（`hx_site_cookies`）与系统 `WebViewCookieAccess` 均为**单当前会话级**（按 `domain/path/name` 存储，不按多账号分桶；切号时先清旧会话再用保存的密码重登）。因此非当前账号在本地不存在任何残留会话 Cookie。
   - 在 `DefaultAuthRepository.removeAccount(uid)` 中，仅 `removingActive == true`（`441-453`）会调用 `clearAuthCookiesForServer`；移除非当前账号（`454-475`）**不触碰** `SiteCookieStore` 与 `WebViewCookieAccess`，当前活跃账号的 `cdb_auth`、`cdb_sid` 及站点 `cf_clearance` 均不受丝毫影响（已由 `AccountManagementTest.kt:201-261` 对双端真实存储替身实测验证）。
4. **头像缓存（Avatar Cache）**：
   - **未单独清除该账号头像缓存（与旧版一致，且不影响当前账号）**。
   - 对照旧版 `HiSettingsHelper.java:437-447`（`removeProfile(username)`），旧版同样只移除加密配置中的账号条目而不清理头像文件缓存。
   - 新版账号管理页（`AccountManagementOverlay.kt:186-188`）使用首字母徽标展示账号，不加载网络头像；全局帖子楼层/短消息/资料页的用户头像均为论坛公开 UCServer URL（`uc_server/data/avatar/...`），由公共 Coil `ImageLoader` 按 URL 缓存供全站帖子共用，不含任何凭据或私有鉴权状态，移除保存密码无需亦不应驱逐公开头像缓存。

---

### (3) `#9` 图集接口在 Provider 未提供时的单图退化与防崩核验
1. **未提供 Provider 时的退化路径**：
   - `LocalThreadImageGallery`（`ThreadImageGallery.kt:20`）默认值为 `emptyList()`。
   - 当外层未包裹 `CompositionLocalProvider(LocalThreadImageGallery provides ...)`（包括当前 `ThreadDetailPane.kt` 尚未由 `M7-G3` 接线的现态及既有独立单测）时，`PostImageBlock.kt:171` 读取 `threadGallery` 为 `emptyList()`。
   - 点击已加载图片时（`PostImageBlock.kt:311-320`），`verifiedViewerUrl` 非空才放行，传入 `buildGalleryLaunchArgs`（`ThreadImageGallery.kt:47-80`）：
     - `safeGallery` 为空 → 命中第 `56-62` 行分支，直接返回 `ImageViewerLaunchArgs(urls = listOf(clickedUrl), initialIndex = 0, sourceBoundsInWindow = sourceBoundsInWindow)`；
     - 即使外部提供了非空 `gallery` 但因异步刷新等原因未命中 `clickedUrl`（`matchedIndex < 0`，第 `66-72` 行），同样安全回退单图 `urls = listOf(clickedUrl), initialIndex = 0`。
   - 下游 `ImageViewerOverlay.kt:90-91` 另有 `totalPages = args.urls.size.coerceAtLeast(1)` 与 `initialIndex.coerceIn(0, totalPages - 1)` 双重保护，展示 `1 / 1`，**严格零崩溃、零越界**（已由 `ThreadImageGalleryTest.kt:224-253` 断言验证）。
2. **重复 URL 精确定位与三层白名单**：
   - `buildGalleryLaunchArgs`（`ThreadImageGallery.kt:63-65`）优先按 `it.floorPostId == floorPostId && it.url == clickedUrl` 匹配，跨楼层出现相同图片 URL 时仍能精确定位到被点击楼层的 `initialIndex`（若同一楼层内贴了两张完全相同 URL 的图，则会落在该楼层第 1 张同 URL 图，属合理边界）。
   - 所有图片 URL 依次经过 `extractThreadGalleryItems`（`ThreadImageGallery.kt:32-33`）、`buildGalleryLaunchArgs` 纵深二次过滤（`ThreadImageGallery.kt:53-55`）与 `ImageViewerOverlay`（`sanitizeViewerImageUrl`）三道 `http`/`https` 严格白名单（拒绝 `javascript:`/`intent:`/`file:`/`content:`/`data:`/`mailto:`/`//`/前后空白/控制字符），且自动跳过 `isAuthorBlocked` 屏蔽楼层。

---

### (4) `#12` 首启更新记录弹窗：按 `versionCode` 仅一次 & 全新安装行为与旧版对照
1. **按 `versionCode` 仅一次与旋转防重弹**：
   - `SharedPrefsReleaseNotesPromptStore`（`ReleaseNotesPromptStore.kt:25-51`）在 `Dispatchers.IO` 读写 `hipda_next_release_notes` 的 `last_seen_version_code`（`commit()` 同步落盘；非加密普通整型且不含 `hx_` 前缀，兼容 `BackupExclusionRulesTest`）。
   - `NextAppHostViewModel.init`（`NextAppHostViewModel.kt:126-141`）在 `currentVersionCode > 0` 且 `lastSeen != currentVersionCode` 时置 `_showReleaseNotesPrompt.value = true`；用户点「关闭」或按系统返回触发 `AlertDialog.onDismissRequest` 调用 `dismissReleaseNotesPrompt()`（`NextAppHostViewModel.kt:145-158`），立即置内存标志 `releaseNotesPromptDismissed = true`、关闭弹窗并持久化 `currentVersionCode`。同版本后续冷启动及屏幕旋转均不再弹出；版本升至新 `versionCode` 后冷启动再弹一次。
   - 测试夹具中 `NextAppHostViewModel` 次级构造函数默认 `releaseNotesPromptStore = null`（`NextAppHostViewModel.kt:83-90`），确保全部既有 UI/Roborazzi 测试默认不弹窗、0 快照污染。
2. **全新安装是否弹窗（与旧版逐行对照）**：
   - **本卡新版行为：全新安装首次启动【会】弹出一次更新记录**。原因：全新安装时 `getLastSeenVersionCode()` 默认返回 `0`（`ReleaseNotesPromptStore.kt:36`），满足 `0 != BuildConfig.VERSION_CODE`（`NextAppHostViewModel.kt:136`）。**注：这与任务卡 `M7-G2.md:39-40` 的明文规格完全一致**（卡面明确要求：`首次启动（lastSeen = 0, current = 5005）弹出更新记录 → 关闭后持久化 5005`）。
   - **旧版真实行为：全新安装首次启动【不】弹出更新记录，仅升级安装后首次启动弹出**。旧版链路：
     1. 全新安装时 `HiSettingsHelper.getInstalledVersion()` 返回 `""`（`HiSettingsHelper.java:981-983`）；
     2. `HiApplication.onCreate` 调 `UpdateHelper.updateApp()`（`UpdateHelper.java:178-192`），写入 `setInstalledVersion(currentVersion)` 并返回 `newer(currentVersion, installedVersion)`；
     3. `UpdateHelper.newer(version1, "")` 第 `166-167` 行 `if (TextUtils.isEmpty(version2)) return false;` 直接返回 `false`，故全新安装 `HiApplication.isUpdated() == false`，`MainFrameActivity.java:171` **不弹窗**；仅当旧 `installedVersion` 非空且新版本更大时才弹一次。
   - **评估（非阻断建议 S1）**：实现者严格执行了 `M7-G2.md` 卡面契约（非实现偏离）。此外从旧版 `5.0.05` 升级到新版时旧版未写 `hipda_next_release_notes`，`lastSeen == 0` 能保证大版本升级首次进新壳时弹出更新记录（若简单把 `0` 当静默跳过反而会导致旧版升新版漏弹）。若负责人希望区分“真全新安装不弹”与“旧版升级/新版升级必弹”，详见建议 S1。

---

### (5) `git merge-tree` 与最新 `origin/main` 及 `origin/card/M7-G1-list-topbar-profile` 冲突核查
1. **与 `origin/main`（`9338529070a9df6c5feeed5bb281ffba411abf35`）**：
   - `git merge-tree --write-tree origin/main 4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf` → **`exit=0`**（树 `a77ee1601c4ad2c020590fa6e3f07d8f78ba76ec`，纯 fast-forward，**0 冲突**）。
2. **与已推送 `origin/card/M7-G1-list-topbar-profile`（`10e7f35e2b9ab1658b1a2794cda899a0240a04f4`）**：
   - `git merge-tree --write-tree origin/card/M7-G1-list-topbar-profile 4b3c1b9dd1f6fd2ee29bcfaf6d912c699e5f1fcf` → **`exit=0`**（合并树 `0771edd2dbc10622df6022ca091169934ea055fa`，自动合并唯一交集文件 `NextAppHostViewModel.kt`，**0 文本冲突**）。
   - **合并后语义/编译兼容性复核**：已人工逐行检查合成后的 `0771edd2d:NextAppHostViewModel.kt` 与 `M7-G1` 新增测试 `OpenThreadLastPageRoutingTest.kt`：
     - `M7-G2` 修改 `NextAppHostViewModel.kt` 顶部构造/`init`/`dismissReleaseNotesPrompt`（第 `60-158` 行），同时保留了向后兼容的二参次级构造 `constructor(settingsRepository: SettingsRepository, threadDetailRepository: ThreadDetailRepository)`（第 `83-86` 行）；
     - `M7-G1` 修改中下部 `openThread`/`openThreadLastPage`/`resolveThreadTargetAsync`（第 `307-490` 行），并在 `OpenThreadLastPageRoutingTest.kt:55` 调用上述二参构造；
     - 两处改动互不干扰，**0 文本冲突且 0 语义/构造签名冲突**。
3. **顺带核查与已推送 `origin/card/M7-G4-messages-compose-copy`**：
   - `git merge-tree --write-tree` 同样 **`exit=0`**（树 `fab430fadd80b9a52e857da82167529845956cc5`，无交集文件，**0 冲突**）。

---

## 2. 通用门禁、测试强度与截图目视核验

### 2.1 边界、规范与撤回即红单测
- **并行边界与禁止文件**：`HiApplication.java`、`HiNextApplication.kt`、`ThreadDetailPane.kt`、`NextAppHostViewModel.openThread`、`feature/threaddetail/{interact,body}/*`、`feature/{forum,mine,search,profile,messages,common}/*`、共享构建与清单文件、`MotionTokenScanTest` / `CopyTerminologyScanTest` 白名单均 **0 改动**。（注：卡面笔误写 `strings_settings_profile.xml`，仓库实际对应文件为 `res/values/strings_settings.xml`，追加在既有账号管理词条组内完全合规。）
- **撤回即红单测**：新增/扩展 5 个测试类共 15 个用例（`AccountManagementTest`、`AboutAssetDocumentsTest`、`ThreadImageGalleryTest`、`ImageViewerMultiImageGalleryTest`、`StartupNotificationAndReleaseNotesTest`），覆盖真实资产 IO 读取、`Apache License 2.0` / `Backdrop` 呈现、异常不泄漏、真实 `DefaultAuthRepository` Cookie 隔离、真实 `WorkManager` 入队/取消、跨楼层重复 URL 索引与手势横滑、版本持久化和旋转不重弹；任一核心逻辑撤回即红，无空心断言，0 真实外网访问。

### 2.2 Roborazzi 快照零漂移与逐张目视结果
- **既有非授权快照 0 漂移**：`git diff --name-status 9338529...4b3c1b9` 仅含下列 4 张预期截图变动，`multipane_*.png`、`forum_*.png`、`thread_*.png`、`messages_*.png`、`search_*.png`、`mine_*.png`、`image_viewer_*.png` 及其余 7 张 `settings_*.png` **严格 0 漂移**。
- **逐张目视结论（4/4 通过）**：
  1. `hipda/src/test/snapshots/images/account_w411_light.png`（更新）：对照基线图，当前账号 `hipda_veteran`（含 `当前账号` 副标）无行内删除图标；非当前账号 `geek_alt` 右侧新增 `×` 按钮，垂直居中、触控留白充裕，底部 `添加账号` / `退出登录` / 红色 `清除登录信息` 层次清晰，无裁切或重叠。
  2. `hipda/src/test/snapshots/images/account_w411_dark.png`（更新）：深色模式下非当前账号 `geek_alt` 右侧 `×` 图标与容器对比度清晰，当前账号无多余图标，排版与浅色严格一致。
  3. `hipda/src/test/snapshots/images/settings_about_w411_light.png`（新增）：「关于」页浅色模式依次呈现 `版本 5.0.05`、`客户端发布帖 >`、`更新记录 >`、`捐助名单 >`、`开源许可 >` 及底部 `错误日志`（`查看` / `复制` / `分享`），字体清晰、行高一致、箭头对齐。
  4. `hipda/src/test/snapshots/images/settings_about_w411_dark.png`（新增）：「关于」页深色模式主副文字、右向箭头与操作按钮对比度良好，无截断或溢出。

---

## 3. 阻断项与非阻断建议

### 阻断项（Blocking）
- **无**。

### 非阻断建议与后续卡衔接提醒（Non-Blocking）
1. **S1（产品细节确认 / M7-2 模拟器冒烟提醒 — `#12` 全新安装首启弹窗）**：`NextAppHostViewModel.kt:136` 按卡面 `lastSeen = 0` 规格在全新安装首次启动时也会弹一次 `更新记录`（旧版 `UpdateHelper.java:166-167` 在 `installedVersion == ""` 时不弹）。`M7-2` 将新宿主设为 `MAIN/LAUNCHER` 后，全新安装模拟器测试首屏会出现该对话框（点返回/关闭即消，不崩溃）；若希望纯全新安装静默写入当前 `versionCode` 而不弹窗（仅在旧版 `PERF_INSTALLED_VERSION` 非空或 `lastSeen > 0` 升级时弹窗），可在 `M7-2` 顺带微调 `ReleaseNotesPromptStore` 初值逻辑。
2. **S2（M7-2 切新收口提醒 — `#14` 通知与深链入口汇流）**：`M7-2` 移除旧 `MainFrameActivity` / `IntentActivity` 时，需确保 `AndroidManifest.xml` 启动与站内深链入口及 `NotificationPoster.kt:51` 的通知 `PendingIntent` 全部指向新宿主 Activity，并建议将新宿主设为 `singleTask`，保证通知/深链唤醒复用同一宿主与 `NextAppHostViewModel`。
3. **S3（`M7-G3` / 体验收尾建议 — `#9` 跨楼层横滑标题与重复 URL 退场坐标）**：受本卡不改 `feature/common/*` 边界限制，`ImageViewerLaunchArgs`（`ReadingNavContracts.kt:62-68`）仅带单组 `floorNumber`/`author`（横滑到其他楼层图片时页码更新但顶栏仍显示初始点击楼层作者），且 `ImageViewerBoundsRegistry`（`FeaturePaneSlots.kt:124-129`）仅按 `url` 索引缩略图坐标；后续可在允许触碰 `feature/common/` 的收尾卡按页扩展元数据与坐标键。
4. **S4（文档同步建议 — `docs/design/copy.md`）**：`strings_settings.xml:66-68,94-96` 新增的关于三项文档标题与非当前账号清除提示完全符合卡面与术语门禁（0 违禁词），后续文档归档时可顺带同步登记进 `docs/design/copy.md`。
