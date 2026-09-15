package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kotlin.math.ceil
import kotlin.math.floor

data class UnknownObstacleSelection(val show: Boolean, val warningCandidate: Boolean, val reason: String)

/** Measured nearby surfaces selected by physical corridor evidence, with a conservative image fallback. */
object UnknownWalkingObstaclePolicy {
    fun select(mask: BinaryImageMask, output: TrackedObjectDepth?, quarterTurns: Int,
               suppressionReason: String? = null, metricExtent: ObservedMaskExtent? = null,
               spatialEvidence: UnknownSpatialEvidence? = null): UnknownObstacleSelection {
        fun hide(reason: String) = UnknownObstacleSelection(false, false, reason)
        val partialCoverage = suppressionReason == "mask_outside_calibrated_depth_coverage"
        if (suppressionReason != null && !partialCoverage) return hide(suppressionReason)
        if (mask.area == 0 || quarterTurns !in 0..3) return hide("empty_or_invalid_geometry")
        when (spatialEvidence?.disposition) {
            UnknownSpatialDisposition.OUTSIDE_CORRIDOR, UnknownSpatialDisposition.FLAT_FLOOR -> return hide(spatialEvidence.reason)
            UnknownSpatialDisposition.CORRIDOR_OBSTACLE -> Unit
            else -> if (!hasForwardCorridorSupport(mask, quarterTurns)) return hide("outside_forward_corridor")
        }
        if (output?.depthAvailability == DepthAvailability.PREDICTED) {
            val prediction = output.prediction ?: return hide("depth_prediction_unbounded")
            val upper = (spatialEvidence?.predictionUpperRangeM ?: prediction.rangeUpperBoundM)
                ?.takeIf { it.isFinite() && it > 0f }
            if (partialCoverage || upper == null || !prediction.distanceM.isFinite() || prediction.distanceM <= 0f ||
                !prediction.errorBoundM.isFinite() || prediction.errorBoundM < 0f || prediction.horizonMs <= 0L ||
                prediction.predictionAgeMs !in 0L..prediction.horizonMs ||
                prediction.observedAtMs < 0L || output.timestampMs - prediction.observedAtMs != prediction.predictionAgeMs ||
                upper < prediction.distanceM + prediction.errorBoundM) return hide("depth_prediction_unbounded")
            if (upper > 3f) return hide("outside_near_obstacle_range")
            return UnknownObstacleSelection(true, false, "bounded_depth_prediction_display_only")
        }
        val measured = output != null && output.depthAvailability == DepthAvailability.MEASURED && output.source.metric && !partialCoverage
        // riskDistanceM can be axial Z: only calibrated radial evidence establishes the 3 m limit.
        val distance = (spatialEvidence?.nearestReliableRangeM ?: output?.rayDistanceM)
            ?.takeIf { measured && it.isFinite() && it > 0f }
        if (distance == null) return hide("depth_unconfirmed")
        if (distance > 3f) return hide("outside_near_obstacle_range")
        val repeated = output != null && output.trackAgeFrames >= 2 && output.trackStableMs >= 250L
        if (!repeated && distance > 1.2f) return hide("waiting_for_repeated_observation")
        // Visible extent cannot prove harmlessness: retain small, low, thin and head-height surfaces.
        return UnknownObstacleSelection(true, true, if (spatialEvidence?.disposition == UnknownSpatialDisposition.CORRIDOR_OBSTACLE)
            spatialEvidence.reason else "forward_near_obstacle_candidate")
    }

    internal fun hasForwardCorridorSupport(mask: BinaryImageMask, quarterTurns: Int): Boolean {
        if (mask.area == 0 || quarterTurns !in 0..3) return false
        // The band is in upright portrait coordinates. Mapping it back preserves the original mask/depth alignment.
        val band = UprightCameraImage.toSensor(RectNorm(0.25f, 0f, 0.50f, 1f), quarterTurns)
        val inside = mask.areaInside(floor(band.x * mask.originalWidth).toInt(),
            floor(band.y * mask.originalHeight).toInt(),
            ceil((band.x + band.width) * mask.originalWidth).toInt(),
            ceil((band.y + band.height) * mask.originalHeight).toInt())
        return inside.toDouble() / mask.area >= 0.20
    }
}
