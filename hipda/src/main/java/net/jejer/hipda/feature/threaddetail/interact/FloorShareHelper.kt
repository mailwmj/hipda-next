package net.jejer.hipda.feature.threaddetail.interact

import android.app.Activity
import android.content.Context
import android.content.Intent
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.parser.threaddetail.PostFloor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_FLOOR_SHARE_EXCERPT_LENGTH = 4_000

internal fun isPositiveNumericId(raw: String): Boolean =
    raw.isNotEmpty() && raw.all { it in '0'..'9' } && (raw.toLongOrNull() ?: 0L) > 0L

internal fun buildSafeForumEndpointUrl(
    forumServer: String,
    relativeEndpoint: String,
    preferHttps: Boolean,
): String? {
    val safeServer = SafeLinkPolicy.sanitizeHttpUrl(forumServer.trim()) ?: return null
    val parsed = safeServer.toHttpUrlOrNull() ?: return null
    if (parsed.query != null || parsed.fragment != null) return null
    val isLoopback = parsed.host.equals("localhost", ignoreCase = true) || parsed.host == "127.0.0.1" || parsed.host == "::1"
    val normalizedServer = if (preferHttps && parsed.scheme == "http" && parsed.port == 80 && !isLoopback) {
        parsed.newBuilder().scheme("https").port(443).build().toString()
    } else {
        parsed.toString()
    }.trimEnd('/')
    val forumBase = if (normalizedServer.endsWith("/forum", ignoreCase = true)) "$normalizedServer/" else "$normalizedServer/forum/"
    return SafeLinkPolicy.sanitizeHttpUrl("$forumBase$relativeEndpoint")
}

/** 构造经 [SafeLinkPolicy.sanitizeHttpUrl] 校验的单楼层分享链接（优先 `https`，1 楼 `viewthread`，非 1 楼 `redirect`+`pid`）。 */
fun buildSafeFloorShareUrl(forumServer: String, tid: String, floorNumber: Int, postId: String): String? {
    val cleanTid = tid.trim()
    if (!isPositiveNumericId(cleanTid)) return null
    val endpoint = if (floorNumber == 1) {
        "viewthread.php?tid=$cleanTid"
    } else {
        val cleanPid = postId.trim()
        if (!isPositiveNumericId(cleanPid)) return null
        "redirect.php?goto=findpost&ptid=$cleanTid&pid=$cleanPid"
    }
    return buildSafeForumEndpointUrl(forumServer, endpoint, preferHttps = true)
}

/** 拼装单楼层纯文本分享内容（遵守 `copy.md` 标准术语：`主题`/`回复` 与 `第 {N} 楼 · {作者}`）。 */
fun buildFloorShareText(
    threadTitle: String,
    floor: PostFloor,
    safeUrl: String,
    topicPrefix: String,
    replyPrefix: String,
    floorHeader: String,
): String {
    val prefix = if (floor.floor == 1) topicPrefix else replyPrefix
    val cleanTitle = threadTitle.trim()
    val firstLine = if (cleanTitle.isNotEmpty()) "$prefix：$cleanTitle" else prefix
    val rawPlainText = extractFloorPlainText(floor).trim()
    val excerpt = if (rawPlainText.length > MAX_FLOOR_SHARE_EXCERPT_LENGTH) {
        "${rawPlainText.take(MAX_FLOOR_SHARE_EXCERPT_LENGTH).trimEnd()}…"
    } else {
        rawPlainText
    }
    return buildString {
        append(firstLine).append('\n').append(floorHeader).append('\n').append(safeUrl)
        if (excerpt.isNotEmpty()) append("\n\n").append(excerpt)
    }
}

/** 经 [SafeLinkPolicy.sanitizeHttpUrl] 守卫发起系统 `ACTION_SEND`（`text/plain`）单楼分享；非法 URL 拦截且 0 次 `startActivity`。 */
fun shareFloorPlainText(
    context: Context,
    forumServer: String,
    tid: String,
    threadTitle: String,
    floor: PostFloor,
    topicPrefix: String,
    replyPrefix: String,
    floorHeader: String,
    chooserTitle: String,
    startIntent: (Intent) -> Unit = { context.startActivity(it) },
): Boolean {
    val safeUrl = buildSafeFloorShareUrl(forumServer, tid, floor.floor, floor.postId) ?: return false
    val shareText = buildFloorShareText(threadTitle, floor, safeUrl, topicPrefix, replyPrefix, floorHeader)
    val cleanTitle = threadTitle.trim()
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (cleanTitle.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, cleanTitle)
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { startIntent(chooser) }.isSuccess
}
