package kr.co.hanium.dreamup.walksafe.session.gateway

import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class PrivacyStartupRestoreCoordinatorTest {
    @Test fun constructorDoesNotReadPreferencesDeletionAuthorityOrConsent() {
        val f = Fixture(); assertTrue(f.events.isEmpty())
    }

    @Test fun staleActivityCannotWithdrawPrivacyOrReadStorage() {
        val f = Fixture(); f.callback = false
        assertFalse(f.core.restore())
        assertEquals(listOf("deletion.callbackAllowed"), f.events)
    }

    @Test fun unblockedStartupRestoresViaConsentOwnerAfterWithdrawal() {
        val f = Fixture()
        assertTrue(f.core.restore())
        assertTrue(f.events.indexOf("effects.withdrawReportPrivacy") < f.events.indexOf("consent.restoreConsentState"))
        assertTrue(f.events.indexOf("consent.restoreConsentState") < f.events.indexOf("storage.journal"))
        assertEquals("deletion.reconcileAuthorityAfterJournalRestore", f.events.last())
    }

    @Test fun durableConfirmationRecoveryNeverRestoresNormalConsent() {
        val f = Fixture(); f.blocked = true; f.authority = authority(AccountDeletionIntentAuthorityState.Confirmed)
        assertTrue(f.core.restore())
        assertTrue(f.events.contains("deletion.enterDurableConfirmationRecovery"))
        assertFalse(f.events.contains("consent.restoreConsentState"))
        assertTrue(f.events.indexOf("consent.blockForAccountDeletion") < f.events.indexOf("effects.persistRawSourceFieldLogBlocked"))
    }

    @Test fun recoveryFailureCannotPublishConsentBlockOrRawLogCommit() {
        val f = Fixture(); f.blocked = true; f.prepared = true; f.recoveryAccepted = false
        assertFalse(f.core.restore())
        assertFalse(f.events.contains("consent.blockForAccountDeletion"))
        assertFalse(f.events.contains("effects.persistRawSourceFieldLogBlocked"))
    }

    @Test fun unreadableFallbackBlocksRemoteResumeWithoutLosingRecoveryToFailClosed() {
        for (marker in listOf(AndroidAccountDeletionFallbackMarker.State.Corrupt,
            AndroidAccountDeletionFallbackMarker.State.Unavailable)) {
            val f = Fixture(); f.blocked = true; f.fallback = marker
            assertTrue(f.core.restore())
            assertTrue(f.events.contains("deletion.blockRemoteResume"))
            assertFalse(f.events.contains("deletion.failClosed"))
            assertFalse(f.events.contains("effects.invalidateSpeechStorage"))
        }
    }

    @Test fun existingFailClosedFileWinsOverPreparedOrUnreadableMarker() {
        val f = Fixture(); f.blocked = true; f.prepared = true
        f.fallback = AndroidAccountDeletionFallbackMarker.State.Corrupt
        f.authority = authority(AccountDeletionIntentAuthorityState.FailClosed("existing_reason"))
        assertTrue(f.core.restore())
        assertEquals(listOf("existing_reason"), f.reasons)
        assertTrue(f.events.contains("deletion.enforceFailClosedAuthority"))
        assertFalse(f.events.contains("deletion.enterDurableConfirmationRecovery"))
        assertFalse(f.events.contains("deletion.blockRemoteResume"))
        assertTrue(f.events.indexOf("effects.invalidateSpeechStorage") < f.events.indexOf("consent.failClosed"))
    }

    @Test fun leaseLostAfterProcessFailClosedStopsDualAuthorityPublicationAndCommit() {
        val f = Fixture(); f.blocked = true; f.callbackAnswers.addAll(listOf(true, false))
        assertFalse(f.core.restore())
        assertTrue(f.events.contains("deletion.failClosed"))
        assertFalse(f.events.contains("deletion.publishDualFailClosed"))
        assertFalse(f.events.contains("effects.persistRawSourceFieldLogBlocked"))
    }

    @Test fun corruptJournalClosesActualAuthorityBeforeBlockingConsent() {
        val f = Fixture(); f.journalRaw = "corrupt"; f.decoded = null
        assertTrue(f.core.restore())
        assertEquals(listOf("account_deletion_journal_corrupt"), f.reasons)
        assertTrue(f.events.indexOf("deletion.forceAuthorityFailClosed") < f.events.indexOf("consent.blockForAccountDeletion"))
    }

    @Test fun corruptJournalAuthorityFailureStopsFurtherRestore() {
        val f = Fixture(); f.journalRaw = "corrupt"; f.decoded = null; f.forceAccepted = false
        assertFalse(f.core.restore())
        assertFalse(f.events.contains("consent.blockForAccountDeletion"))
        assertFalse(f.events.contains("deletion.reconcileAuthorityAfterJournalRestore"))
    }

    @Test fun monotonicSuccessorIsAcceptedButStaleOrConflictingRestoreIsRejected() {
        for (result in AccountDeletionStartupRestoreResult.entries) {
            val f = Fixture(); f.journalRaw = "journal"; f.restoreResult = result
            val accepted = result == AccountDeletionStartupRestoreResult.RESTORED ||
                result == AccountDeletionStartupRestoreResult.RETAINED_MONOTONIC_PROCESS_SUCCESSOR
            assertEquals(accepted, f.core.restore())
            assertEquals(accepted, f.events.contains("consent.blockForAccountDeletion"))
            assertEquals(accepted, f.events.contains("deletion.reconcileAuthorityAfterJournalRestore"))
            assertSame(f.decoded, f.restoredJournal)
        }
    }

    @Test fun persistedMarkerWithoutJournalBlocksFieldLogThroughExistingEffect() {
        val f = Fixture(); f.marker = true; f.reason = "persisted_reason"
        assertTrue(f.core.restore())
        assertEquals(listOf("persisted_reason"), f.reasons)
        assertTrue(f.events.contains("effects.blockFieldSessionLogIfInitialized"))
    }

    @Test fun existingJournalSuppressesOldFailClosedMarker() {
        val f = Fixture(); f.marker = true; f.journalPresent = true
        assertTrue(f.core.restore())
        assertFalse(f.events.contains("storage.validFailClosedReason"))
        assertFalse(f.events.contains("deletion.forceAuthorityFailClosed"))
    }

    @Test fun legacyResetKeepsPrivacyBlockedBeforeFinalAuthorityReconciliation() {
        val f = Fixture(); f.legacyReset = true
        assertTrue(f.core.restore())
        assertEquals(listOf("account_deletion_reset_pending"), f.reasons)
        assertTrue(f.events.indexOf("consent.blockForAccountDeletion") < f.events.indexOf("deletion.reconcileAuthorityAfterJournalRestore"))
    }

    private class Fixture {
        val events = mutableListOf<String>(); val reasons = mutableListOf<String>()
        var callback = true; val callbackAnswers = mutableListOf<Boolean>()
        var blocked = false; var prepared = false; var recoveryAccepted = true
        var forceAccepted = true; var authority = authority(AccountDeletionIntentAuthorityState.Absent)
        var fallback: AndroidAccountDeletionFallbackMarker.State = AndroidAccountDeletionFallbackMarker.State.Absent
        var journalRaw: String? = null
        var decoded: AccountDeletionJournal? = AccountDeletionJournal.pending("https://gateway.example.test", "installation_fixture", "request_fixture_0001", "2026-10-07T00:00:00Z")
        var restoreResult = AccountDeletionStartupRestoreResult.RESTORED
        var restoredJournal: AccountDeletionJournal? = null
        var journalPresent = false; var marker = false; var reason: String? = null; var legacyReset = false
        val core = PrivacyStartupRestoreCoordinator(
            deletion = port("deletion", events) { method, args -> when(method.name) {
                "callbackAllowed" -> if(callbackAnswers.isEmpty()) callback else callbackAnswers.removeAt(0)
                "startupAuthority" -> authority
                "preparedRecoveryCandidatePresent" -> prepared
                "fallbackState" -> fallback
                "enterDurableConfirmationRecovery" -> recoveryAccepted
                "failClosed", "enforceFailClosedAuthority", "forceAuthorityFailClosed" -> { reasons += args[0] as String; forceAccepted }
                "decodeJournal" -> decoded
                "restoreJournal" -> { restoredJournal = args[0] as AccountDeletionJournal; restoreResult }
                "journalPresent" -> journalPresent
                "legacyResetIntentPresent" -> legacyReset
                "reconcileAuthorityAfterJournalRestore" -> true
                else -> null
            } },
            consent = port("consent", events) { _, _ -> null },
            storage = port("storage", events) { method, _ -> when(method.name) {
                "blocked" -> blocked; "journal" -> journalRaw; "failClosedMarker" -> marker
                "validFailClosedReason" -> reason; else -> null
            } },
            effects = port("effects", events) { _, _ -> null },
        )
    }

    private companion object {
        fun authority(file: AccountDeletionIntentAuthorityState) = AccountDeletionAuthoritySnapshot(
            AccountDeletionIntentFenceState.ABSENT, file, when(file) {
                AccountDeletionIntentAuthorityState.Confirmed -> AccountDeletionAggregateAuthorityState.CONFIRMED
                is AccountDeletionIntentAuthorityState.FailClosed -> AccountDeletionAggregateAuthorityState.FAIL_CLOSED
                else -> AccountDeletionAggregateAuthorityState.ABSENT
            })
    }
}
