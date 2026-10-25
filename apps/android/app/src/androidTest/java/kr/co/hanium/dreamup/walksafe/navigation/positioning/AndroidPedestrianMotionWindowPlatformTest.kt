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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.AssumptionViolatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SensorManager input and the existing registration constructor only. The native event is
 * forwarded unchanged inside its callback; only scalar copies survive that callback. No synthetic
 * event, direct onSensorSample, clock/step/detector injection or MainActivity launch is used.
 * This checks producer window arithmetic, not physical stillness, body rotation or field accuracy.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPedestrianMotionWindowPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test(timeout = 45_000L)
    fun actualNativeSufficientWindowsMatchTheirAdmittedScalarInputs() {
        val registration = NativeRegistration()
        val tracker = onMain {
            AndroidPedestrianMotionTracker(registration, onDecision = registration::captureDecision)
                .also { registration.tracker = it }
        }
        var primaryFailure: Throwable? = null
        try {
            onMain {
                assumeTrue("ENV_BOTH_MOTION_SOURCES_REQUIRED: sufficient ACC/GYR window not run",
                    sources.all { manager.getDefaultSensor(typeOf(it)) != null })
                val started = tracker.start()
                val health = tracker.operationalHealth
                report("REGISTRATION started=$started health=$health")
                registration.assertBothNativeRegistrationAttempts()
                assumeTrue("ENV_BOTH_MOTION_REGISTRATIONS_REQUIRED: $health",
                    registration.bothNativeRegistrationsSucceeded())
                assertTrue("Both successful SDK registrations must activate the producer", started)
                assertEquals(SensorTrackerLifecycle.ACTIVE, health.lifecycle)
                assertFalse(health.cleanupPending)
                sources.forEach { source ->
                    assertEquals(SensorRegistrationOutcome.REGISTERED,
                        health.source(source)?.lastRegistrationOutcome)
                    assertTrue(health.source(source)?.acceptingSamples == true)
                    assertNull(health.source(source)?.lastAcceptedSample)
                }
                registration.beginObservation(health.registrationGeneration)
            }
            val deadlineMs = SystemClock.elapsedRealtime() + NATIVE_WINDOW_TIMEOUT_MS
            var verified = false
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                verified = onMain {
                    registration.throwCallbackFailure()
                    registration.assertObservingProducer()
                    registration.sufficientWindowVerified
                }
                if (verified) break
                // Wait on the instrumentation thread, leaving actual Main sensor callbacks free.
                SystemClock.sleep(25L)
            }
            onMain {
                registration.throwCallbackFailure()
                registration.assertObservingProducer()
                verified = registration.sufficientWindowVerified
                report("WINDOW_OBSERVATION ${registration.summary()} verified=$verified")
                registration.throwCallbackFailure()
                // End the owned observation on this serial context before an ENV assumption.
                // No active callback can enter between the last failure check and this fence.
                registration.endObservation()
                // Eligible native inputs and each emitted decision are asserted in the callback.
                // This assumption covers missing native supply/coverage, not arithmetic mismatch.
                assumeTrue("ENV_NATIVE_SUFFICIENT_WINDOW_NOT_OBSERVED: ${registration.summary()}", verified)
                assertTrue("The initial insufficient native window must also be observed",
                    registration.initialInsufficientWindowVerified)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanup(tracker, registration, primaryFailure)
        }
    }

    @Test
    fun rejectedScalarObservationsKeepFactsAndDoNotInventCausalReasons() {
        // Observation-method controls only: these values are never supplied to the tracker/SDK.
        val prior = AcceptedSensorSample(7, 800L, 900L)
        val before = SensorTrackerHealthSnapshot(7, SensorTrackerLifecycle.ACTIVE, null, false,
            sources.map { source -> SensorSourceHealth(source, true, SensorRegistrationOutcome.REGISTERED,
                true, prior.takeIf { source == TrackedSensorSource.ACCELEROMETER }) })
        val accRaw = RawInput(TrackedSensorSource.ACCELEROMETER, 700L,
            Double.NaN, 2.0, 3.0, 1_000L)
        val acc = checkNotNull(rejectionObservation(accRaw, before, before, 1_100L, emptyList()))
        assertEquals(accRaw, acc.raw)
        assertEquals(before, acc.before)
        assertEquals(before, acc.after)
        assertEquals(1_100L, acc.exitNs)
        assertEquals(listOf("MISSING_OR_NONFINITE_VECTOR", "NONINCREASING_SOURCE_MEASUREMENT_NS"),
            acc.observedConditions)
        assertEquals("UNKNOWN", acc.rawCausalReason) // Two conditions do not reveal the selected guard.

        val gyrRaw = RawInput(TrackedSensorSource.GYROSCOPE, -1L, 0.0, 0.0, 0.0, 1_000L)
        val gyr = checkNotNull(rejectionObservation(gyrRaw, before, before, 1_100L, emptyList()))
        assertEquals(gyrRaw, gyr.raw)
        assertEquals(listOf("NEGATIVE_MEASUREMENT_NS"), gyr.observedConditions)
        assertEquals("UNKNOWN", gyr.rawCausalReason)
        val rows = listOf(acc, gyr)
        assertTrue(rejectionSummary(rows, TrackedSensorSource.ACCELEROMETER).contains("rawRejected=1"))
        assertTrue(rejectionSummary(rows, TrackedSensorSource.GYROSCOPE).contains("rawRejected=1"))
        assertFalse(rejectionSummary(rows, TrackedSensorSource.GYROSCOPE).contains("NONINCREASING_SOURCE"))

        val future = checkNotNull(rejectionObservation(gyrRaw.copy(measurementNs = 1_101L),
            before, before, 1_100L, emptyList()))
        assertEquals(listOf("MEASUREMENT_AFTER_CALLBACK_EXIT_NS"), future.observedConditions)
        assertEquals("UNKNOWN", future.rawCausalReason)
        val withinBounds = checkNotNull(rejectionObservation(gyrRaw.copy(measurementNs = 1_050L),
            before, before, 1_100L, emptyList()))
        assertEquals(listOf("MEASUREMENT_WITHIN_CALLBACK_CLOCK_BOUNDS"), withinBounds.observedConditions)
        assertEquals("UNKNOWN", withinBounds.rawCausalReason) // Product receipt is not wrapper entry.
        val atEntry = checkNotNull(rejectionObservation(gyrRaw.copy(measurementNs = 1_000L),
            before, before, 1_100L, emptyList()))
        assertEquals(listOf("NO_OBSERVED_RAW_INPUT_CONDITION"), atEntry.observedConditions)
        assertEquals("UNKNOWN", atEntry.rawCausalReason)

        val old = AcceptedSensorSample(7, 1_000_100L, 1_000_150L)
        val fresh = AcceptedSensorSample(7, 1_000_200L, 1_000_330L)
        val oldHealth = before.copy(sources = before.sources.map { it.copy(lastAcceptedSample = old) })
        val newHealth = oldHealth.copy(sources = oldHealth.sources.map {
            if (it.source == TrackedSensorSource.ACCELEROMETER) it.copy(lastAcceptedSample = fresh) else it
        })
        val sameMs = RawInput(TrackedSensorSource.ACCELEROMETER, fresh.measurementElapsedRealtimeNanos,
            0.0, 0.0, 9.8, 1_000_300L)
        assertEquals(old.measurementElapsedRealtimeNanos / NS_PER_MS,
            fresh.measurementElapsedRealtimeNanos / NS_PER_MS)
        assertNull("Accepted input without a new-ms decision is not a raw rejection",
            rejectionObservation(sameMs, oldHealth, newHealth, 1_000_400L, emptyList()))
        val published = PedestrianStationaryDecision(PedestrianStationaryState.UNKNOWN,
            PedestrianStationaryState.UNKNOWN, accepted = false,
            rejectionReason = PedestrianMotionSampleRejectionReason.INVALID_TIME)
        val decision = checkNotNull(rejectionObservation(sameMs, oldHealth, newHealth,
            1_000_400L, listOf(published)))
        assertTrue(decision.inputAdmitted)
        assertEquals("NOT_APPLICABLE", decision.rawCausalReason)
        assertEquals(listOf(published.rejectionReason), decision.decisionRejectionReasons)
        assertTrue(rejectionSummary(listOf(decision), TrackedSensorSource.ACCELEROMETER)
            .contains("rawRejected=0"))
        report("MEASUREMENT_METHOD_CONTROLS verified=true nativeRawRejectionPhysicalEvidence=NOT_TESTED")
    }

    /** Main owns registration, scalar ledger, health reads and synchronous decision capture. */
    private inner class NativeRegistration : MotionSensorRegistration {
        lateinit var tracker: AndroidPedestrianMotionTracker
        private val wrappers = IdentityHashMap<SensorEventListener, SensorEventListener>()
        private val nativeRegistrationResults = mutableMapOf<TrackedSensorSource, Boolean>()
        private val admitted = mutableListOf<AdmittedInput>()
        private val rejectionObservations = mutableListOf<RejectionObservation>()
        private val lastCoverage = mutableMapOf<TrackedSensorSource, String>()
        private val failure = AtomicReference<Throwable>()
        private var delivery: NativeDelivery? = null
        private var observationGeneration: Int? = null
        private var maximumAdmittedNs = -1L
        private var expectedLastEmissionMs = -1L
        private var nativeCallbacks = 0
        private var rejectedInputs = 0
        private var outsideObservationCallbacks = 0
        var sufficientWindowVerified = false
            private set
        var initialInsufficientWindowVerified = false
            private set

        override fun hasSensor(type: Int): Boolean = manager.getDefaultSensor(type) != null

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            assertMain()
            val sensor = manager.getDefaultSensor(type) ?: return false
            val wrapper = wrappers.getOrPut(listener) { object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val entryNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        assertMain()
                        if (failure.get() != null) return
                        if (observationGeneration == null) {
                            outsideObservationCallbacks++
                            listener.onSensorChanged(event)
                            return
                        }
                        assertObservingProducer()
                        nativeCallbacks++
                        val raw = RawInput(sourceOf(event.sensor.type), event.timestamp,
                            event.values.getOrNull(0)?.toDouble(), event.values.getOrNull(1)?.toDouble(),
                            event.values.getOrNull(2)?.toDouble(), entryNs)
                        val pending = NativeDelivery(raw, tracker.operationalHealth)
                        assertNull("Native callbacks must remain serial and non-reentrant", delivery)
                        delivery = pending
                        try {
                            // Original SDK object, original timestamp and original vector. Never retained.
                            listener.onSensorChanged(event)
                        } finally {
                            delivery = null
                        }
                        finishDelivery(pending, tracker.operationalHealth, SystemClock.elapsedRealtimeNanos())
                    } catch (problem: Throwable) {
                        failure.compareAndSet(null, problem)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    try { assertMain(); listener.onAccuracyChanged(sensor, accuracy) }
                    catch (problem: Throwable) { failure.compareAndSet(null, problem) }
                }
            } }
            // Same real registration and delay as the Context adapter, registered on Main.
            return manager.registerListener(wrapper, sensor, SensorManager.SENSOR_DELAY_GAME).also {
                nativeRegistrationResults[sourceOf(type)] = it
            }
        }

        override fun unregister(listener: SensorEventListener?) {
            assertMain()
            val wrapper = wrappers[listener] ?: return
            manager.unregisterListener(wrapper)
            wrappers.remove(listener)
        }

        fun captureDecision(decision: PedestrianStationaryDecision) {
            assertMain()
            if (observationGeneration == null) return
            val pending = checkNotNull(delivery) { "Decision has no original native callback" }
            pending.decisions += EmittedDecision(decision, tracker.operationalHealth)
        }

        private fun finishDelivery(pending: NativeDelivery, after: SensorTrackerHealthSnapshot, exitNs: Long) {
            val raw = pending.raw
            val generation = checkNotNull(observationGeneration)
            assertLiveProducer(pending.before, generation)
            assertLiveProducer(after, generation)
            val beforeSource = checkNotNull(pending.before.source(raw.source))
            val afterSource = checkNotNull(after.source(raw.source))
            assertEquals(pending.before.registrationGeneration, after.registrationGeneration)
            val previous = beforeSource.lastAcceptedSample
            val accepted = afterSource.lastAcceptedSample.takeIf { it != previous }
            // Scalar/immutable facts only. Keep them before any existing admission/decision ASSERT.
            rejectionObservation(raw, pending.before, after, exitNs,
                pending.decisions.map { it.decision })?.let(rejectionObservations::add)
            val eligibleNativeInput = raw.finiteVector &&
                raw.measurementNs >= 0L && raw.measurementNs <= raw.entryNs &&
                (previous == null || raw.measurementNs > previous.measurementElapsedRealtimeNanos)
            if (eligibleNativeInput) {
                assertNotNull("Eligible unchanged native input must reach producer admission: $raw", accepted)
            }
            if (accepted == null) {
                rejectedInputs++
                assertTrue("Rejected input must not emit a decision", pending.decisions.isEmpty())
                return
            }
            assertTrue(raw.finiteVector)
            assertEquals(after.registrationGeneration, accepted.producerRegistrationGeneration)
            assertEquals(raw.measurementNs, accepted.measurementElapsedRealtimeNanos)
            val receiptNs = checkNotNull(accepted.receivedAtElapsedRealtimeNanos)
            assertTrue("Product receipt must be inside the original native callback", receiptNs in raw.entryNs..exitNs)
            assertTrue(receiptNs >= raw.measurementNs)
            assertTrue(previous == null || raw.measurementNs > previous.measurementElapsedRealtimeNanos)
            sources.filter { it != raw.source }.forEach { source ->
                assertEquals(pending.before.source(source), after.source(source))
            }
            // Health supplies actual generation/measurement/receipt identity, not a separate probe.
            admitted += AdmittedInput(raw, accepted)
            maximumAdmittedNs = maxOf(maximumAdmittedNs, raw.measurementNs)
            val sampleAtMs = raw.measurementNs / NS_PER_MS
            val shouldEmit = sampleAtMs > expectedLastEmissionMs
            assertEquals("One decision only for a strictly newer measurement millisecond",
                if (shouldEmit) 1 else 0, pending.decisions.size)
            if (!shouldEmit) return // Still retained above: same/older-ms accepted inputs count in later windows.
            expectedLastEmissionMs = sampleAtMs
            val emitted = pending.decisions.single()
            assertEquals(after, emitted.health)
            assertTrue("Valid native window sample must be accepted by the detector", emitted.decision.accepted)
            val sample = checkNotNull(emitted.decision.sample)
            assertEquals(sampleAtMs, sample.elapsedRealtimeMs)
            val acceleration = windowOracle(TrackedSensorSource.ACCELEROMETER, generation, sampleAtMs)
            val gyroscope = windowOracle(TrackedSensorSource.GYROSCOPE, generation, sampleAtMs)
            assertAcceleration(sample, acceleration)
            assertGyroscope(sample, gyroscope)
            if (acceleration == null && gyroscope == null) {
                assertFalse("An insufficient native window must not request ZUPT",
                    emitted.decision.shouldApplyZeroVelocityUpdate)
                initialInsufficientWindowVerified = true
            }
            if (acceleration != null && gyroscope != null && !sufficientWindowVerified) {
                assertTrue(initialInsufficientWindowVerified)
                sufficientWindowVerified = true
                report("NATIVE_WINDOW_VERIFIED emitterNs=${raw.measurementNs} productReceiptNs=$receiptNs " +
                    "generation=$generation sampleAtMs=$sampleAtMs ACC=$acceleration GYR=$gyroscope; " +
                    "physicalStillnessBodyRotationFieldAccuracy=NOT_TESTED")
            }
        }

        private fun windowOracle(source: TrackedSensorSource, generation: Int, nowMs: Long): WindowOracle? {
            // This oracle is evaluated only at an emitted, strictly newer millisecond: its
            // original emitter is then the newest admitted NS and trims both queues to this bound.
            val oldestRetainedNs = (maximumAdmittedNs - WINDOW_NS).coerceAtLeast(0L)
            val rows = admitted.filter { it.raw.source == source &&
                it.accepted.producerRegistrationGeneration == generation && it.raw.measurementNs >= oldestRetainedNs }
            if (rows.isEmpty()) {
                lastCoverage[source] = "EMPTY"
                return null
            }
            val startMs = rows.first().raw.measurementNs / NS_PER_MS
            val endMs = rows.last().raw.measurementNs / NS_PER_MS
            val gapNs = rows.zipWithNext { left, right -> right.raw.measurementNs - left.raw.measurementNs }
                .maxOrNull() ?: 0L
            val gapMs = gapNs / NS_PER_MS + if (gapNs % NS_PER_MS == 0L) 0L else 1L
            lastCoverage[source] = "count=${rows.size},startMs=$startMs,endMs=$endMs,maxGapMs=$gapMs,nowMs=$nowMs"
            // Existing policy, independently evaluated from copied native measurement times.
            if (rows.size < 5 || startMs < 0L || endMs - startMs < 200L || gapMs !in 0L..250L ||
                nowMs < endMs || nowMs - endMs > 250L) return null
            var count = 0
            var mean = 0.0
            var m2 = 0.0
            var peak = 0.0
            rows.forEach { input ->
                val raw = input.raw
                // hypot + Welford differ from the product's sqrt/sum/two-pass variance calculation.
                val magnitude = Math.hypot(Math.hypot(checkNotNull(raw.x), checkNotNull(raw.y)), checkNotNull(raw.z))
                count++
                val delta = magnitude - mean
                mean += delta / count
                m2 += delta * (magnitude - mean)
                peak = max(peak, magnitude)
            }
            val variance = m2 / count // Population variance, not sample variance.
            return WindowOracle(count, startMs, endMs, gapMs, mean, variance, sqrt(mean * mean + variance), peak)
        }

        fun throwCallbackFailure() { failure.get()?.let { throw it } }
        fun assertBothNativeRegistrationAttempts() {
            assertMain()
            sources.forEach { source ->
                assertTrue("Expected actual SDK registration attempt is missing: $source",
                    nativeRegistrationResults.containsKey(source))
            }
        }
        fun bothNativeRegistrationsSucceeded(): Boolean = sources.all { nativeRegistrationResults[it] == true }
        fun beginObservation(generation: Int) {
            assertMain()
            assertNull(observationGeneration)
            assertLiveProducer(tracker.operationalHealth, generation)
            observationGeneration = generation
        }
        fun assertObservingProducer() {
            assertMain()
            assertLiveProducer(tracker.operationalHealth, checkNotNull(observationGeneration))
        }
        private fun assertLiveProducer(health: SensorTrackerHealthSnapshot, generation: Int) {
            assertEquals("Owned observation must remain in its registration generation",
                generation, health.registrationGeneration)
            assertEquals(SensorTrackerLifecycle.ACTIVE, health.lifecycle)
            assertFalse(health.cleanupPending)
            sources.forEach { source ->
                assertEquals(true, nativeRegistrationResults[source])
                val producer = checkNotNull(health.source(source))
                assertEquals(SensorRegistrationOutcome.REGISTERED, producer.lastRegistrationOutcome)
                assertTrue("Successful SDK source must remain accepting during observation: $source",
                    producer.acceptingSamples)
            }
        }
        fun endObservation() {
            assertMain()
            assertNull("Observation must end outside a native callback", delivery)
            observationGeneration = null
        }
        fun summary(): String = "nativeCallbacks=$nativeCallbacks admitted=${admitted.size} rejected=$rejectedInputs " +
            "ACC=${admitted.count { it.raw.source == TrackedSensorSource.ACCELEROMETER }} " +
            "GYR=${admitted.count { it.raw.source == TrackedSensorSource.GYROSCOPE }} " +
            "outsideObservationCallbacks=$outsideObservationCallbacks lastNativeCoverage=$lastCoverage"

        fun reportRejections() {
            assertMain()
            assertNull("Report after the serial observation fence", observationGeneration)
            rejectionObservations.forEach { report("REJECTION_OBSERVATION $it") }
            sources.forEach { report("REJECTION_SUMMARY ${rejectionSummary(rejectionObservations, it)}") }
        }

        fun close() {
            assertMain()
            var firstFailure: Throwable? = null
            wrappers.keys.toList().forEach { listener ->
                try { unregister(listener) } catch (problem: Throwable) {
                    val first = firstFailure
                    if (first == null) firstFailure = problem else if (problem !== first) first.addSuppressed(problem)
                }
            }
            firstFailure?.let { throw it }
            assertTrue("Only this test's wrapper registrations are checked", wrappers.isEmpty())
        }
    }

    private fun assertAcceleration(sample: PedestrianMotionSample, expected: WindowOracle?) {
        if (expected == null) {
            assertNull(sample.accelerationWindow)
            assertNull(sample.accelerationMagnitudeMeanMps2)
            assertNull(sample.accelerationMagnitudeVarianceMps4)
        } else {
            assertCoverage(checkNotNull(sample.accelerationWindow), expected)
            assertArithmetic("acceleration mean", expected.mean, checkNotNull(sample.accelerationMagnitudeMeanMps2))
            assertArithmetic("acceleration population variance", expected.variance,
                checkNotNull(sample.accelerationMagnitudeVarianceMps4))
        }
    }

    private fun assertGyroscope(sample: PedestrianMotionSample, expected: WindowOracle?) {
        if (expected == null) {
            assertNull(sample.gyroscopeWindow)
            assertNull(sample.gyroscopeRmsRadPerSecond)
            assertNull(sample.gyroscopePeakRadPerSecond)
        } else {
            assertCoverage(checkNotNull(sample.gyroscopeWindow), expected)
            assertArithmetic("gyroscope RMS", expected.rms, checkNotNull(sample.gyroscopeRmsRadPerSecond))
            assertArithmetic("gyroscope peak", expected.peak, checkNotNull(sample.gyroscopePeakRadPerSecond))
        }
    }

    private fun assertCoverage(actual: PedestrianMotionWindowCoverage, expected: WindowOracle) {
        assertEquals(expected.count, actual.sampleCount)
        assertEquals(expected.startMs, actual.startedAtElapsedRealtimeMs)
        assertEquals(expected.endMs, actual.endedAtElapsedRealtimeMs)
        assertEquals(expected.gapMs, actual.maximumGapMs)
    }

    private fun assertArithmetic(name: String, expected: Double, actual: Double) {
        // Floating-operation roundoff only; this is not a sensor accuracy/performance tolerance.
        assertEquals(name, expected, actual, 1e-9 * max(1.0, abs(expected)))
    }

    private fun cleanup(tracker: AndroidPedestrianMotionTracker, registration: NativeRegistration,
        primaryFailure: Throwable?) {
        val failures = try {
            onMain {
                val collected = mutableListOf<Throwable>()
                try { registration.endObservation() } catch (failure: Throwable) { collected += failure }
                try { registration.throwCallbackFailure() } catch (failure: Throwable) {
                    if (failure !== primaryFailure) collected += failure
                }
                try { registration.reportRejections() } catch (failure: Throwable) { collected += failure }
                try { tracker.close() } catch (failure: Throwable) { collected += failure }
                try { registration.close() } catch (failure: Throwable) { collected += failure }
                try {
                    tracker.close() // Clear retained product ownership after any successful wrapper retry.
                    val health = tracker.operationalHealth
                    assertEquals(SensorTrackerLifecycle.CLOSED, health.lifecycle)
                    assertFalse(health.cleanupPending)
                    health.sources.forEach { assertFalse(it.acceptingSamples); assertNull(it.lastAcceptedSample) }
                    report("CLEANUP ownedWrappersUnregistered=true trackerClosed=true ownedWorkerThreads=0")
                } catch (failure: Throwable) { collected += failure }
                try { registration.throwCallbackFailure() } catch (failure: Throwable) {
                    if (failure !== primaryFailure && collected.none { it === failure }) collected += failure
                }
                collected
            }
        } catch (failure: Throwable) { listOf(failure) }
        if (primaryFailure is AssumptionViolatedException && failures.isNotEmpty()) {
            // A real async/cleanup failure must not become a JUnit skip with suppressed errors.
            val actualFailure = failures.first()
            failures.drop(1).filter { it !== actualFailure }.forEach(actualFailure::addSuppressed)
            actualFailure.addSuppressed(primaryFailure)
            throw actualFailure
        } else if (primaryFailure != null) {
            failures.filter { it !== primaryFailure }.forEach(primaryFailure::addSuppressed)
        } else if (failures.isNotEmpty()) {
            failures.drop(1).filter { it !== failures.first() }.forEach(failures.first()::addSuppressed)
            throw failures.first()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        assertTrue("Main queue must accept this bounded test task", mainHandler.post(task))
        return try { task.get(5L, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { throw checkNotNull(failure.cause) }
        catch (failure: TimeoutException) {
            task.cancel(false)
            throw AssertionError("ENV_MAIN_QUEUE_TIMEOUT: no unbounded Main wait", failure)
        } catch (failure: InterruptedException) {
            task.cancel(false)
            throw failure
        }
    }

    private fun assertMain() = assertEquals(Looper.getMainLooper(), Looper.myLooper())
    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "NATIVE_MOTION_WINDOW $text\n")
    })

    private fun rejectionObservation(raw: RawInput, before: SensorTrackerHealthSnapshot,
        after: SensorTrackerHealthSnapshot, exitNs: Long,
        decisions: List<PedestrianStationaryDecision>): RejectionObservation? {
        val previous = before.source(raw.source)?.lastAcceptedSample
        val latest = after.source(raw.source)?.lastAcceptedSample
        val admitted = latest != null && latest != previous
        val decisionReasons = decisions.mapNotNull { it.rejectionReason }
        if (admitted && decisions.all { it.accepted } && decisionReasons.isEmpty()) return null
        val conditions = mutableListOf<String>()
        if (!admitted) {
            if (!raw.finiteVector) conditions += "MISSING_OR_NONFINITE_VECTOR"
            if (raw.measurementNs < 0L) conditions += "NEGATIVE_MEASUREMENT_NS"
            if (previous != null && raw.measurementNs <= previous.measurementElapsedRealtimeNanos)
                conditions += "NONINCREASING_SOURCE_MEASUREMENT_NS"
            if (raw.measurementNs > exitNs) conditions += "MEASUREMENT_AFTER_CALLBACK_EXIT_NS"
            else if (raw.measurementNs > raw.entryNs) conditions += "MEASUREMENT_WITHIN_CALLBACK_CLOCK_BOUNDS"
            if (conditions.isEmpty()) conditions += "NO_OBSERVED_RAW_INPUT_CONDITION"
        }
        // These are observed conditions, not a claim about which internal guard ran first.
        return RejectionObservation(raw, exitNs, before, after, admitted, conditions.toList(), decisionReasons)
    }

    private fun rejectionSummary(rows: List<RejectionObservation>, source: TrackedSensorSource): String {
        val sourceRows = rows.filter { it.raw.source == source }
        val rejected = sourceRows.filter { !it.inputAdmitted }
        val conditions = rejected.flatMap { it.observedConditions }.groupingBy { it }.eachCount()
        val decisionReasons = sourceRows.flatMap { it.decisionRejectionReasons }.groupingBy { it }.eachCount()
        return "source=$source rawRejected=${rejected.size} rawCausalUNKNOWN=${rejected.size} " +
            "observedConditionCounts=$conditions publishedDecisionReasonCounts=$decisionReasons"
    }

    private data class RejectionObservation(val raw: RawInput, val exitNs: Long,
        val before: SensorTrackerHealthSnapshot, val after: SensorTrackerHealthSnapshot,
        val inputAdmitted: Boolean, val observedConditions: List<String>,
        val decisionRejectionReasons: List<PedestrianMotionSampleRejectionReason>) {
        // A failed callback does not publish its product receipt or selected raw-input guard.
        val rawCausalReason: String get() = if (inputAdmitted) "NOT_APPLICABLE" else "UNKNOWN"
        override fun toString(): String = "source=${raw.source} inputAdmitted=$inputAdmitted " +
            "rawCausalReason=$rawCausalReason observedConditions=$observedConditions " +
            "publishedDecisionReasons=$decisionRejectionReasons raw=$raw exitNs=$exitNs before=$before after=$after"
    }

    private data class RawInput(val source: TrackedSensorSource, val measurementNs: Long,
        val x: Double?, val y: Double?, val z: Double?, val entryNs: Long) {
        val finiteVector: Boolean get() = x?.isFinite() == true && y?.isFinite() == true && z?.isFinite() == true
    }
    private data class AdmittedInput(val raw: RawInput, val accepted: AcceptedSensorSample)
    private data class NativeDelivery(val raw: RawInput, val before: SensorTrackerHealthSnapshot,
        val decisions: MutableList<EmittedDecision> = mutableListOf())
    private data class EmittedDecision(val decision: PedestrianStationaryDecision, val health: SensorTrackerHealthSnapshot)
    private data class WindowOracle(val count: Int, val startMs: Long, val endMs: Long, val gapMs: Long,
        val mean: Double, val variance: Double, val rms: Double, val peak: Double)

    private fun typeOf(source: TrackedSensorSource): Int = when (source) {
        TrackedSensorSource.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        TrackedSensorSource.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        else -> error("Not a motion source: $source")
    }
    private fun sourceOf(type: Int): TrackedSensorSource = when (type) {
        Sensor.TYPE_ACCELEROMETER -> TrackedSensorSource.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE -> TrackedSensorSource.GYROSCOPE
        else -> error("Unexpected native source: $type")
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val WINDOW_NS = 1_000_000_000L
        const val NATIVE_WINDOW_TIMEOUT_MS = 15_000L
        val sources = listOf(TrackedSensorSource.ACCELEROMETER, TrackedSensorSource.GYROSCOPE)
    }
}
