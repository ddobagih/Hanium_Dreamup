package kr.co.hanium.dreamup.walksafe.navigation.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers idle environment/direction explanations after the route's ACTION and INFO reservations.
 * A stale active attempt keeps its native terminal owner, but can no longer mark delivery. Replacing
 * a pending notice never cancels or impersonates that attempt's STOP/ERROR/DONE callback.
 */
internal class RouteRecheckNoticeCoordinator(
    private val speech: SpeechPort,
    private val callbacks: RouteCallbackScheduler,
    private val elapsedRealtimeMs: () -> Long,
    private val captureOwner: (() -> Boolean) -> (() -> Boolean)?,
    private val refreshDirection: () -> Unit,
    private val routeSpeechReserved: () -> Boolean,
    private val instructionRetryIntervalMs: () -> Long,
    private val resumeNavigation: () -> Unit,
) {
    interface SpeechPort {
        val commandPending: Boolean
        fun busy(): Boolean
        fun outputBusy(): Boolean
        fun inputActive(): Boolean
        fun submitStatus(message: String, current: () -> Boolean,
            completed: () -> Unit, failed: (() -> Unit)?): Boolean
    }

    private class Notice(
        val message: String,
        val current: () -> Boolean,
        val onDelivered: () -> Unit,
        var delivered: Boolean = false,
        var retryNotBeforeMs: Long? = null,
    )

    private var directionEpisodeId: Long? = null
    private var pendingDirection: Notice? = null
    private var pendingEnvironment: Notice? = null
    private var activeNotice: Notice? = null
    private var retryTimer: Runnable? = null
    val active: Boolean get() = activeNotice != null

    fun offerEnvironment(message: String, isStillCurrent: () -> Boolean, onDelivered: () -> Unit) {
        val existing = pendingEnvironment
        if (existing == null || existing.message != message || !existing.current()) {
            val current = captureOwner(isStillCurrent) ?: return
            if (!current()) return
            pendingEnvironment = Notice(message, current, onDelivered)
        }
        drain()
    }

    /** Guidance evaluates the grace snapshot; this owner deduplicates that exact episode. */
    fun offerDirection(episodeId: Long, message: String = "방향을 다시 확인하고 있습니다.", isStillCurrent: () -> Boolean) {
        if (directionEpisodeId == episodeId) return
        val current = captureOwner(isStillCurrent) ?: return
        if (!current()) return
        directionEpisodeId = episodeId
        pendingDirection = Notice(message, current, {})
    }

    fun retireDirection() {
        // Do not release an in-flight native attempt before its terminal callback.
        pendingDirection = null
    }

    fun resumeAfterRouteTerminal() {
        if (listOfNotNull(pendingEnvironment, pendingDirection).any { !it.delivered && it.current() }) {
            // ACTION completion enables INFO later in the same stack. Let INFO enqueue first.
            scheduleRetry(1L)
        }
    }

    fun drain() {
        refreshDirection()
        if (activeNotice != null) return
        val candidates = listOfNotNull(pendingEnvironment, pendingDirection)
            .filter { !it.delivered && it.current() }
        if (candidates.isEmpty()) return
        // Includes a queued ACTION before native START, even while the speech port is idle.
        if (routeSpeechReserved()) return
        if (speech.commandPending || speech.busy() || speech.outputBusy() || speech.inputActive()) {
            scheduleRetry()
            return
        }
        val nowMs = elapsedRealtimeMs()
        val notice = candidates.firstOrNull { it.retryNotBeforeMs?.let { due -> nowMs >= due } != false }
        if (notice == null) {
            scheduleRetry(candidates.mapNotNull { it.retryNotBeforeMs }.minOrNull()!! - nowMs)
            return
        }
        activeNotice = notice
        val terminal = AtomicBoolean(false)
        val completed = {
            if (terminal.compareAndSet(false, true)) {
                if (notice.current()) {
                    notice.delivered = true
                    notice.onDelivered()
                }
                if (activeNotice === notice) activeNotice = null
                scheduleRetry()
                resumeNavigation()
            }
            Unit
        }
        val failed = {
            if (terminal.compareAndSet(false, true)) {
                if (activeNotice === notice) activeNotice = null
                val retryIntervalMs = instructionRetryIntervalMs()
                notice.retryNotBeforeMs = elapsedRealtimeMs() + retryIntervalMs
                scheduleRetry(retryIntervalMs)
                resumeNavigation()
            }
            Unit
        }
        val accepted = speech.submitStatus(notice.message,
            { activeNotice === notice && notice.current() }, completed, failed)
        if (!accepted) failed()
    }

    private fun scheduleRetry(delayMs: Long = 250L) {
        if (retryTimer != null) return
        lateinit var retry: Runnable
        retry = Runnable {
            if (retryTimer !== retry) return@Runnable
            retryTimer = null
            drain()
        }
        retryTimer = retry
        callbacks.postDelayed(retry, delayMs.coerceAtLeast(1L))
    }
}
