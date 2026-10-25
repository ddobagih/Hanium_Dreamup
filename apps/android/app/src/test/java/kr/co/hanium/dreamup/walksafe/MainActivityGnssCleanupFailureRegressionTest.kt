package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssFrequencyClassifier
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssObservationListener
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssObservationSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssObservationSubscription
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityObserver
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSignalEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real Main stop(false) and observer, with only platform subscription/cancellation effects faked. */
class MainActivityGnssCleanupFailureRegressionTest {
    @Test
    fun closedObserverWithPendingCancellationMustNotAcknowledgeMainCleanupSuccess() {
        for (failedFeed in listOf("status", "measurements")) {
            val fixture = fixture(failedFeed)

            val acknowledged = stopLocationUpdates(fixture.activity)

            assertPendingClosedObserver(fixture)
            assertFalse("$failedFeed native cancellation is still pending", acknowledged)
        }
    }

    @Test
    fun closedObserverWithPendingCancellationMustRemainTheSameMainCleanupTarget() {
        for (failedFeed in listOf("status", "measurements")) {
            val fixture = fixture(failedFeed)

            stopLocationUpdates(fixture.activity)

            assertPendingClosedObserver(fixture)
            assertSame("$failedFeed retry target must not be discarded", fixture.observer,
                field(fixture.activity, "gnssQualityObserver"))
        }
    }

    @Test
    fun pendingGnssCancellationStillRetiresCompassAndDiscardsTrustedPosition() {
        val fixture = fixture("status")

        stopLocationUpdates(fixture.activity)

        assertPendingClosedObserver(fixture)
        assertFalse(field(fixture.activity, "orientationLocationOwnerActive") as Boolean)
        assertNull(field(fixture.activity, "lastRouteCompassStatusKey"))
        assertNull(field(fixture.activity, "pendingRouteCompassRefresh"))
        assertNull(field(fixture.activity, "latestGnssQualitySnapshot"))
        for (name in listOf(
            "latestTrustedLocation", "latestRawRouteLocation", "latestNavigationTestLocation",
            "latestWalkingSpeedObservation", "latestGpsCourseObservation", "latestHeadingDeg",
            "lastPositionZuptAtElapsedMs", "lastMotionTelemetryAtMs",
        )) assertNull(name, field(fixture.activity, name))
        assertFalse(field(fixture.activity, "positionGuidancePaused") as Boolean)
        assertEquals(fixture.inputGeneration + 1L, fixture.coordinator.currentInputGeneration())
    }

    @Test
    fun closeFencesRetainedCallbacksEvenDuringFailedNativeCancellation() {
        val fixture = fixture("status")
        fixture.source.emitRetained(epoch(1_000_000_000L))
        assertTrue(fixture.snapshots.isNotEmpty())
        assertNotNull(field(fixture.activity, "latestGnssQualitySnapshot"))
        val beforeClose = fixture.snapshots.size
        fixture.source.duringCancellation = { fixture.source.emitRetained(epoch(2_000_000_000L)) }

        stopLocationUpdates(fixture.activity)

        assertPendingClosedObserver(fixture)
        assertEquals("Retirement must precede native cancellation callbacks", beforeClose, fixture.snapshots.size)
        fixture.source.emitRetained(epoch(3_000_000_000L))
        assertEquals(beforeClose, fixture.snapshots.size)
        assertNull(field(fixture.activity, "latestGnssQualitySnapshot"))
        assertEquals(0, fixture.observer.snapshot(3_000_000_000L).epochCount)
        assertNull(fixture.observer.snapshot(3_000_000_000L).latestEpochAtNanos)
    }

    @Test
    fun normalCloseClearsMainTargetAndRepeatedNullTargetCleanupDoesNotCancelAgain() {
        val fixture = fixture(null)
        assertFalse(fixture.observer.cleanupPending)
        assertEquals(0, fixture.source.statusCancelCalls)
        assertEquals(0, fixture.source.measurementCancelCalls)

        assertTrue(stopLocationUpdates(fixture.activity))

        assertFalse(fixture.observer.cleanupPending)
        assertNull(field(fixture.activity, "gnssQualityObserver"))
        assertEquals(1, fixture.source.statusCancelCalls)
        assertEquals(1, fixture.source.measurementCancelCalls)
        assertFalse(fixture.source.statusNativeActive)
        assertFalse(fixture.source.measurementNativeActive)
        assertTrue(stopLocationUpdates(fixture.activity))
        assertTrue(stopLocationUpdates(fixture.activity))
        assertEquals(1, fixture.source.statusCancelCalls)
        assertEquals(1, fixture.source.measurementCancelCalls)
        assertNull(field(fixture.activity, "gnssQualityObserver"))
    }

    @Test
    fun mainRetryAcknowledgesTheRetainedObserverOnlyAfterItsNativeCancellationSucceeds() {
        for (failedFeed in listOf("status", "measurements")) {
            val fixture = fixture(failedFeed)
            stopLocationUpdates(fixture.activity)
            assertPendingClosedObserver(fixture)

            assertTrue(stopLocationUpdates(fixture.activity))

            assertFalse("Main must retry the same pending observer before reporting success", fixture.observer.cleanupPending)
            assertNull(field(fixture.activity, "gnssQualityObserver"))
            assertEquals(if (failedFeed == "status") 2 else 1, fixture.source.statusCancelCalls)
            assertEquals(if (failedFeed == "measurements") 2 else 1, fixture.source.measurementCancelCalls)
            assertFalse(fixture.source.statusNativeActive)
            assertFalse(fixture.source.measurementNativeActive)
            assertTrue(stopLocationUpdates(fixture.activity))
            assertEquals(if (failedFeed == "status") 2 else 1, fixture.source.statusCancelCalls)
            assertEquals(if (failedFeed == "measurements") 2 else 1, fixture.source.measurementCancelCalls)
        }
    }

    @Test
    fun theSameRealObserverCanAcknowledgeItsRetryWithoutDuplicateCancellationAfterwards() {
        for (failedFeed in listOf("status", "measurements")) {
            val fixture = fixture(failedFeed)
            stopLocationUpdates(fixture.activity)
            assertPendingClosedObserver(fixture)

            // This comparator exercises the observer's existing retry contract directly.
            // The separate retention test checks whether Main keeps this owner for its own retry.
            fixture.observer.close()

            assertFalse(fixture.observer.cleanupPending)
            assertEquals(if (failedFeed == "status") 2 else 1, fixture.source.statusCancelCalls)
            assertEquals(if (failedFeed == "measurements") 2 else 1, fixture.source.measurementCancelCalls)
            assertFalse(fixture.source.statusNativeActive)
            assertFalse(fixture.source.measurementNativeActive)
            assertTrue(fixture.observer.start().observerClosed)
            assertEquals(1, fixture.source.statusSubscribeCalls)
            assertEquals(1, fixture.source.measurementSubscribeCalls)
            assertTrue(stopLocationUpdates(fixture.activity))
            assertNull(field(fixture.activity, "gnssQualityObserver"))
            fixture.observer.close()
            assertTrue(stopLocationUpdates(fixture.activity))
            assertEquals(if (failedFeed == "status") 2 else 1, fixture.source.statusCancelCalls)
            assertEquals(if (failedFeed == "measurements") 2 else 1, fixture.source.measurementCancelCalls)
        }
    }

    private fun assertPendingClosedObserver(fixture: Fixture) {
        assertTrue("Real observer must retain the failed cancellation", fixture.observer.cleanupPending)
        assertTrue(fixture.observer.start().observerClosed)
        assertEquals(1, fixture.source.statusCancelCalls)
        assertEquals(1, fixture.source.measurementCancelCalls)
        assertTrue(fixture.source.statusNativeActive || fixture.source.measurementNativeActive)
    }

    private fun fixture(failedFeed: String?): Fixture {
        val activity = MainActivity()
        val source = Source(
            statusFailures = if (failedFeed == "status") 1 else 0,
            measurementFailures = if (failedFeed == "measurements") 1 else 0,
        )
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = {
            snapshots += it
            setField(activity, "latestGnssQualitySnapshot", it)
        })
        val started = observer.start()
        assertTrue(started.statusRegistered)
        assertTrue(started.measurementsRegistered)
        setField(activity, "gnssQualityObserver", observer)
        setField(activity, "orientationLocationOwnerActive", true)
        setField(activity, "lastRouteCompassStatusKey", "old_location_heading")
        setField(activity, "pendingRouteCompassRefresh", Runnable { })
        setField(activity, "positionGuidancePaused", true)
        val location = TrustedLocation(37.0, 127.0, 3f, 1_000L)
        setField(activity, "latestTrustedLocation", location)
        setField(activity, "latestRawRouteLocation", location)
        setField(activity, "latestNavigationTestLocation", location)
        setField(activity, "latestWalkingSpeedObservation", WalkingSpeedObservation(1.0, 0.2, 1_000L))
        setField(activity, "latestGpsCourseObservation", HeadingObservation(45.0, 5.0, 1_000L))
        setField(activity, "latestHeadingDeg", 45f)
        setField(activity, "lastPositionZuptAtElapsedMs", 1_000L)
        setField(activity, "lastMotionTelemetryAtMs", 1_000L)
        val coordinator = field(activity, "positioningCoordinator") as PositioningCoordinator
        return Fixture(activity, source, observer, snapshots, coordinator, coordinator.currentInputGeneration())
    }

    private fun stopLocationUpdates(activity: MainActivity): Boolean {
        val lookup = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        val method = lookup.findVirtual(MainActivity::class.java, "stopLocationUpdates",
            MethodType.methodType(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        // Exceptions are genuine failures; no absent-API fallback or compile RED is used.
        return method.bindTo(activity).invokeWithArguments(listOf(false)) as Boolean
    }

    private fun field(activity: MainActivity, name: String): Any? =
        if (name == "lastMotionTelemetryAtMs") motionTelemetryField().get(positionFieldOwner(activity))
        else MainActivityControllerTestFixture.field(name).run { isAccessible = true; get(activity) }

    private fun setField(activity: MainActivity, name: String, value: Any?) {
        if (name == "lastMotionTelemetryAtMs") motionTelemetryField().set(positionFieldOwner(activity), value)
        else MainActivityControllerTestFixture.field(name).apply { isAccessible = true; set(activity, value) }
    }

    private fun positionFieldOwner(activity: MainActivity): Any =
        MainActivityControllerTestFixture.controller(activity, "positionFieldController")

    private fun motionTelemetryField() =
        kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldRecorderController::class.java
            .getDeclaredField("lastMotionTelemetryAtMs").apply { isAccessible = true }

    private fun epoch(atNanos: Long) = GnssSignalEpoch(atNanos, listOf(GnssSatelliteSignal(
        constellation = 1,
        svid = 1,
        carrierFrequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ,
        cn0DbHz = 35.0,
        usedInFix = true,
    )))

    private data class Fixture(
        val activity: MainActivity,
        val source: Source,
        val observer: GnssQualityObserver,
        val snapshots: List<GnssQualitySnapshot>,
        val coordinator: PositioningCoordinator,
        val inputGeneration: Long,
    )

    private class Source(private var statusFailures: Int, private var measurementFailures: Int) : GnssObservationSource {
        val cancellationFailure = IllegalStateException("temporary GNSS unregister failure")
        var statusSubscribeCalls = 0
        var measurementSubscribeCalls = 0
        var statusCancelCalls = 0
        var measurementCancelCalls = 0
        var statusNativeActive = false
        var measurementNativeActive = false
        var duringCancellation: (() -> Unit)? = null
        private val statusListeners = mutableListOf<GnssObservationListener>()
        private val measurementListeners = mutableListOf<GnssObservationListener>()

        override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription {
            statusSubscribeCalls += 1
            statusListeners += listener
            statusNativeActive = true
            return GnssObservationSubscription {
                statusCancelCalls += 1
                duringCancellation?.invoke()
                if (statusFailures > 0) { statusFailures -= 1; throw cancellationFailure }
                statusNativeActive = false
            }
        }

        override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription {
            measurementSubscribeCalls += 1
            measurementListeners += listener
            measurementNativeActive = true
            return GnssObservationSubscription {
                measurementCancelCalls += 1
                duringCancellation?.invoke()
                if (measurementFailures > 0) { measurementFailures -= 1; throw cancellationFailure }
                measurementNativeActive = false
            }
        }

        fun emitRetained(epoch: GnssSignalEpoch) {
            statusListeners.forEach { it.onObservation(epoch) }
            measurementListeners.forEach { it.onObservation(epoch) }
        }
    }
}
