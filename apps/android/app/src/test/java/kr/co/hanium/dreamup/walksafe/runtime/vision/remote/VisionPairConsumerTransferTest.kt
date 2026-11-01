package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTransferredDetector
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ownership/effect ordering only; these tests never construct a native engine or Android host. */
class VisionPairConsumerTransferTest {
    @Test fun normalHandoffDrainsBeforeParkingAndAdoptsBeforeConfirmationWithoutReopeningModels() = Fixture().run {
        val ticket = reserve()
        assertFalse(source.permits())
        coordinator.prepare(ticket, effects)
        assertEquals(listOf("drain_source", "park", "transfer"), log)
        assertSame(ticket, coordinator.pending(ticket.id))
        assertSame(ticket, coordinator.take(ticket.id, target, tag))
        assertNull(coordinator.take(ticket.id, target, tag))
        val adopted = coordinator.adopt(ticket, target, tag, targetDrained, effects)
        assertEquals(listOf("drain_source", "park", "transfer", "attach_both", "confirm", "activate_primary", "activate_auxiliary", "reissue"), log)
        assertTrue(adopted.consumer.permits())
        assertFalse(source.permits())
        assertTrue(adopted.proof.isCurrent())
        assertSame(moved.lease, adopted.consumer.runtime.lease)
        assertEquals(source.runtime.handles().map { it.modelInstanceId }.toSet(),
            adopted.consumer.runtime.handles().map { it.modelInstanceId }.toSet())
        assertTrue(ticket.completion.toCompletableFuture().get().adopted)
        assertNull(coordinator.pending(ticket.id))
        assertTrue(exits.isEmpty())
        assertEquals(listOf(deadline, deadline, deadline, deadline), effectDeadlines)
        assertFalse(coordinator.cancel(ticket, "late_source_cancel"))
    }

    @Test fun mailboxIsBoundedAndRejectsWrongActorRefAndAuthorityWithoutConsumingTheTicket() = Fixture().run {
        val ticket = reserve()
        assertNull(coordinator.reserve(source, witness, target, tag, sourceDrained, deadline))
        assertNull(coordinator.pending(ticket.id))
        coordinator.prepare(ticket, effects)
        assertNull(coordinator.pending("another-ticket"))
        assertNull(coordinator.take(ticket.id, copyOwner(target), tag))
        assertNull(coordinator.take(ticket.id, target, "another-actor"))
        assertSame(ticket, coordinator.pending(ticket.id))
        assertSame(ticket, coordinator.take(ticket.id, target, tag))
    }

    @Test fun duplicatePrepareAndWrongTargetAdoptDoNotRetireTheLegitimatePendingPair() = Fixture().run {
        val ticket = reserve()
        coordinator.prepare(ticket, effects)
        assertThrows(IllegalStateException::class.java) { coordinator.prepare(ticket, effects) }
        coordinator.take(ticket.id, target, tag)
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.adopt(ticket, copyOwner(target), tag, targetDrained, effects)
        }
        assertTrue(exits.isEmpty())
        assertTrue(coordinator.adopt(ticket, target, tag, targetDrained, effects).consumer.permits())
    }

    @Test fun sourceHostBarrierMustFinishBeforeNativeParking() = Fixture(sourceInitiallyDrained = false).run {
        val ticket = reserve(); val prepared = async { coordinator.prepare(ticket, effects) }
        assertTrue(sourceDrainEntered.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("drain_source"), log)
        assertNull(coordinator.pending(ticket.id))
        sourceDrained.complete(Unit)
        prepared.get(1, TimeUnit.SECONDS)
        assertEquals(listOf("drain_source", "park", "transfer"), log)
    }

    @Test fun missingTargetExpiresAndRequiresPhysicalDeathAsWellAsTheHostDrain() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects)
        now = deadline; timer.fire()
        assertEquals(listOf(moved.lease), exits)
        assertFalse(ticket.completion.toCompletableFuture().get().adopted)
        assertFalse(source.consumerReleased.toCompletableFuture().isDone)
        assertNull(coordinator.pending(ticket.id))
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        assertTrue(source.consumerReleased.toCompletableFuture().isDone)
        assertNull(registry.pendingRelease())
    }

    @Test fun cancellationBeforePrepareWaitsForBothSourceHostDrainAndPhysicalReceiptInEitherOrder() {
        for (deathFirst in listOf(true, false)) Fixture(sourceInitiallyDrained = false).run {
            val ticket = reserve()
            assertTrue(coordinator.cancel(ticket, "source_cancelled"))
            assertFalse(coordinator.cancel(ticket, "duplicate_cancel"))
            assertEquals(listOf(lease), exits)
            if (deathFirst) physical.complete(VisionReleaseReceipt(lease.cohortId, 321)) else sourceDrained.complete(Unit)
            assertFalse(source.consumerReleased.toCompletableFuture().isDone)
            if (deathFirst) sourceDrained.complete(Unit) else physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
            assertTrue(source.consumerReleased.toCompletableFuture().isDone)
            assertTrue(log.isEmpty())
        }
    }

    @Test fun partialTargetAttachmentRetiresTheNewLeaseAndWaitsForTargetHostCreationDrain() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects); coordinator.take(ticket.id, target, tag)
        attachedIds = setOf(primary.modelInstanceId)
        assertThrows(IllegalArgumentException::class.java) { coordinator.adopt(ticket, target, tag, targetDrained, effects) }
        assertEquals(listOf(moved.lease), exits)
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        assertFalse(source.consumerReleased.toCompletableFuture().isDone)
        targetDrained.complete(Unit)
        assertTrue(source.consumerReleased.toCompletableFuture().isDone)
        assertFalse(log.contains("confirm"))
    }

    @Test fun failureAfterRegistryAdoptRetiresTheLatestClaimInsteadOfTheOldSourceClaim() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects); coordinator.take(ticket.id, target, tag)
        throwAt = "confirm"
        assertThrows(IllegalStateException::class.java) { coordinator.adopt(ticket, target, tag, targetDrained, effects) }
        assertEquals(listOf(moved.lease), exits)
        assertFalse(registry.permits(source.claim))
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321)); targetDrained.complete(Unit)
        assertTrue(source.consumerReleased.toCompletableFuture().isDone)
        assertNull(registry.pendingRelease())
    }

    @Test fun transferResponseFailureUsesTheLatestCanonicalClientLeaseForExit() = Fixture().run {
        val ticket = reserve(); throwAt = "transfer_response"
        assertThrows(IllegalStateException::class.java) { coordinator.prepare(ticket, effects) }
        assertEquals(listOf(moved.lease), exits)
        assertNotSame(lease, exits.single())
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        assertTrue(source.consumerReleased.toCompletableFuture().isDone)
    }

    @Test fun timeoutDuringBlockedAttachmentWakesTheWorkerButPreservesTheTargetHostFence() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects); coordinator.take(ticket.id, target, tag)
        attachResult = CompletableFuture()
        val adopting = async { coordinator.adopt(ticket, target, tag, targetDrained, effects) }
        assertTrue(attachEntered.await(1, TimeUnit.SECONDS))
        now = deadline; timer.fire()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { adopting.get(1, TimeUnit.SECONDS) }
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        assertFalse(source.consumerReleased.toCompletableFuture().isDone)
        attachResult!!.complete(moved.handles.map { it.modelInstanceId }.toSet())
        targetDrained.complete(Unit)
        assertTrue(source.consumerReleased.toCompletableFuture().isDone)
        assertFalse(log.contains("confirm"))
    }

    @Test fun retiredTargetAndExpiredOrRejectedWatchCannotAdmitAnotherHost() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects)
        currentOwners.remove(target)
        assertNull(coordinator.pending(ticket.id))
        assertNull(coordinator.take(ticket.id, target, tag))
        assertEquals(listOf(moved.lease), exits)
        physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        Unit
    }

    @Test fun earlyTimerKeepsTheOriginalDeadlineAndSchedulerFailureRetiresSafely() {
        Fixture().run {
            val ticket = reserve(); timer.fire()
            assertFalse(ticket.completion.toCompletableFuture().isDone)
            assertEquals(listOf(deadline, deadline), timer.deadlines)
            coordinator.prepare(ticket, effects)
            now = deadline; timer.fire()
            assertEquals(listOf(moved.lease), exits)
            physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        }
        Fixture().run {
            timer.reject = true
            val ticket = reserve()
            assertFalse(ticket.completion.toCompletableFuture().get().adopted)
            assertEquals(listOf(lease), exits)
            physical.complete(VisionReleaseReceipt(lease.cohortId, 321))
        }
    }

    @Test fun ticketStoresOnlyMetadataAndCannotRetainExecutableHostEffects() = Fixture().run {
        val ticket = reserve(); coordinator.prepare(ticket, effects)
        val forbidden = listOf("Activity", "Context", "Function", "Listener", "FrameDetector", "RuntimeService", "NativeEngine", "Effects", "RetirementPort")
        assertTrue(ticket.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .none { field -> forbidden.any { field.type.name.contains(it) } })
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(ticket.javaClass))
    }

    private class Timer : VisionPairTransferDeadlineScheduler {
        val deadlines = mutableListOf<Long>()
        var task: Runnable? = null
        var reject = false
        override fun schedule(deadlineMs: Long, task: Runnable): AutoCloseable {
            check(!reject) { "test_watch_rejected" }
            deadlines += deadlineMs; this.task = task
            return AutoCloseable {}
        }
        fun fire() = requireNotNull(task).run()
    }
    private class Fixture(sourceInitiallyDrained: Boolean = true) {
        var now = 1_000L
        val deadline = 10_000L
        val tag = "actor-session-model-binding"
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val attempt = DeviceCheckAttemptRef("attempt", binding, 0, 20_000, "test-authority")
        val owner = DeviceCheckOwnerRef("source-main", attempt, 1, "test-authority")
        val target = DeviceCheckOwnerRef("target-camera", attempt, 1, "test-authority")
        val currentOwners = Collections.newSetFromMap(java.util.IdentityHashMap<DeviceCheckOwnerRef, Boolean>()).apply { add(owner); add(target) }
        val lease = VisionCohortLease("cohort", owner.ownerId, 1)
        private val runtime = """{"requestedDelegate":"cpu","activeDelegate":"cpu","numThreads":1,"fallbackUsed":false,"gpuPrecisionLossAllowed":false,"modelOwnerPid":321,"modelOwnerThreadId":42,"loadMs":1.0}"""
        val primary = VisionModelHandle(lease.cohortId, "primary-id", 1, VisionRole.PRIMARY, "unified_walksafe", runtime)
        val auxiliary = VisionModelHandle(lease.cohortId, "auxiliary-id", 1, VisionRole.FASTSAM, FastSamModelContract.MODEL_ID, runtime)
        val sourceSnapshot = VisionPairSnapshot(lease, primary, auxiliary, 321)
        val registry = VisionConsumerRegistry()
        val sourceClaim = requireNotNull(registry.acquire(VisionConsumerRegistry.Owner(owner.ownerId, tag), lease, sourceSnapshot.handles()))
        val physical = CompletableFuture<VisionReleaseReceipt>()
        val sourceDrained = CompletableFuture<Unit>().apply { if (sourceInitiallyDrained) complete(Unit) }
        val targetDrained = CompletableFuture<Unit>()
        val source = VisionConsumerLease(owner, sourceSnapshot, sourceClaim, physical, requireNotNull(registry.pendingRelease()), registry)
        private val cpu = RuntimeCandidate(RuntimeBackend.CPU, 1)
        private val candidate = JointRuntimeCandidate(cpu, cpu)
        private val choice = ValidatedJointRuntimeChoice(binding.jointBindingHash, candidate,
            JointFixtureRuntimeEvidence(10_000, 10, 10, List(10) { 1.0 }, List(10) { 1.0 }, 100.0, 100.0,
                emptyList(), cpu, cpu, false, 0))
        private val options = VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions("cpu", 1))
        private val primarySpec = VisionModelSpec(VisionRole.PRIMARY, primary.modelKey, "models/test.tflite", "e".repeat(64), options,
            intArrayOf(1, 768, 768, 3), arrayOf(intArrayOf(1, 300, 6)))
        private fun ref(handle: VisionModelHandle, targetLease: VisionCohortLease) = DeviceCheckNativeModelRef(handle.modelInstanceId,
            DeviceCheckNativeLease(targetLease.cohortId, targetLease.ownerId, targetLease.ownerEpoch),
            DeviceCheckNativeSpec(handle.role.name, handle.modelKey, "e".repeat(64), "cpu", 1, VisionRuntimeCodec.optionsHash(options)),
            handle.modelInstanceId, VisionRuntimeCodec.runtimeHash(handle.runtimeJson), "test-authority")
        private val sourceProof = JointPairReadyProof.issue(choice, binding, sourceSnapshot, ref(primary, lease), ref(auxiliary, lease), emptyList()) { true }
            .also { check(it.bindConsumerGateOnce { registry.permits(sourceClaim) }) }
        val witness = requireNotNull(sourceProof.claimForTransfer())
        private val primaryToken = PrimaryTransferredDetector(lease, listOf(primarySpec), listOf(primary))
        private val auxiliaryToken = FastSamTransferredModel::class.java.getDeclaredConstructor(VisionCohortLease::class.java,
            VisionModelHandle::class.java, FastSamRuntimeOptions::class.java).apply { isAccessible = true }.newInstance(lease, auxiliary,
            FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false, 1_000_000, 800, false, false, true))
        private val tokens = VisionPairDetachTokens(primaryToken, auxiliaryToken)
        val moved = VisionTransferResult(VisionCohortLease(lease.cohortId, target.ownerId, 2), sourceSnapshot.handles().map { it.copy(ownerEpoch = 2) })
        private var latest = lease
        val exits = mutableListOf<VisionCohortLease>()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val effectDeadlines = mutableListOf<Long>()
        val sourceDrainEntered = CountDownLatch(1)
        val attachEntered = CountDownLatch(1)
        var attachedIds = moved.handles.map { it.modelInstanceId }.toSet()
        var attachResult: CompletableFuture<Set<String>>? = null
        var throwAt: String? = null
        val timer = Timer()
        val coordinator = VisionPairConsumerTransfer(registry, object : VisionPairTransferRetirementPort {
            override fun ownerCurrent(owner: DeviceCheckOwnerRef) = currentOwners.contains(owner)
            override fun latestLease(source: VisionCohortLease, targetOwner: DeviceCheckOwnerRef) = latest
            override fun requestExit(lease: VisionCohortLease, reason: String) { exits += lease }
        }, { now }, timer)
        private fun effect(name: String) {
            log += name
            check(throwAt != name) { "test_failure_$name" }
        }
        val effects = Effects()
        inner class Effects : VisionPairTransferSourceEffects, VisionPairTransferTargetEffects {
            override fun drainSource(deadlineMs: Long): CompletionStage<VisionPairDetachTokens> {
                effectDeadlines += deadlineMs; effect("drain_source"); sourceDrainEntered.countDown()
                return CompletableFuture.completedFuture(tokens)
            }
            override fun park(lease: VisionCohortLease, deadlineMs: Long) {
                assertSame(this@Fixture.lease, lease); check(sourceDrained.isDone)
                effectDeadlines += deadlineMs; effect("park")
            }
            override fun transfer(lease: VisionCohortLease, targetOwner: DeviceCheckOwnerRef, deadlineMs: Long): VisionTransferResult {
                effectDeadlines += deadlineMs; effect("transfer"); latest = moved.lease
                check(throwAt != "transfer_response") { "test_response_failure" }
                return moved
            }
            override fun attachBoth(tokens: VisionPairDetachTokens, runtime: VisionTransferResult, deadlineMs: Long): CompletionStage<Set<String>> {
                effectDeadlines += deadlineMs; assertSame(this@Fixture.tokens, tokens); assertSame(moved, runtime)
                effect("attach_both"); attachEntered.countDown()
                return attachResult ?: CompletableFuture.completedFuture(attachedIds)
            }
            override fun activateAndReissue(witness: JointPairTransferWitness, runtime: VisionTransferResult, deadlineMs: Long): JointPairReadyProof {
                assertSame(this@Fixture.witness, witness); assertSame(moved, runtime); assertEquals(deadline, deadlineMs)
                assertFalse(registry.permits(sourceClaim))
                // Registry adoption must already have replaced the source claim.
                assertNull(registry.reserveTransfer(sourceClaim, VisionConsumerRegistry.Owner("other", tag), lease, sourceSnapshot.handles()))
                effect("confirm"); effect("activate_primary"); effect("activate_auxiliary"); effect("reissue")
                val snapshot = VisionPairSnapshot(moved.lease, moved.handles.single { it.role == VisionRole.PRIMARY },
                    moved.handles.single { it.role == VisionRole.FASTSAM }, 321)
                return JointPairReadyProof.issue(choice, binding, snapshot, ref(snapshot.primaryHandle, moved.lease),
                    ref(snapshot.auxiliaryHandle, moved.lease), emptyList()) { currentOwners.contains(target) }
            }
        }
        fun reserve() = requireNotNull(coordinator.reserve(source, witness, target, tag, sourceDrained, deadline))
        fun copyOwner(owner: DeviceCheckOwnerRef) = DeviceCheckOwnerRef(owner.ownerId, owner.source, owner.sessionGeneration, owner.authorityId)
    }
    private companion object {
        fun VisionPairSnapshot.handles() = listOf(primaryHandle, auxiliaryHandle)
        fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
            Thread { try { result.complete(block()) } catch (error: Throwable) { result.completeExceptionally(error) } }.start()
        }
    }
}
