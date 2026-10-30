package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class CameraRuntimeHandoffTest {
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
