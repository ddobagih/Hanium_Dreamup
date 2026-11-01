package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.RuntimeLoadCompletion
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeLoadControl
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeBinding
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortBusyException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionTransferResult
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole

/** One actual native auxiliary slot. Constructor only stores dependencies; it performs no SDK query. */
internal class UnknownRuntimeServiceOwner(
    private val profiles: UnknownRuntimeProfileRepository,
    private val factory: UnknownRuntimeFactory,
    private val authority: UnknownRuntimeServiceAuthority,
    private val clock: UnknownCaptureClock,
    private val deviceTuningExecutor: Executor,
    private val mainDispatcher: (() -> Unit) -> Unit,
    private val invalidateObservations: () -> Unit,
    private val onResult: (FastSamResult<UnknownRuntimeAttachment>) -> Unit,
    private val onDiscard: (FastSamFrameToken, String) -> Unit,
    private val onEvent: (UnknownRuntimeServiceEvent) -> Unit,
) {
    private val ownerLock = Any()
    private class WalkingRequest(val source: UnknownWalkingSource,
        val config: () -> TwoModelRuntimeConfig?, val candidate: () -> RuntimeCandidate?)
    @Volatile private var walkingRequest: WalkingRequest? = null
    private class WalkingAvailability(val request: WalkingRequest, val startedNs: Long)
    @Volatile private var walkingAvailability: WalkingAvailability? = null
    private class WalkingService(val service: FastSamRuntimeService<UnknownRuntimeAttachment>, val request: WalkingRequest)
    private var activeWalkingService: WalkingService? = null
    @Volatile private var unknownRuntimeService: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
    @Volatile private var preparation: ServicePreparationAttempt? = null
    private val unknownLoadingService get() = preparation?.service
    @Volatile private var unknownCalibration: UnknownCalibrationSession? = null
    @Volatile private var unknownRuntimeBinding: UnknownRuntimeBinding? = null
    @Volatile private var unknownJointChoice: ValidatedJointRuntimeChoice? = null
    @Volatile private var release: ServiceReleaseAttempt? = null
    /** A successful host detach relinquishes native execution without requesting native close. */
    private class DetachedPair(service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        val choice: ValidatedJointRuntimeChoice?) {
        val oldService = WeakReference(service)
        val physical = service.releasedFuture()
        val token = CompletableFuture<FastSamTransferredModel>()
        val hostDrained = CompletableFuture<Unit>()
        var resumed: VisionTransferResult? = null
        var reservation: Any? = null
        var retiring = false
    }
    @Volatile private var detachedPair: DetachedPair? = null
    private val unknownRuntimeClosing get() = release != null
    @Volatile private var unknownRuntimeStatus = "추가 물체 인식 준비 전"
    private val unknownServiceSequence = AtomicLong(0L)
    private val unknownLoadControl = UnknownRuntimeLoadControl()
    @Volatile private var unknownLoadRecheckRecorded = false
    @Volatile private var latestUnknownLoadObservation: InferenceLoadObservation? = null

    /** One caller owns readiness, its binding and its one completion event. */
    private class ServicePreparationAttempt(
        @Volatile var binding: UnknownRuntimeBinding?,
        val current: () -> Boolean,
        val composition: Boolean,
        val walkingRequest: WalkingRequest? = null,
        val pairToken: Any? = null,
        val returning: DetachedPair? = null,
    ) {
        @Volatile var service: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
        var jointChoice: ValidatedJointRuntimeChoice? = null
        val terminal = AtomicBoolean(false)
    }

    /** Failed native close keeps this exact target in the slot; no replacement may start. */
    private class ServiceReleaseAttempt(val service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        val onReleased: () -> Unit = {}) {
        val started = AtomicBoolean(false)
    }

    private fun ownsPreparation(attempt: ServicePreparationAttempt) = synchronized(ownerLock) {
        preparation === attempt && !attempt.terminal.get()
    }

    private fun finishPreparation(attempt: ServicePreparationAttempt) {
        synchronized(ownerLock) {
            if (!ownsPreparation(attempt) || !attempt.terminal.compareAndSet(false, true)) return
            preparation = null
        }
        if (attempt.composition && attempt.current()) onEvent(UnknownRuntimeServiceEvent.PreparationFinished)
        continueWalkingPreparation()
    }

    private fun failPreparation(attempt: ServicePreparationAttempt) {
        synchronized(ownerLock) {
            if (!ownsPreparation(attempt) || !attempt.terminal.compareAndSet(false, true)) return
        }
        attempt.service?.let(::requestRelease)
        if (attempt.composition && attempt.current()) onEvent(UnknownRuntimeServiceEvent.PreparationFinished)
    }

    private fun finishRelease(attempt: ServiceReleaseAttempt, error: Throwable?) {
        synchronized(ownerLock) {
            if (release !== attempt || error != null) return
            preparation?.takeIf { it.service === attempt.service }?.let {
                it.terminal.set(true)
                preparation = null
            }
            release = null
        }
        // Source checks, factories and synchronous callbacks never run under ownerLock.
        attempt.onReleased()
        continueWalkingPreparation()
    }

    private fun reserveRelease(service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        onReleased: () -> Unit = {}): ServiceReleaseAttempt {
        release?.takeIf { it.service === service }?.let { return it }
        check(release == null) { "unknown_native_release_already_owned" }
        return ServiceReleaseAttempt(service, onReleased).also { release = it }
    }

    private fun startRelease(attempt: ServiceReleaseAttempt) {
        if (!attempt.started.compareAndSet(false, true)) return
        attempt.service.closeAsync()
        // An unreclaimed driver must retain metadata, not a destroyed Activity's listeners.
        val ownerRef = WeakReference(this)
        val attemptRef = WeakReference(attempt)
        attempt.service.releasedFuture().whenComplete { _, error ->
            ownerRef.get()?.mainDispatcher {
                val retained = attemptRef.get()
                if (retained != null) ownerRef.get()?.finishRelease(retained, error)
            }
        }
    }

    private fun requestRelease(service: FastSamRuntimeService<UnknownRuntimeAttachment>): ServiceReleaseAttempt {
        val attempt = synchronized(ownerLock) { reserveRelease(service) }
        startRelease(attempt)
        return attempt
    }

    /** This wait remains exclusively on the existing tuning owner. */
    private fun releaseOnTuningOwner(service: FastSamRuntimeService<UnknownRuntimeAttachment>): Boolean {
        return awaitReleaseOnTuningOwner(requestRelease(service))
    }

    private fun awaitReleaseOnTuningOwner(attempt: ServiceReleaseAttempt): Boolean {
        try { attempt.service.releasedFuture().get() } catch (_: Exception) {
            synchronized(ownerLock) {
                if (release === attempt) unknownRuntimeStatus = "추가 물체 인식 정리 확인 필요"
            }
            return false
        }
        finishRelease(attempt, null)
        return true
    }

    val currentService get() = unknownRuntimeService
    val loadingService get() = unknownLoadingService

    /** No native close or replacement. Old listener admission is revoked before the host drain. */
    fun detachPreparedPairForTransfer(expectedService: FastSamRuntimeService<UnknownRuntimeAttachment>): CompletionStage<FastSamTransferredModel> {
        val retiring = synchronized(ownerLock) {
            detachedPair?.let { existing ->
                return if (existing.oldService.get() === expectedService && !existing.retiring) existing.token
                else CompletableFuture<FastSamTransferredModel>().also { it.completeExceptionally(IllegalStateException("Different auxiliary owns the transfer")) }
            }
            if (unknownRuntimeService !== expectedService || !expectedService.isReady || preparation != null ||
                release != null || unknownCalibration != null)
                return CompletableFuture<FastSamTransferredModel>().also { it.completeExceptionally(IllegalStateException("Auxiliary transfer requires the exact ready service")) }
            DetachedPair(expectedService, unknownJointChoice).also {
                detachedPair = it
                unknownRuntimeService = null
                unknownRuntimeBinding = null
                unknownJointChoice = null
                walkingRequest = null
                walkingAvailability = null
                activeWalkingService = null
                latestUnknownLoadObservation = null
            }
        }
        try { invalidateObservations() } catch (error: Throwable) {
            finishDetachedHostAfterClose(expectedService, retiring.hostDrained)
            retiring.token.completeExceptionally(error)
            return retiring.token
        }
        try {
            expectedService.drainForTransfer().whenComplete { token, error ->
                if (error == null && token != null) {
                    // Service publishes a successful token only after its functional close barrier.
                    retiring.hostDrained.complete(Unit)
                    retiring.token.complete(token)
                }
                else {
                    finishDetachedHostAfterClose(expectedService, retiring.hostDrained)
                    retiring.token.completeExceptionally(error ?: IllegalStateException("Auxiliary transfer token is missing"))
                }
            }
        } catch (error: Throwable) {
            finishDetachedHostAfterClose(expectedService, retiring.hostDrained)
            retiring.token.completeExceptionally(error)
        }
        return retiring.token
    }

    /** A rejected transfer may precede drain. Only the actual functional-close barrier proves it. */
    private fun finishDetachedHostAfterClose(service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        host: CompletableFuture<Unit>) {
        try { service.closeAsync().whenComplete { _, _ -> host.complete(Unit) } }
        catch (error: Throwable) { host.completeExceptionally(error) }
    }

    /** A source Activity joins only this host receipt; the target still owns the physical model. */
    fun transferredHostDrain(): CompletionStage<Unit>? = detachedPair?.hostDrained

    /** A return receipt is not a new model allocation. Exactly one same-source reservation may use it. */
    fun resumeTransferredHost(receipt: VisionTransferResult, currentGuard: () -> Boolean): Boolean {
        if (!runCatching(currentGuard).getOrDefault(false)) return false
        val returning = synchronized(ownerLock) {
            val old = detachedPair ?: return false
            if (old.retiring || old.resumed != null || old.reservation != null || preparation != null || release != null ||
                unknownRuntimeService != null || !old.token.isDone || old.token.isCompletedExceptionally || old.token.isCancelled) return false
            val token = old.token.getNow(null) ?: return false
            val lease = token.oldLease
            if (lease.ownerEpoch > Long.MAX_VALUE - 2 || receipt.lease.ownerId != lease.ownerId ||
                receipt.lease.cohortId != token.cohortId || receipt.lease.ownerEpoch != lease.ownerEpoch + 2 ||
                receipt.handles.size != 2 || receipt.handles.map { it.role }.toSet() != setOf(VisionRole.PRIMARY, VisionRole.FASTSAM) ||
                receipt.handles.any { it.cohortId != token.cohortId || it.ownerEpoch != receipt.lease.ownerEpoch }) return false
            val auxiliary = receipt.handles.single { it.role == VisionRole.FASTSAM }
            if (auxiliary.modelInstanceId != token.modelInstanceId || auxiliary.modelKey != token.oldHandle.modelKey ||
                auxiliary.runtimeJson != token.oldHandle.runtimeJson) return false
            old
        }
        if (!runCatching(currentGuard).getOrDefault(false)) return false
        return synchronized(ownerLock) {
            if (detachedPair !== returning || returning.retiring || returning.resumed != null || returning.reservation != null ||
                preparation != null || release != null || unknownRuntimeService != null) false
            else { returning.resumed = receipt; true }
        }
    }

    /** Host reservation only; this is not a transfer token or a native-ready proof. */
    class PreparedPairSlot internal constructor(internal val token: Any, val epoch: Long,
        val listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>,
        private val current: () -> Boolean) {
        fun isCurrent(): Boolean = current()
    }

    fun reservePreparedPair(current: () -> Boolean): PreparedPairSlot? {
        if (!runCatching { current() && !authority.destroyedOrDetectorClosing() }.getOrDefault(false)) return null
        val token = Any()
        val returning = synchronized(ownerLock) { detachedPair }
        val attempt = ServicePreparationAttempt(null, current, false, pairToken = token, returning = returning)
        val accepted = synchronized(ownerLock) {
            if (preparation != null || release != null || unknownRuntimeService != null ||
                unknownCalibration != null || walkingRequest != null || detachedPair !== returning ||
                returning != null && (returning.retiring || returning.resumed == null || returning.reservation != null)) false else {
                returning?.reservation = token
                preparation = attempt
                true
            }
        }
        if (!accepted) return null
        val epoch = unknownServiceSequence.incrementAndGet()
        val slot = PreparedPairSlot(token, epoch, listener(epoch, true)) { preparedPairCurrent(token, attempt) }
        if (!slot.isCurrent()) { abortPreparedPair(slot); return null }
        return slot
    }

    private fun preparedPairCurrent(token: Any, attempt: ServicePreparationAttempt): Boolean {
        fun owned() = synchronized(ownerLock) {
            preparation === attempt && attempt.pairToken === token && !attempt.terminal.get() &&
                release == null && walkingRequest == null &&
                (attempt.returning == null || detachedPair === attempt.returning && attempt.returning.reservation === token)
        }
        if (!owned() || !runCatching {
                attempt.current() && !authority.destroyedOrDetectorClosing()
            }.getOrDefault(false)) return false
        return owned() // Caller guards may synchronously revoke or replace the reservation.
    }

    /** Registers before start/OPEN, so a concurrent teardown cannot miss the loading service. */
    fun registerPreparedPair(slot: PreparedPairSlot,
        service: FastSamRuntimeService<UnknownRuntimeAttachment>): Boolean {
        if (service.sessionEpoch != slot.epoch || service.stats().stopping) return false
        if (!slot.isCurrent()) { abortPreparedPair(slot); return false }
        val accepted = synchronized(ownerLock) {
            val attempt = preparation ?: return@synchronized false
            if (attempt.pairToken !== slot.token || attempt.terminal.get() || attempt.service != null) false
            else { attempt.service = service; true }
        }
        if (!accepted) return false
        if (!slot.isCurrent()) { abortPreparedPair(slot); return false }
        return true
    }

    fun adoptPreparedPair(slot: PreparedPairSlot, service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        choice: ValidatedJointRuntimeChoice): Boolean {
        val ownsService = synchronized(ownerLock) {
            preparation?.let { it.pairToken === slot.token && it.service === service && !it.terminal.get() } == true
        }
        if (!ownsService || service.sessionEpoch != slot.epoch) return false
        if (!slot.isCurrent()) { abortPreparedPair(slot); return false }
        val actual = runCatching { service.runtimeIfReady() }.getOrNull()
        val expected = choice.candidate.auxiliary
        if (actual == null || actual.fallbackUsed || actual.requestedBackend.name != expected.backend.name ||
            actual.actualBackend.name != expected.backend.name || actual.numThreads != expected.numThreads ||
            actual.gpuPrecisionLossRequested || actual.gpuPrecisionLossAllowed || !actual.gpuSerializationCacheRequested) {
            abortPreparedPair(slot)
            return false
        }
        if (!slot.isCurrent()) { abortPreparedPair(slot); return false }
        val returning = synchronized(ownerLock) { preparation?.takeIf { it.pairToken === slot.token }?.returning }
        if (returning != null) {
            val receipt = returning.resumed ?: return false
            val handle = receipt.handles.singleOrNull { it.role == VisionRole.FASTSAM }
            if (handle == null || actual.modelInstanceId != handle.modelInstanceId || actual.cohortId != handle.cohortId ||
                returning.choice?.let { it != choice } == true) {
                abortPreparedPair(slot)
                return false
            }
        }
        return synchronized(ownerLock) {
            val attempt = preparation ?: return@synchronized false
            if (attempt.pairToken !== slot.token || attempt.service !== service || attempt.terminal.get() ||
                release != null || unknownRuntimeService != null || walkingRequest != null ||
                unknownCalibration != null || service.stats().stopping) return@synchronized false
            if (!attempt.terminal.compareAndSet(false, true)) return@synchronized false
            preparation = null
            if (attempt.returning != null && detachedPair === attempt.returning) detachedPair = null
            unknownRuntimeService = service
            unknownRuntimeBinding = null // A walking source binds this exact ready instance later.
            unknownJointChoice = choice
            activeWalkingService = null
            unknownLoadRecheckRecorded = false
            latestUnknownLoadObservation = null
            unknownRuntimeStatus = "추가 인식 준비 완료 · 샘플 이미지 성능 점검 설정 · ${actual.actualBackend}"
            true
        }
    }

    /** Only the still-reserved slot is cancelled; a late abort cannot close an adopted/new service. */
    fun abortPreparedPair(slot: PreparedPairSlot) {
        val retiring = synchronized(ownerLock) {
            val attempt = preparation ?: return
            if (attempt.pairToken !== slot.token || !attempt.terminal.compareAndSet(false, true)) return
            val service = attempt.service
            if (service == null) { preparation = null; null } else reserveRelease(service)
        }
        if (retiring != null) startRelease(retiring) else continueWalkingPreparation()
    }

    /** Read after the tuning barrier to include late creation and already-detached close owners. */
    fun retainedReleaseReceipts(): List<CompletableFuture<Void>> = synchronized(ownerLock) {
        listOfNotNull(unknownRuntimeService, preparation?.service, release?.service)
            .distinct().map { it.releasedFuture() }
    }
    val binding get() = unknownRuntimeBinding
    val closing get() = unknownRuntimeClosing
    val calibrationRunning get() = unknownCalibration?.isRunning == true
    val calibrationDraining get() = unknownCalibration?.isDraining == true
    val hasCalibration get() = unknownCalibration != null
    val status get() = unknownRuntimeStatus
    val latestLoadObservation get() = latestUnknownLoadObservation
    fun minimumAuxiliaryIntervalMs(service: FastSamRuntimeService<UnknownRuntimeAttachment>): Long? =
        unknownJointChoice?.takeIf { unknownRuntimeService === service }?.candidate?.auxiliaryIntervalMs
    fun reportCaptureStatus(value: String) { unknownRuntimeStatus = value }
    fun recordLoadObservation(value: InferenceLoadObservation?) { latestUnknownLoadObservation = value }
    fun pauseLoadObservation() { unknownLoadControl.pause(); latestUnknownLoadObservation = null }

    private fun listenerCurrent(epoch: Long): Boolean = synchronized(ownerLock) {
        unknownRuntimeService?.sessionEpoch == epoch || preparation?.takeIf { !it.terminal.get() }?.service?.sessionEpoch == epoch
    }

    private fun listener(epoch: Long, errorsOnMain: Boolean) = object : FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {
        override fun onResult(result: FastSamResult<UnknownRuntimeAttachment>) {
            if (!listenerCurrent(epoch)) return
            val joint = unknownJointChoice?.takeIf { unknownRuntimeService?.sessionEpoch == epoch }
            if (joint != null && (result.runtime.fallbackUsed ||
                    result.runtime.actualBackend.name != joint.candidate.auxiliary.backend.name ||
                    result.runtime.numThreads != joint.candidate.auxiliary.numThreads)) {
                mainDispatcher { handleUnknownRuntimeError(epoch, IllegalStateException("joint_runtime_mismatch")) }
                return
            }
            this@UnknownRuntimeServiceOwner.onResult(result)
        }
        override fun onDiscard(token: FastSamFrameToken, reason: String) {
            if (listenerCurrent(epoch)) this@UnknownRuntimeServiceOwner.onDiscard(token, reason)
        }
        override fun onError(error: Throwable) {
            if (errorsOnMain) mainDispatcher {
                if (!authority.destroyedOrDetectorClosing()) handleUnknownRuntimeError(epoch, error)
            } else handleUnknownRuntimeError(epoch, error)
        }
    }

    /** A null source retires only automatic preparation; explicit calibration keeps its own authority. */
    fun startForWalk(source: UnknownWalkingSource?, currentConfig: () -> TwoModelRuntimeConfig?,
        currentCandidate: () -> RuntimeCandidate?) {
        if (detachedPair != null) return // A transferred native model is not a missing independent auxiliary.
        val requested = if (source != null && source.isCurrent() && authority.mayStartWalkingService())
            WalkingRequest(source, currentConfig, currentCandidate) else null
        if (requested != null && profiles.requiresJointChoice && synchronized(ownerLock) {
                preparation?.pairToken != null
            }) {
            unknownRuntimeStatus = "두 모델 준비 중 · 준비가 끝난 뒤 보행을 시작할 수 있습니다."
            return // A walk request cannot steal/abort the atomic pair's existing preparation.
        }
        val jointMatches = unknownJointChoice?.let {
            requested != null && requested.candidate() == it.candidate.primary
        } ?: !profiles.requiresJointChoice
        var invalidate = false
        val closing = synchronized(ownerLock) {
            val retained = walkingRequest?.takeIf { requested != null && it.source.sameOwner(requested.source) }
            walkingRequest = retained ?: requested
            val active = activeWalkingService
            val loading = preparation?.takeIf { !it.composition && it.pairToken == null }
            val loadingSource = loading?.walkingRequest
            val keepActive = active != null && requested != null && jointMatches && active.request.source.sameOwner(requested.source)
            val keepLoading = loadingSource != null && requested != null && loadingSource.source.sameOwner(requested.source)
            if (keepActive) activeWalkingService = WalkingService(active!!.service, requireNotNull(walkingRequest))
            val retired = when {
                active != null && !keepActive -> active.service.also {
                    unknownRuntimeService = null
                    activeWalkingService = null
                    invalidate = true
                }
                loading != null && !keepLoading -> {
                    loading.terminal.set(true)
                    loading.service
                }
                else -> null
            }
            retired?.let(::reserveRelease)
        }
        // Reserve the physical slot before invalidation can reenter and request another source.
        try { if (invalidate) invalidateObservations() } finally { closing?.let(::startRelease) }
        continueWalkingPreparation()
    }

    private fun walkingCurrent(request: WalkingRequest): Boolean = walkingRequest === request &&
        request.source.isCurrent() && authority.mayStartWalkingService() && !authority.destroyedOrDetectorClosing()

    private fun continueWalkingPreparation() {
        if (detachedPair != null) return
        val request = walkingRequest ?: return
        if (!walkingCurrent(request)) return
        if (profiles.requiresJointChoice) {
            adoptPreparedPairForWalking(request)
            return
        }
        val waiting = walkingAvailability
        if (waiting != null && waiting.request === request) {
            if (!busyWaitCurrent(waiting)) resumeAfterBusy(waiting, IllegalStateException("availability_wait_expired"))
            return
        }
        val unmeasured = synchronized(ownerLock) {
            unknownRuntimeService?.takeIf { profiles.requiresJointChoice && unknownJointChoice == null }?.let {
                unknownRuntimeService = null
                activeWalkingService = null
                reserveRelease(it)
            }
        }
        if (unmeasured != null) { startRelease(unmeasured); return }
        val attempt = synchronized(ownerLock) {
            if (walkingRequest !== request || preparation != null || release != null || unknownCalibration != null) return
            unknownRuntimeService?.let {
                // An explicitly prepared ready service becomes source-bound only when walking adopts it.
                activeWalkingService = WalkingService(it, request)
                return
            }
            ServicePreparationAttempt(null, { walkingCurrent(request) }, false, request).also { preparation = it }
        }
        fun abandonEmpty() {
            synchronized(ownerLock) {
                if (preparation === attempt && attempt.service == null) {
                    attempt.terminal.set(true)
                    preparation = null
                    if (walkingRequest === request) walkingRequest = null
                }
            }
            continueWalkingPreparation()
        }
        deviceTuningExecutor.execute { try {
            if (!ownsPreparation(attempt) || !walkingCurrent(request)) { abandonEmpty(); return@execute }
            val config = request.config() ?: run { abandonEmpty(); return@execute }
            val candidate = request.candidate() ?: run { abandonEmpty(); return@execute }
            val joint = profiles.loadJoint(config, candidate)?.takeIf { it.candidate.primary == candidate }
            if (profiles.requiresJointChoice && joint == null) {
                unknownRuntimeStatus = "기기 점검 필요 · 저장된 인식 조합을 확인하지 못했습니다."
                abandonEmpty()
                return@execute
            }
            // Legacy comparison records remain separate; a fixture choice never claims live scope.
            val binding = if (joint == null) profiles.createBinding(config, candidate, RuntimeFeatureScope.CAMERA_TRACKING) else null
            val selected = joint?.candidate?.auxiliary ?: binding?.let { profiles.load(it) }?.candidate
            if (!ownsPreparation(attempt) || !walkingCurrent(request)) { abandonEmpty(); return@execute }
            attempt.binding = binding
            attempt.jointChoice = joint
            val epoch = unknownServiceSequence.incrementAndGet()
            val service = factory.createService(epoch, FastSamRuntimeOptions(
                if (selected?.backend == RuntimeBackend.CPU) FastSamRuntimeOptions.Backend.CPU else FastSamRuntimeOptions.Backend.GPU,
                joint?.candidate?.auxiliary?.numThreads ?:
                    (selected?.numThreads ?: candidate.numThreads).coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
                joint == null, clock.elapsedRealtimeNanos() + 60_000_000_000L, MAX_SOURCE_AGE_MS, false, false, true),
                listener(epoch, errorsOnMain = true))
            attempt.service = service
            if (!ownsPreparation(attempt) || !walkingCurrent(request)) { requestRelease(service); return@execute }
            unknownRuntimeStatus = "이름 없는 물체 인식 준비 중"
            service.start().whenComplete { info, error -> mainDispatcher {
                if (!ownsPreparation(attempt)) return@mainDispatcher
                val busy = VisionRuntimeFailures.busy(error)
                if (busy != null && walkingCurrent(request)) {
                    waitForAvailability(request, service, busy)
                    return@mainDispatcher
                }
                val matches = joint == null || info != null && !info.fallbackUsed &&
                    info.actualBackend.name == joint.candidate.auxiliary.backend.name &&
                    info.numThreads == joint.candidate.auxiliary.numThreads
                val workloadMatches = joint == null || request.candidate() == joint.candidate.primary
                val current = error == null && info != null && matches && walkingCurrent(request) && workloadMatches
                val adopted = synchronized(ownerLock) {
                    if (current && preparation === attempt && !attempt.terminal.get() && walkingRequest === request) {
                        unknownRuntimeService = service
                        activeWalkingService = WalkingService(service, request)
                        unknownRuntimeBinding = attempt.binding
                        unknownJointChoice = joint
                        unknownLoadControl.reset()
                        unknownRuntimeStatus = "이름 없는 물체 인식 · ${info!!.actualBackend}"
                        true
                    } else false
                }
                if (adopted) finishPreparation(attempt) else {
                    synchronized(ownerLock) { if (walkingRequest === request) walkingRequest = null }
                    releaseUnknownLoadingService(service)
                    if (joint != null && busy == null && (!matches || error != null)) deviceTuningExecutor.execute {
                        profiles.invalidateJointChoice(joint)
                    }
                    unknownRuntimeStatus = if (busy != null) "추가 물체 인식 준비 전"
                        else if (joint != null && (!matches || error != null || !workloadMatches)) "기기 점검 필요 · 인식 실행 설정을 확인하지 못했습니다."
                        else if (error != null) "이름 없는 물체 준비 실패: ${error.javaClass.simpleName}"
                        else "추가 물체 인식 준비 전"
                }
            } }
        } catch (error: RuntimeException) {
            synchronized(ownerLock) { if (walkingRequest === request) walkingRequest = null }
            attempt.service?.let(::releaseUnknownLoadingService) ?: abandonEmpty()
            unknownRuntimeStatus = "추가 물체 인식 준비 실패: ${error.javaClass.simpleName}"
            throw error
        } }
    }

    /** Production uses the pair already prepared by the coordinator. Historical choices and a
     * late Context supplier cannot authorize a new independent auxiliary OPEN on this path. */
    private fun adoptPreparedPairForWalking(request: WalkingRequest) {
        val candidate = request.candidate()
        if (!walkingCurrent(request)) return
        val service = unknownRuntimeService
        val choice = unknownJointChoice
        val ready = service != null && choice != null && choice.candidate.primary == candidate && service.isReady
        if (ready) {
            val prepared = requireNotNull(service)
            synchronized(ownerLock) {
                if (walkingRequest === request && preparation == null && release == null && unknownCalibration == null &&
                    unknownRuntimeService === prepared && unknownJointChoice === choice && !prepared.stats().stopping) {
                    activeWalkingService = WalkingService(prepared, request)
                }
            }
            return
        }
        val reason = if (service == null || choice == null) "actual_pair_missing" else "actual_pair_retired_or_changed"
        val retiring = synchronized(ownerLock) {
            if (walkingRequest !== request) return
            walkingRequest = null
            walkingAvailability = null
            activeWalkingService = null
            val old = unknownRuntimeService
            unknownRuntimeService = null
            release ?: old?.let(::reserveRelease)
        }
        unknownRuntimeStatus = "기기 점검 필요 · 두 모델이 함께 준비되지 않아 보행 인식을 중지했습니다."
        try { onEvent(UnknownRuntimeServiceEvent.PairPreparationRequired(reason)) }
        finally { retiring?.let(::startRelease) }
    }

    private fun busyWaitCurrent(waiting: WalkingAvailability): Boolean {
        val now = clock.elapsedRealtimeNanos()
        return waiting.startedNs >= 0L && now >= waiting.startedNs &&
            now - waiting.startedNs < 15_000_000_000L
    }

    private fun waitForAvailability(request: WalkingRequest,
        service: FastSamRuntimeService<UnknownRuntimeAttachment>, busy: VisionCohortBusyException) {
        val waiting = WalkingAvailability(request, clock.elapsedRealtimeNanos())
        val reserved = synchronized(ownerLock) {
            if (walkingRequest !== request) false
            else { walkingAvailability = waiting; true }
        }
        if (!reserved) { releaseUnknownLoadingService(service); return }
        unknownRuntimeStatus = "이전 인식 작업 종료 대기 중"
        // Reserve the wait before closing this empty service: its synchronous release callback
        // must not immediately retry a still-busy cohort and start an unbounded preparation loop.
        releaseUnknownLoadingService(service)
        val ownerRef = WeakReference(this)
        val waitingRef = WeakReference(waiting)
        busy.availabilityReceipt.whenComplete { _, error ->
            ownerRef.get()?.mainDispatcher {
                val retained = waitingRef.get()
                if (retained != null) ownerRef.get()?.resumeAfterBusy(retained, error)
            }
        }
    }

    private fun resumeAfterBusy(waiting: WalkingAvailability, error: Throwable?) {
        val retry = error == null && busyWaitCurrent(waiting) && walkingCurrent(waiting.request)
        val owned = synchronized(ownerLock) {
            if (walkingAvailability !== waiting || walkingRequest !== waiting.request) false
            else {
                walkingAvailability = null
                if (!retry) walkingRequest = null
                true
            }
        }
        if (!owned) return
        if (retry) continueWalkingPreparation()
        else unknownRuntimeStatus = "이전 인식 작업 대기 만료 · 다시 준비해 주세요."
    }

    /** Cancel intent before native close can synchronously acknowledge while Activity is still foreground. */
    private fun clearWalkingIntent() { synchronized(ownerLock) {
        walkingRequest = null
        walkingAvailability = null
    } }

    /** Called on the existing tuning executor; native release is not awaited on UI/GL. */
    fun prepareComposition(config: TwoModelRuntimeConfig, primary: TfliteAndroidFrameDetector,
        primaryCandidate: RuntimeCandidate, scope: RuntimeFeatureScope, manual: Boolean,
        deadlineMs: Long, current: () -> Boolean,
    ) {
        if (detachedPair != null) {
            mainDispatcher { if (runCatching(current).getOrDefault(false)) onEvent(UnknownRuntimeServiceEvent.PreparationFinished) }
            return
        }

        if (profiles.requiresJointChoice) {
            // Atomic pair preparation is the only production OPEN/calibration path. This legacy
            // completion cannot clear a pending pair slot or retire an already-good live pair.
            mainDispatcher {
                fun callerCurrent() = runCatching { current() }.getOrDefault(false)
                if (!callerCurrent()) return@mainDispatcher
                val pendingPair = synchronized(ownerLock) { preparation?.pairToken != null }
                val choice = unknownJointChoice
                val actual = runCatching { unknownRuntimeService?.runtimeIfReady() }.getOrNull()
                val goodPair = choice != null && choice.candidate.primary == primaryCandidate && actual != null &&
                    !actual.fallbackUsed && actual.actualBackend.name == choice.candidate.auxiliary.backend.name &&
                    actual.requestedBackend.name == choice.candidate.auxiliary.backend.name &&
                    actual.numThreads == choice.candidate.auxiliary.numThreads && !actual.gpuPrecisionLossAllowed &&
                    !actual.gpuPrecisionLossRequested && actual.gpuSerializationCacheRequested
                if (!callerCurrent()) return@mainDispatcher
                if (!pendingPair && !goodPair) {
                    unknownRuntimeStatus = "기기 점검 필요 · 두 모델을 함께 준비해야 합니다."
                    onEvent(UnknownRuntimeServiceEvent.PairPreparationRequired("standalone_auxiliary_preparation_forbidden"))
                    if (!callerCurrent()) return@mainDispatcher
                }
                onEvent(UnknownRuntimeServiceEvent.PreparationFinished)
            }
            return
        }
        clearWalkingIntent()
        unknownJointChoice = null
        fun finishWithoutWork() = mainDispatcher {
            if (current()) onEvent(UnknownRuntimeServiceEvent.PreparationFinished)
        }
        if (preparation != null || unknownRuntimeClosing) { finishWithoutWork(); return }
        val binding = profiles.createBinding(config, primaryCandidate, scope)
        if (binding == null || !current()) { finishWithoutWork(); return }
        val attempt = ServicePreparationAttempt(binding, current, true)
        val claimed = synchronized(ownerLock) {
            if (preparation != null || release != null) false else {
                preparation = attempt
                // Explicit preparation owns an existing service from this point onward.
                activeWalkingService = null
                true
            }
        }
        if (!claimed) { finishWithoutWork(); return }
        val profile = profiles.load(binding)
        val recheck = profiles.needsRecheck(binding, manual)
        val existing = unknownRuntimeService
        if (!recheck && existing != null && unknownRuntimeBinding == binding && !existing.stats().stopping) {
            mainDispatcher { finishPreparation(attempt) }; return
        }
        unknownRuntimeService = null
        invalidateObservations()
        if (existing != null && !releaseOnTuningOwner(existing)) {
            mainDispatcher { finishPreparation(attempt) }; return
        }
        if (!current() || !ownsPreparation(attempt)) { mainDispatcher { finishPreparation(attempt) }; return }
        val serviceEpoch = unknownServiceSequence.incrementAndGet()
        if (!recheck) {
            val selected = profile?.candidate ?: RuntimeCandidate(RuntimeBackend.GPU, primaryCandidate.numThreads)
            val restored = factory.createService(serviceEpoch,
                FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(selected.backend.name), selected.numThreads,
                    profile == null, deadlineMs * 1_000_000L, MAX_SOURCE_AGE_MS, false, false, true),
                listener(serviceEpoch, errorsOnMain = false))
            val registered = synchronized(ownerLock) {
                if (preparation !== attempt || attempt.terminal.get() || release != null) false
                else { attempt.service = restored; true }
            }
            if (!registered) { restored.closeAsync(); return }
            if (!current()) { releaseUnknownLoadingService(restored); return }
            restored.start().whenComplete { info, error -> mainDispatcher {
                if (!ownsPreparation(attempt)) return@mainDispatcher
                if (error == null && info != null && current() && !unknownRuntimeClosing) {
                    unknownRuntimeService = restored
                    unknownRuntimeBinding = binding
                    unknownLoadControl.reset()
                    unknownLoadRecheckRecorded = false
                    unknownRuntimeStatus = if (profile != null && !info.fallbackUsed)
                        "추가 물체 인식 · 저장된 ${info.actualBackend} 설정" else "추가 물체 인식 · ${info.actualBackend} 임시 설정"
                    finishPreparation(attempt)
                } else {
                    if (error != null && VisionRuntimeFailures.busy(error) == null && current() && !unknownRuntimeClosing) {
                        deviceTuningExecutor.execute { profiles.markPending(binding, DeviceTuningRecheckReason.EXECUTION_RECOVERY) }
                    }
                    if (current()) unknownRuntimeStatus = "추가 물체 인식 준비 미완료"
                    failPreparation(attempt)
                }
            } }
            return
        }
        mainDispatcher {
            if (!ownsPreparation(attempt)) return@mainDispatcher
            if (!current() || !authority.calibrationCameraAvailable()) {
                finishPreparation(attempt); return@mainDispatcher
            }
            onEvent(UnknownRuntimeServiceEvent.CalibrationWillStart)
            val calibrationDepth = UnknownObjectDepthPipeline(maximumSourceAgeMs = MAX_SOURCE_AGE_MS,
                elapsedRealtimeMs = clock::elapsedRealtimeMs)
            lateinit var calibration: UnknownCalibrationSession
            calibration = factory.createCalibration(UnknownCalibrationRequest(binding, primary, serviceEpoch, deadlineMs,
                isCurrent = { current() && unknownCalibration === calibration },
                speechActive = authority::speechActive,
                onSelected = { selection ->
                    selection.tryAdopt {
                        if (!current() || unknownCalibration !== calibration || unknownRuntimeService != null) false else {
                            unknownRuntimeService = selection.readyService
                            unknownRuntimeBinding = binding
                            unknownLoadControl.reset()
                            unknownLoadRecheckRecorded = false
                            unknownRuntimeStatus = if (selection.profile != null) "추가 물체 인식 · 조합 성능 확인됨"
                                else "추가 물체 인식 · ${selection.candidate.backend} 임시 설정, 성능 비교 미확정"
                            true
                        }
                    }
                },
                onClosed = { result ->
                    if (unknownCalibration === calibration) {
                        unknownCalibration = null
                        // The explicit check keeps the measured profile, not another idle native
                        // model alongside the separate HOME camera. Publish completion after proof.
                        val released = synchronized(ownerLock) {
                            val ready = unknownRuntimeService?.takeIf { it.sessionEpoch == serviceEpoch }
                            if (ready == null) null else {
                                unknownRuntimeService = null
                                unknownRuntimeBinding = null
                                reserveRelease(ready) { onEvent(UnknownRuntimeServiceEvent.CalibrationClosed(result)) }
                            }
                        }
                        if (released != null) startRelease(released)
                        else {
                            onEvent(UnknownRuntimeServiceEvent.CalibrationClosed(result))
                            continueWalkingPreparation()
                        }
                    }
                },
                onProductionResult = onResult,
                onProductionError = { handleUnknownRuntimeError(serviceEpoch, it) },
                onProductionDiscard = onDiscard,
                onCalibrationResult = { result, evidence ->
                    if (scope == RuntimeFeatureScope.CAMERA_TRACKING) {
                        // The CameraX workload has no ARCore metric claim.
                        evidence.cameraTimestampNs > 0 && evidence.sourceFrameTimestampNs > 0
                    } else {
                        val snapshot = evidence.depthSnapshot
                        val mapperFrame = evidence.mapperFrameId
                        val capture = if (snapshot != null && mapperFrame != null) FrozenUnknownDepthCapture.freeze(
                            result.token, snapshot, evidence.imageToDepthUv, mapperFrame,
                        ) else null
                        if (capture == null) false else {
                            val outputs = calibrationDepth.process(capture, result.token, result.masks, clock.elapsedRealtimeMs())
                            outputs.rejectionReason == null && evidence.poseValid &&
                                (scope != RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH ||
                                    snapshot!!.hasFreshMetricRawDepth && evidence.positiveDepthSamples > 0)
                        }
                    }
                },
            ))
            if (!ownsPreparation(attempt) || !current()) {
                calibration.cancel("primary_release_request_retired")
                return@mainDispatcher
            }
            unknownCalibration = calibration
            if (!calibration.start() && unknownCalibration === calibration) unknownCalibration = null
            finishPreparation(attempt)
        }
    }

    fun handleUnknownRuntimeError(serviceEpoch: Long, error: Throwable) {
        val failedBinding: UnknownRuntimeBinding?
        val failedJoint: ValidatedJointRuntimeChoice?
        val releaseAttempt = synchronized(ownerLock) {
            val failed = unknownRuntimeService?.takeIf { it.sessionEpoch == serviceEpoch } ?: return
            failedBinding = unknownRuntimeBinding
            failedJoint = unknownJointChoice
            unknownJointChoice = null
            activeWalkingService?.takeIf { it.service === failed }?.let {
                if (walkingRequest === it.request) walkingRequest = null
                activeWalkingService = null
            }
            unknownRuntimeService = null
            reserveRelease(failed)
        }
        try {
            invalidateObservations()
            if (!profiles.requiresJointChoice && (failedJoint != null || failedBinding != null)) deviceTuningExecutor.execute {
                if (failedJoint != null) profiles.invalidateJointChoice(failedJoint)
                else if (failedBinding != null) profiles.markPending(failedBinding, DeviceTuningRecheckReason.EXECUTION_RECOVERY)
            }
            unknownRuntimeStatus = if (failedJoint != null) "기기 점검 필요 · 추가 물체 인식 실행이 중지되었습니다."
                else "추가 물체 인식 중지 · ${error.javaClass.simpleName}"
            if (profiles.requiresJointChoice) onEvent(UnknownRuntimeServiceEvent.PairPreparationRequired("auxiliary_runtime_failed"))
        } finally { startRelease(releaseAttempt) }
    }

    fun releaseUnknownLoadingService(service: FastSamRuntimeService<UnknownRuntimeAttachment>) {
        synchronized(ownerLock) {
            val attempt = preparation?.takeIf { it.service === service } ?: return
            // Retire readiness before closeAsync can synchronously complete its ready future.
            attempt.terminal.set(true)
        }
        requestRelease(service)
    }

    fun observeLoad(nowMs: Long, normal: Boolean, inputObserved: Boolean = false,
        primaryCompletion: RuntimeLoadCompletion? = null, auxiliaryCompletion: RuntimeLoadCompletion? = null,
    ) {
        val binding = unknownRuntimeBinding ?: return
        val service = unknownRuntimeService ?: return
        val key = binding.hashOrNull() ?: return
        val pending = unknownLoadControl.observe(nowMs, "$key:${service.sessionEpoch}", normal,
            inputObserved, primaryCompletion, auxiliaryCompletion)
        if (pending && !unknownLoadRecheckRecorded) {
            unknownLoadRecheckRecorded = true
            runCatching { deviceTuningExecutor.execute {
                if (unknownRuntimeBinding == binding && !profiles.markPending(binding,
                        DeviceTuningRecheckReason.PERSISTENT_DEGRADATION)) unknownLoadRecheckRecorded = false
            } }
        }
    }

    fun cancelCalibration(reason: String) { clearWalkingIntent(); unknownCalibration?.cancel(reason) }
    fun releaseLoadingIfPresent() { clearWalkingIntent(); unknownLoadingService?.let(::releaseUnknownLoadingService) }

    /** Retires queued reservations before exposing the receipt. No thread blocks on a driver ACK.
     * A not-yet-created service can no longer register or start; its constructor has no native work.
     * Detached models are excluded from ordinary source teardown; this API is explicit final release. */
    fun releaseBeforePrimaryCalibrationAsync(): CompletionStage<Boolean> {
        val retiredTransfer: DetachedPair?
        val releaseAttempt = synchronized(ownerLock) {
            walkingRequest = null
            walkingAvailability = null
            activeWalkingService = null
            val loading = preparation
            loading?.terminal?.set(true)
            if (loading?.service == null) preparation = null
            val active = unknownRuntimeService
            unknownRuntimeService = null
            retiredTransfer = detachedPair
            retiredTransfer?.retiring = true
            release ?: (active ?: loading?.service)?.let(::reserveRelease)
        }
        val invalidationFailed = runCatching { invalidateObservations() }.isFailure
        releaseAttempt?.let(::startRelease)
        val currentPhysical = releaseAttempt?.service?.releasedFuture() ?: CompletableFuture.completedFuture(null)
        val transferHost = retiredTransfer?.hostDrained ?: CompletableFuture.completedFuture(Unit)
        val transferPhysical = retiredTransfer?.physical ?: CompletableFuture.completedFuture(null)
        val all = CompletableFuture.allOf(currentPhysical, transferHost, transferPhysical)
        val result = CompletableFuture<Boolean>()
        val owner = WeakReference(this)
        val releasing = releaseAttempt?.let { WeakReference(it) }
        all.whenComplete { _, error ->
            val current = owner.get()
            if (error == null) {
                releasing?.get()?.let { current?.finishRelease(it, null) }
                if (current != null && retiredTransfer != null) synchronized(current.ownerLock) {
                    if (current.detachedPair === retiredTransfer) current.detachedPair = null
                }
            } else if (current != null) synchronized(current.ownerLock) {
                if (current.release === releasing?.get()) current.unknownRuntimeStatus = "추가 물체 인식 정리 확인 필요"
            }
            result.complete(error == null && !invalidationFailed)
        }
        return result
    }

    /** Diagnostic compatibility. Android UI and metadata callbacks use the asynchronous API. */
    fun releaseBeforePrimaryCalibration(): Boolean = try {
        releaseBeforePrimaryCalibrationAsync().toCompletableFuture().get()
    } catch (_: Exception) { false }

    /** The original detector teardown only requests auxiliary close and does not await it. */
    fun closeActiveForDetector() {
        val releaseAttempt = synchronized(ownerLock) {
            walkingRequest = null
            walkingAvailability = null
            activeWalkingService = null
            unknownRuntimeService?.also { unknownRuntimeService = null }?.let(::reserveRelease)
        }
        try { invalidateObservations() } finally { releaseAttempt?.let(::startRelease) }
    }

    private companion object { const val MAX_SOURCE_AGE_MS = 800L }
}
