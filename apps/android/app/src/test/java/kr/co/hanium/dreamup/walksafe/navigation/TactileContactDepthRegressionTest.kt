package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.LetterboxCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.ModelInputTransform
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthEstimator
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthInput
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class TactileContactDepthRegressionTest {
    @Test fun bottomContactUsesItsLocalDepthRatherThanTheWholeMaskMedian() {
        // A long block on a flat walkway: depth grows continuously up the image (z = K / (row - horizon)),
        // 1.2 m at the bottom contact and 2.6 m at the far end. No separate nearer population exists.
        val output = estimate { _, y -> (GROUND_K_MM_ROWS / (y + GROUND_ROW_OFFSET)).roundToInt() }

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        val median = requireNotNull(output.depthMedianM)
        val contact = requireNotNull(output.contactDepth).distanceM
        assertEquals(1.2f, contact, 0.05f)
        assertTrue("mask median $median must differ from contact $contact", median - contact >= 0.3f)
        assertNotNull(AndroidTactileRouteObservationSupplier().observationFor(output, context()))
    }

    @Test fun nearStepAtTheContactIsAmbiguousForegroundSoNothingIsProjected() {
        // The same connected 1.2 m strip inside a 3 m mask is S11-2's foreground/background mix:
        // the sampler cannot assign it to the block's surface, so neither 3 m nor 1.2 m is metric
        // evidence (S11-2), and without trusted contact support the ray is not projected (S12-5).
        val output = estimate { x, y -> if (x in 30..69 && y in 67..69) 1_200 else 3_000 }

        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
        assertNull(output.depthMedianM)
        assertNull(output.riskDistanceM)
        assertNull(output.contactDepth)
        assertNull(AndroidTactileRouteObservationSupplier().observationFor(output, context()))
        val result = AndroidTactileRouteGuidance().apply(listOf(output), context(), true, true, "active")
        assertNull(result.observation)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertNull(result.decision.instruction)
    }

    @Test fun wholeMaskDepthWithoutContactSupportFallsClosedToTmap() {
        val output = estimate { x, y -> if (x in 30..69 && y in 67..69) 0 else 3_000 }

        assertEquals(3f, requireNotNull(output.depthMedianM), 0.01f)
        assertNull(output.contactDepth)
        assertNull(AndroidTactileRouteObservationSupplier().observationFor(output, context()))
        val result = AndroidTactileRouteGuidance().apply(listOf(output), context(), true, true, "active")
        assertNull(result.observation)
        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
    }

    @Test fun consistentlySupportedContactStillProducesAnObservation() {
        val output = estimate { _, _ -> 2_000 }

        assertEquals(2f, requireNotNull(output.contactDepth).distanceM, 0.01f)
        assertTrue(requireNotNull(output.contactDepth).validSampleCount >= 5)
        assertNotNull(AndroidTactileRouteObservationSupplier().observationFor(output, context()))
    }

    private fun estimate(depthAt: (Int, Int) -> Int): TrackedObjectDepth {
        val polygon = listOf(Point2(0.3f, 0.1f), Point2(0.7f, 0.1f),
            Point2(0.7f, 0.7f), Point2(0.3f, 0.7f))
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate(
            className = "normal_tactile_block", detectionConfidence = 0.9f,
            bboxNorm = RectNorm(0.3f, 0.1f, 0.4f, 0.6f), polygonNorm = polygon,
        ))
        val tracker = ObjectTracker()
        var track = tracker.update(listOf(geometry), timestampMs = 9_000L).single()
        for (at in listOf(9_300L, 9_600L, 9_900L)) {
            track = tracker.update(listOf(geometry), timestampMs = at).single()
        }
        val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
        )
        val depth = DepthImage16(100, 100, IntArray(10_000) { index ->
            depthAt(index % 100, index / 100)
        })
        return ObjectDepthEstimator(tracker = tracker).estimate(ObjectDepthInput(
            frameId = FRAME_ID, timestampMs = NOW_MS, geometry = geometry, track = track,
            mapper = mapper, rawDepth = depth,
            rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
            rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = NOW_MS * 1_000_000L,
        ))
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

    private companion object {
        const val FRAME_ID = 1L
        const val NOW_MS = 10_000L
        // 131_500 / (69 + 40.58) = 1_200 mm at the contact row; 131_500 / (10 + 40.58) = 2_600 mm at the top.
        const val GROUND_K_MM_ROWS = 131_500.0
        const val GROUND_ROW_OFFSET = 40.58
    }
}
