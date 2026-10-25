package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AcceptedSensorSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AndroidPedestrianMotionTracker
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionSensorRegistration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorRegistrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerHealthSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerLifecycle
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual native delivery delayed before the product listener's entry check. The original event is
 * forwarded synchronously, on the same Main callback stack, after reentrant stop/start. This does
 * not test a default SDK queued race, a race after product admission, or physical step accuracy.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSensorGenerationDeliveryPlatformRaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Test(timeout = 45_000L)
    fun stepNativeDeliveryFromRetiredListenerCannotReviveRestartedGeneration() {
        val registration = NativeRegistration(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_ACCELEROMETER))
        var counts = 0
        var motionCallbacks = 0
        var restartMotionCallbacks = 0
        val events = mutableListOf<StepEvent>()
        val tracker = onMain { AndroidStepTracker(registration, onStepCountChanged = { counts++ },
            onMotionSensorSample = { motionCallbacks++ }, onStepEvent = events::add) }
        var primaryFailure: Throwable? = null
        try {
            val initial = onMain {
                val started = tracker.start()
                assertTrue("ENV_STEP_REGISTRATION_FAILED: ${tracker.operationalHealth}", started)
                tracker.operationalHealth.also(::assertActive)
            }
            val source = initial.sources.first { it.acceptingSamples }.source
            awaitNative(registration, "STEP_FIRST_CURRENT_NATIVE_RECEIPT") {
                matchesNativeReceipt(registration, tracker.operationalHealth, source)
            }
            var boundary: DeliveryBoundary? = null
            onMain {
                registration.arm(typeOf(source)) { oldListener, event, fact ->
                    assertEquals(initial.registrationGeneration, tracker.operationalHealth.registrationGeneration)
                    assertEquals(source, fact.source)
                    assertNativeClock(fact)
                    val beforeCounts = counts
                    val beforeMotion = motionCallbacks
                    val beforeEvents = events.toList()
                    tracker.stop()
                    assertRetired(tracker.operationalHealth)
                    assertNull(tracker.latestCounterObservation)
                    val restartNs = SystemClock.elapsedRealtimeNanos()
                    val started = tracker.start()
                    assertTrue("ENV_STEP_RESTART_REGISTRATION_FAILED: ${tracker.operationalHealth}", started)
                    val restarted = tracker.operationalHealth.also(::assertActive)
                    assertTrue(restarted.registrationGeneration > initial.registrationGeneration)
                    assertNotSame(oldListener, registration.productListener())

                    // This is the actual SDK object, still inside its original native callback.
                    oldListener.onSensorChanged(event)
                    assertEquals(fact.measurementNs, event.timestamp)
                    assertEquals("Retired native input must leave the new health unchanged", restarted, tracker.operationalHealth)
                    assertNull(tracker.latestCounterObservation)
                    assertEquals(if (restarted.source(TrackedSensorSource.STEP_COUNTER)?.acceptingSamples == true) {
                        StepCounterBaselineState.NOT_ESTABLISHED
                    } else StepCounterBaselineState.NOT_REGISTERED, tracker.counterBaselineState)
                    assertEquals(beforeCounts, counts)
                    assertEquals(beforeMotion, motionCallbacks)
                    assertEquals(beforeEvents, events)
                    restartMotionCallbacks = motionCallbacks
                    boundary = DeliveryBoundary(restarted.registrationGeneration, restartNs, fact)
                    report("STEP_OLD_ENTRY_REJECTED source=$source oldGeneration=${initial.registrationGeneration} " +
                        "newGeneration=${restarted.registrationGeneration} nativeMeasurementNs=${fact.measurementNs} " +
                        "wrapperReceiptNs=${fact.wrapperReceiptNs}")
                }
            }
            awaitNative(registration, "STEP_RETIRED_NATIVE_ENTRY_BOUNDARY") { boundary != null }
            val completed = onMain { checkNotNull(boundary) }
            awaitNative(registration, "STEP_NEW_GENERATION_NATIVE_CONTROL") {
                matchesNativeReceipt(registration, tracker.operationalHealth, source, completed)
            }
            onMain {
                val sample = checkNotNull(tracker.operationalHealth.source(source)?.lastAcceptedSample)
                assertEquals(completed.generation, sample.producerRegistrationGeneration)
                assertTrue("Current native input must resume the motion callback", motionCallbacks > restartMotionCallbacks)
                tracker.latestCounterObservation?.let {
                    assertEquals(completed.generation, it.producerRegistrationGeneration)
                    assertEquals(sample.measurementElapsedRealtimeNanos, it.current.measurementElapsedRealtimeNanos)
                }
                report("STEP_CURRENT_NATIVE_CONTROL source=$source sample=$sample " +
                    "mode=${if (source == TrackedSensorSource.ACCELEROMETER) "FALLBACK_RECEIPT_ONLY" else "HARDWARE_RECEIPT"} " +
                    "emittedStepEvents=${events.size}; physicalSteps=NOT_ASSERTED")
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            finish(registration, primaryFailure) { tracker.stop() }
        }
    }

    @Test(timeout = 45_000L)
    fun motionNativeDeliveryFromRetiredListenerCannotReviveRestartedGeneration() {
        val registration = NativeRegistration(setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE))
        var decisions = 0
        var restartDecisionCount = 0
        val tracker = onMain { AndroidPedestrianMotionTracker(registration, onDecision = { decisions++ }) }
        var primaryFailure: Throwable? = null
        try {
            val initial = onMain {
                val started = tracker.start()
                assertTrue("ENV_MOTION_REGISTRATION_FAILED: ${tracker.operationalHealth}", started)
                tracker.operationalHealth.also(::assertActive)
            }
            val source = initial.sources.first { it.acceptingSamples }.source
            awaitNative(registration, "MOTION_FIRST_CURRENT_NATIVE_RECEIPT") {
                matchesNativeReceipt(registration, tracker.operationalHealth, source)
            }
            var boundary: DeliveryBoundary? = null
            onMain {
                registration.arm(typeOf(source)) { oldListener, event, fact ->
                    assertEquals(initial.registrationGeneration, tracker.operationalHealth.registrationGeneration)
                    assertEquals(source, fact.source)
                    assertNativeClock(fact)
                    val beforeDecisions = decisions
                    tracker.stop()
                    assertRetired(tracker.operationalHealth)
                    val restartNs = SystemClock.elapsedRealtimeNanos()
                    val started = tracker.start()
                    assertTrue("ENV_MOTION_RESTART_REGISTRATION_FAILED: ${tracker.operationalHealth}", started)
                    val restarted = tracker.operationalHealth.also(::assertActive)
                    assertTrue(restarted.registrationGeneration > initial.registrationGeneration)
                    assertNotSame(oldListener, registration.productListener())
                    oldListener.onSensorChanged(event)
                    assertEquals(fact.measurementNs, event.timestamp)
                    assertEquals("Retired native input must leave all new source receipts unchanged", restarted, tracker.operationalHealth)
                    assertEquals(beforeDecisions, decisions)
                    restartDecisionCount = decisions
                    boundary = DeliveryBoundary(restarted.registrationGeneration, restartNs, fact)
                    report("MOTION_OLD_ENTRY_REJECTED source=$source oldGeneration=${initial.registrationGeneration} " +
                        "newGeneration=${restarted.registrationGeneration} nativeMeasurementNs=${fact.measurementNs} " +
                        "wrapperReceiptNs=${fact.wrapperReceiptNs}")
                }
            }
            awaitNative(registration, "MOTION_RETIRED_NATIVE_ENTRY_BOUNDARY") { boundary != null }
            val completed = onMain { checkNotNull(boundary) }
            awaitNative(registration, "MOTION_NEW_GENERATION_NATIVE_CONTROL") {
                matchesNativeReceipt(registration, tracker.operationalHealth, source, completed)
            }
            onMain {
                assertEquals(completed.generation,
                    checkNotNull(tracker.operationalHealth.source(source)?.lastAcceptedSample).producerRegistrationGeneration)
                assertTrue("Current native input must publish a motion decision, without asserting its classification",
                    decisions > restartDecisionCount)
                report("MOTION_CURRENT_NATIVE_CONTROL source=$source sample=${tracker.operationalHealth.source(source)?.lastAcceptedSample}; " +
                    "classificationAccuracy=NOT_ASSERTED")
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            finish(registration, primaryFailure) { tracker.close() }
        }
    }

    private fun assertActive(health: SensorTrackerHealthSnapshot) {
        assertEquals(SensorTrackerLifecycle.ACTIVE, health.lifecycle)
        assertFalse(health.cleanupPending)
        assertTrue(health.sources.any { it.acceptingSamples })
        health.sources.forEach {
            assertEquals(it.lastRegistrationOutcome == SensorRegistrationOutcome.REGISTERED, it.acceptingSamples)
            assertNull("New generation must begin without accepted input", it.lastAcceptedSample)
        }
    }

    private fun assertRetired(health: SensorTrackerHealthSnapshot) {
        assertEquals(SensorTrackerLifecycle.STOPPED, health.lifecycle)
        assertFalse(health.cleanupPending)
        health.sources.forEach { assertFalse(it.acceptingSamples); assertNull(it.lastAcceptedSample) }
    }

    private fun matchesNativeReceipt(registration: NativeRegistration, health: SensorTrackerHealthSnapshot,
        source: TrackedSensorSource, boundary: DeliveryBoundary? = null): Boolean {
        val fact = registration.latest(registration.productListener(), source) ?: return false
        val sample: AcceptedSensorSample = health.source(source)?.lastAcceptedSample ?: return false
        if (sample.measurementElapsedRealtimeNanos != fact.measurementNs) return false
        assertNativeClock(fact)
        assertEquals(health.registrationGeneration, sample.producerRegistrationGeneration)
        val receiptNs = checkNotNull(sample.receivedAtElapsedRealtimeNanos)
        assertTrue("Wrapper and product receipts are separate facts", receiptNs >= fact.wrapperReceiptNs)
        assertTrue(receiptNs <= SystemClock.elapsedRealtimeNanos())
        if (boundary != null) {
            assertEquals(boundary.generation, sample.producerRegistrationGeneration)
            assertNotSame(boundary.oldFact.listener, fact.listener)
            assertTrue(fact.wrapperReceiptNs >= boundary.restartNs)
        }
        return true
    }

    private fun assertNativeClock(fact: NativeReceipt) {
        assertTrue("ENV_NATIVE_TIMESTAMP_NEGATIVE", fact.measurementNs >= 0L)
        assertTrue("ENV_NATIVE_TIMESTAMP_AFTER_WRAPPER_RECEIPT", fact.measurementNs <= fact.wrapperReceiptNs)
    }

    private fun awaitNative(registration: NativeRegistration, stage: String, predicate: () -> Boolean) {
        val deadlineMs = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            registration.failure.get()?.let { throw it }
            if (onMain(predicate)) return
            SystemClock.sleep(20L)
        }
        registration.failure.get()?.let { throw it }
        throw AssertionError("ENV_NATIVE_BOUNDARY_NOT_OBSERVED stage=$stage; availability/delivery failure is not SKIP or native PASS")
    }

    private fun finish(registration: NativeRegistration, primaryFailure: Throwable?, retire: () -> Unit) {
        val failures = try {
            onMain {
                registration.disarm()
                val collected = mutableListOf<Throwable>()
                try { retire() } catch (failure: Throwable) { collected += failure }
                try { registration.close() } catch (failure: Throwable) { collected += failure }
                registration.failure.get()?.let { if (it !== primaryFailure) collected += it }
                collected
            }
        } catch (failure: Throwable) { listOf(failure) }
        if (primaryFailure != null) {
            failures.filter { it !== primaryFailure }.forEach(primaryFailure::addSuppressed)
        } else if (failures.isNotEmpty()) {
            failures.drop(1).filter { it !== failures.first() }.forEach(failures.first()::addSuppressed)
            throw failures.first()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return try { task.get() } catch (failure: ExecutionException) { throw checkNotNull(failure.cause) }
    }

    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "NATIVE_GENERATION_DELIVERY $text\n")
    })

    private data class NativeReceipt(val listener: SensorEventListener, val source: TrackedSensorSource,
        val measurementNs: Long, val wrapperReceiptNs: Long)
    private data class DeliveryBoundary(val generation: Int, val restartNs: Long, val oldFact: NativeReceipt)

    /** Existing constructor DI, with real SensorManager registration and one wrapper per identity. */
    private inner class NativeRegistration(private val types: Set<Int>) : StepSensorRegistration, MotionSensorRegistration {
        private val wrappers = IdentityHashMap<SensorEventListener, SensorEventListener>()
        private val receipts = IdentityHashMap<SensorEventListener, MutableMap<TrackedSensorSource, NativeReceipt>>()
        val failure = AtomicReference<Throwable>()
        private var armedType: Int? = null
        private var action: ((SensorEventListener, SensorEvent, NativeReceipt) -> Unit)? = null

        override fun hasSensor(type: Int): Boolean = (type in types && manager.getDefaultSensor(type) != null).also {
            report("AVAILABILITY type=$type present=$it")
        }

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            assertMain()
            val sensor = if (type in types) manager.getDefaultSensor(type) else null
            if (sensor == null) {
                report("AVAILABILITY type=$type present=false")
                return false
            }
            val wrapper = wrappers.getOrPut(listener) { object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val receiptNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        assertMain()
                        val source = sourceOf(event.sensor.type)
                        val fact = NativeReceipt(listener, source, event.timestamp, receiptNs)
                        receipts.getOrPut(listener) { mutableMapOf() }[source] = fact
                        val delayed = action.takeIf { armedType == event.sensor.type }
                        if (delayed != null) {
                            assertSame(this, wrappers[listener])
                            disarm()
                            // No event escapes this callback and no worker waits while Main is blocked.
                            try { delayed(listener, event, fact) } finally { disarm() }
                        } else listener.onSensorChanged(event)
                    } catch (problem: Throwable) {
                        failure.compareAndSet(null, problem)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    try { listener.onAccuracyChanged(sensor, accuracy) }
                    catch (problem: Throwable) { failure.compareAndSet(null, problem) }
                }
            } }
            val delay = if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                SensorManager.SENSOR_DELAY_NORMAL
            } else SensorManager.SENSOR_DELAY_GAME
            return manager.registerListener(wrapper, sensor, delay).also {
                report("REGISTRATION type=$type present=true registered=$it")
            }
        }

        override fun unregister(listener: SensorEventListener?) {
            assertMain()
            val wrapper = wrappers[listener] ?: return
            manager.unregisterListener(wrapper)
            wrappers.remove(listener)
            receipts.remove(listener)
        }

        fun productListener(): SensorEventListener = wrappers.keys.single()
        fun latest(listener: SensorEventListener, source: TrackedSensorSource): NativeReceipt? = receipts[listener]?.get(source)
        fun arm(type: Int, next: (SensorEventListener, SensorEvent, NativeReceipt) -> Unit) {
            assertMain()
            check(action == null)
            armedType = type
            action = next
        }
        fun disarm() { armedType = null; action = null }
        fun close() {
            assertMain()
            disarm()
            var firstFailure: Throwable? = null
            wrappers.keys.toList().forEach { listener ->
                try { unregister(listener) } catch (problem: Throwable) {
                    val first = firstFailure
                    if (first == null) firstFailure = problem else if (problem !== first) first.addSuppressed(problem)
                }
            }
            firstFailure?.let { throw it }
            assertTrue("All test-owned native registrations must be released", wrappers.isEmpty())
            report("CLEANUP nativeRegistrations=0 testWorkerThreads=0 eventRetainedOutsideCallback=false")
        }
        private fun assertMain() = assertSame(Looper.getMainLooper(), Looper.myLooper())
    }

    private fun typeOf(source: TrackedSensorSource): Int = when (source) {
        TrackedSensorSource.STEP_COUNTER -> Sensor.TYPE_STEP_COUNTER
        TrackedSensorSource.STEP_DETECTOR -> Sensor.TYPE_STEP_DETECTOR
        TrackedSensorSource.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        TrackedSensorSource.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
    }
    private fun sourceOf(type: Int): TrackedSensorSource = when (type) {
        Sensor.TYPE_STEP_COUNTER -> TrackedSensorSource.STEP_COUNTER
        Sensor.TYPE_STEP_DETECTOR -> TrackedSensorSource.STEP_DETECTOR
        Sensor.TYPE_ACCELEROMETER -> TrackedSensorSource.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE -> TrackedSensorSource.GYROSCOPE
        else -> error("Unexpected native source: $type")
    }
}
