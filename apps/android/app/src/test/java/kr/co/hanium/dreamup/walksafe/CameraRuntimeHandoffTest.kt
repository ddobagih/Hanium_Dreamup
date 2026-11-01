package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class CameraRuntimeHandoffTest {
    @Test fun anotherActivitiesCleanupCannotBeSkippedOrReleaseAnExistingCameraClaim() {
        for (cameraFirst in listOf(false, true)) {
            val registry = CameraRuntimeHandoff.Registry()
            val camera = requireNotNull(registry.begin())
            val retiringMain = registry.joinRetirement()
            val cameraRelease = CompletableFuture<Unit>()
            val mainRelease = CompletableFuture<Unit>()
            val pending = requireNotNull(registry.pendingRelease())
            camera.completeAfter(listOf(cameraRelease))
            retiringMain.completeAfter(listOf(mainRelease))
            if (cameraFirst) cameraRelease.complete(Unit) else mainRelease.complete(Unit)
            assertFalse(pending.isDone)
            assertNull(registry.begin())
            if (cameraFirst) mainRelease.complete(Unit) else cameraRelease.complete(Unit)
            assertEquals(Unit, pending.join())
            assertNotNull(registry.begin())
        }
    }

    @Test fun aLateRetirementParticipantCannotAcknowledgeTheNextCameraBudget() {
        val registry = CameraRuntimeHandoff.Registry()
        val camera = requireNotNull(registry.begin())
        val retiringMain = registry.joinRetirement()
        retiringMain.complete()
        assertNull(registry.begin())
        camera.complete()
        val next = requireNotNull(registry.begin())
        val pending = registry.pendingRelease()
        retiringMain.complete()
        retiringMain.fail(IllegalStateException("late cleanup"))
        assertSame(pending, registry.pendingRelease())
        assertFalse(requireNotNull(pending).isDone)
        next.complete()
    }

    @Test fun cleanupCanClaimAnEmptySlotButKeepsTheExistingPhysicalFailureFence() {
        val registry = CameraRuntimeHandoff.Registry()
        val retiring = registry.joinRetirement()
        assertNull(registry.begin())
        retiring.fail(IllegalStateException("unproven physical close"))
        val laterCleanup = registry.joinRetirement()
        laterCleanup.complete()
        assertTrue(requireNotNull(registry.pendingRelease()).isCompletedExceptionally)
        assertNull(registry.begin())
    }

    @Test fun anotherOwnerWaitsForCameraPrimaryAndAuxiliaryReleaseAcknowledgements() {
        val registry = CameraRuntimeHandoff.Registry()
        val claim = requireNotNull(registry.begin())
        val pending = requireNotNull(registry.pendingRelease())
        val camera = CompletableFuture<Unit>()
        val primary = CompletableFuture<Unit>()
        val auxiliary = CompletableFuture<Unit>()
        claim.completeAfter(listOf(camera, primary, auxiliary))
        assertNull(registry.begin())
        camera.complete(Unit)
        auxiliary.complete(Unit)
        assertSame(pending, registry.pendingRelease())
        assertFalse(pending.isDone)
        primary.complete(Unit)
        assertEquals(Unit, pending.join())
        assertNull(registry.pendingRelease())
        assertNotNull(registry.begin())
    }

    @Test fun aFailedNativeReleaseRemainsExceptionalAndBlocksReassignment() {
        val registry = CameraRuntimeHandoff.Registry()
        val claim = requireNotNull(registry.begin())
        val camera = CompletableFuture<Unit>()
        val primary = CompletableFuture<Unit>()
        val auxiliary = CompletableFuture<Unit>()
        claim.completeAfter(listOf(camera, primary, auxiliary))
        val pending = requireNotNull(registry.pendingRelease())
        auxiliary.completeExceptionally(IllegalStateException("native close unconfirmed"))
        assertTrue(pending.isCompletedExceptionally)
        assertNull(registry.begin())
        camera.complete(Unit)
        primary.complete(Unit)
        claim.complete()
        assertSame(pending, registry.pendingRelease())
        assertTrue(pending.isCompletedExceptionally)
    }

    @Test fun aRetiredClaimCannotCompleteOrFailItsSuccessor() {
        val registry = CameraRuntimeHandoff.Registry()
        val old = requireNotNull(registry.begin())
        old.complete()
        val current = requireNotNull(registry.begin())
        val pending = requireNotNull(registry.pendingRelease())
        old.complete()
        old.fail(IllegalStateException("late callback"))
        assertSame(pending, registry.pendingRelease())
        assertFalse(pending.isDone)
        current.complete()
        assertNull(registry.pendingRelease())
    }

    @Test fun noNativeAllocationsStillRequireTheOwnerToRelinquishItsClaim() {
        val registry = CameraRuntimeHandoff.Registry()
        val claim = requireNotNull(registry.begin())
        assertFalse(requireNotNull(registry.pendingRelease()).isDone)
        claim.completeAfter(emptyList())
        assertNull(registry.pendingRelease())
    }

    @Test fun aSuccessCallbackCanReenterAndClaimTheNowReleasedSlot() {
        val registry = CameraRuntimeHandoff.Registry()
        val claim = requireNotNull(registry.begin())
        var successor: CameraRuntimeHandoff.Claim? = null
        requireNotNull(registry.pendingRelease()).thenRun { successor = registry.begin() }
        claim.complete()
        assertNotNull(successor)
        assertFalse(requireNotNull(registry.pendingRelease()).isDone)
        successor?.complete()
    }
}
