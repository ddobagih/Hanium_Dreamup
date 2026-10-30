package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import java.util.concurrent.Executor
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
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeLoadControl
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeBinding

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
        val config: () -> TwoModelRuntimeConfig?, val candidate: () -> RuntimeCandidate?,
        val scope: () -> RuntimeFeatureScope)
    @Volatile private var walkingRequest: WalkingRequest? = null
    private class WalkingService(val service: FastSamRuntimeService<UnknownRuntimeAttachment>, val request: WalkingRequest)
    private var activeWalkingService: WalkingService? = null
    @Volatile private var unknownRuntimeService: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
    @Volatile private var preparation: ServicePreparationAttempt? = null
    private val unknownLoadingService get() = preparation?.service
    @Volatile private var unknownCalibration: UnknownCalibrationSession? = null
    @Volatile private var unknownRuntimeBinding: UnknownRuntimeBinding? = null
    @Volatile private var release: ServiceReleaseAttempt? = null
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
    ) {
        @Volatile var service: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
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
        attempt.service.releasedFuture().whenComplete { _, error -> mainDispatcher { finishRelease(attempt, error) } }
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
    val binding get() = unknownRuntimeBinding
    val closing get() = unknownRuntimeClosing
    val calibrationRunning get() = unknownCalibration?.isRunning == true
    val calibrationDraining get() = unknownCalibration?.isDraining == true
    val hasCalibration get() = unknownCalibration != null
    val status get() = unknownRuntimeStatus
    val latestLoadObservation get() = latestUnknownLoadObservation
    fun reportCaptureStatus(value: String) { unknownRuntimeStatus = value }
    fun recordLoadObservation(value: InferenceLoadObservation?) { latestUnknownLoadObservation = value }
    fun pauseLoadObservation() { unknownLoadControl.pause(); latestUnknownLoadObservation = null }

    private fun listener(epoch: Long, errorsOnMain: Boolean) = object : FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {
        override fun onResult(result: FastSamResult<UnknownRuntimeAttachment>) = this@UnknownRuntimeServiceOwner.onResult(result)
        override fun onDiscard(token: FastSamFrameToken, reason: String) = this@UnknownRuntimeServiceOwner.onDiscard(token, reason)
        override fun onError(error: Throwable) {
            if (errorsOnMain) mainDispatcher {
                if (!authority.destroyedOrDetectorClosing()) handleUnknownRuntimeError(epoch, error)
            } else handleUnknownRuntimeError(epoch, error)
        }
    }

    /** A null source retires only automatic preparation; explicit calibration keeps its own authority. */
    fun startForWalk(source: UnknownWalkingSource?, currentConfig: () -> TwoModelRuntimeConfig?,
        currentCandidate: () -> RuntimeCandidate?, currentScope: () -> RuntimeFeatureScope) {
        val requested = if (source != null && source.isCurrent() && authority.mayStartWalkingService())
            WalkingRequest(source, currentConfig, currentCandidate, currentScope) else null
        var invalidate = false
        val closing = synchronized(ownerLock) {
            val retained = walkingRequest?.takeIf { requested != null && it.source.sameOwner(requested.source) }
            walkingRequest = retained ?: requested
            val active = activeWalkingService
            val loading = preparation?.takeIf { !it.composition }
            val loadingSource = loading?.walkingRequest
            val keepActive = active != null && requested != null && active.request.source.sameOwner(requested.source)
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
        val request = walkingRequest ?: return
        if (!walkingCurrent(request)) return
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
        try {
            val config = request.config() ?: run { abandonEmpty(); return }
            val candidate = request.candidate() ?: run { abandonEmpty(); return }
            val binding = profiles.createBinding(config, candidate, request.scope())
            val selected = binding?.let { profiles.load(it) }?.candidate
            if (!ownsPreparation(attempt) || !walkingCurrent(request)) { abandonEmpty(); return }
            attempt.binding = binding
            val epoch = unknownServiceSequence.incrementAndGet()
            val service = factory.createService(epoch, FastSamRuntimeOptions(
                if (selected?.backend == RuntimeBackend.CPU) FastSamRuntimeOptions.Backend.CPU else FastSamRuntimeOptions.Backend.GPU,
                (selected?.numThreads ?: candidate.numThreads).coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
                true, clock.elapsedRealtimeNanos() + 60_000_000_000L, MAX_SOURCE_AGE_MS, false, false, true),
                listener(epoch, errorsOnMain = true))
            attempt.service = service
            if (!ownsPreparation(attempt) || !walkingCurrent(request)) { requestRelease(service); return }
            unknownRuntimeStatus = "이름 없는 물체 인식 준비 중"
            service.start().whenComplete { info, error -> mainDispatcher {
                if (!ownsPreparation(attempt)) return@mainDispatcher
                val current = error == null && info != null && walkingCurrent(request)
                val adopted = synchronized(ownerLock) {
                    if (current && preparation === attempt && !attempt.terminal.get() && walkingRequest === request) {
                        unknownRuntimeService = service
                        activeWalkingService = WalkingService(service, request)
                        unknownRuntimeBinding = attempt.binding
                        unknownLoadControl.reset()
                        unknownRuntimeStatus = "이름 없는 물체 인식 · ${info!!.actualBackend}"
                        true
                    } else false
                }
                if (adopted) finishPreparation(attempt) else {
                    synchronized(ownerLock) { if (walkingRequest === request) walkingRequest = null }
                    releaseUnknownLoadingService(service)
                    unknownRuntimeStatus = if (error != null) "이름 없는 물체 준비 실패: ${error.javaClass.simpleName}"
                        else "추가 물체 인식 준비 전"
                }
            } }
        } catch (error: RuntimeException) {
            synchronized(ownerLock) { if (walkingRequest === request) walkingRequest = null }
            attempt.service?.let(::releaseUnknownLoadingService) ?: abandonEmpty()
            unknownRuntimeStatus = "추가 물체 인식 준비 실패: ${error.javaClass.simpleName}"
            throw error
        }
    }

    /** Cancel intent before native close can synchronously acknowledge while Activity is still foreground. */
    private fun clearWalkingIntent() { synchronized(ownerLock) { walkingRequest = null } }

    /** Called on the existing tuning executor; native release is not awaited on UI/GL. */
    fun prepareComposition(config: TwoModelRuntimeConfig, primary: TfliteAndroidFrameDetector,
        primaryCandidate: RuntimeCandidate, scope: RuntimeFeatureScope, manual: Boolean,
        deadlineMs: Long, current: () -> Boolean,
    ) {
        clearWalkingIntent()
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
            attempt.service = restored
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
                    if (error != null && current() && !unknownRuntimeClosing) {
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
            unknownCalibration = calibration
            if (!calibration.start() && unknownCalibration === calibration) unknownCalibration = null
            finishPreparation(attempt)
        }
    }

    fun handleUnknownRuntimeError(serviceEpoch: Long, error: Throwable) {
        val failedBinding: UnknownRuntimeBinding?
        val releaseAttempt = synchronized(ownerLock) {
            val failed = unknownRuntimeService?.takeIf { it.sessionEpoch == serviceEpoch } ?: return
            failedBinding = unknownRuntimeBinding
            activeWalkingService?.takeIf { it.service === failed }?.let {
                if (walkingRequest === it.request) walkingRequest = null
                activeWalkingService = null
            }
            unknownRuntimeService = null
            reserveRelease(failed)
        }
        try {
            invalidateObservations()
            if (failedBinding != null) deviceTuningExecutor.execute {
                profiles.markPending(failedBinding, DeviceTuningRecheckReason.EXECUTION_RECOVERY)
            }
            unknownRuntimeStatus = "추가 물체 인식 중지 · ${error.javaClass.simpleName}"
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

    /** Called on the same tuning owner before a primary comparison, preserving native close proof. */
    fun releaseBeforePrimaryCalibration(): Boolean {
        val releaseAttempt = synchronized(ownerLock) {
            walkingRequest = null
            if (preparation != null || release != null) return false
            activeWalkingService = null
            unknownRuntimeService?.also { unknownRuntimeService = null }?.let(::reserveRelease)
        }
        try { invalidateObservations() } finally { releaseAttempt?.let(::startRelease) }
        return releaseAttempt == null || awaitReleaseOnTuningOwner(releaseAttempt)
    }

    /** The original detector teardown only requests auxiliary close and does not await it. */
    fun closeActiveForDetector() {
        val releaseAttempt = synchronized(ownerLock) {
            walkingRequest = null
            activeWalkingService = null
            unknownRuntimeService?.also { unknownRuntimeService = null }?.let(::reserveRelease)
        }
        try { invalidateObservations() } finally { releaseAttempt?.let(::startRelease) }
    }

    private companion object { const val MAX_SOURCE_AGE_MS = 800L }
}
