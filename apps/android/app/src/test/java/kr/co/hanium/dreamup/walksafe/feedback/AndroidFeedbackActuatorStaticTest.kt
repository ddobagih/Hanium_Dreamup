package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFeedbackActuatorStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    ).readText()

    @Test
    fun progressBeepReleasesTransientAudioFocusWithoutInterruptingSpeech() {
        val beep = source.substringAfter("fun playProgressBeep(volumePercent: Int)")
            .substringBefore("fun statusText()")

        assertTrue(beep.contains("pendingUtterances.isNotEmpty()"))
        assertTrue(beep.contains("if (speechPending || !requestAudioFocus(isRisk = false)) return"))
        assertTrue(beep.contains("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"))
        assertTrue(beep.contains("abandonAudioFocus()"))
        assertTrue(source.contains("mainHandler.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun speechRecognitionPreparationStopsNavigationButNotRiskSpeech() {
        val preparation = source.substringAfter("fun prepareForSpeechRecognition(): Boolean")
            .substringBefore("fun playProgressBeep(volumePercent: Int)")

        assertTrue(preparation.contains("ANNOUNCE_ASSERTIVE_PREFIX"))
        assertTrue(preparation.contains("if (riskPending) return false"))
        assertTrue(preparation.contains("textToSpeech.stop()"))
        assertTrue(preparation.contains("pendingUtterances.clear()"))
        assertTrue(preparation.contains("utteranceCallbacks.clear()"))
        assertTrue(preparation.indexOf("utteranceCallbacks.clear()") < preparation.indexOf("textToSpeech.stop()"))
        assertTrue(preparation.contains("lastNavMessage = \"\""))
    }

    @Test
    fun routeDeviationCancellationAlwaysStopsAndRetiresNavigationSpeech() {
        val cancellation = source.substringAfter("fun cancelNavigationSpeech(): Boolean")
            .substringBefore("fun speakInteraction(message: String)")
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")

        assertTrue(cancellation.contains("ANNOUNCE_NAV_PREFIX"))
        assertTrue(cancellation.contains("if (ttsState == TtsState.READY) textToSpeech.stop()"))
        assertTrue(cancellation.contains("it in explicitTerminalRequiredUtterances"))
        assertTrue(cancellation.contains("completed = false, notifyFailure = notifyFailure"))
        assertTrue(dispatch.contains("pendingIds.any { !it.startsWith(\"\$ANNOUNCE_ADVISORY_PREFIX-\") }"))
        assertTrue(dispatch.contains("return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(dispatch.contains("SpeechPriority.RISK -> TextToSpeech.QUEUE_FLUSH"))
    }

    @Test
    fun externalRiskAnnouncementStopsAppOwnedSpeechBeforeTalkBack() {
        val preparation = source.substringAfter("fun prepareForExternalRiskAnnouncement()")
            .substringBefore("fun speakNavigation(")

        assertTrue(preparation.contains("pendingUtterances.clear()"))
        assertTrue(preparation.contains("utteranceWatchdogs.clear()"))
        assertTrue(preparation.contains("textToSpeech.stop()"))
        assertTrue(preparation.contains("progressTone?.stopTone()"))
        assertTrue(preparation.contains("abandonAudioFocus()"))
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
        assertTrue(dispatch.contains("textToSpeech.stop()"))
        assertTrue(source.contains("isUtteranceStillValidAtStart(utteranceId)"))
        assertTrue(source.contains("utteranceStartDeadlines"))
        assertTrue(source.contains("utteranceStartValidators"))
        val startValidation = source.substringAfter("private fun isUtteranceStillValidAtStart(")
            .substringBefore("private fun armUtteranceTerminalWatchdog")
        assertTrue(startValidation.contains("markUtteranceFinished(utteranceId, completed = false)"))
        assertFalse(startValidation.contains("notifyFailure = false"))
        val cancellation = source.substringAfter("private fun cancelQueuedCompletionCallbacks(")
            .substringBefore("override fun close()")
        assertTrue(cancellation.contains("ANNOUNCE_ADVISORY_PREFIX"))
        assertTrue(cancellation.contains("takeTerminalCallback(it, completed = false, notifyFailure = true)"))
        assertTrue(cancellation.contains("advisoryFailures.forEach { it() }"))
    }

    @Test
    fun initializationQueuesSpeechAndExposesFailureStates() {
        val initialization = source.substringAfter("override fun onInit(status: Int)")
            .substringBefore("fun emit(action: FeedbackAction)")
        val speak = source.substringAfter("private fun speak(\n")
            .substringBefore("private fun flushPendingSpeech()")

        assertTrue(initialization.contains("TextToSpeech.LANG_MISSING_DATA"))
        assertTrue(initialization.contains("TextToSpeech.LANG_NOT_SUPPORTED"))
        assertTrue(initialization.contains("selectInstalledOfflineKoreanVoice(textToSpeech)"))
        assertTrue(initialization.substringAfter("KoreanOfflineVoiceSelection.UNAVAILABLE ->")
            .substringBefore("KoreanOfflineVoiceSelection.REJECTED ->").contains("failOfflineKoreanLanguage()"))
        assertTrue(initialization.substringAfter("KoreanOfflineVoiceSelection.REJECTED ->")
            .substringBefore("textToSpeech.setAudioAttributes(").contains("recoverTextToSpeechOrFail()"))
        assertTrue(initialization.contains("flushPendingSpeech()"))
        assertTrue(speak.contains("priority == SpeechPriority.INTERACTION"))
        assertTrue(speak.contains("pendingSpeechQueue.offer(message, priority, riskRank)"))
        assertTrue(speak.contains("onCompleted == null"))
        assertTrue(speak.contains("onFailed == null"))
        assertTrue(source.contains("onCompleted: (() -> Unit)?"))
        assertTrue(source.contains("TtsState.FAILED -> \"tts=init_failed\""))
        assertTrue(source.contains("TtsState.LANGUAGE_UNSUPPORTED -> \"tts=korean_unsupported\""))
    }

    @Test
    fun initializationStateExistsBeforeTextToSpeechCanCallBack() {
        val queueIndex = source.indexOf("private val pendingSpeechQueue = PendingSpeechQueue()")
        val stateIndex = source.indexOf("private var ttsState = TtsState.INITIALIZING")
        val handlerIndex = source.indexOf("private val mainHandler = Handler(Looper.getMainLooper())")
        val constructorIndex = source.indexOf("textToSpeech = TextToSpeech(appContext) { status ->")

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
        val dispatch = source.substringAfter("val speakResult = textToSpeech.speak")
            .substringBefore("if (isRisk)")
        assertTrue(dispatch.contains("failRequiredSpeechRuntime(utteranceId, notifyFailure = false)"))
        val runtimeFailure = source.substringAfter("private fun failRequiredSpeechRuntime(")
            .substringBefore("fun emit(")
        assertTrue(runtimeFailure.contains("ready.set(false)"))
        assertTrue(runtimeFailure.contains("ttsState = TtsState.FAILED"))
        assertTrue(runtimeFailure.contains("failPendingSpeech(interrupted, stopTts = false)"))
    }

    @Test
    fun riskDispatchSeparatesQueuedSpeechFromImmediateHapticDelivery() {
        val dispatch = source.substringAfter("fun emit(\n")
            .substringBefore("fun vibrateRiskOnly(action: FeedbackAction)")

        assertTrue(dispatch.contains("onCompleted = onSpeechCompleted"))
        assertTrue(dispatch.contains("onFailed = onSpeechFailed"))
        assertTrue(dispatch.contains("return RiskFeedbackDispatchResult(speech, vibrationAccepted)"))
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
        assertTrue(dispatch.contains("textToSpeech.speak(message, queueMode, params, utteranceId)"))
        assertTrue(dispatch.contains("if (!isRisk) return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(dispatch.contains("if (riskPending) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH"))
    }

    @Test
    fun featureRestrictionsSuppressOnlyTheirOwnOutputChannel() {
        val constructor = source.substringAfter("class AndroidFeedbackActuator(")
            .substringBefore(") : TextToSpeech.OnInitListener")
        val dispatch = source.substringAfter("fun emit(\n")
            .substringBefore("fun vibrateRiskOnly(action: FeedbackAction)")
        val speech = source.substringAfter("private fun speak(\n")
            .substringBefore("private fun flushPendingSpeech()")
        val queuedSpeech = source.substringAfter("private fun flushPendingSpeech()")
            .substringBefore("private fun vibrate(")
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
        assertTrue(queuedSpeech.contains("speechAllowed()"))
        assertTrue(vibration.contains("if (!hapticAllowed()) return false"))
        assertFalse(vibration.contains("speechAllowed"))
        assertTrue(
            vibration.indexOf("if (!hapticAllowed()) return false") <
                vibration.indexOf("cancelPriorityUserTrainingVibration(notifyFailure = true)"),
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
        assertTrue(sharedVibration.contains("VibrationEffect.createWaveform(pattern, -1)"))
    }

    @Test
    fun utteranceRegistrationPrecedesFocusRequestAndIdsHaveAtomicTieBreaker() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")
        val focusFailure = dispatch.substringAfter("if (!requestAudioFocus(isRisk)) {")
            .substringBefore("val speakResult")
        val speakFailure = dispatch.substringAfter("if (speakResult == TextToSpeech.ERROR) {")
            .substringBefore("if (isRisk)")

        assertTrue(source.contains("private val utteranceSequence = AtomicLong(0L)"))
        assertTrue(dispatch.contains("utteranceSequence.incrementAndGet()"))
        assertTrue(dispatch.contains("if (!requestAudioFocus(isRisk)) {"))
        assertTrue(dispatch.indexOf("markUtteranceStarted(") < dispatch.indexOf("requestAudioFocus(isRisk)"))
        assertTrue(focusFailure.contains("if (queueMode == TextToSpeech.QUEUE_FLUSH) textToSpeech.stop()"))
        assertTrue(speakFailure.contains("if (queueMode == TextToSpeech.QUEUE_FLUSH) textToSpeech.stop()"))
        assertTrue(dispatch.contains("notifyFailure = false"))
    }

    @Test
    fun navigationCompletionCommitsOnlyFromTtsDoneAndCloseCannotResurrectInitialization() {
        assertTrue(source.contains("if (ttsState == TtsState.CLOSED) return"))
        assertTrue(source.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(source.contains("markUtteranceFinished(utteranceId, completed = false)"))
        assertTrue(source.contains("utteranceCallbacks.takeTerminalCallback("))
        assertTrue(source.contains("terminalCallback?.invoke()"))
        assertTrue(source.contains("utteranceCallbacks.cancel(cancelled)"))
        assertTrue(source.contains("return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(source.contains("return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(source.contains("UTTERANCE_START_TIMEOUT_MS"))
        assertTrue(source.contains("utteranceTerminalTimeoutMs("))
        assertTrue(source.contains("utteranceWatchdogs[utteranceId] !== watchdog"))
        assertTrue(source.contains("utteranceWatchdogs.remove(utteranceId)"))
    }

    @Test
    fun audioFocusLossStopsOutputAndRiskFocusIsNeverDowngraded() {
        val focus = source.substringAfter("private fun requestAudioFocus(isRisk: Boolean): Boolean")
            .substringBefore("private fun handleAudioFocusChange")
        val loss = source.substringAfter("private fun handleAudioFocusChange")
            .substringBefore("private fun abandonAudioFocus")

        assertTrue(focus.contains("activeAudioFocus == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ||"))
        assertTrue(focus.contains("return requested"))
        assertTrue(loss.contains("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"))
        assertTrue(loss.contains("progressTone?.stopTone()"))
        assertTrue(loss.contains("audioFocusGeneration != generation"))
        assertTrue(loss.contains("releaseAudioFocusRegistration("))
        assertTrue(loss.contains("failPendingSpeech(interrupted"))
        assertTrue(source.contains("vibrator?.cancel()"))
    }

    @Test
    fun pendingRiskIsPreemptedOnlyByHigherSeverityAndQueuedDeadlinesStartAtQueueHead() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(patternMs: LongArray?)")
        val registration = source.substringAfter("private fun markUtteranceStarted(")
            .substringBefore("private fun armUtteranceTerminalWatchdog")

        assertTrue(dispatch.contains("activeRiskRank"))
        assertTrue(dispatch.contains("(riskRank ?: activeRiskRank) <= activeRiskRank"))
        assertTrue(registration.contains("pendingUtteranceOrder.add(utteranceId)"))
        assertTrue(registration.contains("armNextUtteranceStartWatchdogLocked()"))
        assertTrue(source.contains("val next = pendingUtteranceOrder.firstOrNull() ?: return"))
        assertTrue(source.contains("if (next in startedUtterances || next in utteranceWatchdogs) return"))
        assertTrue(source.contains("val predecessors = pendingUtteranceOrder.takeWhile { it != utteranceId }"))
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
        assertFalse(dispatch.contains("explicitTerminalRequiredUtterances.firstOrNull()"))
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
        assertTrue(stopped.contains("markUtteranceFinished(utteranceId, completed = false)"))
        assertTrue(inferred.contains("completed = false"))
        assertFalse(inferred.contains("completed = true"))
    }

    @Test
    fun commandCancellationStopsOnlyAnExclusivelyOwnedQueueAndNeverReportsCompletion() {
        val cancellation = source.substringAfter("fun cancelCommandInteraction()")
            .substringBefore("fun cancelPriorityUserTrainingFeedback()")
        val ownership = cancellation.indexOf("utteranceCallbacks.exclusiveCommandUtteranceIds(")
        val engineStop = cancellation.indexOf("textToSpeech.stop()")
        assertTrue(cancellation.contains("synchronized(pendingUtterances)"))
        assertTrue(cancellation.contains("!it.startsWith(\"\$ANNOUNCE_INTERACTION_PREFIX-\")"))
        assertTrue(ownership >= 0 && ownership < engineStop)
        assertTrue(cancellation.indexOf("if (owned.isEmpty()) return") < engineStop)
        assertTrue(cancellation.contains("explicitTerminalRequiredIds = explicitTerminalRequiredUtterances"))
        assertTrue(cancellation.contains("markUtteranceFinished(it, completed = false)"))
        assertFalse(cancellation.contains("completed = true"))
        assertFalse(cancellation.contains("close()"))
        assertFalse(cancellation.contains("pendingUtterances.clear()"))
        assertFalse(cancellation.contains("cancelPriorityUserTrainingFeedback()"))
    }

    @Test
    fun cancellableCommandsCannotShareTheRiskQueue() {
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(")
        val guard = "if (isCommandInteraction && riskPending) return NavigationSpeechDispatchResult.SUPPRESSED"
        assertTrue(dispatch.contains("isInteraction && requiresExplicitTerminalCallback &&"))
        assertTrue(dispatch.contains("!protectsFromFollowingSpeech"))
        assertTrue(dispatch.indexOf(guard) >= 0)
        assertTrue(dispatch.indexOf(guard) < dispatch.indexOf("val queueMode = when"))
        assertTrue(dispatch.indexOf(guard) < dispatch.indexOf("markUtteranceStarted("))
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
        val information = activity.substringAfter("private fun emitFeedbackAction(")
            .substringBefore("private fun isFeedbackActionStillDeliverable(")
            .substringAfter("val speech = actuator.speakNavigation(")

        assertTrue(navigation.contains("requiresExplicitTerminalCallback: Boolean = false"))
        assertTrue(navigation.contains("requiresExplicitTerminalCallback = requiresExplicitTerminalCallback"))
        assertTrue(navigation.contains("protectsFromFollowingSpeech = false"))
        assertTrue(information.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(information.contains("feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)"))
        for (cancellation in listOf(preparation, flush)) {
            assertTrue(cancellation.contains("explicitTerminalRequiredUtterances"))
            assertTrue(cancellation.contains("takeTerminalCallback(it, completed = false, notifyFailure = true)"))
        }
    }

}
