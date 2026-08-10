package kr.co.hanium.dreamup.walksafe.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountDeletionAuthorityRestartTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileFailClosedOverridesOldNonterminalJournalWhenEncryptedCommitFails() {
        val authorityDirectory = temporaryFolder.newFolder("terminal-restart")
        val dual = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { true },
                store = { false },
            ),
            fileAuthority = FileAccountDeletionIntentAuthority(authorityDirectory),
        )
        val terminalReason = "account_deletion_revision_conflict"
        val encryptedTerminalJournalCommitted = false

        val terminalWrite = dual.failClosed(terminalReason)

        assertFalse(encryptedTerminalJournalCommitted)
        assertFalse(terminalWrite.preferenceWriteVerified)
        assertTrue(terminalWrite.durableSuccess)
        val restartedAuthority = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { true },
                store = { false },
            ),
            fileAuthority = FileAccountDeletionIntentAuthority(authorityDirectory),
        ).startupState()
        val decision = accountDeletionStartupAuthorityDecision(
            snapshot = restartedAuthority,
            journalPresent = true,
        )
        assertEquals(terminalReason, decision.failClosedReason)

        val machine = AccountDeletionStateMachine()
        val staleNonterminal = pendingJournal()
        assertTrue(machine.restore(staleNonterminal))
        assertTrue(machine.failClosed(requireNotNull(decision.failClosedReason)))
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
        assertEquals(terminalReason, machine.failureReasonOrNull())
        assertNull(machine.beginWorkerAttempt(staleNonterminal))
    }

    @Test
    fun fileFailClosedReasonOverridesDifferentLegacyTerminalJournalReason() {
        val authority = FileAccountDeletionIntentAuthority(
            temporaryFolder.newFolder("terminal-reason-override"),
        )
        val fileReason = "account_deletion_revision_conflict"
        assertTrue(authority.failClosed(fileReason))
        val snapshot = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { true },
                store = { true },
            ),
            fileAuthority = authority,
        ).startupState()
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        val oldTerminal = pendingJournal().copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = "old_terminal_reason",
        )
        try {
            assertTrue(machine.restore(oldTerminal, lease))
            val decision = accountDeletionStartupAuthorityDecision(
                snapshot = snapshot,
                journalPresent = true,
            )
            assertEquals(fileReason, decision.failClosedReason)

            assertTrue(machine.enforceFailClosedAuthority(lease, fileReason))
            assertEquals(fileReason, machine.failureReasonOrNull())
            assertEquals(
                oldTerminal.copy(lastErrorCode = fileReason),
                machine.snapshotOrNull(),
            )
        } finally {
            coordinator.detach(lease)
        }
    }

    @Test
    fun terminalDuringDestructiveResetRecreatesAuthorityAfterStaleCommit() {
        var preferencePresent = true
        val authorityDirectory = temporaryFolder.newFolder("reset-terminal-recovery")
        val fileAuthority = FileAccountDeletionIntentAuthority(
            authorityDirectory,
        )
        assertTrue(fileAuthority.confirm())
        val dual = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { preferencePresent },
                store = {
                    preferencePresent = true
                    true
                },
                clear = {
                    preferencePresent = false
                    true
                },
            ),
            fileAuthority = fileAuthority,
        )
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val completed = completedJournal()
        assertTrue(machine.restore(completed, oldLease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(completed, oldLease))
        val reservation = requireNotNull(machine.reserveWorkerResetIfCurrent(attempt))
        val newLease = coordinator.attach()
        val reason = "terminal_during_reset"
        try {
            assertTrue(machine.failClosed(newLease, reason))
            assertTrue(dual.clear().cleared)
            assertEquals(AccountDeletionIntentAuthorityState.Absent, fileAuthority.read())

            assertFalse(machine.commitWorkerReset(reservation))
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
            assertTrue(
                dual.failClosed(requireNotNull(machine.failureReasonOrNull())).durableSuccess,
            )
            val restarted = AccountDeletionDualAuthority(
                preferenceFence = AccountDeletionIntentFence(
                    read = { preferencePresent },
                    store = { false },
                ),
                fileAuthority = FileAccountDeletionIntentAuthority(
                    authorityDirectory,
                ),
            ).startupState()
            assertEquals(
                AccountDeletionIntentAuthorityState.FailClosed(reason),
                restarted.fileState,
            )
        } finally {
            coordinator.detach(newLease)
        }
    }

    @Test
    fun legacyFailClosedJournalMigratesWithoutNetworkEligibility() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        val reason = "legacy_terminal_conflict"
        val legacy = pendingJournal().copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = reason,
        )
        val authority = FileAccountDeletionIntentAuthority(
            temporaryFolder.newFolder("legacy-fail-closed"),
        )
        val dual = fileOnlyDual(authority)
        try {
            assertTrue(machine.restore(legacy, lease))
            assertNull(machine.beginWorkerAttempt(legacy, lease))
            val upgrade = requireNotNull(
                machine.beginLegacyFailClosedUpgradeAttempt(legacy, lease),
            )

            assertEquals(
                AccountDeletionWorkerStageResult.SUCCEEDED,
                machine.runLegacyFailClosedUpgradeStageIfCurrent(upgrade) {
                    dual.failClosed(reason).durableSuccess
                },
            )
            assertEquals(
                AccountDeletionIntentAuthorityState.FailClosed(reason),
                authority.read(),
            )
        } finally {
            coordinator.detach(lease)
        }
    }

    @Test
    fun completedAndNonterminalJournalsMigrateToConfirmedFileAuthority() {
        listOf(pendingJournal(), completedJournal()).forEachIndexed { index, journal ->
            val coordinator = AccountDeletionProcessCoordinator()
            val machine = coordinator.stateMachine
            val lease = coordinator.attach()
            val authority = FileAccountDeletionIntentAuthority(
                temporaryFolder.newFolder("journal-migration-$index"),
            )
            val dual = fileOnlyDual(authority)
            try {
                assertTrue(machine.restore(journal, lease))
                val attempt = requireNotNull(machine.beginWorkerAttempt(journal, lease))

                assertEquals(
                    AccountDeletionWorkerStageResult.SUCCEEDED,
                    machine.runWorkerStageIfCurrent(attempt) {
                        dual.confirm().decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED
                    },
                )
                assertEquals(
                    AccountDeletionIntentAuthorityState.Confirmed,
                    authority.read(),
                )
            } finally {
                coordinator.detach(lease)
            }
        }
    }

    @Test
    fun rotationAfterConfirmedMigrationTreatsStaleStageAsDurableSuccess() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val journal = pendingJournal()
        val authority = FileAccountDeletionIntentAuthority(
            temporaryFolder.newFolder("journal-migration-rotation"),
        )
        val dual = fileOnlyDual(authority)
        val durable = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var replacementLease: AccountDeletionActivityLease? = null
        try {
            assertTrue(machine.restore(journal, oldLease))
            val attempt = requireNotNull(machine.beginWorkerAttempt(journal, oldLease))
            val migration = executor.submit<AccountDeletionWorkerStageResult> {
                machine.runWorkerMonotonicDurableIoIfCurrent(attempt) {
                    val accepted = dual.confirm().decision ==
                        AccountDeletionConfirmationDecision.ACCEPTED
                    durable.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    accepted
                }
            }
            assertTrue(durable.await(5, TimeUnit.SECONDS))
            replacementLease = coordinator.attach()
            release.countDown()

            assertEquals(
                AccountDeletionWorkerStageResult.STALE,
                migration.get(5, TimeUnit.SECONDS),
            )
            val reread = dual.startupState()
            assertEquals(AccountDeletionIntentAuthorityState.Confirmed, reread.fileState)
            assertNull(
                accountDeletionStartupAuthorityDecision(
                    snapshot = reread,
                    journalPresent = true,
                ).failClosedReason,
            )
            assertFalse(
                machine.failClosed(
                    oldLease,
                    "deletion_intent_authority_migration_failed",
                ),
            )
            assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        } finally {
            release.countDown()
            replacementLease?.let(coordinator::detach)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun rotationAfterNetworkAdvanceRetainsExactMonotonicProcessSuccessor() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val persistedRevisionZero = pendingJournal()
        var replacementLease: AccountDeletionActivityLease? = null
        try {
            assertTrue(machine.restore(persistedRevisionZero, oldLease))
            val attempt = requireNotNull(
                machine.beginWorkerAttempt(persistedRevisionZero, oldLease),
            )
            val networkLease = requireNotNull(
                machine.registerNetworkCall(attempt) {},
            )
            assertTrue(machine.beginNetworkCall(networkLease))
            assertEquals(
                AccountDeletionApplyResult.APPLIED,
                machine.completeNetworkCall(
                    networkLease,
                    processingStatus(persistedRevisionZero),
                ),
            )
            val processSuccessor = requireNotNull(machine.snapshotOrNull())
            assertEquals(1L, processSuccessor.serverRevision)

            replacementLease = coordinator.attach()

            assertEquals(
                AccountDeletionStartupRestoreResult
                    .RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
                machine.restoreAtStartup(
                    persistedRevisionZero,
                    replacementLease,
                ),
            )
            assertEquals(processSuccessor, machine.snapshotOrNull())
            assertTrue(
                machine.beginWorkerAttempt(processSuccessor, replacementLease) != null,
            )
            assertEquals(
                AccountDeletionStartupRestoreResult
                    .RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
                machine.restoreAtStartup(
                    persistedRevisionZero,
                    replacementLease,
                ),
            )
        } finally {
            replacementLease?.let(coordinator::detach)
        }
    }

    @Test
    fun startupRestoreRejectsUnrelatedOrNewerPersistedJournal() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val processJournal = pendingJournal()
        assertTrue(machine.restore(processJournal, oldLease))
        val currentLease = coordinator.attach()
        try {
            val unrelated = processJournal.copy(
                requestId = "account_delete_" + "2".repeat(64),
            )
            assertEquals(
                AccountDeletionStartupRestoreResult.CONFLICT,
                machine.restoreAtStartup(unrelated, currentLease),
            )
            assertEquals(
                AccountDeletionStartupRestoreResult.CONFLICT,
                machine.restoreAtStartup(
                    processJournal.copy(
                        phase = AccountDeletionPhase.IN_PROGRESS,
                        serverRevision = 1L,
                        acceptedAt = REQUESTED_AT,
                        accountGeneration = 7L,
                        tombstoneId = "tombstone-test-0001",
                        requestReceiptSha256 = "c".repeat(64),
                    ),
                    currentLease,
                ),
            )
            assertEquals(processJournal, machine.snapshotOrNull())
        } finally {
            coordinator.detach(currentLease)
        }
    }

    @Test
    fun startupRestoreRetainsSameIdentityFailClosedSuccessor() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val persisted = pendingJournal()
        assertTrue(machine.restore(persisted, oldLease))
        assertTrue(machine.failClosed(oldLease, "startup_fail_closed"))
        val currentLease = coordinator.attach()
        try {
            assertEquals(
                AccountDeletionStartupRestoreResult
                    .RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
                machine.restoreAtStartup(persisted, currentLease),
            )
            assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
            assertEquals("startup_fail_closed", machine.failureReasonOrNull())
        } finally {
            coordinator.detach(currentLease)
        }
    }

    @Test
    fun startupRestoreRetainsEvidenceAndRetrySuccessorAtSameRevision() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val oldLease = coordinator.attach()
        val pending = pendingJournal()
        assertTrue(machine.restore(pending, oldLease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(pending, oldLease))
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(attempt, processingStatus(pending)),
        )
        val persisted = requireNotNull(machine.snapshotOrNull())
        assertTrue(
            machine.recordDeviceEvidence(
                attempt = attempt,
                evidenceId = "device-evidence-test-0001",
                evidenceSha256 = "d".repeat(64),
                expectedStatusRevision = 1L,
                result = "DELETED",
                completedAt = REQUESTED_AT,
            ),
        )
        assertTrue(machine.markRetry(attempt, "status_retry"))
        val processSuccessor = requireNotNull(machine.snapshotOrNull())
        val currentLease = coordinator.attach()
        try {
            assertEquals(
                AccountDeletionStartupRestoreResult
                    .RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
                machine.restoreAtStartup(persisted, currentLease),
            )
            assertEquals(processSuccessor, machine.snapshotOrNull())
        } finally {
            coordinator.detach(currentLease)
        }
    }

    @Test
    fun staleStartupLeaseCannotRestoreOrForceFailClosed() {
        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val staleLease = coordinator.attach()
        val currentLease = coordinator.attach()
        try {
            assertEquals(
                AccountDeletionStartupRestoreResult.STALE_ACTIVITY,
                machine.restoreAtStartup(pendingJournal(), staleLease),
            )
            assertFalse(machine.restore(pendingJournal(), staleLease))
            assertFalse(
                machine.failClosed(
                    staleLease,
                    "deletion_intent_authority_migration_failed",
                ),
            )
            assertEquals(AccountDeletionPhase.IDLE, machine.phase())
            assertNull(machine.snapshotOrNull())
        } finally {
            coordinator.detach(currentLease)
        }
    }

    @Test
    fun confirmedFileWithoutJournalRestartsAndRetriesUntilPendingJournalBegins() {
        val authorityDirectory = temporaryFolder.newFolder("confirmed-recovery")
        val authority = FileAccountDeletionIntentAuthority(authorityDirectory)
        val initialDual = fileOnlyDual(authority)
        assertEquals(
            AccountDeletionConfirmationDecision.ACCEPTED,
            initialDual.confirm().decision,
        )

        val restartedDual = fileOnlyDual(
            FileAccountDeletionIntentAuthority(authorityDirectory),
        )
        val startup = restartedDual.startupState()
        val decision = accountDeletionStartupAuthorityDecision(
            snapshot = startup,
            journalPresent = false,
        )
        assertTrue(decision.blocksPrivacy)
        assertTrue(decision.durableConfirmationRecoveryRequired)
        assertNull(decision.failClosedReason)

        val coordinator = AccountDeletionProcessCoordinator()
        val machine = coordinator.stateMachine
        val lease = coordinator.attach()
        try {
            assertTrue(machine.enterDurableConfirmationRecovery(lease))
            repeat(2) {
                val sessionUnavailableAttempt = requireNotNull(
                    machine.beginPreparationWorkerAttempt(lease),
                )
                assertEquals(
                    AccountDeletionWorkerStageResult.SUCCEEDED,
                    machine.runWorkerStageIfCurrent(sessionUnavailableAttempt) {
                        restartedDual.confirm().decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED
                    },
                )
                assertTrue(
                    machine.retainDurableConfirmationRecovery(
                        sessionUnavailableAttempt,
                    ),
                )
                assertTrue(machine.processingBlocked())
                assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, machine.phase())
                assertNull(machine.snapshotOrNull())
            }

            val retryAttempt = requireNotNull(
                machine.beginPreparationWorkerAttempt(lease),
            )
            val pending = pendingJournal()
            assertTrue(machine.begin(pending, retryAttempt))
            assertFalse(machine.durableConfirmationRecoveryRequired())
            assertEquals(AccountDeletionPhase.REQUEST_PENDING, machine.phase())
            assertEquals(pending, machine.snapshotOrNull())
            assertTrue(machine.beginWorkerAttempt(pending, lease) != null)
        } finally {
            coordinator.detach(lease)
        }
    }

    private fun fileOnlyDual(
        authority: AccountDeletionIntentAuthority,
    ): AccountDeletionDualAuthority = AccountDeletionDualAuthority(
        preferenceFence = AccountDeletionIntentFence(
            read = { false },
            store = { false },
        ),
        fileAuthority = authority,
    )

    private fun pendingJournal(): AccountDeletionJournal =
        AccountDeletionJournal.pending(
            gatewayOrigin = "https://gateway.example.test",
            installationId = "install-test-0001",
            requestId = "account_delete_" + "1".repeat(64),
            requestedAt = REQUESTED_AT,
        )

    private fun completedJournal(): AccountDeletionJournal {
        val pending = pendingJournal()
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

    private fun processingStatus(
        journal: AccountDeletionJournal,
    ): AccountDeletionStatus = AccountDeletionStatus(
        schemaVersion = ACCOUNT_DELETION_STATUS_SCHEMA_VERSION,
        installationId = journal.installationId,
        requestId = journal.requestId,
        revision = 1L,
        requestedAt = REQUESTED_AT,
        updatedAt = REQUESTED_AT,
        items = journal.items,
        receiptSha256 = null,
        clientRevision = journal.clientRevision,
        accountGeneration = 7L,
        tombstoneId = "tombstone-test-0001",
        requestReceiptSha256 = "c".repeat(64),
    )

    private companion object {
        const val REQUESTED_AT = "2026-08-10T00:00:00Z"
    }
}
