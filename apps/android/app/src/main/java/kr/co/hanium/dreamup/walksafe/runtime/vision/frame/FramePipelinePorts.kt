package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.feedback.delivery.FeedbackRejectionProducerScope
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Actual runtime/metric owners, queried at the original points; no copied readiness state. */
internal interface FrameRuntimePeer<L : Any, Q> {
    fun runtimePurpose(purpose: Q): Boolean
    fun startupConfirmed(): Boolean
    fun runtimeEpochOrNull(): WalkRuntimeEpoch?
    fun revokeMetricOutput()
    fun updateMetricOutput(capture: ScopedArFrameCapture<L, Q>, epoch: WalkRuntimeEpoch)
    fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun leaseIsCurrent(lease: L): Boolean
    fun metricOutputAllowsWork(arGeneration: Long): Boolean
    fun visualOutputAllowsWork(lease: L, frameTimestampNs: Long, tracking: Boolean): Boolean
    fun expectedCameraFps(lease: L): Int
    fun observeRuntimeFrame(expectedFps: Int, tracking: () -> Boolean)
    fun recordTrackingLoad(snapshot: DepthFrameSnapshot, frameTimestampNs: () -> Long,
        depthProcessingMs: Long, trackingStartedMs: Long, hasGray: Boolean, trackingBudgetNs: () -> Long)
    fun requestEarlyDetection(atElapsedRealtimeMs: Long)
}

/** Both methods synchronously freeze this source before the existing detector/Unknown lanes run. */
internal interface FrameDetectionSchedulingPeer<L : Any> {
    fun scheduleUnknown(input: FrameRecognitionInput?, frame: Frame, snapshot: DepthFrameSnapshot,
        capturedElapsedMs: Long, generation: Int, epoch: WalkRuntimeEpoch, lease: L)
    fun scheduleNamed(input: FrameRecognitionInput?, lease: L, frame: Frame, timestampMs: Long,
        wallClockMs: Long, capturedElapsedMs: Long, snapshot: DepthFrameSnapshot,
        generation: Int, epoch: WalkRuntimeEpoch, arGeneration: Long, gray: GrayTrackingFrame?)
}

internal interface FrameRouteEvidencePeer {
    fun projectionContext(frame: Frame, capturedElapsedMs: Long): TactileProjectionContext
    fun motionContext(capturedElapsedMs: Long): MotionContext
    fun navigationActive(): Boolean
    fun tmapOnRoute(): Boolean
}

/** The actual issued source and receipt remain with the existing tactile result owner. */
internal data class FrameTactileSource<S>(val issued: S, val owner: TactileDotReferenceDeliveryOwner,
    val geometryVersion: Long, val routeId: String?, val routeProgress: TactileActiveRouteProgress?)

internal interface FrameTactileResultPeer<L : Any, S, R> {
    fun coordinator(): AndroidTactileFrameCoordinator
    fun prepareFrozen(snapshot: DetectionSnapshot, wallClockMs: Long, frameTimestampMs: Long,
        decisionElapsedMs: Long, processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>): PreparedTactileFrameDispatch
    fun recordFrozen(snapshot: DetectionSnapshot, outputs: List<TrackedObjectDepth>)
    fun currentSource(epoch: WalkRuntimeEpoch, generation: Int, arGeneration: Long,
        frameTimestampNs: () -> Long, capturedElapsedMs: Long, cameraTimestampNs: Long?, geometryVersion: () -> Long): FrameTactileSource<S>
    fun feedbackLifecycleGeneration(): Int
    fun consume(source: S, frame: TactileSnapshotFrameResult, admissionIsCurrent: () -> Boolean,
        accepted: (TactileSnapshotFrameResult, R) -> Unit): Boolean
    fun publishReference(frame: TactileSnapshotFrameResult, owner: TactileDotReferenceDeliveryOwner, receipt: R): Boolean
    fun resultIsCurrent(receipt: R): Boolean
    fun consumeCurrent(receipt: R, block: () -> Unit)
    fun crosswalkReference(owner: TactileDotReferenceDeliveryOwner, lease: L, current: () -> Boolean)
    fun dotReference(owner: TactileDotReferenceDeliveryOwner, lease: L, current: () -> Boolean)
    fun dispatchFeedback(receipt: R, frame: TactileSnapshotFrameResult, stale: Boolean,
        deviceGateAllowsAlerts: Boolean, nowMs: Long, actuator: TactileFrameFeedbackActuator,
        independentOutputs: List<UnknownObjectFeedbackBatch>, warningContext: WarningRegionContext?)
}

internal interface FrameRiskOutputPeer<R> {
    fun freshIndependentOutputs(nowElapsedMs: Long): List<UnknownObjectFeedbackBatch>
    fun deviceGate(bestOutput: TrackedObjectDepth?, staleReason: String?, arGeneration: Long,
        qualitativeOutput: TrackedObjectDepth? = null, qualitativeRuntimeReady: Boolean = false): DeviceGateState
    fun currentAlertsAllowed(): Boolean
    fun gateText(gate: DeviceGateState): String
    fun qualitativeAlertsAllowed(nowElapsedMs: Long): Boolean
    fun emitFeedback(dispatch: TactileFrameFeedbackDispatch, scope: FeedbackRejectionProducerScope,
        startIsCurrent: () -> Boolean, producerIsCurrent: () -> Boolean)
    fun rememberFrame(frame: TactileSnapshotFrameResult, stale: Boolean, qualitative: Boolean,
        producerIsCurrent: () -> Boolean, alertsAllowed: (Long) -> Boolean, receipt: R,
        warningContext: WarningRegionContext?, actuator: TactileFrameFeedbackActuator? = null,
        delivery: (TactileFrameFeedbackDispatch, () -> Boolean) -> Unit)
}

internal data class FrameCaptureAudit(val timestampMs: Long, val wallClockMs: Long,
    val snapshot: DetectionSnapshot, val overlay: FrameOverlayDiagnostics,
    val depthDetections: List<DetectionCandidate>, val depth: DepthFrameSnapshot,
    val bestOutput: TrackedObjectDepth?, val transformPath: String, val fallbackReason: String?)

internal interface FrameRecordingPeer {
    fun acquisitionFailures(provider: ArCoreFrameProvider, capturedElapsedMs: Long)
    fun buildCaptureEntry(audit: FrameCaptureAudit): MetadataCaptureLogEntry
    fun appendMetadata(entry: MetadataCaptureLogEntry)
    fun enqueueMetadataIfOriginalUploadAllowed(entry: MetadataCaptureLogEntry)
    fun freshLocation(): TrustedLocation?
    fun frameProduced()
    fun appendTelemetry(entry: MetadataCaptureLogEntry, alertsAllowed: Boolean, reportsAllowed: Boolean,
        freshLocation: TrustedLocation?, locationAgeMs: Long?)
}

internal data class FrameReportPublication(val generation: Int, val arGeneration: Long,
    val automaticOutput: TrackedObjectDepth?, val automaticGate: DeviceGateState,
    val explicitOutput: TrackedObjectDepth?, val explicitGate: DeviceGateState,
    val damageWithoutDepth: Boolean, val conflictingDamage: Boolean,
    val sourceSnapshot: DetectionSnapshot, val wallClockMs: Long, val epoch: WalkRuntimeEpoch,
    val frozenCapture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>?)

internal fun interface FrameReportPublicationPeer {
    fun publish(publication: FrameReportPublication): Boolean
}
