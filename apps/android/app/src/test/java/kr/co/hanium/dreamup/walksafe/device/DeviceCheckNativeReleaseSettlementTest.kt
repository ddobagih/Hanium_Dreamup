package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controlled persistence/worker tests. No native, Binder or physical device success is asserted. */
class DeviceCheckNativeReleaseSettlementTest {
    @Test fun aTimedOutPrepareMustActuallySettleBeforeFreshAbsenceCanAcknowledgeNoNativeStart() {
        Fixture().use { f ->
            val gate = f.io.blockWrite(DeviceCheckPublication.NOT_PUBLISHED)
            val prepare = f.open()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            f.authority.publicationUnconfirmed(f.command)
            assertTrue(prepare.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Indeterminate)
            val cleanup = f.settle()
            assertFalse("The public timeout did not finish the original writer", cleanup.isDone)
            gate.release.countDown()
            val uncertain = cleanup.get(2, TimeUnit.SECONDS) as DeviceCheckAuthorityOutcome.Indeterminate
            assertTrue(f.authority.reconcileAfterSettled(uncertain.operationId).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            val reads = f.io.reads.get()
            assertEquals(DeviceCheckNativeReleaseSettlement.ABSENT_AFTER_SETTLED_NO_NATIVE, value(f.settle().get(2, TimeUnit.SECONDS)))
            assertTrue("Reconciliation's read is not the exact cleanup receipt", f.io.reads.get() > reads)
            assertTrue(requireNotNull(DeviceCheckAuthorityCodec.decode(requireNotNull(f.io.text))).cohorts.isEmpty())
        }
    }

    @Test fun aLateCommittedPrepareIsDurablyReleasedRatherThanReportedAsAbsent() {
        Fixture().use { f ->
            f.uncertainPrepare(DeviceCheckPublication.COMMITTED)
            val receipt = value(f.settle(DeviceCheckNativeReleaseProof.CHILD_DEATH).get(2, TimeUnit.SECONDS))
            assertEquals(DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE, receipt)
            val row = requireNotNull(DeviceCheckAuthorityCodec.decode(requireNotNull(f.io.text))).cohorts.single()
            assertEquals(DeviceCheckNativeReleaseProof.CHILD_DEATH.name, row.release)
            assertTrue(row.loads.all { it.closed })
        }
    }

    @Test fun missingLoadRequiresNoNativeStartAndNeverAcceptsChildDeathOrAMismatchedCapture() {
        Fixture().use { f ->
            f.uncertainPrepare(DeviceCheckPublication.NOT_PUBLISHED)
            assertTrue(f.settle(DeviceCheckNativeReleaseProof.CHILD_DEATH).get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(f.settle(prepareCommand = id()).get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertEquals(DeviceCheckNativeReleaseSettlement.ABSENT_AFTER_SETTLED_NO_NATIVE, value(f.settle().get(2, TimeUnit.SECONDS)))
        }
    }

    @Test fun everyOpaqueCaptureAndProcessIdentityMustMatchWithoutBorrowingTheLatestOwner() {
        Fixture().use { f ->
            val permit = value(f.open().get(2, TimeUnit.SECONDS))
            val copiedSource = DeviceCheckAttemptRef(f.attempt.attemptId, f.binding, 1000, 301000, f.attempt.authorityId)
            val copiedOwner = DeviceCheckOwnerRef(f.owner.ownerId, f.attempt, 1, f.owner.authorityId)
            val changed = listOf(
                f.settle(source = copiedSource), f.settle(owner = copiedOwner), f.settle(identity = Any()),
                f.settle(lease = f.lease.copy(ownerEpoch = 2)), f.settle(spec = f.spec.copy(optionsHash = "e".repeat(64))),
                f.settle(process = f.process.copy(pid = 201)), f.settle(process = f.process.copy(startTicks = 11)),
                f.settle(process = f.process.copy(bootIdentity = "boot:2")))
            changed.forEach { assertTrue(it.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected) }
            assertTrue("Rejected foreign cleanup must not fence the real owner", f.authority.consumeNativeOpen(permit, f.identity, 1100))
            assertEquals(DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE,
                value(f.settle(DeviceCheckNativeReleaseProof.CHILD_DEATH).get(2, TimeUnit.SECONDS)))
        }
    }

    @Test fun retiredSourceCanCleanItsCapturedLoadWithoutChangingTheNewActorsAttempt() {
        Fixture().use { f ->
            value(f.open().get(2, TimeUnit.SECONDS))
            f.authority.retireOwner(f.owner)
            val other = f.binding.copy(actorBindingHash = "e".repeat(64))
            val newer = value(f.authority.beginAttempt(other, id(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE,
                value(f.settle(DeviceCheckNativeReleaseProof.CHILD_DEATH).get(2, TimeUnit.SECONDS)))
            val disk = requireNotNull(DeviceCheckAuthorityCodec.decode(requireNotNull(f.io.text)))
            assertEquals(newer.attemptId, disk.latest?.id)
            assertEquals(other, disk.latest?.binding)
            assertEquals(other, f.core.currentBinding())
            assertNull(f.authority.ownerRef(f.owner.ownerId))
        }
    }

    @Test fun metricPhysicalReleaseCannotPermitVisionWhileTheExactJournalReadIsPending() {
        Fixture().use { f ->
            val oldPermit = value(f.open().get(2, TimeUnit.SECONDS))
            val gate = f.io.blockRead()
            val cleanup = f.settle()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val writes = f.io.writes.get()
            assertFalse(f.authority.consumeNativeOpen(oldPermit, f.identity, 1100))
            assertTrue(f.openVision().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertEquals(writes, f.io.writes.get())
            assertFalse(cleanup.isDone)
            gate.release.countDown()
            assertEquals(DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE, value(cleanup.get(2, TimeUnit.SECONDS)))
            assertFalse("The revoked permit cannot revive after cleanup", f.authority.consumeNativeOpen(oldPermit, f.identity, 1100))
            assertTrue(f.openVision().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
        }
    }

    @Test fun failedFreshReadRetainsTheGlobalFenceAndCanBeConfirmedAfterActualReconciliation() {
        Fixture().use { f ->
            value(f.open().get(2, TimeUnit.SECONDS))
            f.io.failRead = true
            val unknown = f.settle().get(2, TimeUnit.SECONDS) as DeviceCheckAuthorityOutcome.Indeterminate
            assertTrue(f.openVision().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(f.authority.reconcileAfterSettled(unknown.operationId).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue("Generic recovery must not drop the exact cleanup fence", f.openVision().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertEquals(DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE, value(f.settle().get(2, TimeUnit.SECONDS)))
        }
    }

    @Test fun aReconciledReleaseIsRecognizedOnlyAfterAnExactFreshReceiptRead() {
        Fixture().use { f ->
            value(f.open().get(2, TimeUnit.SECONDS))
            val gate = f.io.blockWrite(DeviceCheckPublication.COMMITTED)
            val cleanup = f.authority.recordCohortReleased(f.lease.cohortId, 0, DeviceCheckNativeReleaseProof.NO_NATIVE_START)
                .toCompletableFuture()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            // The original prepare command is only an uncertainty marker here, not a new LOAD.
            f.authority.publicationUnconfirmed(f.command)
            gate.release.countDown()
            val unknown = cleanup.get(2, TimeUnit.SECONDS) as DeviceCheckAuthorityOutcome.Indeterminate
            assertTrue(f.authority.reconcileAfterSettled(unknown.operationId).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(DeviceCheckNativeReleaseSettlement.ALREADY_RELEASED, value(f.settle().get(2, TimeUnit.SECONDS)))
        }
    }

    @Test fun sixtyFourUnsettledCapturesAreRetainedAndCapacityReturnsOnlyAfterTheirCohortSettles() {
        val lane = Executors.newSingleThreadExecutor()
        val hold = CountDownLatch(1); val entered = CountDownLatch(1)
        lane.execute { entered.countDown(); hold.await() }
        Fixture(lane).use { f ->
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val originals = (0 until 64).map { f.open(prepareCommand = if (it == 0) f.command else id()) }
                val rejected = f.open(prepareCommand = id()).get(2, TimeUnit.SECONDS)
                assertEquals(DeviceCheckAuthorityOutcome.Rejected("native_prepare_capacity"), rejected)
                f.authority.publicationUnconfirmed(f.command)
                val unknown = f.settle().get(2, TimeUnit.SECONDS) as DeviceCheckAuthorityOutcome.Indeterminate
                val reconciliation = f.authority.reconcileAfterSettled(unknown.operationId).toCompletableFuture()
                assertFalse(reconciliation.isDone)
                hold.countDown()
                reconciliation.get(2, TimeUnit.SECONDS)
                originals.forEach { it.get(2, TimeUnit.SECONDS) }
                assertEquals(DeviceCheckNativeReleaseSettlement.ABSENT_AFTER_SETTLED_NO_NATIVE,
                    value(f.settle().get(2, TimeUnit.SECONDS)))
                assertTrue(f.open(prepareCommand = id()).get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            } finally { hold.countDown() }
        }
    }

    @Test fun aCaptureReservedBeforeCleanupCannotReenterNativeAdmissionAfterTheFenceClears() {
        Fixture().use { f ->
            val captured = requireNotNull(f.core.captureNativePrepare(f.owner, f.lease, f.identity, f.command, f.spec, f.process, 20000))
            assertTrue(f.core.reserveNativeCleanup(captured))
            assertEquals(DeviceCheckNativeReleaseSettlement.ABSENT_AFTER_SETTLED_NO_NATIVE,
                value(f.core.settleNativeOpenRelease(captured, DeviceCheckNativeReleaseProof.NO_NATIVE_START, id())))
            assertTrue(f.core.prepareNativeOpen(f.owner, f.lease, f.identity, f.command, f.spec, f.process, 20000, captured)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(f.open().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
        }
    }

    @Test fun aKnownPreNativeFailureDoesNotAccumulateUnresolvedCaptureCapacity() {
        Fixture().use { f ->
            repeat(65) {
                val rejected = f.authority.prepareNativeOpen(f.owner, f.lease, f.identity, id(),
                    f.spec.copy(optionsHash = "e".repeat(64)), f.process, 20000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                assertEquals(DeviceCheckAuthorityOutcome.Rejected("metric_support_contract_changed"), rejected)
            }
            assertTrue(f.open().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
        }
    }

    @Test fun theOriginalOpenTupleCannotReleaseATransferredOwnerEpoch() {
        Fixture().use { f ->
            val permit = value(f.open().get(2, TimeUnit.SECONDS))
            assertTrue(f.authority.consumeNativeOpen(permit, f.identity, 1100))
            val model = value(f.authority.recordModelOpened(permit, "test-model", "a".repeat(64))
                .toCompletableFuture().get(2, TimeUnit.SECONDS))
            val target = requireNotNull(f.authority.registerOwner("test-camera", f.attempt, 1))
            val targetLease = f.lease.copy(ownerId = target.ownerId, ownerEpoch = 2)
            value(f.authority.recordOwnerTransfer(f.lease, targetLease, target, Any(), id(),
                mapOf(model.modelId to model.actualRuntimeHash)).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(DeviceCheckAuthorityOutcome.Rejected("native_cleanup_binding_mismatch"),
                f.settle(DeviceCheckNativeReleaseProof.CHILD_DEATH).get(2, TimeUnit.SECONDS))
            val before = requireNotNull(DeviceCheckAuthorityCodec.decode(requireNotNull(f.io.text))).cohorts.single()
            assertEquals(targetLease, before.lease)
            assertNull(before.release)
            value(f.authority.recordCohortReleased(targetLease.cohortId, f.process.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH)
                .toCompletableFuture().get(2, TimeUnit.SECONDS))
            val after = requireNotNull(DeviceCheckAuthorityCodec.decode(requireNotNull(f.io.text))).cohorts.single()
            assertEquals(targetLease, after.lease)
            assertEquals(DeviceCheckNativeReleaseProof.CHILD_DEATH.name, after.release)
        }
    }

    private class Gate(val publication: DeviceCheckPublication = DeviceCheckPublication.COMMITTED) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun await() { entered.countDown(); release.await() }
    }
    private class Io : DeviceCheckAuthorityPersistence {
        @Volatile var text: String? = null
        @Volatile var failRead = false
        private val lock = Any()
        private var writeGate: Gate? = null; private var readGate: Gate? = null
        private val gates = mutableListOf<Gate>()
        val reads = AtomicInteger(); val writes = AtomicInteger()
        fun blockWrite(publication: DeviceCheckPublication) = synchronized(lock) {
            Gate(publication).also { writeGate = it; gates += it }
        }
        fun blockRead() = synchronized(lock) { Gate().also { readGate = it; gates += it } }
        override fun read(): String? {
            reads.incrementAndGet()
            synchronized(lock) { readGate.also { readGate = null } }?.await()
            if (failRead) { failRead = false; throw IllegalStateException("test_read_failure") }
            return text
        }
        override fun publish(canonicalState: String): DeviceCheckPublication {
            writes.incrementAndGet()
            val gate = synchronized(lock) { writeGate.also { writeGate = null } }
            gate?.await()
            val result = gate?.publication ?: DeviceCheckPublication.COMMITTED
            if (result != DeviceCheckPublication.NOT_PUBLISHED) text = canonicalState
            return result
        }
        fun drain() { synchronized(lock) { gates.toList() }.forEach { it.release.countDown() } }
    }
    private class Fixture(worker: ExecutorService = Executors.newSingleThreadExecutor()) : AutoCloseable {
        val io = Io()
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN },
            { 1100 }, { 8 }, true).also { it.admit(binding); it.read(binding) }
        val attempt = value(core.beginAttempt(binding, id(), 1000, 301000))
        val owner = requireNotNull(core.registerOwner("test-main", attempt, 1))
        val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1)
        val identity = Any(); val command = id()
        val spec = DeviceCheckMetricSupportContract.spec
        val process = DeviceCheckNativeProcess(200, 1000, "app:metric_support", 10, "boot:1")
        val authority = AndroidDeviceCheckAuthority(core, true, 5000, worker)
        fun open(prepareCommand: String = command) = authority.prepareNativeOpen(owner, lease, identity,
            prepareCommand, spec, process, 20000).toCompletableFuture()
        fun openVision() = authority.prepareNativeOpen(owner, DeviceCheckNativeLease(id(), owner.ownerId, 1), Any(), id(),
            DeviceCheckNativeSpec("PRIMARY", "primary", "e".repeat(64), "cpu", 1, "f".repeat(64)),
            process.copy(pid = 201, processName = "app:vision_runtime"), 20000).toCompletableFuture()
        fun settle(proof: DeviceCheckNativeReleaseProof = DeviceCheckNativeReleaseProof.NO_NATIVE_START,
            source: DeviceCheckAuthoritySource = attempt, owner: DeviceCheckOwnerRef = this.owner,
            lease: DeviceCheckNativeLease = this.lease, identity: Any = this.identity, prepareCommand: String = command,
            spec: DeviceCheckNativeSpec = this.spec, process: DeviceCheckNativeProcess = this.process) =
            authority.settleNativeOpenRelease(source, owner, lease, identity, prepareCommand, spec, process, proof).toCompletableFuture()
        fun uncertainPrepare(publication: DeviceCheckPublication) {
            val gate = io.blockWrite(publication); val prepare = open()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            authority.publicationUnconfirmed(command)
            assertTrue(prepare.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Indeterminate)
            gate.release.countDown()
            assertTrue(authority.reconcileAfterSettled(command).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
        }
        override fun close() { io.drain(); authority.shutdownForTests() }
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun <T> value(outcome: DeviceCheckAuthorityOutcome<T>): T {
            assertTrue("Expected explicit receipt, got $outcome", outcome is DeviceCheckAuthorityOutcome.Committed)
            return (outcome as DeviceCheckAuthorityOutcome.Committed).value
        }
    }
}
