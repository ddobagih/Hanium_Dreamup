package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kotlin.math.ceil
import kotlin.math.floor

data class UnknownObstacleSelection(val show: Boolean, val warningCandidate: Boolean, val reason: String)

/** Camera-forward proximity candidates, not semantic proof of an obstacle or a ground-plane detector. */
object UnknownWalkingObstaclePolicy {
    fun select(mask: BinaryImageMask, output: TrackedObjectDepth?, quarterTurns: Int,
               suppressionReason: String? = null): UnknownObstacleSelection {
        fun hide(reason: String) = UnknownObstacleSelection(false, false, reason)
        if (suppressionReason != null) return hide(suppressionReason)
        if (mask.area == 0 || quarterTurns !in 0..3) return hide("empty_or_invalid_geometry")
        // The band is in upright portrait coordinates. Mapping it back preserves the original mask/depth alignment.
        val band = UprightCameraImage.toSensor(RectNorm(0.25f, 0.12f, 0.50f, 0.83f), quarterTurns)
        val inside = mask.areaInside(floor(band.x * mask.originalWidth).toInt(),
            floor(band.y * mask.originalHeight).toInt(),
            ceil((band.x + band.width) * mask.originalWidth).toInt(),
            ceil((band.y + band.height) * mask.originalHeight).toInt())
        if (inside.toDouble() / mask.area < 0.20) return hide("outside_forward_corridor")
        val distance = output?.riskDistanceM?.takeIf { output.source.metric && it.isFinite() && it > 0f }
        val approaching = output?.timeToCollisionMs?.let { it in 1L..10_000L } == true
        if (distance != null && distance > 4f && !approaching) return hide("outside_near_obstacle_range")
        val repeated = output != null && output.trackAgeFrames >= 2 && output.trackStableMs >= 250L
        if (!repeated && (distance == null || distance > 1.2f)) return hide("waiting_for_repeated_observation")
        return if (distance == null) UnknownObstacleSelection(true, false, "depth_unconfirmed")
            else UnknownObstacleSelection(true, true, "forward_near_obstacle_candidate")
    }
}
