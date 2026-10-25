package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import kr.co.hanium.dreamup.walksafe.depth.toSnapshotAndClose

/** One synchronous GL callback. Native frames and images never become controller state. */
internal class ArFrameRenderController<L : Any, Q>(
    private val frameStateLock: Any,
    private val source: ArFrameRenderSource<L, Q>,
    private val demand: ArFrameRenderDemand,
    private val graphics: ArFrameGraphics,
    private val recognition: ArFrameRecognitionCapture,
    private val clock: ArFrameClock,
    private val observer: ArFrameRenderObserver<L, Q>,
) {
    fun draw(processRuntimeCapture: (ScopedArFrameCapture<L, Q>) -> Unit) {
        observer.onRenderCallback()
        graphics.clear()
        val frameGeneration = synchronized(frameStateLock) {
            if (!source.foreground()) return
            source.frameGeneration()
        }
        val lease = source.acquireLease() ?: return
        source.bindTextureIfReady()
        if (!source.textureBound()) return
        if (!source.leaseIsCurrent(lease.originalLease)) return
        val epoch = source.currentEpoch()
        val frame = try {
            lease.session.update()
        } catch (error: CameraNotAvailableException) {
            observer.updateFailed("카메라 사용 불가", error.message ?: "ARCore frame update 실패",
                lease.purpose, lease.generation, epoch, "CAMERA_NOT_AVAILABLE", error)
            return
        } catch (error: RuntimeException) {
            observer.updateFailed("ARCore frame 대기", error.message ?: "frame update 준비 중",
                lease.purpose, lease.generation, epoch, "RUNTIME_EXCEPTION", error)
            return
        }
        if (!source.leaseIsCurrent(lease.originalLease)) return
        if (frame.hasDisplayGeometryChanged()) observer.geometryChanged(lease.originalLease, frameGeneration, epoch)
        graphics.drawBackground(frame)
        val originalFrameTimestampNs = frame.timestamp
        val timestampMs = originalFrameTimestampNs / 1_000_000L
        val wallClockMs = clock.wallClockMs()
        val elapsedMs = clock.elapsedRealtimeMs()
        val includeFull = source.isPreflight(lease.purpose) || synchronized(frameStateLock) {
            demand.normalDetectionDue(elapsedMs)
        } || demand.unknownCaptureDue(elapsedMs) || demand.freshVisualSource(elapsedMs)
        val acquisitionStartedMs = clock.elapsedRealtimeMs()
        val trackingAtAcquisition = frame.camera.trackingState == TrackingState.TRACKING
        val captured = if (source.isPreflight(lease.purpose)) null else {
            recognition.acquireFrameRecognitionCapture(lease.provider, frame, includeFull, trackingAtAcquisition) { image ->
                recognition.copyFrameRecognitionInput(image, frame, elapsedMs, frameGeneration, epoch,
                    lease.generation, trackingAtAcquisition)
            }
        }
        val snapshot = if (source.isPreflight(lease.purpose)) {
            lease.provider.acquirePreflightDepthBundle(frame).toSnapshotAndClose()
        } else requireNotNull(captured).depthSnapshot
        val failure = if (source.isPreflight(lease.purpose)) null else lease.provider.consumeRuntimeAcquisitionFailure()
        val acquisitionMs = clock.elapsedRealtimeMs() - acquisitionStartedMs
        if (!source.leaseIsCurrent(lease.originalLease)) return
        val scope = ScopedArFrameCapture(frame, lease, frameGeneration, epoch, originalFrameTimestampNs, timestampMs, wallClockMs,
            elapsedMs, captured, snapshot, failure, acquisitionMs)
        if (source.isRuntime(lease.purpose)) observer.diagnoseRuntimeCapture(scope)
        if (source.isPreflight(lease.purpose)) {
            observer.processPreflight(scope)
            return
        }
        processRuntimeCapture(scope)
    }
}
