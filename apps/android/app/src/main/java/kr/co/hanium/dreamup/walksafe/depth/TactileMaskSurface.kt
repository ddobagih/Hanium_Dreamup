package kr.co.hanium.dreamup.walksafe.depth

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticMaskCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Actual captured cell with its own z and bounded local support, never a polygon vertex median. */
class TactileSurfaceContact internal constructor(
    val sample: TactileSurfaceSample,
    val sensorPointNorm: Point2,
    val depth: ContactDepthEstimate,
)

/** One measured depth cell. Derived centerlines must not be retagged as these samples. */
data class TactileSurfaceSample(
    val componentId: Int,
    val depthCellIndex: Int,
    val depthX: Int,
    val depthY: Int,
    val imageX: Double,
    val imageY: Double,
    val axialDepthM: Float,
    val pointInAnchor: Vec3,
    /** Full has no confidence plane; its existing geometric allowance is separate. */
    val pixelConfidence: Float?,
    val supportQuality: Float,
    /** Existing component statistic, an error allowance input rather than calibrated precision. */
    val componentIqrM: Double? = null,
)

/** Owned mask and original capture provenance, not scalar proximity or walking permission. */
class TactileMaskSurface internal constructor(
    val candidate: TactileSemanticMaskCandidate,
    val source: DepthSource,
    val depthTimestampNs: Long,
    val depthMatchesCameraImage: Boolean,
    val pose: CameraPoseEvidence,
    val samplingStatus: MaskDepthEstimator.Status,
    val samplingReason: String,
    val depthWidth: Int,
    val depthHeight: Int,
    /** Original DetectionFrameEvidence context, never a route/location read at completion. */
    val captureContext: TactileProjectionContext?,
    samples: List<TactileSurfaceSample>,
    val measuredContact: TactileSurfaceContact? = null,
    contactCandidates: List<TactileSurfaceContact> = listOfNotNull(measuredContact),
) {
    val samples: List<TactileSurfaceSample> = Collections.unmodifiableList(ArrayList(samples))
    /** Center-ranked actual lower-band cells, each passing the original local depth support. */
    val contactCandidates: List<TactileSurfaceContact> = Collections.unmodifiableList(ArrayList(contactCandidates))
    val sensorMask: BinaryImageMask get() = candidate.sensorMask
    val frameTimestampMs: Long get() = candidate.capture.token.cameraTimestampNs / 1_000_000L
}

enum class TactileSurfaceRejection {
    FOREIGN_CAPTURE, EXPIRED_CAPTURE, MASK_DIMENSIONS, DEPTH_CALIBRATION_UNAVAILABLE,
    POSE_UNAVAILABLE, MEASURED_SURFACE_UNAVAILABLE,
}

data class TactileMaskSurfaceResult(
    val candidate: TactileSemanticMaskCandidate,
    val surface: TactileMaskSurface?,
    val rejection: TactileSurfaceRejection?,
)

/**
 * Uses the existing mask sampler and captured-cell projection math. No Frame/Image is queried,
 * bbox is never filled, and no bounded spatial downsample substitutes for the actual support.
 * Main must first admit the opaque receipt/lease and both completed packets against its runtime.
 */
class TactileMaskSurfaceProjector {
    /** Actual projected cells joined to the optional measured model; missing joins stay UNKNOWN. */
    fun projectWithMeasuredBody(candidate: TactileSemanticMaskCandidate, capture: FrozenUnknownDepthCapture,
        decisionElapsedNs: Long, captureContext: TactileProjectionContext? = null,
        binding: TactileMeasuredBodyBinding?, currentOriginalBodyKey: MeasuredBodyCaptureKey?): TactileMeasuredBodySurfaceResult {
        val projected = project(candidate, capture, decisionElapsedNs, captureContext)
        return TactileMeasuredBodySurfaceResult(projected, projected.surface?.let {
            TactileMeasuredBodySurface.join(it, capture, binding, currentOriginalBodyKey, decisionElapsedNs)
        })
    }

    fun project(candidate: TactileSemanticMaskCandidate, capture: FrozenUnknownDepthCapture,
                decisionElapsedNs: Long, captureContext: TactileProjectionContext? = null): TactileMaskSurfaceResult {
        fun reject(reason: TactileSurfaceRejection) = TactileMaskSurfaceResult(candidate, null, reason)
        val key = candidate.capture
        val token = key.token
        if (!sameTactileCapture(token, capture.token) || key.imageQuarterTurns != capture.imageQuarterTurns ||
            key.imageReceipt.cameraImageTimestampNs != token.cpuImageTimestampNs ||
            captureContext?.let { it.captureFrameId != token.frameId ||
                it.nowElapsedRealtimeMs != token.capturedElapsedNs / 1_000_000L } == true) {
            return reject(TactileSurfaceRejection.FOREIGN_CAPTURE)
        }
        if (!tactileCaptureIsLive(candidate, decisionElapsedNs)) return reject(TactileSurfaceRejection.EXPIRED_CAPTURE)
        val mask = candidate.sensorMask
        if (mask.originalWidth != token.width || mask.originalHeight != token.height || mask.area <= 0) {
            return reject(TactileSurfaceRejection.MASK_DIMENSIONS)
        }
        val pose = capture.motionContext.reliableCameraPoseEvidence()?.takeIf {
            it.referenceId > 0L && it.timestampMs == capture.frameTimestampMs &&
                it.objectCenterInAnchor(Point2(.5f, .5f), 1f) != null
        } ?: return reject(TactileSurfaceRejection.POSE_UNAVAILABLE)
        val imageMask = object : MaskDepthEstimator.ImageMask {
            override fun imageWidth() = mask.originalWidth
            override fun imageHeight() = mask.originalHeight
            override fun minX() = mask.left
            override fun minY() = mask.top
            override fun maxXExclusive() = mask.left + mask.width
            override fun maxYExclusive() = mask.top + mask.height
            override fun contains(x: Int, y: Int) = mask.contains(x, y)
        }
        val detection = MaskDepthEstimator.Detection(candidate.namedDetectionIndex.toString(),
            candidate.semantic.className, capture.identity, imageMask)
        val raw = MaskDepthEstimator().associate(listOf(detection), capture.depth).single()
        // The existing unknown-mask fallback also refuses contradictory/ambiguous Raw layers.
        val full = if (raw.status != MaskDepthEstimator.Status.KNOWN && capture.fullDepth != null)
            MaskDepthEstimator().associate(listOf(detection), capture.fullDepth).single() else null
        val result = when {
            raw.status == MaskDepthEstimator.Status.KNOWN -> raw
            full?.status == MaskDepthEstimator.Status.KNOWN && !raw.componentDepthConflict &&
                raw.components.none { it.reason == "ambiguous_depth_layers" ||
                    it.axialDepthM?.let { z -> abs(z - full.axialDepthM!!) > .40 } == true } -> full
            full != null && !raw.componentDepthConflict && raw.nearerLayers.isEmpty() &&
                raw.components.none { it.status == MaskDepthEstimator.Status.KNOWN || it.reason == "ambiguous_depth_layers" } &&
                (full.nearerLayers.isNotEmpty() || full.components.any { it.status == MaskDepthEstimator.Status.KNOWN }) -> full
            else -> raw
        }
        val source = if (result.source == "ARCORE_FULL_DEPTH") DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH
        val fullSource = source == DepthSource.ARCORE_FULL_DEPTH
        val depth = (if (fullSource) capture.snapshot.fullDepth else capture.snapshot.rawDepth)
            ?: return reject(TactileSurfaceRejection.MEASURED_SURFACE_UNAVAILABLE)
        val calibration = (if (fullSource) capture.fullDepth?.calibration else capture.calibration)
            ?: return reject(TactileSurfaceRejection.DEPTH_CALIBRATION_UNAVAILABLE)
        val confidence = if (fullSource) null else capture.snapshot.rawConfidence
        val projector = CellProjector.create(pose, calibration, depth, confidence, fullSource)
            ?: return reject(TactileSurfaceRejection.DEPTH_CALIBRATION_UNAVAILABLE)
        val samples = result.components.filter { it.status == MaskDepthEstimator.Status.KNOWN }.flatMap { component ->
            component.inlierDepthPixelIndices().map { index ->
                projector.sample(component.componentId, index)?.takeIf {
                    mask.contains(floor(it.imageX).toInt(), floor(it.imageY).toInt())
                }?.copy(componentIqrM = component.axialIqrM())
            }.filterNotNull()
        }
        val stamp = result.depthTimestampNs ?: return reject(TactileSurfaceRejection.MEASURED_SURFACE_UNAVAILABLE)
        if (samples.isEmpty()) return reject(TactileSurfaceRejection.MEASURED_SURFACE_UNAVAILABLE)
        val contacts = if (result.status == MaskDepthEstimator.Status.KNOWN && stamp == token.cpuImageTimestampNs &&
            (fullSource || capture.snapshot.hasFreshMetricRawDepth)) {
            measuredContacts(mask, key.imageQuarterTurns, samples, projector)
        } else emptyList()
        return TactileMaskSurfaceResult(candidate, TactileMaskSurface(candidate, source, stamp,
            stamp == token.cpuImageTimestampNs, pose, result.status, result.reason, depth.width, depth.height,
            captureContext, samples, contacts.firstOrNull(), contacts), null)
    }

    private fun measuredContacts(mask: BinaryImageMask, q: Int, samples: List<TactileSurfaceSample>,
                                projector: CellProjector): List<TactileSurfaceContact> {
        if (q !in 0..3) return emptyList()
        fun upright(x: Int, y: Int) = sensorPointToUpright(Point2((x + .5f) / mask.originalWidth,
            (y + .5f) / mask.originalHeight), q)!!
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (y in mask.top until mask.top + mask.height) for (x in mask.left until mask.left + mask.width) {
            if (!mask.contains(x, y)) continue
            val py = upright(x, y).y
            minY = min(minY, py); maxY = max(maxY, py)
        }
        if (!minY.isFinite() || !maxY.isFinite()) return emptyList()
        val uprightHeight = if (q % 2 == 0) mask.originalHeight else mask.originalWidth
        val halfPixel = .5f / uprightHeight
        // The same lower 10% and clipped-bottom boundary as MaskPolygonExtractor, on actual bits.
        val bottom = maxY + halfPixel
        if (bottom >= .995f) return emptyList()
        val bandTop = bottom - (bottom - (minY - halfPixel)) * .10f
        val band = hashSetOf<Int>()
        val xs = sortedSetOf<Float>(); val ys = sortedSetOf<Float>()
        for (y in mask.top until mask.top + mask.height) for (x in mask.left until mask.left + mask.width) {
            if (!mask.contains(x, y)) continue
            val p = upright(x, y)
            if (p.y < bandTop) continue
            band.add(y * mask.originalWidth + x); xs.add(p.x); ys.add(p.y)
        }
        if (band.isEmpty()) return emptyList()
        // Use the existing surface raster's four-connectivity; do not join separate lower regions.
        val pending = band.toMutableSet()
        val queue = ArrayDeque<Int>()
        queue.add(pending.first()); pending.remove(queue.first())
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst(); val x = index % mask.originalWidth; val y = index / mask.originalWidth
            for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until mask.originalWidth || ny !in 0 until mask.originalHeight) continue
                val next = ny * mask.originalWidth + nx
                if (pending.remove(next)) queue.add(next)
            }
        }
        if (pending.isNotEmpty()) return emptyList()
        fun median(values: Set<Float>): Float {
            val sorted = values.toList()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        }
        // Medians of occupied columns/rows only rank actual cells; no new point is synthesized.
        val cx = median(xs); val cy = median(ys)
        fun uprightSample(s: TactileSurfaceSample) = sensorPointToUpright(Point2(
            (s.imageX / mask.originalWidth).toFloat(), (s.imageY / mask.originalHeight).toFloat()), q)!!
        return samples.filter {
            floor(it.imageY).toInt() * mask.originalWidth + floor(it.imageX).toInt() in band
        }.sortedWith(compareBy<TactileSurfaceSample> {
            val p = uprightSample(it); val dx = p.x - cx; val dy = p.y - cy
            dx * dx + dy * dy
        }.thenBy { uprightSample(it).x }.thenBy { uprightSample(it).y }.thenBy { it.depthCellIndex }).mapNotNull { sample ->
            val support = projector.contactSupport(mask, sample) ?: return@mapNotNull null
            TactileSurfaceContact(sample, Point2((sample.imageX / mask.originalWidth).toFloat(),
                (sample.imageY / mask.originalHeight).toFloat()), support)
        }
    }

    /** Same math/limits as CapturedUnknownSpatialSupport's private projector, without its 256-point bound. */
    private class CellProjector(val pose: CameraPoseEvidence, val calibration: MaskDepthEstimator.Calibration,
                               val depth: DepthImage16, val confidence: ConfidenceImage8?, val full: Boolean,
                               val inverse: DoubleArray) {
        fun sample(componentId: Int, index: Int): TactileSurfaceSample? {
            if (index !in 0 until depth.width * depth.height) return null
            val x = index % depth.width
            val y = index / depth.width
            val (ix, iy) = imagePoint(index) ?: return null
            val z = (depth.millimeters[index] and 0xffff) / 1_000f
            if (z !in .10f..15f) return null
            val pixelConfidence = if (full) null else ((confidence?.values?.get(index)?.toInt() ?: return null) and 255) / 255f
            val quality = pixelConfidence ?: .60f
            if (quality < .55f) return null
            val point = pose.objectCenterInAnchor(Point2((ix / calibration.imageWidth).toFloat(),
                (iy / calibration.imageHeight).toFloat()), z) ?: return null
            return TactileSurfaceSample(componentId, index, x, y, ix, iy, z, point, pixelConfidence, quality)
        }

        private fun imagePoint(index: Int): Pair<Double, Double>? {
            val x = index % depth.width
            val y = index / depth.width
            val u = (x + .5) / depth.width
            val v = (y + .5) / depth.height
            val denominator = inverse[6] * u + inverse[7] * v + inverse[8]
            if (!denominator.isFinite() || abs(denominator) < 1e-12) return null
            val ix = (inverse[0] * u + inverse[1] * v + inverse[2]) / denominator
            val iy = (inverse[3] * u + inverse[4] * v + inverse[5]) / denominator
            if (!ix.isFinite() || !iy.isFinite() || ix < 0.0 || iy < 0.0 ||
                ix >= calibration.imageWidth || iy >= calibration.imageHeight) return null
            return ix to iy
        }

        fun contactSupport(mask: BinaryImageMask, sample: TactileSurfaceSample): ContactDepthEstimate? {
            if (sample.axialDepthM !in .5f..5f) return null
            var eligible = 0
            val zs = mutableListOf<Float>()
            // DepthSampler's private contact limits: 5x5, min5, ratio .6, .5..5m, spread .35m,
            // with ObjectDepthEstimator's existing Raw confidence .35. No threshold is changed.
            for (y in max(0, sample.depthY - 2)..min(depth.height - 1, sample.depthY + 2)) {
                for (x in max(0, sample.depthX - 2)..min(depth.width - 1, sample.depthX + 2)) {
                    val index = y * depth.width + x
                    val (ix, iy) = imagePoint(index) ?: continue
                    if (!mask.contains(floor(ix).toInt(), floor(iy).toInt())) continue
                    eligible++ // Include zero, low-confidence and out-of-range cells in the denominator.
                    val z = depth.depthMetersAt(x, y)?.takeIf { it in .5f..5f } ?: continue
                    if (!full && (confidence?.confidenceAt(x, y) ?: 0f) < .35f) continue
                    zs.add(z)
                }
            }
            if (zs.size < 5 || eligible == 0 || zs.size.toFloat() / eligible < .6f) return null
            val spread = zs.maxOrNull()!! - zs.minOrNull()!!
            if (spread > .35f) return null
            // Neighbors support the selected cell. They do not replace its own measured z.
            return ContactDepthEstimate(sample.axialDepthM, spread, zs.size)
        }

        companion object {
            fun create(pose: CameraPoseEvidence, calibration: MaskDepthEstimator.Calibration, depth: DepthImage16,
                       confidence: ConfidenceImage8?, full: Boolean): CellProjector? {
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
                return CellProjector(pose, calibration, depth, confidence, full, inverse)
            }
        }
    }
}

internal fun sameTactileCapture(a: FastSamFrameToken, b: FastSamFrameToken): Boolean =
    a.source == b.source && a.sessionEpoch == b.sessionEpoch && a.frameId == b.frameId &&
        a.cameraTimestampNs == b.cameraTimestampNs && a.cpuImageTimestampNs == b.cpuImageTimestampNs &&
        a.capturedElapsedNs == b.capturedElapsedNs && a.geometryId == b.geometryId && a.width == b.width && a.height == b.height

internal fun tactileCaptureIsLive(candidate: TactileSemanticMaskCandidate, decisionNs: Long): Boolean {
    val capture = candidate.capture
    if (decisionNs < 0L) return false
    return capture.token.source == FastSamFrameToken.Source.LIVE_CAMERA &&
        decisionNs >= capture.token.capturedElapsedNs && capture.originalDeadlineElapsedNs >= capture.token.capturedElapsedNs &&
        decisionNs <= capture.originalDeadlineElapsedNs
}
