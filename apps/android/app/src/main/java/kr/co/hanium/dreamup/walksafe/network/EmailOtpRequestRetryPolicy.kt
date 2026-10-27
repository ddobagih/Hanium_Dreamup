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
        if (currentPayload.normalized() == payload) {
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
        val normalizedPayload = currentPayload.normalized()
        if (payload != normalizedPayload) {
            // A fresh process (no payload yet) may continue a persisted in-flight request.
            val restoring = payload == null && requestId == null
            cancel(clearPersisted = false) // replace only after the next recovery point is saved
            payload = normalizedPayload
            if (restoring) requestId = restorePendingRequestId(normalizedPayload, currentPayload.copy(dateOfBirth = normalizedPayload.dateOfBirth))
        }
        if (storageFailure) {
            payload = null // the next explicit retry must read the durable record again
            return null
        }
        if (storageFailure) {
            payload = null // retry the durable read, not a fresh ID, on the next explicit request
            return null
        }
        if (activeAttempt != null || remainingWaitMs(currentPayload) > 0L) return null
        if (requestId != null) {
            val persisted = try {
                pendingStore()?.loadPendingOtpRequest()
            } catch (_: Exception) {
                storageFailure = true
                payload = null
                requestId = null
                return null
            }
            val oldSpelling = currentPayload.copy(dateOfBirth = normalizedPayload.dateOfBirth)
            if (persisted == null || persisted.requestId != requestId ||
                (!persisted.reusableFor(normalizedPayload, wallClockMs()) &&
                    !persisted.reusableFor(oldSpelling, wallClockMs()))) requestId = null
        }
        val wireId = requestId ?: createRequestId().let {
            if (!persistPendingRequest(normalizedPayload, it)) {
                storageFailure = true
                payload = null
                return null
            }
            requestId = it
            it
        }
        return Attempt(wireId, normalizedPayload, ++sequence).also { activeAttempt = it }
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
        persistResult: () -> Boolean,
    ): Boolean {
        if (!accepts(attempt, currentPayload)) return false
        // A process restart must keep the same wire ID until the returned handle is durable.
        if (!runCatching(persistResult).getOrDefault(false)) {
            activeAttempt = null
            storageFailure = true
            return false
        }
        cancel()
        return !storageFailure
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

    private fun restorePendingRequestId(currentPayload: Payload, previouslySerializedPayload: Payload): String? {
        val store = runCatching { pendingStore() }.getOrNull() ?: run {
            storageFailure = true
            return null
        }
        val pending = try {
            store.loadPendingOtpRequest()
        } catch (_: Exception) {
            storageFailure = true
            return null
        } ?: return null
        // Pre-normalization v2 records can still match the exact email spelling the user re-entered.
        return pending.requestId.takeIf {
            pending.reusableFor(currentPayload, wallClockMs()) ||
                pending.reusableFor(previouslySerializedPayload, wallClockMs())
        }
    }

    private fun persistPendingRequest(currentPayload: Payload, wireId: String): Boolean {
        val store = runCatching { pendingStore() }.getOrNull() ?: return false
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
        if (!runCatching { pendingStore()?.clearPendingOtpRequest() == true }.getOrDefault(false)) {
            storageFailure = true
        }
    }

    private fun accepts(attempt: Attempt, currentPayload: Payload?): Boolean {
        if (activeAttempt != attempt) return false
        if (currentPayload?.normalized() != attempt.payload) {
            cancel()
            return false
        }
        return true
    }

    private fun Payload.normalized(): Payload {
        // Invalid edits must still differ from the active request and fence its callbacks.
        val normalizedDate = GatewayAccountInputPolicy.normalizedDateOfBirth(dateOfBirth) ?: return this
        val canonicalEmail = GatewayAccountInputPolicy.canonicalEmailOrNull(email) ?: return this
        return if (dateOfBirth == normalizedDate && email == canonicalEmail) this
        else copy(dateOfBirth = normalizedDate, email = canonicalEmail)
    }
}
