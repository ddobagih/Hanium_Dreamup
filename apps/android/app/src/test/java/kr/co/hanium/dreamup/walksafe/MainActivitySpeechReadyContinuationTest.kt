package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.CommandSpeechReadiness
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechRuntime
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechScheduler
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.ProgressToneEffects
import kr.co.hanium.dreamup.walksafe.feedback.TtsEngineRecoveryBudget
import kr.co.hanium.dreamup.walksafe.feedback.UtteranceCallbackRegistry
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main READY ownership, real registry and actuator; native effects and their clock are fake. */
class MainActivitySpeechReadyContinuationTest {
    @Test
    fun nativeReadyCallbackRunsUiAndRouteBeforeCancellingPollAndSubmittingCommandOnce() {
        val fixture = Fixture()
        val command = fixture.remember()
        assertEquals(CommandSpeechReadiness.WAITING, fixture.readiness(command))

        fixture.runtime.ready()

        fixture.assertCallbackSucceeded()
        assertEquals(listOf("ui", "route", "cancel_poll", "command"), fixture.trace)
        assertEquals(listOf(true), fixture.handled)
        assertEquals(1, command.resumed)
        assertEquals(1, command.pollCancelled)
        assertEquals(CommandSpeechReadiness.DISPATCH, command.readiness)
        assertEquals(1, fixture.runtime.spoken.size)
        assertEquals(1, fixture.runtime.focusRequests)
        assertNull(field(fixture.main, "pendingCommandSpeechReadyContinuation"))
        assertTrue(fixture.process(fixture.actuator, GENERATION))
        assertEquals(1, command.resumed)
        assertEquals(1, fixture.runtime.spoken.size)

        val utterance = fixture.runtime.spoken.single().id
        fixture.runtime.start(utterance)
        fixture.runtime.done(utterance)
        fixture.runtime.done(utterance)
        assertEquals(1, command.completed)
        assertEquals(0, command.failed)
        assertEquals(0, fixture.registry.size())
        assertEquals(1, fixture.runtime.focusReleases)
        assertFalse(fixture.actuator.isAppSpeechActive())
    }

    @Test
    fun pollObservedRealReadyBeforeQueuedUiCallbackCannotSubmitTwice() {
        val fixture = Fixture(deferReadyCallback = true)
        val command = fixture.remember()
        fixture.runtime.ready()
        assertNotNull(fixture.actuator.readySpeechEngineGenerationOrNull())
        assertEquals(1, fixture.queuedReadyCallbacks.size)
        assertTrue(fixture.trace.isEmpty())

        // The real Main preflight is invoked before the delayed UI callback; no poll clone is used.
        assertTrue(fixture.process(fixture.actuator, GENERATION))
        assertEquals(1, fixture.runtime.spoken.size)
        fixture.drainReadyCallbacks()

        fixture.assertCallbackSucceeded()
        assertEquals(listOf("ui", "route", "cancel_poll", "command"), fixture.trace)
        assertEquals(1, command.resumed)
        assertEquals(1, command.pollCancelled)
        assertEquals(1, fixture.runtime.spoken.size)
        assertEquals(1, fixture.runtime.focusRequests)
    }

    @Test
    fun processingReentryIsRejectedAndCompletedReentryDoesNotResumeCommandAgain() {
        val fixture = Fixture()
        var processingReentry: Boolean? = null
        var completedReentry: Boolean? = null
        fixture.duringUi = { processingReentry = fixture.process(fixture.actuator, GENERATION) }
        val command = fixture.remember()
        command.onCancelPoll = {
            assertNull(field(fixture.main, "pendingCommandSpeechReadyContinuation"))
            completedReentry = fixture.process(fixture.actuator, GENERATION)
        }

        fixture.runtime.ready()

        fixture.assertCallbackSucceeded()
        assertEquals(false, processingReentry)
        assertEquals(true, completedReentry)
        assertEquals(listOf("ui", "route", "cancel_poll", "command"), fixture.trace)
        assertEquals(1, command.resumed)
        assertEquals(1, command.pollCancelled)
        assertEquals(1, fixture.runtime.spoken.size)
    }

    @Test
    fun staleContinuationActorLifecycleOrResponseIsRetiredWithoutNativeSubmission() {
        for (change in listOf("actuator", "lifecycle", "response")) {
            val fixture = Fixture()
            val command = fixture.remember()
            when (change) {
                "actuator" -> {
                    val replacementRuntime = FakeRuntime()
                    val replacement = fixture.newActuator(replacementRuntime, GENERATION)
                    set(fixture.main, "feedbackActuator", replacement)
                    replacementRuntime.ready()
                }
                "lifecycle" -> {
                    set(fixture.main, "feedbackLifecycleGeneration", GENERATION + 1)
                    fixture.deferReadyCallback = true
                    fixture.runtime.ready()
                    assertTrue(fixture.process(fixture.actuator, GENERATION + 1))
                }
                "response" -> {
                    set(fixture.main, "commandSpeechResponseGeneration", 2L)
                    fixture.runtime.ready()
                }
            }

            fixture.assertCallbackSucceeded()
            assertEquals(change, 1, command.retired)
            assertEquals(change, 1, command.pollCancelled)
            assertEquals(change, 0, command.resumed)
            assertTrue(change, fixture.runtime.spoken.isEmpty())
            assertEquals(change, 0, fixture.runtime.focusRequests)
            assertNull(field(fixture.main, "pendingCommandSpeechReadyContinuation"))
        }
    }

    @Test
    fun oldActuatorReadyCallbackAndOldClearCannotConsumeANewerResponse() {
        val fixture = Fixture(deferReadyCallback = true)
        val old = fixture.remember()
        fixture.runtime.ready()
        val replacementRuntime = FakeRuntime()
        val replacement = fixture.newActuator(replacementRuntime, GENERATION)
        set(fixture.main, "feedbackActuator", replacement)
        val current = fixture.remember(
            id = "command-new", generation = 2L,
            ownerActuator = replacement, ownerRuntime = replacementRuntime,
        )
        assertEquals(1, old.pollCancelled)
        fixture.main.clearCommandSpeechReadyContinuationIfOwned(old.id, old.generation)
        assertFalse(fixture.main.retireCommandSpeechResponseIfOwned(old.id, old.generation))
        fixture.drainReadyCallbacks()
        assertEquals(listOf(false), fixture.handled)
        assertEquals(0, current.pollCancelled)
        assertEquals(0, current.resumed)
        assertEquals(2L, field(fixture.main, "commandSpeechResponseGeneration"))

        replacementRuntime.ready()
        fixture.drainReadyCallbacks()

        fixture.assertCallbackSucceeded()
        assertEquals(0, old.resumed)
        assertEquals(1, current.resumed)
        assertEquals(1, current.pollCancelled)
        assertEquals(0, current.retired)
        assertEquals(1, replacementRuntime.spoken.size)
        assertTrue(fixture.runtime.spoken.isEmpty())
        val utterance = replacementRuntime.spoken.single().id
        replacementRuntime.start(utterance)
        replacementRuntime.done(utterance)
        assertEquals(1, current.completed)
        assertEquals(0, old.completed)
        assertEquals(0, fixture.registry.size())
    }

    @Test
    fun originalReadinessDeadlineAndCurrentRegistryChecksStillRejectResume() {
        for (change in listOf("deadline", "registry", "request")) {
            val fixture = Fixture(deferReadyCallback = true)
            val command = fixture.remember()
            fixture.runtime.ready()
            when (change) {
                "deadline" -> fixture.runtime.advanceBy(command.deadlineMs - fixture.runtime.now)
                "registry" -> fixture.registry.cancel(listOf(command.id))
                "request" -> command.requestCurrent = false
            }
            fixture.drainReadyCallbacks()

            fixture.assertCallbackSucceeded()
            assertEquals(change, 1, command.resumed)
            assertEquals(change, 1, command.pollCancelled)
            assertEquals(
                change,
                if (change == "deadline") CommandSpeechReadiness.TIMED_OUT else CommandSpeechReadiness.CANCELLED,
                command.readiness,
            )
            assertTrue(change, fixture.runtime.spoken.isEmpty())
            assertEquals(change, 0, fixture.runtime.focusRequests)
            assertEquals(change, 0, command.completed)
            assertEquals(change, if (change == "registry") 0 else 1, command.failed)
            assertTrue(fixture.process(fixture.actuator, GENERATION))
            assertEquals(change, 1, command.resumed)
            assertEquals(0, fixture.registry.size())
        }
    }

    @Test
    fun initializingFailedAndClosedEnginesNeverProcessAReadyContinuation() {
        for (state in listOf("initializing", "failed", "closed")) {
            val fixture = Fixture()
            val command = fixture.remember()
            when (state) {
                "failed" -> fixture.runtime.failInitialization()
                "closed" -> fixture.actuator.close()
            }

            assertNull(state, fixture.actuator.readySpeechEngineGenerationOrNull())
            assertFalse(state, fixture.process(fixture.actuator, GENERATION))
            assertTrue(state, fixture.trace.isEmpty())
            assertEquals(state, 0, command.resumed)
            assertEquals(state, 0, command.pollCancelled)
            assertTrue(state, fixture.runtime.spoken.isEmpty())
            assertEquals(state, 0, fixture.runtime.focusRequests)
        }
    }

    @Test
    fun ownershipChangedDuringUiOrRouteAbortsBeforeCommandResume() {
        for (stage in listOf("ui", "route")) {
            for (change in listOf("actuator", "lifecycle", "background", "engine")) {
                val fixture = Fixture()
                val command = fixture.remember()
                val invalidate: () -> Unit = {
                    when (change) {
                        "actuator" -> set(fixture.main, "feedbackActuator", AndroidFeedbackActuator(runtime = FakeRuntime()))
                        "lifecycle" -> set(fixture.main, "feedbackLifecycleGeneration", GENERATION + 1)
                        "background" -> set(fixture.main, "isActivityForeground", false)
                        "engine" -> fixture.actuator.close()
                    }
                }
                if (stage == "ui") fixture.duringUi = invalidate
                else fixture.duringRoute = { invalidate() }
                fixture.runtime.ready()

                fixture.assertCallbackSucceeded()
                assertEquals("$stage/$change", listOf(false), fixture.handled)
                assertEquals("$stage/$change", if (stage == "ui") listOf("ui") else listOf("ui", "route"), fixture.trace)
                assertEquals("$stage/$change", 0, command.resumed)
                assertEquals("$stage/$change", 0, command.pollCancelled)
                assertTrue("$stage/$change", fixture.runtime.spoken.isEmpty())
                assertEquals("$stage/$change", 0, fixture.runtime.focusRequests)
            }
        }
    }

    @Test
    fun sourceOnlyProductionPollAndNativeReadyCallbackShareTheOwnedContinuationPreflight() {
        val source = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
        val ensure = MainActivityRefactorSource.function(source, "private fun ensureFeedbackActuator")
        val composition = MainActivityRefactorSource.member(source, "createSpeechOutputController")
        assertTrue(ensure.contains("onReady ="))
        assertTrue(composition.contains("onOfflineKoreanSpeechReady = ready"))
        assertTrue(ensure.contains("processOfflineSpeechReady(actor, generation, scheduler.elapsedRealtimeMs())"))
        assertTrue(composition.contains("scheduler = voiceCallbacks"))
        val mainCallbacks = MainActivityRefactorSource.property(source, "voiceCallbacks")
        assertTrue(mainCallbacks.contains("AndroidVoiceCallbacks("))
        val callbacks = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt")
        val callbackConstructor = callbacks.substringAfter("internal class AndroidVoiceCallbacks(")
            .substringBefore(") : VoiceInputScheduler")
        assertTrue(callbackConstructor.contains("private val clock: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(MainActivityRefactorSource.member(callbacks, "elapsedRealtimeMs").contains("clock()"))
        val commandResponse = MainActivityRefactorSource.function(source, "private fun speakCommandResponse")
        val dispatch = ReportStaticSourceInspector.functionBlock(commandResponse, "fun dispatchWhenReady")
        assertTrue(dispatch.contains("resume = { dispatchWhenReady() }"))
        assertTrue(dispatch.contains("cancelPoll = { scheduler.removeReadiness(readinessPoll) }"))
        assertTrue(MainActivityRefactorSource.member(callbacks, "removeReadiness").contains("readiness.remove(task)"))
        assertTrue(MainActivityRefactorSource.property(callbacks, "readiness").contains("by lazy { readinessFactory() }"))
        assertTrue(callbackConstructor.contains("val handler = Handler(Looper.getMainLooper())"))
        assertTrue(callbackConstructor.contains("HandlerVoiceCallbackQueue { handler }"))
        val handlerQueue = ReportStaticSourceInspector.blockAfter(callbacks, "internal class HandlerVoiceCallbackQueue")
        assertTrue(MainActivityRefactorSource.member(handlerQueue, "remove").contains("handler().removeCallbacks(task)"))
        assertTrue(dispatch.contains("onRetired = cancelled"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            dispatch, "commandSpeechResponseCallbacks.commandSpeechReadiness(",
            "deadlineMs = if (statusExplanation && !actuator.isSpeechInitializing()) Long.MAX_VALUE",
            "else readinessDeadlineMs", "processOfflineSpeechReady(actuator, lifecycleGeneration, scheduler.elapsedRealtimeMs())",
            "if (dispatchAttempted) return dispatchResult ?: true", "dispatchAttempted = true", "dispatchCommandSpeechResponse(",
        ))
        val shared = MainActivityRefactorSource.function(source, "internal fun dispatchCommandSpeechResponse(")
        val ordinary = ReportStaticSourceInspector.blockAfter(
            shared.substringAfter("} else if (homeOrStatusResponse) {"), "} else",
        )
        assertEquals(
            "{actuator.speakGuardedInteraction(message,isCurrent," +
                "onCompleted=completed,onFailed=failed,onCancelled=cancelled," +
                "deferToCurrentSpeech=deferToCurrentSpeech)}",
            ordinary.lineSequence().filterNot { it.trimStart().startsWith("//") }
                .joinToString("").filterNot(Char::isWhitespace),
        )
        assertFalse(ordinary.contains("actuator.speakInteraction("))
        val actuatorSource = ReportStaticSourceInspector.read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
        )
        val guarded = MainActivityRefactorSource.member(actuatorSource, "speakGuardedInteraction")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(guarded,
            "): NavigationSpeechDispatchResult = speak(", "priority = SpeechPriority.INTERACTION",
            "onCompleted = onCompleted", "onFailed = onFailed",
            "advisoryStartValidator = isStillValidAtStart", "requiresExplicitTerminalCallback = true",
            "onCancelled = onCancelled ?: onFailed", "onCloseCancelled = onCancelled",
            "deferToCurrentSpeech = deferToCurrentSpeech",
        ))
        val handle = MainActivityRefactorSource.function(source, "internal fun handleOfflineSpeechReady")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            handle, "refreshReadyUi()", "readyEffects.reevaluateCurrentRisk(",
            "retryCurrentNavigation(nowMs)", "completedSpeechReadyOwner = owner", "resumeCurrentCommandSpeechAfterReady(",
        ))
        assertTrue(composition.contains("reevaluateCurrentRiskAfterSpeechReady(actuator, lifecycleGeneration, nowMs)"))
        // SOURCE_ONLY: this checks production binding, not execution of Android Handler/UI or a route request.
    }

    private class Command(
        val id: String,
        val generation: Long,
        val ownerActuator: AndroidFeedbackActuator,
        val ownerRuntime: FakeRuntime,
        val deadlineMs: Long,
    ) {
        var requestCurrent = true
        var resumed = 0
        var pollCancelled = 0
        var retired = 0
        var completed = 0
        var failed = 0
        var readiness: CommandSpeechReadiness? = null
        var onCancelPoll: (() -> Unit)? = null
    }

    private class Fixture(var deferReadyCallback: Boolean = false) {
        val main = MainActivity()
        val runtime = FakeRuntime()
        val trace = mutableListOf<String>()
        val handled = mutableListOf<Boolean>()
        val queuedReadyCallbacks = mutableListOf<() -> Unit>()
        val callbackFailures = mutableListOf<Throwable>()
        var duringUi: (() -> Unit)? = null
        var duringRoute: (() -> Unit)? = null
        val actuator = newActuator(runtime, GENERATION)
        val registry = field(main, "commandSpeechResponseCallbacks") as UtteranceCallbackRegistry

        init {
            set(main, "isActivityForeground", true)
            set(main, "feedbackLifecycleGeneration", GENERATION)
            set(main, "feedbackActuator", actuator)
        }

        fun newActuator(nativeRuntime: FakeRuntime, lifecycleGeneration: Int): AndroidFeedbackActuator {
            lateinit var created: AndroidFeedbackActuator
            created = AndroidFeedbackActuator(
                runtime = nativeRuntime,
                recoveryBudget = TtsEngineRecoveryBudget(automaticRetries = 0),
                onOfflineKoreanSpeechReady = {
                    val callback: () -> Unit = {
                        try {
                            process(created, lifecycleGeneration)
                        } catch (failure: Throwable) {
                            callbackFailures += failure
                        }
                    }
                    if (deferReadyCallback) queuedReadyCallbacks += callback else callback()
                },
            )
            return created
        }

        fun process(expectedActuator: AndroidFeedbackActuator, lifecycleGeneration: Int): Boolean =
            main.handleOfflineSpeechReady(
                expectedActuator = expectedActuator,
                expectedLifecycleGeneration = lifecycleGeneration,
                nowMs = runtime.now,
                refreshReadyUi = { trace += "ui"; duringUi?.invoke() },
                retryCurrentNavigation = { trace += "route"; duringRoute?.invoke() },
            ).also { handled += it }

        fun remember(
            id: String = "command-current",
            generation: Long = 1L,
            ownerActuator: AndroidFeedbackActuator = actuator,
            ownerRuntime: FakeRuntime = runtime,
        ): Command {
            val command = Command(id, generation, ownerActuator, ownerRuntime,
                ownerRuntime.now + AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS)
            set(main, "commandSpeechResponseGeneration", generation)
            registry.registerLatest(id, { command.completed += 1 }, { command.failed += 1 })
            main.rememberCommandSpeechReadyContinuation(
                responseId = id,
                responseGeneration = generation,
                actuator = ownerActuator,
                lifecycleGeneration = GENERATION,
                resume = {
                    command.resumed += 1
                    trace += "command"
                    // The actual registry retains the original request and deadline in this consumer callback.
                    val decision = readiness(command)
                    command.readiness = decision
                    if (decision == CommandSpeechReadiness.DISPATCH) {
                        ownerActuator.speakInteraction(
                            message = "현재 위치를 안내합니다.",
                            onCompleted = { registry.takeTerminalCallback(id, completed = true, notifyFailure = false)?.invoke() },
                            onFailed = { registry.takeTerminalCallback(id, completed = false, notifyFailure = true)?.invoke() },
                            onCancelled = { registry.takeTerminalCallback(id, completed = false, notifyFailure = false, cancelled = true)?.invoke() },
                        ) == NavigationSpeechDispatchResult.ACCEPTED
                    } else {
                        registry.takeTerminalCallback(id, completed = false, notifyFailure = true)?.invoke()
                        false
                    }
                },
                cancelPoll = { command.pollCancelled += 1; trace += "cancel_poll"; command.onCancelPoll?.invoke() },
                onRetired = {
                    command.retired += 1
                    trace += "retired"
                    main.retireCommandSpeechResponseIfOwned(id, generation)
                },
            )
            return command
        }

        fun readiness(command: Command): CommandSpeechReadiness = registry.commandSpeechReadiness(
            utteranceId = command.id,
            requestCurrent = command.requestCurrent &&
                field(main, "commandSpeechResponseGeneration") == command.generation &&
                field(main, "feedbackActuator") === command.ownerActuator,
            speechInitializing = command.ownerActuator.isSpeechInitializing(),
            nowMs = command.ownerRuntime.now,
            deadlineMs = command.deadlineMs,
        )

        fun drainReadyCallbacks() {
            val callbacks = queuedReadyCallbacks.toList()
            queuedReadyCallbacks.clear()
            callbacks.forEach { it() }
        }

        fun assertCallbackSucceeded() {
            assertTrue("Native READY callback exceptions must not become behavioral results: $callbackFailures", callbackFailures.isEmpty())
        }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        var focusRequests = 0
        var focusReleases = 0
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
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            focusRequests += 1
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { focusReleases += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("Command READY must not create a tone")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun failInitialization() { requireNotNull(initialized).invoke(TextToSpeech.ERROR); advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
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
        const val GENERATION = 7

        fun field(main: MainActivity, name: String): Any? =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)

        fun set(main: MainActivity, name: String, value: Any?) {
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        }
    }
}
