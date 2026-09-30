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

    @Test
    fun modelPreparationStartsAfterPrivacyReadyAndExplicitRequestsCanRetry() {
        val privacyReady = functionBlock("private fun completePrivacyStartupReadyIfForeground")
        val preparation = functionBlock("private fun prepareHandsFreeVoiceModel")
        val buttonRecognition = functionBlock("private fun startVoiceCommandRecognition")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                privacyReady,
                "privacyStartupInspectionComplete = true",
                "onReady()",
                "prepareHandsFreeVoiceModel()",
            ),
        )
        assertTrue(
            preparation.contains(
                "handsFreeVoiceDestroyed || handsFreeVoiceModelDirectory != null || handsFreeVoiceModelPreparing",
            ),
        )
        assertTrue(preparation.contains("BundledVoskModelInstaller.installedModelOrNull(this)"))
        assertTrue(preparation.contains("generation != handsFreeVoiceModelGeneration"))
        assertTrue(preparation.contains("oneShotSpeechRecognitionLimited = false"))
        assertTrue(
            buttonRecognition.contains(
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
            ),
        )
        assertTrue(buttonRecognition.contains("nativeHomeFeatureContextAvailable()"))
        assertTrue(
            buttonRecognition.contains(
                "if (handsFreeVoiceModelDirectory == null && !preferPlatform)",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                buttonRecognition,
                "if (handsFreeVoiceModelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeVoiceModelDirectory == null && !preferPlatform)",
                "stopHandsFreeVoiceService()",
                "PreferredOfflineSpeechRecognizer(this) { handsFreeVoiceModelDirectory }",
                "preferPlatform = preferPlatform",
                "isRequestCurrent = {",
            ),
        )
    }

    @Test
    fun foregroundServiceFollowsTheActiveWalkLifecycle() {
        val runtimeEligibility = functionBlock("private fun isWalkSessionRuntimeActive")
        val activate = functionBlock("private fun activateWalkSessionRuntime")
        val startAfterCameraRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease")
        val start = functionBlock("private fun maybeStartHandsFreeVoiceService")
        val service = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkVoiceForegroundService.kt",
        ).readText()
        val serviceStart = functionBlockFrom(service, "private fun startVoiceSession")
        val transition = functionBlock("private fun transitionWalkSession")
        val pause = functionBlock("override fun onPause")
        val destroy = functionBlock("override fun onDestroy")
        val close = functionBlock("private fun closeHandsFreeVoice")

        assertTrue(runtimeEligibility.contains("snapshot.state == WalkSessionState.ACTIVE"))
        assertTrue(activate.contains("startWalkSessionRuntimeAfterCameraRelease(runtimeEpoch)"))
        assertTrue(startAfterCameraRelease.contains("maybeStartHandsFreeVoiceService()"))
        assertTrue(start.contains("if (!isActivityForeground ||"))
        assertTrue(start.contains("isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(start.contains("hasHandsFreeNotificationPermission()"))
        assertTrue(start.contains("handsFreeVoiceController == null"))
        assertTrue(start.contains("handsFreeVoiceModelDirectory?.isDirectory != true"))
        assertTrue(start.contains("!hasRecordAudioPermission()"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                start,
                "!isActivityForeground",
                "handsFreeVoiceController == null",
                "handsFreeVoiceModelDirectory?.isDirectory != true",
                "!isWalkSessionRuntimeActive()",
                "!hasRecordAudioPermission()",
                "startService(startIntent)",
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
                    "app.reportWalkVoiceStartFailure(",
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
        assertTrue(transition.contains("stopHandsFreeVoiceService()"))
        assertTrue(pause.contains("stopHandsFreeVoiceService()"))
        assertTrue(destroy.contains("closeHandsFreeVoice()"))
        assertTrue(close.contains("stopHandsFreeVoiceService()"))
    }

    @Test
    fun deviceCheckRecordsDisclosureAndActualVoiceProbeResults() {
        val deviceCheckStart =
            functionBlock("private fun startPostLoginDeviceCheckFromPrimaryAction")
        val orchestration = functionBlock("private fun maybeContinuePostLoginDeviceCheck")
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation")
        val requiredPermissions =
            functionBlock("private fun requiredPostLoginDeviceCheckPermissions")

        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                deviceCheckStart,
                "acknowledgeHandsFreeVoiceDisclosure()",
                "beginPostLoginDeviceCheckFromUserAction(sessionBinding)",
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
        val wakeObservation = observation.substringAfter("wakePhraseRecognition = when {")
            .substringBefore("metricDepth = metricDepth")
        assertTrue(wakeObservation.contains("!hasRecordAudioPermission()"))
        assertTrue(wakeObservation.contains("startup.microphoneAvailable == false"))
        assertTrue(wakeObservation.contains("handsFreeVoiceModelPreparationFailed"))
        assertTrue(wakeObservation.contains("PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(wakeObservation.contains("else -> postLoginWakePhraseSignal"))
        assertTrue(requiredPermissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(requiredPermissions.contains("Manifest.permission.POST_NOTIFICATIONS"))
        val start = functionBlock("private fun maybeStartHandsFreeVoiceService")
        assertTrue(start.contains("isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(start.contains("voice_hands_free=disclosure_required"))
        assertTrue(start.contains("hasHandsFreeNotificationPermission()"))
        assertTrue(start.contains("voice_hands_free=notification_permission_required"))
        val observedPermissions = functionBlock("private fun applyObservedPermissionStateChange")
        val notificationBranch = observedPermissions
            .substringAfter("if (!hasHandsFreeNotificationPermission()) {")
            .substringBefore("if (!hasRecordAudioPermission()) {")
        val microphoneBranch = observedPermissions
            .substringAfter("if (!hasRecordAudioPermission()) {")
            .substringBefore("if (!hasActivityRecognitionPermission()) {")
        assertTrue(notificationBranch.contains("stopHandsFreeVoiceService()"))
        assertFalse(notificationBranch.contains("cancelVoiceCommandRecognition()"))
        assertTrue(microphoneBranch.contains("stopHandsFreeVoiceService()"))
        assertTrue(microphoneBranch.contains("cancelVoiceCommandRecognition()"))
    }

    @Test
    fun handsFreeTranscriptReusesTheExistingHandlerWithoutDisplayingRawText() {
        val attach = functionBlock("private fun attachHandsFreeVoiceController")
        val commandCallback = ReportStaticSourceInspector.blockAfter(
            attach,
            "onCommand = command@",
        )

        assertTrue(commandCallback.contains("handleVoiceCommandPhrases("))
        assertTrue(commandCallback.contains("floatArrayOf(confidence ?: Float.NaN)"))
        assertTrue(commandCallback.contains("includeRecognizedTextInStatus = false"))
        assertTrue(commandCallback.contains("redactedRecognitionSource = \"on_device_hands_free\""))
        assertFalse(commandCallback.contains("updateNavigationStatus("))
        assertFalse(commandCallback.contains("toStatusToken("))
    }

    @Test
    fun homeWakeCommandUsesTheSameHandlerWithoutStartingASecondRecording() {
        val home = functionBlock("private fun refreshForegroundHomeWakeListening")
        val available = home.substringAfter("result.availability == VoskWakePhraseProbeAvailability.AVAILABLE")
            .substringBefore("// A passive render")
        val acknowledgement = functionBlock("private fun acknowledgeForegroundHomeWake")
        val command = acknowledgement.substringAfter("} else {")
        assertTrue(acknowledgement.contains("if (command == null)"))
        assertTrue(acknowledgement.contains("startVoiceCommandRecognition()"))
        assertTrue(command.contains("handleVoiceCommandPhrases("))
        assertTrue(command.contains("phrases = listOf(command.text)"))
        assertTrue(command.contains("confidenceScores = floatArrayOf(command.confidence ?: Float.NaN)"))
        assertTrue(command.contains("includeRecognizedTextInStatus = false"))
        assertTrue(command.contains("resultBackend = OfflineSpeechEngine.VOSK"))
        assertFalse(command.contains("startVoiceCommandRecognition()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            available,
            "cancelForegroundHomeWakeListening()",
            "acknowledgeForegroundHomeWake(",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            home,
            "val expectedNavigationDecisionToken = routeNavigator.pendingDecisionToken()",
            "probe = VoskWakePhraseProbe(",
        ))
        assertTrue(
            Regex(
                """acknowledgeForegroundHomeWake\(\s*result\.commandTranscript,\s*""" +
                    """expectedNavigationDecisionToken,?\s*\)""",
            ).containsMatchIn(available),
        )
        assertTrue(command.contains("expectedNavigationDecisionToken = expectedNavigationDecisionToken"))
        assertFalse(available.contains("pendingDecisionToken()"))
        assertFalse(acknowledgement.contains("pendingDecisionToken()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            acknowledgement,
            "showNativeUiPage(NativeUiPage.VOICE_COMMAND, preserveVoiceInteraction = true)",
            "val isCurrent =",
            "player.play",
            "if (!isCurrent()) return@play",
            "if (command == null)",
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
        val ended = functionBlock("private fun handleHandsFreeVoiceSessionEnded")
        val serviceStart = functionBlockFrom(service, "private fun startVoiceSession")
        val relayStart = functionBlockFrom(application, "fun startController")

        assertTrue(controller.contains("sealed interface HandsFreeVoiceTerminalFailure"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.Transcriber(error)"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.CommandDispatchFailed"))
        assertTrue(controller.contains("HandsFreeVoiceTerminalFailure.OutputTimeout"))
        assertFalse(controller.contains("HandsFreeVoiceInternalFailure"))
        assertTrue(mainActivity.contains("onTerminalFailure = ::handleHandsFreeVoiceTerminalFailure"))
        val terminalHandler = functionBlock("private fun handleHandsFreeVoiceTerminalFailure")
        assertTrue(mainActivity.contains("failure: HandsFreeVoiceTerminalFailure,"))
        assertTrue(terminalHandler.contains("handsFreeVoiceServiceRequested = false"))
        assertTrue(terminalHandler.contains("stopService("))
        assertTrue(terminalHandler.contains("is HandsFreeVoiceTerminalFailure.Transcriber ->"))
        assertTrue(terminalHandler.contains("HandsFreeVoiceTerminalFailure.CommandDispatchFailed,"))
        assertTrue(terminalHandler.contains("HandsFreeVoiceTerminalFailure.OutputTimeout,"))
        assertTrue(terminalHandler.contains("onDeviceSpeechRecognitionCapabilityOverride = false"))
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

        assertTrue(mainActivity.contains("onSessionEnded = ::handleHandsFreeVoiceSessionEnded"))
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
        val start = functionBlock("private fun maybeStartHandsFreeVoiceService")
        val automaticPage = functionBlock("private fun handleHandsFreeVoiceCommandListeningStarted")
        val manualPage = functionBlock("private fun showNativeUiPage")
        val capture = functionBlock("private fun startVoiceCommandRecognition")
        val stop = functionBlock("private fun stopHandsFreeVoiceService")
        val close = functionBlock("private fun closeHandsFreeVoice")

        assertTrue(start.replace(Regex("\\s+"), "").contains(
            "(nativeUiPage==NativeUiPage.VOICE_COMMAND&&!handsFreeVoiceOwnsCommandPage)",
        ))
        assertTrue(start.contains("voiceCommandPromptPending"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            automaticPage,
            "handsFreeVoiceOwnsCommandPage = true",
            "showNativeUiPage(NativeUiPage.VOICE_COMMAND, preserveVoiceInteraction = true)",
        ))
        assertTrue(manualPage.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertTrue(capture.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertTrue(close.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertFalse(stop.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertTrue(start.contains("!isWalkSessionRuntimeActive()"))
        assertTrue(start.contains("isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(start.contains("!hasRecordAudioPermission()"))
    }

    @Test
    fun wakeAcknowledgementFinishesBeforeCommandListeningWithHapticAndLiveStatus() {
        val controller = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt",
        ).readText()
        val openWindow = functionBlockFrom(controller, "private fun openCommandWindow")
        val acknowledgeWake = functionBlockFrom(controller, "private fun acknowledgeWake")
        val callback = functionBlock("private fun handleHandsFreeVoiceCommandListeningStarted")

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
        assertTrue(callback.contains("playVoiceListeningStartVibration()"))
        assertTrue(callback.contains("updateGatewayVoiceStatus("))
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
        val terminalHandler = functionBlock("private fun handleHandsFreeVoiceTerminalFailure")
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
                "updateNavigationStatus(\"voice_hands_free=internal_restart_\${failure.statusToken}\")",
                "scheduleHandsFreeVoiceRestart()",
                "return",
                "onDeviceSpeechRecognitionCapabilityOverride = false",
                "refreshStartupCapabilityUi()",
            ),
        )
        assertTrue(mainActivity.contains("const val HANDS_FREE_TRANSCRIBER_PERSISTENT_FAILURES = 2"))
        assertTrue(mainActivity.contains("private var handsFreeTranscriberFailureCount = 0"))

        // The streak restarts whenever command listening starts again.
        val listeningStarted = functionBlock("private fun handleHandsFreeVoiceCommandListeningStarted")
        assertTrue(listeningStarted.contains("handsFreeTranscriberFailureCount = 0"))
        // Every place that clears the device-check STT override also clears the streak.
        val clearedOverrides = Regex("onDeviceSpeechRecognitionCapabilityOverride = null").findAll(mainActivity).count()
        val clearedWithStreak = Regex(
            "onDeviceSpeechRecognitionCapabilityOverride = null\\s+handsFreeTranscriberFailureCount = 0",
        ).findAll(mainActivity).count()
        assertTrue("override clears=$clearedOverrides", clearedOverrides >= 4)
        assertEquals(clearedOverrides, clearedWithStreak)
    }

    @Test
    fun restoredWakePhraseTestMissStaysAHandsFreeFeatureRestriction() {
        // S14-16-b: a stored HANDS_FREE_VOICE restriction (wake-word test miss) is not evidence that
        // the on-device STT engine is unavailable, so a restart does not turn it into a walk block.
        val bind = functionBlock("private fun bindPostLoginDeviceCheckSession")
        assertTrue(bind.contains("if (restored != null) {"))
        val restoredOverrides = bind
            .substringAfter("runtimeObstacleDetectionCapabilityOverride = if (")
            .substringBefore("offlineKoreanTextToSpeechCapabilityOverride = if (")
        assertTrue(restoredOverrides.contains("onDeviceSpeechRecognitionCapabilityOverride = null"))
        assertFalse(restoredOverrides.contains("HANDS_FREE_VOICE"))
        assertFalse(bind.contains("onDeviceSpeechRecognitionCapabilityOverride = if ("))
        assertFalse(
            Regex("HANDS_FREE_VOICE in restored\\.disabledFeatures\\s*\\)\\s*\\{\\s*false").containsMatchIn(bind),
        )
        // In-session the same restriction is a feature restriction only, never an STT override.
        val restrictions = functionBlock("private fun applyPostLoginDeviceFeatureRestrictions")
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
