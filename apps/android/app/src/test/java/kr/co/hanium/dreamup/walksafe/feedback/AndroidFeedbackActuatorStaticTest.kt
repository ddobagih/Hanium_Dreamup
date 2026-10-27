package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFeedbackActuatorStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    ).readText()

    private val runtimeSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackSpeechRuntime.kt",
    ).readText()

    @Test
    fun productionAndInjectedEffectsUseTheSameNonNullableRuntime() {
        assertTrue(source.contains("onOfflineKoreanSpeechReady, AndroidFeedbackSpeechRuntime(context)"))
        assertTrue(source.contains("private val speechRuntime: FeedbackSpeechRuntime,"))
        assertFalse(source.contains("speechRuntime != null"))
        assertFalse(source.contains("speechRuntime == null"))
        assertFalse(source.contains("speechRuntime?."))
        assertFalse(source.contains("Build.VERSION"))
        assertFalse(source.contains("textToSpeech"))
    }

    @Test
    fun progressBeepReleasesTransientAudioFocusWithoutInterruptingSpeech() {
        val beep = source.substringAfter("fun playProgressBeep(volumePercent: Int)")
            .substringBefore("fun statusText()")

        assertTrue(beep.contains("pendingUtterances.isNotEmpty()"))
        assertTrue(beep.contains("if (speechPending || !requestAudioFocus(isRisk = false)) return"))
        assertTrue(beep.contains("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"))
        assertTrue(beep.contains("abandonProgressFocusIfOwned(attempt, focusGeneration)"))
        assertFalse(beep.contains("abandonAudioFocus()"))
        val ownedRelease = beep.substringAfter("private fun abandonProgressFocusIfOwned")
            .substringBefore("fun isSpeechInitializing")
        assertTrue(ownedRelease.contains("progressToneGeneration != attempt || pendingUtterances.isNotEmpty()"))
        assertTrue(ownedRelease.contains("takeAudioFocusRegistration("))
        assertTrue(ownedRelease.contains("expectedGeneration = focusGeneration"))
        assertTrue(ownedRelease.contains("expectedGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"))
        assertTrue(ownedRelease.contains("releaseAudioFocusRegistration(registration)"))
        assertTrue(source.contains("mainHandler.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun speechRecognitionPreparationPreservesAllPendingSpeechAndItsCallbacks() {
        val preparation = source.substringAfter("fun prepareForSpeechRecognition(): Boolean")
            .substringBefore("fun playProgressBeep(volumePercent: Int)")

        assertTrue(preparation.contains("if (isSpeechRecognitionBlocked())"))
        assertFalse(preparation.contains("stopSpeechEngine()"))
        assertFalse(preparation.contains("pendingSpeechQueue.clear()"))
        assertFalse(preparation.contains("pendingUtterances.drain()"))
        assertFalse(preparation.contains("utteranceCallbacks.clear()"))
        assertTrue(preparation.contains("progressTone?.stopTone()"))
        assertTrue(preparation.contains("abandonAudioFocus()"))
    }

    @Test
    fun routeDeviationCancellationAlwaysStopsAndRetiresNavigationSpeech() {
        val cancellation = source.substringAfter("fun cancelNavigationSpeech(): Boolean")
            .substringBefore("fun speakInteraction(message: String)")
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")

        assertTrue(cancellation.contains("ANNOUNCE_NAV_PREFIX"))
        val ownership = cancellation.substringAfter("val stillOwnsStop = synchronized(pendingUtterances) {")
            .substringBefore("if (stillOwnsStop) stopSpeechEngine()")
        assertTrue(cancellation.contains("synchronized(speechEngineOperationLock) {"))
        assertTrue(ownership.contains("ttsState == TtsState.READY"))
        assertTrue(ownership.contains("navigation.any { it in pendingUtterances }"))
        assertTrue(ownership.contains("pendingUtterances.all { it.startsWith(\"\$ANNOUNCE_NAV_PREFIX-\") }"))
        assertTrue(cancellation.contains("if (stillOwnsStop) stopSpeechEngine()"))
        val beforeOwnedStop = cancellation
            .substringAfter("synchronized(speechEngineOperationLock) {")
            .substringBefore("if (stillOwnsStop) stopSpeechEngine()")
        var lockDepth = 1
        beforeOwnedStop.forEach { character ->
            if (character == '{') lockDepth++
            if (character == '}') lockDepth--
        }
        assertTrue("navigation stop must stay inside its operation lock", lockDepth > 0)
        assertEquals(1, Regex("""\bstopSpeechEngine\(\)""").findAll(cancellation).count())
        assertTrue(cancellation.contains("pendingUtterances[it]?.requiresExplicitTerminalCallback == true"))
        assertTrue(cancellation.contains("completed = false, notifyFailure = notifyFailure"))
        assertTrue(dispatch.contains("pendingIds.any { !it.startsWith(\"\$ANNOUNCE_ADVISORY_PREFIX-\") }"))
        assertTrue(dispatch.contains("return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(dispatch.contains("SpeechPriority.RISK -> TextToSpeech.QUEUE_FLUSH"))
    }

    @Test
    fun externalRiskAnnouncementStopsAppOwnedSpeechBeforeTalkBack() {
        val preparation = source.substringAfter("fun prepareForExternalRiskAnnouncement()")
            .substringBefore("fun speakNavigation(")

        assertTrue(preparation.contains("pendingUtterances.drain()"))
        assertTrue(preparation.contains("pendingUtterances.drain().mapNotNull { it.watchdog }"))
        assertTrue(preparation.contains("stopSpeechEngine()"))
        assertTrue(preparation.contains("synchronized(speechEngineOperationLock) {"))
        assertTrue(preparation.indexOf("stopSpeechEngine()") < preparation.indexOf("watchdogs.forEach(mainHandler::removeCallbacks)"))
        assertTrue(preparation.contains("tone = detachProgressToneLocked()?.first"))
        assertTrue(preparation.contains("tone?.stopTone()"))
        assertTrue(preparation.contains("focusRegistration = retireAudioFocusRegistration()"))
        assertTrue(preparation.contains("releaseAudioFocusRegistration(it)"))
        assertFalse(preparation.contains("abandonAudioFocus()"))
    }

    @Test
    fun interactionFlushesNavigationButQueuesBehindRisk() {
        val dispatch = source.substringAfter("private fun speakReady(message: String, priority: SpeechPriority)")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")

        assertTrue(dispatch.contains("ANNOUNCE_ASSERTIVE_PREFIX"))
        assertTrue(dispatch.contains("if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH"))
        assertTrue(dispatch.contains("ANNOUNCE_INTERACTION_PREFIX"))
    }

    @Test
    fun cameraAdvisoryOnlyStartsWhenIdleAndNeverBlocksTheNextNavigationMessage() {
        val advisory = source.substringAfter("fun speakAdvisory(\n")
            .substringBefore("fun prepareForSpeechRecognition(): Boolean")
        val dispatch = source.substringAfter("private fun speakReady(\n")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")

        assertTrue(advisory.contains("SpeechPriority.ADVISORY"))
        assertTrue(advisory.contains("validUntilMs"))
        assertTrue(advisory.contains("isStillValid"))
        assertTrue(advisory.contains("onCompleted = onCompleted"))
        assertTrue(advisory.contains("onFailed = onFailed"))
        assertFalse(source.contains("fun vibrateAdvisory("))
        assertTrue(dispatch.contains("isAdvisory && synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }"))
        assertTrue(dispatch.contains("if (!isAdvisory && isDuplicateMessage"))
        assertTrue(dispatch.contains("SpeechPriority.ADVISORY -> TextToSpeech.QUEUE_ADD"))
        assertTrue(dispatch.contains("ANNOUNCE_ADVISORY_PREFIX"))
        assertTrue(dispatch.contains("pendingIds.any { !it.startsWith(\"\$ANNOUNCE_ADVISORY_PREFIX-\") }"))
        assertTrue(dispatch.contains("cancelQueuedCompletionCallbacks(keepRisk = false)"))
        assertTrue(dispatch.contains("stopSpeechEngine()"))
        assertTrue(source.contains("isUtteranceStillValidAtStart(utteranceId)"))
        assertTrue(source.contains("pendingUtterances[utteranceId]?.startDeadlineMs"))
        assertTrue(source.contains("pendingUtterances[utteranceId]?.startValidator"))
        val startValidation = source.substringAfter("private fun isUtteranceStillValidAtStart(")
            .substringBefore("private fun armUtteranceTerminalWatchdog")
        assertTrue(startValidation.contains("markUtteranceFinished(utteranceId, completed = false, cancelled = true)"))
        assertFalse(startValidation.contains("notifyFailure = false"))
        val cancellation = source.substringAfter("private fun cancelQueuedCompletionCallbacks(")
            .substringBefore("override fun close()")
        assertTrue(cancellation.contains("ANNOUNCE_ADVISORY_PREFIX"))
        assertTrue(cancellation.contains("takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true)"))
        val compactCancellation = cancellation.replace(Regex("\\s+"), "")
        listOf("advisoryFailures", "strictTrainingFailures").forEach { callbacks ->
            assertTrue(
                "$callbacks must isolate each consumer callback, not the entire cancellation batch",
                compactCancellation.contains(
                    "$callbacks.forEach{callback->runCatching{callback()}" +
                        ".onFailure{logSpeechEvent(\"terminal_callback_error\")}}",
                ),
            )
            assertFalse(compactCancellation.contains("$callbacks.forEach{it()}"))
        }
    }

    @Test
    fun initializationRejectsWithoutQueuingAndExposesFailureStates() {
        val initialization = source.substringAfter("override fun onInit(status: Int)")
            .substringBefore("fun emit(action: FeedbackAction)")
        val speak = source.substringAfter("private fun speak(\n")
            .substringBefore("private fun flushPendingSpeech()")

        assertTrue(initialization.contains("TextToSpeech.LANG_MISSING_DATA"))
        assertTrue(initialization.contains("TextToSpeech.LANG_NOT_SUPPORTED"))
        assertTrue(initialization.contains("speechRuntime.selectOfflineKoreanVoice()"))
        assertTrue(runtimeSource.contains("selectInstalledOfflineKoreanVoice(textToSpeech)"))
        assertTrue(initialization.substringAfter("KoreanOfflineVoiceSelection.UNAVAILABLE ->")
            .substringBefore("KoreanOfflineVoiceSelection.REJECTED ->").contains("failOfflineKoreanLanguage()"))
        assertTrue(initialization.substringAfter("KoreanOfflineVoiceSelection.REJECTED ->")
            .substringBefore("if (!configureSpeechAttributes())").contains("recoverTextToSpeechOrFail()"))
        assertTrue(initialization.contains("flushPendingSpeech()"))
        assertFalse(speak.contains("pendingSpeechQueue.offer("))
        assertTrue(speak.contains("return NavigationSpeechDispatchResult.UNAVAILABLE"))
        val flush = source.substringAfter("private fun flushPendingSpeech()")
            .substringBefore("private fun speakReady(")
        assertTrue(flush.contains("pendingSpeechQueue.clear()"))
        assertFalse(flush.contains("speakReady("))
        assertTrue(source.contains("onCompleted: (() -> Unit)?"))
        assertTrue(source.contains("TtsState.FAILED -> \"tts=init_failed\""))
        assertTrue(source.contains("TtsState.LANGUAGE_UNSUPPORTED -> \"tts=korean_unsupported\""))
    }

    @Test
    fun initializationStateExistsBeforeTextToSpeechCanCallBack() {
        val queueIndex = source.indexOf("private val pendingSpeechQueue = PendingSpeechQueue()")
        val stateIndex = source.indexOf("private var ttsState = TtsState.INITIALIZING")
        val handlerIndex = source.indexOf("private val mainHandler = speechRuntime.scheduler")
        val constructorIndex = source.indexOf("speechRuntime.initialize(initialized)")

        assertTrue(queueIndex >= 0)
        assertTrue(stateIndex > queueIndex)
        assertTrue(handlerIndex > stateIndex)
        assertTrue(constructorIndex > handlerIndex)
        assertTrue(constructorIndex > stateIndex)
    }

    @Test
    fun offlineKoreanTtsInitializationFailureNotifiesTheRuntimeGateOnce() {
        val initialization = source.substringAfter("override fun onInit(status: Int)")
            .substringBefore("fun emit(action: FeedbackAction)")

        assertTrue(source.contains("onOfflineKoreanSpeechUnavailable"))
        assertTrue(source.contains("speechUnavailableNotified.compareAndSet(false, true)"))
        assertTrue(initialization.contains("notifyOfflineKoreanSpeechUnavailable()"))
        assertTrue(initialization.contains("failRequiredSpeechRuntime(utteranceId, errorCode)"))
        val dispatch = source.substringAfter("val speakResult: Int? = try {")
            .substringBefore("if (isRisk)")
        assertTrue(dispatch.contains("failRequiredSpeechRuntime(utteranceId, notifyFailure = false)"))
        assertTrue(dispatch.contains("if (speakResult == TextToSpeech.ERROR) {"))
        val errorBranch = dispatch.substringAfter("if (speakResult == TextToSpeech.ERROR) {")
        val failureCall = errorBranch.indexOf("failRequiredSpeechRuntime(utteranceId, notifyFailure = false)")
        val terminalReturn = errorBranch.indexOf("return NavigationSpeechDispatchResult.UNAVAILABLE")
        assertTrue("TTS ERROR must return unavailable", terminalReturn >= 0)
        assertTrue("TTS ERROR must fail the runtime before returning", failureCall in 0 until terminalReturn)
        val runtimeFailure = source.substringAfter("private fun failRequiredSpeechRuntime(")
            .substringBefore("fun emit(")
        assertTrue(runtimeFailure.contains("ready.set(false)"))
        assertTrue(runtimeFailure.contains("ttsState = TtsState.FAILED"))
        assertTrue(runtimeFailure.contains("failPendingSpeech(interrupted, stopTts = false)"))
    }

    @Test
    fun riskDispatchSeparatesQueuedSpeechFromImmediateHapticDelivery() {
        val dispatch = source.substringAfter("fun emit(\n")
            .substringBefore("fun vibrateRiskOnly(")

        // Completion now wraps the callback to request a fresh distance afterwards.
        assertTrue(dispatch.contains("onSpeechCompleted?.invoke()"))
        assertTrue(dispatch.indexOf("onSpeechCompleted?.invoke()") < dispatch.indexOf("distanceAfterWarning?.invoke()"))
        assertTrue(dispatch.contains("onFailed = onSpeechFailed"))
        assertTrue(dispatch.contains("return RiskFeedbackDispatchResult(speech, vibrationAccepted)"))
    }

    @Test
    fun riskOutputRequiresAnExplicitValidator() {
        val emitSignature = source.substringAfter("fun emit(")
            .substringBefore("): RiskFeedbackDispatchResult")
        val vibrationSignature = source.substringAfter("fun vibrateRiskOnly(")
            .substringBefore("): Boolean")

        assertTrue(emitSignature.contains("isStillValidAtStart: () -> Boolean,"))
        assertFalse(emitSignature.contains("isStillValidAtStart: () -> Boolean ="))
        assertTrue(vibrationSignature.contains("isStillValidAtStart: () -> Boolean"))
        assertFalse(vibrationSignature.contains("isStillValidAtStart: () -> Boolean ="))
    }

    @Test
    fun explicitConsentRequiresAReadyOfflineVoiceAndTrackedCompletion() {
        val consent = source.substringAfter("fun speakConsentClause(")
            .substringBefore("fun speakPriorityUserTraining(")

        assertTrue(consent.contains("if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(consent.contains("if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(consent.indexOf("ttsState != TtsState.READY") < consent.indexOf("return speakReady("))
        assertTrue(consent.contains("priority = SpeechPriority.INTERACTION"))
        assertTrue(consent.contains("onCompleted = onCompleted"))
        assertTrue(consent.contains("onFailed = onFailed"))
        assertTrue(consent.contains("requiresExplicitTerminalCallback = true"))
        assertFalse(consent.contains("speechAllowed()"))
        assertFalse(consent.contains("pendingSpeechQueue.offer"))
        assertFalse(consent.contains("onCompleted()"))
        assertFalse(consent.contains("textToSpeech.speak("))
    }

    @Test
    fun consentExceptionDoesNotBypassTheOrdinaryOrTrainingSpeechGate() {
        val ordinarySpeech = source.substringAfter("private fun speak(\n")
            .substringBefore("private fun flushPendingSpeech()")
        val training = source.substringAfter("fun speakPriorityUserTraining(")
            .substringBefore("fun speakAdvisory(")
        val dispatch = source.substringAfter("private fun speakReady(\n")
            .substringBefore("private fun vibrate(")

        assertTrue(ordinarySpeech.contains("if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(training.contains("): NavigationSpeechDispatchResult = speak("))
        assertFalse(training.contains("speakReady("))
        assertTrue(dispatch.contains("if (!requestAudioFocus(isRisk))"))
        assertTrue(dispatch.contains("speechRuntime.speak(message, queueMode, utteranceId)"))
        assertTrue(runtimeSource.contains("textToSpeech.speak(message, queueMode, params, utteranceId)"))
        assertTrue(dispatch.contains("if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(dispatch.contains("if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH"))
    }

    @Test
    fun featureRestrictionsSuppressOnlyTheirOwnOutputChannel() {
        val constructor = source.substringAfter("class AndroidFeedbackActuator private constructor(")
            .substringBefore(") : TextToSpeech.OnInitListener")
        val dispatch = source.substringAfter("fun emit(\n")
            .substringBefore("fun vibrateRiskOnly(")
        val speech = source.substringAfter("private fun speak(\n")
            .substringBefore("private fun flushPendingSpeech()")
        val queuedSpeech = source.substringAfter("private fun flushPendingSpeech()")
            .substringBefore("private fun speakReady(")
        val vibration = source.substringAfter("private fun vibrate(\n")
            .substringBefore("private fun cancelPriorityUserTrainingVibration(")

        assertTrue(constructor.contains("speechAllowed: () -> Boolean = { true }"))
        assertTrue(constructor.contains("hapticAllowed: () -> Boolean = { true }"))
        assertTrue(
            speech.contains(
                "if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE",
            ),
        )
        assertFalse(speech.contains("hapticAllowed"))
        assertTrue(queuedSpeech.contains("pendingSpeechQueue.clear()"))
        assertFalse(queuedSpeech.contains("speakReady("))
        assertTrue(vibration.contains("if (!hapticAllowed()) return false"))
        assertFalse(vibration.contains("speechAllowed"))
        assertTrue(
            vibration.indexOf("if (!hapticAllowed()) return false") <
                vibration.indexOf("interruptedTraining = takePriorityUserTrainingVibration()"),
        )
        assertTrue(
            vibration.indexOf("val accepted = outputGate.vibrate(") <
                vibration.indexOf("interruptedTraining?.second?.invoke()"),
        )
        assertTrue(
            dispatch.indexOf("val speech = speak(") <
                dispatch.indexOf("val vibrationAccepted = vibrate("),
        )
        assertFalse(
            dispatch.substringAfter("val speech = speak(")
                .substringBefore("val vibrationAccepted = vibrate(")
                .contains("return RiskFeedbackDispatchResult"),
        )
    }

    @Test
    fun voiceListeningSignalsUseDistinctShortHapticsThroughTheSharedGate() {
        val start = source.substringAfter("fun playVoiceListeningStartVibration()")
            .substringBefore("fun playVoiceListeningEndVibration()")
        val end = source.substringAfter("fun playVoiceListeningEndVibration()")
            .substringBefore("fun playRouteGuidancePausedVibration()")
        val sharedVibration = source.substringAfter("private fun vibrate(")
            .substringBefore("private fun cancelPriorityUserTrainingVibration(")

        assertTrue(start.contains("vibrate(longArrayOf(0L, 35L))"))
        assertTrue(end.contains("vibrate(longArrayOf(0L, 35L, 45L, 35L))"))
        assertFalse(start.contains("longArrayOf(0L, 120L)"))
        assertFalse(start.contains("longArrayOf(0L, 220L)"))
        assertFalse(start.contains("currentVibrator.vibrate"))
        assertFalse(end.contains("currentVibrator.vibrate"))
        assertTrue(sharedVibration.contains("if (!hapticAllowed()) return false"))
    }

    @Test
    fun phoneMountingCorrectionUsesAShortNonRepeatingCueThroughTheSharedVibrationGate() {
        val correction = source.substringAfter("fun playPhoneMountingCorrectionVibration()")
            .substringBefore("fun playPhoneMountingSafetyStopVibration()")
        val safetyStop = source.substringAfter("fun playPhoneMountingSafetyStopVibration()")
            .substringBefore("fun playPriorityUserTrainingVibration(")
        val sharedVibration = source.substringAfter("private fun vibrate(")
            .substringBefore("private fun cancelPriorityUserTrainingVibration(")

        assertTrue(correction.contains("vibrate(longArrayOf(0L, 120L))"))
        assertFalse(correction.contains("VibrationPatterns.forLevel"))
        assertFalse(correction.contains("currentVibrator.vibrate"))
        assertFalse(correction.contains("mainHandler.postDelayed"))
        assertTrue(
            safetyStop.contains(
                "VibrationPatterns.forLevel(MessageLevel.STOP)",
            ),
        )
        assertFalse(safetyStop.contains("longArrayOf(0L, 120L)"))
        assertTrue(sharedVibration.contains("if (!hapticAllowed()) return false"))
        assertTrue(sharedVibration.contains("outputGate.vibrate(patternMs, rank, generation, validUntilMs, isStillValid,"))
        assertTrue(sharedVibration.contains("speechRuntime.vibrate(pattern.copyOf())"))
        assertTrue(source.contains("onOfflineKoreanSpeechReady, AndroidFeedbackSpeechRuntime(context)"))
        val sdkVibration = MainActivityRefactorSource.member(runtimeSource, "vibrate")
        assertTrue(sdkVibration.contains("VibrationEffect.createWaveform(patternMs, -1)"))
    }

    @Test
    fun utteranceRegistrationPrecedesFocusRequestAndIdsHaveAtomicTieBreaker() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")
        val focusFailure = dispatch.substringAfter("if (!requestAudioFocus(isRisk)) {")
            .substringBefore("val speakResult")
        val speakFailure = dispatch.substringAfter("if (speakResult == TextToSpeech.ERROR) {")
            .substringBefore("if (isRisk)")
        val abortedSubmission = source.substringAfter("private fun finishUnsubmittedUtterance(")
            .substringBefore("private fun vibrate(")
        val abortedEngineOperation = abortedSubmission
            .substringAfter("val retiredReplacement = synchronized(speechEngineOperationLock) {")
            .substringBefore("// Watchdog/focus effects")

        assertTrue(source.contains("private val utteranceSequence = AtomicLong(0L)"))
        assertTrue(dispatch.contains("utteranceSequence.incrementAndGet()"))
        assertTrue(dispatch.contains("if (!requestAudioFocus(isRisk)) {"))
        assertTrue(dispatch.indexOf("markUtteranceStarted(") < dispatch.indexOf("requestAudioFocus(isRisk)"))
        assertTrue(focusFailure.contains("finishUnsubmittedUtterance(utteranceId, queueMode, dispatchEngineGeneration,"))
        assertTrue(focusFailure.contains("dispatchOutputGeneration, riskDeliveryOwner,"))
        assertTrue(focusFailure.contains("stopCurrentRequest = submissionOwnerIsCurrent()"))
        assertTrue(abortedEngineOperation.contains("pendingUtterances.size == 1 && utteranceId in pendingUtterances"))
        assertTrue(abortedEngineOperation.contains("speechEngineOwnerIsCurrent(engineGeneration)"))
        assertTrue(abortedEngineOperation.contains("registration.owner === riskDeliveryOwner"))
        assertTrue(abortedEngineOperation.contains("registration.engineGeneration == engineGeneration"))
        assertTrue(abortedEngineOperation.contains("registration.outputGeneration == outputGeneration"))
        assertTrue(abortedEngineOperation.contains("outputCurrent && !speechEngineStopInProgress"))
        assertTrue(abortedEngineOperation.contains("queueMode == TextToSpeech.QUEUE_FLUSH && stillOwnsStop && (stopCurrentRequest || retired)"))
        assertTrue(abortedEngineOperation.contains("stopSpeechEngine()"))
        assertTrue(abortedSubmission.indexOf("outputGate.isCurrent(outputGeneration)") <
            abortedSubmission.indexOf("synchronized(speechEngineOperationLock)"))
        assertFalse(abortedEngineOperation.contains("outputGate."))
        assertFalse(abortedEngineOperation.contains("markUtteranceFinished("))
        assertTrue(abortedSubmission.substringAfter("// Watchdog/focus effects")
            .contains("markUtteranceFinished(utteranceId, completed = false, notifyFailure = false,"))
        assertTrue(abortedSubmission.contains("cancelled = retiredReplacement"))
        assertTrue(speakFailure.contains("if (queueMode == TextToSpeech.QUEUE_FLUSH) stopSpeechEngine()"))
        assertTrue(dispatch.contains("notifyFailure = false"))
    }

    @Test
    fun navigationCompletionCommitsOnlyFromTtsDoneAndCloseCannotResurrectInitialization() {
        assertTrue(source.contains("if (ttsState == TtsState.CLOSED) return"))
        assertTrue(source.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(source.contains("markUtteranceFinished(utteranceId, completed = false, cancelled = true)"))
        assertTrue(source.contains("utteranceCallbacks.takeTerminalCallback("))
        assertTrue(source.contains("terminalCallback?.invoke()"))
        assertTrue(source.contains("utteranceCallbacks.cancel(cancelled)"))
        assertTrue(source.contains("return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(source.contains("return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(source.contains("UTTERANCE_START_TIMEOUT_MS"))
        assertTrue(source.contains("utteranceTerminalTimeoutMs("))
        assertTrue(source.contains("pendingUtterances[utteranceId]?.watchdog !== watchdog"))
        assertTrue(source.contains("pendingUtterances.replaceWatchdog(utteranceId, null)"))
    }

    @Test
    fun audioFocusLossStopsOutputAndRiskFocusIsNeverDowngraded() {
        val focus = source.substringAfter("private fun requestAudioFocus(isRisk: Boolean): Boolean")
            .substringBefore("private fun handleAudioFocusChange")
        val loss = source.substringAfter("private fun handleAudioFocusChange")
            .substringBefore("private fun abandonAudioFocus")

        assertTrue(focus.contains("activeAudioFocus == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ||"))
        assertTrue(focus.contains("val reservedGeneration = ++audioFocusGeneration"))
        assertTrue(focus.contains("speechRuntime.requestAudioFocus(isRisk, listener)"))
        assertTrue(runtimeSource.contains("manager.requestAudioFocus(request)"))
        assertTrue(focus.indexOf("val reservedGeneration = ++audioFocusGeneration") <
            focus.indexOf("speechRuntime.requestAudioFocus(isRisk, listener)"))
        assertTrue(focus.contains("if (!outputCurrent || audioFocusGeneration != generation)"))
        assertTrue(focus.contains("if (!accepted) releaseAudioFocusRegistration(listener)"))
        assertTrue(focus.contains("return accepted"))
        assertFalse(focus.contains("abandonAudioFocus()"))
        assertTrue(loss.contains("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"))
        assertTrue(loss.contains("if (toneGeneration == progressToneGeneration) progressTone else null"))
        assertTrue(loss.contains("tone?.stopTone()"))
        assertTrue(loss.indexOf("toneGeneration == progressToneGeneration") < loss.indexOf("tone?.stopTone()"))
        assertTrue(loss.contains("audioFocusGeneration != generation"))
        assertTrue(loss.contains("releaseAudioFocusRegistration("))
        assertTrue(loss.contains("failPendingSpeech(interrupted"))
        assertTrue(source.contains("speechRuntime.cancelVibration()"))
        assertTrue(runtimeSource.contains("vibrator?.cancel()"))
    }

    @Test
    fun pendingRiskIsPreemptedOnlyByHigherSeverityAndQueuedDeadlinesStartAtQueueHead() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")
        val registration = source.substringAfter("private fun markUtteranceStarted(")
            .substringBefore("private fun armUtteranceTerminalWatchdog")

        assertTrue(dispatch.contains("activeRiskRank"))
        assertTrue(dispatch.contains("(riskRank ?: activeRiskRank) <= activeRiskRank"))
        assertTrue(registration.contains("pendingUtterances.reserve(UtteranceRecord("))
        assertTrue(registration.contains("armNextUtteranceStartWatchdogLocked()"))
        assertTrue(source.contains("val record = pendingUtterances.firstRecord() ?: return"))
        assertTrue(source.contains("if (record.started || record.watchdog != null) return"))
        assertTrue(source.contains("val predecessors = pendingUtterances.detachPredecessors(utteranceId)"))
        assertTrue(source.contains("completed = true"))
    }

    @Test
    fun homeCommandResponsesRequireTerminalCallbacksButDoNotLockFollowingResults() {
        val home = source.substringAfter("fun speakHomeCommandInteraction(")
            .substringBefore("fun speakInteraction(message: String)")
        val trackedInteraction = source.substringAfter("fun speakInteraction(\n")
            .substringBefore("fun speakExplicitConfirmation(")
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(")
        val training = source.substringAfter("fun speakPriorityUserTraining(")
            .substringBefore("fun speakAdvisory(")
        val cancelTraining = source.substringAfter("fun cancelPriorityUserTrainingFeedback()")
            .substringBefore("fun prepareForExternalRiskAnnouncement()")
        assertTrue(home.contains("onCompleted = onCompleted"))
        assertTrue(home.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(home.contains("protectsFromFollowingSpeech = false"))
        assertTrue(trackedInteraction.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(trackedInteraction.contains("protectsFromFollowingSpeech = false"))
        assertTrue(dispatch.contains("protectsFromFollowingSpeech: Boolean = requiresExplicitTerminalCallback"))
        assertTrue(dispatch.contains("utteranceCallbacks.protectedUtteranceIds().firstOrNull()"))
        assertFalse(dispatch.contains("pendingUtterances.explicitTerminalIds().firstOrNull()"))
        assertTrue(training.contains("requiresExplicitTerminalCallback = true"))
        assertFalse(training.contains("protectsFromFollowingSpeech = false"))
        assertTrue(cancelTraining.contains("utteranceCallbacks.protectedUtteranceIds()"))
        assertTrue(dispatch.contains("if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED"))
    }

    @Test
    fun commandCompletionIsStillOnlyRealDoneAndInterruptedSpeechIsNotSuccess() {
        val start = source.substringAfter("override fun onStart(").substringBefore("override fun onDone(")
        val done = source.substringAfter("override fun onDone(").substringBefore("@Deprecated")
        val stopped = source.substringAfter("override fun onStop(").substringBefore("if (listenerResult")
        val inferred = source.substringAfter("private fun armUtteranceTerminalWatchdog(")
            .substringBefore("private fun scheduleUtteranceWatchdogLocked(")
        assertFalse(start.contains("completed = true"))
        assertTrue(done.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(stopped.contains("markUtteranceFinished(utteranceId, completed = false, cancelled = true)"))
        assertTrue(inferred.contains("completed = false"))
        assertFalse(inferred.contains("completed = true"))
    }

    @Test
    fun commandCancellationStopsOnlyAnExclusivelyOwnedQueueAndNeverReportsCompletion() {
        val cancellation = source.substringAfter("fun cancelCommandInteraction()")
            .substringBefore("fun cancelPriorityUserTrainingFeedback()")
        val ownership = cancellation.indexOf("utteranceCallbacks.exclusiveCommandUtteranceIds(")
        val engineStop = cancellation.indexOf("stopSpeechEngine()")
        assertTrue(cancellation.contains("synchronized(pendingUtterances)"))
        assertTrue(cancellation.contains("!it.startsWith(\"\$ANNOUNCE_INTERACTION_PREFIX-\")"))
        assertTrue(ownership >= 0 && ownership < engineStop)
        assertTrue(cancellation.indexOf("if (owned.isEmpty()) return") < engineStop)
        assertTrue(cancellation.contains("explicitTerminalRequiredIds = pendingUtterances.explicitTerminalIds()"))
        assertTrue(cancellation.contains("markUtteranceFinished(it, completed = false)"))
        assertFalse(cancellation.contains("completed = true"))
        assertFalse(cancellation.contains("close()"))
        assertFalse(cancellation.contains("pendingUtterances.drain()"))
        assertFalse(cancellation.contains("cancelPriorityUserTrainingFeedback()"))
    }

    @Test
    fun cancellableCommandsCannotShareTheRiskQueue() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(")
        val admission = dispatch.substringAfter("val cancellationEffects = synchronized(speechEngineOperationLock) {")
            .substringBefore("cancellationEffects?.invoke()")
        // The stronger guard excludes all interaction speech, including cancellable commands.
        val guard = "if (isInteraction && riskPending) return NavigationSpeechDispatchResult.SUPPRESSED"
        assertTrue(admission.indexOf(guard) >= 0)
        assertTrue(admission.indexOf(guard) < admission.indexOf("queueMode = when"))
        assertTrue(admission.indexOf(guard) < admission.indexOf("replacement = registerUtterance"))
        assertTrue(admission.indexOf(guard) < admission.indexOf("registerUtterance()"))
    }

    @Test
    fun claimedInformationFeedbackKeepsFailureOwnershipWhenNavigationIsInterrupted() {
        val navigation = source.substringAfter("fun speakNavigation(")
            .substringBefore("fun cancelNavigationSpeech()")
        val preparation = source.substringAfter("fun prepareForSpeechRecognition()")
            .substringBefore("fun playProgressBeep(")
        val flush = source.substringAfter("private fun cancelQueuedCompletionCallbacks(")
            .substringBefore("override fun close()")
        val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val feedback = MainActivityRefactorSource.function(activity, "private fun emitFeedbackAction(")
        val information = feedback.substringAfter("val speech = actuator.speakNavigation(")
        val cancellation = feedback.substringAfter("val cancelDelivery")
            .substringBefore("if (!outputCurrent())")

        assertTrue(navigation.contains("requiresExplicitTerminalCallback: Boolean = false"))
        assertTrue(navigation.contains("requiresExplicitTerminalCallback = requiresExplicitTerminalCallback"))
        assertTrue(navigation.contains("protectsFromFollowingSpeech = false"))
        assertTrue(information.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(information.contains("onFailed = cancelDelivery"))
        assertTrue(information.contains("onCancelled = cancelDelivery"))
        assertTrue(cancellation.contains("feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("feedbackPolicy.claimFeedbackDelivery(action, policyEvaluatedAtMs, scheduler.nowMs())"))
        assertTrue(cancellation.contains("pending?.action == action && pending.policyEvaluatedAtMs == policyEvaluatedAtMs"))
        assertTrue(preparation.contains("if (isSpeechRecognitionBlocked())"))
        assertFalse(preparation.contains("takeTerminalCallback("))
        assertTrue(flush.contains("pendingUtterances[it]?.requiresExplicitTerminalCallback == true"))
        assertTrue(flush.contains("takeTerminalCallback(it, completed = false, notifyFailure = true, cancelled = true)"))
        assertTrue(navigation.contains("onCancelled: (() -> Unit)? = null"))
    }

}
