package kr.co.hanium.dreamup.walksafe.session.gateway

import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*

/** Restores through the existing consent and deletion owners, without owning their stores or authority. */
internal class PrivacyStartupRestoreCoordinator(
    private val deletion: Deletion,
    private val consent: Consent,
    private val storage: Storage,
    private val effects: Effects,
) {
    interface Deletion {
        fun callbackAllowed(): Boolean
        fun startupAuthority(): AccountDeletionAuthoritySnapshot
        fun preparedRecoveryCandidatePresent(): Boolean
        fun fallbackState(): AndroidAccountDeletionFallbackMarker.State
        fun enterDurableConfirmationRecovery(): Boolean
        fun blockRemoteResume()
        fun enforceFailClosedAuthority(reason: String): Boolean
        fun failClosed(reason: String): Boolean
        fun publishDualFailClosed(reason: String)
        fun decodeJournal(raw: String): AccountDeletionJournal?
        fun restoreJournal(journal: AccountDeletionJournal): AccountDeletionStartupRestoreResult
        fun forceAuthorityFailClosed(reason: String): Boolean
        fun journalPresent(): Boolean
        fun legacyResetIntentPresent(): Boolean
        fun reconcileAuthorityAfterJournalRestore(): Boolean
    }
    interface Consent {
        fun restoreConsentState()
        fun blockForAccountDeletion()
        fun failClosed()
    }
    interface Storage {
        fun blocked(): Boolean
        fun journal(): String?
        fun failClosedMarker(): Boolean
        fun validFailClosedReason(): String?
    }
    interface Effects {
        fun withdrawReportPrivacy()
        fun blockReportPrivacy()
        fun invalidateSpeechStorage()
        fun persistRawSourceFieldLogBlocked()
        fun blockFieldSessionLogIfInitialized()
    }

    fun restore(): Boolean {
        if (!deletion.callbackAllowed()) return false
        // Durable receipts remain recovery input; only the existing consent owner restores authority.
        effects.withdrawReportPrivacy()
        if (storage.blocked()) {
            val startupDecision = accountDeletionStartupAuthorityDecision(
                snapshot = deletion.startupAuthority(), journalPresent = false,
            )
            val preparedMarkerRecovery = deletion.preparedRecoveryCandidatePresent() &&
                deletion.startupAuthority().fileState !is AccountDeletionIntentAuthorityState.FailClosed
            val unreadableMarker = deletion.fallbackState() is AndroidAccountDeletionFallbackMarker.State.Corrupt ||
                deletion.fallbackState() is AndroidAccountDeletionFallbackMarker.State.Unavailable
            if (startupDecision.durableConfirmationRecoveryRequired || preparedMarkerRecovery) {
                if (!deletion.enterDurableConfirmationRecovery()) return false
                consent.blockForAccountDeletion()
            } else if (unreadableMarker &&
                deletion.startupAuthority().fileState !is AccountDeletionIntentAuthorityState.FailClosed
            ) {
                deletion.blockRemoteResume()
                consent.blockForAccountDeletion()
            } else {
                val reason = startupDecision.failClosedReason ?: "sensitive_storage_blocked"
                effects.invalidateSpeechStorage()
                consent.failClosed()
                if (deletion.startupAuthority().fileState is AccountDeletionIntentAuthorityState.FailClosed) {
                    if (!deletion.enforceFailClosedAuthority(reason)) return false
                } else {
                    if (!deletion.failClosed(reason)) return false
                }
                if (!deletion.callbackAllowed()) return false
                deletion.publishDualFailClosed(reason)
            }
            effects.blockReportPrivacy()
            effects.persistRawSourceFieldLogBlocked()
            return true
        }
        consent.restoreConsentState()
        storage.journal()?.let { raw ->
            val journal = deletion.decodeJournal(raw)
            if (journal == null) {
                if (!deletion.forceAuthorityFailClosed("account_deletion_journal_corrupt")) return false
                consent.blockForAccountDeletion()
                effects.blockReportPrivacy()
            } else {
                when (deletion.restoreJournal(journal)) {
                    AccountDeletionStartupRestoreResult.RESTORED,
                    AccountDeletionStartupRestoreResult.RETAINED_MONOTONIC_PROCESS_SUCCESSOR -> Unit
                    AccountDeletionStartupRestoreResult.STALE_ACTIVITY,
                    AccountDeletionStartupRestoreResult.CONFLICT -> return false
                }
                consent.blockForAccountDeletion()
                effects.blockReportPrivacy()
            }
        }
        if (storage.failClosedMarker() && !deletion.journalPresent()) {
            val persistedReason = storage.validFailClosedReason()
            if (!deletion.forceAuthorityFailClosed(persistedReason ?: "account_deletion_blocked_marker")) return false
            consent.blockForAccountDeletion()
            effects.blockReportPrivacy()
            effects.blockFieldSessionLogIfInitialized()
        }
        if (deletion.legacyResetIntentPresent()) {
            if (!deletion.forceAuthorityFailClosed("account_deletion_reset_pending")) return false
            consent.blockForAccountDeletion()
            effects.blockReportPrivacy()
        }
        return deletion.reconcileAuthorityAfterJournalRestore()
    }
}
