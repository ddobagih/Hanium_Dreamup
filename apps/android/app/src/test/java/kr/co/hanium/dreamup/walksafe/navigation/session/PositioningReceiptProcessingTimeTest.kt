package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceFailureReason
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import org.junit.Assert.*
import org.junit.Test

/** Test-owned inputs keep measurement, callback receipt and Main-consumption clocks distinct. */
class PositioningReceiptProcessingTimeTest {
    @Test fun watchdogBetweenReceiptAndMainConsumptionDoesNotInvalidateANewGoodFix() {
        val f = Fixture()
        f.deliver(at(1_000L), receipt = 1_000L, processing = 1_000L)
        f.deliver(at(1_500L), receipt = 1_500L, processing = 1_500L)
        assertFalse(f.base.controller.coordinator.evaluate(2_001L).confidence.guidancePaused)

        // The callback was received before the watchdog, then consumed by Main afterwards.
        f.deliver(at(2_000L), receipt = 2_000L, processing = 2_010L)

        val current = f.base.controller.coordinator.evaluate(2_010L)
        assertFalse("A fresh input must use consumption time for policy evaluation", current.confidence.guidancePaused)
        assertNull(current.confidence.failureReason)
        assertNull(current.confidence.announcementRequest)
        assertEquals(2_000L, f.base.controller.currentRouteLocationOrNull()?.elapsedRealtimeMs)
        assertEquals(2_000L, f.base.controller.rawRouteLocation?.elapsedRealtimeMs)
        assertEquals(2_000L, f.base.gpsEvidenceStore.gpsEvidence?.observedAtElapsedRealtimeMs)
    }

    @Test fun rejectedJumpProcessedAfterReceiptRetainsTheOriginalFixAndFirstFailureDeadline() {
        val f = Fixture()
        f.deliver(at(1_000L), 1_000L, 1_000L)
        f.deliver(at(2_000L), 2_000L, 2_000L)
        val trusted = requireNotNull(f.base.controller.trustedLocation)
        val raw = requireNotNull(f.base.controller.rawRouteLocation)

        f.deliver(at(3_000L).copy(latitude = 37.6), receipt = 3_000L, processing = 3_008L)

        assertSame("A refused jump must not erase the retained fix through a past gate query",
            trusted, f.base.controller.trustedLocation)
        assertSame(raw, f.base.controller.rawRouteLocation)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsGate.current(3_008L)?.status)
        val deadline = requireNotNull(f.base.gpsGate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(3_008L + GuidanceRecoveryTiming.GPS_GRACE_MS, deadline)
        assertEquals(2_000L, trusted.elapsedRealtimeMs)
        assertEquals(37.5, raw.latitude, 0.0)

        f.deliver(at(3_300L).copy(latitude = 37.6), receipt = 3_300L, processing = 3_308L)
        assertSame(trusted, f.base.controller.trustedLocation)
        assertSame(raw, f.base.controller.rawRouteLocation)
        assertEquals(deadline, f.base.gpsGate.pendingFailureDeadlineElapsedRealtimeMs())

        // Distinct rejected measurements spanning three seconds are still a hard fault.
        f.deliver(at(6_000L).copy(latitude = 37.6), receipt = 6_000L, processing = 6_008L)
        assertNull(f.base.controller.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsGate.current(6_008L)?.status)
    }

    @Test fun clockAdvancingWithinTheCallbackCannotRewindTheQualityGate() {
        val f = Fixture()
        f.deliver(at(1_000L), 1_000L, 1_000L)
        f.deliver(at(2_000L), 2_000L, 2_000L)
        val original = requireNotNull(f.base.controller.trustedLocation)

        f.deliver(at(3_000L).copy(latitude = 37.6), receipt = 3_000L, processing = 3_008L) {
            // Metadata effects run after coordinator processing and before measured-gate recording.
            f.base.now = 3_013L
            f.base.g.now = 3_013L
        }

        assertSame(original, f.base.controller.trustedLocation)
        assertEquals(2_000L, f.base.controller.rawRouteLocation?.elapsedRealtimeMs)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsGate.current(3_013L)?.status)
        assertEquals(3_013L + GuidanceRecoveryTiming.GPS_GRACE_MS,
            f.base.gpsGate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun receiptInTheFutureOfActualProcessingCannotPublishAValidMeasurement() {
        val f = Fixture()
        f.deliver(at(1_000L), receipt = 1_100L, processing = 1_050L)
        assertNull(f.base.controller.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        assertNull("Invalid receipt must not initialize the estimator",
            f.base.controller.coordinator.evaluate(1_100L).filtered)
    }

    @Test fun processingAfterAFutureMeasurementDoesNotMakeItsOriginalReceiptValid() {
        val f = Fixture()
        f.deliver(at(2_001L), receipt = 2_000L, processing = 2_010L)
        assertNull(f.base.controller.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
    }

    @Test fun sampleAlreadyExpiredAtReceiptStaysRejectedAtConsumption() {
        val f = Fixture()
        f.deliver(at(1_000L), receipt = 11_001L, processing = 11_010L)
        assertNull(f.base.controller.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
    }

    @Test fun freshReceiptCannotRenewASampleThatExpiredWhileWaitingForMain() {
        val f = Fixture()
        f.deliver(at(1_000L), receipt = 1_000L, processing = 11_001L)
        assertNull(f.base.controller.currentRouteLocationOrNull())
        val snapshot = f.base.controller.coordinator.evaluate(11_001L)
        assertTrue(snapshot.confidence.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, snapshot.confidence.failureReason)
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsGate.current(11_001L)?.status)
    }

    @Test fun duplicateReceiptDoesNotRenewOriginalMeasurementOrEvidenceAtProcessingTime() {
        val f = Fixture()
        val sample = at(1_000L)
        f.deliver(sample, 1_000L, 1_010L)
        val original = f.base.gpsEvidenceStore.gpsEvidence
        val trusted = requireNotNull(f.base.controller.trustedLocation)
        f.deliver(sample, receipt = 2_000L, processing = 2_010L)
        assertSame(trusted, f.base.controller.trustedLocation)
        assertSame(original, f.base.gpsEvidenceStore.gpsEvidence)
        assertEquals(listOf(1_000L), f.base.gpsObservationTimes)
        assertEquals(1_000L, trusted.elapsedRealtimeMs)
        f.base.now = 11_001L
        assertNull(f.base.controller.currentRouteLocationOrNull())
        assertTrue(f.base.controller.coordinator.evaluate(11_001L).confidence.guidancePaused)
    }

    @Test fun actualPolicyClockRollbackStillFailsClosed() {
        val f = Fixture()
        f.deliver(at(1_000L), 1_000L, 1_000L)
        f.base.controller.coordinator.evaluate(2_001L)
        val backwards = f.base.controller.coordinator.evaluate(2_000L).confidence
        assertTrue(backwards.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, backwards.failureReason)
    }

    private class Fixture {
        val base = PositioningSessionControllerTest.Fixture(withGpsSource = true).apply {
            navigationAllowed = false
            start()
        }
        private val binding = requireNotNull(base.controller.binding)
        fun deliver(sample: GnssSampleFingerprint, receipt: Long, processing: Long,
            updateOrientationReference: () -> Unit = {}) {
            base.now = processing
            base.g.now = processing
            base.controller.handleLocationObservation(PositioningLocationObservation(sample, receipt),
                binding.owner, binding.locationGeneration, binding, updateOrientationReference, { _, _ -> })
        }
    }

    companion object {
        private fun at(ms: Long) = GnssSampleFingerprint(ms * 1_000_000L, 123L,
            "test-owned-clock", 37.5, 127.0, 3.0, false)
    }
}
