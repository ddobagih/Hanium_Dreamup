package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidencePolicy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceTransition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main wiring; lifecycle freshness/ownership and positioning behavior have executable tests. */
class MainActivityNativeDirectStartStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun anExplicitNativeStartKeepsLiveGatesWithoutRepeatingTheSafetySpeech() {
        val body = MainActivityRefactorSource.function(source, "private fun continueNativePrewalkIfReady()")
        assertTrue(body.contains("checks.environmentReadiness(epoch)"))
        assertTrue(body.contains("checks.mountingReadiness(epoch)"))
        assertTrue(body.contains("!checks.firstRunComplete()"))
        assertTrue(body.contains("actions.completeStartupConfirmation(decision)"))
        assertFalse(body.contains("handleStartupCapabilityConfirmAction()"))
        assertFalse(body.contains("deliverStartupCapabilityNotice("))
    }

    @Test
    fun postedNativeConfirmationRejectsChangedTokensActorsSessionsAndReadiness() {
        val posted = MainActivityRefactorSource.function(source, "private fun continueNativePrewalkIfReady()")
            .substringAfter("actions.postStartup {")
        val confirm = posted.indexOf("actions.completeStartupConfirmation(decision)")
        listOf(
            "currentWalk.confirmationToken != token",
            "currentWalk.epoch != epoch",
            "workflow.nativePhoneMountingCheckRequest != request",
            "scope.gatewayGeneration() != request.gatewayGeneration",
            "actorId != scope.reporterId()",
            "!scope.foreground()",
            "checks.startupDecision() != decision",
            "checks.readinessBlockReason(decision) != null",
        ).forEach { guard ->
            val index = posted.indexOf(guard)
            assertTrue(guard, index >= 0 && confirm > index)
        }
    }

    @Test
    fun genuinePositionLossStillPausesUntilReceivedFixesConfirmRecovery() {
        val body = MainActivityRefactorSource.function(source, "private fun applyPositionConfidenceDecision(")
        assertTrue(body.contains("positionGuidancePaused = decision.guidancePaused"))
        assertTrue(body.contains("speech.cancelNavigation()"))
        // The policy owns usable-quality recovery now; one received fix is still recovery pending.
        for (quality in listOf(PositionQuality.HIGH, PositionQuality.MEDIUM)) {
            val confidence = PositionConfidencePolicy()
            assertFalse(confidence.observe(PositionQuality.HIGH, 500L).guidancePaused)
            val interruption = confidence.observe(PositionQuality.UNAVAILABLE, 1_000L)
            assertEquals(PositionConfidenceTransition.DEGRADATION_PENDING, interruption.transition)
            assertFalse(interruption.guidancePaused)
            val degraded = confidence.observe(PositionQuality.UNAVAILABLE, 4_000L)
            assertEquals(PositionConfidenceTransition.DEGRADED, degraded.transition)
            assertTrue(degraded.guidancePaused)
            val pending = confidence.observe(quality, 5_000L)
            assertEquals(quality, pending.quality)
            assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, pending.transition)
            assertTrue(pending.guidancePaused)
            val recovered = confidence.observe(quality, 6_000L)
            assertEquals(PositionConfidenceTransition.RECOVERED, recovered.transition)
            assertFalse(recovered.guidancePaused)
        }
        assertTrue(body.contains("it.kind == PositionConfidenceAnnouncementKind.RECOVERED"))
        assertTrue(body.contains("confidenceLease.delivered(elapsedRealtimeMs())"))
        assertTrue(body.contains("!speech.commandPending"))
        assertFalse(body.contains("위치를 확인할 수 없어 방향 안내를 중지합니다."))
    }
}
