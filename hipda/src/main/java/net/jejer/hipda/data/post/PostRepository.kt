package net.jejer.hipda.data.post

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Repository for forum write operations (`PrePost` prefetch, new thread, reply, quote, edit,
 * delete), 30-second cooldown tracking, and per-business-key Room draft persistence.
 */
interface PostRepository {
    /**
     * Epoch timestamp in milliseconds until which non-edit submissions (including delete) are
     * blocked by the 30-second post cooldown. `0L` when inactive.
     */
    val cooldownUntilMs: StateFlow<Long>

    /**
     * Remaining cooldown seconds rounded UP (`1ms..1000ms -> 1`, `<= 0ms -> 0`).
     */
    fun getRemainingCooldownSeconds(): Int

    /**
     * Prefetches the Discuz! `post.php` form for [request], intercepts real-name bind redirects,
     * and decodes numeric HTML entities (R-B9b S3) in `subject`, `text`, and `quoteText` so the
     * editor displays real Unicode Emoji.
     */
    suspend fun fetchPrePost(
        request: PrePostRequest,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): PrePostResult

    /**
     * Executes one of the 6 write modes in [request] serialized by an internal lock to prevent
     * duplicate submissions. Deletes the associated [PostSubmitRequest.draftKey] only on confirmed
     * server success and preserves it on any failure.
     */
    suspend fun submitPost(
        request: PostSubmitRequest,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): PostSubmitResult

    fun observeDraft(key: DraftKey): Flow<DraftEntity?>

    /**
     * Persists a draft for [key]. Blank-only content (blank [subject], blank [content], and no
     * non-blank [attachments]) is ignored without writing per `product-design.md` §7.2; call
     * [deleteDraft] to explicitly remove an existing draft.
     */
    suspend fun saveDraft(
        key: DraftKey,
        subject: String = "",
        typeId: String = "",
        content: String = "",
        quotePid: String? = null,
        attachments: List<String> = emptyList(),
    )

    suspend fun deleteDraft(key: DraftKey)
}
