package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S08-34 (nav-F5): the navigation start validator also runs at submission and in the start watchdog.
 * Only the check for an utterance the engine has actually started is marked, so the caller can
 * record the playback start once instead of recording every submission check as "started".
 */
class NavigationSpeechActualStartCheckTest {
    @Test
    fun onlyTheEngineStartCheckIsMarkedAsTheActualStart() {
        for (validAtStart in listOf(true, false)) {
            val f = Fixture()
            val checks = mutableListOf<Boolean>()
            var valid = true
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left", isStillValidAtStart = {
                checks += f.actuator.validatingStartedSpeech
                valid
            }))
            assertTrue("submission checks ran", checks.isNotEmpty())
            assertTrue("no submission check is the start: $checks", checks.none { it })
            valid = validAtStart
            f.runtime.start(f.runtime.spoken.last().id)
            assertEquals("validAtStart=$validAtStart $checks", 1, checks.count { it })
            assertTrue(checks.last())
            assertTrue("the mark does not outlive the check", !f.actuator.validatingStartedSpeech)
            if (!validAtStart) assertTrue(f.events.contains(SpeechOutputOutcome.EXPIRED_BEFORE_START))
        }
    }

    @Test
    fun theStartWatchdogCheckOfAnUnstartedUtteranceIsNotTheStart() {
        val f = Fixture()
        val checks = mutableListOf<Boolean>()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn right", isStillValidAtStart = {
            checks += f.actuator.validatingStartedSpeech
            true
        }))
        val beforeWatchdog = checks.size
        f.runtime.advance(60_000L)
        assertTrue("the watchdog re-checked the start validator", checks.size > beforeWatchdog)
        assertTrue(checks.none { it })
    }

    private class Fixture {
        val runtime = SpeechRuntime()
        val events = mutableListOf<SpeechOutputOutcome>()
        val actuator = AndroidFeedbackActuator(runtime = runtime).also { actuator ->
            actuator.speechOutputListener = { events += it.outcome }
            runtime.ready()
        }
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val runnable: Runnable)
        var now = 1_000L
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
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
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
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("Progress tone is not used by this test")
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
        fun advance(ms: Long) {
            now += ms
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
