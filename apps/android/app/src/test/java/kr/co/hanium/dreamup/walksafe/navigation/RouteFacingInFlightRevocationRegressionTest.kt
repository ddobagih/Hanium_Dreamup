package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-23: facing loss during playback revokes the sentence on every dispatch path, not only location updates. */
class RouteFacingInFlightRevocationRegressionTest {
    @Test fun manualRequestWhileFacingIsLostCarriesTheRevocationToTheDispatcher() {
        val navigator = navigator()
        val playing = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        // S08-5: the first precise sample is spoken with its clock number.
        assertTrue(playing.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.reserveInstruction(playing))
        // The first unconfirmed nominal edge at 1,100ms starts the fixed GRACE.
        // The later null must not renew that origin or the original 500ms sensor lease.
        val edge = facing(1_100L, 80.0)
        val edgeQuality = RouteFacingGuidance.evaluate(
            requireNotNull(playing.routeAlignmentDiagnostic?.bearingDegreesTrueNorth), edge, 1_100L,
        )
        assertFalse(edgeQuality.clockPrecisionAvailable)
        assertEquals(12, edgeQuality.clockHour)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, edgeQuality.clockReferenceKind)
        assertNull(edgeQuality.clockRange)
        val stillPlaying = requireNotNull(navigator.currentGuidance(1_100L, edge))
        assertEquals("guidance_in_flight", stillPlaying.reason)
        assertFalse(stillPlaying.cancelStaleNavigationSpeech)
        val manual = requireNotNull(navigator.currentGuidance(1_200L, null))
        assertFalse(manual.cancelStaleNavigationSpeech)
        assertEquals("guidance_in_flight", manual.reason)
        assertNull(manual.instruction)
        assertEquals(1_000L, playing.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, playing.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertTrue(navigator.isInstructionCurrent(playing, 1_200L, null))
        assertFalse(navigator.reserveInstruction(playing))
        assertEquals(1_100L, navigator.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(2_600L, navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertNull(requireNotNull(navigator.retryGuidance(2_599L, null)).instruction)
        assertTrue(navigator.isInstructionCurrent(playing, 2_599L, null))
        assertFalse(navigator.isInstructionCurrent(playing, 2_600L, null))
        val recheck = requireNotNull(navigator.retryGuidance(2_600L, null))
        assertNotNull(recheck.instruction)
        assertTrue(recheck.cancelStaleNavigationSpeech)
        assertFalse(navigator.isInstructionCompletionCurrent(playing, 2_600L))
        assertFalse(recheck.instruction.orEmpty().contains("꺾으세요"))
        assertTrue(recheck.instruction.orEmpty().contains("경로 기준 3시 방향 회전이 있습니다"))
        assertFalse(Regex("""후면 카메라.*약 \d+시""").containsMatchIn(recheck.instruction.orEmpty()))
        assertFalse(recheck.instruction.orEmpty().contains("몸을 돌리세요"))
        assertTrue(navigator.reserveInstruction(recheck))
        assertTrue(navigator.isInstructionCurrent(recheck, 2_600L, null))
    }

    @Test fun everyRouteDispatchPathHonorsTheRevocationBeforeReservingAReplacement() {
        val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val dispatch = MainActivityRefactorSource.function(main, "private fun tryDispatchRouteInstruction(")
        val cancel = dispatch.indexOf("if (update.cancelStaleNavigationSpeech) speech.cancelNavigation()")
        assertTrue(cancel >= 0)
        assertTrue(cancel < dispatch.indexOf("val instruction = update.instruction"))
        assertTrue(cancel < dispatch.indexOf("routeNavigator.reserveInstruction(update)"))
        val navigation = MainActivityRefactorSource.factory(main, "createNavigationSessions")
        assertTrue(navigation.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(navigation.contains("output = { speechOutputController }"))
        val speech = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
        assertTrue(speech.contains("override fun cancelNavigation() { output().currentActuatorOrNull()?.cancelNavigationSpeech() }"))
        assertTrue(MainActivityRefactorSource.member(main, "tryDispatchRouteInstruction")
            .contains("routeGuidanceController.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val manual = MainActivityRefactorSource.function(main, "private fun speakNextNavigationInstruction(")
        assertTrue(manual.contains("val compass = route.currentRouteCompassHeadingResult(nowMs)"))
        assertTrue(manual.contains("routeRun().navigator.currentGuidance(nowMs, compass?.observation, compass?.reason)"))
        assertTrue(manual.contains("route.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val voices = MainActivityRefactorSource.factory(main, "createVoiceInteractionControllers")
        assertTrue(voices.contains("routeRun = { navigationRunState }"))
        assertTrue(voices.contains("route = VoiceRoutePeerAdapter("))
        assertTrue(voices.contains("guidance = { routeGuidanceController }, compass = { routeCompassSession }"))
        val peer = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.member(peer, "tryDispatchRouteInstruction")
            .contains("guidance().tryDispatchRouteInstruction(update, epoch, compass)"))
        val retry = MainActivityRefactorSource.function(main, "private fun retryCurrentNavigationSpeech(")
        assertTrue(retry.contains("retryAdmittedNavigationSpeech(nowMs, epoch, compass)"))
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        val admitted = MainActivityRefactorSource.member(controller, "retryAdmittedNavigationSpeech", 1)
        assertTrue(admitted.contains("routeNavigator.retryGuidance("))
        assertTrue(MainActivityRefactorSource.member(controller, "retryAdmittedNavigationSpeech")
            .contains("= ::tryDispatchRouteInstruction"))
        assertTrue(admitted.contains("dispatch(update, epoch, compass)"))
        val scheduled = MainActivityRefactorSource.function(main, "private fun scheduleNavigationSpeechRetry(")
        assertTrue(scheduled.contains("retryCurrentNavigationSpeech(elapsedRealtimeMs(), epoch, requestGeneration, lifecycleGeneration)"))
        assertTrue(navigation.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
}
