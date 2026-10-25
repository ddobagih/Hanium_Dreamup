package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring only: this does not execute an Activity or simulate a thermal sensor. */
class MainActivityResourcePerformanceReadinessStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun readyCombinationPreservesTheNonBlockingResourceRestriction() {
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        assertTrue(capture.replace(Regex("\\s+"), "").contains(
            "producers.evaluate(requirement,effectiveDecision,epoch,officialAssessment,mountingAssessment,deviceResources,allowModelPreparationPending)",
        ))

        val producers = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText()
        assertTrue(producers.contains("resources.readinessStatus to resources.reason"))
        assertTrue(functionBlock("private fun combineReadinessChecks(").contains("session.readiness.combineReadinessChecks(*checks)"))
        val combination = MainActivityRefactorSource.member(producers, "combineReadinessChecks")
        val readyBranch = combination.substringAfterLast(
            "return WalkSessionReadinessStatus.READY to",
        )
        assertTrue("READY must retain the checked resource reasons", readyBranch.contains("checks"))
        assertTrue(readyBranch.contains("second"))
        assertTrue(readyBranch.contains("filter(String::isNotBlank)"))
        assertTrue(readyBranch.contains("joinToString(\",\")"))
        assertFalse(readyBranch.trimStart().startsWith("\"\""))
    }

    @Test
    fun safetySummaryDistinguishesThermalRestrictionFromExecutableAndStoppedStates() {
        val summary = functionBlock("private fun updateWalkSafetySummary(")
        assertTrue(summary.contains("WalkSessionReadinessRequirement.DEVICE_RESOURCES"))
        assertTrue(summary.contains("reason"))
        assertTrue(summary.contains("thermal_throttled"))
        assertTrue(summary.contains("성능 제한"))
        assertTrue(summary.contains("thermal_throttle_state_pending"))
        assertTrue(summary.contains("확인 중"))
        assertTrue(summary.contains("R.string.walk_safety_active"))
        assertTrue(summary.contains("R.string.walk_safety_stopped"))
        assertTrue(summary.contains("safetySummaryText.text = summary"))
        assertTrue(summary.contains("safetySummaryText.contentDescription = summary"))
    }

    @Test
    fun performancePresentationDoesNotReplaceCurrentEpochEvidenceOrActionExpiryChecks() {
        val delivery = functionBlock("private fun isFeedbackActionStillDeliverable(")
        assertTrue(delivery.contains("val nowMs = scheduler.nowMs()"))
        assertTrue(delivery.contains("admission.runtimeEpochOrNull() ?: return false"))
        assertTrue(delivery.contains("admission.evaluateSafety(nowMs)"))
        assertTrue(delivery.contains("!admission.runtimeEpochCurrent(epoch)"))
        assertTrue(delivery.contains("decision.epoch != epoch || !decision.safetyOutputsAllowed"))
        assertTrue(delivery.contains("nowMs <= action.validUntilMs"))
        assertTrue(delivery.contains("action.deliveryKey in current.activeFeedbackDeliveryKeys"))
        assertTrue(delivery.contains("current.observedAtMs <= nowMs"))
    }

    @Test
    fun resourceChangeRefreshesSummaryAfterConsumingTheCurrentReadiness() {
        val monitor = functionBlock("private fun ensureWalkSessionResourceMonitoring()")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        assertTrue(monitor.contains("refreshStartupCapabilityUi()"))
        val readiness = refresh.indexOf("applyRuntimeReadinessIfActive(decision)")
        val summary = refresh.indexOf("ui.updateSafetySummary()")
        assertTrue(readiness >= 0)
        assertTrue("Use the changed or recovered resource snapshot", summary > readiness)
        assertTrue(summary > refresh.indexOf("ui.advanceAfterCheck()"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
