package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun regrantMustWaitForExactWithdrawalBeforeANewExplicitGrantCanComplete() {
        for (retry in listOf(false, true)) {
            val (session, current, pending) = withdrawalScenario(retry)
            assertPendingWithdrawalPreserved(session, current, pending, retry)
            val blockedRegrant = pending.copy(
                requestId = "consent_regrant_request_0001",
                clientRevision = 3L,
                desiredSelections = current.selections,
                withdrawalItems = emptySet(),
            )

            assertFalse(session.restorePendingMutation(blockedRegrant, retry = !retry))
            assertPendingWithdrawalPreserved(session, current, pending, retry)

            val prematureGrant = current.copy(
                requestId = blockedRegrant.requestId,
                clientRevision = blockedRegrant.clientRevision,
                revision = 2L,
                selections = blockedRegrant.desiredSelections,
            )
            assertTrue(blockedRegrant.isExactNewerConfirmation(prematureGrant))
            assertTrue(prematureGrant.revision > current.revision)
            assertTrue(prematureGrant.revision > pending.previousServerRevision)
            assertEquals(
                IntegratedConsentApplyResult.NOT_EXACT,
                session.evaluateExactPendingConfirmation(prematureGrant),
            )
            assertFalse(session.applyExactPendingConfirmation(prematureGrant))
            assertPendingWithdrawalPreserved(session, current, pending, retry)

            val denial = current.copy(
                requestId = pending.requestId,
                clientRevision = pending.clientRevision,
                revision = 2L,
                selections = pending.desiredSelections,
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

            val explicitRegrant = blockedRegrant.copy(
                requestId = "consent_regrant_after_deny_0001",
                previousServerRevision = denial.revision,
                expectedPreviousBackendReceiptSha256 = denial.backendConsentReceiptSha256,
            )
            assertTrue(session.restorePendingMutation(explicitRegrant))
            assertEquals(explicitRegrant, session.pendingMutationOrNull())
            assertEquals(
                PurposeConsentSyncState.GRANT_PENDING,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
            assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

            val grant = denial.copy(
                requestId = explicitRegrant.requestId,
                clientRevision = explicitRegrant.clientRevision,
                revision = 3L,
                selections = explicitRegrant.desiredSelections,
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
    }

    @Test
    fun otherHigherChangesCannotReplaceWithdrawalButGrantOnlyPendingCanAdvance() {
        val (session, current, pending) = withdrawalScenario(retry = true)
        val otherChange = pending.copy(
            requestId = "consent_other_change_request_0001",
            clientRevision = 3L,
            desiredSelections = pending.desiredSelections.copy(trainingReuse = true),
            withdrawalItems = emptySet(),
        )
        assertFalse(otherChange.desiredSelections.rawSourceCollection)
        assertTrue(otherChange.withdrawalItems.isEmpty())

        assertFalse(session.restorePendingMutation(otherChange))

        assertPendingWithdrawalPreserved(session, current, pending, retry = true)
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.TRAINING_REUSE),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        val denial = current.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
        )
        assertTrue(session.applyExactPendingConfirmation(denial))
        assertNull(session.pendingMutationOrNull())
        assertEquals(denial, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

        val grants = IntegratedConsentSession()
        val baseline = confirmation(clientRevision = 1L, revision = 1L, granted = false).copy(
            selections = IntegratedConsentSelections(automaticReporting = true),
        )
        assertTrue(grants.accept(baseline))
        val firstGrant = mutation().copy(
            expectedPreviousBackendReceiptSha256 = baseline.backendConsentReceiptSha256,
            desiredSelections = baseline.selections.copy(rawSourceCollection = true),
            withdrawalItems = emptySet(),
        )
        assertTrue(grants.restorePendingMutation(firstGrant))
        val newerGrant = firstGrant.copy(
            requestId = "consent_newer_grant_request_0001",
            clientRevision = 3L,
            desiredSelections = firstGrant.desiredSelections.copy(trainingReuse = true),
        )

        assertTrue(grants.restorePendingMutation(newerGrant))

        assertEquals(newerGrant, grants.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.GRANT_PENDING,
            grants.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertEquals(
            PurposeConsentSyncState.GRANT_PENDING,
            grants.status(IntegratedConsentItem.TRAINING_REUSE),
        )
        assertFalse(grants.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(grants.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        assertTrue(grants.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        val exactGrant = baseline.copy(
            requestId = newerGrant.requestId,
            clientRevision = newerGrant.clientRevision,
            revision = 2L,
            selections = newerGrant.desiredSelections,
        )
        assertTrue(grants.applyExactPendingConfirmation(exactGrant))
        assertNull(grants.pendingMutationOrNull())
        assertEquals(exactGrant, grants.currentConfirmationOrNull())
        for (item in listOf(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            IntegratedConsentItem.AUTOMATIC_REPORTING,
            IntegratedConsentItem.TRAINING_REUSE,
        )) {
            assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, grants.status(item))
            assertTrue(grants.isAllowed(item))
        }
    }

    @Test
    fun identicalWithdrawalRestorationKeepsUnchangedAndRetryTransitionSemantics() {
        val (session, current, pending) = withdrawalScenario(retry = false)
        assertPendingWithdrawalPreserved(session, current, pending, retry = false)
        assertFalse(session.restorePendingMutation(pending.copy(), retry = false))
        assertPendingWithdrawalPreserved(session, current, pending, retry = false)

        assertTrue(session.restorePendingMutation(pending.copy(), retry = true))
        assertPendingWithdrawalPreserved(session, current, pending, retry = true)
        assertFalse(session.restorePendingMutation(pending.copy(), retry = true))
        assertPendingWithdrawalPreserved(session, current, pending, retry = true)
        assertTrue(session.restorePendingMutation(pending.copy(), retry = false))
        assertPendingWithdrawalPreserved(session, current, pending, retry = false)

        val denial = current.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
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

    private fun withdrawalScenario(retry: Boolean): Triple<
        IntegratedConsentSession,
        IntegratedConsentConfirmation,
        PendingIntegratedConsentMutation,
    > {
        val current = confirmation(clientRevision = 1L, revision = 1L, granted = true).copy(
            selections = IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = true,
            ),
        )
        val pending = mutation().copy(
            expectedPreviousBackendReceiptSha256 = current.backendConsentReceiptSha256,
            desiredSelections = current.selections.copy(rawSourceCollection = false),
        )
        val session = IntegratedConsentSession()
        assertTrue(session.accept(current))
        assertTrue(session.restorePendingMutation(pending, retry = retry))
        return Triple(session, current, pending)
    }

    private fun assertPendingWithdrawalPreserved(
        session: IntegratedConsentSession,
        current: IntegratedConsentConfirmation,
        pending: PendingIntegratedConsentMutation,
        retry: Boolean,
    ) {
        assertEquals(current, session.currentConfirmationOrNull())
        assertEquals(pending, session.pendingMutationOrNull())
        assertEquals(
            if (retry) PurposeConsentSyncState.WITHDRAWAL_RETRY
            else PurposeConsentSyncState.WITHDRAWAL_PENDING,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
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
