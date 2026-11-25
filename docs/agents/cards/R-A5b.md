# 审查卡 R-A5b：透明验证宿主与 WebView 自动过盾（commit 0e0da1b）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/A5b-challenge-host` @ `0e0da1b49f73306e9cab675ed48adfb380024b85`，相对 `0247458`。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 0e0da1b:<路径>`、`git diff 0247458...0e0da1b`；可解压 jar 到 `/tmp/R-A5b/` 用 `javap` 核对 `androidx.webkit` API；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45555091962`、`gh run view 45556082109`（模拟器冒烟）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-A5b.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/A5b.md`；实现者的真机验收清单在 `_reports/A5b.md`（只看清单，不看自评）。依据 `docs/design/tech-plan.md` §5“验证面板有独立的宿主（T3）”、§6；`docs/design/cloudflare-research.md` 第五节第 3 小节；`docs/design/product-design.md` §7.12。阻断标准 `docs/agents/DISPATCH.md` §4（Cloudflare 通道失效、旋转/折叠崩溃须给触发路径）。
【审查重点】
1. Activity 生命周期：透明主题是否真的不遮挡、不抢焦点；旋转/折叠/多窗口/进程被杀时 WebView 与求解状态如何（重建 Activity 会不会丢掉进行中的求解、泄漏 WebView 或让网关等到 120 秒超时）；从后台（无前台 Activity）启动时 Android 10+ 的后台启动 Activity 限制是否会让验证静默失败；`singleTop`、`exported=false`、`taskAffinity`/`excludeFromRecents` 是否会在最近任务里留下空白页或打断当前任务栈。
2. WebView：真实视口尺寸 + `alpha=0` 是否不可触摸不可聚焦；UA 与 `UserAgentProvider` 一致；JS/DOM storage/第三方 Cookie；`addDocumentStartJavaScript` + `addWebMessageListener` 的来源限制（只接受 4d4y.com / challenges.cloudflare.com）与退化路径；`onRenderProcessGone` 返回 true；销毁顺序（`stopLoading`、`removeAllViews`、`destroy`）防泄漏。
3. 判定通过的条件：页面加载完成 + 主帧不再是验证页 + 有快照外的新 `cf_clearance`；会不会把仍在验证中的页面误判为通过，或在 `cf_clearance` 写入稍晚时误判超时。
4. 与 A4b/A5a 的契约：8 秒预算 + 交互等待在网关 120 秒总超时内；超时、取消、失败时都 `restoreClearance` 并关闭 Activity；调用方取消（网关取消 solverJob）时 Activity 一定关闭；`syncFromWebView` 只在通过后调用。
5. 测试：WebView 抽象接口的假实现是否按真实 WebView 行为建模（回调线程、页面多次 `onPageFinished`、重定向）；举“改坏一行测试仍通过”的例子（若存在）。
6. 复杂度：按 DISPATCH §5 判断有无可删的过度设计。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `0e0da1b`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径。
