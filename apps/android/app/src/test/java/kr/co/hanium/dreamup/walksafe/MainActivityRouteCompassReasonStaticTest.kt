package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-8: every route guidance path passes the compass cause of the facing lookup it just made. */
class MainActivityRouteCompassReasonStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun theFacingLookupRecordsItsCauseAndClearsItWhenNoCompassIsActive() {
        val lookup = main.substringAfter("private fun currentRouteFacingObservation(nowMs: Long): RouteFacingObservation? {")
            .substringBefore("private fun routeCompassStatusMessage(")
        val cleared = lookup.indexOf("latestRouteFacingUnavailableReason = null")
        assertTrue(cleared >= 0)
        assertTrue(cleared < lookup.indexOf(") return null"))
        val heading = lookup.indexOf("val facing = earthOrientationTracker.latestRouteCompassHeading(nowMs)")
        assertTrue(heading > cleared)
        assertTrue(lookup.indexOf("latestRouteFacingUnavailableReason = facing.reason") > heading)
    }

    @Test
    fun automaticManualAndRetryGuidanceReadTheCauseRightAfterTheirOwnLookup() {
        assertTrue(main.contains(
            "routeNavigator.currentGuidance(nowMs, currentRouteFacingObservation(nowMs), latestRouteFacingUnavailableReason)"))
        val automatic = main.substringAfter("val facing = currentRouteFacingObservation(nowMs)\n        val update = routeNavigator.update(")
            .substringBefore("recordRouteAlignmentDiagnostic(update, facing")
        assertTrue(automatic.contains("facingObservation = facing,\n            facingUnavailableReason = latestRouteFacingUnavailableReason,"))
        val retry = main.substringAfter("val update = routeNavigator.retryGuidance(").substringBefore(") ?: return@Runnable")
        assertTrue(retry.indexOf("facingObservation = currentRouteFacingObservation(nowMs)") <
            retry.indexOf("facingUnavailableReason = latestRouteFacingUnavailableReason"))
        // No other route guidance call bypasses the cause.
        assertEquals(1, Regex("""routeNavigator\.currentGuidance\(""").findAll(main).count())
        assertEquals(1, Regex("""routeNavigator\.retryGuidance\(""").findAll(main).count())
        assertEquals(1, Regex("""routeNavigator\.update\(""").findAll(main).count())
    }
}
