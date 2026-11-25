package net.jejer.hipda.feature.threaddetail.media

/**
 * 帖子图片真实宽高比内存 LRU 缓存（按规范化 URL 键控，线程安全）。
 *
 * 用于回收再入视口时首帧直接以真实比例占位，避免回退 `4:3` 触发二次高度跳动。
 */
class ImageAspectRatioCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    private val entries = LinkedHashMap<String, Float>(32, 0.75f, true)

    @Synchronized
    fun get(url: String): Float? = entries[normalizeKey(url)]

    @Synchronized
    fun put(url: String, width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        return put(url, width.toFloat() / height.toFloat())
    }

    @Synchronized
    fun put(url: String, aspectRatio: Float): Float? {
        if (!aspectRatio.isFinite() || aspectRatio <= 0f) return null
        val key = normalizeKey(url)
        if (key.isEmpty()) return null
        val clamped = clampAspectRatio(aspectRatio)
        entries[key] = clamped
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator().next().key
            entries.remove(eldest)
        }
        return clamped
    }

    /** 占位宽高比解析顺序：优先命中缓存（`displayUrl` → `fallbackUrl`）→ 默认 `4:3`。 */
    @Synchronized
    fun resolveInitialAspectRatio(displayUrl: String, fallbackUrl: String? = null): Float =
        entries[normalizeKey(displayUrl)]
            ?: fallbackUrl?.let { entries[normalizeKey(it)] }
            ?: DEFAULT_ASPECT_RATIO

    @Synchronized
    fun clear() {
        entries.clear()
    }

    val size: Int
        @Synchronized get() = entries.size

    private fun normalizeKey(url: String): String = sanitizeImageUrl(url) ?: url.trim()

    companion object {
        const val DEFAULT_ASPECT_RATIO: Float = 4f / 3f
        const val MIN_ASPECT_RATIO: Float = 0.25f
        const val MAX_ASPECT_RATIO: Float = 4f
        const val DEFAULT_MAX_ENTRIES: Int = 512

        val Default: ImageAspectRatioCache = ImageAspectRatioCache()

        fun clampAspectRatio(ratio: Float): Float =
            if (!ratio.isFinite() || ratio <= 0f) DEFAULT_ASPECT_RATIO else ratio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
    }
}
