package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimateFactory
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 종합분석 6.6/6.8: 정지 사용자에게 접근하는 차량의 외부 위험 경고를 유지한다.
 * Accepted zero user speed (a standing user) must not switch off the STOP warning or its TTC.
 */
class StationaryUserApproachingVehicleRegressionTest {
    @Test
    fun acceptedStandingStillUserStillGetsStopWarningAndTtcForAnApproachingCar() {
        val last = approachingCar(VisualMaterialPointId()).last()
        assertEquals(0.0, requireNotNull(last.userMotion.speedMps), 0.0)
        assertEquals(ObjectMotion.OBJECT_APPROACHING, last.objectMotion)
        assertEquals(MessageLevel.STOP, last.userFacing.messageLevel)
        assertNotNull(last.timeToCollisionMs)
        assertEquals(2_500L, last.timeToCollisionMs)
    }

    @Test
    fun acceptedStandingStillWithoutMaterialPointKeepsObjectMotionUnknown() {
        val last = approachingCar(null).last()
        assertEquals(0.0, requireNotNull(last.userMotion.speedMps), 0.0)
        assertEquals(ObjectMotion.UNKNOWN, last.objectMotion)
    }

    // As in DepthEstimatorTest, this uniform-depth scene declares one physical surface point.
    // Its identity is not inferred from the bbox or used to supply motion, velocity or TTC.
    private fun approachingCar(materialPointId: VisualMaterialPointId?): List<TrackedObjectDepth> {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20))
        return listOf(4_000, 3_500, 3_000, 2_500).mapIndexed { index, mm ->
            val at = 1_000L + index * 500L
            val frameKey = VisualFrameKey(1L, at * 1_000_000L, at * 1_000_000L, at, 1L)
            val standingStill = UserMotionEstimateFactory.estimate(
                nowElapsedRealtimeMs = at,
                speed = WalkingSpeedObservation(0.0, 0.1, at),
                course = null,
                phoneHeading = null,
                locationEvidence = UserMotionLocationEvidence(at, 3.0, false),
            )
            assertEquals(0.0, requireNotNull(standingStill.speedMps), 0.0)
            val pose = CameraPoseEvidence(1L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(20, 20, 14f, 14f, 10f, 10f, 1f, 0f, 0f, 0f, 1f, 0f))
            estimator.estimate(ObjectDepthInput(at * 1_000_000L, at, geometry,
                tracker.update(listOf(geometry), at).single(), mapper,
                rawDepth = DepthImage16(20, 20, IntArray(400) { mm }),
                rawConfidence = ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() }),
                rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = at * 1_000_000L,
                materialPoint = materialPointId?.let { VisualMaterialPoint(it, frameKey, Point2(.5f, .5f)) },
                motionContext = MotionContext(
                    egoForwardSpeedMps = 0f,
                    cameraPoseEvidence = pose,
                    userMotion = standingStill,
                )))
        }
    }
}
