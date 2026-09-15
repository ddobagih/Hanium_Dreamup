package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kotlin.math.abs
import kotlin.math.floor

/** A real depth sample in the captured pose reference; no filled box or component median. */
data class UnknownSpatialSample(val pointInAnchor: Vec3, val confidence: Float = 1f)

data class UnknownSpatialContext(
    val pose: CameraPoseEvidence,
    val gravityUpInAnchor: Vec3?,
    val frameTimestampMs: Long,
    val independentDepth: Boolean,
    val fullyCovered: Boolean,
    val travelVelocityInAnchorMps: Vec3? = null,
    val groundPlaneCandidates: List<CapturedHorizontalPlane> = pose.horizontalPlaneCandidates,
    /** A validated current reprojection can describe geometry without adding an independent depth observation. */
    val depthGeometryCurrent: Boolean = independentDepth,
)

enum class UnknownSpatialDisposition { UNCERTAIN, CORRIDOR_OBSTACLE, OUTSIDE_CORRIDOR, FLAT_FLOOR }

data class UnknownSpatialEvidence(
    val disposition: UnknownSpatialDisposition,
    /** Nearest coherent measured surface range from the camera, not axial depth or plane distance. */
    val nearestReliableRangeM: Float?,
    val supportConfidence: Float,
    val reason: String,
    /** Optional calibrated radial upper bound for an already established depth prediction. */
    val predictionUpperRangeM: Float? = null,
)

/** Conservative geometric selection. An uncertain plane or unsupported side region stays uncertain. */
object UnknownSpatialRelevance {
    private const val CORRIDOR_HALF_WIDTH_M = .60f
    private const val LATERAL_MARGIN_M = .15f
    private const val FLOOR_RESIDUAL_M = .012f

    fun evaluate(
        samples: List<UnknownSpatialSample>,
        context: UnknownSpatialContext,
        sceneSamples: List<UnknownSpatialSample> = emptyList(),
    ): UnknownSpatialEvidence {
        fun uncertain(reason: String, range: Float? = null, confidence: Float = 0f) =
            UnknownSpatialEvidence(UnknownSpatialDisposition.UNCERTAIN, range, confidence, reason)
        val pose = context.pose
        if (pose.referenceId < 0L || pose.timestampMs != context.frameTimestampMs ||
            !finite(Vec3(pose.positionX, pose.positionY, pose.positionZ)) ||
            pose.objectCenterInAnchor(Point2(.5f, .5f), 1f) == null) return uncertain("invalid_or_stale_spatial_pose")
        if (!context.depthGeometryCurrent) return uncertain("spatial_depth_geometry_unavailable")
        val origin = Vec3(pose.positionX, pose.positionY, pose.positionZ)
        val valid = samples.filter { finite(it.pointInAnchor) && it.confidence.isFinite() && it.confidence in .55f..1f &&
            (it.pointInAnchor - origin).norm() in .1f..20f }
        if (valid.size < 3) return uncertain("insufficient_measured_spatial_support")
        val confidence = valid.map { it.confidence }.sorted()[valid.size / 2]
        val ranges = valid.map { (it.pointInAnchor - origin).norm() }.sorted()
        // Three agreeing samples are required so one isolated near point cannot admit a far mask.
        val range = ranges.indices.firstOrNull { it + 2 < ranges.size && ranges[it + 2] - ranges[it] <= .08f }
            ?.let { ranges[it] } ?: return uncertain("incoherent_spatial_range")
        val up = context.gravityUpInAnchor?.takeIf { finite(it) && abs(it.norm() - 1f) <= .01f }
            ?.normalized() ?: return uncertain("gravity_unavailable_spatial_uncertain", range, confidence)
        val cameraForward = Vec3(pose.forwardX, pose.forwardY, pose.forwardZ)
        val travel = context.travelVelocityInAnchorMps?.takeIf { finite(it) && it.norm() in .15f..3.5f }
            ?.let { it - up * it.dot(up) }?.takeIf { it.norm() >= .15f }
        val forward = (travel ?: (cameraForward - up * cameraForward.dot(up)))
            .takeIf { it.norm() >= .25f }?.normalized()
            ?: return uncertain("camera_vertical_travel_direction_unknown", range, confidence)
        val right = cross(forward, up).normalized()
        val direction = if (travel == null) "camera_forward_approximation" else "measured_travel_direction"
        val coordinates = valid.map { sample ->
            val delta = sample.pointInAnchor - origin
            Coordinates(sample, delta.dot(right), delta.dot(forward), delta.dot(up))
        }
        // Spatial rejection requires extensive reliable support, never a few pixels on one side.
        val strong = context.fullyCovered && valid.size >= 8 && confidence >= .75f &&
            valid.size >= samples.size * .90f
        if (strong && coordinates.all { it.forward > 0f && abs(it.lateral) > CORRIDOR_HALF_WIDTH_M + LATERAL_MARGIN_M } &&
            (coordinates.all { it.lateral > 0f } || coordinates.all { it.lateral < 0f })) {
            return UnknownSpatialEvidence(UnknownSpatialDisposition.OUTSIDE_CORRIDOR, range, confidence,
                "measured_outside_walking_corridor_$direction")
        }
        if (strong && isSupportedFloor(coordinates, sceneSamples, context, origin, up, right, forward)) {
            return UnknownSpatialEvidence(UnknownSpatialDisposition.FLAT_FLOOR, range, confidence,
                "tracked_below_camera_plane_and_connected_depth_floor")
        }
        if (coordinates.any { it.forward > 0f && abs(it.lateral) <= CORRIDOR_HALF_WIDTH_M + LATERAL_MARGIN_M }) {
            return UnknownSpatialEvidence(UnknownSpatialDisposition.CORRIDOR_OBSTACLE, range, confidence,
                "measured_corridor_surface_$direction")
        }
        return uncertain("insufficient_evidence_for_spatial_exclusion_$direction", range, confidence)
    }

    private data class Coordinates(val sample: UnknownSpatialSample, val lateral: Float, val forward: Float, val height: Float)

    private fun isSupportedFloor(
        mask: List<Coordinates>, scene: List<UnknownSpatialSample>, context: UnknownSpatialContext,
        origin: Vec3, up: Vec3, right: Vec3, forward: Vec3,
    ): Boolean {
        if (scene.size < 24) return false
        for (plane in context.groundPlaneCandidates) {
            if (plane.referenceId != context.pose.referenceId || plane.captureTimestampMs != context.frameTimestampMs) continue
            val polygon = plane.polygonInAnchor
            if (polygon.size < 3 || polygon.any { !finite(it) }) continue
            val heights = polygon.map { (it - origin).dot(up) }
            val height = heights.average().toFloat()
            // A tabletop is not floor merely because it faces up. The tracked bounded plane must
            // extend under the camera at plausible standing camera height and match scene depth.
            if (-height !in 1.1f..2.2f || heights.any { abs(it - height) > .015f }) continue
            val bounds = polygon.map { (it - origin).let { d -> d.dot(right) to d.dot(forward) } }
            if (!insidePolygon(0f, 0f, bounds) || bounds.maxOf { it.first } - bounds.minOf { it.first } < .9f ||
                bounds.maxOf { it.second } - bounds.minOf { it.second } < 1.2f) continue
            // The whole measured mask must agree, so even a 2 cm supported protrusion is retained.
            if (mask.any { abs(it.height - height) > FLOOR_RESIDUAL_M ||
                    !insidePolygon(it.lateral, it.forward, bounds) }) continue
            val support = scene.filter { finite(it.pointInAnchor) && it.confidence.isFinite() && it.confidence in .75f..1f }
                .map { sample -> (sample.pointInAnchor - origin).let { d ->
                    Coordinates(sample, d.dot(right), d.dot(forward), d.dot(up)) } }
                .filter { abs(it.height - height) <= FLOOR_RESIDUAL_M && it.forward in .1f..3f &&
                    abs(it.lateral) <= 1.2f && insidePolygon(it.lateral, it.forward, bounds) }
            if (support.size < 24) continue
            // A disconnected far horizontal shelf does not establish traversable ground near feet.
            val cells = support.groupBy { floor(it.lateral / .20f).toInt() to floor(it.forward / .20f).toInt() }
            val remaining = cells.keys.toMutableSet()
            while (remaining.isNotEmpty()) {
                val queue = ArrayDeque<Pair<Int, Int>>()
                val seed = remaining.first(); remaining.remove(seed); queue.add(seed)
                val component = mutableListOf<Coordinates>()
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst(); component.addAll(cells.getValue(cell))
                    // Cells accelerate lookup only. Floating point rounding at a cell boundary
                    // must not break genuinely adjacent depth samples or bridge a physical gap.
                    for (dx in -2..2) for (dy in -2..2) {
                        val neighbor = cell.first + dx to cell.second + dy
                        if (neighbor !in remaining) continue
                        val connected = cells.getValue(cell).any { a -> cells.getValue(neighbor).any { b ->
                            val lateral = a.lateral - b.lateral; val ahead = a.forward - b.forward
                            lateral * lateral + ahead * ahead <= .25f * .25f
                        } }
                        if (connected && remaining.remove(neighbor)) queue.add(neighbor)
                    }
                }
                if (component.size >= 24 && component.minOf { it.forward } <= .70f &&
                    component.maxOf { it.forward } >= 1.5f &&
                    component.maxOf { it.forward } - component.minOf { it.forward } >= 1f &&
                    component.minOf { it.lateral } <= -.4f && component.maxOf { it.lateral } >= .4f &&
                    mask.all { m -> component.any { s -> abs(m.lateral - s.lateral) <= .25f &&
                        abs(m.forward - s.forward) <= .25f } }) return true
            }
        }
        return false
    }

    private fun insidePolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (point in polygon) {
            val dx = point.first - previous.first; val dy = point.second - previous.second
            val cross = (x - previous.first) * dy - (y - previous.second) * dx
            if (abs(cross) <= .00001f && x >= minOf(previous.first, point.first) && x <= maxOf(previous.first, point.first) &&
                y >= minOf(previous.second, point.second) && y <= maxOf(previous.second, point.second)) return true
            if ((point.second > y) != (previous.second > y) &&
                x < (previous.first - point.first) * (y - point.second) / (previous.second - point.second) + point.first) inside = !inside
            previous = point
        }
        return inside
    }

    private fun finite(point: Vec3) = point.x.isFinite() && point.y.isFinite() && point.z.isFinite()
    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}
