package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken

/** One submitted capture, independent of its context history and any later tactile publication. */
internal class UnknownCaptureAttempt(
    val token: FastSamFrameToken,
    val ticket: InferencePacingTicket,
    private val pacing: AdaptiveInferencePacingPolicy,
) {
    private var terminal = false
    private var consuming = false
    private var leaseRetired = false
    private var lease: UnknownMaterialPointCaptureLease? = null

    fun matches(candidate: FastSamFrameToken): Boolean = sameCapture(token, candidate) &&
        ticket.startedAtElapsedRealtimeMs == candidate.capturedElapsedNs / 1_000_000L

    @Synchronized fun attachLease(capture: UnknownMaterialPointCaptureLease): Boolean {
        if (terminal || leaseRetired || lease != null || !matches(capture.token)) return false
        lease = capture
        return true
    }

    @Synchronized fun claimResult(): Boolean {
        if (terminal || consuming) return false
        consuming = true
        return true
    }

    /** Invalidation withdraws capture authority immediately, while native consumption may drain. */
    fun retireCaptureLease(): Boolean {
        val owned = synchronized(this) {
            leaseRetired = true
            lease.also { lease = null }
        } ?: return false
        if (!owned.isClosed) owned.close()
        return true
    }

    private fun claimTerminal(): Boolean = synchronized(this) {
        if (terminal) false else { terminal = true; true }
    }

    /** Admission failure/backstop is not a measured fast inference. */
    fun cancel(): Boolean {
        if (!claimTerminal()) return false
        retireCaptureLease()
        pacing.cancel(ticket)
        return true
    }

    /** Result and stale discard carry the original capture age into the pacing policy once. */
    fun complete(nowMs: Long, inferenceDurationMs: Long? = null, thermalThrottled: Boolean? = null,
        loadObservation: InferenceLoadObservation? = null,
    ): Boolean {
        if (!claimTerminal()) return false
        retireCaptureLease()
        pacing.complete(ticket, nowMs, inferenceDurationMs, thermalThrottled, loadObservation)
        return true
    }
}
