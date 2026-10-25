package kr.co.hanium.dreamup.walksafe.runtime.metric

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricFrameEvidence
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightPolicy
import kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle.RuntimeArPlatform
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionAdmission

/** Owns the temporary support Session's finally-close, using the same existing SDK platform. */
internal class RuntimeMetricSupportProbe<S : Any, P : Any>(
    private val platform: RuntimeArPlatform<S, P>,
    private val automaticDepthSupported: (S) -> Boolean,
) : RuntimeMetricDepthSupportProbe {
    override fun automaticDepthSupported(): Boolean {
        val supportSession = platform.createSession()
        try {
            return automaticDepthSupported(supportSession)
        } finally {
            platform.closeSession(supportSession)
        }
    }
}

/** Called synchronously only after the actual metric owner admits this frame; no Frame is stored. */
internal class AndroidRuntimeMetricFrameEvidence(private val minimumRawConfidence: Double) : RuntimeMetricPreflightFrameEvidence<Frame> {
    companion object {
        fun automaticDepthSupported(session: Session): Boolean = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    }
    override fun observe(frame: Frame, snapshot: DepthFrameSnapshot, observedAtMs: Long,
        generation: Long, recognitionCameraTimestampNs: Long?): RuntimeMetricFrameEvidence {
        val validSamples = FrameRecognitionAdmission.metricSampleCount(recognitionCameraTimestampNs, frame.timestamp, snapshot) {
            snapshot.validMetricSampleCount(
                minimumRawConfidence = minimumRawConfidence,
                minimumDistanceMeters = RuntimeMetricPreflightPolicy.MIN_VALID_DISTANCE_METERS,
                maximumDistanceMeters = RuntimeMetricPreflightPolicy.MAX_VALID_DISTANCE_METERS,
                requireFreshRaw = false,
            )
        }
        return RuntimeMetricFrameEvidence(
            generation = generation,
            frameTimestampNanos = frame.timestamp,
            observedAtElapsedRealtimeMs = observedAtMs,
            tracking = frame.camera.trackingState == TrackingState.TRACKING,
            metricDepthAvailable = FrameRecognitionAdmission.hasMetricDepth(recognitionCameraTimestampNs, frame.timestamp, snapshot),
            validMetricSamplesInRange = validSamples,
        )
    }
}
