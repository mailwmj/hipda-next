# 验证卡 V-E2a：独立视觉复验与行为探针验证 E2a（commit 60bd5b7）

【角色】独立视觉与交互行为验证者，不先读实现者的测试与 `_reports/E2a.md` 报告；结论只来自你逐张审阅录制截图与自己编写跑出的探针证据。
【边界】对象 `origin/card/E2a-thread-detail-core` @ `60bd5b7`，相对 `db7dba6`，只读：不得向 `card/E2a-thread-detail-core` 推送，不得修改 `main`，不得覆盖或修改任何既有截图基线。工作区：`git -C /home/mir/Documents/hipda-next worktree add /home/mir/Documents/hipda-next.wt/V-E2a -b verify/E2a-probes 60bd5b7`，只在这里写探针测试。本地 Gradle：先 `export ANDROID_HOME=/home/mir/.local/share/mise/installs/android-sdk/21.0 JAVA_HOME=/home/mir/.local/share/mise/installs/java/latest; export PATH="$JAVA_HOME/bin:$PATH"`，再 `flock /tmp/hipda-gradle.lock nice -n 10 ionice -c3 ./gradlew <任务> -Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.workers.max=4`，结束 `./gradlew --stop`。先本地跑通全部检查与探针，再推送 `verify/E2a-probes` 并 `gh workflow run android.yml --ref verify/E2a-probes` 跑一次 CI，`gh run watch <id> --interval 60`。严禁访问真实论坛（4d4y.com），所有网络验证只用离线假实现/`MockWebServer` + 仓内脱敏样本。详报写 `/home/mir/Documents/hipda-next.wt/_reports/V-E2a.md`。
【背景】契约见任务卡 `/home/mir/Documents/hipda-next.wt/_cards/E2a.md` 范围与验收节（注：双向连续加载已拆分至 `E2d`，本卡翻页沿用 `E0` 上下页按钮）；基座与数据层 API 参考 `_reports/{E0,D5,B8}.md`（**以基线源码为准**）；依据 `docs/design/product-design.md`（v0.4 §5/§6.3/§7.3/§10）、`docs/design/copy.md`、`_cards/BATCH-5.md`。只读生产代码与截图，不先读实现者测试与报告。
【视觉与截图基线逐张复验】
1. 执行 `git diff --name-status db7dba6...60bd5b7 -- hipda/src/test/snapshots/`，确认仅新增 `thread_detail_*.png` 且既有基线（`theme_specimen_*`/`shell_*`/`glass_*`/`challenge_*`）**0 漂移**，并在本地与 CI 跑通 `-Proborazzi.test.verify=true`；
2. 用读图工具逐张检查全部 `hipda/src/test/snapshots/images/thread_detail_*.png`（不含连续加载头尾态）：
   - 核对单栏（`411`/`559`/`640dp`）与大屏详情宽（`411`/`448`/`680dp`）× 浅/深的排版均衡、正文最大宽 `680dp` 居中、`{N}楼` 角标与头像/时间行不拥挤、第 5 档字号×1.3 不裁切溢出；
   - 专项检查**详情透明极简顶栏 + 轻底纯色圆钮**与 **`只看楼主 ×` 过滤胶囊**：确认圆钮轻透可读、层次干净、无双层模糊痕迹；
   - 专项检查**原生富文本 + 表情 + 浅/深/OLED 调色**与空/错/骨架态：确认论坛表情基线对齐、彩色文字在深色与纯黑背景上均清晰可读、图片占位块比例整齐。
【探针】（写在 `hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt`，Robolectric Compose UI 独立探针，严禁访问真实站点）：
a. **极端文字色 × 浅/深/OLED 纯黑对比度探针（B4-E2a ②）**：对 `#000000`、`#000080`、`#FFFF00`、`#FFFFFF` 四种极端颜色分别在浅色表面、深色表面与 OLED 纯黑（`#000000`）表面调用 `TextColorContrastAdjuster`，硬断言全部 12 组输出颜色与背景的 WCAG 2.1 对比度 **`≥ 4.5:1`**。
b. **原生富文本、表情深浅切换、上下页切页与 `只看楼主` 退出定位探针**：使用含 3 套论坛表情 `normalizedKey`、链接、换行、跳楼标记与彩色文字的 `PostFloor` 渲染 `ThreadDetailPane`，验证浅/深主题切换后表情与调色即时更新且不丢滚动位置；验证沿用 `E0` 上一页/下一页按钮切页正常；滚至中间楼层开启 `只看楼主` 验证出现 `只看楼主 ×` 胶囊，点 `×` 退出后断言返回全帖并重新定位到原楼层；注入无匹配楼层验证显示 `没有符合条件的楼层` + `看全部`。
c. **玻璃拓扑与 Wave 3 `E2b` 接缝探针（B2-2）**：检查详情页语义树与 `LayoutInfo`——硬断言 `HiGlassMarker == 1`、顶栏与 `LazyColumn` 为严格兄弟、顶栏圆形按钮零 `HiGlassMarker` 且触控区 ≥ `48×48dp`；验证 `PostImageBlock` 占位被实际挂载且楼层列表使用 `floor:$postId` 稳定键。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：总判定 PASS/FAIL + `60bd5b7`、CI 链接、视觉逐张审阅与 3 个探针（a–c）逐项结论、FAIL 触发路径、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/V-E2a.md`。不要在拿到 CI 结果前结束回合。保留分支与 worktree。

【改哪些文件】
- 独立验证分支（`verify/E2a-probes`，不推实现分支/不改 `main`）：
  - 新增 `hipda/src/test/java/net/jejer/hipda/verify/E2aProbeTest.kt`
- 报告（仓外）：
  - `/home/mir/Documents/hipda-next.wt/_reports/V-E2a.md`
