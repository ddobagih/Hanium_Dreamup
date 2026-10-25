package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class WalkingCalibrationSessionTest {
    @Test fun sameScopeKeepsIdentityStepsAndDiagnosticHistory() {
        val session = WalkingCalibrationSession()
        val epoch = WalkRuntimeEpoch("walk", 0L)
        val owner = session.synchronizeScope("actor", epoch)
        val originalStep = step(750L)
        session.onStep(originalStep, 760L)
        val diagnostic = session.segments.diagnosticSnapshot()
        assertSame(owner, session.synchronizeScope("actor", epoch.copy()))
        assertSame(originalStep, session.latestStep)
        assertEquals(diagnostic, session.segments.diagnosticSnapshot())
    }

    @Test fun actorOrWalkChangeRetiresStepsHistoryAndTheExactScope() {
        val session = WalkingCalibrationSession()
        val first = session.synchronizeScope("actor", WalkRuntimeEpoch("walk", 0L))
        session.onStep(step(750L), 760L)
        session.observeFix(fix(), moving(), 1_100L)
        val second = session.synchronizeScope("other", first.walkEpoch)
        assertNotSame(first, second)
        assertNull(session.latestStep)
        assertTrue(session.segments.diagnosticSnapshot().decisionCounts.isEmpty())
        assertFalse(session.isCurrentScope(first, "actor", "actor", first.walkEpoch))
        assertTrue(session.isCurrentScope(second, "other", "other", second.walkEpoch))
        session.onStep(step(1_250L), 1_300L)
        assertEquals(WalkingCalibrationReason.WAITING_FOR_RAW_FIX,
            session.segments.diagnosticSnapshot().latestDecision?.reason)
        val third = session.synchronizeScope("other", WalkRuntimeEpoch("walk", 1L))
        assertNotSame(second, third)
        assertNull(session.latestStep)
        assertTrue(session.segments.diagnosticSnapshot().decisionCounts.isEmpty())
    }

    @Test fun equivalentScopeValueAndChangedExternalAuthoritiesCannotValidateOldEffects() {
        val session = WalkingCalibrationSession()
        val owner = session.synchronizeScope("actor", WalkRuntimeEpoch("walk", 0L))
        assertFalse(session.isCurrentScope(owner.copy(), "actor", "actor", owner.walkEpoch))
        assertFalse(session.isCurrentScope(owner, "other", "actor", owner.walkEpoch))
        assertFalse(session.isCurrentScope(owner, "actor", "other", owner.walkEpoch))
        assertFalse(session.isCurrentScope(owner, "actor", "actor", WalkRuntimeEpoch("walk", 1L)))
        assertTrue(session.isCurrentScope(owner, "actor", "actor", owner.walkEpoch))
    }

    @Test fun historyResetPreservesDiagnosticCountsAndLatestOriginalStep() {
        val session = WalkingCalibrationSession()
        val owner = session.synchronizeScope("actor", null)
        val originalStep = step(750L)
        session.onStep(originalStep, 760L)
        session.observeFix(fix(), moving(), 1_100L)
        val diagnostic = session.segments.diagnosticSnapshot()
        session.resetHistory()
        assertSame(owner, session.scope)
        assertSame(originalStep, session.latestStep)
        assertEquals(diagnostic, session.segments.diagnosticSnapshot())
        session.onStep(step(1_250L), 1_300L)
        assertEquals(WalkingCalibrationReason.WAITING_FOR_RAW_FIX,
            session.segments.diagnosticSnapshot().latestDecision?.reason)
    }

    @Test fun speedOnlyMovingAndStaleOrFutureMotionCannotApproveTheRawFix() {
        val observations = listOf(
            moving().copy(stepObservedAtElapsedRealtimeMs = null),
            moving().copy(movement = UserMovementState.UNKNOWN),
            moving(at = -901L),
            moving(at = 1_101L),
        )
        observations.forEach { motion ->
            val session = WalkingCalibrationSession()
            assertTrue(session.observeFix(fix(), motion, 1_100L).isEmpty())
            assertEquals(WalkingCalibrationReason.UNTRUSTED_RAW_FIX,
                session.segments.diagnosticSnapshot().latestDecision?.reason)
        }
    }

    @Test fun motionFreshnessIncludesExactlyTwoSecondsButNeverRenewsReceipt() {
        for ((processingAt, credible) in listOf(3_100L to true, 3_101L to false)) {
            val session = WalkingCalibrationSession()
            session.observeFix(fix(measuredAt = 3_000L, receivedAt = 3_100L), moving(), processingAt)
            assertEquals(if (credible) WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK
                else WalkingCalibrationReason.UNTRUSTED_RAW_FIX,
                session.segments.diagnosticSnapshot().latestDecision?.reason)
        }
        val session = WalkingCalibrationSession()
        session.onStep(step(750L), 760L)
        session.observeFix(fix(), moving(), 1_800L)
        session.onStep(step(1_250L), 3_101L)
        assertEquals(1L, session.segments.diagnosticSnapshot().decisionCounts[WalkingCalibrationDecision(
            WalkingCalibrationStage.TIMESTAMP_ALIGNMENT,
            WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.FIX_CONFIRMATION_EXPIRED,
        )])
    }

    @Test fun counterBatchesRemainIneligibleAndLatestStepIsTheOriginalObject() {
        val session = WalkingCalibrationSession()
        val counter = step(750L).copy(deltaSteps = 3, source = StepEventSource.STEP_COUNTER)
        assertTrue(session.onStep(counter, 760L).isEmpty())
        assertSame(counter, session.latestStep)
        assertEquals(WalkingCalibrationReason.STEP_NOT_INDIVIDUAL,
            session.segments.diagnosticSnapshot().latestDecision?.reason)
    }

    @Test fun acceptedWarmupAndUpdatedResultKeepTheExistingEffectOrderAndStatus() {
        val session = WalkingCalibrationSession()
        session.synchronizeScope("actor", null)
        val coordinator = PositioningCoordinator()
        repeat(3) { index ->
            val result = coordinator.calibrateProfile(sample())
            val effects = mutableListOf<String>()
            session.applyResult(result,
                { effects += "read"; coordinator.currentProfile().stepLengthM.toFloat() },
                { effects += "apply" }, { effects += "persist" }, { effects += it })
            assertEquals(listOf("read", "apply", "persist"), effects.take(3))
            assertEquals(if (index < 2) "navigation=step_length_uncalibrated=0.65m"
                else "navigation=step_length_updated=0.69m", effects.last())
        }
    }

    @Test fun rejectedResultCannotReadApplyPersistOrPublishProfileEffects() {
        val session = WalkingCalibrationSession()
        val rejected = PositioningCoordinator().calibrateProfile(sample().copy(trustedPositionSegment = false))
        session.applyResult(rejected, { error("read") }, { error("apply") }, { error("persist") }, { error("status") })
    }

    @Test fun reentrantScopeChangeRetiresRemainingAcceptedResultEffects() {
        for (retireAt in listOf("read", "apply", "persist")) {
            val session = WalkingCalibrationSession()
            session.synchronizeScope("actor", null)
            val result = PositioningCoordinator().calibrateProfile(sample())
            val effects = mutableListOf<String>()
            fun effect(name: String) {
                effects += name
                if (name == retireAt) session.synchronizeScope("other", null)
            }
            session.applyResult(result, { effect("read"); 0.65f }, { effect("apply") },
                { effect("persist") }, { effect("status") })
            assertEquals(listOf("read", "apply", "persist").takeWhile { it != retireAt } + retireAt, effects)
        }
    }

    @Test fun positioningEntryUsesTheSharedCalculatorAndRetainsDiagnosticsAcrossLocationReset() {
        val controller = PositioningSessionControllerTest.Fixture().controller
        val collector = controller.calibrationSegments
        assertTrue(controller.observeWalkingCalibrationFix(fix(), moving(), 1_800L).isEmpty())
        assertSame(collector, controller.calibrationSegments)
        assertEquals(WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK,
            collector.diagnosticSnapshot().latestDecision?.reason)
        val diagnostic = collector.diagnosticSnapshot()
        controller.clearLocationDerivedState()
        assertEquals(diagnostic, collector.diagnosticSnapshot())
        controller.synchronizeWalkingCalibrationScope("other", null)
        assertTrue(collector.diagnosticSnapshot().decisionCounts.isEmpty())
    }

    @Test fun positioningSampleEntryCalibratesTheActualProfileAndPublishesItsStatus() {
        val fixture = PositioningSessionControllerTest.Fixture()
        fixture.controller.applyWalkingCalibrationSample(sample())
        assertEquals(1L, fixture.controller.coordinator.currentProfile().acceptedSampleCount)
        assertEquals("navigation=step_length_uncalibrated=0.65m", fixture.statusMessages.single())
        repeat(2) { fixture.controller.applyWalkingCalibrationSample(sample()) }
        assertTrue(fixture.controller.coordinator.currentProfile().isCalibrated)
        assertEquals("navigation=step_length_updated=0.69m", fixture.statusMessages.last())
    }

    private fun sample() = WalkingCalibrationSample(16.0, 20, 16_000L, true)
    private fun step(at: Long) = StepEvent(1, at, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH,
        measurementElapsedRealtimeNanos = at * 1_000_000L, receivedAtElapsedRealtimeNanos = (at + 10L) * 1_000_000L)
    private fun fix(measuredAt: Long = 1_000L, receivedAt: Long = 1_100L) = RawWalkingCalibrationFix(
        latitude = 37.5, longitude = 127.0, horizontalAccuracyM = 1.0,
        elapsedRealtimeMs = measuredAt, receivedAtElapsedRealtimeMs = receivedAt,
        speedMps = 1.4, speedAccuracyMps = 0.1, courseDegrees = 90.0, courseAccuracyDegrees = 5.0,
        trustedRawFix = true,
    )
    private fun moving(at: Long = 1_100L) = UserMotionStateSnapshot(
        generation = 1L, movement = UserMovementState.MOVING, locomotion = UserLocomotionState.WALKING,
        deviceRotation = UserDeviceRotationState.STABLE, reason = UserMotionStateReason.STEP_OBSERVED,
        stateSinceElapsedRealtimeMs = 750L, evaluatedAtElapsedRealtimeMs = at,
        expiresAtElapsedRealtimeMs = at + 2_000L, motionObservedAtElapsedRealtimeMs = null,
        speedObservedAtElapsedRealtimeMs = null, stepObservedAtElapsedRealtimeMs = 750L,
        stationaryCandidateSinceElapsedRealtimeMs = null, zeroVelocityUpdateAllowed = false,
    )
}
