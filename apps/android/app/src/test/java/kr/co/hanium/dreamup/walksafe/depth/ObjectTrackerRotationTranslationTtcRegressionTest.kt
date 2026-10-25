package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.cos
import kotlin.math.sin
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerRotationTranslationTtcRegressionTest {
    @Test
    fun fixedObjectDoesNotAcquireAnAxialTtcWhileTheCameraRotatesAndTranslatesSideways() {
        val result = kinematics(yawPerFrameDeg = 8.0)
        assertEquals(Trend.UNKNOWN, result.trend)
        assertNull(result.approachSpeedMps)
        assertNull(result.timeToCollisionMs)
    }

    @Test
    fun sidewaysCameraTranslationWithoutRotationDoesNotCreateApproach() {
        val result = kinematics(yawPerFrameDeg = 0.0)
        assertEquals(Trend.STABLE, result.trend)
        assertNull(result.approachSpeedMps)
        assertNull(result.timeToCollisionMs)
    }

    @Test
    fun anActualAxialApproachWithoutRotationRetainsItsSpeedAndTtc() {
        val result = kinematics(yawPerFrameDeg = 0.0, objectAdvancePerFrameM = .25f)
        assertEquals(Trend.APPROACHING, result.trend)
        assertNotNull(result.approachSpeedMps)
        assertNotNull(result.timeToCollisionMs)
        assertEquals(1f, requireNotNull(result.approachSpeedMps), .01f)
    }

    private fun kinematics(yawPerFrameDeg: Double, objectAdvancePerFrameM: Float = 0f): ApproachKinematics {
        val tracker = ObjectTracker()
        val materialPointId = VisualMaterialPointId()
        var latest: TrackState? = null
        var originalId: String? = null
        var lastDepth = 0f
        for (i in 0..3) {
            val at = 1_000L + i * 250L
            val yaw = Math.toRadians(i * yawPerFrameDeg)
            val camera = Vec3(.04f * i, 0f, 0f)
            val point = Vec3(0f, 0f, -4f + i * objectAdvancePerFrameM)
            val forward = Vec3(sin(yaw).toFloat(), 0f, -cos(yaw).toFloat())
            val right = Vec3(cos(yaw).toFloat(), 0f, sin(yaw).toFloat())
            val relative = point - camera
            val depth = relative.dot(forward)
            val center = Point2(.5f + .7f * relative.dot(right) / depth, .5f)
            val box = RectNorm(center.x - .1f, .35f, .2f, .3f)
            val geometry = ObjectGeometry("person", .9f, box, bboxPolygon(box, erosionRatio = 0f),
                box.area, center, Point2(center.x, .65f))
            val track = tracker.update(listOf(geometry), at).single { it.missedFrames == 0 }
            if (originalId == null) originalId = track.trackId
            assertEquals("The TTC check must use the same matched track", originalId, track.trackId)
            val pose = CameraPoseEvidence(referenceId = 42L, timestampMs = at,
                positionX = camera.x, positionY = camera.y, positionZ = camera.z,
                forwardX = forward.x, forwardY = forward.y, forwardZ = forward.z,
                imageProjection = CameraImageProjection(1_000, 1_000, 700f, 700f, 500f, 500f,
                    right.x, right.y, right.z, 0f, 1f, 0f))
            val reconstructed = requireNotNull(pose.objectCenterInAnchor(center, depth))
            assertTrue((reconstructed - point).norm() < .00001f)
            assertTrue(tracker.recordDistance(track, depth, DepthSource.ARCORE_RAW_DEPTH, .9f, at,
                pose, reconstructed, depthObservationTimestampNs = 9_000_000_000L + at * 1_000_000L,
                requireIndependentDepthObservation = true, materialPointId = materialPointId))
            latest = track
            lastDepth = depth
        }
        val track = requireNotNull(latest)
        assertTrue(track.stable)
        assertEquals(4, track.motionSamples().size)
        return tracker.approachKinematics(track, lastDepth, DepthSource.ARCORE_RAW_DEPTH)
    }
}
