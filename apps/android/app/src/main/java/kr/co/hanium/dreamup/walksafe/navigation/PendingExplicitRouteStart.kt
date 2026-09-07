package kr.co.hanium.dreamup.walksafe.navigation

internal class PendingExplicitRouteStart<C>(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(timeoutMs > 0L)
    }

    private var pending: Request<C>? = null

    fun replace(context: C, nowElapsedRealtimeMs: Long): Request<C> {
        require(nowElapsedRealtimeMs >= 0L)
        return Request(context, nowElapsedRealtimeMs).also { pending = it }
    }

    fun currentOrNull(): Request<C>? = pending

    fun consumeIfCurrent(context: C, nowElapsedRealtimeMs: Long): C? {
        val request = pending ?: return null
        if (request.context != context) {
            pending = null
            return null
        }
        // Leave an expired request for its timeout callback so the user gets the retry reason.
        if (request.isExpired(nowElapsedRealtimeMs, timeoutMs)) return null
        pending = null
        return request.context
    }

    fun expireIfCurrent(
        expected: Request<C>,
        context: C,
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        val request = pending ?: return false
        if (request !== expected) return false
        if (request.context != context) {
            pending = null
            return false
        }
        if (!request.isExpired(nowElapsedRealtimeMs, timeoutMs)) return false
        pending = null
        return true
    }

    fun cancel() {
        pending = null
    }

    data class Request<C>(
        val context: C,
        val requestedAtElapsedRealtimeMs: Long,
    ) {
        internal fun isExpired(nowElapsedRealtimeMs: Long, timeoutMs: Long): Boolean {
            require(nowElapsedRealtimeMs >= requestedAtElapsedRealtimeMs)
            return nowElapsedRealtimeMs - requestedAtElapsedRealtimeMs >= timeoutMs
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
