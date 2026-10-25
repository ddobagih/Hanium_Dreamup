package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.navigation.dispatchRouteInstructionSpeech
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the actual actuator and the delivery function shared by Main; only platform effects are fake. */
class RouteInstructionSpeechActuatorIntegrationTest {
    @Test
    fun actualActionDoneSubmitsInformationOnceAndOldDoneCannotFinishTheNewNativeUtterance() {
        val f = Fixture()
        val action = f.offer()
        assertTrue(f.submit(action))
        val actionId = f.runtime.spoken.single().id
        assertEquals(0, f.actionCompleted)
        assertFalse(f.navigator.isInformationCurrent(requireNotNull(action.infoUtterance), f.runtime.now))
        f.runtime.start(actionId)
        f.runtime.advance(700L)
        f.runtime.done(actionId)
        assertEquals(1, f.actionCompleted)
        assertEquals(2, f.runtime.spoken.size)
        assertEquals(action.infoUtterance?.message, f.runtime.spoken.last().message)
        val informationId = f.runtime.spoken.last().id
        assertNotEquals(actionId, informationId)
        assertTrue(f.actuator.isAppSpeechActive())
        assertEquals(0, f.informationFinished)
        f.runtime.done(actionId)
        f.runtime.done(actionId)
        assertEquals(1, f.actionCompleted)
        assertEquals(0, f.informationFinished)
        assertTrue(f.actuator.isAppSpeechActive())
        f.runtime.start(informationId)
        assertTrue(f.navigator.isInformationCurrent(requireNotNull(action.infoUtterance), f.runtime.now))
        f.runtime.advance(100L)
        f.runtime.done(informationId)
        assertEquals(1, f.informationFinished)
        assertEquals(0, f.actionFailed)
        assertFalse(f.actuator.isAppSpeechActive())
        f.runtime.done(actionId)
        f.runtime.done(informationId)
        assertEquals(1, f.informationFinished)
        assertEquals(2, f.runtime.spoken.size)
        assertEquals(1_500L, action.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test
    fun riskFlushCancelsPendingOrStartedInformationWithoutRetryingTheCompletedAction() {
        for (started in listOf(false, true)) {
            val f = Fixture()
            val action = f.offer()
            val informationId = f.finishAction(action)
            if (started) f.runtime.start(informationId)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk().speech)
            val riskId = f.runtime.spoken.last().id
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertEquals(1, f.informationFinished)
            assertFalse(f.navigator.isInformationCurrent(requireNotNull(action.infoUtterance), f.runtime.now))
            f.runtime.done(informationId)
            assertTrue(f.actuator.isAppSpeechActive())
            f.runtime.start(riskId)
            f.runtime.done(riskId)
            f.runtime.advance(2_000L)
            assertEquals("started=$started", 3, f.runtime.spoken.size)
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertEquals(1, f.informationFinished)
            assertTrue(f.navigator.retryGuidance(f.runtime.now, f.facing())?.instruction == null)
        }
    }

    @Test
    fun informationRechecksNewOutputOwnersBeforeSubmissionAndAtActualStart() {
        for (change in listOf("voice", "owner", "route", "context")) {
            val f = Fixture()
            val action = f.offer()
            val informationId = f.finishAction(action)
            when (change) {
                "voice" -> f.voiceActive = true
                "owner" -> f.outputOwnerCurrent = false
                "route" -> f.navigator.setRoute(route())
                "context" -> f.navigator.onPositioningEvidenceInterrupted()
            }
            f.runtime.start(informationId)
            assertEquals(change, 1, f.informationFinished)
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertFalse(f.actuator.isAppSpeechActive())
            f.runtime.done(informationId)
            f.runtime.advance(2_000L)
            assertEquals(2, f.runtime.spoken.size)
            assertEquals(1, f.informationFinished)
            assertTrue(f.runtime.stopCount > 0)
        }
        for (risk in listOf(false, true)) {
            val f = Fixture()
            val action = f.offer()
            f.afterActionCompleted = {
                if (risk) assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.emitRisk().speech)
                else f.voiceActive = true
            }
            assertTrue(f.submit(action))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertEquals(1, f.informationFinished)
            assertEquals(if (risk) 2 else 1, f.runtime.spoken.size)
            assertFalse(f.runtime.spoken.any { it.message == action.infoUtterance?.message })
            assertFalse(f.navigator.reserveInformation(requireNotNull(action.infoUtterance), f.runtime.now))
        }
    }

    @Test
    fun cancelledExpiredOrUnacknowledgedActionCannotEnableInformation() {
        for (cause in listOf("cancelled", "expired", "unacknowledged")) {
            val f = Fixture()
            val action = f.offer()
            assertTrue(f.submit(action))
            val actionId = f.runtime.spoken.single().id
            when (cause) {
                "expired" -> {
                    f.runtime.advance(501L)
                    f.runtime.start(actionId)
                }
                "cancelled" -> assertTrue(f.actuator.cancelNavigationSpeech())
                "unacknowledged" -> {
                    f.runtime.start(actionId)
                    // Owner unchanged, but the producer revokes its context before the ACK.
                    f.navigator.onPositioningEvidenceInterrupted()
                }
            }
            f.runtime.done(actionId)
            assertEquals(if (cause == "unacknowledged") 1 else 0, f.actionCompleted)
            assertEquals(if (cause == "unacknowledged") 0 else 1, f.actionFailed)
            assertEquals(0, f.informationFinished)
            assertEquals(1, f.runtime.spoken.size)
            assertFalse(f.navigator.reserveInformation(requireNotNull(action.infoUtterance), f.runtime.now))
            if (cause != "unacknowledged") {
                f.runtime.advance(2_000L)
                val retry = requireNotNull(f.navigator.retryGuidance(f.runtime.now, f.facing()))
                assertNotEquals(action.speechCueToken, retry.speechCueToken)
                assertTrue(requireNotNull(retry.infoUtterance).message.contains("전체 경로는"))
            }
        }
    }

    @Test
    fun informationEngineFailureConsumesOnlyInformationAndDoesNotReplayTheAction() {
        val f = Fixture()
        val action = f.offer()
        val informationId = f.finishAction(action)
        f.runtime.start(informationId)
        f.runtime.error(informationId)
        assertEquals(1, f.informationFinished)
        assertEquals(1, f.actionCompleted)
        assertEquals(0, f.actionFailed)
        f.runtime.done(informationId)
        f.runtime.advance(2_000L)
        assertEquals(2, f.runtime.spoken.size)
        assertFalse(f.navigator.reserveInformation(requireNotNull(action.infoUtterance), f.runtime.now))
        assertTrue(f.navigator.retryGuidance(f.runtime.now, f.facing())?.instruction == null)
    }

    @Test
    fun passedGuideActionIsNotSuppressedByThePreviousGuidesStartedInformation() {
        val f = Fixture(cornerRoute())
        val first = f.cornerSample(16.0, 1_000L, 90.0)
        assertEquals(1, first.guideIndex)
        assertTrue(requireNotNull(first.infoUtterance).message.contains("전체 경로는"))
        val informationId = f.finishAction(first)
        f.runtime.start(informationId)
        assertTrue(f.actuator.isAppSpeechActive())
        val vertex = f.cornerSample(20.0, 2_000L, 180.0)
        assertEquals(1, vertex.guideIndex)
        assertTrue("a new essential near-turn action also yields the previous INFO",
            vertex.cancelStaleNavigationSpeech)
        // Main also applies the earlier vertex update; cancellation may correctly occur here.
        if (vertex.cancelStaleNavigationSpeech) f.actuator.cancelNavigationSpeech()
        assertEquals(1, f.informationFinished)
        assertFalse(f.actuator.isAppSpeechActive())
        val passed = f.cornerSample(26.0, 3_000L, 180.0)
        assertEquals("the trusted passage must select the next guide", 2, passed.guideIndex)
        assertTrue(passed.instruction.orEmpty().isNotEmpty())
        assertEquals(2, f.runtime.spoken.size)
        // This is Main's cancellation boundary before reserving/submitting the replacement.
        if (passed.cancelStaleNavigationSpeech) f.actuator.cancelNavigationSpeech()
        assertTrue("the passed guide's INFO must not occupy NAVIGATION and suppress the new action",
            f.submit(passed))
        val newActionId = f.runtime.spoken.last().id
        assertNotEquals(informationId, newActionId)
        assertEquals(1, f.informationFinished)
        assertEquals(0, f.actionFailed)
        f.runtime.start(newActionId)
        f.runtime.done(informationId)
        assertEquals(1, f.actionCompleted)
        f.runtime.done(newActionId)
        assertEquals(2, f.actionCompleted)
        assertEquals(1, f.informationFinished)
        assertEquals(1_500L, first.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test
    fun manualActionAfterInformationHasCompletedStillStartsNormally() {
        val f = Fixture()
        val first = f.offer()
        val informationId = f.finishAction(first)
        f.runtime.start(informationId)
        f.runtime.done(informationId)
        assertEquals(1, f.informationFinished)
        assertFalse(f.actuator.isAppSpeechActive())
        f.runtime.advance(100L)
        val manual = requireNotNull(f.navigator.currentGuidance(f.runtime.now, f.facing()))
        if (manual.cancelStaleNavigationSpeech) f.actuator.cancelNavigationSpeech()
        assertTrue(f.submit(manual))
        val manualId = f.runtime.spoken.last().id
        f.runtime.start(manualId)
        f.runtime.done(informationId)
        assertEquals(1, f.actionCompleted)
        f.runtime.done(manualId)
        assertEquals(2, f.actionCompleted)
        assertEquals(1, f.informationFinished)
        assertEquals(0, f.actionFailed)
    }

    @Test
    fun manualActionPreemptsPendingOrStartedInformationAndLateInfoDoneKeepsTheNewAction() {
        for (started in listOf(false, true)) {
            val f = Fixture()
            val first = f.offer()
            val informationId = f.finishAction(first)
            if (started) f.runtime.start(informationId)
            assertTrue(f.actuator.isAppSpeechActive())
            val manual = requireNotNull(f.navigator.currentGuidance(f.runtime.now, f.facing()))
            assertTrue(manual.instruction.orEmpty().isNotEmpty())
            assertTrue(manual.cancelStaleNavigationSpeech)
            if (manual.cancelStaleNavigationSpeech) f.actuator.cancelNavigationSpeech()
            assertEquals(1, f.informationFinished)
            assertTrue(f.submit(manual))
            val manualId = f.runtime.spoken.last().id
            f.runtime.start(manualId)
            f.runtime.done(informationId)
            assertEquals(1, f.actionCompleted)
            assertTrue(f.actuator.isAppSpeechActive())
            f.runtime.done(manualId)
            assertEquals(2, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertEquals(1, f.informationFinished)
            assertEquals(3, f.runtime.spoken.size)
            assertEquals(1_500L, first.speechCueToken?.facingValidUntilElapsedRealtimeMs)
            assertFalse(f.navigator.reserveInformation(requireNotNull(first.infoUtterance), f.runtime.now))
        }
    }

    @Test
    fun manualRequestDuringAnActualActionStillDefersWithoutCancellingItAsInformation() {
        val f = Fixture()
        val first = f.offer()
        assertTrue(f.submit(first))
        val actionId = f.runtime.spoken.single().id
        f.runtime.start(actionId)
        f.runtime.advance(100L)
        val deferred = requireNotNull(f.navigator.currentGuidance(f.runtime.now, f.facing()))
        assertEquals("guidance_in_flight", deferred.reason)
        assertTrue(deferred.instruction == null)
        assertFalse(deferred.cancelStaleNavigationSpeech)
        assertEquals(1, f.runtime.spoken.size)
        assertTrue(f.actuator.isAppSpeechActive())
        assertEquals(0, f.actionFailed)
        f.runtime.done(actionId)
        assertEquals(1, f.actionCompleted)
        assertEquals(0, f.actionFailed)
        assertEquals(2, f.runtime.spoken.size)
    }

    @Test
    fun lostContextCancelsStartedInformationOnceWithoutCancellingTheRecoveredAction() {
        val f = Fixture()
        val first = f.offer()
        val informationId = f.finishAction(first)
        f.runtime.start(informationId)
        f.navigator.onPositioningEvidenceInterrupted()
        val revoked = requireNotNull(f.navigator.retryGuidance(f.runtime.now, f.facing()))
        assertTrue(revoked.instruction == null)
        assertTrue(revoked.cancelStaleNavigationSpeech)
        if (revoked.cancelStaleNavigationSpeech) f.actuator.cancelNavigationSpeech()
        assertEquals(1, f.informationFinished)
        assertFalse(f.actuator.isAppSpeechActive())
        assertTrue(f.navigator.retryGuidance(f.runtime.now, f.facing()) == null)
        f.runtime.advance(100L)
        val recovery = f.offer()
        assertFalse(recovery.cancelStaleNavigationSpeech)
        // Lost position authority needs two distinct fresh fixes before speech can resume.
        assertEquals("route_geometry_recheck_required", recovery.reason)
        assertTrue(recovery.instruction == null)
        assertTrue(f.navigator.currentGuidance(f.runtime.now, f.facing()) == null)
        f.runtime.advance(100L)
        val confirmedRecovery = f.offer()
        assertFalse(confirmedRecovery.cancelStaleNavigationSpeech)
        val manual = requireNotNull(f.navigator.currentGuidance(f.runtime.now, f.facing()))
        assertFalse(manual.cancelStaleNavigationSpeech)
        assertTrue(f.submit(manual))
        val newId = f.runtime.spoken.last().id
        f.runtime.start(newId)
        f.runtime.done(informationId)
        assertTrue(f.actuator.isAppSpeechActive())
        assertEquals(1, f.actionCompleted)
        f.runtime.done(newId)
        assertEquals(2, f.actionCompleted)
        assertEquals(0, f.actionFailed)
        assertEquals(1, f.informationFinished)
    }

    private class Fixture(routeData: WalkingRoute = route()) {
        val runtime = SpeechRuntime()
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { runtime.now }).apply { setRoute(routeData) }
        val actuator = AndroidFeedbackActuator(runtime = runtime).also { runtime.ready() }
        var outputOwnerCurrent = true
        var voiceActive = false
        var actionCompleted = 0
        var actionFailed = 0
        var informationFinished = 0
        var afterActionCompleted: () -> Unit = {}
        private var heading = 90.0

        fun facing() = RouteFacingObservation(heading, 5.0, runtime.now)
        fun offer() = navigator.update(TrustedLocation(0.0, 0.0, 2f, runtime.now), runtime.now, false,
            facingObservation = facing())

        fun cornerSample(progressM: Double, at: Long, headingDegrees: Double): RouteNavigatorUpdate {
            runtime.advance(at - runtime.now)
            heading = headingDegrees
            val point = if (progressM <= 20.0) cornerPoint(progressM) else cornerPoint(20.0, 20.0 - progressM)
            return navigator.update(TrustedLocation(point.latitude, point.longitude, 3f, at), at, false,
                FilteredRoutePosition(point, 3.0, at, heading = RouteHeadingEstimate(headingDegrees, 8.0)),
                facingObservation = facing())
        }

        fun submit(action: RouteNavigatorUpdate): Boolean {
            assertTrue(navigator.reserveInstruction(action))
            return dispatchRouteInstructionSpeech(
                update = action,
                isActionCurrent = { outputOwnerCurrent && !voiceActive &&
                    navigator.isInstructionCurrent(action, runtime.now, facing()) },
                isInformationCurrent = { outputOwnerCurrent && !voiceActive &&
                    navigator.isInformationCurrent(it, runtime.now) },
                reserveInformation = { navigator.reserveInformation(it, runtime.now) },
                finishInformation = {
                    informationFinished += 1
                    navigator.finishInformation(it)
                },
                onActionCompleted = {
                    actionCompleted += 1
                    navigator.acknowledgeInstruction(action, runtime.now)
                    afterActionCompleted()
                },
                onActionFailed = {
                    actionFailed += 1
                    navigator.releaseInstruction(action, runtime.now)
                },
                speak = { message, current, completed, failed ->
                    actuator.speakNavigation(message, onCompleted = completed, onFailed = failed,
                        requiresExplicitTerminalCallback = true, onCancelled = failed,
                        isStillValidAtStart = current) == NavigationSpeechDispatchResult.ACCEPTED
                },
            )
        }

        fun finishAction(action: RouteNavigatorUpdate): String {
            assertTrue(submit(action))
            val actionId = runtime.spoken.single().id
            runtime.start(actionId)
            runtime.advance(100L)
            runtime.done(actionId)
            assertEquals(2, runtime.spoken.size)
            return runtime.spoken.last().id
        }

        fun emitRisk() = actuator.emit(
            FeedbackAction("risk-owner", "위험 안내입니다.", MessageLevel.STOP, null,
                "route-info-integration", runtime.now + 100L),
            onSpeechCompleted = null, onSpeechFailed = null, isStillValidAtStart = { true },
        )
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val dueMs: Long, val runnable: Runnable)
        var now = 1_000L
        var stopCount = 0
        val spoken = mutableListOf<Spoken>()
        private val tasks = mutableListOf<Task>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stopCount += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("No tone in this fixture")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS); runCurrent() }
        fun start(id: String) { requireNotNull(listener).onStart(id); runCurrent() }
        fun done(id: String) { requireNotNull(listener).onDone(id); runCurrent() }
        fun error(id: String) { requireNotNull(listener).onError(id); runCurrent() }
        fun advance(ms: Long) { now += ms; runCurrent() }
        private fun runCurrent() {
            while (true) {
                val index = tasks.indexOfFirst { it.dueMs <= now }
                if (index < 0) return
                tasks.removeAt(index).runnable.run()
            }
        }
    }

    companion object {
        private fun cornerPoint(eastM: Double, northM: Double = 0.0) =
            RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)

        private fun cornerRoute() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(120, 120),
            listOf(cornerPoint(0.0), cornerPoint(20.0), cornerPoint(20.0, -100.0)),
            listOf(WalkingRouteGuidePoint(0, cornerPoint(0.0), null, 0, 120, turnType = 200, pointType = "SP"),
                WalkingRouteGuidePoint(1, cornerPoint(20.0), null, 20, 100, turnType = 13),
                WalkingRouteGuidePoint(2, cornerPoint(20.0, -75.0), null, 95, 25, turnType = 11)))

        private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(333, 300),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 333, turnType = 200, pointType = "SP"),
                WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.001), null, 111, 222, turnType = 12)))
    }
}
