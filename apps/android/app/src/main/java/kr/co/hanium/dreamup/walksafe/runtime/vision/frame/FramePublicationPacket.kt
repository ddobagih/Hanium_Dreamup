package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.navigation.PreparedTactileFrameDispatch
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot

/** Frozen publication inputs. No SDK Frame, Image, plane buffer or frame-reading lambda is present. */
internal data class FramePublicationPacket<L : Any, R>(
    val scope: FramePresentationScope<L>,
    val provider: ArCoreFrameProvider,
    val timestampMs: Long,
    val wallClockMs: Long,
    val depthSnapshot: DepthFrameSnapshot,
    val detectionSnapshot: DetectionSnapshot,
    val grayFrame: GrayTrackingFrame?,
    val frame: TactileSnapshotFrameResult,
    val receipt: R,
    val dotOwner: TactileDotReferenceDeliveryOwner,
    val sourceIsCurrent: () -> Boolean,
    val frozenPrepared: PreparedTactileFrameDispatch?,
    val frozenOutputs: List<TrackedObjectDepth>,
    val guidanceOutputs: List<TrackedObjectDepth>,
    val bestOutput: TrackedObjectDepth?,
    val reportOutput: TrackedObjectDepth?,
    val automaticReportOutput: TrackedObjectDepth?,
    val independentBatches: List<UnknownObjectFeedbackBatch>,
    val feedbackGateOutput: TrackedObjectDepth?,
    val qualitativeGateOutput: TrackedObjectDepth?,
    val overlayDiagnostics: FrameOverlayDiagnostics,
    val overlayCount: Int,
    val depthInputDetections: List<DetectionCandidate>,
)
