package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentWithdrawalTest {
    @Test
    fun staleGrantAndGenericRefreshCannotReleaseRestoredWithdrawalFence() {
        val session = IntegratedConsentSession()
        session.accept(confirmation(revision = 1L, granted = true))
        val pending = mutation()
        session.restorePendingMutation(pending, retry = true)

        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        session.accept(
            confirmation(
                requestId = "generic_refresh_request_0001",
                clientRevision = 2L,
                revision = 2L,
                granted = true,
            ),
        )

        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(
            session.applyExactPendingConfirmation(
                confirmation(revision = 1L, granted = false),
            ),
        )
    }

    @Test
    fun onlyExactNewerDenyReleasesWithdrawalFence() {
        val session = IntegratedConsentSession()
        session.accept(confirmation(revision = 1L, granted = true))
        session.restorePendingMutation(mutation())

        assertTrue(
            session.applyExactPendingConfirmation(
                confirmation(revision = 2L, granted = false),
            ),
        )

        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test
    fun accountDeletionFenceRequiresExplicitNewEnrollmentReset() {
        val session = IntegratedConsentSession()
        session.accept(confirmation(revision = 1L, granted = true))

        session.blockForAccountDeletion()
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        session.accept(confirmation(revision = 2L, granted = true))
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))

        session.resetForNewEnrollment()
        assertEquals(
            PurposeConsentSyncState.UNCONFIRMED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    private fun mutation() = PendingIntegratedConsentMutation(
        installationId = INSTALLATION_ID,
        actorSha256 = "a".repeat(64),
        requestId = REQUEST_ID,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        clientRevision = 2L,
        previousServerRevision = 1L,
        expectedPreviousBackendReceiptSha256 = "1".repeat(64),
        desiredSelections = IntegratedConsentSelections(rawSourceCollection = false),
        withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        createdAtEpochMs = 1_000L,
    )

    private fun confirmation(
        requestId: String = REQUEST_ID,
        clientRevision: Long = 2L,
        revision: Long,
        granted: Boolean,
    ) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = INSTALLATION_ID,
        requestId = requestId,
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = clientRevision,
        revision = revision,
        selections = IntegratedConsentSelections(rawSourceCollection = granted),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )

    private companion object {
        const val INSTALLATION_ID = "501e3ad4-e74f-4433-820f-72ac2fdd42ad"
        const val REQUEST_ID = "consent_withdrawal_request_0001"
    }
}
