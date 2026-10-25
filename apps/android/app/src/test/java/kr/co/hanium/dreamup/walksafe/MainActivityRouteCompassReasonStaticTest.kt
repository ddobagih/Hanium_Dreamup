package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-8: every route guidance path passes the compass cause of the facing lookup it just made. */
class MainActivityRouteCompassReasonStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val compass = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt").readText()
    private val guide = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()

    @Test
    fun theFacingLookupRecordsItsCauseAndClearsItWhenNoCompassIsActive() {
        assertTrue(main.contains("routeCompassSession.currentRouteCompassHeadingResult(nowMs)"))
        val lookup = compass.substringAfter("fun currentRouteCompassHeadingResult(nowMs: Long): RouteCompassHeadingResult? {")
        val cleared = lookup.indexOf("latestRouteFacingUnavailableReason = null")
        assertTrue(cleared >= 0)
        assertTrue(cleared < lookup.indexOf(") return null"))
        val heading = lookup.indexOf("val facing = tracker.heading(nowMs)")
        assertTrue(heading > cleared)
        assertTrue(lookup.indexOf("latestRouteFacingUnavailableReason = facing.reason") > heading)
    }

    @Test
    fun automaticManualAndRetryGuidanceReadTheCauseRightAfterTheirOwnLookup() {
        assertTrue(guide.contains(
            "routeNavigator.currentGuidance(nowMs, latestCompass?.observation, latestCompass?.reason)"))
        val automatic = guide.substringAfter("val facing = compass?.observation\n        val update = routeNavigator.update(")
            .substringBefore("recordRouteAlignmentDiagnostic(update, facing")
        assertTrue(automatic.contains("facingObservation = facing,\n            facingUnavailableReason = compass?.reason,"))
        val retry = guide.substringAfter("val update = (if (firstBadAtMs == null) routeNavigator.retryGuidance(").substringBefore(") ?: return")
        val observation = retry.indexOf("facingObservation = compass?.observation")
        assertTrue(observation >= 0 && observation < retry.indexOf("facingUnavailableReason = compass?.reason"))
        // No other route guidance call bypasses the cause.
        assertTrue(retry.contains("routeNavigator.retryGuidance(snapshotNowMs, compass?.observation, compass?.reason, firstBadAtMs)"))
        assertEquals(1, Regex("""routeNavigator\.currentGuidance\(""").findAll(guide).count())
        assertEquals(2, Regex("""routeNavigator\.retryGuidance\(""").findAll(guide).count())
        assertEquals(1, Regex("""routeNavigator\.update\(""").findAll(guide).count())
        assertEquals(0, Regex("""routeNavigator\.(?:currentGuidance|retryGuidance|update)\(""").findAll(main).count())
    }
}
