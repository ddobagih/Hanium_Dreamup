package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionContextTest {
    @Test
    fun cpuImagePointUsesIntrinsicsAndCompleteCameraBasisForAnchorPosition() {
        val pose = CameraPoseEvidence(
            referenceId = 1L, timestampMs = 1_000L, positionX = 1f, positionY = 2f, positionZ = 3f,
            forwardX = 1f, forwardY = 0f, forwardZ = 0f,
            imageProjection = CameraImageProjection(
                imageWidth = 1_000, imageHeight = 1_000, fx = 500f, fy = 500f, cx = 500f, cy = 500f,
                rightX = 0f, rightY = 0f, rightZ = 1f, upX = 0f, upY = 1f, upZ = 0f,
            ),
        )
        val point = requireNotNull(pose.objectCenterInAnchor(Point2(0.6f, 0.4f), 2f))

        assertEquals(3f, point.x, 0.001f)
        assertEquals(2.4f, point.y, 0.001f)
        assertEquals(3.4f, point.z, 0.001f)
    }

    @Test
    fun routeBearingAlignmentIsNeutralWhenUnknown() {
        assertEquals(1f, routeBearingAlignmentQuality(null, 90f), 0.001f)
        assertEquals(1f, routeBearingAlignmentQuality(90f, null), 0.001f)
    }

    @Test
    fun routeBearingAlignmentHandlesWrapAround() {
        assertEquals(1f, routeBearingAlignmentQuality(350f, 10f), 0.001f)
        assertEquals(0.35f, routeBearingAlignmentQuality(0f, 180f), 0.001f)
    }

    @Test
    fun safeMotionQualityUsesRouteAlignmentAsAuxiliaryGate() {
        val context = MotionContext(motionQuality = 0.9f, routeAlignmentQuality = 0.35f)

        assertEquals(0.35f, context.safeMotionQuality, 0.001f)
    }
}
