package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentLocalWithdrawalRegrantRegressionTest {
    @Test
    fun pendingFreeLocalWithdrawalRequiresExactDenialBeforeANewGrant() {
        val session = IntegratedConsentSession()
        val original = initialConfirmation()
        assertTrue(session.accept(original))
        assertTrue(session.isAllowed(raw))
        assertTrue(session.withdrawImmediately(raw))
        assertNull(session.pendingMutationOrNull())
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_PENDING, session.status(raw))
        val withdrawn = publicState(session)

        val prematureGrant = mutation(
            previous = original,
            requestId = "local_withdrawal_regrant_0002",
            clientRevision = 2L,
            desiredSelections = original.selections,
        )
        assertTrue(prematureGrant.withdrawalItems.isEmpty())
        assertFalse(session.restorePendingMutation(prematureGrant, retry = true))
        assertEquals(withdrawn, publicState(session))

        val prematureResponse = original.copy(
            requestId = prematureGrant.requestId,
            clientRevision = prematureGrant.clientRevision,
            revision = 2L,
            selections = prematureGrant.desiredSelections,
            backendConsentReceiptSha256 = "2".repeat(64),
        )
        assertTrue(prematureGrant.isExactNewerConfirmation(prematureResponse))
        assertTrue(prematureResponse.revision > original.revision)
        assertEquals(
            IntegratedConsentApplyResult.NOT_EXACT,
            session.evaluateExactPendingConfirmation(prematureResponse),
        )
        assertFalse(session.applyExactPendingConfirmation(prematureResponse))
        assertEquals(withdrawn, publicState(session))

        val denial = mutation(
            previous = original,
            requestId = "local_withdrawal_denial_0002",
            clientRevision = 2L,
            desiredSelections = original.selections.copy(rawSourceCollection = false),
            withdrawalItems = setOf(raw),
        )
        assertTrue(session.restorePendingMutation(denial, retry = true))
        assertEquals(denial, session.pendingMutationOrNull())
        assertEquals(original, session.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(raw))
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))

        val denialResponse = original.copy(
            requestId = denial.requestId,
            clientRevision = denial.clientRevision,
            revision = 2L,
            selections = denial.desiredSelections,
            backendConsentReceiptSha256 = "2".repeat(64),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(denialResponse),
        )
        assertTrue(session.applyExactPendingConfirmation(denialResponse))
        assertNull(session.pendingMutationOrNull())
        assertEquals(denialResponse, session.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, session.status(raw))
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))

        val nextGrant = mutation(
            previous = denialResponse,
            requestId = "local_withdrawal_regrant_0003",
            clientRevision = 3L,
            desiredSelections = denialResponse.selections.copy(rawSourceCollection = true),
        )
        assertEquals(denialResponse.revision, nextGrant.previousServerRevision)
        assertEquals(
            denialResponse.backendConsentReceiptSha256,
            nextGrant.expectedPreviousBackendReceiptSha256,
        )
        assertTrue(session.restorePendingMutation(nextGrant))
        assertEquals(PurposeConsentSyncState.GRANT_PENDING, session.status(raw))
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))

        val grantedResponse = denialResponse.copy(
            requestId = nextGrant.requestId,
            clientRevision = nextGrant.clientRevision,
            revision = 3L,
            selections = nextGrant.desiredSelections,
            backendConsentReceiptSha256 = "3".repeat(64),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(grantedResponse),
        )
        assertTrue(session.applyExactPendingConfirmation(grantedResponse))
        assertNull(session.pendingMutationOrNull())
        assertEquals(grantedResponse, session.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, session.status(raw))
        assertTrue(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))
    }

    @Test
    fun identicalPendingRetrySurvivesLocalWithdrawalAndUnfencedHigherGrantsStillComplete() {
        val session = IntegratedConsentSession()
        val original = initialConfirmation()
        val existing = mutation(
            previous = original,
            requestId = "local_withdrawal_existing_grant_0002",
            clientRevision = 2L,
            desiredSelections = original.selections.copy(trainingReuse = true),
        )
        assertTrue(existing.withdrawalItems.isEmpty())
        assertTrue(session.accept(original))
        assertTrue(session.restorePendingMutation(existing))
        assertTrue(session.withdrawImmediately(raw))
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_PENDING, session.status(raw))
        assertEquals(PurposeConsentSyncState.GRANT_PENDING, session.status(training))
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))

        val beforeCopy = publicState(session)
        assertFalse(session.restorePendingMutation(existing.copy()))
        assertEquals(beforeCopy, publicState(session))
        assertTrue(session.restorePendingMutation(existing.copy(), retry = true))
        assertEquals(existing, session.pendingMutationOrNull())
        assertEquals(original, session.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(raw))
        val retrying = publicState(session)
        assertFalse(session.restorePendingMutation(existing.copy(), retry = true))
        assertEquals(retrying, publicState(session))
        assertTrue(session.restorePendingMutation(existing.copy(), retry = false))
        assertEquals(beforeCopy, publicState(session))
        assertTrue(session.restorePendingMutation(existing.copy(), retry = true))
        assertEquals(retrying, publicState(session))

        val higher = existing.copy(
            requestId = "local_withdrawal_higher_grant_0003",
            clientRevision = 3L,
            createdAtEpochMs = 2_000L,
        )
        assertFalse(session.restorePendingMutation(higher, retry = false))
        assertEquals(retrying, publicState(session))
        val higherResponse = original.copy(
            requestId = higher.requestId,
            clientRevision = higher.clientRevision,
            revision = 2L,
            selections = higher.desiredSelections,
            backendConsentReceiptSha256 = "3".repeat(64),
        )
        assertTrue(higher.isExactNewerConfirmation(higherResponse))
        assertTrue(higherResponse.revision > original.revision)
        assertEquals(
            IntegratedConsentApplyResult.NOT_EXACT,
            session.evaluateExactPendingConfirmation(higherResponse),
        )
        assertFalse(session.applyExactPendingConfirmation(higherResponse))
        assertEquals(retrying, publicState(session))

        // The already-owned request can finish, but cannot erase the later local withdrawal.
        val existingResponse = original.copy(
            requestId = existing.requestId,
            clientRevision = existing.clientRevision,
            revision = 2L,
            selections = existing.desiredSelections,
            backendConsentReceiptSha256 = "2".repeat(64),
        )
        assertTrue(session.applyExactPendingConfirmation(existingResponse))
        assertNull(session.pendingMutationOrNull())
        assertEquals(existingResponse, session.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_PENDING, session.status(raw))
        assertFalse(session.isAllowed(raw))
        assertTrue(session.isAllowed(automatic))
        assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, session.status(training))
        assertTrue(session.isAllowed(training))

        // Separate public session: without a local withdrawal, higher replacement remains valid.
        val unfenced = IntegratedConsentSession()
        assertTrue(unfenced.accept(original))
        assertTrue(unfenced.restorePendingMutation(existing))
        assertTrue(unfenced.restorePendingMutation(higher))
        assertEquals(higher, unfenced.pendingMutationOrNull())
        assertEquals(original, unfenced.currentConfirmationOrNull())
        assertTrue(unfenced.isAllowed(raw))
        assertTrue(unfenced.isAllowed(automatic))
        assertEquals(PurposeConsentSyncState.GRANT_PENDING, unfenced.status(training))
        assertFalse(unfenced.isAllowed(training))
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            unfenced.evaluateExactPendingConfirmation(higherResponse),
        )
        assertTrue(unfenced.applyExactPendingConfirmation(higherResponse))
        assertNull(unfenced.pendingMutationOrNull())
        assertEquals(higherResponse, unfenced.currentConfirmationOrNull())
        assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, unfenced.status(raw))
        assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, unfenced.status(training))
        assertTrue(unfenced.isAllowed(raw))
        assertTrue(unfenced.isAllowed(automatic))
        assertTrue(unfenced.isAllowed(training))
    }

    private val raw = IntegratedConsentItem.RAW_SOURCE_COLLECTION
    private val automatic = IntegratedConsentItem.AUTOMATIC_REPORTING
    private val training = IntegratedConsentItem.TRAINING_REUSE

    private fun initialConfirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "local_withdrawal_initial_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(
            rawSourceCollection = true,
            automaticReporting = true,
        ),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "1".repeat(64),
        controlSecret = "b".repeat(64),
    )

    private fun mutation(
        previous: IntegratedConsentConfirmation,
        requestId: String,
        clientRevision: Long,
        desiredSelections: IntegratedConsentSelections,
        withdrawalItems: Set<IntegratedConsentItem> = emptySet(),
    ) = PendingIntegratedConsentMutation(
        installationId = previous.installationId,
        actorSha256 = "c".repeat(64),
        requestId = requestId,
        policyVersion = previous.policyVersion,
        clientRevision = clientRevision,
        previousServerRevision = previous.revision,
        expectedPreviousBackendReceiptSha256 = previous.backendConsentReceiptSha256,
        desiredSelections = desiredSelections,
        withdrawalItems = withdrawalItems,
        createdAtEpochMs = 1_000L,
    )

    private data class PublicState(
        val current: IntegratedConsentConfirmation?,
        val pending: PendingIntegratedConsentMutation?,
        val statuses: Map<IntegratedConsentItem, PurposeConsentSyncState>,
        val grants: Map<IntegratedConsentItem, Boolean>,
    )

    /** Capture public observations only; no consent or revision decisions are reproduced. */
    private fun publicState(session: IntegratedConsentSession) = PublicState(
        current = session.currentConfirmationOrNull(),
        pending = session.pendingMutationOrNull(),
        statuses = IntegratedConsentItem.entries.associateWith { session.status(it) },
        grants = IntegratedConsentItem.entries.associateWith { session.isAllowed(it) },
    )
}
