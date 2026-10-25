package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureProcessingLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCameraSourceLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureFrameInput
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureEvidence
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionInput

internal interface UnknownFrameAdmission<L> {
    fun obstacleDetectionEnabled(): Boolean
    fun recognitionAllowsVisualWork(cpuTimestampNs: Long?, frameTimestampNs: Long, snapshot: DepthFrameSnapshot, depthAllowsWork: () -> Boolean): Boolean
    fun visualDepthAllowsWork(lease: L, frameTimestampNs: Long, tracking: Boolean): Boolean
    fun arGeneration(lease: L): Long
    val metricOutputAllowed: Boolean
    val lastValidMetricFrameAtMs: Long
    fun modelGeneration(): Long
}

/** The evidence supplier is borrowed synchronously; the actual Unknown owner freezes before enqueue. */
internal class UnknownFrameCaptureBridge<L : Any>(
    private val depthAuthorityLock: Any,
    private val authority: UnknownFrameAdmission<L>,
    private val unknown: () -> kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureCoordinator,
    private val geometry: () -> kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ArFrameGeometryFreezer,
    private val motion: () -> FrameMotionEvidenceComposer,
) {
    fun scheduleUnknownDetectionIfDue(
        recognitionInput: FrameRecognitionInput?, frame: Frame, snapshot: DepthFrameSnapshot,
        capturedElapsedMs: Long, generation: Int, epoch: WalkRuntimeEpoch, arLease: L,
    ) {
        val input = recognitionInput ?: return
        val luma = input.unknownLuma ?: return
        if (!authority.obstacleDetectionEnabled() ||
            !authority.recognitionAllowsVisualWork(input.cameraTimestampNs, frame.timestamp, snapshot) {
                authority.visualDepthAllowsWork(
                    arLease, frame.timestamp, frame.camera.trackingState == TrackingState.TRACKING)
            }
        ) return
        if (luma.capture.token.frameId != frame.timestamp ||
            luma.capture.token.capturedElapsedNs != capturedElapsedMs * 1_000_000L) return
        val processingLease = synchronized(depthAuthorityLock) {
            UnknownCaptureProcessingLease(UnknownCameraSourceLease(arLease, authority.arGeneration(arLease)),
                frame.timestamp, capturedElapsedMs,
                if (authority.metricOutputAllowed) null else authority.lastValidMetricFrameAtMs + 600L)
        }
        unknown().scheduleUnknownDetectionIfDue(input.toUnknownCaptureFrameInput(), snapshot,
            frame.timestamp, capturedElapsedMs, generation, epoch, processingLease) {
            // Invoked synchronously before enqueue. No Frame or Image escapes this GL scope.
            UnknownCaptureEvidence(frame.timestamp, snapshot,
                geometry().createFrozenDepthMapper(frame, frame.timestamp, input.cameraFrame.width, input.cameraFrame.height,
                    snapshot, allowMissingDepth = true),
                motion().buildDepthMotionContext(capturedElapsedMs), input.imageQuarterTurns, processingLease,
                authority.modelGeneration())
        }
    }

    
}

internal fun FrameRecognitionInput.toUnknownCaptureFrameInput() = UnknownCaptureFrameInput(
        cameraFrame, cameraTimestampNs, qualityObservation, imageQuarterTurns, geometryVersion,
        unknownLuma, timestampEvidence)
