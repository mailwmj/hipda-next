package net.jejer.hipda.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * HiPDA 版块静态元数据（对应旧版 `HiUtils.java:84-106` 的 21 个内置版块，不依赖 FontAwesome）。
 */
@Immutable
data class ForumMetadata(
    val fid: Int,
    val fullName: String,
    val shortLabel: String,
    val icon: ImageVector?,
) {
    /** 圆角首字/短标徽标文本（最多 2 个拉丁字母/数字或 1 个汉字）。 */
    val badgeMonogram: String
        get() = normalizeForumMonogram(shortLabel.ifBlank { fullName })
}

/** 64dp 收起图标轨上的版块展示模式（product-design.md §3.2）。 */
@Immutable
sealed interface CollapsedForumDisplay {
    /** 默认：居中 22dp 纯矢量图标（与顶部功能入口和底部按钮统一节奏，不显示下方字母/文字）。 */
    data class IconOnly(
        val icon: ImageVector,
    ) : CollapsedForumDisplay

    /** 回退：未知 `fid` 或图标撞车时的 28×28dp 圆角短标/首字徽标。 */
    data class MonogramBadge(
        val badgeText: String,
    ) : CollapsedForumDisplay
}

/**
 * 纯函数计算 64dp 收起轨中每个关注版块的展示形态：
 * - 若版块有内置矢量图标，且在当前关注列表中无其他版块映射到同一图标，展示 [CollapsedForumDisplay.IconOnly]；
 * - 若版块无内置图标（未知 `fid`）或与列表中其他版块图标撞车，自动回退为 [CollapsedForumDisplay.MonogramBadge]。
 */
fun resolveCollapsedForumDisplays(
    forums: List<ForumMetadata>,
): Map<Int, CollapsedForumDisplay> {
    val iconCounts = HashMap<ImageVector, Int>(forums.size)
    for (forum in forums) {
        val icon = forum.icon ?: continue
        iconCounts[icon] = (iconCounts[icon] ?: 0) + 1
    }
    return forums.associate { forum ->
        val icon = forum.icon
        val display = if (icon != null && (iconCounts[icon] ?: 0) == 1) {
            CollapsedForumDisplay.IconOnly(icon = icon)
        } else {
            CollapsedForumDisplay.MonogramBadge(badgeText = forum.badgeMonogram)
        }
        forum.fid to display
    }
}

/** 将版块名或短标规范化为最多 2 个拉丁字母/数字或 1 个汉字的徽标文本。 */
fun normalizeForumMonogram(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "#"
    val first = trimmed[0]
    if (first.isAsciiAlphaNum()) {
        val words = trimmed.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> first.uppercase()
        }
    }
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1))
}

private fun Char.isAsciiAlphaNum(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

/** 21 个内置版块静态元数据注册表，供侧栏、预览页与论坛页共用。 */
object ForumMetadataRegistry {
    /** 默认关注的 3 个版块（Discovery、Buy & Sell、Geek Talks，与 `AppSettings.DEFAULT_FOLLOWED_FORUMS` 一致）。 */
    val DefaultFollowedForumIds: List<Int> = listOf(2, 6, 7)

    val BuiltInForums: List<ForumMetadata> = listOf(
        ForumMetadata(fid = 2, fullName = "Discovery", shortLabel = "D", icon = ForumVectorIcons.Compass),
        ForumMetadata(fid = 6, fullName = "Buy & Sell", shortLabel = "BS", icon = Icons.Rounded.ShoppingCart),
        ForumMetadata(fid = 7, fullName = "Geek Talks", shortLabel = "极", icon = ForumVectorIcons.Code),
        ForumMetadata(fid = 59, fullName = "E-INK", shortLabel = "墨", icon = ForumVectorIcons.Book),
        ForumMetadata(fid = 12, fullName = "PalmOS", shortLabel = "P", icon = ForumVectorIcons.Pda),
        ForumMetadata(fid = 57, fullName = "疑似机器人", shortLabel = "机", icon = ForumVectorIcons.Robot),
        ForumMetadata(fid = 63, fullName = "已完成交易", shortLabel = "已", icon = Icons.Rounded.CheckCircle),
        ForumMetadata(fid = 62, fullName = "Joggler", shortLabel = "J", icon = ForumVectorIcons.SmartDisplay),
        ForumMetadata(fid = 5, fullName = "站务与公告", shortLabel = "站", icon = Icons.Rounded.Info),
        ForumMetadata(fid = 9, fullName = "Smartphone", shortLabel = "SP", icon = Icons.Rounded.Phone),
        ForumMetadata(fid = 56, fullName = "iPhone, iPod Touch，iPad", shortLabel = "果", icon = ForumVectorIcons.Tablet),
        ForumMetadata(fid = 60, fullName = "Android, Chrome, & Google", shortLabel = "安", icon = ForumVectorIcons.Droid),
        ForumMetadata(fid = 14, fullName = "Windows Mobile，PocketPC，HPC", shortLabel = "WM", icon = ForumVectorIcons.WindowGrid),
        ForumMetadata(fid = 22, fullName = "麦客爱苹果", shortLabel = "麦", icon = ForumVectorIcons.Desktop),
        ForumMetadata(fid = 50, fullName = "DC,NB,MP3,Gadgets", shortLabel = "DC", icon = ForumVectorIcons.Camera),
        ForumMetadata(fid = 24, fullName = "意欲蔓延", shortLabel = "欲", icon = ForumVectorIcons.Coffee),
        ForumMetadata(fid = 23, fullName = "随笔与个人文集", shortLabel = "文", icon = Icons.Rounded.Create),
        ForumMetadata(fid = 25, fullName = "吃喝玩乐", shortLabel = "玩", icon = ForumVectorIcons.Dining),
        ForumMetadata(fid = 51, fullName = "La Femme", shortLabel = "LF", icon = Icons.Rounded.Face),
        ForumMetadata(fid = 65, fullName = "改版建议", shortLabel = "议", icon = Icons.Rounded.ThumbUp),
        ForumMetadata(fid = 64, fullName = "只讨论2.0", shortLabel = "讨", icon = ForumVectorIcons.ChatBubble),
    )

    private val byFid: Map<Int, ForumMetadata> = BuiltInForums.associateBy { it.fid }

    /** 查找内置版块；未命中时返回 `icon = null` 的动态回退元数据（自动使用圆角首字徽标）。 */
    fun resolve(fid: Int, fallbackName: String? = null): ForumMetadata {
        val builtIn = byFid[fid]
        if (builtIn != null) {
            return if (!fallbackName.isNullOrBlank() && fallbackName != builtIn.fullName) {
                builtIn.copy(fullName = fallbackName)
            } else {
                builtIn
            }
        }
        val name = fallbackName?.trim()?.takeIf { it.isNotEmpty() } ?: "版块 $fid"
        return ForumMetadata(
            fid = fid,
            fullName = name,
            shortLabel = normalizeForumMonogram(name),
            icon = null,
        )
    }

    fun resolveAll(
        fids: List<Int>,
        customNames: Map<Int, String> = emptyMap(),
    ): List<ForumMetadata> = fids.map { fid -> resolve(fid, customNames[fid]) }
}

internal object ForumVectorIcons {
    private val tint = SolidColor(Color.Black)

    private fun stroked(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Forum.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = tint,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    val Compass: ImageVector = stroked("Compass") {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        close()
        moveTo(15.5f, 8.5f)
        lineTo(13.5f, 13.5f)
        lineTo(8.5f, 15.5f)
        lineTo(10.5f, 10.5f)
        close()
    }

    val Code: ImageVector = stroked("Code") {
        moveTo(8f, 7f)
        lineTo(3f, 12f)
        lineTo(8f, 17f)
        moveTo(16f, 7f)
        lineTo(21f, 12f)
        lineTo(16f, 17f)
        moveTo(13.5f, 5f)
        lineTo(10.5f, 19f)
    }

    val Book: ImageVector = stroked("Book") {
        moveTo(4f, 5f)
        horizontalLineTo(10f)
        quadTo(12f, 5f, 12f, 7f)
        verticalLineTo(19f)
        quadTo(12f, 17.5f, 10f, 17.5f)
        horizontalLineTo(4f)
        close()
        moveTo(20f, 5f)
        horizontalLineTo(14f)
        quadTo(12f, 5f, 12f, 7f)
        verticalLineTo(19f)
        quadTo(12f, 17.5f, 14f, 17.5f)
        horizontalLineTo(20f)
        close()
    }

    val Pda: ImageVector = stroked("Pda") {
        moveTo(7f, 3f)
        horizontalLineTo(17f)
        verticalLineTo(21f)
        horizontalLineTo(7f)
        close()
        moveTo(7f, 15f)
        horizontalLineTo(17f)
        moveTo(11f, 18f)
        horizontalLineTo(13f)
    }

    val Robot: ImageVector = stroked("Robot") {
        moveTo(12f, 3f)
        verticalLineTo(7f)
        moveTo(5f, 7f)
        horizontalLineTo(19f)
        verticalLineTo(18f)
        horizontalLineTo(5f)
        close()
        moveTo(9f, 12f)
        horizontalLineTo(9.01f)
        moveTo(15f, 12f)
        horizontalLineTo(15.01f)
        moveTo(9f, 15.5f)
        horizontalLineTo(15f)
    }

    val SmartDisplay: ImageVector = stroked("SmartDisplay") {
        moveTo(3f, 6f)
        horizontalLineTo(21f)
        verticalLineTo(17f)
        horizontalLineTo(3f)
        close()
        moveTo(8f, 20f)
        horizontalLineTo(16f)
        moveTo(12f, 9f)
        verticalLineTo(12f)
        lineTo(14.5f, 13.5f)
    }

    val Tablet: ImageVector = stroked("Tablet") {
        moveTo(5f, 3f)
        horizontalLineTo(19f)
        verticalLineTo(21f)
        horizontalLineTo(5f)
        close()
        moveTo(11f, 18f)
        horizontalLineTo(13f)
    }

    val Droid: ImageVector = stroked("Droid") {
        moveTo(6f, 14f)
        arcTo(6f, 6f, 0f, false, true, 18f, 14f)
        verticalLineTo(19f)
        horizontalLineTo(6f)
        close()
        moveTo(8.5f, 8.5f)
        lineTo(6.5f, 5.5f)
        moveTo(15.5f, 8.5f)
        lineTo(17.5f, 5.5f)
        moveTo(9.5f, 13f)
        horizontalLineTo(9.51f)
        moveTo(14.5f, 13f)
        horizontalLineTo(14.51f)
    }

    val WindowGrid: ImageVector = stroked("WindowGrid") {
        moveTo(4f, 4f)
        horizontalLineTo(10.5f)
        verticalLineTo(10.5f)
        horizontalLineTo(4f)
        close()
        moveTo(13.5f, 4f)
        horizontalLineTo(20f)
        verticalLineTo(10.5f)
        horizontalLineTo(13.5f)
        close()
        moveTo(4f, 13.5f)
        horizontalLineTo(10.5f)
        verticalLineTo(20f)
        horizontalLineTo(4f)
        close()
        moveTo(13.5f, 13.5f)
        horizontalLineTo(20f)
        verticalLineTo(20f)
        horizontalLineTo(13.5f)
        close()
    }

    val Desktop: ImageVector = stroked("Desktop") {
        moveTo(3f, 4f)
        horizontalLineTo(21f)
        verticalLineTo(15f)
        horizontalLineTo(3f)
        close()
        moveTo(12f, 15f)
        verticalLineTo(19f)
        moveTo(8f, 19f)
        horizontalLineTo(16f)
    }

    val Camera: ImageVector = stroked("Camera") {
        moveTo(3f, 8f)
        horizontalLineTo(7f)
        lineTo(9f, 5f)
        horizontalLineTo(15f)
        lineTo(17f, 8f)
        horizontalLineTo(21f)
        verticalLineTo(19f)
        horizontalLineTo(3f)
        close()
        moveTo(12f, 10.5f)
        arcTo(3f, 3f, 0f, true, true, 11.99f, 10.5f)
        close()
    }

    val Coffee: ImageVector = stroked("Coffee") {
        moveTo(4f, 8f)
        horizontalLineTo(16f)
        verticalLineTo(15f)
        arcTo(4f, 4f, 0f, false, true, 12f, 19f)
        horizontalLineTo(8f)
        arcTo(4f, 4f, 0f, false, true, 4f, 15f)
        close()
        moveTo(16f, 9f)
        horizontalLineTo(18f)
        arcTo(2.5f, 2.5f, 0f, false, true, 18f, 14f)
        horizontalLineTo(16f)
    }

    val Dining: ImageVector = stroked("Dining") {
        moveTo(7f, 4f)
        verticalLineTo(20f)
        moveTo(5f, 4f)
        verticalLineTo(10f)
        horizontalLineTo(9f)
        verticalLineTo(4f)
        moveTo(16f, 4f)
        verticalLineTo(20f)
        moveTo(16f, 4f)
        quadTo(20f, 6f, 20f, 11f)
        horizontalLineTo(16f)
    }

    val ChatBubble: ImageVector = stroked("ChatBubble") {
        moveTo(4f, 5f)
        horizontalLineTo(20f)
        verticalLineTo(16f)
        horizontalLineTo(9f)
        lineTo(5f, 20f)
        verticalLineTo(16f)
        horizontalLineTo(4f)
        close()
    }
}
