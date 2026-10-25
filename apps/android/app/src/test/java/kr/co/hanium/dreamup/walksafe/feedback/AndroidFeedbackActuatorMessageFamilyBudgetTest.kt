package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPlayback
import kr.co.hanium.dreamup.walksafe.session.PriorityUserEducationPresentation
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-14: all timing assertions execute the real admission, native start and watchdog scheduler. */
class AndroidFeedbackActuatorMessageFamilyBudgetTest {
    @Test
    fun originalUnsegmentedUsageEducationHitsTheObservedTwoMinuteWatchdog() {
        val message = PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE)
        assertTrue(message.length * 180L > 120_000L)
        Fixture().use { f ->
            val events = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.speakPriorityUserTraining(message, events::completed, events::failed, events::cancelled))
            f.runtime.start(f.runtime.spoken.single().id)
            f.runtime.advanceBy(120_000L)
            assertEquals(1, events.failed)
            assertEquals(0, events.completed)
        }
    }

    @Test
    fun segmentedUsageEducationCanFinishAfterTwoMinutesWithoutWeakeningAnyWatchdog() {
        val message = PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE)
        val segments = PriorityUserEducationPresentation.speechSegments(message)
        Fixture().use { f ->
            val events = Events()
            val startedAt = f.runtime.nowMs
            segments.forEachIndexed { index, segment ->
                assertEquals(index, events.completed)
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                    f.actor.speakPriorityUserTraining(segment, events::completed, events::failed, events::cancelled))
                val id = f.runtime.spoken.last().id
                f.runtime.start(id)
                f.runtime.advanceBy(segment.length * 180L)
                assertEquals(index, events.completed)
                assertEquals(0, events.failed + events.cancelled)
                f.runtime.done(id)
            }
            assertTrue(f.runtime.nowMs - startedAt > 120_000L)
            assertEquals(segments.size, events.completed)
            assertEquals(message, f.runtime.spoken.joinToString("") { it.message })
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals(0, f.observations.count { it.outcome == SpeechOutputOutcome.TERMINAL_TIMEOUT })
        }
    }

    @Test
    fun shortRiskEngineHangDoesNotRemainRegisteredPastTheApprovedEightSecondUpperBound() {
        Fixture().use { f ->
            val events = Events()
            val action = risk("전방 장애물. 즉시 멈추세요.")
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(action, events))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id)
            f.runtime.advanceBy(7_999L)
            assertEquals(0, events.failed)
            assertTrue(f.actor.isAppSpeechActive())
            f.runtime.advanceBy(1L)
            println("riskAt8sState=${f.actor.statusText()} failed=${events.failed} active=${f.actor.isAppSpeechActive()} outcomes=${f.observations.map { it.outcome }}")
            assertEquals("started short risk must leave pending at the approved upper bound", 1, events.failed)
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals("tts=init_failed", f.actor.statusText())
            assertEquals(1, f.observations.count { it.outcome == SpeechOutputOutcome.TERMINAL_TIMEOUT })
        }
    }

    @Test
    fun normalRiskDoneRetiresItsBudgetAndTheFollowingNavigationGetsItsOwnThirtySecondBudget() {
        Fixture().use { f ->
            val riskEvents = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("멈추세요"), riskEvents))
            val old = f.runtime.spoken.last().id
            f.runtime.start(old)
            f.runtime.advanceBy(1_000L)
            f.runtime.done(old)
            assertEquals(1, riskEvents.completed)
            assertEquals(0, f.budgets().size)
            val navEvents = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("길안내".repeat(200),
                navEvents::completed, navEvents::failed, onCancelled = navEvents::cancelled))
            val nav = f.runtime.spoken.last().id
            assertEquals(listOf(30_000L), f.budgets().values.toList())
            f.runtime.start(nav)
            f.runtime.callbacksFor(old)
            f.runtime.advanceBy(29_999L)
            assertEquals(0, navEvents.failed)
            assertEquals(nav, f.runtime.activeSpeechId)
            f.runtime.advanceBy(1L)
            assertEquals(1, navEvents.failed)
            assertEquals(0, f.budgets().size)
        }
    }

    @Test
    fun allSixActualPublicKindsUseTheirOwnUpperBoundsEvenWhenEducationSharesInteractionPrefix() {
        data class Case(val name: String, val bound: Long, val dispatch: (Fixture, String, Events) -> NavigationSpeechDispatchResult)
        val cases = listOf(
            Case("risk", 8_000L) { f, text, e -> f.emitRisk(risk(text), e) },
            Case("navigation", 30_000L) { f, text, e -> f.actor.speakNavigation(text, e::completed, e::failed, onCancelled = e::cancelled) },
            Case("interaction", 60_000L) { f, text, e -> f.actor.speakInteraction(text, e::completed, e::failed, e::cancelled) },
            Case("training", 120_000L) { f, text, e -> f.actor.speakPriorityUserTraining(text, e::completed, e::failed, e::cancelled) },
            Case("consent", 120_000L) { f, text, e -> f.actor.speakConsentClause(text, e::completed, e::failed, e::cancelled) },
            Case("confirmation", 120_000L) { f, text, e -> f.actor.speakExplicitConfirmation(text, e::completed, e::failed, e::cancelled) },
        )
        cases.forEach { case -> Fixture().use { f ->
            val events = Events()
            assertEquals(case.name, NavigationSpeechDispatchResult.ACCEPTED, case.dispatch(f, "안내".repeat(400), events))
            val id = f.runtime.spoken.last().id
            if (case.name in listOf("training", "consent", "confirmation")) assertTrue(id.startsWith("interaction-"))
            assertEquals(case.name, listOf(case.bound), f.budgets().values.toList())
            f.runtime.start(id)
            f.runtime.advanceBy(case.bound - 1L)
            assertEquals(case.name, 0, events.failed)
            assertTrue(case.name, f.actor.isAppSpeechActive())
            f.runtime.advanceBy(1L)
            assertEquals(case.name, 1, events.failed)
            assertEquals(case.name, 0, events.completed + events.cancelled)
            assertFalse(case.name, f.actor.isAppSpeechActive())
            assertEquals(case.name, 0, f.budgets().size)
        } }
    }

    @Test
    fun shortGeneralInteractionBooleanCallbackAndHomeEntriesUseTheCharacterCorrection() {
        val dispatches: List<(Fixture, Events) -> Boolean> = listOf(
            { f, _ -> f.actor.speakInteraction("네") },
            { f, e -> f.actor.speakInteraction("네", e::completed, e::failed, e::cancelled) == NavigationSpeechDispatchResult.ACCEPTED },
            { f, e -> f.actor.speakHomeCommandInteraction("네", e::failed, e::completed, e::cancelled) == NavigationSpeechDispatchResult.ACCEPTED },
        )
        dispatches.forEach { dispatch -> Fixture().use { f ->
            val events = Events()
            assertTrue(dispatch(f, events))
            assertEquals(listOf(5_300L), f.budgets().values.toList())
            f.runtime.start(f.runtime.spoken.last().id)
            f.runtime.advanceBy(5_299L)
            assertEquals("tts=ready", f.actor.statusText())
            f.runtime.advanceBy(1L)
            assertEquals("tts=init_failed", f.actor.statusText())
            assertEquals(1, f.observations.count { it.outcome == SpeechOutputOutcome.TERMINAL_TIMEOUT })
        } }
    }

    @Test
    fun shortTrainingConsentAndExplicitConfirmationKeepTheTenSecondMinimumAndNormalDone() {
        val dispatches: List<(Fixture, Events) -> NavigationSpeechDispatchResult> = listOf(
            { f, e -> f.actor.speakPriorityUserTraining("교육", e::completed, e::failed, e::cancelled) },
            { f, e -> f.actor.speakConsentClause("동의", e::completed, e::failed, e::cancelled) },
            { f, e -> f.actor.speakExplicitConfirmation("확인", e::completed, e::failed, e::cancelled) },
        )
        dispatches.forEach { dispatch -> Fixture().use { f ->
            val e = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch(f, e))
            assertEquals(listOf(10_000L), f.budgets().values.toList())
            val id = f.runtime.spoken.last().id
            f.runtime.start(id)
            f.runtime.advanceBy(9_999L)
            assertEquals(0, e.failed)
            f.runtime.done(id)
            f.runtime.advanceBy(1L)
            assertEquals(1, e.completed)
            assertEquals(0, e.failed + e.cancelled)
            assertEquals(0, f.budgets().size)
        } }
    }

    @Test
    fun delayedNativeStartUsesTheAdmissionSelectedBudgetWithoutRetaggingTheRequestOrSourceClock() {
        Fixture().use { f ->
            val e = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("전방 위험입니다. 지금 멈추세요."), e))
            val id = f.runtime.spoken.last().id
            val admitted = f.budgets().toMap()
            assertEquals(8_000L, admitted[id])
            assertEquals(1_000L, f.observations.single().atElapsedRealtimeMs)
            f.runtime.advanceBy(7_000L)
            assertEquals(0, e.failed)
            f.runtime.start(id)
            assertEquals(admitted, f.budgets())
            assertEquals(8_000L, f.observations.last().atElapsedRealtimeMs)
            f.runtime.advanceBy(7_999L)
            assertEquals(0, e.failed)
            f.runtime.advanceBy(1L)
            assertEquals(1, e.failed)
            assertEquals(16_000L, f.observations.last { it.outcome == SpeechOutputOutcome.TERMINAL_TIMEOUT }.atElapsedRealtimeMs)
        }
    }

    @Test
    fun informationExpiryBeforeStartIsCancellationAndKeepsEngineReady() {
        Fixture().use { f ->
            val e = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("전방 위험", 1_050L), e))
            val old = f.runtime.spoken.last().id
            f.runtime.advanceBy(51L)
            assertEquals(1, e.cancelled)
            assertEquals(0, e.failed)
            assertEquals("tts=ready", f.actor.statusText())
            assertEquals(0, f.budgets().size)
            assertEquals(1, f.observations.count { it.outcome == SpeechOutputOutcome.EXPIRED_BEFORE_START })
            assertEquals(0, f.observations.count { it.outcome == SpeechOutputOutcome.TERMINAL_TIMEOUT })
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("현재 안내"))
            val nav = f.runtime.spoken.last().id
            f.runtime.callbacksFor(old)
            assertEquals(nav, f.runtime.activeSpeechId)
        }
    }

    @Test
    fun sourceEpochRevocationBeforeStartCancelsButAfterStartDoesNotBecomeATerminalTrigger() {
        Fixture().use { f ->
            var epochCurrent = true
            val e = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("전방 위험"), e, { epochCurrent }))
            val old = f.runtime.spoken.last().id
            epochCurrent = false
            f.runtime.start(old)
            assertEquals(1, e.cancelled)
            assertEquals(0, e.failed)
            assertEquals(0, f.budgets().size)
            assertEquals("tts=ready", f.actor.statusText())
            epochCurrent = true
            val current = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("다른 전방 위험", 1_100L), current, { epochCurrent }))
            val id = f.runtime.spoken.last().id
            f.runtime.start(id)
            epochCurrent = false
            f.runtime.advanceBy(500L)
            assertEquals(0, current.cancelled + current.failed)
            f.runtime.done(id)
            assertEquals(1, current.completed)
        }
    }

    @Test
    fun advisoryFallbackIsPreservedAndExactCancellationCannotRemoveTheFollowingNavigationBudget() {
        Fixture().use { f ->
            var receipt: AdvisorySpeechReceipt? = null
            assertTrue(f.actor.speakAdvisoryWithReceipt("안내", 5_000L, { true }, { receipt = it }))
            assertEquals(listOf(10_000L), f.budgets().values.toList())
            val old = f.runtime.spoken.last().id
            f.runtime.start(old)
            assertTrue(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertEquals(0, f.budgets().size)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("길안내".repeat(200)))
            val nav = f.runtime.spoken.last().id
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertFalse(f.actor.cancelAdvisorySpeech(AdvisorySpeechReceipt()))
            f.runtime.callbacksFor(old)
            assertEquals(listOf(30_000L), f.budgets().values.toList())
            assertEquals(nav, f.runtime.activeSpeechId)
        }
    }

    @Test
    fun priorityReplacementExternalTakeoverAndCloseReleaseTheBudgetRegistration() {
        Fixture().use { f ->
            assertTrue(f.actor.speakAdvisory("정보", 5_000L, { true }))
            val advisory = f.runtime.spoken.last().id
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("길안내".repeat(200)))
            assertEquals(1, f.budgets().size)
            assertEquals(30_000L, f.budgets().values.single())
            f.runtime.callbacksFor(advisory)
            f.actor.prepareForExternalRiskAnnouncement()
            assertEquals(0, f.budgets().size)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakInteraction("설명", null, null))
            val old = f.runtime.spoken.last().id
            assertEquals(1, f.budgets().size)
            f.actor.close()
            f.runtime.callbacksFor(old)
            assertEquals(0, f.budgets().size)
            assertEquals("tts=closed", f.actor.statusText())
        }
    }

    @Test
    fun oldEngineAndOldWatchdogsCannotApplyTheirBudgetToTheReplacementOwner() {
        Fixture(retries = 1).use { f ->
            val e = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk(risk("전방 위험입니다. 멈추세요."), e))
            val old = f.runtime.spoken.last().id
            f.runtime.start(old)
            f.runtime.error(old, TextToSpeech.ERROR_SERVICE)
            assertEquals(0, f.budgets().size)
            f.runtime.advanceBy(250L)
            f.runtime.ready()
            val nextEvents = Events()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("현재 안내".repeat(100),
                nextEvents::completed, nextEvents::failed, onCancelled = nextEvents::cancelled))
            val next = f.runtime.spoken.last().id
            f.runtime.start(next)
            f.runtime.callbacksFor(old, fromEngine = 0)
            f.runtime.advanceBy(8_000L)
            assertEquals(0, nextEvents.failed + nextEvents.cancelled)
            assertEquals(listOf(30_000L), f.budgets().values.toList())
            f.runtime.done(next)
            assertEquals(1, nextEvents.completed)
            assertEquals(0, f.budgets().size)
        }
    }

    @Test
    fun negativeAndHugeLengthsAreBoundedWithoutOverflowAndTheLegacyHelperIsUnchanged() {
        val bounds = mapOf(SpeechMessageFamily.RISK to (5_000L to 8_000L),
            SpeechMessageFamily.NAVIGATION to (5_000L to 30_000L),
            SpeechMessageFamily.INTERACTION to (5_000L to 60_000L),
            SpeechMessageFamily.EDUCATION_OR_CONSENT to (10_000L to 120_000L))
        bounds.forEach { (family, expected) ->
            assertEquals(expected.first, utteranceTerminalTimeoutMs(Int.MIN_VALUE, family))
            assertEquals(expected.second, utteranceTerminalTimeoutMs(Int.MAX_VALUE, family))
        }
        assertEquals(10_000L, utteranceTerminalTimeoutMs(Int.MIN_VALUE))
        assertEquals(120_000L, utteranceTerminalTimeoutMs(Int.MAX_VALUE))
    }

    private fun risk(message: String, validUntilMs: Long = 200_000L) =
        FeedbackAction("test-risk", message, MessageLevel.STOP, null, "family-budget", validUntilMs)
    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
        fun completed() { completed++ }
        fun failed() { failed++ }
        fun cancelled() { cancelled++ }
    }
    private class Fixture(retries: Int = 0) : AutoCloseable {
        val runtime = FakeRuntime()
        val observations = mutableListOf<SpeechOutputEvent>()
        val actor = AndroidFeedbackActuator(runtime = runtime,
            recoveryBudget = TtsEngineRecoveryBudget(retries)).also { actor ->
            actor.speechOutputListener = { observations += it }
            runtime.ready()
        }
        fun emitRisk(action: FeedbackAction, events: Events, validator: () -> Boolean = { true }) =
            actor.emit(action, events::completed, events::failed, validator, events::cancelled).speech
        fun budgets(): Map<String, Long> {
            val field = AndroidFeedbackActuator::class.java.getDeclaredField("pendingUtterances")
                .apply { isAccessible = true }
            val ledger = field.get(actor) as UtteranceLedger
            return synchronized(ledger) { ledger.associateWith { requireNotNull(ledger[it]).terminalBudgetMs } }
        }
        override fun close() { actor.close() }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
        private var initialized: ((Int) -> Unit)? = null
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        private val listeners = mutableListOf<FeedbackSpeechRuntime.Listener>()
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var nowMs = 1_000L
            private set
        val spoken = mutableListOf<Spoken>()
        var activeSpeechId: String? = null
            private set
        var engineCount = 0
            private set
        var stops = 0
            private set
        val stoppedSpeechIds = mutableListOf<String?>()
        var vibrations = 0
            private set
        var vibrationCancellations = 0
            private set
        var stopping = false
            private set
        var inlinePosts = false
        var clockCalls = 0
        var throwOnStop = false
        var grantFocus = true
        var onStop: ((String?) -> Unit)? = null
        var onSpeak: (() -> Unit)? = null
        var onFocus: ((Boolean) -> Unit)? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long { clockCalls += 1; return nowMs }
        override fun initialize(onInitialized: (Int) -> Unit) {
            engineCount += 1
            initialized = onInitialized
        }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            listeners += listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
            onSpeak?.invoke()
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            stops += 1
            stoppedSpeechIds += activeSpeechId
            if (throwOnStop) throw IllegalStateException("simulated native stop error")
            stopping = true
            try {
                onStop?.invoke(activeSpeechId)
                activeSpeechId = null
            } finally {
                stopping = false
            }
        }
        override fun shutdown() { activeSpeechId = null }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            onFocus?.invoke(isRisk)
            return grantFocus
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun vibrate(patternMs: LongArray): Boolean { vibrations += 1; return true }
        override fun cancelVibration() { vibrationCancellations += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean {
            if (inlinePosts) task.run() else postDelayed(task, 0L)
            return true
        }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            synchronized(tasks) { tasks += Task(nowMs + delayMs.coerceAtLeast(0L), sequence++, task) }
            return true
        }
        override fun removeCallbacks(task: Runnable) { synchronized(tasks) { tasks.removeAll { it.runnable === task } } }
        override fun removeCallbacksAndMessages(token: Any?) { synchronized(tasks) { tasks.clear() } }
        fun ready() { checkNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun done(id: String) {
            if (activeSpeechId == id) activeSpeechId = null
            listener.onDone(id)
            advanceBy(0L)
        }
        fun error(id: String, code: Int) { listener.onError(id, code); advanceBy(0L) }
        fun callbacksFor(id: String, fromEngine: Int = listeners.lastIndex) {
            val callback = listeners[fromEngine]
            callback.onStart(id)
            callback.onDone(id)
            callback.onError(id, TextToSpeech.ERROR_SERVICE)
            callback.onStop(id, interrupted = true)
            advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = synchronized(tasks) {
                    tasks.filter { it.dueMs <= targetMs }
                        .minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order })
                        ?.also(tasks::remove)
                } ?: break
                nowMs = next.dueMs
                next.runnable.run()
            }
            nowMs = targetMs
        }
    }
}
