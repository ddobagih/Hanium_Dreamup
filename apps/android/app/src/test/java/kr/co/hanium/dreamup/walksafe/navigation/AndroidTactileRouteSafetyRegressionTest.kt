package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kr.co.hanium.dreamup.walksafe.depth.ContactDepthEstimate
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.*
import org.junit.Test

/** S12 counterexamples to legacy local-steering fixtures, not measured walking-safety evidence. */
class AndroidTactileRouteSafetyRegressionTest {
    @Test fun cameraAlignedWithTheRouteStillCannotAuthorizeFollowingTheBlock() {
        val result = apply(listOf(output()))
        val observation = requireNotNull(result.observation)
        assertEquals(0f, requireNotNull(observation.routeHeadingDeltaDeg), 0.001f)
        assertEquals(TmapCorridorProjectionEvidence.OVERLAPS, observation.tmapCorridorProjection)
        assertEquals("tactile_motion_evidence_unavailable", result.decision.reason)
        assertNoMovement(result)
    }

    @Test fun rotatingOnlyThePhoneChangesImageSideButNeverGeneratesSteering() {
        // Same synthetic world target (north 2 m, down 0.8 m), same location/time; only camera yaw changes.
        val sides = mutableListOf<Float>()
        for (yaw in listOf(-20.0, 20.0)) {
            val radians = Math.toRadians(yaw)
            val depth = (2.0 * cos(radians)).toFloat()
            val contact = Point2((0.5 - 0.5 * tan(radians)).toFloat(), 0.5f + 0.4f / depth)
            val result = apply(listOf(output(contact = contact, depth = depth)), context(yaw))
            sides += requireNotNull(result.observation).centerXNormalized
            assertNoMovement(result)
        }
        val deadZone = TactileRoutePolicyConfig().steeringDeadZone
        assertTrue(sides.any { it < 0.5f - deadZone })
        assertTrue(sides.any { it > 0.5f + deadZone })
    }

    @Test fun matchingRepresentativeDepthValuesDoNotProveDepthAtTheContactPixel() {
        val representativeOnly = output().copy(contactDepth = null)
        assertEquals(representativeOnly.zDistanceM, representativeOnly.depthMedianM)
        assertNull(representativeOnly.groundDistanceM)
        val result = apply(listOf(representativeOnly))
        // S12-5: without contact-neighborhood depth the representative value is not projected.
        assertNull(result.observation)
        assertEquals("tactile_not_visible", result.decision.reason)
        assertNoMovement(result)
    }

    @Test fun unstableHighConfidenceCandidateDoesNotHideAStableObservationInProductionComposition() {
        val unstable = output("invalid").copy(detectionConfidence = 0.99f, trackAgeFrames = 1)
        val valid = output("valid")
        val result = apply(listOf(unstable, valid))
        assertEquals("valid", result.observation?.candidateId)
        assertNoMovement(result)
    }

    @Test fun activeRouteIdentityIsAppliedBeforeCandidateRanking() {
        val supplier = TactileRouteObservationSupplier { output, _ ->
            TactileRouteObservation(
                candidateId = output.trackId, className = output.className,
                confidence = output.detectionConfidence, stableFrames = 4, stableMs = 900,
                ageMs = 100, routeHeadingDeltaDeg = 0f,
                tmapCorridorProjection = TmapCorridorProjectionEvidence.OVERLAPS,
                centerXNormalized = 0.5f, routeId = if (output.trackId == "wrong") "previous" else "active",
                routeSegmentIndex = 0,
            )
        }
        val result = AndroidTactileRouteGuidance(supplier).apply(
            listOf(output("wrong").copy(detectionConfidence = 0.99f), output("valid")),
            context(), true, true, activeRouteId = "active",
        )
        assertEquals("valid", result.observation?.candidateId)
        assertNoMovement(result)
    }

    @Test fun unprojectedBlockerStillSuppressesTheNormalCandidate() {
        val blocker = output("blocker").copy(className = "damaged_tactile_block", frameId = FRAME_ID + 1)
        val result = apply(listOf(output("normal"), blocker))
        assertNull(result.observation)
        assertEquals("tactile_not_visible", result.decision.reason)
        assertNoMovement(result)
    }

    @Test fun failClosedTactileGuidancePreservesEveryNonTactileRiskOutputByIdentity() {
        val riskLevels = enumValues<MessageLevel>().filter { it != MessageLevel.NONE && it != MessageLevel.INFO }
        assertTrue(riskLevels.isNotEmpty())
        for (level in riskLevels) {
            val hazard = output("hazard").copy(className = "person",
                userFacing = UserFacingDepth(stepsAhead = 3, messageLevel = level, message = "existing risk warning"))
            val result = apply(listOf(output(), hazard))
            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.decision.steering)
            assertNull(result.decision.instruction)
            assertSame(hazard, result.outputs.last())
            assertSame(hazard.userFacing, result.outputs.last().userFacing)
        }
    }

    private fun apply(outputs: List<TrackedObjectDepth>, context: TactileProjectionContext = context()) =
        AndroidTactileRouteGuidance().apply(outputs, context, true, true, activeRouteId = "active")

    private fun assertNoMovement(result: TactileRouteGuidanceResult) {
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
        result.outputs.forEach {
            assertEquals(MessageLevel.NONE, it.userFacing.messageLevel)
            assertNull(it.userFacing.message)
        }
    }

    private fun context(yawDegrees: Double = 0.0): TactileProjectionContext {
        val radians = Math.toRadians(yawDegrees)
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return TactileProjectionContext(
            captureFrameId = FRAME_ID, expectedRouteId = "active",
            routeProjection = ActiveRouteProjection("active", 0,
                RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f),
            trustedLocation = TrustedLocation(37.0, 127.0, 2f, NOW_MS - 50),
            orientation = DeviceEarthOrientation(
                RotationMatrix3(c, 0f, -s, -s, 0f, -c, 0f, 1f, 0f),
                NOW_MS, 5f, EarthOrientationAccuracy.HIGH),
            magneticDeclinationDeg = 0f,
            cameraFrame = TactileCameraFrame(FRAME_ID,
                RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), NOW_MS, true),
            detectionAgeMs = 100L, nowElapsedRealtimeMs = NOW_MS,
        )
    }

    private fun output(id: String = "normal", contact: Point2 = Point2(0.5f, 0.7f), depth: Float = 2f) = TrackedObjectDepth(
        frameId = FRAME_ID, timestampMs = NOW_MS, trackId = id,
        className = "normal_tactile_block", detectionConfidence = 0.9f,
        bboxNorm = RectNorm(contact.x - 0.1f, 0.5f, 0.2f, 0.3f),
        polygonNorm = listOf(Point2(contact.x - 0.1f, 0.5f), Point2(contact.x + 0.1f, 0.5f),
            Point2(contact.x + 0.1f, 0.8f), Point2(contact.x - 0.1f, 0.8f)),
        maskAreaNorm = 0.06f, centerNorm = Point2(contact.x, 0.65f), bottomContactNorm = contact,
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = depth, rayDistanceM = null,
        groundDistanceM = null, riskDistanceM = depth, validSampleCount = 80, validSampleRatio = 0.6f,
        depthMedianM = depth, depthP20M = depth - 0.1f, depthIqrM = 0.2f,
        trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(sourceQuality = 0.95f, sampleQuality = 0.9f,
            depthQuality = 0.9f, detectionQuality = 0.9f, trackingQuality = 1f,
            motionQuality = 1f, freshnessQuality = 1f, corridorQuality = 1f, hardGate = 1f),
        userFacing = UserFacingDepth(stepsAhead = 3, messageLevel = MessageLevel.NONE, message = null),
        trackAgeFrames = 4, trackStableMs = 900L, sourceValidUntilElapsedRealtimeMs = NOW_MS + 400,
        // Same-frame contact-neighborhood depth (DepthSampler 5x5 window) at the synthetic target.
        contactDepth = ContactDepthEstimate(distanceM = depth, spreadM = 0.05f, validSampleCount = 25),
    )

    private companion object {
        const val FRAME_ID = 1L
        const val NOW_MS = 10_000L
    }
}
