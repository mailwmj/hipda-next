# 任务卡 A5c：统一 UA 改为"桌面站点"身份，拿回桌面版 Discuz 模板

【背景（真机 M1 阻断）】真机实测：统一 UA 是移动版 Chrome UA（含 `Android`），4d4y.com 因此下发**手机版模板**：无 `#umenu`，用户名提取失败；`pm.php`、`notice.php` 返回 500。负责人 2026-11-25 用只读 GET 实测 `logging.php?action=login`：
- 含 `Android` 的 UA（不管有没有 `Mobile`）→ 手机模板（6147 字节，无 `id="umenu"`）；
- `Mozilla/5.0 (X11; Linux x86_64) … Chrome/131.0.0.0 Safari/537.36` 和旧版 `net.jejer.hipda 2.8.19` → 桌面模板（10765 字节，有 `id="umenu"`）；
- 去掉 `Android` 只留 `Linux; Pixel 8 … Mobile Safari` → 桌面模板。即触发词是 `Android`。
另：`/forum/` 首页对所有 UA 都弹 Managed Challenge；`forumdisplay.php` 对所有 UA 都直接 200。

【目标】WebView 和网络层继续共用**同一个** UA（cf_clearance 和 UA 绑定，这条不变），但改成 Chrome for Android"请求桌面版网站"那样的桌面身份，并让 Client Hints 与它一致：
1. UA：取 WebView 真实 UA 中的 Chrome 版本号，生成 `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<真实主版本>.0.0.0 Safari/537.36`（不含 `Android`、`Mobile`、`; wv`、`Version/4.0`）。取不到时用同格式的固定默认值。`DefaultUserAgentProvider` 的默认值同步改为桌面格式。
2. WebView：`settings.userAgentString` 设为上面的 UA；若 `WebViewFeature.USER_AGENT_METADATA` 可用，用 `WebSettingsCompat.setUserAgentMetadata` 设 platform=`Linux`、mobile=false、品牌版本与 UA 一致，让 `navigator.userAgentData` 和 `Sec-CH-UA-*` 不再自称 Android。不可用时只设 UA，不崩。挑战页 WebView（A5b/A6）、登录 WebView（B1b debug）都必须走同一个设置入口。
3. 网络层：若 `BrowserHeaderInterceptor` 发送 `Sec-CH-UA-*`，改为 `Sec-CH-UA-Mobile: ?0`、`Sec-CH-UA-Platform: "Linux"`，与 WebView 一致；不发则不新增。
4. 文档：`docs/design/tech-plan.md` 第 60 行 UA 一条改写为新策略和理由（触发词 `Android` → 手机模板）。

【范围】`data/network/UserAgentProvider.kt`、`data/network/webview/WebViewUserAgentProvider.kt`、WebView 设置入口（及其调用方）、`BrowserHeaderInterceptor.kt`、对应测试、tech-plan.md 一行。不改 Cookie 桥、网关、求解状态机的逻辑。

【验收】每条给证据：
- 单测：多组真实 WebView UA 样例（Android 10/14、含 `; wv`、含 `Version/4.0`、空串）都转换成不含 `Android`/`Mobile` 的桌面 UA，Chrome 主版本保留。改坏一行测试会红。
- 单测：挑战 WebView、登录 WebView、OkHttp 请求三处 UA 字符串完全相同。
- 单测：metadata 不可用时不崩；可用时 platform=Linux、mobile=false（用假 WebSettings 断言）。
- 源码扫描：`src/main` 和 `src/debug` 中除转换函数和它的测试样例外，不再出现 `Mobile Safari` 字面量。
- CI 全绿。不得访问真实站点（负责人的实测已写在背景里）。

【交付】分支 `card/A5c-desktop-ua`，worktree `/home/mir/Documents/hipda-next.wt/A5c`，基于 `origin/main` @ `c6aba62`。先本地跑相关测试再推送。最终回复 ≤600 字：DONE/PARTIAL/NEEDS-INPUT/STUCK + commit + CI 链接 + 每条验收一行；细节写 `/home/mir/Documents/hipda-next.wt/_reports/A5c.md`。
