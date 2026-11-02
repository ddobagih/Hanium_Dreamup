package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Owns device-check attempts and saved-result application. Construction starts no probes. */
internal class PostLoginDeviceCheckController(
    private val facts: PostLoginDeviceCheckFactsReader,
    private val savedResults: PostLoginDeviceCheckSavedResults,
    private val probes: PostLoginDeviceCheckProbeSuite,
    private val permissions: PostLoginDeviceCheckPermissionPort,
    private val effects: PostLoginDeviceCheckEffects,
    private val scheduler: PostLoginDeviceCheckScheduler,
    private val config: Config,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dispatch: ((() -> Unit) -> Unit) = { it() },
    private val trace: DeviceCheckTraceSink = DeviceCheckTraceSink.NONE,
) {
    data class Config(
        val overallTimeoutMs: Long = 300_000L,
        val developmentQuickStartEnabled: Boolean = false,
        val developmentGateBypassed: Boolean = false,
        val savingTimeoutMs: Long = 15_000L,
    )

    @Volatile private var current = PostLoginDeviceCheckPolicy.initial()
    private var traceFlow = DeviceCheckTraceIds.next()
    private var traceBinding: PostLoginDeviceCheckBinding? = null

    private fun recordSnapshot(snapshot: PostLoginDeviceCheckSnapshot) {
        if (traceBinding != snapshot.bindingOrNull) {
            traceBinding = snapshot.bindingOrNull
            traceFlow = DeviceCheckTraceIds.next()
        }
        val stage = when {
            snapshot.state == PostLoginDeviceCheckState.SAVING -> DeviceCheckTraceStage.SAVING
            snapshot.passesFeatureGate -> DeviceCheckTraceStage.PUBLISH
            else -> DeviceCheckTraceStage.ATTEMPT
        }
        val outcome = when {
            snapshot.failure != null -> DeviceCheckTraceOutcome.FAILED
            snapshot.passesFeatureGate -> DeviceCheckTraceOutcome.SUCCEEDED
            else -> DeviceCheckTraceOutcome.WAITING
        }
        trace.emit(DeviceCheckTraceEvent(traceFlow, stage, outcome,
            if (snapshot.failure == null) DeviceCheckTraceReason.NONE else DeviceCheckTraceReason.FAILED,
            snapshot.sessionGeneration, snapshot.attemptGeneration, snapshot.state, snapshot.failure))
    }
    val snapshot: PostLoginDeviceCheckSnapshot get() = current
    var cameraDependentChecksDeferred: Boolean = false
        private set
    var permissionRequestCode: Int? = null
        private set
    private var pendingPermissionBinding: PostLoginDeviceCheckBinding? = null
    private var backgroundFailurePending = false
    private var overallTimeout: DeviceCheckScheduledTask? = null
    var runtimeDeadlineMs: Long? = null
        private set
    private var runtimeStartedAtMs: Long? = null
    private var permissionObservationPending: PostLoginDeviceCheckBinding? = null
    private var timeoutGeneration = 0L
    private var runtimeCallbackGeneration = 0L
    private var storedMetricRefresh: StoredDeviceCheckMetricRefresh? = null
    private var storedMetricRefreshPending = false
    private var storedMetricRetryAllowed = true
    private var resourceWaitThermal: Boolean? = null
    private enum class Decision { OPEN, SUCCESS, CANCELLED, TIMED_OUT }
    private class RuntimeDecision(val binding: PostLoginDeviceCheckBinding) {
        val value = AtomicReference(Decision.OPEN)
        val cancellationNotified = AtomicBoolean(false)
    }
    private class Publication(val saving: PostLoginDeviceCheckSnapshot,
        val completed: PostLoginDeviceCheckSnapshot, val deferred: Boolean,
        val startedAtMs: Long, val deadlineMs: Long) {
        var timeout: DeviceCheckScheduledTask? = null
    }
    private var runtimeDecision: RuntimeDecision? = null
    private var publication: Publication? = null
    private var pendingJournal: RuntimeDecision? = null
    private var pendingRuntimeLease: RuntimeDecision? = null
    private var storageReadyBinding: PostLoginDeviceCheckBinding? = null
    private class ResultUpdate(val expected: PostLoginDeviceCheckSnapshot,
        val next: PostLoginDeviceCheckSnapshot, val deferred: Boolean, val depth: PostLoginMetricDepthState,
        val metricAvailable: Boolean?, val onCompleted: (Boolean) -> Unit,
        val startedAtMs: Long, val deadlineMs: Long) {
        var timeout: DeviceCheckScheduledTask? = null
        var synchronousResult: Boolean? = null
    }
    private var resultUpdate: ResultUpdate? = null

    val permissionResultPending: Boolean get() = pendingPermissionBinding != null
    val storedMetricRefreshContext: StoredDeviceCheckMetricRefresh? get() = storedMetricRefresh
    val storedMetricRefreshIsPending: Boolean get() = storedMetricRefreshPending
    val signals: PostLoginDeviceCheckProbeSignals get() = probes.signals
    val storedBindingValidationPending: Boolean get() = probes.storedBindingValidationPending
    val resourceWaitNotice: String?
        get() = resourceWaitThermal?.let { thermal ->
            if (current.state == PostLoginDeviceCheckState.FAIL) {
                if (thermal) "발열 상태가 해소되지 않았습니다. 휴대전화가 식은 뒤 다시 점검하세요."
                else "배터리·저장공간·발열 상태를 확인하지 못했습니다. 다시 점검하세요."
            } else {
                if (thermal) "발열로 점검을 기다리고 있습니다. 휴대전화가 식은 뒤 계속합니다."
                else "배터리·저장공간·발열 상태를 확인하고 있습니다."
            }
        }

    private fun publish(next: PostLoginDeviceCheckSnapshot): Boolean {
        val previous = current
        if (current != next) {
            // Preserve the Activity's old setter: retire both output owners before publishing.
            effects.invalidateRouteResponseLease()
            if (current !== previous) return false
            effects.invalidateSpeechStorageLease()
            if (current !== previous) return false
        }
        current = next
        if (previous.state != next.state || previous.failure != next.failure || previous.bindingOrNull != next.bindingOrNull)
            recordSnapshot(next)
        return true
    }

    fun currentSessionBinding(): PostLoginDeviceCheckBinding? {
        val actor = facts.actor()
        val actorId = actor.actorId ?: return null
        val generation = actor.sessionGeneration ?: return null
        if (actor.verifiedActorId != actorId || !actor.sessionVerified || !actor.sessionUsable ||
            actor.storageBlocked || actor.deletionRecoveryOnly) return null
        return PostLoginDeviceCheckBinding(actorId, generation, current.attemptGeneration)
    }

    fun isSessionBindingLive(binding: PostLoginDeviceCheckBinding): Boolean = currentSessionBinding() == binding
    fun isSnapshotBindingLive(binding: PostLoginDeviceCheckBinding): Boolean =
        PostLoginDeviceCheckPolicy.isCurrent(current, binding) && isSessionBindingLive(binding)
    private fun isRunningAttemptLive(binding: PostLoginDeviceCheckBinding): Boolean =
        permissionObservationPending != binding && facts.actor().foreground &&
            current.state == PostLoginDeviceCheckState.RUNNING && isSnapshotBindingLive(binding)
    fun isRunningBindingCurrent(binding: PostLoginDeviceCheckBinding): Boolean =
        storageReadyBinding == binding && isRunningAttemptLive(binding)
    private fun runtimeReady(binding: PostLoginDeviceCheckBinding): Boolean =
        runCatching { savedResults.runtimeReady(binding) }.getOrDefault(false)

    /** Identifies the same successful attempt across reentrant output teardown. */
    fun isPassedSnapshotCurrent(expected: PostLoginDeviceCheckSnapshot): Boolean =
        current === expected && expected.passesFeatureGate && facts.actor().foreground &&
            expected.bindingOrNull?.let { isSnapshotBindingLive(it) && runtimeReady(it) } == true &&
            runCatching { savedResults.acceptsPublishedSnapshot(expected) }.getOrDefault(false)

    fun passesFeatureGate(): Boolean {
        val actor = facts.actor()
        if (config.developmentQuickStartEnabled) return actor.foreground && actor.quickStartSessionUsable
        if (!actor.foreground || (!config.developmentGateBypassed && !current.passesFeatureGate)) return false
        val actorId = actor.actorId ?: return false
        val generation = actor.sessionGeneration ?: return false
        val binding = current.bindingOrNull
        if (binding == null && config.developmentGateBypassed) {
            return actor.sessionVerified && actor.sessionUsable && !actor.storageBlocked && !actor.deletionRecoveryOnly
        }
        binding ?: return false
        return binding.actorId == actorId && binding.sessionGeneration == generation &&
            actor.sessionVerified && actor.sessionUsable && !actor.storageBlocked && !actor.deletionRecoveryOnly &&
            runtimeReady(binding) && runCatching { savedResults.acceptsPublishedSnapshot(current) }.getOrDefault(false)
    }

    fun revalidateCompletedPrerequisites(): Boolean {
        if (!current.passesFeatureGate) return false
        val expected = current
        val restored = current.actorId?.let(facts::resultBinding)?.let(savedResults::retainedResult)
        if (restored != null && restored.state == current.state && restored.disabledFeatures == current.disabledFeatures &&
            restored.cameraDependentChecksDeferred == cameraDependentChecksDeferred) return false
        cancel("saved_result_prerequisites_changed")
        if (current !== expected) return true
        if (!publish(current.copy(state = PostLoginDeviceCheckState.NOT_RUN, failure = null, disabledFeatures = emptySet()))) return true
        cameraDependentChecksDeferred = false
        effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(null, null, null))
        return true
    }

    fun bindSession(actorId: String?, sessionGeneration: Long?) {
        if (config.developmentQuickStartEnabled) return
        savedResults.bindSession(actorId, sessionGeneration)
        val previous = current
        val bound = PostLoginDeviceCheckPolicy.bindSession(previous, actorId, sessionGeneration)
        // Session generations may stay equal while installation/hardware prerequisites change.
        // Revalidate the durable identity before retaining a passed in-memory snapshot.
        if (bound === previous && bound.passesFeatureGate) revalidateCompletedPrerequisites()
        val next = if (bound === previous) current else bound
        val restored = if (actorId != null && sessionGeneration != null && next.state == PostLoginDeviceCheckState.NOT_RUN) {
            facts.resultBinding(actorId)?.let(savedResults::restore)
        } else null
        val restoredSnapshot = restored?.let {
            PostLoginDeviceCheckSnapshot(it.state, actorId, sessionGeneration, next.attemptGeneration.coerceAtLeast(1L), disabledFeatures = it.disabledFeatures)
        } ?: next
        if (restoredSnapshot != previous) {
            val expected = current
            cancel("session_changed")
            if (current !== expected) return
        }
        if (!publish(restoredSnapshot)) return
        if (restored != null) {
            if (!isPassedSnapshotCurrent(restoredSnapshot)) {
                rejectPairProof(restoredSnapshot)
                return
            }
            savedResults.didPublish(restoredSnapshot)
            if (current !== restoredSnapshot || !isPassedSnapshotCurrent(restoredSnapshot)) return
            cameraDependentChecksDeferred = restored.cameraDependentChecksDeferred
            probes.restoreMetricDepth(restored.metricDepthState)
            effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(
                metricDistance = if (restored.cameraDependentChecksDeferred) false else when (restored.metricDepthState) {
                    PostLoginMetricDepthState.SUPPORTED -> true
                    PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED -> false
                    else -> null
                },
                obstacleDetection = if (restored.cameraDependentChecksDeferred) false else null,
                koreanSpeech = if (PostLoginDeviceCheckFeature.VOICE_GUIDANCE in restored.disabledFeatures) false else null,
            ))
            if (restored.metricDepthProbePolicyCurrent) clearStoredMetricRefresh() else prepareStoredMetricRefresh(restoredSnapshot, restored.cameraDependentChecksDeferred)
        } else if (next != previous) {
            clearStoredMetricRefresh()
            cameraDependentChecksDeferred = false
            probes.restoreMetricDepth(PostLoginMetricDepthState.PENDING)
            effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(null, null, null))
        }
        maybeValidateStoredBinding()
        maybeStartStoredMetricRefresh()
        effects.refreshReadinessPresentation()
        if (current.passesFeatureGate) effects.scheduleCalibration()
    }

    fun restoreBoundResultIfPossible(): Boolean {
        if (current.state != PostLoginDeviceCheckState.NOT_RUN) return false
        val binding = currentSessionBinding() ?: return false
        if (facts.resultBinding(binding.actorId) == null) return false
        bindSession(binding.actorId, binding.sessionGeneration)
        return passesFeatureGate()
    }

    fun maybeValidateStoredBinding() {
        if (!probes.mayValidateStoredBinding() || probes.capabilityProbeStarted ||
            (current.state != PostLoginDeviceCheckState.NOT_RUN && !current.passesFeatureGate) ||
            currentSessionBinding() == null || !savedResults.candidatePresent()) return
        probes.startStoredBindingValidation {
            val bindingReady = currentSessionBinding()?.actorId?.let(facts::resultBinding) != null
            if (bindingReady) {
                probes.clearStoredBindingValidation()
                if (restoreBoundResultIfPossible()) effects.afterRestoredResult()
            }
            effects.refreshReadinessPresentation()
        }
    }

    fun handlePrimaryAction(deferredChecksCanBeRetried: Boolean) {
        if (current.passesFeatureGate) {
            if (!deferredChecksCanBeRetried) return
            if (!publish(current.copy(state = PostLoginDeviceCheckState.NOT_RUN, failure = null, disabledFeatures = emptySet()))) return
            effects.clearCameraCapabilityOverrides()
            effects.prepareDetectorRetry()
        }
        if (current.state in setOf(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, PostLoginDeviceCheckState.RUNNING, PostLoginDeviceCheckState.SAVING)) return
        beginFromPrimaryAction()
    }

    fun beginFromPrimaryAction() {
        if (!savedResults.mayBeginAttempt()) {
            effects.showStatus("이전 점검 결과 확인 중", "이전 저장 결과의 확인이 끝난 뒤 다시 점검할 수 있습니다.")
            return
        }
        val binding = currentSessionBinding()
        if (!facts.actor().foreground || binding == null) {
            effects.showStatus("로그인 확인 필요", "현재 로그인 세션을 확인한 뒤 기기 점검을 다시 시작하세요.")
            return
        }
        if (current.state in setOf(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, PostLoginDeviceCheckState.RUNNING, PostLoginDeviceCheckState.SAVING)) return
        if (!permissions.acknowledgeVoiceDisclosure()) {
            effects.showStatus("안전 고지 저장 실패", "안전 고지 확인을 저장하지 못했습니다. 저장공간을 확인한 뒤 다시 시도하세요.")
            return
        }
        beginFromUserAction(binding)
    }

    fun beginFromUserAction(expectedSession: PostLoginDeviceCheckBinding) {
        if (!savedResults.mayBeginAttempt()) return
        val processBinding = currentSessionBinding() ?: return
        if (!facts.actor().foreground || processBinding.actorId != expectedSession.actorId ||
            processBinding.sessionGeneration != expectedSession.sessionGeneration) return
        val started = PostLoginDeviceCheckPolicy.beginFromUserAction(current, processBinding.actorId, processBinding.sessionGeneration, true)
        if (started === current) return
        if (!publish(started)) return
        val binding = started.bindingOrNull ?: return
        backgroundFailurePending = false
        cancel("user_started_new_attempt")
        if (current !== started) return
        clearStoredMetricRefresh()
        probes.resetForAttempt(binding)
        if (current !== started) return
        effects.clearAttemptPresentation()
        if (current !== started) return
        effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(null, null, null))
        if (current !== started) return
        pendingPermissionBinding = null
        val missing = permissions.requestedPermissions()
        if (missing.isEmpty()) {
            completePermissionObservation()
            return
        }
        permissionRequestCode = runCatching { permissions.request(binding, missing) }.getOrNull()
        if (permissionRequestCode == null) completePermissionObservation()
        effects.refreshReadinessPresentation()
    }

    fun handlePermissionResult(binding: PostLoginDeviceCheckBinding) {
        if (!isSnapshotBindingLive(binding)) return
        permissionRequestCode = null
        if (!facts.actor().foreground) {
            pendingPermissionBinding = binding
            return
        }
        completePermissionObservation(binding)
    }

    fun completePermissionObservation(expectedBinding: PostLoginDeviceCheckBinding? = null) {
        val binding = expectedBinding ?: current.bindingOrNull ?: return
        if (current.state != PostLoginDeviceCheckState.REQUESTING_PERMISSIONS || !isSnapshotBindingLive(binding)) return
        if (permissionObservationPending == binding) return
        val observationGeneration = runtimeCallbackGeneration
        permissionObservationPending = binding
        if (!publish(PostLoginDeviceCheckPolicy.beginRunning(current, binding, facts.actor().foreground))) return
        val observedSnapshot = current
        val accepted = runCatching { effects.recordPermissionObservation(binding) }.getOrDefault(false)
        // A reentrant newer attempt can own its own observation while this callback unwinds.
        if (permissionObservationPending == binding) permissionObservationPending = null
        if (!accepted || observationGeneration != runtimeCallbackGeneration) {
            rejectOnboardingTransition(observedSnapshot)
            return
        }
        if (current !== observedSnapshot || !isRunningAttemptLive(binding)) return
        startRuntime(binding)
        if (isSnapshotBindingLive(binding) && (current.state == PostLoginDeviceCheckState.RUNNING || current.passesFeatureGate))
            effects.afterPermissionObservation(binding)
    }

    fun startRuntime(binding: PostLoginDeviceCheckBinding) {
        if (!isRunningAttemptLive(binding) || runtimeDeadlineMs != null) return
        resourceWaitThermal = null
        probes.clearStoredBindingValidation()
        val callbackGeneration = ++runtimeCallbackGeneration
        storageReadyBinding = null
        armOverallTimeout(binding)
        if (!isRunningAttemptLive(binding) || expireBeforeAcceptance(binding)) return
        val expected = current
        val resultBinding = facts.resultBinding(binding.actorId)
        if (current !== expected || !isRunningAttemptLive(binding) || expireBeforeAcceptance(binding)) return
        if (resultBinding == null) { publicationFailed(expected, false); return }
        val decision = runtimeDecision ?: return
        pendingJournal = decision
        val owner = WeakReference(this)
        val future = try { savedResults.beginAttemptAsync(binding, resultBinding,
            runtimeStartedAtMs ?: return, runtimeDeadlineMs ?: return) }
        catch (_: Throwable) { publicationFailed(expected, true); return }
        future.whenComplete { outcome, error -> owner.get()?.dispatch {
            owner.get()?.finishJournalStart(decision, callbackGeneration, outcome, error)
        } }
    }

    private fun finishJournalStart(decision: RuntimeDecision, callbackGeneration: Long,
        outcome: DeviceCheckAuthorityOutcome<Unit>?, error: Throwable?) {
        val binding = decision.binding
        if (pendingJournal !== decision || runtimeDecision !== decision ||
            callbackGeneration != runtimeCallbackGeneration || !isRunningAttemptLive(binding)) return
        if (expireBeforeAcceptance(binding)) return
        if (error != null || outcome !is DeviceCheckAuthorityOutcome.Committed) {
            publicationFailed(current, error != null || outcome is DeviceCheckAuthorityOutcome.Indeterminate)
            return
        }
        pendingJournal = null
        pendingRuntimeLease = decision
        val owner = WeakReference(this)
        val delivered = AtomicBoolean()
        fun completed(ready: Boolean) {
            if (!delivered.compareAndSet(false, true)) return
            owner.get()?.dispatch { owner.get()?.finishRuntimeLease(decision, callbackGeneration, ready) }
        }
        try { probes.prepareRuntimeLease(binding, ::completed) } catch (_: Throwable) { completed(false) }
    }

    private fun finishRuntimeLease(decision: RuntimeDecision, callbackGeneration: Long, ready: Boolean) {
        val binding = decision.binding
        if (pendingRuntimeLease !== decision || runtimeDecision !== decision || callbackGeneration != runtimeCallbackGeneration ||
            !isRunningAttemptLive(binding) || expireBeforeAcceptance(binding)) return
        pendingRuntimeLease = null
        if (!ready) { rejectPairProof(current); return }
        storageReadyBinding = binding
        startRuntimeWork(binding, callbackGeneration)
    }

    private fun startRuntimeWork(binding: PostLoginDeviceCheckBinding, callbackGeneration: Long) {
        fun mayContinueRuntimeWork(): Boolean = callbackGeneration == runtimeCallbackGeneration &&
            isRunningBindingCurrent(binding) && !expireBeforeAcceptance(binding)
        // Synchronous capability callbacks may start the camera stage. Its single shared deadline
        // must exist before any source is allowed to publish readiness.
        if (!mayContinueRuntimeWork()) return
        probes.restartCapabilityAndResources(binding) {
            if (!mayContinueRuntimeWork()) return@restartCapabilityAndResources
            maybeContinue(binding)
            effects.refreshReadinessPresentation()
        }
        if (!mayContinueRuntimeWork()) return
        probes.prepareVoiceModelAndDetector()
        if (!mayContinueRuntimeWork()) return
        probes.startAutomaticProbes(binding)
        if (!mayContinueRuntimeWork()) return
        maybeContinue(binding)
    }

    private fun armOverallTimeout(binding: PostLoginDeviceCheckBinding) {
        clearOverallTimeout()
        val started = elapsedRealtimeMs().coerceAtLeast(0L)
        runtimeStartedAtMs = started
        runtimeDeadlineMs = if (started > Long.MAX_VALUE - config.overallTimeoutMs) Long.MAX_VALUE
            else started + config.overallTimeoutMs
        runtimeDecision = RuntimeDecision(binding)
        val generation = ++timeoutGeneration
        overallTimeout = scheduler.schedule(config.overallTimeoutMs) {
            if (generation != timeoutGeneration || !isRunningAttemptLive(binding)) return@schedule
            overallTimeout = null
            terminateTimedOutAttempt(binding)
        }
    }

    /** Keep ownership predicates time-independent: the Controller, including an overdue timer,
     * must still be able to publish the terminal transition and cancel its resources. */
    private fun expireBeforeAcceptance(binding: PostLoginDeviceCheckBinding,
        completingSnapshot: PostLoginDeviceCheckSnapshot? = null): Boolean {
        val observed = current
        val deadline = runtimeDeadlineMs ?: return false
        val started = runtimeStartedAtMs
        if ((observed.state != PostLoginDeviceCheckState.RUNNING &&
                !(observed === completingSnapshot && observed.passesFeatureGate)) ||
            !isSnapshotBindingLive(binding) || !facts.actor().foreground) return false
        val now = elapsedRealtimeMs()
        if (started != null && now >= started && now < deadline) return false
        terminateTimedOutAttempt(binding, completingSnapshot)
        return true
    }

    private fun terminateTimedOutAttempt(binding: PostLoginDeviceCheckBinding,
        completingSnapshot: PostLoginDeviceCheckSnapshot? = null) {
        val previous = current
        val decision = runtimeDecision
        if (decision != null && decision.binding == binding &&
            !decision.value.compareAndSet(Decision.OPEN, Decision.TIMED_OUT)) return
        val next = if (previous === completingSnapshot && previous.passesFeatureGate && isSnapshotBindingLive(binding)) {
            previous.copy(state = PostLoginDeviceCheckState.FAIL,
                failure = PostLoginDeviceCheckFailure.CHECK_TIMEOUT, disabledFeatures = emptySet())
        } else PostLoginDeviceCheckPolicy.timeout(previous, binding, facts.actor().foreground)
        if (next == previous) return
        if (!publish(next)) return
        cancel("check_timeout")
        if (current !== next) return
        effects.announceTerminal("기기 점검 시간이 초과되었습니다. 화면의 대기 항목을 확인하세요.")
        effects.showStatus("기기 점검 시간 초과", effects.failureMessage(next.failure))
        effects.refreshReadinessPresentation()
    }

    fun maybeContinue(expectedBinding: PostLoginDeviceCheckBinding? = null) {
        val binding = expectedBinding ?: current.bindingOrNull ?: return
        if (!isRunningBindingCurrent(binding)) return
        if (expireBeforeAcceptance(binding)) return
        val hardware = facts.hardware()
        if (!isRunningBindingCurrent(binding) || expireBeforeAcceptance(binding)) return
        val observation = observation(hardware)
        if (!isRunningBindingCurrent(binding) || expireBeforeAcceptance(binding)) return
        resourceWaitThermal = if (hardware.resourceReadiness == WalkSessionReadinessStatus.PENDING) hardware.thermalThrottled == true else null
        evaluate(binding, observation)
        if (!isRunningBindingCurrent(binding)) return
        if (expireBeforeAcceptance(binding)) return
        if (listOf(observation.minimumAndroidVersion, observation.coreHardwareAndServices, observation.deviceResources,
                observation.koreanTextToSpeech).any { it == PostLoginDeviceCheckSignal.PENDING }) return
        if (observation.metricDepth == PostLoginMetricDepthState.PENDING) {
            if (!probes.metricPreflightStarted) probes.startMetricPreflight(binding)
            return
        }
        if (!probes.requiresJointRuntimeCheck && observation.detector == PostLoginDeviceCheckSignal.PENDING) return
        if (observation.cameraPipeline == PostLoginDeviceCheckSignal.PENDING) {
            probes.startCamera(binding)
            return
        }
        evaluate(binding)
    }

    fun evaluate(expectedBinding: PostLoginDeviceCheckBinding? = null, observation: PostLoginDeviceCheckObservation = observation()) {
        val binding = expectedBinding ?: current.bindingOrNull ?: return
        if (!isRunningBindingCurrent(binding)) return
        if (expireBeforeAcceptance(binding)) return
        val previous = current
        val next = PostLoginDeviceCheckPolicy.evaluate(previous, binding, facts.actor().foreground, observation)
        if (current !== previous || !isSnapshotBindingLive(binding)) return
        if (next.passesFeatureGate && expireBeforeAcceptance(binding)) return
        if (next.passesFeatureGate && next != previous) {
            completePass(next)
            return
        }
        if (!publish(next)) return
        if (next == previous) return
        when (next.state) {
            PostLoginDeviceCheckState.FAIL -> {
                cancel("terminal_failure")
                if (current !== next) return
                val detail = effects.failureMessage(next.failure)
                effects.announceTerminal("기기 점검에 실패했습니다. $detail")
                effects.showStatus("기기 점검 실패", detail)
                effects.refreshReadinessPresentation()
            }
            else -> Unit
        }
    }

    fun observation(hardware: PostLoginDeviceCheckHardwareFacts = facts.hardware()): PostLoginDeviceCheckObservation {
        val startup = hardware.startup
        val detector = hardware.detector
        val probeSignals = probes.signals
        val speechRecognitionAvailable = hardware.speechRecognitionOverride ?: startup.onDeviceSpeechRecognitionAvailable
        val koreanTextToSpeechAvailable = hardware.koreanSpeechOverride ?: startup.offlineKoreanTextToSpeechAvailable
        val coreSignals = listOf(startup.cameraAvailable, startup.gpsAvailable, startup.microphoneAvailable)
        // The sample check temporarily retires the production detector. Its result is delivered
        // on a queued callback; another readiness callback must not convert that gap to LIMITED.
        val compositeActive = probeSignals.compositeRuntimeActive
        val jointPending = probes.requiresJointRuntimeCheck && probeSignals.cameraPipeline == PostLoginDeviceCheckSignal.PENDING &&
            !probeSignals.compositeRuntimeFailed
        val detectorUnavailable = !jointPending && !compositeActive && detector.loadAttempted && !detector.loadInFlight &&
            (!detector.configLoaded || !detector.available)
        val cameraPipeline = when {
            probeSignals.compositeRuntimeFailed -> PostLoginDeviceCheckSignal.UNAVAILABLE
            jointPending -> PostLoginDeviceCheckSignal.PENDING
            compositeActive -> PostLoginDeviceCheckSignal.PENDING
            startup.cameraAvailable == false || !hardware.cameraPermission || detectorUnavailable ->
                PostLoginDeviceCheckSignal.UNAVAILABLE
            else -> probeSignals.cameraPipeline
        }
        val metricDepth = when {
            !hardware.cameraPermission || startup.cameraAvailable == false -> PostLoginMetricDepthState.UNKNOWN
            startup.metricDistanceAvailable == false -> PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED
            else -> probeSignals.metricDepth
        }
        val unsupported = buildSet {
            val obstacleUnavailable = startup.cameraAvailable == false || detectorUnavailable
            if (obstacleUnavailable) add(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)
            if (obstacleUnavailable || metricDepth in setOf(PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                    PostLoginMetricDepthState.UNKNOWN, PostLoginMetricDepthState.TIMED_OUT)) add(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE)
            if (startup.gpsAvailable == false) add(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)
            if (startup.microphoneAvailable == false || speechRecognitionAvailable == false ||
                probeSignals.wakePhrase == PostLoginDeviceCheckSignal.UNAVAILABLE || hardware.voiceModelPreparationFailed) add(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)
            if (koreanTextToSpeechAvailable == false) add(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)
        }
        return PostLoginDeviceCheckObservation(
            minimumAndroidVersion = startup.androidVersionSupported.signal(),
            requiredPermissions = if (hardware.missingRequiredPermissions.isEmpty()) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE,
            voiceDisclosure = hardware.voiceDisclosureAccepted.signal(),
            coreHardwareAndServices = when {
                coreSignals.any { it == false } -> PostLoginDeviceCheckSignal.UNAVAILABLE
                coreSignals.any { it == null } -> PostLoginDeviceCheckSignal.PENDING
                else -> PostLoginDeviceCheckSignal.READY
            },
            locationService = hardware.locationServiceEnabled.signal(),
            locationFix = probeSignals.locationFix,
            deviceResources = when (hardware.resourceReadiness) {
                WalkSessionReadinessStatus.READY, WalkSessionReadinessStatus.NOT_REQUIRED -> PostLoginDeviceCheckSignal.READY
                WalkSessionReadinessStatus.PENDING -> PostLoginDeviceCheckSignal.PENDING
                WalkSessionReadinessStatus.UNAVAILABLE -> PostLoginDeviceCheckSignal.UNAVAILABLE
            },
            detector = when {
                compositeActive || jointPending -> PostLoginDeviceCheckSignal.PENDING
                !detector.loadAttempted || detector.loadInFlight -> PostLoginDeviceCheckSignal.PENDING
                detector.configLoaded && detector.available -> detector.readiness
                else -> PostLoginDeviceCheckSignal.UNAVAILABLE
            },
            cameraPipeline = cameraPipeline,
            koreanTextToSpeech = koreanTextToSpeechAvailable.signal(),
            wakePhraseRecognition = if (!hardware.microphonePermission || startup.microphoneAvailable == false || hardware.voiceModelPreparationFailed) PostLoginDeviceCheckSignal.UNAVAILABLE else probeSignals.wakePhrase,
            metricDepth = metricDepth,
            unsupportedFeatures = unsupported,
            blockingFailure = when {
                startup.androidVersionSupported == false -> PostLoginDeviceCheckFailure.MINIMUM_ANDROID_VERSION
                hardware.resourceReadiness == WalkSessionReadinessStatus.UNAVAILABLE -> PostLoginDeviceCheckFailure.DEVICE_RESOURCE
                koreanTextToSpeechAvailable == false -> PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE
                cameraPipeline == PostLoginDeviceCheckSignal.UNAVAILABLE && startup.cameraAvailable != false &&
                    hardware.cameraPermission && (!detectorUnavailable || probeSignals.compositeRuntimeFailed) ->
                    PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE
                else -> null
            },
        )
    }

    private fun completePass(completedSnapshot: PostLoginDeviceCheckSnapshot) {
        val expected = current
        val binding = completedSnapshot.bindingOrNull ?: return
        if (expected.state != PostLoginDeviceCheckState.RUNNING || !isRunningBindingCurrent(binding)) return
        if (expireBeforeAcceptance(binding)) return
        val deferred = probes.signals.cameraPipeline != PostLoginDeviceCheckSignal.READY &&
            PostLoginDeviceCheckFeature.OBSTACLE_DETECTION !in completedSnapshot.disabledFeatures
        val depth = probes.signals.metricDepth
        val resultBinding = facts.resultBinding(binding.actorId)
        if (current !== expected) return
        if (!isSnapshotBindingLive(binding)) { failForSessionChange(); return }
        if (expireBeforeAcceptance(binding)) return
        if (resultBinding == null) { publicationFailed(expected, false); return }
        if (!runtimeReady(binding)) { rejectPairProof(expected); return }
        // Retiring old output can call back synchronously. Finish that work BEFORE the one
        // success decision, then recheck ownership and time; SAVING itself never opens a gate.
        effects.invalidateRouteResponseLease()
        effects.invalidateSpeechStorageLease()
        if (current !== expected || !isRunningBindingCurrent(binding)) return
        if (expireBeforeAcceptance(binding)) return
        if (!runtimeReady(binding)) { rejectPairProof(expected); return }
        val decision = runtimeDecision ?: return
        val acceptedAt = elapsedRealtimeMs()
        val startedAt = runtimeStartedAtMs ?: return
        val deadline = runtimeDeadlineMs ?: return
        if (acceptedAt < startedAt || acceptedAt >= deadline) { terminateTimedOutAttempt(binding); return }
        if (decision.binding != binding || !decision.value.compareAndSet(Decision.OPEN, Decision.SUCCESS)) return
        val saving = completedSnapshot.copy(state = PostLoginDeviceCheckState.SAVING)
        current = saving // Output owners were already retired above, before the success CAS.
        recordSnapshot(saving)
        // Detach the old timer without invoking external cancellation before request freezing.
        ++timeoutGeneration
        val oldOverallTimer = overallTimeout
        overallTimeout = null
        runtimeDeadlineMs = null
        runtimeStartedAtMs = null
        val savingStarted = elapsedRealtimeMs()
        val savingDeadline = if (savingStarted > Long.MAX_VALUE - config.savingTimeoutMs) Long.MAX_VALUE
            else savingStarted + config.savingTimeoutMs
        val pending = Publication(saving, completedSnapshot, deferred, savingStarted, savingDeadline)
        publication = pending
        val owner = WeakReference(this)
        val future = try {
            savedResults.saveAsync(completedSnapshot, resultBinding, deferred, depth, acceptedAt)
        } catch (_: Throwable) {
            oldOverallTimer?.cancel()
            publicationFailed(saving, true)
            return
        }
        oldOverallTimer?.cancel()
        // The immutable durable request is frozen immediately after the success decision.
        // UI refresh may reenter/pause; the already-decided write can then settle historically.
        if (current === saving) effects.refreshReadinessPresentation()
        future.whenComplete { result, error -> owner.get()?.dispatch {
            owner.get()?.finishPublication(pending, result, error)
        } }
        // Already-completed test/metadata adapters need no timer. A real disk operation returns
        // immediately and continues on its worker; even a rejected/early timer fails closed.
        if (publication !== pending) return
        val timer = runCatching { scheduler.schedule(config.savingTimeoutMs) {
            owner.get()?.dispatch { owner.get()?.publicationTimedOut(pending) }
        } }.getOrNull()
        if (publication === pending && timer != null) pending.timeout = timer else timer?.cancel()
        if (timer == null && publication === pending) publicationTimedOut(pending)
    }

    private fun publicationTimedOut(pending: Publication) {
        if (publication !== pending || current !== pending.saving) return
        publicationFailed(pending.saving, true)
    }

    private fun finishPublication(pending: Publication, result: DeviceCheckAuthorityOutcome<Unit>?, error: Throwable?) {
        if (publication !== pending || current !== pending.saving) return
        val now = elapsedRealtimeMs()
        if (now < pending.startedAtMs || now >= pending.deadlineMs) { publicationTimedOut(pending); return }
        val binding = pending.saving.bindingOrNull ?: return
        if (!facts.actor().foreground || !isSnapshotBindingLive(binding)) return
        if (error != null || result !is DeviceCheckAuthorityOutcome.Committed) {
            publicationFailed(pending.saving, error != null || result is DeviceCheckAuthorityOutcome.Indeterminate)
            return
        }
        pending.timeout?.cancel()
        publication = null
        if (!runtimeReady(binding)) { rejectPairProof(pending.saving); return }
        val completedSnapshot = pending.completed
        if (!publish(completedSnapshot)) return
        if (current !== completedSnapshot) return
        if (!isPassedSnapshotCurrent(completedSnapshot)) {
            rejectPairProof(completedSnapshot)
            return
        }
        savedResults.didPublish(completedSnapshot)
        if (current !== completedSnapshot || !isPassedSnapshotCurrent(completedSnapshot)) return
        cameraDependentChecksDeferred = pending.deferred
        probes.cancelFunctionalProbes()
        if (current !== completedSnapshot) return
        if (!isPassedSnapshotCurrent(completedSnapshot)) { rejectPairProof(completedSnapshot); return }
        probes.stopCamera()
        if (current !== completedSnapshot) return
        if (!isPassedSnapshotCurrent(completedSnapshot)) { rejectPairProof(completedSnapshot); return }
        val continuation = runCatching { effects.afterPassed(completedSnapshot) }
            .getOrDefault(PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED)
        if (current !== completedSnapshot) return
        when (continuation) {
            PostLoginDeviceCheckPassContinuation.SESSION_CHANGED -> failForSessionChange()
            PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED -> rejectOnboardingTransition(completedSnapshot)
            PostLoginDeviceCheckPassContinuation.COMPLETED -> {
                effects.refreshReadinessPresentation()
                effects.scheduleCalibration()
            }
        }
    }

    private fun rejectPairProof(expected: PostLoginDeviceCheckSnapshot) {
        if (current !== expected) return
        val failed = expected.copy(state = PostLoginDeviceCheckState.FAIL,
            failure = PostLoginDeviceCheckFailure.DETECTOR_UNAVAILABLE, disabledFeatures = emptySet())
        if (!publish(failed)) return
        cancel("actual_pair_unavailable")
        if (current !== failed) return
        effects.showStatus("인식 모델 확인 필요", "현재 준비된 두 인식 모델을 확인하지 못했습니다. 다시 점검해 주세요.")
        effects.refreshReadinessPresentation()
    }

    /** A normal ownership handoff temporarily closes the gate; a failed handoff ends that state. */
    fun reportRuntimeLost(binding: PostLoginDeviceCheckBinding) {
        val expected = current
        if (expected.bindingOrNull != binding || !expected.passesFeatureGate) return
        val failed = expected.copy(state = PostLoginDeviceCheckState.FAIL,
            failure = PostLoginDeviceCheckFailure.DETECTOR_UNAVAILABLE, disabledFeatures = emptySet())
        if (!publish(failed)) return
        cancel("actual_pair_lost")
        if (current !== failed) return
        if (facts.actor().foreground) effects.showStatus("인식 모델 확인 필요",
            "인식 작업 종료를 확인한 뒤 기기 점검을 다시 진행해 주세요.")
        effects.refreshReadinessPresentation()
    }

    private fun publicationFailed(expected: PostLoginDeviceCheckSnapshot, indeterminate: Boolean) {
        if (current !== expected) return
        if (indeterminate) expected.bindingOrNull?.let(savedResults::publicationUnconfirmed)
        if (current !== expected) return
        val failed = expected.copy(state = PostLoginDeviceCheckState.FAIL,
            failure = if (indeterminate) PostLoginDeviceCheckFailure.RESULT_SAVE_UNCONFIRMED
                else PostLoginDeviceCheckFailure.RESULT_SAVE_FAILED, disabledFeatures = emptySet())
        if (!publish(failed)) return
        cancel("result_publication_failed")
        if (current !== failed) return
        effects.showStatus(if (indeterminate) "기기 점검 결과 저장 확인 필요" else "기기 점검 결과 저장 실패",
            if (indeterminate) "저장 결과를 확인하고 있습니다. 확인이 끝나기 전에는 점검 결과를 적용하지 않습니다."
            else "점검 결과를 저장하지 못했습니다. 저장공간을 확인한 뒤 다시 시도하세요.")
        effects.refreshReadinessPresentation()
    }

    private fun rejectOnboardingTransition(expectedSnapshot: PostLoginDeviceCheckSnapshot) {
        val binding = expectedSnapshot.bindingOrNull ?: return
        if (current !== expectedSnapshot || !isSnapshotBindingLive(binding)) return
        val failed = expectedSnapshot.copy(state = PostLoginDeviceCheckState.FAIL,
            failure = PostLoginDeviceCheckFailure.ONBOARDING_TRANSITION_REJECTED, disabledFeatures = emptySet())
        if (!publish(failed)) return
        cancel("onboarding_transition_rejected")
        if (current !== failed) return
        val detail = effects.failureMessage(PostLoginDeviceCheckFailure.ONBOARDING_TRANSITION_REJECTED)
        effects.announceTerminal("기기 점검이 중지되었습니다. $detail")
        effects.showStatus("기기 점검 단계 확인 필요", detail)
        effects.refreshReadinessPresentation()
    }

    fun failForSessionChange() {
        val failed = current.copy(state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.SESSION_CHANGED)
        if (!publish(failed)) return
        cancel("session_changed_before_pass")
        if (current !== failed) return
        effects.announceTerminal("로그인 세션이 변경되어 기기 점검 결과를 적용하지 않았습니다.")
        effects.showStatus("로그인 다시 확인 필요", effects.failureMessage(PostLoginDeviceCheckFailure.SESSION_CHANGED))
        effects.refreshReadinessPresentation()
    }

    fun cancel(reason: String) {
        val generation = ++runtimeCallbackGeneration
        val decision = runtimeDecision
        decision?.value?.compareAndSet(Decision.OPEN, Decision.CANCELLED)
        val oldPublication = publication
        publication = null
        val oldUpdate = resultUpdate
        resultUpdate = null
        pendingJournal = null
        pendingRuntimeLease = null
        storageReadyBinding = null
        val oldPermission = permissionRequestCode
        permissionRequestCode = null
        pendingPermissionBinding = null
        oldPublication?.timeout?.cancel()
        if (generation != runtimeCallbackGeneration) return
        oldUpdate?.timeout?.cancel()
        if (generation != runtimeCallbackGeneration) return
        if (decision != null && decision.value.get() != Decision.SUCCESS &&
            decision.cancellationNotified.compareAndSet(false, true)) {
            savedResults.attemptCancelled(decision.binding, reason)
            if (generation != runtimeCallbackGeneration) return
        }
        probes.cancelCalibration("device_check_$reason")
        if (generation != runtimeCallbackGeneration) return
        clearOverallTimeout()
        if (generation != runtimeCallbackGeneration) return
        probes.cancelFunctionalProbes()
        if (generation != runtimeCallbackGeneration) return
        probes.cancelMetricPreflight("post_login_device_check_$reason")
        if (generation != runtimeCallbackGeneration) return
        probes.closeCapabilityAndResetResources()
        if (generation != runtimeCallbackGeneration) return
        probes.clearStoredBindingValidation()
        if (generation != runtimeCallbackGeneration) return
        oldPermission?.let(permissions::cancel)
        if (generation != runtimeCallbackGeneration) return
        probes.stopCamera()
        if (generation != runtimeCallbackGeneration) return
        if (reason != "session_changed") effects.noteCancellation(reason)
    }

    private fun clearOverallTimeout() {
        ++timeoutGeneration
        val oldTimeout = overallTimeout
        overallTimeout = null
        runtimeDeadlineMs = null
        runtimeStartedAtMs = null
        oldTimeout?.cancel()
    }

    fun enteredBackground() {
        // A capability update is already a durable request, but has no authority to publish to
        // a background screen. Detach only its UI wait; the authority still settles the write.
        val oldUpdate = resultUpdate
        resultUpdate = null
        oldUpdate?.timeout?.cancel()
        val previous = current
        val ownsDialog = permissionRequestCode?.let(permissions::ownsDialog) == true
        if (!publish(PostLoginDeviceCheckPolicy.onBackground(previous, previous.bindingOrNull, ownsDialog))) return
        if (current != previous) {
            backgroundFailurePending = current.failure == PostLoginDeviceCheckFailure.BACKGROUNDED
            cancel("app_paused")
        }
    }

    fun announceDeferredBackgroundFailure() {
        if (!backgroundFailurePending || current.state != PostLoginDeviceCheckState.FAIL || current.failure != PostLoginDeviceCheckFailure.BACKGROUNDED) return
        backgroundFailurePending = false
        effects.announceTerminal("기기 점검이 중지되었습니다. " + effects.failureMessage(PostLoginDeviceCheckFailure.BACKGROUNDED))
    }

    fun resumeDeferredPermissionObservation() {
        val binding = pendingPermissionBinding ?: return
        pendingPermissionBinding = null
        completePermissionObservation(binding)
    }

    fun prepareStoredMetricRefresh(snapshot: PostLoginDeviceCheckSnapshot, deferred: Boolean) {
        val binding = snapshot.bindingOrNull ?: return
        if (!snapshot.passesFeatureGate) return
        storedMetricRefresh = StoredDeviceCheckMetricRefresh(binding, snapshot.state, snapshot.disabledFeatures.toSet(), deferred)
        storedMetricRefreshPending = true
        storedMetricRetryAllowed = true
    }

    fun clearStoredMetricRefresh() {
        storedMetricRefresh = null
        storedMetricRefreshPending = false
        storedMetricRetryAllowed = false
    }

    fun allowStoredMetricRetry() { storedMetricRetryAllowed = true }

    fun isStoredMetricRefreshCurrent(context: StoredDeviceCheckMetricRefresh): Boolean {
        val binding = currentSessionBinding() ?: return false
        return storedMetricRefreshPending && storedMetricRefresh == context && current.passesFeatureGate &&
            current.bindingOrNull == context.binding && current.state == context.state &&
            current.disabledFeatures == context.disabledFeatures && cameraDependentChecksDeferred == context.cameraDependentChecksDeferred &&
            binding.actorId == context.binding.actorId && binding.sessionGeneration == context.binding.sessionGeneration
    }

    fun maybeStartStoredMetricRefresh() {
        val context = storedMetricRefresh ?: return
        if (!storedMetricRefreshPending || !storedMetricRetryAllowed || !facts.actor().foreground ||
            !isStoredMetricRefreshCurrent(context) || !probes.mayStartStoredMetricRefresh(context.binding)) return
        storedMetricRetryAllowed = false
        probes.startStoredMetricRefresh(context.binding)
    }

    fun completeStoredMetricRefresh(supported: Boolean?, binding: PostLoginDeviceCheckBinding): Boolean {
        val context = storedMetricRefresh ?: return false
        if (context.binding != binding || !isStoredMetricRefreshCurrent(context)) return false
        val available = supported ?: return false
        val depth = if (available) PostLoginMetricDepthState.SUPPORTED else PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED
        val disabled = if (available) context.disabledFeatures - PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE
            else context.disabledFeatures + PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE
        val next = current.copy(state = if (disabled.isEmpty()) PostLoginDeviceCheckState.FULL else PostLoginDeviceCheckState.LIMITED, disabledFeatures = disabled)
        val resultBinding = facts.resultBinding(binding.actorId) ?: return false
        return updatePassedResult(next, resultBinding, context.cameraDependentChecksDeferred, depth, available) {}
    }

    /** True means accepted (or no update needed); capabilities change only after durable ACK. */
    fun recoverKoreanSpeechPassedGate(onCompleted: (Boolean) -> Unit = {}): Boolean {
        if (!current.passesFeatureGate || PostLoginDeviceCheckFeature.VOICE_GUIDANCE !in current.disabledFeatures) {
            onCompleted(true)
            return true
        }
        val actorId = current.actorId ?: return false
        val remaining = current.disabledFeatures - PostLoginDeviceCheckFeature.VOICE_GUIDANCE
        val next = current.copy(state = if (remaining.isEmpty()) PostLoginDeviceCheckState.FULL else PostLoginDeviceCheckState.LIMITED,
            failure = null, disabledFeatures = remaining)
        val binding = facts.resultBinding(actorId) ?: return false
        return updatePassedResult(next, binding, cameraDependentChecksDeferred, probes.signals.metricDepth, null, onCompleted)
    }

    private fun updatePassedResult(next: PostLoginDeviceCheckSnapshot, resultBinding: PostLoginDeviceCheckResultBinding,
        deferred: Boolean, depth: PostLoginMetricDepthState, metricAvailable: Boolean?, onCompleted: (Boolean) -> Unit): Boolean {
        val expected = current
        val binding = expected.bindingOrNull ?: return false
        if (resultUpdate != null || publication != null || !expected.passesFeatureGate || !facts.actor().foreground ||
            !isSnapshotBindingLive(binding) || !runtimeReady(binding)) return false
        val started = elapsedRealtimeMs()
        if (started < 0L) return false
        val deadline = if (started > Long.MAX_VALUE - config.savingTimeoutMs) Long.MAX_VALUE else started + config.savingTimeoutMs
        val pending = ResultUpdate(expected, next, deferred, depth, metricAvailable, onCompleted, started, deadline)
        resultUpdate = pending
        val owner = WeakReference(this)
        val future = try { savedResults.updateAsync(expected, next, resultBinding, deferred, depth) }
        catch (_: Throwable) { publicationFailed(expected, true); return false }
        future.whenComplete { outcome, error -> owner.get()?.dispatch {
            owner.get()?.finishResultUpdate(pending, outcome, error)
        } }
        if (resultUpdate === pending) {
            val timer = runCatching { scheduler.schedule(config.savingTimeoutMs) {
                owner.get()?.dispatch { owner.get()?.resultUpdateTimedOut(pending) }
            } }.getOrNull()
            if (resultUpdate === pending && timer != null) pending.timeout = timer else timer?.cancel()
            if (timer == null && resultUpdate === pending) resultUpdateTimedOut(pending)
        }
        return pending.synchronousResult ?: (resultUpdate === pending)
    }

    private fun resultUpdateTimedOut(pending: ResultUpdate) {
        if (resultUpdate !== pending || current !== pending.expected) return
        publicationFailed(pending.expected, true)
    }

    private fun finishResultUpdate(pending: ResultUpdate, outcome: DeviceCheckAuthorityOutcome<Unit>?, error: Throwable?) {
        if (resultUpdate !== pending || current !== pending.expected) return
        val binding = pending.expected.bindingOrNull ?: return
        if (!facts.actor().foreground || !isSnapshotBindingLive(binding)) return
        val now = elapsedRealtimeMs()
        if (now < pending.startedAtMs || now >= pending.deadlineMs || error != null ||
            outcome is DeviceCheckAuthorityOutcome.Indeterminate) {
            publicationFailed(pending.expected, true)
            return
        }
        val timer = pending.timeout
        resultUpdate = null
        timer?.cancel()
        if (current !== pending.expected) return
        if (outcome !is DeviceCheckAuthorityOutcome.Committed) {
            pending.synchronousResult = false
            runCatching { pending.onCompleted(false) }
            return
        }
        if (!runtimeReady(binding)) { rejectPairProof(pending.expected); return }
        if (!publish(pending.next)) return
        if (current !== pending.next) return
        if (!isPassedSnapshotCurrent(pending.next)) { rejectPairProof(pending.next); return }
        savedResults.didPublish(pending.next)
        if (current !== pending.next || !isPassedSnapshotCurrent(pending.next)) return
        cameraDependentChecksDeferred = pending.deferred
        pending.metricAvailable?.let { supported ->
            probes.restoreMetricDepth(pending.depth)
            if (current !== pending.next) return
            effects.metricCapabilityChanged(supported)
            if (current !== pending.next) return
            clearStoredMetricRefresh()
        }
        pending.synchronousResult = true
        runCatching { pending.onCompleted(true) }
        if (current === pending.next) effects.refreshReadinessPresentation()
    }

    private fun Boolean?.signal(): PostLoginDeviceCheckSignal = when (this) {
        true -> PostLoginDeviceCheckSignal.READY
        false -> PostLoginDeviceCheckSignal.UNAVAILABLE
        null -> PostLoginDeviceCheckSignal.PENDING
    }
}
