package kr.co.hanium.dreamup.walksafe.device.postlogin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class DeviceCompositeAttemptStateTest {
    @Test fun noDurableWriteCanOccurBeforeActualApplicationVerification() {
        val f = Fixture().running()
        f.apply()
        assertEquals("old", f.stored)
        assertEquals(listOf("apply:new"), f.events)
        assertTrue(f.storage.isEmpty())
        // Dropping this Activity/process at this boundary leaves the previous durable choice.
        assertFalse(f.budget.isDone)
    }

    @Test fun verifiedChoiceIsSavedThenPublishesSuccessOnceAndReleasesAfterOwnerStateIsCleared() {
        val f = Fixture().running()
        f.apply(); f.verify()
        assertEquals("old", f.stored)
        f.runStorage(); assertEquals("new", f.stored)
        assertTrue(f.results.isEmpty())
        var reentered = false
        f.budget.thenRun { assertFalse(f.ownerPresent); reentered = true }
        f.runUi()
        assertEquals(listOf(DeviceCompositeAttemptState.Completion(true, true, "joint_runtime_measured_applied_saved")), f.results)
        assertTrue(reentered)
        assertEquals(listOf("apply:new", "save:new", "owner-cleared", "result:true", "budget-released"), f.events)
        f.finish(true)
        assertEquals(1, f.results.size)
    }

    @Test fun cancelBeforeApplyRejectsTheCandidateAndWaitsForActualWorkerDeath() {
        val f = Fixture().running()
        f.fail("cancel")
        assertFalse(f.apply())
        assertEquals("old", f.stored)
        assertEquals(0, f.nativeRequests)
        assertFalse(f.budget.isDone)
        f.workerDeath()
        assertTrue(f.budget.isDone)
        assertEquals(1, f.results.size)
        assertEquals(1, f.releaseAcknowledgements)
        assertFalse(f.results.single().success)
    }

    @Test fun releasedCandidateCrossingDeadlineBetweenOuterAndInnerChecksCompletesWithoutAnotherWorkerAck() {
        val f = Fixture().running()
        var checks = 0
        val admitted = f.receiveReleased { ++checks == 1 }
        assertFalse(admitted)
        assertEquals(2, checks)
        assertTrue(f.state.hasWorkerReleaseAcknowledgement())
        assertEquals(DeviceCompositeAttemptState.Phase.FINISHED, f.state.phase)
        assertEquals("old", f.stored)
        assertTrue(f.storage.isEmpty())
        assertTrue(f.native.isEmpty())
        assertTrue(f.budget.isDone)
        assertEquals(1, f.results.size)
        assertFalse(f.results.single().success)
        assertTrue(f.results.single().releaseConfirmed)
    }

    @Test fun duplicateReleasedResultsDuringApplicationOrSavingCannotCompleteTheNativeOwner() {
        for (saving in listOf(false, true)) {
            val f = Fixture().running()
            assertTrue(f.receiveReleased())
            if (saving) f.verify()
            val phase = f.state.phase
            assertFalse(f.receiveReleased { error("Duplicate result must not recheck admission") })
            assertEquals(phase, f.state.phase)
            assertTrue(f.ownerPresent)
            assertFalse(f.budget.isDone)
            assertTrue(f.results.isEmpty())
            assertEquals(listOf("apply:new"), f.events)
            f.fail("timeout")
            assertEquals(1, f.nativeRequests)
            assertFalse(f.budget.isDone)
            if (saving) { f.runStorage(); f.runUi() }
            f.runNative()
            assertTrue(f.budget.isDone)
            assertEquals(1, f.results.size)
        }
    }

    @Test fun confirmedPreRunnerReleaseFailurePublishesImmediatelyAndKeepsThePhysicalFence() {
        val f = Fixture()
        f.fail("primary_release_unconfirmed")
        f.fail("duplicate")
        assertEquals(1, f.results.size)
        assertFalse(f.results.single().releaseConfirmed)
        assertEquals(1, f.nativeRequests)
        assertFalse(f.state.beginRunning())
        assertFalse(f.budget.isDone)
        f.nativeFailure = true
        f.runNative()
        assertFalse(f.budget.isDone)
        assertEquals(1, f.results.size)
    }

    @Test fun synchronousFailureCallbackCanCancelAgainWithoutDuplicatingCleanupOrReleasingEarly() {
        val f = Fixture().running()
        f.apply()
        f.onResult = { f.fail("controller_cancel_reentry") }
        f.fail("application_timeout")
        assertEquals(1, f.results.size)
        assertEquals(1, f.nativeRequests)
        assertFalse(f.budget.isDone)
        assertTrue(f.ownerPresent)
        f.runNative()
        assertTrue(f.budget.isDone)
        assertEquals(1, f.releaseAcknowledgements)
        assertEquals(1, f.results.size)
    }

    @Test fun cancellationDuringApplyClosesTheAdoptedCandidateBeforeReleasingBudget() {
        val f = Fixture().running()
        f.apply()
        f.fail("application_timeout")
        assertFalse(f.verify())
        assertEquals("old", f.stored)
        assertFalse(f.budget.isDone)
        assertEquals(1, f.nativeRequests)
        f.runNative()
        assertTrue(f.budget.isDone)
        assertEquals(1, f.results.size)
        assertEquals(1, f.releaseAcknowledgements)
    }

    @Test fun savedWorkerToUiCancellationGapCasRetiresOnlyThisChoiceAndWaitsForBothAcks() {
        val f = Fixture().running()
        f.apply(); f.verify(); f.runStorage()
        assertEquals("new", f.stored)
        f.fail("backgrounded")
        f.runUi() // The old success arrives after cancellation: enqueue CAS, never apply/save again.
        assertEquals(DeviceCompositeAttemptState.Phase.INVALIDATING, f.state.phase)
        f.runNative()
        assertFalse(f.budget.isDone)
        f.runStorage(); assertNull(f.stored)
        f.runUi()
        assertTrue(f.budget.isDone)
        assertEquals(1, f.results.size)
        assertEquals(1, f.releaseAcknowledgements)
    }

    @Test fun storageCleanupCannotReleaseAnAlreadyAdoptedNativeOwnerBeforeItsCloseAck() {
        val f = Fixture().running()
        f.apply(); f.verify(); f.runStorage(); f.fail("cancel"); f.runUi()
        f.runStorage(); f.runUi()
        assertNull(f.stored)
        assertFalse(f.budget.isDone)
        f.runNative()
        assertTrue(f.budget.isDone)
    }

    @Test fun lateRetirementPreservesANewerChoiceAndDuplicateCancelDoesNotRepeatCleanup() {
        val f = Fixture().running()
        f.apply(); f.verify(); f.runStorage(); f.fail("cancel"); f.fail("again")
        f.stored = "newer"
        f.runUi(); f.runStorage(); f.runUi(); f.runNative()
        assertEquals("newer", f.stored)
        assertEquals(1, f.nativeRequests)
        assertEquals(1, f.retireRequests)
        assertEquals(1, f.results.size)
        f.fail("late-old-callback"); f.workerDeath(); f.finish(false)
        assertEquals(1, f.results.size)
        assertEquals(1, f.releaseAcknowledgements)
    }

    @Test fun cancellationAtFinalSuccessCallbackStillRetiresItsDurableChoice() {
        val f = Fixture().running()
        f.apply(); f.verify(); f.runStorage()
        f.retireAtSuccess = true
        f.runUi()
        assertFalse(f.budget.isDone)
        f.runStorage(); f.runUi(); f.runNative()
        assertNull(f.stored)
        assertTrue(f.budget.isDone)
        assertFalse(f.results.single().success)
    }

    @Test fun firstSaveSchedulingFailureIsKnownNotToHaveWrittenAndAllowsRetryAfterNativeClose() {
        val f = Fixture().running()
        f.apply(); f.rejectStorage = true; f.verify()
        assertEquals("old", f.stored)
        assertEquals(1, f.results.size)
        assertFalse(f.budget.isDone)
        f.runNative()
        assertTrue(f.budget.isDone)
        assertEquals(0, f.retireRequests)
    }

    @Test fun cleanupSchedulingOrCommitFailureKeepsTheFenceAfterNativeClose() {
        for (reject in listOf(true, false)) {
            val f = Fixture().running()
            f.apply(); f.verify(); f.runStorage(); f.fail("cancel")
            f.rejectStorage = reject; f.retireSucceeds = false
            f.runUi()
            if (!reject) { f.runStorage(); f.runUi() }
            f.runNative()
            assertFalse(f.budget.isDone)
            assertEquals("new", f.stored)
            assertEquals(1, f.results.size)
        }
    }

    @Test fun deadlineBeforeSaveDoesNotCommitAndLateModelSignalsCannotPublishSuccess() {
        val f = Fixture().running()
        f.apply(); f.verify(); f.current = false
        f.runStorage(); f.runUi(); f.runNative()
        assertEquals("old", f.stored)
        assertFalse(f.apply())
        assertFalse(f.verify())
        assertFalse(f.results.single().success)
        assertTrue(f.budget.isDone)
    }

    private class Fixture {
        val storage = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val native = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val results = mutableListOf<DeviceCompositeAttemptState.Completion>()
        val budget = CompletableFuture<Unit>()
        var ownerPresent = true
        var current = true
        var stored: String? = "old"
        var nativeRequests = 0
        var retireRequests = 0
        var releaseAcknowledgements = 0
        var nativeFailure = false
        var rejectStorage = false
        var retireSucceeds = true
        var retireAtSuccess = false
        var onResult: ((DeviceCompositeAttemptState.Completion) -> Unit)? = null
        val state = DeviceCompositeAttemptState<String> {
            results += it
            events += "result:${it.success}"
            onResult?.invoke(it)
        }
        fun running() = apply { assertTrue(state.beginRunning()) }
        fun apply() = state.applyCandidate("new", { current }, { events += "apply:$it" }, ::fail)
        fun receiveReleased(isCurrent: () -> Boolean = { current }) = state.receiveReleasedCandidate(
            "new", isCurrent, { events += "apply:$it" }, { finish(false) }, ::fail)
        fun verify() = state.persistVerified({ current }, { action ->
            if (rejectStorage) throw RejectedExecutionException("storage rejected")
            storage += action
        }, { ui += it }, { stored = it; events += "save:$it"; true }, {
            retireRequests++
            if (retireSucceeds && stored == it) stored = null
            retireSucceeds
        }, { success, reason ->
            if (retireAtSuccess && success) current = false
            if (!success || !current) fail(reason)
            finish(success && current)
        }, ::fail)
        fun fail(reason: String) {
            state.fail(reason) {
                nativeRequests++
                native += {
                    if (nativeFailure) fail("native_cleanup_unconfirmed") else {
                        state.nativeCleanupConfirmed()
                        finish(false)
                    }
                }
            }
        }
        fun finish(success: Boolean) {
            state.complete(success, if (success) "joint_runtime_measured_applied_saved" else "released", {
                ownerPresent = false; events += "owner-cleared"
            }, { releaseAcknowledgements++ }, { events += "budget-released"; budget.complete(Unit) })
        }
        fun workerDeath() { if (state.acknowledgeWorkerRelease()) finish(false) }
        fun runStorage() = storage.removeFirst().invoke()
        fun runUi() = ui.removeFirst().invoke()
        fun runNative() = native.removeFirst().invoke()
    }
}
