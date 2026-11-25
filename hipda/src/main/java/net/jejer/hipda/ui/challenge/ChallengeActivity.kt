package net.jejer.hipda.ui.challenge

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jejer.hipda.data.network.webview.applyUnifiedDesktopUserAgent
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * Transparent, `singleTop` host Activity that mounts an invisible real-viewport [WebView] during
 * background Cloudflare auto-solving and exposes [ChallengeActivityBinding] so A6 can reveal the
 * exact same WebView without reloading when user interaction is required.
 */
open class ChallengeActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private var activeSessionId: String? = null
    private var activeHandle: AndroidChallengeWebViewHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        applyWindowInteractiveMode(interactive = false)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(
            rootContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (!attachFromIntent(intent)) {
            finishWithoutAnimation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isFinishing || isDestroyed) {
            // Never attach a fresh session onto a dying Activity; re-launch so AMS creates a new instance.
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            val relaunch = Intent(intent).apply {
                component = ComponentName(applicationContext, ChallengeActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            runCatching { applicationContext.startActivity(relaunch) }
                .onFailure { failure ->
                    if (!sessionId.isNullOrBlank()) {
                        ChallengeSessionRegistry.failPending(sessionId, failure)
                    }
                }
            return
        }
        setIntent(intent)
        detachActiveSession(emitHostDestroyed = true)
        applyWindowInteractiveMode(interactive = false)
        if (!attachFromIntent(intent)) {
            finishWithoutAnimation()
        }
    }

    override fun onDestroy() {
        detachActiveSession(emitHostDestroyed = true)
        super.onDestroy()
    }

    protected open fun createWebView(): WebView = WebView(this)

    private fun attachFromIntent(intent: Intent?): Boolean {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
            ?: return false
        val session = ChallengeSessionRegistry.takePending(sessionId) ?: return false
        if (session.isAborted()) return false

        val webView = try {
            createConfiguredWebView(session.userAgent)
        } catch (t: Throwable) {
            session.failAttach(t)
            return false
        }

        val density = resources.displayMetrics.density
        val widthPx = (SILENT_WEBVIEW_WIDTH_DP * density).roundToInt().coerceAtLeast(SILENT_WEBVIEW_WIDTH_DP)
        val heightPx = (SILENT_WEBVIEW_HEIGHT_DP * density).roundToInt().coerceAtLeast(SILENT_WEBVIEW_HEIGHT_DP)
        rootContainer.removeAllViews()
        rootContainer.addView(webView, FrameLayout.LayoutParams(widthPx, heightPx))

        val handle = AndroidChallengeWebViewHandle(
            activity = this,
            sessionId = sessionId,
            triggerUrl = session.triggerUrl,
            rootContainer = rootContainer,
            initialWebView = webView,
            onRequestClose = { targetSessionId, handleRef, completed ->
                val closeAction = Runnable {
                    try {
                        if (activeSessionId == targetSessionId) {
                            detachActiveSession(emitHostDestroyed = false, completeCloseSignal = false)
                            finishWithoutAnimation()
                        } else {
                            handleRef.tearDownFromActivity(
                                emitHostDestroyed = false,
                                completeCloseSignal = false,
                            )
                        }
                    } finally {
                        completed.complete(Unit)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    closeAction.run()
                } else {
                    runOnUiThread(closeAction)
                }
            },
        )

        activeSessionId = sessionId
        activeHandle = handle

        if (!session.completeAttach(handle)) {
            detachActiveSession(emitHostDestroyed = false)
            return false
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(userAgent: String): WebView =
        createWebView().apply {
            // Keep View.VISIBLE with alpha=0f and real viewport dimensions so Chromium executes
            // Cloudflare Turnstile JS/timers without triggering 0x0 or GONE throttling/risk checks.
            visibility = View.VISIBLE
            alpha = 0f
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            settings.apply {
                // Cloudflare Managed Challenge / Turnstile requires JavaScript and DOM storage.
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                applyUnifiedDesktopUserAgent(this, userAgent)
            }

            runCatching {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
            }
        }

    internal fun applyWindowInteractiveMode(interactive: Boolean) {
        val nonInteractiveFlags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (interactive) {
            window.clearFlags(nonInteractiveFlags)
        } else {
            window.addFlags(nonInteractiveFlags)
        }
    }

    private fun detachActiveSession(
        emitHostDestroyed: Boolean,
        completeCloseSignal: Boolean = true,
    ) {
        val handle = activeHandle
        activeHandle = null
        activeSessionId = null
        handle?.tearDownFromActivity(
            emitHostDestroyed = emitHostDestroyed,
            completeCloseSignal = completeCloseSignal,
        )
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishWithoutAnimation() {
        if (!isFinishing && !isDestroyed) {
            finish()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID: String = "net.jejer.hipda.ui.challenge.EXTRA_SESSION_ID"
        const val SILENT_WEBVIEW_WIDTH_DP: Int = 360
        const val SILENT_WEBVIEW_HEIGHT_DP: Int = 480

        internal const val WEB_MESSAGE_BRIDGE_NAME: String = "__hipdaChallengeBridge"
        internal const val FALLBACK_JS_BRIDGE_NAME: String = "__hipdaChallengeFallback"

        fun createLaunchIntent(context: Context, sessionId: String): Intent =
            Intent(context, ChallengeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }
}

/**
 * Production [ChallengeWebViewHost] that registers a session handshake, launches [ChallengeActivity]
 * from `@ApplicationContext` with [Intent.FLAG_ACTIVITY_NEW_TASK], and awaits handle attachment.
 */
@Singleton
class AndroidChallengeWebViewHost @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : ChallengeWebViewHost {

    override suspend fun acquire(
        triggerUrl: HttpUrl,
        userAgent: String,
    ): ChallengeWebViewHandle {
        currentCoroutineContext().ensureActive()
        val session = ChallengeSessionRegistry.register(triggerUrl, userAgent)
        try {
            currentCoroutineContext().ensureActive()
            val intent = ChallengeActivity.createLaunchIntent(appContext, session.sessionId)
            appContext.startActivity(intent)
            return session.handleDeferred.await()
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                ChallengeSessionRegistry.abortAndAwaitClose(session)
            }
            throw t
        }
    }
}

internal class PendingChallengeSession(
    val sessionId: String,
    val triggerUrl: HttpUrl,
    val userAgent: String,
) {
    private val lock = Any()
    private var aborted = false
    private var attachedHandle: ChallengeWebViewHandle? = null
    val handleDeferred: CompletableDeferred<ChallengeWebViewHandle> = CompletableDeferred()

    fun isAborted(): Boolean = synchronized(lock) { aborted }

    fun completeAttach(handle: ChallengeWebViewHandle): Boolean {
        synchronized(lock) {
            if (aborted) return false
            attachedHandle = handle
        }
        handleDeferred.complete(handle)
        return true
    }

    fun failAttach(cause: Throwable) {
        synchronized(lock) {
            if (aborted || attachedHandle != null) return
            aborted = true
        }
        handleDeferred.completeExceptionally(cause)
    }

    suspend fun abortAndAwaitClose() {
        val handleToClose = synchronized(lock) {
            aborted = true
            val existing = attachedHandle
            attachedHandle = null
            existing
        }
        handleDeferred.cancel()
        handleToClose?.let { handle -> runCatching { handle.closeAndAwait() } }
    }
}

internal object ChallengeSessionRegistry {
    private val pendingSessions = ConcurrentHashMap<String, PendingChallengeSession>()

    fun register(triggerUrl: HttpUrl, userAgent: String): PendingChallengeSession {
        val session = PendingChallengeSession(
            sessionId = UUID.randomUUID().toString(),
            triggerUrl = triggerUrl,
            userAgent = userAgent,
        )
        pendingSessions[session.sessionId] = session
        return session
    }

    fun takePending(sessionId: String): PendingChallengeSession? =
        pendingSessions.remove(sessionId)

    fun failPending(sessionId: String, cause: Throwable) {
        pendingSessions.remove(sessionId)?.failAttach(cause)
    }

    suspend fun abortAndAwaitClose(session: PendingChallengeSession) {
        pendingSessions.remove(session.sessionId, session)
        session.abortAndAwaitClose()
    }
}

private class AndroidChallengeWebViewHandle(
    private val activity: ChallengeActivity,
    private val sessionId: String,
    private val triggerUrl: HttpUrl,
    private val rootContainer: FrameLayout,
    initialWebView: WebView,
    private val onRequestClose: (String, AndroidChallengeWebViewHandle, CompletableDeferred<Unit>) -> Unit,
) : ChallengeWebViewHandle {

    private val eventChannel = Channel<ChallengeWebViewEvent>(Channel.UNLIMITED)
    override val events: Flow<ChallengeWebViewEvent> = eventChannel.receiveAsFlow()

    private val allowedOriginRules: Set<String> = buildAllowedChallengeOriginRules(triggerUrl)
    private val triggerOrigin: String = formatOrigin(triggerUrl)
    private val documentStartScript: String = turnstileMessageScript(
        guardFlag = "__hipdaCfMsgBridgeInstalled",
        bridgeName = ChallengeActivity.WEB_MESSAGE_BRIDGE_NAME,
        methodName = "postMessage",
        triggerOrigin = triggerOrigin,
    )
    private val fallbackListenerScript: String = turnstileMessageScript(
        guardFlag = "__hipdaCfFallbackInstalled",
        bridgeName = ChallengeActivity.FALLBACK_JS_BRIDGE_NAME,
        methodName = "onChallengeEvent",
        triggerOrigin = triggerOrigin,
    )

    private val tracker = MainFrameStateTracker()
    private val closed = AtomicBoolean(false)
    private val closeCompleted = CompletableDeferred<Unit>()
    private val pendingDomChecks = CopyOnWriteArrayList<CancellableContinuation<Boolean>>()

    @Volatile
    private var webView: WebView? = initialWebView

    @Volatile
    private var useFallbackJsInjection: Boolean = false

    private var scriptHandler: ScriptHandler? = null
    private var webMessageListenerInstalled = false
    private var fallbackJsInterfaceInstalled = false

    override val activityBinding: ChallengeActivityBinding = object : ChallengeActivityBinding {
        override val activity: ComponentActivity
            get() = this@AndroidChallengeWebViewHandle.activity
        override val webView: WebView
            get() = initialWebView
        override val rootContainer: FrameLayout
            get() = this@AndroidChallengeWebViewHandle.rootContainer

        override fun setInteractiveMode(interactive: Boolean) {
            this@AndroidChallengeWebViewHandle.activity.runOnUiThread {
                if (closed.get()) return@runOnUiThread
                this@AndroidChallengeWebViewHandle.activity.applyWindowInteractiveMode(interactive)
                val wv = this@AndroidChallengeWebViewHandle.webView ?: return@runOnUiThread
                wv.visibility = View.VISIBLE
                wv.alpha = if (interactive) 1f else 0f
                wv.isFocusable = interactive
                wv.isFocusableInTouchMode = interactive
                wv.isClickable = interactive
                wv.isLongClickable = interactive
                wv.importantForAccessibility = if (interactive) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
        }
    }

    init {
        initialWebView.webViewClient = ChallengeWebViewClient()
    }

    override fun installChallengeMessageListener() {
        activity.runOnUiThread {
            val wv = webView ?: return@runOnUiThread
            if (closed.get()) return@runOnUiThread
            if (webMessageListenerInstalled || fallbackJsInterfaceInstalled) return@runOnUiThread

            val installed = runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addWebMessageListener(
                            wv,
                            ChallengeActivity.WEB_MESSAGE_BRIDGE_NAME,
                            allowedOriginRules,
                        ) { _, message, sourceOrigin, _, _ ->
                            if (message.type == WebMessageCompat.TYPE_STRING &&
                                isAllowedChallengeOrigin(sourceOrigin.toString(), triggerUrl)
                            ) {
                                parseTurnstileChallengeEvent(message.data)?.let { eventChannel.trySend(it) }
                            }
                        }
                        webMessageListenerInstalled = true
                        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                            wv,
                            documentStartScript,
                            allowedOriginRules,
                        )
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }.onFailure {
                if (webMessageListenerInstalled) {
                    webMessageListenerInstalled = false
                    runCatching {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            WebViewCompat.removeWebMessageListener(wv, ChallengeActivity.WEB_MESSAGE_BRIDGE_NAME)
                        }
                    }
                }
            }.getOrDefault(false)
            if (installed) return@runOnUiThread

            // Safe fallback when WebView lacks DOCUMENT_START_SCRIPT or WEB_MESSAGE_LISTENER.
            useFallbackJsInjection = true
            fallbackJsInterfaceInstalled = true
            wv.addJavascriptInterface(
                FallbackChallengeJsBridge { rawEvent ->
                    if (isAllowedChallengeOrigin(tracker.snapshot().url, triggerUrl)) {
                        parseTurnstileChallengeEvent(rawEvent)?.let { eventChannel.trySend(it) }
                    }
                },
                ChallengeActivity.FALLBACK_JS_BRIDGE_NAME,
            )
        }
    }

    override fun loadUrl(url: String) {
        tracker.onLoadRequested(url)
        activity.runOnUiThread {
            if (closed.get()) return@runOnUiThread
            webView?.loadUrl(url)
        }
    }

    override fun reload() {
        tracker.onLoadRequested(tracker.snapshot().url)
        activity.runOnUiThread {
            if (closed.get()) return@runOnUiThread
            webView?.reload()
        }
    }

    override fun mainFrameState(): MainFrameState = tracker.snapshot()

    override suspend fun isDomInChallengeState(): Boolean {
        if (closed.get() || webView == null) return true
        return suspendCancellableCoroutine { cont ->
            pendingDomChecks.add(cont)
            cont.invokeOnCancellation { pendingDomChecks.remove(cont) }
            activity.runOnUiThread {
                val wv = webView
                if (closed.get() || wv == null) {
                    if (pendingDomChecks.remove(cont) && cont.isActive) {
                        cont.resume(true)
                    }
                    return@runOnUiThread
                }
                try {
                    wv.evaluateJavascript(CHALLENGE_DOM_CHECK_SCRIPT) { raw ->
                        if (pendingDomChecks.remove(cont) && cont.isActive) {
                            // Fail closed: only an explicit JS `false` confirms no active challenge DOM.
                            cont.resume(raw?.trim() != "false")
                        }
                    }
                } catch (_: Throwable) {
                    if (pendingDomChecks.remove(cont) && cont.isActive) {
                        cont.resume(true)
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            drainPendingDomChecks()
            eventChannel.close()
            onRequestClose(sessionId, this, closeCompleted)
        }
    }

    override suspend fun closeAndAwait() {
        close()
        withTimeoutOrNull(CLOSE_AWAIT_TIMEOUT_MS) {
            closeCompleted.await()
        }
    }

    fun tearDownFromActivity(
        emitHostDestroyed: Boolean,
        completeCloseSignal: Boolean = true,
    ) {
        val wasOpen = closed.compareAndSet(false, true)
        if (wasOpen && emitHostDestroyed) {
            eventChannel.trySend(ChallengeWebViewEvent.HostDestroyed)
        }
        drainPendingDomChecks()
        destroyWebViewSafely()
        eventChannel.close()
        if (completeCloseSignal) {
            closeCompleted.complete(Unit)
        }
    }

    private fun destroyWebViewSafely() {
        val wv = webView ?: return
        webView = null
        scriptHandler?.let { handler -> runCatching { handler.remove() } }
        scriptHandler = null
        if (webMessageListenerInstalled) {
            webMessageListenerInstalled = false
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(wv, ChallengeActivity.WEB_MESSAGE_BRIDGE_NAME)
                }
            }
        }
        if (fallbackJsInterfaceInstalled) {
            fallbackJsInterfaceInstalled = false
            runCatching { wv.removeJavascriptInterface(ChallengeActivity.FALLBACK_JS_BRIDGE_NAME) }
        }
        runCatching {
            wv.stopLoading()
            wv.webViewClient = DetachedWebViewClient
            wv.webChromeClient = null
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.onPause()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    private fun drainPendingDomChecks() {
        for (cont in pendingDomChecks) {
            if (pendingDomChecks.remove(cont) && cont.isActive) {
                cont.resume(true)
            }
        }
    }

    private object DetachedWebViewClient : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean = true
    }

    private inner class ChallengeWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase(Locale.ROOT) ?: return false
            return scheme != "http" && scheme != "https" && scheme != "about"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            tracker.onPageStarted(url)
            if (useFallbackJsInjection && !closed.get() && isAllowedChallengeOrigin(url, triggerUrl)) {
                runCatching { view?.evaluateJavascript(fallbackListenerScript, null) }
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request == null || errorResponse == null) return
            if (isIgnoredChallengeProbeHost(request.url?.host)) return
            if (!request.isForMainFrame) return
            tracker.onMainFrameHttpError(
                requestUrl = request.url?.toString(),
                statusCode = errorResponse.statusCode,
                responseHeaders = errorResponse.responseHeaders,
            )
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request == null) return
            if (isIgnoredChallengeProbeHost(request.url?.host)) return
            if (!request.isForMainFrame) return
            tracker.onMainFrameLoadError(request.url?.toString())
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (useFallbackJsInjection && !closed.get() && isAllowedChallengeOrigin(url, triggerUrl)) {
                runCatching { view?.evaluateJavascript(fallbackListenerScript, null) }
            }
            tracker.onPageFinished(url)
            eventChannel.trySend(ChallengeWebViewEvent.PageFinished(url))
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            val didCrash = detail?.didCrash() ?: true
            val priority = detail?.rendererPriorityAtExit() ?: -1
            drainPendingDomChecks()
            destroyWebViewSafely()
            eventChannel.trySend(
                ChallengeWebViewEvent.RenderProcessGone(
                    didCrash = didCrash,
                    rendererPriorityAtExit = priority,
                ),
            )
            // Always return true so renderer termination never takes down the host app process.
            return true
        }
    }

    @Keep
    private class FallbackChallengeJsBridge(
        private val onEvent: (String?) -> Unit,
    ) {
        @Keep
        @JavascriptInterface
        fun onChallengeEvent(eventName: String?) {
            onEvent(eventName)
        }
    }

    private companion object {
        const val CLOSE_AWAIT_TIMEOUT_MS: Long = 500L

        fun turnstileMessageScript(
            guardFlag: String,
            bridgeName: String,
            methodName: String,
            triggerOrigin: String,
        ): String {
            val quotedTriggerOrigin = JSONObject.quote(triggerOrigin.lowercase(Locale.ROOT))
            return """
                (function() {
                  if (window.$guardFlag) return;
                  window.$guardFlag = true;
                  var triggerOrigin = $quotedTriggerOrigin;
                  function isAllowedOrigin(o) {
                    if (!o || typeof o !== 'string') return false;
                    var lo = o.toLowerCase();
                    if (lo === triggerOrigin) return true;
                    if (lo === 'https://4d4y.com' || lo === 'https://challenges.cloudflare.com') return true;
                    if (lo.indexOf('https://') === 0) {
                      var host = lo.slice(8);
                      var colon = host.indexOf(':');
                      if (colon !== -1) {
                        if (host.slice(colon + 1) !== '443') return false;
                        host = host.slice(0, colon);
                      }
                      if (host.endsWith('.4d4y.com') || host.endsWith('.challenges.cloudflare.com')) return true;
                    }
                    return false;
                  }
                  window.addEventListener('message', function(e) {
                    try {
                      if (!isAllowedOrigin(e && e.origin)) return;
                      var d = e && e.data;
                      if (typeof d === 'string') {
                        if (d.indexOf('cloudflare-challenge') === -1) return;
                        d = JSON.parse(d);
                      }
                      if (d && d.source === 'cloudflare-challenge') {
                        var ev = d.event;
                        if (ev === 'interactiveBegin' || ev === 'interactiveTimeout') {
                          var b = window.$bridgeName;
                          if (b && typeof b.$methodName === 'function') {
                            b.$methodName(ev);
                          }
                        }
                      }
                    } catch (_) {}
                  }, false);
                })();
            """.trimIndent()
        }

        val CHALLENGE_DOM_CHECK_SCRIPT: String =
            """
            (function() {
              try {
                if (typeof window._cf_chl_opt !== 'undefined' || typeof window.cf_chl_opt !== 'undefined') {
                  return true;
                }
                if (document.querySelector('#challenge-running, #challenge-stage, .cf-turnstile, [name="cf-turnstile-response"]')) {
                  return true;
                }
                var html = document.documentElement ? document.documentElement.innerHTML : '';
                if (!html) return true;
                return html.indexOf('cf-turnstile') !== -1 ||
                  html.indexOf('challenge-running') !== -1 ||
                  html.indexOf('challenge-stage') !== -1 ||
                  html.indexOf('_cf_chl_opt') !== -1 ||
                  html.indexOf('cf_chl_opt') !== -1;
              } catch (_) {
                return true;
              }
            })()
            """.trimIndent()
    }
}
