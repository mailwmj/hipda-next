# 任务卡执行报告 D-cleanup：数据层、后台轮询与零冲突小修收尾

- **状态**：`DONE`（按【负责人裁定】删除 `R-E2b S1`，零碰 `feature/threaddetail/media/*` 及其测试；其余 5 项全部收口）
- **基线提交**：`fbeeb28`（`origin/main`）
- **分支 / 工作区**：`card/D-cleanup-followups` / `/home/mir/Documents/hipda-next.wt/D-cleanup`
- **最终 Commit**：`230e88d3d9f558f8d9036d3e474eb5714a005e6a`（`230e88d`）
- **GitHub Actions CI**：[Android CI #45715881101](https://github.com/mailwmj/hipda-next/actions/runs/45715881101)
  - `headSha`：`230e88d3d9f558f8d9036d3e474eb5714a005e6a`（精确匹配）
  - Job `Build, Lint & Unit Tests`（ID `139561675082`）：`completed / success`（13m35s）
  - 全量单元测试汇总：`TOTAL: 1126 tests, 1126 passed, 0 failed, 0 errors, 0 skipped`（含 `:hipda:testDebugUnitTest` 1028/1028、`:core-parser:test` 98/98、Roborazzi 全仓截图零漂移、Room schema 一致性校验、Debug/Release APK 与 `lintDebug`）

---

## 一、文件边界与改动统计核验

`git diff --name-status fbeeb28...230e88d`（共 10 个文件，生产 4 个 `+4 / -5` 行、测试 6 个 `+762 / -8` 行，合计 `+766 / -13` 行）：

| 类型 | 文件路径 | 行数变化 | 对应条目 |
|---|---|---:|---|
| 生产 | `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt` | `+1 / -1` | `R-D6 S3` |
| 生产 | `hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt` | `+1 / -2` | `R-D9 S1` |
| 生产 | `hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt` | `+1 / -1` | `R-E1` 备注 1（仅 KDoc） |
| 生产 | `hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt` | `+1 / -1` | `R-E1` 备注 2（仅 `hasMore`） |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/ImageUploadRepositoryTest.kt` | `+121 / -0` | `R-D6 S3` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/PostRepositoryTest.kt` | `+177 / -0` | `R-D6 S4 (a)(b)` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/post/ImageCompressorTest.kt` | `+215 / -0` | `R-D6 S4 (c)` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/data/threaddetail/ThreadDetailRepositoryTest.kt` | `+87 / -0` | `D5-fixcheck` 微注 |
| 测试 | `hipda/src/test/java/net/jejer/hipda/service/NotificationPollingWorkerTest.kt` | `+111 / -6` | `R-D9 S1` |
| 测试 | `hipda/src/test/java/net/jejer/hipda/feature/forum/ForumViewModelTest.kt` | `+59 / -2` | `R-E1` 备注 2 |

**零重叠 / 裁定核对**：
- `feature/threaddetail/media/*` 及其测试：**0 改动**（严格执行负责人裁定删除 `R-E2b S1`）。
- 并行卡 `E2c`（`feature/threaddetail/*` VM/Pane/PostFloorCard/PostBodyRenderer、`interact/*`、`core-parser/*`）、`E3`（`feature/app/*`、`ui/shell/*`）、`E4`（`chrome/*`、`ui/designsystem/feedback/*`）：**0 改动**。
- 共享构建/Manifest/CI/快照 PNG 文件：**0 改动**。

---

## 二、5 项收口实现与“回退必红”单测说明

### 1. `R-D6 S3`：上传未登录检测 `#umenu` 权威短路
- **生产改动**：`DefaultImageUploadRepository.kt:388-393`
  - 原先检测到 `id="umenu"` / `id='umenu'` 且 `!meta.session.isLoggedIn` 时才 `return true`，已登录错误页会落入下方 `UNAUTH_UPLOAD_MARKERS` 子串兜底。
  - 现改为检测到 `#umenu` 时直接 `return !meta.session.isLoggedIn`。
- **单测验证**：`ImageUploadRepositoryTest.kt:582-701`（`uploadImage_umenuLoggedInErrorPageWithLoginKeywords_neverReloginsWhileLoggedOutUmenuDoes`）
  - 单元测试直接覆盖双引号/单引号已登录 `#umenu` 含 `"请先登录"`/`"您还未登录"` 返回 `false`，未登录 `#umenu` 无宽松关键词返回 `true`，无 `#umenu` 纯文本含关键词返回 `true`。
  - `MockWebServer` 端到端对照：
    - 对照 A（已登录 `#umenu` 错误页正文含 `"请先登录"`/`"您还未登录"`）：断言终态为 `SiteRejected`、`uploadAttempts == 1`、`reloginAttempts == 0`。
    - 对照 B（未登录 `#umenu` 错误页正文仅 `"操作受限"`，不含宽松关键词）：断言触发静默重登（`loginPostAttempts == 1`）、重放上传（`uploadAttempts == 2`）并成功返回 `imageId = "887766"`。
- **回退必红机制**：若回退 `DefaultImageUploadRepository.kt:392` 为 `if (!meta.session.isLoggedIn) return true`，对照 A 会命中 `UNAUTH_UPLOAD_MARKERS`，单测 `isUploadResponseUnauthenticated` 返回 `true` 且 `MockWebServer` 触发重登（`reloginAttempts > 0`），断言立即失败。

### 2. `R-D6 S4`：发帖重登、冷却后台流与图片 `content://` + EXIF 补测
- **S4-(a) `fetchPrePost` 与 `submitPost` 过期静默重登**（`PostRepositoryTest.kt:1067-1144`，`fetchPrePost_andSubmitPost_expiredSessionTriggersSilentReloginAndRetriesSuccessfully`）：
  - `fetchPrePost` 首次返回未登录页 → 静默重登（`loginSubmitAttempts == 1`）→ 重试 `GET` 成功拿到 `formhash = "refetched_fh"`（`prePostAttempts == 2`）。
  - `submitPost`（传入预取好的 `prePostInfo` 隔离自动预取）首次 `POST` 返回未登录页 → 静默重登（`loginSubmitAttempts == 2`）→ 重试 `POST` 成功跳转（`submitPostAttempts == 2`）。
  - **回退必红**：若将 `DefaultPostRepository.kt:112` 或 `:193` 任一处 `requireAuth = true` 改为 `false`，对应调用不会触发静默重登而直接落入 `SiteError`，`assertTrue(result is ...Success)` 与 `loginSubmitAttempts` 断言立即失败。
- **S4-(b) `cooldownUntilMs` 切号/退登自动推送 `0L`**（`PostRepositoryTest.kt:1147-1238`，`cooldownUntilMs_flowAutomaticallyEmitsZeroOnAccountSwitchAndLogoutWithoutPollingGetter`）：
  - 用 Turbine 直接订阅 `postRepo.cooldownUntilMs.test`：发帖建立冷却后，在不调用 `getRemainingCooldownSeconds()` 的前提下先后执行 `authRepo.switchAccount(accountB.uid)` 与 `authRepo.logout()`，两次均直接 `awaitItem()` 断言自动收到 `0L`。
  - **回退必红**：若删除 `DefaultPostRepository.kt:82-91` 后台收集器，Turbine `awaitItem()` 永远收不到 `0L` 而超时失败。
- **S4-(c) `ImageCompressor` `content://` 流式拷贝与全套 EXIF 旋转/镜像矩阵**（`ImageCompressorTest.kt:246-455`）：
  - `prepare_contentUri_streamsViaContentResolverAppliesExifRotationAndCleansTempFiles`：通过 Robolectric `shadowOf(context.contentResolver).registerInputStream(...)` 注册纯 `content://` URI（不走 `file://` 回退），覆盖原图直传拷贝（`isTempFile = true`、字节逐位一致、清理后 0 残留）、带 `ORIENTATION_ROTATE_270` EXIF 的 `content://` 重编码（确认中间 `uri_*.tmp` 已自动删除且像素正确旋转）以及不可读取 `content://` 返回 `DecodeFailed` 且 0 临时文件残留。
  - `prepare_allExifOrientationTransformsProduceExactPixelOrientationAndDimensions`：构造 240×120 四象限纯色图（TL=红、TR=绿、BL=蓝、BR=黄），逐一验证 `ROTATE_90`、`ROTATE_180`、`ROTATE_270`、`FLIP_HORIZONTAL`、`FLIP_VERTICAL`、`TRANSPOSE`、`TRANSVERSE` 全部 7 种 EXIF 变换后的宽高方向与四象限中心像素颜色。
  - **回退必红**：删改 `openUriInputStream` 的 `ContentResolver` 分支或 `applyExifOrientation` 任意一个方向分支，对应象限颜色或 `DecodeFailed` 断言立即失败。

### 3. `D5-fixcheck` 微注：`AuthorOnly` 第 2 页 `fallbackOpAuthor` 夹具加固
- **单测加固**：`ThreadDetailRepositoryTest.kt:1359-1444`（并入 `browseHistory_nonFirstPageNeverOverwritesOpAuthor_coversPage3ThenPage1AndLastPageOnlyAndUserOnly` 第 4、5 段）
  - 新增两条无历史前置记录的 `AuthorOnly(authorId = null)` 第 2 页夹具：
    - 第 4 段（`tid = "71004"`）：第 2 页楼层作者仅为空白 `"   "`，断言写入历史 `author == "FallbackOpBlankFloor"`。
    - 第 5 段（`tid = "71005"`）：第 2 页 `#postlist` 为空楼层列表 `floors = emptyList()`，断言写入历史 `author == "FallbackOpEmptyList"`。
- **回退必红机制**：若单删 `DefaultThreadDetailRepository.kt:493`（`fallbackOpAuthor?.trim()?.takeIf { it.isNotEmpty() }`），因第 2 页次级分支 `:494`（首楼作者）返回 `null` 且无已有历史，落盘 `author` 变为 `""`，两条断言均立即失败。

### 4. `R-D9 S1`：后台轮询 Keystore 异常兜底与冷启动确定性门闩
- **生产改动**：`NotificationPollingWorker.kt:130`（并清理原第 9 行未使用的 `import java.io.IOException`）
  - 将 `hasActiveAccount()` 的 `catch (_: IOException)` 放宽为 `catch (_: Exception)`，保留紧邻上方的 `catch (e: CancellationException) { throw e }`。
- **单测验证**：
  - `NotificationPollingWorkerTest.kt:614-660`（`preGate4_keystoreSecurityAndProviderExceptions_returnSuccessWithZeroRequestsWhileCancellationRethrows`）：构造 `GeneralSecurityException` 与 `ProviderException` 凭据流断言均安静返回 `Result.success()` 且 0 网络请求、0 通知；同时构造 `CancellationException` 凭据流断言照常重抛不被吞。
  - `NotificationPollingWorkerTest.kt:350-405`（`coldStartBackgroundPoll_hydratesSavedActiveAccountAndPostsSingleSmsPreviewNotification`）：通过 `authHydrationStarted` / `releaseAuthHydration` / `workerCheckedCredentials` 三个 `CompletableDeferred` 门闩挂起 `DefaultAuthRepository` 初始凭据流，先确认 `authRepo.currentAccount.value == null`、Worker 已进入 `hasActiveAccount()` 等待分支且 `server.requestCount == 0`，再放行水合并断言轮询成功与通知落盘，100% 消除 `Dispatchers.Default` 调度不确定性。
- **回退必红机制**：若回退 `:130` 为 `catch (_: IOException)`，`GeneralSecurityException` 与 `ProviderException` 直接逃逸导致测试异常失败；若删去 `CancellationException` 重抛，第 3 子用例立即失败；若删去冷启动等待水合逻辑，门闩用例因提前退出或未等待直接失败。

### 5. `R-E1` 末两条：`HiSkeleton` KDoc 对齐 + `ForumViewModel` `hasMore` 跨号屏蔽
- **生产改动**：
  - `HiSkeleton.kt:24`：将顶部 KDoc 的 `LocalContentColor` 更新为 `MaterialTheme.colorScheme.onSurface`（纯注释，0 行为改动）。
  - `ForumViewModel.kt:106`（按负责人裁定仅改 `hasMore` 一处）：将 `load.hasMore` 改为 `!staleUidCache && load.hasMore`。
- **单测验证**：`ForumViewModelTest.kt:280-358`
  - 在 `accountSwitch_masksPreviousAccountCachedForumItemsWhenNewAccountGetsSiteError` 补齐切号前 `hasMore == true`、切号后 `SiteError` 态（版块 2 与 6）`hasMore == false` 且 `vm.loadMore()` 被阻断（`loadMoreQueries.isEmpty()`）、切至新号有权限版块 7 后 `hasMore == true` 断言。
  - 新增 `accountSwitch_whileNewAccountRefreshInFlight_masksHasMoreFalseUntilSettled`，使用 `StandardTestDispatcher` + `CompletableDeferred` 门闩在账号 B 刷新在途未就绪期间（`staleUidCache == true` 且仓储内存 `load.hasMore == true`）断言 `uiState.value.hasMore == false`，放行就绪后恢复 `true`。
- **回退必红机制**：若将 `ForumViewModel.kt:106` 回退为 `load.hasMore`，跨号在途态与 `SiteError` 态的 `assertFalse(vm.uiState.value.hasMore)` 以及 `loadMoreQueries.isEmpty()` 均立即失败。

---

## 三、本地与 CI 验证证据

1. **本地定向单测**（遵守 `DISPATCH.md` §2 `flock` + `-Xmx3g` + `--stop`）：
   - `ImageCompressorTest`：9/9 PASSED
   - `ImageUploadRepositoryTest`：8/8 PASSED
   - `PostRepositoryTest`：18/18 PASSED
   - `ThreadDetailRepositoryTest`：19/19 PASSED
   - `ForumViewModelTest`：10/10 PASSED
   - `NotificationPollingWorkerTest`：11/11 PASSED
   - 合计：**75/75 PASSED**（0 编译警告于改动文件）
2. **本地全量验证**（`flock` 串行执行 `:core-parser:test :hipda:testDebugUnitTest -Proborazzi.test.verify=true :hipda:assembleDebug :hipda:assembleRelease :hipda:lintDebug` + `./gradlew --stop`）：
   - `:hipda:testDebugUnitTest`：**1028/1028 PASSED**，`finalizeTestRoborazziDebug` 0 像素漂移；`:core-parser:test`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 全部 `BUILD SUCCESSFUL`（5m06s）。
3. **GitHub Actions CI**：
   - Run [#45715881101](https://github.com/mailwmj/hipda-next/actions/runs/45715881101)（`headSha = 230e88d3d9f558f8d9036d3e474eb5714a005e6a`）：`completed / success`，全仓 **1126/1126** 通过，Roborazzi 0 差异，APK 与 lint 全部通过。

---

## 四、对外 API 清单（零破坏性变更）

本卡仅修改内部实现细节与 KDoc，**所有公开/内部对外签名 100% 保持不变**：

- `DefaultImageUploadRepository.uploadImage(input: ImageUploadInput, uid: String, hash: String, maxUploadBytes: Int?, priority: AccessPriority): Flow<ImageUploadProgress>` — `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt:68`
- `internal DefaultImageUploadRepository.Companion.isUploadResponseUnauthenticated(responseText: String): Boolean` — `hipda/src/main/java/net/jejer/hipda/data/post/DefaultImageUploadRepository.kt:386`
- `NotificationPollingWorker.doWork(): ListenableWorker.Result` — `hipda/src/main/java/net/jejer/hipda/service/NotificationPollingWorker.kt:54`
- `object HiSkeletonTokens` / `fun Modifier.hiSkeleton(shape: Shape = HiShapeTokens.Small): Modifier` / `fun HiSkeletonBlock(...)` — `hipda/src/main/java/net/jejer/hipda/ui/designsystem/state/HiSkeleton.kt:26,37,59`
- `class ForumViewModel @Inject constructor(...)` / `val uiState: StateFlow<ForumUiState>` — `hipda/src/main/java/net/jejer/hipda/feature/forum/ForumViewModel.kt:69,89`

---

## 五、修复轮 1（`R-D-cleanup S1`：`DefaultAuthRepository` 初始化凭据流订阅降级保护）

- **新增 Commit**：`de2681e84cbf8af0f80998e5d8b4a7c4f3b3f02b`（`de2681e`，基于 `230e88d`）
- **GitHub Actions CI**：[Android CI #45721346091](https://github.com/mailwmj/hipda-next/actions/runs/45721346091)
  - `headSha`：`de2681e84cbf8af0f80998e5d8b4a7c4f3b3f02b`（精确匹配）
  - Job `Build, Lint & Unit Tests`（ID `139580171366`）：`completed / success`（11m38s）
  - 全量单元测试汇总：`TOTAL: 1128 tests, 1128 passed, 0 failed, 0 errors, 0 skipped`（`AuthRepositoryTest` `35/35`、`:hipda:testDebugUnitTest` `1030/1030`、`:core-parser:test` `98/98`、Roborazzi 0 漂移、Debug/Release APK 与 `lintDebug` 全绿）
- **本轮改动范围**（`git diff --name-status 230e88d...de2681e`，严格仅 2 文件，`+402 / -2` 行）：
  - `hipda/src/main/java/net/jejer/hipda/data/auth/DefaultAuthRepository.kt`（`+25 / -2` 行）
  - `hipda/src/test/java/net/jejer/hipda/data/auth/AuthRepositoryTest.kt`（`+377 / -0` 行）

### 实现与安全边界
- **`DefaultAuthRepository.kt:193-219`**：将 `init` 中 `combine(credentialsStore.accounts, activeUid, manuallyLoggedOut).conflate().collect { ... }` 调整为 `.onEach { ... }.catch { e -> if (e is CancellationException || e !is Exception) throw e; handleCredentialsObservationFailure(e) }.collect()`，同时覆盖上游 `combine` 抛异常与 `.onEach` 内 `readPersistentSnapshotFromStore()` 抛异常两条路径。
- **`DefaultAuthRepository.kt:1292-1303, 1350`**：`handleCredentialsObservationFailure(e)` 仅以 `Log.w("DefaultAuthRepository", "Failed to observe credentials store ($errorType); degrading to signed-out state")` 记录异常类名（不输出 `e.message`、不附带 `Throwable`，杜绝凭据内容泄露至 logcat），随后在 `stateLock` 下（`explicitOperationCount == 0` 时）调用 `applyPersistentSnapshotLocked(PersistentSnapshot())` 降级为“无已保存账号”态（`currentAccount = null`、`savedAccounts = emptyList()`、`authState = AuthState.SignedOut`）。
- **对外 API**：0 签名变更。

### 新增单测与回退必红验证
1. `AuthRepositoryTest.initCredentialsObservation_nonCancellationException_logsWithoutCredentialsAndDegradesWithoutCrashing`（`AuthRepositoryTest.kt:2025-2222`）：
   - 子用例 A（冷启动 `combine` 上游抛含伪造 `passwordMd5` 文本的 `GeneralSecurityException`）与子用例 B（水合 `SignedIn` 后 `readPersistentSnapshotFromStore()` 抛含 `passwordMd5`/`securityAnswer` 的 `ProviderException`）：均通过 `CoroutineExceptionHandler` 断言 0 未捕获异常、状态降级为 `currentAccount == null` / `savedAccounts.isEmpty()` / `AuthState.SignedOut`，并通过 `ShadowLog` 断言写入 `Log.WARN` 且不含任何凭据文本或原始 `Throwable`。
   - **回退必红**：删去 `.catch` 或降级逻辑后，未捕获异常立即触发 `CoroutineExceptionHandler` 断言失败，且子用例 B 状态滞留 `SignedIn` 断言失败。
2. `AuthRepositoryTest.initCredentialsObservation_cancellationException_propagatesWithoutDegradingOrLogging`（`AuthRepositoryTest.kt:2225-2392`）：
   - 覆盖上游显式抛 `CancellationException` 与取消仓库 `scope` 两条路径：断言 `initJob.isCancelled == true`、完成原因为 `CancellationException`、未触发 `CoroutineExceptionHandler`、已水合的 `currentAccount`/`savedAccounts`/`AuthState.SignedIn` 均不被降级，且 `ShadowLog` 0 条告警日志。
   - **回退必红**：若删去 `.catch` 中的 `if (e is CancellationException) throw e`（或用宽泛 `catch (_: Exception)` 吞掉取消），上游 `CancellationException` 会被吞并触发降级与告警，`initJob.isCancelled`、`cause is CancellationException`、账号保留与空日志断言立即变红。

### 本地验证记录
- 本地 `flock` 串行运行 `data.auth.*`、`NotificationPollingWorkerTest`、`data.post.*`、`data.message.*` 共 **143/143 PASSED**（`AuthRepositoryTest` **35/35**），`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug` 全绿。
