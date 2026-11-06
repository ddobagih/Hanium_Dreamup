package kr.co.hanium.dreamup.walksafe.navigation.session

/**
 * Owns the timer and backoff of one admitted initial human start. Acquisition still admits the
 * actor/session/archive lease and dispatches the request; polling cannot create or extend an intent.
 */
internal class InitialRouteRetryEpisode(
    private val callbacks: RouteCallbackScheduler,
    private val elapsedRealtimeMs: () -> Long,
    private val retry: (Episode) -> Boolean,
) {
    class Episode internal constructor(
        val context: RouteAcquisitionController.ExplicitRouteStartContext,
        val requestedAtMs: Long,
        val firstFailureAtMs: Long,
        requestId: Int,
    ) {
        var requestId: Int = requestId
            private set
        var delayMs: Long = 2_000L
            private set
        var retryAtMs: Long = firstFailureAtMs + delayMs
            private set
        var failureCount: Int = 1
            private set
        val deadlineMs: Long = if (firstFailureAtMs > Long.MAX_VALUE - MAXIMUM_RECOVERY_MS)
            Long.MAX_VALUE else firstFailureAtMs + MAXIMUM_RECOVERY_MS

        fun exhaustedAt(nowMs: Long): Boolean = failureCount >= MAXIMUM_FAILURES || nowMs >= deadlineMs

        internal fun bindRequest(requestId: Int) { this.requestId = requestId }
        internal fun advanceFailure(nowMs: Long) {
            failureCount += 1
            delayMs = minOf(10_000L, delayMs * 2L)
            retryAtMs = nowMs + delayMs
        }
    }

    var current: Episode? = null
        private set
    private var timer: Runnable? = null

    fun eligibleAt(nowMs: Long): Boolean = current?.let { !it.exhaustedAt(nowMs) && nowMs >= it.retryAtMs } != false

    /** Called only for a newly admitted failure, never by observation polling. */
    fun retainFailure(
        context: RouteAcquisitionController.ExplicitRouteStartContext,
        requestedAtMs: Long,
        requestId: Int,
        nowMs: Long = elapsedRealtimeMs(),
    ): Episode? {
        val previous = current
        if (previous != null && (previous.context != context || previous.requestedAtMs != requestedAtMs)) return null
        if (nowMs < requestedAtMs || previous?.let { nowMs < it.firstFailureAtMs } == true) return null
        if (previous?.exhaustedAt(nowMs) == true) return previous
        val episode = previous ?: Episode(context, requestedAtMs, nowMs, requestId)
        if (previous != null) episode.advanceFailure(nowMs)
        episode.bindRequest(requestId)
        current = episode
        return episode
    }

    fun bindRequest(requestId: Int) {
        cancelTimer()
        current?.let {
            it.bindRequest(requestId)
            // Starting transport must not remove the episode's absolute time limit.
            schedule(it, maxOf(0L, it.deadlineMs - elapsedRealtimeMs()))
        }
    }

    fun cancelTimer() {
        val previous = timer
        timer = null
        previous?.let(callbacks::remove)
    }

    fun clear() {
        current = null
        cancelTimer()
    }

    fun schedule(episode: Episode, delayMs: Long = maxOf(0L, episode.retryAtMs - elapsedRealtimeMs())) {
        if (current !== episode) return
        cancelTimer()
        val requestId = episode.requestId
        lateinit var callback: Runnable
        callback = Runnable {
            if (timer !== callback || current !== episode || episode.requestId != requestId) return@Runnable
            timer = null
            val nowMs = elapsedRealtimeMs()
            if (retry(episode) && current === episode && episode.requestId == requestId &&
                !episode.exhaustedAt(elapsedRealtimeMs())) {
                // Keep the first failure/deadline while a current GPS observation is unavailable.
                schedule(episode, maxOf(1_000L, episode.retryAtMs - nowMs))
            }
        }
        timer = callback
        callbacks.postDelayed(callback, minOf(delayMs.coerceAtLeast(0L),
            (episode.deadlineMs - elapsedRealtimeMs()).coerceAtLeast(0L)))
    }

    companion object {
        internal const val MAXIMUM_FAILURES = 5
        internal const val MAXIMUM_RECOVERY_MS = 60_000L
    }
}
