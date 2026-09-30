package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFeedbackActuatorDuplicateRollbackRegressionTest {
    @Test
    fun riskFlushReleasesOnlyTheInterruptedNavigationReservation() {
        val runtime = SpeechRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        var cancellations = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation(
                message = "turn left",
                onCancelled = { cancellations += 1 },
                requiresExplicitTerminalCallback = true,
            ),
        )
        val interruptedId = runtime.spoken.last().id

        val risk = FeedbackAction(
            trackId = "risk-owner",
            message = "stop",
            level = MessageLevel.STOP,
            vibrationPatternMs = null,
            reason = "duplicate-rollback-regression",
            validUntilMs = runtime.now + 20_000L,
        )
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = risk,
                onSpeechCompleted = null,
                onSpeechFailed = null,
                isStillValidAtStart = { true },
            ).speech,
        )
        assertEquals(1, cancellations)
        runtime.done(runtime.spoken.last().id)

        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation("turn left"),
        )
        assertEquals(3, runtime.spoken.size)
        val replacementId = runtime.spoken.last().id
        runtime.failUtterance(interruptedId)
        runtime.done(replacementId)
        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakNavigation("turn left"),
        )
    }

    @Test
    fun nativeSpeakReturnCannotRestoreAFlushedNavigationReservation() {
        val runtime = SpeechRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        var cancellations = 0
        var riskResult: NavigationSpeechDispatchResult? = null
        runtime.onSpeak = { _, _, utteranceId ->
            if (utteranceId.startsWith("nav-")) {
                runtime.onSpeak = null
                riskResult = actuator.emit(
                    action = FeedbackAction(
                        trackId = "risk-native-return",
                        message = "stop",
                        level = MessageLevel.STOP,
                        vibrationPatternMs = null,
                        reason = "native-speak-return-regression",
                        validUntilMs = runtime.now + 20_000L,
                    ),
                    onSpeechCompleted = null,
                    onSpeechFailed = null,
                    isStillValidAtStart = { true },
                ).speech
            }
        }
        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakNavigation(
                message = "turn left",
                onCancelled = { cancellations += 1 },
                requiresExplicitTerminalCallback = true,
            ),
        )
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, riskResult)
        assertEquals(1, cancellations)
        assertEquals(2, runtime.spoken.size)
        runtime.done(runtime.spoken.last().id)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("turn left"))
    }

    @Test
    fun navigationCancellationCallbackCannotEraseTheReplacementReservation() {
        val runtime = SpeechRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        var replacement: NavigationSpeechDispatchResult? = null
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation(
                message = "turn left",
                onCancelled = { replacement = actuator.speakNavigation("turn right") },
                requiresExplicitTerminalCallback = true,
            ),
        )
        assertEquals(true, actuator.cancelNavigationSpeech())
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, replacement)
        assertEquals(2, runtime.spoken.size)
        assertEquals(
            NavigationSpeechDispatchResult.SUPPRESSED,
            actuator.speakNavigation("turn right"),
        )
    }

    @Test
    fun defaultNavigationStillSuppressesAnImmediateDuplicate() {
        val runtime = SpeechRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("turn left"))
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actuator.speakNavigation("turn left"))
        assertEquals(1, runtime.spoken.size)
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val runnable: Runnable)

        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        var onSpeak: ((String, Int, String) -> Unit)? = null
        private val tasks = mutableListOf<Task>()
        private var onInitialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null

        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = onInitialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) {
            this.onInitialized = onInitialized
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection =
            KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            onSpeak?.invoke(message, queueMode, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(
            isRisk: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
            error("Progress tone is not used by this regression")

        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) {
            tasks.removeAll { it.runnable === task }
        }
        override fun removeCallbacksAndMessages(token: Any?) {
            tasks.clear()
        }

        fun ready() {
            checkNotNull(onInitialized)(TextToSpeech.SUCCESS)
            runCurrent()
        }
        fun done(id: String) {
            checkNotNull(listener).onDone(id)
            runCurrent()
        }
        fun failUtterance(id: String) {
            checkNotNull(listener).onError(id, TextToSpeech.ERROR)
            runCurrent()
        }
        private fun runCurrent() {
            while (true) {
                val index = tasks.indexOfFirst { it.dueMs <= now }
                if (index < 0) return
                tasks.removeAt(index).runnable.run()
            }
        }
    }
}
