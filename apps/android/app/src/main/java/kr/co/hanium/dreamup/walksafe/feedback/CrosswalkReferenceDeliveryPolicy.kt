package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.navigation.CrosswalkReferenceNotice
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal data class CrosswalkReferenceDeliveryOwner(
    val epoch: WalkRuntimeEpoch,
    val feedbackLifecycleGeneration: Int,
    val frameGeneration: Int,
    val arGeneration: Long,
)

/** The exact reservation owns its terminal receipt, including when its values are reused. */
internal class CrosswalkReferenceDeliveryReservation internal constructor(
    val notice: CrosswalkReferenceNotice,
    val owner: CrosswalkReferenceDeliveryOwner,
    internal val reservedAtMs: Long,
)

/** The caller separately validates its active route, AR lease and output permissions at start. */
internal class CrosswalkReferenceDeliveryPolicy {
    private val intervalMs = MessagePolicyConfig().rateLimit.pathGuidanceIntervalMs
    private var observationOwner: CrosswalkReferenceDeliveryOwner? = null
    private var latestNotice: CrosswalkReferenceNotice? = null
    private var lastCapture: CrosswalkReferenceNotice? = null
    private var observedAtMs: Long? = null
    private var pending: CrosswalkReferenceDeliveryReservation? = null
    private var completedAtMs: Long? = null

    /** GL publishes only while its captured owner is still current in the caller. */
    @Synchronized
    fun updateObservation(
        notice: CrosswalkReferenceNotice?,
        owner: CrosswalkReferenceDeliveryOwner,
        nowMs: Long,
    ) {
        if (nowMs < 0L) return
        if (observationOwner != owner) clearState(owner)
        if (observedAtMs?.let { nowMs < it } == true) return
        val valid = notice?.takeIf { it.isValidAt(nowMs) }
        val previous = lastCapture
        if (valid != null && previous != null &&
            (valid.captureFrameId < previous.captureFrameId ||
                valid.capturedAtElapsedRealtimeMs < previous.capturedAtElapsedRealtimeMs ||
                (latestNotice == null && valid.captureFrameId == previous.captureFrameId &&
                    valid.capturedAtElapsedRealtimeMs == previous.capturedAtElapsedRealtimeMs))
        ) return
        observedAtMs = nowMs
        latestNotice = valid
        if (valid != null) lastCapture = valid
        // Source loss blocks a new start; an actual started utterance retains its terminal token.
    }

    /** UI reserves only the published observation, without restoring its captured older notice. */
    @Synchronized
    fun reserveCurrent(
        owner: CrosswalkReferenceDeliveryOwner,
        nowMs: Long,
    ): CrosswalkReferenceDeliveryReservation? {
        if (pending != null || observationOwner != owner || nowMs < 0L ||
            observedAtMs?.let { nowMs < it } == true
        ) return null
        val notice = latestNotice?.takeIf { it.isValidAt(nowMs) } ?: return null
        if (completedAtMs?.let { nowMs < it || nowMs - it < intervalMs } == true) return null
        return CrosswalkReferenceDeliveryReservation(notice, owner, nowMs).also { pending = it }
    }

    @Synchronized
    fun isCurrent(reservation: CrosswalkReferenceDeliveryReservation, nowMs: Long): Boolean =
        owns(reservation) && nowMs >= reservation.reservedAtMs &&
            observedAtMs?.let { nowMs >= it } == true &&
            reservation.notice.isValidAt(nowMs) &&
            latestNotice?.let {
                it.trackId == reservation.notice.trackId &&
                    it.routeId == reservation.notice.routeId &&
                    it.routeVersion == reservation.notice.routeVersion &&
                    it.routeSegmentIndex == reservation.notice.routeSegmentIndex && it.isValidAt(nowMs)
            } == true

    /** Actual speech DONE may follow the start deadline or loss of the source observation. */
    @Synchronized
    fun complete(reservation: CrosswalkReferenceDeliveryReservation, nowMs: Long): Boolean {
        if (!owns(reservation) || nowMs < reservation.reservedAtMs) return false
        pending = null
        completedAtMs = nowMs
        return true
    }

    /** Rejection, failure and cancellation release the token without consuming the DONE interval. */
    @Synchronized
    fun fail(reservation: CrosswalkReferenceDeliveryReservation): Boolean {
        if (!owns(reservation)) return false
        pending = null
        return true
    }

    /** Captured-owner cleanup cannot reset a newer owner; null is an intentional full reset. */
    @Synchronized
    fun reset(owner: CrosswalkReferenceDeliveryOwner? = null) {
        if (owner != null && observationOwner != owner) return
        clearState(null)
    }

    private fun owns(reservation: CrosswalkReferenceDeliveryReservation): Boolean =
        pending === reservation && observationOwner == reservation.owner

    private fun clearState(owner: CrosswalkReferenceDeliveryOwner?) {
        observationOwner = owner
        latestNotice = null
        lastCapture = null
        observedAtMs = null
        pending = null
        completedAtMs = null
    }
}
