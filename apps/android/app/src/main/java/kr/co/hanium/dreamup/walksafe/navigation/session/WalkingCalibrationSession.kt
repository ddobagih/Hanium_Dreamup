package kr.co.hanium.dreamup.walksafe.navigation.session

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionCalibrationResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RawWalkingCalibrationFix
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TimestampAlignedWalkingCalibration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationSample
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/**
 * Actor/walk lifetime of calibration inputs, diagnostics and accepted-result effects.
 * TimestampAlignedWalkingCalibration alone owns segment mathematics and measurement ordering.
 * The positioning controller serializes calls and retains profile storage/Android adapters.
 */
internal class WalkingCalibrationSession {
    val segments = TimestampAlignedWalkingCalibration()
    var scope: WalkingCalibrationScope? = null
        private set
    var latestStep: StepEvent? = null
        private set

    fun synchronizeScope(actorId: String?, walkEpoch: WalkRuntimeEpoch?): WalkingCalibrationScope {
        val current = scope
        if (current != null && current.actorId == actorId && current.walkEpoch == walkEpoch) return current
        segments.resetForScope()
        latestStep = null
        return WalkingCalibrationScope(actorId, walkEpoch).also { scope = it }
    }

    fun isCurrentScope(
        expected: WalkingCalibrationScope,
        profileActorId: String?,
        authenticatedActorId: String?,
        walkEpoch: WalkRuntimeEpoch?,
    ): Boolean = scope === expected && profileActorId == expected.actorId &&
        authenticatedActorId == expected.actorId && walkEpoch == expected.walkEpoch

    /** A lost/reinitialized fix retires measurement history, not this walk's diagnostic counts. */
    fun resetHistory() = segments.reset()

    fun onStep(event: StepEvent, receivedAtElapsedRealtimeMs: Long): List<WalkingCalibrationSample> {
        // Set before evaluating a sample: diagnostics must describe the completing original step.
        latestStep = event
        return segments.onStep(
            timestampMs = event.timestampMs,
            deltaSteps = event.deltaSteps,
            individualStepDetector = event.source == StepEventSource.STEP_DETECTOR &&
                event.confidence == StepEventConfidence.HIGH,
            receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
        )
    }

    fun observeFix(
        fix: RawWalkingCalibrationFix,
        userMotion: UserMotionStateSnapshot?,
        processingAtElapsedRealtimeMs: Long,
        coordinator: TimestampAlignedWalkingCalibration = segments,
    ): List<WalkingCalibrationSample> {
        // stateSince is approval time, not freshness. GNSS speed alone cannot approve walking.
        // Receipt and original measurement timestamps remain unchanged in the copied fix.
        val freshMovingEvidence = userMotion?.movement == UserMovementState.MOVING &&
            userMotion.stepObservedAtElapsedRealtimeMs != null &&
            processingAtElapsedRealtimeMs - userMotion.evaluatedAtElapsedRealtimeMs in 0L..2_000L
        return coordinator.onFix(fix.copy(trustedRawFix = fix.trustedRawFix && freshMovingEvidence))
    }

    fun applyResult(
        calibration: PedestrianMotionCalibrationResult,
        currentStepLengthM: () -> Float,
        applyStepLength: (Float) -> Unit,
        persistProfile: () -> Unit,
        updateStatus: (String) -> Unit,
    ) {
        if (calibration.rejectionReason != null) return
        val expectedScope = scope
        val stepLengthM = currentStepLengthM()
        if (scope !== expectedScope) return
        applyStepLength(stepLengthM)
        if (scope !== expectedScope) return
        persistProfile()
        if (scope !== expectedScope) return
        val stepLengthText = String.format(Locale.US, "%.2f", stepLengthM)
        if (!calibration.snapshot.isCalibrated) {
            updateStatus("navigation=step_length_uncalibrated=${stepLengthText}m")
        } else {
            updateStatus("navigation=step_length_updated=${stepLengthText}m")
        }
    }
}
