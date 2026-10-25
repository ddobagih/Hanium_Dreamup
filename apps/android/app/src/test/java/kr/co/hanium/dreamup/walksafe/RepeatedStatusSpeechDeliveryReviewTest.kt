package kr.co.hanium.dreamup.walksafe.voice

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechRuntime
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechScheduler
import kr.co.hanium.dreamup.walksafe.feedback.ProgressToneEffects
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Test

/** Real controller, registry, actuator and output gate; stable admitted reader + controlled native callbacks only. */
class RepeatedStatusSpeechDeliveryReviewTest {

    private val explanation = "방향의 측정 오차가 큽니다. 휴대전화 뒷면을 앞쪽으로 유지해 주세요."

    @Test fun repeatedSameStatusBeforeNativeStartMustKeepTheOriginalOwner() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        assertTrue(f.controller.speakStatusExplanation(explanation))
        val first = f.runtime.spoken.single(); val generation = f.controller.currentCommandGeneration()
        f.clock.advanceBy(300L); val duplicate = f.controller.speakStatusExplanation(explanation)
        f.clock.advanceBy(100L); f.runtime.start(first.id); f.clock.advanceBy(2_600L); f.runtime.done(first.id)
        println("queued duplicate=$duplicate stops=${f.runtime.nativeStops} interrupted=${f.runtime.interrupted.size} terminal=${f.terminals}")
        assertEquals("same pending explanation must not make the first native START stale", 0, f.runtime.nativeStops)
        assertEquals(generation, f.controller.currentCommandGeneration())
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(listOf(true), f.terminals)
    }

    @Test fun repeatedSameStatusDuringThreeSecondPlaybackMustNotFlushItsOwnSentence() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        assertTrue(f.controller.speakStatusExplanation(explanation))
        val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        val generation = f.controller.currentCommandGeneration()
        repeat(10) {
            f.clock.advanceBy(300L)
            val count = f.runtime.spoken.size
            f.controller.speakStatusExplanation(explanation)
            if (f.runtime.spoken.size > count) f.runtime.start(f.runtime.spoken.last().id)
        }
        f.runtime.done(first.id)
        println("playing speaks=${f.runtime.spoken.size} stops=${f.runtime.nativeStops} flushInterrupts=${f.runtime.interrupted.size} terminal=${f.terminals}")
        assertTrue("identical explanation must finish instead of repeatedly flushing itself", f.runtime.interrupted.isEmpty())
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(generation, f.controller.currentCommandGeneration())
        assertEquals(listOf(true), f.terminals)
    }

    @Test fun repeatedNavigationDispatchDoesNotFlushAnAlreadyPlayingNavigationSentence() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        var done = 0
        assertTrue(f.controller.dispatchNavigationSpeech("약 12시에서 1시 사이 방향, 약 20미터입니다.", { done++ }))
        val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        repeat(10) { f.clock.advanceBy(300L); f.controller.dispatchNavigationSpeech(first.message) }
        f.runtime.done(first.id)
        assertEquals(1, f.runtime.spoken.size); assertTrue(f.runtime.interrupted.isEmpty())
        assertEquals(0, f.runtime.nativeStops); assertEquals(1, done)
    }

    @Test fun differentCurrentStatusWaitsForThePreviousExplanationToActuallyFinish() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.clock.advanceBy(300L); assertTrue(f.controller.speakStatusExplanation("보행 안내를 종료했습니다."))
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(first.id, f.runtime.activeId)
        assertTrue(f.runtime.interrupted.isEmpty())
        f.runtime.done(first.id)
        assertEquals(listOf(true), f.terminals)
        f.clock.advanceBy(100L)
        val second = f.runtime.spoken.last(); assertNotEquals(first.id, second.id)
        assertEquals("보행 안내를 종료했습니다.", second.message)
        f.runtime.start(second.id); f.runtime.done(first.id)
        assertEquals(second.id, f.runtime.activeId)
        f.runtime.done(second.id)
        assertEquals(listOf(true, true), f.terminals)
        assertTrue(f.runtime.interrupted.isEmpty())
    }

    @Test fun explicitCancellationStillStopsTheStatusAndCannotCompleteItsOldCallback() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.controller.cancelCommandSpeechResponse(); f.runtime.done(first.id)
        assertEquals(1, f.runtime.nativeStops); assertFalse(f.controller.hasPendingCommandResponse())
        assertTrue(f.terminals.isEmpty())
    }

    @Test fun equalTextAfterActorOrEpochRetirementCannotReuseTheOldStatusOwner() {
        for (actor in listOf(true, false)) {
            val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
            f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single()
            val oldGeneration = f.controller.currentCommandGeneration(); f.clock.advanceBy(300L)
            f.context = if (actor) f.context.copy(actorId = "new-actor")
                else f.context.copy(walkEpoch = WalkRuntimeEpoch("new-walk", 0L))
            f.controller.speakStatusExplanation(explanation); f.runtime.start(first.id); f.runtime.done(first.id)
            assertTrue(f.controller.currentCommandGeneration() > oldGeneration)
            assertEquals(1, f.runtime.nativeStops)
            assertFalse(f.terminals.contains(true))
            f.clock.advanceBy(100L)
            assertEquals("The new current status must survive cancellation of the old actor/epoch", 2, f.runtime.spoken.size)
            val current = f.runtime.spoken.last()
            assertNotEquals(first.id, current.id)
            f.runtime.start(current.id); f.runtime.done(first.id)
            assertEquals(current.id, f.runtime.activeId)
            assertTrue(f.terminals.isEmpty())
            f.runtime.done(current.id)
            assertEquals(listOf(true), f.terminals)
            assertFalse(f.controller.hasPendingCommandResponse())
        }
    }

    @Test fun actualRiskCanImmediatelyFlushTheCoalescedStatusExplanation() {
        val f = Fixture(); val actor = f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.clock.advanceBy(300L); assertTrue(f.controller.speakStatusExplanation(explanation))
        val action = kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction(
            trackId = "current-risk", message = "멈추세요.", level = kr.co.hanium.dreamup.walksafe.depth.MessageLevel.STOP,
            vibrationPatternMs = null, reason = "controlled-current-risk", validUntilMs = f.clock.now + 1_000L,
        )
        var riskDone = 0
        assertEquals(kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult.ACCEPTED,
            actor.emit(action, { riskDone++ }, null, { true }).speech)
        assertEquals(listOf(first.id), f.runtime.interrupted)
        val risk = f.runtime.spoken.last(); f.runtime.start(risk.id)
        assertTrue(f.controller.speakStatusExplanation(explanation))
        assertEquals("A pending status must never submit or flush while urgent speech owns playback", 2, f.runtime.spoken.size)
        assertEquals(risk.id, f.runtime.activeId)
        f.runtime.done(first.id); f.runtime.done(risk.id)
        assertEquals(1, riskDone); assertFalse(f.terminals.contains(true))
        f.clock.advanceBy(100L)
        assertEquals(3, f.runtime.spoken.size)
        val resumed = f.runtime.spoken.last()
        assertEquals(explanation, resumed.message)
        f.runtime.start(resumed.id); f.runtime.done(first.id); f.runtime.done(risk.id)
        assertTrue(f.terminals.isEmpty())
        assertEquals(resumed.id, f.runtime.activeId)
        f.runtime.done(resumed.id)
        assertEquals(listOf(true), f.terminals)
        assertEquals("Only the real urgent risk may preempt", listOf(first.id), f.runtime.interrupted)
    }

    @Test fun aCompletedExplanationDoesNotKeepALaterRequestCoalesced() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.clock.advanceBy(3_000L); f.runtime.done(first.id)
        val old = f.controller.currentCommandGeneration()
        assertTrue(f.controller.speakStatusExplanation(explanation))
        assertTrue(f.controller.currentCommandGeneration() > old); assertEquals(2, f.runtime.spoken.size)
        val second = f.runtime.spoken.last(); f.runtime.start(second.id); f.runtime.done(second.id)
        assertEquals(listOf(true, true), f.terminals)
    }

    @Test fun aFailedExplanationAllowsACurrentRetryWithoutKeepingTheOldOwner() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.clock.advanceBy(300L); f.runtime.fail(first.id)
        val old = f.controller.currentCommandGeneration()
        assertTrue(f.controller.speakStatusExplanation(explanation))
        assertTrue(f.controller.currentCommandGeneration() > old); assertEquals(2, f.runtime.spoken.size)
        val second = f.runtime.spoken.last(); f.runtime.start(second.id); f.runtime.done(first.id); f.runtime.done(second.id)
        assertEquals(listOf(false, true), f.terminals)
    }

    @Test fun anExplicitCallbackConsumerStillOwnsANewRequestEvenForTheSameWords() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single(); f.runtime.start(first.id)
        f.clock.advanceBy(1_200L); var done = 0
        assertTrue(f.controller.speakCommandResponse(explanation, statusExplanation = true, onCompleted = { done++ }))
        assertEquals(1, f.runtime.spoken.size); assertTrue(f.runtime.interrupted.isEmpty())
        assertEquals(first.id, f.runtime.activeId)
        f.runtime.done(first.id); assertEquals(0, done)
        f.clock.advanceBy(100L)
        assertEquals(2, f.runtime.spoken.size)
        val second = f.runtime.spoken.last(); assertNotEquals(first.id, second.id)
        f.runtime.start(second.id); f.runtime.done(first.id); assertEquals(0, done)
        f.runtime.done(second.id); assertEquals(1, done)
        assertEquals(listOf(true, true), f.terminals)
        assertTrue(f.runtime.interrupted.isEmpty())
    }

    @Test fun backgroundRetirementCannotCoalesceOrStartAnOldStatus() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakStatusExplanation(explanation); val first = f.runtime.spoken.single()
        f.clock.advanceBy(300L); f.lifetime = f.lifetime.copy(foreground = false)
        assertFalse(f.controller.speakStatusExplanation(explanation))
        f.runtime.start(first.id); f.runtime.done(first.id)
        assertEquals(1, f.runtime.nativeStops); assertEquals(1, f.runtime.spoken.size)
        assertFalse(f.terminals.contains(true))
    }

    @Test fun repeatingTheSameExplanationWhileColdDoesNotReplaceItsOriginalReadyContinuation() {
        val f = Fixture(); assertTrue(f.controller.speakStatusExplanation(explanation))
        val old = f.controller.currentCommandGeneration()
        repeat(10) { f.clock.advanceBy(300L); assertTrue(f.controller.speakStatusExplanation(explanation)) }
        assertTrue(f.runtime.spoken.isEmpty()); assertEquals(old, f.controller.currentCommandGeneration())
        f.runtime.ready(); val first = f.runtime.spoken.single(); f.runtime.start(first.id); f.runtime.done(first.id)
        assertEquals(listOf(true), f.terminals); assertFalse(f.controller.hasPendingCommandResponse())
    }

    private enum class Page { HOME, VOICE, SETTINGS }

    private class Fixture {
        val clock = Clock()
        var lifetime = SpeechOutputLifetime(7, true)
        var context = CommandSpeechContext(3, "actor", 11L, WalkRuntimeEpoch("walk", 0L),
            Page.VOICE, true, true, false, false)
        var navigationCurrent = true
        var screenReader = false
        val promptEffects = mutableListOf<Pair<CommandResponseToken, CommandPromptEffect>>()
        val readyTrace = mutableListOf<String>()
        val diagnosticTrace = mutableListOf<String>()
        val storageTrace = mutableListOf<String>()
        val accessibilityMessages = mutableListOf<String>()
        val runtimes = mutableListOf<NativeRuntime>()
        val terminals = mutableListOf<Boolean>()
        val factoryReadyCallbacks = mutableListOf<() -> Unit>()
        val runtime get() = runtimes.last()
        var onRisk: ((AndroidFeedbackActuator, Int, Long) -> Unit)? = null
        val controller = SpeechOutputController(
            debugDiagnostic = { diagnosticTrace += it },
            reader = object : SpeechOutputReader {
                override fun lifetime() = this@Fixture.lifetime
                override fun commandContext() = context
                override fun navigationOwner() = NavigationSpeechOwner(context.walkEpoch, "route", null)
                override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) = navigationCurrent
                override fun homeFeatureContextAvailable() = true
                override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = true
                override fun guidanceVoiceInputActive() = context.recognitionActive
                override fun screenReaderActive() = screenReader
            },
            input = object : SpeechOutputInputEffects {
                override fun invalidateHomeWakeInput() = Unit
                override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) {
                    promptEffects += token to effect
                }
                override fun cancelDeferredInput() = Unit
                override fun refreshHomeWakeListening() = Unit
                override fun cancelHomeWakeListening() = Unit
                override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
            },
            presentation = object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) { terminals += completed }
                override fun accessibilityTargetOrNull() = SpeechAccessibilityTarget { message, _ ->
                    accessibilityMessages += message
                    true
                }
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            },
            readyEffects = object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() { readyTrace += "ui" }
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) {
                    readyTrace += "risk"
                    onRisk?.invoke(actuator, lifecycleGeneration, nowMs)
                }
                override fun retryCurrentNavigation(nowMs: Long) { readyTrace += "navigation" }
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = Unit
                override fun beforeCommandReplacement(previousGeneration: Long) = Unit
            },
            storage = object : SpeechOutputStorageBinding {
                override fun invalidate() { storageTrace += "invalidate" }
                override fun refresh(actuator: AndroidFeedbackActuator) { storageTrace += "refresh" }
            },
            actuatorFactory = SpeechOutputActuatorFactory { unavailable, ready ->
                val native = NativeRuntime(clock).also(runtimes::add)
                factoryReadyCallbacks += ready
                AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            },
            scheduler = clock,
        )
    }

    private class Clock : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var now = 1_000L
        val pendingTasks get() = tasks.size
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0L)
        fun post(owner: Any, task: Runnable, delayMs: Long) {
            tasks += Task(owner, now + delayMs.coerceAtLeast(0L), sequence++, task)
        }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.task === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.task.run()
            }
            now = target
        }
    }

    private class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        val spoken = mutableListOf<Spoken>()
        var shutdowns = 0
        var nativeStops = 0
        var activeId: String? = null
        val interrupted = mutableListOf<String>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            if (queueMode == TextToSpeech.QUEUE_FLUSH && activeId != null) {
                val old = activeId!!; activeId = null; interrupted += old
                requireNotNull(listener).onStop(old, true)
            }
            spoken += Spoken(message, utteranceId, queueMode)
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            nativeStops++
            activeId?.let { old -> activeId = null; interrupted += old; requireNotNull(listener).onStop(old, true) }
        }
        override fun shutdown() { shutdowns++ }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone requested")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); clock.advanceBy(0L) }
        fun failInitialization() { requireNotNull(initialized).invoke(TextToSpeech.ERROR); clock.advanceBy(0L) }
        fun start(id: String) { activeId = id; requireNotNull(listener).onStart(id); clock.advanceBy(0L) }
        fun fail(id: String) { if (activeId == id) activeId = null; requireNotNull(listener).onError(id); clock.advanceBy(0L) }
        fun done(id: String) { if (activeId == id) activeId = null; requireNotNull(listener).onDone(id); clock.advanceBy(0L) }
    }
}
