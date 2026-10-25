package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssObservationDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual estimator, admitted Main GPS publisher and quality gate under the current source fixture.
 * SDK callback delivery, Android Location conversion, Activity UI and audible output are not run. */
class MainActivityGnssDuplicateConsumptionBehaviorTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun acceptedRecoveryFixDeliveredAgainMustNotRevokeTheOriginalGpsPass() {
        val f = Fixture()
        f.publish(f.sample(1_000L))
        f.publish(f.sample(2_000L, accuracy = 30.0))
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.base.gate.latestRawObservation()?.status)
        assertEquals(2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())

        val recovery = f.sample(2_500L)
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, requireNotNull(f.publish(recovery)).gnssUpdate?.disposition)
        val passing = requireNotNull(f.base.evidence)
        assertEquals(EnvironmentEvidenceStatus.PASS, passing.status)
        assertEquals(2_500L, passing.observedAtElapsedRealtimeMs)
        assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())

        // Both SDK producers feed this same path; the second receipt must not become a new fault.
        val publications = f.evidencePublications
        val duplicate = f.publish(recovery.copy(receivedAtElapsedRealtimeMs = 2_501L))
        assertNull("The exact accepted sample must not be consumed by the estimator again", duplicate)
        assertEquals(publications, f.evidencePublications)
        assertEquals("Duplicate delivery must not revoke the accepted source-owned GPS evidence",
            EnvironmentEvidenceStatus.PASS, f.base.evidence?.status)
        assertSame(passing, f.base.evidence)
        assertSame(passing, f.base.gate.latestRawObservation())
        assertNull(f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(f.base.callback, f.base.field("locationCallback"))
        assertSame(f.base.binding, f.base.field("positioningCollectionBinding"))
    }

    @Test fun aGenuinelyNewerAcceptedFixPublishesItsOriginalMeasurementTime() {
        val f = Fixture()
        f.publish(f.sample(1_000L))
        val original = requireNotNull(f.base.evidence)
        val newer = requireNotNull(f.publish(f.sample(2_000L).copy(receivedAtElapsedRealtimeMs = 2_100L)))
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, newer.gnssUpdate?.disposition)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.evidence?.status)
        assertEquals(2_000L, f.base.evidence?.observedAtElapsedRealtimeMs)
        assertNotSame(original, f.base.evidence)
    }

    @Test fun changedContentAtTheSameTimeAndMockInputRemainRejected() {
        for (mock in listOf(false, true)) {
            val f = Fixture()
            val accepted = f.sample(1_000L)
            f.publish(accepted)
            val conflict = if (mock) accepted.copy(mock = true, receivedAtElapsedRealtimeMs = 1_001L)
                else accepted.copy(latitude = accepted.latitude + 0.0001, receivedAtElapsedRealtimeMs = 1_001L)
            val rejected = requireNotNull(f.publish(conflict))
            assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate?.disposition)
            assertEquals(if (mock) GnssHardRejectReason.MOCK else GnssHardRejectReason.OUT_OF_ORDER,
                rejected.gnssUpdate?.hardRejectReason)
            assertEquals(EnvironmentEvidenceStatus.FAIL, f.base.evidence?.status)
            assertEquals("TRUSTED_FIX_UNAVAILABLE", f.base.evidence?.detail)
        }
    }

    @Test fun duplicatePoorAccuracyDoesNotRenewTheOriginalFailureDeadline() {
        val f = Fixture()
        f.publish(f.sample(1_000L))
        val bad = f.sample(2_000L, accuracy = 30.0)
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, requireNotNull(f.publish(bad)).gnssUpdate?.disposition)
        val raw = requireNotNull(f.base.gate.latestRawObservation())
        val retained = requireNotNull(f.base.evidence)
        assertEquals(EnvironmentEvidenceStatus.FAIL, raw.status)
        assertEquals(EnvironmentEvidenceStatus.PASS, retained.status)
        assertEquals(2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(18_000L, GuidanceRecoveryTiming.GPS_GRACE_MS)
        val deadline = 2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertNull(f.publish(bad.copy(receivedAtElapsedRealtimeMs = 4_999L)))
        assertSame(raw, f.base.gate.latestRawObservation())
        assertSame(retained, f.base.evidence)
        assertEquals(1_000L, retained.observedAtElapsedRealtimeMs)
        assertEquals(2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.main.currentContinuousGpsEvidence(f.base.epoch, 11_000L)?.status)
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.main.currentContinuousGpsEvidence(f.base.epoch, 11_001L)?.status)
        assertEquals(deadline, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.main.currentContinuousGpsEvidence(f.base.epoch, deadline - 1L)?.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.base.main.currentContinuousGpsEvidence(f.base.epoch, deadline)?.status)
    }

    @Test fun expiredFutureAndInvalidSignalWeightStillReachActualEstimatorRejection() {
        for ((receipt, multiplier, reason) in listOf(
            Triple(11_001L, 1.0, GnssHardRejectReason.STALE),
            Triple(999L, 1.0, GnssHardRejectReason.INVALID_TIME),
            Triple(1_001L, Double.NaN, GnssHardRejectReason.INVALID_ACCURACY),
        )) {
            val f = Fixture()
            val accepted = f.sample(1_000L)
            f.publish(accepted)
            val rejected = requireNotNull(f.publish(accepted.copy(
                receivedAtElapsedRealtimeMs = receipt, measurementNoiseMultiplier = multiplier)))
            assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate?.disposition)
            assertEquals(reason, rejected.gnssUpdate?.hardRejectReason)
            if (reason == GnssHardRejectReason.INVALID_ACCURACY) {
                assertEquals(EnvironmentEvidenceStatus.FAIL, f.base.gate.latestRawObservation()?.status)
                assertEquals("GPS_INVALID_ACCURACY", f.base.gate.latestRawObservation()?.detail)
                assertEquals(EnvironmentEvidenceStatus.PASS, f.base.evidence?.status)
                assertEquals(1_000L, f.base.evidence?.observedAtElapsedRealtimeMs)
                assertEquals(receipt + GuidanceRecoveryTiming.GPS_GRACE_MS,
                    f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
                assertNotEquals(EnvironmentEvidenceStatus.PASS,
                    f.base.main.currentContinuousGpsEvidence(f.base.epoch, 11_001L)?.status)
            } else {
                assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.evidence?.status)
            }
        }
    }

    @Test fun validSignalWeightRefreshDoesNotRepublishAnAcceptedRawSample() {
        val f = Fixture()
        val accepted = f.sample(1_000L)
        f.publish(accepted)
        val original = requireNotNull(f.base.evidence)
        val publications = f.evidencePublications
        assertNull(f.publish(accepted.copy(receivedAtElapsedRealtimeMs = 1_100L, measurementNoiseMultiplier = 4.0)))
        assertSame(original, f.base.evidence)
        assertEquals(publications, f.evidencePublications)
    }

    @Test fun retiredCollectionOrBackgroundCannotReuseItsAcceptedLedger() {
        for (field in listOf("locationCallbackGeneration", "isActivityForeground")) {
            val f = Fixture()
            val accepted = f.sample(1_000L)
            f.publish(accepted)
            val original = f.base.field(field)
            f.base.set(field, if (field == "locationCallbackGeneration") 4 else false)
            assertFalse(f.base.main.shouldSkipAcceptedGnssSample(f.fingerprint(accepted), 1_001L, 1.0))
            f.base.set(field, original)
            assertFalse("Returning to an old owner must not revive its ledger",
                f.base.main.shouldSkipAcceptedGnssSample(f.fingerprint(accepted), 1_001L, 1.0))
        }
    }

    @Test fun estimatorStillRejectsDuplicateWhenItHasNotPassedTheMainAcceptedSampleGate() {
        val f = Fixture()
        val accepted = f.sample(1_000L)
        f.publish(accepted)
        val duplicate = f.coordinator.observeGnss(accepted.copy(receivedAtElapsedRealtimeMs = 1_001L),
            f.coordinator.currentInputGeneration())
        assertEquals(GnssObservationDisposition.HARD_REJECTED, duplicate.gnssUpdate?.disposition)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, duplicate.gnssUpdate?.hardRejectReason)
        assertFalse(duplicate.singleOutlierAgainstFreshEstimate)
    }

    @Test fun originalAndroidFieldsAndOwnedMainConsumptionAreConnected() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val handle = NavigationPolicySource.observation(source)
        assertTrue(MainActivityRefactorSource.member(source, "handleLocationUpdate")
            .contains("positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"))
        val actualPositioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(actualPositioning.contains("val scope = positions.scope()"))
        assertTrue(actualPositioning.contains("val sources = positions.sources()"))
        assertTrue(actualPositioning.contains("val mounting = positions.mounting()"))
        assertTrue(actualPositioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertEquals(1, Regex(Regex.escape("positions = PositioningAssemblyBindings(")).findAll(navigationFactory).count())
        val positioningPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "positions = PositioningAssemblyBindings(")
        assertEquals(1, Regex(Regex.escape("mounting = { object : PositioningSessionController.Mounting")).findAll(navigationFactory).count())
        val mountingPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "mounting = { object : PositioningSessionController.Mounting")
        assertTrue(positioningPorts.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(positioningPorts.contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        val skip = handle.indexOf("shouldSkipAcceptedGnssSample(")
        val estimate = handle.indexOf("binding.coordinator.observeGnss(")
        val publish = handle.indexOf("recordPositioningGpsObservation(")
        val record = handle.indexOf("recordAcceptedGnssSample(")
        assertTrue(skip >= 0 && estimate > skip && publish > estimate && record > publish)
        val admission = handle.substring(0, skip)
        assertTrue(admission.contains("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"))
        assertTrue(admission.contains("val sampleFingerprint = location"))
        assertTrue(admission.contains("currentGnssQualitySnapshot?.measurementNoiseMultiplier ?: 1.0"))
        assertTrue(admission.contains("val acceptedSampleSource = gps().currentSource()"))
        assertTrue(handle.substring(publish, record).contains(
            "acceptedSampleSource != null && gps().currentSource() == acceptedSampleSource"))
        val cleanup = MainActivityRefactorSource.function(source, "private fun clearContinuousGpsHistory")
        assertTrue(cleanup.indexOf("acceptedGnssSamples.reset()") in 0 until cleanup.indexOf("if (continuousGpsSource == null) return"))
        for (hook in listOf("shouldSkipAcceptedGnssSample", "recordAcceptedGnssSample")) {
            val body = MainActivityRefactorSource.function(source, "internal fun $hook")
            assertTrue(body.contains("source.current()"))
            assertTrue(body.contains("acceptedGnssSamples.reset()"))
        }
        val adapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/AndroidGnssSampleFingerprint.kt").readText()
        for (field in listOf("elapsedRealtimeNanos = elapsedRealtimeNanos", "timeMillis = time", "provider = provider",
            "latitude = latitude", "longitude = longitude", "if (hasAccuracy()) accuracy.toDouble() else null",
            "if (hasAltitude()) altitude else null", "if (hasSpeed()) speed.toDouble() else null",
            "if (hasBearing()) bearing.toDouble() else null", "if (hasVerticalAccuracy())",
            "if (hasSpeedAccuracy())", "if (hasBearingAccuracy())", "hasElapsedRealtimeUncertaintyNanos()",
            "hasMslAltitude()", "hasMslAltitudeAccuracy()", "mock = mock")) assertTrue(adapter.contains(field))
        assertFalse(adapter.contains("SystemClock"))
        assertFalse(adapter.contains("measurementNoiseMultiplier"))
    }

    private class Fixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val coordinator = base.field("positioningCoordinator") as PositioningCoordinator
        var evidencePublications = 0

        fun sample(atMs: Long, accuracy: Double = 3.0) =
            GnssPositionObservation(37.5, 127.0, accuracy, atMs)

        fun fingerprint(observation: GnssPositionObservation) = GnssSampleFingerprint(
            elapsedRealtimeNanos = observation.elapsedRealtimeMs * 1_000_000L,
            timeMillis = 123_456L,
            provider = "fused",
            latitude = observation.latitude,
            longitude = observation.longitude,
            horizontalAccuracyM = observation.horizontalAccuracyM,
            mock = observation.mock,
        )

        fun publish(observation: GnssPositionObservation): PositioningSnapshot? {
            base.now = observation.receivedAtElapsedRealtimeMs
            val sample = fingerprint(observation)
            if (base.main.shouldSkipAcceptedGnssSample(sample, base.now, observation.measurementNoiseMultiplier)) return null
            val snapshot = coordinator.observeGnss(observation, coordinator.currentInputGeneration())
            base.main.recordPositioningGpsObservation(snapshot, observation.elapsedRealtimeMs,
                observation.horizontalAccuracyM, { base.now }, { evidencePublications += 1 })
            base.main.recordAcceptedGnssSample(sample, snapshot.gnssUpdate?.disposition, base.now,
                observation.measurementNoiseMultiplier)
            return snapshot
        }
    }
}
