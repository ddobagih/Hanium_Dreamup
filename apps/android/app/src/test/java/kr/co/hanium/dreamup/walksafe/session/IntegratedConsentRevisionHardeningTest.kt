package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntegratedConsentRevisionHardeningTest {
    @Test
    fun pendingPreviousRevisionCannotLowerLegacyFloor() {
        val session = IntegratedConsentSession()
        assertTrue(
            session.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 5L,
            ),
        )
        val confirmation = confirmation(
            requestId = "consent_request_00000004",
            clientRevision = 4L,
            revision = 4L,
            rawSourceCollection = true,
            receipt = "4".repeat(64),
        )
        assertTrue(
            session.restorePendingMutation(
                pendingMutation(
                    installationId = INSTALLATION_ID,
                    requestId = confirmation.requestId,
                    clientRevision = confirmation.clientRevision,
                    previousServerRevision = 3L,
                    selections = confirmation.selections,
                ),
            ),
        )

        assertFalse(session.applyExactPendingConfirmation(confirmation))
        assertNull(session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun pendingIdentityCannotOverrideLegacyFloorIdentity() {
        val session = IntegratedConsentSession()
        assertTrue(
            session.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 5L,
            ),
        )
        val rejected = session.restorePendingMutation(
            pendingMutation(
                installationId = "install-test-0002",
                requestId = "consent_request_00000006",
                clientRevision = 6L,
                previousServerRevision = 5L,
                selections = IntegratedConsentSelections(
                    rawSourceCollection = true,
                    automaticReporting = false,
                    mobileNetworkTransfer = false,
                    trainingReuse = false,
                ),
            ),
        )

        assertFalse(rejected)
        assertNull(session.pendingMutationOrNull())
        assertNull(session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun validPendingConfirmationMustExceedLegacyAndPendingFloors() {
        val session = IntegratedConsentSession()
        assertTrue(
            session.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 5L,
            ),
        )
        val confirmation = confirmation(
            requestId = "consent_request_00000006",
            clientRevision = 6L,
            revision = 6L,
            rawSourceCollection = true,
            receipt = "6".repeat(64),
        )
        assertTrue(
            session.restorePendingMutation(
                pendingMutation(
                    installationId = INSTALLATION_ID,
                    requestId = confirmation.requestId,
                    clientRevision = confirmation.clientRevision,
                    previousServerRevision = 3L,
                    selections = confirmation.selections,
                ),
            ),
        )

        assertTrue(session.applyExactPendingConfirmation(confirmation))
        assertEquals(confirmation, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun fullConfirmationOlderThanLegacyFloorFailsClosedWithoutCurrentGrant() {
        val restarted = IntegratedConsentSession()
        assertTrue(
            restarted.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val staleGrant = confirmation(
            requestId = "consent_request_00000001",
            clientRevision = 1L,
            revision = 1L,
            rawSourceCollection = true,
            receipt = "1".repeat(64),
        )

        assertFalse(restarted.restoreCurrentConfirmation(staleGrant))
        assertNull(restarted.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun fullConfirmationForDifferentInstallationFailsClosedWithoutCurrentGrant() {
        val restarted = IntegratedConsentSession()
        assertTrue(
            restarted.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val foreignGrant = confirmation(
            requestId = "consent_request_00000002",
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = true,
            receipt = "2".repeat(64),
        ).copy(installationId = "install-test-0002")

        assertFalse(restarted.restoreCurrentConfirmation(foreignGrant))
        assertNull(restarted.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun fullConfirmationMatchingLegacyFloorIdentityAndRevisionRestores() {
        val restarted = IntegratedConsentSession()
        assertTrue(
            restarted.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val exactGrant = confirmation(
            requestId = "consent_request_00000002",
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = true,
            receipt = "2".repeat(64),
        )

        assertTrue(restarted.restoreCurrentConfirmation(exactGrant))
        assertNull(restarted.currentConfirmationOrNull())
        assertEquals(exactGrant, restarted.cachedConfirmationOrNull())
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, restarted.applyConfirmation(exactGrant))
        assertEquals(exactGrant, restarted.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun fullConfirmationNewerThanLegacyFloorFailsClosedWithoutCurrentGrant() {
        val restarted = IntegratedConsentSession()
        assertTrue(
            restarted.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val newerGrant = confirmation(
            requestId = "consent_request_00000003",
            clientRevision = 3L,
            revision = 3L,
            rawSourceCollection = true,
            receipt = "3".repeat(64),
        )

        assertFalse(restarted.restoreCurrentConfirmation(newerGrant))
        assertNull(restarted.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun invalidatePreservesAcceptedRevisionFloorForNewPendingMutation() {
        val session = IntegratedConsentSession()
        val accepted = confirmation(
            requestId = "consent_request_00000003",
            clientRevision = 3L,
            revision = 3L,
            rawSourceCollection = true,
            receipt = "3".repeat(64),
        )
        assertTrue(session.accept(accepted))
        assertTrue(session.invalidate())

        val mutation = pendingMutation(
            installationId = INSTALLATION_ID,
            requestId = "consent_request_00000004",
            clientRevision = 4L,
            previousServerRevision = 1L,
            selections = accepted.selections.copy(rawSourceCollection = false),
        )
        assertTrue(session.restorePendingMutation(mutation))

        listOf(2L, 3L).forEach { revision ->
            val staleConfirmation = confirmation(
                requestId = mutation.requestId,
                clientRevision = mutation.clientRevision,
                revision = revision,
                rawSourceCollection = false,
                receipt = revision.toString().repeat(64),
            )
            assertFalse(session.applyExactPendingConfirmation(staleConfirmation))
            assertNull(session.currentConfirmationOrNull())
            assertEquals(
                PurposeConsentSyncState.FAIL_CLOSED,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
        }
    }

    @Test
    fun legacyFloorSynthesizesNoGrantAndRejectsOlderRevisionFailClosed() {
        val restarted = IntegratedConsentSession()
        assertTrue(
            restarted.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        assertNull(restarted.currentConfirmationOrNull())
        assertFalse(
            restarted.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        val oldGrant = confirmation(
            requestId = "consent_request_00000001",
            clientRevision = 1L,
            revision = 1L,
            rawSourceCollection = true,
            receipt = "1".repeat(64),
        )
        assertFalse(restarted.accept(oldGrant))
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun legacyFloorRejectsEqualGrantButAcceptsNewerValidConfirmation() {
        val equalSession = IntegratedConsentSession()
        assertTrue(
            equalSession.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val equalGrant = confirmation(
            requestId = "consent_request_00000002",
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = true,
            receipt = "2".repeat(64),
        )
        assertFalse(equalSession.accept(equalGrant))
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            equalSession.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        val newerSession = IntegratedConsentSession()
        assertTrue(
            newerSession.restoreServerRevisionFloor(
                installationId = INSTALLATION_ID,
                policyVersion = POLICY_VERSION,
                revision = 2L,
            ),
        )
        val newerGrant = confirmation(
            requestId = "consent_request_00000003",
            clientRevision = 3L,
            revision = 3L,
            rawSourceCollection = true,
            receipt = "3".repeat(64),
        )
        assertTrue(newerSession.accept(newerGrant))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            newerSession.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun restoredServerConfirmationIsMonotonicRevisionFloor() {
        val denied = confirmation(
            requestId = "consent_request_00000002",
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = false,
            receipt = "2".repeat(64),
        )
        val restarted = IntegratedConsentSession()
        assertTrue(restarted.restoreCurrentConfirmation(denied))
        assertNull(restarted.currentConfirmationOrNull())
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, restarted.applyConfirmation(denied))

        val oldGrant = confirmation(
            requestId = "consent_request_00000001",
            clientRevision = 1L,
            revision = 1L,
            rawSourceCollection = true,
            receipt = "1".repeat(64),
        )
        assertFalse(restarted.accept(oldGrant))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        val equalGrant = denied.copy(
            selections = denied.selections.copy(rawSourceCollection = true),
            backendConsentReceiptSha256 = "3".repeat(64),
        )
        assertFalse(restarted.accept(equalGrant))
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            restarted.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun lateGrantCannotOverwriteExactNewerWithdrawal() {
        val session = IntegratedConsentSession()
        val granted = confirmation(
            requestId = "consent_request_00000001",
            clientRevision = 1L,
            revision = 1L,
            rawSourceCollection = true,
            receipt = "1".repeat(64),
        )
        assertTrue(session.accept(granted))

        val mutation = PendingIntegratedConsentMutation(
            installationId = INSTALLATION_ID,
            actorSha256 = "a".repeat(64),
            requestId = "consent_request_00000002",
            policyVersion = POLICY_VERSION,
            clientRevision = 2L,
            previousServerRevision = 1L,
            expectedPreviousBackendReceiptSha256 = "1".repeat(64),
            desiredSelections = granted.selections.copy(rawSourceCollection = false),
            withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            createdAtEpochMs = 1L,
        )
        assertTrue(session.restorePendingMutation(mutation, retry = false))
        val denied = confirmation(
            requestId = mutation.requestId,
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = false,
            receipt = "2".repeat(64),
        )
        assertTrue(session.applyExactPendingConfirmation(denied))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        assertFalse(session.accept(granted))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )

        val equalRevisionConflict = denied.copy(
            selections = denied.selections.copy(rawSourceCollection = true),
            backendConsentReceiptSha256 = "3".repeat(64),
        )
        assertFalse(session.accept(equalRevisionConflict))
        assertEquals(
            PurposeConsentSyncState.FAIL_CLOSED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
    }

    @Test
    fun genericThenExactDuplicateCompletesOnlyThePendingWithdrawal() {
        val (prior, mutation, confirmed) = withdrawalOrderFixture()
        val session = IntegratedConsentSession()
        assertTrue(session.restoreCurrentConfirmation(prior))
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.applyConfirmation(prior))
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertTrue(session.restorePendingMutation(mutation, retry = true))

        assertEquals(IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(confirmed))
        assertEquals(confirmed, session.currentConfirmationOrNull())
        assertEquals(mutation, session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertEquals(
            IntegratedConsentApplyResult.DUPLICATE,
            session.evaluateExactPendingConfirmation(confirmed),
        )

        assertTrue(session.applyExactPendingConfirmation(confirmed))

        assertNull(session.pendingMutationOrNull())
        assertEquals(confirmed, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun exactThenGenericDuplicatePreservesCompletedWithdrawalAndIndependentGrant() {
        val (prior, mutation, confirmed) = withdrawalOrderFixture()
        val session = IntegratedConsentSession()
        assertTrue(session.restoreCurrentConfirmation(prior))
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.applyConfirmation(prior))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertTrue(session.restorePendingMutation(mutation, retry = true))
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(confirmed),
        )

        assertTrue(session.applyExactPendingConfirmation(confirmed))
        assertNull(session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.applyConfirmation(confirmed))

        assertNull(session.pendingMutationOrNull())
        assertEquals(confirmed, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_GRANTED,
            session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun equalRevisionDifferentConfirmationContentCannotCompletePendingWithdrawal() {
        val (prior, mutation, confirmed) = withdrawalOrderFixture()
        val conflicts = listOf(
            "raw selection" to confirmed.copy(
                selections = confirmed.selections.copy(rawSourceCollection = true),
            ),
            "request identity" to confirmed.copy(requestId = "consent_request_00000003"),
            "client revision" to confirmed.copy(clientRevision = 3L),
            "confirmation time" to confirmed.copy(confirmedAt = "2026-07-25T00:00:01.000Z"),
            "backend receipt" to confirmed.copy(backendConsentReceiptSha256 = "3".repeat(64)),
            "gateway receipt" to confirmed.copy(gatewayAuditRecordSha256 = "8".repeat(64)),
            "control secret" to confirmed.copy(controlSecret = "d".repeat(64)),
        )
        for ((name, conflict) in conflicts) {
            val session = IntegratedConsentSession()
            assertTrue(name, session.restoreCurrentConfirmation(prior))
            assertTrue(name, session.restorePendingMutation(mutation, retry = true))
            assertEquals(name, IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(confirmed))
            assertEquals(name, confirmed, session.currentConfirmationOrNull())
            assertTrue(name, session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
            assertEquals(
                name,
                PurposeConsentSyncState.WITHDRAWAL_RETRY,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
            assertEquals(name, confirmed.revision, conflict.revision)
            assertFalse(name, conflict == confirmed)
            assertEquals(
                name,
                IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED,
                session.evaluateExactPendingConfirmation(conflict),
            )

            assertFalse(name, session.applyExactPendingConfirmation(conflict))

            assertEquals(name, mutation, session.pendingMutationOrNull())
            assertNull(name, session.currentConfirmationOrNull())
            assertEquals(
                name,
                PurposeConsentSyncState.FAIL_CLOSED,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
            assertEquals(
                name,
                PurposeConsentSyncState.FAIL_CLOSED,
                session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
            )
            assertFalse(name, session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            assertFalse(name, session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        }
    }

    @Test
    fun identicalCurrentConfirmationMustStillMatchTheExactPendingMutation() {
        val (prior, mutation, confirmed) = withdrawalOrderFixture()
        val mismatches = listOf(
            "pending request identity" to mutation.copy(requestId = "consent_request_00000003"),
            "pending desired selections" to mutation.copy(
                desiredSelections = confirmed.selections.copy(mobileNetworkTransfer = true),
            ),
        )
        for ((name, mismatch) in mismatches) {
            val session = IntegratedConsentSession()
            assertTrue(name, session.restoreCurrentConfirmation(prior))
            assertTrue(name, session.restorePendingMutation(mismatch, retry = true))
            assertFalse(name, mismatch.isExactNewerConfirmation(confirmed))
            assertEquals(name, IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(confirmed))
            assertEquals(name, confirmed, session.currentConfirmationOrNull())
            assertEquals(name, mismatch, session.pendingMutationOrNull())
            assertTrue(name, session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
            assertEquals(
                name,
                PurposeConsentSyncState.WITHDRAWAL_RETRY,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
            assertEquals(
                name,
                IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED,
                session.evaluateExactPendingConfirmation(confirmed),
            )

            assertFalse(name, session.applyExactPendingConfirmation(confirmed))

            assertEquals(name, mismatch, session.pendingMutationOrNull())
            assertNull(name, session.currentConfirmationOrNull())
            assertEquals(
                name,
                PurposeConsentSyncState.FAIL_CLOSED,
                session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            )
            assertEquals(
                name,
                PurposeConsentSyncState.FAIL_CLOSED,
                session.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
            )
            assertFalse(name, session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            assertFalse(name, session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        }
    }

    private fun withdrawalOrderFixture(): Triple<
        IntegratedConsentConfirmation,
        PendingIntegratedConsentMutation,
        IntegratedConsentConfirmation,
    > {
        val prior = confirmation(
            requestId = "consent_request_00000001",
            clientRevision = 1L,
            revision = 1L,
            rawSourceCollection = true,
            receipt = "1".repeat(64),
        ).copy(
            selections = IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = true,
            ),
        )
        val confirmed = confirmation(
            requestId = "consent_request_00000002",
            clientRevision = 2L,
            revision = 2L,
            rawSourceCollection = false,
            receipt = "2".repeat(64),
        ).copy(selections = prior.selections.copy(rawSourceCollection = false))
        val mutation = pendingMutation(
            installationId = INSTALLATION_ID,
            requestId = confirmed.requestId,
            clientRevision = confirmed.clientRevision,
            previousServerRevision = prior.revision,
            selections = confirmed.selections,
        ).copy(withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        return Triple(prior, mutation, confirmed)
    }

    private fun confirmation(
        requestId: String,
        clientRevision: Long,
        revision: Long,
        rawSourceCollection: Boolean,
        receipt: String,
    ): IntegratedConsentConfirmation =
        IntegratedConsentConfirmation(
            schemaVersion = "walksafe.integrated-consent-confirmation.v2",
            policyVersion = POLICY_VERSION,
            installationId = INSTALLATION_ID,
            requestId = requestId,
            itemVersions = IntegratedConsentItemVersions(),
            clientRevision = clientRevision,
            revision = revision,
            selections = IntegratedConsentSelections(
                rawSourceCollection = rawSourceCollection,
                automaticReporting = false,
                mobileNetworkTransfer = false,
                trainingReuse = false,
            ),
            confirmedAt = "2026-07-25T00:00:00.000Z",
            gatewayAuditRecordSha256 = "9".repeat(64),
            backendConsentReceiptSha256 = receipt,
            controlSecret = "c".repeat(64),
        )

    private fun pendingMutation(
        installationId: String,
        requestId: String,
        clientRevision: Long,
        previousServerRevision: Long,
        selections: IntegratedConsentSelections,
    ): PendingIntegratedConsentMutation =
        PendingIntegratedConsentMutation(
            installationId = installationId,
            actorSha256 = "a".repeat(64),
            requestId = requestId,
            policyVersion = POLICY_VERSION,
            clientRevision = clientRevision,
            previousServerRevision = previousServerRevision,
            expectedPreviousBackendReceiptSha256 = "1".repeat(64),
            desiredSelections = selections,
            withdrawalItems = emptySet(),
            createdAtEpochMs = 1L,
        )

    private companion object {
        const val INSTALLATION_ID = "install-test-0001"
        const val POLICY_VERSION = "FP-013-1.2.0"
    }
}
