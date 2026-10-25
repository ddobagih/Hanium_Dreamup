package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*

internal data class PrimaryCalibrationRequest<D : AndroidFrameDetector>(
    val baseline: D, val config: TwoModelRuntimeConfig, val candidate: RuntimeCandidate,
    val scope: RuntimeFeatureScope, val manual: Boolean, val deadlineMs: Long,
    val authority: () -> Boolean,
)
internal data class PrimaryCalibrationLoad<D : AndroidFrameDetector>(
    val detector: D?, val modelKey: String?, val fallbackUsed: Boolean,
)
internal data class PrimaryCalibrationSessionSpec<D : AndroidFrameDetector>(
    val config: TwoModelRuntimeConfig, val baseline: D, val candidate: RuntimeCandidate,
    val binding: String, val scope: RuntimeFeatureScope, val budgetMs: Long,
    val baselinePreviouslyConfirmed: Boolean, val restoredQueue: RuntimeCandidateQueueSnapshot?,
)
internal data class PrimaryCalibrationSessionCallbacks(
    val isCurrent: () -> Boolean,
    val onSelectionReady: (RuntimeCalibrationSelection) -> Unit,
    val onClosed: (RuntimeCalibrationResult?) -> Unit,
)
internal interface PrimaryCalibrationPlatform<D : AndroidFrameDetector, S : Any> {
    fun createBinding(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope): String?
    fun load(config: TwoModelRuntimeConfig): PrimaryCalibrationLoad<D>?
    fun createSession(spec: PrimaryCalibrationSessionSpec<D>, callbacks: PrimaryCalibrationSessionCallbacks): S
    fun startSession(session: S)
    fun calibratableDetector(detector: AndroidFrameDetector): D?
    fun closeDetector(detector: D)
}
internal interface PrimaryCalibrationProfiles {
    fun load(binding: String): RuntimeTuningProfile?
    fun loadAttempt(binding: String): DeviceTuningAttempt?
    fun needsRecheck(binding: String, manual: Boolean): Boolean
    fun save(profile: RuntimeTuningProfile): Boolean
    fun markStarted(binding: String, reason: DeviceTuningRecheckReason = DeviceTuningRecheckReason.INCOMPLETE_COMPARISON,
        cursor: RuntimeCandidateQueueSnapshot? = null): Boolean
    fun markFinished(binding: String, pending: DeviceTuningRecheckReason?, cursor: RuntimeCandidateQueueSnapshot?): Boolean
}
internal class AndroidPrimaryCalibrationProfiles(private val store: () -> AndroidDeviceTuningProfileStore) : PrimaryCalibrationProfiles {
    override fun load(binding: String) = store().load(binding)
    override fun loadAttempt(binding: String) = store().loadAttempt(binding)
    override fun needsRecheck(binding: String, manual: Boolean) = store().needsRecheck(binding, manual)
    override fun save(profile: RuntimeTuningProfile) = store().save(profile)
    override fun markStarted(binding: String, reason: DeviceTuningRecheckReason, cursor: RuntimeCandidateQueueSnapshot?) =
        store().markAttemptStarted(binding, reason, cursor)
    override fun markFinished(binding: String, pending: DeviceTuningRecheckReason?, cursor: RuntimeCandidateQueueSnapshot?) =
        store().markAttemptFinished(binding, pending, cursor)
}
internal interface PrimaryAuxiliaryCalibration<D : AndroidFrameDetector> {
    fun isAbsent(): Boolean
    fun releaseBeforePrimary(): Boolean
    fun prepare(config: TwoModelRuntimeConfig, primary: D, candidate: RuntimeCandidate, scope: RuntimeFeatureScope,
        manual: Boolean, deadlineMs: Long, current: () -> Boolean)
}
internal interface PrimaryCalibrationEffects {
    fun onUi(effect: () -> Unit)
    fun cameraOwnerAvailable(): Boolean
    fun cancelHomeWakeListening()
    fun verifyRunningDetector(detector: AndroidFrameDetector, modelKey: String?, fallbackUsed: Boolean)
    fun resetRuntimeLoadMeasurements()
    fun measuredProfileStatus(profile: RuntimeTuningProfile)
    fun schedulePreparation(deadlineMs: Long)
    fun closeDetectorAfterCalibration()
    fun comparisonDeadlineReached()
    fun previousDetectorReleaseFailed(error: RuntimeException)
}

/** Primary comparison orchestration; SDK factories and existing auxiliary owner remain composed peers. */
internal class PrimaryRuntimeCalibrationCoordinator<D : AndroidFrameDetector, S : Any>(
    private val state: PrimaryRuntimeCalibrationOwner<S>,
    private val detector: PrimaryDetectorExecutionOwner,
    private val profiles: PrimaryCalibrationProfiles,
    private val platform: PrimaryCalibrationPlatform<D, S>,
    private val auxiliary: PrimaryAuxiliaryCalibration<D>,
    private val effects: PrimaryCalibrationEffects,
    private val elapsedRealtimeMs: () -> Long,
) {
    /** Same lane/current binding and commit-failure policy; exceptions retain the recorded marker. */
    fun recordPersistentDegradation(binding: String) {
        detector.tuningLane.execute {
            if (state.projection().selectionBinding == binding) {
                val saved = profiles.markFinished(binding, DeviceTuningRecheckReason.PERSISTENT_DEGRADATION, null)
                if (!saved) state.clearPendingDegradationRecording()
            }
        }
    }

    fun prepare(request: PrimaryCalibrationRequest<D>) {
        val generation = state.beginPreparation()
        val flowCurrent = { state.flowIsCurrent(generation, request.authority) }
        val current = { state.baselineIsCurrent(generation, request.baseline, request.deadlineMs, request.authority) }
        detector.tuningLane.execute {
            val binding = platform.createBinding(request.config, request.scope)
            val profile = binding?.let(profiles::load)
            val attempt = binding?.let(profiles::loadAttempt)
            var recheck = binding != null && profiles.needsRecheck(binding, request.manual)
            if (binding == null || !current()) {
                effects.onUi { state.preparationFinished() }
                return@execute
            }
            state.retainSelectionBinding(binding)
            if (state.beginStoredRestorationIfNeeded(request.manual, auxiliary::isAbsent, profile, request.candidate, binding)) {
                val stored = requireNotNull(profile)
                val unified = requireNotNull(request.config.unifiedWalksafe)
                val selectedConfig = request.config.copy(
                    unifiedWalksafe = unified.copy(runtime = unified.runtime.copy(
                        delegate = stored.candidate.backend.delegate, numThreads = stored.candidate.numThreads,
                        fallbackToCpu = false)), fallbackModelKey = null, customTactile = null, cocoGeneral = null)
                val load = runCatching { platform.load(selectedConfig) }.getOrNull()
                val replacement = load?.detector
                if (replacement != null && !load.fallbackUsed && load.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && current()) {
                    try {
                        detector.nativeLane.execute {
                            val swapped = detector.restoreMeasuredIfCurrent(replacement, current) {
                                effects.verifyRunningDetector(replacement, load.modelKey, load.fallbackUsed)
                                state.retainMeasuredProfile(stored)
                                detector.resetPacing()
                                effects.resetRuntimeLoadMeasurements()
                                effects.measuredProfileStatus(stored)
                            }
                            if (swapped) closePrevious(request.baseline) {
                                state.preparationFinished()
                                effects.schedulePreparation(request.deadlineMs)
                            } else {
                                state.storedRestorationDidNotAdopt()
                                closePrevious(replacement) { state.preparationFinished() }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        platform.closeDetector(replacement)
                        effects.onUi { state.preparationFinished() }
                    }
                    return@execute
                }
                replacement?.let(platform::closeDetector)
                if (!current()) {
                    state.storedRestorationDidNotAdopt()
                    effects.onUi { state.preparationFinished() }
                    return@execute
                }
                profiles.markStarted(binding, DeviceTuningRecheckReason.EXECUTION_RECOVERY)
                recheck = true
            } else if (profile?.candidate == request.candidate) state.retainAlreadyActiveProfile(profile)
            if (!recheck) {
                prepareAuxiliary(request, request.baseline, request.candidate, current)
                return@execute
            }
            if (!auxiliary.releaseBeforePrimary()) {
                effects.onUi { state.preparationFinished() }
                return@execute
            }
            if (!current() || !profiles.markStarted(binding, cursor = attempt?.cursor)) {
                effects.onUi { state.preparationFinished() }
                return@execute
            }
            effects.onUi {
                state.preparationFinished()
                if (!current() || !effects.cameraOwnerAvailable()) return@onUi
                effects.cancelHomeWakeListening()
                var ownedSession: S? = null
                val budget = ((request.deadlineMs - elapsedRealtimeMs()) / 3L).coerceIn(1L, 100_000L)
                val session = platform.createSession(
                    PrimaryCalibrationSessionSpec(request.config, request.baseline, request.candidate, binding,
                        request.scope, budget, profile?.candidate == request.candidate, attempt?.cursor),
                    PrimaryCalibrationSessionCallbacks(
                        isCurrent = { current() && state.sessionIsOwned(ownedSession) },
                        onSelectionReady = { selection -> adopt(selection, request.baseline, current) },
                        onClosed = { result ->
                            val finishOwner: () -> Unit = {
                                state.clearClosedSessionIfOwned(ownedSession)
                                detector.tuningLane.execute {
                                    val pending = when {
                                        result?.adopted == true -> null
                                        result?.status == RuntimeSelectionStatus.INCONCLUSIVE -> DeviceTuningRecheckReason.INCONCLUSIVE
                                        else -> DeviceTuningRecheckReason.INCOMPLETE_COMPARISON
                                    }
                                    profiles.markFinished(binding, pending, if (pending == null) null else result?.queueSnapshot)
                                }
                                if (state.consumeDeferredDetectorClose()) effects.closeDetectorAfterCalibration()
                                else if (flowCurrent()) {
                                    val selected = platform.calibratableDetector(detector.projection().detector)
                                    val candidate = state.projection().activeCandidate
                                    if (selected != null && candidate != null && elapsedRealtimeMs() < request.deadlineMs) {
                                        state.preparationContinues()
                                        detector.tuningLane.execute {
                                            val nextCurrent = { flowCurrent() && detector.projection().detector === selected &&
                                                elapsedRealtimeMs() < request.deadlineMs }
                                            prepareAuxiliary(request, selected, candidate, nextCurrent)
                                        }
                                    } else effects.comparisonDeadlineReached()
                                }
                            }
                            state.awaitOldDetectorReleaseOrFinish(finishOwner)
                        },
                    ),
                )
                ownedSession = session
                state.installSession(session)
                platform.startSession(session)
            }
        }
    }

    private fun prepareAuxiliary(request: PrimaryCalibrationRequest<D>, primary: D, candidate: RuntimeCandidate, current: () -> Boolean) =
        auxiliary.prepare(request.config, primary, candidate, request.scope, request.manual, request.deadlineMs, current)

    fun adopt(selection: RuntimeCalibrationSelection, baseline: D, current: () -> Boolean) {
        detector.tuningLane.execute {
            if (!current() || !profiles.save(selection.profile)) {
                selection.reject()
                return@execute
            }
            try {
                detector.nativeLane.execute {
                    val adopted = selection.tryAdopt { load ->
                        detector.adoptMeasuredIfCurrent(load.detector, current,
                            beforeInstall = { if (!selection.isBaseline) state.retainOldDetectorRelease() },
                            afterInstall = { state.retainMeasuredProfile(selection.profile) },
                            afterGeneration = {
                                effects.verifyRunningDetector(requireNotNull(load.detector), load.modelKey, load.fallbackUsed)
                                detector.resetPacing()
                                effects.resetRuntimeLoadMeasurements()
                                effects.measuredProfileStatus(selection.profile)
                            })
                    }
                    if (adopted && !selection.isBaseline) closePrevious(baseline, state::oldDetectorReleaseAcknowledged)
                }
            } catch (_: RejectedExecutionException) { selection.reject() }
        }
    }

    /** A native failure retains uncertainty; no finish callback or automatic retry acknowledges it. */
    fun closePrevious(owned: D, afterRelease: () -> Unit) {
        try {
            platform.closeDetector(owned)
            effects.onUi(afterRelease)
        } catch (error: RuntimeException) {
            state.oldDetectorReleaseFailed()
            effects.previousDetectorReleaseFailed(error)
        }
    }
}
