package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test

class MetricDepthCapabilityRetentionTest {
    private fun retain(
        reason: String = "app_paused",
        previous: Boolean? = true,
        state: PostLoginMetricDepthState = PostLoginMetricDepthState.SUPPORTED,
        complete: Boolean = true,
        bound: Boolean = true,
        deferred: Boolean = false,
        refresh: Boolean = false,
    ) = retainedMetricDepthCapabilityAfterLifecycle(reason, previous, state, complete, bound,
        deferred, refresh)

    @Test fun backgroundReturnKeepsMeasuredApiSupportAndExplicitUnsupportedResult() {
        for (reason in listOf("app_paused", "first_run_stage_changed", "new_walk")) {
            assertEquals(true, retain(reason = reason))
            assertEquals(false, retain(reason = reason, previous = false,
                state = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED))
        }
    }

    @Test fun runtimeFailureCannotBeRevivedBySubsequentPauseOrOnboardingRefresh() {
        for (reason in listOf("app_paused", "first_run_stage_changed", "new_walk")) {
            assertNull(retain(reason = reason, previous = null))
            assertNull(retain(reason = reason, previous = false))
            assertNull(retain(reason = reason, previous = true,
                state = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED))
        }
        for (reason in listOf("runtime_session_start_failed", "runtime_arcore_install_required",
            "runtime_arcore_availability_unknown", "runtime_detector_start_unavailable",
            "app_destroyed", "post_login_device_check_session_changed")) {
            assertNull(retain(reason = reason))
        }
    }

    @Test fun uncertainOrLegacyFrameResultsNeverBecomeApiSupportEvidence() {
        for (state in listOf(PostLoginMetricDepthState.PENDING, PostLoginMetricDepthState.UNKNOWN,
            PostLoginMetricDepthState.TIMED_OUT, PostLoginMetricDepthState.AVAILABLE)) {
            for (previous in listOf(true, false, null)) assertNull(retain(previous = previous, state = state))
        }
    }

    @Test fun changingAccountOrRecheckingTheDeviceInvalidatesSupport() {
        assertNull(retain(bound = false))
        assertNull(retain(deferred = true))
        assertNull(retain(refresh = true))
        assertNull(retain(complete = false))
    }

    @Test fun returningFromBackgroundDoesNotLeaveAnOtherwiseReadyWalkWaitingForMetricSupport() {
        fun decision(metric: Boolean?) = WalkSafeStartupCapabilityResolver.resolve(
            WalkSafeStartupCapabilityInput(true, true, true, true, true, true, true,
                metric, false, null), approvedDeviceProfileRequired = false,
        )
        val oldPause = decision(null)
        assertFalse(oldPause.mayConfirmAndStart)
        assertEquals(listOf(WalkSafeStartupRequirement.METRIC_DISTANCE), oldPause.pendingRequirements)
        assertEquals(WalkSafeStartupCapabilityTier.FULL, decision(retain()).tier)
        assertTrue(decision(retain()).mayConfirmAndStart)
        assertEquals(WalkSafeStartupCapabilityTier.LIMITED,
            decision(retain(previous = false, state = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)).tier)
        assertFalse(decision(retain(bound = false)).mayConfirmAndStart)
        assertFalse(decision(retain(previous = null)).mayConfirmAndStart)
    }

    @Test fun pendingLiveVoiceProbeStillBlocksWalkWhileIndependentDepthSupportIsRetained() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            WalkSafeStartupCapabilityInput(true, true, true, true, true, true, null,
                retain(), false, null), approvedDeviceProfileRequired = false,
        )
        assertFalse(decision.mayConfirmAndStart)
        assertEquals(listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), decision.pendingRequirements)
    }
    @Test fun newWalkRetainsOnlyCurrentTypedApiEvidenceAndNeverUsesSavedFullAsSupport() {
        assertEquals(true, retain(reason = "new_walk"))
        assertEquals(false, retain(reason = "new_walk", previous = false, state = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED))
        assertNull(retain(reason = "new_walk", previous = null))
        assertNull(retain(reason = "new_walk", previous = false))
        assertNull(retain(reason = "new_walk", state = PostLoginMetricDepthState.UNKNOWN))
        assertNull(retain(reason = "new_walk", bound = false))
        assertNull(retain(reason = "new_walk", complete = false))
        assertNull(retain(reason = "new_walk", refresh = true))
        assertNull(retain(reason = "new_walk", deferred = true))
        assertNull(retain(reason = "new_walk_unrecognized"))
    }

}
