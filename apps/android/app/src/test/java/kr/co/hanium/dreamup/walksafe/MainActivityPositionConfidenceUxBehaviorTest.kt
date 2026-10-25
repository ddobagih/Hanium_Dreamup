package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main admission and completion callbacks; these are not physical GPS/audio measurements. */
class MainActivityPositionConfidenceUxBehaviorTest {
    @Test fun acceptedQueueDoesNotCommitAndActualDoneCommitsOnce() {
        val main = MainActivity()
        var delivered = 0
        var done: (() -> Unit)? = null
        assertTrue(main.submitPositionConfidenceAnnouncement("위치를 확인하고 있습니다", { true },
            { _, current, completed -> assertTrue(current()); done = completed; true }) { delivered++ })
        assertEquals(0, delivered)
        done!!.invoke()
        done!!.invoke()
        assertEquals(1, delivered)
    }

    @Test fun oldCollectionOrAnnouncementCannotCommitALateDone() {
        val main = MainActivity()
        var current = true
        var done: (() -> Unit)? = null
        main.submitPositionConfidenceAnnouncement("위치 안내", { current },
            { _, _, completed -> done = completed; true }) { fail("old owner") }
        current = false
        done!!.invoke()
    }

    @Test fun rejectedOrAlreadyStaleRequestIsNotDelivered() {
        val main = MainActivity()
        assertFalse(main.submitPositionConfidenceAnnouncement("위치 안내", { true },
            { _, _, _ -> false }) { fail("not delivered") })
        assertFalse(main.submitPositionConfidenceAnnouncement("위치 안내", { false },
            { _, _, _ -> fail("old owner"); false }) { fail("old owner") })
    }

    @Test fun generalGuidanceEligibilityAllowsLowButRejectsUnavailableAndInvalidTime() {
        val main = MainActivity()
        assertTrue(main.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(PositionQuality.LOW)))
        assertFalse(main.mayPublishPositioningRouteFix(PositionQuality.UNAVAILABLE, decision(PositionQuality.UNAVAILABLE)))
        assertTrue(main.mayPublishPositioningRouteFix(PositionQuality.HIGH, decision(PositionQuality.HIGH)))
        assertTrue(main.mayPublishPositioningRouteFix(PositionQuality.MEDIUM, decision(PositionQuality.MEDIUM)))
        assertFalse(main.mayPublishPositioningRouteFix(PositionQuality.HIGH,
            decision(PositionQuality.HIGH).copy(failureReason = PositionConfidenceFailureReason.INVALID_TIME)))
    }

    @Test fun actualMainGpsGateKeepsSixPeriodOnsetButNeverExtendsOriginalEvidenceTtl() {
        val main = MainActivity()
        val gate = MainActivity::class.java.getDeclaredField("runtimeGpsQualityGate").apply {
            isAccessible = true
        }.get(main) as RuntimeMeasuredQualityGate
        val pass = evidence(1_000L)
        assertEquals(pass, gate.observe(pass, 1_000L))
        val poor = evidence(2_000L).copy(status = EnvironmentEvidenceStatus.FAIL,
            detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
        assertEquals(pass, gate.observe(poor, 2_000L))
        val deadline = 2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertEquals(20_000L, deadline)
        assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        listOf(4_999L, 5_001L, 8_000L, 10_999L, 11_000L).forEach { now ->
            assertEquals(pass, gate.current(now))
        }
        assertEquals(poor, gate.current(11_001L))
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, gate.gpsRecoveryStage(11_001L))
        assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        listOf(14_000L, 17_000L).forEach { now ->
            assertEquals(poor.copy(observedAtElapsedRealtimeMs = now),
                gate.observe(poor.copy(observedAtElapsedRealtimeMs = now), now))
            assertEquals(deadline, gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, gate.gpsRecoveryStage(deadline - 1L))
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, gate.gpsRecoveryStage(deadline))
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(1_000L, pass.observedAtElapsedRealtimeMs)
    }

    @Test fun actualMainGpsGateDoesNotBorrowStaleOrInvalidEvidenceForTheLongerGrace() {
        val main = MainActivity()
        val gate = MainActivity::class.java.getDeclaredField("runtimeGpsQualityGate").apply {
            isAccessible = true
        }.get(main) as RuntimeMeasuredQualityGate
        val pass = evidence(1_000L).copy(maximumEvidenceAgeMs = 1_000L)
        gate.observe(pass, 1_000L)
        val poor = evidence(1_100L).copy(status = EnvironmentEvidenceStatus.FAIL,
            detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
        assertEquals(pass, gate.observe(poor, 1_100L))
        assertEquals(poor, gate.current(2_001L))
    }

    @Test fun availabilityPredictionDuringShortQualityDipRetainsOnlyTheOriginalFreshFix() {
        val main = MainActivity()
        val position = TrustedLocation(37.0, 127.0, 5f, 1_000L)
        val pending = decision(PositionQuality.UNAVAILABLE).copy(
            transition = PositionConfidenceTransition.DEGRADATION_PENDING,
            degradationPendingSinceElapsedRealtimeMs = 2_000L,
        )
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, pending, 4_999L))
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, pending, 5_001L))
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, pending, 8_000L))
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, pending, 10_999L))
        assertTrue(main.retainFreshPositionOnAvailabilityLoss(position, pending, 11_000L))
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, pending, 999L))
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, pending, 11_001L))
        assertFalse(main.retainFreshPositionOnAvailabilityLoss(position, pending.copy(guidancePaused = true), 3_000L))
        assertEquals(1_000L, position.elapsedRealtimeMs)
    }

    private fun evidence(atMs: Long) = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.GPS_QUALITY,
        status = EnvironmentEvidenceStatus.PASS,
        detail = "GPS_QUALITY_PASSED",
        epoch = WalkRuntimeEpoch("gps-ux-test", 1L),
        observedAtElapsedRealtimeMs = atMs,
        measurementProfileId = "gps-ux-profile",
        maximumEvidenceAgeMs = 10_000L,
    )

    private fun decision(quality: PositionQuality) = PositionConfidenceDecision(
        quality = quality, previousQuality = PositionQuality.HIGH,
        transition = PositionConfidenceTransition.STABLE, guidancePaused = false,
        degradedEpisodeId = null, consecutiveHighRecoverySamples = 0,
    )
}
