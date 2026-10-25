package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-38: executes the real actuator, without adding text or id to the diagnostic event. */
class SpeechOutputRequestedTimingRegressionTest {
    @Test
    fun registeredNavigationRequestHasItsOwnTimeBeforeDelayedStartAndDone() {
        Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("비공개 목적지 길안내"))
            assertEquals("a registered speech request must be observable before native start",
                listOf("REQUESTED"), f.events.map { it.outcome.name })
            assertEquals(listOf(1_000L), f.events.map { it.atElapsedRealtimeMs })
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(300L)
            f.runtime.start(id)
            f.runtime.advanceBy(200L)
            f.runtime.done(id)
            assertEquals(listOf("REQUESTED", "STARTED", "DONE"), f.events.map { it.outcome.name })
            assertEquals(listOf(1_000L, 1_300L, 1_500L), f.events.map { it.atElapsedRealtimeMs })
            assertTrue(f.events.all { it.kind == SpeechOutputKind.NAVIGATION && it.errorCode == null })
            for (event in f.events) {
                assertFalse(event.toString().contains("목적지"))
                assertFalse(event.toString().contains(id))
            }
        }
    }

    @Test
    fun focusDenialFollowsExactlyOneRegisteredRequestWithoutNativeSpeech() {
        Fixture().use { f ->
            f.runtime.grantFocus = false
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.actuator.speakNavigation("turn left"))
            assertEquals(listOf("REQUESTED", "FOCUS_DENIED"), f.events.map { it.outcome.name })
            assertEquals(listOf(1_000L, 1_000L), f.events.map { it.atElapsedRealtimeMs })
            assertTrue(f.events.all { it.kind == SpeechOutputKind.NAVIGATION })
            assertTrue(f.runtime.spoken.isEmpty())
        }
    }

    @Test
    fun duplicateAndSuppressedAttemptsDoNotInventAdditionalRegisteredRequests() {
        Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left"))
            val id = f.runtime.spoken.single().id
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.actuator.speakNavigation("turn left"))
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.actuator.speakNavigation("turn left"))
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                f.actuator.speakNavigation("revoked turn", isStillValidAtStart = { false }))
            assertEquals(1, f.events.count { it.outcome.name == "REQUESTED" })
            assertEquals(listOf("REQUESTED", "STARTED", "DONE"), f.events.map { it.outcome.name })
        }
    }

    @Test
    fun initializingUnavailableAttemptHasNoOwnedQueueAndOnlyAnExplicitReadyRetryIsRequested() {
        Fixture(ready = false).use { f ->
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.actuator.speakNavigation("turn left"))
            assertTrue(f.events.isEmpty())
            f.runtime.ready()
            assertTrue(f.runtime.spoken.isEmpty())
            assertTrue(f.events.isEmpty())
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left"))
            assertEquals(listOf("REQUESTED"), f.events.map { it.outcome.name })
        }
    }

    @Test
    fun missingOrThrowingRequestObserverCannotBreakCompletionOrCreateAReplay() {
        Fixture().use { f ->
            var requested = 0
            var completed = 0
            f.actuator.speechOutputListener = { event ->
                if (event.outcome.name == "REQUESTED") { requested += 1; error("diagnostic sink failure") }
            }
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actuator.speakNavigation("first", onCompleted = { completed += 1 }))
            val first = f.runtime.spoken.last().id
            f.runtime.start(first)
            f.runtime.done(first)
            assertEquals(1, requested)
            assertEquals(1, completed)
            f.actuator.speechOutputListener = null
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actuator.speakNavigation("second", onCompleted = { completed += 1 }))
            val second = f.runtime.spoken.last().id
            f.runtime.start(second)
            f.runtime.done(second)
            assertEquals(2, completed)
            assertEquals(2, f.runtime.spoken.size)
            assertEquals("tts=ready", f.actuator.statusText())
        }
    }

    @Test
    fun absentObserverDoesNotAddADiagnosticClockReadToTheExistingAdmissionBudget() {
        Fixture().use { f ->
            f.actuator.speechOutputListener = null
            val before = f.runtime.clockCalls
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left"))
            val admissionClockCalls = f.runtime.clockCalls - before
            println("absentObserverAdmissionClockCalls=$admissionClockCalls")
            // Measured against accepted 0db actor with this same READY navigation/no-listener input.
            assertEquals("diagnostics must not add a clock read without an observer", 17, admissionClockCalls)
            assertEquals(1, f.runtime.spoken.size)
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun requestObserverCanWaitForAnotherThreadsRiskAndTheRetiredNavigationCannotSpeak() {
        Fixture().use { f ->
            val riskDispatch = AtomicReference<NavigationSpeechDispatchResult>()
            var observerJoined = false
            var navigationCancelled = 0
            f.onEvent = { event ->
                if (event.outcome.name == "REQUESTED" && event.kind == SpeechOutputKind.NAVIGATION) {
                    val request = thread {
                        riskDispatch.set(f.actuator.emit(
                            FeedbackAction("risk", "stop", MessageLevel.STOP, null,
                                "request-observer-reentry", 2_000L), null, null, { true },
                        ).speech)
                    }
                    request.join(2_000L)
                    observerJoined = !request.isAlive
                }
            }
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.actuator.speakNavigation("retired turn",
                requiresExplicitTerminalCallback = true,
                onCancelled = { navigationCancelled += 1 }))
            assertTrue("request observer held the speech admission or pending registry lock", observerJoined)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, riskDispatch.get())
            assertEquals(1, navigationCancelled)
            assertEquals(listOf("stop"), f.runtime.spoken.map { it.message })
            assertEquals(1, f.events.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome.name == "REQUESTED" })
            assertEquals(1, f.events.count { it.kind == SpeechOutputKind.RISK && it.outcome.name == "REQUESTED" })
            f.runtime.start(f.runtime.spoken.single().id)
            f.runtime.done(f.runtime.spoken.single().id)
            assertEquals("tts=ready", f.actuator.statusText())
        }
    }

    @Test
    fun requestObserverClosingTheEngineCannotPublishNativeSpeechFromTheRetiredGeneration() {
        Fixture().use { f ->
            f.onEvent = { event -> if (event.outcome.name == "REQUESTED") f.actuator.close() }
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.actuator.speakNavigation("retired turn"))
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(listOf("REQUESTED"), f.events.map { it.outcome.name })
            assertEquals("tts=closed", f.actuator.statusText())
        }
    }

    @Test
    fun requestObserverCanRevokeAndExactlyCancelTheIssuedRiskBeforeNativeSubmission() {
        Fixture().use { f ->
            val policy = WalkSafeFeedbackPolicy()
            val action = requireNotNull(policy.evaluate(FeedbackCandidate(
                "risk", "stop", MessageLevel.STOP, DepthSource.ARCORE_RAW_DEPTH, .9f,
                51_000L, 20, 4_000L, false, validUntilMs = 1_800L,
                timeToCollisionMs = 2_000L, riskDistanceM = 1.1f,
                observedAtElapsedRealtimeMs = 1_000L, observationMaxAgeMs = 800L,
            ), true, f.runtime.now))
            assertTrue(policy.claimFeedbackDelivery(action, f.runtime.now, f.runtime.now))
            var cancellationAccepted: Boolean? = null
            var cancelled = 0
            f.onEvent = { event ->
                if (event.outcome.name == "REQUESTED") {
                    policy.rejectUndeliveredFeedback(action, 1_000L)
                    cancellationAccepted = f.actuator.cancelRiskSpeech(action)
                }
            }
            assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, f.actuator.emit(action,
                onSpeechCompleted = { error("cancelled request completed") },
                onSpeechFailed = { error("cancelled request failed") },
                onSpeechCancelled = { cancelled += 1 },
                isStillValidAtStart = { policy.hasClaimedFeedbackDelivery(action) },
            ).speech)
            assertEquals(true, cancellationAccepted)
            assertEquals(1, cancelled)
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(listOf("REQUESTED", "CANCELLED_RISK"), f.events.map { it.outcome.name })
            assertFalse(f.actuator.isAppSpeechActive())
            assertEquals("tts=ready", f.actuator.statusText())
        }
    }

    @Test
    fun existingCountCollectorDrainsRequestStartAndDoneWithoutPretendingToStoreATimeline() {
        Fixture().use { f ->
            val diagnostics = SpeechOutputDiagnostics()
            f.onEvent = diagnostics::record
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakNavigation("turn left"))
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(300L)
            f.runtime.start(id)
            f.runtime.advanceBy(200L)
            f.runtime.done(id)
            assertEquals(1_500L, diagnostics.lastEventAtElapsedRealtimeMs())
            assertEquals(mapOf("navigation.requested" to 1, "navigation.started" to 1, "navigation.done" to 1),
                diagnostics.drain())
            assertTrue(diagnostics.snapshot().isEmpty())
            assertNull(diagnostics.lastEventAtElapsedRealtimeMs())
        }
    }

    private class Fixture(ready: Boolean = true) : AutoCloseable {
        val runtime = Runtime()
        val events = mutableListOf<SpeechOutputEvent>()
        var onEvent: ((SpeechOutputEvent) -> Unit)? = null
        val actuator = AndroidFeedbackActuator(runtime = runtime).also { actor ->
            actor.speechOutputListener = { event ->
                synchronized(events) { events += event }
                onEvent?.invoke(event)
            }
            if (ready) runtime.ready()
        }
        override fun close() { actuator.close() }
    }

    private class Runtime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
            private set
        var grantFocus = true
        var clockCalls = 0
            private set
        val spoken = mutableListOf<Spoken>()
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        private var onInitialized: ((Int) -> Unit)? = null
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = onInitialized != null
        override fun elapsedRealtime(): Long { clockCalls += 1; return now }
        override fun initialize(onInitialized: (Int) -> Unit) { this.onInitialized = onInitialized }
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
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = grantFocus
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            synchronized(tasks) { tasks += Task(now + delayMs, sequence++, task) }
            return true
        }
        override fun removeCallbacks(task: Runnable) { synchronized(tasks) { tasks.removeAll { it.runnable === task } } }
        override fun removeCallbacksAndMessages(token: Any?) { synchronized(tasks) { tasks.clear() } }
        fun ready() { checkNotNull(onInitialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun done(id: String) { listener.onDone(id); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = synchronized(tasks) {
                    tasks.filter { it.dueMs <= target }.minWithOrNull(compareBy<Task> { it.dueMs }.thenBy { it.order })
                        ?.also(tasks::remove)
                } ?: break
                now = next.dueMs
                next.runnable.run()
            }
            now = target
        }
    }
}
