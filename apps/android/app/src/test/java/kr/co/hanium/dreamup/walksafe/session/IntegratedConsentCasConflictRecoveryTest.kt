package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact previous-receipt conflicts must preserve a pending withdrawal while rebasing it. */
class IntegratedConsentCasConflictRecoveryTest {
    @Test
    fun rejectedRequestCanBeReplacedByANonexpandingWithdrawalRequest() {
        val session = IntegratedConsentSession()
        val old = confirmation()
        assertTrue(session.accept(old))
        val rejected = mutation(
            clientRevision = 2L,
            receipt = old.backendConsentReceiptSha256,
            selections = old.selections.copy(rawSourceCollection = false, trainingReuse = true),
        )
        assertTrue(session.restorePendingMutation(rejected, retry = true))
        session.invalidateBackendAuthority()
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))

        // Another installation withdrew automatic reporting and changed the receipt. Its
        // choices are the authority; the rejected request's training grant must not return.
        val latest = old.selections.copy(automaticReporting = false)
        val successor = rejected.successorAfterRejectedCas(
            backendSelections = latest,
            backendReceiptSha256 = "c".repeat(64),
            backendClientRevisionFloor = 1L,
            clientRevision = 2L,
            withdrawalItems = rejected.withdrawalItems,
            createdAtEpochMs = 2L,
        )
        val result = session.supersedeRejectedCasMutation(
            supersession = RejectedConsentCasSupersession(
                original = rejected,
                backendSelections = latest,
                backendReceiptSha256 = "c".repeat(64),
                backendClientRevisionFloor = 1L,
                successor = successor,
            ),
            persist = { true },
        )
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, result)
        assertEquals(successor, session.pendingMutationOrNull())
        assertEquals(rejected.clientRevision, successor.clientRevision)
        assertFalse(successor.desiredSelections.automaticReporting)
        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun failedDurableReplacementKeepsTheOriginalRequestAndWithdrawal() {
        val session = IntegratedConsentSession()
        val old = confirmation()
        assertTrue(session.accept(old))
        val rejected = mutation(
            clientRevision = 2L,
            receipt = old.backendConsentReceiptSha256,
            selections = old.selections.copy(automaticReporting = false),
            withdrawalItems = setOf(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.restorePendingMutation(rejected, retry = true))
        session.invalidateBackendAuthority()
        val latest = old.selections.copy(rawSourceCollection = false)
        val successor = rejected.successorAfterRejectedCas(
            backendSelections = latest,
            backendReceiptSha256 = "c".repeat(64),
            backendClientRevisionFloor = 1L,
            clientRevision = 2L,
            withdrawalItems = rejected.withdrawalItems,
            createdAtEpochMs = 2L,
        )
        val transition = RejectedConsentCasSupersession(
            rejected, latest, "c".repeat(64), 1L, successor,
        )
        assertEquals(
            IntegratedConsentSubmissionResult.STORAGE_FAILED,
            session.supersedeRejectedCasMutation(transition) { false },
        )
        assertEquals(rejected, session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertFalse(successor.desiredSelections.rawSourceCollection)
        assertFalse(successor.desiredSelections.automaticReporting)
    }

    @Test
    fun secondConflictKeepsEveryEarlierWithdrawalAndNeverCopiesAStaleGrant() {
        val session = IntegratedConsentSession()
        val old = confirmation()
        assertTrue(session.accept(old))
        val firstRejected = mutation(
            clientRevision = 2L,
            receipt = old.backendConsentReceiptSha256,
            selections = old.selections.copy(automaticReporting = false, trainingReuse = true),
            withdrawalItems = setOf(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.restorePendingMutation(firstRejected, retry = true))
        session.invalidateBackendAuthority()
        val firstBackendChoices = old.selections.copy(rawSourceCollection = false)
        val firstSuccessor = firstRejected.successorAfterRejectedCas(
            firstBackendChoices, "c".repeat(64), 1L, 2L,
            firstRejected.withdrawalItems, 2L,
        )
        assertEquals(IntegratedConsentSubmissionResult.APPLIED,
            session.supersedeRejectedCasMutation(RejectedConsentCasSupersession(
                firstRejected, firstBackendChoices, "c".repeat(64), 1L, firstSuccessor,
            )) { true })
        val secondBackendChoices = firstBackendChoices.copy(mobileNetworkTransfer = false)
        val secondSuccessor = firstSuccessor.successorAfterRejectedCas(
            secondBackendChoices, "e".repeat(64), 1L, 2L,
            firstSuccessor.withdrawalItems, 3L,
        )
        assertEquals(IntegratedConsentSubmissionResult.APPLIED,
            session.supersedeRejectedCasMutation(RejectedConsentCasSupersession(
                firstSuccessor, secondBackendChoices, "e".repeat(64), 1L, secondSuccessor,
            )) { true })
        assertEquals(secondSuccessor, session.pendingMutationOrNull())
        assertFalse(secondSuccessor.desiredSelections.rawSourceCollection)
        assertFalse(secondSuccessor.desiredSelections.automaticReporting)
        assertFalse(secondSuccessor.desiredSelections.mobileNetworkTransfer)
        assertFalse(secondSuccessor.desiredSelections.trainingReuse)
    }

    @Test
    fun anExistingFalseChoiceCannotBecomeGrantedWhenAnotherInstallationTurnsItOn() {
        val old = confirmation().copy(selections = IntegratedConsentSelections(
            rawSourceCollection = false,
            automaticReporting = false,
            mobileNetworkTransfer = false,
            trainingReuse = false,
        ))
        val rejected = mutation(
            clientRevision = 2L,
            receipt = old.backendConsentReceiptSha256,
            selections = old.selections.copy(mobileNetworkTransfer = true),
            withdrawalItems = emptySet(),
        )
        val latest = old.selections.copy(rawSourceCollection = true)
        val successor = rejected.successorAfterRejectedCas(
            backendSelections = latest,
            backendReceiptSha256 = "c".repeat(64),
            backendClientRevisionFloor = 1L,
            clientRevision = 2L,
            withdrawalItems = rejected.withdrawalItems,
            createdAtEpochMs = 2L,
        )
        assertFalse(successor.desiredSelections.rawSourceCollection)
        assertFalse(successor.desiredSelections.automaticReporting)
        assertFalse(successor.desiredSelections.trainingReuse)
    }

    @Test
    fun aNonExactServerConfirmationCannotOpenAChoiceDeniedByThePendingRequest() {
        val session = IntegratedConsentSession()
        val old = confirmation().copy(selections = IntegratedConsentSelections())
        assertTrue(session.accept(old))
        val pending = mutation(
            clientRevision = 2L,
            receipt = old.backendConsentReceiptSha256,
            selections = old.selections.copy(mobileNetworkTransfer = true),
            withdrawalItems = emptySet(),
        )
        assertTrue(session.restorePendingMutation(pending, retry = true))
        val differentServerConfirmation = old.copy(
            requestId = "integrated_consent_request_0002",
            clientRevision = 2L,
            revision = 2L,
            selections = old.selections.copy(rawSourceCollection = true),
            backendConsentReceiptSha256 = "c".repeat(64),
        )
        assertEquals(IntegratedConsentApplyResult.APPLIED,
            session.applyConfirmation(differentServerConfirmation))
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    private fun confirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(
            rawSourceCollection = true,
            automaticReporting = true,
            mobileNetworkTransfer = true,
            trainingReuse = false,
        ),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )

    private fun mutation(
        clientRevision: Long,
        receipt: String,
        selections: IntegratedConsentSelections,
        withdrawalItems: Set<IntegratedConsentItem> =
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
    ) = PendingIntegratedConsentMutation(
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        actorSha256 = "d".repeat(64),
        requestId = "integrated_consent_request_000$clientRevision",
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        clientRevision = clientRevision,
        previousServerRevision = 1L,
        expectedPreviousBackendReceiptSha256 = receipt,
        desiredSelections = selections,
        withdrawalItems = withdrawalItems,
        createdAtEpochMs = 1L,
    )
}
