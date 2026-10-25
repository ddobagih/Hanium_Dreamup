package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorConfig
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real cue producer and actuator start validator with a deterministic engine. */
class RouteSpeechCueActualStartExpiryRegressionTest {
    @Test
    fun delayedEngineStartCancelsExpiredCueThenFreshRetryIgnoresTheOldDoneCallback() {
        val f = Fixture()
        val old = f.offer()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.submit(old))
        val oldId = f.runtime.spoken.last().id
        f.runtime.advance(600L)
        // The validator obtains a fresh same-direction observation at the actual engine start.
        f.runtime.start(oldId)
        assertEquals("a fresh observation must not extend the original cue", 1, f.cancelled)
        assertEquals(0, f.completed)
        assertEquals(0, f.failed)
        assertTrue(f.events.contains(SpeechOutputOutcome.EXPIRED_BEFORE_START))
        assertTrue(f.runtime.stopCount > 0)

        f.runtime.advance(2_000L)
        val replacement = requireNotNull(f.navigator.retryGuidance(f.runtime.now, f.facing()))
        assertNotEquals(old.speechCueToken, replacement.speechCueToken)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.submit(replacement))
        val replacementId = f.runtime.spoken.last().id
        f.runtime.start(replacementId)
        f.runtime.done(oldId)
        assertEquals(0, f.completed)
        assertEquals(1, f.cancelled)
        assertTrue(f.navigator.isInstructionCurrent(replacement, f.runtime.now, f.facing()))
        f.runtime.advance(100L)
        f.runtime.done(replacementId)
        assertEquals(1, f.completed)
        assertEquals(0, f.failed)
        assertFalse(f.navigator.isInstructionCurrent(replacement, f.runtime.now, f.facing()))
    }

    @Test
    fun startAtThe500MillisecondBoundaryMayFinishAfterExpiryWithoutBeingRestarted() {
        val f = Fixture()
        val offered = f.offer()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.submit(offered))
        val id = f.runtime.spoken.last().id
        f.runtime.advance(500L)
        f.runtime.start(id)
        assertEquals(0, f.cancelled)
        f.runtime.advance(100L)
        val whilePlaying = requireNotNull(f.navigator.retryGuidance(f.runtime.now, f.facing()))
        assertEquals("guidance_in_flight", whilePlaying.reason)
        assertFalse(whilePlaying.cancelStaleNavigationSpeech)
        f.runtime.advance(200L)
        f.runtime.done(id)
        assertEquals(1, f.completed)
        assertEquals(0, f.cancelled)
        assertEquals(0, f.failed)
        assertEquals(1, f.runtime.spoken.size)
    }

    private class Fixture {
        val runtime = SpeechRuntime()
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L),
            nowElapsedRealtimeMs = { runtime.now }).apply {
            setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
                listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
                listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13))))
        }
        val events = mutableListOf<SpeechOutputOutcome>()
        var completed = 0
        var cancelled = 0
        var failed = 0
        val actuator = AndroidFeedbackActuator(runtime = runtime).also {
            it.speechOutputListener = { event -> events += event.outcome }
            runtime.ready()
        }

        fun facing() = RouteFacingObservation(90.0, 3.0, runtime.now)

        fun offer() = navigator.update(TrustedLocation(37.0, 127.0, 1f, runtime.now), runtime.now, false,
            facingObservation = facing())

        fun submit(update: RouteNavigatorUpdate): NavigationSpeechDispatchResult {
            assertTrue(navigator.reserveInstruction(update))
            return actuator.speakNavigation(
                message = requireNotNull(update.instruction),
                isStillValidAtStart = { navigator.isInstructionCurrent(update, runtime.now, facing()) },
                onCompleted = {
                    completed += 1
                    navigator.acknowledgeInstruction(update, runtime.now)
                },
                onCancelled = {
                    cancelled += 1
                    navigator.releaseInstruction(update, runtime.now)
                },
                onFailed = {
                    failed += 1
                    navigator.releaseInstruction(update, runtime.now)
                },
            )
        }
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val runnable: Runnable)
        var now = 1_000L
        var stopCount = 0
        val spoken = mutableListOf<Spoken>()
        private val tasks = mutableListOf<Task>()
        private var onInitialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = onInitialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { this.onInitialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stopCount += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No progress tone in this test")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { checkNotNull(onInitialized)(TextToSpeech.SUCCESS); runCurrent() }
        fun start(id: String) { checkNotNull(listener).onStart(id); runCurrent() }
        fun done(id: String) { checkNotNull(listener).onDone(id); runCurrent() }
        fun advance(ms: Long) { now += ms; runCurrent() }
        private fun runCurrent() {
            while (true) {
                val index = tasks.indexOfFirst { it.dueMs <= now }
                if (index < 0) return
                tasks.removeAt(index).runnable.run()
            }
        }
    }
}
