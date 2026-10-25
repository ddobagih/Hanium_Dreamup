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

/** The same assertions reach the original public dispatch before guarded dispatch is available. */
class AndroidFeedbackActuatorInteractionCurrentnessRegressionTest {
    @Test
    fun lateInteractionStartRejectsTheCapturedSourceThatChanged() {
        assertLateStartRejectsChangedSource(home = false)
    }

    @Test
    fun lateHomeStartRejectsTheCapturedSourceThatChanged() {
        assertLateStartRejectsChangedSource(home = true)
    }

    private fun assertLateStartRejectsChangedSource(home: Boolean) {
        for (change in InteractionSourceChange.entries) {
            InteractionStartFixture().use { f ->
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(home))
                val id = f.runtime.spoken.single().id
                assertEquals(TextToSpeech.QUEUE_FLUSH, f.runtime.spoken.single().queueMode)
                f.changeSource(change)
                f.runtime.start(id)
                assertEquals("home=$home source=$change", 1, f.events.cancelled)
                f.runtime.start(id)
                f.runtime.done(id)
                f.runtime.error(id)
                assertEquals(1, f.events.cancelled)
                assertEquals(0, f.events.completed)
                assertEquals(0, f.events.failed)
                assertEquals(1, f.runtime.stops)
                assertFalse(f.actor.isAppSpeechActive())
                assertEquals("tts=ready", f.actor.statusText())
            }
        }
    }
}

/** Candidate effects and compatibility checks; baseline compiles these without a new-type reference. */
class AndroidFeedbackActuatorGuardedInteractionTest {
    @Test
    fun aStaleReadyRequestIsSuppressedBeforeAdmissionForBothEntryPoints() {
        for (home in listOf(false, true)) InteractionStartFixture().use { f ->
            f.changeSource(InteractionSourceChange.ROUTE)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(home))
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(0, f.runtime.focusRequests)
            assertEquals(0, f.events.cancelled)
            assertFalse(f.actor.isAppSpeechActive())
        }
    }

    @Test
    fun initializationDoesNotOwnOrQueueAResponseAndReadyRetryRechecksItsSource() {
        for (home in listOf(false, true)) InteractionStartFixture(ready = false).use { f ->
            var checks = 0
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.dispatch(home) {
                checks += 1
                f.sourceIsCurrent()
            })
            assertEquals(0, checks)
            assertFalse(f.actor.isAppSpeechActive())
            f.changeSource(InteractionSourceChange.EPOCH)
            f.runtime.ready()
            assertTrue("READY does not replay an anonymous response", f.runtime.spoken.isEmpty())
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(home))
            assertEquals(0, f.events.cancelled)
        }
    }

    @Test
    fun focusAndRequestedObserverInvalidationUseTheRegisteredValidatorBeforeNativeSpeak() {
        for (fromObserver in listOf(false, true)) InteractionStartFixture().use { f ->
            if (fromObserver) {
                f.actor.speechOutputListener = { event ->
                    if (event.outcome == SpeechOutputOutcome.REQUESTED) f.changeSource(InteractionSourceChange.PROGRESS)
                }
            } else {
                f.runtime.onFocusRequest = { f.changeSource(InteractionSourceChange.REINSTALL) }
            }
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(home = false))
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(1, f.events.cancelled)
            assertEquals(0, f.events.completed)
            assertEquals(0, f.events.failed)
            assertFalse(f.actor.isAppSpeechActive())
        }
    }

    @Test
    fun currentHomeAndInteractionFinishNormallyWithCallerChecksOutsideBothLocks() {
        for (home in listOf(false, true)) InteractionStartFixture().use { f ->
            val checks = mutableListOf<Boolean>()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(home) {
                assertFalse(Thread.holdsLock(f.actorLock("pendingUtterances")))
                assertFalse(Thread.holdsLock(f.actorLock("speechEngineOperationLock")))
                checks += f.actor.validatingStartedSpeech
                f.sourceIsCurrent()
            })
            assertTrue(checks.isNotEmpty())
            assertTrue(checks.none { it })
            val id = f.runtime.spoken.single().id
            f.runtime.start(id)
            assertEquals(1, checks.count { it })
            assertFalse(f.actor.validatingStartedSpeech)
            // After a valid START, source expiry alone never becomes an extra completion trigger.
            f.changeSource(InteractionSourceChange.PROGRESS)
            f.runtime.done(id)
            assertEquals(1, f.events.completed)
            assertEquals(0, f.events.cancelled)
            assertEquals(0, f.events.failed)
        }
    }

    @Test
    fun aThrowingStartPredicateCancelsOnceAndIgnoresLateTerminalCallbacks() {
        InteractionStartFixture().use { f ->
            var throwAtStart = false
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(home = false) {
                if (throwAtStart) error("source retired") else true
            })
            val id = f.runtime.spoken.single().id
            throwAtStart = true
            f.runtime.start(id)
            f.runtime.done(id)
            f.runtime.error(id)
            assertEquals(1, f.events.cancelled)
            assertEquals(0, f.events.failed)
            assertEquals(0, f.events.completed)
            assertEquals("tts=ready", f.actor.statusText())
        }
    }

    @Test
    fun startPredicateReentryCannotStopTheNewRiskThatAlreadyRetiredTheResponse() {
        InteractionStartFixture().use { f ->
            val riskEvents = InteractionStartEvents()
            var replaceAtStart = false
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(home = false) {
                if (replaceAtStart) {
                    replaceAtStart = false
                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.emit(
                        FeedbackAction("new-risk", "멈추세요", MessageLevel.STOP, null,
                            "guarded-start-reentry", Long.MAX_VALUE),
                        riskEvents::completed, riskEvents::failed, { true }, riskEvents::cancelled,
                    ).speech)
                    false
                } else true
            })
            val oldId = f.runtime.spoken.single().id
            replaceAtStart = true
            f.runtime.start(oldId)
            val newId = f.runtime.spoken.last().id
            assertEquals(1, f.events.cancelled)
            assertEquals(0, f.runtime.stops)
            assertEquals(newId, f.runtime.activeSpeechId)
            f.runtime.done(oldId)
            f.runtime.error(oldId)
            assertEquals(newId, f.runtime.activeSpeechId)
            f.runtime.start(newId)
            f.runtime.done(newId)
            assertEquals(1, riskEvents.completed)
            assertEquals(0, riskEvents.cancelled)
        }
    }

    @Test
    fun criticalConfirmationKeepsItsProtectionAgainstTheGuardedResponse() {
        InteractionStartFixture().use { f ->
            val critical = InteractionStartEvents()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakExplicitConfirmation(
                "동의 내용을 확인해 주세요", critical::completed, critical::failed, critical::cancelled,
            ))
            val id = f.runtime.spoken.single().id
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(home = false))
            assertEquals(id, f.runtime.activeSpeechId)
            assertEquals(0, f.runtime.stops)
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(1, critical.completed)
        }
    }

    @Test
    fun microphoneWaitsForOutputThenResumesAfterStaleCancellationAndExistingQuietTail() {
        InteractionStartFixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(home = false))
            val id = f.runtime.spoken.single().id
            assertFalse(f.actor.prepareForSpeechRecognition())
            assertEquals(0, f.runtime.stops)
            f.changeSource(InteractionSourceChange.ROUTE)
            f.runtime.start(id)
            assertEquals(1, f.events.cancelled)
            assertTrue(f.actor.isSpeechRecognitionBlocked())
            f.runtime.advanceBy(2_000L)
            assertTrue(f.actor.prepareForSpeechRecognition())
            assertEquals(0, f.runtime.grantedFocus.size)
        }
    }

    @Test
    fun homeBypassesWalkingCapabilityExactlyAsBeforeWhileGeneralInteractionDoesNot() {
        for (home in listOf(false, true)) InteractionStartFixture(speechAllowed = { false }).use { f ->
            assertEquals(if (home) NavigationSpeechDispatchResult.ACCEPTED else NavigationSpeechDispatchResult.UNAVAILABLE,
                f.dispatch(home))
            if (home) f.runtime.done(f.runtime.spoken.single().id)
            assertEquals(if (home) 1 else 0, f.events.completed)
        }
    }

    @Test
    fun legacyBooleanAndTrailingCancellationCallbacksKeepTheirBindings() {
        InteractionStartFixture().use { f ->
            assertTrue(f.actor.speakInteraction("legacy Boolean"))
            f.runtime.done(f.runtime.spoken.single().id)
            assertFalse(f.actor.isAppSpeechActive())
        }
        for (home in listOf(false, true)) InteractionStartFixture().use { f ->
            val result = if (home) f.actor.speakHomeCommandInteraction(
                "legacy home", f.events::failed, f.events::completed,
            ) { f.events.cancelled() } else f.actor.speakInteraction(
                "legacy interaction", f.events::completed, f.events::failed,
            ) { f.events.cancelled() }
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, result)
            f.actor.close()
            assertEquals(0, f.events.cancelled)
            f.runtime.advanceBy(0L)
            assertEquals(1, f.events.cancelled)
            assertEquals(0, f.events.failed)
            assertEquals(0, f.events.completed)
        }
    }
}

private enum class InteractionSourceChange { ROUTE, REINSTALL, PROGRESS, EPOCH }
private data class InteractionSourceSnapshot(val route: Long = 1, val install: Long = 1,
    val progress: Long = 1, val epoch: Long = 1)

private class InteractionStartEvents {
    var completed = 0
    var failed = 0
    var cancelled = 0
    fun completed() { completed += 1 }
    fun failed() { failed += 1 }
    fun cancelled() { cancelled += 1 }
}

private class InteractionStartFixture(ready: Boolean = true, speechAllowed: () -> Boolean = { true }) : AutoCloseable {
    val runtime = InteractionStartRuntime()
    val events = InteractionStartEvents()
    val actor = AndroidFeedbackActuator(runtime = runtime, speechAllowed = speechAllowed)
    private val capturedSource = InteractionSourceSnapshot()
    private var currentSource = capturedSource

    init { if (ready) runtime.ready() }
    fun sourceIsCurrent(): Boolean = capturedSource == currentSource
    fun changeSource(change: InteractionSourceChange) {
        currentSource = when (change) {
            InteractionSourceChange.ROUTE -> currentSource.copy(route = currentSource.route + 1)
            InteractionSourceChange.REINSTALL -> currentSource.copy(install = currentSource.install + 1)
            InteractionSourceChange.PROGRESS -> currentSource.copy(progress = currentSource.progress + 1)
            InteractionSourceChange.EPOCH -> currentSource.copy(epoch = currentSource.epoch + 1)
        }
    }

    fun dispatch(home: Boolean, current: () -> Boolean = ::sourceIsCurrent): NavigationSpeechDispatchResult {
        val name = if (home) "speakGuardedHomeCommandInteraction" else "speakGuardedInteraction"
        val guarded = actor.javaClass.methods.singleOrNull { it.name == name && it.parameterCount == 5 }
        val completed: () -> Unit = events::completed
        val failed: () -> Unit = events::failed
        val cancelled: () -> Unit = events::cancelled
        // Version compatibility only: baseline reaches the real public path Main previously used.
        // There is no fake guarded implementation; absence itself is never the test assertion.
        return if (guarded != null) {
            val args = if (home) arrayOf("현재 상태 응답", current, failed, completed, cancelled)
            else arrayOf("현재 상태 응답", current, completed, failed, cancelled)
            guarded.invoke(actor, *args) as NavigationSpeechDispatchResult
        } else if (home) {
            actor.speakHomeCommandInteraction("현재 상태 응답", failed, completed, cancelled)
        } else {
            actor.speakInteraction("현재 상태 응답", completed, failed, cancelled)
        }
    }

    fun actorLock(name: String): Any = actor.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(actor)
    }
    override fun close() = actor.close()
}

/** Deterministic platform effects only; admission, cancellation, priorities and fences are real Actor code. */
private class InteractionStartRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
    data class Spoken(val message: String, val id: String, val queueMode: Int)
    private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
    var now = 1_000L
    val spoken = mutableListOf<Spoken>()
    var activeSpeechId: String? = null
    var stops = 0
    var focusRequests = 0
    var onFocusRequest: (() -> Unit)? = null
    val grantedFocus = mutableSetOf<AudioManager.OnAudioFocusChangeListener>()
    private val tasks = mutableListOf<Task>()
    private var sequence = 0L
    private var initialized: ((Int) -> Unit)? = null
    private lateinit var listener: FeedbackSpeechRuntime.Listener
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
        if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
        return TextToSpeech.SUCCESS
    }
    override fun stop() {
        stops += 1
        val old = activeSpeechId
        activeSpeechId = null
        if (old != null) listener.onStop(old, true)
    }
    override fun shutdown() = Unit
    override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
        focusRequests += 1
        val effect = onFocusRequest
        onFocusRequest = null
        effect?.invoke()
        grantedFocus += listener
        return true
    }
    override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { grantedFocus.remove(listener) }
    override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("No progress tone in this fixture")
    override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
    override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
        tasks += Task(now + delayMs, sequence++, task)
        return true
    }
    override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
    override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
    fun ready() { checkNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
    fun start(id: String) { listener.onStart(id); advanceBy(0L) }
    fun done(id: String) { if (activeSpeechId == id) activeSpeechId = null; listener.onDone(id); advanceBy(0L) }
    fun error(id: String) { listener.onError(id, TextToSpeech.ERROR); advanceBy(0L) }
    fun advanceBy(deltaMs: Long) {
        val target = now + deltaMs
        while (true) {
            val next = tasks.filter { it.dueMs <= target }
                .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order }) ?: break
            tasks.remove(next)
            now = next.dueMs
            next.runnable.run()
        }
        now = target
    }
}
