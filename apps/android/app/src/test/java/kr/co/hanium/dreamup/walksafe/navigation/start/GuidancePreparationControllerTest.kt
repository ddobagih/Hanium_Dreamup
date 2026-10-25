package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

class GuidancePreparationControllerTest {
    @Test fun constructorReadsNoActorLifecycleOrClockAndSchedulesNothing() {
        val f = NativeGuidanceTestFixture()
        assertEquals(0, f.reads)
        assertTrue(f.events.isEmpty())
        assertTrue(f.preparationPosts.isEmpty())
        assertSame(f.preparation.installedStart, f.preparation.installedStart)
    }

    @Test fun initialFourSecondPollKeepsLocationAcquisitionSilentAndUsesOriginalFiveHundredMsCadence() {
        val f = ready()
        f.preparation.begin(f.destination)
        assertEquals(4_000L, f.preparationPosts.single().first)
        f.preparationPosts.single().second.run()
        assertEquals(500L, f.preparationPosts.last().first)
        assertFalse(f.events.contains("wait_speech"))
        assertNotNull(f.preparation.pending)
    }

    @Test fun retiredActorCancelsPendingPollInsteadOfClaimingDelivery() {
        val f = ready()
        f.preparation.begin(f.destination)
        val callback = f.preparationPosts.removeAt(0).second
        f.actor = "replacement"
        callback.run()
        assertNull(f.preparation.pending)
        assertTrue(f.preparationPosts.isEmpty())
        assertFalse(f.events.contains("wait_speech"))
    }

    @Test fun earlierQueuedPollCannotMutateANewerPreparationOwner() {
        val f = ready()
        f.preparation.begin(f.destination)
        val old = f.preparationPosts.single().second
        f.preparation.begin(f.destination)
        val current = f.preparation.pending
        val callbacks = f.preparationPosts.size
        old.run()
        assertSame(current, f.preparation.pending)
        assertEquals(callbacks, f.preparationPosts.size)
    }

    @Test fun cancelRetiresOnlyTheMatchingCommandGenerationWithoutAdvancingIt() {
        val f = ready()
        f.preparation.begin(f.destination)
        val pending = f.preparation.pending!!
        pending.responseGeneration = 2L
        f.preparation.cancel()
        assertEquals(listOf("cancel_response:2", "cancel_interaction"), f.events.filter { !it.startsWith("page:") })
        assertEquals(2L, f.commandGeneration)
        assertNull(pending.responseGeneration)
        assertNull(f.preparation.pending)
    }

    @Test fun oldPreparationCannotCancelANewerCommandResponse() {
        val f = ready()
        f.preparation.begin(f.destination)
        f.preparation.pending!!.responseGeneration = 1L
        f.preparation.cancel()
        assertFalse(f.events.any { it.startsWith("cancel_") })
    }

    @Test fun actualInstalledStartFailureOwnerClearsOnlyItsCurrentVisibleFailure() {
        val f = ready()
        val installed = f.preparation.installedStart
        installed.install("12시 방향 20m 이동하세요.") { true }
        installed.inheritWaitingFailure("실패")
        f.preparation.reportVisibleFailure("실패")
        var refreshed = 0
        assertFalse(f.preparation.clearWaitingFailureIfCurrent({ false }) { refreshed++ })
        assertEquals("실패", f.preparation.visibleFailure)
        assertTrue(f.preparation.clearWaitingFailureIfCurrent({ true }) { refreshed++ })
        assertNull(f.preparation.visibleFailure)
        assertEquals(1, refreshed)
    }

    private fun ready() = NativeGuidanceTestFixture().apply {
        workflow.nativeUiPage = NativeUiPage.GUIDANCE
        workflow.pendingUiDestination = destination
        events.clear()
    }
}
