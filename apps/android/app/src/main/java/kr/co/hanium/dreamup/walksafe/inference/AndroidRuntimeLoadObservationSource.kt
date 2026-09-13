package kr.co.hanium.dreamup.walksafe.inference

import android.os.Handler
import android.os.SystemClock

/** Small test boundary for the UI queue; production uses the supplied Handler exclusively. */
internal interface RuntimeLoadUiScheduler {
    fun postDelayed(task: Runnable, delayMs: Long): Boolean
    fun remove(task: Runnable)
}

/**
 * Collects observed delay without retaining camera images or creating a worker thread. At most one
 * UI probe is scheduled; after dispatch it schedules the next probe 500 ms later. UI dispatch delay
 * uses uptime, the Handler clock, so deep sleep is not mistaken for UI queue contention. Observation
 * timestamps use elapsed realtime, matching the inference policy; no camera timestamp is subtracted
 * from either clock.
 *
 * Budgets must come from the active camera/display modes; missing budgets produce no ratio evidence.
 * [recordFrame] accepts durations measured for that frame only, never cached tracking/Depth timings.
 * [latestObservation] consumes each sample at most once, preserving its original measurement time.
 * UI and camera/Depth/tracking samples remain separate because they were measured at different times.
 */
class AndroidRuntimeLoadObservationSource internal constructor(
    private val uiScheduler: RuntimeLoadUiScheduler,
    private val expectedCameraFrameIntervalMs: () -> Long?,
    private val uiDispatchBudgetMs: () -> Long?,
    private val elapsedRealtimeMs: () -> Long,
    private val uptimeMs: () -> Long,
) : AutoCloseable {
    constructor(
        uiHandler: Handler,
        expectedCameraFrameIntervalMs: () -> Long?,
        uiDispatchBudgetMs: () -> Long?,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    ) : this(
        uiScheduler = object : RuntimeLoadUiScheduler {
            override fun postDelayed(task: Runnable, delayMs: Long): Boolean = uiHandler.postDelayed(task, delayMs)
            override fun remove(task: Runnable) = uiHandler.removeCallbacks(task)
        },
        expectedCameraFrameIntervalMs = expectedCameraFrameIntervalMs,
        uiDispatchBudgetMs = uiDispatchBudgetMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        uptimeMs = SystemClock::uptimeMillis,
    )

    private var active = false
    private var closed = false
    private var generation = 0L
    private var environmentRevision = 0L
    private var environmentKey: String? = null
    private var startedAtElapsedRealtimeMs = 0L
    private var latestFrameObservationAtMs: Long? = null
    private var uiProbe: Runnable? = null
    private var previousCameraTimestampNs: Long? = null
    private var previousExpectedCameraIntervalMs: Long? = null
    private var pendingFrame: InferenceLoadObservation? = null
    private var pendingUi: InferenceLoadObservation? = null

    @Synchronized
    fun start() {
        if (active || closed) return
        val nowMs = elapsedRealtimeMs()
        if (nowMs < 0L) return
        startedAtElapsedRealtimeMs = nowMs
        latestFrameObservationAtMs = null
        active = true
        generation += 1L
        clearObservations()
        scheduleUiProbe(delayMs = 0L)
    }

    @Synchronized
    fun pause() {
        active = false
        generation += 1L
        uiProbe?.let(uiScheduler::remove)
        uiProbe = null
        latestFrameObservationAtMs = null
        clearObservations()
    }

    @Synchronized
    override fun close() {
        closed = true
        pause()
    }

    @Synchronized
    fun recordFrame(
        cameraTimestampNs: Long,
        observedAtElapsedRealtimeMs: Long,
        depthProcessingMs: Long?,
        trackingProcessingMs: Long?,
        trackingBudgetMs: Long?,
        environmentKey: String,
    ) {
        if (!active || closed) return
        val lastFrameAtMs = latestFrameObservationAtMs
        if (observedAtElapsedRealtimeMs < startedAtElapsedRealtimeMs ||
            lastFrameAtMs != null && observedAtElapsedRealtimeMs <= lastFrameAtMs
        ) return
        latestFrameObservationAtMs = observedAtElapsedRealtimeMs
        if (environmentKey.isBlank() || cameraTimestampNs <= 0L || observedAtElapsedRealtimeMs < 0L) {
            clearObservations()
            return
        }
        if (this.environmentKey != environmentKey) {
            clearObservations()
            this.environmentKey = environmentKey
        }
        val previousTimestampNs = previousCameraTimestampNs
        if (previousTimestampNs != null && cameraTimestampNs <= previousTimestampNs) return
        val frameBudgetMs = readBudget(expectedCameraFrameIntervalMs)
        val cameraIntervalMs = if (previousTimestampNs != null && frameBudgetMs != null &&
            frameBudgetMs == previousExpectedCameraIntervalMs
        ) ((cameraTimestampNs - previousTimestampNs) / NANOS_PER_MILLISECOND).coerceAtLeast(1L) else null
        previousCameraTimestampNs = cameraTimestampNs
        previousExpectedCameraIntervalMs = frameBudgetMs
        val measuredDepthMs = depthProcessingMs?.takeIf { it >= 0L && frameBudgetMs != null }
        val measuredTrackingBudgetMs = trackingBudgetMs?.takeIf { it > 0L }
        val measuredTrackingMs = trackingProcessingMs?.takeIf { it >= 0L && measuredTrackingBudgetMs != null }
        if (cameraIntervalMs == null && measuredDepthMs == null && measuredTrackingMs == null) return
        pendingFrame = InferenceLoadObservation(
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            cameraFrameIntervalMs = cameraIntervalMs,
            expectedCameraFrameIntervalMs = frameBudgetMs.takeIf { cameraIntervalMs != null },
            depthProcessingMs = measuredDepthMs,
            depthBudgetMs = frameBudgetMs.takeIf { measuredDepthMs != null },
            trackingProcessingMs = measuredTrackingMs,
            trackingBudgetMs = measuredTrackingBudgetMs.takeIf { measuredTrackingMs != null },
        )
    }

    /** Keeps only the latest sample per channel and returns the older pending channel first. */
    @Synchronized
    fun latestObservation(nowElapsedRealtimeMs: Long): InferenceLoadObservation? {
        if (!active || closed) return null
        pendingFrame = pendingFrame?.takeIf { it.isFreshAt(nowElapsedRealtimeMs) }
        pendingUi = pendingUi?.takeIf { it.isFreshAt(nowElapsedRealtimeMs) }
        val frame = pendingFrame
        val ui = pendingUi
        return when {
            frame != null && (ui == null || frame.observedAtElapsedRealtimeMs <= ui.observedAtElapsedRealtimeMs) -> {
                pendingFrame = null
                frame
            }
            else -> {
                pendingUi = null
                ui
            }
        }
    }

    private fun scheduleUiProbe(delayMs: Long) {
        val postedAtUptimeMs = uptimeMs()
        val postedBudgetMs = readBudget(uiDispatchBudgetMs)
        val postedGeneration = generation
        val postedEnvironmentRevision = environmentRevision
        val probe = object : Runnable {
            override fun run() {
                synchronized(this@AndroidRuntimeLoadObservationSource) {
                    if (!active || closed || postedGeneration != generation || uiProbe !== this) return
                    uiProbe = null
                    val nowUptimeMs = uptimeMs()
                    val observedAtMs = elapsedRealtimeMs()
                    if (postedEnvironmentRevision == environmentRevision && environmentKey != null &&
                        postedBudgetMs != null && postedBudgetMs == readBudget(uiDispatchBudgetMs) &&
                        postedAtUptimeMs >= 0L && nowUptimeMs >= postedAtUptimeMs && observedAtMs >= 0L
                    ) {
                        pendingUi = InferenceLoadObservation(
                            observedAtElapsedRealtimeMs = observedAtMs,
                            uiDispatchDelayMs = (nowUptimeMs - postedAtUptimeMs - delayMs).coerceAtLeast(0L),
                            uiDispatchBudgetMs = postedBudgetMs,
                        )
                    }
                    scheduleUiProbe(UI_PROBE_INTERVAL_MS)
                }
            }
        }
        uiProbe = probe
        if (!uiScheduler.postDelayed(probe, delayMs)) uiProbe = null
    }

    private fun clearObservations() {
        environmentRevision += 1L
        environmentKey = null
        previousCameraTimestampNs = null
        previousExpectedCameraIntervalMs = null
        pendingFrame = null
        pendingUi = null
    }

    private fun InferenceLoadObservation.isFreshAt(nowMs: Long): Boolean =
        nowMs >= observedAtElapsedRealtimeMs && nowMs - observedAtElapsedRealtimeMs <= MAXIMUM_OBSERVATION_AGE_MS

    private fun readBudget(provider: () -> Long?): Long? = try {
        provider()?.takeIf { it > 0L }
    } catch (_: RuntimeException) {
        // A camera/display mode can disappear during lifecycle changes; telemetry stays unknown.
        null
    }

    companion object {
        private const val UI_PROBE_INTERVAL_MS = 500L
        private const val MAXIMUM_OBSERVATION_AGE_MS = 500L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
