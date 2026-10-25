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

/** 13.txt #38: bounded, text-free speech output outcomes that a release build can keep. */
class SpeechOutputDiagnosticsTest {
    @Test
    fun startDoneAndEngineErrorAreStructuredWithoutTextOrUtteranceId() {
        val f = Fixture()
        f.actuator.speakNavigation("left turn at the crossing")
        val first = f.runtime.spoken.last().id
        f.runtime.start(first)
        f.runtime.done(first)
        f.actuator.speakNavigation("second secret destination")
        f.runtime.fail(f.runtime.spoken.last().id, TextToSpeech.ERROR_SYNTHESIS)

        assertEquals(
            listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE,
                SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.ENGINE_ERROR),
            f.events.map(SpeechOutputEvent::outcome),
        )
        assertTrue(f.events.all { it.kind == SpeechOutputKind.NAVIGATION })
        assertEquals(TextToSpeech.ERROR_SYNTHESIS, f.events.last().errorCode)
        f.events.forEach { event ->
            assertFalse(event.toString().contains("crossing"))
            assertFalse(event.toString().contains("destination"))
            assertFalse(event.toString().contains(first))
        }
    }

    @Test
    fun focusDenialIsRecordedForTheRequestedChannel() {
        val f = Fixture()
        f.runtime.grantFocus = false
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.actuator.speakNavigation("turn left"))
        assertEquals(
            listOf(
                SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED, f.runtime.now),
                SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.FOCUS_DENIED, f.runtime.now),
            ),
            f.events,
        )
    }

    @Test
    fun cancellationReasonsSeparatePriorityPreemptionFromStaleNavigation() {
        val f = Fixture()
        f.actuator.speakNavigation("turn left")
        f.actuator.emit(
            action = FeedbackAction(
                trackId = "risk",
                message = "stop",
                level = MessageLevel.STOP,
                vibrationPatternMs = null,
                reason = "speech-diagnostics",
                validUntilMs = f.runtime.now + 20_000L,
            ),
            onSpeechCompleted = null,
            onSpeechFailed = null,
            isStillValidAtStart = { true },
        )
        assertTrue(
            f.events.contains(
                SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.CANCELLED_BY_PRIORITY, f.runtime.now),
            ),
        )
        f.runtime.done(f.runtime.spoken.last().id)
        f.events.clear()
        f.actuator.speakNavigation("turn right")
        assertTrue(f.actuator.cancelNavigationSpeech())
        assertEquals(
            listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION),
            f.events.map(SpeechOutputEvent::outcome),
        )
    }

    @Test
    fun aThrowingListenerNeverBreaksSpeechAndNoListenerRecordsNothing() {
        val f = Fixture(listener = { error("sink failure") })
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left"))
        f.runtime.start(f.runtime.spoken.last().id)
        f.runtime.done(f.runtime.spoken.last().id)
        f.actuator.speechOutputListener = null
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn right"))
    }

    @Test
    fun diagnosticsAggregateCountsAndDrainPerWindow() {
        val diagnostics = SpeechOutputDiagnostics()
        diagnostics.record(SpeechOutputEvent(SpeechOutputKind.RISK, SpeechOutputOutcome.REQUESTED, 5L))
        diagnostics.record(SpeechOutputEvent(SpeechOutputKind.RISK, SpeechOutputOutcome.FOCUS_DENIED, 10L))
        diagnostics.record(SpeechOutputEvent(SpeechOutputKind.RISK, SpeechOutputOutcome.FOCUS_DENIED, 20L))
        diagnostics.record(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.ENGINE_ERROR, 30L, -3))
        (0 until 40).forEach { code ->
            diagnostics.record(SpeechOutputEvent(SpeechOutputKind.OTHER, SpeechOutputOutcome.ENGINE_ERROR, 40L, 1_000 + code))
        }
        val snapshot = diagnostics.drain()
        assertEquals(1, snapshot["risk.requested"])
        assertEquals(2, snapshot["risk.focus_denied"])
        assertEquals(1, snapshot["navigation.engine_error"])
        assertEquals(1, snapshot["error_code.-3"])
        assertEquals(16, snapshot.keys.count { it.startsWith("error_code.") })
        assertTrue(diagnostics.snapshot().isEmpty())
        assertEquals(SpeechOutputKind.RISK, speechOutputKindOf("risk-1-2"))
        assertEquals(SpeechOutputKind.OTHER, speechOutputKindOf(null))
    }

    private class Fixture(listener: ((SpeechOutputEvent) -> Unit)? = null) {
        val runtime = SpeechRuntime()
        val events = mutableListOf<SpeechOutputEvent>()
        val actuator = AndroidFeedbackActuator(runtime = runtime).also { actuator ->
            actuator.speechOutputListener = listener ?: { events += it }
            runtime.ready()
        }
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val runnable: Runnable)

        var now = 1_000L
        var grantFocus = true
        val spoken = mutableListOf<Spoken>()
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
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(
            isRisk: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Boolean = grantFocus
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
            error("Progress tone is not used by this test")

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
        fun start(id: String) {
            checkNotNull(listener).onStart(id)
            runCurrent()
        }
        fun done(id: String) {
            checkNotNull(listener).onDone(id)
            runCurrent()
        }
        fun fail(id: String, code: Int) {
            checkNotNull(listener).onError(id, code)
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
