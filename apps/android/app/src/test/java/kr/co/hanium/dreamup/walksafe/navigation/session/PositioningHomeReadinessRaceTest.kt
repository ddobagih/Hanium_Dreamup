package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import com.google.android.gms.location.LocationResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import org.junit.Assert.*
import org.junit.Test

/** Real positioning/GPS evidence producers. SDK inputs are controlled; Main HOME admission is separate. */
class PositioningHomeReadinessRaceTest {
    @Test fun registeredFusedCallbackCapturesOriginalSdkMeasurementBeforePublishingReadiness() {
        val f = Fixture()
        val location = ControlledLocation(at(1_000L).copy(elapsedRealtimeNanos = 1_000_000_123L))
        f.base.deliver(location, 1_001L)
        assertTrue(location.reads > 0)
        assertEquals(1_000_000_123L, f.positioning.gpsMotionInputs?.evidence?.measurementElapsedRealtimeNanos)
        assertEquals(1_000L, f.positioning.currentRouteLocationOrNull()?.elapsedRealtimeMs)
        val originalEvidence = f.base.gpsEvidenceStore.gpsEvidence
        assertEquals(EnvironmentEvidenceStatus.PASS, originalEvidence?.status)
        f.base.deliver(location, 2_000L)
        assertSame(originalEvidence, f.base.gpsEvidenceStore.gpsEvidence)
        assertEquals(listOf(1_000L), f.base.gpsObservationTimes)
    }

    @Test fun sdkCaptureCannotPublishAfterItsOwnerRetiresDuringAGetter() {
        val f = Fixture()
        val location = ControlledLocation(at(1_000L)) {
            f.base.owner = f.base.owner.copy(actorId = "retired-during-capture", gatewayGeneration = 2L)
        }
        f.base.deliver(location, 1_000L)
        assertTrue("The admitted SDK copy actually began", location.reads > 0)
        assertNull(f.positioning.trustedLocation)
        assertNull(f.positioning.gpsMotionInputs)
        assertNull(f.base.gpsEvidenceStore.gpsEvidence)
        assertTrue(f.base.gpsObservationTimes.isEmpty())
    }

    @Test fun retiredRegisteredCallbackDoesNotReadALateSdkMeasurement() {
        val f = Fixture()
        val retired = requireNotNull(f.base.registeredCallback)
        assertTrue(f.positioning.stopLocationUpdates())
        f.positioning.startLocationUpdatesIfAllowed()
        val location = ControlledLocation(at(2_000L))
        retired.onLocationResult(LocationResult.create(listOf(location)))
        assertEquals(0, location.reads)
        assertNull(f.base.gpsEvidenceStore.gpsEvidence)
        f.base.deliver(location, 2_000L)
        assertTrue(location.reads > 0)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
    }

    @Test fun negativeSdkNanosecondsCannotPublishThroughTheRegisteredFusedCallback() {
        val f = Fixture()
        f.base.deliver(ControlledLocation(at(1_000L).copy(elapsedRealtimeNanos = -1L)), 1_000L)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
    }

    @Test fun subscriptionWithoutARealMeasurementCannotPublishGpsReadiness() {
        val f = Fixture()
        assertNotNull(f.positioning.binding)
        assertNotNull(f.positioning.callback)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertNull(f.base.gpsEvidenceStore.gpsEvidence)
        assertTrue(f.base.gpsObservationTimes.isEmpty())
        f.deliver(at(1_000L), 1_000L)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        assertEquals(1_000L, f.positioning.currentRouteLocationOrNull()?.elapsedRealtimeMs)
    }

    @Test fun negativeSubMillisecondNanosecondsCannotBecomeAValidZeroTimestamp() {
        for (nanos in listOf(-1L, -999_999L, -1_000_000L)) {
            val f = Fixture()
            f.deliver(at(1_000L).copy(elapsedRealtimeNanos = nanos), 1_000L)
            assertNull("original ns=$nanos", f.positioning.currentRouteLocationOrNull())
            assertNotEquals("original ns=$nanos", EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        }
    }

    @Test fun zeroAndPositiveSubMillisecondTimestampsKeepTheirOriginalAdmission() {
        for (nanos in listOf(0L, 1L, 999_999L, 1_000_000L)) {
            val f = Fixture()
            f.deliver(at(1_000L).copy(elapsedRealtimeNanos = nanos), 1_000L)
            assertEquals("original ns=$nanos", nanos / 1_000_000L, f.positioning.currentRouteLocationOrNull()?.elapsedRealtimeMs)
            assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        }
    }

    @Test fun futureExpiredMockOrInvalidFirstObservationCannotCreateReadiness() {
        val refused = listOf(
            at(1_001L) to 1_000L,
            at(1_000L) to 11_001L,
            at(1_000L).copy(mock = true) to 1_000L,
            at(1_000L).copy(horizontalAccuracyM = Double.NaN) to 1_000L,
            at(1_000L).copy(horizontalAccuracyM = null) to 1_000L,
            at(1_000L).copy(horizontalAccuracyM = 30.0) to 1_000L,
            at(1_000L).copy(latitude = Double.NaN) to 1_000L,
            at(1_000L).copy(longitude = 181.0) to 1_000L,
        )
        for ((sample, receipt) in refused) {
            val f = Fixture(); f.deliver(sample, receipt)
            assertNull("sample=$sample receipt=$receipt", f.positioning.currentRouteLocationOrNull())
            assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        }
    }

    @Test fun recoveryAfterMissingOrInvalidGpsRequiresAnActualNewGoodObservation() {
        for (accuracy in listOf<Double?>(null, Double.NaN, 30.0)) {
            val f = Fixture()
            f.deliver(at(1_000L).copy(horizontalAccuracyM = accuracy), 1_000L)
            assertNull(f.positioning.currentRouteLocationOrNull())
            assertEquals(1, f.base.gpsFreshRequests.size)
            f.base.gpsFreshRequests.single()(null)
            assertNull(f.positioning.currentRouteLocationOrNull())
            f.deliver(at(2_000L), 2_000L)
            assertEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
            assertEquals(2_000L, f.base.gpsEvidenceStore.gpsEvidence?.observedAtElapsedRealtimeMs)
            assertNotNull(f.positioning.currentRouteLocationOrNull())
            assertNull(f.base.gpsGate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }

    @Test fun repeatedOriginalMeasurementCannotExtendReadinessBeyondItsTtl() {
        val f = Fixture(); val sample = at(1_000L)
        f.deliver(sample, 1_000L)
        val original = f.base.gpsEvidenceStore.gpsEvidence
        for (receipt in listOf(1_001L, 4_000L, 7_000L, 10_999L, 11_000L)) {
            f.deliver(sample, receipt)
            assertEquals(1_000L, f.positioning.currentRouteLocationOrNull()?.elapsedRealtimeMs)
            assertSame(original, f.base.gpsEvidenceStore.gpsEvidence)
        }
        f.deliver(sample, 11_001L)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
        assertEquals(1_000L, original?.observedAtElapsedRealtimeMs)
    }

    @Test fun olderAndCompoundInvalidPacketsDoNotRenewOrRevokeTheNewerProof() {
        val f = Fixture(); f.deliver(at(11_000L), 11_000L)
        val original = f.base.gpsEvidenceStore.gpsEvidence
        val coordinate = f.positioning.trustedLocation
        val refused = listOf(at(10_999L), at(0L), at(1_000L).copy(horizontalAccuracyM = Double.NaN),
            at(1_000L).copy(horizontalAccuracyM = -1.0))
        for (sample in refused) {
            f.deliver(sample, 11_001L)
            assertSame(original, f.base.gpsEvidenceStore.gpsEvidence)
            assertSame(coordinate, f.positioning.currentRouteLocationOrNull())
            assertEquals(11_000L, coordinate?.elapsedRealtimeMs)
        }
    }

    @Test fun oneLargeJumpNeverPublishesItsCoordinatesAndRepeatedDistinctJumpsRemainHardFailure() {
        val f = Fixture(); f.deliver(at(1_000L), 1_000L); f.deliver(at(2_000L), 2_000L)
        val original = f.positioning.trustedLocation
        f.deliver(at(3_000L).copy(latitude = 37.6), 3_000L)
        assertSame(original, f.positioning.trustedLocation)
        assertEquals(37.5, requireNotNull(f.positioning.rawRouteLocation).latitude, 0.0)
        f.deliver(at(4_000L).copy(latitude = 37.7), 4_000L)
        assertSame("two fast rejected fixes remain one bounded burst", original, f.positioning.trustedLocation)
        assertEquals(37.5, requireNotNull(f.positioning.rawRouteLocation).latitude, 0.0)
        f.deliver(at(6_000L).copy(latitude = 37.7001), 6_000L)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.base.gpsEvidenceStore.gpsEvidence?.status)
    }

    @Test fun permissionDenialRejectsQueuedPacketsAndRestorationNeedsCurrentCollection() {
        val f = Fixture(); f.deliver(at(1_000L), 1_000L)
        val evidence = f.base.gpsEvidenceStore.gpsEvidence
        f.base.collectionAllowed = false
        f.deliver(at(2_000L), 2_000L)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertSame(evidence, f.base.gpsEvidenceStore.gpsEvidence)
        assertTrue(f.positioning.stopLocationUpdates())
        f.base.collectionAllowed = true
        f.positioning.startLocationUpdatesIfAllowed()
        f.deliver(at(3_000L), 3_000L) // deliberately uses the retired binding
        assertNull(f.positioning.currentRouteLocationOrNull())
        f.deliver(at(3_000L), 3_000L, requireNotNull(f.positioning.binding))
        assertEquals(3_000L, f.positioning.currentRouteLocationOrNull()?.elapsedRealtimeMs)
    }

    @Test fun backgroundEpochRetirementRejectsLateLocationBeforeEvidencePublication() {
        val f = Fixture(); f.deliver(at(1_000L), 1_000L)
        val evidence = f.base.gpsEvidenceStore.gpsEvidence
        f.base.g.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        f.deliver(at(2_000L), 2_000L)
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertSame(evidence, f.base.gpsEvidenceStore.gpsEvidence)
        assertEquals(listOf(1_000L), f.base.gpsObservationTimes)
    }

    @Test fun delayedExpiryCleanupCannotEraseARecoveryThatArrivedBeforeTheUiTask() {
        val f = Fixture(); f.deliver(at(1_000L), 1_000L)
        f.base.now = 11_001L
        assertNull(f.positioning.currentRouteLocationOrNull())
        assertEquals(1, f.base.ui.size)
        f.deliver(at(11_002L), 11_002L)
        val recovered = requireNotNull(f.positioning.currentRouteLocationOrNull())
        f.base.ui.removeAt(0).invoke()
        assertSame(recovered, f.positioning.currentRouteLocationOrNull())
    }

    private class Fixture {
        val base = PositioningSessionControllerTest.Fixture(withGpsSource = true).apply {
            navigationAllowed = false
            start()
        }
        val positioning get() = base.controller
        val original = requireNotNull(positioning.binding)
        fun deliver(sample: GnssSampleFingerprint, receipt: Long, binding: PositioningCollectionBinding = original) {
            base.now = receipt; base.g.now = receipt
            positioning.handleLocationObservation(PositioningLocationObservation(sample, receipt),
                binding.owner, binding.locationGeneration, binding, {}, { _, _ -> })
        }
    }

    /** SDK values are controlled; the registered callback, adapter and publication policy are real. */
    private class ControlledLocation(
        private val sample: GnssSampleFingerprint,
        private val onAccuracyRead: () -> Unit = {},
    ) : Location("controlled-fused") {
        var reads = 0
        override fun getElapsedRealtimeNanos() = sample.elapsedRealtimeNanos.also { reads++ }
        override fun getTime() = sample.timeMillis
        override fun getProvider() = sample.provider
        override fun getLatitude() = sample.latitude
        override fun getLongitude() = sample.longitude
        override fun hasAccuracy() = sample.horizontalAccuracyM != null
        override fun getAccuracy() = requireNotNull(sample.horizontalAccuracyM).toFloat().also { onAccuracyRead() }
        override fun hasAltitude() = false
        override fun hasVerticalAccuracy() = false
        override fun hasSpeed() = false
        override fun hasSpeedAccuracy() = false
        override fun hasBearing() = false
        override fun hasBearingAccuracy() = false
        override fun hasElapsedRealtimeUncertaintyNanos() = false
        override fun hasMslAltitude() = false
        override fun hasMslAltitudeAccuracy() = false
    }

    companion object {
        private fun at(ms: Long) = GnssSampleFingerprint(ms * 1_000_000L, 123L, "fused", 37.5, 127.0, 3.0, false)
    }
}
