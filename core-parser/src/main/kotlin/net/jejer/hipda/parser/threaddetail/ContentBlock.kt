package net.jejer.hipda.parser.threaddetail

/**
 * 正文行内文字格式标记（不可变值对象）。
 *
 * 遵循 tech-plan §5 T6：[rawColor] 原样透传 HTML `<font color="...">` 的 CSS 颜色字符串，
 * 不在解析阶段做深浅色可读性转换或丢弃，由渲染层按当前主题决定可读性适配。
 */
data class TextFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val smallFont: Boolean = false,
    val rawColor: String? = null,
) {
    val isPlain: Boolean
        get() = !bold && !italic && !underline && !strikethrough && !smallFont && rawColor == null

    companion object {
        val PLAIN = TextFormat()
    }
}

/**
 * [ContentBlock.Text] 内的有序行内富文本元素，供 `E2a` Compose `AnnotatedString` + `InlineTextContent` 原生渲染。
 */
sealed interface InlineSpan {

    /** 纯文本片段（已解码二次转义数字实体，不含 HTML 实体转义乱码）。 */
    data class Plain(
        val text: String,
        val format: TextFormat = TextFormat.PLAIN,
    ) : InlineSpan

    /** 行内超链接（支持 `<a>` 标签、纯文本 URL 自动识别与小字号旧域名 `.hi-pda.com` 替换）。 */
    data class Link(
        val label: String,
        val url: String,
        val isSmallFont: Boolean = false,
    ) : InlineSpan

    /** 行内邮箱地址。 */
    data class Email(
        val email: String,
    ) : InlineSpan

    /**
     * 行内论坛表情。
     *
     * @property normalizedKey 按 `C1b#2` 规则去 `images/smilies/` 前缀、去扩展名并将 `/` 替换为 `_` 的归一化键
     *   （如 `"default_smile"`、`"coolmonkey_01"`、`"grapeman_08"`），可直接匹配 `:emoji` `EmojiMap`。
     * @property rawSrc 原始图片地址。
     */
    data class Emoji(
        val normalizedKey: String,
        val rawSrc: String,
    ) : InlineSpan

    /**
     * 行内内置小图标记（如旧 `SmallImages` 对应的客户端角标），纯数据标记不依赖 Android `R.drawable`。
     */
    data class SmallImage(
        val key: String,
        val rawSrc: String,
    ) : InlineSpan

    /** 行内显式换行（`<br>`、表格行或列表项分隔）。 */
    data object LineBreak : InlineSpan
}

/**
 * 帖子楼层正文结构化内容块密封契约（纯 JVM、不可变，供给 `E2a`–`E2c` Compose 原生渲染）。
 */
sealed interface ContentBlock {

    /** 富文本段落块，内部保留有序 [InlineSpan] 列表。 */
    data class Text(
        val spans: List<InlineSpan>,
    ) : ContentBlock

    /**
     * 引用块（对应 `div.quote`）。
     *
     * 已在局部克隆子树上排除 `font[size=2]` 作者时间行及 `display:none` 隐藏节点，不修改原 DOM。
     */
    data class Quote(
        val authorAndTime: String,
        val contentHtml: String,
        val contentText: String,
        val targetTid: String?,
        val targetPid: String?,
    ) : ContentBlock {
        /** 从 [authorAndTime] 中提取的原作者名（按 `"发表于"` 拆分），不存在时为 `null`。 */
        val author: String?
            get() = authorAndTime.substringBefore("发表于", missingDelimiterValue = "")
                .trim()
                .ifEmpty { null }

        /** 从 [authorAndTime] 中提取的原发帖时间（按 `"发表于"` 拆分），不存在时为 `null`。 */
        val time: String?
            get() = authorAndTime.substringAfter("发表于", missingDelimiterValue = "")
                .trim()
                .ifEmpty { null }
    }

    /** 跳楼回复标记（对应 `<strong>` 内 `回复 N# Author` 与 `redirect.php?goto=findpost`）。 */
    data class GoToFloor(
        val label: String,
        val targetTid: String?,
        val targetPid: String?,
        val floor: Int,
        val author: String,
    ) : ContentBlock

    /**
     * 正文或附件图片块。
     *
     * @property fullUrl 原图优先地址（`onclick="zoom(...)"` > `file` > `thumbUrl` > 外链 `src`）。
     * @property thumbUrl 缩略图地址（仅当 `src` 含 `thumb.` 时非空）。
     * @property fileSizeBytes 解析到的文件字节数；未知时为 `0L`，尺寸文本无法解析时为 `-1L`。
     */
    data class Image(
        val fullUrl: String,
        val thumbUrl: String?,
        val fileSizeBytes: Long,
    ) : ContentBlock

    /** 附件块（覆盖行内 `span a[href*=attachment.php?]` 非 `nothumb`、直出 `a[href^=attachment.php?]` 及文末 `dl.t_attachlist p.attachname`）。 */
    data class Attachment(
        val url: String,
        val title: String,
        val description: String?,
    ) : ContentBlock

    /** 提示信息块（锁定楼层 `div.locked`、缺失正文、空正文或空 src 异常图片提示）。 */
    data class Notice(
        val message: String,
    ) : ContentBlock
}
