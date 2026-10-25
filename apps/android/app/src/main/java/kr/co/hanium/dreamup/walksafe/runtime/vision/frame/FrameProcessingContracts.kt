package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import java.nio.ByteBuffer
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Reads the real owners at each original admission point. Construction never queries these. */
internal interface FrameRecognitionAuthority {
    fun obstacleFeatureEnabled(): Boolean
    fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun detectorAvailable(): Boolean
    fun geometryVersion(): Long
    fun unknownService(): FastSamRuntimeService<UnknownRuntimeAttachment>?
    /** Synchronous borrowed luma inspection while the original CPU Image is still open. */
    fun cameraQualityObservation(epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long,
        frameAvailable: Boolean, width: Int, height: Int, yPlaneBuffer: ByteBuffer?,
        yPlaneRowStride: Int?, yPlanePixelStride: Int?): CameraFrameQualityObservation
}

internal interface FrameSynthesisAuthority {
    fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun userStepLengthM(): Float
    fun retireUnstartedFeedback(trackPrefix: String)
}

internal interface ArFrameClock {
    fun elapsedRealtimeMs(): Long
    fun wallClockMs(): Long
}

/** Native references from the exact existing source lease; no new acceptance or generation proof. */
internal data class FrameArResources<L : Any, Q>(val originalLease: L, val session: Session,
    val provider: ArCoreFrameProvider, val purpose: Q, val generation: Long)

internal interface ArFrameRenderSource<L : Any, Q> {
    fun foreground(): Boolean
    fun frameGeneration(): Int
    fun acquireLease(): FrameArResources<L, Q>?
    fun bindTextureIfReady()
    fun textureBound(): Boolean
    fun leaseIsCurrent(lease: L): Boolean
    fun currentEpoch(): WalkRuntimeEpoch
    fun isPreflight(purpose: Q): Boolean
    fun isRuntime(purpose: Q): Boolean
}

internal interface ArFrameRenderDemand {
    /** Called under the exact shared frame lock; preserves the old availability/inflight/pacing reads. */
    fun normalDetectionDue(nowElapsedRealtimeMs: Long): Boolean
    fun unknownCaptureDue(nowElapsedRealtimeMs: Long): Boolean
    fun freshVisualSource(nowElapsedRealtimeMs: Long): Boolean
}

internal interface ArFrameGraphics {
    fun clear()
    fun drawBackground(frame: Frame)
}

/** Stack-only producer scope: passed synchronously, never queued, stored or used by a UI callback. */
internal data class ScopedArFrameCapture<L : Any, Q>(
    val frame: Frame,
    val resources: FrameArResources<L, Q>,
    val frameGeneration: Int,
    val frameWalkEpoch: WalkRuntimeEpoch,
    val originalFrameTimestampNs: Long,
    val timestampMs: Long,
    val wallClockMs: Long,
    val capturedElapsedMs: Long,
    val recognition: FrameRecognitionCapture<FrameRecognitionInput>?,
    val snapshot: DepthFrameSnapshot,
    val acquisitionFailure: DepthAcquisitionFailureReason?,
    val depthProcessingMs: Long,
)

internal interface ArFrameRenderObserver<L : Any, Q> {
    /** Preserves the heartbeat owner's nullable clock evaluation. */
    fun onRenderCallback()
    fun geometryChanged(lease: L,
        frameGeneration: Int, epoch: WalkRuntimeEpoch)
    fun updateFailed(status: String, detail: String, purpose: Q, arGeneration: Long,
        epoch: WalkRuntimeEpoch, diagnosticKind: String, error: Throwable)
    fun diagnoseRuntimeCapture(capture: ScopedArFrameCapture<L, Q>)
    fun processPreflight(capture: ScopedArFrameCapture<L, Q>)
}
