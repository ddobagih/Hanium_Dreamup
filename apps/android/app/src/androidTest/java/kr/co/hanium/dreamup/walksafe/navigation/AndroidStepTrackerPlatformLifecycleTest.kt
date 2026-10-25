package kr.co.hanium.dreamup.walksafe.navigation

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AcceptedSensorSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorRegistrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerHealthSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerLifecycle
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerStoppedReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses only the public Context constructor and actual SensorManager callbacks. No SensorEvent
 * reflection, manual adapter input, sensor injection, permission changes or MainActivity launch.
 * An accelerometer fallback run proves native receipt/lifecycle, not physical step accuracy.
 * Hardware Counter/Detector runs require real steps in each observation window.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStepTrackerPlatformLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Test(timeout = 30_000L)
    fun nativeAvailabilityRegistrationAndCleanupAreReportedSeparately() {
        val tracker = onMain { AndroidStepTracker(context, onStepCountChanged = {}) }
        try {
            onMain {
                reportInventory()
                assertEquals(SensorTrackerLifecycle.IDLE, tracker.operationalHealth.lifecycle)
                assertEquals(sensor(TrackedSensorSource.STEP_COUNTER) != null, tracker.usesStepCounter)
                val started = tracker.start()
                val health = tracker.operationalHealth
                assertRegistrationTruth(started, health)
                if (started) {
                    assertEquals(if (health.source(TrackedSensorSource.STEP_COUNTER)?.acceptingSamples == true) {
                        StepCounterBaselineState.NOT_ESTABLISHED
                    } else StepCounterBaselineState.NOT_REGISTERED, tracker.counterBaselineState)
                    assertTrue(tracker.start())
                    assertEquals(health, tracker.operationalHealth)
                }
                report("AVAILABILITY_ONLY started=$started health=$health callbackProof=NOT_REQUESTED")
                tracker.stop()
                assertStopped(tracker)
            }
        } finally {
            onMain { tracker.stop() }
        }
    }

    @Test(timeout = 90_000L)
    fun actualNativeReceiptsStopAndRestartInANewRegistrationGeneration() {
        var callbacks = 0
        var startedCallbacks = 0
        var wrongCallbackThread = false
        val events = mutableListOf<StepEvent>()
        val tracker = onMain {
            AndroidStepTracker(context, onStepCountChanged = {}, onTrackingStarted = { startedCallbacks++ },
                onMotionSensorSample = {
                    callbacks++
                    wrongCallbackThread = wrongCallbackThread || Looper.myLooper() != Looper.getMainLooper()
                }, onStepEvent = { events.add(it) })
        }
        val probe = NativeProbe()
        try {
            val firstStartNs = SystemClock.elapsedRealtimeNanos()
            val active = onMain {
                reportInventory()
                assumeTrue("ENV_NO_STEP_OR_FALLBACK_SENSOR: native callback scenario not run",
                    sources.any { sensor(it) != null })
                assertTrue("ENV_STEP_REGISTRATION_FAILED: ${tracker.operationalHealth}", tracker.start())
                tracker.operationalHealth.also {
                    assertRegistrationTruth(true, it)
                    assertTrue(it.sources.all { source -> source.lastAcceptedSample == null })
                }
            }
            // Keep a separate native listener alive during tracker stop. Its receipt is never
            // substituted for production operationalHealth or used as a synthetic adapter input.
            val source = active.sources.first { it.acceptingSamples }.source
            onMain {
                assertTrue("ENV_INDEPENDENT_PROBE_REGISTRATION_FAILED", manager.registerListener(
                    probe, checkNotNull(sensor(source)), SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper())))
            }
            await("FIRST_NATIVE_RECEIPT") { sample(tracker, source) != null && probe.last != null && callbacks > 0 }
            val first = onMain { checkNotNull(sample(tracker, source)).also { assertReceipt(it, active, firstStartNs) } }
            await("LATER_NATIVE_RECEIPT") { sample(tracker, source)?.measurementElapsedRealtimeNanos?.let {
                it > first.measurementElapsedRealtimeNanos
            } == true }
            val stopped = onMain {
                val last = checkNotNull(sample(tracker, source))
                assertReceipt(last, active, firstStartNs)
                assertTrue(checkNotNull(last.receivedAtElapsedRealtimeNanos) >= checkNotNull(first.receivedAtElapsedRealtimeNanos))
                events.forEach { assertOriginalEvent(it, active.registrationGeneration) }
                report("NATIVE_RECEIPTS source=$source firstProduction=$first lastProduction=$last " +
                    "independentProbe=${probe.last} callbacks=$callbacks emittedSteps=${events.size} " +
                    "physicalStepAccuracy=NOT_TESTED")
                tracker.stop()
                assertStopped(tracker)
                Triple(tracker.operationalHealth, callbacks, probe.callbacks)
            }
            await("INDEPENDENT_NATIVE_PROBE_AFTER_STOP") { probe.callbacks > stopped.third }
            SystemClock.sleep(250L)
            onMain {
                assertEquals("Retired tracker must not publish while native probe continues", stopped.second, callbacks)
                assertEquals(stopped.first, tracker.operationalHealth)
                report("STOP_INACTIVE health=${tracker.operationalHealth} independentProbe=${probe.last}")
            }
            val restartNs = SystemClock.elapsedRealtimeNanos()
            val eventBoundary = onMain { events.size }
            val restarted = onMain {
                assertTrue(tracker.start())
                tracker.operationalHealth.also {
                    assertTrue(it.registrationGeneration > stopped.first.registrationGeneration)
                    assertTrue(it.sources.all { sourceHealth -> sourceHealth.lastAcceptedSample == null })
                    assertEquals(2, startedCallbacks)
                    assertTrue(tracker.start())
                    assertEquals(it, tracker.operationalHealth)
                    assertEquals(2, startedCallbacks)
                }
            }
            await("RESTART_NATIVE_RECEIPT") { sample(tracker, source) != null && callbacks > stopped.second }
            onMain {
                assertReceipt(checkNotNull(sample(tracker, source)), restarted, restartNs)
                if (source == TrackedSensorSource.STEP_COUNTER) {
                    assertEquals(StepCounterBaselineState.ESTABLISHED, tracker.counterBaselineState)
                    assertEquals(restarted.registrationGeneration, tracker.latestCounterObservation?.producerRegistrationGeneration)
                }
                events.drop(eventBoundary).forEach { assertOriginalEvent(it, restarted.registrationGeneration) }
                assertFalse("Actual production callbacks must remain on the serial main looper", wrongCallbackThread)
                assertFalse(probe.wrongThread)
                report("RESTART_RECEIVED health=${tracker.operationalHealth} independentProbe=${probe.last} " +
                    "screenLockBackgroundPermissionRevokeBoot=NOT_TESTED")
            }
        } finally {
            onMain {
                tracker.stop()
                manager.unregisterListener(probe)
                assertStopped(tracker)
                report("CLEANUP trackerStopped=true probeUnregistered=true")
            }
        }
    }

    private fun assertRegistrationTruth(started: Boolean, health: SensorTrackerHealthSnapshot) {
        health.sources.forEach { source ->
            source.available?.let { assertEquals(sensor(source.source) != null, it) }
            assertEquals(started && source.lastRegistrationOutcome == SensorRegistrationOutcome.REGISTERED,
                source.acceptingSamples)
        }
        assertEquals(started, health.sources.any { it.acceptingSamples })
        assertFalse(health.cleanupPending)
        if (started) {
            assertEquals(SensorTrackerLifecycle.ACTIVE, health.lifecycle)
            if (health.sources.any { it.acceptingSamples && it.source != TrackedSensorSource.ACCELEROMETER }) {
                assertFalse(checkNotNull(health.source(TrackedSensorSource.ACCELEROMETER)).acceptingSamples)
            }
        } else {
            assertEquals(SensorTrackerLifecycle.STOPPED, health.lifecycle)
            assertEquals(SensorTrackerStoppedReason.FAILED_START, health.stoppedReason)
        }
    }

    private fun assertStopped(tracker: AndroidStepTracker) {
        val health = tracker.operationalHealth
        assertEquals(SensorTrackerLifecycle.STOPPED, health.lifecycle)
        assertEquals(SensorTrackerStoppedReason.EXPLICIT_STOP, health.stoppedReason)
        assertFalse(health.cleanupPending)
        health.sources.forEach { assertFalse(it.acceptingSamples); assertNull(it.lastAcceptedSample) }
        assertEquals(StepCounterBaselineState.NOT_REGISTERED, tracker.counterBaselineState)
        assertNull(tracker.latestCounterObservation)
    }

    private fun assertReceipt(sample: AcceptedSensorSample, health: SensorTrackerHealthSnapshot, startNs: Long) {
        assertEquals(health.registrationGeneration, sample.producerRegistrationGeneration)
        assertTrue(sample.measurementElapsedRealtimeNanos >= 0L)
        val receipt = checkNotNull(sample.receivedAtElapsedRealtimeNanos)
        assertTrue(receipt >= sample.measurementElapsedRealtimeNanos)
        assertTrue(receipt >= startNs)
        assertTrue(receipt <= SystemClock.elapsedRealtimeNanos())
    }

    private fun assertOriginalEvent(event: StepEvent, generation: Int) {
        assertNotNull(event.measurementElapsedRealtimeNanos)
        assertNotNull(event.receivedAtElapsedRealtimeNanos)
        assertEquals(generation, event.producerRegistrationGeneration)
        assertEquals(event.timestampMs, checkNotNull(event.measurementElapsedRealtimeNanos) / 1_000_000L)
        assertTrue(checkNotNull(event.receivedAtElapsedRealtimeNanos) >= checkNotNull(event.measurementElapsedRealtimeNanos))
    }

    private fun sample(tracker: AndroidStepTracker, source: TrackedSensorSource) =
        tracker.operationalHealth.source(source)?.lastAcceptedSample

    private fun sensor(source: TrackedSensorSource): Sensor? = manager.getDefaultSensor(when (source) {
        TrackedSensorSource.STEP_COUNTER -> Sensor.TYPE_STEP_COUNTER
        TrackedSensorSource.STEP_DETECTOR -> Sensor.TYPE_STEP_DETECTOR
        TrackedSensorSource.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        TrackedSensorSource.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
    })

    private fun reportInventory() {
        val permission = Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        report("INVENTORY activityRecognitionGranted=$permission sensors=" + sources.joinToString { source ->
            "$source=${sensor(source)?.let { "${it.name}/${it.vendor}" } ?: "ABSENT"}"
        })
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(25L)
        }
        report("ENV_NATIVE_CALLBACK_NOT_OBSERVED stage=$stage timeoutMs=15000; no manual input substituted")
        assertTrue("ENV_NATIVE_CALLBACK_NOT_OBSERVED: $stage; real hardware steps may be required", onMain(condition))
    }

    private fun report(text: String) {
        Log.i("NativeStepLifecycle", text)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NativeStepLifecycle $text\n") })
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return try { task.get() } catch (failure: ExecutionException) { throw checkNotNull(failure.cause) }
    }

    private data class ProbeReceipt(val measurementNs: Long, val receivedAtNs: Long)

    private class NativeProbe : SensorEventListener {
        var last: ProbeReceipt? = null
        var callbacks = 0
        var wrongThread = false
        override fun onSensorChanged(event: SensorEvent) {
            last = ProbeReceipt(event.timestamp, SystemClock.elapsedRealtimeNanos())
            callbacks++
            wrongThread = wrongThread || Looper.myLooper() != Looper.getMainLooper()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    companion object {
        private val sources = listOf(TrackedSensorSource.STEP_COUNTER, TrackedSensorSource.STEP_DETECTOR,
            TrackedSensorSource.ACCELEROMETER)
    }
}
