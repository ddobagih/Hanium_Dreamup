package kr.co.hanium.dreamup.walksafe.session

import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.network.validatedIntegratedConsentConfirmationOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentPolicyTest {
    @Test
    fun restoredConfirmationRemainsCachedButCannotGrantBeforeBackendRevalidation() {
        val session = IntegratedConsentSession()
        val cached = confirmation(
            IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
        )

        assertTrue(session.restoreCurrentConfirmation(cached))
        assertNull(session.currentConfirmationOrNull())
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

        assertEquals(IntegratedConsentApplyResult.DUPLICATE, session.applyConfirmation(cached))
        assertEquals(cached, session.currentConfirmationOrNull())
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.invalidateBackendAuthority())
        assertEquals(cached, session.cachedConfirmationOrNull())
        assertNull(session.currentConfirmationOrNull())
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun authorityRefreshHoldKeepsTheExactPendingWithdrawalJournal() {
        val (session, current, pending) = retriedPendingScenario()
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))

        assertTrue(session.invalidateBackendAuthority())
        assertNull(session.currentConfirmationOrNull())
        assertEquals(current, session.cachedConfirmationOrNull())
        assertEquals(pending, session.pendingMutationOrNull())
        assertEquals(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION), session.localWithdrawalItems())
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun processEpochChangeDeniesAConfirmedReceiptBeforeUiObserverRuns() {
        val session = IntegratedConsentSession()
        val proofLive = AtomicBoolean(true)
        val guard = object : IntegratedConsentAuthorityGuard {
            override fun <T : Any> withCurrent(action: () -> T): T? =
                if (proofLive.get()) action() else null
        }
        val current = confirmation(
            IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.applyConfirmation(current, guard),
        )
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))

        proofLive.set(false) // The process generation changed; no UI callback ran yet.
        assertNull(session.currentConfirmationOrNull())
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertEquals(current, session.cachedConfirmationOrNull())
    }

    @Test
    fun independentSelectionsRequireCurrentServerConfirmation() {
        val session = IntegratedConsentSession()
        val confirmation = confirmation(
            IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = false,
                mobileNetworkTransfer = true,
                trainingReuse = false,
            ),
        )

        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.accept(confirmation))
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertTrue(session.isAllowed(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER))
        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
    }

    @Test
    fun oneLocalWithdrawalDoesNotRewriteOtherIndependentDecisions() {
        val session = IntegratedConsentSession()
        session.accept(
            confirmation(
                IntegratedConsentSelections(
                    rawSourceCollection = true,
                    automaticReporting = true,
                    mobileNetworkTransfer = true,
                    trainingReuse = true,
                ),
            ),
        )

        assertTrue(session.withdrawImmediately(IntegratedConsentItem.TRAINING_REUSE))

        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test
    fun exactServerResponseParserRejectsWrongVersionOrExtraFields() {
        val valid = """
            {
              "schema_version":"walksafe.integrated-consent-confirmation.v2",
              "current":true,
              "installation_id":"501e3ad4-e74f-4433-820f-72ac2fdd42ad",
              "request_id":"integrated_consent_request_0001",
              "policy_version":"FP-013-1.2.0",
              "item_versions":{
                "raw_source_collection":"FP-013-RAW-1.2.0",
                "automatic_reporting":"FP-013-AUTO-1.2.0",
                "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
                "training_reuse":"FP-013-TRAINING-1.1.0"
              },
              "client_revision":4,
              "revision":3,
              "selections":{
                "raw_source_collection":true,
                "automatic_reporting":false,
                "mobile_network_transfer":false,
                "training_reuse":true
              },
              "confirmed_at":"2026-07-25T12:00:00.000Z",
              "gateway_audit_record_sha256":"${"9".repeat(64)}",
              "backend_consent_receipt_sha256":"${"a".repeat(64)}"
            }
        """.trimIndent()

        val parsed = validatedIntegratedConsentConfirmationOrNull(
            valid,
            "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
            "b".repeat(64),
        )

        assertNotNull(parsed)
        assertEquals(3L, parsed?.revision)
        assertEquals("9".repeat(64), parsed?.gatewayAuditRecordSha256)
        assertEquals("a".repeat(64), parsed?.backendConsentReceiptSha256)
        assertTrue(parsed?.selections?.rawSourceCollection == true)
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("FP-013-1.2.0", "FP-013-0.9.0"),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("\"current\":true", "\"current\":true,\"extra\":1"),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid.replace("\"revision\":3", "\"revision\":\"3\""),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
        assertNull(
            validatedIntegratedConsentConfirmationOrNull(
                valid
                    .replace(
                        "\"walksafe.integrated-consent-confirmation.v2\"",
                        "\"walksafe.integrated-consent-confirmation.v1\"",
                    )
                    .replace(
                        "\"gateway_audit_record_sha256\":\"${"9".repeat(64)}\",\n              \"backend_consent_receipt_sha256\"",
                        "\"receipt_sha256\"",
                    ),
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
                "b".repeat(64),
            ),
        )
    }

    @Test
    fun olderPendingAndItsExactResponseCannotRollBackTheLatestRetryOrSelections() {
        val (session, current, pending) = retriedPendingScenario()
        assertRetriedPendingPreserved(session, current, pending)
        val older = pending.copy(
            requestId = "integrated_consent_request_0002",
            clientRevision = 2L,
            desiredSelections = current.selections.copy(automaticReporting = false),
            withdrawalItems = setOf(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )

        assertFalse(session.restorePendingMutation(older, retry = false))
        assertRetriedPendingPreserved(session, current, pending)

        val oldExactResponse = current.copy(
            requestId = older.requestId,
            clientRevision = older.clientRevision,
            revision = 2L,
            selections = older.desiredSelections,
        )
        assertTrue(older.isExactNewerConfirmation(oldExactResponse))
        assertTrue(oldExactResponse.revision > current.revision)
        assertTrue(oldExactResponse.revision > pending.previousServerRevision)
        assertEquals(
            IntegratedConsentApplyResult.NOT_EXACT,
            session.evaluateExactPendingConfirmation(oldExactResponse),
        )
        assertFalse(session.applyExactPendingConfirmation(oldExactResponse))
        assertRetriedPendingPreserved(session, current, pending)

        val newestExactResponse = current.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(newestExactResponse),
        )
        assertTrue(session.applyExactPendingConfirmation(newestExactResponse))
        assertCompletedPendingSelections(session, newestExactResponse)

        assertFalse(session.restorePendingMutation(older, retry = true))
        assertFalse(session.applyExactPendingConfirmation(oldExactResponse))
        assertCompletedPendingSelections(session, newestExactResponse)
    }

    @Test
    fun identicalPendingRestorationWithTheSameRetryStateIsUnchangedAndCanComplete() {
        val (session, current, pending) = retriedPendingScenario()
        val identical = pending.copy()
        assertEquals(pending, identical)
        assertRetriedPendingPreserved(session, current, pending)

        // False means no change, not removal or a conflicting mutation.
        assertFalse(session.restorePendingMutation(identical, retry = true))

        assertRetriedPendingPreserved(session, current, pending)
        val exactResponse = current.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(exactResponse),
        )
        assertTrue(session.applyExactPendingConfirmation(exactResponse))
        assertCompletedPendingSelections(session, exactResponse)
    }

    @Test
    fun sameClientRevisionWithDifferentValidPayloadFailsClosedWithoutReplacingPending() {
        for (variant in listOf("request", "selections")) {
            val (session, current, pending) = retriedPendingScenario()
            assertRetriedPendingPreserved(session, current, pending)
            val conflicting = if (variant == "request") {
                pending.copy(requestId = "integrated_consent_conflict_0003")
            } else {
                pending.copy(
                    desiredSelections = pending.desiredSelections.copy(trainingReuse = false),
                )
            }
            assertEquals(pending.clientRevision, conflicting.clientRevision)
            assertEquals(pending.installationId, conflicting.installationId)
            assertEquals(pending.actorSha256, conflicting.actorSha256)
            assertEquals(pending.policyVersion, conflicting.policyVersion)
            assertFalse(pending == conflicting)

            assertFalse(session.restorePendingMutation(conflicting, retry = false))

            assertPendingConflictClosesSession(session, pending)
        }
    }

    @Test
    fun higherRevisionCannotCrossPendingIdentityAndOldPolicyFailsAtDtoConstruction() {
        for (variant in listOf("actor", "installation")) {
            val (session, current, pending) = retriedPendingScenario()
            assertRetriedPendingPreserved(session, current, pending)
            val newer = pending.copy(
                requestId = "integrated_consent_request_0004",
                clientRevision = 4L,
            )
            val foreign = if (variant == "actor") {
                newer.copy(actorSha256 = "d".repeat(64))
            } else {
                newer.copy(installationId = "601e3ad4-e74f-4433-820f-72ac2fdd42ad")
            }
            assertTrue(foreign.clientRevision > pending.clientRevision)

            assertFalse(session.restorePendingMutation(foreign, retry = false))

            assertPendingConflictClosesSession(session, pending)
        }

        val (session, current, pending) = retriedPendingScenario()
        assertRetriedPendingPreserved(session, current, pending)
        // An unsupported policy cannot produce a DTO to pass to restorePendingMutation.
        assertThrows(IllegalArgumentException::class.java) {
            pending.copy(policyVersion = PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION)
        }
        assertRetriedPendingPreserved(session, current, pending)
        val exactResponse = current.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
        )
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.evaluateExactPendingConfirmation(exactResponse),
        )
        assertTrue(session.applyExactPendingConfirmation(exactResponse))
        assertCompletedPendingSelections(session, exactResponse)
    }

    private fun retriedPendingScenario(): Triple<
        IntegratedConsentSession,
        IntegratedConsentConfirmation,
        PendingIntegratedConsentMutation,
    > {
        val current = confirmation(
            IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = true,
                mobileNetworkTransfer = true,
                trainingReuse = false,
            ),
        )
        val pending = PendingIntegratedConsentMutation(
            installationId = current.installationId,
            actorSha256 = "c".repeat(64),
            requestId = "integrated_consent_request_0003",
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = 3L,
            previousServerRevision = current.revision,
            expectedPreviousBackendReceiptSha256 = current.backendConsentReceiptSha256,
            desiredSelections = current.selections.copy(
                rawSourceCollection = false,
                trainingReuse = true,
            ),
            withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            createdAtEpochMs = 1L,
        )
        val session = IntegratedConsentSession()
        assertTrue(session.accept(current))
        assertTrue(session.restorePendingMutation(pending))
        assertTrue(session.markPendingRetry())
        return Triple(session, current, pending)
    }

    private fun assertRetriedPendingPreserved(
        session: IntegratedConsentSession,
        current: IntegratedConsentConfirmation,
        pending: PendingIntegratedConsentMutation,
    ) {
        assertEquals(current, session.currentConfirmationOrNull())
        assertEquals(pending, session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.GRANT_PENDING,
            session.status(IntegratedConsentItem.TRAINING_REUSE),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))
        for (item in listOf(
            IntegratedConsentItem.AUTOMATIC_REPORTING,
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
        )) {
            assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, session.status(item))
            assertTrue(session.isAllowed(item))
        }
    }

    private fun assertCompletedPendingSelections(
        session: IntegratedConsentSession,
        confirmation: IntegratedConsentConfirmation,
    ) {
        assertNull(session.pendingMutationOrNull())
        assertEquals(confirmation, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        for (item in listOf(
            IntegratedConsentItem.AUTOMATIC_REPORTING,
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
            IntegratedConsentItem.TRAINING_REUSE,
        )) {
            assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, session.status(item))
            assertTrue(session.isAllowed(item))
        }
    }

    private fun assertPendingConflictClosesSession(
        session: IntegratedConsentSession,
        pending: PendingIntegratedConsentMutation,
    ) {
        assertEquals(pending, session.pendingMutationOrNull())
        assertNull(session.currentConfirmationOrNull())
        IntegratedConsentItem.entries.forEach { item ->
            assertEquals(PurposeConsentSyncState.FAIL_CLOSED, session.status(item))
            assertFalse(session.isAllowed(item))
        }
    }

    private fun confirmation(
        selections: IntegratedConsentSelections,
    ) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = selections,
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )
}
