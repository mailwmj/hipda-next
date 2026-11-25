# B1c 执行报告：补上脱敏漏网的本人 UID，并清洗已导出的真机样本

> 隐私合规声明：本报告、所有提交、代码、测试与样本中均不含用户真实 UID。

## 1. 结论与交付元信息

- **状态**：`DONE`
- **Worktree**：`/home/mir/Documents/hipda-next.wt/B1c`
- **分支**：`card/B1c-uid-scrub`（基于 `origin/main` @ `65233b6`）
- **最终提交**：`37df441`（`37df441a4f5b9271241d65dca6576d18e49bcb43`），单次提交同时包含代码修复与 17 个清洗后的真机样本
- **CI 证据**：Android CI Run [#45596129517](https://github.com/mailwmj/hipda-next/actions/runs/45596129517) — **SUCCESS**（9m33s；Build、Lint、Room Schema、Roborazzi、全仓 567/567 单测通过）

## 2. 逐条目标验收

| 目标 | 结果 | 证据摘要 |
|---|---|---|
| 1. `HtmlSanitizer` 全位置替换独立数字记号本人 UID，不误伤更长数字 | 通过 | 扩展 `UID_QUERY_PARAM_REGEX` / `UID_INPUT_NAMES` / `SCRIPT_UID_*` 覆盖 `newbuddyid`、`buddyid`、`touid`、`authorid` 等参数，并新增基于 `(?<!\d)(?:<selfUid>)(?!\d)` 的全局独立数字记号替换（`MIN_STANDALONE_SELF_UID_LENGTH = 4`，保护既有 1 位短 UID `blocker6` 不误伤 `fid=2`/`page=2`/`size="2"`），覆盖 URL、属性、JS、注释、纯文本、保护文本节点与跳楼链接，保持 DOM `id`/`class` 不变 |
| 2. `SampleLeakScanner` 独立数字记号检测本人 UID | 通过 | 新增 `LeakType.SELF_UID`；`scan` / `scanDirectory` / `scanDirectories` 支持可选参数 `selfUid: String? = null`（兼保留零参向后兼容）；扫描属性、脚本、注释、纯文本、XML CDATA 中的独立数字记号本人 UID、本人头像路径与短 UID 语境；并新增 `div.profile_side` 操作栏一致性校验（未传 `selfUid` 时也能拦住本人资料页中 `addbuddy` 的漏网 UID）；泄漏明细 `detail` 永不回显真实 UID |
| 3. 单元测试覆盖形态、反例与变异自证 | 通过 | 用假 UID `424242` 覆盖 `newbuddyid=`、`touid=`、`buddyid=`、`authorid=`、未知参数、属性、JS 变量/对象/调用、纯文本、注释，以及前后含数字的更长数字反例（`94242420`、`1424242`、`4242429`、`1424242888`）；同时在 `SampleExportEngineTest` 验证导出引擎透传 `identity.uid` 拦下独立 UID 泄漏；3 组单行变异自证均在运行期立刻变红 |
| 4. 清洗 `origin/samples/b1b` 17 个样本并随代码单次提交 | 通过 | 运行期从 `origin/samples/b1b` 的 `user_info/user_profile.html` 第 121 行读取真实 UID，用修复后的 `HtmlSanitizer` 重跑全部 16 个 HTML 样本并更新 `manifest.json` 字节数；清洗前后由 `SampleLeakScanner`（带/不带 `selfUid`）复查：清洗前检出 1 个文件泄漏，清洗后均为 0；一次性清洗脚本运行后已删除，未留入 `src/main` 或仓库 |
| 5. 自查清洗后样本 `grep -rw` 查本人 UID | 通过 | 对 `hipda/src/test/resources/samples/` 执行 `grep -rw` 结果为 **0 处**；`grep -rF` 结果为 **0 处**；全工作区 `core-parser/src`、`hipda/src` 与 `git diff --cached`、提交说明均为 **0 处** |

## 3. 改动说明与对外 API 清单

### 3.1 `core-parser` 脱敏与扫描模块

- `LeakType.SELF_UID` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeModels.kt:45`
- `SanitizeSelectors.MIN_STANDALONE_SELF_UID_LENGTH = 4` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt:32`
- `SanitizeSelectors.UID_INPUT_NAMES` / `UID_QUERY_PARAM_REGEX` / `SCRIPT_UID_ASSIGN_REGEX` / `SCRIPT_UID_PROP_REGEX` / `SCRIPT_UID_CALL_REGEX` / `TEXT_UID_LABEL_REGEX`（新增 `(?:new_?)?buddy_?id` 覆盖 `newbuddyid`、`buddyid`） — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt:103-112`
- `SanitizeSelectors.isReplaceableUid(uid: String): Boolean` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt:155-156`
- `SanitizeSelectors.buildStandaloneUidRegex(uids: Collection<String>): Regex?`（构造 `(?<!\d)(?:...)(?!\d)`） — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SanitizeSelectors.kt:158-167`
- `HtmlSanitizer.sanitize(html: String, page: PageType, identity: Identity): String`（对外签名不变；内部在 `collectSelfIdentities` 补充脚本 `discuz_uid` 采集，并在 `transformUrlAttribute`、`transformText`、`sanitizeProtectedText`、`sanitizeElementAttributes`、`sanitizeKnownIdentityElements` 中调用 `replaceStandaloneSelfUids`） — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/HtmlSanitizer.kt:111,192-207,350-397,436-484,620-628`
- `SampleLeakScanner.scan(html: String, selfUid: String? = null): List<Leak>` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SampleLeakScanner.kt:97`
- `SampleLeakScanner.scanDirectory(directory: Path, selfUid: String? = null): List<FileLeakReport>` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SampleLeakScanner.kt:115`
- `SampleLeakScanner.scanDirectories(directories: List<Path>, selfUid: String? = null): List<FileLeakReport>` — `core-parser/src/main/kotlin/net/jejer/hipda/parser/sanitize/SampleLeakScanner.kt:128`

### 3.2 导出流程最小改动说明（符合范围条款）

- `SampleExportEngine` 构造函数参数 `leakScanner: (html: String, selfUid: String?) -> List<Leak> = SampleLeakScanner::scan` 与调用点 `leakScanner(cleaned, identity.uid)` — `hipda/src/debug/java/net/jejer/hipda/debug/sampleexport/SampleExportEngine.kt:67,287`
  - **改动量**：仅 2 行，未改动任何 B1b debug 页面 UI/交互逻辑。
  - **原因**：真机导出时 `SampleExportEngine` 已持有完整 `identity`，将其 `identity.uid` 传给 `SampleLeakScanner.scan`，使导出门禁能直接按独立数字记号拦住所有本人 UID 漏网形态。

### 3.3 测试改动

- `core-parser/src/test/kotlin/net/jejer/hipda/parser/sanitize/HtmlSanitizerTest.kt`：新增 3 个测试方法（`b1cSanitizesAndScansStandaloneSelfUidAcrossUrlsScriptsAttributesAndText`、`b1cDoesNotCorruptOrFlagLongerNumbersContainingSelfUid`、`b1cProfileSideConsistencyAndShortUidRules`），全部使用假 UID（`424242` / `2`）。
- `core-parser/src/test/kotlin/net/jejer/hipda/parser/sanitize/SamplesAreSanitizedTest.kt`：新增 `scanDirectoryDetectsLeakedSelfUidAndPassesAfterSanitization`，验证目录级 `selfUid` 扫描与脱敏闭环。
- `hipda/src/test/java/net/jejer/hipda/debug/sampleexport/SampleExportEngineTest.kt`：新增 `leakGate_passesSelfUidToScannerAndRejectsLeakedStandaloneUid`，验证导出门禁拒绝携带漏网本人 UID 的页面并排除出 zip，且错误文案不泄露 UID。

## 4. 样本清洗与验证证据

### 4.1 清洗过程与逐文件结果

来源：`origin/samples/b1b` @ `01907ca` 下共 17 个文件（16 个 HTML + `manifest.json`）。

- 清洗前 `SampleLeakScanner.scanDirectory(samplesDir, selfUid)`：检出 1 个文件、2 条 `SELF_UID` 泄漏；`scanDirectory(samplesDir, null)` 检出 1 个文件、1 条 `SELF_UID` 泄漏。
- 清洗结果：
  - `user_info/user_profile.html`：仅第 121 行 `newbuddyid=<本人UID>` 替换为 `newbuddyid=10000`（`17298 -> 17297` bytes）。
  - `manifest.json`：`USER_PROFILE` 的 `byteSize` 同步更新为 `17297`。
  - 其余 15 个 HTML 样本与 `origin/samples/b1b` 逐字节一致（幂等无误改）。
- 清洗后验证：
  - `SampleLeakScanner.scanDirectory(samplesDir, selfUid)`：**0 处泄漏**
  - `SampleLeakScanner.scanDirectory(samplesDir, null)`：**0 处泄漏**
  - `grep -rw <本人UID> hipda/src/test/resources/samples/`：**0 处**
  - `grep -rF <本人UID> hipda/src/test/resources/samples/`：**0 处**
  - `git diff --cached` 与提交信息匹配真实 UID：**0 处**

### 4.2 变异自证（改坏一行测试必红）

1. 破坏 `HtmlSanitizer.replaceStandaloneSelfUids`（改为直接返回 `input`）：
   - `HtmlSanitizerTest > b1cSanitizesAndScansStandaloneSelfUidAcrossUrlsScriptsAttributesAndText()` FAILED (`HtmlSanitizerTest.kt:1031`)。
2. 破坏 `SampleLeakScanner` 的 `standaloneRegex` 检测（改为空串扫描）：
   - `HtmlSanitizerTest > b1cSanitizesAndScansStandaloneSelfUidAcrossUrlsScriptsAttributesAndText()` FAILED (`HtmlSanitizerTest.kt:999`)。
3. 破坏 `SanitizeSelectors.buildStandaloneUidRegex`（去掉前后非数字边界 `(?<!\d)` / `(?!\d)`）：
   - `HtmlSanitizerTest > b1cDoesNotCorruptOrFlagLongerNumbersContainingSelfUid()` FAILED (`HtmlSanitizerTest.kt:1079`)。

### 4.3 本地与 CI 测试结果

- **本地（遵守 `DISPATCH.md` §2，使用 `flock /tmp/hipda-gradle.lock` + `-Xmx3g` + `workers.max=4`，结束后 `./gradlew --stop`）**：
  - `:core-parser:test`：31/31 通过（`CoreParserPlaceholderTest` 1、`HtmlSanitizerTest` 25、`SamplesAreSanitizedTest` 5）
  - `:hipda:testDebugUnitTest --tests "net.jejer.hipda.debug.sampleexport.*"`：19/19 通过（`SampleExportEngineTest` 14、`SampleExportReadOnlyScanTest` 3、`SampleExportScreenshotTest` 2）
- **GitHub Actions CI**：
  - Run [#45596129517](https://github.com/mailwmj/hipda-next/actions/runs/45596129517)（commit `37df441`）：**SUCCESS**
  - `HtmlSanitizerTest` 25/25、`SamplesAreSanitizedTest` 5/5、`SampleExportEngineTest` 14/14
  - 全仓合计：`TOTAL: 567 tests, 567 passed, 0 failed, 0 errors, 0 skipped`
  - Build、Lint、Room schema consistency、Roborazzi 截图验证、APK 输出验证全部通过。

## 5. 遗留问题

无。
