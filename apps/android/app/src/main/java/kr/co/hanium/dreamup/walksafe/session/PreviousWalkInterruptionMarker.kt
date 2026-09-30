package kr.co.hanium.dreamup.walksafe.session

/** Keeps a previous interruption until notice acknowledgement and a fresh runtime completion. */
class PreviousWalkInterruptionMarker(
    interruptedAtStartup: Boolean,
    private val writeReleasedMarker: (currentWalkRequiresMarker: Boolean) -> Boolean,
) {
    var isPending: Boolean = interruptedAtStartup
        private set

    private var noticeAcknowledged = false
    private var newWalkActivationCompleted = false

    fun onNoticeAcknowledged(currentWalkRequiresMarker: Boolean) {
        noticeAcknowledged = true
        releaseIfReady(currentWalkRequiresMarker)
    }

    fun onNewWalkActivationCompleted(currentWalkRequiresMarker: Boolean) {
        newWalkActivationCompleted = true
        releaseIfReady(currentWalkRequiresMarker)
    }

    private fun releaseIfReady(currentWalkRequiresMarker: Boolean) {
        if (!isPending || !noticeAcknowledged || !newWalkActivationCompleted) return
        val stored = runCatching { writeReleasedMarker(currentWalkRequiresMarker) }
            .getOrDefault(false)
        if (stored) isPending = false
    }
}
