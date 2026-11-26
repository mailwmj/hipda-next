# 任务卡 CI-heap 交付报告

- **卡号**：CI-heap（单测 JVM 内存余量）
- **分支**：`card/CI-test-heap`（基于 `origin/main@2739d4d`）
- **最终 Commit**：`822dccb44dc0b5d3ecb7498477d95727eb6d1222`（`822dccb`）
- **CI Run**：https://github.com/mailwmj/hipda-next/actions/runs/45812293247（`success`，耗时 7m 29s）

## 1. 改动清单

仅修改 `hipda/build.gradle.kts` 的 `tasks.withType<Test>().configureEach`（lines 93-95），未修改其他文件、测试代码或 Gradle daemon `-Xmx`：

```kotlin
tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    forkEvery = 400L
```

- 无新增或变更对外 API。

## 2. 改前 / 改后 `:hipda:testDebugUnitTest` 耗时对比（取 CI 日志）

| 运行 | 分支 / Commit | 结果 | `:hipda:testDebugUnitTest` 起止（UTC） | 任务耗时 | 相对改前变化 | 测试步骤总耗时 |
|---|---|---|---|---|---|---|
| 改前（失败 1）[45808744222 #1](https://github.com/mailwmj/hipda-next/actions/runs/45808744222) | `main@2739d4d` | `FAILURE`（1459/1460，OOM） | `11:29:13.974Z` → `11:32:56.068Z` | **222.1s（3m 42s）** | 基准 | 4m 54s |
| 改前（失败 2，热缓存）[45808744222 #2](https://github.com/mailwmj/hipda-next/actions/runs/45808744222) | `main@2739d4d` | `FAILURE`（1459/1460，OOM） | `11:35:51.387Z` → `11:39:22.887Z` | **211.5s（3m 32s）** | 基准（同缓存） | 3m 38s |
| 改前（最近绿 main）[45789623941](https://github.com/mailwmj/hipda-next/actions/runs/45789623941) | `main@bb5a870` | `SUCCESS`（1427/1427） | `09:25:13.054Z` → `09:28:48.053Z` | **215.0s（3m 35s）** | 基准（绿 main） | 4m 44s |
| **改后**[45812293247](https://github.com/mailwmj/hipda-next/actions/runs/45812293247) | `card/CI-test-heap@822dccb` | **`SUCCESS`（1460/1460）** | `11:41:37.559Z` → `11:44:42.359Z` | **184.8s（3m 05s）** | **-12.6% ~ -16.8%** | **3m 12s** |

结论：增加 `maxHeapSize = "1536m"` 与 `forkEvery = 400L` 后，不仅消除了 `HiShellScreenshotTest` 的 OOM，且 `:hipda:testDebugUnitTest` 耗时反而缩短约 **12.6%～16.8%**（远低于 +30% 阈值，无需调大或移除 `forkEvery`）。全仓 JUnit 汇总 `1560 tests, 1560 passed, 0 failed, 0 errors, 0 skipped`，Roborazzi 校验通过。

## 3. 本地验证记录（遵守 DISPATCH §2）

命令：
```bash
export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0
export JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest
export PATH="$JAVA_HOME/bin:$PATH"
flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew \
  :hipda:assembleDebug :hipda:testDebugUnitTest \
  --tests 'net.jejer.hipda.ui.shell.*' \
  --tests '*CopyTerminologyScanTest' \
  --tests '*MotionTokenScanTest' \
  -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4
./gradlew --stop
```

结果：`BUILD SUCCESSFUL in 52s`
- `net.jejer.hipda.ui.shell.*`：6 个测试类共 127 tests 全部通过（含 `HiShellScreenshotTest` 18/18）
- `CopyTerminologyScanTest`、`MotionTokenScanTest`：共 10 tests 全部通过
- 合计：`[:hipda:testDebugUnitTest] TOTAL: SUCCESS (137 tests, 137 passed, 0 failed, 0 skipped)`
- `:hipda:assembleDebug`：通过，结束后已停止 Gradle daemon（`1 Daemon stopped`）

## 4. 验收核对

1. **CI 全绿**：通过（Run `45812293247`，`Build, Lint & Unit Tests` 全绿，1560/1560 通过）。
2. **改前/改后耗时对比及 ≤30% 门禁**：通过（改前 211.5s～222.1s → 改后 184.8s，缩短 12.6%～16.8%）。
3. **本地按 DISPATCH §2 跑 `--tests 'net.jejer.hipda.ui.shell.*'`**：通过（137/137 通过，已 `./gradlew --stop`）。
