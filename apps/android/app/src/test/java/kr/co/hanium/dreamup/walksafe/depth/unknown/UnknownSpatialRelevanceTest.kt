package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

class UnknownSpatialRelevanceTest {
    private val pose = CameraPoseEvidence(1, 1000, 0f, 1.5f, 0f, 0f, 0f, 1f,
        CameraImageProjection(100, 100, 100f, 100f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))
    private val up = Vec3(0f, 1f, 0f)
    private val floorPlane = CapturedHorizontalPlane(1, 1000,
        listOf(Vec3(-1.2f, 0f, -.3f), Vec3(1.2f, 0f, -.3f), Vec3(1.2f, 0f, 3.2f), Vec3(-1.2f, 0f, 3.2f)))
    private fun context(planes: List<CapturedHorizontalPlane> = emptyList()) =
        UnknownSpatialContext(pose, up, 1000, true, true, groundPlaneCandidates = planes)
    private fun patch(x: Float = 0f, y: Float = 1f, z: Float = 1.5f) = (0..2).flatMap { row ->
        (0..3).map { column -> UnknownSpatialSample(Vec3(x + column * .006f, y, z + row * .006f)) } }
    private val floorScene get() = (0..10).flatMap { x -> (0..19).map { z ->
        UnknownSpatialSample(Vec3(-.6f + x * .12f, 0f, .24f + z * .12f)) } }
    private fun mask(left: Int = 45, top: Int = 35, width: Int = 10, height: Int = 20): BinaryImageMask {
        val size = width * height
        val bytes = ByteArray((size + 7) / 8)
        repeat(size) { bytes[it / 8] = (bytes[it / 8].toInt() or (1 shl (it % 8))).toByte() }
        return BinaryImageMask.fromPackedRoi(100, 100, left, top, width, height, bytes)
    }
    private fun output(range: Float = 2f) = TrackedObjectDepth(
        frameId = 1, timestampMs = 1000, trackId = "unknown-test", className = UNNAMED_OBSTACLE_CLASS,
        detectionConfidence = .95f, bboxNorm = RectNorm(.45f, .35f, .1f, .2f), polygonNorm = emptyList(),
        maskAreaNorm = .02f, centerNorm = Point2(.5f, .45f), bottomContactNorm = Point2(.5f, .55f),
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = range, rayDistanceM = range,
        groundDistanceM = null, riskDistanceM = range, validSampleCount = 20, validSampleRatio = 1f,
        depthMedianM = range, depthP20M = range, depthIqrM = .01f, trend = Trend.STABLE,
        approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null), trackAgeFrames = 5, trackStableMs = 1000,
    )

    @Test fun threeMetersIncludedButOneMillimeterBeyondExcludedEvenApproaching() {
        assertTrue(UnknownWalkingObstaclePolicy.select(mask(), output(3f), 0).warningCandidate)
        val fast = output(3.001f).copy(trend = Trend.APPROACHING, approachSpeedMps = 4f, timeToCollisionMs = 750)
        assertEquals("outside_near_obstacle_range", UnknownWalkingObstaclePolicy.select(mask(), fast, 0).reason)
    }

    @Test fun axialDistanceCannotAdmitOffAxisSurfaceBeyondThreeMeters() {
        val offAxis = output(3.1f).copy(zDistanceM = 2.9f, riskDistanceM = 2.9f)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), offAxis, 0).show)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), offAxis.copy(rayDistanceM = null), 0).show)
    }

    @Test fun coherentActualSampleRangeOverridesAxialOrCenterDistance() {
        val samples = patch(x = 1.3f, y = 1.5f, z = 2.9f)
        val evidence = UnknownSpatialRelevance.evaluate(samples, context().copy(gravityUpInAnchor = null))
        assertTrue(evidence.nearestReliableRangeM!! > 3f)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), output(2.9f), 0, spatialEvidence = evidence).show)
    }

    @Test fun ordinaryUnmeasuredAndUnavailableCandidatesNeverDisplay() {
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), null, 0).show)
        for (state in listOf(DepthAvailability.UNAVAILABLE, DepthAvailability.TEMPORARILY_UNAVAILABLE)) {
            assertFalse(UnknownWalkingObstaclePolicy.select(mask(), output().copy(depthAvailability = state), 0).show)
        }
    }

    @Test fun boundedRadialPredictionCanDisplayWithoutNewMetricWarning() {
        val predicted = output().copy(source = DepthSource.UNKNOWN, riskDistanceM = null, rayDistanceM = null,
            depthAvailability = DepthAvailability.PREDICTED,
            prediction = PredictedDepthEstimate(2.8f, 900, 100, .2f, 500))
        val evidence = UnknownSpatialEvidence(UnknownSpatialDisposition.UNCERTAIN, null, 0f, "prediction", 3f)
        val selection = UnknownWalkingObstaclePolicy.select(mask(), predicted, 0, spatialEvidence = evidence)
        assertTrue(selection.show); assertFalse(selection.warningCandidate)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), predicted, 0,
            spatialEvidence = evidence.copy(predictionUpperRangeM = 3.001f)).show)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), predicted, 0).show)
        val spatialPrediction = predicted.copy(prediction = predicted.prediction!!.copy(rangeUpperBoundM = 3f))
        assertTrue(UnknownWalkingObstaclePolicy.select(mask(), spatialPrediction, 0).show)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), spatialPrediction.copy(timestampMs = 1001), 0).show)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), predicted.copy(
            prediction = predicted.prediction!!.copy(predictionAgeMs = 501)), 0, spatialEvidence = evidence).show)
    }

    @Test fun smallExtentDoesNotProveATripFragmentHarmless() {
        val extent = ObservedMaskExtent(.008f, .008f, .01f, .01f, true, "supported_extent")
        assertTrue(UnknownWalkingObstaclePolicy.select(mask(width = 2, height = 2), output(2.8f), 0,
            metricExtent = extent).show)
    }

    @Test fun fallbackPreservesHeadAndFootHeightAcrossAllPortraitRotations() {
        for (turns in 0..3) for (uprightY in listOf(.025f, .975f)) {
            val center = uprightPointToSensor(Point2(.5f, uprightY), turns)!!
            val selected = UnknownWalkingObstaclePolicy.select(mask((center.x * 100).toInt() - 1,
                (center.y * 100).toInt() - 1, 2, 2), output(1f), turns)
            assertTrue("rotation $turns height $uprightY", selected.show)
        }
    }

    @Test fun actualCorridorCanKeepAProjectedSideMask() {
        val evidence = UnknownSpatialRelevance.evaluate(patch(), context())
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE, evidence.disposition)
        assertTrue(UnknownWalkingObstaclePolicy.select(mask(left = 0), output(), 0, spatialEvidence = evidence).show)
    }

    @Test fun stronglyMeasuredSideSurfaceSuppressesEvenScreenCenteredMask() {
        val evidence = UnknownSpatialRelevance.evaluate(patch(x = 1f), context())
        assertEquals(UnknownSpatialDisposition.OUTSIDE_CORRIDOR, evidence.disposition)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), output(), 0, spatialEvidence = evidence).show)
    }

    @Test fun thinAndLowAndHeadSurfacesRemainCorridorCandidates() {
        for (height in listOf(.02f, .08f, 1.5f, 1.9f)) {
            val evidence = UnknownSpatialRelevance.evaluate(patch(y = height), context(listOf(floorPlane)), floorScene)
            assertEquals("height $height", UnknownSpatialDisposition.CORRIDOR_OBSTACLE, evidence.disposition)
        }
    }

    @Test fun floorRequiresTrackedUnderCameraPlaneAndConnectedMatchingSceneDepth() {
        val floorMask = patch(y = 0f)
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR,
            UnknownSpatialRelevance.evaluate(floorMask, context(listOf(floorPlane)), floorScene).disposition)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(floorMask, context(), floorScene).disposition)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(floorMask, context(listOf(floorPlane))).disposition)
    }

    @Test fun horizontalTableAndUnsupportedPlaneDoNotBecomeFloor() {
        val table = floorPlane.copy(polygonInAnchor = floorPlane.polygonInAnchor.map { it + Vec3(0f, .7f, 0f) })
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(patch(y = .7f), context(listOf(table)), floorScene).disposition)
        val away = floorPlane.copy(polygonInAnchor = floorPlane.polygonInAnchor.map { it + Vec3(0f, 0f, 1f) })
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(patch(y = 0f), context(listOf(away)), floorScene).disposition)
        val stale = floorPlane.copy(captureTimestampMs = 999)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(patch(y = 0f), context(listOf(stale)), floorScene).disposition)
    }

    @Test fun disconnectedFarFlatDepthCannotConfirmGroundNearFeet() {
        val farOnly = floorScene.filter { it.pointInAnchor.z > 1f }
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            UnknownSpatialRelevance.evaluate(patch(y = 0f), context(listOf(floorPlane)), farOnly).disposition)
    }

    @Test fun cellRoundingDoesNotDisconnectContiguousMeasuredFloor() {
        val regularScene = (0..16).flatMap { x -> (0..24).map { z ->
            UnknownSpatialSample(Vec3(-1.6f + x * (3.2f / 16f), 0f, .3f + z * (3.2f / 24f))) } }
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR,
            UnknownSpatialRelevance.evaluate(patch(y = 0f), context(listOf(floorPlane)), regularScene).disposition)
    }

    @Test fun partialOrWeakSupportCannotExcludeSideOrFloor() {
        assertEquals(UnknownSpatialDisposition.UNCERTAIN,
            UnknownSpatialRelevance.evaluate(patch(x = 1f), context().copy(fullyCovered = false)).disposition)
        assertEquals(UnknownSpatialDisposition.UNCERTAIN,
            UnknownSpatialRelevance.evaluate(patch(x = 1f).map { it.copy(confidence = .6f) }, context()).disposition)
    }

    @Test fun gravityAndPoseReferenceUncertaintyNeverImplyHarmlessFloor() {
        val noGravity = UnknownSpatialRelevance.evaluate(patch(y = 0f),
            context(listOf(floorPlane)).copy(gravityUpInAnchor = null), floorScene)
        assertEquals(UnknownSpatialDisposition.UNCERTAIN, noGravity.disposition)
        assertNotNull(noGravity.nearestReliableRangeM)
        assertNull(UnknownSpatialRelevance.evaluate(patch(), context().copy(frameTimestampMs = 1001)).nearestReliableRangeM)
        assertNull(UnknownSpatialRelevance.evaluate(patch(), context().copy(independentDepth = false,
            depthGeometryCurrent = false)).nearestReliableRangeM)
    }

    @Test fun validatedCurrentReprojectionRetainsGeometryWithoutClaimingIndependentDepth() {
        val reprojected = context(listOf(floorPlane)).copy(independentDepth = false, depthGeometryCurrent = true)
        assertFalse(reprojected.independentDepth)
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR,
            UnknownSpatialRelevance.evaluate(patch(y = 0f), reprojected, floorScene).disposition)
    }

    @Test fun anchorRotationPreservesFloorSelectionWhenGravityIsRotatedWithIt() {
        fun rotate(v: Vec3) = Vec3(-v.y, v.x, v.z)
        val projection = pose.imageProjection!!
        val rotatedPose = pose.copy(positionX = -1.5f, positionY = 0f,
            imageProjection = projection.copy(rightX = 0f, rightY = 1f, upX = -1f, upY = 0f))
        val rotatedPlane = floorPlane.copy(polygonInAnchor = floorPlane.polygonInAnchor.map(::rotate))
        val rotated = context(listOf(rotatedPlane)).copy(pose = rotatedPose, gravityUpInAnchor = rotate(up))
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR, UnknownSpatialRelevance.evaluate(
            patch(y = 0f).map { it.copy(pointInAnchor = rotate(it.pointInAnchor)) }, rotated,
            floorScene.map { it.copy(pointInAnchor = rotate(it.pointInAnchor)) }).disposition)
    }

    @Test fun measuredTravelDirectionReplacesCameraDirectionForCorridor() {
        val samples = patch(x = 1f, y = 1.5f, z = .1f)
        assertEquals(UnknownSpatialDisposition.OUTSIDE_CORRIDOR, UnknownSpatialRelevance.evaluate(samples, context()).disposition)
        val travel = UnknownSpatialRelevance.evaluate(samples, context().copy(travelVelocityInAnchorMps = Vec3(1f, 0f, 0f)))
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE, travel.disposition)
        assertTrue(travel.reason.contains("measured_travel_direction"))
    }

    @Test fun oneSpuriousNearPointCannotPullFarSurfaceInsideThreeMeters() {
        val samples = patch(y = 1.5f, z = 3.2f) + UnknownSpatialSample(Vec3(0f, 1.5f, 1f))
        assertTrue(UnknownSpatialRelevance.evaluate(samples, context()).nearestReliableRangeM!! > 3f)
    }

    @Test fun emptyAndInvalidGeometryAndEmptySamplesStayExcludedOrUncertain() {
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(width = 0), output(), 0).show)
        assertFalse(UnknownWalkingObstaclePolicy.select(mask(), output(), 4).show)
        assertNull(UnknownSpatialRelevance.evaluate(emptyList(), context()).nearestReliableRangeM)
    }
}
