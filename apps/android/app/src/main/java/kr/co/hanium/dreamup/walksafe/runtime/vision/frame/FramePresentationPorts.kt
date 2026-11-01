package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal typealias FrameMappedDebugBox = DebugBboxOverlayView.DebugOverlayBox

/** Exact borrowed lease identity plus producer-frozen scalars; no Frame or Image is retained. */
internal data class FramePresentationScope<L : Any>(
    val frameGeneration: Int,
    val walkEpoch: WalkRuntimeEpoch,
    val lease: L,
    val arGeneration: Long,
    val elapsedMs: Long,
    val originalFrameTimestampNs: Long,
    val tracking: Boolean,
)

internal data class FrameUnavailableProjection(
    val boxes: List<FrameMappedDebugBox>,
    val displayFrameId: Long,
    val sourceCapturedAt: Long?,
    val fresh: Boolean,
    val message: String,
)

/** The overlay timestamp getter is supplied separately and invoked synchronously after mapping/admission. */
internal data class FrameOverlayPresentationInput<L : Any>(
    val scope: FramePresentationScope<L>,
    val currentObservationPresent: Boolean,
    val sourceFrameId: Long?,
    val detectorCapturedElapsedMs: Long?,
    val currentCapturedElapsedMs: Long?,
    val bestOutput: TrackedObjectDepth?,
    val diagnostics: FrameOverlayDiagnostics,
    val overlayDetectionsSize: Int,
)

internal data class FrameGapPresentationInput<L : Any>(
    val scope: FramePresentationScope<L>,
    val currentCapturedElapsedMs: Long?,
    val overlayCount: Int,
    val guidanceOutputs: List<TrackedObjectDepth>,
)

internal data class FrameStatusPresentationInput<L : Any>(
    val scope: FramePresentationScope<L>,
    val bestOutput: TrackedObjectDepth?,
    val snapshot: DepthFrameSnapshot,
    val detectionSnapshot: DetectionSnapshot,
    val timestampMs: Long,
    val wallNowMs: Long,
    val currentCapturedElapsedMs: Long?,
    /** Live startup/depth/detector diagnostic query, evaluated after queued UI admission. */
    val gateText: () -> String,
)

/** Same original actor/page/viewport snapshot; the actual owner validates it in source read order. */
internal data class FrameStatusUiContext(
    val actorId: String?,
    val accountGeneration: Long,
    val page: Enum<*>,
    val cameraVisible: Boolean,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val geometryVersion: Long,
)

/** Existing role owners decide admission; presentation does not manufacture a replacement lease. */
internal interface FramePresentationSource<L : Any> {
    fun ownsFrame(frameGeneration: Int, epoch: WalkRuntimeEpoch): Boolean
    fun ownsLease(lease: L): Boolean
    fun metricAllowsWork(arGeneration: Long): Boolean
    fun visualDepthAllowsWork(lease: L, frameTimestampNs: Long, tracking: Boolean): Boolean
}

internal interface FramePresentationContext {
    val cameraVisible: Boolean
    val recognitionDisplayGeneration: Long? get() = null
    val surfaceWidth: Int
    val surfaceHeight: Int
    val statusOwner: Any
    fun captureStatusContext(): FrameStatusUiContext
    /** Actor -> account generation -> page -> camera visibility -> width -> height; short-circuit. */
    fun isStatusContextCurrent(context: FrameStatusUiContext): Boolean
    /** Queried with the original shared frame lock held, together with the status watermark. */
    fun geometryVersion(): Long
}

/** Only the original widgets and dispatchers; getters are called in the original conditional branch. */
internal interface FramePresentationView {
    val overlayWidth: Int
    val overlayHeight: Int
    var cameraStatus: String
    val statusLabel: String
    val detailText: String
    val statusDescription: String?
    fun updateStatus(status: String, detail: String)
    fun clearOverlay()
    fun updateMapped(boxes: List<FrameMappedDebugBox>, sourceFrameId: Long, sourceCapturedAt: Long)
    fun onUi(action: () -> Unit)
    fun postOverlay(delayMs: Long, action: () -> Unit)
    fun postCameraStatus(delayMs: Long, action: () -> Unit)
    fun postStatus(delayMs: Long, action: () -> Unit)
    fun updateDebugUploadButton()
    fun updateRecognition(frame: kr.co.hanium.dreamup.walksafe.ui.GuidanceRecognitionDisplay.Frame, context: FrameStatusUiContext) {}
}

internal data class FrameStatusDiagnosticParts(
    val deliveryLine: String,
    val userMotion: String,
    val inferencePacing: String,
    val visualTracking: String,
    val detector: String,
    val unknownRuntime: String,
    val metadataUpload: String,
    val captureHistory: String,
)

internal data class FrameGapDiagnosticParts(val visualTracking: String, val unknownRuntime: String)

/** Purpose-specific diagnostic rendering, queried after the queued UI work's original admission. */
internal interface FramePresentationDiagnostics {
    fun depthSupported(): Boolean
    fun noDetectionStatus(snapshot: DepthFrameSnapshot): String
    /** Original delivery line -> motion -> pacing -> visual -> detector -> unknown -> uploader -> history. */
    fun statusParts(gateText: String): FrameStatusDiagnosticParts
    fun gapParts(): FrameGapDiagnosticParts
}
