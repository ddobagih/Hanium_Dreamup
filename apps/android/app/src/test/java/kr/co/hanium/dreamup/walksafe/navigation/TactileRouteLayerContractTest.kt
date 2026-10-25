package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.ContactDepthEstimate
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-27: observation eligibility and the production capture gates are separate contracts. */
class TactileRouteLayerContractTest {
    private val policy = TactileRoutePolicy()
    private val supplier = AndroidTactileRouteObservationSupplier()

    @Test
    fun policyRejectsTwoMissingRouteIdentifiers() {
        assertIdentityRejected(activeId = null, observedId = null)
    }

    @Test
    fun policyRejectsTwoEmptyRouteIdentifiers() {
        assertIdentityRejected(activeId = "", observedId = "")
    }

    @Test
    fun policyRejectsTwoWhitespaceRouteIdentifiers() {
        assertIdentityRejected(activeId = " \t", observedId = " \t")
    }

    @Test
    fun policyRejectsAMissingActiveRouteIdentifier() {
        assertIdentityRejected(activeId = null, observedId = ROUTE_ID)
    }

    @Test
    fun policyRejectsAMissingObservedRouteIdentifier() {
        assertIdentityRejected(activeId = ROUTE_ID, observedId = null)
    }

    @Test
    fun policyRejectsABlankIdentifierOnEitherSide() {
        for (blank in listOf("", " \t")) {
            assertIdentityRejected(activeId = blank, observedId = ROUTE_ID)
            assertIdentityRejected(activeId = ROUTE_ID, observedId = blank)
        }
    }

    @Test
    fun policyRequiresExactIdentityIncludingWhitespaceAndCase() {
        for (otherId in listOf("previous-route", "$ROUTE_ID ", ROUTE_ID.uppercase())) {
            assertIdentityRejected(activeId = ROUTE_ID, observedId = otherId)
        }
    }

    @Test
    fun missingIdentityIsDiagnosedBeforeOtherObservationEligibilityGates() {
        val invalidObservations = listOf(
            observation().copy(tmapCorridorProjection = TmapCorridorProjectionEvidence.UNAVAILABLE),
            observation().copy(className = "damaged_tactile_block"),
            observation().copy(confidence = 0.1f),
            observation().copy(ageMs = 1_201L),
        )
        for (invalid in invalidObservations) {
            val decision = policy.evaluate(input(invalid.copy(routeId = null)).copy(activeRouteId = null))
            assertEquals("tactile_route_identity_mismatch", decision.reason)
            assertNoMovement(decision)
        }
    }

    @Test
    fun matchingNonblankIdentityStillCannotAuthorizeMovement() {
        val decision = policy.evaluate(input(observation()))
        assertEquals("tactile_motion_evidence_unavailable", decision.reason)
        assertNoMovement(decision)
    }

    @Test
    fun policyGpsOuterBoundaryRemainsTwentyFiveMeters() {
        val accepted = policy.evaluate(input(observation()).copy(gpsAccuracyM = 25f))
        assertEquals("tactile_motion_evidence_unavailable", accepted.reason)
        assertNoMovement(accepted)
        val rejected = policy.evaluate(input(observation()).copy(gpsAccuracyM = 25.01f))
        assertEquals("gps_untrusted", rejected.reason)
        assertNoMovement(rejected)
    }

    @Test
    fun policyObservationAgeOuterBoundaryRemainsTwelveHundredMilliseconds() {
        for ((age, reason) in listOf(
            0L to "tactile_motion_evidence_unavailable",
            1_200L to "tactile_motion_evidence_unavailable",
            -1L to "tactile_stale",
            1_201L to "tactile_stale",
        )) {
            val decision = policy.evaluate(input(observation().copy(ageMs = age)))
            assertEquals(age.toString(), reason, decision.reason)
            assertNoMovement(decision)
        }
    }

    @Test
    fun productionGpsBoundaryIsFiveMetersAndRemainsSilentTmap() {
        for (accuracy in listOf(0f, 5f)) {
            val context = context(gpsAccuracyM = accuracy)
            val captured = supplier.evaluateObservation(output(), context)
            assertNotNull(accuracy.toString(), captured.observation)
            assertNull(accuracy.toString(), captured.rejection)
            val result = guidance(context)
            assertEquals(captured.observation, result.observation)
            assertEquals("tactile_motion_evidence_unavailable", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    @Test
    fun productionGpsGateRejectsInputsInsideThePurePolicyOuterLimit() {
        for (accuracy in listOf(5.01f, 25f)) {
            val pure = policy.evaluate(input(observation()).copy(gpsAccuracyM = accuracy))
            assertEquals("tactile_motion_evidence_unavailable", pure.reason)
            assertNoMovement(pure)
            val context = context(gpsAccuracyM = accuracy)
            assertEquals(TactileObservationRejection.LOCATION_UNTRUSTED,
                supplier.evaluateObservation(output(), context).rejection)
            val result = guidance(context)
            assertNull(result.observation)
            assertEquals(TactileObservationRejection.LOCATION_UNTRUSTED, result.observationRejections[TRACK_ID])
            assertEquals("tactile_not_visible", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    @Test
    fun productionDetectionAgeBoundaryIsFourHundredMilliseconds() {
        for (age in listOf(0L, 400L)) {
            val context = context(detectionAgeMs = age)
            val captured = supplier.evaluateObservation(output(), context)
            assertNotNull(age.toString(), captured.observation)
            assertNull(age.toString(), captured.rejection)
            assertEquals(age, requireNotNull(captured.observation).ageMs)
            val result = guidance(context)
            assertEquals(captured.observation, result.observation)
            assertEquals("tactile_motion_evidence_unavailable", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    @Test
    fun productionAgeGateRejectsInputsInsideThePurePolicyOuterLimit() {
        for (age in listOf(401L, 1_200L)) {
            val pure = policy.evaluate(input(observation().copy(ageMs = age)))
            assertEquals("tactile_motion_evidence_unavailable", pure.reason)
            assertNoMovement(pure)
            val context = context(detectionAgeMs = age)
            assertEquals(TactileObservationRejection.DETECTION_STALE,
                supplier.evaluateObservation(output(), context).rejection)
            val result = guidance(context)
            assertNull(result.observation)
            assertEquals(TactileObservationRejection.DETECTION_STALE, result.observationRejections[TRACK_ID])
            assertEquals("tactile_not_visible", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    @Test
    fun productionSupplierRequiresANonblankCapturedRouteIdentity() {
        for (routeId in listOf(null, "", " \t", "previous-route")) {
            val context = context().copy(expectedRouteId = routeId)
            val captured = supplier.evaluateObservation(output(), context)
            assertNull(captured.observation)
            assertEquals(TactileObservationRejection.ROUTE_MISMATCH, captured.rejection)
            val result = guidance(context)
            assertNull(result.observation)
            assertEquals(TactileObservationRejection.ROUTE_MISMATCH, result.observationRejections[TRACK_ID])
            assertEquals("tactile_not_visible", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    @Test
    fun productionCompositionRechecksTheLiveIdentityAfterCapture() {
        val context = context()
        assertNotNull(supplier.evaluateObservation(output(), context).observation)
        for (activeId in listOf(null, "", " \t", "previous-route")) {
            val result = guidance(context, activeId)
            assertNotNull(result.observation)
            assertTrue(result.observationRejections.isEmpty())
            assertEquals("tactile_route_identity_mismatch", result.decision.reason)
            assertSilentTmap(result)
        }
    }

    private fun assertIdentityRejected(activeId: String?, observedId: String?) {
        val decision = policy.evaluate(input(observation().copy(routeId = observedId)).copy(activeRouteId = activeId))
        assertEquals("active=$activeId observed=$observedId", "tactile_route_identity_mismatch", decision.reason)
        assertNoMovement(decision)
    }

    private fun assertNoMovement(decision: TactileRouteDecision) {
        assertEquals(LocalRouteMode.TMAP, decision.mode)
        assertNull(decision.steering)
        assertNull(decision.instruction)
    }

    private fun assertSilentTmap(result: TactileRouteGuidanceResult) {
        assertNoMovement(result.decision)
        val guided = result.outputs.single().userFacing
        assertEquals(MessageLevel.NONE, guided.messageLevel)
        assertNull(guided.stepsAhead)
        assertNull(guided.message)
    }

    private fun guidance(context: TactileProjectionContext, activeId: String? = context.expectedRouteId) =
        AndroidTactileRouteGuidance().apply(listOf(output()), context, true, true, activeRouteId = activeId)

    private fun observation() = TactileRouteObservation(
        candidateId = TRACK_ID, className = "normal_tactile_block", confidence = 0.9f,
        stableFrames = 4, stableMs = 900L, ageMs = 100L, routeHeadingDeltaDeg = 0f,
        tmapCorridorProjection = TmapCorridorProjectionEvidence.OVERLAPS,
        centerXNormalized = 0.5f, routeId = ROUTE_ID, routeSegmentIndex = 0,
    )

    private fun input(observation: TactileRouteObservation) = TactileRoutePolicyInput(
        navigationActive = true, tmapOnRoute = true, gpsAccuracyM = 2f,
        tactile = observation, activeRouteId = ROUTE_ID,
    )

    private fun context(gpsAccuracyM: Float = 2f, detectionAgeMs: Long = 100L) = TactileProjectionContext(
        captureFrameId = FRAME_ID, expectedRouteId = ROUTE_ID,
        routeProjection = ActiveRouteProjection(ROUTE_ID, 0,
            RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f),
        trustedLocation = TrustedLocation(37.0, 127.0, gpsAccuracyM, NOW_MS - 50L),
        orientation = DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            NOW_MS, 5f, EarthOrientationAccuracy.HIGH),
        magneticDeclinationDeg = 0f,
        cameraFrame = TactileCameraFrame(FRAME_ID,
            RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), NOW_MS, true),
        detectionAgeMs = detectionAgeMs, nowElapsedRealtimeMs = NOW_MS,
    )

    private fun output() = TrackedObjectDepth(
        frameId = FRAME_ID, timestampMs = NOW_MS, trackId = TRACK_ID,
        className = "normal_tactile_block", detectionConfidence = 0.9f,
        bboxNorm = RectNorm(0.4f, 0.5f, 0.2f, 0.3f),
        polygonNorm = listOf(Point2(0.4f, 0.5f), Point2(0.6f, 0.5f), Point2(0.6f, 0.8f), Point2(0.4f, 0.8f)),
        maskAreaNorm = 0.06f, centerNorm = Point2(0.5f, 0.65f), bottomContactNorm = Point2(0.5f, 0.7f),
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = 2f, rayDistanceM = null,
        groundDistanceM = null, riskDistanceM = 2f, validSampleCount = 80, validSampleRatio = 0.6f,
        depthMedianM = 2f, depthP20M = 1.9f, depthIqrM = 0.2f,
        trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(sourceQuality = 0.95f, sampleQuality = 0.9f,
            depthQuality = 0.9f, detectionQuality = 0.9f, trackingQuality = 1f,
            motionQuality = 1f, freshnessQuality = 1f, corridorQuality = 1f, hardGate = 1f),
        userFacing = UserFacingDepth(stepsAhead = 3, messageLevel = MessageLevel.INFO, message = "legacy tactile message"),
        trackAgeFrames = 4, trackStableMs = 900L, sourceValidUntilElapsedRealtimeMs = NOW_MS + 400L,
        contactDepth = ContactDepthEstimate(distanceM = 2f, spreadM = 0.05f, validSampleCount = 25),
    )

    private companion object {
        const val ROUTE_ID = "active-route"
        const val TRACK_ID = "normal"
        const val FRAME_ID = 1L
        const val NOW_MS = 10_000L
    }
}
