package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S13-12 (originals/13.txt:118-121) under FP-027-DETAIL-03 interim ("첫 음성 실패가 발생하면
 * 진동·화면으로 알리고 정식 보행 기능을 중단한다"): the first engine failure is final. The actuator
 * does not silently re-initialize, reports unavailability once, plays one stop vibration and keeps
 * it through the owner's close(); MainActivity then safety-stops an active walk or blocks start.
 */
class TtsFirstFailureInterimPolicyTest {
    @Test
    fun firstRuntimeEngineFailureIsFinalAndVibratesOnceWithoutARetryEngine() {
        val runtime = FakeRuntime()
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
            hapticAllowed = { true },
        )
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        var failed = 0
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation("첫 안내", onFailed = { failed += 1 }),
        )
        runtime.error(runtime.spoken.last().id, TextToSpeech.ERROR_SERVICE)

        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, failed)
        assertEquals(1, unavailable)
        assertEquals(listOf(VibrationPatterns.forLevel(MessageLevel.STOP)!!.toList()), runtime.vibrations)
        runtime.advanceBy(30_000L)
        assertEquals("no silent re-initialization", 1, runtime.engineCount)
        assertEquals(1, unavailable)
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, actuator.speakNavigation("다음 안내"))

        // The owner's safety stop closes the actuator; the failure vibration is not cut off.
        actuator.close()
        assertEquals(0, runtime.vibrationCancels)
    }

    @Test
    fun firstInitializationFailureBeforeAnyWalkIsFinalToo() {
        val runtime = FakeRuntime()
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
            hapticAllowed = { true },
        )
        runtime.initialize(TextToSpeech.ERROR)

        assertEquals("tts=init_failed", actuator.statusText())
        assertFalse(actuator.isSpeechInitializing())
        assertEquals(1, unavailable)
        assertEquals(1, runtime.vibrations.size)
        runtime.advanceBy(30_000L)
        assertEquals(1, runtime.engineCount)
    }

    @Test
    fun initializationTimeoutIsFinalWithoutAnotherEngine() {
        val runtime = FakeRuntime()
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
        )
        runtime.advanceBy(10_000L)

        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, unavailable)
        // Haptics disabled by the owner: no vibration, and close() still cancels normally.
        assertTrue(runtime.vibrations.isEmpty())
        runtime.advanceBy(30_000L)
        assertEquals(1, runtime.engineCount)
        actuator.close()
        assertEquals(1, runtime.vibrationCancels)
    }

    @Test
    fun stopRiskSpeechFailingInsideTheDuplicateWindowStillEndsWithAStopVibrationCloseKeeps() {
        // w24 misc-F1: a STOP risk cue vibrates STOP right after its speech request. When that
        // speech fails ~100 ms later (asynchronous ERROR_SERVICE) the failure notice must still
        // start a full STOP pattern (not absorbed by the same-pattern dedupe window), and the
        // owner's safety-stop close() must not cancel it: it is the only non-visual cue left.
        val runtime = FakeRuntime()
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
            hapticAllowed = { true },
        )
        runtime.ready()
        val stop = checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP))
        val action = riskAction(MessageLevel.STOP, runtime.now + 20_000L)
        val dispatch = actuator.emit(
            action = action,
            onSpeechCompleted = null,
            onSpeechFailed = null,
            isStillValidAtStart = { true },
        )
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch.speech)
        assertTrue(dispatch.vibrationAccepted)
        val riskVibrationAt = runtime.now
        runtime.advanceBy(50L)
        // Ordinary vibrations keep the duplicate window: the same STOP cue is not replayed.
        assertFalse(actuator.vibrateRiskOnly(action, isStillValidAtStart = { true }))
        runtime.advanceBy(50L)
        val errorAt = runtime.now
        runtime.error(runtime.spoken.last().id, TextToSpeech.ERROR_SERVICE)
        assertEquals("tts=init_failed", actuator.statusText())
        assertEquals(1, unavailable)
        assertEquals(listOf(stop.toList(), stop.toList()), runtime.vibrations)
        assertEquals(listOf(riskVibrationAt, errorAt), runtime.vibrationTimes)
        actuator.close()
        assertEquals("the STOP notice started after the error is not cut", 0, runtime.vibrationCancels)
    }
    @Test
    fun warningRiskSpeechFailureStillAddsOneStopNoticeThatCloseKeeps() {
        // Non-STOP path (unchanged by misc-F1): WARNING differs from STOP, so the notice was
        // never deduplicated; it still replaces the WARNING pattern and survives close().
        val runtime = FakeRuntime()
        var unavailable = 0
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            onOfflineKoreanSpeechUnavailable = { unavailable += 1 },
            hapticAllowed = { true },
        )
        runtime.ready()
        val dispatch = actuator.emit(
            action = riskAction(MessageLevel.WARNING, runtime.now + 20_000L),
            onSpeechCompleted = null,
            onSpeechFailed = null,
            isStillValidAtStart = { true },
        )
        assertTrue(dispatch.vibrationAccepted)
        runtime.advanceBy(100L)
        runtime.error(runtime.spoken.last().id, TextToSpeech.ERROR_SERVICE)
        assertEquals(1, unavailable)
        assertEquals(
            listOf(
                checkNotNull(VibrationPatterns.forLevel(MessageLevel.WARNING)).toList(),
                checkNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)).toList(),
            ),
            runtime.vibrations,
        )
        actuator.close()
        assertEquals(0, runtime.vibrationCancels)
    }
    private fun riskAction(level: MessageLevel, validUntilMs: Long) = FeedbackAction(
        trackId = "misc-f1-risk",
        message = if (level == MessageLevel.STOP) "멈추세요. 바로 앞에 장애물이 있습니다." else "앞에 장애물이 있습니다.",
        level = level,
        vibrationPatternMs = VibrationPatterns.forLevel(level),
        reason = "misc-F1",
        validUntilMs = validUntilMs,
    )
    @Test
    fun ownerHandlerStopsAnActiveWalkAndBlocksStartOnTheFirstFailure() {
        // Unchanged owner path that the first failure now reaches directly.
        val source = String(
            Files.readAllBytes(
                Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            ),
            Charsets.UTF_8,
        )
        val handler = source.substringAfter("private fun handleRuntimeSpeechCapabilityFailure(")
            .substringBefore("private fun announceTalkBackInteractionAfterVoiceRecognition(")
        assertTrue(handler.contains("offlineKoreanTextToSpeechCapabilityOverride = false"))
        assertTrue(handler.contains("enterWalkSessionSafetyStopAndCancelOutputs(\"offline_korean_tts_runtime_failure\")"))
        assertTrue(handler.contains("refreshStartupCapabilityUi()"))
        assertTrue(handler.contains("한국어 음성 필요 · 보행 시작/재개 불가"))
        assertTrue(handler.contains("announceTalkBackInteractionAfterVoiceRecognition(detail)"))
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)

        var now = 1_000L
        var engineCount = 0
        val spoken = mutableListOf<Spoken>()
        val vibrations = mutableListOf<List<Long>>()
        val vibrationTimes = mutableListOf<Long>()
        var vibrationCancels = 0
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
            spoken += Spoken(message, utteranceId)
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
            object : ProgressToneEffects {
                override fun startTone(toneType: Int, durationMs: Int): Boolean = true
                override fun stopTone() = Unit
                override fun release() = Unit
            }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrations += patternMs.toList()
            vibrationTimes += now
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
        fun ready() = initialize(TextToSpeech.SUCCESS)
        fun initialize(status: Int) {
            checkNotNull(initialized)(status)
            runCurrent()
        }
        fun error(id: String, code: Int) {
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
