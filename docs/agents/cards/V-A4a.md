# 验证卡 V-A4a：独立验证 Cloudflare 验证页检测器（commit e75ef76）

【角色】你是独立验证者，没有看过实现者的自评；结论只能来自你自己跑出来的证据。

【边界】被验证对象：`origin/card/A4a-challenge-detector` @ `e75ef76b55918467d86e8f29e0310d58c74d8694`，只读：不得向该分支推送，不得修改 `main`。你的工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-A4a -b verify/A4a-probes e75ef76b55918467d86e8f29e0310d58c74d8694`，只在这里写文件。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再按 `docs/agents/DISPATCH.md` 第 2 节用 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束后 `./gradlew --stop`。最终证据以 CI 为准：推送 `verify/A4a-probes`，`gh run watch <id> --interval 60`。不访问真实论坛。

【背景】契约（`/home/mir/Documents/hipda-next.wt/_cards/A4a.md` 范围第 1 项）：规则 A：状态码 ∈ {403, 429, 503} 且任一 `cf-mitigated` 头值（不区分大小写）含 `challenge` → `Challenge(byHeader=true)`。规则 B（无 `cf-mitigated` 头时）：状态码 ∈ {403, 503}；Content-Type 为空或含 `text/html`；窥探前 16KB，命中任一：含 `_cf_chl_opt`；含 `/cdn-cgi/challenge-platform/h/` 或 `orchestrate/chl_page`；同时含 `<title>Just a moment`（不区分大小写）与 `cloudflare` 或 `cf-challenge`。仅含 `challenge-platform` 或 `scripts/jsd/main.js` 不算。不消耗原响应体。实测背景见 `docs/design/cloudflare-research.md` 第四节。

【步骤】
1. 只看生产代码 `hipda/src/main/java/net/jejer/hipda/data/network/ChallengeDetector.kt`，不要先读实现者的测试。
2. 在 `hipda/src/test/java/net/jejer/hipda/verify/A4aProbeTest.kt` 写探针，侧重实现者容易漏的地方：
   a. 真实感夹具：按 `cloudflare-research.md` 第四节表格第 1 行（`/forum/` 托管验证：403、`cf-mitigated: challenge`、`Just a moment...`、`window._cf_chl_opt`、`cType: 'managed'`）与第 6、9、10 行（200 GBK 正文、页尾注入 `/cdn-cgi/challenge-platform/scripts/jsd/main.js` 与 `window.__CF$cv$params`）、第 8 行（500 + JSD）构造夹具；正文用真实 GBK 字节（中文标题 + 实体）。
   b. gzip 压缩的验证页（`Content-Encoding: gzip`）：经 OkHttp 透明解压后仍能命中规则 B。
   c. 特征恰好跨越 16KB 边界（前半在 16KB 内、后半在外）→ 不命中；特征完整落在 16KB 最后几个字节内 → 命中。
   d. `Content-Type: TEXT/HTML; Charset=GBK`（大写）命中；`application/xhtml+xml` 不命中（按契约只认 `text/html`，若实现命中请报告并说明是否应视为问题）。
   e. 503 + `Retry-After` + 普通维护页（不含任何强特征）→ 不命中。
   f. `inspect` 后：原响应体完整可读且字节一致；对同一响应连续调用两次 `inspect` 结果一致。
   g. 并发：16 个线程共享一个 `ChallengeDetector` 实例同时检测不同响应，结果全部正确。
3. 推送并取得 CI 结果。探针失败时先判断是实现问题还是探针写错（依据契约），只有实现问题才算 FAIL。

【交付】最后一条回复第一个词：DONE（完成验证，无论 PASS 或 FAIL）、NEEDS-INPUT 或 STUCK。前约 1000 字：总判定 PASS/FAIL + 被验 commit `e75ef76`、CI run 链接、每个探针一行、FAIL 项触发路径。不要在拿到 CI 结果前结束回合。保留 `verify/A4a-probes` 分支和 worktree。
