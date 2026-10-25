package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureLumaInput as UnknownLumaInput
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointLumaCapture
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.UprightCameraImage

internal class ArFrameRecognitionCapture(
    private val frameStateLock: Any,
    private val authority: FrameRecognitionAuthority,
    private val diagnosticsEnabled: Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val diagnostic: (String) -> Unit,
) {
    private var runtimeRecognitionRejectDiagnosticAtMs = 0L

    /** The input owns copies only. Receipt failure never triggers another CPU acquisition. */
    internal fun <T> acquireFrameRecognitionCapture(
        provider: ArCoreFrameProvider,
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean,
        trackingAtAcquisition: Boolean = frame.camera.trackingState == TrackingState.TRACKING,
        copyInput: (android.media.Image) -> T?,
    ): FrameRecognitionCapture<T> {
        val tracking = trackingAtAcquisition
        if (!tracking) {
            val input = try {
                provider.acquireUntrackedRecognitionCameraImageOrNull(frame)?.use(copyInput)
            } catch (_: RuntimeException) {
                null
            }
            return FrameRecognitionCapture(input, DepthFrameSnapshot(frame.timestamp, null, null, null))
        }
        val packet = provider.acquireRecognitionCameraImageOrNull(frame)
        val input = try {
            packet?.image?.use(copyInput)
        } catch (error: RuntimeException) {
            logRuntimeRecognitionRejected("COPY_EXCEPTION_${error.javaClass.simpleName}")
            null
        }
        if (packet == null) logRuntimeRecognitionRejected("PACKET_MISSING")
        val snapshot = provider.acquireDepthSnapshot(
            frame,
            cameraImageTimestampEvidence = packet?.timestampEvidence?.takeIf { input != null },
            includeFullDepthWhenRawAvailable = includeFullDepthWhenRawAvailable,
        )
        return FrameRecognitionCapture(input, snapshot, packet?.timestampEvidence?.takeIf { input != null })
    }

    fun copyFrameRecognitionInput(
        image: android.media.Image,
        frame: Frame,
        capturedAtElapsedRealtimeMs: Long,
        frameGeneration: Int,
        walkEpoch: WalkRuntimeEpoch,
        arGeneration: Long,
        trackingAtAcquisition: Boolean,
    ): FrameRecognitionInput? {
        if (!authority.obstacleFeatureEnabled()) {
            logRuntimeRecognitionRejected("OBSTACLE_FEATURE_DISABLED")
            return null
        }
        val geometryVersion = synchronized(frameStateLock) {
            if (!authority.ownsFrame(frameGeneration, walkEpoch) || !authority.detectorAvailable()) {
                logRuntimeRecognitionRejected(if (!authority.detectorAvailable()) "DETECTOR_UNAVAILABLE" else "FRAME_OWNER_CHANGED")
                return null
            }
            authority.geometryVersion()
        }
        val tracking = trackingAtAcquisition
        // The untracked RGB branch owns pixels, but must never read or invent a CPU clock.
        val cameraTimestampNs = if (tracking) image.timestamp.takeIf { it > 0L } else null
        val ownedFrame = OwnedYuvFrame.copyOf(image)
        val plane = image.planes.firstOrNull()
        val gray = if (cameraTimestampNs != null && plane != null && frame.timestamp > 0L) {
            GrayTrackingFrame.copyFromLuma(
                VisualFrameKey(arGeneration, frame.timestamp, cameraTimestampNs, capturedAtElapsedRealtimeMs, geometryVersion),
                image.width, image.height, plane.buffer, plane.rowStride, plane.pixelStride,
            )
        } else null
        val qualityObservation = authority.cameraQualityObservation(
            epoch = walkEpoch,
            observedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
            frameAvailable = true,
            width = image.width,
            height = image.height,
            yPlaneBuffer = plane?.buffer,
            yPlaneRowStride = plane?.rowStride,
            yPlanePixelStride = plane?.pixelStride,
        )
        val basis = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED,
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.VIEW, basis)
        val quarterTurns = UprightCameraImage.quarterTurns(basis) ?: run {
            logRuntimeRecognitionRejected("IMAGE_ORIENTATION_UNAVAILABLE")
            return null
        }
        val service = authority.unknownService()
        val unknownLuma = if (service != null && cameraTimestampNs != null && plane != null && frame.timestamp > 0L) {
            val geometryId = "$arGeneration:$geometryVersion:${image.width}x${image.height}:q$quarterTurns"
            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, service.sessionEpoch,
                frame.timestamp, frame.timestamp, cameraTimestampNs, capturedAtElapsedRealtimeMs * 1_000_000L,
                geometryId, image.width, image.height)
            try { UnknownMaterialPointLumaCapture.copyFromLuma(
                token = token,
                imageQuarterTurns = quarterTurns,
                observedCpuImageTimestampNs = cameraTimestampNs,
                imageWidth = image.width,
                imageHeight = image.height,
                luma = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )?.let { UnknownLumaInput(service, it) } } catch (_: RuntimeException) { null }
        } else null
        if (!authority.ownsFrame(frameGeneration, walkEpoch)) return null
        return FrameRecognitionInput(ownedFrame, cameraTimestampNs, gray, qualityObservation, quarterTurns,
            geometryVersion, unknownLuma = unknownLuma)
    }

    private fun logRuntimeRecognitionRejected(reason: String) {
        if (!diagnosticsEnabled) return
        try {
            val nowMs = elapsedRealtimeMs()
            if (nowMs - runtimeRecognitionRejectDiagnosticAtMs < 1_000L) return
            runtimeRecognitionRejectDiagnosticAtMs = nowMs
            diagnostic("event=RUNTIME_RGB_REJECTED reason=$reason")
        } catch (_: RuntimeException) {
            // Optional platform diagnostics must not change capture/failure handling.
        }
    }
}

