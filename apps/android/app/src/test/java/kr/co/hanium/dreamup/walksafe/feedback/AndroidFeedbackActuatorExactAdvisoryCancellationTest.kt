package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-24/25 actuator API coverage; source-loss production and Main binding are separate. */
class AndroidFeedbackActuatorExactAdvisoryCancellationTest {
    @Test
    fun startedOwnerCanBeCancelledAfterItsSourceIsInvalidWithoutStoppingHaptics() {
        Fixture().use { f ->
            var sourceCurrent = true
            val issued = f.issue(validator = { sourceCurrent })
            assertTrue(f.actor.playPhoneMountingSafetyStopVibration())
            sourceCurrent = false
            f.runtime.advanceBy(20L)
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(1, f.runtime.stops)
            assertEquals(1, issued.events.cancelled)
            assertEquals(0, issued.events.completed + issued.events.failed)
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED,
                SpeechOutputOutcome.CANCELLED_ADVISORY), f.observations.map { it.outcome })
            val cancelled = f.observations.last()
            assertEquals(SpeechOutputKind.ADVISORY, cancelled.kind)
            assertEquals(1_020L, cancelled.atElapsedRealtimeMs)
            assertNull(cancelled.errorCode)
            assertFalse(cancelled.toString().contains(issued.id))
            assertFalse(cancelled.toString().contains("카메라 안내"))
            assertEquals(0, f.runtime.vibrationCancellations)
            assertEquals(1, f.runtime.vibrations)
            assertEquals("tts=ready", f.actor.statusText())
            assertFalse(f.actor.isAppSpeechActive())
        }
    }

    @Test
    fun foreignAndFabricatedReceiptsCannotCancelTheCurrentAdvisory() {
        Fixture().use { f -> Fixture().use { foreign ->
            val current = f.issue()
            val other = foreign.issue()
            assertFalse(f.actor.cancelAdvisorySpeech(other.receipt))
            assertFalse(f.actor.cancelAdvisorySpeech(AdvisorySpeechReceipt()))
            assertEquals(current.id, f.runtime.activeSpeechId)
            assertEquals(0, f.runtime.stops)
            assertEquals(0, current.events.cancelled)
            assertEquals(0, f.cancelObservations())
        } }
    }

    @Test
    fun navigationReplacementMakesTheOldReceiptHarmless() {
        Fixture().use { f ->
            val old = f.issue()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("현재 길안내"))
            val nav = f.runtime.spoken.last().id
            val stops = f.runtime.stops
            assertEquals(1, old.events.cancelled)
            assertFalse(f.actor.cancelAdvisorySpeech(old.receipt))
            assertFalse(f.actor.cancelAdvisorySpeech(AdvisorySpeechReceipt()))
            assertEquals(stops, f.runtime.stops)
            assertEquals(nav, f.runtime.activeSpeechId)
            assertEquals(0, f.cancelObservations())
        }
    }

    @Test
    fun completedAndCancelledReceiptsCannotStopTheNextOwnerEvenForTheSameText() {
        Fixture().use { f ->
            val completed = f.issue()
            f.runtime.done(completed.id)
            val cancelled = f.issue()
            assertTrue(f.actor.cancelAdvisorySpeech(cancelled.receipt))
            val next = f.issue()
            val stops = f.runtime.stops
            assertFalse(f.actor.cancelAdvisorySpeech(completed.receipt))
            assertFalse(f.actor.cancelAdvisorySpeech(cancelled.receipt))
            assertEquals(1, completed.events.completed)
            assertEquals(1, cancelled.events.cancelled)
            assertEquals(next.id, f.runtime.activeSpeechId)
            assertEquals(stops, f.runtime.stops)
            assertEquals(1, f.cancelObservations())
        }
    }

    @Test
    fun sourceExpiryAloneDoesNotCancelAnAlreadyStartedAdvisory() {
        Fixture().use { f ->
            var sourceCurrent = true
            val issued = f.issue(validUntilMs = 1_050L, validator = { sourceCurrent })
            sourceCurrent = false
            f.runtime.advanceBy(300L)
            assertEquals(issued.id, f.runtime.activeSpeechId)
            assertEquals(0, f.runtime.stops)
            assertEquals(0, issued.events.cancelled + issued.events.failed)
            assertEquals(0, f.cancelObservations())
            f.runtime.done(issued.id)
            assertEquals(1, issued.events.completed)
        }
    }

    @Test
    fun exactUnstartedOwnerCanBeDiscardedBeforeNativeStart() {
        Fixture().use { f ->
            val issued = f.issue(started = false)
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            f.runtime.callbacksFor(issued.id)
            assertEquals(1, f.runtime.stops)
            assertEquals(1, issued.events.cancelled)
            assertEquals(0, issued.events.completed + issued.events.failed)
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.CANCELLED_ADVISORY),
                f.observations.map { it.outcome })
        }
    }

    @Test
    fun stopFailureStillReportsLogicalCancellationOnceWithoutPlaybackAcknowledgement() {
        Fixture().use { f ->
            val issued = f.issue()
            f.runtime.throwOnStop = true
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(issued.id, f.runtime.activeSpeechId)
            assertEquals(1, f.runtime.stops)
            assertEquals(1, issued.events.cancelled)
            f.runtime.callbacksFor(issued.id)
            assertFalse(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(1, f.cancelObservations())
            assertEquals(0, issued.events.completed + issued.events.failed)
            assertEquals("tts=ready", f.actor.statusText())
            f.runtime.throwOnStop = false
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("새 길안내"))
            // QUEUE_ADD does not acknowledge completion of a failed native stop.
            assertEquals(issued.id, f.runtime.activeSpeechId)
            assertEquals("새 길안내", f.runtime.spoken.last().message)
        }
    }

    @Test
    fun synchronousStopCallbacksAndUpperReentryCannotStopTheNewOwner() {
        Fixture().use { f ->
            val issued = f.issue()
            val order = mutableListOf<String>()
            var duplicate: Boolean? = null
            var joined = false
            f.runtime.inlinePosts = true
            f.runtime.onStop = { id ->
                order += "native"
                f.runtime.callbacksFor(requireNotNull(id))
                duplicate = f.actor.cancelAdvisorySpeech(issued.receipt)
            }
            issued.events.onCancelled = {
                order += "upper"
                assertFalse(f.runtime.stopping)
                val result = AtomicReference<NavigationSpeechDispatchResult>()
                val request = thread { result.set(f.actor.speakNavigation("새 길안내")) }
                request.join(2_000L)
                joined = !request.isAlive
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, result.get())
            }
            f.onEvent = { if (it.outcome == SpeechOutputOutcome.CANCELLED_ADVISORY) order += "observer" }
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(false, duplicate)
            assertTrue("upper callback must run outside engine and pending locks", joined)
            assertEquals(listOf("native", "upper", "observer"), order)
            val next = f.runtime.spoken.last().id
            f.runtime.callbacksFor(issued.id)
            assertEquals(next, f.runtime.activeSpeechId)
            assertEquals(1, f.runtime.stops)
            assertEquals(1, issued.events.cancelled)
            assertEquals(0, issued.events.completed + issued.events.failed)
            assertEquals(1, f.cancelObservations())
        }
    }

    @Test
    fun cancellationObserverCanWaitForANewOwnerOutsideLocks() {
        Fixture().use { f ->
            val issued = f.issue()
            var joined = false
            f.onEvent = { event ->
                if (event.outcome == SpeechOutputOutcome.CANCELLED_ADVISORY) {
                    assertFalse(f.runtime.stopping)
                    val request = thread { f.actor.speakNavigation("관측자 길안내") }
                    request.join(2_000L)
                    joined = !request.isAlive
                }
            }
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertTrue(joined)
            val next = f.runtime.spoken.last().id
            f.runtime.callbacksFor(issued.id)
            assertEquals(next, f.runtime.activeSpeechId)
            assertEquals(1, f.cancelObservations())
        }
    }

    @Test
    fun throwingCancellationObserverAndUpperCallbackCannotChangeTheCancellation() {
        Fixture().use { f ->
            val issued = f.issue()
            issued.events.onCancelled = { error("upper consumer failed") }
            f.onEvent = { if (it.outcome == SpeechOutputOutcome.CANCELLED_ADVISORY) error("observer failed") }
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertFalse(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(1, issued.events.cancelled)
            assertEquals(1, f.cancelObservations())
            assertEquals("tts=ready", f.actor.statusText())
            f.onEvent = null
            assertTrue(f.actor.speakAdvisory("카메라 안내", 5_000L, { true }))
        }
    }

    @Test
    fun registrationCallbackCanCancelImmediatelyWithRequestBeforeCancellation() {
        Fixture().use { f ->
            val events = Events()
            var receipt: AdvisorySpeechReceipt? = null
            assertFalse(f.actor.speakAdvisoryWithReceipt("카메라 안내", 5_000L, { true },
                onRegistered = {
                    receipt = it
                    assertTrue(f.actor.cancelAdvisorySpeech(it))
                }, onCompleted = events::completed, onFailed = events::failed,
                onCancelled = events::cancelled))
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.CANCELLED_ADVISORY),
                f.observations.map { it.outcome })
            assertEquals(0, f.runtime.spoken.size)
            assertEquals(1, events.cancelled)
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
        }
    }

    @Test
    fun registrationCallbackCanReplaceWithNavigationBeforeNativeSubmission() {
        Fixture().use { f ->
            val events = Events()
            var receipt: AdvisorySpeechReceipt? = null
            assertFalse(f.actor.speakAdvisoryWithReceipt("카메라 안내", 5_000L, { true },
                onRegistered = {
                    receipt = it
                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("현재 길안내"))
                }, onFailed = events::failed, onCancelled = events::cancelled))
            assertEquals(1, events.cancelled)
            assertEquals(listOf("현재 길안내"), f.runtime.spoken.map { it.message })
            val stops = f.runtime.stops
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertEquals(stops, f.runtime.stops)
            assertEquals(f.runtime.spoken.single().id, f.runtime.activeSpeechId)
        }
    }

    @Test
    fun registrationCallbackCanWaitForAConcurrentReplacementOutsideLocks() {
        Fixture().use { f ->
            var joined = false
            var receipt: AdvisorySpeechReceipt? = null
            val result = AtomicReference<NavigationSpeechDispatchResult>()
            assertFalse(f.actor.speakAdvisoryWithReceipt("카메라 안내", 5_000L, { true }, {
                receipt = it
                val request = thread { result.set(f.actor.speakNavigation("다른 요청의 길안내")) }
                request.join(2_000L)
                joined = !request.isAlive
            }))
            assertTrue("registration callback must not hold engine or pending locks", joined)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, result.get())
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertEquals(listOf("다른 요청의 길안내"), f.runtime.spoken.map { it.message })
        }
    }

    @Test
    fun requestedObserverMayRetireRegistrationBeforeItsReceiptIsDelivered() {
        Fixture().use { f ->
            val order = mutableListOf<String>()
            val events = Events()
            f.onEvent = { event ->
                if (event.kind == SpeechOutputKind.ADVISORY && event.outcome == SpeechOutputOutcome.REQUESTED) {
                    order += "requested"
                    f.actor.speakNavigation("현재 길안내")
                }
            }
            assertFalse(f.actor.speakAdvisoryWithReceipt("카메라 안내", 5_000L, { true },
                onRegistered = {
                    order += "registered"
                    assertFalse(f.actor.cancelAdvisorySpeech(it))
                }, onFailed = events::failed, onCancelled = events::cancelled))
            assertEquals(listOf("requested", "registered"), order)
            assertEquals(1, events.cancelled)
            assertEquals(0, f.cancelObservations())
            assertEquals(listOf("현재 길안내"), f.runtime.spoken.map { it.message })
        }
    }

    @Test
    fun registrationExceptionIsIsolatedAndNativeCompletionMayPrecedeDispatchReturn() {
        Fixture().use { f ->
            val order = mutableListOf<String>()
            var receipt: AdvisorySpeechReceipt? = null
            val events = Events()
            f.runtime.inlinePosts = true
            f.runtime.onSpeak = {
                order += "native"
                val id = f.runtime.spoken.last().id
                f.runtime.start(id)
                f.runtime.done(id)
            }
            val accepted = f.actor.speakAdvisoryWithReceipt("카메라 안내", 5_000L, { true },
                onRegistered = {
                    order += "registered"
                    receipt = it
                    error("registration consumer failed")
                }, onCompleted = { order += "done"; events.completed() }, onFailed = events::failed,
                onCancelled = events::cancelled)
            assertFalse(accepted)
            assertEquals(listOf("registered", "native", "done"), order)
            assertEquals(1, events.completed)
            assertEquals(0, events.failed + events.cancelled)
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertEquals(listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE),
                f.observations.map { it.outcome })
        }
    }

    @Test
    fun rejectedAdmissionsPublishNoReceiptAndFocusFailureRetiresThePublishedOne() {
        Fixture().use { f ->
            var registrations = 0
            val registered: (AdvisorySpeechReceipt) -> Unit = { registrations++ }
            assertFalse(f.actor.speakAdvisoryWithReceipt(" ", 5_000L, { true }, registered))
            assertFalse(f.actor.speakAdvisoryWithReceipt("expired", 999L, { true }, registered))
            assertFalse(f.actor.speakAdvisoryWithReceipt("invalid", 5_000L, { false }, registered))
            assertEquals(0, registrations)
            val owner = f.issue()
            assertFalse(f.actor.speakAdvisoryWithReceipt("busy", 5_000L, { true }, registered))
            assertEquals(0, registrations)
            f.runtime.done(owner.id)
            var receipt: AdvisorySpeechReceipt? = null
            f.runtime.grantFocus = false
            assertFalse(f.actor.speakAdvisoryWithReceipt("focus", 5_000L, { true }, { receipt = it }))
            assertFalse(f.actor.cancelAdvisorySpeech(requireNotNull(receipt)))
            assertEquals(0, f.cancelObservations())
        }
        Fixture(ready = false).use { f ->
            assertFalse(f.actor.speakAdvisoryWithReceipt("initializing", 5_000L, { true },
                { error("initializing has no registered receipt") }))
            assertEquals(0, f.runtime.spoken.size)
        }
    }

    @Test
    fun externalRiskTakeoverRetiresReceiptsWithoutRetainingOrphansOrStoppingLaterSpeech() {
        Fixture().use { f ->
            val old = mutableListOf<Issued>()
            val retained = mutableListOf<Int>()
            repeat(2) {
                val issued = f.issue()
                old += issued
                f.actor.prepareForExternalRiskAnnouncement()
                assertEquals(1, issued.events.cancelled)
                assertFalse(f.actor.isAppSpeechActive())
                val stops = f.runtime.stops
                f.runtime.callbacksFor(issued.id)
                assertFalse(f.actor.cancelAdvisorySpeech(issued.receipt))
                assertEquals(stops, f.runtime.stops)
                // The public takeover must release registration memory even when no callbacks arrive.
                val field = AndroidFeedbackActuator::class.java
                    .getDeclaredField("pendingUtterances").apply { isAccessible = true }
                val ledger = field.get(f.actor) as UtteranceLedger
                retained += synchronized(ledger) { ledger.count { ledger[it]?.advisoryDelivery != null } }
            }
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("현재 길안내"))
            val nav = f.runtime.spoken.last().id
            val stops = f.runtime.stops
            old.forEach { assertFalse(f.actor.cancelAdvisorySpeech(it.receipt)) }
            assertEquals(nav, f.runtime.activeSpeechId)
            assertEquals(stops, f.runtime.stops)
            assertEquals(0, f.cancelObservations())
            println("externalTakeoverRetainedReceiptCounts=$retained")
            assertEquals("retired receipts must not accumulate until actuator close", listOf(0, 0), retained)
        }
    }

    @Test
    fun closeAndRecoveryRetireReceiptsWithoutChangingTheExistingRetryBudget() {
        Fixture().use { f ->
            val issued = f.issue()
            f.actor.close()
            val stops = f.runtime.stops
            assertFalse(f.actor.cancelAdvisorySpeech(issued.receipt))
            assertEquals(stops, f.runtime.stops)
            assertEquals(0, f.cancelObservations())
        }
        Fixture(retries = 1).use { f ->
            val old = f.issue()
            f.runtime.error(old.id, TextToSpeech.ERROR_SERVICE)
            f.runtime.advanceBy(250L)
            f.runtime.ready()
            val next = f.issue()
            val stops = f.runtime.stops
            assertFalse(f.actor.cancelAdvisorySpeech(old.receipt))
            f.runtime.callbacksFor(old.id, fromEngine = 0)
            assertEquals(next.id, f.runtime.activeSpeechId)
            assertEquals(stops, f.runtime.stops)
            assertEquals(2, f.runtime.engineCount)
            assertEquals(0, f.cancelObservations())
            f.runtime.error(next.id, TextToSpeech.ERROR_SERVICE)
            f.runtime.advanceBy(250L)
            assertEquals(2, f.runtime.engineCount)
            assertEquals("tts=init_failed", f.actor.statusText())
        }
    }

    @Test
    fun listenerlessReceiptAdmissionMatchesLegacyClockReadsAndCancelAddsOnlyTheExistingThree() {
        fun measure(receiptApi: Boolean): Int = Fixture(observe = false).use { f ->
            val before = f.runtime.clockCalls
            if (receiptApi) {
                assertTrue(f.actor.speakAdvisoryWithReceipt("カメ라 안내", 5_000L, { true }, {}))
            } else {
                assertTrue(f.actor.speakAdvisory("카메라 안내", 5_000L, { true }))
            }
            f.runtime.clockCalls - before
        }
        val legacy = measure(false)
        val receiptApi = measure(true)
        println("legacyAdvisoryClockCalls=$legacy receiptAdvisoryClockCalls=$receiptApi")
        assertEquals(legacy, receiptApi)
        Fixture(observe = false).use { f ->
            val issued = f.issue()
            val before = f.runtime.clockCalls
            assertTrue(f.actor.cancelAdvisorySpeech(issued.receipt))
            val cancellationReads = f.runtime.clockCalls - before
            println("listenerlessAdvisoryCancellationClockCalls=$cancellationReads")
            assertEquals(3, cancellationReads)
        }
    }

    private class Events {
        var completed = 0
        var failed = 0
        var cancelled = 0
        var onCancelled: (() -> Unit)? = null
        fun completed() { completed++ }
        fun failed() { failed++ }
        fun cancelled() { cancelled++; onCancelled?.invoke() }
    }
    private data class Issued(val receipt: AdvisorySpeechReceipt, val id: String, val events: Events)
    private class Fixture(ready: Boolean = true, observe: Boolean = true, retries: Int = 0) : AutoCloseable {
        val runtime = FakeRuntime()
        val observations = mutableListOf<SpeechOutputEvent>()
        var onEvent: ((SpeechOutputEvent) -> Unit)? = null
        val actor = AndroidFeedbackActuator(runtime = runtime,
            hapticAllowed = { true },
            recoveryBudget = TtsEngineRecoveryBudget(retries)).also { actor ->
            if (observe) actor.speechOutputListener = { event ->
                synchronized(observations) { observations += event }
                onEvent?.invoke(event)
            }
            if (ready) runtime.ready()
        }
        fun issue(started: Boolean = true, validUntilMs: Long = 5_000L,
            validator: () -> Boolean = { true }): Issued {
            val events = Events()
            var receipt: AdvisorySpeechReceipt? = null
            assertTrue(actor.speakAdvisoryWithReceipt("카메라 안내", validUntilMs, validator,
                { receipt = it }, events::completed, events::failed, events::cancelled))
            val id = runtime.spoken.last().id
            if (started) runtime.start(id)
            return Issued(requireNotNull(receipt), id, events)
        }
        fun cancelObservations() = synchronized(observations) {
            observations.count { it.outcome == SpeechOutputOutcome.CANCELLED_ADVISORY }
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
