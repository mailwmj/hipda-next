package net.jejer.hipda.feature.threaddetail.body

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import net.jejer.hipda.R
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser

/** 论坛表情解析结果（闭合 `C1b#2`：直接将 `InlineSpan.Emoji.normalizedKey` 映射至 `EmojiMap`）。 */
@Immutable
sealed interface ResolvedForumEmoji {
    @Immutable data class Drawable(@param:DrawableRes val drawableResId: Int, val code: String?) : ResolvedForumEmoji
    @Immutable data class FallbackText(val text: String) : ResolvedForumEmoji
}

@Immutable
internal data class InlineDrawableSpec(
    @param:DrawableRes val drawableResId: Int,
    val contentDescription: String,
    val sizeScale: Float,
)

/** 根据 `InlineSpan.Emoji.normalizedKey` 解析深浅色资源，未知键安全回退至代码或可读占位。 */
fun resolveForumEmoji(normalizedKey: String, darkTheme: Boolean): ResolvedForumEmoji {
    val key = normalizedKey.trim()
    if (key.isEmpty()) return ResolvedForumEmoji.FallbackText("[表情]")
    val resId = EmojiMap.drawableForImageSrc(key, dark = darkTheme)
    val code = EmojiMap.codeForImageSrc(key)
    return if (resId != null) ResolvedForumEmoji.Drawable(resId, code) else ResolvedForumEmoji.FallbackText(code ?: "[$key]")
}

/** 根据 `InlineSpan.SmallImage.key` 解析内置小图资源（如客户端角标 `win`）。 */
@DrawableRes
fun resolveSmallImageDrawable(key: String): Int? =
    if (key == ThreadDetailParser.SMALL_IMAGE_WIN_KEY) R.drawable.win else null

internal fun buildInlineTextContentMap(
    specs: Map<String, InlineDrawableSpec>,
    bodyFontSize: TextUnit,
): Map<String, InlineTextContent> {
    if (specs.isEmpty()) return emptyMap()
    val baseSp = bodyFontSize.value.coerceAtLeast(12f)
    return specs.mapValues { (_, spec) ->
        val sizeSp = (baseSp * spec.sizeScale).sp
        InlineTextContent(Placeholder(sizeSp, sizeSp, PlaceholderVerticalAlign.TextCenter)) {
            Image(
                painter = painterResource(spec.drawableResId),
                contentDescription = spec.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
