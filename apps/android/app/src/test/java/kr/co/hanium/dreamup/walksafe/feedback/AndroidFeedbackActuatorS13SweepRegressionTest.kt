package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S13 counterexamples against the real actuator state machine. Only platform effects are faked:
 * the engine queue, audio focus, progress tone and vibrator.
 */
class AndroidFeedbackActuatorS13SweepRegressionTest {
    @Test
    fun expiredOrRevokedRiskIsBlockedOnBothSpeechAndVibrationOnlyPaths() {
        val (runtime, actuator) = readyActuator()
        val expired = action(MessageLevel.STOP, deadline = runtime.now - 1L)

        val emitted = actuator.emit(expired, null, null, isStillValidAtStart = { true })
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, emitted.speech)
        assertFalse(emitted.vibrationAccepted)
        assertFalse(actuator.vibrateRiskOnly(expired) { true })

        // A live lease is still refused when the walk session or current risk no longer matches.
        val revoked = action(MessageLevel.STOP, deadline = runtime.now + 5_000L)
        val revokedEmit = actuator.emit(revoked, null, null, isStillValidAtStart = { false })
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, revokedEmit.speech)
        assertFalse(revokedEmit.vibrationAccepted)
        assertFalse(actuator.vibrateRiskOnly(revoked) { false })

        assertTrue(runtime.spoken.isEmpty())
        assertTrue(runtime.vibrations.isEmpty())
    }

    @Test
    fun unavailableSpeechDoesNotBlockAValidRiskVibration() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { true })
        assertTrue(actuator.isSpeechInitializing())

        val dispatch = actuator.emit(
            action(MessageLevel.STOP, deadline = runtime.now + 5_000L),
            null,
            null,
            isStillValidAtStart = { true },
        )

        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, dispatch.speech)
        assertTrue(dispatch.vibrationAccepted)
        assertEquals(listOf(VibrationPatterns.forLevel(MessageLevel.STOP)!!.toList()), runtime.vibrations)
    }

    @Test
    fun closeRejectsLateVibrationToneAndSpeechWithoutRecreatingResources() {
        val (runtime, actuator) = readyActuator()
        assertTrue(actuator.vibrateRiskOnly(action(MessageLevel.STOP, runtime.now + 5_000L)) { true })
        actuator.playProgressBeep(40)
        assertEquals(1, runtime.toneCreates)
        val focusRequestsBeforeClose = runtime.focusRequests

        actuator.close()
        assertEquals(1, runtime.vibrationCancels)
        runtime.advanceBy(1_000L)

        // Delayed risk callback, then delayed route progress callback.
        assertFalse(actuator.vibrateRiskOnly(action(MessageLevel.STOP, runtime.now + 5_000L)) { true })
        val lateEmit = actuator.emit(
            action(MessageLevel.WARNING, runtime.now + 5_000L),
            null,
            null,
            isStillValidAtStart = { true },
        )
        assertNotEquals(NavigationSpeechDispatchResult.ACCEPTED, lateEmit.speech)
        assertFalse(lateEmit.vibrationAccepted)
        actuator.playProgressBeep(40)
        actuator.playProgressBeep(60)
        assertFalse(actuator.playVoiceListeningStartVibration())
        assertFalse(actuator.playVoiceListeningEndVibration())
        assertFalse(actuator.playRouteGuidancePausedVibration())
        assertFalse(actuator.playRouteDeviationConfirmedVibration())
        assertFalse(actuator.playPhoneMountingSafetyStopVibration())
        assertFalse(actuator.playPriorityUserTrainingVibration({}, {}))
        assertNotEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("late route"))
        runtime.advanceBy(20_000L)

        assertEquals(1, runtime.vibrations.size)
        assertEquals(1, runtime.toneCreates)
        assertEquals(focusRequestsBeforeClose, runtime.focusRequests)
        assertTrue(runtime.spoken.isEmpty())
        assertTrue(runtime.grantedFocus.isEmpty())
        assertEquals("tts=closed", actuator.statusText())
    }

    @Test
    fun lowerPriorityCuesCannotOverwriteAStopWaveformUntilItEnds() {
        val (runtime, actuator) = readyActuator()
        val stop = VibrationPatterns.forLevel(MessageLevel.STOP)!!
        assertTrue(actuator.vibrateRiskOnly(action(MessageLevel.STOP, runtime.now + 5_000L)) { true })

        runtime.advanceBy(100L)
        assertFalse(actuator.playVoiceListeningStartVibration())
        assertFalse(actuator.playVoiceListeningEndVibration())
        assertFalse(actuator.playRouteGuidancePausedVibration())
        assertFalse(actuator.playPhoneMountingCorrectionVibration())
        assertFalse(actuator.playPriorityUserTrainingVibration({}, {}))
        assertFalse(actuator.vibrateRiskOnly(action(MessageLevel.WARNING, runtime.now + 5_000L)) { true })
        assertEquals(1, runtime.vibrations.size)

        runtime.advanceBy(stop.sum() - 100L)
        assertTrue(actuator.playVoiceListeningStartVibration())
        assertEquals(listOf(stop.toList(), listOf(0L, 35L)), runtime.vibrations)
    }

    @Test
    fun patternlessRequestsDoNotCancelTrainingButARealRiskWaveformDoes() {
        val (runtime, actuator) = readyActuator()
        var elapsed = 0
        var failed = 0
        assertTrue(actuator.playPriorityUserTrainingVibration({ elapsed += 1 }, { failed += 1 }))

        val caution = action(MessageLevel.CAUTION, runtime.now + 5_000L)
        assertNull(caution.vibrationPatternMs)
        assertFalse(actuator.emit(caution, null, null, isStillValidAtStart = { true }).vibrationAccepted)
        assertFalse(actuator.vibrateRiskOnly(caution) { true })
        assertFalse(actuator.vibrateRiskOnly(action(MessageLevel.INFO, runtime.now + 5_000L)) { true })
        runtime.advanceBy(PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS)
        assertEquals(1, elapsed)
        assertEquals(0, failed)

        runtime.advanceBy(1_000L)
        assertTrue(actuator.playPriorityUserTrainingVibration({ elapsed += 1 }, { failed += 1 }))
        assertTrue(actuator.vibrateRiskOnly(action(MessageLevel.WARNING, runtime.now + 5_000L)) { true })
        runtime.advanceBy(PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS)
        assertEquals(1, elapsed)
        assertEquals(1, failed)
    }

    @Test
    fun trainingAndCommandsCannotQueueBehindRiskSoTheirCancellationNeverStopsIt() {
        val (runtime, actuator) = readyActuator()
        val risk = Events()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitSpeech(actuator, runtime, "risk", risk))
        val riskId = runtime.spoken.single().id
        runtime.start(riskId)
        val training = Events()

        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakPriorityUserTraining("training", { training.completed += 1 }, { training.failed += 1 }),
        )
        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakInteraction("command", { training.completed += 1 }, { training.failed += 1 }),
        )
        val stops = runtime.stops
        actuator.cancelPriorityUserTrainingFeedback()
        actuator.cancelCommandInteraction()

        assertEquals(stops, runtime.stops)
        assertEquals(listOf(riskId), runtime.engineQueue)
        assertTrue(actuator.isAppSpeechActive())
        runtime.done(riskId)
        assertEquals(1, risk.completed)
        assertEquals(0, risk.failed + risk.cancelled)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(listOf("risk"), runtime.spoken.map { it.message })
        assertEquals(0, training.completed + training.failed)
    }

    @Test
    fun protectedTrainingReplacedByRiskLeavesNoGhostWhenASameRankRiskIsSuppressed() {
        val (runtime, actuator) = readyActuator()
        val training = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakPriorityUserTraining("training", { training.completed += 1 }, { training.failed += 1 }),
        )
        val trainingId = runtime.spoken.single().id
        val first = Events()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitSpeech(actuator, runtime, "first risk", first))
        val firstId = runtime.spoken.last().id
        assertEquals(1, training.failed)

        val second = Events()
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, emitSpeech(actuator, runtime, "second risk", second))
        assertEquals(listOf(firstId), runtime.engineQueue)

        runtime.done(trainingId)
        runtime.done(firstId)
        assertEquals(1, first.completed)
        assertEquals(0, second.completed + second.failed + second.cancelled)
        assertEquals(0, training.completed)
        assertEquals(1, training.failed)
        assertTrue(runtime.engineQueue.isEmpty())
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun failedRiskReleasesOnlyItsOwnDuplicateWindow() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitSpeech(actuator, runtime, "stop", events))
        val failedId = runtime.spoken.last().id
        runtime.error(failedId, TextToSpeech.ERROR_OUTPUT)
        assertEquals(1, events.failed)
        assertEquals("tts=ready", actuator.statusText())

        // The caller's immediate retry of an undelivered alert is not an already-heard duplicate.
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitSpeech(actuator, runtime, "stop", events))
        val retryId = runtime.spoken.last().id
        runtime.done(retryId)
        assertEquals(1, events.completed)

        // A late callback for the failed ID cannot erase the delivered retry's window.
        runtime.error(failedId, TextToSpeech.ERROR_OUTPUT)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, emitSpeech(actuator, runtime, "stop", events))
        runtime.advanceBy(800L)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitSpeech(actuator, runtime, "stop", events))
    }

    @Test
    fun initializationTimeInteractionIsNeitherOwnedNorReplayed() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        val events = Events()
        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakInteraction("old dialog", { events.completed += 1 }, { events.failed += 1 }),
        )
        assertFalse(actuator.speakInteraction("old screen"))
        assertFalse(actuator.isSpeechRecognitionBlocked())

        runtime.ready()
        runtime.advanceBy(20_000L)
        assertTrue(runtime.spoken.isEmpty())
        assertEquals(0, events.completed + events.failed)
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun throwingToneStopCannotSkipFocusLossCleanupOfPendingSpeech() {
        val (runtime, actuator) = readyActuator()
        actuator.playProgressBeep(40)
        runtime.advanceBy(200L)
        assertTrue(runtime.grantedFocus.isEmpty())
        val navigation = Events()
        acceptNavigation(actuator, "turn left", navigation)
        runtime.toneStopThrows = true

        runtime.grantedFocus.last().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        runtime.runCurrent()

        assertEquals(1, runtime.tones.single().stops)
        assertEquals(1, navigation.failed)
        assertFalse(actuator.isAppSpeechActive())
        assertTrue(runtime.grantedFocus.isEmpty())
        runtime.advanceBy(20_000L)
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.engineCount)
    }

    @Test
    fun throwingToneStopCannotSkipRecognitionPreparationFocusRelease() {
        val (runtime, actuator) = readyActuator()
        actuator.playProgressBeep(40)
        assertEquals(1, runtime.grantedFocus.size)
        runtime.toneStopThrows = true

        assertTrue(actuator.prepareForSpeechRecognition())

        assertEquals(1, runtime.tones.single().stops)
        assertTrue(runtime.grantedFocus.isEmpty())
    }

    @Test
    fun throwingToneStopCannotSkipExternalAnnouncementPreparation() {
        val (runtime, actuator) = readyActuator()
        actuator.playProgressBeep(40)
        runtime.advanceBy(200L)
        val navigation = Events()
        acceptNavigation(actuator, "turn right", navigation)
        runtime.toneStopThrows = true

        actuator.prepareForExternalRiskAnnouncement()

        assertEquals(1, navigation.cancelled)
        assertFalse(actuator.isAppSpeechActive())
        assertTrue(runtime.grantedFocus.isEmpty())
        // Duplicate suppression of the interrupted route cue was released as part of preparation.
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("turn right"))
    }

    @Test
    fun throwingVibratorIsARejectedRequestAndReservesNoPriority() {
        val (runtime, actuator) = readyActuator()
        runtime.vibrateThrows = true
        assertFalse(actuator.vibrateRiskOnly(action(MessageLevel.STOP, runtime.now + 5_000L)) { true })
        runtime.vibrateThrows = false
        assertTrue(actuator.playVoiceListeningStartVibration())
        assertEquals(listOf(listOf(0L, 35L)), runtime.vibrations)
    }

    private fun readyActuator(): Pair<FakeRuntime, AndroidFeedbackActuator> {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { true })
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        return runtime to actuator
    }

    private fun emitSpeech(
        actuator: AndroidFeedbackActuator,
        runtime: FakeRuntime,
        message: String,
        events: Events,
    ): NavigationSpeechDispatchResult = actuator.emit(
        action = FeedbackAction(
            trackId = "risk-$message",
            message = message,
            level = MessageLevel.WARNING,
            vibrationPatternMs = null,
            reason = "s13-sweep",
            validUntilMs = runtime.now + 20_000L,
        ),
        onSpeechCompleted = { events.completed += 1 },
        onSpeechFailed = { events.failed += 1 },
        isStillValidAtStart = { true },
        onSpeechCancelled = { events.cancelled += 1 },
    ).speech

    private fun acceptNavigation(actuator: AndroidFeedbackActuator, message: String, events: Events) {
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation(
                message = message,
                onCompleted = { events.completed += 1 },
                onFailed = { events.failed += 1 },
                onCancelled = { events.cancelled += 1 },
                requiresExplicitTerminalCallback = true,
            ),
        )
    }

    private fun action(level: MessageLevel, deadline: Long) = FeedbackAction(
        trackId = "risk-${level.name}",
        message = "risk ${level.name}",
        level = level,
        vibrationPatternMs = VibrationPatterns.forLevel(level),
        reason = "s13-sweep",
        validUntilMs = deadline,
    )

    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)

        inner class Tone : ProgressToneEffects {
            var stops = 0
            override fun startTone(toneType: Int, durationMs: Int): Boolean = true
            override fun stopTone() {
                stops += 1
                if (toneStopThrows) throw IllegalStateException("tone stop")
            }
            override fun release() = Unit
        }

        var now = 1_000L
        var engineCount = 0
        val spoken = mutableListOf<Spoken>()
        val engineQueue = mutableListOf<String>()
        val vibrations = mutableListOf<List<Long>>()
        var vibrateThrows = false
        var vibrationCancels = 0
        val tones = mutableListOf<Tone>()
        var toneCreates = 0
        var toneStopThrows = false
        var stops = 0
        var focusRequests = 0
        val grantedFocus = mutableListOf<AudioManager.OnAudioFocusChangeListener>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L

        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = engineCount > 0
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) {
            engineCount += 1
            initialized = onInitialized
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            if (queueMode == TextToSpeech.QUEUE_FLUSH) engineQueue.clear()
            engineQueue += utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            stops += 1
            engineQueue.clear()
        }
        override fun shutdown() = Unit
        override fun requestAudioFocus(
            isRisk: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Boolean {
            focusRequests += 1
            grantedFocus += listener
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
            grantedFocus.remove(listener)
        }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects {
            toneCreates += 1
            return Tone().also(tones::add)
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            if (vibrateThrows) throw IllegalStateException("vibrator")
            vibrations += patternMs.toList()
            return true
        }
        override fun cancelVibration() {
            vibrationCancels += 1
        }
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) {
            tasks.removeAll { it.runnable === task }
        }
        override fun removeCallbacksAndMessages(token: Any?) {
            tasks.clear()
        }
        fun ready() {
            checkNotNull(initialized)(TextToSpeech.SUCCESS)
            runCurrent()
        }
        fun start(id: String) {
            checkNotNull(listener).onStart(id)
            runCurrent()
        }
        fun done(id: String) {
            engineQueue.remove(id)
            checkNotNull(listener).onDone(id)
            runCurrent()
        }
        fun error(id: String, code: Int) {
            engineQueue.remove(id)
            checkNotNull(listener).onError(id, code)
            runCurrent()
        }
        fun runCurrent() = advanceBy(0L)
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}
