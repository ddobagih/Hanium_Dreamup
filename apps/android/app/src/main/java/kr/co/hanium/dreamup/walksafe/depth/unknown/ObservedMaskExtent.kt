package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kotlin.math.max

/** Projected visible mask extent, NOT the dimensions of a complete or unoccluded object. */
data class ObservedMaskExtent(
    val widthM: Float,
    val heightM: Float,
    val conservativeWidthM: Float,
    val conservativeHeightM: Float,
    /** Strong enough measured support for the distant-speck heuristic, not semantic proof of harmlessness. */
    val canRejectSmall: Boolean,
    val reason: String,
)

object ObservedMaskExtentEstimator {
    fun estimate(mask: BinaryImageMask, depth: MaskDepthEstimator.Result,
                 calibration: MaskDepthEstimator.Calibration?, fullyCovered: Boolean,
                 quarterTurns: Int): ObservedMaskExtent? {
        if (!fullyCovered || mask.area == 0 || quarterTurns !in 0..3 || depth.status != MaskDepthEstimator.Status.KNOWN ||
            depth.distanceScope != MaskDepthEstimator.Scope.ALL_COMPONENTS) return null
        val z = depth.axialDepthM?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val intrinsics = calibration?.intrinsics ?: return null
        if (calibration.imageWidth != mask.originalWidth || calibration.imageHeight != mask.originalHeight ||
            intrinsics.imageWidth() != mask.originalWidth || intrinsics.imageHeight() != mask.originalHeight ||
            !intrinsics.fx().isFinite() || !intrinsics.fy().isFinite() || intrinsics.fx() <= 0 || intrinsics.fy() <= 0) return null
        // Use the enclosing RGB mask, never the smaller patch where depth happened to succeed.
        // A padded mask ROI overestimates extent and therefore cannot introduce a smallness rejection.
        val component = depth.components.singleOrNull()
        val farZ = max(z, component?.axialP80M ?: z) + max(z * .10, 3 * (component?.axialMadM ?: 0.0))
        val width = mask.width * z / intrinsics.fx()
        val height = mask.height * z / intrinsics.fy()
        // Two pixels and a depth margin are conservative heuristics, not a calibrated confidence interval.
        val upperWidth = (mask.width + 2.0) * farZ / intrinsics.fx()
        val upperHeight = (mask.height + 2.0) * farZ / intrinsics.fy()
        if (listOf(width, height, upperWidth, upperHeight).any { !it.isFinite() || it <= 0 || it > Float.MAX_VALUE }) return null
        val reason = when {
            !fullyCovered -> "partial_calibrated_coverage"
            mask.left <= 1 || mask.top <= 1 || mask.left + mask.width >= mask.originalWidth - 1 ||
                mask.top + mask.height >= mask.originalHeight - 1 -> "image_edge_may_clip_object"
            component == null || depth.componentDepthConflict -> "multiple_or_conflicting_components"
            depth.source != "ARCORE_RAW_DEPTH" -> "smoothed_depth_extent_is_diagnostic"
            !depth.newDepthInformation -> "reused_depth_extent_is_diagnostic"
            (component.rawConfidenceMedian ?: 0.0) < 200.0 ||
                depth.support.robustInlierMaskFraction() < .60 ||
                (component.axialIqrM() ?: Double.POSITIVE_INFINITY) > .10 -> "weak_depth_extent_support"
            else -> "supported_visible_mask_extent"
        }
        val swap = quarterTurns % 2 == 1
        return ObservedMaskExtent((if (swap) height else width).toFloat(), (if (swap) width else height).toFloat(),
            (if (swap) upperHeight else upperWidth).toFloat(), (if (swap) upperWidth else upperHeight).toFloat(),
            reason == "supported_visible_mask_extent", reason)
    }
}
