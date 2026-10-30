package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket

/** A service's terminal callback can release only its own admitted frame, including after pause. */
internal class CameraAuxiliaryPacing {
    private val pacing = AdaptiveInferencePacingPolicy()
    private var epoch: Long? = null
    private var flight: Flight? = null
    private data class Flight(val epoch: Long, val frame: Long, val ticket: InferencePacingTicket)

    @Synchronized fun begin(serviceEpoch: Long, intervalMs: Long? = null) {
        flight?.let { pacing.cancel(it.ticket) }
        epoch = serviceEpoch
        flight = null
        pacing.reset()
        intervalMs?.let(pacing::configureMinimumInterval)
    }

    @Synchronized fun isDue(serviceEpoch: Long, nowMs: Long): Boolean =
        epoch == serviceEpoch && flight == null && pacing.isDue(nowMs)

    @Synchronized fun tryStart(serviceEpoch: Long, frameId: Long, capturedAtMs: Long): Boolean {
        if (epoch != serviceEpoch || flight != null) return false
        val ticket = pacing.tryStart(capturedAtMs) ?: return false
        flight = Flight(serviceEpoch, frameId, ticket)
        return true
    }

    @Synchronized fun complete(serviceEpoch: Long, frameId: Long, nowMs: Long,
        inferenceMs: Long? = null, thermalThrottled: Boolean? = null,
        load: InferenceLoadObservation? = null): Boolean {
        val current = flight?.takeIf { it.epoch == serviceEpoch && it.frame == frameId } ?: return false
        flight = null
        return pacing.complete(current.ticket, nowMs, inferenceMs, thermalThrottled, load)
    }

    @Synchronized fun cancel(serviceEpoch: Long, frameId: Long): Boolean {
        val current = flight?.takeIf { it.epoch == serviceEpoch && it.frame == frameId } ?: return false
        flight = null
        pacing.cancel(current.ticket)
        return true
    }

    @Synchronized fun cancelService(serviceEpoch: Long) {
        if (epoch == serviceEpoch) reset()
    }

    @Synchronized fun reset() {
        flight?.let { pacing.cancel(it.ticket) }
        epoch = null
        flight = null
        pacing.reset()
    }

    @Synchronized fun snapshot() = pacing.snapshot()
}
