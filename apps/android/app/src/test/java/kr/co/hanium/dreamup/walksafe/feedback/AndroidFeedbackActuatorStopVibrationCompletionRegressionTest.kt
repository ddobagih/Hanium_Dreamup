package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-3: repeated requests for one STOP event must not restart its unfinished waveform. */
class AndroidFeedbackActuatorStopVibrationCompletionRegressionTest {
    @Test
    fun sameStopEventCannotRestartBeforeCompletionWhenItsLeaseIsRefreshed() {
        val (runtime, actuator) = readyActuator()
        val action = stopAction()
        assertEquals(900L, checkNotNull(action.vibrationPatternMs).sum())
        assertTrue(actuator.vibrateRiskOnly(action) { true })

        runtime.advanceBy(800L)
        val repeated = actuator.vibrateRiskOnly(action.copy(validUntilMs = 20_000L)) { true }

        assertEquals("The original STOP is still playing until 1900ms", listOf(1_000L), runtime.vibrationTimes)
        assertFalse(repeated)
    }

    @Test
    fun sameStopEventCannotRestartThroughCombinedSpeechAndVibrationDispatch() {
        val (runtime, actuator) = readyActuator()
        val action = stopAction()
        val first = emit(actuator, action)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, first.speech)
        assertTrue(first.vibrationAccepted)

        runtime.advanceBy(800L)
        val repeated = emit(actuator, action)

        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, repeated.speech)
        assertEquals("Speech suppression must not restart the same STOP waveform", listOf(1_000L), runtime.vibrationTimes)
        assertFalse(repeated.vibrationAccepted)
    }

    @Test
    fun sameStopEventMayStartAgainAtTheOriginalCompletionBoundary() {
        val (runtime, actuator) = readyActuator()
        val action = stopAction()
        assertTrue(actuator.vibrateRiskOnly(action) { true })

        runtime.advanceBy(900L)

        assertTrue(actuator.vibrateRiskOnly(action) { true })
        assertEquals(listOf(1_000L, 1_900L), runtime.vibrationTimes)
    }

    @Test
    fun differentStopEventKeepsTheExistingSameRankBehavior() {
        val (runtime, actuator) = readyActuator()
        val first = stopAction()
        assertTrue(actuator.vibrateRiskOnly(first) { true })

        runtime.advanceBy(800L)

        assertTrue(actuator.vibrateRiskOnly(first.copy(trackId = "another-risk")) { true })
        assertEquals(listOf(1_000L, 1_800L), runtime.vibrationTimes)
    }

    @Test
    fun changedMessageKeepsTheExistingBehaviorForADifferentDeliveryKey() {
        val (runtime, actuator) = readyActuator()
        val first = stopAction()
        assertTrue(actuator.vibrateRiskOnly(first) { true })

        runtime.advanceBy(800L)

        assertTrue(actuator.vibrateRiskOnly(first.copy(message = "다른 방향의 위험입니다.")) { true })
        assertEquals(listOf(1_000L, 1_800L), runtime.vibrationTimes)
    }

    @Test
    fun higherRankStopMayPreemptAnUnfinishedWarning() {
        val (runtime, actuator) = readyActuator()
        val warning = stopAction().copy(
            level = MessageLevel.WARNING,
            vibrationPatternMs = VibrationPatterns.forLevel(MessageLevel.WARNING),
        )
        assertTrue(actuator.vibrateRiskOnly(warning) { true })

        runtime.advanceBy(100L)

        assertTrue(actuator.vibrateRiskOnly(stopAction()) { true })
        assertEquals(listOf(1_000L, 1_100L), runtime.vibrationTimes)
        assertEquals(listOf(220L, 900L), runtime.vibrations.map { it.sum() })
    }

    @Test
    fun lowerRankListeningCueWaitsUntilTheStopWaveformHasFinished() {
        val (runtime, actuator) = readyActuator()
        assertTrue(actuator.vibrateRiskOnly(stopAction()) { true })

        runtime.advanceBy(800L)
        assertFalse(actuator.playVoiceListeningStartVibration())
        assertEquals(listOf(1_000L), runtime.vibrationTimes)
        runtime.advanceBy(100L)

        assertTrue(actuator.playVoiceListeningStartVibration())
        assertEquals(listOf(1_000L, 1_900L), runtime.vibrationTimes)
    }

    @Test
    fun falseValidatorCannotStartEitherRiskVibrationEntryPoint() {
        val (runtime, actuator) = readyActuator()
        assertFalse(actuator.vibrateRiskOnly(stopAction()) { false })

        val dispatch = actuator.emit(stopAction(), null, null, isStillValidAtStart = { false })

        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, dispatch.speech)
        assertFalse(dispatch.vibrationAccepted)
        assertTrue(runtime.vibrationTimes.isEmpty())
        assertTrue(runtime.spoken.isEmpty())
    }

    @Test
    fun expiredLeaseCannotStartEitherRiskVibrationEntryPoint() {
        val (runtime, actuator) = readyActuator()
        val expired = stopAction().copy(validUntilMs = runtime.now - 1L)
        assertFalse(actuator.vibrateRiskOnly(expired) { true })

        val dispatch = emit(actuator, expired)

        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, dispatch.speech)
        assertFalse(dispatch.vibrationAccepted)
        assertTrue(runtime.vibrationTimes.isEmpty())
        assertTrue(runtime.spoken.isEmpty())
    }

    @Test
    fun closeRejectsLateRequestsEvenAfterTheDuplicateWindow() {
        val (runtime, actuator) = readyActuator()
        val action = stopAction()
        assertTrue(actuator.vibrateRiskOnly(action) { true })
        actuator.close()
        runtime.advanceBy(800L)

        assertFalse(actuator.vibrateRiskOnly(action) { true })
        val dispatch = emit(actuator, action)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, dispatch.speech)
        assertFalse(dispatch.vibrationAccepted)
        assertEquals(listOf(1_000L), runtime.vibrationTimes)
        assertTrue(runtime.spoken.isEmpty())
    }

    @Test
    fun gateRejectingTheSameEventDoesNotExtendItsOriginalCompletionTime() {
        var nowMs = 1_000L
        var outputs = 0
        val gate = FeedbackOutputGate { nowMs }
        val generation = gate.generation()
        fun send() = gate.vibrate(
            VibrationPatterns.forLevel(MessageLevel.STOP), MessageLevel.STOP.ordinal,
            generation, 10_000L, { true }, eventId = "same-event",
        ) { outputs += 1; true }
        assertTrue(send())

        nowMs = 1_800L
        assertFalse(send())
        nowMs = 1_900L

        assertTrue(send())
        assertEquals(2, outputs)
    }

    @Test
    fun gateEventIdentityDoesNotPreventHigherRankPreemption() {
        var nowMs = 1_000L
        var outputs = 0
        val gate = FeedbackOutputGate { nowMs }
        val generation = gate.generation()
        fun send(level: MessageLevel) = gate.vibrate(
            VibrationPatterns.forLevel(level), level.ordinal,
            generation, 10_000L, { true }, eventId = "same-target",
        ) { outputs += 1; true }
        assertTrue(send(MessageLevel.WARNING))

        nowMs = 1_100L

        assertTrue(send(MessageLevel.STOP))
        assertEquals(2, outputs)
    }

    @Test
    fun routeDeviationStopCannotRestartItsUnfinishedWaveform() {
        val (runtime, actuator) = readyActuator()
        assertTrue(actuator.playRouteDeviationConfirmedVibration())

        runtime.advanceBy(800L)
        val repeated = actuator.playRouteDeviationConfirmedVibration()

        assertEquals("The same route deviation STOP must finish at 1900ms", listOf(1_000L), runtime.vibrationTimes)
        assertFalse(repeated)
    }

    @Test
    fun phoneMountingSafetyStopCannotRestartItsUnfinishedWaveform() {
        val (runtime, actuator) = readyActuator()
        assertTrue(actuator.playPhoneMountingSafetyStopVibration())

        runtime.advanceBy(800L)
        val repeated = actuator.playPhoneMountingSafetyStopVibration()

        assertEquals("The same mounting safety STOP must finish at 1900ms", listOf(1_000L), runtime.vibrationTimes)
        assertFalse(repeated)
    }

    @Test
    fun differentNamedStopKindsAndActionsKeepTheirExistingReplacementBehavior() {
        val requests: List<(AndroidFeedbackActuator) -> Boolean> = listOf(
            { it.playRouteDeviationConfirmedVibration() },
            { it.playPhoneMountingSafetyStopVibration() },
            { it.vibrateRiskOnly(stopAction()) { true } },
        )
        requests.forEachIndexed { firstIndex, first ->
            requests.forEachIndexed { nextIndex, next ->
                if (firstIndex != nextIndex) {
                    val (runtime, actuator) = readyActuator()
                    assertTrue(first(actuator))
                    runtime.advanceBy(800L)

                    assertTrue(next(actuator))
                    assertEquals(listOf(1_000L, 1_800L), runtime.vibrationTimes)
                }
            }
        }
    }

    @Test
    fun eachNamedStopKeepsItsOriginalCompletionBoundaryAfterRejectingARepeat() {
        val requests: List<(AndroidFeedbackActuator) -> Boolean> = listOf(
            { it.playRouteDeviationConfirmedVibration() },
            { it.playPhoneMountingSafetyStopVibration() },
        )
        requests.forEach { request ->
            val (runtime, actuator) = readyActuator()
            assertTrue(request(actuator))
            runtime.advanceBy(800L)
            assertFalse(request(actuator))
            runtime.advanceBy(100L)

            assertTrue(request(actuator))
            assertEquals(listOf(1_000L, 1_900L), runtime.vibrationTimes)
        }
    }

    private fun stopAction() = FeedbackAction(
        trackId = "same-risk",
        message = "멈추세요. 바로 앞에 장애물이 있습니다.",
        level = MessageLevel.STOP,
        vibrationPatternMs = VibrationPatterns.forLevel(MessageLevel.STOP),
        reason = "S13-3-regression",
        validUntilMs = 10_000L,
    )

    private fun emit(actuator: AndroidFeedbackActuator, action: FeedbackAction) =
        actuator.emit(action, null, null, isStillValidAtStart = { true })

    private fun readyActuator(): Pair<FakeRuntime, AndroidFeedbackActuator> {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { true })
        runtime.ready()
        return runtime to actuator
    }

    /** Platform effect recorder only: the real actuator and output gate own admission. */
    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
            private set
        val vibrations = mutableListOf<List<Long>>()
        val vibrationTimes = mutableListOf<Long>()
        val spoken = mutableListOf<String>()
        private var initialized: ((Int) -> Unit)? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) {
            initialized = onInitialized
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int = TextToSpeech.SUCCESS
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = object : ProgressToneEffects {
            override fun startTone(toneType: Int, durationMs: Int): Boolean = true
            override fun stopTone() = Unit
            override fun release() = Unit
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrations += patternMs.toList()
            vibrationTimes += now
            return true
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
            advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = now + deltaMs
            while (true) {
                val next = tasks.filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.dueMs
                next.runnable.run()
            }
            now = targetMs
        }
    }
}
