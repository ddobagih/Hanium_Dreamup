package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs

data class MotionContext(
    val motionQuality: Float = 1f,
    val trackingQuality: Float = 1f,
    val freshnessQuality: Float = 1f,
    val routeAlignmentQuality: Float = 1f,
    val egoForwardSpeedMps: Float? = null,
    val shakeScore: Float = 0f,
) {
    val safeMotionQuality: Float get() = minOf(motionQuality, routeAlignmentQuality).coerceIn(0.2f, 1f)
}

fun routeBearingAlignmentQuality(headingDeg: Float?, routeBearingDeg: Float?): Float {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return 1f
    val bearing = routeBearingDeg?.takeIf { it.isFinite() } ?: return 1f
    val delta = angularDifferenceDeg(heading, bearing)
    return when {
        delta <= 30f -> 1f
        delta <= 75f -> 0.75f
        delta <= 120f -> 0.55f
        else -> 0.35f
    }
}

private fun angularDifferenceDeg(a: Float, b: Float): Float {
    val normalized = ((a - b + 540f) % 360f) - 180f
    return abs(normalized)
}
