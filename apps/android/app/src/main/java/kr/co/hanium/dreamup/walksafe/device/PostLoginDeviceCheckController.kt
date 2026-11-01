package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus

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
) {
    data class Config(
        val overallTimeoutMs: Long = 300_000L,
        val developmentQuickStartEnabled: Boolean = false,
        val developmentGateBypassed: Boolean = false,
    )

    @Volatile private var current = PostLoginDeviceCheckPolicy.initial()
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
    private var timeoutGeneration = 0L
    private var runtimeCallbackGeneration = 0L
    private var storedMetricRefresh: StoredDeviceCheckMetricRefresh? = null
    private var storedMetricRefreshPending = false
    private var storedMetricRetryAllowed = true
    private var resourceWaitThermal: Boolean? = null

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

    private fun publish(next: PostLoginDeviceCheckSnapshot) {
        if (current != next) {
            // Preserve the Activity's old setter: retire both output owners before publishing.
            effects.invalidateRouteResponseLease()
            effects.invalidateSpeechStorageLease()
        }
        current = next
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
    fun isRunningBindingCurrent(binding: PostLoginDeviceCheckBinding): Boolean =
        facts.actor().foreground && current.state == PostLoginDeviceCheckState.RUNNING && isSnapshotBindingLive(binding)

    /** Identifies the same successful attempt across reentrant output teardown. */
    fun isPassedSnapshotCurrent(expected: PostLoginDeviceCheckSnapshot): Boolean =
        current === expected && expected.passesFeatureGate && facts.actor().foreground &&
            expected.bindingOrNull?.let(::isSnapshotBindingLive) == true

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
            actor.sessionVerified && actor.sessionUsable && !actor.storageBlocked && !actor.deletionRecoveryOnly
    }

    fun revalidateCompletedPrerequisites(): Boolean {
        if (!current.passesFeatureGate) return false
        val restored = current.actorId?.let(facts::resultBinding)?.let(savedResults::restore)
        if (restored != null && restored.state == current.state && restored.disabledFeatures == current.disabledFeatures &&
            restored.cameraDependentChecksDeferred == cameraDependentChecksDeferred) return false
        cancel("saved_result_prerequisites_changed")
        publish(current.copy(state = PostLoginDeviceCheckState.NOT_RUN, failure = null, disabledFeatures = emptySet()))
        cameraDependentChecksDeferred = false
        effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(null, null, null))
        return true
    }

    fun bindSession(actorId: String?, sessionGeneration: Long?) {
        if (config.developmentQuickStartEnabled) return
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
        if (restoredSnapshot != previous) cancel("session_changed")
        publish(restoredSnapshot)
        if (restored != null) {
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
        return current.passesFeatureGate
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
            publish(current.copy(state = PostLoginDeviceCheckState.NOT_RUN, failure = null, disabledFeatures = emptySet()))
            effects.clearCameraCapabilityOverrides()
            effects.prepareDetectorRetry()
        }
        if (current.state in setOf(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, PostLoginDeviceCheckState.RUNNING)) return
        beginFromPrimaryAction()
    }

    fun beginFromPrimaryAction() {
        val binding = currentSessionBinding()
        if (!facts.actor().foreground || binding == null) {
            effects.showStatus("로그인 확인 필요", "현재 로그인 세션을 확인한 뒤 기기 점검을 다시 시작하세요.")
            return
        }
        if (current.state in setOf(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, PostLoginDeviceCheckState.RUNNING)) return
        if (!permissions.acknowledgeVoiceDisclosure()) {
            effects.showStatus("안전 고지 저장 실패", "안전 고지 확인을 저장하지 못했습니다. 저장공간을 확인한 뒤 다시 시도하세요.")
            return
        }
        beginFromUserAction(binding)
    }

    fun beginFromUserAction(expectedSession: PostLoginDeviceCheckBinding) {
        val processBinding = currentSessionBinding() ?: return
        if (!facts.actor().foreground || processBinding.actorId != expectedSession.actorId ||
            processBinding.sessionGeneration != expectedSession.sessionGeneration) return
        val started = PostLoginDeviceCheckPolicy.beginFromUserAction(current, processBinding.actorId, processBinding.sessionGeneration, true)
        publish(started)
        val binding = started.bindingOrNull ?: return
        backgroundFailurePending = false
        cancel("user_started_new_attempt")
        clearStoredMetricRefresh()
        probes.resetForAttempt(binding)
        effects.clearAttemptPresentation()
        effects.applyCapabilityOverrides(PostLoginDeviceCheckCapabilityOverrides(null, null, null))
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
        publish(PostLoginDeviceCheckPolicy.beginRunning(current, binding, facts.actor().foreground))
        if (!effects.recordPermissionObservation(binding)) {
            cancel("jit_transition_rejected")
            return
        }
        startRuntime(binding)
        effects.afterPermissionObservation(binding)
    }

    fun startRuntime(binding: PostLoginDeviceCheckBinding) {
        if (!isRunningBindingCurrent(binding) || runtimeDeadlineMs != null) return
        resourceWaitThermal = null
        probes.clearStoredBindingValidation()
        val callbackGeneration = ++runtimeCallbackGeneration
        // Synchronous capability callbacks may start the camera stage. Its single shared deadline
        // must exist before any source is allowed to publish readiness.
        armOverallTimeout(binding)
        probes.restartCapabilityAndResources(binding) {
            if (callbackGeneration != runtimeCallbackGeneration) return@restartCapabilityAndResources
            maybeContinue(binding)
            effects.refreshReadinessPresentation()
        }
        probes.prepareVoiceModelAndDetector()
        probes.startAutomaticProbes(binding)
        maybeContinue(binding)
    }

    private fun armOverallTimeout(binding: PostLoginDeviceCheckBinding) {
        clearOverallTimeout()
        val started = elapsedRealtimeMs().coerceAtLeast(0L)
        runtimeDeadlineMs = if (started > Long.MAX_VALUE - config.overallTimeoutMs) Long.MAX_VALUE
            else started + config.overallTimeoutMs
        val generation = ++timeoutGeneration
        overallTimeout = scheduler.schedule(config.overallTimeoutMs) {
            if (generation != timeoutGeneration || !isRunningBindingCurrent(binding)) return@schedule
            overallTimeout = null
            val previous = current
            publish(PostLoginDeviceCheckPolicy.timeout(previous, binding, facts.actor().foreground))
            if (current != previous) {
                cancel("check_timeout")
                effects.announceTerminal("기기 점검 시간이 초과되었습니다. 화면의 대기 항목을 확인하세요.")
                effects.showStatus("기기 점검 시간 초과", effects.failureMessage(current.failure))
                effects.refreshReadinessPresentation()
            }
        }
    }

    fun maybeContinue(expectedBinding: PostLoginDeviceCheckBinding? = null) {
        val binding = expectedBinding ?: current.bindingOrNull ?: return
        if (!isRunningBindingCurrent(binding)) return
        val hardware = facts.hardware()
        val observation = observation(hardware)
        resourceWaitThermal = if (hardware.resourceReadiness == WalkSessionReadinessStatus.PENDING) hardware.thermalThrottled == true else null
        evaluate(binding, observation)
        if (current.state != PostLoginDeviceCheckState.RUNNING) return
        if (listOf(observation.minimumAndroidVersion, observation.coreHardwareAndServices, observation.deviceResources,
                observation.detector, observation.koreanTextToSpeech).any { it == PostLoginDeviceCheckSignal.PENDING }) return
        if (observation.cameraPipeline == PostLoginDeviceCheckSignal.PENDING) {
            probes.startCamera(binding)
            return
        }
        if (observation.metricDepth == PostLoginMetricDepthState.PENDING && !probes.metricPreflightStarted) probes.startMetricPreflight(binding)
        evaluate(binding)
    }

    fun evaluate(expectedBinding: PostLoginDeviceCheckBinding? = null, observation: PostLoginDeviceCheckObservation = observation()) {
        val binding = expectedBinding ?: current.bindingOrNull ?: return
        if (!isSnapshotBindingLive(binding)) return
        val previous = current
        val next = PostLoginDeviceCheckPolicy.evaluate(previous, binding, facts.actor().foreground, observation)
        publish(next)
        if (next == previous) return
        when (next.state) {
            PostLoginDeviceCheckState.FULL, PostLoginDeviceCheckState.LIMITED -> completePass()
            PostLoginDeviceCheckState.FAIL -> {
                cancel("terminal_failure")
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
        val detectorUnavailable = !compositeActive && detector.loadAttempted && !detector.loadInFlight &&
            (!detector.configLoaded || !detector.available)
        val cameraPipeline = when {
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
                compositeActive -> PostLoginDeviceCheckSignal.PENDING
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

    private fun completePass() {
        val binding = current.bindingOrNull
        if (binding == null || currentSessionBinding() != binding) {
            failForSessionChange()
            return
        }
        val deferred = probes.signals.cameraPipeline != PostLoginDeviceCheckSignal.READY &&
            PostLoginDeviceCheckFeature.OBSTACLE_DETECTION !in current.disabledFeatures
        val resultBinding = facts.resultBinding(binding.actorId)
        if (resultBinding == null || !savedResults.save(current, resultBinding, deferred, probes.signals.metricDepth)) {
            publish(current.copy(state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.RESULT_SAVE_FAILED, disabledFeatures = emptySet()))
            cancel("result_save_failed")
            effects.showStatus("기기 점검 결과 저장 실패", "점검 결과를 저장하지 못했습니다. 저장공간을 확인한 뒤 다시 시도하세요.")
            effects.refreshReadinessPresentation()
            return
        }
        cameraDependentChecksDeferred = deferred
        clearOverallTimeout()
        probes.cancelFunctionalProbes()
        probes.stopCamera()
        when (effects.afterPassed(current)) {
            PostLoginDeviceCheckPassContinuation.SESSION_CHANGED -> failForSessionChange()
            PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED -> return
            PostLoginDeviceCheckPassContinuation.COMPLETED -> {
                effects.refreshReadinessPresentation()
                effects.scheduleCalibration()
            }
        }
    }

    fun failForSessionChange() {
        publish(current.copy(state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.SESSION_CHANGED))
        cancel("session_changed_before_pass")
        effects.announceTerminal("로그인 세션이 변경되어 기기 점검 결과를 적용하지 않았습니다.")
        effects.showStatus("로그인 다시 확인 필요", effects.failureMessage(PostLoginDeviceCheckFailure.SESSION_CHANGED))
        effects.refreshReadinessPresentation()
    }

    fun cancel(reason: String) {
        ++runtimeCallbackGeneration
        probes.cancelCalibration("device_check_$reason")
        clearOverallTimeout()
        probes.cancelFunctionalProbes()
        probes.cancelMetricPreflight("post_login_device_check_$reason")
        probes.closeCapabilityAndResetResources()
        probes.clearStoredBindingValidation()
        permissionRequestCode?.let(permissions::cancel)
        permissionRequestCode = null
        pendingPermissionBinding = null
        probes.stopCamera()
        if (reason != "session_changed") effects.noteCancellation(reason)
    }

    private fun clearOverallTimeout() {
        ++timeoutGeneration
        overallTimeout?.cancel()
        overallTimeout = null
        runtimeDeadlineMs = null
    }

    fun enteredBackground() {
        val previous = current
        val ownsDialog = permissionRequestCode?.let(permissions::ownsDialog) == true
        publish(PostLoginDeviceCheckPolicy.onBackground(previous, previous.bindingOrNull, ownsDialog))
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
        if (!savedResults.save(next, resultBinding, context.cameraDependentChecksDeferred, depth)) return false
        publish(next)
        cameraDependentChecksDeferred = context.cameraDependentChecksDeferred
        probes.restoreMetricDepth(depth)
        effects.metricCapabilityChanged(available)
        clearStoredMetricRefresh()
        return true
    }

    fun recoverKoreanSpeechPassedGate(): Boolean {
        if (!current.passesFeatureGate || PostLoginDeviceCheckFeature.VOICE_GUIDANCE !in current.disabledFeatures) return true
        val actorId = current.actorId ?: return false
        val remaining = current.disabledFeatures - PostLoginDeviceCheckFeature.VOICE_GUIDANCE
        val next = current.copy(state = if (remaining.isEmpty()) PostLoginDeviceCheckState.FULL else PostLoginDeviceCheckState.LIMITED, failure = null, disabledFeatures = remaining)
        val binding = facts.resultBinding(actorId) ?: return false
        if (!savedResults.save(next, binding, cameraDependentChecksDeferred, probes.signals.metricDepth)) return false
        publish(next)
        return true
    }

    private fun Boolean?.signal(): PostLoginDeviceCheckSignal = when (this) {
        true -> PostLoginDeviceCheckSignal.READY
        false -> PostLoginDeviceCheckSignal.UNAVAILABLE
        null -> PostLoginDeviceCheckSignal.PENDING
    }
}
