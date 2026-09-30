package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentPendingMutationOwnershipRegressionTest {
    @Test
    fun clearingCallerWithdrawalSetCannotOrphanTheFenceAfterExactDenial() {
        val session = IntegratedConsentSession()
        val initial = grantedConfirmation()
        assertTrue(session.accept(initial))
        val callerItems = mutableSetOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)
        val supplied = withdrawalMutation(initial, callerItems)
        val expected = supplied.copy(
            withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        assertTrue(session.restorePendingMutation(supplied))
        callerItems.clear()

        assertTrue(callerItems.isEmpty())
        assertWithdrawalState(
            session,
            initial,
            expected,
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
        )

        val denial = initial.copy(
            requestId = expected.requestId,
            clientRevision = expected.clientRevision,
            revision = 2L,
            selections = expected.desiredSelections,
            backendConsentReceiptSha256 = "2".repeat(64),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(denial),
        )
        assertTrue(session.applyExactPendingConfirmation(denial))
        assertNull(session.pendingMutationOrNull())
        assertEquals(denial, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

        val regrant = expected.copy(
            requestId = "pending_ownership_regrant_0003",
            clientRevision = 3L,
            previousServerRevision = denial.revision,
            expectedPreviousBackendReceiptSha256 = denial.backendConsentReceiptSha256,
            desiredSelections = denial.selections.copy(rawSourceCollection = true),
            withdrawalItems = emptySet(),
            createdAtEpochMs = 2_000L,
        )
        assertTrue(session.restorePendingMutation(regrant))
        assertEquals(regrant, session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.GRANT_PENDING,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

        val grant = denial.copy(
            requestId = regrant.requestId,
            clientRevision = regrant.clientRevision,
            revision = 3L,
            selections = regrant.desiredSelections,
            backendConsentReceiptSha256 = "3".repeat(64),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(grant),
        )
        assertTrue(session.applyExactPendingConfirmation(grant))
        assertNull(session.pendingMutationOrNull())
        assertEquals(grant, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun publicPendingCollectionRejectsMutationWithoutChangingCopyOrRetryContracts() {
        val session = IntegratedConsentSession()
        val initial = grantedConfirmation()
        assertTrue(session.accept(initial))
        val pending = withdrawalMutation(
            initial,
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertTrue(session.restorePendingMutation(pending))
        val retained = session.pendingMutationOrNull()!!

        assertThrows(UnsupportedOperationException::class.java) {
            (retained.withdrawalItems as MutableSet<IntegratedConsentItem>).clear()
        }
        assertWithdrawalState(
            session,
            initial,
            pending,
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
        )
        assertFalse(session.restorePendingMutation(retained.copy(), retry = false))
        assertWithdrawalState(
            session,
            initial,
            pending,
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
        )

        assertTrue(session.restorePendingMutation(retained.copy(), retry = true))
        assertWithdrawalState(
            session,
            initial,
            pending,
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
        )
        assertFalse(session.restorePendingMutation(retained.copy(), retry = true))
        assertWithdrawalState(
            session,
            initial,
            pending,
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
        )
        assertTrue(session.restorePendingMutation(retained.copy(), retry = false))
        assertWithdrawalState(
            session,
            initial,
            pending,
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
        )

        val denial = initial.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
            backendConsentReceiptSha256 = "2".repeat(64),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(denial),
        )
        assertTrue(session.applyExactPendingConfirmation(denial))
        assertNull(session.pendingMutationOrNull())
        assertEquals(denial, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    private fun grantedConfirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "pending_ownership_initial_0001",
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

    private fun withdrawalMutation(
        previous: IntegratedConsentConfirmation,
        withdrawalItems: Set<IntegratedConsentItem>,
    ) = PendingIntegratedConsentMutation(
        installationId = previous.installationId,
        actorSha256 = "c".repeat(64),
        requestId = "pending_ownership_denial_0002",
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        clientRevision = 2L,
        previousServerRevision = previous.revision,
        expectedPreviousBackendReceiptSha256 = previous.backendConsentReceiptSha256,
        desiredSelections = previous.selections.copy(rawSourceCollection = false),
        withdrawalItems = withdrawalItems,
        createdAtEpochMs = 1_000L,
    )

    private fun assertWithdrawalState(
        session: IntegratedConsentSession,
        expectedCurrent: IntegratedConsentConfirmation,
        expectedPending: PendingIntegratedConsentMutation,
        expectedStatus: PurposeConsentSyncState,
    ) {
        assertEquals(expectedCurrent, session.currentConfirmationOrNull())
        assertEquals(expectedPending, session.pendingMutationOrNull())
        assertEquals(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            session.pendingMutationOrNull()!!.withdrawalItems,
        )
        assertEquals(
            expectedStatus,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }
}
