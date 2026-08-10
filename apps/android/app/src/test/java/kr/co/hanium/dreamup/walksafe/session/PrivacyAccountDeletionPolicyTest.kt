package kr.co.hanium.dreamup.walksafe.session

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAccountDeletionPolicyTest {
    @Test
    fun allNineItemsAndPolicySlaAreRequired() {
        val status = status(
            revision = 1L,
            states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.IN_PROGRESS
            },
        )

        assertEquals(9, status.items.size)
        assertEquals(AccountDeletionPhase.IN_PROGRESS, status.derivedPhase())
        assertThrows(IllegalArgumentException::class.java) {
            status.copy(items = status.items - DeletionInventoryItem.BACKUP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = status.items.toMutableMap()
            invalid[DeletionInventoryItem.DEVICE_UNSENT] =
                invalid.getValue(DeletionInventoryItem.DEVICE_UNSENT).copy(
                    dueAt = REQUESTED_AT.plusSeconds(24L * 60L * 60L + 1L).toString(),
                )
            status.copy(items = invalid)
        }
    }

    @Test
    fun holdFailureAndRetryNeverDeriveCompleted() {
        listOf(
            DeletionItemState.LEGAL_HOLD to AccountDeletionPhase.RESTRICTED,
            DeletionItemState.FAILED to AccountDeletionPhase.PARTIAL_FAILURE,
            DeletionItemState.RETRY_WAIT to AccountDeletionPhase.RETRY_WAIT,
        ).forEach { (state, expectedPhase) ->
            val states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.COMPLETED
            }.toMutableMap()
            states[DeletionInventoryItem.BACKUP] = state
            assertEquals(
                expectedPhase,
                status(revision = 1L, states = states).derivedPhase(),
            )
        }
    }

    @Test
    fun completionNeedsEveryItemAndReceipt() {
        val states = DeletionInventoryItem.entries.associateWith {
            DeletionItemState.NOT_APPLICABLE
        }.toMutableMap()
        states[DeletionInventoryItem.DEVICE_UNSENT] = DeletionItemState.COMPLETED

        val complete = status(1L, states, receipt = "c".repeat(64))

        assertEquals(AccountDeletionPhase.COMPLETED, complete.derivedPhase())
        assertThrows(IllegalArgumentException::class.java) {
            complete.copy(receiptSha256 = null)
        }
    }

    @Test
    fun revisionAndIdempotencyCannotMoveBackwardOrConflict() {
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal()))
        val first = status(
            revision = 2L,
            states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.IN_PROGRESS
            },
        )

        assertEquals(AccountDeletionApplyResult.APPLIED, machine.apply(first))
        assertEquals(AccountDeletionApplyResult.DUPLICATE, machine.apply(first))
        assertEquals(
            AccountDeletionApplyResult.STALE_IGNORED,
            machine.apply(first.copy(revision = 1L)),
        )

        val conflictItems = first.items.toMutableMap()
        conflictItems[DeletionInventoryItem.BACKUP] =
            conflictItems.getValue(DeletionInventoryItem.BACKUP).copy(
                state = DeletionItemState.PENDING,
            )
        assertEquals(
            AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
            machine.apply(first.copy(items = conflictItems)),
        )
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, machine.phase())
        assertFalse(machine.resetForNewEnrollment().not())
    }

    @Test
    fun oneInstallationAckDoesNotCompleteAggregateAndAggregateEvidenceMayDiffer() {
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal()))
        val processing = status(
            revision = 1L,
            states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.IN_PROGRESS
            },
        )
        assertEquals(AccountDeletionApplyResult.APPLIED, machine.apply(processing))
        assertTrue(
            machine.recordDeviceEvidence(
                evidenceId = "device-evidence-0001",
                evidenceSha256 = "d".repeat(64),
                expectedStatusRevision = 1L,
                result = "DELETED",
                completedAt = REQUESTED_AT.plusSeconds(20L).toString(),
            ),
        )

        val firstDeviceResponse = processing.copy(
            revision = 2L,
            updatedAt = REQUESTED_AT.plusSeconds(20L).toString(),
        )
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(
                firstDeviceResponse,
                acknowledgesDeviceEvidence = true,
            ),
        )
        assertEquals(AccountDeletionPhase.IN_PROGRESS, machine.phase())
        assertTrue(requireNotNull(machine.snapshotOrNull()).deviceEvidenceAcknowledged)

        val aggregateComplete = status(
            revision = 3L,
            states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.COMPLETED
            },
            receipt = "c".repeat(64),
        )
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(aggregateComplete),
        )
        val completed = requireNotNull(machine.snapshotOrNull())
        assertEquals(AccountDeletionPhase.COMPLETED, completed.phase)
        assertTrue(isConfirmedTerminalAccountDeletion(completed))
        assertEquals("d".repeat(64), completed.deviceEvidenceSha256)
        assertEquals(
            "e".repeat(64),
            completed.items.getValue(DeletionInventoryItem.DEVICE_UNSENT)
                .evidenceSha256,
        )
    }

    @Test
    fun revisionConflictReissuesEvidenceWithNewIdentityAndSameDeletionFact() {
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal()))
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(
                status(
                    revision = 1L,
                    states = DeletionInventoryItem.entries.associateWith {
                        DeletionItemState.IN_PROGRESS
                    },
                ),
            ),
        )
        val completedAt = "2026-07-25T12:05:00Z"
        assertTrue(
            machine.recordDeviceEvidence(
                evidenceId = "device-evidence-0001",
                evidenceSha256 = "d".repeat(64),
                expectedStatusRevision = 1L,
                result = "DELETED",
                completedAt = completedAt,
            ),
        )
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(
                status(
                    revision = 2L,
                    states = DeletionInventoryItem.entries.associateWith {
                        DeletionItemState.IN_PROGRESS
                    },
                ),
            ),
        )

        assertTrue(
            machine.rebaseDeviceEvidence(
                evidenceId = "device-evidence-0002",
                evidenceSha256 = "e".repeat(64),
                expectedStatusRevision = 2L,
                result = "DELETED",
                completedAt = completedAt,
            ),
        )
        val rebased = requireNotNull(machine.snapshotOrNull())
        assertEquals("device-evidence-0002", rebased.deviceEvidenceId)
        assertEquals(2L, rebased.deviceEvidenceExpectedStatusRevision)
        assertEquals("DELETED", rebased.deviceEvidenceResult)
        assertEquals(completedAt, rebased.deviceEvidenceCompletedAt)
        assertFalse(rebased.deviceEvidenceAcknowledged)
        assertFalse(
            machine.rebaseDeviceEvidence(
                evidenceId = "device-evidence-0003",
                evidenceSha256 = "f".repeat(64),
                expectedStatusRevision = 3L,
                result = "NOT_FOUND",
                completedAt = completedAt,
            ),
        )
    }

    @Test
    fun deviceEvidenceRejectsNormalizedButNonCanonicalUtcInstants() {
        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal()))
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            machine.apply(
                status(
                    revision = 1L,
                    states = DeletionInventoryItem.entries.associateWith {
                        DeletionItemState.IN_PROGRESS
                    },
                ),
            ),
        )

        listOf(
            "2026-02-31T12:05:00Z",
            "2026-07-25T24:00:00Z",
            "2026-07-25T23:59:60Z",
        ).forEach { invalid ->
            assertFalse(
                machine.recordDeviceEvidence(
                    evidenceId = "device-evidence-0001",
                    evidenceSha256 = "d".repeat(64),
                    expectedStatusRevision = 1L,
                    result = "DELETED",
                    completedAt = invalid,
                ),
            )
        }
    }

    @Test
    fun alreadyAbsentMarkerRestartKeepsCompletedAndBlocksResetUntilDurableCleanup() {
        val original = AccountDeletionStateMachine()
        assertTrue(original.requestConfirmation())
        assertTrue(original.begin(journal()))
        val processing = status(
            revision = 1L,
            states = DeletionInventoryItem.entries.associateWith {
                DeletionItemState.IN_PROGRESS
            },
        )
        assertEquals(AccountDeletionApplyResult.APPLIED, original.apply(processing))
        assertTrue(
            original.recordDeviceEvidence(
                evidenceId = "device-evidence-0001",
                evidenceSha256 = "d".repeat(64),
                expectedStatusRevision = 1L,
                result = "DELETED",
                completedAt = REQUESTED_AT.plusSeconds(5L).toString(),
            ),
        )
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            original.apply(
                processing.copy(
                    revision = 2L,
                    updatedAt = REQUESTED_AT.plusSeconds(6L).toString(),
                ),
                acknowledgesDeviceEvidence = true,
            ),
        )
        assertEquals(
            AccountDeletionApplyResult.APPLIED,
            original.apply(
                status(
                    revision = 3L,
                    states = DeletionInventoryItem.entries.associateWith {
                        DeletionItemState.COMPLETED
                    },
                    receipt = "c".repeat(64),
                ),
            ),
        )

        val restarted = AccountDeletionStateMachine()
        assertTrue(restarted.restore(requireNotNull(original.snapshotOrNull())))
        val completed = requireNotNull(restarted.snapshotOrNull())
        val cleanup = AccountDeletionTerminalCleanupCoordinator()

        val actorStillPresent = requireNotNull(cleanup.begin(completed))
        assertEquals(
            false,
            cleanup.complete(
                attempt = actorStillPresent,
                journal = completed,
                markerCleanup =
                    AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT,
                actorBindingAbsent = false,
            ),
        )
        assertEquals(AccountDeletionPhase.COMPLETED, restarted.phase())
        assertFalse(cleanup.isResetEnabled(completed))

        val cleanupAttempt = requireNotNull(cleanup.begin(completed))
        val executorEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupResult = AtomicReference<Boolean?>()
        val worker = thread(name = "terminal-cleanup-test") {
            executorEntered.countDown()
            assertTrue(releaseCleanup.await(2L, TimeUnit.SECONDS))
            cleanupResult.set(
                cleanup.complete(
                    attempt = cleanupAttempt,
                    journal = completed,
                    markerCleanup =
                        AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT,
                    actorBindingAbsent = true,
                ),
            )
        }
        assertTrue(executorEntered.await(2L, TimeUnit.SECONDS))
        assertEquals(AccountDeletionPhase.COMPLETED, restarted.phase())
        assertFalse(cleanup.isResetEnabled(completed))

        releaseCleanup.countDown()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        assertEquals(true, cleanupResult.get())
        assertEquals(AccountDeletionPhase.COMPLETED, restarted.phase())
        assertTrue(cleanup.isResetEnabled(completed))
    }

    private fun journal() = AccountDeletionJournal.pending(
        gatewayOrigin = "https://gateway.example.test",
        installationId = INSTALLATION_ID,
        requestId = REQUEST_ID,
        requestedAt = REQUESTED_AT.toString(),
    )

    private fun status(
        revision: Long,
        states: Map<DeletionInventoryItem, DeletionItemState>,
        receipt: String? = null,
    ): AccountDeletionStatus {
        val items = states.mapValues { (item, state) ->
            val terminal = state.isComplete
            DeletionItemStatus(
                item = item,
                state = state,
                itemRevision = revision,
                dueAt = REQUESTED_AT.plusMillis(item.maximumSlaMs).toString(),
                nextRetryAt =
                    if (state == DeletionItemState.RETRY_WAIT) {
                        REQUESTED_AT.plusSeconds(3_600L).toString()
                    } else {
                        null
                    },
                reasonCode =
                    if (state == DeletionItemState.LEGAL_HOLD) "legal_hold"
                    else null,
                legalHoldReviewAt =
                    if (state == DeletionItemState.LEGAL_HOLD) {
                        REQUESTED_AT.plusSeconds(3_600L).toString()
                    } else {
                        null
                    },
                contactUrl =
                    if (state == DeletionItemState.LEGAL_HOLD) {
                        "https://example.test/privacy"
                    } else {
                        null
                    },
                evidenceSha256 = if (terminal) "e".repeat(64) else null,
                dispositionBasis =
                    if (state == DeletionItemState.NOT_APPLICABLE) {
                        "no_matching_record"
                    } else {
                        null
                    },
                terminalAt =
                    if (terminal) REQUESTED_AT.plusSeconds(10L).toString()
                    else null,
            )
        }
        return AccountDeletionStatus(
            schemaVersion = ACCOUNT_DELETION_STATUS_SCHEMA_VERSION,
            installationId = INSTALLATION_ID,
            requestId = REQUEST_ID,
            revision = revision,
            requestedAt = REQUESTED_AT.toString(),
            updatedAt = REQUESTED_AT.plusSeconds(10L).toString(),
            items = items,
            receiptSha256 = receipt,
        )
    }

    private companion object {
        const val INSTALLATION_ID = "501e3ad4-e74f-4433-820f-72ac2fdd42ad"
        const val REQUEST_ID = "account_delete_request_0001"
        val REQUESTED_AT: Instant = Instant.parse("2026-07-25T12:00:00Z")
    }
}
