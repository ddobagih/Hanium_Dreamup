package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.TactileMaskSurfaceResult
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.RuntimeLoadCompletion
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointLumaCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureBundle
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Exact source lease, kept opaque to the consumer. No Frame/Image is admitted here. */
internal data class UnknownCameraSourceLease(val originalLease: Any, val generation: Long)

internal data class UnknownCaptureProcessingLease(
    val arLease: UnknownCameraSourceLease,
    val frameId: Long,
    val capturedAtElapsedRealtimeMs: Long,
    val gapDeadlineElapsedRealtimeMs: Long?,
)

internal data class UnknownCaptureLumaInput(
    val service: FastSamRuntimeService<UnknownRuntimeAttachment>,
    val capture: UnknownMaterialPointLumaCapture,
)

/** Shares the original copied pixels, sensor receipt and actual service; it never stamps a new fix. */
internal data class UnknownCaptureFrameInput(
    val cameraFrame: OwnedYuvFrame,
    val cameraTimestampNs: Long?,
    val qualityObservation: CameraFrameQualityObservation,
    val imageQuarterTurns: Int,
    val geometryVersion: Long,
    val unknownLuma: UnknownCaptureLumaInput?,
    val timestampEvidence: CameraImageTimestampEvidence?,
)

internal data class UnknownCaptureEvidence(
    val frameId: Long,
    val depthSnapshot: DepthFrameSnapshot,
    val depthMapper: FrozenImageToTextureCoordinateMapper?,
    val motionContext: MotionContext,
    val imageQuarterTurns: Int,
    val processingLease: UnknownCaptureProcessingLease,
    val modelGeneration: Long,
)

internal data class UnknownCaptureRuntimeScope(
    val epoch: WalkRuntimeEpoch?,
    val foreground: Boolean,
    val detectorClosing: Boolean,
    val detectorGeneration: Int,
    val arGeneration: Long,
    val geometryVersion: Long,
    val modelGeneration: Long,
    val feedbackLifecycleGeneration: Int,
    val stepLengthM: Float,
    val runtimeConfig: TwoModelRuntimeConfig?,
)

internal data class UnknownCaptureDepthAuthority(
    val latestFrameTimestampNs: Long,
    val visualFrameTracking: Boolean,
    val visualOutputAllowsWork: () -> Boolean,
)

internal data class UnknownCaptureExecutionObservation(
    val thermalThrottled: Boolean?,
)

/** The caller reuses its actual source gates and existing lock order, not supplied success facts. */
internal interface UnknownCaptureScopeReader {
    fun runtime(): UnknownCaptureRuntimeScope
    /** Holds the existing metric-state lock for the clock read, age checks and lazy source gate. */
    fun withDepthAuthority(lease: UnknownCaptureProcessingLease,
        check: (UnknownCaptureDepthAuthority) -> Boolean): Boolean
    fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun execution(): UnknownCaptureExecutionObservation
    fun metricOutputAllowsWork(expectedArGeneration: Long): Boolean
    fun cameraProfile(): ApprovedCameraFrameQualityProfile?
    fun powerSaveMode(): Boolean
}

internal interface UnknownCaptureClock {
    fun elapsedRealtimeMs(): Long
    fun elapsedRealtimeNanos(): Long
    fun cpuNanos(): Long
}

internal data class UnknownTactilePublication(
    val epoch: WalkRuntimeEpoch,
    val detectorGeneration: Int,
    val arGeneration: Long,
    val feedbackLifecycleGeneration: Int,
    val geometryVersion: Long,
    val geometryId: String,
    val processingLease: UnknownCaptureProcessingLease,
)

/** Domain output transactions. UI, route, report and speech owners remain outside this component. */
internal interface UnknownCaptureObserver {
    fun observeCamera(observation: CameraFrameQualityObservation)
    fun projectTactile(bundle: TactileMaskCaptureBundle, atElapsedRealtimeNs: Long): List<TactileMaskSurfaceResult>
    fun publishTactile(bundle: TactileMaskCaptureBundle, surfaces: List<TactileMaskSurfaceResult>,
        publication: UnknownTactilePublication, decisionNs: Long,
        admissionIsCurrent: () -> Boolean, outputIsCurrent: () -> Boolean): Boolean
    fun cancelFeedbackForTrackPrefix(prefix: String)
    fun retireTerminalFeedbackForTrackPrefix(prefix: String)
    fun observeLoad(nowMs: Long, normal: Boolean, completion: RuntimeLoadCompletion)
}
