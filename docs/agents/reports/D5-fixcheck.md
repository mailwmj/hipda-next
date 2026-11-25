# 修复轮核对报告 D5-fixcheck（只读，不跑构建）

- **结论**：**PASS**
- **对象**：`origin/card/D5-thread-detail-repo` @ `ec0a5e8741fc79ce890abcc2a3efbafa43380fbc`（相对 `R-D5` 评审基线 `65000ff4915a5ea71007aab6687cc2c48cfab1b1`）
- **CI 核实**：[Android CI #45647302483](https://github.com/mailwmj/hipda-next/actions/runs/45647302483)（`headSha` 精确匹配 `ec0a5e8741fc79ce890abcc2a3efbafa43380fbc`，`completed / success`）
- **改动范围核查**：`git diff --stat 65000ff..ec0a5e8` 仅改 2 个授权文件（`DefaultThreadDetailRepository.kt`、`ThreadDetailRepositoryTest.kt`，`+515 / -13`），**无范围外改动**，未改对外接口或 DAO。

## 逐条核对（对照 `_reports/R-D5.md` S1–S4）

1. **S1（非第 1 页不以回帖层主覆盖浏览历史楼主名）**：**PASS**。`DefaultThreadDetailRepository.kt:487-510` 仅从 `floor == 1` 或 `AuthorOnly` 模式取楼主名，其余非首页先查 `browseHistoryDao.observeRecent(Int.MAX_VALUE)` 保留原 `author`/`forumName`/`fid`（无历史时保持 `""`）。新增测试覆盖第 3 页→第 1 页→第 2 页/`UserOnly`/`loadByPostId` 及仅进末页，回退修复后 5 处断言必败。
2. **S2（收紧投票成功判定）**：**PASS**。`DefaultThreadDetailRepository.kt:743-768, 858-863` 移除宽泛礼貌/跳转词，仅保留 `"投票已经提交"`/`"投票已提交"`/`"投票成功"` 并先排除认证提示与 `POLL_ERROR_MARKERS`。新增测试覆盖礼貌/跳转尾缀 XML 与报错+提交词叠加 XML（断言 `SiteError` 且 0 刷新），回退任一半修复测试均必败。
3. **S3（`loadByPostId(tid = null)` 页头脚本 `tid = parseInt` 兜底）**：**PASS**。`DefaultThreadDetailRepository.kt:434-436, 770, 812-821` 仅扫描 `doc.head().select("script")` 匹配 `\btid\s*=\s*parseInt\(\s*['"](\d+)['"]\s*\)`。新增测试构造无 URL/表单/DOM `tid` 且正文含伪造脚本的直出页，断言 `success.tid == "77003"` 及历史落盘，回退修复后必败。
4. **S4（4 处单行变异单测与构造边界）**：**PASS**（附 1 条微注）。
   - S4-a：`loadLastPage`/`loadByPostId`/`AuthorOnly(null)` 第 1 页预取过期静默重登 3 条路径均断言 `Success` + `reloginCalls` 递增，回退任一 `requireAuth` 必败；
   - S4-b：`:49` 收紧 `require(maxBrowseHistory >= 1)`，直接测 6 参 `@Inject` 构造与 `maxBrowseHistory = 0` 抛 `IllegalArgumentException`，回退必败；
   - S4-c：`submitPollVote` 刷新断言 `browseHistoryDao` 为空，回退 `:360` `recordHistory = false` 必败（微注：`:493` `fallbackOpAuthor` 因同块 `:494` 亦回退 `AuthorOnly` 第 2 页首楼作者且夹具 2 页作者非空，仅单删 `:493` 仍由 `:494` 兜住，属良性防御冗余）；
   - S4-d：多选 `listOf("2", "10", "2")` 断言表单有序对 `["10", "2"]`，回退字典序排序必败。
