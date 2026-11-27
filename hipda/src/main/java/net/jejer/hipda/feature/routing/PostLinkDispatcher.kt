package net.jejer.hipda.feature.routing

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import net.jejer.hipda.data.search.ForumLinkResolver
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.feature.common.PostLinkDispatcher
import net.jejer.hipda.feature.common.SafeLinkPolicy

/** 外部系统链接打开器抽象（支持严格白名单校验、排除本应用防 Intent 循环与无浏览器安全兜底）。 */
fun interface ExternalLinkOpener {
    fun open(rawUrl: String): Boolean
}

/**
 * Android 平台外部链接打开器：
 * - 入口强制经 [SafeLinkPolicy.sanitizeLinkUrl] 校验（仅 `http`/`https`/纯邮箱 `mailto:`）；
 * - 统一附加 `ACTION_VIEW` + `CATEGORY_BROWSABLE`；
 * - 解析目标 Activity 时显式排除本应用包名，防止未识别站内链接回环拉起本应用造成 Intent 循环；
 * - 全程 `runCatching` 兜底 `ActivityNotFoundException` / `SecurityException`，无浏览器时安全返回 `false`。
 */
class AndroidExternalLinkOpener(
    private val context: Context,
    private val launchIntent: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) : ExternalLinkOpener {

    override fun open(rawUrl: String): Boolean {
        val safeUrl = SafeLinkPolicy.sanitizeLinkUrl(rawUrl) ?: return false
        val uri = Uri.parse(safeUrl)
        val baseIntent = buildBrowsableViewIntent(uri)
        val pm = context.packageManager
        val selfPackage = context.packageName

        val resolved = queryHandlers(pm, baseIntent)
        val selfMatches = resolved.any { it.packageName == selfPackage }
        val externalTargets = resolved
            .filter { it.packageName != selfPackage && it.exported }
            .distinctBy { "${it.packageName}/${it.name}" }

        if (externalTargets.isNotEmpty()) {
            return launchExternalTargets(uri, externalTargets)
        }

        if (selfMatches) {
            // 本应用自身匹配此 URL（例如未知路径的 4d4y.com 链接）：仅尝试显式浏览器组件，绝不回落未过滤隐式 Intent 以防死循环
            if (safeUrl.startsWith("http://") || safeUrl.startsWith("https://")) {
                val browserSelector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                val httpsBrowsableProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val browsers = (queryHandlers(pm, browserSelector) + queryHandlers(pm, httpsBrowsableProbe))
                    .filter { it.packageName != selfPackage && it.exported }
                    .distinctBy { "${it.packageName}/${it.name}" }
                if (browsers.isNotEmpty()) {
                    return launchExternalTargets(uri, browsers)
                }
            }
            return false
        }

        // Android 11+ 无 <queries> 时外部域名 queryIntentActivities 可能返回空，但本应用不在匹配集内可安全隐式拉起
        return runCatching {
            launchIntent(baseIntent)
            true
        }.getOrDefault(false)
    }

    private fun launchExternalTargets(uri: Uri, targets: List<ActivityInfo>): Boolean {
        val explicitIntents = targets.map { info ->
            buildBrowsableViewIntent(uri).apply {
                component = ComponentName(info.packageName, info.name)
            }
        }
        val intentToStart = if (explicitIntents.size == 1) {
            explicitIntents[0]
        } else {
            Intent.createChooser(explicitIntents[0], null).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, explicitIntents.drop(1).toTypedArray())
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            launchIntent(intentToStart)
            true
        }.getOrDefault(false)
    }

    private fun buildBrowsableViewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Suppress("DEPRECATION")
    private fun queryHandlers(pm: PackageManager, intent: Intent): List<ActivityInfo> {
        val primary = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        val all = primary.ifEmpty { runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()) }
        return all.mapNotNull { it.activityInfo }
    }
}

/**
 * 帖子正文链接统一分发实现：
 * 1. 先经 [SafeLinkPolicy.sanitizeLinkUrl] 硬白名单守卫（拒绝一切危险 scheme、控制字符、空 host、畸形 `mailto:`）；
 * 2. 合法 `http`/`https` 优先经 [ForumLinkResolver.resolve] 命中站内目标直接应用内导航；
 * 3. 非站内合法 `http`/`https` 或纯邮箱 `mailto:` 交由 [externalLinkOpener] 安全打开。
 */
class DefaultPostLinkDispatcher(
    private val forumServerProvider: () -> String,
    private val onOpenForum: (fid: Int) -> Unit,
    private val onOpenThread: (tid: String, page: Int) -> Unit,
    private val onOpenThreadLastPage: (tid: String) -> Unit,
    private val onOpenPost: (tid: String?, pid: String) -> Unit,
    private val onOpenUserProfile: (uid: String) -> Unit,
    internal val externalLinkOpener: ExternalLinkOpener,
    private val onExternalOpenFailed: (() -> Unit)? = null,
) : PostLinkDispatcher {

    override fun dispatch(rawUrl: String): Boolean {
        val safeUrl = SafeLinkPolicy.sanitizeLinkUrl(rawUrl) ?: return false
        if (safeUrl.startsWith("mailto:")) {
            return openExternal(safeUrl)
        }
        return when (val resolved = ForumLinkResolver.resolve(safeUrl, forumServerProvider())) {
            is ResolvedForumLink.Forum -> {
                onOpenForum(resolved.fid)
                true
            }
            is ResolvedForumLink.Thread -> {
                onOpenThread(resolved.tid, resolved.page)
                true
            }
            is ResolvedForumLink.ThreadLastPage -> {
                onOpenThreadLastPage(resolved.tid)
                true
            }
            is ResolvedForumLink.Post -> {
                onOpenPost(resolved.tid, resolved.pid)
                true
            }
            is ResolvedForumLink.User -> {
                onOpenUserProfile(resolved.uid)
                true
            }
            null -> openExternal(safeUrl)
        }
    }

    private fun openExternal(safeUrl: String): Boolean {
        val opened = externalLinkOpener.open(safeUrl)
        if (!opened) onExternalOpenFailed?.invoke()
        return opened
    }
}
