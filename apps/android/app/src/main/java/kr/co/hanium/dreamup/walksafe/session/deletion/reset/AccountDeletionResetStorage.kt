package kr.co.hanium.dreamup.walksafe.session.deletion.reset

import android.content.SharedPreferences
import java.io.File
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.FileAccountDeletionResetJournal
import kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionStore
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionDualAuthority
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournalCodec
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase

/** The existing cross-store participants and exact preference contract of installation reset. */
internal class AccountDeletionResetStorage(
    private val plainPreferences: () -> SharedPreferences,
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore?,
    private val gatewayStore: () -> AndroidGatewaySessionStore?,
    private val dualAuthority: () -> AccountDeletionDualAuthority,
    private val routePurgeVerified: () -> Boolean,
    private val resetFieldStorage: () -> Boolean,
) {
    private val stepLengthPrefs: SharedPreferences get() = plainPreferences()
    private val sensitivePrefs: AndroidSensitivePreferenceStore get() = requireNotNull(sensitivePreferences())
    private val accountDeletionDualAuthority: AccountDeletionDualAuthority get() = dualAuthority()

    fun createCoordinator(noBackupRoot: File): AccountDeletionResetCoordinator =
        AccountDeletionResetCoordinator(
            journal = FileAccountDeletionResetJournal(File(noBackupRoot, "account_deletion_reset")),
            storeLegacyIntent = {
                stepLengthPrefs.edit().putBoolean(PREF_ACCOUNT_DELETION_RESET_REQUIRED, true).commit() &&
                    legacyIntentState() == LegacyResetIntentState.EXACT
            },
            resetGateway = {
                if (gatewayStore() == null) false else {
                    val generation = GatewaySessionProcessCoordinator.snapshot().generation
                    GatewaySessionProcessCoordinator.clear(generation) != null &&
                        requireNotNull(gatewayStore()).resetInstallationAfterConfirmedAccountDeletion()
                }
            },
            resetSensitivePreferences = { sensitivePreferences()?.destroyAndClear() == true },
            resetPlainPreferences = ::clearPlainResetArtifacts,
            resetFieldStorage = resetFieldStorage,
            clearDeletionIntentFence = { accountDeletionDualAuthority.clear().cleared },
        )

    fun authoritySnapshot() = accountDeletionDualAuthority.startupState()
    fun clearIntentFence(): Boolean = accountDeletionDualAuthority.clear().cleared
    fun failClosedAuthority(reason: String) { accountDeletionDualAuthority.failClosed(reason) }

    fun blockSensitiveAndAuthorityForResetFailure() {
        sensitivePrefs.failClosed()
        accountDeletionDualAuthority.failClosed("account_deletion_reset_failed")
    }

    fun markRawSourceFieldLogBlocked() {
        stepLengthPrefs.edit().putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true).commit()
    }

    fun confirmedJournalOrNull(): AccountDeletionJournal? = sensitivePrefs
        .getString(PREF_ACCOUNT_DELETION_JOURNAL, null)?.let(AccountDeletionJournalCodec::decodeOrNull)

    fun clearPlainResetArtifacts(): Boolean {
        val routeSnapshotPurged = routePurgeVerified()
        if (!routeSnapshotPurged) return false
        val keys = setOf(
            PREF_RAW_SOURCE_FIELD_LOG_BLOCKED,
            PREF_ACCOUNT_DELETION_RESET_REQUIRED,
            PREF_REPORTER_USER_ID_KEY,
            PREF_ACCOUNT_DELETION_COMPLETION_RECEIPT,
            PREF_DEVICE_CHECK_LOCATION_RESULT,
            PREF_DEVICE_CHECK_LOCATION_REASON,
            PREF_DEVICE_CHECK_LOCATION_ACCURACY_BUCKET,
            PREF_DEVICE_CHECK_LOCATION_AGE_BUCKET,
        )
        val committed = stepLengthPrefs.edit().also { editor ->
            keys.forEach(editor::remove)
        }.commit()
        return routeSnapshotPurged && committed && runCatching {
            val remaining = stepLengthPrefs.all.keys
            keys.none(remaining::contains)
        }.getOrDefault(false)
    }

    fun legacyIntentState(): LegacyResetIntentState {
        val present = runCatching {
            stepLengthPrefs.contains(PREF_ACCOUNT_DELETION_RESET_REQUIRED)
        }.getOrElse { return LegacyResetIntentState.CORRUPT }
        if (!present) return LegacyResetIntentState.ABSENT
        val value = runCatching {
            stepLengthPrefs.all[PREF_ACCOUNT_DELETION_RESET_REQUIRED]
        }.getOrElse { return LegacyResetIntentState.CORRUPT }
        return if (value is Boolean && value) {
            LegacyResetIntentState.EXACT
        } else {
            LegacyResetIntentState.CORRUPT
        }
    }

    fun persistTerminalState(
        journal: AccountDeletionJournal?,
        fallbackReason: String?,
    ): Boolean {
        val terminalJournal = journal?.takeIf {
            it.phase == AccountDeletionPhase.FAIL_CLOSED
        }
        val reason = terminalJournal?.lastErrorCode
            ?: fallbackReason
            ?: journal?.lastErrorCode
            ?: "account_deletion_fail_closed"
        val authorityPersisted =
            accountDeletionDualAuthority.failClosed(reason).durableSuccess
        val serializedJournal = terminalJournal?.let(AccountDeletionJournalCodec::encode)
        val preferencePersisted = runCatching {
            val editor = sensitivePrefs.edit()
                .putBoolean(PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER, true)
                .putString(PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON, reason)
            if (serializedJournal != null) {
                editor.putString(PREF_ACCOUNT_DELETION_JOURNAL, serializedJournal)
            }
            // This is a recovery authority write, so a previous identical value is not a fresh ACK.
            editor.commitPersisted() &&
                sensitivePrefs.getBoolean(
                    PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                    false,
                ) &&
                sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                    null,
                ) == reason &&
                (
                    serializedJournal == null ||
                        sensitivePrefs.getString(
                            PREF_ACCOUNT_DELETION_JOURNAL,
                            null,
                        ) == serializedJournal
                )
        }.getOrDefault(false)
        return authorityPersisted && preferencePersisted
    }

    private companion object {
        const val PREF_ACCOUNT_DELETION_RESET_REQUIRED =
                    "account_deletion_reset_required_v1"
        const val PREF_RAW_SOURCE_FIELD_LOG_BLOCKED =
                    "raw_source_field_log_blocked_v1"
        const val PREF_REPORTER_USER_ID_KEY = "reporter_user_id"
        const val PREF_ACCOUNT_DELETION_COMPLETION_RECEIPT =
                    "account_deletion_completion_receipt_v1"
        const val PREF_DEVICE_CHECK_LOCATION_RESULT =
                    "device_check_location_result_v1"
        const val PREF_DEVICE_CHECK_LOCATION_REASON =
                    "device_check_location_reason_v1"
        const val PREF_DEVICE_CHECK_LOCATION_ACCURACY_BUCKET =
                    "device_check_location_accuracy_bucket_v1"
        const val PREF_DEVICE_CHECK_LOCATION_AGE_BUCKET =
                    "device_check_location_age_bucket_v1"
        const val PREF_ACCOUNT_DELETION_JOURNAL =
                    "account_deletion_journal_v2"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER =
                    "account_deletion_fail_closed_v1"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON =
                    "account_deletion_fail_closed_reason_v1"
    }
}
