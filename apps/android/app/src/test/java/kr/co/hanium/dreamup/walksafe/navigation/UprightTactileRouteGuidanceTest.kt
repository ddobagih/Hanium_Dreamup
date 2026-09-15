package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UprightTactileRouteGuidanceTest {
    @Test
    fun centralPavingKeepsTheSamePhysicalContactAndStraightGuidanceAtEveryRotation() {
        for (turns in 0..3) {
            val output = output(RectNorm(.4f, .55f, .2f, .3f), turns)
            assertContact(output, Point2(.5f, .82f), turns)
            val decision = guidance(output, turns)
            assertEquals("rotation=$turns", TactileSteering.STRAIGHT, decision.decision.steering)
            assertEquals(.5f, requireNotNull(decision.observation).centerXNormalized, EPSILON)
        }
    }

    @Test
    fun currentTrackedPavingPropagatesRotationThroughDepthAndGuidance() {
        for (turns in 0..3) {
            val output = output(RectNorm(.4f, .55f, .2f, .3f), turns, tracked = true)
            assertContact(output, Point2(.5f, .82f), turns)
            assertEquals("rotation=$turns", TactileSteering.STRAIGHT, guidance(output, turns).decision.steering)
        }
    }

    @Test
    fun leftAndRightPavingKeepUserRelativeDirectionsAtEveryRotation() {
        for (turns in 0..3) for ((centerX, steering) in listOf(.2f to TactileSteering.LEFT, .8f to TactileSteering.RIGHT)) {
            val output = output(RectNorm(centerX - .1f, .55f, .2f, .3f), turns)
            assertContact(output, Point2(centerX, .82f), turns)
            assertEquals("rotation=$turns center=$centerX", steering, guidance(output, turns).decision.steering)
        }
    }

    @Test
    fun segmentedPavingSelectsTheUprightBottomBandAndKeepsSensorPolygon() {
        val uprightPolygon = listOf(Point2(.44f, .5f), Point2(.56f, .5f), Point2(.65f, .82f), Point2(.35f, .82f))
        for (turns in 0..3) {
            val output = output(RectNorm(.35f, .5f, .3f, .32f), turns, uprightPolygon)
            assertEquals(uprightPolygon.map { sensorPoint(it, turns) }, output.polygonNorm)
            assertContact(output, Point2(.5f, .82f), turns)
            assertEquals(TactileSteering.STRAIGHT, guidance(output, turns).decision.steering)
        }
    }

    @Test
    fun uprightTopContactCannotPassTheLowerScreenGateAfterSensorRotation() {
        for (turns in 0..3) {
            val output = output(RectNorm(.4f, .08f, .2f, .18f), turns)
            assertContact(output, Point2(.5f, .242f), turns)
            assertNull("rotation=$turns", guidance(output, turns).observation)
        }
    }

    @Test
    fun bottomClippingSuppressesContactForBothSegmentationAndErodedBboxFallback() {
        val rect = RectNorm(.4f, .7f, .2f, .3f)
        val polygon = listOf(Point2(.4f, .7f), Point2(.6f, .7f), Point2(.6f, 1f), Point2(.4f, 1f))
        for (turns in 0..3) for (mask in listOf(emptyList(), polygon)) {
            val output = output(rect, turns, mask)
            assertNull("rotation=$turns segmented=${mask.isNotEmpty()}", output.bottomContactNorm)
            assertNull(guidance(output, turns).observation)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        }
    }

    @Test
    fun sideClippingDoesNotBecomeBottomClippingWhenTheSensorTurns() {
        for (turns in 0..3) {
            val output = output(RectNorm(0f, .55f, .2f, .3f), turns)
            assertContact(output, Point2(.1f, .82f), turns)
            assertEquals(TactileSteering.LEFT, guidance(output, turns).decision.steering)
        }
    }

    @Test
    fun unknownAndInvalidDisplayOrientationKeepMetricDepthButCannotGuide() {
        for (turns in listOf(null, -1, 4)) {
            val output = output(RectNorm(.4f, .55f, .2f, .3f), turns)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
            assertEquals(2f, requireNotNull(output.zDistanceM), EPSILON)
            assertNull("rotation=$turns", output.bottomContactNorm)
            assertNull(guidance(output, 0).observation)
            assertNull(guidance(output.copy(bottomContactNorm = Point2(.5f, .82f)), 0).observation)
        }
    }

    @Test
    fun missingMetricDepthCannotGainTactileGuidanceThroughRotation() {
        for (turns in 0..3) {
            val output = output(RectNorm(.4f, .55f, .2f, .3f), turns, metric = false)
            assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
            assertNull(output.zDistanceM)
            assertNull(guidance(output, turns).observation)
        }
    }

    private fun output(
        uprightRect: RectNorm,
        turns: Int?,
        uprightPolygon: List<Point2> = emptyList(),
        tracked: Boolean = false,
        metric: Boolean = true,
    ): TrackedObjectDepth {
        val sensorTurns = turns?.takeIf { it in 0..3 } ?: 0
        val detection = DetectionCandidate("linear_tactile_paving", .95f,
            UprightCameraImage.toSensor(uprightRect, sensorTurns), uprightPolygon.map { sensorPoint(it, sensorTurns) })
        val pipeline = ObjectDepthRuntimePipeline()
        return (0..3).map { index ->
            val at = NOW_MS - 900L + index * 300L
            val frameId = at * 1_000_000L
            val snapshot = DepthFrameSnapshot(frameId,
                rawDepth = if (metric) DepthImage16(100, 100, IntArray(10_000) { 2_000 }) else null,
                rawConfidence = if (metric) ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }) else null,
                fullDepth = null, rawDepthTimestampNs = frameId, cameraImageTimestampNs = frameId)
            if (tracked) {
                val key = VisualFrameKey(1L, frameId, frameId, at, 1L)
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    VisualTrackingResult(key, key, listOf(VisualTrackingObservation(
                        sourceIndex = 0, detectorSourceKey = key, trackedTargetKey = key,
                        status = VisualTrackingStatus.TRACKED, geometry = detection, trackingQuality = .95f,
                        failure = null, translationNorm = Point2(0f, 0f), originalFeatureCount = 8,
                        survivingFeatureCount = 8, residualPx = 0f, polygonConstrained = true,
                    )), VisualTrackingMetrics(0L, 1, 0L, false, 1, 0)), listOf(detection), true, at))
                pipeline.processTrackedObservation(snapshot, observation, mapper, frameId, at, imageQuarterTurns = turns).single()
            } else {
                pipeline.process(snapshot, frameId, at, listOf(detection), mapper = mapper, imageQuarterTurns = turns).single()
            }
        }.last()
    }

    private fun guidance(output: TrackedObjectDepth, turns: Int): TactileRouteGuidanceResult =
        AndroidTactileRouteGuidance().apply(listOf(output), context(turns), true, true)

    private fun assertContact(output: TrackedObjectDepth, upright: Point2, turns: Int) {
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(2f, requireNotNull(output.zDistanceM), EPSILON)
        assertNotNull("rotation=$turns", output.bottomContactNorm)
        val contact = requireNotNull(output.bottomContactNorm)
        val expected = sensorPoint(upright, turns)
        assertEquals("sensor x rotation=$turns", expected.x, contact.x, EPSILON)
        assertEquals("sensor y rotation=$turns", expected.y, contact.y, EPSILON)
        // Fixed camera mounting, independently varied device-to-Earth roll. The same upright
        // contact must project to the same Earth point, not merely the same guidance string.
        val earth = earthRotation(turns).rotate(Vec3((contact.x - .5f) * 4f, -(contact.y - .5f) * 4f, -2f))
        assertEquals((upright.x - .5f) * 4f, earth.x, EPSILON)
        assertEquals(2f, earth.y, EPSILON)
        assertEquals(-(upright.y - .5f) * 4f, earth.z, EPSILON)
    }

    private fun sensorPoint(point: Point2, turns: Int): Point2 = when (turns) {
        0 -> point
        1 -> Point2(point.y, 1f - point.x)
        2 -> Point2(1f - point.x, 1f - point.y)
        else -> Point2(1f - point.y, point.x)
    }

    private fun earthRotation(turns: Int): RotationMatrix3 = when (turns) {
        0 -> RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        1 -> RotationMatrix3(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f)
        2 -> RotationMatrix3(-1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f)
        else -> RotationMatrix3(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f)
    }

    private fun context(turns: Int): TactileProjectionContext {
        val location = TrustedLocation(37.0, 127.0, 2f, NOW_MS - 50L)
        val navigator = RouteNavigator()
        navigator.setRoute(WalkingRoute(priority = "STAIR_AVOID", summary = WalkingRouteSummary(120, 100),
            polyline = listOf(RoutePoint(36.9999, 127.0), RoutePoint(37.001, 127.0)),
            guidePoints = emptyList(), providerRouteId = "route-1"))
        navigator.update(location, nowMs = NOW_MS, requestInFlight = false)
        return TactileProjectionContext(
            captureFrameId = FRAME_ID, expectedRouteId = navigator.currentRouteId(),
            routeProjection = navigator.currentProjection(location), trustedLocation = location,
            orientation = DeviceEarthOrientation(earthRotation(turns), NOW_MS, 5f, EarthOrientationAccuracy.HIGH),
            magneticDeclinationDeg = 0f,
            cameraFrame = TactileCameraFrame(FRAME_ID, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                TactileCameraIntrinsics(1_000, 1_000, 500f, 500f, 500f, 500f), NOW_MS, true),
            detectionAgeMs = 100L, nowElapsedRealtimeMs = NOW_MS,
        )
    }

    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))

    private companion object {
        const val NOW_MS = 10_000L
        const val FRAME_ID = NOW_MS * 1_000_000L
        const val EPSILON = .00001f
    }
}
