package kr.co.hanium.dreamup.walksafe

import android.hardware.Sensor
import android.hardware.SensorEventListener
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldRecorderController
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AndroidPedestrianMotionTracker
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionSensorRegistration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main cleanup and motion adapter; the Earth/Android service fields stay uninitialized. */
class MainActivityLocationCleanupFailureRegressionTest {
    @Test
    fun failedMotionCloseKeepsItsTargetButRetiresTheLocationCompassOwner() {
        val fixture = fixture(unregisterFailures = 1)

        assertCleanupFailure(invokeStopLocationUpdates(fixture.activity), fixture.registration.failure)

        assertSame(fixture.tracker, field(fixture.activity, "pedestrianMotionTracker"))
        assertEquals(1, fixture.registration.unregisterCalls)
        assertEquals(1, fixture.registration.listeners.size)
        assertFalse(fixture.tracker.start())
        assertFalse(fixture.tracker.recordStep(1_100L))
        assertFalse(field(fixture.activity, "orientationLocationOwnerActive") as Boolean)
        assertNull(field(fixture.activity, "lastRouteCompassStatusKey"))
    }

    @Test
    fun failedMotionCloseStillDiscardsTrustedPositionAndResetsTheCoordinatorGeneration() {
        val fixture = fixture(unregisterFailures = 1)

        assertCleanupFailure(invokeStopLocationUpdates(fixture.activity), fixture.registration.failure)

        assertSame(fixture.tracker, field(fixture.activity, "pedestrianMotionTracker"))
        assertPositionDiscarded(fixture.activity)
        assertEquals(fixture.inputGeneration + 1L, fixture.coordinator.currentInputGeneration())
    }

    @Test
    fun successfulMotionCloseRemovesItsTargetAndRepeatedCleanupDoesNotUnregisterAgain() {
        val fixture = fixture(unregisterFailures = 0)

        assertCleanupSuccess(invokeStopLocationUpdates(fixture.activity))

        assertNull(field(fixture.activity, "pedestrianMotionTracker"))
        assertEquals(1, fixture.registration.unregisterCalls)
        assertTrue(fixture.registration.listeners.isEmpty())
        assertFalse(field(fixture.activity, "orientationLocationOwnerActive") as Boolean)
        assertNull(field(fixture.activity, "lastRouteCompassStatusKey"))
        assertPositionDiscarded(fixture.activity)
        assertEquals(fixture.inputGeneration + 1L, fixture.coordinator.currentInputGeneration())

        assertCleanupSuccess(invokeStopLocationUpdates(fixture.activity))
        assertEquals(1, fixture.registration.unregisterCalls)
        assertTrue(fixture.registration.listeners.isEmpty())
    }

    @Test
    fun failedMotionCloseCanRetryItsRetainedTargetWithoutAnotherNativeCleanupAfterAcknowledgement() {
        val fixture = fixture(unregisterFailures = 1)

        assertCleanupFailure(invokeStopLocationUpdates(fixture.activity), fixture.registration.failure)

        assertSame(fixture.tracker, field(fixture.activity, "pedestrianMotionTracker"))
        assertEquals(1, fixture.registration.unregisterCalls)
        assertEquals(1, fixture.registration.listeners.size)
        assertFalse(field(fixture.activity, "orientationLocationOwnerActive") as Boolean)
        assertNull(field(fixture.activity, "lastRouteCompassStatusKey"))
        assertPositionDiscarded(fixture.activity)
        assertEquals(fixture.inputGeneration + 1L, fixture.coordinator.currentInputGeneration())

        assertCleanupSuccess(invokeStopLocationUpdates(fixture.activity))

        assertNull(field(fixture.activity, "pedestrianMotionTracker"))
        assertEquals(2, fixture.registration.unregisterCalls)
        assertTrue(fixture.registration.listeners.isEmpty())

        assertCleanupSuccess(invokeStopLocationUpdates(fixture.activity))
        assertEquals(2, fixture.registration.unregisterCalls)
        assertTrue(fixture.registration.listeners.isEmpty())
    }

    private fun fixture(unregisterFailures: Int): Fixture {
        val activity = MainActivity()
        val registration = Registration(unregisterFailures)
        val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
        assertTrue(tracker.start())
        val coordinator = field(activity, "positioningCoordinator") as PositioningCoordinator
        val location = TrustedLocation(37.0, 127.0, 3f, 1_000L)
        setField(activity, "pedestrianMotionTracker", tracker)
        setField(activity, "orientationLocationOwnerActive", true)
        setField(activity, "lastRouteCompassStatusKey", "old_location_heading")
        setField(activity, "positionGuidancePaused", true)
        setField(activity, "latestTrustedLocation", location)
        setField(activity, "latestRawRouteLocation", location)
        setField(activity, "latestNavigationTestLocation", location)
        setField(activity, "latestWalkingSpeedObservation", WalkingSpeedObservation(1.0, 0.2, 1_000L))
        setField(activity, "latestGpsCourseObservation", HeadingObservation(45.0, 5.0, 1_000L))
        setField(activity, "latestHeadingDeg", 45f)
        setField(activity, "lastPositionZuptAtElapsedMs", 1_000L)
        val fieldController = positionFieldController(activity)
        PositionFieldRecorderController::class.java.getDeclaredField("lastMotionTelemetryAtMs").apply {
            isAccessible = true
            set(fieldController, 1_000L)
        }
        assertEquals(1_000L, positionFieldTelemetryAtMs(activity))
        return Fixture(activity, registration, tracker, coordinator, coordinator.currentInputGeneration())
    }

    private fun assertPositionDiscarded(activity: MainActivity) {
        listOf(
            "latestTrustedLocation",
            "latestRawRouteLocation",
            "latestNavigationTestLocation",
            "latestWalkingSpeedObservation",
            "latestGpsCourseObservation",
            "latestHeadingDeg",
            "lastPositionZuptAtElapsedMs",
        ).forEach { name -> assertNull(name, field(activity, name)) }
        assertNull("field-controller telemetry must be discarded by actual Main cleanup", positionFieldTelemetryAtMs(activity))
        assertFalse(field(activity, "positionGuidancePaused") as Boolean)
    }

    private fun invokeStopLocationUpdates(activity: MainActivity): CleanupInvocation {
        val lookup = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        // Use the existing private method now and retain the same condition after Boolean ack.
        val method = try {
            lookup.findVirtual(
                MainActivity::class.java,
                "stopLocationUpdates",
                MethodType.methodType(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
            )
        } catch (_: NoSuchMethodException) {
            lookup.findVirtual(
                MainActivity::class.java,
                "stopLocationUpdates",
                MethodType.methodType(Void.TYPE, Boolean::class.javaPrimitiveType),
            )
        }
        return CleanupInvocation(
            activity = activity,
            returnsBoolean = method.type().returnType() == Boolean::class.javaPrimitiveType,
            result = runCatching { method.bindTo(activity).invokeWithArguments(listOf(false)) },
        )
    }

    private fun assertCleanupFailure(invocation: CleanupInvocation, expectedFailure: Throwable) {
        if (invocation.returnsBoolean) {
            assertNull("Boolean cleanup must let its callers continue after a native failure", invocation.result.exceptionOrNull())
            assertEquals("failed native cleanup must not acknowledge success", false, invocation.result.getOrNull())
            assertSame(expectedFailure, field(invocation.activity, "lastLocationCleanupFailure"))
        } else {
            assertSame(
                "observe the injected unregister failure before testing the remaining cleanup",
                expectedFailure,
                invocation.result.exceptionOrNull(),
            )
        }
    }

    private fun assertCleanupSuccess(invocation: CleanupInvocation) {
        assertNull(invocation.result.exceptionOrNull())
        if (invocation.returnsBoolean) {
            assertEquals(true, invocation.result.getOrNull())
        } else {
            assertNull(invocation.result.getOrNull())
        }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).run {
            isAccessible = true
            get(activity)
        }

    private fun setField(activity: MainActivity, name: String, value: Any?) {
        MainActivityControllerTestFixture.field(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun positionFieldController(activity: MainActivity): PositionFieldRecorderController =
        (field(activity, "positionFieldController\$delegate") as Lazy<*>).value as PositionFieldRecorderController

    private fun positionFieldTelemetryAtMs(activity: MainActivity): Long? =
        PositionFieldRecorderController::class.java.getDeclaredField("lastMotionTelemetryAtMs").run {
            isAccessible = true
            get(positionFieldController(activity)) as Long?
        }

    private data class Fixture(
        val activity: MainActivity,
        val registration: Registration,
        val tracker: AndroidPedestrianMotionTracker,
        val coordinator: PositioningCoordinator,
        val inputGeneration: Long,
    )

    private data class CleanupInvocation(
        val activity: MainActivity,
        val returnsBoolean: Boolean,
        val result: Result<Any?>,
    )

    private class Registration(private var unregisterFailures: Int) : MotionSensorRegistration {
        val failure = IllegalStateException("motion unregister failed")
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0

        override fun hasSensor(type: Int): Boolean =
            type == Sensor.TYPE_ACCELEROMETER || type == Sensor.TYPE_GYROSCOPE

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            listeners += listener
            return true
        }

        override fun unregister(listener: SensorEventListener?) {
            unregisterCalls += 1
            if (unregisterFailures > 0) {
                unregisterFailures -= 1
                throw failure
            }
            listeners.remove(listener)
        }
    }
}
