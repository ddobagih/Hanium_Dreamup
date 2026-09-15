package kr.co.hanium.dreamup.walksafe.depth

import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.abs

internal const val MAX_CAPTURED_HORIZONTAL_PLANES = 16
internal const val MAX_INSPECTED_HORIZONTAL_PLANES = 64
internal const val MAX_CAPTURED_PLANE_VERTICES = 128

/** Optional spatial metadata must never invalidate an otherwise valid camera pose. */
internal fun <T> CameraPoseEvidence.withCapturedSpatialMetadata(
    readGravityUpInAnchor: () -> Vec3,
    readPlanes: () -> Collection<T>,
    readPolygonInAnchor: (T) -> List<Vec3>?,
): CameraPoseEvidence {
    if (referenceId <= 0L || timestampMs <= 0L) {
        return copy(gravityUpInAnchor = null, horizontalPlaneCandidates = emptyList())
    }
    val gravity = try {
        readGravityUpInAnchor().takeIf { it.isFinitePoint() && abs(it.norm() - 1f) <= 0.001f }
    } catch (_: Exception) {
        null
    }
    val planes = mutableListOf<CapturedHorizontalPlane>()
    try {
        val iterator = readPlanes().iterator()
        var inspected = 0
        while (inspected < MAX_INSPECTED_HORIZONTAL_PLANES &&
            planes.size < MAX_CAPTURED_HORIZONTAL_PLANES && iterator.hasNext()
        ) {
            val candidate = iterator.next()
            inspected += 1
            try {
                val polygon = readPolygonInAnchor(candidate) ?: continue
                if (polygon.size !in 3..MAX_CAPTURED_PLANE_VERTICES || polygon.any { !it.isFinitePoint() }) continue
                planes += CapturedHorizontalPlane(
                    referenceId = referenceId,
                    captureTimestampMs = timestampMs,
                    polygonInAnchor = Collections.unmodifiableList(polygon.map { it.copy() }),
                )
            } catch (_: Exception) {
                // One unavailable trackable must not erase the other same-frame candidates.
            }
        }
    } catch (_: Exception) {
        // Collection acquisition/iteration can fail independently of pose tracking.
    }
    return copy(
        gravityUpInAnchor = gravity,
        horizontalPlaneCandidates = Collections.unmodifiableList(planes),
    )
}

/** Copies complete local X/Z boundaries; an oversized polygon is omitted, never truncated. */
internal fun captureHorizontalPlanePolygon(
    coordinates: FloatBuffer,
    transformLocalPoint: (FloatArray) -> FloatArray,
): List<Vec3>? {
    val vertices = coordinates.asReadOnlyBuffer()
    val coordinateCount = vertices.remaining()
    if (coordinateCount % 2 != 0 || coordinateCount / 2 !in 3..MAX_CAPTURED_PLANE_VERTICES) return null
    return List(coordinateCount / 2) {
        val x = vertices.get()
        val z = vertices.get()
        if (!x.isFinite() || !z.isFinite()) return null
        val point = transformLocalPoint(floatArrayOf(x, 0f, z))
        if (point.size != 3) return null
        Vec3(point[0], point[1], point[2]).takeIf { it.isFinitePoint() } ?: return null
    }
}

private fun Vec3.isFinitePoint(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
