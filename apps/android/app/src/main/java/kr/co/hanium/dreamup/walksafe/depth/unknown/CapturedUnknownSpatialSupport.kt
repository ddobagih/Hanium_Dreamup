package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** One actual depth cell, mapped through the captured calibration into RGB and the pose anchor. */
internal data class CalibratedUnknownPoint(
    val imageX: Int,
    val imageY: Int,
    val pointInAnchor: Vec3,
    val confidence: Float,
) {
    fun spatial() = UnknownSpatialSample(pointInAnchor, confidence)
}

/** Bounded pose history. A compass course is never treated as an anchor-space direction. */
internal class UnknownTravelDirection {
    private val history = ArrayDeque<CameraPoseEvidence>()
    fun reset() = history.clear()

    fun update(pose: CameraPoseEvidence?): Vec3? {
        if (pose == null || pose.gravityUpInAnchor == null) { reset(); return null }
        val previous = history.lastOrNull()
        if (previous != null && (previous.referenceId != pose.referenceId ||
                pose.timestampMs <= previous.timestampMs || pose.timestampMs - previous.timestampMs > 1_500L)) reset()
        history.addLast(pose)
        while (history.size > 8 || history.first().timestampMs < pose.timestampMs - 1_500L) history.removeFirst()
        val first = history.first()
        val elapsed = pose.timestampMs - first.timestampMs
        if (history.size < 3 || elapsed < 400L) return null
        val up = requireNotNull(pose.gravityUpInAnchor)
        if (!up.norm().isFinite() || abs(up.norm() - 1f) > .01f) return null
        fun horizontal(a: CameraPoseEvidence, b: CameraPoseEvidence): Vec3 {
            val delta = Vec3(b.positionX - a.positionX, b.positionY - a.positionY, b.positionZ - a.positionZ)
            return delta - up * delta.dot(up)
        }
        val displacements = history.toList().zipWithNext().map { (a, b) ->
            val distance = horizontal(a, b).norm()
            if (!distance.isFinite() || distance * 1_000f / (b.timestampMs - a.timestampMs) > 3.5f) return null
            distance
        }
        val net = horizontal(first, pose)
        val path = displacements.sum()
        if (path <= 0f || net.norm() / path < .80f) return null
        return (net * (1_000f / elapsed)).takeIf { it.norm() in .20f..3.0f }
    }
}

/** Reads only owned, current-capture arrays. No Android image or mapper is retained. */
internal class CapturedUnknownSpatialSupport(
    private val capture: FrozenUnknownDepthCapture,
    private val travelVelocity: Vec3?,
) {
    private val pose = capture.motionContext.reliableCameraPoseEvidence()?.takeIf {
        it.timestampMs == capture.frameTimestampMs
    }
    private val projectors = mutableMapOf<String, Projector?>()
    private val pointCache = mutableMapOf<Pair<MaskDepthEstimator.Result, BinaryImageMask?>, List<CalibratedUnknownPoint>>()
    private val scene by lazy {
        if (!capture.snapshot.hasFreshMetricRawDepth) emptyList() else {
            val projector = projector("ARCORE_RAW_DEPTH") ?: return@lazy emptyList()
            val step = ceil(sqrt(projector.size / 4_096.0)).toInt().coerceAtLeast(1)
            buildList {
                for (y in 0 until projector.height step step) for (x in 0 until projector.width step step) {
                    projector.point(y * projector.width + x)?.takeIf { it.confidence >= .75f }?.let { add(it.spatial()) }
                }
            }
        }
    }

    fun points(result: MaskDepthEstimator.Result, mask: BinaryImageMask? = null): List<CalibratedUnknownPoint> =
        pointCache.getOrPut(result to mask) { readPoints(result, mask) }

    private fun readPoints(result: MaskDepthEstimator.Result, mask: BinaryImageMask?): List<CalibratedUnknownPoint> {
        val source = result.source ?: return emptyList()
        val projector = projector(source) ?: return emptyList()
        val indices = if (result.status == MaskDepthEstimator.Status.KNOWN) {
            result.components.flatMap { it.inlierDepthPixelIndices().toList() }
        } else {
            result.components.filter { it.status == MaskDepthEstimator.Status.KNOWN }
                .flatMap { it.inlierDepthPixelIndices().toList() } +
                result.nearerLayers.flatMap { it.inlierDepthPixelIndices().toList() }
        }
        val supported = indices.distinct().filter { index ->
            mask == null || projector.imagePoint(index)?.let { mask.contains(it.first, it.second) } == true
        }
        return boundedPoints(projector, supported)
    }

    private fun boundedPoints(projector: Projector, supported: List<Int>): List<CalibratedUnknownPoint> {
        // Keep spatial extrema as well as uniform samples. Uniform downsampling alone could drop
        // a tiny protrusion or a few points entering the walking corridor and falsely reject it.
        val axes = mutableListOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val camera = pose
        camera?.gravityUpInAnchor?.let { up ->
            axes += up
            val direction = travelVelocity ?: Vec3(camera.forwardX, camera.forwardY, camera.forwardZ)
            val forward = (direction - up * direction.dot(up)).normalized()
            axes += forward
            axes += Vec3(forward.y * up.z - forward.z * up.y,
                forward.z * up.x - forward.x * up.z, forward.x * up.y - forward.y * up.x)
        }
        val lows = arrayOfNulls<CalibratedUnknownPoint>(axes.size)
        val highs = arrayOfNulls<CalibratedUnknownPoint>(axes.size)
        val nearest = mutableListOf<CalibratedUnknownPoint>()
        val origin = camera?.let { Vec3(it.positionX, it.positionY, it.positionZ) } ?: return emptyList()
        val selected = mutableListOf<CalibratedUnknownPoint>()
        val step = ceil(supported.size / 256.0).toInt().coerceAtLeast(1)
        supported.forEachIndexed { index, cell ->
            val point = projector.point(cell) ?: return@forEachIndexed
            if (index % step == 0) selected += point
            axes.forEachIndexed { axisIndex, axis ->
                val dot = point.pointInAnchor.dot(axis)
                if (lows[axisIndex]?.let { dot < it.pointInAnchor.dot(axis) } != false) lows[axisIndex] = point
                if (highs[axisIndex]?.let { dot > it.pointInAnchor.dot(axis) } != false) highs[axisIndex] = point
            }
            if (nearest.size < 16 || (point.pointInAnchor - origin).norm() < (nearest.last().pointInAnchor - origin).norm()) {
                nearest += point
                nearest.sortBy { (it.pointInAnchor - origin).norm() }
                if (nearest.size > 16) nearest.removeAt(nearest.lastIndex)
            }
        }
        return (selected + lows.filterNotNull() + highs.filterNotNull() + nearest)
            .distinctBy { it.imageX to it.imageY }
    }

    fun evaluate(result: MaskDepthEstimator.Result, fullyCovered: Boolean,
                 mask: BinaryImageMask? = null, supportedRegion: Boolean = false): UnknownSpatialEvidence? {
        val camera = pose ?: return null
        val samples = points(result, mask)
        val context = UnknownSpatialContext(camera, camera.gravityUpInAnchor, capture.frameTimestampMs,
            result.newDepthInformation,
            fullyCovered && mask != null && (supportedRegion || result.support.robustInlierMaskFraction() >= .90),
            travelVelocity,
            depthGeometryCurrent = result.newDepthInformation ||
                (result.source == "ARCORE_RAW_DEPTH" && result.depthTimestampNs?.let {
                    capture.token.cpuImageTimestampNs - it in 0L..250_000_000L
                } == true))
        val measured = UnknownSpatialRelevance.evaluate(samples.map { it.spatial() }, context, scene)
        if (measured.disposition !in setOf(UnknownSpatialDisposition.FLAT_FLOOR,
                UnknownSpatialDisposition.OUTSIDE_CORRIDOR)) return measured
        // A robust range intentionally removes outliers. Those real high-confidence pixels may
        // still be a small protrusion or a separate surface, so they must constrain exclusion.
        val projector = result.source?.let(::projector) ?: return measured.copy(
            disposition = UnknownSpatialDisposition.UNCERTAIN, reason = "exclusion_depth_unavailable")
        val foreground = mask ?: return measured.copy(disposition = UnknownSpatialDisposition.UNCERTAIN,
            reason = "exclusion_mask_unavailable")
        val allSupported = (0 until projector.size).filter { index ->
            projector.point(index)?.let { it.confidence >= .75f && foreground.contains(it.imageX, it.imageY) } == true
        }
        val exclusion = UnknownSpatialRelevance.evaluate(
            (samples + boundedPoints(projector, allSupported)).distinctBy { it.imageX to it.imageY }.map { it.spatial() },
            context, scene)
        // Extra exclusion evidence must never substitute its distance for the robust metric range.
        return exclusion.copy(nearestReliableRangeM = measured.nearestReliableRangeM)
    }

    private fun projector(source: String): Projector? = projectors.getOrPut(source) {
        val camera = pose ?: return@getOrPut null
        val full = source == "ARCORE_FULL_DEPTH"
        if (source != "ARCORE_RAW_DEPTH" && !full) return@getOrPut null
        if (full && !capture.snapshot.hasFreshFullDepth || !full && !capture.snapshot.hasMetricRawDepth) return@getOrPut null
        val calibration = (if (full) capture.fullDepth?.calibration else capture.calibration) ?: return@getOrPut null
        val depth = (if (full) capture.snapshot.fullDepth else capture.snapshot.rawDepth) ?: return@getOrPut null
        val confidence = if (full) null else capture.snapshot.rawConfidence
        Projector.create(camera, calibration, depth, confidence, full)
    }

    private class Projector(
        private val pose: CameraPoseEvidence,
        private val calibration: MaskDepthEstimator.Calibration,
        private val depth: DepthImage16,
        private val confidence: ConfidenceImage8?,
        private val full: Boolean,
        private val inverse: DoubleArray,
    ) {
        val width get() = depth.width
        val height get() = depth.height
        val size get() = width * height

        fun imagePoint(index: Int): Pair<Int, Int>? = imageCoordinates(index)?.let { it.first.toInt() to it.second.toInt() }

        private fun imageCoordinates(index: Int): Pair<Double, Double>? {
            if (index !in 0 until size) return null
            val u = (index % width + .5) / width
            val v = (index / width + .5) / height
            val divisor = inverse[6] * u + inverse[7] * v + inverse[8]
            if (!divisor.isFinite() || abs(divisor) < 1e-12) return null
            val x = (inverse[0] * u + inverse[1] * v + inverse[2]) / divisor
            val y = (inverse[3] * u + inverse[4] * v + inverse[5]) / divisor
            if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0 ||
                x >= calibration.imageWidth || y >= calibration.imageHeight) return null
            return x to y
        }

        fun point(index: Int): CalibratedUnknownPoint? {
            val image = imageCoordinates(index) ?: return null
            val z = (depth.millimeters[index].toInt() and 0xffff) / 1_000f
            if (z !in .10f..15f) return null
            // Full has no per-pixel confidence. It can establish range, not the strong floor/side veto.
            val quality = if (full) .60f else ((confidence?.values?.get(index)?.toInt() ?: return null) and 0xff) / 255f
            if (quality < .55f) return null
            val point = pose.objectCenterInAnchor(Point2((image.first / calibration.imageWidth).toFloat(),
                (image.second / calibration.imageHeight).toFloat()), z) ?: return null
            return CalibratedUnknownPoint(image.first.toInt(), image.second.toInt(), point, quality)
        }

        companion object {
            fun create(pose: CameraPoseEvidence, calibration: MaskDepthEstimator.Calibration, depth: DepthImage16,
                       confidence: ConfidenceImage8?, full: Boolean): Projector? {
                val projection = pose.imageProjection ?: return null
                if (depth.width != calibration.depthWidth || depth.height != calibration.depthHeight ||
                    projection.imageWidth != calibration.imageWidth || projection.imageHeight != calibration.imageHeight ||
                    confidence?.let { it.width != depth.width || it.height != depth.height } == true) return null
                val h = calibration.imageToDepthUv() ?: return null
                if (h.size != 9 || h.any { !it.isFinite() }) return null
                val (a, b, c) = h.take(3)
                val d = h[3]; val e = h[4]; val f = h[5]; val g = h[6]; val i = h[7]; val j = h[8]
                val determinant = a * (e * j - f * i) - b * (d * j - f * g) + c * (d * i - e * g)
                if (!determinant.isFinite() || abs(determinant) < 1e-15) return null
                val inverse = doubleArrayOf(e*j-f*i, c*i-b*j, b*f-c*e, f*g-d*j, a*j-c*g,
                    c*d-a*f, d*i-e*g, b*g-a*i, a*e-b*d).map { it / determinant }.toDoubleArray()
                return Projector(pose, calibration, depth, confidence, full, inverse)
            }
        }
    }
}
