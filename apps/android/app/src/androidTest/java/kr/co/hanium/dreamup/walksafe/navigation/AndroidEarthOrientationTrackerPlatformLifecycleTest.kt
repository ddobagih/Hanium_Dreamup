package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SensorManager delivery and public Tracker lifecycle. No SensorEvent construction, manual
 * callbacks, reflection, sensor injection, MainActivity launch, or valid-heading/field claim.
 * Probe receipts belong to an independent native listener, not to Tracker's private watchdog.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEarthOrientationTrackerPlatformLifecycleTest {
    @Test
    fun nativeRotationDeliveryUpdatesTrackerEvidenceAndRemainsLive() = withNativeTracker("delivery") { tracker, probe, observations ->
        val startAtMs = SystemClock.elapsedRealtime()
        assertTrue("Tracker must register an available native sensor configuration", tracker.start())
        awaitEligibleNativeDelivery(probe)
        val firstAtMs = awaitTrackerEvidence(tracker, probe, startAtMs)
        val firstRouteEvent = awaitCurrentRouteEvent(tracker, observations)
        assertEquals(RouteCompassStream.ROTATION_VECTOR, firstRouteEvent.stream)
        val eventAtNanos = requireNotNull(firstRouteEvent.sensorTimestampNanos)
        val eventAtMs = eventAtNanos / 1_000_000L
        assertTrue("Route event measurement must be after this Tracker start", eventAtMs >= startAtMs)
        assertTrue("Route event must carry a timestamp observed by the independent native probe",
            probe.containsMeasurementNanos(eventAtNanos))
        report("first_tracker_measurement_ms=$firstAtMs ${probe.summary()}")
        report("first_route_compass_event_generation=${firstRouteEvent.registrationGeneration} stream=${firstRouteEvent.stream}")
        assertTrue("Repeated start on an active Tracker remains successful", tracker.start())

        // Observe actual continued delivery beyond the existing 2000 ms receipt watchdog gap.
        val observeUntilMs = SystemClock.elapsedRealtime() + CONTINUED_DELIVERY_MS
        while (SystemClock.elapsedRealtime() < observeUntilMs) {
            val result = tracker.latestRouteCompassHeading()
            assertFalse("native delivery health; reason=${result.reason} ${probe.summary()}",
                result.reason == RouteCompassHeadingReason.SENSOR_STALLED)
            SystemClock.sleep(POLL_MS)
        }
        val lastAtMs = awaitTrackerEvidence(tracker, probe, maxOf(firstAtMs + 1L, observeUntilMs))
        assertTrue("A new real RV measurement must replace the first measurement", lastAtMs > firstAtMs)
        report("continued_tracker_measurement_ms=$lastAtMs ${probe.summary()}")
    }

    @Test
    fun stopClearsPublicEvidenceWhileNativeInputContinuesAndRestartReceivesNewMeasurements() =
        withNativeTracker("restart") { tracker, probe, observations ->
            val startAtMs = SystemClock.elapsedRealtime()
            assertTrue(tracker.start())
            awaitEligibleNativeDelivery(probe)
            val firstAtMs = awaitTrackerEvidence(tracker, probe, startAtMs)
            val retiredRouteEvent = awaitCurrentRouteEvent(tracker, observations)
            tracker.stop()
            assertStopped(tracker)
            assertFalse("stop must retire the route event's registration generation",
                tracker.isRouteObservationCurrent(retiredRouteEvent))
            val countAtStop = probe.callbackCount()
            val stoppedUntilMs = SystemClock.elapsedRealtime() + STOPPED_OBSERVATION_MS
            while (SystemClock.elapsedRealtime() < stoppedUntilMs) {
                assertStopped(tracker)
                SystemClock.sleep(POLL_MS)
            }
            assertTrue("The independent native producer must continue while Tracker is stopped: ${probe.summary()}",
                waitUntil { probe.callbackCount() > countAtStop })
            assertStopped(tracker)
            tracker.stop()
            assertStopped(tracker)
            report("stopped_without_public_evidence=true first_tracker_measurement_ms=$firstAtMs ${probe.summary()}")

            val restartAtMs = SystemClock.elapsedRealtime()
            assertTrue("The same Tracker must register again after stop", tracker.start())
            val restartedAtMs = awaitTrackerEvidence(tracker, probe, maxOf(restartAtMs, firstAtMs + 1L))
            val restartedRouteEvent = awaitCurrentRouteEvent(tracker, observations, retiredRouteEvent.registrationGeneration)
            assertTrue("Restart must expose a measurement from after the new start boundary",
                restartedAtMs >= restartAtMs && restartedAtMs > firstAtMs)
            assertTrue("Restart must publish route observations with a new live registration generation",
                restartedRouteEvent.registrationGeneration > retiredRouteEvent.registrationGeneration)
            report("restart_boundary_ms=$restartAtMs restarted_tracker_measurement_ms=$restartedAtMs ${probe.summary()}")
            report("retired_route_generation=${retiredRouteEvent.registrationGeneration} restarted_route_generation=${restartedRouteEvent.registrationGeneration}")
        }

    private fun withNativeTracker(
        label: String,
        test: (AndroidEarthOrientationTracker, NativeRotationProbe, CopyOnWriteArrayList<RouteCompassObservationEvent>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val availability = listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER).joinToString { type ->
            "$type:${manager.getDefaultSensor(type)?.name ?: "ABSENT"}"
        }
        report("attempt=$label sensors=$availability tracker_internal_receipts=NOT_EXPOSED heading_accuracy=NOT_TESTED")
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        assumeTrue("NOT_RUN: native TYPE_ROTATION_VECTOR absent; sensors=$availability", sensor != null)
        val tracker = AndroidEarthOrientationTracker(context)
        val probe = NativeRotationProbe(manager, requireNotNull(sensor))
        val observations = CopyOnWriteArrayList<RouteCompassObservationEvent>()
        tracker.setRouteObservationListener(observations::add)
        try {
            assertStopped(tracker)
            assertTrue("Native RV probe registration must succeed for the available sensor", probe.start())
            test(tracker, probe, observations)
        } finally {
            tracker.stop()
            tracker.setRouteObservationListener(null)
            probe.close()
            report("attempt=$label cleanup=tracker_stop_and_probe_unregister ${probe.summary()}")
        }
    }

    private fun awaitCurrentRouteEvent(
        tracker: AndroidEarthOrientationTracker,
        observations: List<RouteCompassObservationEvent>,
        generationAfter: Int? = null,
    ): RouteCompassObservationEvent {
        var event: RouteCompassObservationEvent? = null
        val observed = waitUntil {
            event = observations.lastOrNull { candidate ->
                candidate.stream == RouteCompassStream.ROTATION_VECTOR &&
                    candidate.sensorTimestampNanos != null &&
                    (generationAfter == null || candidate.registrationGeneration > generationAfter) &&
                    tracker.isRouteObservationCurrent(candidate)
            }
            event != null
        }
        assertTrue("Native sensor delivery must reach the current route-compass listener", observed)
        return requireNotNull(event)
    }

    private fun awaitEligibleNativeDelivery(probe: NativeRotationProbe) {
        val delivered = waitUntil { probe.hasEligibleDelivery() }
        report("native_eligible_delivery=$delivered ${probe.summary()}")
        assumeTrue("NOT_RUN: native RV absent, delayed, background-restricted, or not currently admissible; ${probe.summary()}",
            delivered)
    }

    private fun awaitTrackerEvidence(
        tracker: AndroidEarthOrientationTracker,
        probe: NativeRotationProbe,
        notBeforeMs: Long,
    ): Long {
        var acceptedAtMs: Long? = null
        var lastReason: RouteCompassHeadingReason? = null
        val observed = waitUntil {
            val nowMs = SystemClock.elapsedRealtime()
            val result = tracker.latestRouteCompassHeading(nowMs)
            lastReason = result.reason
            val evidence = result.checkEvidence
            val atMs = evidence?.observedAtElapsedRealtimeMs
            if (evidence?.source == RouteFacingSource.ROTATION_VECTOR && atMs != null &&
                atMs >= notBeforeMs && atMs <= nowMs && nowMs - atMs <= 500L && probe.containsMeasurement(atMs)
            ) acceptedAtMs = atMs
            acceptedAtMs != null
        }
        report("tracker_clock_observed=$observed not_before_ms=$notBeforeMs reason=$lastReason ${probe.summary()}")
        assertTrue("Native RV delivery was observed, but no matching fresh Tracker measurement is exposed. " +
            "Investigate provider/foreground/registration/admission before classifying a product RED; " +
            "reason=$lastReason ${probe.summary()}", observed)
        return requireNotNull(acceptedAtMs)
    }

    private fun assertStopped(tracker: AndroidEarthOrientationTracker) {
        val result = tracker.latestRouteCompassHeading()
        assertEquals(RouteCompassHeadingReason.ORIENTATION_MISSING, result.reason)
        assertNull(result.observation)
        assertNull(result.checkEvidence)
        assertNull(tracker.latest())
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + DELIVERY_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (predicate()) return true
            SystemClock.sleep(POLL_MS)
        }
        return predicate()
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "ORIENTATION_NATIVE $message\n")
        })
    }

    private class NativeRotationProbe(private val manager: SensorManager, private val sensor: Sensor) : SensorEventListener {
        private val thread = HandlerThread("OrientationNativeProbe").apply { start() }
        private var count = 0L
        private var eligibleCount = 0L
        private var firstMeasurementNanos: Long? = null
        private var lastMeasurementNanos: Long? = null
        private var firstReceiptNanos: Long? = null
        private var lastReceiptNanos: Long? = null
        private var accuracyStatus: Int? = null
        private val eligibleMeasurementsMs = linkedSetOf<Long>()
        private val eligibleMeasurementNanos = linkedSetOf<Long>()

        fun start(): Boolean = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))

        override fun onSensorChanged(event: SensorEvent) {
            val receiptNanos = SystemClock.elapsedRealtimeNanos()
            synchronized(this) {
                count += 1L
                if (firstReceiptNanos == null) {
                    firstReceiptNanos = receiptNanos
                    firstMeasurementNanos = event.timestamp
                }
                lastReceiptNanos = receiptNanos
                lastMeasurementNanos = event.timestamp
                val accuracy = minOf(event.accuracy, accuracyStatus ?: event.accuracy)
                if (event.timestamp >= 0L && event.timestamp <= receiptNanos &&
                    event.values.size >= 3 && event.values.take(3).all { it.isFinite() } &&
                    accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                ) {
                    eligibleCount += 1L
                    eligibleMeasurementsMs.add(event.timestamp / 1_000_000L)
                    eligibleMeasurementNanos.add(event.timestamp)
                    if (eligibleMeasurementNanos.size > 2_048) eligibleMeasurementNanos.remove(eligibleMeasurementNanos.first())
                    if (eligibleMeasurementsMs.size > 2_048) eligibleMeasurementsMs.remove(eligibleMeasurementsMs.first())
                }
            }
        }

        @Synchronized
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            accuracyStatus = accuracy
        }

        @Synchronized
        fun callbackCount(): Long = count

        @Synchronized
        fun hasEligibleDelivery(): Boolean = eligibleCount > 0L

        @Synchronized
        fun containsMeasurement(atMs: Long): Boolean = atMs in eligibleMeasurementsMs

        @Synchronized
        fun containsMeasurementNanos(atNanos: Long): Boolean = atNanos in eligibleMeasurementNanos

        @Synchronized
        fun summary(): String = "probe_callbacks=$count probe_eligible=$eligibleCount " +
            "probe_first_measurement_ns=$firstMeasurementNanos probe_last_measurement_ns=$lastMeasurementNanos " +
            "probe_first_receipt_ns=$firstReceiptNanos probe_last_receipt_ns=$lastReceiptNanos probe_accuracy=$accuracyStatus"

        fun close() {
            manager.unregisterListener(this)
            thread.quitSafely()
            thread.join(2_000L)
            assertFalse("The native probe thread must finish after unregister/quitSafely", thread.isAlive)
        }
    }

    private companion object {
        // Test observation budgets only; production timing/quality thresholds are unchanged.
        const val DELIVERY_WAIT_MS = 8_000L
        const val CONTINUED_DELIVERY_MS = 2_500L
        const val STOPPED_OBSERVATION_MS = 250L
        const val POLL_MS = 20L
    }
}
