package kr.co.hanium.dreamup.walksafe.network

/**
 * UI-thread-owned retry state. The wire ID survives uncertain delivery; each async attempt is distinct.
 * Payload fields match the actual client request, without a second email canonicalization rule.
 */
internal class EmailOtpRequestRetryPolicy(
    private val elapsedRealtimeMs: () -> Long,
    private val createRequestId: () -> String,
) {
    internal data class Payload(
        val gatewayBaseUrl: String,
        val ownerBindingSha256: String,
        val signupAttempt: String,
        val email: String,
        val dateOfBirth: String,
    ) {
        override fun toString(): String = "EmailOtpRetryPayload(redacted)"
    }

    internal data class Attempt internal constructor(
        val requestId: String,
        val payload: Payload,
        private val sequence: Long,
    ) {
        override fun toString(): String = "EmailOtpRetryAttempt(redacted)"
    }

    private var sequence = 0L
    private var payload: Payload? = null
    private var requestId: String? = null
    private var activeAttempt: Attempt? = null
    private var retryAvailableAtMs = 0L

    fun remainingWaitMs(currentPayload: Payload): Long =
        if (currentPayload == payload) {
            (retryAvailableAtMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        } else {
            0L
        }

    fun begin(currentPayload: Payload): Attempt? {
        if (payload != currentPayload) {
            cancel()
            payload = currentPayload
        }
        if (activeAttempt != null || remainingWaitMs(currentPayload) > 0L) return null
        val wireId = requestId ?: createRequestId().also { requestId = it }
        return Attempt(wireId, currentPayload, ++sequence).also { activeAttempt = it }
    }

    fun recordFailure(
        attempt: Attempt,
        currentPayload: Payload?,
        statusCode: Int,
        serverCode: String?,
        retryAfterMs: Long?,
    ): Boolean {
        if (!accepts(attempt, currentPayload)) return false
        activeAttempt = null
        val pending = statusCode == 409 && serverCode == "account_enrollment_in_progress"
        val retryable = statusCode == 0 || statusCode in 500..599 || statusCode == 429 || pending
        if (!retryable) {
            requestId = null
            retryAvailableAtMs = 0L
            return true
        }
        val delay = retryAfterMs?.takeIf { it in 1L..300_000L }
            ?: if (pending || statusCode == 429) 1_000L else 0L
        retryAvailableAtMs = elapsedRealtimeMs() + delay
        return true
    }

    fun recordSuccess(attempt: Attempt, currentPayload: Payload?): Boolean {
        if (!accepts(attempt, currentPayload)) return false
        cancel()
        return true
    }

    fun suspendForLifecycle() {
        activeAttempt = null
    }

    fun cancel() {
        payload = null
        requestId = null
        activeAttempt = null
        retryAvailableAtMs = 0L
    }

    private fun accepts(attempt: Attempt, currentPayload: Payload?): Boolean {
        if (activeAttempt != attempt) return false
        if (currentPayload != attempt.payload) {
            cancel()
            return false
        }
        return true
    }
}
