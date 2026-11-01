package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.CameraRuntimeHandoff
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class VisionOwnerRetirementTest {
    private val lease = VisionCohortLease("cohort-a", "main-unique-a", 1L)

    @Test fun lateAuxiliaryCreationAndRetiredNativeAreBothJoinedBeforeAnotherOwnerCanStart() {
        val registry = CameraRuntimeHandoff.Registry()
        val participant = registry.joinRetirement()
        val primary = CompletableFuture<Unit>()
        val auxiliary = CompletableFuture<Unit>()
        val native = CompletableFuture<VisionReleaseReceipt>()
        val pending = registry.pendingRelease()!!
        var capturedAuxiliary = false
        var snapshots = 0
        val exits = mutableListOf<VisionCohortLease>()
        VisionOwnerRetirement.begin(lease.ownerId, participant, primary,
            { capturedAuxiliary = true; listOf(auxiliary) },
            { snapshots++; VisionRetirementSnapshot(lease, native) }, exits::add)
        assertFalse(capturedAuxiliary)
        primary.complete(Unit) // The late creation already entered the same tuning barrier.
        assertTrue(capturedAuxiliary)
        assertEquals(0, snapshots)
        auxiliary.complete(Unit)
        assertEquals(1, snapshots)
        assertEquals(listOf(lease), exits)
        assertFalse(pending.isDone)
        assertNull(registry.begin())
        native.complete(VisionReleaseReceipt(lease.cohortId, 44))
        assertEquals(Unit, pending.join())
        assertNotNull(registry.begin())
        assertEquals(1, snapshots)
    }

    @Test fun anotherCamerasClaimDoesNotSkipThisMainsCleanupOrGetReleasedByIt() {
        val registry = CameraRuntimeHandoff.Registry()
        val camera = registry.begin()!!
        val primary = CompletableFuture<Unit>()
        val pending = registry.pendingRelease()!!
        VisionOwnerRetirement.begin(lease.ownerId, registry.joinRetirement(), primary,
            { emptyList() }, { null }, { fail("No native cohort belongs to this owner") })
        camera.complete()
        assertFalse(pending.isDone)
        primary.complete(Unit)
        assertEquals(Unit, pending.join())
    }

    @Test fun exitControlErrorDoesNotReplaceTheLateActualDeathReceipt() {
        val registry = CameraRuntimeHandoff.Registry()
        val native = CompletableFuture<VisionReleaseReceipt>()
        val pending = registry.joinRetirement().let { participant ->
            val value = registry.pendingRelease()!!
            VisionOwnerRetirement.begin(lease.ownerId, participant, CompletableFuture.completedFuture(Unit),
                { emptyList() }, { VisionRetirementSnapshot(lease, native) },
                { throw IllegalStateException("control delivery failed") })
            value
        }
        assertFalse(pending.isDone)
        native.complete(VisionReleaseReceipt(lease.cohortId, 44))
        assertEquals(Unit, pending.join())
    }

    @Test fun aForeignOwnerOrCohortCannotAcknowledgeOrTerminateTheRetiringOwner() {
        for (foreignOwner in listOf(false, true)) {
            val registry = CameraRuntimeHandoff.Registry()
            val participant = registry.joinRetirement()
            val pending = registry.pendingRelease()!!
            var exits = 0
            val captured = if (foreignOwner) lease.copy(ownerId = "camera-new-owner", ownerEpoch = 2L) else lease
            val receipt = CompletableFuture.completedFuture(VisionReleaseReceipt("wrong-cohort", 44))
            VisionOwnerRetirement.begin(lease.ownerId, participant, CompletableFuture.completedFuture(Unit),
                { emptyList() }, { VisionRetirementSnapshot(captured, receipt) }, { exits++ })
            assertTrue(pending.isCompletedExceptionally)
            assertEquals(if (foreignOwner) 0 else 1, exits)
            assertNull(registry.begin())
        }
    }

    @Test fun failedConsumerProofStillRequestsItsOwnNativeCleanupButCannotFreeTheBudget() {
        val registry = CameraRuntimeHandoff.Registry()
        val participant = registry.joinRetirement()
        val primary = CompletableFuture<Unit>().also { it.completeExceptionally(IllegalStateException("barrier rejected")) }
        var exits = 0
        VisionOwnerRetirement.begin(lease.ownerId, participant, primary, { emptyList() },
            { VisionRetirementSnapshot(lease, CompletableFuture.completedFuture(VisionReleaseReceipt(lease.cohortId, 44))) },
            { exits++ })
        assertEquals(1, exits)
        assertTrue(registry.pendingRelease()!!.isCompletedExceptionally)
        assertNull(registry.begin())
    }
}
