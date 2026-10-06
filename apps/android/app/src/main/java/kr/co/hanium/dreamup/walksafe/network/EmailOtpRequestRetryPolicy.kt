package kr.co.hanium.dreamup.walksafe.network

/**
 * UI-thread-owned retry state. The wire ID survives uncertain delivery; each async attempt is distinct.
 * Payload fields match the actual client request, without a second email canonicalization rule.
 */
internal class EmailOtpRequestRetryPolicy(
    private val elapsedRealtimeMs: () -> Long,
    private val createRequestId: () -> String,
    // S01-5: encrypted minimal record so a restarted process resends the same request ID.
    private val pendingStore: () -> PendingEmailOtpRequestStore?,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
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
    var storageFailure: Boolean = false
        private set

    fun remainingWaitMs(currentPayload: Payload): Long =
        if (currentPayload == payload) {
            (retryAvailableAtMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        } else {
            0L
        }

    fun begin(currentPayload: Payload): Attempt? {
        storageFailure = false
        if (runCatching { pendingStore() }.getOrNull() == null) {
            storageFailure = true
            return null
        }
        if (payload != currentPayload) {
            // A fresh process (no payload yet) may continue a persisted in-flight request.
            val restoring = payload == null && requestId == null
            // Replacing the record is one write; do not erase the old recovery point first.
            cancel(clearPersisted = false)
            payload = currentPayload
            if (restoring) requestId = restorePendingRequestId(currentPayload)
        }
        if (storageFailure) {
            payload = null // retry the durable read, not a fresh ID, on the next explicit request
            return null
        }
        if (activeAttempt != null || remainingWaitMs(currentPayload) > 0L) return null
        val wireId = requestId ?: createRequestId().let {
            if (!persistPendingRequest(currentPayload, it)) {
                storageFailure = true
                payload = null
                return null
            }
            requestId = it
            it
        }
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
        val retryable = statusCode == 0 || statusCode == 408 || statusCode in 500..599 || statusCode == 429 || pending
        if (!retryable) {
            requestId = null
            retryAvailableAtMs = 0L
            clearPendingRequest()
            return true
        }
        val delay = retryAfterMs?.takeIf { it in 1L..300_000L }
            ?: if (pending || statusCode == 429) 1_000L else 0L
        retryAvailableAtMs = elapsedRealtimeMs() + delay
        return true
    }

    fun recordSuccess(
        attempt: Attempt,
        currentPayload: Payload?,
        persistResult: () -> Boolean = { true },
    ): Boolean {
        if (!accepts(attempt, currentPayload)) return false
        // Keep the replay ID until the returned enrollment is durably saved.
        if (!runCatching(persistResult).getOrDefault(false)) {
            activeAttempt = null
            storageFailure = true
            return false
        }
        cancel()
        return true
    }

    fun suspendForLifecycle() {
        activeAttempt = null
    }

    fun cancel() = cancel(clearPersisted = true)

    private fun cancel(clearPersisted: Boolean) {
        payload = null
        requestId = null
        activeAttempt = null
        retryAvailableAtMs = 0L
        if (clearPersisted) clearPendingRequest()
    }

    private fun restorePendingRequestId(currentPayload: Payload): String? {
        val store = pendingStore() ?: return null
        val pending = try {
            store.loadPendingOtpRequest()
        } catch (_: Exception) {
            storageFailure = true
            return null
        } ?: return null
        if (pending.reusableFor(currentPayload, wallClockMs())) return pending.requestId
        return null
    }

    private fun persistPendingRequest(currentPayload: Payload, wireId: String): Boolean {
        val store = pendingStore() ?: return false
        val pending = runCatching {
            PendingEmailOtpRequest(
                requestId = wireId,
                ownerBindingSha256 = currentPayload.ownerBindingSha256,
                payloadSha256 = pendingEmailOtpPayloadSha256(currentPayload, wireId),
                startedAtEpochMs = wallClockMs(),
            )
        }.getOrNull() ?: return false
        return runCatching { store.savePendingOtpRequest(pending) }.getOrDefault(false)
    }

    private fun clearPendingRequest() {
        runCatching { pendingStore()?.clearPendingOtpRequest() }
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
