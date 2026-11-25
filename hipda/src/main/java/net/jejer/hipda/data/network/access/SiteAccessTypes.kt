package net.jejer.hipda.data.network.access

import java.io.IOException

/** Priority governing how a request behaves when a Cloudflare challenge or cooldown is active. */
enum class AccessPriority {
    BackgroundSilent,
    UserBlocking,
    UserExplicit,
}

/** Observable challenge lifecycle state exposed by the site access gateway. */
sealed interface ChallengeState {
    data object Idle : ChallengeState
    data class Solving(val startedAtMs: Long) : ChallengeState
    data class Cooldown(val untilMs: Long, val rejected: Boolean) : ChallengeState
}

/** Thrown when a challenge is cancelled, fails, or is blocked by failure cooldown. */
class ChallengeIncompleteException(
    val cooldownUntilMs: Long? = null,
    cause: Throwable? = null,
) : IOException(
    if (cooldownUntilMs != null) {
        "Challenge incomplete; cooldown until $cooldownUntilMs"
    } else {
        "Challenge incomplete"
    },
    cause,
)

/** Thrown when a post-challenge retry is still challenged or blocked by rejected cooldown. */
class ChallengeRejectedException(
    val cooldownUntilMs: Long,
) : IOException("Challenge rejected after retry; cooldown until $cooldownUntilMs")

/** Thrown when a [AccessPriority.BackgroundSilent] request encounters or meets an active challenge. */
class ChallengeAbandonedException(
    message: String = "Challenge abandoned for background request",
) : IOException(message)
