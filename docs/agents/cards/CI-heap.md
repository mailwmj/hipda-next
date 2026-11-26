【任务卡 CI-heap：单测 JVM 内存余量（普通档，OPS 核对）】
分支：`card/CI-test-heap`（基于 origin/main@2739d4d）
背景：main CI 45808744222 中 `HiShellScreenshotTest.shell_w1280_h800_dark` 报 `OutOfMemoryError: Java heap space`，栈在 Roborazzi 的 AwtRoboCanvas 里。测试 JVM 用的是 Gradle 默认堆（约 512MB），单测已有 1,460 个。
范围：只改 `hipda/build.gradle.kts` 里的 `tasks.withType<Test>().configureEach { ... }`：
- 设 `maxHeapSize = "1536m"`；
- 设 `forkEvery = 400`，定期重启测试 JVM，防止 Robolectric 和截图缓冲越积越多。
不改其他文件，不改测试，不改 Gradle daemon 的 -Xmx。本卡授权改这一处共享构建文件。
验收：
1. CI 全绿。
2. 报告给出改前、改后 `:hipda:testDebugUnitTest` 的耗时（取 CI 日志）。耗时增加超过 30% 时，把 forkEvery 调大，或者去掉 forkEvery 只保留 maxHeapSize。
3. 本地按 DISPATCH §2 跑一次 `--tests 'net.jejer.hipda.ui.shell.*'`。
最后一条回复 ≤300 字，首词 DONE 或 STUCK，附 commit 和 CI 链接；报告写 _reports/CI-heap.md。
