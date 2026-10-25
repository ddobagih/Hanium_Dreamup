package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*

/** One process StateMachine lease and the same local admission slot through actual SDK completion. */
internal class AccountDeletionNetworkController(
    private val operation: AccountDeletionOperationAuthority,
    private val store: AccountDeletionRecoveryStore,
    private val actor: AccountDeletionRecoveryActorController,
    private val stages: AccountDeletionWorkerStages,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val cleanupExecutor: () -> Executor,
    private val networkExecutor: () -> Executor,
    private val queries: AccountDeletionNetworkQueries,
    private val effects: AccountDeletionNetworkEffects,
    private val progress: AccountDeletionNetworkProgress,
    private val client: AndroidPrivacyDeletionClient,
) : AccountDeletionNetworkEntry {
    fun refreshStatus(
        workerAttempt: AccountDeletionWorkerAttempt?,
    ) {
        val journal = stateMachine().snapshotOrNull() ?: return
        val currentAttempt =
            workerAttempt ?: stages.begin(journal) ?: return
        if (!stages.allowed(currentAttempt)) return
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(currentAttempt)) return@execute
                dispatchAccountDeletionNetworkEntry(
                    journal = journal,
                    keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed,
                    dispatch = {
                        refreshStatusAllowed(journal, currentAttempt)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

    fun refreshStatus() {
        refreshStatus(workerAttempt = null)
    }

    fun refreshStatusAllowed(
        journal: AccountDeletionJournal,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        if (
            operation.requestInFlight ||
            store.remoteResumeBlocked ||
            queries.localPurgeRequestId() == journal.requestId
        ) return
        val trustedGatewayOrigin = queries.trustedOrigin()
        if (
            trustedGatewayOrigin == null ||
            journal.gatewayOrigin != trustedGatewayOrigin
        ) {
            stages.terminal(
                workerAttempt,
                "account_deletion_gateway_origin_conflict",
            ) ?: return
            effects.invalidateSpeechStorage()
            effects.failClosedIntegratedConsent()
            effects.postUiRefresh()
            return
        }
        var markerState: AndroidAccountDeletionFallbackMarker.State =
            AndroidAccountDeletionFallbackMarker.State.Unavailable
        stages.durable(workerAttempt) {
            markerState = store.fallbackMarker.read()
            true
        } ?: return
        val marker = markerState
            as? AndroidAccountDeletionFallbackMarker.State.Present
        if (
            marker == null ||
            marker.record.identity.requestId != journal.requestId ||
            marker.record.gatewayOrigin != journal.gatewayOrigin ||
            marker.record.installationId != journal.installationId
        ) {
            stages.terminal(
                workerAttempt,
                "account_deletion_fallback_marker_conflict",
            ) ?: return
            effects.invalidateSpeechStorage()
            effects.failClosedIntegratedConsent()
            store.setRemoteResumeBlocked(true)
            effects.postUiRefresh()
            return
        }
        if (journal.serverRevision == 0L) {
            val recoveryBinding =
                actor.rev0RecoveryBindingOrNull()
                    ?.takeIf { it.journal == journal }
            val recoverySession = recoveryBinding?.let {
                actor.recoveryGatewaySessionOrNull()
            }
            if (recoveryBinding != null && recoverySession != null) {
                startCall(
                    callFactory = {
                        client.recoverDeletionRequestAcceptOrReplayCall(
                            gatewayBaseUrl = journal.gatewayOrigin,
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            session = recoverySession,
                            installationId = journal.installationId,
                            accessSecret = marker.record.accessSecret,
                            requestId = journal.requestId,
                            clientRevision = journal.clientRevision,
                        )
                    },
                    purpose = AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY,
                )
            } else {
                startCall(
                    callFactory = {
                        client.replayDeletionRequestCall(
                            journal = journal,
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            accessSecret = marker.record.accessSecret,
                        )
                    },
                    purpose = AccountDeletionCallPurpose.CAPABILITY_REPLAY,
                )
            }
        } else {
            startCall(
                callFactory = {
                    client.fetchDeletionStatusCall(
                        journal = journal,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        accessSecret = marker.record.accessSecret,
                    )
                },
                purpose = AccountDeletionCallPurpose.STATUS,
            )
        }
    }

    override fun startCall(
        callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>,
        purpose: AccountDeletionCallPurpose,
    ) {
        val journal = stateMachine().snapshotOrNull()
        dispatchAccountDeletionNetworkEntry(
            journal = journal,
            keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed,
            dispatch = {
                startCallAllowed(callFactory(), purpose)
            },
        )
    }

    fun startCallAllowed(
        call: CancellableNetworkCall<AccountDeletionStatus>,
        purpose: AccountDeletionCallPurpose,
    ) {
        val journal = stateMachine().snapshotOrNull()
        val workerAttempt = journal?.let(stages::begin)
        if (workerAttempt == null) {
            call.cancel()
            return
        }
        val networkLease = stateMachine().registerNetworkCall(
            workerAttempt,
            call::cancel,
        ) ?: run {
            call.cancel()
            effects.keepPrivacyFenceClosed()
            return
        }
        val generation = operation.admitCall(call)
        effects.postUiRefresh()
        try {
            networkExecutor().execute {
                if (!stateMachine().beginNetworkCall(networkLease)) {
                    stateMachine().releaseNetworkCall(networkLease)
                    call.cancel()
                    operation.completeCall(generation, call)
                    return@execute
                }
                val execution = executeAccountDeletionCall(call) {
                    val stateReleased =
                        stateMachine().releaseNetworkCall(networkLease)
                    val localReleased =
                        operation.completeCall(generation, call)
                    stateReleased && localReleased
                }
                when (execution) {
                    is AccountDeletionCallExecution.Succeeded -> {
                        val status = execution.value
                        scheduleCompletion(
                            networkLease = networkLease,
                            generation = generation,
                            call = call,
                            completion = completion@{
                            val applyResult =
                                stateMachine().completeNetworkCall(
                                    networkLease,
                                    status,
                                    acknowledgesDeviceEvidence =
                                        purpose == AccountDeletionCallPurpose.EVIDENCE,
                                )
                            if (!operation.completeCall(generation, call)) {
                                return@completion
                            }
                            if (applyResult == null) {
                                effects.keepPrivacyFenceClosed()
                                return@completion
                            }
                            when (applyResult) {
                                AccountDeletionApplyResult.APPLIED,
                                AccountDeletionApplyResult.DUPLICATE,
                                -> {
                                    val snapshot =
                                        stateMachine().snapshotOrNull()
                                            ?: return@completion
                                    val journalPersisted =
                                        stages.durable(
                                            workerAttempt,
                                        ) {
                                            it == snapshot &&
                                                store.persistJournal(snapshot)
                                        } ?: return@completion
                                    if (!journalPersisted) {
                                        stages.terminal(
                                            workerAttempt,
                                            "account_deletion_journal_storage",
                                        ) ?: return@completion
                                        effects.invalidateSpeechStorage()
                                        effects.failClosedIntegratedConsent()
                                    } else {
                                        actor.completeRev0ReauthenticationAfterAcceptance(
                                            snapshot,
                                        )
                                        progress.scheduleAfterStatus(snapshot)
                                    }
                                }
                                AccountDeletionApplyResult.STALE_IGNORED -> Unit
                                AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
                                AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
                                -> {
                                    stages.terminal(
                                        workerAttempt,
                                        stateMachine().failureReasonOrNull(),
                                    ) ?: return@completion
                                    effects.invalidateSpeechStorage()
                                    effects.failClosedIntegratedConsent()
                                }
                            }
                            effects.postUiRefresh()
                            },
                        )
                    }
                    AccountDeletionCallExecution.Cancelled -> Unit
                    is AccountDeletionCallExecution.Failed -> {
                        val error = execution.error
                        scheduleCompletion(
                            networkLease = networkLease,
                            generation = generation,
                            call = call,
                            completion = completion@{
                            val stateReleased =
                                stateMachine().releaseNetworkCall(networkLease)
                            val localReleased =
                                operation.completeCall(generation, call)
                            if (!stateReleased || !localReleased) {
                                return@completion
                            }
                            if (!stages.allowed(workerAttempt)) {
                                return@completion
                            }
                            if (
                                purpose == AccountDeletionCallPurpose.CAPABILITY_REPLAY &&
                                error is AccountDeletionHttpException &&
                                error.statusCode == 404 &&
                                (
                                    actor.activateRev0Reauthentication(
                                        workerAttempt,
                                    ) ||
                                        actor.bindLegacyRev0RecoveryActor(
                                            workerAttempt,
                                        ) ||
                                        actor.handleLegacyRev0NotFound(
                                            workerAttempt,
                                        )
                                    )
                            ) {
                                effects.postUiRefresh()
                                if (actor.recoveryGatewaySessionOrNull() != null) {
                                    refreshStatus(workerAttempt)
                                }
                                return@completion
                            }
                            if (
                                purpose ==
                                AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY &&
                                error is AccountDeletionHttpException &&
                                (
                                    error is
                                    AccountDeletionReauthenticationRequiredException ||
                                        error.statusCode == 401 ||
                                        error.statusCode == 403
                                    ) &&
                                actor.retainRev0ReauthenticationAfterSessionFailure(
                                    workerAttempt,
                                )
                            ) {
                                actor.clearRev0RecoverySessionIfCurrent()
                                effects.postUiRefresh()
                                return@completion
                            }
                            if (
                                purpose == AccountDeletionCallPurpose.EVIDENCE &&
                                error is AccountDeletionHttpException &&
                                error.statusCode == 409 &&
                                progress.handleEvidenceRevisionConflict(
                                    error.errorBody,
                                    workerAttempt,
                                )
                            ) {
                                return@completion
                            }
                            val disposition = if (error is AccountDeletionHttpException) {
                                accountDeletionHttpFailureDisposition(
                                    statusCode = error.statusCode,
                                    body = error.errorBody,
                                )
                            } else {
                                AccountDeletionHttpFailureDisposition.Retry(
                                    "request_failed",
                                )
                            }
                            when (disposition) {
                                is AccountDeletionHttpFailureDisposition.TerminalConflict ->
                                    progress.postFailure(
                                        "terminal_conflict:${disposition.code}",
                                        workerAttempt,
                                    )
                                is AccountDeletionHttpFailureDisposition.Retry ->
                                    markRetry(
                                        disposition.code,
                                        workerAttempt,
                                        activateRev0Reauthentication =
                                            purpose == AccountDeletionCallPurpose.INITIAL &&
                                                error is AccountDeletionHttpException &&
                                                error.statusCode == 401 &&
                                                disposition.code == ACCOUNT_DELETION_REAUTHENTICATION_RETRY_CODE,
                                    )
                            }
                            },
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            val stateReleased =
                stateMachine().releaseNetworkCall(networkLease)
            call.cancel()
            val localReleased = operation.completeCall(generation, call)
            if (stateReleased && localReleased) {
                markRetry("executor_rejected", workerAttempt)
            } else {
                effects.keepPrivacyFenceClosed()
            }
        }
    }

    fun scheduleCompletion(
        networkLease: AccountDeletionNetworkCallLease,
        generation: Long,
        call: CancellableNetworkCall<AccountDeletionStatus>,
        completion: () -> Unit,
    ) {
        try {
            cleanupExecutor().execute(completion)
        } catch (_: RejectedExecutionException) {
            val stateReleased =
                stateMachine().releaseNetworkCall(networkLease)
            val localReleased = operation.completeCall(generation, call)
            if (stateReleased && localReleased) {
                effects.keepPrivacyFenceClosed()
            }
        }
    }

    fun markRetry(
        errorCode: String,
        workerAttempt: AccountDeletionWorkerAttempt,
        activateRev0Reauthentication: Boolean = false,
    ) {
        try {
            cleanupExecutor().execute {
                if (!stateMachine().markRetry(workerAttempt, errorCode)) {
                    effects.keepPrivacyFenceClosed()
                    return@execute
                }
                val current = stateMachine().snapshotOrNull() ?: return@execute
                val persisted = stages.durable(workerAttempt) {
                    it == current && store.persistJournal(current)
                } ?: return@execute
                if (!persisted) {
                    progress.postFailure(
                        "retry_journal_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                if (activateRev0Reauthentication) {
                    if (!stages.allowed(workerAttempt)) return@execute
                    actor.activateRev0Reauthentication(workerAttempt)
                }
                effects.postUiRefresh()
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

}
internal interface AccountDeletionNetworkQueries {
    fun trustedOrigin(): String?
    fun localPurgeRequestId(): String?
}
internal interface AccountDeletionNetworkEffects {
    fun keepPrivacyFenceClosed()
    fun invalidateSpeechStorage()
    fun failClosedIntegratedConsent()
    fun postUiRefresh()
}
internal interface AccountDeletionNetworkProgress {
    fun scheduleAfterStatus(journal: AccountDeletionJournal)
    fun handleEvidenceRevisionConflict(responseBody: String, workerAttempt: AccountDeletionWorkerAttempt): Boolean
    fun postFailure(errorCode: String, workerAttempt: AccountDeletionWorkerAttempt)
}
