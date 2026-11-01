package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityOutcome
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityRead
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeReleaseProof
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class MetricReleaseJournalTest {
    private val proof = MetricJournalReleaseReceipt(200, DeviceCheckNativeReleaseProof.CHILD_DEATH)
    private val noNative = MetricJournalReleaseReceipt(0, DeviceCheckNativeReleaseProof.NO_NATIVE_START)

    @Test fun normalAndLateObserversShareOnePublicationAndCannotPromoteTheOldSemanticFailure() {
        val fixture = Fixture()
        val oldResult = CompletableFuture.completedFuture(Result.failure<Boolean>(IllegalStateException("deadline")))
        fixture.publishedIntent()
        fixture.journal.sealIntent()
        assertEquals(0, fixture.writes)
        val start = CountDownLatch(1)
        val observers = List(4) { Thread { start.await(); repeat(10) { fixture.journal.observePhysical(proof) } } }
        observers.forEach(Thread::start); start.countDown()
        observers.forEach { it.join(2_000L); assertFalse(it.isAlive) }
        assertEquals(1, fixture.writes)
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.write.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
        assertTrue(oldResult.get().isFailure)
        assertTrue(fixture.journal.firstOutcome.get() is DeviceCheckAuthorityOutcome.Committed)
        fixture.journal.recheck(); fixture.journal.observePhysical(proof)
        assertEquals(1, fixture.writes)
    }

    @Test fun nativeReleaseBeforeTheOriginalPrepareFutureSettlesDoesNotGuessWhetherTheLoadWasPublished() {
        val fixture = Fixture()
        val pending = CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>()
        fixture.journal.intentStarting("prepare")
        fixture.journal.observeIntent(pending)
        fixture.journal.sealIntent(); fixture.journal.observePhysical(noNative)
        assertEquals(0, fixture.writes); assertEquals(0, fixture.verifications)
        assertFalse(fixture.journal.confirmed.isDone)
        pending.complete(committed())
        assertEquals(1, fixture.writes)
        fixture.write.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
    }

    @Test fun noPrepareWasEverEnteredRequiresNoSyntheticJournalRow() {
        val fixture = Fixture()
        fixture.journal.sealIntent()
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.journal.observePhysical(noNative)
        assertTrue(fixture.journal.confirmed.isDone)
        assertEquals(0, fixture.writes); assertEquals(0, fixture.verifications)
    }

    @Test fun uncertainCleanupWaitsForActualWriterSettlementAndMergesTheRetainedProofOnlyOnce() {
        val fixture = Fixture()
        fixture.publishedIntent(); fixture.journal.sealIntent(); fixture.journal.observePhysical(proof)
        fixture.write.complete(DeviceCheckAuthorityOutcome.Indeterminate("release-io"))
        assertEquals(1, fixture.reconciles)
        repeat(30) { fixture.journal.recheck() }
        assertEquals(1, fixture.reconciles)
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.read.complete(DeviceCheckAuthorityRead.NeedsCheck("no_bundle"))
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.verified.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
        assertEquals(1, fixture.writes); assertEquals(1, fixture.verifications)
        assertTrue("The original timeout remains a failure", fixture.journal.firstOutcome.get() is DeviceCheckAuthorityOutcome.Indeterminate)
    }

    @Test fun anUncertainPrepareUsesExactSettledAbsenceVerificationInsteadOfTreatingMissingAsReleased() {
        val fixture = Fixture()
        fixture.journal.intentStarting("prepare")
        fixture.journal.observeIntent<Unit>(completed(DeviceCheckAuthorityOutcome.Indeterminate("prepare-io")))
        fixture.journal.sealIntent(); fixture.journal.observePhysical(noNative)
        assertEquals(0, fixture.writes); assertEquals(1, fixture.verifications)
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.verified.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
        assertEquals(noNative, fixture.lastProof)
        assertEquals("prepare", fixture.lastPrepare)
    }

    @Test fun missingOrWrongPidRemainsUnconfirmedAndTheAutomaticRecoveryIsBounded() {
        val fixture = Fixture()
        fixture.journal.intentStarting("prepare")
        fixture.journal.observeIntent<Unit>(completed(DeviceCheckAuthorityOutcome.Indeterminate("prepare-io")))
        fixture.journal.sealIntent(); fixture.journal.observePhysical(proof)
        fixture.verified.complete(DeviceCheckAuthorityOutcome.Rejected("cohort_receipt_mismatch"))
        assertEquals(1, fixture.reconciles)
        fixture.read.complete(DeviceCheckAuthorityRead.NeedsCheck("no_bundle"))
        assertEquals(2, fixture.verifications)
        assertFalse(fixture.journal.confirmed.isDone)
        assertEquals(1, fixture.reconciles)
        assertTrue(fixture.journal.firstOutcome.get() is DeviceCheckAuthorityOutcome.Rejected)
    }

    @Test fun blockedRecoveryWaitsForAnExplicitRecheckAndNeverRepeatsTheNativeQuery() {
        val fixture = Fixture()
        fixture.publishedIntent(); fixture.journal.sealIntent(); fixture.journal.observePhysical(proof)
        fixture.write.complete(DeviceCheckAuthorityOutcome.Indeterminate("release-io"))
        fixture.read.complete(DeviceCheckAuthorityRead.Blocked("io_running", "release-io"))
        assertFalse(fixture.journal.confirmed.isDone)
        assertEquals(1, fixture.reconciles)
        fixture.read = completed(DeviceCheckAuthorityRead.NeedsCheck("no_bundle"))
        fixture.journal.recheck()
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.verified.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
        assertEquals(2, fixture.reconciles)
        assertEquals(1, fixture.writes)
    }

    @Test fun failedCleanupSubmissionIsVerifiedAfterReconciliationInsteadOfAssumingDeferredProofWasRecorded() {
        val fixture = Fixture()
        fixture.publishedIntent(); fixture.journal.sealIntent(); fixture.journal.observePhysical(proof)
        fixture.write.complete(DeviceCheckAuthorityOutcome.Rejected("writer_queue_full"))
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.read.complete(DeviceCheckAuthorityRead.NeedsCheck("no_bundle"))
        assertEquals(1, fixture.verifications)
        assertFalse(fixture.journal.confirmed.isDone)
        fixture.verified.complete(committed())
        assertTrue(fixture.journal.confirmed.isDone)
    }

    private class Fixture {
        var writes = 0
        var reconciles = 0
        var verifications = 0
        var lastProof: MetricJournalReleaseReceipt? = null
        var lastPrepare: String? = null
        val write = CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>()
        var read = CompletableFuture<DeviceCheckAuthorityRead>()
        val verified = CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>()
        val journal = MetricReleaseJournal(
            write = { receipt -> writes++; lastProof = receipt; write },
            settleExactRelease = { command, receipt ->
                verifications++; lastPrepare = command; lastProof = receipt; verified
            },
            reconcile = { reconciles++; read },
            markUnconfirmed = { it },
            changed = {},
        )
        fun publishedIntent() {
            journal.intentStarting("prepare")
            journal.observeIntent(completed(committed()))
        }
    }
    companion object {
        private fun committed(): DeviceCheckAuthorityOutcome<Unit> = DeviceCheckAuthorityOutcome.Committed(Unit, 1L, "ack")
        private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)
    }
}
