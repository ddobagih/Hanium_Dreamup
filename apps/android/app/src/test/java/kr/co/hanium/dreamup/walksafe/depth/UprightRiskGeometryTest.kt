package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UprightRiskGeometryTest {
    @Test
    fun sameUprightRegionsHaveTheSameCorridorQualityInAllFourRotations() {
        val cases = listOf(
            Triple(Point2(.5f, .8f), ScreenZone.LOWER, 1f),
            Triple(Point2(.5f, .5f), ScreenZone.CENTER, 1f),
            Triple(Point2(.8f, .5f), ScreenZone.RIGHT, .45f),
            Triple(Point2(.2f, .5f), ScreenZone.LEFT, .45f),
            Triple(Point2(.8f, .8f), ScreenZone.LOWER, .75f),
            Triple(Point2(.5f, .2f), ScreenZone.UPPER, .30f),
        )
        for ((center, zone, quality) in cases) for (turns in 0..3) {
            val geometry = geometry(center, turns)
            assertEquals(center.x, geometry.uprightCenterNorm!!.x, .00001f)
            assertEquals(center.y, geometry.uprightCenterNorm!!.y, .00001f)
            assertEquals(zone, geometry.uprightScreenZone)
            assertEquals(quality, pseudo(listOf(geometry)).confidence.corridorQuality, 0f)
        }
    }

    @Test
    fun approachingObjectInUprightLowerCenterIsClassifiedInEveryRotation() {
        for (turns in 0..3) {
            val (track, tracker) = movingTrack(geometry(Point2(.5f, .8f), turns))
            val result = tracker.approachKinematics(track, 2.2f, DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(ObjectMovementDirection.TOWARD_USER, result.motionEstimate.direction)
            assertEquals(ObjectMotion.OBJECT_APPROACHING, result.objectMotion)
        }
    }

    @Test
    fun userApproachingStationaryUprightObstacleIsClassifiedInEveryRotation() {
        for (turns in 0..3) {
            val (track, tracker) = movingTrack(geometry(Point2(.5f, .8f), turns), stationary = true)
            val result = tracker.approachKinematics(track, 2.2f, DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(ObjectMovementDirection.STATIONARY, result.motionEstimate.direction)
            assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, result.objectMotion)
        }
    }

    @Test
    fun uprightSideDoesNotAcquireTheFrontApproachLabelWhenSensorXIsCentered() {
        for (turns in 0..3) {
            val (track, tracker) = movingTrack(geometry(Point2(.8f, .5f), turns))
            val result = tracker.approachKinematics(track, 2.2f, DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(ObjectMovementDirection.TOWARD_USER, result.motionEstimate.direction)
            assertEquals(ObjectMotion.UNKNOWN, result.objectMotion)
        }
    }

    @Test
    fun nonMetricApproachUsesUprightBottomGrowthWithoutInventingDistance() {
        val outputs = (0..3).map { turns ->
            pseudo((0..4).map { geometry(Point2(.5f, .5f + it * .045f), turns) })
        }
        for (output in outputs) {
            assertEquals(Trend.APPROACHING, output.trend)
            assertEquals(outputs.first().approachScore, output.approachScore, .00001f)
            assertNull(output.riskDistanceM)
            assertNull(output.approachSpeedMps)
            assertNull(output.timeToCollisionMs)
            assertNull(output.userFacing.stepsAhead)
        }
    }

    @Test
    fun unknownOrInvalidOrientationWithholdsUprightClaimsButKeepsMetricFallbackStop() {
        for (turns in listOf(null, -1, 4)) {
            val geometry = geometry(Point2(.5f, .8f), 0).copy(imageQuarterTurns = turns)
            assertNull(geometry.uprightCenterNorm)
            assertNull(geometry.uprightScreenZone)
            val pseudo = pseudo((0..4).map {
                geometry(Point2(.5f, .5f + it * .045f), 0).copy(imageQuarterTurns = turns)
            })
            assertEquals(Trend.UNKNOWN, pseudo.trend)
            assertEquals(.30f, pseudo.confidence.corridorQuality, 0f)
            val (track, tracker) = movingTrack(geometry)
            val kinematics = tracker.approachKinematics(track, 2.2f, DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(ObjectMotion.UNKNOWN, kinematics.objectMotion)
            val message = MessagePolicy().buildUserFacing(MetricDepthDecision(
                className = "person", source = DepthSource.ARCORE_RAW_DEPTH, riskDistanceM = .5f,
                trend = kinematics.trend, confidenceFinal = .95f, trackKey = track.trackId,
                timeToCollisionMs = kinematics.timeToCollisionMs,
                objectMotion = kinematics.objectMotion, motionEstimate = kinematics.motionEstimate,
            ), 2_800L)
            assertEquals(MessageLevel.STOP, message.messageLevel)
            assertTrue(message.message!!.contains("이 물체가 사용자 쪽으로 이동하는 것으로 보입니다"))
        }
    }

    @Test
    fun orientationMetadataDoesNotRotateDepthSamplingOrCameraPoseProjection() {
        for (turns in 0..3) {
            val geometry = geometry(Point2(.5f, .8f), turns)
            val sensorPolygon = geometry.polygonNorm.toList()
            val recordingMapper = RecordingMapper()
            val tracker = ObjectTracker()
            val track = tracker.update(listOf(geometry), 1_000L).single()
            val pose = pose(1_000L)
            // Only the captured sensor rectangle has depth. Rotating the sampling polygon again
            // would read empty pixels for the lower-center off-axis object in rotations 1/2/3.
            val pixels = IntArray(SIZE * SIZE) { index ->
                val x = (index % SIZE + .5f) / SIZE
                val y = (index / SIZE + .5f) / SIZE
                if (x >= geometry.bboxNorm.x && x <= geometry.bboxNorm.x + geometry.bboxNorm.width &&
                    y >= geometry.bboxNorm.y && y <= geometry.bboxNorm.y + geometry.bboxNorm.height) 1_600 else 0
            }
            val result = ObjectDepthEstimator(tracker = tracker).estimate(ObjectDepthInput(
                frameId = 1L, timestampMs = 1_000L, geometry = geometry, track = track,
                mapper = recordingMapper, rawDepth = DepthImage16(SIZE, SIZE, pixels),
                rawConfidence = ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }),
                rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = 1_000_000_000L,
                motionContext = MotionContext(cameraPoseEvidence = pose),
            ))
            assertEquals(DepthSource.ARCORE_RAW_DEPTH, result.source)
            assertEquals(1.6f, result.depthMedianM!!, .00001f)
            assertEquals(sensorPolygon, recordingMapper.sampledPolygon)
            assertEquals(sensorPolygon, result.polygonNorm)
            assertEquals(geometry.bboxNorm, result.bboxNorm)
            assertEquals(geometry.centerNorm, result.centerNorm)
            assertEquals(geometry.bottomContactNorm, result.bottomContactNorm)
            assertEquals(pose.objectCenterInAnchor(geometry.centerNorm, 1.6f),
                track.distanceHistory.last().objectPositionInAnchor)
            assertEquals(1f, result.confidence.corridorQuality, 0f)
        }
    }

    @Test
    fun rotationChangeStartsNewVisualTrendHistoryWithoutClearingMetricEvidence() {
        val geometry = geometry(Point2(.5f, .5f), 0)
        val (track, _) = movingTrack(geometry)
        val metricHistory = track.distanceHistory.toList()
        assertTrue(track.polygonHistory.size >= 3)
        track.markSeen(geometry.copy(imageQuarterTurns = 1), 3_100L, 3)
        assertEquals(1, track.bboxHistory.size)
        assertEquals(1, track.centerHistory.size)
        assertEquals(1, track.polygonHistory.size)
        assertEquals(metricHistory, track.distanceHistory)
        track.markTracked(geometry.copy(imageQuarterTurns = null), 3_400L)
        assertEquals(1, track.polygonHistory.size)
        assertEquals(metricHistory, track.distanceHistory)
    }

    private fun geometry(center: Point2, turns: Int): ObjectGeometry = MaskPolygonExtractor().extract(
        DetectionCandidate("person", .95f,
            UprightCameraImage.toSensor(RectNorm(center.x - .08f, center.y - .08f, .16f, .16f), turns)),
    ).copy(imageQuarterTurns = turns)

    private fun pseudo(geometries: List<ObjectGeometry>): TrackedObjectDepth {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        return geometries.mapIndexed { index, geometry ->
            val at = 1_000L + index * 300L
            val track = tracker.update(listOf(geometry), at).single { it.missedFrames == 0 }
            estimator.estimate(ObjectDepthInput(index.toLong(), at, geometry, track, mapper))
        }.last()
    }

    private fun movingTrack(geometry: ObjectGeometry, stationary: Boolean = false): Pair<TrackState, ObjectTracker> {
        val tracker = ObjectTracker()
        var latest: TrackState? = null
        for (at in 1_000L..2_800L step 300L) {
            val track = tracker.update(listOf(geometry), at).single { it.missedFrames == 0 }
            val depth = 4f - (at - 1_000L) / 1_000f
            val basePose = pose(at)
            val ray = requireNotNull(basePose.objectCenterInAnchor(geometry.centerNorm, 1f))
            val pose = if (stationary) basePose.copy(
                positionX = ray.x * (4f - depth), positionY = ray.y * (4f - depth),
                positionZ = ray.z * (4f - depth),
            ) else basePose
            assertTrue(tracker.recordDistance(track, depth, DepthSource.ARCORE_RAW_DEPTH, .95f, at,
                pose, pose.objectCenterInAnchor(geometry.centerNorm, depth),
                9_000_000_000L + at * 1_000_000L, true))
            latest = track
        }
        return requireNotNull(latest) to tracker
    }

    private fun pose(at: Long) = CameraPoseEvidence(1L, at, 0f, 0f, 0f, 0f, 0f, -1f,
        CameraImageProjection(SIZE, SIZE, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))

    private class RecordingMapper : CoordinateMapper by mapper {
        var sampledPolygon: List<Point2>? = null
        override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
            sampledPolygon = polygon.toList()
            return polygon
        }
    }

    private companion object {
        const val SIZE = 100
        val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE),
        )
    }
}
