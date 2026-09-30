package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-24: TTC needs an approach speed that stands out from the distance history's own scatter. */
class ObjectTrackerTtcEvidenceQualityTest {
    private fun kinematics(
        distances: List<Float>, intervalMs: Long = 200L, sigmas: List<Float?> = List(distances.size) { null },
    ): ApproachKinematics {
        val tracker = ObjectTracker()
        var latest: TrackState? = null
        distances.forEachIndexed { index, distance ->
            val at = 1_000L + index * intervalMs
            val track = tracker.update(listOf(geometry()), at).single()
            assertTrue(tracker.recordDistance(track, distance, DepthSource.ARCORE_RAW_DEPTH, 0.9f, at,
                distanceSigmaM = sigmas[index]))
            latest = track
        }
        return tracker.approachKinematics(requireNotNull(latest), distances.last(), DepthSource.ARCORE_RAW_DEPTH)
    }

    @Test
    fun steadyApproachKeepsSpeedAndTtc() {
        val jitter = listOf(0.03f, -0.02f, 0.01f, -0.03f, 0.02f, -0.01f, 0.03f, -0.02f)
        val result = kinematics(List(8) { 4.0f - it * 0.2f + jitter[it] })

        assertEquals(Trend.APPROACHING, result.trend)
        assertNotNull(result.approachSpeedMps)
        assertNotNull(result.timeToCollisionMs)
        assertEquals(1.0f, requireNotNull(result.approachSpeedMps), 0.1f)
    }

    @Test
    fun singleWithinToleranceOutlierDoesNotCreateSpeedOrTtcForAStationaryObject() {
        // Seven readings near 3.0 m, then one 1.1 m low reading (inside the 1.2 m jump tolerance).
        val result = kinematics(listOf(3.0f, 3.02f, 2.98f, 3.01f, 2.99f, 3.0f, 3.02f, 1.9f))

        assertNull(result.approachSpeedMps)
        assertNull(result.timeToCollisionMs)
    }

    @Test
    fun alternatingSurfacesWithDriftDoNotCreateTtc() {
        // Two surfaces 0.9 m apart alternate while the fitted line drifts toward the camera.
        val result = kinematics(listOf(3.6f, 2.7f, 3.5f, 2.6f, 3.4f, 2.5f, 3.3f, 2.4f))

        assertNull(result.approachSpeedMps)
        assertNull(result.timeToCollisionMs)
    }

    @Test
    fun equalObservationErrorsGiveExactlyTheOrdinaryFit() {
        // S11-24: with equal per-observation errors the weighted fit is the existing fit.
        val jitter = listOf(0.03f, -0.02f, 0.01f, -0.03f, 0.02f, -0.01f, 0.03f, -0.02f)
        val distances = List(8) { 4.0f - it * 0.2f + jitter[it] }
        val plain = kinematics(distances)
        val equal = kinematics(distances, sigmas = List(8) { 0.1f })
        assertEquals(plain.approachSpeedMps, equal.approachSpeedMps)
        assertEquals(plain.timeToCollisionMs, equal.timeToCollisionMs)
        // A missing error on any sample also falls back to equal weights.
        assertEquals(plain.timeToCollisionMs, kinematics(distances, sigmas = List(8) { if (it == 3) null else 0.1f }).timeToCollisionMs)
    }

    @Test
    fun aCaptureWithAWideDepthSpreadCountsLessInTheSpeedFit() {
        // True approach 0.8 m/s; the second capture read 0.7 m short while its own depth spread was wide.
        val distances = listOf(5.0f, 4.1f, 4.6f, 4.4f, 4.2f)
        val unweighted = kinematics(distances, intervalMs = 250L)
        assertNull(unweighted.approachSpeedMps)
        assertNull(unweighted.timeToCollisionMs)
        val weighted = kinematics(distances, intervalMs = 250L, sigmas = listOf(0.05f, 1.0f, 0.05f, 0.05f, 0.05f))
        assertEquals(0.8f, requireNotNull(weighted.approachSpeedMps), 0.05f)
        assertNotNull(weighted.timeToCollisionMs)
        // The same wide-spread capture cannot manufacture an approach for a stationary object.
        val stationary = kinematics(listOf(3.0f, 2.3f, 3.0f, 3.0f, 3.0f), intervalMs = 250L,
            sigmas = listOf(0.05f, 1.0f, 0.05f, 0.05f, 0.05f))
        assertNull(stationary.timeToCollisionMs)
    }

    @Test
    fun theEstimatorRecordsTheCaptureDepthSpreadAsTheObservationError() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        // Raw depth rising 1.10..1.29 m across the columns: a non-zero sample spread.
        val depth = DepthImage16(40, 40, IntArray(40 * 40) { index -> 1_100 + (index % 40) * 5 })
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = 1L, rawDepth = depth,
            rawConfidence = ConfidenceImage8(40, 40, ByteArray(40 * 40) { 255.toByte() }),
            fullDepth = null, rawDepthTimestampNs = 1L, rawConfidenceTimestampNs = 1L, cameraImageTimestampNs = 1L,
        )
        val output = pipeline.processPrealignedForTest(snapshot, 1L, 1_000L,
            listOf(DetectionCandidate("person", 0.9f, RectNorm(0.3f, 0.2f, 0.4f, 0.6f)))).single()
        val iqr = requireNotNull(output.depthIqrM)
        assertTrue(iqr > 0f)
        val sigma = tracker.activeTracks().single().distanceHistory.last().distanceSigmaM
        assertEquals(iqr / 1.349f, requireNotNull(sigma), 1e-4f)
    }

    private fun geometry(): ObjectGeometry {
        val bbox = RectNorm(x = 0.4f, y = 0.35f, width = 0.2f, height = 0.30f)
        return ObjectGeometry(
            className = "person",
            detectionConfidence = 0.90f,
            bboxNorm = bbox,
            polygonNorm = bboxPolygon(bbox, erosionRatio = 0f),
            maskAreaNorm = bbox.area,
            centerNorm = bbox.center,
            bottomContactNorm = Point2(bbox.center.x, bbox.y + bbox.height),
        )
    }
}
