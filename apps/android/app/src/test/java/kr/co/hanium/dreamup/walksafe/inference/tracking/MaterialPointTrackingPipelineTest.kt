package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic estimator seam: exercises the real tracker/admission/depth ledger, not native LK. */
class MaterialPointTrackingPipelineTest {
    @Test fun movingPointSurvivesNewDetectorSourcesEvery250Ms() {
        val fixture = Fixture(imageSpeedNormPerSec = .1f)
        val rows = fixture.run()
        val latest = rows.last().output
        assertEquals(ObjectMovementDirection.RIGHT, latest.motionEstimate.direction)
        assertEquals(3f / .7f * .1f, latest.motionEstimate.objectSpeedMps!!, .001f)
        assertEquals(1, rows.mapNotNull { it.row.materialPoint?.id }.distinct().size)
        assertTrue(rows.map { it.row.detectorSourceKey }.distinct().size >= 7)
        assertEquals(1, rows.map { it.output.trackId }.distinct().size)
        assertTrue(fixture.flow.inputCounts.all { it <= 40 })
        assertTrue(fixture.flow.inputCounts.any { it == 40 })
        assertTrue(rows.all { it.row.originalFeatureCount == 39 })
    }

    @Test fun stationaryPointDoesNotFollowJitteringDetectorBoxCenters() {
        val rows = Fixture(imageSpeedNormPerSec = 0f, jitter = true).run()
        assertEquals(ObjectMovementDirection.STATIONARY, rows.last().output.motionEstimate.direction)
        assertEquals(0f, rows.last().output.motionEstimate.objectSpeedMps!!, .001f)
        assertEquals(1, rows.mapNotNull { it.row.materialPoint?.id }.distinct().size)
        assertTrue(rows.map { it.row.geometry!!.bboxNorm.center.x }.distinct().size > 1)
    }

    @Test fun actualImagePointAndCameraTranslationKeepStationaryWorldPointStationary() {
        val rows = Fixture(imageSpeedNormPerSec = -.07f, cameraSpeedMps = .3f).run()
        val motion = rows.last().output.motionEstimate
        assertEquals(ObjectMovementDirection.STATIONARY, motion.direction)
        assertEquals(0f, motion.objectSpeedMps!!, .001f)
        assertEquals(.3f, motion.cameraVelocityInAnchorMps!!.x, .001f)
        assertEquals(-.3f, motion.relativeVelocityInAnchorMps!!.x, .001f)
    }

    @Test fun pointLossRestartsSpatialHistoryButPreservesRawProximityAndMetricSamples() {
        val fixture = Fixture(.1f, pointLostAt = 2_000L)
        val rows = fixture.run()
        val before = rows.first { it.at == 1_950L }
        val lost = rows.first { it.at == 2_000L }
        val fresh = rows.first { it.at == 2_100L }
        assertEquals(ObjectMovementDirection.RIGHT, before.output.motionEstimate.direction)
        assertNull(lost.row.materialPoint)
        assertUnknown(lost.output)
        assertUnknown(fresh.output)
        assertNull(rows.first { it.at == 2_050L }.row.materialPoint)
        assertUnknown(rows.first { it.at == 2_050L }.output)
        assertNotSame(before.row.materialPoint!!.id, fresh.row.materialPoint!!.id)
        assertMeasuredProximity(lost.output)
        assertMeasuredProximity(fresh.output)
        assertEquals(12, fixture.identities.activeTracks().single().distanceHistory.size)
        assertEquals(ObjectMovementDirection.RIGHT, rows.last().output.motionEstimate.direction)
    }

    @Test fun missingDepthDuringPointLossCannotResumeOldSpatialIdentity() {
        val rows = Fixture(.1f, pointLostAt = 2_000L, rawMissingAt = 2_000L).run()
        assertUnknown(rows.first { it.at == 2_000L }.output)
        assertUnknown(rows.first { it.at == 2_050L }.output)
        assertNotSame(rows.first { it.at == 1_950L }.row.materialPoint!!.id,
            rows.first { it.at == 2_100L }.row.materialPoint!!.id)
        assertEquals(ObjectMovementDirection.RIGHT, rows.last().output.motionEstimate.direction)
    }

    @Test fun invalidCenterPixelCannotBorrowValidNeighborDepth() {
        val rows = Fixture(.1f, invalidPointAt = 2_200L).run()
        val invalid = rows.first { it.at == 2_200L }.output
        assertUnknown(invalid)
        assertMeasuredProximity(invalid)
        assertUnknown(rows.first { it.at == 2_250L }.output)
    }

    @Test fun pointOnDifferentDepthSurfaceCannotBorrowObjectMedian() {
        val rows = Fixture(.1f, differentPointDepthAt = 2_200L).run()
        val invalid = rows.first { it.at == 2_200L }.output
        assertUnknown(invalid)
        assertMeasuredProximity(invalid)
    }

    @Test fun existingObjectDepthRangeStillAllowsMotionBeyondContactRange() {
        val rows = Fixture(.05f, depthMm = 6_000).run()
        assertEquals(ObjectMovementDirection.RIGHT, rows.last().output.motionEstimate.direction)
        assertEquals(6f / .7f * .05f, rows.last().output.motionEstimate.objectSpeedMps!!, .001f)
    }

    @Test fun materialPointSamplerPreservesExistingSubHalfMeterObjectRange() {
        val sampler = DepthSampler()
        val polygon = bboxPolygon(RectNorm(.1f, .1f, .8f, .8f), 0f)
        val depth = DepthImage16(20, 20, IntArray(400) { 300 })
        val confidence = ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() })
        assertEquals(.3f, sampler.sampleMaterialPointDepth(depth, confidence, polygon, Point2(.5f, .5f), .35f)!!, .001f)
        assertNull(sampler.sampleContactDepth(depth, confidence, polygon, Point2(.5f, .5f), .35f))
    }

    @Test fun sourceWithoutExactCommonFrameRestartsInsteadOfExtrapolatingOldPoint() {
        val rows = Fixture(.1f, publishDelayMs = 0L).run()
        assertTrue(rows.mapNotNull { it.row.materialPoint?.id }.distinct().size > 1)
        assertUnknown(rows.last().output)
        assertMeasuredProximity(rows.last().output)
    }

    @Test fun auxiliaryPointLeavingObjectDuringBackfillCannotReturnWithOldIdentity() {
        val flow = Flow(0f, pointOutsideAt = 1_150L)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val source = key(1_000L)
        for (at in 1_000L..1_100L step 50) tracker.offerFrame(frame(at))
        val first = tracker.trackFrom(source, listOf(candidate(0f)), key(1_100L)).observations.single()
        assertNotNull(first.materialPoint)
        tracker.offerFrame(frame(1_150L)); tracker.offerFrame(frame(1_200L))
        val backfilled = tracker.trackFrom(source, listOf(candidate(0f)), key(1_200L)).observations.single()
        assertEquals(VisualTrackingStatus.TRACKED, backfilled.status)
        assertTrue(flow.recoveryX / 100f in candidate(0f).bboxNorm.x..(candidate(0f).bboxNorm.x + candidate(0f).bboxNorm.width))
        assertNull(backfilled.materialPoint)
    }

    @Test fun intermediatePeerOverlapRetiresPointEvenWhenFinalBoxesAreSeparate() {
        val flow = Flow(0f, overlappingPeerAt = 1_150L)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val detections = listOf(candidate(0f), DetectionCandidate("bicycle", .9f, RectNorm(.1f, .35f, .12f, .3f)))
        for (at in 1_000L..1_100L step 50) tracker.offerFrame(frame(at))
        val first = tracker.trackFrom(key(1_000L), detections, key(1_100L))
        assertNotNull(first.observations.first().materialPoint)
        tracker.offerFrame(frame(1_150L)); tracker.offerFrame(frame(1_200L))
        val result = tracker.trackFrom(key(1_000L), detections, key(1_200L))
        assertTrue(result.observations.all { it.status == VisualTrackingStatus.TRACKED })
        assertNull(result.observations.first().materialPoint)
        assertTrue(result.observations[1].geometry!!.bboxNorm.x < .2f)
    }

    @Test fun sixGeometryFeaturesRemainValidWithoutInventingAnAuxiliaryPoint() {
        val flow = Flow(0f, seedCount = 6)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(maxFeaturesPerObject = 6), { 0L }, flow)
        tracker.offerFrame(frame(1_000L)); tracker.offerFrame(frame(1_050L))
        val row = tracker.trackFrom(key(1_000L), listOf(candidate(0f)), key(1_050L)).observations.single()
        assertEquals(VisualTrackingStatus.TRACKED, row.status)
        assertEquals(6, row.originalFeatureCount)
        assertNull(row.materialPoint)
        assertEquals(listOf(6), flow.inputCounts)
    }

    private class Fixture(
        val imageSpeedNormPerSec: Float,
        val cameraSpeedMps: Float = 0f,
        val jitter: Boolean = false,
        pointLostAt: Long? = null,
        val rawMissingAt: Long? = null,
        val invalidPointAt: Long? = null,
        val differentPointDepthAt: Long? = null,
        val depthMm: Int = 3_000,
        val publishDelayMs: Long = 100L,
    ) {
        val flow = Flow(imageSpeedNormPerSec, pointLostAt)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)
        fun run(): List<Seen> {
            val rows = mutableListOf<Seen>()
            for (at in 1_000L..3_000L step 50) {
                val target = key(at)
                assertTrue(tracker.offerFrame(frame(at)).accepted)
                if (at < 1_000L + publishDelayMs) continue
                val sourceAt = 1_000L + ((at - 1_000L - publishDelayMs) / 250L) * 250L
                val jitterNorm = if (jitter) if (((sourceAt - 1_000L) / 250L) % 2L == 0L) -.006f else .006f else 0f
                val detection = candidate(imageSpeedNormPerSec * (sourceAt - 1_000L) / 1_000f + jitterNorm)
                val result = tracker.trackFrom(key(sourceAt), listOf(detection), target)
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
                    listOf(detection), true, target.capturedAtElapsedRealtimeMs))
                val row = result.observations.single()
                val millimeters = IntArray(10_000) { if (at == rawMissingAt) 0 else depthMm }
                row.materialPoint?.imagePointNorm?.let { point ->
                    val x = kotlin.math.round(point.x * 99).toInt()
                    val y = kotlin.math.round(point.y * 99).toInt()
                    if (at == invalidPointAt) millimeters[y * 100 + x] = 0
                    if (at == differentPointDepthAt) millimeters[y * 100 + x] = 6_000
                }
                val capture = DepthFrameSnapshot(target.frameId, DepthImage16(100, 100, millimeters),
                    ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
                    rawDepthTimestampNs = target.cameraTimestampNs, rawConfidenceTimestampNs = target.cameraTimestampNs,
                    cameraImageTimestampNs = target.cameraTimestampNs,
                    cameraPoseEvidence = CameraPoseEvidence(42L, at, cameraSpeedMps * (at - 1_000L) / 1_000f,
                        0f, 0f, 0f, 0f, -1f, imageProjection = CameraImageProjection(1_000, 1_000,
                            700f, 700f, 500f, 500f, 1f, 0f, 0f, 0f, 1f, 0f)))
                val output = pipeline.processTrackedObservation(capture, observation, mapper,
                    target.frameId, target.capturedAtElapsedRealtimeMs).single()
                rows += Seen(at, row, output)
            }
            return rows
        }
    }

    private class Flow(val speed: Float, val pointLostAt: Long? = null, val pointOutsideAt: Long? = null, val seedCount: Int = 40, val overlappingPeerAt: Long? = null) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        val inputCounts = mutableListOf<Int>()
        val recoveryX = 49f
        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature> =
            (0 until seedCount).map { index ->
                val box = detection.bboxNorm
                VisualMotionFeature(((box.x + box.width * (.1f + (index % 8) * .8f / 7f)) * frame.width).toInt(),
                    ((box.y + box.height * (.1f + (index / 8) * .8f / 4f)) * frame.height).toInt())
            }
        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            inputCounts += features.size + if (materialPoint == null) 0 else 1
            val at = target.key.frameId / 1_000_000L
            fun displacement(frame: GrayTrackingFrame): Float = if (detection.className == "bicycle" &&
                frame.key.frameId / 1_000_000L == overlappingPeerAt) .32f else
                speed * (frame.key.frameId - source.key.frameId) / 1_000_000_000f
            val dx = displacement(target)
            val edgeDx = dx - displacement(previous)
            val point = materialPoint?.takeUnless { at == pointLostAt }?.let {
                it.copy(x = when {
                    at == pointOutsideAt -> 99f
                    pointOutsideAt != null && at == pointOutsideAt + 50L -> recoveryX
                    else -> it.x + edgeDx * target.width
                })
            }
            return VisualMotionEstimate(features.map { it.copy(currentX = it.sourceX + dx * target.width) },
                VisualAffineTransform(tx = dx), .95f, 0f, materialPoint = point)
        }
    }

    private data class Seen(val at: Long, val row: VisualTrackingObservation, val output: TrackedObjectDepth)
    private fun assertUnknown(output: TrackedObjectDepth) {
        assertEquals(ObjectMovementDirection.UNKNOWN, output.motionEstimate.direction)
        assertNull(output.motionEstimate.objectVelocityInAnchorMps)
    }
    private fun assertMeasuredProximity(output: TrackedObjectDepth) {
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(3f, output.riskDistanceM!!, .001f)
        assertEquals(Trend.STABLE, output.trend)
    }
    private companion object {
        fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L, 10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L)
        fun frame(at: Long) = GrayTrackingFrame.copyOf(key(at), 100, 100, ByteArray(10_000))
        fun candidate(dx: Float) = DetectionCandidate("car", .95f, RectNorm(.38f + dx, .35f, .24f, .3f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(1_000, 1_000, 1_000, 1_000, 1f, 0f, 0f), ImageSize(100, 100))
    }
}
