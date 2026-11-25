# 真机操作清单：M1 验收、样本导出、M4 外壳验收

适用版本：`main` @ `f7e0bcf`（A 线 Cloudflare 通道 + B1b 样本导出页已合入）。

## 0. 取安装包

debug 包在 CI 产物里（私有仓库，需登录 GitHub）：

- 网页：<https://github.com/mailwmj/hipda-next/actions/runs/45578432700> → 页面底部 Artifacts → `debug-apk`（下载后解压得到 `hipda-debug.apk`）
- 命令行：`gh run download 45578432700 -R mailwmj/hipda-next -n debug-apk`

安装：`adb install -r hipda-debug.apk`。包名 `net.jejer.hipda.next`，可与旧版并存。

## 一、样本导出 + M1 验收（约 10 分钟）

全程只发只读 GET 请求：不发帖、不回复、不发短消息、不改收藏，不保存密码。

1. 打开桌面「**HiPDA 预览**」→ 右上角「**样本导出**」。
2. 点「**登录**」，在内置网页里登录论坛（先出现 Cloudflare 验证就先完成验证）。
3. 登录后点「**完成登录**」，确认顶部显示「**已登录**」。
4. 隐私开关「**包含我的帖子、我的回复与收藏**」：这三页已去掉账号名、UID、令牌，但保留帖子编号，可在论坛反查到你。仓库以后若可能公开，建议关闭。
5. 点「**一键抓取并导出**」。中途弹出验证就按提示完成，抓取会自动继续。
6. **M1 验收**：第 1 项「版块列表 · 第 1 页」变为「**成功**」后截图，画面里要同时有「已登录」和这一项。
7. 个别项「失败」可点「重试」；出现「**脱敏校验未通过**」的项不会进压缩包，把详情记下来反馈即可。
8. 完成后点「**分享**」，把 `hipda-samples-<时间戳>.zip` 传到那台电脑。

## 二、M4 外壳验收（约 5 分钟，需 Android 12+）

「HiPDA 预览」→「**外壳**」：

1. **毛玻璃**：底部导航胶囊后面的内容是否真的模糊透出；深色、浅色各看一次；浅色下文字是否清楚。
2. **滚动**：快速上下滑，导航隐藏和出现是否顺滑，有无明显卡顿或闪烁。
3. **旋转 / 折叠**：横竖屏切换、折叠屏展开合上，布局是否正常、有无崩溃。

## 三、交回结果

在那台电脑上：

```bash
git clone https://github.com/mailwmj/hipda-next.git && cd hipda-next
git switch -c samples/b1b
unzip <路径>/hipda-samples-*.zip -d hipda/src/test/resources/samples/
git add hipda/src/test/resources/samples && git commit -m "test(samples): 真机导出脱敏样本"
git push -u origin samples/b1b
```

推送后 CI 会自动跑脱敏扫描（`SamplesAreSanitizedTest`）。然后告诉负责人：

1. 已推送 `samples/b1b`；
2. M1 截图（直接发给负责人即可，不必进仓库）；
3. M4 三项结论：每项"正常"或描述看到的问题。
