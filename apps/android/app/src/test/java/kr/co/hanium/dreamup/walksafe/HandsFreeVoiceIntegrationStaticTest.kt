package kr.co.hanium.dreamup.walksafe

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceIntegrationStaticTest {
    private val mainActivity = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val input = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
    private val runtime = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt").readText()
    private fun runtimeMember(name: String) = MainActivityRefactorSource.member(runtime, name)

    @Test
    fun modelPreparationStartsAfterPrivacyReadyAndExplicitRequestsCanRetry() {
        val startupController = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt",
        ).readText()
        val privacyReady = ReportStaticSourceInspector.functionBlock(startupController, "fun completeReadyIfForeground")
        val inspection = MainActivityRefactorSource.member(startupController, "startInspection")
        assertTrue(inspection.contains("pendingPrivacyStartupReadyAction = PendingReady(generation, onReady)"))
        assertTrue(mainActivity.contains("privacyStartupInspectionController.completeReadyIfForeground()"))
        assertTrue(mainActivity.contains("override fun prepareHandsFreeVoiceModel() { this@MainActivity.prepareHandsFreeVoiceModel() }"))
        assertTrue(privacyReady.contains("privacyStartupInspectionDestroyed || privacyStartupInspectionComplete || !isForeground()"))
        assertTrue(privacyReady.contains("val pending = pendingPrivacyStartupReadyAction ?: return"))
        val readyGuard = privacyReady.substringAfter("pending.action()").substringBefore("actions.prepareHandsFreeVoiceModel()")
        assertTrue(readyGuard.contains("if (!isCurrent(pending.generation))"))
        assertTrue(readyGuard.contains("privacyStartupInspectionComplete = false"))
        assertTrue(readyGuard.contains("actions.closeReadyResourcesOnUiThread()"))
        assertTrue(readyGuard.contains("scheduleResourcesClose()"))
        assertTrue(readyGuard.contains("return"))
        val preparation = runtimeMember("prepareHandsFreeVoiceModel")
        val buttonRecognition = MainActivityRefactorSource.function(mainActivity, "private fun startVoiceCommandRecognition")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                privacyReady,
                "privacyStartupInspectionComplete = true",
                "pending.action()",
                "prepareHandsFreeVoiceModel()",
            ),
        )
        assertTrue(
            preparation.contains(
                "handsFreeVoiceDestroyed || handsFreeVoiceModelDirectory != null || handsFreeVoiceModelPreparing",
            ),
        )
        val sdk = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
        assertTrue(preparation.contains("platform.prepareInstalledModel()"))
        assertTrue(MainActivityRefactorSource.member(sdk, "prepareInstalledModel").contains("BundledVoskModelInstaller.installedModelOrNull(context)"))
        assertTrue(MainActivityRefactorSource.function(mainActivity, "private fun createVoiceInputController(").contains("val platform = AndroidVoiceInputPlatform(this)"))
        assertTrue(preparation.contains("generation != handsFreeVoiceModelGeneration"))
        assertTrue(preparation.contains("input.modelPrepared()"))
        assertTrue(MainActivityRefactorSource.member(input.replace("\n            override fun ", "\n    override fun "), "modelPrepared").contains("oneShotSpeechRecognitionLimited = false"))
        assertTrue(
            buttonRecognition.contains(
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
            ),
        )
        assertTrue(buttonRecognition.contains("environment.homeFeatureContextAvailable()"))
        assertTrue(
            buttonRecognition.contains(
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                buttonRecognition,
                "if (handsFreeRuntime.modelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
                "stopHandsFreeVoiceService()",
                "platform.createRecognizer { handsFreeRuntime.modelDirectory }",
                "preferPlatform = preferPlatform",
                "isRequestCurrent = {",
            ),
        )
    }

    @Test
    fun foregroundServiceFollowsTheActiveWalkLifecycle() {
        val runtimeEligibility = MainActivityRefactorSource.function(mainActivity, "private fun isWalkSessionRuntimeActive")
        val activate = MainActivityRefactorSource.function(mainActivity, "private fun activateWalkSessionRuntime")
        val startAfterCameraRelease =
            MainActivityRefactorSource.function(mainActivity, "private fun startWalkSessionRuntimeAfterCameraRelease")
        val start = runtimeMember("maybeStartHandsFreeVoiceService")
        val service = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkVoiceForegroundService.kt",
        ).readText()
        val serviceStart = functionBlockFrom(service, "private fun startVoiceSession")
        val transition = MainActivityRefactorSource.function(mainActivity, "private fun transitionWalkSession")
        val pause = MainActivityRefactorSource.function(mainActivity, "override fun onPause")
        val destroy = MainActivityRefactorSource.function(mainActivity, "override fun onDestroy")
        val close = runtimeMember("closeHandsFreeVoice")

        assertTrue(runtimeEligibility.contains("snapshot.state == WalkSessionState.ACTIVE"))
        assertTrue(activate.contains("camera.startRuntimeAfterRelease(runtimeEpoch)"))
        assertTrue(startAfterCameraRelease.contains("effects.startHandsFreeVoice()"))
        assertTrue(start.contains("if (!environment.foreground() ||"))
        assertTrue(start.contains("environment.disclosureAccepted()"))
        assertTrue(start.contains("environment.notificationGranted()"))
        assertTrue(start.contains("handsFreeVoiceController == null"))
        assertTrue(start.contains("handsFreeVoiceModelDirectory?.isDirectory != true"))
        assertTrue(start.contains("!environment.microphoneGranted()"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                start,
                "!environment.foreground()",
                "handsFreeVoiceController == null",
                "handsFreeVoiceModelDirectory?.isDirectory != true",
                "!environment.runtimeActive()",
                "!environment.microphoneGranted()",
                "platform.startService(requestId)",
                "handsFreeVoiceServiceRequested = true",
            ),
        )
        assertFalse(start.contains("ContextCompat.startForegroundService(this, startIntent)"))
        // The expected platform start failures are reported with their own class (the caller
        // contract also pinned by WalkVoicePlatformStartRecoveryTest).
        for ((handler, failure) in listOf(
            "catch (error: SecurityException)" to "WalkVoiceStartFailure.SECURITY",
            "catch (error: IllegalStateException)" to "WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED",
        )) {
            assertTrue(handler, start.contains(handler))
            assertTrue(
                handler,
                ReportStaticSourceInspector.appearsInOrder(
                    start.substringAfter(handler),
                    "handsFreeVoiceServiceRequested = false",
                    "platform.reportStartFailure(",
                    failure,
                ),
            )
        }
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                serviceStart,
                "val controller = app?.walkVoiceSessionController",
                "!app.acceptsWalkVoiceStartRequest(requestId, controller)",
                "try {",
                "activeController = controller",
                "ensureForegroundStarted()",
                "if (!app.startWalkVoiceController(requestId, controller))",
            ),
        )
        // Foreground promotion can throw too: its failure must release the owned controller
        // before the owner is told (handleWalkVoiceServiceStartException behaviour is exercised in
        // WalkVoicePlatformStartRecoveryTest.expectedPlatformFailuresCleanUpBeforeReporting).
        val failedStart = serviceStart.substringAfter("catch (error: RuntimeException)")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            failedStart,
            "handleWalkVoiceServiceStartException(",
            "stopVoiceSession()",
            "stopSelf()",
            "app.reportWalkVoiceStartFailure(requestId, controller, failure)",
        ))
        assertFalse(failedStart.contains("throw "))
        assertTrue(transition.contains("transition.current.state != WalkSessionState.ACTIVE"))
        assertTrue(transition.contains("effects.stopHandsFreeVoiceService()"))
        assertTrue(pause.contains("stopHandsFreeVoiceService()"))
        assertTrue(destroy.contains("closeHandsFreeVoice()"))
        assertTrue(close.contains("stopHandsFreeVoiceService()"))
    }

    @Test
    fun deviceCheckRecordsDisclosureAndActualVoiceProbeResults() {
        val deviceCheckStart =
            MainActivityRefactorSource.function(mainActivity, "private fun startPostLoginDeviceCheckFromPrimaryAction")
        val orchestration = MainActivityRefactorSource.function(mainActivity, "private fun maybeContinuePostLoginDeviceCheck")
        val observation = MainActivityRefactorSource.function(mainActivity, "private fun currentPostLoginDeviceCheckObservation")
        val requiredPermissions =
            MainActivityRefactorSource.function(mainActivity, "private fun requiredPostLoginDeviceCheckPermissions")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                deviceCheckStart,
                "permissions.acknowledgeVoiceDisclosure()",
                "beginFromUserAction(binding)",
            ),
        )
        assertFalse(deviceCheckStart.contains("AlertDialog"))
        assertFalse(orchestration.contains("maybeStartPostLoginWakePhraseProbe("))
        assertFalse(orchestration.contains("beginPostLoginWakePhraseProbeFromUserAction("))
        assertFalse(
            observation.contains(
                "wakePhraseRecognition = speechRecognitionAvailable.toDeviceCheckSignal()",
            ),
        )
        val wakeObservation = observation.substringAfter("wakePhraseRecognition = if (")
            .substringBefore("metricDepth = metricDepth")
        assertTrue(wakeObservation.contains("!hardware.microphonePermission"))
        assertTrue(wakeObservation.contains("startup.microphoneAvailable == false"))
        assertTrue(wakeObservation.contains("hardware.voiceModelPreparationFailed"))
        assertTrue(wakeObservation.contains("PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(wakeObservation.contains("else probeSignals.wakePhrase"))
        assertTrue(requiredPermissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(requiredPermissions.contains("Manifest.permission.POST_NOTIFICATIONS"))
        val start = runtimeMember("maybeStartHandsFreeVoiceService")
        assertTrue(start.contains("environment.disclosureAccepted()"))
        assertTrue(start.contains("voice_hands_free=disclosure_required"))
        assertTrue(start.contains("environment.notificationGranted()"))
        assertTrue(start.contains("voice_hands_free=notification_permission_required"))
        val observedPermissions = MainActivityRefactorSource.function(mainActivity, "private fun applyObservedPermissionStateChange")
        val notificationBranch = observedPermissions
            .substringAfter("if (!platform.notificationGranted()) {")
            .substringBefore("if (!platform.microphoneGranted()) {")
        val microphoneBranch = observedPermissions
            .substringAfter("if (!platform.microphoneGranted()) {")
            .substringBefore("if (!platform.activityRecognitionGranted()) {")
        assertTrue(notificationBranch.contains("runtime.stopHandsFree()"))
        assertFalse(notificationBranch.contains("runtime.cancelVoiceRecognition()"))
        assertTrue(microphoneBranch.contains("runtime.stopHandsFree()"))
        assertTrue(microphoneBranch.contains("runtime.cancelVoiceRecognition()"))
    }

    @Test
    fun handsFreeTranscriptReusesTheExistingHandlerWithoutDisplayingRawText() {
        val attach = runtimeMember("attachHandsFreeVoiceController")
        assertTrue(attach.contains("command = input::recognizedCommand"))
        assertTrue(MainActivityRefactorSource.member(input.replace("\n            override fun ", "\n    override fun "), "recognizedCommand").contains("handleHandsFreeCommand(text, confidence)"))
        val commandCallback = MainActivityRefactorSource.member(input, "handleHandsFreeCommand")

        assertTrue(commandCallback.contains("commands.recognized(RecognizedVoiceInput("))
        assertTrue(commandCallback.contains("listOf(confidence ?: Float.NaN)"))
        assertTrue(commandCallback.contains("capture.decisionToken, false,"))
        assertTrue(commandCallback.contains("\"on_device_hands_free\""))
        assertFalse(commandCallback.contains("updateNavigationStatus("))
        assertFalse(commandCallback.contains("toStatusToken("))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.function(mainActivity, "private fun createVoiceInputController(").contains("commands = VoiceInputCommandPeerAdapter({ voiceInteractionControllers }"))
        assertTrue(MainActivityRefactorSource.member(peers, "recognized").contains("interaction().commands.handleRecognized(result)"))
        val commands = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
        val handoff = MainActivityRefactorSource.member(commands, "handleRecognized")
        assertTrue(handoff.contains("handleVoiceCommandPhrases(result.phrases,"))
        assertTrue(handoff.contains("result.confidenceScores?.toFloatArray(), result.expectedNavigationDecisionToken"))
        assertTrue(handoff.contains("result.includeRecognizedTextInStatus, result.source, result.backend, result.hasAdditionalAlternatives"))
    }

    @Test
    fun homeWakeCommandUsesTheSameHandlerWithoutStartingASecondRecording() {
        val home = MainActivityRefactorSource.function(mainActivity, "private fun refreshForegroundHomeWakeListening")
        val available = home.substringAfter("result.availability == VoskWakePhraseProbeAvailability.AVAILABLE")
            .substringBefore("// A passive render")
        val acknowledgement = MainActivityRefactorSource.function(mainActivity, "private fun acknowledgeForegroundHomeWake")
        val command = acknowledgement.substringAfter("} else {")
        assertTrue(acknowledgement.contains("if (command == null || environment.serverCommandRecognitionEnabled())"))
        assertTrue(acknowledgement.contains("session.startCommandRecognition()"))
        assertTrue(command.contains("session.recognized(RecognizedVoiceInput("))
        assertTrue(command.contains("listOf(command.text)"))
        assertTrue(command.contains("listOf(command.confidence ?: Float.NaN)"))
        assertTrue(command.contains("expectedNavigationDecisionToken, false,"))
        assertTrue(command.contains("OfflineSpeechEngine.VOSK"))
        assertFalse(command.contains("session.startCommandRecognition()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            available,
            "cancelForegroundHomeWakeListening()",
            "acknowledgeForegroundHomeWake(",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            home,
            "val expectedNavigationDecisionToken = environment.pendingNavigationDecisionToken()",
            "probe = platform.createProbe(ForegroundWakeCallbacks(",
        ))
        assertTrue(
            Regex(
                """acknowledgeForegroundHomeWake\(\s*result\.commandTranscript,\s*""" +
                    """expectedNavigationDecisionToken,?\s*\)""",
            ).containsMatchIn(available),
        )
        assertTrue(command.contains("expectedNavigationDecisionToken, false," ))
        assertFalse(available.contains("pendingDecisionToken()"))
        assertFalse(acknowledgement.contains("pendingDecisionToken()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            acknowledgement,
            "presentation.showCommandPage()",
            "val isCurrent =",
            "player.play",
            "if (!isCurrent()) {",
            "return@play",
            "if (command == null || environment.serverCommandRecognitionEnabled())",
        ))
    }

    @Test
    fun staleStartupAndTerminalErrorsCannotLeaveAFalseListeningService() {
        val controller = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt",
        ).readText()
        val service = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkVoiceForegroundService.kt",
        ).readText()
        val application = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkSafeApplication.kt",
        ).readText()
        val controllerMethods = controller.substringAfter(
            "private val stateMachine = HandsFreeVoiceStateMachine()",
        )
        val controllerStart = functionBlockFrom(controllerMethods, "override fun start")
        val controllerStop = functionBlockFrom(controllerMethods, "override fun stop")
        val controllerClose = functionBlockFrom(controllerMethods, "override fun close")
        val transcriberSource = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/VoskStreamingTranscriber.kt",
        ).readText()
        val transcriberStart = functionBlockFrom(transcriberSource, "fun start")
        val reservedSubmission = functionBlockFrom(
            transcriberSource,
            "internal fun submitReservedVoskStreamingRun",
        )
        val ready = functionBlockFrom(controller, "private fun handleReady")
        val transcript = functionBlockFrom(controller, "private fun handleTranscript")
        val handleError = functionBlockFrom(controller, "private fun handleError")
        val failClosed = functionBlockFrom(controller, "private fun failClosed")
        val ended = runtimeMember("handleHandsFreeVoiceSessionEnded")
        val serviceStart = functionBlockFrom(service, "private fun startVoiceSession")
        val relayStart = functionBlockFrom(application, "fun startController")

        assertTrue(controller.contains("sealed interface HandsFreeVoiceTerminalFailure"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.Transcriber(error)"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.CommandDispatchFailed"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.OutputTimeout"))
        assertFalse(controller.contains("HandsFreeVoiceInternalFailure"))
        assertTrue(runtimeMember("attachHandsFreeVoiceController").contains("terminalFailure = ::handleHandsFreeVoiceTerminalFailure"))
        val terminalHandler = runtimeMember("handleHandsFreeVoiceTerminalFailure")
        assertTrue(terminalHandler.contains("failure: HandsFreeVoiceTerminalFailure,"))
        assertTrue(terminalHandler.contains("handsFreeVoiceServiceRequested = false"))
        assertTrue(terminalHandler.contains("stopService("))
        assertTrue(terminalHandler.contains("is HandsFreeVoiceTerminalFailure.Transcriber ->"))
        assertTrue(terminalHandler.contains("HandsFreeVoiceTerminalFailure.CommandDispatchFailed,"))
        assertTrue(terminalHandler.contains("HandsFreeVoiceTerminalFailure.OutputTimeout,"))
        assertTrue(terminalHandler.contains("applicationEffects.limitPersistentHandsFreeRecognition()"))
        assertTrue(terminalHandler.contains("scheduleHandsFreeVoiceRestart()"))
        assertTrue(
            terminalHandler.contains("transcriber_limited_\${failure.statusToken}"),
        )
        assertTrue(
            terminalHandler.contains("internal_restart_\${failure.statusToken}"),
        )
        assertFalse(terminalHandler.contains("failure.error.name.lowercase()"))
        assertFalse(terminalHandler.contains("failure.reason"))
        assertFalse(terminalHandler.contains("oneShotSpeechRecognitionLimited"))
        assertFalse(terminalHandler.contains("enterWalkSessionSafetyStopAndCancelOutputs("))

        assertTrue(service.contains("fun start(): Boolean"))
        assertTrue(controller.contains("override fun start(): Boolean"))
        assertTrue(controllerStart.contains("return false"))
        assertTrue(controllerStart.contains("return true"))
        assertTrue(controllerStart.contains("transcriber.start { runId ->"))
        assertTrue(controllerStart.contains("startingRunId = runId"))
        assertTrue(transcriberStart.contains("submitReservedVoskStreamingRun("))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                reservedSubmission,
                "onRunReserved(runId)",
                "executor.execute(task)",
            ),
        )
        assertTrue(serviceStart.contains("walkVoiceSessionController"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            serviceStart,
            "if (app == null || controller == null ||",
            "!app.acceptsWalkVoiceStartRequest(requestId, controller)",
            "if (!sessionStarted) stopSelf()",
            "return",
            "ensureForegroundStarted()",
            "if (!app.startWalkVoiceController(requestId, controller))",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            relayStart,
            "current?.takeIf { it.id == requestId && it.controller === controller }",
            "?: return false",
            "val started = controller.start()",
            "if (started) invalidate(requestId, controller)",
            "else report(requestId, controller, WalkVoiceStartFailure.CONTROLLER_REJECTED)",
        ))
        assertTrue(serviceStart.contains("stopVoiceSession(stopController = false)"))
        assertTrue(serviceStart.contains("stopSelf()"))

        assertTrue(runtimeMember("attachHandsFreeVoiceController").contains("sessionEnded = ::handleHandsFreeVoiceSessionEnded"))
        assertTrue(ready.contains("HandsFreeVoiceRunCallbackFence.acceptsReady"))
        assertTrue(ready.contains("endSessionWithoutRestart("))
        assertTrue(transcript.contains("endSessionWithoutRestart("))
        assertTrue(ended.contains("handsFreeVoiceServiceRequested = false"))
        assertTrue(ended.contains("stopService("))
        assertFalse(ended.contains("scheduleHandsFreeVoiceRestart()"))
        assertFalse(ended.contains("enterWalkSessionSafetyStopAndCancelOutputs("))

        assertTrue(controllerStop.contains("if (!preserveStopStatus)"))
        assertTrue(controllerStop.contains("startingRunId = null"))
        assertTrue(controllerStop.contains("terminalFailureDelivered = true"))
        assertTrue(controllerClose.contains("startingRunId = null"))
        assertTrue(controllerClose.contains("terminalFailureDelivered = true"))
        assertTrue(handleError.contains("HandsFreeVoiceRunCallbackFence.acceptsError"))
        assertTrue(handleError.contains("|| terminalFailureDelivered"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handleError,
                "terminalFailureDelivered = true",
                "activeRunId = null",
                "startingRunId = null",
                "stateMachine.onError(generation)",
                "transcriber.stop()",
                "preserveStopStatus = true",
                "notifyHandsFreeVoiceTerminalFailure(failure, onTerminalFailure)",
            ),
        )
        assertTrue(failClosed.contains("if (terminalFailureDelivered) return"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                failClosed,
                "terminalFailureDelivered = true",
                "stateMachine.onError(generation)",
                "activeRunId = null",
                "startingRunId = null",
                "transcriber.stop()",
                "preserveStopStatus = true",
                "notifyHandsFreeVoiceTerminalFailure(failure, onTerminalFailure)",
            ),
        )
    }

    @Test
    fun automaticVoicePageCanRecoverWithoutTakingOverAnExplicitButtonCapture() {
        val start = runtimeMember("maybeStartHandsFreeVoiceService")
        val automaticPage = MainActivityRefactorSource.function(mainActivity, "private fun handleHandsFreeVoiceCommandListeningStarted")
        val manualPage = MainActivityRefactorSource.function(mainActivity, "private fun showNativeUiPage")
        val capture = MainActivityRefactorSource.function(mainActivity, "private fun startVoiceCommandRecognition")
        val stop = runtimeMember("stopHandsFreeVoiceService")
        val close = runtimeMember("closeHandsFreeVoice")

        assertFalse(start.replace(Regex("\\s+"), "").contains(
            "(nativeUiPage==NativeUiPage.VOICE_COMMAND&&!handsFreeVoiceOwnsCommandPage)",
        ))
        assertTrue(start.contains("input.otherInputBusy()"))
        val inputBusy = MainActivityRefactorSource.member(input.replace("\n            override fun ", "\n    override fun "), "otherInputBusy")
        assertTrue(inputBusy.contains("voiceRecognitionActive || voiceCommandPromptPending ||"))
        assertTrue(inputBusy.contains("deferredVoiceRecognitionStart != null"))
        assertTrue(inputBusy.contains("gateway.isRecording()"))
        assertTrue(inputBusy.contains("gateway.hasInteraction()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            automaticPage,
            "handsFreeVoiceOwnsCommandPage = true",
            "presentation.showCommandPage()",
        ))
        assertTrue(manualPage.contains("pages.releaseCommandPageOwnership()"))
        assertTrue(capture.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertTrue(close.contains("input.runtimeRetired()"))
        assertTrue(MainActivityRefactorSource.member(input.replace("\n            override fun ", "\n    override fun "), "runtimeRetired").contains("handsFreeVoiceOwnsCommandPage = false"))
        assertFalse(stop.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertTrue(start.contains("!environment.runtimeActive()"))
        assertTrue(start.contains("environment.disclosureAccepted()"))
        assertTrue(start.contains("!environment.microphoneGranted()"))
    }

    @Test
    fun wakeAcknowledgementFinishesBeforeCommandListeningWithHapticAndLiveStatus() {
        val controller = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt",
        ).readText()
        val openWindow = functionBlockFrom(controller, "private fun openCommandWindow")
        val acknowledgeWake = functionBlockFrom(controller, "private fun acknowledgeWake")
        val callback = MainActivityRefactorSource.function(mainActivity, "private fun handleHandsFreeVoiceCommandListeningStarted")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                openWindow,
                "stateMachine.onWakeAcknowledgementFinished",
                "runCatching(onCommandListeningStarted)",
                "scheduleCommandTimeout",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                acknowledgeWake,
                "stateMachine.onWakeWordDetected",
                "dependencies.playAcknowledgement acknowledgement@",
                "if (closed || terminalFailureDelivered || activeRunId != runId ||",
                "if (result == WakeAcknowledgementResult.CANCELLED ||",
                "openCommandWindow(acknowledging.generation, runId, command, confidence)",
            ),
        )
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(callback.contains("output.playListeningStarted()"))
        assertTrue(MainActivityRefactorSource.member(peers, "playListeningStarted").contains("speech().ensureFeedbackActuator().playVoiceListeningStartVibration()"))
        assertTrue(callback.contains("presentation.voiceStatus("))
        assertTrue(MainActivityRefactorSource.property(mainActivity, "voiceInputPresentation").contains("VoiceInputPresentationAdapter(voiceUiEffects"))
        assertTrue(MainActivityRefactorSource.property(mainActivity, "voiceUiEffects").contains("updateGatewayVoiceStatus(message)"))
        assertTrue(mainActivity.contains("ACCESSIBILITY_LIVE_REGION_POLITE"))
    }

    @Test
    fun pcmStaysInMemoryAndNativeDecoderCloseIsSerializedWithInference() {
        val pcmSource = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/AndroidPcmAudioSource.kt",
        ).readText()
        val transcriber = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/VoskStreamingTranscriber.kt",
        ).readText()

        assertTrue(pcmSource.contains("const val SAMPLE_RATE_HZ = 16_000"))
        assertFalse(pcmSource.contains("FileOutputStream"))
        assertFalse(pcmSource.contains("preRoll"))
        val processPcm = functionBlockFrom(transcriber, "private fun processPcm")
        val release = functionBlockFrom(transcriber, "private fun releaseResources")
        val releaseWithLock = functionBlockFrom(transcriber, "internal fun releaseVoskResources")
        assertTrue(processPcm.contains("synchronized(decoderLock)"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                release,
                "releaseVoskResources(",
                "decoderLock = decoderLock",
                "stopAudio = { resources.audioSource?.stop() }",
                "closeRecognizer = { resources.recognizer?.close() }",
                "closeModel = { resources.model?.close() }",
                "resetDecoderState = {",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                releaseWithLock,
                "attempt(stopAudio)",
                "synchronized(decoderLock)",
                "attempt(closeRecognizer)",
                "attempt(closeModel)",
                "resetDecoderState()",
            ),
        )
    }

    @Test
    fun manifestAndBuildDeclareTheMicrophoneForegroundServiceRuntime() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = buildSet {
            for (index in 0 until permissions.length) {
                add(permissions.item(index).androidAttribute("name"))
            }
        }
        assertTrue("android.permission.RECORD_AUDIO" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissionNames)

        val application = manifest.getElementsByTagName("application").item(0)
        assertEquals(".voice.WalkSafeApplication", application.androidAttribute("name"))
        val services = manifest.getElementsByTagName("service")
        val voiceService = (0 until services.length)
            .map(services::item)
            .single { it.androidAttribute("name") == ".voice.WalkVoiceForegroundService" }
        assertEquals("false", voiceService.androidAttribute("exported"))
        assertEquals("microphone", voiceService.androidAttribute("foregroundServiceType"))

        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("implementation(\"com.alphacephei:vosk-android:0.3.75\")"))
        assertTrue(build.contains("verifyBundledVoskModelAssets"))
        assertTrue(build.contains("name == \"mergeDebugAssets\""))
    }

    @Test
    fun onlyAPersistentTranscriberFailureDisablesOnDeviceSpeechRecognition() {
        // S14-16-a / FP-025 "계속 실패": a single transient transcriber error restarts listening; only
        // MODEL_UNAVAILABLE or two consecutive failures disable on-device STT (which blocks walks).
        val terminalHandler = runtimeMember("handleHandsFreeVoiceTerminalFailure")
        val transcriberBranch = terminalHandler
            .substringAfter("is HandsFreeVoiceTerminalFailure.Transcriber ->")
            .substringBefore("HandsFreeVoiceTerminalFailure.CommandDispatchFailed,")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                transcriberBranch,
                "handsFreeTranscriberFailureCount += 1",
                "failure.error == VoskStreamingError.MODEL_UNAVAILABLE ||",
                "handsFreeTranscriberFailureCount >= HANDS_FREE_TRANSCRIBER_PERSISTENT_FAILURES",
                "if (!persistentTranscriberFailure) {",
                "presentation.navigationStatus(\"voice_hands_free=internal_restart_\${failure.statusToken}\")",
                "scheduleHandsFreeVoiceRestart()",
                "return",
                "applicationEffects.limitPersistentHandsFreeRecognition()",
                "presentation.navigationStatus(",
            ),
        )
        assertTrue(runtime.contains("const val HANDS_FREE_TRANSCRIBER_PERSISTENT_FAILURES = 2"))
        assertTrue(runtime.contains("private var handsFreeTranscriberFailureCount = 0"))

        // The streak restarts whenever command listening starts again.
        val listeningStarted = MainActivityRefactorSource.function(mainActivity, "private fun handleHandsFreeVoiceCommandListeningStarted")
        assertTrue(listeningStarted.contains("handsFreeRuntime.clearTranscriberFailureHistory()"))
        // Every place that clears the device-check STT override also clears the streak.
        val device = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/PostLoginDeviceCheckController.kt").readText()
        val clearedOverrides = Regex("effects\\.applyCapabilityOverrides\\(").findAll(device).count()
        assertTrue("override clears=$clearedOverrides", clearedOverrides >= 4)
        val reset = functionBlockFrom(mainActivity, "override fun applyCapabilityOverrides")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(reset,
            "onDeviceSpeechRecognitionCapabilityOverride = null", "voiceInputController.clearTranscriberFailureHistory()"))
        val persistentEffect = functionBlockFrom(mainActivity, "override fun limitPersistentHandsFreeRecognition")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(persistentEffect,
            "val wasConfirmed = isStartupCapabilityConfirmed()",
            "onDeviceSpeechRecognitionCapabilityOverride = false",
            "refreshStartupCapabilityUi()",
            "if (wasConfirmed) {",
            "confirmedStartupCapabilityDecision = startupCapabilityDecision?.takeIf {",
            "it.mayConfirmAndStart",
            "refreshStartupCapabilityUi()",
            "val walkStartStillAllowed = startupCapabilityDecision?.mayConfirmAndStart == true"))
        val confirmedRecheck = ReportStaticSourceInspector.blockAfter(persistentEffect, "if (wasConfirmed)")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(confirmedRecheck,
            "confirmedStartupCapabilityDecision = startupCapabilityDecision?.takeIf {",
            "it.mayConfirmAndStart",
            "refreshStartupCapabilityUi()"))
        val clearHistory = runtimeMember("clearTranscriberFailureHistory")
        assertTrue(clearHistory.contains("handsFreeTranscriberFailureCount = 0"))
    }

    @Test
    fun restoredWakePhraseTestMissStaysAHandsFreeFeatureRestriction() {
        // S14-16-b: a stored HANDS_FREE_VOICE restriction (wake-word test miss) is not evidence that
        // the on-device STT engine is unavailable, so a restart does not turn it into a walk block.
        val bind = MainActivityRefactorSource.function(mainActivity, "private fun bindPostLoginDeviceCheckSession")
        assertTrue(bind.contains("if (restored != null) {"))
        val restoredOverrides = functionBlockFrom(mainActivity, "override fun applyCapabilityOverrides")
        assertTrue(restoredOverrides.contains("onDeviceSpeechRecognitionCapabilityOverride = null"))
        assertFalse(restoredOverrides.contains("HANDS_FREE_VOICE"))
        assertFalse(bind.contains("speechRecognition ="))
        assertFalse(
            Regex("HANDS_FREE_VOICE in restored\\.disabledFeatures\\s*\\)\\s*\\{\\s*false").containsMatchIn(bind),
        )
        // In-session the same restriction is a feature restriction only, never an STT override.
        val restrictions = MainActivityRefactorSource.function(mainActivity, "private fun applyPostLoginDeviceFeatureRestrictions")
        assertTrue(restrictions.contains("PostLoginDeviceCheckFeature.HANDS_FREE_VOICE"))
        assertFalse(restrictions.contains("onDeviceSpeechRecognitionCapabilityOverride = false"))
    }

    private fun functionBlock(signature: String): String =
        ReportStaticSourceInspector.functionBlock(mainActivity, signature)

    private fun functionBlockFrom(source: String, signature: String): String =
        ReportStaticSourceInspector.functionBlock(source, signature)

    private fun org.w3c.dom.Node.androidAttribute(name: String): String =
        attributes.getNamedItemNS(ANDROID_NAMESPACE, name).nodeValue

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().parse(file)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
