package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.AndroidPrivacyDeletionClient
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController

/** Confirmation, durable PREPARED authority and exact canonical pending-request preparation. */
internal class AccountDeletionPreparationController(
    private val operation: AccountDeletionOperationAuthority,
    private val store: AccountDeletionRecoveryStore,
    private val actor: AccountDeletionRecoveryActorController,
    private val stages: AccountDeletionWorkerStages,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val resetController: () -> AccountDeletionInstallationResetController,
    private val cleanupExecutor: () -> Executor,
    private val callbacks: AccountDeletionCleanupCallbacks,
    private val queries: AccountDeletionPreparationQueries,
    private val effects: AccountDeletionPreparationEffects,
    private val network: AccountDeletionNetworkEntry,
    private val client: AndroidPrivacyDeletionClient,
) {
    private val installationKeyCall = AtomicReference<CancellableNetworkCall<Unit>?>()
    @Volatile var installationKeyPreparationState = AccountDeletionInstallationKeyPreparationState.UNAVAILABLE
        private set

    fun cancelInstallationKeyPreparation() { installationKeyCall.getAndSet(null)?.cancel() }

    fun onPrimaryClicked() {
        if (stateMachine().phase() == AccountDeletionPhase.COMPLETED) {
            resetController().resetAfterConfirmedDeletion()
            return
        }
        if (
            !stateMachine().requestConfirmation(
                activityLease(),
            )
        ) return
        effects.updateUi()
        effects.speak(
            "계정 삭제는 새 개인정보 처리를 즉시 막고 단말, 서버, 학습자료와 백업 삭제를 요청합니다. 확인 버튼을 다시 누르세요.",
        )
    }

    fun onCancelClicked() {
        if (
            !stateMachine().cancelConfirmation(
                activityLease(),
            )
        ) {
            if (stateMachine().durableConfirmationRecoveryRequired()) {
                effects.keepPrivacyFenceClosed()
                effects.speak(
                    "이미 저장된 삭제 확인은 취소할 수 없습니다. 삭제 요청 준비를 다시 시도하세요.",
                )
            }
            return
        }
        effects.updateUi()
        effects.speak("계정 삭제 확인을 취소했습니다.")
    }

    fun onConfirmClicked() {
        if (
            stateMachine().phase() !=
            AccountDeletionPhase.CONFIRM_REQUIRED
        ) return
        val recoveringDurableConfirmation =
            stateMachine().durableConfirmationRecoveryRequired()
        val capturedSession = if (recoveringDurableConfirmation) {
            actor.recoveryGatewaySessionOrNull()
        } else {
            queries.generalSession()
        }
        val capturedOrigin = queries.trustedOrigin()
        val capturedActorId = if (recoveringDurableConfirmation) {
            actor.recoveryActorIdOrNull()
        } else {
            queries.currentReporterActor()
        }
        if (
            capturedSession == null ||
            capturedOrigin == null ||
            capturedSession.gatewayBaseUrl != capturedOrigin ||
            capturedActorId == null ||
            capturedSession.actorId != capturedActorId
        ) {
            effects.status(
                if (recoveringDurableConfirmation) {
                    "accountDeletion=recovery_required:verified_login_required"
                } else {
                    "accountDeletion=not_accepted:verified_login_required"
                },
            )
            if (recoveringDurableConfirmation) effects.applyRuntimeFence()
            effects.updateUi()
            effects.speak(
                if (recoveringDurableConfirmation) {
                    "삭제 확인은 보존됐습니다. 현장 게이트웨이에 다시 로그인한 뒤 재시도하세요."
                } else {
                    "현장 게이트웨이에 먼저 로그인하세요."
                },
            )
            return
        }
        if (queries.sensitiveStorageBlocked()) {
            if (recoveringDurableConfirmation) {
                effects.applyRuntimeFence()
                effects.status(
                    "accountDeletion=recovery_required:sensitive_storage_blocked",
                )
                effects.updateUi()
                effects.speak(
                    "삭제 확인은 보존됐지만 보호 저장소를 사용할 수 없습니다. 저장소를 복구한 뒤 다시 시도하세요.",
                )
            } else {
                effects.status(
                    "accountDeletion=not_accepted:sensitive_storage_blocked",
                )
                effects.updateUi()
                effects.speak("보호 저장소를 사용할 수 없어 삭제 요청을 확인하지 않았습니다.")
            }
            return
        }
        val keyPreparation = if (recoveringDurableConfirmation) null else runCatching {
            queries.captureInstallationKeyPreparation(capturedSession)
        }.getOrNull()
        if (!effects.applyRuntimeFence()) {
            effects.status("accountDeletion=blocked:position_field_purge")
            effects.updateUi()
            return
        }
        val confirmationAttempt =
            stateMachine().beginPreparationWorkerAttempt(
                activityLease(),
            ) ?: run {
                if (recoveringDurableConfirmation) {
                    effects.keepPrivacyFenceClosed()
                }
                return
            }
        startConfirmationPreparation(
            trustedGatewayOrigin = capturedOrigin,
            session = capturedSession,
            actorId = capturedActorId,
            recoveringDurableConfirmation = recoveringDurableConfirmation,
            workerAttempt = confirmationAttempt,
            installationKeyPreparation = keyPreparation,
        )
    }

    fun startConfirmationPreparation(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        actorId: String,
        recoveringDurableConfirmation: Boolean,
        workerAttempt: AccountDeletionWorkerAttempt,
        installationKeyPreparation: (() -> CancellableNetworkCall<Unit>)? = null,
    ) {
        val generation = operation.beginPreparation()
        operation.updateCurrentPreparation(generation) {
            installationKeyPreparationState = if (installationKeyPreparation != null && !recoveringDurableConfirmation)
                AccountDeletionInstallationKeyPreparationState.UNCONFIRMED
            else AccountDeletionInstallationKeyPreparationState.UNAVAILABLE
        }
        effects.postUiRefresh()
        try {
            cleanupExecutor().execute {
                if (!operation.isCurrentPreparation(generation) || !stages.allowed(workerAttempt)) {
                    operation.completePreparation(generation)
                    return@execute
                }
                var preparation: AccountDeletionRecoveryMarkerPreparation =
                    AccountDeletionRecoveryMarkerPreparation.Blocked
                val prepared = stages.durable(workerAttempt) {
                    preparation = store.prepareRecoveryMarker(
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        actorId = actorId,
                    )
                    preparation is AccountDeletionRecoveryMarkerPreparation.Ready
                }
                if (prepared == null) {
                    operation.completePreparation(generation)
                    return@execute
                }
                if (prepared && preparation is AccountDeletionRecoveryMarkerPreparation.Ready &&
                    installationKeyPreparation != null && !recoveringDurableConfirmation) {
                    var ownedCall: CancellableNetworkCall<Unit>? = null
                    try {
                        val call = installationKeyPreparation()
                        ownedCall = call
                        var current = false
                        operation.updateCurrentPreparation(generation) {
                            if (stages.allowed(workerAttempt) && !callbacks.destroyed) {
                                installationKeyCall.set(call)
                                current = true
                            }
                        }
                        if (!current) call.cancel()
                        call.execute()
                        publishInstallationKeyState(generation, workerAttempt,
                            AccountDeletionInstallationKeyPreparationState.REGISTERED)
                    } catch (_: Exception) {
                        publishInstallationKeyState(generation, workerAttempt,
                            AccountDeletionInstallationKeyPreparationState.UNCONFIRMED)
                    } finally { ownedCall?.let { installationKeyCall.compareAndSet(it, null) } }
                    // Registration is additional installation proof. It never removes deletion rights.
                    if (!stages.allowed(workerAttempt)) {
                        operation.completePreparation(generation)
                        return@execute
                    }
                }
                postRecoveryMarkerPreparation(
                    generation = generation,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    session = session,
                    recoveringDurableConfirmation = recoveringDurableConfirmation,
                    workerAttempt = workerAttempt,
                    preparation = preparation,
                )
            }
        } catch (_: RejectedExecutionException) {
            operation.completePreparation(generation)
            if (recoveringDurableConfirmation) {
                effects.keepPrivacyFenceClosed()
            }
            effects.status(
                if (recoveringDurableConfirmation) {
                    "accountDeletion=recovery_required:cleanup_executor_rejected"
                } else {
                    "accountDeletion=not_accepted:cleanup_executor_rejected"
                },
            )
            effects.updateUi()
        }
    }

    private fun publishInstallationKeyState(
        generation: Long,
        workerAttempt: AccountDeletionWorkerAttempt,
        state: AccountDeletionInstallationKeyPreparationState,
    ) {
        operation.updateCurrentPreparation(generation) {
            if (stages.allowed(workerAttempt) && !callbacks.destroyed) installationKeyPreparationState = state
        }
    }

    fun postRecoveryMarkerPreparation(
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        recoveringDurableConfirmation: Boolean,
        workerAttempt: AccountDeletionWorkerAttempt,
        preparation: AccountDeletionRecoveryMarkerPreparation,
    ) {
        val activityToken = callbacks.activityToken
        callbacks.post {
            if (
                callbacks.destroyed ||
                activityToken != callbacks.activityToken ||
                !operation.isCurrentPreparation(generation) ||
                !stages.allowed(workerAttempt)
            ) return@post
            when (preparation) {
                is AccountDeletionRecoveryMarkerPreparation.Ready -> {
                    if (
                        !stateMachine()
                            .retainDurableConfirmationRecovery(workerAttempt)
                    ) {
                        operation.completePreparation(generation)
                        effects.keepPrivacyFenceClosed()
                        return@post
                    }
                    effects.applyRuntimeFence()
                    store.setRemoteResumeBlocked(false)
                    val recoveryAttempt =
                        stateMachine().beginPreparationWorkerAttempt(
                            activityLease(),
                        ) ?: run {
                            operation.completePreparation(generation)
                            effects.keepPrivacyFenceClosed()
                            return@post
                        }
                    startPreparedAuthorityConfirmation(
                        generation = generation,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        markerRecord = preparation.record,
                        workerAttempt = recoveryAttempt,
                    )
                }
                is AccountDeletionRecoveryMarkerPreparation.RetryablePresent -> {
                    operation.completePreparation(generation)
                    store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(
                            preparation.record,
                        ))
                    store.observeFallbackPresence(true)
                    effects.applyRuntimeFence()
                    retainPreparationRecovery(
                        reason = "fallback_marker_durability_retry",
                        workerAttempt = workerAttempt,
                        announcement =
                            "삭제 복구 정보가 보존됐지만 저장소 동기화를 다시 확인해야 합니다. 다시 시도하세요.",
                    )
                }
                AccountDeletionRecoveryMarkerPreparation.Absent -> {
                    operation.completePreparation(generation)
                    if (recoveringDurableConfirmation) {
                        retainPreparationRecovery(
                            reason = "fallback_marker_storage",
                            workerAttempt = workerAttempt,
                            announcement =
                                "삭제 확인은 보존됐지만 복구 정보를 저장하지 못했습니다. 다시 시도하세요.",
                        )
                    } else {
                        if (
                            !stateMachine().abortPreparationRecovery(
                                activityLease(),
                            )
                        ) {
                            effects.keepPrivacyFenceClosed()
                            return@post
                        }
                        effects.invalidateSpeechStorage()
                        effects.resetIntegratedConsentForNewEnrollment()
                        effects.resetReportConsentForNewEnrollment()
                        effects.reapplyActorBoundConsent()
                        effects.resumePrivacyControlAfterStartup()
                        effects.status(
                            "accountDeletion=not_accepted:fallback_marker_storage",
                        )
                        effects.updateUi()
                        effects.speak(
                            "삭제 요청 복구 정보를 저장하지 못해 삭제 요청을 확인하지 않았습니다.",
                        )
                    }
                }
                AccountDeletionRecoveryMarkerPreparation.Blocked -> {
                    operation.completePreparation(generation)
                    store.setRemoteResumeBlocked(true)
                    effects.applyRuntimeFence()
                    if (recoveringDurableConfirmation) {
                        retainPreparationRecovery(
                            reason = "fallback_marker_storage",
                            workerAttempt = workerAttempt,
                            announcement =
                                "삭제 확인은 보존됐지만 복구 저장소를 신뢰할 수 없습니다. 저장소를 복구한 뒤 다시 시도하세요.",
                        )
                    } else {
                        effects.status(
                            "accountDeletion=blocked:fallback_marker_storage",
                        )
                        effects.updateUi()
                        effects.speak(
                            "삭제 복구 저장소를 신뢰할 수 없어 개인정보 기능을 잠갔습니다.",
                        )
                    }
                }
            }
        }
    }

    fun startPreparedAuthorityConfirmation(
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        try {
            cleanupExecutor().execute {
                var preparation = AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = true,
                    failureReason = null,
                )
                val confirmed = stages.durable(workerAttempt) {
                    preparation = store.prepareIntentAuthority()
                    preparation.confirmed
                }
                if (confirmed == null) {
                    operation.completePreparation(generation)
                    return@execute
                }
                val terminalFailureReason = if (
                    !preparation.confirmed && !preparation.retryable
                ) {
                    preparation.failureReason
                        ?: "deletion_intent_prepared_recovery_rejected"
                } else {
                    null
                }
                if (
                    terminalFailureReason != null &&
                    stages.terminal(
                        attempt = workerAttempt,
                        errorCode = terminalFailureReason,
                    ) != true
                ) {
                    operation.completePreparation(generation)
                    return@execute
                }
                val activityToken = callbacks.activityToken
                callbacks.post {
                    if (
                        callbacks.destroyed ||
                        activityToken != callbacks.activityToken ||
                        !operation.isCurrentPreparation(generation) ||
                        (
                            terminalFailureReason == null &&
                                !stages.allowed(workerAttempt)
                            ) ||
                        (
                            terminalFailureReason != null &&
                                !stateMachine().activityLeaseIsCurrent(
                                    activityLease(),
                                )
                            )
                    ) return@post
                    operation.completePreparation(generation)
                    when {
                        preparation.confirmed -> {
                            startPreparation(
                                trustedGatewayOrigin = trustedGatewayOrigin,
                                session = session,
                                markerRecord = markerRecord,
                                workerAttempt = workerAttempt,
                            )
                        }
                        preparation.retryable -> {
                            retainPreparationRecovery(
                                reason = "deletion_intent_authority_retry",
                                workerAttempt = workerAttempt,
                                announcement =
                                    "삭제 요청 신원은 보존됐지만 삭제 확인 저장을 완료하지 못했습니다. 다시 시도하세요.",
                            )
                        }
                        else -> {
                            resetController().terminalCleanup.invalidate()
                            effects.invalidateSpeechStorage()
                            effects.failClosedIntegratedConsent()
                            effects.blockReportConsent()
                            store.setRemoteResumeBlocked(true)
                            effects.status(
                                "accountDeletion=fail_closed:$terminalFailureReason",
                            )
                            effects.updateUi()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            operation.completePreparation(generation)
            retainPreparationRecovery(
                reason = "cleanup_executor_rejected",
                workerAttempt = workerAttempt,
                announcement =
                    "삭제 확인은 보존됐지만 저장 작업을 시작하지 못했습니다. 다시 시도하세요.",
            )
        }
    }

    fun startPreparation(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        val generation = operation.beginPreparation()
        effects.postUiRefresh()
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) {
                    operation.completePreparation(generation)
                    return@execute
                }
                var preparation: AccountDeletionPendingJournalPreparation =
                    AccountDeletionPendingJournalPreparation.Retry
                val pendingPersisted =
                    stages.durable(workerAttempt) {
                        preparation = store.preparePendingJournal(
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            session = session,
                            markerRecord = markerRecord,
                        )
                        preparation is AccountDeletionPendingJournalPreparation.Ready
                    }
                if (pendingPersisted == null) {
                    operation.completePreparation(generation)
                    return@execute
                }
                if (!pendingPersisted) {
                    postPreparationFailure(
                        workerAttempt,
                        generation,
                        markerRecord.identity.requestId,
                        if (
                            preparation is
                            AccountDeletionPendingJournalPreparation.Conflict
                        ) {
                            "pending_journal_conflict"
                        } else {
                            "pending_journal_storage"
                        },
                    )
                    return@execute
                }
                val pendingJournal =
                    (preparation as?
                        AccountDeletionPendingJournalPreparation.Ready)
                        ?.journal
                        ?: run {
                            postPreparationFailure(
                                workerAttempt,
                                generation,
                                markerRecord.identity.requestId,
                                "pending_journal_storage",
                            )
                            return@execute
                        }
                if (!stages.allowed(workerAttempt)) {
                    operation.completePreparation(generation)
                    return@execute
                }
                postPendingJournalReady(
                    workerAttempt = workerAttempt,
                    generation = generation,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    session = session,
                    pendingJournal = pendingJournal,
                    markerRecord = markerRecord,
                )
            }
        } catch (_: RejectedExecutionException) {
            postPreparationFailure(
                workerAttempt,
                generation,
                "unknown_account_deletion_request",
                "cleanup_executor_rejected",
            )
        }
    }

    fun postPendingJournalReady(
        workerAttempt: AccountDeletionWorkerAttempt,
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        pendingJournal: AccountDeletionJournal,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    ) {
        if (!stages.allowed(workerAttempt)) {
            operation.completePreparation(generation)
            return
        }
        val activityToken = callbacks.activityToken
        callbacks.post {
            if (
                callbacks.destroyed ||
                activityToken != callbacks.activityToken ||
                !operation.isCurrentPreparation(generation) ||
                !stages.allowed(workerAttempt)
            ) return@post
            if (!stateMachine().begin(pendingJournal, workerAttempt)) {
                operation.completePreparation(generation)
                retainPreparationRecovery(
                    reason = "pending_journal_conflict",
                    workerAttempt = workerAttempt,
                    announcement =
                        "삭제 요청 준비가 중단됐습니다. 상태를 확인한 뒤 다시 시도하세요.",
                )
                return@post
            }
            effects.updateUi()
            if (!operation.completePreparation(generation)) return@post
            network.startCall(
                callFactory = {
                    client.requestDeletionCall(
                        gatewayBaseUrl = pendingJournal.gatewayOrigin,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        installationId = pendingJournal.installationId,
                        accessSecret = markerRecord.accessSecret,
                        requestId = pendingJournal.requestId,
                        clientRevision = pendingJournal.clientRevision,
                    )
                },
                purpose = AccountDeletionCallPurpose.INITIAL,
            )
        }
    }

    fun postPreparationFailure(
        workerAttempt: AccountDeletionWorkerAttempt,
        generation: Long,
        @Suppress("UNUSED_PARAMETER") requestId: String,
        reason: String,
    ) {
        if (!stages.allowed(workerAttempt)) {
            operation.completePreparation(generation)
            return
        }
        val activityToken = callbacks.activityToken
        callbacks.post {
            if (
                callbacks.destroyed ||
                activityToken != callbacks.activityToken ||
                !operation.isCurrentPreparation(generation) ||
                !stages.allowed(workerAttempt)
            ) return@post
            operation.completePreparation(generation)
            retainPreparationRecovery(
                reason = reason,
                workerAttempt = workerAttempt,
                announcement =
                    "삭제 요청 준비를 완료하지 못했습니다. 삭제 확인은 보존됐으니 다시 시도하세요.",
            )
        }
    }

    fun retainPreparationRecovery(
        reason: String,
        workerAttempt: AccountDeletionWorkerAttempt,
        announcement: String,
    ): Boolean {
        if (
            !stateMachine().retainDurableConfirmationRecovery(
                workerAttempt,
            )
        ) {
            effects.keepPrivacyFenceClosed()
            return false
        }
        effects.blockIntegratedConsent()
        effects.blockReportConsent()
        effects.status("accountDeletion=recovery_required:$reason")
        effects.updateUi()
        effects.speak(announcement)
        return true
    }

}

internal interface AccountDeletionCleanupCallbacks {
    val activityToken: String
    val destroyed: Boolean
    fun post(callback: () -> Unit): Boolean
}
internal enum class AccountDeletionInstallationKeyPreparationState { REGISTERED, UNCONFIRMED, UNAVAILABLE }

internal interface AccountDeletionPreparationQueries {
    fun generalSession(): GatewayFieldSession?
    fun trustedOrigin(): String?
    fun currentReporterActor(): String?
    fun sensitiveStorageBlocked(): Boolean
    /** Capture confirmed installation/session facts before the processing fence invalidates consent. */
    fun captureInstallationKeyPreparation(session: GatewayFieldSession): (() -> CancellableNetworkCall<Unit>)? = null
}
internal interface AccountDeletionPreparationEffects {
    fun applyRuntimeFence(): Boolean
    fun keepPrivacyFenceClosed()
    fun postUiRefresh()
    fun updateUi()
    fun status(value: String)
    fun speak(value: String)
    fun invalidateSpeechStorage()
    fun resetIntegratedConsentForNewEnrollment()
    fun resetReportConsentForNewEnrollment()
    fun reapplyActorBoundConsent()
    fun resumePrivacyControlAfterStartup()
    fun blockIntegratedConsent()
    fun blockReportConsent()
    fun failClosedIntegratedConsent()
}
internal interface AccountDeletionNetworkEntry {
    fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose)
}
