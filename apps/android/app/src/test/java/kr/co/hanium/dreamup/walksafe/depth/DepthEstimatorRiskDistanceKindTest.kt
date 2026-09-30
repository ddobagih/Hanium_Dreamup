package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * S11-9: ARCore depth is optical-axis Z. With a chest camera 1.3 m high and tilted 20 degrees
 * down, a ground object ahead has a Z larger than its horizontal range, so Z alone overstates the
 * user's distance. The risk distance takes the smallest pose-supported interpretation and names it.
 */
class DepthEstimatorRiskDistanceKindTest {
    @Test
    fun forwardGroundObjectUsesTheShorterHorizontalRange() {
        val output = estimate(RectNorm(0.3f, 0.3f, 0.4f, 0.4f), tiltedPose(gravity = true))
        assertEquals(Z_M, requireNotNull(output.zDistanceM), 1e-4f)
        val expected = (Z_M * cos(TILT_RAD)).toFloat()
        assertEquals(expected, requireNotNull(output.horizontalDistanceM), 1e-3f)
        assertEquals(expected, requireNotNull(output.riskDistanceM), 1e-3f)
        assertEquals(RiskDistanceKind.HORIZONTAL_RANGE, output.riskDistanceKind)
        assertTrue(output.riskDistanceM!! < output.zDistanceM!!)
    }

    @Test
    fun lateralObjectWhoseHorizontalRangeExceedsZKeepsCameraZ() {
        val output = estimate(RectNorm(0.8f, 0.3f, 0.2f, 0.4f), tiltedPose(gravity = true))
        val lateral = (0.9f * 10f - 5f) / 7f
        val expectedHorizontal = (Z_M * sqrt(cos(TILT_RAD) * cos(TILT_RAD) + lateral * lateral)).toFloat()
        assertEquals(expectedHorizontal, requireNotNull(output.horizontalDistanceM), 1e-3f)
        assertTrue(output.horizontalDistanceM!! > Z_M)
        assertEquals(Z_M, requireNotNull(output.riskDistanceM), 1e-4f)
        assertEquals(RiskDistanceKind.CAMERA_Z, output.riskDistanceKind)
    }

    @Test
    fun withoutPoseOrGravityTheRiskDistanceIsTheUnchangedCameraZ() {
        listOf(null, tiltedPose(gravity = false)).forEach { pose ->
            val output = estimate(RectNorm(0.3f, 0.3f, 0.4f, 0.4f), pose)
            assertEquals("$pose", Z_M, requireNotNull(output.riskDistanceM), 1e-4f)
            assertEquals("$pose", RiskDistanceKind.CAMERA_Z, output.riskDistanceKind)
            assertNull("$pose", output.horizontalDistanceM)
        }
    }

    @Test
    fun trackingHistoryKeepsTheZSeriesSoAPoseGapCannotFakeApproach() {
        val tracker = ObjectTracker()
        val output = estimate(RectNorm(0.3f, 0.3f, 0.4f, 0.4f), tiltedPose(gravity = true), tracker)
        assertEquals(RiskDistanceKind.HORIZONTAL_RANGE, output.riskDistanceKind)
        val track = tracker.activeTracks().single()
        assertTrue(track.distanceHistory.isNotEmpty())
        track.distanceHistory.forEach { assertEquals(Z_M, it.distanceM, 1e-4f) }
    }

    @Test
    fun pseudoDepthHasNoRiskDistanceKind() {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.9f, RectNorm(0.3f, 0.3f, 0.4f, 0.4f)))
        val track = tracker.update(listOf(geometry), 0L).single()
        val output = estimator.estimate(ObjectDepthInput(0L, 0L, geometry, track, mapper))
        assertNull(output.riskDistanceM)
        assertNull(output.riskDistanceKind)
    }

    private fun estimate(
        bbox: RectNorm,
        pose: ((Long) -> CameraPoseEvidence)?,
        tracker: ObjectTracker = ObjectTracker(),
    ): TrackedObjectDepth {
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.9f, bbox))
        var latest: TrackedObjectDepth? = null
        (0 until 4).forEach { index ->
            val at = 1_000L + index * 500L
            val track = tracker.update(listOf(geometry), at).single()
            latest = estimator.estimate(
                ObjectDepthInput(
                    frameId = index.toLong(), timestampMs = at, geometry = geometry, track = track, mapper = mapper,
                    rawDepth = DepthImage16(40, 40, IntArray(1_600) { (Z_M * 1_000f).toInt() }),
                    rawConfidence = ConfidenceImage8(40, 40, ByteArray(1_600) { 255.toByte() }),
                    rawDepthMatchesCameraFrame = true,
                    rawDepthTimestampNs = at * 1_000_000L,
                    motionContext = MotionContext(cameraPoseEvidence = pose?.invoke(at)),
                ),
            )
        }
        return requireNotNull(latest)
    }

    /** Camera 1.3 m above the anchor floor, looking toward -Z and pitched 20 degrees down. */
    private fun tiltedPose(gravity: Boolean): (Long) -> CameraPoseEvidence = { at ->
        val s = sin(TILT_RAD).toFloat()
        val c = cos(TILT_RAD).toFloat()
        CameraPoseEvidence(
            referenceId = 1L, timestampMs = at,
            positionX = 0f, positionY = 1.3f, positionZ = 0f,
            forwardX = 0f, forwardY = -s, forwardZ = -c,
            imageProjection = CameraImageProjection(
                imageWidth = 10, imageHeight = 10, fx = 7f, fy = 7f, cx = 5f, cy = 5f,
                rightX = 1f, rightY = 0f, rightZ = 0f, upX = 0f, upY = c, upZ = -s,
            ),
            gravityUpInAnchor = if (gravity) Vec3(0f, 1f, 0f) else null,
        )
    }

    private val mapper = LetterboxCoordinateMapper(
        transform = ModelInputTransform(
            imageWidth = 10, imageHeight = 10, modelWidth = 10, modelHeight = 10,
            scale = 1f, padX = 0f, padY = 0f,
        ),
        depthSize = ImageSize(40, 40),
    )

    private companion object {
        const val Z_M = 3.8f
        val TILT_RAD = Math.toRadians(20.0)
    }
}
