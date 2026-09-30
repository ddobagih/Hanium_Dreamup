package kr.co.hanium.dreamup.walksafe.session

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RejectedConsentCasJournalCodecTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun durablePendingAndJournalReopenWithoutGrantingTheOldCachedReceipt() {
        val old = confirmation()
        val rejected = pending(old)
        val latest = old.selections.copy(rawSourceCollection = false)
        val successor = rejected.successorAfterRejectedCas(
            latest, "c".repeat(64), 1L, 2L,
            rejected.withdrawalItems, 2L,
        )
        val transition = RejectedConsentCasSupersession(
            rejected, latest, "c".repeat(64), 1L, successor,
        )
        val journal = RejectedConsentCasRecoveryJournal.next(null, transition)
        val pendingFile = temp.newFile("pending.json")
        val journalFile = temp.newFile("cas-journal.json")
        pendingFile.writeText(RejectedConsentCasJournalCodec.pendingJson(successor))
        journalFile.writeText(RejectedConsentCasJournalCodec.journalJson(journal))

        val reopened = RejectedConsentCasJournalCodec.pendingOrNull(pendingFile.readText())
        val history = RejectedConsentCasJournalCodec.journalOrNull(
            journalFile.readText(), reopened,
        )
        assertEquals(successor, reopened)
        assertEquals(journal, history)
        assertTrue(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(
            successor, old, rejected.actorSha256, successor.clientRevision, history,
        ))
        val restoredSession = IntegratedConsentSession()
        assertTrue(restoredSession.restoreCurrentConfirmation(old))
        assertTrue(restoredSession.restorePendingMutation(requireNotNull(reopened), retry = true))
        assertFalse(restoredSession.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            restoredSession.status(IntegratedConsentItem.AUTOMATIC_REPORTING),
        )
        assertFalse(successor.desiredSelections.rawSourceCollection)
        assertFalse(successor.desiredSelections.automaticReporting)
    }

    @Test
    fun missingPendingWrongOwnerAndAlteredBackendChoicesCannotReopen() {
        val old = confirmation()
        val rejected = pending(old)
        val latest = old.selections.copy(rawSourceCollection = false)
        val first = rejected.successorAfterRejectedCas(
            latest, "c".repeat(64), 1L, 2L, rejected.withdrawalItems, 2L,
        )
        val firstTransition = RejectedConsentCasSupersession(
            rejected, latest, "c".repeat(64), 1L, first,
        )
        val secondLatest = latest.copy(mobileNetworkTransfer = false)
        val second = first.successorAfterRejectedCas(
            secondLatest, "e".repeat(64), 1L, 2L, first.withdrawalItems, 3L,
        )
        val secondTransition = RejectedConsentCasSupersession(
            first, secondLatest, "e".repeat(64), 1L, second,
        )
        val firstJournal = RejectedConsentCasRecoveryJournal.next(null, firstTransition)
        val finalJournal = RejectedConsentCasRecoveryJournal.next(firstJournal, secondTransition)
        val valid = RejectedConsentCasJournalCodec.journalJson(finalJournal)
        assertEquals(2L, RejectedConsentCasJournalCodec.journalOrNull(valid, second)?.rejectedCount)
        assertNull(RejectedConsentCasJournalCodec.journalOrNull(valid, null))
        assertNull(RejectedConsentCasJournalCodec.journalOrNull(valid, first))
        val broken = JSONObject(valid)
        broken.getJSONObject("latest_transition").getJSONObject("original")
            .put("actor_sha256", "f".repeat(64))
        assertNull(RejectedConsentCasJournalCodec.journalOrNull(broken.toString(), second))
        val expanded = JSONObject(valid)
        expanded.getJSONObject("latest_transition")
            .getJSONObject("backend_selections").put("mobile_network_transfer", true)
        assertNull(RejectedConsentCasJournalCodec.journalOrNull(expanded.toString(), second))
        assertNull(RejectedConsentCasJournalCodec.pendingOrNull(
            JSONObject(RejectedConsentCasJournalCodec.pendingJson(second))
                .put("unexpected", true).toString(),
        ))
    }

    @Test
    fun seventeenRejectedRacesStayBoundedAndTheStableSuccessorCanComplete() {
        val old = confirmation()
        val session = IntegratedConsentSession()
        assertTrue(session.accept(old))
        var pending = pending(old)
        assertTrue(session.restorePendingMutation(pending, retry = true))
        session.invalidateBackendAuthority()
        var journal: RejectedConsentCasRecoveryJournal? = null
        repeat(17) { index ->
            val choices = old.selections.copy(rawSourceCollection = false)
            val receipt = MessageDigest.getInstance("SHA-256").digest(
                "other-installation-$index".toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val successor = pending.successorAfterRejectedCas(
                choices, receipt, 1L, 2L,
                (journal?.cumulativeDenials ?: emptySet()) + pending.deniedItems(),
                index + 2L,
            )
            val transition = RejectedConsentCasSupersession(
                pending, choices, receipt, 1L, successor,
            )
            journal = RejectedConsentCasRecoveryJournal.next(journal, transition)
            assertEquals(IntegratedConsentSubmissionResult.APPLIED,
                session.supersedeRejectedCasMutation(transition) { true })
            val encoded = RejectedConsentCasJournalCodec.journalJson(requireNotNull(journal))
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size <
                RejectedConsentCasJournalCodec.MAX_JOURNAL_BYTES)
            assertEquals(journal, RejectedConsentCasJournalCodec.journalOrNull(
                encoded, successor))
            pending = successor
        }
        assertEquals(17L, journal?.rejectedCount)
        assertEquals(this.pending(old), journal?.firstRejected)
        assertFalse(pending.desiredSelections.rawSourceCollection)
        assertFalse(pending.desiredSelections.automaticReporting)
        val stable = old.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 20L,
            selections = pending.desiredSelections,
            backendConsentReceiptSha256 = "e".repeat(64),
        )
        assertTrue(session.applyExactPendingConfirmation(stable))
        assertNull(session.pendingMutationOrNull())
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

    private fun pending(old: IntegratedConsentConfirmation): PendingIntegratedConsentMutation {
        val choices = old.selections.copy(automaticReporting = false)
        val requestId = "consent_" + MessageDigest.getInstance("SHA-256").digest(
            ("FP013|${old.installationId}|2|${old.backendConsentReceiptSha256}|$choices")
                .toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PendingIntegratedConsentMutation(
            installationId = old.installationId,
            actorSha256 = "d".repeat(64),
            requestId = requestId,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = 2L,
            previousServerRevision = old.revision,
            expectedPreviousBackendReceiptSha256 = old.backendConsentReceiptSha256,
            desiredSelections = choices,
            withdrawalItems = setOf(IntegratedConsentItem.AUTOMATIC_REPORTING),
            createdAtEpochMs = 1L,
        )
    }
}
