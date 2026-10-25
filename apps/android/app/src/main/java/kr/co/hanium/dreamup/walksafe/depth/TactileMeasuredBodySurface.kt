package kr.co.hanium.dreamup.walksafe.depth

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken

/** Original mask acquisition and original body lease, with no service-epoch/walk-epoch conversion. */
class TactileMeasuredBodyBinding private constructor(
    internal val semanticCapture: TactileSemanticCapture,
    internal val producer: MeasuredBodySpace,
    internal val bodyCapture: MeasuredBodySpace.Capture,
    internal val observationError: BodyDepthObservationError,
) {
    companion object {
        /** Main must first admit its real runtime/receipt/lease. This factory does not replace it. */
        fun freeze(originalCapture: TactileSemanticCapture, originalGeometryVersion: Long,
            producer: MeasuredBodySpace, bodyCapture: MeasuredBodySpace.Capture?,
            currentOriginalBodyKey: MeasuredBodyCaptureKey?, error: BodyDepthObservationError?): TactileMeasuredBodyBinding? {
            val body = bodyCapture ?: return null
            val key = currentOriginalBodyKey ?: return null
            val token = originalCapture.token
            val observedError = error ?: return null
            if (body.registration.producer !== producer || body.key != key ||
                originalGeometryVersion != key.geometryVersion || token.source != FastSamFrameToken.Source.LIVE_CAMERA ||
                key.frameTimestampNs != token.cameraTimestampNs || key.cameraImageTimestampNs != token.cpuImageTimestampNs ||
                originalCapture.imageReceipt.cameraImageTimestampNs != token.cpuImageTimestampNs ||
                key.capturedAtElapsedNs != token.capturedElapsedNs || key.validUntilElapsedNs != originalCapture.originalDeadlineElapsedNs ||
                body.mapper.frameId != token.frameId ||
                body.projection.imageWidth != token.width || body.projection.imageHeight != token.height ||
                observedError.metricPositionErrorM?.let { !it.isFinite() || it < 0.0 } != false ||
                observedError.axialQuantizationErrorBoundM?.let { !it.isFinite() || it < 0.0 } != false ||
                observedError.basis.isNullOrBlank()) return null
            return TactileMeasuredBodyBinding(originalCapture, producer, body, observedError.copy())
        }
    }

    internal fun matches(surface: TactileMaskSurface, frozen: FrozenUnknownDepthCapture,
        currentKey: MeasuredBodyCaptureKey?): Boolean {
        val key = bodyCapture.key
        if (surface.candidate.capture !== semanticCapture || currentKey != key ||
            !sameTactileCapture(semanticCapture.token, frozen.token) ||
            semanticCapture.imageQuarterTurns != frozen.imageQuarterTurns ||
            surface.pose != frozen.snapshot.cameraPoseEvidence || surface.pose.referenceId != key.poseReferenceId ||
            surface.pose != bodyCapture.pose || bodyCapture.semantic !== frozen.snapshot.semanticFrame ||
            bodyCapture.mapper.frameId != semanticCapture.token.frameId ||
            surface.pose.timestampMs != key.frameTimestampNs / 1_000_000L ||
            surface.pose.imageProjection != bodyCapture.projection ||
            bodyCapture.dimensions(surface.source) != (surface.depthWidth to surface.depthHeight) ||
            surface.depthMatchesCameraImage != (surface.depthTimestampNs == key.cameraImageTimestampNs)) return false
        val timestamp = when (surface.source) {
            DepthSource.ARCORE_RAW_DEPTH -> bodyCapture.rawTimestampNs
            DepthSource.ARCORE_FULL_DEPTH -> bodyCapture.fullTimestampNs
            else -> null
        }
        if (timestamp != surface.depthTimestampNs) return false
        val calibration = (if (surface.source == DepthSource.ARCORE_FULL_DEPTH) frozen.fullDepth?.calibration
            else frozen.calibration) ?: return false
        val h = bodyCapture.mapper.imagePixelsToDepthUvMatrix(semanticCapture.token.width, semanticCapture.token.height)
            ?: return false
        return calibration.imageWidth == semanticCapture.token.width && calibration.imageHeight == semanticCapture.token.height &&
            calibration.depthWidth == surface.depthWidth && calibration.depthHeight == surface.depthHeight &&
            h.contentEquals(calibration.imageToDepthUv())
    }
}

/** One original component/cell; null clearance preserves an unknown join without filling gaps. */
class TactileMeasuredBodyCell internal constructor(
    val sample: TactileSurfaceSample,
    val clearance: MeasuredBodyPointClearance?,
)

/** Observed own-mask geometry only, not floor/foot, whole collision, CONNECTED or walking authority. */
class TactileMeasuredBodySurface private constructor(
    val originalSurface: TactileMaskSurface,
    cells: List<TactileMeasuredBodyCell>,
) {
    val cells: List<TactileMeasuredBodyCell> = Collections.unmodifiableList(ArrayList(cells))

    companion object {
        internal fun join(surface: TactileMaskSurface, frozen: FrozenUnknownDepthCapture,
            binding: TactileMeasuredBodyBinding?, currentKey: MeasuredBodyCaptureKey?, nowElapsedNs: Long): TactileMeasuredBodySurface {
            val matched = binding?.takeIf { it.matches(surface, frozen, currentKey) }
            val width = surface.candidate.capture.token.width
            val height = surface.candidate.capture.token.height
            val cells = surface.samples.map { sample ->
                // Original sensor coordinates; no second rotation, bbox point, nearest-cell repair or new tolerance.
                val point = Point2((sample.imageX / width).toFloat(), (sample.imageY / height).toFloat())
                val clearance = matched?.producer?.pointClearance(matched.bodyCapture, currentKey, point,
                    surface.source, matched.observationError, nowElapsedNs)?.takeIf {
                    it.depthX == sample.depthX && it.depthY == sample.depthY &&
                        sample.depthCellIndex == it.depthY * surface.depthWidth + it.depthX &&
                        it.source == surface.source && it.depthTimestampNs == surface.depthTimestampNs &&
                        (-it.cameraPoint.z).toFloat() == sample.axialDepthM &&
                        (it.depthPixelConfidence?.div(255f)) == sample.pixelConfidence
                }
                TactileMeasuredBodyCell(sample, clearance)
            }
            return TactileMeasuredBodySurface(surface, cells)
        }
    }
}

/** The unchanged original projection/rejection is always available, including when body is absent. */
data class TactileMeasuredBodySurfaceResult(
    val projectionResult: TactileMaskSurfaceResult,
    val measuredSurface: TactileMeasuredBodySurface?,
)
