# 验证报告 V-D6：发帖、回复、编辑、草稿与图片上传 Repository 独立探针验证

## 1. 总览与判定

- **总判定**：**PASS**
- **验证对象**：`origin/card/D6-post-upload-repo` @ `ce4840e8604ad7a4b68d57ec70b3a654389e6021`（只读未改，未向该分支或 `main` 推送）
- **探针分支与工作区**：`verify/D6-probes` @ `ea1450757e86e6765ddb3c6c32712408c36c9317`（worktree：`/home/mir/Documents/hipda-next.wt/V-D6`）
- **探针测试文件**：[D6ProbeTest.kt](file:///home/mir/Documents/hipda-next.wt/V-D6/hipda/src/test/java/net/jejer/hipda/verify/D6ProbeTest.kt)（Robolectric 4.18.1 Native Graphics + `MockWebServer` + 内存 `HiDatabase` + `AtomicLong` 虚拟时钟，零阻塞休眠、零真实站点请求）
- **CI 验证**：[Android CI #45649910464](https://github.com/mailwmj/hipda-next/actions/runs/45649910464)（`verify/D6-probes`，`Build, Lint & Unit Tests` 全绿通过：`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、Roborazzi 验证及 `TOTAL: 850 tests, 850 passed, 0 failed, 0 errors, 0 skipped`；其中 `D6ProbeTest` 9/9 通过，`:hipda:testDebugUnitTest` 752/752 通过）

## 2. 独立探针逐项结论（a–e）

| 探针 | 覆盖用例（文件:行号） | 结论 | 关键实测证据 |
|---|---|---|---|
| **a. 编辑/删除绝不拼小尾巴 + R-B9b S3 Emoji 往返字节探针** | `probeA_prePostEmojiDecode_andByteLevelGbkRoundTrip_tailOnlyOnNonEditDelete`（`D6ProbeTest.kt:265`）、`probeA_replyPost_realNameBind_uninsertedImagesAndBareUrls`（`D6ProbeTest.kt:527`） | **PASS** | 1) 真机样本 `newthread_fid2.html` 预取 `formhash="00000000"`、`uid="10000"`、`hash`、`typeOptions["9"]="聚会"`、`maxUploadFileSizeBytes=8MB`；2) `EditPost`/`QuotePost` 预取将 `&amp;#128512;`、`&amp;amp;#128512;`、`&amp;amp;#x1F603;`、`&#128076;` 还原为真实 Unicode（`😀`/`😃`/`👌`），且命名实体 `&lt;b&gt;` 保持不被误解；3) 在 `tailEnabled=true`、`tailText="来自测试尾巴"`、`tailUrl="example.com/app"` 下，`NewThread`/`ReplyThread`/`QuotePost` 请求体按 GBK + `&#128512;` / `&#128076;`（原始 ASCII 为 `%26%23128512%3B`、`%26%23128076%3B`，无 `%26amp%3B%23` 二次转义）并追加 `  [url=https://example.com/app][size=1]来自测试尾巴[/size][/url]`；4) `EditPost` 与 `DeletePost` 原始请求体均含 `0` 处小尾巴文本及域名，且中文严格按 GBK 字节编码（非 UTF-8）；5) 覆盖实名绑定 `memcp.php?action=bind` 拦截、`ReplyPost` `noticeauthor` 与 `attachnew[][description]` / `attachdel[]`、未插入正文的 `[attachimg]` 按序追加末尾及裸 URL 包装。 |
| **b. 草稿一致性全路径探针（成功必删、任意失败必留）** | `probeB_draftConsistency_allFailureModesPreserveAllDrafts_successDeletesOnlyTargetKey`（`D6ProbeTest.kt:607`） | **PASS** | 内存 Room `HiDatabase` 预置 `DraftKey.reply("1001")`、`DraftKey.newThread("2")`、`DraftKey.edit("5001")`（含标题/分类/正文/引用 PID/附件列表）。依次制造 4 类失败：① 30s 冷却拦截 `CooldownActive`；② 站点返回 `div.alert_info` `SiteError`；③ HTTP 500 `NetworkError`；④ 静默重登失败 `AuthRequired(RELOGIN_FAILED)` 与手动登出 `AuthRequired(MANUALLY_LOGGED_OUT)`。每次失败后 3 份草稿全部字段 100% 完好保留。随后模拟 302 跳转 `viewthread.php?tid=1001`（解析 `thread_p1.html` 为 `redirectedPage`）仅删除 `reply:1001`；模拟删除首楼 302 跳转 `forumdisplay.php?fid=2`（`threadDeleted=true`）仅删除 `edit:5001`；无关 `new:2` 全程保留。 |
| **c. 30 秒冷却并发边界与编辑免冷却探针** | `probeC_cooldownCeilingBoundaries_fiveWayConcurrentAtBoundary_editBypass_failureNoLock`（`D6ProbeTest.kt:881`） | **PASS** | 注入可控虚拟时钟 `virtualClockMs`：回复成功后立即在 `+0ms`、`+1ms`、`+29_001ms`、`+29_999ms` 提交，均返回 `CooldownActive(30/30/1/1)` 且 `getRemainingCooldownSeconds()` 为 `30/30/1/1`，`MockWebServer` 收到 0 次额外请求；冷却期 `+10_000ms` 执行 `EditPost` 成功提交且 `cooldownUntilMs` 保持 `t0 + 30_000L` 不被重置，同刻 `DeletePost` 仍被拦截为 `CooldownActive(20)`；在 `+30_000ms` 临界点用拦截器门控并发启动 5 路协程同时提交回复，`submitMutex` 串行后仅放行 1 路成功、其余 4 路立即被新 30s 冷却拦截（`CooldownActive(30)`）、服务器仅收 1 次 POST；站点 `div.alert_info` 报错及 HTTP 500 失败后均不锁定冷却，原地 `+0ms` 重发直达服务器成功。 |
| **d. 图片压缩、Multipart 上传并发/取消与临时文件清理探针** | `probeD_imageCompression_gifDirectAndOversize_multipartProgressAndErrors`（`D6ProbeTest.kt:1079`）、`probeD_concurrentThreeUploads_cancelOneMidFlight_propagatesCancellationAndCleansTempFiles`（`D6ProbeTest.kt:1217`）、`probeD_exifRotatedSmallJpeg_isTransformedInsteadOfDirectUploaded`（`D6ProbeTest.kt:1317`）、`probeD_expiredUploadSession_triggersSingleSilentReloginAndReplaysMultipart`（`D6ProbeTest.kt:1358`） | **PASS** | 1) `3000×2000` 复杂图案 PNG（超 `120KB` 上限）经 `BitmapFactory` + `Matrix` 压缩为 `<= 120KB`、长边 `<= 2560px` 合法 JPEG，无 OOM/崩溃；2) `40KB` 合法 GIF89a 直传，`MultipartReader` 解析 `Filedata` 字节与原图 100% 一致；3) 超限 GIF 返回 `ImageUploadError.GifTooLarge` 且 0 次网络请求；4) `ORIENTATION_ROTATE_90` 小 JPEG 不直传而触发旋转重编（`120×60` -> `60×120`）；5) 验证 Multipart `uid`/`hash`/`Filedata`、进度流按 `Compressing` -> 严格递增 `Uploading`（终值 `fraction == 1f`）-> `Succeeded(imageId)` 发射，并覆盖 `DISCUZUPLOAD|0|0|0`（`无效上传图片ID`）、非 `DISCUZUPLOAD` 响应（`无法获取图片ID`）与过期会话单次静默重登重放；6) 3 图并发上传时中途取消第 2 路，被取消协程立即抛出 `CancellationException`（未吞异常、未发射 `Failed`），第 1/3 路成功返回 `20001`/`20003`，调用方本地源文件保留，`post_image_upload` 临时目录残留文件数为 0。 |
| **e. 去旧依赖与离线边界纯洁性自查探针** | `probeE_noLegacyCompressorReferenceAndOfflinePurityAndHiltModulePresence`（`D6ProbeTest.kt:1413`）+ `git diff` 核查 | **PASS** | 扫描 `data/post/*` 与 `di/PostModule.kt` 确认 **0 处 `id.zelory.compressor`、`Compressor(` 及站点域名硬编码**；`PostModule` 具备 `@Module`、`@InstallIn(SingletonComponent::class)` 及双 `@Binds` 方法；探针内 HTTP 拦截器记录全部请求主机均为 `127.0.0.1`/`localhost`；`git diff --name-status origin/main...ce4840e8604ad7a4b68d57ec70b3a654389e6021` 确认仅新增 `data/post/*`（6 文件）、`di/PostModule.kt` 与 `test/.../data/post/*`（3 文件），`data/local/*`、`data/user/*`、`data/auth/*`、`ui/`、`core-parser/` 与共享文件 0 改动。 |

## 3. 本地与 CI 执行证据

### 3.1 本地验证（按 `DISPATCH.md §2` `flock` 串行）
- 命令：
  ```bash
  export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"
  cd /home/mir/Documents/hipda-next.wt/V-D6 && flock /tmp/hipda-gradle.lock bash -c 'nice -n 10 ionice -c3 ./gradlew :hipda:testDebugUnitTest --tests "net.jejer.hipda.verify.D6ProbeTest" :hipda:lintDebug --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4; status=$?; ./gradlew --stop; exit $status'
  ```
- 结果：`D6ProbeTest` 9/9 PASSED（`0 failed, 0 skipped`），`:hipda:lintDebug` PASSED，`BUILD SUCCESSFUL in 1m`。

### 3.2 GitHub Actions CI
- 触发分支：`verify/D6-probes` @ `ea1450757e86e6765ddb3c6c32712408c36c9317`
- CI Run：[https://github.com/mailwmj/hipda-next/actions/runs/45649910464](https://github.com/mailwmj/hipda-next/actions/runs/45649910464)
- 结论：`success`（`Build, Lint & Unit Tests` 用时 `11m14s`）
  - `:hipda:testDebugUnitTest`：`752 tests, 752 passed, 0 failed, 0 skipped`（含 `D6ProbeTest` 9/9、`PostRepositoryTest` 16/16、`ImageUploadRepositoryTest` 7/7、`ImageCompressorTest` 7/7）
  - 汇总（JUnit XML）：`TOTAL: 850 tests, 850 passed, 0 failed, 0 errors, 0 skipped`

### 3.3 边界与未改文件核查
- `git diff --stat 90c026dc370e7edd2f4a44e9dfe823eb9ff9aec2..ce4840e8604ad7a4b68d57ec70b3a654389e6021`：仅 D6 范围内的 10 个新增文件（`+4485`）。
- `git diff --name-status origin/main...ce4840e8604ad7a4b68d57ec70b3a654389e6021`：与 `main` 零冲突、`data/local/*`、`data/user/*`、`data/auth/*`、`ui/`、`core-parser/` 及共享配置文件 0 改动。
- `git diff --stat origin/card/D6-post-upload-repo..verify/D6-probes`：仅新增 `hipda/src/test/java/net/jejer/hipda/verify/D6ProbeTest.kt`（1861 行）。
- **遗留 / FAIL 项**：无。
