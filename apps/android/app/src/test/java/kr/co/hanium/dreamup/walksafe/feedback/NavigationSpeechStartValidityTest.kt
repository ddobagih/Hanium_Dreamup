package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSpeechStartValidityTest {
    @Test
    fun omittedValidatorPreservesExistingNavigationCallers() {
        assertTrue(speechStartIsCurrent(1_000L))
    }

    @Test
    fun invalidImmediateRequestCannotStart() {
        assertFalse(speechStartIsCurrent(1_000L, validator = { false }))
    }

    @Test
    fun captureDeadlineIsRecheckedAfterQueueDelayWithoutRenewal() {
        var now = 1_000L
        val capturedAt = now
        val validUntil = capturedAt + 800L
        val outputCurrent = { now <= validUntil }
        assertTrue(speechStartIsCurrent(now, validator = outputCurrent))
        now = validUntil
        assertTrue(speechStartIsCurrent(now, validator = outputCurrent))
        now += 1L
        assertFalse(speechStartIsCurrent(now, validator = outputCurrent))
        assertFalse(speechStartIsCurrent(now + 50L, validator = outputCurrent))
    }

    @Test
    fun sessionRevocationBetweenSubmissionAndQueueReleaseCancelsCurrentSpeech() {
        var epoch = 4L
        val submittedEpoch = epoch
        val outputCurrent = { epoch == submittedEpoch }
        assertTrue(speechStartIsCurrent(1_000L, validator = outputCurrent))
        epoch += 1L
        assertFalse(speechStartIsCurrent(1_001L, validator = outputCurrent))
    }

    @Test
    fun validatorFailureIsNotPermissionToStart() {
        assertFalse(speechStartIsCurrent(1_000L, validator = { error("revoked owner") }))
    }

    @Test
    fun expiredRegisteredDeadlineCannotBeOverriddenByCurrentValidator() {
        assertFalse(speechStartIsCurrent(1_801L, 1_800L) { true })
    }

    @Test
    fun queueExpiryTerminatesOnlyCancellationAndLateCallbacksCannotFallback() {
        val callbacks = UtteranceCallbackRegistry()
        var completed = 0
        var failed = 0
        var cancelled = 0
        callbacks.register(
            utteranceId = "navigation-1",
            onCompleted = { completed += 1 },
            onFailed = { failed += 1 },
            onCancelled = { cancelled += 1 },
        )
        if (!speechStartIsCurrent(1_801L, 1_800L) { true }) {
            callbacks.takeTerminalCallback(
                utteranceId = "navigation-1",
                completed = false,
                notifyFailure = true,
                cancelled = true,
            )?.invoke()
        }
        callbacks.takeTerminalCallback("navigation-1", completed = false, notifyFailure = true)?.invoke()
        callbacks.takeTerminalCallback("navigation-1", completed = true, notifyFailure = true)?.invoke()
        assertEquals(1, cancelled)
        assertEquals(0, failed)
        assertEquals(0, completed)
    }

    @Test
    fun actuatorRetainsNavigationValidatorAndChecksBothSubmissionAndQueueRelease() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
        ).readText()
        val navigation = source.substringAfter("fun speakNavigation(")
            .substringBefore("fun cancelNavigationSpeech()")
        assertTrue(navigation.contains("isStillValidAtStart: () -> Boolean = { true }"))
        assertTrue(navigation.contains("validator = isStillValidAtStart"))
        assertTrue(navigation.contains("onCancelled?.invoke()"))
        assertTrue(navigation.contains("NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(navigation.contains("advisoryStartDeadlineMs = Long.MAX_VALUE"))
        assertTrue(navigation.contains("advisoryStartValidator = isStillValidAtStart"))
        assertTrue(navigation.contains("onCancelled = onCancelled"))
        val dispatch = source.substringAfter("private fun speakReady(")
            .substringBefore("private fun vibrate(")
        val finalCheck = dispatch.indexOf("if (!isUtteranceStillValidAtStart(utteranceId))")
        val submission = dispatch.indexOf("val speakResult: Int? = try {")
        val injectedSubmission = dispatch.indexOf("speechRuntime.speak(message, queueMode, utteranceId)")
        val platformSubmission = dispatch.indexOf("textToSpeech.speak(message, queueMode, params, utteranceId)")
        assertTrue(finalCheck >= 0 && submission > finalCheck)
        assertTrue(injectedSubmission > submission)
        assertTrue(platformSubmission > submission)
        val start = source.substringAfter("override fun onStart(")
            .substringBefore("override fun onDone(")
        assertTrue(start.contains("isUtteranceStillValidAtStart(utteranceId)"))
        val validation = source.substringAfter("private fun isUtteranceStillValidAtStart(")
            .substringBefore("private fun armUtteranceTerminalWatchdog(")
        assertTrue(validation.contains("if (utteranceId !in pendingUtterances) return false"))
        assertTrue(validation.contains("speechStartIsCurrent("))
        assertTrue(validation.contains("completed = false, cancelled = true"))
    }
}
