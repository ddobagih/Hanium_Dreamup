package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.navigation.TactileDotReferenceNotice
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal data class TactileDotReferenceDeliveryOwner(
    val epoch: WalkRuntimeEpoch,
    val feedbackLifecycleGeneration: Int,
    val frameGeneration: Int,
    val arGeneration: Long,
)

/** Identity belongs to this reservation, even when an observation or owner has equal values. */
internal class TactileDotReferenceDeliveryReservation internal constructor(
    val notice: TactileDotReferenceNotice,
    val owner: TactileDotReferenceDeliveryOwner,
    internal val reservedAtMs: Long,
)

/** Delivery state only: the caller separately checks its current AR lease and output permissions. */
internal class TactileDotReferenceDeliveryPolicy {
    private val intervalMs = MessagePolicyConfig().rateLimit.pathGuidanceIntervalMs
    private var observationOwner: TactileDotReferenceDeliveryOwner? = null
    private var latestNotice: TactileDotReferenceNotice? = null
    private var lastCapture: TactileDotReferenceNotice? = null
    private var observedAtMs: Long? = null
    private var pending: TactileDotReferenceDeliveryReservation? = null
    private var sourceInvalidatedPending: TactileDotReferenceDeliveryReservation? = null
    private var completedAtMs: Long? = null

    /** GL publishes only while its captured owner is still current in the caller. */
    @Synchronized
    fun updateObservation(
        notice: TactileDotReferenceNotice?,
        owner: TactileDotReferenceDeliveryOwner,
        nowMs: Long,
    ): TactileDotReferenceDeliveryReservation? {
        if (nowMs < 0L) return null
        val retiredOwner = pending.takeIf { observationOwner != owner }
        if (observationOwner != owner) clearState(owner)
        if (observedAtMs?.let { nowMs < it } == true) return null
        val valid = notice?.takeIf { it.isValidAt(nowMs) }
        val previous = lastCapture
        if (valid != null && previous != null &&
            (valid.captureFrameId < previous.captureFrameId ||
                valid.capturedAtElapsedRealtimeMs < previous.capturedAtElapsedRealtimeMs ||
                (latestNotice == null && valid.captureFrameId == previous.captureFrameId &&
                    valid.capturedAtElapsedRealtimeMs == previous.capturedAtElapsedRealtimeMs))
        ) return null
        observedAtMs = nowMs
        latestNotice = valid
        if (valid != null) lastCapture = valid
        // A receipt expiry alone is not a source-loss cancellation of an already started speech.
        val sourceLost = pending?.takeIf { reservation ->
            (notice == null || notice.trackId != reservation.notice.trackId) &&
                sourceInvalidatedPending !== reservation
        }
        if (sourceLost != null) sourceInvalidatedPending = sourceLost
        return retiredOwner ?: sourceLost
    }

    /** Source/terminal identity only; inclusive start TTL remains in [isCurrent]. */
    @Synchronized
    fun isSourceCurrent(reservation: TactileDotReferenceDeliveryReservation): Boolean =
        owns(reservation) && sourceInvalidatedPending !== reservation

    /** UI cannot publish its captured observation or restore a retired owner while reserving. */
    @Synchronized
    fun reserveCurrent(
        owner: TactileDotReferenceDeliveryOwner,
        nowMs: Long,
    ): TactileDotReferenceDeliveryReservation? {
        if (pending != null || observationOwner != owner || nowMs < 0L ||
            observedAtMs?.let { nowMs < it } == true
        ) return null
        val notice = latestNotice?.takeIf { it.isValidAt(nowMs) } ?: return null
        if (completedAtMs?.let { nowMs < it || nowMs - it < intervalMs } == true) return null
        return TactileDotReferenceDeliveryReservation(notice, owner, nowMs).also { pending = it }
    }

    @Synchronized
    fun isCurrent(reservation: TactileDotReferenceDeliveryReservation, nowMs: Long): Boolean =
        owns(reservation) && sourceInvalidatedPending !== reservation && nowMs >= reservation.reservedAtMs &&
            observedAtMs?.let { nowMs >= it } == true &&
            reservation.notice.isValidAt(nowMs) &&
            latestNotice?.let { it.trackId == reservation.notice.trackId && it.isValidAt(nowMs) } == true

    /** Call only for actual speech DONE. Start expiry and later source loss do not undo delivery. */
    @Synchronized
    fun complete(reservation: TactileDotReferenceDeliveryReservation, nowMs: Long): Boolean {
        if (!owns(reservation) || nowMs < reservation.reservedAtMs) return false
        pending = null
        completedAtMs = nowMs
        return true
    }

    /** Includes synchronous rejection and cancellation; neither consumes the completion interval. */
    @Synchronized
    fun fail(reservation: TactileDotReferenceDeliveryReservation): Boolean {
        if (!owns(reservation)) return false
        pending = null
        return true
    }

    /** A delayed owner cleanup must pass its captured owner; null is an intentional full reset. */
    @Synchronized
    fun reset(owner: TactileDotReferenceDeliveryOwner? = null): TactileDotReferenceDeliveryReservation? {
        if (owner != null && observationOwner != owner) return null
        val retired = pending
        clearState(null)
        return retired
    }

    private fun owns(reservation: TactileDotReferenceDeliveryReservation): Boolean =
        pending === reservation && observationOwner == reservation.owner

    private fun clearState(owner: TactileDotReferenceDeliveryOwner?) {
        observationOwner = owner
        latestNotice = null
        lastCapture = null
        observedAtMs = null
        pending = null
        sourceInvalidatedPending = null
        completedAtMs = null
    }
}
