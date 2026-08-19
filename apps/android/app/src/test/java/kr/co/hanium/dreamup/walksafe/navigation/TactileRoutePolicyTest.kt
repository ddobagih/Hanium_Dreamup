package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                )
            }
            val decision = policy.evaluate(
                TactileRoutePolicyInput(
                    navigationActive = case.getBoolean("navigation_active"),
                    tmapOnRoute = case.getBoolean("tmap_on_route"),
                    gpsAccuracyM = case.getDouble("gps_accuracy_m").toFloat(),
                    tactile = tactile,
                ),
            )
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("id"), expected.getString("mode"), decision.mode.wireValue)
            if (expected.isNull("steering")) {
                assertNull(case.getString("id"), decision.steering?.wireValue)
            } else {
                assertEquals(case.getString("id"), expected.getString("steering"), decision.steering?.wireValue)
            }
            assertEquals(case.getString("id"), expected.getString("reason"), decision.reason)
        }
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
        val selected = policy.selectCandidate(listOf(normal, damaged))

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
        val selected = policy.selectCandidate(listOf(damaged, normal))

        val decision = policy.evaluate(baseInput(tactile = selected))

        assertEquals("normal", selected?.candidateId)
        assertEquals(LocalRouteMode.TACTILE_LOCAL, decision.mode)
        assertEquals(TactileSteering.STRAIGHT, decision.steering)
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
        assertTrue(source.contains("routeNavigator.pendingUserDecision() != RouteNavigatorUserDecision.OFF_ROUTE_CHOICE"))
        assertTrue(source.contains("routeNavigator.rerouteRequestFailed()"))
        assertTrue(source.contains("existing_route_retained"))
        assertTrue(source.contains("!routeRequestInFlight.get() &&"))
        assertTrue(source.contains("update.reason != \"route_missing\""))
        assertTrue(source.contains("!routeNavigator.hasRoute()"))
        assertTrue(source.contains("navigation=route_waiting trusted_gps_missing"))
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
        )
    }

    private fun baseInput(tactile: TactileRouteObservation?): TactileRoutePolicyInput {
        return TactileRoutePolicyInput(
            navigationActive = true,
            tmapOnRoute = true,
            gpsAccuracyM = 5f,
            tactile = tactile,
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
}
