package kr.co.hanium.dreamup.walksafe.navigation

/** Owns at most two speech attempts for each monotonically increasing guidance-start request. */
internal class GuidanceStartSpeechDelivery {
    data class Attempt(val requestId: Long, val ordinal: Int)

    private var highestRequestId: Long? = null
    private var inFlight: Attempt? = null
    private var attempts = 0
    private var delivered = false
    private var cancelled = false

    @Synchronized
    fun begin(requestId: Long): Attempt? {
        val highest = highestRequestId
        if (highest != null && requestId < highest) return null
        if (highest == null || requestId > highest) {
            highestRequestId = requestId
            inFlight = null
            attempts = 0
            delivered = false
            cancelled = false
        }
        if (cancelled || delivered || inFlight != null || attempts >= MAX_ATTEMPTS) return null
        return Attempt(requestId, ++attempts).also { inFlight = it }
    }

    /** Call only for the speech engine's actual onDone, never for submission acceptance. */
    @Synchronized
    fun complete(attempt: Attempt): Boolean {
        if (inFlight != attempt) return false
        inFlight = null
        delivered = true
        return true
    }

    /** Releases only the current attempt; true permits one bounded retry of the same request. */
    @Synchronized
    fun fail(attempt: Attempt): Boolean {
        if (inFlight != attempt) return false
        inFlight = null
        return attempts < MAX_ATTEMPTS
    }

    @Synchronized
    fun cancel() {
        inFlight = null
        attempts = 0
        delivered = false
        // Keep the highest ID as a tombstone against delayed begin/retry callbacks.
        cancelled = true
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
