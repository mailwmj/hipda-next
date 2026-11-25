package net.jejer.hipda.data.auth

import kotlinx.coroutines.flow.first
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.decodeBody
import net.jejer.hipda.data.network.gbkFormBody
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.common.DiscuzCommonParser
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Unified forum HTTP executor shared across D-line repositories (`D4`–`D9`).
 *
 * Dynamically resolves `{forumServer}/forum/{relativePath}` from [SettingsRepository], executes
 * requests via [SiteAccessGateway], decodes bodies with [decodeBody], parses [DiscuzPageMeta] via
 * [DiscuzCommonParser], and when `requireAuth == true` and the page indicates an unauthenticated
 * session, triggers at most one single-flight silent re-login and retries the request once.
 */
class ForumHttpClient(
    private val siteAccessGateway: SiteAccessGateway,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
) {

    /** Performs a GET request to `{forumServer}/forum/{relativePath}`. */
    suspend fun getHtml(
        relativePath: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
        requireAuth: Boolean = false,
    ): ForumHtmlResponse = executeWithAuthRetry(
        relativePath = relativePath,
        priority = priority,
        requireAuth = requireAuth,
        buildRequest = { url -> Request.Builder().url(url).get().build() },
    )

    /** Performs a GBK-encoded form POST request to `{forumServer}/forum/{relativePath}`. */
    suspend fun postGbkForm(
        relativePath: String,
        fields: List<Pair<String, String>>,
        priority: AccessPriority = AccessPriority.UserBlocking,
        requireAuth: Boolean = true,
    ): ForumHtmlResponse = executeWithAuthRetry(
        relativePath = relativePath,
        priority = priority,
        requireAuth = requireAuth,
        buildRequest = { url ->
            Request.Builder()
                .url(url)
                .post(gbkFormBody(fields))
                .build()
        },
    )

    private suspend fun executeWithAuthRetry(
        relativePath: String,
        priority: AccessPriority,
        requireAuth: Boolean,
        buildRequest: (HttpUrl) -> Request,
    ): ForumHtmlResponse {
        val epochBeforeFirstAttempt = authRepository.sessionEpoch
        val firstResponse = executeSingleAttempt(relativePath, priority, buildRequest)
        if (!requireAuth || firstResponse.meta.session.isLoggedIn) {
            return firstResponse
        }

        return when (
            val relogin = authRepository.trySilentRelogin(
                failedEpoch = epochBeforeFirstAttempt,
                priority = priority,
            )
        ) {
            is SilentReloginResult.Recovered -> {
                val retriedResponse = executeSingleAttempt(relativePath, priority, buildRequest)
                if (retriedResponse.meta.session.isLoggedIn) {
                    retriedResponse
                } else {
                    throw AuthRequiredException(
                        reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                        reloginResult = relogin,
                    )
                }
            }

            SilentReloginResult.Suppressed -> throw AuthRequiredException(
                reason = AuthRequiredReason.MANUALLY_LOGGED_OUT,
                reloginResult = relogin,
            )

            SilentReloginResult.NotLoggedIn -> throw AuthRequiredException(
                reason = AuthRequiredReason.NOT_LOGGED_IN,
                reloginResult = relogin,
            )

            is SilentReloginResult.Failed -> throw AuthRequiredException(
                reason = AuthRequiredReason.RELOGIN_FAILED,
                reloginResult = relogin,
                cause = relogin.cause,
            )
        }
    }

    private suspend fun executeSingleAttempt(
        relativePath: String,
        priority: AccessPriority,
        buildRequest: (HttpUrl) -> Request,
    ): ForumHtmlResponse {
        val forumServer = settingsRepository.settings.first().forumServer
        val url = buildForumUrl(forumServer, relativePath)
        val request = buildRequest(url)
        siteAccessGateway.execute(request = request, priority = priority).use { response ->
            if (!response.isSuccessful) {
                throw ForumHttpException(response.code, response.request.url.toString())
            }
            val bytes = response.body.bytes()
            val contentType = response.header("Content-Type") ?: response.body.contentType()?.toString()
            val html = decodeBody(bytes, contentType)
            val meta = DiscuzCommonParser.parsePageMeta(html)
            return ForumHtmlResponse(
                url = response.request.url.toString(),
                statusCode = response.code,
                html = html,
                meta = meta,
            )
        }
    }
}
