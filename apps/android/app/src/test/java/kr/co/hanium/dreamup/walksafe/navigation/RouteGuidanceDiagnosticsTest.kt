package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-34: the route sentence carries its relative angle and expression level into the diagnostic chain. */
class RouteGuidanceDiagnosticsTest {
    @Test
    fun eachSentenceRecordsTheRelativeAngleAndHowTheFacingWasExpressed() {
        fun diagnostic(observation: RouteFacingObservation?) =
            requireNotNull(navigator().update(fix(), 1_000, false, facingObservation = observation).facingDiagnostic)
        assertEquals(RouteFacingSpeechDiagnostic(0, RouteFacingExpression.CLOCK), diagnostic(facing(90.0)))
        assertEquals(RouteFacingSpeechDiagnostic(90, RouteFacingExpression.CLOCK), diagnostic(facing(0.0)))
        assertEquals(RouteFacingSpeechDiagnostic(0, RouteFacingExpression.COARSE),
            diagnostic(facing(90.0).copy(headingAccuracyDegrees = 20.0)))
        assertEquals(RouteFacingSpeechDiagnostic(null, RouteFacingExpression.UNKNOWN), diagnostic(null))
    }

    @Test
    fun generatedAndStartEventsShareTheCueIdAndCarryNoCoordinatesOrText() {
        val update = navigator().update(fix(), 1_000, false, facingObservation = facing(0.0))
        val generated = RouteGuidanceDiagnostics.guidanceGenerated(update, 1_234L)
        val token = requireNotNull(update.speechCueToken)
        assertEquals("${token.routeRevision}:${token.cueRevision}", generated["cue_id"])
        assertEquals(1_234L, generated["elapsed_realtime_ms"])
        assertEquals("CURRENT_SEGMENT", generated["reason"])
        assertEquals(90, generated["route_bearing_deg"])
        assertEquals(90, generated["relative_angle_deg"])
        assertEquals("CLOCK", generated["expression_level"])
        val started = RouteGuidanceDiagnostics.guidanceStartChecked(update, 1_300L, current = true)
        assertEquals(generated["cue_id"], started["cue_id"])
        assertEquals("started", started["result"])
        assertEquals("revoked", RouteGuidanceDiagnostics.guidanceStartChecked(update, 1_301L, current = false)["result"])
        assertOnlyEnumsNumbersAndIds(generated + started)
    }

    @Test
    fun compassAndAlignmentInputsUseWholeDegreesAndAges() {
        val observation = RouteFacingObservation(91.6, 4.4, 900L)
        val compass = RouteGuidanceDiagnostics.compassState(
            RouteCompassHeadingResult(observation, RouteCompassHeadingReason.VALID), 1_000L)
        assertEquals("VALID", compass["reason"])
        assertEquals(92, compass["rear_heading_deg"])
        assertEquals(4, compass["heading_accuracy_deg"])
        assertEquals(100L, compass["compass_age_ms"])
        assertNotNull(compass["compass_source"])
        val rejected = RouteGuidanceDiagnostics.compassState(
            RouteCompassHeadingResult(null, RouteCompassHeadingReason.REAR_AXIS_VERTICAL), 1_000L)
        assertEquals("REAR_AXIS_VERTICAL", rejected["reason"])
        assertNull(rejected["rear_heading_deg"])
        val alignment = RouteGuidanceDiagnostics.alignment(
            RouteAlignmentDiagnostic(RouteAlignmentReason.CURRENT_SEGMENT, 2, 179.6), observation, 7.6f, 1_000L)
        assertEquals(mapOf<String, Any?>("reason" to "CURRENT_SEGMENT", "segment_index" to 2, "route_bearing_deg" to 180,
            "rear_heading_deg" to 92, "compass_source" to observation.source.name, "compass_age_ms" to 100L,
            "position_uncertainty_m" to 8), alignment)
        assertOnlyEnumsNumbersAndIds(compass + alignment)
    }

    private fun assertOnlyEnumsNumbersAndIds(fields: Map<String, Any?>) {
        fields.forEach { (key, value) ->
            assertTrue("$key=$value", value == null || value is Int || value is Long ||
                (value is String && Regex("[A-Z_]+|[a-z]+|\\d+:\\d+").matches(value)))
        }
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000) }
    private fun facing(degrees: Double) = RouteFacingObservation(degrees, 5.0, 1_000)
}
