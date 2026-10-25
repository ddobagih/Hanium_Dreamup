package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.LegacyResetIntentState
import org.json.JSONObject

/** Actual durable deletion stores and their original observed startup authority. */
internal class AccountDeletionRecoveryStore(
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore,
    private val installationId: () -> String?,
    fallbackMarkerFactory: () -> AndroidAccountDeletionFallbackMarker,
    private val configuredGatewayOriginOrNull: () -> String?,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val workerStages: AccountDeletionWorkerStages,
    private val resetController: () -> AccountDeletionInstallationResetController,
    private val effects: AccountDeletionRecoveryEffects,
) {
    private val sensitivePrefs: AndroidSensitivePreferenceStore get() = sensitivePreferences()
    val fallbackMarker: AndroidAccountDeletionFallbackMarker by lazy { fallbackMarkerFactory() }
    lateinit var intentFence: AccountDeletionIntentFence
        private set
    lateinit var preparedConfirmationRecovery: PreparedAccountDeletionConfirmationRecovery
        private set
    lateinit var dualAuthority: AccountDeletionDualAuthority
        private set
    var authorityAtStartup = AccountDeletionAuthoritySnapshot(
        preferenceState = AccountDeletionIntentFenceState.UNAVAILABLE,
        fileState = AccountDeletionIntentAuthorityState.Unavailable,
        aggregateState = AccountDeletionAggregateAuthorityState.UNAVAILABLE,
    )
        private set
    var fallbackPresentAtStartup = false
        private set
    @Volatile
    var startupFallbackState: AndroidAccountDeletionFallbackMarker.State = AndroidAccountDeletionFallbackMarker.State.Absent
        private set
    var remoteResumeBlocked = false
        private set

    fun bindIntentFence(fence: AccountDeletionIntentFence) { intentFence = fence }
    fun bindFileAuthority(authority: FileAccountDeletionIntentAuthority) {
        preparedConfirmationRecovery = authority
        dualAuthority = AccountDeletionDualAuthority(preferenceFence = intentFence, fileAuthority = authority)
    }
    fun observeAuthority(snapshot: AccountDeletionAuthoritySnapshot) { authorityAtStartup = snapshot }
    fun observeFallbackState(state: AndroidAccountDeletionFallbackMarker.State) { startupFallbackState = state }
    fun observeFallbackPresence(present: Boolean) { fallbackPresentAtStartup = present }
    fun setRemoteResumeBlocked(blocked: Boolean) { remoteResumeBlocked = blocked }

    fun reconcileAuthorityAfterJournalRestore(): Boolean {
        if (!effects.activityCallbackAllowed()) return false
        val restoredJournal = stateMachine().snapshotOrNull()
        var authority = dualAuthority.startupState()

        if (
            restoredJournal != null &&
            authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed
        ) {
            val migrated = if (
                restoredJournal.phase == AccountDeletionPhase.FAIL_CLOSED
            ) {
                val upgrade =
                    stateMachine().beginLegacyFailClosedUpgradeAttempt(
                        restoredJournal,
                        activityLease(),
                    )
                val result = upgrade?.let {
                    stateMachine().runLegacyFailClosedUpgradeStageIfCurrent(
                        upgrade,
                    ) { terminalJournal ->
                        val reason = terminalJournal.lastErrorCode
                            ?: "account_deletion_fail_closed"
                        dualAuthority.failClosed(reason).durableSuccess
                    }
                }
                val afterUpgrade = dualAuthority.startupState()
                result == AccountDeletionWorkerStageResult.SUCCEEDED ||
                    (
                        afterUpgrade.fileState is
                            AccountDeletionIntentAuthorityState.FailClosed &&
                            afterUpgrade.fileState.reason ==
                            restoredJournal.lastErrorCode
                        )
            } else if (
                authority.fileState == AccountDeletionIntentAuthorityState.Absent
            ) {
                val attempt = stateMachine().beginWorkerAttempt(
                    restoredJournal,
                    activityLease(),
                )
                val result = attempt?.let {
                    workerStages.durable(it) {
                        dualAuthority.confirm().decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED
                    }
                }
                result == true ||
                    dualAuthority.startupState().fileState ==
                    AccountDeletionIntentAuthorityState.Confirmed
            } else {
                true
            }
            authority = dualAuthority.startupState()
            if (!migrated) {
                if (!effects.activityCallbackAllowed()) {
                    return false
                }
                if (
                    !forceAuthorityFailClosed(
                        "deletion_intent_authority_migration_failed",
                    )
                ) return false
                authority = dualAuthority.startupState()
            }
        }

        val startupDecision = accountDeletionStartupAuthorityDecision(
            snapshot = authority,
            journalPresent = restoredJournal != null,
        )
        val preparedMarkerRecovery =
            restoredJournal == null &&
                preparedRecoveryCandidateOrNull() != null &&
                authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed
        if (
            startupDecision.durableConfirmationRecoveryRequired ||
            preparedMarkerRecovery
        ) {
            if (
                !stateMachine().enterDurableConfirmationRecovery(
                    activityLease(),
                )
            ) return false
        } else {
            startupDecision.failClosedReason?.let { reason ->
                if (authority.fileState is AccountDeletionIntentAuthorityState.FailClosed) {
                    if (
                        !stateMachine().enforceFailClosedAuthority(
                            activityLease(),
                            reason,
                        )
                    ) return false
                    resetController().persistTerminalStateOrHandle(
                        stateMachine().snapshotOrNull(),
                        reason,
                    )
                } else if (!forceAuthorityFailClosed(reason)) {
                    return false
                }
            }
        }
        if (startupDecision.blocksPrivacy || preparedMarkerRecovery) {
            effects.blockPrivacyForAuthority()
        }
        authorityAtStartup = dualAuthority.startupState()
        return true
    }

    fun forceAuthorityFailClosed(reason: String): Boolean {
        if (
            !stateMachine().failClosed(
                activityLease(),
                reason,
            )
        ) return false
        if (
            !resetController().persistTerminalState(
                stateMachine().snapshotOrNull(),
                reason,
            )
        ) {
            dualAuthority.failClosed(reason)
        }
        return true
    }

    fun markerPresentAtStartup(): Boolean =
        sensitivePrefs.isBlocked() ||
            sensitivePrefs.contains(PREF_ACCOUNT_DELETION_JOURNAL) ||
            sensitivePrefs.getBoolean(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                false,
            ) ||
            authorityAtStartup.blocksPrivacy ||
            resetController().hasPendingOrCorruptJournal() ||
            resetController().legacyIntentState() != LegacyResetIntentState.ABSENT

    fun prepareRecoveryMarker(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        actorId: String,
    ): AccountDeletionRecoveryMarkerPreparation {
        if (
            session.actorId != actorId ||
            session.gatewayBaseUrl != trustedGatewayOrigin
        ) return AccountDeletionRecoveryMarkerPreparation.Blocked
        val actorHash = sha256Hex(actorId.toByteArray(Charsets.UTF_8))
        val installationId =
            installationId()
                ?: return AccountDeletionRecoveryMarkerPreparation.Blocked
        var durabilityVerified = false
        val expected = when (val markerState = fallbackMarker.read()) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> {
                val requestRandom = ByteArray(32)
                val capabilityRandom = ByteArray(32)
                SecureRandom().nextBytes(requestRandom)
                SecureRandom().nextBytes(capabilityRandom)
                val requestId = "account_delete_" +
                    requestRandom.joinToString("") {
                        "%02x".format(Locale.US, it.toInt() and 0xff)
                    }
                requestRandom.fill(0)
                val record = AndroidAccountDeletionFallbackMarker.Record(
                    identity = AndroidAccountDeletionFallbackMarker.Identity(
                        requestId = requestId,
                        actorHash = actorHash,
                    ),
                    recoveryActorId = actorId,
                    gatewayOrigin = trustedGatewayOrigin,
                    installationId = installationId,
                    accessSecret = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(capabilityRandom),
                    clientRevision = 1L,
                    phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
                )
                capabilityRandom.fill(0)
                durabilityVerified = fallbackMarker.create(record)
                record
            }
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                val current = markerState.record.takeIf {
                    it.identity.actorHash == actorHash &&
                        it.gatewayOrigin == trustedGatewayOrigin &&
                        it.installationId == installationId &&
                        it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                        (it.recoveryActorId == null || it.recoveryActorId == actorId)
                } ?: return AccountDeletionRecoveryMarkerPreparation.Blocked
                if (current.recoveryActorId == actorId) {
                    current.also {
                        durabilityVerified = fallbackMarker.create(it)
                    }
                } else {
                    current.copy(recoveryActorId = actorId).also {
                        durabilityVerified = fallbackMarker.update(it)
                    }
                }
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> return AccountDeletionRecoveryMarkerPreparation.Blocked
        }
        val readback = fallbackMarker.read()
        if (
            durabilityVerified &&
            readback is AndroidAccountDeletionFallbackMarker.State.Present &&
            readback.record == expected
        ) {
            startupFallbackState =
                AndroidAccountDeletionFallbackMarker.State.Present(expected)
            fallbackPresentAtStartup = true
            return AccountDeletionRecoveryMarkerPreparation.Ready(expected)
        }
        if (
            readback is AndroidAccountDeletionFallbackMarker.State.Present &&
            readback.record == expected
        ) {
            return AccountDeletionRecoveryMarkerPreparation.RetryablePresent(expected)
        }
        return if (readback is AndroidAccountDeletionFallbackMarker.State.Absent) {
            AccountDeletionRecoveryMarkerPreparation.Absent
        } else {
            AccountDeletionRecoveryMarkerPreparation.Blocked
        }
    }

    fun prepareIntentAuthority():
        AccountDeletionAuthorityPreparation {
        effects.blockSpeechOutputStorageForAccountDeletion()
        return when (
            runCatching {
                preparedConfirmationRecovery
                    .recoverPreparedConfirmation()
            }.getOrDefault(PreparedConfirmationRecoveryResult.RETRY_REQUIRED)
        ) {
            PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED -> {
                runCatching { intentFence.engage() }
                val snapshot = dualAuthority.startupState()
                authorityAtStartup = snapshot
                AccountDeletionAuthorityPreparation(
                    confirmed =
                        snapshot.fileState ==
                            AccountDeletionIntentAuthorityState.Confirmed,
                    retryable =
                        snapshot.fileState !is
                            AccountDeletionIntentAuthorityState.FailClosed,
                    failureReason =
                        (snapshot.fileState as?
                            AccountDeletionIntentAuthorityState.FailClosed)?.reason,
                )
            }
            PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED -> {
                val confirmation = dualAuthority.confirm()
                authorityAtStartup = confirmation.snapshot
                val fileState = confirmation.snapshot.fileState
                AccountDeletionAuthorityPreparation(
                    confirmed =
                        confirmation.decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED,
                    retryable =
                        fileState !is AccountDeletionIntentAuthorityState.FailClosed,
                    failureReason =
                        (fileState as? AccountDeletionIntentAuthorityState.FailClosed)
                            ?.reason,
                )
            }
            PreparedConfirmationRecoveryResult.RETRY_REQUIRED ->
                AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = true,
                    failureReason = null,
                )
            PreparedConfirmationRecoveryResult.REJECTED -> {
                val snapshot = dualAuthority.startupState()
                authorityAtStartup = snapshot
                val durableReason =
                    (snapshot.fileState as?
                        AccountDeletionIntentAuthorityState.FailClosed)?.reason
                AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = false,
                    failureReason =
                        durableReason
                            ?: "deletion_intent_prepared_recovery_rejected",
                )
            }
        }
    }

    fun preparePendingJournal(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    ): AccountDeletionPendingJournalPreparation {
        if (
            session.gatewayBaseUrl != trustedGatewayOrigin ||
            markerRecord.gatewayOrigin != trustedGatewayOrigin
        ) return AccountDeletionPendingJournalPreparation.Conflict
        return try {
            val rawJournal = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_JOURNAL,
                null,
            )
            val failClosedMarker = sensitivePrefs.getBoolean(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                false,
            )
            val actorHash = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_ACTOR_HASH,
                null,
            )
            val terminalReason = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                null,
            )
            if (rawJournal != null) {
                val current = AccountDeletionJournalCodec.decodeOrNull(rawJournal)
                    ?: return AccountDeletionPendingJournalPreparation.Conflict
                val expected = AccountDeletionJournal.pending(
                    gatewayOrigin = trustedGatewayOrigin,
                    installationId = markerRecord.installationId,
                    requestId = markerRecord.identity.requestId,
                    requestedAt = current.requestedAt,
                    clientRevision = markerRecord.clientRevision,
                )
                return if (
                    current == expected &&
                    rawJournal == AccountDeletionJournalCodec.encode(current) &&
                    failClosedMarker &&
                    terminalReason == null &&
                    actorHash == markerRecord.identity.actorHash
                ) {
                    AccountDeletionPendingJournalPreparation.Ready(current)
                } else {
                    AccountDeletionPendingJournalPreparation.Conflict
                }
            }
            if (
                failClosedMarker ||
                actorHash != null ||
                terminalReason != null
            ) {
                return AccountDeletionPendingJournalPreparation.Conflict
            }
            val pending = AccountDeletionJournal.pending(
                gatewayOrigin = trustedGatewayOrigin,
                installationId = markerRecord.installationId,
                requestId = markerRecord.identity.requestId,
                requestedAt = java.time.Instant.now().toString(),
                clientRevision = markerRecord.clientRevision,
            )
            val serialized = AccountDeletionJournalCodec.encode(pending)
            val committed = sensitivePrefs.edit()
                .putString(PREF_ACCOUNT_DELETION_JOURNAL, serialized)
                .putBoolean(PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER, true)
                .putString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    markerRecord.identity.actorHash,
                )
                .remove(PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON)
                .commit()
            val exactReadback =
                sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_JOURNAL,
                    null,
                ) == serialized &&
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    ) == markerRecord.identity.actorHash &&
                    !sensitivePrefs.contains(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                    )
            if (committed && exactReadback) {
                AccountDeletionPendingJournalPreparation.Ready(pending)
            } else {
                AccountDeletionPendingJournalPreparation.Retry
            }
        } catch (_: RuntimeException) {
            AccountDeletionPendingJournalPreparation.Retry
        }
    }

    fun readFallbackAtStartup() {
        val state =
            try {
                fallbackMarker.read()
            } catch (_: RuntimeException) {
                AndroidAccountDeletionFallbackMarker.State.Unavailable
            }
        startupFallbackState = state
        fallbackPresentAtStartup =
            state !is AndroidAccountDeletionFallbackMarker.State.Absent
    }

    /** The accepted evidence tuple supplies actor ownership even after reporter/session cleanup. */
    fun evidenceActorHashOrNull(journal: AccountDeletionJournal, digest: String): String? = runCatching {
        val record = (fallbackMarker.read() as? AndroidAccountDeletionFallbackMarker.State.Present)?.record
            ?: return@runCatching null
        if (record.identity.requestId != journal.requestId || record.gatewayOrigin != journal.gatewayOrigin ||
            record.installationId != journal.installationId || record.clientRevision != journal.clientRevision ||
            record.accountGeneration != journal.accountGeneration ||
            record.tombstoneId != journal.tombstoneId || record.requestReceiptSha256 != journal.requestReceiptSha256 ||
            record.evidenceSha256 != digest || journal.deviceEvidenceSha256 != digest ||
            record.evidenceId != journal.deviceEvidenceId ||
            record.evidenceExpectedStatusRevision != journal.deviceEvidenceExpectedStatusRevision ||
            record.evidenceResult != journal.deviceEvidenceResult ||
            record.evidenceCompletedAt != journal.deviceEvidenceCompletedAt ||
            !Regex("[0-9a-f]{64}").matches(record.identity.actorHash) ||
            sensitivePrefs.getString(PREF_ACCOUNT_DELETION_ACTOR_HASH, null) != record.identity.actorHash) return@runCatching null
        val actorId = record.recoveryActorId
        if (actorId != null && sha256Hex(actorId.toByteArray(Charsets.UTF_8)) != record.identity.actorHash) return@runCatching null
        record.identity.actorHash
    }.getOrNull()

    fun preparedRecoveryRecordOrNull():
        AndroidAccountDeletionFallbackMarker.Record? {
        val record = preparedRecoveryCandidateOrNull() ?: return null
        val actorId = record.recoveryActorId ?: return null
        return record.takeIf {
            sha256Hex(actorId.toByteArray(Charsets.UTF_8)) ==
                record.identity.actorHash
        }
    }

    fun preparedRecoveryCandidateOrNull():
        AndroidAccountDeletionFallbackMarker.Record? {
        val record =
            (startupFallbackState
                as? AndroidAccountDeletionFallbackMarker.State.Present)
                ?.record
                ?: return null
        return record.takeIf {
            it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                it.gatewayOrigin == configuredGatewayOriginOrNull()
        }
    }

    fun reconcileFallbackAtStartup() {
        val state = startupFallbackState
        if (!fallbackPresentAtStartup) return
        effects.applyRuntimeFence()
        when (state) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> Unit
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                val rawJournal = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_JOURNAL,
                    null,
                )
                val journalRequestId =
                    rawJournal?.let { raw ->
                        runCatching {
                            JSONObject(raw).optString("request_id")
                        }.getOrNull()
                    }
                val persistedActorHash =
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    )
                val exactTuple =
                    journalRequestId == state.identity.requestId &&
                        persistedActorHash == state.identity.actorHash
                val durablePreJournalRecovery =
                    state.record == preparedRecoveryCandidateOrNull() &&
                        authorityAtStartup.fileState !is
                        AccountDeletionIntentAuthorityState.FailClosed &&
                        rawJournal == null &&
                        persistedActorHash == null
                remoteResumeBlocked =
                    !exactTuple && !durablePreJournalRecovery
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> {
                remoteResumeBlocked = true
            }
        }
    }

    fun persistCurrentJournal(): Boolean {
        val journal = stateMachine().snapshotOrNull() ?: return false
        return persistJournal(journal)
    }

    fun persistJournal(
        journal: AccountDeletionJournal,
    ): Boolean =
        sensitivePrefs.edit()
            .putString(
                PREF_ACCOUNT_DELETION_JOURNAL,
                AccountDeletionJournalCodec.encode(journal),
            )
            .commit()

    private fun sha256Hex(bytes: ByteArray): String {
        return hexEncoded(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
    private fun hexEncoded(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val PREF_ACCOUNT_DELETION_JOURNAL = "account_deletion_journal_v2"
        const val PREF_ACCOUNT_DELETION_ACTOR_HASH = "account_deletion_actor_hash_v2"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER = "account_deletion_fail_closed_v1"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON = "account_deletion_fail_closed_reason_v1"
    }
}

internal interface AccountDeletionRecoveryEffects {
    fun activityCallbackAllowed(): Boolean
    fun blockSpeechOutputStorageForAccountDeletion()
    fun blockPrivacyForAuthority()
    fun applyRuntimeFence(): Boolean
}
