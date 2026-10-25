package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual public Context adapter, native registration and native callback receipts only.
 * No SensorEvent construction, direct onSensorSample/recordStep, sensor injection, permission
 * changes or MainActivity launch. Motion classification/physical walking accuracy is not tested.
 * Background restrictions or an unavailable native source must not be called a product RED.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPedestrianMotionTrackerPlatformLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Test(timeout = 30_000L)
    fun nativeAvailabilityRegistrationAndCloseAreReportedSeparately() {
        val tracker = onMain { AndroidPedestrianMotionTracker(context, onDecision = {}) }
        try {
            onMain {
                reportInventory()
                assertEquals(SensorTrackerLifecycle.IDLE, tracker.operationalHealth.lifecycle)
                val started = tracker.start()
                val active = tracker.operationalHealth
                assertRegistrationTruth(started, active)
                if (started) {
                    assertTrue(tracker.start())
                    assertEquals(active, tracker.operationalHealth)
                }
                report("AVAILABILITY_ONLY started=$started health=$active callbackProof=NOT_REQUESTED")
                tracker.close()
                assertRetired(tracker, SensorTrackerLifecycle.CLOSED, SensorTrackerStoppedReason.CLOSED)
                val closed = tracker.operationalHealth
                assertFalse(tracker.start())
                tracker.close()
                assertEquals(closed, tracker.operationalHealth)
            }
        } finally {
            onMain { tracker.close() }
        }
    }

    @Test(timeout = 90_000L)
    fun actualNativeReceiptsStopRestartAndStayRetiredAfterClose() {
        var decisions = 0
        var wrongCallbackThread = false
        var lastDecisionAtMs: Long? = null
        val tracker = onMain {
            AndroidPedestrianMotionTracker(context, onDecision = { decision ->
                decisions++
                lastDecisionAtMs = decision.sample?.elapsedRealtimeMs
                wrongCallbackThread = wrongCallbackThread || Looper.myLooper() != Looper.getMainLooper()
            })
        }
        val probe = NativeProbe()
        try {
            val firstStartNs = SystemClock.elapsedRealtimeNanos()
            val active = onMain {
                reportInventory()
                assumeTrue("ENV_NO_MOTION_SENSOR: native callback scenario not run", sources.any { sensor(it) != null })
                assertTrue("ENV_MOTION_REGISTRATION_FAILED: ${tracker.operationalHealth}", tracker.start())
                tracker.operationalHealth.also {
                    assertRegistrationTruth(true, it)
                    assertTrue(it.sources.all { source -> source.lastAcceptedSample == null })
                }
            }
            val registered = active.sources.filter { it.acceptingSamples }.map { it.source }
            onMain {
                registered.forEach { source ->
                    assertTrue("ENV_INDEPENDENT_PROBE_REGISTRATION_FAILED: $source", manager.registerListener(
                        probe, checkNotNull(sensor(source)), SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper())))
                }
            }
            await("FIRST_NATIVE_RECEIPTS") {
                registered.all { sample(tracker, it) != null && probe.latest[it] != null } && decisions > 0
            }
            val first = onMain { registered.associateWith { source ->
                checkNotNull(sample(tracker, source)).also { assertReceipt(it, active, firstStartNs) }
            } }
            await("LATER_NATIVE_RECEIPTS") {
                registered.all { source -> sample(tracker, source)?.measurementElapsedRealtimeNanos?.let {
                    it > checkNotNull(first[source]).measurementElapsedRealtimeNanos
                } == true }
            }
            val stopped = onMain {
                registered.forEach { source ->
                    val last = checkNotNull(sample(tracker, source))
                    assertReceipt(last, active, firstStartNs)
                    assertTrue(checkNotNull(last.receivedAtElapsedRealtimeNanos) >=
                        checkNotNull(checkNotNull(first[source]).receivedAtElapsedRealtimeNanos))
                    report("NATIVE_RECEIPTS source=$source firstProduction=${first[source]} lastProduction=$last " +
                        "independentProbe=${probe.latest[source]}")
                }
                lastDecisionAtMs?.let { decisionAt ->
                    assertEquals(tracker.operationalHealth.sources.mapNotNull {
                        it.lastAcceptedSample?.measurementElapsedRealtimeNanos?.div(1_000_000L)
                    }.maxOrNull(), decisionAt)
                }
                report("DECISION_DIAGNOSTIC callbacks=$decisions lastDecisionSampleAtMs=$lastDecisionAtMs; " +
                    "a rejected decision may carry no sample, classification acceptance is not asserted")
                tracker.stop()
                assertRetired(tracker, SensorTrackerLifecycle.STOPPED, SensorTrackerStoppedReason.EXPLICIT_STOP)
                Triple(tracker.operationalHealth, decisions, probe.callbacks)
            }
            await("INDEPENDENT_NATIVE_PROBE_AFTER_STOP") { probe.callbacks > stopped.third }
            SystemClock.sleep(250L)
            onMain {
                assertEquals("Retired producer must not publish while native probe continues", stopped.second, decisions)
                assertEquals(stopped.first, tracker.operationalHealth)
                report("STOP_INACTIVE health=${tracker.operationalHealth} independentProbe=${probe.latest}")
            }
            val restartNs = SystemClock.elapsedRealtimeNanos()
            val restarted = onMain {
                assertTrue(tracker.start())
                tracker.operationalHealth.also {
                    assertTrue(it.registrationGeneration > stopped.first.registrationGeneration)
                    assertTrue(it.sources.all { source -> source.lastAcceptedSample == null })
                    assertTrue(tracker.start())
                    assertEquals(it, tracker.operationalHealth)
                }
            }
            await("RESTART_NATIVE_RECEIPTS") {
                registered.all { sample(tracker, it) != null } && decisions > stopped.second
            }
            val closed = onMain {
                registered.forEach { assertReceipt(checkNotNull(sample(tracker, it)), restarted, restartNs) }
                assertFalse("Production decisions must remain on the serial main looper", wrongCallbackThread)
                assertFalse(probe.wrongThread)
                report("RESTART_RECEIVED health=${tracker.operationalHealth} independentProbe=${probe.latest} " +
                    "decisions=$decisions classificationAccuracy=NOT_TESTED")
                tracker.close()
                assertRetired(tracker, SensorTrackerLifecycle.CLOSED, SensorTrackerStoppedReason.CLOSED)
                val health = tracker.operationalHealth
                assertFalse(tracker.start())
                assertEquals(health, tracker.operationalHealth)
                Triple(health, decisions, probe.callbacks)
            }
            await("INDEPENDENT_NATIVE_PROBE_AFTER_CLOSE") { probe.callbacks > closed.third }
            onMain {
                assertEquals(closed.second, decisions)
                assertEquals(closed.first, tracker.operationalHealth)
                report("CLOSED_INACTIVE health=${tracker.operationalHealth} independentProbe=${probe.latest} " +
                    "screenLockBackgroundPermissionRevokeBoot=NOT_TESTED")
            }
        } finally {
            onMain {
                tracker.close()
                manager.unregisterListener(probe)
                assertRetired(tracker, SensorTrackerLifecycle.CLOSED, SensorTrackerStoppedReason.CLOSED)
                report("CLEANUP trackerClosed=true probeUnregistered=true")
            }
        }
    }

    private fun assertRegistrationTruth(started: Boolean, health: SensorTrackerHealthSnapshot) {
        health.sources.forEach { source ->
            assertEquals(sensor(source.source) != null, source.available)
            assertEquals(started && source.lastRegistrationOutcome == SensorRegistrationOutcome.REGISTERED,
                source.acceptingSamples)
        }
        assertEquals(started, health.sources.any { it.acceptingSamples })
        assertFalse(health.cleanupPending)
        assertEquals(if (started) SensorTrackerLifecycle.ACTIVE else SensorTrackerLifecycle.STOPPED, health.lifecycle)
        if (!started) assertEquals(SensorTrackerStoppedReason.FAILED_START, health.stoppedReason)
    }

    private fun assertRetired(tracker: AndroidPedestrianMotionTracker, lifecycle: SensorTrackerLifecycle,
        reason: SensorTrackerStoppedReason) {
        val health = tracker.operationalHealth
        assertEquals(lifecycle, health.lifecycle)
        assertEquals(reason, health.stoppedReason)
        assertFalse(health.cleanupPending)
        health.sources.forEach { assertFalse(it.acceptingSamples); assertNull(it.lastAcceptedSample) }
    }

    private fun assertReceipt(sample: AcceptedSensorSample, health: SensorTrackerHealthSnapshot, startNs: Long) {
        assertEquals(health.registrationGeneration, sample.producerRegistrationGeneration)
        assertTrue(sample.measurementElapsedRealtimeNanos >= 0L)
        val receipt = checkNotNull(sample.receivedAtElapsedRealtimeNanos)
        assertTrue(receipt >= sample.measurementElapsedRealtimeNanos)
        assertTrue(receipt >= startNs)
        assertTrue(receipt <= SystemClock.elapsedRealtimeNanos())
    }

    private fun sample(tracker: AndroidPedestrianMotionTracker, source: TrackedSensorSource) =
        tracker.operationalHealth.source(source)?.lastAcceptedSample

    private fun sensor(source: TrackedSensorSource): Sensor? = manager.getDefaultSensor(when (source) {
        TrackedSensorSource.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        TrackedSensorSource.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        else -> error("Not a motion sensor: $source")
    })

    private fun reportInventory() {
        report("INVENTORY sensors=" + sources.joinToString { source ->
            "$source=${sensor(source)?.let { "${it.name}/${it.vendor}" } ?: "ABSENT"}"
        } + " bothMotionSourcesAvailable=${sources.all { sensor(it) != null }}; no stationary/ZUPT proof")
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(25L)
        }
        report("ENV_NATIVE_CALLBACK_NOT_OBSERVED stage=$stage timeoutMs=15000; background UID state requires separate inspection")
        assertTrue("ENV_NATIVE_CALLBACK_NOT_OBSERVED: $stage; availability alone is not receipt", onMain(condition))
    }

    private fun report(text: String) {
        Log.i("NativeMotionLifecycle", text)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NativeMotionLifecycle $text\n") })
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return try { task.get() } catch (failure: ExecutionException) { throw checkNotNull(failure.cause) }
    }

    private data class ProbeReceipt(val measurementNs: Long, val receivedAtNs: Long)

    private class NativeProbe : SensorEventListener {
        val latest = mutableMapOf<TrackedSensorSource, ProbeReceipt>()
        var callbacks = 0
        var wrongThread = false
        override fun onSensorChanged(event: SensorEvent) {
            val source = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> TrackedSensorSource.ACCELEROMETER
                Sensor.TYPE_GYROSCOPE -> TrackedSensorSource.GYROSCOPE
                else -> return
            }
            latest[source] = ProbeReceipt(event.timestamp, SystemClock.elapsedRealtimeNanos())
            callbacks++
            wrongThread = wrongThread || Looper.myLooper() != Looper.getMainLooper()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    companion object {
        private val sources = listOf(TrackedSensorSource.ACCELEROMETER, TrackedSensorSource.GYROSCOPE)
    }
}
