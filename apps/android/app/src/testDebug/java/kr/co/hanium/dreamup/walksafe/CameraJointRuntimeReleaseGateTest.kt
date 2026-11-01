package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.UnknownRuntimeServiceTestFactory
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseReceipt
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRetirementSnapshot
import org.junit.Assert.*
import org.junit.Test

class CameraJointRuntimeReleaseGateTest {
    @Test fun invalidationDuringRestoreRejectsTheDetachedPreparationsLaterAdoption() {
        val authority = CameraPrimaryRuntimeAuthority(Any())
        val request = authority.beginPreparation()
        var model: Any? = null
        var choice: Any? = Any()
        var preparedGeneration = -1
        assertTrue(authority.invalidateIf({ true }) { choice = null; model = null })
        assertFalse(authority.isCurrent(request))
        val adopted = authority.publish(request, { true }) {
            choice = Any(); model = Any(); preparedGeneration = 9
        }
        assertFalse("An invalidated restore cannot re-adopt a still-durable choice", adopted)
        assertNull(choice)
        assertNull(model)
        assertEquals(-1, preparedGeneration)
    }

    @Test fun invalidationCannotObserveTheGapBetweenChoiceAndModelPublication() {
        val lock = Any()
        val authority = CameraPrimaryRuntimeAuthority(lock)
        val request = authority.beginPreparation()
        val choicePublished = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)
        val invalidationEntered = CountDownLatch(1)
        val invalidationCompleted = CountDownLatch(1)
        val publication = CompletableFuture<Boolean>()
        val invalidation = CompletableFuture<Boolean>()
        var choice: Any? = null
        var model: Any? = null
        var preparedGeneration = -1
        var adoptionOwnsLock = false
        val publisher = Thread {
            runCatching { authority.publish(request, { true }) {
                adoptionOwnsLock = Thread.holdsLock(lock)
                choice = Any()
                choicePublished.countDown()
                check(finishPublication.await(3, TimeUnit.SECONDS))
                model = Any()
                preparedGeneration = 9
            } }.fold(publication::complete, publication::completeExceptionally)
        }
        val invalidator = Thread {
            invalidationEntered.countDown()
            runCatching { authority.invalidateIf({ true }) {
                choice = null; model = null; preparedGeneration = -1
            } }.fold(invalidation::complete, invalidation::completeExceptionally)
            invalidationCompleted.countDown()
        }
        publisher.start()
        try {
            assertTrue(choicePublished.await(3, TimeUnit.SECONDS))
            invalidator.start()
            assertTrue(invalidationEntered.await(3, TimeUnit.SECONDS))
            assertFalse("Invalidation cannot enter while only the choice has been published",
                invalidationCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishPublication.countDown()
            publisher.join(3_000)
            if (invalidator.state != Thread.State.NEW) invalidator.join(3_000)
        }
        assertTrue(publication.get(3, TimeUnit.SECONDS))
        assertTrue(invalidation.get(3, TimeUnit.SECONDS))
        assertTrue(adoptionOwnsLock)
        assertNull(choice)
        assertNull(model)
        assertEquals(-1, preparedGeneration)
    }

    @Test fun anOldFailedPreparationCannotClearANewerAdoptedChoiceModelOrGeneration() {
        val authority = CameraPrimaryRuntimeAuthority(Any())
        val old = authority.beginPreparation()
        val fresh = authority.beginPreparation()
        val chosen = Any()
        val loaded = Any()
        var choice: Any? = null
        var model: Any? = null
        var preparedGeneration = -1
        assertTrue(authority.publish(fresh, { true }) {
            choice = chosen; model = loaded; preparedGeneration = 10
        })
        assertFalse(authority.clearFailed(old) { choice = null; model = null; preparedGeneration = -1 })
        assertSame(chosen, choice)
        assertSame(loaded, model)
        assertEquals(10, preparedGeneration)
        assertTrue(authority.isCurrent(fresh))
    }

    @Test fun auxiliaryInvalidationQueuedDuringPreparationCannotJoinItsOwnLaterWorkerTask() {
        val queued = ArrayDeque<() -> Unit>()
        val physical = java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Unit>>()
        physical.add(CompletableFuture.completedFuture(Unit)) // previous.close already returned actual ACK.
        val retiringNative = CompletableFuture<Unit>()
        var closes = 0
        // The current preparation owns the worker. An auxiliary callback enqueues behind it.
        val enqueued = enqueueCameraPrimaryRetirement(
            { action -> queued.add(action) }, physical,
            { closes++; retiringNative }, { fail("Cleanup was accepted") })
        val preparing = CompletableFuture.allOf(*physical.toTypedArray())
        assertEquals(0, closes)
        assertFalse(enqueued.isDone)
        assertTrue("Preparing must not wait for a cleanup task queued behind itself", preparing.isDone)
        queued.removeFirst().invoke()
        assertEquals(1, closes)
        assertTrue(physical.contains(enqueued))
        assertFalse(enqueued.isDone)
        retiringNative.complete(Unit)
        assertEquals(Unit, enqueued.join())
    }

    @Test fun destroyBarrierStillCollectsRetirementQueuedAfterThePreparationSnapshot() {
        val queued = ArrayDeque<() -> Unit>()
        val physical = java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Unit>>()
        val actual = CompletableFuture<Unit>()
        val enqueued = enqueueCameraPrimaryRetirement(
            { action -> queued.add(action) }, physical, { actual }, { fail("Cleanup was accepted") })
        val destroyDrain = drainCameraPrimaryForRetirement(
            { action -> queued.add(action) }, { CompletableFuture.completedFuture(Unit) }, { physical.toList() })
        queued.removeFirst().invoke() // The detached model starts closing before the destroy barrier.
        queued.removeFirst().invoke()
        assertFalse(destroyDrain.isDone)
        actual.complete(Unit)
        assertEquals(Unit, enqueued.join())
        assertEquals(Unit, destroyDrain.join())
    }

    @Test fun rejectedDetachedRetirementRecordsATerminalFailureInsteadOfAPendingWorkerDependency() {
        val physical = java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Unit>>()
        var closes = 0
        var failures = 0
        val retired = enqueueCameraPrimaryRetirement(
            { throw java.util.concurrent.RejectedExecutionException("camera worker closed") }, physical,
            { closes++; CompletableFuture.completedFuture(Unit) }, { failures++ })
        assertEquals(0, closes)
        assertEquals(1, failures)
        assertTrue(retired.isCompletedExceptionally)
        assertSame(retired, physical.single())
        assertTrue(CompletableFuture.allOf(*physical.toTypedArray()).isCompletedExceptionally)
    }

    @Test fun cameraRetirementReusesItsClaimAndWaitsForCameraModelAndActualCohortProofs() {
        val registry = CameraRuntimeHandoff.Registry()
        val claim = registry.begin()!!
        val pending = registry.pendingRelease()!!
        val primary = CompletableFuture<Unit>()
        val auxiliary = CompletableFuture<Void>()
        val camera = CompletableFuture<Unit>()
        val actual = CompletableFuture<VisionReleaseReceipt>()
        val lease = VisionCohortLease("camera-cohort", "camera-owner", 4L)
        var snapshots = 0
        val exits = mutableListOf<VisionCohortLease>()
        val retired = retireCameraRuntime(lease.ownerId, claim,
            { fail("An owned camera must reuse its participant"); registry.joinRetirement() },
            primary, listOf(camera), { listOf(auxiliary) },
            { snapshots++; VisionRetirementSnapshot(lease, actual) }, exits::add)
        assertSame(claim, retired)
        primary.complete(Unit)
        auxiliary.complete(null)
        assertEquals(0, snapshots)
        assertFalse(pending.isDone)
        camera.complete(Unit)
        assertEquals(1, snapshots)
        assertEquals(listOf(lease), exits)
        assertNull(registry.begin())
        actual.complete(VisionReleaseReceipt(lease.cohortId, 99))
        assertEquals(Unit, pending.join())
        assertNotNull(registry.begin())
        assertEquals(1, snapshots)
    }

    @Test fun cameraWithoutAnOwnedClaimJoinsCleanupWithoutReleasingTheExistingOwner() {
        val registry = CameraRuntimeHandoff.Registry()
        val older = registry.begin()!!
        val pending = registry.pendingRelease()!!
        var joins = 0
        retireCameraRuntime("camera-owner", null, { joins++; registry.joinRetirement() },
            CompletableFuture.completedFuture(Unit), emptyList(), { emptyList() }, { null },
            { fail("This camera never acquired a native cohort") })
        assertEquals(1, joins)
        assertFalse(pending.isDone)
        older.complete()
        assertEquals(Unit, pending.join())
    }

    @Test fun semanticModelCloseFailureCanRecoverWithLateActualReleaseWithoutUnlockingEarly() {
        val registry = CameraRuntimeHandoff.Registry()
        val participant = registry.begin()!!
        val pending = registry.pendingRelease()!!
        val actualModel = CompletableFuture<Unit>()
        val cohort = CompletableFuture<VisionReleaseReceipt>()
        val lease = VisionCohortLease("camera-cohort", "camera-owner", 4L)
        var problems = 0
        var exits = 0
        val model = closeCameraPrimaryRuntime(
            { throw IllegalStateException("remote CLOSE failed before actual death") },
            { actualModel }, { problems++ })
        retireCameraRuntime(lease.ownerId, participant, registry::joinRetirement,
            model, emptyList(), { emptyList() }, { VisionRetirementSnapshot(lease, cohort) }, { exits++ })
        assertEquals(1, problems)
        assertEquals(0, exits)
        assertFalse(model.isDone)
        assertFalse(pending.isDone)
        actualModel.complete(Unit)
        assertEquals(1, exits)
        assertFalse(pending.isDone)
        cohort.complete(VisionReleaseReceipt(lease.cohortId, 99))
        assertEquals(Unit, pending.join())
    }

    @Test fun failedCameraProofStillCleansItsCapturedCohortAndRetainsTheAllocationFence() {
        val registry = CameraRuntimeHandoff.Registry()
        val participant = registry.begin()!!
        val camera = CompletableFuture<Unit>()
        val lease = VisionCohortLease("camera-cohort", "camera-owner", 4L)
        val actual = CompletableFuture<VisionReleaseReceipt>()
        var exits = 0
        retireCameraRuntime(lease.ownerId, participant, registry::joinRetirement,
            CompletableFuture.completedFuture(Unit), listOf(camera), { emptyList() },
            { VisionRetirementSnapshot(lease, actual) }, { exits++ })
        camera.completeExceptionally(IllegalStateException("AR Session.close unconfirmed"))
        assertEquals(1, exits)
        assertTrue(registry.pendingRelease()!!.isCompletedExceptionally)
        actual.complete(VisionReleaseReceipt(lease.cohortId, 99))
        assertNull(registry.begin())
    }

    @Test fun destroyWorkerBarrierCollectsDetachedModelsAfterPriorFrameWorkHasDrained() {
        val queued = ArrayDeque<() -> Unit>()
        val detached = mutableListOf<CompletableFuture<Unit>>()
        val lateActual = CompletableFuture<Unit>()
        var closes = 0
        queued.add { detached += lateActual } // Earlier queued frame retired its model.
        val drained = drainCameraPrimaryForRetirement(queued::add,
            { closes++; CompletableFuture.completedFuture(Unit) }, { detached.toList() })
        assertEquals(0, closes)
        queued.removeFirst().invoke()
        queued.removeFirst().invoke()
        assertEquals(1, closes)
        assertFalse(drained.isDone)
        lateActual.complete(Unit)
        assertEquals(Unit, drained.join())
    }

    @Test fun rejectedPrimaryDrainCannotReleaseTheClaimButStillRetiresItsOwnCohort() {
        val registry = CameraRuntimeHandoff.Registry()
        val participant = registry.begin()!!
        val lease = VisionCohortLease("camera-cohort", "camera-owner", 4L)
        var closes = 0
        var exits = 0
        val drained = drainCameraPrimaryForRetirement(
            { throw java.util.concurrent.RejectedExecutionException("worker closed") },
            { closes++; CompletableFuture.completedFuture(Unit) }, { emptyList() })
        retireCameraRuntime(lease.ownerId, participant, registry::joinRetirement,
            drained, emptyList(), { emptyList() },
            { VisionRetirementSnapshot(lease, CompletableFuture.completedFuture(VisionReleaseReceipt(lease.cohortId, 99))) },
            { exits++ })
        assertEquals(0, closes)
        assertEquals(1, exits)
        assertTrue(registry.pendingRelease()!!.isCompletedExceptionally)
        assertNull(registry.begin())
    }

    @Test fun primaryPreparationWaitsForActualAuxiliaryNativeRelease() {
        val f = Fixture()
        val service = f.closingService()
        assertTrue(f.defer(service))
        assertEquals(0, f.prepared)
        assertTrue(f.dispatched.isEmpty())
        f.finishRelease(service)
        assertEquals(1, f.prepared)
        assertEquals(0, f.failures)
        assertFalse(f.defer(service))
    }

    @Test fun leavingTheForegroundOrCameraEpochRetiresTheReleaseCallback() {
        val f = Fixture()
        val service = f.closingService()
        assertTrue(f.defer(service))
        f.current = false
        f.finishRelease(service)
        assertEquals(0, f.prepared)
        assertEquals(0, f.failures)
    }

    @Test fun failedNativeReleaseNeverRestartsPrimaryPreparation() {
        val f = Fixture()
        f.native.closeFailure = IllegalStateException("native close failed")
        val service = f.closingService()
        assertTrue(f.defer(service))
        f.finishRelease(service)
        assertEquals(0, f.prepared)
        assertEquals(1, f.failures)
        assertFalse(service.stats().nativeReleaseConfirmed)
    }

    private class Fixture {
        val native = UnknownRuntimeServiceTestFactory()
        val dispatched = LinkedBlockingQueue<() -> Unit>()
        var current = true
        var prepared = 0
        var failures = 0
        fun closingService(): FastSamRuntimeService<Unit> {
            val service = native.create(1L, FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,
                1, false, 61_000_000_000L, 800L, false), FastSamRuntimeService.Listener<Unit> {},
                { 1_000_000_000L })
            service.start().get(3, TimeUnit.SECONDS)
            native.closeEntered = CountDownLatch(1)
            native.releaseClose = CountDownLatch(1)
            service.closeAsync()
            assertTrue(native.closeEntered.await(3, TimeUnit.SECONDS))
            return service
        }
        fun defer(service: FastSamRuntimeService<Unit>) = deferCameraRuntimeUntilAuxiliaryReleased(
            service, { dispatched.put(it) }, { current }, { prepared++ }, { failures++ })
        fun finishRelease(service: FastSamRuntimeService<Unit>) {
            native.releaseClose.countDown()
            runCatching { service.releasedFuture().get(3, TimeUnit.SECONDS) }
            val callback = requireNotNull(dispatched.poll(3, TimeUnit.SECONDS))
            callback()
        }
    }
}
