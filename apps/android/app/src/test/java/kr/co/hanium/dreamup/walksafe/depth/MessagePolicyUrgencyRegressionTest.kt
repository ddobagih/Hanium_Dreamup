package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-37: motion commentary must not delay the already selected emergency action. */
class MessagePolicyUrgencyRegressionTest {
    @Test
    fun stopActionPrecedesMeasuredApproachCommentary() {
        val decision = decision(distanceM = 1f, confidence = .9f)
        assertEquals(MessageLevel.STOP, decision.userFacing.messageLevel)
        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertActionBeforeMotion(requireNotNull(decision.userFacing.message), "멈추세요")
    }

    @Test
    fun warningPreparationPrecedesMeasuredApproachWithoutEscalatingToStop() {
        val decision = decision(distanceM = 2f, confidence = .6f)
        assertEquals(MessageLevel.WARNING, decision.userFacing.messageLevel)
        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertActionBeforeMotion(requireNotNull(decision.userFacing.message), "멈출 준비를 하세요")
    }

    private fun assertActionBeforeMotion(message: String, action: String) {
        val actionAt = message.indexOf(action)
        assertTrue("The selected action remains audible: $message", actionAt >= 0)
        assertTrue("Unverified movement commentary must be omitted: $message", !message.contains("사용자 쪽으로"))
        if (action == "멈추세요") assertTrue("Stop must be first: $message", message.startsWith("멈추세요."))
    }

    private fun decision(distanceM: Float, confidence: Float) = MessagePolicy().evaluate(
        MetricDepthDecision(
            className = "person",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = distanceM,
            trend = Trend.APPROACHING,
            confidenceFinal = confidence,
            trackKey = "person-urgent",
            objectMotion = ObjectMotion.OBJECT_APPROACHING,
            motionEstimate = ObjectMotionEstimate(
                referenceId = 1L,
                observedAtMs = 1_000L,
                elapsedMs = 1_000L,
                objectVelocityInAnchorMps = Vec3(0f, 0f, -.6f),
                relativeVelocityInAnchorMps = Vec3(0f, 0f, -.6f),
                cameraVelocityInAnchorMps = Vec3(0f, 0f, 0f),
                objectSpeedMps = .6f,
                relativeClosingSpeedMps = .6f,
                direction = ObjectMovementDirection.TOWARD_USER,
                confidence = .9f,
            ),
        ),
        nowMs = 1_000L,
    )
}
