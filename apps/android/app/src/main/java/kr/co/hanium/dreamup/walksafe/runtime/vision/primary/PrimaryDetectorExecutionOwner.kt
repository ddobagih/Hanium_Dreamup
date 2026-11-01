package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.DetectorStallWatchdog
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal interface PrimaryNativeFlightScheduler {
    fun postDelayed(task: Runnable, delayMs: Long): Boolean
    fun remove(task: Runnable)
}

internal data class PrimaryDetectorObservation(
    val detector: AndroidFrameDetector,
    val frameGeneration: Int,
    val loadGeneration: Long,
    val closing: Boolean,
    val available: Boolean,
    val workInFlight: Boolean,
)

internal data class PrimaryDetectorLoadClaim(val generation: Long)
internal data class PrimaryLoadedDetector(val detector: AndroidFrameDetector?, val detectorAvailable: Boolean)

internal data class PrimaryCompletionObservation(
    val thermalThrottled: Boolean?,
    val loadObservation: InferenceLoadObservation?,
)

/** Actual single detector pointer, physical executor, native flight and pacing owner. */
internal class PrimaryDetectorExecutionOwner(
    private val stateLock: Any,
    private val detectorExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        // Foreground safety inference has a fixed freshness deadline; reduce runnable starvation
        // without changing its deadline or using a display/realtime priority.
        Thread(runnable, "walksafe-tflite-detector").apply { priority = Thread.NORM_PRIORITY + 1 }
    },
    private val tuningExecutor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "walksafe-device-tuning") },
    private val elapsedRealtimeMs: () -> Long,
    private val nativeScheduler: PrimaryNativeFlightScheduler,
    private val rejectBlockedWork: () -> Boolean,
    private val auxiliaryCopyInFlight: () -> Boolean,
    private val onDueButInFlightDrop: () -> Unit,
    private val onNativeStall: (WalkRuntimeEpoch, Int) -> Unit,
    private val dispatchOnMain: (() -> Unit) -> Unit,
    private val sourceAgeMs: Long = 800L,
) {
    @Volatile private var detector: AndroidFrameDetector = NoopAndroidFrameDetector()
    @Volatile private var frameGeneration = 0
    @Volatile private var loadGeneration = 0L
    private var closing = false
    private var reloadReleasePending: AndroidFrameDetector? = null
    private var shutdownStarted = false
    private var releaseFailure: Throwable? = null
    private val shutdownRelease = CompletableFuture<Unit>()
    @Volatile private var available = false
    private val workInFlight = AtomicBoolean(false)
    private val pacing = AdaptiveInferencePacingPolicy(sourceAgeMs)
    private val watchdog = DetectorStallWatchdog(sourceAgeMs, elapsedRealtimeMs)

    /** General facts preserve the original separate volatile/plain reads and acquire no new frame lock. */
    fun projection(): PrimaryDetectorObservation =
        PrimaryDetectorObservation(detector, frameGeneration, loadGeneration, closing, available, workInFlight.get())

    /** Existing exact running-detector observations keep their original frame-state transaction. */
    fun observe(): PrimaryDetectorObservation = synchronized(stateLock) { projection() }

    val nativeLane: Executor get() = detectorExecutor
    val tuningLane: Executor get() = tuningExecutor

    /** No timer/unbind/rejected callback is a release receipt; this future follows both lanes. */
    fun closedFuture(): CompletableFuture<Unit> = shutdownRelease

    fun recordReleaseFailure(error: Throwable) = synchronized(stateLock) {
        if (releaseFailure == null) releaseFailure = error
        else if (releaseFailure !== error) releaseFailure?.addSuppressed(error)
        closing = true
        available = false
    }

    fun beginLoad(): PrimaryDetectorLoadClaim = synchronized(stateLock) { PrimaryDetectorLoadClaim(loadGeneration) }

    /** Native-lane barrier: drain old invocations and release their owner before allocating another runtime. */
    fun retireLoadedForReloadOnNativeLane(claim: PrimaryDetectorLoadClaim, current: () -> Boolean,
        close: (AndroidFrameDetector) -> Unit): PrimaryDetectorLoadClaim? {
        val release = synchronized(stateLock) {
            if (!current() || closing || loadGeneration != claim.generation) return null
            val previous = detector
            detector = NoopAndroidFrameDetector()
            frameGeneration += 1
            loadGeneration += 1L
            available = false
            // Temporary fence also blocks a cancelled reload from allocating before release ACK.
            closing = true
            reloadReleasePending = previous
            pacing.reset()
            previous to PrimaryDetectorLoadClaim(loadGeneration)
        }
        // Native destruction can be slow; never hold the UI/frame lock across it. A failed
        // release retains its owner and fence, without authorizing an automatic retry.
        try { close(release.first) }
        catch (error: Throwable) { recordReleaseFailure(error); throw error }
        return synchronized(stateLock) {
            reloadReleasePending = null
            if (loadGeneration != release.second.generation) return@synchronized null
            closing = false
            release.second
        }
    }

    /** Called in the existing detector lane before the original UI admission callback. */
    fun adoptLoadedOnNativeLane(claim: PrimaryDetectorLoadClaim, result: PrimaryLoadedDetector,
        destroyed: () -> Boolean): Boolean = synchronized(stateLock) {
        if (destroyed() || closing || loadGeneration != claim.generation) return@synchronized false
        result.detector?.let { detector = it }
        true
    }

    /** Availability is admitted at the original UI stage, never optimistically at native adoption. */
    fun admitLoadedOnMain(claim: PrimaryDetectorLoadClaim, result: PrimaryLoadedDetector,
        beforeAvailability: () -> Unit = {}, destroyed: () -> Boolean): Boolean {
        if (destroyed() || closing || loadGeneration != claim.generation) return false
        beforeAvailability()
        available = result.detectorAvailable
        return true
    }

    /** SDK pointer is read at actual invocation time; a queued frame does not invent a model fence. */
    fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
        partial: (AndroidDetectionResult) -> Unit = {}): AndroidDetectionResult =
        detector.detectOriented(cameraFrame, timestampMs, quarterTurns, partial)

    fun tryStartWork(nowMs: Long): InferencePacingTicket? = synchronized(stateLock) {
        if (rejectBlockedWork()) return@synchronized null
        if (auxiliaryCopyInFlight()) return@synchronized null
        if (workInFlight.get()) {
            if (nowMs >= pacing.snapshot().nextEligibleAtElapsedRealtimeMs) onDueButInFlightDrop()
            return@synchronized null
        }
        if (!pacing.isDue(nowMs)) return@synchronized null
        if (!workInFlight.compareAndSet(false, true)) {
            onDueButInFlightDrop()
            return@synchronized null
        }
        pacing.tryStart(nowMs) ?: run {
            workInFlight.set(false)
            null
        }
    }

    /** The observation callback runs at the original completion stage under the shared state lock. */
    fun finishWork(ticket: InferencePacingTicket, detectorInvoked: Boolean,
        inferenceDurationMs: Long? = null, validCompletion: Boolean = false,
        observation: (Long) -> PrimaryCompletionObservation,
        onValidCompletion: (endToEndMs: Long, staleOrDropped: Boolean) -> Unit,
    ): Unit = synchronized(stateLock) {
        try {
            if (detectorInvoked) {
                val completedAt = elapsedRealtimeMs()
                val facts = observation(completedAt)
                val accepted = pacing.complete(ticket, completedAt, inferenceDurationMs,
                    facts.thermalThrottled, facts.loadObservation)
                if (accepted && validCompletion) {
                    val endToEnd = completedAt - ticket.startedAtElapsedRealtimeMs
                    onValidCompletion(endToEnd, endToEnd > sourceAgeMs)
                }
            } else pacing.cancel(ticket)
        } finally {
            workInFlight.set(false)
        }
    }

    fun beginNativeFlight(epoch: WalkRuntimeEpoch, generation: Int): NativeDetectorFlight? {
        val ticket = watchdog.begin(epoch) ?: run {
            rejectBlockedWork()
            return null
        }
        val stopClaim = AtomicBoolean(false)
        val timeout = Runnable {
            if (watchdog.expire(ticket) && stopClaim.compareAndSet(false, true)) onNativeStall(epoch, generation)
        }
        val delay = (ticket.deadlineElapsedRealtimeMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        if (!nativeScheduler.postDelayed(timeout, delay)) {
            watchdog.complete(ticket)
            return null
        }
        return NativeDetectorFlight(ticket, timeout, epoch, generation, stopClaim)
    }

    fun completeNativeFlight(flight: NativeDetectorFlight) {
        val completed = watchdog.complete(flight.ticket)
        nativeScheduler.remove(flight.timeout)
        if (!completed) requestNativeStall(flight)
    }

    fun requestNativeStall(flight: NativeDetectorFlight) {
        if (watchdog.blocksNewWork() && flight.stopClaim.compareAndSet(false, true)) {
            dispatchOnMain { onNativeStall(flight.walkEpoch, flight.detectorGeneration) }
        }
    }

    fun publishNativeIfCurrent(flight: NativeDetectorFlight, publish: () -> Unit): Boolean =
        watchdog.publishIfCurrent(flight.ticket, publish)

    fun nativeWorkBlocked(): Boolean = watchdog.blocksNewWork()
    fun rejectBlockedNativeWork(epoch: WalkRuntimeEpoch?, state: WalkSessionState?, stop: (WalkRuntimeEpoch) -> Unit): Boolean =
        watchdog.rejectBlockedWork(epoch, state, stop)
    fun rejectUnsafeWalkEntry(epoch: WalkRuntimeEpoch, state: WalkSessionState, stop: (WalkRuntimeEpoch) -> Unit): Boolean =
        watchdog.rejectUnsafeWalkEntry(epoch, state, stop)

    // Each pacing operation already owns the original policy lock; callers keep their existing outer lock.
    fun resetPacing(): Unit = pacing.reset()
    fun configurePacingMinimum(intervalMs: Long): Unit = pacing.configureMinimumInterval(intervalMs)
    fun requestEarlyWork(nowMs: Long): Boolean = pacing.requestEarlyStart(nowMs)
    fun pacingSnapshot() = pacing.snapshot()
    fun workIsDue(nowMs: Long): Boolean = pacing.isDue(nowMs)
    fun ownsWork(ticket: InferencePacingTicket): Boolean = pacing.ownsSlot(ticket)

    /** The original owner-aware stall reducer still decides whether this frame generation owns a walk. */
    fun fenceStalledIfCurrent(expectedGeneration: Int, commit: () -> Unit = {},
        ownsCurrentWalk: () -> Boolean): Boolean = synchronized(stateLock) {
        if (!ownsCurrentWalk() || frameGeneration != expectedGeneration) return@synchronized false
        frameGeneration += 1
        loadGeneration += 1L
        closing = true
        available = false
        commit()
        true
    }

    /** Callers retain the original transaction boundary, including original volatile lifecycle writes. */
    fun retireFrameGeneration(): Int = ++frameGeneration

    fun fenceForClose() = synchronized(stateLock) { closing = true; loadGeneration += 1L }

    fun disableUnverified(expectedDetector: AndroidFrameDetector, commit: () -> Unit): Boolean = synchronized(stateLock) {
        if (detector !== expectedDetector || closing) return@synchronized false
        detector = NoopAndroidFrameDetector()
        frameGeneration += 1
        loadGeneration += 1L
        available = false
        commit()
        true
    }

    fun disableFailedIfCurrent(isCurrent: () -> Boolean, resetVisualState: () -> Unit,
        afterUnavailable: () -> Unit): Int? = synchronized(stateLock) {
        if (!isCurrent()) return@synchronized null
        try { (detector as? Closeable)?.close() }
        catch (error: Throwable) { recordReleaseFailure(error); throw error }
        detector = NoopAndroidFrameDetector()
        frameGeneration += 1
        resetVisualState()
        pacing.reset()
        available = false
        afterUnavailable()
        frameGeneration
    }

    /** Used only on the native lane after a stored measured profile still owns its old baseline. */
    fun restoreMeasuredIfCurrent(replacement: AndroidFrameDetector, current: () -> Boolean,
        commit: () -> Unit): Boolean = synchronized(stateLock) {
        if (!current()) return@synchronized false
        detector = replacement
        loadGeneration += 1L
        commit()
        true
    }

    /** Preserve the original baseline/pending-close, metadata, generation and verification order. */
    fun adoptMeasuredIfCurrent(replacement: AndroidFrameDetector?, current: () -> Boolean,
        beforeInstall: () -> Unit, afterInstall: () -> Unit, afterGeneration: () -> Unit): Boolean = synchronized(stateLock) {
        if (!current() || replacement == null) return@synchronized false
        beforeInstall()
        detector = replacement
        afterInstall()
        loadGeneration += 1L
        afterGeneration()
        true
    }

    /** Enqueue-only: finish accepted creation before closing the native admission lane. */
    fun closeAfterCalibrationDrains() {
        synchronized(stateLock) {
            if (shutdownStarted) return
            shutdownStarted = true
            fenceForClose()
        }
        val detachAndClose = Runnable {
            val owned = synchronized(stateLock) { detector }
            try {
                (owned as? Closeable)?.close()
                val failure = synchronized(stateLock) {
                    if (detector === owned) detector = NoopAndroidFrameDetector()
                    available = false
                    if (reloadReleasePending != null && releaseFailure == null)
                        releaseFailure = IllegalStateException("Previous detector release remains unconfirmed")
                    releaseFailure
                }
                if (failure == null) shutdownRelease.complete(Unit)
                else shutdownRelease.completeExceptionally(failure)
            } catch (error: Throwable) {
                recordReleaseFailure(error)
                shutdownRelease.completeExceptionally(error)
            }
        }
        try {
            tuningExecutor.execute {
                try { detectorExecutor.execute(detachAndClose) }
                catch (error: RejectedExecutionException) {
                    recordReleaseFailure(error)
                    shutdownRelease.completeExceptionally(error)
                } finally { detectorExecutor.shutdown() }
            }
        } catch (error: RejectedExecutionException) {
            recordReleaseFailure(error)
            shutdownRelease.completeExceptionally(error)
        } finally { tuningExecutor.shutdown() }
    }
}
