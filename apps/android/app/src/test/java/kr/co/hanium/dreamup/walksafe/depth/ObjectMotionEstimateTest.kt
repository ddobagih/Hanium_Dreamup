package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ObjectMotionEstimateTest {
    @Test
    fun stationaryCameraAndObjectProduceMeasuredZeroWithObservationProvenance() {
        val fixture = trackedMotion()
        val estimate = fixture.estimate()

        assertEquals(ObjectMovementDirection.STATIONARY, estimate.direction)
        assertVector(ZERO, estimate.objectVelocityInAnchorMps)
        assertVector(ZERO, estimate.relativeVelocityInAnchorMps)
        assertVector(ZERO, estimate.cameraVelocityInAnchorMps)
        assertEquals(0f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertEquals(0f, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
        assertNull(estimate.objectDirectionInCamera)
        assertEquals(42L, estimate.referenceId)
        assertEquals(750L, estimate.observedAtMs)
        assertEquals(750L, estimate.elapsedMs)
        assertEquals(0.9f, estimate.confidence, TOLERANCE)
        assertEquals(estimate, fixture.kinematics().motionEstimate)
    }

    @Test
    fun walkingTowardStationaryObjectKeepsObjectSpeedZeroAndRelativeClosingOne() {
        val fixture = trackedMotion(cameraVelocity = Vec3(0f, 0f, -1f))
        val estimate = fixture.estimate()

        assertEquals(ObjectMovementDirection.STATIONARY, estimate.direction)
        assertVector(ZERO, estimate.objectVelocityInAnchorMps)
        assertVector(Vec3(0f, 0f, -1f), estimate.cameraVelocityInAnchorMps)
        assertVector(Vec3(0f, 0f, 1f), estimate.relativeVelocityInAnchorMps)
        assertEquals(0f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertEquals(1f, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
        assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, fixture.kinematics().objectMotion)
    }

    @Test
    fun incomingObjectRetainsWorldSpeedWhileWalkingUserChangesRelativeSpeed() {
        listOf(0f, 0.5f).forEach { walkingSpeed ->
            val fixture = trackedMotion(
                objectVelocity = Vec3(0f, 0f, 0.8f),
                cameraVelocity = Vec3(0f, 0f, -walkingSpeed),
            )
            val estimate = fixture.estimate()

            assertEquals(ObjectMovementDirection.TOWARD_USER, estimate.direction)
            assertVector(Vec3(0f, 0f, 0.8f), estimate.objectVelocityInAnchorMps)
            assertVector(Vec3(0f, 0f, 0.8f + walkingSpeed), estimate.relativeVelocityInAnchorMps)
            assertVector(Vec3(0f, 0f, -1f), estimate.objectDirectionInCamera)
            assertEquals(0.8f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
            assertEquals(0.8f + walkingSpeed, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
            assertEquals(ObjectMotion.OBJECT_APPROACHING, fixture.kinematics().objectMotion)
        }
    }

    @Test
    fun recedingObjectHasPositiveSpeedAndNegativeRelativeClosingSpeed() {
        val estimate = trackedMotion(objectVelocity = Vec3(0f, 0f, -0.8f)).estimate()

        assertEquals(ObjectMovementDirection.AWAY_FROM_USER, estimate.direction)
        assertEquals(0.8f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertEquals(-0.8f, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
        assertVector(Vec3(0f, 0f, 1f), estimate.objectDirectionInCamera)
    }

    @Test
    fun constantDepthSidewaysMotionHasMeasuredSpeedAndLeftOrRightDirection() {
        listOf(-1f, 1f).forEach { sign ->
            val fixture = trackedMotion(objectVelocity = Vec3(sign * 0.32f, 0f, 0f))
            val estimate = fixture.estimate()

            assertEquals(if (sign > 0f) ObjectMovementDirection.RIGHT else ObjectMovementDirection.LEFT, estimate.direction)
            assertEquals(0.32f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
            assertVector(Vec3(sign * 0.32f, 0f, 0f), estimate.objectVelocityInAnchorMps)
            assertVector(Vec3(sign, 0f, 0f), estimate.objectDirectionInCamera)
            assertTrue(requireNotNull(estimate.relativeClosingSpeedMps).isFinite())
            assertTrue(fixture.track.distanceHistory.all { it.distanceM == 6f })
            assertEquals(Trend.STABLE, fixture.kinematics().trend)
            assertEquals(ObjectMotion.UNKNOWN, fixture.kinematics().objectMotion)
        }
    }

    @Test
    fun sidewaysObjectIsNotStationaryWhenUserAlsoWalksForward() {
        val fixture = trackedMotion(
            objectVelocity = Vec3(0.32f, 0f, 0f),
            cameraVelocity = Vec3(0f, 0f, -1f),
        )
        val estimate = fixture.estimate()

        assertEquals(ObjectMovementDirection.RIGHT, estimate.direction)
        assertEquals(0.32f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertVector(Vec3(0.32f, 0f, 1f), estimate.relativeVelocityInAnchorMps)
        assertTrue(requireNotNull(estimate.relativeClosingSpeedMps) > 0.9f)
        assertEquals(Trend.APPROACHING, fixture.kinematics().trend)
        assertEquals(ObjectMotion.UNKNOWN, fixture.kinematics().objectMotion)
    }

    @Test
    fun lateralCameraMovementIsCompensatedInsteadOfMakingStationaryObjectMoveLeft() {
        val estimate = trackedMotion(cameraVelocity = Vec3(0.6f, 0f, 0f)).estimate()

        assertEquals(ObjectMovementDirection.STATIONARY, estimate.direction)
        assertEquals(0f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertVector(ZERO, estimate.objectVelocityInAnchorMps)
        assertVector(Vec3(0.6f, 0f, 0f), estimate.cameraVelocityInAnchorMps)
        assertVector(Vec3(-0.6f, 0f, 0f), estimate.relativeVelocityInAnchorMps)
        assertNull(estimate.objectDirectionInCamera)
    }

    @Test
    fun objectAndCameraMovingTogetherHaveObjectSpeedDespiteZeroRelativeVelocity() {
        val estimate = trackedMotion(
            objectVelocity = Vec3(0.6f, 0f, 0f),
            cameraVelocity = Vec3(0.6f, 0f, 0f),
        ).estimate()

        assertEquals(ObjectMovementDirection.RIGHT, estimate.direction)
        assertEquals(0.6f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertVector(ZERO, estimate.relativeVelocityInAnchorMps)
        assertEquals(0f, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
    }

    @Test
    fun cameraRightAxisDeterminesDirectionAfterNinetyDegreeAnchorRotation() {
        val estimate = trackedMotion(
            objectVelocity = Vec3(0f, 0f, 0.32f),
            right = Vec3(0f, 0f, 1f),
            forward = Vec3(1f, 0f, 0f),
        ).estimate()

        assertEquals(ObjectMovementDirection.RIGHT, estimate.direction)
        assertVector(Vec3(0f, 0f, 0.32f), estimate.objectVelocityInAnchorMps)
        assertVector(Vec3(1f, 0f, 0f), estimate.objectDirectionInCamera)
        assertEquals(0.32f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
    }

    @Test
    fun verticalMotionRemainsMeasuredInsteadOfBeingCollapsedToStationaryOrLateral() {
        val estimate = trackedMotion(objectVelocity = Vec3(0f, 0.32f, 0f)).estimate()

        assertEquals(ObjectMovementDirection.OTHER, estimate.direction)
        assertEquals(0.32f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertVector(Vec3(0f, 1f, 0f), estimate.objectDirectionInCamera)
    }

    @Test
    fun diagonalMovementUsesFullSpeedAndUnitDirectionInCameraAxes() {
        val estimate = trackedMotion(objectVelocity = Vec3(0.32f, 0.24f, 0f)).estimate()

        assertEquals(ObjectMovementDirection.RIGHT, estimate.direction)
        assertEquals(0.4f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertVector(Vec3(0.32f, 0.24f, 0f), estimate.objectVelocityInAnchorMps)
        assertVector(Vec3(0.8f, 0.6f, 0f), estimate.objectDirectionInCamera)
        assertEquals(1f, requireNotNull(estimate.objectDirectionInCamera).norm(), TOLERANCE)
    }

    @Test
    fun fourUniqueSamplesAndSixHundredMillisecondsAreRequiredForMeasuredZero() {
        val accepted = trackedMotion(timestamps = listOf(0L, 200L, 400L, 600L)).estimate()
        assertEquals(ObjectMovementDirection.STATIONARY, accepted.direction)
        assertEquals(0f, requireNotNull(accepted.objectSpeedMps), TOLERANCE)

        listOf(
            listOf(0L, 200L, 400L, 599L),
            listOf(0L, 300L, 600L),
            listOf(0L, 200L, 200L, 600L),
        ).forEach { timestamps -> assertUnknown(trackedMotion(timestamps = timestamps).estimate()) }
    }

    @Test
    fun repeatedFrameCannotExtendMotionEvidenceWindow() {
        val fixture = trackedMotion()
        val last = fixture.track.distanceHistory.last()
        val countBefore = fixture.track.distanceHistory.size

        repeat(5) {
            fixture.tracker.recordDistance(
                fixture.track, last.distanceM, last.source, last.confidence, last.timestampMs,
                last.cameraPoseEvidence, last.objectPositionInAnchor,
                depthObservationTimestampNs = last.depthObservationTimestampNs,
            )
        }

        assertEquals(countBefore, fixture.track.distanceHistory.size)
        assertEquals(750L, fixture.estimate().elapsedMs)
        assertEquals(750L, fixture.estimate().observedAtMs)
    }

    @Test
    fun invalidObservationIntervalsDoNotPublishMotion() {
        listOf(
            listOf(0L, 99L, 350L, 750L),
            listOf(0L, 250L, 500L, 2_001L),
            listOf(0L, 300L, 200L, 750L),
        ).forEach { timestamps -> assertUnknown(trackedMotion(timestamps = timestamps).estimate()) }
    }

    @Test
    fun latestDetectionWithoutCurrentDepthClearsMotionInsteadOfReusingOldEvidence() {
        val fixture = trackedMotion(objectVelocity = Vec3(0f, 0f, 0.8f))
        assertEquals(ObjectMovementDirection.TOWARD_USER, fixture.estimate().direction)

        val current = fixture.tracker.update(listOf(requireNotNull(fixture.track.latestGeometry)), 1_000L).single()

        assertEquals(fixture.track.trackId, current.trackId)
        assertEquals(750L, current.distanceHistory.last().timestampMs)
        assertUnknown(ObjectMotionPolicy.estimate(current))
        assertUnknown(fixture.kinematics().motionEstimate)
    }

    @Test
    fun missedDetectionInvalidatesOtherwiseCompleteMotionEvidence() {
        val fixture = trackedMotion()
        fixture.tracker.update(emptyList(), 800L)

        assertTrue(fixture.track.missedFrames > 0)
        assertUnknown(fixture.estimate())
    }

    @Test
    fun inconsistentObjectSegmentsCannotAverageIntoStationaryZero() {
        val estimate = trackedMotion(
            objectOffsets = listOf(ZERO, Vec3(0.1f, 0f, 0f), Vec3(-0.1f, 0f, 0f), ZERO),
        ).estimate()

        assertUnknown(estimate)
    }

    @Test
    fun recentMovementCannotBeHiddenByStationarySamplesAndLowAverageSpeed() {
        val fixture = trackedMotion(
            timestamps = listOf(0L, 200L, 400L, 600L),
            initialDepthM = 2f,
            objectOffsets = listOf(ZERO, ZERO, ZERO, Vec3(0f, 0f, 0.1f)),
        )

        assertEquals(listOf(2f, 2f, 2f, 1.9f), fixture.track.distanceHistory.map { it.distanceM })
        assertUnknown(fixture.estimate())
    }

    @Test
    fun consistentlySlowMotionKeepsMeasuredSpeedInsteadOfRoundingItToZero() {
        val estimate = trackedMotion(
            objectVelocity = Vec3(0f, 0f, 0.1f),
            timestamps = listOf(0L, 200L, 400L, 600L),
            initialDepthM = 2f,
        ).estimate()

        assertEquals(ObjectMovementDirection.STATIONARY, estimate.direction)
        assertEquals(0.1f, requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        assertEquals(0.1f, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
        assertVector(Vec3(0f, 0f, 0.1f), estimate.objectVelocityInAnchorMps)
        assertNull(estimate.objectDirectionInCamera)
    }

    @Test
    fun inconsistentCameraSegmentsCannotBeHiddenByZeroNetDisplacement() {
        val estimate = trackedMotion(
            cameraOffsets = listOf(ZERO, Vec3(0.1f, 0f, 0f), Vec3(-0.1f, 0f, 0f), ZERO),
        ).estimate()

        assertUnknown(estimate)
    }

    @Test
    fun cameraSpeedAboveWalkingAdmissionLimitIsUnknown() {
        assertUnknown(trackedMotion(cameraVelocity = Vec3(0f, 0f, -3.6f)).estimate())
    }

    @Test
    fun anchorRebaseAndUnsynchronizedPoseInvalidateMetricMotion() {
        val transforms: List<(Int, CameraPoseEvidence) -> CameraPoseEvidence?> = listOf(
            { index, pose -> if (index == 3) pose.copy(referenceId = 43L) else pose },
            { index, pose -> if (index == 3) pose.copy(timestampMs = pose.timestampMs - 1L) else pose },
            { index, pose -> if (index == 3) null else pose },
        )

        transforms.forEach { transform -> assertUnknown(trackedMotion(poseTransform = transform).estimate()) }
    }

    @Test
    fun cameraRotationAboveFiveDegreesInvalidatesMotionEvenWithValidProjection() {
        val angle = Math.toRadians(6.0)
        val estimate = trackedMotion(poseTransform = { index, pose ->
            if (index != 3) pose else pose.copy(
                forwardX = sin(angle).toFloat(),
                forwardZ = -cos(angle).toFloat(),
                imageProjection = requireNotNull(pose.imageProjection).copy(
                    rightX = cos(angle).toFloat(),
                    rightZ = sin(angle).toFloat(),
                ),
            )
        }).estimate()

        assertUnknown(estimate)
    }

    @Test
    fun missingOrInvalidProjectionCannotTurnMissingGeometryIntoZeroSpeed() {
        val transforms: List<(Int, CameraPoseEvidence) -> CameraPoseEvidence?> = listOf(
            { index, pose -> if (index == 3) pose.copy(imageProjection = null) else pose },
            { index, pose -> if (index == 3) pose.copy(
                imageProjection = requireNotNull(pose.imageProjection).copy(fx = 0f),
            ) else pose },
            { index, pose -> if (index == 3) pose.copy(positionX = Float.NaN) else pose },
        )

        transforms.forEach { transform -> assertUnknown(trackedMotion(poseTransform = transform).estimate()) }
    }

    @Test
    fun depthSourceChangeAndLowConfidenceCannotPublishMetricObjectMotion() {
        assertUnknown(trackedMotion(sourceAt = { if (it == 3) DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH }).estimate())
        assertUnknown(trackedMotion(sourceAt = { DepthSource.MONOCULAR_METRIC_DEPTH }).estimate())
        assertUnknown(trackedMotion(confidenceAt = { if (it == 3) 0.549f else 0.9f }).estimate())

        val accepted = trackedMotion(confidenceAt = { if (it == 3) 0.55f else 0.9f }).estimate()
        assertEquals(ObjectMovementDirection.STATIONARY, accepted.direction)
        assertEquals(0.55f, accepted.confidence, TOLERANCE)
    }

    private fun trackedMotion(
        objectVelocity: Vec3 = ZERO,
        cameraVelocity: Vec3 = ZERO,
        timestamps: List<Long> = listOf(0L, 250L, 500L, 750L),
        initialDepthM: Float = 6f,
        right: Vec3 = Vec3(1f, 0f, 0f),
        forward: Vec3 = Vec3(0f, 0f, -1f),
        objectOffsets: List<Vec3>? = null,
        cameraOffsets: List<Vec3>? = null,
        poseTransform: (Int, CameraPoseEvidence) -> CameraPoseEvidence? = { _, pose -> pose },
        sourceAt: (Int) -> DepthSource = { DepthSource.ARCORE_RAW_DEPTH },
        confidenceAt: (Int) -> Float = { 0.9f },
    ): Fixture {
        val tracker = ObjectTracker()
        val up = Vec3(0f, 1f, 0f)
        var latest: TrackState? = null
        timestamps.forEachIndexed { index, at ->
            val seconds = at / 1_000f
            val camera = cameraVelocity * seconds + (cameraOffsets?.get(index) ?: ZERO)
            val objectPosition = forward * initialDepthM + objectVelocity * seconds + (objectOffsets?.get(index) ?: ZERO)
            val relative = objectPosition - camera
            val depth = relative.dot(forward)
            val center = Point2(
                0.5f + 0.7f * relative.dot(right) / depth,
                0.5f - 0.7f * relative.dot(up) / depth,
            )
            val geometry = geometry(center)
            val track = tracker.update(listOf(geometry), at).singleOrNull { it.missedFrames == 0 }
                ?: return@forEachIndexed
            val pose = poseTransform(index, CameraPoseEvidence(
                referenceId = 42L,
                timestampMs = at,
                positionX = camera.x, positionY = camera.y, positionZ = camera.z,
                forwardX = forward.x, forwardY = forward.y, forwardZ = forward.z,
                imageProjection = CameraImageProjection(
                    imageWidth = 1_000, imageHeight = 1_000,
                    fx = 700f, fy = 700f, cx = 500f, cy = 500f,
                    rightX = right.x, rightY = right.y, rightZ = right.z,
                    upX = up.x, upY = up.y, upZ = up.z,
                ),
            ))
            tracker.recordDistance(
                track, depth, sourceAt(index), confidenceAt(index), at, pose,
                pose?.objectCenterInAnchor(center, depth),
                depthObservationTimestampNs = 9_000_000_000L + at * 1_000_000L,
            )
            latest = track
        }
        return Fixture(tracker, requireNotNull(latest))
    }

    private fun geometry(center: Point2): ObjectGeometry {
        val bbox = RectNorm(center.x - 0.1f, center.y - 0.15f, 0.2f, 0.3f)
        return ObjectGeometry(
            className = "person", detectionConfidence = 0.9f,
            bboxNorm = bbox, polygonNorm = bboxPolygon(bbox, erosionRatio = 0f),
            maskAreaNorm = bbox.area, centerNorm = center,
            bottomContactNorm = Point2(center.x, bbox.y + bbox.height),
        )
    }

    private fun assertVector(expected: Vec3, actual: Vec3?) {
        val value = requireNotNull(actual)
        assertEquals(expected.x, value.x, TOLERANCE)
        assertEquals(expected.y, value.y, TOLERANCE)
        assertEquals(expected.z, value.z, TOLERANCE)
    }

    private fun assertUnknown(estimate: ObjectMotionEstimate) {
        assertEquals(ObjectMovementDirection.UNKNOWN, estimate.direction)
        assertNull(estimate.objectSpeedMps)
        assertNull(estimate.relativeClosingSpeedMps)
        assertNull(estimate.objectVelocityInAnchorMps)
        assertNull(estimate.relativeVelocityInAnchorMps)
        assertNull(estimate.cameraVelocityInAnchorMps)
        assertNull(estimate.objectDirectionInCamera)
        assertNull(estimate.referenceId)
        assertNull(estimate.observedAtMs)
        assertEquals(0L, estimate.elapsedMs)
        assertEquals(0f, estimate.confidence, TOLERANCE)
    }

    private data class Fixture(val tracker: ObjectTracker, val track: TrackState) {
        fun estimate(): ObjectMotionEstimate = ObjectMotionPolicy.estimate(track)

        fun kinematics(): ApproachKinematics {
            val latest = track.distanceHistory.lastOrNull()
            return tracker.approachKinematics(track, latest?.distanceM, latest?.source ?: DepthSource.UNKNOWN)
        }
    }

    private companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        const val TOLERANCE = 0.0001f
    }
}
