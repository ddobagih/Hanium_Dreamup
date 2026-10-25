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

/** Real output coordinator/registry/actuator; only Android effects, current facts and time vary. */
class SpeechOutputControllerBehaviorTest {
    @Test
    fun constructionDoesNotStartAnEngineOrTimerAndActorStorageBindingHasOneOwner() {
        val f = Fixture()
        assertTrue(f.runtimes.isEmpty())
        assertEquals(0, f.clock.pendingTasks)
        assertNull(f.controller.currentActuatorOrNull())
        val actor = f.controller.ensureFeedbackActuator()
        assertSame(actor, f.controller.ensureFeedbackActuator())
        assertEquals(1, f.runtimes.size)
        assertEquals(listOf("invalidate", "refresh", "refresh"), f.storageTrace)
        f.controller.closeActuator()
        assertNull(f.controller.currentActuatorOrNull())
        assertEquals(1, f.runtime.shutdowns)
        assertEquals("invalidate", f.storageTrace.last())
    }

    @Test
    fun coldCommandWaitsForReadyAndOnlyNativeDoneCompletesTheOriginalPrompt() {
        val f = Fixture()
        var completed = 0
        var failed = 0
        assertTrue(f.controller.speakCommandResponse("안내가 끝나면 말씀해 주세요.", preparingInput = true,
            onCompleted = { completed++ }, onFailed = { failed++ }))
        assertTrue(f.runtime.spoken.isEmpty())
        assertEquals(listOf("event=OUTPUT_READY_WAIT result=WAITING"), f.diagnosticTrace)
        assertEquals(CommandPromptEffect.PREPARING, f.promptEffects.last().second)
        f.runtime.ready()
        assertEquals(listOf("ui", "risk", "navigation"), f.readyTrace)
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(0, completed)
        val speech = f.runtime.spoken.single()
        f.runtime.start(speech.id)
        assertEquals(0, completed)
        f.runtime.done(speech.id)
        f.runtime.done(speech.id)
        assertEquals(1, completed)
        assertEquals(0, failed)
        assertFalse(f.controller.hasPendingCommandResponse())
    }

    @Test fun candidatePartsAdvanceOnlyOnTheirOwnNativeDoneAndFinishTheCommandOnce() {
        val f = Fixture(); var done = 0
        f.controller.beginCommandSpeech(12L)
        assertTrue(f.controller.speakCommandResponse("전체 후보", speechParts = listOf("후보 머리", "1번 후보", "선택 안내"), onCompleted = { done++ }))
        f.runtime.ready()
        val first = f.runtime.spoken.single()
        f.runtime.start(first.id); f.runtime.done(first.id)
        assertEquals(2, f.runtime.spoken.size); assertEquals(CommandSpeechProgress.PENDING, f.controller.commandSpeechProgress(12L))
        f.runtime.done(first.id); assertEquals(2, f.runtime.spoken.size)
        val second=f.runtime.spoken.last(); f.runtime.start(second.id); f.runtime.done(second.id)
        assertEquals(3, f.runtime.spoken.size); assertEquals(0,done)
        val last=f.runtime.spoken.last(); f.runtime.start(last.id); f.runtime.done(last.id)
        assertEquals(1,done); assertEquals(CommandSpeechProgress.COMPLETED, f.controller.commandSpeechProgress(12L))
    }
    @Test fun stopErrorAndFirstSubmitRejectionCannotStartAnotherPartOrCompleteTheAnswer() {
        for (terminal in listOf("STOP","ERROR","REJECT")) {
            val f=Fixture();var done=0
            f.controller.beginCommandSpeech(1)
            f.controller.speakCommandResponse("후보",speechParts=listOf("첫 후보","둘 후보"),onCompleted={done++})
            if (terminal=="REJECT") f.runtime.speakResult=TextToSpeech.ERROR
            f.runtime.ready();val first=f.runtime.spoken.single()
            when(terminal) { "STOP"->f.runtime.stopUtterance(first.id);"ERROR"->f.runtime.failUtterance(first.id) }
            f.runtime.done(first.id)
            assertEquals(terminal,1,f.runtime.spoken.size);assertEquals(terminal,0,done)
            assertEquals(terminal,CommandSpeechProgress.FAILED,f.controller.commandSpeechProgress(1))
            assertFalse(terminal,f.controller.hasPendingCommandResponse())
        }
    }

    @Test fun asyncReservationTransfersToNativeOutputWithoutFailingOrFinishingEarly() {
        val f=Fixture();f.controller.beginCommandSpeech(1)
        val release=f.controller.reserveCommandSpeech()
        assertEquals(CommandSpeechProgress.PENDING,f.controller.commandSpeechProgress(1))
        f.controller.speakCommandResponse("조회 결과")
        release(true);f.runtime.ready()
        assertEquals(CommandSpeechProgress.PENDING,f.controller.commandSpeechProgress(1))
        val result=f.runtime.spoken.single();f.runtime.start(result.id);f.runtime.done(result.id)
        assertEquals(CommandSpeechProgress.COMPLETED,f.controller.commandSpeechProgress(1))
    }

    @Test fun retiredPartCannotStartTheNextCandidateOrCompleteTheNewCommand() {
        val f=Fixture(); var done=0
        f.controller.beginCommandSpeech(1); f.controller.speakCommandResponse("후보",speechParts=listOf("첫 후보","둘 후보"),onCompleted={done++})
        f.runtime.ready(); val old=f.runtime.spoken.single()
        f.controller.cancelCommandSpeechResponse(); assertEquals(CommandSpeechProgress.FAILED,f.controller.commandSpeechProgress(1))
        f.controller.beginCommandSpeech(2); f.controller.speakCommandResponse("새 명령")
        val size=f.runtime.spoken.size; f.runtime.done(old.id)
        assertEquals(size,f.runtime.spoken.size); assertEquals(0,done)
        assertEquals(CommandSpeechProgress.PENDING,f.controller.commandSpeechProgress(2))
    }

    @Test
    fun readyReentryAndItsPollCannotDispatchTheSameResponseTwice() {
        val f = Fixture()
        var nested: Boolean? = null
        f.onRisk = { actor, generation, now ->
            nested = f.controller.processOfflineSpeechReady(actor, generation, now)
        }
        assertTrue(f.controller.speakCommandResponse("현재 상태를 안내합니다."))
        f.runtime.ready()
        assertEquals(false, nested)
        assertEquals(1, f.runtime.spoken.size)
        assertTrue(f.controller.processOfflineSpeechReady(f.controller.currentActuatorOrNull()!!, 7, f.clock.now))
        f.clock.advanceBy(100L)
        assertEquals(listOf("ui", "risk", "navigation"), f.readyTrace)
        assertEquals(1, f.runtime.spoken.size)
    }

    @Test
    fun backgroundActorAndEpochChangesCancelWaitingCommandWithoutCompletionOrFailure() {
        for (change in listOf("background", "actor", "epoch", "page", "recognition")) {
            val f = Fixture()
            var completed = 0
            var failed = 0
            var cancelled = 0
            f.controller.speakCommandResponse("응답", preparingInput = true, onCompleted = { completed++ },
                onFailed = { failed++ }, onCancelled = { cancelled++ })
            when (change) {
                "background" -> f.lifetime = f.lifetime.copy(foreground = false)
                "actor" -> f.context = f.context.copy(actorId = "replacement")
                "epoch" -> f.context = f.context.copy(walkEpoch = WalkRuntimeEpoch("new-walk", 0L))
                "page" -> f.context = f.context.copy(page = Page.SETTINGS, voiceInteractionPage = false)
                "recognition" -> f.context = f.context.copy(recognitionGeneration = 99)
            }
            f.clock.advanceBy(100L)
            assertTrue(change, f.runtime.spoken.isEmpty())
            assertEquals(change, 0, completed)
            assertEquals(change, 0, failed)
            assertEquals(change, 1, cancelled)
            assertFalse(change, f.controller.hasPendingCommandResponse())
            assertEquals(CommandPromptEffect.CLEAR, f.promptEffects.last().second)
        }
    }

    @Test
    fun retiredNativeCancellationCannotReleaseTheReplacementPrompt() {
        val f = Fixture()
        f.controller.ensureFeedbackActuator()
        f.runtime.ready()
        var oldCancelled = 0
        var newDone = 0
        assertTrue(f.controller.speakCommandResponse("첫 번째", preparingInput = true,
            onCancelled = { oldCancelled++ }))
        val oldToken = f.promptEffects.last().first
        assertTrue(f.controller.speakCommandResponse("두 번째", preparingInput = true,
            onCompleted = { newDone++ }))
        val newToken = f.promptEffects.last().first
        f.clock.advanceBy(0L)
        assertFalse(f.controller.retireCommandSpeechResponseIfOwned(oldToken.responseId, oldToken.generation, true))
        assertEquals(newToken.generation, f.controller.currentCommandGeneration())
        assertTrue(f.controller.hasPendingCommandResponse())
        assertEquals(CommandPromptEffect.PREPARING, f.promptEffects.last().second)
        assertEquals(0, oldCancelled)
        val second = f.runtime.spoken.last()
        f.runtime.start(second.id)
        f.runtime.done(second.id)
        assertEquals(1, newDone)
    }

    @Test
    fun ownedCancellationIsTerminalWithoutReportingDoneOrEngineFailure() {
        val f = Fixture()
        var terminals = 0
        f.controller.speakCommandResponse("대기", preparingInput = true,
            onCompleted = { terminals++ }, onFailed = { terminals++ })
        val token = f.promptEffects.last().first
        assertTrue(f.controller.retireCommandSpeechResponseIfOwned(token.responseId, token.generation, true))
        assertFalse(f.controller.retireCommandSpeechResponseIfOwned(token.responseId, token.generation, true))
        assertEquals(token.generation + 1L, f.controller.currentCommandGeneration())
        assertEquals(CommandPromptEffect.CLEAR, f.promptEffects.last().second)
        assertEquals(0, terminals)
        f.clock.advanceBy(100L)
        assertTrue(f.runtime.spoken.isEmpty())
    }

    @Test
    fun oldReadyCallbackAfterActorCloseCannotRefreshOrReplayOnTheReplacement() {
        val f = Fixture()
        val old = f.controller.ensureFeedbackActuator()
        f.runtime.ready()
        val oldReady = f.factoryReadyCallbacks.single()
        f.readyTrace.clear()
        f.controller.closeActuator()
        val replacement = f.controller.ensureFeedbackActuator()
        assertNotSame(old, replacement)
        oldReady()
        assertTrue(f.readyTrace.isEmpty())
        assertSame(replacement, f.controller.currentActuatorOrNull())
        f.runtime.ready()
        assertEquals(listOf("ui", "risk", "navigation"), f.readyTrace)
    }

    @Test
    fun sourceRetirementDuringReadyStopsBeforeNavigationAndCommandResume() {
        val f = Fixture()
        f.onRisk = { _, _, _ -> f.lifetime = f.lifetime.copy(generation = 8) }
        var completed = 0
        f.controller.speakCommandResponse("기다린 응답", onCompleted = { completed++ })
        f.runtime.ready()
        assertEquals(listOf("ui", "risk"), f.readyTrace)
        f.clock.advanceBy(100L)
        assertTrue(f.runtime.spoken.isEmpty())
        assertEquals(0, completed)
    }

    @Test
    fun navigationFallbackDispatchDoesNotCommitSpokenNavigationCompletion() {
        val f = Fixture()
        f.screenReader = true
        f.controller.ensureFeedbackActuator()
        f.runtime.failInitialization()
        var completed = 0
        var failed = 0
        f.controller.dispatchNavigationSpeech("직진하세요.", { completed++ }, { failed++ })
        assertEquals(listOf("직진하세요."), f.accessibilityMessages)
        assertEquals(0, completed)
        assertEquals(1, failed)
        assertTrue(f.runtime.spoken.isEmpty())
    }

    @Test
    fun navigationChangedBeforeNativeStartCannotCompleteAnOldInstruction() {
        val f = Fixture()
        f.controller.ensureFeedbackActuator()
        f.runtime.ready()
        var completed = 0
        var failed = 0
        assertTrue(f.controller.dispatchNavigationSpeech("오른쪽으로 이동하세요.", { completed++ }, { failed++ }))
        val id = f.runtime.spoken.single().id
        f.navigationCurrent = false
        f.runtime.start(id)
        f.runtime.done(id)
        assertEquals(0, completed)
        assertEquals(1, failed)
    }

    @Test
    fun advisoryKeepsProducerGuardAndWaitsForActualNativeDone() {
        val f = Fixture()
        var current = false
        var completed = 0
        var failed = 0
        assertFalse(f.controller.speakAdvisory("주변을 확인하세요.", f.clock.now + 2_000L,
            { current }, { completed++ }, { failed++ }))
        assertTrue(f.runtimes.isEmpty())
        assertEquals(1, failed)
        current = true
        f.controller.ensureFeedbackActuator()
        f.runtime.ready()
        assertTrue(f.controller.speakAdvisory("주변을 확인하세요.", f.clock.now + 2_000L,
            { current }, { completed++ }, { failed++ }))
        assertEquals(0, completed)
        val id = f.runtime.spoken.single().id
        f.runtime.start(id)
        f.runtime.done(id)
        assertEquals(1, completed)
        assertEquals(1, failed)
    }

    @Test
    fun talkbackRiskPriorityAndDeferredInteractionRetainTheirExistingAdmission() {
        val f = Fixture()
        assertTrue(f.controller.announceForTalkBack("위험", SpeechAnnouncementPriority.RISK,
            riskRank = 1, riskTrackId = "track"))
        assertFalse(f.controller.announceForTalkBack("위험", SpeechAnnouncementPriority.RISK,
            riskRank = 1, riskTrackId = "track"))
        assertTrue(f.controller.announceForTalkBack("더 큰 위험", SpeechAnnouncementPriority.RISK,
            riskRank = 2, riskTrackId = "track"))
        assertTrue(f.controller.scheduleTalkBackInteraction("응답", 500L))
        assertEquals(listOf("위험", "더 큰 위험"), f.accessibilityMessages)
        f.controller.reconcileTalkBackRiskTracks(emptySet())
        assertEquals(listOf("위험", "더 큰 위험", "응답"), f.accessibilityMessages)
        assertTrue(f.runtimes.isEmpty())
    }

    internal enum class Page { HOME, VOICE, SETTINGS }

    internal class Fixture {
        val clock = Clock()
        var lifetime = SpeechOutputLifetime(7, true)
        var context = CommandSpeechContext(3, "actor", 11L, WalkRuntimeEpoch("walk", 0L),
            Page.VOICE, true, true, false, false)
        var navigationCurrent = true
        var screenReader = false
        var homeAvailable = true
        var nativeSpeechAllowed: () -> Boolean = { true }
        val terminals = mutableListOf<Boolean>()
        val promptEffects = mutableListOf<Pair<CommandResponseToken, CommandPromptEffect>>()
        val readyTrace = mutableListOf<String>()
        val diagnosticTrace = mutableListOf<String>()
        val storageTrace = mutableListOf<String>()
        val accessibilityMessages = mutableListOf<String>()
        val runtimes = mutableListOf<NativeRuntime>()
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
                override fun homeFeatureContextAvailable() = homeAvailable
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
                AndroidFeedbackActuator(runtime = native, speechAllowed = { nativeSpeechAllowed() },
                    onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            },
            scheduler = clock,
        )
    }

    internal class Clock : SpeechOutputScheduler {
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

    internal class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        val spoken = mutableListOf<Spoken>()
        var shutdowns = 0
        var speakResult=TextToSpeech.SUCCESS
        var stops = 0
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
            spoken += Spoken(message, utteranceId)
            return speakResult
        }
        override fun stop() { stops++ }
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
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0L) }
        fun stopUtterance(id:String) { requireNotNull(listener).onStop(id,true);clock.advanceBy(0L) }
        fun failUtterance(id:String) { requireNotNull(listener).onError(id,TextToSpeech.ERROR);clock.advanceBy(0L) }
    }
}
