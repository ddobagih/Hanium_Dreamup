package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TactileRoutePolicyTest {
    private val policy = TactileRoutePolicy()

    @Test
    fun followsSharedTactileRouteContractCases() {
        val root = JSONObject(sharedFixture().readText())
        assertEquals("walksafe.tactile_route_policy_cases.v1", root.getString("schema_version"))
        val thresholds = root.getJSONObject("thresholds")
        val config = TactileRoutePolicyConfig()
        assertEquals(thresholds.getDouble("minimum_confidence").toFloat(), config.minimumConfidence)
        assertEquals(thresholds.getInt("minimum_stable_frames"), config.minimumStableFrames)
        assertEquals(thresholds.getLong("minimum_stable_ms"), config.minimumStableMs)
        assertEquals(thresholds.getLong("maximum_detection_age_ms"), config.maximumDetectionAgeMs)
        assertEquals(thresholds.getDouble("maximum_route_heading_delta_deg").toFloat(), config.maximumRouteHeadingDeltaDeg)
        assertEquals(thresholds.getDouble("maximum_gps_accuracy_m").toFloat(), config.maximumGpsAccuracyM)
        assertEquals(thresholds.getDouble("steering_dead_zone").toFloat(), config.steeringDeadZone)
        val cases = root.getJSONArray("cases")
        // Keep the shared v1 fixture normative for its other consumers. These exact three
        // legacy permissions are an explicit Android safety-contract divergence, not a
        // rewritten fixture or a blanket exemption for future tactile_local cases.
        val legacyMovementCases = mapOf(
            "stable-aligned-center-uses-tactile" to ("straight" to 0.5f),
            "stable-aligned-left-steers-left" to ("left" to 0.25f),
            "stable-aligned-right-steers-right" to ("right" to 0.75f),
        )
        val comparedLegacyCases = mutableSetOf<String>()

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val tactile = case.optJSONObject("tactile")?.let { item ->
                TactileRouteObservation(
                    className = item.getString("class_name"),
                    confidence = item.getDouble("confidence").toFloat(),
                    stableFrames = item.getInt("stable_frames"),
                    stableMs = item.getLong("stable_ms"),
                    ageMs = item.getLong("age_ms"),
                    routeHeadingDeltaDeg = item.getDouble("route_heading_delta_deg").toFloat(),
                    tmapCorridorProjection = if (item.getBoolean("overlaps_tmap_corridor")) {
                        TmapCorridorProjectionEvidence.OVERLAPS
                    } else {
                        TmapCorridorProjectionEvidence.OUTSIDE
                    },
                    centerXNormalized = item.getDouble("center_x_normalized").toFloat(),
                    // v1 has no route-context fields; the Android adapter supplies the required
                    // identity without changing the shared fixture or its other consumers.
                    routeId = ACTIVE_ROUTE_ID,
                )
            }
            val decision = policy.evaluate(
                TactileRoutePolicyInput(
                    navigationActive = case.getBoolean("navigation_active"),
                    tmapOnRoute = case.getBoolean("tmap_on_route"),
                    gpsAccuracyM = case.getDouble("gps_accuracy_m").toFloat(),
                    tactile = tactile,
                    activeRouteId = ACTIVE_ROUTE_ID,
                ),
            )
            val expected = case.getJSONObject("expected")
            val caseId = case.getString("id")
            if (expected.getString("mode") == "tactile_local") {
                val legacy = requireNotNull(legacyMovementCases[caseId]) { caseId }
                assertEquals(caseId, legacy.first, expected.getString("steering"))
                assertEquals(caseId, "stable_aligned_tactile", expected.getString("reason"))
                val observation = requireNotNull(tactile)
                assertEquals(legacy.second, observation.centerXNormalized, 0f)
                assertTrue(caseId, case.getBoolean("navigation_active"))
                assertTrue(caseId, case.getBoolean("tmap_on_route"))
                assertEquals(TmapCorridorProjectionEvidence.OVERLAPS, observation.tmapCorridorProjection)
                assertSame(caseId, observation, policy.selectCandidate(listOf(observation), ACTIVE_ROUTE_ID))
                // v1 records camera heading, image side and corridor association, not block
                // axis, user axis or contact-pixel depth. Independent production counterexamples:
                // AndroidTactileRouteSafetyRegressionTest.cameraAlignedWithTheRouteStillCannotAuthorizeFollowingTheBlock,
                // rotatingOnlyThePhoneChangesImageSideButNeverGeneratesSteering, and
                // matchingRepresentativeDepthValuesDoNotProveDepthAtTheContactPixel.
                assertEquals(caseId, LocalRouteMode.TMAP, decision.mode)
                assertEquals(caseId, "tactile_motion_evidence_unavailable", decision.reason)
                assertNull(caseId, decision.steering)
                assertNull(caseId, decision.instruction)
                comparedLegacyCases += caseId
                continue
            }
            assertEquals(case.getString("id"), expected.getString("mode"), decision.mode.wireValue)
            if (expected.isNull("steering")) {
                assertNull(case.getString("id"), decision.steering?.wireValue)
            } else {
                assertEquals(case.getString("id"), expected.getString("steering"), decision.steering?.wireValue)
            }
            assertEquals(case.getString("id"), expected.getString("reason"), decision.reason)
            assertNull(caseId, decision.instruction)
        }
        assertEquals(legacyMovementCases.keys, comparedLegacyCases)
    }

    @Test
    fun rejectedObservationRetainsItsReasonWithoutDisplacingAnEligibleCandidate() {
        val valid = stableObservation("valid", "normal_tactile_block", centerX = 0.5f, inCorridor = true)
            .copy(routeId = "active-route")
        val config = TactileRoutePolicyConfig()
        val rejectedCases = listOf(
            "route_heading_mismatch" to valid.copy(routeHeadingDeltaDeg = 90f),
            "route_heading_mismatch" to valid.copy(routeHeadingDeltaDeg = null),
            "tactile_route_identity_mismatch" to valid.copy(routeId = "previous-route"),
            "tactile_confidence_low" to valid.copy(confidence = 0.1f),
            "tactile_not_stable" to valid.copy(stableFrames = 1),
            "tactile_stale" to valid.copy(ageMs = config.maximumDetectionAgeMs + 1L),
            "tactile_geometry_invalid" to valid.copy(centerXNormalized = 1.2f),
            "tactile_outside_tmap_corridor" to valid.copy(tmapCorridorProjection = TmapCorridorProjectionEvidence.OUTSIDE),
            "tmap_corridor_projection_unavailable" to valid.copy(tmapCorridorProjection = TmapCorridorProjectionEvidence.UNAVAILABLE),
        )
        for ((reason, template) in rejectedCases) {
            val rejected = template.copy(candidateId = "rejected", confidence = if (reason == "tactile_confidence_low") 0.1f else 0.99f)
            val selected = policy.selectCandidate(listOf(rejected), "active-route")
            assertSame(reason, rejected, selected)
            val diagnostic = policy.evaluate(baseInput(selected).copy(activeRouteId = "active-route"))
            assertEquals(reason, diagnostic.reason)
            assertEquals(LocalRouteMode.TMAP, diagnostic.mode)
            assertNull(diagnostic.steering)
            assertNull(diagnostic.instruction)

            for (candidates in listOf(listOf(rejected, valid), listOf(valid, rejected))) {
                val eligible = policy.selectCandidate(candidates, "active-route")
                assertSame(reason, valid, eligible)
                val decision = policy.evaluate(baseInput(eligible).copy(activeRouteId = "active-route"))
                assertEquals("tactile_motion_evidence_unavailable", decision.reason)
                assertEquals(LocalRouteMode.TMAP, decision.mode)
                assertNull(decision.steering)
                assertNull(decision.instruction)
            }
        }
    }

    @Test
    fun noObservationStillReportsNotVisibleRatherThanARejection() {
        val selected = policy.selectCandidate(emptyList(), ACTIVE_ROUTE_ID)
        val decision = policy.evaluate(baseInput(selected))
        assertNull(selected)
        assertEquals("tactile_not_visible", decision.reason)
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertNull(decision.instruction)
    }

    @Test
    fun headingDeltaHandlesWrapAroundAndUnknownValues() {
        assertEquals(20f, routeHeadingDeltaDegrees(350f, 10f)!!, 0.001f)
        assertNull(routeHeadingDeltaDegrees(null, 10f))
    }

    @Test
    fun damagedBlockInTheLocalCorridorPreventsNormalTactileOverride() {
        val normal = stableObservation("normal", "normal_tactile_block", centerX = 0.5f, inCorridor = true)
        val damaged = stableObservation("damaged", "damaged_tactile_block", centerX = 0.55f, inCorridor = true)
        val selected = policy.selectCandidate(listOf(normal, damaged), ACTIVE_ROUTE_ID)

        val decision = policy.evaluate(baseInput(tactile = selected))

        assertEquals("damaged", selected?.candidateId)
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertEquals("tactile_not_traversable", decision.reason)
    }

    @Test
    fun damageOutsideVisualCorridorDoesNotHideStableNormalCorridor() {
        val normal = stableObservation("normal", "normal_tactile_block", centerX = 0.5f, inCorridor = true)
        val damaged = stableObservation("damaged", "damaged_tactile_block", centerX = 0.05f, inCorridor = false)
        val selected = policy.selectCandidate(listOf(damaged, normal), ACTIVE_ROUTE_ID)

        val decision = policy.evaluate(baseInput(tactile = selected))

        assertEquals("normal", selected?.candidateId)
        assertSame(normal, selected)
        assertEquals(TmapCorridorProjectionEvidence.OVERLAPS, selected?.tmapCorridorProjection)
        assertEquals(0.5f, requireNotNull(selected).centerXNormalized, 0f)
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertEquals("tactile_motion_evidence_unavailable", decision.reason)
        assertNull(decision.steering)
        assertNull(decision.instruction)
    }

    @Test
    fun missingProjectionEvidenceKeepsTmapEvenForStableCenteredNormalBlock() {
        val observation = stableObservation(
            id = "normal",
            className = "normal_tactile_block",
            centerX = 0.5f,
            projection = TmapCorridorProjectionEvidence.UNAVAILABLE,
        )

        val decision = policy.evaluate(baseInput(tactile = observation))

        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertEquals("tmap_corridor_projection_unavailable", decision.reason)
    }

    @Test
    fun projectionEvidenceFromAnotherRouteFailsClosed() {
        val observation = stableObservation(
            id = "normal",
            className = "normal_tactile_block",
            centerX = 0.5f,
            projection = TmapCorridorProjectionEvidence.OVERLAPS,
        ).copy(routeId = "old-route", routeSegmentIndex = 0)

        val decision = policy.evaluate(
            baseInput(tactile = observation).copy(activeRouteId = "current-route"),
        )

        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertEquals("tactile_route_identity_mismatch", decision.reason)
    }

    @Test
    fun mainActivityRequiresFineLocationForTmapRouting() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val routePermission = source.substringAfter("private fun isRouteLocationPermissionReady()")
            .substringBefore("private fun hasCameraPermission")

        assertTrue(routePermission.contains("hasLocationPermission()"))
        assertFalse(routePermission.contains("hasActivityRecognitionPermission()"))
        assertTrue(source.contains("approximate-only is insufficient"))
        val locationPermission = source.substringAfter("private fun hasLocationPermission(): Boolean")
            .substringBefore("private fun hasActivityRecognitionPermission")
        assertTrue(locationPermission.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertFalse(locationPermission.contains("Manifest.permission.ACCESS_COARSE_LOCATION"))
        val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
        val guidance = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        assertTrue(source.contains("routeAcquisitionController."))
        assertTrue(source.contains("routeGuidanceController."))
        assertTrue(acquisition.contains("routeNavigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE"))
        assertTrue(acquisition.contains("routeNavigator.rerouteRequestFailed()"))
        assertTrue(acquisition.contains("existing_route_retained"))
        assertTrue(guidance.contains("!scope.requestPending &&"))
        assertTrue(guidance.contains("update.reason != \"route_missing\""))
        assertTrue(acquisition.contains("!routeNavigator.hasRoute()"))
        assertTrue(acquisition.contains("navigation=route_waiting trusted_gps_missing"))
    }

    @Test
    fun cameraDirectionComesFromTheRotationSensorAndNotFromLocationHeading() {
        val guidance = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidance.kt",
        ).readText()

        assertTrue(guidance.contains("val cameraBearingTrue = normalizeBearing(cameraBearingMagnetic + declinationDeg)"))
        assertTrue(guidance.contains("routeHeadingDeltaDegrees(cameraBearingTrue, route.bearingDeg)"))
        assertFalse(guidance.contains("routeHeadingDeltaDegrees(latestHeadingDeg"))
        assertFalse(guidance.contains("cameraBearingTrue = latestHeadingDeg"))
    }

    private fun stableObservation(
        id: String,
        className: String,
        centerX: Float,
        inCorridor: Boolean,
    ): TactileRouteObservation = stableObservation(
        id = id,
        className = className,
        centerX = centerX,
        projection = if (inCorridor) {
            TmapCorridorProjectionEvidence.OVERLAPS
        } else {
            TmapCorridorProjectionEvidence.OUTSIDE
        },
    )

    private fun stableObservation(
        id: String,
        className: String,
        centerX: Float,
        projection: TmapCorridorProjectionEvidence,
    ): TactileRouteObservation {
        return TactileRouteObservation(
            candidateId = id,
            className = className,
            confidence = 0.9f,
            stableFrames = 4,
            stableMs = 900L,
            ageMs = 100L,
            routeHeadingDeltaDeg = 5f,
            tmapCorridorProjection = projection,
            centerXNormalized = centerX,
            routeId = ACTIVE_ROUTE_ID,
        )
    }

    private fun baseInput(tactile: TactileRouteObservation?): TactileRoutePolicyInput {
        return TactileRoutePolicyInput(
            navigationActive = true,
            tmapOnRoute = true,
            gpsAccuracyM = 5f,
            tactile = tactile,
            activeRouteId = ACTIVE_ROUTE_ID,
        )
    }

    private fun sharedFixture(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory = File(userDirectory).canonicalFile
        repeat(8) {
            val fixture = File(directory, "tests/fixtures/navigation/tactile_route_policy_cases.json")
            if (fixture.isFile) return fixture
            directory = directory.parentFile ?: return@repeat
        }
        error("shared tactile route policy fixture not found from $userDirectory")
    }

    private companion object {
        const val ACTIVE_ROUTE_ID = "active-route"
    }
}
