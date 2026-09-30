package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException

/**
 * Server-side confirmation of a walk end (S03-21). The local walk has already stopped; the server
 * release is confirmed separately and retried with the same end request id, only with the gateway
 * session that owned the lease and only while that lease could still be held on the server
 * (FP-012 design_rules[4], FP-012-DETAIL-02: one request id is processed once).
 */
enum class GatewayWalkEndOutcome {
    CONFIRMED,
    ALREADY_RELEASED,
    RETRY,
    GIVE_UP,
}

object GatewayWalkEndRetryPolicy {
    const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    private val RELEASED_CODES = setOf("walk_lease_conflict", "walk_lease_stale", "walk_lease_expired")
    private val TRANSIENT_CODES = setOf("walk_ledger_busy", "walk_ledger_unavailable")

    fun outcome(error: Throwable?): GatewayWalkEndOutcome = when {
        error == null -> GatewayWalkEndOutcome.CONFIRMED
        error is GatewayWalkHttpException -> when {
            error.serverCode in RELEASED_CODES -> GatewayWalkEndOutcome.ALREADY_RELEASED
            error.serverCode in TRANSIENT_CODES ||
                error.statusCode == 408 || error.statusCode == 429 ||
                error.statusCode in 500..599 -> GatewayWalkEndOutcome.RETRY
            // Login loss, invalid commands and unknown rejections are left to the lease expiry.
            else -> GatewayWalkEndOutcome.GIVE_UP
        }
        error is IOException -> GatewayWalkEndOutcome.RETRY
        else -> GatewayWalkEndOutcome.GIVE_UP
    }

    /** Delay before resending the same end request, or null when no further attempt is made. */
    fun retryDelayMsOrNull(
        error: Throwable?,
        previousRetries: Int,
        leaseMayStillBeHeld: Boolean,
        sameGatewaySession: Boolean,
    ): Long? {
        if (outcome(error) != GatewayWalkEndOutcome.RETRY) return null
        if (!leaseMayStillBeHeld || !sameGatewaySession) return null
        if (previousRetries !in 0 until MAX_RETRIES) return null
        return RETRY_DELAYS_MS[previousRetries]
    }
}
