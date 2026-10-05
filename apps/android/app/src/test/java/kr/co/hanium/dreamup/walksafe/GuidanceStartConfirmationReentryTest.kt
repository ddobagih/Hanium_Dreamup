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
        val posted = body("continueNativePrewalkIfReady").substringAfter("startupCapabilityText.post {")
        val beforeOpen = posted.substringBefore("requestNativeGuidanceStartConfirmation {")
        for (guard in listOf(
            "nativeGuidanceConfirmationGeneration != confirmationGeneration",
            "confirmationToken != readinessToken",
            "nativeGuidanceConfirmationScreen != null",
            "startupCapabilityConfirmationPending",
            "isStartupCapabilityConfirmed()",
            "isWalkSessionRuntimeActive()",
            "startupCapabilityRetryRequiresUserAction",
            "walkSessionReadinessBlockReason(decision) != null",
        )) assertTrue("Missing queued-callback guard: $guard", beforeOpen.contains(guard))
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
