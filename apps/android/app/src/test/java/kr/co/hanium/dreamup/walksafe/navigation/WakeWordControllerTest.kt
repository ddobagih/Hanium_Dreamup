package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordControllerTest {
    @Test
    fun productionProfileIsNullAndNeverInvokesStart() {
        var starts = 0
        val controller = WakeWordController(startCallback = { starts += 1 })

        assertNull(PRODUCTION_WAKE_WORD_PROFILE)
        assertFalse(
            controller.startIfEligible(
                WalkSessionState.ACTIVE,
                microphonePermissionGranted = true,
                onDeviceRecognitionAvailable = true,
            ),
        )
        assertEquals(0, starts)
    }

    @Test
    fun everyMissingEligibilityConditionKeepsStartCallbackAtZero() {
        data class Case(
            val state: WalkSessionState,
            val microphone: Boolean,
            val onDevice: Boolean,
            val profile: ApprovedWakeWordProfile?,
        )

        listOf(
            Case(WalkSessionState.PAUSED, true, true, PROFILE),
            Case(WalkSessionState.ACTIVE, false, true, PROFILE),
            Case(WalkSessionState.ACTIVE, true, false, PROFILE),
            Case(WalkSessionState.ACTIVE, true, true, null),
        ).forEach { case ->
            var starts = 0
            val controller = WakeWordController(case.profile) { starts += 1 }

            assertFalse(
                controller.startIfEligible(case.state, case.microphone, case.onDevice),
            )
            assertEquals(0, starts)
        }
    }

    @Test
    fun approvedActiveOnDeviceProfileStartsCallbackOnlyOnce() {
        val startedProfiles = mutableListOf<ApprovedWakeWordProfile>()
        val controller = WakeWordController(PROFILE) { profile -> startedProfiles += profile }

        assertTrue(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))
        assertTrue(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))

        assertEquals(listOf(PROFILE), startedProfiles)
    }

    @Test
    fun callbackFailureDoesNotClaimThatWakeWordStarted() {
        var starts = 0
        val controller = WakeWordController(PROFILE) {
            starts += 1
            error("test failure")
        }

        assertFalse(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))
        assertFalse(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))
        assertEquals(2, starts)
    }

    @Test
    fun losingActiveEligibilityStopsOnceAndAllowsALaterRestart() {
        var starts = 0
        var stops = 0
        val controller = WakeWordController(
            approvedProfile = PROFILE,
            stopCallback = { stops += 1 },
            startCallback = { starts += 1 },
        )

        assertTrue(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))
        assertFalse(controller.startIfEligible(WalkSessionState.PAUSED, true, true))
        assertFalse(controller.startIfEligible(WalkSessionState.PAUSED, true, true))
        assertTrue(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))

        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun failedStopRemainsRetryable() {
        var stops = 0
        val controller = WakeWordController(
            approvedProfile = PROFILE,
            stopCallback = {
                stops += 1
                if (stops == 1) error("test failure")
            },
            startCallback = {},
        )

        assertTrue(controller.startIfEligible(WalkSessionState.ACTIVE, true, true))
        assertFalse(controller.stop())
        assertTrue(controller.stop())
        assertEquals(2, stops)
    }

    private companion object {
        val PROFILE = ApprovedWakeWordProfile(
            approvalId = "test-approved-profile-v1",
            exactKoreanPhrase = "워크세이프",
        )
    }
}
