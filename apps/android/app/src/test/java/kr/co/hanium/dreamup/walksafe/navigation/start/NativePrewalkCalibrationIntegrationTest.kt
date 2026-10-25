package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

/** Actual prewalk and calibration owners; speech, UI queue, permissions and gateway leaves are controlled. */
class NativePrewalkCalibrationIntegrationTest {
    @Test fun startupIsPostedOnlyAfterActualPromptDoneAndItsAdditionalThreeSeconds() {
        val f = prepared()
        f.completeReadiness()
        val prompt = f.calibrationPrompts.single()
        assertEquals(GuidanceStartCalibration.PROMPT_KO, prompt.message)
        assertTrue(prompt.current())
        f.now += 5_000L
        f.runCalibrationPost()
        assertTrue(f.startupPosts.isEmpty())
        assertEquals("WAIT_DIRECTION_CALIBRATION", f.lastPhase)
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.calibration.snapshot?.phase)
        assertNull(f.calibration.snapshot?.readyAtMs)
        assertEquals(1, f.calibrationPrompts.size)

        prompt.completed()
        assertFalse(prompt.current())
        assertEquals(f.now + 3_000L, f.calibration.snapshot?.readyAtMs)
        f.now += 2_999L
        f.runCalibrationPost()
        assertTrue(f.startupPosts.isEmpty())
        assertEquals(1L, f.calibrationPosts.single().first)
        f.now++
        f.runCalibrationPost()
        assertEquals(GuidanceStartCalibration.Phase.READY, f.calibration.snapshot?.phase)
        assertEquals(1, f.startupPosts.size)
        assertFalse(f.events.contains("completeStartupConfirmation"))
        f.startupPosts.single().invoke()
        assertTrue(f.events.contains("completeStartupConfirmation"))
        assertEquals(1, f.calibrationPrompts.size)
    }

    @Test fun failedPromptRetriesTheSameIntentAndItsLateDoneCannotCompleteTheReplacement() {
        val f = prepared()
        f.completeReadiness()
        val old = f.calibrationPrompts.single()
        val attempt = f.calibration.currentAttempt
        f.now += 500L
        old.failed()
        assertSame(attempt, f.calibration.currentAttempt)
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.calibration.snapshot?.phase)
        assertNull(f.calibration.snapshot?.readyAtMs)
        f.now += 1_000L
        f.runCalibrationPost()
        val retry = f.calibrationPrompts.last()
        assertEquals(2, f.calibrationPrompts.size)
        assertSame(old.lease.attempt, retry.lease.attempt)
        assertTrue(retry.lease.serial > old.lease.serial)
        old.completed()
        old.failed()
        assertTrue(retry.current())
        assertNull(f.calibration.snapshot?.readyAtMs)
        assertTrue(f.startupPosts.isEmpty())
        retry.completed()
        f.now += 3_000L
        f.runCalibrationPost()
        assertEquals(1, f.startupPosts.size)
    }

    @Test fun busySpeechRetainsThePendingIntentAndRetriesWithoutAnotherStartButton() {
        val f = prepared(speechBusy = true)
        f.completeReadiness()
        val attempt = f.calibration.currentAttempt
        assertTrue(f.calibrationPrompts.isEmpty())
        assertEquals(1_000L, f.calibrationPosts.single().first)
        f.now += 1_000L
        f.runCalibrationPost()
        assertTrue(f.calibrationPrompts.isEmpty())
        assertTrue(f.startupPosts.isEmpty())
        f.calibrationSpeechBusy = false
        f.now += 1_000L
        f.runCalibrationPost()
        assertSame(attempt, f.calibration.currentAttempt)
        assertEquals(1, f.calibrationPrompts.size)
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.calibration.snapshot?.phase)
    }

    @Test fun rejectedSpeechSubmissionReleasesItsLeaseForAnAutomaticCurrentRetry() {
        val f = prepared(acceptSpeech = false)
        f.completeReadiness()
        val old = f.calibrationPrompts.single()
        assertFalse(old.current())
        assertNull(f.calibration.snapshot?.prompt)
        assertTrue(f.startupPosts.isEmpty())
        f.acceptCalibrationSpeech = true
        f.now += 1_000L
        f.runCalibrationPost()
        val retry = f.calibrationPrompts.last()
        assertSame(old.lease.attempt, retry.lease.attempt)
        old.completed()
        assertNull(f.calibration.snapshot?.readyAtMs)
        assertTrue(retry.current())
        retry.completed()
        f.now += 3_000L
        f.runCalibrationPost()
        assertEquals(1, f.startupPosts.size)
    }

    @Test fun changedOwnerEpochDestinationPermissionOrCancellationRetiresThePromptAndTimer() {
        val changes: List<(NativeGuidanceTestFixture) -> Unit> = listOf(
            { it.actor = "replacement" },
            { it.gatewayGeneration++ },
            { it.walk = it.walk.copy(epoch = WalkRuntimeEpoch("walk", 1L), readiness = null) },
            { it.workflow.pendingUiDestination = it.destination.copy(id = "another-place") },
            { it.foreground = false },
            { it.locationPermission = false },
            { it.firstRunComplete = false },
            { it.walk = it.walk.copy(state = WalkSessionState.ENDED, mode = WalkSessionMode.UNAVAILABLE) },
            { it.prewalk.cancel(cancelFeatureEntry = true) },
        )
        for (change in changes) {
            for (doneBeforeChange in listOf(false, true)) {
                val f = prepared()
                f.completeReadiness()
                val old = f.calibrationPrompts.single()
                val queued = f.calibrationPosts.single().second
                f.now++
                if (doneBeforeChange) old.completed()
                change(f)
                f.prewalk.continueIfReady()
                assertNull(f.calibration.currentAttempt)
                assertFalse(old.current())
                assertTrue(f.calibrationPosts.isEmpty())
                f.now += 3_000L
                old.completed()
                queued.run()
                assertTrue(f.startupPosts.isEmpty())
                assertFalse(f.events.contains("completeStartupConfirmation"))
            }
        }
    }

    @Test fun aQueuedConfirmationForTheRetiredRequestCannotConfirmTheNewCalibratedRequest() {
        val f = prepared()
        f.completeReadiness()
        f.calibrationPrompts.single().completed()
        f.now += 3_000L
        f.runCalibrationPost()
        val oldConfirmation = f.startupPosts.removeAt(0)
        val oldAttempt = f.calibration.currentAttempt
        f.prewalk.cancel(cancelFeatureEntry = false)
        f.now++
        f.prewalk.beginFromHome()
        assertNotSame(oldAttempt, f.calibration.currentAttempt)
        val newPrompt = f.calibrationPrompts.last()
        newPrompt.completed()
        f.now += 3_000L
        f.runCalibrationPost()
        assertEquals(1, f.startupPosts.size)
        f.events.clear()
        oldConfirmation()
        assertFalse(f.events.contains("completeStartupConfirmation"))
        assertEquals(1, f.startupPosts.size)
        f.startupPosts.single().invoke()
        assertTrue(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun failedPreflightRequestsAutomaticRecoveryWithoutRepeatingTheCalibrationPrompt() {
        val f = prepared()
        f.completeReadiness()
        val request = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
        f.preflightFailed = true
        f.calibrationPrompts.single().completed()
        f.now += 3_000L
        f.runCalibrationPost()
        assertEquals("MEASUREMENT_FAILED_OR_TIMED_OUT", f.lastPhase)
        assertSame(request, f.preparationRetries.single())
        assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
        assertTrue(f.startupPosts.isEmpty())
        f.prewalk.continueIfReady()
        assertEquals(2, f.preparationRetries.size)
        assertTrue(f.preparationRetries.all { it === request })
        assertEquals(1, f.speechSubmissions)
        assertEquals(1, f.calibrationPrompts.size)
        f.preflightFailed = false
        f.prewalk.continueIfReady()
        assertEquals(1, f.startupPosts.size)
        assertEquals(1, f.calibrationPrompts.size)
    }

    @Test fun elapsedCalibrationDoesNotBypassTheActualStartupReadinessRequirements() {
        val f = prepared()
        f.calibrationPrompts.single().completed()
        f.now += 3_000L
        f.runCalibrationPost()
        assertEquals(GuidanceStartCalibration.Phase.READY, f.calibration.snapshot?.phase)
        assertEquals("WAIT_ENVIRONMENT_OR_MOUNTING", f.lastPhase)
        assertTrue(f.startupPosts.isEmpty())
        assertNull(f.walk.confirmationToken)
        f.completeReadiness()
        f.prewalk.continueIfReady()
        assertEquals(1, f.startupPosts.size)
        assertEquals(1, f.calibrationPrompts.size)
    }

    private fun prepared(speechBusy: Boolean = false, acceptSpeech: Boolean = true) =
        NativeGuidanceTestFixture(enableRealCalibration = true).apply {
            workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
            workflow.pendingUiDestination = destination
            calibrationSpeechBusy = speechBusy
            acceptCalibrationSpeech = acceptSpeech
            assertFalse(prewalk.beginFromHome())
        }
}
