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
        assertTrue(cancellation.contains("completed = false, notifyFailure = false"))
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
        assertTrue(initialization.contains("!voice.isNetworkConnectionRequired"))
        assertTrue(initialization.contains("textToSpeech.setVoice(offlineKoreanVoice)"))
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
        val constructorIndex = source.indexOf("private val textToSpeech = TextToSpeech(appContext, this)")

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
        assertTrue(initialization.contains("failRequiredSpeechRuntime(utteranceId)"))
        val dispatch = source.substringAfter("val speakResult = textToSpeech.speak")
            .substringBefore("if (isRisk)")
        assertTrue(dispatch.contains("failRequiredSpeechRuntime(utteranceId)"))
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
}
