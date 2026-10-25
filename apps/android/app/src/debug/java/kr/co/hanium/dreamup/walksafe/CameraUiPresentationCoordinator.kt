package kr.co.hanium.dreamup.walksafe

/**
 * UI-thread presentation only: domain callbacks still record every capture and offer every risk sample.
 * Retains one current render signature and at most one expiry callback for this model source.
 */
internal class CameraUiPresentationCoordinator<Signature>(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
) {
    private var current: CameraObjectPresentation.Capture? = null
    private var rendered: Signature? = null
    private var hasRendered = false
    private var pendingExpiry: Expiry? = null
    private class Expiry(val deadlineMs: Long, val callback: Runnable)

    /** Preserve newest capture identity even when the boxes and text have equal values. */
    fun begin(capture: CameraObjectPresentation.Capture): Boolean {
        val previous = current
        if (previous != null && (capture.epoch < previous.epoch || capture.epoch == previous.epoch &&
                (capture.frameId <= previous.frameId || capture.capturedAtMs < previous.capturedAtMs))) return false
        cancelExpiry()
        current = capture
        rendered = null
        hasRendered = false
        return true
    }

    fun shouldRender(capture: CameraObjectPresentation.Capture, signature: Signature): Boolean {
        if (current != capture || hasRendered && rendered == signature) return false
        rendered = signature
        hasRendered = true
        return true
    }

    /** Deadlines are inclusive. On expiry the caller renders and schedules its next remaining deadline. */
    fun scheduleExpiry(
        capture: CameraObjectPresentation.Capture,
        deadlinesMs: List<Long?>,
        nowMs: Long,
        isLive: () -> Boolean,
        render: () -> Unit,
    ) {
        if (current != capture) return
        var deadline: Long? = null
        for (candidate in deadlinesMs) {
            if (candidate != null && candidate >= nowMs && candidate < Long.MAX_VALUE &&
                (deadline == null || candidate < deadline)) deadline = candidate
        }
        if (deadline == pendingExpiry?.deadlineMs) return
        cancelExpiry()
        if (deadline == null) return
        lateinit var expiry: Expiry
        val callback = Runnable {
            if (pendingExpiry !== expiry) return@Runnable
            pendingExpiry = null
            if (current == capture && isLive()) render()
        }
        expiry = Expiry(deadline, callback)
        pendingExpiry = expiry
        postDelayed(callback, (deadline - nowMs + 1L).coerceAtLeast(1L))
    }

    fun clear() {
        cancelExpiry()
        current = null
        rendered = null
        hasRendered = false
    }

    private fun cancelExpiry() {
        pendingExpiry?.let { removeCallback(it.callback) }
        pendingExpiry = null
    }
}
