package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real authority/cache/worker with explicit metadata-only proof fixtures; no native claim. */
class AuthorityPostLoginDeviceCheckSavedResultsTest {
    @Test fun explicitRetryReconcilesTheKnownBeginAgainAfterItsFirstFreshReadFailed() {
        SettlementFixture().use { f ->
            f.failedBeginSettlement()
            val beforeReads = f.io.reads.get(); val beforeChanges = f.changes.get()
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(beforeReads + 1, f.io.reads.get())
            assertEquals(2, f.readCalls.get()) // The current captured session receives its own authority read.
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(beforeChanges, f.changes.get())
            assertEquals(1, f.io.writes.get())
            assertNull(f.saved.historical())
        }
    }

    @Test fun duplicateExplicitSettlementRetriesShareOneReadAndSuppressAutomaticRestoration() {
        SettlementFixture().use { f ->
            f.failedBeginSettlement()
            val beforeChanges = f.changes.get()
            val gate = f.io.holdNextRead()
            val first = f.saved.prepareExplicitRetry(f.resultBinding())
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            assertSame(first, f.saved.prepareExplicitRetry(f.resultBinding()))
            assertFalse(f.saved.mayBeginAttempt())
            gate.release.countDown()
            assertTrue(first.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(3, f.io.reads.get())
            assertEquals(2, f.reconcileCalls.get())
            assertEquals(beforeChanges, f.changes.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun repeatedSettlementReadFailuresKeepTheExactFenceUntilTheNextUserRequest() {
        SettlementFixture().use { f ->
            val operation = f.failedBeginSettlement()
            f.io.failReads.set(1)
            assertEquals(DeviceCheckAuthorityRead.Blocked("reconcile_read_failed", operation),
                f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(operation, f.core.unsettledOperation())
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(3, f.io.reads.get())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(4, f.io.reads.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun anExplicitRetryJoinsTheActualWriterAndCannotIssueANativeOwnerBeforeItSettles() {
        SettlementFixture(timeoutMs = 100).use { f ->
            val gate = f.io.holdNextPublication()
            f.io.failReads.set(1)
            val begin = f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1000, 301000).toCompletableFuture()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            assertTrue(begin.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Indeterminate)
            val retry = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertSame(retry, f.saved.prepareExplicitRetry(f.resultBinding()))
            assertFalse("A public deadline is not physical writer settlement", retry.isDone)
            assertFalse(f.saved.mayBeginAttempt())
            assertNull(f.saved.registerRuntimeOwner(f.checkBinding(), "pending-owner"))
            assertEquals(1, f.io.reads.get())
            gate.release.countDown()
            assertTrue(retry.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(1, f.changes.get())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun staleSettlementCanCleanItsOwnStorageButOnlyTheNewUsersFreshReadAdmitsAnotherAttempt() {
        for (changeActor in listOf(false, true)) SettlementFixture().use { f ->
            f.failedBeginSettlement()
            val gate = f.io.holdNextRead()
            val old = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            if (changeActor) f.actor = "other"
            f.session = 2
            f.saved.bindSession(f.actor, f.session)
            gate.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), old.get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertNull(f.core.unsettledOperation())
            assertEquals(1, f.io.writes.get())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertTrue(f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1200, 301200).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            assertEquals(2, f.io.writes.get())
        }
    }

    @Test fun aBindingChangedDuringSettlementCannotBorrowTheOldReadConfirmation() {
        SettlementFixture().use { f ->
            f.failedBeginSettlement()
            val gate = f.io.holdNextRead()
            val old = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            f.selectedBinding = f.selectedBinding.copy(resultBindingHash = "f".repeat(64))
            val current = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            gate.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("binding_superseded"), old.get(2, TimeUnit.SECONDS))
            assertTrue(current.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(2, f.reconcileCalls.get())
        }
    }

    @Test fun schemaFailureKeepsStorageUncertainAndNativeRecoveryKeepsAdmissionBlocked() {
        val native = DeviceCheckAuthorityCoreTest.Fixture().also { it.pair() }
        for ((record, reason) in listOf("invalid" to "reconcile_invalid_record",
            requireNotNull(native.io.text) to "native_recovery_required")) SettlementFixture().use { f ->
            val operation = f.failedBeginSettlement()
            f.io.text = record
            val read = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            if (reason == "native_recovery_required") {
                assertEquals(DeviceCheckAuthorityOutcome.Rejected("native_process_identity_unconfirmed"),
                    f.authority.beginAttempt(f.selectedBinding, UUID.randomUUID().toString(), 1200, 301200, f.session)
                        .toCompletableFuture().get(2, TimeUnit.SECONDS))
                assertEquals(1, f.io.writes.get())
                assertNull(f.saved.sourceFor(f.checkBinding()))
                assertNull(f.saved.registerRuntimeOwner(f.checkBinding(), "blocked-foreign-native"))
            }
            assertEquals(DeviceCheckAuthorityRead.Blocked(reason,
                operation.takeIf { reason == "reconcile_invalid_record" }), read)
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(3, f.io.reads.get())
            if (reason == "reconcile_invalid_record") assertEquals(operation, f.core.unsettledOperation())
            else {
                assertNull(f.core.unsettledOperation())
                assertEquals(DeviceCheckAuthorityRead.Blocked(reason),
                    f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun anAlreadySettledKnownOperationStillRequiresTheCurrentSessionsFreshRead() {
        SettlementFixture().use { f ->
            val operation = f.failedBeginSettlement()
            assertTrue(f.authority.reconcileAfterSettled(operation).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertFalse(f.saved.mayBeginAttempt())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(2, f.readCalls.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun anIndeterminateSaveCanBeReadBackAfterItsFirstSettlementReadFailedWithoutRepublishingIt() {
        SettlementFixture().use { f ->
            f.preparePair()
            val beforeWrites = f.io.writes.get()
            f.io.nextPublication = DeviceCheckPublication.INDETERMINATE
            f.io.failReads.set(1)
            assertTrue(f.saved.saveAsync(PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                f.actor, f.session, 1), f.resultBinding(), false, PostLoginMetricDepthState.SUPPORTED, 1100)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Indeterminate)
            f.drain()
            assertFalse(f.saved.mayBeginAttempt())
            f.now = 301000 // Readback is cleanup; it does not grant another expired save decision.
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Ready)
            assertEquals(beforeWrites + 1, f.io.writes.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun traceConnectsTheExactJournalAndSettlementRetriesWithoutRecordingTheirStorageId() {
        val events = CopyOnWriteArrayList<DeviceCheckTraceEvent>()
        SettlementFixture(trace = DeviceCheckTraceSink { events += it }).use { f ->
            val operation = f.failedBeginSettlement()
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            val journal = events.single { it.stage == DeviceCheckTraceStage.JOURNAL_BEGIN &&
                it.outcome == DeviceCheckTraceOutcome.WAITING }
            val settlement = events.filter { it.stage == DeviceCheckTraceStage.AUTHORITY_RECONCILE }
            assertTrue(settlement.all { it.flowId == journal.flowId })
            assertEquals(2, settlement.count { it.outcome == DeviceCheckTraceOutcome.STARTED })
            assertTrue(settlement.any { it.outcome == DeviceCheckTraceOutcome.FAILED &&
                it.reason == DeviceCheckTraceReason.RECONCILE_FAILED })
            assertTrue(settlement.any { it.outcome == DeviceCheckTraceOutcome.SUCCEEDED })
            assertTrue(events.any { it.flowId == journal.flowId && it.stage == DeviceCheckTraceStage.AUTHORITY_READ &&
                it.outcome == DeviceCheckTraceOutcome.SUCCEEDED })
            val formatted = events.joinToString("\n", transform = ::formatDeviceCheckTrace)
            assertFalse(formatted.contains(operation))
            assertFalse(formatted.contains("actor"))
            assertFalse(formatted.contains("test_read_failure"))
        }
    }

    @Test fun aThrowingTraceSinkCannotPreventReadOrSettlementRecovery() {
        SettlementFixture(trace = DeviceCheckTraceSink { error("test_trace_sink_failure") }).use { f ->
            f.failedBeginSettlement()
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun anIssuedReadDeadlineCanRetryItsFailedSettlementAfterTheActualReaderFinishes() {
        SettlementFixture(timeoutMs = 100, initialRead = false).use { f ->
            val gate = f.io.holdNextRead()
            f.io.failOnRead = 2
            val initial = f.saved.refresh(f.resultBinding()).toCompletableFuture()
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            val blocked = initial.get(2, TimeUnit.SECONDS) as DeviceCheckAuthorityRead.Blocked
            assertNotNull(blocked.operationId)
            assertFalse(f.saved.mayBeginAttempt())
            gate.release.countDown()
            assertTrue(f.io.failedRead.await(2, TimeUnit.SECONDS))
            f.drain()
            val beforeChanges = f.changes.get()
            assertEquals(2, f.io.reads.get())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(3, f.io.reads.get())
            assertEquals(2, f.readCalls.get())
            assertEquals(beforeChanges, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun anIssuedBeginCanRetryTheDifferentCanonicalWriterIdReturnedByTheFacade() {
        SettlementFixture().use { f ->
            val externalCommand = UUID.randomUUID().toString()
            f.io.nextPublication = DeviceCheckPublication.INDETERMINATE
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(externalCommand),
                f.authority.beginAttempt(f.selectedBinding, externalCommand, 1000, 301000, f.session)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS))
            f.io.failReads.set(1)
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(externalCommand),
                f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1100, 301100)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS))
            f.drain()
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(3, f.io.reads.get())
            assertEquals(1, f.io.writes.get())
            assertTrue(f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1200, 301200)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            assertEquals(2, f.io.writes.get())
        }
    }

    @Test fun traceDistinguishesJournalPublicationAndFormatFailuresFromNativeRecovery() {
        val cases = mapOf("reconcile_cleanup_unconfirmed" to DeviceCheckTraceReason.STORAGE_UNCERTAIN,
            "reconcile_encoding_failed" to DeviceCheckTraceReason.SCHEMA_BLOCKED,
            "revision_exhausted" to DeviceCheckTraceReason.SCHEMA_BLOCKED,
            "native_recovery_required" to DeviceCheckTraceReason.NATIVE_RELEASE_PENDING)
        for ((reason, expected) in cases) {
            val events = CopyOnWriteArrayList<DeviceCheckTraceEvent>()
            SettlementFixture(trace = DeviceCheckTraceSink { events += it }).use { f ->
                f.forcedRead = DeviceCheckAuthorityRead.Blocked(reason)
                assertEquals(f.forcedRead, f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
                val result = events.last { it.stage == DeviceCheckTraceStage.AUTHORITY_READ }
                assertEquals(expected, result.reason)
                assertEquals(DeviceCheckTraceOutcome.BLOCKED, result.outcome)
                assertFalse(f.saved.mayBeginAttempt())
                assertEquals(0, f.io.writes.get())
            }
        }
    }

    @Test fun anUnloadedAuthorityNeverAdmitsAnAttemptFromItsEmptyCache() {
        ReadRecoveryFixture().use { f ->
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(0, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun anInitialReadMustFinishBeforeAnAttemptCanBegin() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val pending = f.saved.refresh(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(pending.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun concurrentRecoveryReadsShareOneRequestAfterTheInitialReadFailed() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.failRead = false; f.io.block = true
            val first = f.saved.refresh(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            val duplicate = f.saved.refresh(f.resultBinding())
            assertSame(first, duplicate)
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(first.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
            assertNull(f.saved.historical())
        }
    }

    @Test fun anOldActorsReadCompletionCannotAdmitTheNewActorsAttempt() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val old = f.saved.refresh(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.actor = "other"; f.session = 2
            f.saved.bindSession(f.actor, f.session)
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), old.get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertTrue(f.saved.refresh(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun explicitRetryCanPerformTheFirstReadWithoutAutomaticallyRestoringAnything() {
        ReadRecoveryFixture().use { f ->
            assertFalse(f.saved.mayBeginAttempt())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(1, f.io.reads.get())
            assertEquals(0, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun explicitRecoveryReadsOnceAndOnlyThenAllowsAUserStartedAttempt() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(1, f.changes.get())
            f.io.failRead = false; f.io.block = true
            val retry = f.saved.prepareExplicitRetry(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertSame(retry, f.saved.prepareExplicitRetry(f.resultBinding()))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(retry.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(1, f.changes.get())
            assertEquals(0, f.io.writes.get())
            assertNull(f.saved.historical())
            assertTrue(f.saved.beginAttemptAsync(PostLoginDeviceCheckBinding(f.actor, f.session, 1),
                f.resultBinding(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Committed)
            assertEquals(1, f.io.writes.get())
            assertNull(f.saved.historical())
        }
    }

    @Test fun explicitRetryJoiningTheInitialReadSuppressesItsAutomaticNotification() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val initial = f.saved.refresh(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertSame(initial, f.saved.prepareExplicitRetry(f.resultBinding()))
            f.io.release.countDown()
            assertTrue(initial.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(0, f.changes.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun repeatedReadFailureStaysBlockedUntilAnotherExplicitRequest() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(1, f.changes.get())
            f.io.failRead = false
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(3, f.io.reads.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun explicitRetryCannotReopenAnInvalidRecordOrAnUnknownNativeProcess() {
        val native = DeviceCheckAuthorityCoreTest.Fixture().also { it.pair() }
        for ((record, reason) in listOf("invalid" to "invalid_authority_record",
            requireNotNull(native.io.text) to "native_recovery_required")) {
            ReadRecoveryFixture().use { f ->
                f.io.text = record
                val blocked = DeviceCheckAuthorityRead.Blocked(reason)
                assertEquals(blocked, f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
                f.io.text = null
                assertEquals(blocked, f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS))
                assertFalse(f.saved.mayBeginAttempt())
                assertEquals(1, f.io.reads.get())
                assertEquals(0, f.io.writes.get())
            }
        }
    }

    @Test fun explicitRetryCannotClearAnUncertainWriter() {
        ReadRecoveryFixture().use { f ->
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            val operation = UUID.randomUUID().toString()
            f.core.publicationUnconfirmed(operation)
            assertEquals(DeviceCheckAuthorityRead.Blocked("publication_unconfirmed", operation),
                f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(operation, f.core.unsettledOperation())
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(1, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun aLateExplicitRetryCannotAdmitTheSameActorInANewerSession() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            f.io.failRead = false; f.io.block = true
            val retry = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.session = 2
            f.saved.bindSession(f.actor, f.session)
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), retry.get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(1, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun aSupersededBindingCannotCompleteTheCurrentExplicitRetry() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val old = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.selectedBinding = f.binding.copy(resultBindingHash = "f".repeat(64))
            val current = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertNotSame(old, current)
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("binding_superseded"), old.get(2, TimeUnit.SECONDS))
            assertTrue(current.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(0, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun historicalCommitNeedsTheCurrentIssuedPairAndSessionBeforeRestore() {
        Fixture().use { f ->
            val ready = f.runtime
            f.runtime = null
            assertNull(f.saved.restore(f.resultBinding()))
            f.runtime = ready
            f.issued = false
            assertNull(f.saved.restore(f.resultBinding()))
            f.issued = true
            f.runtime = ready!!.copy(binding = ready.binding.copy(sessionGeneration = 2))
            assertNull(f.saved.restore(f.resultBinding()))
            f.runtime = ready
            assertEquals(f.result, f.saved.restore(f.resultBinding()))
        }
    }

    @Test fun lateOtherActorRefreshCannotReselectTheAuthorityOrRetireTheNewAttempt() {
        Fixture().use { f ->
            val old = f.resultBinding()
            f.actor = "other"; f.session = 2; f.generation = 2
            f.saved.bindSession(f.actor, f.session)
            val started = f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1000, 301000)
                .toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(started is DeviceCheckAuthorityOutcome.Committed)
            val source = f.saved.sourceFor(f.checkBinding()) as DeviceCheckAttemptRef
            assertTrue(f.authority.checkAttempt(source, 1100))
            val stale = f.saved.refresh(old).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), stale)
            assertTrue(f.authority.checkAttempt(source, 1100))
            assertTrue(f.authority.cachedSnapshot(f.otherBinding) is DeviceCheckAuthorityRead.NeedsCheck)
        }
    }

    @Test fun sessionChangingDuringBindingCaptureCannotAdmitTheOldBegin() {
        Fixture().use { f ->
            val oldCheck = f.checkBinding(); val oldResult = f.resultBinding()
            f.capture = {
                f.capture = {}
                f.actor = "other"; f.session = 2; f.generation = 2
                f.saved.bindSession(f.actor, f.session)
                f.authority.read(f.otherBinding, 2).toCompletableFuture().get(2, TimeUnit.SECONDS)
            }
            assertTrue(f.saved.beginAttemptAsync(oldCheck, oldResult, 1000, 301000)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(f.authority.cachedSnapshot(f.otherBinding) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.authority.cachedSnapshot(f.base.binding) is DeviceCheckAuthorityRead.Blocked)
        }
    }

    @Test fun updatesRequireTheDisplayedRevisionAndCannotApplyAnOldResultTwice() {
        Fixture(limited = true).use { f ->
            val before = f.snapshot()
            assertEquals(f.result, f.saved.restore(f.resultBinding()))
            f.saved.didPublish(before)
            val next = before.copy(state = PostLoginDeviceCheckState.FULL, disabledFeatures = emptySet())
            assertTrue(f.saved.updateAsync(before, next, f.resultBinding(), false, PostLoginMetricDepthState.SUPPORTED)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            assertFalse(f.saved.acceptsPublishedSnapshot(before))
            assertTrue(f.saved.acceptsPublishedSnapshot(next))
            assertTrue(f.saved.updateAsync(before, next, f.resultBinding(), false, PostLoginMetricDepthState.SUPPORTED)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            f.saved.didPublish(next)
            assertTrue(f.saved.runtimeReady(f.checkBinding()))
        }
    }

    @Test fun aHistoricalBundleIsAvailableForFreshPreparationWithoutPretendingItIsReady() {
        Fixture().use { f ->
            f.runtime = null
            assertNotNull(f.saved.sourceFor(f.checkBinding()))
            assertFalse(f.saved.runtimeReady(f.checkBinding()))
            assertNull(f.saved.restore(f.resultBinding()))
            assertEquals(f.result, f.saved.retainedResult(f.resultBinding()))
        }
    }

    private class Fixture(limited: Boolean = false) : AutoCloseable {
        val base = DeviceCheckAuthorityCoreTest.Fixture()
        private val pair = base.pair()
        val result = if (limited) base.full.copy(state = PostLoginDeviceCheckState.LIMITED,
            disabledFeatures = setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED) else base.full
        val authority = AndroidDeviceCheckAuthority(base.core, true, 5000)
        var actor = "actor"
        var session = 1L
        var generation = 1L
        var issued = true
        var capture: () -> Unit = {}
        val otherBinding = base.binding.copy(actorBindingHash = "e".repeat(64))
        var runtime: DeviceCheckReadyRuntime? = DeviceCheckReadyRuntime(checkBinding(), base.choice, pair.proof)
        val saved = AuthorityPostLoginDeviceCheckSavedResults(authority,
            bindingFor = { capture(); if (it.actorId == "actor") base.binding else otherBinding },
            readyRuntime = { runtime }, isCurrent = { it == checkBinding() },
            sessionIsCurrent = { a, s -> a == actor && s == session },
            isIssuedProof = { issued && it === pair.proof }, nowMs = { 1100 })
        init {
            val frozen = requireNotNull(base.core.freezeSuccess(pair.attempt, 1100, result, base.choice, pair.proof))
            assertTrue(base.core.commitSuccess(UUID.randomUUID().toString(), frozen) is DeviceCheckAuthorityOutcome.Committed)
            saved.bindSession(actor, session)
            saved.refresh(resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
        }
        fun checkBinding() = PostLoginDeviceCheckBinding(actor, session, generation)
        fun snapshot() = PostLoginDeviceCheckSnapshot(result.state, actor, session, generation,
            disabledFeatures = result.disabledFeatures)
        fun resultBinding() = PostLoginDeviceCheckResultBinding(actor, "installation", "maker", "model", "device", 33,
            "fingerprint", "app", 1, "1", "source", "policy", null, null, "registry", "sha", null, null,
            emptySet(), true, true, true, true)
        override fun close() = authority.shutdownForTests()
    }

    private class SettlementFixture(timeoutMs: Long = 5000, trace: DeviceCheckTraceSink = DeviceCheckTraceSink.NONE,
        initialRead: Boolean = true) : AutoCloseable {
        private val models = DeviceCheckAuthorityCoreTest.Fixture()
        val io = SettlementIo()
        val baseBinding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), models.choice.bindingHash, "d".repeat(64))
        var selectedBinding = baseBinding
        var actor = "actor"
        var session = 1L
        var now = 1100L
        private var issuedProof: DeviceCheckPairProof? = null
        private var runtime: DeviceCheckReadyRuntime? = null
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN },
            { now }, { 8 }, true, verifyProofOrigin = { it === issuedProof }, requireSessionObservation = true)
        private val worker = Executors.newSingleThreadExecutor()
        val authority = AndroidDeviceCheckAuthority(core, true, timeoutMs, worker)
        val readCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val changes = AtomicInteger()
        var forcedRead: DeviceCheckAuthorityRead? = null
        private val counted = object : DeviceCheckAuthority by authority {
            override fun read(binding: DeviceCheckAuthorityBinding, expectedSessionGeneration: Long?): CompletionStage<DeviceCheckAuthorityRead> {
                readCalls.incrementAndGet()
                forcedRead?.let { return CompletableFuture.completedFuture(it) }
                return authority.read(binding, expectedSessionGeneration)
            }
            override fun reconcileAfterSettled(operationId: String): CompletionStage<DeviceCheckAuthorityRead> {
                reconcileCalls.incrementAndGet()
                return authority.reconcileAfterSettled(operationId)
            }
        }
        val saved = AuthorityPostLoginDeviceCheckSavedResults(counted,
            bindingFor = { selectedBinding.copy(actorBindingHash = if (it.actorId == "actor") "a".repeat(64) else "e".repeat(64)) },
            readyRuntime = { runtime }, isCurrent = { it.actorId == actor && it.sessionGeneration == session },
            sessionIsCurrent = { a, s -> a == actor && s == session }, isIssuedProof = { it === issuedProof },
            nowMs = { now }, onChanged = { changes.incrementAndGet() }, trace = trace)
        init {
            saved.bindSession(actor, session)
            if (initialRead) saved.refresh(resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
        }
        fun checkBinding() = PostLoginDeviceCheckBinding(actor, session, 1)
        fun resultBinding() = PostLoginDeviceCheckResultBinding(actor, "installation", "maker", "model", "device", 33,
            "fingerprint", "app", 1, "1", "source", "policy", null, null, "registry", "sha", null, null,
            emptySet(), true, true, true, true)
        fun failedBeginSettlement(): String {
            io.nextPublication = DeviceCheckPublication.INDETERMINATE
            io.failReads.set(1)
            val outcome = saved.beginAttemptAsync(checkBinding(), resultBinding(), 1000, 301000)
                .toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(outcome is DeviceCheckAuthorityOutcome.Indeterminate)
            drain()
            assertFalse(saved.mayBeginAttempt())
            assertEquals(2, io.reads.get())
            return (outcome as DeviceCheckAuthorityOutcome.Indeterminate).operationId
        }
        fun preparePair() {
            committed(saved.beginAttemptAsync(checkBinding(), resultBinding(), 1000, 301000))
            val attempt = saved.sourceFor(checkBinding()) as DeviceCheckAttemptRef
            val owner = requireNotNull(authority.registerOwner("settlement-owner", attempt, session))
            val lease = DeviceCheckNativeLease(UUID.randomUUID().toString(), owner.ownerId, 1)
            val identity = Any()
            fun open(spec: DeviceCheckNativeSpec): DeviceCheckNativeModelRef {
                val permit = committed(authority.prepareNativeOpen(owner, lease, identity, UUID.randomUUID().toString(),
                    spec, models.child, 20000))
                assertTrue(authority.consumeNativeOpen(permit, identity, 1100))
                return committed(authority.recordModelOpened(permit, UUID.randomUUID().toString(), "a".repeat(64)))
            }
            val primary = open(models.primarySpec); val auxiliary = open(models.auxSpec)
            val proof = DeviceCheckAuthorityCoreTest.Proof(models.choice.measurementId, primary, auxiliary)
            issuedProof = proof
            runtime = DeviceCheckReadyRuntime(checkBinding(), models.choice, proof)
        }
        private fun <T> committed(stage: CompletionStage<DeviceCheckAuthorityOutcome<T>>): T {
            val outcome = stage.toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(outcome is DeviceCheckAuthorityOutcome.Committed)
            return (outcome as DeviceCheckAuthorityOutcome.Committed).value
        }
        fun drain() { worker.submit { }.get(2, TimeUnit.SECONDS) }
        override fun close() { io.releaseAll(); authority.shutdownForTests() }
    }

    private class SettlementIo : DeviceCheckAuthorityPersistence {
        class Gate { val entered = CountDownLatch(1); val release = CountDownLatch(1) }
        @Volatile var text: String? = null
        @Volatile var nextPublication = DeviceCheckPublication.COMMITTED
        @Volatile var failOnRead: Int? = null
        @Volatile private var readGate: Gate? = null
        @Volatile private var publicationGate: Gate? = null
        private val gates = mutableListOf<Gate>()
        val failReads = AtomicInteger()
        val failedRead = CountDownLatch(1)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        fun holdNextRead() = Gate().also { gates += it; readGate = it }
        fun holdNextPublication() = Gate().also { gates += it; publicationGate = it }
        fun releaseAll() { gates.forEach { it.release.countDown() } }
        override fun read(): String? {
            val ordinal = reads.incrementAndGet()
            readGate?.also { readGate = null }?.let { it.entered.countDown(); check(it.release.await(4, TimeUnit.SECONDS)) }
            if (ordinal == failOnRead || failReads.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                failedRead.countDown()
                error("test_read_failure")
            }
            return text
        }
        override fun publish(canonicalState: String): DeviceCheckPublication {
            writes.incrementAndGet()
            publicationGate?.also { publicationGate = null }?.let { it.entered.countDown(); check(it.release.await(4, TimeUnit.SECONDS)) }
            val publication = nextPublication.also { nextPublication = DeviceCheckPublication.COMMITTED }
            if (publication != DeviceCheckPublication.NOT_PUBLISHED) text = canonicalState
            return publication
        }
    }

    /** Starts before any read, owner, attempt or cache population. */
    private class ReadRecoveryFixture : AutoCloseable {
        val io = ReadRecoveryIo()
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        var selectedBinding = binding
        val otherBinding = binding.copy(actorBindingHash = "e".repeat(64))
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN },
            { 1100 }, { 8 }, true, requireSessionObservation = true)
        val authority = AndroidDeviceCheckAuthority(core, true, 5000)
        var actor = "actor"
        var session = 1L
        val changes = AtomicInteger()
        val saved = AuthorityPostLoginDeviceCheckSavedResults(authority,
            bindingFor = { if (it.actorId == "actor") selectedBinding else otherBinding },
            readyRuntime = { null }, isCurrent = { it.actorId == actor && it.sessionGeneration == session },
            sessionIsCurrent = { a, s -> a == actor && s == session },
            isIssuedProof = { false }, nowMs = { 1100 }, onChanged = { changes.incrementAndGet() })
        init { saved.bindSession(actor, session) }
        fun resultBinding() = PostLoginDeviceCheckResultBinding(actor, "installation", "maker", "model", "device", 33,
            "fingerprint", "app", 1, "1", "source", "policy", null, null, "registry", "sha", null, null,
            emptySet(), true, true, true, true)
        override fun close() { io.release.countDown(); authority.shutdownForTests() }
    }

    private class ReadRecoveryIo : DeviceCheckAuthorityPersistence {
        @Volatile var failRead = false
        @Volatile var block = false
        @Volatile var text: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        override fun read(): String? {
            reads.incrementAndGet()
            if (block) { block = false; entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            check(!failRead) { "test_read_failure" }
            return text
        }
        override fun publish(canonicalState: String): DeviceCheckPublication {
            writes.incrementAndGet()
            return DeviceCheckPublication.COMMITTED
        }
    }
}
