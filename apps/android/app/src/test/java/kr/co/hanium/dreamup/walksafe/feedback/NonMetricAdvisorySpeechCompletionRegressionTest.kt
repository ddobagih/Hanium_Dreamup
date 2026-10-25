package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real policy and actuator; only the platform speaker, focus and clock are controlled. */
class NonMetricAdvisorySpeechCompletionRegressionTest {
    @Test
    fun actualDoneAfterStartExpiryAndOneMissingFrameStillConsumesTheRepeatInterval() {
        Fixture().use { f ->
            val action = f.offer()
            f.submit(action)
            val id = f.runtime.spoken.single()
            f.runtime.start(id)
            f.runtime.advance(100L)
            assertNull(f.observe(emptyList()))
            assertFalse("source loss still blocks a new start", f.policy.isDeliveryCurrent(action))
            f.runtime.advance(3_900L)
            assertTrue(f.runtime.now > action.validUntilMs)
            f.runtime.done(id)

            assertEquals("audible completion must retain its delivery receipt", 1, f.completed)
            assertFalse(f.actor.isAppSpeechActive())
            assertNull("same object cannot repeat immediately after DONE", f.observe())
            f.runtime.advance(7_999L)
            assertNull(f.observe())
            f.runtime.advance(1L)
            assertEquals(action.key, f.offer().key)
            f.runtime.done(id)
            assertEquals("late duplicate DONE cannot complete a new owner", 1, f.completed)
        }
    }

    @Test
    fun lossBeforeNativeStartStillCancelsWithoutConsumingTheRepeatInterval() {
        Fixture().use { f ->
            val action = f.offer()
            f.submit(action)
            val id = f.runtime.spoken.single()
            f.runtime.advance(100L)
            assertNull(f.observe(emptyList()))
            assertFalse(f.policy.isDeliveryCurrent(action))
            f.runtime.start(id)
            f.runtime.done(id)

            assertEquals(0, f.completed)
            assertEquals(1, f.failed)
            assertFalse(f.actor.isAppSpeechActive())
            assertTrue(f.runtime.stops > 0)
            assertEquals(action.key, f.offer().key)
        }
    }

    @Test
    fun returningDetectionCannotReviveTheOldUnstartedSource() {
        Fixture().use { f ->
            val action = f.offer()
            f.submit(action)
            f.runtime.advance(100L)
            assertNull(f.observe(emptyList()))
            f.runtime.advance(100L)
            assertNull("the first speech still owns its terminal receipt", f.observe())
            assertFalse(f.policy.isDeliveryCurrent(action))
            f.runtime.start(f.runtime.spoken.single())
            assertEquals(1, f.failed)
            assertEquals(0, f.completed)
            assertEquals(action.key, f.offer().key)
        }
    }

    @Test
    fun gateLossRetiresTheOldDoneWithoutAcknowledgingAReplacement() {
        Fixture().use { f ->
            val action = f.offer()
            f.submit(action)
            val id = f.runtime.spoken.single()
            f.runtime.start(id)
            f.runtime.advance(100L)
            assertNull(f.observe(gate = f.gate.copy(imuFresh = false)))
            val replacement = f.offer()
            f.runtime.done(id)
            assertEquals(0, f.completed)
            assertTrue(f.policy.isDeliveryCurrent(replacement))
            assertTrue(f.policy.claimDelivery(replacement))
        }
    }

    private class Fixture : AutoCloseable {
        val runtime = SpeechRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime).also { runtime.ready() }
        val policy = AndroidNonMetricObstacleAdvisoryPolicy(
            NonMetricObstacleAdvisoryConfig(minStableFrames = 1, minStableMs = 0L),
        )
        val gate = NonMetricAdvisoryGate(true, true, true, true, true)
        private val detection = DetectionCandidate("person", 0.86f, RectNorm(0.4f, 0.4f, 0.2f, 0.5f))
        private var frame = 0L
        var completed = 0
        var failed = 0

        fun observe(detections: List<DetectionCandidate> = listOf(detection), gate: NonMetricAdvisoryGate = this.gate) =
            policy.evaluate(detections, gate, ++frame, runtime.now)

        fun offer() = requireNotNull(observe())

        fun submit(action: NonMetricObstacleAdvisoryAction) {
            assertTrue(policy.claimDelivery(action))
            assertTrue(actor.speakAdvisory(
                message = action.message,
                validUntilMs = action.validUntilMs,
                isStillValid = { policy.isDeliveryCurrent(action) },
                onCompleted = { if (policy.confirmDelivery(action, runtime.now)) completed += 1 },
                onFailed = { policy.rejectDelivery(action); failed += 1 },
            ))
        }

        override fun close() = actor.close()
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        private data class Task(val dueMs: Long, val runnable: Runnable)
        var now = 1_000L
        var stops = 0
        val spoken = mutableListOf<String>()
        private val tasks = mutableListOf<Task>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { checkNotNull(initialized)(TextToSpeech.SUCCESS); runCurrent() }
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
