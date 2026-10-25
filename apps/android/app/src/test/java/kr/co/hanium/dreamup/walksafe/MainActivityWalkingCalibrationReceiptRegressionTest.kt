package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RawWalkingCalibrationFix
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TimestampAlignedWalkingCalibration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserDeviceRotationState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserLocomotionState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main receipt/motion gate and real timestamp alignment; no Android callbacks are run. */
class MainActivityWalkingCalibrationReceiptRegressionTest {
    @Test
    fun processingDelayDoesNotTurnAFreshCallbackIntoAStaleRawFix() {
        val coordinator = TimestampAlignedWalkingCalibration()

        val samples = MainActivity().observeWalkingCalibrationFix(
            fix = fix(measuredAt = 1_000L, receivedAt = 1_100L),
            userMotion = moving(at = 1_100L),
            processingAtElapsedRealtimeMs = 1_800L,
            coordinator = coordinator,
        )

        assertTrue(samples.isEmpty())
        assertEquals(1L, credibleFixCount(coordinator))
        assertEquals(0L, rejectedFixCount(coordinator, WalkingCalibrationReason.STALE_FIX))
    }

    @Test
    fun processingDelayCannotExtendTheOriginalPendingFixConfirmationDeadline() {
        val coordinator = TimestampAlignedWalkingCalibration()
        coordinator.onStep(750L, 1, true, 760L)
        val samples = MainActivity().observeWalkingCalibrationFix(
            fix = fix(measuredAt = 1_000L, receivedAt = 1_100L),
            userMotion = moving(at = 1_100L),
            processingAtElapsedRealtimeMs = 1_200L,
            coordinator = coordinator,
        )
        assertTrue(samples.isEmpty())
        assertEquals(1L, credibleFixCount(coordinator))

        // Valid step delivery, but 2,001 ms after the fix's original receipt.
        val confirmed = coordinator.onStep(1_250L, 1, true, 3_101L)

        assertTrue(confirmed.isEmpty())
        assertEquals(
            1L,
            count(coordinator, WalkingCalibrationStage.TIMESTAMP_ALIGNMENT,
                WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.FIX_CONFIRMATION_EXPIRED),
        )
    }

    @Test
    fun aLaterProcessingClockCannotHideAnOutOfOrderOriginalCallbackReceipt() {
        val coordinator = TimestampAlignedWalkingCalibration()
        coordinator.onStep(750L, 1, true, 1_150L)

        val samples = MainActivity().observeWalkingCalibrationFix(
            fix = fix(measuredAt = 1_000L, receivedAt = 1_100L),
            userMotion = moving(at = 1_150L),
            processingAtElapsedRealtimeMs = 1_200L,
            coordinator = coordinator,
        )

        assertTrue(samples.isEmpty())
        assertEquals(1L, credibleFixCount(coordinator))
        assertEquals(
            1L,
            count(coordinator, WalkingCalibrationStage.TIMESTAMP_ALIGNMENT,
                WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.DELIVERY_ORDER),
        )
    }

    @Test
    fun movingEvidenceStillUsesProcessingTimeAtAndAfterItsTwoSecondBoundary() {
        listOf(3_100L to true, 3_101L to false, 3_250L to false).forEach { (processingAt, eligible) ->
            val coordinator = TimestampAlignedWalkingCalibration()

            val samples = MainActivity().observeWalkingCalibrationFix(
                fix = fix(measuredAt = 3_000L, receivedAt = 3_100L),
                userMotion = moving(at = 1_100L),
                processingAtElapsedRealtimeMs = processingAt,
                coordinator = coordinator,
            )

            assertTrue(samples.isEmpty())
            assertEquals(if (eligible) 1L else 0L, credibleFixCount(coordinator))
            assertEquals(if (eligible) 0L else 1L,
                rejectedFixCount(coordinator, WalkingCalibrationReason.UNTRUSTED_RAW_FIX))
        }
    }

    @Test
    fun anOrdinaryFreshFixWaitsForTheActualStepWatermarkAndThenCreatesOnlyAnAnchor() {
        val coordinator = TimestampAlignedWalkingCalibration()
        coordinator.onStep(750L, 1, true, 760L)

        val pending = MainActivity().observeWalkingCalibrationFix(
            fix = fix(measuredAt = 1_000L, receivedAt = 1_100L),
            userMotion = moving(at = 1_100L),
            processingAtElapsedRealtimeMs = 1_100L,
            coordinator = coordinator,
        )
        assertTrue(pending.isEmpty())
        assertEquals(WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK,
            coordinator.diagnosticSnapshot().latestDecision?.reason)

        val confirmed = coordinator.onStep(1_250L, 1, true, 1_300L)

        assertTrue(confirmed.isEmpty())
        assertEquals(WalkingCalibrationReason.NEW_SEGMENT_ANCHOR,
            coordinator.diagnosticSnapshot().latestDecision?.reason)
    }

    @Test
    fun aRejectedRawFixStillProducesNoCalibrationSampleDespiteAFreshMovingSnapshot() {
        val coordinator = TimestampAlignedWalkingCalibration()

        val samples = MainActivity().observeWalkingCalibrationFix(
            fix = fix(measuredAt = 1_000L, receivedAt = 1_100L).copy(trustedRawFix = false),
            userMotion = moving(at = 1_100L),
            processingAtElapsedRealtimeMs = 1_800L,
            coordinator = coordinator,
        )

        assertTrue(samples.isEmpty())
        assertEquals(0L, credibleFixCount(coordinator))
        assertEquals(1L, rejectedFixCount(coordinator, WalkingCalibrationReason.UNTRUSTED_RAW_FIX))
    }

    @Test
    fun sourceOnlyTheActualCallbackReceiptReachesTheSharedMainGateAndExistingSampleConsumer() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val callback = NavigationPolicySource.observation(source)
        assertTrue(callback.contains("attemptStepCalibration(location, positioningSnapshot, receivedAtElapsedRealtimeMs)"))
        val wrapper = NavigationPolicySource.calibration(source)
        assertTrue(wrapper.contains("receivedAtElapsedRealtimeMs: Long"))
        assertTrue(wrapper.contains("receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs"))
        assertTrue(wrapper.contains("observeWalkingCalibrationFix("))
        assertTrue(wrapper.contains("processingAtElapsedRealtimeMs = processingAtElapsedRealtimeMs"))
        assertTrue(wrapper.contains(".forEach(::applyWalkingCalibrationSample)"))
    }

    private fun credibleFixCount(coordinator: TimestampAlignedWalkingCalibration) =
        count(coordinator, WalkingCalibrationStage.RAW_FIX_CREDIBILITY,
            WalkingCalibrationOutcome.PASSED, WalkingCalibrationReason.CREDIBLE_RAW_FIX)

    private fun rejectedFixCount(coordinator: TimestampAlignedWalkingCalibration, reason: WalkingCalibrationReason) =
        count(coordinator, WalkingCalibrationStage.RAW_FIX_CREDIBILITY, WalkingCalibrationOutcome.REJECTED, reason)

    private fun count(
        coordinator: TimestampAlignedWalkingCalibration,
        stage: WalkingCalibrationStage,
        outcome: WalkingCalibrationOutcome,
        reason: WalkingCalibrationReason,
    ): Long = coordinator.diagnosticSnapshot().decisionCounts[WalkingCalibrationDecision(stage, outcome, reason)] ?: 0L

    private fun fix(measuredAt: Long, receivedAt: Long) = RawWalkingCalibrationFix(
        latitude = 37.5,
        longitude = 127.0,
        horizontalAccuracyM = 1.0,
        elapsedRealtimeMs = measuredAt,
        receivedAtElapsedRealtimeMs = receivedAt,
        speedMps = 1.4,
        speedAccuracyMps = 0.1,
        courseDegrees = 90.0,
        courseAccuracyDegrees = 5.0,
        trustedRawFix = true,
    )

    private fun moving(at: Long) = UserMotionStateSnapshot(
        generation = 1L,
        movement = UserMovementState.MOVING,
        locomotion = UserLocomotionState.WALKING,
        deviceRotation = UserDeviceRotationState.STABLE,
        reason = UserMotionStateReason.STEP_OBSERVED,
        stateSinceElapsedRealtimeMs = 750L,
        evaluatedAtElapsedRealtimeMs = at,
        expiresAtElapsedRealtimeMs = at + 2_000L,
        motionObservedAtElapsedRealtimeMs = null,
        speedObservedAtElapsedRealtimeMs = null,
        stepObservedAtElapsedRealtimeMs = 750L,
        stationaryCandidateSinceElapsedRealtimeMs = null,
        zeroVelocityUpdateAllowed = false,
    )

    private fun functionBlock(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
