package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDepthRuntimePipelineTest {
    @Test
    fun synchronizedDepthPublishesWorldAndRelativeMotionWithoutMixingGpsAndArClocks() {
        val userMotion = UserMotionEstimate(
            speed = WalkingSpeedObservation(1.0, 0.1, 90_000L),
            course = HeadingObservation(90.0, 10.0, 90_000L),
            phoneHeading = HeadingObservation(180.0, 10.0, 90_000L),
        )
        listOf(255, 0).forEach { rawConfidence ->
            val pipeline = ObjectDepthRuntimePipeline()
            val outputs = (0..3).map { index ->
                val atMs = 1_000L + index * 500L
                val frameNs = atMs * 1_000_000L
                val cameraNs = frameNs + 400_000L
                val depthMm = 4_000 - index * 500
                pipeline.process(
                    snapshot = snapshot(depthMm, rawConfidence, depthMm).copy(
                        frameTimestampNs = frameNs,
                        rawDepthTimestampNs = cameraNs,
                        fullDepthTimestampNs = cameraNs,
                        cameraImageTimestampNs = cameraNs,
                        cameraPoseEvidence = CameraPoseEvidence(
                            referenceId = 7L, timestampMs = atMs,
                            positionX = 0f, positionY = 0f, positionZ = -index * 0.5f,
                            forwardX = 0f, forwardY = 0f, forwardZ = -1f,
                            imageProjection = CameraImageProjection(
                                1_000, 1_000, 700f, 700f, 500f, 500f,
                                1f, 0f, 0f, 0f, 1f, 0f,
                            ),
                        ),
                    ),
                    frameId = frameNs,
                    timestampMs = atMs,
                    detections = listOf(DetectionCandidate("person", 0.9f, RectNorm(0.35f, 0.35f, 0.30f, 0.30f))),
                    motionContext = MotionContext(userMotion = userMotion),
                ).single()
            }
            assertNull(outputs.first().motionEstimate.objectSpeedMps)
            val result = outputs.last()
            assertEquals(userMotion, result.userMotion)
            assertEquals(2_500L, result.motionEstimate.observedAtMs)
            assertEquals(7L, result.motionEstimate.referenceId)
            assertEquals(ObjectMovementDirection.STATIONARY, result.motionEstimate.direction)
            assertEquals(0f, result.motionEstimate.objectSpeedMps!!, 0.001f)
            assertEquals(1f, result.motionEstimate.relativeClosingSpeedMps!!, 0.001f)
            assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, result.objectMotion)
            assertEquals(1f, result.approachSpeedMps!!, 0.001f)
            assertEquals(2_500L, result.timeToCollisionMs)
        }
    }

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
    fun fullDepthFromAnotherCaptureCannotCreateCurrentDistanceOrMotionHistory() {
        val frameTimestamp = 1_000_000_000L
        val cameraTimestamp = frameTimestamp + 400_000L
        listOf(null, 0L, frameTimestamp, cameraTimestamp - 1L, cameraTimestamp + 1L).forEach { fullTimestamp ->
            val tracker = ObjectTracker()
            val pipeline = ObjectDepthRuntimePipeline(
                detectionProvider = onePersonProvider(),
                tracker = tracker,
            )
            val snapshot = snapshot(rawMm = 1_200, rawConfidence = 0, fullMm = 1_800).copy(
                frameTimestampNs = frameTimestamp,
                fullDepthTimestampNs = fullTimestamp,
                cameraImageTimestampNs = cameraTimestamp,
            )

            val output = pipeline.process(snapshot, frameId = frameTimestamp, timestampMs = 1_000L).single()

            assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
            assertNull(output.riskDistanceM)
            assertNull(output.userFacing.stepsAhead)
            assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
            assertTrue(tracker.activeTracks().single().distanceHistory.isEmpty())
        }
    }

    @Test
    fun currentRawDepthRemainsUsableWhenFullDepthComesFromAnOlderCapture() {
        val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider())
        val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 1_800).copy(
            frameTimestampNs = 1_000_000_000L,
            rawDepthTimestampNs = 1_000_400_000L,
            fullDepthTimestampNs = 1_000_399_999L,
            cameraImageTimestampNs = 1_000_400_000L,
        )

        val output = pipeline.process(snapshot, frameId = 1_000_000_000L, timestampMs = 1_000L).single()

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(1.2f, output.riskDistanceM!!, 0.01f)
    }

    @Test
    fun rawDepthWithAnUnknownOrDifferentTimestampCannotAcquireCurrentCameraPose() {
        val frameTimestamp = 1_000_000_000L
        val cameraTimestamp = frameTimestamp + 400_000L
        listOf(null, 0L, frameTimestamp, cameraTimestamp - 1L, cameraTimestamp + 1L).forEach { rawTimestamp ->
            val tracker = ObjectTracker()
            val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider(), tracker = tracker)
            val snapshot = snapshot(rawMm = 1_200, rawConfidence = 255, fullMm = 1_800).copy(
                frameTimestampNs = frameTimestamp,
                rawDepthTimestampNs = rawTimestamp,
                cameraPoseEvidence = CameraPoseEvidence(1L, 1_000L, 0f, 0f, 0f, 0f, 0f, -1f),
                cameraImageTimestampNs = cameraTimestamp,
            )

            val output = pipeline.process(snapshot, frameId = frameTimestamp, timestampMs = 1_000L).single()

            assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
            assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
            assertNull(tracker.activeTracks().single().distanceHistory.single().cameraPoseEvidence)
        }
    }

    @Test
    fun cameraAlignedRawAndFullDepthRetainPoseAndObservationTimeFromTheArFrame() {
        val frameTimestamp = 1_000_900_000L
        val cameraTimestamp = 1_001_300_000L
        val pose = CameraPoseEvidence(1L, 1_000L, 0f, 0f, 0f, 0f, 0f, -1f)
        listOf(255, 0).forEach { rawConfidence ->
            val tracker = ObjectTracker()
            val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider(), tracker = tracker)
            val snapshot = snapshot(rawMm = 1_200, rawConfidence = rawConfidence, fullMm = 1_800).copy(
                frameTimestampNs = frameTimestamp,
                rawDepthTimestampNs = cameraTimestamp,
                fullDepthTimestampNs = cameraTimestamp,
                cameraPoseEvidence = pose,
                cameraImageTimestampNs = cameraTimestamp,
            )

            val output = pipeline.process(snapshot, frameId = frameTimestamp, timestampMs = 1_000L).single()

            assertEquals(
                if (rawConfidence == 255) DepthSource.ARCORE_RAW_DEPTH else DepthSource.ARCORE_FULL_DEPTH,
                output.source,
            )
            assertEquals(if (rawConfidence == 255) 1.2f else 1.8f, output.riskDistanceM!!, 0.01f)
            val observation = tracker.activeTracks().single().distanceHistory.single()
            assertEquals(pose, observation.cameraPoseEvidence)
            assertEquals(1_000L, observation.timestampMs)
            assertEquals(frameTimestamp, snapshot.frameTimestampNs)
        }
    }

    @Test
    fun missingOrZeroCameraTimestampCannotQualifyFullDepthOrAttachPoseToRawDepth() {
        val frameTimestamp = 1_000_000_000L
        listOf(null, 0L).forEach { cameraTimestamp ->
            listOf(255, 0).forEach { rawConfidence ->
                val tracker = ObjectTracker()
                val pipeline = ObjectDepthRuntimePipeline(detectionProvider = onePersonProvider(), tracker = tracker)
                val snapshot = snapshot(rawMm = 1_200, rawConfidence = rawConfidence, fullMm = 1_800).copy(
                    frameTimestampNs = frameTimestamp,
                    rawDepthTimestampNs = frameTimestamp,
                    fullDepthTimestampNs = frameTimestamp,
                    cameraPoseEvidence = CameraPoseEvidence(1L, 1_000L, 0f, 0f, 0f, 0f, 0f, -1f),
                    cameraImageTimestampNs = cameraTimestamp,
                )

                val output = pipeline.process(snapshot, frameId = frameTimestamp, timestampMs = 1_000L).single()

                assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
                val history = tracker.activeTracks().single().distanceHistory
                if (rawConfidence == 255) {
                    assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
                    assertEquals(1.2f, output.riskDistanceM!!, 0.01f)
                    assertNull(history.single().cameraPoseEvidence)
                } else {
                    assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
                    assertNull(output.riskDistanceM)
                    assertNull(output.userFacing.stepsAhead)
                    assertTrue(history.isEmpty())
                }
            }
        }
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
            rawDepthTimestampNs = 1L,
            fullDepthTimestampNs = 1L,
            cameraImageTimestampNs = 1L,
        )
    }

    private fun depthImage(mm: Int): DepthImage16 {
        return DepthImage16(40, 40, IntArray(40 * 40) { mm })
    }
}
