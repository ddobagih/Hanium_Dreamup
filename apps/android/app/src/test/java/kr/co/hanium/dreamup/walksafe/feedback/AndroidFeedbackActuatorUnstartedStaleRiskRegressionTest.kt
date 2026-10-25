package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-5: actual actuator admission; only the clock and Android effects are replaced. */
class AndroidFeedbackActuatorUnstartedStaleRiskRegressionTest {
    @Test
    fun invalidatedUnstartedStopCanBeReplacedWithoutLateCallbacksAffectingNewStop() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var oldValid = true
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) { oldValid })
            val oldId = runtime.spoken.single().id

            // The source lease is still live, but this request can no longer pass its start check.
            // No onStart has been delivered; this is not cancellation of an ongoing warning.
            runtime.advanceBy(100L)
            oldValid = false
            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("new", 5_100L), replacement) { true })
            assertEquals(listOf("old stop", "new stop"), runtime.spoken.map { it.message })
            val replacementId = runtime.spoken.last().id
            assertEquals(TextToSpeech.QUEUE_FLUSH, runtime.spoken.last().queueMode)
            assertEquals(replacementId, runtime.activeSpeechId)
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)

            runtime.start(oldId)
            runtime.done(oldId)
            runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
            runtime.stopped(oldId)
            assertEquals("tts=ready", actuator.statusText())
            assertTrue(actuator.isAppSpeechActive())
            assertEquals(replacementId, runtime.activeSpeechId)
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)
            assertEquals(0, replacement.completed + replacement.failed + replacement.cancelled)

            runtime.start(replacementId)
            runtime.done(replacementId)
            runtime.advanceBy(20_000L)
            assertEquals(1, replacement.completed)
            assertEquals(0, replacement.failed + replacement.cancelled)
            assertEquals(1, runtime.engineCount)
            assertEquals("tts=ready", actuator.statusText())
            assertFalse(actuator.isAppSpeechActive())
        }
    }

    @Test
    fun startedStopStillSuppressesEqualRankReplacementWhenItsStartValidatorChanges() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var oldValid = true
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) { oldValid })
            val oldId = runtime.spoken.single().id
            runtime.start(oldId)
            runtime.advanceBy(100L)
            oldValid = false

            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                emit(actuator, stop("new", 5_100L), replacement) { true })
            assertEquals(listOf("old stop"), runtime.spoken.map { it.message })
            assertEquals(oldId, runtime.activeSpeechId)
            assertTrue(actuator.isAppSpeechActive())
            assertEquals(0, old.completed + old.failed + old.cancelled)
            assertEquals(0, replacement.completed + replacement.failed + replacement.cancelled)

            runtime.done(oldId)
            assertEquals(1, old.completed)
            assertEquals(0, old.failed + old.cancelled)
            assertFalse(actuator.isAppSpeechActive())
        }
    }

    @Test
    fun stillValidUnstartedStopKeepsEqualRankSuppressionUntilItCompletes() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) { true })
            val oldId = runtime.spoken.single().id
            runtime.advanceBy(100L)

            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                emit(actuator, stop("new", 5_100L), replacement) { true })
            assertEquals(listOf("old stop"), runtime.spoken.map { it.message })
            assertEquals(oldId, runtime.activeSpeechId)
            assertEquals(0, old.completed + old.failed + old.cancelled)
            assertEquals(0, replacement.completed + replacement.failed + replacement.cancelled)

            runtime.start(oldId)
            runtime.done(oldId)
            assertEquals(1, old.completed)
            assertEquals(0, old.failed + old.cancelled)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("new", 5_100L), replacement) { true })
        }
    }

    @Test(timeout = 10_000L)
    fun staleRiskValidatorAndCancellationCallbackCanWaitForAnotherThreadsRequest() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var oldValid = true
            var checkHook: (() -> Unit)? = null
            var validatorProbe: ThreadProbe? = null
            var cancellationProbe: ThreadProbe? = null
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old, onCancelled = {
                    cancellationProbe = requestFromAnotherThread {
                        actuator.speakInteraction("probe command", onCompleted = null, onFailed = null)
                    }
                }) {
                    val hook = checkHook
                    checkHook = null
                    hook?.invoke()
                    oldValid
                })
            runtime.advanceBy(100L)
            oldValid = false
            checkHook = {
                validatorProbe = requestFromAnotherThread {
                    actuator.speakNavigation("probe route", isStillValidAtStart = { true })
                }
            }
            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("new", 5_100L), replacement) { true })

            for (probe in listOf(checkNotNull(validatorProbe), checkNotNull(cancellationProbe))) {
                // Assertions stay outside callbacks: the actuator intentionally isolates their errors.
                assertFalse("caller code held a dispatch or output-gate lock", probe.blocked)
                assertNull("worker request threw", probe.error)
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, probe.result)
            }
            assertEquals(listOf("old stop", "new stop"), runtime.spoken.map { it.message })
            assertEquals(1, old.cancelled)
            val id = runtime.spoken.last().id
            runtime.start(id)
            runtime.done(id)
            assertEquals(1, replacement.completed)
            assertEquals(0, replacement.failed + replacement.cancelled)
        }
    }

    @Test
    fun oldValidatorReentryCannotRetireTheNewEqualRankOwner() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var oldValid = true
            var checkHook: (() -> Unit)? = null
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) {
                    val hook = checkHook
                    checkHook = null
                    hook?.invoke()
                    oldValid
                })
            runtime.advanceBy(100L)
            oldValid = false
            val winner = Events()
            var winnerDispatch: NavigationSpeechDispatchResult? = null
            checkHook = { winnerDispatch = emit(actuator, stop("winner", 5_100L), winner) { true } }
            val outer = Events()
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                emit(actuator, stop("outer", 5_100L), outer) { true })

            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, winnerDispatch)
            assertEquals(listOf("old stop", "winner stop"), runtime.spoken.map { it.message })
            assertEquals(runtime.spoken.last().id, runtime.activeSpeechId)
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)
            assertEquals(0, outer.completed + outer.failed + outer.cancelled)
            runtime.start(runtime.spoken.last().id)
            runtime.done(runtime.spoken.last().id)
            assertEquals(1, winner.completed)
            assertEquals(0, winner.failed + winner.cancelled)
        }
    }

    @Test
    fun oldRiskStartingDuringValidationKeepsEqualRankSuppression() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var oldValid = true
            var checkHook: (() -> Unit)? = null
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) {
                    val hook = checkHook
                    checkHook = null
                    hook?.invoke()
                    oldValid
                })
            val oldId = runtime.spoken.single().id
            runtime.advanceBy(100L)
            checkHook = {
                runtime.start(oldId)
                oldValid = false
            }
            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                emit(actuator, stop("new", 5_100L), replacement) { true })
            assertEquals(listOf("old stop"), runtime.spoken.map { it.message })
            assertEquals(oldId, runtime.activeSpeechId)
            assertEquals(0, old.completed + old.failed + old.cancelled)
            runtime.done(oldId)
            assertEquals(1, old.completed)
            assertEquals(0, old.failed + old.cancelled)
            assertEquals(0, replacement.completed + replacement.failed + replacement.cancelled)
        }
    }

    @Test
    fun closeDuringOldValidatorPreventsReplacementRegistration() {
        val runtime = FakeRuntime()
        AndroidFeedbackActuator(runtime = runtime).use { actuator ->
            runtime.ready()
            var checkHook: (() -> Unit)? = null
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) {
                    val hook = checkHook
                    checkHook = null
                    hook?.invoke()
                    hook == null
                })
            val oldId = runtime.spoken.single().id
            runtime.advanceBy(100L)
            checkHook = actuator::close
            val replacement = Events()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE,
                emit(actuator, stop("new", 5_100L), replacement) { true })
            assertEquals(0, old.cancelled)
            runtime.start(oldId)
            runtime.done(oldId)
            runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
            assertEquals("tts=closed", actuator.statusText())
            assertEquals(listOf("old stop"), runtime.spoken.map { it.message })
            assertNull(runtime.activeSpeechId)
            assertFalse(actuator.isAppSpeechActive())
            assertEquals(1, old.cancelled)
            assertEquals(0, old.completed + old.failed)
            assertEquals(0, replacement.completed + replacement.failed + replacement.cancelled)
        }
    }

    @Test
    fun reinitializedEngineDuringOldValidatorKeepsItsNewOwner() {
        val runtime = FakeRuntime()
        // Existing recovery-mechanism fixture budget; production still uses the interim zero retries.
        AndroidFeedbackActuator(runtime = runtime, recoveryBudget = TtsEngineRecoveryBudget(automaticRetries = 1)).use { actuator ->
            runtime.ready()
            var oldValid = true
            var checkHook: (() -> Unit)? = null
            val old = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                emit(actuator, stop("old", 5_000L), old) {
                    val hook = checkHook
                    checkHook = null
                    hook?.invoke()
                    oldValid
                })
            val oldId = runtime.spoken.single().id
            runtime.advanceBy(100L)
            oldValid = false
            val winner = Events()
            var winnerDispatch: NavigationSpeechDispatchResult? = null
            checkHook = {
                runtime.error(oldId, TextToSpeech.ERROR_SERVICE)
                runtime.advanceBy(250L)
                runtime.ready()
                winnerDispatch = emit(actuator, stop("winner", 6_000L), winner) { true }
            }
            val outer = Events()
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE,
                emit(actuator, stop("outer", 5_100L), outer) { true })
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, winnerDispatch)
            assertEquals(listOf("old stop", "winner stop"), runtime.spoken.map { it.message })
            assertEquals(2, runtime.engineCount)
            assertEquals("tts=ready", actuator.statusText())
            assertEquals(runtime.spoken.last().id, runtime.activeSpeechId)
            assertEquals(1, old.failed)
            assertEquals(0, old.completed + old.cancelled)
            assertEquals(0, outer.completed + outer.failed + outer.cancelled)
            runtime.start(runtime.spoken.last().id)
            runtime.done(runtime.spoken.last().id)
            assertEquals(1, winner.completed)
            assertEquals(0, winner.failed + winner.cancelled)
        }
    }

    private fun stop(trackId: String, deadlineMs: Long) = FeedbackAction(
        trackId = trackId,
        message = "$trackId stop",
        level = MessageLevel.STOP,
        vibrationPatternMs = null,
        reason = "unstarted-stale-risk-regression",
        validUntilMs = deadlineMs,
    )

    private fun emit(
        actuator: AndroidFeedbackActuator,
        action: FeedbackAction,
        events: Events,
        onCancelled: (() -> Unit)? = null,
        validator: () -> Boolean,
    ): NavigationSpeechDispatchResult = actuator.emit(
        action = action,
        onSpeechCompleted = { events.completed += 1 },
        onSpeechFailed = { events.failed += 1 },
        onSpeechCancelled = { events.cancelled += 1; onCancelled?.invoke() },
        isStillValidAtStart = validator,
    ).speech

    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
    }

    private data class ThreadProbe(
        val blocked: Boolean,
        val result: NavigationSpeechDispatchResult?,
        val error: Throwable?,
    )

    private fun requestFromAnotherThread(request: () -> NavigationSpeechDispatchResult): ThreadProbe {
        val result = AtomicReference<NavigationSpeechDispatchResult?>()
        val error = AtomicReference<Throwable?>()
        val thread = Thread({
            try { result.set(request()) } catch (failure: Throwable) { error.set(failure) }
        }, "stale-risk-probe").apply { isDaemon = true }
        thread.start()
        thread.join(2_000L)
        return ThreadProbe(thread.isAlive, result.get(), error.get())
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
        private var initialized: ((Int) -> Unit)? = null
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        private var nowMs = 1_000L
        val spoken = mutableListOf<Spoken>()
        var activeSpeechId: String? = null
            private set
        var engineCount = 0
            private set
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = nowMs
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
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() { activeSpeechId = null }
        override fun shutdown() { activeSpeechId = null }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(nowMs + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() {
            checkNotNull(initialized)(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun done(id: String) {
            if (activeSpeechId == id) activeSpeechId = null
            listener.onDone(id)
            advanceBy(0L)
        }
        fun error(id: String, code: Int) { listener.onError(id, code); advanceBy(0L) }
        fun stopped(id: String) { listener.onStop(id, interrupted = true); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = tasks.filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order }) ?: break
                tasks.remove(next)
                nowMs = next.dueMs
                next.runnable.run()
            }
            nowMs = targetMs
        }
    }
}
