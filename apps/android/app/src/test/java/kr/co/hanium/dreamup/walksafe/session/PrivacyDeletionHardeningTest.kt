package kr.co.hanium.dreamup.walksafe.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDeletionHardeningTest {
    @Test
    fun canonicalAcceptedAtMismatchEntersDurableFailClosedState() {
        val journal = pendingJournal(REQUESTED_AT)
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(status(journal, revision = 1L)),
        )

        val result = machine.apply(
            status(
                journal,
                revision = 2L,
                acceptedAt = "2099-01-01T00:00:00Z",
            ),
        )

        assertEquals(
            AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
            result,
        )
        val failed = requireNotNull(machine.snapshotOrNull())
        assertNotNull(failed)
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, failed.phase)
        assertEquals("account_deletion_identity_mismatch", failed.lastErrorCode)
        assertEquals("account_deletion_identity_mismatch", machine.failureReasonOrNull())

        val restored = AccountDeletionStateMachine()
        assertTrue(restored.restore(failed))
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, restored.phase())
    }

    @Test
    fun equalRevisionConflictReplacesRetryReasonWithStableTerminalReason() {
        val journal = pendingJournal(REQUESTED_AT)
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        val first = status(journal, revision = 1L)
        assertEquals(AccountDeletionApplyResult.APPLIED, machine.apply(first))
        assertTrue(machine.markRetry("http_500"))
        val conflictedItems = journal.items.toMutableMap().apply {
            val item =
                DeletionInventoryItem.entries.first {
                    it != DeletionInventoryItem.DEVICE_UNSENT
                }
            this[item] = getValue(item).copy(state = DeletionItemState.IN_PROGRESS)
        }

        assertEquals(
            AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
            machine.apply(first.copy(items = conflictedItems)),
        )
        val failed = requireNotNull(machine.snapshotOrNull())
        assertNotNull(failed)
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, failed.phase)
        assertEquals("account_deletion_revision_conflict", failed.lastErrorCode)
        assertEquals("account_deletion_revision_conflict", machine.failureReasonOrNull())
    }

    @Test
    fun invalidHigherRevisionAdvanceUsesStableStatusConflictReason() {
        val journal = pendingJournal(REQUESTED_AT)
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(status(journal, revision = 1L)),
        )
        assertTrue(machine.markRetry("http_500"))
        val conflictedItems = journal.items.toMutableMap().apply {
            val item = DeletionInventoryItem.SERVER_ORIGINAL
            this[item] = getValue(item).copy(state = DeletionItemState.IN_PROGRESS)
        }

        assertEquals(
            AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
            machine.apply(status(journal, revision = 2L, items = conflictedItems)),
        )
        assertEquals(
            "account_deletion_status_conflict",
            requireNotNull(machine.snapshotOrNull()).lastErrorCode,
        )
        assertEquals("account_deletion_status_conflict", machine.failureReasonOrNull())
    }

    @Test
    fun completedStatusWithoutDeviceEvidenceUsesStableMissingReason() {
        val journal = pendingJournal(REQUESTED_AT)
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        assertTrue(machine.markRetry("http_500"))
        val completedItems = journal.items.mapValues { (_, item) ->
            item.copy(
                state = DeletionItemState.COMPLETED,
                evidenceSha256 = "e".repeat(64),
                terminalAt = REQUESTED_AT,
            )
        }

        assertEquals(
            AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
            machine.apply(
                status(
                    journal = journal,
                    revision = 1L,
                    items = completedItems,
                    receiptSha256 = "f".repeat(64),
                ),
            ),
        )
        assertEquals(
            "account_deletion_device_evidence_missing",
            requireNotNull(machine.snapshotOrNull()).lastErrorCode,
        )
        assertEquals(
            "account_deletion_device_evidence_missing",
            machine.failureReasonOrNull(),
        )
    }

    @Test
    fun failClosedSurvivesRepeatedResumePurgeAndEveryJournalMutation() {
        val journal = pendingJournal(REQUESTED_AT)
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        val terminalReason =
            "terminal_conflict:account_deletion_installation_inventory_missing"
        assertTrue(machine.failClosed(terminalReason))
        val deletedLocal =
            journal.items.getValue(DeletionInventoryItem.DEVICE_UNSENT)
                .copy(state = DeletionItemState.IN_PROGRESS)

        assertTrue(!machine.updateLocal(deletedLocal))
        val firstSnapshot = requireNotNull(machine.snapshotOrNull())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, firstSnapshot.phase)

        val resumed = AccountDeletionStateMachine()
        assertTrue(resumed.restore(firstSnapshot))
        assertTrue(!resumed.updateLocal(deletedLocal))
        val secondSnapshot = requireNotNull(resumed.snapshotOrNull())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, secondSnapshot.phase)

        val restoredAgain = AccountDeletionStateMachine()
        assertTrue(restoredAgain.restore(secondSnapshot))
        assertFalse(restoredAgain.markRetry("resume_retry"))
        assertEquals(
            AccountDeletionPhase.FAIL_CLOSED,
            requireNotNull(restoredAgain.snapshotOrNull()).phase,
        )
        assertEquals(
            terminalReason,
            requireNotNull(restoredAgain.snapshotOrNull()).lastErrorCode,
        )
        val exactTerminalSnapshot = requireNotNull(restoredAgain.snapshotOrNull())
        assertTrue(restoredAgain.failClosed("stale_worker_failure"))
        assertEquals(exactTerminalSnapshot, restoredAgain.snapshotOrNull())
        val status = status(journal, revision = 1L)
        assertEquals(
            AccountDeletionApplyResult.STALE_IGNORED,
            restoredAgain.apply(status),
        )
        assertEquals(exactTerminalSnapshot, restoredAgain.snapshotOrNull())
    }

    @Test
    fun applyAfterFailClosedPreservesExactJournalAndFirstReason() {
        val machine = AccountDeletionStateMachine()
        val journal = pendingJournal(REQUESTED_AT)
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        assertTrue(machine.failClosed(TERMINAL_REASON))
        val terminal = requireNotNull(machine.snapshotOrNull())

        assertEquals(
            AccountDeletionApplyResult.STALE_IGNORED,
            machine.apply(status(journal, revision = 1L)),
        )
        assertEquals(terminal, machine.snapshotOrNull())
        assertEquals(TERMINAL_REASON, terminal.lastErrorCode)
    }

    @Test
    fun queuedProgressWorkerCannotRunAfterTerminalConflict() {
        assertQueuedWorkerCannotRunAfterTerminalConflict("progress")
    }

    @Test
    fun queuedEvidenceRebaseWorkerCannotRunAfterTerminalConflict() {
        assertQueuedWorkerCannotRunAfterTerminalConflict("rebase")
    }

    @Test
    fun progressWriteCompletesBeforeTerminalLinearization() {
        assertWorkerWriteCompletesBeforeTerminalLinearization("progress")
    }

    @Test
    fun progressWriteSkipsWhenTerminalLinearizesFirst() {
        assertWorkerWriteSkipsWhenTerminalLinearizesFirst("progress")
    }

    @Test
    fun evidenceRebaseWriteCompletesBeforeTerminalLinearization() {
        assertWorkerWriteCompletesBeforeTerminalLinearization("rebase")
    }

    @Test
    fun evidenceRebaseWriteSkipsWhenTerminalLinearizesFirst() {
        assertWorkerWriteSkipsWhenTerminalLinearizesFirst("rebase")
    }

    @Test
    fun terminalWorkerPersistenceCompletesBeforeResetLinearization() {
        val machine = initializedMachine()
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val persistedJournal = AtomicReference<AccountDeletionJournal?>()
        val sequence = AtomicInteger()
        val persistenceOrder = AtomicInteger()
        val resetOrder = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerTerminalStageIfCurrent(
                    attempt = attempt,
                    errorCode = TERMINAL_REASON,
                ) { terminalJournal ->
                    persistenceStarted.countDown()
                    check(releasePersistence.await(5, TimeUnit.SECONDS))
                    val terminal = requireNotNull(terminalJournal)
                    check(terminal.lastErrorCode == TERMINAL_REASON)
                    persistedJournal.set(terminal)
                    persistenceOrder.set(sequence.incrementAndGet())
                    true
                }
            }
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))
            val reset = executor.submit<Boolean> {
                resetAttempted.countDown()
                machine.resetForNewEnrollment().also {
                    persistedJournal.set(null)
                    resetOrder.set(sequence.incrementAndGet())
                }
            }
            assertTrue(resetAttempted.await(5, TimeUnit.SECONDS))
            releasePersistence.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.SUCCEEDED,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertTrue(reset.get(5, TimeUnit.SECONDS))
            assertTrue(persistenceOrder.get() < resetOrder.get())
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
            assertEquals(null, machine.snapshotOrNull())
            assertEquals(null, persistedJournal.get())
        } finally {
            releasePersistence.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalWorkerCannotPersistAfterResetLinearizesFirst() {
        val machine = initializedMachine()
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        val terminalApplied = CountDownLatch(1)
        val releaseTerminalPersistence = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val persistedJournal = AtomicReference<AccountDeletionJournal?>()
        val journalWrites = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                check(
                    machine.apply(
                        status(scheduledJournal, revision = 1L).copy(
                            installationId = "install-other-0002",
                        ),
                    ) == AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
                )
                terminalApplied.countDown()
                check(releaseTerminalPersistence.await(5, TimeUnit.SECONDS))
                machine.runWorkerTerminalStageIfCurrent(attempt) { terminalJournal ->
                    journalWrites.incrementAndGet()
                    persistedJournal.set(terminalJournal)
                    true
                }
            }
            val reset = executor.submit<Boolean> {
                check(terminalApplied.await(5, TimeUnit.SECONDS))
                machine.resetForNewEnrollment().also {
                    persistedJournal.set(null)
                    resetFinished.countDown()
                }
            }
            assertTrue(resetFinished.await(5, TimeUnit.SECONDS))
            releaseTerminalPersistence.countDown()

            assertTrue(reset.get(5, TimeUnit.SECONDS))
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(0, journalWrites.get())
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
            assertEquals(null, machine.snapshotOrNull())
            assertEquals(null, persistedJournal.get())
        } finally {
            releaseTerminalPersistence.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun statusApplyTerminalSuccessorCanPersistForOwningWorker() {
        val machine = initializedMachine()
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        assertEquals(
            AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
            machine.apply(
                status(scheduledJournal, revision = 1L).copy(
                    installationId = "install-other-0002",
                ),
            ),
        )
        val exactTerminalJournal = requireNotNull(machine.snapshotOrNull())
        val persistedJournal = AtomicReference<AccountDeletionJournal?>()

        assertEquals(
            AccountDeletionWorkerStageResult.SUCCEEDED,
            machine.runWorkerTerminalStageIfCurrent(attempt) { terminalJournal ->
                persistedJournal.set(terminalJournal)
                true
            },
        )
        assertEquals(exactTerminalJournal, persistedJournal.get())
        assertEquals(exactTerminalJournal, machine.snapshotOrNull())
    }

    @Test
    fun beginRestoreAndRepeatedFailureNeverReplaceExistingStateOrFirstReason() {
        val machine = initializedMachine()
        val original = requireNotNull(machine.snapshotOrNull())
        val replacement = pendingJournal(REQUESTED_AT).copy(
            requestId = "account_delete_" + "2".repeat(64),
        )

        assertFalse(machine.begin(replacement))
        assertFalse(machine.restore(replacement))
        assertEquals(original, machine.snapshotOrNull())

        assertTrue(machine.failClosed(TERMINAL_REASON))
        val terminal = requireNotNull(machine.snapshotOrNull())
        assertFalse(machine.begin(replacement))
        assertFalse(machine.restore(replacement))
        assertTrue(machine.failClosed("later_failure_must_not_replace_first"))
        assertEquals(terminal, machine.snapshotOrNull())
        assertEquals(TERMINAL_REASON, machine.failureReasonOrNull())

        val journalFree = AccountDeletionStateMachine()
        assertTrue(journalFree.failClosed("journal_free_first"))
        assertEquals(null, journalFree.snapshotOrNull())
        assertEquals("journal_free_first", journalFree.failureReasonOrNull())
        assertTrue(journalFree.failClosed("journal_free_later"))
        assertFalse(journalFree.restore(original))
        assertEquals("journal_free_first", journalFree.failureReasonOrNull())
    }

    @Test
    fun recreatedActivityTerminalMakesOldDurableReadAndWriteStageStale() {
        val fixture = initializedCoordinator()
        val oldAttempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val workerReady = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val durableReads = AtomicInteger()
        val durableWrites = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        var newLease: AccountDeletionActivityLease? = null
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                workerReady.countDown()
                check(releaseWorker.await(5, TimeUnit.SECONDS))
                fixture.machine.runWorkerPreparationIfCurrent(oldAttempt) {
                    durableReads.incrementAndGet()
                    durableWrites.incrementAndGet()
                    true
                }
            }
            assertTrue(workerReady.await(5, TimeUnit.SECONDS))
            newLease = fixture.coordinator.attach()
            assertTrue(fixture.machine.failClosed(newLease, TERMINAL_REASON))
            val exactTerminal = requireNotNull(fixture.machine.snapshotOrNull())
            releaseWorker.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(0, durableReads.get())
            assertEquals(0, durableWrites.get())
            assertEquals(exactTerminal, fixture.machine.snapshotOrNull())
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
            assertFalse(fixture.coordinator.detach(fixture.lease))
        } finally {
            releaseWorker.countDown()
            newLease?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun activityRecreationReturnsBeforeBlockedPreparationAndRejectsItsPublish() {
        val fixture = initializedCoordinator()
        val oldAttempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val stagedWrites = AtomicInteger()
        val publishedWrites = AtomicInteger()
        val discardedWrites = AtomicInteger()
        val newLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                fixture.machine.runWorkerPreparedCommitIfCurrent(
                    attempt = oldAttempt,
                    prepare = {
                        writeStarted.countDown()
                        check(releaseWrite.await(5, TimeUnit.SECONDS))
                        stagedWrites.incrementAndGet()
                        true
                    },
                    publish = {
                        publishedWrites.incrementAndGet()
                        true
                    },
                    discardPreparedState = discardedWrites::incrementAndGet,
                )
            }
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            val recreation = executor.submit<AccountDeletionActivityLease> {
                val lease = fixture.coordinator.attach()
                newLease.set(lease)
                lease
            }
            val replacementLease = recreation.get(1, TimeUnit.SECONDS)
            assertFalse(worker.isDone)
            releaseWrite.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, stagedWrites.get())
            assertEquals(0, publishedWrites.get())
            assertEquals(1, discardedWrites.get())
            assertTrue(
                fixture.machine.failClosed(replacementLease, TERMINAL_REASON),
            )
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                fixture.machine.runWorkerPreparationIfCurrent(oldAttempt) { true },
            )
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
        } finally {
            releaseWrite.countDown()
            newLease.get()?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun nullIdentityPreparationDoesNotBlockAttachDetachOrConfirmationAndCannotPublishStale() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val originalLease = coordinator.attach()
        assertTrue(machine.requestConfirmation(originalLease))
        assertFalse(machine.processingBlocked())
        assertFalse(machine.preparationRecoveryRequired())
        val originalAttempt = requireNotNull(
            machine.beginPreparationWorkerAttempt(originalLease),
        )
        assertEquals(null, originalAttempt.requestId)
        assertTrue(machine.processingBlocked())
        assertTrue(machine.preparationRecoveryRequired())
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val stagedWrites = AtomicInteger()
        val publishedWrites = AtomicInteger()
        val discardedWrites = AtomicInteger()
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerPreparedCommitIfCurrent(
                    attempt = originalAttempt,
                    prepare = {
                        ioStarted.countDown()
                        check(releaseIo.await(5, TimeUnit.SECONDS))
                        stagedWrites.incrementAndGet()
                        true
                    },
                    publish = {
                        publishedWrites.incrementAndGet()
                        true
                    },
                    discardPreparedState = discardedWrites::incrementAndGet,
                )
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            val mainTransition = executor.submit<Boolean> {
                val replacement = coordinator.attach()
                check(coordinator.detach(replacement))
                val current = coordinator.attach()
                currentLease.set(current)
                !machine.requestConfirmation(current)
            }

            assertTrue(mainTransition.get(1, TimeUnit.SECONDS))
            assertFalse(worker.isDone)
            releaseIo.countDown()
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, stagedWrites.get())
            assertEquals(0, publishedWrites.get())
            assertEquals(1, discardedWrites.get())
            assertFalse(machine.workerAttemptAllowed(originalAttempt))
            assertTrue(machine.preparationRecoveryRequired())
            assertFalse(machine.durableConfirmationRecoveryRequired())
            assertTrue(machine.processingBlocked())
            assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())
            val retryAttempt = requireNotNull(
                machine.beginPreparationWorkerAttempt(
                    requireNotNull(currentLease.get()),
                ),
            )
            assertTrue(
                machine.abortPreparationRecovery(requireNotNull(currentLease.get())),
            )
            assertFalse(machine.workerAttemptAllowed(retryAttempt))
            assertFalse(machine.preparationRecoveryRequired())
            assertFalse(machine.processingBlocked())
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
        } finally {
            releaseIo.countDown()
            currentLease.get()?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun nullIdentityMonotonicDurableIoHandsSuccessfulStaleWriteToRecovery() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val originalLease = coordinator.attach()
        assertTrue(machine.requestConfirmation(originalLease))
        val originalAttempt = requireNotNull(
            machine.beginPreparationWorkerAttempt(originalLease),
        )
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val durableWrites = AtomicInteger()
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerMonotonicDurableIoIfCurrent(
                    attempt = originalAttempt,
                    action = {
                        ioStarted.countDown()
                        check(releaseIo.await(5, TimeUnit.SECONDS))
                        durableWrites.incrementAndGet()
                        true
                    },
                )
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            val recreation = executor.submit<AccountDeletionActivityLease> {
                coordinator.attach().also(currentLease::set)
            }
            val replacementLease = recreation.get(1, TimeUnit.SECONDS)
            assertFalse(worker.isDone)
            releaseIo.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, durableWrites.get())
            assertTrue(machine.durableConfirmationRecoveryRequired())
            assertTrue(machine.preparationRecoveryRequired())
            assertTrue(machine.processingBlocked())
            assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())
            assertFalse(machine.requestConfirmation(replacementLease))
            assertNotNull(machine.beginPreparationWorkerAttempt(replacementLease))
        } finally {
            releaseIo.countDown()
            currentLease.get()?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun journalMonotonicDurableIoCompletesOldWriteButCannotPublishAfterRotation() {
        val fixture = initializedCoordinator()
        val attempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val durableWrites = AtomicInteger()
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                fixture.machine.runWorkerMonotonicDurableIoIfCurrent(attempt) { journal ->
                    assertEquals(fixture.journal, journal)
                    ioStarted.countDown()
                    check(releaseIo.await(5, TimeUnit.SECONDS))
                    durableWrites.incrementAndGet()
                    true
                }
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            val replacementLease = executor.submit<AccountDeletionActivityLease> {
                fixture.coordinator.attach().also(currentLease::set)
            }.get(1, TimeUnit.SECONDS)
            releaseIo.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, durableWrites.get())
            assertEquals(fixture.journal, fixture.machine.snapshotOrNull())
            assertFalse(fixture.machine.durableConfirmationRecoveryRequired())
            assertNotNull(
                fixture.machine.beginWorkerAttempt(fixture.journal, replacementLease),
            )
        } finally {
            releaseIo.countDown()
            currentLease.get()?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalCleanupMayProgressBeforeRotationAndReplacementVerifiesIt() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val completed = confirmedJournal()
        assertTrue(machine.restore(completed, oldLease))
        val oldAttempt = requireNotNull(
            machine.beginWorkerAttempt(completed, oldLease),
        )
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val metadataPresent = AtomicReference(true)
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldCleanup = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerTerminalCleanupIoIfCurrent(oldAttempt) { journal ->
                    assertEquals(completed, journal)
                    cleanupStarted.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS))
                    metadataPresent.set(false)
                    true
                }
            }
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            val replacementLease = executor.submit<AccountDeletionActivityLease> {
                coordinator.attach().also(currentLease::set)
            }.get(1, TimeUnit.SECONDS)
            releaseCleanup.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                oldCleanup.get(5, TimeUnit.SECONDS),
            )
            assertFalse(metadataPresent.get())
            assertEquals(completed, machine.snapshotOrNull())
            assertEquals(AccountDeletionPhase.COMPLETED, machine.phase())

            val replacementAttempt = requireNotNull(
                machine.beginWorkerAttempt(completed, replacementLease),
            )
            assertEquals(
                AccountDeletionWorkerStageResult.SUCCEEDED,
                machine.runWorkerTerminalCleanupIoIfCurrent(replacementAttempt) {
                    !metadataPresent.get()
                },
            )
        } finally {
            releaseCleanup.countDown()
            currentLease.get()?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalMonotonicDurableIoHandsSuccessfulWriteToProcessAfterRotation() {
        val fixture = initializedCoordinator()
        val attempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val terminalWrites = AtomicInteger()
        val durableTerminal = AtomicReference<AccountDeletionJournal?>()
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                fixture.machine.runWorkerMonotonicTerminalDurableIoIfCurrent(
                    attempt = attempt,
                    errorCode = TERMINAL_REASON,
                    action = { terminalJournal ->
                        assertEquals(
                            AccountDeletionPhase.FAIL_CLOSED,
                            requireNotNull(terminalJournal).phase,
                        )
                        durableTerminal.set(terminalJournal)
                        ioStarted.countDown()
                        check(releaseIo.await(5, TimeUnit.SECONDS))
                        terminalWrites.incrementAndGet()
                        true
                    },
                )
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            val mainTransition = executor.submit<Boolean> {
                val replacement = fixture.coordinator.attach()
                check(fixture.coordinator.detach(replacement))
                val current = fixture.coordinator.attach()
                currentLease.set(current)
                !fixture.machine.requestConfirmation(current)
            }

            assertTrue(mainTransition.get(1, TimeUnit.SECONDS))
            assertFalse(worker.isDone)
            releaseIo.countDown()
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, terminalWrites.get())
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, fixture.machine.phase())
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
            assertEquals(durableTerminal.get(), fixture.machine.snapshotOrNull())
        } finally {
            releaseIo.countDown()
            currentLease.get()?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalMonotonicDurableIoHandsFailedWriteToProcessAfterRotation() {
        val fixture = initializedCoordinator()
        val attempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val failedWrites = AtomicInteger()
        val currentLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                fixture.machine.runWorkerMonotonicTerminalDurableIoIfCurrent(
                    attempt = attempt,
                    errorCode = TERMINAL_REASON,
                ) { terminalJournal ->
                    assertEquals(
                        AccountDeletionPhase.FAIL_CLOSED,
                        requireNotNull(terminalJournal).phase,
                    )
                    ioStarted.countDown()
                    check(releaseIo.await(5, TimeUnit.SECONDS))
                    failedWrites.incrementAndGet()
                    false
                }
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            executor.submit<AccountDeletionActivityLease> {
                fixture.coordinator.attach().also(currentLease::set)
            }.get(1, TimeUnit.SECONDS)
            releaseIo.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, failedWrites.get())
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, fixture.machine.phase())
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
            assertEquals(
                fixture.journal.copy(
                    phase = AccountDeletionPhase.FAIL_CLOSED,
                    lastErrorCode = TERMINAL_REASON,
                ),
                fixture.machine.snapshotOrNull(),
            )
        } finally {
            releaseIo.countDown()
            currentLease.get()?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalMonotonicDurableIoFailClosesLatestNetworkRevision() {
        val fixture = initializedCoordinator()
        val attempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val ioStarted = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val durableTerminal = AtomicReference<AccountDeletionJournal?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                fixture.machine.runWorkerMonotonicTerminalDurableIoIfCurrent(
                    attempt = attempt,
                    errorCode = TERMINAL_REASON,
                ) { terminalJournal ->
                    durableTerminal.set(terminalJournal)
                    ioStarted.countDown()
                    check(releaseIo.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            assertEquals(
                AccountDeletionApplyResult.APPLIED,
                fixture.machine.apply(
                    attempt,
                    status(fixture.journal, revision = 1L),
                ),
            )
            releaseIo.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            val latestTerminal = requireNotNull(fixture.machine.snapshotOrNull())
            assertEquals(1L, latestTerminal.serverRevision)
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, latestTerminal.phase)
            assertEquals(TERMINAL_REASON, latestTerminal.lastErrorCode)
            assertEquals(0L, requireNotNull(durableTerminal.get()).serverRevision)
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
        } finally {
            releaseIo.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun detachInvalidatesOldWorkerAndCannotDetachNewActivityLease() {
        val fixture = initializedCoordinator()
        val oldAttempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        assertTrue(fixture.coordinator.detach(fixture.lease))
        val newLease = fixture.coordinator.attach()
        try {
            val newAttempt = requireNotNull(
                fixture.machine.beginWorkerAttempt(fixture.journal, newLease),
            )
            assertFalse(fixture.coordinator.detach(fixture.lease))
            assertFalse(fixture.machine.workerAttemptAllowed(oldAttempt))
            assertTrue(fixture.machine.workerAttemptAllowed(newAttempt))
        } finally {
            fixture.coordinator.detach(newLease)
        }
    }

    @Test
    fun terminalBeforeNetworkBeginCancelsLeaseAndPerformsNoIo() {
        val fixture = initializedCoordinator()
        val oldAttempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val cancellations = AtomicInteger()
        val ioCalls = AtomicInteger()
        val networkLease = requireNotNull(
            fixture.machine.registerNetworkCall(oldAttempt, cancellations::incrementAndGet),
        )

        val newLease = fixture.coordinator.attach()
        try {
            assertTrue(fixture.machine.failClosed(newLease, TERMINAL_REASON))
            assertFalse(fixture.machine.beginNetworkCall(networkLease))
            assertEquals(0, ioCalls.get())
            assertEquals(1, cancellations.get())
            assertEquals(
                null,
                fixture.machine.completeNetworkCall(
                    networkLease,
                    status(fixture.journal, revision = 1L),
                ),
            )
            assertEquals(0, ioCalls.get())
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
        } finally {
            fixture.coordinator.detach(newLease)
        }
    }

    @Test
    fun networkExecuteDoesNotHoldStateLockAndTerminalRejectsItsResponse() {
        val fixture = initializedCoordinator()
        val oldAttempt = requireNotNull(
            fixture.machine.beginWorkerAttempt(fixture.journal, fixture.lease),
        )
        val cancellations = AtomicInteger()
        val ioCalls = AtomicInteger()
        val persistenceMutations = AtomicInteger()
        val ioStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val networkLease = requireNotNull(
            fixture.machine.registerNetworkCall(oldAttempt, cancellations::incrementAndGet),
        )
        val executor = Executors.newFixedThreadPool(2)
        val newLease = AtomicReference<AccountDeletionActivityLease?>()
        try {
            val response = executor.submit<AccountDeletionApplyResult?> {
                check(fixture.machine.beginNetworkCall(networkLease))
                ioCalls.incrementAndGet()
                ioStarted.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                fixture.machine.completeNetworkCall(
                    networkLease,
                    status(fixture.journal, revision = 1L),
                )
            }
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            val terminal = executor.submit<Boolean> {
                val lease = fixture.coordinator.attach()
                newLease.set(lease)
                fixture.machine.failClosed(lease, TERMINAL_REASON)
            }
            assertTrue(
                "terminal must not wait for network execute",
                terminal.get(5, TimeUnit.SECONDS),
            )
            releaseResponse.countDown()

            assertEquals(null, response.get(5, TimeUnit.SECONDS))
            assertEquals(1, ioCalls.get())
            assertEquals(1, cancellations.get())
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                fixture.machine.runWorkerPreparationIfCurrent(oldAttempt) {
                    persistenceMutations.incrementAndGet()
                    true
                },
            )
            assertEquals(0, persistenceMutations.get())
            assertEquals(TERMINAL_REASON, fixture.machine.failureReasonOrNull())
        } finally {
            releaseResponse.countDown()
            newLease.get()?.let(fixture.coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedAndExceptionalDurableActionsReturnFailedWithoutPartialStateMutation() {
        val machine = initializedMachine()
        val journal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(journal))
        val publishedWrites = AtomicInteger()
        val discardedWrites = AtomicInteger()

        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runWorkerPreparedCommitIfCurrent(
                attempt = attempt,
                prepare = { false },
                publish = {
                    publishedWrites.incrementAndGet()
                    true
                },
                discardPreparedState = discardedWrites::incrementAndGet,
            ),
        )
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runWorkerPreparedCommitIfCurrent(
                attempt = attempt,
                prepare = {
                    throw IllegalStateException("durable action failed")
                },
                publish = {
                    publishedWrites.incrementAndGet()
                    true
                },
                discardPreparedState = discardedWrites::incrementAndGet,
            ),
        )
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runWorkerPreparedCommitIfCurrent(
                attempt = attempt,
                prepare = { true },
                publish = {
                    publishedWrites.incrementAndGet()
                    throw IllegalStateException("publish failed")
                },
                discardPreparedState = discardedWrites::incrementAndGet,
            ),
        )
        assertEquals(1, publishedWrites.get())
        assertEquals(3, discardedWrites.get())
        assertEquals(journal, machine.snapshotOrNull())
        val terminalPublishes = AtomicInteger()
        val terminalDiscards = AtomicInteger()
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runWorkerTerminalPreparedCommitIfCurrent(
                attempt = attempt,
                errorCode = TERMINAL_REASON,
                prepare = {
                    throw IllegalStateException("terminal persistence failed")
                },
                publish = {
                    terminalPublishes.incrementAndGet()
                    true
                },
                discardPreparedState = terminalDiscards::incrementAndGet,
            ),
        )
        assertEquals(0, terminalPublishes.get())
        assertEquals(1, terminalDiscards.get())
        assertEquals(TERMINAL_REASON, machine.failureReasonOrNull())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
    }

    @Test
    fun terminalPreparedCommitDiscardsEveryFailedPreparationOrPublishExactlyOnce() {
        val prepareFailureMachine = initializedMachine()
        val prepareFailureJournal = requireNotNull(prepareFailureMachine.snapshotOrNull())
        val prepareFailureAttempt = requireNotNull(
            prepareFailureMachine.beginWorkerAttempt(prepareFailureJournal),
        )
        val prepareFailurePublishes = AtomicInteger()
        val prepareFailureDiscards = AtomicInteger()
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            prepareFailureMachine.runWorkerTerminalPreparedCommitIfCurrent(
                attempt = prepareFailureAttempt,
                errorCode = TERMINAL_REASON,
                prepare = { false },
                publish = {
                    prepareFailurePublishes.incrementAndGet()
                    true
                },
                discardPreparedState = prepareFailureDiscards::incrementAndGet,
            ),
        )
        assertEquals(0, prepareFailurePublishes.get())
        assertEquals(1, prepareFailureDiscards.get())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, prepareFailureMachine.phase())

        val publishFailureMachine = initializedMachine()
        val publishFailureJournal = requireNotNull(publishFailureMachine.snapshotOrNull())
        val publishFailureAttempt = requireNotNull(
            publishFailureMachine.beginWorkerAttempt(publishFailureJournal),
        )
        val publishFailurePublishes = AtomicInteger()
        val publishFailureDiscards = AtomicInteger()
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            publishFailureMachine.runWorkerTerminalPreparedCommitIfCurrent(
                attempt = publishFailureAttempt,
                errorCode = TERMINAL_REASON,
                prepare = { true },
                publish = {
                    publishFailurePublishes.incrementAndGet()
                    false
                },
                discardPreparedState = publishFailureDiscards::incrementAndGet,
            ),
        )
        assertEquals(1, publishFailurePublishes.get())
        assertEquals(1, publishFailureDiscards.get())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, publishFailureMachine.phase())

        val successfulMachine = initializedMachine()
        val successfulJournal = requireNotNull(successfulMachine.snapshotOrNull())
        val successfulAttempt = requireNotNull(
            successfulMachine.beginWorkerAttempt(successfulJournal),
        )
        val successfulPublishes = AtomicInteger()
        val successfulDiscards = AtomicInteger()
        assertEquals(
            AccountDeletionWorkerStageResult.SUCCEEDED,
            successfulMachine.runWorkerTerminalPreparedCommitIfCurrent(
                attempt = successfulAttempt,
                errorCode = TERMINAL_REASON,
                prepare = { true },
                publish = {
                    successfulPublishes.incrementAndGet()
                    true
                },
                discardPreparedState = successfulDiscards::incrementAndGet,
            ),
        )
        assertEquals(1, successfulPublishes.get())
        assertEquals(0, successfulDiscards.get())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, successfulMachine.phase())
    }

    @Test
    fun durableConfirmationRecoveryBlocksPrivacyAndSurvivesWithoutActivityOrSession() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val firstLease = coordinator.attach()

        assertTrue(machine.enterDurableConfirmationRecovery(firstLease))
        assertTrue(machine.durableConfirmationRecoveryRequired())
        assertTrue(machine.processingBlocked())
        assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())
        assertEquals(null, machine.snapshotOrNull())
        assertEquals(null, machine.failureReasonOrNull())
        assertFalse(machine.requestConfirmation(firstLease))
        assertFalse(machine.cancelConfirmation(firstLease))
        assertFalse(machine.resetForNewEnrollment(firstLease))

        val staleAttempt = requireNotNull(
            machine.beginPreparationWorkerAttempt(firstLease),
        )
        assertTrue(coordinator.detach(firstLease))
        assertFalse(machine.workerAttemptAllowed(staleAttempt))
        assertTrue(machine.durableConfirmationRecoveryRequired())
        assertTrue(machine.processingBlocked())
        assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())

        val replacementLease = coordinator.attach()
        try {
            assertFalse(machine.cancelConfirmation(replacementLease))
            assertNotNull(machine.beginPreparationWorkerAttempt(replacementLease))
            assertEquals(null, machine.snapshotOrNull())
        } finally {
            coordinator.detach(replacementLease)
        }
    }

    @Test
    fun durableConfirmationAndPreJournalRetryEachRetireTheirPreparationAttempt() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        try {
            assertTrue(machine.requestConfirmation(lease))
            assertFalse(machine.processingBlocked())
            val initialAttempt = requireNotNull(
                machine.beginPreparationWorkerAttempt(lease),
            )
            assertTrue(machine.processingBlocked())
            assertTrue(machine.preparationRecoveryRequired())
            val cancellations = AtomicInteger()
            val callLease = requireNotNull(
                machine.registerNetworkCall(initialAttempt, cancellations::incrementAndGet),
            )
            assertTrue(machine.beginNetworkCall(callLease))

            assertTrue(machine.retainDurableConfirmationRecovery(initialAttempt))
            assertEquals(1, cancellations.get())
            assertFalse(machine.workerAttemptAllowed(initialAttempt))
            assertFalse(machine.beginNetworkCall(callLease))
            assertFalse(machine.retainDurableConfirmationRecovery(initialAttempt))
            assertTrue(machine.durableConfirmationRecoveryRequired())
            assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())
            assertTrue(machine.processingBlocked())
            assertEquals(null, machine.snapshotOrNull())

            val failedPreJournalAttempt = requireNotNull(
                machine.beginPreparationWorkerAttempt(lease),
            )
            assertTrue(
                machine.retainDurableConfirmationRecovery(failedPreJournalAttempt),
            )
            assertFalse(machine.workerAttemptAllowed(failedPreJournalAttempt))
            assertFalse(
                machine.retainDurableConfirmationRecovery(failedPreJournalAttempt),
            )

            val retryAttempt = requireNotNull(
                machine.beginPreparationWorkerAttempt(lease),
            )
            val journal = pendingJournal(REQUESTED_AT)
            assertTrue(machine.begin(journal, retryAttempt))
            assertFalse(machine.durableConfirmationRecoveryRequired())
            assertFalse(machine.preparationRecoveryRequired())
            assertEquals(AccountDeletionPhase.REQUEST_PENDING, machine.phase())
            assertEquals(journal, machine.snapshotOrNull())
            assertTrue(machine.processingBlocked())
        } finally {
            coordinator.detach(lease)
        }
    }

    @Test
    fun trustedRestoreAndTerminalAuthorityExplicitlyRetireRecoveryFlag() {
        val restoreCoordinator = AccountDeletionProcessCoordinator()
        val restoreMachine = restoreCoordinator.stateMachine
        val restoreLease = restoreCoordinator.attach()
        val restoredJournal = pendingJournal(REQUESTED_AT)
        try {
            assertTrue(restoreMachine.enterDurableConfirmationRecovery(restoreLease))
            assertTrue(restoreMachine.restore(restoredJournal, restoreLease))
            assertFalse(restoreMachine.durableConfirmationRecoveryRequired())
            assertFalse(restoreMachine.preparationRecoveryRequired())
            assertEquals(restoredJournal, restoreMachine.snapshotOrNull())
        } finally {
            restoreCoordinator.detach(restoreLease)
        }

        val terminalCoordinator = AccountDeletionProcessCoordinator()
        val terminalMachine = terminalCoordinator.stateMachine
        val terminalLease = terminalCoordinator.attach()
        try {
            assertTrue(terminalMachine.enterDurableConfirmationRecovery(terminalLease))
            assertTrue(terminalMachine.failClosed(terminalLease, TERMINAL_REASON))
            assertFalse(terminalMachine.durableConfirmationRecoveryRequired())
            assertFalse(terminalMachine.preparationRecoveryRequired())
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, terminalMachine.phase())
            assertEquals(TERMINAL_REASON, terminalMachine.failureReasonOrNull())
        } finally {
            terminalCoordinator.detach(terminalLease)
        }
    }

    @Test
    fun resetReservationRollbackFailClosesWithoutClearingAndCommitClearsOnSuccess() {
        val failedCoordinator = AccountDeletionProcessCoordinator()
        val failedMachine = failedCoordinator.stateMachine
        val failedLease = failedCoordinator.attach()
        val completed = confirmedJournal()
        assertTrue(failedMachine.restore(completed, failedLease))
        val failedAttempt = requireNotNull(
            failedMachine.beginWorkerAttempt(completed, failedLease),
        )
        val failedReservation = requireNotNull(
            failedMachine.reserveWorkerResetIfCurrent(failedAttempt),
        )
        val destructiveAction: () -> Boolean = {
            throw IllegalStateException("reset action failed")
        }
        val durableResetSucceeded = try {
            destructiveAction()
        } catch (_: Exception) {
            false
        }
        assertFalse(durableResetSucceeded)
        assertEquals(AccountDeletionPhase.RESET_PENDING, failedMachine.phase())
        assertFalse(failedMachine.workerAttemptAllowed(failedAttempt))
        assertEquals(null, failedMachine.beginWorkerAttempt(completed, failedLease))
        val failedReplacementLease = failedCoordinator.attach()
        assertFalse(failedMachine.activityLeaseIsCurrent(failedLease))
        assertTrue(failedMachine.rollbackWorkerReset(failedReservation))
        assertEquals(
            completed.copy(
                phase = AccountDeletionPhase.FAIL_CLOSED,
                lastErrorCode = "account_deletion_reset_failed",
            ),
            failedMachine.snapshotOrNull(),
        )
        assertEquals("account_deletion_reset_failed", failedMachine.failureReasonOrNull())
        assertFalse(failedMachine.rollbackWorkerReset(failedReservation))
        failedCoordinator.detach(failedReplacementLease)

        val successfulCoordinator = AccountDeletionProcessCoordinator()
        val successfulMachine = successfulCoordinator.stateMachine
        val successfulLease = successfulCoordinator.attach()
        assertTrue(successfulMachine.restore(completed, successfulLease))
        try {
            val successfulAttempt = requireNotNull(
                successfulMachine.beginWorkerAttempt(completed, successfulLease),
            )
            val successfulReservation = requireNotNull(
                successfulMachine.reserveWorkerResetIfCurrent(successfulAttempt),
            )
            val forgedReservation = AccountDeletionResetReservation(
                generation = successfulReservation.generation,
                journal = completed,
            )
            assertFalse(successfulMachine.commitWorkerReset(forgedReservation))
            assertFalse(successfulMachine.rollbackWorkerReset(forgedReservation))
            assertEquals(AccountDeletionPhase.RESET_PENDING, successfulMachine.phase())
            assertFalse(successfulMachine.resetForNewEnrollment(successfulLease))
            assertEquals(
                null,
                successfulMachine.registerNetworkCall(successfulAttempt) { Unit },
            )
            assertTrue(successfulMachine.commitWorkerReset(successfulReservation))
            assertEquals(AccountDeletionPhase.IDLE, successfulMachine.phase())
            assertEquals(null, successfulMachine.snapshotOrNull())
            assertEquals(null, successfulMachine.failureReasonOrNull())
            assertFalse(successfulMachine.workerAttemptAllowed(successfulAttempt))
            assertFalse(successfulMachine.commitWorkerReset(successfulReservation))
            assertFalse(successfulMachine.rollbackWorkerReset(successfulReservation))
            assertTrue(successfulMachine.requestConfirmation(successfulLease))
        } finally {
            successfulCoordinator.detach(successfulLease)
        }
    }

    @Test
    fun activityRotationDuringResetReservationDoesNotBlockProcessOwnedCommit() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val completed = confirmedJournal()
        assertTrue(machine.restore(completed, oldLease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(completed, oldLease))
        val reservation = requireNotNull(machine.reserveWorkerResetIfCurrent(attempt))
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val rotatedLease = AtomicReference<AccountDeletionActivityLease?>()
        try {
            val action = executor.submit<Boolean> {
                actionStarted.countDown()
                check(releaseAction.await(5, TimeUnit.SECONDS))
                true
            }
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS))
            val rotation = executor.submit<Boolean> {
                val lease = coordinator.attach()
                rotatedLease.set(lease)
                !coordinator.detach(oldLease)
            }
            assertTrue(
                "attach and detach must not wait for the destructive action",
                rotation.get(5, TimeUnit.SECONDS),
            )
            assertFalse(machine.activityLeaseIsCurrent(oldLease))
            releaseAction.countDown()
            assertTrue(action.get(5, TimeUnit.SECONDS))

            assertTrue(machine.commitWorkerReset(reservation))
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
            assertEquals(null, machine.snapshotOrNull())
            assertFalse(machine.activityLeaseIsCurrent(oldLease))
            assertTrue(
                machine.activityLeaseIsCurrent(requireNotNull(rotatedLease.get())),
            )
        } finally {
            releaseAction.countDown()
            rotatedLease.get()?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun terminalDuringResetReservationProceedsAndBlocksStaleCommit() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val completed = confirmedJournal()
        assertTrue(machine.restore(completed, oldLease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(completed, oldLease))
        val reservation = requireNotNull(machine.reserveWorkerResetIfCurrent(attempt))
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val newLease = AtomicReference<AccountDeletionActivityLease?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val action = executor.submit<Boolean> {
                actionStarted.countDown()
                check(releaseAction.await(5, TimeUnit.SECONDS))
                true
            }
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS))
            val terminal = executor.submit<Boolean> {
                val lease = coordinator.attach()
                newLease.set(lease)
                machine.failClosed(lease, TERMINAL_REASON)
            }
            assertTrue(
                "terminal must not wait for the destructive action",
                terminal.get(5, TimeUnit.SECONDS),
            )
            assertEquals(AccountDeletionPhase.RESET_PENDING, machine.phase())
            val exactTerminal = requireNotNull(machine.snapshotOrNull())
            releaseAction.countDown()
            assertTrue(action.get(5, TimeUnit.SECONDS))

            assertFalse(machine.commitWorkerReset(reservation))
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
            assertEquals(exactTerminal, machine.snapshotOrNull())
            assertEquals(TERMINAL_REASON, machine.failureReasonOrNull())
            assertFalse(machine.rollbackWorkerReset(reservation))
        } finally {
            releaseAction.countDown()
            newLease.get()?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun resetActionAndGatewayDeliveryCannotDeadlockAcrossProcessCoordinators() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        val completed = confirmedJournal()
        assertTrue(machine.restore(completed, lease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(completed, lease))
        val reservation = requireNotNull(machine.reserveWorkerResetIfCurrent(attempt))
        val owner = Any()
        val deliveries = AtomicInteger()
        val subscriberEntered = CountDownLatch(1)
        val allowSubscriberStateRead = CountDownLatch(1)
        val subscriberStateRead = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            GatewaySessionProcessCoordinator.attach(owner) {
                if (deliveries.incrementAndGet() == 2) {
                    subscriberEntered.countDown()
                    check(allowSubscriberStateRead.await(5, TimeUnit.SECONDS))
                    machine.snapshotOrNull()
                    subscriberStateRead.countDown()
                }
            }
            val firstGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val firstClear = executor.submit<Boolean> {
                GatewaySessionProcessCoordinator.clear(firstGeneration) != null
            }
            assertTrue(subscriberEntered.await(5, TimeUnit.SECONDS))

            val resetAction = executor.submit<Boolean> {
                val generation = GatewaySessionProcessCoordinator.snapshot().generation
                GatewaySessionProcessCoordinator.clear(generation) != null
            }
            val expectedResetGeneration = firstGeneration + 2L
            val generationDeadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            var observedGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            while (
                observedGeneration < expectedResetGeneration &&
                System.nanoTime() < generationDeadline
            ) {
                Thread.yield()
                observedGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            }
            assertEquals(expectedResetGeneration, observedGeneration)
            allowSubscriberStateRead.countDown()

            assertTrue(firstClear.get(5, TimeUnit.SECONDS))
            assertTrue(subscriberStateRead.await(5, TimeUnit.SECONDS))
            assertTrue(resetAction.get(5, TimeUnit.SECONDS))
            assertTrue(machine.commitWorkerReset(reservation))
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
        } finally {
            allowSubscriberStateRead.countDown()
            GatewaySessionProcessCoordinator.detach(owner)
            val cleanupGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            GatewaySessionProcessCoordinator.clear(cleanupGeneration)
            coordinator.detach(lease)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun legacyFailClosedUpgradeIsLeaseBoundAndIndependentOfNetworkEligibility() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        val terminal = pendingJournal(REQUESTED_AT).copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = "legacy_fail_closed",
        )
        assertTrue(machine.restore(terminal, lease))
        assertEquals(null, machine.beginWorkerAttempt(terminal, lease))
        val attempt = requireNotNull(
            machine.beginLegacyFailClosedUpgradeAttempt(terminal, lease),
        )

        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runLegacyFailClosedUpgradeStageIfCurrent(attempt) { false },
        )
        assertEquals(terminal, machine.snapshotOrNull())
        assertEquals(
            AccountDeletionWorkerStageResult.FAILED,
            machine.runLegacyFailClosedUpgradeStageIfCurrent(attempt) {
                throw IllegalStateException("legacy upgrade persistence failed")
            },
        )
        val persisted = AtomicReference<AccountDeletionJournal?>()
        assertEquals(
            AccountDeletionWorkerStageResult.SUCCEEDED,
            machine.runLegacyFailClosedUpgradeStageIfCurrent(attempt) { journal ->
                persisted.set(journal)
                true
            },
        )
        assertEquals(terminal, persisted.get())
        assertEquals(terminal, machine.snapshotOrNull())

        val replacementLease = coordinator.attach()
        try {
            val staleActionCalls = AtomicInteger()
            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                machine.runLegacyFailClosedUpgradeStageIfCurrent(attempt) {
                    staleActionCalls.incrementAndGet()
                    true
                },
            )
            assertEquals(0, staleActionCalls.get())
            assertNotNull(
                machine.beginLegacyFailClosedUpgradeAttempt(terminal, replacementLease),
            )
        } finally {
            coordinator.detach(replacementLease)
        }
    }

    @Test
    fun legacyFailClosedUpgradeRejectsCompletedAndNonterminalJournals() {
        val completedCoordinator = AccountDeletionProcessCoordinator()
        val completedLease = completedCoordinator.attach()
        val completed = confirmedJournal()
        assertTrue(completedCoordinator.stateMachine.restore(completed, completedLease))
        assertEquals(
            null,
            completedCoordinator.stateMachine.beginLegacyFailClosedUpgradeAttempt(
                completed,
                completedLease,
            ),
        )
        completedCoordinator.detach(completedLease)

        val pendingCoordinator = initializedCoordinator()
        try {
            assertEquals(
                null,
                pendingCoordinator.machine.beginLegacyFailClosedUpgradeAttempt(
                    pendingCoordinator.journal,
                    pendingCoordinator.lease,
                ),
            )
        } finally {
            pendingCoordinator.coordinator.detach(pendingCoordinator.lease)
        }
    }

    @Test
    fun completedJournalRequiresAllNineTerminalItems() {
        val journal = pendingJournal(REQUESTED_AT)
        var rejected = false
        try {
            journal.copy(
                phase = AccountDeletionPhase.COMPLETED,
                serverRevision = 1L,
                receiptSha256 = "a".repeat(64),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun pendingJournal(requestedAt: String): AccountDeletionJournal =
        AccountDeletionJournal.pending(
            gatewayOrigin = "https://gateway.example.test",
            installationId = "install-test-0001",
            requestId = "account_delete_" + "1".repeat(64),
            requestedAt = requestedAt,
        )

    private fun initializedCoordinator(): CoordinatorFixture {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        val journal = pendingJournal(REQUESTED_AT)
        assertTrue(machine.requestConfirmation(lease))
        val preparationAttempt = requireNotNull(
            machine.beginPreparationWorkerAttempt(lease),
        )
        assertTrue(machine.begin(journal, preparationAttempt))
        return CoordinatorFixture(coordinator, machine, lease, journal)
    }

    private fun confirmedJournal(): AccountDeletionJournal {
        val pending = pendingJournal(REQUESTED_AT)
        val terminalItems = pending.items.mapValues { (_, status) ->
            status.copy(
                state = DeletionItemState.COMPLETED,
                evidenceSha256 = "a".repeat(64),
                terminalAt = REQUESTED_AT,
            )
        }
        return pending.copy(
            phase = AccountDeletionPhase.COMPLETED,
            serverRevision = 1L,
            items = terminalItems,
            receiptSha256 = "b".repeat(64),
            acceptedAt = REQUESTED_AT,
            accountGeneration = 7L,
            tombstoneId = "tombstone-test-0001",
            requestReceiptSha256 = "c".repeat(64),
            deviceEvidenceId = "device-evidence-test-0001",
            deviceEvidenceSha256 = "d".repeat(64),
            deviceEvidenceExpectedStatusRevision = 1L,
            deviceEvidenceResult = "DELETED",
            deviceEvidenceCompletedAt = REQUESTED_AT,
            deviceEvidenceAcknowledged = true,
        )
    }

    private class CoordinatorFixture(
        val coordinator: AccountDeletionProcessCoordinator,
        val machine: AccountDeletionStateMachine,
        val lease: AccountDeletionActivityLease,
        val journal: AccountDeletionJournal,
    )

    private fun assertQueuedWorkerCannotRunAfterTerminalConflict(
        workerName: String,
    ) {
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(pendingJournal(REQUESTED_AT)))
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        val blockerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val markerWrites = AtomicInteger()
        val localWrites = AtomicInteger()
        val networkCalls = AtomicInteger()
        val callbacks = AtomicInteger()
        val privacyFenceHolds = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.execute {
                blockerStarted.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            }
            executor.execute {
                try {
                    dispatchAccountDeletionWorkerStage(
                        stateMachine = machine,
                        attempt = attempt,
                        keepPrivacyFenceClosed = {
                            privacyFenceHolds.incrementAndGet()
                        },
                        dispatch = {
                            markerWrites.incrementAndGet()
                            localWrites.incrementAndGet()
                            networkCalls.incrementAndGet()
                            callbacks.incrementAndGet()
                        },
                    )
                } finally {
                    workerFinished.countDown()
                }
            }
            assertTrue("$workerName blocker did not start", blockerStarted.await(5, TimeUnit.SECONDS))
            val terminalReason =
                "terminal_conflict:account_deletion_installation_inventory_missing"
            assertTrue(machine.failClosed(terminalReason))
            val exactTerminalJournal = requireNotNull(machine.snapshotOrNull())
            releaseWorker.countDown()
            assertTrue("$workerName worker did not finish", workerFinished.await(5, TimeUnit.SECONDS))

            assertEquals(0, markerWrites.get())
            assertEquals(0, localWrites.get())
            assertEquals(0, networkCalls.get())
            assertEquals(0, callbacks.get())
            assertEquals(1, privacyFenceHolds.get())
            assertTrue(machine.failClosed("${workerName}_storage"))
            assertEquals(exactTerminalJournal, machine.snapshotOrNull())
            assertEquals(terminalReason, exactTerminalJournal.lastErrorCode)
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertWorkerWriteCompletesBeforeTerminalLinearization(
        workerName: String,
    ) {
        val machine = initializedMachine()
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val terminalAttempted = CountDownLatch(1)
        val markerWrites = AtomicInteger()
        val sequence = AtomicInteger()
        val writeOrder = AtomicInteger()
        val terminalOrder = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerPreparationIfCurrent(attempt) {
                    writeStarted.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                    markerWrites.incrementAndGet()
                    writeOrder.set(sequence.incrementAndGet())
                    true
                }
            }
            assertTrue(
                "$workerName durable write did not start",
                writeStarted.await(5, TimeUnit.SECONDS),
            )
            val terminal = executor.submit<Boolean> {
                terminalAttempted.countDown()
                machine.failClosed(TERMINAL_REASON).also {
                    terminalOrder.set(sequence.incrementAndGet())
                }
            }
            assertTrue(terminalAttempted.await(5, TimeUnit.SECONDS))
            releaseWrite.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.SUCCEEDED,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertTrue(terminal.get(5, TimeUnit.SECONDS))
            assertEquals(1, markerWrites.get())
            assertTrue(writeOrder.get() < terminalOrder.get())
            assertExactTerminalJournal(machine, scheduledJournal)
        } finally {
            releaseWrite.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertWorkerWriteSkipsWhenTerminalLinearizesFirst(
        workerName: String,
    ) {
        val machine = initializedMachine()
        val scheduledJournal = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(machine.beginWorkerAttempt(scheduledJournal))
        val workerReady = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val markerWrites = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<AccountDeletionWorkerStageResult> {
                workerReady.countDown()
                check(releaseWorker.await(5, TimeUnit.SECONDS))
                machine.runWorkerPreparationIfCurrent(attempt) {
                    markerWrites.incrementAndGet()
                    true
                }
            }
            assertTrue(
                "$workerName worker did not reach the release latch",
                workerReady.await(5, TimeUnit.SECONDS),
            )
            val terminal = executor.submit<Boolean> {
                machine.failClosed(TERMINAL_REASON)
            }
            assertTrue(terminal.get(5, TimeUnit.SECONDS))
            val exactTerminalJournal = requireNotNull(machine.snapshotOrNull())
            releaseWorker.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                worker.get(5, TimeUnit.SECONDS),
            )
            assertEquals(0, markerWrites.get())
            assertEquals(exactTerminalJournal, machine.snapshotOrNull())
            assertEquals(TERMINAL_REASON, exactTerminalJournal.lastErrorCode)
            assertExactTerminalJournal(machine, scheduledJournal)
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun initializedMachine(): AccountDeletionStateMachine =
        AccountDeletionStateMachine().also { machine ->
            assertTrue(machine.requestConfirmation())
            assertTrue(machine.begin(pendingJournal(REQUESTED_AT)))
        }

    private fun assertExactTerminalJournal(
        machine: AccountDeletionStateMachine,
        beforeTerminal: AccountDeletionJournal,
    ) {
        val expected = beforeTerminal.copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = TERMINAL_REASON,
        )
        assertEquals(expected, machine.snapshotOrNull())
        assertTrue(machine.failClosed("stale_worker_storage"))
        assertEquals(expected, machine.snapshotOrNull())
    }

    private fun status(
        journal: AccountDeletionJournal,
        revision: Long,
        acceptedAt: String = REQUESTED_AT,
        items: Map<DeletionInventoryItem, DeletionItemStatus> =
            journal.items.mapValues { (_, item) ->
                item.copy(updatedAt = acceptedAt)
            },
        receiptSha256: String? = null,
    ): AccountDeletionStatus = AccountDeletionStatus(
        schemaVersion = ACCOUNT_DELETION_STATUS_SCHEMA_VERSION,
        installationId = journal.installationId,
        requestId = journal.requestId,
        requestedAt = acceptedAt,
        updatedAt = acceptedAt,
        revision = revision,
        clientRevision = journal.clientRevision,
        accountGeneration = 7L,
        tombstoneId = "tombstone-test-0001",
        requestReceiptSha256 = "b".repeat(64),
        items = items,
        receiptSha256 = receiptSha256,
    )

    private companion object {
        const val REQUESTED_AT = "2026-07-25T00:00:00Z"
        const val TERMINAL_REASON =
            "terminal_conflict:account_deletion_installation_inventory_missing"
    }
}
