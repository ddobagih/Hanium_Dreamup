package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class VisionConsumerRegistryTest {
    private val main = VisionConsumerRegistry.Owner("foreground", "same-actor-and-model-contract")
    private val camera = VisionConsumerRegistry.Owner("camera-instance-2", main.authority)
    private val lease = VisionCohortLease("cohort-a", main.id, 1L)
    private val models = listOf(
        VisionModelHandle(lease.cohortId, "primary-instance", 1L, VisionRole.PRIMARY, "unified_walksafe", "{}"),
        VisionModelHandle(lease.cohortId, "aux-instance", 1L, VisionRole.FASTSAM, "fastsam", "{}"),
    )
    private fun moved() = VisionTransferResult(VisionCohortLease(lease.cohortId, camera.id, 2L),
        models.map { it.copy(ownerEpoch = 2L) })

    @Test fun delayedListenerAndHalfAttachedPairCannotAdmitTheNewOwner() {
        val registry = VisionConsumerRegistry()
        val old = registry.acquire(main, lease, models)!!
        val transfer = registry.reserveTransfer(old, camera, lease, models)!!
        assertFalse(registry.permits(old))
        assertFalse(registry.park(transfer, moved()))
        assertNull(registry.acquire(camera, moved().lease, moved().handles))
        assertNull(registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet()))
        assertTrue(registry.consumersDrained(transfer))
        assertTrue(registry.park(transfer, moved()))
        assertNull(registry.adopt(transfer, camera, setOf(models.first().modelInstanceId)))
        val adopted = registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet())!!
        assertTrue(registry.permits(adopted.claim))
        assertFalse(registry.permits(old))
        assertNull(registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet()))
    }

    @Test fun oldOwnerCannotCloseOrRepublishAfterTransfer() {
        val registry = VisionConsumerRegistry()
        val old = registry.acquire(main, lease, models)!!
        val transfer = registry.reserveTransfer(old, camera, lease, models)!!
        registry.consumersDrained(transfer)
        registry.park(transfer, moved())
        val current = registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet())!!.claim
        assertFalse(registry.retire(old, CompletableFuture.completedFuture(Unit),
            CompletableFuture.completedFuture(VisionReleaseReceipt(lease.cohortId, 12))))
        assertFalse(registry.consumersDrained(transfer))
        assertFalse(registry.park(transfer, moved()))
        assertTrue(registry.permits(current))
    }

    @Test fun physicalDeathAndConsumerDrainAreBothRequiredAndNeverCreateAFreeGap() {
        for (deathFirst in listOf(false, true)) {
            val registry = VisionConsumerRegistry()
            val claim = registry.acquire(main, lease, models)!!
            val drain = CompletableFuture<Unit>()
            val death = CompletableFuture<VisionReleaseReceipt>()
            val released = registry.pendingRelease()!!.toCompletableFuture()
            assertTrue(registry.retire(claim, drain, death))
            if (deathFirst) death.complete(VisionReleaseReceipt(lease.cohortId, 12)) else drain.complete(Unit)
            assertFalse(released.isDone)
            assertFalse(registry.permits(claim))
            assertNull(registry.acquire(camera, moved().lease, moved().handles))
            if (deathFirst) drain.complete(Unit) else death.complete(VisionReleaseReceipt(lease.cohortId, 12))
            assertTrue(released.isDone)
            assertNull(registry.pendingRelease())
            assertNotNull(registry.acquire(camera, moved().lease, moved().handles))
        }
    }

    @Test fun anotherCohortsDeathCannotReleaseTheCurrentBudget() {
        val registry = VisionConsumerRegistry()
        val claim = registry.acquire(main, lease, models)!!
        registry.retire(claim, CompletableFuture.completedFuture(Unit),
            CompletableFuture.completedFuture(VisionReleaseReceipt("some-other-cohort", 12)))
        assertFalse(registry.pendingRelease()!!.toCompletableFuture().isDone)
        assertNull(registry.acquire(camera, moved().lease, moved().handles))
    }

    @Test fun actorChangesAndDifferentModelInstancesCannotBeTransferred() {
        val registry = VisionConsumerRegistry()
        val claim = registry.acquire(main, lease, models)!!
        assertNull(registry.reserveTransfer(claim, camera.copy(authority = "new-actor"), lease, models))
        val transfer = registry.reserveTransfer(claim, camera, lease, models)!!
        registry.consumersDrained(transfer)
        assertThrows(IllegalArgumentException::class.java) {
            registry.park(transfer, moved().copy(handles = moved().handles.map { it.copy(modelInstanceId = "new-instance") }))
        }
        assertNull(registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet()))
        assertTrue(registry.park(transfer, moved()))
    }

    @Test fun anUnchangedEpochCannotBeAdopted() {
        val registry = VisionConsumerRegistry()
        val claim = registry.acquire(main, lease, models)!!
        val transfer = registry.reserveTransfer(claim, camera, lease, models)!!
        registry.consumersDrained(transfer)
        assertThrows(IllegalArgumentException::class.java) {
            registry.park(transfer, moved().let { it.copy(lease = it.lease.copy(ownerEpoch = 1L),
                handles = it.handles.map { handle -> handle.copy(ownerEpoch = 1L) }) })
        }
        assertNull(registry.adopt(transfer, camera, models.map { it.modelInstanceId }.toSet()))
    }
}
