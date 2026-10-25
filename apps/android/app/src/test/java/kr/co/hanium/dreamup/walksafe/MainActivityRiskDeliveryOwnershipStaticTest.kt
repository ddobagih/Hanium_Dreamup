package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native admission and policy ownership are tested directly by the feedback regression suite. */
class MainActivityRiskDeliveryOwnershipStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun claimsAndEveryTerminalUseTheExactIssuedActionOwner() {
        val emit = block("private fun emitFeedbackAction(")
        assertTrue(emit.contains("claimFeedbackDelivery(action, policyEvaluatedAtMs"))
        assertTrue(emit.contains("hasClaimedFeedbackDelivery(action)"))
        assertTrue(emit.contains("rejectUndeliveredFeedback(action, policyEvaluatedAtMs)"))
        assertFalse(emit.contains("rejectUndeliveredFeedback(action.trackId"))
        val confirm = block("private fun confirmFeedbackDelivery(").replace(Regex("\\s+"), " ")
        assertTrue(confirm.contains("feedbackPolicy.confirmFeedbackDelivery( action, policyEvaluatedAtMs, deliveredAtMs"))
        assertFalse(confirm.contains("feedbackPolicy.confirmFeedbackDelivery( action.trackId"))
        val cancel = delegatedOwner("cancelPendingFeedbackTerminalResolution", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "cancelPendingFeedbackTerminalResolution", "= this@MainActivity.riskFeedbackComponents.delivery.cancelPendingFeedbackTerminalResolution()").replace(Regex("\\s+"), " ")
        assertTrue(cancel.contains("rejectUndeliveredFeedback( pending.action, pending.policyEvaluatedAtMs"))
    }

    @Test
    fun proposalDoesNotCancelTheOldTerminalTimerBeforeNativeOrHapticAdmission() {
        val emit = block("private fun emitFeedbackAction(")
        assertTrue(emit.contains("if (action.replacesDeliveryOwner == null) cancelPendingFeedbackTerminalResolution()"))
        val completion = emit.substringAfter("val completionCurrent: () -> Boolean = {")
            .substringBefore("val outputCurrent")
        assertTrue(completion.contains("admission.runtimeEpochOrNull() == expectedWalkEpoch"))
        assertTrue(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents")
            .contains("override fun runtimeEpochOrNull() = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(completion.contains("hasClaimedFeedbackDelivery(action)"))
        assertTrue(emit.contains("pending?.action == action && pending.policyEvaluatedAtMs == policyEvaluatedAtMs"))
        assertTrue(emit.contains("vibrationAccepted = dispatch.vibrationAccepted"))
        assertTrue(emit.contains("if (vibrationAccepted)"))
        assertTrue(block("private fun scheduleFeedbackTerminalResolution(").contains("cancelPendingFeedbackTerminalResolution()"))
    }

    private fun block(signature: String): String = MainActivityRefactorSource.function(source, signature)

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
