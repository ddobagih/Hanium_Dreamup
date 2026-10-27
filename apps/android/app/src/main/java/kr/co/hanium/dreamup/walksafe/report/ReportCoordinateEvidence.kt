package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileFrameComposition
import kotlin.math.abs

/** Capture-bound runtime mapping evidence, not a claim of completed field/device validation. */
internal class ReportCoordinateEvidence private constructor(
    private val output: TrackedObjectDepth,
    private val image: ByteArray,
    private val capturedAtMs: Long,
    private val geometryVersion: Long,
    val capturedAtElapsedRealtimeMs: Long?,
    val captureLocation: TrustedLocation?,
) {
    fun statusFor(output: TrackedObjectDepth, image: ByteArray?, capturedAtMs: Long, geometryVersion: Long): String =
        if (this.output === output && this.image === image && this.capturedAtMs == capturedAtMs &&
            this.geometryVersion == geometryVersion) "pass" else "fail"

    companion object {
        fun capture(
            snapshot: DetectionSnapshot,
            frame: TactileSnapshotFrameResult?,
            output: TrackedObjectDepth?,
            geometryVersion: Long,
        ): ReportCoordinateEvidence? {
            val evidence = frame?.matchedEvidence ?: return null
            val mapper = evidence.depthMapper ?: return null
            val image = snapshot.reportImage ?: return null
            val capturedAt = snapshot.capturedAtMs?.takeIf { it > 0L } ?: return null
            if (snapshot.partial || snapshot.completedAtMs == null || !frame.depthProcessingAttempted ||
                evidence !== snapshot.frameEvidence || evidence.geometryVersion != geometryVersion ||
                output == null || frame.guidance.outputs.none { it === output } ||
                output.frameId != evidence.frameId || output.timestampMs != evidence.frameTimestampMs ||
                !AndroidTactileFrameComposition.identity(snapshot.identity, evidence.tactileFrameIdentity())
                    .matchesCapture(evidence.tactileContext, requireDepthMapper = true)) return null
            if (mapper.imagePixelsToDepthUvMatrix(snapshot.imageWidth ?: 0, snapshot.imageHeight ?: 0) == null) return null
            val sourceIndex = output.sourceDetectionIndex ?: return null
            val source = frame.depthDetections.getOrNull(sourceIndex) ?: return null
            if (snapshot.detections.getOrNull(sourceIndex) !== source) return null
            val geometry = MaskPolygonExtractor().extract(source, evidence.imageQuarterTurns)
            if (geometry.className != output.className || geometry.bboxNorm != output.bboxNorm ||
                geometry.polygonNorm != output.polygonNorm) return null
            val mapped = mapper.imagePolygonToDepthPolygon(output.polygonNorm)
            if (mapped.size < 3 || mapped.size != output.polygonNorm.size) return null
            val twiceArea = mapped.indices.sumOf { i ->
                val a = mapped[i]
                val b = mapped[(i + 1) % mapped.size]
                a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
            if (!twiceArea.isFinite() || abs(twiceArea) <= 0.0) return null
            val sensorAvailable = when (output.source) {
                DepthSource.ARCORE_RAW_DEPTH -> evidence.depthSnapshot.hasMetricRawDepth
                DepthSource.ARCORE_FULL_DEPTH -> evidence.depthSnapshot.hasFreshFullDepth
                else -> false
            }
            if (!sensorAvailable) return null
            return ReportCoordinateEvidence(output, image, capturedAt, geometryVersion,
                snapshot.capturedAtElapsedRealtimeMs, snapshot.reportCaptureFacts?.takeIf { it.capturedAtElapsedRealtimeMs == snapshot.capturedAtElapsedRealtimeMs }?.trustedLocation)
        }
    }
}
