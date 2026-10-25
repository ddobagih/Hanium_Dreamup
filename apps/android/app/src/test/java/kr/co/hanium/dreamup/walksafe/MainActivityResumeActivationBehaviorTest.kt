package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionResumeConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Actual Main completion guards. Successful Android activation and native output are not run. */
class MainActivityResumeActivationBehaviorTest {
    @Test
    fun completionWithoutAPendingTokenDoesNotPublishSuccess() {
        val fixture = ResumeMainBehaviorFixture()
        val before = fixture.lifecycle.snapshot()

        fixture.complete(before.epoch)
        fixture.complete(before.epoch)

        fixture.assertNoAdmissionOrStatus(before, "no pending activation")
    }

    @Test
    fun oldCameraReleaseCallbackCannotConsumeTheSuccessorActivationToken() {
        val fixture = ResumeMainBehaviorFixture()
        val oldEpoch = fixture.lifecycle.snapshot().epoch
        fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        fixture.prepareResumeReadiness()
        val successorToken = fixture.token()
        fixture.set("walkSessionResumeActivationToken", successorToken)
        val before = fixture.lifecycle.snapshot()

        fixture.complete(oldEpoch)
        fixture.complete(oldEpoch)

        assertEquals(before, fixture.lifecycle.snapshot())
        assertSame(successorToken, fixture.field("walkSessionResumeActivationToken"))
        fixture.assertNoStatusAndNoActuatorInstance("old camera release")
    }

    @Test
    fun foregroundLossClearsTheCurrentActivationAndItsReplayCannotPublishSuccess() {
        val fixture = activeFixture()
        val token = fixture.token()
        fixture.set("walkSessionResumeActivationToken", token)
        fixture.set("isActivityForeground", false)

        fixture.complete(token.epoch)

        assertNull(fixture.field("walkSessionResumeActivationToken"))
        fixture.assertNoStatusAndNoActuatorInstance("background completion")
        fixture.set("isActivityForeground", true)
        fixture.complete(token.epoch)
        fixture.complete(token.epoch)
        assertNull(fixture.field("walkSessionResumeActivationToken"))
        fixture.assertNoStatusAndNoActuatorInstance("duplicate background completion")
    }

    @Test
    fun incompleteOnboardingGateClearsThePendingActivation() {
        val fixture = activeFixture()
        val token = fixture.token()
        fixture.set("walkSessionResumeActivationToken", token)
        // firstRunOnboardingSnapshot is also uninitialized. This covers the overall incomplete
        // onboarding gate; it cannot isolate this flag or fail solely when its check is removed.
        fixture.set("initialAppPermissionExitRequired", true)

        fixture.complete(token.epoch)
        fixture.complete(token.epoch)

        assertNull(fixture.field("walkSessionResumeActivationToken"))
        fixture.assertNoStatusAndNoActuatorInstance("incomplete onboarding gate")
    }

    private fun activeFixture(): ResumeMainBehaviorFixture = ResumeMainBehaviorFixture().also { fixture ->
        fixture.lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(
            WalkSessionResumeConfirmation.START, fixture.token()))
        assertEquals(WalkSessionState.ACTIVE, fixture.lifecycle.snapshot().state)
    }
}
