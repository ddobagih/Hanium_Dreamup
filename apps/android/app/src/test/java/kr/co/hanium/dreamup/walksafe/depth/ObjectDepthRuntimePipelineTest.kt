package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDepthRuntimePipelineTest {
    @Test
    fun defaultPipelineDoesNotEmitDebugObjectsWithoutDetector() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        val outputs = pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L)

        assertTrue(outputs.isEmpty())
    }

    @Test
    fun runtimePipelineAcceptsSameFrameDetectionsFromCameraImagePath() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        val outputs = pipeline.process(
            snapshot = snapshot,
            frameId = 1L,
            timestampMs = 1_000L,
            detections = onePersonProvider().detect(frameId = 1L, timestampMs = 1_000L),
        )

        assertEquals(1, outputs.size)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, outputs.first().source)
    }

    @Test
    fun runtimePipelineConnectsDetectionTrackerAndRawDepthEstimator() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        val outputs = pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L)

        assertEquals(1, outputs.size)
        assertEquals("person", outputs.first().className)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, outputs.first().source)
        assertEquals(1.2f, outputs.first().depthMedianM!!, 0.01f)
        assertEquals(1, outputs.first().trackAgeFrames)
        assertTrue(outputs.first().trackId.startsWith("track-"))
    }

    @Test
    fun runtimePipelineFallsBackToFullDepthWhenRawConfidenceIsRejected() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 0, fullMm = 1_800)

        val output = pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L).single()

        assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
        assertEquals(1.8f, output.depthMedianM!!, 0.01f)
    }

    @Test
    fun runtimePipelineUsesInjectedMapperForDepthSampling() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        val output = pipeline.process(
            snapshot = snapshot,
            frameId = 1L,
            timestampMs = 1_000L,
            mapper = emptyDepthMapper(),
        ).single()

        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
        assertNull(output.depthMedianM)
    }

    @Test
    fun runtimePipelineUsesPseudoTrendWithoutMetricDistanceWhenDepthIsUnavailable() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = DepthFrameSnapshot(frameTimestampNs = 1L, rawDepth = null, rawConfidence = null, fullDepth = null)

        val output = pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L).single()

        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
        assertNull(output.riskDistanceM)
        assertNull(output.userFacing.stepsAhead)
    }

    @Test
    fun runtimePipelinePassesMotionContextIntoDepthConfidence() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        val output = pipeline.process(
            snapshot = snapshot,
            frameId = 1L,
            timestampMs = 1_000L,
            motionContext = MotionContext(motionQuality = 0.8f, routeAlignmentQuality = 0.35f, freshnessQuality = 0.75f),
        ).single()

        assertEquals(0.35f, output.confidence.motionQuality, 0.001f)
        assertEquals(0.75f, output.confidence.freshnessQuality, 0.001f)
    }

    @Test
    fun runtimePipelineDoesNotEmitStaleObjectWhenDetectorMissesNextFrame() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = oneThenMissProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        assertEquals(1, pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L).size)

        val outputsAfterMiss = pipeline.process(snapshot, frameId = 2L, timestampMs = 1_100L)

        assertTrue(outputsAfterMiss.isEmpty())
    }

    @Test
    fun runtimePipelineDoesNotEstimatePartiallyMissedTracks() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = twoThenOneProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)

        assertEquals(2, pipeline.process(snapshot, frameId = 1L, timestampMs = 1_000L).size)

        val outputsAfterPartialMiss = pipeline.process(snapshot, frameId = 2L, timestampMs = 1_100L)

        assertEquals(1, outputsAfterPartialMiss.size)
        assertEquals("normal_tactile_block", outputsAfterPartialMiss.single().className)
    }

    @Test
    fun repeatedArFramesDoNotCountOneDetectorSnapshotAsThreeObservations() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)
        val detection = onePersonProvider().detect(frameId = 10L, timestampMs = 1_000L)

        val first = pipeline.process(
            snapshot = snapshot,
            frameId = 1L,
            timestampMs = 1_000L,
            detections = detection,
            detectionSequenceId = 10L,
        ).single()
        val repeated = pipeline.process(
            snapshot = snapshot,
            frameId = 2L,
            timestampMs = 1_800L,
            detections = detection,
            detectionSequenceId = 10L,
        ).single()

        assertEquals(1, first.trackAgeFrames)
        assertEquals(1, repeated.trackAgeFrames)
        assertEquals(800L, repeated.trackStableMs)
    }

    @Test
    fun stabilityAdvancesOnlyAfterThreeUniqueCompletedInferenceSnapshots() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 2_000)
        val detection = onePersonProvider().detect(frameId = 10L, timestampMs = 1_000L)

        val partial = pipeline.process(
            snapshot = snapshot,
            frameId = 1L,
            timestampMs = 900L,
            detections = detection,
            detectionSequenceId = 10L,
            detectionCompleted = false,
        )
        val first = pipeline.process(snapshot, 2L, 1_000L, detection, detectionSequenceId = 10L).single()
        val second = pipeline.process(snapshot, 3L, 1_400L, detection, detectionSequenceId = 11L).single()
        val third = pipeline.process(snapshot, 4L, 1_800L, detection, detectionSequenceId = 12L).single()

        assertTrue(partial.isEmpty())
        assertEquals(1, first.trackAgeFrames)
        assertEquals(2, second.trackAgeFrames)
        assertEquals(3, third.trackAgeFrames)
        assertEquals(800L, third.trackStableMs)
    }

    private fun onePersonProvider(): DetectionProvider {
        return object : DetectionProvider {
            override fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate> {
                return listOf(
                    DetectionCandidate(
                        className = "person",
                        detectionConfidence = 0.9f,
                        bboxNorm = RectNorm(0.35f, 0.20f, 0.30f, 0.55f),
                    ),
                )
            }
        }
    }

    private fun oneThenMissProvider(): DetectionProvider {
        var count = 0
        return object : DetectionProvider {
            override fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate> {
                count += 1
                return if (count == 1) onePersonProvider().detect(frameId, timestampMs) else emptyList()
            }
        }
    }

    private fun twoThenOneProvider(): DetectionProvider {
        var count = 0
        return object : DetectionProvider {
            override fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate> {
                count += 1
                val tactile = DetectionCandidate(
                    className = "normal_tactile_block",
                    detectionConfidence = 0.8f,
                    bboxNorm = RectNorm(0.45f, 0.55f, 0.20f, 0.25f),
                )
                if (count == 1) {
                    return onePersonProvider().detect(frameId, timestampMs) + tactile
                }
                return listOf(tactile)
            }
        }
    }

    private fun emptyDepthMapper(): CoordinateMapper {
        return object : CoordinateMapper {
            override fun modelToImage(point: Point2): Point2 = point

            override fun imageToModel(point: Point2): Point2 = point

            override fun imageToDepth(point: Point2): Point2? = null

            override fun depthToImage(point: Point2): Point2? = null

            override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> = emptyList()

            override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3? = null
        }
    }

    private fun snapshot(rawMm: Int, rawConfidence: Int, fullMm: Int): DepthFrameSnapshot {
        return DepthFrameSnapshot(
            frameTimestampNs = 1L,
            rawDepth = depthImage(rawMm),
            rawConfidence = ConfidenceImage8(40, 40, ByteArray(40 * 40) { rawConfidence.toByte() }),
            fullDepth = depthImage(fullMm),
        )
    }

    private fun depthImage(mm: Int): DepthImage16 {
        return DepthImage16(40, 40, IntArray(40 * 40) { mm })
    }
}
