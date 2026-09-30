package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-28 (originals/11.txt:258-262): "may this distance join the trend" (a pending jump may not) is
 * separate from "is there reason to suspect a near hazard now". A same-frame confident Raw reading
 * that jumped nearer inside the warning distance, together with a visual approach, gives only the
 * existing qualitative caution: no distance, steps or TTC. Depth contamination alone stays silent.
 */
class JumpPendingQualitativeCautionTest {
    @Test
    fun aNearJumpWithAVisualApproachGivesOnlyAQualitativeCaution() {
        val last = sequence(className = "person", growing = true, lastDepthMm = 1_200)
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, last.source)
        assertEquals(Trend.APPROACHING, last.trend)
        assertEquals(MessageLevel.CAUTION, last.userFacing.messageLevel)
        val message = requireNotNull(last.userFacing.message)
        assertFalse(message, message.any(Char::isDigit))
        assertNull(last.userFacing.stepsAhead)
        assertNull(last.riskDistanceM)
        assertNull(last.zDistanceM)
        assertNull(last.timeToCollisionMs)
        assertNull(last.approachSpeedMps)
        assertTrue(last.confidence.hardGate > 0f)
    }

    @Test
    fun theSameNearJumpWithoutAVisualApproachStaysSilent() {
        val last = sequence(className = "person", growing = false, lastDepthMm = 1_200)
        assertEquals(MessageLevel.NONE, last.userFacing.messageLevel)
        assertEquals(0f, last.confidence.hardGate, 0f)
        assertNull(last.riskDistanceM)
    }

    @Test
    fun jumpsBeyondTheWarningDistanceFartherJumpsAndNonObstaclesStaySilent() {
        // Nearer by 1.5 m but still beyond the 2.5 m warning distance.
        assertEquals(MessageLevel.NONE, sequence("person", growing = true, lastDepthMm = 3_500, steadyMm = 5_000).userFacing.messageLevel)
        // A farther jump is not a near-hazard suspicion.
        assertEquals(MessageLevel.NONE, sequence("person", growing = true, lastDepthMm = 2_400, steadyMm = 1_000).userFacing.messageLevel)
        // Tactile paving is not an obstacle warning.
        assertEquals(MessageLevel.NONE, sequence("linear_tactile_paving", growing = true, lastDepthMm = 1_200).userFacing.messageLevel)
    }

    /** Four accepted readings at [steadyMm], then one same-frame Raw reading at [lastDepthMm]. */
    private fun sequence(className: String, growing: Boolean, lastDepthMm: Int, steadyMm: Int = 3_000): TrackedObjectDepth {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val confidence = ConfidenceImage8(40, 40, ByteArray(40 * 40) { 255.toByte() })
        var last: TrackedObjectDepth? = null
        (0..4).forEach { index ->
            val at = 1_000L + index * 300L
            val grow = if (growing) index * 0.04f else 0f
            val bbox = RectNorm(0.40f - grow / 2f, 0.30f, 0.20f + grow, 0.40f + grow)
            val geometry = MaskPolygonExtractor().extract(DetectionCandidate(className, 0.9f, bbox))
            val track = tracker.update(listOf(geometry), at).single()
            val depthMm = if (index == 4) lastDepthMm else steadyMm
            last = estimator.estimate(
                ObjectDepthInput(
                    frameId = at * 1_000_000L, timestampMs = at, geometry = geometry, track = track,
                    mapper = mapper, rawDepth = DepthImage16(40, 40, IntArray(40 * 40) { depthMm }),
                    rawConfidence = confidence, rawDepthMatchesCameraFrame = true,
                    rawDepthTimestampNs = at * 1_000_000L + 1L,
                ),
            )
            if (index < 4) assertTrue("reading $index accepted", requireNotNull(last).source.metric)
        }
        return requireNotNull(last)
    }

    private val mapper = LetterboxCoordinateMapper(
        transform = ModelInputTransform(
            imageWidth = 40, imageHeight = 40, modelWidth = 40, modelHeight = 40, scale = 1f, padX = 0f, padY = 0f,
        ),
        depthSize = ImageSize(40, 40),
    )
}
