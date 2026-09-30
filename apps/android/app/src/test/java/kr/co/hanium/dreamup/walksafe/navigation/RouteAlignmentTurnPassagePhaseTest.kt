package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * S08-18 (08.txt:197): the bearing selector advanced to the next leg when the projection reached
 * the vertex, while the navigator passes the guide there only with its accuracy margin. In between
 * the compared bearing was the next leg but the turn sentence still described the vertex, so a
 * user who had already turned heard "the route is ahead ... turn right". Both now share one phase.
 */
class RouteAlignmentTurnPassagePhaseTest {
    @Test
    fun theNextLegBearingIsWithheldAtTheVertexUntilItsGuideIsPassed() {
        val route = cornerRoute()
        val atVertex = select(route, point(20.0), accepted(0), PendingRouteGuide(20.0, 10.0))
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, atVertex.diagnostic.reason)
        assertNull(atVertex.bearingDegrees)
        val aroundTheCorner = select(route, point(20.0, -2.0), accepted(1), PendingRouteGuide(20.0, 10.0))
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, aroundTheCorner.diagnostic.reason)
        assertNull(aroundTheCorner.bearingDegrees)
        // The approach keeps the incoming leg, matching the "N m ahead, turn" sentence.
        val before = select(route, point(15.0), accepted(0), PendingRouteGuide(20.0, 10.0))
        assertEquals(0, before.diagnostic.segmentIndex)
        assertBearing(90.0, before)
    }

    @Test
    fun atTheVertexTheUnpassedTurnIsStillSpokenWithoutAClockOrARequestToAskAgain() {
        // S08-18-F1: holding the bearing must not drop the turn cue, for a user who has not turned
        // yet (facing 90) or who already faces the outgoing leg (180). Degraded and production mode.
        listOf(true, false).forEach { degraded ->
            listOf(90.0, 180.0).forEach { facingAtVertex ->
                val navigator = RouteNavigator(allowDegradedRouteGuidance = degraded)
                    .apply { setRoute(cornerRoute(), origin = null) }
                val approach = navigator.update(fix(10.0, 0.0, 1_000), 1_000, false,
                    facingObservation = RouteFacingObservation(90.0, 5.0, 1_000))
                approach.instruction?.let { navigator.reserveInstruction(approach); navigator.acknowledgeInstruction(approach, 1_000) }
                val vertex = navigator.update(fix(20.0, 0.0, 2_000), 2_000, false,
                    facingObservation = RouteFacingObservation(facingAtVertex, 5.0, 2_000))
                val label = "degraded=$degraded facing=$facingAtVertex ${vertex.instruction}"
                println("S08-18-F1 $label")
                assertEquals(label, RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
                assertEquals(label, "안내 지점 부근입니다. 경로 기준으로 오른쪽으로 꺾으세요.", vertex.instruction)
                val text = vertex.instruction.orEmpty()
                assertFalse(label, text.contains("다시 안내를 요청"))
                assertFalse(label, text.contains("시 방향"))
                assertFalse(label, text.contains("앞쪽이 경로 진행 방향"))
                // A manual request while the hold only waits for passage repeats the same turn.
                vertex.instruction?.let { navigator.reserveInstruction(vertex); navigator.acknowledgeInstruction(vertex, 2_000) }
                val repeated = navigator.currentGuidance(2_100, RouteFacingObservation(facingAtVertex, 5.0, 2_100))
                assertEquals(label, vertex.instruction, repeated?.instruction)
            }
        }
    }

    @Test
    fun oncePassedOrWithoutAPendingGuideTheExistingAdvanceIsUnchanged() {
        val route = cornerRoute()
        listOf(null, PendingRouteGuide(100.0, 10.0), PendingRouteGuide(60.0, 10.0)).forEach { pending ->
            val selection = select(route, point(20.0), accepted(0), pending)
            assertEquals("$pending", RouteAlignmentReason.CURRENT_SEGMENT, selection.diagnostic.reason)
            assertEquals("$pending", 1, selection.diagnostic.segmentIndex)
            assertBearing(180.0, selection)
        }
    }

    @Test
    fun theHoldEndsAtThePassageMarginPastTheVertexEvenBeforeThePassageIsConfirmed() {
        // S08-18-F2: the hold is bounded by the navigator's passage margin, not by passage itself.
        val route = cornerRoute()
        listOf(3.0, 10.0, 15.0).forEach { marginM ->
            val pending = PendingRouteGuide(20.0, 10.0, marginM)
            val within = select(route, point(20.0, -marginM + 0.5), accepted(1), pending)
            assertEquals("margin=$marginM", RouteAlignmentReason.TURN_PASSAGE_PENDING, within.diagnostic.reason)
            assertTrue(within.beyondPendingTurnVertex)
            val past = select(route, point(20.0, -marginM - 1.0), accepted(1), pending)
            assertEquals("margin=$marginM", RouteAlignmentReason.CURRENT_SEGMENT, past.diagnostic.reason)
            assertEquals(1, past.diagnostic.segmentIndex)
            assertBearing(180.0, past)
            assertTrue(past.beyondPendingTurnVertex)
            // Two legs later the hold does not carry over.
            val later = select(threeLegRoute(), point(15.0, -80.0), accepted(2), pending)
            assertEquals("margin=$marginM", RouteAlignmentReason.CURRENT_SEGMENT, later.diagnostic.reason)
            assertEquals(2, later.diagnostic.segmentIndex)
        }
        val before = select(route, point(15.0), accepted(0), PendingRouteGuide(20.0, 10.0, 15.0))
        assertFalse(before.beyondPendingTurnVertex)
        assertBearing(90.0, before)
    }

    @Test
    fun productionModeAtTenAndFifteenMetreAccuracyHoldsOnlyWithinThePassageMargin() {
        // S08-18-F2: release builds pass a guide at max(3 m, accuracy). With the guide at the drawn
        // vertex the hold is exactly the passage wait; declared 6 m after it (inside the 10 m timing
        // tolerance) the navigator passes later than the bearing selector's near-vertex zone ends.
        for (guideM in listOf(20, 26)) for (accuracy in listOf(10f, 15f)) {
            val navigator = RouteNavigator(allowDegradedRouteGuidance = false)
                .apply { setRoute(cornerRoute(guideDistanceM = guideM), origin = null) }
            val walk = listOf(10.0 to 0.0, 18.0 to 0.0, 20.0 to 0.0) + (1..14).map { 20.0 to -3.0 * it }
            val facing = listOf(90.0, 90.0, 180.0) + List(14) { 180.0 }
            val updates = walk.mapIndexed { index, (east, north) ->
                val at = 1_000L + index * 1_000L
                val update = navigator.update(fix(east, north, at, accuracy), at, false,
                    facingObservation = RouteFacingObservation(facing[index], 5.0, at))
                update.instruction?.let { navigator.reserveInstruction(update); navigator.acknowledgeInstruction(update, at) }
                update
            }
            updates.forEachIndexed { index, update ->
                println("S08-18-F2 guide=$guideM acc=$accuracy fix#$index ${walk[index]} guide=${update.guideIndex} " +
                    "reason=${update.routeAlignmentDiagnostic?.reason} " +
                    "bearing=${update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt()} instr=${update.instruction}")
            }
            walk.forEachIndexed { index, (east, north) ->
                val update = updates[index]
                val label = "guide=$guideM acc=$accuracy fix#$index ($east,$north) ${update.routeAlignmentDiagnostic} '${update.instruction}'"
                val pastVertexM = if (east >= 20.0) -north else 0.0
                val reason = update.routeAlignmentDiagnostic?.reason
                // The hold never extends beyond the passage margin past the vertex while only
                // passage is awaited (the turn sentence lasts until progress reaches the guide).
                if (reason == RouteAlignmentReason.TURN_PASSAGE_PENDING) {
                    val turnStillSpoken = 20.0 + pastVertexM <= guideM
                    assertTrue(label, pastVertexM <= accuracy || turnStillSpoken)
                }
                if (pastVertexM > accuracy && 20.0 + pastVertexM > guideM) {
                    assertEquals(label, 180, update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt())
                }
                // The outgoing bearing is never compared beside the unpassed turn sentence, and from
                // the vertex on no facing claim or clock is paired with it.
                val text = update.instruction.orEmpty()
                if (text.contains("꺾으세요")) {
                    assertNotEquals(label, 180, update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt())
                }
                if (east >= 20.0) {
                    assertFalse(label, text.contains("앞쪽이 경로 진행 방향") && text.contains("꺾으세요"))
                    assertFalse(label, text.contains("시 방향") && text.contains("꺾으세요"))
                }
                assertFalse(label, text.contains("다시 안내를 요청"))
            }
            val vertex = updates[2]
            assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
            assertTrue("${vertex.instruction}", vertex.instruction.orEmpty().startsWith("안내 지점 부근입니다. 경로 기준으로 "))
            assertTrue("${vertex.instruction}", vertex.instruction.orEmpty().contains("오른쪽으로 꺾으세요"))
            // The walk ends past the guide and its margin: passage is confirmed and the next leg guides.
            assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, updates.last().routeAlignmentDiagnostic?.reason)
            assertEquals(180, updates.last().routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt())
        }
    }

    @Test
    fun aVertexThatKeepsTheSameDirectionIsNotHeld() {
        // 20 m east, then 80 m at 100 degrees: a 10 degree bend within the same-direction limit.
        val route = route(point(0.0), point(20.0), point(20.0 + 80.0 * 0.9848, -80.0 * 0.1736))
        val selection = select(route, point(20.0), accepted(0), PendingRouteGuide(20.0, 10.0))
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, selection.diagnostic.reason)
        assertEquals(1, selection.diagnostic.segmentIndex)
    }

    @Test
    fun theCornerKeepsTheIncomingLegThenHoldsAtTheVertexThenUsesTheOutgoingLegAfterPassage() {
        // S08-18-F3: moved from RouteNavigatorOrderedAlignmentTest.targetStops..., whose earlier
        // clock assertion fails in the baseline, so these lines never ran there. Production mode;
        // each phase gets two facing observations because a clock sector needs two to confirm.
        val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply { setRoute(cornerRoute(), origin = null) }
        fun step(east: Double, north: Double, at: Long, heading: Double): RouteNavigatorUpdate =
            navigator.update(fix(east, north, at), at, false, facingObservation = RouteFacingObservation(heading, 5.0, at))
                .also { update -> update.instruction?.let { navigator.reserveInstruction(update); navigator.acknowledgeInstruction(update, at) } }
        step(16.0, 0.0, 1_000, 90.0)
        val before = navigator.currentGuidance(1_500, RouteFacingObservation(90.0, 5.0, 1_500))
            ?.also { navigator.reserveInstruction(it); navigator.acknowledgeInstruction(it, 1_500) }
        assertTrue("$before", before?.instruction?.contains("약 12시 방향") == true)
        assertEquals(0, before?.routeAlignmentDiagnostic?.segmentIndex)
        val vertex = step(20.0, 0.0, 2_000, 180.0)
        assertFalse("$vertex", vertex.instruction?.contains("시 방향") == true)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        assertEquals("안내 지점 부근입니다. 경로 기준으로 오른쪽으로 꺾으세요.", vertex.instruction)
        val passed = step(20.0, -6.0, 3_000, 180.0)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, passed.routeAlignmentDiagnostic?.reason)
        assertEquals(1, passed.routeAlignmentDiagnostic?.segmentIndex)
        val confirmed = navigator.currentGuidance(3_500, RouteFacingObservation(180.0, 5.0, 3_500))
            ?.also { navigator.reserveInstruction(it); navigator.acknowledgeInstruction(it, 3_500) }
        assertTrue("$confirmed", confirmed?.instruction?.contains("약 12시 방향") == true)
        assertEquals(1, confirmed?.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test
    fun navigatorNeverPairsTheOutgoingBearingWithTheUnpassedTurnAndGpsWobbleSwitchesOnce() {
        val navigator = RouteNavigator(allowDegradedRouteGuidance = true).apply { setRoute(cornerRoute(), origin = null) }
        // Approach, vertex, just around, back before, around again, then clearly past the margin.
        val fixes = listOf(10.0 to 0.0, 20.0 to 0.0, 20.0 to -1.0, 19.0 to 0.0, 20.0 to -1.0, 20.0 to -6.0, 20.0 to -9.0)
        val facingSouthAfterTurn = listOf(90.0, 180.0, 180.0, 90.0, 180.0, 180.0, 180.0)
        val updates = fixes.mapIndexed { index, (east, north) ->
            val at = 1_000L + index * 1_000L
            val update = navigator.update(fix(east, north, at), at, false,
                facingObservation = RouteFacingObservation(facingSouthAfterTurn[index], 5.0, at))
            update.instruction?.let { navigator.reserveInstruction(update); navigator.acknowledgeInstruction(update, at) }
            update
        }
        val bearings = updates.map { it.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt() }
        println("S08-18 bearings=$bearings reasons=${updates.map { it.routeAlignmentDiagnostic?.reason }}")
        println("S08-18 instructions=${updates.map { it.instruction }}")
        // Unpassed guide (fixes 0..4): the outgoing leg's bearing is never the compared bearing.
        updates.take(5).forEachIndexed { index, update ->
            assertNotEquals("fix $index ${update.routeAlignmentDiagnostic}", 180,
                update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt())
            // From the vertex on, the user facing the outgoing leg must not hear "ahead ... turn right".
            val text = update.instruction.orEmpty()
            if (index >= 1) assertFalse("fix $index: '$text'", text.contains("앞쪽이 경로 진행 방향") && text.contains("꺾으세요"))
        }
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, updates[1].routeAlignmentDiagnostic?.reason)
        // After passage the outgoing leg is used.
        assertEquals(180, bearings[5])
        assertEquals(180, bearings[6])
        val switches = bearings.filterNotNull().zipWithNext().count { (a, b) -> a != b }
        assertTrue("bearings=$bearings", switches <= 1)
    }

    private fun select(
        route: WalkingRoute,
        at: RoutePoint,
        match: (RoutePoint) -> RouteCorridorMatchResult,
        pending: PendingRouteGuide?,
    ): RouteAlignmentSelection = RouteAlignmentSelector().select(
        route, FilteredRoutePosition(at, 3.0, 1_000L), match(at), 1_000L, 35.0, pending,
    )

    private fun accepted(segmentIndex: Int): (RoutePoint) -> RouteCorridorMatchResult = { at ->
        RouteCorridorMatchResult(
            "route", at, at, segmentIndex, null, 0.0, null, null, 0.95, RouteMatchQuality.HIGH, RouteMatchReason.MATCHED,
        )
    }

    private fun assertBearing(expected: Double, selection: RouteAlignmentSelection) {
        val bearing = requireNotNull(selection.bearingDegrees) { "${selection.diagnostic}" }
        assertTrue("bearing=$bearing", abs((bearing - expected + 540.0) % 360.0 - 180.0) < 1.0)
    }

    private fun cornerRoute(guideDistanceM: Int = 20) =
        route(point(0.0), point(20.0), point(20.0, -80.0)).copy(guidePoints = listOf(
            WalkingRouteGuidePoint(0, point(20.0), null, guideDistanceM, 100 - guideDistanceM, turnType = 13),
        ))

    // A third leg back west: two legs past the held vertex, in a direction it does not share.
    private fun threeLegRoute() = route(point(0.0), point(20.0), point(20.0, -80.0), point(-20.0, -80.0))

    private fun route(vararg points: RoutePoint): WalkingRoute {
        val distanceM = points.toList().zipWithNext()
            .sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }.roundToInt()
        return WalkingRoute("TEST", WalkingRouteSummary(distanceM, distanceM), points.toList(), emptyList())
    }

    private fun point(east: Double, north: Double = 0.0) =
        RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)

    private fun fix(east: Double, north: Double, time: Long, accuracy: Float = 3f): TrustedLocation =
        point(east, north).let { TrustedLocation(it.latitude, it.longitude, accuracy, time) }
}
