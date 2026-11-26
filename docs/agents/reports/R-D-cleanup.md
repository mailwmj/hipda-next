# 独立代码审查报告 R-D-cleanup：数据层、后台轮询与零冲突小修收尾（commit `230e88d`）

- **审查结论**：**`APPROVE`**（0 项阻断，1 项跨卡非阻断建议 `S1`）
- **审查对象**：`origin/card/D-cleanup-followups` @ `230e88d3d9f558f8d9036d3e474eb5714a005e6a`（简称 `230e88d`）
- **比较基线**：`fbeeb2863a5ca213f013bdae735ef6c88bacbdb4`（`origin/main`，已含 `E2d`）
- **并行合入预检**：
  - 对照 `origin/main`（`fbeeb28`）：`git merge-tree --write-tree origin/main 230e88d` 输出树 `3b5e5c3cadb07c772df44249978df5badc020dd7`（快进无冲突）
  - 对照在途 `origin/card/E3-multipane-container`：`git merge-tree --write-tree 230e88d origin/card/E3-multipane-container` 输出树 `46c6b95912124d848b41863f3918e3675600b366`（退出码 `0`，**0 冲突、0 重叠文件**）
- **GitHub Actions CI 核实**：[Android CI #45715881101](https://github.com/mailwmj/hipda-next/actions/runs/45715881101)
  - `headSha`：`230e88d3d9f558f8d9036d3e474eb5714a005e6a`（与分支提交 100% 精确匹配）
  - Job `Build, Lint & Unit Tests`（ID `139561675082`）：**`completed / success`**
  - 原始日志逐项核实通过：`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`、Room schema 一致性检查、`:hipda:testDebugUnitTest`（`1028/1028`）+ `:core-parser:test`（`98/98`）全量 **`1126/1126 passed, 0 failed, 0 errors, 0 skipped`**、`-Proborazzi.test.verify=true`（`:hipda:finalizeTestRoborazziDebug` 零差异，`Check for Roborazzi diff images` skipped）、`ContinuousPagingAnchorTest` `15/15`、`PostImageBlockTest` `8/8`、`PostImageScreenshotTest` `6/6`，以及 Debug/Release APK `net.jejer.hipda.next` 包名校验。

---

## 一、阻断项（Blockers）

**无**（对照 `docs/agents/DISPATCH.md` §4、`R-D-cleanup.md` 阻断标准与【负责人裁定】逐项核查全部通过）。

---

## 二、文件边界、裁定执行与零快照漂移核查

`git diff --numstat fbeeb28...230e88d` 共修改 **10 个文件**，生产代码 **4 个文件（`+4 / -5` 行）**、测试代码 **6 个文件（`+762 / -8` 行）**，总计 **`+766 / -13` 行**：

| 类别 | 文件路径 | 行数变化 | 对应收口项 |
|---|---|---:|---|
| 生产 | `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt` | `+1 / -1` | `R-D6 S3` |
| 生产 | `hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt` | `+1 / -2` | `R-D9 S1` |
| 生产 | `hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt` | `+1 / -1` | `R-E1` 备注 1（纯 KDoc） |
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt` | `+1 / -1` | `R-E1` 备注 2（仅 `hasMore`） |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/ImageUploadRepositoryTest.kt` | `+121 / -0` | `R-D6 S3` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/PostRepositoryTest.kt` | `+177 / -0` | `R-D6 S4 (a)(b)` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/ImageCompressorTest.kt` | `+215 / -0` | `R-D6 S4 (c)` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt` | `+87 / -0` | `D5-fixcheck` 微注 |
| 测试 | `hipda/src/test/java/net/jejer/hipda/feature/forum/ForumViewModelTest.kt` | `+56 / -3` | `R-E1` 备注 2 |
| 测试 | `hipda/src/test/java/net/jejer/hipda/service/NotificationPollingWorkerTest.kt` | `+106 / -5` | `R-D9 S1` |

- **【负责人裁定】核对**：`feature/threaddetail/media/*` 及其测试文件改动数为 **`0`**（严格执行裁定删除 `R-E2b S1`，未触碰 `PostImageBlock.kt`、`ImageLoadingPolicy.kt`、`ImageAspectRatioCache.kt`，`E2d` 的 `LocalAboveViewportHeightCompensationAuthority`、同帧 `classifyHeightChangeBranch` 与 `http`/`https` 白名单 100% 保持基线原状）。
- **禁改区与并行边界核对**：`E2c`、`E3`、`E4` 归属文件、`hipda/src/test/snapshots/` 全部 PNG、`:core-parser`、共享构建文件（`libs.versions.toml`、`*build.gradle.kts`、`AndroidManifest.xml`、`.github/`）及 `MotionTokenScanTest` 均为 **`0` 改动**。
- **离线测试合规扫描**：全 diff 扫描确认 `0` 处 `Thread.sleep`、`0` 处真实站点 `4d4y.com` 访问，全部网络用例均走本地 `MockWebServer`。

---

## 三、逐项闭合核实与“回退必红”单行变异推演

### 1. `R-D6 S3`：`DefaultImageUploadRepository` `#umenu` 权威判定收口
- **生产代码核实**（`DefaultImageUploadRepository.kt:386-395`）：
  - 在排除 `DISCUZUPLOAD` 正常回包（`:387`）后，凡响应命中 `id="umenu"` 或 `id='umenu'`（忽略大小写，`:388-390`），现于 `:392` 直接 `return !meta.session.isLoggedIn` 短路返回，不再在 `meta.session.isLoggedIn == true` 时继续落入 `:394` 的宽松子串列表 `UNAUTH_UPLOAD_MARKERS`（`"您还未登录"` / `"请先登录"` 等）；无 `#umenu` 的纯文本/片段错误响应仍保留 `UNAUTH_UPLOAD_MARKERS` 兜底。
- **单测与回退变红推演**（`ImageUploadRepositoryTest.kt:582-701`，`uploadImage_umenuLoggedInErrorPageWithLoginKeywords_neverReloginsWhileLoggedOutUmenuDoes`）：
  - **回退 `:392` 为基线 `if (!meta.session.isLoggedIn) return true`**：
    1. `loggedInDoubleQuote` 与 `loggedInSingleQuote` 含已登录 `#umenu`（`uid="10000"`）且正文 `div.alert_error` 含 `"请先登录"` 与 `"您还未登录"`，回退后穿透至 `:394` 返回 `true`，`:593` 与 `:597` 的 `assertFalse` **立即变红**；
    2. `MockWebServer` 对照组 A（`:641-653`）将误触发 `authRepository.trySilentRelogin` 请求 `logging.php?action=login`（测试桩直接返回 500），`:648`（`SiteRejected`）与 `:652`（`assertEquals(0, loggedInReloginAttempts.get())`）**立即变红**。
  - **反向变异 `:392` 为 `return false`**：`loggedOutWithoutKeywords`（未登录 `#umenu` 且正文仅 `"操作受限"`、不含任何 `UNAUTH_UPLOAD_MARKERS` 词条）在 `:601` `assertTrue` 及对照组 B（`:690-699`，断言 `loggedOutLoginPostAttempts == 1` 且重放上传成功返回 `"887766"`）**立即变红**。

### 2. `R-D6 S4`：发帖与图片压缩 3 处单行变异盲区补测
- **S4-(a) 锁死 `DefaultPostRepository.kt:112` 与 `:193` 的 `requireAuth = true`**：
  - 单测：`PostRepositoryTest.kt:1067-1144`（`fetchPrePost_andSubmitPost_expiredSessionTriggersSilentReloginAndRetriesSuccessfully`）。
  - 推演：
    - 第 1 段调 `fetchPrePost(NewThread(fid = 2))`，首次 GET 返回未登录页，经 `ForumHttpClient.executeWithAuthRetry`（`ForumHttpClient.kt:84`）触发静默重登并重试拿到 `refetched_fh`。若将 `DefaultPostRepository.kt:112` 改为 `requireAuth = false`，`ForumHttpClient` 直接返回未登录 HTML，`PrePostParser` 解析无表单返回 `PrePostResult.SiteError`，`:1126`、`1129`、`1130` **确定性变红**；
    - 第 2 段显式传入 `prePostInfo = info` 跳过内部自动预取、直击 `:189` `postGbkForm(..., requireAuth = true)`，首次 POST 返回未登录页，重登后重试 POST 返回 `302 /forum/viewthread.php?tid=555&page=1`。若将 `:193` 改为 `requireAuth = false`，直接返回 `PostSubmitResult.SiteError`，`:1140`、`1142`、`1143` **确定性变红**。
- **S4-(b) 锁死 `DefaultPostRepository.kt:82-91` 后台账号流收集器**：
  - 单测：`PostRepositoryTest.kt:1147-1238`（`cooldownUntilMs_flowAutomaticallyEmitsZeroOnAccountSwitchAndLogoutWithoutPollingGetter`）。
  - 推演：此前切号测试调用了同步方法 `getRemainingCooldownSeconds()`（`:93-99` 内部亦会调 `resetCooldownIfAccountChanged`），掩盖了 `:82-91` `init` 收集器被删的变异。新测试用 Turbine 直接订阅 `postRepo.cooldownUntilMs.test`，在发帖产生 `30_000ms` 冷却后**零调用 `getRemainingCooldownSeconds()`**，直接调 `authRepo.switchAccount(accountB.uid)` 与 `authRepo.logout()` 并分别 `awaitItem()` 断言收到 `0L`（`:1214-1218`、`:1230-1235`）。若删去 `:82-91` 后台收集器，`_cooldownUntilMs` 无事件发出，Turbine `awaitItem()` 5s 超时**确定性变红**。
- **S4-(c) 锁死 `ImageCompressor.kt:240-271` `ContentResolver` 流式读取与 `:412-433` 全套 7 种 EXIF 变换**：
  - 单测：`ImageCompressorTest.kt:246-455`（`prepare_contentUri_...` + `prepare_allExifOrientationTransformsProduceExactPixelOrientationAndDimensions`）。
  - 推演：
    1. 使用纯 `content://net.jejer.hipda.test/...` URI 注册到 Robolectric `ShadowContentResolver`，不走 `:272` `"file".equals(uri.scheme)` 分支；覆盖直传拷贝字节一致、带 `ORIENTATION_ROTATE_270` 重编码后中间 `uri_*.tmp` 已由 `finally` 自动清理（`workDir` 仅剩 1 个输出文件，`cleanup()` 后为 0）及不存在 URI 返回 `DecodeFailed` 且 0 残留。若删去 `:269-271` `contentResolver.openInputStream(uri)`，用例 1/2 立即返回 `DecodeFailed` **确定性变红**；
    2. 构造 `240×120` 四象限纯色图（`TL=红, TR=绿, BL=蓝, BR=黄`），逐一校验 `ROTATE_90`、`ROTATE_180`、`ROTATE_270`、`FLIP_HORIZONTAL`、`FLIP_VERTICAL`、`TRANSPOSE`、`TRANSVERSE` 共 7 个分支的横竖屏比例与四象限中心像素颜色（7 组五元组两两互异且均异于原图）。删改 `:415-428` 任意单分支均在对应 `assertQuadrantColors` / 宽高比断言处**确定性变红**。

### 3. `D5-fixcheck` 微注：`AuthorOnly` 第 2 页锁死 `fallbackOpAuthor` 分支
- **背景与代码核实**（`DefaultThreadDetailRepository.kt:111-116, 133, 487-497`）：
  - `loadPage(tid, page = 2, AuthorOnly(authorId = null))` 先拉第 1 页提取 `discoveredOpAuthor` 作为 `fallbackOpAuthor` 传入 `recordBrowseHistory`；`:492-495` 依次取 `:493` `fallbackOpAuthor?.trim()?.takeIf { it.isNotEmpty() }` 与 `:494` `parsed.floors.firstOrNull()?.author?.trim()?.takeIf { it.isNotEmpty() }`。原夹具 `tid="71003"` 第 2 页首层带非空作者 `"AuthorOnlyOP"`，单删 `:493` 会被 `:494` 兜住。
- **单测与回退变红推演**（`ThreadDetailRepositoryTest.kt:1359-1444`）：
  - 新增无历史前置记录的两组夹具：`tid="71004"`（第 2 页楼层 `author = "   "` 全空白）与 `tid="71005"`（第 2 页 `floors = emptyList()`），分别断言浏览历史落盘 `author == "FallbackOpBlankFloor"` 与 `"FallbackOpEmptyList"`。
  - **若单删 `DefaultThreadDetailRepository.kt:493`**：第 2 页无 `floor == 1`（`:488` 为 `null`），`:494` 因空白/空列表亦为 `null`，且库中无旧历史记录（`:507` 回退 `""`），两处 `assertEquals` 均因实际值为 `""` **确定性变红**。

### 4. `R-D9 S1`：`NotificationPollingWorker` 异常兜底、`CancellationException` 重抛与 WorkManager `Result` 语义专项核查
- **生产代码核实**（`NotificationPollingWorker.kt:1-143`）：
  - 仅移除未使用的 `import java.io.IOException` 并将 `hasActiveAccount()` 第 `130` 行由 `catch (_: IOException)` 放宽为 `catch (_: Exception)`（返回 `false`，进而在 `:85-87` 返回 `Result.success()`）。
- **`CancellationException` 不被吞核查（全文件 4 处挂起区逐一确认）**：
  1. Gate 2 读设置（`:61-67`）：`catch (e: CancellationException) { throw e }` 位于 `catch (_: Exception)` 之前；
  2. 执行轮询（`:89-95`）：`catch (e: CancellationException) { throw e }` 位于 `catch (_: Exception)` 之前；
  3. Gate 4 `hasActiveAccount()` 读加密凭据流（`:121-132`）：`catch (e: CancellationException) { throw e }`（`:128-129`）严格位于 `catch (_: Exception)`（`:130-132`）之前；
  4. 冷启动水合等待（`:134-136`）：`withTimeoutOrNull(AUTH_HYDRATION_TIMEOUT_MS)` 仅捕获其自身超时协程产生的 `TimeoutCancellationException`（超时返回 `null -> false -> Result.success()`），若外层 `CoroutineWorker` 因网络断开、系统配额或 `cancelUniqueWork` 被 WorkManager `onStopped()` 取消，父 Job 的 `CancellationException` 原样向外抛出，使 `CoroutineWorker` 正确将底层 `ListenableFuture` 置为 `cancelled`。全文件零 `runCatching`。
- **WorkManager `Result` 语义核查（何时 `Result.success()`、为何全文件 0 处 `Result.retry()`）**：
  - `NotificationPollingWorker` 由 `WorkManagerNotificationScheduler`（`NotificationScheduler.kt:95-101`）以 **15 分钟 `PeriodicWorkRequest`**（`NetworkType.CONNECTED`、`ExistingPeriodicWorkPolicy.KEEP`）调度。
  - 在 `WorkManager` 的 `PeriodicWorkRequest` 模型下：
    - 返回 **`Result.success()`** 表示“本轮周期执行完毕，重置 `runAttemptCount = 0`，按固定 15 分钟周期进入下一轮调度”；
    - 返回 **`Result.retry()`** 则表示“立即按指数退避（默认 30s → 60s → 120s…）在当前周期内密集重试”。
  - 对照 `D9.md:63-64`、`R-D9.md:8` 与 `tech-plan.md` §6（T5）：
    - 4 级前置门禁跳过（含 Gate 4 后台唤醒遇 Keystore 锁死/不可用抛出 `GeneralSecurityException` / `ProviderException`）、`ChallengeAbandoned`（Cloudflare 403）、`AuthRequired`、站点/HTTP/IO 瞬态错误均**必须返回 `Result.success()` 而非 `Result.retry()`**——既杜绝向 Cloudflare 发起退避重试风暴，也避免设备后台锁屏 Keystore 不可用时每 30 秒反复唤醒重试耗电，安静留待下个 15 分钟周期自然恢复；
    - 仅当 WorkManager 主动取消任务时通过重抛 `CancellationException` 交由 WorkManager 原生中断机制处理。当前 `Result` 语义 **100% 严密正确**。
- **单测与回退变红推演**（`NotificationPollingWorkerTest.kt`）：
  1. `preGate4_keystoreSecurityAndProviderExceptions_returnSuccessWithZeroRequestsWhileCancellationRethrows`（`:614-660`）：
     - 若回退 `:130` 为 `catch (_: IOException)`：`:633` `securityExWorker.doWork()` 直接抛出未捕获 `GeneralSecurityException`、`:642` 抛出 `ProviderException`，测试**立即崩溃变红**；
     - 若误删 `:128-129` `catch (e: CancellationException) { throw e }`：`:653` `cancelWorker.doWork()` 吞掉取消并返回 `Result.success()`，`:657` `assertTrue(caughtCancellation)` **立即变红**。
  2. `coldStartBackgroundPoll_hydratesSavedActiveAccountAndPostsSingleSmsPreviewNotification`（`:350-425`）：
     - 用 `authHydrationStarted` / `releaseAuthHydration` / `workerCheckedCredentials` 三道 `CompletableDeferred` 门闩精确卡住 `DefaultAuthRepository` 初始凭据流，在放行水合前确定性断言 `authRepo.currentAccount.value == null`、`!workDeferred.isCompleted`（`:394`）且 `server.requestCount == 0`（`:395`），放行后验证完成 1 次请求并弹出通知，彻底消除了 `Dispatchers.Default` 调度先后不确定性；若回退冷启动等待水合逻辑，`:394` 或 `:401` **100% 确定性变红**。

### 5. `R-E1` 末两条：`HiSkeleton` KDoc 与 `ForumViewModel` `hasMore` 跨号屏蔽
- **`HiSkeleton.kt:24`**：仅更新顶部单行 KDoc 注释（`LocalContentColor` → `MaterialTheme.colorScheme.onSurface`），0 逻辑/令牌改动。
- **`ForumViewModel.kt:106`**：按负责人裁定仅将 `ForumUiState` 构造中的 `load.hasMore` 改为 `!staleUidCache && load.hasMore`（`+1 / -1` 行）。
  - 实际防御价值：除修正 `ForumUiState` 对外状态一致性外，当切到无版块权限的新账号返回 `SiteError` 时（此时 `isLoading == false` 而仓储内存仍保留上一账号 `load.hasMore == true`），修复前直接调用 `vm.loadMore()`（`:172-173`）会穿透守卫发出跨号续载请求；修复后 `cur.hasMore == false` 在 `:173` 直接拦截。
- **单测与回退变红推演**（`ForumViewModelTest.kt:279-342`）：
  - 若将 `ForumViewModel.kt:106` 回退为 `load.hasMore`：
    - `accountSwitch_masksPreviousAccountCachedForumItemsWhenNewAccountGetsSiteError` 中 `:284`（版块 2 `hasMore == false`）、`:289`（`vm.loadMore()` 后 `loadMoreQueries.isEmpty()`）、`:296`（版块 6 `hasMore == false`）**三处断言立即变红**；
    - `accountSwitch_whileNewAccountRefreshInFlight_masksHasMoreFalseUntilSettled` 中 `:329`（账号 B 刷新挂起在途期间 `hasMore == false`）**立即变红**。

---

## 四、非阻断建议（供后续授权触碰 `data/auth/` 的卡吸纳）

- **S1（`DefaultAuthRepository.kt:190-209` 后台凭据流订阅补齐非取消异常防护，建议归入 `E12` 账号与通知收口）**：本卡在授权边界内闭合了 `NotificationPollingWorker.kt:130` 直接读取 `CredentialsStore` 时的 Keystore `GeneralSecurityException`/`ProviderException` 兜底；但在真实 Hilt 单例图中，后台冷唤醒构造 `NotificationPollingWorker` 时会一并实例化 `DefaultAuthRepository`，其 `init` 块（`DefaultAuthRepository.kt:190-209`，运行于无 `CoroutineExceptionHandler` 的 `SupervisorJob() + Dispatchers.Default`）对 `combine(credentialsStore.accounts, activeUid, manuallyLoggedOut)` 尚未包裹 `.catch` / `try-catch`。若真机后台唤醒遇极端 Keystore 全部恢复阶段均失败抛出 `GeneralSecurityException`/`ProviderException`/`IOException`，该后台收集协程仍可能向线程未捕获异常处理器冒泡。建议后续触碰 `data/auth/`（如 `E12`）时为该 `init` 收集流与 `trySilentRelogin` 的 `readPersistentSnapshotFromStore()` 补上非 `CancellationException` 降级保护。
