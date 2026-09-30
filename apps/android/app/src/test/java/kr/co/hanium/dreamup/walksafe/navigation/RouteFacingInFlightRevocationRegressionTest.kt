package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // A small fresh turn keeps the sentence, even when it only nears a clock sector edge while
        // the sensor precision still supports a number; losing the direction does not.
        assertEquals("guidance_in_flight", navigator.currentGuidance(1_100L, facing(1_100L, 80.0))?.reason)
        val manual = requireNotNull(navigator.currentGuidance(1_200L, null))
        assertTrue(manual.cancelStaleNavigationSpeech)
        assertNotNull(manual.instruction)
        assertFalse(manual.instruction.orEmpty().contains("꺾으세요"))
        assertFalse(navigator.isInstructionCurrent(playing, 1_200L, null))
    }

    @Test fun everyRouteDispatchPathHonorsTheRevocationBeforeReservingAReplacement() {
        val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val dispatch = main.substringAfter("private fun tryDispatchRouteInstruction(")
            .substringBefore("private fun failInterruptedRouteDecisionSpeech(")
        val cancel = dispatch.indexOf("if (update.cancelStaleNavigationSpeech) feedbackActuator?.cancelNavigationSpeech()")
        assertTrue(cancel >= 0)
        assertTrue(cancel < dispatch.indexOf("val instruction = update.instruction"))
        assertTrue(cancel < dispatch.indexOf("routeNavigator.reserveInstruction(update)"))
        // The manual repeat and the retry runnable both reach speech only through this function.
        // S08-8: the manual repeat also passes the compass cause of that same facing lookup.
        assertTrue(main.contains("val update = routeNavigator.currentGuidance(nowMs, currentRouteFacingObservation(nowMs), " +
            "latestRouteFacingUnavailableReason)"))
        val retry = main.substringAfter("private fun scheduleNavigationSpeechRetry(")
            .substringBefore("private fun tryDispatchRouteInstruction(")
        assertTrue(retry.contains("tryDispatchRouteInstruction(update, epoch)"))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
}
