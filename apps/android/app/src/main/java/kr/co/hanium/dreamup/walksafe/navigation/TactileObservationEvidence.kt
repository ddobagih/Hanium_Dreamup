package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

/** Existing capture/measurement limits shared by projection and distance-free tactile references. */
internal object TactileObservationEvidence {
    const val MAX_DETECTION_AGE_MS = 400L
    const val MAX_LOCATION_AGE_MS = 2_000L
    private const val MAX_GPS_ACCURACY_M = 5f

    fun freshAtCaptureAndDecision(observedMs: Long, captureMs: Long, decisionMs: Long, maximumAgeMs: Long): Boolean =
        observedMs >= 0L && captureMs >= 0L && observedMs <= captureMs && decisionMs >= captureMs &&
            captureMs - observedMs <= maximumAgeMs && decisionMs - observedMs <= maximumAgeMs

    fun hasFreshCameraFrame(context: TactileProjectionContext): Boolean {
        val frame = context.cameraFrame ?: return false
        val decisionMs = context.decisionElapsedRealtimeMs ?: return false
        return frame.frameId == context.captureFrameId && frame.tracking &&
            frame.cameraToAndroidSensor.isOrthonormal() && freshAtCaptureAndDecision(
                frame.observedAtElapsedRealtimeMs, context.nowElapsedRealtimeMs, decisionMs, MAX_CAMERA_FRAME_AGE_MS,
            )
    }

    fun hasFreshLocation(context: TactileProjectionContext): Boolean {
        val location = context.trustedLocation ?: return false
        val decisionMs = context.decisionElapsedRealtimeMs ?: return false
        return freshAtCaptureAndDecision(location.elapsedRealtimeMs, context.nowElapsedRealtimeMs,
            decisionMs, MAX_LOCATION_AGE_MS) &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.accuracyM.isFinite() && location.accuracyM in 0f..MAX_GPS_ACCURACY_M
    }

    fun hasTrustedMetricDepth(output: TrackedObjectDepth): Boolean {
        val minimumSamples = when (output.source) {
            DepthSource.ARCORE_RAW_DEPTH -> MIN_RAW_DEPTH_SAMPLES
            DepthSource.ARCORE_FULL_DEPTH -> MIN_FULL_DEPTH_SAMPLES
            else -> return false
        }
        return output.validSampleCount >= minimumSamples && output.validSampleRatio.isFinite() &&
            output.validSampleRatio in MIN_VALID_SAMPLE_RATIO..1f && output.confidence.hardGate > 0f &&
            output.confidence.finalScore >= MIN_DEPTH_CONFIDENCE
    }

    private const val MAX_CAMERA_FRAME_AGE_MS = 100L
    private const val MIN_RAW_DEPTH_SAMPLES = 30
    private const val MIN_FULL_DEPTH_SAMPLES = 50
    private const val MIN_VALID_SAMPLE_RATIO = .25f
    private const val MIN_DEPTH_CONFIDENCE = .55f
}
