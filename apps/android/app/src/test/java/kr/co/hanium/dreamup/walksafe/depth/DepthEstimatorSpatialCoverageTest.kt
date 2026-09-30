package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-6: sample count, valid ratio and mask area do not show whether the kept depth covers the
 * object. The same count and ratio crowded into one side may be background, so that metric claim
 * is withdrawn; a thin object is judged along its length, not by its narrow width.
 */
class DepthEstimatorSpatialCoverageTest {
    @Test
    fun sameCountAndRatioKeepMetricWhenSpreadButAreDemotedWhenCrowdedIntoOneSide() {
        val bbox = RectNorm(0f, 0f, 1f, 1f)
        val spread = sample(30, 10, bbox) { x, _ -> x % 3 == 0 }
        val oneSided = sample(30, 10, bbox) { x, _ -> x < ONE_SIDED_COLUMNS }
        println("S11-6 spread=${spread.stats} oneSided=${oneSided.stats}")
        assertEquals(spread.stats.validSampleCount, oneSided.stats.validSampleCount)
        assertEquals(spread.stats.validSampleRatio, oneSided.stats.validSampleRatio, 0f)
        assertEquals(1f, requireNotNull(spread.stats.spatialCoverage), 0f)
        assertEquals(1f / 3f, requireNotNull(oneSided.stats.spatialCoverage), 1e-6f)

        val spreadOutput = lastOutput(30, 10, bbox) { x, _ -> x % 3 == 0 }
        assertTrue(spreadOutput.source.metric)
        assertNotNull(spreadOutput.riskDistanceM)
        val oneSidedOutput = lastOutput(30, 10, bbox) { x, _ -> x < ONE_SIDED_COLUMNS }
        assertFalse(oneSidedOutput.source.metric)
        assertNull(oneSidedOutput.riskDistanceM)
        assertNull(oneSidedOutput.timeToCollisionMs)
    }

    @Test
    fun thinObjectIsJudgedAlongItsLengthNotByItsWidth() {
        // Three depth pixels wide and forty tall.
        val bbox = RectNorm(18f / 40f, 0f, 3f / 40f, 1f)
        val whole = sample(40, 40, bbox) { _, _ -> true }
        assertEquals(1f, requireNotNull(whole.stats.spatialCoverage), 0f)
        assertTrue(lastOutput(40, 40, bbox) { _, _ -> true }.source.metric)

        // A longer thin object with enough kept samples, all crowded into its top third.
        val longBbox = RectNorm(18f / 40f, 0f, 4f / 40f, 1f)
        val oneEnd = sample(40, 60, longBbox) { _, y -> y < 20 }
        println("S11-6 thin oneEnd=${oneEnd.stats}")
        assertTrue(oneEnd.stats.validSampleCount >= 30)
        assertEquals(1f / 3f, requireNotNull(oneEnd.stats.spatialCoverage), 1e-6f)
        assertFalse(lastOutput(40, 60, longBbox) { _, y -> y < 20 }.source.metric)
        assertTrue(lastOutput(40, 60, longBbox) { _, _ -> true }.source.metric)
    }

    @Test
    fun aWideObjectIsAlsoJudgedAcrossItsShortAxis() {
        // S11-6 (11.txt:78-82, shape-specific rule): 30 x 20 depth pixels is wide (short >= long / 2).
        val bbox = RectNorm(0f, 0f, 1f, 1f)
        // Valid depth only in the top band: every long-axis third is covered, one short-axis third is.
        val band = sample(30, 20, bbox) { _, y -> y < 7 }
        assertTrue(band.stats.validSampleCount >= 30)
        assertEquals(1f / 3f, requireNotNull(band.stats.spatialCoverage), 1e-6f)
        assertFalse(lastOutput(30, 20, bbox) { _, y -> y < 7 }.source.metric)
        // Spread over both axes with the same kind of sparsity keeps the metric claim.
        val spread = sample(30, 20, bbox) { x, y -> x % 3 == 0 && y % 2 == 0 }
        assertEquals(1f, requireNotNull(spread.stats.spatialCoverage), 0f)
        assertTrue(lastOutput(30, 20, bbox) { x, y -> x % 3 == 0 && y % 2 == 0 }.source.metric)
        // A band along the long axis of an elongated (not wide) object keeps the long-axis rule only.
        val elongated = sample(30, 10, bbox) { _, y -> y < 4 }
        assertEquals(1f, requireNotNull(elongated.stats.spatialCoverage), 0f)
    }

    @Test
    fun coverageIsNotJudgedForEmptyOrInsufficientStatistics() {
        val none = sample(30, 10, RectNorm(0f, 0f, 1f, 1f)) { _, _ -> false }
        assertNull(none.stats.spatialCoverage)
        assertNull(DepthStats(0, 0f, null, null, null, null, null, null, null, 1f).spatialCoverage)
    }

    @Test
    fun aPoleWithNearDepthInOneThirdKeepsAWarningButNeverAStopMotionOrReport() {
        // S11-6-F1: a pole whose upper part has no depth (sky) has near Raw depth in one third.
        val pole = RectNorm(18f / 40f, 0f, 4f / 40f, 1f)
        val limited = outputs(40, 60, pole, POLE, 1_000) { _, y -> y >= 40 }
        limited.forEach { output ->
            println("S11-6-F1 limited pole level=${output.userFacing.messageLevel} source=${output.source} " +
                "risk=${output.riskDistanceM} ttc=${output.timeToCollisionMs} trend=${output.trend} " +
                "limited=${output.spatialCoverageLimited} msg=${output.userFacing.message}")
            assertTrue(output.source.metric)
            assertTrue(output.spatialCoverageLimited)
            assertEquals(1.0f, requireNotNull(output.riskDistanceM), 0.05f)
            assertEquals(MessageLevel.WARNING, output.userFacing.messageLevel)
            assertNull(output.timeToCollisionMs)
            assertEquals(Trend.UNKNOWN, output.trend)
            assertNull(output.prediction)
        }
        val spoken = requireNotNull(limited.first().userFacing.message)
        assertTrue(spoken, spoken.contains("멈출 준비를 하세요."))
        assertFalse(spoken, spoken.contains("멈추세요."))
        // The same pole with depth along its whole length is a full metric claim (STOP at 1 m).
        val covered = outputs(40, 60, pole, POLE, 1_000) { _, _ -> true }.last()
        assertFalse(covered.spatialCoverageLimited)
        assertEquals(MessageLevel.STOP, covered.userFacing.messageLevel)
    }

    @Test
    fun farDepthCrowdedIntoOneSideNeverJudgesTheObjectFar() {
        // S11-6-F1: one-sided far depth may be background seen beside the object.
        val bbox = RectNorm(0f, 0f, 1f, 1f)
        val oneSidedFar = outputs(30, 10, bbox, POLE, 6_000) { x, _ -> x < ONE_SIDED_COLUMNS }
        oneSidedFar.forEach { output ->
            assertFalse(output.source.metric)
            assertNull(output.riskDistanceM)
            assertFalse(output.spatialCoverageLimited)
            assertEquals(DepthAvailability.UNAVAILABLE, output.depthAvailability)
        }
        // Spread depth at the same distance is covered evidence and may judge the object far.
        val spreadFar = outputs(30, 10, bbox, POLE, 6_000) { x, _ -> x % 3 == 0 }.last()
        assertTrue(spreadFar.source.metric)
        assertEquals(6.0f, requireNotNull(spreadFar.riskDistanceM), 0.05f)
        assertEquals(MessageLevel.NONE, spreadFar.userFacing.messageLevel)
        // The warning boundary is the message policy's own warning distance, not a new number.
        val beyondWarning = outputs(30, 10, bbox, POLE, 2_800) { x, _ -> x < ONE_SIDED_COLUMNS }.last()
        assertFalse(beyondWarning.source.metric)
        val withinWarning = outputs(30, 10, bbox, POLE, 2_400) { x, _ -> x < ONE_SIDED_COLUMNS }.last()
        assertTrue(withinWarning.spatialCoverageLimited)
        assertEquals(MessageLevel.WARNING, withinWarning.userFacing.messageLevel)
    }

    @Test
    fun limitedCoverageIsNeverAReportSourceAndTactileClassesStayDemoted() {
        val bbox = RectNorm(0f, 0f, 1f, 1f)
        val damaged = "damaged_linear_tactile_paving"
        // Tactile paving is not an obstacle warning: a one-sided distance stays demoted.
        assertFalse(outputs(30, 10, bbox, damaged, 1_500) { x, _ -> x < ONE_SIDED_COLUMNS }.last().source.metric)
        val covered = outputs(30, 10, bbox, damaged, 1_500) { x, _ -> x % 3 == 0 }.last()
        assertTrue(covered.source.metric)
        assertNotNull(kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy.selectReportOutput(listOf(covered)))
        val limited = covered.copy(spatialCoverageLimited = true)
        assertNull(kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy.selectReportOutput(listOf(limited)))
        assertNull(kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(limited)))
    }

    private fun outputs(
        width: Int, height: Int, bbox: RectNorm, className: String, depthMm: Int, valid: (Int, Int) -> Boolean,
    ): List<TrackedObjectDepth> {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate(className, 0.9f, bbox))
        val depth = DepthImage16(width, height, IntArray(width * height) { index ->
            if (valid(index % width, index / width)) depthMm else 0
        })
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        return (0 until 4).map { index ->
            val at = index * 500L
            val track = tracker.update(listOf(geometry), at).single()
            estimator.estimate(
                ObjectDepthInput(
                    frameId = index.toLong(), timestampMs = at, geometry = geometry, track = track,
                    mapper = mapper(width, height), rawDepth = depth, rawConfidence = confidence,
                    rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = at * 1_000_000L + 1L,
                ),
            )
        }
    }

    private data class Sampled(val stats: DepthStats)

    private fun sample(width: Int, height: Int, bbox: RectNorm, valid: (Int, Int) -> Boolean): Sampled {
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.9f, bbox))
        val polygon = mapper(width, height).imagePolygonToDepthPolygon(geometry.polygonNorm)
        return Sampled(DepthSampler().sampleObjectDepth(
            depthImage = depthImage(width, height, valid),
            confidenceImage = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() }),
            polygonDepthNorm = polygon,
            options = DepthSampleOptions(minValidSamples = 30, minConfidence = 0.35f),
        ))
    }

    private fun lastOutput(width: Int, height: Int, bbox: RectNorm, valid: (Int, Int) -> Boolean): TrackedObjectDepth {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.9f, bbox))
        val depth = depthImage(width, height, valid)
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        var latest: TrackedObjectDepth? = null
        (0 until 4).forEach { index ->
            val at = index * 500L
            val track = tracker.update(listOf(geometry), at).single()
            latest = estimator.estimate(
                ObjectDepthInput(
                    frameId = index.toLong(), timestampMs = at, geometry = geometry, track = track,
                    mapper = mapper(width, height), rawDepth = depth, rawConfidence = confidence,
                    rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = at * 1_000_000L + 1L,
                ),
            )
        }
        return requireNotNull(latest)
    }

    private fun depthImage(width: Int, height: Int, valid: (Int, Int) -> Boolean) =
        DepthImage16(width, height, IntArray(width * height) { index ->
            if (valid(index % width, index / width)) 3_000 else 0
        })

    private fun mapper(width: Int, height: Int) = LetterboxCoordinateMapper(
        transform = ModelInputTransform(
            imageWidth = width, imageHeight = height, modelWidth = width, modelHeight = height,
            scale = 1f, padX = 0f, padY = 0f,
        ),
        depthSize = ImageSize(width, height),
    )

    private companion object {
        const val ONE_SIDED_COLUMNS = 11
        const val POLE = "utility_or_streetlight_pole"
    }
}
