package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.ContactDepthEstimate
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-30 reasons, S12-16 damage-area scope and S12-6 protrusion counterexamples. */
class TactileObservationDiagnosticsRegressionTest {
    private val supplier = AndroidTactileRouteObservationSupplier()

    @Test fun everySupplierGateNamesItsOwnRejectionAndKeepsTheOldNullResult() {
        val base = context()
        val downwardOrientation = requireNotNull(base.orientation).copy(
            deviceToMagneticEnu = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
        val farRoute = requireNotNull(base.routeProjection).copy(
            segmentStart = RoutePoint(36.9999, 127.01), segmentEnd = RoutePoint(37.001, 127.01),
        )
        val cases = listOf(
            Triple(TactileObservationRejection.NOT_TACTILE_ROUTE_CLASS, output().copy(className = "person"), base),
            Triple(TactileObservationRejection.FRAME_MISMATCH, output().copy(frameId = FRAME_ID + 1), base),
            Triple(TactileObservationRejection.ROUTE_MISMATCH, output(), base.copy(expectedRouteId = "other")),
            Triple(TactileObservationRejection.ROUTE_GEOMETRY_INVALID, output(),
                base.copy(routeProjection = requireNotNull(base.routeProjection).copy(segmentIndex = -1))),
            Triple(TactileObservationRejection.LOCATION_UNTRUSTED, output(),
                base.copy(trustedLocation = TrustedLocation(37.0, 127.0, 2f, NOW_MS - 5_000))),
            Triple(TactileObservationRejection.ORIENTATION_UNTRUSTED, output(),
                base.copy(orientation = requireNotNull(base.orientation).copy(observedAtElapsedRealtimeMs = NOW_MS - 1_000))),
            Triple(TactileObservationRejection.DECLINATION_UNAVAILABLE, output(), base.copy(magneticDeclinationDeg = null)),
            Triple(TactileObservationRejection.CAMERA_FRAME_UNTRUSTED, output(),
                base.copy(cameraFrame = requireNotNull(base.cameraFrame).copy(tracking = false))),
            Triple(TactileObservationRejection.DETECTION_STALE, output(), base.copy(detectionAgeMs = 1_000L)),
            Triple(TactileObservationRejection.DEPTH_INSUFFICIENT, output().copy(contactDepth = null), base),
            Triple(TactileObservationRejection.CONTACT_UNAVAILABLE, output().copy(bottomContactNorm = null), base),
            Triple(TactileObservationRejection.POSE_UNSUPPORTED, output(), base.copy(orientation = downwardOrientation)),
            // A contact at the principal point has no vertical drop to the ground.
            Triple(TactileObservationRejection.PROJECTION_OUT_OF_RANGE, output(contact = Point2(0.5f, 0.52f)), base),
            Triple(TactileObservationRejection.OUTSIDE_TMAP_CORRIDOR, output(), base.copy(routeProjection = farRoute)),
        )
        for ((reason, output, context) in cases) {
            val evaluation = supplier.evaluateObservation(output, context)
            assertNull(reason.name, evaluation.observation)
            assertEquals(reason.name, reason, evaluation.rejection)
            assertNull(reason.name, supplier.observationFor(output, context))
        }
        val accepted = supplier.evaluateObservation(output(), base)
        assertNotNull(accepted.observation)
        assertNull(accepted.rejection)
        assertEquals(accepted.observation, supplier.observationFor(output(), base))
    }

    @Test fun guidanceKeepsReasonsWithoutChangingTheSilentTmapFallback() {
        val blocker = output("blocker").copy(className = "damaged_tactile_block", frameId = FRAME_ID + 1)
        val blocked = apply(listOf(output("normal"), blocker))
        assertNull(blocked.observation)
        assertEquals("tactile_not_visible", blocked.decision.reason)
        assertEquals(TactileObservationRejection.FRAME_MISMATCH, blocked.observationRejections["blocker"])
        assertEquals(TactileObservationRejection.BLOCKER_UNPROJECTABLE, blocked.observationRejections["normal"])
        assertEquals("obs=frame_mismatch:1,blocker_unprojectable:1", blocked.observationDiagnosticsText())
        assertNoMovement(blocked)

        val noContext = AndroidTactileRouteGuidance().apply(listOf(output()), null, true, true, "active")
        assertEquals(TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE, noContext.observationRejections["normal"])
        assertNoMovement(noContext)

        val noSupplier = AndroidTactileRouteGuidance(observationSupplier = null)
            .apply(listOf(output()), context(), true, true, "active")
        assertEquals(TactileObservationRejection.SUPPLIER_UNAVAILABLE, noSupplier.observationRejections["normal"])
        assertNoMovement(noSupplier)

        val custom = AndroidTactileRouteGuidance(TactileRouteObservationSupplier { _, _ -> null })
            .apply(listOf(output()), context(), true, true, "active")
        assertEquals(TactileObservationRejection.UNSPECIFIED, custom.observationRejections["normal"])

        val accepted = apply(listOf(output()))
        assertNotNull(accepted.observation)
        assertTrue(accepted.observationRejections.isEmpty())
        assertEquals("obs=-", accepted.observationDiagnosticsText())
        assertEquals("tactile_motion_evidence_unavailable", accepted.decision.reason)
        assertNoMovement(accepted)
    }

    @Test fun damageAreaOverlappingANormalBlockRemovesOnlyThatCandidate() {
        val overlapped = output("overlapped")
        val separate = output("separate", contact = Point2(0.8f, 0.7f))
        val area = damageArea(RectNorm(0.45f, 0.60f, 0.10f, 0.15f))

        val result = apply(listOf(overlapped, separate, area))
        assertEquals("separate", result.observation?.candidateId)
        assertEquals(TactileObservationRejection.DAMAGE_AREA_OVERLAP, result.observationRejections["overlapped"])
        assertNull(result.observationRejections["separate"])
        assertNoMovement(result)

        val only = apply(listOf(overlapped, area))
        assertNull(only.observation)
        assertEquals("tactile_not_visible", only.decision.reason)
        assertNoMovement(only)
    }

    @Test fun unrelatedDamageAreaDoesNotBlockEveryNormalBlock() {
        val result = apply(listOf(output(), damageArea(RectNorm(0.0f, 0.0f, 0.1f, 0.1f))))
        assertEquals("normal", result.observation?.candidateId)
        assertTrue(result.observationRejections.isEmpty())
        assertNoMovement(result)
    }

    @Test fun invalidDamageAreaGeometryIsAnUnknownRelationAndFailsClosed() {
        val result = apply(listOf(output(), damageArea(RectNorm(Float.NaN, 0.6f, 0.1f, 0.1f))))
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.DAMAGE_AREA_OVERLAP, result.observationRejections["normal"])
        assertNoMovement(result)
    }

    @Test fun smallBottomProtrusionMovesTheContactButNeverCreatesSteering() {
        val extractor = MaskPolygonExtractor()
        val block = listOf(Point2(0.40f, 0.45f), Point2(0.60f, 0.45f), Point2(0.60f, 0.75f), Point2(0.40f, 0.75f))
        // Same mask plus a two-vertex sliver at the lower right (a shadow or leaked pixel).
        val protruding = listOf(Point2(0.40f, 0.45f), Point2(0.60f, 0.45f), Point2(0.60f, 0.75f),
            Point2(0.585f, 0.79f), Point2(0.575f, 0.75f), Point2(0.40f, 0.75f))
        val contacts = listOf(block, protruding).map { polygon ->
            requireNotNull(extractor.extract(DetectionCandidate(
                className = "normal_tactile_block", detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0.40f, 0.45f, 0.20f, 0.34f), polygonNorm = polygon,
            )).bottomContactNorm)
        }
        // S12-6 remains partly open: the single extreme vertex still moves the contact.
        assertNotEquals(contacts[0].x, contacts[1].x, 0.01f)
        for (contact in contacts) {
            val result = apply(listOf(output(contact = contact)))
            assertNull(result.decision.steering)
            assertNoMovement(result)
        }
    }

    private fun apply(outputs: List<TrackedObjectDepth>) =
        AndroidTactileRouteGuidance().apply(outputs, context(), true, true, activeRouteId = "active")

    private fun assertNoMovement(result: TactileRouteGuidanceResult) {
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.steering)
        assertNull(result.decision.instruction)
        result.outputs.forEach {
            assertEquals(MessageLevel.NONE, it.userFacing.messageLevel)
            assertNull(it.userFacing.message)
        }
    }

    private fun context() = TactileProjectionContext(
        captureFrameId = FRAME_ID, expectedRouteId = "active",
        routeProjection = ActiveRouteProjection("active", 0,
            RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0), 0.0, 0f),
        trustedLocation = TrustedLocation(37.0, 127.0, 2f, NOW_MS - 50),
        orientation = DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            NOW_MS, 5f, EarthOrientationAccuracy.HIGH),
        magneticDeclinationDeg = 0f,
        cameraFrame = TactileCameraFrame(FRAME_ID,
            RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), NOW_MS, true),
        detectionAgeMs = 100L, nowElapsedRealtimeMs = NOW_MS,
    )

    private fun damageArea(box: RectNorm) = output("area").copy(
        className = "tactile_damage_area", bboxNorm = box, contactDepth = null,
    )

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
        contactDepth = ContactDepthEstimate(distanceM = depth, spreadM = 0.05f, validSampleCount = 25),
    )

    private companion object {
        const val FRAME_ID = 1L
        const val NOW_MS = 10_000L
    }
}
