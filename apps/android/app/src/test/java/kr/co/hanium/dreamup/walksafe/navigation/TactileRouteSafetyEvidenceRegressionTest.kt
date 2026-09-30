package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** S12: eligible observation fixtures deliberately supply no block axis or user-motion proof. */
class TactileRouteSafetyEvidenceRegressionTest {
    private val config = TactileRoutePolicyConfig()
    private val policy = TactileRoutePolicy(config)

    @Test fun cameraAndRouteAlignmentDoesNotProveTheBlockAxis() {
        val observation = observation().copy(routeHeadingDeltaDeg = 0f)
        assertSame(observation, policy.selectCandidate(listOf(observation), "active"))
        val decision = policy.evaluate(input(observation))
        assertNoMovement(decision)
        assertEquals("tactile_motion_evidence_unavailable", decision.reason)
    }

    @Test fun imageSideChangesCannotSupplyAUserRelativeMovementAxis() {
        for (x in listOf(0.1f, 0.5f, 0.9f)) {
            val observation = observation().copy(centerXNormalized = x)
            assertSame(observation, policy.selectCandidate(listOf(observation), "active"))
            assertNoMovement(policy.evaluate(input(observation)))
        }
    }

    @Test fun invalidHighConfidenceCandidateCannotHideAnEligibleObservation() {
        val valid = observation("valid")
        val high = observation("invalid").copy(confidence = 0.99f)
        val invalid = listOf(
            high.copy(stableFrames = config.minimumStableFrames - 1),
            high.copy(stableMs = config.minimumStableMs - 1),
            high.copy(ageMs = config.maximumDetectionAgeMs + 1),
            high.copy(ageMs = -1),
            high.copy(confidence = Float.NaN),
            high.copy(routeHeadingDeltaDeg = null),
            high.copy(routeHeadingDeltaDeg = config.maximumRouteHeadingDeltaDeg + 1f),
            high.copy(centerXNormalized = Float.NaN),
            high.copy(tmapCorridorProjection = TmapCorridorProjectionEvidence.UNAVAILABLE),
        )
        for (candidate in invalid) {
            for (candidates in listOf(listOf(candidate, valid), listOf(valid, candidate))) {
                val selected = policy.selectCandidate(candidates, "active")
                assertSame(valid, selected)
                assertNoMovement(policy.evaluate(input(selected)))
            }
        }
    }

    @Test fun anotherRoutesHighConfidenceCandidateCannotHideTheActiveRouteObservation() {
        val wrongRoute = observation("wrong").copy(routeId = "previous", confidence = 0.99f)
        val valid = observation("valid")
        assertEquals("tactile_route_identity_mismatch", policy.evaluate(input(wrongRoute)).reason)
        assertSame(valid, policy.selectCandidate(listOf(wrongRoute, valid), "active"))
        assertNoMovement(policy.evaluate(input(valid)))
    }

    @Test fun blockersKeepTheirPriorityWithoutNormalCandidateEligibilityGates() {
        val normal = observation("normal")
        for (className in listOf("damaged_tactile_block", "dot_tactile_paving")) {
            val blocker = observation("blocker").copy(className = className,
                confidence = 0.1f, stableFrames = 0, stableMs = 0, ageMs = config.maximumDetectionAgeMs + 1)
            assertSame(blocker, policy.selectCandidate(listOf(normal, blocker), "active"))
            assertEquals("tactile_not_traversable", policy.evaluate(input(blocker)).reason)
            assertNoMovement(policy.evaluate(input(blocker)))
        }
    }

    @Test fun outsideBlockerDoesNotHideAnEligibleCorridorObservationOrAuthorizeMovement() {
        val normal = observation("normal")
        val outside = observation("outside").copy(className = "damaged_tactile_block",
            tmapCorridorProjection = TmapCorridorProjectionEvidence.OUTSIDE)
        assertSame(normal, policy.selectCandidate(listOf(outside, normal), "active"))
        assertNoMovement(policy.evaluate(input(normal)))
    }

    @Test fun existingGlobalGatesRemainIndependentOfObservationRanking() {
        val input = input(observation())
        assertEquals("navigation_inactive", policy.evaluate(input.copy(navigationActive = false)).reason)
        assertEquals("tmap_off_route", policy.evaluate(input.copy(tmapOnRoute = false)).reason)
        assertEquals("gps_untrusted", policy.evaluate(input.copy(gpsAccuracyM = null)).reason)
        assertEquals("gps_untrusted", policy.evaluate(input.copy(gpsAccuracyM = config.maximumGpsAccuracyM + 1f)).reason)
    }

    @Test fun allExistingNumericPolicyValuesArePreserved() {
        assertEquals(TactileRoutePolicyConfig(
            minimumConfidence = 0.55f, minimumStableFrames = 3, minimumStableMs = 700L,
            maximumDetectionAgeMs = 1_200L, maximumRouteHeadingDeltaDeg = 35f,
            maximumGpsAccuracyM = 25f, steeringDeadZone = 0.15f,
        ), config)
    }

    private fun observation(id: String = "normal") = TactileRouteObservation(
        candidateId = id, className = "normal_tactile_block", confidence = 0.9f,
        stableFrames = 4, stableMs = 900L, ageMs = 100L, routeHeadingDeltaDeg = 0f,
        tmapCorridorProjection = TmapCorridorProjectionEvidence.OVERLAPS,
        centerXNormalized = 0.5f, routeId = "active", routeSegmentIndex = 0,
    )

    private fun input(observation: TactileRouteObservation?) = TactileRoutePolicyInput(
        navigationActive = true, tmapOnRoute = true, gpsAccuracyM = 2f,
        tactile = observation, activeRouteId = "active",
    )

    private fun assertNoMovement(decision: TactileRouteDecision) {
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertNull(decision.instruction)
    }
}
