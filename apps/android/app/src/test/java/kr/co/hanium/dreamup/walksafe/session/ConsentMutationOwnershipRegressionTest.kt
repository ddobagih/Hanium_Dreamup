package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.*
import org.junit.Test

class ConsentMutationOwnershipRegressionTest {
    @Test fun olderRestorationCannotReplacePendingWithdrawal() {
        val session = IntegratedConsentSession()
        val current = pending(3L)
        assertTrue(session.restorePendingMutation(current, retry = true))
        assertFalse(session.restorePendingMutation(pending(2L)))
        assertEquals(current, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun equalRevisionWithDifferentRequestFailsClosedAndPreservesPending() {
        val session = IntegratedConsentSession()
        val current = pending(3L)
        session.restorePendingMutation(current)
        assertFalse(session.restorePendingMutation(current.copy(requestId = "different_request_0003")))
        assertEquals(current, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun differentActorCannotReplacePendingWithinTheSameInstallation() {
        val session = IntegratedConsentSession()
        val current = pending(3L)
        session.restorePendingMutation(current)
        assertFalse(session.restorePendingMutation(pending(4L).copy(actorSha256 = "b".repeat(64))))
        assertEquals(current, session.pendingMutationOrNull())
    }

    @Test fun policyReconsentDoesNotEraseUndeliveredWithdrawal() {
        val session = IntegratedConsentSession()
        val current = pending(3L)
        session.restorePendingMutation(current, retry = true)
        assertFalse(session.resetForPolicyReconsent())
        assertEquals(current, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun exactAlreadyStoredResponseFinishesPendingWithoutRevisionConflict() {
        val session = IntegratedConsentSession()
        val mutation = pending(3L)
        val receipt = confirmation(mutation)
        session.restorePendingMutation(mutation)
        assertEquals(IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(receipt))
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.evaluateExactPendingConfirmation(receipt))
        assertTrue(session.applyExactPendingConfirmation(receipt))
        assertNull(session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun sameRevisionDifferentReceiptRemainsFailClosed() {
        val session = IntegratedConsentSession()
        val mutation = pending(3L)
        val receipt = confirmation(mutation)
        session.restorePendingMutation(mutation)
        session.applyConfirmation(receipt)
        assertFalse(session.applyExactPendingConfirmation(receipt.copy(backendConsentReceiptSha256 = "8".repeat(64))))
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    private fun pending(revision: Long) = PendingIntegratedConsentMutation(
        installationId = "install-test-0001", actorSha256 = "a".repeat(64),
        requestId = "consent_request_0000000$revision", policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        clientRevision = revision, previousServerRevision = 1L,
        expectedPreviousBackendReceiptSha256 = "1".repeat(64), desiredSelections = IntegratedConsentSelections(),
        withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION), createdAtEpochMs = 1L,
    )
    private fun confirmation(pending: PendingIntegratedConsentMutation) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = pending.policyVersion, installationId = pending.installationId,
        requestId = pending.requestId, itemVersions = IntegratedConsentItemVersions(),
        clientRevision = pending.clientRevision, revision = pending.clientRevision,
        selections = pending.desiredSelections, confirmedAt = "2026-09-17T00:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64), backendConsentReceiptSha256 = "3".repeat(64),
        controlSecret = "c".repeat(64),
    )
}
