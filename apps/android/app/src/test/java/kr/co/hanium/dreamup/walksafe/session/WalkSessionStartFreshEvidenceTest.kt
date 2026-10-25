package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A delivered eight-second notice must use genuinely new sensor evidence at activation. */
class WalkSessionStartFreshEvidenceTest {
    private var now = 1_000L
    private val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-start" }, clock = { now })

    private fun prepare(): WalkSessionConfirmationToken {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness()))
        return checkNotNull(lifecycle.snapshot().confirmationToken)
    }

    private fun readiness(
        epoch: WalkRuntimeEpoch = lifecycle.snapshot().epoch,
        plan: WalkSessionReadinessPlan = WalkSessionReadinessPlan(
            WalkSessionAction.START_WALK, WalkSessionMode.FULL, checkEntryPermissions = true,
        ),
        cameraMeasuredAt: Long = now,
        cameraStatus: WalkSessionReadinessStatus = WalkSessionReadinessStatus.READY,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = if (requirement == WalkSessionReadinessRequirement.CAMERA) cameraStatus
                    else WalkSessionReadinessStatus.READY,
                    reason = if (cameraStatus != WalkSessionReadinessStatus.READY &&
                        requirement == WalkSessionReadinessRequirement.CAMERA) "camera_unavailable" else "",
                    supportingEvidence = if (requirement in setOf(
                            WalkSessionReadinessRequirement.PHONE_MOUNTING,
                            WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT,
                        )) listOf(
                            WalkSessionReadinessEvidence("camera_frame", cameraMeasuredAt, 5_000L, "camera-profile"),
                        ) else emptyList(),
                ),
            )
        }
        return collector.build(now)
    }

    @Test
    fun noticeOutlastingCameraEvidenceStillRejectsAnUnrefreshedStart() {
        val token = prepare()
        now += 8_700L
        assertFalse(token.readiness.isReadyAt(now))

        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
        assertTrue(lifecycle.snapshot().epoch != token.epoch)
    }

    @Test
    fun latestMeasuredEvidenceStartsTheSameConfirmedAttemptAfterNoticeAndServerResponse() {
        val token = prepare()
        now += 8_700L
        val fresh = readiness(cameraMeasuredAt = now - 500L)
        assertFalse(token.readiness.isReadyAt(now))
        assertTrue(fresh.isReady)

        lifecycle.handle(WalkSessionEvent.StartRequested(token, fresh))

        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertEquals(token.epoch, lifecycle.snapshot().epoch)
        assertEquals(fresh, lifecycle.snapshot().readiness)
    }

    @Test
    fun reassessmentDoesNotExtendAnOldCameraMeasurement() {
        val token = prepare()
        now += 8_700L
        val wrapped = readiness(cameraMeasuredAt = token.readiness.assessedAtElapsedRealtimeMs)
        assertFalse(wrapped.isReady)
        lifecycle.handle(WalkSessionEvent.StartRequested(token, wrapped))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun freshAssessmentThatExpiresBeforePublicationCannotStart() {
        val token = prepare()
        now += 8_000L
        val fresh = readiness()
        now += 5_001L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, fresh))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun measurementFromAfterItsAssessmentCannotBecomeReadyMerelyByWaiting() {
        val token = prepare()
        now += 8_000L
        val invalidAtAssessment = readiness(cameraMeasuredAt = now + 500L)
        now += 500L
        assertFalse(invalidAtAssessment.isReady)
        assertTrue(invalidAtAssessment.isReadyAt(now))
        lifecycle.handle(WalkSessionEvent.StartRequested(token, invalidAtAssessment))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun actualCameraFailureCannotStartDespiteDeliveredNotice() {
        val token = prepare()
        now += 8_700L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness(cameraStatus = WalkSessionReadinessStatus.UNAVAILABLE)))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun evidenceFromAnotherEpochCannotStart() {
        val token = prepare()
        now += 8_700L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness(epoch = token.epoch.copy(recoveryGeneration = 1L))))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun differentModeCannotReuseTheDeliveredNotice() {
        rejectDifferentPlan { it.copy(mode = WalkSessionMode.DISTANCE_LIMITED) }
    }

    @Test
    fun differentActionCannotReuseTheDeliveredNotice() {
        rejectDifferentPlan { it.copy(action = WalkSessionAction.RESUME_WALK) }
    }

    @Test
    fun droppingTheEntryPermissionCheckCannotReuseTheDeliveredNotice() {
        rejectDifferentPlan { it.copy(checkEntryPermissions = false) }
    }

    private fun rejectDifferentPlan(change: (WalkSessionReadinessPlan) -> WalkSessionReadinessPlan) {
        val token = prepare()
        now += 8_700L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness(plan = change(token.readiness.plan))))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun freshSensorReadingsCannotExtendTheOriginalConfirmationLifetime() {
        val token = prepare()
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS + 1L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness()))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(null, lifecycle.snapshot().confirmationToken)
    }

    @Test
    fun originalConfirmationAgeBoundaryAndCameraAgeBoundaryRemainInclusive() {
        val token = prepare()
        now += WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness(cameraMeasuredAt = now - 5_000L)))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    @Test
    fun backwardsClockCannotStartWithFreshEvidence() {
        val token = prepare()
        now -= 1L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness()))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
    }

    @Test
    fun lateResponseAfterBackgroundDoesNotStart() {
        val token = prepare()
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        now += 8_700L
        lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness()))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertFalse(lifecycle.snapshot().isForeground)
    }

    @Test
    fun oldConfirmationCannotStartAReplacementCheckInTheSameEpoch() {
        val token = prepare()
        now += 8_700L
        val fresh = readiness()
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(fresh))
        lifecycle.handle(WalkSessionEvent.StartRequested(token, fresh))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(fresh, lifecycle.snapshot().readiness)
    }
}
