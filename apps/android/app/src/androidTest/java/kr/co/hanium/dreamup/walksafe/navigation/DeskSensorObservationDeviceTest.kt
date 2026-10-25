package kr.co.hanium.dreamup.walksafe.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowDeskSensorObservationTest=true. Observe actual native sensors for two 30 s
 * windows with the phone's rear facing the desk. No generated SensorEvent, GPS or heading input.
 * Only this test's tracker listener is unregistered to exercise a real callback-gap recovery.
 * No MainActivity, account, preference, permission, global sensor or device-setting change.
 * Vertical rear projection / missing live geomagnetic reference are observations, not a valid
 * compass, walking, camera, TTS or field-safety verdict.
 */
@RunWith(AndroidJUnit4::class)
class DeskSensorObservationDeviceTest {
    @Test(timeout = 180_000L)
    fun realDeskSensorsRemainObservableAcrossStopRestartAndAnOwnedListenerGap() {
        assumeTrue(
            "Requires explicit allowDeskSensorObservationTest=true for the 60 s native sensor observation",
            InstrumentationRegistry.getArguments().getString("allowDeskSensorObservationTest") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        assertTrue("DESK_NATIVE_PRECONDITION: actual TYPE_ROTATION_VECTOR is unavailable", rotationSensor != null)
        val tracker = AndroidEarthOrientationTracker(context)
        val probe = NativeRotationProbe(manager, requireNotNull(rotationSensor))
        val recorder = ObservationRecorder()
        val resources = TrackerResources()
        val ownedThreads = linkedSetOf<HandlerThread>()
        var primaryFailure: Throwable? = null
        tracker.setRouteObservationListener(recorder::record)
        report("scope=NATIVE_COMPONENT placement=REAR_FACING_DESK native_observation_ms=60000 synthetic_sensor_input=false heading_accuracy=NOT_TESTED field_safety=NOT_TESTED")
        try {
            report("geomagnetic_reference=${referenceFromLatestRealLocation(context, tracker)} coordinates_recorded=false")
            assertTrue("DESK_NATIVE_PRECONDITION: independent RV registration rejected", probe.start())
            val firstStartNs = SystemClock.elapsedRealtimeNanos()
            assertTrue("DESK_NATIVE_PRECONDITION: tracker registration rejected", tracker.start())
            resources.thread(tracker)?.let(ownedThreads::add)
            val first = awaitMatchedObservation(tracker, recorder, probe, firstStartNs)
            val firstGeneration = first.registrationGeneration
            assertTrue(tracker.start())
            assertEquals(firstGeneration, resources.generation(tracker))
            observeWindow("first_start", tracker, recorder, probe)

            tracker.stop()
            assertStopped(tracker)
            assertFalse("Stop must retire the captured real observation", tracker.isRouteObservationCurrent(first))
            val atStop = probe.count()
            val stopUntil = SystemClock.elapsedRealtime() + 500L
            while (SystemClock.elapsedRealtime() < stopUntil) {
                assertStopped(tracker)
                recorder.latestRotation()?.let { event ->
                    assertFalse("A callback/event observed after stop must remain retired", tracker.isRouteObservationCurrent(event))
                }
                Thread.sleep(25L)
            }
            assertTrue("Independent native sensor must continue while this tracker is stopped", probe.count() > atStop)
            assertStopped(tracker)
            tracker.stop()

            val restartNs = SystemClock.elapsedRealtimeNanos()
            assertTrue("Tracker restart must register actual sensors", tracker.start())
            resources.thread(tracker)?.let(ownedThreads::add)
            val restarted = awaitMatchedObservation(tracker, recorder, probe, restartNs)
            assertTrue(restarted.registrationGeneration > firstGeneration)
            assertFalse("Old event must stay retired after restart", tracker.isRouteObservationCurrent(first))
            observeWindow("restarted", tracker, recorder, probe)

            // Fault only this test-owned registration. The independent probe continues unchanged;
            // no clock is accelerated, listener callback manufactured or global sensor disabled.
            val retiredListener = requireNotNull(resources.listener(tracker))
            val beforeGap = awaitMatchedObservation(tracker, recorder, probe, restartNs)
            manager.unregisterListener(retiredListener)
            val probeBeforeGap = probe.count()
            Thread.sleep(2_600L)
            assertTrue("Fault scenario requires continuing real native production", probe.count() > probeBeforeGap)
            val recoveryBoundaryNs = SystemClock.elapsedRealtimeNanos()
            val recoveryReason = tracker.latestRouteCompassHeading().reason
            resources.thread(tracker)?.let(ownedThreads::add)
            assertNotEquals("Watchdog must re-register after this actual receipt gap", beforeGap.registrationGeneration, resources.generation(tracker))
            assertFalse("Watchdog recovery must retire the old native event", tracker.isRouteObservationCurrent(beforeGap))
            assertFalse("Watchdog recovery must also retire the initial event", tracker.isRouteObservationCurrent(first))
            assertFalse("Recovery must replace this exact listener", retiredListener === resources.listener(tracker))
            val recovered = awaitMatchedObservation(tracker, recorder, probe, recoveryBoundaryNs)
            assertTrue(recovered.registrationGeneration > beforeGap.registrationGeneration)
            assertFalse("Fresh native recovery must clear stalled output", tracker.latestRouteCompassHeading().reason == RouteCompassHeadingReason.SENSOR_STALLED)
            recorder.assertNoTimestampViolation()
            report("scenario=owned_listener_gap recovery_reason=$recoveryReason old_generation=${beforeGap.registrationGeneration} new_generation=${recovered.registrationGeneration} old_event_current=false native_recovery=true ${probe.summary()}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            report("result=FAILED failure_type=${failure.javaClass.simpleName} detail=${failure.message} ${probe.summary()}")
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun retain(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
            }
            // Capture every test-owned native listener/thread before stop clears its handles.
            val exactListener = resources.listener(tracker)
            resources.thread(tracker)?.let(ownedThreads::add)
            try {
                tracker.stop()
            } catch (failure: Throwable) {
                retain(failure)
                try { exactListener?.let(manager::unregisterListener) } catch (fallback: Throwable) { retain(fallback) }
            } finally {
                tracker.setRouteObservationListener(null)
                ownedThreads.forEach { thread ->
                    try {
                        thread.quitSafely()
                        thread.join(2_000L)
                        assertFalse("Test-owned tracker callback thread must terminate", thread.isAlive)
                    } catch (failure: Throwable) { retain(failure) }
                }
                try { probe.close() } catch (failure: Throwable) { retain(failure) }
            }
            try {
                assertStopped(tracker)
                assertNull("Tracker listener must be cleared", resources.listener(tracker))
                assertNull("Tracker thread handle must be cleared", resources.thread(tracker))
            } catch (failure: Throwable) { retain(failure) }
            report("cleanup_failed=${cleanupFailure != null} ${recorder.summary()} ${probe.summary()}")
            cleanupFailure?.let { failure ->
                if (primaryFailure != null) primaryFailure?.addSuppressed(failure) else throw failure
            }
        }
    }

    private fun observeWindow(label: String, tracker: AndroidEarthOrientationTracker, recorder: ObservationRecorder, probe: NativeRotationProbe) {
        val until = SystemClock.elapsedRealtime() + 30_000L
        val before = recorder.sampleCount()
        var nextReport = SystemClock.elapsedRealtime()
        val reasons = linkedMapOf<RouteCompassHeadingReason, Int>()
        while (SystemClock.elapsedRealtime() < until) {
            val result = tracker.latestRouteCompassHeading()
            reasons[result.reason] = (reasons[result.reason] ?: 0) + 1
            assertFalse("Native probe remains live but tracker reports a stall: ${probe.summary()}",
                probe.hasFreshMeasurement() && result.reason == RouteCompassHeadingReason.SENSOR_STALLED)
            recorder.assertNoTimestampViolation()
            if (SystemClock.elapsedRealtime() >= nextReport) {
                report("window=$label reasons=$reasons ${recorder.summary()} ${probe.summary()} rear_axis_vertical_expected=true")
                nextReport = SystemClock.elapsedRealtime() + 5_000L
            }
            Thread.sleep(100L)
        }
        assertTrue("Real native samples must continue throughout $label", recorder.sampleCount() > before)
        assertTrue("At least one finite real RV sample is required", probe.validCount() > 0L)
        report("window=$label duration_ms=30000 reasons=$reasons native_delivery=true compass_validity=OBSERVED_ONLY ${recorder.summary()} ${probe.summary()}")
    }

    private fun awaitMatchedObservation(tracker: AndroidEarthOrientationTracker, recorder: ObservationRecorder, probe: NativeRotationProbe, notBeforeNs: Long): RouteCompassObservationEvent {
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < until) {
            recorder.latestRotation()?.let { event ->
                val timestamp = event.sensorTimestampNanos
                if (timestamp != null && timestamp >= notBeforeNs && tracker.isRouteObservationCurrent(event) && probe.contains(timestamp)) {
                    return event
                }
            }
            Thread.sleep(20L)
        }
        throw AssertionError("DESK_NATIVE_PRECONDITION: no current-generation tracker sample matched an independent original RV timestamp; ${recorder.summary()} ${probe.summary()}")
    }

    private fun assertStopped(tracker: AndroidEarthOrientationTracker) {
        val result = tracker.latestRouteCompassHeading()
        assertEquals(RouteCompassHeadingReason.ORIENTATION_MISSING, result.reason)
        assertNull(result.observation)
        assertNull(result.checkEvidence)
        assertNull(tracker.latest())
        assertNull(tracker.orientationAt(SystemClock.elapsedRealtime(), 500L))
    }

    /** Reads only an already permitted, recent, accurate, non-mock fix. Never requests a new fix. */
    @Suppress("DEPRECATION")
    private fun referenceFromLatestRealLocation(context: Context, tracker: AndroidEarthOrientationTracker): String {
        val permitted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!permitted) return "MISSING_NO_EXISTING_LOCATION_PERMISSION"
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { fix ->
                val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fix.isMock else fix.isFromMockProvider
                !mock && fix.elapsedRealtimeNanos in 1L..nowNs && nowNs - fix.elapsedRealtimeNanos <= 60_000_000_000L &&
                    fix.hasAccuracy() && fix.accuracy.isFinite() && fix.accuracy in 0f..50f
            }.maxByOrNull { it.elapsedRealtimeNanos } ?: return "MISSING_NO_FRESH_REAL_LOCATION"
        val accepted = tracker.updateGeomagneticReference(location.latitude, location.longitude,
            location.altitude.takeIf { location.hasAltitude() && it.isFinite() } ?: 0.0, location.time)
        return if (accepted) "LATEST_EXISTING_REAL_LOCATION" else "MISSING_REFERENCE_REJECTED"
    }

    private class ObservationRecorder {
        private val lastTimestamp = mutableMapOf<Pair<Int, RouteCompassStream>, Long>()
        private val latestRotation = AtomicReference<RouteCompassObservationEvent?>()
        private var samples = 0L
        private var invalidations = 0L
        private var violation: String? = null

        @Synchronized
        fun record(event: RouteCompassObservationEvent) {
            val timestamp = event.sensorTimestampNanos
            if (timestamp == null) { invalidations += 1L; return }
            val key = event.registrationGeneration to event.stream
            if (timestamp < 0L || timestamp > (event.receivedAtMs + 1L) * 1_000_000L ||
                lastTimestamp[key]?.let { timestamp <= it } == true) {
                violation = "Nonmonotonic/future original sensor timestamp generation=${event.registrationGeneration} stream=${event.stream}"
            }
            lastTimestamp[key] = timestamp
            samples += 1L
            if (event.stream == RouteCompassStream.ROTATION_VECTOR) latestRotation.set(event)
        }

        fun latestRotation(): RouteCompassObservationEvent? = latestRotation.get()
        @Synchronized fun sampleCount(): Long = samples
        @Synchronized fun assertNoTimestampViolation() { assertNull(violation, violation) }
        @Synchronized fun summary(): String = "tracker_samples=$samples quality_invalidations=$invalidations generations=${lastTimestamp.keys.map { it.first }.toSet()} last_original_rv_ns=${latestRotation.get()?.sensorTimestampNanos}"
    }

    /** Read-only handles to this test's tracker resources; no private field is assigned. */
    private class TrackerResources {
        private fun field(name: String): Field = AndroidEarthOrientationTracker::class.java.getDeclaredField(name).apply { isAccessible = true }
        private val listener = field("currentListener")
        private val thread = field("sensorThread")
        private val generation = field("registrationGeneration")
        fun listener(tracker: AndroidEarthOrientationTracker): SensorEventListener? = synchronized(tracker) { listener.get(tracker) as? SensorEventListener }
        fun thread(tracker: AndroidEarthOrientationTracker): HandlerThread? = synchronized(tracker) { thread.get(tracker) as? HandlerThread }
        fun generation(tracker: AndroidEarthOrientationTracker): Int = synchronized(tracker) { generation.getInt(tracker) }
    }

    private class NativeRotationProbe(private val manager: SensorManager, private val sensor: Sensor) : SensorEventListener {
        private val thread = HandlerThread("DeskNativeRotationProbe").apply { start() }
        private val originalTimes = linkedSetOf<Long>()
        private var callbacks = 0L
        private var valid = 0L
        private var lastReceiptMs = 0L
        private var latestRearHorizontal: Double? = null

        fun start(): Boolean = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))
        @Synchronized override fun onSensorChanged(event: SensorEvent) {
            callbacks += 1L
            lastReceiptMs = SystemClock.elapsedRealtime()
            originalTimes += event.timestamp
            if (originalTimes.size > 4_096) originalTimes.remove(originalTimes.first())
            if (event.timestamp >= 0L && event.timestamp <= SystemClock.elapsedRealtimeNanos() &&
                event.values.size >= 3 && event.values.take(3).all { it.isFinite() }) {
                valid += 1L
                val rotation = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                latestRearHorizontal = hypot(rotation[2].toDouble(), rotation[5].toDouble())
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        @Synchronized fun count(): Long = callbacks
        @Synchronized fun validCount(): Long = valid
        @Synchronized fun contains(timestampNs: Long): Boolean = timestampNs in originalTimes
        @Synchronized fun hasFreshMeasurement(): Boolean = callbacks > 0L && SystemClock.elapsedRealtime() - lastReceiptMs in 0L..500L
        @Synchronized fun summary(): String = "probe_callbacks=$callbacks finite_native_samples=$valid last_receipt_ms=$lastReceiptMs rear_horizontal_projection=$latestRearHorizontal"
        fun close() {
            try { manager.unregisterListener(this) } finally {
                thread.quitSafely()
                thread.join(2_000L)
                assertFalse("Independent native probe thread must terminate", thread.isAlive)
            }
        }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "DESK_SENSOR_OBSERVATION $message\n")
        })
    }
}
