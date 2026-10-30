package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthObservation
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

/** Keep whole-mask distance, a supported nearby surface and unmeasured geometry distinct. */
internal object CameraUnknownObservation {
    data class DisplayCandidate(val observation: UnknownDepthObservation?, val output: TrackedObjectDepth?) {
        val feedbackId: String? get() = observation?.let(CameraUnknownObservation::feedbackId) ?: output?.trackId
        val distanceM: Double? get() = if (observation != null) CameraUnknownObservation.distanceM(observation)
            ?: output?.prediction?.distanceM?.toDouble() else output?.riskDistanceM?.toDouble()
        val bboxNorm: RectNorm get() = observation?.let { observation ->
            val mask = mask(observation)
            RectNorm(mask.left.toFloat() / mask.originalWidth, mask.top.toFloat() / mask.originalHeight,
                mask.width.toFloat() / mask.originalWidth, mask.height.toFloat() / mask.originalHeight)
        } ?: requireNotNull(output).bboxNorm
    }

    fun distanceM(observation: UnknownDepthObservation): Double? {
        if (observation.suppressionReason == "mask_outside_calibrated_depth_coverage") return null
        val nearby = observation.proximityDistanceM?.takeIf { it.isFinite() && it > 0f }
        if (nearby != null) return nearby.toDouble()
        return observation.depth.axialDepthM?.takeIf {
            observation.depth.status == MaskDepthEstimator.Status.KNOWN &&
                observation.depth.distanceScope == MaskDepthEstimator.Scope.ALL_COMPONENTS && it.isFinite() && it > 0.0
        }
    }

    fun mask(observation: UnknownDepthObservation) = observation.proximityMask ?: observation.mask
    /** Only this displayed region's output may annotate its distance and bounds. */
    fun feedbackId(observation: UnknownDepthObservation) = observation.proximityRegionId ?: observation.trackId

    /** Rank every visible region together before the diagnostic cap; feedback admission is unchanged. */
    fun selectForDisplay(observations: List<UnknownDepthObservation>, outputs: List<TrackedObjectDepth>,
                         admittedOutputs: List<TrackedObjectDepth>, limit: Int = 6): List<DisplayCandidate> {
        val outputsById = outputs.associateBy { it.trackId }
        val admittedById = admittedOutputs.associateBy { it.trackId }
        val candidates = observations.map { DisplayCandidate(it, outputsById[feedbackId(it)]) } +
            unrepresentedWarnings(observations, outputs).map { DisplayCandidate(null, it) }
        val ordered = candidates.sortedWith(compareByDescending<DisplayCandidate> {
            admittedById[it.feedbackId]?.userFacing?.messageLevel?.takeIf { level -> level in ALERT_LEVELS }?.ordinal ?: 0
        }.thenBy { admittedById[it.feedbackId]?.timeToCollisionMs?.takeIf { ttc -> ttc >= 0L } ?: Long.MAX_VALUE }
            .thenBy { it.distanceM == null }.thenBy { it.distanceM ?: Double.MAX_VALUE })
        return ordered.filterIndexed { index, candidate ->
            val observation = candidate.observation ?: return@filterIndexed true
            ordered.take(index).none { earlier ->
                earlier.observation?.let { prior ->
                    mask(observation).iou(mask(prior)) >= 0.70f && candidate.distanceM?.let { distance ->
                        earlier.distanceM?.let { kotlin.math.abs(distance - it) <= 0.30 }
                    } == true
                } == true
            }
        }.take(limit)
    }

    fun unrepresentedWarnings(observations: List<UnknownDepthObservation>, outputs: List<TrackedObjectDepth>): List<TrackedObjectDepth> {
        val displayedIds = observations.mapNotNull(::feedbackId).toSet()
        return outputs.filter { output ->
            output.trackId !in displayedIds && output.walkingObstacleCandidate &&
                output.userFacing.messageLevel in ALERT_LEVELS &&
                output.riskDistanceM?.let { it.isFinite() && it > 0f } == true
        }
    }

    private val ALERT_LEVELS = setOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)
}
