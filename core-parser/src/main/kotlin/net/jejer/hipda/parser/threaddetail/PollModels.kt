package net.jejer.hipda.parser.threaddetail

/**
 * 1 楼投票单个选项不可变模型（对照旧 `PollOptionBean`）。
 *
 * @property optionId 选项提交值（`td.selector input[value]`）；已投票或不可投时为 `null`。
 * @property text 选项文本（经 `DiscuzTextDecoder` 解码二次转义数字实体）。
 * @property ratesText 票数与占比文本（如 `"60.00% (12)"`）；未展示结果时为 `null`。
 */
data class PollOption(
    val optionId: String?,
    val text: String,
    val ratesText: String? = null,
)

/**
 * 1 楼投票不可变模型（对照旧 `PollBean` 及 `HiParserThreadDetail.java:202-257`）。
 *
 * @property titleHtml 投票摘要 HTML（来自 `div.pollinfo`，已排除 `<a>` 且不修改原 DOM）。
 * @property timerHtml 投票倒计时/截止时间 HTML（来自 `p.polltimer`），缺省为 `null`。
 * @property formhash 投票表单提交用 `formhash`，不可投票或无表单时为 `null`。
 * @property maxChoices 最多可选票数，单选默认为 `1`；多选按“最多可选 N 项”解析且至少为 `1`。
 * @property options 有序投票选项列表。
 * @property footer 投票表尾说明文本（已排除 `<button>` 文本且不修改原 DOM），为空时为 `null`。
 */
data class ThreadPoll(
    val titleHtml: String,
    val timerHtml: String?,
    val formhash: String?,
    val maxChoices: Int = 1,
    val options: List<PollOption>,
    val footer: String?,
) {
    /** 是否为多选投票。 */
    val isMultipleChoice: Boolean
        get() = maxChoices > 1

    /** 与旧 `PollBean.getTitle()` 对齐的合并标题 HTML（存在 [timerHtml] 时以 `<br>` 拼接）。 */
    val combinedTitleHtml: String
        get() = if (timerHtml.isNullOrEmpty()) titleHtml else "$titleHtml<br>$timerHtml"
}
