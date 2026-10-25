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
 * The test deliberately withholds app forwarding while SDK callbacks continue. This is an app
 * delivery gap, not proof of a physical sensor outage, stillness, body rotation or field accuracy.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPedestrianMotionGapPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test(timeout = 60_000L)
    fun actualNativeDeliveryGapRetiresStatisticsUntilPreGapSamplesAreEvicted() {
        val registration = NativeRegistration()
        val tracker = onMain {
            AndroidPedestrianMotionTracker(registration, onDecision = registration::captureDecision)
                .also { registration.tracker = it }
        }
        var primaryFailure: Throwable? = null
        try {
            onMain {
                assumeTrue("ENV_BOTH_MOTION_SOURCES_REQUIRED: delivery-gap scenario not run",
                    sources.all { manager.getDefaultSensor(typeOf(it)) != null })
                val started = tracker.start()
                val health = tracker.operationalHealth
                report("REGISTRATION started=$started health=$health")
                registration.assertBothNativeRegistrationAttempts()
                assumeTrue("ENV_BOTH_MOTION_REGISTRATIONS_REQUIRED: $health",
                    registration.bothNativeRegistrationsSucceeded())
                assertTrue("Both successful SDK registrations must activate the producer", started)
                sources.forEach { assertNull(health.source(it)?.lastAcceptedSample) }
                registration.beginObservation(health.registrationGeneration)
            }
            awaitNative(registration, "SUFFICIENT_WINDOW_SETUP", 15_000L) { registration.setupReady }
            onMain { registration.closeForwardingGate() }
            awaitNative(registration, "SDK_CONTINUES_WITHOUT_FORWARDING", 5_000L) { registration.canResumeAfterMeasuredGap() }
            onMain { registration.resumeForwarding() }
            awaitNative(registration, "GAP_RETIREMENT_AND_MEASUREMENT_EVICTION", 15_000L) {
                registration.recoveryVerified || registration.gapOpportunityExpired
            }
            onMain {
                registration.throwCallbackFailure()
                registration.assertObservingProducer()
                val complete = registration.gapRetirementVerified && registration.recoveryVerified
                report("SCENARIO_FINAL ${registration.summary()} complete=$complete")
                registration.throwCallbackFailure()
                registration.endObservation()
                assumeTrue("ENV_GAP_OR_RETENTION_CONDITION_NOT_OBSERVED: ${registration.summary()}", complete)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanup(tracker, registration, primaryFailure)
        }
    }

    private fun awaitNative(registration: NativeRegistration, stage: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (onMain {
                    registration.throwCallbackFailure()
                    registration.assertObservingProducer()
                    condition()
                }) return
            SystemClock.sleep(25L) // Instrumentation worker only; never block Main/sensor delivery.
        }
        onMain {
            registration.throwCallbackFailure()
            registration.assertObservingProducer()
            val observed = condition()
            report("ENV_STAGE_CONDITION stage=$stage observed=$observed ${registration.summary()}")
            registration.throwCallbackFailure()
            if (!observed) registration.endObservation()
            assumeTrue("ENV_STAGE_CONDITION_NOT_OBSERVED: $stage; ${registration.summary()}", observed)
        }
    }

    /** Main owns registration, scalar ledger, health reads and synchronous decision capture. */
    private inner class NativeRegistration : MotionSensorRegistration {
        lateinit var tracker: AndroidPedestrianMotionTracker
        private val wrappers = IdentityHashMap<SensorEventListener, SensorEventListener>()
        private val nativeRegistrationResults = mutableMapOf<TrackedSensorSource, Boolean>()
        private val admitted = mutableListOf<AdmittedInput>()
        private val lastCoverage = mutableMapOf<TrackedSensorSource, String>()
        private val failure = AtomicReference<Throwable>()
        private var delivery: NativeDelivery? = null
        private var observationGeneration: Int? = null
        private var maximumAdmittedNs = -1L
        private var expectedLastEmissionMs = -1L
        private var nativeCallbacks = 0
        private var rejectedInputs = 0
        private var outsideObservationCallbacks = 0
        private var phase = Phase.INITIAL
        private val gatedRaw = mutableListOf<RawInput>()
        private val preGapLast = mutableMapOf<TrackedSensorSource, AdmittedInput>()
        private val firstAfterGate = mutableMapOf<TrackedSensorSource, RawInput>()
        private val firstAdmittedAfterGate = mutableMapOf<TrackedSensorSource, AdmittedInput>()
        private var gateHealth: SensorTrackerHealthSnapshot? = null
        private var gateClosedAtNs: Long? = null
        private var forwardedAtGate = 0
        private var admittedAtGate = 0
        private var forwardedCallbacks = 0
        var setupReady = false
            private set
        var gapRetirementVerified = false
            private set
        var recoveryVerified = false
            private set
        var gapOpportunityExpired = false
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
                        if (phase == Phase.GATED) {
                            gatedRaw += raw
                            assertTrue(raw.entryNs >= checkNotNull(gateClosedAtNs))
                            assertEquals("Gate may not change product health", gateHealth, tracker.operationalHealth)
                            assertEquals("SDK callbacks continue but app forwarding stays zero", forwardedAtGate, forwardedCallbacks)
                            assertEquals(admittedAtGate, admitted.size)
                            return // Discard this actual event; never retain or replay it.
                        }
                        if (phase == Phase.RESUMED) firstAfterGate.putIfAbsent(raw.source, raw)
                        forwardedCallbacks++
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
            val admittedInput = AdmittedInput(raw, accepted)
            admitted += admittedInput
            if (phase == Phase.RESUMED) firstAdmittedAfterGate.putIfAbsent(raw.source, admittedInput)
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
            when (phase) {
                Phase.INITIAL -> setupReady = acceleration?.sufficient == true && gyroscope?.sufficient == true
                Phase.GATED -> error("Gated SDK input must not reach the producer")
                Phase.RESUMED -> verifyGapAndRecovery(emitted.decision, acceleration, gyroscope)
            }
        }

        fun closeForwardingGate() {
            assertObservingProducer()
            assertNull(delivery)
            assertEquals(Phase.INITIAL, phase)
            val generation = checkNotNull(observationGeneration)
            val windows = sources.associateWith { windowOracle(it, generation, expectedLastEmissionMs) }
            val ready = windows.values.all { it?.sufficient == true }
            if (!ready) endObservation()
            assumeTrue("ENV_SUFFICIENT_SETUP_LOST_BEFORE_GATE: $windows", ready)
            sources.forEach { source ->
                preGapLast[source] = admitted.last { it.raw.source == source }
            }
            gateHealth = tracker.operationalHealth
            gateClosedAtNs = SystemClock.elapsedRealtimeNanos()
            forwardedAtGate = forwardedCallbacks
            admittedAtGate = admitted.size
            phase = Phase.GATED
            report("APP_GATE_CLOSED closedAtNs=$gateClosedAtNs preGapLastNs=${preGapLast.mapValues { it.value.raw.measurementNs }} " +
                "windows=$windows; SDKRegistrationRemainsActive=true physicalSensorGap=NOT_CLAIMED")
        }

        fun canResumeAfterMeasuredGap(): Boolean = sources.all { source ->
            val latest = gatedRaw.lastOrNull { it.source == source } ?: return@all false
            latest.measurementNs - checkNotNull(preGapLast[source]).raw.measurementNs > MAXIMUM_GAP_NS
        }

        fun resumeForwarding() {
            assertObservingProducer()
            assertNull(delivery)
            assertEquals(Phase.GATED, phase)
            assertTrue(canResumeAfterMeasuredGap())
            assertEquals(gateHealth, tracker.operationalHealth)
            assertEquals(forwardedAtGate, forwardedCallbacks)
            assertEquals(admittedAtGate, admitted.size)
            phase = Phase.RESUMED
            report("APP_GATE_OPEN openedAtNs=${SystemClock.elapsedRealtimeNanos()} SDKDuringGate=${sources.associateWith { source ->
                val rows = gatedRaw.filter { it.source == source }
                "count=${rows.size},firstNs=${rows.first().measurementNs},lastNs=${rows.last().measurementNs}"
            }} forwardedDuringGate=${forwardedCallbacks - forwardedAtGate}; " +
                "nextOriginalNativeEventsForwardedOnce=true bufferedReplay=0")
        }

        private fun verifyGapAndRecovery(decision: PedestrianStationaryDecision,
            acceleration: WindowOracle?, gyroscope: WindowOracle?) {
            val sample = checkNotNull(decision.sample)
            val windows = mapOf(TrackedSensorSource.ACCELEROMETER to acceleration, TrackedSensorSource.GYROSCOPE to gyroscope)
            val cutoffNs = (maximumAdmittedNs - WINDOW_NS).coerceAtLeast(0L)
            val firstEventsAdmitted = sources.all { source ->
                firstAfterGate[source]?.measurementNs != null &&
                    firstAfterGate[source]?.measurementNs == firstAdmittedAfterGate[source]?.raw?.measurementNs
            }
            val bothGapEdgesRetained = firstEventsAdmitted && sources.all { source ->
                val before = checkNotNull(preGapLast[source]).raw.measurementNs
                val after = checkNotNull(firstAdmittedAfterGate[source]).raw.measurementNs
                val window = windows[source] ?: return@all false
                after - before > MAXIMUM_GAP_NS && before >= cutoffNs && window.startNs <= before && window.endNs >= after &&
                    window.count >= 5 && window.endMs - window.startMs >= 200L && window.gapMs > 250L &&
                    sample.elapsedRealtimeMs >= window.endMs && sample.elapsedRealtimeMs - window.endMs <= 250L
            }
            if (!gapRetirementVerified && bothGapEdgesRetained) {
                assertNull(sample.accelerationWindow)
                assertNull(sample.accelerationMagnitudeMeanMps2)
                assertNull(sample.accelerationMagnitudeVarianceMps4)
                assertNull(sample.gyroscopeWindow)
                assertNull(sample.gyroscopeRmsRadPerSecond)
                assertNull(sample.gyroscopePeakRadPerSecond)
                assertFalse("Measured admitted gap must retire statistics and prohibit ZUPT", decision.shouldApplyZeroVelocityUpdate)
                gapRetirementVerified = true
                report("ADMITTED_GAP_RETIRED emitterNs=$maximumAdmittedNs cutoffNs=$cutoffNs " +
                    "preGapLastNs=${preGapLast.mapValues { it.value.raw.measurementNs }} " +
                    "actualFirstAfterNs=${firstAdmittedAfterGate.mapValues { it.value.raw.measurementNs }} " +
                    "gapNs=${sources.associateWith { checkNotNull(firstAdmittedAfterGate[it]).raw.measurementNs -
                        checkNotNull(preGapLast[it]).raw.measurementNs }} rawWindows=$windows " +
                    "productCoverageAndStatistics=null ZUPT=false")
            }
            if (!gapRetirementVerified && sources.any { cutoffNs > checkNotNull(preGapLast[it]).raw.measurementNs }) {
                gapOpportunityExpired = true // Environmental timing cannot satisfy the retained gap-edge scenario now.
            }
            val evictedBeforeButAfterStillRetained = firstEventsAdmitted && sources.all { source ->
                val before = checkNotNull(preGapLast[source]).raw.measurementNs
                val after = checkNotNull(firstAdmittedAfterGate[source]).raw.measurementNs
                val window = windows[source] ?: return@all false
                cutoffNs > before && cutoffNs <= after && window.startNs == after && window.sufficient
            }
            if (gapRetirementVerified && !recoveryVerified && evictedBeforeButAfterStillRetained) {
                sources.forEach { source ->
                    val lastBefore = checkNotNull(preGapLast[source]).raw.measurementNs
                    assertTrue(cutoffNs > lastBefore) // Product trims strictly < cutoff, never <=.
                    assertTrue(checkNotNull(windows[source]).startNs > lastBefore)
                    assertTrue(admitted.any { it.raw.source == source && it.raw.measurementNs == lastBefore })
                }
                recoveryVerified = true // Earlier independent assertions proved actual coverage and statistics match.
                report("MEASUREMENT_WINDOW_RECOVERED emitterNs=$maximumAdmittedNs cutoffNs=$cutoffNs " +
                    "excludedOldCounts=${sources.associateWith { source -> admitted.count {
                        it.raw.source == source && it.raw.measurementNs <= checkNotNull(preGapLast[source]).raw.measurementNs &&
                            it.raw.measurementNs < cutoffNs } }} rawWindows=$windows " +
                    "oldGapLeftEdgeExcluded=true firstPostGapEdgeRetained=true bothStatisticsRestored=true")
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
            // Keep raw coverage even when product coverage/statistics must be null.
            val sufficient = rows.size >= 5 && startMs >= 0L && endMs - startMs >= 200L && gapMs in 0L..250L &&
                nowMs >= endMs && nowMs - endMs <= 250L
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
            return WindowOracle(count, rows.first().raw.measurementNs, rows.last().raw.measurementNs,
                startMs, endMs, gapMs, mean, variance, sqrt(mean * mean + variance), peak, sufficient)
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
            "outsideObservationCallbacks=$outsideObservationCallbacks phase=$phase gatedSDK=${gatedRaw.size} " +
            "gapRetired=$gapRetirementVerified recovered=$recoveryVerified gapOpportunityExpired=$gapOpportunityExpired " +
            "lastNativeCoverage=$lastCoverage"

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
        if (expected == null || !expected.sufficient) {
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
        if (expected == null || !expected.sufficient) {
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
        putString("stream", "NATIVE_MOTION_GAP $text\n")
    })

    private data class RawInput(val source: TrackedSensorSource, val measurementNs: Long,
        val x: Double?, val y: Double?, val z: Double?, val entryNs: Long) {
        val finiteVector: Boolean get() = x?.isFinite() == true && y?.isFinite() == true && z?.isFinite() == true
    }
    private data class AdmittedInput(val raw: RawInput, val accepted: AcceptedSensorSample)
    private data class NativeDelivery(val raw: RawInput, val before: SensorTrackerHealthSnapshot,
        val decisions: MutableList<EmittedDecision> = mutableListOf())
    private data class EmittedDecision(val decision: PedestrianStationaryDecision, val health: SensorTrackerHealthSnapshot)
    private data class WindowOracle(val count: Int, val startNs: Long, val endNs: Long,
        val startMs: Long, val endMs: Long, val gapMs: Long, val mean: Double, val variance: Double,
        val rms: Double, val peak: Double, val sufficient: Boolean)
    private enum class Phase { INITIAL, GATED, RESUMED }

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
        const val MAXIMUM_GAP_NS = 250_000_000L
        val sources = listOf(TrackedSensorSource.ACCELEROMETER, TrackedSensorSource.GYROSCOPE)
    }
}
