# 验证卡 V-D6：独立探针验证发帖、回复、编辑、草稿与图片上传 Repository（commit ce4840e8604ad7a4b68d57ec70b3a654389e6021）

【角色】独立黑盒与边界探针验证者，不先读实现者的测试与 `_reports/D6.md` 报告；结论只来自你对照规格与生产源码自己编写并跑出的探针证据。

【安全红线（DISPATCH §0，绝对不可违背）】
- 本卡验证发帖/回复/编辑/删除/上传写操作逻辑。**全部探针必须 100% 离线使用 `MockWebServer` 与内存 Room，任何情况下严禁访问真实站点（`4d4y.com`）或用真实账号发出任何请求！**

【边界】对象 `origin/card/D6-post-upload-repo` @ `ce4840e8604ad7a4b68d57ec70b3a654389e6021`，只读：不得向 `card/D6-post-upload-repo` 推送，不得修改 `main`。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-D6 -b verify/D6-probes ce4840e8604ad7a4b68d57ec70b3a654389e6021`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部探针，再推送 `verify/D6-probes` 并 `gh workflow run android.yml --ref verify/D6-probes` 跑一次 CI，`gh run watch <id> --interval 60`。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-D6.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/D6.md` 范围节；依据 `docs/design/product-design.md` §7.1–§7.4、`docs/design/tech-plan.md` §9、`hipda/src/main/java/net/jejer/hipda/async/{PostHelper,PrePostAsyncTask,UploadImgHelper}.java`。只读生产源码，不先读实现者测试与报告。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/D6ProbeTest.kt`，Robolectric + `MockWebServer` + 内存 `HiDatabase` + 虚拟时钟，零 `Thread.sleep`、零真实网络）：
a. **编辑/删除绝不拼小尾巴 + R-B9b S3 Emoji 往返字节探针**：
   - 在 `AppSettings` 开启 `tailEnabled = true`、`tailText = "来自测试尾巴"`、`tailUrl = "example.com/app"` 的前提下：
     1. 构造含双重转义 `&amp;#128512;` 与单次实体 `&#128076;` 的编辑/引用 `PrePost` HTML，验证预取回填给编辑器的 `subject`/`text`/`quoteText` 已还原为真实 Unicode `😀`/`👌`；
     2. 分别执行 `NewThread` / `ReplyThread` / `QuotePost` 与 `EditPost` / `DeletePost`，逐字节解析 `MockWebServer` 收到的 GBK 请求体：断言非编辑态已按 GBK + `&#128512;` 正确编码并附带 `[url=https://example.com/app][size=1]来自测试尾巴[/size][/url]`，且 **编辑与删除请求体中绝对不包含任何小尾巴文本**，且原有中文 GBK 字节与 Emoji 十进制实体未发生双重转义（如无 `&amp;#128512;`）。
b. **草稿一致性全路径探针（成功必删、任意失败必留）**：
   - 使用内存 `HiDatabase` 预写入 `DraftKey.reply("1001")`、`DraftKey.newThread("2")`、`DraftKey.edit("5001")` 草稿；
   - 分别制造 4 类失败（30 秒冷却中拦截、站点返回 `div.alert_info` 报错、HTTP 500 网络异常、未登录/静默重登失败），每次断言对应 `DraftDao` 草稿内容、标题与附件列表 **100% 完好保留**；
   - 随后模拟 302 跳转 `viewthread.php?tid=1001`（及删除首楼跳转 `forumdisplay.php?fid=2`）成功回包，断言对应 `DraftKey` 草稿被自动删除，而其他无关 `DraftKey` 草稿不受误删。
c. **30 秒冷却并发边界与编辑免冷却探针**：
   - 注入可控虚拟时钟，模拟一次回复成功后立即于 `+0ms`、`+1ms`、`+29_001ms`、`+29_999ms` 发起新回复，断言均返回 `CooldownActive` 且剩余秒数精确向上取整（`30`、`30`、`1`、`1`）、`MockWebServer` 收到 **0 次**额外请求；
   - 并发启动 5 个协程在冷却过期临界点（`+30_000ms`）同时提交回复，验证串行互斥后至多放行 1 次成功请求、其余 4 路立即被新开启的 30 秒冷却拦截（绝不并发发出 5 条重复回复）；
   - 验证在 30 秒冷却期内执行 `EditPost`（非删除）仍能正常提交成功，且不重置回复冷却时间；验证回复失败时绝不开启冷却，立刻重发可直达服务器。
d. **图片压缩、Multipart 上传并发/取消与临时文件清理探针**：
   - 构造超过上限（如大尺寸 `3000×2000`）的高像素 Bitmap 测试图、小于上限的合法 GIF 与超过 `maxUploadBytes` 的超大 GIF：验证大图被压缩至 `<= maxUploadBytes` 且无 `OutOfMemoryError`/崩溃，合法 GIF 直传字节不变，超大 GIF 返回明确超限失败且 0 次网络请求；
   - 经 `MockWebServer` 上传验证 Multipart 含 `uid`/`hash`/`Filedata`、进度流按 `Compressing` → 单调递增 `Uploading`（终值 `1f`）→ `Succeeded(imageId)` 发射，并覆盖 `DISCUZUPLOAD|0|0|0` 与无效响应错误处理；
   - 并发启动 3 张图片上传并中途取消其中 1 张协程：断言被取消任务立即终止且不吞 `CancellationException`，其余 2 张上传成功返回各自 `imageId`，且临时目录无残留压缩临时文件。
e. **去旧依赖与离线边界纯洁性自查探针**：
   - 扫描 `hipda/src/main/java/net/jejer/hipda/data/post/` 源码确认 **0 处 `id.zelory.compressor` 引用**；核实 `data/local/*`、`data/user/*`、`data/auth/*`、`ui/`、`core-parser/` 与共享文件零改动；核实整个 `V-D6` 探针零真实域名访问。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `ce4840e8604ad7a4b68d57ec70b3a654389e6021`、CI 链接、5 个探针（a–e）逐项结论、FAIL 触发路径与复现步骤、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-D6.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。
