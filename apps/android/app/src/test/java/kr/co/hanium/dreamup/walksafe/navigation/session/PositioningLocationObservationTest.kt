package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssFrequencySummary
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.DualFrequencyObservationState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.SignalEnvironmentRisk
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Actual collection/estimator policy; no Android Location construction or SDK version mutation. */
class PositioningLocationObservationTest {
    @Test fun acceptedObservationPreservesOriginalNanosecondsAndCoherentMotionFields() {
        val f = Fixture()
        val sample = sample().copy(elapsedRealtimeNanos = 1_000_900_001L,
            speedMps = 1.25, speedAccuracyMps = 0.125, bearingDegrees = 91.0, bearingAccuracyDegrees = 4.0)
        val sdkBefore = android.os.Build.VERSION.SDK_INT
        f.deliver(sample, 1_250L)
        assertEquals(1_000L, f.controller.rawRouteLocation?.elapsedRealtimeMs)
        assertEquals(1_000L, f.controller.trustedLocation?.elapsedRealtimeMs)
        val motion = requireNotNull(f.controller.gpsMotionInputs)
        assertEquals(1_000_900_001L, motion.evidence.measurementElapsedRealtimeNanos)
        assertEquals(1_000L, motion.evidence.observedAtElapsedRealtimeMs)
        assertEquals(1.25, requireNotNull(motion.speed).speedMps, 0.0)
        assertEquals(0.125, requireNotNull(motion.speed?.accuracyMps), 0.0)
        assertEquals(91.0, requireNotNull(motion.course).headingDegreesTrueNorth, 0.0)
        assertEquals(4.0, requireNotNull(motion.course?.accuracyDegrees), 0.0)
        assertFalse(motion.evidence.isMock)
        assertEquals(1, f.references)
        assertEquals(sdkBefore, android.os.Build.VERSION.SDK_INT)
    }

    @Test fun receiptTimeCannotBeReplacedByProcessingClockForStaleOrFutureMeasurement() {
        for (receipt in listOf(999L, 11_001L)) {
            val f = Fixture()
            f.deliver(sample(), receipt, processingAt = 1_100L)
            assertNull("receipt=$receipt", f.controller.trustedLocation)
            assertNull("receipt=$receipt", f.controller.gpsMotionInputs)
        }
    }

    @Test fun mockAndInvalidAccuracyNeverPublishAnAcceptedPosition() {
        val inputs = listOf(sample().copy(mock = true), sample().copy(horizontalAccuracyM = Double.NaN),
            sample().copy(horizontalAccuracyM = -1.0), sample().copy(horizontalAccuracyM = null))
        for (input in inputs) {
            val f = Fixture()
            f.deliver(input, 1_100L)
            assertNull("input=$input", f.controller.trustedLocation)
            assertNull("input=$input", f.controller.gpsMotionInputs)
            if (input.mock) assertEquals(0, f.references)
        }
    }

    @Test fun actorGatewayAndWalkOwnerRetirementRejectsBeforeAnyPolicyEffect() {
        val changes: List<(LocationCollectionOwner) -> LocationCollectionOwner> = listOf(
            { it.copy(actorId = "other") },
            { it.copy(gatewayGeneration = it.gatewayGeneration + 1) },
            { it.copy(fieldWalkEpoch = WalkRuntimeEpoch("other-walk", 1L)) },
            { it.copy(fieldScopeId = "other-field") },
        )
        for (change in changes) {
            val f = Fixture()
            f.base.owner = change(f.binding.owner)
            f.deliver(sample(), 1_100L)
            f.assertNotConsumed()
        }
    }

    @Test fun collectionIdentityAndBothGenerationsMustStillBeCurrent() {
        val callback = Fixture()
        callback.deliver(sample(), 1_100L, locationGeneration = callback.binding.locationGeneration + 1)
        callback.assertNotConsumed()
        val replacement = Fixture()
        val old = replacement.binding
        replacement.deliver(sample(), 1_100L, binding = PositioningCollectionBinding(old.coordinator,
            old.inputGeneration, old.owner, old.locationGeneration, old.startedAtElapsedRealtimeMs))
        replacement.assertNotConsumed()
        val generation = Fixture()
        generation.controller.coordinator.reset()
        generation.deliver(sample(), 1_100L)
        generation.assertNotConsumed()
    }

    @Test fun stoppedCollectionDoesNotAcceptAnImmutablePacketQueuedForItsOldOwner() {
        val f = Fixture()
        assertTrue(f.controller.stopLocationUpdates())
        f.deliver(sample(), 1_100L)
        f.assertNotConsumed()
    }

    @Test fun qualityWeightAndReportedRadiusRemainSeparate() {
        val f = Fixture(fieldLogging = true)
        val quality = GnssQualitySnapshot(1_000_000_000L, 1_000_000_000L, 1, 4, 20.0, 19.0,
            0.0, 0.0, GnssFrequencySummary(4, 0, 0, 4, 4, DualFrequencyObservationState.SINGLE_FREQUENCY_OBSERVED),
            SignalEnvironmentRisk.HIGH, 0.8, 4.0, false)
        f.deliver(sample(), 1_100L, quality = quality)
        val raw = requireNotNull(f.tracedSnapshots.single().raw)
        assertEquals(3.0, requireNotNull(raw.reportedHorizontalAccuracyM), 0.0)
        assertEquals(6.0, requireNotNull(raw.effectiveHorizontalAccuracyM), 0.0)
        assertSame(quality, f.controller.gnssQuality)
    }

    @Test fun acceptedDuplicateCannotRefreshOriginalTimestampOrPublishAgain() {
        val f = Fixture(withGpsSource = true)
        val original = sample().copy(elapsedRealtimeNanos = 1_000_999_999L)
        f.deliver(original, 1_100L)
        val trusted = requireNotNull(f.controller.trustedLocation)
        val motion = requireNotNull(f.controller.gpsMotionInputs)
        f.deliver(original, 1_200L)
        assertSame(trusted, f.controller.trustedLocation)
        assertSame(motion, f.controller.gpsMotionInputs)
        assertEquals(listOf(1_000L), f.base.gpsObservationTimes)
        assertEquals(1, f.references)
    }

    @Test fun nanosecondOrderWithinSameMillisecondPreservesNewerGeographicMetadata() {
        val f = Fixture(withGpsSource = true)
        f.deliver(sample().copy(elapsedRealtimeNanos = 1_000_999_999L), 1_100L)
        val trusted = requireNotNull(f.controller.trustedLocation)
        f.deliver(sample().copy(elapsedRealtimeNanos = 1_000_000_001L), 1_200L)
        assertSame(trusted, f.controller.trustedLocation)
        assertEquals(listOf(1_000L), f.base.gpsObservationTimes)
        assertEquals(1, f.references)
    }

    @Test fun lateInvalidAccuracyOrExpiredFixCannotEraseNewerAcceptedPosition() {
        val inputs = listOf(sample().copy(horizontalAccuracyM = Double.NaN),
            sample().copy(elapsedRealtimeNanos = 0L), sample())
        for (old in inputs) {
            val f = Fixture(withGpsSource = true)
            f.deliver(sample().copy(elapsedRealtimeNanos = 11_000_000_000L), 11_000L)
            val trusted = requireNotNull(f.controller.trustedLocation)
            val acceptedEvidence = requireNotNull(f.base.gpsEvidenceStore.gpsEvidence)
            assertEquals(EnvironmentEvidenceStatus.PASS, acceptedEvidence.status)
            f.deliver(old, 11_001L)
            assertSame("The real evidence gate must retain the newer original observation", acceptedEvidence,
                f.base.gpsEvidenceStore.gpsEvidence)
            assertNull(f.base.gpsGate.pendingFailureDeadlineElapsedRealtimeMs())
            assertSame("old=$old", trusted, f.controller.trustedLocation)
            assertEquals(listOf(11_000L), f.base.gpsObservationTimes)
            assertEquals(1, f.references)
        }
    }

    private class Fixture(withGpsSource: Boolean = false, fieldLogging: Boolean = false) {
        val base = PositioningSessionControllerTest.Fixture(withGpsSource = withGpsSource).apply {
            navigationAllowed = false
            fieldAllowed = fieldLogging
            start()
        }
        val controller get() = base.controller
        val binding = requireNotNull(controller.binding)
        var references = 0
        var traces = 0
        val tracedSnapshots = mutableListOf<PositioningSnapshot>()
        fun deliver(sample: GnssSampleFingerprint, receipt: Long, processingAt: Long = receipt,
            locationGeneration: Int = this.binding.locationGeneration,
            binding: PositioningCollectionBinding = this.binding, quality: GnssQualitySnapshot? = null) {
            base.now = processingAt
            base.g.now = processingAt
            controller.handleLocationObservation(PositioningLocationObservation(sample, receipt, quality),
                this.binding.owner, locationGeneration, binding,
                updateOrientationReference = { references++ }, appendGnssTrace = { snapshot, _ -> traces++; tracedSnapshots += snapshot })
        }
        fun assertNotConsumed() {
            assertNull(controller.coordinator.evaluate(base.now).raw)
            assertNull(controller.trustedLocation)
            assertNull(controller.gpsMotionInputs)
            assertEquals(0, references)
            assertEquals(0, traces)
        }
    }

    companion object {
        private fun sample() = GnssSampleFingerprint(1_000_000_000L, 123L, "fused", 37.5, 127.0, 3.0, false)
    }
}
