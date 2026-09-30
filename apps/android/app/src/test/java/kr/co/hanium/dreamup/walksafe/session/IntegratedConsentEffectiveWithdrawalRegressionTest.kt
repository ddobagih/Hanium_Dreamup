package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02-23/S02-27 policy regressions: the server receipt is kept as evidence, while every allow
 * decision reads the effective consent that already includes an undelivered local withdrawal.
 */
class IntegratedConsentEffectiveWithdrawalRegressionTest {
    @Test
    fun offlineWithdrawalSurvivesRestartAndReplayOfTheOldReceipt() {
        val receipt = confirmation(revision = 2L)
        // Before restart: local withdrawal of automatic reporting only.
        IntegratedConsentSession().apply {
            assertTrue(restoreCurrentConfirmation(receipt))
            assertTrue(withdrawImmediately(IntegratedConsentItem.AUTOMATIC_REPORTING))
        }
        // After restart: the confirmation and the durable local-withdrawal marker are restored.
        val session = IntegratedConsentSession()
        assertTrue(session.restoreCurrentConfirmation(receipt))
        session.withdrawImmediately(IntegratedConsentItem.AUTOMATIC_REPORTING)
        // The server replays the same or an older receipt.
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.applyConfirmation(receipt))
        assertEquals(
            IntegratedConsentApplyResult.STALE_IGNORED,
            session.applyConfirmation(confirmation(revision = 1L)),
        )
        // Evidence keeps the server selection; allow decisions follow the local withdrawal.
        assertTrue(session.currentConfirmationOrNull()!!.selections.automaticReporting)
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertNull(session.currentConfirmationOrNull(setOf(IntegratedConsentItem.AUTOMATIC_REPORTING)))
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        // Independent grants stay usable.
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        // A re-send uses the effective selection, never the stale receipt value.
        assertEquals(
            receipt.selections.copy(automaticReporting = false),
            session.withdrawalRecoverySelectionsOrNull(),
        )
    }

    @Test
    fun policyReconsentRefusesToDropAMarkerOnlyLocalWithdrawal() {
        val session = IntegratedConsentSession()
        assertTrue(session.restoreCurrentConfirmation(confirmation(revision = 2L)))
        session.withdrawImmediately(IntegratedConsentItem.RAW_SOURCE_COLLECTION)

        assertFalse(session.resetForPolicyReconsent())
        assertEquals(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            session.localWithdrawalItems(),
        )
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    private fun confirmation(revision: Long) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "install-test-0001",
        requestId = "consent_request_0000000$revision",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = revision,
        revision = revision,
        selections = IntegratedConsentSelections(
            rawSourceCollection = true,
            automaticReporting = true,
            mobileNetworkTransfer = false,
            trainingReuse = false,
        ),
        confirmedAt = "2026-09-17T00:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "3".repeat(64),
        controlSecret = "c".repeat(64),
    )
}
