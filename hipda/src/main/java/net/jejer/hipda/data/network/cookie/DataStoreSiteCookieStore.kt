package net.jejer.hipda.data.network.cookie

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * [SiteCookieStore] backed by an in-memory cache and a serialized async encrypted [DataStore].
 */
class DataStoreSiteCookieStore internal constructor(
    private val dataStore: DataStore<StoredCookieStore>,
    private val persistScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SiteCookieStore {

    private data class CookieKey(val name: String, val domain: String, val path: String)

    private val stateLock = Any()
    private val initMutex = Mutex()
    private val persistMutex = Mutex()
    private val cookies = LinkedHashMap<CookieKey, Cookie>()

    @Volatile private var initialized: Boolean = false
    private var mutationVersion: Long = 0L
    private var completedVersion: Long = 0L
    private var drainScheduled: Boolean = false

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        readActive { active -> active.filter { it.matches(url) }.sortedByDescending { it.path.length } }

    override fun snapshot(): List<Cookie> = readActive { it.toList() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        ensureInitializedBlocking()
        val now = clock()
        val dirty = synchronized(stateLock) {
            var changed = this.cookies.entries.removeAll { it.value.isExpiredAt(now) }
            for (cookie in cookies) {
                val key = cookie.toKey()
                if (cookie.isExpiredAt(now)) {
                    if (this.cookies.remove(key) != null) changed = true
                } else if (this.cookies.put(key, cookie) != cookie) {
                    changed = true
                }
            }
            if (changed) mutationVersion++
            changed
        }
        if (dirty) scheduleDrainIfNeeded()
    }

    override suspend fun clear() {
        ensureInitialized()
        synchronized(stateLock) {
            cookies.clear()
            mutationVersion++
        }
        drainPersistQueue(isScheduledWorker = false)
    }

    override suspend fun clearForHost(host: String) {
        val canonicalHost = canonicalizeHost(host) ?: return
        ensureInitialized()
        val now = clock()
        val dirty = synchronized(stateLock) {
            val pruned = cookies.entries.removeAll { it.value.isExpiredAt(now) }
            val removed = cookies.entries.removeAll { it.value.matchesHost(canonicalHost) }
            if (pruned || removed) mutationVersion++
            pruned || removed
        }
        if (dirty) drainPersistQueue(isScheduledWorker = false)
    }

    @VisibleForTesting
    internal suspend fun flush() {
        ensureInitialized()
        drainPersistQueue(isScheduledWorker = false)
    }

    private fun readActive(select: (Collection<Cookie>) -> List<Cookie>): List<Cookie> {
        ensureInitializedBlocking()
        val now = clock()
        var pruned = false
        val result = synchronized(stateLock) {
            pruned = cookies.entries.removeAll { it.value.isExpiredAt(now) }
            if (pruned) mutationVersion++
            select(cookies.values)
        }
        if (pruned) scheduleDrainIfNeeded()
        return result
    }

    private fun ensureInitializedBlocking() {
        if (!initialized) runBlocking { ensureInitialized() }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val stored = runCatching { dataStore.data.first() }.getOrElse { error ->
                if (error is IOException || error is GeneralSecurityException) StoredCookieStore() else throw error
            }
            val now = clock()
            synchronized(stateLock) {
                cookies.clear()
                for (entry in stored.cookies) {
                    val cookie = entry.toOkHttpCookieOrNull() ?: continue
                    if (!cookie.isExpiredAt(now)) cookies[cookie.toKey()] = cookie
                }
                initialized = true
            }
        }
    }

    private fun scheduleDrainIfNeeded() {
        val shouldLaunch = synchronized(stateLock) {
            if (drainScheduled || completedVersion >= mutationVersion) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (!shouldLaunch) return
        val job = persistScope.launch { drainPersistQueue(isScheduledWorker = true) }
        if (job.isCancelled) synchronized(stateLock) { drainScheduled = false }
    }

    private suspend fun drainPersistQueue(isScheduledWorker: Boolean) {
        try {
            persistMutex.withLock {
                while (true) {
                    val (targetVersion, payload) = synchronized(stateLock) {
                        if (completedVersion >= mutationVersion) {
                            if (isScheduledWorker) drainScheduled = false
                            return@withLock
                        }
                        mutationVersion to StoredCookieStore(cookies = cookies.values.map(Cookie::toStoredCookie))
                    }
                    try {
                        dataStore.updateData { payload }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is IOException && e !is GeneralSecurityException) throw e
                    }
                    synchronized(stateLock) { completedVersion = maxOf(completedVersion, targetVersion) }
                }
            }
        } catch (e: CancellationException) {
            if (isScheduledWorker) synchronized(stateLock) { drainScheduled = false } else scheduleDrainIfNeeded()
            throw e
        }
    }

    private fun Cookie.toKey(): CookieKey = CookieKey(name, domain, path)
    private fun Cookie.isExpiredAt(nowMillis: Long): Boolean = expiresAt <= nowMillis

    private fun Cookie.matchesHost(canonicalHost: String): Boolean {
        if (domain == canonicalHost) return true
        if (hostOnly) return false
        return canonicalHost.endsWith(".$domain") && !isIpAddress(canonicalHost)
    }

    private fun canonicalizeHost(rawHost: String): String? {
        val cleaned = rawHost.trim().trim('.').lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null
        return runCatching { HttpUrl.Builder().scheme("https").host(cleaned).build().host }.getOrNull()
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.contains(':')) return true
        val lastLabel = host.substringAfterLast('.')
        return lastLabel.isNotEmpty() && lastLabel.all(Char::isDigit)
    }
}
