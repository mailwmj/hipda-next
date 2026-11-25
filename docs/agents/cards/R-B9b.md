# 审查卡 R-B9b：搜索/简单列表/用户资料/黑名单/发帖表单/收藏/上传解析器（commit 14dc33db512d1a4483292d0f72aa52a631e0b7b6）

【角色】独立代码审查者，只读。
【边界】对象 `origin/card/B9b-parser-search-user-post` @ `14dc33db512d1a4483292d0f72aa52a631e0b7b6`，相对 `{BASE}`。`git -C /home/mir/Documents/hipda-next fetch origin` 后用 `git show 14dc33db512d1a4483292d0f72aa52a631e0b7b6:<路径>`、`git diff {BASE}...14dc33db512d1a4483292d0f72aa52a631e0b7b6` 读代码；不 checkout、不提交推送、不在主目录切换分支、不编译。CI：`gh run view 45610947130`（核实 `headSha` 匹配、含 `:core-parser:test`、`:hipda:testDebugUnitTest`、`:hipda:assembleDebug`、`:hipda:assembleRelease`、`:hipda:lintDebug`）。详报写 `/home/mir/Documents/hipda-next.wt/_reports/R-B9b.md`。
【背景】任务卡 `/home/mir/Documents/hipda-next.wt/_cards/B9b.md`；实现者报告 `/home/mir/Documents/hipda-next.wt/_reports/B9b.md`（可读，所有结论须以代码与 diff 核实为准）。依据 `docs/design/tech-plan.md` §5（T2）、§8（M2）、`hipda/src/main/java/net/jejer/hipda/utils/HiParser.java:57-211,525-785,806-825`、`PrePostAsyncTask.java:86-229`、`FavoriteHelper.java:104-133,218-257`、`UploadImgHelper.java:210-227` 及 `B9a` 公共基座。阻断标准 `docs/agents/DISPATCH.md` §4（敏感区 `HiParser*`/发帖表单解析回归、破坏调用方 DOM、测试访问真实站点、越界修改旧代码/样本/同波 `D2`/`B8`/在途 `C4c`/`G1`，须给具体触发路径）。
【审查重点】
1. **只读边界与零冲突**：核查 `git diff --stat {BASE}...14dc33db512d1a4483292d0f72aa52a631e0b7b6`——确认 `hipda/src/main/`（含 `HiParser.java`、`PrePostAsyncTask.java`、`FavoriteHelper.java`、`UploadImgHelper.java`）、`core-parser` 其他包、`SampleFixtures.kt`、`LegacyTestSupport.kt`、`samples/`、`ui/`、`data/`、共享文件零改动；确认全部解析函数零 Android 依赖、零设置写入、零 DOM 变更副作用。
2. **复用 `B9a` 基座与一致性**：核查 4 组解析器是否直接复用 B9a `DiscuzCommonParser`/`DiscuzPageMeta`、`DiscuzTextDecoder`（尤其 `search_result.html` 中的 `&amp;#\d{5,6};`）、`DiscuzUrlHelper`（`firstMatch`、分页整数提取、`avatarUrlForUid`）、`SampleFixtures` 与 `LegacyTestSupport`，未重复造轮子；核查 `_reports/B9b.md` 对外 API 清单是否满足下游 `D6`/`D8a`/`D8b` 直接接入。
3. **4 组领域逐字段等价与边界处理**：
   - 搜索（标题 + 全文 `sp_theard` 5 `span`）：`searchId`、`maxPage`、`tid`/`pid`/`title`/`snippet`/`author`/`avatarUrl`/`time`/`forumName`；
   - 简单列表（我的帖子/我的回复奇偶行/收藏）：尤其核查 `my_posts.html` 奇偶行错位或单行残缺时不会 NPE 或漏项；
   - 用户资料与黑名单：核查 `user_profile.html` 全部字段与 `h3.blocktitle` 拼装；黑名单去重、空态（`暂无数据`）与非法页判错；
   - 发帖表单/收藏/上传：核查 `PrePostParser` 对 6 种模式（尤其 `REPLY_POST`/`QUOTE_POST` 放 `quoteText` vs 其他放 `text`、`notice*` 三字段、3 种图片/附件来源合并去重、`uploadinfo` KB/MB 字节换算、`typeOptions` 保序与默认选中）；核查 `FavoriteParser` 仅提取有效 `tid` 与 XML 截断 `<` 前文案；核查 `UploadResponseParser` 拒绝 `imgId=="0"` 与段数不足响应。
4. **测试质量与复杂度**：核实两份等价测试类是否真正对 6 份真机样本与合成夹具逐字段断言（而非仅断非空），指出“改坏关键一行测试仍通过”的盲区（若存在）；评估有无过度设计。
【交付】最后一条回复 ≤600 字，第一个词 DONE/NEEDS-INPUT/STUCK：APPROVE/CHANGES-REQUESTED + `14dc33db512d1a4483292d0f72aa52a631e0b7b6`、阻断项（触发路径 + 文件:行号 + 后果）、建议项一行一条、详报路径 `/home/mir/Documents/hipda-next.wt/_reports/R-B9b.md`。
