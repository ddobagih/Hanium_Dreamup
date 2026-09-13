package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorTest {
    @Test
    fun isolatedDepthJumpSuppressesConfidenceSpeechAndTtcThenRecoversTheSameTrack() {
        val outputs = metricSequence(listOf(4_000, 4_000, 4_000, 4_000, 500, 4_000))
        val anomaly = outputs[4]
        val recovered = outputs.last()

        assertEquals(0f, anomaly.confidence.finalScore, 0f)
        assertEquals(Trend.UNKNOWN, anomaly.trend)
        assertNull(anomaly.timeToCollisionMs)
        assertNull(anomaly.userFacing.message)
        assertTrue(recovered.confidence.finalScore >= 0.55f)
        assertEquals(Trend.STABLE, recovered.trend)
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
    }

    @Test
    fun confirmedNewNearBaselineCanWarnWithoutManufacturingTtcAcrossTheJump() {
        val outputs = metricSequence(listOf(4_000, 600, 500))

        assertNull(outputs[1].userFacing.message)
        assertEquals(0f, outputs[1].confidence.finalScore, 0f)
        assertEquals(MessageLevel.STOP, outputs.last().userFacing.messageLevel)
        assertTrue(outputs.last().confidence.finalScore >= 0.55f)
        assertNull(outputs.last().timeToCollisionMs)
        assertEquals(Trend.UNKNOWN, outputs.last().trend)
    }

    @Test
    fun consistentFastMetricApproachSurvivesLargePerFrameDistanceChanges() {
        val outputs = metricSequence(listOf(7_000, 5_000, 3_000, 1_000))
        val approaching = outputs.last()

        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        assertEquals(0f, outputs[1].confidence.finalScore, 0f)
        assertTrue(approaching.confidence.finalScore >= 0.55f)
        assertEquals(Trend.APPROACHING, approaching.trend)
        assertEquals(ObjectMotion.OBJECT_APPROACHING, approaching.objectMotion)
        assertEquals(250L, approaching.timeToCollisionMs)
        assertEquals(MessageLevel.STOP, approaching.userFacing.messageLevel)
    }

    private fun metricSequence(distancesMm: List<Int>): List<TrackedObjectDepth> {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.9f, RectNorm(0f, 0f, 1f, 1f)))
        return distancesMm.mapIndexed { index, millimeters ->
            val at = index * 500L
            val track = tracker.update(listOf(geometry), at).single()
            estimator.estimate(
                ObjectDepthInput(
                    frameId = index.toLong(), timestampMs = at, geometry = geometry, track = track, mapper = mapper,
                    rawDepth = DepthImage16(10, 10, IntArray(100) { millimeters }),
                    rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() }),
                    rawDepthMatchesCameraFrame = true,
                    rawDepthTimestampNs = at * 1_000_000L + 1L,
                    motionContext = MotionContext(cameraPoseEvidence = CameraPoseEvidence(
                        referenceId = 1L, timestampMs = at, positionX = 0f, positionY = 0f, positionZ = 0f,
                        forwardX = 0f, forwardY = 0f, forwardZ = -1f,
                        imageProjection = CameraImageProjection(
                            imageWidth = 10, imageHeight = 10, fx = 7f, fy = 7f, cx = 5f, cy = 5f,
                            rightX = 1f, rightY = 0f, rightZ = 0f, upX = 0f, upY = 1f, upZ = 0f,
                        ),
                    )),
                ),
            )
        }
    }

    @Test
    fun synchronizedMetricDepthAndPoseReachDistinctUserMessagesThroughEstimator() {
        fun approach(
            egoDisplacementSpeed: Float?,
            motion: MotionContext = MotionContext(),
            alignedDepth: Boolean = true,
        ): TrackedObjectDepth {
            val tracker = ObjectTracker()
            val estimator = ObjectDepthEstimator(tracker = tracker)
            val geometry = MaskPolygonExtractor().extract(
                DetectionCandidate("car", 0.9f, RectNorm(0f, 0f, 1f, 1f)),
            )
            var latest: TrackedObjectDepth? = null
            listOf(4_000, 3_500, 3_000, 2_500).forEachIndexed { index, millimeters ->
                val at = index * 500L
                val track = tracker.update(listOf(geometry), at).single()
                latest = estimator.estimate(
                    ObjectDepthInput(
                        frameId = index.toLong(), timestampMs = at, geometry = geometry,
                        track = track, mapper = mapper,
                        rawDepth = DepthImage16(10, 10, IntArray(100) { millimeters }),
                        rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() }),
                        rawDepthMatchesCameraFrame = alignedDepth,
                        rawDepthTimestampNs = at * 1_000_000L + 1L,
                        motionContext = motion.copy(cameraPoseEvidence = egoDisplacementSpeed?.let { speed ->
                            CameraPoseEvidence(
                                referenceId = 1L, timestampMs = at,
                                positionX = 0f, positionY = 0f, positionZ = -speed * at / 1_000f,
                                forwardX = 0f, forwardY = 0f, forwardZ = -1f,
                                imageProjection = CameraImageProjection(
                                    imageWidth = 10, imageHeight = 10, fx = 7f, fy = 7f, cx = 5f, cy = 5f,
                                    rightX = 1f, rightY = 0f, rightZ = 0f, upX = 0f, upY = 1f, upZ = 0f,
                                ),
                            )
                        }),
                    ),
                )
            }
            return requireNotNull(latest)
        }

        // Stopping just before each detector sample makes instantaneous speed zero. The pose
        // displacement still records the user's walking between samples and prevents false motion.
        val stationary = approach(1f, MotionContext(egoForwardSpeedMps = 0f))
        val incoming = approach(0.5f)
        val uncertain = approach(null, MotionContext(egoForwardSpeedMps = 1f))
        val shaking = approach(0.5f, MotionContext(shakeScore = 0.9f))
        val unsynchronized = approach(1f, alignedDepth = false)

        assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, stationary.objectMotion)
        assertTrue(stationary.userFacing.message!!.contains("정지해 있는 것으로 보이며"))
        assertEquals(ObjectMotion.OBJECT_APPROACHING, incoming.objectMotion)
        assertTrue(incoming.userFacing.message!!.contains("이 물체가 사용자 쪽으로 다가오는 것으로 보입니다"))
        assertEquals(ObjectMotion.UNKNOWN, uncertain.objectMotion)
        assertEquals(ObjectMotion.UNKNOWN, shaking.objectMotion)
        assertEquals(ObjectMotion.UNKNOWN, unsynchronized.objectMotion)
        assertTrue(uncertain.userFacing.message!!.contains("거리가 줄어들고 있습니다"))
    }

    private val mapper = LetterboxCoordinateMapper(
        transform = ModelInputTransform(
            imageWidth = 10,
            imageHeight = 10,
            modelWidth = 10,
            modelHeight = 10,
            scale = 1f,
            padX = 0f,
            padY = 0f,
        ),
        depthSize = ImageSize(10, 10),
    )

    @Test
    fun estimatorPrefersRawDepthAndFallsBackToFullDepthWhenConfidenceMissing() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0f, 0f, 1f, 1f),
            ),
        )
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry), timestampMs = 0L).first()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val raw = DepthImage16(10, 10, IntArray(100) { 1_000 })
        val rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() })
        val full = DepthImage16(10, 10, IntArray(100) { 1_700 })

        val rawResult = estimator.estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = rawConfidence,
                fullDepth = full,
            ),
        )
        val fullFallbackResult = estimator.estimate(
            ObjectDepthInput(
                frameId = 2L,
                timestampMs = 2_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = null,
                fullDepth = full,
            ),
        )

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, rawResult.source)
        assertEquals(1.0f, rawResult.depthMedianM!!, 0.01f)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, fullFallbackResult.source)
        assertEquals(1.7f, fullFallbackResult.depthMedianM!!, 0.01f)
    }

    @Test
    fun reprojectedRawDepthRemainsMetricButIsSoftDownweighted() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0f, 0f, 1f, 1f),
            ),
        )
        val freshTracker = ObjectTracker()
        val freshTrack = freshTracker.update(listOf(geometry), timestampMs = 0L).first()
        val staleTracker = ObjectTracker()
        val staleTrack = staleTracker.update(listOf(geometry), timestampMs = 0L).first()
        val raw = DepthImage16(10, 10, IntArray(100) { 1_000 })
        val confidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() })
        val fresh = ObjectDepthEstimator(tracker = freshTracker).estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = freshTrack,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = confidence,
            ),
        )
        val reprojected = ObjectDepthEstimator(tracker = staleTracker).estimate(
            ObjectDepthInput(
                frameId = 2L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = staleTrack,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = confidence,
                rawDepthFreshnessQuality = 0.70f,
            ),
        )

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, reprojected.source)
        assertEquals(fresh.riskDistanceM!!, reprojected.riskDistanceM!!, 0f)
        assertTrue(reprojected.confidence.finalScore < fresh.confidence.finalScore)
    }

    @Test
    fun pseudoDepthDoesNotExposeDistanceOrSteps() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0.3f, 0.3f, 0.3f, 0.4f),
            ),
        )
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry), timestampMs = 0L).first()
        val result = ObjectDepthEstimator(tracker = tracker).estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
            ),
        )

        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, result.source)
        assertNull(result.riskDistanceM)
        assertNull(result.userFacing.stepsAhead)
    }

    @Test
    fun stableTrackerComputesApproachSpeedOnlyAfterStableMetricHistory() {
        val tracker = ObjectTracker()
        val extractor = MaskPolygonExtractor()
        lateinit var track: TrackState
        listOf(4.0f, 3.5f, 3.0f, 2.5f).forEachIndexed { index, distance ->
            val geometry = extractor.extract(
                DetectionCandidate(
                    className = "person",
                    detectionConfidence = 0.9f,
                    bboxNorm = RectNorm(0.35f, 0.2f + index * 0.01f, 0.3f, 0.4f),
                ),
            )
            track = tracker.update(listOf(geometry), timestampMs = index * 1_000L).first()
            tracker.recordDistance(track, distance, DepthSource.ARCORE_RAW_DEPTH, 0.9f, index * 1_000L)
        }

        val kinematics = tracker.approachKinematics(track, currentDistanceM = 2.5f, source = DepthSource.ARCORE_RAW_DEPTH)

        assertTrue(track.stable)
        assertEquals(Trend.APPROACHING, kinematics.trend)
        assertTrue(kinematics.approachSpeedMps!! > 0.25f)
    }
}
