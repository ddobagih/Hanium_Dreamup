package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisController
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortBusyException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionTransferResult
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRetirementSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseProof
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Load/verification metadata and orchestration; the primary execution owner alone holds the native slot. */
internal class PrimaryDetectorLifecycleController<S : Any>(
    private val frameStateLock: Any,
    private val detector: PrimaryDetectorExecutionOwner,
    private val calibration: PrimaryRuntimeCalibrationOwner<S>,
    private val frame: () -> FrameSynthesisController,
    private val supervisor: DetectorRuntimeSupervisor,
    private val currentness: PrimaryLifecycleCurrentness,
    private val configuration: PrimaryModelConfiguration,
    private val platform: PrimaryLifecyclePlatform,
    private val preparation: PrimaryPreparationInteraction,
    private val presentation: PrimaryLifecyclePresentation,
    private val auxiliary: PrimaryLifecycleAuxiliary,
    private val failureEffects: PrimaryDetectorFailureEffects,
    private val scheduler: PrimaryNativeFlightScheduler,
    private val elapsedRealtimeMs: () -> Long,
    private val verification: RunningDetectorVerificationGate = RunningDetectorVerificationGate(),
) {
    private var configLoaded = false
    private var reportModelKey: String? = null
    @Volatile private var loadedModelKey: String? = null
    private var fallbackUsed = false
    @Volatile private var loadReason: String? = null
    @Volatile private var loadAttempted = false
    @Volatile private var loadInFlight = false
    @Volatile private var loadRequestGeneration = 0L
    private var loadAdoptionCommitted = false
    private var comparisonAttempted = false
    private var comparisonRequested = false
    @Volatile private var statusText = "detector=load_pending_after_camera_gate"
    private var fixedFixtureRunning = false
    private var choiceOverrideRequestId: String? = null
    @Volatile private var compositeLoadDrainPending = false
    private class RetiredLoadFailure(val detector: TfliteAndroidFrameDetector,
        val local: CompletionStage<Unit>?, val model: CompletionStage<Unit>?,
        val captured: VisionRetirementSnapshot?)
    private var failedRetiredLoad: RetiredLoadFailure? = null // guarded by frameStateLock
    private data class AvailabilityWindow(val startedAt: Long, val deadline: Long) {
        fun accepts(now: Long) = now >= startedAt && now < deadline
    }
    private class AvailabilityWait(val request: Long, val detectorGeneration: Long,
        val window: AvailabilityWindow, val retry: Runnable) { var timeout: Runnable? = null }
    private var availabilityWait: AvailabilityWait? = null
    private var transferredHostDrain: CompletionStage<PrimaryTransferredDetector>? = null
    private val preparedReceiverKey = Any()
    private var preparedPairSlot: PrimaryPreparedPairSlot? = null

    fun reservePreparedPair(current: () -> Boolean): PrimaryPreparedPairSlot? = synchronized(frameStateLock) {
        if (loadInFlight || failedRetiredLoad != null || currentness.destroyed() ||
            detector.projection().detector !is NoopAndroidFrameDetector || !current()) return@synchronized null
        if (detector.projection().closing && !detector.consumeTransferredResumeAuthorization() &&
            !detector.consumeQuiescedRecheckAuthorization()) return@synchronized null
        loadRequestGeneration += 1L
        loadInFlight = true
        loadAttempted = true
        loadAdoptionCommitted = false
        configLoaded = false
        val slot = PrimaryPreparedPairSlot(preparedReceiverKey, loadRequestGeneration, detector.beginLoad(), current)
        preparedPairSlot = slot
        transferredHostDrain = null
        // This receipt is present before the caller starts its independent pair-creation worker.
        detector.retainPhysicalRelease(slot.actualReleasedFuture)
        slot
    }

    /** Stage a single explicit return reservation; automatic loading cannot consume this fence. */
    fun resumeTransferredHost(receipt: VisionTransferResult, currentGuard: () -> Boolean): Boolean {
        val previous = synchronized(frameStateLock) { transferredHostDrain }
        if (!detector.resumeTransferredHost(receipt) { !currentness.destroyed() && currentGuard() }) return false
        synchronized(frameStateLock) { if (transferredHostDrain === previous) transferredHostDrain = null }
        return true
    }

    /** The caller supplies the old current runtime's captured cohort; this never means Activity teardown. */
    fun quiesceForRuntimeError(captured: VisionRetirementSnapshot): CompletionStage<Unit> {
        val drained = detector.quiesceForRuntimeError(captured)
        synchronized(frameStateLock) {
            loadRequestGeneration += 1L
            loadInFlight = false
            loadAttempted = true
            configLoaded = false
            comparisonRequested = false
            comparisonAttempted = false
            preparedPairSlot?.phase = PrimaryPreparedPairSlot.Phase.RETIRED
            preparedPairSlot = null
            availabilityWait?.timeout?.let { runCatching { scheduler.remove(it) } }
            availabilityWait = null
            verification.invalidate()
            frame().clearDetectionSnapshot()
            loadReason = "runtime_error_quiesced"
            statusText = "객체 인식 오류 · 이전 작업 정리 확인 후 기기 점검이 필요합니다."
        }
        auxiliary.releaseLoadingIfInitialized()
        auxiliary.closeActiveOrInvalidateObservations()
        return drained
    }

    fun quiescedReleaseConfirmed(captured: VisionRetirementSnapshot): CompletionStage<Unit> {
        val failed = synchronized(frameStateLock) { failedRetiredLoad }
            ?: return detector.quiescedReleaseConfirmed(captured)
        val original = failed.captured
        if (original == null || original.lease !== captured.lease || original.actualReleased !== captured.actualReleased ||
            original.allocationAvailable !== captured.allocationAvailable || failed.local == null || failed.model == null) {
            return CompletableFuture<Unit>().also { it.completeExceptionally(
                IllegalStateException("Retired primary cleanup has no matching local/model/cohort evidence")) }
        }
        val owner = WeakReference(this)
        return CompletableFuture.allOf(detector.quiescedReleaseConfirmed(captured).toCompletableFuture(),
            failed.local.toCompletableFuture(), failed.model.toCompletableFuture(),
            captured.actualReleased.toCompletableFuture(), captured.allocationAvailable.toCompletableFuture()).thenApply {
            val receipt = captured.actualReleased.toCompletableFuture().getNow(null)
            check(receipt != null && receipt.cohortId == captured.lease.cohortId &&
                (receipt.releaseProof == VisionReleaseProof.CHILD_DEATH && receipt.childPid > 0 ||
                    receipt.releaseProof == VisionReleaseProof.NO_NATIVE_START && receipt.childPid == 0)) {
                "Wrong retired primary cohort receipt"
            }
            owner.get()?.let { current -> synchronized(current.frameStateLock) {
                check(current.failedRetiredLoad == null || current.failedRetiredLoad === failed) {
                    "A newer primary close failure still owns its resources"
                }
                if (current.failedRetiredLoad === failed) current.failedRetiredLoad = null
            } }
            Unit // Cleanup only; neither host reopening nor a new native permit is issued here.
        }
    }

    /** Guard must include a new AuthorityAttempt, an explicit recheck action and auxiliary drains. */
    fun resumeQuiescedForRecheck(currentGuard: () -> Boolean): Boolean {
        val failed = synchronized(frameStateLock) { failedRetiredLoad }
        if (failed != null) {
            val captured = failed.captured ?: return false
            val confirmed = quiescedReleaseConfirmed(captured).toCompletableFuture()
            if (!confirmed.isDone || confirmed.isCompletedExceptionally || confirmed.isCancelled) return false
        }
        return detector.resumeQuiescedForRecheck { !currentness.destroyed() && currentGuard() }
    }

    private fun preparedCurrent(slot: PrimaryPreparedPairSlot) = slot.ownerKey === preparedReceiverKey &&
        preparedPairSlot === slot && slot.request == loadRequestGeneration && !currentness.destroyed() &&
        !detector.projection().closing && slot.claim.generation == detector.projection().loadGeneration && slot.current()

    /** Called immediately for every returned create result, including unavailable/failure DTOs. */
    fun retainCreatedLoad(slot: PrimaryPreparedPairSlot, load: AndroidDetectorLoadResult): Boolean = synchronized(frameStateLock) {
        if (slot.ownerKey !== preparedReceiverKey || slot.creationFinished || slot.load != null) return@synchronized false
        slot.load = load
        val physical = load.actualReleasedFuture
        when {
            physical != null -> slot.retainPhysical(physical)
            load.detector == null && load.reason != "DetectorNativeCleanupFailure" && load.reason != "DetectorCohortRetired" ->
                slot.retainPhysical(CompletableFuture.completedFuture(Unit))
            else -> {
                slot.retainUnproven()
                detector.recordReleaseFailure(IllegalStateException("Prepared primary physical receipt is missing"))
            }
        }
        detector.retainPhysicalRelease(slot.actualReleasedFuture, load.detector)
        val admitted = preparedCurrent(slot) && slot.phase == PrimaryPreparedPairSlot.Phase.RESERVED
        if (admitted) slot.phase = PrimaryPreparedPairSlot.Phase.CREATED
        admitted
    }

    /** Worker completion only; native errors still follow their independent physical receipt. */
    fun finishPreparedCreation(slot: PrimaryPreparedPairSlot, error: Throwable? = null) = synchronized(frameStateLock) {
        if (slot.ownerKey !== preparedReceiverKey || slot.creationFinished) return@synchronized
        slot.creationFailed = error != null
        if (slot.load == null) {
            when {
                error is DetectorCohortRetired -> slot.retainPhysical(error.actualReleasedFuture)
                error is DetectorNativeCleanupFailure && error.actualReleasedFuture != null -> slot.retainPhysical(error.actualReleasedFuture)
                VisionRuntimeFailures.busy(error) != null -> slot.retainPhysical(CompletableFuture.completedFuture(Unit))
                else -> {
                    slot.retainUnproven()
                    detector.recordReleaseFailure(error ?: IllegalStateException("Primary creation ended without a result"))
                }
            }
        }
        slot.sealCreation()
    }

    /** Explicit producer ACK: the create effect was never called, including preflight rejection. */
    fun finishPreparedNotStarted(slot: PrimaryPreparedPairSlot) = synchronized(frameStateLock) {
        if (slot.ownerKey !== preparedReceiverKey || slot.creationFinished) return@synchronized
        check(slot.load == null) { "A returned primary model cannot be acknowledged as not started" }
        slot.creationFailed = true
        slot.retainPhysical(CompletableFuture.completedFuture(Unit))
        slot.sealCreation()
    }

    /** No factory or OPEN: install the exact prepared instance, then verify one fresh sample. */
    fun adoptPreparedLoad(slot: PrimaryPreparedPairSlot, load: AndroidDetectorLoadResult,
        choice: ValidatedJointRuntimeChoice, identity: RunningDetectorIdentity? = null,
        onAdopted: (Boolean) -> Unit,
    ) {
        val instance = synchronized(frameStateLock) {
            val created = slot.load
            if (!preparedCurrent(slot) || slot.phase != PrimaryPreparedPairSlot.Phase.CREATED ||
                !slot.creationFinished || slot.creationFailed || !load.configLoaded || !load.detectorAvailable || load.fallbackUsed ||
                load.detector == null || created?.detector !== load.detector || created?.actualReleasedFuture !== load.actualReleasedFuture ||
                (identity != null && (identity.modelKey != load.modelKey || identity.configSha256 != configuration.sha256() ||
                    identity.activeDelegate != choice.candidate.primary.backend.delegate))) {
                presentation.onUi { onAdopted(false) }
                return
            }
            slot.phase = PrimaryPreparedPairSlot.Phase.ADOPTING
            load.detector
        }
        try {
            detector.nativeLane.execute {
                val loaded = PrimaryLoadedDetector(instance, true)
                val nativeAdmission = instance.isRuntimeAdmissionCurrent()
                val adopted = synchronized(frameStateLock) {
                    preparedCurrent(slot) && slot.phase == PrimaryPreparedPairSlot.Phase.ADOPTING &&
                        nativeAdmission &&
                        detector.adoptLoadedOnNativeLane(slot.claim, loaded, currentness::destroyed).also { if (it) loadAdoptionCommitted = true }
                }
                presentation.onUi {
                    val uiAdmission = instance.isRuntimeAdmissionCurrent()
                    val committed = synchronized(frameStateLock) {
                        if (!adopted || !preparedCurrent(slot) || slot.phase != PrimaryPreparedPairSlot.Phase.ADOPTING ||
                            !uiAdmission) false
                        else detector.admitLoadedOnMain(slot.claim, loaded, beforeAvailability = {
                            configLoaded = true
                            loadInFlight = false
                            loadAttempted = true
                            loadedModelKey = load.modelKey
                            fallbackUsed = load.fallbackUsed
                            loadReason = load.reason
                            reportModelKey = reportModelKey(load.modelKey)
                            choiceOverrideRequestId = choice.measurementId
                            statusText = "detector=prepared_pair_adopted model=${load.modelKey}"
                            calibration.retainActiveCandidate(choice.candidate.primary)
                            detector.configurePacingMinimum(choice.candidate.primaryIntervalMs)
                            slot.phase = PrimaryPreparedPairSlot.Phase.ADOPTED
                        }, destroyed = currentness::destroyed)
                    }
                    if (committed) {
                        scheduleVerification(instance, load.modelKey, load.fallbackUsed)
                        preparation.loaded()
                    }
                    onAdopted(committed)
                }
            }
        } catch (_: RejectedExecutionException) { presentation.onUi { onAdopted(false) } }
    }

    /** Exact-slot host cleanup; use slot.actualReleasedFuture for physical completion. */
    fun rollbackPreparedLoad(slot: PrimaryPreparedPairSlot,
        captured: VisionRetirementSnapshot? = null): CompletionStage<Boolean> {
        val closed = CompletableFuture<Boolean>()
        val instance = synchronized(frameStateLock) {
            if (slot.ownerKey !== preparedReceiverKey || slot.rollbackStarted) { closed.complete(false); return closed }
            slot.rollbackStarted = true
            slot.phase = PrimaryPreparedPairSlot.Phase.RETIRED
            if (preparedPairSlot === slot && slot.request == loadRequestGeneration) {
                preparedPairSlot = null
                loadRequestGeneration += 1L
                loadInFlight = false
                loadAttempted = false
                loadAdoptionCommitted = false
                configLoaded = false
                verification.invalidate()
            }
            slot.load?.detector
        }
        if (instance == null) { closed.complete(false); return closed }
        try {
            detector.nativeLane.execute {
                detector.disableUnverified(instance) { }
                try { closeRetiredLoad(instance, captured); closed.complete(true) }
                catch (error: Throwable) { closed.completeExceptionally(error) }
            }
        } catch (error: RejectedExecutionException) { closed.completeExceptionally(error) }
        return closed
    }

    /** Consumer drain, never a physical native-release receipt or another model preparation. */
    fun detachForTransferAfterDrain(): CompletionStage<PrimaryTransferredDetector> = synchronized(frameStateLock) {
        transferredHostDrain?.let { return@synchronized it }
        check(!loadInFlight && !currentness.destroyed() && !detector.projection().closing && detector.projection().available) {
            "primary transfer requires a completed live owner"
        }
        verification.invalidate()
        frame().clearDetectionSnapshot()
        configLoaded = false
        statusText = "detector=transferred_host_draining"
        detector.detachAfterCalibrationDrains { instance ->
            require(instance is TfliteAndroidFrameDetector)
            instance.detachForTransfer()
        }.also { transferredHostDrain = it }
    }

    fun metadata() = PrimaryLifecycleMetadata(configLoaded, reportModelKey, loadedModelKey, fallbackUsed,
        loadReason, loadAttempted, loadInFlight, statusText, fixedFixtureRunning)

    /** Keep readiness pending while the existing device check borrows the camera/native budget. */
    fun releaseForCompositeCheck(current: () -> Boolean, onReleased: (Boolean) -> Unit) {
        val claim: PrimaryDetectorLoadClaim
        synchronized(frameStateLock) {
            if (loadInFlight || detector.projection().closing || !current()) {
                presentation.onUi { onReleased(false) }
                return
            }
            loadRequestGeneration += 1L
            loadInFlight = true
            loadAttempted = true
            comparisonRequested = false
            comparisonAttempted = false
            claim = detector.beginLoad()
        }
        try {
            detector.nativeLane.execute {
                val released = runCatching {
                    detector.retireLoadedForReloadOnNativeLane(claim, current) { previous ->
                        if (previous is TfliteAndroidFrameDetector) platform.close(previous)
                        else (previous as? Closeable)?.close()
                    } != null
                }.onFailure(platform::logPreparationFailure).getOrDefault(false)
                synchronized(frameStateLock) {
                    verification.invalidate()
                    frame().clearDetectionSnapshot()
                }
                presentation.onUi { onReleased(released) }
            }
        } catch (_: RejectedExecutionException) { presentation.onUi { onReleased(false) } }
    }

    fun finishCompositeCheck(reload: Boolean, choiceOverride: ValidatedJointRuntimeChoice? = null) {
        synchronized(frameStateLock) {
            if (choiceOverride != null && choiceOverrideRequestId == choiceOverride.measurementId &&
                (loadInFlight || loadAttempted)) return
            loadInFlight = false
            loadAttempted = false
            configLoaded = false
            comparisonAttempted = false
        }
        if (reload) loadForCurrentProcess(choiceOverride = choiceOverride)
    }
    fun measuredProfileStatus(profile: RuntimeTuningProfile) {
        statusText = "detector=measured_profile runtime=${profile.candidate.backend} threads=${profile.candidate.numThreads}"
    }
    fun allowLoadRetryIfUnavailable() {
        if (!detector.projection().available && !detector.projection().closing && !loadInFlight) loadAttempted = false
    }
    fun verificationState(instance: AndroidFrameDetector) = verification.state(instance)
    fun verifiedIdentity() = verification.current()?.identity
    fun verificationAllows(instance: AndroidFrameDetector, identity: RunningDetectorIdentity?) = verification.allowsOutput(instance, identity)

    /** Retire only a pending comparison/load; an adopted native owner completes its original UI commit. */
    fun cancelPendingLoad() = synchronized(frameStateLock) {
        comparisonRequested = false
        if (!loadInFlight || loadAdoptionCommitted) return@synchronized
        loadRequestGeneration += 1L
        loadInFlight = false
        loadAttempted = false
        comparisonAttempted = false
        availabilityWait?.timeout?.let { runCatching { scheduler.remove(it) } }
        availabilityWait = null
    }

    /** Contention is neither model evidence nor this caller's native-release obligation. */
    private fun endAvailabilityWait(request: Long, status: String, expected: AvailabilityWait? = null) {
        val timer = synchronized(frameStateLock) {
            if (request != loadRequestGeneration || (expected != null && availabilityWait !== expected)) return
            val timer = availabilityWait?.timeout
            availabilityWait = null
            if (!currentness.destroyed() && !loadAdoptionCommitted) {
                loadInFlight = false
                loadAttempted = false
                comparisonAttempted = false
                comparisonRequested = false
                configLoaded = false
                statusText = status
            }
            timer
        }
        timer?.let { runCatching { scheduler.remove(it) } }
    }

    private fun armAvailabilityTimeout(wait: AvailabilityWait) {
        val delay = synchronized(frameStateLock) {
            if (availabilityWait !== wait) return
            (wait.window.deadline - elapsedRealtimeMs()).coerceAtLeast(0L)
        }
        val registering = AtomicBoolean(true)
        val synchronousFire = AtomicBoolean(false)
        val timeout = Runnable {
            if (registering.get()) synchronousFire.set(true)
            else {
                val current = synchronized(frameStateLock) {
                    availabilityWait === wait && loadInFlight && loadIsCurrent(wait.request, wait.detectorGeneration) &&
                        currentness.foreground() && wait.window.accepts(elapsedRealtimeMs())
                }
                if (current) armAvailabilityTimeout(wait)
                else endAvailabilityWait(wait.request, "이전 인식 작업 대기 만료", wait)
            }
        }
        synchronized(frameStateLock) {
            if (availabilityWait !== wait) return
            wait.timeout = timeout
        }
        val posted = runCatching { scheduler.postDelayed(timeout, delay) }.getOrDefault(false)
        registering.set(false)
        if (!posted || synchronousFire.get())
            endAvailabilityWait(wait.request, "이전 인식 작업 대기 중단", wait)
    }

    private fun waitForAvailability(busy: VisionCohortBusyException, request: Long, generation: Long,
        window: AvailabilityWindow, retry: Runnable) {
        val wait = AvailabilityWait(request, generation, window, retry)
        val admitted = synchronized(frameStateLock) {
            if (!loadInFlight || !loadIsCurrent(request, generation) || !currentness.foreground() ||
                !window.accepts(elapsedRealtimeMs())) false
            else { availabilityWait = wait; statusText = "이전 인식 작업 종료 대기 중"; true }
        }
        if (!admitted) { endAvailabilityWait(request, "이전 인식 작업 대기 만료"); return }
        armAvailabilityTimeout(wait)
        busy.availabilityReceipt.whenComplete { _, error ->
            val retryAllowed = synchronized(frameStateLock) {
                if (availabilityWait !== wait) return@whenComplete
                if (error != null || !loadInFlight || !loadIsCurrent(request, generation) || !currentness.foreground() ||
                    !window.accepts(elapsedRealtimeMs())) false
                else { availabilityWait = null; true }
            }
            if (!retryAllowed) {
                endAvailabilityWait(request, "이전 인식 작업 대기 만료", wait)
                return@whenComplete
            }
            wait.timeout?.let { runCatching { scheduler.remove(it) } }
            try { detector.tuningLane.execute(retry) }
            catch (_: RejectedExecutionException) { endAvailabilityWait(request, "이전 인식 작업 대기 중단") }
        }
    }

    /** Both lanes are serial: the tuning barrier follows creation, then native follows adoption/close. */
    fun cancelPendingLoadAfterDrain(onDrained: (Boolean) -> Unit) {
        compositeLoadDrainPending = true
        cancelPendingLoad()
        try {
            detector.tuningLane.execute {
                try {
                    detector.nativeLane.execute {
                        val released = !detector.projection().closing && failedRetiredLoad == null
                        presentation.onUi {
                            if (released) compositeLoadDrainPending = false
                            onDrained(released)
                        }
                    }
                } catch (_: RejectedExecutionException) { presentation.onUi { onDrained(false) } }
            }
        } catch (_: RejectedExecutionException) { presentation.onUi { onDrained(false) } }
    }

    private fun closeRetiredLoad(instance: TfliteAndroidFrameDetector, captured: VisionRetirementSnapshot? = null) {
        try {
            platform.close(instance)
            // Verification may already have closed this facade before its pair's exact rollback.
            // A successful repeated close must not strand that earlier failure without its capture.
            if (captured != null) {
                val local = runCatching { instance.hostReleasedFuture() }.getOrNull()
                val model = runCatching { instance.actualReleasedFuture() }.getOrNull()
                synchronized(frameStateLock) {
                    val previous = failedRetiredLoad
                    if (previous?.detector === instance && previous.captured == null &&
                        local != null && model != null && previous.local === local && previous.model === model)
                        failedRetiredLoad = RetiredLoadFailure(instance, local, model, captured)
                }
            }
        }
        catch (error: Throwable) {
            val local = runCatching { instance.hostReleasedFuture() }.getOrNull()
            val model = runCatching { instance.actualReleasedFuture() }.getOrNull()
            synchronized(frameStateLock) {
                val previous = failedRetiredLoad?.takeIf {
                    it.detector === instance && it.local === local && it.model === model
                }
                failedRetiredLoad = RetiredLoadFailure(instance, local, model, previous?.captured ?: captured)
                detector.recordDetectorReleaseFailure(instance, error)
                detector.fenceForClose()
            }
            throw error
        } finally { detector.forgetReleasedDetector(instance) }
    }

    private fun failedPreparationRelease(error: Throwable, proof: CompletionStage<Unit>?, request: Long,
        reason: String, metricDistanceWasEnabled: Boolean) {
        // Ownership survives cancellation, while obsolete requests have no authority over the UI.
        if (proof != null) detector.recordReleaseFailure(error, proof) else detector.recordReleaseFailure(error)
        val ownsRequest = synchronized(frameStateLock) {
            detector.fenceForClose()
            (request == loadRequestGeneration && !currentness.destroyed()).also { owns -> if (owns) {
                loadInFlight = false
                configLoaded = false
                loadReason = reason
                statusText = "detector=$loadReason"
            } }
        }
        platform.logPreparationFailure(error)
        if (ownsRequest) presentation.onUi {
            if (request != loadRequestGeneration || currentness.destroyed()) return@onUi
            if (preparation.featureGatePassed()) preparation.modelUnavailable(metricDistanceWasEnabled)
            preparation.loaded()
        }
    }

    private fun loadIsCurrent(request: Long, detectorGeneration: Long) = request == loadRequestGeneration &&
        !currentness.destroyed() && !detector.projection().closing && detector.projection().loadGeneration == detectorGeneration

    private fun reloadIsAllowed() = !preparation.activeOrPausedWalk() && !calibration.projection().preparing &&
        calibration.projection().session == null && !calibration.projection().oldDetectorReleasePending &&
        !auxiliary.calibrationPresent()

    fun loadForCurrentProcess(allowComparison: Boolean = false, choiceOverride: ValidatedJointRuntimeChoice? = null) {
        require(choiceOverride == null || !allowComparison) { "An explicit choice must not start comparison" }
        val metricDistanceWasEnabled: Boolean
        var loadGeneration: Long
        val requestGeneration: Long
        val reload: Boolean
        synchronized(frameStateLock) {
            if (loadAttempted && (!allowComparison || comparisonAttempted)) return
            if (loadInFlight) {
                if (allowComparison) comparisonRequested = true
                return
            }
            if (loadInFlight || compositeLoadDrainPending || detector.projection().closing || currentness.destroyed()) return
            reload = detector.projection().detector !is NoopAndroidFrameDetector
            if (reload && !reloadIsAllowed()) return
            metricDistanceWasEnabled = preparation.metricDistanceEnabled()
            loadAttempted = true
            comparisonAttempted = allowComparison
            comparisonRequested = false
            loadInFlight = true
            loadAdoptionCommitted = false
            loadRequestGeneration += 1L
            requestGeneration = loadRequestGeneration
            loadGeneration = detector.projection().loadGeneration
            choiceOverrideRequestId = choiceOverride?.measurementId
            availabilityWait?.timeout?.let { runCatching { scheduler.remove(it) } }
            availabilityWait = null
        }
        var availabilityWindow: AvailabilityWindow? = null
        lateinit var loadTask: Runnable
        loadTask = Runnable loadTask@{
            if (requestGeneration != loadRequestGeneration) return@loadTask
            fun mayRetryAvailability(): Boolean {
                val window = availabilityWindow ?: return true
                if (loadIsCurrent(requestGeneration, loadGeneration) && loadInFlight && currentness.foreground() &&
                    window.accepts(elapsedRealtimeMs())) return true
                endAvailabilityWait(requestGeneration, "이전 인식 작업 대기 만료")
                return false
            }
            if (!mayRetryAvailability()) return@loadTask
            val load = try {
                val config = configuration.loadOrCurrent()
                if (!mayRetryAvailability()) return@loadTask
                if (choiceOverride != null) {
                    if (!loadIsCurrent(requestGeneration, loadGeneration)) throw CancellationException("Measured preparation retired")
                    platform.prepareMeasuredChoice(requireNotNull(config) { "Explicit choice requires its model configuration" },
                        choiceOverride, current = { loadIsCurrent(requestGeneration, loadGeneration) })
                } else if (config == null) platform.loadDefault()
                else platform.select(config, allowComparison = allowComparison,
                    current = { loadIsCurrent(requestGeneration, loadGeneration) },
                    progress = { message -> presentation.onUi {
                        if (requestGeneration == loadRequestGeneration && !currentness.destroyed() && loadInFlight && currentness.foreground()) {
                            statusText = message
                            preparation.progress(message)
                        }
                    } })
            } catch (error: Throwable) {
                VisionRuntimeFailures.busy(error)?.let { busy ->
                    val started = availabilityWindow?.startedAt ?: elapsedRealtimeMs()
                    if (started < 0L || started > Long.MAX_VALUE - verification.pendingDeadlineMs) {
                        endAvailabilityWait(requestGeneration, "이전 인식 작업 대기 만료")
                    } else {
                        val window = availabilityWindow ?: AvailabilityWindow(started, started + verification.pendingDeadlineMs)
                            .also { availabilityWindow = it }
                        waitForAvailability(busy, requestGeneration, loadGeneration, window, loadTask)
                    }
                    return@loadTask
                }
                if (error is DetectorNativeCleanupFailure || error is DetectorCohortRetired) {
                    failedPreparationRelease(error, (error as? DetectorCohortRetired)?.actualReleasedFuture,
                        requestGeneration, error.javaClass.simpleName, metricDistanceWasEnabled)
                    return@loadTask
                }
                if (error is CancellationException) {
                    synchronized(frameStateLock) {
                        if (requestGeneration == loadRequestGeneration) cancelPendingLoad()
                    }
                    return@loadTask
                }
                if (requestGeneration != loadRequestGeneration) return@loadTask
                platform.logPreparationFailure(error)
                AndroidDetectorLoadResult(null, false, false, null, false, error.javaClass.simpleName)
            }
            // OPEN may have failed without producing a Java detector. Keep its stable physical
            // receipt before checking request freshness or enqueueing native adoption/retirement.
            detector.retainPhysicalRelease(load.actualReleasedFuture, load.detector)
            if (load.reason == DetectorCohortRetired::class.java.simpleName ||
                load.reason == DetectorNativeCleanupFailure::class.java.simpleName) {
                failedPreparationRelease(IllegalStateException(load.reason), load.actualReleasedFuture,
                    requestGeneration, load.reason, metricDistanceWasEnabled)
                return@loadTask
            }
            try {
                detector.nativeLane.execute nativeAdoption@{
                    val claim = PrimaryDetectorLoadClaim(loadGeneration)
                    val loaded = PrimaryLoadedDetector(load.detector, load.detectorAvailable)
                    val adopted = synchronized(frameStateLock) {
                        if (requestGeneration != loadRequestGeneration) false
                        else detector.adoptLoadedOnNativeLane(claim, loaded, currentness::destroyed).also {
                            if (it) loadAdoptionCommitted = true
                        }
                    }
                    if (!adopted) {
                        load.detector?.let(::closeRetiredLoad)
                        return@nativeAdoption
                    }
                    presentation.onUi {
                        if (requestGeneration != loadRequestGeneration) return@onUi
                        loadInFlight = false
                        if (!detector.admitLoadedOnMain(claim, loaded, destroyed = currentness::destroyed, beforeAvailability = {
                            loadAttempted = true
                            configLoaded = load.configLoaded
                        })) return@onUi
                        loadedModelKey = load.modelKey
                        fallbackUsed = load.fallbackUsed
                        loadReason = load.reason
                        reportModelKey = reportModelKey(load.modelKey)
                        statusText = "detector=${load.reason} model=${load.modelKey ?: "-"}"
                        load.detector?.let { instance ->
                            val runtime = platform.firstSuccessfulDelegate(instance)
                            calibration.retainActiveCandidate(platform.observedCandidate(instance) ?: RuntimeCandidate(
                                if (runtime == "gpu") RuntimeBackend.GPU else RuntimeBackend.CPU,
                                (configuration.current()?.unifiedWalksafe?.runtime?.numThreads ?: 1)
                                    .coerceIn(1, platform.availableProcessors().coerceAtLeast(1))))
                            platform.measuredPrimaryIntervalMs(instance)?.let(detector::configurePacingMinimum)
                            scheduleVerification(instance, load.modelKey, load.fallbackUsed)
                        }
                        if (preparation.featureGatePassed() && !detector.projection().available) preparation.modelUnavailable(metricDistanceWasEnabled)
                        // Keep a true request made during cache-only loading pending until the
                        // adopted owner can be drained. Do this before publishing check readiness.
                        if (comparisonRequested && !allowComparison) loadForCurrentProcess(allowComparison = true)
                        preparation.loaded()
                    }
                }
            } catch (_: RejectedExecutionException) { load.detector?.let(::closeRetiredLoad) }
        }
        if (!reload) {
            detector.tuningLane.execute(loadTask)
            return
        }
        fun reloadFailed(error: Throwable) {
            platform.logPreparationFailure(error)
            presentation.onUi {
                if (requestGeneration != loadRequestGeneration) return@onUi
                loadInFlight = false
                configLoaded = false
                loadReason = error.javaClass.simpleName
                if (preparation.featureGatePassed() && !detector.projection().available)
                    preparation.modelUnavailable(metricDistanceWasEnabled)
                preparation.loaded()
            }
        }
        try {
            detector.nativeLane.execute retire@{
                if (requestGeneration != loadRequestGeneration) return@retire
                try {
                    val next = detector.retireLoadedForReloadOnNativeLane(PrimaryDetectorLoadClaim(loadGeneration),
                        current = { loadIsCurrent(requestGeneration, loadGeneration) && reloadIsAllowed() }, close = { previous ->
                            if (previous is TfliteAndroidFrameDetector) platform.close(previous)
                            else (previous as? Closeable)?.close()
                        }) ?: run {
                            synchronized(frameStateLock) {
                                if (requestGeneration == loadRequestGeneration) cancelPendingLoad()
                            }
                            return@retire
                        }
                    loadGeneration = next.generation
                    synchronized(frameStateLock) {
                        verification.invalidate()
                        frame().clearDetectionSnapshot()
                    }
                    detector.tuningLane.execute(loadTask)
                } catch (error: Throwable) { reloadFailed(error) }
            }
        } catch (error: RejectedExecutionException) { reloadFailed(error) }
    }

    fun beginVerification(instance: AndroidFrameDetector, nowMs: Long) = verification.begin(instance, nowMs)
    fun recordVerification(instance: AndroidFrameDetector, identity: RunningDetectorIdentity?, evidence: RunningDetectorLoadEvidence,
        expectedGeneration: Long, nowMs: () -> Long): RunningDetectorVerificationStep = synchronized(frameStateLock) {
        if (detector.projection().detector !== instance || detector.projection().loadGeneration != expectedGeneration || detector.projection().closing)
            return@synchronized RunningDetectorVerificationStep.STALE
        verification.recordAttempt(instance, identity, evidence, nowMs())
    }
    fun scheduleVerification(instance: TfliteAndroidFrameDetector, modelKey: String?, fallbackUsed: Boolean, retryAttempt: Boolean = false) {
        if (!retryAttempt) {
            beginVerification(instance, elapsedRealtimeMs())
            scheduler.postDelayed(Runnable { expireVerification(instance) }, verification.pendingDeadlineMs)
        }
        val verify = Runnable {
            val generation = synchronized(frameStateLock) {
                if (detector.projection().detector !== instance || detector.projection().closing) return@Runnable
                detector.projection().loadGeneration
            }
            val sample = runCatching { platform.sample(instance) }.getOrNull()
            val identity = RunningDetectorIdentity.of(configuration.current(), configuration.sha256(), modelKey,
                sample?.timing?.modelRuntime?.activeDelegate, fallbackUsed, generation)
            val step = recordVerification(instance, identity, RunningDetectorLoadEvidence(true, true,
                sample?.inferenceCompleted == true), generation, elapsedRealtimeMs)
            when (step) {
                RunningDetectorVerificationStep.RETRY -> scheduler.postDelayed(Runnable {
                    if (!currentness.destroyed()) scheduleVerification(instance, modelKey, fallbackUsed, retryAttempt = true)
                }, verification.retryDelayMs)
                RunningDetectorVerificationStep.FAILED -> presentation.onUi { disableUnverified(instance) }
                RunningDetectorVerificationStep.VERIFIED -> presentation.onUi {
                    if (!currentness.destroyed()) preparation.modelVerified()
                }
                RunningDetectorVerificationStep.STALE -> Unit
            }
        }
        try { detector.nativeLane.execute(verify) }
        catch (_: RejectedExecutionException) {
            verification.invalidate()
            if (verification.fail(instance)) presentation.onUi { disableUnverified(instance) }
        }
    }
    fun expireVerification(instance: TfliteAndroidFrameDetector) {
        if (currentness.destroyed()) return
        if (verification.expire(instance, elapsedRealtimeMs())) disableUnverified(instance)
    }
    fun disableUnverified(instance: TfliteAndroidFrameDetector) {
        if (currentness.destroyed()) return
        val disabled = detector.disableUnverified(instance) {
            loadReason = RunningDetectorReadiness.SAMPLE_INFERENCE_UNVERIFIED_REASON
            statusText = "detector=$loadReason"
            frame().clearDetectionSnapshot()
        }
        if (!disabled) return
        if (calibration.projection().session != null || auxiliary.calibrationPresent()) auxiliary.cancelCalibration("detector_verification_failed")
        try { detector.nativeLane.execute { closeRetiredLoad(instance) } }
        catch (error: RejectedExecutionException) { detector.recordReleaseFailure(error) }
        if (preparation.featureGatePassed()) preparation.disableUnverifiedCapabilities()
        val walking = preparation.activeOrPausedWalk()
        if (walking) failureEffects.enterSafetyStop()
        preparation.showVerificationFailure(walking)
        preparation.modelVerified()
    }
    fun blocksWalkEntry() = RunningDetectorReadiness.blocksWalkEntry(verification.state(detector.projection().detector),
        detector.projection().available, loadReason)
    fun verifiedForOutput() = configLoaded && detector.projection().available && verification.allowsOutput(
        detector.projection().detector, detector.projection().loadGeneration, configuration.sha256())

    fun fixedFixturePerformanceCheck() {
        if (fixedFixtureRunning) return
        val instance = detector.projection().detector as? TfliteAndroidFrameDetector
        if (instance == null || !detector.projection().available) {
            loadForCurrentProcess()
            presentation.status("고정 자료 성능 확인 대기", "객체 인식 모델을 준비한 뒤 다시 눌러 주세요.")
            return
        }
        val generation = detector.projection().loadGeneration
        fixedFixtureRunning = true
        presentation.status("고정 자료 성능 확인 중", "카메라를 사용하지 않고 내장 768×768 자료 4종을 반복 실행합니다.")
        detector.nativeLane.execute {
            val result = runCatching { platform.performance(instance) }
            presentation.onUi {
                fixedFixtureRunning = false
                if (generation != detector.projection().loadGeneration || detector.projection().detector !== instance) return@onUi
                result.onSuccess { value -> presentation.status("고정 자료 성능 확인 완료",
                    "카메라 미사용 · 자료 ${value.fixtureCount}종 · 측정 ${value.samples}회 · " +
                        "중앙 전체 ${value.medianTotalMs}ms · 추론 ${value.medianInferenceMs}ms · " +
                        "실행 ${value.activeDelegate} · 객체 출력 ${value.totalDetectedObjects}건")
                }.onFailure { presentation.status("고정 자료 성능 확인 실패", "내장 시험 자료 또는 객체 인식 모델 실행을 완료하지 못했습니다.") }
            }
        }
    }

    fun runtimeFailure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch) {
        if (!currentness.frameIsCurrent(generation, epoch)) return
        val run = generation to epoch
        val runtimeConfig = configuration.current()
        val legacyAvailable = runtimeConfig?.fallbackModelKey == TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY &&
            runtimeConfig.customTactile != null && runtimeConfig.cocoGeneral != null
        when (supervisor.onFailure(loadedModelKey, legacyAvailable, true, detectorRuntimeFailureKind(error),
            elapsedRealtimeMs(), run)) {
            DetectorRuntimeFailureAction.KEEP_CURRENT -> {
                if (currentness.frameIsCurrent(generation, epoch)) statusText = "detector=runtime_failure_${supervisor.consecutiveFailures} error=${error::class.java.simpleName}"
                else supervisor.disarmValidOutputClock()
            }
            DetectorRuntimeFailureAction.SAFE_STOP -> {
                if (failureEffects.developmentBypassEnabled()) { disableAfterFailure(error, generation, epoch); return }
                val safetyHandled = failureEffects.observeSafetyFailure(epoch, elapsedRealtimeMs())
                disableAfterFailure(error, generation, epoch, safetyHandled)
            }
            DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK, DetectorRuntimeFailureAction.DISABLE -> disableAfterFailure(error, generation, epoch)
        }
    }
    fun disableAfterFailure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch, safetyHandled: Boolean = false) {
        val disabledGeneration = detector.disableFailedIfCurrent({ currentness.frameIsCurrent(generation, epoch) },
            failureEffects::resetVisualTracking, afterUnavailable = {
                loadReason = "runtime_failed_unavailable"
                statusText = "detector=runtime_failed_unavailable error=${error::class.java.simpleName}"
            })
        if (disabledGeneration == null) { supervisor.disarmValidOutputClock(); return }
        clearDetectionAfterTransition(epoch)
        presentation.onUi {
            if (!currentness.frameIsCurrent(disabledGeneration, epoch)) return@onUi
            if (!safetyHandled) failureEffects.enterSafetyStop()
            failureEffects.stopCameraFallback()
            failureEffects.stopDepthSession()
            failureEffects.blockReportCandidates()
            failureEffects.showDetectorUnavailable()
        }
    }
    fun clearDetectionAfterTransition(epoch: WalkRuntimeEpoch) {
        val generation = synchronized(frameStateLock) {
            if (!currentness.epochIsCurrent(epoch)) return
            frame().clearDetectionSnapshot()
            detector.projection().frameGeneration
        }
        presentation.onUi { if (currentness.frameIsCurrent(generation, epoch)) presentation.clearOverlay() }
    }
    fun closeAsync() {
        auxiliary.releaseLoadingIfInitialized()
        if (calibration.deferDetectorCloseIfCalibrationPresent(auxiliary::calibrationPresent)) {
            auxiliary.cancelCalibration("detector_close")
            return
        }
        auxiliary.closeActiveOrInvalidateObservations()
        detector.closeAfterCalibrationDrains()
    }
    private fun reportModelKey(modelKey: String?) = when (modelKey) {
        "unified_walksafe" -> "unified_walksafe"
        "legacy_two_model", "custom_tactile" -> "custom_tactile"
        else -> null
    }
}
