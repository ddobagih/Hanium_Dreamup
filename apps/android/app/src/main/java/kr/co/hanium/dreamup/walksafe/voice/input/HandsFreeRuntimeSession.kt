package kr.co.hanium.dreamup.walksafe.voice.input

import java.io.File
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.voice.*

/** Owns model preparation, the attached controller and exact service/restart attempts. */
internal class HandsFreeRuntimeSession(
    private val environment: VoiceInputEnvironment,
    private val input: HandsFreeRuntimeInput,
    private val presentation: VoiceInputPresentation,
    private val applicationEffects: VoiceInputApplicationEffects,
    private val output: VoiceInputOutput,
    private val scheduler: VoiceInputScheduler,
    private val platform: VoiceInputPlatform,
    private val diagnostics: VoiceInputDiagnostics,
) {
    private var handsFreeVoiceController: HandsFreeVoiceController? = null
    @Volatile
    private var handsFreeVoiceModelDirectory: File? = null
    @Volatile
    private var handsFreeVoiceModelPreparationFailed = false
    private var handsFreeVoiceModelPreparing = false
    private var handsFreeVoiceServiceRequested = false
    private var handsFreeVoiceStartRequestId: Long? = null
    private val handsFreeVoiceStartRecovery = WalkVoiceStartRecovery()
    private var pendingHandsFreeVoiceStartFailure: WalkVoiceStartFailurePresentation? = null
    private var handsFreeVoiceRestartRunnable: Runnable? = null
    /** Consecutive hands-free transcriber failures since listening last started (FP-025 persistence). */
    private var handsFreeTranscriberFailureCount = 0
    private var handsFreeVoiceModelGeneration = 0L
    private var handsFreeVoiceDestroyed = false

    val modelDirectory: File? get() = handsFreeVoiceModelDirectory
    val modelPreparing: Boolean get() = handsFreeVoiceModelPreparing
    val modelPreparationFailed: Boolean get() = handsFreeVoiceModelPreparationFailed
    val destroyed: Boolean get() = handsFreeVoiceDestroyed

    fun clearTranscriberFailureHistory() { handsFreeTranscriberFailureCount = 0 }
    fun commandInputActive() = handsFreeVoiceController?.isCommandInputActive() == true
    fun dispatchingCommandId(): Long? = handsFreeVoiceController?.dispatchingCommandId()

    fun onBeforeForegroundReturn() {
        if (pendingHandsFreeVoiceStartFailure == null ||
            handsFreeVoiceStartRecovery.retriesOnNextForegroundReturn && !platform.inputHeldElsewhere()
        ) handsFreeVoiceStartRecovery.onForegroundReturn()
    }

    fun onStopped() {
        if (pendingHandsFreeVoiceStartFailure == null) handsFreeVoiceStartRecovery.onStopped()
    }

    fun foregroundVoiceModelDirectoryOrNull(
        requestPreparation: () -> Unit = ::prepareHandsFreeVoiceModel,
    ): File? {
        if (handsFreeVoiceDestroyed) return null
        handsFreeVoiceModelDirectory?.takeIf { it.isDirectory }?.let { return it }
        handsFreeVoiceModelDirectory = null
        if (!handsFreeVoiceModelPreparing && !handsFreeVoiceModelPreparationFailed) requestPreparation()
        return null
    }

    fun prepareHandsFreeVoiceModel() {
        if (handsFreeVoiceDestroyed || handsFreeVoiceModelDirectory != null || handsFreeVoiceModelPreparing) return
        if (!platform.modelAssetAvailable()) {
            handsFreeVoiceModelPreparationFailed = true
            presentation.navigationStatus("voice_hands_free=model_asset_missing")
            applicationEffects.continueDeviceCheck()
            return
        }
        handsFreeVoiceModelPreparationFailed = false
        handsFreeVoiceModelPreparing = true
        presentation.refreshControls()
        val generation = ++handsFreeVoiceModelGeneration
        try {
            platform.executeModelWork {
                val prepared = runCatching {
                    platform.prepareInstalledModel()
                }
                scheduler.runOnMain {
                    if (
                        handsFreeVoiceDestroyed ||
                        generation != handsFreeVoiceModelGeneration
                    ) return@runOnMain
                    handsFreeVoiceModelPreparing = false
                    prepared.onSuccess { directory ->
                        input.modelPrepared()
                        handsFreeVoiceModelPreparationFailed = false
                        handsFreeVoiceModelDirectory = directory
                        attachHandsFreeVoiceController(directory)
                        presentation.navigationStatus("voice_hands_free=model_ready")
                        applicationEffects.continueDeviceCheck()
                        applicationEffects.refreshCapabilityUi()
                        presentation.refreshControls()
                        maybeStartHandsFreeVoiceService()
                    }.onFailure {
                        handsFreeVoiceModelPreparationFailed = true
                        presentation.refreshControls()
                        presentation.navigationStatus("voice_hands_free=model_install_failed")
                        applicationEffects.continueDeviceCheck()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            handsFreeVoiceModelPreparing = false
            presentation.refreshControls()
            handsFreeVoiceModelPreparationFailed = true
            presentation.navigationStatus("voice_hands_free=model_install_rejected")
            applicationEffects.continueDeviceCheck()
        }
    }

    fun attachHandsFreeVoiceController(modelDirectory: File) {
        if (handsFreeVoiceController != null || handsFreeVoiceDestroyed) return
        val controller = platform.createHandsFree(modelDirectory, HandsFreeInputCallbacks(
            eligibility = {
                HandsFreeVoiceEligibility(
                    walkState = requireNotNull(environment.walkOrNull()).state,
                    microphoneGranted = environment.microphoneGranted(),
                    disclosureAccepted = environment.disclosureAccepted(),
                    modelReady = handsFreeVoiceModelDirectory?.isDirectory == true,
                )
            },
            speechActive = input::outputActive,
            command = input::recognizedCommand,
            status = presentation::navigationStatus,
            listeningStarted = {
                clearTranscriberFailureHistory()
                input.commandListeningStarted()
            },
            terminalFailure = ::handleHandsFreeVoiceTerminalFailure,
            sessionEnded = ::handleHandsFreeVoiceSessionEnded,
            recognitionBlocked = input::recognitionBlocked,
            workPending = input::commandWorkPending,
            commandSpeechProgress = input::commandSpeechProgress,
            commandSpeechRevision = input::commandSpeechRevision,
            lowConfidenceReprompt = input::lowConfidenceReprompt,
        ))
        handsFreeVoiceController = controller
        if (!platform.attachHandsFree(controller)) {
            handsFreeVoiceController = null
            controller.close()
            presentation.navigationStatus("voice_hands_free=application_provider_missing")
        }
        platform.setUserStopListener(::handleHandsFreeVoiceUserStop)
    }

    fun handleHandsFreeVoiceUserStop(stopped: WalkVoiceSessionController) {
        if (stopped !== handsFreeVoiceController || environment.walkOrNull() == null) return
        platform.recordUserStop(requireNotNull(environment.walkOrNull()).epoch.walkSessionId)
        input.clearCommandCapture()
        handsFreeVoiceServiceRequested = false
        clearHandsFreeVoiceStartRequest()
        presentation.navigationStatus("voice_hands_free=user_stopped")
        presentation.status(
            "호출어 음성 입력 중지",
            "알림에서 음성 입력을 껐습니다. 이번 보행이 끝날 때까지 호출어를 듣지 않습니다.",
        )
    }

    fun handleHandsFreeVoiceSessionEnded() {
        input.clearCommandCapture()
        handsFreeVoiceRestartRunnable?.let { pending ->
            if (scheduler.canScheduleRestart()) scheduler.removeRestart(pending)
        }
        handsFreeVoiceRestartRunnable = null
        handsFreeVoiceServiceRequested = false
        clearHandsFreeVoiceStartRequest(onSessionEnded = true)
        if (isCurrentHandsFreeVoiceController(handsFreeVoiceController)) {
            runCatching { platform.stopService() }
        }
    }

    fun handleHandsFreeVoiceTerminalFailure(
        failure: HandsFreeVoiceTerminalFailure,
    ) {
        input.clearCommandCapture()
        handsFreeVoiceRestartRunnable?.let { pending ->
            if (scheduler.canScheduleRestart()) scheduler.removeRestart(pending)
        }
        handsFreeVoiceRestartRunnable = null
        handsFreeVoiceServiceRequested = false
        clearHandsFreeVoiceStartRequest()
        if (isCurrentHandsFreeVoiceController(handsFreeVoiceController)) {
            runCatching { platform.stopService() }
        }
        when (failure) {
            is HandsFreeVoiceTerminalFailure.Transcriber -> {
                if (failure.error == VoskStreamingError.AUDIO_SILENCED || failure.error == VoskStreamingError.AUDIO_ROUTE_CHANGED) {
                    // S04-27/FP-025: the system silenced capture. Say so, never auto-restart into the same
                    // block, keep STT capability (the model is fine); retry after the user returns.
                    reportWalkVoiceStartFailure(WalkVoiceStartFailure.INPUT_SILENCED)
                    output.speakInteraction(if (failure.error == VoskStreamingError.AUDIO_ROUTE_CHANGED)
                        VoiceInputFailure.ROUTE_CHANGED.guidance else VoiceInputFailure.SILENCED.guidance)
                    return
                }
                // FP-025: only a persistent recognizer failure disables on-device STT (and so blocks walks);
                // a single transient audio/inference error restarts hands-free listening first.
                handsFreeTranscriberFailureCount += 1
                val persistentTranscriberFailure =
                    failure.error == VoskStreamingError.MODEL_UNAVAILABLE ||
                        handsFreeTranscriberFailureCount >= HANDS_FREE_TRANSCRIBER_PERSISTENT_FAILURES
                if (!persistentTranscriberFailure) {
                    presentation.navigationStatus("voice_hands_free=internal_restart_${failure.statusToken}")
                    scheduleHandsFreeVoiceRestart()
                    return
                }
                applicationEffects.limitPersistentHandsFreeRecognition()
                presentation.navigationStatus(
                    "voice_hands_free=transcriber_limited_${failure.statusToken}",
                )
            }
            HandsFreeVoiceTerminalFailure.CommandDispatchFailed,
            HandsFreeVoiceTerminalFailure.OutputTimeout,
            HandsFreeVoiceTerminalFailure.CommandResultTimeout,
            -> {
                presentation.navigationStatus(
                    "voice_hands_free=internal_restart_${failure.statusToken}",
                )
                if (failure == HandsFreeVoiceTerminalFailure.CommandResultTimeout) {
                    // S04-11: the command did not silently vanish; the restart waits for this notice.
                    output.speakInteraction("요청한 결과를 받지 못했습니다. 다시 말해 주세요.")
                }
                scheduleHandsFreeVoiceRestart()
            }
        }
    }

    fun maybeStartHandsFreeVoiceService() {
        if (input.refreshWakeAndHasProbe()) return
        if (handsFreeVoiceStartRecovery.blocksAutomaticRetry) return
        if (!environment.foreground() || environment.page().pendingVoiceCommand) return
        if (!environment.handsFreeFeatureEnabled()) {
            presentation.navigationStatus("voice_hands_free=device_feature_limited")
            return
        }
        if (
            environment.capabilityDecision()?.let { environment.capabilityAllows(it, WalkSafeStartupRequirement.MICROPHONE) } == false ||
            environment.capabilityDecision()?.let { environment.capabilityAllows(it, WalkSafeStartupRequirement.ON_DEVICE_STT) } == false
        ) {
            presentation.navigationStatus("voice_hands_free=runtime_feature_limited")
            return
        }
        if (!environment.disclosureAccepted()) {
            presentation.navigationStatus("voice_hands_free=disclosure_required")
            return
        }
        if (!environment.notificationGranted()) {
            presentation.navigationStatus("voice_hands_free=notification_permission_required")
            return
        }
        if (
            handsFreeVoiceDestroyed ||
            handsFreeVoiceServiceRequested ||
            handsFreeVoiceController == null ||
            handsFreeVoiceModelDirectory?.isDirectory != true ||
            input.otherInputBusy() ||
            !environment.runtimeActive()
        ) return
        // S04-30: a user's stop from the notification holds for the rest of this walk.
        if (platform.userStoppedWalk(requireNotNull(environment.walkOrNull()).epoch.walkSessionId)
        ) {
            presentation.navigationStatus("voice_hands_free=user_stopped")
            return
        }
        if (!environment.microphoneGranted()) {
            applicationEffects.permissionStateChanged("voice_hands_free_microphone_permission_missing")
            return
        }
        val controller = handsFreeVoiceController ?: return
        if (!platform.applicationAvailable()) return
        pendingHandsFreeVoiceStartFailure = null
        val requestId = platform.registerStart(controller, ::handleWalkVoiceStartFailure) ?: return
        handsFreeVoiceStartRequestId = requestId
        try {
            diagnostics.serviceInfo("service_start_requested")
            platform.startService(requestId)
            handsFreeVoiceServiceRequested = true
        } catch (error: SecurityException) {
            handsFreeVoiceServiceRequested = false
            diagnostics.serviceWarning("service_start_failed")
            platform.reportStartFailure(
                requestId,
                controller,
                WalkVoiceStartFailure.SECURITY,
            )
        } catch (error: IllegalStateException) {
            handsFreeVoiceServiceRequested = false
            diagnostics.serviceWarning("service_start_failed")
            platform.reportStartFailure(
                requestId,
                controller,
                WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
            )
        }
    }

    fun handleWalkVoiceStartFailure(
        failedId: Long,
        failedController: WalkVoiceSessionController,
        failure: WalkVoiceStartFailure,
    ) {
        if (
            handsFreeVoiceDestroyed ||
            handsFreeVoiceStartRequestId != failedId ||
            handsFreeVoiceController !== failedController
        ) return
        handsFreeVoiceStartRequestId = null
        handsFreeVoiceServiceRequested = false
        reportWalkVoiceStartFailure(failure)
    }

    fun reportWalkVoiceStartFailure(failure: WalkVoiceStartFailure) {
        val microphoneGranted = environment.microphoneGranted()
        val failurePresentation = handsFreeVoiceStartRecovery.recordFailure(
            failure,
            microphoneGranted,
            appInForeground = environment.foreground(),
        )
        stopHandsFreeVoiceService()
        pendingHandsFreeVoiceStartFailure = failurePresentation
        if (environment.foreground() && failure == WalkVoiceStartFailure.SECURITY && !microphoneGranted) {
            applicationEffects.permissionStateChanged("voice_hands_free_start_security_permission_missing")
        }
        showPendingHandsFreeVoiceStartFailure()
    }

    fun showPendingHandsFreeVoiceStartFailure() {
        val failurePresentation = pendingHandsFreeVoiceStartFailure ?: return
        if (!environment.foreground() || !presentation.canShowStatus()) return
        pendingHandsFreeVoiceStartFailure = null
        presentation.navigationStatus("voice_hands_free=${failurePresentation.statusToken}")
        presentation.status("호출어 음성 시작 실패", failurePresentation.guidance)
        // S04-27-F1: the first spoken notice came during the call; say the outcome again on return.
        handsFreeVoiceStartRecovery.spokenNoticeOnReturn(failurePresentation)?.let(output::speakInteraction)
    }

    fun clearHandsFreeVoiceStartRequest(onSessionEnded: Boolean = false) {
        val requestId = handsFreeVoiceStartRequestId ?: return
        val controller = handsFreeVoiceController
        if (onSessionEnded && controller != null &&
            platform.invalidateStartOnSessionEnd(requestId, controller) == false
        ) return
        handsFreeVoiceStartRequestId = null
        if (!onSessionEnded && controller != null) {
            platform.invalidateStart(requestId, controller)
        }
    }

    fun scheduleHandsFreeVoiceRestart() {
        if (!scheduler.canScheduleRestart() || handsFreeVoiceDestroyed) return
        handsFreeVoiceRestartRunnable?.let(scheduler::removeRestart)
        lateinit var restart: Runnable
        restart = Runnable {
            if (handsFreeVoiceRestartRunnable !== restart) return@Runnable
            handsFreeVoiceRestartRunnable = null
            maybeStartHandsFreeVoiceService()
        }
        handsFreeVoiceRestartRunnable = restart
        scheduler.postRestart(restart, HANDS_FREE_VOICE_RESTART_DELAY_MS)
    }

    fun stopHandsFreeVoiceService(): Boolean {
        input.clearCommandCapture()
        handsFreeVoiceRestartRunnable?.let { pending ->
            if (scheduler.canScheduleRestart()) scheduler.removeRestart(pending)
        }
        handsFreeVoiceRestartRunnable = null
        val controller = handsFreeVoiceController
        val serviceMayBeRunning =
            (handsFreeVoiceServiceRequested || controller != null) &&
                isCurrentHandsFreeVoiceController(controller)
        handsFreeVoiceServiceRequested = false
        clearHandsFreeVoiceStartRequest()
        var stopped = true
        try {
            controller?.stop()
        } catch (error: RuntimeException) {
            stopped = false
            diagnostics.serviceWarning("controller_stop_failed", error)
        }
        if (serviceMayBeRunning) {
            try {
                platform.stopService()
            } catch (error: RuntimeException) {
                stopped = false
                diagnostics.serviceWarning("service_stop_failed", error)
            }
        }
        return stopped
    }

    fun closeHandsFreeVoice() {
        if (handsFreeVoiceDestroyed) return
        // Retire before any callback-capable cleanup can complete the old worker.
        handsFreeVoiceModelGeneration += 1L
        handsFreeVoiceDestroyed = true
        input.runtimeRetired()
        stopHandsFreeVoiceService()
        val controller = handsFreeVoiceController
        handsFreeVoiceController = null
        if (controller != null) {
            platform.setUserStopListener(null)
            platform.detachHandsFree(controller)
            controller.close()
        }
        platform.shutdownModelWorker()
    }

    fun isCurrentHandsFreeVoiceController(controller: HandsFreeVoiceController?): Boolean =
        controller != null && platform.isAttached(controller)

    private companion object {
        const val HANDS_FREE_TRANSCRIBER_PERSISTENT_FAILURES = 2
        const val HANDS_FREE_VOICE_RESTART_DELAY_MS = 500L
    }
}
