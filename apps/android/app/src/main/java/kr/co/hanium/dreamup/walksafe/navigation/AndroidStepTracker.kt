package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerHealthRecorder
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerHealthSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerStoppedReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import kotlin.math.sqrt

enum class StepEventSource {
    STEP_DETECTOR,
    STEP_COUNTER,
    ACCELEROMETER_FALLBACK,
}

enum class StepEventConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * S09-20: whether this registration's Step Counter baseline exists. Until the first Counter sample
 * the session count comes from the Detector (if any); the Counter's cumulative total before that
 * sample is never counted as a session step.
 */
enum class StepCounterBaselineState {
    NOT_REGISTERED,
    NOT_ESTABLISHED,
    ESTABLISHED,
}

/** An accepted raw Counter sample, including its actual receipt when supplied. */
data class StepCounterSample(
    val cumulativeSteps: Float,
    val measurementElapsedRealtimeNanos: Long,
    val receivedAtElapsedRealtimeNanos: Long? = null,
) {
    internal fun isValid(receivedAtMs: Long): Boolean = cumulativeSteps.isFinite() && cumulativeSteps >= 0f &&
        measurementElapsedRealtimeNanos >= 0L &&
        (receivedAtElapsedRealtimeNanos == null || (receivedAtElapsedRealtimeNanos >= measurementElapsedRealtimeNanos &&
            receivedAtElapsedRealtimeNanos / 1_000_000L <= receivedAtMs))
}

/** Sample intervals are observation spans, not the physical interval of each emitted step. */
data class StepCounterObservation(
    val producerRegistrationGeneration: Int,
    val baseline: StepCounterSample,
    val previous: StepCounterSample?,
    val current: StepCounterSample,
) {
    internal fun isConsistentWith(event: StepEvent, receivedAtMs: Long): Boolean {
        val baselineReceiptNs = baseline.receivedAtElapsedRealtimeNanos
        val previousReceiptNs = previous?.receivedAtElapsedRealtimeNanos
        val currentReceiptNs = current.receivedAtElapsedRealtimeNanos
        return producerRegistrationGeneration >= 0 && baseline.isValid(receivedAtMs) && current.isValid(receivedAtMs) &&
            baseline.measurementElapsedRealtimeNanos <= current.measurementElapsedRealtimeNanos &&
            (previous == null || (previous.isValid(receivedAtMs) &&
                previous.measurementElapsedRealtimeNanos >= baseline.measurementElapsedRealtimeNanos &&
                previous.measurementElapsedRealtimeNanos < current.measurementElapsedRealtimeNanos &&
                (previous.measurementElapsedRealtimeNanos != baseline.measurementElapsedRealtimeNanos ||
                    (previous.cumulativeSteps == baseline.cumulativeSteps &&
                        (baselineReceiptNs == null || previousReceiptNs == null ||
                            baselineReceiptNs == previousReceiptNs))))) &&
            (baselineReceiptNs == null || previousReceiptNs == null || baselineReceiptNs <= previousReceiptNs) &&
            (baselineReceiptNs == null || currentReceiptNs == null || baselineReceiptNs <= currentReceiptNs) &&
            (previousReceiptNs == null || currentReceiptNs == null || previousReceiptNs <= currentReceiptNs) &&
            event.source == StepEventSource.STEP_COUNTER &&
            event.producerRegistrationGeneration == producerRegistrationGeneration &&
            event.measurementElapsedRealtimeNanos == current.measurementElapsedRealtimeNanos &&
            event.receivedAtElapsedRealtimeNanos == current.receivedAtElapsedRealtimeNanos
    }
}

data class StepEvent(
    val deltaSteps: Int,
    val timestampMs: Long,
    val source: StepEventSource,
    val confidence: StepEventConfidence,
    /** Original sensor time; [timestampMs] is its display/legacy precision only. */
    val measurementElapsedRealtimeNanos: Long? = null,
    /** Captured at sensor callback entry, not at a later UI dispatch. Missing legacy facts stay null. */
    val receivedAtElapsedRealtimeNanos: Long? = null,
    /** Producer lifecycle boundary, distinct from the positioning owner and any source observation ID. */
    val producerRegistrationGeneration: Int? = null,
    val counterObservation: StepCounterObservation? = null,
) {
    internal fun isConsistentWith(
        stepCount: Int,
        measuredAtMs: Long,
        receivedAtMs: Long,
        expectedSource: StepEventSource?,
        expectedConfidence: StepEventConfidence?,
    ): Boolean = deltaSteps == stepCount && timestampMs == measuredAtMs &&
        (expectedSource == null || source == expectedSource) &&
        (expectedConfidence == null || confidence == expectedConfidence) &&
        (measurementElapsedRealtimeNanos == null ||
            (measurementElapsedRealtimeNanos >= 0L && measurementElapsedRealtimeNanos / 1_000_000L == measuredAtMs)) &&
        (receivedAtElapsedRealtimeNanos == null ||
            (receivedAtElapsedRealtimeNanos >= 0L && receivedAtElapsedRealtimeNanos / 1_000_000L in measuredAtMs..receivedAtMs &&
                (measurementElapsedRealtimeNanos == null || receivedAtElapsedRealtimeNanos >= measurementElapsedRealtimeNanos))) &&
        (producerRegistrationGeneration == null || producerRegistrationGeneration >= 0) &&
        (counterObservation == null || counterObservation.isConsistentWith(this, receivedAtMs))
}

class AndroidStepTracker internal constructor(
    private val registration: StepSensorRegistration,
    private val onStepCountChanged: (Int) -> Unit,
    private val onTrackingStarted: () -> Unit = {},
    private val onMotionSensorSample: (Int) -> Unit = {},
    private val onStepEvent: (StepEvent) -> Unit = {},
) {
    constructor(
        context: Context,
        onStepCountChanged: (Int) -> Unit,
        onTrackingStarted: () -> Unit = {},
        onMotionSensorSample: (Int) -> Unit = {},
        onStepEvent: (StepEvent) -> Unit = {},
    ) : this(
        AndroidStepSensorRegistration(context),
        onStepCountChanged,
        onTrackingStarted,
        onMotionSensorSample,
        onStepEvent,
    )

    // Written on the sensor (main) thread; @Volatile so [counterBaselineState] can be sampled by a status reader.
    @Volatile
    private var activeWindowHardwareBaseline: Float? = null
    private var activeWindowStepOffset = 0
    private var totalActiveSteps = 0

    // S09-17: gravity, peak/trough and interval-continuity fallback detection (still LOW).
    private val fallbackStepDetector = AccelerometerStepPeakDetector()

    // Detector/Counter hand-over state for the current registration window (S09-18). Both sensors
    // count the same walk; PDR events use one common basis so no step is emitted twice.
    private var windowPdrSteps = 0
    private var lastDetectorEventNs: Long? = null
    private var detectorSilentSinceNs: Long? = null
    private var counterCoveredUntilNs = Long.MIN_VALUE
    private var counterBaselineNs = Long.MIN_VALUE

    // S09-20 follow-up (w25 review pos-F10): Detector steps added to the session count at or before the Counter
    // baseline, Detector steps after it, and how many of the former the Counter window has counted again (a
    // device whose first Counter sample is stamped before its total includes those steps).
    private var preBaselineDetectorSteps = 0
    private var detectorStepsAfterBaseline = 0
    private var recountedPreBaselineSteps = 0
    private var latestCounterWindowSteps = 0
    private var latestCounterSampleNs = Long.MIN_VALUE
    // Missing legacy receipts do not erase an earlier accepted receipt in this registration.
    private var latestKnownStepReceiptNs: Long? = null
    @Volatile
    private var latestCounterEvidence: StepCounterObservation? = null

    /** Includes baseline, unchanged and absorbed accepted samples, even when no PDR step is emitted. */
    val latestCounterObservation: StepCounterObservation?
        get() = latestCounterEvidence

    @Volatile
    private var stepCounterRegistered = false
    private var stepDetectorRegistered = false
    private var accelerometerRegistered = false
    private var started = false
    private var registrationGeneration = 0
    private var currentListener: SensorEventListener? = null

    private val health = SensorTrackerHealthRecorder(listOf(
        TrackedSensorSource.STEP_COUNTER, TrackedSensorSource.STEP_DETECTOR, TrackedSensorSource.ACCELEROMETER,
    ))

    /** Registration, accepted input and cleanup facts; current permission is checked by the caller. */
    val operationalHealth: SensorTrackerHealthSnapshot
        get() = health.snapshot

    val usesStepCounter: Boolean
        get() = registration.hasSensor(Sensor.TYPE_STEP_COUNTER)

    val counterBaselineState: StepCounterBaselineState
        get() = when {
            !stepCounterRegistered -> StepCounterBaselineState.NOT_REGISTERED
            activeWindowHardwareBaseline == null -> StepCounterBaselineState.NOT_ESTABLISHED
            else -> StepCounterBaselineState.ESTABLISHED
        }

    fun start(): Boolean {
        if (started) return true
        if (currentListener != null) {
            try {
                stop(SensorTrackerStoppedReason.FAILED_START)
            } catch (_: RuntimeException) {
                // The old registration still needs cleanup; do not add another listener.
                health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
                return false
            }
        }
        health.beginAttempt(registrationGeneration)
        val anySensorAvailable = try {
            probeSensorAvailability(Sensor.TYPE_STEP_COUNTER, TrackedSensorSource.STEP_COUNTER) ||
                probeSensorAvailability(Sensor.TYPE_STEP_DETECTOR, TrackedSensorSource.STEP_DETECTOR) ||
                probeSensorAvailability(Sensor.TYPE_ACCELEROMETER, TrackedSensorSource.ACCELEROMETER)
        } catch (failure: RuntimeException) {
            health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
            throw failure
        }
        if (!anySensorAvailable) {
            health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, false)
            return false
        }
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = totalActiveSteps
        fallbackStepDetector.reset()
        resetDetectorHandOver()
        val generation = ++registrationGeneration
        health.generationStarted(generation)
        lateinit var listener: SensorEventListener
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!started) return
                if (
                    generation != registrationGeneration ||
                    currentListener !== listener
                ) return
                handleSensorChanged(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        currentListener = listener
        try {
            val counterAvailable = registration.hasSensor(Sensor.TYPE_STEP_COUNTER)
            health.availability(TrackedSensorSource.STEP_COUNTER, counterAvailable)
            stepCounterRegistered = counterAvailable &&
                health.register(TrackedSensorSource.STEP_COUNTER) {
                    registration.register(listener, Sensor.TYPE_STEP_COUNTER)
                }
            val detectorAvailable = registration.hasSensor(Sensor.TYPE_STEP_DETECTOR)
            health.availability(TrackedSensorSource.STEP_DETECTOR, detectorAvailable)
            stepDetectorRegistered = detectorAvailable &&
                health.register(TrackedSensorSource.STEP_DETECTOR) {
                    registration.register(listener, Sensor.TYPE_STEP_DETECTOR)
                }
            accelerometerRegistered =
                if (!stepCounterRegistered && !stepDetectorRegistered) {
                    val accelerometerAvailable = registration.hasSensor(Sensor.TYPE_ACCELEROMETER)
                    health.availability(TrackedSensorSource.ACCELEROMETER, accelerometerAvailable)
                    accelerometerAvailable && health.register(TrackedSensorSource.ACCELEROMETER) {
                        registration.register(listener, Sensor.TYPE_ACCELEROMETER)
                    }
                } else {
                    false
                }
        } catch (_: SecurityException) {
            abandonRegistration(listener)
            return false
        } catch (failure: RuntimeException) {
            try {
                stop(SensorTrackerStoppedReason.FAILED_START)
            } catch (cleanupFailure: RuntimeException) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        started = stepCounterRegistered || stepDetectorRegistered || accelerometerRegistered
        if (!started) {
            abandonRegistration(listener)
        } else if (started) {
            health.activate()
            onTrackingStarted()
        }
        return started
    }

    private fun probeSensorAvailability(type: Int, source: TrackedSensorSource): Boolean =
        registration.hasSensor(type).also { health.availability(source, it) }

    private fun abandonRegistration(listener: SensorEventListener) {
        registrationGeneration += 1
        started = false
        stepCounterRegistered = false
        stepDetectorRegistered = false
        accelerometerRegistered = false
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = totalActiveSteps
        fallbackStepDetector.reset()
        resetDetectorHandOver()
        health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
        try {
            registration.unregister(listener)
            if (currentListener === listener) currentListener = null
        } catch (_: RuntimeException) {
            // Failed start remains false; keep the cleanup target for the next retry.
        } finally {
            health.retire(registrationGeneration, SensorTrackerStoppedReason.FAILED_START, currentListener != null)
        }
    }

    fun stop() = stop(SensorTrackerStoppedReason.EXPLICIT_STOP)

    private fun stop(reason: SensorTrackerStoppedReason) {
        registrationGeneration += 1
        val listener = currentListener
        started = false
        health.retire(registrationGeneration, reason, listener != null)
        try {
            registration.unregister(listener)
            if (currentListener === listener) currentListener = null
        } finally {
            stepCounterRegistered = false
            stepDetectorRegistered = false
            accelerometerRegistered = false
            activeWindowHardwareBaseline = null
            activeWindowStepOffset = totalActiveSteps
            fallbackStepDetector.reset()
            resetDetectorHandOver()
            health.retire(registrationGeneration, reason, currentListener != null)
        }
    }

    private fun resetDetectorHandOver() {
        windowPdrSteps = 0
        lastDetectorEventNs = null
        detectorSilentSinceNs = null
        counterCoveredUntilNs = Long.MIN_VALUE
        counterBaselineNs = Long.MIN_VALUE
        preBaselineDetectorSteps = 0
        detectorStepsAfterBaseline = 0
        recountedPreBaselineSteps = 0
        latestCounterWindowSteps = 0
        latestCounterSampleNs = Long.MIN_VALUE
        latestKnownStepReceiptNs = null
        latestCounterEvidence = null
    }

    fun resetForNewWalk() {
        stop()
        activeWindowHardwareBaseline = null
        activeWindowStepOffset = 0
        totalActiveSteps = 0
        fallbackStepDetector.reset()
        onStepCountChanged(0)
    }

    private fun handleSensorChanged(event: SensorEvent) {
        val generation = registrationGeneration
        val receivedAtNs = SystemClock.elapsedRealtimeNanos()
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                if (!onStepCounterTotal(total, event.timestamp, receivedAtNs)) return
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val detectorValue = event.values.firstOrNull() ?: return
                if (!onStepDetected(event.timestamp, receivedAtNs, detectorValue)) return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val z = event.values.getOrNull(2) ?: return
                if (!onAccelerometerSample(x, y, z, event.timestamp, receivedAtNs)) return
            }
        }
        if (generation != registrationGeneration) return
        onMotionSensorSample(totalActiveSteps)
    }

    /** Accelerometer branch of [handleSensorChanged]; internal because JVM tests cannot build a SensorEvent. */
    internal fun onAccelerometerSample(x: Float, y: Float, z: Float, timestampNs: Long, receivedAtNs: Long? = null): Boolean {
        if (receivedAtNs != null && receivedAtNs < timestampNs) return false
        val generation = registrationGeneration
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val previousAcceptedNs = fallbackStepDetector.lastAcceptedSampleTimestampNs
        val steps = fallbackStepDetector.observe(timestampNs, magnitude, Int.MAX_VALUE - totalActiveSteps)
        if (fallbackStepDetector.lastAcceptedSampleTimestampNs == previousAcceptedNs) return false
        health.acceptedSample(TrackedSensorSource.ACCELEROMETER, generation, timestampNs, receivedAtNs)
        steps.forEach { stepAtNs ->
            totalActiveSteps += 1
            onStepCountChanged(totalActiveSteps)
            if (generation != registrationGeneration) return true
            emitStepEvent(
                deltaSteps = 1,
                timestampNs = stepAtNs,
                source = StepEventSource.ACCELEROMETER_FALLBACK,
                confidence = StepEventConfidence.LOW,
                receivedAtNs = receivedAtNs,
            )
            if (generation != registrationGeneration) return true
        }
        return true
    }

    /**
     * Counter branch of [handleSensorChanged]; internal because JVM tests cannot build a SensorEvent.
     * Impossible input (non-finite, negative, untimed, or beyond the integer walk total) is
     * rejected before the baseline, total, or any count/motion callback changes.
     */
    internal fun onStepCounterTotal(total: Float, timestampNs: Long, receivedAtNs: Long? = null): Boolean {
        if (!total.isFinite() || total < 0f || timestampNs < 0L || timestampNs <= latestCounterSampleNs) return false
        if (receivedAtNs != null && receivedAtNs < timestampNs) return false
        if (receivedAtNs != null && latestKnownStepReceiptNs?.let { receivedAtNs < it } == true) return false
        val generation = registrationGeneration
        val baseline = activeWindowHardwareBaseline ?: total
        val windowSteps = (total.toDouble() - baseline.toDouble()).coerceAtLeast(0.0)
        if (activeWindowStepOffset + windowSteps > Int.MAX_VALUE.toDouble()) return false
        val currentSample = StepCounterSample(total, timestampNs, receivedAtNs)
        val counterObservation = StepCounterObservation(
            generation, latestCounterEvidence?.baseline ?: currentSample,
            latestCounterEvidence?.current, currentSample,
        )
        latestCounterEvidence = counterObservation
        latestCounterSampleNs = timestampNs
        if (receivedAtNs != null) latestKnownStepReceiptNs = receivedAtNs
        health.acceptedSample(TrackedSensorSource.STEP_COUNTER, generation, timestampNs, receivedAtNs)
        if (activeWindowHardwareBaseline == null && stepDetectorRegistered) {
            // Detector steps up to the Counter's first sample are inside its baseline, not its window.
            windowPdrSteps = 0
            counterBaselineNs = timestampNs
        }
        activeWindowHardwareBaseline = baseline
        if (stepDetectorRegistered) {
            latestCounterWindowSteps = windowSteps.toInt()
            reconcileRecountedPreBaselineSteps()
        }
        val previousTotal = totalActiveSteps
        val updatedTotal = activeWindowStepOffset + windowSteps.toInt()
        totalActiveSteps = maxOf(previousTotal, updatedTotal)
        onStepCountChanged(totalActiveSteps)
        if (generation != registrationGeneration) return true
        val deltaSteps = totalActiveSteps - previousTotal
        if (!stepDetectorRegistered && deltaSteps > 0) {
            emitStepEvent(
                deltaSteps = deltaSteps,
                timestampNs = timestampNs,
                source = StepEventSource.STEP_COUNTER,
                confidence = StepEventConfidence.MEDIUM,
                receivedAtNs = receivedAtNs,
                counterObservation = counterObservation,
            )
        } else if (stepDetectorRegistered) {
            handOverCounterSteps(windowSteps.toInt(), timestampNs, receivedAtNs, counterObservation)
        }
        return true
    }

    private fun detectorIncrementFitsIntegerCapacity(timestampNs: Long): Boolean {
        if (timestampNs > counterCoveredUntilNs && timestampNs > counterBaselineNs && windowPdrSteps == Int.MAX_VALUE) {
            return false
        }
        if (!stepCounterRegistered) return totalActiveSteps < Int.MAX_VALUE
        if (activeWindowHardwareBaseline == null || timestampNs <= counterBaselineNs) {
            return totalActiveSteps < Int.MAX_VALUE && activeWindowStepOffset < Int.MAX_VALUE &&
                preBaselineDetectorSteps < Int.MAX_VALUE
        }
        if (detectorStepsAfterBaseline == Int.MAX_VALUE) return false
        if (latestCounterSampleNs == Long.MIN_VALUE) return true
        val recounted = if (latestCounterSampleNs - counterBaselineNs <= COUNTER_RECOUNT_WINDOW_NS) {
            (latestCounterWindowSteps - (detectorStepsAfterBaseline + 1)).coerceIn(0, preBaselineDetectorSteps)
        } else {
            recountedPreBaselineSteps
        }
        val offset = activeWindowStepOffset.toLong() + recountedPreBaselineSteps - recounted
        return offset in 0..Int.MAX_VALUE.toLong() && offset + latestCounterWindowSteps <= Int.MAX_VALUE.toLong()
    }

    /**
     * Detector branch of [handleSensorChanged]; internal for the same JVM-test reason as
     * [onStepCounterTotal]. A step at or before the newest counter batch that covered a detector
     * stall is already counted and is not emitted again.
     */
    internal fun onStepDetected(timestampNs: Long, receivedAtNs: Long? = null, detectorValue: Float = 1f): Boolean {
        if (!detectorValue.isFinite() || !(detectorValue > 0f)) return false
        if (timestampNs < 0L || lastDetectorEventNs?.let { timestampNs <= it } == true) return false
        if (receivedAtNs != null && receivedAtNs < timestampNs) return false
        if (receivedAtNs != null && latestKnownStepReceiptNs?.let { receivedAtNs < it } == true) return false
        if (!detectorIncrementFitsIntegerCapacity(timestampNs)) return false
        val generation = registrationGeneration
        lastDetectorEventNs = timestampNs
        if (receivedAtNs != null) latestKnownStepReceiptNs = receivedAtNs
        health.acceptedSample(TrackedSensorSource.STEP_DETECTOR, generation, timestampNs, receivedAtNs)
        if (!stepCounterRegistered) {
            totalActiveSteps += 1
            onStepCountChanged(totalActiveSteps)
        } else if (activeWindowHardwareBaseline == null || timestampNs <= counterBaselineNs) {
            // S09-20: a Detector step at or before the Counter's first sample is inside the Counter
            // baseline, so the Counter window never counts it; it is a session step exactly once here.
            activeWindowStepOffset += 1
            totalActiveSteps += 1
            preBaselineDetectorSteps += 1
            onStepCountChanged(totalActiveSteps)
        } else {
            detectorStepsAfterBaseline += 1
            if (latestCounterSampleNs != Long.MIN_VALUE) {
                // A Counter increment that arrived before this Detector event was not a recount after all.
                reconcileRecountedPreBaselineSteps()
                val updatedTotal = activeWindowStepOffset + latestCounterWindowSteps
                if (updatedTotal > totalActiveSteps) {
                    totalActiveSteps = updatedTotal
                    onStepCountChanged(totalActiveSteps)
                }
            }
        }
        if (generation != registrationGeneration) return true
        if (timestampNs <= counterCoveredUntilNs) return true
        if (timestampNs > counterBaselineNs) windowPdrSteps += 1
        emitStepEvent(
            deltaSteps = 1,
            timestampNs = timestampNs,
            source = StepEventSource.STEP_DETECTOR,
            confidence = StepEventConfidence.HIGH,
            receivedAtNs = receivedAtNs,
        )
        return true
    }

    /**
     * pos-F10: within the Counter delivery latency after the baseline, Counter window steps beyond the Detector
     * steps measured after the baseline are the pre-baseline Detector steps counted a second time (up to their
     * number); they are taken back out of the session offset. Recomputed from the latest totals each time, so a
     * Detector event delivered after its Counter increment restores the count. The shown count stays monotonic.
     */
    private fun reconcileRecountedPreBaselineSteps() {
        if (latestCounterSampleNs - counterBaselineNs > COUNTER_RECOUNT_WINDOW_NS) return
        val recounted = (latestCounterWindowSteps - detectorStepsAfterBaseline).coerceIn(0, preBaselineDetectorSteps)
        activeWindowStepOffset += recountedPreBaselineSteps - recounted
        recountedPreBaselineSteps = recounted
    }

    /**
     * With a registered Detector the Counter stays silent while the Detector is alive. When the
     * Counter's newest step is more than [DETECTOR_STALL_NS] after the Detector's newest step (or
     * after the window's first Counter sample if the Detector never reported), the Detector is
     * treated as stalled and only the Counter steps not yet emitted are sent, as one batch at the
     * Counter timestamp. While alive, Counter-only surplus is absorbed so it is never dumped later.
     */
    private fun handOverCounterSteps(counterWindowSteps: Int, timestampNs: Long, receivedAtNs: Long?,
        counterObservation: StepCounterObservation) {
        // Late baseline recounts belong to Detector steps already emitted before this window.
        // Count reconciliation and PDR handover must use the same post-baseline step basis.
        val counterPdrWindowSteps = (counterWindowSteps - recountedPreBaselineSteps).coerceAtLeast(0)
        val silentSince = lastDetectorEventNs ?: detectorSilentSinceNs
        if (detectorSilentSinceNs == null) detectorSilentSinceNs = timestampNs
        val stalled = silentSince != null && timestampNs - silentSince > DETECTOR_STALL_NS
        if (!stalled) {
            windowPdrSteps = maxOf(windowPdrSteps, counterPdrWindowSteps)
            return
        }
        val uncovered = counterPdrWindowSteps - windowPdrSteps
        if (uncovered <= 0) return
        windowPdrSteps = counterPdrWindowSteps
        counterCoveredUntilNs = maxOf(counterCoveredUntilNs, timestampNs)
        emitStepEvent(
            deltaSteps = uncovered,
            timestampNs = timestampNs,
            source = StepEventSource.STEP_COUNTER,
            confidence = StepEventConfidence.MEDIUM,
            receivedAtNs = receivedAtNs,
            counterObservation = counterObservation,
        )
    }

    private fun emitStepEvent(
        deltaSteps: Int,
        timestampNs: Long,
        source: StepEventSource,
        confidence: StepEventConfidence,
        receivedAtNs: Long?,
        counterObservation: StepCounterObservation? = null,
    ) {
        if (deltaSteps <= 0 || timestampNs < 0L) return
        onStepEvent(
            StepEvent(
                deltaSteps = deltaSteps,
                timestampMs = timestampNs / NANOS_PER_MILLISECOND,
                source = source,
                confidence = confidence,
                measurementElapsedRealtimeNanos = timestampNs,
                receivedAtElapsedRealtimeNanos = receivedAtNs,
                producerRegistrationGeneration = registrationGeneration,
                counterObservation = counterObservation,
            ),
        )
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L

        // Longer than the documented Step Detector (<2 s) and Step Counter (<=10 s) delivery
        // latency, so a live Detector is not replaced because one of them is slow.
        const val DETECTOR_STALL_NS = 10_000_000_000L

        // The documented Step Counter delivery latency (<=10 s): a recount of pre-baseline steps shows up within it.
        const val COUNTER_RECOUNT_WINDOW_NS = 10_000_000_000L
    }
}

internal interface StepSensorRegistration {
    fun hasSensor(type: Int): Boolean
    fun register(listener: SensorEventListener, type: Int): Boolean
    fun unregister(listener: SensorEventListener?)
}

private class AndroidStepSensorRegistration(context: Context) : StepSensorRegistration {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun hasSensor(type: Int): Boolean = sensorFor(type) != null

    override fun register(listener: SensorEventListener, type: Int): Boolean =
        sensorFor(type)?.let {
            // S09-17: peak/trough detection needs the motion-sensor rate the app already uses (GAME).
            val delay = if (type == Sensor.TYPE_ACCELEROMETER) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_NORMAL
            sensorManager.registerListener(listener, it, delay)
        } == true

    override fun unregister(listener: SensorEventListener?) {
        listener?.let(sensorManager::unregisterListener)
    }

    private fun sensorFor(type: Int): Sensor? = when (type) {
        Sensor.TYPE_STEP_COUNTER -> stepCounter
        Sensor.TYPE_STEP_DETECTOR -> stepDetector
        Sensor.TYPE_ACCELEROMETER -> accelerometer
        else -> null
    }
}
