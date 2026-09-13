package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kotlin.math.abs

/** Affine map in the unrotated CPU image's normalized coordinates. */
data class VisualAffineTransform(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun map(point: Point2): Point2 = Point2(a * point.x + b * point.y + tx, c * point.x + d * point.y + ty)
    val areaScale: Float get() = abs(a * d - b * c)
    val translationOnly: Boolean get() = a == 1f && b == 0f && c == 0f && d == 1f
}

internal data class VisualMotionFeature(
    val sourceX: Int,
    val sourceY: Int,
    val template: FloatArray = floatArrayOf(),
    val currentX: Float = sourceX.toFloat(),
    val currentY: Float = sourceY.toFloat(),
)

internal data class VisualMotionEstimate(
    val features: List<VisualMotionFeature>,
    val transformNorm: VisualAffineTransform,
    val quality: Float,
    val residual: Float,
    val failure: VisualTrackingFailure? = null,
)

/** The pure patch implementation remains an explicitly selected diagnostic backend. */
internal interface VisualMotionEstimator {
    fun initialize(): Boolean = true
    fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature>
    fun advance(
        source: GrayTrackingFrame,
        previous: GrayTrackingFrame,
        target: GrayTrackingFrame,
        detection: DetectionCandidate,
        features: List<VisualMotionFeature>,
        originalFeatureCount: Int,
        budget: TrackingWorkBudget,
    ): VisualMotionEstimate
}
