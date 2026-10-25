package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.navigation.CrosswalkReferenceNotice
import kr.co.hanium.dreamup.walksafe.navigation.TactileActiveRouteProgress
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the Main delivery consumer and production actuator without launching Activity. */
class AndroidFeedbackActuatorCrosswalkReferenceAdvisoryTest {
    @Test
    fun mainReferenceExpiresDuringFocusAcquisitionWithoutNativeSubmission() {
        val fixture = Fixture()
        fixture.runtime.onFocusRequest = { fixture.runtime.now = DEADLINE + 1L }

        assertFalse(fixture.dispatch())

        assertUnsubmittedReferenceFailed(fixture)
        assertEquals(DEADLINE, fixture.transportedNotices.single().validUntilElapsedRealtimeMs)
    }

    @Test
    fun livePermissionAndEachRouteComponentRevokedDuringFocusPreventNativeSubmission() {
        for (change in listOf("permission", "route-id", "route-version", "route-segment")) {
            val fixture = Fixture()
            fixture.runtime.onFocusRequest = { fixture.revoke(change) }

            assertFalse(change, fixture.dispatch())

            assertUnsubmittedReferenceFailed(fixture)
        }
    }

    @Test
    fun heldNativeStartRejectsExpiredRenewedFrameRevokedOutputRouteAndLostSource() {
        for (change in listOf("expiry", "permission", "route-id", "route-version", "route-segment", "source")) {
            val fixture = Fixture()
            assertTrue(fixture.dispatch())
            val issued = fixture.runtime.spoken.single()
            assertEquals(fixture.transportedNotices.single().message, issued.message)
            assertEquals(TextToSpeech.QUEUE_ADD, issued.queueMode)
            assertEquals(0, fixture.events.completed)
            assertEquals(0, fixture.events.failed)
            if (change == "expiry") {
                fixture.runtime.advanceBy(200L)
                fixture.publish(notice(frame = 2L, captured = 1_200L, until = 1_600L))
                fixture.runtime.advanceBy(101L)
            } else {
                fixture.runtime.advanceBy(1L)
                if (change == "source") fixture.publish(null) else fixture.revoke(change)
            }

            // Native speak was accepted before its onStart callback; the held start is now stale.
            fixture.runtime.start(issued.id)
            fixture.runtime.done(issued.id)
            fixture.runtime.done(issued.id)

            assertEquals(change, 1, fixture.runtime.spoken.size)
            assertEquals(change, 1, fixture.runtime.stops)
            assertEquals(change, 0, fixture.events.completed)
            assertEquals(change, 1, fixture.events.failed)
            assertEquals(1, fixture.runtime.focusReleases)
            assertFalse(fixture.actuator.isAppSpeechActive())
            assertEquals("tts=ready", fixture.actuator.statusText())
        }
    }

    @Test
    fun inclusiveNativeStartThenSourceLossDoneKeepsEightSecondsFromActualCompletion() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch())
        val issued = fixture.runtime.spoken.single()
        assertEquals(TextToSpeech.QUEUE_ADD, issued.queueMode)
        fixture.runtime.advanceBy(DEADLINE - fixture.runtime.now)
        fixture.runtime.start(issued.id)
        assertTrue(fixture.actuator.isAppSpeechActive())
        assertEquals(0, fixture.events.failed)
        fixture.runtime.advanceBy(1L)
        fixture.publish(null)
        fixture.runtime.advanceBy(2_000L - fixture.runtime.now)
        fixture.runtime.done(issued.id)
        fixture.runtime.done(issued.id)

        assertEquals(1, fixture.events.completed)
        assertEquals(0, fixture.events.failed)
        assertEquals(0, fixture.runtime.stops)
        assertEquals(1, fixture.runtime.focusReleases)
        assertFalse(fixture.actuator.isAppSpeechActive())
        fixture.runtime.advanceBy(7_999L)
        fixture.publish(notice(frame = 2L, captured = 9_999L, until = 10_399L))
        assertFalse(fixture.dispatch())
        assertEquals(1, fixture.runtime.spoken.size)
        assertEquals(1, fixture.runtime.focusRequests)
        fixture.runtime.advanceBy(1L)
        assertTrue(fixture.dispatch())
        assertEquals(2, fixture.runtime.spoken.size)
        val next = fixture.runtime.spoken.last()
        fixture.runtime.start(next.id)
        fixture.runtime.done(next.id)
        assertEquals(2, fixture.events.completed)
        assertEquals(0, fixture.events.failed)
    }

    @Test
    fun startedRiskOwnerSuppressesMainReferenceWithoutPreemptionOrReferenceCooldown() {
        val fixture = Fixture()
        val risk = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            fixture.actuator.emit(
                action = FeedbackAction(
                    trackId = "risk-owner",
                    message = "정지하세요. 바로 앞에 장애물이 있습니다.",
                    level = MessageLevel.STOP,
                    vibrationPatternMs = null,
                    reason = "crosswalk-reference-transport-test",
                    validUntilMs = DEADLINE,
                ),
                isStillValidAtStart = { true },
                onSpeechCompleted = { risk.completed += 1 },
                onSpeechFailed = { risk.failed += 1 },
                onSpeechCancelled = { risk.failed += 1 },
            ).speech,
        )
        assertReferenceDoesNotPreemptStartedOwner(fixture, risk)
    }

    @Test
    fun startedTmapNavigationOwnerSuppressesMainReferenceWithoutPreemptionOrReferenceCooldown() {
        val fixture = Fixture()
        val navigation = Events()
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            fixture.actuator.speakNavigation(
                message = "경로 기준으로 20m 앞, 오른쪽으로 꺾으세요.",
                onCompleted = { navigation.completed += 1 },
                onFailed = { navigation.failed += 1 },
                onCancelled = { navigation.failed += 1 },
                requiresExplicitTerminalCallback = true,
                isStillValidAtStart = { true },
            ),
        )
        assertReferenceDoesNotPreemptStartedOwner(fixture, navigation)
    }

    private fun assertUnsubmittedReferenceFailed(fixture: Fixture) {
        assertTrue(fixture.runtime.spoken.isEmpty())
        assertEquals(1, fixture.runtime.stops)
        assertEquals(0, fixture.events.completed)
        assertEquals(1, fixture.events.failed)
        assertEquals(1, fixture.runtime.focusRequests)
        assertEquals(1, fixture.runtime.focusReleases)
        assertFalse(fixture.actuator.isAppSpeechActive())
        assertEquals("tts=ready", fixture.actuator.statusText())
    }

    private fun assertReferenceDoesNotPreemptStartedOwner(fixture: Fixture, previous: Events) {
        val issued = fixture.runtime.spoken.single()
        fixture.runtime.start(issued.id)
        val stopsBefore = fixture.runtime.stops
        val focusRequestsBefore = fixture.runtime.focusRequests

        assertFalse(fixture.dispatch())

        assertEquals(listOf(issued), fixture.runtime.spoken)
        assertEquals(stopsBefore, fixture.runtime.stops)
        assertEquals(focusRequestsBefore, fixture.runtime.focusRequests)
        assertEquals(0, fixture.runtime.focusReleases)
        assertEquals(0, fixture.events.completed)
        assertEquals(0, fixture.events.failed)
        assertEquals(0, previous.completed)
        assertEquals(0, previous.failed)
        assertTrue(fixture.actuator.isAppSpeechActive())
        fixture.runtime.done(issued.id)
        assertEquals(1, previous.completed)
        assertEquals(0, previous.failed)
        assertFalse(fixture.actuator.isAppSpeechActive())

        // Main released its rejected reservation; rejection must not consume the DONE interval.
        assertTrue(fixture.dispatch())
        assertEquals(2, fixture.runtime.spoken.size)
        val reference = fixture.runtime.spoken.last()
        assertEquals(fixture.transportedNotices.last().message, reference.message)
        assertEquals(TextToSpeech.QUEUE_ADD, reference.queueMode)
        fixture.runtime.start(reference.id)
        fixture.runtime.done(reference.id)
        assertEquals(1, fixture.events.completed)
        assertEquals(0, fixture.events.failed)
    }

    private class Fixture {
        val main = MainActivity()
        val runtime = FakeRuntime()
        val events = Events()
        val transportedNotices = mutableListOf<CrosswalkReferenceNotice>()
        val owner = CrosswalkReferenceDeliveryOwner(WalkRuntimeEpoch("crosswalk-walk", 0L), 1, 2, 3L)
        var outputAllowed = true
        var activeRouteId: String? = "route"
        var progress: TactileActiveRouteProgress? = TactileActiveRouteProgress(1L, 0)
        val actuator = AndroidFeedbackActuator(runtime = runtime, speechAllowed = { outputAllowed })

        init {
            runtime.ready()
            assertEquals("tts=ready", actuator.statusText())
            publish(notice())
        }

        fun publish(notice: CrosswalkReferenceNotice?) =
            main.updateCrosswalkReferenceObservation(notice, owner, runtime.now)

        fun revoke(change: String) {
            when (change) {
                "permission" -> outputAllowed = false
                "route-id" -> activeRouteId = "replacement-route"
                "route-version" -> progress = TactileActiveRouteProgress(2L, 0)
                "route-segment" -> progress = TactileActiveRouteProgress(1L, 1)
                else -> error("unknown revocation: $change")
            }
        }

        fun dispatch(): Boolean = main.dispatchCrosswalkReference(
            owner = owner,
            nowElapsedRealtimeMs = { runtime.now },
            outputIsCurrent = { outputAllowed },
            routeIsCurrent = { it.isValidForRoute(activeRouteId, progress) },
            speak = { notice, current, completed, failed ->
                transportedNotices += notice
                actuator.speakAdvisory(
                    message = notice.message,
                    validUntilMs = notice.validUntilElapsedRealtimeMs,
                    isStillValid = current,
                    onCompleted = { events.completed += 1; completed() },
                    onFailed = { events.failed += 1; failed() },
                )
            },
        )
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
            kotlin.error("crosswalk reference must not request a progress tone")
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
        const val DEADLINE = 1_300L

        fun notice(frame: Long = 1L, captured: Long = 1_000L, until: Long = DEADLINE) =
            CrosswalkReferenceNotice("crosswalk", frame, captured, until, "route", 1L, 0)
    }
}
