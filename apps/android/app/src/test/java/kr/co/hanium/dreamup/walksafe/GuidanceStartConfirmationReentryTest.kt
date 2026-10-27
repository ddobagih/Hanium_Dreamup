package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: a render queued before start was accepted must not open another confirmation. */
class GuidanceStartConfirmationReentryTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun body(name: String) = source.substringAfter("private fun $name(")
        .substringBefore("\n    private fun ")

    @Test fun queuedRenderRechecksConfirmationOwnershipAndStartProgress() {
        val prewalk = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/start/NativePrewalkController.kt").readText()
        val posted = prewalk.substringAfter("confirmationPosted = true").substringBefore("diagnostics.directStart")
        for (guard in listOf("workflow.nativePrewalkStartEpoch != epoch", "workflow.nativePhoneMountingCheckRequest != request",
            "scope.gatewayGeneration() != request.gatewayGeneration", "!scope.foreground()", "actorId != scope.reporterId()",
            "checks.startupDecision() != decision", "checks.readinessBlockReason(decision) != null",
            "currentWalk.confirmationToken != token", "!checks.preflightFailedOrTimedOut()", "!checks.startupRetryRequired()")) {
            assertTrue("Missing queued-callback guard: $guard", posted.contains(guard))
        }
        val open = body("requestNativeGuidanceStartConfirmation")
        assertTrue(open.contains("nativeGuidanceConfirmationScreen != null || nativeGuidanceConfirmationAction != null"))
        assertTrue(open.contains("startupCapabilityConfirmationPending"))

    }

    @Test fun duplicateButtonCallbackCannotStartOrCancelAnotherAttempt() {
        val finish = body("finishNativeGuidanceConfirmation")
        assertTrue(finish.contains("val action = nativeGuidanceConfirmationAction ?: return"))
        assertTrue(finish.indexOf("nativeGuidanceConfirmationAction = null") <
            finish.indexOf("cancelVoiceCommandRecognition()"))
        assertTrue(finish.indexOf("nativeGuidanceConfirmationGeneration++") <
            finish.indexOf("cancelVoiceCommandRecognition()"))
        assertTrue(finish.indexOf("removeNativeGuidanceConfirmationScreen()") < finish.indexOf("action()"))
    }
}
