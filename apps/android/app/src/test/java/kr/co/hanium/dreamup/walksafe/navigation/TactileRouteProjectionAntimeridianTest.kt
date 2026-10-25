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
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S09-52 (originals/09.txt:133 "경도 정규화", w25 review pos-F11): the tactile route corridor check measures a
 * route segment just across ±180° by its short way round, like the same geometry anywhere else.
 */
class TactileRouteProjectionAntimeridianTest {
    private val supplier = AndroidTactileRouteObservationSupplier()

    @Test
    fun aSegmentJustAcrossTheAntimeridianIsInsideTheCorridorLikeTheSameGeometryElsewhere() {
        // About 0.18 m east of the walker in both cases.
        val ordinary = supplier.evaluateObservation(output(), context(walkerLongitude = 127.0, segmentLongitude = 127.000002))
        assertNotNull(ordinary.observation)
        assertNull(ordinary.rejection)
        val acrossDateLine = supplier.evaluateObservation(
            output(),
            context(walkerLongitude = 179.999999, segmentLongitude = -179.999999),
        )
        assertNull(acrossDateLine.rejection)
        assertNotNull(acrossDateLine.observation)
        // A segment really far away is still outside the corridor.
        val far = supplier.evaluateObservation(output(), context(walkerLongitude = 179.999999, segmentLongitude = -179.99))
        assertEquals(TactileObservationRejection.OUTSIDE_TMAP_CORRIDOR, far.rejection)
    }

    @Test
    fun corridorBoundaryUsesTheSameMeterScaleAtOrdinaryAndWrappedLongitudes() {
        // This fixture has 6.4937 m combined projection error, leaving about 8.5063 m
        // inside the 15 m corridor. The former 111320 scale incorrectly rejects 8.50 m.
        for (walkerLongitude in listOf(127.0, 179.99999)) {
            for ((offsetMeters, accepted) in listOf(8.50 to true, 8.51 to false)) {
                val segmentLongitude = LocalGeodesy.Frame(37.0, walkerLongitude)
                    .toCoordinate(offsetMeters, 0.0).longitude
                val result = supplier.evaluateObservation(output(), context(walkerLongitude, segmentLongitude))
                if (accepted) {
                    assertNotNull("longitude=$walkerLongitude, offset=$offsetMeters", result.observation)
                    assertNull(result.rejection)
                } else {
                    assertEquals(TactileObservationRejection.OUTSIDE_TMAP_CORRIDOR, result.rejection)
                }
            }
        }
    }

    @Test
    fun aShortAntimeridianSegmentDoesNotSpanTheOppositeSideOfTheWorld() {
        for ((startLongitude, endLongitude) in listOf(179.99995 to -179.99995, -179.99995 to 179.99995)) {
            val route = ActiveRouteProjection("active", 0,
                RoutePoint(37.0, startLongitude), RoutePoint(37.0, endLongitude), 0.0, 90f)
            val nearby = supplier.evaluateObservation(output(),
                context(179.999999, startLongitude).copy(routeProjection = route))
            assertNotNull(nearby.observation)
            assertNull(nearby.rejection)

            val opposite = supplier.evaluateObservation(output(),
                context(0.0, startLongitude).copy(routeProjection = route))
            assertNull("An approximately 9 m segment cannot include longitude 0", opposite.observation)
            assertEquals(TactileObservationRejection.OUTSIDE_TMAP_CORRIDOR, opposite.rejection)
        }
    }

    private fun context(walkerLongitude: Double, segmentLongitude: Double) = TactileProjectionContext(
        captureFrameId = FRAME_ID, expectedRouteId = "active",
        routeProjection = ActiveRouteProjection("active", 0,
            RoutePoint(36.9999, segmentLongitude), RoutePoint(37.001, segmentLongitude), 0.0, 0f),
        trustedLocation = TrustedLocation(37.0, walkerLongitude, 2f, NOW_MS - 50),
        orientation = DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            NOW_MS, 5f, EarthOrientationAccuracy.HIGH),
        magneticDeclinationDeg = 0f,
        cameraFrame = TactileCameraFrame(FRAME_ID,
            RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), NOW_MS, true),
        detectionAgeMs = 100L, nowElapsedRealtimeMs = NOW_MS,
    )

    private fun output(contact: Point2 = Point2(0.5f, 0.7f), depth: Float = 2f) = TrackedObjectDepth(
        frameId = FRAME_ID, timestampMs = NOW_MS, trackId = "normal",
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
