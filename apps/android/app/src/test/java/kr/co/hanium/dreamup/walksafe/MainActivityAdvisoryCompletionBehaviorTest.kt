package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.feedback.AndroidNonMetricObstacleAdvisoryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricAdvisoryGate
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryAction
import org.junit.Assert.*
import org.junit.Test

/** Real Main completion handler and advisory policy; injected ownership is not a physical sensor result. */
class MainActivityAdvisoryCompletionBehaviorTest {
    private val gate = NonMetricAdvisoryGate(true, true, true, true, false)
    private val obstacle = DetectionCandidate("moving_e_scooter", 0.9f, RectNorm(0.35f, 0.3f, 0.3f, 0.65f))

    @Test fun actualDoneAfterStartDeadlineRecordsOneDeliveryAndPreventsImmediateRepeat() {
        val (main, policy, action) = claimed()
        val doneAt = action.validUntilMs + 2_000L
        assertTrue(main.completeCameraFallbackAdvisory(action, doneAt) { true })
        assertFalse(main.completeCameraFallbackAdvisory(action, doneAt + 1L) { true })
        assertNull(policy.evaluate(listOf(obstacle), gate, 4L, doneAt + 100L))
        assertNull(policy.evaluate(listOf(obstacle), gate, 5L, doneAt + 500L))
        assertNull(policy.evaluate(listOf(obstacle), gate, 6L, doneAt + 900L))
    }

    @Test fun aFrameWithoutTheCandidateAfterStartDoesNotEraseActualDone() {
        val (main, policy, action) = claimed()
        assertNull(policy.evaluate(emptyList(), gate, 4L, action.observedAtMs + 100L))
        assertTrue(main.completeCameraFallbackAdvisory(action, action.validUntilMs + 2_000L) { true })
    }

    @Test fun retiredWalkOrMountingRejectsLateDoneWithoutConsumingTheRepeatInterval() {
        listOf(false, true).forEach { loseMounting ->
            val (main, policy, action) = claimed()
            if (loseMounting) set(main, "phoneMountingOutputsAllowed", false)
            assertFalse(main.completeCameraFallbackAdvisory(action, action.validUntilMs + 2_000L) { loseMounting })
            assertFalse(policy.isDeliveryCurrent(action))
            assertFalse(policy.confirmDelivery(action, action.validUntilMs + 2_001L))
        }
    }

    @Test fun obsoleteCompletionCannotRetireAReplacementReservation() {
        val (main, policy, old) = claimed()
        policy.rejectDelivery(old)
        val replacement = requireNotNull(policy.evaluate(listOf(obstacle), gate, 4L, 1_800L))
        assertTrue(policy.claimDelivery(replacement))
        assertFalse(main.completeCameraFallbackAdvisory(old, 1_900L) { false })
        assertTrue(policy.isDeliveryCurrent(replacement))
        assertTrue(main.completeCameraFallbackAdvisory(replacement, 5_000L) { true })
    }

    private fun claimed(): Triple<MainActivity, AndroidNonMetricObstacleAdvisoryPolicy, NonMetricObstacleAdvisoryAction> {
        val main = MainActivity()
        set(main, "phoneMountingOutputsAllowed", true)
        val policy = MainActivityControllerTestFixture.field("nonMetricAdvisoryPolicy")
            .apply { isAccessible = true }.get(main) as AndroidNonMetricObstacleAdvisoryPolicy
        policy.evaluate(listOf(obstacle), gate, 1L, 1_000L)
        policy.evaluate(listOf(obstacle), gate, 2L, 1_400L)
        val action = requireNotNull(policy.evaluate(listOf(obstacle), gate, 3L, 1_700L))
        assertTrue(policy.claimDelivery(action))
        return Triple(main, policy, action)
    }

    private fun set(main: MainActivity, name: String, value: Any) {
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
    }
}
