package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-12: a matching route ID is not enough; version and the current guidance segment must match. */
class TactileRouteProgressPolicyTest {
    private val policy = TactileRoutePolicy()

    @Test
    fun sameRouteIdWithADifferentRouteVersionIsRejected() {
        val decision = policy.evaluate(input(observation(version = 7L, segment = 2), progress(version = 8L, segment = 2)))
        assertEquals("tactile_route_version_mismatch", decision.reason)
        assertNoMovement(decision)
        assertEquals("tactile_route_version_mismatch", policy.evaluate(input(observation(version = null, segment = 2), progress(8L, 2))).reason)
    }

    @Test
    fun currentAndNextSegmentAreContinuousButPreviousAndSkippedAreNot() {
        assertEquals("tactile_motion_evidence_unavailable", policy.evaluate(input(observation(segment = 3), progress(segment = 3))).reason)
        assertEquals("tactile_motion_evidence_unavailable", policy.evaluate(input(observation(segment = 4), progress(segment = 3))).reason)
        assertEquals("tactile_segment_mismatch", policy.evaluate(input(observation(segment = 2), progress(segment = 3))).reason)
        assertEquals("tactile_segment_mismatch", policy.evaluate(input(observation(segment = 5), progress(segment = 3))).reason)
        assertEquals("tactile_segment_mismatch", policy.evaluate(input(observation(segment = null), progress(segment = 3))).reason)
    }

    @Test
    fun observationFromTheSegmentBeforeABranchPointIsRejectedOnceTheUserPassedIt() {
        val capturedBeforeBranch = observation(segment = 4)
        assertEquals("tactile_motion_evidence_unavailable", policy.evaluate(input(capturedBeforeBranch, progress(segment = 4))).reason)
        assertEquals("tactile_segment_mismatch", policy.evaluate(input(capturedBeforeBranch, progress(segment = 5))).reason)
    }

    @Test
    fun unknownCurrentProgressFailsClosed() {
        for (unknown in listOf(progress(version = null, segment = 3), progress(version = 7L, segment = null), progress(7L, -1))) {
            val decision = policy.evaluate(input(observation(segment = 3), unknown))
            assertEquals(unknown.toString(), "tactile_route_progress_unavailable", decision.reason)
            assertNoMovement(decision)
        }
    }

    @Test
    fun identityMismatchStillWinsAndNoProgressContractKeepsTheExistingDecision() {
        val otherRoute = observation(segment = 3).copy(routeId = "previous")
        assertEquals("tactile_route_identity_mismatch", policy.evaluate(input(otherRoute, progress(segment = 3))).reason)
        assertEquals("tactile_motion_evidence_unavailable", policy.evaluate(input(observation(version = null, segment = 9), null)).reason)
    }

    @Test
    fun rankingUsesProgressEligibilityBeforeConfidence() {
        val previousSegment = observation(id = "previous", segment = 2).copy(confidence = 0.99f)
        val current = observation(id = "current", segment = 3)
        assertSame(current, policy.selectCandidate(listOf(previousSegment, current), "active", progress(segment = 3)))
        // Without the progress contract the higher-confidence block is still chosen (unchanged behavior).
        assertSame(previousSegment, policy.selectCandidate(listOf(previousSegment, current), "active"))
    }

    @Test
    fun productionGuidanceBindsCaptureRevisionAndPassesEvaluationProgress() {
        val guidance = source("navigation/AndroidTactileRouteGuidance.kt")
        val observation = guidance.substringAfter("observation = TactileRouteObservation(").substringBefore("rejection = null")
        assertTrue(observation.contains("routeSegmentIndex = route.segmentIndex"))
        assertTrue(observation.contains("routeVersion = route.routeRevision"))
        assertTrue(guidance.contains("policy.selectCandidate(observations, activeRouteId, activeRouteProgress)"))
        val navigator = source("navigation/RouteNavigator.kt")
        val projection = navigator.substringAfter("fun currentProjection(location: TrustedLocation): ActiveRouteProjection?")
            .substringBefore("fun currentInstruction(")
        assertTrue(projection.contains("routeRevision = routeRevision"))
        val coordinator = source("navigation/AndroidTactileFrameCoordinator.kt")
        assertEquals(3, Regex("activeRouteProgress = (input\\.)?activeRouteProgress").findAll(coordinator).count())
        val activity = source("MainActivity.kt")
        assertEquals(2, Regex("activeRouteProgress = routeNavigator\\.currentTactileRouteProgress\\(\\)").findAll(activity).count())
    }

    @Test
    fun navigatorProgressUsesOnlyAMatchForTheCurrentRouteRevision() {
        val navigator = RouteNavigator()
        assertEquals(TactileActiveRouteProgress(null, null), navigator.currentTactileRouteProgress())
        navigator.setRoute(straightRoute())
        val installed = navigator.currentTactileRouteProgress()
        assertEquals(1L, installed.routeVersion)
        assertNull(installed.segmentIndex)
        navigator.update(TrustedLocation(37.0, 127.0, 2f, elapsedRealtimeMs = 1_000L), nowMs = 1_000L, requestInFlight = false)
        val matched = navigator.currentTactileRouteProgress()
        assertEquals(1L, matched.routeVersion)
        assertEquals(0, matched.segmentIndex)
        val projection = requireNotNull(navigator.currentProjection(TrustedLocation(37.0, 127.0, 2f, elapsedRealtimeMs = 1_000L)))
        assertEquals(matched.routeVersion, projection.routeRevision)
        // Re-installing the route (same ID) is a new version and drops the old match.
        navigator.setRoute(straightRoute())
        assertEquals(TactileActiveRouteProgress(2L, null), navigator.currentTactileRouteProgress())
        assertEquals(
            "tactile_route_version_mismatch",
            policy.evaluate(input(observation(version = projection.routeRevision, segment = 0), TactileActiveRouteProgress(2L, 0))).reason,
        )
    }

    private fun straightRoute() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 230, durationS = 190),
        polyline = listOf(RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), RoutePoint(37.002, 127.0)),
        guidePoints = emptyList(),
        providerRouteId = "route-1",
    )

    private fun observation(id: String = "normal", version: Long? = 7L, segment: Int?) = TactileRouteObservation(
        candidateId = id, className = "normal_tactile_block", confidence = 0.9f,
        stableFrames = 4, stableMs = 900L, ageMs = 100L, routeHeadingDeltaDeg = 0f,
        tmapCorridorProjection = TmapCorridorProjectionEvidence.OVERLAPS,
        centerXNormalized = 0.5f, routeId = "active", routeSegmentIndex = segment, routeVersion = version,
    )

    private fun progress(version: Long? = 7L, segment: Int?) = TactileActiveRouteProgress(version, segment)

    private fun input(observation: TactileRouteObservation?, progress: TactileActiveRouteProgress?) = TactileRoutePolicyInput(
        navigationActive = true, tmapOnRoute = true, gpsAccuracyM = 2f,
        tactile = observation, activeRouteId = "active", activeRouteProgress = progress,
    )

    private fun assertNoMovement(decision: TactileRouteDecision) {
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertNull(decision.instruction)
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
    ).first { it.isFile }.readText()
}
