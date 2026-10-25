package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.dispatchAndroidAccountDeletionResume
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController

/** Builds, validates and replays canonical evidence; the process StateMachine owns its durable state. */
internal class AccountDeletionEvidenceController(
    private val store: AccountDeletionRecoveryStore,
    private val stages: AccountDeletionWorkerStages,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val cleanupExecutor: () -> Executor,
    private val queries: AccountDeletionEvidenceQueries,
    private val effects: AccountDeletionEvidenceEffects,
    private val progress: AccountDeletionEvidenceProgress,
    private val network: AccountDeletionNetworkEntry,
    private val client: AndroidPrivacyDeletionClient,
) : AccountDeletionProgressEvidence {
    fun handleRevisionConflict(
        responseBody: String,
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val before = stateMachine().snapshotOrNull() ?: return false
        val status = validatedAccountDeletionStatusOrNull(
            body = responseBody,
            expectedInstallationId = before.installationId,
            expectedRequestId = before.requestId,
        ) ?: return false
        when (stateMachine().apply(workerAttempt, status)) {
            AccountDeletionApplyResult.APPLIED,
            AccountDeletionApplyResult.DUPLICATE,
            AccountDeletionApplyResult.STALE_IGNORED,
            -> Unit
            AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
            AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
            -> {
                stages.terminal(
                    workerAttempt,
                    stateMachine().failureReasonOrNull(),
                ) ?: return true
                effects.invalidateSpeechStorage()
                effects.failClosedIntegratedConsent()
                effects.postUiRefresh()
                return true
            }
        }
        val current = stateMachine().snapshotOrNull() ?: return false
        val journalPersisted =
            stages.durable(workerAttempt) {
                it == current && store.persistJournal(current)
            } ?: return true
        if (!journalPersisted) {
            progress.postFailure(
                "device_evidence_rebase_storage",
                workerAttempt,
            )
            return true
        }
        if (current.deviceEvidenceAcknowledged) {
            progress.scheduleAfterStatus(current)
            effects.postUiRefresh()
            return true
        }
        val priorExpected = current.deviceEvidenceExpectedStatusRevision
            ?: return false
        if (current.serverRevision <= priorExpected) return false
        scheduleRebase(current)
        effects.postUiRefresh()
        return true
    }

    fun scheduleRebase(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = stages.begin(journal) ?: return
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                var markerState: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                stages.durable(workerAttempt) {
                    markerState = store.fallbackMarker.read()
                    true
                } ?: return@execute
                val record =
                    (markerState
                        as? AndroidAccountDeletionFallbackMarker.State.Present)
                        ?.record
                if (
                    record == null ||
                    record.identity.requestId != journal.requestId ||
                    record.installationId != journal.installationId ||
                    record.phase !=
                    AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING ||
                    record.evidenceExpectedStatusRevision == null ||
                    journal.serverRevision <= record.evidenceExpectedStatusRevision ||
                    record.evidenceResult == null ||
                    record.evidenceCompletedAt == null
                ) {
                    progress.postFailure(
                        "device_evidence_rebase_linkage",
                        workerAttempt,
                    )
                    return@execute
                }
                val evidenceWithoutHash = DeviceDeletionEvidence(
                    requestId = journal.requestId,
                    tombstoneId = journal.tombstoneId!!,
                    requestReceiptSha256 = journal.requestReceiptSha256!!,
                    installationId = journal.installationId,
                    evidenceId = newEvidenceId(),
                    clientRevision = journal.clientRevision,
                    expectedStatusRevision = journal.serverRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                    evidenceSha256 = "0".repeat(64),
                )
                val evidence = evidenceWithoutHash.copy(
                    evidenceSha256 =
                        deviceDeletionEvidenceSha256(evidenceWithoutHash),
                )
                val next = record.copy(
                    statusRevision = journal.serverRevision,
                    evidenceId = evidence.evidenceId,
                    evidenceSha256 = evidence.evidenceSha256,
                    evidenceExpectedStatusRevision =
                        evidence.expectedStatusRevision,
                )
                val markerUpdated =
                    stages.durable(workerAttempt) {
                        store.fallbackMarker.update(next)
                    } ?: return@execute
                if (
                    !stages.allowed(workerAttempt) ||
                    !markerUpdated ||
                    !stateMachine().rebaseDeviceEvidence(
                        workerAttempt,
                        evidenceId = evidence.evidenceId,
                        evidenceSha256 = evidence.evidenceSha256,
                        expectedStatusRevision = evidence.expectedStatusRevision,
                        result = evidence.result,
                        completedAt = evidence.completedAt,
                    )
                ) {
                    progress.postFailure(
                        "device_evidence_rebase_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                val current = stateMachine().snapshotOrNull()
                    ?: return@execute
                val journalPersisted =
                    stages.durable(workerAttempt) {
                        it == current && store.persistJournal(current)
                    } ?: return@execute
                if (!journalPersisted) {
                    progress.postFailure(
                        "device_evidence_rebase_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                submitPendingAllowed(next, current)
            }
        } catch (_: RejectedExecutionException) {
            progress.postFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    override fun newEvidenceId(): String {
        val random = ByteArray(24)
        SecureRandom().nextBytes(random)
        return "device_evidence_" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    override fun submitPending(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        try {
            cleanupExecutor().execute {
                dispatchAccountDeletionNetworkEntry(
                    journal = stateMachine().snapshotOrNull(),
                    keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed,
                    dispatch = {
                        submitPendingAllowed(record, journal)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

    override fun submitPendingAllowed(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        val currentAtEntry = stateMachine().snapshotOrNull() ?: return
        val workerAttempt =
            stages.begin(currentAtEntry) ?: return
        val trustedGatewayOrigin = queries.trustedOrigin()
        val markerEvidenceMatchesJournal =
            journal.deviceEvidenceId == record.evidenceId &&
                journal.deviceEvidenceSha256 == record.evidenceSha256 &&
                journal.deviceEvidenceExpectedStatusRevision ==
                record.evidenceExpectedStatusRevision &&
                journal.deviceEvidenceResult == record.evidenceResult &&
                journal.deviceEvidenceCompletedAt == record.evidenceCompletedAt
        val linkedJournal = when {
            markerEvidenceMatchesJournal -> journal
            journal.deviceEvidenceId == null &&
                record.evidenceId != null &&
                record.evidenceSha256 != null &&
                record.evidenceExpectedStatusRevision != null &&
                record.evidenceResult != null &&
                record.evidenceCompletedAt != null &&
                stateMachine().recordDeviceEvidence(
                    workerAttempt,
                    evidenceId = record.evidenceId,
                    evidenceSha256 = record.evidenceSha256,
                    expectedStatusRevision = record.evidenceExpectedStatusRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                ) -> {
                val current = stateMachine().snapshotOrNull()
                    ?: return
                val persisted =
                    stages.durable(workerAttempt) {
                        it == current && store.persistJournal(current)
                    } ?: return
                current.takeIf { persisted }
            }
            !journal.deviceEvidenceAcknowledged &&
                journal.deviceEvidenceExpectedStatusRevision != null &&
                record.evidenceId != null &&
                record.evidenceSha256 != null &&
                record.evidenceExpectedStatusRevision != null &&
                record.evidenceExpectedStatusRevision >
                journal.deviceEvidenceExpectedStatusRevision &&
                record.evidenceResult != null &&
                record.evidenceCompletedAt != null &&
                stateMachine().rebaseDeviceEvidence(
                    workerAttempt,
                    evidenceId = record.evidenceId,
                    evidenceSha256 = record.evidenceSha256,
                    expectedStatusRevision = record.evidenceExpectedStatusRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                ) -> {
                val current = stateMachine().snapshotOrNull()
                    ?: return
                val persisted =
                    stages.durable(workerAttempt) {
                        it == current && store.persistJournal(current)
                    } ?: return
                current.takeIf { persisted }
            }
            else -> null
        }
        if (trustedGatewayOrigin == null || linkedJournal == null) {
            progress.postFailure(
                "device_evidence_linkage",
                workerAttempt,
            )
            return
        }
        val evidence = runCatching {
            DeviceDeletionEvidence(
                requestId = linkedJournal.requestId,
                tombstoneId = linkedJournal.tombstoneId!!,
                requestReceiptSha256 = linkedJournal.requestReceiptSha256!!,
                installationId = linkedJournal.installationId,
                evidenceId = record.evidenceId!!,
                clientRevision = linkedJournal.clientRevision,
                expectedStatusRevision = record.evidenceExpectedStatusRevision!!,
                result = record.evidenceResult!!,
                completedAt = record.evidenceCompletedAt!!,
                evidenceSha256 = record.evidenceSha256!!,
            )
        }.getOrNull()
        if (evidence == null) {
            progress.postFailure(
                "device_evidence_linkage",
                workerAttempt,
            )
            return
        }
        network.startCall(
            callFactory = {
                client.submitDeviceDeletionEvidenceCall(
                    journal = linkedJournal,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    accessSecret = record.accessSecret,
                    evidence = evidence,
                )
            },
            purpose = AccountDeletionCallPurpose.EVIDENCE,
        )
    }

}
internal interface AccountDeletionEvidenceQueries { fun trustedOrigin(): String? }
internal interface AccountDeletionEvidenceEffects {
    fun keepPrivacyFenceClosed()
    fun invalidateSpeechStorage()
    fun failClosedIntegratedConsent()
    fun postUiRefresh()
}
internal interface AccountDeletionEvidenceProgress {
    fun scheduleAfterStatus(journal: AccountDeletionJournal)
    fun postFailure(reason: String, workerAttempt: AccountDeletionWorkerAttempt)
}
internal interface AccountDeletionProgressEvidence {
    fun newEvidenceId(): String
    fun submitPending(record: AndroidAccountDeletionFallbackMarker.Record, journal: AccountDeletionJournal)
    fun submitPendingAllowed(record: AndroidAccountDeletionFallbackMarker.Record, journal: AccountDeletionJournal)
}
