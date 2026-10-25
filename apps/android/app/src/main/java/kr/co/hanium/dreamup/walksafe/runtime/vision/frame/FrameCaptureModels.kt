package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureLumaInput as UnknownLumaInput

internal data class FrameRecognitionCapture<T>(
        val input: T?,
        val depthSnapshot: DepthFrameSnapshot,
        val timestampEvidence: CameraImageTimestampEvidence? = null,
    )

/** Frozen before recognition; never substituted with a later request-time location. */
internal data class ReportFrameCaptureFacts(
    val capturedAtElapsedRealtimeMs: Long,
    val trustedLocation: kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation?,
    val reporterActorId: String,
    val accountGeneration: Long?,
    val walkEpoch: kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch,
)

internal data class FrameRecognitionInput(
        val cameraFrame: OwnedYuvFrame,
        val cameraTimestampNs: Long?,
        val gray: GrayTrackingFrame?,
        val qualityObservation: CameraFrameQualityObservation,
        val imageQuarterTurns: Int,
        val geometryVersion: Long,
        val unknownLuma: UnknownLumaInput? = null,
        val timestampEvidence: CameraImageTimestampEvidence? = null,
        val reportCaptureFacts: ReportFrameCaptureFacts? = null,
    )

internal data class FrameOverlaySelection(
        val detections: List<DetectionCandidate>,
        val mappingSnapshot: DetectionSnapshot,
        val debugState: FrameOverlayDiagnostics,
    )

internal data class FrameOverlayDiagnostics(
        val selection: String,
        val detectionCount: Int,
        val tactileDetectionCount: Int,
        val boxCount: Int = 0,
        val tactileBoxCount: Int = 0,
        val heldTactileBoxCount: Int = 0,
        val smoothedTactileBoxCount: Int = 0,
        val holdApplied: Boolean,
        val snapshotPartial: Boolean,
        val sourceAgeMs: Long?,
        val frameDeltaMs: Long?,
        val staleReason: String?,
    )
