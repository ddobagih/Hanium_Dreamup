package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-3's 20/30/50/100-pixel comparison through the public sampler and estimator.
 * These characterize the current admission boundary; they do not calibrate a smaller-object
 * policy or establish field accuracy. Each capture has its own original depth-image timestamp.
 */
class ThinObjectRoiAreaComparisonRegressionTest {
    @Test
    fun equallyAccurateRawDepthShowsTheCurrentSmallRoiGuidanceBoundary() {
        val comparisons = ROI_PIXEL_COUNTS.map { pixels -> compare(pixels, DepthSource.ARCORE_RAW_DEPTH) }

        assertUnsupported(comparisons.single { it.roiPixels == 20 })
        comparisons.filter { it.roiPixels != 20 }.forEach {
            assertMeasured(it, DepthSource.ARCORE_RAW_DEPTH)
        }
        assertEquals(3, comparisons.count { it.outputs.last().source.metric })
    }

    @Test
    fun equallyAccurateFullDepthShowsTheCurrentSmallRoiGuidanceBoundary() {
        val comparisons = ROI_PIXEL_COUNTS.map { pixels -> compare(pixels, DepthSource.ARCORE_FULL_DEPTH) }

        comparisons.filter { it.roiPixels == 20 || it.roiPixels == 30 }.forEach(::assertUnsupported)
        comparisons.filter { it.roiPixels == 50 || it.roiPixels == 100 }.forEach {
            assertMeasured(it, DepthSource.ARCORE_FULL_DEPTH)
        }
        assertEquals(2, comparisons.count { it.outputs.last().source.metric })
    }

    private fun compare(roiPixels: Int, source: DepthSource): Comparison {
        val geometry = geometry(roiPixels)
        val depth = DepthImage16(IMAGE_SIZE, IMAGE_SIZE, IntArray(IMAGE_SIZE * IMAGE_SIZE) { DEPTH_MM })
        val confidence = ConfidenceImage8(IMAGE_SIZE, IMAGE_SIZE,
            ByteArray(IMAGE_SIZE * IMAGE_SIZE) { 255.toByte() })
        val polygon = mapper().imagePolygonToDepthPolygon(geometry.polygonNorm)
        // Default sampling preserves the product's erosion and budget. This is an input
        // precondition, evaluated before any estimator/source/message expectations.
        val sampled = DepthSampler().sampleObjectDepth(depth, confidence, polygon)
        val rejected = sampled.rejectedSamples
        val actualCandidates = sampled.validSampleCount + rejected.invalidZero + rejected.tooNear +
            rejected.tooFar + rejected.lowConfidence
        assertEquals("ROI fixture candidate count, not an estimator failure", roiPixels, actualCandidates)
        assertEquals("all supplied pixels have the same accurate depth", roiPixels, sampled.validSampleCount)
        assertEquals(1f, sampled.validSampleRatio, 0f)
        assertFalse(sampled.foregroundAmbiguous)
        sampled.medianM?.let { assertEquals(DEPTH_M, it, DEPTH_TOLERANCE_M) }
        sampled.spatialCoverage?.let { assertEquals(1f, it, 0f) }

        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val captureIds = mutableListOf<Long>()
        val outputs = (0 until CAPTURE_COUNT).map { capture ->
            val atMs = 1_000L + capture * 500L
            val imageTimestampNs = atMs * 1_000_000L + 1L
            captureIds += imageTimestampNs
            val track = tracker.update(listOf(geometry), atMs).single()
            estimator.estimate(ObjectDepthInput(
                frameId = capture.toLong(), timestampMs = atMs, geometry = geometry, track = track,
                mapper = mapper(),
                rawDepth = depth.takeIf { source == DepthSource.ARCORE_RAW_DEPTH },
                rawConfidence = confidence.takeIf { source == DepthSource.ARCORE_RAW_DEPTH },
                fullDepth = depth.takeIf { source == DepthSource.ARCORE_FULL_DEPTH },
                rawDepthMatchesCameraFrame = source == DepthSource.ARCORE_RAW_DEPTH,
                fullDepthMatchesCameraFrame = source == DepthSource.ARCORE_FULL_DEPTH,
                rawDepthTimestampNs = imageTimestampNs.takeIf { source == DepthSource.ARCORE_RAW_DEPTH },
                fullDepthTimestampNs = imageTimestampNs.takeIf { source == DepthSource.ARCORE_FULL_DEPTH },
                requireIndependentDepthObservation = true,
            )).also { output ->
                println("S11-3 sourceInput=$source roiPixels=$roiPixels capture=$capture " +
                    "imageTimestampNs=$imageTimestampNs candidates=$actualCandidates " +
                    "valid=${sampled.validSampleCount} validRatio=${sampled.validSampleRatio} " +
                    "maskAreaNorm=${geometry.maskAreaNorm} samplerMedianM=${sampled.medianM} " +
                    "samplerCoverage=${sampled.spatialCoverage} trackStable=${track.stable} " +
                    "outputSource=${output.source} outputValid=${output.validSampleCount} " +
                    "riskM=${output.riskDistanceM} confidence=${output.confidence.finalScore} " +
                    "level=${output.userFacing.messageLevel} message=${output.userFacing.message}")
            }
        }
        assertEquals("independent captures, without temporal pixel accumulation", CAPTURE_COUNT,
            captureIds.distinct().size)
        return Comparison(roiPixels, outputs)
    }

    private fun assertUnsupported(comparison: Comparison) {
        comparison.outputs.forEach { output ->
            assertFalse(output.source.metric)
            assertNull(output.riskDistanceM)
            assertNull(output.depthMedianM)
            assertEquals(DepthAvailability.UNAVAILABLE, output.depthAvailability)
            assertEquals(MessageLevel.NONE, output.userFacing.messageLevel)
            assertNull(output.userFacing.message)
        }
    }

    private fun assertMeasured(comparison: Comparison, expectedSource: DepthSource) {
        val stableOutput = comparison.outputs.last()
        assertEquals(expectedSource, stableOutput.source)
        assertEquals(DEPTH_M, requireNotNull(stableOutput.riskDistanceM), DEPTH_TOLERANCE_M)
        assertEquals(DEPTH_M, requireNotNull(stableOutput.depthMedianM), DEPTH_TOLERANCE_M)
        assertEquals(DepthAvailability.MEASURED, stableOutput.depthAvailability)
        assertEquals(MessageLevel.STOP, stableOutput.userFacing.messageLevel)
        val messages = comparison.outputs.mapNotNull { it.userFacing.message }
        assertTrue("a measured near obstacle must reach the message policy", messages.isNotEmpty())
        assertTrue(messages.first(), messages.first().contains("멈추세요."))
        comparison.outputs.filter { it.source.metric }.forEach { output ->
            assertEquals(expectedSource, output.source)
            assertEquals("each output still describes one capture's ROI", comparison.roiPixels,
                output.validSampleCount)
            assertEquals(1f, output.validSampleRatio, 0f)
            assertEquals(DEPTH_M, requireNotNull(output.riskDistanceM), DEPTH_TOLERANCE_M)
            assertFalse(output.spatialCoverageLimited)
        }
    }

    private fun geometry(roiPixels: Int): ObjectGeometry {
        val columns = roiPixels / ROI_ROWS
        val leftPixel = 30 - columns / 2
        val topPixel = 26
        val denominator = (IMAGE_SIZE - 1).toFloat()
        // Explicit half-pixel boundaries survive default sampler erosion while containing
        // exactly the intended pixel centers. No bbox fallback erosion is substituted.
        val left = (leftPixel - 0.5f) / denominator
        val right = (leftPixel + columns - 0.5f) / denominator
        val top = (topPixel - 0.5f) / denominator
        val bottom = (topPixel + ROI_ROWS - 0.5f) / denominator
        return MaskPolygonExtractor().extract(DetectionCandidate(
            className = "utility_or_streetlight_pole", detectionConfidence = 0.9f,
            bboxNorm = RectNorm(left, top, right - left, bottom - top),
            polygonNorm = listOf(Point2(left, top), Point2(right, top), Point2(right, bottom), Point2(left, bottom)),
        ))
    }

    private fun mapper() = LetterboxCoordinateMapper(
        transform = ModelInputTransform(IMAGE_SIZE, IMAGE_SIZE, IMAGE_SIZE, IMAGE_SIZE, 1f, 0f, 0f),
        depthSize = ImageSize(IMAGE_SIZE, IMAGE_SIZE),
    )

    private data class Comparison(val roiPixels: Int, val outputs: List<TrackedObjectDepth>)

    private companion object {
        val ROI_PIXEL_COUNTS = listOf(20, 30, 50, 100)
        const val IMAGE_SIZE = 64
        const val ROI_ROWS = 10
        const val CAPTURE_COUNT = 4
        const val DEPTH_MM = 1_000
        const val DEPTH_M = 1f
        const val DEPTH_TOLERANCE_M = 0.000001f
    }
}
