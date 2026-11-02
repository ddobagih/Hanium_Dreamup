package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.DetectorStallWatchdog
import kr.co.hanium.dreamup.walksafe.inference.DetectorCohortRetired
import kr.co.hanium.dreamup.walksafe.inference.DetectorNativeCleanupFailure
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTransferredDetector
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionTransferResult
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRetirementSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseProof
import java.util.concurrent.CancellationException
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
    private var unprovenReleaseFailure: Throwable? = null
    private val releaseProofs = mutableListOf<CompletionStage<Unit>>()
    private val detectorReleaseProofs = java.util.IdentityHashMap<AndroidFrameDetector, CompletionStage<Unit>>()
    private val shutdownRelease = CompletableFuture<Unit>()
    private val shutdownHostDrain = CompletableFuture<Unit>()
    private var transferDrain: CompletableFuture<PrimaryTransferredDetector>? = null
    private var transferDraining = false
    private var transferredIdle = false
    private var transferredResumeAuthorized = false
    private class RuntimeQuiesce(val captured: VisionRetirementSnapshot) {
        val hostDrain = CompletableFuture<Unit>()
        val cleanupReleased = CompletableFuture<Unit>()
    }
    private var runtimeQuiesce: RuntimeQuiesce? = null
    private var runtimeRecheckAuthorized = false
    @Volatile private var available = false
    private val workInFlight = AtomicBoolean(false)
    private val pacing = AdaptiveInferencePacingPolicy(sourceAgeMs)
    @Volatile private var watchdog = DetectorStallWatchdog(sourceAgeMs, elapsedRealtimeMs)
    private val flightWatchdogs = java.util.WeakHashMap<DetectorStallWatchdog.Flight, DetectorStallWatchdog>()

    /** General facts preserve the original separate volatile/plain reads and acquire no new frame lock. */
    fun projection(): PrimaryDetectorObservation =
        PrimaryDetectorObservation(detector, frameGeneration, loadGeneration, closing, available, workInFlight.get())

    /** Existing exact running-detector observations keep their original frame-state transaction. */
    fun observe(): PrimaryDetectorObservation = synchronized(stateLock) { projection() }

    val nativeLane: Executor get() = detectorExecutor
    val tuningLane: Executor get() = tuningExecutor

    /** No timer/unbind/rejected callback is a release receipt; this future follows both lanes. */
    fun closedFuture(): CompletableFuture<Unit> = shutdownRelease
    /** Actual Activity teardown only; an idle transfer leaves these queues available for return. */
    fun hostDrainedFuture(): CompletableFuture<Unit> = shutdownHostDrain

    /** Host-only acknowledgement. Native ownership intentionally remains in [closedFuture]. */
    fun detachAfterCalibrationDrains(detach: (AndroidFrameDetector) -> PrimaryTransferredDetector):
        CompletableFuture<PrimaryTransferredDetector> {
        val drained = synchronized(stateLock) {
            transferDrain?.let { if (transferDraining || transferredIdle) return it }
            check(!shutdownStarted && !closing && available && detector !is NoopAndroidFrameDetector &&
                detectorReleaseProofs[detector] != null) {
                "primary transfer requires an available, unretired owner"
            }
            transferDraining = true
            transferredResumeAuthorized = false
            frameGeneration += 1
            fenceForClose()
            available = false
            pacing.reset()
            CompletableFuture<PrimaryTransferredDetector>().also { transferDrain = it }
        }
        val detachTask = Runnable {
            val owned = synchronized(stateLock) { detector }
            val ownedProof = synchronized(stateLock) { detectorReleaseProofs[owned] }
            val stillCurrent = synchronized(stateLock) { !shutdownStarted && transferDraining && transferDrain === drained }
            if (!stillCurrent) {
                drained.completeExceptionally(CancellationException("primary transfer was retired by host teardown"))
                return@Runnable
            }
            try {
                val token = detach(owned)
                synchronized(stateLock) {
                    if (detector === owned) detector = NoopAndroidFrameDetector()
                    if (detectorReleaseProofs[owned] === ownedProof) detectorReleaseProofs.remove(owned)
                    transferDraining = false
                    transferredIdle = !shutdownStarted
                }
                // Physical receipts stay in releaseProofs. Dropping an already drained remote
                // facade does not assert that its native model was freed or close another owner.
                drained.complete(token)
            } catch (error: Throwable) {
                recordDetectorReleaseFailure(owned, error)
                synchronized(stateLock) { transferDraining = false }
                drained.completeExceptionally(error)
            }
        }
        try {
            tuningExecutor.execute {
                try { detectorExecutor.execute(detachTask) }
                catch (error: RejectedExecutionException) {
                    recordReleaseFailure(error)
                    synchronized(stateLock) { transferDraining = false }
                    drained.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            recordReleaseFailure(error)
            synchronized(stateLock) { transferDraining = false }
            drained.completeExceptionally(error)
        }
        return drained
    }

    /** The guard must validate the current global-registry claim and exact client target lease. */
    fun resumeTransferredHost(receipt: VisionTransferResult, currentGuard: () -> Boolean): Boolean {
        if (!currentGuard()) return false
        val pending = synchronized(stateLock) {
            if (shutdownStarted || !transferredIdle || !closing || detector !is NoopAndroidFrameDetector ||
                releaseFailure != null || unprovenReleaseFailure != null) return false
            val drained = transferDrain ?: return false
            val previous = drained.getNow(null) ?: return false
            val primary = receipt.handles.filter { it.role == VisionRole.PRIMARY }
            if (receipt.lease.cohortId != previous.oldLease.cohortId || receipt.lease.ownerId != previous.oldLease.ownerId ||
                receipt.lease.ownerEpoch <= previous.oldLease.ownerEpoch || primary.size != previous.oldHandles.size ||
                primary.map { it.modelInstanceId }.toSet() != previous.oldHandles.map { it.modelInstanceId }.toSet() ||
                primary.any { it.cohortId != receipt.lease.cohortId || it.ownerEpoch != receipt.lease.ownerEpoch }) return false
            drained
        }
        if (!currentGuard()) return false
        return synchronized(stateLock) {
            if (shutdownStarted || !transferredIdle || transferDrain !== pending || detector !is NoopAndroidFrameDetector) false
            else { transferredResumeAuthorized = true; true }
        }
    }

    /** Consume only inside the lifecycle's explicit prepared-reservation transaction. */
    fun consumeTransferredResumeAuthorization(): Boolean = synchronized(stateLock) {
        if (shutdownStarted || !transferredIdle || !transferredResumeAuthorized || detector !is NoopAndroidFrameDetector) return@synchronized false
        transferredResumeAuthorized = false
        transferredIdle = false
        closing = false
        transferDrain = null
        frameGeneration += 1
        loadGeneration += 1L
        pacing.reset()
        true
    }

    /** Runtime failure stops host admission, but leaves the captured lanes available for an explicit recheck. */
    fun quiesceForRuntimeError(captured: VisionRetirementSnapshot): CompletionStage<Unit> {
        val quiesce = synchronized(stateLock) {
            runtimeQuiesce?.let {
                check(it.captured.lease === captured.lease && it.captured.actualReleased === captured.actualReleased) {
                    "Runtime quiescence belongs to a different captured cohort"
                }
                return it.hostDrain
            }
            check(!shutdownStarted) { "A destroyed host cannot be quiesced for reuse" }
            transferDraining = false
            transferredIdle = false
            transferredResumeAuthorized = false
            runtimeRecheckAuthorized = false
            frameGeneration += 1
            fenceForClose()
            available = false
            pacing.reset()
            RuntimeQuiesce(captured).also { runtimeQuiesce = it }
        }
        val task = Runnable {
            val owned = synchronized(stateLock) { detector }
            try { (owned as? Closeable)?.close() }
            catch (error: Throwable) {
                val known = synchronized(stateLock) { detectorReleaseProofs[owned] } ?: physicalRelease(error)
                recordReleaseFailure(error, known)
                if (known == null) synchronized(stateLock) { if (reloadReleasePending == null) reloadReleasePending = owned }
            }
            synchronized(stateLock) { if (detector === owned) detector = NoopAndroidFrameDetector(); available = false }
            forgetReleasedDetector(owned)
            // Capture local ownership after the serial barrier. Physical callbacks retain only
            // receipts, never this owner (whose presentation callbacks can retain an Activity).
            val proofs = synchronized(stateLock) {
                if (unprovenReleaseFailure == null && detector is NoopAndroidFrameDetector)
                    releaseProofs.toList() else null
            }
            if (proofs == null) quiesce.cleanupReleased.completeExceptionally(
                IllegalStateException("Local primary release remains unproven"))
            else CompletableFuture.allOf(quiesce.hostDrain,
                *proofs.map { it.toCompletableFuture() }.toTypedArray(),
                captured.actualReleased.toCompletableFuture()).whenComplete { _, error ->
                if (error != null) quiesce.cleanupReleased.completeExceptionally(error)
                else {
                    val receipt = captured.actualReleased.toCompletableFuture().getNow(null)
                    if (receipt != null && receipt.cohortId == captured.lease.cohortId &&
                        (receipt.releaseProof == VisionReleaseProof.CHILD_DEATH && receipt.childPid > 0 ||
                            receipt.releaseProof == VisionReleaseProof.NO_NATIVE_START && receipt.childPid == 0))
                        quiesce.cleanupReleased.complete(Unit)
                    else quiesce.cleanupReleased.completeExceptionally(IllegalStateException("Wrong primary release receipt"))
                }
            }
            quiesce.hostDrain.complete(Unit)
        }
        try {
            tuningExecutor.execute {
                try { detectorExecutor.execute(task) }
                catch (error: RejectedExecutionException) { recordReleaseFailure(error); quiesce.hostDrain.completeExceptionally(error) }
            }
        } catch (error: RejectedExecutionException) { recordReleaseFailure(error); quiesce.hostDrain.completeExceptionally(error) }
        return quiesce.hostDrain
    }

    /** Cleanup proof only: observing it cannot authorize a model reservation or resume. */
    fun quiescedReleaseConfirmed(captured: VisionRetirementSnapshot): CompletionStage<Unit> = synchronized(stateLock) {
        val quiet = runtimeQuiesce
        if (quiet != null && quiet.captured.lease === captured.lease &&
            quiet.captured.actualReleased === captured.actualReleased) quiet.cleanupReleased
        else CompletableFuture<Unit>().also { it.completeExceptionally(
            IllegalStateException("No matching primary quiescence")) }
    }

    /** Guard: a fresh AuthorityAttempt, explicit user recheck, and all auxiliary host drains. */
    fun resumeQuiescedForRecheck(currentGuard: () -> Boolean): Boolean {
        if (!currentGuard()) return false
        val captured = synchronized(stateLock) {
            val quiet = runtimeQuiesce ?: return false
            if (shutdownStarted || !closing || detector !is NoopAndroidFrameDetector || unprovenReleaseFailure != null ||
                !positive(quiet.hostDrain) || !releaseProofs.all(::positive)) return false
            val actual = quiet.captured.actualReleased.toCompletableFuture()
            if (!positive(actual)) return false
            val receipt = actual.getNow(null) ?: return false
            if (receipt.cohortId != quiet.captured.lease.cohortId ||
                !(receipt.releaseProof == VisionReleaseProof.CHILD_DEATH && receipt.childPid > 0 ||
                    receipt.releaseProof == VisionReleaseProof.NO_NATIVE_START && receipt.childPid == 0)) return false
            quiet
        }
        if (!currentGuard()) return false
        return synchronized(stateLock) {
            if (shutdownStarted || runtimeQuiesce !== captured || unprovenReleaseFailure != null) false
            else { runtimeRecheckAuthorized = true; true }
        }
    }

    /** Recovery is consumed only by an explicit pair reservation, never ordinary profile restore. */
    fun consumeQuiescedRecheckAuthorization(): Boolean = synchronized(stateLock) {
        val quiet = runtimeQuiesce ?: return@synchronized false
        if (shutdownStarted || !runtimeRecheckAuthorized || !closing || detector !is NoopAndroidFrameDetector ||
            unprovenReleaseFailure != null || !positive(quiet.hostDrain) || !releaseProofs.all(::positive)) return@synchronized false
        runtimeRecheckAuthorized = false
        runtimeQuiesce = null
        releaseFailure = null
        reloadReleasePending = null
        detectorReleaseProofs.entries.removeAll { positive(it.value) }
        releaseProofs.removeAll(::positive)
        watchdog = DetectorStallWatchdog(sourceAgeMs, elapsedRealtimeMs)
        closing = false
        frameGeneration += 1
        loadGeneration += 1L
        pacing.reset()
        true
    }

    private fun positive(proof: CompletionStage<*>): Boolean = proof.toCompletableFuture().let {
        it.isDone && !it.isCompletedExceptionally && !it.isCancelled
    }

    private fun physicalRelease(error: Throwable): CompletionStage<Unit>? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            (cause as? DetectorCohortRetired)?.actualReleasedFuture?.let { return it }
            (cause as? DetectorNativeCleanupFailure)?.actualReleasedFuture?.let { return it }
            cause = cause.cause
        }
        return null
    }

    /** Retained before adoption: even a failed OPEN without a detector can still own child resources. */
    fun retainPhysicalRelease(proof: CompletionStage<Unit>?, instance: AndroidFrameDetector? = null) {
        if (proof == null) return
        synchronized(stateLock) {
            if (releaseProofs.none { it === proof }) releaseProofs += proof
            if (instance != null) detectorReleaseProofs[instance] = proof
        }
        // A producer may close a rejected, never-adopted facade directly, outside this owner.
        instance?.let(::forgetReleasedDetector)
    }

    fun recordReleaseFailure(error: Throwable,
        proof: CompletionStage<Unit>? = physicalRelease(error),
    ) = synchronized(stateLock) {
        if (releaseFailure == null) releaseFailure = error
        else if (releaseFailure !== error) releaseFailure?.addSuppressed(error)
        if (proof == null && unprovenReleaseFailure == null) unprovenReleaseFailure = error
        retainPhysicalRelease(proof)
        closing = true
        available = false
    }

    fun recordDetectorReleaseFailure(instance: AndroidFrameDetector, error: Throwable) =
        recordReleaseFailure(error, synchronized(stateLock) { detectorReleaseProofs[instance] }
            ?: physicalRelease(error))

    /** After a host close/detach attempt, keep unknown resources but forget confirmed old facades. */
    fun forgetReleasedDetector(instance: AndroidFrameDetector) {
        val proof = synchronized(stateLock) { detectorReleaseProofs[instance] } ?: return
        val weakOwner = WeakReference(this)
        val weakDetector = WeakReference(instance)
        proof.whenComplete { _, error ->
            if (error == null) weakOwner.get()?.let { owner -> weakDetector.get()?.let { released ->
                synchronized(owner.stateLock) {
                    if (owner.detector !== released && owner.detectorReleaseProofs[released] === proof)
                        owner.detectorReleaseProofs.remove(released)
                }
            } }
        }
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
        try {
            close(release.first)
            synchronized(stateLock) { detectorReleaseProofs[release.first] }?.toCompletableFuture()?.let { proof ->
                check(proof.isDone && !proof.isCompletedExceptionally && !proof.isCancelled) {
                    "Previous detector physical release remains unconfirmed"
                }
            }
        } catch (error: Throwable) { recordDetectorReleaseFailure(release.first, error); throw error }
        finally { forgetReleasedDetector(release.first) }
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
        val owned = synchronized(stateLock) { if (closing || shutdownStarted) return null; watchdog }
        val ticket = owned.begin(epoch) ?: run {
            rejectBlockedWork()
            return null
        }
        synchronized(stateLock) { flightWatchdogs[ticket] = owned }
        val stopClaim = AtomicBoolean(false)
        val timeout = Runnable {
            if (owned === watchdog && owned.expire(ticket) && stopClaim.compareAndSet(false, true)) onNativeStall(epoch, generation)
        }
        val delay = (ticket.deadlineElapsedRealtimeMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        if (!nativeScheduler.postDelayed(timeout, delay)) {
            owned.complete(ticket)
            synchronized(stateLock) { flightWatchdogs.remove(ticket) }
            return null
        }
        return NativeDetectorFlight(ticket, timeout, epoch, generation, stopClaim)
    }

    fun completeNativeFlight(flight: NativeDetectorFlight) {
        nativeScheduler.remove(flight.timeout)
        val owned = synchronized(stateLock) { flightWatchdogs[flight.ticket] } ?: return
        val completed = owned.complete(flight.ticket)
        if (owned === watchdog && !completed) requestNativeStall(flight)
    }

    fun requestNativeStall(flight: NativeDetectorFlight) {
        val owned = synchronized(stateLock) { flightWatchdogs[flight.ticket] } ?: return
        if (owned === watchdog && owned.blocksNewWork() && flight.stopClaim.compareAndSet(false, true)) {
            dispatchOnMain { if (owned === watchdog) onNativeStall(flight.walkEpoch, flight.detectorGeneration) }
        }
    }

    fun publishNativeIfCurrent(flight: NativeDetectorFlight, publish: () -> Unit): Boolean = synchronized(stateLock) {
        val owned = flightWatchdogs[flight.ticket] ?: return@synchronized false
        owned === watchdog && owned.publishIfCurrent(flight.ticket, publish)
    }

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
        afterUnavailable: () -> Unit): Int? {
        var metadataFailure: Throwable? = null
        val retired = synchronized(stateLock) {
            if (!isCurrent() || closing || shutdownStarted) return null
            val owned = detector
            detector = NoopAndroidFrameDetector()
            available = false
            closing = true
            frameGeneration += 1
            loadGeneration += 1L
            reloadReleasePending = owned
            pacing.reset()
            try { resetVisualState(); afterUnavailable() } catch (error: Throwable) { metadataFailure = error }
            owned to frameGeneration
        }
        // This method is already called on the native lane. Never wait for remote CLOSE
        // while holding the frame/UI metadata lock, and never publish a second generation.
        var closeFailure: Throwable? = null
        try { (retired.first as? Closeable)?.close() }
        catch (error: Throwable) { closeFailure = error; recordDetectorReleaseFailure(retired.first, error) }
        finally {
            val proof = synchronized(stateLock) { detectorReleaseProofs[retired.first] }
                ?: closeFailure?.let(::physicalRelease)
            synchronized(stateLock) {
                if (reloadReleasePending === retired.first && (closeFailure == null || proof != null)) reloadReleasePending = null
            }
            forgetReleasedDetector(retired.first)
        }
        closeFailure?.let { failure -> metadataFailure?.takeIf { it !== failure }?.let(failure::addSuppressed); throw failure }
        metadataFailure?.let { throw it }
        return retired.second
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
            } catch (error: Throwable) {
                recordDetectorReleaseFailure(owned, error)
            }
            finishPhysicalReleaseAfterBarrier(owned)
            shutdownHostDrain.complete(Unit)
        }
        try {
            tuningExecutor.execute {
                try { detectorExecutor.execute(detachAndClose) }
                catch (error: RejectedExecutionException) {
                    recordReleaseFailure(error)
                    shutdownRelease.completeExceptionally(error)
                    shutdownHostDrain.completeExceptionally(error)
                } finally { detectorExecutor.shutdown() }
            }
        } catch (error: RejectedExecutionException) {
            recordReleaseFailure(error)
            shutdownRelease.completeExceptionally(error)
            shutdownHostDrain.completeExceptionally(error)
        } finally { tuningExecutor.shutdown() }
    }

    /** Called only by a task which really ran after both host lane barriers. */
    private fun finishPhysicalReleaseAfterBarrier(owned: AndroidFrameDetector) {
        forgetReleasedDetector(owned)
        val (failure, proofs) = synchronized(stateLock) {
            available = false
            if (reloadReleasePending != null && releaseFailure == null)
                recordReleaseFailure(IllegalStateException("Previous detector release remains unconfirmed"))
            unprovenReleaseFailure to releaseProofs.toList()
        }
        if (failure != null) shutdownRelease.completeExceptionally(failure)
        else {
            val ownedProof = synchronized(stateLock) { detectorReleaseProofs[owned] }
            val joined = proofs.map { proof ->
                CompletableFuture<Unit>().also { joinedProof -> proof.whenComplete { _, error ->
                    if (error == null) joinedProof.complete(Unit) else joinedProof.completeExceptionally(error)
                } }
            }
            val shutdownFuture = shutdownRelease
            val weakOwner = WeakReference(this@PrimaryDetectorExecutionOwner)
            val weakOwned = WeakReference(owned)
            CompletableFuture.allOf(*joined.toTypedArray()).whenComplete { _, error ->
                if (error != null) shutdownFuture.completeExceptionally(error)
                else {
                    weakOwner.get()?.let { owner -> synchronized(owner.stateLock) {
                        val released = weakOwned.get()
                        if (owner.detector === released) owner.detector = NoopAndroidFrameDetector()
                        if (released != null && owner.detectorReleaseProofs[released] === ownedProof)
                            owner.detectorReleaseProofs.remove(released)
                        owner.reloadReleasePending = null
                    } }
                    shutdownFuture.complete(Unit)
                }
            }
        }
    }
}
