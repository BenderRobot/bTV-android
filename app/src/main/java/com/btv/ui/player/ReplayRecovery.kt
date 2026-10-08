package com.btv.ui.player

/**
 * What to do when a catch-up stream fails. Panels limiting connections
 * keep counting the previous socket for a few seconds (403, empty reply),
 * and an old archive can take long to seek: those are waited out with a
 * growing delay. A 404 first questions the URL format, then the quality
 * (another variant of the channel may hold the archive).
 */
internal class ReplayRecovery(
    private val budgetMs: Long = BUDGET_MS,
    private val failuresPerVariant: Int = 2
) {
    data class Decision(
        val giveUp: Boolean,
        val delayMs: Long = 0L,
        val switchFormat: Boolean = false,
        val nextVariant: Boolean = false
    )

    private var outageStartedAtMs = 0L
    private var failuresOnVariant = 0
    var failures = 0
        private set

    fun onFailure(nowMs: Long, httpCode: Int?, variantCount: Int, canSwitchFormat: Boolean): Decision {
        if (outageStartedAtMs == 0L) outageStartedAtMs = nowMs
        if (nowMs - outageStartedAtMs > budgetMs) return Decision(giveUp = true)
        failures++
        failuresOnVariant++
        val delayMs = BACKOFF_MS[(failures - 1).coerceAtMost(BACKOFF_MS.size - 1)]
        if (httpCode in URL_REJECTED_CODES && canSwitchFormat) {
            failuresOnVariant = 0
            return Decision(giveUp = false, switchFormat = true)
        }
        val wrongVariant = httpCode in URL_REJECTED_CODES || failuresOnVariant >= failuresPerVariant
        if (wrongVariant && variantCount > 1) {
            failuresOnVariant = 0
            return Decision(giveUp = false, delayMs = delayMs, nextVariant = true)
        }
        return Decision(giveUp = false, delayMs = delayMs)
    }

    /** Frames are flowing again: the next failure starts a fresh outage. */
    fun reset() {
        outageStartedAtMs = 0L
        failuresOnVariant = 0
        failures = 0
    }

    companion object {
        const val BUDGET_MS = 45_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
        private val URL_REJECTED_CODES = setOf(400, 404, 405)
    }
}
