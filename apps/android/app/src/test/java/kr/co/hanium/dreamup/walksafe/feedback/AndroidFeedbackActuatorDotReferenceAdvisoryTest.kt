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

/** Uses the production actuator and native callbacks; Main owns the notice/owner policy tests. */
class AndroidFeedbackActuatorDotReferenceAdvisoryTest {
    @Test
    fun pendingRiskSuppressesDotAdvisoryWithoutPreemptingRisk() {
        val (runtime, actuator) = readyActuator()
        val risk = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.emit(
                action = FeedbackAction(
                    trackId = "risk-owner",
                    message = "정지하세요. 바로 앞에 장애물이 있습니다.",
                    level = MessageLevel.STOP,
                    vibrationPatternMs = null,
                    reason = "dot-reference-transport-test",
                    validUntilMs = DEADLINE,
                ),
                isStillValidAtStart = { true },
                onSpeechCompleted = { risk.completed += 1 },
                onSpeechFailed = { risk.failed += 1 },
                onSpeechCancelled = { risk.failed += 1 },
            ).speech,
        )
        assertDotDoesNotPreempt(runtime, actuator, risk)
    }

    @Test
    fun pendingNavigationAndTmapRouteSpeechSuppressDotAdvisoryWithoutPreemption() {
        // TMAP guidance reaches the same public NAVIGATION transport through Main.
        for (message in listOf("경로가 앞쪽입니다.", "경로 기준으로 20m 앞, 오른쪽으로 꺾으세요.")) {
            val (runtime, actuator) = readyActuator()
            val navigation = Events()
            assertEquals(
                NavigationSpeechDispatchResult.ACCEPTED,
                actuator.speakNavigation(
                    message = message,
                    onCompleted = { navigation.completed += 1 },
                    onFailed = { navigation.failed += 1 },
                    onCancelled = { navigation.failed += 1 },
                    requiresExplicitTerminalCallback = true,
                    isStillValidAtStart = { true },
                ),
            )
            assertDotDoesNotPreempt(runtime, actuator, navigation)
        }
    }

    @Test
    fun focusAcquisitionPastTheIssuedDeadlineNeverCallsNativeSpeak() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        runtime.onFocusRequest = { runtime.now = DEADLINE + 1L }

        assertFalse(speakDot(actuator, events))

        assertUnsubmittedDotWasCancelled(runtime, actuator, events)
    }

    @Test
    fun revokedClosureDuringFocusAcquisitionNeverCallsNativeSpeak() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        var current = true
        runtime.onFocusRequest = { current = false }

        assertFalse(speakDot(actuator, events) { current })

        assertUnsubmittedDotWasCancelled(runtime, actuator, events)
    }

    @Test
    fun heldNativeStartCannotCompleteAfterDeadlineExpiryOrClosureRevocation() {
        for (expired in listOf(true, false)) {
            val (runtime, actuator) = readyActuator()
            val events = Events()
            var current = true
            assertTrue(speakDot(actuator, events) { current })
            val issued = runtime.spoken.single()
            assertEquals(DOT_MESSAGE, issued.message)
            assertEquals(TextToSpeech.QUEUE_ADD, issued.queueMode)
            assertTrue(actuator.isAppSpeechActive())
            assertEquals(0, events.completed)
            assertEquals(0, events.failed)

            if (expired) {
                runtime.advanceBy(DEADLINE - runtime.now + 1L)
            } else {
                runtime.advanceBy(1L)
                current = false
            }
            // The native submission already happened; a late onStart must stop/reject it.
            runtime.start(issued.id)
            runtime.done(issued.id)

            assertEquals(1, runtime.spoken.size)
            assertTrue(runtime.stops > 0)
            assertEquals(0, events.completed)
            assertEquals(1, events.failed)
            assertFalse(actuator.isAppSpeechActive())
            assertEquals("tts=ready", actuator.statusText())
            assertEquals(1, runtime.focusReleases)
        }
    }

    @Test
    fun validNativeStartAtDeadlineCompletesNormallyAfterTheStartDeadline() {
        val (runtime, actuator) = readyActuator()
        val events = Events()
        assertTrue(speakDot(actuator, events))
        val issued = runtime.spoken.single()
        assertEquals(DOT_MESSAGE, issued.message)
        assertEquals(TextToSpeech.QUEUE_ADD, issued.queueMode)
        assertEquals(0, events.completed)

        runtime.advanceBy(DEADLINE - runtime.now)
        runtime.start(issued.id)
        assertTrue(actuator.isAppSpeechActive())
        assertEquals(0, events.failed)
        runtime.advanceBy(1L)
        assertTrue(runtime.now > DEADLINE)
        assertTrue(actuator.isAppSpeechActive())
        runtime.done(issued.id)

        assertEquals(1, events.completed)
        assertEquals(0, events.failed)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(0, runtime.stops)
        assertEquals(1, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
        assertEquals("tts=ready", actuator.statusText())
    }

    private fun assertDotDoesNotPreempt(
        runtime: FakeRuntime,
        actuator: AndroidFeedbackActuator,
        previous: Events,
    ) {
        val issued = runtime.spoken.single()
        val stopsBefore = runtime.stops
        val focusRequestsBefore = runtime.focusRequests
        val dot = Events()

        // speakAdvisory exposes Boolean: false is admission rejection, without native replay.
        assertFalse(speakDot(actuator, dot))

        assertEquals(listOf(issued), runtime.spoken)
        assertEquals(stopsBefore, runtime.stops)
        assertEquals(focusRequestsBefore, runtime.focusRequests)
        assertEquals(0, runtime.focusReleases)
        assertEquals(0, dot.completed)
        assertEquals(0, dot.failed)
        assertEquals(0, previous.completed)
        assertEquals(0, previous.failed)
        assertTrue(actuator.isAppSpeechActive())
        runtime.start(issued.id)
        runtime.done(issued.id)
        assertEquals(1, previous.completed)
        assertEquals(0, previous.failed)
        assertEquals(listOf(issued), runtime.spoken)
        assertFalse(actuator.isAppSpeechActive())
    }

    private fun assertUnsubmittedDotWasCancelled(
        runtime: FakeRuntime,
        actuator: AndroidFeedbackActuator,
        events: Events,
    ) {
        assertTrue(runtime.spoken.isEmpty())
        assertTrue(runtime.stops > 0)
        assertEquals(0, events.completed)
        assertEquals(1, events.failed)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals("tts=ready", actuator.statusText())
        assertEquals(1, runtime.focusRequests)
        assertEquals(1, runtime.focusReleases)
    }

    private fun speakDot(
        actuator: AndroidFeedbackActuator,
        events: Events,
        current: () -> Boolean = { true },
    ): Boolean = actuator.speakAdvisory(
        message = DOT_MESSAGE,
        validUntilMs = DEADLINE,
        isStillValid = current,
        onCompleted = { events.completed += 1 },
        onFailed = { events.failed += 1 },
    )

    private fun readyActuator(): Pair<FakeRuntime, AndroidFeedbackActuator> {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime)
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        return runtime to actuator
    }

    private class Events {
        var completed = 0
        var failed = 0
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)

        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        var stops = 0
        var focusRequests = 0
        var focusReleases = 0
        var onFocusRequest: (() -> Unit)? = null
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L

        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(
            isRisk: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Boolean {
            focusRequests += 1
            val callback = onFocusRequest
            onFocusRequest = null
            callback?.invoke()
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { focusReleases += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
            kotlin.error("dot advisory must not request a progress tone")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() {
            requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) {
            requireNotNull(listener).onStart(id)
            advanceBy(0L)
        }
        fun done(id: String) {
            requireNotNull(listener).onDone(id)
            advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.runnable.run()
            }
            now = target
        }
    }

    private companion object {
        const val DOT_MESSAGE = "점형 점자블록 참고 정보입니다. 주변 환경을 확인하세요."
        // The existing Main notice fixture uses capture 1000 and its issued source deadline 1300.
        const val DEADLINE = 1_300L
    }
}
